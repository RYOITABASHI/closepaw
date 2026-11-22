package ai.closepaw.app

import ai.closepaw.session.AgentSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Holds admission before dispatch and records the result before a cancellable dispatcher return. */
internal class InternalSessionCreation {
    private val token = Any()
    var session: AgentSession? = null
        private set

    fun claim(): Boolean = BrainSessionAdmission.tryClaimInternal(token)

    suspend fun <T : AgentSession?> create(dispatcher: CoroutineDispatcher, factory: () -> T): T =
        withContext(dispatcher) {
            factory().also { created ->
                session = created
                created?.tryClaimInternalAdmission()
            }
        }

    fun adopt() {
        session = null
        release()
    }

    fun release() = BrainSessionAdmission.releaseInternal(token)
}
