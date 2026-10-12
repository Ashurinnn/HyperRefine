package cn.ianzb.hyperrefine.hook.systemui.wallpaper

/**
 * 「深色模式自动压暗壁纸」配置键。
 *
 * 与 App 侧 `FeaturesPage` 的声明保持一致。
 */
object WallpaperDimKeys {

    /** 总开关。 */
    const val ENABLED = "wallpaper_dark_dim"

    /** 压暗程度（0–100，滑条以 Float 存储）。 */
    const val AMOUNT = "wallpaper_dark_dim_amount"

    /** 默认压暗程度（百分比）。 */
    const val DEFAULT_AMOUNT = 30f
}
