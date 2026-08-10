package com.runninggu.orchestrator.core

import com.runninggu.orchestrator.logging.StructuredLogger
import com.runninggu.orchestrator.model.ExecutionPlan
import com.runninggu.orchestrator.model.ModelInfo
import com.runninggu.orchestrator.model.ReviewResult
import com.runninggu.orchestrator.model.WorkerResult
import com.runninggu.orchestrator.rpc.AppServerClient
import com.runninggu.orchestrator.util.OrchestratorJson
import com.runninggu.orchestrator.util.asObject
import com.runninggu.orchestrator.util.obj
import com.runninggu.orchestrator.util.string
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

class SolAgent(
    private val client: AppServerClient,
    private val cwd: Path,
    private val modelInfo: ModelInfo,
    private val logger: StructuredLogger,
) {
    private lateinit var threadId: String
    val effort: String = selectSolEffort(modelInfo)

    fun plan(goal: String): ExecutionPlan {
        startThreadIfNeeded()
        val initialPrompt = """
            You are the Sol orchestration planner. Analyze and decompose the requested repository work.
            Return only the required schema. Every task must have a stable ID, explicit dependencies,
            minimal files to read, an exact allowlist of files it may modify, testable completion criteria,
            and LOW/MEDIUM/HIGH difficulty. Keep overlapping writable files out of concurrent tasks.
            Treat every constraint in the Goal as mandatory. Do not downgrade requested priorities,
            exclude a requested scope, or substitute a language, build tool, framework, model, or transport.
            Make mandatory scope and toolchain choices auditable in the plan summary, task goals, writable
            files, and completion criteria.
            Do not implement anything in this planning turn.

            Goal:
            $goal
        """.trimIndent()
        var prompt = initialPrompt
        var lastError: Exception? = null
        repeat(MAX_PLAN_ATTEMPTS) { attempt ->
            val output = runTurn(prompt, Schemas.plan)
            val plan = OrchestratorJson.decodeFromString<ExecutionPlan>(extractJson(output))
            val validation = runCatching { validatePlan(plan, cwd, goal) }
            if (validation.isSuccess) {
                logger.info(
                    "plan_created",
                    "Sol이 ${plan.tasks.size}개 태스크 계획을 생성했습니다.",
                    mapOf("effort" to effort, "attempt" to attempt + 1),
                )
                return plan
            }
            lastError = validation.exceptionOrNull() as? Exception
            if (attempt + 1 < MAX_PLAN_ATTEMPTS) {
                val reason = lastError?.message ?: "unknown plan validation failure"
                logger.warn("plan_repair", "Sol 계획 검증 실패로 교정 turn을 요청합니다: $reason")
                prompt = """
                    Your previous plan was rejected before any worker started.
                    Correct the exact validation failure below while preserving every other mandatory goal constraint.
                    Return the complete corrected plan using the required schema. Do not implement anything.

                    Validation failure: $reason

                    Original goal:
                    $goal
                """.trimIndent()
            }
        }
        throw lastError ?: IllegalStateException("Sol plan validation failed")
    }

    fun review(plan: ExecutionPlan, results: List<WorkerResult>): ReviewResult {
        startThreadIfNeeded()
        val prompt = """
            Review the worker results against the plan and completion criteria. Reject results with failed tests,
            missing evidence, out-of-allowlist changes, unsupported claims, model rerouting, or incomplete criteria.
            Give concrete repair instructions only for rejected tasks. Return exactly one decision for every task ID.

            Plan JSON:
            ${OrchestratorJson.encodeToString(plan)}

            Worker result JSON:
            ${OrchestratorJson.encodeToString(results)}
        """.trimIndent()
        val review = OrchestratorJson.decodeFromString<ReviewResult>(extractJson(runTurn(prompt, Schemas.review)))
        val expected = plan.tasks.map { it.id }.toSet()
        check(review.decisions.map { it.taskId }.toSet() == expected) {
            "Sol review decisions do not match task IDs"
        }
        logger.info(
            "review_complete",
            "Sol 검수가 완료됐습니다.",
            mapOf("accepted" to review.decisions.count { it.accepted }, "rejected" to review.decisions.count { !it.accepted }),
        )
        return review
    }

    private fun startThreadIfNeeded() {
        if (this::threadId.isInitialized) return
        val result = client.request(
            "thread/start",
            buildJsonObject {
                put("model", modelInfo.id)
                put("cwd", cwd.toString())
                put("approvalPolicy", "never")
                put("sandbox", "read-only")
                put("serviceName", "runninggu_codex_orchestrator_sol")
            },
        ).asObject()
        threadId = result.obj("thread")?.string("id") ?: error("thread/start returned no thread id")
    }

    private fun runTurn(prompt: String, schema: JsonObject): String {
        val response = client.request(
            "turn/start",
            buildJsonObject {
                put("threadId", threadId)
                put("input", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", prompt) }) })
                put("cwd", cwd.toString())
                put("model", modelInfo.id)
                put("effort", effort)
                put("approvalPolicy", "never")
                put("sandboxPolicy", buildJsonObject { put("type", "readOnly") })
                put("outputSchema", schema)
            },
        ).asObject()
        val turnId = response.obj("turn")?.string("id") ?: error("turn/start returned no turn id")
        val outcome = client.awaitTurn(turnId)
        check(outcome.reroutedTo == null) { "Sol model was rerouted to ${outcome.reroutedTo}" }
        check(outcome.status == "completed") { "Sol turn failed: ${outcome.error ?: outcome.status}" }
        check(outcome.finalMessage.isNotBlank()) { "Sol returned no final structured output" }
        return outcome.finalMessage
    }

    companion object {
        private val effortOrder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

        fun selectSolEffort(model: ModelInfo): String {
            val supported = model.supportedReasoningEfforts
            if ("max" in supported) return "max"
            return supported.maxByOrNull { effortOrder.indexOf(it).takeIf { index -> index >= 0 } ?: -1 }
                ?: model.defaultReasoningEffort
                ?: error("${model.id} did not report reasoning efforts")
        }

        fun extractJson(raw: String): String {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("```")) return trimmed
            return trimmed.substringAfter('\n').substringBeforeLast("```").trim()
        }

        fun validatePlan(plan: ExecutionPlan, cwd: Path, goal: String? = null) {
            check(plan.tasks.isNotEmpty()) { "Sol returned an empty task plan" }
            val ids = plan.tasks.map { it.id }
            check(ids.size == ids.toSet().size) { "Task IDs must be unique" }
            val idSet = ids.toSet()
            plan.tasks.forEach { task ->
                check(task.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "Unsafe task ID: ${task.id}" }
                check(task.dependencies.all { it in idSet && it != task.id }) { "Invalid dependency in ${task.id}" }
                (task.readFiles + task.writableFiles).forEach { relative ->
                    val path = Path.of(relative)
                    check(!path.isAbsolute && !relative.replace('\\', '/').split('/').contains("..")) {
                        "Task ${task.id} contains unsafe path: $relative"
                    }
                    cwd.resolve(path).normalize()
                }
            }
            detectCycle(plan)
            goal?.let { validateGoalConstraints(plan, it) }
        }

        private fun validateGoalConstraints(plan: ExecutionPlan, goal: String) {
            val normalizedGoal = goal.lowercase()
            val planText = buildString {
                appendLine(plan.summary)
                plan.tasks.forEach { task ->
                    appendLine(task.goal)
                    task.writableFiles.forEach(::appendLine)
                    task.completionCriteria.forEach(::appendLine)
                }
            }.lowercase()

            val requiresGradleKotlinBackend =
                ("gradle kotlin dsl" in normalizedGoal || "gradle kotlin" in normalizedGoal) &&
                    ("backend" in normalizedGoal || "백엔드" in normalizedGoal)
            if (requiresGradleKotlinBackend) {
                check("backend/pom.xml" !in planText && "backend/src/main/java" !in planText && "mvn " !in planText) {
                    "Sol plan substituted Maven/Java for a required Gradle Kotlin DSL backend"
                }
                check(plan.tasks.flatMap { it.writableFiles }.any {
                    it.replace('\\', '/').endsWith("backend/build.gradle.kts")
                }) { "Sol plan omitted backend/build.gradle.kts required by the goal" }
                check(plan.tasks.flatMap { it.writableFiles }.any {
                    it.replace('\\', '/').startsWith("backend/src/main/kotlin/")
                }) { "Sol plan omitted Kotlin backend sources required by the goal" }
            }

            val requiresAllPriorities = listOf("p0", "p1", "p2").all { it in normalizedGoal } &&
                ("모두" in normalizedGoal || "전체" in normalizedGoal || " all " in " $normalizedGoal ")
            if (requiresAllPriorities) {
                check("p2" in planText) { "Sol plan does not make the required P2 scope auditable" }
                val p2Included = Regex(
                    "p2.{0,160}(필수|포함|모두.{0,24}(구현|완성|완료)|all.{0,24}(implement|complete))",
                ).containsMatchIn(planText)
                check(p2Included) {
                    "Sol plan does not explicitly include P2 even though the goal requires P0/P1/P2"
                }
            }
        }

        private fun detectCycle(plan: ExecutionPlan) {
            val byId = plan.tasks.associateBy { it.id }
            val visiting = mutableSetOf<String>()
            val visited = mutableSetOf<String>()
            fun visit(id: String) {
                check(id !in visiting) { "Task dependency cycle contains $id" }
                if (id in visited) return
                visiting += id
                byId.getValue(id).dependencies.forEach(::visit)
                visiting -= id
                visited += id
            }
            plan.tasks.forEach { visit(it.id) }
        }

        private const val MAX_PLAN_ATTEMPTS = 3
    }
}
