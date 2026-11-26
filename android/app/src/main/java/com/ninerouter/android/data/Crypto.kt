package com.ninerouter.android.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed AES-GCM box for the few secrets we persist locally.
 * Mirrors the "provider secrets must not be plaintext" rule from the upstream
 * project without pulling in a full SQLCipher / EncryptedSharedPreferences stack.
 */
object Crypto {
    private const val ALIAS = "ninerouter_local_key"
    private const val PREFIX = "enc1:"
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    fun box(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
        // Saves are frequent (every request and UI edit). Reuse the ciphertext
        // for an unchanged secret instead of re-running Keystore AES-GCM; the
        // IV belongs to that ciphertext, so no IV is ever reused for new data.
        synchronized(cache) { cache[plain]?.let { return it } }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + blob
        val value = PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
        synchronized(cache) { cache[plain] = value }
        return value
    }

    fun unbox(value: String): String = unboxOrNull(value).orEmpty()

    /** Null means the value was boxed but cannot be opened (Keystore loss). */
    fun unboxOrNull(value: String): String? {
        if (!value.startsWith(PREFIX)) return value
        return try {
            val packed = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, packed.copyOfRange(0, IV_BYTES)),
            )
            String(cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 128
    }
}
