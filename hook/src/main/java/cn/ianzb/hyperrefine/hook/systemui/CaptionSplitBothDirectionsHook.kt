package cn.ianzb.hyperrefine.hook.systemui

import android.graphics.Point
import android.view.View
import android.view.ViewGroup
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method

/**
 * 全屏应用「···」把手菜单同时给出左右与上下两对分屏按钮。
 *
 * 目标进程：`com.android.systemui`（小米 WMShell）。
 *
 * 官方只按当前方向创建一对按钮（左/上、右/下）。这里在官方创建后原地补一对另一方向的
 * 按钮（复用官方 `createAndAddCaptionButton`，自带独立 id），把菜单窗口宽度补足，
 * 再给这对按钮挂上本模块自己的点击监听：
 *
 * - 未进入分屏时先通过官方 `SplitLayout#updateDynamicLeftRightSplit()` 对齐实际方向，
 *   再把按钮 id 临时换成官方对应侧的 id 并调用官方 `onClick`，完整复用官方的校验、
 *   动画与「关菜单」逻辑；
 * - 已进入分屏时：点到另一方向的分屏按钮会调用官方 `switchSplitDirection()` 旋转分屏方向
 *   （不支持横屏上下分屏时保持官方语义），并把「点击应用切换前的占比」交给
 *   [SplitRatioBridge]（开启「切换方向保持比例」时按占比落位，跟随应用走而不是跟随位置）；
 *   点到同方向按钮则走官方「同侧聚焦 / 异侧换位」。
 *
 * 官方按钮（全屏 / 小窗 / 关闭等）不经过本模块，行为与未安装模块时一致。
 *
 * 依赖「解除分屏方向限制」开启（否则横向上下不可用），配置项已用 `dependsOn` 约束。
 */
class CaptionSplitBothDirectionsHook : BaseHook() {

    override val key: String = SplitScreenKeys.CAPTION_BOTH_DIRECTIONS

    /** 平板 / 折叠屏专属：手机（直板）保持官方行为。 */
    override val deviceScope: Set<DeviceType> = setOf(DeviceType.PAD, DeviceType.FOLD)

    private var loader: ClassLoader? = null

    /**
     * 菜单被屏幕边界钳制时相对「居中位置」的位移（屏幕 px）；
     * 由 [widenMenuWindow] 写入、紧随其后的 `updateDotPositionInfo` 读取，用于修正圆点动画枢轴。
     */
    @Volatile
    private var pendingDotPivotShift = 0f

    override fun init() {
        val loader = target.classLoader ?: throw HookSkippedException("no classloader")
        this.loader = loader
        val containerCls = Reflect.findClassIfExists(CAPTION_CONTAINER, loader)
            ?: throw HookSkippedException("$CAPTION_CONTAINER not found")
        var hooked = 0
        containerCls.declaredMethods
            .firstOrNull { it.name == "addSplitStateButton" && it.parameterCount == 7 }
            ?.also { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        runCatching { addOppositeSplitPair(containerCls, param) }
                            .onFailure { HookHelper.log("$tag: add opposite split pair failed", it) }
                    }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook addSplitStateButton failed", it) }
            }
        hooked += hookMenuWindow(loader)
        hooked += hookDotPositionInfo(loader)
        if (hooked == 0) throw HookSkippedException("caption menu hooks not installed")
        HookHelper.log("$tag: hooked $hooked method(s)")
    }

    /**
     * 官方创建完当前方向的一对按钮后，紧接着补上另一方向的一对。
     *
     * 官方 `init` 的顺序是「全屏 → [分屏对] → 小窗 → (新窗口) → 关闭」，这里在分屏对
     * 之后立刻创建，按钮排列天然就是「全屏 / 左(上) / 右(下) / 另一方向两个 / 小窗 / 关闭」，
     * 不需要再移动任何子视图。
     */
    private fun addOppositeSplitPair(containerCls: Class<*>, param: HookParam) {
        val loader = loader ?: return
        val container = param.thisObject as? ViewGroup ?: return
        val leftRight = param.args.getOrNull(4) as? Boolean ?: return
        val supported = param.args.getOrNull(5) as? Boolean ?: return
        if (!supported) return
        val width = param.args.getOrNull(0) as? Int ?: return
        val height = param.args.getOrNull(1) as? Int ?: return
        val padLr = param.args.getOrNull(2) as? Int ?: return
        val padTb = param.args.getOrNull(3) as? Int ?: return
        val containerPad = param.args.getOrNull(6) as? Int ?: return
        val buttonContainer = runCatching {
            Reflect.callMethod(container, "getCaptionButtonContainer") as? ViewGroup
        }.getOrNull() ?: return
        val firstOwnId = if (leftRight) ID_TB_TOP else ID_LR_LEFT
        if (buttonContainer.findViewById<View>(firstOwnId) != null) return
        val create = containerCls.declaredMethods.firstOrNull {
            it.name == "createAndAddCaptionButton" && it.parameterCount == 9
        } ?: return
        create.isAccessible = true
        val bg = SplitScreenSupport.wmRes(loader, "drawable", "caption_selector_press_selected")
        // 官方该方向的一对按钮：左/上、右/下；我们补的是另一方向。
        val specs = if (leftRight) {
            arrayOf(
                intArrayOf(ID_TB_TOP, SplitScreenSupport.wmRes(loader, "drawable", "caption_selector_splittop")),
                intArrayOf(ID_TB_BOTTOM, SplitScreenSupport.wmRes(loader, "drawable", "caption_selector_splitbottom")),
            )
        } else {
            arrayOf(
                intArrayOf(ID_LR_LEFT, SplitScreenSupport.wmRes(loader, "drawable", "caption_selector_splitleft")),
                intArrayOf(ID_LR_RIGHT, SplitScreenSupport.wmRes(loader, "drawable", "caption_selector_splitright")),
            )
        }
        val descs = if (leftRight) {
            arrayOf(RES_CONTENT_TOP, RES_CONTENT_BOTTOM)
        } else {
            arrayOf(RES_CONTENT_LEFT, RES_CONTENT_RIGHT)
        }
        var added = 0
        specs.forEachIndexed { index, spec ->
            if (spec[1] == 0) {
                HookHelper.log("$tag: caption icon missing: ${if (leftRight) "top/bottom" else "left/right"}")
                return@forEachIndexed
            }
            val desc = SplitScreenSupport.wmRes(loader, "string", descs[index])
            createButton(create, container, spec[0], bg, spec[1], desc, padLr, padTb, containerPad, width, height)
            if (buttonContainer.findViewById<View>(spec[0]) != null) added++
        }
        if (added > 0) HookHelper.log("$tag: caption split pair added (leftRight=$leftRight)")
    }

    private fun createButton(
        create: Method,
        container: ViewGroup,
        id: Int,
        bg: Int,
        icon: Int,
        desc: Int,
        padLr: Int,
        padTb: Int,
        containerPad: Int,
        width: Int,
        height: Int,
    ) {
        runCatching {
            create.invoke(
                container, id, bg, icon, desc,
                padLr, padTb, containerPad, true, ViewGroup.MarginLayoutParams(width, height),
            )
        }.onFailure { HookHelper.log("$tag: create button 0x${id.toString(16)} failed", it) }
    }

    /**
     * 菜单窗口宽度按「官方按钮数量」计算，多出来的一对按钮会落在裁剪区外看不见。
     *
     * 官方窗口宽度与按钮行等宽（`(n+1)*margin + n*width`），因此这里补上两个槽位的宽度
     * （`槽位 = 按钮宽 + 左右 margin`），并把窗口 x 与 `mHandleMenuPosition` 一起左移
     * 半个补宽；菜单缩放动画的枢轴（窗口左上角到圆点中心的偏移）同步补偿——菜单保持居中、
     * 命中区一致，收回动画也仍收向圆点。
     *
     * 窗口创建完成后再给补出来的两个按钮挂上本模块的点击监听：官方 `addMouseHoverEffect`
     * 在窗口创建前会把官方监听器挂到所有按钮上，这里只覆盖自己补的那两个。
     */
    private fun hookMenuWindow(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(MIUI_DECORATION_DOT, loader) ?: run {
            HookHelper.log("$tag: $MIUI_DECORATION_DOT not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "addWindow" && it.parameterCount == 8 }
            ?: run {
                HookHelper.log("$tag: $MIUI_DECORATION_DOT#addWindow not found")
                return 0
            }
        method.isAccessible = true
        var hooked = 0
        runCatching {
            HookHelper.hookBefore(method) { param ->
                runCatching { widenMenuWindow(param) }
                    .onFailure { HookHelper.log("$tag: widen caption menu failed", it) }
            }
            hooked++
        }.onFailure { HookHelper.log("$tag: hook caption menu width failed", it) }
        runCatching {
            HookHelper.hookAfter(method) { param ->
                runCatching { attachOwnListeners(param) }
                    .onFailure { HookHelper.log("$tag: attach caption listeners failed", it) }
            }
            hooked++
        }.onFailure { HookHelper.log("$tag: hook caption menu listeners failed", it) }
        return hooked
    }

    /** 补足窗口宽度、按屏幕边界避让、平移窗口、菜单位置点与缩放枢轴。 */
    private fun widenMenuWindow(param: HookParam) {
        val dot = param.thisObject ?: return
        pendingDotPivotShift = 0f
        val container = param.args.getOrNull(0) as? ViewGroup ?: return
        val buttons = runCatching {
            Reflect.callMethod(container, "getCaptionButtonContainer") as? ViewGroup
        }.getOrNull() ?: return
        val ownCount = SPLIT_BUTTON_IDS.count { buttons.findViewById<View>(it) != null }
        if (ownCount == 0) return
        val width = param.args.getOrNull(3) as? Int ?: return
        val x = param.args.getOrNull(1) as? Int ?: return
        val each = buttonSlotWidth(buttons) ?: return
        val extra = each * ownCount
        val newWidth = width + extra
        // 官方位置先左移半个补宽保持菜单居中；再按屏幕边界钳制，让补宽后的菜单仍完整留在
        // 屏幕内（官方的「超出屏幕边界自动避让」按原宽度计算，补宽会破坏它）。
        var newX = x - extra / 2
        val displayWidth = displayWidth() ?: 0
        if (displayWidth > 0 && newWidth < displayWidth) {
            newX = newX.coerceIn(0, displayWidth - newWidth)
        }
        // 钳制产生的位移同步到随后 `updateDotPositionInfo` 的枢轴：圆点放大时仍然滑向菜单中心。
        pendingDotPivotShift = (newX - (x - extra / 2)).toFloat()
        param.setArg(3, newWidth)
        param.setArg(1, newX)
        // 菜单缩放动画（展开 / 收回）以「窗口左上角到圆点中心」的偏移为枢轴：
        // 枢轴与触摸区域都改用最终窗口位置，收回时才收向圆点而不是偏出一个按钮位。
        val shift = x - newX
        (param.args.getOrNull(5) as? Int)?.let { param.setArg(5, it + shift) }
        runCatching {
            (Reflect.callMethod(dot, "getHandleMenuPosition") as? Point)
                ?.also { it.x = newX }
        }.onFailure { HookHelper.log("$tag: shift caption menu position failed", it) }
    }

    /** 当前显示宽度（`MultiTaskingDisplayInfo#getDisplayWidth`），拿不到时返回 null。 */
    private fun displayWidth(): Int? {
        val loader = loader ?: return null
        return runCatching {
            Reflect.callStaticMethod(Reflect.findClass(DISPLAY_INFO, loader), "getDisplayWidth") as? Int
        }.getOrNull()?.takeIf { it > 0 }
    }

    /**
     * 圆点（点开的「···」）放大动画的枢轴 = 菜单中心的镜像点；
     * 菜单被边界钳制后真实中心平移了，官方算法按原中心计算，这里把枢轴同步 -位移/scale。
     */
    private fun hookDotPositionInfo(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(MIUI_DECORATION_ANIM, loader) ?: run {
            HookHelper.log("$tag: $MIUI_DECORATION_ANIM not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "updateDotPositionInfo" && it.parameterCount == 5 }
            ?: run {
                HookHelper.log("$tag: MiuiDecorationAnim#updateDotPositionInfo not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param ->
                val shift = pendingDotPivotShift
                if (shift == 0f) return@hookBefore
                pendingDotPivotShift = 0f
                val scale = param.args.getOrNull(4) as? Float ?: 1f
                val pivotX = param.args.getOrNull(2) as? Float ?: return@hookBefore
                if (scale > 0f) param.setArg(2, pivotX - shift / scale)
            }
            1
        }.onFailure { HookHelper.log("$tag: hook updateDotPositionInfo failed", it) }.getOrDefault(0)
    }

    /** 单个按钮占用的横向宽度（按钮宽度 + 左右外边距），取已有按钮量得。 */
    private fun buttonSlotWidth(buttons: ViewGroup): Int? {
        for (i in 0 until buttons.childCount) {
            val lp = buttons.getChildAt(i).layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val slot = lp.width + lp.leftMargin + lp.rightMargin
            if (slot > 0) return slot
        }
        return null
    }

    /** 给本模块补出的按钮挂独立点击监听（官方按钮保持原监听器，不受影响）。 */
    private fun attachOwnListeners(param: HookParam) {
        val dot = param.thisObject ?: return
        val container = param.args.getOrNull(0) as? ViewGroup ?: return
        val official = SplitScreenSupport.field(dot, "mOnMiuiCaptionClickListener") ?: return
        val listener = View.OnClickListener { view ->
            runCatching { onOwnSplitClick(dot, official, view) }
                .onFailure { HookHelper.log("$tag: caption split click failed", it) }
        }
        SPLIT_BUTTON_IDS.forEach { id ->
            container.findViewById<View>(id)?.setOnClickListener(listener)
        }
    }

    /**
     * 处理补出来的分屏按钮。
     *
     * - 未进入分屏：先对齐方向，再把 id 临时替换成官方对应侧的按钮 id 并调用官方 `onClick`，
     *   完整复用官方的支持性校验、进入动画与关菜单逻辑（回调结束前恢复原 id）。
     * - 已进入分屏且点的方向与当前方向不同：调用官方 `switchSplitDirection()` 旋转分屏方向
     *   （与分割线菜单一致，需要「解除分屏方向限制」支持横屏上下；竖屏原生支持）。
     * - 已进入分屏且方向相同：不修改方向，走官方语义（同侧聚焦 / 异侧换位）。
     */
    private fun onOwnSplitClick(dot: Any, official: Any, view: View) {
        val loader = loader ?: return
        val id = view.id
        val sideZero = when (id) {
            ID_LR_LEFT, ID_TB_TOP -> true
            ID_LR_RIGHT, ID_TB_BOTTOM -> false
            else -> return
        }
        val wantLeftRight = id == ID_LR_LEFT || id == ID_LR_RIGHT
        if (SplitScreenSupport.isSoScActive(loader)) {
            val current = SplitScreenSupport.layoutLeftRightSplit(loader)
            if (current != null && current != wantLeftRight && canSwitchDirection(loader)) {
                // 「跟随应用」：先把点击应用的占比换算成「切换后新布局槽位 0（左/上）的占比」再交给
                // 比例记忆——方向切换保留各窗口的「第几侧」角色（上→左、下→右），应用会落在槽位
                // sideZero；落在槽位 1（右/下）时新分割线占比 = 1 - 应用占比。
                //（它的侧序随后可能被对齐互换，实时位置不再等于该应用的占比。）
                SplitRatioBridge.nextSwitchRatio = clickedAppShare(loader, official)
                    ?.let { share -> if (sideZero) share else 1f - share }
                // 官方的方向切换只改方向、保留各窗口「第几侧」的角色，于是会出现「点左却落到右」；
                // 先按需把两侧位置静默互换，旋转动画随后的目标就会落在用户点击的那一侧。
                alignSideForRotation(loader, official, sideZero)
                runCatching {
                    val soSc = SplitScreenSupport.soScUtils(loader) ?: return@runCatching
                    Reflect.callMethod(soSc, "switchSplitDirection")
                }.onFailure { HookHelper.log("$tag: switchSplitDirection failed", it) }
                SplitRatioBridge.nextSwitchRatio = null
                HookHelper.log("$tag: caption split click rotates split direction (wantLeftRight=$wantLeftRight)")
                closeMenu(dot)
                return
            }
            HookHelper.log("$tag: caption split click in split, keep official side semantics")
        } else {
            val aligned = SplitScreenSupport.ensureDirection(loader, wantLeftRight)
            HookHelper.log("$tag: caption split click wantLeftRight=$wantLeftRight aligned=$aligned")
        }
        val officialId = officialSplitId(loader, sideZero)
        if (officialId == 0) return
        val originalId = view.id
        view.id = officialId
        try {
            (official as? View.OnClickListener)?.onClick(view)
        } finally {
            view.id = originalId
        }
    }

    /**
     * 点击的分屏按钮对应的应用当前的占比（槽位 0 = 左 / 上）。
     *
     * 取不到任务 id 或布局时返回 null——比例记忆会退回「切换瞬间的实时占比」。
     */
    private fun clickedAppShare(loader: ClassLoader, official: Any): Float? {
        val taskId = SplitScreenSupport.field(official, "mTaskId") as? Int ?: return null
        val soSc = SplitScreenSupport.soScUtils(loader) ?: return null
        val leftOrTop = runCatching {
            Reflect.callMethod(soSc, "isTaskInSoScLeftOrTopScreen", taskId) as? Boolean
        }.getOrNull() ?: return null
        val slotZeroRatio = SplitScreenSupport.splitRatio(loader) ?: return null
        return if (leftOrTop) slotZeroRatio else 1f - slotZeroRatio
    }

    /**
     * 让「当前把手菜单对应的应用」在随后的方向切换里落到点击的那一侧。
     *
     * `switchSplitDirection` 的过渡保持各窗口原有的「第几侧」角色（上→左、下→右），因此当
     * 应用当前所在侧与点击侧不同（侧序不一致）时，先调用官方 `setSideStagePosition(pos, false, null)`
     * 静默互换两侧位置（不触发额外的位移动画），再执行旋转，一次动画即可落到目标侧。
     */
    private fun alignSideForRotation(loader: ClassLoader, official: Any, sideZero: Boolean) {
        val taskId = SplitScreenSupport.field(official, "mTaskId") as? Int ?: return
        val soSc = SplitScreenSupport.soScUtils(loader) ?: return
        val currentSideZero = runCatching {
            Reflect.callMethod(soSc, "isTaskInSoScLeftOrTopScreen", taskId) as? Boolean
        }.getOrNull() ?: return
        if (currentSideZero == sideZero) return
        val coordinator = SplitScreenSupport.stageCoordinator(loader) ?: return
        val position = SplitScreenSupport.field(coordinator, "mSideStagePosition") as? Int ?: return
        runCatching {
            Reflect.callMethod(coordinator, "setSideStagePosition", 1 - position, false, null)
        }.onFailure { HookHelper.log("$tag: align split side before rotate failed", it) }
        HookHelper.log("$tag: caption split click aligns side before rotate (wantSideZero=$sideZero)")
    }

    /** 横屏是否允许切成上下分屏（官方条件：`supportTopBottomSplitFeature()` 为真，或本就不在横屏）。 */
    private fun canSwitchDirection(loader: ClassLoader): Boolean = runCatching {
        val soSc = SplitScreenSupport.soScUtils(loader) ?: return@runCatching false
        val feature = Reflect.callMethod(soSc, "supportTopBottomSplitFeature") as? Boolean == true
        if (feature) return@runCatching true
        Reflect.callMethod(soSc, "isLandscape") as? Boolean == false
    }.getOrDefault(false)

    /** 关闭当前把手菜单。 */
    private fun closeMenu(dot: Any) {
        runCatching { Reflect.callMethod(dot, "closeHandleMenu", true, false) }
            .onFailure { HookHelper.log("$tag: close caption menu failed", it) }
    }

    /** 官方「左/上」「右/下」分屏按钮 id（`com.android.wm.shell.R.id.state_split*`）。 */
    private fun officialSplitId(loader: ClassLoader, sideZero: Boolean): Int {
        val name = if (sideZero) "state_splitleftortop" else "state_splitrightorbottom"
        val fallback = if (sideZero) R_ID_SPLIT_LEFT_OR_TOP else R_ID_SPLIT_RIGHT_OR_BOTTOM
        return SplitScreenSupport.wmRes(loader, "id", name).takeIf { it != 0 } ?: fallback
    }

    private companion object {
        const val CAPTION_CONTAINER =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.handlemenu.MiuiCaptionContainerView"
        const val MIUI_DECORATION_DOT =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationDot"
        const val MIUI_DECORATION_ANIM =
            "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationAnim"
        const val DISPLAY_INFO = "com.android.wm.shell.multitasking.common.MultiTaskingDisplayInfo"

        /** 官方把手菜单里分屏按钮的兜底 id（正常从 WMShell R 里取）。 */
        const val R_ID_SPLIT_LEFT_OR_TOP = 1879704280
        const val R_ID_SPLIT_RIGHT_OR_BOTTOM = 1879704281

        /** 本模块补出的分屏按钮 id（避开资源 id 段，只在本进程内使用）。 */
        const val ID_LR_LEFT = 0x0F0F1001
        const val ID_LR_RIGHT = 0x0F0F1002
        const val ID_TB_TOP = 0x0F0F1003
        const val ID_TB_BOTTOM = 0x0F0F1004

        val SPLIT_BUTTON_IDS = intArrayOf(ID_LR_LEFT, ID_LR_RIGHT, ID_TB_TOP, ID_TB_BOTTOM)

        const val RES_CONTENT_LEFT = "drag_shadow_split_left_open"
        const val RES_CONTENT_RIGHT = "drag_shadow_split_right_open"
        const val RES_CONTENT_TOP = "drag_shadow_split_top_open"
        const val RES_CONTENT_BOTTOM = "drag_shadow_split_bottom_open"
    }
}
