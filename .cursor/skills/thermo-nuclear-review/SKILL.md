---
name: thermo-nuclear-review
description: >-
  One-shot Thermo-nuclear branch audit for /thermos PR N. Independent audit pass,
  fix P0–P2, file P3 issues, closure verification, SHIP — no second session needed.
  Not for GitHub inline review-thread triage.
---

# Thermo-nuclear review (`/thermos PR N`) — one-shot complete

For **GitHub review comment threads**, use `pr-review-response` instead.

**Goal:** finish in **one user invocation** with confidence comparable to a fresh second
session. Use **independent passes** inside the session — not IDLE resume across sessions.

Read `workflow-router` only if you have not already routed here.

## Phase 0 — Setup (once)

Before any `gh` call: resolve `command -v gh` or `/exec-daemon/gh`, then `gh auth status`.

```bash
gh pr view N --json headRefName,baseRefName,title,files
git fetch origin <headRefName>
git checkout -B <headRefName> origin/<headRefName>
git diff origin/<baseRefName>...HEAD > /tmp/pr-diff.txt
```

If `gh` is unavailable, use `headRefName` / `baseRefName` from the user prompt, Cloud Agent task context, or PR URL — then run the `git fetch` / `checkout` / `diff` commands above without `gh pr view`.

- **ManagePullRequest** `get_ci_status` once when merge-readiness matters (does not return branch names).
- Record `BASE_SHA=$(git merge-base origin/<baseRefName> HEAD)` optionally for audit notes.

Do not explore on `main`. Do not pass prior session conclusions into later phases.

## Phase 1 — Deterministic scan (objective, no judgment)

Map `files` from PR metadata to **one** `copilot-review-preflight` subsection. Run only
checklist items verifiable by Grep / targeted `Read` on changed paths.

| Changed area | Preflight section |
|--------------|-------------------|
| `src/main/**`, `src/test/**` | MCP server implementation |
| `build.gradle.kts`, `gradle/**`, `settings.gradle.kts` | Build / version catalog |
| `.cursor/`, `skills/`, `AGENTS.md`, `README.md` | Agent docs and release sync |

Output a **findings table** (id, source, path, priority, action). Sources: `deterministic` only.

## Phase 2 — Independent audit pass (fresh judgment)

| Tier | When | Subagent |
|------|------|----------|
| **A** | ≤2 changed files **and** ≤30 net lines | **Skip** — Phase 1 table is the audit |
| **B** | Typical code PR (not Tier A or C) | **One** audit subagent |
| **C** | >5 files **or** >200 net lines **or** `src/main/**` / `src/test/**` touched | **One** audit subagent + **one** post-fix closure subagent (Phase 5 only) |

When multiple tiers match, use the **highest** tier (C > B > A).

### Subagent input (mandatory — simulates a new session)

Pass **only**:

- `git diff origin/<base>...HEAD` (or `/tmp/pr-diff.txt`)
- PR title and changed file list
- Thermo rubric / priority definitions
- Instruction: *return a findings table; do not assume any prior SHIP or triage*

**Do not pass:** parent agent analysis, earlier findings table, chat history summary.

Parent agent: **no** long manual P0–P3 audit in parallel with the subagent.

Merge subagent rows into the findings table (`source: audit`). Deduplicate by path + concern.

## Phase 3 — Triage gate (block until clear)

| Priority | Rule |
|----------|------|
| P0–P1 | Fix on branch before SHIP |
| P2 | Fix on branch or block SHIP |
| P3 | Fix now **or** `gh issue create` — **every P3 row resolved before Phase 4** |

No SHIP with open P3 rows marked "later".

## Phase 4 — Fix + verify

1. Batch fixes per file; prefer one commit.
2. Server code: **one** `./gradlew test` (targeted `--tests` when possible) or `./gradlew build`.
3. Update findings table: `fixed` / `issue #NNN` / `wontfix` (with reason).

## Phase 5 — Closure pass (replaces a second session)

Run **after** fixes are committed (before push). Mandatory for Tier B/C; Tier A when code changed.

1. `git diff origin/<baseRefName>...HEAD` — post-fix diff only.
2. **Findings closure:** every row from Phases 1–2 has a terminal status.
3. **Deterministic re-scan:** re-run Phase 1 checklist on **newly changed hunks** only.
4. **Tests:** rerun only if fix commit touched production or test code.
5. Tier C only: **one** closure subagent with **post-fix diff only**.

**SHIP blocked** if any P0–P2 row is open or closure finds new P0–P2.

## Phase 6 — Ship

```bash
git push -u origin <headRefName>
```

- **ManagePullRequest** `update_pr` when behavior changed (per `pr-description-format.mdc`).
- **ManagePullRequest** `post_comment` — findings table + test commands.
- Link created P3 issues.

User summary: findings table, SHIP verdict, PR link. State that closure pass ran.

## `/thermos` and session resume

| Situation | Action |
|-----------|--------|
| New `/thermos PR N` | Run **Phases 0–6** fully |
| Resume + push only | Skip re-audit; complete push/issue links |
| Resume + same `/thermos` again | **Full Phases 0–6** at current `HEAD` |

## Token budget

- [ ] ≤1 PR metadata fetch; ≤2 diffs (initial + post-fix)
- [ ] Subagent count within tier cap; blind input only
- [ ] No `pr-review-response` full read
- [ ] Gradle: ≤2 test rounds (pre-fix + post-fix only if code changed)
