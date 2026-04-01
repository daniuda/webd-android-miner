package com.webdollar.miner.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/**
 * Monitorizează starea bateriei și temperaturii.
 * Folosit de MinerService pentru safety checks.
 */
class SafetyMonitor(private val context: Context) {

    /** Returnează true dacă dispozitivul este conectat la priză (AC sau USB). */
    fun isCharging(): Boolean {
        val status = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: return false
        return status == BatteryManager.BATTERY_PLUGGED_AC ||
               status == BatteryManager.BATTERY_PLUGGED_USB ||
               status == BatteryManager.BATTERY_PLUGGED_WIRELESS
    }

    /** Returnează nivelul bateriei în procente (0-100). */
    fun batteryLevel(): Int {
        val intent = batteryIntent() ?: return 100
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return 100
        return (level * 100 / scale.toFloat()).toInt()
    }

    /**
     * Returnează temperatura bateriei în grade Celsius (0.1°C per unitate BatteryManager).
     * Exemplu: 350 = 35.0°C
     */
    fun batteryTempCelsius(): Float {
        val temp = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: return 0f
        return temp / 10f
    }

    /**
     * Returnează true dacă temperatura e sigură pentru mining (< 45°C).
     * Pragul poate fi ajustat ulterior din configurație.
     */
    fun isThermalSafe(maxTempC: Float = 45f): Boolean {
        return batteryTempCelsius() < maxTempC
    }

    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
}
