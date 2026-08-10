package com.runninggu.orchestrator

import com.runninggu.orchestrator.core.Preflight
import com.runninggu.orchestrator.logging.StructuredLogger
import com.runninggu.orchestrator.rpc.CodexAppServerClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RealAccountIntegrationTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `real ChatGPT account and model catalog can be checked explicitly`() {
        assumeTrue(System.getenv("CODEX_INTEGRATION_TEST") == "true")
        val logger = StructuredLogger(temp.resolve("integration.jsonl"), quiet = true)
        CodexAppServerClient(listOf("codex", "app-server", "--listen", "stdio://"), logger).use { client ->
            val report = Preflight(client, logger).inspect(temp, requirePro = true, requireModels = true)
            assertEquals("chatgpt", report.account.type)
            assertEquals("pro", report.account.planType)
        }
    }
}
