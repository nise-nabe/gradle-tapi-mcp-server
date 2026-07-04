#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly INSTALL_DIR="${HOME}/.local/share/gradle-tapi-mcp-server"
readonly STABLE_JAR_PATH="${INSTALL_DIR}/gradle-tapi-mcp-server.jar"

ensure_jdk17() {
  if [[ -n "${JAVA_HOME:-}" ]] && java -version 2>&1 | grep -q '"17\.'; then
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

resolve_versioned_jar() {
  local jar
  jar="$(find "${REPO_ROOT}/build/libs" -maxdepth 1 -name 'gradle-tapi-mcp-server-*.jar' ! -name '*-plain.jar' -print -quit)"
  if [[ -z "${jar}" ]]; then
    echo "Fat JAR not found under ${REPO_ROOT}/build/libs" >&2
    return 1
  fi
  printf '%s\n' "${jar}"
}

ensure_jar() {
  cd "${REPO_ROOT}"
  ./gradlew --no-daemon jar

  local versioned_jar
  versioned_jar="$(resolve_versioned_jar)"
  mkdir -p "${INSTALL_DIR}"
  ln -sfn "${versioned_jar}" "${STABLE_JAR_PATH}"
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

ensure_jdk17
ensure_jar
setup_gh_cli

cd "${REPO_ROOT}"
./gradlew --no-daemon build
