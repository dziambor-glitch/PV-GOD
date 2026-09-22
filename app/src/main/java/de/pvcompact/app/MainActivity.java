package de.pvcompact.app;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private LinearLayout content;
    private String currentTab = "Übersicht";

    private PvOutputClient.Dashboard pv;
    private List<ForecastClient.Day> forecast = Collections.emptyList();
    private OctopusClient.Summary octopus;
    private String pvError = "";
    private String forecastError = "";
    private String octopusError = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        buildShell();
        showTab("Übersicht");
        refreshPvForecast(false);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(250, 250, 250));

        TextView title = new TextView(this);
        title.setText("PV Compact · Clean 1.0");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(20,20,20));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), dp(14), dp(16), dp(10));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        String[] tabs = {"Übersicht", "Verlauf", "Jahre", "Forecast", "Octopus", "Automatik", "Einstellungen"};
        for (String tab : tabs) {
            Button b = new Button(this);
            b.setText(tab);
            b.setAllCaps(false);
            b.setOnClickListener(v -> showTab(tab));
            nav.addView(b, new LinearLayout.LayoutParams(-2, dp(48)));
        }
        navScroll.addView(nav);
        root.addView(navScroll, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(10), dp(14), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private void showTab(String tab) {
        currentTab = tab;
        content.removeAllViews();
        switch (tab) {
            case "Verlauf": showHistory(); break;
            case "Jahre": showYears(); break;
            case "Forecast": showForecast(); break;
            case "Octopus": showOctopus(); break;
            case "Automatik": showAutomation(); break;
            case "Einstellungen": showSettings(); break;
            default: showOverview(); break;
        }
    }

    private void showOverview() {
        heading("Übersicht");
        TextView status = body("");
        if (pv == null) {
            status.setText(pvError.isEmpty()
                    ? "PVOutput: noch keine Daten. Unter Einstellungen System-ID und API-Key hinterlegen."
                    : "PVOutput: " + pvError);
        } else {
            StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.GERMANY, "Heute %.2f kWh\n", pv.live.energyWh / 1000.0));
            s.append(String.format(Locale.GERMANY, "PV jetzt %.0f W\n", pv.live.powerW));
            if (pv.live.consumptionW != null) {
                s.append(String.format(Locale.GERMANY, "Verbrauch jetzt %.0f W\n", pv.live.consumptionW));
            }
            if (pv.rateRemaining != null) s.append("PVOutput API Rest: ").append(pv.rateRemaining).append("\n");
            if (!forecast.isEmpty()) {
                ForecastClient.Day today = forecast.get(0);
                s.append(String.format(Locale.GERMANY, "PV-Prognose heute %.1f kWh\n", today.energyKwh));
                if (forecast.size() > 1) {
                    s.append(String.format(Locale.GERMANY, "PV-Prognose morgen %.1f kWh\n", forecast.get(1).energyKwh));
                }
            } else if (!forecastError.isEmpty()) {
                s.append("Forecast: ").append(forecastError).append("\n");
            }
            if (octopus != null) {
                s.append(String.format(Locale.GERMANY, "Octopus Netzbezug zuletzt %.2f kWh", octopus.totalKwh));
            }
            status.setText(s.toString().trim());
        }

        Button refresh = button("Alles aktualisieren");
        refresh.setOnClickListener(v -> {
            refreshPvForecast(true);
            refreshOctopus();
        });

        card("API-schonend", "Live-PV maximal alle 2 Minuten, Tageskurve 15 Minuten, 7 Tage 6 Stunden, Jahre 24 Stunden. Das Widget greift nur auf den App-Cache zu und erzeugt keine zusätzlichen PVOutput-Aufrufe.");
        card("Nulleinspeisung", "PVOutput zeigt bei deinem System die tatsächlich genutzte PV-Energie. Abgeregelte Leistung ist darin nicht automatisch enthalten; eine spätere Schätzung kommt aus dem Raspberry-Controller.");
    }

    private void showHistory() {
        heading("Tagesverlauf & 7 Tage");
        if (pv == null) {
            body(pvError.isEmpty() ? "Noch keine PVOutput-Daten geladen." : pvError);
            buttonRefreshPv();
            return;
        }

        TextView live = body(String.format(Locale.GERMANY,
                "%s %s · %.2f kWh · %.0f W",
                pv.live.date, pv.live.time, pv.live.energyWh / 1000.0, pv.live.powerW));

        LineChartView chart = new LineChartView(this);
        chart.setPoints(pv.history);
        content.addView(chart, new LinearLayout.LayoutParams(-1, dp(245)));

        subheading("Letzte 7 Tage");
        if (pv.week.isEmpty()) body("Keine 7-Tage-Daten verfügbar.");
        for (PvOutputClient.Day d : pv.week) {
            String t = String.format(Locale.GERMANY, "%s   %.2f kWh", d.date, d.generatedWh / 1000.0);
            if (d.consumedWh != null) t += String.format(Locale.GERMANY, "   Verbrauch %.2f kWh", d.consumedWh / 1000.0);
            body(t);
        }
        buttonRefreshPv();
    }

    private void showYears() {
        heading("Jahre");
        if (pv == null || pv.years.isEmpty()) {
            body(pvError.isEmpty() ? "Noch keine Jahresdaten geladen." : pvError);
            buttonRefreshPv();
            return;
        }
        for (PvOutputClient.Year y : pv.years) {
            StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.GERMANY, "%.1f kWh Erzeugung", y.generatedWh / 1000.0));
            if (y.consumedWh != null) s.append(String.format(Locale.GERMANY, "\n%.1f kWh Verbrauch", y.consumedWh / 1000.0));
            if (y.importedWh != null) s.append(String.format(Locale.GERMANY, "\n%.1f kWh Import", y.importedWh / 1000.0));
            card(String.valueOf(y.year), s.toString());
        }
    }

    private void showForecast() {
        heading("PV-Prognose");
        if (forecast.isEmpty()) {
            body(forecastError.isEmpty()
                    ? "Noch keine Forecast-Daten. Standort, kWp, Dachneigung und Azimut in Einstellungen hinterlegen."
                    : forecastError);
        } else {
            for (ForecastClient.Day d : forecast) {
                StringBuilder s = new StringBuilder();
                s.append(String.format(Locale.GERMANY, "%.1f kWh", d.energyKwh));
                if (d.minC != null && d.maxC != null) {
                    s.append(String.format(Locale.GERMANY, "\n%.0f–%.0f °C", d.minC, d.maxC));
                }
                if (d.weatherCode != null) s.append("\nWettercode ").append(d.weatherCode);
                card(d.date, s.toString());
            }
        }
        Button b = button("Forecast aktualisieren");
        b.setOnClickListener(v -> refreshPvForecast(false));
        body("Quelle: Open-Meteo Global Tilted Irradiance. Azimut: Ost −90°, Süd 0°, West +90°.");
    }

    private void showOctopus() {
        heading("Octopus Energy");

        card("API-Umstellung ab 21.09.2026",
                "E-Mail/Passwort wird in dieser App nicht mehr verwendet. Unterstützt werden die von Kraken weiterhin dokumentierten Wege API-Key oder Refresh Token. Beide werden lokal im Android Keystore verschlüsselt gespeichert.");

        if (octopus != null) {
            String s = String.format(Locale.GERMANY,
                    "Authentifiziert: ja\nNetzbezug: %.2f kWh\nGünstig: %.2f kWh\nNormal: %.2f kWh\nGeschätzte Energiekosten: %.2f €\n%s",
                    octopus.totalKwh, octopus.cheapKwh, octopus.normalKwh,
                    octopus.estimatedCostEuro, octopus.note);
            card("Smart-Meter", s);
        } else if (!octopusError.isEmpty()) {
            card("Octopus", octopusError);
        } else {
            body("Kundennummer plus API-Key oder Refresh Token unter Einstellungen eintragen.");
        }

        Button b = button("Octopus testen & Netzverbrauch laden");
        b.setOnClickListener(v -> refreshOctopus());

        Button docs = button("Offizielle Kraken API-Doku öffnen");
        docs.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://developer.oeg-kraken.energy/"))));

        body("Dein Go-Tarif wird lokal mit dem eingestellten günstigen Zeitfenster berechnet. Die Smart-Meter-Werte von Octopus können zeitverzögert eintreffen.");
    }

    private void showAutomation() {
        heading("Automatik / Raspberry");

        card("Zielbild",
                "MPI10K + ICC/MQTT + Batterie + PV-Forecast + Octopus + Panasonic Aquarea/Intesis + später EV-Lader. Der Raspberry trifft die Entscheidungen; die Android-App ist Anzeige, Konfiguration und manueller Override.");

        String url = prefs.get("controller_url", "");
        if (url.isEmpty()) {
            body("Noch kein Raspberry-Controller eingetragen.");
        } else {
            body("Controller: " + url);
        }

        String cfg = String.format(Locale.GERMANY,
                "Batterie: %s kWh\nAC-Laden normal: %s A\nAbsolute Grenze: %s A\nReserve-SOC: %s %%\nAquarea/FBH: %s\nEV-Lader: %s",
                prefs.get("battery_kwh", "70"),
                prefs.get("charge_soft_a", "60"),
                prefs.get("charge_hard_a", "70"),
                prefs.get("reserve_soc", "20"),
                prefs.getBool("heatpump_enabled", true) ? "vorbereitet" : "aus",
                prefs.getBool("ev_enabled", false) ? "vorbereitet" : "aus");
        card("Regelgrenzen", cfg);

        String vacation = prefs.get("vacation_return", "");
        card("Urlaubsmodus",
                vacation.isEmpty()
                        ? "Aus. In Einstellungen kann eine Rückkehrzeit hinterlegt werden. Der spätere Pi-Controller heizt die FBH rechtzeitig vor der Rückkehr wieder an."
                        : "Rückkehr: " + vacation + "\nDie Vorheizzeit wird später anhand Außentemperatur/PV-Prognose optimiert.");

        Button check = button("Raspberry-Controller prüfen");
        check.setOnClickListener(v -> checkController());

        body("Wichtig: Diese Clean-Version schreibt noch keine MPI10K-Befehle. Erst kommt ein read-only Dry-Run auf dem Pi; Nulleinspeisung und BMS-Schutzgrenzen werden nie automatisch verändert.");
    }

    private void showSettings() {
        heading("Einstellungen");

        subheading("PVOutput");
        EditText pvSystem = field("System-ID", prefs.get("pv_system", ""), false);
        EditText pvKey = field("API-Key", prefs.getSecret("pv_key"), true);

        subheading("PV-Forecast");
        EditText lat = field("Breitengrad", prefs.get("lat", ""), false);
        EditText lon = field("Längengrad", prefs.get("lon", ""), false);
        EditText kwp = field("Anlagengröße kWp", prefs.get("kwp", ""), false);
        EditText tilt = field("Dachneigung °", prefs.get("tilt", "30"), false);
        EditText az = field("Azimut (Ost -90 / Süd 0 / West +90)", prefs.get("azimuth", "0"), false);
        EditText pr = field("Systemfaktor 0–1", prefs.get("pr", "0.85"), false);

        subheading("Octopus Energy");
        body("Seit 21.09.2026 verwenden wir keinen E-Mail/Passwort-Login mehr.");
        EditText octAccount = field("Kundennummer", prefs.get("oct_account", ""), false);
        EditText octKey = field("Kraken API-Key (optional)", prefs.getSecret("oct_api_key"), true);
        EditText octRefresh = field("Refresh Token (optional)", prefs.getSecret("oct_refresh"), true);
        EditText cheapStart = field("Günstig ab HH:mm", prefs.get("cheap_start", "00:00"), false);
        EditText cheapEnd = field("Günstig bis HH:mm", prefs.get("cheap_end", "05:00"), false);
        EditText cheapPrice = field("Günstiger Preis ct/kWh", prefs.get("cheap_price", "19"), false);
        EditText normalPrice = field("Normalpreis ct/kWh", prefs.get("normal_price", "29"), false);

        subheading("Raspberry / Automatik");
        EditText controllerUrl = field("Controller URL, z.B. http://192.168.1.50:8787", prefs.get("controller_url", ""), false);
        EditText controllerToken = field("Controller Token", prefs.getSecret("controller_token"), true);
        EditText battery = field("Batterie kWh", prefs.get("battery_kwh", "70"), false);
        EditText softA = field("Ladestrom normal A", prefs.get("charge_soft_a", "60"), false);
        EditText hardA = field("Ladestrom absolute Grenze A", prefs.get("charge_hard_a", "70"), false);
        EditText reserve = field("Reserve-SOC %", prefs.get("reserve_soc", "20"), false);
        EditText vacationReturn = field("Urlaubs-Rückkehr YYYY-MM-DD HH:mm", prefs.get("vacation_return", ""), false);

        CheckBox heatpump = new CheckBox(this);
        heatpump.setText("Panasonic Aquarea / FBH in Automatik vorbereiten");
        heatpump.setChecked(prefs.getBool("heatpump_enabled", true));
        content.addView(heatpump);

        CheckBox ev = new CheckBox(this);
        ev.setText("EV-Lader in Automatik vorbereiten");
        ev.setChecked(prefs.getBool("ev_enabled", false));
        content.addView(ev);

        Button save = button("Speichern");
        save.setOnClickListener(v -> {
            prefs.put("pv_system", pvSystem.getText().toString());
            prefs.putSecret("pv_key", pvKey.getText().toString());

            prefs.put("lat", lat.getText().toString());
            prefs.put("lon", lon.getText().toString());
            prefs.put("kwp", kwp.getText().toString());
            prefs.put("tilt", tilt.getText().toString());
            prefs.put("azimuth", az.getText().toString());
            prefs.put("pr", pr.getText().toString());

            prefs.put("oct_account", octAccount.getText().toString());
            prefs.putSecret("oct_api_key", octKey.getText().toString());
            prefs.putSecret("oct_refresh", octRefresh.getText().toString());
            prefs.put("cheap_start", cheapStart.getText().toString());
            prefs.put("cheap_end", cheapEnd.getText().toString());
            prefs.put("cheap_price", cheapPrice.getText().toString());
            prefs.put("normal_price", normalPrice.getText().toString());

            prefs.put("controller_url", controllerUrl.getText().toString());
            prefs.putSecret("controller_token", controllerToken.getText().toString());
            prefs.put("battery_kwh", battery.getText().toString());
            prefs.put("charge_soft_a", softA.getText().toString());
            prefs.put("charge_hard_a", hardA.getText().toString());
            prefs.put("reserve_soc", reserve.getText().toString());
            prefs.put("vacation_return", vacationReturn.getText().toString());
            prefs.putBool("heatpump_enabled", heatpump.isChecked());
            prefs.putBool("ev_enabled", ev.isChecked());

            Toast.makeText(this, "Gespeichert", Toast.LENGTH_SHORT).show();
            pv = null;
            forecast = Collections.emptyList();
            octopus = null;
            refreshPvForecast(false);
        });

        body("API-Schlüssel und Tokens werden verschlüsselt im Android Keystore abgelegt und nicht in GitHub gespeichert.");
    }

    private void refreshPvForecast(boolean forceLive) {
        io.execute(() -> {
            PvOutputClient.Dashboard newPv = pv;
            List<ForecastClient.Day> newForecast = forecast;
            String newPvError = "";
            String newForecastError = "";

            String sys = prefs.get("pv_system", "");
            String key = prefs.getSecret("pv_key");
            if (!sys.isEmpty() && !key.isEmpty()) {
                try {
                    newPv = new PvOutputClient(this, key, sys).loadDashboard(forceLive);
                } catch (Exception e) {
                    newPvError = cleanError(e);
                }
            }

            String latS = prefs.get("lat", "");
            String lonS = prefs.get("lon", "");
            String kwpS = prefs.get("kwp", "");
            if (!latS.isEmpty() && !lonS.isEmpty() && !kwpS.isEmpty()) {
                try {
                    double lat = Double.parseDouble(latS.replace(',', '.'));
                    double lon = Double.parseDouble(lonS.replace(',', '.'));
                    double kwp = Double.parseDouble(kwpS.replace(',', '.'));
                    int tilt = parseInt(prefs.get("tilt", "30"), 30);
                    int az = parseInt(prefs.get("azimuth", "0"), 0);
                    double pr = parseDouble(prefs.get("pr", "0.85"), 0.85);
                    newForecast = new ForecastClient().load(lat, lon, tilt, az, kwp, pr);
                } catch (Exception e) {
                    newForecastError = cleanError(e);
                }
            }

            PvOutputClient.Dashboard finalPv = newPv;
            List<ForecastClient.Day> finalForecast = newForecast;
            String finalPvError = newPvError;
            String finalForecastError = newForecastError;
            runOnUiThread(() -> {
                pv = finalPv;
                forecast = finalForecast == null ? Collections.emptyList() : finalForecast;
                pvError = finalPvError;
                forecastError = finalForecastError;
                updateWidgetCache();
                showTab(currentTab);
            });
        });
    }

    private void refreshOctopus() {
        io.execute(() -> {
            try {
                OctopusClient client = new OctopusClient(
                        prefs.get("oct_account", ""),
                        prefs.getSecret("oct_api_key"),
                        prefs.getSecret("oct_refresh"),
                        prefs.get("cheap_start", "00:00"),
                        prefs.get("cheap_end", "05:00"),
                        parseDouble(prefs.get("cheap_price", "19"), 19),
                        parseDouble(prefs.get("normal_price", "29"), 29));
                OctopusClient.Summary s = client.loadRecentConsumption();
                if (s.refreshedToken != null && !s.refreshedToken.isEmpty()) {
                    prefs.putSecret("oct_refresh", s.refreshedToken);
                }
                runOnUiThread(() -> {
                    octopus = s;
                    octopusError = "";
                    if ("Octopus".equals(currentTab) || "Übersicht".equals(currentTab)) showTab(currentTab);
                });
            } catch (Exception e) {
                String err = cleanError(e);
                runOnUiThread(() -> {
                    octopusError = err;
                    if ("Octopus".equals(currentTab) || "Übersicht".equals(currentTab)) showTab(currentTab);
                });
            }
        });
    }

    private void checkController() {
        String base = prefs.get("controller_url", "").trim();
        if (base.isEmpty()) {
            Toast.makeText(this, "Controller URL fehlt", Toast.LENGTH_SHORT).show();
            return;
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/api/status";
        String token = prefs.getSecret("controller_token");
        final String finalUrl = url;
        io.execute(() -> {
            try {
                java.util.Map<String,String> h = new java.util.HashMap<>();
                if (!token.isEmpty()) h.put("Authorization", "Bearer " + token);
                Net.Response r = Net.get(finalUrl, h);
                String result = "HTTP " + r.code + "\n" + (r.body.length() > 1200 ? r.body.substring(0,1200) : r.body);
                runOnUiThread(() -> card("Controller-Antwort", result));
            } catch (Exception e) {
                runOnUiThread(() -> card("Controller-Fehler", cleanError(e)));
            }
        });
    }

    private void updateWidgetCache() {
        if (pv != null) {
            prefs.put("widget_today_kwh", Double.toString(pv.live.energyWh / 1000.0));
            prefs.put("widget_power_w", Double.toString(pv.live.powerW));
        }
        if (forecast.size() > 1) {
            prefs.put("widget_tomorrow_kwh", Double.toString(forecast.get(1).energyKwh));
        }
        prefs.put("widget_stamp", "Stand " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM HH:mm")));
        PvWidgetProvider.refreshAll(this);
    }

    private void buttonRefreshPv() {
        Button b = button("PVOutput aktualisieren");
        b.setOnClickListener(v -> refreshPvForecast(true));
    }

    private TextView heading(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(24);
        v.setTextColor(Color.rgb(20,20,20));
        v.setPadding(0, dp(6), 0, dp(10));
        content.addView(v);
        return v;
    }

    private TextView subheading(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(18);
        v.setTextColor(Color.rgb(30,30,30));
        v.setPadding(0, dp(16), 0, dp(6));
        content.addView(v);
        return v;
    }

    private TextView body(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(15);
        v.setTextColor(Color.rgb(55,55,55));
        v.setLineSpacing(0, 1.15f);
        v.setPadding(dp(2), dp(4), dp(2), dp(8));
        content.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private void card(String title, String text) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.rgb(225,225,225));
        box.setBackground(bg);

        TextView h = new TextView(this);
        h.setText(title);
        h.setTextSize(17);
        h.setTextColor(Color.rgb(25,25,25));
        box.addView(h);

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(Color.rgb(70,70,70));
        t.setPadding(0, dp(6), 0, 0);
        box.addView(t);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(5), 0, dp(8));
        content.addView(box, lp);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.setMargins(0, dp(4), 0, dp(6));
        content.addView(b, lp);
        return b;
    }

    private EditText field(String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setSingleLine(true);
        if (secret) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        content.addView(e, new LinearLayout.LayoutParams(-1, dp(56)));
        return e;
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (Exception e) { return def; }
    }

    private String cleanError(Exception e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) s = e.getClass().getSimpleName();
        return s.length() > 260 ? s.substring(0, 260) : s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
