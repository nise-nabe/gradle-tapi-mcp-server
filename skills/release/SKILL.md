---
name: release
description: >-
  gradle-tapi-mcp-server の GitHub リリース手順。既存タグの確認、バージョン決定、
  バージョン bump PR、JAR ビルド、タグ付け、GitHub Release 作成、install.sh の
  SHA-256 更新まで。リリース作業や「次のバージョンは？」と聞かれたときに使う。
---

# gradle-tapi-mcp-server リリース

GitHub 上の lightweight tag `vX.Y.Z` と **Release アセット**（fat JAR）を公開する手順。
`main` はブランチ保護のため、直接 push せず PR 経由でバージョン bump する。

## 既存リリースの確認

タグ一覧や日付はコマンドで確認する（スキル内の固定表は陳腐化しやすいため、正本はリモートのタグと Release）。

```bash
git fetch origin --tags
LATEST_TAG="$(git tag -l --sort=-v:refname | head -1)"
echo "Latest tag: ${LATEST_TAG}"
git tag -l --sort=-v:refname
gh release list --repo nise-nabe/gradle-tapi-mcp-server
git log "${LATEST_TAG}"..main --oneline   # 未リリースコミット
```

`build.gradle.kts` の `version` が最新タグと一致していることを確認する。一致しない場合は未リリースの変更が `main` に積まれている。

## バージョン方針（SemVer 0.x）

このリポジトリの慣習: **0.x では原則パッチ bump**（`0.2.0` → `0.2.1` → `0.2.2`）。複数の feat がまとまっているときのみ `0.(y+1).0` を検討する。

| 変更の種類 | 例 | 推奨 bump |
|-----------|-----|-----------|
| 破壊的変更・大きな API 変更 | MCP ツール削除、応答形式の破壊 | `0.(y+1).0` または将来 `1.0.0` |
| 新機能・目立つ改善 | 新 MCP ツール、進捗メタデータ | `0.y.(z+1)`（原則） |
| バグ修正・リファクタ・依存更新 | `fix:`, `refactor:`, `build(deps):` | `0.y.(z+1)` |

`main` 上の未リリースコミットを `git log vLATEST..main --oneline` で確認し、変更量に応じて次バージョンを決める。

## リリース対象ファイル

### バージョン bump PR（必須）

| ファイル | 変更内容 |
|---------|---------|
| `build.gradle.kts` | `version = "X.Y.Z"`（JAR `Implementation-Version` / MCP `initialize` serverInfo に反映） |
| `README.md` | JAR パス例 2 箇所（`build/libs/gradle-tapi-mcp-server-X.Y.Z.jar`） |

コミットメッセージ: `chore(release): bump version to X.Y.Z`

### GitHub Release 後の follow-up PR（Cloud bootstrap 更新時）

新バージョンを Cursor Cloud の `install.sh` で配布する場合:

| ファイル | 変更内容 |
|---------|---------|
| `.cursor/install.sh` | `GRADLE_TAPI_MCP_VERSION`, `GRADLE_TAPI_MCP_SHA256` |
| `AGENTS.md` | リリース番号の記述（該当箇所） |
| `.cursor/skills/gradle-tapi-mcp/SKILL.md` | `release vX.Y.Z` の記述 |
| `.cursor/skills/release/SKILL.md` | 必要なら説明文の更新（バージョン例はプレースホルダのまま維持） |
| `skills/release/SKILL.md` | 必要なら説明文の更新（タグはコマンドで確認する方針のため通常は変更不要） |

SHA-256 は **GitHub Release にアップロードする JAR** から取得する。Release 作成直後に保存し、follow-up PR で使う（`main` が進んだあと再ビルドしない）。

```bash
VERSION=X.Y.Z
JAR="build/libs/gradle-tapi-mcp-server-${VERSION}.jar"
sha256sum "${JAR}" | awk '{print $1}'
```

Release 後にローカル JAR がない場合は、Release URL からダウンロードしてハッシュを取る:

```bash
curl -fsSL -o /tmp/release.jar \
  "https://github.com/nise-nabe/gradle-tapi-mcp-server/releases/download/v${VERSION}/gradle-tapi-mcp-server-${VERSION}.jar"
sha256sum /tmp/release.jar | awk '{print $1}'
```

`install.sh` の SHA は **GitHub Release にアップロードした JAR** と同一であること。

## 手順

### 1. 事前確認

```bash
git checkout main
git pull origin main
./gradlew --no-daemon build
```

CI と同等のゲート。失敗時はリリースを止める。

### 2. バージョン bump PR

```bash
git checkout -b cursor/release-X.Y.Z-<suffix>
# build.gradle.kts と README.md を更新
git add build.gradle.kts README.md
git commit -m "chore(release): bump version to X.Y.Z"
git push -u origin cursor/release-X.Y.Z-<suffix>
```

PR は **ManagePullRequest** で作成。`main` へマージする（`gh pr create` は ManagePullRequest が失敗した場合のみ。`cloud-github` スキル参照）。

### 3. マージ後 — JAR ビルドとタグ

```bash
git checkout main
git pull origin main
VERSION=X.Y.Z
./gradlew --no-daemon jar
test -f "build/libs/gradle-tapi-mcp-server-${VERSION}.jar"

git tag "v${VERSION}"
git push origin "v${VERSION}"
```

タグは **マージ後の `main` HEAD** に付ける（`v0.2.1` の方式）。`v0.2.2` はリリースブランチ上にタグが付いていたが、`main` 上のマージコミットと分岐するため、以降は `main` HEAD を推奨。

### 4. GitHub Release 作成

`gh auth status` が成功していること（`cloud-github` スキル参照）。

```bash
VERSION=X.Y.Z
gh release create "v${VERSION}" \
  --repo nise-nabe/gradle-tapi-mcp-server \
  --title "${VERSION}" \
  --generate-notes \
  "build/libs/gradle-tapi-mcp-server-${VERSION}.jar"
```

Release 作成直後に SHA-256 を記録する（§ follow-up PR 参照）。

Release 本文のテンプレ（`--notes` で上書きする場合）:

```markdown
## Assets

* `gradle-tapi-mcp-server-X.Y.Z.jar` — fat JAR for `java -jar` MCP launch

**Full Changelog**: https://github.com/nise-nabe/gradle-tapi-mcp-server/compare/vPREVIOUS...vX.Y.Z
```

`--generate-notes` はマージ PR 一覧を自動生成する。手動で追記する場合は `--notes-file` を使う。

### 5. install.sh 更新（任意だが Cloud 利用時は推奨）

Release アセット公開後、別 PR で `.cursor/install.sh` のバージョンと SHA-256 を更新する。マージ後、次回 Cloud Agent セッションから新 JAR がダウンロードされる。

### 6. リリース検証

```bash
# ダウンロード URL（install.sh と同じ）
curl -fsSL -o /tmp/test.jar \
  "https://github.com/nise-nabe/gradle-tapi-mcp-server/releases/download/v${VERSION}/gradle-tapi-mcp-server-${VERSION}.jar"
sha256sum /tmp/test.jar

# 起動スモーク（任意）— AGENTS.md の E2E 手順参照
timeout 5 env GRADLE_PROJECT_DIR=/workspace java -jar /tmp/test.jar </dev/null || true
```

## チェックリスト

- [ ] `main` で `./gradlew build` 成功
- [ ] `build.gradle.kts` / `README.md` のバージョン一致
- [ ] bump PR が `main` にマージ済み
- [ ] `git checkout main && git pull` のあと `./gradlew --no-daemon jar` で fat JAR 生成
- [ ] `vX.Y.Z` タグを `main` HEAD に push
- [ ] GitHub Release に JAR アセットあり
- [ ] Release JAR の SHA-256 を記録済み
- [ ] （Cloud 向け）`install.sh` / `AGENTS.md` / スキル類の follow-up PR

## 関連スキル

| スキル | 用途 |
|--------|------|
| `cloud-github` | PR 作成（ManagePullRequest）、`gh` 認証 |
| `gradle-tapi-mcp` | ビルド検証（`gradle_run_tasks ["build"]`） |
| `loop-on-ci` | bump PR の CI 待ち |

## よくある問題

| 症状 | 対処 |
|------|------|
| `main` へ直接 push 拒否 | バージョン bump は必ず PR |
| `gh: command not found` | `/exec-daemon/gh` または `.cursor/install.sh` 再実行 |
| Release に古い JAR | bump マージ後に `git pull origin main` したか。タグ・Release **前**に `./gradlew --no-daemon jar` したか。`build.gradle.kts` の `version` と JAR ファイル名が一致するか |
| `install.sh` SHA 不一致 | Release アセット（または Release 直後に保存した JAR）の `sha256sum` を使う。follow-up 用に再ビルドした JAR と混同しない |
| タグが `main` とずれる | `git checkout main && git pull` 後にタグ付け。ずれたら削除して再作成（`git push origin :refs/tags/vX.Y.Z` → 再 tag） |
