package com.example.modernhabitrewire;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

public class UrlListEditorActivity extends AppCompatActivity {

    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;
    private UrlListRecyclerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_url_list_editor);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contentContainer), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(this);

        TextInputEditText urlEditText = findViewById(R.id.urlEditText);
        Button addButton = findViewById(R.id.addButton);
        RecyclerView recyclerView = findViewById(R.id.urlRecyclerView);

        adapter = new UrlListRecyclerAdapter(appPreferencesManagerSingleton.getForbiddenUrls(), url -> {
            appPreferencesManagerSingleton.removeUrl(url);
            refreshList();
        });

        recyclerView.setAdapter(adapter);

        addButton.setOnClickListener(v -> {
            String newUrl = urlEditText.getText() != null
                    ? urlEditText.getText().toString().trim() : "";
            if (!newUrl.isEmpty()) {
                appPreferencesManagerSingleton.addForbiddenUrl(newUrl);
                urlEditText.setText("");
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
        adapter.updateList(appPreferencesManagerSingleton.getForbiddenUrls());
    }
}

        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(this);

        TextInputEditText urlEditText = findViewById(R.id.urlEditText);
        Button addButton = findViewById(R.id.addButton);
        RecyclerView recyclerView = findViewById(R.id.urlRecyclerView);

        adapter = new UrlListRecyclerAdapter(appPreferencesManagerSingleton.getForbiddenUrls(), url -> {
            appPreferencesManagerSingleton.removeUrl(url);
            refreshList();
        });

        recyclerView.setAdapter(adapter);

        addButton.setOnClickListener(v -> {
            String newUrl = urlEditText.getText() != null
                    ? urlEditText.getText().toString().trim() : "";
            if (!newUrl.isEmpty()) {
                appPreferencesManagerSingleton.addForbiddenUrl(newUrl);
                urlEditText.setText("");
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
        adapter.updateList(appPreferencesManagerSingleton.getForbiddenUrls());
    }
}