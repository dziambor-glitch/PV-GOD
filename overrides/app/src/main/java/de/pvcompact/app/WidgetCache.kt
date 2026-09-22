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
        val e = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong("energy", java.lang.Double.doubleToRawLongBits(live.energyWh))
            .putLong("power", java.lang.Double.doubleToRawLongBits(live.powerW))
            .putString("time", live.time)
        if (tomorrowWh == null) e.remove("tomorrow") else e.putLong("tomorrow", java.lang.Double.doubleToRawLongBits(tomorrowWh))
        if (octopusPriceCents == null) e.remove("price") else e.putLong("price", java.lang.Double.doubleToRawLongBits(octopusPriceCents))
        e.apply()
    }

    fun load(context: Context): WidgetSnapshot? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.contains("energy")) return null
        return WidgetSnapshot(
            energyWh = java.lang.Double.longBitsToDouble(p.getLong("energy", 0L)),
            powerW = java.lang.Double.longBitsToDouble(p.getLong("power", 0L)),
            time = p.getString("time", "") ?: "",
            tomorrowWh = if (p.contains("tomorrow")) java.lang.Double.longBitsToDouble(p.getLong("tomorrow", 0L)) else null,
            octopusPriceCents = if (p.contains("price")) java.lang.Double.longBitsToDouble(p.getLong("price", 0L)) else null
        )
    }
}
