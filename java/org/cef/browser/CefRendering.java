// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

package org.cef.browser;

/**
 * Selects how a {@link CefBrowser} renders its content.
 */
public enum CefRendering {
    /**
     * Windowed rendering backed by a native, heavyweight AWT window
     * ({@code CefBrowserWr}). Fastest, but the heavyweight peer does not
     * participate in Swing double-buffering and flickers when sibling Swing
     * components relayout or repaint.
     */
    WINDOWED,

    /**
     * Off-screen rendering drawn by an OpenGL {@code GLCanvas}
     * ({@code CefBrowserOsr}). Still a heavyweight AWT component.
     */
    OFFSCREEN,

    /**
     * Off-screen rendering painted into a lightweight Swing component via a
     * software {@code BufferedImage} ({@code CefBrowserOsrBuffered}). Composes
     * without flicker inside tabs, split panes and overlapping Swing content.
     */
    OFFSCREEN_BUFFERED;

    /**
     * Maps the legacy {@code isOffscreenRendered} boolean to a rendering mode.
     */
    public static CefRendering fromLegacy(boolean isOffscreenRendered) {
        return isOffscreenRendered ? OFFSCREEN : WINDOWED;
    }
}
