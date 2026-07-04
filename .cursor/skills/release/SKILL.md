---
name: release
description: >-
  Release workflow for gradle-tapi-mcp-server: check tags, bump version via PR,
  build JAR, tag, create GitHub Release, update install.sh SHA-256.
---

# Release (gradle-tapi-mcp-server)

Publish `vX.Y.Z` tag and fat JAR on GitHub Releases. `main` is branch-protected — version bumps go through PRs.

## Current tags (newest first)

`v0.2.2` (latest) · `v0.2.1` · `v0.2.0` · `v0.1.0`

```bash
git fetch origin --tags && git tag -l --sort=-v:refname
gh release list --repo nise-nabe/gradle-tapi-mcp-server
git log v0.2.2..main --oneline   # unreleased commits
```

## Quick workflow

1. **Verify** — `./gradlew --no-daemon build` on `main`
2. **Bump PR** — update `build.gradle.kts` (`version`) and `README.md` (2 JAR paths); commit `chore(release): bump version to X.Y.Z`; open PR via **ManagePullRequest** (`cloud-github` skill)
3. **After merge** — on `main`: `./gradlew jar` → `build/libs/gradle-tapi-mcp-server-X.Y.Z.jar`
4. **Tag** — `git tag vX.Y.Z && git push origin vX.Y.Z` on merged `main` HEAD
5. **Release** — `gh release create vX.Y.Z --title X.Y.Z --generate-notes build/libs/gradle-tapi-mcp-server-X.Y.Z.jar`
6. **Cloud bootstrap** (follow-up PR) — update `.cursor/install.sh` (`GRADLE_TAPI_MCP_VERSION`, `GRADLE_TAPI_MCP_SHA256` from `sha256sum` of the release JAR), plus `AGENTS.md` and `.cursor/skills/gradle-tapi-mcp/SKILL.md` version strings

## Version choice

Patch (`0.2.3`) for fixes/refactors/deps; minor within 0.x for notable features. Inspect `git log vLATEST..main`.

## Full reference

See `skills/release/SKILL.md` in this repository for the checklist, release notes template, verification steps, and troubleshooting.
