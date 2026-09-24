package de.pvcompact.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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
        public String refreshedToken = "";
        public String accountNumber = "";
        public String productCode = "";
        public String productName = "";
        public boolean timeOfUse;
        public boolean tariffFromApi;
        public String cheapStart = "";
        public String cheapEnd = "";
        public double cheapCents = Double.NaN;
        public double normalCents = Double.NaN;
        public String note = "";
        public double totalKwh;
        public double cheapKwh;
        public double normalKwh;
        public double estimatedCostEuro;
        public String latestDate = "";
        public final List<Interval> intervals = new ArrayList<>();
    }

    private static final class Auth {
        String token = "";
        String refreshToken = "";
    }

    private static final class AccountContext {
        String propertyId = "";
        String maloNumber = "";
    }

    private final String account;
    private final String apiKey;
    private final String refreshToken;
    private final String email;
    private final String password;
    private final LocalTime fallbackCheapStart;
    private final LocalTime fallbackCheapEnd;
    private final double fallbackCheapCents;
    private final double fallbackNormalCents;

    public OctopusClient(String account, String apiKey, String refreshToken,
                         String cheapStart, String cheapEnd,
                         double cheapCents, double normalCents) {
        this(account, apiKey, refreshToken, "", "",
                cheapStart, cheapEnd, cheapCents, normalCents);
    }

    /**
     * email/password are deliberately one-shot bootstrap credentials.
     * PV Compact does not persist them. After a successful login the returned
     * refresh token is stored encrypted by MainActivity/Prefs.
     */
    public OctopusClient(String account, String apiKey, String refreshToken,
                         String email, String password,
                         String cheapStart, String cheapEnd,
                         double cheapCents, double normalCents) {
        this.account = safe(account);
        this.apiKey = safe(apiKey);
        this.refreshToken = safe(refreshToken);
        this.email = safe(email);
        this.password = password == null ? "" : password;
        this.fallbackCheapStart = parseTime(cheapStart, LocalTime.MIDNIGHT);
        this.fallbackCheapEnd = parseTime(cheapEnd, LocalTime.of(5, 0));
        this.fallbackCheapCents = cheapCents;
        this.fallbackNormalCents = normalCents;
    }

    public Summary loadRecentConsumption() throws Exception {
        if (apiKey.isEmpty() && refreshToken.isEmpty()
                && (email.isEmpty() || password.isEmpty())) {
            throw new IllegalStateException(
                    "Octopus Zugang fehlt: API-Key, Refresh Token oder einmaliger Login erforderlich");
        }

        Auth auth = authenticate();
        Summary s = new Summary();
        s.authenticated = true;
        s.refreshedToken = auth.refreshToken;

        String resolvedAccount = account;
        if (resolvedAccount.isEmpty()) {
            resolvedAccount = discoverSingleAccount(auth.token);
        }
        if (resolvedAccount.isEmpty()) {
            throw new IllegalStateException("Keine Octopus Kundennummer im Konto gefunden");
        }
        s.accountNumber = resolvedAccount;

        AccountContext context = new AccountContext();
        try {
            context = loadTariff(resolvedAccount, auth.token, s);
        } catch (Exception tariffError) {
            s.note = "Tarif konnte nicht automatisch gelesen werden: " + cut(tariffError.getMessage());
        }

        if (context.propertyId.isEmpty() || context.maloNumber.isEmpty()) {
            appendNote(s, "Smart-Meter-Zuordnung fehlt im Octopus-Konto.");
            return s;
        }

        loadMeasurements(context, auth.token, s);

        double cheapRate = Double.isFinite(s.cheapCents) ? s.cheapCents : fallbackCheapCents;
        double normalRate = Double.isFinite(s.normalCents) ? s.normalCents : fallbackNormalCents;
        s.estimatedCostEuro = (s.cheapKwh * cheapRate + s.normalKwh * normalRate) / 100.0;

        if (s.intervals.isEmpty()) {
            appendNote(s, "Login OK. Octopus hat für den abgefragten Zeitraum noch keine Intervallwerte geliefert.");
        } else {
            appendNote(s, "Offizielle Smart-Meter-Intervalle von Octopus; Daten können zeitverzögert eintreffen.");
        }
        return s;
    }

    private Auth authenticate() throws Exception {
        JSONObject input = new JSONObject();
        if (!refreshToken.isEmpty()) {
            input.put("refreshToken", refreshToken);
        } else if (!apiKey.isEmpty()) {
            input.put("APIKey", apiKey);
        } else {
            input.put("email", email);
            input.put("password", password);
        }

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

    private String discoverSingleAccount(String token) throws Exception {
        String query = "query { viewer { accounts { number } } }";
        JSONObject root = post(query, new JSONObject(), token);
        String err = firstError(root);
        if (err != null) throw new IllegalStateException("Kontosuche: " + err);

        JSONObject data = root.optJSONObject("data");
        JSONObject viewer = data == null ? null : data.optJSONObject("viewer");
        JSONArray accounts = viewer == null ? null : viewer.optJSONArray("accounts");
        if (accounts == null || accounts.length() == 0) return "";
        if (accounts.length() > 1) {
            throw new IllegalStateException(
                    "Mehrere Octopus-Konten gefunden. Bitte die Kundennummer in den Einstellungen eintragen.");
        }
        JSONObject first = accounts.optJSONObject(0);
        return first == null ? "" : first.optString("number", "");
    }

    private AccountContext loadTariff(String accountNumber, String token, Summary s) throws Exception {
        String query =
                "query GetPvCompactTariff($accountNumber: String!) {"
              + " account(accountNumber: $accountNumber) {"
              + "  allProperties {"
              + "   id"
              + "   electricityMalos {"
              + "    maloNumber"
              + "    agreements {"
              + "     isActive isRevoked isTerminated validFrom validTo"
              + "     product { code description fullName isTimeOfUse }"
              + "     unitRateInformation {"
              + "      __typename"
              + "      ... on SimpleProductUnitRateInformation { latestGrossUnitRateCentsPerKwh }"
              + "      ... on TimeOfUseProductUnitRateInformation {"
              + "       rates {"
              + "        latestGrossUnitRateCentsPerKwh"
              + "        timeslotName"
              + "        timeslotActivationRules { activeFromTime activeToTime }"
              + "       }"
              + "      }"
              + "     }"
              + "    }"
              + "   }"
              + "  }"
              + " }"
              + "}";

        JSONObject vars = new JSONObject().put("accountNumber", accountNumber);
        JSONObject root = post(query, vars, token);
        String err = firstError(root);
        if (err != null) throw new IllegalStateException("Tarifabfrage: " + err);

        AccountContext context = new AccountContext();
        JSONObject data = root.optJSONObject("data");
        JSONObject accountObj = data == null ? null : data.optJSONObject("account");
        JSONArray properties = accountObj == null ? null : accountObj.optJSONArray("allProperties");
        if (properties == null) return context;

        JSONObject selectedAgreement = null;
        for (int p = 0; p < properties.length(); p++) {
            JSONObject prop = properties.optJSONObject(p);
            if (prop == null) continue;
            JSONArray malos = prop.optJSONArray("electricityMalos");
            if (malos == null) continue;

            for (int m = 0; m < malos.length(); m++) {
                JSONObject malo = malos.optJSONObject(m);
                if (malo == null) continue;
                JSONArray agreements = malo.optJSONArray("agreements");
                if (agreements == null) continue;

                JSONObject candidate = chooseAgreement(agreements);
                if (candidate != null) {
                    context.propertyId = prop.optString("id", "");
                    context.maloNumber = malo.optString("maloNumber", "");
                    selectedAgreement = candidate;
                    break;
                }
            }
            if (selectedAgreement != null) break;
        }

        if (selectedAgreement == null) return context;

        JSONObject product = selectedAgreement.optJSONObject("product");
        if (product != null) {
            s.productCode = product.optString("code", "");
            s.productName = product.optString("fullName", "");
            if (s.productName.isEmpty()) s.productName = product.optString("description", "");
            s.timeOfUse = product.optBoolean("isTimeOfUse", false);
        }

        JSONObject ratesInfo = selectedAgreement.optJSONObject("unitRateInformation");
        if (ratesInfo == null) return context;

        String type = ratesInfo.optString("__typename", "");
        if ("SimpleProductUnitRateInformation".equals(type)) {
            double rate = ratesInfo.optDouble("latestGrossUnitRateCentsPerKwh", Double.NaN);
            if (Double.isFinite(rate)) {
                s.normalCents = rate;
                s.tariffFromApi = true;
            }
            return context;
        }

        JSONArray rates = ratesInfo.optJSONArray("rates");
        if (rates == null || rates.length() == 0) return context;

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        JSONObject cheapest = null;
        for (int i = 0; i < rates.length(); i++) {
            JSONObject rateObj = rates.optJSONObject(i);
            if (rateObj == null) continue;
            double rate = rateObj.optDouble("latestGrossUnitRateCentsPerKwh", Double.NaN);
            if (!Double.isFinite(rate)) continue;
            if (rate < min) {
                min = rate;
                cheapest = rateObj;
            }
            if (rate > max) max = rate;
        }

        if (Double.isFinite(min)) s.cheapCents = min;
        if (Double.isFinite(max)) s.normalCents = max;
        if (Double.isFinite(min) || Double.isFinite(max)) s.tariffFromApi = true;

        if (cheapest != null) {
            JSONArray rules = cheapest.optJSONArray("timeslotActivationRules");
            if (rules != null && rules.length() > 0) {
                JSONObject rule = rules.optJSONObject(0);
                if (rule != null) {
                    s.cheapStart = normalizeTime(rule.optString("activeFromTime", ""));
                    s.cheapEnd = normalizeTime(rule.optString("activeToTime", ""));
                }
            }
        }
        return context;
    }

    private JSONObject chooseAgreement(JSONArray agreements) {
        JSONObject fallback = null;
        for (int i = 0; i < agreements.length(); i++) {
            JSONObject a = agreements.optJSONObject(i);
            if (a == null) continue;
            if (a.optBoolean("isActive", false)) return a;
            if (!a.optBoolean("isRevoked", false) && !a.optBoolean("isTerminated", false)
                    && fallback == null) {
                fallback = a;
            }
        }
        return fallback;
    }

    private void loadMeasurements(AccountContext context, String token, Summary s) throws Exception {
        String query =
                "query GetPvCompactMeasurements("
              + " $propertyId: ID!, $first: Int!, $after: String,"
              + " $utilityFilters: [UtilityFiltersInput!]!,"
              + " $startAt: DateTime!, $endAt: DateTime!, $timezone: String!) {"
              + " property(id: $propertyId) {"
              + "  measurements(first: $first, after: $after,"
              + "   utilityFilters: $utilityFilters, startAt: $startAt, endAt: $endAt, timezone: $timezone) {"
              + "   pageInfo { hasNextPage endCursor }"
              + "   edges { node {"
              + "    __typename source value unit"
              + "    ... on IntervalMeasurementType { startAt endAt durationInSeconds }"
              + "   } }"
              + "  }"
              + " }"
              + "}";

        LocalDate today = LocalDate.now(BERLIN);
        ZonedDateTime start = today.minusDays(3).atStartOfDay(BERLIN);
        ZonedDateTime end = today.plusDays(1).atStartOfDay(BERLIN);

        JSONObject electricityFilters = new JSONObject()
                .put("marketSupplyPointId", context.maloNumber)
                .put("readingFrequencyType", "RAW_INTERVAL");
        JSONArray utilityFilters = new JSONArray()
                .put(new JSONObject().put("electricityFilters", electricityFilters));

        String cursor = null;
        Map<String, Double> daily = new LinkedHashMap<>();

        for (int page = 0; page < 10; page++) {
            JSONObject vars = new JSONObject()
                    .put("propertyId", context.propertyId)
                    .put("first", 100)
                    .put("utilityFilters", utilityFilters)
                    .put("startAt", start.toOffsetDateTime().toString())
                    .put("endAt", end.toOffsetDateTime().toString())
                    .put("timezone", "Europe/Berlin");
            if (cursor == null) vars.put("after", JSONObject.NULL);
            else vars.put("after", cursor);

            JSONObject root = post(query, vars, token);
            String graphError = firstError(root);
            if (graphError != null) {
                appendNote(s, "Messwerte derzeit nicht lesbar: " + graphError);
                return;
            }

            JSONObject data = root.optJSONObject("data");
            JSONObject property = data == null ? null : data.optJSONObject("property");
            JSONObject measurements = property == null ? null : property.optJSONObject("measurements");
            JSONArray edges = measurements == null ? null : measurements.optJSONArray("edges");
            if (edges != null) {
                for (int i = 0; i < edges.length(); i++) {
                    JSONObject edge = edges.optJSONObject(i);
                    JSONObject node = edge == null ? null : edge.optJSONObject("node");
                    if (node == null) continue;
                    String readAt = node.optString("startAt", "");
                    double value = node.optDouble("value", Double.NaN);
                    if (readAt.isEmpty() || !Double.isFinite(value) || value < 0) continue;

                    String unit = node.optString("unit", "").toLowerCase();
                    double kwh = (unit.contains("wh") && !unit.contains("kwh"))
                            ? value / 1000.0 : value;

                    ZonedDateTime zdt = parseDateTime(readAt);
                    if (zdt == null) continue;

                    Interval in = new Interval();
                    in.readAt = readAt;
                    in.kwh = kwh;
                    in.cheap = isCheap(zdt.toLocalTime(), s);
                    s.intervals.add(in);

                    s.totalKwh += kwh;
                    if (in.cheap) s.cheapKwh += kwh;
                    else s.normalKwh += kwh;
                    String date = zdt.toLocalDate().toString();
                    daily.put(date, daily.getOrDefault(date, 0.0) + kwh);
                }
            }

            JSONObject pageInfo = measurements == null ? null : measurements.optJSONObject("pageInfo");
            boolean hasNext = pageInfo != null && pageInfo.optBoolean("hasNextPage", false);
            String next = pageInfo == null ? "" : pageInfo.optString("endCursor", "");
            if (!hasNext || next.isEmpty() || next.equals(cursor)) break;
            cursor = next;
        }

        if (!daily.isEmpty()) {
            s.latestDate = new ArrayList<>(daily.keySet()).get(daily.size() - 1);
        }
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

    private boolean isCheap(LocalTime t, Summary s) {
        LocalTime start = parseTime(s.cheapStart, fallbackCheapStart);
        LocalTime end = parseTime(s.cheapEnd, fallbackCheapEnd);
        if (start.equals(end)) return false;
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        return !t.isBefore(start) || t.isBefore(end);
    }

    private ZonedDateTime parseDateTime(String value) {
        try { return Instant.parse(value).atZone(BERLIN); }
        catch (Exception ignored) {}
        try { return OffsetDateTime.parse(value).atZoneSameInstant(BERLIN); }
        catch (Exception ignored) {}
        try { return ZonedDateTime.parse(value).withZoneSameInstant(BERLIN); }
        catch (Exception ignored) {}
        return null;
    }

    private String normalizeTime(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try { return LocalTime.parse(value.trim()).toString().substring(0, 5); }
        catch (Exception ignored) {}
        if (value.length() >= 5) return value.substring(0, 5);
        return value;
    }

    private LocalTime parseTime(String s, LocalTime def) {
        try { return LocalTime.parse(s); }
        catch (Exception e) { return def; }
    }

    private void appendNote(Summary s, String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (s.note == null || s.note.trim().isEmpty()) s.note = text;
        else s.note = s.note + " " + text;
    }

    private String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private String cut(String s) {
        if (s == null) return "";
        return s.length() <= 180 ? s : s.substring(0, 180);
    }
}
