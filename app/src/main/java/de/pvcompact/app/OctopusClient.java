package de.pvcompact.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OctopusClient {
    private static final String ENDPOINT = "https://api.oeg-kraken.energy/v1/graphql/";
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    public static final class Interval {
        public String readAt = "";
        public double kwh;
        public boolean cheap;
    }

    public static final class Summary {
        public boolean authenticated;
        public String refreshedToken;
        public String note = "";
        public double totalKwh;
        public double cheapKwh;
        public double normalKwh;
        public double estimatedCostEuro;
        public String latestDate = "";
        public final List<Interval> intervals = new ArrayList<>();
    }

    private final String account;
    private final String apiKey;
    private final String refreshToken;
    private final LocalTime cheapStart;
    private final LocalTime cheapEnd;
    private final double cheapCents;
    private final double normalCents;

    public OctopusClient(String account, String apiKey, String refreshToken,
                         String cheapStart, String cheapEnd,
                         double cheapCents, double normalCents) {
        this.account = safe(account);
        this.apiKey = safe(apiKey);
        this.refreshToken = safe(refreshToken);
        this.cheapStart = parseTime(cheapStart, LocalTime.MIDNIGHT);
        this.cheapEnd = parseTime(cheapEnd, LocalTime.of(5,0));
        this.cheapCents = cheapCents;
        this.normalCents = normalCents;
    }

    public Summary loadRecentConsumption() throws Exception {
        if (account.isEmpty()) throw new IllegalStateException("Octopus Kundennummer fehlt");
        if (apiKey.isEmpty() && refreshToken.isEmpty()) {
            throw new IllegalStateException("Octopus API-Key oder Refresh Token fehlt");
        }

        Auth auth = authenticate();
        Summary s = new Summary();
        s.authenticated = true;
        s.refreshedToken = auth.refreshToken;

        LocalDate today = LocalDate.now(BERLIN);
        LocalDate start = today.minusDays(3);
        LocalDate end = today.plusDays(1);

        String query =
                "query GetConsumption($accountNumber: String!, $start: Date!, $end: Date!) {"
              + " account(accountNumber: $accountNumber) {"
              + "  properties {"
              + "   measurements(startOn: $start, endOn: $end, timezone: \"Europe/Berlin\", first: 500,"
              + "    utilityFilters: [{electricityFilters: {readingDirection: CONSUMPTION, readingFrequencyType: FIFTEEN_MIN_INTERVAL}}]) {"
              + "     edges { node { value unit readAt } }"
              + "   }"
              + "  }"
              + " }"
              + "}";

        JSONObject vars = new JSONObject()
                .put("accountNumber", account)
                .put("start", start.toString())
                .put("end", end.toString());
        JSONObject root = post(query, vars, auth.token);
        String graphError = firstError(root);
        if (graphError != null) {
            s.note = "Login OK, Messwerte derzeit nicht lesbar: " + graphError;
            return s;
        }

        JSONObject accountObj = root.optJSONObject("data") == null ? null
                : root.optJSONObject("data").optJSONObject("account");
        JSONArray properties = accountObj == null ? null : accountObj.optJSONArray("properties");
        if (properties == null) {
            s.note = "Login OK, aber keine Strom-Messwerte im Konto gefunden.";
            return s;
        }

        Map<String, Double> daily = new LinkedHashMap<>();
        for (int p = 0; p < properties.length(); p++) {
            JSONObject prop = properties.optJSONObject(p);
            JSONObject measurements = prop == null ? null : prop.optJSONObject("measurements");
            JSONArray edges = measurements == null ? null : measurements.optJSONArray("edges");
            if (edges == null) continue;
            for (int i = 0; i < edges.length(); i++) {
                JSONObject edge = edges.optJSONObject(i);
                JSONObject node = edge == null ? null : edge.optJSONObject("node");
                if (node == null) continue;
                String readAt = node.optString("readAt", "");
                double value = node.optDouble("value", Double.NaN);
                if (readAt.isEmpty() || !Double.isFinite(value)) continue;
                String unit = node.optString("unit", "").toLowerCase();
                double kwh = (unit.contains("wh") && !unit.contains("kwh")) ? value / 1000.0 : value;
                if (kwh < 0) continue;

                ZonedDateTime zdt;
                try { zdt = Instant.parse(readAt).atZone(BERLIN); }
                catch (Exception ex) {
                    try { zdt = ZonedDateTime.parse(readAt).withZoneSameInstant(BERLIN); }
                    catch (Exception ignored) { continue; }
                }

                Interval in = new Interval();
                in.readAt = readAt;
                in.kwh = kwh;
                in.cheap = isCheap(zdt.toLocalTime());
                s.intervals.add(in);

                s.totalKwh += kwh;
                if (in.cheap) s.cheapKwh += kwh; else s.normalKwh += kwh;
                String date = zdt.toLocalDate().toString();
                daily.put(date, daily.getOrDefault(date, 0.0) + kwh);
            }
        }

        s.estimatedCostEuro = (s.cheapKwh * cheapCents + s.normalKwh * normalCents) / 100.0;
        if (!daily.isEmpty()) {
            s.latestDate = new ArrayList<>(daily.keySet()).get(daily.size() - 1);
        }
        s.note = s.intervals.isEmpty()
                ? "Login OK. Octopus hat für den abgefragten Zeitraum noch keine Intervallwerte geliefert."
                : "Offizielle Smart-Meter-Intervalle von Octopus; Daten können zeitverzögert eintreffen.";
        return s;
    }

    private static final class Auth {
        String token;
        String refreshToken;
    }

    private Auth authenticate() throws Exception {
        JSONObject input = new JSONObject();
        if (!refreshToken.isEmpty()) input.put("refreshToken", refreshToken);
        else input.put("APIKey", apiKey);

        String query =
                "mutation ObtainKrakenToken($input: ObtainJSONWebTokenInput!) {"
              + " obtainKrakenToken(input: $input) { token refreshToken refreshExpiresIn }"
              + "}";

        JSONObject root = post(query, new JSONObject().put("input", input), null);
        String err = firstError(root);
        if (err != null) throw new IllegalStateException("Octopus Login: " + err);

        JSONObject data = root.optJSONObject("data");
        JSONObject obj = data == null ? null : data.optJSONObject("obtainKrakenToken");
        String token = obj == null ? "" : obj.optString("token", "");
        if (token.isEmpty()) throw new IllegalStateException("Octopus hat keinen Token geliefert");

        Auth a = new Auth();
        a.token = token;
        a.refreshToken = obj.optString("refreshToken", "");
        return a;
    }

    private JSONObject post(String query, JSONObject vars, String token) throws Exception {
        JSONObject payload = new JSONObject().put("query", query).put("variables", vars);
        Map<String, String> headers = new HashMap<>();
        if (token != null && !token.isEmpty()) headers.put("Authorization", token);
        Net.Response r = Net.postJson(ENDPOINT, payload.toString(), headers);
        if (r.code < 200 || r.code >= 300) {
            throw new IllegalStateException("Octopus HTTP " + r.code + ": " + cut(r.body));
        }
        return new JSONObject(r.body);
    }

    private String firstError(JSONObject root) {
        JSONArray errors = root.optJSONArray("errors");
        if (errors == null || errors.length() == 0) return null;
        JSONObject e = errors.optJSONObject(0);
        return e == null ? "GraphQL-Fehler" : e.optString("message", "GraphQL-Fehler");
    }

    private boolean isCheap(LocalTime t) {
        if (cheapStart.equals(cheapEnd)) return false;
        if (cheapStart.isBefore(cheapEnd)) {
            return !t.isBefore(cheapStart) && t.isBefore(cheapEnd);
        }
        return !t.isBefore(cheapStart) || t.isBefore(cheapEnd);
    }

    private LocalTime parseTime(String s, LocalTime def) {
        try { return LocalTime.parse(s); } catch (Exception e) { return def; }
    }

    private String safe(String s) { return s == null ? "" : s.trim(); }

    private String cut(String s) {
        if (s == null) return "";
        return s.length() <= 180 ? s : s.substring(0, 180);
    }
}
