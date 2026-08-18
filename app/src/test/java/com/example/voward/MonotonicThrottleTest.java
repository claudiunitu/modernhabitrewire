package com.example.voward;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MonotonicThrottleTest {
    @Test
    public void firstRunIsImmediateAndSubsequentRunsRespectTheInterval() {
        MonotonicThrottle throttle = new MonotonicThrottle(60_000);

        assertTrue(throttle.acquire(10_000));
        assertFalse(throttle.acquire(69_999));
        assertTrue(throttle.acquire(70_000));
    }

    @Test
    public void forcedRunsRestartTheIntervalAndClockRegressionFailsSafe() {
        MonotonicThrottle throttle = new MonotonicThrottle(60_000);

        throttle.recordForcedRun(100_000);
        assertFalse(throttle.acquire(159_999));
        assertTrue(throttle.acquire(99_999));
    }
}
