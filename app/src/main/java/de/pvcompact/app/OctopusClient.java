package de.pvcompact.app;

import android.content.Context;
import android.content.SharedPreferences;

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
    private static final long HISTORY_TTL_MS = 24L * 60L * 60L * 1000L;

    public static final class Interval {
        public String readAt = "";
        public double kwh;
        public boolean cheap;
    }

    public static final class DailyUsage {
        public String date = "";
        public double totalKwh;
        public double cheapKwh;
        public double normalKwh;
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
        public boolean smartMeterExpected;
        public boolean smartMeterDataAvailable;
        public int recentIntervalCount;
        public int historyDayCount;
        public String latestReadingAt = "";
        public String measurementSource = "";
        public final List<Interval> intervals = new ArrayList<>();
        public final List<DailyUsage> dailyHistory = new ArrayList<>();
    }

    private static final class Auth {
        String token = "";
        String refreshToken = "";
    }

    private static final class AccountContext {
        String propertyId = "";
        String maloNumber = "";
        boolean smartMeterExpected;
    }

    private final SharedPreferences cache;
    private final String account;
    private final String apiKey;
    private final String refreshToken;
    private final String email;
    private final String password;
    private final LocalTime fallbackCheapStart;
    private final LocalTime fallbackCheapEnd;
    private final double fallbackCheapCents;
    private final double fallbackNormalCents;

    public OctopusClient(Context context,
                         String account, String apiKey, String refreshToken,
                         String cheapStart, String cheapEnd,
                         double cheapCents, double normalCents) {
        this(context, account, apiKey, refreshToken, "", "",
                cheapStart, cheapEnd, cheapCents, normalCents);
    }

    /**
     * email/password are one-shot bootstrap credentials. They are never persisted
     * by this class. MainActivity stores only the returned refresh token encrypted.
     */
    public OctopusClient(Context context,
                         String account, String apiKey, String refreshToken,
                         String email, String password,
                         String cheapStart, String cheapEnd,
                         double cheapCents, double normalCents) {
        this.cache = context.getSharedPreferences("octopus_history_cache", Context.MODE_PRIVATE);
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

        List<String> availableAccounts = discoverAccounts(auth.token);
        String resolvedAccount = resolveAccount(account, availableAccounts, s);
        if (resolvedAccount.isEmpty()) {
            throw new IllegalStateException("Keine Octopus Kundennummer im eingeloggten Konto gefunden");
        }
        s.accountNumber = resolvedAccount;

        AccountContext context = new AccountContext();
        try {
            context = loadTariff(resolvedAccount, auth.token, s);
        } catch (Exception tariffError) {
            s.note = "Tarif konnte nicht automatisch gelesen werden: "
                    + cut(tariffError.getMessage());
        }

        if (context.propertyId.isEmpty() || context.maloNumber.isEmpty()) {
            appendNote(s,
                    "Strom-Zählpunkt konnte noch nicht aus dem Octopus-Konto gelesen werden. "
                            + "Das bedeutet nicht automatisch, dass kein Smart Meter vorhanden ist.");
            return s;
        }

        try {
            loadRecentMeasurements(context, auth.token, s);
        } catch (Exception recentError) {
            appendNote(s, "Jüngste Smart-Meter-Werte konnten nicht geladen werden: "
                    + cut(recentError.getMessage()));
        }

        try {
            loadYearHistory(context, auth.token, s);
        } catch (Exception historyError) {
            appendNote(s, "Jahresstatistik konnte nicht aktualisiert werden: "
                    + cut(historyError.getMessage()));
        }

        if (s.intervals.isEmpty() && !s.dailyHistory.isEmpty()) {
            applyRecentTotalsFromHistory(s);
        }

        double cheapRate = effectiveCheapRate(s);
        double normalRate = effectiveNormalRate(s);
        s.estimatedCostEuro =
                (s.cheapKwh * cheapRate + s.normalKwh * normalRate) / 100.0;

        if (!s.smartMeterDataAvailable) {
            appendNote(s,
                    "Login OK, aber Octopus hat aktuell keine verwertbaren Smart-Meter-Messwerte geliefert.");
        } else {
            appendNote(s,
                    "Smart-Meter-Daten von Octopus geladen; Messwerte können zeitverzögert eintreffen.");
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
        if (token.isEmpty()) {
            throw new IllegalStateException("Octopus hat keinen Token geliefert");
        }

        Auth a = new Auth();
        a.token = token;
        a.refreshToken = obj.optString("refreshToken", "");
        return a;
    }

    private List<String> discoverAccounts(String token) throws Exception {
        String query = "query { viewer { accounts { number } } }";
        JSONObject root = post(query, new JSONObject(), token);
        String err = firstError(root);
        if (err != null) throw new IllegalStateException("Kontosuche: " + err);

        List<String> out = new ArrayList<>();
        JSONObject data = root.optJSONObject("data");
        JSONObject viewer = data == null ? null : data.optJSONObject("viewer");
        JSONArray accounts = viewer == null ? null : viewer.optJSONArray("accounts");
        if (accounts == null) return out;

        for (int i = 0; i < accounts.length(); i++) {
            JSONObject a = accounts.optJSONObject(i);
            if (a == null) continue;
            String number = safe(a.optString("number", ""));
            if (!number.isEmpty() && !out.contains(number)) out.add(number);
        }
        return out;
    }

    private String resolveAccount(String configured, List<String> available, Summary s) {
        String wanted = safe(configured);
        if (available == null || available.isEmpty()) return wanted;

        for (String candidate : available) {
            if (candidate.equalsIgnoreCase(wanted)) return candidate;
        }

        if (available.size() == 1) {
            String found = available.get(0);
            if (!wanted.isEmpty()) {
                appendNote(s,
                        "Die hinterlegte Kundennummer passte nicht zum Login; "
                                + "PV Compact verwendet automatisch das von Octopus gemeldete Konto.");
            } else {
                appendNote(s, "Octopus-Konto automatisch erkannt.");
            }
            return found;
        }

        if (wanted.isEmpty()) {
            throw new IllegalStateException(
                    "Mehrere Octopus-Konten gefunden. Bitte die passende Kundennummer auswählen.");
        }

        throw new IllegalStateException(
                "Die hinterlegte Kundennummer gehört nicht zu diesem Octopus-Login.");
    }

    private AccountContext loadTariff(String accountNumber, String token, Summary s)
            throws Exception {
        String query =
                "query GetPvCompactTariff($accountNumber: String!) {"
              + " account(accountNumber: $accountNumber) {"
              + "  allProperties {"
              + "   id"
              + "   electricityMalos {"
              + "    maloNumber"
              + "    meters { id number meloNumber shouldReceiveSmartMeterData }"
              + "    agreements {"
              + "     isActive isRevoked isTerminated validFrom validTo"
              + "     product { code description fullName isTimeOfUse }"
              + "     unitRateGrossRateInformation { grossRate }"
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

        AccountContext context = new AccountContext();
        JSONObject data = root.optJSONObject("data");
        JSONObject accountObj = data == null ? null : data.optJSONObject("account");

        String err = firstError(root);
        if (accountObj == null) {
            if (err != null) throw new IllegalStateException("Tarifabfrage: " + err);
            return context;
        }
        if (err != null) {
            appendNote(s,
                    "Octopus hat bei einzelnen Tarif-Feldern eingeschränkten Zugriff gemeldet; "
                            + "verfügbare Kontodaten werden trotzdem verwendet.");
        }
        JSONArray properties =
                accountObj == null ? null : accountObj.optJSONArray("allProperties");
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
                JSONArray meters = malo.optJSONArray("meters");
                if (meters != null) {
                    for (int mi = 0; mi < meters.length(); mi++) {
                        JSONObject meter = meters.optJSONObject(mi);
                        if (meter != null && meter.optBoolean("shouldReceiveSmartMeterData", false)) {
                            context.smartMeterExpected = true;
                            break;
                        }
                    }
                }

                if (context.propertyId.isEmpty()) {
                    context.propertyId = prop.optString("id", "");
                    context.maloNumber = malo.optString("maloNumber", "");
                }

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

        s.smartMeterExpected = context.smartMeterExpected;
        if (selectedAgreement == null) return context;

        JSONObject product = selectedAgreement.optJSONObject("product");
        if (product != null) {
            s.productCode = product.optString("code", "");
            s.productName = product.optString("fullName", "");
            if (s.productName.isEmpty()) {
                s.productName = product.optString("description", "");
            }
            s.timeOfUse = product.optBoolean("isTimeOfUse", false);
        }

        JSONObject ratesInfo = selectedAgreement.optJSONObject("unitRateInformation");
        if (ratesInfo == null) {
            JSONObject gross = selectedAgreement.optJSONObject("unitRateGrossRateInformation");
            double rate = gross == null ? Double.NaN : gross.optDouble("grossRate", Double.NaN);
            if (Double.isFinite(rate)) {
                s.normalCents = rate;
                s.tariffFromApi = true;
            }
            return context;
        }

        String type = ratesInfo.optString("__typename", "");
        if ("SimpleProductUnitRateInformation".equals(type)) {
            double rate =
                    ratesInfo.optDouble("latestGrossUnitRateCentsPerKwh", Double.NaN);
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

            double rate =
                    rateObj.optDouble("latestGrossUnitRateCentsPerKwh", Double.NaN);
            if (!Double.isFinite(rate)) continue;

            if (rate < min) {
                min = rate;
                cheapest = rateObj;
            }
            if (rate > max) max = rate;
        }

        if (Double.isFinite(min)) s.cheapCents = min;
        if (Double.isFinite(max)) s.normalCents = max;
        if (Double.isFinite(min) || Double.isFinite(max)) {
            s.tariffFromApi = true;
        }

        if (cheapest != null) {
            JSONArray rules = cheapest.optJSONArray("timeslotActivationRules");
            if (rules != null && rules.length() > 0) {
                JSONObject rule = rules.optJSONObject(0);
                if (rule != null) {
                    s.cheapStart =
                            normalizeTime(rule.optString("activeFromTime", ""));
                    s.cheapEnd =
                            normalizeTime(rule.optString("activeToTime", ""));
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
            if (!a.optBoolean("isRevoked", false)
                    && !a.optBoolean("isTerminated", false)
                    && fallback == null) {
                fallback = a;
            }
        }
        return fallback;
    }

    private void loadRecentMeasurements(AccountContext context, String token, Summary s)
            throws Exception {
        LocalDate today = LocalDate.now(BERLIN);
        ZonedDateTime start = today.minusDays(3).atStartOfDay(BERLIN);
        ZonedDateTime end = today.plusDays(1).atStartOfDay(BERLIN);

        List<Interval> values = fetchIntervals(
                context,
                token,
                start,
                end,
                "HOUR_INTERVAL",
                100,
                10,
                s);
        String source = "HOUR_INTERVAL";

        if (values.isEmpty()) {
            values = fetchIntervals(
                    context,
                    token,
                    start,
                    end,
                    "RAW_INTERVAL",
                    100,
                    10,
                    s);
            source = "RAW_INTERVAL";
        }

        s.recentIntervalCount = values.size();
        if (!values.isEmpty()) {
            s.smartMeterDataAvailable = true;
            s.measurementSource = source;
        }

        Map<String, Double> daily = new LinkedHashMap<>();
        for (Interval in : values) {
            s.intervals.add(in);
            s.totalKwh += in.kwh;
            if (in.cheap) s.cheapKwh += in.kwh;
            else s.normalKwh += in.kwh;

            ZonedDateTime zdt = parseDateTime(in.readAt);
            if (zdt != null) {
                String date = zdt.toLocalDate().toString();
                daily.put(date, daily.getOrDefault(date, 0.0) + in.kwh);
                if (s.latestReadingAt.isEmpty()) {
                    s.latestReadingAt = in.readAt;
                } else {
                    ZonedDateTime previous = parseDateTime(s.latestReadingAt);
                    if (previous == null || zdt.isAfter(previous)) s.latestReadingAt = in.readAt;
                }
            }
        }

        if (!daily.isEmpty()) {
            s.latestDate =
                    new ArrayList<>(daily.keySet()).get(daily.size() - 1);
        }
    }

    private void loadYearHistory(AccountContext context, String token, Summary s)
            throws Exception {
        LocalDate today = LocalDate.now(BERLIN);
        int currentYear = today.getYear();

        for (int year = currentYear - 1; year <= currentYear; year++) {
            loadHistoryYear(context, token, s, year, today);
        }
        s.dailyHistory.sort((a, b) -> a.date.compareTo(b.date));
        s.historyDayCount = s.dailyHistory.size();
        if (!s.dailyHistory.isEmpty()) {
            s.smartMeterDataAvailable = true;
            DailyUsage last = s.dailyHistory.get(s.dailyHistory.size() - 1);
            if (s.latestDate == null || s.latestDate.isEmpty()
                    || last.date.compareTo(s.latestDate) > 0) {
                s.latestDate = last.date;
            }
            if (s.measurementSource.isEmpty()) s.measurementSource = "HOUR_INTERVAL history";
        }
    }

    private void applyRecentTotalsFromHistory(Summary s) {
        LocalDate today = LocalDate.now(BERLIN);
        LocalDate from = today.minusDays(3);
        s.totalKwh = 0.0;
        s.cheapKwh = 0.0;
        s.normalKwh = 0.0;

        for (DailyUsage d : s.dailyHistory) {
            LocalDate date;
            try { date = LocalDate.parse(d.date); }
            catch (Exception ignored) { continue; }
            if (date.isBefore(from) || date.isAfter(today)) continue;
            s.totalKwh += d.totalKwh;
            s.cheapKwh += d.cheapKwh;
            s.normalKwh += d.normalKwh;
        }
        s.measurementSource = "HOUR_INTERVAL history";
    }

    private void loadHistoryYear(AccountContext context, String token, Summary s,
                                 int year, LocalDate today) throws Exception {
        String key = historyKey(s.accountNumber, year, s);
        List<DailyUsage> cachedFresh = readHistoryCache(key, HISTORY_TTL_MS);
        if (cachedFresh != null) {
            s.dailyHistory.addAll(cachedFresh);
            return;
        }

        List<DailyUsage> stale = readHistoryCache(key, Long.MAX_VALUE);
        try {
            ZonedDateTime start =
                    LocalDate.of(year, 1, 1).atStartOfDay(BERLIN);
            ZonedDateTime end = year == today.getYear()
                    ? today.plusDays(1).atStartOfDay(BERLIN)
                    : LocalDate.of(year + 1, 1, 1).atStartOfDay(BERLIN);

            List<Interval> hourly = fetchIntervals(
                    context,
                    token,
                    start,
                    end,
                    "HOUR_INTERVAL",
                    500,
                    40,
                    s);

            Map<String, DailyUsage> days = new LinkedHashMap<>();
            for (Interval in : hourly) {
                ZonedDateTime zdt = parseDateTime(in.readAt);
                if (zdt == null) continue;

                String date = zdt.toLocalDate().toString();
                DailyUsage day = days.get(date);
                if (day == null) {
                    day = new DailyUsage();
                    day.date = date;
                    days.put(date, day);
                }

                day.totalKwh += in.kwh;
                if (in.cheap) day.cheapKwh += in.kwh;
                else day.normalKwh += in.kwh;
            }

            List<DailyUsage> yearHistory = new ArrayList<>(days.values());
            s.dailyHistory.addAll(yearHistory);
            writeHistoryCache(key, yearHistory);
        } catch (Exception e) {
            if (stale != null && !stale.isEmpty()) {
                s.dailyHistory.addAll(stale);
                appendNote(s,
                        "Historie " + year + " aus lokalem Cache; Octopus-Aktualisierung derzeit nicht möglich.");
            } else if (year == today.getYear()) {
                throw e;
            } else {
                appendNote(s, "Keine Octopus-Historie für " + year + " verfügbar.");
            }
        }
    }

    private List<Interval> fetchIntervals(
            AccountContext context,
            String token,
            ZonedDateTime start,
            ZonedDateTime end,
            String frequency,
            int pageSize,
            int maxPages,
            Summary s) throws Exception {

        String query =
                "query GetPvCompactMeasurements("
              + " $propertyId: ID!, $first: Int!, $after: String,"
              + " $utilityFilters: [UtilityFiltersInput!]!,"
              + " $startAt: DateTime!, $endAt: DateTime!, $timezone: String!) {"
              + " property(id: $propertyId) {"
              + "  measurements(first: $first, after: $after,"
              + "   utilityFilters: $utilityFilters, startAt: $startAt,"
              + "   endAt: $endAt, timezone: $timezone) {"
              + "   pageInfo { hasNextPage endCursor }"
              + "   edges { node {"
              + "    __typename source value unit"
              + "    ... on IntervalMeasurementType { startAt endAt durationInSeconds }"
              + "   } }"
              + "  }"
              + " }"
              + "}";

        JSONObject electricityFilters = new JSONObject()
                .put("marketSupplyPointId", context.maloNumber)
                .put("readingFrequencyType", frequency);
        JSONArray utilityFilters = new JSONArray()
                .put(new JSONObject().put("electricityFilters", electricityFilters));

        List<Interval> out = new ArrayList<>();
        String cursor = null;

        for (int page = 0; page < maxPages; page++) {
            JSONObject vars = new JSONObject()
                    .put("propertyId", context.propertyId)
                    .put("first", pageSize)
                    .put("utilityFilters", utilityFilters)
                    .put("startAt", start.toOffsetDateTime().toString())
                    .put("endAt", end.toOffsetDateTime().toString())
                    .put("timezone", "Europe/Berlin");

            if (cursor == null) vars.put("after", JSONObject.NULL);
            else vars.put("after", cursor);

            JSONObject root = post(query, vars, token);
            String graphError = firstError(root);
            if (graphError != null) {
                throw new IllegalStateException("Messwerte: " + graphError);
            }

            JSONObject data = root.optJSONObject("data");
            JSONObject property =
                    data == null ? null : data.optJSONObject("property");
            JSONObject measurements =
                    property == null ? null : property.optJSONObject("measurements");
            JSONArray edges =
                    measurements == null ? null : measurements.optJSONArray("edges");

            if (edges != null) {
                for (int i = 0; i < edges.length(); i++) {
                    JSONObject edge = edges.optJSONObject(i);
                    JSONObject node =
                            edge == null ? null : edge.optJSONObject("node");
                    if (node == null) continue;

                    String readAt = node.optString("startAt", "");
                    double value = node.optDouble("value", Double.NaN);
                    if (readAt.isEmpty() || !Double.isFinite(value) || value < 0) {
                        continue;
                    }

                    String unit = node.optString("unit", "").toLowerCase();
                    double kwh =
                            (unit.contains("wh") && !unit.contains("kwh"))
                                    ? value / 1000.0
                                    : value;

                    ZonedDateTime zdt = parseDateTime(readAt);
                    if (zdt == null) continue;

                    Interval in = new Interval();
                    in.readAt = readAt;
                    in.kwh = kwh;
                    in.cheap = isCheap(zdt.toLocalTime(), s);
                    out.add(in);
                }
            }

            JSONObject pageInfo =
                    measurements == null ? null : measurements.optJSONObject("pageInfo");
            boolean hasNext =
                    pageInfo != null && pageInfo.optBoolean("hasNextPage", false);
            String next =
                    pageInfo == null ? "" : pageInfo.optString("endCursor", "");

            if (!hasNext) break;
            if (next.isEmpty() || next.equals(cursor)) {
                throw new IllegalStateException(
                        "Messwert-Paginierung lieferte keinen neuen Cursor");
            }
            cursor = next;

            if (page == maxPages - 1) {
                throw new IllegalStateException(
                        "Messwerthistorie überschreitet das Sicherheitslimit");
            }
        }

        return out;
    }

    private String historyKey(String accountNumber, int year, Summary s) {
        String tariffKey =
                currentCheapStart(s) + "_" + currentCheapEnd(s) + "_"
                        + effectiveCheapRate(s) + "_" + effectiveNormalRate(s);
        return Integer.toUnsignedString(
                (accountNumber + "_" + year + "_" + tariffKey).hashCode());
    }

    private List<DailyUsage> readHistoryCache(String key, long maxAgeMs) {
        long savedAt = cache.getLong(key + "_time", 0L);
        String json = cache.getString(key + "_json", "");
        if (json == null || json.isEmpty() || savedAt <= 0L) return null;
        if (maxAgeMs != Long.MAX_VALUE
                && System.currentTimeMillis() - savedAt > maxAgeMs) {
            return null;
        }

        try {
            JSONArray a = new JSONArray(json);
            List<DailyUsage> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;

                DailyUsage d = new DailyUsage();
                d.date = o.optString("date", "");
                d.totalKwh = o.optDouble("total", 0.0);
                d.cheapKwh = o.optDouble("cheap", 0.0);
                d.normalKwh = o.optDouble("normal", 0.0);
                if (!d.date.isEmpty()) out.add(d);
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeHistoryCache(String key, List<DailyUsage> history) {
        try {
            JSONArray a = new JSONArray();
            for (DailyUsage d : history) {
                a.put(new JSONObject()
                        .put("date", d.date)
                        .put("total", d.totalKwh)
                        .put("cheap", d.cheapKwh)
                        .put("normal", d.normalKwh));
            }
            cache.edit()
                    .putLong(key + "_time", System.currentTimeMillis())
                    .putString(key + "_json", a.toString())
                    .apply();
        } catch (Exception ignored) {}
    }

    private JSONObject post(String query, JSONObject vars, String token)
            throws Exception {
        JSONObject payload =
                new JSONObject().put("query", query).put("variables", vars);
        Map<String, String> headers = new HashMap<>();
        if (token != null && !token.isEmpty()) {
            headers.put("Authorization", token);
        }

        Net.Response r =
                Net.postJson(ENDPOINT, payload.toString(), headers);
        if (r.code < 200 || r.code >= 300) {
            throw new IllegalStateException(
                    "Octopus HTTP " + r.code + ": " + cut(r.body));
        }
        return new JSONObject(r.body);
    }

    private String firstError(JSONObject root) {
        JSONArray errors = root.optJSONArray("errors");
        if (errors == null || errors.length() == 0) return null;
        JSONObject e = errors.optJSONObject(0);
        return e == null
                ? "GraphQL-Fehler"
                : e.optString("message", "GraphQL-Fehler");
    }

    private boolean isCheap(LocalTime t, Summary s) {
        LocalTime start =
                parseTime(currentCheapStart(s), fallbackCheapStart);
        LocalTime end =
                parseTime(currentCheapEnd(s), fallbackCheapEnd);

        if (start.equals(end)) return false;
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        return !t.isBefore(start) || t.isBefore(end);
    }

    private String currentCheapStart(Summary s) {
        return s.cheapStart == null || s.cheapStart.isEmpty()
                ? fallbackCheapStart.toString()
                : s.cheapStart;
    }

    private String currentCheapEnd(Summary s) {
        return s.cheapEnd == null || s.cheapEnd.isEmpty()
                ? fallbackCheapEnd.toString()
                : s.cheapEnd;
    }

    private double effectiveCheapRate(Summary s) {
        return Double.isFinite(s.cheapCents)
                ? s.cheapCents
                : fallbackCheapCents;
    }

    private double effectiveNormalRate(Summary s) {
        return Double.isFinite(s.normalCents)
                ? s.normalCents
                : fallbackNormalCents;
    }

    private ZonedDateTime parseDateTime(String value) {
        try {
            return Instant.parse(value).atZone(BERLIN);
        } catch (Exception ignored) {}
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(BERLIN);
        } catch (Exception ignored) {}
        try {
            return ZonedDateTime.parse(value).withZoneSameInstant(BERLIN);
        } catch (Exception ignored) {}
        return null;
    }

    private String normalizeTime(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            String t = LocalTime.parse(value.trim()).toString();
            return t.length() >= 5 ? t.substring(0, 5) : t;
        } catch (Exception ignored) {}
        if (value.length() >= 5) return value.substring(0, 5);
        return value;
    }

    private LocalTime parseTime(String s, LocalTime def) {
        try {
            return LocalTime.parse(s);
        } catch (Exception e) {
            return def;
        }
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
