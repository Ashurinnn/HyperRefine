package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.systemui.SplitScreenKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 「窗口管理」二级页（系统界面 → 分屏与窗口）。
 *
 * - 解除分屏方向限制：竖屏也可左右、横屏也可上下
 * - 自定义多分屏数量：总开关 + 数量上限滑块（3–6）
 */
class WindowManagerActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.feature_window_manager

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui")) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val section = HookSection(
            titleRes = R.string.section_split_screen,
            specs = listOf(
                featureSpec(SplitScreenKeys.UNLOCK_DIRECTION),
                featureSpec(SplitScreenKeys.MULTIPLE_SPLIT_CUSTOM),
                featureSpec(SplitScreenKeys.MULTIPLE_SPLIT_MAX_STAGES),
                featureSpec(SplitScreenKeys.MULTIPLE_SPLIT_FREE_RATIO),
                featureSpec(SplitScreenKeys.SINGLE_SPLIT_FREE_RATIO),
                featureSpec(SplitScreenKeys.SPLIT_FREE_RATIO_GLOBAL),
                featureSpec(SplitScreenKeys.SPLIT_RATIO_MEMORY),
                featureSpec(SplitScreenKeys.SPLIT_PEEK_TRANSITION),
                featureSpec(SplitScreenKeys.CAPTION_BOTH_DIRECTIONS),
            ),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (scrollBehavior != null) {
                        Modifier.pageScrollModifiers(
                            showTopAppBar = true,
                            topAppBarScrollBehavior = scrollBehavior,
                        )
                    } else {
                        Modifier
                    }
                ),
            contentPadding = contentPadding,
        ) {
            item {
                HookSectionCard(section) {
                    section.specs.forEach { HookOptionView(it) }
                }
            }
        }
    }
}
