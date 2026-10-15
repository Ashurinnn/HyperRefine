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
    return listOf(
        OptionSpec(key = KEY_STATUS_BAR, type = OptionType.ARROW, titleRes = R.string.sb_title),
        toggle(Keys.WIFI_ACTIVITY, R.string.sb_wifi_hide_activity),
        toggle(Keys.HIDE_TYPE, R.string.sb_mobile_hide_type),
    )
}

internal fun statusBarSpecs(specs: List<OptionSpec>) =
    Keys.ALL.map { key -> specs.first { it.key == key } }
