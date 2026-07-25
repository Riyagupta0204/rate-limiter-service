package com.riya.ratelimiter.core;

/**
 * Contract for any rate-limiting algorithm.
 *
 * Today the only implementation is {@link TokenBucketRateLimiter}. Tomorrow a
 * SlidingWindowRateLimiter can implement this exact interface, and the callers
 * (controller/filter) won't change at all. That is the whole point of coding to
 * an interface — the "pluggable algorithms" story on your resume.
 */
public interface RateLimiter {

    /**
     * Attempt to consume {@code cost} tokens for {@code key}.
     *
     * @param key        unique bucket id, e.g. "rl:free:user-123"
     * @param capacity   max tokens (burst size)
     * @param refillRate tokens added per second (steady-state rate)
     * @param cost       tokens this request costs
     * @param ttlSeconds idle expiry for the bucket in Redis
     * @return the decision (allowed?, remaining tokens, retry-after)
     */
    RateLimitResult tryConsume(String key, long capacity, double refillRate,
                               long cost, long ttlSeconds);
}
