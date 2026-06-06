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
| `GRADLE_PROJECT_DIR` | Auto-connect on startup |
| `GRADLE_USER_HOME` | Optional Gradle user home |
| `GRADLE_VERSION` | Optional explicit Gradle version |
| `GRADLE_INSTALLATION` | Optional local Gradle installation |

## Tools

| Tool | Description |
|------|-------------|
| `gradle_connect` | Connect to a project directory |
| `gradle_connection_status` | Current connection state (includes cached Gradle/Java when connected) |
| `gradle_disconnect` | Close the connection |
| `gradle_get_build_environment` | Gradle/Java environment including `javaVersion` (lightweight) |
| `gradle_get_project_overview` | Project hierarchy and task counts only; optional `maxDepth` / `maxChildren` |
| `gradle_get_project_model` | Project model; tasks omitted by default |
| `gradle_get_build_invocations` | Runnable tasks; selectors omitted by default |
| `gradle_get_project_publications` | Publications |
| `gradle_run_tasks` | Execute tasks; stdout/stderr truncated by default |
| `gradle_run_tests` | Execute JVM test classes; stdout/stderr truncated by default |
| `gradle_get_build_status` | Poll progress/output for a background build |

## Token-efficient usage

Prefer this order for agent workflows such as project context ingestion:

1. `gradle_get_build_environment` for resolved Gradle/Java versions
2. `gradle_get_project_overview` for module hierarchy and task counts
3. `gradle_run_tasks` with `["build"]` or `["test"]` when verification is needed

Use heavier tools only when required:

- `gradle_get_project_model` with `includeTasks=true` to list tasks
- `includeTaskDetails=true` only when descriptions are needed
- `taskGroup`, `taskNamePrefix`, or `maxTasks` to narrow large builds
- `maxDepth` / `maxChildren` on overview/model queries for large monorepos
- `gradle_get_build_invocations` with `includeTaskSelectors=true` only when selectors matter

`gradle_run_tasks` and `gradle_run_tests` keep `stdout`/`stderr` as strings and add `stdoutTruncated`, `stdoutTotalChars`, `stderrTruncated`, and `stderrTotalChars` when truncation happens. Truncated output is prefixed with `... [truncated N chars] ...` and newlines are normalized to LF.

Foreground responses include `outcome` (`SUCCESS` / `FAILED`) and `buildSummary` (parsed Gradle summary lines). Set `includeProgress: true` to include detailed task progress; default is omitted for token efficiency.

Tune with `maxOutputChars` (default `8000`) and `tailOutput` (default `true`).

Tool errors return structured JSON: `{ "error": { "code": "NOT_CONNECTED", "message": "..." } }`.

## Long-running builds

For slow `build` or `test` runs, pass `background: true` to `gradle_run_tasks` or `gradle_run_tests`. The tool returns immediately with a `buildId`. Poll `gradle_get_build_status` with that ID (or omit `buildId` to use the active build) to read:

- `status`: `running`, `succeeded`, or `failed`
- `outcome` and `buildSummary` when the build has finished
- `progress` (only when `includeProgress: true`): capped task lists and recent events
- partial `stdout`/`stderr` while the build is still running

When the MCP client supplies a progress token, the server may also emit MCP progress/logging notifications during the run.

## Agent skill

Copy or symlink `skills/gradle-tapi-mcp/` into your Cursor skills directory (for example `~/.cursor/skills/gradle-tapi-mcp/`) so agents use token-efficient MCP workflows with `project-context-ingestion`.

## Notes

- Uses the Gradle daemon. Compatible idle daemons are reused automatically.
- STDIO transport: logging goes to stderr only so stdout stays clean for MCP.
