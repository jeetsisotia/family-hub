package com.familyhub.app.core.ai

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
 * Each person's own Gemini API key (BYOK), encrypted with an Android Keystore key that never leaves this phone.
 * Stored per signed-in user, so another family member signing in on the same phone cannot use it.
 * Never logged, synced to Firestore, put in backups (excluded in backup_rules.xml and data_extraction_rules.xml)
 * or sent anywhere except to Google's Gemini API.
 */
object KeyVault {
    private const val ALIAS = "familyhub_ai_key_v1"
    private const val PREFS = "ai_key_vault"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun slot(uid: String) = "k_" + uid.hashCode().toString(16)

    fun save(ctx: Context, uid: String, apiKey: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs(ctx).edit().putString(slot(uid), packed).apply()
    }

    /** The saved key, or null. A key that can no longer be read (for example after a restore) is removed. */
    fun load(ctx: Context, uid: String): String? {
        if (uid.isBlank()) return null
        val packed = prefs(ctx).getString(slot(uid), null) ?: return null
        return try {
            val (iv, ct) = packed.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            clear(ctx, uid)
            null
        }
    }

    fun clear(ctx: Context, uid: String) {
        prefs(ctx).edit().remove(slot(uid)).apply()
    }

    /** "AIza…x7Qk" for showing which key is connected without revealing it. */
    fun masked(key: String): String = if (key.length < 10) "••••" else key.take(4) + "…" + key.takeLast(4)
}
