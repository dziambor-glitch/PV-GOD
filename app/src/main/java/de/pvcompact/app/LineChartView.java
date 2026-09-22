package de.pvcompact.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class LineChartView extends View {
    private static final int GREEN = 0xff0d7a5f;
    private static final int GREEN_DARK = 0xff085846;
    private static final int GRID = 0xffe4ece8;
    private static final int MUTED = 0xff6a756f;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakLabel = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<PvOutputClient.Point> points = new ArrayList<>();
    private Integer daylightStartMin;
    private Integer daylightEndMin;

    public LineChartView(Context context) {
        super(context);

        line.setStrokeWidth(dp(3.2f));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(GREEN);

        grid.setStrokeWidth(dp(1));
        grid.setColor(GRID);

        text.setTextSize(dp(10.5f));
        text.setColor(MUTED);

        peakDot.setStyle(Paint.Style.FILL);
        peakDot.setColor(GREEN_DARK);

        peakLabel.setTextSize(dp(10.5f));
        peakLabel.setColor(GREEN_DARK);
        peakLabel.setFakeBoldText(true);

        setMinimumHeight(Math.round(dp(250)));
    }

    public void setPoints(List<PvOutputClient.Point> p) {
        points = p == null ? new ArrayList<>() : new ArrayList<>(p);
        points.sort(Comparator.comparingInt(a -> minuteOfDay(a.time, Integer.MAX_VALUE)));
        invalidate();
    }

    public void setDaylightRange(String sunrise, String sunset) {
        Integer rise = parseDateTimeMinutes(sunrise);
        Integer set = parseDateTimeMinutes(sunset);
        if (rise != null && set != null && set > rise) {
            daylightStartMin = Math.max(0, rise - 20);
            daylightEndMin = Math.min(24 * 60 - 1, set + 10);
        } else {
            daylightStartMin = null;
            daylightEndMin = null;
        }
        invalidate();
    }

    public String daylightLabel() {
        int[] range = resolveRange();
        return formatMinute(range[0]) + "–" + formatMinute(range[1]);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        float w = getWidth();
        float h = getHeight();
        float left = dp(48);
        float right = dp(12);
        float top = dp(20);
        float bottom = dp(34);
        float cw = Math.max(1, w - left - right);
        float ch = Math.max(1, h - top - bottom);
        float baseline = top + ch;

        int[] range = resolveRange();
        int startMin = range[0];
        int endMin = range[1];
        int span = Math.max(1, endMin - startMin);

        List<PlotPoint> plot = new ArrayList<>();
        double maxPower = 0;
        PvOutputClient.Point peakSource = null;

        for (PvOutputClient.Point p : points) {
            int minute = minuteOfDay(p.time, -1);
            if (minute < startMin || minute > endMin) continue;
            if (!Double.isFinite(p.powerW) || p.powerW < 0) continue;
            maxPower = Math.max(maxPower, p.powerW);
            if (peakSource == null || p.powerW > peakSource.powerW) peakSource = p;
        }

        double yMax = nicePowerMax(maxPower);

        // horizontal grid + y labels
        text.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 4; i++) {
            float y = top + ch * i / 4f;
            c.drawLine(left, y, left + cw, y, grid);
            double watts = yMax * (1.0 - i / 4.0);
            c.drawText(formatKw(watts), left - dp(7), y + dp(3.5f), text);
        }

        // daylight x grid
        int midMin = startMin + span / 2;
        float midX = left + cw / 2f;
        c.drawLine(midX, top, midX, baseline, grid);

        // axis labels
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(formatMinute(startMin), left, baseline + dp(21), text);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(formatMinute(midMin), midX, baseline + dp(21), text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(formatMinute(endMin), left + cw, baseline + dp(21), text);

        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("kW", dp(4), top - dp(5), text);

        if (points.size() < 2 || maxPower <= 0) {
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("Noch keine PV-Leistung im Tageslichtfenster",
                    left + cw / 2f, top + ch / 2f, text);
            return;
        }

        // prepend a zero point at the daylight boundary, which makes the solar curve
        // read naturally without wasting half the chart on the night.
        plot.add(new PlotPoint(left, baseline, startMin, 0));

        for (PvOutputClient.Point p : points) {
            int minute = minuteOfDay(p.time, -1);
            if (minute < startMin || minute > endMin) continue;
            if (!Double.isFinite(p.powerW) || p.powerW < 0) continue;
            float x = left + cw * (minute - startMin) / (float) span;
            float y = top + ch * (1f - (float) Math.min(1.0, p.powerW / yMax));
            plot.add(new PlotPoint(x, y, minute, p.powerW));
        }

        if (plot.size() < 3) return;

        PlotPoint lastData = plot.get(plot.size() - 1);
        if (lastData.minute >= endMin - 20) {
            plot.add(new PlotPoint(left + cw, baseline, endMin, 0));
        }

        Path linePath = smoothPath(plot);
        Path areaPath = new Path(linePath);
        PlotPoint last = plot.get(plot.size() - 1);
        PlotPoint first = plot.get(0);
        areaPath.lineTo(last.x, baseline);
        areaPath.lineTo(first.x, baseline);
        areaPath.close();

        fill.setStyle(Paint.Style.FILL);
        fill.setShader(new LinearGradient(
                0, top, 0, baseline,
                new int[]{0x6610a47a, 0x240d7a5f, 0x000d7a5f},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));

        c.save();
        c.clipRect(left, top, left + cw, baseline);
        c.drawPath(areaPath, fill);
        c.drawPath(linePath, line);
        c.restore();

        // peak marker
        if (peakSource != null && peakSource.powerW > 0) {
            int peakMinute = minuteOfDay(peakSource.time, -1);
            if (peakMinute >= startMin && peakMinute <= endMin) {
                float px = left + cw * (peakMinute - startMin) / (float) span;
                float py = top + ch * (1f - (float) Math.min(1.0, peakSource.powerW / yMax));
                c.drawCircle(px, py, dp(4.2f), peakDot);
                c.drawCircle(px, py, dp(2.1f), whitePaint());

                String label = "Peak " + String.format(Locale.GERMANY, "%.1f kW", peakSource.powerW / 1000.0);
                peakLabel.setTextAlign(px > left + cw * 0.72f ? Paint.Align.RIGHT : Paint.Align.LEFT);
                float labelX = px > left + cw * 0.72f ? px - dp(7) : px + dp(7);
                float labelY = Math.max(top + dp(13), py - dp(8));
                c.drawText(label, labelX, labelY, peakLabel);
            }
        }
    }

    private Paint whitePaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        return p;
    }

    private Path smoothPath(List<PlotPoint> p) {
        Path path = new Path();
        if (p.isEmpty()) return path;

        path.moveTo(p.get(0).x, p.get(0).y);
        if (p.size() == 2) {
            path.lineTo(p.get(1).x, p.get(1).y);
            return path;
        }

        for (int i = 1; i < p.size(); i++) {
            PlotPoint prev = p.get(i - 1);
            PlotPoint cur = p.get(i);
            float midX = (prev.x + cur.x) / 2f;
            float midY = (prev.y + cur.y) / 2f;
            path.quadTo(prev.x, prev.y, midX, midY);
        }

        PlotPoint last = p.get(p.size() - 1);
        path.lineTo(last.x, last.y);
        return path;
    }

    private int[] resolveRange() {
        if (daylightStartMin != null && daylightEndMin != null
                && daylightEndMin > daylightStartMin) {
            return new int[]{daylightStartMin, daylightEndMin};
        }

        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        for (PvOutputClient.Point p : points) {
            if (p.powerW <= 10) continue;
            int m = minuteOfDay(p.time, -1);
            if (m < 0) continue;
            first = Math.min(first, m);
            last = Math.max(last, m);
        }

        if (first != Integer.MAX_VALUE && last != Integer.MIN_VALUE && last > first) {
            int start = Math.max(0, first - 30);
            int end = Math.min(24 * 60 - 1, last + 30);
            if (end - start >= 360) return new int[]{start, end};
        }

        return new int[]{5 * 60 + 30, 21 * 60};
    }

    private double nicePowerMax(double maxPower) {
        if (maxPower <= 0) return 1000;
        double step;
        if (maxPower <= 4000) step = 1000;
        else if (maxPower <= 10000) step = 2000;
        else step = 5000;
        return Math.max(step, Math.ceil(maxPower / step) * step);
    }

    private String formatKw(double watts) {
        double kw = watts / 1000.0;
        if (Math.abs(kw - Math.rint(kw)) < 0.01) {
            return String.format(Locale.GERMANY, "%.0f", kw);
        }
        return String.format(Locale.GERMANY, "%.1f", kw);
    }

    private Integer parseDateTimeMinutes(String value) {
        if (value == null || value.isEmpty()) return null;
        int t = value.indexOf('T');
        String time = t >= 0 ? value.substring(t + 1) : value;
        if (time.length() >= 5) time = time.substring(0, 5);
        int m = minuteOfDay(time, -1);
        return m < 0 ? null : m;
    }

    private static int minuteOfDay(String time, int def) {
        if (time == null) return def;
        try {
            String[] p = time.trim().split(":");
            if (p.length < 2) return def;
            int h = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) return def;
            return h * 60 + m;
        } catch (Exception e) {
            return def;
        }
    }

    private String formatMinute(int minute) {
        int m = Math.max(0, Math.min(24 * 60 - 1, minute));
        return String.format(Locale.GERMANY, "%02d:%02d", m / 60, m % 60);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static final class PlotPoint {
        final float x;
        final float y;
        final int minute;
        final double watts;

        PlotPoint(float x, float y, int minute, double watts) {
            this.x = x;
            this.y = y;
            this.minute = minute;
            this.watts = watts;
        }
    }
}
