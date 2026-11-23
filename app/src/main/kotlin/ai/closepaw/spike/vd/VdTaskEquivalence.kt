package ai.closepaw.spike.vd

import ai.closepaw.platform.virtualdisplay.ShizukuClient
import android.os.SystemClock

/**
 * Stage-2 Increment 1 experiment driver: paired reads of the helper's typed
 * root-task query (candidate) against the incumbent Shizuku transport
 * (control), normalized and diffed field by field per
 * stage2-increment1-experiment-plan.md section 3. Observation only: this
 * class never moves, removes, or starts a task.
 *
 * The incumbent's full row is the merge of its two production reads:
 * rootTasksOnDisplay (taskId, packageName = top-else-base, pinned, order)
 * and rootTasksWithPackagesOnDisplay (packages, visible), joined by taskId.
 */
internal class VdTaskEquivalence(
    private val session: VdHelperSession,
    private val shizuku: ShizukuClient,
) {
    data class NormRow(
        val ordinal: Int,
        val taskId: Int,
        val packageName: String?,
        val packages: Set<String>,
        val visible: Boolean?,
        val pinned: Boolean,
    )

    enum class DiffKind { FIELD, ORDER, NULLNESS, TORN }

    data class Diff(val kind: DiffKind, val detail: String)

    data class PairedRead(
        val displayId: Int,
        val candidate: List<NormRow>?,
        val incumbent: List<NormRow>?,
        val candidateLatencyMs: Long,
        val incumbentLatencyMs: Long,
        val diffs: List<Diff>,
    )

    /** One paired read. [candidateFirst] alternates to cancel ordering bias. */
    suspend fun pairedRead(displayId: Int, candidateFirst: Boolean): PairedRead {
        var candidate: List<NormRow>? = null
        var incumbent: List<NormRow>? = null
        var candidateMs = -1L
        var incumbentMs = -1L
        val incumbentTears = mutableListOf<Diff>()

        suspend fun readCandidate() {
            val started = SystemClock.elapsedRealtime()
            candidate = session.queryRootTasks(displayId)?.mapIndexed { index, row ->
                NormRow(index, row.taskId, row.packageName, row.packages, row.visible, row.pinned)
            }
            candidateMs = SystemClock.elapsedRealtime() - started
        }

        fun readIncumbent() {
            val started = SystemClock.elapsedRealtime()
            val tasks = shizuku.rootTasksOnDisplay(displayId)
            val withPackages = shizuku.rootTasksWithPackagesOnDisplay(displayId)
            incumbentMs = SystemClock.elapsedRealtime() - started
            val taskIds = tasks.orEmpty().map { it.taskId }
            val richIds = withPackages.orEmpty().map { it.id }
            incumbentTears += duplicateIds("incumbent tasks", taskIds)
            incumbentTears += duplicateIds("incumbent packages", richIds)
            if (tasks == null || withPackages == null) return
            if (taskIds.toSet() != richIds.toSet()) {
                incumbentTears += Diff(
                    DiffKind.TORN,
                    "incumbent merge IDs differ tasks=$taskIds packages=$richIds",
                )
            }
            if (incumbentTears.isNotEmpty()) return

            val byId = withPackages.associateBy { it.id }
            incumbent = tasks.mapIndexed { index, task ->
                val rich = byId.getValue(task.taskId)
                NormRow(index, task.taskId, task.packageName, rich.packages, rich.visible, task.pinned)
            }
        }

        if (candidateFirst) {
            readCandidate(); readIncumbent()
        } else {
            readIncumbent(); readCandidate()
        }
        return PairedRead(
            displayId, candidate, incumbent, candidateMs, incumbentMs,
            diff(candidate, incumbent, incumbentTears),
        )
    }

    private fun diff(
        candidate: List<NormRow>?,
        incumbent: List<NormRow>?,
        incumbentTears: List<Diff>,
    ): List<Diff> {
        val diffs = incumbentTears.toMutableList()
        if (candidate != null) diffs += duplicateIds("candidate", candidate.map { it.taskId })
        if (diffs.isNotEmpty()) return diffs

        if ((candidate == null) != (incumbent == null)) {
            diffs += Diff(
                DiffKind.NULLNESS,
                "candidate=${if (candidate == null) "null" else "rows"} incumbent=${if (incumbent == null) "null" else "rows"}",
            )
            return diffs
        }
        if (candidate == null || incumbent == null) return diffs

        val candidateIds = candidate.map { it.taskId }
        val incumbentIds = incumbent.map { it.taskId }
        if (candidateIds.toSet() != incumbentIds.toSet()) {
            diffs += Diff(DiffKind.FIELD, "root set differs candidate=$candidateIds incumbent=$incumbentIds")
        } else if (candidateIds != incumbentIds) {
            diffs += Diff(DiffKind.ORDER, "order differs candidate=$candidateIds incumbent=$incumbentIds")
        }
        val candidateById = candidate.associateBy { it.taskId }
        for (inc in incumbent) {
            val cand = candidateById[inc.taskId] ?: continue
            if (cand.packageName != inc.packageName) {
                diffs += Diff(DiffKind.FIELD, "taskId=${inc.taskId} packageName candidate=${cand.packageName} incumbent=${inc.packageName}")
            }
            if (cand.packages != inc.packages) {
                diffs += Diff(DiffKind.FIELD, "taskId=${inc.taskId} packages candidate=${cand.packages.sorted()} incumbent=${inc.packages.sorted()}")
            }
            if (cand.visible != inc.visible) {
                val kind = if (cand.visible == null || inc.visible == null) DiffKind.NULLNESS else DiffKind.FIELD
                diffs += Diff(kind, "taskId=${inc.taskId} visible candidate=${cand.visible} incumbent=${inc.visible}")
            }
            if (cand.pinned != inc.pinned) {
                diffs += Diff(DiffKind.FIELD, "taskId=${inc.taskId} pinned candidate=${cand.pinned} incumbent=${inc.pinned}")
            }
        }
        return diffs
    }

    private fun duplicateIds(source: String, ids: List<Int>): List<Diff> =
        ids.groupingBy { it }.eachCount().mapNotNull { (id, count) ->
            if (count > 1) Diff(DiffKind.TORN, "$source duplicate taskId=$id x$count") else null
        }

    companion object {
        /** One-line-per-fact rendering for the run log the diff report is built from. */
        fun render(read: PairedRead): String {
            fun rows(list: List<NormRow>?): String =
                list?.joinToString(prefix = "[", postfix = "]") {
                    "${it.taskId}:${it.packageName}:${it.packages.sorted()}:v=${it.visible}:p=${it.pinned}"
                } ?: "null"
            val head = "display=${read.displayId} candMs=${read.candidateLatencyMs} incMs=${read.incumbentLatencyMs} " +
                "candidate=${rows(read.candidate)} incumbent=${rows(read.incumbent)}"
            if (read.diffs.isEmpty()) return "$head diffs=NONE"
            return head + read.diffs.joinToString(prefix = " diffs=[", postfix = "]") { "${it.kind}:${it.detail}" }
        }
    }
}
