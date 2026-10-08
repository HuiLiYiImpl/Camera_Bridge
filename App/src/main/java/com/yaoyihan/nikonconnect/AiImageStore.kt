package com.yaoyihan.nikonconnect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AiImageStore(context: Context) {
    private val prefs = context.getSharedPreferences("link_ai_image", Context.MODE_PRIVATE)
    private val alias = "link_ai_image_key_v1"

    private fun activeProvider(): AiImageProvider = runCatching {
        AiImageProvider.valueOf(prefs.getString("provider", null).orEmpty())
    }.getOrDefault(AiImageProvider.DOUBAO)

    fun load(): AiProviderCatalog {
        val saved = prefs.getString("catalog_v2", null)
        if (saved != null) return runCatching { AiProviderCatalogCodec.decode(saved, ::decrypt) }
            .getOrElse { AiProviderCatalog(aiDefaultProfiles()).reconcile() }
        return aiMigrateProfiles(AiImageProvider.entries.map(::readLegacy), activeProvider())
    }

    private fun readLegacy(provider: AiImageProvider): AiImageConfig {
        val prefix = provider.name
        val encoded = prefs.getString("${prefix}_key", "").orEmpty()
        val key = if (encoded.isBlank()) "" else runCatching { decrypt(encoded) }.getOrDefault("")
        return AiImageConfig(provider, prefs.getString("${prefix}_endpoint", provider.endpoint).orEmpty(),
            prefs.getString("${prefix}_model", provider.model).orEmpty(), key)
    }

    fun save(catalog: AiProviderCatalog) {
        val encoded = AiProviderCatalogCodec.encode(catalog, ::encrypt)
        val editor = prefs.edit().putString("catalog_v2", encoded).remove("provider")
        AiImageProvider.entries.forEach {
            editor.remove("${it.name}_key").remove("${it.name}_endpoint").remove("${it.name}_model")
        }
        check(editor.commit()) { "配置保存失败" }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':')
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
