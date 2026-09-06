# Gradle Tooling API MCP Server

MCP server that exposes [Gradle Tooling API](https://docs.gradle.org/current/userguide/third_party_integration.html) operations to Cursor and other MCP clients.

## Build

```bash
./gradlew jar
```

The fat JAR is written to `build/libs/gradle-tapi-mcp-server-0.8.0.jar`.

## Plugin marketplace

This repository is a plugin marketplace for Cursor, Codex, and GitHub Copilot. It publishes the `gradle-tapi-mcp` plugin (Gradle Tooling API MCP server plus the token-efficient agent skill).

Requires Java 17+ and `bash`. The plugin launcher downloads the release JAR to `~/.local/share/gradle-tapi-mcp-server/` (same location as `.cursor/install.sh`).

### Cursor

Add this Git repository as a plugin marketplace from Customize → Plugins (or a team marketplace). The catalog is `.cursor-plugin/marketplace.json`.

### Codex

```bash
codex plugin marketplace add nise-nabe/gradle-tapi-mcp-server
```

The catalog is `.agents/plugins/marketplace.json`.

### GitHub Copilot CLI

```bash
copilot plugin marketplace add nise-nabe/gradle-tapi-mcp-server
```

The catalog is `.github/plugin/marketplace.json`.

Then install the `gradle-tapi-mcp` plugin from that marketplace.

## Cursor configuration

Add to `.cursor/mcp.json` in your Gradle project:

```json
{
  "mcpServers": {
    "gradle": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/gradle-tapi-mcp-server/build/libs/gradle-tapi-mcp-server-0.8.0.jar"
      ],
      "env": {
        "GRADLE_PROJECT_DIR": "${workspaceFolder}"
      }
    }
  }
}
```

## Environment variables

| Variable | Description |
|----------|-------------|
| `GRADLE_PROJECT_DIR` | Default project; auto-connect on startup |
| `GRADLE_USER_HOME` | Optional Gradle user home |
| `GRADLE_VERSION` | Optional explicit Gradle version |
| `GRADLE_INSTALLATION` | Optional local Gradle installation |

## Tools

| Tool | Description |
|------|-------------|
| `gradle_connect` | Connect to a project directory (keeps other projects connected) |
| `gradle_connection_status` | Connection state for one project or all active connections (`connections[]`, `defaultProjectDirectory`). Cache-only by default; set `refresh: true` when `runtimeStackAvailable` is false and you need `gradleVersion` / `javaHome`. Listing all connections with `refresh: true` fetches once per connected project |
| `gradle_disconnect` | Close one project (`projectDirectory`) or all connections (omit) |
| `gradle_get_build_environment` | Gradle/Java environment including `javaVersion` and `versionInfo` (`gradle --version` text on Gradle 9.4+; lightweight) |
| `gradle_get_java_runtimes` | Daemon Java from BuildEnvironment plus detected local JDKs via `javaToolchains -q` (set `includeToolchains: false` for daemon only) |
| `gradle_get_help` | Gradle CLI help text (`gradle --help` equivalent); optional `maxChars` / `tailOutput`; requires Gradle 9.4+ |
| `gradle_get_build_cache_status` | Build cache / configuration cache settings and local cache summaries |
| `gradle_get_project_overview` | Project hierarchy and task counts only; optional `projectPath`, `maxDepth` / `maxChildren` |
| `gradle_get_gradle_build` | GradleBuild structure (root project tree, all projects, included/editable builds); optional `maxDepth` / `maxChildren` |
| `gradle_get_project_model` | Project model; tasks omitted by default; optional `projectPath` to scope a subproject subtree |
| `gradle_get_build_invocations` | Runnable tasks; selectors omitted by default; optional `projectPath` to scope task collection |
| `gradle_get_project_publications` | Publications |
| `gradle_run_tasks` | Execute tasks; stdout/stderr truncated by default |
| `gradle_run_tests` | Execute JVM tests by class, method, pattern, or task scope; stdout/stderr truncated by default |
| `gradle_list_builds` | List recent MCP builds from memory and `.gradle/mcp-builds/` (no Tooling API required) |
| `gradle_get_build_status` | Poll status/output for a background build (`buildId` required); set `includeProgress: true` for detailed progress |
| `gradle_cancel_build` | Cancel a background build via Tooling API `CancellationToken` (`buildId` required) |
| `gradle_index_dependency_sources` | Index dependency sources for exact simple-name locate. `tokenMode`: `all` (default; includes comments/strings) or `idents`. Keep-set: Idea sources by default, or explicit `artifacts[]` / `sourcePaths[]`. Persists under `.gradle/mcp-dependency-sources/<tokenMode>/` |
| `gradle_search_dependency_sources` | Exact simple-name locate against a prior index (does not reindex). Optional `limit` (omit = unlimited; `0` = empty) |
| `gradle_search_dependency_sources_multi` | Multi-name OR locate with dedup and `matchedQueries`; optional `limit` and `perQueryLimit` (`per_query_limit` alias) |

## Modules

| Project | Role |
|---------|------|
| root (`gradle-tapi-mcp-server`) | MCP server fat JAR; registers tools |
| `:dependency-sources-core` | Identifier lexer, δ postings, keep-set resolver, on-disk index |
| `:dependency-sources-mcp` | Index/search tool schemas and facade (depends on core) |

### Dependency sources name locate

1. Call `gradle_index_dependency_sources` (optional `tokenMode`, `artifacts`, `sourcePaths`, `forceReindex`).
2. Call `gradle_search_dependency_sources` with `query` (and matching `tokenMode` when not preferring the default `all` index).
3. For several names at once, call `gradle_search_dependency_sources_multi` with `queries`.

`limit` / `perQueryLimit` are query-time only (no `formatVersion` bump): omit or null = unlimited; `0` = empty. `per_query_limit` is accepted as an alias for `perQueryLimit`. Single search returns hits in posting order; multi search merges, dedups, sorts by `(gav, path, line, column)`, then applies the overall `limit`.

Index format is versioned (`formatVersion`); incompatible on-disk indexes are rebuilt. Fingerprint includes `tokenMode` and the keep-set so modes never share an index.

## Token-efficient usage

Prefer this order for agent workflows such as project context ingestion:

1. `gradle_get_build_environment` for resolved Gradle/Java versions
2. `gradle_get_project_overview` for module hierarchy and task counts (or `gradle_get_gradle_build` for composite/includeBuild repositories)
3. `gradle_run_tasks` with `["build"]` or `["test"]` when verification is needed

Use heavier tools only when required:

- `gradle_get_project_model` with `includeTasks=true` to list tasks
- `includeTaskDetails=true` only when descriptions are needed
- `taskGroup`, `taskNamePrefix`, or `maxTasks` to narrow large builds
- `projectPath` on overview/model/invocations to scope a subproject subtree (e.g. `:plugin`) instead of the full monorepo. Resolves within the connected build's `GradleProject` tree only; use `gradle_get_gradle_build` for included/editable composite builds. Scoped `taskSelectors` omit names shared with sibling subprojects; prefer scoped `tasks` paths for invocation targets.
- `maxDepth` / `maxChildren` on overview/model queries for large monorepos
- `gradle_get_build_invocations` with `includeTaskSelectors=true` only when selectors matter

`gradle_run_tasks` and `gradle_run_tests` omit `stdout`/`stderr` by default (`includeOutput=false`) and return `outcome`, `buildSummary`, and failure fields only—no task log noise such as `UP-TO-DATE`. Set `includeOutput=true` to include captured streams (truncated per `maxOutputChars`, default `8000`; CRLF normalized to LF).

Foreground responses include `outcome` (`SUCCESS` / `FAILED`) and `buildSummary` (parsed Gradle summary lines). Completed builds also include `failedTaskCount`, `failedTasks` (from Tooling API progress events), and `buildSummary.failureSummary` (parsed `> Task ... FAILED` / test failure lines from stdout). Set `includeProgress: true` to include the full progress object; default is omitted for token efficiency.

Tune captured output with `includeOutput` (default `false`), `maxOutputChars` (default `8000` when included), and `tailOutput` (default `true`).

Tool errors return structured JSON: `{ "error": { "code": "NOT_CONNECTED", "message": "..." } }`.

Build task failures are **not** tool errors: `gradle_run_tasks` / `gradle_run_tests` return `status: "failed"` / `outcome: "FAILED"` in the success payload (`isError=false`). Reserve `BUILD_FAILED` for tooling failures (for example `javaToolchains` probe errors on `gradle_get_java_runtimes`).

## Multiple projects

One MCP server process can hold **multiple Gradle project connections** at once.

1. `gradle_connect` with each project root (does not disconnect other projects)
2. Pass optional `projectDirectory` on query/build tools. When omitted, the server uses the default connected project, then `GRADLE_PROJECT_DIR` when set and connected, or the sole connected project when the workspace env is unset or not connected. With **multiple** connections and no usable workspace default, `projectDirectory` is **required**.
3. `gradle_connection_status` without arguments returns `connections[]` plus legacy flat fields for the default project
4. `gradle_disconnect` with `projectDirectory` closes one project; omit to close all
5. Background builds are scoped per project; only one MCP build may run per `projectDirectory` at a time (concurrent builds across different projects share the global pool limit). Do not run shell `./gradlew` in parallel on the same checkout while an MCP build is active—especially IntelliJ Platform `:plugin:test`, which competes for the same test sandbox and can hang or corrupt state.

Example:

```json
{ "projectDirectory": "/path/to/other-repo", "tasks": ["build"], "background": true }
```

## Long-running builds

For slow `build` or `test` runs, pass `background: true` to `gradle_run_tasks` or `gradle_run_tests`. The tool returns immediately with a `buildId`. Only one MCP build may run per `projectDirectory` at a time; a second `background: true` run enqueues (`status: queued`, max 3 queued per project; `queueIfBusy` defaults true when `background` is true). Pass `queueIfBusy: false` to reject with `BUILD_ALREADY_RUNNING` instead. Concurrent builds across different projects are allowed up to a server-side limit. Do not run shell `./gradlew` on the same checkout while an MCP build is active (IntelliJ Platform `:plugin:test` sandboxes are especially sensitive). Call `gradle_cancel_build` with that `buildId` to stop an unneeded background run. Poll `gradle_get_build_status` with that `buildId` (required) to read:

- `status`: `queued`, `running`, `succeeded`, `failed`, or `cancelled`
- `statusSource`: `memory` (in-process record) or `disk` (`.gradle/mcp-builds/<buildId>/`)
- `outcome` and `buildSummary` when the build has finished
- `progress` (only when `includeProgress: true`): capped task lists and recent events; running polls merge in-memory progress with disk `events.ndjson` when available
- `problems` on terminal `failed` + `failureCategory: GRADLE_TASK` when the Problems API emitted them (capped; no `includeProblems` needed). Set `includeProblems: true` for `liveProblems` while running, or to re-poll if terminal `problems` is missing. Do not re-run the task via CLI just to read compiler output.
- `recordDirectory`: path to `.gradle/mcp-builds/<buildId>/` (included during running polls when disk artifacts exist)
- `stdout`/`stderr` only when `includeOutput: true` — live partial output while running only when the MCP server still holds the in-memory record; disk-only polls return streams after MCP finalizes logs at build end. Tails often miss Kotlin compiler diagnostics (`Compilation error. See log for more details`); prefer `problems` on `GRADLE_TASK` failure
- optional `projectDirectory` when the in-memory record was evicted and the connected project differs (disk-only lookup)
- `sinceStdoutOffset` / `sinceStderrOffset` with `includeOutput: true` for incremental `stdoutDelta` / `stderrDelta` polling (avoids re-reading prior log prefixes)
- `waitUntilComplete: true` with optional `waitTimeoutMs` (default `30000`, max `60000`) / `pollIntervalMs` for a **short server-side** wait. This wait is independent of the MCP client/host request timeout: do not rely on one long wait for multi-minute builds. Prefer plain polls (`waitUntilComplete` omitted/false) or short waits; on timeout the response includes `waitTimedOut`, `waitedMs`, and a `hint` to poll again. Non-wait status polls read memory/disk only and never block on the Tooling API.

Build records persist under `.gradle/mcp-builds/<buildId>/`. Terminal status prefers `gradle-result.json` (Gradle init script) over stale MCP memory. When Gradle still reports `running` but MCP already finalized (e.g. disconnect) and `events.ndjson` shows no activity after MCP's `finishedAt`, MCP's terminal status is used instead (daemon likely dead). Terminal `buildSummary` follows the winning terminal authority: when Gradle's `gradle-result.json` is terminal, parse the fullest available stdout (`stdout.log` and any richer in-memory capture); stale `mcp-result.json` summaries are ignored. When MCP finalized the build, use `mcp-result.json`, then parsed `stdout.log` as fallback. Gradle-terminal failed builds include `failedTaskCount` / `failedTasks` from `events.ndjson` when present. Failed test runs also expose structured `testFailures` (class, method, exception, source line) and `failedTestCount` without enabling full stdout. Terminal failures also include `failureKind` / `failureCategory` (`TEST`, `GRADLE_TASK`, `TOOLING_CONNECTION`, `CANCELLED`) for agent branching.

The single-flight gate (one MCP build per `projectDirectory`) releases as soon as the build reaches a terminal status in memory—there is no grace window. Foreground starts still need a terminal poll or cancel before the next foreground call. Parallel `background: true` calls for the same project enqueue (`status: queued`, max 3; `BUILD_QUEUE_FULL` when saturated). Foreground overlap or `queueIfBusy: false` returns `BUILD_ALREADY_RUNNING` with `activeBuildId`, `activeKind`, `activeStatus`, and task/test selection fields when the occupying build is known (`activeStatus` may be `queued`). Global pool saturation returns `activeBuildIds` when multiple builds are running.

### `gradle_run_tests` selectors

| Goal | Use |
|------|-----|
| One Test task + class list | `taskPath` + `testClasses` |
| One Test task + method map | `taskPath` + `testMethods` |
| Custom `JvmTestSuite` (e.g. `fastTest`) | Same as above with `taskPath: ":mod:fastTest"`, or `tasks: [":mod:fastTest"]` + `includePatterns` |
| Several Test tasks in **one** MCP build | `tasks: [":mod:test", ":mod:fastTest"]` + `includePatterns` |
| Whole Test task / suite (no class filter) | `gradle_run_tasks` with `tasks: [":mod:test"]` — not selector-less `gradle_run_tests` |
| Multi-project without scoping | Infers `taskPath` when unambiguous (`taskPathInferred: true`); else `INVALID_ARGUMENT` with `suggestedTaskPaths` (capped) and `hint` |

Exactly one of `testClasses`, `testMethods`, or `includePattern(s)` is required. Patterns require `tasks`. `gradle_run_tests` with only `taskPath` / `tasks` returns `INVALID_ARGUMENT` plus a `hint` to call `gradle_run_tasks` for the whole suite.

## Agent workflows

Key defaults for MCP clients and agents (full detail in `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/SKILL.md` and `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/reference.md`):

- **Long builds:** pass `background: true` for runs that may exceed ~30s. Foreground `gradle_run_tasks` / `gradle_run_tests` auto-detach after ~45s with `detached: true` and a `buildId` to poll.
- **Status polling:** call `gradle_get_build_status` without `includeOutput` while `status` is `running`. Use `sinceStdoutOffset` / `sinceStderrOffset` for live logs. On terminal failure, read `testFailures` / `buildSummary` / `problems` / `failureCategory`. On `status: failed` + `failureCategory: GRADLE_TASK`, use capped `problems` (included by default when emitted). Re-poll with `includeProblems: true` if `problems` is missing; do not re-run the task via CLI for compiler output. `includeOutput` tails often miss Kotlin compiler diagnostics (`Compilation error. See log for more details`).
- **Multi-project tests:** `gradle_run_tests` infers `taskPath` for unscoped `testClasses` / `testMethods` when a unique JVM Test task matches (package-suffix tokens vs subproject path segments); success sets `taskPathInferred: true`. When ambiguous or unmatched, the error is `INVALID_ARGUMENT` with up to 20 `suggestedTaskPaths` (`suggestedTaskPathsTruncated: true` when capped) plus a `hint`; use `gradle_get_project_model` with `includeTasks=true` for the full task list.

When the MCP client supplies a progress token, the server may also emit MCP progress/logging notifications during the run.

## Disconnect during a build

`gradle_disconnect` is non-blocking: the server cancels running builds for the disconnected project(s) via the Tooling API `CancellationToken` and marks them `cancelled`. If the Gradle daemon keeps running briefly, on-disk `gradle-result.json` may still report `running` or `succeeded`; `gradle_get_build_status` prefers the disk record when it disagrees with the in-memory snapshot. Completed build records remain available via `gradle_get_build_status` (from memory or `.gradle/mcp-builds/`) while retained. The disconnect response includes a `warning` field when a build was active.

`gradle_connect` registers an additional project connection without closing others. It rejects the call while a build is still running for the same `projectDirectory`.

## Agent skill

Copy or symlink `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/` into your Cursor skills directory (for example `~/.cursor/skills/gradle-tapi-mcp/`) so agents use token-efficient MCP workflows with `project-context-ingestion`. Prefer the plugin marketplace above when the host supports it.

## Notes

- Uses the Gradle daemon. Compatible idle daemons are reused automatically.
- STDIO transport: logging goes to stderr only so stdout stays clean for MCP.
