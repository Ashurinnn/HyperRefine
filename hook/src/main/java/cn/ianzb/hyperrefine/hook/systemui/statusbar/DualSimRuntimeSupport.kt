package cn.ianzb.hyperrefine.hook.systemui.statusbar

import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicBoolean

/** One refresh per event batch; an immediate/pre-draw flush makes the queued callback a no-op. */
internal class DualSimRefreshGate(
    private val post: (() -> Unit) -> Unit,
    private val refresh: () -> Unit,
) {
    private val pending = AtomicBoolean()
    fun request() {
        if (pending.compareAndSet(false, true)) post { flush() }
    }
    fun flush() {
        if (pending.compareAndSet(true, false)) refresh()
    }
}

/** A later LoadedApk callback must not reinstall the same process's dual-SIM hooks. */
internal class DualSimInstallation {
    private var installed = false

    @Synchronized
    fun installOnce(install: () -> Unit): Boolean {
        if (installed) return false
        install() // A failed/rolled-back installation remains retryable.
        installed = true
        return true
    }
}

/** Collect with MobileUiAdapter's actual JavaAdapter, without the removed Dependency.get API. */
internal class DualSimFlowObserver {
    private data class Collector(val owner: Any, val method: Method)
    private data class Subscription(val flow: Any, val consumer: Consumer<Any?>)
    private var collector: Collector? = null
    private val waiting = ArrayDeque<Subscription>()

    fun value(flow: Any): Any? = Reflect.callMethod(flow, "getValue")

    @Synchronized
    fun attach(adapter: Any): Boolean {
        if (collector?.owner === adapter) return false
        check(collector == null) { "Dual-SIM JavaAdapter changed within one process" }
        val method = StatusBarMembers.methods(adapter.javaClass, "alwaysCollectFlow") {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 2 &&
                !it.parameterTypes[0].isPrimitive && it.parameterTypes[1] == Consumer::class.java
        }.single()
        // Binding can precede MobileUiAdapter.start. Drain only after the real adapter is ready.
        // Remove each subscription after success, so a failed drain does not lose pending work.
        while (waiting.isNotEmpty()) {
            val subscription = waiting.first()
            method.invoke(adapter, subscription.flow, subscription.consumer)
            waiting.removeFirst()
        }
        collector = Collector(adapter, method)
        return true
    }

    @Synchronized
    fun observe(flow: Any, onValue: (Any?) -> Unit) {
        val consumer = Consumer<Any?> { onValue(it) }
        val active = collector
        if (active == null) waiting.addLast(Subscription(flow, consumer))
        else active.method.invoke(active.owner, flow, consumer)
    }
}
