// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef;

import java.awt.Color;

/**
 * Browser initialization settings. Specify NULL or 0 to get the recommended
 * default values. The consequences of using custom values may not be well
 * tested. Many of these and other settings can also configured using command-
 * line switches.
 */
public class CefBrowserSettings {
    /**
     * The maximum rate in frames per second (fps) that CefRenderHandler::OnPaint
     * will be called for a windowless browser. The actual fps may be lower if
     * the browser cannot generate frames at the requested rate. The minimum
     * value is 1 and the maximum value is 60 (default 30). This value can also
     * be changed dynamically via {@code CefBrowser#setWindowlessFrameRate}
     */
    public int windowless_frame_rate = 0;

    /**
     * Orion fork addition. See MODIFICATIONS.md.
     * Background color painted by Chromium before the page draws and in areas
     * newly exposed by a resize. The alpha component is ignored for
     * non-transparent browsers. Leave null for the default (white). Matching it
     * to the embedder's theme avoids white flashes while the browser resizes.
     */
    public Color background_color = null;

    public CefBrowserSettings() {}

    @Override
    public CefBrowserSettings clone() {
        CefBrowserSettings tmp = new CefBrowserSettings();
        tmp.windowless_frame_rate = windowless_frame_rate;
        tmp.background_color = background_color;
        return tmp;
    }
}
