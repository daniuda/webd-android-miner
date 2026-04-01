package com.webdollar.miner.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.webdollar.miner.data.MinerPrefs
import com.webdollar.miner.service.MinerService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && MinerPrefs.autoStart) {
            ContextCompat.startForegroundService(context, MinerService.startIntent(context))
        }
    }
}
