/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Approach based on the MIT-licensed HiLight Studio (github.com/DhananjayBhosale/hilight-studio)
 * and Essentials (github.com/sameerasw/essentials) projects.
 */

package com.chiller3.pixellight;

import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shizuku starts this class in an app_process running as the shell UID. The shell user holds
 * android.permission.CONTROL_DEVICE_LIGHTS (signature|privileged), which normal apps can never
 * get, so this is the only non-root way to drive the HiLight RGB LEDs.
 *
 * Talks to the hidden ILightsManager binder directly because there is no Context here.
 */
public class RgbLedUserService extends IRgbLedService.Stub {
    private static final String TAG = RgbLedUserService.class.getSimpleName();

    // A color with zero RGB but non-zero alpha. Some units were reported to latch an LED on when
    // going straight to 0, so clear in two steps like HiLight Studio does.
    private static final int ALPHA_BLACK = 0x01000000;
    private static final long MAX_SETTLE_MS = 250;
    private static final int SESSION_PRIORITY = 0;

    private final Object lock = new Object();
    private Object service;
    private Method openSession;
    private Method closeSession;
    private Method setLightStates;
    private int[] ledIds = new int[0];
    private long minUpdatePeriodMs = 33;
    private String description = "not initialized";
    private IBinder session;

    /** Shizuku instantiates user services with a no-arg constructor. */
    public RgbLedUserService() {
        try {
            init();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize lights service", t);
            description = "init failed: " + t;
        }
    }

    @android.annotation.SuppressLint({"NewApi", "PrivateApi"})
    private void init() throws Exception {
        // The HiLight light type was added in Android 17 (API 37).
        if (Build.VERSION.SDK_INT < 37) {
            description = "requires Android 17";
            return;
        }

        final var binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "lights");
        if (binder == null) {
            description = "no lights service";
            return;
        }

        final var iface = Class.forName("android.hardware.lights.ILightsManager");
        service = Class.forName("android.hardware.lights.ILightsManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        final Method getLights = iface.getMethod("getLights");
        openSession = iface.getMethod("openSession", IBinder.class, int.class);
        closeSession = iface.getMethod("closeSession", IBinder.class);
        setLightStates = iface.getMethod("setLightStates",
                IBinder.class, int[].class, LightState[].class);

        @SuppressWarnings("unchecked")
        final var all = (List<Light>) getLights.invoke(service);
        final var rgb = new ArrayList<Light>();
        final var sb = new StringBuilder();
        if (all != null) {
            for (final var light : all) {
                sb.append(light.getName()).append(" type=").append(light.getType())
                        .append(" rgb=").append(light.hasRgbControl()).append("; ");
                // LIGHT_TYPE_APPLICATION is the API 37 type used for the HiLight array.
                if (light.getType() == Light.LIGHT_TYPE_APPLICATION && light.hasRgbControl()) {
                    rgb.add(light);
                    minUpdatePeriodMs = Math.max(minUpdatePeriodMs,
                            light.getMinUpdatePeriodMillis());
                }
            }
        }
        // Ordered so effects sweep around the physical ring.
        rgb.sort(Comparator.comparingInt(Light::getOrdinal));
        ledIds = rgb.stream().mapToInt(Light::getId).toArray();
        description = ledIds.length + " RGB LEDs; " + sb;
        Log.i(TAG, description);
    }

    @Override
    public int ledCount() {
        synchronized (lock) {
            return ledIds.length;
        }
    }

    @Override
    public String describe() {
        synchronized (lock) {
            return description;
        }
    }

    private boolean write(int color) throws Exception {
        final var states = new LightState[ledIds.length];
        final var state = new LightState.Builder().setColor(color).build();
        for (int i = 0; i < states.length; ++i) {
            states[i] = state;
        }
        setLightStates.invoke(service, session, ledIds, states);
        return true;
    }

    @Override
    public boolean setColor(int rgb) {
        synchronized (lock) {
            if (service == null || ledIds.length == 0) {
                return false;
            }
            try {
                if (session == null) {
                    final var token = new Binder();
                    openSession.invoke(service, token, SESSION_PRIORITY);
                    session = token;
                }
                return write(0xFF000000 | rgb);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to set color", t);
                session = null;
                return false;
            }
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            clearLocked();
        }
    }

    private void clearLocked() {
        if (session == null || service == null) {
            return;
        }
        final long settle = Math.max(1, Math.min(minUpdatePeriodMs, MAX_SETTLE_MS));
        try {
            write(ALPHA_BLACK);
            Thread.sleep(settle);
            write(0);
            Thread.sleep(settle);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to blank LEDs", t);
        }
        try {
            // Closing the session hands the LEDs back to the system's own HiLight effects.
            closeSession.invoke(service, session);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to close session", t);
        }
        session = null;
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            clearLocked();
        }
        System.exit(0);
    }
}
