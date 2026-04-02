package com.webdollar.miner.util

import android.util.Base64
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

data class GeneratedWallet(
    val address: String,          // WIF base64 address (ex: "WEBD$...")
    val secretHex: String,        // 64 hex chars = 32 bytes seed ed25519
    val publicKeyHex: String,     // 64 hex chars = 32 bytes public key
    val unencodedAddressHex: String // 40 hex chars = 20 bytes RIPEMD160(SHA256(pubKey))
)

/**
 * Generator wallet WebDollar corect.
 * Derivare: seed(32b) → ed25519 pubkey(32b) → RIPEMD160(SHA256(pubKey)) → WIF base64
 */
object WalletGenerator {

    private val rng = SecureRandom()
    private val CURVE = EdDSANamedCurveTable.getByName("Ed25519")

    // Constante WIF WebDollar (din const_global.js)
    private val WIF_PREFIX   = byteArrayOf(0x58.toByte(), 0x40.toByte(), 0x43.toByte(), 0xFE.toByte()) // "WEBD"
    private val WIF_VERSION  = byteArrayOf(0x00.toByte())
    private val WIF_SUFFIX   = byteArrayOf(0xFF.toByte())

    fun generate(): GeneratedWallet {
        val seed = ByteArray(32)
        rng.nextBytes(seed)
        return fromSeed(seed)
    }

    fun fromSecretHex(secretHex: String): GeneratedWallet {
        val clean = secretHex.trim().lowercase()
        require(clean.matches(Regex("^[0-9a-f]{64}$"))) { "Secret invalid: trebuie 64 caractere hex" }
        return fromSeed(clean.hexToBytes())
    }

    fun privateKeyWifFromSecretHex(secretHex: String): String {
        val seed = secretHex.trim().lowercase().hexToBytes()
        require(seed.size == 32) { "Secret invalid" }
        val versionAndKey = byteArrayOf(0x80.toByte()) + seed
        val checksum = sha256d(versionAndKey).copyOfRange(0, 4)
        val raw = versionAndKey + checksum
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    /**
     * Format legacy din fișiere .webd:
     * privateKey hex = 0x80 + (seed32 + pub32) + checksum4
     */
    fun legacyPrivateKeyHexFromSecretHex(secretHex: String): String {
        val seed = secretHex.trim().lowercase().hexToBytes()
        require(seed.size == 32) { "Secret invalid" }
        val pub = publicKeyBytesFromSeed(seed)
        val key64 = seed + pub
        val body = byteArrayOf(0x80.toByte()) + key64
        val checksum = sha256d(body).copyOfRange(0, 4)
        return (body + checksum).toHex()
    }

    fun fromLegacyPrivateKeyHex(privateKeyHex: String): GeneratedWallet {
        val raw = privateKeyHex.trim().lowercase().hexToBytes()
        require(raw.size == 69) { "privateKey legacy invalid" }
        require(raw[0] == 0x80.toByte()) { "privateKey legacy prefix invalid" }

        val body = raw.copyOfRange(0, 65)
        val cks = raw.copyOfRange(65, 69)
        val expect = sha256d(body).copyOfRange(0, 4)
        require(cks.contentEquals(expect)) { "privateKey legacy checksum invalid" }

        val key64 = raw.copyOfRange(1, 65)
        val seed = key64.copyOfRange(0, 32)
        return fromSeed(seed)
    }

    fun fromPrivateKeyWif(privateKeyWif: String): GeneratedWallet {
        val raw = Base64.decode(privateKeyWif.trim(), Base64.DEFAULT)
        require(raw.size == 37) { "privateKeyWIF invalid" }
        require(raw[0] == 0x80.toByte()) { "privateKeyWIF prefix invalid" }

        val body = raw.copyOfRange(0, 33)
        val cks = raw.copyOfRange(33, 37)
        val expect = sha256d(body).copyOfRange(0, 4)
        require(cks.contentEquals(expect)) { "privateKeyWIF checksum invalid" }

        val seed = raw.copyOfRange(1, 33)
        return fromSeed(seed)
    }

    fun fromSeed(seed: ByteArray): GeneratedWallet {
        require(seed.size == 32) { "Seed trebuie să aibă 32 bytes" }

        // 1. Derivă cheia publică ed25519
        val privSpec = EdDSAPrivateKeySpec(seed, CURVE)
        val privKey  = EdDSAPrivateKey(privSpec)
        val pubKey   = privKey.abyte  // 32 bytes public key

        // 2. Unencoded address = RIPEMD160(SHA256(pubKey))
        val sha256    = MessageDigest.getInstance("SHA-256").digest(pubKey)
        val unencodedAddr = Ripemd160.hash(sha256)  // 20 bytes

        // 3. WIF encode: PREFIX + VERSION + unencodedAddr + checksum(4) + SUFFIX
        val versionAndAddr = WIF_VERSION + unencodedAddr
        val checksum = sha256d(versionAndAddr).copyOfRange(0, 4)
        val wifBytes = WIF_PREFIX + versionAndAddr + checksum + WIF_SUFFIX  // 30 bytes

        val address = Base64.encodeToString(wifBytes, Base64.NO_WRAP)

        return GeneratedWallet(
            address = address,
            secretHex = seed.toHex(),
            publicKeyHex = pubKey.toHex(),
            unencodedAddressHex = unencodedAddr.toHex()
        )
    }

    /** Returnează cheia publică ed25519 (32 bytes) pentru un seed */
    fun publicKeyBytesFromSeed(seed: ByteArray): ByteArray {
        val privSpec = EdDSAPrivateKeySpec(seed, CURVE)
        return EdDSAPrivateKey(privSpec).abyte
    }

    /** SHA256(SHA256(data)) */
    fun sha256d(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }

    fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        var i = 0
        while (i < length) {
            out[i / 2] = substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(size + other.size)
        copyInto(result)
        other.copyInto(result, size)
        return result
    }
}
