# Virality Engine — Spring Boot Microservice

A high-performance Spring Boot microservice acting as an API gateway and guardrail system.
Implements concurrent-safe bot interaction limits, real-time virality scoring, and smart-batched notifications — all backed by Redis as the distributed gatekeeper.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 17, Spring Boot 3.2 |
| Database | PostgreSQL 16 (source of truth) |
| Cache / Guardrails | Redis 7 (via Spring Data Redis + Lettuce) |
| Build | Maven |
| Containerisation | Docker Compose |

---

## Quick Start

### 1. Start infrastructure

```bash
docker-compose up -d
```

This starts PostgreSQL (port 5432) and Redis (port 6379) with health checks.

### 2. Run the application

```bash
mvn spring-boot:run
```

Spring Boot auto-creates all tables via JPA `ddl-auto=update`.

### 3. Import the Postman collection

Import `virality-engine.postman_collection.json` into Postman.
Run requests in order: seed data → posts → comments → guardrail tests.

---

## Project Structure

```
src/main/java/com/virality/
├── controller/
│   ├── PostController.java       # POST /api/posts, /comments, /like
│   └── UserController.java       # POST /api/users, /bots (seed endpoints)
├── service/
│   ├── GuardrailService.java     # All Redis atomic lock logic
│   ├── PostService.java          # Orchestrates guardrails + DB writes
│   └── NotificationService.java  # Smart-batching notification logic
├── scheduler/
│   └── NotificationSweeper.java  # @Scheduled CRON sweep
├── entity/                       # JPA entities: User, Bot, Post, Comment
├── repository/                   # Spring Data JPA repositories
├── dto/                          # Request/response DTOs
├── config/
│   └── RedisConfig.java          # RedisTemplate + Lua script bean
└── exception/
    └── GlobalExceptionHandler.java
```

---

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/users` | Create a user |
| `POST` | `/api/bots` | Create a bot |
| `POST` | `/api/posts` | Create a post (user or bot) |
| `GET` | `/api/posts` | List all posts |
| `GET` | `/api/posts/{id}` | Get post + virality score + bot count |
| `POST` | `/api/posts/{id}/comments` | Add comment (bot guardrails enforced) |
| `POST` | `/api/posts/{id}/like` | Like a post (+20 virality) |

### Example: Bot comment request body

```json
{
  "authorId": 1,
  "authorType": "BOT",
  "content": "Interesting take!",
  "depthLevel": 3,
  "targetUserId": 1
}
```

### Error responses

All guardrail violations return `429 Too Many Requests` with a descriptive message:

```json
{
  "status": 429,
  "message": "Horizontal cap reached: post 1 already has 100 bot replies",
  "timestamp": "2025-07-10T14:30:00"
}
```

---

## Redis Key Schema

| Key | Type | Purpose |
|---|---|---|
| `post:{id}:virality_score` | String (integer) | Running virality score |
| `post:{id}:bot_count` | String (integer) | Total bot replies on a post |
| `cooldown:bot_{b}:human_{h}` | String, TTL 10m | Bot-to-human interaction cooldown |
| `notif_cooldown:user_{id}` | String, TTL 15m | Per-user notification cooldown |
| `user:{id}:pending_notifs` | List | Queued notification strings |

---

## How Thread Safety is Guaranteed for the Atomic Locks

This is the most critical aspect of the implementation. All three guardrails use strictly atomic Redis operations — there is no Java-level synchronisation, no `synchronized` block, no `static` variable, no `HashMap`.

### The problem: check-then-act race conditions

A naive implementation would do:

```java
// WRONG — race condition
long count = Long.parseLong(redis.get("post:1:bot_count"));
if (count < 100) {
    redis.set("post:1:bot_count", count + 1); // Another thread can INCR between these two lines
    allowReply();
}
```

Under 200 concurrent threads, multiple threads can all read `99`, all pass the check, and all increment — resulting in a final count of 199+. This fails the assignment's spam test.

---

### Guardrail 1 — Horizontal Cap: Lua script via EVAL

**Implementation**: `GuardrailService.tryAcquireBotSlot()`

```lua
local key = KEYS[1]
local cap = tonumber(ARGV[1])
local current = redis.call('INCR', key)
if current > cap then
    redis.call('DECR', key)
    return 0
end
return 1
```

**Why this is atomic**: Redis is single-threaded in its command processing. `EVAL` sends the entire Lua script to the Redis server where it executes as a single indivisible unit — no other command from any other client can interleave between the `INCR` and the conditional `DECR`. Redis documentation guarantees this: *"Redis uses the same Lua interpreter to run all the commands. Also, Redis guarantees that a script is executed in an atomic way: no other script or Redis command will be executed while a script is being executed."*

Under 200 concurrent threads all calling `EVAL` simultaneously:
- Redis queues all 200 EVAL calls in its single-threaded event loop
- Each script increments, checks, and conditionally decrements — fully serialised
- Exactly 100 scripts see `current <= 100` and return 1 (allowed)
- Exactly 100 scripts see `current > 100`, roll back, and return 0 (rejected)

This is the correct solution. A Java `synchronized` block would only protect a single JVM instance and would fail in a distributed/multi-instance deployment. The Lua script works correctly across any number of application instances.

---

### Guardrail 2 — Cooldown Cap: SET NX EX

**Implementation**: `GuardrailService.tryAcquireCooldown()`

```java
Boolean set = redisTemplate.opsForValue()
    .setIfAbsent(key, "1", Duration.ofMinutes(10));
return Boolean.TRUE.equals(set);
```

This maps to a single Redis command: `SET key 1 NX EX 600`

**Why this is atomic**: `SET NX EX` is a single atomic Redis command. The "check-then-set" (does key exist? if not, create it) is not a two-step operation — it happens in one command on the server. Two threads cannot both find the key absent and both succeed; one will atomically set the key and return `true`, the other will find the key already present and return `false`.

The 10-minute TTL is set in the same command, eliminating any window between key creation and TTL assignment.

---

### Guardrail 3 — Vertical Cap: Stateless validation

**Implementation**: `GuardrailService.assertDepthAllowed()`

```java
if (depthLevel > 20) throw new ResponseStatusException(429, "Thread depth limit reached");
```

No Redis operation is required. The `depth_level` is an immutable property of the comment being created, supplied by the request. There is no shared counter to race on. The validation is a pure, stateless check.

---

### Redis-Postgres consistency on DB failure

There is a subtle consistency problem: the Redis `bot_count` is incremented before the Postgres write. If the database transaction fails (network error, constraint violation), the slot is "spent" in Redis but no comment exists in Postgres.

This is handled explicitly in `PostService.addBotComment()`:

```java
Comment saved;
try {
    saved = commentRepository.save(comment);
} catch (Exception e) {
    guardrailService.releaseBotSlot(postId);  // DECR the Redis counter
    throw e;
}
```

This ensures Redis and Postgres stay consistent: if the DB write fails, the acquired slot is released, and the count accurately reflects only successfully committed comments.

---

### Statelessness verification

The application holds **zero** in-memory state:
- No `static` counters
- No `HashMap` or `ConcurrentHashMap`
- No Spring bean with mutable instance fields for counts

All runtime state lives in Redis. The Spring Boot application can be horizontally scaled to N instances — all share the same Redis and will coordinate correctly through the atomic operations described above.

---

## Virality Scoring

| Interaction | Points | Redis operation |
|---|---|---|
| Bot reply | +1 | `INCR post:{id}:virality_score 1` |
| Human like | +20 | `INCR post:{id}:virality_score 20` |
| Human comment | +50 | `INCR post:{id}:virality_score 50` |

`INCR` with a delta is atomic in Redis — safe under any concurrency.

---

## Notification Engine

When a bot interacts with a human's post:

1. Check if `notif_cooldown:user_{id}` key exists in Redis
2. **If YES** (user was recently notified): push message to `user:{id}:pending_notifs` Redis List
3. **If NO**: log `[PUSH] Push Notification Sent to User X`, set 15-minute cooldown key

A `@Scheduled` task runs every **5 minutes** (simulating the 15-minute production sweep):
- Scans for all `user:*:pending_notifs` keys
- For each user, pops all messages, builds a summary, logs it
- Deletes the list key

Example console output:
```
[PUSH] Push Notification Sent to User 1: Bot ViralBot-Alpha replied to your post
[SWEEP] Summarized Push Notification for user 1: Bot ViralBot-Alpha replied to your post and 3 others interacted with your posts.
```

---

## Running the Concurrency Test

```bash
# Start Redis first
docker-compose up redis -d

# Run the test
mvn test -Dtest=HorizontalCapConcurrencyTest
```

Expected output:
```
Allowed: 100
Rejected: 100
Tests run: 1, Failures: 0, Errors: 0
```
