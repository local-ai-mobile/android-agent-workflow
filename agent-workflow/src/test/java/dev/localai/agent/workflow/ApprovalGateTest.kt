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
class ApprovalGateTest {

    private lateinit var context: Context

    private val calendarTool = object : AgentTool {
        override val name = "calendar_scheduler"
        override val description = "Schedules events"
        override val parameters = listOf(
            AgentToolParameter("action", "string", "Action"),
            AgentToolParameter("title", "string", "Title")
        )
        override val isSensitive = true
        override fun isCallSensitive(arguments: Map<String, String>) = arguments["action"] == "schedule_event"
        override suspend fun execute(context: Context, arguments: Map<String, String>): AgentToolResult {
            return AgentToolResult(name, true, "Event scheduled: ${arguments["title"]}")
        }
    }

    private val readTool = object : AgentTool {
        override val name = "device_clock_timer"
        override val description = "Reads time"
        override val parameters = listOf(AgentToolParameter("action", "string", "Action"))
        override val isSensitive = false
        override val isDeterministic = true
        override fun isCallSensitive(arguments: Map<String, String>) = false
        override suspend fun execute(context: Context, arguments: Map<String, String>): AgentToolResult {
            return AgentToolResult(name, true, "Current Time: 12:00 PM")
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testAlwaysAskRequiresApprovalForEveryTool() = runBlocking {
        val engine = AgentWorkflowEngine.Builder(context)
            .withApprovalMode(AgentApprovalMode.ALWAYS_ASK)
            .registerTool(readTool)
            .build()

        val session = engine.executeObjective("Check what time it is")
        assertEquals(AgentSessionStatus.AWAITING_APPROVAL, session.status)
        assertNotNull(session.pendingToolCall)
        assertEquals("device_clock_timer", session.pendingToolCall?.toolName)

        val completed = engine.approvePendingStep(session.id)
        assertEquals(AgentSessionStatus.COMPLETED, completed.status)
    }

    @Test
    fun testSensitiveOnlyAutoApprovesBenignAndPausesSensitive() = runBlocking {
        val engine = AgentWorkflowEngine.Builder(context)
            .withApprovalMode(AgentApprovalMode.SENSITIVE_ONLY)
            .registerTool(readTool)
            .registerTool(calendarTool)
            .build()

        // Benign read
        val readSession = engine.executeObjective("Check current time")
        assertEquals(AgentSessionStatus.COMPLETED, readSession.status)

        // Sensitive mutation
        val calSession = engine.executeObjective("Schedule a sync event")
        assertEquals(AgentSessionStatus.AWAITING_APPROVAL, calSession.status)
        assertEquals("calendar_scheduler", calSession.pendingToolCall?.toolName)
    }

    @Test
    fun testNeverAskExecutesSensitiveWithoutPausing() = runBlocking {
        val engine = AgentWorkflowEngine.Builder(context)
            .withApprovalMode(AgentApprovalMode.NEVER_ASK)
            .registerTool(calendarTool)
            .build()

        val session = engine.executeObjective("Schedule a sync event")
        assertEquals(AgentSessionStatus.COMPLETED, session.status)
        assertTrue(session.steps.any { it is AgentStep.ObservationStep })
    }

    @Test
    fun testDoubleApprovalGatePlanWithAlwaysAsk() = runBlocking {
        val engine = AgentWorkflowEngine.Builder(context)
            .withApprovalMode(AgentApprovalMode.ALWAYS_ASK)
            .registerTool(calendarTool)
            .build()

        // 1. Plan creation pauses at DAG Review (Gate 1)
        val planSession = engine.executeObjective("/plan schedule sprint sync")
        assertEquals(AgentSessionStatus.AWAITING_APPROVAL, planSession.status)
        assertFalse(planSession.isPlanApproved)
        assertTrue(planSession.steps.any { it is AgentStep.PlanReviewStep })

        // 2. Approve plan DAG -> advances to sensitive tool call which pauses under ALWAYS_ASK (Gate 2)
        val toolPausedSession = engine.approvePlan(planSession.id)
        assertTrue(toolPausedSession.isPlanApproved)
        assertEquals(AgentSessionStatus.AWAITING_APPROVAL, toolPausedSession.status)
        assertNotNull(toolPausedSession.pendingToolCall)
        assertEquals("calendar_scheduler", toolPausedSession.pendingToolCall?.toolName)

        // 3. Approve individual tool call -> completes execution
        val completedSession = engine.approvePendingStep(toolPausedSession.id)
        assertEquals(AgentSessionStatus.COMPLETED, completedSession.status)
        assertTrue(completedSession.steps.any { it is AgentStep.ObservationStep })
    }

    @Test
    fun testDenyPendingStepSynthesizesGracefully() = runBlocking {
        val engine = AgentWorkflowEngine.Builder(context)
            .withApprovalMode(AgentApprovalMode.ALWAYS_ASK)
            .registerTool(calendarTool)
            .build()

        val paused = engine.executeObjective("Schedule a team sync")
        assertEquals(AgentSessionStatus.AWAITING_APPROVAL, paused.status)

        val denied = engine.denyPendingStep(paused.id, "User cancelled operation")
        assertEquals(AgentSessionStatus.COMPLETED, denied.status)
        val obs = denied.steps.filterIsInstance<AgentStep.ObservationStep>().firstOrNull()
        assertNotNull(obs)
        assertFalse(obs!!.result.isSuccess)
        assertTrue(obs.result.output.contains("denied"))
    }
}
