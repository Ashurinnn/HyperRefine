package cn.ianzb.hyperrefine.hook.assistant

import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 「返回缩回动画」触发：监听负一屏窗口的可见性变化
 * （`android.view.ViewRootImpl.setWindowVisibility`）。
 *
 * 应用盖住负一屏时窗口隐藏、关闭应用回到负一屏时窗口重新可见；
 * 素材与条件判断都在 [AssistantReturnFlight] 内完成，本 Hook 只转发信号。
 *
 * 目标进程：`com.miui.personalassistant`。
 */
class AssistantReturnFlightHook : BaseHook() {

    override val key: String = AssistantKeys.RETURN_FLIGHT

    override fun init() {
        val loader = target.classLoader
        val viewRootImpl = Reflect.findClassIfExists("android.view.ViewRootImpl", loader) ?: run {
            HookHelper.log("$tag: ViewRootImpl not found")
            return
        }
        val methods = viewRootImpl.declaredMethods.filter {
            it.name == "setWindowVisibility" &&
                it.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE))
        }
        if (methods.isEmpty()) {
            HookHelper.log("$tag: setWindowVisibility not found")
            return
        }
        methods.forEach { method ->
            runCatching {
                HookHelper.hookBefore(method) { param ->
                    val visible = param.args.getOrNull(0) as? Boolean ?: return@hookBefore
                    val self = param.thisObject ?: return@hookBefore
                    val rootView = runCatching { Reflect.callMethod(self, "getView") as? View }.getOrNull()
                        ?: return@hookBefore
                    AssistantReturnFlight.onWindowVisibility(rootView, visible)
                }
            }.onFailure { HookHelper.log("$tag: hook setWindowVisibility failed", it) }
        }
        HookHelper.log("$tag: setWindowVisibility hooked (${methods.size})")
    }
}
