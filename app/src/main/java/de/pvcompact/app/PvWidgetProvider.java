package de.pvcompact.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.Locale;

public final class PvWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        Prefs prefs = new Prefs(context);
        double today = parse(prefs.get("widget_today_kwh", ""));
        double power = parse(prefs.get("widget_power_w", ""));
        double tomorrow = parse(prefs.get("widget_tomorrow_kwh", ""));
        String stamp = prefs.get("widget_stamp", "noch nicht aktualisiert");

        for (int id : ids) {
            RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_pv);
            v.setTextViewText(R.id.widget_today,
                    Double.isFinite(today)
                            ? String.format(Locale.GERMANY, "Heute: %.1f kWh", today)
                            : "Heute: -- kWh");
            String detail = (Double.isFinite(power)
                    ? String.format(Locale.GERMANY, "PV %.0f W", power)
                    : "PV -- W");
            detail += " · ";
            detail += Double.isFinite(tomorrow)
                    ? String.format(Locale.GERMANY, "Morgen %.1f kWh", tomorrow)
                    : "Morgen -- kWh";
            detail += "\n" + stamp;
            v.setTextViewText(R.id.widget_detail, detail);

            Intent intent = new Intent(context, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(
                    context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            v.setOnClickPendingIntent(R.id.widget_title, pi);
            v.setOnClickPendingIntent(R.id.widget_today, pi);
            v.setOnClickPendingIntent(R.id.widget_detail, pi);
            manager.updateAppWidget(id, v);
        }
    }

    public static void refreshAll(Context context) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        android.content.ComponentName c = new android.content.ComponentName(context, PvWidgetProvider.class);
        int[] ids = m.getAppWidgetIds(c);
        if (ids != null && ids.length > 0) {
            new PvWidgetProvider().onUpdate(context, m, ids);
        }
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return Double.NaN; }
    }
}
