# Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license that
# can be found in the LICENSE file.
#
# Orion fork addition. See MODIFICATIONS.md.
#
# Installs the Orion fork's org.cef classes on top of the upstream
# me.friwi:jcef-api artifact that jcefmaven pulls in, WITHOUT rebuilding the
# native side. It:
#   1. compiles the fork's org.cef sources (minus the macOS-only package) to a
#      bytecode level compatible with the consuming project;
#   2. overlays ONLY the changed/new classes onto a copy of the upstream
#      jcef-api jar (everything else stays byte-for-byte identical, incl. macOS
#      and the native-bearing helper classes);
#   3. installs the result into the local Maven repository under a distinct
#      "-orion" version.
#
# The consuming project (Orion) then pins that version via <dependencyManagement>
# so the fork's Java classes win over jcefmaven's. Reverse by removing that
# dependencyManagement entry.
#
# Usage (from the java-cef repo root):
#   powershell -File scripts/install-orion-api.ps1
#   powershell -File scripts/install-orion-api.ps1 -Release 17 -OrionSuffix orion

param(
    [int]$Release = 17,
    [string]$OrionSuffix = "orion"
)

$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path "$PSScriptRoot\..").Path
$M2 = "$env:USERPROFILE\.m2\repository\me\friwi\jcef-api"

if (-not (Test-Path $M2)) {
    throw "me.friwi:jcef-api not found in local .m2 ($M2). Build Orion once so Maven downloads it first."
}

# Locate the upstream jcef-api jar (exclude sources/javadoc).
$upstreamJar = Get-ChildItem $M2 -Recurse -Filter "jcef-api-*.jar" |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } |
    Select-Object -First 1
if (-not $upstreamJar) { throw "Could not find the upstream jcef-api jar under $M2" }

$baseVersion = Split-Path (Split-Path $upstreamJar.FullName -Parent) -Leaf
$forkVersion = "$baseVersion-$OrionSuffix"
Write-Host "Upstream jcef-api version : $baseVersion"
Write-Host "Fork jcef-api version     : $forkVersion"

$jogl = (Get-ChildItem "$RepoRoot\third_party\jogamp\jar" -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'

# --- 1. Compile the fork's org.cef sources (minus macOS-only package). -------
$build = Join-Path $env:TEMP "orion-forkbuild"
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force $build | Out-Null
$src = Get-ChildItem "$RepoRoot\java\org\cef" -Recurse -Filter *.java |
    Where-Object { $_.FullName -notmatch '\\browser\\mac\\' } |
    ForEach-Object { $_.FullName }
Write-Host "Compiling org.cef with --release $Release ..."
& javac --release $Release -nowarn -cp $jogl -d $build $src
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# --- 2. Overlay changed/new classes onto a copy of the upstream jar. ---------
$stage = Join-Path $env:TEMP "orion-forkjar"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null
Push-Location $stage
& jar -xf $upstreamJar.FullName
Pop-Location

# Remove upstream copies of the classes we replace (all inner classes too).
Get-ChildItem "$stage\org\cef" -Filter "CefApp*.class" | Remove-Item -Force
Get-ChildItem "$stage\org\cef" -Filter "CefSettings*.class" | Remove-Item -Force

# Copy the fork's replacement + new classes in.
Copy-Item "$build\org\cef\CefApp*.class" "$stage\org\cef\" -Force
Copy-Item "$build\org\cef\CefSettings*.class" "$stage\org\cef\" -Force
Copy-Item "$build\org\cef\CefMainThread*.class" "$stage\org\cef\" -Force
Copy-Item "$build\org\cef\CefInitializationException.class" "$stage\org\cef\" -Force

# --- 3. Re-jar (preserve the original manifest) and install. -----------------
$outDir = Join-Path $RepoRoot "dist"
New-Item -ItemType Directory -Force $outDir | Out-Null
$forkJar = Join-Path $outDir "jcef-api-$forkVersion.jar"
if (Test-Path $forkJar) { Remove-Item -Force $forkJar }

Push-Location $stage
if (Test-Path "META-INF\MANIFEST.MF") {
    & jar -cmf "META-INF\MANIFEST.MF" $forkJar -C $stage .
} else {
    & jar -cf $forkJar -C $stage .
}
Pop-Location
Write-Host "Built fork jar: $forkJar"

Write-Host "Installing into local Maven repository ..."
& mvn -q install:install-file `
    "-Dfile=$forkJar" `
    "-DgroupId=me.friwi" `
    "-DartifactId=jcef-api" `
    "-Dversion=$forkVersion" `
    "-Dpackaging=jar"
if ($LASTEXITCODE -ne 0) { throw "mvn install:install-file failed" }

Write-Host ""
Write-Host "Done. Pin this in the consuming project's <dependencyManagement>:"
Write-Host "  <dependency>"
Write-Host "    <groupId>me.friwi</groupId>"
Write-Host "    <artifactId>jcef-api</artifactId>"
Write-Host "    <version>$forkVersion</version>"
Write-Host "  </dependency>"
Write-Host ""
Write-Host "FORK_JCEF_API_VERSION=$forkVersion"
