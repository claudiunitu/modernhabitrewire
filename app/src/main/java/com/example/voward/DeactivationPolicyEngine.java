package com.example.voward;

import java.util.UUID;

/** Pure state machine for delayed protection deactivation. */
public final class DeactivationPolicyEngine {
    public static final long CLOCK_SHIFT_TOLERANCE_MS = 2 * 60 * 1000L;

    public enum State { NO_REQUEST, COOLDOWN_PENDING, WINDOW_OPEN, EXPIRED, INVALIDATED }

    public static final class Request {
        public final String id;
        public final long wallTimeMs;
        public final long elapsedRealtimeMs;
        public final int bootCount;
        public final long cooldownMs;
        public final long windowMs;

        public Request(String id, long wallTimeMs, long elapsedRealtimeMs, int bootCount,
                       long cooldownMs, long windowMs) {
            this.id = id;
            this.wallTimeMs = wallTimeMs;
            this.elapsedRealtimeMs = elapsedRealtimeMs;
            this.bootCount = bootCount;
            this.cooldownMs = cooldownMs;
            this.windowMs = windowMs;
        }
    }

    public static final class Evaluation {
        public final State state;
        public final long remainingMs;

        private Evaluation(State state, long remainingMs) {
            this.state = state;
            this.remainingMs = Math.max(0, remainingMs);
        }
    }

    public Request createRequest(long wallTimeMs, long elapsedRealtimeMs, int bootCount,
                                 long cooldownMs, long windowMs) {
        if (cooldownMs <= 0 || windowMs <= 0 || bootCount < 0) return null;
        return new Request(UUID.randomUUID().toString(), wallTimeMs, elapsedRealtimeMs,
                bootCount, cooldownMs, windowMs);
    }

    public Evaluation evaluateRequest(Request request, long wallTimeMs,
                                      long elapsedRealtimeMs, int bootCount) {
        if (request == null) return new Evaluation(State.NO_REQUEST, 0);
        long realProgress = elapsedRealtimeMs - request.elapsedRealtimeMs;
        long wallProgress = wallTimeMs - request.wallTimeMs;
        if (bootCount < 0 || request.bootCount < 0 || bootCount != request.bootCount
                || realProgress < 0
                || exceedsTolerance(wallProgress, realProgress)) {
            return new Evaluation(State.INVALIDATED, 0);
        }
        if (realProgress < request.cooldownMs) {
            return new Evaluation(State.COOLDOWN_PENDING, request.cooldownMs - realProgress);
        }
        long windowProgress = realProgress - request.cooldownMs;
        if (windowProgress < request.windowMs) {
            return new Evaluation(State.WINDOW_OPEN, request.windowMs - windowProgress);
        }
        return new Evaluation(State.EXPIRED, 0);
    }

    public boolean canComplete(Request request, long wallTimeMs,
                               long elapsedRealtimeMs, int bootCount) {
        return evaluateRequest(request, wallTimeMs, elapsedRealtimeMs, bootCount).state
                == State.WINDOW_OPEN;
    }

    private static boolean exceedsTolerance(long wallProgress, long realProgress) {
        long difference;
        try {
            difference = Math.subtractExact(wallProgress, realProgress);
        } catch (ArithmeticException overflow) {
            return true;
        }
        return difference == Long.MIN_VALUE || Math.abs(difference) > CLOCK_SHIFT_TOLERANCE_MS;
    }
}
