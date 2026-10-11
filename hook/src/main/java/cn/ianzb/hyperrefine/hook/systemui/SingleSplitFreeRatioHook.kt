package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 分屏比例限制解锁 / 无级调节（两个开关共用一次挂载）。
 *
 * 目标进程：`com.android.systemui`（小米 WMShell）。
 *
 * 1. 「解除单应用分屏比例限制」：单应用分屏（另一半是桌面）里不再被强制回 1:1 ——
 *    - 绕过 `SoScUtilsImpl#findSnapTarget(5 参数)` 的单应用钳制，改用
 *      `DividerSnapAlgorithm` 算出的标准档位（1:1 / 1:3 / 1:9 …），分割线照常吸附；
 *    - 拦掉关闭另一个应用 / 点把手时 `SoScStageCoordinator#setToDefaultSplitRatioIfNeed`
 *      触发的「把比例重置回 1:1」（`SplitLayout#setDivideRatioForSoSc(1, …)`）。
 * 2. 「分屏比例无级调节」（全局）：`DividerSnapAlgorithm#snap` 返回官方自由目标
 *    （snapPosition 10，官方 `mFreeSnapMode` 分支同款），任意位置松手都停在原地；
 *    两个应用分屏与单应用分屏都生效。
 * 3. 归一化「分割线锁定」（`SoScStageCoordinator#mIsDividerFixed`）：平板上桌面会常驻把该标志
 *    推为 true，官方多处据此把比例钳回 1:1 —— 除松手吸附目标（上面第 1 点）外，
 *    `onMultiWindowStateChanged` 在松手的状态广播里会先把「布局边界」按 1:1 重算
 *    （`updateBounds(mMiddleTarget)`），导致自由比例刚松手就被拉回、随后动画与真实位置对不上
 *    （观感「放大动画播到一半跳到终点」），单应用分屏更是每次松手都回 1:1；
 *    `onPreSoScStateChanged` 的拖拽预览同样按 1:1 计算边界。这里在开启任一比例功能时把桌面
 *    推送的该标志一律改写成 false（并把已存在的 true 惰性清掉），官方即按当前分割线位置计算边界。
 * 4. 自由比例松手的收尾路径归一化：官方档位松手时 `snapToTarget` 会起一次 250ms 的 fling，
 *    收尾（`setDividerPosition` → WCT → resize 过渡）在动画帧的 `onAnimationEnd` 里执行；
 *    期间每一帧都会走 `mDividerPositionForSoSc` / `updateBounds` / `onLayoutSizeChanging`
 *    （表面、resize 效果层、模糊逐帧更新），收尾时 `mIsFling` / `mDividerFlingAnimator`
 *    处于动画语义、各效果层状态与画面一致。自由比例松手时 fling 距离为 0，
 *    `SplitLayout#flingDividerPosition` 走「立即返回」分支：收尾回调在 `ACTION_UP` 的输入分发里
 *    同步执行，既没有逐帧更新、`mIsFling` / `mDividerFlingAnimator` 也从未进入动画语义，
 *    收尾时的效果层状态与画面切换对不上，观感「放大到一半直接跳到终态」。
 *    这里把自由松手改写为一次「几乎不动」的官方 fling（起点挪到目标旁 1px，时长保持官方
 *    传入的 250ms）：逐帧更新、收尾时机与标志维护全部交给官方实现，视觉上不产生位移，
 *    同时补齐拖拽预览回弹（窗口整体缩放 0.969 → 1.0 的「放大」动画）所需的动画帧。
 */
class SingleSplitFreeRatioHook : BaseHook() {

    override val key: String = SplitScreenKeys.SINGLE_SPLIT_FREE_RATIO

    /**
     * 两个开关共用一次挂载，「已生效」状态按本次挂载时各自开关的取值分别上报：
     * 只开「分屏比例无级调节」时也要让对应卡片显示已生效（此前只上报单应用键）。
     */
    override val keys: List<String>
        get() = buildList {
            if (HookPrefs.getBoolean(SplitScreenKeys.SINGLE_SPLIT_FREE_RATIO, false)) {
                add(SplitScreenKeys.SINGLE_SPLIT_FREE_RATIO)
            }
            if (HookPrefs.getBoolean(SplitScreenKeys.SPLIT_FREE_RATIO_GLOBAL, false)) {
                add(SplitScreenKeys.SPLIT_FREE_RATIO_GLOBAL)
            }
        }.ifEmpty { listOf(key) }

    private var loader: ClassLoader? = null
    private var stepless = false
    private var loggedFirstBypass = false
    private var loggedMagnet = false
    private var magnetRadius: Int? = null

    /** 「分割线锁定」被归一化的日志只打一次。 */
    private var loggedFixedNormalize = false

    /** 「自由松手改走官方 fling 路径」的日志只打一次。 */
    private var loggedDeferredRelease = false

    /** 「缩放回弹防重入」的日志只打一次。 */
    private var loggedScaleBackSkip = false

    /** 「自由状态查表落空回退」的日志只打一次。 */
    private var loggedSnapFallback = false

    /** 「分割线锁定推送被改写」的日志只打一次（桌面会频繁推送，避免刷日志）。 */
    private var loggedFixedPush = false

    /** 「无法构造自由吸附目标」的日志只打一次。 */
    private var loggedBuildFailure = false

    /** 正在执行 `SplitLayout#snapToTarget` 的松手落位（用于识别随后的 fling 调用）。 */
    @Volatile
    private var releaseInFlight = false

    /** `setToDefaultSplitRatioIfNeed` 执行期间置位：期间跳过 `setDivideRatioForSoSc`（不重置比例）。 */
    @Volatile
    private var suppressForcedRatio = false

    override fun init() {
        val loader = target.classLoader ?: throw HookSkippedException("no classloader")
        this.loader = loader
        stepless = HookPrefs.getBoolean(SplitScreenKeys.SPLIT_FREE_RATIO_GLOBAL, false)
        var hooked = hookSnap(loader) + hookSoScFindSnapTarget(loader) + hookForcedRatioReset(loader) +
            hookDividerFixedNormalize(loader) + hookReleaseTiming(loader) +
            hookScaleBackGuard(loader) + hookSnapTargetLookup(loader)
        if (hooked == 0) throw HookSkippedException("split ratio hooks not installed")
        HookHelper.log("$tag: hooked $hooked method(s) (stepless=$stepless, magnet=${magnetRadiusPx()}px)")
    }

    /**
     * 自由档位的 `snapPosition`（10）会被官方写进 `SplitLayout#mSplitState`；官方在
     * 「最小化单应用分屏里拖拽分割线」等路径会用 `DividerSnapAlgorithm#findSnapTarget(snapPosition)`
     * 反查目标表，而表里没有 10 → 返回 null → `SoScUtilsImpl#sendPreSoScStateChangedIfNeed`
     * 直接读 `.position`，触发 NullPointerException（系统界面崩溃）。
     * 这里按官方兄弟分支同款回退，把落空的查询换成 `mMiddleTarget`（官方在
     * `findSnapTarget(2)/(0)` 落空时用的就是它）。
     */
    private fun hookSnapTargetLookup(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(DIVIDER_SNAP_ALGORITHM, loader) ?: run {
            HookHelper.log("$tag: $DIVIDER_SNAP_ALGORITHM not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "findSnapTarget" && it.parameterCount == 1 }
            ?: run {
                HookHelper.log("$tag: DividerSnapAlgorithm#findSnapTarget(int) not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookAfter(method) { param ->
                if (param.result != null) return@hookAfter
                val algorithm = param.thisObject ?: return@hookAfter
                val fallback = runCatching { Reflect.getObjectField(algorithm, "mMiddleTarget") }
                    .getOrNull() ?: return@hookAfter
                param.setResultValue(fallback)
                if (!loggedSnapFallback) {
                    loggedSnapFallback = true
                    HookHelper.log(
                        "$tag: findSnapTarget miss -> middle target fallback " +
                            "(free snap state ${param.args.getOrNull(0)})",
                    )
                }
            }
            1
        }.onFailure { HookHelper.log("$tag: hook findSnapTarget(int) failed", it) }.getOrDefault(0)
    }

    /**
     * 「放大」缩放回弹（`SoScResizingEffectPolicy#resetRootSurfaceForSoSc`）防重入：
     * 官方在回弹动画未结束时再次进入会先 `mAnimatorForSoSc.end()`，把动画直接跳到终态
     * （观感「放大播到一半直接切到终态」）。已在回弹中时跳过重入，让当前动画自然播完。
     */
    private fun hookScaleBackGuard(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(SOSC_RESIZING_POLICY, loader) ?: run {
            HookHelper.log("$tag: $SOSC_RESIZING_POLICY not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "resetRootSurfaceForSoSc" && it.parameterCount == 2 }
            ?: run {
                HookHelper.log("$tag: SoScResizingEffectPolicy#resetRootSurfaceForSoSc not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param ->
                val self = param.thisObject ?: return@hookBefore
                val animator = field(self, "mAnimatorForSoSc") as? android.animation.ValueAnimator
                if (animator?.isRunning == true) {
                    param.setResultValue(null)
                    if (!loggedScaleBackSkip) {
                        loggedScaleBackSkip = true
                        HookHelper.log("$tag: scale-back re-entry skipped (keep running animation)")
                    }
                }
            }
            1
        }.onFailure { HookHelper.log("$tag: hook resetRootSurfaceForSoSc failed", it) }.getOrDefault(0)
    }

    /**
     * 自由松手的收尾路径归一化（见类注释第 4 点）：
     * `snapToTarget` 期间打标记；`flingDividerPosition(i == i2)`（自由比例落位，官方走「立即返回」
     * 分支）时改写为一次「几乎不动」的官方 fling，逐帧更新与收尾全部走官方实现。
     */
    private fun hookReleaseTiming(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(SPLIT_LAYOUT, loader) ?: run {
            HookHelper.log("$tag: $SPLIT_LAYOUT not found")
            return 0
        }
        var hooked = 0
        cls.declaredMethods
            .firstOrNull { it.name == "snapToTarget" && it.parameterCount == 4 }
            ?.also { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookBefore(method) { releaseInFlight = true }
                    HookHelper.hookAfter(method) { releaseInFlight = false }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook snapToTarget failed", it) }
            } ?: HookHelper.log("$tag: SplitLayout#snapToTarget not found")
        cls.declaredMethods
            .firstOrNull { it.name == "flingDividerPosition" && it.parameterCount == 5 }
            ?.also { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookBefore(method) { param ->
                        runCatching { rerouteFreeRelease(param) }
                            .onFailure { HookHelper.log("$tag: reroute free release failed", it) }
                    }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook flingDividerPosition failed", it) }
            } ?: HookHelper.log("$tag: SplitLayout#flingDividerPosition not found")
        return hooked
    }

    /**
     * 自由松手（`from == to`）改写为官方 fling：起点挪到目标旁 1px（朝向屏幕中线，保证不越界）。
     * 官方随后按正常动画路径逐帧更新并 `onAnimationEnd` 收尾，收尾用的仍是真实目标
     * （`SnapTarget.position`，不受 1px 影响）。
     *
     * 时长必须保持官方松手传入的值（250ms），不能压短：拖拽期间官方把窗口整体缩放
     * （`mScaledRatio` ≈ 0.969 的“剥离/回缩”预览），松手后靠这段 fling 的每一帧把缩放平滑回
     * 1.0——这就是用户看到的「放大」动画。时长压到几帧会让它在 1~2 帧内跳完（观感直接跳到终态）。
     */
    private fun rerouteFreeRelease(param: HookParam) {
        val from = param.args.getOrNull(0) as? Int ?: return
        val to = param.args.getOrNull(1) as? Int ?: return
        if (!releaseInFlight || from != to) return
        releaseInFlight = false
        val start = freeReleaseStart(from) ?: (from + 1)
        param.setArg(0, start)
        if (!loggedDeferredRelease) {
            loggedDeferredRelease = true
            HookHelper.log(
                "$tag: free release routed through official fling " +
                    "(pos=$to, start=$start, duration=${param.args.getOrNull(2)})",
            )
        }
    }

    /** 改写起点：朝屏幕中线偏 1px（拿不到中线时用 +1，行为与官方立即分支一致）。 */
    private fun freeReleaseStart(position: Int): Int? = runCatching {
        val cl = loader ?: return@runCatching null
        val layout = SplitScreenSupport.splitLayout(cl) ?: return@runCatching null
        val algorithm = Reflect.getObjectField(layout, "mDividerSnapAlgorithm") ?: return@runCatching null
        val middle = Reflect.getObjectField(algorithm, "mMiddleTarget") ?: return@runCatching null
        val middlePosition = Reflect.getObjectField(middle, "position") as? Int ?: return@runCatching null
        if (position <= middlePosition) position + 1 else position - 1
    }.getOrNull()

    /**
     * 把桌面推送的「分割线锁定」（`mIsDividerFixed`）改写成 false：官方多处用它把比例钳回 1:1
     * （松手状态广播重算边界、拖拽预览边界），是本功能在单应用分屏里「完全失效」与松手动画
     * 不连贯的根源。
     */
    private fun hookDividerFixedNormalize(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(STAGE_COORDINATOR, loader) ?: run {
            HookHelper.log("$tag: $STAGE_COORDINATOR not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "updateDividerFixedState" && it.parameterCount == 1 }
            ?: run {
                HookHelper.log("$tag: SoScStageCoordinator#updateDividerFixedState not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param ->
                val fixed = param.args.getOrNull(0) as? Boolean ?: return@hookBefore
                if (!fixed) return@hookBefore
                param.setArg(0, false)
                if (!loggedFixedPush) {
                    loggedFixedPush = true
                    HookHelper.log("$tag: divider-fixed push normalized to false (ratio features on)")
                }
            }
            1
        }.onFailure { HookHelper.log("$tag: hook updateDividerFixedState failed", it) }.getOrDefault(0)
    }

    /**
     * 已经存在的 true（桌面在安装 / 切换前后推送过）在这里主动清掉；
     * 拖拽过程中持续检查，避免被其它流程（如「切换方向保持比例」收尾恢复）写回 true。
     */
    private fun normalizeDividerFixedIfNeeded() {
        runCatching {
            val cl = loader ?: return
            val coordinator = SplitScreenSupport.stageCoordinator(cl) ?: return
            val current = Reflect.getObjectField(coordinator, "mIsDividerFixed") as? Boolean ?: return
            if (current) {
                Reflect.setObjectField(coordinator, "mIsDividerFixed", false)
                if (!loggedFixedNormalize) {
                    loggedFixedNormalize = true
                    HookHelper.log("$tag: divider-fixed flag cleared (free ratio on)")
                }
            }
        }.onFailure { HookHelper.log("$tag: normalize divider-fixed failed", it) }
    }

    /** 无级调节：`DividerSnapAlgorithm#snap` 在档位区间内返回自由目标（官方同款 snapPosition 10）。 */
    private fun hookSnap(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(DIVIDER_SNAP_ALGORITHM, loader) ?: run {
            HookHelper.log("$tag: $DIVIDER_SNAP_ALGORITHM not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "snap" && it.parameterCount == 2 }
            ?: run {
                HookHelper.log("$tag: DividerSnapAlgorithm#snap not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param ->
                runCatching { applyFreeSnap(loader, param) }
                    .onFailure { HookHelper.log("$tag: free snap failed", it) }
            }
            1
        }.onFailure { HookHelper.log("$tag: hook DividerSnapAlgorithm#snap failed", it) }.getOrDefault(0)
    }

    /**
     * `SoScUtilsImpl#findSnapTarget(SplitLayout, DividerSnapAlgorithm, SnapTarget, SnapTarget, int)`：
     * 单应用分屏下官方会把目标钳到「固定档位 / 1:1」，这里直接改用算法算出的目标
     * （关闭分屏、最小化、用户锁定分割线三种情况仍保留官方行为）。
     */
    private fun hookSoScFindSnapTarget(loader: ClassLoader): Int {
        val cls = Reflect.findClassIfExists(SplitScreenSupport.SOSC_UTILS_IMPL, loader) ?: run {
            HookHelper.log("$tag: ${SplitScreenSupport.SOSC_UTILS_IMPL} not found")
            return 0
        }
        val method = cls.declaredMethods
            .firstOrNull { it.name == "findSnapTarget" && it.parameterCount == 5 }
            ?: run {
                HookHelper.log("$tag: SoScUtilsImpl#findSnapTarget(5) not found")
                return 0
            }
        method.isAccessible = true
        return runCatching {
            HookHelper.hookBefore(method) { param ->
                val calculated = param.args.getOrNull(2) ?: return@hookBefore
                val snapPosition = intField(calculated, "snapPosition") ?: return@hookBefore
                if (snapPosition == DISMISS_START || snapPosition == DISMISS_END) return@hookBefore
                if (!inSingleMode(loader)) return@hookBefore
                if (isMinimized(loader)) return@hookBefore
                // 注意：不要判断「分割线已锁定」（isDividerFixed）——平板上桌面（Launcher）在分屏 /
                // 单应用分屏下会常驻 isFixed=true，官方据此把吸附目标钳回 1:1，正是本功能要解除的行为。
                if (!loggedFirstBypass) {
                    loggedFirstBypass = true
                    HookHelper.log(
                        "$tag: single-mode snap clamp bypassed (snapPosition=$snapPosition, " +
                            "dividerFixed=${isDividerFixed(loader)})",
                    )
                }
                param.setResultValue(calculated)
            }
            1
        }.onFailure { HookHelper.log("$tag: hook SoScUtilsImpl#findSnapTarget failed", it) }.getOrDefault(0)
    }

    /**
     * 拦掉「设置系统强制比例」：`setToDefaultSplitRatioIfNeed` 内部会调
     * `setDivideRatioForSoSc(1, …)` 把分割线重置回 1:1（关闭另一个应用、点把手时都会触发）。
     * 这里在该方法执行期间把 `setDivideRatioForSoSc` 直接当作「无需调整」返回，
     * 其余调用点（进入分屏等）保持官方行为。
     */
    private fun hookForcedRatioReset(loader: ClassLoader): Int {
        var hooked = 0
        val coordinatorCls = Reflect.findClassIfExists(STAGE_COORDINATOR, loader)
        coordinatorCls?.declaredMethods
            ?.firstOrNull { it.name == "setToDefaultSplitRatioIfNeed" && it.parameterCount == 1 }
            ?.also { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookBefore(method) { suppressForcedRatio = true }
                    HookHelper.hookAfter(method) { suppressForcedRatio = false }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook setToDefaultSplitRatioIfNeed failed", it) }
            } ?: HookHelper.log("$tag: $STAGE_COORDINATOR#setToDefaultSplitRatioIfNeed not found")
        val layoutCls = Reflect.findClassIfExists(SPLIT_LAYOUT, loader)
        layoutCls?.declaredMethods
            ?.firstOrNull { it.name == "setDivideRatioForSoSc" && it.parameterCount == 2 }
            ?.also { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookBefore(method) { param ->
                        if (suppressForcedRatio) param.setResultValue(false)
                    }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook setDivideRatioForSoSc failed", it) }
            } ?: HookHelper.log("$tag: $SPLIT_LAYOUT#setDivideRatioForSoSc not found")
        return hooked
    }

    private fun applyFreeSnap(loader: ClassLoader, param: HookParam) {
        normalizeDividerFixedIfNeeded()
        val algorithm = param.thisObject ?: return
        // 官方自由吸附已开启时保持原实现。
        if (field(algorithm, "mFreeSnapMode") as? Boolean == true) return
        val position = param.args.getOrNull(0) as? Int ?: return
        if (!stepless) return
        // 磁吸：无级调节下，松手位置非常接近系统档位（1:1 / 1:3 / 1:9 …）时仍然吸附过去。
        SplitScreenSupport.nearestSnapTarget(algorithm, position, magnetRadiusPx())?.let {
            if (!loggedMagnet) {
                loggedMagnet = true
                HookHelper.log("$tag: magnet snap active (radius=${magnetRadiusPx()}px)")
            }
            param.setResultValue(it)
            return
        }
        val range = snapRange(algorithm) ?: return
        if (position <= range.first || position >= range.second) return
        val target = newSnapTarget(loader, algorithm, position) ?: run {
            if (!loggedBuildFailure) {
                loggedBuildFailure = true
                HookHelper.log("$tag: cannot build free snap target")
            }
            return
        }
        param.setResultValue(target)
    }

    /**
     * 构造自由吸附目标 `new SnapTarget(position, 10)`。
     *
     * `SnapTarget` 是静态嵌套类（构造器只有 `(int,int)` / `(int,int,float)`）；个别版本若把它
     * 编译成内部类，构造器会多一个外部实例参数，这里两种签名都尝试一次。
     */
    private fun newSnapTarget(loader: ClassLoader, algorithm: Any, position: Int): Any? {
        val targetCls = runCatching { Reflect.findClass(SNAP_TARGET, loader) }.getOrNull() ?: return null
        return runCatching { Reflect.newInstance(targetCls, position, FREE_SNAP_POSITION) }
            .recoverCatching { Reflect.newInstance(targetCls, algorithm, position, FREE_SNAP_POSITION) }
            .getOrNull()
    }

    /** 磁吸半径（px），惰性换算一次。 */
    private fun magnetRadiusPx(): Int =
        magnetRadius ?: SplitScreenSupport.dpToPx(MAGNET_DP).also { magnetRadius = it }

    /** 是否处于「单个应用分屏」状态。 */
    private fun inSingleMode(loader: ClassLoader): Boolean = runCatching {
        val soSc = SplitScreenSupport.soScUtils(loader) ?: return@runCatching false
        (Reflect.callMethod(soSc, "inSoScSingleMode") as? Boolean) == true
    }.getOrDefault(false)

    /** 分屏窗口是否处于最小化（迷你）状态。 */
    private fun isMinimized(loader: ClassLoader): Boolean = runCatching {
        val coordinator = SplitScreenSupport.stageCoordinator(loader) ?: return@runCatching false
        (Reflect.callMethod(coordinator, "isMinimized") as? Boolean) == true
    }.getOrDefault(false)

    /** 用户是否锁定了分割线。 */
    private fun isDividerFixed(loader: ClassLoader): Boolean = runCatching {
        val soSc = SplitScreenSupport.soScUtils(loader) ?: return@runCatching false
        (Reflect.callMethod(soSc, "isDividerFixed") as? Boolean) == true
    }.getOrDefault(false)

    /** 全部非「关闭分屏」档位覆盖的位置范围（最小 → 最大），即可以自由停留的区间。 */
    private fun snapRange(algorithm: Any): Pair<Int, Int>? {
        val targets = field(algorithm, "mTargets") as? List<*> ?: return null
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (target in targets) {
            if (target == null) continue
            val snapPosition = field(target, "snapPosition") as? Int ?: continue
            if (snapPosition == DISMISS_START || snapPosition == DISMISS_END) continue
            val position = field(target, "position") as? Int ?: continue
            if (position < lo) lo = position
            if (position > hi) hi = position
        }
        return if (lo < hi) lo to hi else null
    }

    /** 只读字段（结果按类缓存，拖拽过程中会高频调用）。 */
    private fun field(target: Any, name: String): Any? =
        runCatching { Reflect.findField(target.javaClass, name).get(target) }.getOrNull()

    private fun intField(target: Any, name: String): Int? =
        runCatching { Reflect.findField(target.javaClass, name).get(target) as? Int }.getOrNull()

    private companion object {
        const val DIVIDER_SNAP_ALGORITHM = "com.android.wm.shell.sosc.common.split.DividerSnapAlgorithm"
        const val SNAP_TARGET = "com.android.wm.shell.sosc.common.split.DividerSnapAlgorithm\$SnapTarget"
        const val STAGE_COORDINATOR = "com.android.wm.shell.sosc.SoScStageCoordinator"
        const val SPLIT_LAYOUT = "com.android.wm.shell.sosc.common.split.SplitLayout"
        const val SOSC_RESIZING_POLICY = "com.android.wm.shell.sosc.SoScResizingEffectPolicy"

        /** 官方自由吸附目标的 snapPosition（见 `DividerSnapAlgorithm#snap`）。 */
        const val FREE_SNAP_POSITION = 10

        /** 「关闭分屏」目标（`SplitScreenConstants.isDismissSnapPosition`）。 */
        const val DISMISS_START = 11
        const val DISMISS_END = 12

        /** 磁吸半径（dp）：无级调节下，距系统档位小于该距离即吸附过去。 */
        const val MAGNET_DP = 24f

    }
}
