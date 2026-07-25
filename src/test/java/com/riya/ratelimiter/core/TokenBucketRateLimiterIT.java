package com.riya.ratelimiter.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test against a REAL Redis (started in Docker by Testcontainers).
 * This proves the limiter actually works end-to-end — not against a mock.
 *
 * The class name ends in "IT" so Maven's failsafe plugin runs it during
 * `mvn verify`. Requires Docker to be running.
 */
@SpringBootTest
@Testcontainers
class TokenBucketRateLimiterIT {

    // Spin up a throwaway Redis container for the test.
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    // Point Spring's Redis config at the container's dynamic host/port.
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    RateLimiter rateLimiter;

    @Test
    void allowsUpToCapacityThenDenies() {
        String key = "rl:test:user-1";
        long capacity = 10;       // bucket starts full with 10 tokens
        double refillRate = 1;    // 1 token/sec — negligible during this fast test
        long cost = 1;            // each request costs 1 token
        long ttl = 60;

        // The first 10 requests drain the full bucket — all allowed.
        for (int i = 1; i <= 10; i++) {
            RateLimitResult result = rateLimiter.tryConsume(key, capacity, refillRate, cost, ttl);
            assertThat(result.allowed())
                    .as("request #" + i + " should be allowed")
                    .isTrue();
        }

        // The 11th request finds an empty bucket — denied.
        RateLimitResult eleventh = rateLimiter.tryConsume(key, capacity, refillRate, cost, ttl);
        assertThat(eleventh.allowed()).isFalse();
        assertThat(eleventh.remaining()).isZero();
        assertThat(eleventh.retryAfterMillis()).isPositive(); // tells the client when to retry
    }
}
