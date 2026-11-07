/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.pixellight;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import rikka.shizuku.Shizuku;

/**
 * Drives the Pixel 11 Pro rear "HiLight" RGB LED ring through a Shizuku user service.
 *
 * Google does not let regular apps control these LEDs, so Shizuku (running with ADB shell
 * privileges) is required. All binder calls happen on a background thread because clearing the
 * LEDs sleeps briefly.
 */
public class RgbLed {
    private static final String TAG = RgbLed.class.getSimpleName();

    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final int REQUEST_CODE = 4242;

    // These LEDs are sized for notifications, not as a flashlight. Like HiLight Studio, fade them
    // down after a while and stop entirely after a few minutes to avoid overheating them.
    private static final long TAPER_AFTER_MS = 10_000;
    private static final long TAPER_RAMP_MS = 10_000;
    private static final float TAPER_FLOOR = 0.55f;
    public static final long AUTO_OFF_MS = 5 * 60_000;
    private static final long TICK_MS = 500;

    public enum Status {
        /** Shizuku is not installed. */
        NOT_INSTALLED,
        /** Shizuku is installed but its service isn't running (needs to be started after boot). */
        NOT_RUNNING,
        /** Shizuku is running but PixelLight+ hasn't been allowed yet. */
        NO_PERMISSION,
        CONNECTING,
        READY,
        /** Connected, but no RGB LED array was found (not a Pixel 11 Pro, or unsupported build). */
        UNSUPPORTED,
    }

    public interface Listener {
        @MainThread
        void onRgbLedStatusChanged(@NonNull Status status);

        @MainThread
        void onRgbLedAutoOff();
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread workerThread = new HandlerThread("RgbLed");
    private final Handler worker;
    private final Shizuku.UserServiceArgs serviceArgs;

    private Status status = Status.NOT_RUNNING;
    private volatile IRgbLedService service;
    private boolean bound = false;
    private boolean started = false;

    // Rendering state, main thread only.
    @ColorInt private int requestedColor = Color.BLACK;
    private float requestedBrightness = 1f;
    private long litSince = 0;
    private int lastSent = -1;

    private final Runnable tickRunnable = this::tick;

    private final Shizuku.OnBinderReceivedListener binderReceived = () ->
            mainHandler.post(this::refresh);
    private final Shizuku.OnBinderDeadListener binderDead = () -> mainHandler.post(() -> {
        service = null;
        bound = false;
        refresh();
    });
    private final Shizuku.OnRequestPermissionResultListener permissionResult =
            (requestCode, grantResult) -> {
                if (requestCode == REQUEST_CODE) {
                    mainHandler.post(this::refresh);
                }
            };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder == null || !binder.pingBinder()) {
                return;
            }
            final var s = IRgbLedService.Stub.asInterface(binder);
            worker.post(() -> {
                int count = 0;
                try {
                    count = s.ledCount();
                    Log.i(TAG, "LED service: " + s.describe());
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to query LED service", e);
                }
                final int ledCount = count;
                mainHandler.post(() -> {
                    service = s;
                    lastSent = -1;
                    setStatus(ledCount > 0 ? Status.READY : Status.UNSUPPORTED);
                    render();
                });
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            refresh();
        }
    };

    public RgbLed(@NonNull Context context, @NonNull Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;

        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        serviceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(context.getPackageName(), RgbLedUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("rgbled")
                .debuggable(false)
                .version(1);
    }

    @MainThread
    public void start() {
        if (started) {
            return;
        }
        started = true;
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived, mainHandler);
            Shizuku.addBinderDeadListener(binderDead, mainHandler);
            Shizuku.addRequestPermissionResultListener(permissionResult, mainHandler);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to register Shizuku listeners", e);
        }
        refresh();
    }

    /** Turn the LEDs off, release the service and stop listening. */
    @MainThread
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        off();
        try {
            Shizuku.removeBinderReceivedListener(binderReceived);
            Shizuku.removeBinderDeadListener(binderDead);
            Shizuku.removeRequestPermissionResultListener(permissionResult);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to unregister Shizuku listeners", e);
        }
        final boolean wasBound = bound;
        bound = false;
        service = null;
        // Unbind after the queued clear() has run so the LEDs are handed back cleanly.
        worker.post(() -> {
            if (wasBound) {
                try {
                    Shizuku.unbindUserService(serviceArgs, connection, true);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to unbind LED service", e);
                }
            }
            workerThread.quitSafely();
        });
    }

    @NonNull
    public Status getStatus() {
        return status;
    }

    private boolean isShizukuInstalled() {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @MainThread
    private void refresh() {
        if (!started) {
            return;
        }

        final boolean running;
        try {
            running = Shizuku.pingBinder();
        } catch (RuntimeException e) {
            setStatus(isShizukuInstalled() ? Status.NOT_RUNNING : Status.NOT_INSTALLED);
            return;
        }

        if (!running) {
            service = null;
            setStatus(isShizukuInstalled() ? Status.NOT_RUNNING : Status.NOT_INSTALLED);
            return;
        }

        if (Shizuku.isPreV11()
                || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            setStatus(Status.NO_PERMISSION);
            return;
        }

        if (service != null) {
            return;
        }

        setStatus(Status.CONNECTING);
        if (!bound) {
            try {
                Shizuku.bindUserService(serviceArgs, connection);
                bound = true;
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to bind LED service", e);
                setStatus(Status.NOT_RUNNING);
            }
        }
    }

    /**
     * Ask Shizuku for permission. Returns false if Shizuku isn't running, in which case the caller
     * should show setup instructions.
     */
    @MainThread
    public boolean requestAccess() {
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refresh();
            } else {
                Shizuku.requestPermission(REQUEST_CODE);
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to request Shizuku permission", e);
            return false;
        }
    }

    private void setStatus(@NonNull Status newStatus) {
        if (status != newStatus) {
            status = newStatus;
            listener.onRgbLedStatusChanged(newStatus);
        }
    }

    /**
     * Show a color. Black keeps the LEDs claimed but dark (used for strobe gaps). Call {@link #off()}
     * to hand them back to the system.
     */
    @MainThread
    public void show(@ColorInt int color, float brightness) {
        final boolean wasLit = litSince != 0;
        requestedColor = color;
        requestedBrightness = Math.max(0f, Math.min(1f, brightness));
        if (!wasLit) {
            litSince = SystemClock.elapsedRealtime();
            mainHandler.removeCallbacks(tickRunnable);
            mainHandler.postDelayed(tickRunnable, TICK_MS);
        }
        render();
    }

    @MainThread
    public void off() {
        mainHandler.removeCallbacks(tickRunnable);
        litSince = 0;
        lastSent = -1;
        final var s = service;
        if (s != null) {
            worker.post(() -> {
                try {
                    s.clear();
                } catch (RemoteException | RuntimeException e) {
                    Log.w(TAG, "Failed to clear LEDs", e);
                }
            });
        }
    }

    public boolean isOn() {
        return litSince != 0;
    }

    @MainThread
    private void tick() {
        if (litSince == 0) {
            return;
        }
        if (SystemClock.elapsedRealtime() - litSince >= AUTO_OFF_MS) {
            Log.i(TAG, "Auto-off after " + AUTO_OFF_MS + " ms");
            off();
            listener.onRgbLedAutoOff();
            return;
        }
        // Re-render so the brightness taper progresses even for a steady color.
        render();
        mainHandler.postDelayed(tickRunnable, TICK_MS);
    }

    private float taper() {
        final long lit = SystemClock.elapsedRealtime() - litSince;
        if (lit <= TAPER_AFTER_MS) {
            return 1f;
        }
        final float progress = Math.min(1f, (float) (lit - TAPER_AFTER_MS) / TAPER_RAMP_MS);
        return 1f - (1f - TAPER_FLOOR) * progress;
    }

    @MainThread
    private void render() {
        final var s = service;
        if (s == null || status != Status.READY || litSince == 0) {
            return;
        }

        final float scale = requestedBrightness * taper();
        final int rgb = Color.rgb(
                Math.round(Color.red(requestedColor) * scale),
                Math.round(Color.green(requestedColor) * scale),
                Math.round(Color.blue(requestedColor) * scale)) & 0x00FFFFFF;
        if (rgb == lastSent) {
            return;
        }
        lastSent = rgb;

        // Coalesce: only the newest color matters if the worker falls behind (eg. rainbow mode).
        worker.removeCallbacksAndMessages(RENDER_TOKEN);
        worker.postAtTime(() -> {
            try {
                s.setColor(rgb);
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "Failed to set LED color", e);
            }
        }, RENDER_TOKEN, SystemClock.uptimeMillis());
    }

    private static final Object RENDER_TOKEN = new Object();
}
