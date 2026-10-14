package cn.ianzb.hyperrefine.hook.assistant

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.view.Surface
import android.view.SurfaceControl
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Constructor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * 负一屏过渡「遮罩重绘」：把掩码 surface 的内容重画成源视图快照。
 *
 * 背景（思路参考 HyperAssistant，独立实现）：过渡时 HyperViewScale 会为「要缩放的视图」建立一块
 * 全屏掩码 surface（`setMaskSurface`）。官方在部分路径下不填 / 填错这块 buffer，表现为过渡中
 * 遮罩上显示异常内容（例如微件位置出现应用内容、纯色块等）。这里在 `setMaskSurface` 返回后，
 * 用该次过渡自带的视图快照（`ViewInfo.getBitmap()`）按视图矩形重新绘制掩码：先铺视图背景主色，
 * 再把快照按落点铺上去。位图 / 尺寸不可用时保持官方结果不动（不做任何降级绘制）。
 *
 * 目标进程：`com.miui.personalassistant`。
 */
class AssistantMaskBufferHook : BaseHook() {

    override val key: String = AssistantKeys.MASK_BUFFER

    private val logCount = AtomicInteger(0)
    private val warnCount = AtomicInteger(0)

    @Volatile
    private var surfaceCtor: Constructor<Surface>? = null

    override fun init() {
        val loader = target.classLoader
        AssistantHvsSupport.installWithRetry(tag) { install(loader) }
    }

    private fun install(loader: ClassLoader?): Boolean {
        val manager = AssistantHvsSupport.implClass(loader) ?: return false
        val methods = manager.declaredMethods.filter { it.name == "setMaskSurface" }
        if (methods.isEmpty()) return false
        methods.forEach { method ->
            runCatching {
                HookHelper.hookAfter(method) { param -> redraw(param) }
            }.onFailure { warn("hook setMaskSurface failed", it) }
        }
        HookHelper.log("$tag: setMaskSurface hooks installed (${methods.size}) @ ${manager.name}")
        return true
    }

    private fun redraw(param: HookParam) {
        val control = param.result as? SurfaceControl ?: return
        val rect = param.args.getOrNull(0) as? Rect ?: return
        if (rect.width() <= 0 || rect.height() <= 0) return
        val viewInfo = param.args.getOrNull(1) ?: return
        val bitmap = call(viewInfo, "getBitmap") as? Bitmap
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            warnOnce("bitmap not ready, keep official mask content")
            return
        }
        val viewRect = call(viewInfo, "getViewRect") as? Rect
        var fill = (call(viewInfo, "getViewBackgroundColor") as? Int) ?: 0
        if (fill == 0) {
            fill = (param.thisObject?.let { call(it, "getViewDominantColor", viewInfo) } as? Int) ?: 0
        }
        val destination = destination(rect, bitmap, viewRect)
        val surface = newSurface(control) ?: return
        try {
            val canvas = runCatching { surface.lockHardwareCanvas() }
                .getOrElse { surface.lockCanvas(null) }
            try {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                if (fill != 0) canvas.drawColor(fill)
                canvas.drawBitmap(bitmap, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            if (logCount.getAndIncrement() < 20) {
                HookHelper.log(
                    "$tag: mask redrawn fill=0x${Integer.toHexString(fill)} " +
                        "viewRect=$viewRect bitmap=${bitmap.width}x${bitmap.height} dest=$destination"
                )
            }
        } catch (t: Throwable) {
            warnOnce("redraw failed: ${t.javaClass.simpleName}")
        } finally {
            runCatching { surface.release() }
        }
    }

    private fun destination(rect: Rect, bitmap: Bitmap, viewRect: Rect?): RectF {
        if (viewRect != null && viewRect.width() > 0 && viewRect.height() > 0) {
            val left = (viewRect.left - rect.left).toFloat()
            val top = (viewRect.top - rect.top).toFloat()
            return RectF(left, top, left + viewRect.width(), top + viewRect.height())
        }
        val scale = max(
            rect.width().toFloat() / bitmap.width,
            rect.height().toFloat() / bitmap.height,
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (rect.width() - width) / 2f
        val top = (rect.height() - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    private fun newSurface(control: SurfaceControl): Surface? {
        val ctor = surfaceCtor ?: runCatching {
            Surface::class.java.getConstructor(SurfaceControl::class.java).also { it.isAccessible = true }
        }.getOrNull()?.also { surfaceCtor = it } ?: return null
        return runCatching { ctor.newInstance(control) }.getOrElse {
            surfaceCtor = null
            warnOnce("new Surface(SurfaceControl) failed: ${it.javaClass.simpleName}")
            null
        }
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? =
        runCatching { Reflect.callMethod(target, name, *args) }.getOrNull()

    private fun warnOnce(message: String) {
        if (warnCount.getAndIncrement() < 8) HookHelper.log("$tag: $message")
    }

    private fun warn(message: String, t: Throwable) {
        if (warnCount.getAndIncrement() < 8) HookHelper.log("$tag: $message", t)
    }
}
