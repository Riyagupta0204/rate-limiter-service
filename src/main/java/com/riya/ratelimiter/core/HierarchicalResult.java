package com.riya.ratelimiter.core;

/**
 * Outcome of a multi-key (hierarchical) rate-limit decision.
 *
 * @param allowed          were ALL buckets satisfied (and therefore debited)?
 * @param failedKey        if denied, which bucket ran out first (null when allowed)
 * @param remaining        tokens left in the binding bucket (min across buckets when allowed)
 * @param retryAfterMillis if denied, how long until the failed bucket could satisfy the request
 */
public record HierarchicalResult(boolean allowed, String failedKey, long remaining, long retryAfterMillis) {
}
