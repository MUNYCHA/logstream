# Logstream

A real-time log streaming bridge between **Redis pub/sub** and **WebSocket** clients, built with Spring Boot 3. Also exposes a REST API for log file downloads and per-channel metadata, secured with JWT bearer auth.

## How It Works

```
Redis Channels  →  RedisLogSubscriber  →  LogBroadcastService (batched flush)  →  WebSocket Clients
                                       │
                                       ├──→ StatsAccumulator (per-channel rate + servers)
                                       │      └──→ StatsBroadcaster (every 2s) ──→ all sessions
                                       │
                                       ├──→ ReplayBuffer (recent events per channel, replayed on subscribe)
                                       │
                                       └──→ ChannelMetaStore (server/path snapshots, queried via REST)
```

1. Redis subscriber subscribes to one channel per configured channel name and enqueues events (non-blocking). Delivery is fire-and-forget — no persistence or backlog, so events published while the app is down or briefly disconnected are lost (this is an accepted tradeoff of plain pub/sub).
2. Every 100ms, the broadcast service flushes the queue. Sessions are grouped by identical (**channel subscriptions**, **filters** — server, path, text search, keywords, time range); each group's matched events are serialized once and sent as a single JSON object or a batched array.
3. In parallel, accumulator updates per-channel rate counters and active-server sets. Every 2s a `stats` message is fanned out to every session.
4. On connect, the client receives a one-shot `channels` greeting listing the configured channels. On subscribe, the last ~500 buffered events per newly subscribed channel are replayed so the panel isn't blank, then live events follow.
5. Slow or dead clients never stall the stream for others: each session is wrapped in a `ConcurrentWebSocketSessionDecorator`, so its messages buffer independently (up to 2 MB / 5 s) before the session is closed. The browser can simply reconnect.

## Authentication

Both REST and WebSocket require a valid JWT (OAuth2 resource server).

- **REST** — `Authorization: Bearer <token>` header
- **WebSocket** — token passed as the `bearer.<jwt>` WebSocket subprotocol alongside `logstream.v1`. Validated by `JwtHandshakeInterceptor` before the connection is upgraded; failure → `401`. The token must carry both a `sub` and an `exp` claim — a validly signed token missing its subject is rejected at the handshake, and one missing its expiry is closed by the sweeper (fail closed).
- **Session cap** — at most `LOGSTREAM_MAX_SESSIONS_PER_USER` concurrent sessions per JWT subject; over-cap connections are closed with `1008 Session limit reached`.
- **Token lifetime** — a session lives only as long as its JWT, no exceptions. Clients push silently-renewed tokens via the `refresh` action (must be the same subject as the handshake); `SessionExpirySweeper` (every 60s) closes any session whose stored token is expired, has no expiry, or is missing, with `1008 Token expired`. The UI reconnects with a fresh token.

The signing keys are fetched from `SSO_JWKS_URI`. `GET /actuator/health` is the only unauthenticated endpoint.

## WebSocket API

**Endpoint:** `ws://localhost:8080/ws/logs`, opened with subprotocols `["logstream.v1", "bearer.<jwt>"]`

### Server → Client

**On connect — channel list:**
```json
{ "type": "channels", "channels": ["server-channel", "system-channel", "app1-channel"] }
```

**On subscribe — replay:** the last ~500 buffered events per newly subscribed channel, sent immediately in the same single-object/array shapes as live events below. Replay is unfiltered (the UI filters client-side).

**Live log event (single):**
```json
{
  "serverName": "web-01",
  "path": "/var/log/app.log",
  "channel": "app1-channel",
  "timestamp": "2026-03-07T10:00:00Z",
  "message": "Started application in 1.2 seconds"
}
```

**Live log batch (2+ events in one frame, every ~100ms):**
```json
[
  { "serverName": "web-01", "path": "...", "channel": "...", "timestamp": "...", "message": "..." },
  { "serverName": "web-02", "path": "...", "channel": "...", "timestamp": "...", "message": "..." }
]
```

**Stats (every 2s, sent to all sessions regardless of subscription):**
```json
{
  "type": "stats",
  "channels": {
    "app1-channel": { "rate": 142, "servers": ["web-01", "web-02"] }
  },
  "intervalMs": 2000
}
```

### Client → Server

**Subscribe to channels** (required before any logs are sent):
```json
{ "action": "subscribe", "channels": ["app1-channel", "app2-channel"] }
```

**Set filters** (all fields optional):
```json
{
  "action": "filter",
  "filters": {
    "server": "web-01",
    "path": "/var/log/app.log",
    "search": "error",
    "keywords": { "terms": ["timeout", "exception"], "mode": "or" },
    "timeRange": "15m"
  }
}
```

**Clear all filters:**
```json
{ "action": "clear-filters" }
```

**Refresh the session token** (renewed access token; must be the same subject as the handshake, otherwise ignored):
```json
{ "action": "refresh", "token": "<jwt>" }
```

## REST API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/logs/download?channel={channel}` | Streams `{channel}.log` from `LOGSTREAM_LOG_DIR` as `text/plain`. Path-security hardened: allowlist + lexical + symlink-resolved checks; the `Content-Disposition` filename is scrubbed (`[^a-zA-Z0-9._-]` → `_`). |
| `GET` | `/api/channels/{channel}/meta` | Per-channel snapshot: each server + its paths with event counts and last-seen timestamp. 30s cache. |
| `GET` | `/actuator/health` | Health check (prod profile only, unauthenticated). |

### Error responses

All errors return a consistent shape:
```json
{
  "status": 404,
  "code": "LOG_FILE_NOT_FOUND",
  "message": "Log file not found",
  "timestamp": "2026-05-18T11:00:00Z",
  "path": "/api/logs/download"
}
```

## Tech Stack

| | |
|---|---|
| Java | 17 (Temurin LTS) |
| Spring Boot | 3.5.x |
| Spring Security | OAuth2 resource server (JWT) |
| Spring WebSocket | Native `TextWebSocketHandler` (not STOMP) |
| Spring Data Redis | Lettuce, pub/sub via `RedisMessageListenerContainer` |
| Maven | 3.9.x (via wrapper) |

## Project Structure

Package-by-feature. Each top-level package is a self-contained slice of behavior.

```
src/main/java/org/munycha/logstream/
├── LogstreamApplication.java
│
├── common/
│   ├── config/
│   │   ├── AsyncConfig.java           # @EnableAsync + @EnableScheduling, bounded ThreadPoolTaskExecutor
│   │   ├── CorsConfig.java            # HTTP CORS for /api/**
│   │   ├── LogstreamProperties.java   # @ConfigurationProperties("logstream")
│   │   └── RedisConfig.java           # RedisMessageListenerContainer, per-channel subscription
│   └── exception/
│       ├── ApiError.java              # Uniform error response record
│       ├── GlobalExceptionHandler.java  # @RestControllerAdvice
│       ├── InvalidChannelException.java
│       └── LogFileNotFoundException.java
│
├── security/
│   ├── SecurityConfig.java            # Filter chain, JWT resource server
│   └── JwtHandshakeInterceptor.java   # Validates bearer JWT WS subprotocol
│
└── streaming/
    ├── redis/
    │   ├── RedisLogSubscriber.java    # MessageListener, one channel per logstream.channels entry
    │   └── LogEvent.java              # Record: serverName, path, channel, timestamp, message
    ├── filter/
    │   ├── LogFilterEngine.java       # Stateless filter — evaluates LogEvent vs ClientFilter
    │   └── ClientFilter.java          # Immutable per-session filter record + sanitize()
    ├── broadcast/
    │   ├── LogBroadcastService.java   # Enqueue + @Scheduled(100ms) flush hot path
    │   ├── StatsAccumulator.java      # Per-channel rate + active-server tracking
    │   ├── StatsBroadcaster.java      # @Scheduled(2s) stats emit
    │   ├── SessionBackpressure.java   # Send wrapper — evicts sessions whose send fails
    │   └── ReplayBuffer.java          # Per-channel ring of recent events, replayed on subscribe
    ├── websocket/
    │   ├── WebSocketConfig.java       # /ws/logs endpoint, container limits, handshake interceptor
    │   ├── LogWebSocketHandler.java   # Lifecycle + action dispatch (subscribe/filter/clear-filters/refresh)
    │   ├── WebSocketSessionRegistry.java # ConcurrentHashMap session/subscription/filter store, per-user cap
    │   ├── SessionExpirySweeper.java  # @Scheduled(60s) closes sessions with expired/missing-expiry JWTs
    │   └── dto/
    │       ├── WsClientMessage.java       # Sealed: Subscribe | Filter | ClearFilters | Refresh
    │       ├── ClientFilterRequest.java   # Raw inbound filter → sanitized ClientFilter
    │       ├── ChannelsListMessage.java   # Greeting payload
    │       ├── StatsMessage.java          # Stats payload
    │       └── ChannelStat.java           # Per-channel stats entry
    ├── download/
    │   ├── LogDownloadController.java # GET /api/logs/download
    │   └── LogFileResolver.java       # Allowlist + lexical + symlink-resolved path checks
    └── channel/
        ├── LogChannelMetaController.java  # GET /api/channels/{channel}/meta
        ├── ChannelMetaStore.java          # In-memory: channel → server → path counts
        └── dto/
            └── ChannelMetaResponse.java   # Nested ServerEntry / PathEntry records
```

## Configuration

All config is externalized via environment variables with sensible dev defaults.

| Env Var | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | Spring Boot internal port (jar / spring-boot:run) |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | — | Redis auth password (prod only, blank if unset) |
| `LOGSTREAM_CHANNELS` | `server-channel,system-channel,...` | Comma-separated Redis pub/sub channels to subscribe |
| `LOGSTREAM_ALLOWED_ORIGINS` | `http://localhost:5173` | Allowed WebSocket and REST API origin |
| `LOGSTREAM_MAX_SESSIONS_PER_USER` | `5` | Max concurrent WS sessions per JWT subject; `0` disables the cap |
| `LOGSTREAM_REPLAY_BUFFER_SIZE` | `500` | Events kept per channel for replay on subscribe; `0` disables replay |
| `JVM_MAX_HEAP` | `512m` | JVM max heap size (Docker only) |
| `LOGSTREAM_LOG_DIR` | — | Directory containing download files. Files must be named `{channel}.log`, with the channel included in `LOGSTREAM_CHANNELS`. |
| `SSO_JWKS_URI` | — | JWKS endpoint for JWT validation (prod profile). |

## Running Locally

**Prerequisites:** Java 17, a running Redis instance.

```bash
# Dev profile (uses application-dev.yaml defaults)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Dev with custom Redis host
REDIS_HOST=192.168.1.10 ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

## Building & Deploying

**Build the jar:**
```bash
./mvnw clean package -DskipTests
```

**Run in production:**
```bash
REDIS_HOST=prod-redis \
LOGSTREAM_CHANNELS=server-channel,system-channel,app1-channel \
LOGSTREAM_ALLOWED_ORIGINS=https://myapp.com \
LOGSTREAM_LOG_DIR=/var/log/logstream \
SSO_JWKS_URI=https://sso.example.com/.well-known/jwks.json \
java -jar target/logstream-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

The `prod` profile (`application-prod.yaml`) requires all env vars to be explicitly set — the app will refuse to start if any are missing.

## Docker Deployment

The office-server deployment is owned by the sibling `log-infra` repository. Its
single Compose stack builds this API together with the React/nginx gateway and
Keycloak, using `log-infra/.env` as the deployment source of truth.

Use this repository's `docker-compose.yml` and `.env.example` only when running
the backend container independently for development or integration testing.

This repo's compose stack bundles its own `redis:7-alpine` service for that
standalone use — the `log-infra` stack does not, so if `log-infra` becomes the
real deployment target, a Redis service needs to be added there too (or
`REDIS_HOST` pointed at a shared instance) before switching that stack over.

## Tests

```bash
./mvnw test                                    # all tests
./mvnw test -Dtest=LogFilterEngineTest         # one class
./mvnw test -Dtest=LogFilterEngineTest#matches_nullFilter_alwaysTrue   # one method
```

## Branches

| Branch | Purpose |
|---|---|
| `main` | Stable, production-ready code |
| `dev` | Active development |
