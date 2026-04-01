package com.webdollar.miner

import android.app.Application
import com.webdollar.miner.data.MinerPrefs

class MinerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MinerPrefs.init(this)
    }
}
