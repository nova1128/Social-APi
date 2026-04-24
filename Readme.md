# Social API — JavaDoc Reference

> Spring Boot social platform API with bot guardrails, virality scoring, and smart notifications.  
> Stack: Java 21 · Spring Boot · PostgreSQL · Redis · Lombok

## Table of Contents

- [SocialApiApplication](#socialApiapplication)
- [RedisConfig](#redisconfig)
- [Entities](#entities) — Post · Comment · User · Bot
- [Repositories](#repositories) — PostRepository · CommentRepository · BotRepository · UserRepository
- [PostController](#postcontroller)
- [GuardrailService](#guardrailservice)
- [ViralityService](#viralityservice)
- [NotificationService](#notificationservice)
- [NotificationSchedular](#notificationscheduler)

---

## SocialApiApplication

```
com.grid07.social_api.SocialApiApplication
```

The entry point. Nothing fancy here — it boots Spring and enables the scheduler so the 5-minute CRON job actually runs.

One thing worth noting: there's a manual `TimeZone.setDefault(TimeZone.getTimeZone("UTC"))` call before Spring starts. That's a workaround because PostgreSQL JDBC driver trips over `Asia/Calcutta` (the old JVM name for IST). Setting it to UTC globally avoids that silently breaking date storage.

---

### `main(String[] args)`

```
static void main(String[] args)
```

| | |
|---|---|
| **Purpose** | Bootstraps the entire Spring context |
| **Called by** | JVM on startup |
| **Side effect** | Sets JVM default timezone to UTC before Spring loads |
| **Returns** | void |

```java
TimeZone.setDefault(TimeZone.getTimeZone("UTC")); // must run before Spring
SpringApplication.run(SocialApiApplication.class, args);
```

---

## RedisConfig

```
com.grid07.social_api.config.RedisConfig
```

Sets up the `RedisTemplate` bean. The default Spring Redis template uses Java serialization for values, which stores binary gibberish in Redis and makes debugging a nightmare. This config switches everything to plain `String` serialization so keys and values are human-readable.

---

### `redisTemplate(RedisConnectionFactory factory)`

```
@Bean
RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory)
```

| | |
|---|---|
| **Purpose** | Creates the `RedisTemplate` bean used across all services |
| **Parameter** | `factory` — auto-injected by Spring from `application.properties` Redis config |
| **Returns** | A fully configured `RedisTemplate<String, String>` |
| **Serializers set** | Key, Value, HashKey, HashValue — all `StringRedisSerializer` |

All four services (`GuardrailService`, `ViralityService`, `NotificationService`, `NotificationSchedular`) inject this bean. If you ever need to store non-string values (e.g. JSON objects), you'd swap in `Jackson2JsonRedisSerializer` for the value serializer here.

---

## Entities

---

### Post

```
com.grid_07.social_api.entity.Post
@Entity · @Table(name = "posts")
```

Represents a post on the platform. Both humans and bots can create posts — there's no restriction at the entity level.

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` | Auto-generated PK |
| `authorId` | `Long` | ID of the author — could be a User ID or Bot ID |
| `authorType` | `String` | `"USER"` or `"BOT"` — used in guardrail checks |
| `content` | `String` | The actual post text |
| `createdAt` | `LocalDateTime` | Set server-side at creation time |

`authorType` is a plain string, not an enum. That's a potential improvement — an `AuthorType` enum would prevent typos like `"Bot"` or `"user"` from silently bypassing guardrails.

---

### Comment

```
com.grid_07.social_api.entity.Comment
@Entity · @Table(name = "comments")
```

Represents a reply to a post. The `depthLevel` field is what the vertical cap guardrail reads.

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` | Auto-generated PK |
| `postId` | `Long` | FK to the parent post (no JPA relation — stored as raw ID) |
| `authorId` | `Long` | ID of the commenter |
| `authorType` | `String` | `"USER"` or `"BOT"` |
| `content` | `String` | Comment text |
| `depthLevel` | `int` | Thread nesting level. Guardrail rejects if this exceeds 20 |
| `createdAt` | `LocalDateTime` | Set server-side at creation time |

`postId` is stored as a raw `Long` rather than a `@ManyToOne` JPA relationship. This means you won't get cascading or automatic joins — queries against comments need manual lookups.

---

### User

```
com.grid_07.social_api.entity.User
@Entity · @Table(name = "users")
```

Represents a human user account.

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` | Auto-generated PK |
| `username` | `String` | Display name |
| `isPremium` | `boolean` | Premium account flag — not used in current business logic yet |

`isPremium` exists but nothing reads it at the moment. Could be used later to give premium users higher notification priority or relaxed guardrail rules for their posts.

---

### Bot

```
com.grid_07.social_api.entity.Bot
@Entity · @Table(name = "bots")
```

Represents an AI bot actor on the platform.

| Field | Type | Notes |
|---|---|---|
| `id` | `Long` | Auto-generated PK |
| `name` | `String` | Display name of the bot |
| `personaDescription` | `String` | Free-text description of what the bot simulates |

---

## Repositories

All repositories follow the same pattern — they extend `JpaRepository<EntityType, Long>` and get CRUD + pagination for free. No custom queries are needed yet.

---

### PostRepository

```
com.grid07.social_api.repository.PostRepository
extends JpaRepository<Post, Long>
```

Used in `PostController` for:
- `save(post)` — persists a new post
- `findById(postId)` — fetches the post to check if its author is a human (for cooldown cap)
- `existsById(postId)` — validates post existence before liking

---

### CommentRepository

```
com.grid07.social_api.repository.CommentRepository
extends JpaRepository<Comment, Long>
```

Used in `PostController` for:
- `save(comment)` — persists the comment after all guardrails pass

---

### BotRepository

```
com.grid07.social_api.repository.BotRepository
extends JpaRepository<Bot, Long>
```

Currently not injected anywhere in the controller or services — but it's there for future use (e.g. validating that a bot ID actually exists before allowing it to comment).

---

### UserRepository

```
com.grid07.social_api.repository.UserRepository
```

**⚠️ Currently broken — see the fix at the top of this document.**

Should extend `JpaRepository<User, Long>`. Once fixed, it can be used to:
- Validate that a `userId` exists before sending notifications
- Look up `isPremium` for future business logic

---

## PostController

```
com.grid07.social_api.Controller.PostController
@RestController · @RequestMapping("/api/posts")
```

The only REST controller in the project. Handles all three endpoints. For bot comments, it runs the guardrail pipeline manually in sequence — if any step fails, it returns `429` immediately without touching the database.

---

### `createPost(Map<String, Object> body)`

```
POST /api/posts
ResponseEntity<Post> createPost(@RequestBody Map<String, Object> body)
```

| | |
|---|---|
| **Purpose** | Creates and saves a new post |
| **Auth required** | No |
| **Guardrails** | None — humans and bots post freely |

**Request body:**

```json
{
  "authorId": 1,
  "authorType": "USER",
  "content": "Hello world!"
}
```

**What it does internally:**

Builds a `Post` object from the map, stamps `createdAt` with the current time server-side, and calls `postRepository.save()`. Returns the saved entity (which now has its generated `id`).

**Response:** `200 OK` with the full saved `Post` JSON.

---

### `addComment(Long postId, Map<String, Object> body)`

```
POST /api/posts/{postId}/comments
ResponseEntity<?> addComment(@PathVariable Long postId, @RequestBody Map<String, Object> body)
```

| | |
|---|---|
| **Purpose** | Adds a comment to a post — with full guardrail enforcement for bots |
| **Path variable** | `postId` — the post being replied to |

**Request body:**

```json
{
  "authorId": 42,
  "authorType": "BOT",
  "content": "Great post!",
  "depthLevel": 3
}
```

**Bot comment pipeline (in order):**

```
1. checkVerticalCap(depthLevel)
        ↓ fail → 429 "depth exceeds 20"
2. tryIncrementBotCount(postId)
        ↓ fail → 429 "100 bot replies reached"
3. checkAndSetCooldown(botId, humanId)
        ↓ fail → 429 "bot cooldown active"
4. handleBotNotification(humanId, botName, postId)
5. onBotReply(postId)          ← virality +1
6. save(comment)               ← DB write
```

If the post author is another bot (not a human), steps 3 and 4 are skipped — cooldowns and notifications only apply to human post owners.

**Human comment path:** Skips everything above, just calls `onHumanComment(postId)` (+50 virality) and saves.

**Responses:**
- `200 OK` — saved `Comment` JSON
- `429 Too Many Requests` — rejection reason string (which cap was hit)

---

### `likePost(Long postId, Map<String, Object> body)`

```
POST /api/posts/{postId}/like
ResponseEntity<?> likePost(@PathVariable Long postId, @RequestBody Map<String, Object> body)
```

| | |
|---|---|
| **Purpose** | Registers a like and updates virality for human likes |
| **Path variable** | `postId` — the post being liked |

**Request body:**

```json
{
  "authorType": "USER"
}
```

**What it does:**

Checks the post exists first. If `authorType` is `"USER"`, calls `onHumanLike(postId)` (+20 virality). Bot likes are accepted but silently ignored for scoring purposes.

**Responses:**
- `200 OK` — confirmation string
- `404 Not Found` — post doesn't exist

---

## GuardrailService

```
com.grid07.social_api.service.GuardrailService
@Service
```

Handles all three bot protection rules. Every method here either talks to Redis or does a plain integer check. Nothing writes to PostgreSQL.

**Why Redis instead of `synchronized` blocks?**  
`synchronized` only works within a single JVM process. The moment you run two instances of this app behind a load balancer, shared Java locks break. Redis is an external process that serializes operations itself — so it works regardless of how many app instances are running.

---

### `tryIncrementBotCount(Long postId)`

```
boolean tryIncrementBotCount(Long postId)
```

| | |
|---|---|
| **Purpose** | Atomically increments the bot reply counter for a post and rejects if it hits 100 |
| **Redis key** | `post:{postId}:bot_count` |
| **Redis command** | `INCR` (then `DECR` on rollback) |
| **TTL** | None |

**How it works:**

```
Redis INCR post:{postId}:bot_count
        ↓
    count ≤ 100?  →  return true   (keep the increment)
    count > 100?  →  DECR          (undo)
                  →  return false  (reject)
```

Redis `INCR` is atomic. Even with 500 concurrent requests, each one gets a unique value back — there's no scenario where two threads both read 100 and both think they're under the cap.

| Returns | Meaning |
|---|---|
| `true` | Increment was accepted, proceed |
| `false` | Cap exceeded, reject the comment |

---

### `checkVerticalCap(int depthLevel)`

```
boolean checkVerticalCap(int depthLevel)
```

| | |
|---|---|
| **Purpose** | Checks if a comment is nested too deep |
| **Max depth** | 20 |
| **Redis** | Not used — pure in-memory check |
| **Cost** | ~0 (integer comparison) |

This runs first in the guardrail pipeline because it's the cheapest possible check. If the depth is already over 20, there's no reason to hit Redis at all.

| Returns | Meaning |
|---|---|
| `true` | Depth is fine, continue |
| `false` | Too deep, reject |

---

### `checkAndSetCooldown(Long botId, Long humanId)`

```
boolean checkAndSetCooldown(Long botId, Long humanId)
```

| | |
|---|---|
| **Purpose** | Blocks a bot from replying to the same human more than once in 10 minutes |
| **Redis key** | `cooldown:bot_{botId}:human_{humanId}` |
| **Redis command** | `SET NX EX` (setIfAbsent with TTL) |
| **TTL** | 10 minutes |

**How it works:**

`setIfAbsent(key, "1", Duration.ofMinutes(10))` maps to Redis `SET key 1 NX EX 600`. This is one atomic command — the check and the write happen together. There's no window between them where two threads could both see "key missing" and both set it.

```
Key doesn't exist → set it with 10 min TTL → return true  (first interaction, allow)
Key exists        → don't touch it         → return false (still in cooldown, reject)
```

After 10 minutes the key expires automatically — no cleanup needed.

| Returns | Meaning |
|---|---|
| `true` | No active cooldown, interaction allowed (key was just set) |
| `false` | Cooldown is active, reject |

---

### `getBotCount(Long postId)`

```
Long getBotCount(Long postId)
```

| | |
|---|---|
| **Purpose** | Reads the current bot reply count for a post |
| **Redis key** | `post:{postId}:bot_count` |
| **Redis command** | `GET` |
| **Returns** | Current count as `Long`, or `0` if the key doesn't exist yet |

Currently called in `PostController` only for logging after a cooldown rejection. Could be exposed as a monitoring endpoint later.

---

## ViralityService

```
com.grid07.social_api.service.ViralityService
@Service
```

Tracks how much genuine traction a post is getting via a weighted score stored in Redis. The scoring is intentionally lopsided — a single human comment (+50) outweighs 50 bot replies (+1 each) because bot engagement isn't real engagement.

**Redis key pattern:** `post:{postId}:virality_score`  
**No TTL** — scores accumulate indefinitely.

| Interaction | Points |
|---|---|
| Bot reply | +1 |
| Human like | +20 |
| Human comment | +50 |

---

### `onBotReply(Long postId)`

```
void onBotReply(Long postId)
```

| | |
|---|---|
| **Purpose** | Adds 1 point to the post's virality score after a bot reply passes all guardrails |
| **Redis command** | `INCRBY post:{postId}:virality_score 1` |
| **Called from** | `PostController.addComment()` — only after all 3 guardrails pass |

---

### `onHumanLike(Long postId)`

```
void onHumanLike(Long postId)
```

| | |
|---|---|
| **Purpose** | Adds 20 points when a human likes a post |
| **Redis command** | `INCRBY post:{postId}:virality_score 20` |
| **Called from** | `PostController.likePost()` — only when `authorType = "USER"` |

---

### `onHumanComment(Long postId)`

```
void onHumanComment(Long postId)
```

| | |
|---|---|
| **Purpose** | Adds 50 points when a human comments on a post |
| **Redis command** | `INCRBY post:{postId}:virality_score 50` |
| **Called from** | `PostController.addComment()` — takes the human branch (no guardrails) |

---

### `getViralityScore(Long postId)`

```
Long getViralityScore(Long postId)
```

| | |
|---|---|
| **Purpose** | Returns the current virality score for a post |
| **Redis command** | `GET post:{postId}:virality_score` |
| **Returns** | Score as `Long`, or `0` if the post has no interactions yet |

Not currently wired to any endpoint — useful to expose as `GET /api/posts/{postId}/score` later.

---

## NotificationService

```
com.grid07.social_api.service.NotificationService
@Service
```

Handles push notifications to human users when bots interact with their posts. The goal is to never spam — if a user was notified recently, additional bot interactions are queued instead and delivered as a single summary later.

**Two Redis structures used:**

| Key | Type | Purpose | TTL |
|---|---|---|---|
| `notif_cooldown:user_{id}` | String | "Was this user notified recently?" | 15 min |
| `user:{id}:pending_notifs` | List | Queue of notification messages | None (deleted on flush) |

---

### `handleBotNotification(Long userId, String botName, Long postId)`

```
void handleBotNotification(Long userId, String botName, Long postId)
```

| | |
|---|---|
| **Purpose** | Either sends a notification immediately or queues it, depending on cooldown state |
| **Called from** | `PostController.addComment()` after guardrails pass |
| **Parameters** | `userId` — the human post owner · `botName` — e.g. `"Bot_42"` · `postId` — for the message text |

**Decision logic:**

```
Does notif_cooldown:user_{userId} exist in Redis?
        │
   NO   └──→  Send notification now
               Set cooldown key with 15-min TTL
        │
   YES  └──→  Push message to user:{userId}:pending_notifs (Redis List)
```

"Send notification now" in this implementation means `log.info(...)`. In a real system, this is where you'd call your FCM / APNs / WebSocket push service.

---

### `flushPendingNotifications(Long userId)`

```
void flushPendingNotifications(Long userId)
```

| | |
|---|---|
| **Purpose** | Reads and clears the pending notification queue for a user, logs a summary |
| **Called from** | `NotificationSchedular.sweepPendingNotifications()` every 5 minutes |
| **Redis reads** | `LRANGE user:{userId}:pending_notifs 0 -1` |
| **Redis writes** | `DEL user:{userId}:pending_notifs` |

**Summary format:**

```
// 1 pending message:
"Bot_X replied to your post 7"

// Multiple pending messages:
"Bot_X and 3 others interacted with your posts."
```

Returns early with no log if the list is empty or the key doesn't exist.

---

### `extractBotName(String message)` *(private)*

```
private String extractBotName(String message)
```

| | |
|---|---|
| **Purpose** | Pulls the bot name out of a stored notification message string |
| **Input format** | `"Bot Bot_42 replied to your post 7"` |
| **Output** | `"Bot Bot_42"` |
| **Used by** | `flushPendingNotifications()` — for building the summary string |

Splits on `" replied"` and takes the left side. Wrapped in a try-catch so a malformed message string doesn't crash the sweep.

---

## NotificationScheduler

```
com.grid07.social_api.Schedular.NotificationSchedular
@Component
```

A background CRON component that runs every 5 minutes and sweeps all pending notification queues in Redis. It doesn't know which users have pending notifications ahead of time — it uses `redisTemplate.keys("user:*:pending_notifs")` to discover them dynamically.

> **Note on production use:** `KEYS` is a blocking Redis command that scans the entire keyspace. Fine for small datasets, but on a Redis instance with millions of keys it will cause latency spikes. The production-grade replacement is `SCAN` with a cursor, which iterates in chunks without blocking.

---

### `sweepPendingNotifications()`

```
@Scheduled(fixedRate = 300000)
void sweepPendingNotifications()
```

| | |
|---|---|
| **Purpose** | Finds all users with queued notifications and flushes them |
| **Runs every** | 5 minutes (300,000 ms) |
| **Redis command** | `KEYS user:*:pending_notifs` |
| **Calls** | `NotificationService.flushPendingNotifications(userId)` for each match |

**What it does step by step:**

```
1. Scan Redis for all keys matching "user:*:pending_notifs"
2. If none found → log "no pending notifications" → done
3. For each key:
     a. Parse userId from "user:{id}:pending_notifs"
     b. Call notificationService.flushPendingNotifications(userId)
        → reads the list, deletes it, logs the summary
```

The key parsing splits on `:` and reads `parts[1]`. This works for the current key format. If you ever change the prefix, update the split index accordingly.

---

## Redis Key Quick Reference

| Key | Type | Set by | Read by | TTL |
|---|---|---|---|---|
| `post:{id}:bot_count` | String (int) | `GuardrailService` | `GuardrailService` | None |
| `post:{id}:virality_score` | String (int) | `ViralityService` | `ViralityService` | None |
| `cooldown:bot_{id}:human_{id}` | String | `GuardrailService` | `GuardrailService` | 10 min |
| `notif_cooldown:user_{id}` | String | `NotificationService` | `NotificationService` | 15 min |
| `user:{id}:pending_notifs` | List | `NotificationService` | `NotificationSchedular` | None (DEL on flush) |
