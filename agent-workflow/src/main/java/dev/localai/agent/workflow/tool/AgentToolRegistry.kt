package dev.localai.agent.workflow.tool

import dev.localai.agent.workflow.core.AgentApprovalMode
import java.util.concurrent.ConcurrentHashMap

class AgentToolRegistry {
    private val tools = ConcurrentHashMap<String, AgentTool>()

    fun register(tool: AgentTool) {
        tools[tool.name.lowercase()] = tool
    }

    fun registerAll(toolsList: Collection<AgentTool>) {
        toolsList.forEach { register(it) }
    }

    fun getTool(name: String): AgentTool? = tools[name.trim().lowercase()]

    fun getAllTools(): List<AgentTool> = tools.values.toList()

    fun requiresUserApproval(
        approvalMode: AgentApprovalMode,
        toolName: String,
        arguments: Map<String, String>
    ): Boolean {
        return when (approvalMode) {
            AgentApprovalMode.NEVER_ASK -> false
            AgentApprovalMode.ALWAYS_ASK -> true
            AgentApprovalMode.SENSITIVE_ONLY -> {
                val tool = getTool(toolName)
                tool?.isCallSensitive(arguments) ?: true
            }
            AgentApprovalMode.AUTO_DECIDE -> {
                val action = arguments["action"]?.lowercase()?.trim() ?: ""
                val isHighImpact = action.contains("delete") ||
                        action.contains("drop") ||
                        action.contains("clear") ||
                        action.contains("cancel") ||
                        (toolName.equals("calendar_scheduler", ignoreCase = true) && action == "schedule_event")
                isHighImpact
            }
        }
    }
}
