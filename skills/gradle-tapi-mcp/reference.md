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

`gradle_disconnect` is non-blocking. If a build was active, the response may include `warning` explaining that the Gradle daemon can briefly overlap work until the prior Tooling API call unwinds.

`gradle_connect` clears in-memory build tracking before connecting and fails fast while a build slot is still held.

## Query (read-only)

### gradle_get_build_environment

No arguments. Returns `gradle.gradleVersion`, `gradle.gradleUserHome`, `java.javaHome`, `java.jvmArguments`.

### gradle_get_project_overview

No arguments. Returns hierarchy with `taskCount` per project; no task lists.

### gradle_get_project_model

| Argument | Default | Description |
|----------|---------|-------------|
| `includeTasks` | `false` | Include task arrays |
| `includeTaskDetails` | `false` | Add `description`, `displayName` per task |
| `taskGroup` | — | Filter by Gradle task group |
| `taskNamePrefix` | — | Filter by task name prefix |
| `maxTasks` | — | Cap after filtering |

Slim task shape (default): `{ name, path, group }`.

### gradle_get_build_invocations

Same as `gradle_get_project_model`, plus:

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
| `background` | no | `false` | Return `buildId` immediately; poll with `gradle_get_build_status` |

Response fields when `background=true`: `buildId`, `status`, `kind`, `message`.

Foreground responses also include `progress` with completed/running/failed task counts and recent events.

### gradle_run_tests

| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `testClasses` | yes | — | FQCN list |
| `arguments` | no | `[]` | Extra Gradle CLI args |
| `jvmArguments` | no | `[]` | JVM args |
| `maxOutputChars` | no | `8000` | Per-stream char limit |
| `tailOutput` | no | `true` | Keep tail when truncating |
| `background` | no | `false` | Return `buildId` immediately; poll with `gradle_get_build_status` |

### gradle_get_build_status

| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `buildId` | no | active/latest | Build ID from a background run |
| `maxOutputChars` | no | `8000` | Per-stream char limit for stdout/stderr |
| `tailOutput` | no | `true` | Keep tail when truncating |

Returns `status`, `progress`, timestamps, and partial or final `stdout`/`stderr`.

## Environment variables (server startup)

| Variable | Effect |
|----------|--------|
| `GRADLE_PROJECT_DIR` | Auto-connect on start |
| `GRADLE_USER_HOME` | Default user home |
| `GRADLE_VERSION` | Default Gradle version |
| `GRADLE_INSTALLATION` | Default local install |
