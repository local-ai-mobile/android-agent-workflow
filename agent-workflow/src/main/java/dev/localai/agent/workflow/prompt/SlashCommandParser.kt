package dev.localai.agent.workflow.prompt

import java.util.Locale

object SlashCommandParser {
    private val slashRegex = Regex("""^/(plan|boost|swarm)(\s.*)?$""", RegexOption.IGNORE_CASE)

    fun parse(input: String): Pair<String?, String> {
        val trimmed = input.trim()
        val match = slashRegex.matchEntire(trimmed) ?: return Pair(null, trimmed)
        val cmd = "/" + match.groupValues[1].lowercase(Locale.US)
        val rest = match.groupValues[2].trim()
        return Pair(cmd, rest)
    }

    fun extractSlashCommand(input: String): Pair<String?, String> = parse(input)
}
