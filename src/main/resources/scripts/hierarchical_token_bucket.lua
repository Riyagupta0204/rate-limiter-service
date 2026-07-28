-- hierarchical_token_bucket.lua
-- Atomically check & debit MANY token buckets in ONE Redis round-trip.
-- ALL-OR-NOTHING: if ANY bucket lacks enough tokens, NOTHING is debited.
-- This prevents "token leaks" where one bucket is charged even though the
-- overall request is rejected by a different bucket.
--
-- KEYS[1..n] = the bucket keys (e.g. per-IP, per-user, per-tenant)
-- ARGV[1]    = now (milliseconds)
-- ARGV[2]    = ttlSeconds
-- ARGV[3]    = n  (number of buckets)
-- then, per bucket i: capacity, refillRate, cost   (3 values each)
--
-- Returns { allowed(0|1), failedIndex(0=none), remaining, retryAfterMillis }
-- (failedIndex is 1-based; the Java side maps it back to the failed key string.)

local now        = tonumber(ARGV[1])
local ttlSeconds = tonumber(ARGV[2])
local n          = tonumber(ARGV[3])

local refilled     = {}   -- tokens in each bucket AFTER lazy refill (before debit)
local failedIndex  = 0
local failRemaining = 0
local failRetry    = 0

-- Pass 1: refill + check every bucket. Do NOT write anything yet.
for i = 1, n do
    local base       = 3 + (i - 1) * 3
    local capacity   = tonumber(ARGV[base + 1])
    local refillRate = tonumber(ARGV[base + 2])
    local cost       = tonumber(ARGV[base + 3])

    local state  = redis.call('HMGET', KEYS[i], 'tokens', 'ts')
    local tokens = tonumber(state[1])
    local ts     = tonumber(state[2])
    if tokens == nil then
        tokens = capacity
        ts = now
    end

    local elapsedMs = math.max(0, now - ts)
    tokens = math.min(capacity, tokens + (elapsedMs / 1000.0) * refillRate)
    refilled[i] = tokens

    if failedIndex == 0 and tokens < cost then
        failedIndex   = i
        failRemaining = math.floor(tokens)
        local needed  = cost - tokens
        failRetry     = math.ceil((needed / refillRate) * 1000)
    end
end

-- Pass 2: all-or-nothing decision.
if failedIndex == 0 then
    -- Every bucket has room -> commit the debit to ALL of them.
    local minRemaining = -1
    for i = 1, n do
        local base = 3 + (i - 1) * 3
        local cost = tonumber(ARGV[base + 3])
        local remaining = refilled[i] - cost
        redis.call('HMSET', KEYS[i], 'tokens', remaining, 'ts', now)
        redis.call('PEXPIRE', KEYS[i], ttlSeconds * 1000)
        local r = math.floor(remaining)
        if minRemaining == -1 or r < minRemaining then
            minRemaining = r
        end
    end
    return { 1, 0, minRemaining, 0 }
else
    -- At least one bucket is short -> debit NOTHING.
    return { 0, failedIndex, failRemaining, failRetry }
end
