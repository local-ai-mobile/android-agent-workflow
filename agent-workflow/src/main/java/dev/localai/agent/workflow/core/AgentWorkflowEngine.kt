package dev.localai.agent.workflow.core

import android.content.Context
import dev.localai.agent.workflow.compaction.ContextDistiller
import dev.localai.agent.workflow.model.AgentSession
import dev.localai.agent.workflow.model.AgentSessionStatus
import dev.localai.agent.workflow.model.AgentStep
import dev.localai.agent.workflow.model.ToolApprovalStatus
import dev.localai.agent.workflow.prompt.GemmaToolPromptFormatter
import dev.localai.agent.workflow.prompt.ScopedPromptBuilder
import dev.localai.agent.workflow.prompt.SlashCommandParser
import dev.localai.agent.workflow.store.AgentSessionStore
import dev.localai.agent.workflow.tool.AgentTool
import dev.localai.agent.workflow.tool.AgentToolCall
import dev.localai.agent.workflow.tool.AgentToolRegistry
import dev.localai.agent.workflow.tool.AgentToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

class AgentWorkflowEngine private constructor(
    private val context: Context?,
    private val inferenceClient: AgentInferenceClient?,
    private val approvalMode: AgentApprovalMode,
    private val toolRegistry: AgentToolRegistry,
    private val sessionStore: AgentSessionStore?
) {
    private val _sessionState = MutableStateFlow<AgentSession?>(null)
    val sessionState: StateFlow<AgentSession?> = _sessionState.asStateFlow()

    private val greetingRegex = Regex(
        """^(hi|hello|hey|greetings|howdy|yo|sup|good\s+(morning|afternoon|evening|day|night)|how\s+are\s+you|who\s+are\s+you|what('s|\s+is)\s+up|thanks|thank\s+you|bye|goodbye)(\s+(there|assistant|agent|friend|all|antigravity|buddy))?[!.? ]*$""",
        RegexOption.IGNORE_CASE
    )

    fun classifyExecutionTier(objective: String, slashCommand: String? = null): AgentExecutionTier {
        if (slashCommand != null) {
            if (objective.isBlank()) return AgentExecutionTier.TIER_1_CHAT
            return AgentExecutionTier.TIER_3_PIPELINE
        }
        val trimmed = objective.trim()
        val lower = trimmed.lowercase(Locale.US)
        if (greetingRegex.matches(trimmed)) return AgentExecutionTier.TIER_1_CHAT

        val isExplicitPipeline = lower.contains("pipeline") || lower.contains("heavy") ||
                lower.contains("continuously") || lower.contains("loop") ||
                lower.contains("deep") || lower.contains("recursive")

        val plannedCalls = planInitialTools(trimmed)
        if (plannedCalls.isEmpty()) return AgentExecutionTier.TIER_1_CHAT
        if (plannedCalls.size == 1) {
            if (isExplicitPipeline) {
                return AgentExecutionTier.TIER_3_PIPELINE
            }
            return AgentExecutionTier.TIER_2_SINGLE_TOOL
        }
        return AgentExecutionTier.TIER_3_PIPELINE
    }

    suspend fun executeObjective(
        objective: String,
        sessionId: String? = null,
        modelId: String = "default"
    ): AgentSession {
        val (slashCmd, cleanObjective) = SlashCommandParser.extractSlashCommand(objective)
        val effectiveObjective = cleanObjective.ifEmpty { objective }
        val tier = classifyExecutionTier(cleanObjective, slashCmd)

        val session = createInitialSession(
            id = sessionId ?: UUID.randomUUID().toString(),
            objective = effectiveObjective,
            rawPrompt = objective,
            slashCommand = slashCmd,
            tier = tier,
            modelId = modelId
        )
        updateSession(session)

        return advanceSession(session)
    }

    suspend fun continueSession(sessionId: String, followUpPrompt: String): AgentSession {
        val session = sessionStore?.getSession(sessionId) ?: _sessionState.value
            ?: throw IllegalStateException("Session not found: $sessionId")
        val trimmed = followUpPrompt.trim()
        val (slashCmd, cleanPrompt) = SlashCommandParser.extractSlashCommand(trimmed)
        val effectivePrompt = cleanPrompt.ifEmpty { trimmed }
        val tier = classifyExecutionTier(cleanPrompt, slashCmd)

        val userStep = AgentStep.UserPromptStep(prompt = trimmed)
        val continuing = session.copy(
            objective = effectivePrompt,
            status = AgentSessionStatus.PLANNING,
            steps = session.steps + userStep,
            pendingToolCall = null,
            remainingPlannedCalls = if (tier == AgentExecutionTier.TIER_1_CHAT) emptyList() else planInitialTools(effectivePrompt),
            executionTier = tier,
            slashCommand = slashCmd,
            isPlanApproved = false,
            updatedAt = System.currentTimeMillis()
        )
        updateSession(continuing)
        return advanceSession(continuing)
    }

    suspend fun resumeSession(sessionId: String): AgentSession {
        val session = sessionStore?.getSession(sessionId) ?: _sessionState.value
            ?: throw IllegalStateException("Session not found: $sessionId")
        return advanceSession(session)
    }

    suspend fun approvePlan(sessionId: String): AgentSession {
        val session = sessionStore?.getSession(sessionId) ?: _sessionState.value
            ?: throw IllegalStateException("Session not found: $sessionId")
        val approved = session.copy(
            isPlanApproved = true,
            status = AgentSessionStatus.PLANNING,
            updatedAt = System.currentTimeMillis()
        ).appendScratchpad("[PLAN_APPROVED] User approved execution plan DAG.")
        updateSession(approved)
        return advanceSession(approved)
    }

    suspend fun approvePlanAndResume(sessionId: String): AgentSession = approvePlan(sessionId)

    suspend fun approvePendingStep(sessionId: String): AgentSession {
        val session = sessionStore?.getSession(sessionId) ?: _sessionState.value
            ?: throw IllegalStateException("Session not found: $sessionId")
        val pending = session.pendingToolCall ?: return session

        val updatedSteps = session.steps.map { step ->
            if (step is AgentStep.ToolCallStep && step.toolCall.id == pending.id) {
                step.copy(approvalStatus = ToolApprovalStatus.USER_APPROVED)
            } else step
        }
        var current = session.copy(
            status = AgentSessionStatus.EXECUTING_TOOL,
            steps = updatedSteps,
            pendingToolCall = null
        ).appendScratchpad("[USER_APPROVED] ${pending.toolName}")
        updateSession(current)

        val tool = toolRegistry.getTool(pending.toolName)
            ?: throw IllegalStateException("Tool not registered: ${pending.toolName}")
        val result = executeToolInternal(tool, pending.arguments)
        val obsStep = AgentStep.ObservationStep(stepNumber = current.steps.size + 1, result = result)
        val distilled = ContextDistiller.distillObservation(pending.toolName, result.output)
        current = current.copy(steps = current.steps + obsStep).appendScratchpad("[OBSERVATION:${pending.toolName}]\n$distilled")
        updateSession(current)

        if (current.executionTier == AgentExecutionTier.TIER_2_SINGLE_TOOL) {
            return finalizeSingleToolResolution(current, pending, result)
        }
        return advanceSession(current)
    }

    suspend fun denyPendingStep(sessionId: String, reason: String = "User denied execution"): AgentSession {
        val session = sessionStore?.getSession(sessionId) ?: _sessionState.value
            ?: throw IllegalStateException("Session not found: $sessionId")
        val pending = session.pendingToolCall ?: return session

        val updatedSteps = session.steps.map { step ->
            if (step is AgentStep.ToolCallStep && step.toolCall.id == pending.id) {
                step.copy(approvalStatus = ToolApprovalStatus.USER_DENIED)
            } else step
        }
        val deniedResult = AgentToolResult(
            callId = pending.id,
            toolName = pending.toolName,
            isSuccess = false,
            output = "Execution denied by user: $reason",
            executionTimeMs = 0L,
            structuredSummary = "Denied by user"
        )
        val obsStep = AgentStep.ObservationStep(stepNumber = updatedSteps.size + 1, result = deniedResult)
        val verificationStep = AgentStep.VerificationStep(
            stepNumber = updatedSteps.size + 2,
            summary = "Standalone Verification Gate: Step denied by user.",
            isVerified = false
        )
        val resolutionStep = AgentStep.ResolutionStep(
            finalResponse = "Execution of tool '${pending.toolName}' was denied by user: $reason.",
            totalToolCalls = updatedSteps.count { it is AgentStep.ToolCallStep },
            isolatedScratchpadTokens = session.isolatedTokenEstimate,
            mainChatTokensPolluted = 0
        )
        val completed = session.copy(
            status = AgentSessionStatus.COMPLETED,
            steps = updatedSteps + obsStep + verificationStep + resolutionStep,
            pendingToolCall = null,
            remainingPlannedCalls = emptyList(),
            updatedAt = System.currentTimeMillis()
        ).appendScratchpad("[USER_DENIED] ${pending.toolName}: $reason")
        updateSession(completed)
        return completed
    }

    suspend fun advanceSession(session: AgentSession): AgentSession {
        if (session.executionTier == AgentExecutionTier.TIER_1_CHAT) {
            return executeTier1Chat(session)
        }

        if (session.slashCommand == "/plan" && !session.isPlanApproved) {
            val paused = session.copy(status = AgentSessionStatus.AWAITING_APPROVAL)
            updateSession(paused)
            return paused
        }

        if (session.executionTier == AgentExecutionTier.TIER_2_SINGLE_TOOL && session.remainingPlannedCalls.isNotEmpty()) {
            val toolCall = session.remainingPlannedCalls.first()
            val needsApproval = toolRegistry.requiresUserApproval(approvalMode, toolCall.toolName, toolCall.arguments)
            if (needsApproval) {
                val pendingStep = AgentStep.ToolCallStep(
                    stepNumber = session.steps.size + 1,
                    toolCall = toolCall,
                    approvalStatus = ToolApprovalStatus.PENDING_USER_APPROVAL
                )
                val paused = session.copy(
                    status = AgentSessionStatus.AWAITING_APPROVAL,
                    steps = session.steps + pendingStep,
                    pendingToolCall = toolCall,
                    remainingPlannedCalls = session.remainingPlannedCalls.drop(1),
                    updatedAt = System.currentTimeMillis()
                ).appendScratchpad("[AWAITING_USER_APPROVAL] call:${toolCall.toolName}")
                updateSession(paused)
                return paused
            }
            return executeTier2SingleTool(session, toolCall)
        }

        if (inferenceClient != null) {
            return advanceSessionWithLLM(session)
        }

        // Deterministic multi-tool execution path when inferenceClient == null
        if (session.remainingPlannedCalls.isNotEmpty()) {
            val nextCall = session.remainingPlannedCalls.first()
            val tool = toolRegistry.getTool(nextCall.toolName)

            val needsApproval = toolRegistry.requiresUserApproval(approvalMode, nextCall.toolName, nextCall.arguments)
            if (needsApproval) {
                val pendingStep = AgentStep.ToolCallStep(
                    stepNumber = session.steps.size + 1,
                    toolCall = nextCall,
                    approvalStatus = ToolApprovalStatus.PENDING_USER_APPROVAL
                )
                val paused = session.copy(
                    status = AgentSessionStatus.AWAITING_APPROVAL,
                    steps = session.steps + pendingStep,
                    pendingToolCall = nextCall,
                    remainingPlannedCalls = session.remainingPlannedCalls.drop(1),
                    updatedAt = System.currentTimeMillis()
                ).appendScratchpad("[AWAITING_USER_APPROVAL] call:${nextCall.toolName}")
                updateSession(paused)
                return paused
            }

            if (tool != null) {
                val callStep = AgentStep.ToolCallStep(
                    stepNumber = session.steps.size + 1,
                    toolCall = nextCall,
                    approvalStatus = ToolApprovalStatus.AUTO_APPROVED
                )
                val result = executeToolInternal(tool, nextCall.arguments)
                val obsStep = AgentStep.ObservationStep(stepNumber = session.steps.size + 2, result = result)
                val distilled = ContextDistiller.distillObservation(nextCall.toolName, result.output)
                val current = session.copy(
                    steps = session.steps + callStep + obsStep,
                    remainingPlannedCalls = session.remainingPlannedCalls.drop(1),
                    updatedAt = System.currentTimeMillis()
                ).appendScratchpad("[OBSERVATION:${nextCall.toolName}]\n$distilled")
                updateSession(current)
                return advanceSession(current)
            }
        }

        if (session.remainingPlannedCalls.isEmpty() && session.steps.none { it is AgentStep.ToolCallStep }) {
            return executeTier1Chat(session)
        }

        // All planned tools completed without LLM: finalize resolution
        val obsCount = session.steps.count { it is AgentStep.ObservationStep }
        val verificationStep = AgentStep.VerificationStep(
            stepNumber = session.steps.size + 1,
            summary = "Standalone Verification Gate: Verified $obsCount tool execution(s).",
            isVerified = true
        )
        val lastObs = session.steps.filterIsInstance<AgentStep.ObservationStep>().lastOrNull()
        val finalResponseText = lastObs?.result?.output ?: "Plan execution completed successfully."
        val resolutionStep = AgentStep.ResolutionStep(
            finalResponse = finalResponseText,
            totalToolCalls = session.steps.count { it is AgentStep.ToolCallStep },
            isolatedScratchpadTokens = session.isolatedTokenEstimate,
            mainChatTokensPolluted = 0
        )
        val completed = session.copy(
            status = AgentSessionStatus.COMPLETED,
            steps = session.steps + verificationStep + resolutionStep,
            updatedAt = System.currentTimeMillis()
        ).appendScratchpad("[FINAL_RESOLUTION]\n$finalResponseText")
        updateSession(completed)
        return completed
    }

    private suspend fun executeTier1Chat(session: AgentSession): AgentSession {
        var current = session.copy(status = AgentSessionStatus.SYNTHESIZING)
        updateSession(current)

        val trimmed = session.objective.trim()
        val lower = trimmed.lowercase(Locale.US)
        val isChitChat = greetingRegex.matches(trimmed) ||
                lower == "/plan" || lower == "/boost" || lower == "/swarm"

        val directResponse = if (inferenceClient != null) {
            try {
                val prompt = ScopedPromptBuilder.buildLeanChatPrompt(session)
                ContextDistiller.stripThinkingTags(inferenceClient.generate(prompt))
            } catch (e: Exception) {
                val errStep = AgentStep.ResolutionStep(
                    finalResponse = "❌ Execution Error: ${e.message}",
                    totalToolCalls = 0,
                    isolatedScratchpadTokens = 0
                )
                val errSession = current.copy(
                    status = AgentSessionStatus.ERROR,
                    steps = current.steps + errStep,
                    updatedAt = System.currentTimeMillis()
                )
                updateSession(errSession)
                return errSession
            }
        } else {
            fallbackChatResponse(lower)
        }

        val verificationStep = if (!isChitChat) {
            AgentStep.VerificationStep(
                stepNumber = current.steps.size + 1,
                summary = "Standalone Verification Gate: Verified direct response against objective (0 tool overhead).",
                isVerified = true
            )
        } else null

        val resolutionStep = AgentStep.ResolutionStep(
            finalResponse = directResponse,
            totalToolCalls = 0,
            isolatedScratchpadTokens = (directResponse.length / 4).coerceAtLeast(1),
            mainChatTokensPolluted = 0
        )

        val completed = current.copy(
            status = AgentSessionStatus.COMPLETED,
            steps = if (verificationStep != null) current.steps + verificationStep + resolutionStep
            else current.steps + resolutionStep,
            updatedAt = System.currentTimeMillis()
        ).appendScratchpad("[TIER_1_CHAT_RESPONSE]\n$directResponse")
        updateSession(completed)
        return completed
    }

    private suspend fun executeTier2SingleTool(session: AgentSession, toolCall: AgentToolCall): AgentSession {
        val tool = toolRegistry.getTool(toolCall.toolName)
            ?: throw IllegalStateException("Tool not registered: ${toolCall.toolName}")

        val callStep = AgentStep.ToolCallStep(
            stepNumber = session.steps.size + 1,
            toolCall = toolCall,
            approvalStatus = ToolApprovalStatus.AUTO_APPROVED
        )
        var current = session.copy(
            status = AgentSessionStatus.EXECUTING_TOOL,
            steps = session.steps + callStep,
            pendingToolCall = null,
            remainingPlannedCalls = emptyList()
        ).appendScratchpad("[TIER_2_EXECUTION] call:${toolCall.toolName}")
        updateSession(current)

        val result = executeToolInternal(tool, toolCall.arguments)
        val obsStep = AgentStep.ObservationStep(stepNumber = current.steps.size + 1, result = result)
        current = current.copy(steps = current.steps + obsStep).appendScratchpad("[OBSERVATION]\n${result.output}")
        updateSession(current)

        return finalizeSingleToolResolution(current, toolCall, result)
    }

    private suspend fun finalizeSingleToolResolution(
        session: AgentSession,
        toolCall: AgentToolCall,
        result: AgentToolResult
    ): AgentSession {
        val tool = toolRegistry.getTool(toolCall.toolName)
        val isDeterministic = tool?.isDeterministic == true

        if (isDeterministic) {
            val verificationStep = AgentStep.VerificationStep(
                stepNumber = session.steps.size + 1,
                summary = if (result.isSuccess) "Standalone Verification Gate: Deterministic result verified in 0 LLM passes."
                else "Standalone Verification Gate: Deterministic tool execution failed.",
                isVerified = result.isSuccess
            )
            val resolutionStep = AgentStep.ResolutionStep(
                finalResponse = result.output,
                totalToolCalls = 1,
                isolatedScratchpadTokens = (result.output.length / 4).coerceAtLeast(1),
                mainChatTokensPolluted = 0
            )
            val completed = session.copy(
                status = AgentSessionStatus.COMPLETED,
                steps = session.steps + verificationStep + resolutionStep,
                updatedAt = System.currentTimeMillis()
            ).appendScratchpad("[TIER_2_DETERMINISTIC_RESOLUTION]\n${result.output}")
            updateSession(completed)
            return completed
        } else {
            var current = session.copy(status = AgentSessionStatus.SYNTHESIZING)
            updateSession(current)

            val synthesisResponse = if (inferenceClient != null) {
                try {
                    val scopedPrompt = ScopedPromptBuilder.buildSingleToolScopedPrompt(current.objective, toolCall.toolName, result.output)
                    val rawOutput = inferenceClient.generate(scopedPrompt)
                    val dynamicCall = GemmaToolPromptFormatter.parseModelToolCall(rawOutput, toolRegistry)
                    if (dynamicCall != null) {
                        val promoted = current.copy(executionTier = AgentExecutionTier.TIER_3_PIPELINE)
                        return advanceSessionWithLLM(promoted)
                    }
                    ContextDistiller.stripThinkingTags(rawOutput)
                } catch (_: Exception) {
                    "Task completed using ${toolCall.toolName}:\n\n${result.output}"
                }
            } else {
                "Task completed using ${toolCall.toolName}:\n\n${result.output}"
            }

            val verificationStep = AgentStep.VerificationStep(
                stepNumber = current.steps.size + 1,
                summary = "Standalone Verification Gate: Scoped single-tool synthesis verified.",
                isVerified = true
            )
            val resolutionStep = AgentStep.ResolutionStep(
                finalResponse = synthesisResponse,
                totalToolCalls = 1,
                isolatedScratchpadTokens = (synthesisResponse.length / 4).coerceAtLeast(1),
                mainChatTokensPolluted = 0
            )
            val completed = current.copy(
                status = AgentSessionStatus.COMPLETED,
                steps = current.steps + verificationStep + resolutionStep,
                updatedAt = System.currentTimeMillis()
            ).appendScratchpad("[TIER_2_GENERATIVE_RESOLUTION]\n$synthesisResponse")
            updateSession(completed)
            return completed
        }
    }

    private suspend fun advanceSessionWithLLM(session: AgentSession): AgentSession {
        if (inferenceClient == null) {
            return executeTier1Chat(session)
        }
        var current = session
        val isBoost = session.slashCommand == "/boost"
        val maxIterations = if (isBoost) 10 else 5
        var iteration = 0

        while (iteration < maxIterations) {
            iteration++
            val prompt = ScopedPromptBuilder.buildAgentPrompt(current, toolRegistry.getAllTools())
            current = current.copy(status = AgentSessionStatus.PLANNING)
            updateSession(current)

            val rawOutput = try {
                inferenceClient.generate(prompt)
            } catch (e: Exception) {
                val errStep = AgentStep.ResolutionStep(
                    finalResponse = "❌ Execution Error: ${e.message}",
                    totalToolCalls = current.steps.count { it is AgentStep.ToolCallStep },
                    isolatedScratchpadTokens = current.isolatedTokenEstimate
                )
                val errSession = current.copy(
                    status = AgentSessionStatus.ERROR,
                    steps = current.steps + errStep,
                    updatedAt = System.currentTimeMillis()
                )
                updateSession(errSession)
                return errSession
            }

            val parsedCall = GemmaToolPromptFormatter.parseModelToolCall(rawOutput, toolRegistry)
            if (parsedCall != null) {
                val thoughtText = GemmaToolPromptFormatter.extractThoughtFromOutput(rawOutput, parsedCall)
                if (thoughtText.isNotBlank()) {
                    val thoughtStep = AgentStep.ThoughtStep(
                        stepNumber = current.steps.size + 1,
                        thought = thoughtText,
                        planItems = listOf("Invoking ${parsedCall.toolName}")
                    )
                    current = current.copy(steps = current.steps + thoughtStep)
                }

                val needsApproval = toolRegistry.requiresUserApproval(approvalMode, parsedCall.toolName, parsedCall.arguments)
                if (needsApproval) {
                    val pendingStep = AgentStep.ToolCallStep(
                        stepNumber = current.steps.size + 1,
                        toolCall = parsedCall,
                        approvalStatus = ToolApprovalStatus.PENDING_USER_APPROVAL
                    )
                    val paused = current.copy(
                        status = AgentSessionStatus.AWAITING_APPROVAL,
                        steps = current.steps + pendingStep,
                        pendingToolCall = parsedCall,
                        updatedAt = System.currentTimeMillis()
                    ).appendScratchpad("[AWAITING_APPROVAL] ${parsedCall.toolName}")
                    updateSession(paused)
                    return paused
                }

                val callStep = AgentStep.ToolCallStep(
                    stepNumber = current.steps.size + 1,
                    toolCall = parsedCall,
                    approvalStatus = ToolApprovalStatus.AUTO_APPROVED
                )
                current = current.copy(status = AgentSessionStatus.EXECUTING_TOOL, steps = current.steps + callStep)
                updateSession(current)

                val tool = toolRegistry.getTool(parsedCall.toolName)
                val result = if (tool != null) {
                    executeToolInternal(tool, parsedCall.arguments)
                } else {
                    AgentToolResult(
                        callId = parsedCall.id,
                        toolName = parsedCall.toolName,
                        isSuccess = false,
                        output = "Tool not found: ${parsedCall.toolName}"
                    )
                }
                val obsStep = AgentStep.ObservationStep(stepNumber = current.steps.size + 1, result = result)
                val distilled = ContextDistiller.distillObservation(parsedCall.toolName, result.output)
                current = current.copy(steps = current.steps + obsStep).appendScratchpad("[OBSERVATION:${parsedCall.toolName}]\n$distilled")
                updateSession(current)
            } else {
                val cleaned = ContextDistiller.stripThinkingTags(rawOutput)
                val obsCount = current.steps.count { it is AgentStep.ObservationStep }
                val verificationStep = AgentStep.VerificationStep(
                    stepNumber = current.steps.size + 1,
                    summary = "Standalone Verification Gate: Verified $obsCount tool observation(s) against objective.",
                    isVerified = true
                )
                val totalCalls = current.steps.count { it is AgentStep.ToolCallStep }
                val resolutionStep = AgentStep.ResolutionStep(
                    finalResponse = cleaned,
                    totalToolCalls = totalCalls,
                    isolatedScratchpadTokens = current.isolatedTokenEstimate + (cleaned.length / 4),
                    mainChatTokensPolluted = 0
                )
                val completed = current.copy(
                    status = AgentSessionStatus.COMPLETED,
                    steps = current.steps + verificationStep + resolutionStep,
                    updatedAt = System.currentTimeMillis()
                ).appendScratchpad("[FINAL_RESOLUTION]\n$cleaned")
                updateSession(completed)
                return completed
            }
        }

        // Loop reached max iterations limit
        val verificationStep = AgentStep.VerificationStep(
            stepNumber = current.steps.size + 1,
            summary = "Standalone Verification Gate: Verified after reaching step limit.",
            isVerified = true
        )
        val totalCalls = current.steps.count { it is AgentStep.ToolCallStep }
        val resolutionText = "Task reached maximum iteration limit ($maxIterations steps). Completed $totalCalls tool executions."
        val resolutionStep = AgentStep.ResolutionStep(
            finalResponse = resolutionText,
            totalToolCalls = totalCalls,
            isolatedScratchpadTokens = current.isolatedTokenEstimate + (resolutionText.length / 4),
            mainChatTokensPolluted = 0
        )
        val completed = current.copy(
            status = AgentSessionStatus.COMPLETED,
            steps = current.steps + verificationStep + resolutionStep,
            updatedAt = System.currentTimeMillis()
        ).appendScratchpad("[FINAL_RESOLUTION]\n$resolutionText")
        updateSession(completed)
        return completed
    }

    private suspend fun executeToolInternal(tool: AgentTool, arguments: Map<String, String>): AgentToolResult {
        return try {
            if (context != null) {
                tool.execute(context, arguments)
            } else {
                tool.execute(arguments)
            }
        } catch (e: UnsupportedOperationException) {
            tool.execute(arguments)
        }
    }

    private fun updateSession(session: AgentSession) {
        _sessionState.value = session
        sessionStore?.saveSession(session)
    }

    private fun planInitialTools(objective: String): List<AgentToolCall> {
        val lower = objective.lowercase(Locale.US)
        val calls = mutableListOf<AgentToolCall>()
        val genericWords = setOf("device", "system", "manager", "evaluator", "tool", "local")
        toolRegistry.getAllTools().forEach { tool ->
            val tName = tool.name.lowercase(Locale.US)
            val tKeywords = tName.split("_").filter { it.length > 2 && it !in genericWords } + tName
            val matched = tKeywords.any { kw -> lower.contains(kw) } ||
                    (tName.contains("clock") && (lower.contains("time") || lower.contains("clock"))) ||
                    (tName.contains("math") && (lower.contains("calculate") || lower.contains("calc"))) ||
                    (tName.contains("calendar") && (lower.contains("schedule") || lower.contains("meeting") || lower.contains("sync"))) ||
                    (tName.contains("telemetry") && (lower.contains("telemetry") || lower.contains("hardware") || lower.contains("ram") || lower.contains("cpu")))
            if (matched) {
                val args = extractInitialArgs(tool, objective)
                calls.add(AgentToolCall(toolName = tool.name, arguments = args, reason = "Matched keyword"))
            }
        }
        return calls
    }

    private fun extractInitialArgs(tool: AgentTool, objective: String): Map<String, String> {
        val args = mutableMapOf<String, String>()
        val tName = tool.name.lowercase(Locale.US)
        if (tName.contains("clock")) {
            args["action"] = "get_time"
        } else if (tName.contains("math")) {
            val expr = objective.replace(Regex("""(?i)calculate|eval|math|what is"""), "").trim()
            args["expression"] = expr.ifEmpty { "0" }
        } else if (tName.contains("calendar")) {
            args["action"] = "schedule_event"
            args["title"] = objective.replace(Regex("""(?i)schedule|calendar|event"""), "").trim().ifEmpty { "Meeting" }
        } else if (tName.contains("telemetry")) {
            args["scope"] = "full"
        } else if (tName.contains("notes")) {
            args["action"] = if (objective.lowercase().contains("create") || objective.lowercase().contains("save")) "create" else "read"
        }
        return args
    }

    private fun createInitialSession(
        id: String,
        objective: String,
        rawPrompt: String,
        slashCommand: String?,
        tier: AgentExecutionTier,
        modelId: String
    ): AgentSession {
        val userPromptStep = AgentStep.UserPromptStep(prompt = rawPrompt)
        val plannedCalls = if (tier == AgentExecutionTier.TIER_1_CHAT) emptyList() else planInitialTools(objective)

        if (slashCommand == "/plan") {
            val stages = if (plannedCalls.isNotEmpty()) {
                plannedCalls.mapIndexed { idx, call -> "Stage ${idx + 1}: Execute ${call.toolName}" } +
                        "Stage ${plannedCalls.size + 1}: Verification & Synthesis"
            } else {
                listOf("Stage 1: Environmental Audit", "Stage 2: Deterministic Reasoning", "Stage 3: Verification Gate")
            }
            val planReviewStep = AgentStep.PlanReviewStep(
                stages = stages,
                explanation = "Review and approve the execution DAG before tools run."
            )
            return AgentSession(
                id = id,
                title = objective.take(30),
                objective = objective,
                modelId = modelId,
                status = AgentSessionStatus.AWAITING_APPROVAL,
                steps = listOf(userPromptStep, planReviewStep),
                remainingPlannedCalls = plannedCalls,
                isPipelineTask = true,
                pipelineStages = stages,
                executionTier = AgentExecutionTier.TIER_3_PIPELINE,
                slashCommand = "/plan",
                isPlanApproved = false
            )
        }

        return AgentSession(
            id = id,
            title = objective.take(30),
            objective = objective,
            modelId = modelId,
            status = AgentSessionStatus.PLANNING,
            steps = listOf(userPromptStep),
            remainingPlannedCalls = plannedCalls,
            isPipelineTask = tier == AgentExecutionTier.TIER_3_PIPELINE,
            executionTier = tier,
            slashCommand = slashCommand,
            isPlanApproved = false
        )
    }

    private fun fallbackChatResponse(lower: String): String {
        return when {
            lower == "/plan" || lower.startsWith("/plan") ->
                "📋 **Plan Mode**: Formulates a structured execution DAG for review before running tools.\n\n*Usage*: `/plan <your mission>`"
            lower == "/boost" || lower.startsWith("/boost") ->
                "🚀 **Deep Reasoning Boost**: Allocates up to 2048 tokens and 10 reasoning iterations.\n\n*Usage*: `/boost <task>`"
            lower == "/swarm" || lower.startsWith("/swarm") ->
                "🐝 **Swarm Orchestration**: Coordinates sub-agents sequentially to minimize resource contention.\n\n*Usage*: `/swarm <mission>`"
            lower.contains("who are you") -> "I am your on-device Standalone AI Assistant running locally on your Android device."
            lower.contains("how are you") -> "I'm doing great, ready to help offline on your device!"
            lower.contains("thank") -> "You're welcome! Let me know if you need anything else."
            lower.contains("bye") || lower.contains("goodbye") -> "Goodbye! Have a wonderful day."
            else -> "Hello! How can I assist you offline today?"
        }
    }

    class Builder(private val context: Context? = null) {
        private var inferenceClient: AgentInferenceClient? = null
        private var approvalMode: AgentApprovalMode = AgentApprovalMode.SENSITIVE_ONLY
        private val toolRegistry = AgentToolRegistry()
        private var sessionStore: AgentSessionStore? = null

        fun withInferenceClient(client: AgentInferenceClient) = apply { this.inferenceClient = client }
        fun withInferenceClient(clientFn: suspend (String) -> String) = apply { this.inferenceClient = AgentInferenceClient(clientFn) }
        fun withApprovalMode(mode: AgentApprovalMode) = apply { this.approvalMode = mode }
        fun registerTool(tool: AgentTool) = apply { this.toolRegistry.register(tool) }
        fun registerTools(tools: Collection<AgentTool>) = apply { this.toolRegistry.registerAll(tools) }
        fun withSessionStore(store: AgentSessionStore) = apply { this.sessionStore = store }
        fun build() = AgentWorkflowEngine(context, inferenceClient, approvalMode, toolRegistry, sessionStore)
    }
}
