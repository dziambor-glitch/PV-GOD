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

class OctopusApi(private val config: OctopusConfig) {
    private val endpoint = "https://api.oeg-kraken.energy/v1/graphql/"
    private val berlin = ZoneId.of("Europe/Berlin")

    fun load(): OctopusData {
        val hasApiAuth = config.apiKey.isNotBlank() || config.refreshToken.isNotBlank()
        if (!hasApiAuth || config.accountNumber.isBlank()) {
            return fixedFallback(
                if (config.accountNumber.isBlank()) "Octopus-Kundennummer/API optional hinterlegen. Bis dahin wird dein Go-Zeitfenster verwendet."
                else "Kein Kraken API-Key/Refresh-Token hinterlegt. Go-Zeitfenster wird lokal berechnet."
            )
        }

        return try {
            val auth = obtainToken()
            val rates = loadRates(auth.token)
            val import = runCatching { loadImport(auth.token) }.getOrNull()
            val current = findCurrentPrice(rates)
            OctopusData(
                rates = rates,
                currentPriceCents = current,
                source = "Octopus Energy API",
                note = if (rates.isEmpty()) "Die API hat aktuell keine Tarifwerte geliefert; Go-Fallback wird angezeigt." else null,
                netImportKwh = import?.first,
                netImportDate = import?.second,
                refreshedToken = auth.refreshToken
            ).let { apiData ->
                if (apiData.rates.isEmpty()) fixedFallback(apiData.note).copy(
                    netImportKwh = apiData.netImportKwh,
                    netImportDate = apiData.netImportDate,
                    refreshedToken = apiData.refreshedToken
                ) else apiData
            }
        } catch (e: Exception) {
            fixedFallback("Octopus API derzeit nicht verfügbar: ${e.message?.take(120) ?: "unbekannter Fehler"}")
        }
    }

    private data class AuthResult(val token: String, val refreshToken: String?)

    private fun obtainToken(): AuthResult {
        val input = JSONObject()
        if (config.refreshToken.isNotBlank()) input.put("refreshToken", config.refreshToken)
        else input.put("APIKey", config.apiKey)

        val payload = JSONObject()
            .put("query", "mutation ObtainKrakenToken(\$input: ObtainJSONWebTokenInput!) { obtainKrakenToken(input: \$input) { token refreshToken refreshExpiresIn } }")
            .put("variables", JSONObject().put("input", input))

        val root = post(payload, null)
        throwGraphQlErrors(root)
        val auth = root.optJSONObject("data")?.optJSONObject("obtainKrakenToken")
            ?: throw IllegalStateException("Keine Authentifizierungsantwort")
        val token = auth.optString("token")
        if (token.isBlank()) throw IllegalStateException("Kein Kraken-Token erhalten")
        return AuthResult(token, auth.optString("refreshToken").takeIf { it.isNotBlank() })
    }

    private fun loadRates(token: String): List<OctopusRate> {
        val query = """
            query GetDayAheadPrices(\$accountNumber: String!) {
              account(accountNumber: \$accountNumber) {
                properties {
                  electricityMalos {
                    agreements {
                      unitRateForecast {
                        validFrom
                        validTo
                        unitRateInformation {
                          ... on TimeOfUseProductUnitRateInformation {
                            rates {
                              netUnitRateCentsPerKwh
                              latestGrossUnitRateCentsPerKwh
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val payload = JSONObject()
            .put("query", query)
            .put("variables", JSONObject().put("accountNumber", config.accountNumber))
        val root = post(payload, token)
        throwGraphQlErrors(root)

        val result = mutableListOf<OctopusRate>()
        val properties = root.optJSONObject("data")?.optJSONObject("account")?.optJSONArray("properties") ?: JSONArray()
        for (p in 0 until properties.length()) {
            val malos = properties.optJSONObject(p)?.optJSONArray("electricityMalos") ?: continue
            for (m in 0 until malos.length()) {
                val agreements = malos.optJSONObject(m)?.optJSONArray("agreements") ?: continue
                for (a in 0 until agreements.length()) {
                    val agreement = agreements.optJSONObject(a) ?: continue
                    val forecastAny = agreement.opt("unitRateForecast")
                    val forecasts = when (forecastAny) {
                        is JSONArray -> forecastAny
                        is JSONObject -> JSONArray().put(forecastAny)
                        else -> JSONArray()
                    }
                    for (f in 0 until forecasts.length()) {
                        val item = forecasts.optJSONObject(f) ?: continue
                        val from = item.optString("validFrom")
                        val to = item.optString("validTo")
                        val info = item.optJSONObject("unitRateInformation") ?: continue
                        val rates = info.optJSONArray("rates") ?: continue
                        for (r in 0 until rates.length()) {
                            val rate = rates.optJSONObject(r) ?: continue
                            val gross = when {
                                rate.has("latestGrossUnitRateCentsPerKwh") && !rate.isNull("latestGrossUnitRateCentsPerKwh") -> rate.optDouble("latestGrossUnitRateCentsPerKwh", Double.NaN)
                                else -> rate.optDouble("netUnitRateCentsPerKwh", Double.NaN)
                            }
                            if (from.isNotBlank() && to.isNotBlank() && gross.isFinite()) {
                                result += OctopusRate(from, to, gross)
                            }
                        }
                    }
                }
            }
        }
        return result.distinctBy { Triple(it.validFrom, it.validTo, it.grossCentsPerKwh) }.sortedBy { it.validFrom }
    }

    private fun loadImport(token: String): Pair<Double, String>? {
        val today = LocalDate.now(berlin)
        val start = today.minusDays(1).toString()
        val end = today.toString()
        val query = """
            query GetConsumption(\$accountNumber: String!, \$start: Date!, \$end: Date!) {
              account(accountNumber: \$accountNumber) {
                properties {
                  measurements(
                    startOn: \$start,
                    endOn: \$end,
                    timezone: "Europe/Berlin",
                    first: 200,
                    utilityFilters: [{electricityFilters: {readingDirection: CONSUMPTION, readingFrequencyType: FIFTEEN_MIN_INTERVAL}}]
                  ) {
                    edges {
                      node { value unit readAt }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val payload = JSONObject()
            .put("query", query)
            .put("variables", JSONObject().put("accountNumber", config.accountNumber).put("start", start).put("end", end))
        val root = post(payload, token)
        if (root.optJSONArray("errors")?.length() ?: 0 > 0) return null
        val sums = linkedMapOf<String, Double>()
        val properties = root.optJSONObject("data")?.optJSONObject("account")?.optJSONArray("properties") ?: return null
        for (p in 0 until properties.length()) {
            val edges = properties.optJSONObject(p)?.optJSONObject("measurements")?.optJSONArray("edges") ?: continue
            for (i in 0 until edges.length()) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                val readAt = node.optString("readAt")
                val value = node.optDouble("value", Double.NaN)
                if (readAt.isBlank() || !value.isFinite()) continue
                val unit = node.optString("unit").lowercase()
                val kwh = if (unit.contains("wh") && !unit.contains("kwh")) value / 1000.0 else value
                val date = runCatching { Instant.parse(readAt).atZone(berlin).toLocalDate().toString() }
                    .getOrElse { readAt.take(10) }
                sums[date] = (sums[date] ?: 0.0) + kwh
            }
        }
        val chosen = sums.entries.filter { it.value > 0 }.maxByOrNull { it.key } ?: return null
        return chosen.value to chosen.key
    }

    private fun fixedFallback(note: String?): OctopusData {
        val rates = mutableListOf<OctopusRate>()
        var t = ZonedDateTime.now(berlin).withSecond(0).withNano(0)
        val minute = (t.minute / 15) * 15
        t = t.withMinute(minute)
        repeat(96) {
            val next = t.plusMinutes(15)
            rates += OctopusRate(
                validFrom = t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                validTo = next.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                grossCentsPerKwh = localGoPrice(t.toLocalTime())
            )
            t = next
        }
        return OctopusData(
            rates = rates,
            currentPriceCents = localGoPrice(LocalTime.now(berlin)),
            source = "Octopus Go · lokaler Tarif",
            note = note
        )
    }

    private fun localGoPrice(time: LocalTime): Double {
        val start = runCatching { LocalTime.parse(config.cheapStart) }.getOrDefault(LocalTime.MIDNIGHT)
        val end = runCatching { LocalTime.parse(config.cheapEnd) }.getOrDefault(LocalTime.of(5, 0))
        val cheap = if (start <= end) time >= start && time < end else time >= start || time < end
        return if (cheap) config.cheapPriceCents else config.normalPriceCents
    }

    private fun findCurrentPrice(rates: List<OctopusRate>): Double? {
        val now = Instant.now()
        return rates.firstOrNull { rate ->
            runCatching {
                val from = ZonedDateTime.parse(rate.validFrom).toInstant()
                val to = ZonedDateTime.parse(rate.validTo).toInstant()
                !now.isBefore(from) && now.isBefore(to)
            }.getOrDefault(false)
        }?.grossCentsPerKwh ?: rates.firstOrNull()?.grossCentsPerKwh
    }

    private fun post(payload: JSONObject, token: String?): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "PVCompact/0.3 Android")
            if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", token)
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(150)}")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun throwGraphQlErrors(root: JSONObject) {
        val errors = root.optJSONArray("errors") ?: return
        if (errors.length() == 0) return
        val message = errors.optJSONObject(0)?.optString("message") ?: "GraphQL-Fehler"
        throw IllegalStateException(message)
    }
}
