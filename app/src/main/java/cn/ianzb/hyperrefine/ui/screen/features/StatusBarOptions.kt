package cn.ianzb.hyperrefine.ui.screen.features

import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarKeys as Keys
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.prefs.OptionType

const val KEY_STATUS_BAR = "feature_statusbar"

internal fun statusBarFeatureSpecs(): List<OptionSpec> {
    fun toggle(key: String, title: Int) = OptionSpec(
        key = key, type = OptionType.SWITCH, titleRes = title,
        defaultBoolean = false, targetPackages = listOf(Keys.TARGET), showStatus = true,
    )
    fun slider(key: String, title: Int, value: Float, min: Float, max: Float, step: Float = 0.5f) = OptionSpec(
        key = key, type = OptionType.SLIDER, titleRes = title, defaultFloat = value,
        sliderMin = min, sliderMax = max, sliderStep = step, sliderDecimals = 1,
        sliderUnitRes = if (key == Keys.DUAL_SCALE) R.string.sb_unit_scale else R.string.sb_unit_dp,
        targetPackages = listOf(Keys.TARGET), dependsOn = Keys.DUAL_ROWS,
    )
    return listOf(
        OptionSpec(key = KEY_STATUS_BAR, type = OptionType.ARROW, titleRes = R.string.sb_title),
        toggle(Keys.WIFI_ACTIVITY, R.string.sb_wifi_hide_activity),
        toggle(Keys.HIDE_TYPE, R.string.sb_mobile_hide_type),
        OptionSpec(key = Keys.DUAL_ROWS, type = OptionType.SWITCH, titleRes = R.string.sb_dual_rows,
            summaryRes = R.string.sb_dual_rows_summary, defaultBoolean = false,
            targetPackages = listOf(Keys.TARGET), showStatus = true),
        slider(Keys.DUAL_SCALE, R.string.sb_icon_scale, 1f, 0.6f, 1.6f, 0.1f),
        slider(Keys.DUAL_LEFT_GAP, R.string.sb_left_gap, 0f, 0f, 8f),
        slider(Keys.DUAL_RIGHT_GAP, R.string.sb_right_gap, 0f, 0f, 8f),
        slider(Keys.DUAL_OFFSET, R.string.sb_vertical_offset, 0f, -4f, 4f),
    )
}

internal fun statusBarSpecs(specs: List<OptionSpec>) =
    Keys.ALL.map { key -> specs.first { it.key == key } }
