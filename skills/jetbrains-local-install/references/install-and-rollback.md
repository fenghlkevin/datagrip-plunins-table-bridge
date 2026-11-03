# 安装位置与恢复

macOS 常见插件目录为 ~/Library/Application Support/JetBrains/<产品版本>/plugins；Windows 和 Linux 使用不同位置，IDE 的自定义路径设置也可能覆盖默认值。通过当前 IDE、启动日志、配置或运行参数确认实际 plugins 路径，不能仅凭“最新目录”判断。用户自定义 idea.plugins.path 时遵循该设置。

安装 ZIP 通常含 plugin-name/lib/*.jar；描述文件位于主 JAR 的 META-INF/plugin.xml。不同目录名可能对应同一个插件 ID，旧 ID 也可能是项目历史版本：只有最新项目记录明确证明归属时才替换，无法判断时询问具体冲突。

优先使用 Settings/Preferences → Plugins → 齿轮 → Install Plugin from Disk，再按 IDE 提示重启。位置可能随版本变化，以当前界面为准。

目录部署时先在隔离临时目录解包并验证，关闭 IDE 后将原同 ID 插件移至任务备份，再部署完整插件根目录。不要混合新旧 JAR、只更新某个 class 文件，或把外层 ZIP 当成已解包插件。记录所有文件变更；恢复时只撤回本次插件部署并放回对应备份。

插件仍显示旧版本时先确认实际运行的 IDE 和 plugins 路径、重启状态、是否有重复 ID、插件是否被禁用以及启动日志。不要直接清空 IDE 缓存或禁用其他插件。由启动日志排查依赖和兼容性错误。

官方安装说明：https://www.jetbrains.com/help/idea/managing-plugins.html 。实际执行时核对对应 IDE 的当前文档。
