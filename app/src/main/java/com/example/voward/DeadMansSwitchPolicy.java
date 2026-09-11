package com.example.voward;

import java.util.concurrent.TimeUnit;

/**
 * Rung 3 of the escape ladder: if Voward stops reporting healthy for long enough, every
 * restriction releases itself.
 *
 * <p>Without it a crash loop or a bad update costs the user a factory reset. The clock
 * comparison therefore leans the other way from the budget engine: where an unverifiable
 * reading there means "grant nothing", here it means "count the larger of the two", because
 * firing early only ever hands the user their phone back.</p>
 */
final class DeadMansSwitchPolicy {

    static final int DEFAULT_DAYS = 14;
    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 90;

    /** No health check has been recorded yet. */
    static final long NO_CHECK = Long.MIN_VALUE;

    private DeadMansSwitchPolicy() {}

    /**
     * Time since the last health check, by whichever clock has moved further.
     *
     * <p>Within one boot, {@code elapsedRealtime} is authoritative and the wall clock is the
     * one the user can move; across a reboot it is the other way round. Taking the larger of
     * the two means neither a clock rollback nor a reboot can hold the switch open.</p>
     */
    static long elapsedSinceCheckMs(long lastWallMs, long lastElapsedMs, int lastBootCount,
                                    long nowWallMs, long nowElapsedMs, int nowBootCount) {
        if (lastWallMs == NO_CHECK || lastElapsedMs == NO_CHECK) return 0;
        long wallProgress = Math.max(0, saturatingSubtract(nowWallMs, lastWallMs));
        boolean sameBoot = nowElapsedMs >= lastElapsedMs
                && (nowBootCount < 0 || lastBootCount < 0 || nowBootCount == lastBootCount);
        if (!sameBoot) return wallProgress;
        return Math.max(wallProgress, saturatingSubtract(nowElapsedMs, lastElapsedMs));
    }

    static boolean isExpired(long elapsedMs, int thresholdDays) {
        return elapsedMs >= TimeUnit.DAYS.toMillis(clampDays(thresholdDays));
    }

    static int clampDays(int days) {
        return Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
    }

    private static long saturatingSubtract(long value, long amount) {
        try {
            return Math.subtractExact(value, amount);
        } catch (ArithmeticException overflow) {
            return value >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }
}
