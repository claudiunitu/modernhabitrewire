package com.example.voward;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

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
 * same truth the old one was, with none of the cost.</p>
 */
final class StatusNotifier {

    private static final String CHANNEL_ID = "firewall_stats_channel";
    private static final int NOTIFICATION_ID = 1;

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
        String sessionPackage = preferences.getManagedSessionPackage();
        if (!sessionPackage.isEmpty()) {
            long leftMs = preferences.getManagedSessionDeadlineElapsedMs()
                    - SystemClock.elapsedRealtime();
            if (leftMs > 0) {
                detail = context.getString(R.string.notification_remaining_in_session,
                        formatMinutesSeconds(remaining),
                        formatMinutesSeconds(TimeUnit.MILLISECONDS.toSeconds(leftMs)));
            }
        }

        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, ModernMainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.blocker_active))
                .setContentText(detail)
                // Native-density masks preserve the Voward mark's antialiased alpha edge.
                .setSmallIcon(R.mipmap.ic_notification_voward)
                .setColor(context.getColor(R.color.md_primary_container))
                .setOngoing(true)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
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
