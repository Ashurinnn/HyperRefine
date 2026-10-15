package cn.ianzb.hyperrefine.hook.volume

/** Direct write is authoritative; failed writes and legacy broadcasts retain the receiver fallback. */
internal object AppVolumeDelivery {
    fun send(volume: Float, write: (Float) -> Int, forward: (Float, Boolean) -> Unit) {
        if (!volume.isFinite()) return
        val value = volume.coerceIn(0f, 1f)
        val applied = runCatching { write(value) > 0 }.getOrDefault(false)
        forward(value, applied)
    }

    fun receive(volume: Float, alreadyApplied: Boolean, write: (Float) -> Int, mirror: (Float) -> Unit) {
        if (!volume.isFinite()) return
        val value = volume.coerceIn(0f, 1f)
        if (alreadyApplied || write(value) > 0) mirror(value)
    }
}
