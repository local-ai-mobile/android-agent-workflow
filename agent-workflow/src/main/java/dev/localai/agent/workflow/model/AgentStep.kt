package dev.localai.agent.workflow.model

import dev.localai.agent.workflow.tool.AgentToolCall
import dev.localai.agent.workflow.tool.AgentToolResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
sealed class AgentStep {
    abstract val id: String
    abstract val timestamp: Long

    @Serializable
    @SerialName("user_prompt")
    data class UserPromptStep(
        val prompt: String,
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentStep()

    @Serializable
    @SerialName("thought")
    data class ThoughtStep(
        val stepNumber: Int,
        val thought: String,
        val planItems: List<String> = emptyList(),
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentStep()

    @Serializable
    @SerialName("sub_agent")
    data class SubAgentStep(
        val stepNumber: Int,
        val subAgent: SubAgentDelegation,
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentStep()

    @Serializable
    @SerialName("tool_call")
    data class ToolCallStep(
        val stepNumber: Int,
        val toolCall: AgentToolCall,
        val approvalStatus: ToolApprovalStatus = ToolApprovalStatus.AUTO_APPROVED,
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentStep()

    @Serializable
    @SerialName("observation")
    data class ObservationStep(
        val stepNumber: Int,
        val result: AgentToolResult,
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentStep()

    @Serializable
    @SerialName("verification")
    data class VerificationStep(
        val summary: String,
        val isVerified: Boolean = true,
        val stepNumber: Int = 1,
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentStep()

    @Serializable
    @SerialName("plan_review")
    data class PlanReviewStep(
        val stages: List<String>,
        val explanation: String,
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentStep()

    @Serializable
    @SerialName("resolution")
    data class ResolutionStep(
        val finalResponse: String,
        val totalToolCalls: Int,
        val isolatedScratchpadTokens: Int,
        val mainChatTokensPolluted: Int = 0,
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentStep()
}
