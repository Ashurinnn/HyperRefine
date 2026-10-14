package cn.ianzb.hyperrefine.hook.notification

import android.app.NotificationChannel
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Proxy

class NotificationImportanceSettingsHook : BaseHook() {
    override val key = NotificationKeys.IMPORTANCE

    override fun init() {
        val loader = target.classLoader
        val base = Reflect.findClass("com.android.settings.notification.BaseNotificationSettings", loader)
        val settings = Reflect.findClass("com.android.settings.notification.ChannelNotificationSettings", loader)
        val preference = Reflect.findClass("androidx.preference.Preference", loader)
        val listener = Reflect.findClass("androidx.preference.Preference\$OnPreferenceChangeListener", loader)
        val visible = NotificationMembers.method(base, "setPrefVisible", Void.TYPE, preference, java.lang.Boolean.TYPE)
        val setup = NotificationMembers.method(settings, "setupChannelDefaultPrefs", Void.TYPE)
        val importance = NotificationMembers.field(settings, "mImportance")
        val backup = NotificationMembers.field(settings, "mBackupImportance", Integer.TYPE)
        val channel = NotificationMembers.field(settings, "mChannel")
        val backend = NotificationMembers.field(settings, "mBackend")
        val pkg = NotificationMembers.field(settings, "mPkg", String::class.java)
        val uid = NotificationMembers.field(settings, "mUid", Integer.TYPE)
        val guard = NotificationCallbackGuard(tag)

        installNotificationHooks(listOf(
            { HookHelper.hookBefore(visible) { param -> guard.run {
                val pref = param.args[0] ?: return@run
                if (Reflect.callMethod(pref, "getKey") == "importance") param.setArg(1, true)
            } } },
            { HookHelper.hookAfter(setup) { param -> guard.run {
                val screen = param.thisObject ?: return@run
                val pref = Reflect.callMethod(screen, "findPreference", "importance") ?: return@run
                importance.set(screen, pref)
                val previous = backup.getInt(screen)
                // Disabled channels retain Settings' native handling.
                if (previous <= 0) return@run
                val index = Reflect.callMethod(pref, "findSpinnerIndexOfValue", previous.toString()) as? Int ?: -1
                if (index >= 0) Reflect.callMethod(pref, "setValueIndex", index)
                val handler = ImportanceListener { next ->
                    var persisted = false
                    guard.run {
                        val notificationChannel = channel.get(screen) as? NotificationChannel ?: return@run
                        val notificationBackend = backend.get(screen) ?: return@run
                        val state = object : ImportanceChannel {
                            override var importance: Int
                                get() = notificationChannel.importance
                                set(value) { notificationChannel.importance = value }
                            override val userLockedFields: Int
                                get() = Reflect.callMethod(notificationChannel, "getUserLockedFields") as Int
                            override fun lockFields(fields: Int) {
                                Reflect.callMethod(notificationChannel, "lockFields", fields)
                            }
                            override fun unlockFields(fields: Int) {
                                Reflect.callMethod(notificationChannel, "unlockFields", fields)
                            }
                        }
                        persisted = ImportancePersistence.persist(state, next) {
                            Reflect.callMethod(notificationBackend, "updateChannel",
                                pkg.get(screen), uid.getInt(screen), notificationChannel)
                        }
                        backup.setInt(screen, next)
                        Reflect.callMethod(screen, "updateDependents", false)
                    }
                    persisted
                }
                Reflect.callMethod(pref, "setOnPreferenceChangeListener",
                    Proxy.newProxyInstance(listener.classLoader, arrayOf(listener), handler))
            } } },
        ))
    }
}

/** Keep the supplied APK's two distinct icon branches, including its f$2 bypass. */
class NotificationImportanceIconsHook : BaseHook() {
    override val key = NotificationKeys.IMPORTANCE

    private val resolverWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun init() {
        val loader = target.classLoader
        val listener = Reflect.findClass("com.android.systemui.statusbar.notification.MiuiNotificationListener", loader)
        val silent = NotificationMembers.method(listener, "onSilentStatusBarIconsVisibilityChanged", Void.TYPE, java.lang.Boolean.TYPE)
        val mapper = Reflect.findClass(
            "com.android.systemui.statusbar.notification.icon.domain.interactor.NotificationIconsInteractor\$filteredNotifSet\$1\$\$ExternalSyntheticLambda1", loader)
        val invoke = NotificationMembers.method(mapper, "invoke", Any::class.java, Any::class.java)
        val bypass = NotificationMembers.field(mapper, "f\$2", java.lang.Boolean.TYPE)
        val entry = Reflect.findClass("com.android.systemui.statusbar.notification.collection.NotificationEntry", loader)
        val ranking = NotificationMembers.field(entry, "mRanking")
        val getImportance = NotificationMembers.method(ranking.type, "getImportance", Integer.TYPE)
        val collection = NotificationMembers.field(listener, "mNotifCollection")
        val getEntry = NotificationMembers.methods(collection.type, "getEntry") {
            it.parameterTypes.contentEquals(arrayOf(String::class.java)) && entry.isAssignableFrom(it.returnType)
        }.single()
        val stub = Reflect.findClass("miui.stub.MiuiStub", loader)
        val instance = NotificationMembers.field(stub, "INSTANCE")
        val provider = NotificationMembers.field(stub, "mSysUIProvider")
        val notificationProvider = NotificationMembers.field(provider.type, "mNotificationListener")
        val guard = NotificationCallbackGuard(tag)
        var cachedCollection: Any? = null

        installNotificationHooks(listOf(
            { HookHelper.hookBefore(silent) { it.setArg(0, true) } },
            { HookHelper.hookAfter(invoke) { param -> guard.run {
                if (param.result == null || bypass.getBoolean(param.thisObject)) return@run
                val item = param.args[0] ?: return@run
                val itemKey = Reflect.getObjectField(item, "key") as? String ?: return@run
                val notifications = cachedCollection ?: run {
                    val sysUiProvider = provider.get(instance.get(null)) ?: return@run
                    val rawListener = notificationProvider.get(sysUiProvider) ?: return@run
                    // 部分 ROM 该字段是 Provider 包装（再 get() 一层才是监听器），另一些直接就是监听器实例。
                    val listenerInstance = if (listener.isInstance(rawListener)) {
                        rawListener
                    } else {
                        runCatching { Reflect.callMethod(rawListener, "get") }.getOrNull()
                    }
                    if (listenerInstance == null || !listener.isInstance(listenerInstance)) {
                        if (resolverWarned.compareAndSet(false, true)) {
                            HookHelper.log("$tag: notification collection unavailable")
                        }
                        return@run
                    }
                    collection.get(listenerInstance)?.also { cachedCollection = it } ?: return@run
                }
                val notificationEntry = getEntry.invoke(notifications, itemKey) ?: return@run
                val value = getImportance.invoke(ranking.get(notificationEntry)) as Int
                if (NotificationPolicy.suppressIcon(false, value)) param.result = null
            } } },
        ))
    }
}
