# Gradle Tooling API MCP Server

MCP server that exposes [Gradle Tooling API](https://docs.gradle.org/current/userguide/third_party_integration.html) operations to Cursor and other MCP clients.

## Build

```bash
./gradlew jar
```

The fat JAR is written to `build/libs/gradle-tapi-mcp-server-0.1.0-SNAPSHOT.jar`.

## Cursor configuration

Add to `.cursor/mcp.json` in your Gradle project:

```json
{
  "mcpServers": {
    "gradle": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/gradle-tapi-mcp-server/build/libs/gradle-tapi-mcp-server-0.1.0-SNAPSHOT.jar"
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
| `gradle_connection_status` | Connection state for one project or all active connections (`connections[]`, `defaultProjectDirectory`) |
| `gradle_disconnect` | Close one project (`projectDirectory`) or all connections (omit) |
| `gradle_get_build_environment` | Gradle/Java environment including `javaVersion` and `versionInfo` (`gradle --version` text on Gradle 9.4+; lightweight) |
| `gradle_get_help` | Gradle CLI help text (`gradle --help` equivalent); optional `maxChars` / `tailOutput`; requires Gradle 9.4+ |
| `gradle_get_build_cache_status` | Build cache / configuration cache settings and local cache summaries |
| `gradle_get_project_overview` | Project hierarchy and task counts only; optional `maxDepth` / `maxChildren` |
| `gradle_get_gradle_build` | GradleBuild structure (root project tree, all projects, included/editable builds); optional `maxDepth` / `maxChildren` |
| `gradle_get_project_model` | Project model; tasks omitted by default |
| `gradle_get_build_invocations` | Runnable tasks; selectors omitted by default |
| `gradle_get_project_publications` | Publications |
| `gradle_run_tasks` | Execute tasks; stdout/stderr truncated by default |
| `gradle_run_tests` | Execute JVM tests by class, method, pattern, or task scope; stdout/stderr truncated by default |
| `gradle_list_builds` | List recent MCP builds from memory and `.gradle/mcp-builds/` (no Tooling API required) |
| `gradle_get_build_status` | Poll status/output for a background build (`buildId` required); set `includeProgress: true` for detailed progress |
| `gradle_cancel_build` | Cancel a background build via Tooling API `CancellationToken` (`buildId` required) |

## Token-efficient usage

Prefer this order for agent workflows such as project context ingestion:

1. `gradle_get_build_environment` for resolved Gradle/Java versions
2. `gradle_get_project_overview` for module hierarchy and task counts (or `gradle_get_gradle_build` for composite/includeBuild repositories)
3. `gradle_run_tasks` with `["build"]` or `["test"]` when verification is needed

Use heavier tools only when required:

- `gradle_get_project_model` with `includeTasks=true` to list tasks
- `includeTaskDetails=true` only when descriptions are needed
- `taskGroup`, `taskNamePrefix`, or `maxTasks` to narrow large builds
- `maxDepth` / `maxChildren` on overview/model queries for large monorepos
- `gradle_get_build_invocations` with `includeTaskSelectors=true` only when selectors matter

`gradle_run_tasks` and `gradle_run_tests` omit `stdout`/`stderr` by default (`includeOutput=false`) and return `outcome`, `buildSummary`, and failure fields only—no task log noise such as `UP-TO-DATE`. Set `includeOutput=true` to include captured streams (truncated per `maxOutputChars`, default `8000`; CRLF normalized to LF).

Foreground responses include `outcome` (`SUCCESS` / `FAILED`) and `buildSummary` (parsed Gradle summary lines). Completed builds also include `failedTaskCount`, `failedTasks` (from Tooling API progress events), and `buildSummary.failureSummary` (parsed `> Task ... FAILED` / test failure lines from stdout). Set `includeProgress: true` to include the full progress object; default is omitted for token efficiency.

Tune captured output with `includeOutput` (default `false`), `maxOutputChars` (default `8000` when included), and `tailOutput` (default `true`).

Tool errors return structured JSON: `{ "error": { "code": "NOT_CONNECTED", "message": "..." } }`.

## Multiple projects

One MCP server process can hold **multiple Gradle project connections** at once.

1. `gradle_connect` with each project root (does not disconnect other projects)
2. Pass optional `projectDirectory` on query/build tools; omit to use `GRADLE_PROJECT_DIR`
3. `gradle_connection_status` without arguments returns `connections[]` plus legacy flat fields for the default project
4. `gradle_disconnect` with `projectDirectory` closes one project; omit to close all
5. Background builds are scoped per project; concurrent builds across projects share the global pool limit

Example:

```json
{ "projectDirectory": "/path/to/other-repo", "tasks": ["build"], "background": true }
```

## Long-running builds

For slow `build` or `test` runs, pass `background: true` to `gradle_run_tasks` or `gradle_run_tests`. The tool returns immediately with a `buildId`. Multiple background builds may run concurrently (up to a server-side limit). Call `gradle_cancel_build` with that `buildId` to stop an unneeded background run. Poll `gradle_get_build_status` with that `buildId` (required) to read:

- `status`: `running`, `succeeded`, `failed`, or `cancelled`
- `statusSource`: `memory` (in-process record) or `disk` (`.gradle/mcp-builds/<buildId>/`)
- `outcome` and `buildSummary` when the build has finished
- `progress` (only when `includeProgress: true`): capped task lists and recent events; disk polls use `events.ndjson` (task and test events)
- `stdout`/`stderr` only when `includeOutput: true` — live partial output while running only when the MCP server still holds the in-memory record; disk-only polls return streams after MCP finalizes logs at build end
- optional `projectDirectory` when the in-memory record was evicted and the connected project differs (disk-only lookup)

Build records persist under `.gradle/mcp-builds/<buildId>/`. Terminal status prefers `gradle-result.json` (Gradle init script) over stale MCP memory. When Gradle still reports `running` but MCP already finalized (e.g. disconnect) and `events.ndjson` shows no activity after MCP's `finishedAt`, MCP's terminal status is used instead (daemon likely dead). Terminal `buildSummary` follows the winning terminal authority: when Gradle's `gradle-result.json` is terminal, parse the fullest available stdout (`stdout.log` and any richer in-memory capture); stale `mcp-result.json` summaries are ignored. When MCP finalized the build, use `mcp-result.json`, then parsed `stdout.log` as fallback. Gradle-terminal failed builds include `failedTaskCount` / `failedTasks` from `events.ndjson` when present.

When the MCP client supplies a progress token, the server may also emit MCP progress/logging notifications during the run.

## Disconnect during a build

`gradle_disconnect` is non-blocking: the server cancels running builds for the disconnected project(s) via the Tooling API `CancellationToken` and marks them `cancelled`. If the Gradle daemon keeps running briefly, on-disk `gradle-result.json` may still report `running` or `succeeded`; `gradle_get_build_status` prefers the disk record when it disagrees with the in-memory snapshot. Completed build records remain available via `gradle_get_build_status` (from memory or `.gradle/mcp-builds/`) while retained. The disconnect response includes a `warning` field when a build was active.

`gradle_connect` registers an additional project connection without closing others. It rejects the call while a build is still running for the same `projectDirectory`.

## Agent skill

Copy or symlink `skills/gradle-tapi-mcp/` into your Cursor skills directory (for example `~/.cursor/skills/gradle-tapi-mcp/`) so agents use token-efficient MCP workflows with `project-context-ingestion`.

## Notes

- Uses the Gradle daemon. Compatible idle daemons are reused automatically.
- STDIO transport: logging goes to stderr only so stdout stays clean for MCP.
