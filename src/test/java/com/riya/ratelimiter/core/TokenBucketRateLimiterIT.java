package com.riya.ratelimiter.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests against a REAL Redis (started in Docker by Testcontainers).
 * Requires Docker running. Class name ends in "IT" so failsafe runs it on `mvn verify`.
 */
@SpringBootTest
@Testcontainers
class TokenBucketRateLimiterIT {

    @Container
    static GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    }

    @Autowired
    RateLimiter rateLimiter;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void allowsUpToCapacityThenDenies() {
        String key = "rl:test:user-1";
        long capacity = 10;
        double refillRate = 1;
        long cost = 1;
        long ttl = 60;

        for (int i = 1; i <= 10; i++) {
            RateLimitResult result = rateLimiter.tryConsume(key, capacity, refillRate, cost, ttl);
            assertThat(result.allowed()).as("request #" + i + " should be allowed").isTrue();
        }

        RateLimitResult eleventh = rateLimiter.tryConsume(key, capacity, refillRate, cost, ttl);
        assertThat(eleventh.allowed()).isFalse();
        assertThat(eleventh.remaining()).isZero();
        assertThat(eleventh.retryAfterMillis()).isPositive();
    }

    @Test
    void costWeightedRequestDebitsMultipleTokens() {
        String key = "rl:test:cost-user";
        long capacity = 10;
        double refillRate = 1;   // negligible refill during this fast test
        long cost = 5;           // Feature 1: each request costs 5 tokens

        // capacity 10 / cost 5 -> only 2 requests fit, the 3rd is denied
        assertThat(rateLimiter.tryConsume(key, capacity, refillRate, cost, 60).allowed()).isTrue();
        assertThat(rateLimiter.tryConsume(key, capacity, refillRate, cost, 60).allowed()).isTrue();
        assertThat(rateLimiter.tryConsume(key, capacity, refillRate, cost, 60).allowed()).isFalse();
    }

    @Test
    void hierarchicalDeniedRequestDebitsNoBucket() {
        String big = "rl:test:big-bucket";
        String small = "rl:test:small-bucket";
        List<BucketRequest> buckets = List.of(
                new BucketRequest(big, 100, 1, 1),   // plenty of room
                new BucketRequest(small, 1, 1, 1)    // only 1 token
        );

        // 1st call: both buckets have room -> allowed (big: 100->99, small: 1->0)
        assertThat(rateLimiter.tryConsumeAll(buckets, 60).allowed()).isTrue();

        // 2nd call: the small bucket is empty -> DENIED, and it names the offender
        HierarchicalResult denied = rateLimiter.tryConsumeAll(buckets, 60);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.failedKey()).isEqualTo(small);
        assertThat(denied.retryAfterMillis()).isPositive();

        // ALL-OR-NOTHING: the big bucket must NOT have been debited on the denied call.
        // After only the 1 allowed call it should still read 99, not 98.
        Object bigTokens = redis.opsForHash().get(big, "tokens");
        assertThat(Double.parseDouble(String.valueOf(bigTokens))).isEqualTo(99.0);
    }
}
