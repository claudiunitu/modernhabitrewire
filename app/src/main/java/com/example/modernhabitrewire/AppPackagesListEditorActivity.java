package com.example.modernhabitrewire;

import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

public class AppPackagesListEditorActivity extends AppCompatActivity {

    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;
    private AppPackagesListRecyclerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_package_name_list_editor);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(this);

        TextInputEditText packageNameEditText = findViewById(R.id.packageNameEditText);
        Button addButton = findViewById(R.id.addButton);
        RecyclerView recyclerView = findViewById(R.id.appPackageRecyclerView);

        adapter = new AppPackagesListRecyclerAdapter(appPreferencesManagerSingleton.getExtractiveAppsPackages(), packageName -> {
            appPreferencesManagerSingleton.removeExtractiveAppPackage(packageName);
            refreshList();
        });

        recyclerView.setAdapter(adapter);

        addButton.setOnClickListener(v -> {
            String newAppPackage = packageNameEditText.getText() != null
                    ? packageNameEditText.getText().toString().trim() : "";
            if (!newAppPackage.isEmpty()) {
                appPreferencesManagerSingleton.addExtractiveAppPackage(newAppPackage);
                packageNameEditText.setText("");
                refreshList();
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void refreshList() {
        adapter.updateList(appPreferencesManagerSingleton.getExtractiveAppsPackages());
    }
}
