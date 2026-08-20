package com.example.voward;

/**
 * Pure arithmetic for the transparent attention-budget model.
 *
 * <p>One budget second always buys one second of restricted use.  Friction changes
 * only the delay before re-entry; it never changes the price after a user has seen
 * the session terms.</p>
 */
public final class BudgetMath {
    private BudgetMath() {}

    public static long addDailyAllowancesBounded(long currentBalance, long dailyAllowance,
                                                  long elapsedDays, double carryCapDays) {
        if (dailyAllowance <= 0) return 0;
        long ceiling = safeRoundedProduct(dailyAllowance, Math.max(0, carryCapDays));
        long boundedBalance = clamp(currentBalance, 0, ceiling);
        if (elapsedDays <= 0) return boundedBalance;

        long grant;
        try {
            grant = Math.multiplyExact(dailyAllowance, elapsedDays);
        } catch (ArithmeticException overflow) {
            grant = Long.MAX_VALUE;
        }
        return clamp(addSignedDelta(boundedBalance, grant), 0, ceiling);
    }

    public static int calculateReentryWaitSeconds(int baseWaitSeconds, double growth,
                                                   int completedSessions) {
        int safeBase = Math.max(1, Math.min(3600, baseWaitSeconds));
        double safeGrowth = Double.isFinite(growth) ? Math.max(0, Math.min(1, growth)) : 0;
        int safeSessions = Math.max(0, completedSessions);
        // Every completed session adds the same percentage of the base pause. Keep the
        // base fixed so 10 seconds with 50% growth is 10, 15, 20, ... rather than a
        // logarithmic curve whose increment changes on every re-entry.
        double wait = safeBase * (1.0 + safeGrowth * safeSessions);
        if (!Double.isFinite(wait)) return 3600;
        return (int) Math.max(1, Math.min(3600, Math.round(wait)));
    }

    public static long quoteSessionSeconds(long remainingSeconds, long requestedSeconds) {
        if (remainingSeconds <= 0 || requestedSeconds <= 0) return 0;
        return Math.min(remainingSeconds, requestedSeconds);
    }

    public static long elapsedCostSeconds(long elapsedMillis) {
        return Math.max(0, elapsedMillis) / 1000L;
    }

    public static long addSignedDelta(long value, long delta) {
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException overflow) {
            return delta >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    public static long subtractCost(long balance, long nonNegativeCost) {
        long safeBalance = Math.max(0, balance);
        if (nonNegativeCost <= 0) return safeBalance;
        if (nonNegativeCost >= safeBalance) return 0;
        return safeBalance - nonNegativeCost;
    }

    private static long safeRoundedProduct(long value, double factor) {
        double result = value * factor;
        if (!Double.isFinite(result) || result >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.max(0, Math.round(result));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
