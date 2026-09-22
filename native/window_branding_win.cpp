// Copyright (c) 2026 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.
//
// Orion fork addition. See MODIFICATIONS.md.

#include "window_branding.h"

#include <windows.h>

#include <objbase.h>
#include <propidl.h>
#include <shellapi.h>
#include <shobjidl.h>
#include <winver.h>

#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <utility>
#include <vector>

#include "include/base/cef_callback.h"
#include "include/cef_task.h"
#include "include/wrapper/cef_closure_task.h"

#include "jni_util.h"

#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "version.lib")

namespace window_branding {

namespace {

// PKEY_AppUserModel_* from propkey.h, defined locally so no GUID library is
// needed at link time.
const PROPERTYKEY kAppUserModelRelaunchCommand = {
    {0x9F4C2855, 0x9F79, 0x4B39, {0xA8, 0xD0, 0xE1, 0xD4, 0x2D, 0xE1, 0xD5, 0xF3}},
    2};
const PROPERTYKEY kAppUserModelRelaunchIconResource = {
    {0x9F4C2855, 0x9F79, 0x4B39, {0xA8, 0xD0, 0xE1, 0xD4, 0x2D, 0xE1, 0xD5, 0xF3}},
    3};
const PROPERTYKEY kAppUserModelRelaunchDisplayNameResource = {
    {0x9F4C2855, 0x9F79, 0x4B39, {0xA8, 0xD0, 0xE1, 0xD4, 0x2D, 0xE1, 0xD5, 0xF3}},
    4};
const PROPERTYKEY kAppUserModelId = {
    {0x9F4C2855, 0x9F79, 0x4B39, {0xA8, 0xD0, 0xE1, 0xD4, 0x2D, 0xE1, 0xD5, 0xF3}},
    5};

// Used when neither the embedder nor the process set an AppUserModelID but
// Chromium replaced it with its own.
const wchar_t kGenericAppId[] = L"JCEF.Application";

// Delays after which a Chromium-owned window is branded again: Chrome-style
// windows may refresh their icon while they finish initializing.
const int64_t kReapplyDelaysMs[] = {250, 1500};

std::wstring g_icon_path;
std::wstring g_app_id;
std::wstring g_initial_app_id;
HICON g_big_icon = nullptr;
HICON g_small_icon = nullptr;
bool g_icons_loaded = false;

std::wstring CurrentProcessAppId() {
  PWSTR id = nullptr;
  std::wstring result;
  if (SUCCEEDED(GetCurrentProcessExplicitAppUserModelID(&id)) && id) {
    result = id;
    CoTaskMemFree(id);
  }
  return result;
}

std::wstring EffectiveAppId() {
  if (!g_app_id.empty())
    return g_app_id;
  if (!g_initial_app_id.empty())
    return g_initial_app_id;
  return kGenericAppId;
}

void LoadIcons() {
  if (g_icons_loaded)
    return;
  g_icons_loaded = true;
  if (!g_icon_path.empty()) {
    g_big_icon = static_cast<HICON>(LoadImageW(
        nullptr, g_icon_path.c_str(), IMAGE_ICON, GetSystemMetrics(SM_CXICON),
        GetSystemMetrics(SM_CYICON), LR_LOADFROMFILE));
    g_small_icon = static_cast<HICON>(LoadImageW(
        nullptr, g_icon_path.c_str(), IMAGE_ICON, GetSystemMetrics(SM_CXSMICON),
        GetSystemMetrics(SM_CYSMICON), LR_LOADFROMFILE));
  }
  // Without an embedder icon fall back to the generic application icon rather
  // than Chromium's.
  if (!g_big_icon)
    g_big_icon = LoadIconW(nullptr, MAKEINTRESOURCEW(32512));  // IDI_APPLICATION
  if (!g_small_icon)
    g_small_icon = g_big_icon;
}

bool IsEmbedderWindow(HWND hwnd) {
  wchar_t class_name[64] = {0};
  if (!GetClassNameW(hwnd, class_name, ARRAYSIZE(class_name)))
    return true;
  // AWT top-level windows (SunAwtFrame, SunAwtDialog, ...) belong to the
  // embedder, which sets its own icon.
  return wcsncmp(class_name, L"SunAwt", 6) == 0;
}

void SetStringProperty(IPropertyStore* store,
                       const PROPERTYKEY& key,
                       const std::wstring& value) {
  PROPVARIANT pv;
  PropVariantInit(&pv);
  const size_t bytes = (value.size() + 1) * sizeof(wchar_t);
  pv.pwszVal = static_cast<LPWSTR>(CoTaskMemAlloc(bytes));
  if (!pv.pwszVal)
    return;
  memcpy(pv.pwszVal, value.c_str(), bytes);
  pv.vt = VT_LPWSTR;
  store->SetValue(key, pv);
  PropVariantClear(&pv);
}

void ClearProperty(IPropertyStore* store, const PROPERTYKEY& key) {
  PROPVARIANT pv;
  PropVariantInit(&pv);
  store->SetValue(key, pv);
}

void BrandWindow(HWND root) {
  LoadIcons();
  SendMessageW(root, WM_SETICON, ICON_BIG, reinterpret_cast<LPARAM>(g_big_icon));
  SendMessageW(root, WM_SETICON, ICON_SMALL,
               reinterpret_cast<LPARAM>(g_small_icon));

  // Group the window with the embedder on the taskbar and drop the relaunch
  // properties Chromium points at its own executable and icon.
  IPropertyStore* store = nullptr;
  if (FAILED(SHGetPropertyStoreForWindow(root, IID_PPV_ARGS(&store))) || !store)
    return;
  ClearProperty(store, kAppUserModelRelaunchCommand);
  ClearProperty(store, kAppUserModelRelaunchIconResource);
  ClearProperty(store, kAppUserModelRelaunchDisplayNameResource);
  SetStringProperty(store, kAppUserModelId, EffectiveAppId());
  store->Commit();
  store->Release();
}

void BrandBrowserWindow(CefRefPtr<CefBrowser> browser) {
  if (!browser || !browser->IsValid())
    return;
  HWND hwnd = browser->GetHost()->GetWindowHandle();
  if (!hwnd)
    return;
  HWND root = GetAncestor(hwnd, GA_ROOT);
  if (!root || IsEmbedderWindow(root))
    return;
  BrandWindow(root);
}

std::wstring ReadString(JNIEnv* env,
                        jclass cls,
                        jobject obj,
                        const char* field) {
  CefString value;
  if (!GetJNIFieldString(env, cls, obj, field, &value))
    return std::wstring();
  return value.ToWString();
}

// --- Executable resources -------------------------------------------------

#pragma pack(push, 2)
struct IconDirEntry {
  BYTE width;
  BYTE height;
  BYTE color_count;
  BYTE reserved;
  WORD planes;
  WORD bit_count;
  DWORD bytes_in_res;
  DWORD image_offset;
};
struct GroupIconDirEntry {
  BYTE width;
  BYTE height;
  BYTE color_count;
  BYTE reserved;
  WORD planes;
  WORD bit_count;
  DWORD bytes_in_res;
  WORD id;
};
struct IconDir {
  WORD reserved;
  WORD type;
  WORD count;
};
#pragma pack(pop)

// Explicit wide resource types: the RT_* macros are narrow unless UNICODE is
// defined for the translation unit.
LPCWSTR const kResourceIcon = MAKEINTRESOURCEW(3);        // RT_ICON
LPCWSTR const kResourceGroupIcon = MAKEINTRESOURCEW(14);  // RT_GROUP_ICON
LPCWSTR const kResourceVersion = MAKEINTRESOURCEW(16);    // RT_VERSION

const WORD kNeutralLanguage = MAKELANGID(LANG_NEUTRAL, SUBLANG_NEUTRAL);
const WORD kEnglishLanguage = MAKELANGID(LANG_ENGLISH, SUBLANG_ENGLISH_US);

bool ReadFileBytes(const std::wstring& path, std::vector<BYTE>* data) {
  std::ifstream in(path, std::ios::binary);
  if (!in)
    return false;
  data->assign(std::istreambuf_iterator<char>(in),
               std::istreambuf_iterator<char>());
  return !data->empty();
}

bool UpdateIcon(HANDLE update, const std::wstring& icon_path) {
  std::vector<BYTE> ico;
  if (!ReadFileBytes(icon_path, &ico) || ico.size() < sizeof(IconDir))
    return false;
  IconDir dir;
  memcpy(&dir, ico.data(), sizeof(dir));
  if (dir.reserved != 0 || dir.type != 1 || dir.count == 0 ||
      ico.size() < sizeof(IconDir) + dir.count * sizeof(IconDirEntry)) {
    return false;
  }

  std::vector<BYTE> group(sizeof(IconDir) +
                          dir.count * sizeof(GroupIconDirEntry));
  memcpy(group.data(), &dir, sizeof(dir));
  for (WORD i = 0; i < dir.count; ++i) {
    IconDirEntry entry;
    memcpy(&entry, ico.data() + sizeof(IconDir) + i * sizeof(IconDirEntry),
           sizeof(entry));
    if (static_cast<size_t>(entry.image_offset) + entry.bytes_in_res >
        ico.size()) {
      return false;
    }
    const WORD id = static_cast<WORD>(i + 1);
    if (!UpdateResourceW(update, kResourceIcon, MAKEINTRESOURCEW(id),
                         kNeutralLanguage, ico.data() + entry.image_offset,
                         entry.bytes_in_res)) {
      return false;
    }
    GroupIconDirEntry group_entry = {
        entry.width,  entry.height,    entry.color_count,  entry.reserved,
        entry.planes, entry.bit_count, entry.bytes_in_res, id};
    memcpy(group.data() + sizeof(IconDir) + i * sizeof(GroupIconDirEntry),
           &group_entry, sizeof(group_entry));
  }
  return UpdateResourceW(update, kResourceGroupIcon, MAKEINTRESOURCEW(1),
                         kNeutralLanguage, group.data(),
                         static_cast<DWORD>(group.size())) != FALSE;
}

// Minimal VS_VERSIONINFO writer (see "VS_VERSIONINFO structure" on MSDN).
class VersionWriter {
 public:
  size_t Begin(WORD value_length, WORD type, const wchar_t* key) {
    Align();
    const size_t start = data_.size();
    PutWord(0);  // wLength, patched by End().
    PutWord(value_length);
    PutWord(type);
    PutString(key);
    Align();
    return start;
  }

  void End(size_t start) {
    const WORD length = static_cast<WORD>(data_.size() - start);
    memcpy(&data_[start], &length, sizeof(length));
  }

  void PutWord(WORD value) { PutBytes(&value, sizeof(value)); }

  void PutString(const std::wstring& value) {
    PutBytes(value.c_str(), (value.size() + 1) * sizeof(wchar_t));
  }

  void PutBytes(const void* bytes, size_t size) {
    const BYTE* begin = static_cast<const BYTE*>(bytes);
    data_.insert(data_.end(), begin, begin + size);
  }

  void Align() {
    while (data_.size() % 4)
      data_.push_back(0);
  }

  const std::vector<BYTE>& data() const { return data_; }

 private:
  std::vector<BYTE> data_;
};

bool UpdateDescription(HANDLE update,
                       const std::wstring& source,
                       const std::wstring& description) {
  VS_FIXEDFILEINFO fixed = {};
  fixed.dwSignature = VS_FFI_SIGNATURE;
  fixed.dwStrucVersion = VS_FFI_STRUCVERSION;
  fixed.dwFileOS = VOS_NT_WINDOWS32;
  fixed.dwFileType = VFT_APP;

  static const wchar_t* kKeys[] = {
      L"CompanyName",      L"FileDescription", L"FileVersion",
      L"InternalName",     L"LegalCopyright",  L"OriginalFilename",
      L"ProductName",      L"ProductVersion"};
  std::vector<std::pair<std::wstring, std::wstring>> strings;

  DWORD handle = 0;
  const DWORD size = GetFileVersionInfoSizeW(source.c_str(), &handle);
  std::vector<BYTE> info(size);
  const bool has_info =
      size > 0 && GetFileVersionInfoW(source.c_str(), 0, size, info.data());
  if (has_info) {
    VS_FIXEDFILEINFO* existing = nullptr;
    UINT len = 0;
    if (VerQueryValueW(info.data(), L"\\", reinterpret_cast<LPVOID*>(&existing),
                       &len) &&
        existing && len >= sizeof(fixed)) {
      fixed = *existing;
    }
  }
  for (const wchar_t* key : kKeys) {
    std::wstring value;
    if (wcscmp(key, L"FileDescription") == 0 ||
        wcscmp(key, L"ProductName") == 0) {
      value = description;
    } else if (has_info) {
      std::wstring query = L"\\StringFileInfo\\040904b0\\";
      query += key;
      wchar_t* existing = nullptr;
      UINT len = 0;
      if (VerQueryValueW(info.data(), query.c_str(),
                         reinterpret_cast<LPVOID*>(&existing), &len) &&
          existing && len > 0) {
        value.assign(existing, wcsnlen(existing, len));
      }
    }
    if (!value.empty())
      strings.emplace_back(key, value);
  }

  VersionWriter writer;
  const size_t root =
      writer.Begin(sizeof(VS_FIXEDFILEINFO), 0, L"VS_VERSION_INFO");
  writer.PutBytes(&fixed, sizeof(fixed));
  const size_t string_info = writer.Begin(0, 1, L"StringFileInfo");
  const size_t table = writer.Begin(0, 1, L"040904b0");
  for (const auto& entry : strings) {
    const size_t item = writer.Begin(
        static_cast<WORD>(entry.second.size() + 1), 1, entry.first.c_str());
    writer.PutString(entry.second);
    writer.End(item);
  }
  writer.End(table);
  writer.End(string_info);
  const size_t var_info = writer.Begin(0, 1, L"VarFileInfo");
  const size_t translation = writer.Begin(4, 0, L"Translation");
  writer.PutWord(0x0409);
  writer.PutWord(0x04b0);
  writer.End(translation);
  writer.End(var_info);
  writer.End(root);

  return UpdateResourceW(update, kResourceVersion, MAKEINTRESOURCEW(VS_VERSION_INFO),
                         kEnglishLanguage,
                         const_cast<BYTE*>(writer.data().data()),
                         static_cast<DWORD>(writer.data().size())) != FALSE;
}

}  // namespace

void Configure(JNIEnv* env, jobject jsettings) {
  g_initial_app_id = CurrentProcessAppId();
  if (!jsettings)
    return;
  ScopedJNIClass cls(env, "org/cef/CefSettings");
  if (!cls)
    return;
  g_icon_path = ReadString(env, cls, jsettings, "app_icon_path");
  g_app_id = ReadString(env, cls, jsettings, "app_user_model_id");
  if (!g_app_id.empty())
    SetCurrentProcessExplicitAppUserModelID(g_app_id.c_str());
}

void ReapplyProcessIdentity() {
  const std::wstring current = CurrentProcessAppId();
  if (!g_app_id.empty() || current != g_initial_app_id) {
    const std::wstring id = EffectiveAppId();
    if (current != id)
      SetCurrentProcessExplicitAppUserModelID(id.c_str());
  }
}

void ApplyToBrowser(CefRefPtr<CefBrowser> browser) {
  if (!browser || browser->GetHost()->IsWindowRenderingDisabled())
    return;
  BrandBrowserWindow(browser);
  for (int64_t delay : kReapplyDelaysMs) {
    CefPostDelayedTask(TID_UI, base::BindOnce(&BrandBrowserWindow, browser),
                       delay);
  }
}

bool BrandExecutable(const std::wstring& source,
                     const std::wstring& target,
                     const std::wstring& icon_path,
                     const std::wstring& description) {
  const std::wstring temp = target + L".tmp";
  if (!CopyFileW(source.c_str(), temp.c_str(), FALSE))
    return false;

  bool ok = false;
  HANDLE update = BeginUpdateResourceW(temp.c_str(), FALSE);
  if (update) {
    ok = (icon_path.empty() || UpdateIcon(update, icon_path)) &&
         (description.empty() || UpdateDescription(update, source, description));
    ok = EndUpdateResourceW(update, ok ? FALSE : TRUE) && ok;
  }
  if (ok) {
    ok = MoveFileExW(temp.c_str(), target.c_str(),
                     MOVEFILE_REPLACE_EXISTING) != FALSE;
  }
  if (!ok)
    DeleteFileW(temp.c_str());
  return ok;
}

}  // namespace window_branding
