# Global VoIP A2DP Router

[English](README_EN.md) · [安装指南](docs/installation.md) · [架构](docs/architecture.md) · [问题排查](docs/troubleshooting.md)

[![CI](https://github.com/silverpoetry/GlobalVoipA2dp/actions/workflows/ci.yml/badge.svg)](https://github.com/silverpoetry/GlobalVoipA2dp/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/silverpoetry/GlobalVoipA2dp?display_name=tag)](https://github.com/silverpoetry/GlobalVoipA2dp/releases)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Global VoIP A2DP Router 是一个只注入 Android `system_server` 的 Modern LSPosed 模块。
经典 A2DP 耳机已经连接时，它可以把普通第三方应用申请的 Bluetooth SCO / BLE Headset
通信输出改到 A2DP，并让会议期间的其他媒体继续使用高带宽媒体链路。

模块不注入任何 VoIP 应用，不包含应用包名白名单，也不修改应用代码。设置界面通过 LSPosed
Remote Preferences 工作，不申请 root、不执行 shell，也不创建自定义系统属性。

> [!WARNING]
> 本模块依赖 root、LSPosed 和 Android 私有音频框架接口，并在 `system_server` 中运行。
> 不兼容的系统实现可能导致无声、音量异常或系统进程重启。启用前必须准备可用的恢复路径；
> 系统更新后应先关闭模块并重新验证。

## 功能

- 只改写 `TYPE_BLUETOOTH_SCO` 和 `TYPE_BLE_HEADSET` 输出请求；
- 扬声器、听筒、有线、USB 和其他设备请求原样交给 Android；
- 真实电话、特权组件和 `MODE_IN_CALL` 始终旁路；
- 避免建立 HFP/SCO 和挂起 A2DP，录音由系统选择可用的非 SCO 输入；
- 将活动 VoIP 的音量控制源映射到媒体音量，并在策略退出时恢复原索引；
- 为等待 SCO 状态事件的应用发送包定向兼容通知；
- 提供 Material 3 设置页，开关变化通过 Remote Preferences 实时送达模块。

## 工作方式

```text
第三方应用请求 SCO / BLE Headset
        │
        ▼
AudioService 完成公开参数校验
        │
        ▼
AudioDeviceBroker 统一路由入口
        │  将目标设备改为当前真实 A2DP
        ▼
原生 communication route client
        │  保留原 Binder token、UID 与生命周期
        ▼
AudioFlinger 混音与重采样
        │
        ▼
A2DP 编码与蓝牙输出
```

应用提交的是普通 PCM；`VOICE_COMMUNICATION` / `VOICE_CALL` 是音频策略标签，不是 SCO
传输格式。系统混音器会把轨道转换为最终 A2DP 输出所需的采样率、声道和编码。

## 系统要求

- Android 16；
- Modern LSPosed API 102；
- 已连接且可正常播放媒体的经典 A2DP 耳机；
- 能够在异常时禁用 LSPosed 模块的恢复环境。

当前实现使用 Android 16 的私有 `AudioService` / `AudioDeviceBroker` 成员。不同系统版本或
厂商实现可能改变这些签名；不满足签名时模块会原子回滚已安装钩子并保持原系统行为。

## 安装

1. 从 [Releases](https://github.com/silverpoetry/GlobalVoipA2dp/releases) 下载 APK 和同名
   `.sha256` 文件；
2. 校验 SHA-256；
3. 安装 APK，在 LSPosed 中启用模块；静态作用域应只有 System Framework / `system`，
   不要选择任何应用；
4. 重启设备，让 `system_server` 加载模块；
5. 打开“VoIP A2DP”，确认开关为开启状态，再依次验证普通媒体、VoIP、音量键和真实电话。

从本地 debug 包迁移到公开 Release 时，Android 可能因签名不同拒绝覆盖安装。请先在 LSPosed
中禁用旧包并重启，然后卸载旧包、安装 Release、重新启用并再重启一次。完整步骤见
[安装指南](docs/installation.md)。

## 设置开关

设置应用和 `system_server` 使用同一组 LSPosed Remote Preferences。关闭开关后，新的 SCO /
BLE Headset 请求立即按原系统逻辑处理，不需要重启。

模块不会从设置页异步强拆已经提交的当前路由，以免声音突然切到扬声器。要让正在进行的会话
采用新状态，请在应用中重新选择一次输出设备，或退出后重新进入会话。

## 已知限制

- A2DP 延迟通常高于 SCO，通话或视频可能出现额外延迟；
- 经典 A2DP 没有配对的麦克风输入，录音通常回落到设备内置麦克风，但同时连接有线、USB 或
  其他输入时必须以实际 AudioPolicy 路由为准；
- 被动断开 A2DP 不保证立即清理这个非常规 communication route client，必要时退出并重新进入
  会话；
- 某些只轮询 SCO 查询、不监听状态事件的应用可能仍显示蓝牙连接失败；
- 应用可以通过公开音频 API 观察最终 A2DP 路由，本模块不是隐藏或反检测工具；
- 媒体音量是控制源，但通信轨道仍使用 VOICE_CALL 增益曲线，零音量和步进不保证与普通音乐
  完全相同。

## 隐私与安全

- 不声明 `INTERNET`、录音、存储、账号或位置权限；
- 不包含分析、遥测、广告或崩溃上报 SDK；
- 不读取、保存或转发音频内容；
- Remote Preferences 由 LSPosed 本地保存，不暴露为系统属性；
- 模块仍是一个已安装包，其他应用可能按 Android 权限规则枚举其包名。

详见 [隐私说明](PRIVACY.md) 和 [安全策略](SECURITY.md)。

## 构建

需要 JDK 17 和 Android SDK Platform 37：

```powershell
.\gradlew.bat --no-daemon clean testDebugUnitTest lintRelease assembleRelease
```

没有提供 Release 签名环境变量时，Gradle 生成未签名 APK。标签发布工作流使用仓库 Secrets
签名、验证 APK，并同时生成 SHA-256 文件。详见[发布签名](docs/release-signing.md)。

## 文档

- [安装、升级与卸载](docs/installation.md)
- [架构与安全边界](docs/architecture.md)
- [问题排查与日志采集](docs/troubleshooting.md)
- [发布签名](docs/release-signing.md)
- [贡献指南](CONTRIBUTING.md)

## 许可证

项目以 [Apache License 2.0](LICENSE) 发布。第三方依赖及其许可证见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
