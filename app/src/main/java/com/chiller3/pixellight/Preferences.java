/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.pixellight;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

public class Preferences {
    private static final String PREF_BRIGHTNESS = "brightness";
    private static final String PREF_KEEP_SERVICE_ALIVE = "keep_service_alive";
    private static final String PREF_THERMAL_PROTECTION = "thermal_protection";
    private static final String PREF_BOOST_TIMEOUT = "boost_timeout";
    private static final String PREF_ACCENT = "accent";
    private static final String PREF_SCREEN_COLOR = "screen_color";
    private static final String PREF_SCREEN_MODE = "screen_mode";
    private static final String PREF_HAPTICS = "haptics";
    private static final String PREF_VOLUME_KEYS = "volume_keys";

    private final SharedPreferences prefs;

    public Preferences(@NonNull Context context) {
        prefs = context.getSharedPreferences(
                context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    public int getBrightness(int defaultValue) {
        try {
            return prefs.getInt(PREF_BRIGHTNESS, defaultValue);
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    public void setBrightness(int value) {
        prefs.edit().putInt(PREF_BRIGHTNESS, value).apply();
    }

    public boolean getKeepServiceAlive() {
        return prefs.getBoolean(PREF_KEEP_SERVICE_ALIVE, false);
    }

    public void setKeepServiceAlive(boolean keep) {
        prefs.edit().putBoolean(PREF_KEEP_SERVICE_ALIVE, keep).apply();
    }

    public boolean getThermalProtection() {
        return prefs.getBoolean(PREF_THERMAL_PROTECTION, true);
    }

    public void setThermalProtection(boolean enabled) {
        prefs.edit().putBoolean(PREF_THERMAL_PROTECTION, enabled).apply();
    }

    public boolean getBoostTimeout() {
        return prefs.getBoolean(PREF_BOOST_TIMEOUT, true);
    }

    public void setBoostTimeout(boolean enabled) {
        prefs.edit().putBoolean(PREF_BOOST_TIMEOUT, enabled).apply();
    }

    public int getAccent() {
        return prefs.getInt(PREF_ACCENT, 0);
    }

    public void setAccent(int index) {
        prefs.edit().putInt(PREF_ACCENT, index).apply();
    }

    public int getScreenColor(int defaultColor) {
        return prefs.getInt(PREF_SCREEN_COLOR, defaultColor);
    }

    public void setScreenColor(int color) {
        prefs.edit().putInt(PREF_SCREEN_COLOR, color).apply();
    }

    public int getScreenMode() {
        return prefs.getInt(PREF_SCREEN_MODE, 0);
    }

    public void setScreenMode(int mode) {
        prefs.edit().putInt(PREF_SCREEN_MODE, mode).apply();
    }

    public boolean getHaptics() {
        return prefs.getBoolean(PREF_HAPTICS, true);
    }

    public void setHaptics(boolean enabled) {
        prefs.edit().putBoolean(PREF_HAPTICS, enabled).apply();
    }

    public boolean getVolumeKeys() {
        return prefs.getBoolean(PREF_VOLUME_KEYS, true);
    }

    public void setVolumeKeys(boolean enabled) {
        prefs.edit().putBoolean(PREF_VOLUME_KEYS, enabled).apply();
    }
}
