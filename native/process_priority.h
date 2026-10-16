// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

#ifndef JCEF_NATIVE_PROCESS_PRIORITY_H_
#define JCEF_NATIVE_PROCESS_PRIORITY_H_
#pragma once

#include "include/internal/cef_string.h"

namespace process_priority {

#if defined(OS_WIN)

void Configure(const CefString& browser_subprocess_path);

int ApplyToSubprocesses(bool background);

#else

inline void Configure(const CefString&) {}
inline int ApplyToSubprocesses(bool) {
  return -1;
}

#endif  // defined(OS_WIN)

}  // namespace process_priority

#endif  // JCEF_NATIVE_PROCESS_PRIORITY_H_
