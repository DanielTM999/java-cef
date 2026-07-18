#!/usr/bin/env bash
# Orion fork addition.
#
# Builds jcef-orion-<version>.jar with the Java API plus embedded native
# runtimes copied from binary_distrib/<platform>. By default it embeds every
# supported platform found locally. Set REQUIRED_PLATFORMS="win64 linux64
# macosx64" in CI to fail when any platform is missing.
#
# Usage:
#   scripts/package-universal.sh <version> [output-dir] [binary-distrib-dir]

set -euo pipefail

VERSION="${1:?Usage: package-universal.sh <version> [output-dir] [binary-distrib-dir]}"
OUT_DIR="${2:-dist}"
BINARY_DISTRIB_DIR="${3:-binary_distrib}"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "${ROOT_DIR}"

mkdir -p "${OUT_DIR}"

BASE_JAR="${OUT_DIR}/jcef-orion-${VERSION}.jar"
if [[ ! -f "${BASE_JAR}" ]]; then
  "${ROOT_DIR}/scripts/package-portable.sh" "${VERSION}" "${OUT_DIR}"
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

STAGING_DIR="${WORK_DIR}/universal"
mkdir -p "${STAGING_DIR}/org/cef/native"

copy_platform() {
  local platform="$1"
  local src
  local dst

  if [[ "${platform}" == "macosx64" ]]; then
    src="${BINARY_DISTRIB_DIR}/${platform}/bin/jcef_app.app"
    dst="${STAGING_DIR}/org/cef/native/${platform}/jcef_app.app"
  else
    src="${BINARY_DISTRIB_DIR}/${platform}/bin/lib/${platform}"
    dst="${STAGING_DIR}/org/cef/native/${platform}"
  fi

  if [[ ! -d "${src}" ]]; then
    if [[ " ${REQUIRED_PLATFORMS:-} " == *" ${platform} "* ]]; then
      echo "ERROR: required native distribution not found for ${platform}: ${src}" >&2
      exit 1
    fi
    echo "Skipping ${platform}: native distribution not found at ${src}"
    return 0
  fi

  mkdir -p "$(dirname "${dst}")"
  cp -aL "${src}" "${dst}"

  (
    cd "${STAGING_DIR}/org/cef/native/${platform}"
    find . -type f | sed 's#^\./##' | sort > MANIFEST
  )
  EMBEDDED_COUNT=$((EMBEDDED_COUNT + 1))
  echo "Embedded ${platform} runtime from ${src}"
}

EMBEDDED_COUNT=0
copy_platform win64
copy_platform linux64
copy_platform macosx64

if [[ "${EMBEDDED_COUNT}" -eq 0 ]]; then
  echo "ERROR: no native distributions were found under ${BINARY_DISTRIB_DIR}" >&2
  exit 1
fi

jar uf "${BASE_JAR}" -C "${STAGING_DIR}" org/cef/native

(
  cd "${OUT_DIR}"
  sha256sum "jcef-orion-${VERSION}.jar" \
            "jcef-orion-${VERSION}-sources.jar" \
            "jcef-orion-${VERSION}.pom" > SHA256SUMS.txt
)

echo "Universal jar built: ${BASE_JAR}"
