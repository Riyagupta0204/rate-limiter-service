package com.riya.ratelimiter.core;

import java.util.List;

/**
 * Contract for any rate-limiting algorithm.
 *
 * Today the only implementation is {@link TokenBucketRateLimiter}. A
 * SlidingWindowRateLimiter could implement this exact interface and the callers
 * wouldn't change — that's the point of coding to an interface.
 */
public interface RateLimiter {

    /**
     * Attempt to consume {@code cost} tokens from a single bucket.
     */
    RateLimitResult tryConsume(String key, long capacity, double refillRate,
                               long cost, long ttlSeconds);

    /**
     * Feature 2: atomically check & debit MANY buckets at once (e.g. per-IP + per-user).
     * All-or-nothing: if any bucket is short, NONE are debited and {@code failedKey}
     * names the offending bucket.
     */
    HierarchicalResult tryConsumeAll(List<BucketRequest> buckets, long ttlSeconds);
}
