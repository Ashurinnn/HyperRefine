package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.assistant.AssistantKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 「负一屏」（`com.miui.personalassistant`）二级页。
 *
 * - 过渡动画：闪黑修复（遮罩起始 alpha 归零）、上滑合并强制反向；
 * - 系统服务：放行远程动画权限（需重启设备生效）。
 */
class AssistantActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.feature_assistant

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf(AssistantKeys.TARGET_PACKAGE)) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val animSection = HookSection(
            titleRes = R.string.assistant_section_anim,
            specs = listOf(
                featureSpec(AssistantKeys.MASK_ALPHA_ZERO),
                featureSpec(AssistantKeys.FORCE_REVERSE_MERGE),
            ),
        )
        val serverSection = HookSection(
            titleRes = R.string.assistant_section_server,
            specs = listOf(
                featureSpec(AssistantKeys.REMOTE_ANIM_PERMISSION),
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
            listOf(animSection, serverSection).forEach { section ->
                item {
                    HookSectionCard(section) {
                        section.specs.forEach { HookOptionView(it) }
                    }
                }
            }
        }
    }
}
