/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.pixellight;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.chiller3.pixellight.databinding.ScreenLightActivityBinding;

/**
 * Full-screen colored light using the display. Useful as a soft fill light, a night-vision red
 * light, or a signal light, and works even when the camera flash is busy or too hot.
 */
public class ScreenLightActivity extends Activity {
    private static final int MODE_STEADY = 0;
    private static final int MODE_RAINBOW = 1;
    private static final int MODE_STROBE = 2;
    private static final int MODE_SOS = 3;

    private static final long CONTROLS_TIMEOUT_MS = 5000;
    private static final long RAINBOW_TICK_MS = 40;
    private static final long STROBE_HALF_PERIOD_MS = 100;
    private static final long SOS_UNIT_MS = 200;
    // On/off durations in SOS units: S (···), O (———), S (···), then a word gap.
    private static final int[] SOS_PATTERN = {
            1, 1, 1, 1, 1, 3,
            3, 1, 3, 1, 3, 3,
            1, 1, 1, 1, 1, 7,
    };

    @ColorInt
    private static final int[] COLORS = {
            Color.rgb(0xFF, 0xFF, 0xFF),
            Color.rgb(0xFF, 0xE4, 0xC4),
            Color.rgb(0xFF, 0xB8, 0x6C),
            Color.rgb(0xFF, 0xBF, 0x00),
            Color.rgb(0xFF, 0x12, 0x12),
            Color.rgb(0x3D, 0xFF, 0x6E),
            Color.rgb(0x34, 0xE6, 0xFF),
            Color.rgb(0x2F, 0x6B, 0xFF),
            Color.rgb(0xA8, 0x4D, 0xFF),
            Color.rgb(0xFF, 0x4F, 0xC8),
    };
    @StringRes
    private static final int[] COLOR_NAMES = {
            R.string.color_white, R.string.color_warm, R.string.color_candle,
            R.string.color_amber, R.string.color_red, R.string.color_green,
            R.string.color_cyan, R.string.color_blue, R.string.color_purple,
            R.string.color_pink,
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideControlsRunnable = () -> setControlsVisible(false);
    private final Runnable hideHintRunnable = this::hideHint;
    private final Runnable effectRunnable = this::effectTick;

    private ScreenLightActivityBinding binding;
    private Preferences prefs;
    @ColorInt private int color;
    private int mode = MODE_STEADY;
    private boolean controlsVisible = true;
    private float rainbowHue = 0f;
    private boolean effectLit = true;
    private int sosIndex = 0;
    private TextView[] modeViews;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ScreenLightActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = new Preferences(this);

        final var window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final var controller = window.getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        final int baseMargin = dp(12);
        binding.light.setOnApplyWindowInsetsListener((v, insets) -> {
            final var bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            final var params = (android.widget.FrameLayout.LayoutParams)
                    binding.controls.getLayoutParams();
            params.bottomMargin = baseMargin + bars.bottom;
            binding.controls.setLayoutParams(params);
            return insets;
        });

        binding.light.setOnClickListener(v -> setControlsVisible(!controlsVisible));
        binding.close.setOnClickListener(v -> finish());

        buildSwatches();
        setupHue();
        setupBrightness();
        setupModes();

        color = prefs.getScreenColor(COLORS[1]);
        // Never start in a flashing mode.
        final int savedMode = prefs.getScreenMode();
        mode = savedMode == MODE_RAINBOW ? MODE_RAINBOW : MODE_STEADY;

        applyColor(color, findColorName(color));
        applyMode(mode);
        scheduleHideControls();
        handler.postDelayed(hideHintRunnable, 3000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        restartEffect();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(effectRunnable);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void haptic(@NonNull View view) {
        if (prefs.getHaptics()) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    // Controls

    private void hideHint() {
        binding.hint.animate().alpha(0f).setDuration(400);
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        handler.removeCallbacks(hideControlsRunnable);

        final var controls = binding.controls;
        if (visible) {
            controls.setVisibility(View.VISIBLE);
            controls.animate().alpha(1f).translationY(0f).setDuration(200).withEndAction(null);
            scheduleHideControls();
        } else {
            controls.animate().alpha(0f).translationY(dp(24)).setDuration(200)
                    .withEndAction(() -> {
                        if (!controlsVisible) {
                            controls.setVisibility(View.INVISIBLE);
                        }
                    });
        }
    }

    private void scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable);
        handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS);
    }

    private void onInteraction() {
        scheduleHideControls();
    }

    // Colors

    private void buildSwatches() {
        final var row = binding.swatches;
        row.removeAllViews();

        for (int i = 0; i < COLORS.length; ++i) {
            final int c = COLORS[i];
            final int name = COLOR_NAMES[i];
            final var swatch = new View(this);
            final var params = new LinearLayout.LayoutParams(dp(48), dp(48));
            params.setMarginEnd(dp(10));
            swatch.setLayoutParams(params);
            swatch.setContentDescription(getString(name));
            swatch.setTag(c);
            swatch.setOnClickListener(v -> {
                haptic(v);
                onInteraction();
                if (mode == MODE_RAINBOW) {
                    applyMode(MODE_STEADY);
                }
                applyColor(c, name);
            });
            row.addView(swatch);
        }
    }

    private void renderSwatches() {
        final var row = binding.swatches;
        for (int i = 0; i < row.getChildCount(); ++i) {
            final var swatch = row.getChildAt(i);
            final int c = (int) swatch.getTag();
            final var shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(c);
            if (c == color && mode != MODE_RAINBOW) {
                shape.setStroke(dp(3), Color.WHITE);
            } else {
                shape.setStroke(dp(1), 0x40FFFFFF);
            }
            swatch.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x33000000), shape, null));
        }
    }

    @StringRes
    private static int findColorName(@ColorInt int c) {
        for (int i = 0; i < COLORS.length; ++i) {
            if (COLORS[i] == c) {
                return COLOR_NAMES[i];
            }
        }
        return 0;
    }

    private void applyColor(@ColorInt int c, @StringRes int name) {
        color = c;
        prefs.setScreenColor(c);

        final float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        binding.hue.setProgress(Math.round(hsv[0]));

        if (name != 0) {
            binding.colorName.setText(name);
        } else {
            binding.colorName.setText(R.string.screen_light_title);
        }

        renderSwatches();
        renderLight();
    }

    private void renderLight() {
        final int shown;
        if (mode == MODE_RAINBOW) {
            shown = Color.HSVToColor(new float[] {rainbowHue, 1f, 1f});
        } else if (mode == MODE_STROBE || mode == MODE_SOS) {
            shown = effectLit ? color : Color.BLACK;
        } else {
            shown = color;
        }
        binding.light.setBackgroundColor(shown);
    }

    private void setupHue() {
        final var rainbow = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {
                        Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                        Color.BLUE, Color.MAGENTA, Color.RED,
                });
        rainbow.setCornerRadius(dp(6));
        binding.hue.setProgressDrawable(rainbow);
        binding.hue.setMinHeight(dp(12));
        binding.hue.setMaxHeight(dp(12));
        binding.hue.setSplitTrack(false);
        binding.hue.setThumbTintList(ColorStateList.valueOf(Color.WHITE));

        final float[] hsv = new float[3];
        Color.colorToHSV(prefs.getScreenColor(COLORS[1]), hsv);
        binding.hue.setProgress(Math.round(hsv[0]));

        binding.hue.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    onInteraction();
                    if (mode == MODE_RAINBOW) {
                        applyMode(MODE_STEADY);
                    }
                    applyColor(Color.HSVToColor(new float[] {progress, 1f, 1f}), 0);
                }
            }
        });
    }

    private void setupBrightness() {
        final var accent = ColorStateList.valueOf(Accents.current(this));
        binding.screenBrightness.setProgressTintList(accent);
        binding.screenBrightness.setThumbTintList(accent);
        binding.screenBrightness.setProgressBackgroundTintList(
                ColorStateList.valueOf(getColor(R.color.outline)));
        binding.screenBrightness.setMinHeight(dp(6));
        binding.screenBrightness.setMaxHeight(dp(6));
        binding.screenBrightness.setProgress(100);
        setWindowBrightness(1f);

        binding.screenBrightness.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    onInteraction();
                    setWindowBrightness(progress / 100f);
                }
            }
        });
    }

    private void setWindowBrightness(float value) {
        final var attrs = getWindow().getAttributes();
        attrs.screenBrightness = Math.max(0.01f, Math.min(1f, value));
        getWindow().setAttributes(attrs);
    }

    // Modes

    private void setupModes() {
        modeViews = new TextView[] {
                binding.modeSteady, binding.modeRainbow, binding.modeStrobe, binding.modeSos,
        };
        for (int i = 0; i < modeViews.length; ++i) {
            final int m = i;
            modeViews[i].setOnClickListener(v -> {
                haptic(v);
                onInteraction();
                applyMode(m);
            });
        }
    }

    private void applyMode(int newMode) {
        mode = newMode;
        prefs.setScreenMode(newMode);

        final int accent = Accents.current(this);
        for (int i = 0; i < modeViews.length; ++i) {
            final boolean active = i == mode;
            final var chip = new GradientDrawable();
            chip.setCornerRadius(dp(22));
            chip.setColor(active ? accent : getColor(R.color.surface_high));
            modeViews[i].setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF), chip, null));
            modeViews[i].setTextColor(active
                    ? Accents.onColor(accent) : getColor(R.color.text_primary));
        }

        binding.flashWarning.setVisibility(
                mode == MODE_STROBE || mode == MODE_SOS ? View.VISIBLE : View.GONE);

        if (mode == MODE_RAINBOW) {
            final float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            rainbowHue = hsv[0];
            binding.colorName.setText(R.string.mode_rainbow);
        } else {
            final int name = findColorName(color);
            binding.colorName.setText(name != 0 ? name : R.string.screen_light_title);
        }

        renderSwatches();
        restartEffect();
    }

    private void restartEffect() {
        handler.removeCallbacks(effectRunnable);
        effectLit = true;
        sosIndex = 0;
        renderLight();

        switch (mode) {
            case MODE_RAINBOW -> handler.postDelayed(effectRunnable, RAINBOW_TICK_MS);
            case MODE_STROBE -> handler.postDelayed(effectRunnable, STROBE_HALF_PERIOD_MS);
            case MODE_SOS -> handler.postDelayed(effectRunnable, SOS_PATTERN[0] * SOS_UNIT_MS);
            default -> {}
        }
    }

    private void effectTick() {
        long next;
        switch (mode) {
            case MODE_RAINBOW -> {
                rainbowHue = (rainbowHue + 1.5f) % 360f;
                next = RAINBOW_TICK_MS;
            }
            case MODE_STROBE -> {
                effectLit = !effectLit;
                next = STROBE_HALF_PERIOD_MS;
            }
            case MODE_SOS -> {
                // Even indices are "on" segments and odd indices are "off" gaps.
                sosIndex = (sosIndex + 1) % SOS_PATTERN.length;
                effectLit = sosIndex % 2 == 0;
                next = SOS_PATTERN[sosIndex] * SOS_UNIT_MS;
            }
            default -> {
                return;
            }
        }

        renderLight();
        handler.postDelayed(effectRunnable, next);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
