// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

#ifndef JCEF_NATIVE_WINDOW_BRANDING_H_
#define JCEF_NATIVE_WINDOW_BRANDING_H_
#pragma once

#include <jni.h>

#include <string>

#include "include/cef_browser.h"

// Embedder-controlled branding for the native windows Chromium creates on its
// own (e.g. the DevTools window) and for the process taskbar identity, so that
// the embedding application never shows Chromium's icon or name. Values come
// from the org.cef.CefSettings app_* fields. Only implemented on Windows; the
// other platforms get no-ops.
namespace window_branding {

#if defined(OS_WIN)

// Reads app_icon_path / app_user_model_id from the Java
// CefSettings object and applies the process-wide AppUserModelID. Call before
// CefInitialize.
void Configure(JNIEnv* env, jobject jsettings);

// Re-applies the process-wide AppUserModelID. Chrome-style initialization may
// replace it with Chromium's, which regroups the embedder's own windows under a
// Chromium taskbar entry. Call on the UI thread once the context is ready.
void ReapplyProcessIdentity();

// Brands the top-level native window of |browser| if Chromium owns it (the
// embedder's AWT windows are left alone). Safe to call for any browser.
void ApplyToBrowser(CefRefPtr<CefBrowser> browser);

// Copies |source| to |target| replacing the icon and FileDescription resources.
// Empty |icon_path| keeps the current icon and empty |description| keeps the
// current description. Returns false on failure, leaving |target| untouched.
bool BrandExecutable(const std::wstring& source,
                     const std::wstring& target,
                     const std::wstring& icon_path,
                     const std::wstring& description);

#else

inline void Configure(JNIEnv*, jobject) {}
inline void ReapplyProcessIdentity() {}
inline void ApplyToBrowser(CefRefPtr<CefBrowser>) {}

#endif  // defined(OS_WIN)

}  // namespace window_branding

#endif  // JCEF_NATIVE_WINDOW_BRANDING_H_
