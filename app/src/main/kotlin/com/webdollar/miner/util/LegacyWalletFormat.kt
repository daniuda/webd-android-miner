package com.webdollar.miner.util

import com.google.gson.Gson

data class LegacyWalletFile(
    val version: String = "0.1",
    val address: String,
    val privateKey: String,
    val publicKey: String,
    val unencodedAddress: String? = null
)

object LegacyWalletFormat {
    private val gson = Gson()

    fun exportFromSecretHex(secretHex: String): String {
        val wallet = WalletGenerator.fromSecretHex(secretHex)
        val legacy = LegacyWalletFile(
            address = wallet.address,
            privateKey = WalletGenerator.legacyPrivateKeyHexFromSecretHex(wallet.secretHex),
            publicKey = wallet.publicKeyHex,
            unencodedAddress = wallet.unencodedAddressHex
        )
        return gson.toJson(legacy)
    }

    fun importToWallet(raw: String): GeneratedWallet {
        val text = raw.trim()
        if (text.startsWith("{")) {
            val parsed = gson.fromJson(text, LegacyWalletFile::class.java)
            if (parsed.version == "0.1") {
                val pk = parsed.privateKey.trim()
                if (pk.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                    return WalletGenerator.fromSecretHex(pk)
                }
                if (pk.matches(Regex("^[0-9a-fA-F]{138}$"))) {
                    return WalletGenerator.fromLegacyPrivateKeyHex(pk)
                }
            }
            throw IllegalArgumentException("Format JSON legacy invalid")
        }
        if (text.matches(Regex("^[0-9a-fA-F]{138}$"))) {
            return WalletGenerator.fromLegacyPrivateKeyHex(text)
        }
        if (text.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            return WalletGenerator.fromSecretHex(text)
        }
        return WalletGenerator.fromPrivateKeyWif(text)
    }
}
