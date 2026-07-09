package com.example.gradle.mcp.connection

/**
 * Serializes project lifecycle operations (connect, disconnect, build start, cancel)
 * so concurrent MCP tool calls cannot interleave incompatible states.
 */
internal object ProjectLifecycleLock
