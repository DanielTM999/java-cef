// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

#include "process_priority.h"

#include <windows.h>

#include <tlhelp32.h>

#include <algorithm>
#include <cwctype>
#include <mutex>
#include <string>

namespace process_priority {

namespace {

std::mutex g_lock;
std::wstring g_subprocess_name;

std::wstring ToLower(std::wstring value) {
  std::transform(value.begin(), value.end(), value.begin(),
                 [](wchar_t c) { return static_cast<wchar_t>(std::towlower(c)); });
  return value;
}

std::wstring FileName(const std::wstring& path) {
  const size_t separator = path.find_last_of(L"\\/");
  return separator == std::wstring::npos ? path : path.substr(separator + 1);
}

bool ApplyTo(DWORD pid, bool background) {
  HANDLE process = OpenProcess(PROCESS_SET_INFORMATION, FALSE, pid);
  if (!process)
    return false;
  const bool priority =
      SetPriorityClass(process, background ? BELOW_NORMAL_PRIORITY_CLASS
                                           : NORMAL_PRIORITY_CLASS) != FALSE;
  MEMORY_PRIORITY_INFORMATION memory = {};
  memory.MemoryPriority =
      background ? MEMORY_PRIORITY_LOW : MEMORY_PRIORITY_NORMAL;
  const bool paging = SetProcessInformation(process, ProcessMemoryPriority,
                                            &memory, sizeof(memory)) != FALSE;
  CloseHandle(process);
  return priority || paging;
}

}  // namespace

void Configure(const CefString& browser_subprocess_path) {
  std::lock_guard<std::mutex> guard(g_lock);
  g_subprocess_name =
      ToLower(FileName(browser_subprocess_path.ToWString()));
}

int ApplyToSubprocesses(bool background) {
  std::wstring subprocess_name;
  {
    std::lock_guard<std::mutex> guard(g_lock);
    subprocess_name = g_subprocess_name;
  }
  if (subprocess_name.empty())
    return 0;

  HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if (snapshot == INVALID_HANDLE_VALUE)
    return -1;

  const DWORD self = GetCurrentProcessId();
  int applied = 0;
  PROCESSENTRY32W entry = {};
  entry.dwSize = sizeof(entry);
  for (BOOL more = Process32FirstW(snapshot, &entry); more;
       more = Process32NextW(snapshot, &entry)) {
    if (entry.th32ParentProcessID != self ||
        ToLower(entry.szExeFile) != subprocess_name) {
      continue;
    }
    if (ApplyTo(entry.th32ProcessID, background))
      ++applied;
  }
  CloseHandle(snapshot);
  return applied;
}

}  // namespace process_priority
