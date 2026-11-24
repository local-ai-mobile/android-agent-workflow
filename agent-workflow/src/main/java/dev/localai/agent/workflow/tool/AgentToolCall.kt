package dev.localai.agent.workflow.tool

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AgentToolCall(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val arguments: Map<String, String> = emptyMap(),
    val isSensitive: Boolean = false,
    val reason: String = ""
)
