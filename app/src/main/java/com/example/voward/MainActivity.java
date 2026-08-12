package com.example.voward;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.Locale;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private AppPreferencesManagerSingleton appPreferencesManager;
    private AttentionBudgetEngine budgetEngine;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private boolean attemptedDeviceAdmin;
    private boolean attemptedAccessibility;
    private boolean attemptedNotification;

    private final ActivityResultLauncher<Intent> setupLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> continueInitialSetup());
    private final ActivityResultLauncher<String> notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> continueInitialSetup());
    private final ActivityResultLauncher<String> exportConfigurationLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), this::writeConfiguration);
    private final ActivityResultLauncher<String[]> importConfigurationLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::readConfiguration);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        appPreferencesManager = AppPreferencesManagerSingleton.getInstance(this);
        budgetEngine = new AttentionBudgetEngine(this);
        budgetEngine.resetBudgetIfNeeded();

        this.backButtonPressedDispatcher();
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        initializeUI();
        setWatchers();
        updateUiStates();
        if (savedInstanceState == null) beginInitialSetupWithDisclosure();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_help) {
            startActivity(new Intent(this, HelpActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_export_configuration) {
            exportConfigurationLauncher.launch("voward-config.json");
            return true;
        }
        if (item.getItemId() == R.id.action_import_configuration) {
            if (appPreferencesManager.getIsBlockerActive()) {
                Toast.makeText(this, R.string.deactivate_before_import, Toast.LENGTH_SHORT).show();
            } else {
                importConfigurationLauncher.launch(new String[]{"application/json", "text/json", "text/plain"});
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void initializeUI() {
        refreshBlockerButton();
        refreshKeyButton();
        
        findViewById(R.id.deactivationKeySetterInputLayout).setVisibility(
            appPreferencesManager.getDeactivationKey().isEmpty() ? View.VISIBLE : View.INVISIBLE);
        findViewById(R.id.deactivationKeyUnblockerInputLayout).setVisibility(
            appPreferencesManager.getIsBlockerActive() ? View.VISIBLE : View.INVISIBLE);

        ((SwitchCompat) findViewById(R.id.uninstallGuardSwitch)).setChecked(
                appPreferencesManager.isUninstallGuardEnabled());

        // User-facing amounts are minutes; storage uses seconds.
        ((EditText) findViewById(R.id.dailyBudgetInput)).setText(String.valueOf(
                appPreferencesManager.getDailyAllowanceSeconds() / 60));
        ((EditText) findViewById(R.id.baseWaitInput)).setText(String.valueOf(appPreferencesManager.getBaseWaitTimeSeconds()));
        ((EditText) findViewById(R.id.reentryGrowthInput)).setText(String.format(
                Locale.getDefault(), "%.0f", appPreferencesManager.getReentryGrowth() * 100));
        ((EditText) findViewById(R.id.defaultSessionInput)).setText(String.valueOf(
                appPreferencesManager.getDefaultSessionSeconds() / 60));
        ((EditText) findViewById(R.id.functionalGoalInput)).setText(
                appPreferencesManager.getFunctionalGoal());
        refreshProgressSummary();
    }

    private void setWatchers() {
        ((SwitchCompat) findViewById(R.id.uninstallGuardSwitch)).setOnCheckedChangeListener(
                (v, checked) -> {
                    if (!appPreferencesManager.getIsBlockerActive()) {
                        appPreferencesManager.setUninstallGuardEnabled(checked);
                    }
                });

        ((EditText) findViewById(R.id.dailyBudgetInput)).addTextChangedListener(new SimpleWatcher(s -> {
            if (appPreferencesManager.getIsBlockerActive()) return;
            try { 
                int minutes = Math.max(0, Math.min(1440, Integer.parseInt(s)));
                int allowanceSeconds = minutes * 60;
                int oldAllowanceSeconds = appPreferencesManager.getDailyAllowanceSeconds();
                appPreferencesManager.setDailyAllowanceSeconds(allowanceSeconds);
                budgetEngine.updateRemainingBudgetForAllowanceChange(
                        oldAllowanceSeconds, appPreferencesManager.getDailyAllowanceSeconds());
            } catch (Exception ignored) {}
        }));
        ((EditText) findViewById(R.id.baseWaitInput)).addTextChangedListener(new SimpleWatcher(s -> {
            if (appPreferencesManager.getIsBlockerActive()) return;
            try { appPreferencesManager.setBaseWaitTimeSeconds(Integer.parseInt(s)); } catch (Exception ignored) {}
        }));
        ((EditText) findViewById(R.id.reentryGrowthInput)).addTextChangedListener(new SimpleWatcher(s -> {
            if (appPreferencesManager.getIsBlockerActive()) return;
            try { appPreferencesManager.setReentryGrowth(Float.parseFloat(s) / 100f); }
            catch (Exception ignored) {}
        }));
        ((EditText) findViewById(R.id.defaultSessionInput)).addTextChangedListener(new SimpleWatcher(s -> {
            if (appPreferencesManager.getIsBlockerActive()) return;
            try { appPreferencesManager.setDefaultSessionSeconds(Integer.parseInt(s) * 60); }
            catch (Exception ignored) {}
        }));
        ((EditText) findViewById(R.id.functionalGoalInput)).addTextChangedListener(
                new SimpleWatcher(s -> {
                    if (!appPreferencesManager.getIsBlockerActive()) {
                        appPreferencesManager.setFunctionalGoal(s);
                    }
                }));

        ((EditText) findViewById(R.id.deactivationKeySetterInputText)).addTextChangedListener(new SimpleWatcher(s -> refreshKeyButton()));
    }

    private void updateUiStates() {
        boolean active = appPreferencesManager.getIsBlockerActive();
        findViewById(R.id.uninstallGuardSwitch).setEnabled(!active);
        findViewById(R.id.dailyBudgetInput).setEnabled(!active);
        findViewById(R.id.baseWaitInput).setEnabled(!active);
        findViewById(R.id.reentryGrowthInput).setEnabled(!active);
        findViewById(R.id.defaultSessionInput).setEnabled(!active);
        findViewById(R.id.functionalGoalInput).setEnabled(!active);

        findViewById(R.id.dailyBudgetInputLayout).setEnabled(!active);
        findViewById(R.id.baseWaitInputLayout).setEnabled(!active);
        findViewById(R.id.reentryGrowthInputLayout).setEnabled(!active);
        findViewById(R.id.defaultSessionInputLayout).setEnabled(!active);
        findViewById(R.id.functionalGoalInputLayout).setEnabled(!active);
        findViewById(R.id.deactivationKeySetterInputLayout).setEnabled(!active);
        findViewById(R.id.deactivationKeySetterInputText).setEnabled(!active);
        findViewById(R.id.deactivationKeyButton).setEnabled(!active);
        View resetStatsButton = findViewById(R.id.button_reset_stats);
        resetStatsButton.setEnabled(!active);
        resetStatsButton.setAlpha(active ? 0.38f : 1f);
        findViewById(R.id.button_go_to_edit_urls).setEnabled(!active);
        findViewById(R.id.button_go_to_edit_packages).setEnabled(!active);
    }

    public void onEditUrlListClick(View v) { startActivity(new Intent(this, UrlListEditorActivity.class)); }
    public void onEditAppPackagesListClick(View v) { startActivity(new Intent(this, AppPackagesListEditorActivity.class)); }

    public void onActivateBlockerListClick(View v) {
        if (appPreferencesManager.getIsBlockerActive()) {
            EditText input = findViewById(R.id.deactivationKeyUnblockerInputText);
            String candidate = input.getText().toString();
            v.setEnabled(false);
            ioExecutor.execute(() -> {
                boolean valid = appPreferencesManager.verifyDeactivationKey(candidate);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    v.setEnabled(true);
                    if (valid) deactivateBlocker(input);
                    else Toast.makeText(this, R.string.incorrect_key, Toast.LENGTH_SHORT).show();
                });
            });
        } else {
            if (appPreferencesManager.getDeactivationKey().isEmpty()) {
                Toast.makeText(this, getString(R.string.set_key_first), Toast.LENGTH_SHORT).show();
                return;
            }
            appPreferencesManager.setIsBlockerActive(true);
            initializeUI();
            updateUiStates();
        }
    }

    private void deactivateBlocker(EditText input) {
        input.setText("");
        appPreferencesManager.setIsBlockerActive(false);
        initializeUI();
        updateUiStates();
    }

    public void onDeactivationKeyButtonClick(View v) {
        EditText input = findViewById(R.id.deactivationKeySetterInputText);
        if (appPreferencesManager.getDeactivationKey().isEmpty()) {
            if (!input.getText().toString().isEmpty()) {
                String key = input.getText().toString();
                v.setEnabled(false);
                ioExecutor.execute(() -> {
                    appPreferencesManager.setDeactivationKey(key);
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        input.setText("");
                        v.setEnabled(true);
                        initializeUI();
                    });
                });
                return;
            } else {
                Toast.makeText(this, getString(R.string.cannot_be_empty), Toast.LENGTH_SHORT).show();
            }
        } else {
            appPreferencesManager.setDeactivationKey("");
        }
        input.setText("");
        initializeUI();
    }

    public void onResetStatsClick(View v) {
        if (appPreferencesManager.getIsBlockerActive()) {
            Toast.makeText(this, R.string.reset_stats_blocked, Toast.LENGTH_SHORT).show();
            return;
        }
        budgetEngine.resetTodayStatistics();
        initializeUI();
        Toast.makeText(this, getString(R.string.all_stats_reset), Toast.LENGTH_SHORT).show();
    }

    private void refreshProgressSummary() {
        long remaining = budgetEngine.getRemainingBudget();
        int sessions = appPreferencesManager.getDailySessionCount();
        int early = appPreferencesManager.getSessionsEndedEarlyCount();
        int limits = appPreferencesManager.getSessionLimitReachedCount();
        String grayscale = GrayscaleController.isGrayscaleAvailable(this)
                ? getString(R.string.grayscale_available) : getString(R.string.grayscale_unavailable);
        ((TextView) findViewById(R.id.progress_summary)).setText(getString(
                R.string.progress_summary_template, formatMinutesSeconds(remaining),
                sessions, early, limits, grayscale));
    }

    private static String formatMinutesSeconds(long seconds) {
        boolean negative = seconds < 0;
        long safe = seconds == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(seconds);
        String value = String.format(Locale.getDefault(), "%d:%02d", safe / 60, safe % 60);
        return negative ? "-" + value : value;
    }

    private void refreshBlockerButton() {
        ((Button) findViewById(R.id.button_blocker_activate)).setText(appPreferencesManager.getIsBlockerActive() ? R.string.ButtonBlockerDeactivateLabel : R.string.ButtonBlockerActivateLabel);
    }

    private void refreshKeyButton() {
        Button b = findViewById(R.id.deactivationKeyButton);
        b.setText(appPreferencesManager.getDeactivationKey().isEmpty() ? R.string.BlockerDeactivationKeySetButtonlabel : R.string.BlockerDeactivationKeyUnsetButtonlabel);
    }

    private void backButtonPressedDispatcher() { getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) { @Override public void handleOnBackPressed() { finish(); } }); }

    private void beginInitialSetupWithDisclosure() {
        if (appPreferencesManager.getPermissionDisclosureAccepted()) {
            continueInitialSetup();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_disclosure_title)
                .setMessage(R.string.setup_disclosure_message)
                .setPositiveButton(R.string.setup_continue, (dialog, which) -> {
                    appPreferencesManager.setPermissionDisclosureAccepted(true);
                    continueInitialSetup();
                })
                .setNegativeButton(R.string.setup_not_now, null)
                .show();
    }

    private void continueInitialSetup() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, MyDeviceAdminReceiver.class);
        if (!attemptedDeviceAdmin && dpm != null && !dpm.isAdminActive(admin)) {
            attemptedDeviceAdmin = true;
            setupLauncher.launch(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protect uninstallation."));
            return;
        }
        boolean firewallEnabled = isAccessEnabled(this, AttentionFirewallService.class);
        if (!attemptedAccessibility && !firewallEnabled) {
            attemptedAccessibility = true;
            setupLauncher.launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (!attemptedNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            attemptedNotification = true;
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (budgetEngine != null) {
            budgetEngine.resetBudgetIfNeeded();
            refreshProgressSummary();
        }
    }

    private boolean isAccessEnabled(Context context, Class<?> serviceClass) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component = context.getPackageName() + "/" + serviceClass.getName();
        return enabled != null && enabled.toLowerCase(Locale.ROOT).contains(component.toLowerCase(Locale.ROOT));
    }

    private void writeConfiguration(Uri uri) {
        if (uri == null) return;
        ioExecutor.execute(() -> {
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    getContentResolver().openOutputStream(uri, "wt"), StandardCharsets.UTF_8)) {
                writer.write(appPreferencesManager.exportPortableState().toString(2));
                runOnUiThread(() -> Toast.makeText(this, R.string.configuration_exported, Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.configuration_export_failed, Toast.LENGTH_LONG).show());
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
                int oldAllowance = appPreferencesManager.getDailyAllowanceSeconds();
                appPreferencesManager.importPortableState(new JSONObject(json.toString()));
                budgetEngine.updateRemainingBudgetForAllowanceChange(
                        oldAllowance, appPreferencesManager.getDailyAllowanceSeconds());
                runOnUiThread(() -> {
                    initializeUI();
                    updateUiStates();
                    Toast.makeText(this, R.string.configuration_imported, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.configuration_import_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private interface TextListener { void onTextChanged(String s); }
    private static class SimpleWatcher implements TextWatcher {
        private final TextListener l;
        public SimpleWatcher(TextListener l) { this.l = l; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { l.onTextChanged(s.toString()); }
        @Override public void afterTextChanged(Editable s) {}
    }
}
