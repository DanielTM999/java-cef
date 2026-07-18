# Building & packaging the JCEF Orion fork

This document covers the **Orion fork** additions: the portable jar, the CI/CD
workflows, and how the Orion IDE consumes the result. For the full native JCEF
build (CEF download, JNI, per-OS toolchains) see the upstream docs and the
top-level `CLAUDE.md`.

## The two-layer model (why the jar is portable)

JCEF is Java + native. The Orion fork changes **only the Java layer** (a
dedicated CEF owner thread + async init APIs — see `MODIFICATIONS.md`). That has
an important consequence:

| Layer | OS-specific? | Who provides it |
|---|---|---|
| `jcef-orion.jar` (the `org.cef.*` classes) | **No** — pure Java | This fork's build |
| `libjcef` / `jcef.dll` + CEF runtime (`libcef`, `*.pak`, `icudtl.dat`, `locales/`) | Yes (~200 MB/OS) | Downloaded per-OS at runtime by `jcefmaven` |

Because the fork adds **no native methods**, its jar is a drop-in replacement for
the upstream `org.cef` classes and is byte-for-byte identical on every OS. You
can take `jcef-orion.jar` to Windows, Linux or macOS and it runs — the heavy,
OS-specific CEF runtime is fetched on first use, exactly as the Orion IDE already
does today.

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

## GitHub Actions workflows

| Workflow | Trigger | What it does |
|---|---|---|
| `.github/workflows/ci.yml` | push (master/main), PR, manual | Compiles Java, runs unit tests, builds & uploads the portable jar. `contents: read` only. |
| `.github/workflows/package.yml` | manual (`workflow_dispatch`) | Builds + validates the portable distribution and uploads it as an artifact (no release). |
| `.github/workflows/release.yml` | tag `v*`, manual | Builds the portable artifacts, verifies checksums, and publishes a GitHub Release with the jar, sources jar, POM and `SHA256SUMS.txt`. `contents: write` only in the release job. |
| `.github/workflows/native-binaries.yml` | manual, tag `native-v*` | Builds the portable jar plus per-OS JCEF redistributables (`win64`, `linux64`, `macosx64`), publishes them to a GitHub Release, optionally commits them to `vendor/jcef`, then deletes temporary Actions artifacts. |

### Cut a release

The version is taken from the git tag (single source of truth):

```sh
git tag v146.0.0
git push origin v146.0.0
```

The `v` prefix is stripped for artifact names (`jcef-orion-146.0.0.jar`).

### Run a workflow manually

Use the **Run workflow** button on the Actions tab (or the `gh` CLI):

```sh
gh workflow run package.yml -f version=146.0.0-rc1
```

### Build native release assets

The `Native Binaries` workflow mirrors the SwingTools-style native packaging
flow: it builds the portable `jcef-orion-1.0.0.jar`, builds `win64`, `linux64`
and `macosx64` in a matrix, uploads each output as a temporary artifact,
publishes those files to a GitHub Release, optionally commits the generated
files into `vendor/jcef`, then deletes the temporary Actions artifacts for that
run.

Run it manually from GitHub Actions with a release tag such as
`native-v1.0.0`. The release will contain the portable jar, sources jar, POM,
checksums and one native redistributable archive per platform.

Keep `commit_binaries=true` when running manually if you want the workflow to
push the generated files back to the selected branch. The committed files live
under `vendor/jcef/java`, `vendor/jcef/win64`, `vendor/jcef/linux64` and
`vendor/jcef/macosx64`, and are tracked with Git LFS because CEF binaries are
too large for normal Git blobs.

Tagging `native-v*` runs the release flow only; it does not commit generated
binaries back to a branch.

### Verify a downloaded release

```sh
sha256sum -c SHA256SUMS.txt
```

## How the Orion IDE consumes the fork

Orion already uses `me.friwi.jcefmaven.CefAppBuilder`, which downloads the CEF
runtime per-OS. To activate the fork's behavior:

1. Put **`jcef-orion.jar` ahead of** jcefmaven's bundled `jcef.jar` on the
   classpath (or module path) so the fork's `org.cef.*` classes win. jcefmaven
   still downloads the matching CEF 146 native runtime for the current OS.
2. Select the dedicated mode on Windows/Linux before initializing:

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
