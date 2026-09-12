package com.example.voward;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * The ongoing allowance notification, rebuilt without a resident service behind it.
 *
 * <p>It used to be posted by the accessibility service, which had a foreground callback on
 * every window change and could keep a running total on screen. Nothing is resident now, so
 * this is refreshed at the moments the numbers actually change: activation, release, both ends
 * of a session, boot, and each reconcile tick.</p>
 *
 * <p>Between those moments the remaining allowance does not move — time is only ever spent
 * inside an approved session — so a notification that updates on those edges is showing the
 * same truth the old one was, with none of the cost. The session countdown is the exception,
 * and it is drawn by the platform chronometer rather than re-posted.</p>
 */
final class StatusNotifier {

    private static final String CHANNEL_ID = "firewall_stats_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int END_SESSION_REQUEST = 4414;

    private StatusNotifier() {}

    /**
     * Posts, updates or withdraws the notification to match the current state.
     *
     * <p>Silent when protection is off, and silent when the user has not granted notifications:
     * this is an optional convenience, never a condition of enforcement.</p>
     */
    static void refresh(Context context, AppPreferencesManagerSingleton preferences) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (!preferences.getIsBlockerActive()) {
            manager.cancel(NOTIFICATION_ID);
            return;
        }
        if (!manager.areNotificationsEnabled()) return;
        createChannel(context, manager);

        long remaining = new AttentionBudgetEngine(context).getRemainingBudget();
        String detail = context.getString(R.string.notification_remaining,
                formatMinutesSeconds(remaining));
        long sessionLeftMs = 0;
        if (!preferences.getManagedSessionPackage().isEmpty()) {
            sessionLeftMs = preferences.getManagedSessionDeadlineElapsedMs()
                    - SystemClock.elapsedRealtime();
            if (sessionLeftMs > 0) {
                detail = context.getString(R.string.notification_remaining_in_session,
                        formatMinutesSeconds(remaining));
            }
        }

        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, ModernMainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.blocker_active))
                .setContentText(detail)
                // Native-density masks preserve the Voward mark's antialiased alpha edge.
                .setSmallIcon(R.mipmap.ic_notification_voward)
                .setColor(context.getColor(R.color.md_primary_container))
                .setOngoing(true)
                .setContentIntent(open)
                .setOnlyAlertOnce(true);
        if (sessionLeftMs > 0) {
            // The one number here that moves on its own, with nothing resident to redraw it.
            // Handing the deadline to the platform chronometer has the system tick it down
            // every second instead of freezing it at whatever it read when this was posted.
            builder.setWhen(System.currentTimeMillis() + sessionLeftMs)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true);
            // The only way to stop the clock. Without usage access the quoted duration is
            // charged whatever the user does, so leaving the app early has to be worth
            // something; with it, this is simply the honest end of a session.
            builder.addAction(0, context.getString(R.string.end_session_now),
                    endSessionIntent(context));
        } else {
            builder.setShowWhen(false);
        }
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private static PendingIntent endSessionIntent(Context context) {
        return PendingIntent.getBroadcast(context, END_SESSION_REQUEST,
                new Intent(context, SessionDeadlineReceiver.class)
                        .setAction(SessionDeadlineReceiver.ACTION_END_SESSION_NOW),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void createChannel(Context context, NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static String formatMinutesSeconds(long seconds) {
        long safe = Math.max(0, seconds);
        return String.format(Locale.getDefault(), "%d:%02d", safe / 60, safe % 60);
    }
}
