package de.pvcompact.app

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional bridge to the Raspberry-Pi energy controller.
 * Expected endpoint: GET {baseUrl}/api/status
 * Unknown/missing fields are simply ignored, so the app stays compatible while
 * the Pi controller grows.
 */
class ControllerApi(private val config: ControllerConfig) {
    fun loadStatus(): ControllerStatus {
        if (config.baseUrl.isBlank()) return ControllerStatus(note = "Raspberry-Controller noch nicht eingerichtet")
        val url = config.baseUrl.trimEnd('/') + "/api/status"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 3500
            c.readTimeout = 3500
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "PVCompact/1.0 Android")
            if (config.accessToken.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${config.accessToken}")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("Controller HTTP $code")
            val j = JSONObject(body)
            return ControllerStatus(
                online = true,
                updatedAt = j.optString("updatedAt").takeIf { it.isNotBlank() }
                    ?: j.optString("updated_at").takeIf { it.isNotBlank() },
                batterySoc = number(j, "batterySoc", "battery_soc", "soc"),
                batteryPowerW = number(j, "batteryPowerW", "battery_power_w", "battery_w"),
                pvPowerW = number(j, "pvPowerW", "pv_power_w", "pv_w"),
                loadPowerW = number(j, "loadPowerW", "load_power_w", "load_w"),
                gridPowerW = number(j, "gridPowerW", "grid_power_w", "grid_w"),
                chargeCurrentA = number(j, "chargeCurrentA", "charge_current_a", "battery_current_a"),
                automationEnabled = bool(j, "automationEnabled", "automation_enabled"),
                mode = string(j, "mode", "automation_mode"),
                note = string(j, "note", "message")
            )
        } finally { c.disconnect() }
    }

    private fun number(j: JSONObject, vararg keys: String): Double? {
        for (k in keys) if (j.has(k) && !j.isNull(k)) {
            val v = j.optDouble(k, Double.NaN)
            if (v.isFinite()) return v
        }
        return null
    }

    private fun bool(j: JSONObject, vararg keys: String): Boolean? {
        for (k in keys) if (j.has(k) && !j.isNull(k)) return j.optBoolean(k)
        return null
    }

    private fun string(j: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = j.optString(k)
            if (v.isNotBlank()) return v
        }
        return null
    }
}
