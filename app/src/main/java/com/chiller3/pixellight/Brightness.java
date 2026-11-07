/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.pixellight;

/** Helpers for converting between raw torch levels and user-facing percentages. */
public final class Brightness {
    private Brightness() {}

    public static int toPercent(int level, int max) {
        if (max <= 0 || level <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(100.0f * level / max));
    }

    public static int fromFraction(float fraction, int max) {
        return Math.max(1, Math.min(max, Math.round(max * fraction)));
    }

    /** Levels above half of the maximum exceed what the stock torch allows and run hot. */
    public static boolean isBoost(int level, int max) {
        return max > 0 && level > max / 2;
    }
}
