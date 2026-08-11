package com.example.voward;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
        Button addButton = findViewById(R.id.addButton);
        Button chooseButton = findViewById(R.id.chooseInstalledAppButton);
        newStrictRuleCheckbox = findViewById(R.id.newStrictRuleCheckbox);
        RecyclerView recyclerView = findViewById(R.id.appPackageRecyclerView);

        adapter = new AppPackagesListRecyclerAdapter(appPreferencesManagerSingleton.getRestrictedAppPackages(), packageName -> {
            appPreferencesManagerSingleton.removeRestrictedAppPackage(packageName);
            refreshList();
        }, (packageName, strict) ->
                appPreferencesManagerSingleton.setRestrictedAppStrict(packageName, strict));

        recyclerView.setAdapter(adapter);
        boolean locked = appPreferencesManagerSingleton.getIsBlockerActive();
        findViewById(R.id.lockedBanner).setVisibility(locked ? View.VISIBLE : View.GONE);
        findViewById(R.id.editorComposer).setVisibility(locked ? View.GONE : View.VISIBLE);
        refreshList();

        addButton.setOnClickListener(v -> {
            if (appPreferencesManagerSingleton.getIsBlockerActive()) {
                Toast.makeText(this, R.string.blocker_active_cannot_change, Toast.LENGTH_SHORT).show();
                return;
            }
            String newAppPackage = packageNameEditText.getText() != null
                    ? packageNameEditText.getText().toString().trim() : "";
            if (!newAppPackage.isEmpty()) {
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

    private void refreshList() {
        adapter.updateList(appPreferencesManagerSingleton.getRestrictedAppPackages());
        findViewById(R.id.emptyState).setVisibility(
                appPreferencesManagerSingleton.getRestrictedAppPackages().isEmpty()
                        ? View.VISIBLE : View.GONE);
    }

    private boolean addValidatedPackage(String packageName, boolean strict) {
        if (SafetyPolicy.isCriticalPackage(packageName, getPackageName())) {
            Toast.makeText(this, R.string.critical_app_cannot_be_blocked, Toast.LENGTH_LONG).show();
            return false;
        }
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Toast.makeText(this, R.string.package_not_installed, Toast.LENGTH_LONG).show();
            return false;
        }
        appPreferencesManagerSingleton.addRestrictedAppPackage(packageName, strict);
        if (newStrictRuleCheckbox != null) newStrictRuleCheckbox.setChecked(false);
        refreshList();
        return true;
    }

    private void showInstalledAppPicker() {
        if (appPreferencesManagerSingleton.getIsBlockerActive()) {
            Toast.makeText(this, R.string.blocker_active_cannot_change, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(launcherIntent, 0);
        List<AppChoice> choices = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (SafetyPolicy.isCriticalPackage(packageName, getPackageName())) continue;
            String label = String.valueOf(info.loadLabel(getPackageManager()));
            choices.add(new AppChoice(label, packageName, info.loadIcon(getPackageManager())));
        }
        choices.sort(Comparator.comparing(choice -> choice.label, String.CASE_INSENSITIVE_ORDER));
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);
        EditText search = new EditText(this);
        search.setHint(R.string.search_apps);
        search.setSingleLine(true);
        ListView list = new ListView(this);
        AppChoiceAdapter pickerAdapter = new AppChoiceAdapter(choices);
        list.setAdapter(pickerAdapter);
        container.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (360 * getResources().getDisplayMetrics().density)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.choose_installed_app)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            addValidatedPackage(pickerAdapter.getItem(position).packageName,
                    newStrictRuleCheckbox != null && newStrictRuleCheckbox.isChecked());
            dialog.dismiss();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                pickerAdapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        dialog.show();
    }

    private static final class AppChoice {
        final String label;
        final String packageName;
        final Drawable icon;
        AppChoice(String label, String packageName, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
        }
    }

    private final class AppChoiceAdapter extends BaseAdapter {
        private final List<AppChoice> all;
        private final List<AppChoice> shown;

        AppChoiceAdapter(List<AppChoice> choices) {
            all = new ArrayList<>(choices);
            shown = new ArrayList<>(choices);
        }

        void filter(String query) {
            String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (AppChoice choice : all) {
                if (needle.isEmpty() || choice.label.toLowerCase(Locale.ROOT).contains(needle)
                        || choice.packageName.toLowerCase(Locale.ROOT).contains(needle)) {
                    shown.add(choice);
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public AppChoice getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            AppChoice choice = getItem(position);
            TextView title = row.findViewById(android.R.id.text1);
            TextView subtitle = row.findViewById(android.R.id.text2);
            title.setText(choice.label);
            subtitle.setText(choice.packageName);
            int iconSize = (int) (40 * getResources().getDisplayMetrics().density);
            if (choice.icon != null) choice.icon.setBounds(0, 0, iconSize, iconSize);
            title.setCompoundDrawablePadding((int) (12 * getResources().getDisplayMetrics().density));
            title.setCompoundDrawables(choice.icon, null, null, null);
            return row;
        }
    }
}
