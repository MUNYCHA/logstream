# CLAUDE.md

Guidance for Claude Code. For deep implementation details, see [ARCHITECTURE.md](./ARCHITECTURE.md).

## Project Overview

Logstream is a real-time log streaming server. Kafka → WebSocket. Also exposes REST endpoints for log file downloads and per-topic metadata. JWT-secured.

**Data flow:** Kafka → `KafkaLogConsumer` (batch poll) → `ConcurrentLinkedQueue` → `LogBroadcastService` (@Scheduled 100ms flush) → per-session subscription + filter → `SessionBackpressure` → WebSocket clients. In parallel: `StatsAccumulator` collects per-topic counters; `StatsBroadcaster` fans them out every 2s.

## Build & Run

```bash
./mvnw clean package              # with tests
./mvnw clean package -DskipTests  # skip tests
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev  # dev
./mvnw test                       # all tests
./mvnw test -Dtest=Class#method   # single test
docker-compose up -d              # Docker (requires .env)
```

## Stack

Java 17, Spring Boot 3.5.x, Maven 3.9.x (wrapper), Spring Security (OAuth2 resource server / JWT), Spring WebSocket (native `TextWebSocketHandler`, not STOMP), Spring Kafka (batch `JsonDeserializer`), Spring Boot Actuator (prod only).

## Package Layout (feature-based)

```
common/
  config/         AsyncConfig, CorsConfig, LogstreamProperties
  exception/      GlobalExceptionHandler, ApiError,
                  LogFileNotFoundException, InvalidTopicException
security/         SecurityConfig (JWT resource server),
                  JwtHandshakeInterceptor (WS ?token= validation)
streaming/
  kafka/          KafkaLogConsumer (batch @KafkaListener), LogEvent (record)
  filter/         LogFilterEngine (stateless), ClientFilter (record + sanitize)
  broadcast/      LogBroadcastService     — enqueue + 100ms flush hot path
                  StatsAccumulator        — per-topic counters, atomic-swap drain
                  StatsBroadcaster        — @Scheduled(2s) stats emit
                  SessionBackpressure     — per-session pending CAS + synchronized send
  websocket/      WebSocketConfig (/ws/logs, container limits, handshake interceptor)
                  LogWebSocketHandler     — lifecycle + sealed-message dispatch
                  WebSocketSessionRegistry — sessions/subscriptions/filters (ConcurrentHashMap)
                  dto/   WsClientMessage (sealed: Subscribe | Filter | ClearFilters),
                         ClientFilterRequest, TopicsListMessage, StatsMessage, TopicStat
  download/       LogDownloadController, LogFileResolver (4-layer path security)
  topic/          LogTopicMetaController, TopicMetaStore
                  dto/   TopicMetaResponse (nested ServerEntry / PathEntry)
```

**Conventions**:
- Package-by-feature, not by layer. Don't add top-level `controller/` `service/` `dto/`.
- Data classes live next to their boundary (e.g. `LogEvent` in `kafka/`, not in a `model/` folder).
- `dto/` subpackage appears only when a feature has multiple transport shapes.
- Cross-feature dependencies are explicit imports — keep them rare.

## Threading Model

- **Kafka consumer thread** — only calls `incomingQueue.add()`; never blocks.
- **`LogBroadcastService.flush`** (`@Scheduled`, 100ms) — drains queue, updates stats + meta, filters per session, sends via `SessionBackpressure`.
- **`StatsBroadcaster.flushStats`** (`@Scheduled`, 2s) — atomic-swap drain of `StatsAccumulator`, fans `StatsMessage` to all sessions.
- **`SessionBackpressure.send`** — atomic CAS on per-session pending counter (drop at 500), then `synchronized(session) { session.sendMessage(...) }`.
- **Tomcat WS threads** — handle inbound actions in `LogWebSocketHandler`.
- All shared state in `ConcurrentHashMap` — safe for concurrent access.

## Performance Rules (DO NOT REGRESS)

- **Broadcast is batched**: `LogBroadcastService.broadcast()` MUST only enqueue. NEVER send directly from the Kafka thread.
- **Flush interval**: 100ms — matched events as JSON array (2+) or single object (1).
- **Stats broadcast**: every 2s, independent of subscriptions, drained via atomic accumulator swap.
- **Backpressure**: lock-free CAS on per-session pending counter in `SessionBackpressure`. Drop at 500. NEVER block a scheduler waiting for a slow client.
- **Filter engine is stateless**: no per-call allocations beyond what `ClientFilter.sanitize()` already normalized.
- **Session send**: MUST be `synchronized(session)` — WebSocket is not thread-safe. The lock lives only inside `SessionBackpressure.trySend`.

## Auth

- REST: `oauth2ResourceServer().jwt()` — bearer token required on every path except `/actuator/health` and `/ws/**` (which is gated by the handshake interceptor instead).
- WS: `JwtHandshakeInterceptor` validates `?token=<jwt>` query param at handshake. Failure → 401, no upgrade. JWT + subject stashed in handshake attributes.
- JWKS URI: `SSO_JWKS_URI` env var → `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`.

## Error Handling

REST errors flow through `GlobalExceptionHandler` (`@RestControllerAdvice`) → `ApiError` JSON shape:
```json
{ "status": 404, "code": "LOG_FILE_NOT_FOUND", "message": "...", "timestamp": "...", "path": "..." }
```
Topic-unknown and file-missing both raise `LogFileNotFoundException` → same 404, same message (no info leak).

## WS Protocol

```
Server → Client:
  Greeting:    { "type": "topics", "topics": [...] }                    (once on connect)
  Streaming:   { "serverName", "path", "topic", "timestamp", "message" }  (single event)
          or:  [ {...}, {...}, ... ]                                      (batched, every ~100ms)
  Stats:       { "type": "stats", "topics": { topic: { rate, servers } }, "intervalMs": 2000 }

Client → Server (parsed via sealed WsClientMessage on the "action" field):
  Subscribe:   { "action": "subscribe", "topics": [...] }
  Filter:      { "action": "filter", "filters": { server, path, search,
                                                  keywords: { terms, mode },
                                                  timeRange, timeRangeMs } }
  Clear:       { "action": "clear-filters" }
```

## REST API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/logs/download?topic={topic}` | Streams `{topic}.log` as `text/plain`. 4-layer path-security in `LogFileResolver`. |
| `GET` | `/api/topics/{topic}/meta` | Per-topic server/path snapshot. 30s cache. |
| `GET` | `/actuator/health` | Prod only, unauthenticated. |

## Configuration

Default: `application.yaml`. Production: `application-prod.yaml` (requires all env vars, enables actuator).

| Variable | Dev Default | Description |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `172.27.12.202:9092` | Kafka broker |
| `KAFKA_CONSUMER_GROUP_ID` | `log-dashboard` | Consumer group |
| `KAFKA_MAX_POLL_RECORDS` | `500` | Max records per batch poll |
| `LOGSTREAM_TOPICS` | `server-topic,system-topic,...` | Comma-separated Kafka topics |
| `LOGSTREAM_ALLOWED_ORIGINS` | `http://localhost:5173` | WebSocket + REST CORS origins |
| `SERVER_PORT` | `8080` | App port |
| `HOST_PORT` | `8080` | Docker host port |
| `JVM_MAX_HEAP` | `512m` | JVM heap (Docker only) |
| `LOGSTREAM_LOG_DIR` | — | Directory containing log files; each topic expects `{topic}.log` inside |
| `SSO_JWKS_URI` | — | JWKS endpoint for JWT validation (prod) |

## Branches

- `main` — production-ready
- `dev` — active development
- `feat/auth-setup` — current branch (JWT + the package reorg)
