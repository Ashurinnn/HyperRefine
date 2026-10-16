package cn.ianzb.hyperrefine.hook.systemui.statusbar

internal object StatusBarPolicy {

    data class Visibility(val root: Boolean, val signal: Boolean)
    data class Signal(
        val subId: Int, val resourceId: Int, val visible: Boolean, val rootVisible: Boolean = visible,
        val cellularMode: Boolean = true, val satellite: Boolean = false,
    )
    data class Pairing(val primary: Signal, val secondary: Signal)

    /** Only combine two real visible subscriptions. Unknown / single-SIM state keeps native layout. */
    fun pair(signals: List<Signal>, dataSubId: Int): Pairing? {
        val visible = signals.filter { it.visible && it.cellularMode && !it.satellite && it.subId >= 0 && it.resourceId > 0 }
            .distinctBy { it.subId }.sortedBy { it.subId }
        if (visible.size != 2) return null
        val primary = visible.firstOrNull { it.subId == dataSubId } ?: visible.first()
        return Pairing(primary, visible.first { it.subId != primary.subId })
    }

    private val levelName = Regex("^stat_sys_signal_([0-5])(?:_.*)?$")
    fun signalLevel(resourceName: String): Int? =
        if (resourceName == "stat_sys_signal_null") 0 else levelName.matchEntire(resourceName)?.groupValues?.get(1)?.toInt()

    fun bounded(value: Float, default: Float, min: Float, max: Float): Float =
        if (value.isFinite()) value.coerceIn(min, max) else default
}
