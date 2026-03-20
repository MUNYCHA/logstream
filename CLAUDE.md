# CLAUDE.md

Guidance for Claude Code. For deep implementation details, see [ARCHITECTURE.md](./ARCHITECTURE.md).

## Project Overview

Logstream is a real-time log streaming server. Kafka → WebSocket. Also exposes a REST API for log file downloads.

**Data flow:** Kafka → `KafkaLogConsumer` (batch poll) → `ConcurrentLinkedQueue` → `LogBroadcastService` (@Scheduled 100ms flush) → per-session filter → WebSocket clients

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

Java 17, Spring Boot 3.5.x, Maven 3.9.x (wrapper), Spring WebSocket (native TextWebSocketHandler, not STOMP), Spring Kafka (batch JsonDeserializer), Spring Boot Actuator (prod only)

## Architecture (Summary)

```
config/AsyncConfig          @EnableAsync + @EnableScheduling, ThreadPoolTaskExecutor (4-8 threads, 10k queue)
config/WebSocketConfig      /ws/logs endpoint, CORS, 512KB buffer, 5s send timeout, 5min idle
config/CorsConfig           HTTP CORS — allows GET /api/** from configured allowed origins
config/LogstreamProperties  @ConfigurationProperties: topics, allowedOrigins, logDir (log directory path)
controller/LogDownloadController  GET /api/logs/download?topic=X — streams log file to browser
kafka/KafkaLogConsumer      Batch @KafkaListener (up to 500/poll), enqueues to broadcast service
service/LogBroadcastService Queue + @Scheduled(100ms) flush, per-session filter + backpressure (500 pending)
filter/LogFilterEngine      Stateless: server/path exact, time range, substring search, keyword AND/OR
websocket/LogWebSocketHandler   subscribe/filter/clear-filters actions, 100ms throttle, filter-ack response
websocket/WebSocketSessionRegistry  ConcurrentHashMap store: sessions, subscriptions, per-session filters
model/LogEvent              Record: serverName, path, topic, timestamp, message
model/ClientFilter          Record: server, path, search, keywordTerms, keywordMode, timeRange, timeRangeMs + EMPTY constant
```

## Threading Model

- **Kafka consumer thread** — only calls `incomingQueue.add()`, never blocks
- **Scheduled flush thread** (every 100ms) — drains queue, filters per session, serializes, sends via `synchronized(session)`
- **Tomcat WS threads** — handle client actions (subscribe/filter/clear) in LogWebSocketHandler
- All shared state in `ConcurrentHashMap` — safe for concurrent access

## Performance Rules (DO NOT REGRESS)

- **Broadcast is batched**: `broadcast()` MUST only enqueue. NEVER send directly from Kafka thread (blocks consumer)
- **Flush interval**: 100ms — sends matched events as JSON array (2+ events) or single object (1 event)
- **Backpressure**: Atomic CAS on per-session pending counter. Drop at 500. NEVER block the flush thread waiting for a slow client
- **Filter engine is stateless**: No per-call allocations except keyword lowercasing (already normalized in ClientFilter.sanitize())
- **Session send**: MUST be `synchronized(session)` — WebSocket is not thread-safe

## WS Protocol

```
Server → Client:
  Connect:    string[]                                          (topic list, once)
  Streaming:  { serverName, path, topic, timestamp, message }   (single event)
         or:  [{...}, {...}, ...]                               (batched array, every ~100ms)
  Filter ack: { type: "filter-ack", filters: {...} }

Client → Server:
  Subscribe:     { action: "subscribe", topics: [...] }
  Filter:        { action: "filter", filters: { server, path, search, keywords: { terms, mode }, timeRange } }
  Clear filters: { action: "clear-filters" }
```

## Configuration

Default: `application.yaml`. Production: `application-prod.yaml` (requires all env vars, enables actuator).

| Variable | Dev Default | Description |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `172.27.12.202:9092` | Kafka broker |
| `KAFKA_CONSUMER_GROUP_ID` | `log-dashboard` | Consumer group |
| `KAFKA_MAX_POLL_RECORDS` | `500` | Max records per batch poll |
| `LOGSTREAM_TOPICS` | `server-topic,system-topic,...` | Comma-separated Kafka topics |
| `LOGSTREAM_ALLOWED_ORIGINS` | `http://localhost:5173` | WebSocket CORS origins |
| `SERVER_PORT` | `8080` | App port |
| `HOST_PORT` | `8080` | Docker host port |
| `JVM_MAX_HEAP` | `512m` | JVM heap (Docker only) |
| `LOGSTREAM_LOG_DIR` | — | Directory containing log files; each topic expects `{topic}.log` inside |

## Branches

- `main` — production-ready
- `dev` — active development
