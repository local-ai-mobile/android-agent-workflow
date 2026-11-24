package dev.localai.agent.workflow.tool

import android.content.Context

interface AgentTool {
    val name: String
    val title: String get() = name
    val description: String
    val category: String get() = "General"
    val parameters: List<AgentToolParameter> get() = emptyList()
    val isSensitive: Boolean get() = false
    val sensitiveActions: Set<String> get() = emptySet()
    val isDeterministic: Boolean get() = false

    fun isCallSensitive(arguments: Map<String, String>): Boolean {
        val action = arguments["action"]?.lowercase()?.trim()
        if (action != null && sensitiveActions.isNotEmpty()) {
            return sensitiveActions.contains(action)
        }
        return isSensitive
    }

    suspend fun execute(arguments: Map<String, String>): AgentToolResult {
        throw UnsupportedOperationException("execute(arguments) or execute(context, arguments) must be implemented")
    }

    suspend fun execute(context: Context, arguments: Map<String, String>): AgentToolResult {
        return execute(arguments)
    }
}
