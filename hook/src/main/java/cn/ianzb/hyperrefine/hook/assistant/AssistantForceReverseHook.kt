package cn.ianzb.hyperrefine.hook.assistant

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 负一屏上滑返回：合并动画强制反向。
 *
 * 背景（思路参考 HyperAssistant，独立实现）：负一屏上滑返回时，系统会把返回并入既有过渡
 * （`handleMergeAnimation`，transition type 1021）；这条路径上官方实现可能继续按「打开方向」
 * 播放，表现为返回动画先播一半再跳回去。本 Hook 分两步：
 *
 * 1. `HyperViewScaleRemoteTransition.handleMergeAnimation` 命中「type=1021 且 mOpenScene=true」
 *    时举旗；
 * 2. 紧随其后的 `HyperViewScaleAnimationImpl.reverseAnimationWhenMerge` 把 `reverseToStart`
 *    参数（第 2 个实参）置 true，交给小米自己的实现按返回方向播放动画。
 *
 * 目标进程：`com.miui.personalassistant`。
 */
class AssistantForceReverseHook : BaseHook() {

    override val key: String = AssistantKeys.FORCE_REVERSE_MERGE

    private val armed = AtomicBoolean(false)
    private val logCount = AtomicInteger(0)

    override fun init() {
        val loader = target.classLoader
        AssistantHvsSupport.installWithRetry(tag) { install(loader) }
    }

    private fun install(loader: ClassLoader?): Boolean {
        val transition = AssistantHvsSupport.findClass(HVS_REMOTE_TRANSITION, loader) ?: return false
        val impl = AssistantHvsSupport.findClass(HVS_ANIMATION_IMPL, loader) ?: return false
        if (transition.declaredMethods.none { it.name == "handleMergeAnimation" }) return false
        if (impl.declaredMethods.none { it.name == "reverseAnimationWhenMerge" }) return false

        HookHelper.hookAllMethods(transition, "handleMergeAnimation") { param ->
            val type = transitionType(param.args)
            val openScene = param.thisObject?.let { self ->
                runCatching { Reflect.getObjectField(self, "mOpenScene") as? Boolean }.getOrNull()
            } ?: false
            if (type == "1021" && openScene) {
                if (armed.compareAndSet(false, true) && logCount.getAndIncrement() < 10) {
                    HookHelper.log("$tag: armed on merge (type=1021, mOpenScene=true)")
                }
            } else {
                armed.set(false)
            }
        }
        HookHelper.hookAllMethods(impl, "reverseAnimationWhenMerge") { param ->
            if (!armed.getAndSet(false)) return@hookAllMethods
            if (param.args.size > 1 && param.args[1] is Boolean) {
                param.setArg(1, true)
                if (logCount.getAndIncrement() < 20) {
                    HookHelper.log("$tag: reverseToStart -> true")
                }
            }
        }
        return true
    }

    /** 从回调实参里找 `...TransitionInfo` 并取其 `getType()` 字符串（如 "1021"）。 */
    private fun transitionType(args: List<Any?>): String? {
        for (arg in args) {
            val value = arg ?: continue
            if (!value.javaClass.name.endsWith("TransitionInfo")) continue
            return runCatching { Reflect.callMethod(value, "getType")?.toString() }.getOrNull()
        }
        return null
    }

    private companion object {
        const val HVS_REMOTE_TRANSITION = "android.app.hyperviewscale.HyperViewScaleRemoteTransition"
        const val HVS_ANIMATION_IMPL = "android.app.hyperviewscale.HyperViewScaleAnimationImpl"
    }
}
