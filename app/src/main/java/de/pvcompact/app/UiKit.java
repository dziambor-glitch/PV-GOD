package de.pvcompact.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.TextView;

public final class UiKit {
    public static final int BG = Color.rgb(244, 247, 246);
    public static final int CARD = Color.WHITE;
    public static final int INK = Color.rgb(23, 34, 30);
    public static final int MUTED = Color.rgb(102, 117, 111);
    public static final int LINE = Color.rgb(224, 232, 228);
    public static final int GREEN = Color.rgb(13, 122, 95);
    public static final int GREEN_DARK = Color.rgb(8, 88, 70);
    public static final int MINT = Color.rgb(223, 244, 237);
    public static final int BLUE = Color.rgb(44, 110, 170);
    public static final int BLUE_SOFT = Color.rgb(230, 240, 250);
    public static final int PURPLE = Color.rgb(106, 55, 190);
    public static final int PURPLE_SOFT = Color.rgb(241, 234, 252);
    public static final int AMBER = Color.rgb(174, 112, 0);
    public static final int AMBER_SOFT = Color.rgb(255, 244, 210);
    public static final int RED = Color.rgb(179, 38, 30);
    public static final int RED_SOFT = Color.rgb(255, 232, 230);

    private UiKit() {}

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable roundRect(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable outlined(Context c, int fill, int stroke, int radiusDp) {
        GradientDrawable d = roundRect(c, fill, radiusDp);
        d.setStroke(dp(c, 1), stroke);
        return d;
    }

    public static GradientDrawable hero(Context c) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(12, 128, 98), Color.rgb(8, 88, 70)});
        d.setCornerRadius(dp(c, 24));
        return d;
    }

    public static TextView text(Context c, String value, float sp, int color) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setFontFeatureSettings("tnum");
        return v;
    }

    public static TextView pageTitle(Context c, String value) {
        TextView v = text(c, value, 28, INK);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(0, dp(c, 2), 0, dp(c, 2));
        return v;
    }

    public static TextView pageSubtitle(Context c, String value) {
        TextView v = text(c, value, 14, MUTED);
        v.setPadding(0, 0, 0, dp(c, 16));
        return v;
    }

    public static TextView sectionTitle(Context c, String value) {
        TextView v = text(c, value, 17, INK);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(dp(c, 2), dp(c, 16), 0, dp(c, 8));
        return v;
    }

    public static TextView overline(Context c, String value, int color) {
        TextView v = text(c, value.toUpperCase(), 11, color);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setLetterSpacing(0.08f);
        return v;
    }

    public static TextView value(Context c, String value, int color) {
        TextView v = text(c, value, 27, color);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    public static TextView caption(Context c, String value) {
        TextView v = text(c, value, 13, MUTED);
        v.setLineSpacing(0, 1.12f);
        return v;
    }

    public static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 16), dp(c, 15), dp(c, 16), dp(c, 15));
        box.setBackground(outlined(c, CARD, LINE, 18));
        box.setElevation(dp(c, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(c, 12));
        box.setLayoutParams(lp);
        return box;
    }

    public static LinearLayout metricCard(Context c, String label, String value, String detail, int accent, int soft) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 14), dp(c, 13), dp(c, 14), dp(c, 13));
        box.setBackground(outlined(c, CARD, LINE, 18));

        TextView l = overline(c, label, accent);
        box.addView(l);
        TextView v = text(c, value, 22, INK);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(0, dp(c, 6), 0, dp(c, 3));
        box.addView(v);
        TextView d = text(c, detail, 12, MUTED);
        box.addView(d);

        View bar = new View(c);
        bar.setBackground(roundRect(c, accent, 3));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(c, 34), dp(c, 4));
        bp.setMargins(0, dp(c, 10), 0, 0);
        box.addView(bar, bp);
        return box;
    }

    public static TextView pill(Context c, String value, int fg, int bg) {
        TextView v = text(c, value, 12, fg);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        v.setBackground(roundRect(c, bg, 99));
        return v;
    }

    public static Button primaryButton(Context c, String value) {
        Button b = new Button(c);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(roundRect(c, GREEN, 14));
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        return b;
    }

    public static Button secondaryButton(Context c, String value) {
        Button b = new Button(c);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(GREEN_DARK);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(outlined(c, CARD, LINE, 14));
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        return b;
    }

    public static EditText input(Context c, String label, String value, boolean secret) {
        EditText e = new EditText(c);
        e.setHint(label);
        e.setText(value == null ? "" : value);
        e.setTextSize(15);
        e.setTextColor(INK);
        e.setHintTextColor(Color.rgb(128, 141, 135));
        e.setSingleLine(true);
        e.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        e.setBackground(outlined(c, Color.rgb(250, 252, 251), LINE, 13));
        e.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        return e;
    }

    public static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        return v;
    }

    public static Space space(Context c, int heightDp) {
        Space s = new Space(c);
        s.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, heightDp)));
        return s;
    }

    public static ProgressBar progress(Context c, int max, int value, int color) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(Math.max(1, max));
        p.setProgress(Math.max(0, Math.min(max, value)));
        p.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        p.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(232, 238, 235)));
        return p;
    }
}
