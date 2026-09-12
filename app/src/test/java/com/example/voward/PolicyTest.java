package com.example.voward;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PolicyTest {

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;

    @Test
    public void decisionGateRoundsUpAndSaturates() {
        assertEquals(0, DecisionGatePolicy.remainingSeconds(1_000, 1_000));
        assertEquals(0, DecisionGatePolicy.remainingSeconds(1_000, 2_000));
        assertEquals(1, DecisionGatePolicy.remainingSeconds(1_001, 1_000));
        assertEquals(1, DecisionGatePolicy.remainingSeconds(1_999, 1_000));
        assertEquals(2, DecisionGatePolicy.remainingSeconds(2_000, 0));
        assertEquals(Integer.MAX_VALUE,
                DecisionGatePolicy.remainingSeconds(Long.MAX_VALUE, 0));
    }

    @Test
    public void safetyPolicyProtectsCoreOwnAndRecoveryPackages() {
        Set<String> resolved = Set.of("vendor.phone.dialer", "com.oem.settings");
        assertTrue(SafetyPolicy.isCriticalPackage(null, "com.example.voward", resolved));
        assertTrue(SafetyPolicy.isCriticalPackage(" ", "com.example.voward", resolved));
        assertTrue(SafetyPolicy.isCriticalPackage(
                "COM.EXAMPLE.VOWARD", "com.example.voward", resolved));
        assertTrue(SafetyPolicy.isCriticalPackage(
                "com.android.settings", "com.example.voward", resolved));
        assertTrue(SafetyPolicy.isCriticalPackage(
                " VENDOR.PHONE.DIALER ", "com.example.voward", resolved));
        assertTrue(SafetyPolicy.isCriticalPackage(
                "com.oem.settings", "com.example.voward", resolved));
        assertFalse(SafetyPolicy.isCriticalPackage(
                "com.example.social", "com.example.voward", resolved));
    }

    @Test
    public void safetyPolicyNoLongerBlocksLookAlikeNamesItCannotResolve() {
        // Substring matching made every one of these unprotectable while still missing the
        // OEM dialers it was meant to catch. Only the resolved names are critical now.
        Set<String> resolved = Set.of("com.google.android.dialer");
        assertFalse(SafetyPolicy.isCriticalPackage(
                "vendor.emergency.ui", "com.example.voward", resolved));
        assertFalse(SafetyPolicy.isCriticalPackage(
                "com.game.emergency", "com.example.voward", resolved));
        assertFalse(SafetyPolicy.isCriticalPackage(
                "com.chat.telecom", "com.example.voward", resolved));
        assertFalse(SafetyPolicy.isCriticalPackage(
                "com.social.dialer", "com.example.voward", resolved));
        assertFalse(SafetyPolicy.isCriticalPackage(
                "com.example.social", "com.example.voward", null));
        assertTrue(SafetyPolicy.isCriticalPackage(
                "com.google.android.dialer", "com.example.voward", null));
    }

    @Test
    public void deadMansSwitchCountsNothingUntilAHealthCheckIsRecorded() {
        assertEquals(0, DeadMansSwitchPolicy.elapsedSinceCheckMs(
                DeadMansSwitchPolicy.NO_CHECK, 5_000, 3, 100_000, 9_000, 3));
        assertEquals(0, DeadMansSwitchPolicy.elapsedSinceCheckMs(
                1_000, DeadMansSwitchPolicy.NO_CHECK, 3, 100_000, 9_000, 3));
    }

    @Test
    public void deadMansSwitchTakesWhicheverClockHasMovedFurtherWithinOneBoot() {
        // Uptime moved an hour, the wall clock a minute: uptime is the honest reading.
        assertEquals(HOUR_MS, DeadMansSwitchPolicy.elapsedSinceCheckMs(
                1_000, 1_000, 7, 1_000 + MINUTE_MS, 1_000 + HOUR_MS, 7));
        // And the other way round, so a suspended device is not a free pause either.
        assertEquals(HOUR_MS, DeadMansSwitchPolicy.elapsedSinceCheckMs(
                1_000, 1_000, 7, 1_000 + HOUR_MS, 1_000 + MINUTE_MS, 7));
    }

    @Test
    public void deadMansSwitchIgnoresAClockMovedBackwards() {
        // Never negative: a rolled-back clock must not credit time against the threshold.
        assertEquals(HOUR_MS, DeadMansSwitchPolicy.elapsedSinceCheckMs(
                10 * HOUR_MS, 1_000, 7, HOUR_MS, 1_000 + HOUR_MS, 7));
    }

    @Test
    public void deadMansSwitchFallsBackToTheWallClockAcrossAReboot() {
        // elapsedRealtime restarted, so only the wall clock carries across.
        assertEquals(3 * HOUR_MS, DeadMansSwitchPolicy.elapsedSinceCheckMs(
                1_000, 9 * HOUR_MS, 7, 1_000 + 3 * HOUR_MS, HOUR_MS, 8));
        // A boot count that could not be read is decided by elapsedRealtime going backwards.
        assertEquals(3 * HOUR_MS, DeadMansSwitchPolicy.elapsedSinceCheckMs(
                1_000, 9 * HOUR_MS, -1, 1_000 + 3 * HOUR_MS, HOUR_MS, -1));
    }

    @Test
    public void deadMansSwitchSaturatesRatherThanOverflowing() {
        assertEquals(Long.MAX_VALUE, DeadMansSwitchPolicy.elapsedSinceCheckMs(
                Long.MIN_VALUE + 1, 0, 7, Long.MAX_VALUE, 1_000, 7));
    }

    @Test
    public void deadMansSwitchExpiresOnlyAtTheClampedThreshold() {
        long fourteenDays = 14 * 24 * HOUR_MS;
        assertFalse(DeadMansSwitchPolicy.isExpired(fourteenDays - 1, 14));
        assertTrue(DeadMansSwitchPolicy.isExpired(fourteenDays, 14));
        // Out-of-range settings clamp rather than disable the switch.
        assertEquals(1, DeadMansSwitchPolicy.clampDays(0));
        assertEquals(90, DeadMansSwitchPolicy.clampDays(1_000));
        assertTrue(DeadMansSwitchPolicy.isExpired(24 * HOUR_MS, 0));
        assertFalse(DeadMansSwitchPolicy.isExpired(89 * 24 * HOUR_MS, 1_000));
    }

    @Test
    public void sessionEndsWhenEitherClockReachesTheDeadlineInOneBoot() {
        assertFalse(SessionDeadlinePolicy.hasExpired(
                5_000, 100_000, 7, 4_999, 99_999, 7));
        assertTrue(SessionDeadlinePolicy.hasExpired(
                5_000, 100_000, 7, 5_000, 99_999, 7));
        // A wall clock jumped forward ends the session early; that direction is free to take.
        assertTrue(SessionDeadlinePolicy.hasExpired(
                5_000, 100_000, 7, 4_999, 100_000, 7));
    }

    @Test
    public void sessionEndsAtAReboot() {
        // The reproduced leak: uptime was 2758 s when a five minute session started, so the
        // stored deadline reads ~51 minutes into the next boot and the alarm is gone.
        assertTrue(SessionDeadlinePolicy.hasExpired(
                3_054_247, Long.MAX_VALUE, 7, 12_000, 0, 8));
    }

    @Test
    public void sessionWithoutABootCountLeansOnTheWallClock() {
        assertFalse(SessionDeadlinePolicy.hasExpired(
                3_054_247, 100_000, SessionDeadlinePolicy.UNKNOWN_BOOT_COUNT,
                12_000, 99_999, 8));
        assertTrue(SessionDeadlinePolicy.hasExpired(
                3_054_247, 100_000, SessionDeadlinePolicy.UNKNOWN_BOOT_COUNT,
                12_000, 100_000, 8));
    }

    @Test
    public void sessionWithNeitherBootCountNorWallDeadlineKeepsTheOldReading() {
        // A record written before any of this was kept: the monotonic deadline is all there is.
        assertFalse(SessionDeadlinePolicy.hasExpired(
                5_000, 0, SessionDeadlinePolicy.UNKNOWN_BOOT_COUNT, 4_999, 0, -1));
        assertTrue(SessionDeadlinePolicy.hasExpired(
                5_000, 0, SessionDeadlinePolicy.UNKNOWN_BOOT_COUNT, 5_000, 0, -1));
    }
}
