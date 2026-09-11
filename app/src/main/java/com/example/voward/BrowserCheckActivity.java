package com.example.voward;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "test this browser" flow from section 4.4.
 *
 * <p>A browser that declares {@code URLBlocklist} is approved on sight. A browser that declares
 * nothing is <em>unknown</em>, not unsupported: the declaration exists so MDM consoles can list
 * keys, and a Chromium fork can honour a policy it never advertises. Guessing would either
 * pause a browser that works or trust one that does not, so this asks the only party who can
 * actually see the answer.</p>
 *
 * <p>The test writes one sentinel rule, opens the browser at it, and asks what happened. No
 * external service is involved and nothing here has to be updated as browsers change.</p>
 */
public class BrowserCheckActivity extends Activity {

    private static final String STATE_PENDING = "pendingBrowser";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private AppPreferencesManagerSingleton preferences;
    private LinearLayout list;
    private String pendingBrowser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = AppPreferencesManagerSingleton.getInstance(this);
        if (savedInstanceState != null) {
            pendingBrowser = savedInstanceState.getString(STATE_PENDING);
        }
        setTitle(R.string.browser_check_title);
        setContentView(buildLayout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingBrowser != null) {
            String browser = pendingBrowser;
            pendingBrowser = null;
            askForVerdict(browser);
        }
        refreshList();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PENDING, pendingBrowser);
    }

    private View buildLayout() {
        int pad = dp(24);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView explanation = new TextView(this);
        explanation.setText(R.string.browser_check_explanation);
        column.addView(explanation);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(16), 0, 0);
        column.addView(list);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void refreshList() {
        list.removeAllViews();
        boolean provisioned = new PolicyReconciler(this).isProvisioned();
        Set<String> browsers = BrowserPolicy.installedBrowsers(this);

        if (browsers.isEmpty()) {
            list.addView(line(getString(R.string.browser_check_none)));
            return;
        }
        if (!provisioned) {
            list.addView(line(getString(R.string.browser_check_needs_provisioning)));
        }

        BrowserPolicy.Landscape landscape = BrowserPolicy.survey(this, preferences);
        Set<String> approved = preferences.getApprovedBrowserPackages();
        Set<String> rejected = preferences.getRejectedBrowserPackages();

        for (String browser : browsers) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(12), 0, dp(12));

            TextView name = new TextView(this);
            name.setText(labelOf(browser));
            row.addView(name);

            TextView status = new TextView(this);
            status.setText(getString(statusFor(browser, landscape, approved, rejected)));
            row.addView(status);

            Button test = new Button(this);
            test.setText(R.string.browser_check_test);
            test.setEnabled(provisioned);
            test.setOnClickListener(view -> startTest(browser));
            row.addView(test);

            list.addView(row);
        }
    }

    private int statusFor(String browser, BrowserPolicy.Landscape landscape,
                          Set<String> approved, Set<String> rejected) {
        if (rejected.contains(browser)) return R.string.browser_status_rejected;
        if (approved.contains(browser)) return R.string.browser_status_verified;
        if (landscape.filterable.contains(browser)) return R.string.browser_status_declared;
        return R.string.browser_status_untested;
    }

    /**
     * Pushes the sentinel rule, then opens the browser at it. The verdict is asked for when the
     * user comes back, because there is no way to observe the page from here.
     */
    private void startTest(String browser) {
        if (!new PolicyReconciler(this).pushProbePolicy(browser)) {
            Toast.makeText(this, R.string.browser_check_push_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Intent open = new Intent(Intent.ACTION_VIEW,
                Uri.parse("http://" + BrowserPolicy.PROBE_DOMAIN))
                .setPackage(browser)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (open.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, R.string.browser_check_cannot_open, Toast.LENGTH_LONG).show();
            return;
        }
        pendingBrowser = browser;
        startActivity(open);
    }

    /**
     * A browser that refuses the page is filtering. One that loads it, or that shows an
     * ordinary "site cannot be reached", is not — the sentinel domain does not resolve, so a
     * network error is exactly what an unfiltered browser produces.
     */
    private void askForVerdict(String browser) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.browser_check_verdict_title, labelOf(browser)))
                .setMessage(R.string.browser_check_verdict_message)
                .setNegativeButton(R.string.browser_check_verdict_no,
                        (dialog, which) -> recordVerdict(browser, false))
                .setPositiveButton(R.string.browser_check_verdict_yes,
                        (dialog, which) -> recordVerdict(browser, true))
                .setCancelable(false)
                .show();
    }

    /** Puts the real rules back over the sentinel, whichever way the verdict went. */
    private void recordVerdict(String browser, boolean filtersCorrectly) {
        preferences.setBrowserVerdict(browser, filtersCorrectly);
        BrowserPolicy.invalidate();
        executor.execute(() -> {
            EnforcementCoordinator.applyBrowserVerdict(this, browser);
            main.post(() -> {
                if (!isDestroyed()) refreshList();
            });
        });
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

    private TextView line(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
