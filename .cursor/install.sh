#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly GRADLE_TAPI_MCP_VERSION="0.3.0"
readonly GRADLE_TAPI_MCP_SHA256="1899fb2730146b68280097a6f34dcedcc80cdc682f0887dd2ab6f2d0af8dc71d"
readonly INSTALL_DIR="${HOME}/.local/share/gradle-tapi-mcp-server"
readonly VERSIONED_JAR_NAME="gradle-tapi-mcp-server-${GRADLE_TAPI_MCP_VERSION}.jar"
readonly VERSIONED_JAR_PATH="${INSTALL_DIR}/${VERSIONED_JAR_NAME}"
readonly STABLE_JAR_PATH="${INSTALL_DIR}/gradle-tapi-mcp-server.jar"
readonly MAX_DOWNLOAD_ATTEMPTS=2

verify_jar_sha256() {
  local jar_path="$1"
  local actual
  actual="$(sha256sum "${jar_path}" | awk '{print $1}')"
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

setup_gh_cli() {
  local gh_source="/exec-daemon/gh"
  if [[ ! -x "${gh_source}" ]]; then
    echo "Warning: ${gh_source} not found; gh CLI will be unavailable in this session." >&2
    return 0
  fi

  if ! mkdir -p "${HOME}/.local/bin" 2>/dev/null; then
    echo "Warning: could not create ${HOME}/.local/bin; gh symlink skipped." >&2
  elif ! ln -sfn "${gh_source}" "${HOME}/.local/bin/gh" 2>/dev/null; then
    echo "Warning: could not symlink gh to ${HOME}/.local/bin/gh." >&2
  fi

  if [[ -w /usr/local/bin ]]; then
    if ! ln -sfn "${gh_source}" /usr/local/bin/gh 2>/dev/null; then
      echo "Warning: could not symlink gh to /usr/local/bin/gh." >&2
    fi
  fi

  if ! "${gh_source}" auth status >/dev/null 2>&1; then
    local token="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
    if [[ -n "${token}" ]]; then
      if ! "${gh_source}" auth login --hostname github.com --git-protocol https --with-token <<<"${token}"; then
        echo "Warning: gh auth login failed; gh CLI may be unavailable for API calls." >&2
        echo "Use ManagePullRequest for PRs, or set GH_TOKEN in Cursor Cloud Secrets." >&2
      fi
    else
      echo "Warning: gh is not authenticated and GH_TOKEN/GITHUB_TOKEN is unset." >&2
      echo "Set GH_TOKEN in Cursor Cloud Secrets, or use the ManagePullRequest tool for PRs." >&2
    fi
  fi

  if ! command -v gh >/dev/null 2>&1; then
    echo "Warning: gh is installed but not on PATH; use /exec-daemon/gh or ~/.local/bin/gh." >&2
  fi
}

ensure_jdk17() {
  if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q '"17\.'; then
    return 0
  fi

  local jdk17_home="/usr/lib/jvm/java-17-openjdk-amd64"
  if [[ -d "${jdk17_home}" ]]; then
    export JAVA_HOME="${jdk17_home}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
    return 0
  fi

  echo "JDK 17 is required for ./gradlew (Java toolchain in build.gradle.kts)." >&2
  echo "Install openjdk-17-jdk or set JAVA_HOME to a Java 17 installation." >&2
  return 1
}

# Download the release JAR before ./gradlew so the MCP server can drive this repo's build.
ensure_jar
setup_gh_cli
ensure_jdk17

cd "${REPO_ROOT}"
./gradlew --no-daemon build
