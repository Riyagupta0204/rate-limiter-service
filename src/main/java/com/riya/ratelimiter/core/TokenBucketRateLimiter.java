package com.riya.ratelimiter.core;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-bucket rate limiter backed by Redis + Lua scripts.
 *
 * All the real decision logic lives in the Lua scripts and runs atomically on
 * the Redis server. This class just builds arguments, runs the script, and maps
 * the reply into a result object. Thin on purpose.
 */
@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> tokenBucketScript;        // single-key
    private final RedisScript<List> hierarchicalScript;       // multi-key (Feature 2)

    public TokenBucketRateLimiter(StringRedisTemplate redisTemplate,
                                  @Qualifier("tokenBucketScript") RedisScript<List> tokenBucketScript,
                                  @Qualifier("hierarchicalTokenBucketScript") RedisScript<List> hierarchicalScript) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        this.hierarchicalScript = hierarchicalScript;
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitResult tryConsume(String key, long capacity, double refillRate,
                                      long cost, long ttlSeconds) {
        long now = System.currentTimeMillis();

        List<Long> reply = redisTemplate.execute(
                tokenBucketScript,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(now),
                String.valueOf(cost),
                String.valueOf(ttlSeconds)
        );

        long allowed = reply.get(0);
        long remaining = reply.get(1);
        long retryAfterMillis = reply.get(2);

        return allowed == 1
                ? RateLimitResult.allow(remaining)
                : RateLimitResult.deny(remaining, retryAfterMillis);
    }

    @Override
    @SuppressWarnings("unchecked")
    public HierarchicalResult tryConsumeAll(List<BucketRequest> buckets, long ttlSeconds) {
        long now = System.currentTimeMillis();

        // KEYS = every bucket key; ARGV = [now, ttl, n, (cap,rate,cost) per bucket...]
        List<String> keys = new ArrayList<>(buckets.size());
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(now));
        args.add(String.valueOf(ttlSeconds));
        args.add(String.valueOf(buckets.size()));
        for (BucketRequest b : buckets) {
            keys.add(b.key());
            args.add(String.valueOf(b.capacity()));
            args.add(String.valueOf(b.refillRate()));
            args.add(String.valueOf(b.cost()));
        }

        List<Long> reply = redisTemplate.execute(hierarchicalScript, keys, args.toArray());

        long allowed = reply.get(0);
        long failedIndex = reply.get(1);   // 1-based, 0 = none
        long remaining = reply.get(2);
        long retryAfterMillis = reply.get(3);

        String failedKey = failedIndex == 0 ? null : buckets.get((int) failedIndex - 1).key();
        return new HierarchicalResult(allowed == 1, failedKey, remaining, retryAfterMillis);
    }
}
