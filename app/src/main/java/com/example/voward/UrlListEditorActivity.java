package com.example.voward;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class UrlListEditorActivity extends AppCompatActivity {

    private AppPreferencesManagerSingleton appPreferencesManagerSingleton;
    private UrlListRecyclerAdapter adapter;
    private MaterialCheckBox newStrictRuleCheckbox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_url_rules_modern);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        appPreferencesManagerSingleton = AppPreferencesManagerSingleton.getInstance(this);

        TextInputEditText urlEditText = findViewById(R.id.urlEditText);
        TextInputLayout urlInputLayout = findViewById(R.id.packageNameInput);
        Button addButton = findViewById(R.id.addButton);
        newStrictRuleCheckbox = findViewById(R.id.newStrictRuleCheckbox);
        RecyclerView recyclerView = findViewById(R.id.urlRecyclerView);

        adapter = new UrlListRecyclerAdapter(appPreferencesManagerSingleton.getRestrictedUrls(), url -> {
            appPreferencesManagerSingleton.removeUrl(url);
            refreshList();
        }, (url, strict) ->
                appPreferencesManagerSingleton.setRestrictedUrlStrict(url, strict));

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
            String newUrl = urlEditText.getText() != null
                    ? urlEditText.getText().toString().trim() : "";
            if (!newUrl.isEmpty()) {
                if (UrlPatternMatcher.isValidPattern(newUrl)) {
                    urlInputLayout.setError(null);
                    appPreferencesManagerSingleton.addRestrictedUrl(
                            newUrl, newStrictRuleCheckbox.isChecked());
                    newStrictRuleCheckbox.setChecked(false);
                    urlEditText.setText("");
                    refreshList();
                } else {
                    urlInputLayout.setError(getString(R.string.invalid_url_rule));
                }
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void refreshList() {
        adapter.updateList(appPreferencesManagerSingleton.getRestrictedUrls());
        findViewById(R.id.emptyState).setVisibility(
                appPreferencesManagerSingleton.getRestrictedUrls().isEmpty()
                        ? View.VISIBLE : View.GONE);
    }
}
