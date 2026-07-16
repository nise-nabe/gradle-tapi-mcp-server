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

| Argument | Required | Description |
|----------|----------|-------------|
| `projectDirectory` | no | Inspect or disconnect one project. Omit to list/disconnect all. |
| `refresh` | no | When `true`, fetches `BuildEnvironment` for connected projects missing cached runtime stack. Default `false` (cache-only). Omitting `projectDirectory` with `refresh=true` fetches once per connected project. |

`gradle_connection_status` without `projectDirectory` returns `defaultProjectDirectory`, `connections[]`, and legacy flat fields for the default project. With `projectDirectory`, returns status for that project only. When `runtimeStackAvailable` is `false`, call `gradle_get_build_environment` or pass `refresh: true` to populate `gradleVersion` / `javaHome` / `javaVersion`.

`gradle_disconnect` without `projectDirectory` disconnects **all** projects. With `projectDirectory`, disconnects one project and cancels only its running builds.

`gradle_connect` keeps existing connections open. It rejects the call while a build is running for the same `projectDirectory`.

Multiple `background=true` builds may run concurrently across **different** connected projects (bounded by a server-side pool). Only one MCP build may run per `projectDirectory` at a time; a second `gradle_run_tasks` / `gradle_run_tests` for the same project returns `BUILD_ALREADY_RUNNING` unless `queueIfBusy=true` with `background=true` (enqueues with `status: queued`, max 3 queued per project; saturated queue returns `BUILD_QUEUE_FULL`). When the global pool is full, new background starts also return `BUILD_ALREADY_RUNNING`.

Do not run shell `./gradlew` in parallel on the same checkout while an MCP build is active. IntelliJ Platform `:plugin:test` runs compete for the same IDE test sandbox and can appear hung for many minutes or corrupt sandbox state.

Most query/build tools accept optional `projectDirectory` (defaults to `GRADLE_PROJECT_DIR`).

Model and overview tools also accept optional `prepareTasks` (string array): Gradle tasks to run before fetching the Tooling API model (for example `:app:compileJava` to ensure sources exist). Empty or omitted means no pre-tasks. **While a build is `running` or `queued` for the same `projectDirectory`, model queries are rejected** with `BUILD_ALREADY_RUNNING`. Non-empty `prepareTasks` execute Gradle work and can be slow—use only when needed.

## Query (read-only)

### gradle_get_build_environment

| Argument | Required | Description |
|----------|----------|-------------|
| `projectDirectory` | no | Gradle project root (default: `GRADLE_PROJECT_DIR`) |

Returns `gradle.gradleVersion`, `gradle.gradleUserHome`, `gradle.versionInfo` (Gradle 9.4+; same text as `gradle --version`; omitted on older Gradle), `java.javaHome`, `java.javaVersion`, `java.jvmArguments`.

### gradle_get_java_runtimes

| Argument | Default | Description |
|----------|---------|-------------|
| `includeToolchains` | `true` | Include `javaToolchains` probe results (extra Gradle work) |

Returns daemon Java from the connected project (`javaHome`, `javaVersion`, `jvmArguments`) and, when `includeToolchains=true`, toolchain metadata from `javaToolchains`. Prefer `gradle_get_build_environment` for a lightweight stack snapshot; use this tool when selecting or comparing JDK installations for toolchain configuration.

### gradle_get_help

| Argument | Default | Description |
|----------|---------|-------------|
| `maxChars` | `8000` | Maximum rendered help characters to return |
| `tailOutput` | `true` | When truncated, keep the tail of the help text |

Returns `renderedText` (equivalent to `gradle --help`), `renderedTextTruncated`, and `renderedTextTotalChars`. Truncation metadata is always included; `renderedTextTruncated` is `false` when the full text fits within `maxChars`. Requires Gradle 9.4+; returns `INVALID_ARGUMENT` when the Help model is unavailable.

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
| `prepareTasks` | `[]` | Optional tasks to run before fetching the model |

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
| `maxTasks` | — | Global cap across the project tree after filtering (root tasks first). When capped, the root response includes `tasksTruncated` and `tasksTotalMatched` for visited nodes only (`maxDepth` / `maxChildren` omissions are not counted). |

Slim task shape (default): `{ name, path, group }`.

### gradle_get_build_invocations

Same task query options as `gradle_get_project_model` (including global `maxTasks`, `maxDepth` / `maxChildren`). When `maxTasks` caps the result, the response includes `tasksTruncated` and `tasksTotalMatched`. Plus:

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
| `arguments` | no | `[]` | Extra Gradle CLI args (init scripts, `@` arg files, and `mcp.*` control properties are rejected) |
| `jvmArguments` | no | `[]` | JVM args for the build |
| `includeOutput` | no | `false` | Include stdout/stderr (task log). Default false returns outcome/buildSummary only |
| `maxOutputChars` | no | `8000` | Per-stream char limit when `includeOutput=true` |
| `tailOutput` | no | `true` | Keep tail when truncating |
| `includeProgress` | no | `false` | Include detailed `progress` object |
| `background` | no | `false` | Return `buildId` immediately; poll with `gradle_get_build_status` (multiple concurrent background builds allowed) |
| `queueIfBusy` | no | `false` | Enqueue when the project already has a running or queued build. Requires `background=true`. |

Response when `background=true`: `buildId`, `status` (`running` or `queued`), `kind`, `message`. Queued responses may include `queuePosition` and `queuedBehindBuildId`.

Foreground responses include `outcome` (`SUCCESS` / `FAILED`), `buildSummary` (`resultLine`, `taskSummaryLine`), `failedTaskCount`, `failedTasks`, and `buildSummary.failureSummary` on failure. `stdout`/`stderr` are omitted unless `includeOutput=true` (truncated per `maxOutputChars`; CRLF normalized to LF). `progress` only when `includeProgress=true`.

### gradle_run_tests

At least one selection mechanism is required: `testClasses`, `testMethods`, or `includePattern`/`includePatterns` (patterns also require `tasks`).

| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `testClasses` | no* | `[]` | FQCN list (`withJvmTestClasses` / `withTaskAndTestClasses`). `Class.method` entries with a lowercase-leading final segment (e.g. `com.example.FooTest.testBar`) normalize to `testMethods`. Wildcards (`*`, `?`) in either segment and uppercase final segments stay as class names; nested classes use JVM `$` notation (e.g. `com.example.Outer$Inner.testMethod`). Prefer `testMethods` or `includePatterns` when ambiguous. |
| `testMethods` | no* | — | Preferred API for method selection: map `{"com.example.FooTest": ["method1"]}` or array `[{"class": "...", "methods": ["..."]}]`. `className` and `testClass` are accepted at runtime as aliases for `class`. |
| `taskPath` | no | — | **Single** Test task path for `withTaskAndTest*` (Gradle 6.1+), including custom `JvmTestSuite` tasks such as `:mod:fastTest`. Requires `testClasses` or `testMethods` |
| `includePattern` | no* | — | Single include pattern for `withTestsFor` TestSpec (Gradle 7.6+) |
| `includePatterns` | no* | `[]` | Include patterns for `withTestsFor` TestSpec (Gradle 7.6+). Applied to **every** path in `tasks` |
| `tasks` | no | `[]` | One or more Test task paths for `TestLauncher.forTasks()` (Gradle 7.6+). Required with patterns. Use multiple paths to batch `:test` and custom suites (e.g. `:fastTest`) in **one** MCP build |
| `arguments` | no | `[]` | Extra Gradle CLI args (init scripts, `@` arg files, and `mcp.*` control properties are rejected) |
| `jvmArguments` | no | `[]` | JVM args |
| `includeOutput` | no | `false` | Include stdout/stderr (task log). Default false returns outcome/buildSummary only |
| `maxOutputChars` | no | `8000` | Per-stream char limit when `includeOutput=true` |
| `tailOutput` | no | `true` | Keep tail when truncating |
| `includeProgress` | no | `false` | Include detailed `progress` object |
| `background` | no | `false` | Return `buildId` immediately; poll with `gradle_get_build_status` |
| `queueIfBusy` | no | `false` | Enqueue when the project already has a running or queued build. Requires `background=true`. |

\* Provide exactly one of `testClasses`, `testMethods`, or `includePattern`/`includePatterns` (patterns also require `tasks`). Optional `taskPath` and `tasks` scope the selected tests.

#### Selector decision table

| Goal | Arguments |
|------|-----------|
| One default `:test` task, class list | `taskPath: ":mod:test"` + `testClasses` |
| One task, method map | `taskPath` + `testMethods` |
| Custom suite only (`fastTest`) | `taskPath: ":mod:fastTest"` + classes/methods, **or** `tasks: [":mod:fastTest"]` + `includePatterns` |
| Several Test tasks / suites in one build | `tasks: [":mod:test", ":mod:fastTest"]` + `includePatterns` |
| Multi-project unscoped classes/methods | Invalid — must scope with `taskPath` or `tasks` |

`taskPath` uses `withTaskAndTest*` when combined with classes or methods (single task). `tasks` applies `TestLauncher.forTasks()` when non-empty; with patterns, each listed task gets the same `includePatterns`.

**Concurrency:** Do not start multiple `gradle_run_tests` calls in parallel for the same `projectDirectory` unless `queueIfBusy=true` with `background=true` (otherwise the second call returns `BUILD_ALREADY_RUNNING`). The single-flight gate clears as soon as the build is terminal in memory (no grace window)—serialize `gradle_run_*` across turns after a terminal status. Batch multiple classes, methods, **or Test tasks** in one call via `testMethods` / `testClasses` / `tasks`+`includePatterns`. Parallel test runs are only supported across **different** `projectDirectory` values, up to the server concurrent-build limit (see Connection section).

Same foreground/background response shape as `gradle_run_tasks`. When `testClasses` entries were normalized to `testMethods`, the initial tool response may include `selectionNormalized: true` (not present on `gradle_get_build_status` polls).

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
| `sinceStdoutOffset` | no | — | With `includeOutput=true`, return `stdoutDelta` from this char offset (plus `stdoutOffset` for the next poll) instead of repeating the full tail |
| `sinceStderrOffset` | no | — | Same as `sinceStdoutOffset` for stderr |
| `waitUntilComplete` | no | `false` | Server-side wait until terminal status or `waitTimeoutMs` (independent of MCP client transport timeout) |
| `waitTimeoutMs` | no | `30000` | Max **server-side** wait when `waitUntilComplete=true` (capped at `60000`) |
| `pollIntervalMs` | no | `2000` | Server-side poll interval while waiting |

**Client vs server timeout:** `waitTimeoutMs` applies only inside this server. MCP hosts (e.g. Cursor) may kill the tool call earlier with a transport timeout such as `-32001`. For multi-minute builds, prefer plain polls (`waitUntilComplete` false/omitted) or short waits; treat `waitTimedOut` as “still running, poll again”, not server death. Without wait, status reads memory and/or `.gradle/mcp-builds/` only—no Tooling API call.

Returns `status` (`queued`, `running`, `succeeded`, `failed`, `cancelled`, or `not_found`), timestamps, `outcome`, and `buildSummary`. Always includes `statusSource` (`memory` or `disk`). Disk-backed responses also include `liveProgress` (`false`), `progressAvailable`, and `recordDirectory`. While memory reports `running`, memory status wins and disk `events.ndjson` task events are merged into `progress`; `recordDirectory` is included when disk artifacts exist. When memory is terminal or absent and memory and disk disagree, Gradle on-disk status wins while Gradle is still active; stale Gradle `running` (MCP terminal, no post-finalize events in `events.ndjson`) falls back to MCP. Completed builds include `failedTaskCount`, `failedTasks`, and `buildSummary.failureSummary` without `includeProgress` when available (in-memory, MCP-terminal disk, or Gradle-terminal failed with `events.ndjson`). Failed test runs also include `testFailures` (structured `className`, `methodName`, `exceptionType`, `message`, `sourceFile`, `line`) and `failedTestCount` without `includeOutput` or `includeTestDetails`. Terminal failures include `failureKind` and `failureCategory` (`TEST`, `GRADLE_TASK`, `TOOLING_CONNECTION`, `CANCELLED`). Persisted in `mcp-result.json` under `.gradle/mcp-builds/<buildId>/`. `stdout`/`stderr` are included only when `includeOutput=true`. When `sinceStdoutOffset` / `sinceStderrOffset` are set, responses use `stdoutDelta` / `stderrDelta` and `stdoutOffset` / `stderrOffset` so agents do not re-read prior log prefixes. When `waitUntilComplete=true` and the build is still running when `waitTimeoutMs` elapses, the response includes `waitTimedOut: true`, `waitedMs`, and `hint` (poll again; do not treat as MCP failure). While running, live output requires an in-memory record; disk-only polls return streams only after MCP finalizes logs at build end. `progress` only when `includeProgress=true`; with an in-memory record, includes `CONFIG_*` events from the live `ProgressListener` (task, test, and project-configuration) merged with disk `events.ndjson` task/test events plus capped lists. Disk-only polls read `events.ndjson` (task and test events only—not project-configuration, which the init script does not write).

#### includeProgress / includeProblems / includeDownloads / includeTestDetails

| Flag | Default | Effect |
|------|---------|--------|
| `includeProgress` | `false` | `progress.completedTasks`, `progress.recentEvents` (live Tooling API or disk `events.ndjson`) |
| `includeProblems` | `false` | Live Gradle Problems API as `liveProblems`; merged into terminal failure responses |
| `includeDownloads` | `false` | `activeDownloadCount`, `recentDownloads` (requires in-memory live record) |
| `includeTestDetails` | `false` | Terminal `failedTests`; with `includeProgress=true`, adds `progress.recentEvents[].test` on `TEST_*` events. Disk polls restore `failedTests` from `events.ndjson` (`className`, `methodName`, `failureMessage`; `sourcePath`/`sourceLine` need live Tooling API) |

## MCP tool discovery (token-efficient)

`tools/list` returns every tool name, description, and `inputSchema`. For Cursor agents, prefer lazy discovery:

1. `mcp_get_tools` with no arguments — catalog only (names + short descriptions)
2. `mcp_get_tools` with `server` + `toolName` — full schema for the tool you are about to call
3. Avoid `mcp_get_tools` with `server` only unless you need every schema at once

Detailed parameter semantics live in this reference (Layer 3). Tool `description` fields are summaries (Layer 1).

## Errors

### Tool errors vs build outcomes

- **Tool errors** (`isError=true`): structured `{ "error": { "code", "message" } }` for preflight failures (`NOT_CONNECTED`, `BUILD_ALREADY_RUNNING`, `INVALID_ARGUMENT`, …).
- **Build outcomes** (`isError=false`): `gradle_run_tasks` / `gradle_run_tests` foreground responses and `gradle_get_build_status` terminal polls return `status: "failed"` / `outcome: "FAILED"` with `buildSummary`—not `error.code: BUILD_FAILED`.
- **`BUILD_FAILED`**: reserved for tooling/setup failures where Gradle could not be invoked meaningfully (for example `gradle_get_java_runtimes` when `javaToolchains` probing fails).

Failed tool calls return JSON:

```json
{
  "error": {
    "code": "NOT_CONNECTED",
    "message": "..."
  }
}
```

Codes: `NOT_CONNECTED`, `BUILD_ALREADY_RUNNING` (active/queued build for the same `projectDirectory`, or max concurrent background builds reached), `BUILD_QUEUE_FULL` (per-project queue saturated), `INVALID_ARGUMENT`, `PROJECT_NOT_FOUND`, `BUILD_FAILED`, `INTERNAL_ERROR`.

## Environment variables (server startup)

| Variable | Effect |
|----------|--------|
| `GRADLE_PROJECT_DIR` | Auto-connect on start |
| `GRADLE_USER_HOME` | Default user home |
| `GRADLE_VERSION` | Default Gradle version |
| `GRADLE_INSTALLATION` | Default local install |
