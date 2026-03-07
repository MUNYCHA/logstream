# Logstream

A real-time log streaming bridge between **Kafka** and **WebSocket** clients, built with Spring Boot 3.

## How It Works

```
Kafka Topics  →  KafkaLogConsumer  →  LogBroadcastService  →  WebSocket Clients
```

1. Kafka consumer subscribes to configured topics
2. Each log event is broadcast asynchronously to all connected WebSocket clients
3. On connection, the client receives the list of available topics, then live log events

## WebSocket API

**Endpoint:** `ws://localhost:8080/ws/logs`

**Message 1 — sent immediately on connect (topic list):**
```json
["server-topic", "system-topic", "app1-topic"]
```

**Message 2..N — live log events:**
```json
{
  "serverName": "web-01",
  "path": "/var/log/app.log",
  "topic": "app1-topic",
  "timestamp": "2026-03-07T10:00:00",
  "message": "Started application in 1.2 seconds"
}
```

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
│   ├── LogstreamProperties.java   # @ConfigurationProperties binding
│   └── WebSocketConfig.java       # WebSocket endpoint + CORS config
├── kafka/
│   └── KafkaLogConsumer.java      # Kafka listener
├── model/
│   └── LogEvent.java              # Record: serverName, path, topic, timestamp, message
├── service/
│   └── LogBroadcastService.java   # Async broadcast to all WebSocket sessions
└── websocket/
    ├── LogWebSocketHandler.java      # Connection lifecycle
    └── WebSocketSessionRegistry.java # Tracks active sessions
```

## Configuration

All config is externalized via environment variables with sensible dev defaults.

| Env Var | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP server port |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker address |
| `KAFKA_CONSUMER_GROUP_ID` | `log-dashboard` | Kafka consumer group |
| `LOGSTREAM_TOPICS` | `server-topic,system-topic,...` | Comma-separated topics to subscribe |
| `LOGSTREAM_ALLOWED_ORIGINS` | `http://localhost:5173` | Allowed WebSocket origin |

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
KAFKA_BOOTSTRAP_SERVERS=172.27.12.202:9092
LOGSTREAM_TOPICS=server-topic,system-topic,app1-topic,app2-topic,app3-topic,app4-topic
LOGSTREAM_ALLOWED_ORIGINS=https://myapp.com
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
