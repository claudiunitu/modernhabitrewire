package com.example.voward;

/**
 * Whether an approved session has run out, read across a reboot and a moved clock.
 *
 * <p>A session deadline used to be a bare {@code SystemClock.elapsedRealtime()} value. That
 * clock restarts at zero on boot, so a reboot turned a five minute session into one that ran
 * until uptime passed the stale reading — as long as the device had been up when the session
 * started, and with the deadline alarm gone with the reboot there was nothing else to end it.
 *
 * <p>So three readings, the same three the deactivation request and the dead man's switch
 * already keep: the monotonic deadline, the wall-clock deadline, and the boot count. Where the
 * dead man's switch leans towards releasing because firing early only hands the phone back,
 * this leans the other way and ends the session on whichever clock says so first. Ending early
 * costs the user a session they can ask for again; ending late is free access.</p>
 */
final class SessionDeadlinePolicy {

    /** {@code Settings.Global.BOOT_COUNT} could not be read, or predates this record. */
    static final int UNKNOWN_BOOT_COUNT = -1;

    private SessionDeadlinePolicy() {}

    /**
     * @param deadlineElapsedMs {@code elapsedRealtime} the session ends at, valid for one boot
     * @param deadlineWallMs    wall clock the session ends at, or 0 when it was not recorded
     * @param startBootCount    the boot count when the session started, or
     *                          {@link #UNKNOWN_BOOT_COUNT}
     */
    static boolean hasExpired(long deadlineElapsedMs, long deadlineWallMs, int startBootCount,
                              long nowElapsedMs, long nowWallMs, int nowBootCount) {
        boolean sameBootKnown = startBootCount >= 0 && nowBootCount >= 0;
        // The device has rebooted: the monotonic deadline means nothing any more and the alarm
        // that would have ended the session did not survive either.
        if (sameBootKnown && startBootCount != nowBootCount) return true;
        boolean wallKnown = deadlineWallMs > 0;
        if (!sameBootKnown) {
            // No boot count to compare, so the monotonic reading cannot be trusted at all and
            // only the wall clock is left. Without that either, this is a record written before
            // any of it was kept and the old reading is all there is.
            return wallKnown ? nowWallMs >= deadlineWallMs : nowElapsedMs >= deadlineElapsedMs;
        }
        if (nowElapsedMs >= deadlineElapsedMs) return true;
        return wallKnown && nowWallMs >= deadlineWallMs;
    }
}
