package org.turboio.customai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys are encrypted with an AES-256-GCM key that lives in the Android Keystore (hardware-backed
 * when available, non-exportable). Only ciphertext is stored in private SharedPreferences, and backups
 * are disabled in the manifest. Keys are never placed in source code, BuildConfig, logs or the APK.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("turboio_secrets_v1", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun put(name: String, secret: String) {
        if (secret.isEmpty()) { remove(name); return }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(name, blob).apply()
    }

    fun get(name: String): String {
        val blob = prefs.getString(name, null) ?: return ""
        return try {
            val (iv, data) = blob.split(':', limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Keystore key lost (e.g. device credential reset): never fall back to plaintext.
            ""
        }
    }

    fun has(name: String): Boolean = prefs.contains(name)
    fun remove(name: String) { prefs.edit().remove(name).apply() }
    fun clearAll() { prefs.edit().clear().apply() }

    private companion object {
        const val ALIAS = "turboio_api_keys_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
