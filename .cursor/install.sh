#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly GRADLE_TAPI_MCP_VERSION="0.4.2"
readonly GRADLE_TAPI_MCP_SHA256="32ff51c6a0a900166f0355982239c671dc5af9949e7081514be1f90819829e01"
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

find_jdk_home() {
  local version="$1"
  local home
  for home in "/usr/lib/jvm/java-${version}-openjdk-"*; do
    if [[ -d "${home}/bin" && -x "${home}/bin/java" ]]; then
      echo "${home}"
      return 0
    fi
  done
  return 1
}

install_missing_jdks() {
  local packages=()
  find_jdk_home 17 >/dev/null || packages+=(openjdk-17-jdk)
  find_jdk_home 21 >/dev/null || packages+=(openjdk-21-jdk)

  if [[ ${#packages[@]} -eq 0 ]]; then
    return 0
  fi

  if ! command -v sudo >/dev/null 2>&1 || ! sudo -n true 2>/dev/null; then
    echo "sudo is required to install JDK packages: ${packages[*]}" >&2
    return 1
  fi

  echo "Installing JDK packages: ${packages[*]}..." >&2
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${packages[@]}"
}

ensure_jdks() {
  install_missing_jdks || return 1

  local jdk17_home jdk21_home
  if ! jdk17_home="$(find_jdk_home 17)"; then
    echo "JDK 17 is required for ./gradlew (Java toolchain in build.gradle.kts)." >&2
    return 1
  fi
  if ! jdk21_home="$(find_jdk_home 21)"; then
    echo "JDK 21 is required to run the MCP server JAR (see AGENTS.md)." >&2
    return 1
  fi

  export JAVA_HOME="${jdk17_home}"
  export PATH="${JAVA_HOME}/bin:${jdk21_home}/bin:${PATH}"
}

# Download the release JAR so the MCP server can drive this repo's build when needed.
ensure_jar
setup_gh_cli
ensure_jdks
