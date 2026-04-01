package com.webdollar.miner.util

import java.security.MessageDigest
import java.security.SecureRandom

data class GeneratedWallet(
    val address: String,
    val secretHex: String
)

/**
 * Generator wallet local pentru aplicația miner.
 * Creează o cheie secretă random și o adresă derivată din hash.
 */
object WalletGenerator {

    private val rng = SecureRandom()

    fun generate(): GeneratedWallet {
        val secret = ByteArray(32)
        rng.nextBytes(secret)

        val address = addressFromSecret(secret)

        return GeneratedWallet(
            address = address,
            secretHex = secret.toHex()
        )
    }

    fun fromSecretHex(secretHex: String): GeneratedWallet {
        val clean = secretHex.trim().lowercase()
        require(clean.matches(Regex("^[0-9a-f]{64}$"))) { "Secret invalid: trebuie 64 caractere hex" }
        val secret = clean.hexToBytes()
        val address = addressFromSecret(secret)
        return GeneratedWallet(address = address, secretHex = clean)
    }

    private fun addressFromSecret(secret: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(secret)
        return "WEBD_" + digest.copyOfRange(0, 20).toHex()
    }

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        var i = 0
        while (i < length) {
            out[i / 2] = substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
