#!/usr/bin/env bash
# Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license that
# can be found in the LICENSE file.
#
# Orion fork addition. See MODIFICATIONS.md.
#
# Builds the Java API distribution artifacts:
#   - jcef-orion-<version>.jar          (the Java API classes)
#   - jcef-orion-<version>-sources.jar  (the matching sources)
#   - jcef-orion-<version>.pom          (a consumable Maven POM)
#   - SHA256SUMS.txt                    (checksums of the above)
#
# This script does not bundle native runtimes. Release builds publish matching
# jcef-runtime-<platform>-<version>.zip assets that this jar can download at
# runtime. Use package-universal.sh after producing binary_distrib/<platform>
# outputs to build the optional offline embedded jar.
#
#   tools/compile.sh linux64
#   scripts/package-portable.sh 146.0.0 dist
#
# Usage: scripts/package-portable.sh <version> [output-dir]

set -euo pipefail

VERSION="${1:?Usage: package-portable.sh <version> [output-dir]}"
OUT_DIR="${2:-dist}"

GROUP_ID="io.github.danieltm999"
ARTIFACT_ID="jcef-orion"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "${ROOT_DIR}"

CLASSES_DIR="${JCEF_CLASSES_DIR:-}"
if [[ -z "${CLASSES_DIR}" ]]; then
  for candidate in out/linux64 out/win64 out/macosx64 out/linux32 out/win32; do
    if [[ -d "${candidate}/org" ]]; then
      CLASSES_DIR="${candidate}"
      break
    fi
  done
fi
if [[ ! -d "${CLASSES_DIR}/org" ]]; then
  echo "ERROR: compiled classes not found." >&2
  echo "       Run 'tools/compile.sh linux64' or 'tools\\compile.bat win64' first." >&2
  echo "       You can also set JCEF_CLASSES_DIR=out/<platform>." >&2
  exit 1
fi
echo "==> Using compiled classes from ${CLASSES_DIR}"

mkdir -p "${OUT_DIR}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

JAR_NAME="${ARTIFACT_ID}-${VERSION}.jar"
SOURCES_NAME="${ARTIFACT_ID}-${VERSION}-sources.jar"
POM_NAME="${ARTIFACT_ID}-${VERSION}.pom"

echo "==> Building portable jar: ${JAR_NAME}"
MANIFEST="${CLASSES_DIR}/manifest/MANIFEST.MF"
GENERATED_MANIFEST="${OUT_DIR}/MANIFEST.MF"
if [[ -f "${MANIFEST}" ]]; then
  cp "${MANIFEST}" "${GENERATED_MANIFEST}"
else
  printf 'Manifest-Version: 1.0\n' > "${GENERATED_MANIFEST}"
fi
printf 'Implementation-Title: JCEF Orion\n' >> "${GENERATED_MANIFEST}"
printf 'Implementation-Version: %s\n' "${VERSION}" >> "${GENERATED_MANIFEST}"
printf 'Implementation-Vendor: Orion\n' >> "${GENERATED_MANIFEST}"
jar -cmf "${GENERATED_MANIFEST}" "${OUT_DIR}/${JAR_NAME}" -C "${CLASSES_DIR}" org
rm -f "${GENERATED_MANIFEST}"

echo "==> Embedding JOGL/GlueGen dependency jars"
DEPS_DIR="${WORK_DIR}/deps"
mkdir -p "${DEPS_DIR}"
for dep_jar in third_party/jogamp/jar/*.jar; do
  (cd "${DEPS_DIR}" && jar xf "${ROOT_DIR}/${dep_jar}")
done
rm -f "${DEPS_DIR}/META-INF/MANIFEST.MF"
jar uf "${OUT_DIR}/${JAR_NAME}" -C "${DEPS_DIR}" .

echo "==> Building sources jar: ${SOURCES_NAME}"
jar -cf "${OUT_DIR}/${SOURCES_NAME}" -C java org

echo "==> Generating POM: ${POM_NAME}"
cat > "${OUT_DIR}/${POM_NAME}" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>${GROUP_ID}</groupId>
  <artifactId>${ARTIFACT_ID}</artifactId>
  <version>${VERSION}</version>
  <packaging>jar</packaging>
  <name>JCEF (Orion fork)</name>
  <description>Java Chromium Embedded Framework, Orion fork with a dedicated CEF
    initialization thread (DEDICATED_CEF_THREAD) so native init never blocks the
    Swing EDT. This lightweight jar embeds JOGL/GlueGen classes and downloads
    the current OS native runtime from jcef-runtime-&lt;platform&gt;-&lt;version&gt;.zip
    Release assets when needed. Use the -embedded jar asset for offline
    deployments without runtime downloads.</description>
  <url>https://github.com/DanielTM999/java-cef</url>
  <licenses>
    <license>
      <name>BSD-3-Clause</name>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>https://github.com/DanielTM999/java-cef</url>
    <connection>scm:git:https://github.com/DanielTM999/java-cef.git</connection>
  </scm>
</project>
EOF

echo "==> Generating SHA256SUMS.txt"
(
  cd "${OUT_DIR}"
  sha256sum "${JAR_NAME}" "${SOURCES_NAME}" "${POM_NAME}" > SHA256SUMS.txt
)

echo "==> Done. Artifacts in ${OUT_DIR}:"
ls -la "${OUT_DIR}"
