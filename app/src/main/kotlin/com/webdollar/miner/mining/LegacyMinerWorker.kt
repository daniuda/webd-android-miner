package com.webdollar.miner.mining

import android.util.Log
import com.webdollar.miner.data.MinerPrefs
import com.webdollar.miner.network.LegacyPoolClient
import com.webdollar.miner.network.ParsedPoolAddress
import com.webdollar.miner.network.PosSignResult
import com.webdollar.miner.network.LegacyWork
import com.webdollar.miner.util.WalletGenerator
import de.mkammerer.argon2.Argon2Factory
import de.mkammerer.argon2.Argon2Advanced
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "LegacyMinerWorker"

/**
 * Worker pentru pool-uri legacy WebDollar (Socket.IO protocol).
 * Gestioneaza: conectare, primire work, PoS signing, PoW Argon2 mining, submit.
 */
class LegacyMinerWorker(private val parsed: ParsedPoolAddress) {

    private val _stats = MutableStateFlow(MinerStats())
    val stats: StateFlow<MinerStats> = _stats

    private var scope: CoroutineScope? = null
    private val hashCount = AtomicLong(0)
    private val currentWork = AtomicReference<LegacyWork?>(null)
    private var client: LegacyPoolClient? = null

    val isRunning: Boolean get() = scope != null

    // Argon2 params identice cu WebDollar (const_global.js)
    private val argon2: Argon2Advanced? by lazy {
        runCatching { Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2d) }.getOrNull()
    }
    private val ARGON2_SALT     = "Satoshi_is_Finney".toByteArray()
    private val ARGON2_TIME     = 2
    private val ARGON2_MEM_KIB  = 256   // 256 KiB
    private val ARGON2_PARALLEL = 2
    private val ARGON2_LEN      = 32

    fun start(threadCount: Int = 1) {
        if (isRunning) return
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        _stats.value = MinerStats(running = true)

        // Hashrate meter
        s.launch {
            while (isActive) {
                delay(1000)
                val h = hashCount.getAndSet(0)
                _stats.value = _stats.value.copy(hashrate = h)
            }
        }

        // Worker principal
        s.launch { legacyLoop(threadCount) }
        Log.i(TAG, "LegacyMinerWorker pornit → ${parsed.poolName}")
    }

    fun stop() {
        client?.disconnect()
        client = null
        scope?.cancel()
        scope = null
        _stats.value = _stats.value.copy(running = false, hashrate = 0)
        Log.i(TAG, "LegacyMinerWorker oprit")
    }

    // ─── Loop principal ──────────────────────────────────────────────────────

    private suspend fun legacyLoop(threadCount: Int) {
        val wallet = buildWallet() ?: run {
            updateError("Wallet invalid - regenerează wallet-ul")
            return
        }

        val lc = LegacyPoolClient(parsed).also { client = it }

        // callback când apar work-uri noi
        lc.onWorkReceived = { work ->
            currentWork.set(work)
            _stats.value = _stats.value.copy(currentHeight = work.height, error = "")
        }

        // Conectare
        lc.connect(wallet.address, listOf(wallet.unencodedAddress))
        updateError("Conectare la ${parsed.poolName}...")

        // Așteaptă conectarea, dar nu ieși din worker după 30s - păstrează retry automat.
        var waited = 0
        while (!lc.connected.value && currentCoroutineContext().isActive) {
            delay(500)
            waited += 500

            if (waited % 5000 == 0) {
                val lastErr = lc.error.value
                if (lastErr.isNotBlank()) {
                    updateError("Conectare la pool... $lastErr")
                } else {
                    updateError("Conectare la pool... (${waited / 1000}s)")
                }
            }

            if (waited >= 30_000) {
                val lastErr = lc.error.value
                updateError(
                    if (lastErr.isNotBlank()) {
                        "Nu s-a putut conecta în 30s, retry automat... $lastErr"
                    } else {
                        "Nu s-a putut conecta în 30s, retry automat..."
                    }
                )
                waited = 0
            }
        }

        if (!currentCoroutineContext().isActive) return

        // Cere work inițial după 1s (hello-pool durează puțin)
        delay(1000)
        lc.requestWork()

        // Loop de mining
        var idleTicks = 0
        while (currentCoroutineContext().isActive) {
            if (!lc.connected.value) {
                updateError("Reconectare la pool...")
                delay(1000)
                continue
            }

            val work = currentWork.get()
            if (work == null) {
                idleTicks++
                // Dacă nu vine work push, cere periodic explicit.
                if (idleTicks >= 10) {
                    lc.requestWork()
                    idleTicks = 0
                }
                delay(500)
                continue
            }

            idleTicks = 0

            try {
                if (work.isPow) {
                    minePoW(work, lc, wallet, threadCount)
                } else {
                    minePoS(work, lc, wallet)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Eroare mining: ${e.message}")
                delay(3_000)
            }
        }
    }

    // ─── PoS Mining ─────────────────────────────────────────────────────────

    private suspend fun minePoS(work: LegacyWork, lc: LegacyPoolClient, wallet: WalletData) {
        val t0 = System.currentTimeMillis()

        // Extrage input pentru semnare
        val signingInput = work.posSigningInput()

        // Semnează cu cheia privată ed25519
        val posSign = signPoS(signingInput, wallet.seed, wallet.publicKey, wallet.unencodedAddress)
            ?: return

        // Hash-ul block-ului (Argon2 sau dummy dacă argon2 nu e disponibil)
        val blockHash = computeBlockHash(
            height    = work.height,
            target    = work.target,
            headerPrefix = signingInput,
            nonce     = 0
        )

        val timeDiff = System.currentTimeMillis() - t0
        hashCount.incrementAndGet()

        lc.submitWork(
            work     = work,
            nonce    = 0,
            hash     = blockHash,
            posSign  = posSign,
            timeDiff = timeDiff,
            hashes   = 1,
            result   = false  // share, nu bloc gasit
        )

        _stats.value = _stats.value.copy(
            sharesAccepted = _stats.value.sharesAccepted + 1,
            lastResult = "pos-submitted"
        )
        Log.d(TAG, "PoS share trimis height=${work.height}")

        // Asteaptă work nou (pool trimite automat după work-done/answer)
        currentWork.set(null)
        delay(2_000)
    }

    // ─── PoW Mining ─────────────────────────────────────────────────────────

    private suspend fun minePoW(work: LegacyWork, lc: LegacyPoolClient, wallet: WalletData, threads: Int) {
        if (argon2 == null) {
            Log.w(TAG, "Argon2 nu e disponibil, skip PoW block height=${work.height}")
            currentWork.set(null)
            delay(500)
            return
        }

        val headerPrefix = work.posSigningInput()  // pentru PoW = same offset logic
        val targetBytes = stripLeadingZeros(work.target)
        val heightBytes = stripLeadingZeros(bigEndian4(work.height.toInt()))

        val rangePerThread = ((work.nonceEnd - work.nonceStart) / threads).coerceAtLeast(1)
        var foundNonce = -1L
        var foundHash: ByteArray? = null

        val jobs = (0 until threads).map { idx ->
            scope!!.async {
                val start = work.nonceStart + idx * rangePerThread
                val end = if (idx == threads - 1) work.nonceEnd else start + rangePerThread
                var nonce = start
                while (nonce < end && currentWork.get() == work && isActive) {
                    val hashInput = heightBytes + targetBytes + headerPrefix + bigEndian4(nonce.toInt())
                    val hash = argon2Hash(hashInput) ?: break
                    hashCount.incrementAndGet()
                    if (hashMeetsTarget(hash, work.target)) {
                        foundNonce = nonce
                        foundHash = hash
                        return@async
                    }
                    nonce++
                }
            }
        }
        jobs.forEach { it.await() }

        if (foundNonce >= 0 && foundHash != null) {
            val fh = foundHash!!
            Log.i(TAG, "PoW bloc găsit nonce=$foundNonce height=${work.height}")
            lc.submitWork(work, foundNonce, fh, null, 0, work.nonceEnd - work.nonceStart, true)
            _stats.value = _stats.value.copy(sharesAccepted = _stats.value.sharesAccepted + 1, lastResult = "pow-found")
        } else if (currentWork.get() == work) {
            // Range epuizat fara solutie - trimite raport de hashes
            val lastHash = computeBlockHash(work.height, work.target, headerPrefix, work.nonceEnd)
            lc.submitWork(work, work.nonceEnd, lastHash, null, 0, work.nonceEnd - work.nonceStart, false)
        }

        currentWork.compareAndSet(work, null)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun signPoS(
        input: ByteArray,
        seed: ByteArray,
        publicKey: ByteArray,
        unencodedAddr: ByteArray
    ): PosSignResult? = runCatching {
        val privSpec = EdDSAPrivateKeySpec(seed, EdDSANamedCurveTable.getByName("Ed25519"))
        val privKey  = EdDSAPrivateKey(privSpec)
        val engine   = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        engine.initSign(privKey)
        engine.update(input)
        val sig = engine.sign()
        PosSignResult(signature = sig, publicKey = publicKey, unencodedAddress = unencodedAddr)
    }.onFailure { Log.e(TAG, "signPoS eroare: ${it.message}") }.getOrNull()

    private fun computeBlockHash(height: Long, target: ByteArray, headerPrefix: ByteArray, nonce: Long): ByteArray {
        return try {
            val heightB = stripLeadingZeros(bigEndian4(height.toInt()))
            val targetB = stripLeadingZeros(target)
            val input = heightB + targetB + headerPrefix + bigEndian4(nonce.toInt())
            argon2Hash(input) ?: ByteArray(32)
        } catch (e: Exception) { ByteArray(32) }
    }

    private fun argon2Hash(input: ByteArray): ByteArray? = runCatching {
        argon2!!.rawHash(ARGON2_TIME, ARGON2_MEM_KIB, ARGON2_PARALLEL, input, ARGON2_SALT)
    }.getOrNull()

    private fun hashMeetsTarget(hash: ByteArray, target: ByteArray): Boolean {
        val h = hash.copyOf(32).also { it.reverse() }
        val t = target.copyOf(32).also { it.reverse() }
        for (i in 31 downTo 0) {
            val hb = h[i].toInt() and 0xFF
            val tb = t[i].toInt() and 0xFF
            if (hb < tb) return true
            if (hb > tb) return false
        }
        return true
    }

    private fun stripLeadingZeros(b: ByteArray): ByteArray {
        var start = 0
        while (start < b.size - 1 && b[start] == 0.toByte()) start++
        return b.copyOfRange(start, b.size)
    }

    private fun bigEndian4(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun updateError(msg: String) {
        _stats.value = _stats.value.copy(error = msg)
        Log.w(TAG, msg)
    }

    // ─── Wallet helpers ─────────────────────────────────────────────────────

    private data class WalletData(
        val address: String,
        val seed: ByteArray,
        val publicKey: ByteArray,
        val unencodedAddress: ByteArray
    )

    private fun buildWallet(): WalletData? {
        val secretHex = MinerPrefs.walletSecret.ifEmpty { return null }
        return runCatching {
            val gen = WalletGenerator.fromSecretHex(secretHex)
            WalletData(
                address = gen.address,
                seed = secretHex.hexToBytes(),
                publicKey = gen.publicKeyHex.hexToBytes(),
                unencodedAddress = gen.unencodedAddressHex.hexToBytes()
            )
        }.getOrNull()
    }

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        var i = 0
        while (i < length) {
            out[i / 2] = substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val r = ByteArray(size + other.size)
        copyInto(r); other.copyInto(r, size)
        return r
    }
}
