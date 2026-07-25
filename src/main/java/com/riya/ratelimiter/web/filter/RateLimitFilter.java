package com.riya.ratelimiter.web.filter;

import com.riya.ratelimiter.config.RateLimitProperties;
import com.riya.ratelimiter.core.RateLimiter;
import com.riya.ratelimiter.core.RateLimitResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Protects the service's own endpoints. It runs BEFORE the controller, checks
 * the limit, and either lets the request through or short-circuits with 429.
 *
 * OncePerRequestFilter guarantees it runs exactly once per request (even if the
 * request is internally forwarded).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;

    public RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

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

        // Identify the caller from headers; fall back to their IP if none given.
        String tenant = request.getHeader("X-Tenant");
        String clientId = request.getHeader("X-Client-Id");
        if (clientId == null || clientId.isBlank()) {
            clientId = request.getRemoteAddr();
        }

        RateLimitProperties.Limit limit = properties.limitFor(tenant);
        String key = "rl:" + (tenant == null || tenant.isBlank() ? "default" : tenant) + ":" + clientId;

        RateLimitResult result = rateLimiter.tryConsume(
                key,
                limit.getCapacity(),
                limit.getRefillRate(),
                limit.getCost(),
                properties.getTtlSeconds()
        );

        // Standard informational headers, sent on every response.
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit.getCapacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));

        if (result.allowed()) {
            filterChain.doFilter(request, response); // pass control to the next filter/controller
        } else {
            long retryAfterSeconds = Math.max(1, result.retryAfterMillis() / 1000);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value()); // 429
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"rate_limit_exceeded\",\"retryAfterMillis\":"
                            + result.retryAfterMillis() + "}");
            // NOTE: we do NOT call filterChain.doFilter -> the request stops here.
        }
    }
}
