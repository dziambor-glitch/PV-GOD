package de.pvcompact.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastPvRefreshRequestMs, lastOctopusRefreshRequestMs, lastWattAttemptMs;
    private volatile WattMeterLocalHistory.Day localToday;
    private volatile String localHistoryStatus = "Lokale Historie noch nicht abgerufen";
    private long lastLocalSyncAttemptMs;
    private volatile boolean localSyncRunning;
    private boolean inForeground;
    private final Runnable foregroundUpdates = new Runnable() {
        @Override public void run() {
            if (!inForeground || isFinishing()) return;
            maybeRefreshSources();
            ensureWattMeter();
            maybeSyncWattHistory();
            updateWattMeterTexts();
            mainHandler.postDelayed(this, 15000L);
        }
    };
    private final List<NavItem> navItems = new ArrayList<>();

    private Prefs prefs;
    private LinearLayout content;
    private TextView headerStatus;
    private String currentTab = "Home";

    private PvOutputClient.Dashboard pv;
    private List<ForecastClient.Day> forecast = Collections.emptyList();
    private OctopusClient.Summary octopus;
    private String pvError = "";
    private String forecastError = "";
    private String octopusError = "";
    private WattMeterMqttClient wattMeter;
    private WattMeterHistoryStore wattHistory;
    private volatile WattMeterMqttClient.Snapshot wattSnapshot;
    private String wattError = "";
    private long lastWattUiAt = 0L;
    private TextView wattPowerText, wattModeText, wattRegistersText, wattStateText;
    private TextView wattYearText, wattTodayText, wattLocalStatusText;
    private TextView wattTodayCheapText, wattTodayNormalText, wattTodayCostText, wattTodaySourceText;
    private TextView wattAnnualImportText, wattAnnualPvText, wattMeterValueText;
    private TextView homePvLiveText, homePvTodayText, homePvSavingText, homeAnnualCostText, homeActiveTariffText;
    private TextView homeDayBadge, homeNightBadge, homeDayCostText, homeNightCostText, homeLiveBadge;
    private LinearLayout homeDayTariffCard, homeNightTariffCard;
    private GridPowerChartView wattHomeChart, wattHourChart, wattDayChart;
    private long lastWattChartAt = 0L;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        wattHistory = new WattMeterHistoryStore(this);
        localToday = WattMeterLocalHistory.cached(this, LocalDate.now(BERLIN).toString(),
                currentCheapStart(), currentCheapEnd());
        if (localToday != null) localHistoryStatus = "Letzter gespeicherter Stand; Synchronisierung folgt";

        getWindow().setStatusBarColor(UiKit.BG);
        getWindow().setNavigationBarColor(UiKit.CARD);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        buildShell();
        showTab("Home");
        startWattMeter();
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
        mainHandler.removeCallbacks(foregroundUpdates);
        mainHandler.post(foregroundUpdates);
    }

    @Override
    protected void onPause() {
        inForeground = false;
        mainHandler.removeCallbacks(foregroundUpdates);
        super.onPause();
    }

    private void maybeRefreshSources() {
        long now = System.currentTimeMillis();
        if (now - lastPvRefreshRequestMs > 2 * 60_000L) refreshPvForecast(false);
        if (!prefs.get("oct_account", "").isEmpty()
                && (!prefs.getSecret("oct_api_key").isEmpty() || !prefs.getSecret("oct_refresh").isEmpty())
                && now - lastOctopusRefreshRequestMs > 45 * 60_000L) refreshOctopus();
    }

    private void maybeSyncWattHistory() {
        if (!prefs.getBool("watt_mqtt_enabled", false)) return;
        long now = System.currentTimeMillis();
        if (localSyncRunning || now - lastLocalSyncAttemptMs < 5 * 60_000L) return;
        String host = prefs.get("watt_local_host", "wattwaechter-acebe6036778.local").trim();
        if (host.isEmpty()) return;
        localSyncRunning = true;
        lastLocalSyncAttemptMs = now;
        io.execute(() -> {
            try {
                String date = LocalDate.now(BERLIN).toString();
                WattMeterLocalHistory.Day day = WattMeterLocalHistory.sync(
                        this, host, prefs.getSecret("watt_local_token"), date,
                        currentCheapStart(), currentCheapEnd());
                localToday = day;
                localHistoryStatus = "Lokale Zählerhistorie synchronisiert";
                runOnUiThread(() -> {
                    lastWattChartAt = 0L; // Refresh freshly loaded daily totals immediately.
                    updateWattMeterTexts();
                    if (wattHomeChart != null) wattHomeChart.setHistoricalNetData(day.curve);
                    if (wattHourChart != null) wattHourChart.setHistoricalNetData(day.curve);
                    if (wattDayChart != null) wattDayChart.setHistoricalNetData(day.curve);
                });
            } catch (Exception e) {
                localHistoryStatus = "Historie nur im Heim-WLAN: " + cleanError(e);
                runOnUiThread(this::updateWattMeterTexts);
            } finally {
                localSyncRunning = false;
            }
        });
    }

    private void ensureWattMeter() {
        if (!prefs.getBool("watt_mqtt_enabled", false)
                || prefs.get("watt_mqtt_host", "").trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastWattAttemptMs < 45000L) return;
        WattMeterMqttClient.Snapshot s = wattSnapshot;
        if (wattMeter == null || s == null
                || s.state.startsWith("Verbindung fehlgeschlagen")
                || s.state.startsWith("Abo fehlgeschlagen")
                || (s.livePowerReceivedAtMillis > 0
                    && now - s.livePowerReceivedAtMillis > 100000L)
                || (s.livePowerReceivedAtMillis == 0 && now - lastWattAttemptMs > 90000L)) {
            startWattMeter();
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(foregroundUpdates);
        if (wattMeter != null) wattMeter.close();
        if (wattHistory != null) wattHistory.close();
        io.shutdownNow();
        super.onDestroy();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);

        LinearLayout header = UiKit.row(this);
        header.setPadding(dp(18), dp(12), dp(12), dp(10));
        header.setBackgroundColor(UiKit.BG);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView brandName = UiKit.text(this, "PV Compact", 22, UiKit.INK);
        brandName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headerStatus = UiKit.text(this, "Energie auf einen Blick", 12, UiKit.MUTED);
        brand.addView(brandName);
        brand.addView(headerStatus);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));

        Button refresh = UiKit.secondaryButton(this, "↻");
        refresh.setTextSize(22);
        refresh.setContentDescription("Daten aktualisieren");
        refresh.setOnClickListener(v -> refreshAll());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(46), dp(46));
        rp.setMargins(dp(6), 0, dp(6), 0);
        header.addView(refresh, rp);

        Button settings = UiKit.secondaryButton(this, "⚙");
        settings.setTextSize(18);
        settings.setContentDescription("Einstellungen");
        settings.setOnClickListener(v -> showTab("Einstellungen"));
        header.addView(settings, new LinearLayout.LayoutParams(dp(46), dp(46)));

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(30));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        View divider = UiKit.divider(this);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout nav = UiKit.row(this);
        nav.setPadding(dp(8), dp(6), dp(8), dp(8));
        nav.setBackgroundColor(UiKit.CARD);
        addNav(nav, "⌂", "Home", "Home");
        addNav(nav, "☀", "PV", "PV");
        addNav(nav, "☁", "Forecast", "Forecast");
        addNav(nav, "€", "Octopus", "Octopus");
        addNav(nav, "▥", "Statistik", "Statistik");
        addNav(nav, "⚙", "System", "System");
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(68)));

        setContentView(root);
    }

    private void addNav(LinearLayout nav, String icon, String label, String tab) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), dp(4), dp(4), dp(4));
        item.setClickable(true);
        item.setFocusable(true);

        TextView i = UiKit.text(this, icon, 18, UiKit.MUTED);
        i.setGravity(Gravity.CENTER);
        TextView l = UiKit.text(this, label, 10, UiKit.MUTED);
        l.setGravity(Gravity.CENTER);
        l.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        item.addView(i, new LinearLayout.LayoutParams(-1, dp(26)));
        item.addView(l, new LinearLayout.LayoutParams(-1, dp(20)));
        item.setOnClickListener(v -> showTab(tab));

        nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1f));
        navItems.add(new NavItem(tab, item, i, l));
    }

    private void showTab(String tab) {
        currentTab = tab;
        content.removeAllViews();
        updateNav();

        switch (tab) {
            case "PV":
                showPv();
                break;
            case "Forecast":
                showForecast();
                break;
            case "Octopus":
                showOctopus();
                break;
            case "Netz":
                showGridMeter();
                break;
            case "Statistik":
                showStatistics();
                break;
            case "System":
                showSystem();
                break;
            case "Einstellungen":
                showSettings();
                break;
            default:
                showHome();
                break;
        }
    }

    private void updateNav() {
        for (NavItem item : navItems) {
            boolean selected = item.tab.equals(currentTab);
            item.container.setBackground(selected
                    ? UiKit.roundRect(this, UiKit.MINT, 15)
                    : UiKit.roundRect(this, Color.TRANSPARENT, 15));
            int color = selected ? UiKit.GREEN_DARK : UiKit.MUTED;
            item.icon.setTextColor(color);
            item.label.setTextColor(color);
        }
    }

    // PV Compact Home: approved light-card mockup (v1.8.3).
    // The complete day's figures come from the local WattWächter history, not from
    // instantaneous MQTT wattage sampled only while Android is in foreground.
    private void showHome() {
        wattHomeChart = null;
        homeDayTariffCard = null;
        homeNightTariffCard = null;
        int year = LocalDate.now(BERLIN).getYear();

        // 1. LIVE WattWächter with exactly ONE current grid direction.
        LinearLayout live = UiKit.card(this);
        LinearLayout title = UiKit.row(this);
        TextView logo = UiKit.text(this, "⚡", 30, UiKit.GREEN_DARK);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(UiKit.roundRect(this, UiKit.MINT, 13));
        title.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titleWords = new LinearLayout(this);
        titleWords.setOrientation(LinearLayout.VERTICAL);
        titleWords.setPadding(dp(11), 0, 0, 0);
        TextView wattName = UiKit.text(this, "WattWächter", 21, UiKit.INK);
        wattName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleWords.addView(wattName);
        titleWords.addView(UiKit.caption(this, "Aktuelle Leistung & Tagesbilanz"));
        title.addView(titleWords, new LinearLayout.LayoutParams(0, -2, 1f));
        homeLiveBadge = UiKit.pill(this, isWattFresh() ? "● Live" : "○ Lädt",
                isWattFresh() ? UiKit.GREEN_DARK : UiKit.MUTED, UiKit.MINT);
        title.addView(homeLiveBadge);
        live.addView(title);

        LinearLayout liveMetrics = UiKit.row(this);
        liveMetrics.setBaselineAligned(false);
        LinearLayout meterLiveBox = new LinearLayout(this);
        meterLiveBox.setOrientation(LinearLayout.VERTICAL);
        meterLiveBox.setPadding(dp(4), dp(12), dp(5), dp(10));
        wattPowerText = UiKit.text(this, wattPowerLabel(), 31, wattActiveColor());
        wattPowerText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        meterLiveBox.addView(wattPowerText);
        wattModeText = UiKit.text(this, wattModeLabel(), 12, wattActiveColor());
        meterLiveBox.addView(wattModeText);
        liveMetrics.addView(meterLiveBox, metricLp(true));
        LinearLayout pvLiveBox = new LinearLayout(this);
        pvLiveBox.setOrientation(LinearLayout.VERTICAL);
        pvLiveBox.setPadding(dp(10), dp(12), dp(4), dp(10));
        homePvLiveText = UiKit.text(this, pvNowLabel(), 29, UiKit.GREEN_DARK);
        homePvLiveText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pvLiveBox.addView(homePvLiveText);
        pvLiveBox.addView(UiKit.caption(this, "☀ PV-Leistung jetzt"));
        liveMetrics.addView(pvLiveBox, metricLp(false));
        live.addView(liveMetrics);
        View liveLine = UiKit.divider(this);
        LinearLayout.LayoutParams liveLineParams = new LinearLayout.LayoutParams(-1, dp(1));
        liveLineParams.setMargins(0, dp(3), 0, dp(12));
        live.addView(liveLine, liveLineParams);

        // Today's PV and grid kWh side-by-side, one occurrence of each.
        LinearLayout todayRow = UiKit.row(this);
        todayRow.setBaselineAligned(false);
        LinearLayout pvTodayBox = new LinearLayout(this);
        pvTodayBox.setOrientation(LinearLayout.VERTICAL);
        pvTodayBox.setPadding(dp(11), dp(11), dp(8), dp(11));
        pvTodayBox.setBackground(UiKit.roundRect(this, UiKit.MINT, 14));
        pvTodayBox.addView(UiKit.overline(this, "☀ PV-ERZEUGUNG HEUTE", UiKit.GREEN_DARK));
        homePvTodayText = UiKit.text(this, pvTodayLabel(), 19, UiKit.GREEN_DARK);
        homePvTodayText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        homePvTodayText.setPadding(0, dp(6), 0, 0);
        pvTodayBox.addView(homePvTodayText);
        todayRow.addView(pvTodayBox, metricLp(true));

        LinearLayout netTodayBox = new LinearLayout(this);
        netTodayBox.setOrientation(LinearLayout.VERTICAL);
        netTodayBox.setPadding(dp(11), dp(11), dp(8), dp(11));
        netTodayBox.setBackground(UiKit.roundRect(this, UiKit.BLUE_SOFT, 14));
        netTodayBox.addView(UiKit.overline(this, "⚡ NETZBEZUG HEUTE", UiKit.BLUE));
        wattTodayText = UiKit.text(this, wattTodayLabel(), 19, UiKit.BLUE);
        wattTodayText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wattTodayText.setPadding(0, dp(6), 0, 0);
        netTodayBox.addView(wattTodayText);
        todayRow.addView(netTodayBox, metricLp(false));
        live.addView(todayRow);
        wattTodaySourceText = UiKit.caption(this, todayTariffs().status);
        wattTodaySourceText.setPadding(0, dp(9), 0, 0);
        live.addView(wattTodaySourceText);
        Double measuredHouseDay = pvHouseTodayKwh();
        if (measuredHouseDay != null) {
            TextView house = UiKit.caption(this, String.format(Locale.GERMANY,
                    "Hausverbrauch heute laut PVOutput: %.2f kWh", measuredHouseDay));
            house.setPadding(0, dp(4), 0, 0);
            live.addView(house);
        }
        content.addView(live);

        // 2. Clear active/inactive dual tariff cards. Active state represents NOW
        // only; daily kWh remains cumulative regardless of active state.
        LinearLayout tariffRow = UiKit.row(this);
        tariffRow.setBaselineAligned(false);
        homeDayTariffCard = tariffHomeCard(false);
        tariffRow.addView(homeDayTariffCard, metricLp(true));
        homeNightTariffCard = tariffHomeCard(true);
        tariffRow.addView(homeNightTariffCard, metricLp(false));
        content.addView(tariffRow);
        updateHomeTariffStatus();

        // The total is shown once, below the time-of-use details.
        LinearLayout costCard = UiKit.card(this);
        LinearLayout costLine = UiKit.row(this);
        costLine.addView(UiKit.caption(this, "Geschätzte Stromkosten heute"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        wattTodayCostText = UiKit.text(this, wattCostLabel(), 20, UiKit.PURPLE);
        wattTodayCostText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        costLine.addView(wattTodayCostText);
        costCard.addView(costLine);
        content.addView(costCard);

        // 3. Double-height graph with actual WattWächter history and PVOutput.
        LinearLayout graphCard = UiKit.card(this);
        LinearLayout graphTitle = UiKit.row(this);
        TextView gIcon = UiKit.text(this, "▥", 25, UiKit.GREEN_DARK);
        graphTitle.addView(gIcon);
        TextView gTitle = UiKit.text(this, "Heute · PV und Netzleistung", 17, UiKit.INK);
        gTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        gTitle.setPadding(dp(8), 0, 0, 0);
        graphTitle.addView(gTitle);
        graphCard.addView(graphTitle);
        long now = System.currentTimeMillis();
        long startOfDay = LocalDate.now(BERLIN).atStartOfDay(BERLIN).toInstant().toEpochMilli();
        wattHomeChart = new GridPowerChartView(this);
        try {
            wattHomeChart.setData(wattHistory.load(startOfDay, now), startOfDay, now, true);
            wattHomeChart.setPvData(pvOutputPlotPoints(startOfDay, now));
            WattMeterLocalHistory.Day d = localToday;
            if (d != null && LocalDate.now(BERLIN).toString().equals(d.date))
                wattHomeChart.setHistoricalNetData(d.curve);
        } catch (Exception ignored) {}
        graphCard.addView(wattHomeChart, new LinearLayout.LayoutParams(-1, dp(430)));
        graphCard.addView(UiKit.caption(this,
                "Rot: Netzbezug · Grün: PV-Erzeugung · fehlende Zeiten bleiben leer"));
        Button graphDetails = UiKit.secondaryButton(this, "▥   Netzbezug & Tagesgrafik ansehen   ›");
        graphDetails.setOnClickListener(v -> showTab("Netz"));
        LinearLayout.LayoutParams graphButton = new LinearLayout.LayoutParams(-1, dp(48));
        graphButton.setMargins(0, dp(12), 0, 0);
        graphCard.addView(graphDetails, graphButton);
        content.addView(graphCard);

        // 4. Three-day forecast in place of redundant Octopus/PV-savings cards.
        // Use dates, never indexes alone. The third forecast is three calendar
        // days after today (not "in 2 Tagen").
        addHomeForecast();

        // 5. Annual values and related estimates in two harmonized cards.
        LinearLayout annual = UiKit.row(this);
        annual.setBaselineAligned(false);
        LinearLayout netYear = UiKit.card(this);
        netYear.setPadding(dp(12), dp(14), dp(11), dp(12));
        netYear.setBackground(UiKit.outlined(this, UiKit.BLUE_SOFT,
                Color.rgb(208, 226, 242), 16));
        netYear.addView(UiKit.overline(this, "JAHRESVERBRAUCH " + year, UiKit.BLUE));
        wattAnnualImportText = UiKit.text(this, annualImportLabel(), 20, UiKit.INK);
        wattAnnualImportText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wattAnnualImportText.setPadding(0, dp(7), 0, dp(4));
        netYear.addView(wattAnnualImportText);
        netYear.addView(UiKit.caption(this, "Netzbezug gesamt"));
        View netDivider = UiKit.divider(this);
        LinearLayout.LayoutParams netDivLp = new LinearLayout.LayoutParams(-1, dp(1));
        netDivLp.setMargins(0, dp(12), 0, dp(10));
        netYear.addView(netDivider, netDivLp);
        netYear.addView(UiKit.caption(this, "Ungefähre Stromkosten"));
        homeAnnualCostText = UiKit.text(this, annualCostEstimateLabel(), 16, UiKit.INK);
        homeAnnualCostText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        homeAnnualCostText.setPadding(0, dp(3), 0, 0);
        netYear.addView(homeAnnualCostText);
        netYear.addView(UiKit.caption(this, annualCostBasisLabel()));
        annual.addView(netYear, metricLp(true));

        LinearLayout pvYear = UiKit.card(this);
        pvYear.setPadding(dp(12), dp(14), dp(11), dp(12));
        pvYear.setBackground(UiKit.outlined(this, UiKit.MINT,
                Color.rgb(203, 235, 218), 16));
        pvYear.addView(UiKit.overline(this, "PV-ERZEUGUNG " + year, UiKit.GREEN_DARK));
        wattAnnualPvText = UiKit.text(this, annualPvLabel(), 20, UiKit.INK);
        wattAnnualPvText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wattAnnualPvText.setPadding(0, dp(7), 0, dp(4));
        pvYear.addView(wattAnnualPvText);
        pvYear.addView(UiKit.caption(this, "Eigene PV-Anlage"));
        View pvDivider = UiKit.divider(this);
        LinearLayout.LayoutParams pvDivLp = new LinearLayout.LayoutParams(-1, dp(1));
        pvDivLp.setMargins(0, dp(12), 0, dp(10));
        pvYear.addView(pvDivider, pvDivLp);
        pvYear.addView(UiKit.caption(this, "Ungefähre Einsparung durch PV"));
        homePvSavingText = UiKit.text(this, annualPvSavingsLabel(), 16, UiKit.GREEN_DARK);
        homePvSavingText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        homePvSavingText.setPadding(0, dp(3), 0, 0);
        pvYear.addView(homePvSavingText);
        pvYear.addView(UiKit.caption(this, "Theoretisch bei vollständigem Eigenverbrauch; ohne Batterieflusskorrektur"));
        annual.addView(pvYear, metricLp(false));
        content.addView(annual);

        LinearLayout meterCard = UiKit.card(this);
        LinearLayout meterRow = UiKit.row(this);
        meterRow.addView(UiKit.caption(this, "◷   Aktueller Zählerstand: "));
        wattMeterValueText = UiKit.text(this, wattRegisterLabel(), 13, UiKit.INK);
        wattMeterValueText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        meterRow.addView(wattMeterValueText);
        meterCard.addView(meterRow);
        wattYearText = UiKit.caption(this, wattYearLabel());
        wattYearText.setPadding(0, dp(4), 0, 0);
        meterCard.addView(wattYearText);
        content.addView(meterCard);

        // Detailed PV/forecast/Octopus/statistics/system screens are kept as tabs.
        // The former "PV sinnvoll nutzen statt abregeln" promotion is omitted.
    }

    // Forecast values are matched by calendar date instead of relying on a
    // fixed position in the Open-Meteo response. The UI never invents kWh.
    private ForecastClient.Day homeForecastFor(int daysFromToday) {
        String date = LocalDate.now(BERLIN).plusDays(daysFromToday).toString();
        for (ForecastClient.Day d : forecast)
            if (d != null && date.equals(d.date)) return d;
        return null;
    }

    private String homeForecastValue(ForecastClient.Day day) {
        if (day == null || !Double.isFinite(day.energyKwh) || day.energyKwh < 0)
            return "—";
        double kwh = adjustedForecastKwh(day);
        if (!Double.isFinite(kwh) || kwh < 0) return "—";
        return String.format(Locale.GERMANY, "%.1f kWh", kwh);
    }

    private void addHomeForecast() {
        LinearLayout row = UiKit.row(this);
        row.setGravity(Gravity.TOP);
        row.setBaselineAligned(false);

        LocalDate tomorrowDate = LocalDate.now(BERLIN).plusDays(1);
        ForecastClient.Day tomorrow = homeForecastFor(1);
        LinearLayout main = UiKit.card(this);
        main.setMinimumHeight(dp(205));
        main.setPadding(dp(13), dp(14), dp(10), dp(12));
        main.addView(UiKit.overline(this, "PV-FORECAST MORGEN · " +
                tomorrowDate.format(DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY)),
                UiKit.GREEN_DARK));

        LinearLayout primary = UiKit.row(this);
        TextView primaryIcon = UiKit.text(this,
                tomorrow == null ? "◌" : weatherIcon(tomorrow.weatherCode),
                40, UiKit.AMBER);
        primaryIcon.setGravity(Gravity.CENTER);
        primary.addView(primaryIcon, new LinearLayout.LayoutParams(dp(52), dp(68)));
        LinearLayout primaryNumbers = new LinearLayout(this);
        primaryNumbers.setOrientation(LinearLayout.VERTICAL);
        TextView primaryEnergy = UiKit.text(this, homeForecastValue(tomorrow),
                25, UiKit.GREEN_DARK);
        primaryEnergy.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        primaryEnergy.setSingleLine(true);
        primaryEnergy.setTextSize(25);
        primaryEnergy.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM);
        primaryEnergy.setAutoSizeTextTypeUniformWithConfiguration(16, 25, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        primaryNumbers.addView(primaryEnergy);
        primaryNumbers.addView(UiKit.caption(this,
                tomorrow == null ? "Prognose noch nicht verfügbar" : "Prognose für morgen"));
        primary.addView(primaryNumbers, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams primaryLp = new LinearLayout.LayoutParams(-1, -2);
        primaryLp.setMargins(0, dp(22), 0, 0);
        main.addView(primary, primaryLp);
        main.setOnClickListener(v -> showTab("Forecast"));
        main.setClickable(true);
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(0, -2, 1.12f);
        mainLp.setMargins(0, 0, dp(6), dp(12));
        row.addView(main, mainLp);

        LinearLayout nextDays = new LinearLayout(this);
        nextDays.setOrientation(LinearLayout.VERTICAL);
        ForecastClient.Day following = homeForecastFor(2);
        LocalDate followingDate = LocalDate.now(BERLIN).plusDays(2);
        nextDays.addView(homeSmallForecast(
                "ÜBERMORGEN · " +
                        followingDate.format(DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY)),
                following));
        ForecastClient.Day third = homeForecastFor(3);
        LocalDate thirdDate = LocalDate.now(BERLIN).plusDays(3);
        String weekday = thirdDate.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.GERMANY);
        nextDays.addView(homeSmallForecast(
                weekday.toUpperCase(Locale.GERMANY) + " · " +
                        thirdDate.format(DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY)),
                third));
        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(0, -2, 0.88f);
        sideLp.setMargins(dp(6), 0, 0, 0);
        row.addView(nextDays, sideLp);
        content.addView(row);
    }

    private LinearLayout homeSmallForecast(String heading, ForecastClient.Day day) {
        LinearLayout card = UiKit.card(this);
        card.setPadding(dp(9), dp(10), dp(8), dp(10));
        card.addView(UiKit.overline(this, heading, UiKit.GREEN_DARK));
        LinearLayout line = UiKit.row(this);
        TextView weather = UiKit.text(this, day == null ? "◌" : weatherIcon(day.weatherCode),
                26, UiKit.AMBER);
        line.addView(weather);
        TextView energy = UiKit.text(this, homeForecastValue(day), 18, UiKit.INK);
        energy.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        energy.setPadding(dp(6), dp(7), 0, dp(3));
        energy.setSingleLine(true);
        energy.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM);
        energy.setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        line.addView(energy, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(line);
        card.setOnClickListener(v -> showTab("Forecast"));
        card.setClickable(true);
        return card;
    }

    private Double annualGridKwh() {
        Double meter = currentRegisterKwh();
        if (meter == null || !Double.isFinite(meter) || meter < 0) return null;
        int year = LocalDate.now(BERLIN).getYear();
        if (year == 2026) return meter;
        try {
            WattMeterHistoryStore.YearAnchor anchor = wattHistory.yearAnchor(year);
            if (anchor != null && meter >= anchor.importKwh) return meter - anchor.importKwh;
        } catch (Exception ignored) {}
        return null;
    }

    private boolean hasRepresentativeOctopusMix() {
        if (octopus == null) return false;
        int year = LocalDate.now(BERLIN).getYear();
        int days = 0;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (OctopusClient.DailyUsage d : octopus.dailyHistory) {
            if (d == null || d.date == null || !d.date.startsWith(year + "-")
                    || !seen.add(d.date) || !Double.isFinite(d.totalKwh)
                    || !Double.isFinite(d.cheapKwh) || !Double.isFinite(d.normalKwh)
                    || d.totalKwh <= 0 || d.cheapKwh < 0 || d.normalKwh < 0) continue;
            if (d.cheapKwh + d.normalKwh > 0.01) days++;
        }
        return days >= 7;
    }

    private String annualCostEstimateLabel() {
        Double annualKwh = annualGridKwh();
        if (annualKwh == null) return "—";
        double cheapRate = octopus != null && Double.isFinite(octopus.cheapCents)
                ? octopus.cheapCents : displayCheapRate();
        double normalRate = octopus != null && Double.isFinite(octopus.normalCents)
                ? octopus.normalCents : displayNormalRate();
        if (hasRepresentativeOctopusMix()) {
            int year = LocalDate.now(BERLIN).getYear();
            double cheap = 0.0, normal = 0.0;
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (OctopusClient.DailyUsage d : octopus.dailyHistory) {
                if (d == null || d.date == null || !d.date.startsWith(year + "-")
                        || !seen.add(d.date) || !Double.isFinite(d.cheapKwh)
                        || !Double.isFinite(d.normalKwh) || d.cheapKwh < 0 || d.normalKwh < 0)
                    continue;
                cheap += d.cheapKwh;
                normal += d.normalKwh;
            }
            if (cheap + normal > 0) {
                double weightedCents = (cheap * cheapRate + normal * normalRate)
                        / (cheap + normal);
                return String.format(Locale.GERMANY, "ca. %,.2f €",
                        annualKwh * weightedCents / 100.0);
            }
        }
        // Without an observed tariff mix, give the honest work-price bounds:
        // multiplying all annual kWh by 29 ct overstates the user's low-price charging.
        double low = annualKwh * Math.min(cheapRate, normalRate) / 100.0;
        double high = annualKwh * Math.max(cheapRate, normalRate) / 100.0;
        return String.format(Locale.GERMANY, "ca. %,.0f–%,.0f €", low, high);
    }

    private String annualCostBasisLabel() {
        return hasRepresentativeOctopusMix()
                ? "Hochrechnung aus Octopus-Tarifmix · ohne Grundpreis"
                : "Preisrahmen 19–29 ct/kWh · ohne Grundpreis";
    }

    private String pvNowLabel() {
        if (pv == null || pv.live == null || !Double.isFinite(pv.live.powerW)) return "—";
        return String.format(Locale.GERMANY, "%.0f W", Math.max(0, pv.live.powerW));
    }

    private String pvTodayLabel() {
        Double value = pvTodayKwh();
        return value == null ? "—" : String.format(Locale.GERMANY, "%.2f kWh", value);
    }

    private int displayCheapRate() {
        return (int)Math.round(parseDouble(prefs.get("cheap_price", "19"), 19));
    }

    private int displayNormalRate() {
        return (int)Math.round(parseDouble(prefs.get("normal_price", "29"), 29));
    }

    private String annualPvSavingsLabel() {
        int y = LocalDate.now(BERLIN).getYear();
        if (!hasPvYear(y)) return "—";
        double theoretical = pvForYear(y) * displayNormalRate() / 100.0;
        return String.format(Locale.GERMANY, "ca. %,.2f €", theoretical);
    }

    private LinearLayout homeCompactCard(String heading, String detail, int color, int bg) {
        LinearLayout card = UiKit.card(this);
        card.setPadding(dp(11), dp(11), dp(8), dp(11));
        card.addView(UiKit.overline(this, heading, color));
        TextView text = UiKit.text(this, detail, 12, UiKit.INK);
        text.setPadding(0, dp(7), 0, 0);
        text.setLineSpacing(dp(5), 1.06f);
        card.addView(text);
        return card;
    }

    private LinearLayout tariffHomeCard(boolean night) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(11), dp(9), dp(11));

        LinearLayout heading = UiKit.row(this);
        TextView icon = UiKit.text(this, night ? "☾" : "☀", 25,
                night ? UiKit.PURPLE : UiKit.AMBER);
        heading.addView(icon);
        TextView title = UiKit.text(this, night ? "Nachtstrom" : "Tagstrom", 15, UiKit.INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.setMargins(dp(5), 0, 0, 0);
        heading.addView(title, titleLp);
        TextView badge = UiKit.text(this, "—", 11, UiKit.MUTED);
        badge.setPadding(dp(7), dp(3), dp(7), dp(3));
        heading.addView(badge);
        if (night) homeNightBadge = badge;
        else homeDayBadge = badge;
        card.addView(heading);

        TextView times = UiKit.caption(this, night ? "00:00–05:00 Uhr" : "05:00–24:00 Uhr");
        times.setPadding(0, dp(3), 0, dp(2));
        card.addView(times);
        TextView amount = UiKit.text(this, night ? wattCheapLabel() : wattNormalLabel(),
                21, UiKit.INK);
        amount.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        amount.setPadding(0, dp(6), 0, dp(3));
        card.addView(amount);
        if (night) wattTodayCheapText = amount;
        else wattTodayNormalText = amount;

        TextView footer = UiKit.caption(this,
                (night ? displayCheapRate() : displayNormalRate()) + " ct/kWh · "
                + tariffCostLabel(night));
        card.addView(footer);
        if (night) homeNightCostText = footer;
        else homeDayCostText = footer;
        return card;
    }

    private String tariffCostLabel(boolean night) {
        TodayTariffs t = todayTariffs();
        if (!t.splitAvailable) return "—";
        double kwh = night ? t.cheapKwh : t.normalKwh;
        double rate = night ? displayCheapRate() : displayNormalRate();
        return String.format(Locale.GERMANY, "%.2f €", kwh * rate / 100);
    }

    private void updateHomeTariffStatus() {
        boolean nightActive = isCheapNow();
        if (homeDayTariffCard != null)
            homeDayTariffCard.setBackground(UiKit.outlined(this,
                    nightActive ? Color.rgb(246, 247, 248) : UiKit.AMBER_SOFT,
                    nightActive ? UiKit.LINE : Color.rgb(239, 205, 119), 16));
        if (homeNightTariffCard != null)
            homeNightTariffCard.setBackground(UiKit.outlined(this,
                    nightActive ? UiKit.PURPLE_SOFT : Color.rgb(246, 247, 248),
                    nightActive ? Color.rgb(195, 173, 234) : UiKit.LINE, 16));
        if (homeDayBadge != null) {
            homeDayBadge.setText(nightActive ? "inaktiv" : "aktiv");
            homeDayBadge.setTextColor(nightActive ? UiKit.MUTED : UiKit.GREEN_DARK);
            homeDayBadge.setBackground(UiKit.roundRect(this,
                    nightActive ? UiKit.LINE : UiKit.MINT, 50));
        }
        if (homeNightBadge != null) {
            homeNightBadge.setText(nightActive ? "aktiv" : "inaktiv");
            homeNightBadge.setTextColor(nightActive ? UiKit.GREEN_DARK : UiKit.MUTED);
            homeNightBadge.setBackground(UiKit.roundRect(this,
                    nightActive ? UiKit.MINT : UiKit.LINE, 50));
        }
    }

    private void heroToday() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(18), dp(20), dp(18));
        hero.setBackground(UiKit.hero(this));
        hero.setElevation(dp(3));

        TextView over = UiKit.overline(this, "PV-ERTRAG HEUTE", Color.rgb(210, 241, 231));
        hero.addView(over);

        TextView amount = UiKit.text(this,
                String.format(Locale.GERMANY, "%.2f kWh", pv.live.energyWh / 1000.0),
                36, Color.WHITE);
        amount.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        amount.setPadding(0, dp(7), 0, dp(2));
        hero.addView(amount);

        TextView live = UiKit.text(this,
                String.format(Locale.GERMANY, "Jetzt %.0f W · Stand %s", pv.live.powerW, pv.live.time),
                14, Color.rgb(218, 240, 233));
        hero.addView(live);

        if (!forecast.isEmpty() && forecast.get(0).energyKwh > 0) {
            double actual = pv.live.energyWh / 1000.0;
            double target = adjustedForecastKwh(forecast.get(0));
            int progress = (int)Math.round(Math.min(100, actual / target * 100.0));

            TextView progressLabel = UiKit.text(this,
                    String.format(Locale.GERMANY, "%d %% von %.1f kWh Tagesprognose", progress, target),
                    12, Color.rgb(218, 240, 233));
            progressLabel.setPadding(0, dp(16), 0, dp(6));
            hero.addView(progressLabel);

            ProgressBar bar = UiKit.progress(this, 100, progress, Color.WHITE);
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(41, 122, 98)));
            hero.addView(bar, new LinearLayout.LayoutParams(-1, dp(7)));
        }

        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(0, 0, 0, dp(6));
        content.addView(hero, hp);
    }

    private LinearLayout metric(String label, String value, String detail, int accent, int soft) {
        return UiKit.metricCard(this, label, value, detail, accent, soft);
    }

    private LinearLayout.LayoutParams metricLp(boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        if (first) p.setMargins(0, 0, dp(6), dp(12));
        else p.setMargins(dp(6), 0, 0, dp(12));
        return p;
    }

    private View statusLine(String title, boolean ok, String detail) {
        LinearLayout row = UiKit.row(this);
        row.setPadding(0, dp(5), 0, dp(5));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = UiKit.text(this, title, 15, UiKit.INK);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = UiKit.text(this, detail, 12, UiKit.MUTED);
        labels.addView(name);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView pill = UiKit.pill(this,
                ok ? "OK" : "OFF",
                ok ? UiKit.GREEN_DARK : UiKit.MUTED,
                ok ? UiKit.MINT : Color.rgb(238, 241, 240));
        row.addView(pill);
        return row;
    }

    private void showPv() {
        pageHeader("PV & Verlauf", "Live-Daten, Tageskurve und Ertragshistorie");

        if (pv == null) {
            emptyState("Keine PVOutput-Daten",
                    pvError.isEmpty()
                            ? "Richte PVOutput zuerst in den Einstellungen ein."
                            : pvError,
                    "Einstellungen öffnen",
                    v -> showTab("Einstellungen"));
            return;
        }

        LinearLayout liveCard = UiKit.card(this);
        liveCard.addView(UiKit.overline(this, "LIVE", UiKit.GREEN));
        LinearLayout liveRow = UiKit.row(this);
        liveRow.setPadding(0, dp(9), 0, 0);

        LinearLayout pvCol = new LinearLayout(this);
        pvCol.setOrientation(LinearLayout.VERTICAL);
        pvCol.addView(UiKit.value(this, String.format(Locale.GERMANY, "%.0f W", pv.live.powerW), UiKit.INK));
        pvCol.addView(UiKit.caption(this, "PV-Leistung"));
        liveRow.addView(pvCol, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout dayCol = new LinearLayout(this);
        dayCol.setOrientation(LinearLayout.VERTICAL);
        dayCol.addView(UiKit.value(this, String.format(Locale.GERMANY, "%.2f", pv.live.energyWh / 1000.0), UiKit.INK));
        dayCol.addView(UiKit.caption(this, "kWh heute"));
        liveRow.addView(dayCol, new LinearLayout.LayoutParams(0, -2, 1f));

        if (pv.live.consumptionW != null) {
            LinearLayout loadCol = new LinearLayout(this);
            loadCol.setOrientation(LinearLayout.VERTICAL);
            loadCol.addView(UiKit.value(this, String.format(Locale.GERMANY, "%.0f W", pv.live.consumptionW), UiKit.INK));
            loadCol.addView(UiKit.caption(this, "Verbrauch"));
            liveRow.addView(loadCol, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        liveCard.addView(liveRow);
        content.addView(liveCard);

        content.addView(UiKit.sectionTitle(this, "Heute"));
        LinearLayout chartCard = UiKit.card(this);

        LineChartView chart = new LineChartView(this);
        chart.setPoints(pv.history);
        ForecastClient.Day todayForecast = todayForecastDay();
        if (todayForecast != null) {
            chart.setDaylightRange(todayForecast.sunrise, todayForecast.sunset);
        }

        LinearLayout chartHead = UiKit.row(this);
        TextView chartTitle = UiKit.text(this, "PV-Leistung heute", 16, UiKit.INK);
        chartTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chartHead.addView(chartTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        chartHead.addView(UiKit.pill(this,
                chart.daylightLabel(),
                UiKit.GREEN_DARK,
                UiKit.MINT));
        chartCard.addView(chartHead);

        TextView chartSub = UiKit.caption(this,
                "Nur das Solarfenster · Nachtstunden sind ausgeblendet · Skala in kW");
        chartSub.setPadding(0, dp(4), 0, dp(8));
        chartCard.addView(chartSub);

        chartCard.addView(chart, new LinearLayout.LayoutParams(-1, dp(270)));
        content.addView(chartCard);

        content.addView(UiKit.sectionTitle(this, "Letzte 7 Tage"));
        LinearLayout weekCard = UiKit.card(this);
        if (pv.week.isEmpty()) {
            weekCard.addView(UiKit.caption(this, "Keine 7-Tage-Daten verfügbar."));
        } else {
            double max = 1;
            for (PvOutputClient.Day d : pv.week) max = Math.max(max, d.generatedWh);
            for (int i = 0; i < pv.week.size(); i++) {
                PvOutputClient.Day d = pv.week.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(7), 0, dp(7));

                LinearLayout top = UiKit.row(this);
                TextView date = UiKit.text(this, shortDate(d.date), 14, UiKit.INK);
                date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                top.addView(date, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView val = UiKit.text(this,
                        String.format(Locale.GERMANY, "%.1f kWh", d.generatedWh / 1000.0),
                        14, UiKit.GREEN_DARK);
                val.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                top.addView(val);
                row.addView(top);

                int pct = (int)Math.round(d.generatedWh / max * 100.0);
                ProgressBar p = UiKit.progress(this, 100, pct, UiKit.GREEN);
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(6));
                pp.setMargins(0, dp(7), 0, 0);
                row.addView(p, pp);

                if (d.consumedWh != null) {
                    TextView cons = UiKit.text(this,
                            String.format(Locale.GERMANY, "Verbrauch %.1f kWh", d.consumedWh / 1000.0),
                            11, UiKit.MUTED);
                    cons.setPadding(0, dp(4), 0, 0);
                    row.addView(cons);
                }
                weekCard.addView(row);
                if (i < pv.week.size() - 1) weekCard.addView(UiKit.divider(this), dividerLp());
            }
        }
        content.addView(weekCard);

        if (!pv.years.isEmpty()) {
            content.addView(UiKit.sectionTitle(this, "Jahresübersicht"));
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout years = UiKit.row(this);
            years.setPadding(0, 0, dp(6), 0);
            int count = Math.min(5, pv.years.size());
            for (int i = 0; i < count; i++) {
                PvOutputClient.Year y = pv.years.get(i);
                LinearLayout c = new LinearLayout(this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setPadding(dp(15), dp(14), dp(15), dp(14));
                c.setBackground(UiKit.outlined(this, UiKit.CARD, UiKit.LINE, 18));
                c.addView(UiKit.overline(this, String.valueOf(y.year), UiKit.GREEN));
                TextView v = UiKit.text(this,
                        String.format(Locale.GERMANY, "%.0f kWh", y.generatedWh / 1000.0),
                        21, UiKit.INK);
                v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                v.setPadding(0, dp(7), 0, dp(2));
                c.addView(v);
                c.addView(UiKit.caption(this, "PV-Erzeugung"));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(150), -2);
                cp.setMargins(0, 0, dp(10), dp(12));
                years.addView(c, cp);
            }
            hs.addView(years);
            content.addView(hs);
        }

        Button b = UiKit.secondaryButton(this, "PVOutput aktualisieren");
        b.setOnClickListener(v -> refreshPvForecast(true));
        content.addView(b, buttonLp());
    }

    private void showForecast() {
        pageHeader("PV-Prognose", "Wetterbasierte Solarprognose mit eigener Anlagenkorrektur");

        if (forecast.isEmpty()) {
            emptyState("Forecast noch nicht bereit",
                    forecastError.isEmpty()
                            ? "Hinterlege Standort, kWp, Dachneigung und Ausrichtung in den Einstellungen."
                            : forecastError,
                    "Forecast einrichten",
                    v -> showTab("Einstellungen"));
            return;
        }

        ForecastClient.Day tomorrow = forecast.size() > 1 ? forecast.get(1) : forecast.get(0);
        LinearLayout hero = UiKit.card(this);
        hero.setBackground(UiKit.outlined(this, UiKit.AMBER_SOFT, Color.rgb(245, 222, 158), 22));

        LinearLayout heroTop = UiKit.row(this);
        TextView weather = UiKit.text(this, weatherIcon(tomorrow.weatherCode), 40, UiKit.INK);
        heroTop.addView(weather, new LinearLayout.LayoutParams(dp(58), -2));

        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.addView(UiKit.overline(this, "MORGEN", UiKit.AMBER));
        TextView amount = UiKit.text(this,
                String.format(Locale.GERMANY, "%.1f kWh", adjustedForecastKwh(tomorrow)),
                31, UiKit.INK);
        amount.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        amount.setPadding(0, dp(3), 0, dp(1));
        heroText.addView(amount);
        String temp = tomorrow.minC != null && tomorrow.maxC != null
                ? String.format(Locale.GERMANY, "%.0f bis %.0f °C", tomorrow.minC, tomorrow.maxC)
                : "Temperatur —";
        heroText.addView(UiKit.caption(this, temp));
        heroTop.addView(heroText, new LinearLayout.LayoutParams(0, -2, 1f));
        hero.addView(heroTop);
        content.addView(hero);

        content.addView(UiKit.sectionTitle(this, "Anlagen-Korrektur"));
        LinearLayout correction = UiKit.card(this);

        double manualFactor = manualForecastFactor();
        ForecastCalibration.Stats calibrationStats = forecastCalibrationStats();
        boolean autoLearning = prefs.getBool("forecast_auto_learning", true);
        double autoFactor = autoLearning ? calibrationStats.autoFactor : 1.0;
        double effectiveFactor = manualFactor * autoFactor;

        LinearLayout correctionHead = UiKit.row(this);
        LinearLayout correctionTitle = new LinearLayout(this);
        correctionTitle.setOrientation(LinearLayout.VERTICAL);
        TextView ch = UiKit.text(this, "Manuelle Korrektur", 16, UiKit.INK);
        ch.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        correctionTitle.addView(ch);
        correctionTitle.addView(UiKit.caption(this,
                "Wenn die Prognose dauerhaft zu hoch ist, kannst du sie hier direkt an deine Anlage anpassen."));
        correctionHead.addView(correctionTitle, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView factorValue = UiKit.pill(this,
                Math.round(manualFactor * 100) + " %",
                UiKit.GREEN_DARK, UiKit.MINT);
        correctionHead.addView(factorValue);
        correction.addView(correctionHead);

        SeekBar factorSlider = new SeekBar(this);
        factorSlider.setMax(60); // 60 % bis 120 %
        factorSlider.setProgress((int)Math.round(manualFactor * 100) - 60);
        factorSlider.setProgressTintList(android.content.res.ColorStateList.valueOf(UiKit.GREEN));
        factorSlider.setThumbTintList(android.content.res.ColorStateList.valueOf(UiKit.GREEN));
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(-1, dp(44));
        sliderLp.setMargins(0, dp(10), 0, 0);
        correction.addView(factorSlider, sliderLp);

        LinearLayout scale = UiKit.row(this);
        scale.addView(UiKit.text(this, "60 %", 11, UiKit.MUTED),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView hundred = UiKit.text(this, "100 %", 11, UiKit.MUTED);
        hundred.setGravity(Gravity.CENTER);
        scale.addView(hundred, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView maxText = UiKit.text(this, "120 %", 11, UiKit.MUTED);
        maxText.setGravity(Gravity.RIGHT);
        scale.addView(maxText, new LinearLayout.LayoutParams(0, -2, 1f));
        correction.addView(scale);

        TextView manualHint = UiKit.caption(this,
                "Beispiel: 85 % bedeutet, dass PV Compact nur 85 % der ursprünglichen Open-Meteo-Prognose verwendet.");
        manualHint.setPadding(0, dp(8), 0, dp(10));
        correction.addView(manualHint);

        CheckBox auto = new CheckBox(this);
        auto.setText("Automatisch nachlernen");
        auto.setTextColor(UiKit.INK);
        auto.setChecked(autoLearning);
        correction.addView(auto);

        String learnText;
        if (calibrationStats.samples == 0) {
            learnText = "Noch keine abgeschlossenen Vergleichstage. PV Compact beginnt ab jetzt zu lernen.";
        } else if (calibrationStats.samples < 3) {
            learnText = "Lernphase: " + calibrationStats.samples
                    + " von 3 Vergleichstagen. Automatische Korrektur derzeit "
                    + Math.round(autoFactor * 100) + " %.";
        } else {
            learnText = "Aus " + calibrationStats.samples
                    + " abgeschlossenen Tagen gelernt: Auto-Faktor "
                    + Math.round(autoFactor * 100) + " %. Effektive Prognose "
                    + Math.round(effectiveFactor * 100) + " %.";
        }

        TextView learning = UiKit.caption(this, learnText);
        learning.setPadding(0, dp(4), 0, 0);
        correction.addView(learning);
        content.addView(correction);

        factorSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = 60 + progress;
                factorValue.setText(percent + " %");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                int percent = 60 + seekBar.getProgress();
                prefs.put("forecast_manual_factor", String.format(Locale.US, "%.2f", percent / 100.0));
                showTab("Forecast");
            }
        });

        auto.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.putBool("forecast_auto_learning", isChecked);
            showTab("Forecast");
        });

        content.addView(UiKit.sectionTitle(this, "4-Tage-Ausblick"));
        LinearLayout list = UiKit.card(this);
        double max = 1;
        for (ForecastClient.Day d : forecast) max = Math.max(max, adjustedForecastKwh(d));
        int count = Math.min(4, forecast.size());
        for (int i = 0; i < count; i++) {
            ForecastClient.Day d = forecast.get(i);
            LinearLayout row = UiKit.row(this);
            row.setPadding(0, dp(8), 0, dp(8));

            TextView icon = UiKit.text(this, weatherIcon(d.weatherCode), 26, UiKit.INK);
            icon.setGravity(Gravity.CENTER);
            row.addView(icon, new LinearLayout.LayoutParams(dp(46), -1));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView date = UiKit.text(this, friendlyDate(d.date), 14, UiKit.INK);
            date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            labels.addView(date);

            String temps = d.minC != null && d.maxC != null
                    ? String.format(Locale.GERMANY, "%.0f–%.0f °C", d.minC, d.maxC)
                    : "—";
            labels.addView(UiKit.text(this, temps, 12, UiKit.MUTED));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

            double adjusted = adjustedForecastKwh(d);
            TextView kwh = UiKit.text(this,
                    String.format(Locale.GERMANY, "%.1f kWh", adjusted),
                    15, UiKit.GREEN_DARK);
            kwh.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.addView(kwh);
            list.addView(row);

            ProgressBar p = UiKit.progress(this, 100,
                    (int)Math.round(adjusted / max * 100.0), UiKit.AMBER);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(5));
            pp.setMargins(dp(46), 0, 0, dp(5));
            list.addView(p, pp);

            if (i < count - 1) list.addView(UiKit.divider(this), dividerLp());
        }
        content.addView(list);

        Button b = UiKit.secondaryButton(this, "Prognose aktualisieren");
        b.setOnClickListener(v -> refreshPvForecast(false));
        content.addView(b, buttonLp());

        TextView source = UiKit.caption(this,
                "Basis: Open-Meteo Global Tilted Irradiance plus Dachneigung, Ausrichtung und Anlagenleistung. "
                        + "Die manuelle Korrektur wirkt sofort. Das automatische Lernen nutzt einen rollierenden Vergleich mit PVOutput und passt nur vorsichtig um ±10 % nach.");
        source.setPadding(dp(4), dp(4), dp(4), dp(4));
        content.addView(source);

        TextView zeroExportNote = UiKit.caption(this,
                "Hinweis bei Nulleinspeisung: Wenn die Anlage wegen voller Batterie oder geringer Last abgeregelt wird, sieht PVOutput weniger Ertrag als physikalisch möglich. "
                        + "Darum ist das automatische Lernen absichtlich begrenzt. Mit dem Raspberry können wir solche Abregel-Tage später erkennen und aus dem Lernen herausnehmen.");
        zeroExportNote.setPadding(dp(4), dp(2), dp(4), dp(10));
        content.addView(zeroExportNote);
    }

    private void showOctopus() {
        pageHeader("Octopus Energy", "Tarif, Smart-Meter-Netzbezug und Kosten");

        boolean cheap = isCheapNow();
        double price = currentTariffPrice();

        LinearLayout tariff = new LinearLayout(this);
        tariff.setOrientation(LinearLayout.VERTICAL);
        tariff.setPadding(dp(20), dp(18), dp(20), dp(18));
        tariff.setBackground(UiKit.outlined(this,
                cheap ? UiKit.PURPLE_SOFT : UiKit.CARD,
                cheap ? Color.rgb(212, 194, 243) : UiKit.LINE, 22));

        LinearLayout tariffHead = UiKit.row(this);
        String tariffName = octopus != null && octopus.productName != null && !octopus.productName.isEmpty()
                ? octopus.productName
                : "OCTOPUS GO";
        tariffHead.addView(UiKit.overline(this, tariffName.toUpperCase(Locale.GERMANY), UiKit.PURPLE),
                new LinearLayout.LayoutParams(0, -2, 1f));
        tariffHead.addView(UiKit.pill(this,
                cheap ? "GÜNSTIG AKTIV" : "NORMAL",
                cheap ? UiKit.PURPLE : UiKit.MUTED,
                cheap ? Color.rgb(230, 218, 249) : Color.rgb(238, 241, 240)));
        tariff.addView(tariffHead);

        TextView priceView = UiKit.text(this,
                String.format(Locale.GERMANY, "%.0f ct/kWh", price),
                34, UiKit.INK);
        priceView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        priceView.setPadding(0, dp(9), 0, dp(3));
        tariff.addView(priceView);
        double shownCheapRate = octopus != null && Double.isFinite(octopus.cheapCents)
                ? octopus.cheapCents
                : parseDouble(prefs.get("cheap_price", "19"), 19);
        tariff.addView(UiKit.caption(this,
                "Günstig " + currentCheapStart() + "–" + currentCheapEnd() + " · "
                        + String.format(Locale.GERMANY, "%.2f", shownCheapRate) + " ct/kWh"
                        + (octopus != null && octopus.tariffFromApi ? " · live aus Vertrag" : " · manuelle Vorgabe")));
        content.addView(tariff);

        if (octopus != null) {
            content.addView(UiKit.sectionTitle(this, "Smart-Meter"));
            boolean meterData = octopus.smartMeterDataAvailable;
            String recentDetail = octopus.recentIntervalCount > 0
                    ? recentIntervalRangeText()
                    : (octopus.historyDayCount > 0
                            ? "aus Tageshistorie · " + octopus.historyDayCount + " Tage"
                            : "Octopus hat noch keine Messwerte geliefert");

            LinearLayout r1 = UiKit.row(this);
            r1.addView(metric("NETZBEZUG",
                    meterData ? String.format(Locale.GERMANY, "%.2f kWh", octopus.totalKwh) : "—",
                    recentDetail,
                    UiKit.PURPLE, UiKit.PURPLE_SOFT), metricLp(true));
            r1.addView(metric("KOSTEN",
                    meterData ? String.format(Locale.GERMANY, "%.2f €", octopus.estimatedCostEuro) : "—",
                    meterData ? "geschätzt aus Go-Tarif" : "keine validen Messwerte",
                    UiKit.GREEN, UiKit.MINT), metricLp(false));
            content.addView(r1);

            LinearLayout r2 = UiKit.row(this);
            r2.addView(metric("NEBENZEIT",
                    meterData ? String.format(Locale.GERMANY, "%.2f kWh", octopus.cheapKwh) : "—",
                    meterData ? currentCheapStart() + "–" + currentCheapEnd() : "noch nicht verfügbar",
                    UiKit.PURPLE, UiKit.PURPLE_SOFT), metricLp(true));
            r2.addView(metric("HAUPTZEIT",
                    meterData ? String.format(Locale.GERMANY, "%.2f kWh", octopus.normalKwh) : "—",
                    meterData ? "außerhalb des Fensters" : "noch nicht verfügbar",
                    UiKit.BLUE, UiKit.BLUE_SOFT), metricLp(false));
            content.addView(r2);

            showOctopusDailyTariffChart();
            List<EnergyPeriod> recentDays = recentGridDayBreakdowns();
            if (!recentDays.isEmpty()) {
                LinearLayout daysCard = UiKit.card(this);
                daysCard.addView(UiKit.overline(this, "TAGESAUFSCHLÜSSELUNG", UiKit.PURPLE));
                TextView daysIntro = UiKit.caption(this,
                        "Direkt aus den aktuell geladenen Octopus-Stundenintervallen:");
                daysIntro.setPadding(0, dp(6), 0, dp(4));
                daysCard.addView(daysIntro);

                int shown = 0;
                for (EnergyPeriod day : recentDays) {
                    if (shown >= 4) break;
                    String date = day.date == null ? "—"
                            : day.date.format(DateTimeFormatter.ofPattern("dd.MM."));
                    String completeness = day.intervalCount >= 24
                            ? "vollständig"
                            : day.intervalCount + "/24 h-Werte";
                    TextView line = UiKit.text(this,
                            String.format(Locale.GERMANY,
                                    "%s  ·  %.2f kWh  ·  HZ %.2f / NZ %.2f  ·  %s",
                                    date, day.gridKwh, day.normalKwh, day.cheapKwh, completeness),
                            13, UiKit.INK);
                    line.setPadding(0, dp(5), 0, dp(5));
                    daysCard.addView(line);
                    shown++;
                }
                content.addView(daysCard);
            }

            if (octopus.dayValidation != null) {
                OctopusClient.DayValidation v = octopus.dayValidation;
                LinearLayout validation = UiKit.card(this);
                validation.addView(UiKit.overline(this,
                        v.consistent ? "TAGESWERT BESTÄTIGT" : "TAGESWERT ABWEICHUNG",
                        v.consistent ? UiKit.GREEN : UiKit.AMBER));
                String date = friendlyOctopusDate(v.date);
                TextView vh = UiKit.text(this, date + " · Plausibilitätscheck", 17, UiKit.INK);
                vh.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                vh.setPadding(0, dp(7), 0, dp(5));
                validation.addView(vh);

                String hour = v.hourCount > 0
                        ? String.format(Locale.GERMANY, "Stunde: %.2f kWh · %d Werte", v.hourKwh, v.hourCount)
                        : "Stunde: —";
                String raw = v.rawCount > 0
                        ? String.format(Locale.GERMANY, "15 Min: %.2f kWh · %d Werte", v.rawKwh, v.rawCount)
                        : "15 Min: —";
                String day = v.dayCount > 0
                        ? String.format(Locale.GERMANY, "Tag: %.2f kWh · %d Wert(e)", v.dayKwh, v.dayCount)
                        : "Tag: —";

                String meta = "";
                if (v.source != null && !v.source.isEmpty()) meta += "\nQuelle: " + v.source;
                if (v.quality != null && !v.quality.isEmpty()) meta += " · Qualität: " + v.quality;
                if (v.direction != null && !v.direction.isEmpty()) meta += " · Richtung: " + v.direction;

                TextView vd = UiKit.caption(this,
                        hour + "\n" + raw + "\n" + day
                                + "\nVerwendet: " + (v.preferredSource == null || v.preferredSource.isEmpty()
                                    ? "—"
                                    : v.preferredSource + " · "
                                        + String.format(Locale.GERMANY, "%.2f kWh", v.preferredKwh))
                                + "\n" + v.note + meta);
                vd.setLineSpacing(0, 1.14f);
                validation.addView(vd);
                content.addView(validation);
            }

            LinearLayout diagnostic = UiKit.card(this);
            diagnostic.addView(UiKit.overline(this, "SMART-METER PRÜFUNG",
                    meterData ? UiKit.GREEN : UiKit.AMBER));
            String meterExpected = octopus.smartMeterExpected
                    ? "von Octopus als Smart-Meter-Datenquelle bestätigt"
                    : "im Konto nicht eindeutig als Smart Meter bestätigt";
            String last = octopus.latestReadingAt != null && !octopus.latestReadingAt.isEmpty()
                    ? friendlyOctopusDateTime(octopus.latestReadingAt)
                    : (octopus.latestDate != null && !octopus.latestDate.isEmpty()
                            ? friendlyOctopusDate(octopus.latestDate)
                            : "—");
            TextView diag = UiKit.caption(this,
                    "Zählerstatus: " + meterExpected
                            + "\nMesswerte verfügbar: " + (meterData ? "ja" : "nein")
                            + "\nLetzter verfügbarer Wert: " + last
                            + "\nHistorientage: " + octopus.historyDayCount);
            diag.setLineSpacing(0, 1.14f);
            diag.setPadding(0, dp(6), 0, 0);
            diagnostic.addView(diag);
            content.addView(diagnostic);

            LinearLayout note = UiKit.card(this);
            note.addView(UiKit.overline(this, "STATUS", UiKit.GREEN));
            TextView n = UiKit.caption(this, octopus.note);
            n.setPadding(0, dp(6), 0, 0);
            note.addView(n);
            content.addView(note);

            showEnergyStatistics();
        } else {
            LinearLayout connect = UiKit.card(this);
            connect.addView(UiKit.overline(this, "VERBINDUNG", UiKit.PURPLE));
            TextView h = UiKit.text(this,
                    octopusError.isEmpty() ? "Noch nicht verbunden" : "Verbindung fehlgeschlagen",
                    19, UiKit.INK);
            h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            h.setPadding(0, dp(7), 0, dp(5));
            connect.addView(h);
            connect.addView(UiKit.caption(this,
                    octopusError.isEmpty()
                            ? "Öffne die Einstellungen und tippe dort auf „Octopus verbinden“."
                            : octopusError));
            content.addView(connect);
        }

        boolean hasOctopusAccess = !prefs.get("oct_account", "").isEmpty()
                && (!prefs.getSecret("oct_api_key").isEmpty() || !prefs.getSecret("oct_refresh").isEmpty());
        Button test = UiKit.primaryButton(this,
                hasOctopusAccess ? "Smart-Meter-Daten aktualisieren" : "Octopus einrichten");
        test.setOnClickListener(v -> {
            if (hasOctopusAccess) refreshOctopus();
            else showTab("Einstellungen");
        });
        content.addView(test, buttonLp());

        Button docs = UiKit.secondaryButton(this, "Kraken API-Dokumentation");
        docs.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://developer.oeg-kraken.energy/"))));
        content.addView(docs, buttonLp());

        TextView apiNote = UiKit.caption(this,
                "E-Mail + Passwort werden nur für die erstmalige Anmeldung verwendet und nicht gespeichert. Der danach von Octopus gelieferte Refresh Token wird verschlüsselt im Android Keystore gespeichert. Smart-Meter-Werte können bei Octopus zeitverzögert eintreffen.");
        apiNote.setPadding(dp(4), dp(4), dp(4), dp(10));
        content.addView(apiNote);
    }

    private void showStatistics() {
        pageHeader("Statistik", "Deine Energie-Bilanz über Monate und Jahre");

        if (pv == null || octopus == null || !octopus.smartMeterDataAvailable) {
            LinearLayout missing = UiKit.card(this);
            missing.addView(UiKit.overline(this, "DATENBASIS", UiKit.AMBER));
            TextView title = UiKit.text(this, "PVOutput und Octopus werden benötigt", 19, UiKit.INK);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setPadding(0, dp(7), 0, dp(5));
            missing.addView(title);
            missing.addView(UiKit.caption(this,
                    "Für die Gesamtstatistik kombiniert PV Compact deinen PV-Ertrag aus PVOutput "
                            + "mit dem Netzbezug aus dem Octopus Smart Meter."));
            content.addView(missing);
            addVersionFooter();
            return;
        }

        List<EnergyPeriod> months = monthlyEnergyPeriodsChronological();
        EnergyPeriod overall = currentYearEnergyPeriod();
        int currentYear = LocalDate.now(BERLIN).getYear();

        content.addView(UiKit.sectionTitle(this, currentYear + " bisher"));

        LinearLayout r1 = UiKit.row(this);
        r1.setBaselineAligned(false);
        r1.addView(metric("AUTARKIE",
                overall.hasPv && overall.hasOctopus
                        ? String.format(Locale.GERMANY, "%.1f %%", autonomyPercent(overall))
                        : "—",
                "PV / (PV + Netzbezug)",
                UiKit.GREEN, UiKit.MINT), metricLp(true));
        r1.addView(metric("NETZBEZUG",
                overall.hasOctopus
                        ? String.format(Locale.GERMANY, "%.0f kWh", overall.gridKwh)
                        : "—",
                "Haupt- + Nebenzeit",
                UiKit.BLUE, UiKit.BLUE_SOFT), metricLp(false));
        content.addView(r1);

        LinearLayout r2 = UiKit.row(this);
        r2.setBaselineAligned(false);
        r2.addView(metric("PV-ERTRAG",
                overall.hasPv
                        ? String.format(Locale.GERMANY, "%.0f kWh", overall.pvKwh)
                        : "—",
                "PVOutput · " + LocalDate.now(BERLIN).getYear() + " bisher",
                UiKit.GREEN, UiKit.MINT), metricLp(true));
        r2.addView(metric("KOSTEN",
                overall.hasOctopus
                        ? String.format(Locale.GERMANY, "%.2f €", overall.costEuro)
                        : "—",
                "Netzbezug · geschätzt",
                UiKit.PURPLE, UiKit.PURPLE_SOFT), metricLp(false));
        content.addView(r2);

        TextView note = UiKit.caption(this,
                "Autarkie ist eine Näherung aus PV-Ertrag ÷ (PV-Ertrag + Netzbezug). "
                        + "Die obere Zusammenfassung bezieht sich nur auf das aktuelle Jahr. "
                        + octopusCoverageText()
                        + " Bei deiner Null-Einspeisung ist das eine praktische Kennzahl; "
                        + "Batterie-Ladezustand an Periodengrenzen und Umwandlungsverluste können den Wert leicht verschieben.");
        note.setPadding(dp(4), 0, dp(4), dp(8));
        content.addView(note);

        showMeterRegisterStatistics();

        if (months.isEmpty()) {
            LinearLayout empty = UiKit.card(this);
            empty.addView(UiKit.caption(this, "Noch keine Monatsdaten verfügbar."));
            content.addView(empty);
            addVersionFooter();
            return;
        }

        content.addView(UiKit.sectionTitle(this, "Monatsverlauf · letzte 12 Monate"));
        addStatsChartCard("PV und Netzbezug",
                "Wie sich eigener Solarstrom und Strom aus dem Netz gegenüberstehen.",
                energyChart(months, SimpleEnergyChartView.TYPE_GROUPED_BAR,
                        new String[]{"PV", "Netz"},
                        new int[]{UiKit.GREEN, UiKit.BLUE},
                        new ValuePicker[]{p -> p.pvKwh, p -> p.gridKwh},
                        "kWh"));

        addStatsChartCard("Netzbezug Haupt- / Nebenzeit",
                "Nebenzeit entspricht deinem Octopus-Go-Günstigfenster.",
                energyChart(months, SimpleEnergyChartView.TYPE_STACKED_BAR,
                        new String[]{"Hauptzeit", "Nebenzeit"},
                        new int[]{UiKit.BLUE, UiKit.PURPLE},
                        new ValuePicker[]{p -> p.normalKwh, p -> p.cheapKwh},
                        "kWh"));

        addStatsChartCard("Stromkosten pro Monat",
                "Geschätzte Kosten des Netzbezugs auf Basis der aktuell hinterlegten Tarifpreise.",
                energyChart(months, SimpleEnergyChartView.TYPE_GROUPED_BAR,
                        new String[]{"Kosten"},
                        new int[]{UiKit.PURPLE},
                        new ValuePicker[]{p -> p.costEuro},
                        "€"));

        addStatsChartCard("Autarkiegrad pro Monat",
                "Je höher, desto größer der Anteil deines Energiebedarfs, den die PV abdeckt.",
                energyChart(months, SimpleEnergyChartView.TYPE_LINE,
                        new String[]{"Autarkie"},
                        new int[]{UiKit.GREEN},
                        new ValuePicker[]{this::autonomyPercent},
                        "%"));

        List<EnergyPeriod> years = yearlyEnergyPeriodsChronological();
        if (!years.isEmpty()) {
            content.addView(UiKit.sectionTitle(this, "Jahresvergleich"));
            addStatsChartCard("PV und Netzbezug nach Jahr",
                    "Aktuelles Jahr ist der bisher verfügbare Stand.",
                    energyChart(years, SimpleEnergyChartView.TYPE_GROUPED_BAR,
                            new String[]{"PV", "Netz"},
                            new int[]{UiKit.GREEN, UiKit.BLUE},
                            new ValuePicker[]{p -> p.pvKwh, p -> p.gridKwh},
                            "kWh"));

            addStatsChartCard("Autarkie nach Jahr",
                    "Vergleich der verfügbaren Jahreswerte.",
                    energyChart(years, SimpleEnergyChartView.TYPE_GROUPED_BAR,
                            new String[]{"Autarkie"},
                            new int[]{UiKit.GREEN},
                            new ValuePicker[]{this::autonomyPercent},
                            "%"));
        }

        content.addView(UiKit.sectionTitle(this, "Monatswerte"));
        addPeriodStrip("Monate", monthlyEnergyPeriods());

        Button refresh = UiKit.primaryButton(this, "Statistik aktualisieren");
        refresh.setOnClickListener(v -> refreshAll());
        content.addView(refresh, buttonLp());

        addVersionFooter();
    }

    private void showMeterRegisterStatistics() {
        content.addView(UiKit.sectionTitle(this, "Zähler & Datenabgleich"));

        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.overline(this, "OCTOPUS ZÄHLERREGISTER", UiKit.BLUE));

        OctopusClient.MeterRegisterSummary m =
                octopus == null ? null : octopus.meterRegister;

        if (m == null || !m.available || !Double.isFinite(m.currentValue)) {
            TextView missing = UiKit.caption(this,
                    "Octopus liefert aktuell keinen kumulierten Register-Zählerstand. "
                            + "Die Intervallstatistik bleibt davon unabhängig verfügbar.");
            missing.setPadding(0, dp(7), 0, 0);
            card.addView(missing);
            content.addView(card);
            return;
        }

        String meterLabel = m.meterNumber == null || m.meterNumber.isEmpty()
                ? "Stromzähler"
                : "Stromzähler " + maskedMeterNumber(m.meterNumber);
        TextView h = UiKit.text(this,
                String.format(Locale.GERMANY, "%.1f kWh", m.currentValue),
                28, UiKit.INK);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setPadding(0, dp(7), 0, dp(3));
        card.addView(h);

        String latestAt = friendlyOctopusDateTime(m.currentReadAt);
        String registerInfo = meterLabel
                + " · " + (m.obisCode == null || m.obisCode.isEmpty() ? "Register" : "OBIS " + m.obisCode)
                + "\nLetzter Registerstand: " + latestAt;
        if (m.currentTypeOfRead != null && !m.currentTypeOfRead.isEmpty()) {
            registerInfo += " · " + m.currentTypeOfRead;
        }
        TextView info = UiKit.caption(this, registerInfo);
        info.setLineSpacing(0, 1.12f);
        card.addView(info);

        LocalDate latestDate = parseOctopusDateTimeToDate(m.currentReadAt);
        LocalDate earliestDate = parseOctopusDateTimeToDate(m.earliestReadAt);

        if (Double.isFinite(m.earliestValue)
                && earliestDate != null
                && latestDate != null
                && !latestDate.isBefore(earliestDate)
                && m.currentValue >= m.earliestValue) {

            double registerDelta = m.currentValue - m.earliestValue;
            EnergyPeriod interval = energyForRange(
                    earliestDate, latestDate, "Registervergleich");
            double intervalKwh = interval.hasOctopus ? interval.gridKwh : Double.NaN;
            double difference = Double.isFinite(intervalKwh)
                    ? registerDelta - intervalKwh
                    : Double.NaN;

            card.addView(UiKit.divider(this), dividerLp());

            TextView compare = UiKit.caption(this,
                    "Ältester verfügbarer Registerstand: "
                            + String.format(Locale.GERMANY, "%.1f kWh", m.earliestValue)
                            + " · " + earliestDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                            + "\nRegister-Verbrauch im gleichen Zeitraum: "
                            + String.format(Locale.GERMANY, "%.1f kWh", registerDelta)
                            + "\nIntervallsumme im gleichen Zeitraum: "
                            + (Double.isFinite(intervalKwh)
                                ? String.format(Locale.GERMANY, "%.1f kWh", intervalKwh)
                                : "—")
                            + "\nDifferenz Register – Intervalle: "
                            + (Double.isFinite(difference)
                                ? String.format(Locale.GERMANY, "%+.1f kWh", difference)
                                : "—"));
            compare.setLineSpacing(0, 1.15f);
            card.addView(compare);
        } else {
            LocalDate firstHistory = earliestOctopusHistoryDate();
            LocalDate lastHistory = latestOctopusHistoryDate();
            if (firstHistory != null && lastHistory != null) {
                EnergyPeriod interval = energyForRange(firstHistory, lastHistory, "Historie");
                card.addView(UiKit.divider(this), dividerLp());
                TextView compare = UiKit.caption(this,
                        "Verfügbare Intervallsumme: "
                                + String.format(Locale.GERMANY, "%.1f kWh", interval.gridKwh)
                                + " · " + firstHistory.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                                + "–" + lastHistory.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                                + "\nDer kumulierte Zählerstand ist nicht direkt mit dieser Summe vergleichbar, "
                                + "solange Octopus keinen älteren Registerstand desselben Zählers liefert.");
                compare.setLineSpacing(0, 1.15f);
                card.addView(compare);
            }
        }

        TextView hint = UiKit.caption(this,
                "Wichtig: Zählerstand = kumuliertes Register. Netzbezug in den Statistiken = Summe der von Octopus gelieferten Zeitintervalle. "
                        + "Eine Differenz weist daher zunächst auf unterschiedliche Datenabdeckung oder einen fehlenden Start-Registerstand hin, nicht automatisch auf einen Abrechnungsfehler.");
        hint.setPadding(0, dp(9), 0, 0);
        card.addView(hint);

        content.addView(card);
    }

    private LocalDate earliestOctopusHistoryDate() {
        if (octopus == null || octopus.dailyHistory.isEmpty()) return null;
        LocalDate earliest = null;
        for (OctopusClient.DailyUsage d : octopus.dailyHistory) {
            try {
                LocalDate date = LocalDate.parse(d.date);
                if (earliest == null || date.isBefore(earliest)) earliest = date;
            } catch (Exception ignored) {}
        }
        return earliest;
    }

    private LocalDate latestOctopusHistoryDate() {
        if (octopus == null || octopus.dailyHistory.isEmpty()) return null;
        LocalDate latest = null;
        for (OctopusClient.DailyUsage d : octopus.dailyHistory) {
            try {
                LocalDate date = LocalDate.parse(d.date);
                if (latest == null || date.isAfter(latest)) latest = date;
            } catch (Exception ignored) {}
        }
        return latest;
    }

    private LocalDate parseOctopusDateTimeToDate(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return java.time.Instant.parse(value).atZone(BERLIN).toLocalDate();
        } catch (Exception ignored) {}
        try {
            return java.time.OffsetDateTime.parse(value).atZoneSameInstant(BERLIN).toLocalDate();
        } catch (Exception ignored) {}
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {}
        return null;
    }

    private String maskedMeterNumber(String number) {
        if (number == null || number.isEmpty()) return "";
        String n = number.trim();
        if (n.length() <= 4) return n;
        return "…" + n.substring(n.length() - 4);
    }

    private void addVersionFooter() {
        TextView footer = UiKit.caption(this,
                "PV Compact · Version " + appVersionName());
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(8), dp(12), dp(8), dp(18));
        content.addView(footer);
    }

    private String appVersionName() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = info.versionName;
            return version == null || version.trim().isEmpty() ? "—" : version;
        } catch (Exception ignored) {
            return "—";
        }
    }

    private void addStatsChartCard(String title, String detail, SimpleEnergyChartView chart) {
        LinearLayout card = UiKit.card(this);
        TextView h = UiKit.text(this, title, 17, UiKit.INK);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(h);

        TextView d = UiKit.caption(this, detail);
        d.setPadding(0, dp(3), 0, dp(4));
        card.addView(d);

        card.addView(chart, new LinearLayout.LayoutParams(-1, dp(250)));
        content.addView(card);
    }

    private interface ValuePicker {
        double get(EnergyPeriod period);
    }

    private SimpleEnergyChartView energyChart(
            List<EnergyPeriod> periods,
            int type,
            String[] names,
            int[] colors,
            ValuePicker[] pickers,
            String suffix) {

        List<String> labels = new ArrayList<>();
        for (EnergyPeriod p : periods) labels.add(shortPeriodLabel(p.label));

        List<SimpleEnergyChartView.Series> series = new ArrayList<>();
        for (int s = 0; s < pickers.length; s++) {
            double[] values = new double[periods.size()];
            for (int i = 0; i < periods.size(); i++) {
                values[i] = Math.max(0, pickers[s].get(periods.get(i)));
            }
            series.add(new SimpleEnergyChartView.Series(names[s], values, colors[s]));
        }
        return new SimpleEnergyChartView(this, type, labels, series, suffix);
    }

    private String shortPeriodLabel(String label) {
        if (label == null || label.isEmpty()) return "";
        String[] parts = label.split(" ");
        if (parts.length >= 2 && parts[0].length() > 2) {
            String month = parts[0].substring(0, Math.min(3, parts[0].length()));
            String year = parts[1].length() >= 4 ? parts[1].substring(2) : parts[1];
            return month + " " + year;
        }
        if (label.contains(" · ")) return label.substring(0, label.indexOf(" · "));
        return label;
    }

    private List<EnergyPeriod> monthlyEnergyPeriodsChronological() {
        List<EnergyPeriod> out = monthlyEnergyPeriods();
        Collections.reverse(out);
        return out;
    }

    private List<EnergyPeriod> yearlyEnergyPeriodsChronological() {
        List<EnergyPeriod> out = yearlyEnergyPeriods();
        Collections.reverse(out);
        return out;
    }

    private EnergyPeriod currentYearEnergyPeriod() {
        int year = LocalDate.now(BERLIN).getYear();
        EnergyPeriod p = energyForRange(
                LocalDate.of(year, 1, 1),
                LocalDate.of(year, 12, 31),
                year + " bisher");
        p.pvKwh = pvForYear(year);
        p.hasPv = hasPvYear(year);
        return p;
    }

    private String octopusCoverageText() {
        if (octopus == null || octopus.dailyHistory.isEmpty()) {
            return "Für Octopus liegen noch keine historischen Messwerte vor.";
        }
        int year = LocalDate.now(BERLIN).getYear();
        LocalDate earliest = null;
        LocalDate latest = null;
        for (OctopusClient.DailyUsage d : octopus.dailyHistory) {
            try {
                LocalDate date = LocalDate.parse(d.date);
                if (date.getYear() != year) continue;
                if (earliest == null || date.isBefore(earliest)) earliest = date;
                if (latest == null || date.isAfter(latest)) latest = date;
            } catch (Exception ignored) {}
        }
        if (earliest == null || latest == null) {
            return "Für " + year + " liegen noch keine Octopus-Messwerte vor.";
        }
        return "Octopus-Abdeckung: "
                + earliest.format(DateTimeFormatter.ofPattern("dd.MM."))
                + "–" + latest.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + ".";
    }

    private String friendlyOctopusDate(String value) {
        if (value == null || value.isEmpty()) return "—";
        try {
            return LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        } catch (Exception ignored) {
            return value;
        }
    }

    private String friendlyOctopusDateTime(String value) {
        if (value == null || value.isEmpty()) return "—";
        try {
            return java.time.Instant.parse(value).atZone(BERLIN)
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        } catch (Exception ignored) {}
        try {
            return java.time.OffsetDateTime.parse(value).atZoneSameInstant(BERLIN)
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        } catch (Exception ignored) {}
        return value;
    }

    private EnergyPeriod overallEnergyPeriod(List<EnergyPeriod> months) {
        EnergyPeriod out = new EnergyPeriod();
        out.label = "Gesamt";
        for (EnergyPeriod p : months) {
            if (p.hasPv) {
                out.pvKwh += p.pvKwh;
                out.hasPv = true;
            }
            if (p.hasOctopus) {
                out.gridKwh += p.gridKwh;
                out.cheapKwh += p.cheapKwh;
                out.normalKwh += p.normalKwh;
                out.costEuro += p.costEuro;
                out.hasOctopus = true;
            }
        }
        return out;
    }

    private double autonomyPercent(EnergyPeriod p) {
        if (p == null || !p.hasPv || !p.hasOctopus) return 0.0;
        double total = p.pvKwh + p.gridKwh;
        if (total <= 0) return 0.0;
        return Math.max(0.0, Math.min(100.0, p.pvKwh / total * 100.0));
    }

    private void showSystem() {
        pageHeader("System & Automatik", "Raspberry, Batterie, Wärmepumpe und EV");

        boolean piConfigured = !prefs.get("controller_url", "").isEmpty();
        LinearLayout controller = UiKit.card(this);
        LinearLayout top = UiKit.row(this);
        top.addView(UiKit.overline(this, "RASPBERRY CONTROLLER", UiKit.GREEN),
                new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(UiKit.pill(this,
                piConfigured ? "VORBEREITET" : "OFFLINE",
                piConfigured ? UiKit.GREEN_DARK : UiKit.MUTED,
                piConfigured ? UiKit.MINT : Color.rgb(238, 241, 240)));
        controller.addView(top);

        TextView cTitle = UiKit.text(this,
                piConfigured ? "Controller-Adresse hinterlegt" : "Noch kein Pi verbunden",
                19, UiKit.INK);
        cTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cTitle.setPadding(0, dp(8), 0, dp(4));
        controller.addView(cTitle);
        controller.addView(UiKit.caption(this,
                piConfigured
                        ? prefs.get("controller_url", "")
                        : "Der Pi übernimmt später die echte Regelung. Die App bleibt Dashboard und Bedienoberfläche."));
        content.addView(controller);

        content.addView(UiKit.sectionTitle(this, "Batterie-Regeln"));
        LinearLayout rules = UiKit.row(this);
        rules.addView(metric("NORMAL",
                prefs.get("charge_soft_a", "60") + " A",
                "AC-Ladestrom",
                UiKit.GREEN, UiKit.MINT), metricLp(true));
        rules.addView(metric("HARD LIMIT",
                prefs.get("charge_hard_a", "70") + " A",
                "niemals überschreiten",
                UiKit.RED, UiKit.RED_SOFT), metricLp(false));
        content.addView(rules);

        LinearLayout reserve = UiKit.card(this);
        reserve.addView(UiKit.overline(this, "RESERVE-SOC", UiKit.BLUE));
        TextView rv = UiKit.value(this, prefs.get("reserve_soc", "20") + " %", UiKit.INK);
        rv.setPadding(0, dp(6), 0, dp(2));
        reserve.addView(rv);
        reserve.addView(UiKit.caption(this,
                "70-kWh-System · Ziel-SOC wird später aus Last, PV-Prognose und günstiger Octopus-Zeit berechnet."));
        content.addView(reserve);

        content.addView(UiKit.sectionTitle(this, "Flexible Verbraucher"));
        LinearLayout devices = UiKit.card(this);
        devices.addView(deviceLine("Panasonic Aquarea G", "Fußbodenheizung über Intesis / AC Cloud",
                prefs.getBool("heatpump_enabled", true), "FBH"));
        devices.addView(UiKit.divider(this), dividerLp());
        devices.addView(deviceLine("Chevy Volt", "3,7-kW-Lader / Nachtladung vorbereitet",
                prefs.getBool("ev_enabled", false), "EV"));
        content.addView(devices);

        String vacation = prefs.get("vacation_return", "");
        LinearLayout holiday = UiKit.card(this);
        holiday.addView(UiKit.overline(this, "URLAUBSMODUS", UiKit.AMBER));
        TextView ht = UiKit.text(this,
                vacation.isEmpty() ? "Zurzeit aus" : "Rückkehr " + vacation,
                19, UiKit.INK);
        ht.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ht.setPadding(0, dp(7), 0, dp(4));
        holiday.addView(ht);
        holiday.addView(UiKit.caption(this,
                "Die FBH soll später vor deiner Rückkehr automatisch wieder hochfahren — bevorzugt mit PV oder günstigem Strom."));
        content.addView(holiday);

        Button test = UiKit.primaryButton(this, "Raspberry-Verbindung prüfen");
        test.setOnClickListener(v -> checkController());
        content.addView(test, buttonLp());

        Button settings = UiKit.secondaryButton(this, "Automatik konfigurieren");
        settings.setOnClickListener(v -> showTab("Einstellungen"));
        content.addView(settings, buttonLp());

        LinearLayout safety = UiKit.card(this);
        safety.addView(UiKit.overline(this, "SICHERHEIT", UiKit.RED));
        TextView st = UiKit.caption(this,
                "Die App schreibt noch keine MPI10K-Befehle. Vor echter Steuerung läuft der Raspberry mehrere Tage im Read-only/Dry-run. Nulleinspeisung sowie BMS- und Wechselrichter-Schutzgrenzen werden nicht automatisch verändert.");
        st.setPadding(0, dp(6), 0, 0);
        safety.addView(st);
        content.addView(safety);
    }

    private View deviceLine(String title, String detail, boolean enabled, String tag) {
        LinearLayout row = UiKit.row(this);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView badge = UiKit.pill(this, tag,
                enabled ? UiKit.GREEN_DARK : UiKit.MUTED,
                enabled ? UiKit.MINT : Color.rgb(238, 241, 240));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(48), dp(32));
        bp.setMargins(0, 0, dp(12), 0);
        row.addView(badge, bp);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView t = UiKit.text(this, title, 15, UiKit.INK);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labels.addView(t);
        labels.addView(UiKit.text(this, detail, 12, UiKit.MUTED));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        row.addView(UiKit.pill(this,
                enabled ? "AN" : "AUS",
                enabled ? UiKit.GREEN_DARK : UiKit.MUTED,
                enabled ? UiKit.MINT : Color.rgb(238, 241, 240)));
        return row;
    }


    // -------------- WattWächter: cloud MQTT for live data --------------

    private void startWattMeter() {
        if (wattMeter != null) {
            wattMeter.close();
            wattMeter = null;
        }
        wattSnapshot = null;
        wattError = "";
        lastWattAttemptMs = System.currentTimeMillis();
        if (!prefs.getBool("watt_mqtt_enabled", false)) return;
        String host = prefs.get("watt_mqtt_host", "").trim();
        if (host.isEmpty()) return;
        try {
            wattMeter = new WattMeterMqttClient(
                    host,
                    parseInt(prefs.get("watt_mqtt_port", "8883"), 8883),
                    prefs.get("watt_mqtt_user", ""),
                    prefs.getSecret("watt_mqtt_password"),
                    prefs.get("watt_mqtt_topic", ""),
                    snapshot -> {
                        wattSnapshot = snapshot;
                        if (wattHistory != null && snapshot.livePowerReceivedAtMillis > 0L
                                && snapshot.powerW != null
                                && System.currentTimeMillis() - snapshot.livePowerReceivedAtMillis < 20000L) {
                            try {
                                wattHistory.add(snapshot.livePowerReceivedAtMillis, snapshot.powerW);
                                long when = System.currentTimeMillis();
                                if (snapshot.importKwh != null && snapshot.exportKwh != null
                                        && snapshot.freshImportReceivedAtMillis > 0
                                        && snapshot.freshExportReceivedAtMillis > 0
                                        && when - snapshot.freshImportReceivedAtMillis < 120000L
                                        && when - snapshot.freshExportReceivedAtMillis < 120000L)
                                    wattHistory.addRegisters(when,
                                            snapshot.importKwh, snapshot.exportKwh);
                            } catch (Exception ignored) {
                                // A broken history database must not stop the live telemetry.
                            }
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastWattUiAt < 900L) return;
                        lastWattUiAt = now;
                        runOnUiThread(this::updateWattMeterTexts);
                    }
            );
        } catch (Exception e) {
            wattError = cleanError(e);
        }
    }

    private boolean isWattFresh() {
        WattMeterMqttClient.Snapshot s = wattSnapshot;
        return s != null && s.powerW != null && s.livePowerReceivedAtMillis > 0
                && System.currentTimeMillis() - s.livePowerReceivedAtMillis <= 20000L;
    }

    private boolean isWattExporting() {
        return isWattFresh() && wattSnapshot.powerW < 0;
    }

    private String wattPowerLabel() {
        if (!isWattFresh()) return "—";
        return String.format(Locale.GERMANY, "%.0f W", Math.abs(wattSnapshot.powerW));
    }

    private String wattModeLabel() {
        if (!isWattFresh()) return "Netzleistung wird geladen";
        return isWattExporting() ? "Kurzfristige Rückspeisung" : "Aktueller Verbrauch";
    }

    private int wattActiveColor() {
        return isWattExporting() ? UiKit.GREEN_DARK : UiKit.RED;
    }

    private Double currentRegisterKwh() {
        WattMeterMqttClient.Snapshot s = wattSnapshot;
        if (s != null && s.importKwh != null && Double.isFinite(s.importKwh))
            return s.importKwh;
        WattMeterLocalHistory.Day d = localToday;
        if (d != null && LocalDate.now(BERLIN).toString().equals(d.date)
                && d.currentMeterKwh != null && Double.isFinite(d.currentMeterKwh))
            return d.currentMeterKwh;
        return null;
    }

    private String wattRegisterLabel() {
        Double value = currentRegisterKwh();
        if (value == null) return "—";
        return String.format(Locale.GERMANY, "%,.3f kWh", value);
    }

    private String annualImportLabel() {
        Double meter = currentRegisterKwh();
        if (meter == null) return "—";
        int year = LocalDate.now(BERLIN).getYear();
        if (year == 2026) {
            // Smart meter commissioned with zero baseline in March 2026.
            return String.format(Locale.GERMANY, "%,.1f kWh", meter);
        }
        WattMeterHistoryStore.YearAnchor anchor = null;
        try { anchor = wattHistory.yearAnchor(year); } catch (Exception ignored) {}
        if (anchor == null || meter < anchor.importKwh) return "—";
        return String.format(Locale.GERMANY, "%,.1f kWh", meter - anchor.importKwh);
    }

    private String annualPvLabel() {
        int year = LocalDate.now(BERLIN).getYear();
        return hasPvYear(year)
                ? String.format(Locale.GERMANY, "%,.1f kWh", pvForYear(year)) : "—";
    }

    private String wattYearLabel() {
        int year = LocalDate.now(BERLIN).getYear();
        if (year == 2026) return "Netzbezug seit Einbau des neuen Zählers im März 2026";
        WattMeterHistoryStore.YearAnchor anchor = null;
        try { anchor = wattHistory.yearAnchor(year); } catch (Exception ignored) {}
        if (anchor == null) return "Jahresbeginn noch nicht erfasst; kein geschätzter Nullwert";
        return "Seit erster Ablesung am " +
                new java.text.SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY)
                    .format(new java.util.Date(anchor.firstObservedMs)) + " Uhr";
    }

    private Double pvTodayKwh() {
        if (pv == null) return null;
        LocalDate today = LocalDate.now(BERLIN);
        try {
            if (pv.live != null
                    && today.equals(LocalDate.parse(pv.live.date, DateTimeFormatter.BASIC_ISO_DATE))
                    && Double.isFinite(pv.live.energyWh) && pv.live.energyWh >= 0)
                return pv.live.energyWh / 1000.0;
        } catch (Exception ignored) {}
        if (pv.recentDays != null) for (PvOutputClient.Day day : pv.recentDays) {
            try {
                if (day != null && today.equals(LocalDate.parse(day.date, DateTimeFormatter.BASIC_ISO_DATE))
                        && Double.isFinite(day.generatedWh) && day.generatedWh >= 0)
                    return day.generatedWh / 1000.0;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Double pvHouseTodayKwh() {
        if (pv == null) return null;
        LocalDate today = LocalDate.now(BERLIN);
        try {
            if (pv.live != null
                    && today.equals(LocalDate.parse(pv.live.date, DateTimeFormatter.BASIC_ISO_DATE))
                    && pv.live.consumptionWh != null
                    && Double.isFinite(pv.live.consumptionWh) && pv.live.consumptionWh >= 0)
                return pv.live.consumptionWh / 1000.0;
        } catch (Exception ignored) {}
        if (pv.recentDays != null) for (PvOutputClient.Day day : pv.recentDays) {
            try {
                if (day != null && today.equals(LocalDate.parse(day.date, DateTimeFormatter.BASIC_ISO_DATE))
                        && day.consumedWh != null && Double.isFinite(day.consumedWh)
                        && day.consumedWh >= 0)
                    return day.consumedWh / 1000.0;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static final class TodayTariffs {
        boolean available, splitAvailable, fromMeterHistory;
        double totalKwh, cheapKwh, normalKwh, estimatedCostEuro;
        String status;
    }

    private TodayTariffs todayTariffs() {
        TodayTariffs t = new TodayTariffs();
        WattMeterLocalHistory.Day saved = localToday;
        double cheapRate = parseDouble(prefs.get("cheap_price", "19"), 19);
        double normalRate = parseDouble(prefs.get("normal_price", "29"), 29);
        if (octopus != null && Double.isFinite(octopus.cheapCents)) cheapRate = octopus.cheapCents;
        if (octopus != null && Double.isFinite(octopus.normalCents)) normalRate = octopus.normalCents;

        if (saved != null && LocalDate.now(BERLIN).toString().equals(saved.date)) {
            t.available = true;
            t.fromMeterHistory = true;
            t.totalKwh = saved.importKwh;
            t.cheapKwh = saved.cheapKwh;
            t.normalKwh = saved.normalKwh;
            t.splitAvailable = saved.tariffSplitAvailable || saved.importKwh == 0;
            String stamp = new java.text.SimpleDateFormat("HH:mm", Locale.GERMANY)
                    .format(new java.util.Date(saved.fetchedAtMs));
            t.status = "WattWächter · 15-Min-Verlauf · Stand " + stamp + " Uhr";
        } else {
            long midnight = LocalDate.now(BERLIN).atStartOfDay(BERLIN).toInstant().toEpochMilli();
            WattMeterHistoryStore.DayEstimate e = null;
            try {
                e = wattHistory.estimateDay(midnight, System.currentTimeMillis(),
                        currentCheapStart(), currentCheapEnd());
            } catch (Exception ignored) {}
            if (e != null && e.intervals > 0) {
                t.available = true;
                t.totalKwh = e.totalKwh();
                t.cheapKwh = e.cheapKwh;
                t.normalKwh = e.normalKwh;
                t.splitAvailable = true;
                String stamp = new java.text.SimpleDateFormat("HH:mm", Locale.GERMANY)
                        .format(new java.util.Date(e.firstMs));
                t.status = "Nur Teilmessung seit " + stamp + " Uhr · kompletter Tageswert noch ausstehend";
            } else {
                t.status = "Tageshistorie wird geladen · keine künstlichen Nullwerte";
            }
        }
        if (t.splitAvailable) {
            t.estimatedCostEuro = (t.cheapKwh * cheapRate
                    + t.normalKwh * normalRate) / 100.0;
        }
        return t;
    }

    private String wattTodayLabel() {
        TodayTariffs t = todayTariffs();
        return t.available
                ? String.format(Locale.GERMANY, "%.2f kWh", t.totalKwh)
                : "—";
    }

    private String wattCheapLabel() {
        TodayTariffs t = todayTariffs();
        return t.splitAvailable
                ? String.format(Locale.GERMANY, "%.2f kWh", t.cheapKwh) : "—";
    }

    private String wattNormalLabel() {
        TodayTariffs t = todayTariffs();
        return t.splitAvailable
                ? String.format(Locale.GERMANY, "%.2f kWh", t.normalKwh) : "—";
    }

    private String wattCostLabel() {
        TodayTariffs t = todayTariffs();
        return t.splitAvailable
                ? String.format(Locale.GERMANY, "%.2f €", t.estimatedCostEuro) : "—";
    }

    private String wattStateLabel() {
        WattMeterMqttClient.Snapshot s = wattSnapshot;
        if (s == null) return wattError.isEmpty()
                ? "MQTT: Verbindung wird aufgebaut" : "MQTT: " + wattError;
        long age = System.currentTimeMillis() - s.livePowerReceivedAtMillis;
        if (s.livePowerReceivedAtMillis > 0 && age >= 0 && age < 20000)
            return "Live · letzte Messung vor " + age / 1000 + " s";
        return "MQTT: " + s.state + " · keine frischen Messwerte";
    }

    private List<WattMeterHistoryStore.Point> pvOutputPlotPoints(long fromMs, long toMs) {
        List<WattMeterHistoryStore.Point> result = new ArrayList<>();
        if (pv == null || pv.live == null) return result;
        LocalDate today = LocalDate.now(BERLIN);
        try {
            if (!today.equals(LocalDate.parse(pv.live.date, DateTimeFormatter.BASIC_ISO_DATE)))
                return result;
        } catch (Exception ignored) { return result; }
        if (pv.history != null) for (PvOutputClient.Point p : pv.history) {
            try {
                LocalTime time = LocalTime.parse(p.time);
                long ms = today.atTime(time).atZone(BERLIN).toInstant().toEpochMilli();
                if (ms >= fromMs && ms <= toMs && Double.isFinite(p.powerW) && p.powerW >= 0)
                    result.add(new WattMeterHistoryStore.Point(ms, p.powerW));
            } catch (Exception ignored) {}
        }
        try {
            LocalTime time = LocalTime.parse(pv.live.time);
            long ms = today.atTime(time).atZone(BERLIN).toInstant().toEpochMilli();
            if (ms >= fromMs && ms <= toMs && Double.isFinite(pv.live.powerW)
                    && pv.live.powerW >= 0)
                result.add(new WattMeterHistoryStore.Point(ms, pv.live.powerW));
        } catch (Exception ignored) {}
        result.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return result;
    }

    private void updateWattMeterTexts() {
        if (wattPowerText != null) {
            wattPowerText.setText(wattPowerLabel());
            wattPowerText.setTextColor(wattActiveColor());
        }
        if (wattModeText != null) {
            wattModeText.setText(wattModeLabel());
            wattModeText.setTextColor(wattActiveColor());
        }
        if (wattRegistersText != null) wattRegistersText.setText(wattRegisterLabel());
        if (wattStateText != null) wattStateText.setText(wattStateLabel());
        if (homeLiveBadge != null) {
            boolean live = isWattFresh();
            homeLiveBadge.setText(live ? "● Live" : "○ Warte");
            homeLiveBadge.setTextColor(live ? UiKit.GREEN_DARK : UiKit.MUTED);
        }
        long now = System.currentTimeMillis();
        if (now - lastWattChartAt < 5000L) return;
        lastWattChartAt = now;
        if (wattYearText != null) wattYearText.setText(wattYearLabel());
        if (wattAnnualImportText != null) wattAnnualImportText.setText(annualImportLabel());
        if (wattAnnualPvText != null) wattAnnualPvText.setText(annualPvLabel());
        if (wattMeterValueText != null) wattMeterValueText.setText(wattRegisterLabel());
        if (wattTodayText != null) wattTodayText.setText(wattTodayLabel());
        if (wattTodayCheapText != null) wattTodayCheapText.setText(wattCheapLabel());
        if (wattTodayNormalText != null) wattTodayNormalText.setText(wattNormalLabel());
        if (wattTodayCostText != null) wattTodayCostText.setText(wattCostLabel());
        if (wattTodaySourceText != null) wattTodaySourceText.setText(todayTariffs().status);
        if (homePvLiveText != null) homePvLiveText.setText(pvNowLabel());
        if (homePvTodayText != null) homePvTodayText.setText(pvTodayLabel());
        if (homePvSavingText != null) homePvSavingText.setText(annualPvSavingsLabel());
        if (homeDayCostText != null) homeDayCostText.setText(displayNormalRate()
                + " ct/kWh · " + tariffCostLabel(false));
        if (homeNightCostText != null) homeNightCostText.setText(displayCheapRate()
                + " ct/kWh · " + tariffCostLabel(true));
        if ("Home".equals(currentTab)) updateHomeTariffStatus();
        if (wattLocalStatusText != null) wattLocalStatusText.setText("Synchronisierung: " + localHistoryStatus);
        if (!("Netz".equals(currentTab) || "Home".equals(currentTab))) return;
        try {
            if ("Netz".equals(currentTab) && wattHourChart != null) {
                long from = now - 3600000L;
                wattHourChart.setData(wattHistory.load(from, now), from, now, false);
                wattHourChart.setPvData(pvOutputPlotPoints(from, now));
                WattMeterLocalHistory.Day d = localToday;
                if (d != null && LocalDate.now(BERLIN).toString().equals(d.date))
                    wattHourChart.setHistoricalNetData(d.curve);
            }
            long start = LocalDate.now(BERLIN).atStartOfDay(BERLIN).toInstant().toEpochMilli();
            if ("Netz".equals(currentTab) && wattDayChart != null) {
                wattDayChart.setData(wattHistory.load(start, now), start, now, true);
                wattDayChart.setPvData(pvOutputPlotPoints(start, now));
                WattMeterLocalHistory.Day d = localToday;
                if (d != null && LocalDate.now(BERLIN).toString().equals(d.date))
                    wattDayChart.setHistoricalNetData(d.curve);
            }
            if ("Home".equals(currentTab) && wattHomeChart != null) {
                wattHomeChart.setData(wattHistory.load(start, now), start, now, true);
                wattHomeChart.setPvData(pvOutputPlotPoints(start, now));
                WattMeterLocalHistory.Day day = localToday;
                if (day != null && LocalDate.now(BERLIN).toString().equals(day.date))
                    wattHomeChart.setHistoricalNetData(day.curve);
            }
        } catch (Exception ignored) {}
    }

    private void showWattMeterCard() {
        showWattMeterCard(true);
    }

    private void showWattMeterCard(boolean homeGraph) {
        if (!prefs.getBool("watt_mqtt_enabled", false)) return;
        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.overline(this, "LIVE · NETZ & PV", UiKit.BLUE));

        wattModeText = UiKit.text(this, wattModeLabel(), 14, wattActiveColor());
        wattModeText.setPadding(0, dp(8), 0, dp(1));
        card.addView(wattModeText);
        wattPowerText = UiKit.text(this, wattPowerLabel(), 36, wattActiveColor());
        wattPowerText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wattPowerText.setPadding(0, dp(2), 0, dp(5));
        card.addView(wattPowerText);
        wattStateText = UiKit.caption(this, wattStateLabel());
        card.addView(wattStateText);

        View line = UiKit.divider(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(0, dp(11), 0, dp(11));
        card.addView(line, lp);

        card.addView(UiKit.overline(this, "AKTUELLER ZÄHLERSTAND", UiKit.MUTED));
        wattMeterValueText = UiKit.text(this, wattRegisterLabel(), 23, UiKit.INK);
        wattMeterValueText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wattMeterValueText.setPadding(0, dp(5), 0, dp(13));
        card.addView(wattMeterValueText);

        int year = LocalDate.now(BERLIN).getYear();
        LinearLayout annualRow = UiKit.row(this);
        annualRow.setBaselineAligned(false);
        LinearLayout gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        gridBox.setPadding(dp(12), dp(12), dp(9), dp(12));
        gridBox.setBackground(UiKit.roundRect(this, UiKit.RED_SOFT, 14));
        gridBox.addView(UiKit.overline(this, "JAHRESVERBRAUCH " + year, UiKit.RED));
        wattAnnualImportText = UiKit.text(this, annualImportLabel(), 19, UiKit.RED);
        wattAnnualImportText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wattAnnualImportText.setPadding(0, dp(6), 0, 0);
        gridBox.addView(wattAnnualImportText);
        annualRow.addView(gridBox, metricLp(true));

        LinearLayout pvBox = new LinearLayout(this);
        pvBox.setOrientation(LinearLayout.VERTICAL);
        pvBox.setPadding(dp(12), dp(12), dp(9), dp(12));
        pvBox.setBackground(UiKit.roundRect(this, UiKit.MINT, 14));
        pvBox.addView(UiKit.overline(this, "PV-ERZEUGUNG " + year, UiKit.GREEN_DARK));
        wattAnnualPvText = UiKit.text(this, annualPvLabel(), 19, UiKit.GREEN_DARK);
        wattAnnualPvText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wattAnnualPvText.setPadding(0, dp(6), 0, 0);
        pvBox.addView(wattAnnualPvText);
        annualRow.addView(pvBox, metricLp(false));
        card.addView(annualRow);

        wattYearText = UiKit.caption(this, wattYearLabel());
        wattYearText.setPadding(dp(3), 0, 0, dp(9));
        card.addView(wattYearText);
        View dayLine = UiKit.divider(this);
        LinearLayout.LayoutParams dpLine = new LinearLayout.LayoutParams(-1, dp(1));
        dpLine.setMargins(0, dp(7), 0, dp(12));
        card.addView(dayLine, dpLine);

        card.addView(UiKit.overline(this, "NETZBEZUG HEUTE", UiKit.RED));
        wattTodayText = UiKit.text(this, wattTodayLabel(), 29, UiKit.INK);
        wattTodayText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wattTodayText.setPadding(0, dp(5), 0, dp(10));
        card.addView(wattTodayText);

        LinearLayout tariffRow = UiKit.row(this);
        tariffRow.setBaselineAligned(false);
        LinearLayout cheapBox = new LinearLayout(this);
        cheapBox.setOrientation(LinearLayout.VERTICAL);
        cheapBox.setPadding(dp(12), dp(12), dp(9), dp(12));
        cheapBox.setBackground(UiKit.roundRect(this, UiKit.PURPLE_SOFT, 14));
        cheapBox.addView(UiKit.overline(this, "NACHTSTROM · 00–05 UHR", UiKit.PURPLE));
        wattTodayCheapText = UiKit.text(this, wattCheapLabel(), 19, UiKit.PURPLE);
        wattTodayCheapText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wattTodayCheapText.setPadding(0, dp(6), 0, dp(3));
        cheapBox.addView(wattTodayCheapText);
        cheapBox.addView(UiKit.caption(this, String.format(Locale.GERMANY,
                "%.0f ct/kWh", parseDouble(prefs.get("cheap_price", "19"), 19))));
        tariffRow.addView(cheapBox, metricLp(true));

        LinearLayout normalBox = new LinearLayout(this);
        normalBox.setOrientation(LinearLayout.VERTICAL);
        normalBox.setPadding(dp(12), dp(12), dp(9), dp(12));
        normalBox.setBackground(UiKit.roundRect(this, UiKit.AMBER_SOFT, 14));
        normalBox.addView(UiKit.overline(this, "TAGSTROM · AB 05 UHR", UiKit.AMBER));
        wattTodayNormalText = UiKit.text(this, wattNormalLabel(), 19, UiKit.AMBER);
        wattTodayNormalText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wattTodayNormalText.setPadding(0, dp(6), 0, dp(3));
        normalBox.addView(wattTodayNormalText);
        normalBox.addView(UiKit.caption(this, String.format(Locale.GERMANY,
                "%.0f ct/kWh", parseDouble(prefs.get("normal_price", "29"), 29))));
        tariffRow.addView(normalBox, metricLp(false));
        card.addView(tariffRow);

        LinearLayout costRow = UiKit.row(this);
        TextView costLabel = UiKit.text(this, "Stromkosten heute (geschätzt)", 14, UiKit.MUTED);
        costRow.addView(costLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        wattTodayCostText = UiKit.text(this, wattCostLabel(), 23, UiKit.INK);
        wattTodayCostText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        costRow.addView(wattTodayCostText);
        card.addView(costRow);

        wattTodaySourceText = UiKit.caption(this, todayTariffs().status);
        wattTodaySourceText.setPadding(0, dp(8), 0, dp(5));
        card.addView(wattTodaySourceText);
        wattLocalStatusText = UiKit.caption(this, "Synchronisierung: " + localHistoryStatus);
        card.addView(wattLocalStatusText);

        Double pvDay = pvTodayKwh();
        if (pvDay != null) {
            TextView pvToday = UiKit.text(this,
                    String.format(Locale.GERMANY, "PV-Ertrag heute: %.2f kWh", pvDay),
                    14, UiKit.GREEN_DARK);
            pvToday.setPadding(0, dp(10), 0, 0);
            card.addView(pvToday);
            double normalCent = parseDouble(prefs.get("normal_price", "29"), 29);
            card.addView(UiKit.caption(this,
                    String.format(Locale.GERMANY,
                        "Theoretisches PV-Wertpotenzial: %.2f € bei %.0f ct/kWh – keine tatsächliche Eigenverbrauchsersparnis",
                        pvDay * normalCent / 100.0, normalCent)));
        }

        if (homeGraph) {
            long now = System.currentTimeMillis();
            long start = LocalDate.now(BERLIN).atStartOfDay(BERLIN).toInstant().toEpochMilli();
            TextView graphTitle = UiKit.text(this, "Heute · PV und Netzleistung", 16, UiKit.INK);
            graphTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            graphTitle.setPadding(0, dp(18), 0, dp(4));
            card.addView(graphTitle);
            wattHomeChart = new GridPowerChartView(this);
            try {
                wattHomeChart.setData(wattHistory.load(start, now), start, now, true);
                wattHomeChart.setPvData(pvOutputPlotPoints(start, now));
                WattMeterLocalHistory.Day day = localToday;
                if (day != null && LocalDate.now(BERLIN).toString().equals(day.date))
                    wattHomeChart.setHistoricalNetData(day.curve);
            } catch (Exception ignored) {}
            card.addView(wattHomeChart, new LinearLayout.LayoutParams(-1, dp(218)));
            card.addView(UiKit.caption(this,
                    "Rot: Netzbezug · Grün: PV-Erzeugung. Lücken bleiben sichtbar."));
        }

        Button details = UiKit.secondaryButton(this, "Netzverlauf & Tarifhistorie");
        details.setOnClickListener(v -> showTab("Netz"));
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-1, dp(44));
        button.setMargins(0, dp(11), 0, 0);
        card.addView(details, button);
        content.addView(card);
    }

    private void showGridMeter() {
        pageHeader("Netzbezug live", "WattWächter + PVOutput · aktuelle Netzleistung und Verlauf");
        if (!prefs.getBool("watt_mqtt_enabled", false)) {
            emptyState("WattWächter noch nicht verbunden",
                    "Unter Einstellungen kannst du deinen TLS-MQTT-Broker konfigurieren.",
                    "MQTT einrichten", v -> showTab("Einstellungen"));
            return;
        }
        showWattMeterCard(false);
        long now = System.currentTimeMillis();
        long start = LocalDate.now(BERLIN).atStartOfDay(BERLIN).toInstant().toEpochMilli();
        content.addView(UiKit.sectionTitle(this, "Leistung · letzte 60 Minuten"));
        wattHourChart = addWattGraph(now - 3600000L, now, false);
        content.addView(UiKit.sectionTitle(this, "Leistung · heute"));
        wattDayChart = addWattGraph(start, now, true);
        TextView note = UiKit.caption(this,
                "Rot: tatsächlicher Netzbezug am Zähler. Dunkelgrün: PV-Leistung aus PVOutput. "
                        + "Die Werte sind unterschiedlich zeitlich aufgelöst. Fehlende Zeiten werden nicht interpoliert. "
                        + "Der Gesamtverbrauch im Haus kann ohne separate Batterieflüsse nicht zuverlässig aus beiden Kurven berechnet werden.");
        note.setPadding(dp(5), dp(8), dp(5), dp(13));
        content.addView(note);
        content.addView(UiKit.sectionTitle(this, "Octopus · historische Tarifdaten"));
        showOctopusDailyTariffChart();
        showOctopusHourlyTariffChart();
    }

    private GridPowerChartView addWattGraph(long fromMs, long toMs, boolean day) {
        LinearLayout card = UiKit.card(this);
        GridPowerChartView chart = new GridPowerChartView(this);
        List<WattMeterHistoryStore.Point> points = Collections.emptyList();
        try { points = wattHistory.load(fromMs, toMs); } catch (Exception ignored) {}
        chart.setData(points, fromMs, toMs, day);
        chart.setPvData(pvOutputPlotPoints(fromMs, toMs));
        WattMeterLocalHistory.Day d = localToday;
        if (d != null && LocalDate.now(BERLIN).toString().equals(d.date))
            chart.setHistoricalNetData(d.curve);
        card.addView(chart, new LinearLayout.LayoutParams(-1, dp(240)));
        card.addView(UiKit.caption(this,
                "Echte Messwerte aus PVOutput und WattWächter · keine künstlichen Nullwerte"));
        content.addView(card);
        return chart;
    }

    private void showOctopusDailyTariffChart() {
        if (octopus == null || !octopus.smartMeterDataAvailable) return;
        List<EnergyPeriod> available = new ArrayList<>();
        LocalDate today = LocalDate.now(BERLIN);
        for (int n = 1; n <= 40 && available.size() < 10; n++) {
            LocalDate date = today.minusDays(n);
            EnergyPeriod p = energyForRange(date, date, date.format(DateTimeFormatter.ofPattern("dd.MM.")));
            if (hasUsableGridConsumption(p)) available.add(p);
        }
        if (available.isEmpty()) return;
        Collections.reverse(available);
        List<String> labels = new ArrayList<>();
        double[] cheap = new double[available.size()];
        double[] normal = new double[available.size()];
        for (int i = 0; i < available.size(); i++) {
            EnergyPeriod p = available.get(i);
            labels.add(p.label);
            cheap[i] = p.cheapKwh;
            normal[i] = p.normalKwh;
        }
        List<SimpleEnergyChartView.Series> series = new ArrayList<>();
        series.add(new SimpleEnergyChartView.Series("19 ct Nacht", cheap, UiKit.PURPLE));
        series.add(new SimpleEnergyChartView.Series("29 ct Tag", normal, UiKit.BLUE));
        addStatsChartCard("Netzbezug nach Tarif · letzte verfügbare Tage",
                "Nur von Octopus gelieferte Verbrauchstage. Noch ausstehende Tage fehlen, statt mit 0 kWh zu erscheinen. Die Kosten sind Näherungen mit dem hinterlegten Tarif.",
                new SimpleEnergyChartView(this, SimpleEnergyChartView.TYPE_STACKED_BAR,
                        labels, series, "kWh"));
    }

    private void showOctopusHourlyTariffChart() {
        if (octopus == null || octopus.intervals.isEmpty()) return;
        List<LocalDate> dates = new ArrayList<>();
        for (OctopusClient.Interval in : octopus.intervals) {
            LocalDate date = octopusIntervalDate(in.readAt);
            if (date != null && date.isBefore(LocalDate.now(BERLIN)) && !dates.contains(date))
                dates.add(date);
        }
        dates.sort(Collections.reverseOrder());
        LocalDate selected = null;
        for (LocalDate date : dates) {
            boolean[] seen = new boolean[24];
            for (OctopusClient.Interval in : octopus.intervals) {
                java.time.ZonedDateTime z = octopusIntervalDateTime(in.readAt);
                if (z != null && z.toLocalDate().equals(date)) seen[z.getHour()] = true;
            }
            int hours = 0;
            for (boolean b : seen) if (b) hours++;
            // A DST transition day may have 23 unique local hour labels.
            if (hours >= 23) { selected = date; break; }
        }
        if (selected == null) return;
        double[] night = new double[24], normal = new double[24];
        for (OctopusClient.Interval in : octopus.intervals) {
            java.time.ZonedDateTime z = octopusIntervalDateTime(in.readAt);
            if (z == null || !z.toLocalDate().equals(selected)) continue;
            int hour = z.getHour();
            if (in.cheap) night[hour] += in.kwh;
            else normal[hour] += in.kwh;
        }
        List<String> labels = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++)
            labels.add(String.format(Locale.GERMANY, "%02d", hour));
        List<SimpleEnergyChartView.Series> series = new ArrayList<>();
        series.add(new SimpleEnergyChartView.Series("19 ct Nacht", night, UiKit.PURPLE));
        series.add(new SimpleEnergyChartView.Series("29 ct Tag", normal, UiKit.BLUE));
        addStatsChartCard("Octopus-Stundenprofil · "
                        + selected.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                "Der jüngste Tag mit mindestens 23 erfassten lokalen Stunden. Bei Zeitumstellungen können Stunden doppelt vorkommen oder fehlen. Messwerte sind historisch und nicht live.",
                new SimpleEnergyChartView(this, SimpleEnergyChartView.TYPE_STACKED_BAR,
                        labels, series, "kWh"));
    }

    private void showSettings() {
        pageHeader("Einstellungen", "Verbindungen und Automatik zentral konfigurieren");

        LinearLayout pvCard = settingsCard("PVOutput", "Erzeugung und historische PV-Daten");
        EditText pvSystem = addField(pvCard, "System-ID", prefs.get("pv_system", ""), false);
        EditText pvKey = addField(pvCard, "API-Key", prefs.getSecret("pv_key"), true);
        content.addView(pvCard);

        LinearLayout forecastCard = settingsCard("PV-Forecast", "Adresse statt Koordinaten eingeben");
        TextView addressHelp = UiKit.caption(this,
                "Gib einfach Straße, Hausnummer, PLZ und Ort ein. Die App ermittelt daraus automatisch Breiten- und Längengrad für die PV-Prognose.");
        addressHelp.setPadding(0, 0, 0, dp(9));
        forecastCard.addView(addressHelp);

        EditText forecastStreet = addField(forecastCard, "Straße + Hausnummer", prefs.get("forecast_street", ""), false);
        EditText forecastPostcode = addField(forecastCard, "PLZ", prefs.get("forecast_postcode", ""), false);
        EditText forecastCity = addField(forecastCard, "Ort", prefs.get("forecast_city", ""), false);

        String resolvedAddress = prefs.get("forecast_resolved", "");
        String resolvedLat = prefs.get("lat", "");
        String resolvedLon = prefs.get("lon", "");
        boolean hasResolvedLocation = !resolvedAddress.isEmpty() && !resolvedLat.isEmpty() && !resolvedLon.isEmpty();

        LinearLayout locationStatus = new LinearLayout(this);
        locationStatus.setOrientation(LinearLayout.VERTICAL);
        locationStatus.setPadding(dp(14), dp(12), dp(14), dp(12));
        locationStatus.setBackground(UiKit.outlined(this,
                hasResolvedLocation ? UiKit.MINT : Color.rgb(247, 249, 248),
                hasResolvedLocation ? Color.rgb(177, 224, 208) : UiKit.LINE,
                15));

        TextView locationState = UiKit.text(this,
                hasResolvedLocation ? "✓ Standort gefunden" : "○ Standort noch nicht bestätigt",
                15,
                hasResolvedLocation ? UiKit.GREEN_DARK : UiKit.MUTED);
        locationState.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        locationStatus.addView(locationState);

        TextView addressResolved = UiKit.caption(this,
                hasResolvedLocation ? resolvedAddress : "Bitte die Adresse eingeben und anschließend prüfen.");
        addressResolved.setPadding(0, dp(4), 0, dp(2));
        locationStatus.addView(addressResolved);

        TextView locationCoords = UiKit.text(this,
                hasResolvedLocation
                        ? "Breitengrad " + resolvedLat + " · Längengrad " + resolvedLon
                        : "Koordinaten werden automatisch ermittelt.",
                12, UiKit.MUTED);
        locationStatus.addView(locationCoords);

        LinearLayout.LayoutParams locationLp = new LinearLayout.LayoutParams(-1, -2);
        locationLp.setMargins(0, 0, 0, dp(10));
        forecastCard.addView(locationStatus, locationLp);

        Button locate = UiKit.secondaryButton(this,
                hasResolvedLocation ? "Adresse neu prüfen" : "Adresse finden");
        LinearLayout.LayoutParams locateLp = new LinearLayout.LayoutParams(-1, dp(48));
        locateLp.setMargins(0, 0, 0, dp(12));
        forecastCard.addView(locate, locateLp);

        locate.setOnClickListener(v -> geocodeForecastAddress(
                forecastStreet, forecastPostcode, forecastCity,
                locationStatus, locationState, addressResolved, locationCoords, locate));

        TextView plantTitle = UiKit.text(this, "PV-Anlage", 16, UiKit.INK);
        plantTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        plantTitle.setPadding(0, dp(6), 0, dp(10));
        forecastCard.addView(plantTitle);

        EditText kwp = addLabeledField(
                forecastCard,
                "Anlagengröße",
                "Gesamtleistung deiner PV-Module in kWp, z. B. 15.0",
                prefs.get("kwp", ""),
                false);

        EditText tilt = addLabeledField(
                forecastCard,
                "Dachneigung",
                "In Grad: 0° = flach, typische Schrägdächer liegen etwa bei 25–45°.",
                prefs.get("tilt", "30"),
                false);

        TextView orientationLabel = UiKit.text(this, "Dachausrichtung", 13, UiKit.INK);
        orientationLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        forecastCard.addView(orientationLabel);

        TextView orientationHelp = UiKit.caption(this,
                "Einfach die Himmelsrichtung auswählen – keine Azimut-Zahl nötig.");
        orientationHelp.setPadding(0, dp(2), 0, dp(5));
        forecastCard.addView(orientationHelp);

        Spinner orientation = new Spinner(this);
        String[] orientationItems = {
                "Süd", "Südost", "Südwest", "Ost",
                "West", "Nordost", "Nordwest", "Nord"
        };
        ArrayAdapter<String> orientationAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, orientationItems);
        orientationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        orientation.setAdapter(orientationAdapter);
        orientation.setSelection(orientationIndex(parseInt(prefs.get("azimuth", "0"), 0)));
        orientation.setPadding(dp(10), 0, dp(10), 0);
        orientation.setBackground(UiKit.outlined(this, Color.rgb(250, 252, 251), UiKit.LINE, 13));
        LinearLayout.LayoutParams orientationLp = new LinearLayout.LayoutParams(-1, dp(52));
        orientationLp.setMargins(0, 0, 0, dp(12));
        forecastCard.addView(orientation, orientationLp);

        TextView qualityNote = UiKit.caption(this,
                "Den technischen Systemfaktor übernimmt PV Compact automatisch. Du musst dafür nichts einstellen.");
        qualityNote.setPadding(dp(2), 0, dp(2), dp(4));
        forecastCard.addView(qualityNote);

        content.addView(forecastCard);

        LinearLayout octCard = settingsCard("Octopus Energy", "Konto verbinden und Go-Tarif einstellen");
        TextView octNote = UiKit.caption(this,
                "Du kannst Octopus direkt mit E-Mail + Passwort verbinden. Das Passwort wird nicht gespeichert; nach erfolgreicher Anmeldung bleibt nur der Refresh Token verschlüsselt im Android Keystore.");
        octNote.setPadding(0, 0, 0, dp(9));
        octCard.addView(octNote);

        EditText octAccount = addLabeledField(
                octCard,
                "Kundennummer",
                "Deine Octopus-Kundennummer identifiziert das Konto, ist aber allein noch kein API-Zugang.",
                prefs.get("oct_account", ""),
                false);

        boolean octHasAccount = !prefs.get("oct_account", "").trim().isEmpty();
        boolean octHasCredential = !prefs.getSecret("oct_api_key").isEmpty()
                || !prefs.getSecret("oct_refresh").isEmpty();

        LinearLayout octStatus = new LinearLayout(this);
        octStatus.setOrientation(LinearLayout.VERTICAL);
        octStatus.setPadding(dp(14), dp(12), dp(14), dp(12));
        int octStatusBg = octopus != null ? UiKit.MINT
                : (octHasCredential ? UiKit.AMBER_SOFT : Color.rgb(247, 249, 248));
        int octStatusLine = octopus != null ? Color.rgb(177, 224, 208)
                : (octHasCredential ? Color.rgb(245, 222, 158) : UiKit.LINE);
        octStatus.setBackground(UiKit.outlined(this, octStatusBg, octStatusLine, 15));

        String octStatusTitleText;
        String octStatusDetailText;
        int octStatusColor;
        if (octopus != null) {
            octStatusTitleText = "✓ Octopus verbunden";
            octStatusDetailText = "Smart-Meter-Zugriff funktioniert.";
            octStatusColor = UiKit.GREEN_DARK;
        } else if (!octopusError.isEmpty() && octHasCredential) {
            octStatusTitleText = "! Zugang gespeichert, Verbindung fehlgeschlagen";
            octStatusDetailText = octopusError;
            octStatusColor = UiKit.RED;
        } else if (octHasCredential) {
            octStatusTitleText = "Zugangsschlüssel gespeichert";
            octStatusDetailText = "Bereit zum Verbindungstest.";
            octStatusColor = UiKit.AMBER;
        } else if (octHasAccount) {
            octStatusTitleText = "Kundennummer vorhanden · Zugangsschlüssel fehlt";
            octStatusDetailText = "Tippe auf „Octopus verbinden“, um den API-Zugang zu hinterlegen.";
            octStatusColor = UiKit.MUTED;
        } else {
            octStatusTitleText = "Noch nicht eingerichtet";
            octStatusDetailText = "Kundennummer eintragen und anschließend Octopus verbinden.";
            octStatusColor = UiKit.MUTED;
        }

        TextView octStatusTitle = UiKit.text(this, octStatusTitleText, 15, octStatusColor);
        octStatusTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        octStatus.addView(octStatusTitle);
        TextView octStatusDetail = UiKit.caption(this, octStatusDetailText);
        octStatusDetail.setPadding(0, dp(4), 0, 0);
        octStatus.addView(octStatusDetail);

        LinearLayout.LayoutParams octStatusLp = new LinearLayout.LayoutParams(-1, -2);
        octStatusLp.setMargins(0, 0, 0, dp(10));
        octCard.addView(octStatus, octStatusLp);

        Button octConnect = octHasCredential
                ? UiKit.secondaryButton(this, "Octopus-Zugang ändern")
                : UiKit.primaryButton(this, "Octopus verbinden");
        octConnect.setOnClickListener(v -> {
            String accountValue = octAccount.getText().toString().trim();
            if (accountValue.isEmpty()) {
                Toast.makeText(this, "Bitte zuerst die Kundennummer eingeben.", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.put("oct_account", accountValue);
            showOctopusConnectionDialog(accountValue);
        });
        LinearLayout.LayoutParams octConnectLp = new LinearLayout.LayoutParams(-1, dp(48));
        octConnectLp.setMargins(0, 0, 0, dp(14));
        octCard.addView(octConnect, octConnectLp);

        TextView tariffTitle = UiKit.text(this, "Go-Tarif", 16, UiKit.INK);
        tariffTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tariffTitle.setPadding(0, dp(4), 0, dp(8));
        octCard.addView(tariffTitle);

        EditText cheapStart = addLabeledField(octCard, "Günstig ab", "Beginn des günstigen Ladefensters, z. B. 00:00", prefs.get("cheap_start", "00:00"), false);
        EditText cheapEnd = addLabeledField(octCard, "Günstig bis", "Ende des günstigen Ladefensters, z. B. 05:00", prefs.get("cheap_end", "05:00"), false);
        EditText cheapPrice = addLabeledField(octCard, "Günstiger Preis", "Cent pro kWh", prefs.get("cheap_price", "19"), false);
        EditText normalPrice = addLabeledField(octCard, "Normalpreis", "Cent pro kWh außerhalb des günstigen Fensters", prefs.get("normal_price", "29"), false);
        content.addView(octCard);

        LinearLayout autoCard = settingsCard("Raspberry & Automatik", "Energiecontroller und Sicherheitsgrenzen");
        EditText controllerUrl = addField(autoCard,
                "Controller URL · z. B. http://192.168.1.50:8787",
                prefs.get("controller_url", ""), false);
        EditText controllerToken = addField(autoCard, "Controller Token",
                prefs.getSecret("controller_token"), true);
        EditText battery = addField(autoCard, "Batterie kWh", prefs.get("battery_kwh", "70"), false);
        EditText softA = addField(autoCard, "Ladestrom normal A", prefs.get("charge_soft_a", "60"), false);
        EditText hardA = addField(autoCard, "Absolute Grenze A", prefs.get("charge_hard_a", "70"), false);
        EditText reserveSoc = addField(autoCard, "Reserve-SOC %", prefs.get("reserve_soc", "20"), false);
        EditText vacationReturn = addField(autoCard,
                "Urlaubs-Rückkehr · YYYY-MM-DD HH:mm",
                prefs.get("vacation_return", ""), false);

        CheckBox heatpump = new CheckBox(this);
        heatpump.setText("Panasonic Aquarea / FBH berücksichtigen");
        heatpump.setTextColor(UiKit.INK);
        heatpump.setChecked(prefs.getBool("heatpump_enabled", true));
        heatpump.setPadding(0, dp(5), 0, dp(4));
        autoCard.addView(heatpump);

        CheckBox ev = new CheckBox(this);
        ev.setText("EV-Lader berücksichtigen");
        ev.setTextColor(UiKit.INK);
        ev.setChecked(prefs.getBool("ev_enabled", false));
        ev.setPadding(0, 0, 0, dp(4));
        autoCard.addView(ev);
        content.addView(autoCard);

        LinearLayout wattCard = settingsCard("WattWächter Plus",
                "Live-Netzbezug über externen MQTT-Broker mit TLS · kein Raspberry erforderlich");
        CheckBox wattEnabled = new CheckBox(this);
        wattEnabled.setText("WattWächter MQTT aktivieren");
        wattEnabled.setTextColor(UiKit.INK);
        wattEnabled.setChecked(prefs.getBool("watt_mqtt_enabled", false));
        wattCard.addView(wattEnabled);
        EditText wattHost = addLabeledField(wattCard, "Broker-Hostname",
                "Nur Hostname, ohne ssl:// oder Port (z. B. EMQX-Endpunkt)",
                prefs.get("watt_mqtt_host", ""), false);
        EditText wattPort = addLabeledField(wattCard, "TLS-Port",
                "Standard 8883",
                prefs.get("watt_mqtt_port", "8883"), false);
        EditText wattTopic = addLabeledField(wattCard, "MQTT Topic-Prefix",
                "Exakt den Prefix aus dem WattWächter übernehmen, ohne /#",
                prefs.get("watt_mqtt_topic", ""), false);
        EditText wattUser = addLabeledField(wattCard, "MQTT-Benutzername",
                "MQTT-Zugang deines Brokers",
                prefs.get("watt_mqtt_user", ""), false);
        EditText wattPassword = addLabeledField(wattCard, "MQTT-Passwort",
                "Im Android Keystore verschlüsselt gespeichert",
                prefs.getSecret("watt_mqtt_password"), true);
        EditText wattLocalHost = addLabeledField(wattCard, "WattWächter im Heimnetz · IP oder .local",
                "Für die gespeicherte Tageshistorie; bei Problemen die lokale IP aus deiner Plume-App eintragen",
                prefs.get("watt_local_host", "wattwaechter-acebe6036778.local"), false);
        EditText wattLocalToken = addLabeledField(wattCard, "Lokaler READ-Token (optional)",
                "Nur wenn API-Schutz am WattWächter aktiviert ist. Kein Cloud- oder MQTT-Passwort.",
                prefs.getSecret("watt_local_token"), true);
        wattCard.addView(UiKit.caption(this,
                "Die Kurve enthält nur wirklich empfangene Werte. Fehlende Zeiten sind keine 0-kWh-Zeiten. Octopus bleibt die Quelle für die historischen Tarifstatistiken."));
        content.addView(wattCard);

        Button save = UiKit.primaryButton(this, "Einstellungen speichern");
        save.setOnClickListener(v -> {
            prefs.put("pv_system", pvSystem.getText().toString());
            prefs.putSecret("pv_key", pvKey.getText().toString());

            String streetValue = forecastStreet.getText().toString().trim();
            String postcodeValue = forecastPostcode.getText().toString().trim();
            String cityValue = forecastCity.getText().toString().trim();
            boolean addressChanged =
                    !streetValue.equals(prefs.get("forecast_street", "").trim())
                    || !postcodeValue.equals(prefs.get("forecast_postcode", "").trim())
                    || !cityValue.equals(prefs.get("forecast_city", "").trim());

            if (addressChanged) {
                Toast.makeText(this,
                        "Die Adresse wurde geändert. Bitte zuerst „Adresse finden“ drücken.",
                        Toast.LENGTH_LONG).show();
                return;
            }

            prefs.put("forecast_street", streetValue);
            prefs.put("forecast_postcode", postcodeValue);
            prefs.put("forecast_city", cityValue);
            prefs.put("kwp", kwp.getText().toString());
            prefs.put("tilt", tilt.getText().toString());
            prefs.put("azimuth", Integer.toString(orientationAzimuth(orientation.getSelectedItemPosition())));
            if (prefs.get("pr", "").isEmpty()) prefs.put("pr", "0.85");

            prefs.put("oct_account", octAccount.getText().toString());
            prefs.put("cheap_start", cheapStart.getText().toString());
            prefs.put("cheap_end", cheapEnd.getText().toString());
            prefs.put("cheap_price", cheapPrice.getText().toString());
            prefs.put("normal_price", normalPrice.getText().toString());

            prefs.putBool("watt_mqtt_enabled", wattEnabled.isChecked());
            prefs.put("watt_mqtt_host", wattHost.getText().toString());
            prefs.put("watt_mqtt_port", wattPort.getText().toString());
            prefs.put("watt_mqtt_topic", wattTopic.getText().toString());
            prefs.put("watt_mqtt_user", wattUser.getText().toString());
            prefs.putSecret("watt_mqtt_password", wattPassword.getText().toString());
            prefs.put("watt_local_host", wattLocalHost.getText().toString());
            prefs.putSecret("watt_local_token", wattLocalToken.getText().toString());
            lastLocalSyncAttemptMs = 0L;
            maybeSyncWattHistory();

            prefs.put("controller_url", controllerUrl.getText().toString());
            prefs.putSecret("controller_token", controllerToken.getText().toString());
            prefs.put("battery_kwh", battery.getText().toString());
            prefs.put("charge_soft_a", softA.getText().toString());
            prefs.put("charge_hard_a", hardA.getText().toString());
            prefs.put("reserve_soc", reserveSoc.getText().toString());
            prefs.put("vacation_return", vacationReturn.getText().toString());
            prefs.putBool("heatpump_enabled", heatpump.isChecked());
            prefs.putBool("ev_enabled", ev.isChecked());

            Toast.makeText(this, "Gespeichert", Toast.LENGTH_SHORT).show();
            startWattMeter();
            pv = null;
            forecast = Collections.emptyList();
            octopus = null;
            refreshPvForecast(false);
            if (!prefs.get("oct_account", "").isEmpty()
                    && (!prefs.getSecret("oct_api_key").isEmpty() || !prefs.getSecret("oct_refresh").isEmpty())) {
                refreshOctopus();
            }
            showTab("Home");
        });
        content.addView(save, buttonLp());

        TextView secure = UiKit.caption(this,
                "API-Schlüssel, MQTT-Passwort, Refresh Tokens und Controller Token werden verschlüsselt im Android Keystore gespeichert und nie in GitHub geschrieben.");
        secure.setPadding(dp(4), dp(4), dp(4), dp(12));
        content.addView(secure);
    }

    private LinearLayout settingsCard(String title, String subtitle) {
        LinearLayout box = UiKit.card(this);
        TextView h = UiKit.text(this, title, 18, UiKit.INK);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(h);
        TextView sub = UiKit.caption(this, subtitle);
        sub.setPadding(0, dp(3), 0, dp(11));
        box.addView(sub);
        return box;
    }

    private EditText addLabeledField(LinearLayout parent, String label, String help,
                                         String value, boolean secret) {
        TextView l = UiKit.text(this, label, 13, UiKit.INK);
        l.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        parent.addView(l);

        if (help != null && !help.isEmpty()) {
            TextView h = UiKit.caption(this, help);
            h.setPadding(0, dp(2), 0, dp(5));
            parent.addView(h);
        }

        EditText e = UiKit.input(this, "", value, secret);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.setMargins(0, 0, 0, dp(12));
        parent.addView(e, lp);
        return e;
    }

    private EditText addField(LinearLayout parent, String hint, String value, boolean secret) {
        EditText e = UiKit.input(this, hint, value, secret);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.setMargins(0, 0, 0, dp(9));
        parent.addView(e, lp);
        return e;
    }

    private void pageHeader(String title, String subtitle) {
        content.addView(UiKit.pageTitle(this, title));
        content.addView(UiKit.pageSubtitle(this, subtitle));
    }

    private void emptyState(String title, String text, String buttonText, View.OnClickListener listener) {
        LinearLayout card = UiKit.card(this);
        TextView h = UiKit.text(this, title, 20, UiKit.INK);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(h);
        TextView t = UiKit.caption(this, text);
        t.setPadding(0, dp(6), 0, dp(12));
        card.addView(t);
        Button b = UiKit.primaryButton(this, buttonText);
        b.setOnClickListener(listener);
        card.addView(b, new LinearLayout.LayoutParams(-1, dp(48)));
        content.addView(card);
    }

    private void showOctopusConnectionDialog(String accountNumber) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView intro = UiKit.caption(this,
                "Konto " + accountNumber
                        + "\n\nAm einfachsten: einmal mit deiner Octopus-E-Mail-Adresse und deinem Passwort anmelden. "
                        + "Das Passwort wird nur für diesen Verbindungstest verwendet und nicht gespeichert. "
                        + "Nach erfolgreicher Anmeldung speichert PV Compact ausschließlich den Refresh Token verschlüsselt.");
        intro.setPadding(0, 0, 0, dp(12));
        box.addView(intro);

        EditText email = UiKit.input(this, "Octopus E-Mail-Adresse", "", false);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, dp(52));
        ep.setMargins(0, 0, 0, dp(9));
        box.addView(email, ep);

        EditText password = UiKit.input(this, "Octopus Passwort", "", true);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(52));
        pp.setMargins(0, 0, 0, dp(12));
        box.addView(password, pp);

        TextView advanced = UiKit.caption(this,
                "Alternativ, falls vorhanden: API-Key oder bereits vorhandenen Refresh Token verwenden.");
        advanced.setPadding(0, 0, 0, dp(8));
        box.addView(advanced);

        EditText apiKey = UiKit.input(this, "Kraken API-Key (optional)", prefs.getSecret("oct_api_key"), true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, dp(52));
        ap.setMargins(0, 0, 0, dp(9));
        box.addView(apiKey, ap);

        EditText refresh = UiKit.input(this, "Refresh Token (optional)", prefs.getSecret("oct_refresh"), true);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(52));
        rp.setMargins(0, 0, 0, dp(10));
        box.addView(refresh, rp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Octopus verbinden")
                .setView(box)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Verbinden & testen", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String emailValue = email.getText().toString().trim();
                    String passwordValue = password.getText().toString();
                    String keyValue = apiKey.getText().toString().trim();
                    String refreshValue = refresh.getText().toString().trim();

                    boolean loginProvided = !emailValue.isEmpty() && !passwordValue.isEmpty();
                    boolean tokenProvided = !keyValue.isEmpty() || !refreshValue.isEmpty();
                    if (!loginProvided && !tokenProvided) {
                        Toast.makeText(this,
                                "Bitte E-Mail + Passwort oder API-Key/Refresh Token eingeben.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    prefs.put("oct_account", accountNumber);
                    prefs.putSecret("oct_api_key", keyValue);
                    prefs.putSecret("oct_refresh", refreshValue);
                    octopus = null;
                    octopusError = "";
                    dialog.dismiss();
                    Toast.makeText(this, "Octopus wird verbunden …", Toast.LENGTH_SHORT).show();

                    if (loginProvided && !tokenProvided) {
                        refreshOctopus(emailValue, passwordValue);
                    } else {
                        refreshOctopus();
                    }
                    if ("Einstellungen".equals(currentTab)) showTab("Einstellungen");
                }));
        dialog.show();
    }

    private void geocodeForecastAddress(EditText street, EditText postcode, EditText city,
                                        LinearLayout statusBox, TextView state,
                                        TextView resolved, TextView coords, Button locateButton) {
        String streetValue = street.getText().toString().trim();
        String postcodeValue = postcode.getText().toString().trim();
        String cityValue = city.getText().toString().trim();

        if (streetValue.isEmpty() || cityValue.isEmpty()) {
            Toast.makeText(this, "Bitte mindestens Straße + Hausnummer und Ort eingeben.", Toast.LENGTH_LONG).show();
            return;
        }

        state.setText("⌕ Adresse wird gesucht …");
        state.setTextColor(UiKit.AMBER);
        resolved.setText(streetValue + (postcodeValue.isEmpty() ? "" : ", " + postcodeValue) + ", " + cityValue);
        coords.setText("Koordinaten werden ermittelt …");
        statusBox.setBackground(UiKit.outlined(this, UiKit.AMBER_SOFT,
                Color.rgb(245, 222, 158), 15));
        locateButton.setEnabled(false);

        io.execute(() -> {
            try {
                GeocodingClient.Result result = new GeocodingClient().geocode(
                        streetValue, postcodeValue, cityValue, "Deutschland");
                String latValue = String.format(Locale.US, "%.6f", result.lat);
                String lonValue = String.format(Locale.US, "%.6f", result.lon);

                prefs.put("forecast_street", streetValue);
                prefs.put("forecast_postcode", postcodeValue);
                prefs.put("forecast_city", cityValue);
                prefs.put("forecast_resolved", result.displayName);
                prefs.put("lat", latValue);
                prefs.put("lon", lonValue);

                runOnUiThread(() -> {
                    state.setText("✓ Standort gefunden");
                    state.setTextColor(UiKit.GREEN_DARK);
                    resolved.setText(result.displayName);
                    coords.setText("Breitengrad " + latValue + " · Längengrad " + lonValue);
                    statusBox.setBackground(UiKit.outlined(this, UiKit.MINT,
                            Color.rgb(177, 224, 208), 15));
                    locateButton.setText("Adresse neu prüfen");
                    locateButton.setEnabled(true);
                    Toast.makeText(this, "Standort übernommen.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                String err = cleanError(e);
                runOnUiThread(() -> {
                    state.setText("! Adresse nicht gefunden");
                    state.setTextColor(UiKit.RED);
                    resolved.setText("Bitte Schreibweise, Hausnummer, PLZ und Ort prüfen.");
                    coords.setText("Es wurden keine Koordinaten übernommen.");
                    statusBox.setBackground(UiKit.outlined(this, UiKit.RED_SOFT,
                            Color.rgb(244, 190, 186), 15));
                    locateButton.setEnabled(true);
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void refreshAll() {
        Toast.makeText(this, "Daten werden aktualisiert …", Toast.LENGTH_SHORT).show();
        refreshPvForecast(true);
        if (!prefs.get("oct_account", "").isEmpty()
                && (!prefs.getSecret("oct_api_key").isEmpty() || !prefs.getSecret("oct_refresh").isEmpty())) {
            refreshOctopus();
        }
    }

    private void refreshPvForecast(boolean forceLive) {
        lastPvRefreshRequestMs = System.currentTimeMillis();
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
                    newForecast = new ForecastClient().load(lat, lon, tilt, az, kwp, 0.85);
                } catch (Exception e) {
                    newForecastError = cleanError(e);
                }
            }

            if (newForecast != null && !newForecast.isEmpty()) {
                ForecastCalibration calibration = new ForecastCalibration(this);
                calibration.recordForecasts(newForecast, manualForecastFactor());
                if (newPv != null && newPv.week != null) {
                    calibration.updateActuals(newPv.week);
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
                updateHeaderStatus();
                showTab(currentTab);
            });
        });
    }

    private void refreshOctopus() {
        refreshOctopus("", "");
    }

    private void refreshOctopus(String email, String password) {
        lastOctopusRefreshRequestMs = System.currentTimeMillis();
        io.execute(() -> {
            try {
                OctopusClient client = new OctopusClient(
                        this,
                        prefs.get("oct_account", ""),
                        prefs.getSecret("oct_api_key"),
                        prefs.getSecret("oct_refresh"),
                        email,
                        password,
                        prefs.get("cheap_start", "00:00"),
                        prefs.get("cheap_end", "05:00"),
                        parseDouble(prefs.get("cheap_price", "19"), 19),
                        parseDouble(prefs.get("normal_price", "29"), 29));
                OctopusClient.Summary s = client.loadRecentConsumption();
                if (s.refreshedToken != null && !s.refreshedToken.isEmpty()) {
                    prefs.putSecret("oct_refresh", s.refreshedToken);
                }
                if (s.accountNumber != null && !s.accountNumber.isEmpty()
                        && !s.accountNumber.equals(prefs.get("oct_account", ""))) {
                    prefs.put("oct_account", s.accountNumber);
                }
                runOnUiThread(() -> {
                    octopus = s;
                    octopusError = "";
                    updateHeaderStatus();
                    if ("Octopus".equals(currentTab) || "Statistik".equals(currentTab) || "Home".equals(currentTab) || "Einstellungen".equals(currentTab)) showTab(currentTab);
                });
            } catch (Exception e) {
                String err = cleanError(e);
                runOnUiThread(() -> {
                    octopusError = err;
                    if ("Octopus".equals(currentTab) || "Statistik".equals(currentTab) || "Home".equals(currentTab) || "Einstellungen".equals(currentTab)) showTab(currentTab);
                });
            }
        });
    }

    private void showEnergyStatistics() {
        content.addView(UiKit.sectionTitle(this, "Energie-Statistik"));
        TextView intro = UiKit.caption(this,
                "PV-Ertrag aus PVOutput · Netzbezug aus Octopus Smart Meter · "
                        + "Haupt-/Nebenzeit nach deinem Go-Zeitfenster. Kosten sind eine Schätzung mit den aktuell hinterlegten Tarifpreisen.");
        intro.setPadding(dp(4), 0, dp(4), dp(8));
        content.addView(intro);

        addPeriodStrip("Wochen", weeklyEnergyPeriods());
        addPeriodStrip("Monate", monthlyEnergyPeriods());
        addPeriodStrip("Jahre", yearlyEnergyPeriods());
    }

    private void addPeriodStrip(String title, List<EnergyPeriod> periods) {
        TextView heading = UiKit.text(this, title, 16, UiKit.INK);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(dp(2), dp(10), 0, dp(7));
        content.addView(heading);

        if (periods.isEmpty()) {
            LinearLayout empty = UiKit.card(this);
            empty.addView(UiKit.caption(this, "Für diesen Zeitraum liegen noch keine gemeinsamen Statistikdaten vor."));
            content.addView(empty);
            return;
        }

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = UiKit.row(this);
        row.setPadding(0, 0, dp(6), dp(4));

        for (EnergyPeriod p : periods) {
            LinearLayout card = energyPeriodCard(p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(268), -2);
            lp.setMargins(0, 0, dp(10), dp(8));
            row.addView(card, lp);
        }

        scroll.addView(row);
        content.addView(scroll);
    }

    private LinearLayout energyPeriodCard(EnergyPeriod p) {
        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.overline(this, p.label, UiKit.PURPLE));

        TextView pvText = UiKit.text(this,
                "PV-Ertrag  " + (p.hasPv
                        ? String.format(Locale.GERMANY, "%.1f kWh", p.pvKwh)
                        : "—"),
                15, UiKit.GREEN_DARK);
        pvText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pvText.setPadding(0, dp(9), 0, dp(5));
        card.addView(pvText);

        TextView gridText = UiKit.text(this,
                "Netzbezug  " + (p.hasOctopus
                        ? String.format(Locale.GERMANY, "%.1f kWh", p.gridKwh)
                        : "—"),
                15, UiKit.INK);
        gridText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(gridText);

        card.addView(UiKit.divider(this), dividerLp());

        String split = p.hasOctopus
                ? String.format(Locale.GERMANY,
                        "Hauptzeit %.1f kWh\nNebenzeit %.1f kWh",
                        p.normalKwh, p.cheapKwh)
                : "Hauptzeit —\nNebenzeit —";
        TextView splitText = UiKit.caption(this, split);
        splitText.setLineSpacing(0, 1.15f);
        card.addView(splitText);

        TextView cost = UiKit.text(this,
                p.hasOctopus
                        ? String.format(Locale.GERMANY, "Kosten  %.2f €", p.costEuro)
                        : "Kosten  —",
                15, UiKit.PURPLE);
        cost.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cost.setPadding(0, dp(9), 0, 0);
        card.addView(cost);
        return card;
    }

    private List<EnergyPeriod> weeklyEnergyPeriods() {
        List<EnergyPeriod> out = new ArrayList<>();
        LocalDate today = LocalDate.now(BERLIN);
        LocalDate thisMonday = today.minusDays(today.getDayOfWeek().getValue() - 1L);

        for (int i = 0; i < 8; i++) {
            LocalDate start = thisMonday.minusWeeks(i);
            LocalDate end = start.plusDays(6);
            int week = start.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            String label = "KW " + week + " · "
                    + start.format(DateTimeFormatter.ofPattern("dd.MM."))
                    + "–" + end.format(DateTimeFormatter.ofPattern("dd.MM."));
            out.add(energyForRange(start, end, label));
        }
        return out;
    }

    private List<EnergyPeriod> monthlyEnergyPeriods() {
        List<EnergyPeriod> out = new ArrayList<>();
        LocalDate month = LocalDate.now(BERLIN).withDayOfMonth(1);

        for (int i = 0; i < 12; i++) {
            LocalDate start = month.minusMonths(i);
            LocalDate end = start.plusMonths(1).minusDays(1);
            String label = start.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMANY));
            EnergyPeriod p = energyForRange(start, end, label);
            p.pvKwh = pvForMonth(start);
            p.hasPv = hasPvMonth(start);
            out.add(p);
        }
        return out;
    }

    private List<EnergyPeriod> yearlyEnergyPeriods() {
        List<EnergyPeriod> out = new ArrayList<>();
        int year = LocalDate.now(BERLIN).getYear();

        for (int y = year; y >= year - 1; y--) {
            LocalDate start = LocalDate.of(y, 1, 1);
            LocalDate end = LocalDate.of(y, 12, 31);
            EnergyPeriod p = energyForRange(start, end,
                    y == year ? y + " · bisher" : Integer.toString(y));
            p.pvKwh = pvForYear(y);
            p.hasPv = hasPvYear(y);
            if (p.hasPv || p.hasOctopus) out.add(p);
        }
        return out;
    }

    private EnergyPeriod energyForRange(LocalDate start, LocalDate end, String label) {
        EnergyPeriod p = new EnergyPeriod();
        p.label = label;
        if (start != null && start.equals(end)) p.date = start;

        if (pv != null) {
            for (PvOutputClient.Day d : pv.recentDays) {
                LocalDate date = parsePvDate(d.date);
                if (date == null || date.isBefore(start) || date.isAfter(end)) continue;
                p.pvKwh += d.generatedWh / 1000.0;
                p.hasPv = true;
            }
        }

        if (octopus != null) {
            boolean singleDay = start != null && start.equals(end);
            boolean foundIntervals = false;

            if (singleDay && octopus.dayValidation != null
                    && start.toString().equals(octopus.dayValidation.date)
                    && octopus.dayValidation.preferredSource != null
                    && !octopus.dayValidation.preferredSource.isEmpty()) {
                OctopusClient.DayValidation v = octopus.dayValidation;
                p.gridKwh = v.preferredKwh;
                p.cheapKwh = v.preferredCheapKwh;
                p.normalKwh = v.preferredNormalKwh;
                p.intervalCount = v.preferredCount;
                p.hasOctopus = true;
                foundIntervals = true;
            }

            // For a single day, prefer the actual recent hourly intervals.
            // They are fresher and must win over potentially stale/zero cached daily sums.
            if (singleDay && !foundIntervals) {
                for (OctopusClient.Interval in : octopus.intervals) {
                    LocalDate date = octopusIntervalDate(in.readAt);
                    if (date == null || !date.equals(start)) continue;
                    p.gridKwh += in.kwh;
                    if (in.cheap) p.cheapKwh += in.kwh;
                    else p.normalKwh += in.kwh;
                    p.hasOctopus = true;
                    p.intervalCount++;
                    foundIntervals = true;
                }
            }

            if (!foundIntervals) {
                boolean foundDaily = false;
                for (OctopusClient.DailyUsage d : octopus.dailyHistory) {
                    LocalDate date;
                    try {
                        date = LocalDate.parse(d.date);
                    } catch (Exception ignored) {
                        continue;
                    }
                    if (date.isBefore(start) || date.isAfter(end)) continue;

                    p.gridKwh += d.totalKwh;
                    p.cheapKwh += d.cheapKwh;
                    p.normalKwh += d.normalKwh;
                    p.hasOctopus = true;
                    foundDaily = true;
                }

                // If no daily aggregate exists, use recent hourly intervals as fallback.
                if (!foundDaily) {
                    for (OctopusClient.Interval in : octopus.intervals) {
                        LocalDate date = octopusIntervalDate(in.readAt);
                        if (date == null || date.isBefore(start) || date.isAfter(end)) continue;
                        p.gridKwh += in.kwh;
                        if (in.cheap) p.cheapKwh += in.kwh;
                        else p.normalKwh += in.kwh;
                        p.hasOctopus = true;
                        p.intervalCount++;
                    }
                }
            }
        }

        p.costEuro = historyCost(p.cheapKwh, p.normalKwh);
        return p;
    }

    private LocalDate octopusIntervalDate(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return java.time.Instant.parse(value).atZone(BERLIN).toLocalDate();
        } catch (Exception ignored) {}
        try {
            return java.time.OffsetDateTime.parse(value).atZoneSameInstant(BERLIN).toLocalDate();
        } catch (Exception ignored) {}
        return null;
    }

    private java.time.ZonedDateTime octopusIntervalDateTime(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return java.time.Instant.parse(value).atZone(BERLIN);
        } catch (Exception ignored) {}
        try {
            return java.time.OffsetDateTime.parse(value).atZoneSameInstant(BERLIN);
        } catch (Exception ignored) {}
        return null;
    }

    private String recentIntervalRangeText() {
        if (octopus == null || octopus.intervals.isEmpty()) return "keine aktuellen Intervalle";

        java.time.ZonedDateTime min = null;
        java.time.ZonedDateTime max = null;
        for (OctopusClient.Interval in : octopus.intervals) {
            java.time.ZonedDateTime z = octopusIntervalDateTime(in.readAt);
            if (z == null) continue;
            if (min == null || z.isBefore(min)) min = z;
            if (max == null || z.isAfter(max)) max = z;
        }

        if (min == null || max == null) {
            return octopus.recentIntervalCount + " Messintervalle · " + octopus.measurementSource;
        }

        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM. HH:mm");
        return min.format(f) + "–" + max.format(f)
                + " · " + octopus.recentIntervalCount + " Stundenwerte";
    }

    private List<EnergyPeriod> recentGridDayBreakdowns() {
        List<EnergyPeriod> out = new ArrayList<>();
        if (octopus == null || octopus.intervals.isEmpty()) return out;

        List<LocalDate> dates = new ArrayList<>();
        for (OctopusClient.Interval in : octopus.intervals) {
            LocalDate date = octopusIntervalDate(in.readAt);
            if (date != null && !dates.contains(date)) dates.add(date);
        }
        dates.sort(Collections.reverseOrder());

        for (LocalDate date : dates) {
            EnergyPeriod p = new EnergyPeriod();
            p.date = date;
            p.label = date.toString();

            for (OctopusClient.Interval in : octopus.intervals) {
                LocalDate intervalDate = octopusIntervalDate(in.readAt);
                if (intervalDate == null || !intervalDate.equals(date)) continue;
                p.gridKwh += in.kwh;
                if (in.cheap) p.cheapKwh += in.kwh;
                else p.normalKwh += in.kwh;
                p.intervalCount++;
                p.hasOctopus = true;
            }
            p.costEuro = historyCost(p.cheapKwh, p.normalKwh);
            out.add(p);
        }
        return out;
    }

    private boolean hasUsableGridConsumption(EnergyPeriod p) {
        return p != null
                && p.hasOctopus
                && p.gridKwh > 0.000001
                && (p.intervalCount > 0 || p.cheapKwh > 0.000001 || p.normalKwh > 0.000001);
    }

    private EnergyPeriod latestAvailableGridDay(LocalDate notAfter) {
        if (octopus == null || notAfter == null) return null;

        LocalDate latest = null;

        if (octopus.dayValidation != null
                && octopus.dayValidation.date != null
                && !octopus.dayValidation.date.isEmpty()) {
            try {
                LocalDate checked = LocalDate.parse(octopus.dayValidation.date);
                if (!checked.isAfter(notAfter)) latest = checked;
            } catch (Exception ignored) {}
        }

        // Prefer the latest date backed by actual hourly intervals.
        for (OctopusClient.Interval in : octopus.intervals) {
            LocalDate date = octopusIntervalDate(in.readAt);
            if (date == null || date.isAfter(notAfter)) continue;
            if (latest == null || date.isAfter(latest)) latest = date;
        }

        // Only fall back to the cached daily history when no recent interval exists.
        if (latest == null) {
            for (OctopusClient.DailyUsage d : octopus.dailyHistory) {
                try {
                    LocalDate date = LocalDate.parse(d.date);
                    if (date.isAfter(notAfter)) continue;
                    if (latest == null || date.isAfter(latest)) latest = date;
                } catch (Exception ignored) {}
            }
        }

        // Walk backwards until we find a day with real consumption.
        // A newer zero-value placeholder must not win over the last valid day.
        LocalDate cursor = latest;
        for (int i = 0; cursor != null && i < 14; i++, cursor = cursor.minusDays(1)) {
            EnergyPeriod p = energyForRange(cursor, cursor, cursor.toString());
            p.date = cursor;
            if (hasUsableGridConsumption(p)) return p;
        }
        return null;
    }

    private String gridDayDisplayDate(EnergyPeriod p) {
        if (p == null || p.date == null) return "—";
        LocalDate yesterday = LocalDate.now(BERLIN).minusDays(1);
        if (p.date.equals(yesterday)) return "gestern";
        return p.date.format(DateTimeFormatter.ofPattern("dd.MM."));
    }

    private String gridDayDetail(EnergyPeriod p, String kind) {
        String day = gridDayDisplayDate(p);
        String count = p != null && p.intervalCount > 0
                ? " · " + p.intervalCount + " Messwerte"
                : "";
        if ("Nebenzeit".equals(kind)) {
            return day + count + " · " + currentCheapStart() + "–" + currentCheapEnd();
        }
        return day + count + " · außerhalb " + currentCheapStart() + "–" + currentCheapEnd();
    }

    private LocalDate parsePvDate(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            if (value.matches("\\d{8}")) {
                return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private double pvForMonth(LocalDate monthStart) {
        if (pv == null) return 0.0;
        String key = monthStart.format(DateTimeFormatter.ofPattern("yyyyMM"));
        for (PvOutputClient.Month m : pv.months) {
            if (key.equals(m.month)) return m.generatedWh / 1000.0;
        }
        return 0.0;
    }

    private boolean hasPvMonth(LocalDate monthStart) {
        if (pv == null) return false;
        String key = monthStart.format(DateTimeFormatter.ofPattern("yyyyMM"));
        for (PvOutputClient.Month m : pv.months) {
            if (key.equals(m.month)) return true;
        }
        return false;
    }

    private double pvForYear(int year) {
        if (pv == null) return 0.0;
        for (PvOutputClient.Year y : pv.years) {
            if (y.year == year) return y.generatedWh / 1000.0;
        }
        return 0.0;
    }

    private boolean hasPvYear(int year) {
        if (pv == null) return false;
        for (PvOutputClient.Year y : pv.years) {
            if (y.year == year) return true;
        }
        return false;
    }

    private double historyCost(double cheapKwh, double normalKwh) {
        double cheapRate = octopus != null && Double.isFinite(octopus.cheapCents)
                ? octopus.cheapCents
                : parseDouble(prefs.get("cheap_price", "19"), 19);
        double normalRate = octopus != null && Double.isFinite(octopus.normalCents)
                ? octopus.normalCents
                : parseDouble(prefs.get("normal_price", "29"), 29);
        return (cheapKwh * cheapRate + normalKwh * normalRate) / 100.0;
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
                String result = "HTTP " + r.code + "\n"
                        + (r.body.length() > 800 ? r.body.substring(0,800) : r.body);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Controller antwortet: HTTP " + r.code, Toast.LENGTH_LONG).show();
                    LinearLayout c = UiKit.card(this);
                    c.addView(UiKit.overline(this, "CONTROLLER-ANTWORT", UiKit.GREEN));
                    TextView t = UiKit.caption(this, result);
                    t.setPadding(0, dp(6), 0, 0);
                    c.addView(t);
                    content.addView(c, Math.min(1, content.getChildCount()));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Controller: " + cleanError(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void updateWidgetCache() {
        if (pv != null) {
            prefs.put("widget_today_kwh", Double.toString(pv.live.energyWh / 1000.0));
            prefs.put("widget_power_w", Double.toString(pv.live.powerW));
        }
        if (forecast.size() > 1) {
            prefs.put("widget_tomorrow_kwh", Double.toString(adjustedForecastKwh(forecast.get(1))));
        }
        prefs.put("widget_stamp",
                "Stand " + LocalDateTime.now(BERLIN).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")));
        PvWidgetProvider.refreshAll(this);
    }

    private void updateHeaderStatus() {
        if (headerStatus == null) return;
        String time = LocalTime.now(BERLIN).format(DateTimeFormatter.ofPattern("HH:mm"));
        if (pv != null) headerStatus.setText("Daten aktualisiert · " + time);
        else headerStatus.setText("Energie auf einen Blick");
    }

    private double manualForecastFactor() {
        double factor = parseDouble(prefs.get("forecast_manual_factor", "1.00"), 1.0);
        return Math.max(0.60, Math.min(1.20, factor));
    }

    private ForecastCalibration.Stats forecastCalibrationStats() {
        return new ForecastCalibration(this).stats();
    }

    private double autoForecastFactor() {
        if (!prefs.getBool("forecast_auto_learning", true)) return 1.0;
        return forecastCalibrationStats().autoFactor;
    }

    private double adjustedForecastKwh(ForecastClient.Day day) {
        if (day == null) return 0.0;
        return day.energyKwh * manualForecastFactor() * autoForecastFactor();
    }

    private double currentTariffPrice() {
        boolean cheap = isCheapNow();
        if (octopus != null && octopus.tariffFromApi) {
            double apiRate = cheap ? octopus.cheapCents : octopus.normalCents;
            if (Double.isFinite(apiRate)) return apiRate;
        }
        return cheap
                ? parseDouble(prefs.get("cheap_price", "19"), 19)
                : parseDouble(prefs.get("normal_price", "29"), 29);
    }

    private String currentCheapStart() {
        if (octopus != null && octopus.cheapStart != null && !octopus.cheapStart.isEmpty()) {
            return octopus.cheapStart;
        }
        return prefs.get("cheap_start", "00:00");
    }

    private String currentCheapEnd() {
        if (octopus != null && octopus.cheapEnd != null && !octopus.cheapEnd.isEmpty()) {
            return octopus.cheapEnd;
        }
        return prefs.get("cheap_end", "05:00");
    }

    private boolean isCheapNow() {
        LocalTime now = LocalTime.now(BERLIN);
        LocalTime start = parseTime(currentCheapStart(), LocalTime.MIDNIGHT);
        LocalTime end = parseTime(currentCheapEnd(), LocalTime.of(5, 0));
        if (start.equals(end)) return false;
        if (start.isBefore(end)) return !now.isBefore(start) && now.isBefore(end);
        return !now.isBefore(start) || now.isBefore(end);
    }

    private LocalTime parseTime(String value, LocalTime def) {
        try { return LocalTime.parse(value); } catch (Exception e) { return def; }
    }

    private String greeting() {
        int h = LocalTime.now(BERLIN).getHour();
        if (h < 11) return "Guten Morgen";
        if (h < 18) return "Guten Tag";
        return "Guten Abend";
    }

    private String weatherIcon(Integer code) {
        if (code == null) return "◌";
        if (code == 0) return "☀";
        if (code <= 3) return "⛅";
        if (code == 45 || code == 48) return "≋";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "☂";
        if (code >= 71 && code <= 77) return "❄";
        if (code >= 95) return "⚡";
        return "☁";
    }

    private ForecastClient.Day todayForecastDay() {
        String today = LocalDate.now(BERLIN).toString();
        for (ForecastClient.Day d : forecast) {
            if (d != null && today.equals(d.date)) return d;
        }
        return forecast.isEmpty() ? null : forecast.get(0);
    }

    private String friendlyDate(String iso) {
        try {
            LocalDate d = LocalDate.parse(iso);
            String day = d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.GERMANY);
            return day + ", " + d.format(DateTimeFormatter.ofPattern("dd.MM."));
        } catch (Exception e) {
            return iso;
        }
    }

    private String shortDate(String date) {
        try {
            LocalDate d;
            if (date.matches("\\d{8}")) d = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);
            else d = LocalDate.parse(date);
            return d.format(DateTimeFormatter.ofPattern("dd.MM."));
        } catch (Exception e) {
            return date;
        }
    }

    private LinearLayout.LayoutParams dividerLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(0, dp(7), 0, dp(7));
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.setMargins(0, 0, 0, dp(10));
        return lp;
    }

    private int orientationIndex(int azimuth) {
        if (azimuth >= -23 && azimuth <= 23) return 0;      // Süd
        if (azimuth < -23 && azimuth >= -68) return 1;     // Südost
        if (azimuth > 23 && azimuth <= 68) return 2;       // Südwest
        if (azimuth < -68 && azimuth >= -113) return 3;    // Ost
        if (azimuth > 68 && azimuth <= 113) return 4;      // West
        if (azimuth < -113 && azimuth >= -158) return 5;   // Nordost
        if (azimuth > 113 && azimuth <= 158) return 6;     // Nordwest
        return 7;                                           // Nord
    }

    private int orientationAzimuth(int position) {
        switch (position) {
            case 1: return -45;   // Südost
            case 2: return 45;    // Südwest
            case 3: return -90;   // Ost
            case 4: return 90;    // West
            case 5: return -135;  // Nordost
            case 6: return 135;   // Nordwest
            case 7: return 180;   // Nord
            default: return 0;    // Süd
        }
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
        return UiKit.dp(this, v);
    }

    private static final class EnergyPeriod {
        String label = "";
        LocalDate date;
        double pvKwh;
        double gridKwh;
        double cheapKwh;
        double normalKwh;
        double costEuro;
        int intervalCount;
        boolean hasPv;
        boolean hasOctopus;
    }

    private static final class NavItem {
        final String tab;
        final LinearLayout container;
        final TextView icon;
        final TextView label;

        NavItem(String tab, LinearLayout container, TextView icon, TextView label) {
            this.tab = tab;
            this.container = container;
            this.icon = icon;
            this.label = label;
        }
    }
}
