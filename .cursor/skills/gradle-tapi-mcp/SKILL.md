---
name: gradle-tapi-mcp
description: >-
  Use the gradle MCP server for token-efficient build verification in this repo.
  Prefer lightweight Tooling API queries before running tasks.
---

# Gradle Tooling API MCP

The `gradle` MCP server (release v0.8.0) is configured in `.cursor/mcp.json`. `.cursor/install.sh`
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
3. `gradle_get_project_overview` — project name and task counts (scope with `projectPath` on multi-module repos)
4. `gradle_run_tasks` with `["build"]` or `gradle_run_tests` when verification is needed
5. Optional: `gradle_index_dependency_sources` then `gradle_search_dependency_sources` (or `gradle_search_dependency_sources_multi` for several names), then `gradle_read_dependency_source` for a hit snippet (`line` + `contextLines=10`; omit `line` → `maxLines=200`; Idea dirs/`sourcePaths` need `sourceRoot`). `limit` / `perQueryLimit` omit = unlimited; `0` = empty (`per_query_limit` alias; `tokenMode=all` default)

Avoid `includeTasks=true` and heavy model queries unless necessary. On multi-module projects, pass `projectPath` (e.g. `:plugin`) to scope overview/model/invocation queries to a subproject subtree within the connected build only (not included/editable composite builds; use `gradle_get_gradle_build` for those). `gradle_run_tasks` omits stdout/stderr by default (`includeOutput=false`).

## MCP tool discovery (token-efficient)

When using Cursor **GetMcpTools** (some clients expose this as `mcp_get_tools`):

1. Prefer `server` + `toolName` for tools you will call, or `pattern` when the server id is unknown
2. Use **no arguments** (full catalog) only as a last resort — it is token-heavy
3. Avoid `server` without `toolName` unless you need every schema on that server

Full parameter docs: `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/reference.md`.

## Full tool reference

See `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/SKILL.md` and `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/reference.md` in this repository for the complete tool catalog and advanced workflows (background builds, multiple projects, test concurrency, output limits).

**Test concurrency:** Only one MCP build may **run** per `projectDirectory` at a time; the gate clears on terminal status (no grace). Batch multiple tests or Test tasks (`tasks` + `includePatterns`, including custom `JvmTestSuite` names like `fastTest`) in a single `gradle_run_tests` call. Parallel `background: true` calls for the same project enqueue (`queueIfBusy` defaults true; max 3 queued; `BUILD_QUEUE_FULL` when saturated). Foreground overlap or `queueIfBusy: false` returns `BUILD_ALREADY_RUNNING` with `error.activeBuildId` (and related fields) when the occupying build is known. Whole-suite Test tasks use `gradle_run_tasks` (selector-less `gradle_run_tests` returns `INVALID_ARGUMENT` with `hint`). Multi-module unscoped `testClasses`/`testMethods` infer `taskPath` when unambiguous (`taskPathInferred: true`); otherwise `INVALID_ARGUMENT` with up to 20 `suggestedTaskPaths`, optional `suggestedTaskPathsTruncated: true`, and `hint` (full list via `gradle_get_project_model` + `includeTasks=true`). Prefer short status polls over long `waitUntilComplete` waits (server wait is capped and independent of MCP client transport timeouts). Poll `gradle_get_build_status` **without** `includeOutput` while `status` is `running`; use `sinceStdoutOffset` / `sinceStderrOffset` for live logs. On `failed` + `GRADLE_TASK`, read capped `problems` (default when emitted); re-poll with `includeProblems: true` if missing — do not shell out. `includeOutput` tails often miss Kotlin `Compilation error. See log for more details`. For builds that may exceed ~30s, use `background: true` or rely on foreground auto-detach (~45s) which returns `buildId` to poll. Do not fall back to shell `./gradlew` on `BUILD_ALREADY_RUNNING`.
