package dev.localai.agent.workflow.core

import kotlinx.serialization.Serializable

@Serializable
enum class AgentApprovalMode(
    val id: String,
    val title: String,
    val subtitle: String,
    val badgeLabel: String,
    val iconEmoji: String
) {
    ALWAYS_ASK("ALWAYS_ASK", "Request Review", "Every command requires approval.", "Review", "🔒"),
    SENSITIVE_ONLY("SENSITIVE_ONLY", "Default", "Ask before sensitive operations.", "Default", "🛡️"),
    AUTO_DECIDE("AUTO_DECIDE", "Auto (Preview)", "Automatically decide if commands run.", "Auto", "🤖"),
    NEVER_ASK("NEVER_ASK", "Turbo", "Run all commands without restrictions.", "Turbo", "⚡");

    companion object {
        fun fromId(id: String?): AgentApprovalMode =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: SENSITIVE_ONLY
    }
}
