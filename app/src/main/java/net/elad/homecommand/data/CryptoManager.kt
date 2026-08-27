package net.elad.homecommand.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts short secrets using an AES-256-GCM key that never
 * leaves AndroidKeyStore.
 *
 * Ciphertext format: [1 byte IV length][IV][ciphertext], Base64-encoded.
 */
object CryptoManager {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "my_automations_secrets"
    private const val GCM_TAG_LENGTH_BITS = 128

    /** Null when encryption fails (e.g. KeyStore unavailable); callers must not persist then. */
    fun encryptOrNull(plain: String): String? =
        try {
            if (plain.isEmpty()) {
                ""
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                val iv = cipher.iv
                val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

                val out = ByteArray(1 + iv.size + encrypted.size)
                out[0] = iv.size.toByte()
                iv.copyInto(out, 1)
                encrypted.copyInto(out, 1 + iv.size)

                Base64.encodeToString(out, Base64.NO_WRAP)
            }
        } catch (_: Exception) {
            null
        }

    /** Returns null when [encoded] cannot be decrypted (e.g. corrupted input). */
    fun decryptOrNull(encoded: String): String? =
        try {
            if (encoded.isEmpty()) {
                ""
            } else {
                val data = Base64.decode(encoded, Base64.NO_WRAP)
                val ivLength = data[0].toInt()
                val iv = data.copyOfRange(1, 1 + ivLength)
                val ciphertext = data.copyOfRange(1 + ivLength, data.size)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
