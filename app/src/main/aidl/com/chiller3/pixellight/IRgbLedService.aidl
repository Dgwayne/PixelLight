// SPDX-License-Identifier: GPL-3.0-only
package com.chiller3.pixellight;

/**
 * Runs inside a Shizuku user service (shell UID) so it can use the CONTROL_DEVICE_LIGHTS
 * permission to drive the Pixel 11 Pro "HiLight" RGB LED ring around the camera flash.
 */
interface IRgbLedService {
    /** Reserved transaction code used by Shizuku to destroy user services. */
    void destroy() = 16777114;

    /** Number of RGB LEDs found, or 0 if this device has no HiLight-style array. */
    int ledCount() = 1;

    /** Show one opaque RGB color on every LED. Returns false if the lights service refused. */
    boolean setColor(int rgb) = 2;

    /** Turn the LEDs off and hand them back to the system. */
    void clear() = 3;

    /** Debug description of the detected lights. */
    String describe() = 4;
}
