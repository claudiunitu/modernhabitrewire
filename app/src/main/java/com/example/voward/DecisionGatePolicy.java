package com.example.voward;

/** Pure deadline arithmetic so rotation/restoration behavior can be unit tested. */
public final class DecisionGatePolicy {
    private DecisionGatePolicy() {}

    public static int remainingSeconds(long deadlineElapsed, long nowElapsed) {
        long remaining = Math.max(0, deadlineElapsed - nowElapsed);
        // Divide before adding the remainder so very large deadlines cannot overflow.
        long roundedUp = remaining / 1000 + (remaining % 1000 == 0 ? 0 : 1);
        return (int) Math.min(Integer.MAX_VALUE, roundedUp);
    }
}
