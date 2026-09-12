package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

internal const val MCP_RESOURCE_JSON_MIME_TYPE: String = GradleTapiResourceUri.JSON_MIME_TYPE

internal fun jsonResourceResult(uri: String, value: Any?): ReadResourceResult =
    ReadResourceResult(
        contents = listOf(
            TextResourceContents(
                text = encodeMcpJsonDynamic(value),
                uri = uri,
                mimeType = MCP_RESOURCE_JSON_MIME_TYPE,
            ),
        ),
    )
