// DORMANT: preserved from statusbar.6 (ddb0e59); not compiled or registered.
// Restore only after artwork and device regression testing. Runtime diagnostics removed.
// package cn.ianzb.hyperrefine.hook.systemui.statusbar
//
// import kotlin.math.min
// import kotlin.math.roundToInt
//
// /** Original four-cell artwork geometry and slot ordering, independent of the data SIM. */
// internal object DualSimArtworkPolicy {
//     const val CELL_COUNT = 4
//     private const val INACTIVE_ALPHA = 0.28f
//
//     data class Levels(val top: Int, val bottom: Int)
//     data class Cell(val row: Int, val column: Int, val left: Float, val top: Float,
//         val right: Float, val bottom: Float, val radius: Float)
//
//     /** HyperOS has six native levels (0..5); round proportionally to four visible cells. */
//     fun fourCellLevel(nativeLevel: Int?): Int? = nativeLevel?.takeIf { it in 0..5 }
//         ?.let { (it * CELL_COUNT / 5f).roundToInt() }
//
//     fun levels(primarySlot: Int, secondarySlot: Int, primaryLevel: Int?, secondaryLevel: Int?): Levels? {
//         val first = fourCellLevel(primaryLevel) ?: return null
//         val second = fourCellLevel(secondaryLevel) ?: return null
//         return when {
//             primarySlot == 0 && secondarySlot == 1 -> Levels(first, second)
//             primarySlot == 1 && secondarySlot == 0 -> Levels(second, first)
//             else -> null // Unknown/duplicate slots must not invent a SIM 1 / SIM 2 assignment.
//         }
//     }
//
//     fun cells(width: Float, height: Float, thick: Boolean): List<Cell> {
//         if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return emptyList()
//         val step = width / CELL_COUNT
//         val barWidth = step * if (thick) 0.80f else 0.64f
//         // Compact proportions independent of a tall Android signal drawable's intrinsic bounds.
//         val artworkHeight = min(height * 0.72f, width * 0.80f)
//         val artworkTop = height - artworkHeight
//         val bottomHeight = min(artworkHeight * 0.24f, barWidth * 0.92f)
//         val bottomTop = height - bottomHeight
//         val upperBaseline = bottomTop - artworkHeight * 0.09f
//         val upperHeight = upperBaseline - artworkTop
//         return (0 until CELL_COUNT).map { column ->
//             val left = column * step + (step - barWidth) / 2f
//             val barHeight = upperHeight * (0.4f + column * 0.2f)
//             val radius = min(barWidth, barHeight) * 0.48f
//             Cell(0, column, left, upperBaseline - barHeight, left + barWidth, upperBaseline, radius)
//         } + (0 until CELL_COUNT).map { column ->
//             val left = column * step + (step - barWidth) / 2f
//             val radius = min(barWidth, bottomHeight) * 0.48f
//             Cell(1, column, left, bottomTop, left + barWidth, height, radius)
//         }
//     }
//
//     /** Preserve the host's RGB and alpha; inactive cells use the same hue at lower opacity. */
//     fun cellColor(foreground: Int, drawableAlpha: Int, filled: Boolean): Int {
//         val hostAlpha = foreground ushr 24
//         val strengthAlpha = if (filled) 1f else INACTIVE_ALPHA
//         val alpha = (hostAlpha * drawableAlpha.coerceIn(0, 255) / 255f * strengthAlpha).roundToInt()
//         return (foreground and 0x00ffffff) or (alpha shl 24)
//     }
// }
