-- token_bucket.lua
-- Atomic token-bucket rate limiter. Runs entirely inside Redis, so the
-- read -> refill -> consume -> write sequence is ONE indivisible operation.
-- This is what makes the limiter correct across many app instances.
--
-- KEYS[1] = bucket key, e.g. "rl:{tenant}:{clientId}"
-- ARGV[1] = capacity        (max tokens / burst size)
-- ARGV[2] = refillRate       (tokens added per second)
-- ARGV[3] = now              (current time in milliseconds)
-- ARGV[4] = requested        (tokens this request costs)
-- ARGV[5] = ttlSeconds       (expire the key after this idle period)
--
-- Returns a 3-element array: { allowed(0|1), remaining, retryAfterMillis }

local capacity   = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local now        = tonumber(ARGV[3])
local requested  = tonumber(ARGV[4])
local ttlSeconds = tonumber(ARGV[5])

-- Read current state (tokens left + timestamp of last refill).
local state  = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])

-- First time we see this key: start with a full bucket.
if tokens == nil then
    tokens = capacity
    ts     = now
end

-- Lazily refill based on how much time has passed since we last touched it.
-- (No background job needed — we compute the refill on read.)
local elapsedMs = math.max(0, now - ts)
local refill    = (elapsedMs / 1000.0) * refillRate
tokens          = math.min(capacity, tokens + refill)
ts              = now

-- Try to consume.
local allowed = 0
if tokens >= requested then
    tokens  = tokens - requested
    allowed = 1
end

-- Persist new state and refresh the idle-expiry.
redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', ts)
redis.call('PEXPIRE', KEYS[1], ttlSeconds * 1000)

-- If we rejected the request, tell the caller how long until 1 request would fit.
local retryAfterMillis = 0
if allowed == 0 then
    local needed = requested - tokens
    retryAfterMillis = math.ceil((needed / refillRate) * 1000)
end

-- Redis converts Lua numbers in the reply to integers (truncating decimals),
-- so we floor/ceil deliberately to make the contract explicit.
return { allowed, math.floor(tokens), retryAfterMillis }
