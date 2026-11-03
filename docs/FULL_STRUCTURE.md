# 完整表结构复制（0.3.0-dev）

这一阶段支持 MySQL、PostgreSQL、Oracle，沿用已确认的“可转换的自动处理，复杂定义预览并手工修改”的方式。SQL Server 不增加此功能。

## 使用

对象选择页选中表 → 高级 → **完整结构…**。左侧显示来源 DDL，右侧显示目标预览，可以修改。留空保存恢复自动生成。保存配置会保留全文和显式依赖；配置仍为兼容的 v4，原有 v1–v3 可加载。

第一段必须是限定目标模式和名称的 `CREATE TABLE`，字段排序规则、字符集、计算列等写在此定义内。后续各段以独立一行 `-- @tablebridge-next` 分隔，每段是一条索引、触发器、ADD CONSTRAINT 或 COMMENT ON 定义。不会用分号拆开触发器程序块。依赖函数、过程、类型需作为独立对象选择，可在编辑器下方补选依赖。

```sql
CREATE TABLE dst.orders(id integer PRIMARY KEY, label text COLLATE "C")
-- @tablebridge-next
CREATE INDEX orders_lower ON dst.orders(lower(label)) WHERE id > 0
-- @tablebridge-next
CREATE TRIGGER orders_audit BEFORE INSERT ON dst.orders
FOR EACH ROW EXECUTE FUNCTION dst.audit()
```

此例为 PostgreSQL 目标；`dst.audit()` 必须已存在或单独迁移。编辑字段类型时，还须同步修改字段映射；执行会读取新建目标字段元数据，拒绝与映射不兼容的类型，防止时区等语义被手工定义悄悄改掉。手工结构适用于新建和删除重建；追加/清空保留原目标结构，不接受手工 CREATE 覆盖。

## 自动路径与审核路径

| 内容 | 同类型数据库 | 跨 MySQL / PostgreSQL / Oracle |
|---|---|---|
| 字段、默认值、主键、字段和表注释、CHECK | DataGrip 原生已加载 DDL | 原有字段映射、注释、保守 CHECK 转换；复杂定义可编辑 |
| 普通、唯一、升降序列索引 | 原生 DDL | 自动生成 |
| 简单算术和 ABS/LOWER/UPPER 表达式索引 | 原生 DDL | 保守自动转换；MySQL 使用函数键括号 |
| 条件索引 | 原生 DDL | 目标不支持时要求手工方案，不删除条件 |
| INCLUDE、GIN/GiST/Hash、全文、空间、前缀、operator class、NULLS NOT DISTINCT、Oracle bitmap/domain 等 | 原生已加载 DDL 保留；名称重写 | 检测源 DDL / 内省属性并转到手工完整结构，不能静默降级为普通索引 |
| 字段/表排序规则和字符集 | 原生 DDL 保留已加载定义 | 不假设不同引擎排序规则同名等价；显式 COLLATE 等进入目标 DDL 审核 |
| 触发器 | 开启原生触发器生成；检查源码加载状态；审计依赖需确认 | 来源存在触发器则要求手工目标定义，程序语言不盲改 |

表名称与索引/触发器名称分开重写，避免同名索引把表引用改错。PG/Oracle 的模式范围索引、MySQL/Oracle 的模式范围触发器使用独立派生名；不会替换已有对象。已有派生名冲突仍由目标数据库拒绝，通用冲突解决是后续依赖工作。原生触发器有未限定对象引用或动态 EXECUTE IMMEDIATE 时要求手工确认目标依赖，避免同模式改名后继续写来源对象。原生源代码不可用、已加密或过期时停止。

## 执行顺序与失败边界

1. 创建所有选中对象并导入表记录，逐对象提交。
2. 创建所有后置索引、CHECK、注释等。
3. 创建所有外键。
4. 最后创建触发器，再标记对象成功。

SQL 预览与实际阶段顺序一致。导入期间新复制触发器不会提前执行。追加/清空已有表时，已有目标触发器保持原状，可能执行其原有业务逻辑；本功能不禁用它们。

附属结构失败会报告失败，已提交数据保留；MySQL/Oracle DDL 的隐式提交边界没有变成整批原子回滚。本阶段没有增加断点或自动恢复。单段 SQL 校验限制目标对象、禁止替换和附加顶层语句；这是保守验证器，不是全方言程序编译器，未覆盖的复杂块需要进一步适配。

## 验收范围

- 核心 SQL 绑定、分段、触发器块、索引表达式与名称隔离检查。
- MySQL 8.0 隔离实例：utf8mb4_bin、函数索引、降序前缀索引、BEFORE INSERT 触发器，验证初始记录不被改变、后续 INSERT 执行触发器。
- PostgreSQL 18.3 隔离实例：C collation、条件/表达式、GIN、INCLUDE 索引、触发器函数及触发器，验证元数据和后续行为。
- DataGrip 2026.2.6 SDK 原生触发器模型/DDL、同模式派生名称、表达式模型转换与前缀保护、手工表结构计划、生产编辑器截图。
- Oracle 仅实现和核心/SDK 相关语法路径；独立 Oracle 测试环境不可用，未做实库验收，不宣称已通过。

当前包 `dist/tablebridge-0.3.0-dev.zip`；稳定安装仍保留 0.2.4-alpha，未覆盖。全部剩余开发并验收之前不作为完整版本交付。
