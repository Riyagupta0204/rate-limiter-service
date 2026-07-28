package com.riya.ratelimiter.web.filter;

import com.riya.ratelimiter.config.RateLimitProperties;
import com.riya.ratelimiter.config.RateLimitProperties.FailMode;
import com.riya.ratelimiter.config.RateLimitProperties.Mode;
import com.riya.ratelimiter.core.BucketRequest;
import com.riya.ratelimiter.core.HierarchicalResult;
import com.riya.ratelimiter.core.PenaltyBoxService;
import com.riya.ratelimiter.core.PenaltyLevel;
import com.riya.ratelimiter.core.RateLimiter;
import com.riya.ratelimiter.core.RateLimitResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Protects the service's own endpoints. Runs BEFORE the controller and either
 * lets the request through or short-circuits with 429.
 *
 * Two modes:
 *   - single-key (default): one bucket per (tenant, client)
 *   - hierarchical (Feature 2, opt-in): several buckets (IP + user [+ tenant])
 *     checked atomically, all-or-nothing.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;
    private final PenaltyBoxService penaltyBoxService;

    public RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties,
                           MeterRegistry meterRegistry, PenaltyBoxService penaltyBoxService) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.penaltyBoxService = penaltyBoxService;
    }

    /** A rate-limit decision, decoupled from how the decision was reached (single vs hierarchical). */
    private record Decision(boolean allowed, long remaining, long retryAfterMillis, String failedKey) {}

    /** Only guard paths under /api/. Leaves the check API, actuator, etc. alone. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String tenant = request.getHeader("X-Tenant");
        String ip = resolveIp(request);
        String clientId = resolveUser(request, ip);

        logger.debug("rate-limit identity: ip=" + ip + " user=" + clientId + " tenant=" + tenant);

        // Feature 1: the cost is decided by the ROUTE being called.
        long cost = properties.costFor(request.getMethod(), request.getRequestURI());
        response.setHeader("X-RateLimit-Cost", String.valueOf(cost));

        // Feature 3 & 4: resolve per-route (or global) policy for this request.
        Mode mode = properties.modeFor(request.getMethod(), request.getRequestURI());
        FailMode failMode = properties.onRedisErrorFor(request.getMethod(), request.getRequestURI());

        if (properties.getHierarchical().isEnabled()) {
            handleHierarchical(request, response, filterChain, ip, clientId, tenant, cost, mode, failMode);
        } else {
            handleSingleKey(request, response, filterChain, tenant, clientId, cost, mode, failMode, clientId);
        }
    }

    /** Feature 2: check several buckets (IP + user [+ tenant]) atomically, all-or-nothing. */
    private void handleHierarchical(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain, String ip, String clientId, String tenant,
                                    long cost, Mode mode, FailMode failMode)
            throws IOException, ServletException {

        RateLimitProperties.Hierarchical h = properties.getHierarchical();
        List<BucketRequest> buckets = new ArrayList<>();
        buckets.add(new BucketRequest("rl:ip:" + ip,
                h.getIp().getCapacity(), h.getIp().getRefillRate(), cost));
        buckets.add(new BucketRequest("rl:user:" + clientId,
                h.getUser().getCapacity(), h.getUser().getRefillRate(), cost));
        if (h.isIncludeTenant() && tenant != null && !tenant.isBlank()) {
            RateLimitProperties.Limit tl = properties.limitFor(tenant);
            buckets.add(new BucketRequest("rl:tenant:" + tenant, tl.getCapacity(), tl.getRefillRate(), cost));
        }

        long minCapacity = buckets.stream().mapToLong(BucketRequest::capacity).min().orElse(Long.MAX_VALUE);
        if (cost > minCapacity) {
            writeCostExceedsCapacity(request, response, cost, minCapacity);
            return;
        }

        Decision decision;
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            HierarchicalResult result = rateLimiter.tryConsumeAll(buckets, properties.getTtlSeconds());
            decision = new Decision(result.allowed(), result.remaining(),
                    result.retryAfterMillis(), result.failedKey());
            sample.stop(meterRegistry.timer("ratelimiter.check.latency", "outcome", "success"));
        } catch (RuntimeException e) {
            sample.stop(meterRegistry.timer("ratelimiter.check.latency", "outcome", "error"));
            handleRedisError(request, response, filterChain, failMode, e);
            return;
        }
        applyDecision(request, response, filterChain, decision, mode, clientId);
    }

    /** Original single-bucket behaviour (one bucket per tenant + client). */
    private void handleSingleKey(HttpServletRequest request, HttpServletResponse response,
                                 FilterChain filterChain, String tenant, String clientId,
                                 long cost, Mode mode, FailMode failMode, String penaltyClientId)
            throws IOException, ServletException {

        RateLimitProperties.Limit limit = properties.limitFor(tenant);
        String key = "rl:" + (tenant == null || tenant.isBlank() ? "default" : tenant) + ":" + clientId;

        if (cost > limit.getCapacity()) {
            writeCostExceedsCapacity(request, response, cost, limit.getCapacity());
            return;
        }

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit.getCapacity()));

        Decision decision;
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            RateLimitResult result = rateLimiter.tryConsume(
                    key, limit.getCapacity(), limit.getRefillRate(), cost, properties.getTtlSeconds());
            decision = new Decision(result.allowed(), result.remaining(), result.retryAfterMillis(), null);
            sample.stop(meterRegistry.timer("ratelimiter.check.latency", "outcome", "success"));
        } catch (RuntimeException e) {
            sample.stop(meterRegistry.timer("ratelimiter.check.latency", "outcome", "error"));
            handleRedisError(request, response, filterChain, failMode, e);
            return;
        }
        applyDecision(request, response, filterChain, decision, mode, penaltyClientId);
    }

    /**
     * Turns a decision into an HTTP response, honouring Feature 3 (shadow vs enforce)
     * and the penalty box escalation.
     */
    private void applyDecision(HttpServletRequest request, HttpServletResponse response,
                               FilterChain filterChain, Decision decision, Mode mode, String clientId)
            throws IOException, ServletException {

        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (decision.failedKey() != null) {
            response.setHeader("X-RateLimit-Failed-Key", decision.failedKey());
        }

        if (decision.allowed()) {
            meterRegistry.counter("ratelimiter.decisions", "result", "allow").increment();
            filterChain.doFilter(request, response);
            return;
        }

        meterRegistry.counter("ratelimiter.decisions", "result", "deny").increment();

        if (mode == Mode.SHADOW) {
            meterRegistry.counter("ratelimiter.shadow.would_deny").increment();
            logger.info("SHADOW would-deny: " + request.getMethod() + " " + request.getRequestURI()
                    + (decision.failedKey() != null ? " failedKey=" + decision.failedKey() : ""));
            response.setHeader("X-RateLimit-Shadow", "would-deny");
            filterChain.doFilter(request, response);
            return;
        }

        // ENFORCE: record violation and escalate Retry-After via penalty box.
        PenaltyLevel penalty = penaltyBoxService.recordViolation(clientId);
        meterRegistry.counter("ratelimiter.penalty", "level", penalty.name()).increment();

        long retryAfterSeconds = penalty == PenaltyLevel.NORMAL
                ? Math.max(1, decision.retryAfterMillis() / 1000)
                : penalty.getRetryAfterSeconds();

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setHeader("X-RateLimit-Penalty-Level", penalty.name());
        response.setContentType("application/json");
        String failedKeyJson = decision.failedKey() != null
                ? "\"failedKey\":\"" + decision.failedKey() + "\","
                : "";
        response.getWriter().write("{\"error\":\"rate_limit_exceeded\"," + failedKeyJson
                + "\"penaltyLevel\":\"" + penalty.name() + "\""
                + ",\"retryAfterSeconds\":" + retryAfterSeconds + "}");
    }

    /**
     * Feature 4: Redis threw. Decide availability vs protection.
     *   FAIL_OPEN  -> let the request through (availability first)
     *   FAIL_CLOSED -> reject with 503 (protection first)
     */
    private void handleRedisError(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain, FailMode failMode, RuntimeException e)
            throws IOException, ServletException {

        meterRegistry.counter("ratelimiter.redis.error").increment();
        logger.warn("Redis error during rate-limit check (" + failMode + "): " + e.getMessage());

        if (failMode == FailMode.FAIL_OPEN) {
            meterRegistry.counter("ratelimiter.fail_open").increment();
            filterChain.doFilter(request, response);
        } else {
            meterRegistry.counter("ratelimiter.fail_closed").increment();
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limiter_unavailable\"}");
        }
    }

    /** First IP from X-Forwarded-For (set by reverse proxies/Docker), else the raw remote addr. */
    private String resolveIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** X-User-Id → X-Client-Id → IP fallback (so anonymous callers are still bucketed by IP). */
    private String resolveUser(HttpServletRequest request, String ip) {
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isBlank()) return userId;
        String clientId = request.getHeader("X-Client-Id");
        if (clientId != null && !clientId.isBlank()) return clientId;
        return ip;
    }

    private void writeCostExceedsCapacity(HttpServletRequest request, HttpServletResponse response,
                                          long cost, long capacity) throws IOException {
        logger.warn("Route cost " + cost + " exceeds capacity " + capacity
                + " for " + request.getMethod() + " " + request.getRequestURI()
                + " — check ratelimiter config");
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"cost_exceeds_capacity\",\"cost\":"
                + cost + ",\"capacity\":" + capacity + "}");
    }
}
