package dev.localai.agent.workflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.localai.agent.workflow.core.AgentExecutionTier
import dev.localai.agent.workflow.model.AgentSession
import dev.localai.agent.workflow.model.AgentSessionStatus
import dev.localai.agent.workflow.model.AgentStep
import dev.localai.agent.workflow.model.SubAgentDelegation
import dev.localai.agent.workflow.model.ToolApprovalStatus
import dev.localai.agent.workflow.store.AgentSessionStore
import dev.localai.agent.workflow.store.StepJsonSerializer
import dev.localai.agent.workflow.tool.AgentToolCall
import dev.localai.agent.workflow.tool.AgentToolResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionStoreTest {

    private lateinit var context: Context
    private lateinit var store: AgentSessionStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AgentSessionStore(context)
        store.clearAll()
    }

    @After
    fun tearDown() {
        store.clearAll()
    }

    @Test
    fun testAllEightStepTypesSerializationRoundtrip() {
        val subAgent = SubAgentDelegation(name = "Telemetry Agent", role = "Auditor", description = "Profiles device")
        val toolCall = AgentToolCall(toolName = "device_clock_timer", arguments = mapOf("action" to "get_time"))
        val toolResult = AgentToolResult(toolName = "device_clock_timer", isSuccess = true, output = "12:00 PM")

        val steps = listOf(
            AgentStep.UserPromptStep(prompt = "Check current time"),
            AgentStep.ThoughtStep(stepNumber = 1, thought = "Checking clock", planItems = listOf("Read clock")),
            AgentStep.SubAgentStep(stepNumber = 2, subAgent = subAgent),
            AgentStep.ToolCallStep(stepNumber = 3, toolCall = toolCall, approvalStatus = ToolApprovalStatus.AUTO_APPROVED),
            AgentStep.ObservationStep(stepNumber = 4, result = toolResult),
            AgentStep.VerificationStep(stepNumber = 5, summary = "Clock verified", isVerified = true),
            AgentStep.PlanReviewStep(stages = listOf("Stage 1", "Stage 2"), explanation = "DAG Plan"),
            AgentStep.ResolutionStep(finalResponse = "12:00 PM", totalToolCalls = 1, isolatedScratchpadTokens = 80, mainChatTokensPolluted = 0)
        )

        val json = StepJsonSerializer.serializeSteps(steps)
        val deserialized = StepJsonSerializer.deserializeSteps(json)

        assertEquals(8, deserialized.size)
        assertTrue(deserialized[0] is AgentStep.UserPromptStep)
        assertEquals("Check current time", (deserialized[0] as AgentStep.UserPromptStep).prompt)
        assertTrue(deserialized[1] is AgentStep.ThoughtStep)
        assertTrue(deserialized[2] is AgentStep.SubAgentStep)
        assertTrue(deserialized[3] is AgentStep.ToolCallStep)
        assertTrue(deserialized[4] is AgentStep.ObservationStep)
        assertTrue(deserialized[5] is AgentStep.VerificationStep)
        assertTrue(deserialized[6] is AgentStep.PlanReviewStep)
        assertTrue(deserialized[7] is AgentStep.ResolutionStep)
    }

    @Test
    fun testSessionSqliteCrudAndPinToggle() {
        val sessionId = "session_test_001"
        val session = AgentSession(
            id = sessionId,
            title = "Test Session",
            objective = "Run audit",
            status = AgentSessionStatus.COMPLETED,
            steps = listOf(AgentStep.UserPromptStep(prompt = "Run audit")),
            executionTier = AgentExecutionTier.TIER_3_PIPELINE,
            slashCommand = "/plan",
            isPlanApproved = true,
            isPinned = false
        )

        // Save
        store.saveSession(session)

        // Get
        val retrieved = store.getSession(sessionId)
        assertNotNull(retrieved)
        assertEquals(sessionId, retrieved?.id)
        assertEquals("Test Session", retrieved?.title)
        assertEquals(AgentExecutionTier.TIER_3_PIPELINE, retrieved?.executionTier)
        assertEquals("/plan", retrieved?.slashCommand)
        assertTrue(retrieved?.isPlanApproved == true)

        // Toggle Pin
        val pinned = store.togglePin(sessionId)
        assertTrue(pinned)
        assertEquals(true, store.getSession(sessionId)?.isPinned)

        // List
        val list = store.listSessions()
        assertEquals(1, list.size)
        assertEquals(sessionId, list[0].id)

        // Delete
        store.deleteSession(sessionId)
        assertNull(store.getSession(sessionId))
        assertEquals(0, store.listSessions().size)
    }
}
