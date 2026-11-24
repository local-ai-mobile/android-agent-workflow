package dev.localai.agent.workflow.model

import kotlinx.serialization.Serializable

@Serializable
enum class ToolApprovalStatus {
    PENDING_USER_APPROVAL,
    USER_APPROVED,
    AUTO_APPROVED,
    USER_DENIED
}
