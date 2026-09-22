package de.pvcompact.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ForecastClient {
    public static final class Day {
        public String date = "";
        public double energyKwh;
        public Integer weatherCode;
        public Double minC;
        public Double maxC;
    }

    public List<Day> load(double lat, double lon, int tilt, int azimuth,
                          double kwp, double performanceRatio) throws Exception {
        if (kwp <= 0) throw new IllegalArgumentException("kWp muss größer 0 sein");
        String url = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + f(lat)
                + "&longitude=" + f(lon)
                + "&hourly=global_tilted_irradiance"
                + "&daily=weather_code,temperature_2m_min,temperature_2m_max"
                + "&tilt=" + tilt
                + "&azimuth=" + azimuth
                + "&forecast_days=4"
                + "&timezone=" + enc("Europe/Berlin");

        Net.Response r = Net.get(url, null);
        if (r.code < 200 || r.code >= 300) {
            throw new IllegalStateException("Open-Meteo HTTP " + r.code);
        }

        JSONObject root = new JSONObject(r.body);
        JSONObject hourly = root.getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time");
        JSONArray gti = hourly.getJSONArray("global_tilted_irradiance");

        Map<String, Double> sums = new LinkedHashMap<>();
        int n = Math.min(times.length(), gti.length());
        for (int i = 0; i < n; i++) {
            String t = times.optString(i, "");
            if (t.length() < 10) continue;
            String date = t.substring(0, 10);
            double w = gti.optDouble(i, 0.0);
            if (!Double.isFinite(w) || w < 0) w = 0;
            double kwh = (w / 1000.0) * kwp * performanceRatio;
            sums.put(date, sums.getOrDefault(date, 0.0) + kwh);
        }

        Map<String, Day> byDate = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : sums.entrySet()) {
            Day d = new Day();
            d.date = e.getKey();
            d.energyKwh = e.getValue();
            byDate.put(d.date, d);
        }

        JSONObject daily = root.optJSONObject("daily");
        if (daily != null) {
            JSONArray dates = daily.optJSONArray("time");
            JSONArray codes = daily.optJSONArray("weather_code");
            JSONArray mins = daily.optJSONArray("temperature_2m_min");
            JSONArray maxs = daily.optJSONArray("temperature_2m_max");
            if (dates != null) {
                for (int i = 0; i < dates.length(); i++) {
                    String date = dates.optString(i, "");
                    Day d = byDate.get(date);
                    if (d == null) {
                        d = new Day();
                        d.date = date;
                        byDate.put(date, d);
                    }
                    if (codes != null && !codes.isNull(i)) d.weatherCode = codes.optInt(i);
                    if (mins != null && !mins.isNull(i)) d.minC = mins.optDouble(i);
                    if (maxs != null && !maxs.isNull(i)) d.maxC = maxs.optDouble(i);
                }
            }
        }

        return new ArrayList<>(byDate.values());
    }

    private String f(double x) {
        return String.format(Locale.US, "%.6f", x);
    }

    private String enc(String x) {
        try { return URLEncoder.encode(x, "UTF-8"); } catch (Exception e) { return x; }
    }
}
