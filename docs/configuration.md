# Configuration

## Transports

The server speaks **stdio** by default (subprocess per MCP client). To serve MCP over
[Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#streamable-http)
instead:

```bash
java -jar gradle-tapi-mcp-server-0.13.4.jar \
  --transport=streamable-http --host=127.0.0.1 --port=8080 --path=/mcp
```

Point the client at the endpoint URL instead of launching a subprocess:

```json
{
  "mcpServers": {
    "gradle": {
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

Options (`--help` for the full list): `--transport=stdio|streamable-http` (`http` is an alias),
`--host`, `--port`, `--path`, `--allowed-hosts`, `--allowed-origins`. DNS rebinding protection is
on by default and only allows localhost `Host`/`Origin` headers — pass `--allowed-hosts` /
`--allowed-origins` when exposing the endpoint beyond localhost. `GRADLE_PROJECT_DIR` must be set
in the server process environment; `env` blocks in client config only apply to `command` servers.

## Environment variables

| Variable | Description |
|----------|-------------|
| `GRADLE_PROJECT_DIR` | Default project; auto-connect on startup |
| `GRADLE_USER_HOME` | Optional Gradle user home |
| `GRADLE_VERSION` | Optional explicit Gradle version |
| `GRADLE_INSTALLATION` | Optional local Gradle installation |
