package de.pvcompact.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class GeocodingClient {
    public static final class Result {
        public double lat;
        public double lon;
        public String displayName = "";
    }

    public Result geocode(String street, String postalCode, String city, String country) throws Exception {
        StringBuilder q = new StringBuilder();
        append(q, street);
        append(q, postalCode);
        append(q, city);
        append(q, country == null || country.trim().isEmpty() ? "Deutschland" : country);

        if (q.length() == 0) throw new IllegalArgumentException("Bitte Straße, Hausnummer und Ort eingeben.");

        String url = "https://nominatim.openstreetmap.org/search"
                + "?format=jsonv2"
                + "&limit=1"
                + "&addressdetails=1"
                + "&countrycodes=de"
                + "&q=" + enc(q.toString());

        Map<String,String> headers = new HashMap<>();
        headers.put("Accept-Language", "de");
        headers.put("Referer", "https://github.com/dziambor-glitch/PV-GOD");

        Net.Response r = Net.get(url, headers);
        if (r.code < 200 || r.code >= 300) {
            throw new IllegalStateException("Adresssuche HTTP " + r.code);
        }

        JSONArray a = new JSONArray(r.body);
        if (a.length() == 0) {
            throw new IllegalStateException("Adresse nicht gefunden. Bitte Schreibweise, PLZ und Ort prüfen.");
        }

        JSONObject o = a.getJSONObject(0);
        Result out = new Result();
        out.lat = Double.parseDouble(o.getString("lat"));
        out.lon = Double.parseDouble(o.getString("lon"));
        out.displayName = o.optString("display_name", q.toString());
        return out;
    }

    private void append(StringBuilder b, String value) {
        if (value == null) return;
        String v = value.trim();
        if (v.isEmpty()) return;
        if (b.length() > 0) b.append(", ");
        b.append(v);
    }

    private String enc(String x) {
        try { return URLEncoder.encode(x, "UTF-8"); } catch (Exception e) { return x; }
    }
}
