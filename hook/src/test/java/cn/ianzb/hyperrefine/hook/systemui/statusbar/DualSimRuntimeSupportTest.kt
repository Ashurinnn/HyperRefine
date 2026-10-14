// DORMANT: preserved from statusbar.6 (ddb0e59); not compiled or registered.
// Restore only after artwork and device regression testing. Runtime diagnostics removed.
// package cn.ianzb.hyperrefine.hook.systemui.statusbar
//
// import org.junit.Assert.*
// import org.junit.Test
// import java.lang.reflect.InvocationTargetException
// import java.util.function.Consumer
//
// class DualSimRuntimeSupportTest {
//     class Flow(var current: Any?) {
//         val listeners = mutableListOf<Consumer<Any?>>()
//         fun getValue(): Any? = current
//         fun emit(value: Any?) { current = value; listeners.forEach { it.accept(value) } }
//     }
//
//     class Adapter {
//         var failNext = false
//         fun alwaysCollectFlow(flow: Flow, consumer: Consumer<Any?>) {
//             if (failNext) { failNext = false; error("collector not ready") }
//             flow.listeners += consumer
//             consumer.accept(flow.current)
//         }
//     }
//
//     @Test fun bindBeforeAdapterPreservesInitialAndLaterSignalEvents() {
//         val observer = DualSimFlowObserver()
//         val signal = Flow(2)
//         val seen = mutableListOf<Any?>()
//         observer.observe(signal) { seen += it }
//         signal.emit(3)
//         assertTrue(seen.isEmpty())
//         assertTrue(observer.attach(Adapter()))
//         signal.emit(4)
//         assertEquals(listOf(3, 4), seen)
//         assertEquals(4, observer.value(signal))
//     }
//
//     @Test fun adapterBeforeBindCollectsIndependentFlowsAndDoesNotSubscribeTwice() {
//         val observer = DualSimFlowObserver()
//         val adapter = Adapter()
//         assertTrue(observer.attach(adapter))
//         val first = Flow(1)
//         val second = Flow(2)
//         val firstSeen = mutableListOf<Any?>()
//         val secondSeen = mutableListOf<Any?>()
//         observer.observe(first) { firstSeen += it }
//         observer.observe(second) { secondSeen += it }
//         assertFalse(observer.attach(adapter))
//         first.emit(3)
//         second.emit(4)
//         assertEquals(listOf(1, 3), firstSeen)
//         assertEquals(listOf(2, 4), secondSeen)
//         assertEquals(1, first.listeners.size)
//         assertEquals(1, second.listeners.size)
//     }
//
//     @Test fun failedDrainRetainsPendingSubscriptionForRetry() {
//         val observer = DualSimFlowObserver()
//         val adapter = Adapter().apply { failNext = true }
//         val signal = Flow(5)
//         val seen = mutableListOf<Any?>()
//         observer.observe(signal) { seen += it }
//         try { observer.attach(adapter); fail("Must expose collection failure") }
//         catch (error: InvocationTargetException) { assertEquals("collector not ready", error.cause?.message) }
//         assertTrue(observer.attach(adapter))
//         assertEquals(listOf(5), seen)
//         assertEquals(1, signal.listeners.size)
//     }
//
//     @Test fun unrelatedAdapterCannotConsumeOrDropPendingSignals() {
//         val observer = DualSimFlowObserver()
//         val seen = mutableListOf<Any?>()
//         observer.observe(Flow(1)) { seen += it }
//         try { observer.attach(Any()); fail("Must reject an unsupported adapter") }
//         catch (_: IllegalStateException) { assertTrue(seen.isEmpty()) }
//         observer.attach(Adapter())
//         assertEquals(listOf(1), seen)
//     }
//
//     @Test fun laterLoadedApkCallbacksReuseOnlyCompletedInstallation() {
//         val installation = DualSimInstallation()
//         var calls = 0
//         assertTrue(installation.installOnce { calls++ })
//         assertFalse(installation.installOnce { error("Should not resolve classes from another loader") })
//         assertEquals(1, calls)
//     }
//
//     @Test fun failedInstallationKeepsOriginalErrorAndAllowsRealRetry() {
//         val installation = DualSimInstallation()
//         val expected = ClassNotFoundException("binder")
//         try { installation.installOnce { throw expected }; fail("Must not record failed installation") }
//         catch (actual: ClassNotFoundException) { assertSame(expected, actual) }
//         assertTrue(installation.installOnce {})
//     }
// }
