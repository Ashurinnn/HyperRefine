package cn.ianzb.hyperrefine.hook.assistant

/**
 * 负一屏（智能助理，`com.miui.personalassistant`）过渡动画优化功能的配置键。
 *
 * 功能思路参考 HyperAssistant（未附带开源许可证，按 `docs/WORKFLOW.md` §2.3 仅借鉴思路与
 * Hook 位置，本项目为独立实现、未复用其代码 / 资源；致谢见应用内「第三方许可证与致谢」页）。
 *
 * Hook 侧 [cn.ianzb.hyperrefine.hook.base.BaseHook.key] 与 App 侧 `OptionSpec.key` 共用本常量。
 */
object AssistantKeys {

    /** 负一屏宿主应用 / 进程。 */
    const val TARGET_PACKAGE = "com.miui.personalassistant"

    /** system_server 伪包名（见 `PackageTarget`）。 */
    const val TARGET_SYSTEM = "system"

    /** 闪黑修复：过渡遮罩起始 alpha 归零。 */
    const val MASK_ALPHA_ZERO = "assistant_mask_alpha_zero"

    /** 上滑返回的合并动画强制反向。 */
    const val FORCE_REVERSE_MERGE = "assistant_force_reverse_merge"

    /** 过渡遮罩重绘：把掩码 surface 的内容重画成源视图快照。 */
    const val MASK_BUFFER = "assistant_mask_buffer"

    /** 打开应用动画：从微件 / 图标位置缩放展开。 */
    const val OPEN_ANIM = "assistant_open_anim"

    /** system_server 侧放行负一屏的远程动画权限。 */
    const val REMOTE_ANIM_PERMISSION = "assistant_remote_anim_permission"
}
