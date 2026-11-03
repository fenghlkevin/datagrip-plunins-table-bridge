# 0.2.0-alpha 验证

日期：2026-09-30。SDK：DataGrip 2026.2.6，build 262.10968.148。

- 离线构建成功；654 项核心断言通过，含跨类型类型映射、未知表达式拒绝、serial 引用改写、状态报告和取消。
- 新建独立 MySQL 8.0.43 数据目录，绑定 127.0.0.1:33316；测试首先核对 @@datadir。13 项真实检查通过，覆盖 603 行流式复制、NULL/Unicode/decimal/binary、自增 0、追加碰撞回滚、DELETE 回滚、INSERT SELECT/追加计数、外键级联拦截、PG→MySQL 生成 DDL 的 MySQL 端执行、活动 SQL 取消。
- PostgreSQL 端未运行；不能将 MySQL 端 DDL 检查描述为完成跨类型端到端验收。
- 隔离 DataGrip 原生模型测试通过：serial 自动依赖与改名、PG→MySQL 自增 DDL、APPEND 无 DDL、CLEAR 为 DELETE、RECREATE 为 DROP + CREATE。
- 完整向导中，破坏性操作未勾选确认时不能开始；确认后可开始。保存了实际 Swing 渲染图 ui-wizard-connections.png、ui-wizard-review.png。
- 原有四引擎 DDL 检查和生产对象面板交互测试通过。测试均未使用用户的业务连接。

剩余实库验收：PostgreSQL、Oracle、SQL Server 全流程；真实双端跨类型转换；同名连接别名的所有权限/代理组合；较大规模数据与断网场景。详见 README 限制。

以下保留历史验证记录。

# 0.1.2-alpha 界面与交互验证

- 按已确认原型重做真实 Swing 对象面板：顶部搜索，六类对象（含空分类）、分类计数、对象勾选与详情选择分离、自动说明、高级名称/数据选项、底部全局数据/外键选项。
- 向导实时显示选择数量，未选择对象、空目标名和重复目标名时禁止下一步；搜索保留已选隐藏对象。高级配置直接用于 PlanBuilder。
- 连接页分离连接与数据库/模式选择，联动过滤目标；使用 IDE 缩放尺寸和原生主题控件。
- 本机 DataGrip 2026.2.6 编译及 633 项核心断言通过。隔离 IDE 内四引擎 DDL 生成检查通过。
- 隔离 IDE 内运行生产 ObjectSelectionPanel，验证自动/高级切换、全局和单对象数据优先级、名称校验、搜索、键盘单对象/分组选择和回调；真实组件渲染见 ui-objects.png、ui-advanced.png（内存示例对象）。修复渲染中发现的空标题高度。
- 未在用户真实数据库重新执行传输；不把模拟模型验证视为数据库端功能验收。未验证所有主题或字体缩放组合。

# 0.1.1-alpha 更新验证

- 使用本机 DataGrip 2026.2.6 SDK 编译通过，633 项断言通过（含原有逐行数据检查）。
- 新增同连接 INSERT SELECT SQL、MySQL 带连字符数据库名、PostgreSQL identity、SQL Server IDENTITY_INSERT 在成功/失败后恢复、失败不重试测试。
- 同 DataGrip 数据源：MySQL 跨数据库、Oracle 跨模式，以及 PostgreSQL/SQL Server 同 catalog 跨模式使用服务端复制；其他情况保留分批传输。
- 同连接路径仅创建一个独立数据库会话；逐行路径缓存字段类型，减少远程元数据调用。
- 首页面板改为顶部固定高度表单，修复空路径分隔符；使用 IDE 主题控件和默认下一步按钮。
- 用户已反馈旧版可复制；新版真实数据库执行、耗时对比、重启后的 UI 尚未验证。

以下保留上一版验证记录。

# 0.1.0-alpha 验证记录

日期：2026-09-30。SDK：本机 DataGrip 2026.2.5，build 262.10315.132。

| 检查 | 结果 |
|---|---|
| 使用实际安装的 DataGrip SDK 编译 | 通过 |
| ZIP / META-INF/plugin.xml 打包 | 通过 |
| 隔离 DataGrip 加载插件 | 日志确认 Loaded custom plugins: TableBridge |
| 核心测试 | 624 项断言通过（包含 603 行逐行保真检查，不是 624 个独立测试用例） |
| MySQL 原生 DDL 生成与模式/表名重定向 | 内存模型通过 |
| PostgreSQL 原生 DDL 生成与模式/表名重定向 | 内存模型通过 |
| Oracle 原生 DDL 生成与模式/表名重定向 | 内存模型通过 |
| SQL Server 原生 DDL 生成与模式/表名重定向 | 内存模型通过 |
| 实际数据库 Ctrl+C / Ctrl+V 全流程 | 未验证；没有连接用户数据库 |
| 四种数据库实际数据传输 | 未验证 |
| 视图/函数/过程/序列/类型在真实数据库的执行 | 未验证 |
| 界面截图与操作 QA | 截图服务失败，尚未验证 |

原生 DDL 测试发现并修复：

1. CREATE_BEFORE_IMPORT 在测试模型上仅返回“不支持”注释，改为 CREATE_COMPLETE，并拆出后置 ALTER TABLE/CREATE INDEX。
2. SQL Server 在未指定生成上下文数据库时会添加 USE 来源库；指定当前源模型数据库以抑制该语句，执行时由连接明确设置目标库。

安全检查：不执行 DROP/CREATE OR REPLACE；目标已有名称时阻止计划；跨引擎拒绝；所有读取类型预检查后才开始写入；取消/失败时回滚当前事务，不删除已提交对象。

后续数据库验收必须在可丢弃测试库中进行：普通表与索引、自增/序列、Unicode/NULL/decimal/时间/二进制、超过两批记录、同名冲突、跨连接、连接丢失、取消、对象依赖和源码加载状态。测试结果不得由本文件中的编译/内存测试推断。


## 0.2.1-alpha：原生类型识别与检查反馈

修复 DataGrip 元数据缺少 JDBC 类型码时，PostgreSQL bigint 被误判为不支持的类型。按原生内置类型名称及别名识别，数组、自定义域仍拒绝自动转换。失败原因显示为红色，并请求恢复下一步焦点，不更改目标表冲突策略。

验证：661 项断言通过；独立 DataGrip 2026.2.6 原生模型将 bigint / Types.OTHER 成功转换为 MySQL bigint，串行序列、追加、清空、重建及界面检查通过。焦点恢复代码已加入，尚未在用户业务连接的弹窗中验证。未连接或修改业务数据库。


## 0.2.2-alpha：时间默认值转换

在独立 MySQL 8.0.43 上复现旧 DDL `datetime(6) DEFAULT CURRENT_TIMESTAMP` 的错误 1067。修复时间字段精度和默认值的协同转换：保留显式 0–6 位字段精度；PG 未指定精度使用 6，MySQL 未指定使用 0；常见当前时间表达式使用显式精度，表达式与字段精度不同时在 MySQL 8 中使用 CAST 表达式默认值；支持时间字符串常量的 PostgreSQL 类型转换语法。转换说明展示默认值前后表达式，未通过删除默认值绕过错误。

671 项自动断言通过；64 项真实 MySQL 回归检查通过，包含旧错误复现、49 种字段精度/当前时间默认值组合的建表与默认插入，以及固定时间常量微秒值保留。此前的数据复制、事务、取消等检查一并通过。测试使用任务独有临时数据库，未连接业务库，未验证真实 PostgreSQL 源端。

语义限制：PostgreSQL 当前时间通常以事务开始时间为基准，MySQL 当前时间以语句开始时间为基准；转换为目标数据库原生当前时间默认值，不保证跨引擎事务时间语义相同。其他未知/易变表达式仍在计划阶段提示，不能声称已覆盖全部 Navicat 转换能力。

参考：https://dev.mysql.com/doc/refman/8.0/en/fractional-seconds.html


## 0.2.3-alpha：驱动进程批量传输

定位旧路径逐字段 getter/setter 跨进程调用。对于 MySQL/PostgreSQL 标准标量字段，源端通过 RemoteResultSet.getObjects 每页最多 128 行整批取值（raw 模式避免预览截断），在本地完成原有字段转换。MySQL 目标使用原生 RemoteBatchPreparedStatement 整批发送，关闭内部事务管理，由 TransferRunner 统一提交或回滚。复杂类型保留旧路径；批量执行失败不会自动重试整个传输。

本地 MySQL 8.0.43 + DataGrip 原生 RemoteConnectionImpl/驱动 API 实测：603 行（整数、decimal、中文/null、二进制）约 0.17 秒，计数包装器测得 34 次驱动接口调用。此为本机测试，不是用户 PostgreSQL→MySQL 网络环境的性能承诺。

673 项自动断言通过；72 项真实 MySQL 检查通过，包括整批数据一致性、无内部提交、回滚、长文本不截断、429 行时间微秒/布尔值/二进制和重复键失败。额外使用 PG 风格 LocalDateTime/Boolean/null 数据验证本地缓冲和分页；尚未实测 PostgreSQL 源端。独立 DataGrip 测试确认 grid ErrorRecord 依赖及 CachedRowSet 可在插件类加载器中使用，原生模型和 UI 检查通过。详细日志新增结构、复制、校验、提交分阶段耗时。


## 0.2.4-alpha：减少 MySQL 网络往返

检查真实运行日志确认用户已加载 0.2.3。上一版虽然减少 IDE/驱动进程往返，但原生批量接口配置 lines=1，MySQL 驱动默认执行批次时仍逐条向服务器发送 INSERT。临时 MySQL 8.0.43 实测复现旧配置：429 行对应会话 Com_insert 增量 429。

修复为每批最多 250 行的参数化多行 INSERT，按尾批实际行数构建并复用原生语句，外层统一管理事务。再次查询 MySQL 会话统计确认：603 行对应 3 条 INSERT，包含尾批；本机此次约 0.10 秒（不可代替用户 PostgreSQL 到 MySQL 的远程耗时）。取消在原生语句创建过程中发生时也会阻止后续执行。

674 项自动断言通过，74 项真实 MySQL 检查通过：旧路径复现、服务器 INSERT 计数、尾批、数据一致性、微秒、长文本、重复键及回滚等。仍未连接业务库，未测试真实 PostgreSQL 源端。新增数据通道与连接阶段诊断，并将详细传输日志持久化至 DataGrip idea.log；修复进度指示器的 indeterminate 警告。


## 2026-10-01：0.3.0-dev 阶段验证，尚未完整交付

本阶段接通配置保存加载、手工字段映射和默认值省略/计算列物化，跨库普通/唯一索引、外键与表注释，索引先于外键的后置阶段，以及提交前可选完整记录摘要核对。Oracle 标量映射、DATE 保留时分秒和不依赖 NLS 的时间默认值已加测试；Oracle 实库尚不可用。

本机 PostgreSQL 18.3 从官方源码构建于 build/pg-runtime，数据目录 build/pg-integration/data；MySQL 8.0.43 使用既有 build/mysql-integration/data-local。测试分别仅监听 127.0.0.1:33317/33316。测试程序在任何数据库写入前核实服务器实际数据目录，矩阵使用独立 tb_matrix 库/模式并在 finally 清理；没有连接业务库。

CrossIntegration 最终通过 2454 项真实检查：两个方向、普通 JDBC 和 DataGrip 原生 RemoteConnectionImpl 通道，每条路径 603 行，逐行验证整数、精确 decimal、NULL、中文/emoji、二进制、微秒时间和布尔值；完整摘要能识别行数相同但内容变动且不依赖物理行顺序。实际创建唯一索引、表注释和外键，并确认外键拒绝孤立记录。验证调用者回滚、数组明确转文本、原生 TIME 微秒和高精度 JSON 的文本映射。实库暴露并修复 PostgreSQL CREATE INDEX 不能限定索引名的问题，以及 MySQL 原生 JSON 高精度数值舍入的风险。

同一阶段 MySqlIntegration 74 项真实检查通过：603 行仍为 3 条 MySQL INSERT、38 次驱动接口调用，本次批量段约 0.096 秒；不是远程网络性能承诺。DataGrip 独立配置下原生 DDL/计划、索引元数据接入、生产 Swing 对象页/向导与依赖加载检查通过。核心断言最终 699 项通过（含字段类型参数预检）；矩阵和 SDK 结果保存在 build/cross-integration-result.txt 与 build/sdk-smoke。

Oracle 在写入任何测试用户前连接失败，ORA-12514，监听器未注册用户提供的服务；用户确认环境可能不可用。未创建 Oracle 测试 schema，未把 Oracle 实库标记为通过。临时登录文件已清理，不保存密码。

剩余工作见 README 的开发状态：跨方言非表对象与完整结构、时区/复杂值和 identity 特殊场景、重载与依赖、可靠重试/恢复/增量和整任务恢复、Oracle 六方向矩阵。当前开发包不覆盖已安装 0.2.4-alpha。用户已明确授权每小时后台继续开发、运行隔离测试以及完成后的备份安装和通知；后台任务 id 为 tablebridge。


## 2026-10-01：结构复制继续开发

跨库字段注释已接入 PlanBuilder：MySQL 随完整字段定义生成 COMMENT，PostgreSQL/Oracle 使用限定对象路径的 COMMENT ON COLUMN。支持 Unicode 和单引号；MySQL 反斜线注释因会话转义差异在计划生成阶段明确拒绝，未静默修改注释。

跨库检查约束已从 BasicTableOrView.getChecks() 接入后置 DDL。当前自动转换字段引用、数值比较、算术、AND/OR/NOT、IN/BETWEEN/LIKE 和普通字符串；未知函数、方言类型转换、未知字段、注释和额外语句在执行前拒绝。该支持范围不等于任意检查表达式转换。

构建及 708 项核心断言通过，日志 build/current-development.log。新增部分尚需隔离实库和 SDK 模型验收；未安装开发包。后台任务 tablebridge 已重新创建并确认 ACTIVE，每小时继续五项剩余开发，完成后通知。下一步优先验证上述结构改动，再实现视图跨库转换与视图依赖排序，随后继续特殊字段、恢复及同步。Oracle 实库限制保持不变。


## 2026-10-01：结构注释与检查约束实库验收

新增双向 MySQL/PostgreSQL 隔离矩阵断言，普通 JDBC 和 DataGrip 原生驱动通道均实际创建并查询字段注释，验证中文及单引号保真，并确认生成的 CHECK 拒绝负数记录。发现 MySQL 8.0 元数据注释将补充 Unicode（emoji）替换为问号，已在字段和表注释计划生成时拒绝这类字符，避免静默损失；PostgreSQL 注释不受此限制。反斜线转义限制保持。

构建和 709 项核心断言通过；矩阵 2466 项真实检查通过，四条路径各 603 行，日志 build/cross-integration.log、结果 build/cross-integration-result.txt。测试先确认服务器任务数据目录，结束清理 tb_matrix，并停止两个隔离实例。此次没有执行 Oracle 验收或覆盖安装。基础结构已推进，下一轮仍需 SDK 模型接入验证和非表对象转换、依赖等剩余开发，后台任务保持启用。


## 2026-10-01：基础视图转换与依赖顺序

新增 PortableView 并接入 PlanBuilder 的跨库 VIEW 分支，接受已加载 SELECT 源定义，转换字段标识符引号、限定 FROM/JOIN 到目标模式、用显式别名保持改名表的字段限定引用。字符串内容不重写。依赖必须已选择；视图依赖拓扑排序并在计划生成时拒绝循环。跨模式/三段式引用、未知依赖、表函数、嵌套查询/UNION、CTE、方言 cast、额外语句和含歧义转义的字符串明确拒绝；FROM 后逗号表达式暂不自动转换。MySQL FULL JOIN 明确拒绝。基础源码分支可能需要适配 DataGrip 返回的完整 CREATE VIEW 包装，下一轮需 SDK 模型确认，不能宣称任意视图可复制。

构建及 718 项核心断言通过（基础改写、依赖、循环、非法语法）；新增 MySQL FULL JOIN 与来源字段限定拒绝补丁随后再次构建。未运行此次视图实库验收，未启动任何业务数据库或覆盖稳定插件。下一轮优先 SDK source-text 模型与双向视图实库测试，再扩展非表对象支持。后台任务仍启用。


## 2026-10-01：视图定义包装与真实查询验收

PortableView 新增基础 CREATE [OR REPLACE] VIEW qualified_name AS SELECT 包装提取，以及单个结尾分号处理；重新生成目标 CREATE VIEW，不传播 REPLACE 到目标。未接受 DEFINER、安全属性、列列表等选项，因此没有静默丢弃这些属性。来源模式不一致与额外语句继续拒绝。

隔离实库发现原渲染把 >= 拆成 > =，已合并连续比较符，同时修复数字逐字符渲染，保留 100 与 12.50 字面量。核心 719 项断言通过。MySQL/PostgreSQL 双向普通 JDBC、原生驱动矩阵 2478 项检查通过：每条路径创建引用改名表的视图与二级依赖视图，查询结果分别为 503/403 行，数值常量 12.50 精确保留；原有数据、注释、CHECK、索引、外键测试保持通过。测试模式 finally 清理，两台项目隔离服务器已停止。日志 build/current-development.log、build/cross-integration.log。

尚需 SDK BasicSourceAware 源码对象的计划生成验收；复杂视图、函数过程、序列类型跨方言转换，以及其他原有剩余范围未完成。开发包未安装，后台任务保持活动，Oracle 未验收。


## 2026-10-01：原生 SDK 视图模型验收

PlanSmoke 使用 DataGrip 的 BasicModView、SimpleCompositeText（ORIGINAL_TEXT）及本机 ModelFactory，加入 CREATE VIEW 包装和 SELECT 两类视图源码，按反向选择顺序构建跨库计划。实际调用生产 PlanBuilder 确认表先创建、内层视图先于外层视图、两级依赖均限定至目标模式并改名；单独选择缺失依赖的视图在计划生成阶段明确拒绝。测试不创建数据库连接。

发现原测试夹具 ModelFactory.BLACK_HOLE 丢弃源码文本，不适合源定义测试；改为仅测试类内实现的 ModelTextStorage 内存适配器，保留原生模型方法，不修改生产内省状态或削弱保护。当前 build/sdk-smoke/result.txt 确认依赖类加载、原生 DDL、计划及生产 UI 检查均 PASS，plan-result.txt 包含 view source text/dependency order。构建和核心 719 项断言通过。独立 IDE 测试配置均位于 build/sdk-smoke，最终隔离实例已停止；未改用户 IDE 配置或安装开发包。

基础 SELECT 视图路径的 SDK 与双向实库证据现已补齐，但复杂视图、函数过程及其他剩余五类范围仍未完成。下一轮扩展非表对象和特殊字段，不提前安装；Oracle 测试不可用限制不变。后台任务保持活动。


## 2026-10-01：带时区 timestamp 保真路径

新增 ZonedValues 对 PostgreSQL/标准 TIMESTAMP WITH TIME ZONE 原生名称的识别与 OffsetDateTime 解析。RowCopier 不再经 getTimestamp/setTimestamp 的 JVM 默认时区路径传输该类型，明确禁止手工映射成不带时区时间；可选择文本。CrossEngineDdl 对 PostgreSQL→MySQL 默认 VARCHAR(64) 保留偏移文本，目标类型变化仍列入计划；Oracle→PostgreSQL 增加带时区类型输出但未实库验证。RowDigest 对带时区目标按 Instant 统一，不依赖会话显示偏移；目标明确文本则保持文本比较。已有目标的元数据类型也按原生名称归一化，避免 timestamptz 被误判成普通 timestamp。

核心 722 项断言通过，隔离双向矩阵 2484 项检查通过，新增 PostgreSQL 会话 Asia/Shanghai 下 JDBC 与 DataGrip 原生复制、微秒时间点与 NULL/摘要验证。数据目录预检、测试模式清理和隔离服务器停止保持。最新既有目标元数据归一化补丁再构建，但追加场景须下一轮增加实库断言。Oracle NLS/区域名与 TIME WITH TIME ZONE 尚未完成，未安装插件，后台任务保持活动。


## 2026-10-01：非表对象自动转换与手工入口

用户选择“可等价转换的自动处理，其他提供转换预览和手工修改入口”，本轮按此范围完成流程。新增原生序列、标量复合类型与单返回表达式函数转换，生产高级对象页的转换编辑器（来源只读、目标自动预览/可编辑、依赖多选），目标绑定/大小写/方言引号及单对象 CREATE 校验。手工过程进入同一审核、执行和报告流程，程序体不会盲改。显式依赖补选、拓扑排序、循环检查与配置 v4 的 SQL/依赖保存加载已接通。原有 v1–v3 保持兼容。MySQL 无原生序列/独立类型时明确拒绝，未偷偷改成表或展开类型。

核心 741 项断言通过。MySQL/PostgreSQL 矩阵最终 2490 项真实检查通过，新增实际调用两方向函数、NULL 返回、手工存储过程、PG 序列 21/23 步长与复合属性 12.50。测试不使用业务库；先核验任务数据目录、finally 清理 tb_matrix，并停止项目隔离服务器。

SDK 原生 BasicRoutine 参数/返回、BasicSequence、Oracle ObjectKind.OBJECT_ATTRIBUTE 及手工过程生成路径通过；生产编辑器预览、依赖区和确认界面已捕获并视觉检查。最新 result.txt 中依赖、DDL、计划与 UI 均 PASS。SDK Fixture 的同名已有过程触发保护检查，修正测试目标名称，未放宽已有对象保护。最终实例停止。脚本/日志位于 build/current-development.log、build/cross-integration.log、build/sdk-smoke；开发包 dist/tablebridge-0.3.0-dev.zip。

Oracle 环境不可用，仍未实际验收；Oracle 受限形参按官方规则转为手工审核，避免生成错误声明。完整范围和限制见 docs/NON_TABLE_CONVERSION.md，不把手工入口说成任意程序自动等价。用户已关闭定时任务，本轮未重新启用。当前阶段未覆盖用户已安装插件；其他四类开发和 Oracle 实库验收仍未完成。

## 2026-10-01：完整表结构阶段（触发器、排序规则、高级索引）

开启 MySQL/PostgreSQL/Oracle 原生触发器 DDL，检查触发器内省源码加载状态；生成后置触发器，执行与预览按“数据 → 索引/CHECK/注释 → 外键 → 触发器”一致排序。同模式复制生成独立的模式范围索引/触发器名，并分离表名映射与附属对象名映射，防止索引与表同名时引用错位。未限定对象引用及动态 SQL 的原生触发器转入手工审核，审计表/触发器函数依赖不盲猜。

增加“高级 → 完整结构”生产编辑器、原生/跨库预览、首条目标 CREATE TABLE 与后续附属 DDL 的目标绑定验证，分隔符不拆开触发器程序块。完整内容保存在原有配置 v4 SQL/依赖字段中，无凭据。新建目标读取真实字段类型，核对手工结构与字段映射；只用于手工结构，保留原生批量复制的现有快速路径。追加/清空不会重新创建来源触发器或高级索引。

简单算术及 ABS/LOWER/UPPER 表达式索引加入跨库自动生成。条件索引目标不支持、前缀、INCLUDE、operator class、索引排序规则、NULLS NOT DISTINCT、Oracle bitmap/domain 等高级属性会明确要求手工结构审核，不静默丢失。已加载的同库高级结构交由原生 DDL；跨库排序规则不按同名自动等价。详见 docs/FULL_STRUCTURE.md。没有新增 SQL Server 结构功能。

760 项核心断言 PASS。隔离 MySQL/PostgreSQL 最终 2501 项检查 PASS；四条 JDBC/native 路径各 603 行保真，另有真实排序规则、函数/前缀/降序、条件/表达式、GIN/INCLUDE 和触发器行为断言。两种数据库初始导入不触发新复制触发器，后续 INSERT 执行相应逻辑；手工定义与时区字段映射不一致会拒绝。任务数据目录预检和 finally 清理 tb_matrix 保持。

DataGrip 2026.2.6 SDK 最新 result.txt / plan-result.txt 为 PASS：原生 MySQL 触发器模型、改名、完整预览回读；表达式元数据转换及前缀拒绝；手工表结构计划；生产表结构编辑器及原有 UI。发现测试夹具误把完整 CREATE 写入 MySQL trigger body，按 SDK 的程序体语义改为 SET NEW.id，并未放宽生成验证。截图 build/sdk-smoke/ui-full-structure.png 已视觉检查。

本轮源码和 dist/tablebridge-0.3.0-dev.zip 保留；未覆盖已安装 0.2.4-alpha。Oracle 无实库验收，不能宣称所有 Oracle 高级定义均已通过。保守手工块验证器仍需针对未覆盖的复杂语法适配。其余特殊字段、全面依赖/冲突、持久恢复与增量同步未完成；用户关闭的定时任务未重启。隔离服务器及 SDK 实例在验收后停止。

## 2026-10-01：断点/同步及特殊字段继续开发（未完整交付）

新增 ResumableTable、KeyCursor、SyncSql、SyncDependencies、JobManifest 和 DurableRunner，接入恢复选项、恢复计划加载、任务暂存清理及配置 v5。执行确认保留同一计划 UUID；已确认检查点不重放，未知 DDL 提交状态明确停止。恢复路径在目标数据写入前冻结并校验来源暂存，主键 DML 与提交标记同事务。主键增量更新、新增及镜像删除，目标实际外键顺序与目标写入内容核对接入。详见 docs/RECOVERY_SYNC.md。

原生 PostgreSQL 数组/复合类型/XML/timetz 传输、NULL、identity 显式记录写入已实库验证。Oracle LOCAL TIME ZONE 的 UTC 会话/显式 CAST、区域时区字符串和九位小数传输路径加入，但没有 Oracle 实库证据。Oracle 连接再次返回 ORA-12514，在任何写入前失败；未保存登录密码。PostgreSQL 测试运行时重新以 libxml 支持干净构建，未跳过 XML 测试。

已知对象引用扫描加入函数美元引用体及触发器依赖；名称和引用不明仍保守停止。SDK 中恢复计划 XML 保存加载和执行指纹一致通过，界面/模型测试通过。核心 770 项通过。真实矩阵结果见 build/cross-integration-result.txt；新增故障恢复、源变化拒绝、事务回滚、APPLIED 防重、增量、镜像及实际外键顺序断言。

仍未完成全面重载/动态程序依赖、完整 Oracle identity 与特殊类型验收、循环依赖策略与完整真实断网/重启矩阵。不能把五项全部标记完成；0.3.0-dev 开发包不安装，稳定版继续保留。定时任务保持关闭。

本轮最终结果：核心 770 项通过，真实 MySQL/PostgreSQL 矩阵 2525 项通过。增加来源变化拒绝混合快照、次级唯一键冲突不更新无关主键、重新加载 APPLIED 检查点不重复 DML。APPEND 内容核对也已加入恢复路径。SDK 保存/加载恢复计划、原生模型和对象页通过；最后 APPEND 分支尚缺专项实库用例，完整异常网络矩阵尚待补充。隔离数据库和 SDK 实例均已停止，业务库未操作、稳定安装未修改。

## 2026-10-01：按用户排除 Oracle 实库验收后交付并安装 0.3.0-alpha

用户明确要求完成其他功能并安装。本轮新增按参数签名显示与持久保存的重载对象身份；独立目标名+手工审核定义允许批量迁移不同签名，自动引用歧义拒绝。原生和跨类型非表对象均开放手工定义入口。动态程序 SQL 进入高级审核，未猜测运行时拼接语义。配置 v6 与旧版本兼容，恢复计划识别具体签名。SDK 实际创建重载模型，验证两份审核定义独立入计划和签名区分。

PostgreSQL 可延迟的循环外键在同一事务中 SET CONSTRAINTS ALL DEFERRED，实际相互引用记录提交成功；非延迟循环在写入前拒绝。恢复 APPEND 校验原目标摘要加暂存摘要。新增终止实际目标服务器会话、确认 500 行持久检查点、重连续传、提交成功但确认丢失的故障注入、独立 JVM 重新加载 APPLIED 防重，以及 DataGrip 原生驱动元数据/暂存批量写。真实断线暴露 PG 驱动 -ea 模式 AssertionError，已在确认连接关闭的限定情况下转 SQLRecoverableException，保留原始错误和回滚失败原因；没有吞掉其他断言。

断点 MySQL 原生暂存接入既有每批 250 行多行 INSERT；来源与目标使用独立会话，同一 RemoteConnection 的复用在执行前拒绝。原生同物理连接测试夹具会因 MySQL 流式读取拒绝并发写入，改为与生产一致的独立会话，测试不关闭保护。界面选项和任务按钮分行，高级配置加入滚动容器，窄窗口可以访问完整配置。

Oracle 新建原生 identity 生成策略：加载显式值时 ALWAYS 暂为 BY DEFAULT，后置恢复原策略并 START WITH LIMIT VALUE；跨类型新建 identity 后置重置。核心 SQL 规则验证通过；Oracle 实库测试/验收按用户要求排除，Oracle 现有 identity 表追加/同步及任意复杂类型/程序仍不作为自动支持承诺。

最终核心 771 项、真实 MySQL/PostgreSQL 2545 项、SDK 依赖/模型/配置/生产 UI 均 PASS。安装 JAR、zip 为 0.3.0-alpha；安装前复核 SDK 证据时间晚于该 JAR、测试结果与插件 ID/版本。旧 0.2.4-alpha 备份至 build/install-backups/20261001-181347/tablebridge.jar，新 JAR 原子替换并 SHA-256 验证一致。详细安装记录 installation.json。用户 DataGrip 仍运行，保留会话，需用户重启后生效，未宣称已经热加载。业务数据未操作、定时任务未重启。


## 2026-10-01：0.3.1-alpha 英文 UI 与商店素材

增加展示层 UiText，使用 DataGrip DynamicBundle.getLocale() 选择中文/英文；测试启动参数 tablebridge.language 仅覆盖语言选择。保持数据库对象名称、六组内部身份、策略枚举和保存配置键不变。真实传输向导、对象树渲染器、完整结构编辑器、上下文菜单、结果状态和生成 SQL 注释支持英文。来源 DDL、目标 SQL、对象名称和记录内容不做整段替换；数据库/驱动原始诊断保留原文。

核心 778 项 PASS（新增中英文、空值、未知对象文本和稳定身份验证）。隔离 DataGrip SDK 英文模式原生计划、生产 UI 交互/捕获 PASS，ASCII 样本 SQL 预览增加无中文注释断言。未连接业务数据库；本次是展示层变更，未重复实库矩阵，不新增 Oracle 实库验收声明。

英文素材使用生产 Swing UI 与样例元数据；56 秒、1280×800、12 fps，672 帧全部解码 PASS，0 音轨。明确标注 SDK sample metadata / UI preview；没有伪造数据库执行结果。英文视频公开链接 https://www.youtube.com/watch?v=lTaVCPZRLLQ 。原中文截图按用户选择保留，增加英文截图。源码保留本地，未上传。

## 2026-10-02 — 0.3.2-alpha Logo packaging

Added a compact transparent 40×40 vector logo to META-INF/pluginIcon.svg in the main JAR. Offline SDK build against DataGrip 2026.2.6 and 778 core checks passed. Package ID/version/icon inspection passed (docs/branding/logo-package-check.json); Quick Look rendering inspected. Existing removal/deprecation warnings remain. This package has not been uploaded to Marketplace or installed in the user IDE; no database acceptance was repeated for this branding-only change.

## 2026-10-10 — Oracle bidirectional integration and fixes (0.3.3-alpha)

User-designated Oracle 19c 19.3 test PDB ORCLPDB, with a new random TB_TEST_ user per run; MySQL 8.0.43 and PostgreSQL 18.3 use the existing verified task-owned local data directories. Credentials passed on stdin, never stored. Each run cleaned its random Oracle user, local MySQL database and PostgreSQL schema.

Final OracleCrossIntegration: **4886 checks PASS**, Oracle ↔ MySQL and Oracle ↔ PostgreSQL, each using direct JDBC and existing native/batch adapters, 603 rows per path. Checks include explicit identity values and Oracle reseeding, decimal(20,4), Chinese/emoji/quotes, NULL, binary/BLOB, microsecond timestamps, complete digests, rollback and confirmed commits. Additional cases pass Oracle LOCAL TIME ZONE/TIME ZONE, CLOB and DATE preservation, PostgreSQL timezone return, converted views, column comments on supported targets, indexes/CHECK definitions, sequence generation, Oracle-to-PG/MySQL scalar functions, reviewed Oracle function/trigger and foreign-key behavior. Reviewed definitions are not evidence of universal automatic routine/trigger conversion.

Four production defects fixed: PostgreSQL JDBC BIT boolean read as numeric during digest comparison; Oracle JDBC single-digit timezone offset parsing; conflicting TZH:TZM + TZR format in Oracle session/input; Oracle DEFAULT clause emitted after NOT NULL. Offset timestamp transport preserves the instant/offset, not the source region-name identity.

Core **781 checks PASS**; OracleIntegration **6 real checks PASS** (defaults/identity/rollback); existing MySQL/PostgreSQL matrix **2545 checks PASS**. Evidence: build/oracle-cross-result.txt, build/oracle-cross.log, build/oracle-result.txt, build/cross-integration-result.txt and build/oracle-cross-build.log. Final 0.3.3-alpha ZIP ID/version/packaged Logo checks PASS, hash in build/oracle-release-check.json. Existing API deprecation/removal warnings remain.

Isolated SDK 0.3.3-alpha plugin loading is recorded in its startup log; this run did not produce fresh SDK model/UI result files, so the old October 1 PASS files are not claimed as this run's evidence. No business object was written, no user plugin installation or Marketplace upload performed. These cases do not certify all Oracle UDTs, overloads, dynamic programs, regional timezone semantics or recovery/synchronization modes.

## 2026-10-12 — 同步差异预览（0.3.4-alpha）

确认页增加自动只读差异预览与刷新，统计预计新增/更新/镜像删除/增量保留数量，显示最多 20 条主键和字段变化样例。完整复合主键、数值归一化、NULL、Unicode、二进制、样例截断与取消均覆盖。每表每侧 SQL 限制读取 50,001 行，第 50,001 行拒绝超限；归一化内容上限 32 MiB，超限不发布部分统计。

成功预览后，DurableRunner 在任务暂存创建前核对来源和目标摘要，全部暂存完成后、目标 DML 前再次核对目标与暂存摘要；变化停止同步。刷新重置执行确认；预览失败/取消可重新确认继续原有同步。预览样例和摘要不写配置/恢复计划。恢复任务继续原有冻结快照，已确认 APPLIED 的重试不重放 DML。数量是内容校验规则的估算，不承诺排序规则/类型转换/触发器后的完全一致，也不锁住最终检查后的并发写入。

最终核心 **794 项 PASS**（build/sync-preview-build.log），MySQL 8.0.43 / PostgreSQL 18.3 隔离矩阵 **2593 项 PASS**（build/sync-preview-integration.log）。新增 48 项实库检查：MySQL/PG 同引擎、双向跨引擎及原生驱动预览；来源/目标变化使摘要失效；只读预览保留目标；实际镜像 DML 数量与预览一致，提交后摘要一致。矩阵沿用先检查真实数据目录、仅写并 finally 清理 tb_matrix 的约束。系统 SOCKS 环境曾使 PG 本机地址解析失败，最终只在测试 JVM 用 JAVA_TOOL_OPTIONS 禁用 SOCKS，不修改系统代理。

中文/英文专用 SDK 验证均 PASS（build/sdk-smoke/preview-result-zh.txt、preview-result-en.txt）；生产 TransferDialog 差异页捕获 ui-sync-preview-zh.png、ui-sync-preview-en.png 并视觉检查，覆盖数量/字段显示、确认、失败回退、恢复禁用刷新。完整旧 SDK 测试卡在许可证窗口与模态编辑器，未把旧 result.txt 当成本轮全套 SDK 证据；改用 previewOnly 专项检查，无账号/许可证修改。

构建包 dist/tablebridge-0.3.4-alpha.zip，包 ID/版本/Logo/无测试类检查见 build/sync-preview-release-check.json。本次未重复 Oracle 实库验收，不扩大其支持范围。用户插件安装与 Marketplace 未变更；本轮启动的隔离数据库与 IDE 验证实例均关闭。
