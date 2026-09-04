package de.pvcompact.app

data class LiveStatus(
    val date: String,
    val time: String,
    val energyWh: Double,
    val powerW: Double,
    val consumptionWh: Double?,
    val consumptionW: Double?,
    val temperatureC: Double?,
    val voltageV: Double?
)

data class HistoryPoint(
    val time: String,
    val energyWh: Double,
    val powerW: Double,
    val consumptionW: Double?
)

data class DailyOutput(
    val date: String,
    val generatedWh: Double,
    val efficiency: Double?,
    val consumedWh: Double?,
    val peakPowerW: Double?
)

data class AnnualOutput(
    val year: Int,
    val days: Int,
    val generatedWh: Double,
    val consumedWh: Double?,
    val exportedWh: Double?,
    val importedWh: Double?,
    val efficiency: Double?
)

data class ForecastConfig(
    val latitude: Double,
    val longitude: Double,
    val tilt: Int,
    val azimuth: Int,
    val kwp: Double,
    val performanceRatio: Double = 0.85,
    val forecastSolarApiKey: String = ""
)

data class ForecastDay(
    val date: String,
    val energyWh: Double,
    val weatherCode: Int? = null,
    val temperatureMinC: Double? = null,
    val temperatureMaxC: Double? = null
)

data class ForecastData(
    val days: List<ForecastDay>,
    val source: String,
    val note: String? = null
)

data class OctopusConfig(
    val accountNumber: String = "",
    val apiKey: String = "",
    val refreshToken: String = "",
    val cheapStart: String = "00:00",
    val cheapEnd: String = "05:00",
    val cheapPriceCents: Double = 19.0,
    val normalPriceCents: Double = 29.0
)

data class OctopusRate(
    val validFrom: String,
    val validTo: String,
    val grossCentsPerKwh: Double
)

data class OctopusData(
    val rates: List<OctopusRate>,
    val currentPriceCents: Double?,
    val source: String,
    val note: String? = null,
    val netImportKwh: Double? = null,
    val netImportDate: String? = null,
    val refreshedToken: String? = null
)

data class DashboardData(
    val live: LiveStatus,
    val history: List<HistoryPoint>,
    val week: List<DailyOutput>,
    val years: List<AnnualOutput>,
    val rateRemaining: Int?,
    val forecast: ForecastData? = null,
    val forecastError: String? = null
)
