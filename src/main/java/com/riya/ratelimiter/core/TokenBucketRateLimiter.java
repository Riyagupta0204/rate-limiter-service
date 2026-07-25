package com.riya.ratelimiter.core;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token-bucket rate limiter backed by Redis + a Lua script.
 *
 * Notice how little logic is here: the actual decision (refill, check, consume)
 * all happens inside token_bucket.lua, atomically, on the Redis server. This
 * class just (1) builds the arguments, (2) runs the script, (3) translates the
 * reply into a RateLimitResult. Thin on purpose.
 */
@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> tokenBucketScript;

    public TokenBucketRateLimiter(StringRedisTemplate redisTemplate,
                                  RedisScript<List> tokenBucketScript) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitResult tryConsume(String key, long capacity, double refillRate,
                                      long cost, long ttlSeconds) {
        long now = System.currentTimeMillis();

        // Run the Lua script atomically inside Redis.
        //   KEYS = [key]
        //   ARGV = capacity, refillRate, now, cost, ttlSeconds  (Lua reads them as strings)
        List<Long> reply = redisTemplate.execute(
                tokenBucketScript,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(now),
                String.valueOf(cost),
                String.valueOf(ttlSeconds)
        );

        // reply = { allowed(0|1), remaining, retryAfterMillis }
        long allowed = reply.get(0);
        long remaining = reply.get(1);
        long retryAfterMillis = reply.get(2);

        return allowed == 1
                ? RateLimitResult.allow(remaining)
                : RateLimitResult.deny(remaining, retryAfterMillis);
    }
}
