package com.example.modernhabitrewire;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
    private Button cancelButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int countdownSeconds = 0;
    private boolean isExhaustedMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decision_gate);

        appPreferencesManager = AppPreferencesManagerSingleton.getInstance(this);
        budgetEngine = new DopamineBudgetEngine(this);

        statsTextView = findViewById(R.id.awareness_mirror_stats);
        frictionTextView = findViewById(R.id.friction_status);
        proceedButton = findViewById(R.id.proceed_button);
        cancelButton = findViewById(R.id.cancel_button);

        updateAwarenessMirror();

        proceedButton.setOnClickListener(v -> {
            if (appPreferencesManager.getLaunchFrictionEnabled() || isExhaustedMode) {
                startFrictionDelay();
            } else {
                launchTargetApp();
            }
        });

        cancelButton.setOnClickListener(v -> {
            handler.removeCallbacksAndMessages(null);
            goHome();
        });
    }

    private void updateAwarenessMirror() {
        int sessions = appPreferencesManager.getDailySessionCount();
        double multiplier = budgetEngine.calculateCurrentMultiplier();
        long remainingUnits = budgetEngine.getRemainingBudget();

        String stats = getString(R.string.system_state_template,
                sessions, multiplier, remainingUnits);

        statsTextView.setText(stats);

        if (remainingUnits <= 0) {
            isExhaustedMode = true;
            proceedButton.setText(R.string.budget_exhausted_overdraw);
        }
    }

    private void startFrictionDelay() {
        proceedButton.setVisibility(View.GONE);
        cancelButton.setText(R.string.gate_action_go_back);
        
        // Use budget engine to calculate latency (higher multiplier in overdraw still applies)
        countdownSeconds = budgetEngine.calculateWaitSeconds();
        
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

    private void goHome() {
        AttentionFirewallService.notifyGateClosed();
        
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
        
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
