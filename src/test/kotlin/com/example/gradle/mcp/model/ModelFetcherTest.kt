package com.example.gradle.mcp.model

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.ProjectConnection
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class ModelFetcherTest {
    @Test
    fun `fetchModel uses getModel when prepareTasks is empty`() {
        val calls = mutableListOf<String>()
        val connection = projectConnectionProxy(
            directValue = "direct-model",
            preparedValue = "prepared-model",
            calls = calls,
        )

        val result = connection.fetchModel(String::class.java, emptyList())

        result shouldBe "direct-model"
        calls shouldContainExactly listOf("getModel:java.lang.String")
    }

    @Test
    fun `fetchModel uses modelBuilder forTasks when prepareTasks is not empty`() {
        val calls = mutableListOf<String>()
        val connection = projectConnectionProxy(
            directValue = "direct-model",
            preparedValue = "prepared-model",
            calls = calls,
        )

        val result = connection.fetchModel(String::class.java, listOf("help", ":test"))

        result shouldBe "prepared-model"
        calls shouldContainExactly listOf(
            "model:java.lang.String",
            "forTasks:help,:test",
            "get",
        )
    }
}

@Suppress("UNCHECKED_CAST")
private fun projectConnectionProxy(
    directValue: String,
    preparedValue: String,
    calls: MutableList<String>,
): ProjectConnection {
    lateinit var builderProxy: ModelBuilder<String>
    builderProxy = Proxy.newProxyInstance(
        ModelBuilder::class.java.classLoader,
        arrayOf(ModelBuilder::class.java),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "forTasks" -> {
                    val tasks = (args?.getOrNull(0) as? Array<*>)?.filterIsInstance<String>().orEmpty()
                    calls += "forTasks:${tasks.joinToString(",")}"
                    builderProxy
                }
                "get" -> {
                    calls += "get"
                    preparedValue
                }
                else -> defaultProxyValue(method.returnType, builderProxy)
            }
        },
    ) as ModelBuilder<String>

    return Proxy.newProxyInstance(
        ProjectConnection::class.java.classLoader,
        arrayOf(ProjectConnection::class.java),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "getModel" -> {
                    val modelType = args?.get(0) as Class<*>
                    calls += "getModel:${modelType.name}"
                    directValue
                }
                "model" -> {
                    val modelType = args?.get(0) as Class<*>
                    calls += "model:${modelType.name}"
                    builderProxy
                }
                else -> defaultProxyValue(method.returnType, null)
            }
        },
    ) as ProjectConnection
}

private fun defaultProxyValue(returnType: Class<*>, self: Any?): Any? =
    when {
        self != null && returnType.isInstance(self) -> self
        returnType == Boolean::class.javaPrimitiveType -> false
        returnType == Int::class.javaPrimitiveType -> 0
        returnType == Long::class.javaPrimitiveType -> 0L
        returnType == Float::class.javaPrimitiveType -> 0f
        returnType == Double::class.javaPrimitiveType -> 0.0
        returnType == Short::class.javaPrimitiveType -> 0.toShort()
        returnType == Byte::class.javaPrimitiveType -> 0.toByte()
        returnType == Char::class.javaPrimitiveType -> 0.toChar()
        else -> null
    }
