package de.pvcompact.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ForecastCalibration {
    private static final String PREFS = "forecast_calibration_v1";
    private static final int MIN_SAMPLES = 3;
    private static final int MAX_SAMPLES = 14;

    public static final class Stats {
        public int samples;
        public double autoFactor = 1.0;
        public double medianRatio = 1.0;
    }

    private final SharedPreferences p;

    public ForecastCalibration(Context context) {
        p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void recordForecasts(List<ForecastClient.Day> days, double manualFactor) {
        if (days == null || days.isEmpty()) return;

        LocalDate today = LocalDate.now();
        SharedPreferences.Editor e = p.edit();

        for (ForecastClient.Day d : days) {
            if (d == null || d.date == null || d.date.isEmpty() || d.energyKwh <= 0) continue;
            LocalDate date = parseDate(d.date);
            if (date == null || date.isBefore(today)) continue;

            String key = "pred_" + date;
            double shownKwh = d.energyKwh * manualFactor;

            // Future forecasts may improve as the weather model gets closer to the day.
            // Once the day has started, freeze the value that will later be compared
            // with the completed PVOutput day.
            if (date.isAfter(today) || !p.contains(key)) {
                e.putFloat(key, (float) shownKwh);
            }
        }
        e.apply();
        cleanup();
    }

    public void updateActuals(List<PvOutputClient.Day> days) {
        if (days == null || days.isEmpty()) return;

        LocalDate today = LocalDate.now();
        SharedPreferences.Editor e = p.edit();

        for (PvOutputClient.Day d : days) {
            LocalDate date = parseDate(d.date);
            if (date == null || !date.isBefore(today) || d.generatedWh <= 0) continue;

            String predictionKey = "pred_" + date;
            if (!p.contains(predictionKey)) continue;

            double predicted = p.getFloat(predictionKey, 0f);
            if (predicted <= 0.1) continue;

            double actual = d.generatedWh / 1000.0;
            double ratio = actual / predicted;

            // Guard against bad/missing days. The automatic learner is deliberately
            // conservative because zero-export can make measured PV lower than the
            // physically available solar yield.
            if (ratio < 0.45 || ratio > 1.55) continue;

            e.putFloat("ratio_" + date, (float) ratio);
        }

        e.apply();
        cleanup();
    }

    public Stats stats() {
        LocalDate today = LocalDate.now();
        List<Double> ratios = new ArrayList<>();

        for (int daysAgo = 1; daysAgo <= 30 && ratios.size() < MAX_SAMPLES; daysAgo++) {
            LocalDate date = today.minusDays(daysAgo);
            String key = "ratio_" + date;
            if (p.contains(key)) ratios.add((double) p.getFloat(key, 1f));
        }

        Stats s = new Stats();
        s.samples = ratios.size();
        if (ratios.isEmpty()) return s;

        List<Double> sorted = new ArrayList<>(ratios);
        Collections.sort(sorted);
        int n = sorted.size();
        double median = n % 2 == 1
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        s.medianRatio = median;

        // Only fine-tune around the user's manual correction. This avoids the learner
        // running away on curtailed zero-export days. With fewer than three completed
        // days the effect is gradually blended in.
        double clamped = Math.max(0.90, Math.min(1.10, median));
        double confidence = Math.min(1.0, n / (double) MIN_SAMPLES);
        s.autoFactor = 1.0 + (clamped - 1.0) * confidence;
        return s;
    }

    private void cleanup() {
        LocalDate cutoff = LocalDate.now().minusDays(45);
        SharedPreferences.Editor e = p.edit();
        for (String key : p.getAll().keySet()) {
            if (!(key.startsWith("pred_") || key.startsWith("ratio_"))) continue;
            int underscore = key.indexOf('_');
            if (underscore < 0 || underscore + 1 >= key.length()) continue;
            LocalDate date = parseDate(key.substring(underscore + 1));
            if (date != null && date.isBefore(cutoff)) e.remove(key);
        }
        e.apply();
    }

    private LocalDate parseDate(String value) {
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
}
