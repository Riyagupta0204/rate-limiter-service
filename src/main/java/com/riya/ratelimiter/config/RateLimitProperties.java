package com.riya.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds the `ratelimiter.*` block from application.yml into Java objects.
 *
 * Spring reads the YAML at startup and fills these fields in. It uses
 * "relaxed binding", so a kebab-case key like `refill-rate` maps to the
 * camelCase field `refillRate`, and `default-limit` maps to `defaultLimit`.
 */
@Component
@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimitProperties {

    /** How long an idle bucket lives in Redis before it auto-deletes. */
    private long ttlSeconds = 3600;

    /** Used when a tenant has no specific entry in the tenants map. */
    private Limit defaultLimit = new Limit();

    /** Per-tenant overrides, keyed by tenant name (e.g. "free", "premium"). */
    private Map<String, Limit> tenants = new HashMap<>();

    /** Look up the limit for a tenant, falling back to the default. */
    public Limit limitFor(String tenant) {
        if (tenant == null) {
            return defaultLimit;
        }
        return tenants.getOrDefault(tenant, defaultLimit);
    }

    // --- getters & setters (Spring needs setters to inject the values) ---

    public long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }

    public Limit getDefaultLimit() { return defaultLimit; }
    public void setDefaultLimit(Limit defaultLimit) { this.defaultLimit = defaultLimit; }

    public Map<String, Limit> getTenants() { return tenants; }
    public void setTenants(Map<String, Limit> tenants) { this.tenants = tenants; }

    /** One set of limits: bucket size, refill speed, and cost per request. */
    public static class Limit {
        private long capacity = 10;
        private double refillRate = 5;
        private long cost = 1;

        public long getCapacity() { return capacity; }
        public void setCapacity(long capacity) { this.capacity = capacity; }

        public double getRefillRate() { return refillRate; }
        public void setRefillRate(double refillRate) { this.refillRate = refillRate; }

        public long getCost() { return cost; }
        public void setCost(long cost) { this.cost = cost; }
    }
}
