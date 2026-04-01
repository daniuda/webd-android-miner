package com.webdollar.miner.mining

import com.webdollar.miner.data.MiningJob
import java.security.MessageDigest

/**
 * Hash engine CPU-only (Kotlin/JVM) folosind SHA-256.
 * WebDollar folosește argon2 pentru PoW real — acest hasher e un placeholder
 * funcțional pentru testare end-to-end a fluxului job→share.
 * Înlocuiește cu argon2 JNI (pasul 12 din plan) când throughput-ul contează.
 *
 * Returnează null dacă niciun nonce din plajă nu satisface target-ul sau
 * dacă job-ul expiră în timp ce hashăm.
 */
object HashEngine {

    /**
     * Caută un nonce în [job.nonceStart, job.nonceEnd) al cărui hash satisface target.
     *
     * @param job       job-ul curent
     * @param onHash    callback apelat pentru fiecare hash calculat (pentru metrici hashrate)
     * @param isCancelled  funcție de anulare — returnează true când trebuie oprit
     * @return Pair(nonce, hashHex) dacă găsit, null altfel
     */
    fun mineRange(
        job: MiningJob,
        onHash: () -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Pair<Long, String>? {

        val header   = job.blockHeader.toByteArray(Charsets.UTF_8)
        val sha256   = MessageDigest.getInstance("SHA-256")
        val targetZeros = targetLeadingZeros(job.target)

        var nonce = job.nonceStart
        while (nonce < job.nonceEnd) {
            if (isCancelled()) return null
            if (System.currentTimeMillis() > job.expireAt) return null

            val input = header + longToBytes(nonce)
            val hash  = sha256.digest(sha256.digest(input))   // double-SHA256
            val hashHex = hash.toHex()

            onHash()

            if (meetsTarget(hashHex, targetZeros)) {
                return Pair(nonce, hashHex)
            }

            nonce++
        }
        return null
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun targetLeadingZeros(target: String): Int {
        // Target = hash-ul blocului anterior (hex). Folosim jumătate din zerouri ca difficulty.
        val zeros = target.takeWhile { it == '0' }.length
        return maxOf(1, zeros / 2)
    }

    private fun meetsTarget(hashHex: String, requiredZeros: Int): Boolean {
        return hashHex.take(requiredZeros).all { it == '0' }
    }

    private fun longToBytes(v: Long): ByteArray {
        return byteArrayOf(
            (v ushr 56).toByte(), (v ushr 48).toByte(),
            (v ushr 40).toByte(), (v ushr 32).toByte(),
            (v ushr 24).toByte(), (v ushr 16).toByte(),
            (v ushr  8).toByte(), v.toByte()
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
