// DORMANT: preserved from statusbar.6 (ddb0e59); not compiled or registered.
// Restore only after artwork and device regression testing. Runtime diagnostics removed.
// package cn.ianzb.hyperrefine.hook.systemui.statusbar
//
// import android.view.View
// import android.view.ViewGroup
// import cn.ianzb.hyperrefine.hook.xposed.Reflect
//
// /** Native status-bar lookup only; no network-type styling or view reparenting. */
// internal object StatusBarViews {
//     const val MOBILE_BINDER = "com.android.systemui.statusbar.pipeline.mobile.ui.binder.MiuiMobileIconBinder"
//
//     fun find(root: View, name: String): View? {
//         val id = root.resources.getIdentifier(name, "id", StatusBarKeys.TARGET)
//         return if (id == 0) null else root.findViewById(id)
//     }
//
//     fun isStatusBarMobile(root: ViewGroup, model: Any?): Boolean =
//         model?.javaClass?.name?.contains("ShadeCarrierGroup") != true &&
//             (runCatching { Reflect.getObjectField(root, "slot") }.getOrNull() in listOf("mobile", "demo_mobile"))
// }
