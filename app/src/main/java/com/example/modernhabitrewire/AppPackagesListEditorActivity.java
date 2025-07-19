package com.example.modernhabitrewire;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

public class AppPackagesListEditorActivity extends AppCompatActivity {

    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;
    private AppPackagesListRecyclerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_package_name_list_editor);


        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(this);

        EditText packageNameInput = findViewById(R.id.packageNameInput);
        Button addButton = findViewById(R.id.addButton);
        RecyclerView recyclerView = findViewById(R.id.appPackageRecyclerView);

        adapter = new AppPackagesListRecyclerAdapter(appPreferencesManagerSingleton.getForbiddenAppsPackages(), packageName -> {
            appPreferencesManagerSingleton.removeAppPackage(packageName);
            refreshList();
        });

        recyclerView.setAdapter(adapter);

        addButton.setOnClickListener(v -> {
            String newAppPackage = packageNameInput.getText().toString().trim();
            if (!newAppPackage.isEmpty()) {
                appPreferencesManagerSingleton.addForbiddenAppPackage(newAppPackage);
                packageNameInput.setText("");
                refreshList();
            }
        });
    }

    private void refreshList() {
        adapter.updateList(appPreferencesManagerSingleton.getForbiddenAppsPackages());
    }


}