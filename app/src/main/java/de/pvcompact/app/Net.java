package de.pvcompact.app;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

public final class Net {
    public static final class Response {
        public final int code;
        public final String body;
        public final Map<String, java.util.List<String>> headers;

        Response(int code, String body, Map<String, java.util.List<String>> headers) {
            this.code = code;
            this.body = body;
            this.headers = headers == null ? Collections.emptyMap() : headers;
        }

        public String header(String name) {
            for (Map.Entry<String, java.util.List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                        && e.getValue() != null && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private Net() {}

    public static Response get(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("User-Agent", "PVCompactClean/1.0 Android");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
            c.setRequestProperty(e.getKey(), e.getValue());
        }
        return read(c);
    }

    public static Response postJson(String url, String json, Map<String, String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "PVCompactClean/1.0 Android");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
            c.setRequestProperty(e.getKey(), e.getValue());
        }
        try (OutputStreamWriter w = new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write(json);
        }
        return read(c);
    }

    private static Response read(HttpURLConnection c) throws Exception {
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String body = "";
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    StringBuilder b = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) b.append(line).append('\n');
                    body = b.toString().trim();
                }
            }
            return new Response(code, body, c.getHeaderFields());
        } finally {
            c.disconnect();
        }
    }
}
