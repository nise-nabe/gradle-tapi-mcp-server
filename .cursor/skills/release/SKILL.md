---
name: release
description: >-
  Release workflow for gradle-tapi-mcp-server: check tags, bump version via PR
  (including marketplace metadata), build JAR, tag, create GitHub Release, then
  update install.sh SHA-256.
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
2. **Bump PR** — `build.gradle.kts` + `README.md` + marketplace catalogs + plugin.json versions; open PR via **ManagePullRequest** (`cloud-github` skill)
3. **After merge** — on `main`: `./gradlew --no-daemon jar` → tag `vX.Y.Z` on `main` HEAD → `gh release create` with `--repo nise-nabe/gradle-tapi-mcp-server`
4. **Cloud bootstrap** (SHA PR only) — `install.sh` + `server-release.json` version and SHA-256 together (do not bump those version fields in the bump PR). Marketplace / plugin `version` are already in step 2.

## Full reference

See `skills/release/SKILL.md` for the checklist, file tables, SHA-256 capture, release notes template, verification steps, and troubleshooting.
