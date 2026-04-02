package com.webdollar.miner.ui

import androidx.lifecycle.ViewModel
import com.webdollar.miner.data.MinerPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConfigState(
    val poolUrl: String = "",
    val poolKey: String = "",
    val walletAddress: String = "",
    val onlyWhenCharging: Boolean = true
)

class MainViewModel : ViewModel() {

    private val _config = MutableStateFlow(
        ConfigState(
            poolUrl = MinerPrefs.poolUrl,
            poolKey = MinerPrefs.poolKey,
            walletAddress = MinerPrefs.walletAddress,
            onlyWhenCharging = MinerPrefs.onlyWhenCharging
        )
    )
    val config: StateFlow<ConfigState> = _config.asStateFlow()

    fun setPoolUrl(v: String) {
        _config.value = _config.value.copy(poolUrl = v)
    }

    fun setPoolKey(v: String) {
        _config.value = _config.value.copy(poolKey = v)
    }

    fun setWalletAddress(v: String) {
        _config.value = _config.value.copy(walletAddress = v)
    }

    fun setOnlyWhenCharging(v: Boolean) {
        _config.value = _config.value.copy(onlyWhenCharging = v)
    }

    fun saveConfig() {
        MinerPrefs.poolUrl = _config.value.poolUrl.trim()
        MinerPrefs.poolKey = _config.value.poolKey.trim()
        MinerPrefs.walletAddress = _config.value.walletAddress.trim()
        MinerPrefs.onlyWhenCharging = _config.value.onlyWhenCharging
    }
}
