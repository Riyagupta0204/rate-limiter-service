package com.riya.ratelimiter.core;

/**
 * Immutable outcome of a single rate-limit decision.
 *
 * A Java {@code record} (Java 16+) auto-generates the constructor, getters
 * (accessors named {@code allowed()}, {@code remaining()}, ...), equals(),
 * hashCode() and toString(). Perfect for a small, read-only value object.
 *
 * @param allowed          whether the request may proceed
 * @param remaining        tokens left in the bucket after this decision
 * @param retryAfterMillis if rejected, how long until a retry could succeed (0 when allowed)
 */
public record RateLimitResult(boolean allowed, long remaining, long retryAfterMillis) {

    public static RateLimitResult allow(long remaining) {
        return new RateLimitResult(true, remaining, 0);
    }

    public static RateLimitResult deny(long remaining, long retryAfterMillis) {
        return new RateLimitResult(false, remaining, retryAfterMillis);
    }
}
