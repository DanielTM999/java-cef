# MODIFICATIONS.md

This file documents the changes made in the **Orion fork** of JCEF. It exists to
satisfy the licensing requirement that modifications to the original work be
clearly identified. All original copyright notices, `LICENSE.txt`, and the CEF /
Chromium / third-party notices are preserved unchanged.

## Fork identity

| Field | Value |
|---|---|
| Fork name | JCEF (Orion fork) |
| Original project | JCEF - https://github.com/chromiumembedded/java-cef |
| Base JCEF commit (upstream) | `17e805a` (merged 2026-09-22; previously `6d3e8ca`) |
| CEF version | `152.0.6+g708dc14+chromium-152.0.7977.83` (see `CMakeLists.txt`) |
| First modification date | 2026-07-18 |
| Purpose | Allow the global CEF context lifecycle to run on a dedicated thread so that native Chromium initialization does not freeze the Swing EDT. |

## Summary of changes

Upstream JCEF forces the global CEF context lifecycle (`CefInitialize`,
`CefShutdown`, and when applicable `CefDoMessageLoopWork`) onto the AWT Event
Dispatch Thread (EDT) via `SwingUtilities.invokeAndWait()`. As a result the first
real browser open blocks the whole UI while Chromium initializes.

This fork adds an opt-in **initialization mode** that moves the entire global CEF
context lifecycle to a dedicated, permanent thread named **`Orion-JCEF-Main`**,
keeping the EDT responsive. The change is deliberately small and isolated so it
survives future upstream merges.

### New initialization modes

```text
CefSettings.CefInitializationMode.LEGACY_EDT             (default; upstream behavior)
CefSettings.CefInitializationMode.DEDICATED_CEF_THREAD   (Orion; Windows/Linux only)
```

- The mode is resolved **once**, before pre-initialization, in the centralized
  `CefApp.resolveInitializationMode(CefSettings)`.
- The default is `LEGACY_EDT`, so existing code and upstream users are unaffected.
- On macOS and any non-Windows/Linux platform, `DEDICATED_CEF_THREAD`
  automatically falls back to `LEGACY_EDT` with an explanatory log line because
  macOS requires the process main thread and a Cocoa loop.

### New public API

- `CompletableFuture<CefApp> CefApp.initializeAsync()` - idempotent; concurrent
  callers share the same future; native pre-init/init each run at most once.
- `CompletableFuture<CefClient> CefApp.createClientAsync()` - waits for
  initialization without blocking the caller.
- `CefApp.createClient()` - in `DEDICATED_CEF_THREAD` mode requires the app to be
  `INITIALIZED` and throws a clear `IllegalStateException` otherwise. In
  `LEGACY_EDT` mode it is unchanged.
- `CefInitializationMode CefApp.getInitializationMode()`.
- `SystemBootstrap.setRuntimeDownloadProvider(...)` - overrides where the
  portable jar downloads native runtime zips from.
- `SystemBootstrap.setDownloadProgressListener(...)` - reports native runtime
  download progress by platform, URL, byte counts and percentage.

### Buffered (lightweight) off-screen rendering

Upstream ships two `CefBrowser` implementations: `CefBrowserWr` (windowed) and
`CefBrowserOsr` (off-screen via a JOGL `GLCanvas`). Both expose a **heavyweight**
AWT peer, so embedding a browser next to Swing content (tabs, split panes)
flickers whenever a sibling relayouts or repaints.

This fork adds a third implementation, `CefBrowserOsrBuffered`, that paints the
off-screen `onPaint` pixel buffer into a lightweight, double-buffered
`JComponent` via a software `BufferedImage`. It reuses the existing native
windowless path unchanged (created with a `0` window handle, exactly as
`CefBrowserOsr` already does), so **no native/C++ change is required**. It
handles input, focus, cursor, drag-and-drop, HiDPI scaling and `<select>`
popups.

Selection is done with the new `CefRendering` enum, keeping every existing
boolean-based `createBrowser` overload untouched:

```java
// heavyweight windowed (unchanged default)
client.createBrowser(url, CefRendering.WINDOWED, false);
// heavyweight GLCanvas OSR (== legacy isOffscreenRendered=true)
client.createBrowser(url, CefRendering.OFFSCREEN, false);
// new lightweight, flicker-free software OSR
client.createBrowser(url, CefRendering.OFFSCREEN_BUFFERED, false);
```

Requires `CefSettings.windowless_rendering_enabled = true` (as any OSR mode
does). The legacy `createBrowser(url, boolean isOffscreenRendered, ...)`
overloads map to `WINDOWED` / `OFFSCREEN` and are unchanged.

### Windowed-rendering flicker mitigation (Windows)

`CefRendering.WINDOWED` parents a native Chromium child window inside the AWT
hierarchy, so the surrounding AWT windows repaint the area the child window
occupies before Chromium gets to repaint it. That is visible as flicker
whenever a sibling Swing component relayouts. Two mitigations were added for
embedders that prefer the native surface over `OFFSCREEN_BUFFERED`:

- `CefBrowserWr` marks its `Canvas` with `setIgnoreRepaint(true)`. The canvas
  exists only to lend its window handle to the browser, so every AWT paint of
  it is immediately overdrawn by the child window.
- `native/CefBrowser_N.cpp` adds `WS_CLIPCHILDREN` to the parent window and its
  ancestors up to the top-level window when a windowed browser is created, so
  those windows stop painting over the browser's child window. Windows-only and
  scoped to the windowed creation path.

Neither affects `OFFSCREEN` or `OFFSCREEN_BUFFERED`.

### Windowed-rendering focus hand-back (Windows)

`CefBrowserHost::SetFocus(false)` only blurs the web contents — the native
keyboard focus stays on Chromium's child window. An embedder that puts Swing
input fields next to a windowed browser therefore cannot get keystrokes back:
the field appears focused to Java while Windows keeps routing keys to Chromium.

`N_SetFocus` now hands the native focus back to the AWT parent window when
`enable` is `false` and the browser is windowed. It attaches to the parent's
input queue first (`AttachThreadInput`), because the AWT windows are owned by
the toolkit thread rather than by the caller, and only moves the focus when it
currently sits on the browser window or one of its children. Windowless
browsers are untouched.

### Windowed-rendering keyboard focus (Linux/X11)

On X11 the keyboard focus stays on the embedder's top-level window and AWT never
receives mouse events that land on the browser's own child window, so nothing
ever tells a windowed browser to take the keyboard: clicking a page and typing
sends the keystrokes to whatever Swing component holds the AWT focus. Upstream
relies on the hosting `Canvas` gaining AWT focus, which cannot happen there.

`CefBrowserWr` now watches the pointer instead. A `mouseExited` whose position
is still inside the canvas means the pointer moved into the browser's child
window; that arms a 400 ms timer which keeps calling `setFocus(true)` for as
long as the pointer stays over the browser. Re-asserting is required rather than
optional: click-to-focus window managers hand the focus back to the top-level
window on every click, which would undo a single `setFocus`.

To avoid stealing the keyboard from an embedder field that is being typed into,
the timer skips while the AWT focus owner is outside the browser component and a
key was pressed in the last 3 seconds. Set `-Djcef.orion.linux.pointer-focus=false`
to disable the whole mechanism. Windows, macOS and both off-screen modes are
unaffected.

### Buffered OSR paint performance

`CefBrowserOsrBuffered` used to spend most of the EDT time per frame in Java2D
slow paths. Measured on Windows with an animated page (EDT paint time per
frame, same runtime):

| Scale | Before | After |
|---|---|---|
| 100% | 6.8 ms (max 13 ms) | 2.1 ms (max 4 ms) |
| 150% | 37 ms (max 45 ms, frames dropped) | 5.9 ms (max 11 ms) |

- Opaque browsers store frames as `TYPE_INT_RGB` instead of `TYPE_INT_ARGB`,
  so Java2D blits instead of alpha blending every pixel.
- On scaled displays the frame (already in device pixels) is painted with a
  snapped, translation-only transform. `scale(sf)` combined with
  `scale(1/sf)` is rarely exactly 1.0 in floating point, which forced Java2D to
  resample the whole frame on every paint.
- Only the area not covered by the frame is cleared.
- `-Djcef.orion.osr.stats=true` logs `onPaint` fps and copy/paint timings every
  5 seconds.

### Embedder branding (Windows)

Chromium creates some native windows on its own (DevTools) and may replace the
process AppUserModelID with its own, so the embedder showed Chromium's icon on
those windows, on its own taskbar group and, through `jcef_helper.exe`, in the
Task Manager. New `CefSettings` fields let the embedder choose; nothing set
means a generic icon, never Chromium's:

| Field | Effect |
|---|---|
| `app_icon_path` | `.ico` for Chromium-owned windows and the branded helper. |
| `app_user_model_id` | Process AppUserModelID, re-applied after Chromium initializes and set on Chromium-owned windows so DevTools groups with the embedder. |
| `app_display_name` | FileDescription/ProductName of the branded helper (Task Manager name). |
| `helper_executable_name` | Creates `<name>.exe` next to `jcef_helper.exe` with that icon/description and launches sub-processes from it (only when `browser_subprocess_path` is unset). Refreshed when the helper, icon or description change; falls back to `jcef_helper.exe` if it cannot be written. |

Native side: `native/window_branding.h` / `native/window_branding_win.cpp`
(`WM_SETICON` + window property store on Chromium-owned top-level windows,
re-applied after 250 ms and 1.5 s; `UpdateResource` based helper branding),
hooked from `context.cpp` (`Configure` before `CefInitialize`,
`ReapplyProcessIdentity` in `OnContextInitialized`), `life_span_handler.cpp`
(`OnAfterCreated`) and the new static `CefApp.N_BrandExecutable`. AWT windows
(`SunAwt*` classes) are never touched. Linux and macOS are no-ops.

### Versioned runtime cache

The runtime used to live in `<jcef.orion.cache.path>/<platform>` when the
embedder set a cache path, with a completion marker that did not record the
version. Once any runtime had been extracted there, every later release kept
loading it, pairing new Java classes with an old `libcef`/`jcef` (a 1.1.0 jar
kept running the CEF 146 runtime of 1.0.0).

- Runtimes now always live in `<base>/<version>/<platform>`, where `<base>` is
  `jcef.orion.cache.path` or `~/.jcef-orion`, and the marker records
  `version=<version>`; a marker for another version is ignored.
- A running process holds a shared lock on `<runtime>/.jcef-runtime-lock`.
- After the runtime is ready, a background thread removes runtimes of other
  versions and the old `<base>/<platform>` layout. Only directories carrying the
  runtime marker are considered, never symlinks. A directory is removed only if
  its lock can be taken exclusively (not in use by another process); old
  layouts without a lock are removed only on Windows, where renaming a directory
  whose libraries are loaded fails. Set `-Djcef.orion.runtime.cleanup=false` to
  keep them.

The version is resolved, in order, from `-Djcef.orion.version`, the resource
`org/cef/jcef-orion-version.properties` (written by `scripts/package-portable.sh`)
and the jar manifest's `Implementation-Version`. The resource exists because
shaded/fat jars (e.g. `maven-shade-plugin`) replace the manifest: an embedder
shipping such a jar otherwise fell back to `1.0.0` and kept downloading the
CEF 146 runtime of that release. When no version can be found the loader warns,
still uses `1.0.0`, and never removes other runtimes.

### Runtime download integrity

A server that closes the connection early ends the download read loop without
raising, and `ZipInputStream` extracts a truncated archive without complaining,
so a partial download used to produce short files, still get the
`.jcef-runtime-complete` marker written, and poison the cache permanently: every
later run reused it and failed with `UnsatisfiedLinkError: %1 is not a valid
Win32 application`.

The downloader now compares the number of bytes read against `Content-Length`
and fails on a short read; extraction went from `ZipInputStream` to `ZipFile`, so
a truncated archive is rejected up front by the central directory and every
entry's extracted size is checked against the size recorded in the zip. If a
runtime library still fails to load, the completion marker is deleted so the next
run downloads the runtime again instead of failing forever.

### Supported platforms for `DEDICATED_CEF_THREAD`

| Platform | Behavior |
|---|---|
| Windows x86_64 | Dedicated `Orion-JCEF-Main` thread, when requested |
| Linux x86_64 | Dedicated `Orion-JCEF-Main` thread, when requested |
| macOS, any arch | Forced `LEGACY_EDT`, documented with a log line |
| Other | Forced `LEGACY_EDT` |

### Known limitations

- OSR / windowless rendering uses an external message pump; the Swing `Timer`
  still schedules on the EDT, but the native `N_DoMessageLoopWork` call is
  routed to `Orion-JCEF-Main`.
- No native C++ change is required for the happy path on Windows/Linux: thread
  ownership consistency is guaranteed on the Java side by always dispatching the
  global-context operations to the same owner thread.

## New files

| File | Purpose |
|---|---|
| `java/org/cef/browser/CefBrowserOsrBuffered.java` | Lightweight software OSR browser painting into a `BufferedImage`/`JComponent` (flicker-free embedding). |
| `java/org/cef/browser/CefRendering.java` | Rendering-mode enum (`WINDOWED` / `OFFSCREEN` / `OFFSCREEN_BUFFERED`). |
| `java/org/cef/browser/CefScrollConfigurable.java` | Public interface to tune the OSR mouse-wheel pixels-per-notch at runtime (implemented by `CefBrowserOsrBuffered`). |
| `java/org/cef/CefMainThread.java` | The dedicated `Orion-JCEF-Main` single-thread executor. |
| `java/org/cef/CefInitializationException.java` | Rich Java exception wrapping native init failures. |
| `java/tests/junittests/CefMainThreadTest.java` | Pure-Java unit tests for the owner thread. |
| `java/tests/junittests/CefInitializationModeTest.java` | Pure-Java unit tests for mode resolution / platform fallback. |
| `java/tests/orion/OrionAsyncInitExample.java` | Runnable Swing demo comparing `LEGACY_EDT` vs `DEDICATED_CEF_THREAD`. |
| `native/window_branding.h`, `native/window_branding_win.cpp` | Embedder icon / AppUserModelID for Chromium-owned windows and helper executable branding (Windows). |
| `MODIFICATIONS.md` | This file. |
| `docs/BUILDING.md` | Build/packaging/workflow guide and Orion integration notes. |
| `scripts/package-portable.sh` | Build the Java API jar with shaded JOGL/GlueGen dependencies, sources jar, POM and `SHA256SUMS.txt`. |
| `scripts/package-universal.sh` | Embed `win64`, `linux64` and `macosx64` redistributables into the optional offline jar. |
| `scripts/validate-package.sh` | Validate a produced distribution. |
| `.github/workflows/native-binaries.yml` | Build native redistributables per OS, publish the embedded jar, portable jar, runtime zips, and delete temporary Actions artifacts. |

## Modified files

| File | Change |
|---|---|
| `java/org/cef/CefSettings.java` | Added `CefInitializationMode` enum + `initialization_mode` field; `app_icon_path`, `app_user_model_id`, `app_display_name`, `helper_executable_name` branding fields. |
| `java/org/cef/browser/CefBrowserOsrBuffered.java` | Opaque `TYPE_INT_RGB` frames, device-space 1:1 blit on scaled displays, opt-in paint stats. |
| `native/context.cpp`, `native/life_span_handler.cpp`, `native/CefApp.{cpp,h}`, `native/CMakeLists.txt` | Branding hooks and `N_BrandExecutable` (see "Embedder branding"). |
| `java/org/cef/CefApp.java` | Mode resolution; dedicated owner-thread dispatch for pre-init / init / message-loop / shutdown; `initializeAsync()` / `createClientAsync()`; one-shot native-init guard; bundled-native library path lookup; logging; branded Windows helper resolution. Legacy EDT path preserved. |
| `java/org/cef/SystemBootstrap.java` | Default loader can extract embedded per-OS native runtime resources, download missing runtime zips from a configurable provider, report download progress, and load native libraries from the extracted cache; verifies download length and per-entry extracted sizes, and drops the cache marker when a runtime library fails to load; versioned runtime cache with in-use lock and cleanup of other versions. |
| `java/org/cef/browser/CefBrowserFactory.java` | Added `create(...)` overload taking a `CefRendering` mode; legacy boolean overload delegates to it. |
| `java/org/cef/CefClient.java` | Added `createBrowser(...)` overloads taking a `CefRendering` mode. |
| `java/org/cef/browser/CefBrowserWr.java` | `setIgnoreRepaint(true)` on the hosting `Canvas` to cut windowed-rendering flicker; pointer-driven keyboard focus for windowed browsers on Linux/X11. |
| `native/CefBrowser_N.cpp` | Windows only: sets `WS_CLIPCHILDREN` on the AWT parent window chain when creating a windowed browser; `N_SetFocus(false)` hands the native keyboard focus back to the AWT parent window. |
| `tools/compile.sh`, `tools/compile.bat` | Also compile the new `tests/orion` package; Windows compilation now uses an argument file so `javac` receives expanded source paths reliably. |
| `tools/make_jar.bat` | Packages class directories with `jar -C` instead of relying on Windows wildcard expansion. |
| `CMakeLists.txt` | Added `JCEF_DOWNLOAD_CLANG_FORMAT=OFF` option so CI can avoid the Chromium `gsutil` / Python `six.moves` failure while configuring native builds. |
| `README.md` | Added an "Orion fork" section linking to this file and `docs/BUILDING.md`. |

## Not modified

- `LICENSE.txt` and all copyright headers.
- Native C++ except for the windowed-rendering `WS_CLIPCHILDREN` and focus
  hand-back changes in `native/CefBrowser_N.cpp`, the Windows multi-threaded
  message loop in `native/context.cpp`, and the embedder branding hooks listed
  above.
- Upstream behavior when `initialization_mode` is left at its default.

## Distribution model

The fork Release publishes `jcef-orion-<version>.jar` as the primary embedded
artifact. It contains the Java API, shaded JOGL/GlueGen dependencies and native
runtimes for `win64`, `linux64` and `macosx64`, so it does not need a runtime
download.

For smaller deployments the Release also publishes
`jcef-orion-<version>-portable.jar`. At runtime the default loader downloads
only the current OS asset named `jcef-runtime-<platform>-<version>.zip`,
extracts it to `~/.jcef-orion/<version>/<platform>`, and loads it from there.

Native binaries are Release assets, not committed to git.

See `docs/BUILDING.md` for build, packaging, workflow and Orion integration
details.

---

*Pending optional work: a `legal/` bundle aggregating third-party license texts.*
