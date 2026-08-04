package com.joshua.sp1loader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the app's process alive and protected from being killed while a
 * transfer is running - screen off, switched to another app, whatever.
 *
 * This does NOT own the USB connection or do any transfer work itself -
 * MainActivity's existing background thread (and its Sp1UsbSerial instance)
 * keep doing that exactly as before. A plain background thread's lifetime
 * is tied to the app's *process*, not to any one Activity instance - so the
 * only thing actually needed here is to stop Android from killing the
 * process while there's no visible Activity. A foreground notification
 * (via startForeground()) does that; a wake lock on top of it keeps the
 * CPU itself from suspending mid-transfer once the screen turns off.
 *
 * Started right before a transfer begins, stopped right after it ends
 * (success, failure, or user-stopped) - see MainActivity's
 * startTransferService()/stopTransferService().
 */
public class Sp1TransferService extends Service {

    private static final String CHANNEL_ID = "sp1_transfer";
    private static final int NOTIFICATION_ID = 1;

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(this, "Transfer in progress..."));

        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sp1loader:transfer");
        }
        if (!wakeLock.isHeld()) {
            // Safety cap rather than an indefinite hold - if something goes
            // very wrong and the service is never stopped, this still lets
            // the wake lock (and battery drain) end on its own eventually.
            wakeLock.acquire(60 * 60 * 1000L); // 1 hour
        }

        return START_NOT_STICKY;
    }

    /** Call from MainActivity while a transfer is running to update the notification text. */
    public static void updateProgress(Context context, String statusText) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(context, statusText));
        }
    }

    private static Notification buildNotification(Context context, String text) {
        Intent tapIntent = new Intent(context, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, tapIntent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }
        builder.setContentTitle("SP1 Loader")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pendingIntent)
                .setOngoing(true);
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "SP-1 Transfer", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows progress while transferring songs to the SP-1");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // not a bound service - just started/stopped
    }
}
