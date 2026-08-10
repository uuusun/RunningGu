package com.runninggu.orchestrator.rpc

import com.runninggu.orchestrator.logging.StructuredLogger
import com.runninggu.orchestrator.util.OrchestratorJson
import com.runninggu.orchestrator.util.obj
import com.runninggu.orchestrator.util.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

data class TurnOutcome(
    val turnId: String,
    val status: String,
    val finalMessage: String,
    val error: String? = null,
    val reroutedTo: String? = null,
)

interface AppServerClient : AutoCloseable {
    fun start()
    fun initialize()
    fun request(method: String, params: JsonElement = JsonObject(emptyMap())): JsonElement
    fun notify(method: String, params: JsonElement = JsonObject(emptyMap()))
    fun awaitTurn(turnId: String, timeout: Duration = Duration.ofHours(2)): TurnOutcome
    fun interruptTurn(threadId: String, turnId: String) {
        request(
            "turn/interrupt",
            buildJsonObject {
                put("threadId", threadId)
                put("turnId", turnId)
            },
        )
    }
}

class CodexAppServerClient(
    private val command: List<String>,
    private val logger: StructuredLogger,
    private val requestTimeout: Duration = Duration.ofSeconds(45),
    private val processEnvironment: Map<String, String> = emptyMap(),
) : AppServerClient {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableFuture<JsonElement>>()
    private val turnFutures = ConcurrentHashMap<String, CompletableFuture<TurnOutcome>>()
    private val turnMessages = ConcurrentHashMap<String, String>()
    private val reroutes = ConcurrentHashMap<String, String>()
    private lateinit var process: Process
    private lateinit var writer: BufferedWriter

    override fun start() {
        check(!this::process.isInitialized) { "App Server client was already started" }
        process = ProcessBuilder(command).apply {
            environment().putAll(processEnvironment)
        }.start()
        writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        thread(name = "codex-app-server-stdout", isDaemon = true) {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach(::receiveLine)
            }
            val failure = IllegalStateException("Codex App Server stdout closed")
            pending.values.forEach { it.completeExceptionally(failure) }
            turnFutures.values.forEach { it.completeExceptionally(failure) }
        }
        thread(name = "codex-app-server-stderr", isDaemon = true) {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { logger.warn("app_server_stderr", it) }
            }
        }
        logger.info("app_server_started", "Codex App Server를 stdio로 시작했습니다.", mapOf("command" to command))
    }

    override fun initialize() {
        request(
            "initialize",
            buildJsonObject {
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "runninggu_codex_orchestrator")
                        put("title", "RunningGu Codex Orchestrator")
                        put("version", "0.1.0")
                    },
                )
            },
        )
        notify("initialized")
        logger.info("app_server_initialized", "initialize/initialized 핸드셰이크가 완료됐습니다.")
    }

    override fun request(method: String, params: JsonElement): JsonElement {
        check(this::process.isInitialized && process.isAlive) { "Codex App Server is not running" }
        val id = nextId.getAndIncrement().toString()
        val future = CompletableFuture<JsonElement>()
        pending[id] = future
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id.toLong())
                put("method", method)
                put("params", params)
            },
        )
        return try {
            future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } finally {
            pending.remove(id)
        }
    }

    override fun notify(method: String, params: JsonElement) {
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            },
        )
    }

    override fun awaitTurn(turnId: String, timeout: Duration): TurnOutcome =
        turnFutures.computeIfAbsent(turnId) { CompletableFuture() }
            .get(timeout.toMillis(), TimeUnit.MILLISECONDS)

    @Synchronized
    private fun send(message: JsonObject) {
        writer.write(OrchestratorJson.encodeToString(JsonObject.serializer(), message))
        writer.newLine()
        writer.flush()
    }

    private fun receiveLine(line: String) {
        if (line.isBlank()) return
        val message = runCatching { OrchestratorJson.parseToJsonElement(line) as JsonObject }
            .getOrElse {
                logger.warn("app_server_non_json", "App Server가 JSON이 아닌 출력을 보냈습니다.", mapOf("line" to line))
                return
            }
        val id = (message["id"] as? JsonPrimitive)?.content
        val method = message.string("method")
        if (id != null && method == null) {
            val future = pending[id] ?: return
            val error = message.obj("error")
            if (error != null) {
                future.completeExceptionally(
                    IllegalStateException("JSON-RPC error ${error["code"]}: ${error.string("message")}"),
                )
            } else {
                future.complete(message["result"] ?: JsonObject(emptyMap()))
            }
            return
        }
        if (id != null && method != null) {
            answerServerRequest(id, method)
            return
        }
        if (method != null) handleNotification(method, message.obj("params") ?: JsonObject(emptyMap()))
    }

    private fun answerServerRequest(id: String, method: String) {
        val result = when {
            method.contains("requestApproval", ignoreCase = true) -> buildJsonObject { put("decision", "decline") }
            method == "execCommandApproval" || method == "applyPatchApproval" -> buildJsonObject {
                put("decision", buildJsonObject { put("denied", buildJsonObject { put("rejection", "non-interactive orchestrator") }) })
            }
            method == "tool/requestUserInput" -> buildJsonObject { put("answers", JsonObject(emptyMap())) }
            else -> null
        }
        val response = if (result != null) {
            buildJsonObject {
                put("jsonrpc", "2.0")
                id.toLongOrNull()?.let { put("id", it) } ?: put("id", id)
                put("result", result)
            }
        } else {
            buildJsonObject {
                put("jsonrpc", "2.0")
                id.toLongOrNull()?.let { put("id", it) } ?: put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", -32601)
                        put("message", "Unsupported non-interactive server request: $method")
                    },
                )
            }
        }
        send(response)
        logger.warn("server_request_rejected", "비대화형 실행에서 서버 요청을 거부했습니다.", mapOf("method" to method))
    }

    private fun handleNotification(method: String, params: JsonObject) {
        when (method) {
            "item/completed" -> {
                val item = params.obj("item") ?: return
                if (item.string("type") == "agentMessage") {
                    val turnId = params.string("turnId") ?: return
                    item.string("text")?.let { turnMessages[turnId] = it }
                }
            }
            "model/rerouted" -> {
                val turnId = params.string("turnId") ?: return
                val to = params.string("toModel") ?: "unknown"
                reroutes[turnId] = to
                logger.error(
                    "model_rerouted",
                    "요청 모델이 다른 모델로 라우팅되어 실행을 실패 처리합니다.",
                    mapOf("turnId" to turnId, "from" to params.string("fromModel"), "to" to to),
                )
            }
            "turn/completed" -> {
                val turn = params.obj("turn") ?: return
                val turnId = turn.string("id") ?: return
                val error = turn.obj("error")?.string("message")
                val outcome = TurnOutcome(
                    turnId = turnId,
                    status = turn.string("status") ?: "failed",
                    finalMessage = turnMessages[turnId].orEmpty(),
                    error = error,
                    reroutedTo = reroutes[turnId],
                )
                turnFutures.computeIfAbsent(turnId) { CompletableFuture() }.complete(outcome)
            }
            "warning", "configWarning" -> logger.warn(
                "app_server_warning",
                params.string("message") ?: params.string("summary") ?: params.toString(),
            )
        }
    }

    override fun close() {
        if (this::writer.isInitialized) runCatching { writer.close() }
        if (this::process.isInitialized) {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
