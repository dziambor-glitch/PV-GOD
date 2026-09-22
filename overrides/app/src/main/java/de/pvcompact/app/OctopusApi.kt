package de.pvcompact.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Octopus Energy DE / Kraken integration for PV Compact 1.0.
 *
 * Primary auth methods are API key or refresh token. Email/password auth is
 * deliberately not used because Kraken deprecated those GraphQL input fields.
 * The local Octopus Go time window remains usable even when no API credential
 * is configured, while Smart-Meter import needs authenticated access.
 */
class OctopusApi(private val config: OctopusConfig) {
    private val endpoint = "https://api.oeg-kraken.energy/v1/graphql/"
    private val berlin = ZoneId.of("Europe/Berlin")

    private data class AuthResult(val token: String, val refreshToken: String?)
    private data class ImportResult(
        val totalKwh: Double,
        val date: String,
        val cheapKwh: Double,
        val normalKwh: Double,
        val costEuro: Double
    )

    fun load(): OctopusData {
        val localRates = localGoRates()
        val current = localGoPrice(LocalTime.now(berlin))
        val hasAuth = config.apiKey.isNotBlank() || config.refreshToken.isNotBlank()
        if (!hasAuth || config.accountNumber.isBlank()) {
            val note = when {
                config.accountNumber.isBlank() -> "Für Smart-Meter-Daten noch die Octopus-Kundennummer eintragen. Der Go-Tarif wird lokal angezeigt."
                else -> "Für Smart-Meter-Daten API-Key oder Refresh-Token eintragen. Der Go-Tarif wird lokal angezeigt."
            }
            return OctopusData(localRates, current, "Octopus Go · lokaler Tarif", note)
        }

        return try {
            val auth = obtainToken()
            val import = runCatching { loadImport(auth.token) }.getOrNull()
            OctopusData(
                rates = localRates,
                currentPriceCents = current,
                source = "Octopus Smart Meter + Go-Tarif",
                note = if (import == null) "Anmeldung erfolgreich, aber aktuell keine Smart-Meter-Verbrauchswerte verfügbar." else null,
                netImportKwh = import?.totalKwh,
                netImportDate = import?.date,
                cheapImportKwh = import?.cheapKwh,
                normalImportKwh = import?.normalKwh,
                estimatedCostEuro = import?.costEuro,
                refreshedToken = auth.refreshToken
            )
        } catch (e: Exception) {
            OctopusData(
                rates = localRates,
                currentPriceCents = current,
                source = "Octopus Go · lokaler Tarif",
                note = "Octopus API: ${e.message?.take(180) ?: "unbekannter Fehler"}"
            )
        }
    }

    private fun obtainToken(): AuthResult {
        val input = JSONObject()
        when {
            config.refreshToken.isNotBlank() -> input.put("refreshToken", config.refreshToken)
            config.apiKey.isNotBlank() -> input.put("APIKey", config.apiKey)
            else -> throw IllegalStateException("Kein Octopus API-Key oder Refresh-Token hinterlegt")
        }
        val query = "mutation ObtainKrakenToken(${ '$' }input: ObtainJSONWebTokenInput!) { obtainKrakenToken(input: ${ '$' }input) { token refreshToken refreshExpiresIn } }"
        val payload = JSONObject().put("query", query).put("variables", JSONObject().put("input", input))
        val root = post(payload, null)
        throwGraphQlErrors(root)
        val auth = root.optJSONObject("data")?.optJSONObject("obtainKrakenToken")
            ?: throw IllegalStateException("Keine Kraken-Authentifizierungsantwort")
        val token = auth.optString("token")
        if (token.isBlank()) throw IllegalStateException("Kraken hat keinen Token geliefert")
        val refresh = auth.optString("refreshToken").takeIf { it.isNotBlank() }
        return AuthResult(token, refresh)
    }

    private fun loadImport(token: String): ImportResult? {
        val today = LocalDate.now(berlin)
        val start = today.minusDays(2).toString()
        val end = today.plusDays(1).toString()
        val query = """
            query GetConsumption(${ '$' }accountNumber: String!, ${ '$' }start: Date!, ${ '$' }end: Date!) {
              account(accountNumber: ${ '$' }accountNumber) {
                properties {
                  measurements(
                    startOn: ${ '$' }start,
                    endOn: ${ '$' }end,
                    timezone: "Europe/Berlin",
                    first: 400,
                    utilityFilters: [{electricityFilters: {readingDirection: CONSUMPTION, readingFrequencyType: FIFTEEN_MIN_INTERVAL}}]
                  ) {
                    edges { node { value unit readAt } }
                  }
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject()
            .put("accountNumber", config.accountNumber)
            .put("start", start)
            .put("end", end)
        val root = post(JSONObject().put("query", query).put("variables", variables), token)
        throwGraphQlErrors(root)

        data class Bucket(var total: Double = 0.0, var cheap: Double = 0.0, var normal: Double = 0.0, var cost: Double = 0.0)
        val sums = linkedMapOf<String, Bucket>()
        val properties = root.optJSONObject("data")?.optJSONObject("account")?.optJSONArray("properties") ?: return null
        for (p in 0 until properties.length()) {
            val edges = properties.optJSONObject(p)?.optJSONObject("measurements")?.optJSONArray("edges") ?: continue
            for (i in 0 until edges.length()) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                val readAt = node.optString("readAt")
                val value = node.optDouble("value", Double.NaN)
                if (readAt.isBlank() || !value.isFinite() || value < 0) continue
                val unit = node.optString("unit").lowercase()
                val kwh = if (unit.contains("wh") && !unit.contains("kwh")) value / 1000.0 else value
                val zdt = parseReadAt(readAt)
                val date = zdt?.toLocalDate()?.toString() ?: readAt.take(10)
                val time = zdt?.toLocalTime() ?: LocalTime.NOON
                val cheap = isCheap(time)
                val bucket = sums.getOrPut(date) { Bucket() }
                bucket.total += kwh
                if (cheap) bucket.cheap += kwh else bucket.normal += kwh
                bucket.cost += kwh * (if (cheap) config.cheapPriceCents else config.normalPriceCents) / 100.0
            }
        }
        val preferred = sums.entries
            .filter { it.value.total > 0.0 }
            .sortedByDescending { it.key }
            .firstOrNull { it.key <= today.toString() }
            ?: return null
        val b = preferred.value
        return ImportResult(b.total, preferred.key, b.cheap, b.normal, b.cost)
    }

    private fun parseReadAt(value: String): ZonedDateTime? = runCatching { Instant.parse(value).atZone(berlin) }
        .recoverCatching { ZonedDateTime.parse(value).withZoneSameInstant(berlin) }
        .getOrNull()

    private fun localGoRates(): List<OctopusRate> {
        val rates = mutableListOf<OctopusRate>()
        var t = ZonedDateTime.now(berlin).withSecond(0).withNano(0)
        t = t.withMinute((t.minute / 15) * 15)
        repeat(96) {
            val next = t.plusMinutes(15)
            rates += OctopusRate(
                validFrom = t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                validTo = next.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                grossCentsPerKwh = localGoPrice(t.toLocalTime())
            )
            t = next
        }
        return rates
    }

    private fun isCheap(time: LocalTime): Boolean {
        val start = runCatching { LocalTime.parse(config.cheapStart) }.getOrDefault(LocalTime.MIDNIGHT)
        val end = runCatching { LocalTime.parse(config.cheapEnd) }.getOrDefault(LocalTime.of(5, 0))
        return if (start <= end) time >= start && time < end else time >= start || time < end
    }

    private fun localGoPrice(time: LocalTime): Double = if (isCheap(time)) config.cheapPriceCents else config.normalPriceCents

    private fun post(payload: JSONObject, token: String?): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "PVCompact/1.0 Android")
            if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", token)
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(180)}")
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun throwGraphQlErrors(root: JSONObject) {
        val errors = root.optJSONArray("errors") ?: JSONArray()
        if (errors.length() == 0) return
        val message = errors.optJSONObject(0)?.optString("message")?.takeIf { it.isNotBlank() } ?: "GraphQL-Fehler"
        throw IllegalStateException(message)
    }
}
