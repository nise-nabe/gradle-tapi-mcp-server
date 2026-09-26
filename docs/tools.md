# Tools and resources

## Tools

| Tool | Description |
|------|-------------|
| `gradle_connect` | Connect to a project directory (keeps other projects connected). First connect may download the Gradle distribution; download progress is emitted as log notifications and MCP request cancellation aborts it |
| `gradle_connection_status` | Connection state for one project or all session-known connections (`connections[]`, `defaultProjectDirectory`; `connecting: true` while a connect/distribution download is in flight). Cache-only by default; set `refresh: true` when `runtimeStackAvailable` is false and you need `gradleVersion` / `javaHome`. Listing all connections with `refresh: true` fetches once per connected project |
| `gradle_disconnect` | Release this session's hold on one project (`projectDirectory`), the session default (omit), or every connection on the server (`all: true`). A shared pooled connection closes when its last session releases it (`retainedByOtherSessions`) |
| `gradle_get_build_environment` | Gradle/Java environment including `javaVersion` and `versionInfo` (`gradle --version` text on Gradle 9.4+; lightweight) |
| `gradle_get_java_runtimes` | Daemon Java from BuildEnvironment plus detected local JDKs via `javaToolchains -q` (set `includeToolchains: false` for daemon only) |
| `gradle_get_help` | Gradle CLI help text (`gradle --help` equivalent); optional `maxChars` / `tailOutput`; requires Gradle 9.4+ |
| `gradle_get_build_cache_status` | Build cache / configuration cache settings and local cache summaries |
| `gradle_get_project_overview` | Project hierarchy and task counts only; optional `projectPath`, `buildTreePath`, `maxDepth` / `maxChildren`. Gradle 9.4+ may return `partial` + capped `failures` |
| `gradle_get_gradle_build` | GradleBuild structure (root project tree, all projects, included/editable builds); optional `maxDepth` / `maxChildren`. Gradle 9.4+ may return `partial` + capped `failures` |
| `gradle_get_project_model` | Project model; tasks omitted by default; optional `projectPath` or `buildTreePath`. Gradle 9.4+ may return `partial` + capped `failures` |
| `gradle_get_build_invocations` | Runnable tasks; selectors omitted by default; optional `projectPath` or `buildTreePath`. Gradle 9.4+ may return `partial` + capped `failures` |
| `gradle_get_project_publications` | Publications; optional `buildTreePath` for included/`buildSrc`. Gradle 9.4+ may return `partial` + capped `failures` |
| `gradle_get_dependency_resolution` | Resolved dependency graph via Tooling API `ResolutionResult` (no task run). Omit `configuration` to list resolvable/consumable names (optional `includeAttributes` / `includeOutgoingVariants`; catalog cap 200). With `configuration`: optional `projectPath`, `dependency` filter, `maxDependencies` / `maxComponents` (default 500). Unknown names return `suggestedConfigurations` |
| `gradle_run_tasks` | Execute tasks; stdout/stderr truncated by default |
| `gradle_run_tests` | Execute JVM tests by class, method, pattern, or task scope; stdout/stderr truncated by default |
| `gradle_get_task_execution_plan` | Ordered task execution plan via dry run (no task actions run); returns `taskPlan` `{taskCount, tasks[{path, state}]}`. Foreground only; busy → `BUILD_ALREADY_RUNNING` + `activeBuildId` |
| `gradle_list_builds` | List recent MCP builds from memory and `.gradle/mcp-builds/` (no Tooling API required) |
| `gradle_get_build_status` | Poll status/output for a background build (`buildId` required); set `includeProgress: true` for detailed progress |
| `gradle_cancel_build` | Cancel a background build via Tooling API `CancellationToken` (`buildId` required) |
| `gradle_index_dependency_sources` | Index sources for exact simple-name locate (project Idea keep-set **or** any Maven GAV via `artifacts[]`, including versions not in the graph). `tokenMode`: `all` (default; includes comments/strings) or `idents`. Keep-set: Idea sources by default (optional `projectPath` to scope a Gradle project subtree), or explicit `artifacts[]` / `sourcePaths[]`. `background: true` returns `indexId` immediately; foreground auto-detaches after ~45s with `detached: true` + `indexId`. Poll with `gradle_get_dependency_sources_index_status`. The **server** looks up `artifacts[]` jars via optional `gradleUserHome`, else the connected project's Gradle user home, else process `GRADLE_USER_HOME`/`~/.gradle` — do not list those directories. Optional `downloadSources: true` fetches missing `*-sources.jar` (default Maven Central; override with `sourcesRepositories` for corporate mirrors) into `.gradle/mcp-dependency-sources/jars/`. Persists under `.gradle/mcp-dependency-sources/<tokenMode>/` |
| `gradle_get_dependency_sources_index_status` | Poll a background/detached dependency-sources index job (`indexId` required) |
| `gradle_search_dependency_sources` | Exact simple-name locate against a prior index (does not reindex). Optional `limit` (omit = unlimited; `0` = empty) |
| `gradle_search_dependency_sources_multi` | Multi-name OR locate with dedup and `matchedQueries`; optional `limit` and `perQueryLimit` (`per_query_limit` alias) |
| `gradle_read_dependency_source` | Read a UTF-8 snippet from a dependency `*-sources.jar` **or** dir/file via `sourceRoot`, using `gav`/`group`+`name`+`version` and `path`. Optional `line` + `contextLines` (default 10, max 100); omit `line` to read from the start up to `maxLines` (default 200, max 2000). Cache jars resolve by coordinates; Idea directories / `sourcePaths` use indexed `sourceRoot` (or an explicit arg) |

## Resources

MCP **resources** are optional host context. They wrap the same handlers as the tools above (`application/json`). **Do not treat them as a replacement for tools** — agents should keep calling tools until the MCP client actually attaches resources. This server does not remove or deprecate any tool.

URI scheme: `gradle-tapi://{url-encoded-absolute-project-root}/…`

| URI | Wraps | Notes |
|-----|--------|-------|
| `…/connection/status` | `gradle_connection_status` | `?refresh=true` live-fetches `BuildEnvironment` when the cache is empty |
| `…/environment` | `gradle_get_build_environment` | Resolved Gradle/Java, not Version Catalog files |
| `…/overview` | `gradle_get_project_overview` | Optional `?projectPath=` or `?buildTreePath=` |
| `…/builds/{buildId}/status` | `gradle_get_build_status` | Default omits stdout and progress (same as the tool) |
| `…/builds/recent` | `gradle_list_builds` | Memory + `.gradle/mcp-builds/`; no Tooling API |

`resources/templates/list` always advertises those five templates. `resources/list` lists concrete URIs for the projects the session knows (its workspace plus projects it connected; typically `GRADLE_PROJECT_DIR` auto-connect), except `builds/{buildId}/status`, which stays template-only until a `buildId` is known. Later `gradle_connect` / `gradle_disconnect` do not change that list (`listChanged` is off). `resources/read` still works for any encoded project root that matches a template. Subscribe is not enabled.

While an MCP build is active for a project, the **overview** resource **rejects** the read. The JSON-RPC error `data.error` matches the tool payload (`code`: `BUILD_ALREADY_RUNNING`, `message`, `activeBuildId` and related fields). It does not return a stale tree. Connection status, environment, build status, and recent builds follow the corresponding tools.
