package cn.ianzb.hyperrefine.hook.assistant

import android.os.SystemClock
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.concurrent.atomic.AtomicInteger

/**
 * system_server 侧放行负一屏的远程动画权限。
 *
 * 背景（思路参考 HyperAssistant，独立实现）：`com.miui.personalassistant` 用
 * `miuix.transition.ActivityOptionsHelper` 打开应用时会给启动参数带上 `RemoteTransition`
 * （HVS 打开动画），而 `SafeActivityOptions.checkPermissions` 会校验
 * `android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`——负一屏通常没有这个签名权限，
 * 于是抛 `SecurityException: ... with remoteTransition`，动画退化为默认路径。这里把该权限对
 * 负一屏 uid 放行，覆盖两条校验路径：
 * - `ActivityTaskManagerService.checkPermission`（`SafeActivityOptions` 的直接调用点）；
 * - `ActivityManagerService.checkComponentPermission`（所有权限校验的汇聚点，
 *   同时覆盖 `enforceCallingPermission` 等路径）。
 *
 * 负一屏的 appId 因 ROM 而异（实测 10140 / 10143 等）：安装时动态解析并缓存，解析失败时才
 * 回退小米 ROM 常见固定值，并在首次校验时低频重试解析。
 *
 * 目标进程：system_server（`system`）。开关变更需重启设备生效。
 */
class AssistantRemoteAnimPermissionHook : BaseHook() {

    override val key: String = AssistantKeys.REMOTE_ANIM_PERMISSION

    @Volatile
    private var resolvedAppId = -1

    @Volatile
    private var lastResolveAttempt = 0L

    private val logCount = AtomicInteger(0)

    override fun init() {
        val loader = target.classLoader
        installGate(loader, ATMS, "checkPermission")
        installGate(loader, AMS, "checkComponentPermission")
        val appId = appId()
        if (appId > 0 && appId != FALLBACK_APP_ID) {
            HookHelper.log("$tag: personalassistant appId = $appId")
        }
    }

    private fun installGate(loader: ClassLoader?, className: String, methodName: String) {
        val clazz = Reflect.findClassIfExists(className, loader) ?: run {
            HookHelper.log("$tag: $className not found")
            return
        }
        val methods = clazz.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) {
            HookHelper.log("$tag: $className.$methodName not found")
            return
        }
        methods.forEach { method ->
            runCatching {
                HookHelper.hookBefore(method) { param ->
                    val uid = param.args.getOrNull(2) as? Int ?: return@hookBefore
                    if (!isTargetUid(uid)) return@hookBefore
                    val permission = param.args.getOrNull(0) as? String ?: return@hookBefore
                    if (permission != PERMISSION) return@hookBefore
                    param.setResultValue(PERMISSION_GRANTED)
                    if (logCount.getAndIncrement() < 20) {
                        HookHelper.log("$tag: granted $PERMISSION to uid=$uid via ${clazz.simpleName}.$methodName")
                    }
                }
            }.onFailure { HookHelper.log("$tag: hook $className.$methodName failed", it) }
        }
        HookHelper.log("$tag: $className.$methodName hooked (${methods.size})")
    }

    /** 负一屏 appId（多用户 uid % 100000）；解析成功前按固定值兜底并低频重试。 */
    private fun isTargetUid(uid: Int): Boolean {
        val uidApp = uid % UID_PER_USER
        val cached = resolvedAppId
        if (cached > 0) return uidApp == cached
        val now = SystemClock.uptimeMillis()
        if (now - lastResolveAttempt > RESOLVE_RETRY_MS) {
            lastResolveAttempt = now
            val resolved = resolveAppId()
            if (resolved != null && resolved > 0) {
                resolvedAppId = resolved
                HookHelper.log("$tag: appId resolved = $resolved")
                return uidApp == resolved
            }
        }
        return uidApp == FALLBACK_APP_ID
    }

    private fun appId(): Int {
        val cached = resolvedAppId
        if (cached > 0) return cached
        val resolved = resolveAppId()
        if (resolved != null && resolved > 0) resolvedAppId = resolved
        return resolved?.takeIf { it > 0 } ?: FALLBACK_APP_ID
    }

    private fun resolveAppId(): Int? =
        resolveViaSystemContext()?.takeIf { it > 0 } ?: resolveViaPackageManager()

    /** system context → PackageManager。 */
    private fun resolveViaSystemContext(): Int? = runCatching {
        val thread = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread")
            .invoke(null) ?: return@runCatching null
        val context = Reflect.callMethod(thread, "getSystemContext") ?: return@runCatching null
        val pm = Reflect.callMethod(context, "getPackageManager") ?: return@runCatching null
        val info = Reflect.callMethod(pm, "getPackageInfo", AssistantKeys.TARGET_PACKAGE, 0)
            ?: return@runCatching null
        val applicationInfo = Reflect.callMethod(info, "getApplicationInfo") ?: return@runCatching null
        (Reflect.callMethod(applicationInfo, "getUid") as? Int)?.rem(UID_PER_USER)
    }.getOrNull()

    /** `ActivityThread.getPackageManager()`（IPackageManager，不依赖 system context）。 */
    private fun resolveViaPackageManager(): Int? = runCatching {
        val threadClass = Class.forName("android.app.ActivityThread")
        val pm = threadClass.getMethod("getPackageManager").invoke(null) ?: return@runCatching null
        val uid = runCatching {
            pm.javaClass.getMethod(
                "getPackageUid",
                String::class.java,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(pm, AssistantKeys.TARGET_PACKAGE, 0L, 0)
        }.getOrNull() ?: runCatching {
            pm.javaClass.getMethod(
                "getPackageUid",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(pm, AssistantKeys.TARGET_PACKAGE, 0, 0)
        }.getOrNull()
        (uid as? Int)?.rem(UID_PER_USER)
    }.getOrNull()

    private companion object {
        const val ATMS = "com.android.server.wm.ActivityTaskManagerService"
        const val AMS = "com.android.server.am.ActivityManagerService"
        const val PERMISSION = "android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS"
        const val PERMISSION_GRANTED = 0
        const val UID_PER_USER = 100000
        const val RESOLVE_RETRY_MS = 1000L

        /** 小米 ROM 上负一屏常见固定 appId（动态解析失败时兜底）。 */
        const val FALLBACK_APP_ID = 10143
    }
}
