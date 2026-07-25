package com.riya.ratelimiter.web;

/**
 * Builds the Redis bucket key from a tenant + client id, so the controller and
 * the filter produce identical keys (avoids subtle "why are my two entry points
 * using different buckets?" bugs). Small, shared, single source of truth.
 */
final class KeyBuilder {

    private KeyBuilder() { }

    static String bucketKey(String tenant, String clientId) {
        String t = (tenant == null || tenant.isBlank()) ? "default" : tenant;
        return "rl:" + t + ":" + clientId;
    }
}
