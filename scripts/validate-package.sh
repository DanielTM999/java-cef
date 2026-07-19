#!/usr/bin/env bash
# Copyright (c) 2024 The Chromium Embedded Framework Authors. All rights
# reserved. Use of this source code is governed by a BSD-style license that
# can be found in the LICENSE file.
#
# Orion fork addition. See MODIFICATIONS.md.
#
# Validates a portable distribution produced by package-portable.sh:
#   - required files exist (jar, sources jar, pom, checksums)
#   - checksums verify
#   - the jar actually contains the fork's classes (loaded from a clean copy)
#
# Usage: scripts/validate-package.sh <version> [dist-dir]

set -euo pipefail

VERSION="${1:?Usage: validate-package.sh <version> [dist-dir]}"
DIST_DIR="${2:-dist}"
ARTIFACT_ID="jcef-orion"

JAR="${ARTIFACT_ID}-${VERSION}.jar"
SOURCES="${ARTIFACT_ID}-${VERSION}-sources.jar"
POM="${ARTIFACT_ID}-${VERSION}.pom"

fail() {
  echo "Packaging validation failed: $1" >&2
  exit 1
}

cd "${DIST_DIR}"

for f in "${JAR}" "${SOURCES}" "${POM}" "SHA256SUMS.txt"; do
  [[ -f "${f}" ]] || fail "required file ${f} was not found"
done

echo "==> Verifying checksums"
sha256sum -c SHA256SUMS.txt

echo "==> Verifying the jar contains the fork's classes"
for cls in org/cef/CefApp.class \
           org/cef/CefMainThread.class \
           org/cef/CefInitializationException.class \
           org/cef/CefSettings\$CefInitializationMode.class; do
  if ! jar -tf "${JAR}" | grep -qx "${cls}"; then
    fail "class ${cls} missing from ${JAR}"
  fi
done

echo "==> Extracting to a clean directory and loading a fork class"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
cp "${JAR}" "${WORK}/"
CP_SEP=":"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) CP_SEP=";" ;;
esac
cat > "${WORK}/Check.java" <<'EOF'
import org.cef.CefApp;
public class Check {
    public static void main(String[] a) {
        // Pure-Java call: proves the fork classes load with no native runtime.
        System.out.println("resolveInitializationMode(null)="
                + CefApp.resolveInitializationMode(null));
    }
}
EOF
( cd "${WORK}" && javac -cp "${JAR}" Check.java && java -cp ".${CP_SEP}${JAR}" Check )

echo "All package validations passed for version ${VERSION}."
