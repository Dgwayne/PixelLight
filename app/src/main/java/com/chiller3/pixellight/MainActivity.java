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
import android.os.Bundle;
import android.os.IBinder;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.chiller3.pixellight.databinding.MainActivityBinding;

import java.util.Arrays;

public class MainActivity extends Activity implements ServiceConnection, TorchSession.Listener,
        SeekBar.OnSeekBarChangeListener, CompoundButton.OnCheckedChangeListener {
    private static final int REQUEST_PERMISSIONS = 1;

    private MainActivityBinding binding;
    private Preferences prefs;
    private TorchService.TorchBinder torchBinder;
    private boolean initialUpdate = true;
    private int maxBrightness = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = MainActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = new Preferences(this);

        binding.brightness.setEnabled(false);
        binding.brightness.setOnSeekBarChangeListener(this);

        binding.toggle.setEnabled(false);
        binding.toggle.setOnCheckedChangeListener(this);

        binding.presetLow.setOnClickListener(v -> applyPreset(0.10f));
        binding.presetMedium.setOnClickListener(v -> applyPreset(0.25f));
        binding.presetHigh.setOnClickListener(v -> applyPreset(0.50f));
        binding.presetBoost.setOnClickListener(v -> applyPreset(1.0f));
        setPresetsEnabled(false);

        binding.requestPermissions.setOnClickListener(v ->
            requestPermissions(Permissions.REQUIRED, REQUEST_PERMISSIONS));
    }

    private void setPresetsEnabled(boolean enabled) {
        binding.presetLow.setEnabled(enabled);
        binding.presetMedium.setEnabled(enabled);
        binding.presetHigh.setEnabled(enabled);
        binding.presetBoost.setEnabled(enabled);
    }

    private void applyPreset(float fraction) {
        if (torchBinder == null || maxBrightness <= 0) {
            return;
        }

        final int value = Math.max(1, Math.round(maxBrightness * fraction));
        torchBinder.setTorchBrightness(value, true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_activity_options, menu);

        menu.findItem(R.id.keep_service_alive).setChecked(prefs.getKeepServiceAlive());
        menu.findItem(R.id.thermal_protection).setChecked(prefs.getThermalProtection());
        menu.findItem(R.id.boost_timeout).setChecked(prefs.getBoostTimeout());

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        final int id = item.getItemId();

        if (id == R.id.keep_service_alive) {
            item.setChecked(!item.isChecked());
            prefs.setKeepServiceAlive(item.isChecked());

            if (torchBinder != null) {
                if (item.isChecked()) {
                    // Start the persistent service right away so the QS tile works without
                    // collapsing the panel, instead of waiting for the first toggle.
                    torchBinder.ensureAliveIfConfigured();
                } else {
                    // Try to shut down the service so that the user doesn't have to manually turn
                    // the torch on and off for the change to take effect.
                    torchBinder.tryStopService();
                }
            }

            return true;
        } else if (id == R.id.thermal_protection) {
            item.setChecked(!item.isChecked());
            prefs.setThermalProtection(item.isChecked());
            return true;
        } else if (id == R.id.boost_timeout) {
            item.setChecked(!item.isChecked());
            prefs.setBoostTimeout(item.isChecked());
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSIONS) {
            if (Arrays.stream(grantResults).allMatch(r -> r == PackageManager.PERMISSION_GRANTED)) {
                refreshUiGroups();
                refreshCameras();
                if (torchBinder != null) {
                    torchBinder.ensureAliveIfConfigured();
                }
            } else if (!Permissions.canRequestRequired(this)) {
                startActivity(Permissions.getAppInfoIntent(this));
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

        final var intent = new Intent(this, TorchService.class);
        bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();

        onBinderGone();
        unbindService(this);
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

    private void updateLabel(int curBrightness, int maxBrightness) {
        if (maxBrightness > 0 && curBrightness > 0) {
            final int pct = Math.round(100.0f * curBrightness / maxBrightness);
            binding.label.setText(pct + "%  (" + curBrightness + " / " + maxBrightness + ")");
        } else {
            binding.label.setText(curBrightness + " / " + maxBrightness);
        }

        final boolean boost = maxBrightness > 0 && curBrightness > maxBrightness / 2;
        binding.boostWarning.setVisibility(boost ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public void onTorchStateChanged(int curBrightness, int maxBrightness) {
        this.maxBrightness = maxBrightness;

        if (initialUpdate) {
            binding.brightness.setMin(1);
            binding.brightness.setMax(maxBrightness);
            binding.brightness.setProgress(prefs.getBrightness(maxBrightness));
            initialUpdate = false;
        }

        if (curBrightness == 0) {
            binding.brightness.setEnabled(false);
        } else {
            binding.brightness.setProgress(curBrightness);
            binding.brightness.setEnabled(true);
        }

        binding.toggle.setEnabled(true);
        binding.toggle.setChecked(curBrightness != 0);
        setPresetsEnabled(maxBrightness > 0);

        updateLabel(curBrightness, maxBrightness);
    }

    @Override
    public void onTorchError(@NonNull TorchError error) {
        // Retry if async ordering didn't allow onRequestPermissionsResult() to do the refresh.
        if (error == TorchError.NO_PERMISSION && Permissions.haveRequired(this)) {
            refreshCameras();
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (seekBar == binding.brightness && fromUser && torchBinder != null) {
            // Don't write preferences on every tick; persist once the user lets go.
            torchBinder.setTorchBrightness(progress, false);
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {}

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if (seekBar == binding.brightness && torchBinder != null) {
            torchBinder.setTorchBrightness(seekBar.getProgress(), true);
        }
    }

    @Override
    public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
        // On configuration change, this may be invoked before the service is bound.
        if (buttonView == binding.toggle && torchBinder != null) {
            if (isChecked) {
                torchBinder.setTorchBrightness(binding.brightness.getProgress());
            } else {
                torchBinder.setTorchBrightness(0);
            }
        }
    }
}
