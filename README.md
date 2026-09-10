# [Traccar](https://www.traccar.org)

## Overview

Traccar is an open source GPS tracking system. This repository contains Java-based back-end service. It supports more than 200 GPS protocols and more than 2000 models of GPS tracking devices. Traccar can be used with any major SQL database system. It also provides easy to use [REST API](https://www.traccar.org/traccar-api/).

Other parts of Traccar solution include:

- [Traccar web app](https://github.com/traccar/traccar-web)
- [Traccar Manager app](https://github.com/traccar/traccar-manager)

There is also a set of mobile apps that you can use for tracking mobile devices:

- [Traccar Client app](https://github.com/traccar/traccar-client)

## Features

Some of the available features include:

- Real-time GPS tracking
- Driver behaviour monitoring
- Detailed and summary reports
- Geofencing functionality
- Alarms and notifications
- Account and device management
- Email and SMS support

## Build

Please read [build from source documentation](https://www.traccar.org/build/) on the official website.

## Team

- Anton Tananaev ([anton@traccar.org](mailto:anton@traccar.org))
- Andrey Kunitsyn ([andrey@traccar.org](mailto:andrey@traccar.org))

## DMujeres fork — canal móvil (MQTT + HTTP fallback + jornadas)

Este submódulo es el fork `Dmujeres-Traccar-server` (rama `dev`).
Añade sobre Traccar upstream un canal móvil para la app Android de
colaboradoras, con idempotencia extremo a extremo y jornadas.

### Canal MQTT

- Tópicos (configurables vía `mobile.mqtt.topic` / `mobile.mqtt.ackTopic`):
  - Subida: `dmj/v1/devices/{id}/telemetry` (QoS 1, suscripción del server)
  - ACK: `dmj/v1/devices/{id}/ack` (`accepted | duplicate | rejected | invalid | expired | pending`)
- Consumidor: `mobile/MobileMqttConsumer.java` (MQTT 5, ack manual, cola
  `mobile.mqtt.workerQueue`, límite `mobile.mqtt.maxPayload`, serie por
  dispositivo). Solo publica el ACK de aplicación y confirma el publish
  MQTT después; sin ACK el broker reentrega.

### Envelope e idempotencia

- Envelope `schema: 1`, tipos `position | presence | ack`
  (`mobile/MobileEnvelope.java`, validación en `MobileEnvelopeValidator.java`:
  `messageId` `[A-Za-z0-9_-]{16,64}`, `deviceId == topic`, `sequence > 0`,
  `sentAt/observedAt` ISO-8601, `observedAt` máx. 7 días, payload GPS válido).
- `presence` = heartbeat/señal de jornada (sin GPS): actualiza telemetría y
  estado online/offline sin insertar posición ficticia.
- Dedupe: reserva en `tc_mobile_messages` por `(deviceId, sequence/messageId)`
  + hash canónico (`MobileIngestionService.canonicalHash`) idéntico en MQTT y
  HTTP; persistencia atómica `INSERT tc_positions + UPDATE tc_mobile_messages`
  (`MobileAtomicPersistence.java`): un crash antes del commit reintenta, después
  responde `duplicate` sin segunda posición.

### HTTP fallback

- `POST /api/mobile/v1/positions` (`MobileHttpResource.java`): mismo envelope
  en batch JSON (header `X-Api-Key: mobile.http.apiKey`). Serial por elemento,
  semáforo de 20 concurrentes (== `database.maxPoolSize`): si se llena responde
  `503 Retry-After: 2` para que la app haga backoff.
- `POST /api/mobile/provision` (`MobileProvisionResource.java`, **solo admin**):
  crea/actualiza el `Device` (`uniqueId = username`) con
  `mobile.intervalSeconds/bufferMax/bufferPolicy/ackTimeoutSeconds/maxRetries`
  y crea/actualiza el usuario MQTT en EMQX vía API (idempotente: 409 → PUT).
- Ambos documentados en [`openapi.yaml`](openapi.yaml) (`MobileEnvelope`,
  `MobileProvisionRequest/Response`, `MobilePositionsResult`).

### Jornadas

- `MobileJourneyRegistry` (en memoria): dispositivos con jornada activa.
- `presence` con `journeyStarted=true` → `start(deviceId, journeyId)`,
  `journeyEnded=true` → `end(deviceId)` (+ `Device.STATUS_ONLINE/OFFLINE` y
  push por `ConnectionManager`, que el dashboard recibe por WebSocket).
- Pipeline posición: `MobileIngestionService → PositionPipeline → DB →
  WebSocket` (`tc_positions` + `tc_mobile_messages` con `positionId`).
- Eventos: `MobileTelemetryMonitor` (gps on/off, red, batería ≤10%,
  journey started/ended) y `MobileSilenceMonitor` (`mobileNetworkLost` a los
  2 min de silencio con última red wifi/mobile, `mobilePossiblePowerOff` si era
  none/sin datos; cooldown 15 min). Ver tipos `mobile*` en `model/Event.java`.

### Dev local

```bash
cp ../.env.example ../.env   # completar CHANGE_ME
../infrastructure/scripts/dev.sh up
./gradlew build
../infrastructure/scripts/run-server-dev.sh start  # poll /api/health
```

## License

    Apache License, Version 2.0

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
