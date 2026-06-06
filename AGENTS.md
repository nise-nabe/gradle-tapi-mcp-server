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
- MCP defaults are token-efficient: prefer `gradle_get_project_overview`; task lists omitted unless `includeTasks=true`; run output truncated at 8000 chars.
- Long Gradle builds: set `background=true` on `gradle_run_tasks`/`gradle_run_tests`, then poll `gradle_get_build_status` for progress and partial output.
- Cursor MCP config launches the JAR via `java -jar` with `GRADLE_PROJECT_DIR=${workspaceFolder}` (workspace `.cursor/mcp.json` or global `~/.cursor/mcp.json`).
- After MCP server code changes, rebuild the JAR (`./gradlew jar`) and restart MCP servers in Cursor.
- `main` is branch-protected; push feature branches and open PRs instead of pushing directly to `main`.
