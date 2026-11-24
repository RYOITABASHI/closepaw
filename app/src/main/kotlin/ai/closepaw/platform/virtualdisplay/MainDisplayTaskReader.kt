package ai.closepaw.platform.virtualdisplay

import ai.closepaw.adb.AdbShellChannel
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Display
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Q5 display-0 observation. The temporary session owns its own launch and retirement. */
internal class MainDisplayTaskReader(
    context: Context,
    private val shizuku: ShizukuClient = ShizukuClient(),
) {
    private val ownership = TaskHelperOwnership()
    private val session = TaskHelperSession(context, AdbShellChannel(context), ownership)
    private val queryLock = Mutex()

    suspend fun read(
        useHelper: Boolean = TaskObservationPolicy.helperRouteEnabled(Build.VERSION.SDK_INT),
    ): List<ShizukuActivityTaskTransport.RootTask>? =
            withContext(Dispatchers.IO) {
                val displayId = Display.DEFAULT_DISPLAY
                if (!useHelper) {
                    return@withContext shizuku.rootTasksWithPackagesOnDisplayIncumbent(displayId)
                }
                if (shizuku.hasTaskHelper()) {
                    return@withContext shizuku.helperRows(displayId)?.let(TaskRowMapping::toRootTasks)
                }
                queryLock.withLock {
                    val token = ownership.mint()
                    try {
                        session.open(token)
                        session.queryRows(displayId)?.let(TaskRowMapping::toRootTasks)
                    } catch (_: TimeoutCancellationException) {
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("MainDisplayTaskReader", "Display 0 task query failed", e)
                        null
                    } finally {
                        session.close(token)
                    }
                }
            }
}
