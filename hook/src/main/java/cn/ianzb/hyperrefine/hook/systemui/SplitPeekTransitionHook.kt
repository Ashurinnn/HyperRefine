package cn.ianzb.hyperrefine.hook.systemui

import android.graphics.Rect
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 1:9 小窗拖拽过渡 + 「小窗有部分在屏幕外时点击先回屏幕内」。
 *
 * 目标进程：`com.android.systemui`（小米 WMShell）。
 *
 * 1) 拖拽过渡曲线：
 *    官方在 `SplitUtilsImpl#updateBounds(i, …)` 里只在 `i` 跨过 1:9 档位
 *    （`mFirstSplitTarget` / `mLastSplitTarget`）的那一刻一次性加上 `2×(i − 中位)` 的
 *    「移出屏幕」偏移，于是从相邻档位拖向 1:9 时窗口会瞬间跳成「大部分在屏幕外」。
 *    这里在「相邻档位 → 1:9 档位」之间连续过渡：**前 90% 行程保持整窗在屏幕内**
 *    （偏移恒为 0），只有非常接近 1:9 档位的最后 10% 才用 smoothstep（`3s²−2s³`，
 *    两端导数为 0）平滑地把窗口移出屏幕，落位与官方完全一致、没有突变。
 *
 * 2) 点击小窗回到屏幕内：
 *    官方只在窗口有部分在屏幕外且正好停在 1:9 档位（`mSplitState` 为 3 / 4）时，才在小窗上
 *    挂一层「离屏触摸区」（`OffscreenTouchZone` + `inflateOnStageRoot` 机制）——点击它会把
 *    小窗滑入屏幕并让它成为主窗口（另一侧变成小窗），触摸也不会落到应用。
 *    自定义过渡区间里小窗同样有部分在屏幕外，但分割线停在自由档位（状态 10），官方不会挂
 *    触摸区，点击会直接落到应用。这里检测到「任一窗口有部分在屏幕外」且官方未覆盖时补一层
 *    同样的触摸区：第一次点击先让该窗口滑入屏幕（复用官方 `snapToTarget` 到对侧 1:9 档位，
 *    动画、状态与窗口边界收尾都走官方链路），之后触摸才直接交给应用。
 */
class SplitPeekTransitionHook : BaseHook() {

    override val key: String = SplitScreenKeys.SPLIT_PEEK_TRANSITION

    private var loader: ClassLoader? = null

    /** 我们自己补的「离屏触摸区」（官方 1:9 档位自带的触摸区不归这里管）。 */
    @Volatile
    private var customZone: Any? = null

    override fun init() {
        val loader = target.classLoader ?: throw HookSkippedException("no classloader")
        this.loader = loader
        val boundsHooked = hookUpdateBounds(loader)
        val zoneHooked = hookTouchZoneRefresh(loader)
        if (boundsHooked == 0 && zoneHooked == 0) {
            throw HookSkippedException("split peek hooks not installed")
        }
        HookHelper.log("$tag: hooked updateBounds=$boundsHooked, touchZone=$zoneHooked")
    }

    // ---------------- 1) 拖拽过渡曲线 ----------------

    private fun hookUpdateBounds(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(SPLIT_UTILS_IMPL, loader) ?: run {
            HookHelper.log("$tag: $SPLIT_UTILS_IMPL not found")
            return 0
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == "updateBounds" && it.parameterCount == 5
        } ?: run {
            HookHelper.log("$tag: SplitUtilsImpl#updateBounds(5) not found")
            return 0
        }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param ->
                runCatching { applyPeekTransition(param) }
                    .onFailure { HookHelper.log("$tag: peek transition failed", it) }
            }
            1
        }.onFailure { HookHelper.log("$tag: hook updateBounds failed", it) }.getOrDefault(0)
    }

    private fun applyPeekTransition(param: HookParam) {
        val position = param.args.getOrNull(0) as? Int ?: return
        val first = param.args.getOrNull(1) as? Rect ?: return
        val second = param.args.getOrNull(2) as? Rect ?: return
        val leftRight = param.args.getOrNull(3) as? Boolean ?: return
        val algorithm = param.args.getOrNull(4) ?: return
        // 只接管官方 1:9 比例模式（平板：横屏 7 / 竖屏上下 5）；其它模式官方行为不变。
        val snapMode = targetInt(algorithm, "mSnapMode") ?: return
        if (snapMode != SNAP_MODE_PAD_TB && snapMode != SNAP_MODE_PAD_LR) return
        val middle = targetPosition(algorithm, "mMiddleTarget") ?: return
        val firstTarget = targetPosition(algorithm, "mFirstSplitTarget") ?: return
        val lastTarget = targetPosition(algorithm, "mLastSplitTarget") ?: return
        val targets = sortedTargetPositions(algorithm) ?: return
        if (targets.size < 3) return
        val neighborFirst = targets[1]
        val neighborLast = targets[targets.size - 2]
        // 与官方相同的偏移量：2×(当前位置 − 中间位置)，负值表示往起点侧移出屏幕。
        val delta = position - middle
        if (position > firstTarget && position < neighborFirst) {
            val fraction = (neighborFirst - position).toFloat() / (neighborFirst - firstTarget)
            val shift = (delta * 2 * peekFraction(fraction)).toInt()
            if (leftRight) first.left += shift else first.top += shift
            param.setResultValue(true)
            return
        }
        if (position > neighborLast && position < lastTarget) {
            val fraction = (position - neighborLast).toFloat() / (lastTarget - neighborLast)
            val shift = (delta * 2 * peekFraction(fraction)).toInt()
            if (leftRight) second.right += shift else second.bottom += shift
            param.setResultValue(true)
        }
    }

    /**
     * 「移出屏幕」比例曲线：`fraction` 为 0 时是相邻档位、为 1 时是 1:9 档位。
     *
     * 前 [PEEK_START_FRACTION]（90%）恒为 0——小窗整窗留在屏幕内；最后 10% 用
     * smoothstep（`3s²−2s³`，两端导数为 0）平滑加速到 1，两个端点与官方完全一致、没有突变。
     */
    private fun peekFraction(fraction: Float): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped <= PEEK_START_FRACTION) return 0f
        val s = (clamped - PEEK_START_FRACTION) / (1f - PEEK_START_FRACTION)
        return s * s * (3f - 2f * s)
    }

    /** 全部「非关闭分屏」档位的位置，升序。 */
    private fun sortedTargetPositions(algorithm: Any): List<Int>? {
        val targets = field(algorithm, "mTargets") as? List<*> ?: return null
        val positions = mutableListOf<Int>()
        for (target in targets) {
            if (target == null) continue
            val snapPosition = targetInt(target, "snapPosition") ?: continue
            if (snapPosition == DISMISS_START || snapPosition == DISMISS_END) continue
            val position = targetInt(target, "position") ?: continue
            positions += position
        }
        positions.sort()
        return positions
    }

    // ---------------- 2) 离屏小窗的触摸区 ----------------

    /**
     * 触摸区维护的触发点：
     * - `SplitLayout#populateTouchZones()`：官方每次重建触摸区（拖拽落位后、转场结束等）后评估；
     * - `SplitLayout#setDividerPosition(int, boolean)`：拖拽落位与位移动画结束时兜底评估。
     */
    private fun hookTouchZoneRefresh(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(SPLIT_LAYOUT, loader) ?: run {
            HookHelper.log("$tag: $SPLIT_LAYOUT not found")
            return 0
        }
        var hooked = 0
        cls.declaredMethods
            .firstOrNull { it.name == "populateTouchZones" && it.parameterCount == 0 }
            ?.also { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param -> evaluateTouchZone(param.thisObject) }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook populateTouchZones failed", it) }
            } ?: HookHelper.log("$tag: SplitLayout#populateTouchZones not found")
        cls.declaredMethods
            .firstOrNull { it.name == "setDividerPosition" && it.parameterCount == 2 }
            ?.also { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param -> evaluateTouchZone(param.thisObject) }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook setDividerPosition failed", it) }
            } ?: HookHelper.log("$tag: SplitLayout#setDividerPosition not found")
        return hooked
    }

    private fun evaluateTouchZone(layoutAny: Any?) {
        val layout = layoutAny ?: return
        runCatching { evaluateTouchZoneInternal(layout) }
            .onFailure { HookHelper.log("$tag: evaluate touch zone failed", it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun evaluateTouchZoneInternal(layout: Any) {
        val cl = loader ?: return
        val zones = Reflect.getObjectField(layout, "mOffscreenTouchZones") as? MutableList<Any?>
            ?: return
        val current = customZone
        if (current != null && !zones.contains(current)) {
            // 官方统一清理（removeTouchZones / release）时也会带走我们的触摸区。
            customZone = null
        }
        val peekSide = peekedStageIsTopLeft(layout)
        if (peekSide == null) {
            releaseCustomZone(zones)
            return
        }
        val state = currentSnapPosition(layout)
        // 官方 1:9 档位（状态 3 / 4）自带触摸区；最小化偏移等其它分支也可能已有官方覆盖。
        val officialState = state == STATE_SMALL_TOP_LEFT || state == STATE_SMALL_BOTTOM_RIGHT
        val officialCovers = zones.any { zone ->
            zone != null && zone !== customZone && boolField(zone, "mIsTopLeft") == peekSide
        }
        val existing = customZone
        if (existing != null &&
            (officialCovers || officialState || boolField(existing, "mIsTopLeft") != peekSide)
        ) {
            releaseCustomZone(zones)
        }
        if (customZone == null && !officialCovers && !officialState) {
            if (!SplitScreenSupport.isSoScActive(cl) || isMinimized(cl) || isDocked(cl)) return
            customZone = createCustomZone(layout, zones, peekSide)
        }
    }

    /** 哪一侧的窗口有部分在屏幕外（`true` = 左/上，`false` = 右/下，`null` = 都完整在屏幕内）。 */
    private fun peekedStageIsTopLeft(layout: Any): Boolean? = runCatching {
        val root = Reflect.callMethod(layout, "getRootBounds") as? Rect ?: return null
        val topLeft = Reflect.callMethod(layout, "getTopLeftBounds") as? Rect ?: return null
        val bottomRight = Reflect.callMethod(layout, "getBottomRightBounds") as? Rect ?: return null
        val topLeftOff = topLeft.left < root.left || topLeft.top < root.top
        val bottomRightOff = bottomRight.right > root.right || bottomRight.bottom > root.bottom
        when {
            topLeftOff -> true
            bottomRightOff -> false
            else -> null
        }
    }.getOrNull()

    /** 按官方 `inflateOnStageRoot` 的方式挂一层覆盖小窗的触摸区。 */
    private fun createCustomZone(layout: Any, zones: MutableList<Any?>, topLeft: Boolean): Any? {
        val cl = loader ?: return null
        val zoneCls = Reflect.findClassIfExists(OFFSCREEN_TOUCH_ZONE, cl) ?: run {
            HookHelper.log("$tag: $OFFSCREEN_TOUCH_ZONE not found")
            return null
        }
        val listener = object : Runnable {
            override fun run() {
                runCatching { onPeekedStageClicked(layout) }
                    .onFailure { HookHelper.log("$tag: peeked stage click failed", it) }
            }
        }
        val zone = runCatching { Reflect.newInstance(zoneCls, topLeft, listener) }
            .onFailure { HookHelper.log("$tag: create touch zone failed", it) }
            .getOrNull() ?: return null
        zones.add(zone)
        return runCatching {
            val callbacks = Reflect.getObjectField(layout, "mParentContainerCallbacks")
                ?: throw IllegalStateException("mParentContainerCallbacks is null")
            Reflect.callMethod(callbacks, "inflateOnStageRoot", zone)
            HookHelper.log("$tag: custom offscreen touch zone added (topLeft=$topLeft)")
            zone
        }.onFailure {
            zones.remove(zone)
            HookHelper.log("$tag: inflate touch zone failed", it)
        }.getOrNull()
    }

    /** 释放并移除我们自己补的触摸区（与官方 `removeTouchZones` 的单条清理逻辑一致）。 */
    private fun releaseCustomZone(zones: MutableList<Any?>) {
        val zone = customZone ?: return
        customZone = null
        runCatching {
            zones.remove(zone)
            Reflect.getObjectField(zone, "mViewHost")?.let { viewHost ->
                Reflect.callMethod(viewHost, "release")
                Reflect.setObjectField(zone, "mViewHost", null)
            }
            Reflect.getObjectField(zone, "mLeash")?.let { leash ->
                val txCls = Reflect.findClassIfExists(SURFACE_TRANSACTION, loader ?: return)
                if (txCls != null) {
                    val transaction = Reflect.newInstance(txCls)
                    Reflect.callMethod(transaction, "remove", leash)
                    Reflect.callMethod(transaction, "apply")
                }
                Reflect.setObjectField(zone, "mLeash", null)
            }
            HookHelper.log("$tag: custom offscreen touch zone released")
        }.onFailure { HookHelper.log("$tag: release touch zone failed", it) }
    }

    /**
     * 小窗（有部分在屏幕外）被点击：与官方 1:9 触摸区同样的语义——把小窗滑入屏幕并让它成为
     * 主窗口（另一侧变成小窗）。等价于「把分割线拖到对侧 1:9 档位并松手」：直接复用官方
     * `snapToTarget(…, 对侧 1:9 档位, …)`，动画、分屏状态、窗口边界与触摸区重建都走官方链路。
     */
    private fun onPeekedStageClicked(layout: Any) {
        val cl = loader ?: return
        if (boolField(layout, "mIsFling") == true) {
            HookHelper.log("$tag: peeked stage click ignored (fling animating)")
            return
        }
        val soSc = SplitScreenSupport.soScUtils(cl) ?: return
        if ((Reflect.callMethod(soSc, "isDragging") as? Boolean) == true ||
            (Reflect.callMethod(soSc, "isSwitchDirectionAnimating") as? Boolean) == true
        ) {
            HookHelper.log("$tag: peeked stage click ignored (dragging/switch animating)")
            return
        }
        val smallTopLeft = peekedStageIsTopLeft(layout) ?: return
        val algorithm = runCatching { Reflect.callMethod(layout, "getDividerSnapAlgorithm") }
            .getOrNull() ?: return
        val target = runCatching {
            Reflect.getObjectField(algorithm, if (smallTopLeft) "mLastSplitTarget" else "mFirstSplitTarget")
        }.getOrNull() ?: return
        val position = intField(layout, "mDividerPosition") ?: return
        HookHelper.log("$tag: tap on peeked stage -> slide in (smallTopLeft=$smallTopLeft, pos=$position)")
        runCatching {
            Reflect.callMethod(layout, "snapToTarget", position, target, SLIDE_IN_DURATION_MS, flingInterpolator(cl))
        }.onFailure { HookHelper.log("$tag: slide in failed", it) }
    }

    // ---------------- 工具 ----------------

    /** shell 的 `Interpolators.FAST_OUT_SLOW_IN`（拿不到时传 null，官方动画用默认插值器）。 */
    private fun flingInterpolator(cl: ClassLoader): Any? = runCatching {
        val cls = Reflect.findClassIfExists(SHELL_INTERPOLATORS, cl) ?: return null
        Reflect.getStaticObjectField(cls, "FAST_OUT_SLOW_IN")
    }.getOrNull()

    private fun currentSnapPosition(layout: Any): Int? =
        runCatching { Reflect.callMethod(layout, "calculateCurrentSnapPosition") as? Int }.getOrNull()

    private fun isMinimized(cl: ClassLoader): Boolean = runCatching {
        val soSc = SplitScreenSupport.soScUtils(cl) ?: return@runCatching false
        (Reflect.callMethod(soSc, "inSoScMinimizedMode") as? Boolean) == true
    }.getOrDefault(false)

    private fun isDocked(cl: ClassLoader): Boolean = runCatching {
        val soSc = SplitScreenSupport.soScUtils(cl) ?: return@runCatching false
        (Reflect.callMethod(soSc, "isDocked") as? Boolean) == true
    }.getOrDefault(false)

    private fun targetPosition(algorithm: Any, field: String): Int? =
        (field(algorithm, field) as? Any)?.let { targetInt(it, "position") }

    private fun targetInt(target: Any, name: String): Int? =
        runCatching { Reflect.findField(target.javaClass, name).get(target) as? Int }.getOrNull()

    private fun intField(target: Any, name: String): Int? = targetInt(target, name)

    private fun boolField(target: Any, name: String): Boolean? =
        runCatching { Reflect.findField(target.javaClass, name).get(target) as? Boolean }.getOrNull()

    private fun field(target: Any, name: String): Any? =
        runCatching { Reflect.findField(target.javaClass, name).get(target) }.getOrNull()

    private companion object {
        const val SPLIT_UTILS_IMPL = "com.android.wm.shell.common.split.SplitUtilsImpl"
        const val SPLIT_LAYOUT = "com.android.wm.shell.sosc.common.split.SplitLayout"
        const val OFFSCREEN_TOUCH_ZONE = "com.android.wm.shell.sosc.common.split.OffscreenTouchZone"
        const val SURFACE_TRANSACTION = "android.view.SurfaceControl\$Transaction"
        const val SHELL_INTERPOLATORS = "com.android.wm.shell.shared.animation.Interpolators"

        /** 平板 1:9 比例模式的 snapMode（上下 / 左右）。 */
        const val SNAP_MODE_PAD_TB = 5
        const val SNAP_MODE_PAD_LR = 7

        /** 「关闭分屏」目标（`SplitScreenConstants.isDismissSnapPosition`）。 */
        const val DISMISS_START = 11
        const val DISMISS_END = 12

        /** 官方 1:9 档位对应的 snap 状态：4 = 左/上小窗（10/90），3 = 右/下小窗（90/10）。 */
        const val STATE_SMALL_TOP_LEFT = 4
        const val STATE_SMALL_BOTTOM_RIGHT = 3

        /** 「移出屏幕」只在距 1:9 档位最后 10% 的行程内开始（前 90% 整窗留在屏幕内）。 */
        const val PEEK_START_FRACTION = 0.9f

        /** 点击小窗后「滑入 + 成为主窗」的动画时长（与官方 1:9 切换一致）。 */
        const val SLIDE_IN_DURATION_MS = 550
    }
}
