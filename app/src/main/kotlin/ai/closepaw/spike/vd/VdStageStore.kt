package ai.closepaw.spike.vd

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

internal class VdStageStore(context: Context) {
    private val base = File(context.filesDir, "spike-vd.json")
    private val file = AtomicFile(base)

    fun load(): VdStageLoad = synchronized(lock) {
        try {
            decodeVdStages(file.readFully().decodeToString(throwOnInvalidSequence = true))
        } catch (_: FileNotFoundException) {
            if (listOf("", ".bak", ".new").any { File(base.path + it).exists() }) VdStageLoad.Invalid
            else VdStageLoad.Missing
        } catch (_: Exception) {
            VdStageLoad.Invalid
        }
    }

    fun save(log: VdStageLog) = synchronized(lock) {
        val bytes = log.json().toString().encodeToByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        file.finishWrite(stream)
        if (load() != VdStageLoad.Present(log)) throw IOException("VD stage write could not be verified")
    }

    private companion object {
        val lock = Any()
    }
}
