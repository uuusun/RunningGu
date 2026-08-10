package com.runninggu.orchestrator.workspace

import com.runninggu.orchestrator.logging.StructuredLogger
import com.runninggu.orchestrator.model.PlannedTask
import com.runninggu.orchestrator.util.ProcessRunner
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale

data class WorkspaceHandle(
    val taskId: String,
    val path: Path,
    val git: Boolean,
    val writable: Boolean,
    val baseRevision: String? = null,
    val branch: String? = null,
    val baselineHashes: Map<String, String?> = emptyMap(),
    val inheritedCommits: List<String> = emptyList(),
)

class WorkspaceManager(
    private val root: Path,
    private val runId: String,
    private val logger: StructuredLogger,
    private val processRunner: ProcessRunner = ProcessRunner(),
    private val refreshDevelop: Boolean = true,
) {
    private val gitRepository = isGitRepository()
    private var developRef: String? = null

    fun create(task: PlannedTask, dependencyWorkspaces: List<WorkspaceHandle> = emptyList()): WorkspaceHandle {
        val writable = task.writableFiles.isNotEmpty()
        if (!writable) return WorkspaceHandle(task.id, root, gitRepository, false)
        return if (gitRepository) createGitWorktree(task, dependencyWorkspaces) else createIsolatedCopy(task)
    }

    fun createRetry(
        task: PlannedTask,
        dependencyWorkspaces: List<WorkspaceHandle>,
        previous: WorkspaceHandle,
        attempt: Int,
    ): WorkspaceHandle {
        if (!gitRepository || !previous.git || !previous.writable) return create(task, dependencyWorkspaces)
        snapshotAllowedRetryChanges(task, previous)
        val ownCommits = processRunner.run(
            listOf("git", "rev-list", "--reverse", "${previous.baseRevision}..HEAD"),
            previous.path,
        ).requireSuccess("list previous ${task.id} commits").stdout.lineSequence().filter { it.isNotBlank() }.toList()
        return createGitWorktree(
            task,
            dependencyWorkspaces,
            suffix = "-retry-${attempt + 1}",
            seedTaskCommits = ownCommits,
        )
    }

    fun actualChangedFiles(handle: WorkspaceHandle): List<String> {
        if (!handle.writable) return emptyList()
        return if (handle.git) {
            val committed = processRunner.run(
                listOf("git", "-c", "core.quotepath=false", "diff", "--name-only", "${handle.baseRevision}..HEAD"),
                handle.path,
            ).requireSuccess("git diff").stdout.lineSequence()
            val working = processRunner.run(
                listOf("git", "-c", "core.quotepath=false", "status", "--porcelain", "--untracked-files=all"),
                handle.path,
            ).requireSuccess("git status").stdout.lineSequence().mapNotNull { line ->
                line.takeIf { it.length > 3 }?.substring(3)
            }
            (committed + working)
                .filter { it.isNotBlank() }
                .map(::normalize)
                .filterNot(::isEphemeralBuildPath)
                .distinct()
                .sorted()
                .toList()
        } else {
            handle.baselineHashes.keys.filter { relative ->
                hashOrNull(handle.path.resolve(relative)) != handle.baselineHashes[relative]
            }.sorted()
        }
    }

    fun hasUncommittedChanges(handle: WorkspaceHandle): Boolean {
        if (!handle.git || !handle.writable) return false
        return pendingChangedFiles(handle).isNotEmpty()
    }

    private fun pendingChangedFiles(handle: WorkspaceHandle): List<String> {
        return processRunner.run(
            listOf("git", "-c", "core.quotepath=false", "status", "--porcelain", "--untracked-files=all"),
            handle.path,
        ).requireSuccess("git status").stdout.lineSequence().mapNotNull { line ->
            line.takeIf { it.length > 3 }?.substring(3)?.let(::normalize)
        }.filterNot(::isEphemeralBuildPath).distinct().toList()
    }

    private fun snapshotAllowedRetryChanges(task: PlannedTask, previous: WorkspaceHandle) {
        val pending = pendingChangedFiles(previous)
        if (pending.isEmpty()) return
        val disallowed = pending.filterNot { isAllowed(it, task.writableFiles) }
        check(disallowed.isEmpty()) {
            "Previous ${task.id} workspace has changes outside its allowlist: ${disallowed.joinToString()}"
        }
        processRunner.run(listOf("git", "add", "--") + pending, previous.path)
            .requireSuccess("stage previous ${task.id} retry changes")
        processRunner.run(
            listOf("git", "commit", "--no-verify", "-m", "chore: snapshot ${task.id} retry state"),
            previous.path,
            Duration.ofMinutes(2),
        ).requireSuccess("snapshot previous ${task.id} retry changes")
    }

    fun headRevision(handle: WorkspaceHandle): String? {
        if (!handle.git || !handle.writable) return null
        return processRunner.run(listOf("git", "rev-parse", "HEAD"), handle.path)
            .requireSuccess("git rev-parse HEAD").stdout.trim()
    }

    fun commitsForDescendants(handle: WorkspaceHandle): List<String> {
        if (!handle.git || !handle.writable) return emptyList()
        val own = processRunner.run(
            listOf("git", "rev-list", "--reverse", "${handle.baseRevision}..HEAD"),
            handle.path,
        ).requireSuccess("list task commits").stdout.lineSequence().filter { it.isNotBlank() }.toList()
        return (handle.inheritedCommits + own).distinct()
    }

    fun restore(
        task: PlannedTask,
        path: Path,
        baseRevision: String,
        inheritedCommits: List<String>,
    ): WorkspaceHandle {
        val normalized = path.toAbsolutePath().normalize()
        val allowedRoot = root.resolve(".codex-orchestrator").resolve("worktrees").toAbsolutePath().normalize()
        check(normalized.startsWith(allowedRoot) && Files.isDirectory(normalized)) {
            "Unsafe or missing resumed worktree for ${task.id}: $normalized"
        }
        val branch = processRunner.run(listOf("git", "branch", "--show-current"), normalized)
            .requireSuccess("read resumed branch").stdout.trim()
        check(branch.isNotBlank()) { "Resumed worktree for ${task.id} is detached" }
        processRunner.run(listOf("git", "rev-parse", "--verify", baseRevision), normalized)
            .requireSuccess("verify resumed base")
        prepareLocalBuildEnvironment(normalized)
        logger.info(
            "workspace_restored",
            "태스크 ${task.id}의 성공 worktree를 재개 상태에서 복원했습니다.",
            mapOf("path" to normalized.toString(), "branch" to branch, "base" to baseRevision),
        )
        return WorkspaceHandle(
            task.id,
            normalized,
            git = true,
            writable = task.writableFiles.isNotEmpty(),
            baseRevision = baseRevision,
            branch = branch,
            inheritedCommits = inheritedCommits.distinct(),
        )
    }

    fun isAllowed(relative: String, allowlist: List<String>): Boolean {
        val normalized = normalize(relative)
        return allowlist.any { entry ->
            val rule = normalize(entry).removePrefix("./")
            when {
                rule.endsWith("/**") -> normalized == rule.removeSuffix("/**") || normalized.startsWith(rule.removeSuffix("**"))
                rule.endsWith("/") -> normalized.startsWith(rule)
                '*' in rule || '?' in rule -> root.fileSystem.getPathMatcher("glob:${rule.replace('/', root.fileSystem.separator.single())}")
                    .matches(Path.of(normalized.replace('/', root.fileSystem.separator.single())))
                else -> normalized == rule || normalized.startsWith("$rule/")
            }
        }
    }

    private fun createGitWorktree(
        task: PlannedTask,
        dependencyWorkspaces: List<WorkspaceHandle>,
        suffix: String = "",
        seedTaskCommits: List<String> = emptyList(),
    ): WorkspaceHandle {
        val base = resolveDevelopRef()
        val baseRevision = processRunner.run(listOf("git", "rev-parse", base), root)
            .requireSuccess("resolve $base").stdout.trim()
        val safeTask = task.id.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(32)
        val branch = branchName(task.id + suffix, runId)
        val worktree = root.resolve(".codex-orchestrator").resolve("worktrees").resolve(runId).resolve(safeTask + suffix)
            .toAbsolutePath().normalize()
        check(worktree.startsWith(root.toAbsolutePath().normalize())) { "Unsafe worktree path: $worktree" }
        Files.createDirectories(worktree.parent)
        val add = processRunner.run(
            listOf("git", "worktree", "add", "-b", branch, worktree.toString(), baseRevision),
            root,
            Duration.ofMinutes(3),
        )
        if (add.exitCode != 0) {
            error("Failed to create worktree for ${task.id}: ${add.stderr.ifBlank { add.stdout }}")
        }
        injectLocalOrchestratorForVerification(worktree)
        val dependencyCommits = dependencyWorkspaces
            .filter { it.git && it.writable }
            .flatMap(::commitsForDescendants)
            .distinct()
        dependencyWorkspaces.filter { it.git && it.writable }.forEach { dependency ->
            check(!hasUncommittedChanges(dependency)) {
                "Dependency ${dependency.taskId} has uncommitted changes"
            }
        }
        dependencyCommits.forEach { commit ->
            processRunner.run(
                listOf("git", "cherry-pick", commit),
                worktree,
                Duration.ofMinutes(2),
            ).requireSuccess("cherry-pick dependency commit $commit")
        }
        val taskBaseRevision = processRunner.run(listOf("git", "rev-parse", "HEAD"), worktree)
            .requireSuccess("resolve task base").stdout.trim()
        seedTaskCommits.forEach { commit ->
            processRunner.run(
                listOf("git", "cherry-pick", commit),
                worktree,
                Duration.ofMinutes(2),
            ).requireSuccess("reapply previous task commit $commit")
        }
        prepareLocalBuildEnvironment(worktree)
        logger.info(
            "workspace_created",
            "태스크 ${task.id}용 Git worktree를 생성했습니다.",
            mapOf("path" to worktree.toString(), "branch" to branch, "base" to taskBaseRevision),
        )
        return WorkspaceHandle(
            task.id,
            worktree,
            true,
            true,
            taskBaseRevision,
            branch,
            inheritedCommits = dependencyCommits,
        )
    }

    private fun resolveDevelopRef(): String {
        developRef?.let { return it }
        val remoteExists = processRunner.run(listOf("git", "remote", "get-url", "origin"), root).exitCode == 0
        if (remoteExists && refreshDevelop) {
            val fetch = processRunner.run(
                listOf("git", "fetch", "origin", "develop"),
                root,
                Duration.ofMinutes(3),
            )
            if (fetch.exitCode != 0) {
                error(
                    "최신 develop을 가져오지 못해 worktree 생성을 중단합니다. " +
                        "오래된 기준으로 조용히 진행하지 않습니다: ${fetch.stderr.ifBlank { fetch.stdout }}",
                )
            }
        }
        val candidates = if (remoteExists) listOf("refs/remotes/origin/develop", "refs/heads/develop") else listOf("refs/heads/develop")
        developRef = candidates.firstOrNull {
            processRunner.run(listOf("git", "rev-parse", "--verify", it), root).exitCode == 0
        } ?: error("Git Flow 기준 브랜치 develop을 찾을 수 없습니다.")
        return developRef!!
    }

    private fun createIsolatedCopy(task: PlannedTask): WorkspaceHandle {
        val safeTask = task.id.replace(Regex("[^A-Za-z0-9._-]+"), "-")
        val target = root.resolve(".codex-orchestrator").resolve("isolated").resolve(runId).resolve(safeTask)
            .toAbsolutePath().normalize()
        check(target.startsWith(root.toAbsolutePath().normalize())) { "Unsafe isolated path: $target" }
        Files.createDirectories(target)
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(dir).toString().replace('\\', '/')
                if (relative == ".git" || relative == ".gradle" || relative == ".codex-orchestrator" ||
                    relative.split('/').any { it == "build" }
                ) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(target.resolve(root.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, target.resolve(root.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
        val hashes = task.writableFiles.filterNot { '*' in it || '?' in it }.associateWith { relative ->
            hashOrNull(root.resolve(relative))
        }
        logger.info("workspace_created", "태스크 ${task.id}용 격리 복사본을 생성했습니다.", mapOf("path" to target.toString()))
        return WorkspaceHandle(task.id, target, false, true, baselineHashes = hashes)
    }

    private fun isGitRepository(): Boolean = processRunner.run(
        listOf("git", "rev-parse", "--is-inside-work-tree"),
        root,
    ).let { it.exitCode == 0 && it.stdout.trim() == "true" }

    private fun prepareLocalBuildEnvironment(worktree: Path) {
        val gradleHome = worktree.resolve(".gradle")
        Files.createDirectories(gradleHome.resolve("tmp"))
        val jdk17 = locateJdk17()
        val localGradleProperties = buildString {
            appendLine("kotlin.compiler.execution.strategy=in-process")
            appendLine("org.gradle.daemon=false")
            if (jdk17 != null) {
                appendLine("org.gradle.java.installations.paths=${jdk17.toString().replace('\\', '/')}")
                appendLine("org.gradle.java.installations.auto-download=false")
            }
        }
        Files.writeString(gradleHome.resolve("gradle.properties"), localGradleProperties)
        val sourceLocalProperties = root.resolve("android").resolve("local.properties")
        val targetLocalProperties = worktree.resolve("android").resolve("local.properties")
        if (Files.isRegularFile(sourceLocalProperties) && !Files.exists(targetLocalProperties)) {
            Files.createDirectories(targetLocalProperties.parent)
            Files.copy(sourceLocalProperties, targetLocalProperties, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    private fun injectLocalOrchestratorForVerification(worktree: Path) {
        val relative = Path.of("android", "tools", "codex-orchestrator")
        val source = root.resolve(relative)
        val target = worktree.resolve(relative)
        if (!Files.isDirectory(source) || Files.exists(target)) return
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = dir.fileName?.toString().orEmpty()
                if (dir != source && (name == "build" || name == ".gradle")) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(target.resolve(source.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
        processRunner.run(listOf("git", "add", "--", relative.toString()), worktree)
            .requireSuccess("stage local orchestrator verification overlay")
        processRunner.run(
            listOf("git", "commit", "--no-verify", "-m", "chore: inject local orchestrator for isolated verification"),
            worktree,
            Duration.ofMinutes(2),
        ).requireSuccess("commit local orchestrator verification overlay")
        logger.info(
            "workspace_overlay_created",
            "현재 오케스트레이터를 격리 검증용 기준 커밋으로 주입했습니다.",
            mapOf("path" to target.toString()),
        )
    }

    private fun locateJdk17(): Path? {
        System.getenv("RUNNINGGU_JDK17_HOME")?.takeIf { it.isNotBlank() }?.let { configured ->
            val path = Path.of(configured).toAbsolutePath().normalize()
            if (isJdk17(path)) return path
        }
        val gradleJdks = Path.of(System.getProperty("user.home"), ".gradle", "jdks")
        if (!Files.isDirectory(gradleJdks)) return null
        return Files.list(gradleJdks).use { candidates ->
            candidates.filter(Files::isDirectory).filter(::isJdk17).findFirst().orElse(null)
        }
    }

    private fun isJdk17(path: Path): Boolean {
        val release = path.resolve("release")
        if (!Files.isRegularFile(release)) return false
        val text = runCatching { Files.readString(release) }.getOrDefault("").uppercase(Locale.ROOT)
        return text.contains("JAVA_VERSION=\"17.") || text.contains("JAVA_RUNTIME_VERSION=\"17.")
    }

    private fun hashOrNull(path: Path): String? {
        if (!Files.isRegularFile(path)) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun normalize(path: String): String = path.replace('\\', '/').removePrefix("\"").removeSuffix("\"")

    private fun isEphemeralBuildPath(path: String): Boolean {
        val normalized = normalize(path)
        if (normalized == "android/local.properties") return true
        return normalized.split('/').any { segment ->
            segment == ".gradle" || segment.startsWith(".gradle-")
        }
    }

    companion object {
        fun branchName(taskId: String, runId: String): String {
            val safeTask = taskId.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(32)
            val safeRun = runId.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-')
            val uniqueRun = listOf(safeRun.take(15), safeRun.takeLast(8)).filter { it.isNotBlank() }.joinToString("-")
            return "feature/codex-${safeTask.ifBlank { "task" }}-${uniqueRun.ifBlank { "run" }}"
        }
    }
}
