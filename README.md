# Gradle Tooling API MCP Server

MCP server that exposes [Gradle Tooling API](https://docs.gradle.org/current/userguide/third_party_integration.html) operations to Cursor and other MCP clients.

## Build

```bash
./gradlew jar
```

The fat JAR is written to `build/libs/gradle-tapi-mcp-server-0.1.0-SNAPSHOT.jar`.

## Cursor configuration

Add to `.cursor/mcp.json` in your Gradle project:

```json
{
  "mcpServers": {
    "gradle": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/gradle-tapi-mcp-server/build/libs/gradle-tapi-mcp-server-0.1.0-SNAPSHOT.jar"
      ],
      "env": {
        "GRADLE_PROJECT_DIR": "${workspaceFolder}"
      }
    }
  }
}
```

## Environment variables

| Variable | Description |
|----------|-------------|
| `GRADLE_PROJECT_DIR` | Auto-connect on startup |
| `GRADLE_USER_HOME` | Optional Gradle user home |
| `GRADLE_VERSION` | Optional explicit Gradle version |
| `GRADLE_INSTALLATION` | Optional local Gradle installation |

## Tools

| Tool | Description |
|------|-------------|
| `gradle_connect` | Connect to a project directory |
| `gradle_connection_status` | Current connection state |
| `gradle_disconnect` | Close the connection |
| `gradle_get_build_environment` | Gradle/Java environment |
| `gradle_get_project_model` | Project hierarchy and tasks |
| `gradle_get_build_invocations` | Runnable tasks |
| `gradle_get_project_publications` | Publications |
| `gradle_run_tasks` | Execute tasks |
| `gradle_run_tests` | Execute JVM test classes |

## Notes

- Uses the Gradle daemon. Compatible idle daemons are reused automatically.
- STDIO transport: logging goes to stderr only so stdout stays clean for MCP.
