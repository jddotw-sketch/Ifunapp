package com.local.ifunnyshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import java.util.concurrent.LinkedBlockingQueue;
import org.kivy.android.PythonService;

/** The generated ServiceDownloader extends this class; its Python thread drains the queue. */
public class DownloadServiceBase extends PythonService {
    private static DownloadServiceBase instance;
    private final LinkedBlockingQueue<String> jobs = new LinkedBlockingQueue<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int outstanding = 0;
    private PowerManager.WakeLock wakeLock;
    private static final String PROGRESS = "ifunny_download_progress";
    private static final String RESULTS = "ifunny_download_results";
    private final Runnable idleStop = () -> {
        if (outstanding == 0) stopSelf();
    };

    public static DownloadServiceBase current() { return instance; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager manager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(RESULTS, "Download results", NotificationManager.IMPORTANCE_DEFAULT));
        PowerManager power = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ifunnyshare:download");
        wakeLock.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        main.removeCallbacks(idleStop);
        String text = intent == null ? null : intent.getStringExtra("pythonServiceArgument");
        if (text != null && !text.isEmpty()) {
            outstanding++;
            jobs.offer(text);
            wakeLock.acquire(10 * 60 * 1000L);
        }
        // Starts the Python interpreter once; later calls only add jobs above.
        int result = super.onStartCommand(intent, flags, startId);
        if (outstanding == 0) main.postDelayed(idleStop, 3000);
        return result;
    }

    @Override protected void doStartForeground(Bundle extras) {
        Notification notification = progressNotification("Starting download…");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(getServiceId(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(getServiceId(), notification);
        }
    }

    private Notification progressNotification(String text) {
        return new Notification.Builder(this, PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("iFunny Downloader")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).build();
    }

    public String nextJob() throws InterruptedException { return jobs.take(); }

    public void updateProgress(String stage) {
        notifySafely(getServiceId(), progressNotification(stage));
    }

    private void notifySafely(int id, Notification notification) {
        try {
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, notification);
        } catch (SecurityException error) {
            Log.w("iFunnyDownloader", "Notifications are disabled", error);
        }
    }

    private synchronized int resultId() {
        int value = getSharedPreferences("download_results", MODE_PRIVATE).getInt("next_id", 1000);
        getSharedPreferences("download_results", MODE_PRIVATE).edit().putInt("next_id", value == Integer.MAX_VALUE ? 1000 : value + 1).apply();
        return value;
    }

    private void record(String message) {
        getSharedPreferences("download_results", MODE_PRIVATE).edit().putString("last_result", message).apply();
    }

    public void reportSuccess(String uri, String filename, String mime) {
        record("Saved to Download/iFunny\n" + filename);
        int id = resultId();
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent open = PendingIntent.getActivity(this, id, view, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notice = new Notification.Builder(this, RESULTS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("iFunny download complete")
            .setContentText(filename + " — Download/iFunny")
            .setStyle(new Notification.BigTextStyle().bigText(filename + "\nSaved to Download/iFunny"))
            .setContentIntent(open).setAutoCancel(true).build();
        notifySafely(id, notice);
    }

    public void reportFailure(String error) {
        record("Download failed\n" + error);
        Notification notice = new Notification.Builder(this, RESULTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("iFunny download failed").setContentText(error)
            .setStyle(new Notification.BigTextStyle().bigText(error)).setAutoCancel(true).build();
        notifySafely(resultId(), notice);
    }

    public void jobDone() {
        main.post(() -> {
            outstanding = Math.max(0, outstanding - 1);
            if (outstanding == 0) {
                updateProgress("All downloads finished");
                main.postDelayed(idleStop, 3000);
            }
        });
    }

    // Removing the launcher's task must not cancel a foreground download.
    @Override public void onTaskRemoved(Intent rootIntent) { }

    // Android 15's data-sync deadline: stop promptly rather than causing an ANR.
    @Override public void onTimeout(int startId, int type) {
        reportFailure("Android stopped the download service after its time limit.");
        stopSelf();
    }

    @Override public void onDestroy() {
        main.removeCallbacks(idleStop);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        instance = null;
        // p4a stops its interpreter and terminates only the dedicated service process.
        super.onDestroy();
    }
}
