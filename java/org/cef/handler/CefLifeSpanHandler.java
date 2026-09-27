// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

package org.cef.handler;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;

/**
 * Implement this interface to handle events related to browser life span. The methods of this class
 * will be called on the UI thread unless otherwise indicated.
 */
public interface CefLifeSpanHandler {
    /**
     * Called on the IO thread before a new popup window is created.
     * @param browser The source of the popup request.
     * @param frame The source of the popup request. Instance only valid within the scope of this
     *         method.
     * @param target_url May be empty if none is specified with the request.
     * @param target_frame_name May be empty if none is specified with the request.
     * @return True to cancel creation of the popup window or false to proceed.
     */
    boolean onBeforePopup(
            CefBrowser browser, CefFrame frame, String target_url, String target_frame_name);

    /**
     * Orion fork: called on the UI thread before a popup is created for an off-screen rendered
     * browser. Return a browser created with {@link org.cef.CefClient#createPopupBrowser} to host
     * the popup: the native popup (which keeps its {@code window.opener} relationship) is then
     * adopted by that Java browser and reported through its client's {@link #onAfterCreated}.
     * Return null to fall back to {@link #onBeforePopup}, in which case off-screen popups are
     * cancelled. Must not block.
     * @param browser The source of the popup request.
     * @param frame The source of the popup request. Instance only valid within the scope of this
     *         method.
     * @param target_url May be empty if none is specified with the request.
     * @param target_frame_name May be empty if none is specified with the request.
     * @return The browser that adopts the popup, or null.
     */
    default CefBrowser onBeforePopupBrowser(
            CefBrowser browser, CefFrame frame, String target_url, String target_frame_name) {
        return null;
    }

    /**
     * Orion fork: called when a popup returned from {@link #onBeforePopupBrowser} could not be
     * created. The popup browser will never be created and should be discarded. Called on the
     * popup browser's client.
     * @param popup The browser that was supposed to adopt the popup.
     */
    default void onPopupBrowserAborted(CefBrowser popup) {}

    /**
     * Handle creation of a new browser window.
     * @param browser The browser generating the event.
     */
    void onAfterCreated(CefBrowser browser);

    /**
     * Called after a browser's native parent window has changed.
     * @param browser The browser generating the event.
     */
    void onAfterParentChanged(CefBrowser browser);

    /**
     * Called when a browser has received a request to close.
     *
     * If CEF created an OS window for the browser returning false will send an OS close
     * notification to the browser window's top-level owner (e.g. WM_CLOSE on Windows, performClose:
     * on OS-X and "delete_event" on Linux). If no OS window exists (window rendering disabled)
     * returning false will cause the browser object to be destroyed immediately. Return true if the
     * browser is parented to another window and that other window needs to receive close
     * notification via some non-standard technique.
     *
     * @param browser The browser generating the event.
     * @return False to send an OS close notification to the browser window's top-level owner.
     */
    boolean doClose(CefBrowser browser);

    /**
     * Called just before a browser is destroyed.
     *
     * Release all references to the browser object and do not attempt to execute any methods on the
     * browser object after this callback returns. If this is a modal window and a custom modal loop
     * implementation was provided in runModal() this callback should be used to exit the custom
     * modal loop. See doClose() documentation for additional usage information.
     *
     * @param browser The browser generating the event.
     */
    void onBeforeClose(CefBrowser browser);
}
