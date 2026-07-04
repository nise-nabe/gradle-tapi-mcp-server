# Kotlin MCP SDK spike (historical)

**Note:** The production server now uses Kotlin MCP SDK directly. This spike was used to validate stdio wiring before the full migration.

Standalone spike used during the Kotlin MCP SDK investigation.

## Build

```bash
cd spike/kotlin-mcp-sdk
../../gradlew jar
```

Fat JAR: `build/libs/kotlin-mcp-sdk-spike-0.1.0-spike.jar`

## Smoke test (stdio)

From the repository root after building the spike JAR:

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"spike","version":"1.0.0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"gradle_connection_status","arguments":{}}}' \
  | GRADLE_PROJECT_DIR=/workspace java -jar spike/kotlin-mcp-sdk/build/libs/kotlin-mcp-sdk-spike-0.1.0-spike.jar 2>/dev/null
```

Expect `gradle_connection_status` in `tools/list` and a JSON text payload with `"spike":true` from `tools/call`.

## Scope

- `gradle_connection_status` — static JSON (no Gradle Tooling API)
- `spike_progress_demo` — progress + logging when `_meta.progressToken` is set
- stdio transport only (no Ktor server engine)
