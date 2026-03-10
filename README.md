# Logstream

A real-time log streaming bridge between **Kafka** and **WebSocket** clients, built with Spring Boot 3.

## How It Works

```
Kafka Topics  →  KafkaLogConsumer  →  LogBroadcastService  →  WebSocket Clients
```

1. Kafka consumer subscribes to configured topics
2. Each log event is evaluated against per-session **topic subscriptions** and **filters** (server, path, text search, regex, keywords, time range)
3. Only matching events are broadcast asynchronously to the relevant WebSocket clients
4. On connection, the client receives the list of available topics, then live log events that pass its filters

## WebSocket API

**Endpoint:** `ws://localhost:8080/ws/logs`

**Message 1 — sent immediately on connect (topic list):**
```json
["server-topic", "system-topic", "app1-topic"]
```

**Message 2..N — live log events (filtered per session):**
```json
{
  "serverName": "web-01",
  "path": "/var/log/app.log",
  "topic": "app1-topic",
  "timestamp": "2026-03-07T10:00:00",
  "message": "Started application in 1.2 seconds"
}
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
    "regex": false,
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
{ "type": "filter-ack", "filters": { ... }, "regexError": "Unclosed group" }
```
The `regexError` field is only present when `regex` is `true` and the pattern is invalid.

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
│   ├── AsyncConfig.java           # Bounded ThreadPoolTaskExecutor for @Async broadcast
│   ├── LogstreamProperties.java   # @ConfigurationProperties binding
│   └── WebSocketConfig.java       # WebSocket endpoint + CORS + container limits
├── filter/
│   └── LogFilterEngine.java       # Stateless filter engine — evaluates LogEvent vs ClientFilter
├── kafka/
│   └── KafkaLogConsumer.java      # Batch Kafka listener (up to 500 records/poll)
├── model/
│   ├── ClientFilter.java          # Immutable per-session filter criteria record
│   └── LogEvent.java              # Record: serverName, path, topic, timestamp, message
├── service/
│   └── LogBroadcastService.java   # Async broadcast with per-session backpressure (500 pending max)
└── websocket/
    ├── LogWebSocketHandler.java      # Connection lifecycle + subscribe/filter/clear-filters + throttle
    └── WebSocketSessionRegistry.java # Tracks sessions, subscriptions, and per-session filters
```

## Configuration

All config is externalized via environment variables with sensible dev defaults.

| Env Var | Default | Description |
|---|---|---|
| `HOST_PORT` | `8080` | Host port exposed by Docker (Docker only) |
| `SERVER_PORT` | `8080` | Spring Boot internal port (jar/spring boot run) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker address |
| `KAFKA_CONSUMER_GROUP_ID` | `log-dashboard` | Kafka consumer group |
| `KAFKA_MAX_POLL_RECORDS` | `500` | Max Kafka records per batch poll |
| `LOGSTREAM_TOPICS` | `server-topic,system-topic,...` | Comma-separated topics to subscribe |
| `LOGSTREAM_ALLOWED_ORIGINS` | `http://localhost:5173` | Allowed WebSocket origin |
| `JVM_MAX_HEAP` | `512m` | JVM max heap size (Docker only) |

## Running Locally

**Prerequisites:** Java 17, a running Kafka broker

```bash
# Dev (uses application.yaml defaults)
./mvnw spring-boot:run

# Dev with custom Kafka broker
KAFKA_BOOTSTRAP_SERVERS=192.168.1.10:9092 ./mvnw spring-boot:run
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
JVM_MAX_HEAP=512m
```

**3. Run**
```bash
docker-compose up -d
```

App is now running on port `8080`.

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
