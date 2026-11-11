package de.pvcompact.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Signed grid power: import above zero (blue), feed-in below zero (amber). */
public final class GridPowerChartView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint importPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint exportPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<WattMeterHistoryStore.Point> points = new ArrayList<>();
    private long fromMs, toMs;
    private boolean fullDay;

    public GridPowerChartView(Context context) {
        super(context);
        grid.setColor(UiKit.LINE);
        grid.setStrokeWidth(dp(1));
        label.setColor(UiKit.MUTED);
        label.setTextSize(dp(10));
        importPaint.setColor(UiKit.BLUE);
        exportPaint.setColor(UiKit.AMBER);
        for (Paint p : new Paint[]{importPaint, exportPaint}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.6f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
    }

    public void setData(List<WattMeterHistoryStore.Point> samples, long from, long to, boolean day) {
        this.points = samples == null ? new ArrayList<>() : new ArrayList<>(samples);
        fromMs = from;
        toMs = to;
        fullDay = day;
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
        final float left = dp(49), right = w - dp(10), top = dp(19), bottom = h - dp(32);
        final float zeroY = (top + bottom) / 2f;
        final float halfH = (bottom - top) / 2f;
        final float cw = Math.max(1, right - left);
        final long span = Math.max(1, toMs - fromMs);

        double maxAbs = 400;
        for (WattMeterHistoryStore.Point p : points) {
            if (Double.isFinite(p.powerW)) maxAbs = Math.max(maxAbs, Math.abs(p.powerW));
        }
        // Use symmetric signed axis: imports and exports are visually comparable.
        maxAbs = Math.ceil(maxAbs / 500d) * 500d;
        grid.setStrokeWidth(dp(1));
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, grid);
        }
        label.setTextAlign(Paint.Align.RIGHT);
        label.setTextSize(dp(9));
        canvas.drawText(formatPower(maxAbs), left - dp(5), top + dp(4), label);
        canvas.drawText("0 W", left - dp(5), zeroY + dp(4), label);
        canvas.drawText(formatPower(-maxAbs), left - dp(5), bottom + dp(4), label);

        label.setTextAlign(Paint.Align.LEFT);
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.GERMANY);
        canvas.drawText(fmt.format(new Date(fromMs)), left, bottom + dp(21), label);
        label.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(fmt.format(new Date(fromMs + span / 2)), left + cw / 2, bottom + dp(21), label);
        label.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(fmt.format(new Date(toMs)), right, bottom + dp(21), label);

        if (points.isEmpty()) {
            label.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Noch keine MQTT-Messwerte erfasst",
                    left + cw / 2f, zeroY - dp(12), label);
            return;
        }

        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        for (int i = 1; i < points.size(); i++) {
            WattMeterHistoryStore.Point a = points.get(i - 1);
            WattMeterHistoryStore.Point b = points.get(i);
            // Explicitly leave gaps; never imply measurements while the app was offline.
            if (b.timeMs <= a.timeMs || b.timeMs - a.timeMs > (fullDay ? 120000 : 45000)) continue;
            if (!Double.isFinite(a.powerW) || !Double.isFinite(b.powerW)) continue;
            float ax = left + cw * (a.timeMs - fromMs) / span;
            float bx = left + cw * (b.timeMs - fromMs) / span;
            float ay = zeroY - (float) (a.powerW / maxAbs * halfH);
            float by = zeroY - (float) (b.powerW / maxAbs * halfH);
            if ((a.powerW >= 0 && b.powerW >= 0)) {
                canvas.drawLine(ax, ay, bx, by, importPaint);
            } else if (a.powerW <= 0 && b.powerW <= 0) {
                canvas.drawLine(ax, ay, bx, by, exportPaint);
            } else {
                float fraction = (float)(Math.abs(a.powerW)
                        / (Math.abs(a.powerW) + Math.abs(b.powerW)));
                float crossX = ax + (bx - ax) * fraction;
                canvas.drawLine(ax, ay, crossX, zeroY, a.powerW >= 0 ? importPaint : exportPaint);
                canvas.drawLine(crossX, zeroY, bx, by, b.powerW >= 0 ? importPaint : exportPaint);
            }
        }
        canvas.restore();
    }

    private String formatPower(double value) {
        if (Math.abs(value) >= 1000) return String.format(Locale.GERMANY, "%.1f kW", value / 1000);
        return String.format(Locale.GERMANY, "%.0f W", value);
    }
}
