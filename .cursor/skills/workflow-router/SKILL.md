---
name: workflow-router
description: >-
  Route Cloud Agent tasks to the correct on-demand skill. Read this first when the
  user prompt matches /thermos, PR review comments, issue fixes, or docs-only edits.
  Prevents loading the wrong skill and wasting tokens.
---

# Workflow router (read first)

Pick **one** path below. Do **not** read unrelated skills in full.

| User intent | Read only | Do not read |
|-------------|-----------|-------------|
| `/thermos PR N`, Thermo branch audit | `thermo-nuclear-review` | `pr-review-response` |
| Address PR review comments / resolve threads | `pr-review-response` | `thermo-nuclear-review` |
| Fix an issue → open PR | `issue-to-pr` | `pr-review-response` |
| Release (version bump, tag, GitHub Release) | `release` | `gradle-tapi-mcp` (full) |
| Docs / `.cursor/` / `AGENTS.md` only | `gradle-mcp.mdc` (verify subsection) + `cloud-github` + target files via Grep | `gradle-tapi-mcp` (full) |
| MCP server code (implementation) | Area skill from table below | Full `AGENTS.md` |

## Area skills (implementation only)

| Area | Skill |
|------|-------|
| Package placement, MCP tools, tests, errors | `gradle-tapi-mcp-server-dev` |
| Gradle verify (after you know the task) | `gradle-tapi-mcp` — verification table only, or shell `./gradlew` for server code |
| Pre-PR Copilot patterns | `copilot-review-preflight` — checklist for changed area only |
| GitHub PR / issues | `cloud-github` |

## Session reuse

| Task | Resume IDLE session? |
|------|---------------------|
| `/thermos PR N` (audit) | **No** — run full one-shot pipeline at current `HEAD` (Phases 0–6 in `thermo-nuclear-review`) |
| PR comment triage | Optional — resume if same branch and threads already fetched |
| Issue → PR continuation | Yes — push, PR polish, issue comment |
| Push / link issues only | Yes — no re-audit |

**Do not** start a second Cloud session for a "fresh" Thermo look — the skill's closure pass
(Phase 5) is the built-in independent re-check.

## Fetch once

| Need | One call |
|------|----------|
| PR branch + files | `gh pr view N --json headRefName,baseRefName,title,files` or **ManagePullRequest** |
| Diff after checkout | `git diff origin/<base>...HEAD` — not `gh pr diff` |
| Review threads | GraphQL once (`pr-review-response`); if empty, do not re-fetch |
