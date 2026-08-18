package com.example.voward;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

/** Silent opportunistic validation used outside the deactivation UI. */
final class DeactivationRequestValidator {
    private DeactivationRequestValidator() { }

    static void validate(Context context, AppPreferencesManagerSingleton preferences) {
        DeactivationPolicyEngine.Request request = preferences.getPendingDeactivation();
        if (request == null) return;
        int bootCount;
        try {
            bootCount = Settings.Global.getInt(
                    context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | RuntimeException unavailable) {
            bootCount = -1;
        }
        DeactivationPolicyEngine.State state = new DeactivationPolicyEngine()
                .evaluateRequest(request, System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(), bootCount).state;
        if (state == DeactivationPolicyEngine.State.EXPIRED
                || state == DeactivationPolicyEngine.State.INVALIDATED) {
            preferences.finishPendingDeactivation(state);
        }
    }
}
