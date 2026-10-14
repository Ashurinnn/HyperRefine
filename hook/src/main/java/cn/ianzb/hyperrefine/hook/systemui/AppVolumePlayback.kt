package cn.ianzb.hyperrefine.hook.systemui

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.ianzb.hyperrefine.hook.volume.AppVolumePolicy
import cn.ianzb.hyperrefine.hook.volume.AppVolumeTarget
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 系统界面与音质音效共用的多应用音量「播放身份」解析 / 写入。
 *
 * 判定条件与官方 `com.miui.misound.playervolume.f.a(Context)` 逐条对齐：
 * `uid ≥ 10000`、剔除常驻播放配置的 `com.miui.miwallpaper`、
 * `getPlayerState() == PLAYER_STATE_STARTED(2)`、
 * `usage == USAGE_MEDIA(1)` **或** `getVolumeControlStream() == STREAM_MUSIC(3)`、按 uid 去重。
 * 与官方不同的是：用 `getPackagesForUid` 解析**真实包名**（共享 UID 下 `getNameForUid` 可能是
 * `sharedUserId:appId` 之类的名称），`getNameForUid` 只用作原生音量存储键，
 * 从而兼容多包共享 UID 的应用。
 *
 * 仅调用系统自身接口（隐藏方法走反射），不复制任何系统代码。
 *
 * 线程模型（重要）：官方判定内部会同步调用音频服务——
 * `AudioManager.getActivePlaybackConfigurations()` 走 AudioService（system_server）、
 * `AudioAttributes.getVolumeControlStream()` 走音频策略服务（audioserver）。
 * 通话等场景下这些调用可能阻塞数秒（真机 ANR 日志里单次 `getStreamTypeForAttributes` 8.6s），
 * 在系统界面主线程调用会直接导致输入分发超时 ANR，因此**探测与写入一律在后台线程执行**；
 * 主线程只读 [snapshot]，需要更新时用 [refresh] 触发一次后台刷新（刷新完成后在主线程回调）。
 */
object AppVolumePlayback {
    private const val TAG = "AppVolumePlayback"

    /** 主线程只读的播放快照：共享 UID 感知的播放身份列表。 */
    data class Snapshot(
        val targets: List<AppVolumeTarget>,
        val timestampMs: Long,
    ) {
        val hasActive: Boolean get() = targets.isNotEmpty()

        /** 正在播放的真实包名（按 uid 去重）。 */
        val packages: List<String> get() = targets.map { it.packageName }
    }

    /** 快照新鲜期：期内直接命中缓存，不再触发刷新。 */
    private const val FRESH_MS = 800L

    /** 两次后台刷新的最小间隔（避免高频回调把探测打满）。 */
    private const val MIN_REFRESH_INTERVAL_MS = 300L

    /** 等待回调队列上限（探测长时间阻塞时避免无限堆积；回调语义都是「重新读快照」）。 */
    private const val MAX_PENDING_CALLBACKS = 16

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 仅主线程访问：等待本轮刷新结果的回调。 */
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    @Volatile
    private var snapshot = Snapshot(emptyList(), 0L)

    @Volatile
    private var refreshing = false

    @Volatile
    private var lastRefreshAt = 0L

    private val setPlayerVolume by lazy {
        AudioManager::class.java.getMethod(
            "setPlayerVolume", AudioPlaybackConfiguration::class.java, Float::class.javaPrimitiveType,
        )
    }

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
            val targets = runCatching { detectActiveTargets(appContext) }.getOrDefault(emptyList())
            mainHandler.post {
                refreshing = false
                snapshot = Snapshot(targets, SystemClock.uptimeMillis())
                val callbacks = pendingCallbacks.toList()
                pendingCallbacks.clear()
                callbacks.forEach { runCatching { it() } }
            }
        }.apply {
            isDaemon = true
            name = "HyperRefine-AppVolume"
        }.start()
    }

    /** 后台探测（只能在后台线程调用）：共享 UID 感知的播放身份列表。 */
    private fun detectActiveTargets(context: Context): List<AppVolumeTarget> = activeApplications(context)

    /** 正在播放的应用包名（按 uid 去重）。**会阻塞，只能在后台线程调用。** */
    fun activePackages(context: Context): List<String> =
        activeApplications(context).map { it.packageName }.distinct()

    /**
     * 正在播放的播放身份（按 UID 分组；多包共享 UID 时优先播放进程的包、再选有入口的应用）。
     * **会阻塞（音频服务 / 包管理 IPC），只能在后台线程调用。**
     */
    fun activeApplications(context: Context): List<AppVolumeTarget> {
        val pm = context.packageManager
        // 只有多包共享 UID 才需查询进程；系统限制可见性时退回入口 / 稳定包名选择。
        val processes by lazy {
            runCatching {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.runningAppProcesses
            }.getOrNull().orEmpty()
        }
        return AppVolumePolicy.targets(
            players = players(context),
            packagesForUid = { uid -> runCatching { pm.getPackagesForUid(uid)?.toList() }.getOrNull().orEmpty() },
            packagesForProcess = { uid, pid ->
                val process = processes.firstOrNull { it.uid == uid && it.pid == pid }
                listOfNotNull(process?.processName?.substringBefore(':')) + process?.pkgList.orEmpty()
            },
            hasLauncher = { pkg -> runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false) },
            nameForUid = { uid -> runCatching { pm.getNameForUid(uid) }.getOrNull() },
        )
    }

    /** 广播的 UID 与包名必须对应；缺少 UID 时兼容旧进程发送的包名广播。 */
    fun targetForPackage(context: Context, pkg: String, suppliedUid: Int = -1): AppVolumeTarget? {
        val pm = context.packageManager
        return AppVolumePolicy.resolveTarget(pkg, suppliedUid,
            uidForPackage = { runCatching { pm.getPackageUid(it, 0) }.getOrNull() },
            packagesForUid = { runCatching { pm.getPackagesForUid(it)?.toList() }.getOrNull().orEmpty() },
            nameForUid = { runCatching { pm.getNameForUid(it) }.getOrNull() },
        )
    }

    /**
     * 用最新播放配置设置该 UID 的全部活跃媒体播放器，不选已暂停 / 已停止的条目。
     * **会阻塞（跨进程调用音频服务），只能在后台线程调用**；返回成功生效的播放器条数。
     */
    fun applyVolume(context: Context, target: AppVolumeTarget, volume: Float): Int {
        if (!volume.isFinite() || targetForPackage(context, target.packageName, target.uid) == null) return 0
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        return runCatching {
            val setter = setPlayerVolume
            AppVolumePolicy.applyVolume(players(context), target.uid, volume,
                setVolume = { config, value -> setter.invoke(am, config, value) },
                onFailure = { HookHelper.log("$TAG: setPlayerVolume failed uid=${target.uid}", it) },
            )
        }.onFailure { HookHelper.log("$TAG: volume API unavailable", it) }.getOrDefault(0)
    }

    private fun players(context: Context): List<AppVolumePolicy.Player<AudioPlaybackConfiguration>> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        val configs = runCatching { am.activePlaybackConfigurations }.getOrNull() ?: return emptyList()
        return configs.mapNotNull { config ->
            val uid = runCatching { Reflect.callMethod(config, "getClientUid") as? Int }.getOrNull()
                ?: return@mapNotNull null
            val pid = runCatching { Reflect.callMethod(config, "getClientPid") as? Int }.getOrNull() ?: -1
            val state = runCatching { Reflect.callMethod(config, "getPlayerState") as? Int }.getOrNull()
            val attrs = runCatching { Reflect.callMethod(config, "getAudioAttributes") }.getOrNull()
            val usage = attrs?.let { runCatching { Reflect.callMethod(it, "getUsage") as? Int }.getOrNull() }
            val stream = if (usage == 1) null else attrs?.let {
                runCatching { Reflect.callMethod(it, "getVolumeControlStream") as? Int }.getOrNull()
            }
            AppVolumePolicy.Player(config, uid, pid, state, usage, stream)
        }
    }
}
