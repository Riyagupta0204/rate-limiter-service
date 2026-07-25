package com.riya.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Loads token_bucket.lua once at startup and exposes it as a reusable bean.
 *
 * Under the hood Spring Data Redis sends the script to Redis with EVALSHA,
 * so Redis caches it by hash after the first call — we don't re-upload the
 * script text on every request.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(List.class); // the Lua script returns an array of numbers
        return script;
    }
}
