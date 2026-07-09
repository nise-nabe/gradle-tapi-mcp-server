## Learned User Preferences

- Prefer Japanese for technical explanations and discussion.
- Use `com.example` for Maven group and `com.example.gradle.mcp` for Kotlin package names; do not use personal or misleading domains (e.g. `dev.nisenabe`, `org.gradle.mcp`).
- Do not commit local machine paths or personal identifiers into published files; use generic placeholders in README and examples.
- If personal info was pushed to GitHub, rewrite git history and force-push to remove it completely rather than only fixing HEAD.
- When refactoring Gradle build scripts, align with the gradle-build-script skill (Version Catalog, centralised repositories in settings, JVM Test Suites).
- Use `project-context-ingestion` for declared build constraints; reserve Gradle MCP for resolved runtime Gradle/Java, build verification, and task execution.
- When asked to commit (and push), split changes into semantic commits by meaningful concern (build / feat / test / docs); use the `semantic-commits` skill when appropriate, or `rework-commits` when rewriting existing branch history (publish with `git push --force-with-lease`).
- Prefer Kotest for list and nullable collection assertions in tests; avoid `!!` combined with `assertTrue`/`assertFalse`/`isEmpty()`.
- When splitting sources into feature subpackages, keep `GradleTapiMcpServer` (entry point) in the root `com.example.gradle.mcp` package; move tool handlers and schemas into feature packages.

## Learned Workspace Facts

- Standalone MCP server exposing Gradle Tooling API over stdio to MCP clients.
- GitHub repository: `nise-nabe/gradle-tapi-mcp-server` (public, default branch `main`).
- Stack: Kotlin 2.4.0, Java 17 toolchain, **Kotlin** MCP SDK 0.14.0 (`io.modelcontextprotocol:kotlin-sdk-server`), Gradle Tooling API 9.6.1, kotlinx.serialization（ツール結果 JSON・MCP ワイヤ）。
- Build uses `gradle/libs.versions.toml`, `dependencyResolutionManagement` with `FAIL_ON_PROJECT_REPOS`, JVM Test Suites (JUnit 5), and Configuration Cache enabled.
- Single-module project with feature subpackages (`build`, `cache`, `connection`, `model`, `protocol`, `server`) under `com.example.gradle.mcp`; MCP tool definitions live in each feature package with shared helpers in `protocol`; `build-logic` deferred until multi-module need arises.
- `gradle-wrapper.jar` is explicitly un-ignored so clones can run `./gradlew`.
- Cursor Cloud bootstraps via `.cursor/environment.json` → `.cursor/install.sh` (release JAR download, gh symlink, JDK 17, `./gradlew build`).
- Agent skill at `skills/gradle-tapi-mcp/` (install to `~/.cursor/skills/` globally) documents token-efficient MCP workflows: prefer `gradle_get_project_overview`; use `gradle_get_build_cache_status` for cache settings; task lists omitted unless `includeTasks=true`; run output omitted by default (`includeOutput=false`; outcome/buildSummary only).
- Agent skill at `skills/release/` (summary in `.cursor/skills/release/SKILL.md`) documents the GitHub release workflow: version bump PR, JAR build, tag, Release asset upload, and `install.sh` SHA-256 follow-up.
- MCP server holds a **connection pool** keyed by canonical project path; `gradle_connect` ensures a project without disconnecting others. Optional `projectDirectory` on tools defaults to `GRADLE_PROJECT_DIR`.
- MCP tool errors use structured `McpException` with `McpErrorCode` (`NOT_CONNECTED`, `BUILD_ALREADY_RUNNING`, `INVALID_ARGUMENT`, `PROJECT_NOT_FOUND`, `BUILD_FAILED`, `INTERNAL_ERROR`); `mapExceptionToErrorCode` maps legacy `IllegalStateException` messages.
- Long Gradle builds: set `background=true` on `gradle_run_tasks`/`gradle_run_tests`, then poll `gradle_get_build_status` for progress and partial output; call `gradle_cancel_build` to stop unneeded background runs.
- Cursor MCP config (`.cursor/mcp.json`) launches the JAR from `~/.local/share/gradle-tapi-mcp-server/gradle-tapi-mcp-server.jar` with `GRADLE_PROJECT_DIR=${workspaceFolder}`; global `~/.cursor/mcp.json` works for other Gradle projects using a release JAR.
- After MCP server code changes, rebuild the JAR (`./gradlew jar` or re-run `.cursor/install.sh`) and restart MCP servers in Cursor.
- `main` is branch-protected; push feature branches and open PRs instead of pushing directly to `main`.

## Cursor Cloud specific instructions

Single-module Kotlin/JVM MCP server (stdio). No web UI, Docker, or dedicated lint task.

### Bootstrap

`.cursor/environment.json` runs `.cursor/install.sh` on every Cloud Agent session:

1. Downloads release **v0.4.0** JAR (SHA-256 verified) to `~/.local/share/gradle-tapi-mcp-server/gradle-tapi-mcp-server.jar` — **before** `./gradlew` so MCP can drive this repo's build
2. Configures `gh` from `/exec-daemon/gh` (optional `GH_TOKEN` / `GITHUB_TOKEN` login)
3. Ensures **JDK 17** for `./gradlew` (toolchain in `build.gradle.kts`; JDK 21+ can run the MCP JAR at runtime)
4. `./gradlew build` as the compile/test gate

The `gradle` MCP server is defined in `.cursor/mcp.json`. Token-efficient workflows: `.cursor/skills/gradle-tapi-mcp/SKILL.md` (summary) and `skills/gradle-tapi-mcp/` (full reference). Release workflow: `.cursor/skills/release/SKILL.md` (summary) and `skills/release/` (full reference).

### GitHub and pull requests (Cursor Cloud)

| Goal | Preferred approach |
|------|-------------------|
| Create or update a PR | Built-in **ManagePullRequest** tool (`create_pr` / `update_pr`) |
| Edit PR labels | **EditPullRequestLabels** tool |
| Verify changes locally | `./gradlew build` or Gradle MCP `gradle_run_tasks` with `["build"]` |
| PR check status / CI logs | `gh` only after `gh auth status` succeeds (see `.cursor/skills/cloud-github/SKILL.md`) |

Do not rely on bare `gh` before install completes. Set `GH_TOKEN` in Cursor Cloud Secrets when the GitHub App token lacks required scopes.

### Build, test, run

| Goal | Command |
|------|---------|
| Build JAR | `./gradlew jar` → `build/libs/gradle-tapi-mcp-server-0.4.0.jar` |
| Unit tests | `./gradlew test` (JUnit 5; mocked Tooling API, no Gradle daemon) |
| Full verify | `./gradlew build` (compile + test + assemble) |
| Lint | Not configured; use `./gradlew build` as compile/test gate |
| Run MCP server | `GRADLE_PROJECT_DIR=/workspace java -jar build/libs/gradle-tapi-mcp-server-0.4.0.jar` |

Logging goes to **stderr** only; **stdout** is reserved for MCP JSON-RPC (newline-delimited JSON).

### E2E smoke test (MCP + Gradle Tooling API)

After `./gradlew jar`, drive the server over stdio: send `initialize` → `notifications/initialized` → `tools/list` → `tools/call` for `gradle_connection_status` and `gradle_get_project_overview` with `GRADLE_PROJECT_DIR` set to a Gradle project (this repo works). Expect `connected: true`, the resolved Gradle version of the connected project (this repo: wrapper **9.6.1**), and project name `gradle-tapi-mcp-server`.

`GradleTapiMcpServerLauncherSmokeTest` runs during `./gradlew build` (jar + initialize smoke). Optional local benchmark: `scripts/measure_startup.py` after `./gradlew jar`.

There are no automated MCP integration tests in the repo; unit tests under `src/test/` do not start the MCP server or Gradle daemon.

### Services

Only the **MCP server process** and an implicit **Gradle daemon** (via Tooling API when connected) are involved. No database or HTTP server.
