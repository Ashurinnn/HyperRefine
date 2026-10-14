package cn.ianzb.hyperrefine.hook.assistant

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.concurrent.atomic.AtomicInteger

/**
 * system_server 侧放行负一屏的远程动画权限。
 *
 * 背景（思路参考 HyperAssistant，独立实现）：`com.miui.personalassistant` 要播放「远程动画」
 * （跨进程窗口过渡，由系统框架的 HyperViewScale 引擎驱动）需要
 * `android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`；部分 ROM 的允许名单里没有
 * 负一屏，过渡就会落回普通动画路径。这里在 `ActivityTaskManagerService.checkPermission` 上
 * 对「该权限 + 负一屏 uid」直接放行，负一屏自己的过渡动画才能完整生效。
 *
 * 目标进程：system_server（`system`）。开关变更需重启设备生效。
 */
class AssistantRemoteAnimPermissionHook : BaseHook() {

    override val key: String = AssistantKeys.REMOTE_ANIM_PERMISSION

    @Volatile
    private var resolvedAppId = -1

    private val logCount = AtomicInteger(0)

    override fun init() {
        val atms = Reflect.findClassIfExists(ATMS, target.classLoader) ?: run {
            HookHelper.log("$tag: $ATMS not found")
            return
        }
        HookHelper.hookAllMethods(atms, "checkPermission") { param ->
            val uid = param.args.getOrNull(2) as? Int ?: return@hookAllMethods
            if (!isTargetUid(uid)) return@hookAllMethods
            val permission = param.args.getOrNull(0) as? String ?: return@hookAllMethods
            if (permission != PERMISSION) return@hookAllMethods
            param.setResultValue(PERMISSION_GRANTED)
            if (logCount.getAndIncrement() < 20) {
                HookHelper.log("$tag: granted $PERMISSION to uid=$uid")
            }
        }
    }

    /**
     * 判定 uid 是否属于负一屏（多用户下 `uid % 100000` 即 appId）。
     * 动态解析成功后走缓存；未成功前先按小米 ROM 的固定 appId 命中，并在命中时尝试解析。
     */
    private fun isTargetUid(uid: Int): Boolean {
        val uidApp = uid % UID_PER_USER
        val cached = resolvedAppId
        if (cached > 0) return uidApp == cached
        if (uidApp != FALLBACK_APP_ID) return false
        val resolved = resolveAppId()
        if (resolved != null && resolved > 0) resolvedAppId = resolved
        return true
    }

    /** 通过 system context 的 PackageManager 解析负一屏的 appId；拿不到返回 null。 */
    private fun resolveAppId(): Int? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread")
            .invoke(null) ?: return@runCatching null
        val context = Reflect.callMethod(activityThread, "getSystemContext") ?: return@runCatching null
        val pm = Reflect.callMethod(context, "getPackageManager") ?: return@runCatching null
        val info = Reflect.callMethod(pm, "getPackageInfo", AssistantKeys.TARGET_PACKAGE, 0)
            ?: return@runCatching null
        val applicationInfo = Reflect.callMethod(info, "getApplicationInfo") ?: return@runCatching null
        (Reflect.callMethod(applicationInfo, "getUid") as? Int)?.rem(UID_PER_USER)
    }.getOrNull()

    private companion object {
        const val ATMS = "com.android.server.wm.ActivityTaskManagerService"
        const val PERMISSION = "android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS"
        const val PERMISSION_GRANTED = 0
        const val UID_PER_USER = 100000

        /** 小米 ROM 上负一屏的固定 appId（动态解析失败时兜底）。 */
        const val FALLBACK_APP_ID = 10143
    }
}
