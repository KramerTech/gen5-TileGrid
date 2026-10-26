package com.tilepad;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

/** APK-contained ownership. There is no boot receiver and no automatic Start. */
public final class PreconditioningService extends Service {
    static final String START = "com.tilepad.PRECONDITION_START";
    static final String STOP = "com.tilepad.PRECONDITION_STOP";
    private static final int NOTIFICATION = 1203;
    private final LocalBinder binder = new LocalBinder();
    private HandlerThread thread;
    private Handler worker;
    private PreconditioningController controller;
    private SharedPreferences journal;
    private volatile PreconditioningSnapshot snapshot = PreconditioningSnapshot.checking();
    private boolean bound, foreground;
    final class LocalBinder extends Binder {
        PreconditioningSnapshot snapshot() { return snapshot; }
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            controller.refresh(SystemClock.elapsedRealtime()); publish();
            if (bound || snapshot.requested) worker.postDelayed(this, 1000);
            else stopSelf();
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        journal = getSharedPreferences("tilepad_preconditioning", MODE_PRIVATE);
        thread = new HandlerThread("tilepad-vehicle"); thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(new Runnable() { @Override public void run() {
            controller = new PreconditioningController(VehicleClientFactory.create(),
                new PreconditioningController.Journal() {
                    @Override public void requested(boolean value) {
                        if (!journal.edit().putBoolean("requested", value).commit())
                            throw new IllegalStateException("Could not record session ownership");
                    }
                });
            boolean pending = journal.getBoolean("requested", false);
            if (pending) promote("Recovering · cancel pending request");
            controller.recover(pending, SystemClock.elapsedRealtime());
            publish();
        } });
    }
    @Override public IBinder onBind(Intent intent) {
        worker.post(new Runnable() { @Override public void run() {
            bound = true; worker.removeCallbacks(tick); tick.run();
        } });
        return binder;
    }
    @Override public boolean onUnbind(Intent intent) {
        worker.post(new Runnable() { @Override public void run() {
            bound = false;
            if (!snapshot.requested) { worker.removeCallbacks(tick); stopSelf(); }
        } });
        return false;
    }
    @Override public int onStartCommand(final Intent intent, int flags, int startId) {
        worker.post(new Runnable() { @Override public void run() {
            if (intent == null) return;
            if (START.equals(intent.getAction())) {
                controller.refresh(SystemClock.elapsedRealtime());
                if (controller.snapshot(SystemClock.elapsedRealtime()).canStart) {
                    promote("Sending request");
                    controller.start(SystemClock.elapsedRealtime());
                }
            }
            else if (STOP.equals(intent.getAction())) controller.stop(SystemClock.elapsedRealtime());
            publish(); worker.removeCallbacks(tick);
            if (bound || snapshot.requested) worker.postDelayed(tick, 1000); else stopSelf();
        } });
        return START_NOT_STICKY;
    }
    private void publish() {
        snapshot = controller.snapshot(SystemClock.elapsedRealtime());
        if (snapshot.requested) {
            promote(snapshot.title() + " · " + snapshot.elapsed());
        } else if (foreground) { stopForeground(true); foreground = false; }
    }
    private void promote(String message) {
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent content = PendingIntent.getActivity(this, 1203, open, PendingIntent.FLAG_UPDATE_CURRENT);
            Intent stop = new Intent(this, PreconditioningService.class).setAction(STOP);
            PendingIntent cancel = PendingIntent.getService(this, 1204, stop, PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("Battery preconditioning")
                .setContentText(message).setOngoing(true)
                .setContentIntent(content).addAction(R.drawable.ic_notification, "Stop", cancel).build();
            startForeground(NOTIFICATION, notification); foreground = true;
    }
    @Override public void onDestroy() {
        if (worker != null) worker.removeCallbacksAndMessages(null);
        // Retain the ownership journal after unexpected destruction. Recovery attempts
        // cancellation, never Start. Do not claim cancellation from onDestroy alone.
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
