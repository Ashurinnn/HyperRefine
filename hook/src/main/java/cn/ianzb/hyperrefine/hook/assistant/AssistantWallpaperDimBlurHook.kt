package cn.ianzb.hyperrefine.hook.assistant

import android.app.Application
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.systemui.wallpaper.WallpaperDimBlurDim
import cn.ianzb.hyperrefine.hook.systemui.wallpaper.WallpaperDimKeys

/**
 * 负一屏「取壁纸做模糊」的界面跟随「深色模式自动压暗壁纸」。
 *
 * 压暗壁纸改的是壁纸图层的 alpha，只在合成阶段生效；负一屏的背景模糊读的是壁纸纹理本身，
 * 会停在压暗前的亮度。这里给负一屏进程的模糊容器补上与系统界面同浓度的压暗层，
 * 与系统界面共用「深色模式自动压暗壁纸」这一个开关。
 *
 * 目标进程：`com.miui.personalassistant`。
 */
class AssistantWallpaperDimBlurHook : BaseHook() {

    override val key: String = WallpaperDimKeys.ENABLED

    override fun init() {
        WallpaperDimBlurDim.install(currentApplication())
    }

    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private fun currentApplication(): Application? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Application
    }.getOrNull()
}
