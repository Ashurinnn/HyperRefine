package cn.ianzb.hyperrefine.hook.volume

/** 播放身份与展示包名分离；volumeKey 仅用于原生音量存储 / 面板镜像。 */
data class AppVolumeTarget(
    val uid: Int,
    val packageName: String,
    val volumeKey: String = packageName,
)

/** 不依赖 Android 的筛选与身份解析规则，供两条音量写入路径共用。 */
internal object AppVolumePolicy {
    private const val MIN_APP_UID = 10_000
    private const val STARTED = 2
    private const val USAGE_MEDIA = 1
    private const val STREAM_MUSIC = 3
    private const val WALLPAPER_PACKAGE = "com.miui.miwallpaper"

    data class Player<T>(
        val configuration: T,
        val uid: Int,
        val pid: Int,
        val state: Int?,
        val usage: Int?,
        val volumeControlStream: Int?,
    )

    fun <T> activeMedia(players: List<Player<T>>): List<Player<T>> = players.filter {
        it.uid >= MIN_APP_UID && it.state == STARTED &&
            (it.usage == USAGE_MEDIA || it.volumeControlStream == STREAM_MUSIC)
    }

    /** 不信任广播中单独给出的 UID，必须确认真实包名属于该完整 UID。 */
    fun resolveTarget(
        pkg: String,
        suppliedUid: Int,
        uidForPackage: (String) -> Int?,
        packagesForUid: (Int) -> List<String>,
        nameForUid: (Int) -> String?,
    ): AppVolumeTarget? {
        val uid = if (suppliedUid == -1) uidForPackage(pkg) ?: return null else suppliedUid
        if (uid < MIN_APP_UID || pkg !in packagesForUid(uid)) return null
        return AppVolumeTarget(uid, pkg, nameForUid(uid)?.takeIf { it.isNotBlank() } ?: pkg)
    }

    /** 多包共享 UID 时优先播放进程的包，再选有入口的应用，最后稳定地选一个真实包名。 */
    fun <T> targets(
        players: List<Player<T>>,
        packagesForUid: (Int) -> List<String>,
        packagesForProcess: (Int, Int) -> List<String> = { _, _ -> emptyList() },
        hasLauncher: (String) -> Boolean = { false },
        nameForUid: (Int) -> String? = { null },
    ): List<AppVolumeTarget> = activeMedia(players).groupBy { it.uid }.mapNotNull { (uid, group) ->
        // UID 官方名称可能是 sharedUserId:appId，不能拿来替代此处的真实包名。
        val packages = packagesForUid(uid).filter { it.isNotBlank() && ':' !in it }.distinct().sorted()
        if (packages.isEmpty() || packages.all { it == WALLPAPER_PACKAGE }) return@mapNotNull null
        val candidates = packages.filter { it != WALLPAPER_PACKAGE }
        val processPackages = if (candidates.size > 1) {
            group.flatMap { packagesForProcess(uid, it.pid) }.distinct().filter { it in candidates }
        } else emptyList()
        val preferred = processPackages.ifEmpty { candidates }
        val pkg = if (preferred.size == 1) preferred.single() else preferred.firstOrNull(hasLauncher) ?: preferred.first()
        AppVolumeTarget(uid, pkg, nameForUid(uid)?.takeIf { it.isNotBlank() } ?: pkg)
    }

    /** 当前活跃的全部媒体播放器均设置；单个播放器失效不阻断同 UID 的其余播放器。 */
    fun <T> applyVolume(
        players: List<Player<T>>,
        uid: Int,
        volume: Float,
        setVolume: (T, Float) -> Unit,
        onFailure: (Throwable) -> Unit = {},
    ): Int {
        if (!volume.isFinite()) return 0
        val value = volume.coerceIn(0f, 1f)
        var applied = 0
        activeMedia(players).filter { it.uid == uid }.forEach {
            runCatching { setVolume(it.configuration, value) }
                .onSuccess { applied++ }
                .onFailure(onFailure)
        }
        return applied
    }
}
