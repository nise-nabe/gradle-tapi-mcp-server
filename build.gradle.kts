plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.example"
version = "0.13.2"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

val resolutionModelEmbed = configurations.create("resolutionModelEmbed") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(libs.mcp.kotlin.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gradle.tooling.api)
    implementation(libs.kotlin.logging)
    implementation(project(":mcp-toolkit"))
    implementation(project(":dependency-sources-mcp"))
    implementation(project(":resolution-model"))
    runtimeOnly(libs.slf4j.simple)
    resolutionModelEmbed(project(path = ":resolution-model", configuration = "runtimeElements"))
}

tasks.processResources {
    from(resolutionModelEmbed) {
        into("META-INF/mcp")
        rename { "resolution-model.jar" }
    }
}

application {
    mainClass = "com.example.gradle.mcp.GradleTapiMcpServerLauncher"
}

testing {
    suites {
        @Suppress("UnstableApiUsage")
        getByName<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit.get())
            dependencies {
                implementation(platform(libs.kotest.bom))
                implementation(libs.kotest.assertions.core)
            }
        }
    }
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.example.gradle.mcp.GradleTapiMcpServerLauncher",
            "Implementation-Version" to project.version,
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

tasks.withType<Test>().configureEach {
    // Forked test JVMs inherit the environment of the process that launched the
    // Gradle daemon. When this build runs under the MCP server (or any shell with
    // GRADLE_PROJECT_DIR set), the variable leaks into tests and
    // ProjectDirectoryResolver.workspaceFromEnvironment() resolves the host
    // workspace instead of @TempDir fixtures. An empty value reads as unset
    // (the resolver rejects blank values), without enumerating the parent
    // environment, which is not allowed under isolated projects.
    environment("GRADLE_PROJECT_DIR", "")
}

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("com.example.gradle.mcp.GradleTapiMcpServerLauncherSmokeTest")
    }
}

val launcherSmokeTest = tasks.register<Test>("launcherSmokeTest") {
    description = "Runs the fat-jar launcher smoke test against the assembled jar."
    group = "verification"
    val testSuite = testing.suites.getByName<JvmTestSuite>("test")
    testClassesDirs = testSuite.sources.output.classesDirs
    classpath = files(testSuite.sources.runtimeClasspath)
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.example.gradle.mcp.GradleTapiMcpServerLauncherSmokeTest")
    }
    dependsOn(tasks.jar)
}

tasks.check {
    dependsOn(launcherSmokeTest)
}

