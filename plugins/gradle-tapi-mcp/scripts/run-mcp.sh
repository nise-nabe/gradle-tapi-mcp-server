#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PLUGIN_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly RELEASE_JSON="${PLUGIN_ROOT}/server-release.json"
readonly INSTALL_DIR="${HOME}/.local/share/gradle-tapi-mcp-server"
readonly MAX_DOWNLOAD_ATTEMPTS=2

read_release_field() {
  local key="$1"
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' \
      "${RELEASE_JSON}" "${key}"
    return 0
  fi
  sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" "${RELEASE_JSON}" | head -1
}

if [[ ! -f "${RELEASE_JSON}" ]]; then
  echo "Missing ${RELEASE_JSON}" >&2
  exit 1
fi

readonly GRADLE_TAPI_MCP_VERSION="$(read_release_field version)"
readonly GRADLE_TAPI_MCP_SHA256="$(read_release_field sha256)"
if [[ -z "${GRADLE_TAPI_MCP_VERSION}" || -z "${GRADLE_TAPI_MCP_SHA256}" ]]; then
  echo "Could not read version/sha256 from ${RELEASE_JSON}" >&2
  exit 1
fi

readonly VERSIONED_JAR_NAME="gradle-tapi-mcp-server-${GRADLE_TAPI_MCP_VERSION}.jar"
readonly VERSIONED_JAR_PATH="${INSTALL_DIR}/${VERSIONED_JAR_NAME}"
readonly STABLE_JAR_PATH="${INSTALL_DIR}/gradle-tapi-mcp-server.jar"

file_sha256() {
  local jar_path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${jar_path}" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${jar_path}" | awk '{print $1}'
  else
    echo "Neither sha256sum nor shasum is available to verify the MCP JAR." >&2
    return 1
  fi
}

verify_jar_sha256() {
  local jar_path="$1"
  local actual
  actual="$(file_sha256 "${jar_path}")"
  if [[ "${actual}" != "${GRADLE_TAPI_MCP_SHA256}" ]]; then
    echo "SHA-256 mismatch for ${jar_path}" >&2
    echo "Expected: ${GRADLE_TAPI_MCP_SHA256}" >&2
    echo "Actual:   ${actual}" >&2
    return 1
  fi
}

download_jar() {
  mkdir -p "${INSTALL_DIR}"
  local tmp
  tmp="$(mktemp "${INSTALL_DIR}/.${VERSIONED_JAR_NAME}.XXXXXX")"

  if ! curl -fsSL -o "${tmp}" \
    "https://github.com/nise-nabe/gradle-tapi-mcp-server/releases/download/v${GRADLE_TAPI_MCP_VERSION}/${VERSIONED_JAR_NAME}"; then
    rm -f "${tmp}"
    echo "curl failed to download ${VERSIONED_JAR_NAME}" >&2
    return 1
  fi

  mv -f "${tmp}" "${VERSIONED_JAR_PATH}"
}

remove_jar_artifacts() {
  rm -f "${VERSIONED_JAR_PATH}" "${STABLE_JAR_PATH}"
}

ensure_jar() {
  if [[ -f "${VERSIONED_JAR_PATH}" ]] && verify_jar_sha256 "${VERSIONED_JAR_PATH}"; then
    ln -sfn "${VERSIONED_JAR_NAME}" "${STABLE_JAR_PATH}"
    return 0
  fi

  if [[ -f "${VERSIONED_JAR_PATH}" ]]; then
    echo "Removing corrupted MCP server JAR for re-download..." >&2
  fi
  remove_jar_artifacts

  local attempt
  for attempt in $(seq 1 "${MAX_DOWNLOAD_ATTEMPTS}"); do
    if download_jar && verify_jar_sha256 "${VERSIONED_JAR_PATH}"; then
      ln -sfn "${VERSIONED_JAR_NAME}" "${STABLE_JAR_PATH}"
      return 0
    fi
    echo "Download attempt ${attempt}/${MAX_DOWNLOAD_ATTEMPTS} failed." >&2
    remove_jar_artifacts
  done

  echo "Failed to download a valid MCP server JAR after ${MAX_DOWNLOAD_ATTEMPTS} attempts." >&2
  return 1
}

if ! command -v java >/dev/null 2>&1; then
  echo "java is required to launch gradle-tapi-mcp-server (Java 17+)." >&2
  exit 1
fi

ensure_jar
export GRADLE_PROJECT_DIR="${GRADLE_PROJECT_DIR:-${PWD}}"
exec java -jar "${STABLE_JAR_PATH}"
