package com.webdollar.miner.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Configurație persistată în SharedPreferences.
 * Nu se stochează nicio informație despre blockchain; doar config pool + worker identity.
 */
object MinerPrefs {
    private const val PREFS_NAME = "webd_miner_prefs"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var poolUrl: String
        get() = prefs.getString("pool_url", "http://192.168.1.100:3001") ?: "http://192.168.1.100:3001"
        set(v) = prefs.edit().putString("pool_url", v).apply()

    var walletAddress: String
        get() = prefs.getString("wallet_address", "") ?: ""
        set(v) = prefs.edit().putString("wallet_address", v).apply()

    /** Secret local al wallet-ului generat în app (hex). */
    var walletSecret: String
        get() = prefs.getString("wallet_secret", "") ?: ""
        set(v) = prefs.edit().putString("wallet_secret", v).apply()

    /** Cheie de autentificare pool (shared key) */
    var poolKey: String
        get() = prefs.getString("pool_key", "") ?: ""
        set(v) = prefs.edit().putString("pool_key", v).apply()

    /** workerId recuperat de la backend după auth */
    var workerId: String
        get() = prefs.getString("worker_id", "") ?: ""
        set(v) = prefs.edit().putString("worker_id", v).apply()

    /** token de sesiune (refresh la fiecare auth) */
    var workerToken: String
        get() = prefs.getString("worker_token", "") ?: ""
        set(v) = prefs.edit().putString("worker_token", v).apply()

    /** Număr de thread-uri de mining (1..4) */
    var threadCount: Int
        get() = prefs.getInt("thread_count", 1)
        set(v) = prefs.edit().putInt("thread_count", v.coerceIn(1, 4)).apply()

    /** Mining numai când e conectat la priză */
    var onlyWhenCharging: Boolean
        get() = prefs.getBoolean("only_charging", true)
        set(v) = prefs.edit().putBoolean("only_charging", v).apply()

    /** Auto-start la boot */
    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", false)
        set(v) = prefs.edit().putBoolean("auto_start", v).apply()
}
