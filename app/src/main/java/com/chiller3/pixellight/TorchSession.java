/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.pixellight;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A type to manage the lifecycle of the torch. The lifecycle begins when the torch is turned on and
 * ends when it turns off.
 */
public class TorchSession implements PowerManager.OnThermalStatusChangedListener {
    private enum State {
        OFF,
        ACTIVATING,
        ON,
    }

    private static final String TAG = TorchSession.class.getSimpleName();

    private static final int STEP_SIZE = 16;
    private static final long BOOST_TIMEOUT_MS = 2 * 60 * 1000L;

    public static final int BRIGHTNESS_DECREASE = -4;
    public static final int BRIGHTNESS_INCREASE = -3;
    public static final int BRIGHTNESS_TOGGLE = -2;
    public static final int BRIGHTNESS_PERSISTED = -1;

    // Things following the object lifecycle.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HashSet<Listener> listeners = new HashSet<>();
    private final ServiceOwner serviceOwner;
    private final CameraManager cameraManager;
    private final PowerManager powerManager;
    private final HandlerThread cameraThread = new HandlerThread("CameraThread");
    private final Handler cameraHandler;
    private final Executor cameraExecutor = new Executor() {
        @Override
        public void execute(Runnable command) {
            if (!cameraHandler.post(command)) {
                throw new RejectedExecutionException("Failed to queue runnable: " + command);
            }
        }
    };
    private final SurfaceTexture surfaceTexture = new SurfaceTexture(0);
    private final Surface surface;
    private final Preferences prefs;
    private final Runnable boostTimeoutRunnable = this::onBoostTimeout;

    // Things following the torch lifecycle.
    private State state = State.OFF;
    private String cameraId;
    private int maxBrightness = -1;
    private long maxFrameDuration = 0;
    private CameraCharacteristics characteristics;
    // Disabled automatically if the camera HAL rejects the low-power capture settings.
    private boolean optimizeIsp = true;
    private int curBrightness = 0;
    private int desiredBrightness = 0;
    private CameraDevice camera;
    private CameraCaptureSession session;

    // Callbacks.
    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mainHandler.post(() -> onCameraOpened(camera));
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mainHandler.post(() -> onCameraClosed(camera));
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            mainHandler.post(() -> onCameraError(camera, error));
        }
    };
    private final CameraCaptureSession.StateCallback sessionCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    mainHandler.post(() -> onSessionConfigured(session));
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    mainHandler.post(() -> onSessionConfigureFailed(session));
                }
            };

    public TorchSession(@NonNull Context context, @NonNull ServiceOwner owner) {
        serviceOwner = owner;

        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        cameraManager = context.getSystemService(CameraManager.class);
        powerManager = context.getSystemService(PowerManager.class);
        prefs = new Preferences(context);

        // Default to smallest standard buffer size until camera characteristics are queried.
        surfaceTexture.setDefaultBufferSize(160, 120);
        surface = new Surface(surfaceTexture);

        if (powerManager != null) {
            powerManager.addThermalStatusListener(context.getMainExecutor(), this);
        }
    }

    @MainThread
    public void close() {
        mainHandler.removeCallbacks(boostTimeoutRunnable);

        if (powerManager != null) {
            try {
                powerManager.removeThermalStatusListener(this);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Thermal listener was not registered", e);
            }
        }

        if (state != State.OFF) {
            closeCamera();
        }

        cameraThread.quitSafely();

        surface.release();
        surfaceTexture.release();
    }

    @MainThread
    public void registerTorchListener(@NonNull Listener listener) {
        Log.d(TAG, "Registering listener: " + listener);

        if (!listeners.add(listener)) {
            Log.w(TAG, "Listener was already registered: " + listener);
        }

        if (updateCameraDetails()) {
            listener.onTorchStateChanged(curBrightness, maxBrightness);
        }
    }

    @MainThread
    public void unregisterTorchListener(@NonNull Listener listener) {
        Log.d(TAG, "Unregistering listener: " + listener);

        if (!listeners.remove(listener)) {
            Log.w(TAG, "Listener was never registered: " + listener);
        }
    }

    public boolean isOwnerNeeded() {
        return state != State.OFF;
    }

    private void notifyOwnerNeeded() {
        Log.d(TAG, "Notifying owner that foreground mode is needed");
        serviceOwner.onTorchOwnerNeeded(true);
    }

    private void tryNotifyOwnerNotNeeded() {
        if (isOwnerNeeded()) {
            Log.d(TAG, "Foreground mode is still needed: state=" + state);
        } else {
            Log.d(TAG, "Notifying owner that foreground mode is not needed");
            serviceOwner.onTorchOwnerNeeded(false);
        }
    }

    @MainThread
    private boolean updateCameraDetails() {
        if (cameraId != null) {
            return true;
        }

        try {
            for (final var cameraId : cameraManager.getCameraIdList()) {
                final var characteristics = cameraManager.getCameraCharacteristics(cameraId);
                final var maxBrightness = characteristics.get(
                        ExperimentalKeys.CHARACTERISTICS_FLASHLIGHT_BRIGHTNESS_LEVEL_MAX);
                if (maxBrightness == null) {
                    continue;
                }

                this.cameraId = cameraId;
                this.maxBrightness = maxBrightness;
                this.curBrightness = 0;

                // Configure the smallest supported buffer size and longest frame duration to
                // minimize ISP and CPU overhead while the torch session is open.
                final var map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    final Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                    if (sizes != null && sizes.length > 0) {
                        Size smallest = sizes[0];
                        for (final Size s : sizes) {
                            if ((long) s.getWidth() * s.getHeight()
                                    < (long) smallest.getWidth() * smallest.getHeight()) {
                                smallest = s;
                            }
                        }
                        surfaceTexture.setDefaultBufferSize(
                                smallest.getWidth(), smallest.getHeight());
                        Log.d(TAG, "Using minimal SurfaceTexture size: " + smallest);
                    }
                }

                final var maxDuration = characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
                if (maxDuration != null && maxDuration > 0) {
                    this.maxFrameDuration = maxDuration;
                }

                this.characteristics = characteristics;

                Log.d(TAG, "Found camera " + cameraId + " with max brightness " + maxBrightness);

                return true;
            }

            Log.e(TAG, "Failed to find suitable camera");
            onError(TorchError.NO_VALID_CAMERA);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to query for suitable cameras", e);
            onError(TorchError.fromException(e));
        }

        return false;
    }

    @MainThread
    public void refreshCameras() {
        if (updateCameraDetails()) {
            for (final var listener : listeners) {
                listener.onTorchStateChanged(curBrightness, maxBrightness);
            }
        }
    }

    @MainThread
    public void setTorchBrightness(int brightness) {
        setTorchBrightness(brightness, true);
    }

    @MainThread
    public void setTorchBrightness(int brightness, boolean persist) {
        Log.d(TAG, "User requesting brightness of " + brightness + " (persist=" + persist + ")");

        if (maxBrightness <= 0 && !updateCameraDetails()) {
            return;
        }

        if (brightness >= 0) {
            desiredBrightness = brightness;
        } else if (brightness == BRIGHTNESS_PERSISTED) {
            desiredBrightness = prefs.getBrightness(maxBrightness);
        } else if (brightness == BRIGHTNESS_TOGGLE) {
            switch (state) {
                case OFF:
                    desiredBrightness = prefs.getBrightness(maxBrightness);
                    break;
                case ACTIVATING, ON:
                    desiredBrightness = 0;
                    break;
            }
        } else if (brightness == BRIGHTNESS_INCREASE) {
            if (curBrightness > 0) {
                desiredBrightness = Math.min(curBrightness + STEP_SIZE, maxBrightness);
            } else {
                Log.w(TAG, "Ignoring increase when turned off");
                return;
            }
        } else if (brightness == BRIGHTNESS_DECREASE) {
            if (curBrightness > 0) {
                desiredBrightness = Math.max(curBrightness - STEP_SIZE, 1);
            } else {
                Log.w(TAG, "Ignoring decrease when turned off");
                return;
            }
        } else {
            Log.w(TAG, "Ignoring invalid brightness value: " + brightness);
            return;
        }

        desiredBrightness = Math.min(desiredBrightness, maxBrightness);

        if (desiredBrightness > 0 && powerManager != null && prefs.getThermalProtection()) {
            final int thermal = powerManager.getCurrentThermalStatus();
            if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                Log.w(TAG, "Refusing to turn on torch: thermal status " + thermal);
                onError(TorchError.THERMAL_SHUTDOWN);
                return;
            } else if (thermal == PowerManager.THERMAL_STATUS_MODERATE) {
                final int safeStockMax = Math.max(1, maxBrightness / 2);
                if (desiredBrightness > safeStockMax) {
                    Log.w(TAG, "Clamping torch to " + safeStockMax + " due to thermal status");
                    desiredBrightness = safeStockMax;
                    persist = false;
                }
            }
        }

        if (desiredBrightness == 0) {
            closeCamera();
            return;
        } else if (persist) {
            prefs.setBrightness(desiredBrightness);
        }

        scheduleBoostTimeoutIfNeeded();

        switch (state) {
            case OFF -> openCamera();
            // Session is not ready yet. It'll pick up the new value when it is ready.
            case ACTIVATING -> {}
            // Session is already active. Change the brightness with a new capture request.
            case ON -> performCapture();
        }
    }

    @MainThread
    private void scheduleBoostTimeoutIfNeeded() {
        mainHandler.removeCallbacks(boostTimeoutRunnable);

        if (prefs.getBoostTimeout() && maxBrightness > 0) {
            final int safeStockMax = Math.max(1, maxBrightness / 2);
            if (desiredBrightness > safeStockMax) {
                mainHandler.postDelayed(boostTimeoutRunnable, BOOST_TIMEOUT_MS);
            }
        }
    }

    @MainThread
    private void onBoostTimeout() {
        if (state == State.ON && maxBrightness > 0) {
            final int safeStockMax = Math.max(1, maxBrightness / 2);
            if (curBrightness > safeStockMax) {
                Log.i(TAG, "Boost timeout reached; stepping down to safe max: " + safeStockMax);
                setTorchBrightness(safeStockMax, false);
            }
        }
    }

    @Override
    @MainThread
    public void onThermalStatusChanged(int status) {
        if (state == State.OFF || !prefs.getThermalProtection() || maxBrightness <= 0) {
            return;
        }

        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            Log.w(TAG, "Severe thermal status (" + status + "); shutting down torch");
            onError(TorchError.THERMAL_SHUTDOWN);
        } else if (status == PowerManager.THERMAL_STATUS_MODERATE) {
            final int safeStockMax = Math.max(1, maxBrightness / 2);
            if (curBrightness > safeStockMax) {
                Log.w(TAG, "Moderate thermal status; stepping down torch to " + safeStockMax);
                setTorchBrightness(safeStockMax, false);
            }
        }
    }

    @MainThread
    private void onError(@NonNull TorchError error) {
        Log.w(TAG, "Camera lifecycle exiting due to error: " + error);

        notifyTorchError(error);

        if (state != State.OFF) {
            closeCamera();
        }
    }

    @MainThread
    private void openCamera() {
        assert state == State.OFF;
        state = State.ACTIVATING;

        notifyOwnerNeeded();

        try {
            cameraManager.openCamera(cameraId, cameraCallback, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to open camera: " + cameraId, e);
            onError(TorchError.fromException(e));
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied when opening camera: " + cameraId, e);
            onError(TorchError.NO_PERMISSION);
        }
    }

    @MainThread
    private void closeCamera() {
        mainHandler.removeCallbacks(boostTimeoutRunnable);

        // We don't need to close the session. Closing the camera device is sufficient.
        session = null;

        if (camera != null) {
            camera.close();
            camera = null;
        }

        final var notifyOwner = state != State.OFF;
        state = State.OFF;
        curBrightness = 0;

        notifyTorchState();

        // Only notify if the torch was turned on. The service would not have been running in the
        // foreground otherwise.
        if (notifyOwner) {
            tryNotifyOwnerNotNeeded();
        }
    }

    @MainThread
    private void onCameraOpened(@NonNull CameraDevice camera) {
        Log.d(TAG, "Camera " + camera.getId() + " opened");

        this.camera = camera;

        final var output = Collections.singletonList(new OutputConfiguration(surface));
        final var sessionConfiguration = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, output, cameraExecutor, sessionCallback);

        try {
            camera.createCaptureSession(sessionConfiguration);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create capture session", e);
            onError(TorchError.fromException(e));
        }
    }

    @MainThread
    private void onCameraClosed(@NonNull CameraDevice camera) {
        Log.e(TAG, "Camera " + camera.getId() + " disconnected");

        onError(TorchError.DISCONNECTED);
    }

    @MainThread
    private void onCameraError(@NonNull CameraDevice camera, int error) {
        Log.e(TAG, "Camera " + camera.getId() + " failed with error: " + error);

        final var torchError = switch (error) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> TorchError.IN_USE;
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> TorchError.MAXIMUM_IN_USE;
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> TorchError.BLOCKED_BY_POLICY;
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> TorchError.DEVICE_ERROR;
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> TorchError.SERVICE_ERROR;
            default -> TorchError.UNKNOWN;
        };

        onError(torchError);
    }

    @MainThread
    private void onSessionConfigured(@NonNull CameraCaptureSession session) {
        Log.d(TAG, "Camera session configured: " + session);

        this.session = session;

        performCapture();
    }

    @MainThread
    private void onSessionConfigureFailed(@NonNull CameraCaptureSession session) {
        Log.e(TAG, "Failed to configure session: " + session);

        onError(TorchError.SESSION_ERROR);
    }

    @MainThread
    private void performCapture() {
        try {
            assert state == State.ACTIVATING || state == State.ON;
            state = State.ON;

            if (curBrightness != desiredBrightness) {
                Log.d(TAG, "Performing capture because current brightness (" + curBrightness +
                        ") != desired brightness (" + desiredBrightness + ")");
                curBrightness = desiredBrightness;

                final var captureRequest = session.getDevice()
                        .createCaptureRequest(CameraDevice.TEMPLATE_MANUAL);
                captureRequest.addTarget(surface);
                captureRequest.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
                captureRequest.set(ExperimentalKeys.REQUEST_FLASHLIGHT_BRIGHTNESS_ENABLED, true);
                captureRequest.set(ExperimentalKeys.REQUEST_FLASHLIGHT_BRIGHTNESS, curBrightness);

                if (optimizeIsp) {
                    applyLowPowerSettings(captureRequest);
                }

                session.capture(captureRequest.build(), captureCallback, cameraHandler);

                notifyTorchState();
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to perform capture", e);
            onError(TorchError.fromException(e));
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull android.hardware.camera2.CaptureFailure failure) {
                    mainHandler.post(() -> onCaptureFailedMain(session));
                }
            };

    @MainThread
    private void onCaptureFailedMain(@NonNull CameraCaptureSession failedSession) {
        if (optimizeIsp && failedSession == session && state == State.ON) {
            Log.w(TAG, "Capture failed with low-power settings; retrying with stock request");
            optimizeIsp = false;
            // Force performCapture() to issue a new request.
            final int target = curBrightness;
            curBrightness = -1;
            desiredBrightness = target;
            performCapture();
        }
    }

    private static boolean contains(int[] values, int value) {
        if (values == null) {
            return false;
        }
        for (final int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    /** Minimize ISP and sensor pipeline work. Only applies settings the camera advertises. */
    private void applyLowPowerSettings(@NonNull CaptureRequest.Builder builder) {
        final var c = characteristics;
        if (c == null) {
            return;
        }

        final int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        final boolean manualSensor = contains(caps,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);

        if (manualSensor) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);

            final var exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (exposureRange != null) {
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureRange.getLower());
            }
            final var isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (isoRange != null) {
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, isoRange.getLower());
            }
            if (maxFrameDuration > 0) {
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, maxFrameDuration);
            }
        }

        if (contains(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AF_MODE_OFF)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        }
        if (contains(c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                CaptureRequest.EDGE_MODE_OFF)) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
        }
        if (contains(c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                CaptureRequest.NOISE_REDUCTION_MODE_OFF)) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF);
        }
        if (contains(c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
                CaptureRequest.HOT_PIXEL_MODE_OFF)) {
            builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF);
        }
        if (contains(c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)) {
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);
        }
        builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF);
    }

    private void notifyTorchState() {
        if (cameraId != null) {
            for (final var listener : listeners) {
                listener.onTorchStateChanged(curBrightness, maxBrightness);
            }
        }
    }

    private void notifyTorchError(@NonNull TorchError error) {
        for (final var listener : listeners) {
            listener.onTorchError(error);
        }
    }

    public interface Listener {
        @MainThread
        void onTorchStateChanged(int curBrightness, int maxBrightness);

        @MainThread
        void onTorchError(@NonNull TorchError error);
    }

    public interface ServiceOwner {
        @MainThread
        void onTorchOwnerNeeded(boolean needed);
    }
}
