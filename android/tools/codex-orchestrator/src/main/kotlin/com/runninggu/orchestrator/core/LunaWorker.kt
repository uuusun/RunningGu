package com.runninggu.orchestrator.core

import com.runninggu.orchestrator.logging.StructuredLogger
import com.runninggu.orchestrator.model.Difficulty
import com.runninggu.orchestrator.model.ModelInfo
import com.runninggu.orchestrator.model.PlannedTask
import com.runninggu.orchestrator.model.WorkerResult
import com.runninggu.orchestrator.model.WorkerStatus
import com.runninggu.orchestrator.rpc.AppServerClient
import com.runninggu.orchestrator.util.OrchestratorJson
import com.runninggu.orchestrator.util.asObject
import com.runninggu.orchestrator.util.obj
import com.runninggu.orchestrator.util.string
import com.runninggu.orchestrator.workspace.WorkspaceHandle
import com.runninggu.orchestrator.workspace.WorkspaceManager
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.util.concurrent.TimeoutException

class LunaWorker(
    private val client: AppServerClient,
    private val modelInfo: ModelInfo,
    private val workspaceManager: WorkspaceManager,
    private val logger: StructuredLogger,
    private val turnTimeout: Duration = Duration.ofMinutes(30),
) {
    fun execute(
        task: PlannedTask,
        workspace: WorkspaceHandle,
        attempt: Int,
        feedback: String?,
    ): WorkerResult {
        val effort = selectLunaEffort(modelInfo, task.difficulty)
        val threadResult = client.request(
            "thread/start",
            buildJsonObject {
                put("model", modelInfo.id)
                put("cwd", workspace.path.toString())
                put("approvalPolicy", "never")
                put("sandbox", if (workspace.writable) "workspace-write" else "read-only")
                put("serviceName", "runninggu_codex_orchestrator_luna")
            },
        ).asObject()
        val threadId = threadResult.obj("thread")?.string("id") ?: error("Luna thread/start returned no thread id")
        val prompt = buildPrompt(task, workspace, attempt, feedback)
        val turnResponse = client.request(
            "turn/start",
            buildJsonObject {
                put("threadId", threadId)
                put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", prompt) }) })
                put("cwd", workspace.path.toString())
                put("model", modelInfo.id)
                put("effort", effort)
                put("approvalPolicy", "never")
                put(
                    "sandboxPolicy",
                    if (workspace.writable) {
                        buildJsonObject {
                            put("type", "workspaceWrite")
                            put("writableRoots", buildJsonArray { add(JsonPrimitive(workspace.path.toString())) })
                            put("networkAccess", true)
                        }
                    } else {
                        buildJsonObject { put("type", "readOnly") }
                    },
                )
                put("outputSchema", Schemas.workerResult)
            },
        ).asObject()
        val turnId = turnResponse.obj("turn")?.string("id") ?: error("Luna turn/start returned no turn id")
        val outcome = try {
            client.awaitTurn(turnId, turnTimeout)
        } catch (timeout: TimeoutException) {
            runCatching { client.interruptTurn(threadId, turnId) }
                .onFailure { logger.warn("turn_interrupt_failed", "타임아웃된 Luna 턴 중단 요청이 실패했습니다.", mapOf("taskId" to task.id, "turnId" to turnId, "error" to it.message)) }
            return failed(task, attempt, workspace, "Luna turn timed out after $turnTimeout and was interrupted")
        }
        if (outcome.reroutedTo != null) {
            return failed(task, attempt, workspace, "Luna model was rerouted to ${outcome.reroutedTo}")
        }
        if (outcome.status != "completed") {
            return failed(task, attempt, workspace, outcome.error ?: "Luna turn status: ${outcome.status}")
        }
        val parsed = runCatching {
            OrchestratorJson.decodeFromString<WorkerResult>(SolAgent.extractJson(outcome.finalMessage))
        }.getOrElse { error ->
            return failed(task, attempt, workspace, "Invalid worker JSON: ${error.message}")
        }
        if (parsed.taskId != task.id) {
            return failed(task, attempt, workspace, "Worker returned taskId ${parsed.taskId}, expected ${task.id}")
        }
        val consistent = if (parsed.status == WorkerStatus.SUCCESS && !parsed.criteriaMet) {
            parsed.copy(
                status = WorkerStatus.FAILED,
                failureReason = parsed.failureReason
                    ?: "Worker reported SUCCESS while criteriaMet=false; completion criteria are authoritative",
            )
        } else {
            parsed
        }
        val actualChanged = workspaceManager.actualChangedFiles(workspace)
        val outside = actualChanged.filterNot { workspaceManager.isAllowed(it, task.writableFiles) }
        if (outside.isNotEmpty()) {
            return bounded(consistent.copy(
                status = WorkerStatus.FAILED,
                criteriaMet = false,
                changedFiles = actualChanged,
                failureReason = "Changed files outside allowlist: ${outside.take(MAX_CHANGED_FILES)}" +
                    if (outside.size > MAX_CHANGED_FILES) " (${outside.size - MAX_CHANGED_FILES} more)" else "",
                attempt = attempt,
                workspace = workspace.path.toString(),
            ))
        }
        if (workspaceManager.hasUncommittedChanges(workspace)) {
            return bounded(consistent.copy(
                status = WorkerStatus.FAILED,
                criteriaMet = false,
                changedFiles = actualChanged,
                failureReason = "Git worker left uncommitted changes; commit is required for review/apply",
                attempt = attempt,
                workspace = workspace.path.toString(),
            ))
        }
        val head = workspaceManager.headRevision(workspace)
        val result = consistent.copy(
            changedFiles = actualChanged,
            commitOrPatch = head?.takeIf { it != workspace.baseRevision } ?: parsed.commitOrPatch,
            attempt = attempt,
            workspace = workspace.path.toString(),
        )
        logger.info(
            "worker_complete",
            "Luna 워커 ${task.id} 시도 ${attempt + 1}이 ${result.status}로 끝났습니다.",
            mapOf("effort" to effort, "changedFiles" to actualChanged),
        )
        return bounded(result)
    }

    private fun buildPrompt(
        task: PlannedTask,
        workspace: WorkspaceHandle,
        attempt: Int,
        feedback: String?,
    ): String = """
        You are a Luna implementation worker. Work only on the assigned task and return the required result schema.
        Do not ask the user questions. Do not modify anything outside the writable allowlist. Preserve existing changes.
        Read only the listed task files plus repository instruction files automatically loaded by Codex.
        Run the completion-criterion tests. Never claim an unrun test passed.
        On Windows, read and write text explicitly as UTF-8 (for PowerShell use -Encoding UTF8 where applicable).
        For Gradle, set GRADLE_USER_HOME to the existing ignored directory `${workspace.path.resolve(".gradle")}`
        so builds never write to the user profile or leave an untracked cache in the task worktree.
        Create `${workspace.path.resolve(".gradle").resolve("tmp")}` first, then set TEMP and TMP to it before tests that need temporary files.
        Do not create `.gradle-home` or another cache directory outside `.gradle/`.
        The installed JDK 21 satisfies the user's JDK 17-or-newer runtime constraint. Configure JVM bytecode/toolchain
        compatibility requested by the task, but do not download another JDK or fail only because `java -version` is newer than 17.
        On Windows, JAVA_HOME is authoritative. Prepend `${'$'}env:JAVA_HOME\\bin` to PATH before Java/Gradle commands;
        never reject the task because a stale `java.exe` appears later on PATH.
        Run Android Gradle only after changing to `${workspace.path.resolve("android")}` with `.\\gradlew.bat`.
        Run backend Gradle only after changing to `${workspace.path.resolve("backend")}` with `.\\gradlew.bat`.
        There is intentionally no Gradle build or wrapper at the repository root.
        For Android builds, use the inherited ANDROID_HOME/ANDROID_SDK_ROOT environment. Do not commit local.properties.
        Do not let one shell/build command run for more than 20 minutes. Stop it and report the exact timeout instead.
        ${if (workspace.git && workspace.writable) "Commit all task changes using CONVENTION.md Conventional Commit rules before reporting." else ""}

        Task ID: ${task.id}
        Attempt: ${attempt + 1} of 3
        Goal: ${task.goal}
        Read files: ${task.readFiles}
        Writable allowlist: ${task.writableFiles}
        Completion criteria: ${task.completionCriteria}
        ${feedback?.let { "Sol repair instruction: $it" }.orEmpty()}
    """.trimIndent()

    private fun failed(
        task: PlannedTask,
        attempt: Int,
        workspace: WorkspaceHandle,
        reason: String,
    ) = bounded(WorkerResult(
        taskId = task.id,
        status = WorkerStatus.FAILED,
        summary = "Luna worker failed",
        changedFiles = runCatching { workspaceManager.actualChangedFiles(workspace) }.getOrDefault(emptyList()),
        tests = emptyList(),
        criteriaMet = false,
        risks = listOf(reason.take(MAX_TEXT)),
        failureReason = reason.take(MAX_FAILURE),
        attempt = attempt,
        workspace = workspace.path.toString(),
    ))

    private fun bounded(result: WorkerResult): WorkerResult = result.copy(
        summary = result.summary.take(MAX_TEXT),
        changedFiles = result.changedFiles.take(MAX_CHANGED_FILES).map { it.take(MAX_PATH) },
        tests = result.tests.take(MAX_TESTS).map { test ->
            test.copy(command = test.command.take(MAX_COMMAND), outcome = test.outcome.take(MAX_TEST_OUTCOME))
        },
        risks = result.risks.take(MAX_RISKS).map { it.take(MAX_TEXT) },
        commitOrPatch = result.commitOrPatch?.take(MAX_PATH),
        failureReason = result.failureReason?.take(MAX_FAILURE),
    )

    companion object {
        private val effortOrder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        private const val MAX_TEXT = 4_000
        private const val MAX_FAILURE = 8_000
        private const val MAX_PATH = 1_000
        private const val MAX_COMMAND = 2_000
        private const val MAX_TEST_OUTCOME = 8_000
        private const val MAX_CHANGED_FILES = 500
        private const val MAX_TESTS = 100
        private const val MAX_RISKS = 50

        fun selectLunaEffort(model: ModelInfo, difficulty: Difficulty): String {
            val supported = model.supportedReasoningEfforts
            check(supported.isNotEmpty()) { "${model.id} did not report reasoning efforts" }
            if (difficulty == Difficulty.HIGH) {
                return when {
                    "high" in supported -> "high"
                    else -> supported.maxBy { effortOrder.indexOf(it).takeIf { index -> index >= 0 } ?: -1 }
                }
            }
            return supported.minBy { effortOrder.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        }
    }
}
