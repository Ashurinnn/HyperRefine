package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 分屏方向判定兼容层（无独立开关，依附「解除分屏方向限制」或「把手菜单同时显示双向分屏」）。
 *
 * 官方 `SplitUtilsImpl#isLeftRightSplit()` 在平板横屏时无视「动态方向标志」，导致分屏线菜单切到
 * 「上下」不生效；`MulWinSwitchSplitUtils#isLeftRightSplit()` 在未进入分屏时又恒返回「上下」，
 * 于是把手菜单的分屏图标与实际方向不符。这里把两处统一为
 * [SplitScreenSupport.effectiveLeftRightSplit]（平板：横屏 == 动态方向标志）。
 */
class SplitDirectionCompatHook : BaseHook() {

    override val key: String = KEY

    /** 平板 / 折叠屏专属：仅服务方向解锁 / 把手菜单双向分屏，手机不安装。 */
    override val deviceScope: Set<DeviceType> = setOf(DeviceType.PAD, DeviceType.FOLD)

    override fun init() {
        val loader = target.classLoader ?: throw HookSkippedException("no classloader")
        val hooked = hook(loader, SplitScreenSupport.SPLIT_UTILS_IMPL) +
            hook(loader, SplitScreenSupport.MULWIN_SPLIT_UTILS)
        if (hooked == 0) throw HookSkippedException("WMShell split classes not found")
    }

    private fun hook(loader: ClassLoader, className: String): Int {
        val cls = Reflect.findClassIfExists(className, loader) ?: run {
            HookHelper.log("$tag: $className not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "isLeftRightSplit" && it.parameterCount == 0 }
            ?: run {
                HookHelper.log("$tag: $className#isLeftRightSplit() not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param ->
                SplitScreenSupport.effectiveLeftRightSplit(loader)?.let { param.setResultValue(it) }
            }
            1
        }.onFailure { HookHelper.log("$tag: hook $className#isLeftRightSplit failed", it) }.getOrDefault(0)
    }

    companion object {
        /** 内部键：没有对应配置项，仅用于状态上报去重。 */
        const val KEY = "sosc_split_direction_compat"
    }
}
