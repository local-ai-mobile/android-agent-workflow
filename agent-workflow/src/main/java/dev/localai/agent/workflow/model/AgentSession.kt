package dev.localai.agent.workflow.model

import dev.localai.agent.workflow.core.AgentExecutionTier
import dev.localai.agent.workflow.tool.AgentToolCall
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AgentSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val objective: String,
    val modelId: String = "default",
    val status: AgentSessionStatus = AgentSessionStatus.IDLE,
    val steps: List<AgentStep> = emptyList(),
    val subAgents: List<SubAgentDelegation> = emptyList(),
    val pendingToolCall: AgentToolCall? = null,
    val remainingPlannedCalls: List<AgentToolCall> = emptyList(),
    val isolatedScratchpad: List<String> = emptyList(),
    val isPipelineTask: Boolean = false,
    val pipelineStages: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val executionTier: AgentExecutionTier = AgentExecutionTier.TIER_1_CHAT,
    val slashCommand: String? = null,
    val isPlanApproved: Boolean = false
) {
    val isolatedTokenEstimate: Int
        get() = isolatedScratchpad.sumOf { (it.length / 4).coerceAtLeast(1) }

    fun appendScratchpad(entry: String): AgentSession = copy(
        isolatedScratchpad = isolatedScratchpad + entry,
        updatedAt = System.currentTimeMillis()
    )
}
