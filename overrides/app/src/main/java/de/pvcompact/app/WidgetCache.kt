package de.pvcompact.app

import android.content.Context
import java.time.LocalDate

data class WidgetSnapshot(
    val energyWh: Double,
    val powerW: Double,
    val time: String,
    val tomorrowWh: Double?,
    val octopusPriceCents: Double?
)

object WidgetCache {
    private const val PREF = "pvcompact_widget_cache"

    fun save(context: Context, live: LiveStatus, forecast: ForecastData? = null, octopusPriceCents: Double? = null) {
        val tomorrow = LocalDate.now().plusDays(1).toString()
        val tomorrowWh = forecast?.days?.firstOrNull { it.date == tomorrow }?.energyWh
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong("energy", java.lang.Double.doubleToRawLongBits(live.energyWh))
            .putLong("power", java.lang.Double.doubleToRawLongBits(live.powerW))
            .putString("time", live.time)
            .apply {
                if (tomorrowWh == null) remove("tomorrow")
                else putLong("tomorrow", java.lang.Double.doubleToRawLongBits(tomorrowWh))
                if (octopusPriceCents == null) remove("octopus_price")
                else putLong("octopus_price", java.lang.Double.doubleToRawLongBits(octopusPriceCents))
            }
            .apply()
    }

    fun load(context: Context): WidgetSnapshot? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.contains("energy")) return null
        return WidgetSnapshot(
            java.lang.Double.longBitsToDouble(p.getLong("energy", 0L)),
            java.lang.Double.longBitsToDouble(p.getLong("power", 0L)),
            p.getString("time", "") ?: "",
            if (p.contains("tomorrow")) java.lang.Double.longBitsToDouble(p.getLong("tomorrow", 0L)) else null,
            if (p.contains("octopus_price")) java.lang.Double.longBitsToDouble(p.getLong("octopus_price", 0L)) else null
        )
    }
}
