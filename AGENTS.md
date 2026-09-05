## Learned User Preferences

- Prefer Japanese for technical explanations and discussion with the user.
- Write GitHub issues, pull requests, and other repository-facing GitHub text in English (titles, descriptions, comments).
- Use `com.example` for Maven group and `com.example.gradle.mcp` for Kotlin package names; do not use personal or misleading domains (e.g. `dev.nisenabe`, `org.gradle.mcp`).
- Do not commit local machine paths or personal identifiers into published files; use generic placeholders in README and examples.
- If personal info was pushed to GitHub, rewrite git history and force-push to remove it completely rather than only fixing HEAD.
- When refactoring Gradle build scripts, align with the gradle-build-script skill (Version Catalog, centralised repositories in settings, JVM Test Suites).
- Use `project-context-ingestion` for declared build constraints; reserve Gradle MCP for resolved runtime Gradle/Java, build verification, and task execution.
- When asked to commit (and push), split changes into semantic commits by meaningful concern (build / feat / test / docs); use the `semantic-commits` skill when appropriate, or `rework-commits` when rewriting existing branch history (publish with `git push --force-with-lease`).
- Prefer Kotest for list and nullable collection assertions in tests; avoid `!!` combined with `assertTrue`/`assertFalse`/`isEmpty()`.
- When splitting sources into feature subpackages, keep entry-point classes in the root `com.example.gradle.mcp` package; see `gradle-tapi-mcp-server-dev` for the canonical list and subpackage ownership.
- Read `workflow-router` first for `/thermos`, PR review comments, or issue-driven work — then only the matching on-demand skill; do not load unrelated skills in full.

## Learned Workspace Facts

- Standalone MCP server exposing Gradle Tooling API over stdio to MCP clients.
- GitHub repository: `nise-nabe/gradle-tapi-mcp-server` (public, default branch `main`).
- Stack: Kotlin 2.4.10, Java 17 toolchain, **Kotlin** MCP SDK 0.15.0 (`io.modelcontextprotocol:kotlin-sdk-server`), Gradle Tooling API 9.7.0, kotlinx.serialization（ツール結果 JSON・MCP ワイヤ）。
- Build uses `gradle/libs.versions.toml`, `dependencyResolutionManagement` with `FAIL_ON_PROJECT_REPOS`, JVM Test Suites (JUnit 5), Configuration Cache, and Isolated Projects (`org.gradle.isolated-projects=true`).
- Single-module project with feature subpackages (`build`, `cache`, `connection`, `model`, `protocol`, `server`) under `com.example.gradle.mcp`; MCP tool definitions live in each feature package with shared helpers in `protocol`; `build-logic` deferred until multi-module need arises.
- `gradle-wrapper.jar` is explicitly un-ignored so clones can run `./gradlew`.
- Cursor Cloud bootstraps via `.cursor/environment.json` → `.cursor/install.sh` (release JAR download, gh symlink, JDK 17/21).
- Agent skill at `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/` (install via Cursor/Codex/Copilot marketplace, or copy to `~/.cursor/skills/` globally) documents token-efficient MCP workflows: prefer `gradle_get_project_overview`; use `gradle_get_build_cache_status` for cache settings; task lists omitted unless `includeTasks=true`; run output omitted by default (`includeOutput=false`; outcome/buildSummary only).
- Agent skill at `skills/release/` (summary in `.cursor/skills/release/SKILL.md`) documents the GitHub release workflow: version bump PR, JAR build, tag, Release asset upload, and `install.sh` SHA-256 follow-up.
- MCP server holds a **connection pool** keyed by canonical project path; `gradle_connect` ensures a project without disconnecting others. Optional `projectDirectory` on tools defaults to `GRADLE_PROJECT_DIR`.
- MCP tool errors use structured `McpException` with `McpErrorCode` (`NOT_CONNECTED`, `BUILD_ALREADY_RUNNING`, `BUILD_QUEUE_FULL`, `INVALID_ARGUMENT`, `PROJECT_NOT_FOUND`, `BUILD_FAILED`, `INTERNAL_ERROR`); `mapExceptionToErrorCode` maps legacy `IllegalStateException` messages.
- Long Gradle builds: set `background=true` on `gradle_run_tasks`/`gradle_run_tests`, then poll `gradle_get_build_status` (prefer plain short polls; `waitUntilComplete` is server-side only and capped—do not rely on one long wait vs MCP client timeouts); a second background run for the same project enqueues (`queueIfBusy` defaults true); call `gradle_cancel_build` to stop unneeded background runs.
- Cursor MCP config (`.cursor/mcp.json`) launches the JAR from `~/.local/share/gradle-tapi-mcp-server/gradle-tapi-mcp-server.jar` with `GRADLE_PROJECT_DIR=${workspaceFolder}`; global `~/.cursor/mcp.json` works for other Gradle projects using a release JAR.
- After MCP server code changes, rebuild the JAR (`./gradlew jar` or re-run `.cursor/install.sh`) and restart MCP servers in Cursor.
- `main` is branch-protected; push feature branches and open PRs instead of pushing directly to `main`.

## Cursor Cloud specific instructions

Single-module Kotlin/JVM MCP server (stdio). No web UI, Docker, or dedicated lint task.

### Bootstrap

`.cursor/environment.json` runs `.cursor/install.sh` on every Cloud Agent session:

1. Downloads release JAR (version in `.cursor/install.sh` `GRADLE_TAPI_MCP_VERSION`, currently **0.7.1**) with SHA-256 verification to `~/.local/share/gradle-tapi-mcp-server/gradle-tapi-mcp-server.jar` so MCP can drive this repo's build when needed
2. Configures `gh` from `/exec-daemon/gh` (optional `GH_TOKEN` / `GITHUB_TOKEN` login)
3. Ensures **JDK 17** for `./gradlew` (toolchain in `build.gradle.kts`; JDK 21+ can run the MCP JAR at runtime)

The `gradle` MCP server is defined in `.cursor/mcp.json`. Token-efficient workflows: `.cursor/skills/gradle-tapi-mcp/SKILL.md` (summary) and `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/` (full reference). Release workflow: `.cursor/skills/release/SKILL.md` (summary) and `skills/release/` (full reference). Marketplace catalogs: `.cursor-plugin/marketplace.json` (Cursor), `.agents/plugins/marketplace.json` (Codex), `.github/plugin/marketplace.json` (GitHub Copilot).

### Token-efficient agent workflow

| Layer | Location | Purpose |
|-------|----------|---------|
| Always-apply rules | `.cursor/rules/gradle-mcp.mdc`, `agent-workflow.mdc`, `pr-description-format.mdc`, `cloud-github.mdc` | MCP vs shell verify, checkout-first PR work, PR body format, GitHub tooling |
| Skill router | `.cursor/skills/workflow-router/SKILL.md` | Pick one on-demand skill — avoid loading the wrong playbook |
| On-demand skills | `issue-to-pr`, `pr-review-response`, `thermo-nuclear-review`, `copilot-review-preflight` | Issue/PR/Thermo workflows with explicit token budgets |
| Domain skill | `.cursor/skills/gradle-tapi-mcp-server-dev/SKILL.md` | Package placement, MCP tool patterns, test conventions |

**Server code changes:** verify with shell `./gradlew` (release JAR from bootstrap cannot compile the server you are editing). **Docs-only changes:** canonical verify in `gradle-mcp.mdc` (MCP `gradle_run_tasks` with `background: true` + poll, or `./gradlew build`).

### GitHub and pull requests (Cursor Cloud)

| Goal | Preferred approach |
|------|-------------------|
| Create or update a PR | Built-in **ManagePullRequest** tool (`create_pr` / `update_pr`) |
| Post PR comment or resolve thread | **ManagePullRequest** (`post_comment`, `resolve_comment`) |
| PR CI status | **ManagePullRequest** (`get_ci_status`) or `gh pr checks` when auth works |
| Edit PR labels | **EditPullRequestLabels** tool |
| Verify changes locally | `./gradlew build` or canonical MCP verify in `gradle-mcp.mdc` |

Do not rely on bare `gh` before install completes. Set `GH_TOKEN` in Cursor Cloud Secrets when the GitHub App token lacks required scopes.

### Build, test, run

| Goal | Command |
|------|---------|
| Build JAR | `./gradlew jar` → `build/libs/gradle-tapi-mcp-server-0.7.1.jar` |
| Unit tests | `./gradlew test` (JUnit 5; mocked Tooling API, no Gradle daemon) |
| Full verify | `./gradlew build` (compile + test + assemble) |
| Lint | Not configured; use `./gradlew build` as compile/test gate |
| Run MCP server | `GRADLE_PROJECT_DIR=/workspace java -jar build/libs/gradle-tapi-mcp-server-0.7.1.jar` |

Logging goes to **stderr** only; **stdout** is reserved for MCP JSON-RPC (newline-delimited JSON).

### E2E smoke test (MCP + Gradle Tooling API)

After `./gradlew jar`, drive the server over stdio: send `initialize` → `notifications/initialized` → `tools/list` → `tools/call` for `gradle_connection_status` and `gradle_get_project_overview` with `GRADLE_PROJECT_DIR` set to a Gradle project (this repo works). Expect `connected: true`, the resolved Gradle version of the connected project (this repo: wrapper **9.7.0**), and project name `gradle-tapi-mcp-server`.

`GradleTapiMcpServerLauncherSmokeTest` runs during `./gradlew build` (jar + initialize smoke). Optional local benchmark: `scripts/measure_startup.py` after `./gradlew jar`.

There are no automated MCP integration tests in the repo; unit tests under `src/test/` do not start the MCP server or Gradle daemon.

### Services

Only the **MCP server process** and an implicit **Gradle daemon** (via Tooling API when connected) are involved. No database or HTTP server.
