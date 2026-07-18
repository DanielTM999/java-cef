#!/usr/bin/env bash
# Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license that
# can be found in the LICENSE file.
#
# Orion fork addition. See MODIFICATIONS.md.
#
# Builds the portable (OS-independent) distribution artifacts:
#   - jcef-orion-<version>.jar          (the Java API classes; runs on any OS)
#   - jcef-orion-<version>-sources.jar  (the matching sources)
#   - jcef-orion-<version>.pom          (a consumable Maven POM)
#   - SHA256SUMS.txt                    (checksums of the above)
#
# The heavy CEF runtime is NOT bundled: it is downloaded per-OS at runtime
# (jcefmaven-style). This script can be run locally exactly as CI runs it:
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

CLASSES_DIR="out/linux64"
if [[ ! -d "${CLASSES_DIR}/org" ]]; then
  echo "ERROR: compiled classes not found at ${CLASSES_DIR}/org." >&2
  echo "       Run 'tools/compile.sh linux64' first." >&2
  exit 1
fi

mkdir -p "${OUT_DIR}"

JAR_NAME="${ARTIFACT_ID}-${VERSION}.jar"
SOURCES_NAME="${ARTIFACT_ID}-${VERSION}-sources.jar"
POM_NAME="${ARTIFACT_ID}-${VERSION}.pom"

echo "==> Building portable jar: ${JAR_NAME}"
MANIFEST="${CLASSES_DIR}/manifest/MANIFEST.MF"
if [[ -f "${MANIFEST}" ]]; then
  jar -cmf "${MANIFEST}" "${OUT_DIR}/${JAR_NAME}" -C "${CLASSES_DIR}" org
else
  jar -cf "${OUT_DIR}/${JAR_NAME}" -C "${CLASSES_DIR}" org
fi

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
    Swing EDT. OS-independent Java API; the CEF runtime is provided per-OS at
    runtime (jcefmaven-style).</description>
  <licenses>
    <license>
      <name>BSD-3-Clause</name>
      <distribution>repo</distribution>
    </license>
  </licenses>
</project>
EOF

echo "==> Generating SHA256SUMS.txt"
(
  cd "${OUT_DIR}"
  sha256sum "${JAR_NAME}" "${SOURCES_NAME}" "${POM_NAME}" > SHA256SUMS.txt
)

echo "==> Done. Artifacts in ${OUT_DIR}:"
ls -la "${OUT_DIR}"
