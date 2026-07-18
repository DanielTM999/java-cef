# Building & packaging the JCEF Orion fork

This document covers the **Orion fork** additions: the portable Java API jar and
how the Orion IDE consumes it. For the full native JCEF build (CEF download,
JNI, per-OS toolchains) see the upstream docs and the top-level `CLAUDE.md`.

## The two-layer model

JCEF is Java + native. The Orion fork changes **only the Java layer** (a
dedicated CEF owner thread + async init APIs; see `MODIFICATIONS.md`). That has
an important consequence:

| Layer | OS-specific? | Who provides it |
|---|---|---|
| `jcef-orion.jar` (`org.cef.*`) | No; pure Java | This fork's build |
| `libjcef` / `jcef.dll` + CEF runtime (`libcef`, `*.pak`, `icudtl.dat`, `locales/`) | Yes | The per-OS application package, for example the Orion `jpackage` build |

Because the fork adds no native methods, its jar is a drop-in replacement for
the upstream `org.cef` classes. The browser runtime still belongs in the
platform-specific app package. In Orion's case, `jpackage` can produce one
package per OS containing the right native CEF runtime.

## Build locally

Requirements: JDK 21+ and `bash` (Git Bash on Windows). No native toolchain or
CEF download is needed for the portable jar.

```sh
# 1. Compile the OS-independent Java classes.
tools/compile.sh linux64          # any platform label works; bytecode is identical
#   On Windows use:  tools\compile.bat win64

# 2. Run the pure-Java unit tests (no native libraries required).
JUNIT=third_party/junit/junit-platform-console-standalone-1.4.2.jar
JOGL=$(printf '%s:' third_party/jogamp/jar/*.jar)
CP="$JUNIT:out/linux64:$JOGL"
java -cp "$CP" org.junit.platform.console.ConsoleLauncher -cp "$CP" \
  --select-class tests.junittests.CefMainThreadTest \
  --select-class tests.junittests.CefInitializationModeTest

# 3. Package the portable artifacts (jar + sources jar + POM + checksums).
scripts/package-portable.sh 146.0.0 dist

# 4. Validate the distribution.
scripts/validate-package.sh 146.0.0 dist
```

`dist/` then contains:

```
jcef-orion-146.0.0.jar          # portable Java API — runs on any OS
jcef-orion-146.0.0-sources.jar
jcef-orion-146.0.0.pom
SHA256SUMS.txt
```

## Run the Swing demo

`java/tests/orion/OrionAsyncInitExample.java` contrasts `LEGACY_EDT` and
`DEDICATED_CEF_THREAD`. It needs the JCEF native libraries on
`-Djava.library.path` (a full native build or a jcefmaven install dir). While
`N_Initialize` runs, the demo's counter, spinner, text field and buttons stay
responsive in dedicated mode and freeze in legacy mode.

### Verify a downloaded release

```sh
sha256sum -c SHA256SUMS.txt
```

## How the Orion IDE consumes the fork

Put **`jcef-orion.jar` ahead of** jcefmaven's bundled `jcef.jar` on the
classpath (or module path) so the fork's `org.cef.*` classes win. The native CEF
runtime should be provided by the current OS package, such as Orion's `jpackage`
output.

1. Select the dedicated mode on Windows/Linux before initializing:

   ```java
   CefSettings settings = builder.getCefSettings();
   settings.windowless_rendering_enabled = false;
   settings.initialization_mode = CefSettings.CefInitializationMode.DEDICATED_CEF_THREAD;

   CefApp app = CefApp.getInstance(args, settings);
   app.initializeAsync().whenComplete((cefApp, error) -> {
       if (error != null) { /* show error on the EDT */ return; }
       SwingUtilities.invokeLater(() -> {
           CefClient client = cefApp.createClient(); // requires INITIALIZED
           // attach browser...
       });
   });
   ```

   On macOS this automatically falls back to `LEGACY_EDT` (logged), so the same
   code is safe everywhere.

> Note: in `DEDICATED_CEF_THREAD` mode `createClient()` requires the app to be
> `INITIALIZED` and throws otherwise. `CefAppBuilder.build()` calls
> `createClient()` internally, so initialize via `initializeAsync()` first (or
> use `createClientAsync()`), rather than relying on lazy init.

## `LEGACY_EDT` vs `DEDICATED_CEF_THREAD`

| | `LEGACY_EDT` (default) | `DEDICATED_CEF_THREAD` |
|---|---|---|
| CEF owner thread | AWT EDT | `Orion-JCEF-Main` |
| EDT during `N_Initialize` | **frozen** | responsive |
| Platforms | all | Windows, Linux (macOS falls back) |
| `createClient()` | lazy init on EDT | requires `INITIALIZED` |

## Updating base versions

- **CEF version:** edit `CEF_VERSION` in `CMakeLists.txt` (single source of
  truth); `cmake/DownloadCEF.cmake` fetches the matching distribution.
- **Upstream sync:** the fork touches a small, isolated set of Java files (see
  `MODIFICATIONS.md`), so rebasing onto a newer upstream JCEF should be low
  friction.

