package dev.localai.agent.workflow.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SubAgentDelegation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: String,
    val description: String,
    val assignedTools: List<String> = emptyList(),
    val status: AgentSessionStatus = AgentSessionStatus.IDLE,
    val liveAction: String? = null,
    val resultPreview: String? = null
)
