/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.pixellight;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.chiller3.pixellight.databinding.MainActivityBinding;

import java.util.Arrays;

public class MainActivity extends Activity implements ServiceConnection, TorchSession.Listener,
        SeekBar.OnSeekBarChangeListener {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int REQUEST_PERMISSIONS = 1;

    private static final float[] PRESETS = {0.10f, 0.25f, 0.50f, 1.0f};
    private static final int[] ACCENT_NAMES = {
            R.string.accent_dynamic, R.string.accent_amber, R.string.accent_ice,
            R.string.accent_lime, R.string.accent_rose, R.string.accent_violet,
            R.string.accent_ember, R.string.accent_mint,
    };

    private MainActivityBinding binding;
    private Preferences prefs;
    private TorchService.TorchBinder torchBinder;
    private boolean bound = false;
    private boolean initialUpdate = true;
    private int curBrightness = 0;
    private int maxBrightness = -1;
    @ColorInt private int accent;
    private TextView[] presetViews;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = MainActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = new Preferences(this);

        // Android 15+ forces edge-to-edge, so keep content clear of the status and nav bars.
        final int basePaddingBottom = binding.content.getPaddingBottom();
        binding.root.setOnApplyWindowInsetsListener((v, insets) -> {
            final var bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            binding.content.setPadding(binding.content.getPaddingLeft(), bars.top,
                    binding.content.getPaddingRight(), basePaddingBottom + bars.bottom);
            return insets;
        });

        binding.brightness.setOnSeekBarChangeListener(this);
        binding.brightness.setMinHeight(dp(6));
        binding.brightness.setMaxHeight(dp(6));

        binding.glow.setOnClickListener(v -> {
            if (torchBinder == null || maxBrightness <= 0) {
                return;
            }
            haptic(v, HapticFeedbackConstants.CONFIRM);
            torchBinder.setTorchBrightness(curBrightness > 0
                    ? 0 : TorchSession.BRIGHTNESS_PERSISTED);
        });

        binding.dimmer.setOnClickListener(v -> step(v, false));
        binding.brighter.setOnClickListener(v -> step(v, true));

        presetViews = new TextView[] {
                binding.presetLow, binding.presetMedium, binding.presetHigh, binding.presetBoost,
        };
        for (int i = 0; i < presetViews.length; ++i) {
            final float fraction = PRESETS[i];
            presetViews[i].setOnClickListener(v -> {
                haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
                applyPreset(fraction);
            });
        }

        binding.settings.setOnClickListener(this::showSettings);
        binding.screenLight.setOnClickListener(v ->
                startActivity(new Intent(this, ScreenLightActivity.class)));

        binding.requestPermissions.setOnClickListener(v ->
                requestPermissions(Permissions.REQUIRED, REQUEST_PERMISSIONS));

        buildAccentRow();
        applyAccent();
        render();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void haptic(@NonNull View view, int constant) {
        if (prefs.getHaptics()) {
            view.performHapticFeedback(constant);
        }
    }

    private void step(@NonNull View view, boolean up) {
        if (torchBinder == null || maxBrightness <= 0) {
            return;
        }
        haptic(view, HapticFeedbackConstants.CLOCK_TICK);
        if (curBrightness > 0) {
            torchBinder.setTorchBrightness(up
                    ? TorchSession.BRIGHTNESS_INCREASE : TorchSession.BRIGHTNESS_DECREASE);
        } else {
            // Adjust the saved level while off so the next turn-on uses it.
            final int step = Math.max(1, maxBrightness / 16);
            final int saved = prefs.getBrightness(maxBrightness);
            final int next = Math.max(1, Math.min(maxBrightness, saved + (up ? step : -step)));
            prefs.setBrightness(next);
            render();
        }
    }

    private void applyPreset(float fraction) {
        if (torchBinder == null || maxBrightness <= 0) {
            return;
        }

        torchBinder.setTorchBrightness(Brightness.fromFraction(fraction, maxBrightness), true);
    }

    // Accent colors

    private void buildAccentRow() {
        final var row = binding.accentRow;
        row.removeAllViews();

        for (int i = 0; i < Accents.count(); ++i) {
            final int index = i;
            final var swatch = new View(this);
            final var params = new LinearLayout.LayoutParams(0, dp(40), 1f);
            params.setMarginStart(i == 0 ? 0 : dp(6));
            swatch.setLayoutParams(params);
            swatch.setContentDescription(getString(ACCENT_NAMES[i]));
            swatch.setClickable(true);
            swatch.setFocusable(true);
            swatch.setOnClickListener(v -> {
                haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
                prefs.setAccent(index);
                applyAccent();
            });
            row.addView(swatch);
        }
    }

    private void applyAccent() {
        accent = Accents.current(this);
        final int selected = prefs.getAccent();

        for (int i = 0; i < binding.accentRow.getChildCount(); ++i) {
            final var swatch = binding.accentRow.getChildAt(i);
            final var shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(Accents.get(this, i));
            if (i == selected) {
                shape.setStroke(dp(3), Color.WHITE);
            }
            swatch.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF), shape, null));
            swatch.setSelected(i == selected);
            swatch.setScaleX(i == selected ? 1.0f : 0.82f);
            swatch.setScaleY(i == selected ? 1.0f : 0.82f);
        }

        binding.glow.setAccent(accent);

        final var accentList = ColorStateList.valueOf(accent);
        binding.brightness.setProgressTintList(accentList);
        binding.brightness.setThumbTintList(accentList);
        binding.brightness.setProgressBackgroundTintList(
                ColorStateList.valueOf(getColor(R.color.outline)));

        binding.screenLightIcon.setImageTintList(accentList);

        final var button = new GradientDrawable();
        button.setCornerRadius(dp(26));
        button.setColor(accent);
        binding.requestPermissions.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33000000), button, null));
        binding.requestPermissions.setTextColor(Accents.onColor(accent));

        renderPresets();
    }

    private void renderPresets() {
        int activeIndex = -1;
        if (curBrightness > 0 && maxBrightness > 0) {
            for (int i = 0; i < PRESETS.length; ++i) {
                if (Brightness.fromFraction(PRESETS[i], maxBrightness) == curBrightness) {
                    activeIndex = i;
                }
            }
        }

        for (int i = 0; i < presetViews.length; ++i) {
            final boolean active = i == activeIndex;
            final var chip = new GradientDrawable();
            chip.setCornerRadius(dp(22));
            chip.setColor(active ? accent : getColor(R.color.surface_high));
            presetViews[i].setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF), chip, null));
            presetViews[i].setTextColor(active
                    ? Accents.onColor(accent) : getColor(R.color.text_primary));
            presetViews[i].setEnabled(maxBrightness > 0);
            presetViews[i].setAlpha(maxBrightness > 0 ? 1f : 0.4f);
        }
    }

    // Settings

    private void showSettings(@NonNull View anchor) {
        final var popup = new PopupMenu(this, anchor);
        popup.inflate(R.menu.main_activity_options);
        final var menu = popup.getMenu();
        menu.findItem(R.id.keep_service_alive).setChecked(prefs.getKeepServiceAlive());
        menu.findItem(R.id.thermal_protection).setChecked(prefs.getThermalProtection());
        menu.findItem(R.id.boost_timeout).setChecked(prefs.getBoostTimeout());
        menu.findItem(R.id.haptics).setChecked(prefs.getHaptics());
        menu.findItem(R.id.volume_keys).setChecked(prefs.getVolumeKeys());
        popup.setOnMenuItemClickListener(this::onSettingSelected);
        popup.show();
    }

    private boolean onSettingSelected(@NonNull MenuItem item) {
        final int id = item.getItemId();
        final boolean checked = !item.isChecked();
        item.setChecked(checked);

        if (id == R.id.keep_service_alive) {
            prefs.setKeepServiceAlive(checked);

            if (torchBinder != null) {
                if (checked) {
                    // Start the persistent service right away so the QS tile works without
                    // collapsing the panel, instead of waiting for the first toggle.
                    torchBinder.ensureAliveIfConfigured();
                } else {
                    // Try to shut down the service so that the user doesn't have to manually turn
                    // the torch on and off for the change to take effect.
                    torchBinder.tryStopService();
                }
            }
        } else if (id == R.id.thermal_protection) {
            prefs.setThermalProtection(checked);
        } else if (id == R.id.boost_timeout) {
            prefs.setBoostTimeout(checked);
        } else if (id == R.id.haptics) {
            prefs.setHaptics(checked);
        } else if (id == R.id.volume_keys) {
            prefs.setVolumeKeys(checked);
        } else {
            return false;
        }

        return true;
    }

    // Volume keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (prefs.getVolumeKeys() && torchBinder != null && curBrightness > 0) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                torchBinder.setTorchBrightness(TorchSession.BRIGHTNESS_INCREASE);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                torchBinder.setTorchBrightness(TorchSession.BRIGHTNESS_DECREASE);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (prefs.getVolumeKeys() && curBrightness > 0 && (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // Permissions & lifecycle

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.length > 0 && Arrays.stream(grantResults)
                    .allMatch(r -> r == PackageManager.PERMISSION_GRANTED)) {
                refreshUiGroups();
                refreshCameras();
                if (torchBinder != null) {
                    torchBinder.ensureAliveIfConfigured();
                }
            } else if (!Permissions.canRequestRequired(this)) {
                try {
                    startActivity(Permissions.getAppInfoIntent(this));
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to open app info", e);
                }
            }
        }
    }

    private void refreshUiGroups() {
        final var haveRequired = Permissions.haveRequired(this);
        binding.torchGroup.setVisibility(haveRequired ? View.VISIBLE : View.GONE);
        binding.permissionGroup.setVisibility(haveRequired ? View.GONE : View.VISIBLE);
    }

    private void refreshCameras() {
        if (torchBinder != null) {
            initialUpdate = true;
            torchBinder.refreshCameras();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        refreshUiGroups();
        applyAccent();

        final var intent = new Intent(this, TorchService.class);
        bound = bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The user may have granted permissions from Android's settings.
        final boolean wasMissing = binding.permissionGroup.getVisibility() == View.VISIBLE;
        refreshUiGroups();
        if (wasMissing && Permissions.haveRequired(this)) {
            refreshCameras();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        onBinderGone();
        if (bound) {
            bound = false;
            unbindService(this);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        torchBinder = (TorchService.TorchBinder) service;
        torchBinder.registerTorchListener(this);
        torchBinder.ensureAliveIfConfigured();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        onBinderGone();
    }

    private void onBinderGone() {
        if (torchBinder != null) {
            torchBinder.unregisterTorchListener(this);
        }

        torchBinder = null;
    }

    // Rendering

    private void render() {
        final boolean ready = maxBrightness > 0;
        final boolean on = ready && curBrightness > 0;
        final boolean boost = on && torchBinder != null && torchBinder.isUsingPrivateApi()
                && Brightness.isBoost(curBrightness, maxBrightness);
        final int saved = ready ? prefs.getBrightness(maxBrightness) : 0;

        if (!ready) {
            binding.status.setText(R.string.status_loading);
            binding.percent.setText("–");
            binding.level.setText("");
        } else if (on) {
            final int pct = Brightness.toPercent(curBrightness, maxBrightness);
            binding.status.setText(boost ? R.string.status_boost : R.string.status_on);
            binding.percent.setText(getString(R.string.percent_format, pct));
            binding.level.setText(getString(R.string.level_format, curBrightness, maxBrightness));
        } else {
            binding.status.setText(R.string.status_off);
            binding.percent.setText(R.string.status_off);
            binding.level.setText(getString(R.string.level_off_format,
                    Brightness.toPercent(saved, maxBrightness)));
        }

        binding.status.setTextColor(boost ? getColor(R.color.warning)
                : on ? accent : getColor(R.color.text_secondary));
        binding.boostWarning.setVisibility(boost ? View.VISIBLE : View.GONE);
        binding.glow.setState(on, ready ? (float) curBrightness / maxBrightness : 0f, boost);
        binding.glow.setEnabled(ready);

        binding.brightness.setEnabled(ready);
        binding.dimmer.setEnabled(ready);
        binding.brighter.setEnabled(ready);
        binding.dimmer.setAlpha(ready ? 1f : 0.4f);
        binding.brighter.setAlpha(ready ? 1f : 0.4f);

        renderPresets();
    }

    @Override
    public void onTorchStateChanged(int curBrightness, int maxBrightness) {
        this.curBrightness = curBrightness;
        this.maxBrightness = maxBrightness;

        if (maxBrightness > 0) {
            if (initialUpdate) {
                binding.brightness.setMin(1);
                binding.brightness.setMax(maxBrightness);
                initialUpdate = false;
            }

            if (!binding.brightness.isPressed()) {
                binding.brightness.setProgress(curBrightness > 0
                        ? curBrightness : prefs.getBrightness(maxBrightness));
            }
        }

        render();
    }

    @Override
    public void onTorchError(@NonNull TorchError error) {
        // Retry if async ordering didn't allow onRequestPermissionsResult() to do the refresh.
        if (error == TorchError.NO_PERMISSION && Permissions.haveRequired(this)) {
            refreshCameras();
            return;
        }

        // Show the reason inline instead of failing silently.
        binding.status.setText(error.toUiString());
        binding.status.setTextColor(getColor(R.color.warning));
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (seekBar == binding.brightness && fromUser && torchBinder != null && progress > 0) {
            // Don't write preferences on every tick; persist once the user lets go. Dragging
            // the slider while off turns the torch on.
            torchBinder.setTorchBrightness(progress, false);
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        haptic(seekBar, HapticFeedbackConstants.GESTURE_START);
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if (seekBar == binding.brightness && torchBinder != null && seekBar.getProgress() > 0) {
            torchBinder.setTorchBrightness(seekBar.getProgress(), true);
        }
        haptic(seekBar, HapticFeedbackConstants.GESTURE_END);
    }
}
