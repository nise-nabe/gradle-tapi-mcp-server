---
name: release
description: >-
  Release workflow for gradle-tapi-mcp-server: check tags, bump version via PR,
  build JAR, tag, create GitHub Release, update install.sh SHA-256.
---

# Release (gradle-tapi-mcp-server)

Publish `vX.Y.Z` tag and fat JAR on GitHub Releases. `main` is branch-protected — version bumps go through PRs.

## Status check

```bash
git fetch origin --tags && git tag -l --sort=-v:refname | head -1   # latest tag
gh release list --repo nise-nabe/gradle-tapi-mcp-server
git log "$(git tag -l --sort=-v:refname | head -1)"..main --oneline   # unreleased commits
```

## Workflow (summary)

1. **Verify** — `git checkout main && git pull origin main`, then `./gradlew --no-daemon build`
2. **Bump PR** — `build.gradle.kts` + `README.md`; open PR via **ManagePullRequest** (`cloud-github` skill)
3. **After merge** — on `main`: `./gradlew --no-daemon jar` → tag `vX.Y.Z` on `main` HEAD → `gh release create` with `--repo nise-nabe/gradle-tapi-mcp-server`
4. **Cloud bootstrap** (follow-up PR) — `install.sh`, `AGENTS.md`, skill version strings (see full reference)

## Full reference

See `skills/release/SKILL.md` for the checklist, file tables, SHA-256 capture, release notes template, verification steps, and troubleshooting.
