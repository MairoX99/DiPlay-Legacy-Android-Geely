# DiPlay Legacy Android

> 本项目基于 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) 修改，重点增强对低版本 Android 系统及老款 Android 车机的兼容支持。

> 上游项目：https://github.com/shihabal3amri/DiPlay

为兼容的比亚迪安卓车机提供有线及无线 CarPlay，采用 DiAuto 风格界面。

> 这些项目专注于比亚迪汽车。它们可能在其他品牌上运行，但其他品牌不在支持范围内，也没有增加支持或修复其品牌特定兼容性问题的计划。

> **本分支**（`MairoX99/DiPlay-Legacy-Android-Geely`）在此基础上增加**吉利**兼容。吉利车机运行 GKUI（ECARX 平台），行为与比亚迪 DiLink 不同。所有比亚迪相关功能原样保留。

## 吉利兼容（本分支）

| 兼容项 | 说明 |
|---|---|
| **ECARX 方向盘按键** | 吉利方向盘**不以标准 `ACTION_MEDIA_BUTTON` 广播下发**，所以此前无论 DiPlay 用多高的优先级注册都收不到，方向盘一直是"死"的，日志也一片空白。现在 DiPlay 会向车机自己的输入服务（`com.ecarx.xui.adaptapi.input`）**申请接管**方向盘，并另外识别这些车机发送的**厂商键码** —— `200085` / `200087` / `200088` / `200231`，以及 `110000` / `210000` 两组 seek 码（规律：标准 Android 键码加 200000 / 110000 / 210000 偏移）。由于该服务在 CarPlay 接入时不一定已就绪，接管会**重试 6 次、间隔 5 秒**；始终拿不到则回退到媒体会话，行为与之前完全一致。 |
| **车机热点** | "车机热点"连接此前只**检查**车机自带热点是否已打开，没开就提示用户去车机设置里手动开。现在 DiPlay 会自己通过平台的 `setWifiApEnabled` 打开它，并**保留车机原有的热点配置**；只有在第一次尝试失败后才会关闭 Wi-Fi 站点模式 —— 那会断开车机当前连接的网络。若固件拒绝（该权限往往只能通过 ADB 授予），则回退到原来的提示，并附上失败原因。 |
| **Android 5 设置页** | 设置项开关不再绘制 on/off 文字（Android 5 上的排布不正确）。 |
| **有线连接崩溃** | 只要配置了手动热点模式，运行时校验就要求填写热点 SSID —— 有线连接也不例外，导致有线连接直接中断。现已改为仅无线模式校验。 |

**状态**：ECARX 方向盘路径为**新增，尚未在实车验证**。在没有 ECARX 输入服务的车机上会自动降级为原有行为，**不影响非吉利车型**。排查日志：`DiPlay-EcarxKeys`（服务是否存在、批准了哪些键码）、`DiPlay-MediaKeys`（每个转发给 CarPlay 的按键）。

[下载与中文网站](https://shihabal3amri.github.io/DiPlay/zh-Hans/) · [完整说明](README.md) · [报告问题](https://github.com/shihabal3amri/DiPlay/issues/new/choose)

0.2.7 为公开预览版，未经 Apple 认证。请安装在车机上，而非 iPhone。无需越狱、转接盒或认证服务器。移动版 APK 现支持 Android 4.4（API 19）及以上版本：Android 4.4–7 的无线连接使用手动配置的车载热点，Android 8 及以上可使用 LocalOnlyHotspot，Wi-Fi Direct 仍需要 Android 10 或更高版本；有线 USB 路径在低版本上使用兼容实现。

认证使用从公开固件中提取的实验性配件身份，无法保证未来持续可用。部分车机仍可能卡顿或无法应用图标大小设置。应用界面支持英语、简体中文、阿拉伯语、俄语和西班牙语。源代码、构建说明及许可证随版本提供。

