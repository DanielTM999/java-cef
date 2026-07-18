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
| `java/org/cef/CefMainThread.java` | The dedicated `Orion-JCEF-Main` single-thread executor. |
| `java/org/cef/CefInitializationException.java` | Rich Java exception wrapping native init failures. |
| `java/tests/junittests/CefMainThreadTest.java` | Pure-Java unit tests for the owner thread. |
| `java/tests/junittests/CefInitializationModeTest.java` | Pure-Java unit tests for mode resolution / platform fallback. |
| `java/tests/orion/OrionAsyncInitExample.java` | Runnable Swing demo comparing `LEGACY_EDT` vs `DEDICATED_CEF_THREAD`. |
| `MODIFICATIONS.md` | This file. |
| `docs/BUILDING.md` | Build/packaging/workflow guide and Orion integration notes. |
| `scripts/package-portable.sh` | Build the Java API jar with shaded JOGL/GlueGen dependencies, sources jar, POM and `SHA256SUMS.txt`. |
| `scripts/package-universal.sh` | Embed `win64`, `linux64` and `macosx64` redistributables into `jcef-orion-<version>.jar`. |
| `scripts/validate-package.sh` | Validate a produced distribution. |
| `.github/workflows/native-binaries.yml` | Build native redistributables per OS, assemble the shared jar, publish a Release and delete temporary Actions artifacts. |

## Modified files

| File | Change |
|---|---|
| `java/org/cef/CefSettings.java` | Added `CefInitializationMode` enum + `initialization_mode` field. |
| `java/org/cef/CefApp.java` | Mode resolution; dedicated owner-thread dispatch for pre-init / init / message-loop / shutdown; `initializeAsync()` / `createClientAsync()`; one-shot native-init guard; bundled-native library path lookup; logging. Legacy EDT path preserved. |
| `java/org/cef/SystemBootstrap.java` | Default loader can extract embedded per-OS native runtime resources from the shared jar and load native libraries from the extracted cache. |
| `tools/compile.sh`, `tools/compile.bat` | Also compile the new `tests/orion` package; Windows compilation now uses an argument file so `javac` receives expanded source paths reliably. |
| `tools/make_jar.bat` | Packages class directories with `jar -C` instead of relying on Windows wildcard expansion. |
| `CMakeLists.txt` | Added `JCEF_DOWNLOAD_CLANG_FORMAT=OFF` option so CI can avoid the Chromium `gsutil` / Python `six.moves` failure while configuring native builds. |
| `README.md` | Added an "Orion fork" section linking to this file and `docs/BUILDING.md`. |

## Not modified

- `LICENSE.txt` and all copyright headers.
- Native C++ (`native/context.cpp`, `native/CefApp.cpp`, etc.).
- Upstream behavior when `initialization_mode` is left at its default.

## Distribution model

The fork Release is distributed as a shared `jcef-orion-<version>.jar` containing
the Java API, shaded JOGL/GlueGen dependencies, and embedded native runtimes for
`win64`, `linux64` and `macosx64`. At runtime the default loader extracts only
the current OS runtime to `~/.jcef-orion/<version>/<platform>` and loads it from
there.

Native binaries are Release assets, not committed to git. The workflow also
publishes `jcef-distrib-<platform>.tar.gz` for manual inspection or external
packaging.

See `docs/BUILDING.md` for build, packaging, workflow and Orion integration
details.

---

*Pending optional work: a `legal/` bundle aggregating third-party license texts.*
