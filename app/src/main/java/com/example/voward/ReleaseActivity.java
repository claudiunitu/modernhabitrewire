package com.example.voward;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rung 2 of the escape ladder: release protection when the main screen will not open.
 *
 * <p>Reached with {@code adb shell am start -n com.example.voward/.ReleaseActivity}. It shares
 * no layout, theme, navigation or view model with the rest of the app, and builds its own
 * views in code, so a resource or startup fault in the main UI cannot take it down with it.</p>
 *
 * <p>It does not skip the recovery key, the cooldown, or the confirmation window. Being a
 * separate screen is about the app being broken, not about the policy being optional — a
 * back door here would make the cooldown decorative for anyone holding a USB cable.</p>
 */
public class ReleaseActivity extends Activity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DeactivationPolicyEngine engine = new DeactivationPolicyEngine();

    private EditText keyInput;
    private Button releaseButton;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.release_title);
        setContentView(buildLayout());
        refreshStatus();
    }

    private View buildLayout() {
        int pad = dp(24);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView explanation = new TextView(this);
        explanation.setText(R.string.release_explanation);
        column.addView(explanation);

        status = new TextView(this);
        status.setPadding(0, dp(16), 0, dp(16));
        column.addView(status);

        keyInput = new EditText(this);
        keyInput.setHint(R.string.enter_recovery_key);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setSingleLine(true);
        column.addView(keyInput);

        releaseButton = new Button(this);
        releaseButton.setText(R.string.deactivate_now);
        releaseButton.setOnClickListener(view -> submit());
        column.addView(releaseButton);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void submit() {
        String candidate = keyInput.getText() == null ? "" : keyInput.getText().toString();
        if (candidate.isEmpty()) {
            status.setText(R.string.cannot_be_empty);
            return;
        }
        releaseButton.setEnabled(false);
        status.setText(R.string.release_checking);
        // PBKDF2 is deliberately slow; running it inline would freeze the only screen the
        // user has left.
        executor.execute(() -> {
            boolean valid = AppPreferencesManagerSingleton.getInstance(this)
                    .verifyDeactivationKey(candidate);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                keyInput.setText("");
                releaseButton.setEnabled(true);
                if (valid) advance(); else status.setText(R.string.incorrect_key);
            });
        });
    }

    /** The same request, cooldown and window policy the main screen applies. */
    private void advance() {
        AppPreferencesManagerSingleton preferences =
                AppPreferencesManagerSingleton.getInstance(this);
        int cooldownMinutes = preferences.getDeactivationCooldownMinutes();
        if (cooldownMinutes <= 0) {
            release();
            return;
        }

        DeactivationPolicyEngine.Evaluation evaluation = evaluate(preferences);
        if (evaluation.state == DeactivationPolicyEngine.State.EXPIRED
                || evaluation.state == DeactivationPolicyEngine.State.INVALIDATED) {
            // Retire the dead request and stop. A missed window costs a fresh cooldown here
            // exactly as it does on the main screen.
            preferences.finishPendingDeactivation(evaluation.state);
            status.setText(R.string.deactivation_window_closed);
            return;
        }
        if (evaluation.state == DeactivationPolicyEngine.State.WINDOW_OPEN) {
            release();
            return;
        }
        if (evaluation.state == DeactivationPolicyEngine.State.COOLDOWN_PENDING) {
            status.setText(R.string.release_cooldown_pending);
            return;
        }
        DeactivationPolicyEngine.Request request = engine.createRequest(
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount(),
                cooldownMinutes * 60L * 1000L,
                preferences.getDeactivationWindowHours() * 60L * 60L * 1000L);
        if (request == null) {
            status.setText(R.string.deactivation_window_closed);
            return;
        }
        preferences.savePendingDeactivation(request);
        status.setText(R.string.deactivation_request_created);
    }

    private void release() {
        AppPreferencesManagerSingleton preferences =
                AppPreferencesManagerSingleton.getInstance(this);
        preferences.clearPendingDeactivation();
        preferences.setIsBlockerActive(false);
        status.setText(R.string.release_done);
        releaseButton.setEnabled(false);
        keyInput.setEnabled(false);
        executor.execute(() -> EnforcementCoordinator.releaseNow(this));
    }

    private void refreshStatus() {
        AppPreferencesManagerSingleton preferences =
                AppPreferencesManagerSingleton.getInstance(this);
        if (!preferences.getIsBlockerActive()) {
            status.setText(R.string.release_not_active);
            return;
        }
        if (preferences.getDeactivationCooldownMinutes() <= 0) {
            status.setText(R.string.release_active);
            return;
        }
        DeactivationPolicyEngine.Evaluation evaluation = evaluate(preferences);
        status.setText(evaluation.state == DeactivationPolicyEngine.State.WINDOW_OPEN
                ? R.string.release_window_open
                : evaluation.state == DeactivationPolicyEngine.State.COOLDOWN_PENDING
                        ? R.string.release_cooldown_pending
                        : R.string.release_active);
    }

    private DeactivationPolicyEngine.Evaluation evaluate(
            AppPreferencesManagerSingleton preferences) {
        return engine.evaluateRequest(preferences.getPendingDeactivation(),
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount());
    }

    private int bootCount() {
        try {
            return Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | RuntimeException unavailable) {
            return -1;
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
