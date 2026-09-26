package com.example.gradle.mcp.server

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

/**
 * stdio transport keeps newline-delimited JSON-RPC on the real stdout. The
 * Tooling API installs missing Gradle distributions through
 * `org.gradle.wrapper.Install`/`Download`, whose `org.gradle.wrapper.Logger`
 * writes `Downloading <url>` and progress characters directly to `System.out`;
 * those lines corrupt the protocol stream while a distribution is downloaded.
 *
 * Call once before serving stdio (and before any Tooling API work such as
 * auto-connect): returns the original stdout for the transport and redirects
 * subsequent `System.out` writes to the process's real stderr (fd 2), so
 * downloader noise stays visible in logs without touching the protocol.
 */
internal fun captureProtocolStdout(): PrintStream {
    val protocolOut = System.out
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), /* autoFlush = */ true))
    return protocolOut
}
