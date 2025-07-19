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
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.Objects;

public class MainActivity extends AppCompatActivity {
    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

//        Log.d("MainActivity", "Created.");
        this.backButtonPressedDispatcher();
        this.requestDeviceAdminPermission();
        this.requestAccessibilityPermission();
        this.requestNotificationPermission();
        this.requestPostNotificationPermision();

        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(this);

        initializeBlockerTogglerButton();
        initializeDeactivationKeySetterButton();
        initializeDeactivationKeySetterTextViewState();
        initializeDeactivationKeyDeblockerTextViewState();
        initializeBypassSwitchState();
        initializeForbidSettingsSwitchState();
        setKeyDeactivationTextWatcher();
        setBypassSwitchWatcher();
        setForbidSettingsSwitchWatcher();

    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    public void onEditUrlListClick(View view){
        Intent intent = new Intent(MainActivity.this,UrlListEditorActivity.class);
        startActivity(intent);
    }

    public void onEditAppPackagesListClick(View view){
        Intent intent = new Intent(MainActivity.this,AppPackagesListEditorActivity.class);
        startActivity(intent);
    }

    public void onActivateBlockerListClick(View view){
//        ChargingState.isCharging
        if(appPreferencesManagerSingleton.getIsBlockerActive()){
            startBlockerDeactivationFlow();
        } else {
            startBlockerActivationFlow();
        }
    }

    private void initializeDeactivationKeySetterTextViewState(){
        TextView keyTextView =  findViewById(R.id.deactivationKeySetterInputText);
        keyTextView.setEnabled(!appPreferencesManagerSingleton.getIsBlockerActive());
        if(appPreferencesManagerSingleton.getIsBlockerActive() || !appPreferencesManagerSingleton.getDeactivationKey().isEmpty()) {
            keyTextView.setVisibility(View.INVISIBLE);
            keyTextView.clearFocus();
        } else {
            keyTextView.setVisibility(View.VISIBLE);
        }

    }
    private void initializeDeactivationKeyDeblockerTextViewState(){
        TextView keyTextView =  findViewById(R.id.deactivationKeyUnblockerInputText);
        keyTextView.setEnabled(appPreferencesManagerSingleton.getIsBlockerActive());
        if(appPreferencesManagerSingleton.getIsBlockerActive() && !appPreferencesManagerSingleton.getDeactivationKey().isEmpty()) {
            keyTextView.setVisibility(View.VISIBLE);
        } else {
            keyTextView.setVisibility(View.INVISIBLE);
            keyTextView.clearFocus();
        }

    }

    private void initializeBypassSwitchState(){
        SwitchCompat bypassSwitch =  findViewById(R.id.bypassSwitch);
        bypassSwitch.setChecked(appPreferencesManagerSingleton.getBypassSwitchValue());
        bypassSwitch.setEnabled(!appPreferencesManagerSingleton.getIsBlockerActive());
    }

    private void initializeForbidSettingsSwitchState(){
        SwitchCompat forbidSettingsSwitch =  findViewById(R.id.forbidSettingsSwitch);
        forbidSettingsSwitch.setChecked(appPreferencesManagerSingleton.getForbidSettingsSwitchValue());
        forbidSettingsSwitch.setEnabled(!appPreferencesManagerSingleton.getIsBlockerActive());
    }

    public void onDeactivationKeyButtonClick(View view){
        TextView keyTextView =  findViewById(R.id.deactivationKeySetterInputText);
        String text = keyTextView.getText().toString();
        if(Objects.equals(appPreferencesManagerSingleton.getDeactivationKey(), "")){

            if (!text.isEmpty()){
                appPreferencesManagerSingleton.setDeactivationKey(text);
            } else {
                Toast toast = Toast.makeText(this, "Error. Key cannot be empty.",  Toast.LENGTH_SHORT);
                toast.show();
            }
        } else {
            appPreferencesManagerSingleton.setDeactivationKey("");
        }
        keyTextView.setText("");
        keyTextView.clearFocus();
        initializeDeactivationKeySetterTextViewState();

    }

    public void startBlockerDeactivationFlow(){
        TextView keyTextView =  findViewById(R.id.deactivationKeyUnblockerInputText);
        String keyText = keyTextView.getText().toString();
        if(appPreferencesManagerSingleton.getDeactivationKey().equals(keyText) || isBypassingByChargingState()){
            keyTextView.setText("");
            appPreferencesManagerSingleton.setIsBlockerActive(false);
            onBlockerActiveStateChange();

        } else {
            Toast toast = Toast.makeText(this, "Error. Deactivation key is incorrect.",  Toast.LENGTH_SHORT);
            toast.show();
        }

    }
    public void startBlockerActivationFlow(){
        if(appPreferencesManagerSingleton.getDeactivationKey().isEmpty()){
            Toast toast = Toast.makeText(this, "Error. Set deactivation key first.",  Toast.LENGTH_SHORT);
            toast.show();
            return;
        }
        appPreferencesManagerSingleton.setIsBlockerActive(true);
        onBlockerActiveStateChange();
    }

    private Boolean isBypassingByChargingState(){
        if(appPreferencesManagerSingleton.getBypassSwitchValue()) {
            return ChargingState.isCharging;
        } else {
            return false;
        }
    }

    private void setKeyDeactivationTextWatcher() {
        TextView keyTextView =  findViewById(R.id.deactivationKeySetterInputText);
        keyTextView.addTextChangedListener(new TextWatcher() {

            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            public void afterTextChanged(Editable s) {
                initializeDeactivationKeySetterButton();
            }
        });
    }
    private void setBypassSwitchWatcher() {
        SwitchCompat bypassSwitch =  findViewById(R.id.bypassSwitch);
        bypassSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            this.appPreferencesManagerSingleton.setBypassSwitchValue(isChecked);
        });
    }

    private void setForbidSettingsSwitchWatcher() {
        SwitchCompat forbidSettingsSwitch =  findViewById(R.id.forbidSettingsSwitch);
        forbidSettingsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            this.appPreferencesManagerSingleton.setForbidSettingsSwitchValue(isChecked);
        });
    }

    private void onBlockerActiveStateChange() {

        initializeBlockerTogglerButton();
        initializeDeactivationKeySetterButton();
        initializeDeactivationKeyDeblockerTextViewState();
        initializeBypassSwitchState();
        initializeForbidSettingsSwitchState();
    }

    private void initializeBlockerTogglerButton(){
        Button togglerButton = findViewById(R.id.button_blocker_activate);
        if(appPreferencesManagerSingleton.getIsBlockerActive()){
            togglerButton.setText(R.string.ButtonBlockerDeactivateLabel);
        } else {
            togglerButton.setText(R.string.ButtonBlockerActivateLabel);
        }
    }

    private void initializeDeactivationKeySetterButton(){
        Button deactivationKeyButton = findViewById(R.id.deactivationKeyButton);
        TextView keyTextView =  findViewById(R.id.deactivationKeySetterInputText);
        String currentKeyTextBox = keyTextView.getText().toString();
        String savedPrefKey = appPreferencesManagerSingleton.getDeactivationKey();

        if(savedPrefKey.isEmpty()){
            deactivationKeyButton.setText(R.string.BlockerDeactivationKeySetButtonlabel);
        } else {
            deactivationKeyButton.setText(R.string.BlockerDeactivationKeyUnsetButtonlabel);
        }

//        deactivationKeyButton.setEnabled(!appPreferencesManager.getIsBlockerActive() || !currentKeyTextBox.isEmpty() || !savedPrefKey.isEmpty());
        if(appPreferencesManagerSingleton.getIsBlockerActive()){
            deactivationKeyButton.setEnabled(false);
        } else if (!savedPrefKey.isEmpty()) {
            deactivationKeyButton.setEnabled(true);
        } else if (currentKeyTextBox.isEmpty()) {
            deactivationKeyButton.setEnabled(false);
        } else {
            deactivationKeyButton.setEnabled(true);

        }
    }

    private void backButtonPressedDispatcher(){
        this.getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });
    }

    private void requestDeviceAdminPermission() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, MyDeviceAdminReceiver.class));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enable device administration to protect app closing and uninstallation.");
        startActivity(intent);
    }

    private void requestNotificationPermission() {
        if(!NotificationManagerCompat.from(this).areNotificationsEnabled()){
            Intent notificationSettingsIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            notificationSettingsIntent.putExtra(Settings.EXTRA_APP_PACKAGE, this.getPackageName());
            startActivity(notificationSettingsIntent);
        }
    }
    private void requestPostNotificationPermision(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String permission = android.Manifest.permission.POST_NOTIFICATIONS;
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{ permission }, 1);
            }
        }
    }

    private void requestAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled(this, ScreenReaderAccessibilityService.class)) {
            Intent accessibilitySettingsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(accessibilitySettingsIntent);
        }
    }


    private boolean isAccessibilityServiceEnabled(Context context, Class<?> service) {
        String prefString = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (prefString == null) return false;
        final String serviceId = context.getPackageName() + "/" + service.getName();
        return prefString.toLowerCase().contains(serviceId.toLowerCase());
    }







}