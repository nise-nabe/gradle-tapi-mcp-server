package com.example.gradle.mcp.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal val mcpJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal object DynamicMapSerializer : KSerializer<Map<String, Any?>> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Map<String, Any?>) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("DynamicMapSerializer only supports JSON")
        jsonEncoder.encodeJsonElement(value.toJsonElement())
    }

    override fun deserialize(decoder: Decoder): Map<String, Any?> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("DynamicMapSerializer only supports JSON")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonObject -> element.mapValues { (_, value) -> value.toKotlinValue() }
            else -> emptyMap()
        }
    }
}

internal fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is Long -> JsonPrimitive(this)
        is Double -> JsonPrimitive(this)
        is Float -> JsonPrimitive(this.toDouble())
        is Number -> JsonPrimitive(this.toDouble())
        is String -> JsonPrimitive(this)
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            (this as Map<String, Any?>).toJsonObject()
        }
        is List<*> -> JsonArray(map { element -> element.toJsonElement() })
        else -> JsonPrimitive(toString())
    }

internal fun Map<String, Any?>.toJsonObject(): JsonObject =
    JsonObject(mapValues { (_, value) -> value.toJsonElement() })

internal fun JsonObject?.toArgumentMap(): Map<String, Any> {
    if (this == null) {
        return emptyMap()
    }
    @Suppress("UNCHECKED_CAST")
    return mapValues { (_, value) -> value.toKotlinValue() } as Map<String, Any>
}

internal fun JsonElement.toKotlinValue(): Any? =
    when (this) {
        is JsonNull -> null
        is JsonObject -> mapValues { (_, value) -> value.toKotlinValue() }
        is JsonArray -> map { element -> element.toKotlinValue() }
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> content
        }
    }

internal fun encodeMcpJsonDynamic(value: Any?): String =
    mcpJson.encodeToString(JsonElement.serializer(), value.toJsonElement())

internal inline fun <reified T> encodeMcpJson(value: T): String =
    mcpJson.encodeToString(value)

internal inline fun <reified T> decodeMcpJson(text: String): T =
    mcpJson.decodeFromString(text)

internal fun decodeMcpJsonMap(text: String): Map<String, Any?> {
    val element = mcpJson.parseToJsonElement(text)
    return when (element) {
        is JsonObject -> element.mapValues { (_, value) -> value.toKotlinValue() }
        else -> emptyMap()
    }
}
