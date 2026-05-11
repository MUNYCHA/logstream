# Logstream

A real-time log streaming bridge between **Kafka** and **WebSocket** clients, built with Spring Boot 3. Also exposes a REST API for downloading log files directly from the server.

## How It Works

```
Kafka Topics  →  KafkaLogConsumer  →  LogBroadcastService (batched)  →  WebSocket Clients
```

1. Kafka consumer subscribes to configured topics and enqueues events
2. Every ~100ms, the broadcast service flushes the queue — each event is evaluated against per-session **topic subscriptions** and **filters** (server, path, text search, keywords, time range)
3. Only matching events are sent as a **batched JSON array** (or single object) per session per flush
4. On connection, the client receives the list of available topics. Live logs are sent only after the client subscribes to one or more topics.

## WebSocket API

**Endpoint:** `ws://localhost:8080/ws/logs`

**Message 1 — sent immediately on connect (topic list):**
```json
{
  "type": "topics",
  "topics": ["server-topic", "system-topic", "app1-topic"]
}
```

**Message 2..N — live log events (filtered per session, single or batched):**
```json
{
  "serverName": "web-01",
  "path": "/var/log/app.log",
  "topic": "app1-topic",
  "timestamp": "2026-03-07T10:00:00Z",
  "message": "Started application in 1.2 seconds"
}
```

**Batched (multiple events in one frame, sent every ~100ms):**
```json
[
  { "serverName": "web-01", "path": "/var/log/app.log", "topic": "app1-topic", "timestamp": "...", "message": "..." },
  { "serverName": "web-02", "path": "/var/log/app.log", "topic": "app1-topic", "timestamp": "...", "message": "..." }
]
```

**Client → Server — subscribe to topics:**
```json
{ "action": "subscribe", "topics": ["app1-topic", "app2-topic"] }
```

**Client → Server — set filters (all fields optional):**
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

**Client → Server — clear all filters:**
```json
{ "action": "clear-filters" }
```

**Server → Client — filter acknowledgment:**
```json
{ "type": "filter-ack", "filters": { ... } }
```

**Server -> Client - subscribed topic stats, every ~2 seconds when data is available:**
```json
{
  "type": "stats",
  "topics": {
    "app1-topic": {
      "rate": 42,
      "servers": ["web-01", "web-02"]
    }
  },
  "intervalMs": 2000
}
```

Timestamps should be ISO-8601 instants such as `2026-03-07T10:00:00Z`. Offset timestamps are supported, and local date-times are interpreted in the server timezone. Malformed timestamps are rejected when a time-range filter is active.

## Tech Stack

| | |
|---|---|
| Java | 17 (Temurin LTS) |
| Spring Boot | 3.5.x |
| Spring WebSocket | Embedded Tomcat |
| Spring Kafka | Kafka consumer |
| Maven | 3.9.x (via wrapper) |

## Project Structure

```
src/main/java/org/munycha/logstream/
├── LogstreamApplication.java
├── config/
│   ├── AsyncConfig.java           # @EnableAsync + @EnableScheduling, bounded ThreadPoolTaskExecutor
│   ├── CorsConfig.java            # HTTP CORS for /api/**
│   ├── LogstreamProperties.java   # @ConfigurationProperties binding
│   └── WebSocketConfig.java       # WebSocket endpoint + CORS + container limits
├── controller/
│   └── LogDownloadController.java # GET /api/logs/download?topic=X
├── filter/
│   └── LogFilterEngine.java       # Stateless filter engine — evaluates LogEvent vs ClientFilter
├── kafka/
│   └── KafkaLogConsumer.java      # Batch Kafka listener (up to 500 records/poll)
├── model/
│   ├── ClientFilter.java          # Immutable per-session filter criteria record
│   └── LogEvent.java              # Record: serverName, path, topic, timestamp, message
├── service/
│   └── LogBroadcastService.java   # Batched broadcast (100ms flush) with per-session backpressure (500 pending max)
└── websocket/
    ├── LogWebSocketHandler.java      # Connection lifecycle + subscribe/filter/clear-filters + filter-ack
    └── WebSocketSessionRegistry.java # Tracks sessions, subscriptions, and per-session filters
```

## Configuration

All config is externalized via environment variables with sensible dev defaults.

| Env Var | Default | Description |
|---|---|---|
| `HOST_PORT` | `8080` | Host port exposed by Docker (Docker only) |
| `SERVER_PORT` | `8080` | Spring Boot internal port (jar/spring boot run) |
| `KAFKA_BOOTSTRAP_SERVERS` | `172.27.12.202:9092` | Kafka broker address |
| `KAFKA_CONSUMER_GROUP_ID` | `log-dashboard` | Kafka consumer group |
| `KAFKA_MAX_POLL_RECORDS` | `500` | Max Kafka records per batch poll |
| `LOGSTREAM_TOPICS` | `server-topic,system-topic,...` | Comma-separated topics to subscribe |
| `LOGSTREAM_ALLOWED_ORIGINS` | `http://localhost:5173` | Allowed WebSocket and REST API origin |
| `LOGSTREAM_AUTH_TOKEN` | — | Optional shared token. When set, REST requests and WebSocket handshakes must send `X-Logstream-Token`, `Authorization: Bearer ...`, or `?token=...`. |
| `JVM_MAX_HEAP` | `512m` | JVM max heap size (Docker only) |
| `LOGSTREAM_LOG_DIR` | — | Directory where log files live — can be any path, but files must be named `{topic}.log` (e.g. `server-topic` → `{dir}/server-topic.log`). Also used as the Docker volume mount path. |

## Running Locally

**Prerequisites:** Java 17, a running Kafka broker

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
java -jar target/logstream-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

The `prod` profile (`application-prod.yaml`) requires all env vars to be explicitly set — the app will refuse to start if any are missing.

## Running with Docker

**Prerequisites:** Docker installed on the server — no Java required.

**1. Clone the repo**
```bash
git clone git@github.com:MUNYCHA/logstream.git
cd logstream
```

**2. Create your `.env` file from the example**
```bash
cp .env.example .env
```
Then edit `.env` with your actual values:
```env
HOST_PORT=8080
KAFKA_BOOTSTRAP_SERVERS=172.27.12.202:9092
KAFKA_CONSUMER_GROUP_ID=log-dashboard
KAFKA_MAX_POLL_RECORDS=500
LOGSTREAM_TOPICS=server-topic,system-topic,app1-topic,app2-topic,app3-topic,app4-topic
LOGSTREAM_ALLOWED_ORIGINS=https://myapp.com
LOGSTREAM_AUTH_TOKEN=replace-with-a-secret
JVM_MAX_HEAP=512m
LOGSTREAM_LOG_DIR=/var/log/logstream
```

**3. Run**
```bash
docker-compose up -d
```

The container exposes port `8080` only to the Docker `monitoring` network. Attach a reverse proxy on that network, or add a local `ports` mapping when testing directly from the host.

**Useful commands:**
```bash
# view live logs
docker-compose logs -f

# stop
docker-compose down

# pull latest code and restart
git pull && docker-compose up -d --build
```

## Branches

| Branch | Purpose |
|---|---|
| `main` | Stable, production-ready code |
| `dev` | Active development |
