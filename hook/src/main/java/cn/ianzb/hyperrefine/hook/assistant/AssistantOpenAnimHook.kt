package cn.ianzb.hyperrefine.hook.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 负一屏「打开应用动画」：点按微件 / 应用图标打开应用时，从图标位置缩放展开。
 *
 * 安装三类钩子（思路参考 HyperAssistant，独立实现）：
 * 1. 记源：`View.performClick` 之后、`View.dispatchTouchEvent`（只认 MAML / 负一屏视图类）、
 *    微件网格 `AppGrid$2.onClick`、MAML 触控视图的 `dispatchTouchEvent`；
 * 2. 拦截：`android.app.ContextImpl.startActivity`（所有重载）——记源后 2.5 秒内的启动带着
 *    `ActivityOptionsHelper` 产出的动画 options 重新发起，原调用被吞；
 * 3. 兜底：options 被拒绝时按固定顺序逐级剥离兼容键重试，全部失败再退回框架最小缩放动画，
 *    仍失败则放行原始启动（应用照常打开，只是没有动画）。
 *
 * 目标进程：`com.miui.personalassistant`。
 */
class AssistantOpenAnimHook : BaseHook() {

    override val key: String = AssistantKeys.OPEN_ANIM

    private val launchInstalled = AtomicBoolean(false)
    private val performClickInstalled = AtomicBoolean(false)
    private val dispatchInstalled = AtomicBoolean(false)
    private val appGridInstalled = AtomicBoolean(false)
    private val mamlHooked = ConcurrentHashMap.newKeySet<String>()
    private val logCount = AtomicInteger(0)

    override fun init() {
        val loader = target.classLoader ?: return
        AssistantHvsSupport.installWithRetry(tag) { install(loader) }
    }

    private fun install(loader: ClassLoader): Boolean {
        AssistantOpenAnim.attach(loader)
        if (!AssistantOpenAnim.ready()) return false
        installLaunchHook(loader)
        installArmHooks(loader)
        installOptionalArmHooks(loader)
        return launchInstalled.get() && performClickInstalled.get()
    }

    /** `ContextImpl.startActivity`（所有重载）→ 带 options 重新启动。 */
    private fun installLaunchHook(loader: ClassLoader) {
        if (launchInstalled.get()) return
        val contextImpl = Reflect.findClassIfExists("android.app.ContextImpl", loader) ?: return
        val handles = HookHelper.hookAllMethods(contextImpl, "startActivity") { param -> handleLaunch(param) }
        if (handles.isEmpty()) return
        launchInstalled.set(true)
        HookHelper.log("$tag: ContextImpl.startActivity hooks installed (${handles.size})")
    }

    /** 记源钩子：performClick（全部视图）与 dispatchTouchEvent（只认 MAML / 负一屏视图类）。 */
    private fun installArmHooks(loader: ClassLoader) {
        val viewClass = Reflect.findClassIfExists("android.view.View", loader) ?: return
        if (!performClickInstalled.get()) {
            var count = 0
            viewClass.declaredMethods.filter { it.name == "performClick" }.forEach { method ->
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        AssistantOpenAnim.arm(param.thisObject as? View, "performClick")
                    }
                    count++
                }
            }
            if (count > 0) performClickInstalled.set(true)
        }
        if (!dispatchInstalled.get()) {
            var count = 0
            viewClass.declaredMethods.filter { it.name == "dispatchTouchEvent" }.forEach { method ->
                runCatching {
                    HookHelper.hookBefore(method) { param ->
                        val view = param.thisObject as? View ?: return@hookBefore
                        val action = (param.args.getOrNull(0) as? MotionEvent)?.actionMasked ?: return@hookBefore
                        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return@hookBefore
                        val name = view.javaClass.name
                        if (name.startsWith("com.miui.maml.") ||
                            name.startsWith("com.miui.personalassistant.core.view.") ||
                            name.endsWith("LayerView") || name.endsWith("InnerView")
                        ) {
                            AssistantOpenAnim.arm(view, "touch")
                        }
                    }
                    count++
                }
            }
            if (count > 0) dispatchInstalled.set(true)
        }
    }

    /** 可选记源钩子：微件网格点击与 MAML 触控视图（类可能稍后才加载，按需补挂）。 */
    private fun installOptionalArmHooks(loader: ClassLoader) {
        if (!appGridInstalled.get()) {
            val appGrid = Reflect.findClassIfExists(APP_GRID, loader)
            if (appGrid != null) {
                var count = 0
                appGrid.declaredMethods.filter { it.name == "onClick" && it.parameterCount >= 1 }.forEach { method ->
                    runCatching {
                        HookHelper.hookBefore(method) { param ->
                            firstViewArg(param)?.let { AssistantOpenAnim.arm(it, "AppGrid.onClick") }
                        }
                        count++
                    }
                }
                if (count > 0) {
                    appGridInstalled.set(true)
                    HookHelper.log("$tag: AppGrid onClick hooks installed (${count})")
                }
            }
        }
        for (className in MAML_TOUCH_VIEWS) {
            if (mamlHooked.contains(className)) continue
            val clazz = Reflect.findClassIfExists(className, loader) ?: continue
            var count = 0
            clazz.declaredMethods.filter { it.name == "dispatchTouchEvent" }.forEach { method ->
                runCatching {
                    HookHelper.hookBefore(method) { param ->
                        val view = param.thisObject as? View ?: return@hookBefore
                        val action = (param.args.getOrNull(0) as? MotionEvent)?.actionMasked ?: return@hookBefore
                        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                            AssistantOpenAnim.arm(view, "maml.touch")
                        }
                    }
                    count++
                }
            }
            if (count > 0) mamlHooked.add(className)
        }
    }

    private fun handleLaunch(param: HookParam) {
        val intent = param.args.firstNotNullOfOrNull { it as? Intent } ?: return
        if (!AssistantOpenAnim.isArmed()) return
        val context = param.thisObject as? Context ?: return
        val source = AssistantOpenAnim.consumeArmed() ?: return
        if (logCount.getAndIncrement() < 20) {
            HookHelper.log(
                "$tag: intercept startActivity -> " +
                    (intent.component?.className ?: intent.`package`)
            )
        }
        val options = AssistantOpenAnim.rawOptionsBundle(source)
        if (options == null) {
            HookHelper.log("$tag: no usable options, fall through")
            return
        }
        for (level in 0..AssistantOpenAnim.dropOrder.size) {
            val bundle = Bundle(options)
            for (i in 0 until level) bundle.remove(AssistantOpenAnim.dropOrder[i])
            if (tryLaunch(context, intent, bundle, level)) {
                param.setResultValue(null)
                return
            }
        }
        val fallback = AssistantOpenAnim.frameworkScaleUpBundle(source)
        if (fallback != null && tryLaunch(context, intent, fallback, null)) {
            param.setResultValue(null)
        } else {
            HookHelper.log("$tag: all launch routes failed, let original call proceed")
        }
    }

    private fun tryLaunch(context: Context, intent: Intent, bundle: Bundle, level: Int?): Boolean =
        runCatching {
            context.startActivity(intent, bundle)
            true
        }.getOrElse { error ->
            if (logCount.getAndIncrement() < 30) {
                HookHelper.log(
                    "$tag: launch rejected (strip=$level): " +
                        "${error.javaClass.simpleName} ${error.message}"
                )
            }
            false
        }

    private fun firstViewArg(param: HookParam): View? =
        param.args.firstNotNullOfOrNull { it as? View }

    private companion object {
        const val APP_GRID = "com.miui.miuiwidget.servicedelivery.appitem.AppGrid\$2"

        val MAML_TOUCH_VIEWS = listOf(
            "com.miui.maml.elements.LayerScreenElement\$LayerView",
            "com.miui.maml.component.MamlView\$InnerView",
            "com.miui.personalassistant.core.view.MaMlHostView",
        )
    }
}
