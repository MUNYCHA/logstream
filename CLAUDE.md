# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Logstream is a real-time log streaming WebSocket server. It consumes log events from Kafka topics and broadcasts them to connected WebSocket clients. There is no REST API — all client communication is via WebSocket.

**Data flow:** Kafka Topics → KafkaLogConsumer → LogBroadcastService (async) → WebSocket Clients

## Build & Run Commands

```bash
# Build
./mvnw clean package              # with tests
./mvnw clean package -DskipTests  # skip tests

# Run (dev)
./mvnw spring-boot:run

# Run tests
./mvnw test
./mvnw test -Dtest=ClassName              # single test class
./mvnw test -Dtest=ClassName#methodName   # single test method

# Docker
docker-compose up -d    # requires .env file (see .env.example)
```

## Tech Stack

- Java 17, Spring Boot 3.5.x, Maven 3.9.x (via wrapper)
- Spring WebSocket (native TextWebSocketHandler, not STOMP)
- Spring Kafka (JsonDeserializer → LogEvent record)
- Spring Boot Actuator (prod profile only)

## Architecture

All source is under `org.munycha.logstream`:

- **`config/WebSocketConfig`** — Registers handler at `/ws/logs`, configures CORS from `logstream.allowed-origins`
- **`config/LogstreamProperties`** — `@ConfigurationProperties(prefix="logstream")` binding topics and allowed-origins
- **`kafka/KafkaLogConsumer`** — `@KafkaListener` consuming from configured topics, delegates to broadcast service
- **`service/LogBroadcastService`** — `@Async` service that sends LogEvent JSON to matching WebSocket sessions (applies per-session topic subscription + filter checks before sending)
- **`filter/LogFilterEngine`** — Stateless `@Component` that evaluates a `LogEvent` against a `ClientFilter`. Handles server/path exact match, substring/regex text search, keyword AND/OR matching, and time-range cutoff (server-clock based)
- **`websocket/LogWebSocketHandler`** — Handles connect/disconnect; sends topic list on connect; processes `subscribe`, `filter`, and `clear-filters` client actions; sends `filter-ack` responses
- **`websocket/WebSocketSessionRegistry`** — Thread-safe `ConcurrentHashMap` session store for sessions, topic subscriptions, and per-session `ClientFilter`
- **`model/LogEvent`** — Java record: `serverName`, `path`, `topic`, `timestamp`, `message`
- **`model/ClientFilter`** — Immutable record holding per-session filter criteria: `server`, `path`, `search`, `regex`, `keywordTerms`, `keywordMode`, `timeRange`

## Configuration

Default config is in `application.yaml`; production overrides in `application-prod.yaml`.

Key environment variables:

| Variable | Dev Default | Description |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `172.27.12.202:9092` | Kafka broker address |
| `KAFKA_CONSUMER_GROUP_ID` | `log-dashboard` | Consumer group ID |
| `LOGSTREAM_TOPICS` | `server-topic,system-topic,app1-topic,...` | Comma-separated Kafka topics |
| `LOGSTREAM_ALLOWED_ORIGINS` | `http://localhost:5173` | WebSocket CORS origins |
| `SERVER_PORT` | `8080` | Application port |
| `HOST_PORT` | `8080` | Docker host port mapping |

## WebSocket API

- **Endpoint:** `ws://localhost:8080/ws/logs`
- **On connect:** Server sends JSON array of available topic names
- **Streaming:** Server pushes `LogEvent` JSON objects as they arrive from Kafka (only events matching the session's topic subscriptions and active filter)

### Client → Server actions

| Action | Payload | Description |
|---|---|---|
| `subscribe` | `{ "action": "subscribe", "topics": ["t1","t2"] }` | Subscribe to specific topics (only receives events from those topics) |
| `filter` | `{ "action": "filter", "filters": { server, path, search, regex, keywords: { terms, mode }, timeRange } }` | Set per-session filter — server applies before sending events |
| `clear-filters` | `{ "action": "clear-filters" }` | Remove all filters for this session |

### Server → Client messages

| Type | Shape | Description |
|---|---|---|
| Topic list | `string[]` | Sent once on connect |
| Log event | `{ topic, serverName, path, message, timestamp }` | Live log (already filtered) |
| Filter ack | `{ "type": "filter-ack", "filters": {...}, "regexError": "..." }` | Confirms filter applied; includes `regexError` if regex is invalid |

## Branches

- `main` — production-ready
- `dev` — active development
