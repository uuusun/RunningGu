package com.runninggu.orchestrator.core

import com.runninggu.orchestrator.model.PlannedTask
import com.runninggu.orchestrator.model.TestResult
import com.runninggu.orchestrator.model.WorkerResult
import com.runninggu.orchestrator.model.WorkerStatus
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

class DagScheduler(private val workers: Int) {
    init {
        require(workers in 1..4) { "workers must be between 1 and 4" }
    }

    fun execute(
        tasks: List<PlannedTask>,
        initialResults: List<WorkerResult> = emptyList(),
        onResult: (WorkerResult) -> Unit = {},
        action: (PlannedTask) -> WorkerResult,
    ): List<WorkerResult> {
        val executor = Executors.newFixedThreadPool(workers)
        val completion = ExecutorCompletionService<Pair<PlannedTask, WorkerResult>>(executor)
        val reusable = initialResults.filter { it.status == WorkerStatus.SUCCESS && it.criteriaMet }.associateBy { it.taskId }
        val pending = tasks.filterNot { it.id in reusable }.associateBy { it.id }.toMutableMap()
        val running = linkedMapOf<String, PlannedTask>()
        val results = linkedMapOf<String, WorkerResult>().apply { putAll(reusable) }
        try {
            while (pending.isNotEmpty() || running.isNotEmpty()) {
                val blocked = pending.values.filter { task ->
                    task.dependencies.any { dependency ->
                        dependency in results && results[dependency]?.let { it.status != WorkerStatus.SUCCESS || !it.criteriaMet } == true
                    }
                }
                blocked.forEach { task ->
                    pending.remove(task.id)
                    val result = WorkerResult(
                        taskId = task.id,
                        status = WorkerStatus.BLOCKED,
                        summary = "선행 태스크 실패로 실행하지 않았습니다.",
                        changedFiles = emptyList(),
                        tests = listOf(TestResult("not run", "blocked by dependency")),
                        criteriaMet = false,
                        risks = listOf("dependency failure"),
                        failureReason = "Failed dependency",
                    )
                    results[task.id] = result
                    onResult(result)
                }

                var launched: Boolean
                do {
                    launched = false
                    if (running.size >= workers) break
                    val candidate = pending.values.firstOrNull { task ->
                        task.dependencies.all { results[it]?.let { result -> result.status == WorkerStatus.SUCCESS && result.criteriaMet } == true } &&
                            running.values.none { overlaps(task, it) }
                    }
                    if (candidate != null) {
                        pending.remove(candidate.id)
                        running[candidate.id] = candidate
                        completion.submit(Callable {
                            val result = runCatching { action(candidate) }.getOrElse { error ->
                                WorkerResult(
                                    taskId = candidate.id,
                                    status = WorkerStatus.FAILED,
                                    summary = "워커 실행 예외",
                                    changedFiles = emptyList(),
                                    tests = emptyList(),
                                    criteriaMet = false,
                                    risks = listOf("worker exception"),
                                    failureReason = error.message ?: error::class.java.name,
                                )
                            }
                            candidate to result
                        })
                        launched = true
                    }
                } while (launched)

                if (running.isNotEmpty()) {
                    val (task, result) = completion.take().get()
                    running.remove(task.id)
                    results[task.id] = result
                    onResult(result)
                } else if (pending.isNotEmpty()) {
                    // A failure can block several dependency levels. Let the next loop
                    // cascade the newly-created BLOCKED result before diagnosing a cycle.
                    if (blocked.isNotEmpty()) continue
                    error("DAG scheduler made no progress; plan may contain a dependency cycle or unknown dependency")
                }
            }
        } finally {
            executor.shutdownNow()
        }
        return tasks.map { results.getValue(it.id) }
    }

    private fun overlaps(left: PlannedTask, right: PlannedTask): Boolean {
        val leftRules = left.writableFiles.map(::normalize)
        val rightRules = right.writableFiles.map(::normalize)
        return leftRules.any { a -> rightRules.any { b -> pathsOverlap(a, b) } }
    }

    private fun pathsOverlap(left: String, right: String): Boolean {
        val a = left.removeSuffix("/**").removeSuffix("/")
        val b = right.removeSuffix("/**").removeSuffix("/")
        if ('*' in a || '?' in a || '*' in b || '?' in b) return left == right
        return a == b || a.startsWith("$b/") || b.startsWith("$a/")
    }

    private fun normalize(path: String): String = path.replace('\\', '/').removePrefix("./")
}
