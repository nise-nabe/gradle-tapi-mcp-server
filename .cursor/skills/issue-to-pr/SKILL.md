---
name: issue-to-pr
description: >-
  Token-efficient workflow to pick one GitHub issue, implement a minimal fix,
  verify with targeted Gradle tests, and open a PR. Use for issue-driven tasks.
---

# Issue → PR (token-efficient)

Read `workflow-router` only if you have not already routed here.

## 1 — Pick one issue (one command)

**Oldest open** (`gh issue list --sort` is not available):

```bash
gh issue list --state open --limit 100 --json number,title,createdAt \
  | jq 'sort_by(.createdAt) | .[0]'
```

**Easy / small** — prefer labels or a tight limit; do not list and compare 30+ issues:

```bash
gh issue list --state open --label "good first issue" --limit 5 --json number,title
# or: --search "is:open no:assignee" --limit 5
```

Then: `gh issue view N` once.

## 2 — Branch

```bash
git checkout main && git pull origin main
git checkout -b cursor/issue-<N>-<short-slug>-3cc6
```

Use plan mode only for non-trivial refactors; hygiene/docs fixes go straight to code.

## 3 — Implement

- Minimal diff scoped to the issue.
- `Grep` + `Read` with `offset`/`limit` — not full-file reads unless refactoring.
- New tests only when behavior changes require them.
- Follow `gradle-tapi-mcp-server-dev` for package placement.

## 4 — Verify (before commit)

| Change type | Verify |
|-------------|--------|
| Server Kotlin / tests | `./gradlew test` or `./gradlew build` (targeted `--tests` when possible) |
| Docs / `.cursor/` only | MCP `gradle_run_tasks` `["build"]` if connected, else `./gradlew build` |

Do not run full `build` for one-class fixes — use `--tests "FullyQualifiedClassName"`.

## 5 — Commit + PR

```bash
git commit -m "<type>: <summary>

Fixes #N"
git push -u origin cursor/issue-<N>-...
```

- **ManagePullRequest** `create_pr` — **open** (not draft) when verification passed.
- Body: Summary / Changes / Test plan (`pr-description-format.mdc`).
- Link issue in PR body (`Fixes #N`).

## Token budget

- [ ] ≤2 `gh issue` calls (list pick + view)
- [ ] ≤1 explore pass (Grep, not subagent) for simple issues
- [ ] Gradle: one targeted test run or one `build`
- [ ] Did not read `pr-review-response` or full `gradle-tapi-mcp`
