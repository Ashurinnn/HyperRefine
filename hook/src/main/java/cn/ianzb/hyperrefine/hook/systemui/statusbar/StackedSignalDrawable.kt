// DORMANT: preserved from statusbar.6 (ddb0e59); not compiled or registered.
// Restore only after artwork and device regression testing. Runtime diagnostics removed.
// package cn.ianzb.hyperrefine.hook.systemui.statusbar
//
// import android.content.res.ColorStateList
// import android.graphics.Canvas
// import android.graphics.ColorFilter
// import android.graphics.Paint
// import android.graphics.PixelFormat
// import android.graphics.drawable.Drawable
//
// /** Four independently drawn cells per SIM; SystemUI supplies the current light/dark tint. */
// internal class StackedSignalDrawable(
//     private val nativeSizing: Drawable,
//     private val topLevel: Int,
//     private val bottomLevel: Int,
//     private val style: String,
//     private val baseColor: Int,
// ) : Drawable() {
//     private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
//     private var tint: ColorStateList? = null
//     private var foreground = baseColor
//     private var opacity = 255
//     private var geometry: List<DualSimArtworkPolicy.Cell> = emptyList()
//     private var geometryWidth = -1
//     private var geometryHeight = -1
//
//     override fun draw(canvas: Canvas) {
//         if (bounds.width() != geometryWidth || bounds.height() != geometryHeight) {
//             geometryWidth = bounds.width(); geometryHeight = bounds.height()
//             geometry = DualSimArtworkPolicy.cells(geometryWidth.toFloat(), geometryHeight.toFloat(), style == "thick")
//         }
//         geometry.forEach { cell ->
//             val level = if (cell.row == 0) topLevel else bottomLevel
//             paint.color = DualSimArtworkPolicy.cellColor(foreground, opacity, cell.column < level)
//             canvas.drawRoundRect(bounds.left + cell.left, bounds.top + cell.top,
//                 bounds.left + cell.right, bounds.top + cell.bottom, cell.radius, cell.radius, paint)
//         }
//     }
//
//     override fun setAlpha(alpha: Int) {
//         opacity = alpha.coerceIn(0, 255); nativeSizing.alpha = opacity; invalidateSelf()
//     }
//     override fun getAlpha() = opacity
//     override fun setColorFilter(filter: ColorFilter?) {
//         paint.colorFilter = filter; nativeSizing.colorFilter = filter; invalidateSelf()
//     }
//     override fun setTintList(tint: ColorStateList?) {
//         this.tint = tint
//         nativeSizing.setTintList(tint) // Keep the private native clone ready for single-SIM restoration.
//         foreground = tint?.getColorForState(state, tint.defaultColor) ?: baseColor
//         invalidateSelf()
//     }
//     override fun isStateful() = tint?.isStateful == true || nativeSizing.isStateful
//     override fun onStateChange(state: IntArray): Boolean {
//         val next = tint?.let { it.getColorForState(state, it.defaultColor) } ?: baseColor
//         val nativeChanged = nativeSizing.setState(state)
//         if (foreground == next) return nativeChanged
//         foreground = next; invalidateSelf(); return true
//     }
//     @Deprecated("Drawable opacity is retained for Android compatibility")
//     override fun getOpacity() = PixelFormat.TRANSLUCENT
//     override fun getIntrinsicWidth() = nativeSizing.intrinsicWidth
//     override fun getIntrinsicHeight() = nativeSizing.intrinsicHeight
// }
