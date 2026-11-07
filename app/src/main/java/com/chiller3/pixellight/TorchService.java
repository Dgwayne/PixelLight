/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.pixellight;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Service for managing the torch state that can be bound. */
public class TorchService extends Service implements TorchSession.ServiceOwner, TorchSession.Listener {
    private static final String TAG = TorchService.class.getSimpleName();

    private static final String ACTION_SET_BRIGHTNESS =
            TorchService.class.getCanonicalName() + ".set_brightness";
    private static final String ACTION_PERSIST =
            TorchService.class.getCanonicalName() + ".persist";

    private static final String EXTRA_BRIGHTNESS = "brightness";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable debouncedNotificationRunnable = this::updateForegroundNotification;

    private TorchSession session;
    private Preferences prefs;
    private Notifications notifications;
    private int curBrightness = -1;
    private int maxBrightness = -1;
    private boolean foreground = false;

    public static @NonNull Intent createSetBrightnessIntent(
            @NonNull Context context, int brightness) {
        final var intent = new Intent(context, TorchService.class);
        intent.setAction(ACTION_SET_BRIGHTNESS);
        // This is unused, but necessary to ensure that intents for different brightnesses are
        // treated as unique when used with PendingIntent.
        intent.setData(Uri.fromParts(EXTRA_BRIGHTNESS, Integer.toString(brightness), null));
        intent.putExtra(EXTRA_BRIGHTNESS, brightness);
        return intent;
    }

    public static @NonNull Intent createPersistIntent(@NonNull Context context) {
        final var intent = new Intent(context, TorchService.class);
        intent.setAction(ACTION_PERSIST);
        return intent;
    }

    private boolean haveCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    @Nullable
    public IBinder onBind(Intent intent) {
        return new TorchBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Creating service");

        session = new TorchSession(this, this);
        prefs = new Preferences(this);
        notifications = new Notifications(this);

        session.registerTorchListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Destroying service");

        mainHandler.removeCallbacks(debouncedNotificationRunnable);
        session.unregisterTorchListener(this);
        session.close();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Received intent: " + intent);

        final var action = intent != null ? intent.getAction() : null;

        // Every intent may have been delivered via startForegroundService(), so startForeground()
        // must always be called, even when the request ends up not turning the torch on (eg.
        // turning off, thermal refusal, or no valid camera). Otherwise Android kills the app with
        // ForegroundServiceDidNotStartInTimeException.
        if (!updateForegroundNotification()) {
            Log.w(TAG, "Could not enter foreground; refusing request: " + intent);
            if (session.isOwnerNeeded()) {
                session.abort(TorchError.NO_PERMISSION);
            }
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        if (ACTION_SET_BRIGHTNESS.equals(action)) {
            final var brightness = intent.getIntExtra(EXTRA_BRIGHTNESS, TorchSession.BRIGHTNESS_TOGGLE);
            session.setTorchBrightness(brightness);
        } else if (ACTION_PERSIST.equals(action)) {
            Log.d(TAG, "Keeping service alive");
        } else {
            Log.w(TAG, "Invalid intent: " + intent);
        }

        tryStopService();

        return START_NOT_STICKY;
    }

    /**
     * Enter (or refresh) the foreground state.
     *
     * @return false if Android refused, eg. because the camera permission was revoked or the app is
     *         not allowed to start a camera foreground service right now.
     */
    @MainThread
    private boolean updateForegroundNotification() {
        mainHandler.removeCallbacks(debouncedNotificationRunnable);
        Log.d(TAG, "Updating foreground notification");

        if (!haveCameraPermission()) {
            // startForeground() with the camera type throws SecurityException without CAMERA.
            Log.w(TAG, "Camera permission not granted");
            notifications.sendErrorNotification(TorchError.NO_PERMISSION);
            return false;
        }

        try {
            final var notification = notifications.createPersistentNotification(
                    curBrightness, maxBrightness);
            startForeground(Notifications.ID_PERSISTENT, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } catch (RuntimeException e) {
            // SecurityException, ForegroundServiceStartNotAllowedException, or
            // InvalidForegroundServiceTypeException.
            Log.e(TAG, "Failed to enter foreground", e);
            return false;
        }

        foreground = true;
        return true;
    }

    private void stopSelfSafely() {
        mainHandler.removeCallbacks(debouncedNotificationRunnable);
        if (foreground) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        stopSelf();
    }

    public void tryStopService() {
        final var ownerNeeded = session.isOwnerNeeded();
        Log.d(TAG, "Attempting to stop service: ownerNeeded=" + ownerNeeded);

        if (prefs.getKeepServiceAlive() && foreground) {
            Log.d(TAG, "Keeping service alive indefinitely");
        } else if (!ownerNeeded) {
            Log.d(TAG, "Stopping service");
            stopSelfSafely();
        } else {
            Log.d(TAG, "Cannot stop service yet");
        }
    }

    @Override
    public void onTorchOwnerNeeded(boolean needed) {
        if (needed) {
            Log.d(TAG, "Entering foreground to keep the torch alive");

            // Mark the service as started so it survives unbinding. A plain startService() is
            // used instead of startForegroundService() so that Android does not demand a
            // startForeground() call later. Previously, if the torch failed quickly (eg. camera in
            // use or thermal refusal), the service stopped itself before that happened and Android
            // crashed the app.
            try {
                startService(createPersistIntent(this));
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to mark service as started", e);
            }

            if (!updateForegroundNotification()) {
                session.abort(haveCameraPermission()
                        ? TorchError.BACKGROUND_START : TorchError.NO_PERMISSION);
            }
        } else {
            tryStopService();
        }
    }

    @Override
    public void onTorchStateChanged(int curBrightness, int maxBrightness) {
        final boolean toggled = (this.curBrightness <= 0) != (curBrightness <= 0);
        this.curBrightness = curBrightness;
        this.maxBrightness = maxBrightness;

        if (foreground) {
            if (toggled) {
                updateForegroundNotification();
            } else {
                mainHandler.removeCallbacks(debouncedNotificationRunnable);
                mainHandler.postDelayed(debouncedNotificationRunnable, 150);
            }
        }
    }

    @Override
    public void onTorchError(@NonNull TorchError error) {
        notifications.sendErrorNotification(error);
    }

    public class TorchBinder extends Binder {
        @MainThread
        public void registerTorchListener(@NonNull TorchSession.Listener listener) {
            session.registerTorchListener(listener);
        }

        @MainThread
        public void unregisterTorchListener(@NonNull TorchSession.Listener listener) {
            session.unregisterTorchListener(listener);
        }

        @MainThread
        public void setTorchBrightness(int brightness) {
            session.setTorchBrightness(brightness, true);
        }

        @MainThread
        public void setTorchBrightness(int brightness, boolean persist) {
            session.setTorchBrightness(brightness, persist);
        }

        @MainThread
        public void refreshCameras() {
            session.refreshCameras();
        }

        @MainThread
        public boolean isUsingPrivateApi() {
            return session.isUsingPrivateApi();
        }

        @MainThread
        public boolean isInForeground() {
            return foreground;
        }

        @MainThread
        public void ensureAliveIfConfigured() {
            if (prefs.getKeepServiceAlive() && !foreground && Permissions.haveRequired(TorchService.this)) {
                try {
                    startForegroundService(createPersistIntent(TorchService.this));
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to start persistent service", e);
                }
            }
        }

        @MainThread
        public void tryStopService() {
            TorchService.this.tryStopService();
        }
    }
}
