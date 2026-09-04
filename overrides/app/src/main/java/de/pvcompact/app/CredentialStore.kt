package de.pvcompact.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("pvcompact_secure", Context.MODE_PRIVATE)
    private val forecastPrefs = context.getSharedPreferences("pvcompact_forecast", Context.MODE_PRIVATE)
    private val octopusPrefs = context.getSharedPreferences("pvcompact_octopus", Context.MODE_PRIVATE)
    private val alias = "pvcompact_api_key"

    fun hasCredentials(): Boolean = getSystemId().isNotBlank() && getApiKey().isNotBlank()

    fun getSystemId(): String = prefs.getString("system_id", "") ?: ""

    fun save(systemId: String, apiKey: String) {
        val encrypted = encrypt(apiKey.trim())
        prefs.edit()
            .putString("system_id", systemId.trim())
            .putString("api_iv", encrypted.first)
            .putString("api_data", encrypted.second)
            .commit()
    }

    fun getApiKey(): String = decrypt(
        prefs.getString("api_iv", null),
        prefs.getString("api_data", null)
    )

    fun clearCredentials() {
        prefs.edit().clear().commit()
    }

    fun saveForecastConfig(config: ForecastConfig) {
        val keyPair = if (config.forecastSolarApiKey.isBlank()) null else encrypt(config.forecastSolarApiKey.trim())
        val editor = forecastPrefs.edit()
            .putString("lat", config.latitude.toString())
            .putString("lon", config.longitude.toString())
            .putInt("tilt", config.tilt)
            .putInt("azimuth", config.azimuth)
            .putString("kwp", config.kwp.toString())
            .putString("pr", config.performanceRatio.toString())
        if (keyPair == null) {
            editor.remove("fs_iv").remove("fs_data")
        } else {
            editor.putString("fs_iv", keyPair.first).putString("fs_data", keyPair.second)
        }
        editor.commit()
    }

    fun getForecastConfig(): ForecastConfig? {
        val lat = forecastPrefs.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = forecastPrefs.getString("lon", null)?.toDoubleOrNull() ?: return null
        val kwp = forecastPrefs.getString("kwp", null)?.toDoubleOrNull() ?: return null
        return ForecastConfig(
            latitude = lat,
            longitude = lon,
            tilt = forecastPrefs.getInt("tilt", 30),
            azimuth = forecastPrefs.getInt("azimuth", 0),
            kwp = kwp,
            performanceRatio = forecastPrefs.getString("pr", "0.85")?.toDoubleOrNull() ?: 0.85,
            forecastSolarApiKey = decrypt(
                forecastPrefs.getString("fs_iv", null),
                forecastPrefs.getString("fs_data", null)
            )
        )
    }

    fun clearForecastConfig() {
        forecastPrefs.edit().clear().commit()
    }

    fun saveOctopusConfig(config: OctopusConfig) {
        val api = config.apiKey.trim().takeIf { it.isNotBlank() }?.let(::encrypt)
        val refresh = config.refreshToken.trim().takeIf { it.isNotBlank() }?.let(::encrypt)
        val editor = octopusPrefs.edit()
            .putString("account", config.accountNumber.trim())
            .putString("cheap_start", config.cheapStart)
            .putString("cheap_end", config.cheapEnd)
            .putString("cheap_price", config.cheapPriceCents.toString())
            .putString("normal_price", config.normalPriceCents.toString())
        if (api == null) editor.remove("api_iv").remove("api_data")
        else editor.putString("api_iv", api.first).putString("api_data", api.second)
        if (refresh == null) editor.remove("refresh_iv").remove("refresh_data")
        else editor.putString("refresh_iv", refresh.first).putString("refresh_data", refresh.second)
        editor.commit()
    }

    fun updateOctopusRefreshToken(token: String) {
        if (token.isBlank()) return
        val encrypted = encrypt(token)
        octopusPrefs.edit()
            .putString("refresh_iv", encrypted.first)
            .putString("refresh_data", encrypted.second)
            .commit()
    }

    fun getOctopusConfig(): OctopusConfig {
        return OctopusConfig(
            accountNumber = octopusPrefs.getString("account", "") ?: "",
            apiKey = decrypt(octopusPrefs.getString("api_iv", null), octopusPrefs.getString("api_data", null)),
            refreshToken = decrypt(octopusPrefs.getString("refresh_iv", null), octopusPrefs.getString("refresh_data", null)),
            cheapStart = octopusPrefs.getString("cheap_start", "00:00") ?: "00:00",
            cheapEnd = octopusPrefs.getString("cheap_end", "05:00") ?: "05:00",
            cheapPriceCents = octopusPrefs.getString("cheap_price", "19.0")?.toDoubleOrNull() ?: 19.0,
            normalPriceCents = octopusPrefs.getString("normal_price", "29.0")?.toDoubleOrNull() ?: 29.0
        )
    }

    fun clearOctopusConfig() {
        octopusPrefs.edit().clear().commit()
    }

    fun clearAll() {
        prefs.edit().clear().commit()
        forecastPrefs.edit().clear().commit()
        octopusPrefs.edit().clear().commit()
    }

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(ivText: String?, dataText: String?): String {
        return try {
            val iv = ivText ?: return ""
            val data = dataText ?: return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
