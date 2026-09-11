package com.example.voward;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.util.concurrent.Executors;

/**
 * The bridge from Android's "app is paused by your admin" dialog back into Voward's gate.
 *
 * <p>A suspended package cannot be launched, so there is no launch for an accessibility
 * service to intercept. Instead the system dialog offers a details button and it lands here,
 * which opens the same decision gate the user has always seen. That keeps the gate in the
 * moment rather than turning every attempt into "go and open Voward first".</p>
 */
public class SuspendedAppDetailsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String packageName = getIntent() == null ? null
                : getIntent().getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (packageName == null || packageName.isEmpty()) {
            finish();
            return;
        }

        AppPreferencesManagerSingleton preferences =
                AppPreferencesManagerSingleton.getInstance(this);
        if (preferences.getQuarantinedPackages().contains(packageName)) {
            askAboutNewApp(preferences, packageName);
            return;
        }
        if (!preferences.isRestrictedApp(packageName)) {
            // Suspended but no longer a rule. The next reconcile lifts it, and saying nothing
            // beats explaining a block that is about to disappear.
            finish();
            return;
        }

        preferences.setLastInterceptedApp(packageName);
        preferences.setLastInterceptedUrl("");
        preferences.setLastInterceptionKind("APP");

        startActivity(new Intent(this, DecisionGateActivity.class)
                .putExtra(DecisionGateActivity.EXTRA_STRICT_BLOCK,
                        preferences.isStrictRestrictedApp(packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    /**
     * The one prompt a newly installed app costs. Either it becomes a rule, or it is simply an
     * app the user has and Voward stops holding it.
     */
    private void askAboutNewApp(AppPreferencesManagerSingleton preferences, String packageName) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.quarantine_title)
                .setMessage(getString(R.string.quarantine_message, labelOf(packageName)))
                .setNegativeButton(R.string.quarantine_keep, (dialog, which) ->
                        decide(preferences, packageName, false))
                .setPositiveButton(R.string.quarantine_restrict, (dialog, which) ->
                        decide(preferences, packageName, true))
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void decide(AppPreferencesManagerSingleton preferences, String packageName,
                        boolean restrict) {
        if (restrict) preferences.addRestrictedAppPackage(packageName, false);
        preferences.releaseFromQuarantine(packageName);
        Context context = getApplicationContext();
        // Off the main thread: this is device policy IPC, and the screen is about to go away.
        Executors.newSingleThreadExecutor().execute(() -> {
            EnforcementCoordinator.reconcileNow(context);
        });
        finish();
    }

    private String labelOf(String packageName) {
        PackageManager packageManager = getPackageManager();
        try {
            return packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException uninstalled) {
            return packageName;
        }
    }
}
