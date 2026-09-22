package de.pvcompact.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LineChartView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<PvOutputClient.Point> points = new ArrayList<>();

    public LineChartView(Context context) {
        super(context);
        line.setStrokeWidth(dp(2));
        line.setStyle(Paint.Style.STROKE);
        line.setColor(0xff1565c0);

        grid.setStrokeWidth(dp(1));
        grid.setColor(0xffdddddd);

        text.setTextSize(dp(11));
        text.setColor(0xff555555);
        setMinimumHeight(Math.round(dp(220)));
    }

    public void setPoints(List<PvOutputClient.Point> p) {
        points = p == null ? new ArrayList<>() : new ArrayList<>(p);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        float left = dp(42), right = dp(12), top = dp(16), bottom = dp(28);
        float cw = Math.max(1, w - left - right);
        float ch = Math.max(1, h - top - bottom);

        double max = 1000;
        for (PvOutputClient.Point p : points) max = Math.max(max, p.powerW);
        max = Math.ceil(max / 1000.0) * 1000.0;

        for (int i = 0; i <= 4; i++) {
            float y = top + ch * i / 4f;
            c.drawLine(left, y, left + cw, y, grid);
            double val = max * (1.0 - i / 4.0);
            c.drawText(String.format(Locale.GERMANY, "%.0f", val), dp(2), y + dp(4), text);
        }

        if (points.size() < 2) {
            c.drawText("Noch keine Tageskurve geladen", left + dp(10), top + ch / 2, text);
            return;
        }

        Path path = new Path();
        for (int i = 0; i < points.size(); i++) {
            PvOutputClient.Point p = points.get(i);
            float x = left + cw * i / Math.max(1f, points.size() - 1f);
            float y = top + ch * (1f - (float)Math.min(1.0, p.powerW / max));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, line);

        c.drawText("00", left, top + ch + dp(18), text);
        c.drawText("12", left + cw / 2 - dp(6), top + ch + dp(18), text);
        c.drawText("24", left + cw - dp(12), top + ch + dp(18), text);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
