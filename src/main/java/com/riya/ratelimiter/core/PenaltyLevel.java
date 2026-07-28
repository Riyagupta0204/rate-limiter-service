package com.riya.ratelimiter.core;

/**
 * Escalating penalty levels based on how many times a client has been denied.
 *
 * NORMAL  → standard Retry-After from the token bucket
 * WARNING → 3+ violations: forced 30-second wait
 * PENALTY → 5+ violations: forced 5-minute wait
 * BOX     → 10+ violations: forced 1-hour ban
 *
 * Thresholds and the violation-window TTL are configurable in application.yml.
 */
public enum PenaltyLevel {

    NORMAL  (0,  0),
    WARNING (3,  30),
    PENALTY (5,  300),
    BOX     (10, 3600);

    private final int threshold;           // min violation count to reach this level
    private final long retryAfterSeconds;  // forced Retry-After at this level (0 = use bucket value)

    PenaltyLevel(int threshold, long retryAfterSeconds) {
        this.threshold = threshold;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }

    /** Return the highest level the violation count has crossed. */
    public static PenaltyLevel forCount(long count) {
        PenaltyLevel result = NORMAL;
        for (PenaltyLevel level : values()) {
            if (level.threshold > 0 && count >= level.threshold) {
                result = level;
            }
        }
        return result;
    }
}
