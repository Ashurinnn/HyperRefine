package cn.ianzb.hyperrefine.hook.notification

import java.lang.reflect.Field
import java.lang.reflect.Method

/** Resolve and validate members before installing any part of a rule. */
internal object NotificationMembers {
    fun field(clazz: Class<*>, name: String, type: Class<*>? = null): Field {
        var current: Class<*>? = clazz
        while (current != null) {
            val field = current.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                require(type == null || field.type == type) { "${clazz.name}#$name: unexpected field type" }
                return field.apply { isAccessible = true }
            }
            current = current.superclass
        }
        throw NoSuchFieldException("${clazz.name}#$name")
    }

    fun methods(clazz: Class<*>, name: String, predicate: (Method) -> Boolean): List<Method> {
        val found = linkedMapOf<List<Class<*>>, Method>()
        var current: Class<*>? = clazz
        while (current != null) {
            // An R8 Function1 mapper can expose its hookable Object signature as a
            // bridge. Match the requested signature rather than excluding bridges.
            current.declaredMethods.filter { it.name == name && predicate(it) }
                .forEach { found.putIfAbsent(it.parameterTypes.toList(), it) }
            current = current.superclass
        }
        check(found.isNotEmpty()) { "${clazz.name}#$name: no compatible method" }
        return found.values.map { it.apply { isAccessible = true } }
    }

    fun method(clazz: Class<*>, name: String, result: Class<*>, vararg args: Class<*>): Method =
        methods(clazz, name) { it.returnType == result && it.parameterTypes.contentEquals(args) }.single()
}
