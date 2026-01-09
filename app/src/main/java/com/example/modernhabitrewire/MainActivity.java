package com.example.modernhabitrewire;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private AppPreferencesManagerSingleton appPreferencesManager;
    private DopamineBudgetEngine budgetEngine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appPreferencesManager = AppPreferencesManagerSingleton.getInstance(this);
        budgetEngine = new DopamineBudgetEngine(this);

        this.backButtonPressedDispatcher();
        this.requestDeviceAdminPermission();
        this.requestAccessibilityPermission();
        this.requestNotificationPermission();
        this.requestPostNotificationPermision();

        initializeUI();
        setWatchers();
        updateUiStates();
    }

    private void initializeUI() {
        refreshBlockerButton();
        refreshKeyButton();
        
        findViewById(R.id.deactivationKeySetterInputText).setVisibility(
            appPreferencesManager.getDeactivationKey().isEmpty() ? View.VISIBLE : View.INVISIBLE);
        findViewById(R.id.deactivationKeyUnblockerInputText).setVisibility(
            appPreferencesManager.getIsBlockerActive() ? View.VISIBLE : View.INVISIBLE);

        ((SwitchCompat) findViewById(R.id.bypassSwitch)).setChecked(appPreferencesManager.getBypassSwitchValue());
        ((SwitchCompat) findViewById(R.id.forbidSettingsSwitch)).setChecked(appPreferencesManager.getForbidSettingsSwitchValue());

        // Sync restored physics settings to UI
        ((EditText) findViewById(R.id.dailyBudgetInput)).setText(String.valueOf(appPreferencesManager.getDailyBudgetMinutes()));
        ((EditText) findViewById(R.id.baseWaitInput)).setText(String.valueOf(appPreferencesManager.getBaseWaitTimeSeconds()));
        ((EditText) findViewById(R.id.costFactorInput)).setText(String.format(Locale.getDefault(), "%.1f", appPreferencesManager.getCostIncrementFactor()));
    }

    private void setWatchers() {
        ((SwitchCompat) findViewById(R.id.bypassSwitch)).setOnCheckedChangeListener((v, checked) -> appPreferencesManager.setBypassSwitchValue(checked));
        ((SwitchCompat) findViewById(R.id.forbidSettingsSwitch)).setOnCheckedChangeListener((v, checked) -> appPreferencesManager.setForbidSettingsSwitchValue(checked));

        ((EditText) findViewById(R.id.dailyBudgetInput)).addTextChangedListener(new SimpleWatcher(s -> {
            try { 
                int mins = Integer.parseInt(s);
                appPreferencesManager.setDailyBudgetMinutes(mins);
                budgetEngine.updateRemainingBudgetOnly();
            } catch (Exception ignored) {}
        }));
        ((EditText) findViewById(R.id.baseWaitInput)).addTextChangedListener(new SimpleWatcher(s -> {
            try { appPreferencesManager.setBaseWaitTimeSeconds(Integer.parseInt(s)); } catch (Exception ignored) {}
        }));
        ((EditText) findViewById(R.id.costFactorInput)).addTextChangedListener(new SimpleWatcher(s -> {
            try { appPreferencesManager.setCostIncrementFactor(Float.parseFloat(s)); } catch (Exception ignored) {}
        }));

        ((EditText) findViewById(R.id.deactivationKeySetterInputText)).addTextChangedListener(new SimpleWatcher(s -> refreshKeyButton()));
    }

    private void updateUiStates() {
        boolean active = appPreferencesManager.getIsBlockerActive();
        findViewById(R.id.bypassSwitch).setEnabled(!active);
        findViewById(R.id.forbidSettingsSwitch).setEnabled(!active);
        findViewById(R.id.dailyBudgetInput).setEnabled(!active);
        findViewById(R.id.baseWaitInput).setEnabled(!active);
        findViewById(R.id.costFactorInput).setEnabled(!active);
        findViewById(R.id.deactivationKeySetterInputText).setEnabled(!active);
        findViewById(R.id.deactivationKeyButton).setEnabled(!active);
        findViewById(R.id.button_reset_stats).setEnabled(!active);
    }

    public void onEditUrlListClick(View v) { startActivity(new Intent(this, UrlListEditorActivity.class)); }
    public void onEditAppPackagesListClick(View v) { startActivity(new Intent(this, AppPackagesListEditorActivity.class)); }

    public void onActivateBlockerListClick(View v) {
        if (appPreferencesManager.getIsBlockerActive()) {
            EditText input = findViewById(R.id.deactivationKeyUnblockerInputText);
            if (appPreferencesManager.getDeactivationKey().equals(input.getText().toString()) || (appPreferencesManager.getBypassSwitchValue() && ChargingState.isCharging)) {
                input.setText("");
                appPreferencesManager.setIsBlockerActive(false);
                initializeUI();
                updateUiStates();
            } else {
                Toast.makeText(this, "Incorrect Key", Toast.LENGTH_SHORT).show();
            }
        } else {
            if (appPreferencesManager.getDeactivationKey().isEmpty()) {
                Toast.makeText(this, "Set key first", Toast.LENGTH_SHORT).show();
                return;
            }
            appPreferencesManager.setIsBlockerActive(true);
            initializeUI();
            updateUiStates();
        }
    }

    public void onDeactivationKeyButtonClick(View v) {
        EditText input = findViewById(R.id.deactivationKeySetterInputText);
        if (appPreferencesManager.getDeactivationKey().isEmpty()) {
            if (!input.getText().toString().isEmpty()) {
                appPreferencesManager.setDeactivationKey(input.getText().toString());
            } else {
                Toast.makeText(this, "Cannot be empty", Toast.LENGTH_SHORT).show();
            }
        } else {
            appPreferencesManager.setDeactivationKey("");
        }
        input.setText("");
        initializeUI();
    }

    public void onResetStatsClick(View v) {
        budgetEngine.resetAllStats();
        initializeUI();
        Toast.makeText(this, "All stats reset", Toast.LENGTH_SHORT).show();
    }

    private void refreshBlockerButton() {
        ((Button) findViewById(R.id.button_blocker_activate)).setText(appPreferencesManager.getIsBlockerActive() ? R.string.ButtonBlockerDeactivateLabel : R.string.ButtonBlockerActivateLabel);
    }

    private void refreshKeyButton() {
        Button b = findViewById(R.id.deactivationKeyButton);
        b.setText(appPreferencesManager.getDeactivationKey().isEmpty() ? R.string.BlockerDeactivationKeySetButtonlabel : R.string.BlockerDeactivationKeyUnsetButtonlabel);
    }

    private void backButtonPressedDispatcher() { getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) { @Override public void handleOnBackPressed() { finish(); } }); }
    private void requestDeviceAdminPermission() { startActivity(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, MyDeviceAdminReceiver.class)).putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protect uninstallation.")); }
    private void requestNotificationPermission() { if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); } }
    private void requestPostNotificationPermision() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { ActivityCompat.requestPermissions(this, new String[]{ android.Manifest.permission.POST_NOTIFICATIONS }, 1); } }
    private void requestAccessibilityPermission() { if (!isAccessEnabled(this, AttentionFirewallService.class)) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } }
    private boolean isAccessEnabled(Context c, Class<?> s) { String p = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES); return p != null && p.toLowerCase().contains((c.getPackageName() + "/" + s.getName()).toLowerCase()); }

    private interface TextListener { void onTextChanged(String s); }
    private static class SimpleWatcher implements TextWatcher {
        private final TextListener l;
        public SimpleWatcher(TextListener l) { this.l = l; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { l.onTextChanged(s.toString()); }
        @Override public void afterTextChanged(Editable s) {}
    }
}
