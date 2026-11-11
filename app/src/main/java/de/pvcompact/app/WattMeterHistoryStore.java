package de.pvcompact.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

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
    private long lastRegisterWriteMs = 0;
    private long writes = 0;

    public WattMeterHistoryStore(Context context) {
        super(context, "wattmeter_live.db", null, 2);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS samples (time_ms INTEGER PRIMARY KEY, power_w REAL NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS registers (time_ms INTEGER PRIMARY KEY, import_kwh REAL NOT NULL, export_kwh REAL NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS year_anchors (year INTEGER PRIMARY KEY, first_observed_ms INTEGER NOT NULL, import_kwh REAL NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS year_anchors (year INTEGER PRIMARY KEY, first_observed_ms INTEGER NOT NULL, import_kwh REAL NOT NULL)");
            // The 1.7.9 version already recorded registers. Recover the first observation
            // for each year; never reset or delete old sampling data on migration.
            try (Cursor cursor = db.rawQuery("SELECT time_ms,import_kwh FROM registers ORDER BY time_ms", null)) {
                while (cursor.moveToNext()) insertYearAnchor(db, cursor.getLong(0), cursor.getDouble(1));
            }
        }
    }

    public static final class YearAnchor {
        public final int year;
        public final long firstObservedMs;
        public final double importKwh;
        YearAnchor(int year, long firstObservedMs, double importKwh) {
            this.year = year;
            this.firstObservedMs = firstObservedMs;
            this.importKwh = importKwh;
        }
    }

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static void insertYearAnchor(SQLiteDatabase db, long timeMs, double importKwh) {
        if (timeMs <= 0 || !Double.isFinite(importKwh) || importKwh < 0) return;
        int year = Instant.ofEpochMilli(timeMs).atZone(BERLIN).getYear();
        android.content.ContentValues fields = new android.content.ContentValues();
        fields.put("year", year);
        fields.put("first_observed_ms", timeMs);
        fields.put("import_kwh", importKwh);
        db.insertWithOnConflict("year_anchors", null, fields, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public synchronized YearAnchor yearAnchor(int year) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT first_observed_ms, import_kwh FROM year_anchors WHERE year=?",
                new String[]{Integer.toString(year)})) {
            if (c.moveToFirst())
                return new YearAnchor(year, c.getLong(0), c.getDouble(1));
        }
        return null;
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

    public static final class RegisterDelta {
        public final long startMs, endMs;
        public final double importKwh, exportKwh;
        RegisterDelta(long startMs, long endMs, double importKwh, double exportKwh) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.importKwh = importKwh;
            this.exportKwh = exportKwh;
        }
    }

    public synchronized void addRegisters(long timeMs, double importKwh, double exportKwh) {
        if (timeMs <= 0 || !Double.isFinite(importKwh) || !Double.isFinite(exportKwh)
                || importKwh < 0 || exportKwh < 0) return;
        if (lastRegisterWriteMs > 0 && timeMs - lastRegisterWriteMs < 60000L) return;
        lastRegisterWriteMs = timeMs;
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("time_ms", timeMs);
        values.put("import_kwh", importKwh);
        values.put("export_kwh", exportKwh);
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("registers", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        insertYearAnchor(db, timeMs, importKwh);
        db.delete("registers", "time_ms < ?",
                new String[]{Long.toString(timeMs - 30L * 86400000L)});
    }

    public synchronized RegisterDelta dayDelta(long startMs, long endMs) {
        double firstImp = Double.NaN, firstExp = Double.NaN, lastImp = Double.NaN, lastExp = Double.NaN;
        long firstMs = 0, lastMs = 0;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT time_ms,import_kwh,export_kwh FROM registers WHERE time_ms>=? AND time_ms<=? ORDER BY time_ms",
                new String[]{Long.toString(startMs), Long.toString(endMs)})) {
            while (c.moveToNext()) {
                if (firstMs == 0) {
                    firstMs = c.getLong(0);
                    firstImp = c.getDouble(1);
                    firstExp = c.getDouble(2);
                }
                lastMs = c.getLong(0);
                lastImp = c.getDouble(1);
                lastExp = c.getDouble(2);
            }
        }
        if (firstMs == 0 || lastMs <= firstMs || lastImp < firstImp || lastExp < firstExp)
            return null;
        return new RegisterDelta(firstMs, lastMs, lastImp - firstImp, lastExp - firstExp);
    }

    /**
     * Only integrate register deltas between close consecutive observations.
     * Longer outages remain unmeasured instead of being treated as zero consumption
     * or assigned to an arbitrary tariff window.
     */
    public static final class DayEstimate {
        public final double cheapKwh, normalKwh, coveredHours;
        public final long firstMs, lastMs;
        public final int intervals;
        public double totalKwh() { return cheapKwh + normalKwh; }
        public DayEstimate(double cheapKwh, double normalKwh, double coveredHours,
                           long firstMs, long lastMs, int intervals) {
            this.cheapKwh = cheapKwh; this.normalKwh = normalKwh;
            this.coveredHours = coveredHours; this.firstMs = firstMs;
            this.lastMs = lastMs; this.intervals = intervals;
        }
    }

    public synchronized DayEstimate estimateDay(long startMs, long endMs,
                                                 String cheapStart, String cheapEnd) {
        List<long[]> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT time_ms, import_kwh FROM registers WHERE time_ms>=? AND time_ms<=? ORDER BY time_ms",
                new String[]{Long.toString(startMs), Long.toString(endMs)})) {
            while (c.moveToNext())
                rows.add(new long[]{c.getLong(0), Double.doubleToRawLongBits(c.getDouble(1))});
        }
        double cheap = 0, normal = 0, hours = 0;
        int intervals = 0;
        long first = 0, last = 0;
        for (int i = 1; i < rows.size(); i++) {
            long t0 = rows.get(i - 1)[0], t1 = rows.get(i)[0];
            long duration = t1 - t0;
            if (duration <= 0 || duration > 15 * 60_000L) continue;
            double v0 = Double.longBitsToDouble(rows.get(i - 1)[1]);
            double v1 = Double.longBitsToDouble(rows.get(i)[1]);
            double delta = v1 - v0;
            if (delta < 0 || !Double.isFinite(delta) || delta > 100) continue;
            // Apportion any interval spanning a tariff switch proportionally by duration.
            long cursor = t0;
            while (cursor < t1) {
                java.time.ZonedDateTime local = Instant.ofEpochMilli(cursor).atZone(BERLIN);
                boolean isCheap = inWindow(local.toLocalTime(), cheapStart, cheapEnd);
                long next = Math.min(t1, cursor + 60_000L);
                double kwh = delta * (next - cursor) / duration;
                if (isCheap) cheap += kwh;
                else normal += kwh;
                cursor = next;
            }
            hours += duration / 3_600_000.0;
            intervals++;
            if (first == 0) first = t0;
            last = t1;
        }
        return new DayEstimate(cheap, normal, hours, first, last, intervals);
    }

    private static boolean inWindow(java.time.LocalTime time, String begin, String end) {
        java.time.LocalTime start, stop;
        try {
            start = java.time.LocalTime.parse(begin);
            stop = java.time.LocalTime.parse(end);
        } catch (Exception e) {
            start = java.time.LocalTime.MIDNIGHT;
            stop = java.time.LocalTime.of(5, 0);
        }
        if (start.equals(stop)) return false;
        if (start.isBefore(stop)) return !time.isBefore(start) && time.isBefore(stop);
        return !time.isBefore(start) || time.isBefore(stop);
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
