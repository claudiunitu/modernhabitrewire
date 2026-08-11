package com.example.voward;

/** Pure deadline arithmetic so rotation/restoration behavior can be unit tested. */
public final class DecisionGatePolicy {
    private DecisionGatePolicy() {}

    public static int remainingSeconds(long deadlineElapsed, long nowElapsed) {
        long remaining = Math.max(0, deadlineElapsed - nowElapsed);
        long roundedUp = (remaining + 999) / 1000;
        return (int) Math.min(Integer.MAX_VALUE, roundedUp);
    }
}
