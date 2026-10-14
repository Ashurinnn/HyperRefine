package cn.ianzb.hyperrefine.hook.systemui.statusbar

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.Reflect

// Presentation-only override verified against HyperOS 4.0.22.0's native Wi-Fi binder.
class HideWifiActivityHook : BaseHook() {
    override val key = StatusBarKeys.WIFI_ACTIVITY
    override fun init() {
        val loader = target.classLoader
        val name = "com.android.systemui.statusbar.pipeline.wifi.ui.binder.MiuiWifiViewBinder"
        val binder = Reflect.findClass(name, loader)
        val binding = Reflect.findClass(name + "\$bind\$2", loader)
        val bindingActivity = StatusBarMembers.field(binding, "\$wifiActivity")
        require(ImageView::class.java.isAssignableFrom(bindingActivity.type))
        val bind = StatusBarMembers.methods(binder, "bind") {
            it.parameterCount == 2 && it.parameterTypes[0] == ViewGroup::class.java &&
                it.parameterTypes[1].name == "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel" &&
                binding.isAssignableFrom(it.returnType)
        }
        val collector = Reflect.findClass(name + "\$bind\$1\$1\$4\$1", loader)
        val collectorActivity = StatusBarMembers.field(collector, "\$wifiActivity")
        require(ImageView::class.java.isAssignableFrom(collectorActivity.type))
        val update = StatusBarMembers.methods(collector, "invokeSuspend") {
            it.parameterTypes.contentEquals(arrayOf(Any::class.java)) && it.returnType == Any::class.java
        }
        val guard = StatusBarGuard(tag)
        val installs = bind.map { method -> { StatusBarMembers.after(method) { param -> guard.run {
            param.result?.let { (bindingActivity.get(it) as ImageView).visibility = View.GONE }
        } } } } + update.map { method -> { StatusBarMembers.after(method) { param -> guard.run {
            // Native activity updates force this view visible even for the no-traffic drawable.
            param.thisObject?.let { (collectorActivity.get(it) as ImageView).visibility = View.GONE }
        } } } }
        StatusBarMembers.install(installs)
    }
}
