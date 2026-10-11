package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method

/**
 * 解除平板分屏方向限制（官方：竖屏只能上下、横屏只能左右）。
 *
 * 目标进程：`com.android.systemui`（小米 WMShell `Miui-WindowManager-Shell.jar` 在该进程内运行）。
 *
 * 判定点（只反射调用系统自身实现，不复制任何系统代码）：
 * - 竖屏左右：`SplitUtilsImpl#allowLeftRightSplitInPortrait(Context)`，即「资源
 *   `config_leftRightSplitInPortrait_miui` ∧ 调试开关」；`SplitLayout` 在构造 / 旋转 / 配置变化
 *   时都会重新取该值，恒定返回 true 即可放行。AOSP 路径的
 *   `SplitScreenUtils#allowLeftRightSplitInPortrait(Resources)` 一并放行。
 * - 横屏上下：`SoScUtilsImpl#supportTopBottomSplitFeature()` 对平板写死 `!isLandscape()`，
 *   并且 `canEnterSplitDirection()` 也依赖它；`supportTopBottomSplitDevice()` 同时放行。
 *
 * 方向判定本身（分屏线菜单切方向、把手菜单图标）由无独立开关的 [SplitDirectionCompatHook] 统一处理。
 */
class SplitDirectionUnlockHook : BaseHook() {

    override val key: String = SplitScreenKeys.UNLOCK_DIRECTION

    /** 平板 / 折叠屏专属：手机（直板）保持官方行为。 */
    override val deviceScope: Set<DeviceType> = setOf(DeviceType.PAD, DeviceType.FOLD)

    override fun init() {
        val loader = target.classLoader ?: throw HookSkippedException("no classloader")
        val hooked = hookTrue(loader, SplitScreenSupport.SPLIT_UTILS_IMPL, "allowLeftRightSplitInPortrait", 1) +
            hookTrue(loader, SplitScreenSupport.SPLIT_SCREEN_UTILS, "allowLeftRightSplitInPortrait", 1) +
            hookTrue(loader, SplitScreenSupport.SOSC_UTILS_IMPL, "supportTopBottomSplitFeature", 0) +
            hookTrue(loader, SplitScreenSupport.SOSC_UTILS_IMPL, "supportTopBottomSplitDevice", 0)
        if (hooked == 0) throw HookSkippedException("WMShell split classes not found")
        HookHelper.log("$tag: hooked $hooked method(s)")
    }

    /** 挂载 `className#name`（参数个数 [params]）并恒定返回 true；返回命中数量。 */
    private fun hookTrue(loader: ClassLoader, className: String, name: String, params: Int): Int {
        val cls = Reflect.findClassIfExists(className, loader) ?: run {
            HookHelper.log("$tag: $className not found")
            return 0
        }
        val method: Method = cls.declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == params
        } ?: run {
            HookHelper.log("$tag: $className#$name/$params not found")
            return 0
        }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param -> param.setResultValue(true) }
            1
        }.onFailure { HookHelper.log("$tag: hook $className#$name failed", it) }.getOrDefault(0)
    }
}
