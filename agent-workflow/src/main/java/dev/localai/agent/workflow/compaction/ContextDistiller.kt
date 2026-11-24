package dev.localai.agent.workflow.compaction

import dev.localai.agent.workflow.model.AgentStep

object ContextDistiller {

    const val CHARS_PER_TOKEN: Double = 3.8
    const val PASS_THROUGH_THRESHOLD: Int = 320
    const val DEFAULT_MAX_DISTILLED_CHARS: Int = 350
    const val DEFAULT_ROLLING_WINDOW_SIZE: Int = 4

    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
    }

    fun distillObservation(
        toolName: String,
        rawOutput: String,
        maxChars: Int = DEFAULT_MAX_DISTILLED_CHARS
    ): String {
        val trimmed = rawOutput.trim()
        if (trimmed.length <= PASS_THROUGH_THRESHOLD) return trimmed

        val lines = trimmed.lines().filter { it.isNotBlank() }
        val header = lines.firstOrNull() ?: toolName
        val firstFewBullets = lines.drop(1).take(3).joinToString("; ") {
            it.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim()
        }

        val distilled = if (firstFewBullets.isNotBlank()) {
            "$header | Key Facts: $firstFewBullets"
        } else {
            header
        }
        return distilled.take(maxChars)
    }

    fun formatRollingObservations(
        observations: List<AgentStep.ObservationStep>,
        windowSize: Int = DEFAULT_ROLLING_WINDOW_SIZE
    ): String {
        if (observations.isEmpty()) return ""

        return buildString {
            append("--- PREVIOUS TOOL OBSERVATIONS (DISTILLED) ---\n")
            val window = observations.takeLast(windowSize)
            if (observations.size > windowSize) {
                val earlierCount = observations.size - windowSize
                append("• [Earlier Observations]: $earlierCount earlier tool steps successfully completed and summarized.\n")
            }
            window.forEachIndexed { i, obs ->
                val distilled = distillObservation(obs.result.toolName, obs.result.output)
                append("${i + 1}. [${obs.result.toolName}] (Success=${obs.result.isSuccess}): $distilled\n\n")
            }
            append("--- END OF OBSERVATIONS ---\n\n")
        }
    }

    fun stripThinkingTags(raw: String): String = ThinkingTagStripper.stripThinkingTags(raw)
}
