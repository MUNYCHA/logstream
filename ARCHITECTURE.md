# Architecture — logstream

Deep implementation reference. CLAUDE.md links here for details.

---

## Threading Model

```
Thread: redis-subscriber (RedisMessageListenerContainer task executor)
  │
  │  RedisLogSubscriber.onMessage(Message, byte[])
  │    → deserialize the message body into a single LogEvent (pub/sub has no
  │      native batch concept — one message in, one broadcast() call out)
  │    → broadcastService.broadcast(event)
  │    → broadcast() = incomingQueue.add(event)  ← NON-BLOCKING, never stalls the subscriber
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
  │        replayBuffer.record(evt)       ← per-topic history ring
  │  → group sessions by DispatchKey(subscriptions copy, filter)
  │  → for each group:
  │        filter events against the group's subscriptions + ClientFilter
  │        serialize matched events ONCE (array or single), share the TextMessage
  │        sessionBackpressure.send(session, message) for each session in the group
  │
Thread: scheduling-2 (Spring @Scheduled — StatsBroadcaster.flushStats, every 2s)
  │
  │  → statsAccumulator.drain() (atomic swap)
  │  → build StatsMessage
  │  → for each session: sessionBackpressure.send(session, message)
  │
Thread: scheduling-N (Spring @Scheduled — SessionExpirySweeper.closeExpiredSessions, every 60s)
  │
  │  → for each session: close with 1008 "Token expired" unless the stored jwt
  │    attribute has an expiry provably in the future (fail closed: missing
  │    token or missing exp claim also closes)
  │
  ▼
SessionBackpressure (shared by both flush paths)
  │
  │  → session.sendMessage() on the ConcurrentWebSocketSessionDecorator-wrapped
  │    session — non-blocking for the scheduler: messages buffer per session
  │    (decorator closes any session exceeding 5s send time or 2MB buffered)
  │
  ▼
WebSocket Clients
```

### Key threading rules
- **Redis subscriber thread**: Only enqueues; never blocks on WebSocket I/O.
- **Two scheduled threads** (`flush` 100ms, `flushStats` 2s) both route sends through `SessionBackpressure`. Concurrency and slow-client isolation are handled by the `ConcurrentWebSocketSessionDecorator` each session is wrapped in at registration — a stuck client never blocks the schedulers.
- **Tomcat WS threads**: Handle inbound client messages (subscribe/filter/clear-filters/refresh) in `LogWebSocketHandler` — parsed via Jackson into the sealed `WsClientMessage`. On subscribe they also read `ReplayBuffer` (per-topic synchronized ring; single writer = flush thread) and send history through the decorated session.
- **Sweeper thread** (`closeExpiredSessions` 60s): closes sessions whose stored JWT can't be proven valid — expired, no `exp`, or no token at all.
- **Session registry**: All `ConcurrentHashMap` — safe for concurrent reads from flush threads + writes from WS threads.

## Broadcast Pipeline (5-component split)

The broadcast feature is split across five cooperating classes — each with one responsibility — so the hot path stays narrow.

| Class | Role | Trigger |
|---|---|---|
| `LogBroadcastService` | Enqueue (called from the Redis subscriber thread) + 100ms flush. Groups sessions by identical (subscriptions, filter); each group's events are matched and serialized once. | `RedisLogSubscriber.onMessage` callback + `@Scheduled(fixedDelay = 100)` |
| `StatsAccumulator` | Per-topic `LongAdder` counters + active-server `Set<String>`. Atomic swap on drain. | Called by `LogBroadcastService.flush` for every drained event |
| `StatsBroadcaster` | Drains the accumulator, builds a `StatsMessage`, fans out to every session. | `@Scheduled(fixedDelay = 2000)` |
| `SessionBackpressure` | Sends to the decorator-wrapped session; evicts sessions whose send throws. Slow clients are buffered then closed by the decorator. | Called by both schedulers |
| `ReplayBuffer` | Last N events per topic (global sequence for cross-topic ordering), replayed to newly subscribed sessions. `LOGSTREAM_REPLAY_BUFFER_SIZE` (default 500); `0` disables. | Written by `flush` for every drained event; read on subscribe |

`TopicMetaStore` (under `streaming/topic/`) is updated from the same `flush` loop — it holds per-server, per-path counters and last-seen timestamps queried by the REST meta endpoint.

## Message Lifecycle (End-to-End)

```
Redis broker
  → RedisLogSubscriber receives one message per channel publish
  → LogEvent enqueued to ConcurrentLinkedQueue
  ...~100ms later (LogBroadcastService.flush)...
  → Drain queue into List<LogEvent>
  → For each event: statsAccumulator.record + metaStore.record + replayBuffer.record
  → Group sessions by DispatchKey(Set.copyOf(subscriptions), filter)
      (sessions without subscriptions are skipped — no logs until subscribe)
  → For each group:
      → Match events against the group's topics + ClientFilter (filterEngine.matches)
      → Serialize ONCE per ≤100-event chunk: 1 event → single JSON object, 2+ → JSON array
      → Share the immutable TextMessage across the group:
          sessionBackpressure.send(session, TextMessage) for each member
          → session.sendMessage() (decorator buffers; never blocks the scheduler)
  → Client receives JSON frame
```

## Backpressure System

Slow-client protection lives in `ConcurrentWebSocketSessionDecorator`, which
`WebSocketSessionRegistry.add()` wraps around every raw session:

- Sends from any thread are safe (the decorator serializes writes internally).
- If a send is already in progress, additional messages buffer per session and
  the calling scheduler returns immediately — a dead client can never stall
  broadcasts to other sessions.
- A session that exceeds **5 s send time** or **2 MB buffered** is marked
  unreliable and closed (`SESSION_NOT_RELIABLE`); the buffer is freed and the
  browser may reconnect. Healthy clients buffer ~0 bytes.

`SessionBackpressure` is the thin send wrapper both schedulers call: it
delegates to the decorated session and removes the session from the registry
if the send throws.

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
  @Type(value = ClearFilters.class, name = "clear-filters"),
  @Type(value = Refresh.class,      name = "refresh")
})
sealed interface WsClientMessage permits Subscribe, Filter, ClearFilters, Refresh
```

| Action | Handler | Side effect |
|---|---|---|
| `Subscribe(topics)` | `handleSubscribe` | Intersects requested topics with allowlist, calls `sessionRegistry.subscribe`, then replays `ReplayBuffer` history for newly subscribed topics (subscribe-before-replay, so no live event is lost; unfiltered, same frame shapes as live, ≤100 events per frame) |
| `Filter(filters)` | `handleFilter` | Converts `ClientFilterRequest` → sanitized `ClientFilter`, calls `sessionRegistry.setFilter` |
| `ClearFilters()` | `handleClearFilters` | `sessionRegistry.setFilter(session, ClientFilter.EMPTY)` |
| `Refresh(token)` | `handleRefresh` | Decodes the renewed JWT, requires the handshake subject, replaces the session's `jwt` attribute |

### Connection lifecycle
- `afterConnectionEstablished`: register via `sessionRegistry.add(session, subject)` (the subject comes from the handshake attributes). The registry returns the decorator-wrapped session, or `null` when the subject is at the per-user cap — then the connection is closed with `1008 Session limit reached`. On success, send the `TopicsListMessage` greeting (`{"type":"topics","topics":[...]}`) through the wrapped session.
- `afterConnectionClosed`: `sessionRegistry.remove(session)`.

## Session Registry (`WebSocketSessionRegistry`)

```java
Map<String, WebSocketSession> sessions      // sessionId → decorator-wrapped session
Map<String, Set<String>> subscriptions      // sessionId → topic set
Map<String, ClientFilter> filters           // sessionId → filter (absent = EMPTY)
Map<String, Set<String>> subjectSessions    // subject → live session ids (per-user cap)
Map<String, String> sessionSubjects         // sessionId → subject (for cleanup)
```

| Method | Thread safety | Notes |
|---|---|---|
| `add(session, subject)` | Cap claim inside a single `compute` + map put | Atomically reserves a per-subject slot (`logstream.max-sessions-per-user`; `0` disables), wraps in `ConcurrentWebSocketSessionDecorator` (5s/2MB limits), returns the wrapped session — or `null` when the cap is hit (caller closes). Tomcat WS thread |
| `remove(session)` | Map removes by id | Accepts raw or wrapped instance; releases the subject slot. WS thread or backpressure (on send failure) |
| `subscribe(session, topics)` | Atomic replace | Replaces all subscriptions; returns the previous set so the handler can replay only newly added topics |
| `isSubscribed(session, topic)` | Map get + set contains | Hot path in flush |
| `setFilter(session, filter)` | Map put (or remove if empty) | Hot path on filter messages |
| `getFilter(session)` | Map getOrDefault(EMPTY) | Hot path in flush |
| `forEach(consumer)` | Streams wrapped sessions, filters by `isOpen()` | Used by flush + stats |

## Authentication

### REST (`SecurityConfig`)
- `oauth2ResourceServer().jwt()` — Spring auto-configures a `NimbusJwtDecoder` from `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (mapped to `SSO_JWKS_URI` in prod).
- `/actuator/health` and `/ws/**` are `permitAll()` at the filter chain (the WS path is gated by the handshake interceptor instead).
- All other paths require a valid `Authorization: Bearer <jwt>` header.

### WebSocket (`JwtHandshakeInterceptor`)
- Reads `bearer.<jwt>` from the offered `Sec-WebSocket-Protocol` values; the client also offers `logstream.v1`, which the server selects.
- Calls `jwtDecoder.decode(token)`; on failure → respond `401`, abort upgrade.
- **Fail closed on a subject-less token**: a validly signed JWT with no (or blank) `sub` claim is also rejected with `401` — the session cap and the refresh identity check both key on the subject, so a session must never exist without one.
- On success, stashes the `Jwt` and `subject` in the handshake attributes for downstream access.

### Session lifetime (`SessionExpirySweeper`)
- The handshake validates the token once, but access tokens are short-lived (~5 min) — without further checks a session would stream for hours after its authorization lapsed.
- Clients push silently-renewed tokens in-band via `{"action":"refresh","token":...}`; `handleRefresh` validates the token and rejects any subject other than the one that authenticated the handshake (fail closed: a session with no stored subject rejects every refresh), then replaces the `jwt` attribute.
- `SessionExpirySweeper` (`@Scheduled`, 60s) closes sessions with `1008 Token expired` unless the stored `jwt` attribute has an expiry provably in the future — an expired token, a token without an `exp` claim, and a missing token all close the session (fail closed). The UI treats that close like any other drop: reconnect with a freshly renewed token.

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

Any failure → `LogFileNotFoundException` (or `InvalidTopicException` for malformed input). The controller is just headers + `Files.copy`, and scrubs the `Content-Disposition` filename (`[^a-zA-Z0-9._-]` → `_`) as defense-in-depth.

## Configuration

### WebSocket container (`WebSocketConfig`)
| Setting | Value | Purpose |
|---|---|---|
| Message buffer size | 512 KB | Allow large stack traces |
| Send timeout | 5,000 ms | Drop slow clients |
| Idle timeout | 300,000 ms (5 min) | Clean up abandoned sessions |
| CORS origins | From `logstream.allowed-origins` | |
| Handshake interceptor | `JwtHandshakeInterceptor` | JWT validation before upgrade |

### Redis config (`RedisConfig`)
| Setting | Value | Purpose |
|---|---|---|
| Connection factory | Auto-configured Lettuce `RedisConnectionFactory` from `spring.data.redis.{host,port,password}` | No manual factory bean — relies on Boot auto-config |
| Channel subscription | One `ChannelTopic` per `logstream.topics` entry, registered on `RedisMessageListenerContainer` | Mirrors the old one-Kafka-topic-per-log-topic model |
| Serializer | `Jackson2JsonRedisSerializer<LogEvent>` | Deserializes published JSON into `LogEvent` |
| `logstream.redis.listener-auto-startup` | `true` (default) | Toggle to disable the listener container in tests |

**Delivery semantics**: plain pub/sub is fire-and-forget — no persistence, no consumer-group offsets, no broker-side backlog. If the app is down or the subscriber briefly disconnects, events published during that window are lost with no redelivery. This is a deliberate, accepted tradeoff (not Redis Streams). `ReplayBuffer` is unaffected — it's an in-memory, broker-agnostic ring buffer that only ever replays events the app already received live.

### Logstream properties (`LogstreamProperties`)
| Setting | Default | Purpose |
|---|---|---|
| `logstream.max-sessions-per-user` | 5 | Per-JWT-subject WS session cap; `0` disables. Over-cap → `1008 Session limit reached` |
| `logstream.replay-buffer-size` | 500 | Events kept per topic for replay on subscribe; `0` disables replay |

### Thread pool (`AsyncConfig`)
| Setting | Value |
|---|---|
| Core pool size | 4 |
| Max pool size | 8 |
| Queue capacity | 10,000 |
| Rejection policy | Drop oldest, execute new |

## Data Models

### `LogEvent` (record, `streaming/redis`)
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
  Replay:       same shapes as Single/Batched — buffered history for newly
                subscribed topics, sent right after subscribe; unfiltered
  Single event: { "serverName", "path", "topic", "timestamp", "message" }
  Batched:      [ { ... }, { ... }, ... ]                                (every ~100ms)
  Stats:        { "type": "stats", "topics": { topic: { rate, servers } }, "intervalMs": 2000 }

Client → Server:
  Subscribe:    { "action": "subscribe", "topics": [...] }
  Filter:       { "action": "filter", "filters": { server, path, search,
                                                   keywords: { terms, mode },
                                                   timeRange, timeRangeMs } }
  Clear:        { "action": "clear-filters" }
  Refresh:      { "action": "refresh", "token": "<jwt>" }   (renewed access token; same
                                                             subject as the handshake)
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
│   │   ├── LogstreamProperties.java        # @ConfigurationProperties("logstream")
│   │   └── RedisConfig.java                # RedisMessageListenerContainer, per-topic channel subscription
│   └── exception/
│       ├── ApiError.java                   # Uniform error response record
│       ├── GlobalExceptionHandler.java     # @RestControllerAdvice
│       ├── InvalidTopicException.java
│       └── LogFileNotFoundException.java
│
├── security/
│   ├── SecurityConfig.java                 # JWT resource server, filter chain
│   └── JwtHandshakeInterceptor.java        # WS bearer subprotocol validation
│
└── streaming/
    ├── redis/
    │   ├── RedisLogSubscriber.java         # MessageListener, one channel per logstream.topics entry
    │   └── LogEvent.java
    │
    ├── filter/
    │   ├── LogFilterEngine.java            # Stateless: server/path/time/search/keywords
    │   └── ClientFilter.java               # Filter record + EMPTY + sanitize()
    │
    ├── broadcast/
    │   ├── LogBroadcastService.java        # Enqueue + 100ms flush (group serialization)
    │   ├── StatsAccumulator.java           # Per-topic counters, atomic-swap drain
    │   ├── StatsBroadcaster.java           # @Scheduled(2s) stats emit
    │   ├── SessionBackpressure.java        # Send wrapper; evicts sessions on send failure
    │   └── ReplayBuffer.java               # Per-topic ring of recent events, replayed on subscribe
    │
    ├── websocket/
    │   ├── WebSocketConfig.java            # /ws/logs registration, container limits
    │   ├── LogWebSocketHandler.java        # Lifecycle + sealed-message dispatch
    │   ├── WebSocketSessionRegistry.java   # Sessions / subscriptions / filters / per-user cap
    │   ├── SessionExpirySweeper.java       # @Scheduled(60s) closes sessions w/o provably valid JWT
    │   └── dto/
    │       ├── WsClientMessage.java            # Sealed: Subscribe | Filter | ClearFilters | Refresh
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
├── application.yaml                        # Base: app name, lifecycle
├── application-dev.yaml                    # Dev: local Redis host, topics, CORS
└── application-prod.yaml                   # Prod: all values from env vars + SSO_JWKS_URI + actuator
```
