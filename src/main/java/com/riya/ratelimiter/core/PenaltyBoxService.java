package com.riya.ratelimiter.core;

import com.riya.ratelimiter.config.RateLimitProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Tracks how many times a client has been denied and escalates the Retry-After
 * the more they violate.
 *
 * Redis key: rl:violations:{clientId}  →  integer count
 * TTL is reset on every new violation (sliding window) — a client stays in the
 * penalty box as long as they keep hammering.
 */
@Service
public class PenaltyBoxService {

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    public PenaltyBoxService(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * Record one violation for the client and return their current penalty level.
     * TTL is refreshed on each call — keeps repeat offenders stuck in penalty.
     */
    public PenaltyLevel recordViolation(String clientId) {
        if (!properties.getPenaltyBox().isEnabled()) {
            return PenaltyLevel.NORMAL;
        }
        String key = "rl:violations:" + clientId;
        Long count = redisTemplate.opsForValue().increment(key);
        // Slide the expiry window forward on each violation
        redisTemplate.expire(key,
                Duration.ofSeconds(properties.getPenaltyBox().getViolationWindowSeconds()));
        return PenaltyLevel.forCount(count == null ? 0 : count);
    }

    /** Read current level without incrementing — useful for logging/debugging. */
    public PenaltyLevel currentLevel(String clientId) {
        String val = redisTemplate.opsForValue().get("rl:violations:" + clientId);
        if (val == null) return PenaltyLevel.NORMAL;
        return PenaltyLevel.forCount(Long.parseLong(val));
    }
}
