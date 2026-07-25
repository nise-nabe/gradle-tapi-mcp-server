---
name: pr-review-response
description: >-
  Efficient workflow for triaging, fixing, and resolving GitHub PR review comments
  (Copilot, Thermos, human reviewers). Optimized for token use and agent round-trips.
  Use when asked to address PR review comments, resolve review threads, or fix
  findings from a specific pull request number.
---

# PR review response (token-efficient)

Workflow for **triage → batch fix → verify once → resolve threads**.

**Not for `/thermos` branch audits** — use `thermo-nuclear-review` instead (`workflow-router`).

Read **`cloud-github`** for PR tools and **`copilot-review-preflight`** for recurring
fix patterns. Read **`gradle-tapi-mcp`** or **`gradle-tapi-mcp-server-dev`** only for
the verification subsection below — do not read the full skills unless verification fails.

## When to use

- User asks to address / fix / resolve comments on PR *N*
- After Copilot or Thermos review on an open PR
- Before marking review threads resolved

**Not for `/thermos PR N` branch audits** — use `thermo-nuclear-review` (`workflow-router`).

## Phase 1 — Fetch comments (minimal payload)

Read **`cloud-github`** for built-in PR tools. In Cursor Cloud, prefer those tools for
create/update, replies, resolve, and CI status. **`gh` is still required** for review-thread
metadata (GraphQL) and, when the branch is unknown, PR branch metadata via `gh pr view`.

Before any `gh` call:

1. Resolve: `command -v gh` or `/exec-daemon/gh`
2. Verify: `gh auth status`
3. If either step fails, **stop review-thread triage** — GraphQL thread fetch is unavailable. **ManagePullRequest** can still create/update PRs, post top-level comments, resolve threads when the user supplies `comment_id`, and fetch CI status — but it cannot replace GraphQL for listing unresolved threads.

**Do not** call `gh api repos/.../pulls/{n}/comments` — the REST endpoint always
includes `diff_hunk` per comment (~50 KB for a typical Copilot review).

### Preferred: GraphQL (resolved state + metadata only)

```bash
gh api graphql -f query='
{
  repository(owner: "nise-nabe", name: "gradle-tapi-mcp-server") {
    pullRequest(number: N) {
      title
      headRefName
      baseRefName
      reviewThreads(first: 100) {
        pageInfo { hasNextPage endCursor }
        nodes {
          id
          isResolved
          comments(first: 5) {
            pageInfo { hasNextPage endCursor }
            nodes {
              databaseId
              body
              path
              line
              originalLine
              startLine
              originalStartLine
              author { login }
            }
          }
        }
      }
    }
  }
}'
```

Extract:

| Field | Use |
|-------|-----|
| `databaseId` | `ManagePullRequest` `resolve_comment` `comment_id` and `post_comment` `in_reply_to` — numeric GitHub review-comment id, **not** the thread node `id` |
| `id` (thread node) | Ignore for ManagePullRequest — do not pass to `resolve_comment` |
| `isResolved` | Skip already resolved |
| `body` + `path` + line fields | Triage and locate code |
| `headRefName` | Checkout branch |

### Branch metadata without GraphQL

When the agent already has `branch_name` or `headRefName` from the task, skip `gh pr view`.
Otherwise:

```bash
gh pr view N --json headRefName,baseRefName,title
```

### Checkout once

```bash
git fetch origin <headRefName>
git checkout -B <headRefName> origin/<headRefName>
```

Do not explore the repo on `main` if the PR branch exists.

## Phase 2 — Triage (before reading code)

For each **unresolved** thread, record:

| Thread | Valid? | Priority | Action |
|--------|--------|----------|--------|
| … | yes/no | P0–P3 | fix / skip + reply |

**Priority guide**

| Priority | Examples |
|----------|----------|
| **P0** | Crashes, data loss, broken MCP wire protocol |
| **P1** | Real bugs, wrong build status, security |
| **P2** | Performance, test stability, misleading tool output |
| **P3** | Style, dead code, doc nits |

Skip invalid comments with a brief PR reply via **ManagePullRequest** `post_comment`
(`in_reply_to` the thread's `databaseId`); do not implement speculative fixes.

**Batch by file** — fix all comments on the same file in one edit pass.

## Phase 3 — Read code (targeted)

| Do | Don't |
|----|-------|
| `Grep` for symbol / line from comment | Read entire large files |
| `Read` with `offset`/`limit` around `line` | Re-read files already in context |
| Read **`gradle-tapi-mcp-server-dev`** for the area | Read `gradle-tapi-mcp` in full |
| Follow `copilot-review-preflight` checklist | Re-fetch PR comments |

## Phase 4 — Implement (single pass)

1. Apply all accepted fixes per file before any Gradle run.
2. Keep diffs minimal — one concern per hunk where possible.
3. Add/adjust tests only when the comment is about missing coverage or you fixed a bug.
4. Commit once before verification:

```bash
git add <paths>
git commit -m "fix: address PR <N> review comments"
```

## Phase 5 — Verify (one test run)

Server code changes: **shell `./gradlew`** (see `gradle-mcp.mdc`).

1. **One** targeted test run for affected classes:

```bash
./gradlew test --tests "com.example.gradle.mcp.<area>.<Class>Test"
```

2. If multiple classes changed, batch in one `--tests` invocation or run `./gradlew test` once.
3. On failure, rerun **only** the failing method(s) — not the full suite.
4. Docs-only fixes: MCP `gradle_run_tasks` `["build"]` if connected, else `./gradlew build`.

Do **not** run full `build` for review-fix verification unless the PR touched build logic or CI parity is required.

## Phase 6 — Push and resolve

```bash
git push -u origin <headRefName>
```

Resolve threads with **ManagePullRequest** `resolve_comment` — pass each `databaseId` from GraphQL.

Update PR body via `update_pr` only when behavior changed materially — fold fixes into **Changes** (see `pr-description-format.mdc`).

## Phase 7 — User summary

Report in the user's language:

1. Per-comment validity and priority (table)
2. What was fixed vs skipped
3. Test command and result
4. Link to PR

## Token budget checklist

- [ ] Built-in tools used for create/update/post/resolve/CI
- [ ] GraphQL used instead of REST review comments API
- [ ] ≤ 1 branch checkout
- [ ] Files read with offset/limit or Grep
- [ ] Gradle: ≤ 1 test round (+ optional single-method retry)
- [ ] `resolve_comment` batched in one agent turn
- [ ] Did not re-read `gradle-tapi-mcp` or `AGENTS.md` in full

## Related skills

| Need | Skill |
|------|-------|
| `/thermos` branch audit | `thermo-nuclear-review` |
| Route task | `workflow-router` |
| Issue → PR | `issue-to-pr` |
| PR body format | `pr-description-format.mdc` |
| Recurring patterns | `copilot-review-preflight` |
| Server conventions | `gradle-tapi-mcp-server-dev` |
