package com.example.voward;

import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Searchable full-screen app chooser that returns a package identifier. */
public class InstalledAppPickerActivity extends AppCompatActivity {
    public static final String EXTRA_PACKAGE_NAME = "selected_package_name";
    public static final String EXTRA_STRICT = "selected_package_strict";
    private AppAdapter adapter;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_installed_app_picker);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        List<AppChoice> choices = loadChoices();
        adapter = new AppAdapter(choices);
        ListView list = findViewById(R.id.appsList);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent result = new Intent()
                    .putExtra(EXTRA_PACKAGE_NAME, adapter.getItem(position).packageName)
                    .putExtra(EXTRA_STRICT, getIntent().getBooleanExtra(EXTRA_STRICT, false));
            setResult(RESULT_OK, result);
            finish();
        });

        ((EditText) findViewById(R.id.searchInput)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
                refreshEmptyState();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        refreshEmptyState();
    }

    private List<AppChoice> loadChoices() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(launcher, 0);
        List<AppChoice> choices = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (SafetyPolicy.isCriticalPackage(packageName, getPackageName())) continue;
            choices.add(new AppChoice(String.valueOf(info.loadLabel(getPackageManager())),
                    packageName, info.loadIcon(getPackageManager())));
        }
        choices.sort(Comparator.comparing(choice -> choice.label, String.CASE_INSENSITIVE_ORDER));
        return choices;
    }

    private void refreshEmptyState() {
        boolean empty = adapter.getCount() == 0;
        findViewById(R.id.pickerEmptyState).setVisibility(empty ? View.VISIBLE : View.GONE);
        findViewById(R.id.appsList).setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override public boolean onSupportNavigateUp() {
        finish();
        return true;
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

    private final class AppAdapter extends BaseAdapter {
        private final List<AppChoice> all;
        private final List<AppChoice> shown;
        AppAdapter(List<AppChoice> choices) {
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
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) row = getLayoutInflater().inflate(
                    R.layout.installed_app_picker_item, parent, false);
            AppChoice choice = getItem(position);
            ((TextView) row.findViewById(R.id.pickerAppName)).setText(choice.label);
            ((TextView) row.findViewById(R.id.pickerPackageName)).setText(choice.packageName);
            ((ImageView) row.findViewById(R.id.pickerAppIcon)).setImageDrawable(choice.icon);
            return row;
        }
    }
}
