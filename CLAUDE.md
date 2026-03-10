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

- **`config/AsyncConfig`** — Bounded `ThreadPoolTaskExecutor` (4-8 threads, 10k queue) for `@Async` broadcast. Replaces default unbounded `SimpleAsyncTaskExecutor`. Drops oldest queued task under extreme load
- **`config/WebSocketConfig`** — Registers handler at `/ws/logs`, configures CORS, sets WebSocket container limits (512KB message buffer, 5s send timeout, 5min idle timeout)
- **`config/LogstreamProperties`** — `@ConfigurationProperties(prefix="logstream")` binding topics and allowed-origins
- **`kafka/KafkaLogConsumer`** — Batch `@KafkaListener` consuming up to 500 records per poll, delegates each to broadcast service
- **`service/LogBroadcastService`** — `@Async` service that sends LogEvent JSON to matching WebSocket sessions. Applies per-session topic subscription + filter checks. Per-session backpressure: drops messages when a slow client has 500+ pending sends (atomic CAS). Lazy serialization — only creates TextMessage if at least one session passes filters
- **`filter/LogFilterEngine`** — Stateless `@Component` that evaluates a `LogEvent` against a `ClientFilter`. Handles server/path exact match, substring/regex text search (512-char limit, compiled pattern cache), keyword AND/OR matching across message+serverName+path, and time-range cutoff (server-clock based). Invalid regex drops events
- **`websocket/LogWebSocketHandler`** — Handles connect/disconnect; sends topic list on connect; processes `subscribe`, `filter` (with 100ms per-session throttle), and `clear-filters` client actions; sends `filter-ack` responses with regex validation errors
- **`websocket/WebSocketSessionRegistry`** — Thread-safe `ConcurrentHashMap` session store for sessions, topic subscriptions, and per-session `ClientFilter`. Fires `onRemove` listeners for cleanup on disconnect
- **`model/LogEvent`** — Java record: `serverName`, `path`, `topic`, `timestamp`, `message`
- **`model/ClientFilter`** — Immutable record holding per-session filter criteria: `server`, `path`, `search`, `regex`, `keywordTerms`, `keywordMode`, `timeRange`

## Configuration

Default config is in `application.yaml`; production overrides in `application-prod.yaml`.

Key environment variables:

| Variable | Dev Default | Description |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `172.27.12.202:9092` | Kafka broker address |
| `KAFKA_CONSUMER_GROUP_ID` | `log-dashboard` | Consumer group ID |
| `KAFKA_MAX_POLL_RECORDS` | `500` | Max Kafka records per batch poll |
| `LOGSTREAM_TOPICS` | `server-topic,system-topic,app1-topic,...` | Comma-separated Kafka topics |
| `LOGSTREAM_ALLOWED_ORIGINS` | `http://localhost:5173` | WebSocket CORS origins |
| `SERVER_PORT` | `8080` | Application port |
| `HOST_PORT` | `8080` | Docker host port mapping |
| `JVM_MAX_HEAP` | `512m` | JVM max heap size (Docker only) |

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
