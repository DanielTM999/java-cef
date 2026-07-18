# Building & Packaging The JCEF Orion Fork

This document covers the Orion fork additions: the shared
`jcef-orion-<version>.jar` with embedded native runtimes, CI packaging, and how
the Orion IDE consumes it. For the full native JCEF build (CEF download, JNI,
per-OS toolchains) see the upstream docs and the top-level `CLAUDE.md`.

## Packaging Model

JCEF is Java + native. The Orion fork changes the Java layer with a dedicated
CEF owner thread and async init APIs. The Release workflow packages the native
runtimes beside that Java layer.

| Artifact | Contains | Runtime behavior |
|---|---|---|
| `jcef-orion-<version>.jar` from Release | Java API + shaded JOGL/GlueGen dependencies + `win64`, `linux64`, `macosx64` JCEF/CEF runtimes | Extracts the current OS runtime to `~/.jcef-orion/<version>/<platform>` and loads it automatically. |
| `jcef-distrib-<platform>.tar.gz` from Release | Standalone native redistributable for one platform | Useful for manual inspection or external packaging. |
| local `scripts/package-portable.sh` jar | Java API + shaded JOGL/GlueGen dependencies | Requires native JCEF/CEF from `java.library.path`. |

The shared Release jar is intentionally large. It lets Orion use one dependency
name across supported desktop OSes while the loader extracts only the runtime
for the current OS.

## Build Locally

Requirements: JDK 21+ and `bash` (Git Bash on Windows). No native toolchain or
CEF download is needed for the Java/API jar.

```sh
# 1. Compile the OS-independent Java classes.
tools/compile.sh linux64
# On Windows use: tools\compile.bat win64

# 2. Run the pure-Java unit tests.
JUNIT=third_party/junit/junit-platform-console-standalone-1.4.2.jar
JOGL=$(printf '%s:' third_party/jogamp/jar/*.jar)
CP="$JUNIT:out/linux64:$JOGL"
java -cp "$CP" org.junit.platform.console.ConsoleLauncher -cp "$CP" \
  --select-class tests.junittests.CefMainThreadTest \
  --select-class tests.junittests.CefInitializationModeTest

# 3. Package the Java-only artifacts.
scripts/package-portable.sh 1.0.0 dist
```

On Windows, after `tools\compile.bat win64`, run the packaging scripts from Git
Bash. They auto-detect `out/win64`; `JCEF_CLASSES_DIR=out/<platform>` can be set
explicitly when needed.

`dist/` then contains:

```text
jcef-orion-1.0.0.jar
jcef-orion-1.0.0-sources.jar
jcef-orion-1.0.0.pom
SHA256SUMS.txt
```

To assemble a shared jar with embedded native runtimes locally, first create one
or more native redistributables under `binary_distrib/<platform>`, then run:

```sh
scripts/package-universal.sh 1.0.0 dist binary_distrib
```

Local packaging embeds only the platforms it finds. For example, if your machine
only has `binary_distrib/win64`, the jar will contain only the Windows runtime.
The GitHub Actions workflow sets `REQUIRED_PLATFORMS="win64 linux64 macosx64"`,
so the Release jar fails to publish unless all three runtimes are present.

## GitHub Actions Workflow

| Workflow | Trigger | What it does |
|---|---|---|
| `.github/workflows/native-binaries.yml` | manual, tag `native-v*` | Builds native redistributables for `win64`, `linux64` and `macosx64`, assembles `jcef-orion-<version>.jar` with those runtimes embedded, publishes a GitHub Release, then deletes temporary Actions artifacts. |

Manual run example:

```sh
gh workflow run native-binaries.yml -r master -f version=1.0.0 -f release_tag=native-v1.0.0
```

## Verify A Release

```sh
sha256sum -c SHA256SUMS.txt
```

## How Orion Consumes The Fork

Put `jcef-orion-<version>.jar` ahead of jcefmaven's bundled `jcef.jar` on the
classpath or module path so the fork's `org.cef.*` classes win. The default
`SystemBootstrap` loader extracts and loads the embedded native runtime for the
current OS automatically.

Select the dedicated mode on Windows/Linux before initializing:

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

On macOS this automatically falls back to `LEGACY_EDT`, so the same code is safe
everywhere.

In `DEDICATED_CEF_THREAD` mode `createClient()` requires the app to be
`INITIALIZED` and throws otherwise. Initialize via `initializeAsync()` first, or
use `createClientAsync()`.

## LEGACY_EDT Vs DEDICATED_CEF_THREAD

| | `LEGACY_EDT` (default) | `DEDICATED_CEF_THREAD` |
|---|---|---|
| CEF owner thread | AWT EDT | `Orion-JCEF-Main` |
| EDT during `N_Initialize` | Frozen | Responsive |
| Platforms | All | Windows, Linux (macOS falls back) |
| `createClient()` | Lazy init on EDT | Requires `INITIALIZED` |

## Updating Base Versions

- CEF version: edit `CEF_VERSION` in `CMakeLists.txt`.
- Upstream sync: the fork touches a small, isolated set of Java files, so
  rebasing onto a newer upstream JCEF should be low friction.
