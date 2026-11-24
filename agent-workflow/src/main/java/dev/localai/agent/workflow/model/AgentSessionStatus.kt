package dev.localai.agent.workflow.model

import kotlinx.serialization.Serializable

@Serializable
enum class AgentSessionStatus {
    IDLE,
    PLANNING,
    AWAITING_APPROVAL,
    EXECUTING_TOOL,
    SYNTHESIZING,
    COMPLETED,
    DENIED,
    ERROR
}
