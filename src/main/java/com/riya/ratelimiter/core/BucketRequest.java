package com.riya.ratelimiter.core;

/**
 * One bucket to check in a hierarchical (multi-key) rate-limit call.
 *
 * @param key        the Redis bucket key, e.g. "rl:ip:1.2.3.4" or "rl:user:alice"
 * @param capacity   max tokens (burst size) for THIS bucket
 * @param refillRate tokens/second for THIS bucket
 * @param cost       tokens this request debits from THIS bucket
 */
public record BucketRequest(String key, long capacity, double refillRate, long cost) {
}
