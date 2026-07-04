# Kotlin MCP SDK 調査と移行記録

調査日: 2026-07-04  
移行完了: 2026-07-04

## サマリー

本リポジトリは **公式 Kotlin MCP SDK**（`io.modelcontextprotocol:kotlin-sdk-server` **0.11.0**）へ全面移行済みです。  
以前の Java SDK（`io.modelcontextprotocol.sdk:mcp-bom` 2.0.0）は削除しました。

## 採用 SDK

| 項目 | 値 |
|------|-----|
| Artifact | `io.modelcontextprotocol:kotlin-sdk-server:0.11.0` |
| 補助 | `kotlinx-coroutines-core`, `kotlinx-serialization-json` |
| ツール結果 JSON | kotlinx.serialization（`McpJsonSupport`、クライアント互換のため `TextContent` 内 JSON 文字列） |
| MCP ワイヤ | kotlinx.serialization（SDK 内部） |

## アーキテクチャ変更

| 層 | 変更 |
|----|------|
| [`GradleTapiMcpServer.kt`](../src/main/kotlin/com/example/gradle/mcp/GradleTapiMcpServer.kt) | `Server` + `StdioServerTransport` + `runBlocking` |
| [`McpToolSupport.kt`](../src/main/kotlin/com/example/gradle/mcp/protocol/McpToolSupport.kt) | `Server.registerTool()` + `suspend` ハンドラ |
| [`McpBuildNotifier.kt`](../src/main/kotlin/com/example/gradle/mcp/protocol/McpBuildNotifier.kt) | バックグラウンドビルド向け progress/logging 抽象 |
| [`McpSchemaMapping.kt`](../src/main/kotlin/com/example/gradle/mcp/protocol/McpSchemaMapping.kt) | `Map` スキーマ ↔ `ToolSchema` / `JsonObject` 変換 |
| 各 `*Tools.kt` | `registerXxxTools(scope)` パターン（17 ツール） |
| [`BuildExecutionManager.kt`](../src/main/kotlin/com/example/gradle/mcp/build/BuildExecutionManager.kt) | `McpBuildNotifier?` 経由で通知 |

## 検証

- `./gradlew build` — 全ユニットテスト成功
- stdio smoke: `initialize` → `tools/list`（17 ツール）→ `gradle_connection_status`（Gradle 9.6.1 接続確認）
- fat JAR: **約 19 MB**（移行前 14 MB）

## 既知の注意点

- **kotlin-logging 初期化メッセージ**が起動時に stdout に 1 行出力される（SDK  transitively）。MCP クライアントが厳密な stdout パースをする場合は要監視。`System.setProperty("kotlin-logging-to-slf4j", "true")` を `main` 先頭で設定済み。
- MCP 統合テストは引き続き手動 smoke のみ。

## 参考

- [Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
- [Kotlin SDK API](https://kotlin.sdk.modelcontextprotocol.io/)
- 移行前 spike: [`spike/kotlin-mcp-sdk/`](../spike/kotlin-mcp-sdk/)
