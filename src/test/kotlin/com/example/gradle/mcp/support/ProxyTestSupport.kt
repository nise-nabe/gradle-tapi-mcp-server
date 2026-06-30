package com.example.gradle.mcp.support

import java.lang.reflect.Method

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
