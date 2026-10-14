package cn.ianzb.hyperrefine.hook.assistant

import android.os.Handler
import android.os.Looper
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * HyperViewScale（系统框架里的过渡动画引擎，`android.app.hyperviewscale.*`）相关类的定位
 * 与「延迟重试安装」助手。
 *
 * 实现类可能由独立的 ClassLoader 提供：先通过 `HyperViewScaleRemoteStub.getInstance()` 拿到
 * 实现实例、反推其 ClassLoader，再按名字解析；拿不到时退回目标应用自己的 ClassLoader
 * （框架类会经 parent 委派解析到）。进程刚启动时引擎可能尚未绑定（`getInstance()` 返回
 * null），因此解析失败时按固定节奏重试，避免「启动时序不巧整套不生效」。
 */
internal object AssistantHvsSupport {

    private val retryDelaysMs = longArrayOf(500, 1_500, 4_000, 10_000, 30_000)

    @Volatile
    private var implLoader: ClassLoader? = null

    @Volatile
    private var stubInstance: Any? = null

    /** `HyperViewScaleRemoteStub.getInstance()` 的实现实例；尚未绑定时返回 null（下次再试）。 */
    fun implInstance(appLoader: ClassLoader?): Any? {
        stubInstance?.let { return it }
        val resolved = runCatching {
            val stub = Reflect.findClassIfExists(STUB, appLoader) ?: return@runCatching null
            Reflect.callStaticMethod(stub, "getInstance")
        }.getOrNull()
        if (resolved != null) stubInstance = resolved
        return resolved
    }

    /** 解析 HyperViewScale 实现所在的 ClassLoader；尚未绑定时返回 null（下次再试）。 */
    private fun implLoader(appLoader: ClassLoader?): ClassLoader? {
        implLoader?.let { return it }
        val resolved = implInstance(appLoader)?.javaClass?.classLoader
        if (resolved != null) implLoader = resolved
        return resolved
    }

    /** 按名字解析类：优先实现所在的 ClassLoader，其次目标应用的 ClassLoader。 */
    fun findClass(name: String, appLoader: ClassLoader?): Class<*>? {
        val impl = implLoader(appLoader)
        if (impl != null) {
            Reflect.findClassIfExists(name, impl)?.let { return it }
        }
        return appLoader?.let { Reflect.findClassIfExists(name, it) }
    }

    /**
     * 解析 HyperViewScale「管理器」实现类（`setMaskSurface` 等方法的宿主）。
     * 优先使用 Stub 实例的真实类；未绑定时退回按名字解析。
     */
    fun implClass(appLoader: ClassLoader?): Class<*>? =
        implInstance(appLoader)?.javaClass ?: findClass(HVS_REMOTE_MANAGER, appLoader)

    /**
     * 立即执行一次 [install]；未成功（目标类尚未就绪等）则在主线程按节奏重试，
     * 全部失败后放弃并记录。[install] 返回 true 表示安装动作已完成、不再重试。
     */
    fun installWithRetry(tag: String, install: () -> Boolean) {
        if (runCatching(install).getOrDefault(false)) return
        val handler = Handler(Looper.getMainLooper())
        scheduleRetry(tag, handler, install, 0)
    }

    private fun scheduleRetry(tag: String, handler: Handler, install: () -> Boolean, index: Int) {
        if (index >= retryDelaysMs.size) {
            HookHelper.log("$tag: target classes not found after ${retryDelaysMs.size} retries, give up")
            return
        }
        handler.postDelayed({
            if (runCatching(install).getOrDefault(false)) {
                HookHelper.log("$tag: installed after ${index + 1} retries")
            } else {
                scheduleRetry(tag, handler, install, index + 1)
            }
        }, retryDelaysMs[index])
    }

    private const val STUB = "android.app.hyperviewscale.HyperViewScaleRemoteStub"
    private const val HVS_REMOTE_MANAGER = "android.app.hyperviewscale.HyperViewScaleRemoteManager"
}
