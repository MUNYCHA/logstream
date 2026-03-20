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
Thread: scheduling-1 (Spring @Scheduled thread)
  │
  │  LogBroadcastService.flush()
  │    → drain all events from queue
  │    → for each session:
  │        → filter events against session's subscriptions + ClientFilter
  │        → backpressure check (atomic CAS, 500 pending max)
  │        → serialize matched events to JSON (array or single)
  │        → synchronized(session) { session.sendMessage() }
  │
  ▼
WebSocket Clients
```

### Key threading rules
- **Kafka consumer thread**: Only enqueues, never blocks on WebSocket I/O
- **Scheduled flush thread**: Owns all WebSocket sends. `synchronized(session)` ensures one message at a time per session
- **Tomcat WebSocket threads**: Handle incoming client messages (subscribe/filter/clear) in `LogWebSocketHandler`
- **Session registry**: All `ConcurrentHashMap` — safe for concurrent reads from flush thread + writes from WS threads

## Message Lifecycle (End-to-End)

```
Kafka broker
  → KafkaLogConsumer receives batch (up to 500 records)
  → Each LogEvent enqueued to ConcurrentLinkedQueue
  ...~100ms later (@Scheduled flush)...
  → Drain queue into List<LogEvent>
  → For each WebSocketSession:
      → Check topic subscription (sessionRegistry.isSubscribed)
      → Check ClientFilter (filterEngine.matches)
      → Collect matched events into per-session list
      → Backpressure: if pending >= 500, drop entire batch for this session
      → Serialize: 1 event → single JSON object, 2+ events → JSON array
      → synchronized(session) → sendMessage(TextMessage)
  → Client receives JSON frame
      → useWebSocket.onmessage → JSON.parse
      → Normalize: array check → topic list / filter-ack / log event(s)
      → Assign _id, truncate oversized messages
      → Push to pendingRef
      ...150ms later (setInterval flush)...
      → Batch update logsByTopic state → React re-renders
```

## Backpressure System

```
Per-session AtomicInteger in ConcurrentHashMap<String, AtomicInteger> sendQueue

Before send:
  do {
    current = pending.get();
    if (current >= 500) → DROP entire batch, return
  } while (!CAS(current, current + 1));

After send (in finally):
  pending.decrementAndGet();

On session disconnect:
  sendQueue.remove(sessionId)  ← via onRemove listener
```

This prevents slow clients from consuming memory. The CAS loop is lock-free.

## Filter Engine (`LogFilterEngine`)

Stateless `@Component`. Single entry point: `matches(LogEvent, ClientFilter) → boolean`

### Filter evaluation order (short-circuits on first mismatch):
1. **Server** — exact match on `event.serverName()`
2. **Path** — exact match on `event.path()`
3. **Time range** — `1m`, `5m`, `15m`, `1h` → compute cutoff from `System.currentTimeMillis()`, parse event timestamp, reject if older
4. **Text search** — case-insensitive `contains` on `message`
5. **Keywords** — each term checked case-insensitive against `message` only; AND mode = all must match, OR mode = any must match

## Session Registry (`WebSocketSessionRegistry`)

```java
Set<WebSocketSession> sessions              // ConcurrentHashMap.newKeySet()
Map<String, Set<String>> subscriptions      // sessionId → Set<topicName>
Map<String, ClientFilter> filters           // sessionId → ClientFilter
List<Consumer<String>> removeListeners      // called on session removal
```

### Key methods
| Method | Thread safety | Notes |
|---|---|---|
| `add(session)` | Concurrent set add | Called from Tomcat WS thread |
| `remove(session)` | Set remove + map removes + listener callbacks | Called from WS thread or flush thread (on send failure) |
| `subscribe(session, topics)` | Map put | Replaces all subscriptions |
| `isSubscribed(session, topic)` | Map get + set contains | Hot path in flush |
| `hasSubscriptions(session)` | Map containsKey | If false, session gets ALL topics |
| `setFilter(session, filter)` | Map put | Called from WS handler |
| `getFilter(session)` | Map getOrDefault(EMPTY) | Hot path in flush |
| `forEach(consumer)` | Streams sessions, filters by isOpen() | Used by flush |
| `activeCount()` | Set size | |

## WebSocket Handler (`LogWebSocketHandler`)

### Client actions
| Action | Handler | Side effects |
|---|---|---|
| `subscribe` | `handleSubscribe` | `sessionRegistry.subscribe(session, topics)` |
| `filter` | `handleFilter` (throttled 100ms) | `sessionRegistry.setFilter(session, filter)` + sends filter-ack |
| `clear-filters` | `handleClearFilters` | `sessionRegistry.setFilter(session, EMPTY)` + sends filter-ack |

### Filter throttle
Per-session throttle via `ConcurrentHashMap<String, Long> lastFilterTime`:
- `merge(sessionId, now, (last, cur) -> (cur - last) < 100 ? last : cur)`
- If merge kept old value → throttled, skip filter update
- Cleaned up on disconnect

### Connection lifecycle
- `afterConnectionEstablished`: register session, send topic list JSON
- `afterConnectionClosed`: unregister session, clean up throttle map

## Configuration

### WebSocket container (`WebSocketConfig`)
| Setting | Value | Purpose |
|---|---|---|
| Message buffer size | 512 KB | Allow large stack traces |
| Send timeout | 5,000 ms | Drop slow clients |
| Idle timeout | 300,000 ms (5 min) | Clean up abandoned sessions |
| CORS origins | From `logstream.allowed-origins` | |

### Kafka consumer (`application.yaml`)
| Setting | Value | Purpose |
|---|---|---|
| `max.poll.records` | 500 | Batch size per poll |
| `auto-offset-reset` | `latest` | Only stream new events |
| Listener type | `batch` | Receives `List<LogEvent>` |
| Trusted packages | `org.munycha.logstream.model` | JsonDeserializer security |
| Default value type | `LogEvent` | Auto-deserialize |

### Thread pool (`AsyncConfig`)
| Setting | Value |
|---|---|
| Core pool size | 4 |
| Max pool size | 8 |
| Queue capacity | 10,000 |
| Rejection policy | Drop oldest, execute new |

## Data Models

### `LogEvent` (Java record)
```java
record LogEvent(String serverName, String path, String topic, String timestamp, String message)
```

### `ClientFilter` (Java record)
```java
record ClientFilter(String server, String path, String search, boolean regex,
                    List<String> keywordTerms, String keywordMode, String timeRange, long timeRangeMs)

static EMPTY = new ClientFilter(null, null, null, false, List.of(), "or", "all", 0)

// Convenience: hasServer(), hasPath(), hasSearch(), hasKeywords(), hasTimeRange(), isEmpty()
```

### Wire format (JSON)
```
Server → Client:
  Topic list:    ["topic1", "topic2"]                           (once on connect)
  Single event:  { serverName, path, topic, timestamp, message }
  Batched:       [{ ... }, { ... }, ...]                        (every ~100ms)
  Filter ack:    { type: "filter-ack", filters: {...}, regexError?: "..." }

Client → Server:
  Subscribe:     { action: "subscribe", topics: [...] }
  Filter:        { action: "filter", filters: { server, path, search, regex, keywords: { terms, mode }, timeRange } }
  Clear:         { action: "clear-filters" }
```

## File Map

```
src/main/java/org/munycha/logstream/
├── LogstreamApplication.java           # @SpringBootApplication entry point
├── config/
│   ├── AsyncConfig.java                # @EnableAsync + @EnableScheduling, ThreadPoolTaskExecutor
│   ├── CorsConfig.java                 # HTTP CORS — allows GET /api/** from allowed origins
│   ├── LogstreamProperties.java        # @ConfigurationProperties: topics, allowedOrigins, logFiles
│   └── WebSocketConfig.java            # /ws/logs endpoint, CORS, container limits
├── controller/
│   └── LogDownloadController.java      # GET /api/logs/download?topic=X — streams log file to browser
├── filter/
│   └── LogFilterEngine.java            # Stateless filter: server/path/time/search/regex/keywords
├── kafka/
│   └── KafkaLogConsumer.java           # Batch @KafkaListener, enqueues to broadcast service
├── model/
│   ├── ClientFilter.java               # Immutable filter record + EMPTY constant
│   └── LogEvent.java                   # Immutable event record
├── service/
│   └── LogBroadcastService.java        # Queue + @Scheduled flush + per-session backpressure
└── websocket/
    ├── LogWebSocketHandler.java        # subscribe/filter/clear-filters + throttle + filter-ack
    └── WebSocketSessionRegistry.java   # ConcurrentHashMap session store + subscriptions + filters

src/main/resources/
├── application.yaml                    # Dev defaults (Kafka, topics, CORS, log-files: {})
└── application-prod.yaml               # Prod overrides (no defaults, log-files from env vars, actuator enabled)
```
