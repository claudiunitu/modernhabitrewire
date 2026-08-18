package com.example.voward;

import org.junit.Test;

import static org.junit.Assert.*;

public class DeactivationPolicyEngineTest {
    private static final long HOUR = 60 * 60 * 1000L;
    private final DeactivationPolicyEngine engine = new DeactivationPolicyEngine();

    private DeactivationPolicyEngine.Request request() {
        return engine.createRequest(1_000_000, 50_000, 4, 24 * HOUR, HOUR);
    }

    @Test public void positiveCooldownMovesThroughAllStatesAtConsistentBoundaries() {
        DeactivationPolicyEngine.Request r = request();
        assertEquals(DeactivationPolicyEngine.State.COOLDOWN_PENDING,
                engine.evaluateRequest(r, 1_000_000, 50_000, 4).state);
        assertFalse(engine.canComplete(r, 1_000_000 + 24 * HOUR - 1,
                50_000 + 24 * HOUR - 1, 4));
        assertTrue(engine.canComplete(r, 1_000_000 + 24 * HOUR,
                50_000 + 24 * HOUR, 4));
        assertEquals(DeactivationPolicyEngine.State.EXPIRED,
                engine.evaluateRequest(r, 1_000_000 + 25 * HOUR,
                        50_000 + 25 * HOUR, 4).state);
    }

    @Test public void wallClockAloneNeverOpensWindowAndLargeShiftInvalidates() {
        assertEquals(DeactivationPolicyEngine.State.INVALIDATED,
                engine.evaluateRequest(request(), 1_000_000 + 24 * HOUR, 50_000, 4).state);
    }

    @Test public void smallClockCorrectionIsAccepted() {
        assertEquals(DeactivationPolicyEngine.State.COOLDOWN_PENDING,
                engine.evaluateRequest(request(), 1_000_000 + HOUR + 119_000,
                        50_000 + HOUR, 4).state);
    }

    @Test public void rebootOrUnavailableBootCountInvalidates() {
        assertEquals(DeactivationPolicyEngine.State.INVALIDATED,
                engine.evaluateRequest(request(), 1_000_100, 50_100, 5).state);
        assertEquals(DeactivationPolicyEngine.State.INVALIDATED,
                engine.evaluateRequest(request(), 1_000_100, 50_100, -1).state);
    }

    @Test public void disabledCooldownCreatesNoRequest() {
        assertNull(engine.createRequest(1, 1, 1, 0, HOUR));
    }

    @Test public void snapshotsAreImmutable() {
        DeactivationPolicyEngine.Request r = request();
        assertEquals(24 * HOUR, r.cooldownMs);
        assertEquals(HOUR, r.windowMs);
    }
}
