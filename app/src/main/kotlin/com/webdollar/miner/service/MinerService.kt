package com.webdollar.miner.service

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.webdollar.miner.R
import com.webdollar.miner.data.MinerPrefs
import com.webdollar.miner.mining.MinerStats
import com.webdollar.miner.mining.MinerWorker
import com.webdollar.miner.network.PoolClient
import com.webdollar.miner.ui.MainActivity
import com.webdollar.miner.util.SafetyMonitor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG              = "MinerService"
private const val NOTIF_CHANNEL_ID = "webd_miner"
private const val NOTIF_ID         = 1001

class MinerService : LifecycleService() {

    private lateinit var worker: MinerWorker
    private lateinit var safety: SafetyMonitor
    private val binder = LocalBinder()
    private var isMining = false

    inner class LocalBinder : Binder() {
        fun getWorkerService(): MinerService = this@MinerService
    }

    override fun onCreate() {
        super.onCreate()
        val client = PoolClient(MinerPrefs.poolUrl)
        worker = MinerWorker(client)
        safety = SafetyMonitor(this)

        createNotificationChannel()

        // Observă stats și actualizează notificarea
        lifecycleScope.launch {
            worker.stats.collectLatest { stats ->
                updateNotification(stats)

                // Safety: oprire automată dacă nu e la priză (și setarea e activă)
                if (isMining && MinerPrefs.onlyWhenCharging && !safety.isCharging()) {
                    Log.i(TAG, "Baterie: nu e la priză — opresc mining")
                    stopMining()
                }

                if (isMining && !safety.isThermalSafe()) {
                    Log.i(TAG, "Temperatură ridicată — opresc mining")
                    stopMining()
                }

                if (isMining && safety.batteryLevel() <= 15) {
                    Log.i(TAG, "Baterie scăzută (<=15%) — opresc mining")
                    stopMining()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startMining()
            ACTION_STOP  -> { stopMining(); stopSelf() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        stopMining()
        super.onDestroy()
    }

    // ─── control ────────────────────────────────────────────────────────────

    fun startMining() {
        if (isMining) return

        // Verificare safety la start
        if (MinerPrefs.onlyWhenCharging && !safety.isCharging()) {
            Log.i(TAG, "Nu e la priză — mining nu pornit")
            updateNotification(MinerStats(running = false, error = "Asteaptă priză..."))
            return
        }

        startForeground(NOTIF_ID, buildNotification(MinerStats(running = true)))
        worker.start(MinerPrefs.threadCount)
        isMining = true
        Log.i(TAG, "Mining pornit")
    }

    fun stopMining() {
        if (!isMining) return
        worker.stop()
        isMining = false
        Log.i(TAG, "Mining oprit")
    }

    fun statsFlow(): StateFlow<MinerStats> = worker.stats

    fun isMiningRunning(): Boolean = isMining

    // ─── notificare ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID, "WebDollar Miner",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Stare mining WebDollar" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(stats: MinerStats): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MinerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val statusText = when {
            stats.error.isNotEmpty() -> stats.error
            stats.running -> "${stats.hashrate} H/s  ✓${stats.sharesAccepted}  ✗${stats.sharesRejected}"
            else -> "Oprit"
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("WebDollar Miner")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "Oprește", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(stats: MinerStats) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(stats))
    }

    companion object {
        const val ACTION_START = "com.webdollar.miner.START"
        const val ACTION_STOP  = "com.webdollar.miner.STOP"

        fun startIntent(context: Context) =
            Intent(context, MinerService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context) =
            Intent(context, MinerService::class.java).apply { action = ACTION_STOP }
    }
}
