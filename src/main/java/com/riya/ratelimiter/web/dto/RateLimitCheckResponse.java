package com.riya.ratelimiter.web.dto;

/**
 * The JSON answer returned by POST /v1/rate-limit/check.
 *
 * Example:
 *   { "allowed": true, "remaining": 9, "retryAfterMillis": 0 }
 */
public record RateLimitCheckResponse(
        boolean allowed,
        long remaining,
        long retryAfterMillis
) {
}
