package dev.localai.agent.workflow.compaction

import dev.localai.agent.workflow.model.AgentStep

object TokenBudgetManager {

    const val PER_MESSAGE_TOKEN_OVERHEAD: Int = 12
    const val SYSTEM_PROMPT_TOKEN_OVERHEAD: Int = 24
    const val DEFAULT_SAFETY_MARGIN_TOKENS: Int = 64

    fun calculateReservedOutputTokens(totalContextLength: Int): Int {
        return (totalContextLength * 0.25).toInt().coerceIn(128, 512)
    }

    fun calculateMaxInputBudget(
        totalContextLength: Int,
        reservedOutputTokens: Int? = null,
        safetyMarginTokens: Int = DEFAULT_SAFETY_MARGIN_TOKENS
    ): Int {
        val reserved = reservedOutputTokens ?: calculateReservedOutputTokens(totalContextLength)
        return (totalContextLength - reserved - safetyMarginTokens).coerceAtLeast(256)
    }

    fun estimateStepTokens(step: AgentStep): Int {
        val contentTokens = when (step) {
            is AgentStep.UserPromptStep -> ContextDistiller.estimateTokens(step.prompt)
            is AgentStep.ThoughtStep -> ContextDistiller.estimateTokens(step.thought)
            is AgentStep.SubAgentStep -> ContextDistiller.estimateTokens(step.subAgent.description)
            is AgentStep.ToolCallStep -> ContextDistiller.estimateTokens("${step.toolCall.toolName} ${step.toolCall.arguments}")
            is AgentStep.ObservationStep -> ContextDistiller.estimateTokens(
                ContextDistiller.distillObservation(step.result.toolName, step.result.output)
            )
            is AgentStep.VerificationStep -> ContextDistiller.estimateTokens(step.summary)
            is AgentStep.PlanReviewStep -> ContextDistiller.estimateTokens(step.explanation + step.stages.joinToString())
            is AgentStep.ResolutionStep -> ContextDistiller.estimateTokens(step.finalResponse)
        }
        return contentTokens + PER_MESSAGE_TOKEN_OVERHEAD
    }

    fun selectStepsForBudget(
        steps: List<AgentStep>,
        budgetTokens: Int
    ): List<AgentStep> {
        if (steps.isEmpty()) return emptyList()

        val initialPromptStep = steps.firstOrNull { it is AgentStep.UserPromptStep }
        val otherSteps = if (initialPromptStep != null) {
            steps.filter { it.id != initialPromptStep.id }
        } else {
            steps
        }

        val initialPromptCost = initialPromptStep?.let { estimateStepTokens(it) } ?: 0
        var remainingBudget = (budgetTokens - initialPromptCost).coerceAtLeast(0)

        val selected = mutableListOf<AgentStep>()

        for (i in otherSteps.indices.reversed()) {
            val step = otherSteps[i]
            val cost = estimateStepTokens(step)

            if (selected.isEmpty() || cost <= remainingBudget) {
                selected.add(step)
                remainingBudget -= cost
            } else {
                break
            }
        }

        val result = mutableListOf<AgentStep>()
        if (initialPromptStep != null) {
            result.add(initialPromptStep)
        }
        result.addAll(selected.reversed())
        return result
    }
}
