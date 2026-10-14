package cn.ianzb.hyperrefine.hook.systemui.statusbar

import org.junit.Assert.*
import org.junit.Test

class StatusBarPolicyTest {
    @Test fun retainedOptionsHaveUniqueKeysAndOnlySystemUiIsTargeted() {
        assertEquals(listOf(StatusBarKeys.WIFI_ACTIVITY, StatusBarKeys.HIDE_TYPE), StatusBarKeys.ALL)
        assertEquals(2, StatusBarKeys.ALL.distinct().size)
        assertEquals("com.android.systemui", StatusBarKeys.TARGET)
    }

    open class Parent { private var field = 1; open fun build(value: String) {}; fun build(value: Int) {} }
    class Child : Parent() { override fun build(value: String) {} }
    @Test fun memberResolutionValidatesSignaturesAndSupportsInheritedPrivateFields() {
        val child = Child()
        assertEquals(1, StatusBarMembers.field(Child::class.java, "field").getInt(child))
        val methods = StatusBarMembers.methods(Child::class.java, "build") {
            it.parameterTypes.contentEquals(arrayOf(String::class.java)) && it.returnType == Void.TYPE
        }
        assertEquals(1, methods.size)
        assertEquals(Child::class.java, methods.single().declaringClass)
    }

    @Test(expected = IllegalStateException::class)
    fun missingCompatibleHookMethodIsRejected() {
        StatusBarMembers.methods(Child::class.java, "build") { it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
    }

    class MutableFlow(val value: Boolean)
    class OptimizedReadonly private constructor(val flow: MutableFlow)
    class UnoptimizedReadonly(val flow: MutableFlow, val job: Any?)
    class InvalidReadonly(val value: Int)

    @Test fun romOptimizedReadonlyFlowConstructorRetainsTheReplacementFlow() {
        val source = MutableFlow(false)
        val wrapped = StatusBarMembers.readonlyFlow(OptimizedReadonly::class.java, source) as OptimizedReadonly
        assertSame(source, wrapped.flow)
        assertFalse(wrapped.flow.value)
    }

    @Test fun unoptimizedReadonlyFlowConstructorRemainsSupported() {
        val source = MutableFlow(false)
        val wrapped = StatusBarMembers.readonlyFlow(UnoptimizedReadonly::class.java, source) as UnoptimizedReadonly
        assertSame(source, wrapped.flow)
        assertNull(wrapped.job)
    }

    @Test(expected = NoSuchMethodException::class)
    fun unknownReadonlyFlowSignatureIsRejectedInsteadOfConstructingInvalidState() {
        StatusBarMembers.readonlyFlow(InvalidReadonly::class.java, MutableFlow(false))
    }
}
