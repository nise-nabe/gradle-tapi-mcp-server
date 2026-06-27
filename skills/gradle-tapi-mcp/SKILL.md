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
1. gradle_connection_status              # 接続時は gradleVersion / javaHome / javaVersion（runtimeStackAvailable=true 時）
2. gradle_get_build_environment          # 解決済み Gradle/Java + javaVersion（軽量）
3. gradle_get_project_overview           # モジュール階層 + taskCount（軽量、maxDepth/maxChildren 可）
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
| composite / includeBuild | `settings.gradle.kts` | `gradle_get_gradle_build` |
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
| `gradle_get_gradle_build` | GradleBuild 全体（composite / includeBuild 向け） |
| `gradle_get_project_model` | プロジェクトモデル（タスクはデフォルト省略） |
| `gradle_get_build_invocations` | 実行可能タスク（セレクタはデフォルト省略） |
| `gradle_get_project_publications` | Publications |
| `gradle_run_tasks` | タスク実行 |
| `gradle_run_tests` | JVM テストクラス実行 |
| `gradle_get_build_status` | バックグラウンドビルドの進捗確認 |

詳細な引数は [reference.md](reference.md)。

## 実行系の出力

`gradle_run_tasks` / `gradle_run_tests` はデフォルトで `stdout` / `stderr` を返さない（`includeOutput=false`）。`outcome`・`buildSummary`・失敗情報のみ返し、`UP-TO-DATE` などのタスクログは含めない。ログが必要なときは `includeOutput=true`。

- 出力ありのとき: `stdout` / `stderr` は文字列（CRLF は LF に正規化）、`maxOutputChars` 既定 8000
- 切り詰め時: 先頭に `... [truncated N chars] ...`、フラグ `stdoutTruncated`, `stdoutTotalChars`
- フォアグラウンド応答: `outcome`（`SUCCESS`/`FAILED`）、`buildSummary`（Gradle サマリー行）
- 詳細 progress は `includeProgress=true` のときのみ（デフォルト false）
- 末尾を残す: `tailOutput=true`（デフォルト）

失敗時は `failedTasks` / `buildSummary.failureSummary` を先に確認する。詳細ログが必要なら `includeOutput=true` を付ける。バックグラウンド実行中は `gradle_get_build_status` で再ポーリングすれば取得できる（メモリ上の実行中ビルドはライブ出力あり）。**ディスクのみのポーリング**（MCP 再起動後やメモリ evict 後）では `includeOutput=true` でも **ビルド完了まで stdout/stderr は空**（MCP が `finalizeBuild` でログを書くまで）。フォアグラウンド実行では同じ `gradle_run_*` 呼び出しに `includeOutput=true` を付けて再実行する。完了ビルドでは `includeProgress` なしでも `failedTaskCount` / `failedTasks` が返る。

## ディスク永続化と `gradle_get_build_status`

バックグラウンドビルドは `<project>/.gradle/mcp-builds/<buildId>/` に記録される（Gradle init script + MCP 終了時書き込み）。

| フィールド | memory | disk |
|-----------|--------|------|
| `statusSource` | `"memory"` | `"disk"` |
| `liveProgress` | なし | `false` |
| `progressAvailable` | なし | あり |
| `recordDirectory` | なし | 絶対パス |

**ステータス解決**: メモリとディスクが食い違うとき（例: `gradle_disconnect` で MCP が `failed` としたが Gradle が走り続けた）は **ディスクの `gradle-result.json` を優先**。Gradle が `running` のまま MCP が終端確定し、`events.ndjson` に MCP `finishedAt` 以降のイベントが無い場合は **MCP 終端ステータス**（デーモン停止の可能性）。

**永続化の正**:
- 実行中ステータス → `gradle-result.json`（init script）
- 終端 `buildSummary` → `mcp-result.json`、なければ `stdout.log` をパース
- 実行中のディスク進捗 → `events.ndjson`（タスク + テストイベント）
- Gradle 終端 `failed` の `failedTaskCount` / `failedTasks` → `events.ndjson`（MCP 終端時は `mcp-result.json`）

**`projectDirectory`**: メモリ evict 後に別プロジェクトへ接続しているとき、ディスクのみの `gradle_get_build_status` で `.gradle/mcp-builds/<buildId>/` を探すプロジェクトルート。

## 長時間ビルドの進捗確認

`build` や `test` が長いときは `background: true` を付けて起動し、返ってきた `buildId` で `gradle_get_build_status` をポーリングする。

```json
{ "tasks": ["build"], "background": true }
```

→ `gradle_run_tasks`（即座に `buildId` を返す）

```json
{ "buildId": "<id>" }
```

→ `gradle_get_build_status`（`status`, `outcome`, `buildSummary`；`stdout`/`stderr` は `includeOutput=true` 時のみ—ディスクのみポーリングでは完了まで空；`progress` は `includeProgress=true` 時のみ；`statusSource` は常に付与）

`buildId` は必須（並行ビルド時の取り違え防止）。複数の `background=true` ビルドを同時実行できる（サーバー側の上限あり）。上限到達時は `BUILD_ALREADY_RUNNING` が返る。

`gradle_connect` と `gradle_get_build_cache_status` はフォアグラウンド／バックグラウンド問わずビルド実行中は拒否される。

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
- Java: {javaVersion} @ {javaHome}
- Connected project: {projectDirectory}

## Project shape (MCP overview)
- Root: {name} ({path}), taskCount: {n}
- Children: {child paths or "none"}

## Verification
- build: {outcome} — {buildSummary.resultLine}
- notes: {truncated output summary if any}
```

## トラブルシュート

| 症状 | 対処 |
|------|------|
| `error.code: NOT_CONNECTED` | `gradle_connect` または MCP 再起動 |
| `gradle_disconnect` で MCP が failed 確定 | ディスクの `gradle-result.json` を優先して再ポーリング；Gradle がまだ走っていれば `running` に戻る |
| 応答が巨大 | `includeTasks` / `includeTaskSelectors` を false のままにする |
| ファイルと Java バージョン不一致 | toolchain 宣言（ファイル）と daemon の Java（MCP）を両方記載 |

## 関連スキル

- `project-context-ingestion` — ビルドファイルから制約を抽出（MCP より先）
- `gradle-build-script` — `build.gradle.kts` / catalog の編集時
