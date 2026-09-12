package com.example.voward;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reacts to an app being uninstalled — the one package event the platform still delivers to a
 * manifest receiver.
 *
 * <p>Measured on this device as device owner: {@code PACKAGE_ADDED} and {@code PACKAGE_REMOVED}
 * are never delivered to a manifest receiver, while {@code PACKAGE_FULLY_REMOVED} arrives about
 * fifteen seconds after the uninstall. Half a signal is still worth having, because uninstalling
 * is where the cheap bypasses start: removing the one browser that can be filtered so no website
 * rule is enforceable, and removing a blocked app so that the copy installed in its place is not
 * the one the mirror thinks it suspended.</p>
 *
 * <p>Installing has no equivalent and cannot be given one, so the short reconcile poll is what
 * covers that direction.</p>
 */
public class PackageRemovedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        // A receiver for a system broadcast has to be exported, so anything on the phone can
        // send it one. The action is checked, and then the claim is checked: acting on a
        // package that is still installed would let another app clear a quarantine entry by
        // saying the app it holds has gone.
        if (!Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) return;
        Uri data = intent.getData();
        String packageName = data == null ? null : data.getSchemeSpecificPart();
        if (packageName == null || packageName.isEmpty()) return;
        if (packageName.equals(context.getPackageName())) return;
        if (isStillInstalled(context, packageName)) return;

        Context appContext = context.getApplicationContext();
        PendingResult result = goAsync();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                EnforcementCoordinator.onPackageRemoved(appContext, packageName);
            } finally {
                result.finish();
            }
        });
        executor.shutdown();
    }

    /**
     * {@code MATCH_UNINSTALLED_PACKAGES} so that a package which is merely hidden — a strict
     * rule — still counts as installed. A full removal takes the data with it, so nothing is
     * left for this to match.
     */
    private static boolean isStillInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return true;
        } catch (PackageManager.NameNotFoundException gone) {
            return false;
        }
    }
}
