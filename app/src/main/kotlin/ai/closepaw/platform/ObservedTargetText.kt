package ai.closepaw.platform

internal const val OBSERVED_TARGET_DESCENDANT_TEXT_LIMIT = 3

internal fun matchesObservedTargetText(
    ownText: String,
    descendantMergedTexts: List<String>,
    hintText: String,
): Boolean {
    if (ownText.isNotBlank() || hintText.isBlank()) return ownText == hintText

    return descendantMergedTexts.asSequence()
        .filter { it.isNotBlank() }
        .take(OBSERVED_TARGET_DESCENDANT_TEXT_LIMIT)
        .joinToString(" | ") == hintText
}
