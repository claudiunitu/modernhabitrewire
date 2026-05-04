package com.example.modernhabitrewire;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;

public class DecisionGateActivity extends AppCompatActivity {

    public static final String EXTRA_EXHAUSTED = "exhausted_mode";

    private AppPreferencesManagerSingleton appPreferencesManager;
    private DopamineBudgetEngine budgetEngine;
    private TextView statsTextView;
    private TextView frictionTextView;
    private Button proceedButton;
    private Button cancelButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int countdownSeconds = 0;
    private boolean isExhaustedMode = false; // kept for layout compatibility; gate is never reached when budget <= 0

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_decision_gate);

        // Apply system bar insets, adding them on top of the XML spacing_lg base padding.
        int base = getResources().getDimensionPixelSize(R.dimen.spacing_lg);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom);
            return insets;
        });

        appPreferencesManager = AppPreferencesManagerSingleton.getInstance(this);
        budgetEngine = new DopamineBudgetEngine(this);

        statsTextView = findViewById(R.id.awareness_mirror_stats);
        frictionTextView = findViewById(R.id.friction_status);
        proceedButton = findViewById(R.id.proceed_button);
        cancelButton = findViewById(R.id.cancel_button);

        updateAwarenessMirror();

        boolean exhaustedMode = getIntent().getBooleanExtra(EXTRA_EXHAUSTED, false);
        if (exhaustedMode) {
            proceedButton.setEnabled(false);
            proceedButton.setAlpha(0.35f);
            frictionTextView.setVisibility(View.VISIBLE);
            frictionTextView.setText(R.string.budget_depleted_gate_message);
        }

        proceedButton.setOnClickListener(v -> {
            if (appPreferencesManager.getLaunchFrictionEnabled()) {
                startFrictionDelay();
            } else {
                launchTargetApp();
            }
        });

        cancelButton.setOnClickListener(v -> {
            handler.removeCallbacksAndMessages(null);
            goHome();
        });

        // Route hardware back through goHome() so the gate is always properly
        // closed — ensuring notifyGateClosed() is called and the user lands on
        // the home screen rather than bouncing back to the forbidden app.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handler.removeCallbacksAndMessages(null);
                goHome();
            }
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
        String targetPackage = appPreferencesManager.getLastInterceptedApp();
        if (targetPackage != null && !targetPackage.isEmpty()) {
            String interceptedUrl = appPreferencesManager.getLastInterceptedUrl();
            if (interceptedUrl != null && !interceptedUrl.isEmpty()) {
                // MEDIUM-04: URL-based interception — the browser is already open with the
                // page loaded. Only set the approval flag; relaunching would open a new blank
                // tab and trigger the gate again when the user navigates back.
                appPreferencesManager.setTempAllowAppLaunch(true);
                AttentionFirewallService.notifyTempAllowGranted();
            } else {
                // App-based interception: explicitly launch the target app.
                Intent intent = getPackageManager().getLaunchIntentForPackage(targetPackage);
                if (intent != null) {
                    // Set the flag only after confirming the intent is valid, so it can never
                    // be stuck true when the target app is unavailable (e.g. uninstalled).
                    appPreferencesManager.setTempAllowAppLaunch(true);
                    AttentionFirewallService.notifyTempAllowGranted();
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                }
            }
        }
        finish();
    }

    private void goHome() {
        AttentionFirewallService.notifyGateClosed();
        AttentionFirewallService.notifyGateCancelled();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
