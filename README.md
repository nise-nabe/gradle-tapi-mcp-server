# Gradle Tooling API MCP Server

MCP server that exposes [Gradle Tooling API](https://docs.gradle.org/current/userguide/third_party_integration.html) operations to Cursor and other MCP clients.

## Build

```bash
./gradlew jar
```

The fat JAR is written to `build/libs/gradle-tapi-mcp-server-0.13.5.jar`.

## Plugin marketplace

This repository is a plugin marketplace for Cursor, Codex, and GitHub Copilot. It publishes the `gradle-tapi-mcp` plugin (Gradle Tooling API MCP server plus the token-efficient agent skill).

Requires Java 17+ and `bash`. The plugin launcher downloads the release JAR to `~/.local/share/gradle-tapi-mcp-server/` (same location as `.cursor/install.sh`).

### Cursor

Add this Git repository as a plugin marketplace from Customize → Plugins (or a team marketplace). The catalog is `.cursor-plugin/marketplace.json`.

### Codex

```bash
codex plugin marketplace add nise-nabe/gradle-tapi-mcp-server
```

The catalog is `.agents/plugins/marketplace.json`.

### GitHub Copilot CLI

```bash
copilot plugin marketplace add nise-nabe/gradle-tapi-mcp-server
```

The catalog is `.github/plugin/marketplace.json`.

Then install the `gradle-tapi-mcp` plugin from that marketplace.

## Cursor configuration

Add to `.cursor/mcp.json` in your Gradle project:

```json
{
  "mcpServers": {
    "gradle": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/gradle-tapi-mcp-server/build/libs/gradle-tapi-mcp-server-0.13.5.jar"
      ],
      "env": {
        "GRADLE_PROJECT_DIR": "${workspaceFolder}"
      }
    }
  }
}
```

## Documentation

| Document | Contents |
|----------|----------|
| [docs/configuration.md](docs/configuration.md) | Transports (stdio / Streamable HTTP), environment variables |
| [docs/tools.md](docs/tools.md) | MCP tool catalog and resources |
| [docs/dependency-sources.md](docs/dependency-sources.md) | Dependency sources name locate (index → search → read) |
| [docs/usage.md](docs/usage.md) | Token-efficient usage, multiple projects, long-running builds, `gradle_run_tests` selectors, agent workflows, disconnect behavior |

## Modules

| Project | Role |
|---------|------|
| root (`gradle-tapi-mcp-server`) | MCP server fat JAR; registers tools and MCP resources |
| `:dependency-sources-core` | Identifier lexer, δ postings, keep-set resolver, on-disk index, source snippet reader |
| `:dependency-sources-mcp` | Index/search/read tool schemas and facade (depends on core) |
| `:resolution-model` | Thin ToolingModelBuilder jar for `ResolutionResult` graphs (embedded under `META-INF/mcp/`) |

## Agent skill

Copy or symlink `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/` into your Cursor skills directory (for example `~/.cursor/skills/gradle-tapi-mcp/`) so agents use token-efficient MCP workflows with `project-context-ingestion`. Prefer the plugin marketplace above when the host supports it.

## Notes

- Uses the Gradle daemon. Compatible idle daemons are reused automatically.
- STDIO transport: logging goes to stderr only so stdout stays clean for MCP.
