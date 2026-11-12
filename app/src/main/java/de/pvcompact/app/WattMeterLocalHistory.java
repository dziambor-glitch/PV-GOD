package de.pvcompact.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only local WattWächter REST history, independent of MQTT foreground uptime.
 * GET /api/v1/history/highRes?date=YYYY-MM-DD
 *
 * Daily import comes from the WattWächter's own day's summary, not from
 * integrating instantaneous live wattage. History is available in home LAN.
 */
public final class WattMeterLocalHistory {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String STORE = "watt_history_15min";

    public static final class Day {
        public final String date;
        public final double importKwh;
        public final double exportKwh;
        public final double cheapKwh;
        public final double normalKwh;
        public final Double currentMeterKwh;
        public final boolean tariffSplitAvailable;
        public final long fetchedAtMs;
        public final long lastIntervalMs;
        public final List<WattMeterHistoryStore.Point> curve;

        Day(String date, double imported, double exported, double cheap, double normal,
            Double meterKwh, boolean hasTariffSplit,
            long fetchedAt, long lastInterval, List<WattMeterHistoryStore.Point> points) {
            this.date = date;
            this.importKwh = imported;
            this.exportKwh = exported;
            this.cheapKwh = cheap;
            this.normalKwh = normal;
            this.currentMeterKwh = meterKwh;
            this.tariffSplitAvailable = hasTariffSplit;
            this.fetchedAtMs = fetchedAt;
            this.lastIntervalMs = lastInterval;
            this.curve = points;
        }

        public double estimatedEuro(double cheapCent, double normalCent) {
            return (cheapKwh * cheapCent + normalKwh * normalCent) / 100.0;
        }
    }

    private WattMeterLocalHistory() {}

    public static Day sync(Context context, String host, String token, String date,
                           String cheapStart, String cheapEnd) throws Exception {
        String h = host == null ? "" : host.trim();
        // Never accept arbitrary URLs, query strings, userinfo or internet domains;
        // this endpoint is strictly limited to an explicitly configured LAN host.
        if (!h.matches("(?i)^(?:[a-z0-9-]+\\.local|(?:[0-9]{1,3}\\.){3}[0-9]{1,3})$")) {
            throw new IllegalArgumentException("Bitte lokale WattWächter-IP oder *.local-Hostname eingeben");
        }
        LocalDate.parse(date);
        Map<String, String> headers = new HashMap<>();
        if (token != null && !token.trim().isEmpty())
            headers.put("Authorization", "Bearer " + token.trim());
        Net.Response response = Net.get("http://" + h + "/api/v1/history/highRes?date=" + date,
                headers);
        if (response.code == 401 || response.code == 403)
            throw new IllegalStateException("WattWächter verlangt einen READ-API-Token");
        if (response.code != 200) {
            throw new IllegalStateException("Lokale Historie HTTP " + response.code);
        }
        Day parsed = parse(response.body, date, System.currentTimeMillis(), cheapStart, cheapEnd);
        if (parsed == null) throw new IllegalStateException("Keine gültigen 15-Min-Daten geliefert");
        // Retain the raw manufacturer data; no incremental point accumulation needed.
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .putString("date_" + date, response.body)
                .putLong("fetched_" + date, parsed.fetchedAtMs)
                .apply();
        return parsed;
    }

    public static Day cached(Context context, String date, String cheapStart, String cheapEnd) {
        SharedPreferences pref = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        String body = pref.getString("date_" + date, "");
        if (body == null || body.isEmpty()) return null;
        try {
            return parse(body, date, pref.getLong("fetched_" + date, 0),
                    cheapStart, cheapEnd);
        } catch (Exception ignored) { return null; }
    }

    static Day parse(String json, String desiredDate, long fetchedAt,
                     String cheapStart, String cheapEnd) throws Exception {
        JSONObject root = new JSONObject(json);
        String start = root.optString("start", desiredDate);
        if (!start.startsWith(desiredDate)) return null;
        JSONArray items = root.optJSONArray("items");
        if (items == null || items.length() < 1) return null;

        double summaryImport = root.has("import_total_kWh")
                ? root.optDouble("import_total_kWh", Double.NaN) : Double.NaN;
        double summaryExport = root.has("export_total_kWh")
                ? root.optDouble("export_total_kWh", Double.NaN) : 0;
        if (!Double.isFinite(summaryImport) || summaryImport < 0 || summaryImport > 50000)
            return null;
        if (!Double.isFinite(summaryExport) || summaryExport < 0) summaryExport = 0;

        List<WattMeterHistoryStore.Point> curve = new ArrayList<>();
        double nightWeight = 0, dayWeight = 0;
        Double lastRegister = null;
        long lastMs = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            // The history date is the device's local wall clock. Prefer it
            // over a Unix timestamp to avoid a one-hour timezone shift.
            long timeMs = 0L;
            try {
                LocalDateTime local = LocalDateTime.parse(item.getString("date"),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
                timeMs = local.atZone(BERLIN).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
            if (timeMs <= 0) {
                long ts = item.optLong("timestamp", 0);
                if (ts > 946684800) timeMs = ts * 1000L;
            }
            if (timeMs <= 0) continue;
            if (!desiredDate.equals(Instant.ofEpochMilli(timeMs).atZone(BERLIN)
                    .toLocalDate().toString())) continue;
            if (lastMs > 0 && timeMs < lastMs) continue;
            lastMs = Math.max(lastMs, timeMs);
            double meterReg = item.optDouble("import_total_kWh", Double.NaN);
            if (Double.isFinite(meterReg) && meterReg >= 0)
                lastRegister = meterReg;

            double averageImportKw = item.optDouble("import_kW", Double.NaN);
            if (Double.isFinite(averageImportKw) && averageImportKw >= 0) {
                double quarterKwh = averageImportKw * 0.25;
                java.time.LocalTime localTime = Instant.ofEpochMilli(timeMs).atZone(BERLIN)
                        .toLocalTime();
                if (isCheap(localTime, cheapStart, cheapEnd)) nightWeight += quarterKwh;
                else dayWeight += quarterKwh;
                curve.add(new WattMeterHistoryStore.Point(timeMs, averageImportKw * 1000));
            } else {
                double instantaneous = item.optDouble("power_W", Double.NaN);
                if (Double.isFinite(instantaneous))
                    curve.add(new WattMeterHistoryStore.Point(timeMs, Math.max(0, instantaneous)));
            }
        }
        curve.sort((a,b) -> Long.compare(a.timeMs,b.timeMs));
        if (curve.isEmpty()) return null;

        // The manufacturer's footer gives the recorded daily kWh.
        // Its 15-min import_kW columns provide the *approximate* tariff
        // distribution, normalized to the authoritative daily total.
        double sumWeights = nightWeight + dayWeight;
        double cheap = 0, normal = 0;
        if (sumWeights > 0) {
            cheap = summaryImport * nightWeight / sumWeights;
            normal = summaryImport - cheap;
        }
        return new Day(desiredDate, summaryImport, summaryExport,
                cheap, normal, lastRegister, sumWeights > 0,
                fetchedAt, lastMs, curve);
    }

    private static boolean isCheap(java.time.LocalTime time, String from, String to) {
        java.time.LocalTime start, end;
        try {
            start = java.time.LocalTime.parse(from);
            end = java.time.LocalTime.parse(to);
        } catch (Exception ex) {
            start = java.time.LocalTime.MIDNIGHT; end = java.time.LocalTime.of(5,0);
        }
        if (start.equals(end)) return false;
        if (start.isBefore(end)) return !time.isBefore(start) && time.isBefore(end);
        return !time.isBefore(start) || time.isBefore(end);
    }
}
