---
name: gradle-tapi-mcp-server-dev
description: >-
  MCP server implementation conventions for this repository. Use when editing Kotlin
  under src/main or src/test, adding MCP tools, or changing build/connection/protocol logic.
---

# Gradle TAPI MCP server conventions

Apply during implementation — not only before opening a PR.

## When to use

- Adding or changing MCP tool handlers, schemas, or descriptions
- Touching build execution, connection pool, or Tooling API integration
- Writing or updating unit tests (mocked Tooling API)

## Package placement

| Package | Owns |
|---------|------|
| `com.example.gradle.mcp` (root) | `GradleTapiMcpServer`, `GradleTapiMcpServerLauncher`, `GradleMcpRuntime` only (call `registerDependencySourceTools` from `dependency/`) |
| `build/` | `BuildExecutionManager`, run/cancel/status tools, output parsing, persistence, test runners |
| `connection/` | Connection pool, `gradle_connect` / disconnect, build environment snapshots, `gradle_get_java_runtimes` |
| `protocol/` | `McpToolSchemas`, `McpToolDescriptions`, `McpErrors`, JSON mapping, progress notifications |
| `model/` | `gradle_get_project_overview`, `gradle_get_project_publications`, `gradle_get_build_invocations` |
| `cache/` | Build cache status and local cache inspection tools |
| `server/` | Stdio transport helpers (e.g. `EofSignalingInputStream`) |
| `:dependency-sources-core` (`…dependency`) | Lexer, δ codec, keep-set, name-locate index I/O (no MCP SDK) |
| `:dependency-sources-mcp` (`…dependency.mcp`) | `gradle_index_dependency_sources` / `gradle_search_dependency_sources` / `gradle_search_dependency_sources_multi` catalog + facade |

Do not add tool handler classes to the root package (thin register wrappers that call a subproject facade are OK). Do not put connection logic in `protocol/`.

## MCP tool patterns

- Errors: throw `McpException` with `McpErrorCode` — not raw `IllegalStateException` for agent-facing failures.
- Descriptions in `McpToolDescriptions.kt` should state token-efficient defaults (what is omitted by default).
- Tool results: prefer structured fields (`testFailures`, `buildSummary`, `problems`) over full stdout.
- Register new tools in the feature package's `*Tools.kt` and wire from `GradleTapiMcpServer`.

## Tests

- Unit tests mock the Tooling API — no Gradle daemon in `src/test/`.
- Prefer Kotest for list and nullable collection assertions; avoid `!!` with `assertTrue`/`assertFalse`.
- Mirror production package layout under `src/test/kotlin/com/example/gradle/mcp/`.
- Name test classes after the unit under test (`BuildExecutionManagerRunTest` for `BuildExecutionManager`).

## Verification (this repo)

When you change server code, verify with **shell**:

```bash
./gradlew test --tests "com.example.gradle.mcp.<area>.<Class>Test"
# or
./gradlew build
```

Do not use the `gradle` MCP server to compile the server you are editing (see `gradle-mcp.mdc`).

## Version / install sync on release

When bumping the server version, update together:

- `build.gradle.kts` `version`
- `.cursor/install.sh` `GRADLE_TAPI_MCP_VERSION` and SHA-256
- `.cursor/skills/gradle-tapi-mcp/SKILL.md` and `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/SKILL.md` version mentions
- `AGENTS.md` bootstrap section

Follow `.cursor/skills/release/SKILL.md` for the full release workflow.
