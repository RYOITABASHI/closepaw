package ai.closepaw.platform.virtualdisplay

internal object ShadowDiff {
    enum class Kind { NONE, ORDER, FIELD, ROOT_SET, NULLNESS }

    data class Result(val kind: Kind, val taskIdDifference: Set<Int> = emptySet())

    fun observe(
            served: SourcedDisplayTasks,
            enqueue: (() -> Unit) -> Unit,
            incumbent: () -> List<DisplayTask>?,
            report: (Result) -> Unit,
            onFailure: (Throwable) -> Unit = {},
    ): List<DisplayTask> {
        if (served.source == ServedSource.HELPER) {
            runCatching {
                enqueue {
                    runCatching {
                        report(classify(served.tasks, runCatching(incumbent).getOrNull()))
                    }.recoverCatching(onFailure)
                }
            }.recoverCatching(onFailure)
        }
        return served.tasks
    }

    fun classify(served: List<DisplayTask>, incumbent: List<DisplayTask>?): Result {
        val servedOrder = served.map { it.taskId }
        if (incumbent == null) return Result(Kind.NULLNESS, servedOrder.toSet())
        val incumbentOrder = incumbent.map { it.taskId }
        val servedIds = servedOrder.toSet()
        val incumbentIds = incumbentOrder.toSet()
        if (servedIds != incumbentIds) {
            return Result(Kind.ROOT_SET, (servedIds - incumbentIds) + (incumbentIds - servedIds))
        }
        return when {
            servedOrder != incumbentOrder -> Result(Kind.ORDER)
            served != incumbent -> Result(Kind.FIELD)
            else -> Result(Kind.NONE)
        }
    }
}
