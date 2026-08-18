package com.example.voward;

/** Small main-thread throttle based on monotonic time rather than the editable wall clock. */
final class MonotonicThrottle {
    private final long minimumIntervalMs;
    private long lastAcquiredAt = Long.MIN_VALUE;

    MonotonicThrottle(long minimumIntervalMs) {
        this.minimumIntervalMs = Math.max(0, minimumIntervalMs);
    }

    boolean acquire(long nowElapsed) {
        if (lastAcquiredAt != Long.MIN_VALUE
                && nowElapsed >= lastAcquiredAt
                && nowElapsed - lastAcquiredAt < minimumIntervalMs) {
            return false;
        }
        lastAcquiredAt = nowElapsed;
        return true;
    }

    void recordForcedRun(long nowElapsed) {
        lastAcquiredAt = nowElapsed;
    }
}
