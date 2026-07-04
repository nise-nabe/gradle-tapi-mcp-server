# Kotlin MCP SDK 調査と移行影響分析

調査日: 2026-07-04  
対象リポジトリ: `gradle-tapi-mcp-server` @ Java MCP SDK **2.0.0**

## エグゼクティブサマリー

| 項目 | 結果 |
|------|------|
| 公式 Kotlin MCP SDK | **存在する** (`io.modelcontextprotocol:kotlin-sdk` 0.11.0、JetBrains 共同保守) |
| 現状 SDK | **Java SDK** `io.modelcontextprotocol.sdk:mcp-bom:2.0.0`（Kotlin から利用中） |
| 推奨方針 | **B: Java SDK 維持 + progress リフレクション除去** |
| Kotlin SDK 全面移行 | 技術的には可能だが、protocol 層の中規模リファクタが必要。stdio 専用サーバーではコスト対効果が低い |

## 1. SDK 比較

| | Java SDK（現状） | Kotlin SDK |
|--|------------------|------------|
| Group | `io.modelcontextprotocol.sdk` | `io.modelcontextprotocol` |
| バージョン | 2.0.0 | 0.11.0（独立体系） |
| API | 同期 `McpServer.sync` | コルーチン `suspend` ハンドラ |
| JSON | Jackson 3 (`JacksonMcpJsonMapper`) | kotlinx.serialization |
| stdio | `StdioServerTransportProvider` | `StdioServerTransport`（Okio） |
| 移行 | — | ドロップイン不可、公式 migration guide なし |

## 2. 現状の MCP 依存箇所

Java SDK を直接 import するのは **12 ファイル**（protocol 5、entry 1、feature 5、`BuildExecutionManager` 1、test 1）。  
Gradle Tooling API 連携（`build/`、`connection/`、`cache/`、`model/` のドメインロジック）は SDK 非依存。

## 3. オプション B の検証結果（Java SDK 2.0.0 progress API）

`mcp-core-2.0.0.jar` の公開 API を確認:

- `CallToolRequest.meta()` → `Map<String, Object>?`（`progressToken` を直接取得可能）
- `McpSchema.ProgressNotification.builder(progressToken, progress).total(...).message(...).build()`
- `McpSyncServerExchange.progressNotification(...)` / `loggingNotification(...)`

**結論: リフレクションは不要。** [`McpProgressSupport.kt`](../src/main/kotlin/com/example/gradle/mcp/protocol/McpProgressSupport.kt) を型付き API に置き換え済み（約 100 行の reflection 削除）。  
ユニットテスト: [`McpProgressSupportTest.kt`](../src/test/kotlin/com/example/gradle/mcp/protocol/McpProgressSupportTest.kt)

## 4. オプション C の spike 結果

独立 spike: [`spike/kotlin-mcp-sdk/`](../spike/kotlin-mcp-sdk/)

| 検証項目 | 結果 |
|----------|------|
| ビルド | `./gradlew jar` 成功（Kotlin 2.4.0 + `kotlin-sdk-server:0.11.0`） |
| stdio MCP | `initialize` → `tools/list` → `tools/call` 成功 |
| fat JAR サイズ | **13 MB**（本番 JAR 14 MB と同オーダー；Gradle TAPI 未同梱） |
| progress / logging | `ClientConnection` レシーバで `notification()` / `sendLoggingMessage()` 可能 |
| **stdout 汚染** | `kotlin-logging` 初期化メッセージが **stdout に出力**される（MCP プロトコル破壊リスク） |
| Ktor engine | stdio のみなら不要 |

spike の `gradle_connection_status` は静的 JSON を返すのみ（Tooling API 未使用）。

### 全面移行時の主な作業

1. `protocol/` 全面書き換え（`addTool` + `suspend`、Jackson と kotlinx.serialization の併用）
2. `BuildExecutionManager` のバックグラウンドスレッドからの通知を `ClientConnection` 経由に再設計
3. `EofSignalingInputStream` を Okio `Source` ラッパーに適合
4. kotlin-logging の stdout 出力を抑制する設定（または依存除外）
5. MCP 統合テストの追加（現状は手動 smoke のみ）

## 5. 採用方針

**方針 B を採用:**

- Java MCP SDK 2.0.0 を維持
- `McpProgressSupport` のリフレクションを公式 API に置換（実装済み）
- Kotlin SDK 全面移行は見送り（要件が KMP / idiomatic Kotlin 中心に変わった時点で再検討）

## 6. 参考リンク

- [Kotlin SDK README](https://github.com/modelcontextprotocol/kotlin-sdk)
- [Java SDK 2.0 Migration](https://github.com/modelcontextprotocol/java-sdk/blob/main/MIGRATION-2.0.md)
- [Java SDK Server docs](https://java.sdk.modelcontextprotocol.io/latest/server/)
- [Kotlin SDK API](https://kotlin.sdk.modelcontextprotocol.io/)
