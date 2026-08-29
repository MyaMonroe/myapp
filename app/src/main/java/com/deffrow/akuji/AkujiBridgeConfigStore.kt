package com.deffrow.akuji

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the AKUJI bridge endpoint and bearer token in app-private storage.
 * The bearer token is encrypted with a non-exportable Android Keystore key.
 */
class AkujiBridgeConfigStore(context: Context) {
    data class BridgeConfig(
        val baseUrl: String,
        val bearerToken: String,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(baseUrl: String, bearerToken: String) {
        val normalizedUrl = normalizeBaseUrl(baseUrl)
        val token = bearerToken.trim()
        require(token.isNotBlank()) { "Bridge bearer token cannot be empty." }

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString(KEY_BASE_URL, normalizedUrl)
            .putString(KEY_TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_TOKEN_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): BridgeConfig? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.trim().orEmpty()
        val ivText = prefs.getString(KEY_TOKEN_IV, null)?.trim().orEmpty()
        val cipherText = prefs.getString(KEY_TOKEN_CIPHERTEXT, null)?.trim().orEmpty()
        if (baseUrl.isBlank() || ivText.isBlank() || cipherText.isBlank()) return null

        return runCatching {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val encrypted = Base64.decode(cipherText, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val token = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            BridgeConfig(baseUrl = normalizeBaseUrl(baseUrl), bearerToken = token)
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun normalizeBaseUrl(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.isNotBlank()) { "Bridge URL cannot be empty." }

        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "AKUJI bridge must use HTTPS."
        }
        require(!uri.host.isNullOrBlank()) { "AKUJI bridge URL must include a valid host." }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "AKUJI bridge URL cannot contain credentials, a query, or a fragment."
        }
        return value
    }

    private companion object {
        const val PREFS_NAME = "akuji_bridge_config"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN_IV = "token_iv"
        const val KEY_TOKEN_CIPHERTEXT = "token_ciphertext"
        const val KEY_ALIAS = "akuji_bridge_config_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
