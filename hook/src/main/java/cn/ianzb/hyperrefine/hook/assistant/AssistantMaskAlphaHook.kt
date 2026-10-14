package cn.ianzb.hyperrefine.hook.assistant

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.concurrent.atomic.AtomicInteger

/**
 * 负一屏「闪黑」修复：过渡遮罩（mask）的起始 alpha 归零。
 *
 * 背景（思路参考 HyperAssistant，独立实现）：负一屏打开 / 返回时，HyperViewScale 过渡会建立
 * 一块全屏遮罩 surface 用于纯色兜底 / 快照填充，官方从 `maskAlphaFrom = 1.0` 起播；快速切换
 * （连续划入划出、打开后立即返回）时会先闪出一整块纯色（黑）。把 `ViewScaleAnimInfo` 构造后的
 * 起始 alpha 钉成 0 后，遮罩全程不可见，消除闪黑；终值（`maskAlphaTo`）保持官方行为不动。
 *
 * 目标进程：`com.miui.personalassistant`。
 */
class AssistantMaskAlphaHook : BaseHook() {

    override val key: String = AssistantKeys.MASK_ALPHA_ZERO

    private val logCount = AtomicInteger(0)
    private val warnCount = AtomicInteger(0)

    override fun init() {
        val loader = target.classLoader
        AssistantHvsSupport.installWithRetry(tag) { install(loader) }
    }

    private fun install(loader: ClassLoader?): Boolean {
        val clazz = AssistantHvsSupport.findClass(VIEW_SCALE_ANIM_INFO, loader) ?: return false
        val field = runCatching { Reflect.findField(clazz, "maskAlphaFrom") }.getOrNull() ?: return false
        // 必须在构造器「执行后」写：字段由构造器赋值，before 写入会被覆盖。
        clazz.declaredConstructors.forEach { ctor ->
            runCatching {
                HookHelper.hookAfter(ctor) { param ->
                    val self = param.thisObject ?: return@hookAfter
                    val ok = runCatching {
                        field.setFloat(self, 0f)
                        true
                    }.getOrDefault(false)
                    if (ok) {
                        if (logCount.getAndIncrement() == 0) {
                            HookHelper.log("$tag: maskAlphaFrom -> 0.0 @ ${clazz.name}")
                        }
                    } else if (warnCount.getAndIncrement() == 0) {
                        HookHelper.log("$tag: write maskAlphaFrom failed（字段可能为 final）")
                    }
                }
            }.onFailure { HookHelper.log("$tag: hook ctor failed", it) }
        }
        return true
    }

    private companion object {
        const val VIEW_SCALE_ANIM_INFO = "android.app.hyperviewscale.ViewScaleAnimInfo"
    }
}
