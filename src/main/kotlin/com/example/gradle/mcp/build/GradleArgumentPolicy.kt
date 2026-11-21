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
        jvmArguments.forEach { arg ->
            if (isUserHomeSystemProperty(systemPropertyName(arg))) {
                throw userHomeJvmArgumentException(arg)
            }
            if (isMcpControlJvmArgument(arg)) {
                throw mcpControlJvmArgumentException(arg)
            }
        }
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

    private fun systemPropertyName(arg: String): String? {
        if (!arg.startsWith("-D")) {
            return null
        }
        return arg.removePrefix("-D").removePrefix("=").substringBefore('=')
    }

    private fun isUserHomeSystemProperty(name: String?): Boolean = name == GRADLE_USER_HOME_PROPERTY

    private fun isMcpControlSystemPropertyName(name: String): Boolean {
        if (!name.startsWith(GRADLE_PROJECT_SYSTEM_PROPERTY_PREFIX, ignoreCase = true)) {
            return false
        }
        val projectPropertyName = name.removePrefix(GRADLE_PROJECT_SYSTEM_PROPERTY_PREFIX)
        return isMcpControlPropertyName(projectPropertyName)
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
