package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 多分屏无级调节窗口宽度。
 *
 * 目标进程：`com.android.systemui`（小米 WMShell）。
 *
 * 多分屏（2–6 个应用）的分割线位置由 `MultipleSplitSnapAlgorithm` 约束：
 * - 松手时 `calculateSnapTarget` 把分割线吸附到固定档位（snapPosition 21..34），
 *   拖拽过程中 `MultipleSplitLayout#calculateTargetSnapOffset` 还会持续把分割线往档位吸；
 * - `MultipleSplitLayout#isValidDividerPosition` 直接拒绝不在任何档位上的位置
 *   （`getSnapPosition == 10`，即 SNAP_TO_NONE），`isValidWidth` 也只认由档位间距算出的离散宽度。
 *
 * 这里在该功能开启时：
 * 1. `calculateSnapTarget` 返回官方自由目标（position 取当前值、snapPosition 10），
 *    松手停在原地、也不再被吸向档位；
 * 2. `MultipleSplitLayout#getSnapPosition` 把 10 映射成 0，让自由位置通过校验；
 * 3. `isValidWidth` 放宽为「最小宽度 ≤ 宽度 ≤ 可用空间」的连续范围（与官方由档位间距推出的
 *    最小 / 最大宽度一致），拖拽时的最小宽度限制仍由 `updateMultipleDividerPosition` 保证。
 */
class MultipleSplitFreeRatioHook : BaseHook() {

    override val key: String = SplitScreenKeys.MULTIPLE_SPLIT_FREE_RATIO

    /** 平板 / 折叠屏专属：手机（直板）保持官方行为。 */
    override val deviceScope: Set<DeviceType> = setOf(DeviceType.PAD, DeviceType.FOLD)

    private var loader: ClassLoader? = null
    private var magnetRadius: Int? = null

    override fun init() {
        val loader = target.classLoader ?: throw HookSkippedException("no classloader")
        this.loader = loader
        val hooked = hookCalculateSnapTarget(loader) + hookLayoutSnapPosition(loader) + hookValidWidth(loader)
        if (hooked == 0) throw HookSkippedException("multiple split classes not found")
        HookHelper.log("$tag: hooked $hooked method(s)")
    }

    /** 松手 / 拖拽吸附：返回自由目标（就地停住）。 */
    private fun hookCalculateSnapTarget(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(SNAP_ALGORITHM, loader) ?: run {
            HookHelper.log("$tag: $SNAP_ALGORITHM not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "calculateSnapTarget" && it.parameterCount == 3 }
            ?: run {
                HookHelper.log("$tag: calculateSnapTarget not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param ->
                val algorithm = param.thisObject ?: return@hookBefore
                val position = param.args.getOrNull(0) as? Int ?: return@hookBefore
                // 磁吸：松手位置非常接近系统档位时仍然吸附过去，其余位置就地停住。
                SplitScreenSupport.nearestSnapTarget(algorithm, position, magnetRadiusPx())?.let {
                    param.setResultValue(it)
                    return@hookBefore
                }
                newFreeTarget(algorithm, position)?.let { param.setResultValue(it) }
            }
            1
        }.onFailure { HookHelper.log("$tag: hook calculateSnapTarget failed", it) }.getOrDefault(0)
    }

    /** 位置校验：把「不在档位上」（10）映射成普通值，使自由位置可以通过。 */
    private fun hookLayoutSnapPosition(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(SPLIT_LAYOUT, loader) ?: run {
            HookHelper.log("$tag: $SPLIT_LAYOUT not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "getSnapPosition" && it.parameterCount == 1 }
            ?: run {
                HookHelper.log("$tag: MultipleSplitLayout#getSnapPosition not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookAfter(method) { param ->
                if (param.result as? Int == SNAP_TO_NONE) param.setResultValue(0)
            }
            1
        }.onFailure { HookHelper.log("$tag: hook getSnapPosition failed", it) }.getOrDefault(0)
    }

    /** 宽度校验：允许最小宽度与可用空间之间的任意宽度。 */
    private fun hookValidWidth(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(SNAP_ALGORITHM, loader) ?: return 0
        val method = cls.declaredMethods
            .firstOrNull { it.name == "isValidWidth" && it.parameterCount == 1 }
            ?: run {
                HookHelper.log("$tag: isValidWidth not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookAfter(method) { param ->
                if (param.result as? Boolean == true) return@hookAfter
                val algorithm = param.thisObject ?: return@hookAfter
                val width = param.args.getOrNull(0) as? Int ?: return@hookAfter
                val mini = intField(algorithm, "mMiniMoveWidth") ?: return@hookAfter
                val max = intField(algorithm, "mMaxMoveWidth") ?: return@hookAfter
                if (width >= mini && (width <= max - mini || width == max)) {
                    param.setResultValue(true)
                }
            }
            1
        }.onFailure { HookHelper.log("$tag: hook isValidWidth failed", it) }.getOrDefault(0)
    }

    /** `new SnapTarget(position, 10, 1.0f, false)`（兼容内部类构造器多一个外部实例的变体）。 */
    private fun newFreeTarget(algorithm: Any, position: Int): Any? {
        val loader = loader ?: return null
        val targetCls = runCatching { Reflect.findClass(SNAP_TARGET, loader) }.getOrNull() ?: return null
        return runCatching {
            Reflect.newInstance(targetCls, algorithm, position, SNAP_TO_NONE, 1.0f, false)
        }.recoverCatching {
            Reflect.newInstance(targetCls, position, SNAP_TO_NONE, 1.0f, false)
        }.getOrNull()
    }

    /** 磁吸半径（px），惰性换算一次。 */
    private fun magnetRadiusPx(): Int =
        magnetRadius ?: SplitScreenSupport.dpToPx(MAGNET_DP).also { magnetRadius = it }

    private fun intField(target: Any, name: String): Int? =
        runCatching { Reflect.findField(target.javaClass, name).get(target) as? Int }.getOrNull()

    private companion object {
        const val SNAP_ALGORITHM = "com.android.wm.shell.multiplesplit.MultipleSplitSnapAlgorithm"
        const val SNAP_TARGET = "com.android.wm.shell.multiplesplit.MultipleSplitSnapAlgorithm\$SnapTarget"
        const val SPLIT_LAYOUT = "com.android.wm.shell.multiplesplit.MultipleSplitLayout"

        /** `SNAP_TO_NONE`：`MultipleSplitSnapAlgorithm#getSnapPosition` 对非档位位置返回的值。 */
        const val SNAP_TO_NONE = 10

        /** 磁吸半径（dp）：无级调节下，距系统档位小于该距离即吸附过去。 */
        const val MAGNET_DP = 24f
    }
}
