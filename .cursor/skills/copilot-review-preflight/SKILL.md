---
name: copilot-review-preflight
description: >-
  Pre-implementation and pre-PR checklist derived from recurring GitHub Copilot review
  comments on this repository. Use before opening a PR or when implementing features
  likely to trigger Copilot review (MCP tools, build execution, agent docs).
---

# Copilot review preflight

Use as a final pass before requesting review on `nise-nabe/gradle-tapi-mcp-server`.

## When to use

- Before opening or updating a PR (especially feature/fix PRs with server code)
- When Copilot review has flagged similar issues on prior PRs in the same area
- At the start of work in a new area — pick the specialized skill below first
- **After review comments arrive** — switch to `pr-review-response` for triage

## Specialized skills (read the relevant one during implementation)

| Area | Skill |
|------|-------|
| **Route task first** | `workflow-router` |
| **`/thermos` branch audit** | `thermo-nuclear-review` |
| **Addressing PR review comments** | `pr-review-response` |
| **Issue → PR** | `issue-to-pr` |
| MCP server implementation | `gradle-tapi-mcp-server-dev` |
| Gradle MCP consumer workflows | `gradle-tapi-mcp` |
| Release / version bump | `release` |
| PR body format | `.cursor/rules/pr-description-format.mdc` |

## Quick checklist by change type

### MCP server implementation (`src/main/**`, `src/test/**`)

- [ ] Entry point classes stay in root `com.example.gradle.mcp`; handlers in feature subpackages
- [ ] Agent-facing errors use `McpException` + `McpErrorCode`, not ad-hoc `IllegalStateException` messages
- [ ] Tool descriptions document token-efficient defaults (`includeOutput=false`, omitted task lists)
- [ ] Structured failure fields (`testFailures`, `buildSummary`, `problems`) before full stdout
- [ ] `BUILD_ALREADY_RUNNING` includes `activeBuildId` and related active-build fields when known
- [ ] Tests mock Tooling API — no accidental Gradle daemon startup in unit tests
- [ ] Kotest for collection/nullable assertions; no `!!` with `assertTrue`/`assertFalse`
- [ ] Package names use `com.example` — no personal domains in published code

### Build / version catalog (`build.gradle.kts`, `gradle/**`, `settings.gradle.kts`)

- [ ] Version catalog in `gradle/libs.versions.toml`; no duplicate version literals
- [ ] `dependencyResolutionManagement` with `FAIL_ON_PROJECT_REPOS` preserved
- [ ] JVM Test Suites for tests; Configuration Cache compatibility maintained

### Agent docs and release sync (`.cursor/`, `skills/`, `AGENTS.md`, `README.md`)

- [ ] `install.sh` version and SHA-256 match the release being documented
- [ ] `.cursor/skills/gradle-tapi-mcp/SKILL.md` and `skills/gradle-tapi-mcp/SKILL.md` stay in sync when editing MCP workflow docs
- [ ] MCP config uses stdio transport; `GRADLE_PROJECT_DIR` documented correctly
- [ ] Invoke shell scripts via `bash path/to/script.sh` in docs when executable bit may vary
- [ ] PR description is a feature summary (not a "review fixes" changelog) per `pr-description-format.mdc`
- [ ] Server code changes: document shell `./gradlew` verify path in skills/rules (bootstrap uses release JAR)

## Top recurring themes

| Theme | Primary skill |
|-------|---------------|
| Wrong package / entry point placement | `gradle-tapi-mcp-server-dev` |
| Unstructured errors / missing error codes | `gradle-tapi-mcp-server-dev` |
| Verbose tool output defaults | `gradle-tapi-mcp-server-dev` |
| install.sh / skill version drift | `release` |
| Duplicate skill content (`.cursor/` vs `skills/`) | checklist above |

## Verification before PR

1. Read the specialized skill for your change area.
2. Server code: `./gradlew test` or `./gradlew build` (shell — not MCP for self-build).
3. Docs-only: MCP `gradle_run_tasks` `["build"]` or `./gradlew build`.
4. Scan the diff for hard-coded versions, `IllegalStateException` in tool paths, and missing structured error fields.

## Test plan template

```markdown
## Test plan
- [ ] `./gradlew test --tests "com.example.gradle.mcp.<area>.<Class>Test"` — <area changed>
- [ ] `./gradlew build` — full verify (when build scripts or cross-cutting protocol changed)
```

Replace with the narrowest command that covers the change.
