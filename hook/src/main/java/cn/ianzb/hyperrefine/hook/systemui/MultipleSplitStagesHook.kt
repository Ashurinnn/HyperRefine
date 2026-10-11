package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import kotlin.math.roundToInt

/**
 * 自定义多分屏（多窗口分屏）数量上限。
 *
 * 目标进程：`com.android.systemui`（`MultipleSplitOrganizer` 位于小米 WMShell）。
 *
 * 上限来源：`MultipleSplitOrganizer.MAX_STAGES = resolveMaxStages(RAM, SystemProperties.get(
 * "persist.sys.multiple.split.max_stages", "-1"))` —— 高内存默认 6、低内存 3、下限 3；
 * 官方本来就有系统属性覆盖口（需要 Root 且要在类初始化前生效）。
 *
 * 这里不改系统属性，改为两步（只调用系统自身接口）：
 * 1. 挂 `resolveMaxStages(int, String)`（静态初始化里调用）与 `getMaxStages()`，恒定返回设定值；
 * 2. 兜底直接改写静态字段 `MAX_STAGES` —— `MultipleSplitRootTaskOrganizer` 等多处是
 *    **直接读字段** 作为 stage 数组 / 循环边界，只改 getter 不够。
 */
class MultipleSplitStagesHook : BaseHook() {

    override val key: String = SplitScreenKeys.MULTIPLE_SPLIT_CUSTOM

    /** 平板 / 折叠屏专属：手机（直板）保持官方行为。 */
    override val deviceScope: Set<DeviceType> = setOf(DeviceType.PAD, DeviceType.FOLD)

    override fun init() {
        val loader = target.classLoader ?: throw HookSkippedException("no classloader")
        val cls = Reflect.findClassIfExists(ORGANIZER, loader)
            ?: throw HookSkippedException("$ORGANIZER not found")
        val stages = HookPrefs
            .getFloat(SplitScreenKeys.MULTIPLE_SPLIT_MAX_STAGES, SplitScreenKeys.DEFAULT_STAGES.toFloat())
            .roundToInt()
            .coerceIn(SplitScreenKeys.MIN_STAGES, SplitScreenKeys.MAX_STAGES)

        // 1) 类初始化前拦截解析（类已初始化时下面的字段兜底仍会生效）。
        hookConstant(cls, "resolveMaxStages", 2, stages)
        hookConstant(cls, "getMaxStages", 0, stages)
        // 2) 兜底：直接改写静态字段，覆盖「直接读字段」的调用点。
        val patched = patchStaticField(cls, stages)
        HookHelper.log("$tag: max stages=$stages, field patched=$patched")
    }

    private fun hookConstant(cls: Class<*>, name: String, params: Int, value: Int) {
        cls.declaredMethods
            .firstOrNull { it.name == name && it.parameterCount == params }
            ?.also { method ->
                method.isAccessible = true
                runCatching { HookHelper.hookBefore(method) { param -> param.setResultValue(value) } }
                    .onFailure { HookHelper.log("$tag: hook $name failed", it) }
            }
    }

    /** 改写静态 final 字段（先清掉 FINAL 修饰再写），返回是否已等于目标值。 */
    private fun patchStaticField(cls: Class<*>, value: Int): Boolean = runCatching {
        val field = cls.getDeclaredField(FIELD_MAX_STAGES)
        field.isAccessible = true
        if (field.getInt(null) == value) return@runCatching true
        val flags = java.lang.reflect.Field::class.java.getDeclaredField("accessFlags")
        flags.isAccessible = true
        flags.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
        field.setInt(null, value)
        field.getInt(null) == value
    }.getOrElse {
        HookHelper.log("$tag: patch $FIELD_MAX_STAGES failed", it)
        false
    }

    companion object {
        private const val ORGANIZER = "com.android.wm.shell.multiplesplit.MultipleSplitOrganizer"
        private const val FIELD_MAX_STAGES = "MAX_STAGES"
    }
}
