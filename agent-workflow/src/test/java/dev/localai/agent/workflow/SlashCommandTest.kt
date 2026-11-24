package dev.localai.agent.workflow

import dev.localai.agent.workflow.prompt.SlashCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandTest {

    @Test
    fun testValidSlashCommandsExtracted() {
        val (pCmd, pRest) = SlashCommandParser.parse("/plan search the web for Gemma")
        assertEquals("/plan", pCmd)
        assertEquals("search the web for Gemma", pRest)

        val (bCmd, bRest) = SlashCommandParser.parse("/boost profile thermal trends")
        assertEquals("/boost", bCmd)
        assertEquals("profile thermal trends", bRest)

        val (sCmd, sRest) = SlashCommandParser.parse("/swarm coordinate multi-agent workflow")
        assertEquals("/swarm", sCmd)
        assertEquals("coordinate multi-agent workflow", sRest)
    }

    @Test
    fun testCaseInsensitiveSlashCommands() {
        val (pCmd, pRest) = SlashCommandParser.parse("/PLAN search web")
        assertEquals("/plan", pCmd)
        assertEquals("search web", pRest)

        val (bCmd, bRest) = SlashCommandParser.parse("/Boost deep check")
        assertEquals("/boost", bCmd)
        assertEquals("deep check", bRest)

        val (sCmd, sRest) = SlashCommandParser.parse("/SWARM agent sync")
        assertEquals("/swarm", sCmd)
        assertEquals("agent sync", sRest)
    }

    @Test
    fun testPrefixCollisionsDoNotTriggerCommands() {
        val (p1, r1) = SlashCommandParser.parse("/planner for vacation")
        assertNull(p1)
        assertEquals("/planner for vacation", r1)

        val (p2, r2) = SlashCommandParser.parse("/plants in my office")
        assertNull(p2)
        assertEquals("/plants in my office", r2)

        val (b1, r1b) = SlashCommandParser.parse("/booster vaccine status")
        assertNull(b1)
        assertEquals("/booster vaccine status", r1b)
    }

    @Test
    fun testEmptySlashCommands() {
        val (cmd, rest) = SlashCommandParser.parse("/plan")
        assertEquals("/plan", cmd)
        assertEquals("", rest)

        val (bCmd, bRest) = SlashCommandParser.parse("/boost")
        assertEquals("/boost", bCmd)
        assertEquals("", bRest)
    }
}
