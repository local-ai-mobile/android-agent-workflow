package dev.localai.agent.workflow.tool

import kotlinx.serialization.Serializable

@Serializable
data class AgentToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String> = emptyList()
)
