package cn.ianzb.hyperrefine.hook.systemui

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 「正在播放的应用」判定 —— 与系统官方多应用音量面板**逐条对齐**。
 *
 * 官方实现为音质音效的 `com.miui.misound.playervolume.f.a(Context)`（系统界面与音质音效的多应用
 * 音量共用该结果）。此前本模块在系统界面侧用自定的过滤条件（只认 `USAGE_MEDIA` /
 * `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`），因此部分应用（例如只设置了
 * `AudioAttributes.setLegacyStreamType(STREAM_MUSIC)`、usage 并非 MEDIA 的播放器）在官方面板里
 * 能识别、在本模块面板里识别不到，入口 / 自动显示也随之不出现。
 *
 * 官方 `f.a(Context)` 的逻辑：
 * 1. 取 `AudioManager.getActivePlaybackConfigurations()`（为空直接返回空）；
 * 2. 跳过 `uid < 10000`（非第三方应用）；
 * 3. 用 `PackageManager.getNameForUid(uid)` 取包名后走 `f.c(pkg)`：包名为空放行、等于
 *    `com.miui.miwallpaper`（壁纸服务常驻播放配置）剔除；
 * 4. `getPlayerState() == PLAYER_STATE_STARTED(2)`；
 * 5. `getAudioAttributes().getUsage() == USAGE_MEDIA(1)` **或**
 *    `getVolumeControlStream() == STREAM_MUSIC(3)`；
 * 6. 按 uid 去重（官方用 `HashMap<uid, config>`）。
 *
 * 仅调用系统自身接口（隐藏方法走反射），不复制任何系统代码。
 *
 * 线程模型（重要）：官方判定内部会同步调用音频服务——
 * `AudioManager.getActivePlaybackConfigurations()` 走 AudioService（system_server）、
 * `AudioAttributes.getVolumeControlStream()` 走音频策略服务（audioserver）。
 * 通话等场景下这些调用可能阻塞数秒（真机 ANR 日志里单次 `getStreamTypeForAttributes` 8.6s），
 * 在系统界面主线程调用会直接导致输入分发超时 ANR，因此**探测一律在后台线程执行**，
 * 主线程只读 [snapshot]，需要更新时用 [refresh] 触发一次后台刷新（刷新完成后在主线程回调）。
 */
object AppVolumePlayback {

    /** 「正在播放的应用」快照（主线程只读）。 */
    data class Snapshot(
        val packages: List<String>,
        val timestampMs: Long,
    ) {
        val hasActive: Boolean get() = packages.isNotEmpty()
    }

    /** 快照新鲜期：期内直接命中缓存，不再触发刷新。 */
    private const val FRESH_MS = 800L

    /** 两次后台刷新的最小间隔（避免高频回调把探测打满）。 */
    private const val MIN_REFRESH_INTERVAL_MS = 300L

    /** 等待回调队列上限（探测长时间阻塞时避免无限堆积；回调语义都是「重新读快照」）。 */
    private const val MAX_PENDING_CALLBACKS = 16

    /** 官方 `f.c` 剔除的包名（壁纸服务的播放配置常驻，官方不把它当播放中的应用）。 */
    private const val WALLPAPER_PACKAGE = "com.miui.miwallpaper"

    /** 官方只统计第三方应用（`uid >= 10000`）。 */
    private const val MIN_APP_UID = 10000

    /** `AudioPlaybackConfiguration.PLAYER_STATE_STARTED`。 */
    private const val PLAYER_STATE_STARTED = 2

    /** `AudioAttributes.USAGE_MEDIA`。 */
    private const val USAGE_MEDIA = 1

    /** `AudioAttributes.getVolumeControlStream()` 返回的 `AudioManager.STREAM_MUSIC`。 */
    private const val STREAM_MUSIC = 3

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 仅主线程访问：等待本轮刷新结果的回调。 */
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    @Volatile
    private var snapshot = Snapshot(emptyList(), 0L)

    @Volatile
    private var refreshing = false

    @Volatile
    private var lastRefreshAt = 0L

    /** 最近一次后台探测结果（主线程只读，不做任何跨进程调用）。 */
    fun snapshot(): Snapshot = snapshot

    /**
     * 请求一次后台探测；快照仍然新鲜且未指定 [force] 时不触发（也不回调）。
     *
     * @param onUpdated 后台探测完成后的主线程回调（本轮确实触发了刷新时才会被调用）。
     */
    fun refresh(context: Context, force: Boolean = false, onUpdated: (() -> Unit)? = null) {
        val appContext = context.applicationContext ?: context
        val now = SystemClock.uptimeMillis()
        if (!force) {
            if (now - snapshot.timestampMs <= FRESH_MS) return
            if (refreshing || now - lastRefreshAt < MIN_REFRESH_INTERVAL_MS) return
        }
        if (onUpdated != null && pendingCallbacks.size < MAX_PENDING_CALLBACKS) pendingCallbacks.add(onUpdated)
        if (refreshing) return
        refreshing = true
        lastRefreshAt = now
        Thread {
            val packages = runCatching { detectActivePackages(appContext) }.getOrDefault(emptyList())
            mainHandler.post {
                refreshing = false
                snapshot = Snapshot(packages, SystemClock.uptimeMillis())
                val callbacks = pendingCallbacks.toList()
                pendingCallbacks.clear()
                callbacks.forEach { runCatching { it() } }
            }
        }.apply {
            isDaemon = true
            name = "HyperRefine-AppVolume"
        }.start()
    }

    /**
     * 正在播放的应用包名（按 uid 去重，顺序与系统返回一致）。**会阻塞，只能在后台线程调用。**
     *
     * 取不到包名（`getNameForUid` 返回 null）时沿用官方语义跳过该条 —— 官方面板同样以包名 / uid
     * 作为条目标识，无名条目无法映射到应用。
     */
    private fun detectActivePackages(context: Context): List<String> =
        activeConfigurations(context).mapNotNull { config ->
            clientUid(config)?.let { uid ->
                runCatching { context.packageManager.getNameForUid(uid) }.getOrNull()
            }
        }.distinct()

    /** 官方 `f.a(Context)` 的等价实现：按 uid 去重后返回播放配置列表。 */
    private fun activeConfigurations(context: Context): List<Any> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        val pm = context.packageManager
        val configs = runCatching { am.activePlaybackConfigurations }.getOrNull() ?: return emptyList()
        if (configs.isEmpty()) return emptyList()
        val byUid = LinkedHashMap<Int, Any>()
        configs.forEach { config ->
            if (config == null) return@forEach
            val uid = clientUid(config) ?: return@forEach
            if (uid < MIN_APP_UID) return@forEach
            val pkg = runCatching { pm.getNameForUid(uid) }.getOrNull()
            if (pkg == WALLPAPER_PACKAGE) return@forEach
            if (playerState(config) != PLAYER_STATE_STARTED) return@forEach
            if (!isPlayingMedia(config)) return@forEach
            byUid[uid] = config
        }
        return byUid.values.toList()
    }

    /** 与官方一致的媒体判定：usage 为媒体，或音量控制流为 `STREAM_MUSIC`。 */
    private fun isPlayingMedia(config: Any): Boolean {
        val attrs = runCatching { Reflect.callMethod(config, "getAudioAttributes") }.getOrNull() ?: return false
        val usage = runCatching { Reflect.callMethod(attrs, "getUsage") as? Int }.getOrNull()
        if (usage == USAGE_MEDIA) return true
        val stream = runCatching { Reflect.callMethod(attrs, "getVolumeControlStream") as? Int }.getOrNull()
        return stream == STREAM_MUSIC
    }

    private fun clientUid(config: Any): Int? =
        runCatching { Reflect.callMethod(config, "getClientUid") as? Int }.getOrNull()

    private fun playerState(config: Any): Int? =
        runCatching { Reflect.callMethod(config, "getPlayerState") as? Int }.getOrNull()
}
