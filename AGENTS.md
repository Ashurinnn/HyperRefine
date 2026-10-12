# AGENTS.md

本仓库为 HyperRefine（LSPosed 模块），开发时遵循 `~/.codex/skills/hyperrefine-dev` 的工程不变量与合规红线。以下为用户常驻要求：

## 真机部署（常驻授权）

- **每次会话内产出新的模块构建后，自动安装到平板，无需再次征求同意**：
  - 设备：Xiaomi Pad，adb 序列号 `b6af3237`；
  - adb：`C:\Users\93322\AppData\Local\Android\Sdk\platform-tools\adb.exe`；
  - 命令：`adb -s b6af3237 install -r D:\Code\HyperRefine\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk`；
  - 设备离线 / 未授权时：跳过并在回复中说明，不反复重试；
  - 安装完成后在回复中提醒：hook 需重启目标应用进程才会生效。
- 该条为安装动作的常驻授权；其余 adb 操作（改设备、抓日志等）仍按 skill 红线默认只读、逐次说明用途。
