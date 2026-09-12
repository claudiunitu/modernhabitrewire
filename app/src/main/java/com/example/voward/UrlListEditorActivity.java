package com.example.voward;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
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
                appPreferencesManagerSingleton.setRestrictedUrlStrict(url, strict),
                this::requestSession);

        recyclerView.setAdapter(adapter);
        boolean locked = appPreferencesManagerSingleton.getIsBlockerActive();
        TextView lockedBanner = findViewById(R.id.lockedBanner);
        lockedBanner.setVisibility(locked ? View.VISIBLE : View.GONE);
        // Tapping a row is the only route to a session for a website, so it has to be said.
        if (locked && BrowserPolicy.sessionBrowser(this,
                new PolicyReconciler(this).mirroredState().managedBrowsers) != null) {
            lockedBanner.setText(getString(R.string.rules_locked_read_only)
                    + " " + getString(R.string.website_session_hint));
        }
        findViewById(R.id.editorComposer).setVisibility(View.VISIBLE);
        RuleComposer.bind(this, appPreferencesManagerSingleton.getRestrictedUrls().isEmpty());
        refreshList();

        addButton.setOnClickListener(v -> {
            String newUrl = urlEditText.getText() != null
                    ? urlEditText.getText().toString().trim() : "";
            if (!newUrl.isEmpty()) {
                if (isRetiredKeywordRule(newUrl)) {
                    urlInputLayout.setError(getString(R.string.keyword_rule_rejected));
                } else if (UrlPatternMatcher.isValidPattern(newUrl)) {
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

    /**
     * {@code keyword:} rules match page text and {@code URLBlocklist} matches addresses, so the
     * browser cannot enforce one. Rules written before that move are still shown, and named by
     * the retired-rules banner; writing a new one is refused here rather than accepted and then
     * disowned. The matcher still understands the shape, because the old rules have to be
     * readable.
     */
    private static boolean isRetiredKeywordRule(String rule) {
        return rule.toLowerCase(java.util.Locale.ROOT).startsWith("keyword:");
    }

    /**
     * The way back to the gate for a website rule.
     *
     * <p>Once the browser is enforcing the rule itself it shows its own block page, and that
     * page cannot link anywhere. Without this there would be no way to ask for a session for a
     * website at all — the rule would quietly become a hard block.</p>
     */
    private void requestSession(String rule) {
        if (!appPreferencesManagerSingleton.getIsBlockerActive()) return;
        if (appPreferencesManagerSingleton.isStrictRestrictedUrlPattern(rule)) {
            Toast.makeText(this, R.string.strict_rule_no_session, Toast.LENGTH_SHORT).show();
            return;
        }
        String browser = BrowserPolicy.sessionBrowser(this,
                new PolicyReconciler(this).mirroredState().managedBrowsers);
        if (browser == null) {
            // Nothing is enforcing this in a browser, so the gate still appears at the moment
            // the page is opened. Offering a session here would be a second, pointless door.
            Toast.makeText(this, R.string.website_session_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        appPreferencesManagerSingleton.setLastInterceptedApp(browser);
        appPreferencesManagerSingleton.setLastInterceptedUrl(rule);
        appPreferencesManagerSingleton.setLastInterceptionKind("URL");
        startActivity(new Intent(this, DecisionGateActivity.class));
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
