package com.example.voward;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PolicyTest {

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

}
