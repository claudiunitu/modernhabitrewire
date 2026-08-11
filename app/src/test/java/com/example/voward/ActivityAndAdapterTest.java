package com.example.voward;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    public void editorsHideMutationControlsWhileProtectionIsActive() {
        preferences.setIsBlockerActive(true);
        ActivityController<UrlListEditorActivity> controller =
                Robolectric.buildActivity(UrlListEditorActivity.class).setup();
        UrlListEditorActivity activity = controller.get();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.lockedBanner).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.editorComposer).getVisibility());
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
        ActivityController<SetupActivity> setup =
                Robolectric.buildActivity(SetupActivity.class).setup();
        assertEquals("20", ((EditText) setup.get().findViewById(
                R.id.setupBudgetInput)).getText().toString());
        setup.destroy();

        ActivityController<ModernMainActivity> main =
                Robolectric.buildActivity(ModernMainActivity.class).setup();
        assertEquals("20", ((EditText) main.get().findViewById(
                R.id.dailyBudgetInput)).getText().toString());
        ((EditText) main.get().findViewById(R.id.defaultSessionInput)).setText("15");
        assertEquals(900, preferences.getDefaultSessionSeconds());
        main.destroy();
    }

    private static void resetSingleton() throws Exception {
        Field field = AppPreferencesManagerSingleton.class.getDeclaredField("_instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
