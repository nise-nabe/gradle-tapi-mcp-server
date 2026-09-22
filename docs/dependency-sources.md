# Dependency sources name locate

Canonical agent workflow for finding types/symbols in dependency sources (index → search → read). Tool schemas alone omit several operational constraints; use this document as the source of truth.

## Workflow

1. **Index** — `gradle_index_dependency_sources` (required before any search for that `tokenMode`).
2. **Search** — `gradle_search_dependency_sources` (`query`) or `gradle_search_dependency_sources_multi` (`queries`).
3. **Read** — `gradle_read_dependency_source` with a hit's `gav` + `path` (and optional `line`) for a UTF-8 snippet. Idea directory / `sourcePaths` hits may include `sourceRoot`; pass it explicitly only when needed.

`tokenMode` on search **must match** the index. The server does **not** silently reindex when modes differ. Prefer omitting `tokenMode` on search only when you indexed with the default `all`.

## `tokenMode`

| Mode | Behavior | When to use |
|------|----------|-------------|
| `all` (default) | Indexes code identifiers **and** comments/strings | Broader recall; preferred default |
| `idents` | Code identifiers only (skips comments/strings) | Faster / smaller indexes when you only need declared names |

Each mode has its own on-disk index under `.gradle/mcp-dependency-sources/<tokenMode>/` (includes `manifest.json` with `formatVersion` and fingerprint). Modes never share an index. Pass `forceReindex: true` to rebuild even on a fingerprint cache hit. Incompatible `formatVersion` values trigger a rebuild.

## Keep-set and large projects

By default the index uses the Idea project dependency sources keep-set (can be slow or time out on large monorepos). Prefer one of:

- `background: true` — return `indexId` immediately and poll `gradle_get_dependency_sources_index_status` (foreground calls auto-detach after ~45s with `detached: true`)
- `projectPath` — scope the Idea keep-set to one Gradle project subtree (for example `:worker`)
- `artifacts[]` — GAV list (any version, not only the project's graph). The **server** resolves `*-sources.jar` under Gradle user home / Maven local / MCP jars cache (optional `gradleUserHome` override). Prefer `downloadSources: true` instead of listing those caches
- `sourcePaths[]` — local jars, zips, or source trees (with optional GAV labels). Use only when the path is already known (workspace or a zip you have). Do not discover paths by listing Gradle user home / `wrapper/dists`
- `tokenMode: idents` — smaller/faster first-time indexes when you only need declared names

`artifacts[]` is **not** limited to the project's current dependency graph. To inspect another version, or a Maven-coordinate Kotlin / plugin / compiler artifact the Idea keep-set does not attach, pass that GAV (the version need not be in use) and `downloadSources: true`. The server looks up Maven local, the connected project's Gradle user home modules cache (`caches/modules-2/files-2.1`), and the MCP jars cache. **Do not** `ls` / Read `gradleUserHome`, `~/.gradle/caches`, `wrapper/dists`, or `jdks` to discover versions or sources. The `gradleUserHome` tool argument is a server-side cache-home override, not a directory for the agent to walk.

For the connected Gradle/Java runtime, use `gradle_get_build_environment` / `gradle_get_java_runtimes`. To query a different Gradle **runtime** (Tooling API models for that version), `gradle_disconnect` if needed, then `gradle_connect` with `gradleVersion` and re-read the build environment — do not inspect wrapper dists. Do not change the session's connected Gradle version merely to read sources; use `artifacts[]` for Maven-coordinate sources instead.

For a configuration-scoped keep-set, omit `configuration` on `gradle_get_dependency_resolution` to list resolvable names, then resolve with `configuration` (optional `projectPath`) and pass GAVs as `artifacts[]`.

When `artifacts[]` jars are missing locally, pass **`downloadSources: true`** to fetch `*-sources.jar` into `.gradle/mcp-dependency-sources/jars/` (response field `downloadedSources` lists GAVs fetched in that call). Default repository is **Maven Central**. On corporate / air-gapped networks, pass **`sourcesRepositories`** with Maven-layout base URL(s) for your mirror (for example Nexus/Artifactory); when set, only those bases are tried (Central is not appended). Optional `user:token@` in the URL is sent as HTTP Basic auth. Missing-sources errors list searched cache roots and suggest `downloadSources` / `sourcesRepositories` / `sourcePaths`. Do not expect `./gradlew dependencies` alone to fetch sources (it only prints the resolved binary graph). Idea keep-set still requires sources already attached in the Idea model (or use `sourcePaths` / `artifacts`).

## Search semantics

- Query is an **exact simple name** match only (for example `SpringBootApplication`).
- Not FQN (`org.springframework.boot.autoconfigure.SpringBootApplication`), not prefix, and not wildcard.
- `limit` / `perQueryLimit` are query-time only (no `formatVersion` bump): omit or null = unlimited; `0` = empty. `per_query_limit` is an alias for `perQueryLimit`.
- Single search returns hits in posting order; multi search merges, dedups, sorts by `(gav, path, line, column)`, then applies the overall `limit`.

## Minimal JSON example

Index one artifact (any version; need not match the connected project), then search one simple name. Prefer `downloadSources: true` over listing Gradle caches:

```json
{
  "name": "gradle_index_dependency_sources",
  "arguments": {
    "tokenMode": "idents",
    "downloadSources": true,
    "artifacts": [
      { "group": "org.springframework.boot", "name": "spring-boot-autoconfigure", "version": "3.3.0" }
    ]
  }
}
```

```json
{
  "name": "gradle_search_dependency_sources",
  "arguments": {
    "query": "SpringBootApplication",
    "tokenMode": "idents",
    "limit": 5
  }
}
```

Example search hit shape (fields may vary):

```json
{
  "hits": [
    {
      "gav": "org.springframework.boot:spring-boot-autoconfigure:3.3.0",
      "path": "org/springframework/boot/autoconfigure/SpringBootApplication.java",
      "line": 42,
      "column": 1
    }
  ],
  "hitCount": 1
}
```

Then read a snippet:

```json
{
  "name": "gradle_read_dependency_source",
  "arguments": {
    "gav": "org.springframework.boot:spring-boot-autoconfigure:3.3.0",
    "path": "org/springframework/boot/autoconfigure/SpringBootApplication.java",
    "line": 42,
    "contextLines": 10
  }
}
```

Agent skill summaries: `plugins/gradle-tapi-mcp/skills/gradle-tapi-mcp/SKILL.md` and `reference.md`.
