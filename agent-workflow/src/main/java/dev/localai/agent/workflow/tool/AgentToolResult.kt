package dev.localai.agent.workflow.tool

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AgentToolResult(
    val toolName: String,
    val isSuccess: Boolean,
    val output: String,
    val callId: String = UUID.randomUUID().toString(),
    val executionTimeMs: Long = 0L,
    val structuredSummary: String = ""
)
