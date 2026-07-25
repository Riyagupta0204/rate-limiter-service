package com.riya.ratelimiter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The JSON body a caller sends to POST /v1/rate-limit/check.
 *
 * Example:
 *   { "clientId": "user-123", "tenant": "free" }
 *
 * @param clientId who is being rate-limited (required)
 * @param tenant   which limit set to apply ("free"/"premium"); optional — null uses the default
 */
public record RateLimitCheckRequest(
        @NotBlank String clientId,
        String tenant
) {
}
