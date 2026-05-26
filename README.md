# Logstream

A real-time log streaming bridge between **Kafka** and **WebSocket** clients, built with Spring Boot 3. Also exposes a REST API for log file downloads and per-topic metadata, secured with JWT bearer auth.

## How It Works

```
Kafka Topics  →  KafkaLogConsumer  →  LogBroadcastService (batched flush)  →  WebSocket Clients
                                       │
                                       ├──→ StatsAccumulator (per-topic rate + servers)
                                       │      └──→ StatsBroadcaster (every 2s) ──→ all sessions
                                       │
                                       └──→ TopicMetaStore (server/path snapshots, queried via REST)
```

1. Kafka consumer subscribes to configured topics and enqueues events (non-blocking)
2. Every 100ms, the broadcast service flushes the queue. Each event is evaluated per session against **topic subscriptions** and **filters** (server, path, text search, keywords, time range). Matched events go out as a single JSON object or a batched array.
3. In parallel, accumulator updates per-topic rate counters and active-server sets. Every 2s a `stats` message is fanned out to every session.
4. On connect, the client receives a one-shot `topics` greeting listing the configured topics, then live events.

## Authentication

Both REST and WebSocket require a valid JWT (OAuth2 resource server).

- **REST** — `Authorization: Bearer <token>` header
- **WebSocket** — token passed as the `bearer.<jwt>` WebSocket subprotocol alongside `logstream.v1`. Validated by `JwtHandshakeInterceptor` before the connection is upgraded; failure → `401`.

The signing keys are fetched from `SSO_JWKS_URI`. `GET /actuator/health` is the only unauthenticated endpoint.

## WebSocket API

**Endpoint:** `ws://localhost:8080/ws/logs`, opened with subprotocols `["logstream.v1", "bearer.<jwt>"]`

### Server → Client

**On connect — topic list:**
```json
{ "type": "topics", "topics": ["server-topic", "system-topic", "app1-topic"] }
```

**Live log event (single):**
```json
{
  "serverName": "web-01",
  "path": "/var/log/app.log",
  "topic": "app1-topic",
  "timestamp": "2026-03-07T10:00:00Z",
  "message": "Started application in 1.2 seconds"
}
```

**Live log batch (2+ events in one frame, every ~100ms):**
```json
[
  { "serverName": "web-01", "path": "...", "topic": "...", "timestamp": "...", "message": "..." },
  { "serverName": "web-02", "path": "...", "topic": "...", "timestamp": "...", "message": "..." }
]
```

**Stats (every 2s, sent to all sessions regardless of subscription):**
```json
{
  "type": "stats",
  "topics": {
    "app1-topic": { "rate": 142, "servers": ["web-01", "web-02"] }
  },
  "intervalMs": 2000
}
```

### Client → Server

**Subscribe to topics** (required before any logs are sent):
```json
{ "action": "subscribe", "topics": ["app1-topic", "app2-topic"] }
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

## REST API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/logs/download?topic={topic}` | Streams `{topic}.log` from `LOGSTREAM_LOG_DIR` as `text/plain`. Path-security hardened: allowlist + lexical + symlink-resolved checks. |
| `GET` | `/api/topics/{topic}/meta` | Per-topic snapshot: each server + its paths with event counts and last-seen timestamp. 30s cache. |
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
| Spring Kafka | Batch listener, JSON deserializer |
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
│   │   └── LogstreamProperties.java   # @ConfigurationProperties("logstream")
│   └── exception/
│       ├── ApiError.java              # Uniform error response record
│       ├── GlobalExceptionHandler.java  # @RestControllerAdvice
│       ├── InvalidTopicException.java
│       └── LogFileNotFoundException.java
│
├── security/
│   ├── SecurityConfig.java            # Filter chain, JWT resource server
│   └── JwtHandshakeInterceptor.java   # Validates bearer JWT WS subprotocol
│
└── streaming/
    ├── kafka/
    │   ├── KafkaLogConsumer.java      # Batch @KafkaListener
    │   └── LogEvent.java              # Record: serverName, path, topic, timestamp, message
    ├── filter/
    │   ├── LogFilterEngine.java       # Stateless filter — evaluates LogEvent vs ClientFilter
    │   └── ClientFilter.java          # Immutable per-session filter record + sanitize()
    ├── broadcast/
    │   ├── LogBroadcastService.java   # Enqueue + @Scheduled(100ms) flush hot path
    │   ├── StatsAccumulator.java      # Per-topic rate + active-server tracking
    │   ├── StatsBroadcaster.java      # @Scheduled(2s) stats emit
    │   └── SessionBackpressure.java   # Per-session pending counters + synchronized(session) send
    ├── websocket/
    │   ├── WebSocketConfig.java       # /ws/logs endpoint, container limits, handshake interceptor
    │   ├── LogWebSocketHandler.java   # Lifecycle + action dispatch (subscribe/filter/clear-filters)
    │   ├── WebSocketSessionRegistry.java # ConcurrentHashMap session/subscription/filter store
    │   └── dto/
    │       ├── WsClientMessage.java       # Sealed: Subscribe | Filter | ClearFilters
    │       ├── ClientFilterRequest.java   # Raw inbound filter → sanitized ClientFilter
    │       ├── TopicsListMessage.java     # Greeting payload
    │       ├── StatsMessage.java          # Stats payload
    │       └── TopicStat.java             # Per-topic stats entry
    ├── download/
    │   ├── LogDownloadController.java # GET /api/logs/download
    │   └── LogFileResolver.java       # Allowlist + lexical + symlink-resolved path checks
    └── topic/
        ├── LogTopicMetaController.java  # GET /api/topics/{topic}/meta
        ├── TopicMetaStore.java          # In-memory: topic → server → path counts
        └── dto/
            └── TopicMetaResponse.java   # Nested ServerEntry / PathEntry records
```

## Configuration

All config is externalized via environment variables with sensible dev defaults.

| Env Var | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | Spring Boot internal port (jar / spring-boot:run) |
| `KAFKA_BOOTSTRAP_SERVERS` | `172.27.12.202:9092` | Kafka broker address |
| `KAFKA_CONSUMER_GROUP_ID` | `log-dashboard` | Kafka consumer group |
| `KAFKA_MAX_POLL_RECORDS` | `500` | Max Kafka records per batch poll |
| `LOGSTREAM_TOPICS` | `server-topic,system-topic,...` | Comma-separated topics to subscribe |
| `LOGSTREAM_ALLOWED_ORIGINS` | `http://localhost:5173` | Allowed WebSocket and REST API origin |
| `JVM_MAX_HEAP` | `512m` | JVM max heap size (Docker only) |
| `LOGSTREAM_LOG_DIR` | — | Directory containing download files. Files must be named `{topic}.log`, with the topic included in `LOGSTREAM_TOPICS`. |
| `SSO_JWKS_URI` | — | JWKS endpoint for JWT validation (prod profile). |

## Running Locally

**Prerequisites:** Java 17, a running Kafka broker.

```bash
# Dev profile (uses application-dev.yaml defaults)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Dev with custom Kafka broker
KAFKA_BOOTSTRAP_SERVERS=192.168.1.10:9092 ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

## Building & Deploying

**Build the jar:**
```bash
./mvnw clean package -DskipTests
```

**Run in production:**
```bash
KAFKA_BOOTSTRAP_SERVERS=prod-broker:9092 \
LOGSTREAM_TOPICS=server-topic,system-topic,app1-topic \
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
