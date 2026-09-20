package com.example.gradle.mcp.support

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Dynamic proxy whose fluent methods can return the proxy itself.
 * [handler] receives the proxy as `self`; return `self` to chain calls.
 */
internal fun selfReturningProxy(
    interfaceClass: Class<*>,
    handler: (self: Any, method: Method, args: Array<out Any?>?) -> Any?,
): Any {
    val self = arrayOfNulls<Any>(1)
    self[0] = Proxy.newProxyInstance(
        interfaceClass.classLoader,
        arrayOf(interfaceClass),
        InvocationHandler { _, method, args -> handler(requireNotNull(self[0]), method, args) },
    )
    return requireNotNull(self[0])
}

internal fun proxyIdentity(proxy: Any, methodName: String, args: Array<out Any?>?): Any? =
    when (methodName) {
        "equals" -> args?.getOrNull(0) === proxy
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "${proxy.javaClass.simpleName}@${System.identityHashCode(proxy)}"
        else -> null
    }

internal fun defaultProxyReturn(method: Method): Any? =
    when (method.returnType) {
        java.lang.Void.TYPE -> null
        java.lang.Boolean.TYPE, Boolean::class.javaObjectType -> false
        java.lang.Integer.TYPE, Int::class.javaObjectType -> 0
        java.lang.Long.TYPE, Long::class.javaObjectType -> 0L
        java.lang.Short.TYPE, Short::class.javaObjectType -> 0.toShort()
        java.lang.Byte.TYPE, Byte::class.javaObjectType -> 0.toByte()
        java.lang.Character.TYPE, Char::class.javaObjectType -> '\u0000'
        java.lang.Float.TYPE, Float::class.javaObjectType -> 0f
        java.lang.Double.TYPE, Double::class.javaObjectType -> 0.0
        else -> null
    }
