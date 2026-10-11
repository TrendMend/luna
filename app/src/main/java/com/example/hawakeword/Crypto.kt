package com.example.hawakeword

import android.util.Base64
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.SecureRandom

/**
 * mobile_app webhook encryption: NaCl/libsodium `crypto_secretbox` (XSalsa20-Poly1305),
 * implemented in pure Java so the app ships no native libraries of its own to align for 16 KB pages.
 *
 * HA's registration `secret` is 64 hex chars = a 32 byte key. The value sent is
 * base64(nonce[24] || mac[16] || ciphertext) of the JSON `data` object.
 * See homeassistant/components/mobile_app/helpers.py and webhook.py.
 */
object Crypto {
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 24
    private const val MAC_BYTES = 16
    private val random = SecureRandom()

    fun encrypt(secretHex: String, plaintext: ByteArray): String {
        val key = hexToBytes(secretHex)
        require(key.size == KEY_BYTES) { "Unexpected secret length" }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }

        // crypto_secretbox: the first 32 bytes of the XSalsa20 keystream become the Poly1305 key,
        // and the message is XORed with the keystream that follows.
        val stream = XSalsa20Engine().apply { init(true, ParametersWithIV(KeyParameter(key), nonce)) }
        val polyKey = ByteArray(KEY_BYTES)
        stream.processBytes(ByteArray(KEY_BYTES), 0, KEY_BYTES, polyKey, 0)

        val cipher = ByteArray(plaintext.size)
        stream.processBytes(plaintext, 0, plaintext.size, cipher, 0)

        val mac = ByteArray(MAC_BYTES)
        Poly1305().apply {
            init(KeyParameter(polyKey))
            update(cipher, 0, cipher.size)
            doFinal(mac, 0)
        }

        return Base64.encodeToString(nonce + mac + cipher, Base64.NO_WRAP)
    }

    /** Reverse of [encrypt]: base64(nonce || mac || ciphertext) -> plaintext. Throws if the MAC is wrong. */
    fun decrypt(secretHex: String, base64: String): ByteArray {
        val key = hexToBytes(secretHex)
        require(key.size == KEY_BYTES) { "Unexpected secret length" }
        val raw = Base64.decode(base64, Base64.DEFAULT)
        require(raw.size >= NONCE_BYTES + MAC_BYTES) { "Encrypted data too short" }

        val nonce = raw.copyOfRange(0, NONCE_BYTES)
        val mac = raw.copyOfRange(NONCE_BYTES, NONCE_BYTES + MAC_BYTES)
        val cipher = raw.copyOfRange(NONCE_BYTES + MAC_BYTES, raw.size)

        val stream = XSalsa20Engine().apply { init(false, ParametersWithIV(KeyParameter(key), nonce)) }
        val polyKey = ByteArray(KEY_BYTES)
        stream.processBytes(ByteArray(KEY_BYTES), 0, KEY_BYTES, polyKey, 0)

        val expected = ByteArray(MAC_BYTES)
        Poly1305().apply {
            init(KeyParameter(polyKey))
            update(cipher, 0, cipher.size)
            doFinal(expected, 0)
        }
        require(java.security.MessageDigest.isEqual(expected, mac)) { "Response failed authentication" }

        val plain = ByteArray(cipher.size)
        stream.processBytes(cipher, 0, cipher.size, plain, 0)
        return plain
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Invalid hex" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
