package com.riya.ratelimiter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A throwaway endpoint that exists only to demonstrate the RateLimitFilter.
 * Because its path starts with /api/, the filter guards it: hammer it and you'll
 * get 429s once the bucket empties.
 */
@RestController
public class DemoController {

    @GetMapping("/api/hello")
    public Map<String, String> hello() {
        return Map.of("message", "Hello! This endpoint is protected by the rate limiter.");
    }

    /** Matches ratelimiter.routes "/api/search/**" -> costs 5 tokens per call. */
    @GetMapping("/api/search")
    public Map<String, String> search() {
        return Map.of("message", "Search results (this route costs 5 tokens).");
    }

    /** Matches "/api/heavy/**" -> costs 50 tokens; needs a big bucket (e.g. X-Tenant: premium). */
    @GetMapping("/api/heavy")
    public Map<String, String> heavy() {
        return Map.of("message", "Heavy job (this route costs 50 tokens).");
    }
}
