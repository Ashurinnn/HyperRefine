package cn.ianzb.hyperrefine.hook.notification

import android.app.NotificationChannel
import android.content.Context
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

// Independently implemented from the observable behavior of the supplied Pengeek APK.
// Reference project: https://github.com/MonwF/customiuizer (GPL-3.0).
// No Pengeek source, resources, or native libraries are included in this module.
internal fun installNotificationHooks(installers: List<() -> HookHandle>) {
    val handles = mutableListOf<HookHandle>()
    try {
        installers.forEach { handles += it() }
    } catch (error: Throwable) {
        handles.asReversed().forEach { runCatching { it.unhook() } }
        throw error
    }
}

/** Log a changed ROM layout once, and let the original call continue. */
internal class NotificationCallbackGuard(private val label: String) {
    private val logged = AtomicBoolean()

    fun run(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (logged.compareAndSet(false, true)) HookHelper.log("$label: callback unavailable", error)
        }
    }
}

class KeepLockscreenNotificationsHook : BaseHook() {
    override val key = NotificationKeys.KEEP_ON_LOCKSCREEN

    override fun init() {
        val entry = Reflect.findClass("com.android.systemui.statusbar.notification.collection.NotificationEntry", target.classLoader)
        val expanded = Reflect.findClass("com.android.systemui.statusbar.notification.ExpandedNotification", target.classLoader)
        val sbn = NotificationMembers.field(entry, "mSbn")
        val shown = NotificationMembers.field(expanded, "mHasShownAfterUnlock", Boolean::class.javaPrimitiveType)
        val provider = Reflect.findClass("com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProviderImpl", target.classLoader)
        val methods = NotificationMembers.methods(provider, "shouldHideNotification") {
            it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.firstOrNull() == entry
        }
        val guard = NotificationCallbackGuard(tag)
        installNotificationHooks(methods.map { method -> {
            HookHelper.hookBefore(method) { param ->
                guard.run {
                    val notification = sbn.get(param.args.firstOrNull())
                    if (expanded.isInstance(notification)) shown.setBoolean(notification, false)
                }
            }
        } })
    }
}

class AllowLockscreenNotificationsHook : BaseHook() {
    override val key = NotificationKeys.ALLOW_LOCKSCREEN

    override fun init() {
        val expanded = Reflect.findClass("com.android.systemui.statusbar.notification.ExpandedNotification", target.classLoader)
        val method = NotificationMembers.method(expanded, "canShowOnKeyguard", java.lang.Boolean.TYPE)
        val focus = NotificationMembers.field(expanded, "mIsFocusNotification", java.lang.Boolean.TYPE)
        val allowed = NotificationMembers.field(expanded, "mCanShowOnKeyguard", java.lang.Boolean.TYPE)
        val manager = Reflect.findClass("com.miui.systemui.notification.NotificationSettingsManager", target.classLoader)
        val settings = NotificationMembers.method(manager, "canShowOnKeyguard", java.lang.Boolean.TYPE,
            Context::class.java, String::class.java, String::class.java)
        val guard = NotificationCallbackGuard(tag)
        installNotificationHooks(listOf(
            { HookHelper.hookBefore(method) { param -> guard.run {
                param.setResultValue(focus.getBoolean(param.thisObject) || allowed.getBoolean(param.thisObject))
            } } },
            { HookHelper.hookReplace(settings) { true } },
        ))
    }
}

class AllowFloatingNotificationsHook : BaseHook() {
    override val key = NotificationKeys.ALLOW_FLOAT

    override fun init() {
        val expanded = Reflect.findClass("com.android.systemui.statusbar.notification.ExpandedNotification", target.classLoader)
        val method = NotificationMembers.method(expanded, "isEnableFloat", java.lang.Boolean.TYPE)
        val manager = Reflect.findClass("com.miui.systemui.notification.NotificationSettingsManager", target.classLoader)
        val settings = NotificationMembers.method(manager, "canFloat", java.lang.Boolean.TYPE,
            Context::class.java, String::class.java, String::class.java)
        installNotificationHooks(listOf(
            { HookHelper.hookReplace(method) { true } },
            { HookHelper.hookReplace(settings) { true } },
        ))
    }
}

/** Both Settings and system_server must agree that a channel is blockable. */
class NotificationBlockabilityHook : BaseHook() {
    override val key = NotificationKeys.ALLOW_EDIT

    override fun init() = installNotificationHooks(channelBlockabilityPlan())
}

internal fun channelBlockabilityPlan(): List<() -> HookHandle> {
    val clazz = NotificationChannel::class.java
    val getter = NotificationMembers.method(clazz, "isBlockable", java.lang.Boolean.TYPE)
    val setter = NotificationMembers.method(clazz, "setBlockable", Void.TYPE, java.lang.Boolean.TYPE)
    return listOf(
        { HookHelper.hookReplace(getter) { true } },
        { HookHelper.hookBefore(setter) { it.setArg(0, true) } },
    )
}

class NotificationRestrictionsHook(private val includeChannel: Boolean = false) : BaseHook() {
    override val key = NotificationKeys.ALLOW_EDIT

    override fun init() {
        val filter = Reflect.findClass("miui.util.NotificationFilterHelper", target.classLoader)
        val forced = NotificationMembers.methods(filter, "isNotificationForcedEnabled") {
            it.returnType == java.lang.Boolean.TYPE
        }
        val forcedFor = NotificationMembers.method(filter, "isNotificationForcedFor", java.lang.Boolean.TYPE,
            Context::class.java, String::class.java)
        val blockSystem = NotificationMembers.method(filter, "canSystemNotificationBeBlocked", java.lang.Boolean.TYPE, String::class.java)
        val nonBlockable = NotificationMembers.method(filter, "containNonBlockableChannel", java.lang.Boolean.TYPE, String::class.java)
        val list = NotificationMembers.methods(filter, "getNotificationForcedEnabledList") {
            it.parameterCount == 0 && it.returnType.isAssignableFrom(HashSet::class.java)
        }.single()
        val plan = mutableListOf<() -> HookHandle>()
        (forced + forcedFor + nonBlockable).forEach { method -> plan += { HookHelper.hookReplace(method) { false } } }
        plan += { HookHelper.hookReplace(blockSystem) { true } }
        plan += { HookHelper.hookReplace(list) { HashSet<String>() } }
        if (includeChannel) plan += channelBlockabilityPlan()
        installNotificationHooks(plan)
    }
}

class DisableNotificationFoldHook : BaseHook() {
    override val key = NotificationKeys.DISABLE_FOLD

    override fun init() {
        val coordinator = Reflect.findClass("com.android.systemui.statusbar.notification.collection.coordinator.FoldCoordinator", target.classLoader)
        val attach = coordinatorAttach(coordinator)
        val util = Reflect.findClass("com.miui.systemui.notification.MiuiBaseNotifUtil", target.classLoader)
        val suppress = NotificationMembers.method(util, "shouldSuppressFold", java.lang.Boolean.TYPE)
        installNotificationHooks(listOf(
            { HookHelper.hookReplace(attach) { null } },
            { HookHelper.hookReplace(suppress) { true } },
        ))
    }
}

class RemoveNotificationLimitHook : BaseHook() {
    override val key = NotificationKeys.REMOVE_LIMIT

    override fun init() {
        val coordinator = Reflect.findClass("com.android.systemui.statusbar.notification.collection.coordinator.CountLimitCoordinator", target.classLoader)
        val attach = coordinatorAttach(coordinator)
        installNotificationHooks(listOf({ HookHelper.hookReplace(attach) { null } }))
    }
}

private fun coordinatorAttach(clazz: Class<*>): Method = NotificationMembers.methods(clazz, "attach") {
    it.returnType == Void.TYPE && it.parameterTypes.map { type -> type.name } ==
        listOf("com.android.systemui.statusbar.notification.collection.NotifPipeline")
}.single()
