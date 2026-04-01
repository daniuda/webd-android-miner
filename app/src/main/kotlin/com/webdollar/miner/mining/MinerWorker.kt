package com.webdollar.miner.mining

import android.util.Log
import com.webdollar.miner.data.MinerPrefs
import com.webdollar.miner.data.MiningJob
import com.webdollar.miner.network.PoolClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "MinerWorker"

data class MinerStats(
    val running: Boolean = false,
    val hashrate: Long = 0L,          // H/s (actualizat la fiecare secundă)
    val sharesAccepted: Long = 0L,
    val sharesRejected: Long = 0L,
    val sharesStale: Long = 0L,
    val rewardPending: Long = 0L,
    val currentHeight: Long = 0L,
    val lastResult: String = "",
    val error: String = ""
)

/**
 * Orchestrează loop-ul de mining pe N thread-uri (coroutines).
 * Ciclu: auth → getJob → hash(thread pool) → submitShare → repeat.
 */
class MinerWorker(private val client: PoolClient) {

    private val _stats = MutableStateFlow(MinerStats())
    val stats: StateFlow<MinerStats> = _stats

    private var scope: CoroutineScope? = null
    private val hashCount = AtomicLong(0)
    private var hashrateJob: Job? = null

    val isRunning: Boolean get() = scope != null

    // ─── start / stop ───────────────────────────────────────────────────────

    fun start(threadCount: Int = 1) {
        if (isRunning) return
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        _stats.value = MinerStats(running = true)

        // hashrate meter
        hashrateJob = s.launch {
            while (isActive) {
                delay(1000)
                val h = hashCount.getAndSet(0)
                _stats.value = _stats.value.copy(hashrate = h)
            }
        }

        // worker threads
        repeat(threadCount) { idx ->
            s.launch { workerLoop(idx) }
        }
        Log.i(TAG, "MinerWorker pornit cu $threadCount thread(uri)")
    }

    fun stop() {
        scope?.cancel()
        scope = null
        hashrateJob = null
        _stats.value = _stats.value.copy(running = false, hashrate = 0)
        Log.i(TAG, "MinerWorker oprit")
    }

    // ─── worker loop ────────────────────────────────────────────────────────

    private suspend fun workerLoop(threadIdx: Int) {
        var token  = MinerPrefs.workerToken
        var workerId = MinerPrefs.workerId.ifEmpty { null }
        val wallet = MinerPrefs.walletAddress
        val poolKey = MinerPrefs.poolKey

        // Auth (sau re-auth dacă token lipsă)
        if (token.isEmpty()) {
            token = runCatching {
                val r = withContext(Dispatchers.IO) { client.auth(wallet, poolKey, workerId) }
                MinerPrefs.workerToken = r.token
                MinerPrefs.workerId    = r.workerId
                workerId = r.workerId
                r.token
            }.getOrElse {
                updateError("Auth eșuat: ${it.message}")
                delay(10_000)
                return
            }
        }

        // Mining loop
        while (currentCoroutineContext().isActive) {
            try {
                if (token.isEmpty()) {
                    val r = withContext(Dispatchers.IO) { client.auth(wallet, poolKey, workerId) }
                    token = r.token
                    workerId = r.workerId
                    MinerPrefs.workerToken = r.token
                    MinerPrefs.workerId = r.workerId
                }

                // 1. Cere job
                val job: MiningJob = withContext(Dispatchers.IO) { client.getJob(token) }
                _stats.value = _stats.value.copy(currentHeight = job.height, error = "")
                Log.d(TAG, "[$threadIdx] Job primit: height=${job.height} nonces=[${job.nonceStart},${job.nonceEnd})")

                // 2. Hash
                val loopCtx = currentCoroutineContext()
                val found = HashEngine.mineRange(
                    job  = job,
                    onHash = { hashCount.incrementAndGet() },
                    isCancelled = { !loopCtx.isActive }
                )

                // 3. Submit
                if (found != null) {
                    val (nonce, hash) = found
                    Log.d(TAG, "[$threadIdx] Soluție găsită nonce=$nonce hash=$hash")
                    val shareResult = withContext(Dispatchers.IO) {
                        client.submitShare(token, job.jobId, nonce, hash)
                    }
                    applyShareResult(shareResult.result)
                    Log.i(TAG, "[$threadIdx] Share ${shareResult.result}: ${shareResult.message}")

                    // Refresh stats de la backend după un share accepted
                    if (shareResult.result == "accepted") {
                        runCatching {
                            val s = withContext(Dispatchers.IO) { client.getStats(token) }
                            _stats.value = _stats.value.copy(rewardPending = s.rewardPending)
                        }
                    }
                }
                // Dacă nu s-a găsit soluție, cerem job nou imediat

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "[$threadIdx] Eroare loop: ${e.message}")
                // Re-auth dacă pare token invalid (401 sau mesaj relevant)
                if (e.message?.contains("401") == true || e.message?.contains("token") == true) {
                    MinerPrefs.workerToken = ""
                    token = ""
                    updateError("Re-autentificare...")
                }
                delay(5_000)
            }
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun applyShareResult(result: String) {
        _stats.value = when (result) {
            "accepted"  -> _stats.value.copy(sharesAccepted = _stats.value.sharesAccepted + 1, lastResult = "accepted")
            "stale"     -> _stats.value.copy(sharesStale    = _stats.value.sharesStale + 1,    lastResult = "stale")
            else        -> _stats.value.copy(sharesRejected = _stats.value.sharesRejected + 1, lastResult = result)
        }
    }

    private fun updateError(msg: String) {
        _stats.value = _stats.value.copy(error = msg)
        Log.w(TAG, msg)
    }
}
