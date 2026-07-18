// Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

package org.cef;

/**
 * Thrown when the global CEF context fails to pre-initialize or initialize.
 *
 * <p>Native failures are frequently reported as a plain boolean return value
 * with no exception attached; this type wraps such failures into a clear Java
 * exception that captures the operation, the owner thread, the initialization
 * mode, the OS/arch and the {@link CefApp.CefAppState} at the point of failure.
 */
public class CefInitializationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CefInitializationException(String message) {
        super(message);
    }

    public CefInitializationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Build a richly-detailed message describing the failed native operation.
     *
     * @param operation the failed operation, e.g. "N_PreInitialize".
     * @param mode      the resolved initialization mode.
     * @param state     the CefApp state at the time of failure.
     */
    static CefInitializationException forOperation(String operation,
            CefSettings.CefInitializationMode mode, CefApp.CefAppState state) {
        String msg = "JCEF native initialization failed: " + operation + " returned false"
                + " [thread=" + Thread.currentThread().getName()
                + ", mode=" + mode
                + ", os=" + System.getProperty("os.name")
                + ", arch=" + System.getProperty("os.arch")
                + ", state=" + state + "]";
        return new CefInitializationException(msg);
    }
}
