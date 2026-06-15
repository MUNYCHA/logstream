# CLAUDE.md

Guidance for Claude Code. For deep implementation details, see [ARCHITECTURE.md](./ARCHITECTURE.md).

## Project Overview

Logstream is a real-time log streaming server. Kafka → WebSocket. Also exposes REST endpoints for log file downloads and per-topic metadata. JWT-secured.

**Data flow:** Kafka → `KafkaLogConsumer` (batch poll) → `ConcurrentLinkedQueue` → `LogBroadcastService` (@Scheduled 100ms flush) → sessions grouped by (subscriptions, filter), one serialization per group → `SessionBackpressure` → WebSocket clients. In parallel: `StatsAccumulator` collects per-topic counters; `StatsBroadcaster` fans them out every 2s.

## Build & Run

```bash
./mvnw clean package              # with tests
./mvnw clean package -DskipTests  # skip tests
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev  # dev
./mvnw test                       # all tests
./mvnw test -Dtest=Class#method   # single test
docker compose up -d              # standalone backend container only
```

## Stack

Java 17, Spring Boot 3.5.x, Maven 3.9.x (wrapper), Spring Security (OAuth2 resource server / JWT), Spring WebSocket (native `TextWebSocketHandler`, not STOMP), Spring Kafka (batch `JsonDeserializer`), Spring Boot Actuator (always on the classpath; `/actuator/health` exposed in all profiles, explicit exposure config only in prod).

## Package Layout (feature-based)

```
common/
  config/         AsyncConfig, CorsConfig, LogstreamProperties
  exception/      GlobalExceptionHandler, ApiError,
                  LogFileNotFoundException, InvalidTopicException
security/         SecurityConfig (JWT resource server),
                  JwtHandshakeInterceptor (WS bearer subprotocol validation)
streaming/
  kafka/          KafkaLogConsumer (batch @KafkaListener), LogEvent (record)
  filter/         LogFilterEngine (stateless), ClientFilter (record + sanitize)
  broadcast/      LogBroadcastService     — enqueue + 100ms flush hot path
                  StatsAccumulator        — per-topic counters, atomic-swap drain
                  StatsBroadcaster        — @Scheduled(2s) stats emit
                  SessionBackpressure     — send wrapper; evicts sessions on send failure
                  ReplayBuffer            — per-topic ring of recent events, replayed on subscribe
  websocket/      WebSocketConfig (/ws/logs, container limits, handshake interceptor)
                  LogWebSocketHandler     — lifecycle + sealed-message dispatch
                  WebSocketSessionRegistry — sessions/subscriptions/filters (ConcurrentHashMap)
                  SessionExpirySweeper    — @Scheduled(60s) closes sessions with expired JWTs
                  dto/   WsClientMessage (sealed: Subscribe | Filter | ClearFilters | Refresh),
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
- **`LogBroadcastService.flush`** (`@Scheduled`, 100ms) — drains queue, updates stats + meta, groups sessions by (subscriptions, filter), filters + serializes once per group, sends via `SessionBackpressure`.
- **`StatsBroadcaster.flushStats`** (`@Scheduled`, 2s) — atomic-swap drain of `StatsAccumulator`, fans `StatsMessage` to all sessions.
- **`SessionBackpressure.send`** — delegates to the `ConcurrentWebSocketSessionDecorator`-wrapped session (wrapped in `WebSocketSessionRegistry.add`); removes the session if the send throws.
- **Tomcat WS threads** — handle inbound actions in `LogWebSocketHandler`; on subscribe they read `ReplayBuffer` (synchronized per-topic ring, single writer = flush thread) and send history through the decorated session.
- **`SessionExpirySweeper.closeExpiredSessions`** (`@Scheduled`, 60s) — closes sessions unless the stored `jwt` attribute has an expiry provably in the future (`1008 Token expired`).
- All shared state in `ConcurrentHashMap` — safe for concurrent access.

## Performance Rules (DO NOT REGRESS)

- **Broadcast is batched**: `LogBroadcastService.broadcast()` MUST only enqueue. NEVER send directly from the Kafka thread.
- **Flush interval**: 100ms — matched events as JSON array (2+) or single object (1).
- **Group serialization**: sessions with identical (subscriptions, filter) share one matched list, one serialization, and the same `TextMessage` instance per chunk. NEVER reintroduce per-session serialization — with the UI auto-subscribing all clients to all topics, that multiplies flush cost by viewer count.
- **Stats broadcast**: every 2s, independent of subscriptions, drained via atomic accumulator swap.
- **Backpressure**: every session is wrapped in `ConcurrentWebSocketSessionDecorator` (5s send-time limit, 2MB buffer) at registration. Slow clients buffer then get closed. NEVER block a scheduler waiting for a slow client; NEVER send on a raw unwrapped session.
- **Filter engine is stateless**: no per-call allocations beyond what `ClientFilter.sanitize()` already normalized.
- **Session send**: WebSocket is not thread-safe — the decorator owns write serialization. All sends go through `SessionBackpressure.send` on the wrapped session; no manual `synchronized(session)` anywhere.

## Auth

- REST: `oauth2ResourceServer().jwt()` — bearer token required on every path except `/actuator/health` and `/ws/**` (which is gated by the handshake interceptor instead).
- WS: `JwtHandshakeInterceptor` validates `bearer.<jwt>` from `Sec-WebSocket-Protocol` at handshake. Failure or missing `sub` claim -> 401, no upgrade (fail closed — every session must have a subject). JWT + subject stashed in handshake attributes.
- WS session cap: `WebSocketSessionRegistry.add(session, subject)` atomically enforces `logstream.max-sessions-per-user` per JWT subject; over-cap connections are closed with `1008 Session limit reached` in `LogWebSocketHandler`.
- WS token expiry: a session lives only as long as its JWT. Clients push silently-renewed tokens via the `refresh` action; `LogWebSocketHandler.handleRefresh` validates the token, requires the same subject as the handshake, and replaces the session's `jwt` attribute. `SessionExpirySweeper` (60s) closes sessions whose stored token is expired, has no `exp` claim, or is missing (fail closed) with `1008 Token expired`; the UI reconnects with a fresh token.
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
  Replay:      same shape as Streaming — last ~500 events per newly subscribed topic,
               sent right after a subscribe; unfiltered (UI filters client-side)
  Streaming:   { "serverName", "path", "topic", "timestamp", "message" }  (single event)
          or:  [ {...}, {...}, ... ]                                      (batched, every ~100ms)
  Stats:       { "type": "stats", "topics": { topic: { rate, servers } }, "intervalMs": 2000 }

Client → Server (parsed via sealed WsClientMessage on the "action" field):
  Subscribe:   { "action": "subscribe", "topics": [...] }
  Filter:      { "action": "filter", "filters": { server, path, search,
                                                  keywords: { terms, mode },
                                                  timeRange, timeRangeMs } }
  Clear:       { "action": "clear-filters" }
  Refresh:     { "action": "refresh", "token": "<jwt>" }   (renewed access token; must be
                                                            the same subject as the handshake)
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
| `LOGSTREAM_MAX_SESSIONS_PER_USER` | `5` | Max concurrent WS sessions per JWT subject; `0` disables. Over-cap connects are closed with 1008. |
| `LOGSTREAM_REPLAY_BUFFER_SIZE` | `500` | Events kept per topic for replay to newly subscribed sessions; `0` disables. |
| `SERVER_PORT` | `8080` | App port |
| `JVM_MAX_HEAP` | `512m` | JVM heap (Docker only) |
| `LOGSTREAM_LOG_DIR` | — | Directory containing log files; each topic expects `{topic}.log` inside |
| `SSO_JWKS_URI` | — | JWKS endpoint for JWT validation (prod) |

## Branches

- `main` — production-ready
- `dev` — active development
- `feat/auth-setup` — current branch (JWT + the package reorg)
