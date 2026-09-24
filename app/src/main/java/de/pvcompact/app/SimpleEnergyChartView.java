package de.pvcompact.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SimpleEnergyChartView extends View {
    public static final int TYPE_GROUPED_BAR = 1;
    public static final int TYPE_STACKED_BAR = 2;
    public static final int TYPE_LINE = 3;

    public static final class Series {
        public final String label;
        public final double[] values;
        public final int color;

        public Series(String label, double[] values, int color) {
            this.label = label == null ? "" : label;
            this.values = values == null ? new double[0] : values;
            this.color = color;
        }
    }

    private final int type;
    private final List<String> labels;
    private final List<Series> series;
    private final String suffix;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public SimpleEnergyChartView(Context context, int type, List<String> labels,
                                 List<Series> series, String suffix) {
        super(context);
        this.type = type;
        this.labels = labels == null ? Collections.emptyList() : new ArrayList<>(labels);
        this.series = series == null ? Collections.emptyList() : new ArrayList<>(series);
        this.suffix = suffix == null ? "" : suffix;
        this.density = getResources().getDisplayMetrics().density;
        text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        setMinimumHeight(dp(235));
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int desired = dp(250);
        int h = resolveSize(desired, heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (labels.isEmpty() || series.isEmpty()) {
            text.setColor(UiKit.MUTED);
            text.setTextSize(dp(12));
            canvas.drawText("Noch keine Daten", dp(12), dp(30), text);
            return;
        }

        float left = dp(38);
        float top = dp(34);
        float right = getWidth() - dp(10);
        float bottom = getHeight() - dp(42);
        float chartW = Math.max(1f, right - left);
        float chartH = Math.max(1f, bottom - top);

        double max = maxValue();
        if (max <= 0) max = 1;

        paint.setStrokeWidth(dp(1));
        paint.setColor(UiKit.LINE);
        text.setColor(UiKit.MUTED);
        text.setTextSize(dp(9));

        for (int i = 0; i <= 4; i++) {
            float y = bottom - chartH * i / 4f;
            canvas.drawLine(left, y, right, y, paint);
            double v = max * i / 4.0;
            canvas.drawText(shortNumber(v), dp(2), y + dp(3), text);
        }

        drawLegend(canvas);

        if (type == TYPE_LINE) drawLines(canvas, left, top, right, bottom, max);
        else drawBars(canvas, left, top, right, bottom, max);

        drawLabels(canvas, left, right, bottom);
    }

    private void drawLegend(Canvas canvas) {
        float x = dp(4);
        float y = dp(14);
        text.setTextSize(dp(9));
        for (Series s : series) {
            paint.setColor(s.color);
            canvas.drawRoundRect(new RectF(x, y - dp(7), x + dp(9), y + dp(2)),
                    dp(2), dp(2), paint);
            text.setColor(UiKit.MUTED);
            canvas.drawText(s.label, x + dp(13), y + dp(1), text);
            x += dp(13) + text.measureText(s.label) + dp(12);
        }
    }

    private void drawBars(Canvas canvas, float left, float top, float right, float bottom, double max) {
        int n = labels.size();
        float chartW = right - left;
        float groupW = chartW / Math.max(1, n);
        float usable = groupW * 0.72f;

        for (int i = 0; i < n; i++) {
            float groupLeft = left + i * groupW + (groupW - usable) / 2f;

            if (type == TYPE_STACKED_BAR) {
                float yBottom = bottom;
                for (Series s : series) {
                    double value = valueAt(s, i);
                    float h = (float) (value / max * (bottom - top));
                    paint.setColor(s.color);
                    canvas.drawRoundRect(new RectF(groupLeft, yBottom - h,
                                    groupLeft + usable, yBottom),
                            dp(2), dp(2), paint);
                    yBottom -= h;
                }
            } else {
                int count = Math.max(1, series.size());
                float barW = usable / count;
                for (int j = 0; j < series.size(); j++) {
                    Series s = series.get(j);
                    double value = valueAt(s, i);
                    float h = (float) (value / max * (bottom - top));
                    float x1 = groupLeft + j * barW;
                    paint.setColor(s.color);
                    canvas.drawRoundRect(new RectF(x1, bottom - h,
                                    x1 + Math.max(dp(2), barW - dp(2)), bottom),
                            dp(2), dp(2), paint);
                }
            }
        }
    }

    private void drawLines(Canvas canvas, float left, float top, float right, float bottom, double max) {
        int n = labels.size();
        if (n == 0) return;

        for (Series s : series) {
            paint.setColor(s.color);
            paint.setStrokeWidth(dp(2.5f));
            paint.setStyle(Paint.Style.STROKE);
            Path path = new Path();

            for (int i = 0; i < n; i++) {
                float x = n == 1 ? (left + right) / 2f : left + (right - left) * i / (n - 1f);
                double value = valueAt(s, i);
                float y = bottom - (float) (value / max * (bottom - top));
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);

            for (int i = 0; i < n; i++) {
                float x = n == 1 ? (left + right) / 2f : left + (right - left) * i / (n - 1f);
                double value = valueAt(s, i);
                float y = bottom - (float) (value / max * (bottom - top));
                canvas.drawCircle(x, y, dp(3), paint);
            }
        }
        paint.setStrokeWidth(dp(1));
    }

    private void drawLabels(Canvas canvas, float left, float right, float bottom) {
        int n = labels.size();
        if (n == 0) return;
        float groupW = (right - left) / n;
        text.setColor(UiKit.MUTED);
        text.setTextSize(dp(8.5f));
        text.setTextAlign(Paint.Align.CENTER);

        int step = n > 8 ? 2 : 1;
        for (int i = 0; i < n; i += step) {
            String label = labels.get(i);
            float x = left + groupW * (i + 0.5f);
            canvas.drawText(label, x, bottom + dp(18), text);
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    private double maxValue() {
        double max = 0;
        if (type == TYPE_STACKED_BAR) {
            for (int i = 0; i < labels.size(); i++) {
                double sum = 0;
                for (Series s : series) sum += Math.max(0, valueAt(s, i));
                max = Math.max(max, sum);
            }
        } else {
            for (Series s : series) {
                for (int i = 0; i < labels.size(); i++) {
                    max = Math.max(max, Math.max(0, valueAt(s, i)));
                }
            }
        }
        return max * 1.12;
    }

    private double valueAt(Series s, int index) {
        if (s == null || index < 0 || index >= s.values.length) return 0;
        double v = s.values[index];
        return Double.isFinite(v) ? Math.max(0, v) : 0;
    }

    private String shortNumber(double value) {
        if ("%".equals(suffix)) return Math.round(value) + "%";
        if ("€".equals(suffix)) {
            if (value >= 1000) return String.format(java.util.Locale.GERMANY, "%.1fk €", value / 1000.0);
            return String.format(java.util.Locale.GERMANY, "%.0f €", value);
        }
        if (value >= 1000) return String.format(java.util.Locale.GERMANY, "%.1fk", value / 1000.0);
        return String.format(java.util.Locale.GERMANY, "%.0f", value);
    }
}
