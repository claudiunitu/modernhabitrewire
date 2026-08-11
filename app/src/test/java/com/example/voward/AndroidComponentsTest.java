package com.example.voward;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AndroidComponentsTest {
    private Application application;

    @Before
    public void setUp() {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences("display_recovery_state", Context.MODE_PRIVATE)
                .edit().clear().commit();
        ChargingState.isCharging = false;
    }

    @Test
    public void chargingReceiverTracksOnlyPowerConnectionBroadcasts() {
        ChargingState receiver = new ChargingState();
        receiver.onReceive(application, new Intent(Intent.ACTION_POWER_CONNECTED));
        assertTrue(ChargingState.isCharging);
        receiver.onReceive(application, new Intent("other.action"));
        assertTrue(ChargingState.isCharging);
        receiver.onReceive(application, new Intent(Intent.ACTION_POWER_DISCONNECTED));
        assertFalse(ChargingState.isCharging);
        receiver.onReceive(application, new Intent());
        assertFalse(ChargingState.isCharging);
    }

    @Test
    public void deviceAdminReceiverExplainsConsequenceOfDisabling() {
        CharSequence message = new MyDeviceAdminReceiver()
                .onDisableRequested(application, new Intent());
        assertEquals("Disabling device administration will remove app protection.", message);
    }

    @Test
    public void grayscaleAvailabilityReflectsSecureSettingsPermission() {
        assertFalse(GrayscaleController.isGrayscaleAvailable(application));
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        assertTrue(GrayscaleController.isGrayscaleAvailable(application));
    }

    @Test
    public void grayscaleEnableJournalsAndDisableRestoresPreviousDisplayState() {
        Settings.Secure.putInt(application.getContentResolver(),
                "accessibility_display_daltonizer_enabled", 0);
        Settings.Secure.putInt(application.getContentResolver(),
                "accessibility_display_daltonizer", 12);
        GrayscaleController controller = new GrayscaleController(application);

        controller.setGrayscaleEnabled(true);
        assertEquals(1, Settings.Secure.getInt(application.getContentResolver(),
                "accessibility_display_daltonizer_enabled", -1));
        assertEquals(-1, Settings.Secure.getInt(application.getContentResolver(),
                "accessibility_display_daltonizer", 0));
        assertTrue(recovery().getBoolean("grayscale_active", false));

        controller.setGrayscaleEnabled(true);
        controller.setGrayscaleEnabled(false);
        assertEquals(0, Settings.Secure.getInt(application.getContentResolver(),
                "accessibility_display_daltonizer_enabled", -1));
        assertEquals(12, Settings.Secure.getInt(application.getContentResolver(),
                "accessibility_display_daltonizer", -1));
        assertTrue(recovery().getAll().isEmpty());
    }

    @Test
    public void constructorRecoversDisplayStateLeftByAnInterruptedSession() {
        Settings.Secure.putInt(application.getContentResolver(),
                "accessibility_display_daltonizer_enabled", 1);
        Settings.Secure.putInt(application.getContentResolver(),
                "accessibility_display_daltonizer", -1);
        recovery().edit()
                .putBoolean("grayscale_active", true)
                .putInt("previous_enabled", 0)
                .putInt("previous_mode", 7)
                .commit();

        new GrayscaleController(application);

        assertEquals(0, Settings.Secure.getInt(application.getContentResolver(),
                "accessibility_display_daltonizer_enabled", -1));
        assertEquals(7, Settings.Secure.getInt(application.getContentResolver(),
                "accessibility_display_daltonizer", -1));
        assertTrue(recovery().getAll().isEmpty());
    }

    private SharedPreferences recovery() {
        return application.getSharedPreferences("display_recovery_state", Context.MODE_PRIVATE);
    }
}
