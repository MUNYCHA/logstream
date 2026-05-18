# Architecture — logstream

Deep implementation reference. CLAUDE.md links here for details.

---

## Threading Model

```
Thread: kafka-consumer-0 (Spring Kafka listener thread)
  │
  │  KafkaLogConsumer.consume(List<LogEvent>)
  │    → for each event: broadcastService.broadcast(event)
  │    → broadcast() = incomingQueue.add(event)  ← NON-BLOCKING, never stalls Kafka
  │
  ▼
ConcurrentLinkedQueue<LogEvent> incomingQueue
  │
  │  (drained every 100ms)
  ▼
Thread: scheduling-1 (Spring @Scheduled — LogBroadcastService.flush)
  │
  │  → drain queue into List<LogEvent>
  │  → for each event:
  │        statsAccumulator.record(evt)   ← stats accumulators
  │        metaStore.record(evt)          ← server/path snapshots
  │  → for each session:
  │        filter events against subscriptions + ClientFilter
  │        serialize matched events (array or single)
  │        sessionBackpressure.send(session, message)
  │
Thread: scheduling-2 (Spring @Scheduled — StatsBroadcaster.flushStats, every 2s)
  │
  │  → statsAccumulator.drain() (atomic swap)
  │  → build StatsMessage
  │  → for each session: sessionBackpressure.send(session, message)
  │
  ▼
SessionBackpressure (shared by both flush paths)
  │
  │  → reserve slot via atomic CAS (max 500 pending per session)
  │  → synchronized(session) { session.sendMessage() }
  │
  ▼
WebSocket Clients
```

### Key threading rules
- **Kafka consumer thread**: Only enqueues; never blocks on WebSocket I/O.
- **Two scheduled threads** (`flush` 100ms, `flushStats` 2s) both route sends through `SessionBackpressure`, whose `synchronized(session)` block serializes writes per session.
- **Tomcat WS threads**: Handle inbound client messages (subscribe/filter/clear) in `LogWebSocketHandler` — parsed via Jackson into the sealed `WsClientMessage`.
- **Session registry**: All `ConcurrentHashMap` — safe for concurrent reads from flush threads + writes from WS threads.

## Broadcast Pipeline (4-component split)

The broadcast feature is split across four cooperating classes — each with one responsibility — so the hot path stays narrow.

| Class | Role | Trigger |
|---|---|---|
| `LogBroadcastService` | Enqueue (called from Kafka thread) + 100ms flush of matched events to subscribed sessions. | `@KafkaListener` callback + `@Scheduled(fixedDelay = 100)` |
| `StatsAccumulator` | Per-topic `LongAdder` counters + active-server `Set<String>`. Atomic swap on drain. | Called by `LogBroadcastService.flush` for every drained event |
| `StatsBroadcaster` | Drains the accumulator, builds a `StatsMessage`, fans out to every session. | `@Scheduled(fixedDelay = 2000)` |
| `SessionBackpressure` | Per-session pending counter + `synchronized(session)` send. Drops on slow clients. | Called by both schedulers |

`TopicMetaStore` (under `streaming/topic/`) is updated from the same `flush` loop — it holds per-server, per-path counters and last-seen timestamps queried by the REST meta endpoint.

## Message Lifecycle (End-to-End)

```
Kafka broker
  → KafkaLogConsumer receives batch (up to 500 records)
  → Each LogEvent enqueued to ConcurrentLinkedQueue
  ...~100ms later (LogBroadcastService.flush)...
  → Drain queue into List<LogEvent>
  → For each event: statsAccumulator.record + metaStore.record
  → For each WebSocketSession:
      → Check topic subscription (sessionRegistry.isSubscribed)
      → Check ClientFilter (filterEngine.matches)
      → Collect matched events into per-session list
      → Serialize: 1 event → single JSON object, 2+ events → JSON array
      → sessionBackpressure.send(session, TextMessage)
          → atomic CAS reserve slot (drop if pending ≥ 500)
          → synchronized(session) → session.sendMessage()
  → Client receives JSON frame
```

## Backpressure System (`SessionBackpressure`)

```
ConcurrentHashMap<String, AtomicInteger> sendQueue  // sessionId → pending count

send(session, message):
  pending = sendQueue.computeIfAbsent(sessionId, → AtomicInteger(0))
  do {
    current = pending.get()
    if (current >= 500) → DROP message, return    // slow client
  } while (!CAS(current, current + 1))
  synchronized (session) {
    try {
      session.sendMessage(message)
    } catch (Exception e) {
      sessionRegistry.remove(session)
      sendQueue.remove(sessionId)
    } finally {
      pending.decrementAndGet()
    }
  }

// Wired via sessionRegistry.onRemove(this::onSessionRemoved) — cleans up on disconnect.
```

Lock-free CAS reservation prevents slow clients from consuming heap; the lock is held only for the actual `sendMessage` call.

## Filter Engine (`LogFilterEngine`)

Stateless `@Component`. Single entry point: `matches(LogEvent, ClientFilter) → boolean`.

### Evaluation order (short-circuits on first mismatch):
1. **Server** — exact match on `event.serverName()`
2. **Path** — exact match on `event.path()`
3. **Time range** — `1m | 5m | 15m | 1h | custom` → cutoff = `Instant.now() − window`; reject if `event.timestamp` is older. Unparseable timestamps are logged once and let through.
4. **Text search** — case-insensitive `contains` on `message`
5. **Keywords** — each term checked case-insensitive against `message`. `mode=and` → all must match; `mode=or` → any must match.

`ClientFilter.sanitize()` normalizes input on every filter message: trims/null-empties strings, lowercases + de-dupes keyword terms (cap 20), forces `keywordMode ∈ {and, or}`, forces `timeRange` into the allowlist.

## WebSocket Handler (`LogWebSocketHandler`)

### Inbound dispatch
The payload is parsed into the sealed `WsClientMessage` via Jackson polymorphic deserialization on the `action` field:

```java
@JsonTypeInfo(use = NAME, property = "action")
@JsonSubTypes({
  @Type(value = Subscribe.class,    name = "subscribe"),
  @Type(value = Filter.class,       name = "filter"),
  @Type(value = ClearFilters.class, name = "clear-filters")
})
sealed interface WsClientMessage permits Subscribe, Filter, ClearFilters
```

| Action | Handler | Side effect |
|---|---|---|
| `Subscribe(topics)` | `handleSubscribe` | Intersects requested topics with allowlist, calls `sessionRegistry.subscribe` |
| `Filter(filters)` | `handleFilter` | Converts `ClientFilterRequest` → sanitized `ClientFilter`, calls `sessionRegistry.setFilter` |
| `ClearFilters()` | `handleClearFilters` | `sessionRegistry.setFilter(session, ClientFilter.EMPTY)` |

### Connection lifecycle
- `afterConnectionEstablished`: register session, send `TopicsListMessage` greeting (`{"type":"topics","topics":[...]}`).
- `afterConnectionClosed`: `sessionRegistry.remove(session)` (which cascades to backpressure cleanup via `onRemove`).

## Session Registry (`WebSocketSessionRegistry`)

```java
Set<WebSocketSession> sessions              // ConcurrentHashMap.newKeySet()
Map<String, Set<String>> subscriptions      // sessionId → topic set
Map<String, ClientFilter> filters           // sessionId → filter (absent = EMPTY)
List<Consumer<String>> removeListeners      // notified on remove(session)
```

| Method | Thread safety | Notes |
|---|---|---|
| `add(session)` | Concurrent set add | Tomcat WS thread |
| `remove(session)` | Set remove + map removes + listener callbacks | WS thread or backpressure (on send failure) |
| `subscribe(session, topics)` | Atomic replace | Replaces all subscriptions |
| `isSubscribed(session, topic)` | Map get + set contains | Hot path in flush |
| `setFilter(session, filter)` | Map put (or remove if empty) | Hot path on filter messages |
| `getFilter(session)` | Map getOrDefault(EMPTY) | Hot path in flush |
| `forEach(consumer)` | Streams sessions, filters by `isOpen()` | Used by flush + stats |
| `onRemove(listener)` | Append to listener list | Called by `SessionBackpressure` at construction |

## Authentication

### REST (`SecurityConfig`)
- `oauth2ResourceServer().jwt()` — Spring auto-configures a `NimbusJwtDecoder` from `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (mapped to `SSO_JWKS_URI` in prod).
- `/actuator/health` and `/ws/**` are `permitAll()` at the filter chain (the WS path is gated by the handshake interceptor instead).
- All other paths require a valid `Authorization: Bearer <jwt>` header.

### WebSocket (`JwtHandshakeInterceptor`)
- Reads `?token=<jwt>` from the handshake request (browsers can't set headers on the WS handshake).
- Calls `jwtDecoder.decode(token)`; on failure → respond `401`, abort upgrade.
- On success, stashes the `Jwt` and `subject` in the handshake attributes for downstream access.

## Error Handling

All REST errors flow through `GlobalExceptionHandler` (`@RestControllerAdvice`) and return an `ApiError` record:

```json
{ "status": 404, "code": "LOG_FILE_NOT_FOUND", "message": "Log file not found",
  "timestamp": "2026-05-18T11:00:00Z", "path": "/api/logs/download" }
```

| Exception | Status | Code |
|---|---|---|
| `LogFileNotFoundException` | 404 | `LOG_FILE_NOT_FOUND` |
| `InvalidTopicException` | 400 | `INVALID_TOPIC` |

Topic-not-in-allowlist and file-missing-on-disk both raise `LogFileNotFoundException` — same 404, same message — so attackers can't distinguish the two via response.

## Log File Resolver (`LogFileResolver`)

Four ordered checks before serving a file:

1. **Allowlist** — `topic ∈ properties.getTopics()` (semantic guard; closes path traversal at the input level)
2. **Lexical containment** — `base.resolve(topic + ".log").normalize()` must `startsWith(base)`
3. **File state** — `Files.exists` + `Files.isRegularFile` (rejects directories, devices, FIFOs)
4. **Symlink boundary** — `toRealPath()` re-resolved against `base.toRealPath()` (catches symlinks pointing outside the base dir)

Any failure → `LogFileNotFoundException` (or `InvalidTopicException` for malformed input). The controller is just headers + `Files.copy`.

## Configuration

### WebSocket container (`WebSocketConfig`)
| Setting | Value | Purpose |
|---|---|---|
| Message buffer size | 512 KB | Allow large stack traces |
| Send timeout | 5,000 ms | Drop slow clients |
| Idle timeout | 300,000 ms (5 min) | Clean up abandoned sessions |
| CORS origins | From `logstream.allowed-origins` | |
| Handshake interceptor | `JwtHandshakeInterceptor` | JWT validation before upgrade |

### Kafka consumer (`application.yaml`)
| Setting | Value | Purpose |
|---|---|---|
| `max.poll.records` | 500 | Batch size per poll |
| `auto-offset-reset` | `latest` | Only stream new events |
| Listener type | `batch` | Receives `List<LogEvent>` |
| Trusted packages | `org.munycha.logstream.streaming.kafka` | JsonDeserializer security |
| Default value type | `LogEvent` | Auto-deserialize |

### Thread pool (`AsyncConfig`)
| Setting | Value |
|---|---|
| Core pool size | 4 |
| Max pool size | 8 |
| Queue capacity | 10,000 |
| Rejection policy | Drop oldest, execute new |

## Data Models

### `LogEvent` (record, `streaming/kafka`)
```java
record LogEvent(String serverName, String path, String topic, String timestamp, String message)
// isValid() — all fields non-blank except message which may be empty but not null
```

### `ClientFilter` (record, `streaming/filter`)
```java
record ClientFilter(String server, String path, String search,
                    List<String> keywordTerms, String keywordMode,
                    String timeRange, long timeRangeMs)
// EMPTY constant; hasServer/hasPath/hasSearch/hasKeywords/hasTimeRange/isEmpty helpers
// sanitize(...) factory normalizes raw input
```

### Wire format (JSON)

```
Server → Client:
  Greeting:     { "type": "topics", "topics": [...] }                   (once on connect)
  Single event: { "serverName", "path", "topic", "timestamp", "message" }
  Batched:      [ { ... }, { ... }, ... ]                                (every ~100ms)
  Stats:        { "type": "stats", "topics": { topic: { rate, servers } }, "intervalMs": 2000 }

Client → Server:
  Subscribe:    { "action": "subscribe", "topics": [...] }
  Filter:       { "action": "filter", "filters": { server, path, search,
                                                   keywords: { terms, mode },
                                                   timeRange, timeRangeMs } }
  Clear:        { "action": "clear-filters" }
```

## REST API

| Method | Path | Response | Errors |
|---|---|---|---|
| `GET` | `/api/logs/download?topic={topic}` | `text/plain` stream of `{topic}.log` | 404 if topic unknown / file missing; 400 if topic malformed |
| `GET` | `/api/topics/{topic}/meta` | `TopicMetaResponse` (cache 30s) | 400 if topic blank or unknown |
| `GET` | `/actuator/health` | Health JSON (prod only) | — |

### `TopicMetaResponse` shape
```json
{
  "servers": [
    {
      "name": "web-01",
      "count": 1234,
      "lastSeen": "2026-05-18T10:59:42Z",
      "paths": [
        { "path": "/var/log/app.log", "count": 1234, "lastSeen": "2026-05-18T10:59:42Z" }
      ]
    }
  ]
}
```

## File Map

```
src/main/java/org/munycha/logstream/
├── LogstreamApplication.java               # @SpringBootApplication entry point
│
├── common/
│   ├── config/
│   │   ├── AsyncConfig.java                # ThreadPoolTaskExecutor (4-8 threads, 10k queue)
│   │   ├── CorsConfig.java                 # HTTP CORS for /api/**
│   │   └── LogstreamProperties.java        # @ConfigurationProperties("logstream")
│   └── exception/
│       ├── ApiError.java                   # Uniform error response record
│       ├── GlobalExceptionHandler.java     # @RestControllerAdvice
│       ├── InvalidTopicException.java
│       └── LogFileNotFoundException.java
│
├── security/
│   ├── SecurityConfig.java                 # JWT resource server, filter chain
│   └── JwtHandshakeInterceptor.java        # WS handshake ?token= validation
│
└── streaming/
    ├── kafka/
    │   ├── KafkaLogConsumer.java           # Batch @KafkaListener
    │   └── LogEvent.java
    │
    ├── filter/
    │   ├── LogFilterEngine.java            # Stateless: server/path/time/search/keywords
    │   └── ClientFilter.java               # Filter record + EMPTY + sanitize()
    │
    ├── broadcast/
    │   ├── LogBroadcastService.java        # Enqueue + 100ms flush
    │   ├── StatsAccumulator.java           # Per-topic counters, atomic-swap drain
    │   ├── StatsBroadcaster.java           # @Scheduled(2s) stats emit
    │   └── SessionBackpressure.java        # Per-session pending CAS + synchronized send
    │
    ├── websocket/
    │   ├── WebSocketConfig.java            # /ws/logs registration, container limits
    │   ├── LogWebSocketHandler.java        # Lifecycle + sealed-message dispatch
    │   ├── WebSocketSessionRegistry.java   # Sessions / subscriptions / filters
    │   └── dto/
    │       ├── WsClientMessage.java            # Sealed: Subscribe | Filter | ClearFilters
    │       ├── ClientFilterRequest.java        # Raw inbound filter → sanitized
    │       ├── TopicsListMessage.java          # Greeting payload
    │       ├── StatsMessage.java               # Stats payload
    │       └── TopicStat.java
    │
    ├── download/
    │   ├── LogDownloadController.java      # GET /api/logs/download
    │   └── LogFileResolver.java            # 4-layer path security
    │
    └── topic/
        ├── LogTopicMetaController.java     # GET /api/topics/{topic}/meta
        ├── TopicMetaStore.java             # In-memory server/path counters
        └── dto/
            └── TopicMetaResponse.java      # Nested ServerEntry / PathEntry

src/main/resources/
├── application.yaml                        # Base: app name, Kafka consumer, lifecycle
├── application-dev.yaml                    # Dev: hardcoded Kafka broker, topics, CORS
└── application-prod.yaml                   # Prod: all values from env vars + SSO_JWKS_URI + actuator
```
