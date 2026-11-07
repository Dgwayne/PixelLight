/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.pixellight;

import android.content.Context;
import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

/** User-selectable accent colors for the app UI. Index 0 follows the system (Material You). */
public final class Accents {
    private Accents() {}

    @ColorInt
    private static final int[] FIXED = {
            0,                          // Dynamic, resolved at runtime.
            Color.rgb(0xFF, 0xC1, 0x4D), // Amber
            Color.rgb(0x8A, 0xD7, 0xFF), // Ice
            Color.rgb(0xB8, 0xF0, 0x5A), // Lime
            Color.rgb(0xFF, 0x7A, 0x9C), // Rose
            Color.rgb(0xB6, 0x9C, 0xFF), // Violet
            Color.rgb(0xFF, 0x8A, 0x50), // Ember
            Color.rgb(0x5E, 0xEA, 0xD4), // Mint
    };

    public static int count() {
        return FIXED.length;
    }

    @ColorInt
    public static int get(@NonNull Context context, int index) {
        if (index <= 0 || index >= FIXED.length) {
            return context.getColor(android.R.color.system_accent1_200);
        }
        return FIXED[index];
    }

    @ColorInt
    public static int current(@NonNull Context context) {
        return get(context, new Preferences(context).getAccent());
    }

    /** Pick black or white text for legibility on top of the given color. */
    @ColorInt
    public static int onColor(@ColorInt int color) {
        final double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.6 ? Color.rgb(0x14, 0x14, 0x18) : Color.WHITE;
    }

    @ColorInt
    public static int withAlpha(@ColorInt int color, float alpha) {
        final int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
