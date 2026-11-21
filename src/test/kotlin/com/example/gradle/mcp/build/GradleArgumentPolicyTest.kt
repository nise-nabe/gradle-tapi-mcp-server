package com.example.gradle.mcp.build

import com.example.gradle.mcp.protocol.McpErrorCode
import com.example.gradle.mcp.protocol.McpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class GradleArgumentPolicyTest {
    @Test
    fun `rejects init script flag forms`() {
        val cases = listOf(
            listOf("--init-script", "evil.gradle"),
            listOf("--init-script=evil.gradle"),
            listOf("-I", "evil.gradle"),
            listOf("-Ievil.gradle"),
            listOf("-Init.gradle"),
        )
        for (arguments in cases) {
            val exception = shouldThrow<McpException> {
                GradleArgumentPolicy.requireNoInitScript(arguments)
            }
            exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }
    }

    @Test
    fun `rejects gradle argument files`() {
        val exception = shouldThrow<McpException> {
            GradleArgumentPolicy.requireNoInitScript(listOf("@args.gradle"))
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `rejects combined mcp control project properties`() {
        val cases = listOf(
            "-Pmcp.launcherMetadata=/tmp/evil.json",
            "-Pmcp.ccInitScript=/tmp/evil.gradle",
            "-Pmcp.recordDir=/tmp",
            "-Pproject.mcp.launcherMetadata=/tmp/evil.json",
        )
        for (arguments in cases) {
            val exception = shouldThrow<McpException> {
                GradleArgumentPolicy.requireNoMcpControlArguments(listOf(arguments))
            }
            exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }
    }

    @Test
    fun `rejects separated mcp control project properties`() {
        val cases = listOf(
            listOf("-P", "mcp.recordDir=/tmp"),
            listOf("-P", "project.mcp.launcherMetadata=/tmp/evil.json"),
            listOf("--project-prop", "mcp.ccInitScript=/tmp/evil.gradle"),
            listOf("--project-prop=mcp.recordDir=/tmp"),
        )
        for (arguments in cases) {
            val exception = shouldThrow<McpException> {
                GradleArgumentPolicy.requireNoMcpControlArguments(arguments)
            }
            exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }
    }

    @Test
    fun `rejects mcp control properties as system properties in arguments`() {
        val cases = listOf(
            "-Dorg.gradle.project.mcp.recordDir=/tmp",
            "-Dorg.gradle.project.mcp.ccInitScript=/tmp/evil.gradle",
            "-Dorg.gradle.project.project.mcp.launcherMetadata=/tmp/evil.json",
            "--system-prop=org.gradle.project.mcp.recordDir=/tmp",
            "-D=org.gradle.project.mcp.recordDir=/tmp",
            "-DORG.GRADLE.PROJECT.mcp.recordDir=/tmp",
            "-Dorg.gradle.project.MCP.recordDir=/tmp",
        )
        for (argument in cases) {
            val exception = shouldThrow<McpException> {
                GradleArgumentPolicy.requireNoMcpControlArguments(listOf(argument))
            }
            exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }
    }

    @Test
    fun `rejects mcp control properties in jvmArguments`() {
        val cases = listOf(
            "-Dorg.gradle.project.mcp.recordDir=/tmp",
            "-Dorg.gradle.project.mcp.ccInitScript=/tmp/evil.gradle",
            "-Dorg.gradle.project.project.mcp.launcherMetadata=/tmp/evil.json",
            "-DORG.GRADLE.PROJECT.mcp.recordDir=/tmp",
        )
        for (argument in cases) {
            val exception = shouldThrow<McpException> {
                GradleArgumentPolicy.requireNoMcpControlJvmArguments(listOf(argument))
            }
            exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        }
    }

    @Test
    fun `validateUserBuildArguments rejects jvmArguments bypass`() {
        val exception = shouldThrow<McpException> {
            GradleArgumentPolicy.validateUserBuildArguments(
                arguments = emptyList(),
                jvmArguments = listOf("-Dorg.gradle.project.mcp.recordDir=/tmp"),
            )
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `validateUserBuildArguments rejects arguments system property bypass`() {
        val exception = shouldThrow<McpException> {
            GradleArgumentPolicy.validateUserBuildArguments(
                arguments = listOf("-Dorg.gradle.project.mcp.recordDir=/tmp"),
                jvmArguments = emptyList(),
            )
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
    }

    @Test
    fun `allows regular gradle arguments`() {
        GradleArgumentPolicy.validateUserBuildArguments(
            arguments = listOf("--info", "-Dorg.gradle.parallel=true", "-Pfoo=bar", "-Parallel"),
            jvmArguments = listOf("-Xmx1g", "-Dorg.gradle.parallel=true"),
        )
    }

    @Test
    fun `rejects project directory retarget forms`() {
        assertRejected(listOf("--project-dir", "/tmp/other"), "--project-dir")
        assertRejected(listOf("--project-dir=/tmp/other"), "--project-dir=/tmp/other")
        assertRejected(listOf("-p", "/tmp/other"), "-p")
        assertRejected(listOf("-p/tmp/other"), "-p/tmp/other")
        assertRejected(listOf("-p=/tmp/other"), "-p=/tmp/other")
        assertRejected(listOf("--p", "/tmp/other"), "--p")
        assertRejected(listOf("--p=/tmp/other"), "--p=/tmp/other")
        assertRejected(listOf("-project-dir", "/tmp/other"), "-project-dir")
        assertRejected(listOf("-project-dir=/tmp/other"), "-project-dir=/tmp/other")
        assertRejected(listOf("--info", "-p", "/tmp/other"), "-p")
    }

    @Test
    fun `rejects gradle user home retarget forms`() {
        assertRejected(listOf("--gradle-user-home", "/tmp/home"), "--gradle-user-home")
        assertRejected(listOf("--gradle-user-home=/tmp/home"), "--gradle-user-home=/tmp/home")
        assertRejected(listOf("-g", "/tmp/home"), "-g")
        assertRejected(listOf("-g/tmp/home"), "-g/tmp/home")
        assertRejected(listOf("-g=/tmp/home"), "-g=/tmp/home")
        assertRejected(listOf("--g", "/tmp/home"), "--g")
        assertRejected(listOf("--g=/tmp/home"), "--g=/tmp/home")
        assertRejected(listOf("-gradle-user-home", "/tmp/home"), "-gradle-user-home")
        assertRejected(listOf("-gradle-user-home=/tmp/home"), "-gradle-user-home=/tmp/home")
    }

    @Test
    fun `rejects build file and settings file retarget forms`() {
        assertRejected(listOf("--build-file", "other.gradle"), "--build-file")
        assertRejected(listOf("--build-file=other.gradle"), "--build-file=other.gradle")
        assertRejected(listOf("-b", "other.gradle"), "-b")
        assertRejected(listOf("-bother.gradle"), "-bother.gradle")
        assertRejected(listOf("-b=other.gradle"), "-b=other.gradle")
        assertRejected(listOf("--b", "other.gradle"), "--b")
        assertRejected(listOf("--b=other.gradle"), "--b=other.gradle")
        assertRejected(listOf("-build-file", "other.gradle"), "-build-file")
        assertRejected(listOf("-build-file=other.gradle"), "-build-file=other.gradle")
        assertRejected(listOf("--settings-file", "other.settings.gradle"), "--settings-file")
        assertRejected(listOf("--settings-file=other.settings.gradle"), "--settings-file=other.settings.gradle")
        assertRejected(listOf("-c", "other.settings.gradle"), "-c")
        assertRejected(listOf("-cother.settings.gradle"), "-cother.settings.gradle")
        assertRejected(listOf("-c=other.settings.gradle"), "-c=other.settings.gradle")
        assertRejected(listOf("--c", "other.settings.gradle"), "--c")
        assertRejected(listOf("--c=other.settings.gradle"), "--c=other.settings.gradle")
        assertRejected(listOf("-settings-file", "other.settings.gradle"), "-settings-file")
        assertRejected(listOf("-settings-file=other.settings.gradle"), "-settings-file=other.settings.gradle")
    }

    @Test
    fun `rejects include build forms`() {
        assertRejected(listOf("--include-build", "../other"), "--include-build")
        assertRejected(listOf("--include-build=../other"), "--include-build=../other")
        assertRejected(listOf("-include-build", "../other"), "-include-build")
        assertRejected(listOf("-include-build=../other"), "-include-build=../other")
        assertRejected(listOf("--include-build", "../a", "--include-build", "../b"), "--include-build")
    }

    @Test
    fun `rejects gradle user home system property forms`() {
        assertRejected(listOf("-Dgradle.user.home=/tmp/home"), "-Dgradle.user.home=/tmp/home")
        assertRejected(listOf("-Dgradle.user.home"), "-Dgradle.user.home")
        assertRejected(listOf("-D=gradle.user.home=/tmp/home"), "-D=gradle.user.home=/tmp/home")
        assertRejected(listOf("--system-prop=gradle.user.home=/tmp/home"), "--system-prop=gradle.user.home=/tmp/home")
        assertRejected(listOf("--system-prop", "gradle.user.home=/tmp/home"), "--system-prop gradle.user.home=/tmp/home")
        assertRejected(listOf("--D=gradle.user.home=/tmp/home"), "--D=gradle.user.home=/tmp/home")
        assertRejected(listOf("--D", "gradle.user.home=/tmp/home"), "--D gradle.user.home=/tmp/home")
        assertRejected(listOf("-system-prop", "gradle.user.home=/tmp/home"), "-system-prop gradle.user.home=/tmp/home")
        val exception = shouldThrow<McpException> {
            GradleArgumentPolicy.validateUserBuildArguments(
                arguments = emptyList(),
                jvmArguments = listOf("-Dgradle.user.home=/tmp/home"),
            )
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        exception.message shouldContain "-Dgradle.user.home=/tmp/home"
        exception.message shouldContain "jvmArguments"
    }

    @Test
    fun `allows arguments that only resemble retargeting options`() {
        GradleArgumentPolicy.validateUserBuildArguments(
            arguments = listOf(
                "--info",
                "-i",
                "-s",
                "-q",
                "-m",
                "--dry-run",
                "--parallel",
                "-parallel",
                "--build-cache",
                "-build-cache",
                "--no-build-cache",
                "--continue",
                "--configuration-cache",
                "--console=plain",
                "--console",
                "plain",
                "-console",
                "plain",
                "-console=plain",
                "-project-cache-dir=.gradle",
                "--project-cache-dir",
                ".gradle",
                "-project-cache-dir",
                ".gradle",
                "--project",
                "--project-d",
                "--project-dirfoo",
                "--gradle",
                "--gradle-user",
                "--build",
                "--include",
                "--i",
                "-Pfoo=bar",
                "-Parallel",
                "-Pgradle.user.home=/tmp/home",
                "-Dorg.gradle.parallel=true",
                "-Dorg.gradle.user.home=/tmp/home",
                "-Dgradle.user.home.backup=/tmp",
                "-x",
                "compileJava",
                "-xcompileJava",
                "--exclude-task",
                "compileJava",
                "--max-workers",
                "4",
                "--warning-mode=summary",
                "--tests",
                "com.example.Foo",
                "build",
                "check",
                "--",
                "-p",
                "-g/tmp/home",
            ),
            jvmArguments = listOf(
                "-Xmx1g",
                "-Dorg.gradle.parallel=true",
                "-Dorg.gradle.user.home=/tmp/home",
            ),
        )
    }

    @Test
    fun `rejects retargeting options passed as prepareTasks`() {
        val exception = shouldThrow<McpException> {
            GradleArgumentPolicy.validatePrepareTasks(listOf(":app:compileJava", "--gradle-user-home", "/tmp/home"))
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        exception.message shouldContain "--gradle-user-home"
        GradleArgumentPolicy.validatePrepareTasks(listOf("", ":app:compileJava", ":app:compileJava")) shouldBe
            listOf(":app:compileJava")
    }

    @Test
    fun `allows documented daemon jvm arguments`() {
        GradleArgumentPolicy.validateUserBuildArguments(
            arguments = emptyList(),
            jvmArguments = listOf(
                "-Xmx1g",
                "-Xms256m",
                "-Xss512k",
                "-Xmn128m",
                "-Xmx1024",
                "-Xmx1G",
                "-Xmx1t",
                "-XX:MaxMetaspaceSize=256m",
                "-XX:MetaspaceSize=128m",
                "-XX:MaxDirectMemorySize=64m",
                "-XX:MaxHeapSize=1g",
                "-XX:InitialHeapSize=256m",
                "-XX:MinHeapSize=128m",
                "-XX:ThreadStackSize=512",
                "-XX:NewSize=64m",
                "-XX:MaxNewSize=256m",
                "-XX:CompressedClassSpaceSize=128m",
                "-XX:ReservedCodeCacheSize=64m",
                "-XX:InitialCodeCacheSize=16m",
                "-XX:+HeapDumpOnOutOfMemoryError",
                "-XX:-HeapDumpOnOutOfMemoryError",
                "-XX:+ExitOnOutOfMemoryError",
                "-XX:-ExitOnOutOfMemoryError",
                "-XX:+CrashOnOutOfMemoryError",
                "-XX:-CrashOnOutOfMemoryError",
                "-Dfile.encoding=UTF-8",
                "-Dorg.gradle.parallel=true",
                "-Dorg.gradle.user.home=/tmp/home",
                "-Djava.awt.headless=true",
                "-Duser.timezone=UTC",
                "-Duser.language=en",
                "-Dempty.flag",
                "-Dnote=see-javaagent-docs",
                "-Dpath=/opt/agentlib/lib.so",
            ),
        )
    }

    @Test
    fun `rejects agent classpath and module options in jvmArguments`() {
        val cases = listOf(
            "-javaagent:/tmp/evil.jar",
            "-javaagent",
            "-Javaagent:/tmp/evil.jar",
            "-agentpath:/tmp/libagent.so",
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005",
            "-Xbootclasspath/a:/tmp/evil.jar",
            "-Xbootclasspath/p:/tmp/evil.jar",
            "-Xbootclasspath:/tmp/evil.jar",
            "-Xrunjdwp:transport=dt_socket",
            "--patch-module=java.base=/tmp/evil",
            "--patch-module",
            " -javaagent:/tmp/evil.jar",
            "-javaagent:/tmp/evil.jar\n",
            "\t-javaagent:/tmp/evil.jar",
        )
        for (argument in cases) {
            assertJvmRejected(argument)
        }
    }

    @Test
    fun `rejects code-loading and command XX options in jvmArguments`() {
        val cases = listOf(
            "-XX:+EnableJVMCI",
            "-XX:+UseJVMCICompiler",
            "-XX:CompileCommandFile=/tmp/commands.txt",
            "-XX:CompileCommand=exclude,java/lang/String.indexOf",
            "-XX:Flags=/tmp/flags",
            "-XX:VMOptionsFile=/tmp/options",
            "-XX:OnError=/tmp/pwn.sh",
            "-XX:OnOutOfMemoryError=curl attacker",
            "-XX:+EnableDynamicAgentLoading",
            "-XX:+StartAttachListener",
            "-XX:+UseG1GC",
            "-XX:HeapDumpPath=/tmp/dump.hprof",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-exports=java.base/java.lang=ALL-UNNAMED",
            "--module-path=/tmp/evil",
            "--class-path=/tmp/evil.jar",
            "-classpath:/tmp/evil.jar",
            "-cp",
            "/tmp/evil.jar",
            "-XX:SharedArchiveFile=/tmp/evil.jsa",
            "-XX:+UnlockExperimentalVMOptions",
            "--enable-preview",
            "-Xmx",
            "-Xmx1.5g",
            "-XX:MaxMetaspaceSize",
            "-XX:MaxMetaspaceSize=",
            "-XX:+MaxMetaspaceSize=1g",
            "",
        )
        for (argument in cases) {
            assertJvmRejected(argument)
        }
    }

    @Test
    fun `rejects jvm argument files`() {
        for (argument in listOf("@/tmp/jvm.args", "@args.txt", " @/tmp/jvm.args")) {
            assertJvmRejected(argument)
        }
    }

    @Test
    fun `rejects jvm system properties that load code or redirect the daemon`() {
        val cases = listOf(
            "-Djava.system.class.loader=com.evil.Loader",
            "-Djava.security.manager=allow",
            "-Djava.security.policy=/tmp/java.policy",
            "-Djava.library.path=/tmp/native",
            "-DJava.Library.Path=/tmp/native",
            "-Djdk.attach.allowAttachSelf=true",
            "-Dcom.sun.management.jmxremote=true",
            "-Dcom.sun.management.jmxremote.port=9999",
            "-Dcom.sun.jndi.ldap.object.trustURLCodebase=true",
            "-Dorg.gradle.java.home=/tmp/jdk",
            "-Dorg.gradle.java.installations.paths=/tmp/jdk",
            "-Dorg.gradle.jvmargs=-Xmx1g",
            "-Dkotlin.daemon.jvmargs=-Xmx1g",
            "-Dkotlin.daemon.jvm.options=-Xmx1g",
            "-DJAVA_TOOL_OPTIONS=-Xmx1g",
            "-D_JAVA_OPTIONS=-Xmx1g",
            "-DJDK_JAVA_OPTIONS=-Xmx1g",
            "-DIBM_JAVA_OPTIONS=-Xmx1g",
            "-Djava_tool_options=-Xmx1g",
            "-D=",
            "-D",
            "-D=file.encoding=UTF-8",
        )
        for (argument in cases) {
            assertJvmRejected(argument)
        }
    }

    @Test
    fun `rejects jvm system property values that embed agent options`() {
        assertJvmRejected(
            "-Dfoo=-javaagent:/tmp/evil.jar",
            "-javaagent:/tmp/evil.jar",
        )
        assertJvmRejected(
            "-Dfoo=-Xmx1g -javaagent:/tmp/evil.jar",
            "-javaagent:/tmp/evil.jar",
        )
        assertJvmRejected(
            "-Dfoo=-Xmx1g,-javaagent:/tmp/evil.jar",
            "-javaagent:/tmp/evil.jar",
        )
        assertJvmRejected(
            "-Dfoo=\"-javaagent:/tmp/evil.jar\"",
            "-javaagent:/tmp/evil.jar",
        )
        assertJvmRejected(
            "-Dfoo='-agentlib:jdwp'",
            "-agentlib:jdwp",
        )
        assertJvmRejected(
            "-Dfoo=@/tmp/jvm.args",
            "@/tmp/jvm.args",
        )
        assertJvmRejected(
            "-Dfoo=-XX:+EnableJVMCI",
            "-XX:+EnableJVMCI",
        )
        assertJvmRejected(
            "-Dfoo=-XX:VMOptionsFile=/tmp/options",
            "-XX:VMOptionsFile=/tmp/options",
        )
        assertJvmRejected(
            "-Dfoo=-XX:Flags=/tmp/flags",
            "-XX:Flags=/tmp/flags",
        )
        assertJvmRejected(
            "-Dfoo=-XX:CompileCommandFile=/tmp/commands.txt",
            "-XX:CompileCommandFile=/tmp/commands.txt",
        )
        assertJvmRejected(
            "-Dfoo=--patch-module=java.base=/tmp/evil",
            "--patch-module=java.base=/tmp/evil",
        )
        assertJvmRejected(
            "-Dfoo=-Xbootclasspath/a:/tmp/evil.jar",
            "-Xbootclasspath/a:/tmp/evil.jar",
        )
        assertJvmRejected(
            "-Dfoo=-Xrunjdwp:transport=dt_socket",
            "-Xrunjdwp:transport=dt_socket",
        )
        assertJvmRejected(
            "-DJAVA_TOOL_OPTIONS=-javaagent:/tmp/evil.jar",
            "-javaagent:/tmp/evil.jar",
        )
        assertJvmRejected(
            "-Dgradle.user.home=-javaagent:/tmp/evil.jar",
        )
        val userHome = shouldThrow<McpException> {
            GradleArgumentPolicy.validateUserBuildArguments(
                arguments = emptyList(),
                jvmArguments = listOf("-D=gradle.user.home=/tmp/home"),
            )
        }
        userHome.code shouldBe McpErrorCode.INVALID_ARGUMENT
        userHome.message shouldContain "(-D=gradle.user.home=/tmp/home)"
        userHome.message shouldContain "jvmArguments"
    }

    private fun assertJvmRejected(argument: String, vararg extraSnippets: String) {
        val exception = shouldThrow<McpException> {
            GradleArgumentPolicy.validateUserBuildArguments(emptyList(), listOf("-Xmx1g", argument))
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        exception.message shouldContain "($argument)"
        exception.message shouldContain "jvmArguments"
        for (snippet in extraSnippets) {
            exception.message shouldContain snippet
        }
    }

    private fun assertRejected(arguments: List<String>, rejectedToken: String) {
        val exception = shouldThrow<McpException> {
            GradleArgumentPolicy.validateUserBuildArguments(arguments, emptyList())
        }
        exception.code shouldBe McpErrorCode.INVALID_ARGUMENT
        exception.message shouldContain "($rejectedToken)"
    }
}
