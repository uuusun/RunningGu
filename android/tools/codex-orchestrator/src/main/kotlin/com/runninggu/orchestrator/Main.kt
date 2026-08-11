package com.runninggu.orchestrator

import com.runninggu.orchestrator.core.OrchestratorService
import com.runninggu.orchestrator.core.Preflight
import com.runninggu.orchestrator.core.PreflightException
import com.runninggu.orchestrator.core.RunOptions
import com.runninggu.orchestrator.logging.StructuredLogger
import com.runninggu.orchestrator.model.PreflightReport
import com.runninggu.orchestrator.rpc.CodexAppServerClient
import com.runninggu.orchestrator.util.OrchestratorJson
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = runCatching { Cli().execute(args.toList()) }.getOrElse { error ->
        System.err.println("ERROR: ${error.message ?: error::class.java.name}")
        2
    }
    if (exitCode != 0) exitProcess(exitCode)
}

class Cli(private val environment: Map<String, String> = System.getenv()) {
    fun execute(args: List<String>): Int {
        if (args.isEmpty() || args.first() in setOf("help", "--help", "-h")) {
            printHelp()
            return 0
        }
        return when (args.first()) {
            "doctor" -> inspectCommand(modelsOnly = false)
            "models" -> inspectCommand(modelsOnly = true)
            "run" -> runCommand(args.drop(1))
            else -> {
                System.err.println("Unknown command: ${args.first()}")
                printHelp()
                2
            }
        }
    }

    private fun runCommand(args: List<String>): Int {
        val parsed = parseOptions(args)
        val goal = parsed.values["goal"] ?: error("run requires --goal <text>")
        val cwd = Path.of(parsed.values["cwd"] ?: ".").toAbsolutePath().normalize()
        val workers = (parsed.values["workers"] ?: "4").toIntOrNull() ?: error("--workers must be an integer")
        require(workers in 1..4) { "--workers must be between 1 and 4" }
        val apply = "apply" in parsed.flags
        val planState = parsed.values["plan-state"]?.let { Path.of(it).toAbsolutePath().normalize() }
        val runId = OrchestratorService.newRunId()
        val logger = logger(cwd, runId)
        val codex = environment["CODEX_EXECUTABLE"] ?: "codex"
        CodexAppServerClient(
            listOf(codex, "app-server", "--listen", "stdio://"),
            logger,
            processEnvironment = appServerEnvironment(cwd),
        ).use { client ->
            val outcome = OrchestratorService(client, logger, codex).run(
                RunOptions(goal, cwd, workers, apply, planState),
                runId,
            )
            println("State: ${outcome.stateFile}")
            println("Result: ${if (outcome.success) "SUCCESS" else "INCOMPLETE"}")
            return if (outcome.success) 0 else 3
        }
    }

    private fun inspectCommand(modelsOnly: Boolean): Int {
        val cwd = Path.of(".").toAbsolutePath().normalize()
        val runId = "${if (modelsOnly) "models" else "doctor"}-${OrchestratorService.newRunId()}"
        val logger = logger(cwd, runId)
        val codex = environment["CODEX_EXECUTABLE"] ?: "codex"
        return try {
            CodexAppServerClient(listOf(codex, "app-server", "--listen", "stdio://"), logger).use { client ->
                val report = Preflight(client, logger, codexExecutable = codex)
                    .inspect(cwd, requirePro = false, requireModels = false)
                if (modelsOnly) printModels(report) else printDoctor(report)
            }
            0
        } catch (error: PreflightException) {
            System.err.println(error.message)
            3
        }
    }

    private fun printDoctor(report: PreflightReport) {
        println("Codex: ${report.codexVersion}")
        println("Java: ${report.javaVersion}")
        println("Authentication: ${report.account.type} (${report.account.email ?: "no email"})")
        println("ChatGPT plan: ${report.account.planType ?: "unknown"}")
        println("Models: ${report.models.size}")
        println("Rate limits: ${OrchestratorJson.encodeToString(report.rateLimits)}")
    }

    private fun printModels(report: PreflightReport) {
        println("Authentication: ${report.account.type}; plan=${report.account.planType ?: "unknown"}")
        report.models.forEach { model ->
            println("${model.id}\tefforts=${model.supportedReasoningEfforts}\thidden=${model.hidden}")
        }
    }

    private fun logger(cwd: Path, runId: String) = StructuredLogger(
        cwd.resolve(".codex-orchestrator").resolve("logs").resolve("$runId.jsonl"),
    )

    private fun appServerEnvironment(cwd: Path): Map<String, String> {
        val values = mutableMapOf<String, String>()
        environment["JAVA_HOME"]?.takeIf { it.isNotBlank() }?.let { javaHome ->
            val javaBin = Path.of(javaHome).resolve("bin").toString()
            values["PATH"] = javaBin + System.getProperty("path.separator") + environment["PATH"].orEmpty()
        }
        val configuredSdk = environment["ANDROID_HOME"] ?: environment["ANDROID_SDK_ROOT"]
            ?: readAndroidSdk(cwd.resolve("android").resolve("local.properties"))
        if (!configuredSdk.isNullOrBlank()) {
            values["ANDROID_HOME"] = configuredSdk
            values["ANDROID_SDK_ROOT"] = configuredSdk
        }
        val androidUserHome = cwd.resolve(".codex-orchestrator").resolve("android-home")
        Files.createDirectories(androidUserHome)
        values["ANDROID_USER_HOME"] = androidUserHome.toString()
        return values
    }

    private fun readAndroidSdk(localProperties: Path): String? {
        if (!Files.isRegularFile(localProperties)) return null
        val properties = Properties()
        Files.newInputStream(localProperties).use(properties::load)
        return properties.getProperty("sdk.dir")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseOptions(args: List<String>): ParsedOptions {
        val values = mutableMapOf<String, String>()
        val flags = mutableSetOf<String>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            require(token.startsWith("--")) { "Unexpected argument: $token" }
            val name = token.removePrefix("--")
            if (name == "apply") {
                flags += name
                index += 1
            } else {
                require(index + 1 < args.size) { "$token requires a value" }
                values[name] = args[index + 1]
                index += 2
            }
        }
        val unknown = values.keys - setOf("goal", "cwd", "workers", "plan-state")
        require(unknown.isEmpty()) { "Unknown options: $unknown" }
        return ParsedOptions(values, flags)
    }

    private fun printHelp() {
        println(
            """
            Usage: codex-orchestrate <command> [options]

            Commands:
              doctor   Check JDK, Codex App Server handshake, ChatGPT authentication, models, and usage.
              models   Print the exact models and reasoning efforts returned by model/list.
              run      Plan with gpt-5.6-sol and execute isolated tasks with gpt-5.6-luna.

            Run options:
              --goal <text>       Required goal.
              --cwd <path>        Repository or workspace (default: .).
              --workers <1..4>    Maximum independent Luna workers (default: 4).
              --plan-state <file> Reuse a validated Sol plan from a prior run state.
              --apply             Apply Sol-approved patches in DAG order. Default is review-only.

            Authentication is ChatGPT-only. API-key authentication and model fallback are rejected.
            The CLI starts `codex app-server --listen stdio://`; it never calls the Responses API directly.
            """.trimIndent(),
        )
    }

    private data class ParsedOptions(val values: Map<String, String>, val flags: Set<String>)
}
