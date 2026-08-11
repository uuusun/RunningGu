package com.runninggu.orchestrator

import com.runninggu.orchestrator.util.OrchestratorJson
import com.runninggu.orchestrator.util.obj
import com.runninggu.orchestrator.util.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

object MockAppServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val threadCounter = AtomicInteger()
        val turnCounter = AtomicInteger()
        val threadModels = mutableMapOf<String, String>()
        System.`in`.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val request = OrchestratorJson.parseToJsonElement(line) as JsonObject
                val id = request["id"]
                val method = request.string("method") ?: return@forEach
                if (id == null) return@forEach
                when (method) {
                    "initialize" -> respond(id, buildJsonObject { put("userAgent", "mock"); put("platformFamily", "windows") })
                    "account/read" -> respond(
                        id,
                        buildJsonObject {
                            put(
                                "account",
                                buildJsonObject {
                                    put("type", "chatgpt")
                                    put("email", "mock@example.com")
                                    put("planType", "pro")
                                },
                            )
                            put("requiresOpenaiAuth", true)
                        },
                    )
                    "account/rateLimits/read" -> respond(
                        id,
                        buildJsonObject {
                            put(
                                "rateLimits",
                                buildJsonObject {
                                    put("limitId", "codex")
                                    put("primary", buildJsonObject { put("usedPercent", 1) })
                                },
                            )
                        },
                    )
                    "model/list" -> respond(
                        id,
                        buildJsonObject {
                            put(
                                "data",
                                buildJsonArray {
                                    add(model("gpt-5.6-sol", listOf("low", "high", "max"), "low"))
                                    add(model("gpt-5.6-luna", listOf("none", "low", "high"), "none"))
                                },
                            )
                        },
                    )
                    "thread/start" -> {
                        val sandbox = request.obj("params")?.string("sandbox")
                        require(sandbox in setOf("read-only", "workspace-write")) {
                            "invalid thread/start sandbox: $sandbox"
                        }
                        val threadId = "mock-thread-${threadCounter.incrementAndGet()}"
                        threadModels[threadId] = request.obj("params")?.string("model").orEmpty()
                        respond(id, buildJsonObject { put("thread", buildJsonObject { put("id", threadId) }) })
                    }
                    "turn/start" -> {
                        val params = request.obj("params") ?: JsonObject(emptyMap())
                        val threadId = params.string("threadId").orEmpty()
                        val turnId = "mock-turn-${turnCounter.incrementAndGet()}"
                        respond(
                            id,
                            buildJsonObject {
                                put("turn", buildJsonObject { put("id", turnId); put("status", "inProgress") })
                            },
                        )
                        val outputSchema = params.obj("outputSchema")
                        val properties = outputSchema?.obj("properties") ?: JsonObject(emptyMap())
                        val output = when {
                            "tasks" in properties -> PLAN_JSON
                            "changedFiles" in properties -> WORKER_JSON
                            "decisions" in properties -> REVIEW_JSON
                            else -> "{}"
                        }
                        notify(
                            "item/completed",
                            buildJsonObject {
                                put("threadId", threadId)
                                put("turnId", turnId)
                                put("item", buildJsonObject { put("id", "item-$turnId"); put("type", "agentMessage"); put("text", output) })
                            },
                        )
                        notify(
                            "turn/completed",
                            buildJsonObject {
                                put("threadId", threadId)
                                put("turn", buildJsonObject { put("id", turnId); put("status", "completed") })
                            },
                        )
                    }
                    else -> respond(id, JsonObject(emptyMap()))
                }
            }
        }
    }

    private fun model(id: String, efforts: List<String>, default: String) = buildJsonObject {
        put("id", id)
        put("model", id)
        put("displayName", id)
        put("hidden", false)
        put("defaultReasoningEffort", default)
        put(
            "supportedReasoningEfforts",
            buildJsonArray {
                efforts.forEach { effort -> add(buildJsonObject { put("reasoningEffort", effort); put("description", effort) }) }
            },
        )
    }

    private fun respond(id: JsonElement, result: JsonElement) = write(
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) },
    )

    private fun notify(method: String, params: JsonElement) = write(
        buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) },
    )

    private fun write(message: JsonObject) {
        println(OrchestratorJson.encodeToString(JsonObject.serializer(), message))
        System.out.flush()
    }

    private const val PLAN_JSON = """{"planId":"mock-plan","summary":"mock","tasks":[{"id":"inspect","goal":"Inspect without writes","dependencies":[],"readFiles":[],"writableFiles":[],"completionCriteria":["report success"],"difficulty":"LOW"}]}"""
    private const val WORKER_JSON = """{"taskId":"inspect","status":"SUCCESS","summary":"inspected","changedFiles":[],"tests":[{"command":"mock","outcome":"passed"}],"criteriaMet":true,"risks":[],"commitOrPatch":null,"failureReason":null}"""
    private const val REVIEW_JSON = """{"summary":"accepted","decisions":[{"taskId":"inspect","accepted":true,"feedback":""}]}"""
}
