package ai.closepaw.platform.virtualdisplay

import java.util.concurrent.CompletableFuture

/**
 * One in-flight helper query with binder-death semantics: once the helper's
 * binder dies, the query is settled as failed (null) and any late reply is
 * dropped, so a dying helper can never serve a partial or stale snapshot.
 * Used by TaskHelperSession's connection; kept Android-free so the death
 * discipline is host-testable.
 */
internal class TaskHelperQuery(val id: Int, val displayId: Int) {
    private val answer = CompletableFuture<List<TaskHelperRow>?>()
    @Volatile private var binderDead = false

    val isBinderDead: Boolean get() = binderDead

    /** Settles the query with a reply; returns false when death already settled it. */
    fun complete(rows: List<TaskHelperRow>?): Boolean =
            if (binderDead) false else answer.complete(rows)

    /** Settles the query as failed and drops any later reply. */
    fun binderDied() {
        binderDead = true
        answer.complete(null)
    }

    /** The reply, or null on failure, timeout, or binder death at any point up to the answer. */
    fun await(remainingNanos: Long): List<TaskHelperRow>? =
            awaitTaskHelperRows(answer, remainingNanos).takeIf { !binderDead }
}
