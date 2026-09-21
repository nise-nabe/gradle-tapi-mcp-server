package com.example.gradle.mcp.server

/**
 * Command-line options for the MCP server process.
 *
 * stdout is reserved for MCP JSON-RPC when serving on stdio, so the launcher
 * reports parse errors on stderr and only prints usage to stdout for --help.
 */
enum class McpTransport {
    STDIO,
    STREAMABLE_HTTP,
}

data class HttpEndpoint(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val path: String = "/mcp",
    val allowedHosts: List<String>? = null,
    val allowedOrigins: List<String>? = null,
)

data class ServerCliOptions(
    val transport: McpTransport = McpTransport.STDIO,
    val http: HttpEndpoint = HttpEndpoint(),
    val showHelp: Boolean = false,
) {
    companion object {
        val USAGE: String = """
            Usage: java -jar gradle-tapi-mcp-server-<version>.jar [options]

              --transport=<stdio|streamable-http>  MCP transport to serve (default: stdio).
                                                   "http" is accepted as an alias of streamable-http.
              --host=<host>                        bind host for streamable-http (default: 127.0.0.1)
              --port=<port>                        bind port for streamable-http (default: 8080)
              --path=<path>                        endpoint path for streamable-http (default: /mcp)
              --allowed-hosts=<h1,h2,...>          Host header values allowed by DNS rebinding protection
              --allowed-origins=<o1,o2,...>        Origin header values allowed by DNS rebinding protection
              -h, --help                           print this help and exit
        """.trimIndent()

        fun parse(args: Array<String>): ServerCliOptions {
            var transport = McpTransport.STDIO
            var host = HttpEndpoint().host
            var port = HttpEndpoint().port
            var path = HttpEndpoint().path
            var allowedHosts: List<String>? = null
            var allowedOrigins: List<String>? = null
            var showHelp = false

            for (arg in args) {
                val option = arg.substringBefore('=')
                val value = arg.substringAfter('=', "")
                when (option) {
                    "-h", "--help" -> showHelp = true
                    "--transport" -> transport = parseTransport(value.requireValue(option))
                    "--host" -> host = value.requireValue(option)
                    "--port" -> port = parsePort(value.requireValue(option))
                    "--path" -> path = parsePath(value.requireValue(option))
                    "--allowed-hosts" -> allowedHosts = value.requireValue(option).toNameList(option)
                    "--allowed-origins" -> allowedOrigins = value.requireValue(option).toNameList(option)
                    else -> throw IllegalArgumentException("Unknown option: $arg")
                }
            }

            return ServerCliOptions(
                transport = transport,
                http = HttpEndpoint(host, port, path, allowedHosts, allowedOrigins),
                showHelp = showHelp,
            )
        }

        private fun parseTransport(value: String): McpTransport = when (value) {
            "stdio" -> McpTransport.STDIO
            "http", "streamable-http" -> McpTransport.STREAMABLE_HTTP
            else -> throw IllegalArgumentException(
                "Unknown transport: $value (expected stdio or streamable-http)",
            )
        }

        private fun parsePort(value: String): Int {
            val port = value.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid port: $value")
            require(port in 1..65535) { "Port out of range: $port" }
            return port
        }

        private fun parsePath(value: String): String {
            require(value.startsWith("/")) { "--path must start with '/': $value" }
            return value
        }

        private fun String.requireValue(option: String): String =
            ifEmpty { throw IllegalArgumentException("$option requires a value: $option=<value>") }

        private fun String.toNameList(option: String): List<String> =
            split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .also { require(it.isNotEmpty()) { "$option requires at least one entry" } }
    }
}
