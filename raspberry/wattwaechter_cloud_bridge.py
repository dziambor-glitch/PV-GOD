#!/usr/bin/env python3
"""PV Compact WattWächter REST -> EMQX MQTT bridge.

A single execution publishes a validated, retained daily history snapshot.
Run every three minutes using the included systemd timer.  No public ports
are opened on the Raspberry Pi or WattWächter.
"""
from __future__ import annotations

import json
import logging
import os
import re
import ssl
import sys
import threading
import time
from datetime import datetime
from ipaddress import ip_address
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

try:
    import paho.mqtt.client as mqtt
except ImportError as exc:
    raise SystemExit("paho-mqtt fehlt: python -m pip install 'paho-mqtt>=2,<3'") from exc

ZONE = ZoneInfo("Europe/Berlin")
LOG = logging.getLogger("pvcompact-history-bridge")
MAX_JSON = 96_000
LOCAL_TIMEOUT = 12
BROKER_TIMEOUT = 15


def required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ValueError(f"Umgebungsvariable {name} fehlt")
    return value


def parse_local_host() -> str:
    host = os.environ.get("WATT_LOCAL_HOST", "192.168.178.26").strip()
    # Local-only address; avoid accidentally publishing measurements from a WAN URL.
    try:
        addr = ip_address(host)
        if addr.version != 4 or not addr.is_private or addr.is_loopback:
            raise ValueError("WATT_LOCAL_HOST muss eine private IPv4-Adresse sein")
    except ValueError as exc:
        if not re.fullmatch(r"[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.local", host):
            raise ValueError("WATT_LOCAL_HOST muss eine private IPv4-Adresse oder *.local sein") from exc
    return host


def number(value: object, maximum: float = 50000.0) -> float | None:
    if type(value) not in (int, float):
        return None
    v = float(value)
    if not (0 <= v <= maximum) or not (v < float("inf")):
        return None
    return v


def fetch_today() -> dict:
    today = datetime.now(ZONE).date().isoformat()
    host = parse_local_host()
    url = f"http://{host}/api/v1/history/highRes?date={today}"
    headers = {"Accept": "application/json"}
    token = os.environ.get("WATT_READ_TOKEN", "").strip()
    if token:
        headers["Authorization"] = "Bearer " + token
    req = Request(url, headers=headers, method="GET")
    with urlopen(req, timeout=LOCAL_TIMEOUT) as resp:
        if resp.status != 200:
            raise ValueError(f"Lokale Historie HTTP {resp.status}")
        raw = resp.read(MAX_JSON + 1)
    if len(raw) > MAX_JSON:
        raise ValueError("Antwort des WattWächters zu groß")
    doc = json.loads(raw)
    if not isinstance(doc, dict) or not str(doc.get("start", today)).startswith(today):
        raise ValueError("WattWächter hat kein Ergebnis für den heutigen Tag geliefert")

    imported = number(doc.get("import_total_kWh"))
    exported = number(doc.get("export_total_kWh", 0))
    if imported is None:
        raise ValueError("import_total_kWh fehlt oder ist ungültig")
    if exported is None:
        exported = 0.0

    items = doc.get("items")
    if not isinstance(items, list) or not items:
        raise ValueError("Noch keine 15-Minuten-Werte für heute vorhanden")
    cleaned = []
    for item in items:
        if not isinstance(item, dict):
            continue
        date = item.get("date")
        timestamp = item.get("timestamp")
        if not ((isinstance(date, str) and date.startswith(today))
                or (isinstance(timestamp, (int, float)) and timestamp > 946684800)):
            continue
        record = {}
        if isinstance(date, str) and len(date) <= 25:
            record["date"] = date
        if type(timestamp) in (int, float) and 946684800 < timestamp < 4102444800:
            record["timestamp"] = int(timestamp)
        for field, maximum in (("import_kW", 500.0), ("power_W", 1000000.0),
                               ("import_total_kWh", 50000.0)):
            value = number(item.get(field), maximum)
            if value is not None:
                record[field] = value
        if "import_kW" in record or "power_W" in record:
            cleaned.append(record)

    if not cleaned:
        raise ValueError("Keine verwertbare Tageskurve empfangen")
    if len(cleaned) > 200:
        raise ValueError("Ungewöhnlich viele 15-Minuten-Werte empfangen")

    result = {
        "bridge_version": 1,
        "start": today,
        "date": today,
        "published_at_ms": int(time.time() * 1000),
        "import_total_kWh": imported,
        "export_total_kWh": exported,
        "items": cleaned,
    }
    return result


def send_snapshot(payload: dict) -> str:
    host = required("MQTT_HOST")
    if not re.fullmatch(r"[A-Za-z0-9.-]+", host):
        raise ValueError("MQTT_HOST muss ein Hostname ohne Protokoll sein")
    port = int(os.environ.get("MQTT_PORT", "8883"))
    if port < 1 or port > 65535:
        raise ValueError("Ungültiger MQTT-Port")
    prefix = required("MQTT_TOPIC_PREFIX").strip("/")
    if any(x in prefix for x in "#+\x00") or len(prefix) > 240:
        raise ValueError("Ungültiger MQTT_TOPIC_PREFIX")
    username = required("MQTT_USERNAME")
    password = required("MQTT_PASSWORD")
    topic = prefix + "/pvcompact/history/today"

    connected = threading.Event()
    errors = []
    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id="pvcompact-rpi-history",
        protocol=mqtt.MQTTv311,
    )
    client.username_pw_set(username, password)
    client.tls_set(cert_reqs=ssl.CERT_REQUIRED)
    client.tls_insecure_set(False)

    def on_connect(_client, _userdata, _flags, reason_code, _properties):
        if reason_code != 0:
            errors.append("EMQX verweigert MQTT-Verbindung: " + str(reason_code))
        connected.set()

    client.on_connect = on_connect
    try:
        client.connect(host, port=port, keepalive=30)
        client.loop_start()
        if not connected.wait(timeout=BROKER_TIMEOUT):
            raise TimeoutError("MQTT-CONNACK nicht empfangen")
        if errors:
            raise RuntimeError(errors[0])
        body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
        if len(body.encode("utf-8")) > MAX_JSON:
            raise ValueError("Cloud-Datenpaket zu groß")
        info = client.publish(topic, body.encode("utf-8"), qos=1, retain=True)
        if info.rc != mqtt.MQTT_ERR_SUCCESS:
            raise RuntimeError("MQTT-Publish fehlgeschlagen: " + str(info.rc))
        info.wait_for_publish(timeout=BROKER_TIMEOUT)
        if not info.is_published():
            raise TimeoutError("EMQX hat den Publish nicht bestätigt")
        return topic
    finally:
        try:
            client.disconnect()
            client.loop_stop()
        except Exception:
            pass


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        payload = fetch_today()
        topic = send_snapshot(payload)
        LOG.info("Tageshistorie veröffentlicht: %s, %.2f kWh, %d Kurvenpunkte, Topic %s",
                 payload["date"], payload["import_total_kWh"], len(payload["items"]), topic)
        return 0
    except (ValueError, OSError, TimeoutError, HTTPError, URLError, RuntimeError,
            json.JSONDecodeError) as exc:
        LOG.error("Synchronisierung fehlgeschlagen: %s", exc)
        return 1


if __name__ == "__main__":
    sys.exit(main())
