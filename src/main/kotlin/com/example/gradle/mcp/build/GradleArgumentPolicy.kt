package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException

/**
 * Rejects client Gradle arguments that escape the connected project.
 *
 * Matching follows Gradle 9.9.1 `org.gradle.cli.CommandLineParser`: option names are exact,
 * `--` may introduce a short name (`--g`), a single `-` may introduce a long name (`-project-dir`
 * or `-project-dir=/other`), short options attach their value (`-g/tmp/home`, `-g=/tmp/home`),
 * and `--long` takes `=` or a following value. A single-dash token with `=` matches the full
 * name before it is read as an attached short option, so `-console=plain` is not `-c`.
 * Unique prefixes (`--project-d`) are not options.
 * There is no system property for the project directory; the user home property is
 * `gradle.user.home` (`-D` / `--system-prop`), not `org.gradle.user.home`.
 *
 * `jvmArguments` are a separate allowlist. `ConfigurableLauncher.addJvmArguments` appends them to
 * the Gradle daemon JVM, so an option such as `-javaagent` would run code in that process. Only
 * heap and stack sizes, a fixed set of `-XX` memory and out-of-memory switches, and `-D` system
 * properties that do not load code or retarget Gradle are forwarded. The `java` launcher also
 * accepts `@argfiles`, `JAVA_TOOL_OPTIONS`, and agent options as further ways to add arguments.
 */
internal object GradleArgumentPolicy {
    private const val GRADLE_PROJECT_SYSTEM_PROPERTY_PREFIX = "org.gradle.project."
    private const val GRADLE_USER_HOME_PROPERTY = "gradle.user.home"

    fun validateUserBuildArguments(arguments: List<String>, jvmArguments: List<String>) {
        rejectDisallowedGradleArguments(arguments)
        rejectDisallowedJvmArguments(jvmArguments)
    }

    fun validatePrepareTasks(prepareTasks: List<String>): List<String> {
        val tasks = prepareTasks.filter { it.isNotBlank() }.distinct()
        rejectDisallowedGradleArguments(tasks)
        return tasks
    }

    fun requireNoInitScript(arguments: List<String>) {
        rejectDisallowedGradleArguments(arguments)
    }

    fun requireNoMcpControlArguments(arguments: List<String>) {
        rejectDisallowedGradleArguments(arguments)
    }

    fun requireNoMcpControlJvmArguments(jvmArguments: List<String>) {
        rejectDisallowedJvmArguments(jvmArguments)
    }

    internal fun isMcpControlProjectPropToken(token: String): Boolean =
        isMcpControlPropertyName(propertyNameFromProjectPropToken(token))

    internal fun isMcpControlJvmArgument(arg: String): Boolean {
        val propertyName = systemPropertyName(arg) ?: return false
        return isMcpControlSystemPropertyName(propertyName)
    }

    internal fun isMcpControlPropertyName(propertyName: String): Boolean {
        val normalized = propertyName.trim()
        return normalized.startsWith("mcp.", ignoreCase = true) ||
            normalized.startsWith("project.mcp.", ignoreCase = true)
    }

    private fun rejectDisallowedJvmArguments(jvmArguments: List<String>) {
        jvmArguments.forEach(::rejectJvmArgument)
    }

    private fun rejectJvmArgument(arg: String) {
        if (arg.isEmpty() || arg.any { it.isISOControl() }) {
            throw disallowedJvmArgumentException(arg)
        }
        if (arg.trimStart().startsWith("@")) {
            throw jvmArgFileException(arg)
        }
        if (isAllowedDaemonJvmOption(arg)) {
            return
        }
        val assignment = systemPropertyAssignment(arg)
        if (assignment == null) {
            throw disallowedJvmArgumentException(arg)
        }
        if (isUserHomeSystemProperty(assignment.name)) {
            throw userHomeJvmArgumentException(arg)
        }
        if (isMcpControlJvmArgument(arg)) {
            throw mcpControlJvmArgumentException(arg)
        }
        if (assignment.rawBody.startsWith("=") || isDisallowedSystemPropertyName(assignment.name)) {
            throw disallowedJvmArgumentException(arg)
        }
        val embedded = embeddedDisallowedOption(assignment.name) ?: embeddedDisallowedOption(assignment.value)
        if (embedded != null) {
            throw embeddedJvmArgumentException(arg, embedded)
        }
    }

    private fun isAllowedDaemonJvmOption(arg: String): Boolean =
        isAllowedXMemory(arg) || isAllowedXxOption(arg)

    private fun isAllowedXMemory(arg: String): Boolean {
        val flag = X_MEMORY_FLAGS.firstOrNull { arg.startsWith(it) } ?: return false
        return MEMORY_SIZE.matches(arg.removePrefix(flag))
    }

    private fun isAllowedXxOption(arg: String): Boolean {
        if (!arg.startsWith("-XX:")) {
            return false
        }
        val body = arg.removePrefix("-XX:")
        if (body.startsWith("+") || body.startsWith("-")) {
            val name = body.substring(1)
            return name in XX_OOM_FLAGS && !name.contains('=')
        }
        val separator = body.indexOf('=')
        if (separator <= 0) {
            return false
        }
        val name = body.substring(0, separator)
        val size = body.substring(separator + 1)
        return name in XX_SIZE_OPTIONS && MEMORY_SIZE.matches(size)
    }

    private fun isDisallowedSystemPropertyName(name: String): Boolean {
        if (name.isEmpty()) {
            return true
        }
        if (ENV_INDIRECTION_NAMES.any { name.equals(it, ignoreCase = true) }) {
            return true
        }
        if (DISALLOWED_SYSTEM_PROPERTY_NAMES.any { name.equals(it, ignoreCase = true) }) {
            return true
        }
        if (name.startsWith("org.gradle.java.installations.", ignoreCase = true)) {
            return true
        }
        if (name.startsWith("kotlin.daemon.jvm.", ignoreCase = true)) {
            return true
        }
        if (name.startsWith("jvmci.", ignoreCase = true)) {
            return true
        }
        if (name.equals("com.sun.management.jmxremote", ignoreCase = true) ||
            name.startsWith("com.sun.management.jmxremote.", ignoreCase = true)
        ) {
            return true
        }
        return name.contains("trustURLCodebase", ignoreCase = true)
    }

    private fun embeddedDisallowedOption(text: String): String? {
        if (text.isEmpty()) {
            return null
        }
        for (piece in text.split(EMBEDDED_OPTION_SEPARATORS)) {
            val token = stripQuotes(piece.trim())
            if (token.isEmpty()) {
                continue
            }
            if (isDisallowedJvmPayload(token)) {
                return token
            }
            val separator = token.indexOf('=')
            if (separator >= 0) {
                val rhs = stripQuotes(token.substring(separator + 1).trim())
                if (rhs.isNotEmpty() && isDisallowedJvmPayload(rhs)) {
                    return rhs
                }
            }
        }
        return null
    }

    private fun isDisallowedJvmPayload(token: String): Boolean {
        if (token.startsWith("@")) {
            return true
        }
        if (ENV_INDIRECTION_ASSIGNMENT.matches(token)) {
            return true
        }
        if (token.startsWith("-XX:", ignoreCase = true) && token.contains("JVMCI", ignoreCase = true)) {
            return true
        }
        return DISALLOWED_JVM_PAYLOAD_PREFIXES.any { token.startsWith(it, ignoreCase = true) }
    }

    private fun stripQuotes(token: String): String {
        var value = token
        while (value.isNotEmpty()) {
            val wrapped = value.length >= 2 &&
                ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))
            if (wrapped) {
                value = value.substring(1, value.length - 1).trim()
                continue
            }
            if (value.first() == '"' || value.first() == '\'') {
                value = value.substring(1).trim()
                continue
            }
            return value
        }
        return value
    }

    private fun rejectDisallowedGradleArguments(arguments: List<String>) {
        var index = 0
        var optionsEnded = false
        while (index < arguments.size) {
            val arg = arguments[index]
            if (arg.startsWith("@")) {
                throw argsFileException(arg)
            }
            if (optionsEnded) {
                index++
                continue
            }
            if (arg == "--") {
                optionsEnded = true
                index++
                continue
            }
            val matched = matchOption(arg)
            if (matched == null) {
                index++
                continue
            }
            when (matched.option.kind) {
                CliOptionKind.FLAG -> index++
                CliOptionKind.TAKES_VALUE -> index = indexAfterValue(arguments, index, matched.inlineValue)
                CliOptionKind.LAYOUT -> throw layoutArgumentException(arg)
                CliOptionKind.INIT_SCRIPT -> throw initScriptException(arg)
                CliOptionKind.SYSTEM_PROPERTY -> {
                    val expression = propertyExpression(arguments, index, matched.inlineValue)
                    if (expression != null) {
                        rejectSystemPropertyExpression(
                            expression,
                            shownArgument(arg, matched.inlineValue, expression),
                        )
                    }
                    index = indexAfterValue(arguments, index, matched.inlineValue)
                }
                CliOptionKind.PROJECT_PROPERTY -> {
                    val expression = propertyExpression(arguments, index, matched.inlineValue)
                    if (expression != null && isMcpControlProjectPropToken(expression)) {
                        throw mcpControlArgumentException(shownArgument(arg, matched.inlineValue, expression))
                    }
                    index = indexAfterValue(arguments, index, matched.inlineValue)
                }
            }
        }
    }

    private fun propertyExpression(arguments: List<String>, index: Int, inlineValue: String?): String? {
        if (inlineValue != null) {
            return inlineValue
        }
        val next = arguments.getOrNull(index + 1) ?: return null
        if (next.startsWith("@") || (next.startsWith("-") && next != "-")) {
            return null
        }
        return next
    }

    private fun indexAfterValue(arguments: List<String>, index: Int, inlineValue: String?): Int {
        if (inlineValue != null) {
            return index + 1
        }
        val next = arguments.getOrNull(index + 1) ?: return index + 1
        if (next.startsWith("@")) {
            throw argsFileException(next)
        }
        if (next.startsWith("-") && next != "-") {
            return index + 1
        }
        return index + 2
    }

    private fun rejectSystemPropertyExpression(expression: String, shown: String) {
        val name = expression.substringBefore('=')
        if (isUserHomeSystemProperty(name)) {
            throw userHomeArgumentException(shown)
        }
        if (isMcpControlSystemPropertyName(name)) {
            throw mcpControlArgumentException(shown)
        }
    }

    private fun shownArgument(optionToken: String, inlineValue: String?, expression: String): String =
        if (inlineValue != null) optionToken else "$optionToken $expression"

    private fun systemPropertyName(arg: String): String? = systemPropertyAssignment(arg)?.name

    private fun systemPropertyAssignment(arg: String): SystemPropertyAssignment? {
        if (!arg.startsWith("-D")) {
            return null
        }
        val rawBody = arg.removePrefix("-D")
        val body = rawBody.removePrefix("=")
        val separator = body.indexOf('=')
        val name = (if (separator >= 0) body.substring(0, separator) else body).trim()
        val value = if (separator >= 0) body.substring(separator + 1) else ""
        return SystemPropertyAssignment(name, value, rawBody)
    }

    private fun isUserHomeSystemProperty(name: String?): Boolean = name == GRADLE_USER_HOME_PROPERTY

    private fun isMcpControlSystemPropertyName(name: String): Boolean {
        val prefix = GRADLE_PROJECT_SYSTEM_PROPERTY_PREFIX
        if (!name.startsWith(prefix, ignoreCase = true)) {
            return false
        }
        return isMcpControlPropertyName(name.substring(prefix.length))
    }

    private fun propertyNameFromProjectPropToken(token: String): String =
        token.trim().substringBefore('=').trim()

    private fun matchOption(arg: String): MatchedOption? {
        if (!arg.startsWith("-") || arg == "-") {
            return null
        }
        if (arg.startsWith("--")) {
            val body = arg.removePrefix("--")
            if (body.isEmpty()) {
                return null
            }
            val separator = body.indexOf('=')
            val name = if (separator >= 0) body.substring(0, separator) else body
            val inlineValue = if (separator >= 0) body.substring(separator + 1) else null
            val option = optionsByAlias[name] ?: return null
            return MatchedOption(option, inlineValue)
        }
        val body = arg.substring(1)
        val separator = body.indexOf('=')
        if (separator >= 0) {
            val name = body.substring(0, separator)
            optionsByAlias[name]?.let { option ->
                return MatchedOption(option, body.substring(separator + 1))
            }
        }
        optionsByAlias[body]?.let { return MatchedOption(it, null) }
        if (body.length < 2) {
            return null
        }
        val option = optionsByAlias[body.substring(0, 1)] ?: return null
        if (option.kind == CliOptionKind.FLAG) {
            return null
        }
        return MatchedOption(option, body.substring(1))
    }

    private fun initScriptException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Gradle init scripts are not allowed in arguments ($flag). " +
                "The MCP server injects its own init script for build recording.",
        )

    private fun argsFileException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Gradle argument files are not allowed in arguments ($flag). " +
                "They can inject init scripts that conflict with MCP build recording.",
        )

    private fun layoutArgumentException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Gradle arguments that retarget the build are not allowed ($flag). " +
                "Rejected options: -p/--project-dir, -g/--gradle-user-home, -b/--build-file, " +
                "-c/--settings-file, --include-build.",
        )

    private fun userHomeArgumentException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Gradle system property gradle.user.home is not allowed in arguments ($flag). " +
                "It changes the Gradle user home and can load that home's init.d scripts.",
        )

    private fun userHomeJvmArgumentException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Gradle system property gradle.user.home is not allowed in jvmArguments ($flag). " +
                "It changes the Gradle user home and can load that home's init.d scripts.",
        )

    private fun disallowedJvmArgumentException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Daemon JVM arguments that can load code are not allowed in jvmArguments ($flag). " +
                "Allowed: -Xmx/-Xms/-Xss/-Xmn sizes, documented -XX memory sizes " +
                "(including -XX:MaxMetaspaceSize), -XX:+HeapDumpOnOutOfMemoryError and the documented OOM switches, " +
                "and -D system properties that do not load agents or retarget Gradle.",
        )

    private fun embeddedJvmArgumentException(flag: String, embedded: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "Daemon JVM arguments that can load code are not allowed in jvmArguments ($flag). " +
                "The value embeds a disallowed option ($embedded).",
        )

    private fun jvmArgFileException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "JVM argument files are not allowed in jvmArguments ($flag). " +
                "The java launcher reads @argfiles and can inject agent options.",
        )

    private fun mcpControlArgumentException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "MCP-controlled project properties cannot be set in arguments ($flag). " +
                "The server injects persistence metadata and init scripts automatically.",
        )

    private fun mcpControlJvmArgumentException(flag: String): McpException =
        McpException(
            McpErrorCode.INVALID_ARGUMENT,
            "MCP-controlled project properties cannot be set in jvmArguments ($flag). " +
                "The server injects persistence metadata and init scripts automatically.",
        )

    private enum class CliOptionKind {
        FLAG,
        TAKES_VALUE,
        LAYOUT,
        INIT_SCRIPT,
        SYSTEM_PROPERTY,
        PROJECT_PROPERTY,
    }

    private class CliOption(val kind: CliOptionKind)

    private class MatchedOption(val option: CliOption, val inlineValue: String?)

    private class SystemPropertyAssignment(val name: String, val value: String, val rawBody: String)

    private val MEMORY_SIZE = Regex("[0-9]+[kKmMgGtT]?")
    private val X_MEMORY_FLAGS = listOf("-Xmx", "-Xms", "-Xss", "-Xmn")
    private val XX_SIZE_OPTIONS = setOf(
        "MaxMetaspaceSize",
        "MetaspaceSize",
        "MaxDirectMemorySize",
        "MaxHeapSize",
        "InitialHeapSize",
        "MinHeapSize",
        "ThreadStackSize",
        "NewSize",
        "MaxNewSize",
        "CompressedClassSpaceSize",
        "ReservedCodeCacheSize",
        "InitialCodeCacheSize",
    )
    private val XX_OOM_FLAGS = setOf(
        "HeapDumpOnOutOfMemoryError",
        "ExitOnOutOfMemoryError",
        "CrashOnOutOfMemoryError",
    )
    private val ENV_INDIRECTION_NAMES = setOf(
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "JAVA_OPTIONS",
        "IBM_JAVA_OPTIONS",
    )
    private val ENV_INDIRECTION_ASSIGNMENT = Regex(
        "(?i)^(JDK_JAVA_OPTIONS|JAVA_TOOL_OPTIONS|IBM_JAVA_OPTIONS|_JAVA_OPTIONS|JAVA_OPTIONS)(=.*)?$",
    )
    private val EMBEDDED_OPTION_SEPARATORS = Regex("[\\s\\p{Z},;]+")
    private val DISALLOWED_JVM_PAYLOAD_PREFIXES = listOf(
        "-javaagent",
        "-agentpath",
        "-agentlib",
        "-Xbootclasspath",
        "-Xrun",
        "--patch-module",
        "-XX:CompileCommand",
        "-XX:Flags",
        "-XX:VMOptionsFile",
        "-XX:OnError",
        "-XX:OnOutOfMemoryError",
        "-XX:+EnableDynamicAgentLoading",
        "-XX:-EnableDynamicAgentLoading",
        "-XX:+StartAttachListener",
        "-XX:-StartAttachListener",
    )
    private val DISALLOWED_SYSTEM_PROPERTY_NAMES = setOf(
        "java.system.class.loader",
        "java.security.manager",
        "java.security.policy",
        "java.security.properties",
        "java.security.auth.login.config",
        "java.protocol.handler.pkgs",
        "java.rmi.server.codebase",
        "java.ext.dirs",
        "sun.boot.class.path",
        "sun.boot.library.path",
        "java.library.path",
        "jdk.attach.allowAttachSelf",
        "javax.management.builder.initial",
        "org.gradle.jvmargs",
        "org.gradle.java.home",
        "kotlin.daemon.jvmargs",
        "kotlin.daemon.jvm.options",
        "com.sun.management.config.file",
    )

    private val optionsByAlias: Map<String, CliOption> = buildOptions()

    private fun buildOptions(): Map<String, CliOption> {
        val options = linkedMapOf<String, CliOption>()
        fun option(kind: CliOptionKind, vararg aliases: String) {
            val cliOption = CliOption(kind)
            for (alias in aliases) {
                val previous = options.put(alias, cliOption)
                check(previous == null) { "Duplicate Gradle option alias: $alias" }
            }
        }
        option(CliOptionKind.LAYOUT, "project-dir", "p")
        option(CliOptionKind.LAYOUT, "gradle-user-home", "g")
        option(CliOptionKind.LAYOUT, "build-file", "b")
        option(CliOptionKind.LAYOUT, "settings-file", "c")
        option(CliOptionKind.LAYOUT, "include-build")
        option(CliOptionKind.INIT_SCRIPT, "init-script", "I")
        option(CliOptionKind.SYSTEM_PROPERTY, "system-prop", "D")
        option(CliOptionKind.PROJECT_PROPERTY, "project-prop", "P")
        option(CliOptionKind.TAKES_VALUE, "project-cache-dir")
        option(CliOptionKind.TAKES_VALUE, "priority")
        option(CliOptionKind.TAKES_VALUE, "max-workers")
        option(CliOptionKind.TAKES_VALUE, "configuration-cache-problems")
        option(CliOptionKind.TAKES_VALUE, "console")
        option(CliOptionKind.TAKES_VALUE, "console-unicode")
        option(CliOptionKind.TAKES_VALUE, "warning-mode")
        option(CliOptionKind.TAKES_VALUE, "update-locks")
        option(CliOptionKind.TAKES_VALUE, "dependency-verification", "F")
        option(CliOptionKind.TAKES_VALUE, "write-verification-metadata", "M")
        option(CliOptionKind.TAKES_VALUE, "exclude-task", "x")
        option(CliOptionKind.TAKES_VALUE, "develocity-url")
        option(CliOptionKind.TAKES_VALUE, "develocity-plugin-version")
        // Boolean long names whose single-dash form starts with p, g, b, or c.
        // Gradle checks the full name before the short option, so -parallel is --parallel
        // and not -p with value "arallel". --no-* forms start with "n" and need no entry.
        option(CliOptionKind.FLAG, "parallel")
        option(CliOptionKind.FLAG, "profile")
        option(CliOptionKind.FLAG, "problems-report")
        option(CliOptionKind.FLAG, "property-upgrade-report")
        option(CliOptionKind.FLAG, "build-cache")
        option(CliOptionKind.FLAG, "continue")
        option(CliOptionKind.FLAG, "continuous")
        option(CliOptionKind.FLAG, "configure-on-demand")
        option(CliOptionKind.FLAG, "configuration-cache")
        return options
    }
}
