package com.runninggu.orchestrator.logging

import com.runninggu.orchestrator.util.OrchestratorJson
import com.runninggu.orchestrator.util.jsonValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

class StructuredLogger(private val path: Path, private val quiet: Boolean = false) {
    init {
        Files.createDirectories(path.parent)
    }

    @Synchronized
    fun log(
        level: String,
        event: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        val record = JsonObject(
            linkedMapOf(
                "timestamp" to JsonPrimitive(Instant.now().toString()),
                "level" to JsonPrimitive(level),
                "event" to JsonPrimitive(event),
                "message" to JsonPrimitive(message),
                "data" to jsonValue(data),
            ),
        )
        Files.writeString(
            path,
            OrchestratorJson.encodeToString(JsonObject.serializer(), record) + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        if (!quiet) {
            val target = if (level == "ERROR") System.err else System.out
            target.println("[$level] $message")
        }
    }

    fun info(event: String, message: String, data: Map<String, Any?> = emptyMap()) =
        log("INFO", event, message, data)

    fun warn(event: String, message: String, data: Map<String, Any?> = emptyMap()) =
        log("WARN", event, message, data)

    fun error(event: String, message: String, data: Map<String, Any?> = emptyMap()) =
        log("ERROR", event, message, data)
}
