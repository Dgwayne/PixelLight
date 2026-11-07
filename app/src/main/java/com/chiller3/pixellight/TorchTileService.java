/*
 * SPDX-FileCopyrightText: 2024-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.pixellight;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import androidx.annotation.NonNull;

/** Quick settings tile for toggling the torch status. The last selected brightness is used. */
public class TorchTileService extends TileService implements ServiceConnection, TorchSession.Listener {
    private static final String TAG = TorchTileService.class.getSimpleName();

    private TorchService.TorchBinder torchBinder;
    private boolean bound = false;
    private int curBrightness = -1;
    private int maxBrightness = -1;

    @Override
    public void onStartListening() {
        super.onStartListening();
        Log.d(TAG, "Tile is listening");

        if (!bound) {
            final var intent = new Intent(this, TorchService.class);
            try {
                bound = bindService(intent, this, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to bind torch service", e);
            }
        }

        refreshTileState();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        Log.d(TAG, "Tile is no longer listening");

        onBinderGone();
        if (bound) {
            bound = false;
            try {
                unbindService(this);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Service was not bound", e);
            }
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        torchBinder = (TorchService.TorchBinder) service;
        torchBinder.registerTorchListener(this);
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
        curBrightness = -1;
        maxBrightness = -1;
    }

    private void launchCollapsed(@NonNull Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivityAndCollapse(PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to launch activity from tile", e);
        }
    }

    @Override
    public void onClick() {
        super.onClick();

        if (!Permissions.haveRequired(this)) {
            launchCollapsed(new Intent(this, MainActivity.class));
            return;
        }

        // With Android 15, it is no longer possible to start a foreground service that relies on
        // while-in-use permissions, regardless if that's another service or the TileService itself.
        // We have no choice but to provide a worse experience and perform the operation through an
        // activity.

        final int newBrightness;

        if (curBrightness == -1 || torchBinder == null) {
            // The service hasn't connected yet. Previously, this silently did nothing. Let the
            // toggle activity figure out the current state instead.
            Log.w(TAG, "onClick reached before service was ready; toggling via activity");
            launchCollapsed(ToggleActivity.createIntent(this, TorchSession.BRIGHTNESS_TOGGLE));
            return;
        } else if (curBrightness == 0) {
            Log.d(TAG, "Toggling torch on");
            newBrightness = TorchSession.BRIGHTNESS_PERSISTED;
        } else {
            Log.d(TAG, "Toggling torch off");
            newBrightness = 0;
        }

        if (torchBinder.isInForeground() || newBrightness == 0) {
            // With Android 15, we can't start a camera foreground service from a tile service
            // anymore, but we can connect to a previously started instance just fine. Turning off
            // never needs the foreground.
            torchBinder.setTorchBrightness(newBrightness);
        } else {
            launchCollapsed(ToggleActivity.createIntent(this, newBrightness));
        }

        // The tile state will be changed when onTorchStateChanged() is called.
    }

    private void refreshTileState() {
        final var tile = getQsTile();
        if (tile == null) {
            Log.w(TAG, "Tile was null during refreshTileState");
            return;
        }

        if (curBrightness < 0) {
            // Keep the tile clickable: onClick() can handle the not-yet-connected case.
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(null);
        } else if (curBrightness == 0) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(getString(R.string.tile_subtitle_off));
        } else {
            tile.setState(Tile.STATE_ACTIVE);
            if (maxBrightness > 0) {
                tile.setSubtitle(getString(R.string.percent_format,
                        Brightness.toPercent(curBrightness, maxBrightness)));
            } else {
                tile.setSubtitle(Integer.toString(curBrightness));
            }
        }

        tile.updateTile();
    }

    @Override
    public void onTorchStateChanged(int curBrightness, int maxBrightness) {
        Log.d(TAG, "New torch state: current=" + curBrightness + ", max=" + maxBrightness);
        this.curBrightness = curBrightness;
        this.maxBrightness = maxBrightness;
        refreshTileState();
    }

    @Override
    public void onTorchError(@NonNull TorchError error) {
        if (error == TorchError.NO_PERMISSION) {
            launchCollapsed(new Intent(this, MainActivity.class));
        }
    }
}
