package com.example.voward;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit, progressive setup that separates required and optional authority. */
public class SetupActivity extends AppCompatActivity {
    private static final int STEP_COUNT = 6;
    private static final int[] COOLDOWN_MINUTES = {0, 1, 360, 720, 1440, 2880, 4320};
    private static final int[] WINDOW_HOURS = {1, 2, 3, 6, 12, 24};
    private final int[] stepViews = {
            R.id.setupWelcomeStep, R.id.setupGoalStep, R.id.setupRulesStep,
            R.id.setupPermissionsStep, R.id.setupKeyStep, R.id.setupReviewStep
    };
    private final int[] stepTitles = {
            R.string.setup_welcome_title, R.string.setup_goal_title, R.string.setup_rules_title,
            R.string.setup_permissions_title, R.string.setup_key_title, R.string.setup_review_title
    };
    private final int[] stepBodies = {
            R.string.setup_welcome_body, R.string.setup_goal_body, R.string.setup_rules_body,
            R.string.setup_permissions_body, R.string.setup_key_body, R.string.setup_review_body
    };

    private AppPreferencesManagerSingleton preferences;
    private AttentionBudgetEngine budgetEngine;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int step;

    private final ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> refreshStepData());
    private final ActivityResultLauncher<String> notificationLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> refreshStepData());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_setup);
        preferences = AppPreferencesManagerSingleton.getInstance(this);
        if (preferences.getIsBlockerActive()) {
            Toast.makeText(this, R.string.blocker_active_cannot_change, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        budgetEngine = new AttentionBudgetEngine(this);
        step = savedInstanceState == null ? 0 : savedInstanceState.getInt("setup_step", 0);

        setupDeactivationTimingSelectors();
        populateValues();
        findViewById(R.id.setupSkipButton).setOnClickListener(v -> finishSetup(false));
        findViewById(R.id.setupBackButton).setOnClickListener(v -> showStep(Math.max(0, step - 1)));
        findViewById(R.id.setupNextButton).setOnClickListener(v -> next());
        findViewById(R.id.setupChooseAppsButton).setOnClickListener(v ->
                startActivity(new Intent(this, AppPackagesListEditorActivity.class)));
        findViewById(R.id.setupChooseSitesButton).setOnClickListener(v ->
                startActivity(new Intent(this, UrlListEditorActivity.class)));
        findViewById(R.id.setupFirewallButton).setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.setupGuardButton).setOnClickListener(v -> configureGuard());
        findViewById(R.id.setupNotificationButton).setOnClickListener(v -> requestNotifications());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (step > 0) showStep(step - 1);
                else finishSetup(false);
            }
        });
        showStep(step);
    }

    private void populateValues() {
        ((EditText) findViewById(R.id.setupGoalInput)).setText(preferences.getFunctionalGoal());
        ((EditText) findViewById(R.id.setupBudgetInput)).setText(String.valueOf(
                preferences.getDailyAllowanceSeconds() / 60));
        ((EditText) findViewById(R.id.setupSessionInput)).setText(String.valueOf(
                preferences.getDefaultSessionSeconds() / 60));
        ((Spinner) findViewById(R.id.setupDeactivationCooldownSpinner)).setSelection(
                indexOf(COOLDOWN_MINUTES, preferences.getDeactivationCooldownMinutes()));
        ((Spinner) findViewById(R.id.setupDeactivationWindowSpinner)).setSelection(
                indexOf(WINDOW_HOURS, preferences.getDeactivationWindowHours()));
        refreshDeactivationTimingControls();
    }

    private void setupDeactivationTimingSelectors() {
        Spinner cooldown = findViewById(R.id.setupDeactivationCooldownSpinner);
        Spinner window = findViewById(R.id.setupDeactivationWindowSpinner);
        String[] cooldownLabels = new String[COOLDOWN_MINUTES.length];
        for (int i = 0; i < COOLDOWN_MINUTES.length; i++) {
            cooldownLabels[i] = formatCooldownChoice(COOLDOWN_MINUTES[i]);
        }
        String[] windowLabels = new String[WINDOW_HOURS.length];
        for (int i = 0; i < WINDOW_HOURS.length; i++) {
            windowLabels[i] = getString(R.string.window_hours_choice, WINDOW_HOURS[i]);
        }
        cooldown.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, cooldownLabels));
        window.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, windowLabels));
        cooldown.setSelection(indexOf(COOLDOWN_MINUTES,
                preferences.getDeactivationCooldownMinutes()));
        window.setSelection(indexOf(WINDOW_HOURS,
                preferences.getDeactivationWindowHours()));
        cooldown.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                preferences.setDeactivationCooldownMinutes(COOLDOWN_MINUTES[position]);
                refreshDeactivationTimingControls();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        window.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                preferences.setDeactivationWindowHours(WINDOW_HOURS[position]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void refreshDeactivationTimingControls() {
        boolean immediate = preferences.getDeactivationCooldownMinutes() == 0;
        View window = findViewById(R.id.setupDeactivationWindowSpinner);
        window.setEnabled(!immediate);
        window.setAlpha(immediate ? 0.38f : 1f);
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) if (values[i] == target) return i;
        return 0;
    }

    private String formatCooldownChoice(int minutes) {
        if (minutes == 0) return getString(R.string.cooldown_zero_choice);
        if (minutes == 1) return getString(R.string.cooldown_one_minute_choice);
        return getString(R.string.cooldown_hours_choice, minutes / 60);
    }

    private String formatCooldownDuration(int minutes) {
        return minutes == 1 ? getString(R.string.one_minute)
                : getString(R.string.hours_duration, minutes / 60);
    }

    private void showStep(int nextStep) {
        step = Math.max(0, Math.min(STEP_COUNT - 1, nextStep));
        for (int i = 0; i < stepViews.length; i++) {
            findViewById(stepViews[i]).setVisibility(i == step ? View.VISIBLE : View.GONE);
        }
        ((TextView) findViewById(R.id.setupStepLabel)).setText(getString(
                R.string.setup_progress, step + 1, STEP_COUNT));
        ((TextView) findViewById(R.id.setupStepTitle)).setText(stepTitles[step]);
        ((TextView) findViewById(R.id.setupStepBody)).setText(stepBodies[step]);
        LinearProgressIndicator progress = findViewById(R.id.setupProgressBar);
        progress.setProgressCompat(step + 1, true);
        findViewById(R.id.setupBackButton).setVisibility(step == 0 ? View.GONE : View.VISIBLE);
        findViewById(R.id.setupSkipButton).setVisibility(step == STEP_COUNT - 1 ? View.GONE : View.VISIBLE);
        refreshStepData();
        findViewById(R.id.setupScroll).scrollTo(0, 0);
    }

    private void refreshStepData() {
        if (preferences == null) return;
        int apps = preferences.getRestrictedAppPackages().size();
        int sites = preferences.getRestrictedUrls().size();
        ((TextView) findViewById(R.id.setupRulesSummary)).setText(getString(
                R.string.setup_rules_summary, apps, sites));

        boolean firewall = isAccessEnabled(AttentionFirewallService.class);
        boolean guard = isGuardReady();
        boolean notifications = areNotificationsReady();
        refreshPermissionRow(R.id.setupFirewallStatus, R.id.setupFirewallButton, firewall,
                R.string.firewall_permission_ready, R.string.firewall_permission_missing);
        refreshPermissionRow(R.id.setupGuardStatus, R.id.setupGuardButton, guard,
                R.string.uninstall_permission_ready, R.string.uninstall_permission_optional);
        refreshPermissionRow(R.id.setupNotificationStatus, R.id.setupNotificationButton, notifications,
                R.string.notifications_ready, R.string.notifications_optional);

        boolean keyReady = !preferences.getDeactivationKey().isEmpty();
        findViewById(R.id.setupKeyReady).setVisibility(keyReady ? View.VISIBLE : View.GONE);
        findViewById(R.id.setupKeyInputLayout).setVisibility(keyReady ? View.GONE : View.VISIBLE);

        if (step == STEP_COUNT - 1) refreshReview(apps, sites, firewall, keyReady);
    }

    private void refreshPermissionRow(int statusId, int buttonId, boolean ready,
                                      int readyText, int missingText) {
        ((TextView) findViewById(statusId)).setText(ready ? readyText : missingText);
        findViewById(buttonId).setVisibility(ready ? View.GONE : View.VISIBLE);
    }

    private void refreshReview(int apps, int sites, boolean firewall, boolean keyReady) {
        String goal = preferences.getFunctionalGoal();
        if (goal.isEmpty()) goal = getString(R.string.no_goal_compact);
        ((TextView) findViewById(R.id.setupReviewSummary)).setText(getString(
                R.string.setup_review_summary, goal,
                preferences.getDailyAllowanceSeconds() / 60,
                preferences.getDefaultSessionSeconds() / 60, apps, sites)
                + "\n\n" + (preferences.getDeactivationCooldownMinutes() == 0
                ? getString(R.string.activation_immediate_summary)
                : getString(R.string.activation_delayed_summary,
                formatCooldownDuration(preferences.getDeactivationCooldownMinutes()),
                preferences.getDeactivationWindowHours())));
        int missing = 0;
        if (!firewall) missing++;
        if (apps + sites == 0) missing++;
        if (preferences.getDailyAllowanceSeconds() <= 0) missing++;
        if (!keyReady) missing++;
        TextView readiness = findViewById(R.id.setupReadinessText);
        if (missing == 0) readiness.setText(R.string.setup_ready_summary);
        else readiness.setText(getResources().getQuantityString(
                R.plurals.setup_missing_items, missing, missing));
        ((Button) findViewById(R.id.setupNextButton)).setText(missing == 0
                ? R.string.setup_activate : R.string.setup_done);
    }

    private void next() {
        if (step == 0) {
            preferences.setPermissionDisclosureAccepted(true);
            showStep(1);
            return;
        }
        if (step == 1) {
            if (!saveGoalAndTime()) return;
            showStep(2);
            return;
        }
        if (step == 4 && preferences.getDeactivationKey().isEmpty()) {
            EditText keyInput = findViewById(R.id.setupKeyInput);
            String key = keyInput.getText() == null ? "" : keyInput.getText().toString();
            if (!key.isEmpty()) {
                Button next = findViewById(R.id.setupNextButton);
                next.setEnabled(false);
                executor.execute(() -> {
                    preferences.setDeactivationKey(key);
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        keyInput.setText("");
                        next.setEnabled(true);
                        showStep(5);
                    });
                });
                return;
            }
        }
        if (step == STEP_COUNT - 1) {
            boolean ready = isReadyToActivate();
            if (ready) {
                preferences.setIsBlockerActive(true);
            }
            finishSetup(ready);
            return;
        }
        showStep(step + 1);
    }

    private boolean saveGoalAndTime() {
        if (preferences.getIsBlockerActive()) {
            Toast.makeText(this, R.string.blocker_active_cannot_change, Toast.LENGTH_SHORT).show();
            finish();
            return false;
        }
        TextInputLayout budgetLayout = findViewById(R.id.setupBudgetInputLayout);
        TextInputLayout sessionLayout = findViewById(R.id.setupSessionInputLayout);
        int budget = parseInt((EditText) findViewById(R.id.setupBudgetInput));
        int session = parseInt((EditText) findViewById(R.id.setupSessionInput));
        budgetLayout.setError(budget >= 1 && budget <= 1440 ? null : getString(R.string.allowance_setup_error));
        sessionLayout.setError(session >= 1 && session <= 60 ? null : getString(R.string.gate_minutes_error));
        if (budget < 1 || budget > 1440 || session < 1 || session > 60) return false;
        EditText goalInput = findViewById(R.id.setupGoalInput);
        preferences.setFunctionalGoal(goalInput.getText() == null ? "" : goalInput.getText().toString());
        int oldAllowance = preferences.getDailyAllowanceSeconds();
        preferences.setDailyAllowanceSeconds(budget * 60);
        budgetEngine.updateRemainingBudgetForAllowanceChange(
                oldAllowance, preferences.getDailyAllowanceSeconds());
        preferences.setDefaultSessionSeconds(session * 60);
        return true;
    }

    private boolean isReadyToActivate() {
        return isAccessEnabled(AttentionFirewallService.class)
                && (!preferences.getRestrictedAppPackages().isEmpty()
                || !preferences.getRestrictedUrls().isEmpty())
                && preferences.getDailyAllowanceSeconds() > 0
                && !preferences.getDeactivationKey().isEmpty();
    }

    private void finishSetup(boolean activated) {
        preferences.setSetupSeen(true);
        setResult(activated ? RESULT_OK : RESULT_CANCELED);
        finish();
    }

    private void configureGuard() {
        preferences.setUninstallGuardEnabled(true);
        DevicePolicyManager manager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, MyDeviceAdminReceiver.class);
        if (manager != null && !manager.isAdminActive(admin)) {
            settingsLauncher.launch(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            getString(R.string.uninstall_permission_optional)));
        } else if (!isAccessEnabled(AttentionFirewallService.class)) {
            openAccessibilitySettings();
        } else {
            refreshStepData();
        }
    }

    private void openAccessibilitySettings() {
        settingsLauncher.launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        } else {
            Toast.makeText(this, R.string.notifications_ready, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isGuardReady() {
        DevicePolicyManager manager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        boolean admin = manager != null && manager.isAdminActive(
                new ComponentName(this, MyDeviceAdminReceiver.class));
        return preferences.isUninstallGuardEnabled() && admin
                && isAccessEnabled(AttentionFirewallService.class);
    }

    private boolean areNotificationsReady() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isAccessEnabled(Class<?> serviceClass) {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component = getPackageName() + "/" + serviceClass.getName();
        return enabled != null && enabled.toLowerCase(Locale.ROOT)
                .contains(component.toLowerCase(Locale.ROOT));
    }

    private static int parseInt(EditText input) {
        try {
            return Integer.parseInt(input.getText() == null ? "" : input.getText().toString());
        } catch (Exception ignored) {
            return -1;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (preferences != null && preferences.getIsBlockerActive()) {
            finish();
            return;
        }
        refreshStepData();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("setup_step", step);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
