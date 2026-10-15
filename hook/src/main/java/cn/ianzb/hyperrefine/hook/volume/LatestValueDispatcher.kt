package cn.ianzb.hyperrefine.hook.volume

import java.util.concurrent.Executor

/** One worker, one pending value per playback identity; slow IPC cannot queue every drag event. */
internal class LatestValueDispatcher<K, V>(
    private val executor: Executor,
    private val consume: (V) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val pending = LinkedHashMap<K, V>()
    private var running = false

    fun submit(key: K, value: V) {
        synchronized(pending) {
            pending[key] = value
            if (running) return
            running = true
        }
        try {
            executor.execute(::drain)
        } catch (failure: Throwable) {
            synchronized(pending) {
                pending.clear()
                running = false
            }
            runCatching { onFailure(failure) }
        }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(pending) {
                val iterator = pending.entries.iterator()
                if (!iterator.hasNext()) {
                    running = false
                    return
                }
                iterator.next().value.also { iterator.remove() }
            }
            runCatching { consume(next) }.onFailure { runCatching { onFailure(it) } }
        }
    }
}
