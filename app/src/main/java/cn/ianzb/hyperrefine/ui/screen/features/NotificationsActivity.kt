package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.notification.NotificationKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers
import top.yukonga.miuix.kmp.basic.BasicComponent

class NotificationsActivity : BaseSubPageActivity() {
    override val titleRes = R.string.notifications_title
    override val topBarActions: (@Composable () -> Unit)? = {
        QuickActionsAction(listOf(NotificationKeys.SYSTEM_UI, NotificationKeys.SETTINGS, NotificationKeys.SYSTEM))
    }

    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val section = HookSection(specs = notificationSpecs(featureSpecs()))
        LazyColumn(
            modifier = Modifier.fillMaxSize().then(
                if (scrollBehavior != null) Modifier.pageScrollModifiers(
                    showTopAppBar = true,
                    topAppBarScrollBehavior = scrollBehavior,
                ) else Modifier,
            ),
            contentPadding = contentPadding,
        ) {
            item {
                HookSectionCard(section) {
                    BasicComponent(
                        title = stringResource(R.string.notification_apply_title),
                        summary = stringResource(R.string.notification_apply_summary),
                    )
                    section.specs.forEach { HookOptionView(it) }
                }
            }
        }
    }
}
