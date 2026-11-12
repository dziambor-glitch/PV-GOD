package de.pvcompact.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Same time axis for real PVOutput production (green) and WattWaechter
 * grid import (red). Brief unwanted feed-in is a separate, thin green trace.
 * Never join points across missing measurement periods.
 */
public final class GridPowerChartView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint importPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pvPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint exportPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<WattMeterHistoryStore.Point> points = new ArrayList<>();
    private List<WattMeterHistoryStore.Point> pvPoints = new ArrayList<>();
    private List<WattMeterHistoryStore.Point> historicalNetPoints = new ArrayList<>();
    private long fromMs, toMs;
    private boolean fullDay;

    public GridPowerChartView(Context context) {
        super(context);
        grid.setColor(UiKit.LINE);
        grid.setStrokeWidth(dp(1));
        label.setColor(UiKit.MUTED);
        label.setTextSize(dp(10));
        importPaint.setColor(UiKit.RED);
        pvPaint.setColor(UiKit.GREEN_DARK);
        exportPaint.setColor(UiKit.GREEN);
        for (Paint p : new Paint[]{importPaint, pvPaint, exportPaint}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(p == exportPaint ? 1.5f : 2.5f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        mark.setStyle(Paint.Style.FILL);
    }

    public void setData(List<WattMeterHistoryStore.Point> samples, long from, long to, boolean day) {
        this.points = samples == null ? new ArrayList<>() : new ArrayList<>(samples);
        fromMs = from;
        toMs = to;
        fullDay = day;
        invalidate();
    }

    public void setHistoricalNetData(List<WattMeterHistoryStore.Point> samples) {
        historicalNetPoints = samples == null ? new ArrayList<>() : new ArrayList<>(samples);
        invalidate();
    }

    public void setPvData(List<WattMeterHistoryStore.Point> samples) {
        pvPoints = samples == null ? new ArrayList<>() : new ArrayList<>(samples);
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                resolveSize(Math.round(dp(235)), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth(), h = getHeight();
        final float left = dp(50), right = w - dp(11), top = dp(37), bottom = h - dp(31);
        final float cw = Math.max(1, right - left);
        final long span = Math.max(1, toMs - fromMs);

        double maxPositive = 500, maxExport = 0;
        for (WattMeterHistoryStore.Point p : points) {
            if (p.timeMs < fromMs || p.timeMs > toMs || !Double.isFinite(p.powerW)) continue;
            if (p.powerW > 0) maxPositive = Math.max(maxPositive, p.powerW);
            else maxExport = Math.max(maxExport, -p.powerW);
        }
        for (WattMeterHistoryStore.Point p : historicalNetPoints)
            if (p.timeMs >= fromMs && p.timeMs <= toMs && Double.isFinite(p.powerW))
                maxPositive = Math.max(maxPositive, Math.max(0, p.powerW));
        for (WattMeterHistoryStore.Point p : pvPoints)
            if (p.timeMs >= fromMs && p.timeMs <= toMs && Double.isFinite(p.powerW))
                maxPositive = Math.max(maxPositive, Math.max(0, p.powerW));

        maxPositive = Math.ceil(maxPositive / 1000d) * 1000d;
        double bottomRatio = maxExport > 0 ? 0.78 : 1.0;
        final float zeroY = top + (bottom - top) * (float)bottomRatio;
        final float posH = Math.max(1, zeroY - top);
        final float negH = Math.max(1, bottom - zeroY);
        maxExport = Math.max(500, Math.ceil(maxExport / 500d) * 500d);

        for (int i = 0; i <= 4; i++) {
            float y = top + posH * i / 4f;
            canvas.drawLine(left, y, right, y, grid);
            label.setTextAlign(Paint.Align.RIGHT);
            label.setTextSize(dp(9));
            canvas.drawText(shortPower(maxPositive * (1 - i / 4.0)),
                    left - dp(6), y + dp(3), label);
        }
        if (bottomRatio < 1.0) {
            canvas.drawLine(left, bottom, right, bottom, grid);
            label.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(shortPower(-maxExport), left - dp(6), bottom + dp(3), label);
        }

        drawLegend(canvas, left, dp(14));
        label.setTextSize(dp(9));
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.GERMANY);
        label.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(fmt.format(new Date(fromMs)), left, bottom + dp(19), label);
        label.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(fmt.format(new Date(fromMs + span / 2)), left + cw / 2, bottom + dp(19), label);
        label.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(fmt.format(new Date(toMs)), right, bottom + dp(19), label);

        if (points.isEmpty() && pvPoints.isEmpty() && historicalNetPoints.isEmpty()) {
            label.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Noch keine Messwerte vorhanden", left + cw / 2, top + posH / 2, label);
            return;
        }
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        drawTrace(canvas, historicalNetPoints, importPaint, exportPaint,
                fromMs, span, left, cw, zeroY, posH, negH, maxPositive, maxExport,
                20 * 60_000L, true);
        drawTrace(canvas, points, importPaint, exportPaint,
                fromMs, span, left, cw, zeroY, posH, negH, maxPositive, maxExport,
                fullDay ? 120000L : 45000L, true);
        drawTrace(canvas, pvPoints, pvPaint, pvPaint,
                fromMs, span, left, cw, zeroY, posH, negH, maxPositive, maxExport,
                22 * 60_000L, false);
        canvas.restore();
    }

    private void drawLegend(Canvas c, float x, float y) {
        mark.setColor(UiKit.RED);
        c.drawRoundRect(new RectF(x, y - dp(7), x + dp(9), y + dp(2)), dp(2), dp(2), mark);
        label.setTextAlign(Paint.Align.LEFT);
        label.setTextSize(dp(10));
        c.drawText("Netz", x + dp(14), y + dp(1), label);

        float next = x + dp(14) + label.measureText("Netz") + dp(18);
        mark.setColor(UiKit.GREEN_DARK);
        c.drawRoundRect(new RectF(next, y - dp(7), next + dp(9), y + dp(2)), dp(2), dp(2), mark);
        c.drawText("PV", next + dp(14), y + dp(1), label);
    }

    private void drawTrace(Canvas c, List<WattMeterHistoryStore.Point> samples,
                           Paint positive, Paint negative, long startMs, long span,
                           float left, float cw, float zeroY, float posH, float negH,
                           double maxPos, double maxNeg, long maxGap, boolean allowSigned) {
        for (int i = 1; i < samples.size(); i++) {
            WattMeterHistoryStore.Point a = samples.get(i - 1);
            WattMeterHistoryStore.Point b = samples.get(i);
            if (a.timeMs < fromMs || b.timeMs > toMs || b.timeMs <= a.timeMs
                    || b.timeMs - a.timeMs > maxGap
                    || !Double.isFinite(a.powerW) || !Double.isFinite(b.powerW)) continue;
            double av = allowSigned ? a.powerW : Math.max(0, a.powerW);
            double bv = allowSigned ? b.powerW : Math.max(0, b.powerW);
            float ax = left + cw * (a.timeMs - startMs) / span;
            float bx = left + cw * (b.timeMs - startMs) / span;
            float ay = posY(av, zeroY, posH, negH, maxPos, maxNeg);
            float by = posY(bv, zeroY, posH, negH, maxPos, maxNeg);
            if (av >= 0 && bv >= 0) c.drawLine(ax, ay, bx, by, positive);
            else if (av <= 0 && bv <= 0) c.drawLine(ax, ay, bx, by, negative);
            else {
                float frac = (float)(Math.abs(av) / (Math.abs(av) + Math.abs(bv)));
                float cx = ax + (bx - ax) * frac;
                c.drawLine(ax, ay, cx, zeroY, av >= 0 ? positive : negative);
                c.drawLine(cx, zeroY, bx, by, bv >= 0 ? positive : negative);
            }
        }
    }

    private float posY(double value, float zero, float positiveH, float negativeH,
                       double maxPositive, double maxNegative) {
        if (value >= 0) return zero - (float)(value / maxPositive * positiveH);
        return zero + (float)(-value / maxNegative * negativeH);
    }

    private String shortPower(double watt) {
        if (Math.abs(watt) >= 1000)
            return String.format(Locale.GERMANY, "%.0f kW", watt / 1000.0);
        return String.format(Locale.GERMANY, "%.0f W", watt);
    }
}
