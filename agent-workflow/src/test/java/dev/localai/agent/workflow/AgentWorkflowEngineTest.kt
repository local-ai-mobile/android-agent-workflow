package dev.localai.agent.workflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.localai.agent.workflow.core.AgentApprovalMode
import dev.localai.agent.workflow.core.AgentWorkflowEngine
import dev.localai.agent.workflow.model.AgentSessionStatus
import dev.localai.agent.workflow.model.AgentStep
import dev.localai.agent.workflow.tool.AgentTool
import dev.localai.agent.workflow.tool.AgentToolParameter
import dev.localai.agent.workflow.tool.AgentToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentWorkflowEngineTest {

    private lateinit var context: Context

    private val testTelemetryTool = object : AgentTool {
        override val name = "device_telemetry"
        override val description = "Profiles hardware, battery, and memory"
        override val parameters = listOf(AgentToolParameter("scope", "string", "Scope of telemetry"))
        override val isSensitive = false
        override fun isCallSensitive(arguments: Map<String, String>) = false
        override suspend fun execute(context: Context, arguments: Map<String, String>): AgentToolResult {
            return AgentToolResult(
                toolName = name,
                isSuccess = true,
                output = "Device Telemetry: 4.2 GB free RAM, Battery 88%, Thermal NORMAL."
            )
        }
    }

    private val testNotesTool = object : AgentTool {
        override val name = "notes_manager"
        override val description = "Manages notes vault"
        override val parameters = listOf(
            AgentToolParameter("action", "string", "Action to perform"),
            AgentToolParameter("query", "string", "Search query or note title", required = false)
        )
        override val isSensitive = true
        override fun isCallSensitive(arguments: Map<String, String>): Boolean {
            return arguments["action"] == "create" || arguments["action"] == "delete"
        }
        override suspend fun execute(context: Context, arguments: Map<String, String>): AgentToolResult {
            return AgentToolResult(
                toolName = name,
                isSuccess = true,
                output = "Notes Manager: Found 3 notes relating to local AI architecture."
            )
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testReActLoopPlanExecuteObserveVerify() = runBlocking {
        var callCount = 0
        val mockInferenceClient: suspend (String) -> String = { prompt ->
            callCount++
            when (callCount) {
                1 -> "Let me check device telemetry first. call:device_telemetry{scope=\"full\"}"
                2 -> "Now I will summarize findings. call:notes_manager{action=\"search\", query=\"architecture\"}"
                else -> "Based on telemetry and notes, your device has sufficient RAM and notes are indexed."
            }
        }

        val engine = AgentWorkflowEngine.Builder(context)
            .withInferenceClient(mockInferenceClient)
            .withApprovalMode(AgentApprovalMode.NEVER_ASK)
            .registerTool(testTelemetryTool)
            .registerTool(testNotesTool)
            .build()

        val session = engine.executeObjective("Check telemetry and summarize notes")

        assertEquals(AgentSessionStatus.COMPLETED, session.status)
        val toolCalls = session.steps.filterIsInstance<AgentStep.ToolCallStep>()
        assertEquals(2, toolCalls.size)
        assertEquals("device_telemetry", toolCalls[0].toolCall.toolName)
        assertEquals("notes_manager", toolCalls[1].toolCall.toolName)

        val observations = session.steps.filterIsInstance<AgentStep.ObservationStep>()
        assertEquals(2, observations.size)
        assertTrue(observations[0].result.output.contains("4.2 GB free RAM"))
        assertTrue(observations[1].result.output.contains("3 notes relating to local AI"))

        val verification = session.steps.filterIsInstance<AgentStep.VerificationStep>().firstOrNull()
        assertNotNull(verification)
        assertTrue(verification!!.isVerified)

        val resolution = session.steps.filterIsInstance<AgentStep.ResolutionStep>().firstOrNull()
        assertNotNull(resolution)
        assertTrue(resolution!!.finalResponse.contains("Based on telemetry and notes"))
        assertEquals(0, resolution.mainChatTokensPolluted)
        assertTrue(resolution.isolatedScratchpadTokens > 0)
    }

    @Test
    fun testStandardExecutionCapsAtFiveTurns() = runBlocking {
        var iterations = 0
        val infiniteToolClient: suspend (String) -> String = {
            iterations++
            "Iterating step $iterations. call:device_telemetry{scope=\"loop\"}"
        }

        val engine = AgentWorkflowEngine.Builder(context)
            .withInferenceClient(infiniteToolClient)
            .withApprovalMode(AgentApprovalMode.NEVER_ASK)
            .registerTool(testTelemetryTool)
            .build()

        val session = engine.executeObjective("Run telemetry continuously")

        assertEquals(AgentSessionStatus.COMPLETED, session.status)
        assertEquals(5, iterations)
        val toolCalls = session.steps.filterIsInstance<AgentStep.ToolCallStep>()
        assertEquals(5, toolCalls.size)

        val verification = session.steps.filterIsInstance<AgentStep.VerificationStep>().firstOrNull()
        assertNotNull(verification)
        assertTrue(verification!!.summary.contains("step limit") || verification.isVerified)
    }

    @Test
    fun testBoostSlashCommandAllowsTenTurns() = runBlocking {
        var iterations = 0
        val infiniteToolClient: suspend (String) -> String = {
            iterations++
            "Boost step $iterations. call:device_telemetry{scope=\"deep\"}"
        }

        val engine = AgentWorkflowEngine.Builder(context)
            .withInferenceClient(infiniteToolClient)
            .withApprovalMode(AgentApprovalMode.NEVER_ASK)
            .registerTool(testTelemetryTool)
            .build()

        val session = engine.executeObjective("/boost Run deep recursive telemetry analysis")

        assertEquals(AgentSessionStatus.COMPLETED, session.status)
        assertEquals(10, iterations)
        val toolCalls = session.steps.filterIsInstance<AgentStep.ToolCallStep>()
        assertEquals(10, toolCalls.size)
    }

    @Test
    fun testInferenceFailureYieldsErrorStatus() = runBlocking {
        val failingClient: suspend (String) -> String = {
            throw RuntimeException("Native inference out of memory")
        }

        val engine = AgentWorkflowEngine.Builder(context)
            .withInferenceClient(failingClient)
            .withApprovalMode(AgentApprovalMode.NEVER_ASK)
            .registerTool(testTelemetryTool)
            .build()

        val session = engine.executeObjective("Execute complex goal")
        assertEquals(AgentSessionStatus.ERROR, session.status)
        val resolution = session.steps.filterIsInstance<AgentStep.ResolutionStep>().firstOrNull()
        assertNotNull(resolution)
        assertTrue(resolution!!.finalResponse.contains("Native inference out of memory"))
    }
}
