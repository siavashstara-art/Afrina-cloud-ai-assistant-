package com.example.afrina.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.afrina.core.domain.VaultCryptoManager
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore AES-256-GCM vault for local sensitive tokens.
 * Phase 0 stores encrypted ciphertext + IV in private SharedPreferences; keys never leave Keystore.
 */
class AndroidKeystoreVault(private val context: Context) : VaultCryptoManager {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun isHardwareVaultAvailable(): Boolean =
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            true
        }.getOrDefault(false)

    override fun hasStoredCredential(alias: String): Boolean =
        prefs.contains(keyFor(alias)) && prefs.contains(ivFor(alias))

    override fun storeEncryptedCredential(alias: String, plaintext: String): Result<Unit> =
        runCatching {
            require(plaintext.isNotBlank()) { "Credential cannot be blank" }
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

            prefs.edit()
                .putString(keyFor(alias), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(ivFor(alias), Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()
        }

    override fun readDecryptedCredentialMasked(alias: String): String? =
        runCatching {
            val encryptedB64 = prefs.getString(keyFor(alias), null) ?: return null
            val ivB64 = prefs.getString(ivFor(alias), null) ?: return null
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(ivB64, Base64.NO_WRAP))
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plain = String(
                cipher.doFinal(Base64.decode(encryptedB64, Base64.NO_WRAP)),
                StandardCharsets.UTF_8,
            )
            if (plain.length <= 6) {
                "••••••"
            } else {
                "${plain.take(3)}••••••${plain.takeLast(3)}"
            }
        }.getOrNull()

    override fun deleteCredential(alias: String) {
        prefs.edit().remove(keyFor(alias)).remove(ivFor(alias)).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun keyFor(alias: String) = "enc_$alias"
    private fun ivFor(alias: String) = "iv_$alias"

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val MASTER_KEY_ALIAS = "afrina_vault_master_aes256"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val PREFS_NAME = "afrina_encrypted_vault_store"
    }
}
