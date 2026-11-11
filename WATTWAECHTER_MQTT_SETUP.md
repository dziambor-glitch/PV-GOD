# PV Compact 1.7.9 preview – WattWächter Plus MQTT

This is an opt-in, read-only grid-meter integration. It does not change inverter control,
Octopus authentication, PVOutput, or the official meter.

## Purpose

- **Live**: positive `16.7.0` = net import in watts; negative = incidental export.
- **Today**: graph of actual signed meter power samples recorded by this app; no synthetic zeros.
- **Today kWh**: cumulative `1.8.0` difference between first and last observed register samples **within today**; clearly marked as partial if first sample is not midnight.
- **Octopus**: historical tariff-split graph and most recent sufficiently covered hourly diagram. Delayed or missing days remain missing rather than showing zero.
- **PVOutput**: unchanged, remains the PV production source.

## Secure cloud broker setup (without Raspberry Pi)

1. Create a private **EMQX Cloud Serverless** deployment at https://cloud.emqx.com .
2. In deployment Overview, copy the **full MQTT host name** and note TLS port `8883`. Download the broker's CA certificate if the device requires it.
3. In deployment Access Control / Client Authentication, configure a strong user/password. Do **not** use the public broker `broker.emqx.io`: its topics are publicly readable.
4. In WattWächter settings → MQTT, set the same host, port `8883`, TLS enabled, credentials, and interval e.g. 5 seconds. Upload the CA in the WattWächter's MQTT TLS certificate section where required.
5. Copy the **exact topic prefix** from WattWächter settings. The actual data topics are `{prefix}/16.7.0`, `{prefix}/1.8.0`, and `{prefix}/2.8.0`. Values are ASCII floats, not JSON.
6. In PV Compact → Einstellungen → WattWächter Plus, enable MQTT, enter broker hostname, 8883, exact prefix, and credentials. The password is encrypted locally using Android Keystore. Never commit a password to GitHub.
7. Open Home → Netzbezug & Tagesgrafik ansehen and verify **Netzbezug live**. Watch the MQTT connection state and age of the last new non-retained measurement.

WattWächter's *existing manufacturer cloud* can remain enabled. The broker
above is an independent, privately configured MQTT cloud service, not an API
to the manufacturer's cloud.

Official docs:
- https://docs.wattwaechter.com/strom/plus/integrationen/mqtt/
- https://docs.wattwaechter.com/strom/plus/integrationen/rest-api/
- https://docs.emqx.com/en/cloud/latest/connect_to_deployments/deploy_mqtt_broker.html

## Limitations of this preview

- An MQTT broker is a live message router, **not** a time-series database.
  PV Compact only samples while its Android process remains running. A session
  starting at 14:00 cannot honestly show 00:00–14:00 live history.
- The WattWächter itself retains 15-minute readings for ~30 days, accessible
  locally through `/api/v1/history/highRes?date=YYYY-MM-DD`; a **future** optional
  local sync/backfill feature can cover these gaps when the phone is on the home Wi-Fi.
- Cloud broker availability, CA certificate trust and router Internet access must
  be verified on actual devices; this repository does not provision an EMQX account.
- This is *net import/export at the official meter*, not total house loads.
- The historic Octopus allocation uses existing app tariff calculations and
  may remain two or more days behind live readings; Octopus is not real time.
- 15-minute WattWächter history is not equivalent to second-by-second MQTT traces.
- No inverter writes, charging control, or automatic zero-export changes are made.
