package dev.localai.agent.workflow.compaction

object ThinkingTagStripper {
    private val CLOSED_THINK_REGEX = Regex("""<think>.*?</think>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val UNCLOSED_THINK_REGEX = Regex("""<think>.*""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun stripThinkingTags(text: String): String {
        if (!text.contains("<think>", ignoreCase = true)) return text.trim()
        val withoutClosed = text.replace(CLOSED_THINK_REGEX, "")
        val withoutUnclosed = withoutClosed.replace(UNCLOSED_THINK_REGEX, "")
        return withoutUnclosed.trim()
    }

    fun strip(text: String): String = stripThinkingTags(text)

    fun extractThinking(text: String): String? {
        if (!text.contains("<think>", ignoreCase = true)) return null
        val match = CLOSED_THINK_REGEX.find(text)
        if (match != null) {
            return match.value
                .replace(Regex("""(?i)</?think>"""), "")
                .trim()
        }
        val unclosedMatch = UNCLOSED_THINK_REGEX.find(text)
        return unclosedMatch?.value
            ?.replace(Regex("""(?i)<think>"""), "")
            ?.trim()
    }

    fun hasThinkingTags(text: String): Boolean {
        return text.contains("<think>", ignoreCase = true)
    }
}
