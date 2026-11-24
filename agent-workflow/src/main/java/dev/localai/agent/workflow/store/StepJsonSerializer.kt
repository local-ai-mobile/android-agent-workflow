package dev.localai.agent.workflow.store

import dev.localai.agent.workflow.model.AgentStep
import dev.localai.agent.workflow.model.SubAgentDelegation
import dev.localai.agent.workflow.tool.AgentToolCall
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object StepJsonSerializer {

    val jsonInstance = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
        classDiscriminator = "type"
    }

    fun serializeSteps(steps: List<AgentStep>): String {
        return jsonInstance.encodeToString(steps)
    }

    fun deserializeSteps(jsonStr: String?): List<AgentStep> {
        if (jsonStr.isNullOrBlank() || jsonStr.trim() == "[]") return emptyList()
        return try {
            jsonInstance.decodeFromString<List<AgentStep>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serializeSubAgents(subAgents: List<SubAgentDelegation>): String {
        return jsonInstance.encodeToString(subAgents)
    }

    fun deserializeSubAgents(jsonStr: String?): List<SubAgentDelegation> {
        if (jsonStr.isNullOrBlank() || jsonStr.trim() == "[]") return emptyList()
        return try {
            jsonInstance.decodeFromString<List<SubAgentDelegation>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serializeToolCall(call: AgentToolCall?): String? {
        if (call == null) return null
        return jsonInstance.encodeToString(call)
    }

    fun deserializeToolCall(jsonStr: String?): AgentToolCall? {
        if (jsonStr.isNullOrBlank()) return null
        return try {
            jsonInstance.decodeFromString<AgentToolCall>(jsonStr)
        } catch (_: Exception) {
            null
        }
    }

    fun serializeToolCalls(calls: List<AgentToolCall>): String {
        return jsonInstance.encodeToString(calls)
    }

    fun deserializeToolCalls(jsonStr: String?): List<AgentToolCall> {
        if (jsonStr.isNullOrBlank() || jsonStr.trim() == "[]") return emptyList()
        return try {
            jsonInstance.decodeFromString<List<AgentToolCall>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serializeStringList(list: List<String>): String {
        return jsonInstance.encodeToString(list)
    }

    fun deserializeStringList(jsonStr: String?): List<String> {
        if (jsonStr.isNullOrBlank() || jsonStr.trim() == "[]") return emptyList()
        return try {
            jsonInstance.decodeFromString<List<String>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
