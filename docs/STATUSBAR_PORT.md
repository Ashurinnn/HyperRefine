# 状态栏功能说明

入口：**功能 → 系统界面 → 状态栏**。当前仅开放以下两项开关，默认关闭，目标均为 `com.android.systemui`；修改后重新加载系统界面进程生效。

| 选项 | 配置键 | 效果 |
|---|---|---|
| 隐藏 Wi-Fi 网络活动指示器 | `statusbar_wifi_hide_activity` | 隐藏 Wi-Fi 的收发活动图标，保留信号与连接显示 |
| 完全隐藏网络类型 | `statusbar_mobile_hide_type` | 隐藏移动网络类型图片、独立文字和特殊 5G 标识，保留原生信号布局 |

## 实现与验证

Wi-Fi Hook 在原生绑定与活动更新之后将对应活动视图隐藏，避免后续收发更新再次显示。移动网络类型 Hook 在原生蜂窝模型创建后，将四个类型显示 Flow 替换为宿主自身的只读 false Flow；兼容宿主经过优化的一参数构造与普通二参数构造。

配置通过项目既有 `OptionSpec`、偏好与 Hook 状态机制接入；状态栏子页面继承 `BaseSubPageActivity`，登记到父页搜索，并使用 `NetworkIconsActivity`。没有新增权限、作用域或原生依赖。

接口静态核对基于提供的 HyperOS `OS4.0.22.0.XPNCNXM` ROM，SystemUI `17.03.260226.r`；其他 ROM 的私有接口兼容性尚未验证。用户已确认本次精简测试 APK 的两个开关均生效、双 SIM 保持原生布局，从状态栏页下拉通知中心/控制中心时背景显示正常。单元测试覆盖配置键、反射成员解析和两种只读 Flow 构造签名；没有新增临时诊断日志。

## 暂不启用双卡双行

`hook/.../systemui/statusbar/` 中的 `DualSimSignalHook.kt`、`DualSimRuntimeSupport.kt`、`DualSimArtworkPolicy.kt`、`StackedSignalDrawable.kt`、`StatusBarPolicy.kt`、`StatusBarViews.kt` 及对应双排测试以逐行注释保留，供后续修复参考。相关偏好键和宿主 Pair 解析辅助也已注释。

这些文件不生成运行时类；当前没有双排 Hook 注册、配置入口或搜索项，不读取该功能的旧偏好。系统继续使用原生 SIM 信号布局。以后恢复时须重新接入配置与注册，验证各卡信号强度、单双卡切换、数据卡切换、锁屏及主题/明暗表现；不能只取消某个文件的注释就视为可用。

此前电脑端导出的 PNG/SVG 不是运行时依赖，未进入仓库或 APK。当前构建没有图标解包、图片落盘或系统资源替换。

## 参考与安全边界

功能思路参考 Pengeek（米客）`OS4-26.10.13-test`，来源与许可在应用内「关于 → 第三方许可证与致谢」统一登记。实现独立编写，未复用参考模块或小米专有代码/资源；逆向材料仅保留在本地忽略的 `reverse_report/`。项目继续以 AGPL-3.0 分发。

新增 Hook 仅修改 SystemUI 进程中的视图可见性与显示 Flow，不请求 Root、不执行 shell、不挂载或重新挂载分区、不操作块设备/AVB，也不向受保护系统分区写文件。安装 APK、项目既有偏好及系统/框架日志仍可能保存普通应用数据，因此此说明不承诺整个模块或临时 Root 环境的所有组件均无写入。
