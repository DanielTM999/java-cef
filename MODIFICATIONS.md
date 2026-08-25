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
| Base JCEF commit (upstream) | `6d3e8ca` |
| CEF version | `146.0.10+g8219561+chromium-146.0.7680.179` (see `CMakeLists.txt`) |
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
| `MODIFICATIONS.md` | This file. |
| `docs/BUILDING.md` | Build/packaging/workflow guide and Orion integration notes. |
| `scripts/package-portable.sh` | Build the Java API jar with shaded JOGL/GlueGen dependencies, sources jar, POM and `SHA256SUMS.txt`. |
| `scripts/package-universal.sh` | Embed `win64`, `linux64` and `macosx64` redistributables into the optional offline jar. |
| `scripts/validate-package.sh` | Validate a produced distribution. |
| `.github/workflows/native-binaries.yml` | Build native redistributables per OS, publish the embedded jar, portable jar, runtime zips, and delete temporary Actions artifacts. |

## Modified files

| File | Change |
|---|---|
| `java/org/cef/CefSettings.java` | Added `CefInitializationMode` enum + `initialization_mode` field. |
| `java/org/cef/CefApp.java` | Mode resolution; dedicated owner-thread dispatch for pre-init / init / message-loop / shutdown; `initializeAsync()` / `createClientAsync()`; one-shot native-init guard; bundled-native library path lookup; logging. Legacy EDT path preserved. |
| `java/org/cef/SystemBootstrap.java` | Default loader can extract embedded per-OS native runtime resources, download missing runtime zips from a configurable provider, report download progress, and load native libraries from the extracted cache. |
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
  hand-back changes in `native/CefBrowser_N.cpp` (`native/context.cpp`, `native/CefApp.cpp`, etc. are
  untouched).
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
