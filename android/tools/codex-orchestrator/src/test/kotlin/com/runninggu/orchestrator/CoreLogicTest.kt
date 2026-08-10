package com.runninggu.orchestrator

import com.runninggu.orchestrator.core.DagScheduler
import com.runninggu.orchestrator.core.DuplicateGuard
import com.runninggu.orchestrator.core.LunaWorker
import com.runninggu.orchestrator.core.OrchestratorService
import com.runninggu.orchestrator.core.Preflight
import com.runninggu.orchestrator.core.PreflightException
import com.runninggu.orchestrator.core.SolAgent
import com.runninggu.orchestrator.logging.StructuredLogger
import com.runninggu.orchestrator.model.Difficulty
import com.runninggu.orchestrator.model.ExecutionPlan
import com.runninggu.orchestrator.model.ModelInfo
import com.runninggu.orchestrator.model.PlannedTask
import com.runninggu.orchestrator.model.WorkerResult
import com.runninggu.orchestrator.model.WorkerStatus
import com.runninggu.orchestrator.rpc.AppServerClient
import com.runninggu.orchestrator.rpc.TurnOutcome
import com.runninggu.orchestrator.workspace.ApplyManager
import com.runninggu.orchestrator.workspace.WorkspaceHandle
import com.runninggu.orchestrator.workspace.WorkspaceManager
import com.runninggu.orchestrator.util.OrchestratorJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeoutException

class CoreLogicTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `reasoning effort selection follows Sol max and Luna fastest rules`() {
        val sol = ModelInfo("gpt-5.6-sol", "Sol", false, "low", listOf("low", "high", "max"))
        val luna = ModelInfo("gpt-5.6-luna", "Luna", false, "low", listOf("none", "low", "high"))
        assertEquals("max", SolAgent.selectSolEffort(sol))
        assertEquals("none", LunaWorker.selectLunaEffort(luna, Difficulty.LOW))
        assertEquals("high", LunaWorker.selectLunaEffort(luna, Difficulty.HIGH))
    }

    @Test
    fun `Luna timeout interrupts the turn and returns a bounded failure`() {
        val interrupted = mutableListOf<Pair<String, String>>()
        val fake = object : AppServerClient {
            private var requests = 0
            override fun start() = Unit
            override fun initialize() = Unit
            override fun request(method: String, params: JsonElement): JsonElement {
                requests += 1
                return if (method == "thread/start") {
                    OrchestratorJson.parseToJsonElement("""{"thread":{"id":"thread-1"}}""")
                } else {
                    OrchestratorJson.parseToJsonElement("""{"turn":{"id":"turn-1"}}""")
                }
            }
            override fun notify(method: String, params: JsonElement) = Unit
            override fun awaitTurn(turnId: String, timeout: Duration): TurnOutcome = throw TimeoutException("expected")
            override fun interruptTurn(threadId: String, turnId: String) {
                interrupted += threadId to turnId
            }
            override fun close() = Unit
        }
        val root = temp.resolve("timeout-worker")
        Files.createDirectories(root)
        val logger = StructuredLogger(temp.resolve("timeout-worker.jsonl"), quiet = true)
        val worker = LunaWorker(
            fake,
            ModelInfo("gpt-5.6-luna", "Luna", false, "low", listOf("low")),
            WorkspaceManager(root, "timeout", logger, refreshDevelop = false),
            logger,
            Duration.ofMillis(1),
        )
        val result = worker.execute(
            task("timeout"),
            WorkspaceHandle("timeout", root, git = false, writable = false),
            0,
            null,
        )
        assertEquals(WorkerStatus.FAILED, result.status)
        assertEquals(listOf("thread-1" to "turn-1"), interrupted)
        assertTrue(result.failureReason!!.contains("timed out"))
    }

    @Test
    fun `plan validation rejects dependency cycles and parent traversal`() {
        val a = task("a", dependencies = listOf("b"))
        val b = task("b", dependencies = listOf("a"))
        assertThrows(IllegalStateException::class.java) {
            SolAgent.validatePlan(ExecutionPlan("p", "cycle", listOf(a, b)), temp)
        }
        assertThrows(IllegalStateException::class.java) {
            SolAgent.validatePlan(
                ExecutionPlan("p", "unsafe", listOf(task("a", readFiles = listOf("../secret")))),
                temp,
            )
        }
    }

    @Test
    fun `plan validation enforces explicit backend toolchain and complete priorities`() {
        val wrongBackend = ExecutionPlan(
            "p",
            "P0 and P1 complete; P2 제외",
            listOf(task("backend", writableFiles = listOf("backend/pom.xml", "backend/src/main/java/App.java"))),
        )
        val goal = "백엔드는 Gradle Kotlin DSL과 Kotlin으로 구현하고 P0, P1, P2를 모두 완료"
        val error = assertThrows(IllegalStateException::class.java) {
            SolAgent.validatePlan(wrongBackend, temp, goal)
        }
        assertTrue(error.message!!.contains("Maven/Java") || error.message!!.contains("P2"))

        val valid = ExecutionPlan(
            "p",
            "P0, P1, P2 모두 완료",
            listOf(
                task(
                    "backend",
                    writableFiles = listOf(
                        "backend/build.gradle.kts",
                        "backend/src/main/kotlin/com/runninggu/App.kt",
                    ),
                ),
            ),
        )
        SolAgent.validatePlan(valid, temp, goal)

        val p2Omitted = valid.copy(summary = "P0 and P1 complete; P2 is out of scope")
        val p2Error = assertThrows(IllegalStateException::class.java) {
            SolAgent.validatePlan(p2Omitted, temp, "P0, P1, P2 all complete")
        }
        assertTrue(p2Error.message!!.contains("P2"))
    }

    @Test
    fun `worktree branch names are unique across runs on the same day`() {
        val first = WorkspaceManager.branchName("RG-001-CONTRACT", "20260804-033920-6541dc17")
        val second = WorkspaceManager.branchName("RG-001-CONTRACT", "20260804-041500-aabbccdd")
        assertTrue(first.startsWith("feature/codex-rg-001-contract-"))
        assertFalse(first == second)
    }

    @Test
    fun `duplicate guard persists identical input and permits changed feedback`() {
        val guard = DuplicateGuard(temp.resolve("attempts.txt"))
        val task = task("one")
        assertTrue(guard.register(task, null))
        assertFalse(guard.register(task, null))
        assertTrue(guard.register(task, "specific repair"))
        assertEquals(2, OrchestratorService.MAX_RETRIES)
        val reloaded = DuplicateGuard(temp.resolve("attempts.txt"))
        assertFalse(reloaded.register(task, "specific repair"))
    }

    @Test
    fun `DAG scheduler limits concurrency and serializes overlapping writes`() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val intervals = Collections.synchronizedMap(mutableMapOf<String, Pair<Long, Long>>())
        val tasks = listOf(
            task("a", writableFiles = listOf("shared/file.kt")),
            task("b", writableFiles = listOf("shared/file.kt")),
            task("c", writableFiles = listOf("independent/file.kt")),
        )
        val results = DagScheduler(2).execute(tasks) { task ->
            val start = System.nanoTime()
            maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            Thread.sleep(40)
            active.decrementAndGet()
            intervals[task.id] = start to System.nanoTime()
            success(task.id)
        }
        assertEquals(3, results.size)
        assertTrue(maximum.get() <= 2)
        val a = intervals.getValue("a")
        val b = intervals.getValue("b")
        assertTrue(a.second <= b.first || b.second <= a.first)
    }

    @Test
    fun `DAG scheduler cascades a failed dependency without reporting a false cycle`() {
        val tasks = listOf(
            task("a"),
            task("b", dependencies = listOf("a")),
            task("c", dependencies = listOf("b")),
        )
        val results = DagScheduler(2).execute(tasks) { task ->
            WorkerResult(
                taskId = task.id,
                status = WorkerStatus.FAILED,
                summary = "failed",
                changedFiles = emptyList(),
                tests = emptyList(),
                criteriaMet = false,
                risks = emptyList(),
                failureReason = "expected",
            )
        }
        assertEquals(listOf(WorkerStatus.FAILED, WorkerStatus.BLOCKED, WorkerStatus.BLOCKED), results.map { it.status })
    }

    @Test
    fun `DAG scheduler blocks dependants when a nominal success did not meet criteria`() {
        val tasks = listOf(task("a"), task("b", dependencies = listOf("a")))
        val results = DagScheduler(1).execute(tasks) { current ->
            if (current.id == "a") success("a").copy(criteriaMet = false) else success("b")
        }
        assertEquals(listOf(WorkerStatus.SUCCESS, WorkerStatus.BLOCKED), results.map { it.status })
        assertFalse(results.first().criteriaMet)
    }

    @Test
    fun `DAG scheduler reports every terminal result for durable progress`() {
        val observed = mutableListOf<String>()
        val tasks = listOf(task("a"), task("b", dependencies = listOf("a")))
        DagScheduler(1).execute(tasks, onResult = { observed += "${it.taskId}:${it.status}" }) { task ->
            if (task.id == "a") {
                WorkerResult(
                    task.id,
                    WorkerStatus.FAILED,
                    "failed",
                    emptyList(),
                    emptyList(),
                    false,
                    emptyList(),
                )
            } else {
                success(task.id)
            }
        }
        assertEquals(listOf("a:FAILED", "b:BLOCKED"), observed)
    }

    @Test
    fun `DAG scheduler reuses successful durable results and continues dependants`() {
        val executed = mutableListOf<String>()
        val tasks = listOf(task("a"), task("b", dependencies = listOf("a")))
        val results = DagScheduler(1).execute(tasks, initialResults = listOf(success("a"))) { task ->
            executed += task.id
            success(task.id)
        }
        assertEquals(listOf("b"), executed)
        assertEquals(listOf(WorkerStatus.SUCCESS, WorkerStatus.SUCCESS), results.map { it.status })
    }

    @Test
    fun `git change discovery keeps non ascii paths usable by allowlists`() {
        val root = temp.resolve("unicode-git")
        Files.createDirectories(root.resolve("docs/files"))
        runGit(root, "init", "-b", "develop")
        runGit(root, "config", "user.email", "test@example.com")
        runGit(root, "config", "user.name", "Test")
        val relative = "docs/files/런닝구_API_명세서.md"
        Files.writeString(root.resolve(relative), "before", StandardCharsets.UTF_8)
        runGit(root, "add", relative)
        runGit(root, "commit", "-m", "initial")
        val base = runGit(root, "rev-parse", "HEAD").trim()
        Files.writeString(root.resolve(relative), "after", StandardCharsets.UTF_8)
        runGit(root, "add", relative)
        runGit(root, "commit", "-m", "change")

        val manager = WorkspaceManager(
            root,
            "20260804-000000-test",
            StructuredLogger(temp.resolve("unicode.jsonl"), quiet = true),
            refreshDevelop = false,
        )
        val handle = WorkspaceHandle("unicode", root, git = true, writable = true, baseRevision = base)
        assertEquals(listOf(relative), manager.actualChangedFiles(handle))
        assertTrue(manager.isAllowed(manager.actualChangedFiles(handle).single(), listOf(relative)))
    }

    @Test
    fun `git change discovery ignores isolated Gradle caches`() {
        val root = temp.resolve("gradle-cache-git")
        Files.createDirectories(root)
        runGit(root, "init", "-b", "develop")
        runGit(root, "config", "user.email", "test@example.com")
        runGit(root, "config", "user.name", "Test")
        Files.writeString(root.resolve("tracked.txt"), "before", StandardCharsets.UTF_8)
        runGit(root, "add", "tracked.txt")
        runGit(root, "commit", "-m", "initial")
        val base = runGit(root, "rev-parse", "HEAD").trim()
        Files.createDirectories(root.resolve("backend/.gradle-user/caches"))
        Files.writeString(root.resolve("backend/.gradle-user/caches/cache.bin"), "cache", StandardCharsets.UTF_8)

        val manager = WorkspaceManager(
            root,
            "20260804-000000-test",
            StructuredLogger(temp.resolve("gradle-cache.jsonl"), quiet = true),
            refreshDevelop = false,
        )
        val handle = WorkspaceHandle("cache", root, git = true, writable = true, baseRevision = base)
        assertEquals(emptyList<String>(), manager.actualChangedFiles(handle))
        assertFalse(manager.hasUncommittedChanges(handle))
    }

    @Test
    fun `git worktree receives ignored local Android and Gradle build environment`() {
        val root = temp.resolve("local-build-env")
        Files.createDirectories(root.resolve("android"))
        Files.createDirectories(root.resolve("android/tools/codex-orchestrator/src"))
        runGit(root, "init", "-b", "develop")
        runGit(root, "config", "user.email", "test@example.com")
        runGit(root, "config", "user.name", "Test")
        Files.writeString(root.resolve("tracked.txt"), "base", StandardCharsets.UTF_8)
        runGit(root, "add", "tracked.txt")
        runGit(root, "commit", "-m", "initial")
        Files.writeString(root.resolve("android/local.properties"), "sdk.dir=C\\:\\\\Android\\\\Sdk\n", StandardCharsets.UTF_8)
        Files.writeString(
            root.resolve("android/tools/codex-orchestrator/src/marker.txt"),
            "local orchestrator",
            StandardCharsets.UTF_8,
        )

        val logger = StructuredLogger(temp.resolve("local-build-env.jsonl"), quiet = true)
        val manager = WorkspaceManager(root, "20260804-000000-buildenv", logger, refreshDevelop = false)
        val handle = manager.create(task("env", writableFiles = listOf("tracked.txt")))

        assertTrue(Files.isDirectory(handle.path.resolve(".gradle/tmp")))
        assertTrue(Files.readString(handle.path.resolve(".gradle/gradle.properties")).contains("kotlin.compiler.execution.strategy=in-process"))
        assertEquals(
            Files.readString(root.resolve("android/local.properties")),
            Files.readString(handle.path.resolve("android/local.properties")),
        )
        assertEquals(
            "local orchestrator",
            Files.readString(handle.path.resolve("android/tools/codex-orchestrator/src/marker.txt")),
        )
        assertTrue(runGit(handle.path, "log", "--format=%s", "-2").contains("inject local orchestrator"))
        assertFalse(manager.hasUncommittedChanges(handle))
    }

    @Test
    fun `retry worktree layers prior task commits over the latest dependency commits`() {
        val root = temp.resolve("retry-dependencies")
        Files.createDirectories(root)
        runGit(root, "init", "-b", "develop")
        runGit(root, "config", "user.email", "test@example.com")
        runGit(root, "config", "user.name", "Test")
        Files.writeString(root.resolve("base.txt"), "base", StandardCharsets.UTF_8)
        runGit(root, "add", "base.txt")
        runGit(root, "commit", "-m", "initial")

        val logger = StructuredLogger(temp.resolve("retry-dependencies.jsonl"), quiet = true)
        val manager = WorkspaceManager(root, "20260805-000000-retry", logger, refreshDevelop = false)
        val dependencyTask = task("dep", writableFiles = listOf("dep.txt"))
        val dependency = manager.create(dependencyTask)
        Files.writeString(dependency.path.resolve("dep.txt"), "v1", StandardCharsets.UTF_8)
        runGit(dependency.path, "add", "dep.txt")
        runGit(dependency.path, "commit", "-m", "dep v1")
        val childTask = task("child", dependencies = listOf("dep"), writableFiles = listOf("child.txt"))
        val child = manager.create(childTask, listOf(dependency))
        Files.writeString(child.path.resolve("child.txt"), "child", StandardCharsets.UTF_8)
        runGit(child.path, "add", "child.txt")
        runGit(child.path, "commit", "-m", "child v1")
        Files.writeString(dependency.path.resolve("dep.txt"), "v2", StandardCharsets.UTF_8)
        runGit(dependency.path, "add", "dep.txt")
        runGit(dependency.path, "commit", "-m", "dep v2")

        val retry = manager.createRetry(childTask, listOf(dependency), child, 1)

        assertEquals("v2", Files.readString(retry.path.resolve("dep.txt")))
        assertEquals("child", Files.readString(retry.path.resolve("child.txt")))
        assertEquals(listOf("child.txt"), manager.actualChangedFiles(retry))
    }

    @Test
    fun `preflight rejects API key authentication without reading models`() {
        val fake = object : AppServerClient {
            override fun start() = Unit
            override fun initialize() = Unit
            override fun request(method: String, params: JsonElement): JsonElement {
                assertEquals("account/read", method)
                return OrchestratorJson.parseToJsonElement(
                    """{"account":{"type":"apiKey"},"requiresOpenaiAuth":true}""",
                )
            }
            override fun notify(method: String, params: JsonElement) = Unit
            override fun awaitTurn(turnId: String, timeout: Duration) = error("not used")
            override fun close() = Unit
        }
        val error = assertThrows(PreflightException::class.java) {
            Preflight(fake, StructuredLogger(temp.resolve("preflight.jsonl"), quiet = true), codexExecutable = javaExecutable())
                .inspect(temp, requirePro = true, requireModels = true)
        }
        assertTrue(error.message!!.contains("API 키 인증"))
        assertTrue(error.message!!.contains("chatgptDeviceCode"))
    }

    @Test
    fun `non git apply preserves a user file changed after isolation`() {
        val root = temp.resolve("root")
        val isolated = temp.resolve("isolated")
        Files.createDirectories(root)
        Files.createDirectories(isolated)
        Files.writeString(root.resolve("file.txt"), "original", StandardCharsets.UTF_8)
        Files.writeString(isolated.resolve("file.txt"), "worker", StandardCharsets.UTF_8)
        val baseline = sha256(root.resolve("file.txt"))
        Files.writeString(root.resolve("file.txt"), "user edit", StandardCharsets.UTF_8)
        val result = ApplyManager(root, StructuredLogger(temp.resolve("apply.jsonl"), quiet = true)).apply(
            task("apply", writableFiles = listOf("file.txt")),
            WorkspaceHandle("apply", isolated, git = false, writable = true, baselineHashes = mapOf("file.txt" to baseline)),
        )
        assertFalse(result.applied)
        assertEquals("user edit", Files.readString(root.resolve("file.txt"), StandardCharsets.UTF_8))
    }

    private fun task(
        id: String,
        dependencies: List<String> = emptyList(),
        readFiles: List<String> = emptyList(),
        writableFiles: List<String> = emptyList(),
    ) = PlannedTask(id, "goal", dependencies, readFiles, writableFiles, listOf("done"), Difficulty.LOW)

    private fun success(id: String) = WorkerResult(
        id,
        WorkerStatus.SUCCESS,
        "done",
        emptyList(),
        emptyList(),
        true,
        emptyList(),
    )

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it) }

    private fun runGit(cwd: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(cwd.toFile()).start()
        val stdout = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val stderr = process.errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
        assertEquals(0, process.waitFor(), stderr)
        return stdout
    }
}
