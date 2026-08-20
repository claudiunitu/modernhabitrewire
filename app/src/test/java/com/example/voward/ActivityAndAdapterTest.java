package com.example.voward;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ActivityAndAdapterTest {
    private Application application;
    private AppPreferencesManagerSingleton preferences;

    @Before
    public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("global_preferences", Context.MODE_PRIVATE)
                .edit().clear().commit();
        application.getSharedPreferences("portable_preferences", Context.MODE_PRIVATE)
                .edit().clear().commit();
        resetSingleton();
        preferences = AppPreferencesManagerSingleton.getInstance(application);
        preferences.setRestrictedApps(List.of());
        preferences.setRestrictedUrls(List.of());
        preferences.setLastBudgetResetEpochDay(LocalDate.now().toEpochDay());
    }

    @Test
    public void helpSectionsToggleAndNavigationClosesActivity() {
        ActivityController<HelpActivity> controller =
                Robolectric.buildActivity(HelpActivity.class).setup();
        HelpActivity activity = controller.get();
        View content = activity.findViewById(R.id.helpQuickStartContent);
        int initial = content.getVisibility();
        activity.findViewById(R.id.helpQuickStartButton).performClick();
        assertEquals(initial == View.VISIBLE ? View.GONE : View.VISIBLE, content.getVisibility());

        View grayscaleContent = activity.findViewById(R.id.helpGrayscaleContent);
        assertEquals(View.GONE, grayscaleContent.getVisibility());
        activity.findViewById(R.id.helpGrayscaleButton).performClick();
        assertEquals(View.VISIBLE, grayscaleContent.getVisibility());

        assertTrue(activity.onSupportNavigateUp());
        assertTrue(activity.isFinishing());
        controller.destroy();
    }

    @Test
    public void urlEditorRejectsMalformedRuleAndAddsValidStrictRule() {
        ActivityController<UrlListEditorActivity> controller =
                Robolectric.buildActivity(UrlListEditorActivity.class).setup();
        UrlListEditorActivity activity = controller.get();
        EditText input = activity.findViewById(R.id.urlEditText);
        TextInputLayout layout = activity.findViewById(R.id.packageNameInput);

        input.setText("ambiguous");
        activity.findViewById(R.id.addButton).performClick();
        assertNotNull(layout.getError());
        assertTrue(preferences.getRestrictedUrls().isEmpty());

        input.setText("example.com/news");
        ((MaterialCheckBox) activity.findViewById(R.id.newStrictRuleCheckbox)).setChecked(true);
        activity.findViewById(R.id.addButton).performClick();
        assertEquals(List.of("example.com/news"), preferences.getRestrictedUrls());
        assertTrue(preferences.isStrictRestrictedUrlPattern("example.com/news"));
        assertEquals("", input.getText().toString());
        assertEquals(View.GONE, activity.findViewById(R.id.emptyState).getVisibility());
        controller.destroy();
    }

    @Test
    public void editorsAllowAdditiveChangesWhileProtectionIsActive() {
        preferences.setIsBlockerActive(true);
        ActivityController<UrlListEditorActivity> controller =
                Robolectric.buildActivity(UrlListEditorActivity.class).setup();
        UrlListEditorActivity activity = controller.get();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.lockedBanner).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.editorComposer).getVisibility());
        EditText input = activity.findViewById(R.id.urlEditText);
        input.setText("new.example");
        activity.findViewById(R.id.addButton).performClick();
        assertEquals(List.of("new.example"), preferences.getRestrictedUrls());
        controller.destroy();
    }

    @Test
    public void appEditorAllowsPackageThatIsNotInstalled() {
        ActivityController<AppPackagesListEditorActivity> controller =
                Robolectric.buildActivity(AppPackagesListEditorActivity.class).setup();
        AppPackagesListEditorActivity activity = controller.get();
        EditText input = activity.findViewById(R.id.packageNameEditText);

        input.setText("com.example.futureapp");
        activity.findViewById(R.id.addButton).performClick();

        assertEquals(List.of("com.example.futureapp"), preferences.getRestrictedAppPackages());
        assertEquals("", input.getText().toString());
        controller.destroy();
    }

    @Test
    public void activeProtectionAllowsRegularRuleToBecomeStrictButNotRelaxAgain() {
        preferences.setRestrictedUrls(List.of("example.com"));
        preferences.setIsBlockerActive(true);
        UrlListRecyclerAdapter adapter = new UrlListRecyclerAdapter(
                preferences.getRestrictedUrls(), preferences::removeUrl,
                preferences::setRestrictedUrlStrict);
        ActivityController<UrlListEditorActivity> controller =
                Robolectric.buildActivity(UrlListEditorActivity.class).setup();
        UrlViewHolder holder = adapter.onCreateViewHolder(new FrameLayout(controller.get()), 0);

        adapter.onBindViewHolder(holder, 0);
        assertTrue(holder.strictRuleCheckbox.isEnabled());
        holder.strictRuleCheckbox.setChecked(true);
        assertTrue(preferences.isStrictRestrictedUrlPattern("example.com"));
        adapter.onBindViewHolder(holder, 0);
        assertFalse(holder.strictRuleCheckbox.isEnabled());
        assertTrue(holder.strictRuleCheckbox.isChecked());
        controller.destroy();
    }

    @Test
    public void urlAdapterBindsTypesStrictnessAndCallbacks() {
        preferences.setRestrictedUrls(List.of("example.com", "example.com/news", "keyword:shorts"));
        preferences.setRestrictedUrlStrict("example.com/news", true);
        AtomicReference<String> deleted = new AtomicReference<>();
        AtomicReference<String> strictChanged = new AtomicReference<>();
        AtomicBoolean strictValue = new AtomicBoolean();
        UrlListRecyclerAdapter adapter = new UrlListRecyclerAdapter(
                preferences.getRestrictedUrls(), deleted::set,
                (url, strict) -> { strictChanged.set(url); strictValue.set(strict); });
        ActivityController<UrlListEditorActivity> controller =
                Robolectric.buildActivity(UrlListEditorActivity.class).setup();
        FrameLayout parent = new FrameLayout(controller.get());
        UrlViewHolder holder = adapter.onCreateViewHolder(parent, 0);

        adapter.onBindViewHolder(holder, 0);
        assertEquals("example.com", holder.textView.getText().toString());
        assertEquals(application.getString(R.string.url_rule_domain),
                holder.typeView.getText().toString());
        holder.deleteButton.performClick();
        assertEquals("example.com", deleted.get());
        holder.strictRuleCheckbox.setChecked(true);
        assertEquals("example.com", strictChanged.get());
        assertTrue(strictValue.get());

        adapter.onBindViewHolder(holder, 1);
        assertEquals(application.getString(R.string.url_rule_path), holder.typeView.getText());
        assertTrue(holder.strictRuleCheckbox.isChecked());
        adapter.onBindViewHolder(holder, 2);
        assertEquals(application.getString(R.string.url_rule_keyword), holder.typeView.getText());
        adapter.updateList(List.of("one.test"));
        assertEquals(1, adapter.getItemCount());
        controller.destroy();
    }

    @Test
    public void appAdapterFallsBackForUnknownPackageAndInvokesCallbacks() {
        preferences.setRestrictedApps(List.of("missing.package"));
        preferences.setRestrictedAppStrict("missing.package", true);
        AtomicReference<String> deleted = new AtomicReference<>();
        AtomicBoolean changed = new AtomicBoolean();
        AppPackagesListRecyclerAdapter adapter = new AppPackagesListRecyclerAdapter(
                preferences.getRestrictedAppPackages(), deleted::set,
                (name, strict) -> changed.set(!strict));
        ActivityController<AppPackagesListEditorActivity> controller =
                Robolectric.buildActivity(AppPackagesListEditorActivity.class).setup();
        AppPackagesViewHolder holder = adapter.onCreateViewHolder(
                new FrameLayout(controller.get()), 0);

        adapter.onBindViewHolder(holder, 0);
        assertEquals("missing.package", holder.textView.getText().toString());
        assertEquals("missing.package", holder.appNameView.getText().toString());
        assertTrue(holder.strictRuleCheckbox.isChecked());
        holder.deleteButton.performClick();
        assertEquals("missing.package", deleted.get());
        holder.strictRuleCheckbox.setChecked(false);
        assertTrue(changed.get());
        adapter.updateList(List.of("one", "two"));
        assertEquals(2, adapter.getItemCount());
        controller.destroy();
    }

    @Test
    public void decisionGateValidatesPlanThenQuotesReadySessionWithoutFriction() {
        preferences.setDailyAllowanceSeconds(600);
        preferences.setRemainingBudgetSeconds(600);
        preferences.setDefaultSessionSeconds(300);
        preferences.setLaunchFrictionEnabled(false);
        ActivityController<DecisionGateActivity> controller = Robolectric.buildActivity(
                DecisionGateActivity.class,
                new Intent(application, DecisionGateActivity.class)).setup();
        DecisionGateActivity activity = controller.get();

        activity.findViewById(R.id.proceed_button).performClick();
        assertNotNull(((TextInputLayout) activity.findViewById(
                R.id.purpose_input_layout)).getError());

        ((EditText) activity.findViewById(R.id.purpose_input)).setText("Reply to one message");
        ((EditText) activity.findViewById(R.id.planned_minutes_input)).setText("5");
        activity.findViewById(R.id.proceed_button).performClick();
        assertEquals(300, preferences.getPendingSessionSeconds());
        assertEquals(300, preferences.getPendingQuotedSessionSeconds());
        assertEquals(application.getString(R.string.gate_open_intentionally),
                ((TextView) activity.findViewById(R.id.proceed_button)).getText().toString());
        assertEquals(View.GONE, activity.findViewById(R.id.planningGroup).getVisibility());
        controller.destroy();
    }

    @Test
    public void strictDecisionGateOffersOnlyReturnHome() {
        Intent intent = new Intent(application, DecisionGateActivity.class)
                .putExtra(DecisionGateActivity.EXTRA_STRICT_BLOCK, true);
        ActivityController<DecisionGateActivity> controller =
                Robolectric.buildActivity(DecisionGateActivity.class, intent).setup();
        DecisionGateActivity activity = controller.get();
        assertEquals(View.GONE, activity.findViewById(R.id.planningGroup).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.proceed_button).getVisibility());
        assertEquals(application.getString(R.string.strict_gate_home),
                ((TextView) activity.findViewById(R.id.cancel_button)).getText().toString());
        controller.destroy();
    }

    @Test
    public void setupAndMainScreensLaunchWithPersistedConfiguration() {
        preferences.setSetupSeen(true);
        preferences.setDailyAllowanceSeconds(1_200);
        preferences.setDefaultSessionSeconds(600);
        preferences.setDeactivationCooldownMinutes(48 * 60);
        preferences.setDeactivationWindowHours(3);
        ActivityController<SetupActivity> setup =
                Robolectric.buildActivity(SetupActivity.class).setup();
        assertEquals("20", ((EditText) setup.get().findViewById(
                R.id.setupBudgetInput)).getText().toString());
        MaterialAutoCompleteTextView setupCooldown = setup.get().findViewById(
                R.id.setupDeactivationCooldownSpinner);
        MaterialAutoCompleteTextView setupWindow = setup.get().findViewById(
                R.id.setupDeactivationWindowSpinner);
        assertEquals(setupCooldown.getAdapter().getItem(5).toString(),
                setupCooldown.getText().toString());
        assertEquals(setupWindow.getAdapter().getItem(2).toString(),
                setupWindow.getText().toString());
        setupCooldown.getOnItemClickListener().onItemClick(null, null, 0, 0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, preferences.getDeactivationCooldownMinutes());
        assertFalse(setupWindow.isEnabled());
        setupCooldown.getOnItemClickListener().onItemClick(null, null, 1, 1);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, preferences.getDeactivationCooldownMinutes());
        assertTrue(setupWindow.isEnabled());
        setup.destroy();

        ActivityController<ModernMainActivity> main =
                Robolectric.buildActivity(ModernMainActivity.class).setup();
        assertEquals("20", ((EditText) main.get().findViewById(
                R.id.dailyBudgetInput)).getText().toString());
        ((EditText) main.get().findViewById(R.id.defaultSessionInput)).setText("15");
        assertEquals(900, preferences.getDefaultSessionSeconds());
        main.destroy();
    }

    @Test
    public void progressNavigationAndConfiguredGateAlternativesAreVisible() {
        preferences.setSetupSeen(true);
        preferences.setReplacementWalk("Step onto the balcony");
        preferences.setReplacementWater("Make tea");
        preferences.setReplacementTask("Write one sentence");

        ActivityController<ModernMainActivity> main =
                Robolectric.buildActivity(ModernMainActivity.class).setup();
        com.google.android.material.bottomnavigation.BottomNavigationView navigation =
                main.get().findViewById(R.id.bottomNavigation);
        navigation.setSelectedItemId(R.id.navigation_progress);
        assertEquals(View.VISIBLE, main.get().findViewById(R.id.progressScreen).getVisibility());
        assertEquals(View.GONE, main.get().findViewById(R.id.todayScreen).getVisibility());
        assertEquals(View.VISIBLE, main.get().findViewById(R.id.progressEmptyState).getVisibility());
        assertEquals(View.GONE, main.get().findViewById(R.id.progressDataCard).getVisibility());
        main.destroy();

        ActivityController<DecisionGateActivity> gate =
                Robolectric.buildActivity(DecisionGateActivity.class).setup();
        assertEquals("Step onto the balcony", ((TextView) gate.get().findViewById(
                R.id.replacementWalk)).getText().toString());
        assertEquals("Make tea", ((TextView) gate.get().findViewById(
                R.id.replacementWater)).getText().toString());
        assertEquals("Write one sentence", ((TextView) gate.get().findViewById(
                R.id.replacementTask)).getText().toString());
        assertEquals(View.GONE, gate.get().findViewById(
                R.id.planned_minutes_input_layout).getVisibility());
        gate.get().findViewById(R.id.durationCustom).performClick();
        assertEquals(View.VISIBLE, gate.get().findViewById(
                R.id.planned_minutes_input_layout).getVisibility());
        gate.get().findViewById(R.id.duration5).performClick();
        assertEquals(View.GONE, gate.get().findViewById(
                R.id.planned_minutes_input_layout).getVisibility());
        gate.get().findViewById(R.id.replacementWater).performClick();
        assertEquals(1, preferences.getDailyAlternativeChoiceCounts()[1]);
        gate.destroy();
    }

    @Test
    public void intentionEditsRefreshTodayAndShortcutFocusesTheSettingAtTop() {
        preferences.setSetupSeen(true);
        preferences.setFunctionalGoal("Read after dinner");
        ActivityController<ModernMainActivity> controller =
                Robolectric.buildActivity(ModernMainActivity.class).setup();
        ModernMainActivity activity = controller.get();
        EditText intentionInput = activity.findViewById(R.id.functionalGoalInput);
        TextView todayGoal = activity.findViewById(R.id.todayGoalText);

        intentionInput.setText("Call a friend");
        assertEquals("Call a friend", todayGoal.getText().toString());
        intentionInput.setText("");
        assertEquals("", preferences.getFunctionalGoal());
        assertEquals(application.getString(R.string.no_goal_yet), todayGoal.getText().toString());

        intentionInput.setText("Take a walk");
        ((EditText) activity.findViewById(R.id.deactivationKeyUnblockerInputText)).requestFocus();
        activity.findViewById(R.id.editIntentionButton).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.settingsScreen).getVisibility());
        assertTrue(intentionInput.hasFocus());
        assertEquals(0, activity.findViewById(R.id.settingsScreen).getScrollY());
        controller.destroy();
    }

    @Test
    public void activeProtectionLocksAdvancedActionsAndSettingListeners() {
        preferences.setDailyAllowanceSeconds(600);
        preferences.setRemainingBudgetSeconds(120);
        preferences.setDailySessionCount(3);
        preferences.setIsBlockerActive(true);

        ActivityController<ModernMainActivity> controller =
                Robolectric.buildActivity(ModernMainActivity.class).setup();
        ModernMainActivity activity = controller.get();
        View setupButton = activity.findViewById(R.id.rerunSetupButton);
        View resetButton = activity.findViewById(R.id.button_reset_stats);
        View editIntentionButton = activity.findViewById(R.id.editIntentionButton);

        assertFalse(setupButton.isEnabled());
        assertEquals(0.38f, setupButton.getAlpha(), 0.001f);
        assertFalse(resetButton.isEnabled());
        assertEquals(0.38f, resetButton.getAlpha(), 0.001f);
        assertEquals(View.GONE, editIntentionButton.getVisibility());
        ((EditText) activity.findViewById(R.id.dailyBudgetInput)).setText("20");
        activity.onResetStatsClick(resetButton);
        assertEquals(600, preferences.getDailyAllowanceSeconds());
        assertEquals(120, preferences.getRemainingBudgetSeconds());
        assertEquals(3, preferences.getDailySessionCount());
        controller.destroy();

        ActivityController<SetupActivity> setupController =
                Robolectric.buildActivity(SetupActivity.class).setup();
        assertTrue(setupController.get().isFinishing());
        assertEquals(600, preferences.getDailyAllowanceSeconds());
        setupController.destroy();
    }

    @Test
    public void visibleSettingsRefreshWhenDeactivationWindowOpens() {
        preferences.setSetupSeen(true);
        preferences.setDeactivationKey("secret");
        preferences.setDeactivationCooldownMinutes(1);
        preferences.setDeactivationWindowHours(1);
        preferences.setIsBlockerActive(true);
        Settings.Global.putInt(application.getContentResolver(), Settings.Global.BOOT_COUNT, 7);
        long boundaryDelayMs = 10_000;
        long elapsed = SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        preferences.savePendingDeactivation(new DeactivationPolicyEngine.Request(
                "visible-settings", wall - 60_000 + boundaryDelayMs,
                elapsed - 60_000 + boundaryDelayMs, 7, 60_000, 3_600_000));

        ActivityController<ModernMainActivity> controller =
                Robolectric.buildActivity(ModernMainActivity.class).setup();
        ModernMainActivity activity = controller.get();
        ((com.google.android.material.bottomnavigation.BottomNavigationView)
                activity.findViewById(R.id.bottomNavigation))
                .setSelectedItemId(R.id.navigation_settings);
        TextView status = activity.findViewById(R.id.deactivationRequestStatus);
        View action = activity.findViewById(R.id.button_blocker_activate);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.settingsScreen).getVisibility());
        assertEquals(View.GONE, action.getVisibility());
        assertEquals(application.getString(R.string.deactivation_cooldown_pending, "1 min"),
                status.getText().toString());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(11, TimeUnit.SECONDS);

        assertEquals(View.VISIBLE, action.getVisibility());
        assertEquals(application.getString(R.string.deactivate_now),
                ((TextView) action).getText().toString());
        assertTrue(status.getText().toString().contains("left to confirm"));
        controller.destroy();
    }

    private static void resetSingleton() throws Exception {
        Field field = AppPreferencesManagerSingleton.class.getDeclaredField("_instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
