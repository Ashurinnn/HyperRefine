package cn.ianzb.hyperrefine.hook.systemui.statusbar

object StatusBarKeys {
    const val TARGET = "com.android.systemui"
    const val WIFI_ACTIVITY = "statusbar_wifi_hide_activity"
    const val HIDE_TYPE = "statusbar_mobile_hide_type"
    val ALL = listOf(WIFI_ACTIVITY, HIDE_TYPE)

    // DORMANT: restore these only with the commented dual-SIM implementation and its UI.
    // const val DUAL_ROWS = "statusbar_mobile_dual_rows"
    // const val DUAL_STYLE = "statusbar_mobile_dual_style"
    // const val DUAL_SCALE = "statusbar_mobile_dual_scale"
    // const val DUAL_LEFT_GAP = "statusbar_mobile_dual_left_gap"
    // const val DUAL_RIGHT_GAP = "statusbar_mobile_dual_right_gap"
    // const val DUAL_OFFSET = "statusbar_mobile_dual_offset"
}
