# Small-context and local LLM clients

This server exposes 24 tools. The full `tools/list` payload is roughly 26K
characters (~6–7K tokens with annotations), which fits most modern context
windows but can crowd out a small model (e.g. 32K) once system prompts,
conversation history, and skill files are added. This page shows how to run a
reduced tool set.

## Recommended minimal tool set

For typical Gradle work (inspect → build/test → poll), these tools cover the
loop:

| Tool | Approx. size | Why |
|------|--------------|-----|
| `gradle_connect` | ~660 chars | Required unless the client auto-connects (`GRADLE_PROJECT_DIR`) |
| `gradle_connection_status` | ~540 chars | Verify connection / Gradle & Java versions |
| `gradle_get_project_overview` | ~810 chars | Project tree and task counts (cheapest model query) |
| `gradle_run_tasks` | ~1.9K chars | Run tasks; use `background: true` for long builds |
| `gradle_run_tests` | ~3K chars | Selected JVM tests; largest definition — drop if unused |
| `gradle_get_build_status` | ~1.9K chars | Poll `buildId` for background builds |
| `gradle_list_builds` | ~390 chars | Recent builds from memory/disk |
| `gradle_cancel_build` | ~440 chars | Stop a running build |
| `gradle_disconnect` | ~570 chars | Release the session's project hold |

That is roughly **11–12K characters (~3K tokens)** of tool definitions —
about half the full catalog. The five dependency-sources tools
(`gradle_index_dependency_sources`, `gradle_get_dependency_sources_index_status`,
`gradle_search_dependency_sources`, `gradle_search_dependency_sources_multi`,
`gradle_read_dependency_source`, ~5K chars combined) are the heaviest optional
group; exclude them entirely if you do not need source lookup. Model-detail
tools (`gradle_get_project_model`, `gradle_get_build_invocations`,
`gradle_get_dependency_resolution`, etc.) can also be dropped —
`gradle_get_project_overview` covers most "what does this project look like"
questions.

The server also sends short `instructions` in the `initialize` response
(typical call order), which costs only ~60 tokens.

## Pi

Pi (`.pi/mcp.json` or `~/.pi/agent/mcp.json`) supports per-server `exposure`
(`direct` / `deferred` / `codemode` / `hidden`) and per-tool `toolExposure`
overrides, so you can expose only the minimal set:

```json
{
  "mcpServers": {
    "gradle": {
      "command": "java",
      "args": ["-jar", "/path/to/gradle-tapi-mcp-server.jar"],
      "env": { "GRADLE_PROJECT_DIR": "/path/to/your-project" },
      "exposure": "direct",
      "toolExposure": {
        "gradle_get_project_model": "hidden",
        "gradle_get_build_invocations": "hidden",
        "gradle_get_dependency_resolution": "hidden",
        "gradle_get_project_publications": "hidden",
        "gradle_get_gradle_build": "hidden",
        "gradle_get_help": "hidden",
        "gradle_get_build_environment": "hidden",
        "gradle_get_java_runtimes": "hidden",
        "gradle_get_build_cache_status": "hidden",
        "gradle_get_task_execution_plan": "hidden",
        "gradle_index_dependency_sources": "hidden",
        "gradle_get_dependency_sources_index_status": "hidden",
        "gradle_search_dependency_sources": "hidden",
        "gradle_search_dependency_sources_multi": "hidden",
        "gradle_read_dependency_source": "hidden"
      }
    }
  }
}
```

`exposure: "direct"` declares tools to the model immediately; `"deferred"`
loads them via `tool_search` on demand (cheaper up front, slightly slower
first use); `"codemode"` keeps them script-only. `toolExposure` keys are the
exact tool names as reported by `tools/list` (including the `gradle_` prefix);
`*` wildcards are supported. Check `pi mcp list` and `/mcp` to verify effective
exposure.

## OpenCode

OpenCode does not offer per-tool filtering; its mitigation is `codemode`
(default `true`), which exposes MCP tools through a code-mode search interface
instead of declaring every schema to the model:

In `opencode.json` (or `.jsonc`):

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "gradle": {
        "type": "local",
        "command": ["java", "-jar", "/path/to/gradle-tapi-mcp-server.jar"],
        "environment": {
          "GRADLE_PROJECT_DIR": "/path/to/your-project"
        }
        // "codemode": true is the default; set false only if you want
        // every tool schema declared directly (not recommended for small contexts)
      }
    }
  }
}
```

With `codemode` enabled the model discovers tools via `searchTools()` /
`describeTool()` rather than receiving all 24 definitions up front, which is
the closest OpenCode gets to tool filtering today.

## Bundled agent skill files

The plugin skill files shipped with this repo are large and will not fit
alongside the tools in a ~32K context:

- `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/SKILL.md` — ~8.3K tokens
- `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/reference.md` — ~10.6K tokens
  (Qwen tokenizer estimate)

For small-context clients, prefer `docs/tools.md` and `docs/usage.md` (human
docs, not auto-loaded) over installing the full skill, or rely on the server's
own `instructions` plus tool descriptions, which are already trimmed for this
purpose.

## Notes

- Tool-level filtering inside the server (e.g. `--tools` allowlist) is
  tracked separately; until it lands, use client-side exposure controls.
- `maxFailureMessageChars` on `gradle_run_tasks` / `gradle_run_tests` /
  `gradle_get_build_status` caps long failure messages — useful for small
  contexts.
