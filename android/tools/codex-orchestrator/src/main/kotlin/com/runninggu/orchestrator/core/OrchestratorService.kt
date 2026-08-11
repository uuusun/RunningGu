package com.runninggu.orchestrator.core

import com.runninggu.orchestrator.logging.StructuredLogger
import com.runninggu.orchestrator.model.ExecutionPlan
import com.runninggu.orchestrator.model.PersistentRunState
import com.runninggu.orchestrator.model.ReviewResult
import com.runninggu.orchestrator.model.WorkerResult
import com.runninggu.orchestrator.model.WorkerStatus
import com.runninggu.orchestrator.rpc.AppServerClient
import com.runninggu.orchestrator.util.OrchestratorJson
import com.runninggu.orchestrator.workspace.ApplyManager
import com.runninggu.orchestrator.workspace.WorkspaceHandle
import com.runninggu.orchestrator.workspace.WorkspaceManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class RunOptions(
    val goal: String,
    val cwd: Path,
    val workers: Int,
    val apply: Boolean,
    val planState: Path? = null,
)

data class RunOutcome(
    val success: Boolean,
    val runId: String,
    val stateFile: Path,
    val results: List<WorkerResult>,
    val review: ReviewResult?,
)

class OrchestratorService(
    private val client: AppServerClient,
    private val logger: StructuredLogger,
    private val codexExecutable: String = "codex",
) {
    fun run(options: RunOptions, runId: String = newRunId()): RunOutcome {
        require(options.workers in 1..4) { "--workers must be between 1 and 4" }
        val stateDir = options.cwd.resolve(".codex-orchestrator").resolve("state").resolve(runId)
        Files.createDirectories(stateDir)
        val stateFile = stateDir.resolve("run-state.json")
        val resumeCommand = buildResumeCommand(options, stateFile)
        var state = PersistentRunState(
            runId = runId,
            goal = options.goal,
            cwd = options.cwd.toString(),
            workers = options.workers,
            apply = options.apply,
            phase = "PREFLIGHT",
            resumeCommand = resumeCommand,
        )
        persist(stateFile, state)
        var plan: ExecutionPlan? = null
        var results: List<WorkerResult> = emptyList()
        var review: ReviewResult? = null
        var reusedState: PersistentRunState? = null
        var usageBefore: JsonElement? = null
        return try {
            val report = Preflight(client, logger, codexExecutable = codexExecutable)
                .inspect(options.cwd, requirePro = true, requireModels = true)
            usageBefore = report.rateLimits
            state = state.copy(
                phase = "PLANNING",
                models = report.models,
                account = report.account,
                usageBefore = usageBefore,
            )
            persist(stateFile, state)
            val solModel = report.models.first { it.id == "gpt-5.6-sol" }
            val lunaModel = report.models.first { it.id == "gpt-5.6-luna" }
            val sol = SolAgent(client, options.cwd, solModel, logger)
            if (sol.effort != "max") {
                logger.warn(
                    "sol_effort_fallback",
                    "gpt-5.6-sol이 max 추론을 제공하지 않아 지원되는 ${sol.effort} 단계를 사용합니다.",
                    mapOf("supported" to solModel.supportedReasoningEfforts),
                )
            }
            plan = options.planState?.let { planState ->
                val previous = OrchestratorJson.decodeFromString<PersistentRunState>(
                    Files.readString(planState.toAbsolutePath().normalize(), StandardCharsets.UTF_8),
                )
                reusedState = previous
                val reused = previous.plan ?: error("--plan-state does not contain a Sol plan: $planState")
                SolAgent.validatePlan(reused, options.cwd, options.goal)
                logger.info(
                    "plan_reused",
                    "A previously validated Sol plan was reused without another planning turn.",
                    mapOf("sourceState" to planState.toString(), "taskCount" to reused.tasks.size),
                )
                reused
            } ?: sol.plan(options.goal)
            state = state.copy(phase = "EXECUTING", plan = plan)
            persist(stateFile, state)

            val workspaceManager = WorkspaceManager(options.cwd, runId, logger)
            val workspaces = ConcurrentHashMap<String, WorkspaceHandle>()
            val duplicateGuard = DuplicateGuard(stateDir.resolve("attempt-hashes.txt"))
            val worker = LunaWorker(client, lunaModel, workspaceManager, logger)
            val attempts = ConcurrentHashMap<String, Int>()
            val feedback = ConcurrentHashMap<String, String?>()
            reusedState?.review?.decisions?.filter { !it.accepted }?.forEach { decision ->
                feedback[decision.taskId] = decision.feedback
            }
            val liveResults = ConcurrentHashMap<String, WorkerResult>()
            val resumedResults = restoreSuccessfulResults(
                reusedState,
                plan,
                options.cwd,
                workspaceManager,
                workspaces,
            )
            resumedResults.forEach { liveResults[it.taskId] = it }
            val resumedAttempts = restoreUnsuccessfulWorkspaces(
                reusedState,
                plan,
                options.cwd,
                workspaceManager,
                workspaces,
            )
            resumedAttempts.forEach { (taskId, attempt) -> attempts[taskId] = attempt }
            if (resumedResults.isNotEmpty()) {
                state = state.copy(
                    phase = "EXECUTING",
                    results = plan.tasks.mapNotNull { liveResults[it.id] },
                )
                persist(stateFile, state)
            }
            results = DagScheduler(options.workers).execute(
                plan.tasks,
                initialResults = resumedResults,
                onResult = { result ->
                    liveResults[result.taskId] = result
                    state = state.copy(
                        phase = "EXECUTING",
                        results = plan.tasks.mapNotNull { liveResults[it.id] },
                    )
                    persist(stateFile, state)
                },
            ) { task ->
                val repairInstruction = feedback[task.id]
                if (!duplicateGuard.register(task, repairInstruction)) {
                    duplicateFailure(task.id, 0, "Duplicate initial task input was blocked")
                } else {
                    val dependencyWorkspaces = task.dependencies.mapNotNull { workspaces[it] }
                    val previous = workspaces[task.id]
                    val attempt = previous?.let { (attempts[task.id] ?: 0) + 1 } ?: 0
                    if (attempt > MAX_RETRIES) {
                        return@execute retryExhaustedFailure(task.id, attempt)
                    }
                    val workspace = previous?.let {
                        workspaceManager.createRetry(task, dependencyWorkspaces, it, attempt)
                    } ?: workspaceManager.create(task, dependencyWorkspaces)
                    workspaces[task.id] = workspace
                    attempts[task.id] = attempt
                    worker.execute(task, workspace, attempt, repairInstruction)
                }
            }

            review = sol.review(plan, results)
            state = state.copy(phase = "REVIEWING", results = results, review = review)
            persist(stateFile, state)
            var retryRound = 0
            while (review!!.decisions.any { !it.accepted } && retryRound < MAX_RETRIES) {
                retryRound += 1
                val rejected = review!!.decisions.filter { !it.accepted }
                val rejectedIds = rejected.map { it.taskId }.toSet()
                rejected.forEach { feedback[it.taskId] = it.feedback }
                val retryTasks = plan.tasks.filter { it.id in rejectedIds }.map { task ->
                    task.copy(dependencies = task.dependencies.filter { it in rejectedIds })
                }
                val retryResults = DagScheduler(options.workers).execute(
                    retryTasks,
                    onResult = { result ->
                        liveResults[result.taskId] = result
                        state = state.copy(
                            phase = "RETRYING",
                            results = plan.tasks.mapNotNull { liveResults[it.id] },
                            review = review,
                        )
                        persist(stateFile, state)
                    },
                ) { retryTask ->
                    val original = plan.tasks.first { it.id == retryTask.id }
                    val attempt = attempts.compute(original.id) { _, previous -> (previous ?: 0) + 1 } ?: retryRound
                    val instruction = feedback[original.id]
                    if (attempt > MAX_RETRIES) {
                        retryExhaustedFailure(original.id, attempt)
                    } else if (!duplicateGuard.register(original, instruction)) {
                        duplicateFailure(original.id, attempt, "Identical retry input was blocked to prevent an infinite loop")
                    } else {
                        val dependencyWorkspaces = original.dependencies.mapNotNull { workspaces[it] }
                        val workspace = workspaces[original.id]?.let { previous ->
                            workspaceManager.createRetry(original, dependencyWorkspaces, previous, attempt)
                        } ?: workspaceManager.create(original, dependencyWorkspaces)
                        workspaces[original.id] = workspace
                        worker.execute(original, workspace, attempt, instruction)
                    }
                }
                val updated = results.associateBy { it.taskId }.toMutableMap()
                retryResults.forEach { updated[it.taskId] = it }
                results = plan.tasks.map { updated.getValue(it.id) }
                state = state.copy(phase = "REVIEWING", results = results, review = review)
                persist(stateFile, state)
                review = sol.review(plan, results)
                state = state.copy(phase = "REVIEWING", results = results, review = review)
                persist(stateFile, state)
            }

            val accepted = review.decisions.filter { it.accepted }.map { it.taskId }.toSet()
            val applyFailures = mutableListOf<String>()
            if (options.apply) {
                val applyManager = ApplyManager(options.cwd, logger)
                plan.tasks.filter { it.id in accepted }.forEach { task ->
                    val applyResult = applyManager.apply(task, workspaces.getValue(task.id))
                    if (!applyResult.applied) applyFailures += "${task.id}: ${applyResult.message}"
                }
            }
            val usageAfter = client.request("account/rateLimits/read")
            val allAccepted = review.decisions.all { it.accepted }
            val success = allAccepted && applyFailures.isEmpty()
            state = state.copy(
                phase = if (success) "COMPLETED" else "INCOMPLETE",
                results = results,
                review = review,
                usageAfter = usageAfter,
                limitation = applyFailures.takeIf { it.isNotEmpty() }?.joinToString(System.lineSeparator()),
            )
            persist(stateFile, state)
            RunOutcome(success, runId, stateFile, results, review)
        } catch (error: Exception) {
            val rateLimited = error.message?.contains("rate limit", ignoreCase = true) == true ||
                error.message?.contains("usage limit", ignoreCase = true) == true
            val usageAfter = runCatching { client.request("account/rateLimits/read") }.getOrNull()
            state = state.copy(
                phase = if (rateLimited) "RATE_LIMITED" else "FAILED",
                plan = plan,
                results = results,
                review = review,
                usageBefore = usageBefore,
                usageAfter = usageAfter,
                limitation = error.message ?: error::class.java.name,
            )
            persist(stateFile, state)
            logger.error("run_failed", "오케스트레이션 실행이 실패했습니다: ${state.limitation}", mapOf("stateFile" to stateFile.toString()))
            RunOutcome(false, runId, stateFile, results, review)
        }
    }

    private fun persist(path: Path, state: PersistentRunState) {
        Files.writeString(path, OrchestratorJson.encodeToString(state), StandardCharsets.UTF_8)
    }

    private fun restoreSuccessfulResults(
        source: PersistentRunState?,
        plan: ExecutionPlan,
        cwd: Path,
        workspaceManager: WorkspaceManager,
        workspaces: ConcurrentHashMap<String, WorkspaceHandle>,
    ): List<WorkerResult> {
        if (source == null) return emptyList()
        val successful = source.results.filter { it.status == WorkerStatus.SUCCESS && it.criteriaMet }
            .associateBy { it.taskId }
        if (successful.isEmpty()) return emptyList()
        val bases = resumedWorkspaceBases(cwd, source.runId)
        val restored = mutableListOf<WorkerResult>()
        plan.tasks.forEach { task ->
            val result = successful[task.id] ?: return@forEach
            if (task.dependencies.any { !workspaces.containsKey(it) && successful.containsKey(it) }) return@forEach
            val workspacePath = result.workspace?.let(Path::of) ?: return@forEach
            val base = bases[workspacePath.toAbsolutePath().normalize().toString()] ?: return@forEach
            val inherited = task.dependencies.mapNotNull { workspaces[it] }
                .flatMap(workspaceManager::commitsForDescendants)
                .distinct()
            val handle = workspaceManager.restore(task, workspacePath, base, inherited)
            workspaces[task.id] = handle
            restored += result
        }
        logger.info(
            "execution_resumed",
            "이전 실행의 성공 태스크 ${restored.size}개를 재사용합니다.",
            mapOf("sourceRunId" to source.runId, "tasks" to restored.map { it.taskId }),
        )
        return restored
    }

    private fun restoreUnsuccessfulWorkspaces(
        source: PersistentRunState?,
        plan: ExecutionPlan,
        cwd: Path,
        workspaceManager: WorkspaceManager,
        workspaces: ConcurrentHashMap<String, WorkspaceHandle>,
    ): Map<String, Int> {
        if (source == null) return emptyMap()
        val prior = source.results.filter { !it.criteriaMet && it.workspace != null }.associateBy { it.taskId }
        if (prior.isEmpty()) return emptyMap()
        val bases = resumedWorkspaceBases(cwd, source.runId)
        val restoredAttempts = linkedMapOf<String, Int>()
        plan.tasks.forEach { task ->
            if (workspaces.containsKey(task.id)) return@forEach
            val result = prior[task.id] ?: return@forEach
            val workspacePath = result.workspace?.let(Path::of) ?: return@forEach
            val base = bases[workspacePath.toAbsolutePath().normalize().toString()] ?: return@forEach
            val inherited = task.dependencies.mapNotNull { workspaces[it] }
                .flatMap(workspaceManager::commitsForDescendants)
                .distinct()
            val handle = runCatching { workspaceManager.restore(task, workspacePath, base, inherited) }
                .getOrElse { error ->
                    logger.warn(
                        "workspace_resume_skipped",
                        "이전 ${task.id} 작업공간을 복원하지 못해 새 작업공간에서 다시 시작합니다.",
                        mapOf("path" to workspacePath.toString(), "reason" to (error.message ?: error::class.java.name)),
                    )
                    return@forEach
                }
            workspaces[task.id] = handle
            restoredAttempts[task.id] = result.attempt
        }
        if (restoredAttempts.isNotEmpty()) {
            logger.info(
                "failed_workspaces_restored",
                "이전 실행의 미승인 작업 커밋을 새 재시도 작업공간의 입력으로 복원합니다.",
                mapOf("tasks" to restoredAttempts.keys.toList()),
            )
        }
        return restoredAttempts
    }

    private fun resumedWorkspaceBases(cwd: Path, sourceRunId: String): Map<String, String> {
        val logFile = cwd.resolve(".codex-orchestrator").resolve("logs").resolve("$sourceRunId.jsonl")
        if (!Files.isRegularFile(logFile)) return emptyMap()
        return Files.readAllLines(logFile, StandardCharsets.UTF_8).mapNotNull { line ->
            val event = runCatching { OrchestratorJson.parseToJsonElement(line) as JsonObject }.getOrNull()
                ?: return@mapNotNull null
            if (event["event"]?.toString()?.trim('"') != "workspace_created") return@mapNotNull null
            val data = event["data"] as? JsonObject ?: return@mapNotNull null
            val path = data["path"]?.toString()?.trim('"')?.replace("\\\\", "\\") ?: return@mapNotNull null
            val base = data["base"]?.toString()?.trim('"') ?: return@mapNotNull null
            Path.of(path).toAbsolutePath().normalize().toString() to base
        }.toMap()
    }

    private fun retryExhaustedFailure(taskId: String, attempt: Int): WorkerResult = WorkerResult(
        taskId = taskId,
        status = WorkerStatus.FAILED,
        summary = "재시도 한도에 도달해 동일 태스크를 다시 실행하지 않았습니다.",
        changedFiles = emptyList(),
        tests = emptyList(),
        criteriaMet = false,
        risks = listOf("retry limit reached"),
        failureReason = "Task retry limit of $MAX_RETRIES was reached",
        attempt = attempt,
    )

    private fun duplicateFailure(taskId: String, attempt: Int, reason: String) = WorkerResult(
        taskId = taskId,
        status = WorkerStatus.FAILED,
        summary = reason,
        changedFiles = emptyList(),
        tests = emptyList(),
        criteriaMet = false,
        risks = listOf(reason),
        failureReason = reason,
        attempt = attempt,
    )

    private fun buildResumeCommand(options: RunOptions, stateFile: Path): String = buildString {
        append("codex-orchestrate run --goal ")
        append(quote(options.goal))
        append(" --cwd ")
        append(quote(options.cwd.toString()))
        append(" --workers ${options.workers}")
        append(" --plan-state ")
        append(quote(stateFile.toAbsolutePath().normalize().toString()))
        if (options.apply) append(" --apply")
    }

    private fun quote(value: String): String = "\"${value.replace("\"", "\\\"")}\""

    companion object {
        const val MAX_RETRIES = 2
        private val runTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

        fun newRunId(): String = "${runTime.format(Instant.now())}-${UUID.randomUUID().toString().take(8)}"
    }
}
