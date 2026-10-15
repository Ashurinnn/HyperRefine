package cn.ianzb.hyperrefine.hook.assistant

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.systemui.wallpaper.WallpaperDimKeys

/**
 * 负一屏（`com.miui.personalassistant`）过渡动画优化目标 Load。
 *
 * 分两处安装（各自独立开关）：
 * - `com.miui.personalassistant`：闪黑修复（遮罩起始 alpha 归零）、上滑合并强制反向、
 *   过渡遮罩重绘、打开应用动画；
 * - `system`（system_server）：放行远程动画权限（需重启设备生效）。
 */
class AssistantLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(
        AssistantKeys.TARGET_PACKAGE,
        AssistantKeys.TARGET_SYSTEM,
    )

    override fun onPackageLoaded(target: PackageTarget) {
        when {
            target.isSystemServer ->
                initHook(
                    AssistantRemoteAnimPermissionHook(),
                    HookPrefs.getBoolean(AssistantKeys.REMOTE_ANIM_PERMISSION, false),
                )

            target.packageName == AssistantKeys.TARGET_PACKAGE -> {
                initHook(
                    AssistantWallpaperDimBlurHook(),
                    HookPrefs.getBoolean(WallpaperDimKeys.ENABLED, false),
                )
                initHook(
                    AssistantMaskAlphaHook(),
                    HookPrefs.getBoolean(AssistantKeys.MASK_ALPHA_ZERO, false),
                )
                initHook(
                    AssistantForceReverseHook(),
                    HookPrefs.getBoolean(AssistantKeys.FORCE_REVERSE_MERGE, false),
                )
                initHook(
                    AssistantMaskBufferHook(),
                    HookPrefs.getBoolean(AssistantKeys.MASK_BUFFER, false),
                )
                initHook(
                    AssistantOpenAnimHook(),
                    HookPrefs.getBoolean(AssistantKeys.OPEN_ANIM, false),
                )
                initHook(
                    AssistantReturnFlightHook(),
                    HookPrefs.getBoolean(AssistantKeys.RETURN_FLIGHT, false),
                )
            }
        }
    }
}
