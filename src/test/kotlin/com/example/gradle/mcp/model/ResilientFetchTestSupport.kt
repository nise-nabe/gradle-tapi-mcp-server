package com.example.gradle.mcp.model

import com.example.gradle.mcp.support.defaultProxyReturn
import com.example.gradle.mcp.support.gradleProjectProxy
import com.example.gradle.mcp.support.problemProxy
import com.example.gradle.mcp.support.proxyIdentity
import org.gradle.tooling.BuildActionExecuter
import org.gradle.tooling.BuildController
import org.gradle.tooling.Failure
import org.gradle.tooling.FetchModelResult
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.IntermediateResultHandler
import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.events.problems.Severity
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.gradle.BuildInvocations
import java.lang.reflect.Proxy

internal fun toolingFailureProxy(
    message: String?,
    description: String? = null,
    causes: List<Failure> = emptyList(),
    problems: List<org.gradle.tooling.events.problems.Problem> = emptyList(),
): Failure =
    Proxy.newProxyInstance(
        Failure::class.java.classLoader,
        arrayOf(Failure::class.java),
    ) { proxy, method, args ->
        proxyIdentity(proxy, method.name, args) ?: when (method.name) {
            "getMessage" -> message
            "getDescription" -> description
            "getCauses" -> causes
            "getProblems" -> problems
            else -> defaultProxyReturn(method)
        }
    } as Failure

internal fun labeledProblem(displayName: String) =
    problemProxy(displayName = displayName, details = null, severity = Severity.ERROR)

internal fun fetchModelResultProxy(model: Any?, failures: List<Failure> = emptyList()): FetchModelResult<*> =
    Proxy.newProxyInstance(
        FetchModelResult::class.java.classLoader,
        arrayOf(FetchModelResult::class.java),
    ) { proxy, method, args ->
        proxyIdentity(proxy, method.name, args) ?: when (method.name) {
            "getModel" -> model
            "getFailures" -> failures
            else -> defaultProxyReturn(method)
        }
    } as FetchModelResult<*>

internal fun buildControllerProxy(
    resultsByType: Map<Class<*>, FetchModelResult<*>>,
    targetedResults: Map<Pair<Any, Class<*>>, FetchModelResult<*>> = emptyMap(),
    fetchCalls: MutableList<Pair<Any?, Class<*>>>? = null,
): BuildController =
    Proxy.newProxyInstance(
        BuildController::class.java.classLoader,
        arrayOf(BuildController::class.java),
    ) { proxy, method, args ->
        proxyIdentity(proxy, method.name, args) ?: when (method.name) {
            "fetch" -> {
                when {
                    args == null || args.isEmpty() -> fetchModelResultProxy(null)
                    args.size == 1 -> {
                        val modelType = args[0] as Class<*>
                        fetchCalls?.add(null to modelType)
                        resultsByType[modelType] ?: fetchModelResultProxy(null)
                    }
                    else -> {
                        val target = args[0]
                        val modelType = args[1] as Class<*>
                        fetchCalls?.add(target to modelType)
                        targetedResults[target to modelType] ?: fetchModelResultProxy(null)
                    }
                }
            }
            else -> defaultProxyReturn(method)
        }
    } as BuildController

internal data class PhasedFetchHarness(
    val connection: ProjectConnection,
    val calls: MutableList<String>,
)

internal fun phasedConnection(
    payload: Any?,
    runException: GradleConnectionException? = null,
    directModels: Map<Class<*>, Any> = emptyMap(),
): PhasedFetchHarness {
    val calls = mutableListOf<String>()
    lateinit var builder: BuildActionExecuter.Builder
    lateinit var executer: BuildActionExecuter<Void>
    var projectsLoadedHandler: IntermediateResultHandler<Any>? = null
    var buildFinishedHandler: IntermediateResultHandler<Any>? = null

    executer = Proxy.newProxyInstance(
        BuildActionExecuter::class.java.classLoader,
        arrayOf(BuildActionExecuter::class.java),
    ) { proxy, method, args ->
        proxyIdentity(proxy, method.name, args) ?: when (method.name) {
            "forTasks" -> {
                val tasks = when (val first = args?.getOrNull(0)) {
                    is Array<*> -> first.filterIsInstance<String>()
                    is Iterable<*> -> first.filterIsInstance<String>()
                    else -> emptyList()
                }
                calls += "forTasks:${tasks.joinToString(",")}"
                executer
            }
            "withDetailedFailure" -> {
                calls += "withDetailedFailure"
                executer
            }
            "run" -> {
                calls += "run"
                val handler = buildFinishedHandler ?: projectsLoadedHandler
                if (payload != null) {
                    handler?.onComplete(payload)
                }
                if (runException != null) {
                    throw runException
                }
                null
            }
            else -> defaultProxyReturn(method) ?: executer
        }
    } as BuildActionExecuter<Void>

    builder = Proxy.newProxyInstance(
        BuildActionExecuter.Builder::class.java.classLoader,
        arrayOf(BuildActionExecuter.Builder::class.java),
    ) { proxy, method, args ->
        proxyIdentity(proxy, method.name, args) ?: when (method.name) {
            "projectsLoaded" -> {
                calls += "projectsLoaded"
                @Suppress("UNCHECKED_CAST")
                projectsLoadedHandler = args?.getOrNull(1) as IntermediateResultHandler<Any>
                builder
            }
            "buildFinished" -> {
                calls += "buildFinished"
                @Suppress("UNCHECKED_CAST")
                buildFinishedHandler = args?.getOrNull(1) as IntermediateResultHandler<Any>
                builder
            }
            "build" -> {
                calls += "build"
                executer
            }
            else -> defaultProxyReturn(method) ?: builder
        }
    } as BuildActionExecuter.Builder

    lateinit var modelBuilder: ModelBuilder<Any>
    modelBuilder = Proxy.newProxyInstance(
        ModelBuilder::class.java.classLoader,
        arrayOf(ModelBuilder::class.java),
    ) { proxy, method, args ->
        proxyIdentity(proxy, method.name, args) ?: when (method.name) {
            "forTasks" -> {
                val tasks = (args?.getOrNull(0) as? Array<*>)?.filterIsInstance<String>().orEmpty()
                calls += "modelForTasks:${tasks.joinToString(",")}"
                modelBuilder
            }
            "get" -> {
                calls += "modelGet"
                directModels.values.firstOrNull()
            }
            else -> defaultProxyReturn(method) ?: modelBuilder
        }
    } as ModelBuilder<Any>

    val connection = Proxy.newProxyInstance(
        ProjectConnection::class.java.classLoader,
        arrayOf(ProjectConnection::class.java),
    ) { proxy, method, args ->
        proxyIdentity(proxy, method.name, args) ?: when (method.name) {
            "action" -> {
                if (args == null || args.isEmpty()) {
                    calls += "action"
                    builder
                } else {
                    calls += "action:single"
                    executer
                }
            }
            "getModel" -> {
                val modelType = args?.get(0) as Class<*>
                calls += "getModel:${modelType.simpleName}"
                directModels[modelType]
            }
            "model" -> {
                val modelType = args?.get(0) as Class<*>
                calls += "model:${modelType.simpleName}"
                modelBuilder
            }
            else -> defaultProxyReturn(method)
        }
    } as ProjectConnection

    return PhasedFetchHarness(connection, calls)
}

internal fun sampleGradleProject(): GradleProject = gradleProjectProxy()

internal fun sampleBuildInvocations(): BuildInvocations =
    Proxy.newProxyInstance(
        BuildInvocations::class.java.classLoader,
        arrayOf(BuildInvocations::class.java),
    ) { proxy, method, args ->
        proxyIdentity(proxy, method.name, args) ?: when (method.name) {
            "getTaskSelectors" -> emptyList<Any>()
            "getTasks" -> emptyList<Any>()
            else -> defaultProxyReturn(method)
        }
    } as BuildInvocations
