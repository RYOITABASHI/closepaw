package ai.closepaw.app

internal object AccessibilityServiceSetting {
    const val AGENT = "ai.closepaw/ai.closepaw.app.AgentService"
    const val AGENT_SHORT = "ai.closepaw/.app.AgentService"

    fun entries(value: String?): List<String> =
        if (value.isNullOrEmpty() || value == "null") emptyList() else value.split(':')

    private fun isAgent(entry: String): Boolean =
        entry == AGENT || entry == AGENT_SHORT

    fun containsAgent(value: String?): Boolean = entries(value).any(::isAgent)

    fun withAgent(value: String?): String = when {
        containsAgent(value) -> checkNotNull(value)
        entries(value).isEmpty() -> AGENT
        else -> "$value:$AGENT"
    }

    fun withoutAgent(value: String): String = entries(value).filterNot(::isAgent).joinToString(":")

    fun verifyUpdate(
        preExistingEntries: List<String>,
        writtenValue: String,
        readBackValue: String?,
        agentExpected: Boolean = true,
    ): Boolean {
        if (readBackValue == null || containsAgent(readBackValue) != agentExpected) return false
        val others = entries(readBackValue).filterNot(::isAgent)
        return others == preExistingEntries.filterNot(::isAgent) &&
            others == entries(writtenValue).filterNot(::isAgent)
    }
}
