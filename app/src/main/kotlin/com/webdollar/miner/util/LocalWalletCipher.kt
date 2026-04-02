package com.webdollar.miner.util

import com.google.gson.Gson
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class LocalWalletEnvelope(
    val magic: String,
    val salt: String,
    val iv: String,
    val data: String
)

object LocalWalletCipher {
    private const val MAGIC = "WEBD_LOCAL_WALLET_V1"
    private const val ITER = 120_000
    private const val KEY_BITS = 256

    private val random = SecureRandom()
    private val gson = Gson()

    fun encryptSecret(secretHex: String, passphrase: String): String {
        require(passphrase.length >= 8) { "Parola trebuie sa aiba minim 8 caractere" }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val key = derive(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val enc = cipher.doFinal(secretHex.toByteArray(Charsets.UTF_8))

        return gson.toJson(
            LocalWalletEnvelope(
                magic = MAGIC,
                salt = Base64.getEncoder().encodeToString(salt),
                iv = Base64.getEncoder().encodeToString(iv),
                data = Base64.getEncoder().encodeToString(enc)
            )
        )
    }

    fun decryptSecret(envelopeJson: String, passphrase: String): String {
        val env = gson.fromJson(envelopeJson, LocalWalletEnvelope::class.java)
        require(env.magic == MAGIC) { "Format wallet local invalid" }
        val salt = Base64.getDecoder().decode(env.salt)
        val iv = Base64.getDecoder().decode(env.iv)
        val data = Base64.getDecoder().decode(env.data)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(passphrase, salt), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(data)
        return String(plain, Charsets.UTF_8)
    }

    private fun derive(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITER, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
