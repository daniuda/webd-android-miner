package com.webdollar.miner.data

/** Job primit de la pool backend */
data class MiningJob(
    val jobId: String,
    val height: Long,
    val target: String,
    val blockHeader: String,
    val nonceStart: Long,
    val nonceEnd: Long,
    val expireAt: Long
)

/** Rezultat auth */
data class AuthResult(
    val token: String,
    val workerId: String,
    val reward: Long,
    val confirmed: Long,
    val poolName: String,
    val poolFee: Double,
    val keyRequired: Boolean
)

/** Răspuns submit share */
data class ShareResult(
    val result: String,   // "accepted" | "stale" | "duplicate" | "invalid"
    val message: String
)

/** Statistici worker */
data class WorkerStats(
    val workerId: String,
    val walletAddress: String,
    val lastSeen: Long,
    val online: Boolean,
    val sharesAccepted: Long,
    val sharesRejected: Long,
    val sharesStale: Long,
    val rewardPending: Long,
    val rewardConfirmed: Long
)

/** Statistici globale pool */
data class PoolStats(
    val workersTotal: Int,
    val workersOnline: Int,
    val totalShares: Long,
    val poolName: String,
    val height: Long,
    val keyRequired: Boolean
)
