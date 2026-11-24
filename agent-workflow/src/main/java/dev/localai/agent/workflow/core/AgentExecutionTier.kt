package dev.localai.agent.workflow.core

import kotlinx.serialization.Serializable

@Serializable
enum class AgentExecutionTier {
    TIER_1_CHAT,
    TIER_2_SINGLE_TOOL,
    TIER_3_PIPELINE
}
