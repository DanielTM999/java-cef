// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

package tests.junittests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.cef.CefApp;
import org.cef.CefSettings;
import org.cef.CefSettings.CefInitializationMode;
import org.cef.OS;
import org.junit.jupiter.api.Test;

/**
 * Pure-Java unit tests for the platform-aware initialization-mode resolution
 * (no native code required).
 *
 * <p>Covers acceptance scenarios: default is legacy, macOS forces legacy (13),
 * Windows/Linux honor the dedicated mode (14, 15), and the resolution is
 * centralized (24).
 */
class CefInitializationModeTest {
    @Test
    void nullSettingsDefaultsToLegacy() {
        assertEquals(CefInitializationMode.LEGACY_EDT, CefApp.resolveInitializationMode(null));
    }

    @Test
    void defaultSettingsAreLegacy() {
        CefSettings settings = new CefSettings();
        assertEquals(CefInitializationMode.LEGACY_EDT, settings.initialization_mode);
        assertEquals(CefInitializationMode.LEGACY_EDT,
                CefApp.resolveInitializationMode(settings));
    }

    @Test
    void dedicatedIsHonoredOnWindowsAndLinuxButNotElsewhere() {
        CefSettings settings = new CefSettings();
        settings.initialization_mode = CefInitializationMode.DEDICATED_CEF_THREAD;

        CefInitializationMode resolved = CefApp.resolveInitializationMode(settings);
        if (OS.isWindows() || OS.isLinux()) {
            // Windows/Linux: dedicated mode is honored (scenarios 14, 15).
            assertEquals(CefInitializationMode.DEDICATED_CEF_THREAD, resolved);
        } else {
            // macOS/unsupported: forced back to legacy (scenario 13).
            assertEquals(CefInitializationMode.LEGACY_EDT, resolved);
        }
    }

    @Test
    void modeSurvivesSettingsClone() {
        CefSettings settings = new CefSettings();
        settings.initialization_mode = CefInitializationMode.DEDICATED_CEF_THREAD;
        CefSettings clone = settings.clone();
        assertEquals(CefInitializationMode.DEDICATED_CEF_THREAD, clone.initialization_mode);
    }

    @Test
    void macosAlwaysResolvesToLegacy() {
        // Independent of the requested mode, a non-Windows/Linux platform must
        // never end up in dedicated mode. On CI this asserts the local platform
        // behavior; the branch above documents the macOS contract explicitly.
        CefSettings settings = new CefSettings();
        settings.initialization_mode = CefInitializationMode.DEDICATED_CEF_THREAD;
        CefInitializationMode resolved = CefApp.resolveInitializationMode(settings);
        assertNotNull(resolved);
        if (!OS.isWindows() && !OS.isLinux()) {
            assertEquals(CefInitializationMode.LEGACY_EDT, resolved);
        }
    }
}
