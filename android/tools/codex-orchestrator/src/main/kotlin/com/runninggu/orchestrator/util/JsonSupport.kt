package com.runninggu.orchestrator.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

val OrchestratorJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

fun JsonElement.asObject(): JsonObject = this as? JsonObject
    ?: error("Expected JSON object, got $this")

fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to jsonValue(item) })
    is Iterable<*> -> JsonArray(value.map(::jsonValue))
    else -> JsonPrimitive(value.toString())
}
