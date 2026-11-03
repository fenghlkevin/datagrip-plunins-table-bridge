# 商店发布材料

2026-10-10：0.3.3-alpha 已上传成功，更新 ID `1190674`，商店状态为 **Under review**。0.3.1-alpha 已确认 Approved。新版本包含 SVG Logo 和 Oracle 双向复制修复；Logo 的公开展示尚待审核后的核验。现有中英文截图和公开视频保留。

- [插件管理/展示页面](https://plugins.jetbrains.com/plugin/34687-tablebridge--database-object-copy)
- [已发布的闭源使用许可](https://gist.github.com/fenghlkevin/7758c08f1d9eb3733a11b8e9f8c9cefd)
- Marketplace 插件 ID：`com.tablebridge.copy`。
- 原四张中文截图保留，已增加英文截图。预览视频使用英文界面和英文字幕。
- 源码未上传。[操作视频已公开并关联商店](https://www.youtube.com/watch?v=lTaVCPZRLLQ)。

## 可以直接使用的材料

| 文件 | 用途 |
|---|---|
| `README.md` | 英文项目介绍和使用说明，首段可用于商店简介。 |
| `README.zh-CN.md` | 中文介绍及完整操作指南。 |
| `marketplace-description.html` | 可粘贴到 Marketplace 的英文功能介绍。 |
| `media/tablebridge-walkthrough.mp4` | 56 秒、1280×800、H.264 的字幕操作演示。 |
| `media/tablebridge-preview.gif` | 轻量动态预览，适合文档/介绍页。 |
| `media/tablebridge-video-cover.png` | 视频封面，README 点击封面打开公开视频。 |
| `media/objects.png`、`media/advanced.png`、`media/sql-review.png`、`media/full-structure.png` | 真实生产 UI 截图。 |

视频采集于隔离 DataGrip SDK 环境，展示生产插件的实际界面。使用示例元数据、经过剪辑并添加操作提示；演示对象选择、高级改名、完整结构及 SQL 预览，没有真实数据库执行结果，也没有业务连接/凭据。不要将其描述为未经剪辑的完整数据库实操录屏。

## 视频章节

- 00:00：数据库树复制粘贴入口及快捷键提示。
- 00:08：确认来源与目标。
- 00:16：选择数据库对象。
- 00:24：高级改名与复制内容。
- 00:32：完整结构定义。
- 00:40：对象摘要与转换说明。
- 00:48：执行 SQL 审核及开始前确认。

## 放到 Marketplace

JetBrains 官方说明：插件上传后可以在管理页的 **Media** 部分添加截图和视频；预览视频可使用 YouTube URL。本次视频已上传，实际链接为 [TableBridge 操作演示](https://www.youtube.com/watch?v=lTaVCPZRLLQ)。不在 README 中放虚构的商店地址或视频地址。

来源：[Best practices for listing your plugin](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)。官方媒体/描述规则可能更新，提交时再核对。

README 封面与观看链接已指向公开视频；本地 MP4 和 GIF 仍保留在 `docs/media/`。

商店正式发布前，需填写自己的 vendor/contact、选定许可证、检查插件验证报告，并确认 alpha 是否作为公开试用发布。本材料保留真实支持边界：DataGrip 2026.2，MySQL/PostgreSQL 实库验收，Oracle 19c 已通过隔离 MySQL/PostgreSQL 双向表复制测试；复杂 Oracle 对象和完整 UI、恢复/同步路径尚未全面验收（见 `ORACLE_ACCEPTANCE.md`），SQL Server 不纳入当前支持。不将复杂 SQL 说成全自动等价迁移。

## 再生成媒体

1. `python3 tools/build.py --test`。
2. `python3 tools/sdk_smoke.py`，按 README 历史/验收记录中的隔离启动方式运行 SDK 实例，等待采集完成。
3. 使用带 Pillow 的 Python 执行 `tools/make_demo_frames.py`。
4. `swiftc -module-cache-path build/video-swift-cache tools/encode_demo.swift -o build/encode-demo`。
5. `build/encode-demo build/demo-frames docs/media/tablebridge-walkthrough.mp4 12`。
6. 编译 `tools/verify_demo.swift` 为 `build/verify-demo`，运行 `build/verify-demo docs/media/tablebridge-walkthrough.mp4`，解码检查全部帧并抽取画面。

演示工具和测试夹具只用于本地素材制作，不进入发行插件 JAR。
