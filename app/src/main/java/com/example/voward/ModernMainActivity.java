package com.example.voward;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Modern, state-first shell for the existing on-device enforcement engine. */
public class ModernMainActivity extends AppCompatActivity {
    private AppPreferencesManagerSingleton preferences;
    private AttentionBudgetEngine budgetEngine;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private boolean updatingFields;

    private final ActivityResultLauncher<Intent> setupFlowLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                populateEditableFields();
                refreshAll();
            });
    private final ActivityResultLauncher<Intent> externalSettingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> refreshAll());
    private final ActivityResultLauncher<String> notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> refreshAll());
    private final ActivityResultLauncher<String> exportConfigurationLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), this::writeConfiguration);
    private final ActivityResultLauncher<String[]> importConfigurationLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::readConfiguration);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main_modern);

        preferences = AppPreferencesManagerSingleton.getInstance(this);
        budgetEngine = new AttentionBudgetEngine(this);
        budgetEngine.resetBudgetIfNeeded();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        setupNavigation(toolbar);
        populateEditableFields();
        setupWatchers();
        setupActions();
        refreshAll();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                BottomNavigationView navigation = findViewById(R.id.bottomNavigation);
                if (navigation.getSelectedItemId() != R.id.navigation_today) {
                    navigation.setSelectedItemId(R.id.navigation_today);
                } else {
                    finish();
                }
            }
        });

        if (savedInstanceState == null && !preferences.getSetupSeen()) openSetup();
    }

    private void setupNavigation(MaterialToolbar toolbar) {
        BottomNavigationView navigation = findViewById(R.id.bottomNavigation);
        navigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            findViewById(R.id.todayScreen).setVisibility(id == R.id.navigation_today ? View.VISIBLE : View.GONE);
            findViewById(R.id.rulesScreen).setVisibility(id == R.id.navigation_rules ? View.VISIBLE : View.GONE);
            findViewById(R.id.progressScreen).setVisibility(id == R.id.navigation_progress ? View.VISIBLE : View.GONE);
            findViewById(R.id.settingsScreen).setVisibility(id == R.id.navigation_settings ? View.VISIBLE : View.GONE);
            if (id == R.id.navigation_rules) toolbar.setTitle(R.string.nav_rules);
            else if (id == R.id.navigation_progress) toolbar.setTitle(R.string.nav_progress);
            else if (id == R.id.navigation_settings) toolbar.setTitle(R.string.nav_settings);
            else toolbar.setTitle(R.string.nav_today);
            return true;
        });
        navigation.setSelectedItemId(R.id.navigation_today);
    }

    private void setupActions() {
        findViewById(R.id.resumeSetupButton).setOnClickListener(v -> openSetup());
        findViewById(R.id.rerunSetupButton).setOnClickListener(v -> openSetup());
        findViewById(R.id.editIntentionButton).setOnClickListener(v -> openSettingsAtIntention());
        findViewById(R.id.heroActionButton).setOnClickListener(v -> {
            if (preferences.getIsBlockerActive()) {
                ((BottomNavigationView) findViewById(R.id.bottomNavigation))
                        .setSelectedItemId(R.id.navigation_settings);
            } else {
                attemptActivation();
            }
        });
        findViewById(R.id.firewallPermissionButton).setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.guardPermissionButton).setOnClickListener(v -> configureOptionalGuard());
        findViewById(R.id.notificationPermissionButton).setOnClickListener(v -> requestNotifications());
    }

    private void openSetup() {
        if (preferences.getIsBlockerActive()) {
            Toast.makeText(this, R.string.blocker_active_cannot_change, Toast.LENGTH_SHORT).show();
            return;
        }
        setupFlowLauncher.launch(new Intent(this, SetupActivity.class));
    }

    private void populateEditableFields() {
        updatingFields = true;
        ((EditText) findViewById(R.id.dailyBudgetInput)).setText(String.valueOf(
                preferences.getDailyAllowanceSeconds() / 60));
        ((EditText) findViewById(R.id.baseWaitInput)).setText(String.valueOf(
                preferences.getBaseWaitTimeSeconds()));
        ((EditText) findViewById(R.id.reentryGrowthInput)).setText(String.format(
                Locale.getDefault(), "%.0f", preferences.getReentryGrowth() * 100));
        ((EditText) findViewById(R.id.defaultSessionInput)).setText(String.valueOf(
                preferences.getDefaultSessionSeconds() / 60));
        ((EditText) findViewById(R.id.functionalGoalInput)).setText(preferences.getFunctionalGoal());
        ((EditText) findViewById(R.id.replacementOneInput)).setText(preferences.getReplacementWalk());
        ((EditText) findViewById(R.id.replacementTwoInput)).setText(preferences.getReplacementWater());
        ((EditText) findViewById(R.id.replacementThreeInput)).setText(preferences.getReplacementTask());
        ((SwitchCompat) findViewById(R.id.uninstallGuardSwitch)).setChecked(
                preferences.isUninstallGuardEnabled());
        updatingFields = false;
    }

    private void setupWatchers() {
        ((SwitchCompat) findViewById(R.id.uninstallGuardSwitch)).setOnCheckedChangeListener(
                (view, checked) -> {
                    if (!updatingFields && !preferences.getIsBlockerActive()) {
                        preferences.setUninstallGuardEnabled(checked);
                    }
                });

        watch(R.id.dailyBudgetInput, value -> {
            if (preferences.getIsBlockerActive()) return;
            int minutes = parseInt(value);
            TextInputLayout layout = findViewById(R.id.dailyBudgetInputLayout);
            boolean valid = minutes >= 0 && minutes <= 1440;
            layout.setError(valid ? null : getString(R.string.daily_allowance_error));
            if (!valid) return;
            int oldAllowance = preferences.getDailyAllowanceSeconds();
            preferences.setDailyAllowanceSeconds(minutes * 60);
            budgetEngine.updateRemainingBudgetForAllowanceChange(
                    oldAllowance, preferences.getDailyAllowanceSeconds());
        });
        watch(R.id.baseWaitInput, value -> {
            if (preferences.getIsBlockerActive()) return;
            int seconds = parseInt(value);
            boolean valid = seconds >= 1 && seconds <= 3600;
            ((TextInputLayout) findViewById(R.id.baseWaitInputLayout)).setError(
                    valid ? null : getString(R.string.base_pause_error));
            if (valid) preferences.setBaseWaitTimeSeconds(seconds);
        });
        watch(R.id.reentryGrowthInput, value -> {
            if (preferences.getIsBlockerActive()) return;
            float percent = parseFloat(value);
            boolean valid = percent >= 0f && percent <= 100f;
            ((TextInputLayout) findViewById(R.id.reentryGrowthInputLayout)).setError(
                    valid ? null : getString(R.string.reentry_growth_error));
            if (valid) preferences.setReentryGrowth(percent / 100f);
        });
        watch(R.id.defaultSessionInput, value -> {
            if (preferences.getIsBlockerActive()) return;
            int minutes = parseInt(value);
            boolean valid = minutes >= 1 && minutes <= 60;
            ((TextInputLayout) findViewById(R.id.defaultSessionInputLayout)).setError(
                    valid ? null : getString(R.string.gate_minutes_error));
            if (valid) preferences.setDefaultSessionSeconds(minutes * 60);
        });
        watch(R.id.functionalGoalInput, true, value -> {
            if (!preferences.getIsBlockerActive()) {
                preferences.setFunctionalGoal(value);
                refreshTodayGoal();
            }
        });
        watch(R.id.replacementOneInput, value -> {
            if (!preferences.getIsBlockerActive()) preferences.setReplacementWalk(value);
        });
        watch(R.id.replacementTwoInput, value -> {
            if (!preferences.getIsBlockerActive()) preferences.setReplacementWater(value);
        });
        watch(R.id.replacementThreeInput, value -> {
            if (!preferences.getIsBlockerActive()) preferences.setReplacementTask(value);
        });
        watch(R.id.deactivationKeySetterInputText, value -> refreshKeyButton());
    }

    private void watch(int viewId, TextListener listener) {
        watch(viewId, false, listener);
    }

    private void watch(int viewId, boolean notifyWhenEmpty, TextListener listener) {
        ((EditText) findViewById(viewId)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!updatingFields && (notifyWhenEmpty || s.length() > 0)) {
                    listener.onTextChanged(s.toString());
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void openSettingsAtIntention() {
        View focusedView = getCurrentFocus();
        if (focusedView != null) focusedView.clearFocus();

        ((BottomNavigationView) findViewById(R.id.bottomNavigation))
                .setSelectedItemId(R.id.navigation_settings);

        NestedScrollView settingsScreen = findViewById(R.id.settingsScreen);
        EditText intentionInput = findViewById(R.id.functionalGoalInput);
        settingsScreen.post(() -> {
            intentionInput.requestFocus();
            intentionInput.setSelection(intentionInput.length());
            settingsScreen.scrollTo(0, 0);
        });
    }

    private void refreshTodayGoal() {
        String goal = preferences.getFunctionalGoal();
        ((TextView) findViewById(R.id.todayGoalText)).setText(goal.isEmpty()
                ? getString(R.string.no_goal_yet) : goal);
    }

    private void refreshAll() {
        if (preferences == null) return;
        budgetEngine.resetBudgetIfNeeded();
        boolean active = preferences.getIsBlockerActive();
        boolean firewallReady = isAccessEnabled(AttentionFirewallService.class);
        boolean guardReady = isGuardReady();
        boolean notificationsReady = areNotificationsReady();
        int appCount = preferences.getRestrictedAppPackages().size();
        int urlCount = preferences.getRestrictedUrls().size();
        int ruleCount = appCount + urlCount;
        long remaining = budgetEngine.getRemainingBudget();

        boolean essentialsReady = firewallReady && ruleCount > 0
                && preferences.getDailyAllowanceSeconds() > 0
                && !preferences.getDeactivationKey().isEmpty();
        boolean operational = active && essentialsReady;

        TextView badge = findViewById(R.id.protectionStatusBadge);
        badge.setText(operational ? R.string.protection_on_badge : R.string.protection_off_badge);
        badge.setBackgroundResource(operational ? R.drawable.bg_status_active : R.drawable.bg_status_attention);
        badge.setTextColor(ContextCompat.getColor(this,
                operational ? R.color.status_positive : R.color.status_warning));
        ((TextView) findViewById(R.id.protectionStatusTitle)).setText(operational
                ? R.string.protection_active_title
                : active ? R.string.protection_incomplete_title : R.string.protection_needs_attention);
        ((TextView) findViewById(R.id.protectionStatusDetail)).setText(operational
                ? R.string.protection_status_active_detail
                : active ? R.string.protection_incomplete_detail : R.string.protection_status_inactive_detail);
        ((TextView) findViewById(R.id.remainingBudgetValue)).setText(formatMinutesSeconds(remaining));
        int dailyAllowance = preferences.getDailyAllowanceSeconds();
        ((AllowanceRingView) findViewById(R.id.allowanceRing)).setFraction(
                dailyAllowance <= 0 ? 0 : remaining / (float) dailyAllowance);
        int pause = budgetEngine.calculateWaitSeconds();
        ((TextView) findViewById(R.id.nextPauseValue)).setText(getResources().getQuantityString(
                R.plurals.seconds_compact, pause, pause));
        ((TextView) findViewById(R.id.protectedCountValue)).setText(String.valueOf(ruleCount));
        ((Button) findViewById(R.id.heroActionButton)).setText(active
                ? R.string.manage_protection : R.string.activate_protection);

        String goal = preferences.getFunctionalGoal();
        refreshTodayGoal();
        ((TextView) findViewById(R.id.sessionsMetric)).setText(getString(
                R.string.sessions_metric, preferences.getDailySessionCount()));
        ((TextView) findViewById(R.id.earlyMetric)).setText(getString(
                R.string.early_metric, preferences.getSessionsEndedEarlyCount()));
        ((TextView) findViewById(R.id.limitsMetric)).setText(getString(
                R.string.limits_metric, preferences.getSessionLimitReachedCount()));
        refreshProgress();

        ((TextView) findViewById(R.id.appsRulesSummary)).setText(appCount == 0
                ? getString(R.string.no_apps_protected)
                : getResources().getQuantityString(R.plurals.protected_apps_summary, appCount, appCount));
        ((TextView) findViewById(R.id.urlRulesSummary)).setText(urlCount == 0
                ? getString(R.string.no_sites_protected)
                : getResources().getQuantityString(R.plurals.protected_sites_summary, urlCount, urlCount));
        findViewById(R.id.rulesLockBanner).setVisibility(active ? View.VISIBLE : View.GONE);
        MaterialButton appsButton = findViewById(R.id.button_go_to_edit_packages);
        MaterialButton sitesButton = findViewById(R.id.button_go_to_edit_urls);
        appsButton.setText(active ? R.string.view_locked_apps : R.string.manage_apps);
        sitesButton.setText(active ? R.string.view_locked_websites : R.string.manage_websites);
        appsButton.setIconResource(active ? R.drawable.ic_lock : 0);
        sitesButton.setIconResource(active ? R.drawable.ic_lock : 0);

        int missing = 0;
        if (!firewallReady) missing++;
        if (ruleCount == 0) missing++;
        if (preferences.getDailyAllowanceSeconds() <= 0) missing++;
        if (preferences.getDeactivationKey().isEmpty()) missing++;
        findViewById(R.id.setupReadinessCard).setVisibility(missing > 0 ? View.VISIBLE : View.GONE);
        TextView readinessSummary = findViewById(R.id.setupReadinessSummary);
        if (missing == 0) readinessSummary.setText(R.string.setup_ready_summary);
        else readinessSummary.setText(getResources().getQuantityString(
                R.plurals.setup_missing_items, missing, missing));

        findViewById(R.id.settingsLockedCard).setVisibility(active ? View.VISIBLE : View.GONE);
        findViewById(R.id.editableSettingsCard).setVisibility(active ? View.GONE : View.VISIBLE);
        View rerunSetupButton = findViewById(R.id.rerunSetupButton);
        rerunSetupButton.setEnabled(!active);
        rerunSetupButton.setAlpha(active ? 0.38f : 1f);
        View resetStatsButton = findViewById(R.id.button_reset_stats);
        resetStatsButton.setEnabled(!active);
        resetStatsButton.setAlpha(active ? 0.38f : 1f);
        View editIntentionButton = findViewById(R.id.editIntentionButton);
        editIntentionButton.setVisibility(active ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.settingsLockedSummary)).setText(getString(
                R.string.settings_locked_values,
                goal.isEmpty() ? getString(R.string.no_goal_compact) : goal,
                preferences.getDailyAllowanceSeconds() / 60,
                preferences.getDefaultSessionSeconds() / 60,
                preferences.getBaseWaitTimeSeconds(),
                Math.round(preferences.getReentryGrowth() * 100)));

        refreshPermissionRow(R.id.firewallPermissionStatus, R.id.firewallPermissionButton,
                firewallReady, R.string.firewall_permission_ready, R.string.firewall_permission_missing);
        refreshPermissionRow(R.id.guardPermissionStatus, R.id.guardPermissionButton,
                guardReady, R.string.uninstall_permission_ready, R.string.uninstall_permission_optional);
        refreshPermissionRow(R.id.notificationPermissionStatus, R.id.notificationPermissionButton,
                notificationsReady, R.string.notifications_ready, R.string.notifications_optional);

        ((TextView) findViewById(R.id.grayscaleStatus)).setText(
                GrayscaleController.isGrayscaleAvailable(this)
                        ? R.string.grayscale_available : R.string.grayscale_unavailable);
        refreshRecovery(active);
    }

    private void refreshProgress() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        long[] usageSeconds = new long[7];
        int weekSessions = 0;
        int weekEarly = 0;
        int weekLimits = 0;
        int[] weekHours = new int[24];
        int[] weekAlternatives = new int[3];

        List<AppPreferencesManagerSingleton.DailyUsage> history = preferences.getDailyUsageHistory();
        for (AppPreferencesManagerSingleton.DailyUsage day : history) {
            try {
                LocalDate date = LocalDate.parse(day.date);
                int index = (int) (date.toEpochDay() - monday.toEpochDay());
                if (index >= 0 && index < 7) {
                    usageSeconds[index] = day.restrictedTimeMs / 1000;
                    weekSessions += day.sessions;
                    weekEarly += day.endedEarly;
                    weekLimits += day.limitsReached;
                    for (int hour = 0; hour < 24; hour++) {
                        weekHours[hour] += day.sessionHours[hour];
                    }
                    for (int choice = 0; choice < 3; choice++) {
                        weekAlternatives[choice] += day.alternativeChoices[choice];
                    }
                }
            } catch (DateTimeParseException ignored) { }
        }

        int todayIndex = today.getDayOfWeek().getValue() - 1;
        long todaySeconds = preferences.getDailyRestrictedTimeMs() / 1000;
        usageSeconds[todayIndex] = todaySeconds;
        weekSessions += preferences.getDailySessionCount();
        weekEarly += preferences.getSessionsEndedEarlyCount();
        weekLimits += preferences.getSessionLimitReachedCount();
        int[] todayHours = preferences.getDailySessionHourCounts();
        for (int hour = 0; hour < 24; hour++) weekHours[hour] += todayHours[hour];
        int[] todayAlternatives = preferences.getDailyAlternativeChoiceCounts();
        for (int choice = 0; choice < 3; choice++) {
            weekAlternatives[choice] += todayAlternatives[choice];
        }
        long weekSeconds = 0;
        for (long value : usageSeconds) weekSeconds += value;

        ((TextView) findViewById(R.id.progressTodayUsage)).setText(formatCompactDuration(todaySeconds));
        ((TextView) findViewById(R.id.progressWeekUsage)).setText(formatCompactDuration(weekSeconds));
        ((TextView) findViewById(R.id.progressWeekSessions)).setText(String.valueOf(weekSessions));
        ((TextView) findViewById(R.id.progressEarlyMetric)).setText(
                getString(R.string.early_metric, weekEarly));
        ((TextView) findViewById(R.id.progressLimitsMetric)).setText(
                getString(R.string.limits_metric, weekLimits));
        ((WeeklyUsageChartView) findViewById(R.id.weeklyUsageChart)).setUsageSeconds(usageSeconds);
        int commonHour = -1;
        for (int hour = 0; hour < 24; hour++) {
            if (weekHours[hour] > 0 && (commonHour < 0 || weekHours[hour] > weekHours[commonHour])) {
                commonHour = hour;
            }
        }
        ((TextView) findViewById(R.id.progressCommonTime)).setText(commonHour < 0
                ? getString(R.string.no_time_pattern)
                : getString(R.string.most_common_time, formatHourRange(commonHour)));
        int mostChosen = -1;
        for (int choice = 0; choice < 3; choice++) {
            if (weekAlternatives[choice] > 0
                    && (mostChosen < 0 || weekAlternatives[choice] > weekAlternatives[mostChosen])) {
                mostChosen = choice;
            }
        }
        String[] labels = {preferences.getReplacementWalk(), preferences.getReplacementWater(),
                preferences.getReplacementTask()};
        ((TextView) findViewById(R.id.progressAlternativeChoice)).setText(mostChosen < 0
                ? getString(R.string.no_alternative_pattern)
                : getResources().getQuantityString(R.plurals.most_chosen_alternative,
                        weekAlternatives[mostChosen], labels[mostChosen],
                        weekAlternatives[mostChosen]));
    }

    private void refreshPermissionRow(int statusId, int buttonId, boolean ready,
                                      int readyText, int missingText) {
        ((TextView) findViewById(statusId)).setText(ready ? readyText : missingText);
        findViewById(buttonId).setVisibility(ready ? View.GONE : View.VISIBLE);
    }

    private void refreshRecovery(boolean active) {
        boolean hasKey = !preferences.getDeactivationKey().isEmpty();
        findViewById(R.id.deactivationKeySetterInputLayout).setVisibility(
                !active && !hasKey ? View.VISIBLE : View.GONE);
        findViewById(R.id.deactivationKeyButton).setVisibility(
                !active ? View.VISIBLE : View.GONE);
        findViewById(R.id.deactivationKeyUnblockerInputLayout).setVisibility(
                active ? View.VISIBLE : View.GONE);
        ((Button) findViewById(R.id.button_blocker_activate)).setText(active
                ? R.string.ButtonBlockerDeactivateLabel : R.string.activate_protection);
        refreshKeyButton();
    }

    private void refreshKeyButton() {
        Button button = findViewById(R.id.deactivationKeyButton);
        if (!preferences.getDeactivationKey().isEmpty()) {
            button.setText(R.string.unset_recovery_key);
            button.setEnabled(true);
        } else {
            EditText input = findViewById(R.id.deactivationKeySetterInputText);
            button.setText(R.string.set_recovery_key);
            button.setEnabled(input.getText() != null && input.getText().length() > 0);
        }
    }

    public void onEditUrlListClick(View view) {
        startActivity(new Intent(this, UrlListEditorActivity.class));
    }

    public void onEditAppPackagesListClick(View view) {
        startActivity(new Intent(this, AppPackagesListEditorActivity.class));
    }

    public void onActivateBlockerListClick(View view) {
        if (!preferences.getIsBlockerActive()) {
            attemptActivation();
            return;
        }
        EditText input = findViewById(R.id.deactivationKeyUnblockerInputText);
        String candidate = input.getText() == null ? "" : input.getText().toString();
        view.setEnabled(false);
        ioExecutor.execute(() -> {
            boolean valid = preferences.verifyDeactivationKey(candidate);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                view.setEnabled(true);
                if (valid) deactivateBlocker(input);
                else Toast.makeText(this, R.string.incorrect_key, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void attemptActivation() {
        if (!isAccessEnabled(AttentionFirewallService.class)) {
            Toast.makeText(this, R.string.permission_required_to_activate, Toast.LENGTH_LONG).show();
            openSetup();
            return;
        }
        if (preferences.getRestrictedAppPackages().isEmpty() && preferences.getRestrictedUrls().isEmpty()) {
            Toast.makeText(this, R.string.rules_required_to_activate, Toast.LENGTH_LONG).show();
            ((BottomNavigationView) findViewById(R.id.bottomNavigation)).setSelectedItemId(R.id.navigation_rules);
            return;
        }
        if (preferences.getDailyAllowanceSeconds() <= 0) {
            Toast.makeText(this, R.string.allowance_required_to_activate, Toast.LENGTH_LONG).show();
            ((BottomNavigationView) findViewById(R.id.bottomNavigation)).setSelectedItemId(R.id.navigation_settings);
            return;
        }
        if (preferences.getDeactivationKey().isEmpty()) {
            Toast.makeText(this, R.string.set_key_first, Toast.LENGTH_SHORT).show();
            ((BottomNavigationView) findViewById(R.id.bottomNavigation)).setSelectedItemId(R.id.navigation_settings);
            return;
        }
        preferences.setIsBlockerActive(true);
        refreshAll();
    }

    private void deactivateBlocker(EditText input) {
        input.setText("");
        preferences.setIsBlockerActive(false);
        populateEditableFields();
        refreshAll();
    }

    public void onDeactivationKeyButtonClick(View view) {
        if (!preferences.getDeactivationKey().isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.remove_key_title)
                    .setMessage(R.string.remove_key_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.remove_key_confirm, (dialog, which) -> {
                        preferences.setDeactivationKey("");
                        ((EditText) findViewById(R.id.deactivationKeySetterInputText)).setText("");
                        refreshAll();
                    }).show();
            return;
        }
        EditText input = findViewById(R.id.deactivationKeySetterInputText);
        String key = input.getText() == null ? "" : input.getText().toString();
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.cannot_be_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        view.setEnabled(false);
        ioExecutor.execute(() -> {
            preferences.setDeactivationKey(key);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                input.setText("");
                view.setEnabled(true);
                refreshAll();
            });
        });
    }

    public void onResetStatsClick(View view) {
        if (preferences.getIsBlockerActive()) {
            Toast.makeText(this, R.string.reset_stats_blocked, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_stats_title)
                .setMessage(R.string.reset_stats_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset_stats_confirm, (dialog, which) -> {
                    if (preferences.getIsBlockerActive()) {
                        Toast.makeText(this, R.string.reset_stats_blocked, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    budgetEngine.resetTodayStatistics();
                    refreshAll();
                    Toast.makeText(this, R.string.all_stats_reset, Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void configureOptionalGuard() {
        preferences.setUninstallGuardEnabled(true);
        DevicePolicyManager manager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, MyDeviceAdminReceiver.class);
        if (manager != null && !manager.isAdminActive(admin)) {
            externalSettingsLauncher.launch(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            getString(R.string.uninstall_permission_optional)));
        } else if (!isAccessEnabled(AttentionFirewallService.class)) {
            openAccessibilitySettings();
        } else {
            refreshAll();
        }
    }

    private void openAccessibilitySettings() {
        externalSettingsLauncher.launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
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

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_help) {
            startActivity(new Intent(this, HelpActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_export_configuration) {
            exportConfigurationLauncher.launch("voward-config.json");
            return true;
        }
        if (item.getItemId() == R.id.action_import_configuration) {
            if (preferences.getIsBlockerActive()) {
                Toast.makeText(this, R.string.deactivate_before_import, Toast.LENGTH_SHORT).show();
            } else {
                importConfigurationLauncher.launch(new String[]{"application/json", "text/json", "text/plain"});
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void writeConfiguration(Uri uri) {
        if (uri == null) return;
        ioExecutor.execute(() -> {
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    getContentResolver().openOutputStream(uri, "wt"), StandardCharsets.UTF_8)) {
                writer.write(preferences.exportPortableState().toString(2));
                runOnUiThread(() -> Toast.makeText(this,
                        R.string.configuration_exported, Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        R.string.configuration_export_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void readConfiguration(Uri uri) {
        if (uri == null) return;
        ioExecutor.execute(() -> {
            try (InputStreamReader reader = new InputStreamReader(
                    getContentResolver().openInputStream(uri), StandardCharsets.UTF_8)) {
                StringBuilder json = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    json.append(buffer, 0, read);
                    if (json.length() > 1_000_000) throw new IllegalArgumentException("Configuration too large");
                }
                int oldAllowance = preferences.getDailyAllowanceSeconds();
                preferences.importPortableState(new JSONObject(json.toString()));
                budgetEngine.updateRemainingBudgetForAllowanceChange(
                        oldAllowance, preferences.getDailyAllowanceSeconds());
                runOnUiThread(() -> {
                    populateEditableFields();
                    refreshAll();
                    Toast.makeText(this, R.string.configuration_imported, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        R.string.configuration_import_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        refreshAll();
    }

    @Override protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private static String formatMinutesSeconds(long seconds) {
        boolean negative = seconds < 0;
        long safe = seconds == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(seconds);
        String value = String.format(Locale.getDefault(), "%d:%02d", safe / 60, safe % 60);
        return negative ? "-" + value : value;
    }

    private static String formatCompactDuration(long seconds) {
        long minutes = Math.max(0, seconds) / 60;
        if (minutes < 60) return minutes + "m";
        return String.format(Locale.getDefault(), "%dh %02dm", minutes / 60, minutes % 60);
    }

    private static String formatHourRange(int hour) {
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                .ofPattern("h a", Locale.getDefault());
        return java.time.LocalTime.of(hour, 0).format(formatter) + "–"
                + java.time.LocalTime.of((hour + 1) % 24, 0).format(formatter);
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return 0; }
    }

    private static float parseFloat(String value) {
        try { return Float.parseFloat(value); } catch (Exception ignored) { return 0; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private interface TextListener { void onTextChanged(String value); }
}
