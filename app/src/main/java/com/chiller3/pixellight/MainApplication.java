/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.pixellight;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.util.Log;

import java.util.Arrays;

public class MainApplication extends Application {
    private static final String TAG = MainApplication.class.getSimpleName();

    @Override
    public void onCreate() {
        super.onCreate();

        new Notifications(this).updateChannels();
        publishShortcuts();
    }

    /** Long-press launcher shortcuts. Done at runtime so they work with any applicationId suffix. */
    private void publishShortcuts() {
        final var manager = getSystemService(ShortcutManager.class);
        if (manager == null) {
            return;
        }

        final var toggleIntent = ToggleActivity.createIntent(this, TorchSession.BRIGHTNESS_TOGGLE)
                .setAction(Intent.ACTION_VIEW);
        final var toggle = new ShortcutInfo.Builder(this, "toggle")
                .setShortLabel(getString(R.string.shortcut_toggle_short))
                .setLongLabel(getString(R.string.shortcut_toggle_long))
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(toggleIntent)
                .setRank(0)
                .build();

        final var screenIntent = new Intent(this, ScreenLightActivity.class)
                .setAction(Intent.ACTION_VIEW);
        final var screen = new ShortcutInfo.Builder(this, "screen_light")
                .setShortLabel(getString(R.string.shortcut_screen_short))
                .setLongLabel(getString(R.string.shortcut_screen_long))
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(screenIntent)
                .setRank(1)
                .build();

        try {
            manager.setDynamicShortcuts(Arrays.asList(toggle, screen));
        } catch (RuntimeException e) {
            // Rate limiting or launcher restrictions must never crash app startup.
            Log.w(TAG, "Failed to publish shortcuts", e);
        }
    }
}
