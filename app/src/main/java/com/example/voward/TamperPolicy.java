package com.example.voward;

import android.os.Build;
import android.os.UserManager;

import java.util.Set;
import java.util.TreeSet;

/**
 * The complete catalogue of user restrictions Voward is allowed to set.
 *
 * <p>Every {@code DISALLOW_*} key the app can ever apply is listed here and nowhere else, so
 * release has a closed set to clear. A restriction added straight into
 * {@link EnforcementState#desiredFor} would eventually be one the mirror knows about and this
 * catalogue does not, which is exactly the drift section 6.1 forbids.</p>
 *
 * <p>Deliberately absent:</p>
 * <ul>
 *   <li>{@code DISALLOW_INSTALL_UNKNOWN_SOURCES} — installing anything, from anywhere, stays
 *       the user's own decision. The new-app quarantine handles what arrives that way.</li>
 *   <li>{@code DISALLOW_UNINSTALL_APPS} — it would stop the user removing <em>any</em> app.
 *       Protecting Voward is {@code setUninstallBlocked} on one package, which is what the
 *       "block uninstall" toggle actually promises.</li>
 *   <li>{@code DISALLOW_CONFIG_VPN} — the user's own VPN is never touched.</li>
 * </ul>
 */
final class TamperPolicy {

    private TamperPolicy() {}

    /**
     * The restrictions the current settings ask for.
     *
     * <p>Only called for an active {@link EnforcementState}; an inactive one carries none.</p>
     */
    static Set<String> restrictionsFor(AppPreferencesManagerSingleton preferences) {
        Set<String> restrictions = new TreeSet<>();
        if (preferences.isSafeModeGuardEnabled()) {
            restrictions.add(UserManager.DISALLOW_SAFE_BOOT);
        }
        if (preferences.isExtraUserGuardEnabled()) {
            restrictions.add(UserManager.DISALLOW_ADD_USER);
            restrictions.add(UserManager.DISALLOW_ADD_MANAGED_PROFILE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                restrictions.add(UserManager.DISALLOW_ADD_PRIVATE_PROFILE);
            }
        }
        if (preferences.isClockGuardEnabled()) {
            restrictions.add(UserManager.DISALLOW_CONFIG_DATE_TIME);
        }
        if (preferences.isDebuggingGuardEnabled()) {
            restrictions.add(UserManager.DISALLOW_DEBUGGING_FEATURES);
        }
        if (preferences.isSettingsResetGuardEnabled()) {
            restrictions.add(UserManager.DISALLOW_FACTORY_RESET);
        }
        return restrictions;
    }

    /**
     * Whether the clock is pinned to network time. Manual changes are already refused by
     * {@code DISALLOW_CONFIG_DATE_TIME}; without automatic time the device would simply keep
     * whatever wrong time it was left on, and the allowance would be computed from it.
     */
    static boolean requiresAutomaticTime(Set<String> restrictions) {
        return restrictions.contains(UserManager.DISALLOW_CONFIG_DATE_TIME);
    }
}
