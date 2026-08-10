package com.runninggu.orchestrator.workspace

import com.runninggu.orchestrator.logging.StructuredLogger
import com.runninggu.orchestrator.model.PlannedTask
import com.runninggu.orchestrator.util.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

data class ApplyResult(
    val taskId: String,
    val applied: Boolean,
    val message: String,
)

class ApplyManager(
    private val root: Path,
    private val logger: StructuredLogger,
    private val processRunner: ProcessRunner = ProcessRunner(),
) {
    fun apply(task: PlannedTask, workspace: WorkspaceHandle): ApplyResult =
        if (workspace.git) applyGit(task, workspace) else applyCopy(task, workspace)

    private fun applyGit(task: PlannedTask, workspace: WorkspaceHandle): ApplyResult {
        val patch = processRunner.run(
            listOf("git", "diff", "--binary", "${workspace.baseRevision}..HEAD"),
            workspace.path,
            Duration.ofMinutes(2),
        ).requireSuccess("create patch").stdout
        if (patch.isBlank()) return ApplyResult(task.id, true, "No changes to apply")
        val check = processRunner.run(
            listOf("git", "apply", "--check", "--whitespace=nowarn", "-"),
            root,
            Duration.ofMinutes(2),
            stdin = patch,
        )
        if (check.exitCode != 0) {
            val message = "충돌 가능성 때문에 사용자 작업공간을 변경하지 않았습니다: ${check.stderr.ifBlank { check.stdout }}"
            logger.error("apply_conflict", message, mapOf("taskId" to task.id))
            return ApplyResult(task.id, false, message)
        }
        val apply = processRunner.run(
            listOf("git", "apply", "--whitespace=nowarn", "-"),
            root,
            Duration.ofMinutes(2),
            stdin = patch,
        )
        if (apply.exitCode != 0) {
            val message = "검사 후 패치 적용이 실패했습니다: ${apply.stderr.ifBlank { apply.stdout }}"
            logger.error("apply_failed", message, mapOf("taskId" to task.id))
            return ApplyResult(task.id, false, message)
        }
        logger.info("task_applied", "검수 통과 태스크 ${task.id} 패치를 적용했습니다.")
        return ApplyResult(task.id, true, "Applied patch from ${workspace.branch}")
    }

    private fun applyCopy(task: PlannedTask, workspace: WorkspaceHandle): ApplyResult {
        for ((relative, baseline) in workspace.baselineHashes) {
            val current = hashOrNull(root.resolve(relative))
            if (current != baseline) {
                val message = "사용자 파일이 격리 작업 시작 후 변경되어 적용하지 않았습니다: $relative"
                logger.error("apply_conflict", message, mapOf("taskId" to task.id))
                return ApplyResult(task.id, false, message)
            }
        }
        for (relative in workspace.baselineHashes.keys) {
            val source = workspace.path.resolve(relative)
            if (!Files.isRegularFile(source)) {
                return ApplyResult(task.id, false, "Non-git deletion is not auto-applied: $relative")
            }
            val target = root.resolve(relative)
            Files.createDirectories(target.parent)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
        return ApplyResult(task.id, true, "Applied isolated workspace files")
    }

    private fun hashOrNull(path: Path): String? {
        if (!Files.isRegularFile(path)) return null
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
    }
}
