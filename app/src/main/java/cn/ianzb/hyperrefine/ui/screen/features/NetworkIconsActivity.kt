package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

class NetworkIconsActivity : BaseSubPageActivity() {
    override val titleRes = R.string.sb_title
    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        val specs = statusBarSpecs(featureSpecs())
        StatusBarSections(listOf(
            HookSection(R.string.sb_network_section, specs = specs.take(1)),
            HookSection(R.string.sb_mobile_type_section, specs = specs.slice(1..1)),
            HookSection(R.string.sb_mobile_dual_section, specs = specs.drop(2)),
        ), contentPadding)
    }
}

@Composable
private fun StatusBarSections(sections: List<HookSection>, padding: PaddingValues) {
    val scroll = LocalSubPageScrollBehavior.current
    LazyColumn(modifier = Modifier.fillMaxSize().then(
        if (scroll != null) Modifier.pageScrollModifiers(showTopAppBar = true, topAppBarScrollBehavior = scroll) else Modifier,
    ), contentPadding = padding) {
        sections.forEach { section -> item {
            HookSectionCard(section) { section.specs.forEach { spec -> HookOptionView(spec) } }
        } }
    }
}
