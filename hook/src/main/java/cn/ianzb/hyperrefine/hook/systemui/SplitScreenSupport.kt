package cn.ianzb.hyperrefine.hook.systemui

import android.graphics.Rect
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 「跟随应用」占比桥：把手菜单点击另一方向的分屏按钮时，先把「点击应用切换前的占比」
 * 写到这里，比例记忆 Hook（[SplitRatioMemoryHook]）在下一次方向切换时优先采用。
 *
 * 只在本模块内部使用；未消费时由写入方（[CaptionSplitBothDirectionsHook]）在调用返回后清空。
 */
internal object SplitRatioBridge {
    var nextSwitchRatio: Float? = null

    fun consume(): Float? {
        val value = nextSwitchRatio
        nextSwitchRatio = null
        return value
    }
}

/**
 * 分屏 / 多分屏（小米 WMShell，运行于 `com.android.systemui` 进程）共用的反射工具。
 */
internal object SplitScreenSupport {

    const val SPLIT_UTILS_IMPL = "com.android.wm.shell.common.split.SplitUtilsImpl"
    const val SPLIT_UTILS_STUB = "com.android.wm.shell.common.split.SplitUtilsStub"
    const val SPLIT_SCREEN_UTILS = "com.android.wm.shell.common.split.SplitScreenUtils"
    const val SOSC_UTILS_IMPL = "com.android.wm.shell.sosc.SoScUtilsImpl"
    const val MULWIN_SPLIT_UTILS =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.MulWinSwitchSplitUtils"

    /** 资源解析候选包名（WMShell 资源挂在系统界面 / 插件下）。 */
    val RES_PACKAGES = listOf("com.android.systemui", "miui.systemui.plugin")

    fun field(target: Any, name: String): Any? =
        runCatching { Reflect.getObjectField(target, name) }.getOrNull()

    /** `SplitUtilsStub`（即 `SplitUtilsImpl`）单例。 */
    fun stub(loader: ClassLoader): Any? = runCatching {
        Reflect.callStaticMethod(Reflect.findClass(SPLIT_UTILS_STUB, loader), "getInstance")
    }.getOrNull()

    /** `SoScUtils` 单例。 */
    fun soScUtils(loader: ClassLoader): Any? = runCatching {
        Reflect.callStaticMethod(Reflect.findClass(SOSC_UTILS_IMPL, loader), "getInstance")
    }.getOrNull()

    /** `SoScStageCoordinator`（`SoScUtilsImpl.mStageCoordinator`）。 */
    fun stageCoordinator(loader: ClassLoader): Any? = runCatching {
        val soSc = soScUtils(loader) ?: return@runCatching null
        field(soSc, "mStageCoordinator")
    }.getOrNull()

    /** `SplitLayout`（进入分屏时真正用于几何与吸附的布局对象）。 */
    fun splitLayout(loader: ClassLoader): Any? = runCatching {
        val coordinator = stageCoordinator(loader) ?: return@runCatching null
        Reflect.callMethod(coordinator, "getSplitLayout")
    }.getOrNull()

    /** 当前是否已处于分屏（SoSc）状态。 */
    fun isSoScActive(loader: ClassLoader): Boolean = runCatching {
        val soSc = soScUtils(loader) ?: return@runCatching false
        (Reflect.callMethod(soSc, "isSoScActive") as? Boolean) == true
    }.getOrDefault(false)

    /**
     * 平板上的「是否左右分屏」：
     * 动态方向标志 `mDynamicTopBottomSplit=true` 表示使用当前方向下的默认方向
     * （竖屏上下、横屏左右），`false` 表示被菜单切到了另一方向。因此结果为 `横屏 == 标志`。
     *
     * 非平板或尚未初始化（`mReady != true`）时返回 null，交回系统原实现，避免影响手机 / 折叠屏。
     */
    fun effectiveLeftRightSplit(loader: ClassLoader): Boolean? = runCatching {
        val soSc = soScUtils(loader) ?: return@runCatching null
        if (Reflect.callMethod(soSc, "isPad") as? Boolean != true) return@runCatching null
        val landscape = Reflect.callMethod(soSc, "isLandscape") as? Boolean ?: return@runCatching null
        val stub = stub(loader) ?: return@runCatching null
        if (Reflect.getObjectField(stub, "mReady") as? Boolean != true) return@runCatching null
        val dynamic = Reflect.getObjectField(stub, "mDynamicTopBottomSplit") as? Boolean
            ?: return@runCatching null
        landscape == dynamic
    }.getOrNull()

    /**
     * 让「实际进入分屏时使用的方向」（`SplitLayout#mIsLeftRightSplit`）与期望一致。
     *
     * 进入分屏的几何、吸附都读布局对象里缓存的 `mIsLeftRightSplit`，而不是实时的
     * `isLeftRightSplit()`。本模块的兼容层只改写了后者，因此必须调用官方
     * `SplitLayout#updateDynamicLeftRightSplit()`（翻转动态方向标志并重算布局方向、
     * 吸附算法）才能让「点哪个按钮就进哪个方向」。
     */
    fun ensureDirection(loader: ClassLoader, wantLeftRight: Boolean): Boolean {
        val layout = splitLayout(loader)
        if (layout != null && layoutDirection(layout) != null) {
            repeat(2) {
                if (layoutDirection(layout) == wantLeftRight) return true
                runCatching { Reflect.callMethod(layout, "updateDynamicLeftRightSplit") }
                    .onFailure { HookHelper.log("SplitScreenSupport: align split direction failed", it) }
            }
            val aligned = layoutDirection(layout) == wantLeftRight
            if (!aligned) {
                HookHelper.log("SplitScreenSupport: cannot align split direction (wantLeftRight=$wantLeftRight)")
            }
            return aligned
        }
        // 退化路径（拿不到布局对象时）：只翻转动态方向标志，与旧版行为一致。
        val current = effectiveLeftRightSplit(loader) ?: return false
        if (current == wantLeftRight) return true
        val stub = stub(loader) ?: return false
        return runCatching {
            Reflect.callMethod(stub, "updateDynamicLeftRightSplit")
            true
        }.onFailure { HookHelper.log("SplitScreenSupport: toggle split direction failed", it) }
            .getOrDefault(false)
    }

    /** `SplitLayout#mIsLeftRightSplit`：布局对象当前缓存的分屏方向。 */
    private fun layoutDirection(layout: Any): Boolean? =
        runCatching { Reflect.getObjectField(layout, "mIsLeftRightSplit") as? Boolean }.getOrNull()

    /** 当前分屏方向（`SplitLayout` 缓存值）；拿不到时返回 null。 */
    fun layoutLeftRightSplit(loader: ClassLoader): Boolean? =
        splitLayout(loader)?.let { layoutDirection(it) }

    /** 分割线在「槽位 0（左 / 上）」所占的比例；未分屏或尺寸无效时返回 null。 */
    fun splitRatio(loader: ClassLoader): Float? = runCatching {
        val layout = splitLayout(loader) ?: return@runCatching null
        val leftRight = layoutDirection(layout) ?: return@runCatching null
        val position = Reflect.getObjectField(layout, "mDividerPosition") as? Int
            ?: return@runCatching null
        val root = Reflect.callMethod(layout, "getRootBounds") as? Rect ?: return@runCatching null
        val dimension = if (leftRight) root.width() else root.height()
        if (position <= 0 || dimension <= 0) return@runCatching null
        position.toFloat() / dimension
    }.getOrNull()

    /**
     * 取 WMShell 自己的资源 id（`com.android.wm.shell.R`）。
     *
     * WMShell 是共享库资源（运行时包段 0x70，例如 `caption_selector_fullscreen = 0x700800c2`），
     * 按 `getIdentifier(name, type, pkg)` 找不到，只能读它自己的 R 类常量。
     */
    fun wmRes(loader: ClassLoader, type: String, name: String): Int = runCatching {
        Class.forName("com.android.wm.shell.R\$$type", false, loader).getField(name).getInt(null)
    }.getOrDefault(0)

    /** dp → px（用系统密度，无需 Context）。 */
    fun dpToPx(dp: Float): Int = runCatching {
        (dp * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    }.getOrDefault(dp.toInt())

    /**
     * `DividerSnapAlgorithm#mTargets` 里距 [position] 最近的「真实档位」。
     *
     * 仅当距离不超过 [maxDistance] 时返回该目标对象（`SnapTarget`，可直接当吸附结果使用），
     * 用于无级调节时的「磁吸」：非常接近系统档位就吸附过去。
     * 跳过关闭分屏（11 / 12）与自由目标（10）。
     */
    fun nearestSnapTarget(algorithm: Any, position: Int, maxDistance: Int): Any? = runCatching {
        val targets = Reflect.getObjectField(algorithm, "mTargets") as? List<*> ?: return@runCatching null
        var best: Any? = null
        var bestDistance = Int.MAX_VALUE
        for (target in targets) {
            if (target == null) continue
            val snapPosition = intField(target, "snapPosition") ?: continue
            if (snapPosition == FREE_SNAP_POSITION || snapPosition == DISMISS_START || snapPosition == DISMISS_END) {
                continue
            }
            val targetPosition = intField(target, "position") ?: continue
            val distance = kotlin.math.abs(targetPosition - position)
            if (distance < bestDistance) {
                bestDistance = distance
                best = target
            }
        }
        if (best != null && bestDistance <= maxDistance) best else null
    }.getOrNull()

    private fun intField(target: Any, name: String): Int? =
        runCatching { Reflect.findField(target.javaClass, name).get(target) as? Int }.getOrNull()

    private const val FREE_SNAP_POSITION = 10
    private const val DISMISS_START = 11
    private const val DISMISS_END = 12
}
