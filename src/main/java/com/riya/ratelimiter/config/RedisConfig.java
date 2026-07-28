package com.riya.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Loads the Lua scripts once at startup and exposes them as reusable beans.
 * Spring Data Redis sends them to Redis with EVALSHA, so Redis caches them by
 * hash after the first call.
 */
@Configuration
public class RedisConfig {

    /** Single-bucket token bucket. */
    @Bean
    public RedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(List.class);
        return script;
    }

    /** Multi-bucket (hierarchical) token bucket — Feature 2. */
    @Bean
    public RedisScript<List> hierarchicalTokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/hierarchical_token_bucket.lua"));
        script.setResultType(List.class);
        return script;
    }
}
