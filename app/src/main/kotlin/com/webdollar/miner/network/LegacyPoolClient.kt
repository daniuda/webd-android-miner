package com.webdollar.miner.network

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "LegacyPoolClient"

/**
 * Adresa pool in formatul WebDollar legacy:
 * pool/1/1/1/SpyClub/0.0001/pubkeyHex/https:$$host:port
 */
data class ParsedPoolAddress(
    val poolName: String,
    val poolFee: Double,
    val poolPublicKeyHex: String,
    val poolUrl: String            // https://host:port
)

/**
 * Work primit de la pool-ul legacy (via Socket.IO)
 */
data class LegacyWork(
    val height: Long,
    val target: ByteArray,         // 32 bytes difficulty target
    val serialized: ByteArray,     // work.s = serialized block
    val blockId: Long,
    val timestamp: Long,
    val nonceStart: Long,
    val nonceEnd: Long,
    val isPow: Boolean             // false = PoS, true = PoW
) {
    /** Signing input pentru PoS = work.s fara header hash/pubkey/sig/addr */
    fun posSigningInput(): ByteArray {
        if (serialized.size < 129) return serialized
        val addrLen = serialized[128].toInt() and 0xFF
        val offset = 129 + addrLen
        return if (offset < serialized.size) serialized.copyOfRange(offset, serialized.size)
        else serialized
    }

    override fun equals(other: Any?) = other is LegacyWork && blockId == other.blockId
    override fun hashCode() = blockId.hashCode()
}

/**
 * Client Pool legacy WebDollar via Socket.IO.
 * Implementeaza protocolul: hello-pool → get-work → work-done.
 */
class LegacyPoolClient(private val parsed: ParsedPoolAddress) {

    private var socket: Socket? = null
    private val _work = MutableStateFlow<LegacyWork?>(null)
    val work: StateFlow<LegacyWork?> = _work

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error

    var onWorkReceived: ((LegacyWork) -> Unit)? = null

    companion object {
        // pool/1/1/1/SpyClub/0.0001/pubkeyHex/https:$$host:port
        fun parse(poolAddress: String): ParsedPoolAddress? = runCatching {
            val parts = poolAddress.trim().split("/")
            if (parts.size < 8 || parts[0] != "pool") return null
            val poolName = parts[4]
            val fee = parts[5].toDoubleOrNull() ?: 0.0
            val pubkeyHex = parts[6]
            // Restul URL-ului: parts[7..] reunit cu "/" (ex: https:$$host:port sau https:$$host:port/path)
            val rawUrl = parts.subList(7, parts.size).joinToString("/")
            val url = rawUrl.replace("$$", "//")
            ParsedPoolAddress(poolName, fee, pubkeyHex, url)
        }.getOrNull()

        /** height % 30 < 20 = PoS (intre POS_ACTIVATION=567810 si POS90_ACTIVATION=1650000) */
        fun isPoS(height: Long): Boolean = when {
            height < 567810L  -> false
            height < 1650000L -> (height % 30) < 20
            height < 2348110L -> (height % 100) < 90
            else              -> true
        }
    }

    fun connect(minerAddress: String, addresses: List<ByteArray>) {
        try {
            val (sslCtx, trustManager) = trustAllSSL()
            val okClient = OkHttpClient.Builder()
                .sslSocketFactory(sslCtx.socketFactory, trustManager as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()

            val opts = IO.Options().apply {
                // Lasă Socket.IO să facă fallback polling->websocket când websocket direct e blocat.
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 3000
                reconnectionDelayMax = 10000
                timeout = 30000
                callFactory = okClient
                webSocketFactory = okClient
            }

            val uri = URI.create(parsed.poolUrl)
            val s = IO.socket(uri, opts)
            socket = s

            s.on(Socket.EVENT_CONNECT) {
                Log.i(TAG, "Conectat la ${parsed.poolUrl}")
                _connected.tryEmit(true)
                _error.tryEmit("")
                sendHelloPool(s, minerAddress, addresses)
            }
            s.on(Socket.EVENT_DISCONNECT) {
                Log.w(TAG, "Deconectat de la pool")
                _connected.tryEmit(false)
            }
            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val msg = args.firstOrNull()?.toString() ?: "unknown"
                Log.e(TAG, "Eroare conectare: $msg")
                _error.tryEmit("Eroare conectare: $msg")
                _connected.tryEmit(false)
            }

            // Răspuns hello-pool
            s.on("mining-pool/hello-pool/answer") { args ->
                handleHelloAnswer(s, args)
            }

            // New work push de la pool
            s.on("mining-pool/new-work") { args ->
                parseAndEmitWork(args)
            }

            s.connect()
            Log.i(TAG, "Conectare la pool ${parsed.poolName} @ ${parsed.poolUrl}")
        } catch (e: Exception) {
            Log.e(TAG, "connect() eroare: ${e.message}", e)
            _error.tryEmit("connect() eroare: ${e.message}")
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
        _connected.tryEmit(false)
    }

    /**
     * Trimite munca finalizată la pool.
     * @param work   LegacyWork original
     * @param nonce  nonce găsit (0 pentru PoS)
     * @param hash   hash calculat (32 bytes)
     * @param posSign rezultate semnare PoS sau null pentru PoW
     */
    fun submitWork(
        work: LegacyWork,
        nonce: Long,
        hash: ByteArray,
        posSign: PosSignResult?,
        timeDiff: Long,
        hashes: Long,
        result: Boolean
    ) {
        val s = socket ?: return
        try {
            val w = JSONObject().apply {
                put("hash", hash)
                put("nonce", nonce.toInt())
                put("hashes", hashes.toInt())
                put("id", work.blockId.toInt())
                put("h", work.height.toInt())
                put("timeDiff", timeDiff.toInt())
                put("result", result)
                if (posSign != null) {
                    put("pos", JSONObject().apply {
                        put("timestamp", work.timestamp.toInt())
                        put("posSignature", posSign.signature)
                        put("posMinerPublicKey", posSign.publicKey)
                        put("posMinerAddress", posSign.unencodedAddress)
                    })
                }
            }
            s.emit("mining-pool/work-done", w)
            Log.d(TAG, "submitWork height=${work.height} nonce=$nonce result=$result")
        } catch (e: Exception) {
            Log.e(TAG, "submitWork eroare: ${e.message}")
        }
    }

    fun requestWork() {
        socket?.emit("mining-pool/get-work", JSONObject())
    }

    // ─── private ────────────────────────────────────────────────────────────

    private fun sendHelloPool(s: Socket, minerAddress: String, addresses: List<ByteArray>) {
        try {
            val message = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val poolPubKey = parsed.poolPublicKeyHex.hexToBytes()

            val addrHexArray = JSONArray()
            addresses.forEach { addrHexArray.put(it.toHex()) }

            val hello = JSONObject().apply {
                put("message", message)
                put("pool", poolPubKey)
                put("minerAddress", minerAddress)
                put("addresses", addrHexArray)
            }
            s.emit("mining-pool/hello-pool", hello)
            Log.d(TAG, "hello-pool trimis pentru $minerAddress")
        } catch (e: Exception) {
            Log.e(TAG, "sendHelloPool eroare: ${e.message}")
        }
    }

    private fun handleHelloAnswer(s: Socket, args: Array<Any?>) {
        try {
            val answer = args.firstOrNull() as? JSONObject ?: return
            val result = answer.optBoolean("result", false)
            if (!result) {
                val msg = answer.optString("message", "pool refuzat")
                Log.e(TAG, "hello-pool refuzat: $msg")
                _error.tryEmit("Pool refuzat: $msg")
                return
            }

            Log.i(TAG, "hello-pool acceptat: pool=${answer.optString("name")} fee=${answer.optDouble("fee")}")

            // Trimite confirmarea
            s.emit("mining-pool/hello-pool/answer/confirmation", JSONObject().apply {
                put("result", true)
            })

            // Parsează work-ul inclus în răspuns (dacă e prezent)
            if (answer.has("work") && !answer.isNull("work")) {
                val workObj = answer.getJSONObject("work")
                parseAndEmitWorkFromObject(workObj)
            }

            // Listenere suplimentare după autentificare
            s.on("mining-pool/get-work/answer") { wargs ->
                parseAndEmitWork(wargs)
            }
            s.on("mining-pool/work-done/answer") { wargs ->
                val ans = wargs.firstOrNull() as? JSONObject ?: return@on
                val newWork = ans.optJSONObject("newWork") ?: ans.optJSONObject("work")
                if (newWork != null) parseAndEmitWorkFromObject(newWork)
            }

        } catch (e: Exception) {
            Log.e(TAG, "handleHelloAnswer eroare: ${e.message}")
        }
    }

    private fun parseAndEmitWork(args: Array<Any?>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return
            val workObj = data.optJSONObject("work") ?: data
            parseAndEmitWorkFromObject(workObj)
        } catch (e: Exception) {
            Log.e(TAG, "parseWork eroare: ${e.message}")
        }
    }

    private fun parseAndEmitWorkFromObject(w: JSONObject) {
        try {
            val height = w.optLong("h", -1)
            if (height < 0) return

            val tRaw = getBytes(w, "t") ?: return
            val sRaw = getBytes(w, "s") ?: return
            val blockId = w.optLong("I", height)
            val timestamp = w.optLong("m", 0)
            val nonceStart = w.optLong("start", 0)
            val nonceEnd = w.optLong("end", 0)

            val work = LegacyWork(
                height = height,
                target = tRaw,
                serialized = sRaw,
                blockId = blockId,
                timestamp = timestamp,
                nonceStart = nonceStart,
                nonceEnd = nonceEnd,
                isPow = !isPoS(height)
            )
            _work.tryEmit(work)
            onWorkReceived?.invoke(work)
            Log.d(TAG, "Work primit height=$height isPow=${work.isPow}")
        } catch (e: Exception) {
            Log.e(TAG, "parseAndEmitWorkFromObject eroare: ${e.message}")
        }
    }

    /** Extrage byte[] dintr-un câmp JSONObject (poate fi byte[] direct sau {type:Buffer,data:[...]}) */
    private fun getBytes(obj: JSONObject, key: String): ByteArray? {
        if (!obj.has(key)) return null
        return when (val v = obj.get(key)) {
            is ByteArray -> v
            is JSONObject -> {
                // {type:"Buffer", data:[...]} sau {"_placeholder":true}
                if (v.optString("type") == "Buffer") {
                    val arr = v.optJSONArray("data") ?: return null
                    ByteArray(arr.length()) { i -> (arr.getInt(i) and 0xFF).toByte() }
                } else null
            }
            is JSONArray -> ByteArray(v.length()) { i -> (v.getInt(i) and 0xFF).toByte() }
            else -> null
        }
    }

    /** SSL trust-all pentru pool-uri cu certificate self-signed */
    private fun trustAllSSL(): Pair<SSLContext, X509TrustManager> {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        return Pair(ctx, tm)
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = trim().lowercase()
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

/** Rezultatul semnării PoS */
data class PosSignResult(
    val signature: ByteArray,      // 64 bytes ed25519 signature
    val publicKey: ByteArray,      // 32 bytes ed25519 public key
    val unencodedAddress: ByteArray // 20 bytes RIPEMD160(SHA256(pubKey))
)
