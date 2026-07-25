# Distributed Rate Limiter Service

A distributed **token-bucket rate limiter** built with **Spring Boot** and **Redis**, where the core "consume a token" operation runs as an **atomic Lua script inside Redis**. That means it stays correct even when the service is scaled horizontally across many instances behind a load balancer.

It is implemented **from scratch** (no `bucket4j` or similar library) to demonstrate how distributed rate limiting actually works under concurrency — which is the interesting, interview-worthy part.

---

## Why this exists

Public APIs must cap how many requests a client can make — to prevent abuse, protect downstream services, and control cost. The *algorithm* is the easy part. The hard part is keeping the counter correct when many requests hit **multiple app servers at the same time**. A naive `read → check → write` in application code lets two servers both see "1 token left" and both allow the request.

This project solves that by doing the whole check-and-decrement **atomically inside Redis** via a Lua script, so concurrent requests are serialized by Redis and the count can never be over-spent.

---

## Features

- ⚙️ **Token bucket algorithm** — permits short bursts up to a `capacity`, then a steady `refill-rate`.
- 🔒 **Atomic & distributed** — the check-and-consume is a single Redis Lua script; no locks, no race conditions across instances.
- 🌐 **Two ways to use it**
  - a **REST API** (`POST /v1/rate-limit/check`) that *reports* a decision, and
  - a **servlet filter** that *enforces* limits on protected endpoints (returns `429`).
- 👥 **Per-tenant limits** — e.g. `free` vs `premium`, configured in YAML, no code changes.
- 📊 **Standard HTTP semantics** — `429 Too Many Requests`, `Retry-After`, and `X-RateLimit-*` headers.
- ✅ **Tested against a real Redis** using Testcontainers.
- 🐳 **One-command run** with Docker Compose.
- 🧩 **Pluggable algorithms** — everything sits behind a `RateLimiter` interface, so a sliding-window strategy can be added without touching the API or filter.

---

## Architecture

```
                                   ┌───────────────────────────────────────┐
  POST /v1/rate-limit/check ─────▶ │ RateLimitController  ┐                 │
      (ask: allowed? -> JSON)      │                      │                 │
                                   │                      ├─▶ RateLimiter ──┼──┐
  GET /api/hello ────────────────▶ │ RateLimitFilter  ────┘  (interface)    │  │
      (enforce: 200 or 429)        │                                        │  │
                                   └───────────────────────────────────────┘  │
                                        TokenBucketRateLimiter                 │ EVALSHA (atomic)
                                                                               ▼
                                                                    ┌────────────────────┐
                                                                    │       Redis         │
                                                                    │  token_bucket.lua   │
                                                                    │  rl:{tenant}:{id}   │  ← shared by all instances
                                                                    └────────────────────┘
```

Both entry points call the same `RateLimiter`, which runs the same Lua script against the same Redis — so the logic lives in exactly one place.

---

## How it works — the token bucket

Each client gets a "bucket" of tokens stored in Redis (`rl:{tenant}:{clientId}`):

- The bucket holds at most **`capacity`** tokens (this is the **burst** size).
- Each request costs **`cost`** tokens (usually 1).
- Tokens refill at **`refill-rate`** per second, up to `capacity`.

Refill is **lazy** — computed on read, not by a background job:

```
tokensNow = min(capacity, tokensStored + secondsElapsed * refillRate)
```

The Lua script does `read → refill → check → consume → write` as **one atomic operation** inside Redis. Because Redis executes scripts single-threaded, concurrent requests are effectively serialized, so the bucket can never be over-drawn — even across many app instances.

---

## Tech stack

Java 17 · Spring Boot 3.3 · Redis 7 (+ Lua) · Docker & Docker Compose · JUnit 5 + Testcontainers · Maven

---

## Getting started

### Option A — Docker Compose (recommended, no Java/Maven needed)

```bash
docker compose up --build
```

This builds the app image, starts Redis + the app, and waits for Redis to be healthy. The API is then at **http://localhost:8080**.

### Option B — run locally for development

```bash
# 1. Start a Redis
docker run -d --name rl-redis -p 6379:6379 redis:7-alpine

# 2. Run the app
mvn spring-boot:run
```

---

## Try it

**1. The decision API** — ask whether a client is allowed:

```bash
curl -s -X POST http://localhost:8080/v1/rate-limit/check \
  -H "Content-Type: application/json" \
  -d '{"clientId":"user-123","tenant":"free"}'
# => {"allowed":true,"remaining":4,"retryAfterMillis":0}
```

**2. The enforced endpoint** — hammer a protected route and watch it start blocking (default limit = capacity 10):

```bash
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "req $i -> HTTP %{http_code}\n" \
    -H "X-Client-Id: user-123" http://localhost:8080/api/hello
done
# req 1..10 -> HTTP 200
# req 11    -> HTTP 429   (with a Retry-After header)
# req 12    -> HTTP 429
```

---

## Configuration

Limits live in `src/main/resources/application.yml`:

```yaml
ratelimiter:
  ttl-seconds: 3600          # idle buckets auto-expire from Redis
  default-limit:
    capacity: 10             # burst size
    refill-rate: 5           # tokens per second (steady rate)
    cost: 1                  # tokens per request
  tenants:
    free:    { capacity: 5,   refill-rate: 1,  cost: 1 }
    premium: { capacity: 100, refill-rate: 50, cost: 1 }
```

- **`capacity`** controls how big a burst you tolerate.
- **`refill-rate`** controls the sustained requests-per-second.

---

## Running tests

An integration test spins up a real Redis with Testcontainers and asserts that the 11th request in a burst is denied.

```bash
mvn verify        # requires Docker to be running
```

<details>
<summary>Troubleshooting: macOS + Colima</summary>

If Testcontainers can't find Docker, point it at Colima's socket:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
```
and create `~/.docker-java.properties` with `api.version=1.44`.
</details>

---

## Design decisions & trade-offs

- **Atomic Lua over app-side locking** — correctness across instances for free, no distributed lock needed.
- **Lazy refill over a background job** — no scheduler, scales to millions of buckets, idle buckets cost nothing (and expire via TTL).
- **Timestamp passed in from the app** — keeps the script deterministic (safe for Redis replication). Trade-off: relies on app-server clocks being roughly NTP-synced.
- **Hand-rolled instead of `bucket4j`** — the goal is to demonstrate the mechanism, not hide it behind a library.

## Future work

- Sliding-window algorithm (drop-in via the `RateLimiter` interface)
- GitHub Actions CI pipeline
- Prometheus/Micrometer metrics on allow/deny rates
- Redis clustering / failover for high availability
- Harden the Lua for a `refill-rate` of 0 (currently assumes rate > 0)

---

Built by [Riya Gupta](https://github.com/Riyagupta0204).
