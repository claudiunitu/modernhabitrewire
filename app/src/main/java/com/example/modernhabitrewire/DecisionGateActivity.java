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
        long remainingUnits = budgetEngine.getRemainingBudget();

        String stats = getString(R.string.system_state_template,
                sessions, multiplier, remainingUnits);

        statsTextView.setText(stats);

        // Enforce potential limits: disable proceed if no credits left
        if (remainingUnits <= 0) {
            proceedButton.setEnabled(false);
            proceedButton.setText(R.string.potential_depleted);
        }
    }

    private void startFrictionDelay() {
        proceedButton.setEnabled(false);
        int baseWait = appPreferencesManager.getBaseWaitTimeSeconds();
        double multiplier = budgetEngine.calculateCurrentMultiplier();
        countdownSeconds = (int) (baseWait * multiplier);
        frictionTextView.setVisibility(View.VISIBLE);
        runCountdown();
    }

    private void runCountdown() {
        if (countdownSeconds > 0) {
            frictionTextView.setText(getString(R.string.interaction_latency_template, countdownSeconds));
            countdownSeconds--;
            handler.postDelayed(this::runCountdown, 1000);
        } else {
            launchTargetApp();
        }
    }

    private void launchTargetApp() {
        appPreferencesManager.setTempAllowAppLaunch(true);
        
        // Explicitly re-launch the target package (Browser or App) to force task switching
        String targetPackage = appPreferencesManager.getLastInterceptedApp();
        if (targetPackage != null && !targetPackage.isEmpty()) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(targetPackage);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        }
        
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
