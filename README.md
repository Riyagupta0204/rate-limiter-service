# Distributed Rate Limiter Service

A distributed **token-bucket rate limiter** built with **Spring Boot** and **Redis**, where the core check-and-consume operation runs as an **atomic Lua script inside Redis** — so it stays correct even when the service is scaled horizontally across many instances.

Built from scratch (no `bucket4j` or similar library) to demonstrate how distributed rate limiting actually works under concurrency.

---

## Why this exists

Public APIs must cap how many requests a client can make — to prevent abuse, protect downstream services, and control cost. The *algorithm* is the easy part. The hard part is keeping the counter correct when many requests hit **multiple app servers at the same time**. A naive `read → check → write` in application code lets two servers both see "1 token left" and both allow the request.

This project solves that by doing the whole check-and-decrement **atomically inside Redis** via a Lua script, so concurrent requests are serialized by Redis and the count can never be over-spent.

---

## Features

| # | Feature | What it does |
|---|---------|-------------|
| – | **Token bucket** | Bursts up to `capacity`, then steady `refill-rate` tokens/sec |
| – | **Atomic via Lua** | Check-and-consume is one Redis script; no race conditions across instances |
| – | **Per-tenant limits** | `free` / `premium` tiers configured in YAML |
| 1 | **Cost-weighted routes** | Expensive endpoints (e.g. `/api/search`) debit more tokens per call |
| 2 | **Hierarchical multi-key** | One request must satisfy an IP bucket AND a user bucket atomically (all-or-nothing) |
| 3 | **Shadow vs enforce mode** | `shadow` = compute decision + log/count it, but always allow — safe for dry-run rollout |
| 4 | **Fail-open / fail-closed** | On Redis error: `fail-open` = allow (availability first), `fail-closed` = 503 (protection first) |
| – | **Prometheus + Grafana** | Live dashboards for allow/deny rate, blocked %, latency p50/p95/p99, Redis errors |

---

## Architecture

```
                               ┌────────────────────────────────────────────┐
 POST /v1/rate-limit/check ──▶ │  RateLimitController                       │
   (report a decision)         │                     ┐                      │
                               │                     ├──▶ RateLimiter ──────┼──┐
 GET  /api/** ───────────────▶ │  RateLimitFilter ───┘    (interface)       │  │
   (enforce: 200 or 429)       │                                            │  │ EVALSHA (atomic)
                               └────────────────────────────────────────────┘  │
                                                                               ▼
                                                                 ┌─────────────────────┐
                                                                 │        Redis         │
                                                                 │  token_bucket.lua    │
                                                                 │  hierarchical_*.lua  │
                                                                 │  rl:{tenant}:{id}    │ ← shared across all instances
                                                                 └─────────────────────┘
```

```
 App (/actuator/prometheus)
      ↓  scraped every 5 s
 Prometheus  →  Grafana dashboard (localhost:3000)
```

---

## How it works — the token bucket

Each client gets a "bucket" stored in Redis (`rl:{tenant}:{clientId}`):

- Bucket holds at most **`capacity`** tokens (burst size).
- Each request costs **`cost`** tokens (default 1, configurable per route).
- Tokens refill at **`refill-rate`** per second up to `capacity`.

Refill is **lazy** — computed on read, not by a background job:

```
tokensNow = min(capacity, tokensStored + secondsElapsed × refillRate)
```

The Lua script does `read → refill → check → consume → write` as **one atomic operation**. Redis executes scripts single-threaded, so concurrent requests are serialized — the bucket can never be over-drawn.

---

## Tech stack

Java 17 · Spring Boot 3.3 · Redis 7 + Lua · Micrometer · Prometheus · Grafana · Docker Compose · JUnit 5 + Testcontainers · Maven

---

## Getting started

```bash
git clone https://github.com/Riyagupta0204/rate-limiter-service.git
cd rate-limiter-service
docker compose up --build
```

Four containers start: `redis`, `app` (port 8080), `prometheus` (port 9090), `grafana` (port 3000).

Wait ~20 seconds for startup, then verify:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

---

## Configuration

All limits live in `src/main/resources/application.yml`:

```yaml
ratelimiter:
  ttl-seconds: 3600          # idle buckets auto-expire from Redis

  default-limit:
    capacity: 10             # max tokens (burst size)
    refill-rate: 5           # tokens added per second (steady rate)
    cost: 1                  # tokens per request

  tenants:
    free:    { capacity: 5,   refill-rate: 1,  cost: 1 }
    premium: { capacity: 100, refill-rate: 50, cost: 1 }

  # Feature 1 — cost per route (first matching rule wins)
  default-cost: 1
  routes:
    - pattern: "/api/search/**"
      cost: 5
    - pattern: "/api/heavy/**"
      cost: 50

  # Feature 2 — hierarchical IP + user buckets (opt-in)
  hierarchical:
    enabled: false
    ip:   { capacity: 100, refill-rate: 50 }
    user: { capacity: 20,  refill-rate: 10 }

  # Feature 3 — enforce (real 429) or shadow (observe only, always allow)
  mode: enforce

  # Feature 4 — behaviour when Redis is unreachable
  on-redis-error: fail-open   # or fail-closed
```

---

## Test scenarios

All scenarios assume the stack is up (`docker compose up --build -d`) and the default config is unchanged unless stated.

> **Quick reset between tests:** restart the app to flush in-memory state, or wait for TTL expiry, or `docker compose exec redis redis-cli FLUSHALL`.

---

### Scenario 1 — Basic capacity exhaustion

Default: `capacity: 10`, `refill-rate: 5`, `cost: 1`.

```bash
for i in $(seq 1 13); do
  curl -s -o /dev/null -w "req $i -> %{http_code}\n" \
    -H "X-Client-Id: alice" http://localhost:8080/api/hello
done
```

Expected:
```
req 1  -> 200
...
req 10 -> 200   ← bucket empty after this
req 11 -> 429
req 12 -> 429
req 13 -> 429
```

Check the response headers on the last allowed request:

```bash
curl -s -D - -o /dev/null -H "X-Client-Id: alice" http://localhost:8080/api/hello | \
  grep -iE "X-RateLimit|Retry-After"
# X-RateLimit-Cost: 1
# X-RateLimit-Limit: 10
# X-RateLimit-Remaining: 9
```

---

### Scenario 2 — Token refill (lazy refill in action)

Send requests faster than the refill rate → see periodic 200s between 429s. This is **correct** — at `refill-rate: 5` (1 token every 200 ms), a new token appears roughly every 200 ms while the loop runs.

```bash
for i in $(seq 1 30); do
  curl -s -o /dev/null -w "req $i -> %{http_code}\n" \
    -H "X-Client-Id: refill-test" http://localhost:8080/api/hello
done
```

Expected pattern after bucket empties: `200, 429, 429, 429, 200, 429, 429, 429, 200, ...`
Each `200` is a freshly refilled token. The steady-state success rate equals `refill-rate` (5/sec).

To prove the steady rate is exactly 5/sec, slow down to match it:

```bash
for i in $(seq 1 10); do
  curl -s -o /dev/null -w "req $i -> %{http_code}\n" \
    -H "X-Client-Id: slow-test" http://localhost:8080/api/hello
  sleep 0.2   # 5 req/sec = exactly the refill rate → every request should pass
done
```

---

### Scenario 3 — Per-tenant limits

`free` tenant: `capacity: 5`, `premium` tenant: `capacity: 100`.

```bash
# Free tier — exhausted after 5 requests
for i in $(seq 1 7); do
  curl -s -o /dev/null -w "free  req $i -> %{http_code}\n" \
    -H "X-Client-Id: alice" -H "X-Tenant: free" http://localhost:8080/api/hello
done

# Premium tier — much larger bucket
for i in $(seq 1 7); do
  curl -s -o /dev/null -w "prem  req $i -> %{http_code}\n" \
    -H "X-Client-Id: alice" -H "X-Tenant: premium" http://localhost:8080/api/hello
done
```

Expected:
```
free  req 1..5 -> 200
free  req 6    -> 429   ← free bucket (capacity 5) exhausted
prem  req 1..7 -> 200   ← premium bucket (capacity 100) has room
```

---

### Scenario 4 — Feature 1: Cost-weighted routes

`/api/search` costs **5** tokens (bucket capacity is 10 → only 2 requests fit).
`/api/heavy` costs **50** tokens (exceeds capacity 10 → misconfiguration → 500).

```bash
# /api/search: costs 5 per call, capacity 10 -> 2 allowed then blocked
for i in $(seq 1 4); do
  curl -s -w "search req $i -> %{http_code}\n" -o /dev/null \
    -H "X-Client-Id: alice" http://localhost:8080/api/search
done
```

Expected:
```
search req 1 -> 200
search req 2 -> 200
search req 3 -> 429   ← 10 tokens used (2 × cost 5)
search req 4 -> 429
```

Check that the cost header is set:

```bash
curl -sI -H "X-Client-Id: alice" http://localhost:8080/api/search | grep X-RateLimit-Cost
# X-RateLimit-Cost: 5
```

Route with cost that exceeds bucket capacity:

```bash
curl -s http://localhost:8080/api/heavy
# {"error":"cost_exceeds_capacity","cost":50,"capacity":10}
# HTTP 500 — operator misconfiguration, not a 429
```

---

### Scenario 5 — Feature 2: Hierarchical multi-key limits

**Edit `application.yml`** — enable hierarchical with low capacities for easy testing:

```yaml
hierarchical:
  enabled: true
  ip:   { capacity: 10, refill-rate: 1 }
  user: { capacity: 3,  refill-rate: 1 }
```

Restart: `docker compose down && docker compose up --build -d`

**User bucket trips first** (capacity 3 < IP capacity 10):

```bash
for i in $(seq 1 5); do
  RESULT=$(curl -s -w "\n%{http_code}" \
    -H "X-User-Id: alice" -H "X-Forwarded-For: 1.2.3.4" \
    http://localhost:8080/api/hello)
  echo "alice req $i -> $(echo "$RESULT" | tail -1)  $(echo "$RESULT" | head -1)"
done
```

Expected:
```
alice req 1 -> 200  {"message":"hello"}
alice req 2 -> 200
alice req 3 -> 200
alice req 4 -> 429  {"error":"rate_limit_exceeded","failedKey":"rl:user:alice",...}
alice req 5 -> 429
```

**Each user gets their own user bucket** — bob starts fresh:

```bash
for i in $(seq 1 4); do
  curl -s -o /dev/null -w "bob req $i -> %{http_code}\n" \
    -H "X-User-Id: bob" -H "X-Forwarded-For: 1.2.3.4" http://localhost:8080/api/hello
done
```

Expected: `bob req 1..3 -> 200`, `bob req 4 -> 429`

**IP bucket is shared across users** — after alice (3 req) + bob (3 req) = 6 req from `1.2.3.4`, carol gets 4 more before the IP bucket (capacity 10) is exhausted:

```bash
for i in $(seq 1 6); do
  curl -s -o /dev/null -w "carol req $i -> %{http_code}\n" \
    -H "X-User-Id: carol" -H "X-Forwarded-For: 1.2.3.4" http://localhost:8080/api/hello
done
# carol req 1..4 -> 200   (4 tokens left in IP bucket after alice+bob used 6)
# carol req 5    -> 429   failedKey: rl:ip:1.2.3.4
```

Verify keys in Redis:

```bash
docker compose exec redis redis-cli KEYS "rl:*"
# "rl:user:alice"
# "rl:user:bob"
# "rl:user:carol"
# "rl:ip:1.2.3.4"
```

**Revert** `hierarchical.enabled: false` when done.

---

### Scenario 6 — Feature 3: Shadow mode (dry-run rollout)

**Edit `application.yml`**: set `mode: shadow`

Restart: `docker compose down && docker compose up --build -d`

```bash
# Send 15 requests — all return 200 even after bucket empties
for i in $(seq 1 15); do
  RESULT=$(curl -s -D - -o /dev/null -H "X-Client-Id: alice" http://localhost:8080/api/hello)
  HTTP=$(echo "$RESULT" | grep "^HTTP" | awk '{print $2}')
  SHADOW=$(echo "$RESULT" | grep -i "X-RateLimit-Shadow" | tr -d '\r')
  echo "req $i -> $HTTP  ${SHADOW:-}"
done
```

Expected:
```
req 1  -> 200
...
req 10 -> 200
req 11 -> 200  X-RateLimit-Shadow: would-deny   ← blocked by algorithm, but shadow lets it through
req 12 -> 200  X-RateLimit-Shadow: would-deny
...
```

Check the shadow counter in Prometheus:

```bash
curl -s http://localhost:8080/actuator/metrics/ratelimiter.shadow.would_deny | \
  python3 -m json.tool
```

**Revert** `mode: enforce` when done.

---

### Scenario 7 — Feature 4: Fail-open (Redis unavailable → allow)

Default config: `on-redis-error: fail-open`

```bash
# Stop Redis to simulate an outage
docker compose stop redis

# Requests still pass (fail-open = availability first)
for i in $(seq 1 3); do
  curl -s -o /dev/null -w "req $i -> %{http_code}\n" \
    -H "X-Client-Id: alice" http://localhost:8080/api/hello
done
# req 1 -> 200
# req 2 -> 200
# req 3 -> 200
```

Check that the error was counted:

```bash
curl -s http://localhost:8080/actuator/metrics/ratelimiter.redis.error | grep '"value"'
# "value" : 3.0   ← 3 Redis errors, but all were allowed through
```

Bring Redis back:

```bash
docker compose start redis
```

---

### Scenario 8 — Feature 4: Fail-closed (Redis unavailable → 503)

**Edit `application.yml`**: set `on-redis-error: fail-closed`

Restart: `docker compose down && docker compose up --build -d`

```bash
# Stop Redis
docker compose stop redis

# Requests are now rejected
for i in $(seq 1 3); do
  curl -s -w "req $i -> %{http_code}  " http://localhost:8080/api/hello
  curl -s http://localhost:8080/api/hello
  echo
done
# req 1 -> 503  {"error":"rate_limiter_unavailable"}
# req 2 -> 503
# req 3 -> 503
```

Bring Redis back and revert `on-redis-error: fail-open`:

```bash
docker compose start redis
```

---

### Scenario 9 — Decision API (report without enforcing)

The `POST /v1/rate-limit/check` endpoint reports a decision without enforcing it — useful as a library/sidecar integration.

```bash
# First call — allowed
curl -s -X POST http://localhost:8080/v1/rate-limit/check \
  -H "Content-Type: application/json" \
  -d '{"clientId":"user-123","tenant":"free"}'
# {"allowed":true,"remaining":4,"retryAfterMillis":0}

# Exhaust the free-tier bucket (capacity 5)
for i in $(seq 1 5); do
  curl -s -X POST http://localhost:8080/v1/rate-limit/check \
    -H "Content-Type: application/json" \
    -d '{"clientId":"user-123","tenant":"free"}' | python3 -m json.tool
done
# Last call: {"allowed":false,"remaining":0,"retryAfterMillis":...}
```

---

### Scenario 10 — Observability: Prometheus & Grafana

**Raw metrics** (Prometheus text format):

```bash
curl -s http://localhost:8080/actuator/prometheus | grep ratelimiter
```

You should see counters like:
```
ratelimiter_decisions_total{result="allow",...} 42.0
ratelimiter_decisions_total{result="deny",...}  8.0
ratelimiter_check_latency_seconds_bucket{...}
```

**Prometheus UI** — `http://localhost:9090`

Try these queries in the Prometheus expression browser:
```promql
# Live allow/deny rate
rate(ratelimiter_decisions_total[30s])

# Blocked percentage
100 * rate(ratelimiter_decisions_total{result="deny"}[30s])
    / sum without(result)(rate(ratelimiter_decisions_total[30s]))

# p99 Lua script latency
histogram_quantile(0.99,
  sum by(le)(rate(ratelimiter_check_latency_seconds_bucket[30s])))
```

**Grafana dashboard** — `http://localhost:3000`

Login: `admin` / `admin`

Navigate to **Dashboards → Rate Limiter Service**. Five panels auto-load:

| Panel | What to watch |
|-------|---------------|
| Allow vs Deny rate | Green/red lines diverge when you hammer a route |
| Shadow would-deny | Spikes when `mode: shadow` and bucket would be empty |
| Total checks & Blocked % | Percentage climbs toward 100% as you exhaust buckets |
| Lua latency p50/p95/p99 | Sub-millisecond under normal load; spikes on Redis stress |
| Redis errors / Fail-open/closed | Spikes when you `docker compose stop redis` |

Generate a traffic burst to see the graphs move:

```bash
for i in $(seq 1 100); do
  curl -s -o /dev/null -H "X-Client-Id: load-test" http://localhost:8080/api/hello
done
```

---

## Running automated tests

Integration tests spin up a real Redis with Testcontainers:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
mvn verify
```

Tests covered:
- `allowsUpToCapacityThenDenies` — 11th request denied
- `costWeightedRequestDebitsMultipleTokens` — cost 5, capacity 10 → 3rd request denied
- `hierarchicalDeniedRequestDebitsNoBucket` — all-or-nothing: big bucket untouched after hierarchical deny

<details>
<summary>Troubleshooting: macOS + Colima</summary>

If Testcontainers can't find Docker, point it at Colima's socket:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
```

Create `~/.docker-java.properties`:
```
DOCKER_HOST=unix:///Users/<you>/.colima/default/docker.sock
api.version=1.44
```
</details>

---

## Project structure

```
src/main/java/com/riya/ratelimiter/
  core/
    RateLimiter.java              ← strategy interface (pluggable algorithms)
    TokenBucketRateLimiter.java   ← executes Lua scripts against Redis
    RateLimitResult.java          ← single-key result
    HierarchicalResult.java       ← multi-key result (Feature 2)
    BucketRequest.java            ← per-bucket parameters for hierarchical call
  config/
    RateLimitProperties.java      ← binds application.yml → Java objects
    RedisConfig.java              ← loads Lua scripts as Spring beans
  web/
    filter/RateLimitFilter.java   ← enforces limits on /api/** (Features 1–4)
    RateLimitController.java      ← report-only decision API
    DemoController.java           ← test endpoints (/api/hello, /api/search, /api/heavy)

src/main/resources/
  application.yml
  scripts/
    token_bucket.lua              ← atomic single-key token bucket
    hierarchical_token_bucket.lua ← atomic multi-key all-or-nothing (Feature 2)

monitoring/
  prometheus.yml                  ← scrape config
  grafana/
    provisioning/                 ← auto-wires datasource + dashboard on startup
    dashboards/rate-limiter.json  ← 8-panel Grafana dashboard
```

---

## Design decisions & trade-offs

| Decision | Reason | Trade-off |
|----------|--------|-----------|
| Atomic Lua over app-side locking | Correct across instances for free, no distributed lock | Script runs on Redis main thread — keep it short |
| Lazy refill over background job | No scheduler, scales to millions of buckets, idle buckets cost nothing (TTL) | Relies on app clocks being NTP-synced |
| Timestamp passed from app | Keeps script deterministic, safe for Redis replication | Clock skew across app servers can cause minor inaccuracy |
| Shadow mode before enforce | Roll out new limits safely — observe blast radius in prod before blocking real users | Shadow metrics can lag; always monitor for at least one traffic cycle |
| Fail-open default | Availability beats protection for most web APIs | Wrong choice for security-critical rate limits — use fail-closed there |

---

Built by [Riya Gupta](https://github.com/Riyagupta0204).
