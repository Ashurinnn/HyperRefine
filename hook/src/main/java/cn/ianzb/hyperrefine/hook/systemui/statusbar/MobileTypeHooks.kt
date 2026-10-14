package cn.ianzb.hyperrefine.hook.systemui.statusbar

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.Reflect

class MobileIndicatorVisibilityHook : BaseHook() {
    override val key = StatusBarKeys.HIDE_TYPE
    override fun init() {
        val loader = target.classLoader
        val factory = Reflect.findClass("com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MiuiMobileIconVMImpl\$\$ExternalSyntheticLambda0", loader)
        val cell = Reflect.findClass("com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MiuiCellularIconVM", loader)
        val create = StatusBarMembers.methods(factory, "invoke") {
            it.parameterCount == 0 && it.returnType == Any::class.java
        }
        // Native image, separate text, and special 5G visibility have independent collectors.
        val visibility = listOf("mobileTypeVisible", "mobileTypeImageVisible", "mobileTypeSingleVisible", "showSpecial5GIcon")
            .map { StatusBarMembers.field(cell, it) }
        require(visibility.all { it.type.name == "kotlinx.coroutines.flow.ReadonlyStateFlow" })
        val flows = StatusBarFlows(loader)
        val hidden = flows.mutable(false)
        val guard = StatusBarGuard(tag)
        StatusBarMembers.install(create.map { method -> { StatusBarMembers.after(method) { param -> guard.run {
            val model = param.result ?: return@run
            require(cell.isInstance(model)) { "Unexpected cellular model" }
            visibility.forEach { flows.replace(model, it, hidden) }
        } } } })
    }
}
