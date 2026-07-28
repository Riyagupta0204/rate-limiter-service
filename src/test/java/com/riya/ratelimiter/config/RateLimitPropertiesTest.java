package com.riya.ratelimiter.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast unit test for cost resolution (Feature 1). No Spring, no Redis, no Docker —
 * it just exercises the pure {@code costFor(...)} logic, so it runs in `mvn test`.
 */
class RateLimitPropertiesTest {

    private RateLimitProperties.RouteCost route(String pattern, String method, long cost) {
        RateLimitProperties.RouteCost r = new RateLimitProperties.RouteCost();
        r.setPattern(pattern);
        r.setMethod(method);
        r.setCost(cost);
        return r;
    }

    @Test
    void firstMatchingRouteWinsElseDefault() {
        RateLimitProperties props = new RateLimitProperties();
        props.setDefaultCost(1);
        props.setRoutes(List.of(
                route("/api/search/**", null, 5),
                route("/api/heavy/**", null, 50)
        ));

        assertThat(props.costFor("GET", "/api/search/results")).isEqualTo(5);
        assertThat(props.costFor("POST", "/api/heavy/job")).isEqualTo(50);
        assertThat(props.costFor("GET", "/api/hello")).isEqualTo(1); // no rule -> default
    }

    @Test
    void methodIsHonouredWhenSpecified() {
        RateLimitProperties props = new RateLimitProperties();
        props.setRoutes(List.of(route("/api/x/**", "POST", 9)));

        assertThat(props.costFor("POST", "/api/x/1")).isEqualTo(9);
        assertThat(props.costFor("GET", "/api/x/1")).isEqualTo(1); // method mismatch -> default
    }
}
