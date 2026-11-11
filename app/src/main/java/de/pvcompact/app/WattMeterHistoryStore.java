package de.pvcompact.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Local sample cache for periods when PV Compact is connected to MQTT.
 * It does NOT claim to reconstruct periods when the app was not running.
 * WattWaechter's local 15-minute history can be used to fill those gaps later.
 */
public final class WattMeterHistoryStore extends SQLiteOpenHelper {
    public static final class Point {
        public final long timeMs;
        public final double powerW;

        public Point(long timeMs, double powerW) {
            this.timeMs = timeMs;
            this.powerW = powerW;
        }
    }

    private long lastWriteMs = 0;
    private long writes = 0;

    public WattMeterHistoryStore(Context context) {
        super(context, "wattmeter_live.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS samples (time_ms INTEGER PRIMARY KEY, power_w REAL NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Preserve existing samples on future migrations.
    }

    public synchronized void add(long timeMs, double powerW) {
        if (timeMs <= 0 || !Double.isFinite(powerW)) return;
        if (lastWriteMs > 0 && timeMs - lastWriteMs < 5000L) return;
        lastWriteMs = timeMs;
        SQLiteDatabase db = getWritableDatabase();
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("time_ms", timeMs);
        values.put("power_w", powerW);
        db.insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        // Keep the database bounded without running a delete for each message.
        if (++writes % 100 == 0) db.delete("samples", "time_ms < ?",
                new String[]{Long.toString(timeMs - 14L * 86400000L)});
    }

    public synchronized List<Point> load(long sinceMs, long untilMs) {
        List<Point> points = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT time_ms,power_w FROM samples WHERE time_ms>=? AND time_ms<=? ORDER BY time_ms",
                new String[]{Long.toString(sinceMs), Long.toString(untilMs)})) {
            while (c.moveToNext()) points.add(new Point(c.getLong(0), c.getDouble(1)));
        }
        return points;
    }
}
