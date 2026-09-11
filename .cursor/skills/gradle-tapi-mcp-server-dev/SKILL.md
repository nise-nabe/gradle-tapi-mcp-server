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
| `:dependency-sources-mcp` (`…dependency.mcp`) | `gradle_index_dependency_sources` / `gradle_get_dependency_sources_index_status` / `gradle_search_dependency_sources` / `gradle_search_dependency_sources_multi` / `gradle_read_dependency_source` catalog + facade |
| `:resolution-model` (`…resolution`) | Serializable `McpDependencyResolution` tooling model + `ToolingModelBuilder` (init-script jar) |
| `model/resolution/` | `gradle_get_dependency_resolution` MCP tool (BuildAction + init script; no task execution) |

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

When bumping the server version, put these in the **version bump PR** (same commit as `build.gradle.kts`):

- `build.gradle.kts` `version`
- `README.md` JAR path examples
- Marketplace catalogs: `.cursor-plugin/marketplace.json` and `.github/plugin/marketplace.json` (`metadata.version` + plugin `version`); `.agents/plugins/marketplace.json` (plugin `version` only)
- Plugin manifests: `plugins/gradle-tapi-mcp/plugin.json`, `plugins/gradle-tapi-mcp/.cursor-plugin/plugin.json`, `plugins/gradle-tapi-mcp/.codex-plugin/plugin.json`
- `AGENTS.md` JAR path examples (`build/libs/gradle-tapi-mcp-server-X.Y.Z.jar`)

Do **not** bump `.cursor/install.sh` `GRADLE_TAPI_MCP_VERSION` or `plugins/gradle-tapi-mcp/server-release.json` in that PR. After the GitHub Release exists, a SHA PR updates those two files (`version` + SHA-256 together), plus `AGENTS.md` `currently **X.Y.Z**` and `.cursor/skills/gradle-tapi-mcp/SKILL.md` `release vX.Y.Z`, so Cloud / plugin download URLs do not 404.

Follow `.cursor/skills/release/SKILL.md` for the full release workflow.
