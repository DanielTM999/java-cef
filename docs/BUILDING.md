# Building & Packaging The JCEF Orion Fork

This document covers the Orion fork additions: embedded and downloadable native
runtime packaging, CI packaging, and how the Orion IDE consumes it. For the full
native JCEF build (CEF download, JNI, per-OS toolchains) see the upstream docs
and the top-level `CLAUDE.md`.

## Packaging Model

JCEF is Java + native. The Orion fork changes the Java layer with a dedicated
CEF owner thread and async init APIs. The Release workflow packages the native
runtimes beside that Java layer.

| Artifact | Contains | Runtime behavior |
|---|---|---|
| `jcef-orion-<version>.jar` from Release | Java API + shaded JOGL/GlueGen dependencies + `win64`, `linux64`, `macosx64` JCEF/CEF runtimes | Extracts the current OS runtime to `~/.jcef-orion/<version>/<platform>` and loads it automatically without network access. |
| `jcef-orion-<version>-portable.jar` from Release | Java API + shaded JOGL/GlueGen dependencies | Downloads `jcef-runtime-<platform>-<version>.zip` from the same GitHub Release when no local/embedded runtime is available, extracts it to `~/.jcef-orion/<version>/<platform>`, and loads it automatically. |
| `jcef-runtime-<platform>-<version>.zip` from Release | Standalone native runtime files for one platform | Used by the portable jar downloader and useful for manual inspection or external packaging. |
| local `scripts/package-portable.sh` jar | Java API + shaded JOGL/GlueGen dependencies | Downloads the matching runtime zip from the configured provider, or uses native JCEF/CEF from `java.library.path` when already present. |

The normal Release jar is intentionally large for offline/no-download
deployments. The `-portable` jar stays small and downloads only the current OS
runtime.

The default downloader resolves assets from:

```text
https://github.com/DanielTM999/java-cef/releases/download/v<version>/jcef-runtime-<platform>-<version>.zip
```

Set `jcef.orion.release.tag`, `jcef.orion.runtime.base-url`, or
`jcef.orion.runtime.url` to point the portable jar at another Release or mirror.
Applications can also install a Java provider with
`SystemBootstrap.setRuntimeDownloadProvider`. Download progress is reported
through `SystemBootstrap.setDownloadProgressListener`.

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

This local jar is the portable/base jar. In the Release workflow it is copied as
`jcef-orion-<version>-portable.jar`; the primary Release
`jcef-orion-<version>.jar` is produced later by `package-universal.sh` with
native runtimes embedded.

To assemble the offline jar with embedded native runtimes locally, first create
one or more native redistributables under `binary_distrib/<platform>`, then run:

```sh
scripts/package-universal.sh 1.0.0 dist binary_distrib
```

Local embedded packaging embeds only the platforms it finds. For example, if
your machine only has `binary_distrib/win64`, the jar will contain only the
Windows runtime. The GitHub Actions workflow sets
`REQUIRED_PLATFORMS="win64 linux64 macosx64"`, so the embedded Release jar fails
to publish unless all three runtimes are present.

## GitHub Actions Workflow

| Workflow | Trigger | What it does |
|---|---|---|
| `.github/workflows/native-binaries.yml` | manual, tag `v*` or `native-v*` | Builds native redistributables for `win64`, `linux64` and `macosx64`, publishes the embedded jar, the portable jar, runtime zips, and then deletes temporary Actions artifacts. |

Manual run example:

```sh
gh workflow run native-binaries.yml -r master -f version=1.0.0 -f release_tag=v1.0.0
```

## Verify A Release

```sh
sha256sum -c SHA256SUMS.txt
```

## How Orion Consumes The Fork

Put `jcef-orion-<version>.jar` ahead of jcefmaven's bundled `jcef.jar` on the
classpath or module path so the fork's `org.cef.*` classes win. That primary
jar embeds all supported runtimes and does not need downloads. Use
`jcef-orion-<version>-portable.jar` when you want the smaller jar that downloads
only the current OS runtime.

Optional progress/provider configuration:

```java
SystemBootstrap.setDownloadProgressListener((platform, url, read, total, percent) -> {
    System.out.printf("JCEF runtime %s: %.1f%%%n", platform, percent);
});

SystemBootstrap.setRuntimeDownloadProvider((version, platform) ->
        new java.net.URL("https://mirror.example/jcef-runtime-"
                + platform + "-" + version + ".zip"));
```

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
