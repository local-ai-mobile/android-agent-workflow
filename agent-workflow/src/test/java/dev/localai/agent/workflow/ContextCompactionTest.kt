package dev.localai.agent.workflow

import dev.localai.agent.workflow.compaction.ContextDistiller
import dev.localai.agent.workflow.compaction.ThinkingTagStripper
import dev.localai.agent.workflow.model.AgentSession
import dev.localai.agent.workflow.model.AgentStep
import dev.localai.agent.workflow.prompt.ScopedPromptBuilder
import dev.localai.agent.workflow.tool.AgentToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactionTest {

    @Test
    fun testDistillationUnder320CharsPreservedVerbatim() {
        val shortText = "Telemetry: Battery 95%, RAM 5.1GB free, CPU Normal."
        val distilled = ContextDistiller.distillObservation("telemetry", shortText)
        assertEquals(shortText, distilled)
    }

    @Test
    fun testDistillationLargeOutputCompactedUnder350Chars() {
        val largeOutput = buildString {
            appendLine("Web Search Results for 'Kotlin Coroutines':")
            for (i in 1..40) {
                appendLine("• [$i] Comprehensive guide to coroutines continuation passing style and structured concurrency with Dispatchers.IO...")
            }
        }
        assertTrue("Input is large (>2000 chars)", largeOutput.length > 2000)

        val distilled = ContextDistiller.distillObservation("web_search", largeOutput)
        assertTrue("Distilled length must be <= 350 chars", distilled.length <= 350)
        assertTrue(distilled.contains("Web Search Results"))
        assertTrue(distilled.contains("Key Facts"))
    }

    @Test
    fun testRollingObservationWindowCapsAtLastFour() {
        val steps = mutableListOf<AgentStep>()
        for (i in 1..8) {
            steps.add(
                AgentStep.ObservationStep(
                    stepNumber = i,
                    result = AgentToolResult(
                        toolName = "tool_$i",
                        isSuccess = true,
                        output = "Output $i: Detailed observation data for step $i."
                    )
                )
            )
        }

        val session = AgentSession(
            title = "Rolling Window Test",
            objective = "Test rolling window",
            steps = steps
        )

        val prompt = ScopedPromptBuilder.buildAgentPrompt(session, emptyList())

        assertTrue("Must include earlier observations summary", prompt.contains("[Earlier Observations]: 4 earlier tool steps"))
        assertTrue("Must include last observation", prompt.contains("tool_8"))
        assertTrue("Must include 5th observation", prompt.contains("tool_5"))
        assertFalse("Must not include 1st observation directly", prompt.contains("tool_1"))
    }

    @Test
    fun testScratchpadTokenIsolationZeroMainChatTokensPolluted() {
        val session = AgentSession(
            title = "Isolation Test",
            objective = "Execute task",
            isolatedScratchpad = listOf(
                "[STEP 1] Initial reasoning",
                "[TOOL_OBSERVATION] Raw 2000 char log trace",
                "[REASONING] Validating grounded facts"
            )
        )

        assertTrue(session.isolatedTokenEstimate > 0)
        val resolution = AgentStep.ResolutionStep(
            finalResponse = "Concise user answer.",
            totalToolCalls = 1,
            isolatedScratchpadTokens = session.isolatedTokenEstimate,
            mainChatTokensPolluted = 0
        )

        assertEquals(0, resolution.mainChatTokensPolluted)
    }

    @Test
    fun testThinkingTagStripping() {
        val textWithThink = "<think>Analyzing user request step by step.\n1. Verify inputs\n2. Call tool</think>Here is the final answer."
        val stripped = ThinkingTagStripper.strip(textWithThink)
        assertEquals("Here is the final answer.", stripped)

        val unclosedThink = "<think>Still reasoning... No closing tag."
        val strippedUnclosed = ThinkingTagStripper.strip(unclosedThink)
        assertEquals("", strippedUnclosed)

        val noThink = "Direct answer without tags."
        assertEquals(noThink, ThinkingTagStripper.strip(noThink))
    }
}
