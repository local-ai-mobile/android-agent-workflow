package dev.localai.agent.workflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.localai.agent.workflow.core.AgentApprovalMode
import dev.localai.agent.workflow.core.AgentExecutionTier
import dev.localai.agent.workflow.core.AgentWorkflowEngine
import dev.localai.agent.workflow.model.AgentSessionStatus
import dev.localai.agent.workflow.model.AgentStep
import dev.localai.agent.workflow.tool.AgentTool
import dev.localai.agent.workflow.tool.AgentToolParameter
import dev.localai.agent.workflow.tool.AgentToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecutionTierTest {

    private lateinit var context: Context

    private val clockTool = object : AgentTool {
        override val name = "device_clock_timer"
        override val description = "Returns system clock time"
        override val parameters = listOf(AgentToolParameter("action", "string", "clock action"))
        override val isSensitive = false
        override val isDeterministic = true
        override fun isCallSensitive(arguments: Map<String, String>) = false
        override suspend fun execute(context: Context, arguments: Map<String, String>): AgentToolResult {
            return AgentToolResult(name, true, "2026-11-23T20:00:00Z (UTC)")
        }
    }

    private val mathTool = object : AgentTool {
        override val name = "math_evaluator"
        override val description = "Calculates math expressions"
        override val parameters = listOf(AgentToolParameter("expression", "string", "Expression"))
        override val isSensitive = false
        override val isDeterministic = true
        override fun isCallSensitive(arguments: Map<String, String>) = false
        override suspend fun execute(context: Context, arguments: Map<String, String>): AgentToolResult {
            return AgentToolResult(name, true, "512")
        }
    }

    private val telemetryTool = object : AgentTool {
        override val name = "device_telemetry"
        override val description = "Hardware profile"
        override val parameters = listOf(AgentToolParameter("scope", "string", "Scope"))
        override val isSensitive = false
        override val isDeterministic = false
        override fun isCallSensitive(arguments: Map<String, String>) = false
        override suspend fun execute(context: Context, arguments: Map<String, String>): AgentToolResult {
            return AgentToolResult(name, true, "RAM: 4.2GB free, Cores: 8 (4 Cortex-X4 / 4 A720)")
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testTier1ChatFastPathZeroToolsAndNoVerificationGate() = runBlocking {
        val greetings = listOf("hi", "hello", "good morning", "thanks", "who are you")

        val engine = AgentWorkflowEngine.Builder(context)
            .withApprovalMode(AgentApprovalMode.AUTO_DECIDE)
            .registerTool(clockTool)
            .registerTool(mathTool)
            .build()

        for (greeting in greetings) {
            val session = engine.executeObjective(greeting)

            assertEquals(AgentExecutionTier.TIER_1_CHAT, session.executionTier)
            assertEquals(AgentSessionStatus.COMPLETED, session.status)
            assertEquals(0, session.steps.filterIsInstance<AgentStep.ToolCallStep>().size)
            assertFalse(
                "Tier 1 chit-chat must not contain verification gate step",
                session.steps.any { it is AgentStep.VerificationStep }
            )
            val res = session.steps.filterIsInstance<AgentStep.ResolutionStep>().firstOrNull()
            assertNotNull(res)
            assertTrue(res!!.finalResponse.isNotBlank())
        }
    }

    @Test
    fun testTier2DeterministicInstantClockAndMathZeroLLMPasses() = runBlocking {
        var llmCalled = false
        val mockLLM: suspend (String) -> String = {
            llmCalled = true
            "LLM was invoked"
        }

        val engine = AgentWorkflowEngine.Builder(context)
            .withInferenceClient(mockLLM)
            .withApprovalMode(AgentApprovalMode.AUTO_DECIDE)
            .registerTool(clockTool)
            .registerTool(mathTool)
            .build()

        // Clock
        val clockSession = engine.executeObjective("What time is it?")
        assertEquals(AgentExecutionTier.TIER_2_SINGLE_TOOL, clockSession.executionTier)
        assertEquals(AgentSessionStatus.COMPLETED, clockSession.status)
        assertFalse("Deterministic clock tool must not invoke LLM", llmCalled)
        val clockRes = clockSession.steps.filterIsInstance<AgentStep.ResolutionStep>().firstOrNull()
        assertNotNull(clockRes)
        assertTrue(clockRes!!.finalResponse.contains("2026-11-23"))

        // Math
        val mathSession = engine.executeObjective("Calculate 2048 / 4")
        assertEquals(AgentExecutionTier.TIER_2_SINGLE_TOOL, mathSession.executionTier)
        assertEquals(AgentSessionStatus.COMPLETED, mathSession.status)
        assertFalse("Deterministic math tool must not invoke LLM", llmCalled)
        val mathRes = mathSession.steps.filterIsInstance<AgentStep.ResolutionStep>().firstOrNull()
        assertNotNull(mathRes)
        assertTrue(mathRes!!.finalResponse.contains("512"))
    }

    @Test
    fun testTier2GenerativeSingleToolScopedPrompt() = runBlocking {
        var capturedPrompt = ""
        val mockLLM: suspend (String) -> String = { prompt ->
            capturedPrompt = prompt
            "Device status: System running smoothly with 4.2GB free RAM."
        }

        val engine = AgentWorkflowEngine.Builder(context)
            .withInferenceClient(mockLLM)
            .withApprovalMode(AgentApprovalMode.AUTO_DECIDE)
            .registerTool(telemetryTool)
            .registerTool(mathTool)
            .registerTool(clockTool)
            .build()

        val session = engine.executeObjective("Check device telemetry")

        assertEquals(AgentExecutionTier.TIER_2_SINGLE_TOOL, session.executionTier)
        assertEquals(AgentSessionStatus.COMPLETED, session.status)
        assertFalse(
            "Scoped prompt must NOT be bloated with full tool schema declarations",
            capturedPrompt.contains("<|tool_declarations|>")
        )
        assertTrue(
            "Scoped prompt must contain active observation",
            capturedPrompt.contains("4.2GB free")
        )
    }

    @Test
    fun testTier3PipelineMultiToolTask() = runBlocking {
        val engine = AgentWorkflowEngine.Builder(context)
            .withApprovalMode(AgentApprovalMode.NEVER_ASK)
            .registerTool(telemetryTool)
            .registerTool(mathTool)
            .build()

        val session = engine.executeObjective("Check device telemetry and calculate memory headroom")
        assertEquals(AgentExecutionTier.TIER_3_PIPELINE, session.executionTier)
        assertTrue(session.isPipelineTask)
    }
}
