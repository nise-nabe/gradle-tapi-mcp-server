## Learned User Preferences

- Prefer Japanese for technical explanations and discussion.
- Use `com.example` for Maven group and `com.example.mcp` for Kotlin package names; do not use personal or misleading domains (e.g. `dev.nisenabe`, `org.gradle.mcp`).
- Do not commit local machine paths or personal identifiers into published files; use generic placeholders in README and examples.
- If personal info was pushed to GitHub, rewrite git history and force-push to remove it completely rather than only fixing HEAD.
- When refactoring Gradle build scripts, align with the gradle-build-script skill (Version Catalog, centralised repositories in settings, JVM Test Suites).

## Learned Workspace Facts

- Standalone MCP server exposing Gradle Tooling API over stdio to MCP clients.
- GitHub repository: `nise-nabe/gradle-tapi-mcp-server` (public, default branch `main`).
- Stack: Kotlin, Java 17 toolchain, MCP SDK 0.10.0 (BOM), Gradle Tooling API 8.14, `jackson-module-kotlin` 2.17.0 via `jacksonObjectMapper()`.
- Build uses `gradle/libs.versions.toml`, `dependencyResolutionManagement` with `FAIL_ON_PROJECT_REPOS`, JVM Test Suites (JUnit 5), and Configuration Cache enabled.
- Single-module project; `build-logic` is intentionally deferred until multi-module need arises.
- `.gitignore` excludes `.cursor/`; `gradle-wrapper.jar` is explicitly un-ignored so clones can run `./gradlew`.
