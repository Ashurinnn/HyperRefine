package cn.ianzb.hyperrefine.hook.volume

/** Resources belong to one panel generation and can be disposed by end, cancel, or detach. */
internal class OwnedResources<T>(
    private val release: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val resources = mutableListOf<T>()
    private var disposed = false
    val values: List<T> get() = resources

    fun add(resource: T) {
        if (disposed) releaseSafely(resource) else resources.add(resource)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        val owned = resources.toList()
        resources.clear()
        owned.forEach(::releaseSafely)
    }

    private fun releaseSafely(resource: T) {
        runCatching { release(resource) }.onFailure { runCatching { onFailure(it) } }
    }
}
