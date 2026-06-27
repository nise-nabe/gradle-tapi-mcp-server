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

`gradle_disconnect` is non-blocking. If a build was active, the response may include `warning` explaining that running builds were cancelled via the Tooling API CancellationToken.

`gradle_connect` cancels any running builds before connecting. It rejects the call while foreground or background builds are still running.

Multiple `background=true` builds may run concurrently on one connection (bounded by a server-side pool). When the pool is full, new background starts return `BUILD_ALREADY_RUNNING`.

## Query (read-only)

### gradle_get_build_environment

No arguments. Returns `gradle.gradleVersion`, `gradle.gradleUserHome`, `gradle.versionInfo` (when Gradle 9.4+; same text as `gradle --version`), `java.javaHome`, `java.javaVersion`, `java.jvmArguments`.

### gradle_get_help

| Argument | Default | Description |
|----------|---------|-------------|
| `maxChars` | `8000` | Maximum rendered help characters to return |
| `tailOutput` | `true` | When truncated, keep the tail of the help text |

Returns `renderedText` (equivalent to `gradle --help`), plus `renderedTextTruncated` and `renderedTextTotalChars` when truncated. Requires Gradle 9.4+; returns `INVALID_ARGUMENT` when the Help model is unavailable.

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

### gradle_get_gradle_build

| Argument | Default | Description |
|----------|---------|-------------|
| `maxDepth` | unlimited | Maximum project tree depth |
| `maxChildren` | unlimited | Maximum child projects per node |

Returns the connected `GradleBuild` model: `buildRootDir`, `rootProject` tree (`BasicGradleProject`), flat `projects`, `projectCount`, `includedBuilds`, and `editableBuilds`. No tasks. Nested composite builds reuse the same shape; already-visited builds return `{ buildRootDir, cycleReference: true }`.

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
| `includeOutput` | no | `false` | Include stdout/stderr (task log). Default false returns outcome/buildSummary only |
| `maxOutputChars` | no | `8000` | Per-stream char limit when `includeOutput=true` |
| `tailOutput` | no | `true` | Keep tail when truncating |
| `includeProgress` | no | `false` | Include detailed `progress` object |
| `background` | no | `false` | Return `buildId` immediately; poll with `gradle_get_build_status` (multiple concurrent background builds allowed) |

Response when `background=true`: `buildId`, `status`, `kind`, `message`.

Foreground responses include `outcome` (`SUCCESS` / `FAILED`), `buildSummary` (`resultLine`, `taskSummaryLine`), `failedTaskCount`, `failedTasks`, and `buildSummary.failureSummary` on failure. `stdout`/`stderr` are omitted unless `includeOutput=true` (truncated per `maxOutputChars`; CRLF normalized to LF). `progress` only when `includeProgress=true`.

### gradle_run_tests

At least one selection mechanism is required: `testClasses`, `testMethods`, or `includePattern`/`includePatterns` (patterns also require `tasks`).

| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `testClasses` | no* | `[]` | FQCN list (`withJvmTestClasses` / `withTaskAndTestClasses`) |
| `testMethods` | no* | — | Map `{"com.example.FooTest": ["method1"]}` or array `[{"class": "...", "methods": ["..."]}]` |
| `taskPath` | no | — | Test task path for `withTaskAndTest*` (Gradle 6.1+). Requires `testClasses` or `testMethods` |
| `includePattern` | no* | — | Single include pattern for `withTestsFor` TestSpec (Gradle 7.6+) |
| `includePatterns` | no* | `[]` | Include patterns for `withTestsFor` TestSpec (Gradle 7.6+) |
| `tasks` | no | `[]` | Test task paths for `TestLauncher.forTasks()` (Gradle 7.6+). Required with patterns |
| `arguments` | no | `[]` | Extra Gradle CLI args |
| `jvmArguments` | no | `[]` | JVM args |
| `includeOutput` | no | `false` | Include stdout/stderr (task log). Default false returns outcome/buildSummary only |
| `maxOutputChars` | no | `8000` | Per-stream char limit when `includeOutput=true` |
| `tailOutput` | no | `true` | Keep tail when truncating |
| `includeProgress` | no | `false` | Include detailed `progress` object |
| `background` | no | `false` | Return `buildId` immediately; poll with `gradle_get_build_status` |

\* At least one of `testClasses`, `testMethods`, or `includePattern`/`includePatterns` must be provided.

Selection precedence: `taskPath` → `withTaskAndTest*`; else `testMethods` → `withJvmTestMethods`; else `includePatterns` → `withTestsFor`; else `testClasses` → `withJvmTestClasses`. `forTasks` applies when `tasks` is non-empty.

Same foreground/background response shape as `gradle_run_tasks`.

### gradle_list_builds

| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `projectDirectory` | no | connected project, then `GRADLE_PROJECT_DIR` | Project root for scanning `.gradle/mcp-builds/`. When provided explicitly, must stay within the connected project or `GRADLE_PROJECT_DIR` workspace boundary; otherwise `INVALID_ARGUMENT`. |
| `limit` | no | `20` | Maximum builds to return (max `100`), most recent first |

Does not require an active Tooling API connection. Returns `builds` (array of summaries), `projectDirectory` used for disk scan when resolved, `totalAvailable`, and `truncated`. Each build always includes `buildId`, `status`, `tasks`, `testClasses`, and `recordSource`. Optional per-build fields omitted when absent: `kind`, `projectDirectory`, `startedAt`, `finishedAt`, `outcome` (e.g. running builds omit `outcome`; Gradle-only disk records may omit `kind`).

### gradle_cancel_build

| Argument | Required | Description |
|----------|----------|-------------|
| `buildId` | yes | Build ID from a background run |

Cancels the Gradle daemon build via Tooling API `CancellationToken`. Returns immediately with cancellation requested; poll `gradle_get_build_status` until `status` is no longer `running`, then inspect the terminal status. No-op when the build already finished.

### gradle_get_build_status

| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `buildId` | yes | — | Build ID from a background run |
| `projectDirectory` | no | connected project | Project root for disk-only lookup when the in-memory record was evicted and the connected project differs |
| `includeProgress` | no | `false` | Include detailed `progress` object |
| `includeOutput` | no | `false` | Include stdout/stderr for running/completed builds |
| `maxOutputChars` | no | `8000` | Per-stream char limit when `includeOutput=true` |
| `tailOutput` | no | `true` | Keep tail when truncating |

Returns `status` (`running`, `succeeded`, `failed`, `cancelled`, or `not_found`), timestamps, `outcome`, and `buildSummary`. Always includes `statusSource` (`memory` or `disk`). Disk-backed responses also include `liveProgress` (`false`), `progressAvailable`, and `recordDirectory`. When memory and disk disagree, Gradle on-disk status wins while Gradle is still active; stale Gradle `running` (MCP terminal, no post-finalize events in `events.ndjson`) falls back to MCP. Completed builds include `failedTaskCount`, `failedTasks`, and `buildSummary.failureSummary` without `includeProgress` when available (in-memory, MCP-terminal disk, or Gradle-terminal failed with `events.ndjson`). `stdout`/`stderr` are included only when `includeOutput=true`. While running, live output requires an in-memory record; disk-only polls return streams only after MCP finalizes logs at build end. `progress` only when `includeProgress=true` (disk progress from `events.ndjson`, including task, test, and project-configuration events).

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
