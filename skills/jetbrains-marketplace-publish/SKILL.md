---
name: jetbrains-marketplace-publish
description: 准备和发布 JetBrains Marketplace 插件，制作商店介绍、Logo、截图与操作视频，检查发布包并核验上传、审核和展示状态；适用于首次上架、版本更新与商店素材更新。
---

# JetBrains Marketplace 上架

交付可核验的发布包与商店页面。用户只要求准备材料时停在可审阅的本地成果；用户已授权上传时继续上传。使用技能本身不授予公开源码、创建账号或删除既有素材的权限。

## 确定发布范围

读取项目 README、验证记录、插件描述文件和最近发布记录，确定首次发布、版本更新或仅更新素材。核对插件 ID、版本、目标 IDE、已有商店条目和频道。已有用户决定优先，不重复询问。发布规则可能变化，执行时查阅下述官方文档。

只宣称有证据的支持范围。兼容性验证通过、上传成功、审核通过和公开可下载是四个不同状态。记录未验收的数据库、功能与已知限制；弃用 API 警告不能描述为不存在，也不能等同兼容性失败。

## 准备发布成果

1. 按项目构建和验收流程生成最终包，保留源码。版本内容变化后使用新的版本号；对最终上传文件记录 SHA-256。
2. 编写英文商店简介、功能、操作步骤、限制、支持与变更说明。国际展示需要真实英文 UI 截图和英文操作视频；字幕或英文标题不能替代英文界面。用户要求保留中文素材时同时保留。
3. **Logo 是必备发布内容。** 生成设计稿后制作真正的矢量 `pluginIcon.svg`，40×40，保留透明边距；放入项目 resources/META-INF，并核对最终主 JAR 中 `META-INF/pluginIcon.svg`。需要时提供 `pluginIcon_dark.svg`。不能把 PNG 改扩展名或嵌入 SVG 当作完成。查看 [素材与 Logo](references/media-and-logo.md)。
4. 对最终包运行 `python3 scripts/check_release.py /absolute/path/plugin.zip --expected-id ID --expected-version VERSION`（脚本路径相对本技能目录）。修复检查失败；脚本不替代实际测试和目视验证。
5. 准备许可证/EULA、支持链接和必要的账号资料。发布源码与发布许可证文本分别判断；不得为补一个 URL 自动公开私有仓库。法律身份、税务或交易者声明由用户提供，不能代为猜测。

## 上传与核验

按用户指定的浏览器、现有登录会话和可用工具工作。浏览器操作前读取适用的工具/技能说明；上传的易错环节见 [上传操作](references/browser-upload.md)。API 仅在已有安全凭据和授权时使用，不将令牌写入发布记录。

先核验当前商店 ID 和版本列表，上传确切发布包，再保存介绍、截图和视频关联。视频公开程度遵循用户选择；不能默认设为公开。保留用户指定的旧素材；删除需有明确授权。

**Logo 必须随发布包上传。** 不以画廊中的 Logo 图片替代主 JAR 图标。保存后确认服务端版本记录、商店图标与视频链接、截图列表；必要时核对 IDE 插件详情中的图标。审核或缓存导致图标暂未出现时记录“包已上传，展示待核验”，不得宣称 Logo 已公开展示。

上传结果不明确时先检查服务端条目和版本，避免重复上传。不要为了等待审核或图标缓存而盲目重新发布。确认需要人工处理的账号验证、协议或法律选择时，给出具体位置与缺失信息。

## 完成记录

在项目已有发布记录中保存：版本、插件 ID、包 SHA-256、商店 URL/更新 ID、上传与审核状态、Logo 包内路径及哈希/展示状态、截图数量与语言、视频 URL 与可见性、实际测试和限制、是否发布源码。不要记录密码、令牌或浏览器会话数据。

汇报实际已完成与待处理内容，明确审核是否仍在进行。不将本地设计稿、未保存表单、视频草稿或未生效安装说成已交付。无需为本技能创建定时任务。

## 官方依据

- [Plugin Logo](https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html)：格式、尺寸、主 JAR 路径与明暗主题。
- [Listing best practices](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)：介绍与商店素材。
- [Uploading a new plugin](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)：首次上架。
- [Publishing a plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)：更新与发布。
