# Gradle TAPI MCP — Tool Reference

## Connection

### gradle_connect

| Argument | Required | Description |
|----------|----------|-------------|
| `projectDirectory` | yes | Gradle project root |
| `gradleUserHome` | no | `GRADLE_USER_HOME` override |
| `gradleVersion` | no | Pin Gradle version |
| `gradleInstallation` | no | Local Gradle install path |

### gradle_connection_status / gradle_disconnect

No arguments.

`gradle_connection_status` returns `connected`, `projectDirectory`, and a connect-time runtime stack snapshot (`gradleVersion`, `javaHome`, `javaVersion`, `runtimeStackAvailable`). It does not perform Gradle I/O on each call. Use `gradle_get_build_environment` for a fresh query including `gradleUserHome` and `jvmArguments`.

`gradle_disconnect` is non-blocking. If a build was active, the response may include `warning` explaining that the Gradle daemon can briefly overlap work until the prior Tooling API call unwinds.

`gradle_connect` marks any running builds as failed before connecting. It rejects the call while foreground or background builds are still running.

Multiple `background=true` builds may run concurrently on one connection (bounded by a server-side pool). When the pool is full, new background starts return `BUILD_ALREADY_RUNNING`.

## Query (read-only)

### gradle_get_build_environment

No arguments. Returns `gradle.gradleVersion`, `gradle.gradleUserHome`, `java.javaHome`, `java.javaVersion`, `java.jvmArguments`.

### gradle_get_build_cache_status

| Argument | Default | Description |
|----------|---------|-------------|
| `includeLastMcpBuild` | `true` | Include cache-oriented stats from the last MCP task/test run |
| `includeLocalCacheDetails` | `true` | Include local build-cache / configuration-cache directory summaries |
| `includeDeclaredProperties` | `true` | Include cache-related entries from project and user `gradle.properties` |
| `probeConfigurationCache` | `false` | Run `properties -q --configuration-cache` compatibility probe |

Returns:

- `summary` — effective flags (`buildCacheEnabled`, `remoteBuildCacheConfigured`, `configurationCacheRequested`, …)
- `resolvedProperties` — cache-related properties from `properties -q`
- `declaredProperties` — cache keys from project/user `gradle.properties` files
- `local` — `build-cache-*` dirs under `gradleUserHome/caches`, project `.gradle` cache dirs
- `lastMcpBuild` — parsed `taskSummaryLine` / `taskStats` from the last MCP build when available; includes `tasks` for task runs and `testClasses` for test runs
- `configurationCacheProbe` — present when `probeConfigurationCache=true`

### gradle_get_project_overview

| Argument | Default | Description |
|----------|---------|-------------|
| `maxDepth` | unlimited | Maximum project tree depth |
| `maxChildren` | unlimited | Maximum child projects per node |

Returns hierarchy with `taskCount` per project; no task lists. When truncated: `truncated: true`, `totalChildCount`.

### gradle_get_project_model

| Argument | Default | Description |
|----------|---------|-------------|
| `maxDepth` | unlimited | Maximum project tree depth |
| `maxChildren` | unlimited | Maximum child projects per node |
| `includeTasks` | `false` | Include task arrays |
| `includeTaskDetails` | `false` | Add `description`, `displayName` per task |
| `taskGroup` | — | Filter by Gradle task group |
| `taskNamePrefix` | — | Filter by task name prefix |
| `maxTasks` | — | Cap after filtering |

Slim task shape (default): `{ name, path, group }`.

### gradle_get_build_invocations

Same as `gradle_get_project_model` (including `maxDepth` / `maxChildren`), plus:

| Argument | Default | Description |
|----------|---------|-------------|
| `includeTaskSelectors` | `false` | Include `taskSelectors` array |

Tasks are always included when this tool is called (`includeTasks` forced true internally).

### gradle_get_project_publications

No arguments.

## Execute

### gradle_run_tasks

| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `tasks` | yes | — | Task paths (e.g. `["build"]`, `[":app:test"]`) |
| `arguments` | no | `[]` | Extra Gradle CLI args |
| `jvmArguments` | no | `[]` | JVM args for the build |
| `maxOutputChars` | no | `8000` | Per-stream char limit |
| `tailOutput` | no | `true` | Keep tail when truncating |
| `includeProgress` | no | `false` | Include detailed `progress` object |
| `background` | no | `false` | Return `buildId` immediately; poll with `gradle_get_build_status` (multiple concurrent background builds allowed) |

Response when `background=true`: `buildId`, `status`, `kind`, `message`.

Foreground responses include `outcome` (`SUCCESS` / `FAILED`), `buildSummary` (`resultLine`, `taskSummaryLine`), and `progress` only when `includeProgress=true`.

### gradle_run_tests

| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `testClasses` | yes | — | FQCN list |
| `arguments` | no | `[]` | Extra Gradle CLI args |
| `jvmArguments` | no | `[]` | JVM args |
| `maxOutputChars` | no | `8000` | Per-stream char limit |
| `tailOutput` | no | `true` | Keep tail when truncating |
| `includeProgress` | no | `false` | Include detailed `progress` object |
| `background` | no | `false` | Return `buildId` immediately; poll with `gradle_get_build_status` |

### gradle_get_build_status

| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `buildId` | yes | — | Build ID from a background run |
| `includeProgress` | no | `false` | Include detailed `progress` object |
| `maxOutputChars` | no | `8000` | Per-stream char limit for stdout/stderr |
| `tailOutput` | no | `true` | Keep tail when truncating |

Returns `status`, timestamps, and partial or final `stdout`/`stderr`. Completed builds also include `outcome` and `buildSummary`.

## Errors

Failed tool calls return JSON:

```json
{
  "error": {
    "code": "NOT_CONNECTED",
    "message": "..."
  }
}
```

Codes: `NOT_CONNECTED`, `BUILD_ALREADY_RUNNING` (max concurrent background builds reached), `INVALID_ARGUMENT`, `PROJECT_NOT_FOUND`, `BUILD_FAILED`, `INTERNAL_ERROR`.

## Environment variables (server startup)

| Variable | Effect |
|----------|--------|
| `GRADLE_PROJECT_DIR` | Auto-connect on start |
| `GRADLE_USER_HOME` | Default user home |
| `GRADLE_VERSION` | Default Gradle version |
| `GRADLE_INSTALLATION` | Default local install |
