---
name: gradle-tapi-mcp
description: >-
  Use the gradle MCP server for token-efficient build verification in this repo.
  Prefer lightweight Tooling API queries before running tasks.
---

# Gradle Tooling API MCP

The `gradle` MCP server (release v0.5.1) is configured in `.cursor/mcp.json`. `.cursor/install.sh`
downloads the release JAR to `~/.local/share/gradle-tapi-mcp-server/`, verifies its SHA-256, and
exposes it via a stable `gradle-tapi-mcp-server.jar` symlink.
`GRADLE_PROJECT_DIR` is set to the workspace root.

Do not point MCP at a JAR built from this workspace during Cloud Agent bootstrap — the Gradle build
that compiles the server cannot use MCP while that same project is being built.

When developing server code locally, rebuild with `./gradlew jar` and restart MCP with the local
JAR only after you need to test server changes (not during `./gradlew build` of this repo).

## Workflow (token-efficient)

1. `gradle_connection_status` — confirm connected (`runtimeStackAvailable=true` shows `gradleVersion` / `javaHome`; when false, call with `refresh: true` or use `gradle_get_build_environment`; listing all connections with `refresh: true` fetches per project)
2. `gradle_get_build_environment` — resolved Gradle/Java versions
3. `gradle_get_project_overview` — project name and task counts (single-module repo)
4. `gradle_run_tasks` with `["build"]` or `gradle_run_tests` when verification is needed

Avoid `includeTasks=true` and heavy model queries unless necessary. `gradle_run_tasks` omits stdout/stderr by default (`includeOutput=false`).

## MCP tool discovery (token-efficient)

When using Cursor `mcp_get_tools`:

1. Call with **no arguments** first (catalog: names + short descriptions)
2. Fetch full schema with `server` + `toolName` only for tools you will call
3. Avoid `server` without `toolName` unless you need every schema (~3.5k tokens after slimming, was ~7k)

Full parameter docs: `skills/gradle-tapi-mcp/reference.md`.

## Full tool reference

See `skills/gradle-tapi-mcp/SKILL.md` and `skills/gradle-tapi-mcp/reference.md` in this repository for the complete tool catalog and advanced workflows (background builds, multiple projects, test concurrency, output limits).

**Test concurrency:** Only one MCP build per `projectDirectory` at a time; the gate clears on terminal status (no grace). Batch multiple tests or Test tasks (`tasks` + `includePatterns`, including custom `JvmTestSuite` names like `fastTest`) in a single `gradle_run_tests` call; parallel separate calls for the same project return `BUILD_ALREADY_RUNNING`. Prefer short status polls over long `waitUntilComplete` waits (server wait is capped and independent of MCP client transport timeouts).
