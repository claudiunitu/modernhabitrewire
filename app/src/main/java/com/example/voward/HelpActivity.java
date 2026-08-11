package com.example.voward;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import com.google.android.material.appbar.MaterialToolbar;

public class HelpActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_help_modern);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        bindToggle(R.id.helpQuickStartButton, R.id.helpQuickStartContent);
        bindToggle(R.id.helpPauseButton, R.id.helpPauseContent);
        bindToggle(R.id.helpBudgetButton, R.id.helpBudgetContent);
        bindToggle(R.id.helpRulesButton, R.id.helpRulesContent);
        bindToggle(R.id.helpPrivacyButton, R.id.helpPrivacyContent);
        bindToggle(R.id.helpRecoveryButton, R.id.helpRecoveryContent);
    }

    private void bindToggle(int buttonId, int contentId) {
        findViewById(buttonId).setOnClickListener(v -> {
            android.view.View content = findViewById(contentId);
            content.setVisibility(content.getVisibility() == android.view.View.VISIBLE
                    ? android.view.View.GONE : android.view.View.VISIBLE);
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
