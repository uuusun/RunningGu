package com.runninggu.orchestrator

import com.runninggu.orchestrator.core.OrchestratorService
import com.runninggu.orchestrator.core.RunOptions
import com.runninggu.orchestrator.logging.StructuredLogger
import com.runninggu.orchestrator.model.WorkerStatus
import com.runninggu.orchestrator.util.asObject
import com.runninggu.orchestrator.util.obj
import com.runninggu.orchestrator.util.string
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RpcAndFullRunTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `JSON RPC client performs initialize and account read`() {
        mockClient(temp).use { client ->
            client.start()
            client.initialize()
            val account = client.request(
                "account/read",
                buildJsonObject { put("refreshToken", false) },
            ).asObject().obj("account")
            assertEquals("chatgpt", account?.string("type"))
            assertEquals("pro", account?.string("planType"))
        }
    }

    @Test
    fun `mock app server completes planning worker and review without model usage`() {
        val logger = StructuredLogger(temp.resolve("run-log.jsonl"), quiet = true)
        mockClient(temp).use { client ->
            val outcome = OrchestratorService(client, logger, javaExecutable()).run(
                RunOptions("mock goal", temp, workers = 4, apply = false),
                runId = "mock-full-run",
            )
            assertTrue(outcome.success)
            assertEquals(WorkerStatus.SUCCESS, outcome.results.single().status)
            assertTrue(Files.isRegularFile(outcome.stateFile))
            assertEquals(true, outcome.review?.decisions?.single()?.accepted)
        }
    }
}
