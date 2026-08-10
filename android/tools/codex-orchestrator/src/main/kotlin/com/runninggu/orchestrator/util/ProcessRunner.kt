package com.runninggu.orchestrator.util

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    fun requireSuccess(description: String): ProcessResult {
        check(exitCode == 0) { "$description failed ($exitCode): ${stderr.ifBlank { stdout }}" }
        return this
    }
}

class ProcessRunner {
    fun run(
        command: List<String>,
        cwd: Path,
        timeout: Duration = Duration.ofMinutes(2),
        stdin: String? = null,
        environment: Map<String, String> = emptyMap(),
    ): ProcessResult {
        val process = ProcessBuilder(command)
            .directory(cwd.toFile())
            .apply { environment().putAll(environment) }
            .start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = thread(name = "process-stdout", isDaemon = true) {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { stdout.appendLine(it) }
            }
        }
        val errThread = thread(name = "process-stderr", isDaemon = true) {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { stderr.appendLine(it) }
            }
        }
        process.outputStream.use { output ->
            if (stdin != null) {
                output.write(stdin.toByteArray(StandardCharsets.UTF_8))
            }
        }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("Command timed out after $timeout: ${command.joinToString(" ")}")
        }
        outThread.join(5_000)
        errThread.join(5_000)
        return ProcessResult(process.exitValue(), stdout.toString().trimEnd(), stderr.toString().trimEnd())
    }
}
