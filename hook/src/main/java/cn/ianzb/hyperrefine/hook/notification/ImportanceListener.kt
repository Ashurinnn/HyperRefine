package cn.ianzb.hyperrefine.hook.notification

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

internal object NotificationPolicy {
    fun importance(value: Any?): Int? = value?.toString()?.toIntOrNull()?.takeIf { it in 0..5 }

    fun suppressIcon(bypassFilter: Boolean, importance: Int): Boolean = !bypassFilter && importance <= 1
}

/** The listener belongs to Settings' class loader, not the module's AndroidX dependency. */
internal class ImportanceListener(private val update: (Int) -> Boolean) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
        "onPreferenceChange" -> NotificationPolicy.importance(args?.getOrNull(1))?.let(update) ?: false
        "equals" -> proxy === args?.getOrNull(0)
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "HyperRefine notification importance listener"
        else -> null
    }
}
