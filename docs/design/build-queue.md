# Design: per-project build queue (`waitForSlot` / enqueue)

Related: [issue #174](https://github.com/nise-nabe/gradle-tapi-mcp-server/issues/174) §2  
Sibling work: [PR #175](https://github.com/nise-nabe/gradle-tapi-mcp-server/pull/175) covers §1 / docs / multi Test-task guidance and **explicitly defers** queue / `waitForSlot`.

This note investigates the current single-flight gate and proposes a queue design if we want agents to chain `compile → test → other suite` without inventing a poll loop between every `gradle_run_*`.

---

## 1. Problem (from agent feedback)

Agents often need sequential Gradle work on one checkout:

1. `gradle_run_tasks` (`:compileKotlin`, instrumentation, …) with `background: true`
2. Poll until terminal
3. `gradle_run_tests` for `:test`
4. Poll again
5. Another `gradle_run_tests` for a custom suite (`:fastTest`)

Friction:

| Friction | Effect |
|----------|--------|
| Second `gradle_run_*` while the first is still `running` | `BUILD_ALREADY_RUNNING` |
| Parallel tool calls in one agent turn | One succeeds, siblings fail with the same error (race) |
| Agent must poll to terminal between every step | Extra turns and brittle “did it finish?” logic |

Issue §2 asks for an optional queue (or `waitForSlot: true`) so “compile then test” is one logical workflow. Multi Test-task batching (`tasks` + `includePatterns`) already reduces the dual-suite case; queue is for **true sequential different invocations** (and for accidental parallel starts).

---

## 2. Current behavior (as implemented)

### 2.1 Single-flight per project

`BuildExecutionManager.registerBuildStart` holds `ProjectLifecycleLock.forProject` and rejects a second start when `hasActiveBuild(projectDirectory)` is true:

```kotlin
// hasActiveBuild: any in-memory record with status == "running" for that project
```

There is **no grace window** after terminal status. As soon as `finalizeBuild` marks succeeded / failed / cancelled, the next start is allowed.

### 2.2 Global concurrent pool (cross-project)

Background/foreground work shares a bounded executor (`MAX_CONCURRENT_BUILDS = max(4, processors)`). When the pool is full, starts also return `BUILD_ALREADY_RUNNING` (same code, different message).

### 2.3 What else shares the gate

While a build is `running` for a project:

- `gradle_connect` for that project is rejected
- Model / overview / cache / Java-runtime probes that use `ProjectLifecycleGuard.withNoActiveBuild` are rejected
- `gradle_run_tests` preflight (unscoped multi-project check) uses the same guard

Queued work must define how these interact with **not-yet-running** entries.

### 2.4 Why “completed then BUILD_ALREADY_RUNNING” still happens

Lock release itself is prompt. Typical residual causes:

1. **Agent parallelism** — two `gradle_run_*` in one turn; first takes the slot, second fails (matches the issue’s `:fastTest` race).
2. **Status vs start race** — agent starts the next run before the previous record leaves `running` (finalize still in flight after Tooling API return).
3. **Misread terminal** — client timeout on `waitUntilComplete` (§1) looks like failure while the build is still `running`.

Queue helps (1) and softens (2). §1 / docs (PR #175) address (3).

---

## 3. Goals and non-goals

### Goals

- Allow agents to submit the next `gradle_run_tasks` / `gradle_run_tests` for the **same** `projectDirectory` without failing when a build is already active.
- Keep **at most one running Gradle Tooling API operation per project** (daemon / `ProjectConnection` safety unchanged).
- Stay compatible with background + poll (do not require long-held MCP tool requests).
- Make queue state visible via existing status / list APIs.

### Non-goals

- Concurrent Gradle builds on the same project (unsafe; out of scope).
- Replacing multi-task / multi-suite batching in a single invocation.
- Cross-project fair scheduling beyond today’s global executor pool.
- Guaranteeing order across **different** MCP clients / processes (stdio server is single-process).

---

## 4. Options

### Option A — Status quo + docs only

Document “serialize `gradle_run_*`; batch tests when possible.”  
**Pros:** Zero code risk. **Cons:** Agents still pay poll-between-steps tax; parallel turns keep producing noise errors.  
**Status:** Covered by PR #175 for documentation.

### Option B — Blocking `waitForSlot: true`

If busy, block the `gradle_run_*` tool call until the slot frees, then start (or return `BUILD_ALREADY_RUNNING` after a timeout).

| Pros | Cons |
|------|------|
| Simple API | Same class of failure as §1: MCP **client** transport timeout while the server waits |
| Feels like “queue of depth 1 waiter” | Foreground + wait compounds two blocking layers |
| | Holding an MCP request open is hostile to Cursor Cloud agents |

**Verdict:** Reject as the primary mechanism. If ever added, only with a **short** server-side cap (≤30s) and a structured “still waiting / poll again” response — not a multi-minute block.

### Option C — Enqueue + `status: queued` (recommended)

If the per-project slot is taken, accept the request, assign a `buildId`, mark `queued`, return immediately. When the running build reaches a terminal status, dequeue FIFO and start the next on the executor.

| Pros | Cons |
|------|------|
| Fits background + poll; no long MCP wait | New status + cancel / list / disconnect semantics |
| Absorbs parallel tool-call races | Agents must understand `queued` ≠ `running` |
| Compose compile → test without inventing a wait loop | Needs depth limits and clear error when full |

**Verdict:** Preferred if we implement §2’s optional enhancement.

### Option D — Multi-step workflow tool

One tool accepts an ordered list of run specs and runs them as a pipeline.  
**Pros:** Explicit workflow. **Cons:** Large schema / new mental model; overlaps Option C; harder to cancel mid-pipeline. Defer unless queue UX proves insufficient.

---

## 5. Recommended design (Option C)

### 5.1 API surface

Extend `gradle_run_tasks` and `gradle_run_tests`:

| Argument | Default | Behavior |
|----------|---------|----------|
| `queueIfBusy` | `false` | When `false`, keep today’s reject with `BUILD_ALREADY_RUNNING`. When `true` and a build is already `running` or `queued` for the project, enqueue. |
| *(optional later)* `queueTimeoutMs` | n/a | Not needed for enqueue; only if we add Option B-style wait. |

Naming notes:

- Issue used `waitForSlot`. Prefer **`queueIfBusy`** to signal non-blocking enqueue, not a long wait.
- Default **`false`** preserves current fail-fast for callers that treat `BUILD_ALREADY_RUNNING` as “don’t stack work.”

Background should be the normal path with `queueIfBusy: true`:

```json
{ "tasks": ["compileKotlin"], "background": true, "queueIfBusy": true }
```

Foreground + `queueIfBusy: true`:

- **Allowed but discouraged.** If enqueued, either (a) reject with `INVALID_ARGUMENT` (“use background when queueing”), or (b) return immediately with `buildId` + `status: queued` + `detached: true` semantics (same shape as foreground detach). Prefer **(a)** for v1 to avoid silent detach surprises.

### 5.2 Lifecycle states

Per `buildId`:

```text
queued → running → succeeded | failed | cancelled
```

- `hasActiveBuild` today means “slot occupied by `running`.” After queue: the **slot** is occupied by at most one `running` build; **queue occupancy** is separate.
- Model / connect guards should continue to key off **`running` only**, so agents can still query models while something is merely `queued`?  

**Recommendation for v1:** treat **any non-terminal build for the project (`queued` or `running`)** as blocking model queries and `gradle_connect` that would race the connection. Rationale: a queued build is about to use the connection; allowing a long model fetch in between adds surprising latency and TOCTOU. Document this clearly.

Alternative (looser): only `running` blocks model tools. Slightly better for “inspect while waiting,” but riskier. Decide in implementation PR; default to stricter.

### 5.3 Admission and limits

Per `projectDirectory`:

| Limit | Suggested default | On exceed |
|-------|-------------------|-----------|
| Max queued (not including running) | `3` | `BUILD_ALREADY_RUNNING` or new `BUILD_QUEUE_FULL` |
| Max running | `1` (unchanged) | — |

Prefer a **new** `McpErrorCode.BUILD_QUEUE_FULL` so agents can distinguish “busy, retry later” from “queue saturated, cancel or wait.” If we want fewer codes, reuse `BUILD_ALREADY_RUNNING` with a distinct message (weaker).

Global executor pool:

- Queued builds must **not** hold an executor thread.
- On dequeue, `executor.execute` may still hit the global pool AbortPolicy → keep returning `BUILD_ALREADY_RUNNING` (pool full), and leave the item **queued** (retry dequeue when any build finishes) **or** fail that build as `failed` with an internal message. Prefer **remain queued** and retry on any terminal event / periodic wake, with a documented max wait or “still queued” status.

### 5.4 Ordering and start

- FIFO per project.
- Dequeue trigger: `finalizeBuild` success path (and cancel/shutdown paths that free the slot).
- Start under the same `ProjectLifecycleLock` as today’s `registerBuildStart` so two dequeue races cannot create two runners.

### 5.5 Cancel

| Target | Behavior |
|--------|----------|
| `queued` buildId | Remove from queue; mark `cancelled`; no Tooling API cancel |
| `running` buildId | Existing `CancellationToken` path |
| Disconnect / shutdown | Cancel running **and** drain queue as `cancelled` for that project (or all) |

`gradle_cancel_build` response should include `terminalStatus: cancelled` immediately for queued items (no “poll until not running” delay).

### 5.6 Status and list

- `gradle_get_build_status` / `gradle_list_builds` include `status: queued`.
- Useful fields while queued: `queuePosition` (1-based), `queuedBehindBuildId` (optional), `tasks` / selection already known.
- `waitUntilComplete: true` on a queued build: wait until **terminal**, not merely until `running` (same poll loop; just accept `queued` as non-terminal). Respect the short wait caps from §1 / PR #175.

### 5.7 Persistence

Today disk recorder assumes a started Gradle operation. For v1:

- Do **not** create Gradle init-script record dirs until the build actually starts (`running`).
- Memory-only `queued` is enough; if the MCP process dies, queued work is lost (same as in-flight plans). Document that.

### 5.8 Agent skill / docs updates (when implementing)

- Prefer batching multiple Test tasks in one call when filters allow.
- Use `queueIfBusy: true` + `background: true` when chaining distinct steps.
- Still avoid unbounded parallel `gradle_run_*` without queue (default remains reject).
- Poll `queued` → `running` → terminal; do not treat `queued` as stuck.

---

## 6. Interaction with multi Test-task

Issue §2 also asked to run `:test` and `:fastTest` in one invocation. That is **already supported** via `tasks` + `includePatterns` (clarified in PR #175). Queue does not replace that path:

| Need | Mechanism |
|------|-----------|
| Same filters across several Test tasks | One `gradle_run_tests` with multiple `tasks` |
| Different steps (compile then tests, or incompatible selectors) | Queue (`queueIfBusy`) or agent-side poll between calls |
| Accidental parallel starts | Queue absorbs; default reject remains |

---

## 7. Risks

| Risk | Mitigation |
|------|------------|
| Agents enqueue forever / leak work | Small max depth; cancel on disconnect |
| Queued build starts after agent moved on | Cancel API; skills say cancel unused `buildId`s |
| Confusion with §1 timeouts | Enqueue must not block the tool response |
| Model query vs impending build | Stricter gate while `queued` (see §5.2) |
| Schema size budget (`tools/list`) | Short property description; details in skill/reference |
| Double meaning of `BUILD_ALREADY_RUNNING` | Prefer `BUILD_QUEUE_FULL` for depth exceeded |

---

## 8. Suggested rollout

1. **Docs only** (PR #175) — done / in flight for non-queue items.
2. **This design** — review & agree Option C + defaults.
3. **Implementation PR** (follow-up):
   - `queued` status in `BuildProgressTracker` (or parallel enum)
   - Per-project queue in `BuildExecutionManager`
   - `queueIfBusy` on run tools
   - Cancel / list / status / disconnect
   - Unit tests: parallel enqueue, FIFO start after finalize, cancel queued, queue full, model guard
   - Skill + reference updates
4. **Optional later:** `BUILD_QUEUE_FULL` code; looser model-vs-queued policy; Option D workflow tool.

---

## 9. Recommendation

Implement **Option C (`queueIfBusy`, default false, background-only in v1)** after PR #175 lands. Do **not** ship blocking `waitForSlot` as the main fix — it collides with MCP client timeouts that §1 is already fixing.

Until then, agents should: serialize `gradle_run_*`, batch multi-suite tests in one call, and poll with short `waitUntilComplete` windows.
