package com.webdollar.miner.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.webdollar.miner.data.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client HTTP pentru comunicarea cu backend-ul webd-node-modern.
 * Toate apelurile sunt blocking (de rulat pe coroutine Dispatchers.IO).
 *
 * Endpoint-uri folosite:
 *   POST /worker/auth   { walletAddress, workerId?, poolKey? }
 *   GET  /worker/job?token=...
 *   POST /worker/share  { token, jobId, nonce, hash }
 *   GET  /worker/stats?token=...
 *   GET  /pool/stats
 */
class PoolClient(private val baseUrl: String) {

    private val gson = Gson()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Autentifică / reinregistrează worker-ul. Returnează token + workerId. */
    fun auth(walletAddress: String, poolKey: String, existingWorkerId: String? = null): AuthResult {
        val body = JsonObject().apply {
            addProperty("walletAddress", walletAddress)
            if (poolKey.isNotEmpty()) addProperty("poolKey", poolKey)
            if (!existingWorkerId.isNullOrEmpty()) addProperty("workerId", existingWorkerId)
        }
        val resp = post("/worker/auth", body.toString())
        val obj  = gson.fromJson(resp, JsonObject::class.java)
        checkResult(obj)
        return AuthResult(
            token      = obj.get("token").asString,
            workerId   = obj.get("workerId").asString,
            reward     = obj.get("reward")?.asLong ?: 0L,
            confirmed  = obj.get("confirmed")?.asLong ?: 0L,
            poolName   = obj.get("poolName")?.asString ?: "",
            poolFee    = obj.get("poolFee")?.asDouble ?: 0.0,
            keyRequired = obj.get("keyRequired")?.asBoolean ?: false
        )
    }

    /** Cere un job nou de mining. */
    fun getJob(token: String): MiningJob {
        val resp = get("/worker/job?token=${encode(token)}")
        val obj  = gson.fromJson(resp, JsonObject::class.java)
        checkResult(obj)
        val j = obj.getAsJsonObject("job")
        return MiningJob(
            jobId       = j.get("jobId").asString,
            height      = j.get("height").asLong,
            target      = j.get("target").asString,
            blockHeader = j.get("blockHeader").asString,
            nonceStart  = j.get("nonceStart").asLong,
            nonceEnd    = j.get("nonceEnd").asLong,
            expireAt    = j.get("expireAt").asLong
        )
    }

    /** Trimite un share. */
    fun submitShare(token: String, jobId: String, nonce: Long, hash: String): ShareResult {
        val body = JsonObject().apply {
            addProperty("token",  token)
            addProperty("jobId",  jobId)
            addProperty("nonce",  nonce)
            addProperty("hash",   hash)
        }
        val resp = post("/worker/share", body.toString())
        val obj  = gson.fromJson(resp, JsonObject::class.java)
        return ShareResult(
            result  = obj.get("result")?.asString ?: "invalid",
            message = obj.get("message")?.asString ?: ""
        )
    }

    /** Statistici proprii worker. */
    fun getStats(token: String): WorkerStats {
        val resp = get("/worker/stats?token=${encode(token)}")
        val obj  = gson.fromJson(resp, JsonObject::class.java)
        checkResult(obj)
        return WorkerStats(
            workerId        = obj.get("workerId").asString,
            walletAddress   = obj.get("walletAddress").asString,
            lastSeen        = obj.get("lastSeen")?.asLong ?: 0L,
            online          = obj.get("online")?.asBoolean ?: false,
            sharesAccepted  = obj.get("sharesAccepted")?.asLong ?: 0L,
            sharesRejected  = obj.get("sharesRejected")?.asLong ?: 0L,
            sharesStale     = obj.get("sharesStale")?.asLong ?: 0L,
            rewardPending   = obj.get("rewardPending")?.asLong ?: 0L,
            rewardConfirmed = obj.get("rewardConfirmed")?.asLong ?: 0L
        )
    }

    /** Statistici globale pool (nu necesită autentificare). */
    fun getPoolStats(): PoolStats {
        val resp = get("/pool/stats")
        val obj  = gson.fromJson(resp, JsonObject::class.java)
        return PoolStats(
            workersTotal   = obj.get("workersTotal")?.asInt ?: 0,
            workersOnline  = obj.get("workersOnline")?.asInt ?: 0,
            totalShares    = obj.get("totalShares")?.asLong ?: 0L,
            poolName       = obj.get("poolName")?.asString ?: "",
            height         = obj.get("height")?.asLong ?: 0L,
            keyRequired    = obj.get("keyRequired")?.asBoolean ?: false
        )
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun get(path: String): String {
        val req = Request.Builder().url("$baseUrl$path").get().build()
        return execute(req)
    }

    private fun post(path: String, bodyJson: String): String {
        val body = bodyJson.toRequestBody(json)
        val req  = Request.Builder().url("$baseUrl$path").post(body).build()
        return execute(req)
    }

    private fun execute(req: Request): String {
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${resp.message}")
            return resp.body?.string() ?: throw IOException("Body gol")
        }
    }

    private fun checkResult(obj: JsonObject) {
        val r = obj.get("result")
        // result poate fi boolean true sau string "accepted" etc.
        if (r != null && r.isJsonPrimitive && r.asJsonPrimitive.isBoolean && !r.asBoolean)
            throw IOException(obj.get("message")?.asString ?: "eroare de la pool")
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
