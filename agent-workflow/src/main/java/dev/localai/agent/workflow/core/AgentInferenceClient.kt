package dev.localai.agent.workflow.core

fun interface AgentInferenceClient {
    suspend fun generate(prompt: String): String
}
