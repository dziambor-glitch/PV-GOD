package de.pvcompact.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PvOutputClient {
    private static final String BASE = "https://pvoutput.org/service/r2/";
    private final String apiKey;
    private final String systemId;
    private final SharedPreferences cache;

    public static final class Live {
        public String date = "";
        public String time = "";
        public double energyWh;
        public double powerW;
        public Double consumptionWh;
        public Double consumptionW;
    }

    public static final class Point {
        public String time = "";
        public double energyWh;
        public double powerW;
        public Double consumptionW;
    }

    public static final class Day {
        public String date = "";
        public double generatedWh;
        public Double consumedWh;
        public Double peakPowerW;
    }

    public static final class Year {
        public int year;
        public int days;
        public double generatedWh;
        public Double consumedWh;
        public Double importedWh;
        public Double exportedWh;
    }

    public static final class Dashboard {
        public Live live;
        public List<Point> history = Collections.emptyList();
        public List<Day> week = Collections.emptyList();
        public List<Year> years = Collections.emptyList();
        public Integer rateRemaining;
    }

    public PvOutputClient(Context context, String apiKey, String systemId) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.systemId = systemId == null ? "" : systemId.trim();
        this.cache = context.getSharedPreferences("pvoutput_clean_cache", Context.MODE_PRIVATE);
    }

    public Dashboard loadDashboard(boolean forceLive) throws Exception {
        ensureConfigured();

        Raw liveRaw = cached("getstatus.jsp", forceLive ? 0L : 2 * 60_000L);
        Live live = parseLive(liveRaw.body);

        Raw historyRaw = safeCached("getstatus.jsp?h=1&limit=288&asc=1&d=" + live.date, 15 * 60_000L);

        LocalDate today = LocalDate.parse(live.date, DateTimeFormatter.BASIC_ISO_DATE);
        String from = today.minusDays(6).format(DateTimeFormatter.BASIC_ISO_DATE);
        String to = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        Raw weekRaw = safeCached("getoutput.jsp?df=" + from + "&dt=" + to + "&limit=7", 6 * 60 * 60_000L);
        Raw yearsRaw = safeCached("getoutput.jsp?a=y&limit=15", 24 * 60 * 60_000L);

        Dashboard d = new Dashboard();
        d.live = live;
        d.history = historyRaw == null ? Collections.emptyList() : parseHistory(historyRaw.body);
        d.week = weekRaw == null ? Collections.emptyList() : parseWeek(weekRaw.body);
        d.years = yearsRaw == null ? Collections.emptyList() : parseYears(yearsRaw.body);

        Integer remaining = liveRaw.remaining;
        if (historyRaw != null && historyRaw.remaining != null) remaining = min(remaining, historyRaw.remaining);
        if (weekRaw != null && weekRaw.remaining != null) remaining = min(remaining, weekRaw.remaining);
        if (yearsRaw != null && yearsRaw.remaining != null) remaining = min(remaining, yearsRaw.remaining);
        d.rateRemaining = remaining;
        return d;
    }

    public Live loadLiveForWidget() throws Exception {
        ensureConfigured();
        return parseLive(cached("getstatus.jsp", 30 * 60_000L).body);
    }

    private Integer min(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.min(a, b);
    }

    private void ensureConfigured() {
        if (apiKey.isEmpty() || systemId.isEmpty()) {
            throw new IllegalStateException("PVOutput System-ID/API-Key fehlen");
        }
    }

    private static final class Raw {
        final String body;
        final Integer remaining;
        Raw(String body, Integer remaining) { this.body = body; this.remaining = remaining; }
    }

    private Raw safeCached(String path, long ttl) {
        try { return cached(path, ttl); } catch (Exception ignored) { return null; }
    }

    private Raw cached(String path, long ttl) throws Exception {
        String key = systemId + "_" + Integer.toUnsignedString(path.hashCode());
        long savedAt = cache.getLong(key + "_time", 0L);
        String saved = cache.getString(key + "_body", null);
        if (ttl > 0 && saved != null && !saved.isEmpty()
                && System.currentTimeMillis() - savedAt < ttl) {
            int x = cache.getInt(key + "_remaining", -1);
            return new Raw(saved, x < 0 ? null : x);
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Pvoutput-Apikey", apiKey);
        headers.put("X-Pvoutput-SystemId", systemId);
        headers.put("X-Rate-Limit", "1");

        Net.Response r = Net.get(BASE + path, headers);
        if (r.code < 200 || r.code >= 300) {
            throw new IllegalStateException("PVOutput HTTP " + r.code + ": " + cut(r.body));
        }
        Integer remaining = null;
        try {
            String h = r.header("X-Rate-Limit-Remaining");
            if (h != null) remaining = Integer.parseInt(h);
        } catch (Exception ignored) {}

        SharedPreferences.Editor e = cache.edit()
                .putLong(key + "_time", System.currentTimeMillis())
                .putString(key + "_body", r.body);
        if (remaining != null) e.putInt(key + "_remaining", remaining);
        e.apply();
        return new Raw(r.body, remaining);
    }

    private Live parseLive(String body) {
        String[] p = body.split(",", -1);
        if (p.length < 4) throw new IllegalStateException("PVOutput Live-Antwort unvollständig");
        Live v = new Live();
        v.date = p[0];
        v.time = p[1];
        v.energyWh = num(p, 2, 0);
        v.powerW = num(p, 3, 0);
        v.consumptionWh = nullable(p, 4);
        v.consumptionW = nullable(p, 5);
        return v;
    }

    private List<Point> parseHistory(String body) {
        List<Point> out = new ArrayList<>();
        for (String row : body.split(";")) {
            String[] p = row.trim().split(",", -1);
            if (p.length < 5) continue;
            Point x = new Point();
            x.time = p.length > 1 ? p[1] : "";
            x.energyWh = num(p, 2, 0);
            x.powerW = num(p, 4, num(p, 3, 0));
            x.consumptionW = nullable(p, 8);
            out.add(x);
        }
        return out;
    }

    private List<Day> parseWeek(String body) {
        List<Day> out = new ArrayList<>();
        for (String row : body.split(";")) {
            String[] p = row.trim().split(",", -1);
            if (p.length < 2 || p[0].isEmpty()) continue;
            Day d = new Day();
            d.date = p[0];
            d.generatedWh = num(p, 1, 0);
            d.consumedWh = nullable(p, 4);
            d.peakPowerW = nullable(p, 5);
            out.add(d);
        }
        return out;
    }

    private List<Year> parseYears(String body) {
        List<Year> out = new ArrayList<>();
        for (String row : body.split(";")) {
            String[] p = row.trim().split(",", -1);
            if (p.length < 3 || p[0].length() < 4) continue;
            Integer y = intOrNull(p[0].substring(0, 4));
            if (y == null) continue;
            Year a = new Year();
            a.year = y;
            a.days = intAt(p, 1, 0);
            a.generatedWh = num(p, 2, 0);
            a.exportedWh = nullable(p, 4);
            a.consumedWh = nullable(p, 5);
            double imports = 0;
            boolean any = false;
            for (int i = 6; i <= 9 && i < p.length; i++) {
                Double v = nullable(p, i);
                if (v != null) { imports += v; any = true; }
            }
            a.importedWh = any ? imports : null;
            out.add(a);
        }
        out.sort((a,b) -> Integer.compare(b.year, a.year));
        return out;
    }

    private double num(String[] p, int i, double def) {
        Double v = nullable(p, i);
        return v == null ? def : v;
    }

    private Double nullable(String[] p, int i) {
        if (i < 0 || i >= p.length) return null;
        String s = p[i].trim();
        if (s.isEmpty() || "nan".equalsIgnoreCase(s)) return null;
        try { return Double.parseDouble(s); } catch (Exception e) { return null; }
    }

    private int intAt(String[] p, int i, int def) {
        if (i < 0 || i >= p.length) return def;
        try { return Integer.parseInt(p[i]); } catch (Exception e) { return def; }
    }

    private Integer intOrNull(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return null; }
    }

    private String cut(String s) {
        if (s == null) return "";
        return s.length() <= 180 ? s : s.substring(0, 180);
    }
}
