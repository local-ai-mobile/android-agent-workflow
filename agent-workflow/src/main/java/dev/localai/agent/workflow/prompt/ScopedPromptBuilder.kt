package dev.localai.agent.workflow.prompt

import dev.localai.agent.workflow.compaction.ContextDistiller
import dev.localai.agent.workflow.model.AgentSession
import dev.localai.agent.workflow.model.AgentStep
import dev.localai.agent.workflow.tool.AgentTool

object ScopedPromptBuilder {

    fun buildLeanChatPrompt(session: AgentSession): String {
        return buildString {
            append("You are an intelligent, helpful on-device AI assistant.\n\n")
            val pastUserPrompts = session.steps.filterIsInstance<AgentStep.UserPromptStep>()
            if (pastUserPrompts.size > 1) {
                append("--- CONVERSATION HISTORY ---\n")
                pastUserPrompts.dropLast(1).forEach {
                    append("User: ${it.prompt}\n")
                }
                append("---------------------------\n\n")
            }
            append("User: ${session.objective}\n")
            append("Assistant:")
        }
    }

    fun buildSingleToolScopedPrompt(
        objective: String,
        toolName: String,
        toolOutput: String
    ): String {
        return buildString {
            append("User Objective: $objective\n\n")
            append("Tool Observation [$toolName]:\n")
            append(toolOutput.trim())
            append("\n\nSynthesize a clear, concise, and helpful response for the user based strictly on the tool observation above.")
        }
    }

    fun buildAgentPrompt(
        session: AgentSession,
        tools: List<AgentTool>
    ): String {
        return buildString {
            append("You are an autonomous on-device AI agent capable of using tools to achieve user objectives.\n\n")
            if (tools.isNotEmpty()) {
                append(GemmaToolPromptFormatter.formatToolDeclarations(tools))
                append("\n\n")
            }

            val observations = session.steps.filterIsInstance<AgentStep.ObservationStep>()
            if (observations.isNotEmpty()) {
                append(ContextDistiller.formatRollingObservations(observations))
            }

            val thoughts = session.steps.filterIsInstance<AgentStep.ThoughtStep>()
            if (thoughts.isNotEmpty()) {
                append("--- PREVIOUS REASONING ---\n")
                thoughts.takeLast(3).forEach {
                    append("Step ${it.stepNumber}: ${it.thought}\n")
                }
                append("--------------------------\n\n")
            }

            append("User Objective: ${session.objective}\n")
            if (session.slashCommand == "/boost") {
                append("\n[BOOST ACTIVE]: You have an extended reasoning budget (up to 10 iterations). Explore thoroughly.\n")
            }
            append("\nEmit either a tool call (call:<tool_name>{key=\"value\"}) or your final answer.")
        }
    }
}
