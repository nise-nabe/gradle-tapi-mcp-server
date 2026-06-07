## Learned User Preferences

- Prefer Japanese for technical explanations and discussion.
- Use `com.example` for Maven group and `com.example.mcp` for Kotlin package names; do not use personal or misleading domains (e.g. `dev.nisenabe`, `org.gradle.mcp`).
- Do not commit local machine paths or personal identifiers into published files; use generic placeholders in README and examples.
- If personal info was pushed to GitHub, rewrite git history and force-push to remove it completely rather than only fixing HEAD.
- When refactoring Gradle build scripts, align with the gradle-build-script skill (Version Catalog, centralised repositories in settings, JVM Test Suites).
- Use `project-context-ingestion` for declared build constraints; reserve Gradle MCP for resolved runtime Gradle/Java, build verification, and task execution.
- When asked to commit (and push), split changes into semantic commits by meaningful concern (build / feat / test / docs); use the `semantic-commits` skill when appropriate, or `rework-commits` when rewriting existing branch history (publish with `git push --force-with-lease`).

## Learned Workspace Facts

- Standalone MCP server exposing Gradle Tooling API over stdio to MCP clients.
- GitHub repository: `nise-nabe/gradle-tapi-mcp-server` (public, default branch `main`).
- Stack: Kotlin, Java 17 toolchain, MCP SDK 0.10.0 (BOM), Gradle Tooling API 8.14, `jackson-module-kotlin` 2.17.0 via `jacksonObjectMapper()`.
- Build uses `gradle/libs.versions.toml`, `dependencyResolutionManagement` with `FAIL_ON_PROJECT_REPOS`, JVM Test Suites (JUnit 5), and Configuration Cache enabled.
- Single-module project; `build-logic` is intentionally deferred until multi-module need arises.
- `.gitignore` excludes `.cursor/`; `gradle-wrapper.jar` is explicitly un-ignored so clones can run `./gradlew`.
- Agent skill at `skills/gradle-tapi-mcp/` documents token-efficient MCP workflows; install to `~/.cursor/skills/` for global use.
- MCP defaults are token-efficient: prefer `gradle_get_project_overview`; use `gradle_get_build_cache_status` for cache settings; task lists omitted unless `includeTasks=true`; run output truncated at 8000 chars.
- Long Gradle builds: set `background=true` on `gradle_run_tasks`/`gradle_run_tests`, then poll `gradle_get_build_status` for progress and partial output.
- Cursor MCP config launches the JAR via `java -jar` with `GRADLE_PROJECT_DIR=${workspaceFolder}` (workspace `.cursor/mcp.json` or global `~/.cursor/mcp.json`).
- After MCP server code changes, rebuild the JAR (`./gradlew jar`) and restart MCP servers in Cursor.
- `main` is branch-protected; push feature branches and open PRs instead of pushing directly to `main`.

## Cursor Cloud specific instructions

Single-module Kotlin/JVM MCP server (stdio). No web UI, Docker, or dedicated lint task.

### Prerequisites

- **JDK 17** is required for `./gradlew` (Java toolchain in `build.gradle.kts`). JDK 21 alone is insufficient unless JDK 17 is also installed (e.g. `openjdk-17-jdk` on Debian/Ubuntu). JDK 21+ can run the built JAR at runtime.
- Gradle wrapper (`./gradlew`, Gradle 9.5.1) downloads dependencies and the Gradle distribution on first use (network required initially).

### Build, test, run

| Goal | Command |
|------|---------|
| Build JAR | `./gradlew jar` → `build/libs/gradle-tapi-mcp-server-0.1.0-SNAPSHOT.jar` |
| Unit tests | `./gradlew test` (JUnit 5; mocked Tooling API, no Gradle daemon) |
| Full verify | `./gradlew build` (compile + test + assemble) |
| Lint | Not configured; use `./gradlew build` as compile/test gate |
| Run MCP server | `GRADLE_PROJECT_DIR=/workspace java -jar build/libs/gradle-tapi-mcp-server-0.1.0-SNAPSHOT.jar` |

Logging goes to **stderr** only; **stdout** is reserved for MCP JSON-RPC (newline-delimited JSON).

### E2E smoke test (MCP + Gradle Tooling API)

After `./gradlew jar`, drive the server over stdio: send `initialize` → `notifications/initialized` → `tools/list` → `tools/call` for `gradle_connection_status` and `gradle_get_project_overview` with `GRADLE_PROJECT_DIR` set to a Gradle project (this repo works). Expect `connected: true`, Gradle 9.5.1, and project name `gradle-tapi-mcp-server`.

There are no automated MCP integration tests in the repo; unit tests under `src/test/` do not start the MCP server or Gradle daemon.

### Services

Only the **MCP server process** and an implicit **Gradle daemon** (via Tooling API when connected) are involved. No database or HTTP server.
