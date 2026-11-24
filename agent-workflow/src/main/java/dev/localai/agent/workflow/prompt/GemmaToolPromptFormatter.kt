package dev.localai.agent.workflow.prompt

import dev.localai.agent.workflow.tool.AgentTool
import dev.localai.agent.workflow.tool.AgentToolCall
import dev.localai.agent.workflow.tool.AgentToolRegistry

object GemmaToolPromptFormatter {
    private val toolCallRegex = Regex("""call:([a-zA-Z0-9_]+)\s*[\{\(]([^}\)]*)[\}\)]""")
    private val kvRegex = Regex("""([a-zA-Z0-9_]+)\s*[:=]\s*(?:"([^"]*)"|'([^']*)'|([^,}\s]+))""")

    fun formatToolDeclarations(tools: List<AgentTool>): String {
        return buildString {
            append("<|tool_declarations|>\n")
            tools.forEach { tool ->
                val paramsSig = tool.parameters.joinToString(", ") { p ->
                    "${p.name}: ${p.type}${if (p.required) " (required)" else " (optional)"}"
                }
                append("- ${tool.name}($paramsSig): ${tool.description}\n")
            }
            append("<|/tool_declarations|>\n")
            append("To invoke a tool, emit: call:<tool_name>{key=\"value\"}")
        }
    }

    fun parseModelToolCall(rawOutput: String, registry: AgentToolRegistry? = null): AgentToolCall? {
        val trimmed = rawOutput.trim()
        val match = toolCallRegex.find(trimmed) ?: return null
        val toolName = match.groupValues[1].trim()
        val rawArgs = match.groupValues[2].trim()
        val argsMap = mutableMapOf<String, String>()
        kvRegex.findAll(rawArgs).forEach { kv ->
            val key = kv.groupValues[1]
            val value = kv.groupValues[2].ifEmpty {
                kv.groupValues[3].ifEmpty {
                    kv.groupValues[4]
                }
            }
            argsMap[key] = value
        }
        val tool = registry?.getTool(toolName)
        val isSensitive = tool?.isCallSensitive(argsMap) ?: false
        return AgentToolCall(
            toolName = toolName,
            arguments = argsMap,
            isSensitive = isSensitive,
            reason = "Parsed from model function call"
        )
    }

    fun extractThoughtFromOutput(rawOutput: String, toolCall: AgentToolCall): String {
        val callPattern = Regex("""call:${toolCall.toolName}\s*[\{\(][^}\)]*[\}\)]""", RegexOption.IGNORE_CASE)
        val beforeCall = rawOutput.split(callPattern).firstOrNull()?.trim() ?: ""
        return beforeCall
            .replace(Regex("""<\|[^>]*\|>"""), "")
            .replace(Regex("""<[^>]*>"""), "")
            .trim()
    }
}
