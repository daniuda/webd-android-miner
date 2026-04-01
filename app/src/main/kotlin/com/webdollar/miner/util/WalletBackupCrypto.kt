package com.webdollar.miner.util

import com.google.gson.Gson
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class WalletBackupPayload(
    val address: String,
    val secretHex: String,
    val createdAt: Long
)

object WalletBackupCrypto {
    private const val MAGIC = "WEBDWALLET1"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LEN_BITS = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    private val gson = Gson()
    private val random = SecureRandom()

    fun exportEncrypted(payload: WalletBackupPayload, passphrase: String, outFile: File) {
        require(passphrase.length >= 8) { "Parola trebuie sa aiba minim 8 caractere" }

        val plain = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)

        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain)

        val envelope = mapOf(
            "magic" to MAGIC,
            "salt" to Base64.getEncoder().encodeToString(salt),
            "iv" to Base64.getEncoder().encodeToString(iv),
            "data" to Base64.getEncoder().encodeToString(encrypted)
        )

        outFile.parentFile?.mkdirs()
        outFile.writeText(gson.toJson(envelope), StandardCharsets.UTF_8)
    }

    fun importEncrypted(file: File, passphrase: String): WalletBackupPayload {
        require(file.exists()) { "Fisier backup inexistent" }
        val obj = gson.fromJson(file.readText(StandardCharsets.UTF_8), Map::class.java)

        val magic = obj["magic"] as? String ?: throw IllegalArgumentException("Backup invalid")
        if (magic != MAGIC) throw IllegalArgumentException("Backup invalid (magic)")

        val saltB64 = obj["salt"] as? String ?: throw IllegalArgumentException("Backup invalid (salt)")
        val ivB64 = obj["iv"] as? String ?: throw IllegalArgumentException("Backup invalid (iv)")
        val dataB64 = obj["data"] as? String ?: throw IllegalArgumentException("Backup invalid (data)")

        val salt = Base64.getDecoder().decode(saltB64)
        val iv = Base64.getDecoder().decode(ivB64)
        val enc = Base64.getDecoder().decode(dataB64)

        val key = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(enc)

        return gson.fromJson(String(plain, StandardCharsets.UTF_8), WalletBackupPayload::class.java)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LEN_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}
