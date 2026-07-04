---
name: gradle-tapi-mcp
description: >-
  Use the gradle MCP server for token-efficient build verification in this repo.
  Prefer lightweight Tooling API queries before running tasks.
---

# Gradle Tooling API MCP

This repository **is** the MCP server. `.cursor/install.sh` builds the fat JAR with
`./gradlew jar`, symlinks it to `~/.local/share/gradle-tapi-mcp-server/gradle-tapi-mcp-server.jar`,
and `.cursor/mcp.json` launches it with `GRADLE_PROJECT_DIR=${workspaceFolder}`.

After changing server code, rebuild (`./gradlew jar`) and restart MCP servers in Cursor.

## Workflow (token-efficient)

1. `gradle_connection_status` — confirm connected
2. `gradle_get_build_environment` — resolved Gradle/Java versions
3. `gradle_get_project_overview` — project name and task counts (single-module repo)
4. `gradle_run_tasks` with `["build"]` or `gradle_run_tests` when verification is needed

Avoid `includeTasks=true` and heavy model queries unless necessary. `gradle_run_tasks` omits stdout/stderr by default (`includeOutput=false`).

## Full tool reference

See `skills/gradle-tapi-mcp/SKILL.md` and `skills/gradle-tapi-mcp/reference.md` in this repository for the complete tool catalog and advanced workflows (background builds, multiple projects, output limits).
