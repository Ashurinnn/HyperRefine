package cn.ianzb.hyperrefine.hook.systemui

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import kotlin.math.abs

/**
 * 切换分屏方向（左右 ↔ 上下）时保持每个应用的占比不变。
 *
 * 目标进程：`com.android.systemui`（小米 WMShell）。
 *
 * 官方 `SplitLayout#initDividerPosition(旧 bounds, 旧方向)` 本身会按
 * 「旧方向占比 × 新方向尺寸」换算分割线位置，但随后有两条路径会把占比改掉：
 * 1) `SoScStageCoordinator#switchSplitDirection` 在切到左右分屏、且分割线不在 1:1 时，
 *    会按焦点把分割线强推到 1:9 档位（`setDividerPositionInRatio1_9`）——这里在切换期间
 *    让 `is1_9SplitSnapMode()` 按需返回 false，拦掉紧跟在方向切换后的这一次强推；
 * 2) 切换过程中 `onPreSoScStateChanged` 在「分割线锁定」（平板上桌面常置 true）时会把
 *    布局边界钳回 1:1（单应用分屏则直接在 `initDividerPosition` 里重置 1:1）——这里在
 *    切换期间临时挂起该标志，结束后按桌面最新一次推送恢复。
 *
 * 占比快照取自 `initDividerPosition` 的入参（旧方向 + 旧方向尺寸），因此把手菜单切换方向、
 * 分屏线菜单切换方向与旋屏（旋转 / 配置变化）都等价命中；方向真正切换后按同一占比重写
 * 分割线位置，并同步「固定吸附目标」（`mFixedSnapTarget`）与分屏状态。切换瞬间读实时位置，
 * 不按方向分别记忆、没有延迟。
 *
 * 「跟随应用」：把手菜单点另一方向的分屏按钮时，[SplitRatioBridge] 会先写入「点击应用在
 * 切换前的占比」（点击监听里在侧序对齐之前记录），本 Hook 优先采用它，使占比跟着应用走。
 */
class SplitRatioMemoryHook : BaseHook() {

    override val key: String = SplitScreenKeys.SPLIT_RATIO_MEMORY

    private var loader: ClassLoader? = null

    /** `initDividerPosition` 调用前的占比快照（官方同步调用，同一线程读写）。 */
    private var pending: RatioSnapshot? = null

    /** 最近一次方向切换写入的目标，用于切换收尾复查。 */
    private var applied: AppliedRatio? = null

    /** 是否处于官方 `switchSplitDirection` 的同步执行期。 */
    private var switchActive = false

    /** 切换期间临时挂起 `mIsDividerFixed` 前记录的原值。 */
    private var dividerFixedOriginal: Boolean? = null

    /** 切换期间桌面重新推送的 isFixed（以最新一次为准）。 */
    private var dividerFixedLatest: Boolean? = null

    /** 已武装的「拦掉本次切换内的 1:9 强推」标记。 */
    private var suppress1_9 = false

    private val handler = Handler(Looper.getMainLooper())
    private var safetyRestore: Runnable? = null

    override fun init() {
        val loader = target.classLoader ?: throw HookSkippedException("no classloader")
        this.loader = loader
        val hooked = hookInitDividerPosition(loader) +
            hookSwitchSplitDirection(loader) +
            hook1_9SnapMode(loader) +
            hookDividerFixedState(loader)
        if (hooked == 0) throw HookSkippedException("split ratio memory hooks not installed")
        HookHelper.log("$tag: hooked $hooked method(s)")
    }

    // ---------------- SplitLayout#initDividerPosition ----------------

    private fun hookInitDividerPosition(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(SPLIT_LAYOUT, loader) ?: run {
            HookHelper.log("$tag: $SPLIT_LAYOUT not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "initDividerPosition" && it.parameterCount == 2 }
            ?: run {
                HookHelper.log("$tag: SplitLayout#initDividerPosition not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param -> runCatching { capture(param) } }
            HookHelper.hookAfter(method) { param -> runCatching { restore(param) } }
            1
        }.onFailure { HookHelper.log("$tag: hook initDividerPosition failed", it) }.getOrDefault(0)
    }

    /** 记录「切换前」的占比：官方入参 = 旧 bounds + 旧方向。 */
    private fun capture(param: HookParam) {
        pending = null
        val layout = param.thisObject ?: return
        val rect = param.args.getOrNull(0) as? Rect ?: return
        val oldLeftRight = param.args.getOrNull(1) as? Boolean ?: return
        val cl = loader ?: return
        if (!SplitScreenSupport.isSoScActive(cl)) return
        if (isMinimized()) return
        if (switchActive) {
            // 把手菜单点击「另一方向」的分屏按钮时写入的「跟随应用」占比。
            SplitRatioBridge.consume()?.let { ratio ->
                if (ratio > MIN_RATIO && ratio < MAX_RATIO) {
                    pending = RatioSnapshot(ratio, oldLeftRight, "menu app share")
                    return
                }
            }
        }
        val position = intField(layout, "mDividerPosition") ?: return
        val dimension = if (oldLeftRight) rect.width() else rect.height()
        if (position <= 0 || dimension <= 0) return
        val ratio = position.toFloat() / dimension
        if (ratio <= MIN_RATIO || ratio >= MAX_RATIO) return
        pending = RatioSnapshot(ratio, oldLeftRight, "live $position/$dimension")
    }

    /** 方向真正切换后，按同一占比把分割线写回新方向。 */
    private fun restore(param: HookParam) {
        val layout = param.thisObject ?: return
        val snapshot = pending ?: return
        pending = null
        val newLeftRight = boolField(layout, "mIsLeftRightSplit") ?: return
        if (newLeftRight == snapshot.oldLeftRight) return
        val dimension = axisDimension(layout) ?: return
        if (dimension <= 0) return
        val position = applyRatio(layout, snapshot.ratio, dimension)
        applied = AppliedRatio(snapshot.ratio, newLeftRight, position)
        if (switchActive) suppress1_9 = true
        HookHelper.log(
            "$tag: keep ratio ${percent(snapshot.ratio)} across direction switch " +
                "(${snapshot.from} -> pos=$position/$dimension, leftRight=$newLeftRight)",
        )
    }

    /** 把占比写回分割线：位置 + 分屏状态 + 固定吸附目标。 */
    private fun applyRatio(layout: Any, ratio: Float, dimension: Int): Int {
        val position = targetPosition(layout, ratio, dimension)
        runCatching {
            Reflect.callMethod(layout, "setDividerPosition", position, false)
            Reflect.callMethod(layout, "updateStateWithCurrentPosition")
        }.onFailure { HookHelper.log("$tag: apply ratio failed", it) }
        syncFixedSnapTarget(layout, position)
        return position
    }

    /** 占比 → 新方向的分割线位置（含档位贴回）。 */
    private fun targetPosition(layout: Any, ratio: Float, dimension: Int): Int =
        alignToNearbyTarget(
            layout,
            (dimension * ratio).toInt().coerceIn(1, dimension - 1),
        )

    /**
     * 占比换算（两个方向的尺寸不同）天然会有半个分割线的取整误差：非常接近 1:1 档位
     * 或 1:9 档位时贴回档位，避免「切过去再切回来」逐次漂移，也让 1:9 的移出屏幕形态精确复现。
     */
    private fun alignToNearbyTarget(layout: Any, position: Int): Int {
        val algorithm = runCatching { Reflect.callMethod(layout, "getDividerSnapAlgorithm") }
            .getOrNull() ?: return position
        val candidates = listOf(
            "mMiddleTarget" to MIDDLE_TOLERANCE_PX,
            "mFirstSplitTarget" to EDGE_TOLERANCE_PX,
            "mLastSplitTarget" to EDGE_TOLERANCE_PX,
        )
        candidates.forEach { (field, tolerance) ->
            val target = runCatching { Reflect.getObjectField(algorithm, field) }.getOrNull()
                ?: return@forEach
            val targetPosition = intField(target, "position") ?: return@forEach
            if (abs(position - targetPosition) <= tolerance) return targetPosition
        }
        return position
    }

    /**
     * 与官方 `initDividerPosition` 一致：同步「固定吸附目标」。
     * 不同步的话，单应用分屏下松手吸附会继续用换算前的旧目标。
     */
    private fun syncFixedSnapTarget(layout: Any, position: Int) {
        runCatching {
            val algorithm = Reflect.callMethod(layout, "getDividerSnapAlgorithm") ?: return
            val target = Reflect.callMethod(algorithm, "calculateNonDismissingSnapTarget", position)
                ?: return
            Reflect.setObjectField(layout, "mFixedSnapTarget", target)
        }.onFailure { HookHelper.log("$tag: sync fixed snap target failed", it) }
    }

    // ---------------- SoScStageCoordinator#switchSplitDirection ----------------

    private fun hookSwitchSplitDirection(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(STAGE_COORDINATOR, loader) ?: run {
            HookHelper.log("$tag: $STAGE_COORDINATOR not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "switchSplitDirection" && it.parameterCount == 1 }
            ?: run {
                HookHelper.log("$tag: SoScStageCoordinator#switchSplitDirection not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param ->
                runCatching { onSwitchBegin(param.thisObject) }
                    .onFailure { HookHelper.log("$tag: switch begin failed", it) }
            }
            HookHelper.hookAfter(method) { param ->
                runCatching { onSwitchEnd(param.thisObject) }
                    .onFailure { HookHelper.log("$tag: switch end failed", it) }
            }
            1
        }.onFailure { HookHelper.log("$tag: hook switchSplitDirection failed", it) }.getOrDefault(0)
    }

    private fun onSwitchBegin(coordinator: Any?) {
        switchActive = true
        pending = null
        applied = null
        suppress1_9 = false
        dividerFixedLatest = null
        dividerFixedOriginal = coordinator?.let { boolField(it, "mIsDividerFixed") }
        // 平板上桌面常把「分割线锁定」置为 true：切换期间 onPreSoScStateChanged 会据此把
        // 布局边界钳回 1:1，先把标志临时挂起；结束后按桌面最新一次推送恢复。
        if (coordinator != null && dividerFixedOriginal == true) {
            setBoolField(coordinator, "mIsDividerFixed", false)
            scheduleSafetyRestore(coordinator)
            HookHelper.log("$tag: divider fixed suspended during direction switch")
        }
    }

    private fun onSwitchEnd(coordinator: Any?) {
        switchActive = false
        suppress1_9 = false
        cancelSafetyRestore()
        if (coordinator != null) {
            (dividerFixedLatest ?: dividerFixedOriginal)?.let { setBoolField(coordinator, "mIsDividerFixed", it) }
        }
        dividerFixedOriginal = null
        dividerFixedLatest = null
        verifyApplied()
    }

    /** 切换收尾复查：理论上分割线已在目标位置（1:9 强推与 1:1 钳制都被拦掉），有偏差再补一次。 */
    private fun verifyApplied() {
        val expected = applied ?: return
        applied = null
        val layout = SplitScreenSupport.splitLayout(loader ?: return) ?: return
        val current = intField(layout, "mDividerPosition") ?: return
        val leftRight = boolField(layout, "mIsLeftRightSplit")
        val dimension = axisDimension(layout) ?: return
        val target = targetPosition(layout, expected.ratio, dimension)
        if (leftRight != expected.leftRight || abs(current - target) > DRIFT_TOLERANCE_PX) {
            HookHelper.log("$tag: divider drift at switch end (pos=$current, expected=$target), re-apply")
            applyRatio(layout, expected.ratio, dimension)
        } else {
            HookHelper.log("$tag: switch end verify ok (pos=$current, ${percent(expected.ratio)})")
        }
    }

    // ---------------- DividerSnapAlgorithm#is1_9SplitSnapMode ----------------

    /**
     * `switchSplitDirection` 在方向切换后紧跟一段「切到左右分屏且不在 1:1 → 按焦点强推
     * 1:9 档位」的逻辑（同一个同步调用链里的第一次 `is1_9SplitSnapMode()` 查询）。
     * 若刚刚按占比还原过分割线，这里返回 false 拦掉这一次，保持占比不变。
     */
    private fun hook1_9SnapMode(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(DIVIDER_SNAP_ALGORITHM, loader) ?: run {
            HookHelper.log("$tag: $DIVIDER_SNAP_ALGORITHM not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "is1_9SplitSnapMode" && it.parameterCount == 0 }
            ?: run {
                HookHelper.log("$tag: DividerSnapAlgorithm#is1_9SplitSnapMode not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param ->
                if (suppress1_9) {
                    suppress1_9 = false
                    param.setResultValue(false)
                    HookHelper.log("$tag: suppressed 1:9 forcing during direction switch")
                }
            }
            1
        }.onFailure { HookHelper.log("$tag: hook is1_9SplitSnapMode failed", it) }.getOrDefault(0)
    }

    // ---------------- SoScStageCoordinator#updateDividerFixedState ----------------

    /** 切换期间桌面若重新推送「分割线锁定」，只记录不生效，收尾时按最新值恢复。 */
    private fun hookDividerFixedState(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(STAGE_COORDINATOR, loader) ?: return 0
        val method = cls.declaredMethods
            .firstOrNull { it.name == "updateDividerFixedState" && it.parameterCount == 1 }
            ?: return 0
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param ->
                if (!switchActive) return@hookBefore
                val fixed = param.args.getOrNull(0) as? Boolean ?: return@hookBefore
                dividerFixedLatest = fixed
                if (fixed) param.setResultValue(null)
                HookHelper.log("$tag: divider fixed push during switch (isFixed=$fixed, deferred)")
            }
            1
        }.onFailure { HookHelper.log("$tag: hook updateDividerFixedState failed", it) }.getOrDefault(0)
    }

    /**
     * 兜底：万一 `switchSplitDirection` 以异常结束导致收尾 Hook 没跑到，
     * 延时把「分割线锁定」按最新值恢复，避免标志一直挂起。
     */
    private fun scheduleSafetyRestore(coordinator: Any) {
        cancelSafetyRestore()
        val runnable = Runnable {
            safetyRestore = null
            switchActive = false
            (dividerFixedLatest ?: dividerFixedOriginal)?.let { value ->
                if (boolField(coordinator, "mIsDividerFixed") != value) {
                    setBoolField(coordinator, "mIsDividerFixed", value)
                    HookHelper.log("$tag: safety restore dividerFixed=$value")
                }
            }
        }
        safetyRestore = runnable
        handler.postDelayed(runnable, SAFETY_RESTORE_MS)
    }

    private fun cancelSafetyRestore() {
        safetyRestore?.let { handler.removeCallbacks(it) }
        safetyRestore = null
    }

    // ---------------- 工具 ----------------

    /** 分割线所在轴的尺寸（新方向 + 新 bounds）。 */
    private fun axisDimension(layout: Any): Int? {
        val leftRight = boolField(layout, "mIsLeftRightSplit") ?: return null
        val root = runCatching { Reflect.callMethod(layout, "getRootBounds") as? Rect }.getOrNull()
            ?: return null
        return if (leftRight) root.width() else root.height()
    }

    /** 迷你小窗等最小化形态下占比没有意义，保持官方行为。 */
    private fun isMinimized(): Boolean = runCatching {
        val cl = loader ?: return@runCatching false
        val soSc = SplitScreenSupport.soScUtils(cl) ?: return@runCatching false
        (Reflect.callMethod(soSc, "inSoScMinimizedMode") as? Boolean) == true
    }.getOrDefault(false)

    private fun intField(target: Any, name: String): Int? =
        runCatching { Reflect.findField(target.javaClass, name).get(target) as? Int }.getOrNull()

    private fun boolField(target: Any, name: String): Boolean? =
        runCatching { Reflect.findField(target.javaClass, name).get(target) as? Boolean }.getOrNull()

    private fun setBoolField(target: Any, name: String, value: Boolean) =
        runCatching { Reflect.setObjectField(target, name, value) }
            .onFailure { HookHelper.log("$tag: set $name=$value failed", it) }

    private fun percent(ratio: Float): String = "${(ratio * 1000).toInt() / 10f}%"

    private data class RatioSnapshot(val ratio: Float, val oldLeftRight: Boolean, val from: String)

    private data class AppliedRatio(val ratio: Float, val leftRight: Boolean, val position: Int)

    private companion object {
        const val SPLIT_LAYOUT = "com.android.wm.shell.sosc.common.split.SplitLayout"
        const val STAGE_COORDINATOR = "com.android.wm.shell.sosc.SoScStageCoordinator"
        const val DIVIDER_SNAP_ALGORITHM =
            "com.android.wm.shell.sosc.common.split.DividerSnapAlgorithm"

        /** 占比有效区间（关闭分屏等极端位置不还原）。 */
        const val MIN_RATIO = 0.02f
        const val MAX_RATIO = 0.98f

        /** 收尾复查允许的像素偏差。 */
        const val DRIFT_TOLERANCE_PX = 2

        /** 换算后贴回 1:1 档位 / 1:9 档位的容差（px）。 */
        const val MIDDLE_TOLERANCE_PX = 8
        const val EDGE_TOLERANCE_PX = 4

        /** 兜底恢复「分割线锁定」的延时。 */
        const val SAFETY_RESTORE_MS = 2500L
    }
}
