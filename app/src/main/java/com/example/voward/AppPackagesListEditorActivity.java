package com.example.voward;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;


public class AppPackagesListEditorActivity extends AppCompatActivity {

    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;
    private AppPackagesListRecyclerAdapter adapter;
    private MaterialCheckBox newStrictRuleCheckbox;
    private final ActivityResultLauncher<Intent> appPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                String packageName = result.getData().getStringExtra(
                        InstalledAppPickerActivity.EXTRA_PACKAGE_NAME);
                boolean strict = result.getData().getBooleanExtra(
                        InstalledAppPickerActivity.EXTRA_STRICT, false);
                if (packageName != null) addValidatedPackage(packageName, strict);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_app_rules_modern);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(this);

        TextInputEditText packageNameEditText = findViewById(R.id.packageNameEditText);
        TextInputLayout packageNameInput = findViewById(R.id.packageNameInput);
        Button addButton = findViewById(R.id.addButton);
        Button chooseButton = findViewById(R.id.chooseInstalledAppButton);
        newStrictRuleCheckbox = findViewById(R.id.newStrictRuleCheckbox);
        RecyclerView recyclerView = findViewById(R.id.appPackageRecyclerView);

        adapter = new AppPackagesListRecyclerAdapter(appPreferencesManagerSingleton.getRestrictedAppPackages(), packageName -> {
            appPreferencesManagerSingleton.removeRestrictedAppPackage(packageName);
            refreshList();
        }, (packageName, strict) ->
                appPreferencesManagerSingleton.setRestrictedAppStrict(packageName, strict),
                this::requestSession);

        recyclerView.setAdapter(adapter);
        boolean locked = appPreferencesManagerSingleton.getIsBlockerActive();
        TextView lockedBanner = findViewById(R.id.lockedBanner);
        lockedBanner.setVisibility(locked ? View.VISIBLE : View.GONE);
        // A suspended app cannot be launched, so nothing reaches the gate on its own. On the
        // OEMs whose paused-app dialog offers no details button, tapping a row here is the
        // only route to a session, so it has to be said.
        if (locked) {
            lockedBanner.setText(getString(R.string.rules_locked_read_only)
                    + " " + getString(R.string.app_session_hint));
        }
        findViewById(R.id.editorComposer).setVisibility(View.VISIBLE);
        RuleComposer.bind(this, appPreferencesManagerSingleton.getRestrictedAppPackages().isEmpty());
        refreshList();

        addButton.setOnClickListener(v -> {
            String newAppPackage = packageNameEditText.getText() != null
                    ? packageNameEditText.getText().toString().trim() : "";
            if (!newAppPackage.isEmpty()) {
                packageNameInput.setError(null);
                if (!AppPreferencesManagerSingleton.isPlausiblePackageName(newAppPackage)) {
                    packageNameInput.setError(getString(R.string.invalid_app_package_name));
                    return;
                }
                if (addValidatedPackage(newAppPackage,
                        newStrictRuleCheckbox != null && newStrictRuleCheckbox.isChecked())) {
                    packageNameEditText.setText("");
                }
            }
        });
        chooseButton.setOnClickListener(v -> appPickerLauncher.launch(
                new Intent(this, InstalledAppPickerActivity.class).putExtra(
                        InstalledAppPickerActivity.EXTRA_STRICT,
                        newStrictRuleCheckbox != null && newStrictRuleCheckbox.isChecked())));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /**
     * The way back to the gate for an app rule.
     *
     * <p>A suspended package has no launch to intercept, and the system's "paused by your
     * admin" dialog only reaches {@link SuspendedAppDetailsActivity} on the OEMs that offer a
     * details button. Without this the rule would quietly become a hard block on every other
     * device, which is exactly what strict mode is for.</p>
     */
    private void requestSession(String packageName) {
        if (!appPreferencesManagerSingleton.getIsBlockerActive()) return;
        if (appPreferencesManagerSingleton.isStrictRestrictedApp(packageName)) {
            Toast.makeText(this, R.string.strict_rule_no_session, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // Suspension does not hide a package, so this still resolves for a paused app.
            // A rule written ahead of the install it names is what it screens out.
            getPackageManager().getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException notInstalled) {
            Toast.makeText(this, R.string.app_session_not_installed, Toast.LENGTH_SHORT).show();
            return;
        }
        appPreferencesManagerSingleton.setLastInterceptedApp(packageName);
        appPreferencesManagerSingleton.setLastInterceptedUrl("");
        appPreferencesManagerSingleton.setLastInterceptionKind("APP");
        startActivity(new Intent(this, DecisionGateActivity.class));
    }

    private void refreshList() {
        adapter.updateList(appPreferencesManagerSingleton.getRestrictedAppPackages());
        findViewById(R.id.emptyState).setVisibility(
                appPreferencesManagerSingleton.getRestrictedAppPackages().isEmpty()
                        ? View.VISIBLE : View.GONE);
    }

    private boolean addValidatedPackage(String packageName, boolean strict) {
        if (SafetyPolicy.isCriticalPackage(this, packageName)) {
            Toast.makeText(this, R.string.critical_app_cannot_be_blocked, Toast.LENGTH_LONG).show();
            return false;
        }
        if (!AppPreferencesManagerSingleton.isPlausiblePackageName(packageName)) return false;
        appPreferencesManagerSingleton.addRestrictedAppPackage(packageName, strict);
        if (newStrictRuleCheckbox != null) newStrictRuleCheckbox.setChecked(false);
        refreshList();
        return true;
    }

}
