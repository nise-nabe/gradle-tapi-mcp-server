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

Multiple `background=true` builds may run concurrently across **different** connected projects (bounded by a server-side pool). Only one MCP build may run per `projectDirectory` at a time; a second background `gradle_run_tasks` / `gradle_run_tests` for the same project enqueues (`status: queued`, max 3 queued per project; `queueIfBusy` defaults true when `background` is true). Saturated queue returns `BUILD_QUEUE_FULL` with the same active-build fields. Foreground overlap or `queueIfBusy=false` returns `BUILD_ALREADY_RUNNING` with `activeBuildId` and related fields. When the global pool is full, new background starts also return `BUILD_ALREADY_RUNNING` with `activeBuildIds` (and full single-build fields when only one build is running).

Do not run shell `./gradlew` in parallel on the same checkout while an MCP build is active. IntelliJ Platform `:plugin:test` runs compete for the same IDE test sandbox and can appear hung for many minutes or corrupt sandbox state.

Most query/build tools accept optional `projectDirectory` (defaults to `GRADLE_PROJECT_DIR`).

Model and overview tools also accept optional `prepareTasks` (string array): Gradle tasks to run before fetching the Tooling API model (for example `:app:compileJava` to ensure sources exist). Empty or omitted means no pre-tasks. **While a build is `running` or `queued` for the same `projectDirectory`, model queries are rejected** with `BUILD_ALREADY_RUNNING` and `activeBuildId` / related fields when the occupying build is known. Non-empty `prepareTasks` execute Gradle work and can be slow—use only when needed.

## Query (read-only)

Tools that accept `projectPath` (`gradle_get_project_overview`, `gradle_get_project_model`, `gradle_get_build_invocations`) validate it in `ProjectTreeOptions.fromArgs`: malformed paths (e.g. `::plugin`, `:plugin:`) return `INVALID_ARGUMENT` before any Tooling API fetch. Unknown but syntactically valid paths (e.g. `:missing`) still require a `GradleProject` model fetch to resolve against the connected tree.

### gradle_get_build_environment

| Argument | Required | Description |
|----------|----------|-------------|
| `projectDirectory` | no | Gradle project root (default: `GRADLE_PROJECT_DIR`) |

`projectPath` is not supported on this tool; a non-blank value returns `INVALID_ARGUMENT`.

Returns `gradle.gradleVersion`, `gradle.gradleUserHome`, `gradle.versionInfo` (Gradle 9.4+; same text as `gradle --version`; omitted on older Gradle), `java.javaHome`, `java.javaVersion`, `java.jvmArguments`.

### gradle_get_java_runtimes

| Argument | Default | Description |
|----------|---------|-------------|
| `includeToolchains` | `true` | Include `javaToolchains` probe results (extra Gradle work) |

`projectPath` is not supported on this tool; a non-blank value returns `INVALID_ARGUMENT`.

Returns daemon Java from the connected project (`javaHome`, `javaVersion`, `jvmArguments`) and, when `includeToolchains=true`, toolchain metadata from `javaToolchains`. Prefer `gradle_get_build_environment` for a lightweight stack snapshot; use this tool when selecting or comparing JDK installations for toolchain configuration.

### gradle_get_help

| Argument | Default | Description |
|----------|---------|-------------|
| `maxChars` | `8000` | Maximum rendered help characters to return |
| `tailOutput` | `true` | When truncated, keep the tail of the help text |

`projectPath` is not supported on this tool; a non-blank value returns `INVALID_ARGUMENT`.

Returns `renderedText` (equivalent to `gradle --help`), `renderedTextTruncated`, and `renderedTextTotalChars`. Truncation metadata is always included; `renderedTextTruncated` is `false` when the full text fits within `maxChars`. Requires Gradle 9.4+; returns `INVALID_ARGUMENT` when the Help model is unavailable.

### gradle_get_build_cache_status

| Argument | Default | Description |
|----------|---------|-------------|
| `includeLastMcpBuild` | `true` | Include cache-oriented stats from the last MCP task/test run |
| `includeLocalCacheDetails` | `true` | Include local build-cache / configuration-cache directory summaries |
| `includeDeclaredProperties` | `true` | Include cache-related entries from project and user `gradle.properties` |
| `probeConfigurationCache` | `false` | Run `properties -q --configuration-cache` compatibility probe |

`projectPath` is not supported on this tool; a non-blank value returns `INVALID_ARGUMENT`.

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
| `projectPath` | — | Scope results to a subproject path (e.g. `:plugin`); includes its children. Resolves within the connected build's `GradleProject` tree only (not included/editable composite builds; use `gradle_get_gradle_build` for those). |
| `maxDepth` | unlimited | Maximum project tree depth (depth 0 = scoped `projectPath` when set, else build root) |
| `maxChildren` | unlimited | Maximum child projects per node |
| `prepareTasks` | `[]` | Optional tasks to run before fetching the model |

Returns hierarchy with `taskCount` per project; no task lists. When truncated: `truncated: true`, `totalChildCount`.

### gradle_get_gradle_build

| Argument | Default | Description |
|----------|---------|-------------|
| `maxDepth` | unlimited | Maximum project tree depth (depth 0 = build root) |
| `maxChildren` | unlimited | Maximum child projects per node |

`projectPath` is not supported on this tool. Use `gradle_get_project_overview`, `gradle_get_project_model`, or `gradle_get_build_invocations` to scope a subproject within the connected build. Returns the connected `GradleBuild` model: `buildRootDir`, `rootProject` tree (`BasicGradleProject`), flat `projects`, `projectCount`, `includedBuilds`, and `editableBuilds`. No tasks. Nested composite builds reuse the same shape; already-visited builds return `{ buildRootDir, cycleReference: true }`.

### gradle_get_project_model

| Argument | Default | Description |
|----------|---------|-------------|
| `projectPath` | — | Scope results to a subproject path (e.g. `:plugin`); includes its children. Resolves within the connected build's `GradleProject` tree only (not included/editable composite builds; use `gradle_get_gradle_build` for those). |
| `maxDepth` | unlimited | Maximum project tree depth (depth 0 = scoped `projectPath` when set, else build root) |
| `maxChildren` | unlimited | Maximum child projects per node |
| `includeTasks` | `false` | Include task arrays |
| `includeTaskDetails` | `false` | Add `description`, `displayName` per task |
| `taskGroup` | — | Filter by Gradle task group |
| `taskNamePrefix` | — | Filter by task name prefix |
| `maxTasks` | — | Global cap across the project tree after filtering (root tasks first). When capped, the root response includes `tasksTruncated` and `tasksTotalMatched` for visited nodes only (`maxDepth` / `maxChildren` omissions are not counted). |

Slim task shape (default): `{ name, path, group }`.

### gradle_get_build_invocations

Same task query options as `gradle_get_project_model` (including `projectPath`, global `maxTasks`, `maxDepth` / `maxChildren`). When `maxTasks` caps the result, the response includes `tasksTruncated` and `tasksTotalMatched`. Plus:

| Argument | Default | Description |
|----------|---------|-------------|
| `includeTaskSelectors` | `false` | Include `taskSelectors` array |

Tasks are always included when this tool is called (`includeTasks` forced true internally).

When `projectPath` is set, `taskSelectors` include only selectors whose task name is unique within the scoped subtree (names shared with sibling subprojects are omitted). Tooling API selectors are often root-attached (`projectIdentifier.projectPath` is `:`); this server filters by matching selector **names** against tasks in the scoped subtree (and `taskGroup` / `taskNamePrefix` when set). Unit tests in `ModelSerializersTest` cover root-attached selectors, `maxDepth` truncation, and `taskGroup` scoping. Prefer explicit scoped `tasks` paths for precise invocation targets. `maxDepth` / `maxChildren` also limit the task names used for selector matching.

### gradle_get_project_publications

| Argument | Default | Description |
|----------|---------|-------------|
| `projectDirectory` | `GRADLE_PROJECT_DIR` | Gradle project directory to connect |
| `prepareTasks` | `[]` | Optional tasks to run before fetching the model |

`projectPath` is not supported on this tool.

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
| `queueIfBusy` | no | `true` when `background` is true, else `false` | Enqueue when the project already has a running or queued build. Requires `background=true` when set. Pass `false` to reject with `BUILD_ALREADY_RUNNING`. |

Response when `background=true`: `buildId`, `status` (`running` or `queued`), `kind`, `message`. Queued responses may include `queuePosition` and `queuedBehindBuildId`.

Foreground builds auto-detach after ~45s when the MCP client request would time out: response includes `detached: true`, `buildId`, `status: "running"`, `message`, and `hint` to poll `gradle_get_build_status`. Use `background: true` explicitly for builds expected to exceed ~30s (cold IntelliJ Platform tests, first full `build`, etc.).

Foreground responses include `outcome` (`SUCCESS` / `FAILED`), `buildSummary` (`resultLine`, `taskSummaryLine`), `failedTaskCount`, `failedTasks`, and `buildSummary.failureSummary` on failure. `stdout`/`stderr` are omitted unless `includeOutput=true` (truncated per `maxOutputChars`; CRLF normalized to LF). `progress` only when `includeProgress=true`.

### gradle_run_tests

At least one selection mechanism is required: `testClasses`, `testMethods`, or `includePattern`/`includePatterns` (patterns also require `tasks`). Calls with only `taskPath` / `tasks` are invalid; use `gradle_run_tasks` to run the whole suite (`hint` names the supplied task paths when present).

| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `testClasses` | no* | `[]` | FQCN list (`withJvmTestClasses`, or `withTestsFor` `includeClasses` when scoped). `Class.method` entries with a lowercase-leading final segment (e.g. `com.example.FooTest.testBar`) normalize to `testMethods`. Wildcards (`*`, `?`) in either segment and uppercase final segments stay as class names; nested classes use JVM `$` notation (e.g. `com.example.Outer$Inner.testMethod`). Prefer `testMethods` or `includePatterns` when ambiguous. |
| `testMethods` | no* | — | Preferred API for method selection: map `{"com.example.FooTest": ["method1"]}` or array `[{"class": "...", "methods": ["..."]}]`. `className` and `testClass` are accepted at runtime as aliases for `class`. |
| `taskPath` | no | — | **Single** Test task path (`:mod:test` or a JvmTestSuite name such as `:mod:fastTest`). Requires `testClasses` or `testMethods`. Combined with `forTasks` + `withTestsFor` (Gradle 7.6+) |
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
| `queueIfBusy` | no | `true` when `background` is true, else `false` | Enqueue when the project already has a running or queued build. Requires `background=true` when set. Pass `false` to reject with `BUILD_ALREADY_RUNNING`. |

\* Provide exactly one of `testClasses`, `testMethods`, or `includePattern`/`includePatterns` (patterns also require `tasks`). Optional `taskPath` and `tasks` scope the selected tests. Unscoped `testClasses`/`testMethods` in a multi-project build infer `taskPath` when unambiguous (`taskPathInferred: true`).

#### Selector decision table

| Goal | Arguments |
|------|-----------|
| One default `:test` task, class list | `taskPath: ":mod:test"` + `testClasses` |
| One task, method map | `taskPath` + `testMethods` |
| Custom suite only (`fastTest`) | `taskPath: ":mod:fastTest"` + classes/methods, **or** `tasks: [":mod:fastTest"]` + `includePatterns` |
| Several Test tasks / suites in one build | `tasks: [":mod:test", ":mod:fastTest"]` + `includePatterns` |
| Whole Test task / suite (no class/method/pattern filter) | Use `gradle_run_tasks` with `tasks: [":mod:test"]`. Selector-less `gradle_run_tests` returns `INVALID_ARGUMENT` with `hint` |
| Multi-project unscoped classes/methods | Infers `taskPath` when unambiguous (package-suffix tokens vs subproject path segments; `taskPathInferred: true` on success). Otherwise `INVALID_ARGUMENT` with up to 20 `suggestedTaskPaths` (JVM Test task paths from the model: name `test` or `*Test`), `suggestedTaskPathsTruncated: true` when capped, and `hint` when the model is available. Use `gradle_get_project_model` with `includeTasks=true` for the full task list. |

`taskPath` and `tasks` both scope execution with `TestLauncher.forTasks()` plus `withTestsFor` class/method/pattern filters (Gradle 7.6+). If TestLauncher cannot resolve a scoped task (for example some JvmTestSuite `test` tasks), the server retries once with `BuildLauncher.forTasks` and `--tests` (same path as `gradle_run_tasks`). With patterns, each listed task gets the same `includePatterns`.

**Concurrency:** Only one MCP build may **run** per `projectDirectory`. Parallel `background: true` `gradle_run_tests` calls enqueue (`queueIfBusy` defaults true; max 3 queued; `BUILD_QUEUE_FULL` when saturated). Foreground overlap or `queueIfBusy=false` returns `BUILD_ALREADY_RUNNING` with `activeBuildId` and related fields. The single-flight gate clears as soon as the build is terminal in memory (no grace window). Foreground starts still need a terminal poll or cancel before the next foreground call. Batch multiple classes, methods, **or Test tasks** in one call via `testMethods` / `testClasses` / `tasks`+`includePatterns`. Parallel test runs are only supported across **different** `projectDirectory` values, up to the server concurrent-build limit (see Connection section).

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
| `includeProblems` | no | `false` | Live Problems API as `liveProblems` while running. Terminal `failed` + `GRADLE_TASK` already includes capped `problems` when emitted (no flag needed). Re-poll with `true` if terminal `problems` is missing — do not re-run via CLI for compiler output. `includeOutput` tails often miss Kotlin `Compilation error. See log for more details` |
| `includeOutput` | no | `false` | Include stdout/stderr for running/completed builds. **Avoid `true` while `status` is `running`** unless debugging—prefer `sinceStdoutOffset` / `sinceStderrOffset` for incremental output, or read `testFailures` / `buildSummary` / `problems` on terminal failure |
| `maxOutputChars` | no | `8000` | Per-stream char limit when `includeOutput=true` |
| `tailOutput` | no | `true` | Keep tail when truncating |
| `sinceStdoutOffset` | no | — | With `includeOutput=true`, return `stdoutDelta` from this char offset (plus `stdoutOffset` for the next poll) instead of repeating the full tail |
| `sinceStderrOffset` | no | — | Same as `sinceStdoutOffset` for stderr |
| `waitUntilComplete` | no | `false` | Server-side wait until terminal status or `waitTimeoutMs` (independent of MCP client transport timeout) |
| `waitTimeoutMs` | no | `30000` | Max **server-side** wait when `waitUntilComplete=true` (capped at `60000`) |
| `pollIntervalMs` | no | `2000` | Server-side poll interval while waiting |

**Client vs server timeout:** `waitTimeoutMs` applies only inside this server. MCP hosts (e.g. Cursor) may kill the tool call earlier with a transport timeout such as `-32001` (often ~90s). For multi-minute builds, prefer `background: true` at start or rely on foreground auto-detach (~45s) which returns `buildId` + `detached: true`; then poll without `includeOutput` until terminal. Prefer plain polls (`waitUntilComplete` false/omitted) or short waits; treat `waitTimedOut` as “still running, poll again”, not server death. Without wait, status reads memory and/or `.gradle/mcp-builds/` only—no Tooling API call.

Returns `status` (`queued`, `running`, `succeeded`, `failed`, `cancelled`, or `not_found`), timestamps, `outcome`, and `buildSummary`. Always includes `statusSource` (`memory` or `disk`). Disk-backed responses also include `liveProgress` (`false`), `progressAvailable`, and `recordDirectory`. While memory reports `running`, memory status wins and disk `events.ndjson` task events are merged into `progress`; `recordDirectory` is included when disk artifacts exist. When memory is terminal or absent and memory and disk disagree, Gradle on-disk status wins while Gradle is still active; stale Gradle `running` (MCP terminal, no post-finalize events in `events.ndjson`) falls back to MCP. Completed builds include `failedTaskCount`, `failedTasks`, and `buildSummary.failureSummary` without `includeProgress` when available (in-memory, MCP-terminal disk, or Gradle-terminal failed with `events.ndjson`). Failed test runs also include `testFailures` (structured `className`, `methodName`, `exceptionType`, `message`, `sourceFile`, `line`) and `failedTestCount` without `includeOutput` or `includeTestDetails`. Terminal failures include `failureKind` and `failureCategory` (`TEST`, `GRADLE_TASK`, `TOOLING_CONNECTION`, `CANCELLED`). Failed `GRADLE_TASK` includes a capped `problems` array by default when the Problems API emitted them; re-poll with `includeProblems: true` if missing, and do not re-run via CLI for compiler output (`includeOutput` tails often miss Kotlin `Compilation error. See log for more details`). Persisted in `mcp-result.json` under `.gradle/mcp-builds/<buildId>/`. `stdout`/`stderr` are included only when `includeOutput=true`. When `sinceStdoutOffset` / `sinceStderrOffset` are set, responses use `stdoutDelta` / `stderrDelta` and `stdoutOffset` / `stderrOffset` so agents do not re-read prior log prefixes. When `waitUntilComplete=true` and the build is still running when `waitTimeoutMs` elapses, the response includes `waitTimedOut: true`, `waitedMs`, and `hint` (poll again; do not treat as MCP failure). While running, live output requires an in-memory record; disk-only polls return streams only after MCP finalizes logs at build end. `progress` only when `includeProgress=true`; with an in-memory record, includes `CONFIG_*` events from the live `ProgressListener` (task, test, and project-configuration) merged with disk `events.ndjson` task/test events plus capped lists. Disk-only polls read `events.ndjson` (task and test events only—not project-configuration, which the init script does not write).

#### includeProgress / includeProblems / includeDownloads / includeTestDetails

| Flag | Default | Effect |
|------|---------|--------|
| `includeProgress` | `false` | `progress.completedTasks`, `progress.recentEvents` (live Tooling API or disk `events.ndjson`) |
| `includeProblems` | `false` | Live Gradle Problems API as `liveProblems` while running. Failed `GRADLE_TASK` status includes a **capped** `problems` array by default (merged failure-result + live events). Re-poll with this flag if terminal `problems` is missing. Do not re-run via CLI for compiler output; `includeOutput` tails often miss Kotlin `Compilation error. See log for more details`. |
| `includeDownloads` | `false` | `activeDownloadCount`, `recentDownloads` (requires in-memory live record) |
| `includeTestDetails` | `false` | Terminal `failedTests`; with `includeProgress=true`, adds `progress.recentEvents[].test` on `TEST_*` events. Disk polls restore `failedTests` from `events.ndjson` (`className`, `methodName`, `failureMessage`; `sourcePath`/`sourceLine` need live Tooling API) |


## Dependency sources

### gradle_index_dependency_sources

| Argument | Required | Description |
|----------|----------|-------------|
| `tokenMode` | no | `all` (default) or `idents` |
| `artifacts[]` | no | Explicit GAVs; skips Idea keep-set |
| `sourcePaths[]` | no | Local trees/jars with optional GAV labels |
| `gradleUserHome` | no | Cache home for `artifacts[]` jar lookup |
| `indexDir` | no | Override index directory |
| `forceReindex` | no | Rebuild even on fingerprint hit |

### gradle_search_dependency_sources / gradle_search_dependency_sources_multi

Exact simple-name locate against a prior index. Optional `limit` / `perQueryLimit` (`per_query_limit` alias): omit = unlimited; `0` = empty.

### gradle_read_dependency_source

| Argument | Required | Description |
|----------|----------|-------------|
| `path` | yes | Path inside sources jar/tree (from a search hit) |
| `gav` | one of | `group:name:version` |
| `group`+`name`+`version` | one of | Alternative to `gav` |
| `line` | no | 1-based anchor; must be within the file |
| `contextLines` | no | Window around `line` (default 10) |
| `maxLines` | no | Cap when `line` omitted (default 200) |
| `sourceRoot` | no | Explicit jar/zip/dir/file. Optional when search hit / `source-roots.tsv` provides it, or a `*-sources.jar` exists in Maven local / Gradle caches. Pass when neither index nor cache can resolve the root |
| `tokenMode` | no | Which index side-car to consult (`all`/`idents`; omit tries `all` then `idents`) |
| `indexDir` | no | Override index directory |
| `gradleUserHome` | no | Cache home for jar lookup; else connected project |

Returns `snippet`, `startLine`, `endLine`, `lineCount`, `truncated`, and resolved `sourceRoot`.

Search hits may include `sourceRoot` from the index side-car `source-roots.tsv` (written at index time). `gradle_read_dependency_source` uses that path when `sourceRoot` is omitted and cache jar lookup misses. `contextLines` max 100; `maxLines` max 2000.

## MCP tool discovery (token-efficient)

`tools/list` returns every tool name, description, and `inputSchema`. For Cursor agents, prefer lazy discovery:

1. Prefer `server` + `toolName` for tools you will call, or `pattern` when the server id is unknown
2. Use **no arguments** (full catalog) only as a last resort — it is token-heavy
3. Avoid `mcp_get_tools` with `server` only unless you need every schema at once

Detailed parameter semantics live in this reference (Layer 3). Tool `description` fields are summaries (Layer 1).

## Errors

### Tool errors vs build outcomes

- **Tool errors** (`isError=true`): structured `{ "error": { "code", "message", ... } }` for preflight failures (`NOT_CONNECTED`, `BUILD_ALREADY_RUNNING`, `INVALID_ARGUMENT`, …). `BUILD_ALREADY_RUNNING` and `BUILD_QUEUE_FULL` include `activeBuildId`, `activeKind`, `activeStatus`, and task/test fields (`activeTasks`, `activeTestClasses`, …) when the occupying build is known. `activeStatus` reflects the occupying record (`running` or `queued`), not the error code name. Global pool saturation returns `activeBuildIds` when multiple builds are running. Multi-project `gradle_run_tests` without `taskPath`/`tasks` infers `taskPath` when unambiguous (`taskPathInferred: true`); otherwise returns `INVALID_ARGUMENT` with up to 20 `suggestedTaskPaths`, optional `suggestedTaskPathsTruncated: true`, and `hint` when the Gradle project model is available. `gradle_run_tests` without `testClasses` / `testMethods` / `includePattern(s)` returns `INVALID_ARGUMENT` with `hint` to call `gradle_run_tasks` for the whole suite.
- **Build outcomes** (`isError=false`): `gradle_run_tasks` / `gradle_run_tests` foreground responses and `gradle_get_build_status` terminal polls return `status: "failed"` / `outcome: "FAILED"` with `buildSummary`—not `error.code: BUILD_FAILED`. On `failureCategory: GRADLE_TASK`, read capped `problems` (default when emitted) instead of shelling out for compiler output.
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

Codes: `NOT_CONNECTED`, `BUILD_ALREADY_RUNNING` (active/queued build for the same `projectDirectory`, or max concurrent background builds reached), `BUILD_QUEUE_FULL` (per-project queue saturated), `INVALID_ARGUMENT`, `PROJECT_NOT_FOUND`, `BUILD_FAILED`, `INTERNAL_ERROR`. `BUILD_ALREADY_RUNNING` / `BUILD_QUEUE_FULL` add `activeBuildId`, `activeKind`, `activeStatus`, and task/test fields when known; global pool saturation may return `activeBuildIds` (array) instead of a single `activeBuildId`.

## Environment variables (server startup)

| Variable | Effect |
|----------|--------|
| `GRADLE_PROJECT_DIR` | Auto-connect on start |
| `GRADLE_USER_HOME` | Default user home |
| `GRADLE_VERSION` | Default Gradle version |
| `GRADLE_INSTALLATION` | Default local install |
