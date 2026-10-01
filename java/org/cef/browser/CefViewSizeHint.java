// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

package org.cef.browser;

/**
 * Implemented by off-screen browsers whose view size normally comes from the
 * layout of their Swing component. A browser created before its component is
 * laid out (e.g. a pre-warmed browser) would otherwise be born with a 1x1 view
 * and lay its first page out at that size.
 */
public interface CefViewSizeHint {
    /**
     * Sets the view size, in logical pixels, reported to CEF until the UI
     * component receives a real size. Ignored once the component has one.
     * @param width  expected view width.
     * @param height expected view height.
     */
    void setViewSizeHint(int width, int height);
}
