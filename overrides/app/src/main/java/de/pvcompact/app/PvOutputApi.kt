package de.pvcompact.app

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class PvOutputApi(
    private val apiKey: String,
    private val systemId: String,
    context: Context? = null
) {
    private val base = "https://pvoutput.org/service/r2/"
    private val cache = context?.applicationContext?.getSharedPreferences("pvoutput_api_cache", Context.MODE_PRIVATE)

    data class ApiResponse(val body: String, val rateRemaining: Int?)

    /**
     * Rate-friendly dashboard load:
     * - Live: max. every 2 minutes unless forceLive=true
     * - Day curve: max. every 15 minutes
     * - 7 days: max. every 6 hours
     * - Years: max. every 24 hours
     *
     * On the first run the cache is empty, so up to four calls are needed once.
     * Normal app opens afterwards are typically zero or one PVOutput call.
     */
    fun loadDashboard(forceLive: Boolean = false): DashboardData {
        val liveResponse = cachedGet("getstatus.jsp", if (forceLive) 0L else TWO_MINUTES)
        val live = parseLive(liveResponse.body)

        val historyPath = "getstatus.jsp?h=1&limit=288&asc=1&d=${live.date}"
        val historyResponse = runCatching { cachedGet(historyPath, FIFTEEN_MINUTES) }.getOrNull()

        val today = LocalDate.parse(live.date, DateTimeFormatter.BASIC_ISO_DATE)
        val from = today.minusDays(6).format(DateTimeFormatter.BASIC_ISO_DATE)
        val to = today.format(DateTimeFormatter.BASIC_ISO_DATE)
        val weekResponse = runCatching { cachedGet("getoutput.jsp?df=$from&dt=$to&limit=7", SIX_HOURS) }.getOrNull()
        val yearsResponse = runCatching { cachedGet("getoutput.jsp?a=y&limit=15", ONE_DAY) }.getOrNull()

        val week = (weekResponse?.body?.let(::parseWeek) ?: emptyList()).toMutableList()
        val existingToday = week.indexOfFirst { it.date == live.date }
        val todayOutput = DailyOutput(live.date, live.energyWh, null, live.consumptionWh, live.powerW)
        if (existingToday >= 0) {
            val old = week[existingToday]
            week[existingToday] = old.copy(
                generatedWh = maxOf(old.generatedWh, live.energyWh),
                consumedWh = listOfNotNull(old.consumedWh, live.consumptionWh).maxOrNull(),
                peakPowerW = listOfNotNull(old.peakPowerW, live.powerW).maxOrNull()
            )
        } else {
            week.add(todayOutput)
        }

        return DashboardData(
            live = live,
            history = historyResponse?.body?.let(::parseHistory) ?: emptyList(),
            week = week.sortedBy { it.date }.takeLast(7),
            years = yearsResponse?.body?.let(::parseYears) ?: emptyList(),
            rateRemaining = listOfNotNull(
                liveResponse.rateRemaining,
                historyResponse?.rateRemaining,
                weekResponse?.rateRemaining,
                yearsResponse?.rateRemaining
            ).minOrNull()
        )
    }

    /** Widget use: never call more than once per 30 minutes. */
    fun loadLiveForWidget(): Pair<LiveStatus, Int?> {
        val response = cachedGet("getstatus.jsp", THIRTY_MINUTES)
        return parseLive(response.body) to response.rateRemaining
    }

    /** Explicit refresh from the app. */
    fun loadLive(force: Boolean = false): Pair<LiveStatus, Int?> {
        val response = cachedGet("getstatus.jsp", if (force) 0L else TWO_MINUTES)
        return parseLive(response.body) to response.rateRemaining
    }

    private fun cachedGet(path: String, maxAgeMs: Long): ApiResponse {
        if (maxAgeMs > 0 && cache != null) {
            val key = cacheKey(path)
            val savedAt = cache.getLong("${key}_time", 0L)
            val body = cache.getString("${key}_body", null)
            if (!body.isNullOrBlank() && System.currentTimeMillis() - savedAt < maxAgeMs) {
                val remaining = cache.getInt("${key}_remaining", -1).takeIf { it >= 0 }
                return ApiResponse(body, remaining)
            }
        }

        val response = get(path)
        cache?.edit()?.apply {
            val key = cacheKey(path)
            putLong("${key}_time", System.currentTimeMillis())
            putString("${key}_body", response.body)
            response.rateRemaining?.let { putInt("${key}_remaining", it) }
        }?.apply()
        return response
    }

    private fun cacheKey(path: String): String = "${systemId}_${path.hashCode().toUInt()}"

    private fun get(path: String): ApiResponse {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.setRequestProperty("X-Pvoutput-Apikey", apiKey)
            connection.setRequestProperty("X-Pvoutput-SystemId", systemId)
            connection.setRequestProperty("X-Rate-Limit", "1")
            connection.setRequestProperty("User-Agent", "PVCompact/0.3 Android")

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }.trim()
            if (code !in 200..299) {
                throw IllegalStateException("PVOutput Fehler $code: ${body.take(180)}")
            }
            return ApiResponse(body, connection.getHeaderField("X-Rate-Limit-Remaining")?.toIntOrNull())
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLive(body: String): LiveStatus {
        val p = body.split(',')
        require(p.size >= 6) { "Unerwartete Live-Antwort von PVOutput" }
        return LiveStatus(
            date = p[0],
            time = p[1],
            energyWh = num(p[2]) ?: 0.0,
            powerW = num(p[3]) ?: 0.0,
            consumptionWh = p.getOrNull(4)?.let(::num),
            consumptionW = p.getOrNull(5)?.let(::num),
            temperatureC = p.getOrNull(7)?.let(::num),
            voltageV = p.getOrNull(8)?.let(::num)
        )
    }

    private fun parseHistory(body: String): List<HistoryPoint> = body
        .split(';')
        .mapNotNull { row ->
            val p = row.trim().split(',')
            if (p.size < 6) return@mapNotNull null
            HistoryPoint(
                time = p[1],
                energyWh = num(p[2]) ?: 0.0,
                powerW = num(p[4]) ?: 0.0,
                consumptionW = p.getOrNull(8)?.let(::num)
            )
        }

    private fun parseWeek(body: String): List<DailyOutput> = body
        .split(';')
        .mapNotNull { row ->
            val p = row.trim().split(',')
            if (p.size < 2 || p[0].isBlank()) return@mapNotNull null
            DailyOutput(
                date = p[0],
                generatedWh = num(p[1]) ?: 0.0,
                efficiency = p.getOrNull(2)?.let(::num),
                consumedWh = p.getOrNull(4)?.let(::num),
                peakPowerW = p.getOrNull(5)?.let(::num)
            )
        }

    private fun parseYears(body: String): List<AnnualOutput> = body
        .split(';')
        .mapNotNull { row ->
            val p = row.trim().split(',')
            if (p.size < 6 || p[0].length < 4) return@mapNotNull null
            val year = p[0].take(4).toIntOrNull() ?: return@mapNotNull null
            val imports = listOfNotNull(
                p.getOrNull(6)?.let(::num),
                p.getOrNull(7)?.let(::num),
                p.getOrNull(8)?.let(::num),
                p.getOrNull(9)?.let(::num)
            )
            AnnualOutput(
                year = year,
                days = p.getOrNull(1)?.toIntOrNull() ?: 0,
                generatedWh = p.getOrNull(2)?.let(::num) ?: 0.0,
                efficiency = p.getOrNull(3)?.let(::num),
                exportedWh = p.getOrNull(4)?.let(::num),
                consumedWh = p.getOrNull(5)?.let(::num),
                importedWh = if (imports.isEmpty()) null else imports.sum()
            )
        }
        .sortedByDescending { it.year }

    private fun num(value: String): Double? = value.trim().takeIf { it.isNotEmpty() && !it.equals("NaN", true) }?.toDoubleOrNull()

    companion object {
        private const val TWO_MINUTES = 2L * 60L * 1000L
        private const val FIFTEEN_MINUTES = 15L * 60L * 1000L
        private const val THIRTY_MINUTES = 30L * 60L * 1000L
        private const val SIX_HOURS = 6L * 60L * 60L * 1000L
        private const val ONE_DAY = 24L * 60L * 60L * 1000L
    }
}
