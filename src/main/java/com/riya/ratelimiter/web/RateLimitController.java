package com.riya.ratelimiter.web;

import com.riya.ratelimiter.config.RateLimitProperties;
import com.riya.ratelimiter.core.RateLimiter;
import com.riya.ratelimiter.core.RateLimitResult;
import com.riya.ratelimiter.web.dto.RateLimitCheckRequest;
import com.riya.ratelimiter.web.dto.RateLimitCheckResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "rate limiter as a service" API. A caller asks "is this key allowed?"
 * and gets back the decision as JSON. It always returns HTTP 200 — the caller
 * decides what to do with `allowed: false`. (Compare with the filter, which
 * enforces the limit by returning 429.)
 */
@RestController
@RequestMapping("/v1/rate-limit")
public class RateLimitController {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;

    public RateLimitController(RateLimiter rateLimiter, RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @PostMapping("/check")
    public RateLimitCheckResponse check(@Valid @RequestBody RateLimitCheckRequest request) {
        // 1) Which limits apply? (per-tenant, falling back to the default)
        RateLimitProperties.Limit limit = properties.limitFor(request.tenant());

        // 2) Build the unique bucket id for this tenant + client.
        String key = KeyBuilder.bucketKey(request.tenant(), request.clientId());

        // 3) Ask the limiter (which runs the atomic Lua script in Redis).
        RateLimitResult result = rateLimiter.tryConsume(
                key,
                limit.getCapacity(),
                limit.getRefillRate(),
                limit.getCost(),
                properties.getTtlSeconds()
        );

        // 4) Translate the decision into the API response.
        return new RateLimitCheckResponse(
                result.allowed(),
                result.remaining(),
                result.retryAfterMillis()
        );
    }
}
