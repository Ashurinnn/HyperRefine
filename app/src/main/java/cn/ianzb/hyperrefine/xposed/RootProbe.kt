package cn.ianzb.hyperrefine.xposed

import java.util.concurrent.TimeUnit

/** A permission prompt cannot keep a background root probe waiting indefinitely. */
internal object RootProbe {
    private const val TIMEOUT_MS = 30_000L

    fun check(start: () -> Process, timeoutMs: Long = TIMEOUT_MS): Boolean {
        var process: Process? = null
        return try {
            val current = start().also { process = it }
            if (!current.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return false
            val output = current.inputStream.bufferedReader().use { it.readText() }
            current.exitValue() == 0 && output.contains("uid=0")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        } finally {
            process?.let { current ->
                runCatching { if (current.isAlive) current.destroyForcibly() }
                runCatching { current.inputStream.close() }
                runCatching { current.errorStream.close() }
                runCatching { current.outputStream.close() }
            }
        }
    }
}
