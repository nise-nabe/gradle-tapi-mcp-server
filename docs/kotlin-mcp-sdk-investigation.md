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

- **kotlin-logging 起動メッセージ**: `GradleTapiMcpServerLauncher` が `KotlinLoggingConfiguration.logStartupMessage = false` を SDK 初期化前に設定し、stdio の stdout を JSON-RPC 専用に保つ。`gradle.tapi.mcp.smoke=true ./gradlew test` で起動 smoke を実行可能。
- **リクエストタイムアウト**: `ServerOptions.timeout = 30.minutes`（Java SDK 移行前の `requestTimeout` と同等）。
- **長時間ツール呼び出し**: ブロッキング Gradle 処理は `Dispatchers.IO` で実行し、stdio のメッセージ処理ループを解放する。
- MCP 統合テストは引き続き手動 smoke のみ（上記 opt-in テストを除く）。

## 参考

- [Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
- [Kotlin SDK API](https://kotlin.sdk.modelcontextprotocol.io/)
- 移行前 spike: [`spike/kotlin-mcp-sdk/`](../spike/kotlin-mcp-sdk/)
