package cn.ianzb.hyperrefine.hook.systemui

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import cn.ianzb.hyperrefine.hook.volume.AppVolumePolicy
import cn.ianzb.hyperrefine.hook.volume.AppVolumeTarget
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 系统界面与音质音效共用的多应用音量播放身份解析 / 写入。
 *
 * 保留官方的媒体条件（uid >= 10000、STARTED、USAGE_MEDIA 或 STREAM_MUSIC）与按 UID 分组。
 * getPackagesForUid 提供真实包名；getNameForUid 只用作原生音量存储键，兼容共享 UID。
 */
object AppVolumePlayback {
    private const val TAG = "AppVolumePlayback"

    private val setPlayerVolume by lazy {
        AudioManager::class.java.getMethod(
            "setPlayerVolume", AudioPlaybackConfiguration::class.java, Float::class.javaPrimitiveType,
        )
    }

    fun hasActivePlayback(context: Context): Boolean = activeApplications(context).isNotEmpty()

    fun activePackages(context: Context): List<String> =
        activeApplications(context).map { it.packageName }.distinct()

    /** 按 UID 分组；同一个包在不同用户中运行时不会合并播放身份。 */
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

    /** 用最新播放配置设置该 UID 的全部活跃媒体播放器，不选已暂停 / 已停止的条目。 */
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
