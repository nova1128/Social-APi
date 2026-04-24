# Social API — Spring Boot Microservice //Assignment//

## Tech Stack
- Java 17+,Spridokng Boot 4.x
- PostgreSQL(Docker)
- Redis(Docker)

## How to Run
1. Start Docker Desktop
2. Run `docker compose up -d`
3. Run the Spring Boot app from IntelliJ

## How I Guaranteed Thread Safety for Atomic Locks

### Horizontal Cap
I used Redis `INCR` command via `redisTemplate.opsForValue().increment()`.
Redis is single-threaded internally, meaning even 200 concurrent requests
are processed one at a time by Redis. Each request gets a unique incremented
value, so the counter never races. If the count exceeds 100, I decrement it
back and reject the request with HTTP 429.

### Cooldown Cap
I used Redis `SET NX EX` via `redisTemplate.opsForValue().setIfAbsent()`.
This is a single atomic command — it checks if the key exists AND sets it
with a TTL in one operation. There is no gap between the check and the set,
making it impossible for two threads to both pass the check simultaneously.

### Vertical Cap
Simple integer comparison on the depthLevel field before any Redis or
database operations are performed.

## API Endpoints
- POST /api/posts — Create a post
- POST /api/posts/{postId}/comments — Add a comment
- POST /api/posts/{postId}/like — Like a post

## Redis Key Structure
- `post:{id}:virality_score` — Virality score per post
- `post:{id}:bot_count` — Bot reply counter per post
- `cooldown:bot_{id}:human_{id}` — Cooldown between bot and human
- `user:{id}:pending_notifs` — Pending notification queue
- `notif_cooldown:user_{id}` — Notification throttle key