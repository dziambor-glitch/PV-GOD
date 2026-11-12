# PV Compact: vollständige WattWächter-Tageswerte unterwegs

Ein dauerhaft eingeschalteter Raspberry Pi ruft die **lokale** WattWächter-REST-API
(`192.168.178.26`) ab und sendet alle drei Minuten eine bestätigte MQTT-
Nachricht über TLS an den **bestehenden** privaten EMQX-Broker. Die neue
Android-Version **PV Compact 1.8.6** abonniert zusätzlich
`<DEIN_WATT_TOPIC_PREFIX>/pvcompact/history/today`, liest den retained
Tagesstand beim Öffnen sofort ein und ergänzt Netzbezug, Tag-/Nachtstrom,
geschätzte Stromkosten und die Tageskurve. Keine Portweiterleitung.

Die bisherigen WattWächter-Live-MQTT-Nachrichten bleiben unverändert.

## Voraussetzungen

- WattWächter lokal via `http://192.168.178.26` vom Raspberry erreichbar.
- Raspberry Pi mit **Python 3.9+**, Internetzugang und `systemd`.
- Derselbe EMQX-TLS-Broker (normalerweise Port `8883`) und derselbe
  MQTT-Topic-Prefix wie in PV Compact. Der EMQX-Benutzer muss auf
  `<prefix>/pvcompact/history/today` **publish** dürfen und die App dort
  **subscribe** dürfen.
- Die neue APK benötigt dieselben MQTT-Anmeldedaten, speichert sie auf
  Android verschlüsselt. Kein Passwort wird im GitHub-Repository gespeichert.

## Einrichtung am Raspberry Pi (per SSH)

Befehle zeilenweise ausführen. Die Datei wird aus dem Feature-Branch geladen;
nach dem Zusammenführen kann `BRANCH` auf `main` umgestellt werden.

```bash
BRANCH=feature/pvcompact-cloud-history-v186
BASE="https://raw.githubusercontent.com/dziambor-glitch/PV-GOD/$BRANCH/raspberry"

sudo mkdir -p /opt/pvcompact-bridge
sudo curl -fL "$BASE/wattwaechter_cloud_bridge.py" -o /opt/pvcompact-bridge/wattwaechter_cloud_bridge.py
sudo python3 -m venv /opt/pvcompact-bridge/.venv
sudo /opt/pvcompact-bridge/.venv/bin/python -m pip install --upgrade 'paho-mqtt>=2,<3'

sudo curl -fL "$BASE/pvcompact-bridge.env.example" -o /etc/pvcompact-bridge.env
sudo chmod 600 /etc/pvcompact-bridge.env
sudo nano /etc/pvcompact-bridge.env
```

In `/etc/pvcompact-bridge.env` die **bereits verwendeten EMQX-Daten** aus
PV Compact eintragen:

```ini
WATT_LOCAL_HOST=192.168.178.26
WATT_READ_TOKEN=
MQTT_HOST=dein-emqx-hostname
MQTT_PORT=8883
MQTT_TOPIC_PREFIX=dein-bisheriger-wattwaechter-topic-prefix
MQTT_USERNAME=dein-emqx-benutzer
MQTT_PASSWORD=dein-emqx-passwort
```

Bei Sonderzeichen (insbesondere Leerzeichen oder `#`) Werte nach
`systemd EnvironmentFile`-Syntax in Anführungszeichen setzen.
**Die Zugangsdaten niemals hier in den Chat oder auf GitHub kopieren.**

Wenn die WattWächter-API Authentifizierung verlangt, einen **READ**-Token
unter `WATT_READ_TOKEN` eintragen. Kein WRITE-Token erforderlich.

Zuerst eine einmalige Synchronisierung testen:

```bash
sudo systemd-run --wait --collect --unit=pvcompact-bridge-test \
  -p EnvironmentFile=/etc/pvcompact-bridge.env \
  -p WorkingDirectory=/opt/pvcompact-bridge \
  /opt/pvcompact-bridge/.venv/bin/python /opt/pvcompact-bridge/wattwaechter_cloud_bridge.py

sudo journalctl -u pvcompact-bridge-test -n 30 --no-pager
```

Anschließend den regelmäßigen Dienst einrichten:

```bash
sudo curl -fL "$BASE/pvcompact-bridge.service" -o /etc/systemd/system/pvcompact-bridge.service
sudo curl -fL "$BASE/pvcompact-bridge.timer" -o /etc/systemd/system/pvcompact-bridge.timer
sudo systemctl daemon-reload
sudo systemctl enable --now pvcompact-bridge.timer
sudo systemctl start pvcompact-bridge.service
sudo systemctl status pvcompact-bridge.timer --no-pager
sudo journalctl -u pvcompact-bridge.service -n 30 --no-pager
```

Bei erfolgreicher Ausführung erscheint eine Meldung wie
`Tageshistorie veröffentlicht: 2026-11-12, ... kWh, ... Kurvenpunkte`.

## In der Android-App

1. **PV Compact Cloud 1.8.6** installieren. Diese Preview-App wird getrennt
   von früheren Testversionen installiert; Einstellungen müssen einmalig
   übernommen werden.
2. Unter Einstellungen die bisherigen EMQX-Zugangsdaten und den **gleichen
   Topic-Prefix** wie beim Raspberry eintragen; MQTT aktivieren.
3. Lokaler WattWächter-Host: `192.168.178.26`. Im Heimnetz funktioniert
   weiterhin die direkte API; unterwegs kommen die Werte aus EMQX Cloud.
4. **WLAN testweise ausschalten** und PV Compact per Mobilfunk neu öffnen.
   Unter der heutigen Netzbilanz sollte
   `WattWächter via EMQX Cloud · 15-Min-Verlauf` erscheinen.

## Datenschutz und Grenzen

- In EMQX liegt **nur** der jüngste veröffentlichte Tagesstand als retained
  Nachricht, inklusive 15-Minuten-Kurve und kWh-Summen.
- TLS verschlüsselt den Transport; sichere Broker-Zugangsdaten und
  Publish-/Subscribe-ACLs sind Voraussetzung. Brokerbetreiber kann technisch
  Daten speichern; EMQX-Kontolimits und Datenschutz beachten.
- Ist der Raspberry oder das Heimnetz offline, bleibt der letzte Stand des
  Tages erhalten. Die App zeigt **den Zeitstempel**, nicht eine erfundene
  Live-Tagesbilanz.
- Nach Mitternacht liefert die Bridge erst neue Werte, sobald der
  WattWächter Messungen für den neuen Tag bereitstellt. Alte retained
  Nachrichten werden von der Android-App als **nicht heute** abgewiesen.
- Für eine dauerhafte, verlässliche Verbindung empfiehlt sich eine
  **DHCP-Reservierung** für `192.168.178.26` in Plume.
- Keine Portfreigabe und keine Änderung der WattWächter-Cloud-Konfiguration.
