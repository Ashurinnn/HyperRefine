package cn.ianzb.hyperrefine.hook.assistant

import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * 负一屏「打开应用动画」支持：记录点击源、解析 `miuix.transition.ActivityOptionsHelper`
 * 的动画工厂，并为 `startActivity` 构造带过渡动画的 `ActivityOptions`。
 *
 * 思路参考 HyperAssistant（未开源，独立实现）：点按负一屏上的微件 / 应用图标时记下源视图，
 * 随后的 `startActivity`（2.5 秒内）先尝试用系统自带的 `ActivityOptionsHelper` 工厂构造
 * HyperViewScale 打开动画（不可用依次回退 Rounded → ScaleUp），由调用方带着 options 重新
 * 发起启动；MIUI 可能拒绝含特定键的 options，因此按固定顺序逐级剥离后重试。所有工厂都不可用
 * 时放行原始启动（应用照常打开，只是没有动画）。
 */
internal object AssistantOpenAnim {

    /** 记源后 2.5s 内的首次 startActivity 视为由这次点击触发。 */
    private const val ARM_WINDOW_MS = 2500L

    /** MIUI 可能拒绝的 options 键：按此顺序逐级剥离重试。 */
    val dropOrder: List<String> = listOf(
        "android:activity.animAbortListener",
        "miui:activity.options.injector",
        "key_miui_config_flag",
        "android.activity.splashScreenStyle",
        "android:activity.view.name",
    )

    @Volatile
    private var appLoader: ClassLoader? = null

    @Volatile
    private var resolved = false

    @Volatile
    private var makeHyperScale: Method? = null

    @Volatile
    private var makeRounded: Method? = null

    @Volatile
    private var makeScaleUp: Method? = null

    @Volatile
    private var makeCapture: Method? = null

    @Volatile
    private var sourceView: View? = null

    @Volatile
    private var armedAtMs = 0L

    private val armLog = AtomicInteger(0)
    private val failLog = AtomicInteger(0)

    fun attach(classLoader: ClassLoader) {
        appLoader = classLoader
        resolve()
    }

    /** 动画工厂是否已解析出来（`ActivityOptionsHelper` 存在即视为就绪）。 */
    fun ready(): Boolean = resolved

    /** 解析 `miuix.transition.ActivityOptionsHelper`；类尚未加载时下次再试。 */
    private fun resolve() {
        if (resolved) return
        val loader = appLoader ?: return
        val clazz = Reflect.findClassIfExists(HELPER, loader) ?: return
        for (method in clazz.declaredMethods) {
            val params = method.parameterTypes
            runCatching {
                when {
                    method.name == "makeHyperViewScaleAnimation" && params.size == 1 ->
                        makeHyperScale = method.also { it.isAccessible = true }

                    method.name == "makeScaleUpAnimationFromRoundedView" &&
                        params.size == 4 && params[1] == Rect::class.java ->
                        makeRounded = method.also { it.isAccessible = true }

                    method.name == "makeScaleUpAnim" &&
                        params.size == 5 && params[1] == Rect::class.java ->
                        makeScaleUp = method.also { it.isAccessible = true }

                    method.name == "captureSnapshot" &&
                        params.size == 1 && params[0] == View::class.java ->
                        makeCapture = method.also { it.isAccessible = true }
                }
            }
        }
        resolved = true
        HookHelper.log(
            "AssistantOpenAnim: helper resolved hyper=${makeHyperScale != null} " +
                "rounded=${makeRounded != null} scaleUp=${makeScaleUp != null} capture=${makeCapture != null}"
        )
    }

    /**
     * 抓取视图快照（主线程）：优先小米 `captureSnapshot(View)`，失败退回 `View.draw`。
     * 供「返回缩回动画」等需要部件快照的场景使用。
     */
    fun captureSnapshot(view: View): Bitmap? {
        resolve()
        val method = makeCapture
        if (method != null) {
            runCatching { method.invoke(null, view) as? Bitmap }.getOrNull()?.let { return it }
        }
        return runCatching {
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) return@runCatching null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap
        }.getOrNull()
    }

    fun arm(view: View?, by: String) {
        if (view == null) return
        sourceView = view
        armedAtMs = SystemClock.uptimeMillis()
        if (armLog.getAndIncrement() < 20) {
            HookHelper.log("AssistantOpenAnim: armed by $by (${view.javaClass.simpleName} ${view.width}x${view.height})")
        }
    }

    fun isArmed(): Boolean {
        sourceView ?: return false
        return SystemClock.uptimeMillis() - armedAtMs < ARM_WINDOW_MS
    }

    fun consumeArmed(): View? {
        if (!isArmed()) return null
        val view = sourceView
        sourceView = null
        armedAtMs = 0L
        return view
    }

    /** 构造动画 options bundle；工厂不可用时返回 null（调用方放行原始启动）。 */
    fun rawOptionsBundle(view: View): Bundle? {
        resolve()
        val location = IntArray(2)
        var width = 0
        var height = 0
        runCatching {
            view.getLocationOnScreen(location)
            width = view.width
        }
        runCatching { height = view.height }
        invokeOptions(makeHyperScale, view)?.let { return it.toBundle() }
        val rect = Rect(location[0], location[1], location[0] + width, location[1] + height)
        invokeOptions(makeRounded, view, rect, width, height)?.let { return it.toBundle() }
        invokeOptions(makeScaleUp, view, rect, width, height, 0)?.let { return it.toBundle() }
        logFail("no factory produced a usable ActivityOptions")
        return null
    }

    /** 框架自带的最小缩放动画（最终兜底；与本模块效果无关，只保证有过渡）。 */
    fun frameworkScaleUpBundle(view: View): Bundle? = runCatching {
        var width = view.width
        var height = view.height
        if (width <= 0 || height <= 0) {
            width = 200
            height = 200
        }
        ActivityOptions.makeScaleUpAnimation(view, 0, 0, width, height).toBundle()
    }.getOrElse {
        logFail("framework scaleUp failed: ${it.javaClass.simpleName}")
        null
    }

    private fun invokeOptions(method: Method?, vararg args: Any?): ActivityOptions? {
        method ?: return null
        return runCatching {
            val result = method.invoke(null, *args)
            if (result is ActivityOptions) {
                if (method === makeHyperScale) {
                    HookHelper.log("AssistantOpenAnim: makeHyperViewScaleAnimation ok")
                }
                result
            } else {
                null
            }
        }.getOrElse {
            logFail("${method.name} failed: ${it.javaClass.simpleName}")
            null
        }
    }

    private fun logFail(message: String) {
        if (failLog.getAndIncrement() < 10) HookHelper.log("AssistantOpenAnim: $message")
    }

    private const val HELPER = "miuix.transition.ActivityOptionsHelper"
}
