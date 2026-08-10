package com.runninggu.orchestrator.core

import com.runninggu.orchestrator.logging.StructuredLogger
import com.runninggu.orchestrator.model.AccountInfo
import com.runninggu.orchestrator.model.ModelInfo
import com.runninggu.orchestrator.model.PreflightReport
import com.runninggu.orchestrator.rpc.AppServerClient
import com.runninggu.orchestrator.util.ProcessRunner
import com.runninggu.orchestrator.util.array
import com.runninggu.orchestrator.util.asObject
import com.runninggu.orchestrator.util.boolean
import com.runninggu.orchestrator.util.obj
import com.runninggu.orchestrator.util.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

class PreflightException(message: String) : IllegalStateException(message)

class Preflight(
    private val client: AppServerClient,
    private val logger: StructuredLogger,
    private val processRunner: ProcessRunner = ProcessRunner(),
    private val codexExecutable: String = "codex",
) {
    fun inspect(cwd: Path, requirePro: Boolean, requireModels: Boolean): PreflightReport {
        val javaFeature = Runtime.version().feature()
        if (javaFeature < 17) {
            throw PreflightException("JDK 17 이상이 필요합니다. 현재 Java: ${Runtime.version()}")
        }
        val versionResult = processRunner.run(listOf(codexExecutable, "--version"), cwd)
        if (versionResult.exitCode != 0) {
            throw PreflightException("codex 실행 파일을 찾거나 실행할 수 없습니다: ${versionResult.stderr}")
        }
        val codexVersion = versionResult.stdout.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        client.start()
        client.initialize()

        val accountResponse = client.request(
            "account/read",
            buildJsonObject { put("refreshToken", false) },
        ).asObject()
        val accountObject = accountResponse.obj("account")
        val account = AccountInfo(
            type = accountObject?.string("type"),
            email = accountObject?.string("email"),
            planType = accountObject?.string("planType"),
            requiresOpenaiAuth = accountResponse.boolean("requiresOpenaiAuth") ?: true,
        )
        enforceAuthentication(account, requirePro)

        val models = readModels()
        if (requireModels) enforceRequiredModels(models)
        val rateLimits = client.request("account/rateLimits/read")
        logger.info(
            "preflight_complete",
            "Codex 사전 검사가 완료됐습니다.",
            mapOf(
                "codexVersion" to codexVersion,
                "javaVersion" to Runtime.version().toString(),
                "authType" to account.type,
                "planType" to account.planType,
                "models" to models.map { it.id },
            ),
        )
        return PreflightReport(
            codexVersion = codexVersion,
            javaVersion = Runtime.version().toString(),
            account = account,
            models = models,
            rateLimits = rateLimits,
        )
    }

    private fun enforceAuthentication(account: AccountInfo, requirePro: Boolean) {
        val type = account.type?.lowercase()
        if (type == "apikey") {
            throw PreflightException(
                "API 키 인증이 감지되어 중단합니다. `codex logout` 후 `codex login --device-auth`를 실행하거나 " +
                    "App Server의 `account/login/start`에 `{\"type\":\"chatgptDeviceCode\"}`를 사용해 ChatGPT 로그인으로 전환하세요.",
            )
        }
        if (type != "chatgpt") {
            throw PreflightException(
                "ChatGPT 로그인이 필요합니다. `codex login --device-auth`를 실행하거나 App Server의 " +
                    "`account/login/start`에 `{\"type\":\"chatgptDeviceCode\"}`를 보내 로그인하세요.",
            )
        }
        if (requirePro && account.planType?.lowercase() !in PRO_PLAN_IDENTIFIERS) {
            throw PreflightException(
                "이 실행은 ChatGPT Pro 계열 사용량만 허용합니다. 확인된 App Server 요금제 식별자: ${account.planType ?: "알 수 없음"}. " +
                    "다른 계정/요금제로 조용히 전환하지 않습니다.",
            )
        }
    }

    private fun readModels(): List<ModelInfo> {
        val result = client.request(
            "model/list",
            buildJsonObject {
                put("limit", 100)
                put("includeHidden", true)
            },
        ).asObject()
        return result.array("data").orEmpty().map { element ->
            val model = element.asObject()
            ModelInfo(
                id = model.string("model") ?: model.string("id") ?: error("model/list entry has no id"),
                displayName = model.string("displayName") ?: model.string("id").orEmpty(),
                hidden = model.boolean("hidden") ?: false,
                defaultReasoningEffort = model.string("defaultReasoningEffort"),
                supportedReasoningEfforts = model.array("supportedReasoningEfforts").orEmpty().mapNotNull {
                    (it as? JsonObject)?.string("reasoningEffort")
                },
            )
        }
    }

    private fun enforceRequiredModels(models: List<ModelInfo>) {
        val ids = models.map { it.id }.toSet()
        val missing = REQUIRED_MODELS - ids
        if (missing.isNotEmpty()) {
            val details = models.joinToString(System.lineSeparator()) {
                "- ${it.id}: efforts=${it.supportedReasoningEfforts.ifEmpty { listOf("unknown") }} hidden=${it.hidden}"
            }
            throw PreflightException(
                "필수 모델 ${missing.sorted()}을 사용할 수 없어 중단합니다. 대체 모델을 사용하지 않습니다.\n" +
                    "실제 모델 목록:\n$details",
            )
        }
    }

    companion object {
        val REQUIRED_MODELS = setOf("gpt-5.6-sol", "gpt-5.6-luna")
        // `prolite` is returned by the current first-party Codex App Server for some Pro entitlements.
        // Keep the exact value in logs/reports; never rewrite it to the public `pro` label.
        val PRO_PLAN_IDENTIFIERS = setOf("pro", "prolite")
    }
}
