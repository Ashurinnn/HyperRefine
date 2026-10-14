package cn.ianzb.hyperrefine.hook.assistant

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 负一屏「返回缩回动画」（返场）：从负一屏打开应用后关闭回到负一屏时，
 * 用部件快照播放「从全屏缩回部件位置」的收回动画，**接近末尾（最后约 25%）透明度从 0 到 100（渐隐）**。
 *
 * 思路参考 HyperAssistant（未开源，独立实现）：素材在点击部件、即将打开应用时抓取
 * （部件位置矩形 + 快照位图）；负一屏窗口先隐藏（被应用盖住）后重新可见、且隐藏时长
 * ≥2.5s 时播放——快照从「铺满全屏」按官方关闭曲线缩回部件矩形，末段渐隐后移除浮层。
 * 素材只在「先点击、后隐藏」的顺序下有效，且单次点击只用一次，避免误播。
 */
internal object AssistantReturnFlight {
    private const val TAG = "AssistantReturnFlight"

    /** 隐藏时长阈值：太短（切个应用马上回来）不播。 */
    private const val MIN_HIDDEN_MS = 2500L

    /** 素材有效期：点击后太久才回到负一屏就不播了。 */
    private const val MATERIAL_TTL_MS = 10 * 60 * 1000L

    /** 播放后的冷却，避免连续触发。 */
    private const val COOLDOWN_MS = 2320L

    /** 渐隐起点：动画进度最后 25% 用于透明度过渡。 */
    private const val FADE_START = 0.75f

    private class Material(
        val anchor: WeakReference<View>,
        val bitmap: Bitmap,
        val rect: Rect,
        val capturedAt: Long,
    )

    @Volatile
    private var material: Material? = null

    @Volatile
    private var hiddenAt = 0L

    @Volatile
    private var cooldownUntil = 0L

    @Volatile
    private var playing = false

    private val logCount = AtomicInteger(0)

    /** 点击部件、即将打开应用时抓取素材（主线程）。 */
    fun arm(view: View) {
        runCatching {
            if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
                log("arm skipped: view not ready")
                return
            }
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val bitmap = AssistantOpenAnim.captureSnapshot(view) ?: run {
                log("arm skipped: snapshot unavailable")
                return
            }
            val previous = material
            if (previous != null && !playing) runCatching { previous.bitmap.recycle() }
            material = Material(
                WeakReference(view),
                bitmap,
                Rect(location[0], location[1], location[0] + view.width, location[1] + view.height),
                SystemClock.uptimeMillis(),
            )
            hiddenAt = 0L
            log("armed rect=${material?.rect} bitmap=${bitmap.width}x${bitmap.height}")
        }.onFailure { HookHelper.log("$TAG: arm failed", it) }
    }

    /** `ViewRootImpl.setWindowVisibility` 信号（主线程）：只认素材 anchor 所在窗口。 */
    fun onWindowVisibility(rootView: View?, visible: Boolean) {
        val current = material ?: return
        val anchor = current.anchor.get() ?: return
        val root = runCatching { anchor.rootView }.getOrNull() ?: return
        if (rootView !== root) return
        val now = SystemClock.uptimeMillis()
        if (visible) {
            if (hiddenAt == 0L || now < cooldownUntil || playing) return
            val hiddenMs = now - hiddenAt
            if (hiddenMs < MIN_HIDDEN_MS) {
                log("visible ignored: hidden only ${hiddenMs}ms")
                return
            }
            // 素材必须先于隐藏建立（点击 → 应用盖住 → 返回），且不能太旧。
            if (current.capturedAt > hiddenAt || hiddenAt - current.capturedAt > MATERIAL_TTL_MS) {
                log("visible ignored: material mismatch")
                return
            }
            hiddenAt = 0L
            material = null
            play(current)
        } else {
            if (now < cooldownUntil) return
            hiddenAt = now
            log("window hidden, waiting for return")
        }
    }

    private fun play(current: Material) {
        val anchor = current.anchor.get() ?: return
        val root = runCatching { anchor.rootView as? ViewGroup }.getOrNull() ?: return
        if (root.width <= 0 || root.height <= 0) {
            log("play skipped: root not ready")
            return
        }
        playing = true
        cooldownUntil = SystemClock.uptimeMillis() + COOLDOWN_MS
        runCatching {
            val context = root.context
            val overlay = FrameLayout(context).apply {
                clipChildren = false
                isClickable = false
                isFocusable = false
            }
            val image = ImageView(context).apply {
                setImageBitmap(current.bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
                drawable?.setFilterBitmap(true)
                pivotX = current.bitmap.width / 2f
                pivotY = current.bitmap.height / 2f
            }
            val rect = current.rect
            val lp = FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
                leftMargin = rect.left
                topMargin = rect.top
            }
            overlay.addView(image, lp)
            root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

            val rootWidth = root.width.toFloat()
            val rootHeight = root.height.toFloat()
            val scaleFull = max(
                rootHeight / max(1, rect.height()),
                rootWidth / max(1, rect.width()),
            )
            val dx = rootWidth / 2f - (rect.left + rect.width() / 2f)
            val dy = rootHeight / 2f - (rect.top + rect.height() / 2f)
            image.scaleX = scaleFull
            image.scaleY = scaleFull
            image.translationX = dx
            image.translationY = dy
            image.alpha = 1f
            log("play: rect=$rect scale=$scaleFull duration=${XiaomiCloseSpring.FIRST_CROSS_MS}ms")

            val spring = XiaomiCloseSpring()
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = XiaomiCloseSpring.FIRST_CROSS_MS.toLong()
            }
            animator.addUpdateListener { valueAnimator ->
                val progress = spring.getInterpolation(valueAnimator.animatedFraction)
                val scale = scaleFull + (1f - scaleFull) * progress
                image.scaleX = scale
                image.scaleY = scale
                val remaining = 1f - progress
                image.translationX = dx * remaining
                image.translationY = dy * remaining
                // 接近末尾（最后 25%）：透明度从 0 渐变到 100（即 alpha 1→0 渐隐）。
                image.alpha = if (progress >= FADE_START) {
                    (remaining / (1f - FADE_START)).coerceIn(0f, 1f)
                } else {
                    1f
                }
            }
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    runCatching { root.removeView(overlay) }
                    playing = false
                    log("finished")
                }
            })
            animator.start()
        }.onFailure {
            playing = false
            HookHelper.log("$TAG: play failed", it)
        }
    }

    private fun log(message: String) {
        if (logCount.getAndIncrement() < 30) HookHelper.log("$TAG: $message")
    }

    /**
     * 官方关闭曲线（CLOSE_POS_EASE ζ=0.29 / 周期 0.95s）的解析解，
     * 播放到「首次到达目标」的时刻（约 320ms）。
     */
    private class XiaomiCloseSpring : TimeInterpolator {

        override fun getInterpolation(input: Float): Float {
            if (input >= 0.999f) return 1f
            return raw(input * FIRST_CROSS_MS / 1000f).coerceIn(0f, 1.05f)
        }

        companion object {
            val FIRST_CROSS_MS: Int = run {
                for (i in 60..3000) {
                    if (abs(1f - raw(i / 1000f)) < 0.004f) return@run i + 30
                }
                320
            }

            private fun raw(t: Float): Float {
                val x = t.toDouble()
                val wd = sqrt(0.9158999919891357) * 6.613879203796387
                return (
                    1.0 - (exp(-1.9180249 * x) *
                        (cos(wd * x) + (0.28999999165534973 / sqrt(0.9158999919891357)) * sin(wd * x)))
                    ).toFloat()
            }
        }
    }
}
