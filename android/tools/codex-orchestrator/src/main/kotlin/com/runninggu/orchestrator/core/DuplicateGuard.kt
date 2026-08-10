package com.runninggu.orchestrator.core

import com.runninggu.orchestrator.model.PlannedTask
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class DuplicateGuard(private val path: Path) {
    private val seen = linkedSetOf<String>()

    init {
        Files.createDirectories(path.parent)
        if (Files.exists(path)) seen += Files.readAllLines(path, StandardCharsets.UTF_8).filter { it.isNotBlank() }
    }

    @Synchronized
    fun register(task: PlannedTask, feedback: String?): Boolean {
        val input = listOf(
            task.id,
            task.goal,
            task.dependencies.joinToString("\u0000"),
            task.readFiles.joinToString("\u0000"),
            task.writableFiles.joinToString("\u0000"),
            task.completionCriteria.joinToString("\u0000"),
            task.difficulty.name,
            feedback.orEmpty(),
        ).joinToString("\u0001")
        val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (!seen.add(hash)) return false
        Files.writeString(
            path,
            hash + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        return true
    }
}
