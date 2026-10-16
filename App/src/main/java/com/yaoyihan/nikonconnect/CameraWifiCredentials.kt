package com.yaoyihan.nikonconnect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps camera hotspot passwords encrypted with a device-bound Android Keystore key. */
object CameraWifiCredentials {
    private const val ALIAS = "camera_bridge_wifi"
    private const val PREFS = "camera_wifi_credentials"

    fun list(context: Context): List<String> = records(context).mapNotNull {
        it.optString("ssid").takeIf(String::isNotBlank)
    }.distinct()

    fun isOpen(context: Context, ssid: String): Boolean =
        records(context).firstOrNull { it.optString("ssid") == ssid }?.optBoolean("open", false) == true

    fun brand(context: Context, ssid: String): CameraBrand? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("brand:$ssid", null)?.let { stored -> CameraBrand.entries.firstOrNull { it.name == stored && it.available } }

    fun rememberBrand(context: Context, ssid: String, brand: CameraBrand) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("brand:$ssid", brand.name).apply()
    }

    fun save(context: Context, ssid: String, password: String, open: Boolean = password.isEmpty()) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        val entries = records(context).filterNot { it.optString("ssid") == ssid }
        val updated = JSONArray().apply {
            put(JSONObject().put("ssid", ssid).put("password", encoded).put("open", open))
            entries.forEach(::put)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("entries", updated.toString())
            .remove("ssid")
            .remove("password")
            .apply()
    }

    fun load(context: Context, ssid: String): String? = runCatching {
        val encoded = records(context).firstOrNull { it.optString("ssid") == ssid }?.optString("password") ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    fun remove(context: Context, ssid: String) {
        val updated = JSONArray().apply {
            records(context).filterNot { it.optString("ssid") == ssid }.forEach(::put)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("brand:$ssid")
            .putString("entries", updated.toString())
            .remove("ssid")
            .remove("password")
            .apply()
    }

    private fun records(context: Context): List<JSONObject> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("entries", null)
        if (stored != null) return runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull(array::optJSONObject)
        }.getOrDefault(emptyList())
        val legacySsid = prefs.getString("ssid", null).orEmpty()
        val legacyPassword = prefs.getString("password", null)
        return if (legacySsid.isNotBlank() && legacyPassword != null) {
            listOf(JSONObject().put("ssid", legacySsid).put("password", legacyPassword))
        } else emptyList()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }
}
