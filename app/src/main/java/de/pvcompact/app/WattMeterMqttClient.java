package de.pvcompact.app;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Foreground-only, read-only MQTT subscriber for WattWaechter Plus.
 * Uses the externally configured TLS broker, not the WattWaechter cloud API.
 * WattWaechter topics: {prefix}/16.7.0, /1.8.0, /2.8.0, /status.
 */
public final class WattMeterMqttClient {
    public interface Listener {
        void onSnapshot(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final String state;
        public final Double powerW;
        public final Double importKwh;
        public final Double exportKwh;
        public final long powerReceivedAtMillis;
        public final long livePowerReceivedAtMillis;
        public final long freshImportReceivedAtMillis;
        public final long freshExportReceivedAtMillis;

        private Snapshot(String state, Double powerW, Double importKwh, Double exportKwh,
                         long powerReceivedAtMillis, long livePowerReceivedAtMillis,
                         long freshImportReceivedAtMillis, long freshExportReceivedAtMillis) {
            this.state = state;
            this.powerW = powerW;
            this.importKwh = importKwh;
            this.exportKwh = exportKwh;
            this.powerReceivedAtMillis = powerReceivedAtMillis;
            this.livePowerReceivedAtMillis = livePowerReceivedAtMillis;
            this.freshImportReceivedAtMillis = freshImportReceivedAtMillis;
            this.freshExportReceivedAtMillis = freshExportReceivedAtMillis;
        }
    }

    private final Listener listener;
    private final String topicPrefix;
    private MqttAsyncClient mqtt;
    private String state = "Verbindung wird aufgebaut";
    private Double powerW, importKwh, exportKwh;
    private long powerReceivedAtMillis, livePowerReceivedAtMillis;
    private long freshImportReceivedAtMillis, freshExportReceivedAtMillis;
    private volatile boolean closed;

    public WattMeterMqttClient(String host, int port, String username,
                               String password, String topicPrefix, Listener listener)
            throws Exception {
        if (host == null || !host.matches("[A-Za-z0-9.-]+") || host.isEmpty()) {
            throw new IllegalArgumentException("Bitte einen gültigen Broker-Hostnamen ohne Protokoll eingeben.");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Ungültiger MQTT-Port.");
        if (topicPrefix == null || topicPrefix.isEmpty() || topicPrefix.contains("#")
                || topicPrefix.contains("+") || topicPrefix.startsWith("/")
                || topicPrefix.endsWith("/")) {
            throw new IllegalArgumentException("Ungültiger MQTT-Topic-Prefix.");
        }
        this.listener = listener;
        this.topicPrefix = topicPrefix;
        // Always use verified TLS; never silently downgrade credentials to plain MQTT.
        mqtt = new MqttAsyncClient("ssl://" + host + ":" + port,
                MqttAsyncClient.generateClientId(), new MemoryPersistence());
        mqtt.setCallback(new MqttCallbackExtended() {
            @Override public void connectComplete(boolean reconnect, String serverURI) {
                synchronized (WattMeterMqttClient.this) {
                    if (closed) return;
                    state = "Verbunden · abonniere Messwerte";
                }
                report();
                try {
                    mqtt.subscribe(WattMeterMqttClient.this.topicPrefix + "/#", 0,
                            null, new IMqttActionListener() {
                                @Override public void onSuccess(IMqttToken token) {
                                    synchronized (WattMeterMqttClient.this) {
                                        if (closed) return;
                                        state = "Verbunden";
                                    }
                                    report();
                                }

                                @Override public void onFailure(IMqttToken token, Throwable error) {
                                    setState("Abo fehlgeschlagen: " + shortError(error));
                                }
                            });
                } catch (Exception e) {
                    setState("Abo fehlgeschlagen: " + shortError(e));
                }
            }

            @Override public void connectionLost(Throwable cause) {
                if (!closed) setState("Verbindung unterbrochen · erneuter Versuch läuft");
            }

            @Override public void messageArrived(String topic, MqttMessage message) {
                if (closed || !topic.startsWith(WattMeterMqttClient.this.topicPrefix + "/")) return;
                String code = topic.substring(WattMeterMqttClient.this.topicPrefix.length() + 1);
                if (!code.equals("16.7.0") && !code.equals("1.8.0") && !code.equals("2.8.0")) return;

                try {
                    String raw = new String(message.getPayload(), StandardCharsets.UTF_8).trim();
                    double value = Double.parseDouble(raw);
                    if (!Double.isFinite(value)) return;
                    synchronized (WattMeterMqttClient.this) {
                        if (code.equals("16.7.0")) {
                            powerW = value;
                            powerReceivedAtMillis = System.currentTimeMillis();
                            // Retained values may be arbitrarily old: do not label them as live.
                            if (!message.isRetained()) livePowerReceivedAtMillis = powerReceivedAtMillis;
                        } else if (code.equals("1.8.0") && value >= 0) {
                            importKwh = value;
                            if (!message.isRetained()) freshImportReceivedAtMillis = System.currentTimeMillis();
                        } else if (code.equals("2.8.0") && value >= 0) {
                            exportKwh = value;
                            if (!message.isRetained()) freshExportReceivedAtMillis = System.currentTimeMillis();
                        }
                    }
                    report();
                } catch (NumberFormatException ignored) {
                    // Ignore unparseable values; never substitute zero for missing telemetry.
                }
            }

            @Override public void deliveryComplete(IMqttDeliveryToken token) {
                // Read-only subscriber: no publishes.
            }
        });

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        if (username != null && !username.trim().isEmpty()) options.setUserName(username.trim());
        if (password != null && !password.isEmpty()) options.setPassword(password.toCharArray());
        mqtt.connect(options, null, new IMqttActionListener() {
            @Override public void onSuccess(IMqttToken token) {
                // connectComplete subscribes on initial connection and reconnect.
            }
            @Override public void onFailure(IMqttToken token, Throwable error) {
                setState("Verbindung fehlgeschlagen: " + shortError(error));
            }
        });
    }

    private static String shortError(Throwable e) {
        String msg = e == null ? "Unbekannter Fehler" : e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return e == null ? "Fehler" : e.getClass().getSimpleName();
        return msg.length() <= 110 ? msg : msg.substring(0, 110);
    }

    private synchronized void setState(String next) {
        if (closed) return;
        state = next;
        report();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(state, powerW, importKwh, exportKwh,
                powerReceivedAtMillis, livePowerReceivedAtMillis,
                freshImportReceivedAtMillis, freshExportReceivedAtMillis);
    }

    private void report() {
        if (!closed && listener != null) listener.onSnapshot(snapshot());
    }

    public void close() {
        closed = true;
        if (mqtt != null) {
            try { mqtt.disconnectForcibly(250, 250, false); } catch (Exception ignored) {}
            try { mqtt.close(); } catch (Exception ignored) {}
            mqtt = null;
        }
    }
}
