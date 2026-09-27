# Configuration

## Transports

The server speaks **stdio** by default (subprocess per MCP client). To serve MCP over
[Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#streamable-http)
instead:

```bash
java -jar gradle-tapi-mcp-server-0.14.0.jar \
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

### Multiple projects and sessions (streamable HTTP)

One HTTP endpoint can serve many MCP sessions for different Gradle projects. Each session keeps
its own **project context**:

- The session default project is `GRADLE_PROJECT_DIR` when set, otherwise the project of the most
  recent `gradle_connect` call. Tool calls without `projectDirectory` resolve to the session
  default — never to another session's project.
- Clients that can send custom HTTP headers may set `X-Gradle-Project-Dir: <path>` on the
  `initialize` request to bind a project before the first `gradle_connect`.
- A session only sees the projects it knows (its workspace plus projects it connected):
  `gradle_connection_status` lists only them, and `gradle_list_builds`, `gradle_get_build_status`,
  and `gradle_cancel_build` cannot reach other sessions' projects.
- `gradle_disconnect` without arguments releases the session default project only; pass
  `all: true` to close every connection on the server. A pooled connection is closed once its
  last session releases it — disconnecting a project shared with another session leaves it
  running (`retainedByOtherSessions: true`).
- When a session connects to a project already pooled with different `gradleUserHome` /
  `gradleVersion` / `gradleInstallation`, the existing connection is reused and the response
  reports `reusedExistingConnection: true` with a warning.

Notifications (build progress, logging) are delivered on the standalone `GET` SSE stream that
Streamable HTTP clients open; responses to tool calls are plain JSON. Clients that never open
the GET stream will not receive progress or log notifications — the tool responses themselves
are unaffected.

## Environment variables

| Variable | Description |
|----------|-------------|
| `GRADLE_PROJECT_DIR` | Default project; auto-connect on startup |
| `GRADLE_USER_HOME` | Optional Gradle user home |
| `GRADLE_VERSION` | Optional explicit Gradle version |
| `GRADLE_INSTALLATION` | Optional local Gradle installation |
