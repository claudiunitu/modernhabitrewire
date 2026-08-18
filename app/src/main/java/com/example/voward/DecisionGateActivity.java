package com.example.voward;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.net.Uri;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.button.MaterialButtonToggleGroup;

public class DecisionGateActivity extends AppCompatActivity {

    public static final String EXTRA_STRICT_BLOCK = "strict_block";

    private static final String STATE_STAGE = "gate_stage";
    private static final String STATE_DEADLINE = "gate_deadline";
    private static final String STATE_OUTCOME = "gate_outcome";
    private static final String STATE_QUOTED_SECONDS = "gate_quoted_seconds";
    private static final String STATE_STRICT_BLOCK = "gate_strict_block";
    private static final String STATE_COUNTDOWN_TOTAL = "gate_countdown_total";
    private static final int STAGE_PLANNING = 0;
    private static final int STAGE_COUNTDOWN = 1;
    private static final int STAGE_READY = 2;

    private AppPreferencesManagerSingleton appPreferencesManager;
    private AttentionBudgetEngine budgetEngine;
    private TextView statsTextView;
    private TextView frictionTextView;
    private Button proceedButton;
    private Button cancelButton;
    private EditText purposeInput;
    private EditText plannedMinutesInput;
    private TextView sessionTerms;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int stage = STAGE_PLANNING;
    private long countdownDeadlineElapsed = 0;
    private long quotedSessionSeconds = 0;
    private int countdownTotalSeconds = 0;
    private boolean outcomeRecorded = false;
    private boolean strictBlocked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_decision_gate_modern);

        // Keep all controls inside the edge-to-edge safe area.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        appPreferencesManager = AppPreferencesManagerSingleton.getInstance(this);
        budgetEngine = new AttentionBudgetEngine(this);
        strictBlocked = savedInstanceState == null
                ? getIntent().getBooleanExtra(EXTRA_STRICT_BLOCK, false)
                : savedInstanceState.getBoolean(STATE_STRICT_BLOCK, false);
        if (!strictBlocked && savedInstanceState == null) {
            appPreferencesManager.incrementFrictionShown();
        }

        statsTextView = findViewById(R.id.awareness_mirror_stats);
        frictionTextView = findViewById(R.id.friction_status);
        proceedButton = findViewById(R.id.proceed_button);
        cancelButton = findViewById(R.id.cancel_button);
        purposeInput = findViewById(R.id.purpose_input);
        plannedMinutesInput = findViewById(R.id.planned_minutes_input);
        sessionTerms = findViewById(R.id.session_terms);
        ((TextView) findViewById(R.id.gateTarget)).setText(getTargetDescription());
        String functionalGoal = appPreferencesManager.getFunctionalGoal();
        if (!functionalGoal.isEmpty()) {
            ((TextView) findViewById(R.id.replacement_prompt)).setText(getString(
                    R.string.gate_replacement_with_goal, functionalGoal));
        }
        ((TextView) findViewById(R.id.replacementWalk)).setText(appPreferencesManager.getReplacementWalk());
        ((TextView) findViewById(R.id.replacementWater)).setText(appPreferencesManager.getReplacementWater());
        ((TextView) findViewById(R.id.replacementTask)).setText(appPreferencesManager.getReplacementTask());

        if (savedInstanceState == null) {
            plannedMinutesInput.setText(String.valueOf(
                    appPreferencesManager.getDefaultSessionSeconds() / 60));
        } else {
            stage = savedInstanceState.getInt(STATE_STAGE, STAGE_PLANNING);
            countdownDeadlineElapsed = savedInstanceState.getLong(STATE_DEADLINE, 0);
            outcomeRecorded = savedInstanceState.getBoolean(STATE_OUTCOME, false);
            quotedSessionSeconds = savedInstanceState.getLong(STATE_QUOTED_SECONDS, 0);
            countdownTotalSeconds = savedInstanceState.getInt(STATE_COUNTDOWN_TOTAL, 0);
        }

        MaterialButtonToggleGroup durations = findViewById(R.id.durationChips);
        TextInputLayout customDuration = findViewById(R.id.planned_minutes_input_layout);
        findViewById(R.id.duration5).setOnClickListener(v -> {
            plannedMinutesInput.setText(R.string.duration_value_5);
            customDuration.setVisibility(View.GONE);
        });
        findViewById(R.id.duration10).setOnClickListener(v -> {
            plannedMinutesInput.setText(R.string.duration_value_10);
            customDuration.setVisibility(View.GONE);
        });
        findViewById(R.id.duration15).setOnClickListener(v -> {
            plannedMinutesInput.setText(R.string.duration_value_15);
            customDuration.setVisibility(View.GONE);
        });
        findViewById(R.id.durationCustom).setOnClickListener(v -> {
            customDuration.setVisibility(View.VISIBLE);
            plannedMinutesInput.requestFocus();
            plannedMinutesInput.setSelection(plannedMinutesInput.length());
        });
        int initialMinutes = appPreferencesManager.getDefaultSessionSeconds() / 60;
        if (initialMinutes == 5) durations.check(R.id.duration5);
        else if (initialMinutes == 10) durations.check(R.id.duration10);
        else if (initialMinutes == 15) durations.check(R.id.duration15);
        else {
            durations.clearChecked();
            customDuration.setVisibility(View.VISIBLE);
        }

        updateAwarenessMirror();

        proceedButton.setOnClickListener(v -> onProceed());

        cancelButton.setOnClickListener(v -> {
            handler.removeCallbacksAndMessages(null);
            recordAborted();
            goHome();
        });
        findViewById(R.id.replacementWalk).setOnClickListener(v -> chooseAlternative(0));
        findViewById(R.id.replacementWater).setOnClickListener(v -> chooseAlternative(1));
        findViewById(R.id.replacementTask).setOnClickListener(v -> chooseAlternative(2));

        // Route hardware back through goHome() so the gate is always properly
        // closed — ensuring notifyGateClosed() is called and the user lands on
        // the home screen rather than bouncing back to the restricted app.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handler.removeCallbacksAndMessages(null);
                recordAborted();
                goHome();
            }
        });

        renderStage();
    }

    private void updateAwarenessMirror() {
        if (strictBlocked) {
            statsTextView.setText(R.string.strict_gate_summary);
            return;
        }
        long remainingSeconds = budgetEngine.getRemainingBudget();
        int waitSeconds = budgetEngine.calculateWaitSeconds();
        String waitDescription = getResources().getQuantityString(
                R.plurals.quoted_reentry_pause, waitSeconds, waitSeconds);

        String stats = getString(R.string.gate_summary_friendly,
                formatMinutesSeconds(remainingSeconds), waitDescription);

        statsTextView.setText(stats);

    }

    private void onProceed() {
        if (strictBlocked) return;
        if (stage == STAGE_READY) {
            launchTargetApp();
            return;
        }
        if (stage != STAGE_PLANNING || !validatePlan()) return;

        int minutes = Integer.parseInt(plannedMinutesInput.getText().toString());
        appPreferencesManager.setPendingSessionSeconds(minutes * 60);
        quotedSessionSeconds = budgetEngine.quoteSessionSeconds(minutes * 60);
        if (quotedSessionSeconds <= 0) {
            Toast.makeText(this, R.string.no_attention_budget_remaining, Toast.LENGTH_LONG).show();
            return;
        }
        appPreferencesManager.setPendingQuotedSessionSeconds(quotedSessionSeconds);

        int delay = appPreferencesManager.getLaunchFrictionEnabled()
                ? budgetEngine.calculateWaitSeconds() : 0;
        countdownTotalSeconds = delay;
        countdownDeadlineElapsed = SystemClock.elapsedRealtime() + delay * 1000L;
        stage = delay > 0 ? STAGE_COUNTDOWN : STAGE_READY;
        renderStage();
    }

    private boolean validatePlan() {
        TextInputLayout purposeLayout = findViewById(R.id.purpose_input_layout);
        TextInputLayout minutesLayout = findViewById(R.id.planned_minutes_input_layout);
        String purpose = purposeInput.getText() == null ? "" : purposeInput.getText().toString().trim();
        purposeLayout.setError(purpose.isEmpty() ? getString(R.string.gate_purpose_required) : null);
        int minutes = -1;
        try { minutes = Integer.parseInt(plannedMinutesInput.getText().toString()); }
        catch (Exception ignored) { }
        boolean validMinutes = minutes >= 1 && minutes <= 60;
        minutesLayout.setError(validMinutes ? null : getString(R.string.gate_minutes_error));
        return !purpose.isEmpty() && validMinutes;
    }

    private void renderStage() {
        TransitionManager.beginDelayedTransition(
                (ViewGroup) findViewById(R.id.rootLayout),
                new AutoTransition().setDuration(180));
        if (strictBlocked) {
            renderStrictBlock();
            return;
        }
        boolean planning = stage == STAGE_PLANNING;
        purposeInput.setEnabled(planning);
        plannedMinutesInput.setEnabled(planning);
        findViewById(R.id.planningGroup).setVisibility(planning ? View.VISIBLE : View.GONE);
        findViewById(R.id.gatePlanSummary).setVisibility(planning ? View.GONE : View.VISIBLE);
        cancelButton.setText(R.string.gate_not_now);
        if (planning) {
            sessionTerms.setText(R.string.gate_terms_before_plan);
        } else {
            long left = Math.max(0, budgetEngine.getRemainingBudget() - quotedSessionSeconds);
            sessionTerms.setText(getString(R.string.gate_terms_friendly,
                    formatMinutesSeconds(quotedSessionSeconds), formatMinutesSeconds(left)));
            String purpose = purposeInput.getText() == null ? "" : purposeInput.getText().toString().trim();
            ((TextView) findViewById(R.id.gatePlanSummary)).setText(getString(
                    R.string.gate_plan_summary, purpose, formatMinutesSeconds(quotedSessionSeconds)));
        }
        if (stage == STAGE_COUNTDOWN) {
            ((TextView) findViewById(R.id.gateStageLabel)).setText(R.string.gate_stage_pause);
            ((TextView) findViewById(R.id.gateTitle)).setText(R.string.gate_pause_title);
            proceedButton.setVisibility(View.GONE);
            findViewById(R.id.pauseVisual).setVisibility(View.VISIBLE);
            runCountdown();
        } else if (stage == STAGE_READY) {
            ((TextView) findViewById(R.id.gateStageLabel)).setText(R.string.gate_stage_confirm);
            ((TextView) findViewById(R.id.gateTitle)).setText(R.string.gate_confirm_title);
            findViewById(R.id.pauseVisual).setVisibility(View.VISIBLE);
            ((AllowanceRingView) findViewById(R.id.pauseRing)).setFraction(1f);
            frictionTextView.setText(R.string.gate_pause_complete);
            proceedButton.setVisibility(View.VISIBLE);
            proceedButton.setText(R.string.gate_open_intentionally);
            if (!outcomeRecorded) {
                appPreferencesManager.incrementFrictionEndured();
                outcomeRecorded = true;
            }
        } else {
            ((TextView) findViewById(R.id.gateStageLabel)).setText(R.string.gate_stage_plan);
            ((TextView) findViewById(R.id.gateTitle)).setText(R.string.gate_plan_title);
            findViewById(R.id.pauseVisual).setVisibility(View.GONE);
            proceedButton.setVisibility(View.VISIBLE);
            proceedButton.setText(R.string.gate_start_pause);
        }
    }

    private void renderStrictBlock() {
        handler.removeCallbacksAndMessages(null);
        ((TextView) findViewById(R.id.gateStageLabel)).setText(R.string.strict_gate_stage);
        ((TextView) findViewById(R.id.gateTitle)).setText(R.string.strict_gate_title);
        statsTextView.setText(R.string.strict_gate_summary);
        statsTextView.setTextColor(ContextCompat.getColor(this, R.color.status_warning));
        com.google.android.material.card.MaterialCardView statsCard = findViewById(R.id.statsCard);
        statsCard.setCardBackgroundColor(
                ContextCompat.getColor(this, R.color.status_warning_container));
        findViewById(R.id.planningGroup).setVisibility(View.GONE);
        findViewById(R.id.gatePlanSummary).setVisibility(View.GONE);
        TextView replacement = findViewById(R.id.replacement_prompt);
        replacement.setText(R.string.strict_gate_explanation);
        replacement.setTextColor(ContextCompat.getColor(this, R.color.status_warning));
        sessionTerms.setVisibility(View.GONE);
        findViewById(R.id.pauseVisual).setVisibility(View.GONE);
        findViewById(R.id.replacementActions).setVisibility(View.GONE);
        proceedButton.setVisibility(View.GONE);
        cancelButton.setText(R.string.strict_gate_home);
    }

    private String getTargetDescription() {
        String url = appPreferencesManager.getLastInterceptedUrl();
        if (url != null && !url.isEmpty()) {
            String host = Uri.parse(url).getHost();
            return getString(R.string.gate_target_site,
                    host == null || host.isEmpty() ? url : host);
        }
        String packageName = appPreferencesManager.getLastInterceptedApp();
        if (packageName != null && !packageName.isEmpty()) {
            try {
                CharSequence label = getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(packageName, 0));
                return getString(R.string.gate_target_app, label);
            } catch (Exception ignored) {
                return getString(R.string.gate_target_app, packageName);
            }
        }
        return getString(R.string.gate_target_unknown);
    }

    private void runCountdown() {
        if (stage != STAGE_COUNTDOWN) return;
        long now = SystemClock.elapsedRealtime();
        long remainingMs = countdownDeadlineElapsed - now;
        int seconds = DecisionGatePolicy.remainingSeconds(countdownDeadlineElapsed, now);
        if (seconds <= 0) {
            stage = STAGE_READY;
            renderStage();
            return;
        }
        frictionTextView.setText(getResources().getQuantityString(
                R.plurals.interaction_latency_template, seconds, seconds));
        float completed = countdownTotalSeconds <= 0 ? 1f
                : 1f - (seconds / (float) countdownTotalSeconds);
        ((AllowanceRingView) findViewById(R.id.pauseRing)).setFraction(completed);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::runCountdown, Math.min(1000, remainingMs));
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

    private void chooseAlternative(int index) {
        handler.removeCallbacksAndMessages(null);
        appPreferencesManager.incrementAlternativeChoice(index);
        recordAborted();
        goHome();
    }

    private void recordAborted() {
        if (strictBlocked) return;
        if (outcomeRecorded) return;
        appPreferencesManager.incrementFrictionAborted();
        outcomeRecorded = true;
    }

    private static String formatMinutesSeconds(long seconds) {
        boolean negative = seconds < 0;
        long safe = seconds == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(seconds);
        String value = String.format(java.util.Locale.getDefault(), "%d:%02d", safe / 60, safe % 60);
        return negative ? "-" + value : value;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_STAGE, stage);
        outState.putLong(STATE_DEADLINE, countdownDeadlineElapsed);
        outState.putBoolean(STATE_OUTCOME, outcomeRecorded);
        outState.putLong(STATE_QUOTED_SECONDS, quotedSessionSeconds);
        outState.putBoolean(STATE_STRICT_BLOCK, strictBlocked);
        outState.putInt(STATE_COUNTDOWN_TOTAL, countdownTotalSeconds);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_STRICT_BLOCK, false)) {
            strictBlocked = true;
            updateAwarenessMirror();
            renderStage();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
