package de.pvcompact.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale

class PvWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateFromCache(context, manager, it) }

        val pending = goAsync()
        Thread {
            try {
                val store = CredentialStore(context)
                if (store.hasCredentials()) {
                    val (live, _) = PvOutputApi(store.getApiKey(), store.getSystemId(), context).loadLiveForWidget()
                    val forecast = store.getForecastConfig()?.let { config -> runCatching { PvForecastApi(config).load() }.getOrNull() }
                    val octopus = runCatching { OctopusApi(store.getOctopusConfig()).load() }.getOrNull()
                    octopus?.refreshedToken?.let(store::updateOctopusRefreshToken)
                    WidgetCache.save(context, live, forecast, octopus?.currentPriceCents)
                    val tomorrowWh = forecast?.days?.firstOrNull { it.date == java.time.LocalDate.now().plusDays(1).toString() }?.energyWh
                    appWidgetIds.forEach { update(context, manager, it, live.energyWh, live.powerW, live.time, tomorrowWh, octopus?.currentPriceCents) }
                }
            } catch (_: Exception) {
                // Cache bleibt sichtbar.
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun updateFromCache(context: Context, manager: AppWidgetManager, id: Int) {
        val cache = WidgetCache.load(context)
        if (cache == null) {
            manager.updateAppWidget(id, baseViews(context))
        } else {
            update(context, manager, id, cache.energyWh, cache.powerW, cache.time, cache.tomorrowWh, cache.octopusPriceCents)
        }
    }

    private fun update(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        energyWh: Double,
        powerW: Double,
        time: String,
        tomorrowWh: Double?,
        octopusPriceCents: Double?
    ) {
        val views = baseViews(context)
        views.setTextViewText(R.id.widget_energy, String.format(Locale.GERMANY, "%.1f kWh", energyWh / 1000.0))
        views.setTextViewText(R.id.widget_power, String.format(Locale.GERMANY, "%.2f kW", powerW / 1000.0))
        views.setTextViewText(
            R.id.widget_forecast,
            tomorrowWh?.let { String.format(Locale.GERMANY, "Morgen ca. %.1f kWh", it / 1000.0) } ?: "Forecast in der App einrichten"
        )
        views.setTextViewText(
            R.id.widget_price,
            octopusPriceCents?.let { String.format(Locale.GERMANY, "Octopus %.1f ct/kWh", it) } ?: "Octopus-Tarif in der App einrichten"
        )
        views.setTextViewText(R.id.widget_updated, "Stand $time Uhr")
        manager.updateAppWidget(id, views)
    }

    private fun baseViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_pv)
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
        return views
    }
}
