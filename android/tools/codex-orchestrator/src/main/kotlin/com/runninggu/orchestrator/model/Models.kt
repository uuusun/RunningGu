package com.runninggu.orchestrator.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class Difficulty {
    LOW,
    MEDIUM,
    HIGH,
}

@Serializable
data class PlannedTask(
    val id: String,
    val goal: String,
    val dependencies: List<String>,
    val readFiles: List<String>,
    val writableFiles: List<String>,
    val completionCriteria: List<String>,
    val difficulty: Difficulty,
)

@Serializable
data class ExecutionPlan(
    val planId: String,
    val summary: String,
    val tasks: List<PlannedTask>,
)

@Serializable
enum class WorkerStatus {
    SUCCESS,
    FAILED,
    BLOCKED,
}

@Serializable
data class WorkerResult(
    val taskId: String,
    val status: WorkerStatus,
    val summary: String,
    val changedFiles: List<String>,
    val tests: List<TestResult>,
    val criteriaMet: Boolean,
    val risks: List<String>,
    val commitOrPatch: String? = null,
    val failureReason: String? = null,
    val attempt: Int = 0,
    val workspace: String? = null,
)

@Serializable
data class TestResult(
    val command: String,
    val outcome: String,
)

@Serializable
data class ReviewDecision(
    val taskId: String,
    val accepted: Boolean,
    val feedback: String,
)

@Serializable
data class ReviewResult(
    val summary: String,
    val decisions: List<ReviewDecision>,
)

@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String,
    val hidden: Boolean,
    val defaultReasoningEffort: String? = null,
    val supportedReasoningEfforts: List<String> = emptyList(),
)

@Serializable
data class AccountInfo(
    val type: String? = null,
    val email: String? = null,
    val planType: String? = null,
    val requiresOpenaiAuth: Boolean = true,
)

@Serializable
data class PreflightReport(
    val codexVersion: String,
    val javaVersion: String,
    val account: AccountInfo,
    val models: List<ModelInfo>,
    val rateLimits: JsonElement,
)

@Serializable
data class PersistentRunState(
    val runId: String,
    val goal: String,
    val cwd: String,
    val workers: Int,
    val apply: Boolean,
    val phase: String,
    val plan: ExecutionPlan? = null,
    val results: List<WorkerResult> = emptyList(),
    val review: ReviewResult? = null,
    val models: List<ModelInfo> = emptyList(),
    val account: AccountInfo? = null,
    val usageBefore: JsonElement? = null,
    val usageAfter: JsonElement? = null,
    val resumeCommand: String,
    val limitation: String? = null,
)
