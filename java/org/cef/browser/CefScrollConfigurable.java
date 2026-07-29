// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

package org.cef.browser;

/**
 * Implemented by browsers whose mouse-wheel scroll step can be tuned at
 * runtime. Off-screen browsers forward a raw, unscaled wheel delta to the
 * native layer; this interface exposes the pixels-per-notch factor so the host
 * application can match the platform feel or drive it from a UI control.
 */
public interface CefScrollConfigurable {
    /**
     * Sets the vertical scroll amount, in pixels, applied per wheel notch.
     * Values are clamped to a sane minimum of 1.
     * @param pixelsPerNotch pixels scrolled for one full wheel notch.
     */
    void setScrollPixelsPerNotch(int pixelsPerNotch);

    /**
     * @return the current pixels-per-notch scroll factor.
     */
    int getScrollPixelsPerNotch();
}
