---
name: gradle-tapi-mcp
description: >-
  Query and run Gradle builds through the gradle-tapi-mcp-server MCP tools with
  token-efficient defaults. Use when the gradle MCP server is configured, when
  verifying builds/tests via MCP, when resolving runtime Gradle/Java versions,
  or when supplementing project-context-ingestion with live Tooling API state.
---

# Gradle Tooling API MCP

Gradle プロジェクトの**実行時状態**を MCP 経由で取得・実行する。宣言（`build.gradle.kts` 等）は `project-context-ingestion` を先に使い、MCP は解決済みバージョン・ビルド成否・タスク実行に使う。

## 前提

1. `gradle` MCP サーバーが有効であること（グローバル: `~/.cursor/mcp.json`、またはプロジェクトの `.cursor/mcp.json`）
2. JAR がビルド済みであること（`gradle-tapi-mcp-server` で `./gradlew jar`）
3. 接続前に `gradle_connection_status` で `connected: true` を確認。未接続なら `gradle_connect` または `GRADLE_PROJECT_DIR=${workspaceFolder}`

グローバル設定では `${workspaceFolder}` が開いている Gradle プロジェクトを指す。設定例は [README.md](../../README.md) を参照。

## ワークフロー（トークン節約順）

```
1. gradle_connection_status
2. gradle_get_build_environment          # 解決済み Gradle/Java（軽量）
3. gradle_get_project_overview           # モジュール階層 + taskCount（軽量）
4. [必要時] gradle_run_tasks ["build"]   # ビルド検証
5. [必要時] gradle_run_tests [...]       # 特定テストクラス
```

**重いツールは必要なときだけ**

| 目的 | ツール | オプション |
|------|--------|-----------|
| タスク一覧 | `gradle_get_project_model` | `includeTasks=true` |
| タスク説明付き | 同上 | `+ includeTaskDetails=true` |
| 絞り込み | 同上 / `gradle_get_build_invocations` | `taskGroup`, `taskNamePrefix`, `maxTasks` |
| セレクタ | `gradle_get_build_invocations` | `includeTaskSelectors=true` |
| Publications | `gradle_get_project_publications` | — |

`gradle_get_project_model` と `gradle_get_build_invocations` を引数なしで連続呼び出ししない。大規模プロジェクトでトークンが爆発する。

## project-context-ingestion との役割分担

| 情報 | 先に読む場所 | MCP で補う |
|------|-------------|-----------|
| 宣言バージョン（Kotlin, Boot, plugins） | `libs.versions.toml`, `build.gradle.kts` | — |
| 解決済み Gradle/Java | — | `gradle_get_build_environment` |
| モジュール構成 | `settings.gradle.kts` | `gradle_get_project_overview` |
| ビルド成否 | — | `gradle_run_tasks` |
| 全タスク探索 | 通常は不要 | `includeTasks` + フィルタ |

MCP の結果で brief を作るときは、ファイルから得た **宣言** と MCP の **実行時** を混同しない。

## ツール早見表

| ツール | 用途 |
|--------|------|
| `gradle_connect` | プロジェクトルートへ接続 |
| `gradle_connection_status` | 接続確認 |
| `gradle_disconnect` | 切断 |
| `gradle_get_build_environment` | Gradle/Java 実行環境 |
| `gradle_get_build_cache_status` | Build Cache / Configuration Cache 設定とローカルキャッシュ概要 |
| `gradle_get_project_overview` | 階層 + taskCount（推奨） |
| `gradle_get_project_model` | プロジェクトモデル（タスクはデフォルト省略） |
| `gradle_get_build_invocations` | 実行可能タスク（セレクタはデフォルト省略） |
| `gradle_get_project_publications` | Publications |
| `gradle_run_tasks` | タスク実行 |
| `gradle_run_tests` | JVM テストクラス実行 |
| `gradle_get_build_status` | バックグラウンドビルドの進捗確認 |

詳細な引数は [reference.md](reference.md)。

## 実行系の出力

`gradle_run_tasks` / `gradle_run_tests` はデフォルトで各ストリーム最大 8000 文字に truncate する。

- `stdout` / `stderr` は従来どおり文字列
- 切り詰め時: `stdoutTruncated`, `stdoutTotalChars`（stderr も同様）
- 末尾を残す: `tailOutput=true`（デフォルト）
- 調整: `maxOutputChars`, `tailOutput`

失敗時は `stdout` 末尾（ビルドサマリー）を優先して読む。

## 長時間ビルドの進捗確認

`build` や `test` が長いときは `background: true` を付けて起動し、返ってきた `buildId` で `gradle_get_build_status` をポーリングする。

```json
{ "tasks": ["build"], "background": true }
```

→ `gradle_run_tasks`（即座に `buildId` を返す）

```json
{ "buildId": "<id>" }
```

→ `gradle_get_build_status`（`status`, `progress`, 途中の `stdout`/`stderr`）

`buildId` を省略すると、実行中または直近のビルドを返す。同時に走らせられるバックグラウンドビルドは 1 件。

フォアグラウンド実行（デフォルト）でも、完了レスポンスに `progress` サマリーが含まれる。

## 典型的な呼び出し

**スタック確認（軽量）**

```json
{}
```

→ `gradle_get_build_environment`

**マルチモジュール把握**

```json
{}
```

→ `gradle_get_project_overview`

**Build Cache 調査（ビルド不要）**

```json
{}
```

→ `gradle_get_build_cache_status`

Configuration Cache 互換性まで試す場合:

```json
{ "probeConfigurationCache": true }
```

**ビルド検証**

```json
{ "tasks": ["build"] }
```

→ `gradle_run_tasks`

**テストクラス指定**

```json
{ "testClasses": ["com.example.FooTest"] }
```

→ `gradle_run_tests`

**ビルド系タスクだけ列挙**

```json
{
  "includeTasks": true,
  "taskGroup": "build",
  "maxTasks": 20
}
```

→ `gradle_get_project_model`

## エージェント向け brief テンプレート

MCP だけでなくファイルも読んだうえで、次の形式で要約する:

```markdown
## Gradle runtime (MCP)
- Gradle: {gradleVersion} @ {gradleUserHome}
- Java: {javaHome}
- Connected project: {projectDirectory}

## Project shape (MCP overview)
- Root: {name} ({path}), taskCount: {n}
- Children: {child paths or "none"}

## Verification
- build: {SUCCESS|FAILED} (gradle_run_tasks)
- notes: {truncated output summary if any}
```

## トラブルシュート

| 症状 | 対処 |
|------|------|
| `Not connected` | `gradle_connect` または MCP 再起動 |
| MCP タイムアウト | Cursor で `MCP: Restart Servers`、JAR 再ビルド |
| 応答が巨大 | `includeTasks` / `includeTaskSelectors` を false のままにする |
| ファイルと Java バージョン不一致 | toolchain 宣言（ファイル）と daemon の Java（MCP）を両方記載 |

## 関連スキル

- `project-context-ingestion` — ビルドファイルから制約を抽出（MCP より先）
- `gradle-build-script` — `build.gradle.kts` / catalog の編集時
