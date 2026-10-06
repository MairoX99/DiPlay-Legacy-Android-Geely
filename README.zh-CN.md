# DiPlay — 吉利 E01

为**吉利星瑞 2021 款 E01** 车机提供的 CarPlay —— 亿咖通 ECARX E01（联发科 MT6735）平台，
运行 GKUI 19，系统为 **Android 5.1（API 22）**。

本仓库 fork 自 [programmerguohuajing/DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android)，
后者又 fork 自 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)。
**这里记录的全部是吉利专属改动，或从上游移植的品牌无关改动。**
上游的比亚迪专属功能不在本分支范围内，本文不再复述。

**版本：** `0.2.9.1-geely-rc`（versionCode 29）· 包名 `com.shihab.diplay`

## 已在实车验证

| | |
|---|---|
| **方向盘上一曲 / 下一曲** | ✅ **实车可切歌**。日志：`DiPlay-MediaKeys: media key -> CarPlay 4/5 sent=true` |
| **星瑞方向盘没有播放/暂停键** | 那个位置是车机自己的**飞屏键**。它只经厂商 AIDL 送到 `PopView`，**从不进入 Android 按键管线**，所以任何应用都够不着。 |

方向盘是这里最难的一块，而**真正生效的修法不是上游那套**。这台车机的方向盘按键走车机自己的输入服务，
服务再把每次按压**重新发成标准的 `ACTION_MEDIA_BUTTON`**。而 DiPlay 的媒体会话当时是
`flags=0`、且没有 media-button receiver，于是框架把按键交给了先前占着方向盘的那个应用。
补上 `FLAG_HANDLES_MEDIA_BUTTONS` 与一个 media-button receiver 之后就通了 ——
`dumpsys media_session` 由 `flags=0 / mediaButtonReceiver=null` 变为 `flags=3 / PendingIntent{...}`。

上游做的**通用无障碍过滤**和**ECARX 厂商键码**两条路**仍留在代码里，作为其他车机的兜底**。
这台车上两条都没用上：GKUI 19 在星瑞上不暴露「无障碍」设置入口，不开 ADB 根本打不开那条路。

**尚未在实车验证**：USB 权限自动确认、无文件选择器车机的诊断导出回退、无线启动诊断。

## 本分支做了什么

| | |
|---|---|
| **吉利方向盘按键** | 见上。媒体会话 flags + receiver；无障碍过滤与 ECARX 键码作为兜底保留。 |
| **车机热点接管** | 改为 DiPlay 自己通过 `setWifiApEnabled` 打开车机热点，并**保留车机原有热点配置**，不再提示用户去车机设置里手动开。只有在重试时才释放 Wi-Fi 站点模式 —— 那会断开车机当前所连的网络。失败原因会区分：固件不给权限、车机拒绝、热点迟迟不上报。 |
| **无线启动诊断** | 无线连接卡住时，报告会指出**卡在哪一步**，并附接口状态、Bonjour 发现计数、内核收包 / UDP 计数。**纯观察** —— 不改变连接超时、地址选择或重试行为。 |
| **USB attach 过滤器** | 补上 Apple 厂商 ID，插上 iPhone 冷启动不再拉不起 DiPlay。 |
| **USB 权限自动确认** | 免去系统的 USB 授权弹窗。**只替系统弹窗作答**，不替其他应用作答。 |
| **USBMUX 分帧** | 容忍控制回复后的 4 字节填充，此前会以协议错误中断连接。 |
| **音频** | UDP 接收缓冲扩到 512 KB，并补卡顿诊断时间线（最大到达间隔、序列缺口）。 |
| **触摸延迟** | 触摸事件通道关闭 Nagle 算法，手势不再成批延迟到达。 |
| **诊断导出** | 无文件选择器的车机上三级回退，并支持屏幕内查看报告、长按复制。 |
| **Android 5 设置页** | 设置项开关不再绘制 on/off 文字（Android 5 上排布不正确）。 |
| **有线连接崩溃** | 只要配置了手动热点模式，运行时校验就要求填 SSID —— 有线也不例外，导致有线连接中断。现改为仅无线模式校验。 |
| **比亚迪代码加门禁** | SOME/IP HUD 桥接在绑定前先检查网关注册包是否安装，吉利车机不再每 300 ms 重试一个永远够不到的服务。 |

## 安装

请安装在**车机**上，不是 iPhone。无需越狱或转接盒。

⚠️ **本 APK 与上游 APK 签名不同，无法覆盖安装。** 需先卸载：

```sh
adb uninstall com.shihab.diplay
adb install -r DiPlay-Legacy-Geely-V0291.apk
```

卸载会丢失应用内设置（分辨率、音频缓冲、方向盘按键角色、已存 Wi-Fi 凭据）。
没有 ADB 时，把 APK 拷进 U 盘，用车机文件管理器安装（需允许「未知来源」）。

车机必须允许安装 APK。

## 平台说明

参考车是 **Android 5.1 / API 22**，不是 4.4 —— 这是**实测**结论而非推测
（`ro.build.version.release=5.1`、`ro.build.version.sdk=22`）。本分支保留 **API 19 底线**
以便仍能编给更老的车机，但**目标是 5.1**，每一处移植都要按两者分别过一遍。

E01 内存极度紧张：`MemFree` 常态只有 **26–33 MB**，`AnonPages` 已占约 1.05 GB。
**该不该移植一项功能，取决于它要多少常驻内存**，而不只是 API 级别够不够。

## 上游对齐程度

本分支代码基线是上游 DiPlay **v0.2.7**。上游此后已到 **v0.2.12 及未发布内容**，
共 **464 个 commit**，分属六个功能组。对齐是**分阶段推进的工程，不是合并**：
两边没有共同历史，且上游面向 Android 9（minSdk 28）、本分支保留 API 19 底线，
每处移植都要额外过一遍低版本可行性。

| 功能组 | 状态 |
|---|---|
| **方向盘按键** | **已完成，实车验证**。见上。上游在同一服务上做的仪表地图缩放与摇杆，驱动的是本车没有的比亚迪仪表。 |
| **无线连接** | **部分对齐**。车机热点接管、无线启动诊断已就位。Same LAN（现有 Wi-Fi）尚未移植，但在本车**可行**。Wi-Fi Direct 组恢复与首选信道**不列入计划**：上游标了 `@RequiresApi(Q)`（API 29），本车是 API 22。 |
| **有线 / USB** | 部分对齐 —— attach 过滤、USBMUX 分帧、权限自动确认。 |
| **协议与音频** | 部分对齐 —— UDP 接收缓冲与卡顿诊断。缓冲音频**不列入计划**（见下）。 |
| **界面与设置** | 未开始。 |
| **稳定性修复** | 未开始。 |

明确不做：

- **比亚迪硬件** —— DiLink 3/4/5 仪表集群投影、BYD HUD 导航、CAN 电池上报。本车无对应硬件。
  代码留在树里，是为了让上游同步保持低成本。
- **缓冲音频**（`BufferedAudioStream`）—— 一个大子系统，**本质就是拿内存换流畅**。
  在只剩 26–33 MB 的车机上这是笔亏本买卖，且上游默认就没开。
- **Android 多窗口 / 分屏** —— GKUI 没有分屏，本车 DiPlay 永远全屏。
  （CarPlay 的 **view area** 是 iAP2 协议层能力、本身可用，只是车机从不让 DiPlay 拿到更小的窗口。）

## 文档

- [更新日志](CHANGELOG.md)
- [从源码构建](docs/BUILD.md)
- [安装与连接](docs/INSTALL.md)
- [兼容性与疑难排查](docs/COMPATIBILITY.md)
- [隐私与诊断报告](docs/PRIVACY.md)
- [验证](docs/VALIDATION.md)
- [致谢与许可证](docs/THIRD_PARTY_NOTICES.md)

## 来源与致谢

基于 [xcertplay](https://github.com/shilapi/xcertplay)，GPL-3.0。主页/设置界面与网站改编自
[DiAuto](https://github.com/shihabal3amri/DiAuto)，AGPL-3.0；该许可证收录于 `docs/licenses`。
分发修改版时请保留这些声明。CarPlay 及其图标归 Apple Inc. 所有；本项目与 Apple、吉利、亿咖通
**无任何关联或背书关系**。

本仓库自一份干净的公开源码快照开始。本地研究资料、测试者报告与发布签名密钥均不包含在内。
每次发布都附带与 APK 对应的完整源码；实验性的运行时身份资产在构建说明与声明中单独描述。

本产品**未经 Apple 认证**。APK 内含一份从公开 Carlinkit 固件中提取的**实验性配件身份**，
并非为 DiPlay 申领的 MFi 身份。内含的私钥可被提取。该身份在未来 iOS 更新后是否仍被接受、
在不同车机上是否可靠、以及是否适合大范围分发，**均未解决**。

## 本地发布打包

发布用 APK **有意包含**该实验性配件身份。Git 仓库与源码归档**排除**全部配件密钥与 Android 签名密钥；
测试在运行时生成合成身份。源码 / CI 构建默认不含运行时身份资产，本地发布构建则显式指定外部资产目录。
发布 APK 会让其中的身份可被提取；本地构建并不能保证该身份的机密性。
