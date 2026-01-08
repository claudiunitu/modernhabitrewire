package com.example.modernhabitrewire;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class DecisionGateActivity extends AppCompatActivity {

    private AppPreferencesManagerSingleton appPreferencesManager;
    private DopamineBudgetEngine budgetEngine;
    private TextView statsTextView;
    private TextView frictionTextView;
    private Button proceedButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int countdownSeconds = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decision_gate);

        appPreferencesManager = AppPreferencesManagerSingleton.getInstance(this);
        budgetEngine = new DopamineBudgetEngine(this);

        statsTextView = findViewById(R.id.awareness_mirror_stats);
        frictionTextView = findViewById(R.id.friction_status);
        proceedButton = findViewById(R.id.proceed_button);
        Button cancelButton = findViewById(R.id.cancel_button);

        updateAwarenessMirror();

        proceedButton.setOnClickListener(v -> {
            if (appPreferencesManager.getLaunchFrictionEnabled()) {
                startFrictionDelay();
            } else {
                launchTargetApp();
            }
        });

        cancelButton.setOnClickListener(v -> {
            handler.removeCallbacksAndMessages(null);
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
            finish();
        });
    }

    private void updateAwarenessMirror() {
        int sessions = appPreferencesManager.getDailySessionCount();
        double multiplier = budgetEngine.calculateCurrentMultiplier();
        long remainingMillis = budgetEngine.getRemainingBudget();

        long seconds = (remainingMillis / 1000) % 60;
        long minutes = (remainingMillis / (1000 * 60)) % 60;
        long hours = (remainingMillis / (1000 * 60 * 60)) % 24;

        String stats = String.format(Locale.getDefault(),
                "System State:\n---\nSessions today: %d\nCurrent cost: %.1fx\nRemaining budget: %02d:%02d:%02d",
                sessions, multiplier, hours, minutes, seconds);

        statsTextView.setText(stats);
    }

    private void startFrictionDelay() {
        proceedButton.setEnabled(false);
        
        // Physics: Base Wait Time (from settings) * Current Cost Multiplier
        int baseWait = appPreferencesManager.getBaseWaitTimeSeconds();
        double multiplier = budgetEngine.calculateCurrentMultiplier();
        
        countdownSeconds = (int) (baseWait * multiplier);
        
        frictionTextView.setVisibility(View.VISIBLE);
        runCountdown();
    }

    private void runCountdown() {
        if (countdownSeconds > 0) {
            frictionTextView.setText(String.format(Locale.getDefault(), "Interaction Latency: %ds remaining...", countdownSeconds));
            countdownSeconds--;
            handler.postDelayed(this::runCountdown, 1000);
        } else {
            launchTargetApp();
        }
    }

    private void launchTargetApp() {
        appPreferencesManager.setTempAllowAppLaunch(true);
        String packageName = appPreferencesManager.getLastInterceptedApp();
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent != null) {
            startActivity(launchIntent);
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
