package com.riya.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds the `ratelimiter.*` block from application.yml into Java objects.
 * Uses "relaxed binding" so kebab-case keys like `refill-rate` map to
 * camelCase fields like `refillRate`.
 */
@Component
@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimitProperties {

    /** Matches Ant-style path patterns like "/api/heavy/**" against a request path. */
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** How long an idle bucket lives in Redis before it auto-deletes. */
    private long ttlSeconds = 3600;

    /** Used when a tenant has no specific entry in the tenants map. */
    private Limit defaultLimit = new Limit();

    /** Per-tenant overrides, keyed by tenant name (e.g. "free", "premium"). */
    private Map<String, Limit> tenants = new HashMap<>();

    // ---------- Feature 1: cost-weighted routes ----------

    /** Cost charged when no route rule matches (e.g. a cheap GET). */
    private long defaultCost = 1;

    /** Ordered cost rules — the FIRST rule that matches wins. */
    private List<RouteCost> routes = new ArrayList<>();

    // ---------- Feature 2: hierarchical multi-key limits ----------

    private Hierarchical hierarchical = new Hierarchical();

    // ---------- Penalty box ----------

    private PenaltyBox penaltyBox = new PenaltyBox();

    // ---------- Feature 3: shadow vs enforce ----------

    /** Global default. ENFORCE = real 429 on deny; SHADOW = log/count but always allow. */
    private Mode mode = Mode.ENFORCE;

    // ---------- Feature 4: behaviour when Redis is unreachable ----------

    /** Global default. FAIL_OPEN = allow on Redis error; FAIL_CLOSED = reject with 503. */
    private FailMode onRedisError = FailMode.FAIL_OPEN;

    /** How the limiter behaves on a deny decision. */
    public enum Mode { ENFORCE, SHADOW }

    /** How the limiter behaves when the Redis call throws. */
    public enum FailMode { FAIL_OPEN, FAIL_CLOSED }

    /** Look up the limit for a tenant, falling back to the default. */
    public Limit limitFor(String tenant) {
        if (tenant == null) {
            return defaultLimit;
        }
        return tenants.getOrDefault(tenant, defaultLimit);
    }

    /** The first route rule matching (method, path), or {@code null} if none match. */
    public RouteCost matchRoute(String method, String path) {
        for (RouteCost route : routes) {
            boolean methodMatches = route.getMethod() == null
                    || route.getMethod().isBlank()
                    || route.getMethod().equalsIgnoreCase(method);
            if (methodMatches && route.getPattern() != null
                    && PATH_MATCHER.match(route.getPattern(), path)) {
                return route;
            }
        }
        return null;
    }

    /**
     * How many tokens should a request to (method, path) cost?
     * Returns the first matching route rule's cost, else {@link #defaultCost}.
     */
    public long costFor(String method, String path) {
        RouteCost route = matchRoute(method, path);
        return route != null ? route.getCost() : defaultCost;
    }

    /** Feature 3: matched route's mode override, else the global default. */
    public Mode modeFor(String method, String path) {
        RouteCost route = matchRoute(method, path);
        return route != null && route.getMode() != null ? route.getMode() : mode;
    }

    /** Feature 4: matched route's Redis-error policy override, else the global default. */
    public FailMode onRedisErrorFor(String method, String path) {
        RouteCost route = matchRoute(method, path);
        return route != null && route.getOnRedisError() != null ? route.getOnRedisError() : onRedisError;
    }

    // --- getters & setters ---

    public long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }

    public Limit getDefaultLimit() { return defaultLimit; }
    public void setDefaultLimit(Limit defaultLimit) { this.defaultLimit = defaultLimit; }

    public Map<String, Limit> getTenants() { return tenants; }
    public void setTenants(Map<String, Limit> tenants) { this.tenants = tenants; }

    public long getDefaultCost() { return defaultCost; }
    public void setDefaultCost(long defaultCost) { this.defaultCost = defaultCost; }

    public List<RouteCost> getRoutes() { return routes; }
    public void setRoutes(List<RouteCost> routes) { this.routes = routes; }

    public Hierarchical getHierarchical() { return hierarchical; }
    public void setHierarchical(Hierarchical hierarchical) { this.hierarchical = hierarchical; }

    public PenaltyBox getPenaltyBox() { return penaltyBox; }
    public void setPenaltyBox(PenaltyBox penaltyBox) { this.penaltyBox = penaltyBox; }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public FailMode getOnRedisError() { return onRedisError; }
    public void setOnRedisError(FailMode onRedisError) { this.onRedisError = onRedisError; }

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

    /** A rule that assigns a token cost to requests matching a path (and optionally an HTTP method). */
    public static class RouteCost {
        private String pattern;   // Ant-style path pattern, e.g. "/api/heavy/**"
        private String method;    // optional; null/blank = any method
        private long cost = 1;
        private Mode mode;               // Feature 3: null = inherit global
        private FailMode onRedisError;   // Feature 4: null = inherit global

        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }

        public long getCost() { return cost; }
        public void setCost(long cost) { this.cost = cost; }

        public Mode getMode() { return mode; }
        public void setMode(Mode mode) { this.mode = mode; }

        public FailMode getOnRedisError() { return onRedisError; }
        public void setOnRedisError(FailMode onRedisError) { this.onRedisError = onRedisError; }
    }

    /** Escalating ban config: violation thresholds and per-level Retry-After durations. */
    public static class PenaltyBox {
        private boolean enabled = true;
        private int warningThreshold = 3;    // violations before WARNING (30s wait)
        private int penaltyThreshold = 5;    // violations before PENALTY (5min wait)
        private int boxThreshold = 10;       // violations before BOX (1hr wait)
        private long violationWindowSeconds = 3600;  // TTL; resets if client goes quiet

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getWarningThreshold() { return warningThreshold; }
        public void setWarningThreshold(int warningThreshold) { this.warningThreshold = warningThreshold; }

        public int getPenaltyThreshold() { return penaltyThreshold; }
        public void setPenaltyThreshold(int penaltyThreshold) { this.penaltyThreshold = penaltyThreshold; }

        public int getBoxThreshold() { return boxThreshold; }
        public void setBoxThreshold(int boxThreshold) { this.boxThreshold = boxThreshold; }

        public long getViolationWindowSeconds() { return violationWindowSeconds; }
        public void setViolationWindowSeconds(long violationWindowSeconds) { this.violationWindowSeconds = violationWindowSeconds; }
    }

    /** Feature 2 config: per-dimension buckets for IP + user (+ optional tenant). */
    public static class Hierarchical {
        private boolean enabled = false;
        private Limit ip = new Limit();     // per-IP bucket (capacity/refillRate; cost comes from the route)
        private Limit user = new Limit();   // per-user bucket
        private boolean includeTenant = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Limit getIp() { return ip; }
        public void setIp(Limit ip) { this.ip = ip; }

        public Limit getUser() { return user; }
        public void setUser(Limit user) { this.user = user; }

        public boolean isIncludeTenant() { return includeTenant; }
        public void setIncludeTenant(boolean includeTenant) { this.includeTenant = includeTenant; }
    }
}
