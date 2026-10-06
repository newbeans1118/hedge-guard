# 数据模型

> 由**技术负责人**在 S2 架构阶段产出，与 `ARCHITECTURE.md` **并列为主产物**。
>
> **为什么独立成文**：数据模型是**最难改、最贵改**的制品——表结构一旦落库，
> 变更将涉及数据迁移、兼容处理与历史数据修复。若只作为架构文档的一节，
> 它会被写薄：架构文档的读者关心的是分层与接口，不是索引与约束。
>
> **本文件是数据层面的唯一事实源**：库表结构、字段含义、约束、索引均以此为准。
> 开发角色不得自行增删表或字段；确需变更时，走变更流程并先改本文件。

- **项目名称**：企业期货套期保值管理系统（HedgeGuard）
- **数据模型版本**：**v1.3.6**（变更时递增并记录于 §7；v1.1 传导 `CH-001` / `CH-002`，v1.2 为 **M1 会签整改**，v1.3 为 **M1 会签第二轮整改**，**v1.3.4 为 `TB-PLAN-1.term_end_date` 期限口径勘误〔`OR` → `min`〕**，**v1.3.5 为 `CH-011` 传导：抬头「需求基线」`FR` 计数 `183 → 184`〔**非结构变更**〕**，**v1.3.6 为 `CH-021` 扩 `parse_status`/`dispatch_type` 两处 `CHECK` 取值域〔**结构性**〕**）
- **需求基线**：`docs/REQUIREMENTS/` **184 FR** / **129 R** / 50 NFR（**v1.2 校正：原写 131 R，实测 129 R**，`PI-10`；**v1.3.5 `FR` 计数传导 `183 → 184`，源自 `CH-011` 新增 `FR-SPOT-19`**）；**17 条待澄清问题已全部解决**（`INDEX.md` §9.1）
- **数据库**：**达梦数据库 DM8**（国产；与 `PROJECT.md` §5 一致）
- **访问方式**：**仅 Java 业务组件经标准 JDBC 访问**；**C++ 交易组件零数据库访问**（架构红线）
- **上游锚点**：`PROJECT.md` v1.2 ｜ `docs/REQUIREMENTS/INDEX.md` §2.1 **子账户贯穿字段规范**（**本文件强制输入**）
- **配套制品**：`docs/ARCHITECTURE.md`（接口契约 / HGCP v1.0 FROZEN / 事前风控内存模型）
- **门禁**：**M1 门禁 = 三方会签**（产品经理 + 技术负责人 + 测试经理），三方全部通过方视为达成

> ⚠️ **v1.1 修订说明（必读）**：v1.0 起草期间，产品经理通过 `CH-001` / `CH-002` 完成
> **`Q-1`～`Q-17` 全部 17 条待澄清问题的口径确认**（`INDEX.md` §9.1 为唯一事实源），
> 并**新增 2 条 FR**（`FR-SETTLE-17`、`FR-AUDIT-13`）与 **2 条规则**（`R-SETTLE-11`、`R-AUDIT-11`）。
> v1.1 已把全部确认口径传导到数据模型：**新增 3 张表**（三层限额层②、规模基数、核对签署）、
> **修订 12 张表**（子账户类别、敞口三分量、三层限额、交易理由关联对象、有效性观察点、再平衡四类触发、
> 核对任务、双侧盈亏价格时点、科目模板、筛查关联对象缺失等）。逐项登记见 **§7.2 `DC-001`～`DC-014`**。
> **表数由 106 → 109（v1.1）→ 111（v1.2，M1 会签整改新增 `TB-SETTLE-16` 与 `TB-PLAN-8`）。**

> ⚠️ **v1.2 修订说明（M1 会签整改，必读）**：本版**仅做 M1 会签缺陷整改**，不改变模型架构。
> ① **阻塞项 B-1**：删除建在已删除列 `reason_code` 上的失效索引 `idx_trade_reason_code`，
> 改建于 `related_type`（`DC-017`）；
> ② **阻塞项 B-2**：`t_trade_reason` 的两关联对象列改为**可空 + 条件非空 `CHECK`**
> （`(两列皆非空) OR related_object_missing_flag='Y'`），使 `Q-13` 的「**关联缺失**」筛查维度
> **结构上可达、可构造、可测**；「提交时关联对象必选」由**服务层三层校验**（Java 提交权威 → C++
> `0x1019` 兜底 → 落库复核）保证（**按产品经理 `CH-004` 裁定**，`DC-024` 取代 `DC-018`/`DC-019`）；
> ③ **一般项**：`G-1` 计数统一为 43；`G-2` 重写 §4.6.3（改为**三层划分的机器可核对清单**，合计 = ① 59 + ② 19 + ③ 33 = 111）；
> `G-4` 补"不可代签"的**角色级判据与多角色解析规则**；`G-5` 作用域列改**哨兵值 `0`**（不依赖 `NULL` 唯一语义）；
> `G-6` 新增 `t_agg_diff_candidate`（`TB-SETTLE-16`）使差异归属**可机判**；
> ④ **四条判据写死**：三层限额全序（`#13`）、敞口三分量缺失处理（`#17`）、在途判定与时窗（`#19`）、
> 不可代签（`#14`）。逐项登记见 **§7.2 `DC-017`～`DC-023`**。

> ⚠️ **v1.3 修订说明（M1 会签第二轮整改，必读）**：本版**仅做第二轮会签缺陷整改与文档口径统一**，不改变模型架构。
> ① **阻塞项 `A-1`（= 产品经理 `PI-15`，两席独立复现）**：`t_spot_change_log.change_type` 的**列级 `CHECK`
> 缺 `COMPONENT_RECALC`**，使 `#17` 的必测用例⑤（展示侧重算留痕）**按本模型必然落库失败** → 已补入该值（`DC-033`）；
> ② **一般项**：§3.1 枚举总表同步（`related_type` 8 值、`limit_layer` 5 值、非限额类拒绝统一 `NONE`，`DC-034`）；
> **「可加总」口径拆名**（`t_spot_price` 归①但单价不加总 → 五类① **44**、**可加总 43**）；**取一算法统一**为
> `ratio_cap` 升序 → `priority` 升序 → `id` 升序；§0.6 去重、§5 旧表名清除、`TB-SPOT-7` 清除 `PER_SUB_ACCOUNT` 残留；
> `round_trip_count` 改 `NOT NULL DEFAULT 0`（`DC-035`）；
> ③ **建议项**：唯一索引计数 **94 → 93**（合计 391 → 390）并写死计数口径；补**快照字段集定义**（`C-3`）；`#13` 补
> 占用率权威/协议构造纪律（`C-4`）。逐项登记见 **§7.2 `DC-033`～`DC-036`**。

---

## 填写总则

> 本节是给**技术负责人**的指引。

| 原则 | 说明 |
|------|------|
| **从业务实体出发，不从表出发** | 先画出业务实体与关系（§1），再落成表——避免"为存而存"的表 |
| **字段必须可解释** | 每个字段写清业务含义；写不出含义的字段说明设计有问题 |
| **约束写在模型里，不写在代码里** | 能由数据库保证的一致性（唯一、外键、非空）应体现在模型中 |
| **考虑变更成本** | 高频变更的字段与低频字段分开；避免把易变属性写进主表 |
| **与 `PROJECT.md` §5 一致** | 数据库选型以 §5 为准；本文件不得自行更换数据库 |
| **子账户是贯穿字段** | 凡涉及**资金 / 持仓 / 交易 / 敞口 / 凭证**五类数据的表，**行级**携带 `sub_account_id`，**不得**靠关联表推导（见 §4.6 逐表核对） |

**产出时机**：S2 架构阶段，**在 `ARCHITECTURE.md` 的接口契约定稿之后、S3 任务分解之前**。
数据模型是 S3 任务分解的依据之一——没有它，任务无法按数据边界切开。

---

## 0. 全局建表约定（所有表适用，§2 各表不再重复）

> 本节定义**全库统一约定**，避免 111 张表逐一重复声明。**执行者照此建表即可**。

### 0.1 命名约定

| 对象 | 规范 | 示例 |
|------|------|------|
| 表名 | `t_<业务实体>`，**小写下划线**；模块归属通过表编号 `TB-<模块>-<序号>` 表达 | `t_hedge_plan` ← `TB-PLAN-1` |
| 主键索引 | 隐式（PK 自带） | — |
| 唯一索引 | `uk_<表名简称>_<字段简称>` | `uk_hedge_plan_no` |
| 普通索引 | `idx_<表名简称>_<字段简称>` | `idx_trade_fill_sub_ts` |
| 外键约束 | `fk_<子表简称>_<父表简称>` | `fk_hedge_plan_sub_account` |
| 检查约束 | `ck_<表名简称>_<字段简称>` | `ck_product_status` |

### 0.2 字段类型约定

| 语义 | 类型 | 说明 |
|------|------|------|
| 代理主键 | `BIGINT IDENTITY(1,1)` | **DM 特有**（见 §6）；无业务含义，**不可跨库迁移**（换库时改序列或 `AUTO_INCREMENT`） |
| 业务编号 | `VARCHAR(32)` | **由应用层生成**（合同号 / 计划号 / 凭证号 / 指令号等），**不依赖数据库序列** → 换库零成本 |
| 子账户标识 | `INT NOT NULL` | **贯穿字段**（§4.6），引用 `t_sub_account(id)`；**五类业务数据表的「行级归属列（A 类）」恒 `> 0`**（**不得使用下方哨兵值**；**不含「作用域列（B 类）例外」**——二者**非同一集合**，见下行哨兵值与 §4.3「术语分离」） |
| 子账户**作用域哨兵值** | `INT NOT NULL DEFAULT 0` | **`0` = 账户级 / 全账户（跨子账户）**。**仅允许出现在"作用域列"**（额度、规模基数、阈值、核对任务的 `sub_account_id`，见 §4.3、`DC-020`）；引用保留的系统行 `t_sub_account(id=0)`（`TB-CONFIG-1` 备注）。**业务数据表禁用**（由服务层保证，§4.4） |
| 外键（代理） | `BIGINT` | 引用父表 `id` |
| 编码（品种 / 合约 / 子账户 / 用户） | `VARCHAR(32)` | ASCII，**唯一** |
| 名称 / 短文本 | `VARCHAR(128)` | — |
| 描述 / 原因 / 说明 | `VARCHAR(500)` | — |
| 长文本（文档正文 / 报文原文 / 快照 JSON） | `CLOB` | DM 支持 `CLOB`（见 §6） |
| 金额 | `DECIMAL(20,4)` | 人民币元；**禁止 `FLOAT/DOUBLE`**（合规金额比较必须精确） |
| 价格 | `DECIMAL(20,6)` | 行情 / 成交价 / 现货价；精度 6 位小数 |
| 数量（手 / 吨 / 张） | `DECIMAL(20,4)` | 折算后数量；整数手数用 `INT` |
| 比例 / 百分比 | `DECIMAL(9,6)` | 以**小数**存储（90% → `0.900000`），**不存 `90`** |
| 布尔 | `CHAR(1)` | `'Y'` / `'N'`，配 `CHECK (col IN ('Y','N'))` |
| 枚举 | `VARCHAR(24)` | 配 `CHECK`；**取值域见 §3.1**；**不得用整数掩码**（可读性与换库友好） |
| 业务日期 | `DATE` | `yyyy-MM-dd`，时区语义 = `Asia/Shanghai` |
| 时间戳（事件发生） | `TIMESTAMP` | 应用层写入，**不使用 `CURRENT_TIMESTAMP` 默认值**（须可注入时钟以支持测试，`NFR-TEST-4`） |
| 审计时间（创建/更新） | `TIMESTAMP` | 默认 `CURRENT_TIMESTAMP`（**DM 特有语法**，见 §6） |
| 毫秒时间戳（外部/协议用） | `BIGINT` | 如 CTP 回报时间、`event_seq` 关联时间 |
| 哈希 / 校验值 | `VARCHAR(128)` | 十六进制小写（SHA-256 为 64 字符；PBKDF2 串为演进格式） |

### 0.3 通用审计字段（**每张表都有**，§2 各表不再重复列出）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | `BIGINT IDENTITY(1,1)` | PK | 代理主键 |
| `tenant_scope` | — | — | **不适用**：本系统**单法人、单真实期货账户**，**无多租户维度**（`PROJECT.md` §4.2 排除多租户 SaaS） |
| `created_by` | `BIGINT` | NOT NULL | 创建人（用户 ID）；系统自动创建时取系统账号 ID |
| `created_time` | `TIMESTAMP` | NOT NULL DEFAULT `CURRENT_TIMESTAMP` | 创建时间 |
| `updated_by` | `BIGINT` | NULL | 最后修改人；只增不改的表（留痕/日志/流水类）恒为 `NULL` |
| `updated_time` | `TIMESTAMP` | NULL | 最后修改时间；只增不改的表恒为 `NULL` |
| `row_version` | `INT` | NOT NULL DEFAULT `0` | 乐观锁版本号；**只增不改的表不使用** |

> ⚠️ **不设物理删除标记**：本系统**禁止物理删除业务记录**（除草稿类与"未产生敞口/未被引用"的单据，
> 见各表 `备注`）。"删除"= 状态置为作废 / 已撤销 / 已冲销，因此**不需要 `is_deleted` 字段**——
> 用 `is_deleted` 会掩盖"历史数据被物理删除"的合规风险。
>
> **例外（允许物理 `DELETE`）**：`t_login_session`（会话）、`t_dict_item` 中**未被引用**的项、
> 草稿态单据（`t_purchase_contract` / `t_sales_contract` / `t_production_plan` 的 `DRAFT` 状态）、
> 草稿态 `t_hedge_plan`、草稿态 `t_voucher`。上述例外均在对应表 `备注` 中显式标注。

### 0.4 外键与级联约定

| 约定 | 说明 |
|------|------|
| 是否使用外键 | **使用**。子账户贯穿、追溯链完整性、加总对账均依赖引用完整性；`PROJECT.md` §6 未禁止外键（仅禁止存储过程/触发器/自定义函数） |
| 级联规则 | **一律 `ON DELETE RESTRICT` / `ON UPDATE RESTRICT`**——**禁止级联删除**（会静默破坏追溯链与审计证据）。业务上"删除"经状态流转实现 |
| 引用子账户 | 所有业务表 `sub_account_id` → `t_sub_account(id)`，`RESTRICT`（子账户不可物理删除） |
| 追溯链外键 | `t_trade_fill.trade_plan_id` → `t_trade_plan(id)`；`t_trade_plan.hedge_plan_id` → `t_hedge_plan(id)`；`t_hedge_plan_exposure.exposure_id` → `t_exposure(id)`；`t_voucher_line.settle_cycle_id` / `trade_fill_id` → 结算与成交。**上述外键是 `FR-AUDIT-1/2` 正反向追溯的物理载体** |
| 跨模块外键 | 允许（如 `t_settle_fund` 引用 `t_fund_ledger`）。但**跨模块写表**仅限已声明共享台账的三处边界（`R-SETTLE-8`、`R-FUND-8`） |
| **可空外键与 `NULL`** | **可空外键列在 `NULL` 时不参与引用完整性校验**（`NULL` 视为"无引用"，非"引用不存在"）——故 `t_spot_change_log.sub_account_id` 等可空外键**不会因 `NULL` 报错**；实现者**不得**据此把可空作用域列视为必填（`S-5` ②） |
| **作用域列例外** | 账户级/全账户口径的**作用域列**（`t_product_quota` / `t_annual_scale` / `t_risk_threshold` / `t_check_task` / `t_check_signature` 的 `sub_account_id`）**不使用 `NULL`**，一律取**哨兵值 `0`** 并保留外键（指向保留系统行 `t_sub_account(id=0)`）；详见 §4.3 `DC-020` |

### 0.5 DM8 建表脚本模板（供 DBA 执行；**禁止**写入任何业务逻辑）

```sql
-- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
CREATE TABLE t_example (
    id                BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id    INT                         NOT NULL,          -- 贯穿字段
    biz_no            VARCHAR(32)                 NOT NULL,
    status            VARCHAR(24)                 NOT NULL,
    amount            DECIMAL(20,4)               NULL,
    created_by        BIGINT                      NOT NULL,
    created_time      TIMESTAMP  DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by        BIGINT                      NULL,
    updated_time      TIMESTAMP                   NULL,
    row_version       INT        DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_example           PRIMARY KEY (id),
    CONSTRAINT uk_t_example_biz_no    UNIQUE (biz_no),
    CONSTRAINT ck_t_example_status    CHECK (status IN ('DRAFT','ACTIVE','CLOSED'))
);
-- DM-SPECIFIC: 表/列注释语法
COMMENT ON TABLE  t_example            IS '示例表';
COMMENT ON COLUMN t_example.biz_no     IS '业务编号，应用层生成';
CREATE INDEX idx_t_example_sub_status ON t_example (sub_account_id, status);
```
> ⚠️ **脚本纪律**：① 不含存储过程 / 触发器 / 自定义函数（§6 红线）；② 不含 DML（初始化数据用独立的 `data-init.sql`）；
> ③ 每条 DM 特性语法须带 `-- DM-SPECIFIC:` 注释（`NFR-COMPAT-5`）。

### 0.6 表编号总览（**111 张**，v1.2）

| 模块 | 表编号区间 | 张数 | 模块 | 表编号区间 | 张数 |
|------|-----------|------|------|-----------|------|
| `CONFIG` | `TB-CONFIG-1` ~ `TB-CONFIG-7` | 7 | `AUDIT` | `TB-AUDIT-1` ~ `TB-AUDIT-8` | 8 |
| `AUTH` | `TB-AUTH-1` ~ `TB-AUTH-13` | 13 | `ACCT` | `TB-ACCT-1` ~ `TB-ACCT-12` | 12 |
| `PRODUCT` | `TB-PRODUCT-1` ~ `TB-PRODUCT-8` | **8** | `FUND` | `TB-FUND-1` ~ `TB-FUND-9` | 9 |
| `SPOT` | `TB-SPOT-1` ~ `TB-SPOT-10` | 10 | `RISK` | `TB-RISK-1` ~ `TB-RISK-11` | **11** |
| `PLAN` | `TB-PLAN-1` ~ `TB-PLAN-8` | **8** | `SETTLE` | `TB-SETTLE-1` ~ `TB-SETTLE-16` | **16** |
| `TRADE` | `TB-TRADE-1` ~ `TB-TRADE-9` | 9 | **合计** | — | **111** |

> **v1.1 新增 3 张**（源自 `CH-001` / `CH-002`）：
> `TB-PRODUCT-8` `t_sub_instrument_quota`（限额层②，`Q-16`）、
> `TB-RISK-11` `t_annual_scale`（规模基数，`Q-1`）、
> `TB-SETTLE-15` `t_check_signature`（核对签署记录，`Q-14`）。
>
> **v1.2 新增 2 张**（M1 会签整改）：
> `TB-SETTLE-16` `t_agg_diff_candidate`（加总差异候选归属，`Q-11`、`NFR-REL-6`，`DC-021`）、
> `TB-PLAN-8` `t_custom_hedge_rule`（自定义套保比例规则实体，`Q-6`、`FR-PLAN-8`，`DC-025`）
> → **表数 109 → 111**。

---

## 1. 实体关系

> **合格标准**：用文字或图表说清「有哪些实体」「它们之间是什么关系」。
> 这一节决定后续所有表的结构，**必须让不看代码的人也能理解业务全貌**。

### 1.1 业务主线（17 号文「期现一体」——**以实货敞口为根，单向**）

```
 子账户(真实期货账户下的逻辑虚拟账户)
    │
    ├─1:N─▶ 现货单据 ──▶ 【实货敞口(根)】──1:N──▶ 敞口构成明细
    │         · 采购合同 / 销售合同 / 生产计划(+行) / 在制品 / 库存台账
    │         · 现货价（外部录入，供敞口金额与套保比率计算）
    │                              │
    │                    N:M 绑定（计划覆盖敞口，记录覆盖数量）
    │                              ▼
    ├─1:N─▶ 套保计划 ──1:1(多版本)──▶ 套期关系书面文件（三要素）
    │         │  ──1:N──▶ 审批链（发起 ≠ 审批；审批人无交易岗权限）
    │         └─1:N──▶ 交易计划 ──1:N──▶ 交易指令 ──1:1──▶ 委托
    │                                        │  ──1:N──▶ 成交记录
    │                                        │  ──1:1──▶ 交易理由
    │                                        │  ──1:N──▶ 事前风控拒绝记录
    │                                        │  ──1:N──▶ 指令流水（生命周期轨迹）
    │                                        └──(期权)──▶ 期权策略（多腿共用策略 ID）
    │                                        │
    │                                        ▼
    ├─1:N─▶ 持仓明细 ◀── 成交驱动的本地增量（C++ 内存为权威源，落库为归档）
    │         └── 日终快照 ──▶ 结算持仓 ──┐
    ├─1:N─▶ 资金台账 ──1:N──▶ 资金流水 ───┼──▶ 【加总对账：Σ 子账户 = 真实账户】
    │         └──1:N──▶ 出入金申请 / 划拨申请（各含双人复核记录）
    │                    └──1:N──▶ 回执（CTP 异步；幂等标识）
    └─1:N─▶ 会计凭证 ──1:N──▶ 凭证分录行
               │
               └── 关联 ──▶ 套期关系 ──1:N──▶ 有效性评估记录 / 再平衡记录
                                              │
                                              └──▶ 套保效果（实货盈亏 + 衍生品盈亏，双侧同时上报）
```

### 1.2 支撑与横向关系

```
 用户 ──N:M──▶ 角色 ──N:1──▶ 岗位组 ──N:M──▶ 互斥岗位组（两两互斥 / 资金与交易互斥）
   │                └──1:N──▶ 角色功能权限
   ├─1:N─▶ 授权（人员名单 + 品种 + 额度，三者缺一不可）──▶ 下发 C++ 权限快照
   ├─1:N─▶ 子账户数据权限（LIMITED / CROSS_READONLY；安全默认 = 空）
   ├─1:N─▶ 登录会话 / 登录日志 / 口令历史
   └─1:N─▶ 权限变更留痕 / 越权尝试记录

 品种 ──1:N──▶ 期货-期权合约关联（共用同一追溯链与同一套保额度）
   ├──1:N──▶ 计量单位与换算关系
   ├──1:N──▶ 现货品类映射（期现一体的唯一入口；主映射标记）
   ├──N:M──▶ 子账户（品种白名单）
   └──1:N──▶ 品种 × 子账户 额度指标（严禁跨品种/跨子账户串用）

 配置项 ──1:N──▶ 配置变更留痕（敏感字段只记状态）
   ├── 敏感字段（密码/AuthCode/资金密码）**零落盘，不入库**
   ├──1:N──▶ 枚举字典 / 会计科目字典
   └──1:N──▶ 报告周期配置

 风控阈值 ──1:N──▶ 参数下发记录（param_version 单调；无回执不视为生效）
 镜像水位 ──▶ 事中指标快照 ──▶ 告警 ──▶ 处置（限制新开仓 = 下发 C++）
 事后风险报表 ──1:N──▶ 违规事项 / 限额使用统计 / 异常下单统计

 操作留痕 ──┐
 审计日志 ──┼──▶ 审计日志链式校验值（防篡改）
 违规筛查运行 ──1:N──▶ 筛查命中项
 审计报表 / 证据链导出 / 重大事项 ──▶ AUDIT 只读产出（不反向写入业务数据）
```

### 1.3 实体清单与预估量级

> **量级**决定索引策略与是否需要分区/归档。量级按**中型实体企业 5 年使用**估算
> （10 个子账户 / 200 品种 / 2000 合约 / 每日 ≤ 1 万笔成交）。

| # | 实体（表） | 表编号 | 一句话说明 | 预估量级（5 年） |
|---|-----------|--------|-----------|----------------|
| 1 | 子账户 | `TB-CONFIG-1` | 真实期货账户下的逻辑虚拟账户（**全系统根维度**） | ≤ 10 |
| 2 | 配置项 | `TB-CONFIG-2` | 系统可调参数（含 §11 的 20 组配置） | ≤ 2,000 |
| 3 | 配置变更留痕 | `TB-CONFIG-3` | 配置项变更记录（敏感字段只记状态） | ≤ 100,000 |
| 4 | 枚举字典 | `TB-CONFIG-4` | 业务枚举项（交易理由类型等） | ≤ 500 |
| 5 | 会计科目字典 | `TB-CONFIG-5` | 科目编码与映射 | ≤ 1,000 |
| 6 | 报告周期配置 | `TB-CONFIG-6` | 报告节奏与提醒 | ≤ 50 |
| 7 | CTP 前置配置 | `TB-CONFIG-7` | 行情/交易前置地址（非敏感） | ≤ 50 |
| 8 | 用户 | `TB-AUTH-1` | 操作账号（不可物理删除） | ≤ 500 |
| 9 | 角色 | `TB-AUTH-2` | 角色（六类业务角色 + 系统管理员，预置不可删） | ≤ 50 |
| 10 | 角色功能权限 | `TB-AUTH-3` | 角色 → 功能权限 | ≤ 2,000 |
| 11 | 用户角色 | `TB-AUTH-4` | 用户 → 角色（互斥校验在此生效） | ≤ 2,000 |
| 12 | 岗位组 | `TB-AUTH-5` | 前中后台岗位归并（互斥判定单位） | ≤ 10 |
| 13 | 岗位互斥关系 | `TB-AUTH-6` | 岗位组两两互斥矩阵 | ≤ 50 |
| 14 | 授权 | `TB-AUTH-7` | 人员名单 + 品种 + 额度（8 号文 四(二)） | ≤ 10,000 |
| 15 | 子账户数据权限 | `TB-AUTH-8` | 限定子账户 / 跨子账户只读 | ≤ 2,000 |
| 16 | 登录会话 | `TB-AUTH-9` | 会话（空闲超时；**可物理删除**） | ≤ 100,000（滚动） |
| 17 | 登录日志 | `TB-AUTH-10` | 登录成功/失败/锁定/解锁留痕 | ≤ 1,000,000 |
| 18 | 口令历史 | `TB-AUTH-11` | 最近 N 次口令哈希（禁重用） | ≤ 10,000 |
| 19 | 权限变更留痕 | `TB-AUTH-12` | 角色/授权/数据权限变更前后值 | ≤ 100,000 |
| 20 | 越权尝试记录 | `TB-AUTH-13` | 被拒的越权操作 | ≤ 100,000 |
| 21 | 品种 | `TB-PRODUCT-1` | 可套保品种（编码全系统唯一，不可删） | ≤ 200 |
| 22 | 计量单位换算 | `TB-PRODUCT-2` | 现货单位 ↔ 期货交易单位换算 | ≤ 400 |
| 23 | 品种-合约关联 | `TB-PRODUCT-3` | 品种 ↔ 期货/期权合约 | ≤ 5,000 |
| 24 | 品种-现货品类映射 | `TB-PRODUCT-4` | 期现一体的唯一入口 | ≤ 1,000 |
| 25 | 子账户品种白名单 | `TB-PRODUCT-5` | 子账户 × 品种（下单前置校验） | ≤ 3,000 |
| 26 | 品种额度指标（层①、层③） | `TB-PRODUCT-6` | **三层限额之层①（子账户+品种，主维度）与层③（账户+品种）**（禁串用） | ≤ 3,000 |
| 26b | **合约额度指标（层②）** | `TB-PRODUCT-8` | **三层限额之层②（子账户+合约）**，`Q-16` 新增 | ≤ 5,000 |
| 27 | 品种变更历史 | `TB-PRODUCT-7` | 品种关键属性变更留痕 | ≤ 20,000 |
| 28 | 采购合同 | `TB-SPOT-1` | 买入方向敞口来源 | ≤ 50,000 |
| 29 | 销售合同 | `TB-SPOT-2` | 卖出方向敞口来源 | ≤ 50,000 |
| 30 | 生产计划 | `TB-SPOT-3` | 计划期与状态 | ≤ 20,000 |
| 31 | 生产计划行 | `TB-SPOT-4` | 产成品/原料品种与数量（多行） | ≤ 100,000 |
| 32 | 在制品记录 | `TB-SPOT-5` | 在制品数量与完工比例 | ≤ 100,000 |
| 33 | 库存台账 | `TB-SPOT-6` | 库存数量序列（按记录日期） | ≤ 500,000 |
| 34 | 现货价 | `TB-SPOT-7` | 品种 + 日期 + 价格 + 来源标注 | ≤ 400,000 |
| 35 | 实货敞口 | `TB-SPOT-8` | 子账户 × 品种 × 方向（**根实体**） | ≤ 500,000 |
| 36 | 敞口构成明细 | `TB-SPOT-9` | 敞口 ← 原始单据的贡献量（可追溯） | ≤ 2,000,000 |
| 37 | 现货单据变更留痕 | `TB-SPOT-10` | 单据与子账户归属变更留痕 | ≤ 200,000 |
| 38 | 套保计划 | `TB-PLAN-1` | 交易的正当性依据 | ≤ 50,000 |
| 39 | 计划-敞口绑定 | `TB-PLAN-2` | 计划覆盖敞口（连接追溯链） | ≤ 150,000 |
| 40 | 套保比率计算留痕 | `TB-PLAN-3` | 方法/输入/输出/样本数（可复现） | ≤ 100,000 |
| 41 | 套期关系书面文件 | `TB-PLAN-4` | 三要素（多版本） | ≤ 150,000 |
| 42 | 计划审批链 | `TB-PLAN-5` | 审批节点记录（永久留痕） | ≤ 200,000 |
| 43 | 交易计划 | `TB-PLAN-6` | 可执行下单任务 | ≤ 200,000 |
| 44 | 计划状态流转留痕 | `TB-PLAN-7` | 状态变更与额度占用/释放记录 | ≤ 500,000 |
| **44a**<br>（`44` 的**追加条目**，序列 `TB-PLAN-8`） | **自定义套保比例规则**（v1.2） | **`TB-PLAN-8`** | **自定义规则法的规则实体**（比例上限 + 方向 + 品种范围 + 生效期间 + 优先级；`Q-6`/`FR-PLAN-8`，`DC-025`） | ≤ 2,000 |
| 45 | 交易指令 | `TB-TRADE-1` | 发往 CTP 的下单请求 | ≤ 1,000,000 |
| 46 | 委托（报单） | `TB-TRADE-2` | 与指令一一对应的柜台报单 | ≤ 1,000,000 |
| 47 | 成交记录 | `TB-TRADE-3` | 结算与审计的原始依据（不可改删） | ≤ 2,500,000 |
| 48 | 交易理由 | `TB-TRADE-4` | 正当性证据（结构化 + 自由说明） | ≤ 1,000,000 |
| 49 | 持仓明细 | `TB-TRADE-5` | 子账户 × 合约 × 方向 × 今昨 | ≤ 100,000 |
| 50 | 事前风控拒绝记录 | `TB-TRADE-6` | 合规证据（不可改删） | ≤ 500,000 |
| 51 | 指令流水镜像 | `TB-TRADE-7` | 生命周期轨迹（权威源为 C++ 落盘文件） | ≤ 5,000,000 |
| 52 | 期权策略 | `TB-TRADE-8` | 多腿策略头（同链同额） | ≤ 50,000 |
| 53 | 重放记录 | `TB-TRADE-9` | 崩溃恢复的重放过程与结果 | ≤ 20,000 |
| 54 | CTP 结算批次 | `TB-SETTLE-1` | 日终数据接收记录（幂等） | ≤ 5,000 |
| 55 | CTP 持仓明细 | `TB-SETTLE-2` | 期货公司侧持仓（只读基准） | ≤ 5,000,000 |
| 56 | CTP 资金明细 | `TB-SETTLE-3` | 期货公司侧资金 | ≤ 5,000 |
| 57 | CTP 成交明细 | `TB-SETTLE-4` | 期货公司侧成交 | ≤ 12,500,000 |
| 58 | 结算单 | `TB-SETTLE-5` | 交易日 × 子账户 的结算结果 | ≤ 20,000 |
| 59 | 结算持仓 | `TB-SETTLE-6` | 系统内按子账户的日终持仓 | ≤ 5,000,000 |
| 60 | 结算资金 | `TB-SETTLE-7` | 系统内按子账户的日终资金 | ≤ 20,000 |
| 61 | 结算盈亏 | `TB-SETTLE-8` | 平仓/浮动/手续费/保证金 | ≤ 200,000 |
| 62 | 核对记录 | `TB-SETTLE-9` | 三类核对（成交/仓位/资金） | ≤ 60,000 |
| 63 | 核对差异 | `TB-SETTLE-10` | 差异逐条定位与闭环 | ≤ 500,000 |
| 64 | 加总对账 | `TB-SETTLE-11` | Σ 子账户 = 真实账户 的结论 | ≤ 20,000 |
| 65 | 结算报告 | `TB-SETTLE-12` | 每日结算报告 / 风控日报 | ≤ 20,000 |
| **65a** | **加总差异候选归属**（v1.2） | **`TB-SETTLE-16`** | 加总不一致时**差异归属到哪些子账户**（结构化机判来源，`Q-11`/`NFR-REL-6`） | ≤ 50,000 |
| 66 | 核对任务 | `TB-SETTLE-13` | 月度三方核对 / 季度报告 / 年度审计覆盖（`Q-14` 升级为任务单，8 号文） | ≤ 500 |
| 66b | **核对签署记录** | `TB-SETTLE-15` | 各角色电子签署留痕（账号/角色/时间/IP/快照哈希，`Q-14` 新增） | ≤ 2,000 |
| 67 | 压力测试结果 | `TB-SETTLE-14` | 情景化保证金缺口估算 | ≤ 20,000 |
| 68 | 风控阈值 | `TB-RISK-1` | 集中配置的风控参数 | ≤ 500 |
| 69 | 参数下发记录 | `TB-RISK-2` | Java→C++ 下发与回执（param_version 单调） | ≤ 200,000 |
| 70 | 镜像状态水位 | `TB-RISK-3` | 已消费事件序号（滞后检测） | ≤ 10,000 |
| 71 | 风险告警 | `TB-RISK-4` | 事中指标超阈值告警 | ≤ 500,000 |
| 72 | 风险处置 | `TB-RISK-5` | 限制开仓 / 强制平仓提示 / 解除 | ≤ 200,000 |
| 73 | 异常下单统计 | `TB-RISK-6` | 开平仓频次与最短持仓周期 | ≤ 50,000 |
| 74 | 事后风险报表 | `TB-RISK-7` | 风险指标统计（落库） | ≤ 5,000 |
| 75 | 违规事项 | `TB-RISK-8` | 六类违规事项汇总 | ≤ 500,000 |
| 76 | 限额使用统计 | `TB-RISK-9` | 子账户 × 品种 使用率 | ≤ 3,000,000 |
| 77 | 风险指标快照 | `TB-RISK-10` | 事中指标时点值（审计复现） | ≤ 10,000,000 |
| 77b | **年度规模基数** | `TB-RISK-11` | 规模红线分子与分母（品种×年度，`Q-1` 新增） | ≤ 2,000 |
| 78 | 操作留痕 | `TB-AUDIT-1` | 关键操作"谁/何时/对什么/做了什么/结果" | ≤ 10,000,000 |
| 79 | 审计日志 | `TB-AUDIT-2` | 审计查询与导出自身记录 | ≤ 1,000,000 |
| 80 | 审计日志校验值 | `TB-AUDIT-3` | 链式哈希批次（防篡改） | ≤ 10,000 |
| 81 | 违规筛查运行 | `TB-AUDIT-4` | 筛查条件/时间/执行人（可复现） | ≤ 5,000 |
| 82 | 筛查命中项 | `TB-AUDIT-5` | 命中明细与理由字段 | ≤ 500,000 |
| 83 | 审计报表 | `TB-AUDIT-6` | 季度 / 年度专项 / 重大事项 | ≤ 2,000 |
| 84 | 证据链导出 | `TB-AUDIT-7` | 导出记录（文件标识、断点标注） | ≤ 20,000 |
| 85 | 重大事项 | `TB-AUDIT-8` | 触发条件命中与专项报告 | ≤ 500 |
| 86 | 套期关系 | `TB-ACCT-1` | 被套期项目 ↔ 套期工具 | ≤ 50,000 |
| 87 | 有效性评估记录 | `TB-ACCT-2` | 每期至少一次（定性经济关系） | ≤ 500,000 |
| 88 | 再平衡记录 | `TB-ACCT-3` | 触发原因与前后比率/数量 | ≤ 50,000 |
| 89 | 会计凭证 | `TB-ACCT-4` | 按子账户出账（头） | ≤ 1,000,000 |
| 90 | 凭证分录行 | `TB-ACCT-5` | 借贷分录与金额 | ≤ 5,000,000 |
| 91 | 会计期间 | `TB-ACCT-6` | 开放 / 已关账 | ≤ 200 |
| 92 | 关账留痕 | `TB-ACCT-7` | 关账与撤销关账（双人复核） | ≤ 1,000 |
| 93 | 套保效果 | `TB-ACCT-8` | 实货盈亏 + 衍生品盈亏 + 未对冲部分 | ≤ 500,000 |
| 94 | 双侧盈亏上报 | `TB-ACCT-9` | 同时上报记录 | ≤ 5,000 |
| 95 | 凭证审核记录 | `TB-ACCT-10` | 审核链（生成人 ≠ 审核人） | ≤ 1,000,000 |
| 96 | CAS24 自检 | `TB-ACCT-11` | 自检运行（含"待外部复核"） | ≤ 20,000 |
| 97 | CAS24 自检条目 | `TB-ACCT-12` | 逐条结论与证据位置 | ≤ 200,000 |
| 98 | 资金台账 | `TB-FUND-1` | 子账户 × 记账日 的期初/期末 | ≤ 20,000 |
| 99 | 资金流水 | `TB-FUND-2` | 每笔带凭证编号（不可改删） | ≤ 2,000,000 |
| 100 | 出入金申请 | `TB-FUND-3` | 经 CTP 的银期转账申请 | ≤ 100,000 |
| 101 | 划拨申请 | `TB-FUND-4` | 子账户间系统内记账调整 | ≤ 100,000 |
| 102 | 资金回执 | `TB-FUND-5` | 期货公司回执（唯一到账依据） | ≤ 200,000 |
| 103 | 资金复核记录 | `TB-FUND-6` | 双人复核（发起 ≠ 复核） | ≤ 300,000 |
| 104 | 资金额度 | `TB-FUND-7` | 子账户 / 账户 两级上限 | ≤ 1,000 |
| 105 | 资金额度告警 | `TB-FUND-8` | 超限告警（不阻断出金） | ≤ 200,000 |
| 106 | 资金对账 | `TB-FUND-9` | 期初 + 发生额 = 期末；与 SETTLE 同口径 | ≤ 20,000 |

**量级带来的索引与归档要求**：成交记录（`TB-TRADE-3`）、CTP 成交明细（`TB-SETTLE-4`）、
指令流水镜像（`TB-TRADE-7`）、风险指标快照（`TB-RISK-10`）、操作留痕（`TB-AUDIT-1`）、
资金流水（`TB-FUND-2`）、凭证分录行（`TB-ACCT-5`）**共 7 张表 5 年量级 ≥ 千万行**，须：
① 主查询路径必须有联合索引（见 §4）；② 支持按时间范围分区（**DM8 分区为可选手段，换库时须重写**，见 §6）；
③ 归档策略：明细保留 ≥ 5 年，超期转历史表（**归档不得删除，只能移表**）。

---

## 2. 表结构

> **合格标准**：每张表都有完整字段定义，执行者照着能建出表，无歧义。
>
> **阅读约定**：以下各表**已省略 §0.3 的通用审计字段**（`id` / `created_by` / `created_time` /
> `updated_by` / `updated_time` / `row_version`）；类型缩写见 §0.2。
> `🔴子` 标记该字段为**子账户贯穿字段**（§4.6 逐表核对）。

### 2.1 模块 `CONFIG`（系统配置）—— 7 张

#### TB-CONFIG-1 `t_sub_account` 子账户

**用途**：真实期货账户下的**逻辑虚拟账户**。全系统的**根维度**，被 40+ 张业务表行级引用。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | `INT IDENTITY(1,1)` | PK | 子账户标识（**业务主键**，非 BIGINT：数量少且被大量引用，用小整型更省空间） |
| `sub_account_code` | `VARCHAR(32)` | **NOT NULL, UNIQUE** | 子账户编码，**全系统唯一且不可修改**（`R-CONFIG-4`） |
| `sub_account_name` | `VARCHAR(128)` | NOT NULL | 子账户名称；同一真实账户内不得重复（`uk_sub_account_name`） |
| `business_line_desc` | `VARCHAR(500)` | NULL | 业务线说明 |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `ENABLED` / `DISABLED`（**无 `DELETED`**：不可物理删除，仅可停用） |
| `🔴类别` `category` | `VARCHAR(16)` | NOT NULL DEFAULT `'COMMODITY'`, CHECK | **子账户分类**（`Q-2` 已确认）：`COMMODITY`（商品类 → **90%** 红线）/ `TRADE`（贸易类 → **80%** 红线）；**默认商品类**，**允许品种级覆盖**（见 `t_sub_product_whitelist.category_override`）；**变更由风控岗发起 + 财务负责人审批**并留痕 |
| `category_approved_by` | `BIGINT` | NULL | 分类变更的**财务负责人审批人**（`Q-2`：变更须审批留痕） |
| `is_default` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否为默认子账户（新用户/新单据的缺省值；**全库至多 1 个 `'Y'`**，由应用层保证） |
| `initial_fund` | `DECIMAL(20,4)` | NULL | 初始资金（台账建立依据，`FR-FUND-1`） |
| `ledger_start_date` | `DATE` | NULL | 资金记账起始日 |
| `remark` | `VARCHAR(500)` | NULL | 备注 |
| `disabled_reason` | `VARCHAR(500)` | NULL | 停用原因（停用时必填，应用层校验） |
| `disable_precheck_json` | `CLOB` | NULL | **停用条件校验快照**（`Q-10` 已确认 4 项：资金为 0 / 持仓为 0 / 无在途指令 / 当日已结算）；不满足时停用被拒 |
| `archive_export_id` | `VARCHAR(128)` | NULL | **"导出归档"批次标识**（`Q-10`：彻底清除**只提供"导出归档"**，不做物理删除） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `pk_t_sub_account` | `id` | 主键 | — |
| `uk_sub_account_code` | `sub_account_code` | 唯一 | 编码唯一性；按编码查询 |
| `uk_sub_account_name` | `sub_account_name` | 唯一 | 防重名；界面按名称检索 |
| `idx_sub_account_status` | `status` | 普通 | 列出启用子账户（下拉框、加总对账取全集） |

**备注**：
- **保留系统行 `id = 0`**（v1.2，`DC-020`）：`sub_account_code = '__ALL_ACCOUNT__'`、
  `sub_account_name = '全账户（系统保留）'`、`status = 'DISABLED'`、`is_default = 'N'`、
  `disabled_reason = '系统保留行：账户级/全账户口径的作用域哨兵值载体'`。
  该行**不是业务子账户**：**不参与任何业务、不进入"启用子账户"列表**（`idx_sub_account_status` 只取 `ENABLED`）、
  **名下无任何业务数据行**，故**不影响 `Σ 子账户 = 真实账户`**。
  作用域列的哨兵值 `0` 外键指向本行（`SET IDENTITY_INSERT` 显式插入，见 §0.5）。
- **停用后的行为**：不可新增交易与划拨；**历史数据仍参与加总对账**（`R-CONFIG-4`）。
- **不可物理删除**：`sub_account_id` 被 40+ 张表引用；删除会造成加总对账与追溯链断裂。
- 本表是**唯一自身即子账户维度**的业务表（不属"某子账户的数据"，故无 `sub_account_id` 列）。

---

#### TB-CONFIG-2 `t_config_item` 配置项

**用途**：系统运行参数（`ARCHITECTURE.md` §11 的 `CFG-*` 全部落在此表的行）。**敏感字段不入本表**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `config_key` | `VARCHAR(128)` | **NOT NULL, UNIQUE** | 配置项 Key（如 `HG.RISK.SCALE_BASE_MODE`） |
| `config_category` | `VARCHAR(24)` | NOT NULL, CHECK | `CTP_CONN` / `RISK_PARAM` / `SUB_ACCOUNT` / `COMPONENT_COMM` / `REPORT_PERIOD` / `DICT_SUBJECT` / `SYSTEM` |
| `config_value` | `VARCHAR(2000)` | NULL | 配置值（字符串统一存储，按 `value_type` 解析）；**敏感字段禁止入本列**（应用层 + `CK` 双保险） |
| `value_type` | `VARCHAR(16)` | NOT NULL, CHECK | `STRING` / `INT` / `DECIMAL` / `BOOL` / `ENUM` / `LIST` |
| `enum_scope` | `VARCHAR(500)` | NULL | `value_type=ENUM/LIST` 时的取值域（逗号分隔） |
| `min_value` / `max_value` | `DECIMAL(20,6)` | NULL | 数值上下界（`FR-CONFIG-11` 的取值校验） |
| `default_value` | `VARCHAR(2000)` | NULL | 默认值（**须与需求文件明示口径一致**，`R-CONFIG-8`） |
| `is_sensitive` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否敏感字段；**`'Y'` 时 `config_value` 必须为 `NULL`**（`ck_config_item_sensitive`） |
| `sensitive_state` | `VARCHAR(16)` | NULL, CHECK | 敏感字段状态：`UNSET` / `SET`（**只记状态不记取值**，`R-CONFIG-2`） |
| `persist_scope` | `VARCHAR(16)` | NOT NULL, CHECK | `LOCAL_ADMIN`（可持久化）/ `API_DISPATCH`（**不落盘**）/ `NEVER`（零落盘） |
| `dispatch_to_ctp` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否需下发 C++ 组件（`R-CONFIG-3`） |
| `double_review_required` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 变更是否须双人复核（如放宽合规红线：`'Y'`） |
| `related_question_no` | `VARCHAR(16)` | NULL | 关联待澄清编号（`Q-1` ~ `Q-17`；非待澄清衍生项为 `NULL`） |
| `effective_version` | `BIGINT` | NOT NULL DEFAULT `0` | 生效版本（下发用的 `param_version`，单调递增） |
| `description` | `VARCHAR(500)` | NULL | 配置项说明（含默认值与影响的提示，`NFR-DEPLOY-4`） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_config_item_key` | `config_key` | 唯一 | 按 Key 取值（下发与读取热路径） |
| `idx_config_item_category` | `config_category` | 普通 | 配置界面按分类浏览（`FR-CONFIG-11`） |
| `idx_config_item_dispatch` | `dispatch_to_ctp` | 普通 | 找出所有需下发 C++ 的项（启动比对 / 全量重下发） |

**备注**：
- **敏感字段"入库"的例外说明**：本表**不存敏感取值**；`is_sensitive='Y'` 的行只存 `sensitive_state`。
  这是"敏感字段零落盘"在数据模型层的落地（`NFR-SEC-1`）——**配置表结构本身就不具备存密码的能力**。
- **新增配置项不需要改表结构**：这是 §11 的 20 组配置能"不阻塞待澄清问题"的结构基础。

---

#### TB-CONFIG-3 `t_config_change_log` 配置变更留痕

**用途**：配置项变更的完整记录（含下发结果）。**只增不改不删**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `config_key` | `VARCHAR(128)` | NOT NULL | 配置项 Key |
| `config_category` | `VARCHAR(24)` | NOT NULL | 冗余分类（便于按分类筛选，避免关联查询） |
| `before_value` | `VARCHAR(2000)` | NULL | 变更前值；**敏感字段恒为 `NULL`** |
| `after_value` | `VARCHAR(2000)` | NULL | 变更后值；**敏感字段恒为 `NULL`** |
| `is_sensitive_change` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否敏感字段变更；`'Y'` 时前后值必须为 `NULL` |
| `sensitive_state_before` | `VARCHAR(16)` | NULL | 敏感字段变更前状态（`UNSET`/`SET`） |
| `sensitive_state_after` | `VARCHAR(16)` | NULL | 敏感字段变更后状态 |
| `operator_id` | `BIGINT` | NOT NULL | 操作人 |
| `reviewer_id` | `BIGINT` | NULL | 复核人（须双人复核项必填且 **≠ `operator_id`**） |
| `change_reason` | `VARCHAR(500)` | NULL | 变更原因（放宽合规红线时**必填**，应用层校验） |
| `dispatch_result` | `VARCHAR(16)` | NULL, CHECK | 下发结果：`SUCCESS` / `FAIL` / `UNCONFIRMED` / `NOT_REQUIRED` |
| `dispatch_param_version` | `BIGINT` | NULL | 下发所用的 `param_version` |
| `change_time` | `TIMESTAMP` | NOT NULL | 变更时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_config_chg_key_time` | `config_key, change_time` | 普通 | 按配置项查变更历史（`FR-CONFIG-12`） |
| `idx_config_chg_category_time` | `config_category, change_time` | 普通 | 按分类 + 时间范围查询 |
| `idx_config_chg_dispatch_fail` | `dispatch_result` | 普通 | 筛"下发失败/未确认"（运维巡检） |

**备注**：本表**不含 `updated_by` / `updated_time` / `row_version`**——只增不改（§0.3）。

---

#### TB-CONFIG-4 `t_dict_item` 枚举字典

**用途**：业务枚举的可维护选项（如交易理由类型、计量单位含义标注）。**被引用的项不得删除**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `dict_type` | `VARCHAR(64)` | NOT NULL | 字典类型（如 `TRADE_REASON` / `UNIT_LABEL` / `EXPOSURE_DIRECTION`） |
| `item_code` | `VARCHAR(32)` | NOT NULL | 枚举编码 |
| `item_name` | `VARCHAR(128)` | NOT NULL | 枚举名称 |
| `item_desc` | `VARCHAR(500)` | NULL | 业务含义说明（**枚举的业务含义必须写清**，`FR-CONFIG-14`） |
| `sort_no` | `INT` | NOT NULL DEFAULT `0` | 排序号 |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `ENABLED` / `DISABLED`（**无删除**；被引用只能停用） |
| `is_system` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否系统内置项（内置项**不可停用**） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_dict_item_type_code` | `dict_type, item_code` | 唯一 | 同类型下编码唯一 |
| `idx_dict_item_type_status` | `dict_type, status` | 普通 | 界面按类型取可用项（下拉框） |

---

#### TB-CONFIG-5 `t_account_subject` 会计科目字典

**用途**：`ACCT` 生成凭证时引用的会计科目（**不得在代码中硬编码科目**，`R-ACCT-11`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `subject_code` | `VARCHAR(32)` | **NOT NULL, UNIQUE** | 科目编码（如 `3101` 衍生工具） |
| `subject_name` | `VARCHAR(128)` | NOT NULL | 科目名称 |
| `parent_code` | `VARCHAR(32)` | NULL | 上级科目编码（`NULL` = 一级科目） |
| `subject_level` | `INT` | NOT NULL DEFAULT `1` | 科目级次 |
| `direction` | `VARCHAR(8)` | NOT NULL, CHECK | 余额方向：`DEBIT` / `CREDIT` |
| `biz_scene` | `VARCHAR(32)` | NOT NULL, CHECK | 适用业务场景：`OPEN_POSITION` / `CLOSE_PNL` / `COMMISSION` / `MARGIN_CHANGE` / `FLOAT_PNL` / `PREMIUM` / `HEDGE_EFFECT` / `OTHER` |
| `is_template_default` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否系统预置的套期会计常用科目模板**（`Q-9`：系统提供模板，**企业可增删改**） |
| `editable_by_enterprise` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 企业是否可修改（模板项亦为 `'Y'`——`Q-9` 明确"企业可增删改"） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `ENABLED` / `DISABLED` |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_account_subject_code` | `subject_code` | 唯一 | 科目编码唯一 |
| `idx_account_subject_template` | `is_template_default, status` | 普通 | **初始化时导入模板项（`Q-9`）；界面区分"模板科目"与"企业自建科目"** |
| `idx_account_subject_scene` | `biz_scene, status` | 普通 | `ACCT` 按场景取科目（生成凭证热路径） |

**备注**：`biz_scene` 是"科目映射口径"（`Q-9`）的落点：`Q-9` 澄清后如需更细的映射规则，
**只需在本表增行或增列**，不需要改动凭证表结构。

---

#### TB-CONFIG-6 `t_report_period_config` 报告周期配置

**用途**：各类报告的生成节奏与提醒（`FR-CONFIG-15`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `report_type` | `VARCHAR(32)` | **NOT NULL, UNIQUE** | `DAILY_SETTLE` / `DAILY_RISK` / `MONTHLY_CHECK` / `QUARTERLY_REPORT` / `ANNUAL_REPORT` / `MAJOR_EVENT` |
| `period_unit` | `VARCHAR(8)` | NOT NULL, CHECK | `DAY` / `MONTH` / `QUARTER` / `YEAR` |
| `period_value` | `INT` | NOT NULL DEFAULT `1` | 周期数量 |
| `remind_advance_days` | `INT` | NOT NULL DEFAULT `0` | 提前提醒天数（计划到期提醒另有 `HG.PLAN.EXPIRY_REMIND_DAYS`） |
| `remind_role_codes` | `VARCHAR(500)` | NOT NULL | 提醒接收角色编码（逗号分隔，须为有效角色） |
| `is_mandatory` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 是否合规必需（**`'Y'` 时不得被配置为不生成**，`R-CONFIG-7`） |
| `is_enabled` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 是否启用；`is_mandatory='Y'` 时**必须为 `'Y'`**（`ck_report_period_mandatory`） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_report_period_type` | `report_type` | 唯一 | 按报告类型取配置 |
| `idx_report_period_enabled` | `is_enabled` | 普通 | 调度线程启动时加载启用的周期 |

**备注**：`is_mandatory='Y'` 与 `is_enabled='N'` 的组合被 `CHECK` 禁止 —— 这是把
"合规必需报告不得被配置关闭"（`R-CONFIG-7`）**下沉到数据结构**，而不是只靠代码校验。

---

#### TB-CONFIG-7 `t_ctp_front_config` CTP 前置配置

**用途**：行情/交易前置地址（**非敏感项**）。密码 / AuthCode **不在此表**（零落盘）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `env_type` | `VARCHAR(16)` | NOT NULL, CHECK | `SIMULATION` / `PRODUCTION`（**显式区分，防误连实盘**，`R-CONFIG-9`） |
| `front_type` | `VARCHAR(16)` | NOT NULL, CHECK | `TRADE` / `MD` |
| `front_addr` | `VARCHAR(128)` | NOT NULL | 前置地址（`tcp://ip:port`） |
| `priority` | `INT` | NOT NULL DEFAULT `0` | 优先级（0 = 主，>0 = 备份） |
| `broker_id` | `VARCHAR(32)` | NOT NULL | 经纪商代码 |
| `investor_id` | `VARCHAR(32)` | NOT NULL | 用户代码（资金账号） |
| `connect_timeout_ms` | `INT` | NOT NULL DEFAULT `5000` | 连接超时 |
| `reconnect_interval_ms` | `INT` | NOT NULL DEFAULT `3000` | 重连间隔 |
| `flow_path` | `VARCHAR(256)` | NULL | CTP 流文件目录（**非敏感**；不得存放明文凭证） |
| `is_enabled` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 是否启用 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_ctp_front_env_type_addr` | `env_type, front_type, front_addr` | 唯一 | 同环境同类型下地址不重复 |
| `idx_ctp_front_env_prio` | `env_type, front_type, priority` | 普通 | 按优先级选取主/备前置（故障切换） |

**备注**：**不含任何密码 / AuthCode 列**——本表结构上就不具备存储敏感凭据的能力（`NFR-SEC-1`）。
`PRODUCTION` 环境的启用须二次确认 + 双人复核（应用层 + `t_config_change_log` 留痕）。

---

### 2.2 模块 `AUTH`（用户与权限）—— 13 张

#### TB-AUTH-1 `t_user` 用户

**用途**：操作账号（对应企业岗位人员）。**不可物理删除，只能停用**（`R-AUTH-1`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `username` | `VARCHAR(64)` | **NOT NULL, UNIQUE** | 登录名，**不可修改** |
| `real_name` | `VARCHAR(64)` | NOT NULL | 姓名 |
| `natural_person_key` | `VARCHAR(64)` | NOT NULL, UNIQUE | **自然人唯一键**（如身份证号哈希或工号）——用于"一个自然人一个账号"（`R-AUTH-2`） |
| `department` | `VARCHAR(128)` | NULL | 所属部门 |
| `is_enterprise_leader` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **企业负责人标记**；`'Y'` → 禁授交易岗 + 禁下单（`FR-AUTH-9`、`0x100F`） |
| `leader_flag_reviewer_id` | `BIGINT` | NULL | 负责人标记变更的复核人（须双人复核，且 ≠ 变更操作人） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `ENABLED` / `DISABLED`（**无 `DELETED`**） |
| `password_hash` | `VARCHAR(128)` | NOT NULL | **加盐哈希**（PBKDF2-HMAC-SHA256，格式 `pbkdf2$<iter>$<salt_b64>$<hash_b64>`）；**永不明文**（`NFR-SEC-2`） |
| `must_change_password` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 首次登录 / 重置后强制改口令 |
| `password_updated_time` | `TIMESTAMP` | NULL | 最近一次口令修改时间 |
| `fail_count` | `INT` | NOT NULL DEFAULT `0` | 连续登录失败次数 |
| `locked_until` | `TIMESTAMP` | NULL | 锁定至（`NULL` = 未锁定）；`FR-AUTH-17` |
| `last_login_time` | `TIMESTAMP` | NULL | 最后登录时间 |
| `disabled_reason` | `VARCHAR(500)` | NULL | 停用原因（停用时必填） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_user_username` | `username` | 唯一 | 登录（命中索引避免枚举）；新增查重 |
| `uk_user_natural_person` | `natural_person_key` | 唯一 | **"一个自然人一个账号"的数据库级保证** |
| `idx_user_status` | `status` | 普通 | 用户列表按状态筛选 |
| `idx_user_leader` | `is_enterprise_leader` | 普通 | 列出企业负责人（复核权限配置时核对） |
| `idx_user_locked` | `locked_until` | 普通 | 解锁巡检任务 |

**备注**：
- **口令字段的唯一形态是 `password_hash`**：表结构上**没有** `password` 明文列 → 从数据模型层杜绝明文落库。
- `fail_count` / `locked_until` 是**认证状态**，不是业务数据；解锁后 `fail_count` 清零（`FR-AUTH-17`）。

---

#### TB-AUTH-2 `t_role` 角色

**用途**：角色（六类业务角色 + 系统管理员）。**预置角色不可删除**（`R-AUTH-10`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `role_code` | `VARCHAR(32)` | **NOT NULL, UNIQUE** | 角色编码 |
| `role_name` | `VARCHAR(128)` | NOT NULL | 角色名称 |
| `position_group_id` | `INT` | NOT NULL, FK → `t_position_group(id)` | 所属岗位组（**互斥判定的单位**） |
| `is_preset` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否预置角色（`'Y'` → 不可删除，只可调整权限） |
| `is_business_role` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 是否业务角色；系统管理员为 `'N'`（**不得拥有业务操作权限**） |
| `has_trading_operation` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否含交易下单能力（供互斥与负责人规则快速判定；`is_business_role='N'` 时**必须为 `'N'`**） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `ENABLED` / `DISABLED` |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_role_code` | `role_code` | 唯一 | 按编码取角色 |
| `idx_role_position_group` | `position_group_id` | 普通 | 按岗位组取角色集合（互斥校验） |
| `idx_role_trading` | `has_trading_operation, status` | 普通 | 快速取"具交易岗能力的角色" |

**备注**：预置的六类业务角色 =
`FIN_RISK`（财务/风控岗）、`TRADER`（期货交易岗）、`SETTLER`（结算岗）、
`CASHIER`（资金岗）、`APPROVER`（管理层/审批人）、`AUDITOR`（审计/合规岗）；
另加 `SYS_ADMIN`（系统管理员，`is_business_role='N'`）。

---

#### TB-AUTH-3 `t_role_permission` 角色功能权限

**用途**：角色 → 功能权限（模块与操作）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `role_id` | `BIGINT` | NOT NULL, FK → `t_role(id)` | 角色 |
| `perm_code` | `VARCHAR(128)` | NOT NULL | 权限编码（如 `PRODUCT:EDIT`、`TRADE:PLACE_ORDER`、`FUND:TRANSFER`） |
| `perm_category` | `VARCHAR(16)` | NOT NULL, CHECK | `FUNCTION` / `DATA` / `QUOTA`（对应 `AUTH.md` §2 的三类权限） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_role_perm` | `role_id, perm_code` | 唯一 | 防重复授予 |
| `idx_role_perm_code` | `perm_code` | 普通 | 反向查"哪些角色有该权限"（越权排查） |

---

#### TB-AUTH-4 `t_user_role` 用户角色

**用途**：用户 → 角色。**岗位互斥校验的落点**（服务层在插入前校验）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `user_id` | `BIGINT` | NOT NULL, FK → `t_user(id)` | 用户 |
| `role_id` | `BIGINT` | NOT NULL, FK → `t_role(id)` | 角色 |
| `granted_by` | `BIGINT` | NOT NULL | 授予人 |
| `granted_time` | `TIMESTAMP` | NOT NULL | 授予时间 |
| `reviewer_id` | `BIGINT` | NULL | 复核人（须双人复核的角色标 `'Y'`） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_user_role` | `user_id, role_id` | 唯一 | 防重复授予 |
| `idx_user_role_role` | `role_id` | 普通 | 按角色统计在岗人员（岗位分离核对） |

**备注**：**互斥关系不在此表用约束表达**（互斥是"跨行跨表"的语义，SQL `CHECK` 无法表达）。
但互斥矩阵（`t_position_conflict`）是**唯一事实源**，服务层每次授予前查矩阵，
并**在 `t_permission_change_log` 记录拒绝尝试**（`R-AUTH-3`、`R-AUTH-8`）。

---

#### TB-AUTH-5 `t_position_group` 岗位组

**用途**：角色按前中后台归并的类别（互斥判定的单位）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `group_code` | `VARCHAR(32)` | **NOT NULL, UNIQUE** | 岗位组编码 |
| `group_name` | `VARCHAR(128)` | NOT NULL | 名称 |
| `front_mid_back` | `VARCHAR(16)` | NOT NULL, CHECK | `FRONT`（前中台-交易）/ `MID`（中台-风控/结算/资金）/ `BACK`（后台-审计）/ `MANAGE`（管理-审批）/ `SYSTEM`（系统） |
| `is_conflict_enforced` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 是否参与强制互斥；**必须恒为 `'Y'`**（不允许关闭互斥，`R-AUTH-3`） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `ENABLED` / `DISABLED` |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_position_group_code` | `group_code` | 唯一 | 按编码取组 |
| `idx_position_group_fmb` | `front_mid_back` | 普通 | 按前后台分类统计 |

**备注**：`is_conflict_enforced` 恒为 `'Y'` 的 `CHECK` 是"**不得提供关闭互斥的配置开关**"
（`FR-AUTH-7` 验收 2）在数据层的落地：即便有人改数据，也只能改成 `'Y'`。

---

#### TB-AUTH-6 `t_position_conflict` 岗位互斥关系

**用途**：岗位组两两互斥矩阵。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `group_a_id` | `INT` | NOT NULL, FK → `t_position_group(id)` | 岗位组 A |
| `group_b_id` | `INT` | NOT NULL, FK → `t_position_group(id)` | 岗位组 B |
| `conflict_reason` | `VARCHAR(500)` | NOT NULL | 互斥依据（如"8 号文 四(一) 前中后台分离"） |
| `is_mandatory` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 是否强制互斥；**默认互斥集恒为 `'Y'`**（不可转为可配置） |
| `reviewer_id` | `BIGINT` | NULL | 变更复核人（互斥配置变更须双人复核） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_position_conflict_pair` | `group_a_id, group_b_id` | 唯一 | 防重复 |
| `idx_position_conflict_a` | `group_a_id` | 普通 | 取某组的所有互斥组（授予时校验） |

**备注**：默认互斥集（初始化数据，`data-init.sql`）：
`TRADER ↔ FIN_RISK`、`TRADER ↔ SETTLER`、`FIN_RISK ↔ SETTLER`（两两互斥）、
`TRADER ↔ CASHIER`（资金岗与交易岗互斥）。
> **对称性**：矩阵按单向存储（仅存 `a < b` 的组合），服务层查询时双向匹配（`OR` 查询）。

---

#### TB-AUTH-7 `t_authorization` 授权

**用途**：**人员名单 + 品种 + 额度**三者齐备的授权（8 号文 四(二)）。事前风控权限校验的输入。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `user_id` | `BIGINT` | NOT NULL, FK → `t_user(id)` | 被授权人（**人员名单**） |
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 授权适用的子账户 |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | **授权品种**（每品种一行，便于精确校验） |
| `quota_limit` | `DECIMAL(20,4)` | NOT NULL | **授权额度**（该用户该子账户该品种的个人上限）；`> 0`（`ck_authorization_quota_positive`） |
| `effective_from` | `DATE` | NOT NULL | 生效起始日 |
| `effective_to` | `DATE` | NULL | 生效截止日（`NULL` = 长期） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `DRAFT` / `PENDING_REVIEW` / `EFFECTIVE` / `EXPIRED` / `REJECTED` |
| `applicant_id` | `BIGINT` | NOT NULL | 申请人 |
| `reviewer_id` | `BIGINT` | NULL | 复核人（**≠ `applicant_id`**，`R-AUTH-4`） |
| `dispatch_status` | `VARCHAR(16)` | NOT NULL DEFAULT `'PENDING'`, CHECK | 下发 C++ 状态：`PENDING` / `SUCCESS` / `FAIL` / `UNCONFIRMED` |
| `dispatch_version` | `BIGINT` | NULL | 下发所用 `param_version` |
| `reject_reason` | `VARCHAR(500)` | NULL | 驳回原因（驳回时必填） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_authorization_unique` | `user_id, sub_account_id, product_id, effective_from` | 唯一 | 防同一人同子账户同品种同起点重复授权 |
| `idx_authorization_user_eff` | `user_id, status, effective_to` | 普通 | 取某人的**有效**授权（事前风控参数预加载、下单前权限展示） |
| `idx_authorization_dispatch` | `dispatch_status` | 普通 | 巡检"未下发成功"的授权（`R-CONFIG-3`） |
| `idx_authorization_sub` | `sub_account_id` | 普通 | 按子账户统计授权分布（审计） |

**备注**：`quota_limit` 的 `NOT NULL` + `> 0` 约束把
"仅配置人员与品种、未配置额度时保存被拒绝"（`FR-AUTH-10` 验收 2）**下沉到数据结构**。

---

#### TB-AUTH-8 `t_user_sub_account_perm` 子账户数据权限

**用途**：用户可访问的子账户范围（**软隔离**；安全默认 = 无记录 = 不可见任何子账户）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `user_id` | `BIGINT` | NOT NULL, FK → `t_user(id)` | 用户 |
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 可访问的子账户 |
| `perm_type` | `VARCHAR(24)` | NOT NULL, CHECK | `LIMITED`（限定子账户，可读写）/ `CROSS_READONLY`（跨子账户**只读**） |
| `granted_by` | `BIGINT` | NOT NULL | 授予人 |
| `reviewer_id` | `BIGINT` | NULL | 复核人（**≠ `granted_by`**，须双人复核） |
| `effective_from` | `DATE` | NOT NULL | 生效起始日 |
| `effective_to` | `DATE` | NULL | 生效截止日 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_user_sub_perm` | `user_id, sub_account_id` | 唯一 | 防重复 |
| `idx_user_sub_perm_user` | `user_id, perm_type` | 普通 | **每次请求构建数据权限过滤条件**（DAO 统一注入 `sub_account_id IN (...)`） |

**备注**：
- **安全默认的落地**：本表**无记录 = 该用户看不到任何子账户数据**。实现上，
  公共数据权限过滤器发现"查不到任何授权行"时，**注入 `sub_account_id IN (空的恒假条件)`**（返回空集），
  而**不是**跳过过滤（跳过 = 全可见，正是 `NFR-SEC-7` 严禁的"默认全可见"）。
- `perm_type='CROSS_READONLY'` 的只读性由服务层保证（写操作前校验 `perm_type`）。
- **本表不属于 §2.1 五类业务数据**（它是**权限维度**而非业务数据维度，`INDEX.md` §2.1 已显式说明）；
  其 `sub_account_id` 语义是"被授权的子账户"，与本表中标注 `🔴子` 的字段含义不同——后者是"数据归属"。

---

#### TB-AUTH-9 `t_login_session` 登录会话

**用途**：会话管理（空闲超时；**可物理删除**）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `session_id` | `VARCHAR(64)` | **NOT NULL, UNIQUE** | 服务端生成的 opaque 会话 ID（Cookie `HGSESSION`） |
| `user_id` | `BIGINT` | NOT NULL, FK → `t_user(id)` | 用户 |
| `login_time` | `TIMESTAMP` | NOT NULL | 登录时间 |
| `last_active_time` | `TIMESTAMP` | NOT NULL | 最后活动时间（用于空闲超时判定） |
| `idle_timeout_minutes` | `INT` | NOT NULL | 该会话的空闲超时（登录时从配置快照，防运行期改配置影响既有会话） |
| `expire_time` | `TIMESTAMP` | NOT NULL | 绝对过期时间 |
| `client_ip` | `VARCHAR(64)` | NULL | 客户端 IP（审计用） |
| `user_agent` | `VARCHAR(500)` | NULL | UA（审计用） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `ACTIVE` / `LOGGED_OUT` / `EXPIRED` / `KICKED`（管理员强制下线） |
| `kicked_by` | `BIGINT` | NULL | 强制下线的管理员 |
| `permission_version_snapshot` | `BIGINT` | NOT NULL | 会话建立时的权限版本快照；**权限变更后须使相关会话立即失效**（`R-AUTH-9`） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_login_session_id` | `session_id` | 唯一 | **每个请求的会话校验（最高频查询）** |
| `idx_login_session_user_status` | `user_id, status` | 普通 | 停用用户/权限变更时**立即失效其全部会话**（`FR-AUTH-3` 验收 1） |
| `idx_login_session_expire` | `expire_time` | 普通 | 清理过期会话 |

**备注**：本表**允许物理 `DELETE`**（清理历史会话）；但 `status` 流转记录留存在 `t_login_log`（只增不改）。

---

#### TB-AUTH-10 `t_login_log` 登录日志

**用途**：登录成功/失败/锁定/解锁**全部留痕**（`R-AUTH-7`）。**只增不改不删**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `user_id` | `BIGINT` | NULL, FK → `t_user(id)` | 用户（**用户不存在时**为 `NULL`，但该场景**不向用户暴露**，防枚举） |
| `username_input` | `VARCHAR(64)` | NOT NULL | 输入的用户名（**不含口令**） |
| `event_type` | `VARCHAR(24)` | NOT NULL, CHECK | `LOGIN_SUCCESS` / `LOGIN_FAIL` / `LOCKED` / `UNLOCKED` / `LOGOUT` / `KICKED` |
| `fail_count_after` | `INT` | NULL | 事件后的失败计数 |
| `client_ip` | `VARCHAR(64)` | NULL | 客户端 IP |
| `user_agent` | `VARCHAR(500)` | NULL | UA |
| `fail_reason_code` | `VARCHAR(32)` | NULL | 失败原因**内部码**（用于运维分析）；**对外统一为同一提示**（`R-AUTH-7`） |
| `event_time` | `TIMESTAMP` | NOT NULL | 事件时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_login_log_user_time` | `user_id, event_time` | 普通 | 按用户查登录历史（审计） |
| `idx_login_log_time_type` | `event_time, event_type` | 普通 | 按时间窗统计失败率（异常检测） |
| `idx_login_log_ip` | `client_ip, event_time` | 普通 | 同 IP 高频失败排查（口令猜测） |

**备注**：**本表无口令字段**。`username_input` 原样存储是为了支持"用户不存在"这类分析的
**内部定位**；对外响应统一（`R-AUTH-7`）。

---

#### TB-AUTH-11 `t_password_history` 口令历史

**用途**：最近 N 次口令哈希（**不得与最近 5 次相同**，`NFR-SEC-2`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `user_id` | `BIGINT` | NOT NULL, FK → `t_user(id)` | 用户 |
| `password_hash` | `VARCHAR(128)` | NOT NULL | 历史口令的加盐哈希 |
| `changed_time` | `TIMESTAMP` | NOT NULL | 修改时间 |
| `seq_no` | `INT` | NOT NULL | 序号（1 = 最近）；保留 `HG.AUTH.PASSWORD_HISTORY_SIZE` 条（默认 5） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_password_history` | `user_id, seq_no` | 唯一 | 每用户每序号唯一 |
| `idx_password_history_user_time` | `user_id, changed_time` | 普通 | 取最近 N 次（改口令时校验）；清理超出 N 的记录 |

---

#### TB-AUTH-12 `t_permission_change_log` 权限变更留痕

**用途**：角色 / 授权 / 子账户数据权限变更的完整留痕（含**前后值**与**复核人**）。**只增不改不删**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `change_target` | `VARCHAR(24)` | NOT NULL, CHECK | `USER_ROLE` / `AUTHORIZATION` / `SUB_ACCOUNT_PERM` / `USER_ATTR` / `ROLE_DEF` / `POSITION_CONFLICT` |
| `target_id` | `BIGINT` | NULL | 变更对象 ID（如 `t_authorization.id`） |
| `target_user_id` | `BIGINT` | NULL | 受影响的用户（便于按用户查历史） |
| `🔴子` `sub_account_id` | `INT` | NULL | 涉及的子账户（数据权限变更时非空） |
| `before_value` | `CLOB` | NULL | 变更前值（JSON 快照） |
| `after_value` | `CLOB` | NULL | 变更后值（JSON 快照） |
| `operator_id` | `BIGINT` | NOT NULL | 操作人 |
| `reviewer_id` | `BIGINT` | NULL | 复核人（**≠ `operator_id`**） |
| `change_reason` | `VARCHAR(500)` | NULL | 变更原因 |
| `rejected` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否被拒绝（拒绝的尝试也须留痕，`R-AUTH-8`） |
| `reject_reason` | `VARCHAR(500)` | NULL | 拒绝原因（岗位冲突 / 负责人禁授交易岗 / 系统管理员不得持业务角色） |
| `change_time` | `TIMESTAMP` | NOT NULL | 变更时间 |
| `dispatch_status` | `VARCHAR(16)` | NULL, CHECK | 是否已下发 C++（`PENDING`/`SUCCESS`/`FAIL`/`UNCONFIRMED`/`NOT_REQUIRED`） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_perm_chg_user_time` | `target_user_id, change_time` | 普通 | 按用户查权限变更历史（`FR-AUTH-11`、`FR-AUTH-18`） |
| `idx_perm_chg_target_time` | `change_target, change_time` | 普通 | 按变更类型查 |
| `idx_perm_chg_operator_time` | `operator_id, change_time` | 普通 | 按操作人查（内部审计） |
| `idx_perm_chg_rejected` | `rejected, change_time` | 普通 | 筛越权尝试（与 `t_unauthorized_attempt` 互补） |

---

#### TB-AUTH-13 `t_unauthorized_attempt` 越权尝试记录

**用途**：被系统拒绝的越权操作（岗位冲突、跨子账户写、负责人下单、系统管理员业务操作等）。**只增不改不删**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `operator_id` | `BIGINT` | NOT NULL, FK → `t_user(id)` | 操作人 |
| `attempt_type` | `VARCHAR(32)` | NOT NULL, CHECK | `POSITION_CONFLICT` / `LEADER_TRADE` / `CROSS_SUB_WRITE` / `SYSADMIN_BIZ_OP` / `DUAL_REVIEW_SELF` / `PERMISSION_DENIED` / `SELF_APPROVE` |
| `attempt_desc` | `VARCHAR(500)` | NOT NULL | 尝试的操作描述 |
| `target_resource` | `VARCHAR(256)` | NULL | 目标资源（API 路径 / 对象标识） |
| `🔴子` `sub_account_id` | `INT` | NULL | 涉及的子账户（跨子账户类越权必有值） |
| `reject_reason` | `VARCHAR(500)` | NOT NULL | 拒绝原因 |
| `reject_code` | `VARCHAR(32)` | NULL | 拒绝错误码（业务码 `10005` / 组件码 `0x100E` 等） |
| `attempt_time` | `TIMESTAMP` | NOT NULL | 尝试时间 |
| `client_ip` | `VARCHAR(64)` | NULL | 客户端 IP |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_unauth_attempt_user_time` | `operator_id, attempt_time` | 普通 | 按操作人查越权历史（审计重点） |
| `idx_unauth_attempt_type_time` | `attempt_type, attempt_time` | 普通 | 按类型统计（合规自查报告） |

**备注**：本表与 `t_permission_change_log` 中 `rejected='Y'` 的行**互补**：
前者记录"越权**操作**"（业务操作被拒），后者记录"越权**配置**"（权限配置被拒）。
两者共同满足 `FR-AUTH-18` 的"越权尝试记录"视图要求。

### 2.3 模块 `PRODUCT`（品种管理）—— 8 张

#### TB-PRODUCT-1 `t_product` 品种

**用途**：企业可套保品种的档案。**编码全系统唯一且不可修改**（`R-PRODUCT-3`）。
> ⚠️ **品种档案为全局共享**（不需子账户标识）；**白名单与额度为子账户级配置**（见 `TB-PRODUCT-5/6`）。
> 这是 `R-PRODUCT-7`「品种一份，白名单与额度多份」的结构落地。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `product_code` | `VARCHAR(32)` | **NOT NULL, UNIQUE** | 品种编码，**全系统唯一、一经使用不得修改** |
| `product_name` | `VARCHAR(128)` | NOT NULL | 品种名称（同一子账户内不得重复由 `TB-PRODUCT-5` + 应用层保证） |
| `category` | `VARCHAR(16)` | NOT NULL, CHECK | **品种分类**：`COMMODITY`（商品类 → 90% 红线）/ `TRADE`（贸易类 → 80% 红线） |
| `category_managed` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 是否纳入分类管理（8 号文）；`'Y'` 时 `category` **必须非空**（`ck_product_category_managed`） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `ENABLED` / `DISABLED`（**无 `DELETED`**：品种不允许物理删除） |
| `is_domestic_exchange` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 是否境内场内（**必须恒为 `'Y'`**：境外/场外永久排除，`PROJECT.md` §4.2） |
| `currency` | `VARCHAR(8)` | NOT NULL DEFAULT `'CNY'` | 计价币种（本项目仅 CNY） |
| `remark` | `VARCHAR(500)` | NULL | 备注 |
| `disabled_reason` | `VARCHAR(500)` | NULL | 停用原因（停用时必填） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_product_code` | `product_code` | 唯一 | **编码唯一性（`FR-PRODUCT-1` 验收 2）**；按编码查询与 CSV 导入校验 |
| `idx_product_status_category` | `status, category` | 普通 | 品种列表按状态/分类筛选（`FR-PRODUCT-5`）；规模红线按分类取比例 |
| `idx_product_name` | `product_name` | 普通 | 名称关键字检索 |

**备注**：
- `is_domestic_exchange` 恒为 `'Y'` 的 `CHECK` 使"**不得接入境外或场外衍生品**"
  （`R-TRADE-16`）在数据结构上不可被违反。
- **保留系统行 `id = 0`**（v1.3.3，`DC-041`）：`product_code = '__UNMAPPED__'`、
  `product_name = '未映射（系统保留）'`、`category = 'COMMODITY'`（**占位值**，不参与红线计算）、
  `status = 'DISABLED'`、`disabled_reason = '系统保留行：未映射实货敞口的品种哨兵值载体'`。
  该行**不是真实品种**：**不参与任何业务、不进入品种列表**（`idx_product_status_category` 只取 `ENABLED`）、
  **名下无任何业务数据行**，故**不影响规模红线与套保比例的分子/分母**。
  `t_exposure.product_id` 的**未映射哨兵值 `0`** 外键指向本行（`SET IDENTITY_INSERT` 显式插入，见 §0.5）。

---

#### TB-PRODUCT-2 `t_product_unit` 计量单位与换算关系

**用途**：品种在现货侧与期货侧的计量单位及换算关系（`FR-PRODUCT-13`）。
**换算关系缺失的品种不得启用**（`R-PRODUCT-6`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `spot_unit` | `VARCHAR(16)` | NOT NULL | 现货计量单位（`TON` / `KG` / `LOT` / `SHEET` / 自定义标签） |
| `futures_unit` | `VARCHAR(16)` | NOT NULL | 期货交易单位（如 `LOT`） |
| `convert_factor` | `DECIMAL(20,6)` | NOT NULL | 换算系数：`1 futures_unit = convert_factor spot_unit`；**必须 > 0**（`ck_product_unit_factor_positive`） |
| `convert_desc` | `VARCHAR(500)` | NULL | 换算关系文字说明（如"1 手 = 5 吨"） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `ENABLED` / `DISABLED` |
| `effective_from` | `DATE` | NOT NULL | 生效起始日（**换算关系变更不追溯重算历史**，`R-PRODUCT-6`） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_product_unit_eff` | `product_id, effective_from` | 唯一 | 同一品种同一生效日只有一份换算关系 |
| `idx_product_unit_product_status` | `product_id, status` | 普通 | 取当前生效换算关系（敞口折算与敞口/持仓同口径比较热路径） |

**备注**：`convert_factor` 的 `CHECK (> 0)` 使"换算系数填写 0 或负数时提交被拒绝"
（`FR-PRODUCT-13` 验收 2）成为数据库级保证（而非仅应用层）。

---

#### TB-PRODUCT-3 `t_product_contract` 品种-期货/期权合约关联

**用途**：品种与交易所挂牌合约的对应关系（`FR-PRODUCT-9`）。**期权与期货共用同一追溯链与同一套保额度**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `instrument_id` | `VARCHAR(32)` | NOT NULL | 合约代码（如 `cu2612`、`cu2612C70000`） |
| `instrument_type` | `VARCHAR(16)` | NOT NULL, CHECK | **`FUTURES` / `OPTION`**（必须显式标注） |
| `exchange_code` | `VARCHAR(16)` | NOT NULL | 交易所（`SHFE` / `DCE` / `CZCE` / `CFFEX` / `INE` / `GFEX`） |
| `contract_multiplier` | `DECIMAL(20,6)` | NOT NULL | 合约乘数（每手对应的标的数量） |
| `expiry_date` | `DATE` | NOT NULL | 合约到期日（期限红线校验输入） |
| `underlying_instrument_id` | `VARCHAR(32)` | NULL | 期权标的期货合约（`instrument_type='OPTION'` 时必填） |
| `strike_price` | `DECIMAL(20,6)` | NULL | 行权价（期权时必填） |
| `option_type` | `VARCHAR(8)` | NULL, CHECK | `CALL` / `PUT`（期权时必填） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `ENABLED` / `DISABLED` |
| `is_domestic_exchange` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | **必须恒为 `'Y'`** |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_product_contract` | `product_id, instrument_id` | 唯一 | **防重复关联（`FR-PRODUCT-9` 验收 2）** |
| `uk_contract_instrument` | `instrument_id` | 唯一 | **合约代码全局唯一**（合约必属唯一品种；保障 `0x1002` 合约-品种校验可判定） |
| `idx_contract_product_type_status` | `product_id, instrument_type, status` | 普通 | 下单合约下拉（按品种 + 类型取可用合约） |
| `idx_contract_expiry` | `expiry_date` | 普通 | 期限红线与到期提醒巡检 |

**备注**：`uk_contract_instrument` 是**必要约束**——若不唯一，"某合约属哪个品种"将不可判定，
事前风控的 `0x1002`（合约与品种不符）也无从校验。

---

#### TB-PRODUCT-4 `t_product_spot_category` 品种-现货品类映射

**用途**：**17 号文「期现一体」的前置**——实货敞口匹配到品种的**唯一入口**（`FR-PRODUCT-10`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `spot_category_code` | `VARCHAR(32)` | NOT NULL | 现货品类编码 |
| `spot_category_name` | `VARCHAR(128)` | NULL | 现货品类名称 |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 映射到的品种 |
| `mapping_direction` | `VARCHAR(16)` | NOT NULL, CHECK | `PURCHASE`（采购侧）/ `SALES`（销售侧）/ `BOTH`（通用） |
| `is_primary` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否主映射**；同一品类（+方向）**至多一个主映射**（应用层 + 唯一索引保证） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `ENABLED` / `DISABLED` |
| `change_desc` | `VARCHAR(500)` | NULL | 映射变更说明（**变更不得影响已成立的套保计划**，`FR-PRODUCT-10`） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_product_spot_cat` | `spot_category_code, product_id, mapping_direction` | 唯一 | 防重复映射 |
| `uk_product_spot_primary` | `spot_category_code, mapping_direction, is_primary`（条件唯一：仅 `is_primary='Y'`） | 唯一 | **"一个现货品类映射到两个品种时须指定主映射"** —— 主映射唯一性（`FR-PRODUCT-10` 验收 3）；DM 支持**条件唯一索引**，见 §6 |
| `idx_product_spot_product` | `product_id, status` | 普通 | 反向查"某品种对应哪些现货品类"（品种停用前置校验） |

**备注**：`is_primary` 允许多个品类映射到同一品种（正常），但**同一品类的主映射唯一**。
"该品类无映射"的场景**不落表**——由 `TB-SPOT-8` 计算时列入"未映射清单"（`FR-SPOT-14` 验收 2）。

---

#### TB-PRODUCT-5 `t_sub_product_whitelist` 子账户品种白名单

**用途**：子账户允许交易的品种集合（`FR-PRODUCT-12`、`R-PRODUCT-5`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `category_override` | `VARCHAR(16)` | NULL, CHECK | **品种级分类覆盖**（`Q-2` 已确认允许）：`COMMODITY` / `TRADE`；`NULL` = 继承子账户分类（`t_sub_account.category`） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `ENABLED` / `DISABLED`（移出白名单 = 置 `DISABLED`，**保留历史**） |
| `dispatch_status` | `VARCHAR(16)` | NOT NULL DEFAULT `'PENDING'`, CHECK | 下发 C++ 状态 |
| `dispatch_version` | `BIGINT` | NULL | 下发所用 `param_version` |
| `disabled_reason` | `VARCHAR(500)` | NULL | 移出原因 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_sub_product_wl` | `sub_account_id, product_id` | 唯一 | 防重复配置（`FR-PRODUCT-12`） |
| `idx_sub_product_wl_sub_status` | `sub_account_id, status` | 普通 | **构建该子账户的白名单集合（事前风控参数预加载热路径）** |
| `idx_sub_product_wl_dispatch` | `dispatch_status` | 普通 | 巡检未下发成功的白名单变更 |

**备注**：移出白名单前须校验**该子账户该品种无未平仓持仓**（`FR-PRODUCT-12` 验收 2）
—— 该校验跨 `t_position`（`TB-TRADE-5`）与计划表，属**服务层编排**（DB 不表达跨模块业务规则）。

---

#### TB-PRODUCT-8 `t_sub_instrument_quota` 限额层②（子账户 + 合约）

**用途**：**三层限额的层②**（`Q-16` 已确认三层全部保留，层②为**可选层**）。
用于对特定合约（如临近交割月）单独设限，防止单一子账户在某合约上过度集中。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `instrument_id` | `VARCHAR(32)` | NOT NULL, FK → `t_product_contract(instrument_id)` | 合约（层②的细分维度） |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种（冗余，便于按品种聚合与展示） |
| `quota_limit` | `DECIMAL(20,4)` | NOT NULL | 额度上限；**必须 ≥ 0**（`ck_sub_instr_quota_nonneg`） |
| `quota_unit` | `VARCHAR(16)` | NOT NULL | 额度单位（`LOT` / `TON`） |
| `used_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 已占用（**非权威源**，权威源为 C++ 内存） |
| `frozen_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 已冻结（下单未成交） |
| `effective_from` | `DATE` | NOT NULL | 生效起始日 |
| `effective_to` | `DATE` | NULL | 生效截止日 |
| `dispatch_status` | `VARCHAR(16)` | NOT NULL DEFAULT `'PENDING'`, CHECK | 下发 C++ 状态 |
| `dispatch_version` | `BIGINT` | NULL | 下发所用 `param_version` |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_sub_instrument_quota` | `sub_account_id, instrument_id, effective_from` | 唯一 | 同一子账户同合约同起点只有一份额度 |
| `idx_sub_instrument_quota_key` | `sub_account_id, instrument_id` | 普通 | **取层②额度（事前风控 `0x1017` 判定热路径）** |
| `idx_sub_instrument_quota_product` | `product_id` | 普通 | 按品种聚合展示三层限额全景 |
| `idx_sub_instrument_quota_dispatch` | `dispatch_status` | 普通 | 巡检未下发成功 |

**备注**：本表是 `Q-16`「三层限额全部保留」的层②落点；**层①与层③在 `TB-PRODUCT-6`**。
三层**同时校验**、**单笔按最细命中者**由 C++ 事前风控完成（`ARCHITECTURE.md` §3.3 `R-RISK-2`）。

---

#### TB-PRODUCT-6 `t_product_quota` 品种额度指标（子账户 × 品种）

**用途**：该品种在该子账户下允许占用的套保规模上限（`FR-PRODUCT-11`）。
**严禁跨品种、跨子账户借用或串用**（8 号文红线，`R-PRODUCT-4`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | **NOT NULL DEFAULT 0**, FK → `t_sub_account(id)` | 子账户（**额度不可跨子账户挪用**）；`limit_layer='ACCOUNT_PRODUCT'`（层③）时取**哨兵值 `0`（= 账户级）**（**不再使用 `NULL`**，`CHECK` 联动见 §4.3 `DC-020`） |
| `limit_layer` | `VARCHAR(24)` | NOT NULL, CHECK | **限额层级**（`Q-16` 已确认三层，本表承载层①与层③）：`SUB_PRODUCT`（**层①：子账户 + 品种，主维度**）/ `ACCOUNT_PRODUCT`（**层③：账户 + 品种，总账户防串用**）；**层②（子账户 + 合约）见 `TB-PRODUCT-8`**；**与 `sub_account_id` 双向联动见 §4.3 具名 `CHECK` `ck_product_quota_layer_sentinel`**（`ACCOUNT_PRODUCT ⇔ 0`；`SUB_PRODUCT ⇔ >0`） |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种（**额度不可跨品种挪用**） |
| `quota_limit` | `DECIMAL(20,4)` | NOT NULL | 额度上限；**必须 ≥ 0**（`ck_product_quota_nonneg`；负数被拒，`FR-PRODUCT-11` 验收 4） |
| `quota_unit` | `VARCHAR(16)` | NOT NULL | 额度单位（`LOT` / `TON`） |
| `used_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 已占用（**汇总口径**；权威源为 C++ 内存，本列由事件流回写，供展示与事后核对） |
| `frozen_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 已冻结（下单未成交） |
| `effective_from` | `DATE` | NOT NULL | 生效起始日 |
| `effective_to` | `DATE` | NULL | 生效截止日 |
| `dispatch_status` | `VARCHAR(16)` | NOT NULL DEFAULT `'PENDING'`, CHECK | 下发 C++ 状态 |
| `dispatch_version` | `BIGINT` | NULL | 下发所用 `param_version` |

**具名 `CHECK`（`G-11`，可执行 DDL 见 §4.3；此处为其表定义侧落点，三处须一致）**：

```sql
ALTER TABLE t_product_quota ADD CONSTRAINT ck_product_quota_layer_sentinel CHECK (
     (limit_layer = 'ACCOUNT_PRODUCT' AND sub_account_id = 0)
  OR (limit_layer = 'SUB_PRODUCT'     AND sub_account_id > 0)
);
```
> **作用域列例外（`G-11`）**：本表**同时含 A 类行级归属行（层① `SUB_PRODUCT`，`sub_account_id > 0`）与 B 类作用域行（层③ `ACCOUNT_PRODUCT`，哨兵 `0`）**，属**混合表**；故「五类业务数据行 `sub_account_id` 恒 `>0`」**不适用于其 B 类行**。该 `CHECK` **为双向蕴含 → 同时拒绝被排除组合 `(limit_layer='SUB_PRODUCT', sub_account_id=0)`**（测试用例「层①行以哨兵 `0` 写入 → 应被拒」的**数据库级判据**）。

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_product_quota` | `limit_layer, sub_account_id, product_id, effective_from` | 唯一 | **同层同范围同起点只有一份额度**（层③ `sub_account_id=0` **哨兵值**；**唯一性不依赖 `NULL` 语义**，见 §4.3 / §6.1 `D16`） |
| `idx_product_quota_sub_product` | `sub_account_id, product_id` | 普通 | **取层①额度（事前风控 `0x1004` 判定与限额使用统计热路径）** |
| `idx_product_quota_account_product` | `limit_layer, product_id` | 普通 | **取层③账户级额度（事前风控 `0x1005` 判定）** |
| `idx_product_quota_dispatch` | `dispatch_status` | 普通 | 巡检未下发成功 |

**备注**：
- `used_qty` / `frozen_qty` 是**内存权威值的库内投影**，用于展示与事后核对；
  **不得**据此做判定（判定只认 C++ 内存，`R-RISK-9`）。命名带 `_qty` 且注释明确"非权威源"。
- **三层限额的分布**：层①（子账户 + 品种，**主维度**）与层③（账户 + 品种）在本表；
  **层②（子账户 + 合约）在 `TB-PRODUCT-8`**。三层的**同时校验**由 C++ 事前风控完成
  （`ARCHITECTURE.md` §3.3 `R-RISK-2`、§4.4.1 `QuotaState`），**单笔按最细命中者**。

---

#### TB-PRODUCT-7 `t_product_change_log` 品种变更历史

**用途**：品种关键属性变更留痕（`FR-PRODUCT-14`）。**只增不改不删**；**不随品种删除而消失**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `change_type` | `VARCHAR(24)` | NOT NULL, CHECK | `CREATE` / `UPDATE` / `ENABLE` / `DISABLE` |
| `changed_field` | `VARCHAR(64)` | NULL | 变更字段名（`UPDATE` 时非空） |
| `before_value` | `VARCHAR(500)` | NULL | 变更前值 |
| `after_value` | `VARCHAR(500)` | NULL | 变更后值 |
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 涉及子账户（白名单/额度变更时非空；品种档案本体变更时为 `NULL`） |
| `operator_id` | `BIGINT` | NOT NULL | 操作人 |
| `change_time` | `TIMESTAMP` | NOT NULL | 变更时间 |
| `change_reason` | `VARCHAR(500)` | NULL | 变更/停用原因 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_product_chg_product_time` | `product_id, change_time` | 普通 | **按品种查变更历史（`FR-PRODUCT-14` 验收 1，倒序）** |
| `idx_product_chg_type_time` | `change_type, change_time` | 普通 | 按变更类型筛选 |
| `idx_product_chg_sub_time` | `sub_account_id, change_time` | 普通 | 审计岗跨子账户查询品种变更历史（`FR-PRODUCT-14` 验收 4） |

**备注**：`sub_account_id` 在此表**可为 `NULL`**（品种档案本体是全局共享的）；
但它仍**显式存在于行上**（不靠关联推导），符合 `INDEX.md` §2.1 的"显式携带"精神。

---

### 2.4 模块 `SPOT`（现货管理）—— 10 张

> ⚠️ **本模块是 17 号文「期现一体」的起点**：**实货敞口是根**，且**只能**由现货数据推导；
> **严禁**由期货成交记录反向创建或修改敞口（`R-SPOT-1`）。本模块**全部业务表**行级携带子账户标识。
>
> **定位边界**：本模块是「为套保服务的现货视图」，**不是完整 ERP**（`R-SPOT-7`）——
> 只有敞口相关字段与现货价；无采购审批流、供应商/客户管理、出入库作业、应收应付、生产排程。

#### TB-SPOT-1 `t_purchase_contract` 采购合同

**用途**：**买入方向**实货敞口的主要来源（`FR-SPOT-1`、`FR-SPOT-2`、`FR-SPOT-3`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **子账户归属（创建时必填，不可静默变更）**，`R-SPOT-2` |
| `contract_no` | `VARCHAR(32)` | NOT NULL | 合同编号（**同一子账户内唯一**，`uk_purchase_contract_no`） |
| `spot_category_code` | `VARCHAR(32)` | NOT NULL | 现货品类（经 `TB-PRODUCT-4` 映射到品种；无映射时**允许存草稿但不得提交为有效**） |
| `product_id` | `BIGINT` | NULL, FK → `t_product(id)` | 映射后的品种（无映射时为 `NULL`，并列入"未映射清单"） |
| `supplier_name` | `VARCHAR(128)` | NULL | 供应商名称（**仅文本说明，不做供应商管理**） |
| `total_qty` | `DECIMAL(20,4)` | NOT NULL | 合同总量；**必须 > 0** |
| `executed_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 已执行/已到货量 |
| `pricing_mode` | `VARCHAR(32)` | NULL | 计价方式（文本） |
| `contract_start_date` | `DATE` | NOT NULL | 合同起止日期（期限红线"实货合同期限"依据） |
| `contract_end_date` | `DATE` | NOT NULL | — |
| `exposure_direction` | `VARCHAR(16)` | NOT NULL DEFAULT `'BUY'`, CHECK | 敞口方向（采购侧默认 `BUY`） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `DRAFT` / `ACTIVE` / `CLOSED` / `VOID` |
| `void_reason` | `VARCHAR(500)` | NULL | 作废原因（作废时必填） |
| `closed_time` | `TIMESTAMP` | NULL | 执行完毕时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_purchase_contract_no` | `sub_account_id, contract_no` | 唯一 | **同一子账户内合同编号唯一（`FR-SPOT-1` 验收 4）**；不同子账户可重号 |
| `idx_purchase_contract_sub_status` | `sub_account_id, status` | 普通 | 合同列表按子账户 + 状态筛选（`FR-SPOT-4`）；敞口计算取 `ACTIVE` |
| `idx_purchase_contract_product` | `product_id, status` | 普通 | 按品种查敞口来源（敞口构成明细下钻） |
| `idx_purchase_contract_dates` | `contract_start_date, contract_end_date` | 普通 | 按合同期筛选；期限红线取"实货合同规定时间" |
| `idx_purchase_contract_cat` | `spot_category_code` | 普通 | 找"未映射品类"的单据（`FR-PRODUCT-10` 验收 2） |

**备注**：
- **草稿可物理删除**；`ACTIVE` 及之后**只能作废**（`R-SPOT-9`）。
- **子账户不可修改**（`FR-SPOT-2` 验收 3）：仅当**敞口未被任何生效计划引用**时，
  经 `FR-SPOT-16` 的专用接口变更，并写 `t_spot_change_log`。

---

#### TB-SPOT-2 `t_sales_contract` 销售合同/订单

**用途**：**卖出方向**实货敞口的主要来源（`FR-SPOT-5`、`FR-SPOT-6`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户归属（必填） |
| `contract_no` | `VARCHAR(32)` | NOT NULL | 合同编号（同一子账户内唯一） |
| `spot_category_code` | `VARCHAR(32)` | NOT NULL | 现货品类 |
| `product_id` | `BIGINT` | NULL, FK → `t_product(id)` | 映射后的品种 |
| `customer_name` | `VARCHAR(128)` | NULL | 客户名称（文本说明，**不做客户管理**） |
| `total_qty` | `DECIMAL(20,4)` | NOT NULL | 合同总量；**必须 > 0** |
| `delivered_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 已发货量 |
| `pricing_mode` | `VARCHAR(32)` | NULL | 计价方式 |
| `contract_start_date` | `DATE` | NOT NULL | 合同起止日期 |
| `contract_end_date` | `DATE` | NOT NULL | — |
| `exposure_direction` | `VARCHAR(16)` | NOT NULL DEFAULT `'SELL'`, CHECK | 敞口方向（销售侧默认 `SELL`） |
| `has_dual_direction` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否存在双向敞口**（如定价未定部分）；`'Y'` 时须在 `t_exposure` 生成两条方向不同的敞口（`FR-SPOT-5` 验收 3） |
| `buy_direction_qty` | `DECIMAL(20,4)` | NULL | 双向敞口下的买入方向数量 |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `DRAFT` / `ACTIVE` / `CLOSED` / `VOID` |
| `void_reason` | `VARCHAR(500)` | NULL | 作废原因 |
| `closed_time` | `TIMESTAMP` | NULL | 执行完毕时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_sales_contract_no` | `sub_account_id, contract_no` | 唯一 | 同一子账户内编号唯一（`FR-SPOT-5` 验收 4） |
| `idx_sales_contract_sub_status` | `sub_account_id, status` | 普通 | 列表筛选（`FR-SPOT-7`）；敞口计算取 `ACTIVE` |
| `idx_sales_contract_product` | `product_id, status` | 普通 | 按品种查敞口来源 |
| `idx_sales_contract_dates` | `contract_start_date, contract_end_date` | 普通 | 按合同期筛选 |

**备注**：`has_dual_direction='Y'` 时 `buy_direction_qty` 必填（`ck_sales_contract_dual`）——
把"双向敞口须显式标注方向拆分"（`FR-SPOT-5` 处理规则）下沉到结构。

---

#### TB-SPOT-3 `t_production_plan` 生产计划

**用途**：计划期内拟生产的产成品与所需原料（`FR-SPOT-8`），用于推导**双向**敞口。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户归属（必填） |
| `plan_no` | `VARCHAR(32)` | NOT NULL | 生产计划编号（同一子账户内唯一） |
| `plan_period_start` | `DATE` | NOT NULL | 计划期起（**计划期不得为空**） |
| `plan_period_end` | `DATE` | NOT NULL | 计划期止；**必须 ≥ 起始日**（`ck_prod_plan_period`） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `SCHEDULED`（已排产）/ `ADJUSTING`（调整中）/ `CLOSED`（已关闭） |
| `overlap_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 计划期是否与既有计划重叠（**提示重复**，不阻断；`FR-SPOT-8` 验收 3） |
| `closed_time` | `TIMESTAMP` | NULL | 关闭时间（**关闭后不再贡献新敞口，历史留痕**） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_prod_plan_no` | `sub_account_id, plan_no` | 唯一 | 同一子账户内编号唯一 |
| `idx_prod_plan_sub_status` | `sub_account_id, status` | 普通 | 取未关闭计划参与敞口计算 |
| `idx_prod_plan_period` | `plan_period_start, plan_period_end` | 普通 | **计划期重叠检测（`FR-SPOT-8` 验收 3）** |

---

#### TB-SPOT-4 `t_production_plan_line` 生产计划行

**用途**：生产计划的产成品 / 原料明细（多行）（`FR-SPOT-8`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户归属（**冗余自计划头，便于直接求和与权限过滤**） |
| `production_plan_id` | `BIGINT` | NOT NULL, FK → `t_production_plan(id)` | 所属计划 |
| `line_type` | `VARCHAR(16)` | NOT NULL, CHECK | `FINISHED`（产成品）/ `RAW_MATERIAL`（所需原料） |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种（**必须已建立映射**，否则不参与计算） |
| `quantity` | `DECIMAL(20,4)` | NOT NULL | 数量；**必须 > 0** |
| `unit` | `VARCHAR(16)` | NOT NULL | 单位 |
| `exposure_direction` | `VARCHAR(16)` | NOT NULL, CHECK | 推导出的敞口方向：`FINISHED` → `SELL`；`RAW_MATERIAL` → `BUY` |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_prod_plan_line_plan` | `production_plan_id, line_type` | 普通 | 按计划取原料/产成品行（敞口计算） |
| `idx_prod_plan_line_sub_product` | `sub_account_id, product_id` | 普通 | 按子账户 × 品种聚合敞口贡献 |

**备注**：本表**冗余 `sub_account_id`** 是为满足"**不得靠关联表推导子账户**"（`INDEX.md` §2.1）
与"可直接求和"的要求——即使不 join 计划头也能按子账户直接聚合。

---

#### TB-SPOT-5 `t_wip_record` 在制品记录

**用途**：处于生产过程中、尚未完工的存货（`FR-SPOT-8`）。其风险敞口介于原料与产成品之间。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户归属（必填） |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种（**必填**） |
| `production_plan_id` | `BIGINT` | NULL, FK → `t_production_plan(id)` | 关联的生产计划（可空） |
| `wip_qty` | `DECIMAL(20,4)` | NOT NULL | 在制品数量；**必须 ≥ 0** |
| `completion_ratio` | `DECIMAL(9,6)` | NOT NULL | **完工比例**（0–1）；敞口按 `wip_qty × completion_ratio` 折算（`R-SPOT-4`） |
| `exposure_direction` | `VARCHAR(16)` | NOT NULL, CHECK | 推导出的方向（默认 `BUY`，因在制品已锁定原料价格风险） |
| `record_date` | `DATE` | NOT NULL | 记录日期 |
| `unit` | `VARCHAR(16)` | NOT NULL | 单位 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_wip_sub_product_date` | `sub_account_id, product_id, record_date` | 普通 | 按子账户 × 品种取最新在制品（敞口计算） |
| `idx_wip_plan` | `production_plan_id` | 普通 | 按计划下钻在制品 |

**备注**：`completion_ratio` 的 `CHECK (completion_ratio >= 0 AND completion_ratio <= 1)`
使"完工比例折算"口径（`R-SPOT-4`）在结构上不可越界。

---

#### TB-SPOT-6 `t_inventory_ledger` 库存台账

**用途**：现货库存数量与位置（`FR-SPOT-9`）。**按记录日期形成序列，不覆盖历史**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户归属（必填） |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `inventory_type` | `VARCHAR(24)` | NOT NULL, CHECK | `RAW_MATERIAL`（原材料）/ `FINISHED`（产成品）/ `WIP`（在制品） |
| `quantity` | `DECIMAL(20,4)` | NOT NULL | 库存数量；**允许为负但须告警并要求二次确认**（`FR-SPOT-9` 验收 2） |
| `negative_confirmed` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 负值是否已经二次确认；`quantity < 0` 时**必须为 `'Y'`**（`ck_inventory_negative_confirmed`） |
| `unit` | `VARCHAR(16)` | NOT NULL | 单位 |
| `valuation` | `DECIMAL(20,6)` | NULL | 计价（可选，单价） |
| `record_date` | `DATE` | NOT NULL | 记录日期（**形成序列，不覆盖历史**） |
| `exposure_direction` | `VARCHAR(16)` | NOT NULL, CHECK | 推导方向：`RAW_MATERIAL`/`WIP` → `BUY`；`FINISHED` → `SELL` |
| `remark` | `VARCHAR(500)` | NULL | 备注（如账实不符说明） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_inventory_sub_product_type_date` | `sub_account_id, product_id, inventory_type, record_date` | 唯一 | 同子账户同品种同类型同日一条（重复录入提示） |
| `idx_inventory_sub_date` | `sub_account_id, record_date` | 普通 | **按子账户取最新库存（敞口计算与库存追溯）** |
| `idx_inventory_product_date` | `product_id, record_date` | 普通 | 按品种查库存变动序列（`FR-SPOT-9` 验收 3） |

**备注**：`ck_inventory_negative_confirmed` 把"录入负值必须二次确认"（`FR-SPOT-9` 验收 2）
变为**不可绕过**的结构约束——未确认的负库存**写不进库**。

---

#### TB-SPOT-7 `t_spot_price` 现货价

**用途**：指定品种在指定日期的现货价（`FR-SPOT-10`~`FR-SPOT-13`、`FR-SPOT-17`）。
**唯一外部输入渠道**（手工 / 批量粘贴 / CSV）；系统**不抓取、不订阅**（`R-SPOT-6`）。

> ⚠️ **`INDEX.md` §2.1 与 `SPOT.md` 数据字典的冲突处理（`RSK-05`，**已闭环**）**：
> §2.1 要求现货价记录携带子账户标识；`SPOT.md` 要求唯一口径为 `品种 + 日期 + 现货价 + 来源标注`。
> **本设计（口径 A，v1.2 `CH-003` 裁定并闭环）**：本表**必带** `sub_account_id`（**仅记录"录入归属"**，
> 满足 §2.1 的"显式携带"，**但不是业务归属维度**），并保留 `is_shared` 标志与配置 **`spot.price.scope`**（架构 `CFG-18`）：
> - **`spot.price.scope` 已收敛为唯一有效取值 `GLOBAL`**（全账户共享一份价格）：
>   唯一口径 = `(品种, 日期, is_shared='Y')`（即 `uk_spot_price_global`），其它子账户读取同一行。
> - **`PER_SUB_ACCOUNT` 已废弃、禁止启用**——保留该开关等于把合规红线交给配置（`CH-003`）。
> - 现货价是**单价、不参与加总**，因此不破坏 `Σ 子账户 = 真实账户`。
> 该冲突已由 `CH-003` 裁定闭环（`ARCHITECTURE.md` §12.2 `DEV-2`：状态**「已裁定并闭环」**）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **录入归属子账户**（显式携带，`INDEX.md` §2.1 敞口类） |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `price_date` | `DATE` | NOT NULL | 价格日期；**不得晚于当日**（`FR-SPOT-10` 验收 2，应用层校验） |
| `spot_price` | `DECIMAL(20,6)` | NOT NULL | 现货价；**必须 > 0**（`ck_spot_price_positive`） |
| `source_type` | `VARCHAR(24)` | NOT NULL, CHECK | `MANUAL`（手工录入）/ `BATCH_PASTE`（批量粘贴）/ `CSV_IMPORT`（CSV 导入） |
| `source_desc` | `VARCHAR(500)` | NULL | 业务说明补充（**仅允许补充，不允许修改系统自动记录项**，`FR-SPOT-17`） |
| `import_file_name` | `VARCHAR(256)` | NULL | CSV 导入文件名（`source_type='CSV_IMPORT'` 时非空） |
| `operator_id` | `BIGINT` | NOT NULL | **录入人**（系统自动记录，不可篡改） |
| `entry_time` | `TIMESTAMP` | NOT NULL | **录入时间**（系统自动记录，不可篡改） |
| `is_shared` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 是否全账户共享（`GLOBAL` 模式下为 `'Y'`） |
| `overwritten_price` | `DECIMAL(20,6)` | NULL | **被覆盖的原值**（`R-SPOT-3` 要求覆盖须留痕） |
| `overwrite_confirmed` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否经用户确认覆盖（`R-SPOT-3`：**不得静默覆盖**） |
| `price_unit` | `VARCHAR(16)` | NOT NULL | 价格单位（`CNY/TON` 等） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_spot_price_global` | `product_id, price_date, is_shared` | 唯一 | `GLOBAL` 模式下的唯一口径（同品种同日一条共享价；**全库现货价唯一键**） |
| `idx_spot_price_sub_product_date` | `sub_account_id, product_id, price_date` | 普通 | **按录入归属子账户查价格序列**（审计检索；`GLOBAL` 口径下价格行全账户共享，本索引仅用于定位录入归属，不再对应 `PER_SUB_ACCOUNT` 模式） |
| `idx_spot_price_product_date` | `product_id, price_date` | 普通 | **按品种取日期区间序列（最小方差法计算热路径，`FR-SPOT-13`）** |
| `idx_spot_price_source` | `source_type, entry_time` | 普通 | 审计按来源类型统计可信度（`FR-SPOT-17` 验收 4） |

**备注**：
- **口径 A（v1.2，`CH-003`/`Q-ARCH-001` 已裁定并闭环）**：现货价是**全账户共享的市场数据/单价**，
  **唯一口径 = 品种 + 日期 + 来源标注**（唯一键 `uk_spot_price_global`）；`sub_account_id` **仅记录入归属**、
  **不是业务归属维度**；`is_shared = 'Y'`。**`spot.price.scope` 已收敛为 `GLOBAL` 单值（`PER_SUB_ACCOUNT` 废弃、禁止启用）**。
  本表为**单价、不参与加总**，故不影响 `Σ 子账户 = 真实账户`（`RSK-05` / `DEV-2` 已闭环）。
- `ck_spot_price_positive`（`spot_price > 0`）使"录入价格为 0 或负数被拒绝"（`FR-SPOT-10` 验收 3）
  成为**数据库级**保证。
- "日期不得晚于当日"**不能**用 `CHECK` 表达（`CURRENT_DATE` 不确定性），由应用层校验并返回 `10001`。
- **覆盖留痕**：`overwritten_price` + `overwrite_confirmed` 同时服务于 `R-SPOT-3` 的
  "覆盖须留痕、不得静默覆盖"。原始录入人/时间**不被覆盖**（保留最近一次的"系统自动记录项"语义）。

---

#### TB-SPOT-8 `t_exposure` 实货敞口（**根实体**）

**用途**：企业因现货经营而暴露于价格波动风险的**数量与方向**（`FR-SPOT-14`、`FR-SPOT-15`）。
**17 号文「期现一体」的起点、全系统的数据源头、`PLAN` 的唯一输入**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **子账户归属（必填）** |
| `product_id` | `BIGINT` | **NOT NULL**（**恒非空**；例外态取**未映射哨兵值 `0`**，由具名 `CHECK` `ck_exposure_product_unmapped` 保证）, FK → `t_product(id)` | 品种。**已映射（`unmapped_flag='N'`）时恒为真实品种（`> 0`）**，须先完成映射（`TB-PRODUCT-4`）；**未映射（`unmapped_flag='Y'`）时取未映射哨兵值 `0`**（外键指向 `t_product` **保留系统行 `id=0`**，见 `TB-PRODUCT-1` 备注）并进"未映射清单"（`FR-SPOT-14` 验收 2）。**本列恒非空 → 其作为 `uk_exposure_key` 组成部分时唯一性不依赖 DM8 对 `NULL` 的处理**（与 `DC-020` / `G-5` 同向，`DC-041`）；表达式见本表备注 |
| `spot_category_code` | `VARCHAR(32)` | **NOT NULL**（**双态**，由具名 `CHECK` `ck_exposure_spot_category_scope` 保证） | **现货品类编码**（`DC-041` 补列）。**未映射（`unmapped_flag='Y'`）行**：取**真实现货品类**（来自来源单据 `TB-SPOT-1` / `TB-SPOT-2`），即"**哪个品类未映射**"的载体（`FR-PRODUCT-10` 验收 2）——**据此按品类分别留行**（`CH-008`）。**已映射（`unmapped_flag='N'`）行**：取**固定占位常量 `'__MAPPED__'`**，**不随来源品类变化** ⇒ **品类维度不参与该行的区分**（`CH-008`：已映射行仍按「子账户 × 品种 × 方向 × 口径 × 基准日」归并，同一品种下来自不同品类的现货数据**须合并为一条**，`FR-SPOT-14` 验收 1/2）。两态均**非空**（**不以 `NULL` 承载**）；占位常量为何不用"该品种主品类"、以及 DM8 可执行性见本表备注 |
| `direction` | `VARCHAR(16)` | NOT NULL, CHECK | **买入敞口（`BUY`）＝未来需买入，担心价格上涨**；**卖出敞口（`SELL`）＝未来需卖出，担心价格下跌** |
| `calc_mode` | `VARCHAR(32)` | NOT NULL, CHECK | **敞口计算口径**（`Q-17` 已确认**默认组合口径、含生产计划**）：`COMBINED`（默认）/ `CONTRACT_UNEXECUTED` / `INVENTORY_HELD` / `PRODUCTION_PLAN` |
| `🔴分量` `contract_unexecuted_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | **分量①已签合同未执行量**（`Q-17`：**快照必须存三类分量，不得只存合计**） |
| `🔴分量` `inventory_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | **分量②库存持有量** |
| `🔴分量` `production_plan_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | **分量③生产计划需求量** |
| `components_enabled` | `VARCHAR(128)` | NOT NULL | **本快照启用的分量集合**（快照配置：来自 `exposure.components`，**可按子账户/品种覆盖**）；被关闭的分量列恒为 `0` |
| `snapshot_id` | `VARCHAR(64)` | NOT NULL | **快照标识**（供事前风控下发引用与审计复现；`CMD_UPDATE_EXPOSURE.snapshot_id`） |
| `snapshot_time` | `TIMESTAMP` | NOT NULL | **快照时点**（`Q-3` 已确认：校验记录须标注所用快照时点） |
| `combined_components` | `VARCHAR(128)` | NULL | **组合口径的构成**（如 `CONTRACT_UNEXECUTED+INVENTORY_HELD`；`calc_mode='COMBINED'` 时必填，`R-SPOT-4`） |
| `as_of_date` | `DATE` | NOT NULL | **计算基准日期**（审计复现口径，`FR-SPOT-14` 验收 4） |
| `calc_time` | `TIMESTAMP` | NOT NULL | **计算时点**（`FR-SPOT-14` 验收 4） |
| `exposure_qty` | `DECIMAL(20,4)` | NOT NULL | 敞口数量（同口径换算后）；**必须 ≥ 0** |
| `unit` | `VARCHAR(16)` | NOT NULL | 数量单位（同口径后的单位） |
| `exposure_amount` | `DECIMAL(20,4)` | NULL | 敞口金额（**只能来自已录入现货价**；缺价时为 `NULL` 并标注，`R-SPOT-4`） |
| `price_missing_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否因缺价导致金额无法计算（`'Y'` 时 `exposure_amount` 必须为 `NULL`） |
| `price_as_of` | `DATE` | **NULL** | **估值所用现货价的实际时点**（`Q-12` + `R-SPOT-4` + `FR-ACCT-5`，**v1.2 补列，`PI-03`/`DC-026`**）：**敞口估值沿用最近一期价时必填**；与 `t_hedge_effect.price_as_of` **同名同义** |
| `non_current_price_flag` | `CHAR(1)` | **NOT NULL DEFAULT `'N'`**, CHECK | **是否使用了"非当日价"**（`Q-12`，v1.2 补列）：`'Y'` 时 `price_as_of < as_of_date`，结果**必须显式标记**"非当日价"；**不得**无标注静默沿用 |
| `snapshot_as_of` | `TIMESTAMP` | **NULL** | **三分量沿用最近一期的分量时点**（`Q-17` / `M1 #17`，v1.2 补列）：分量缺失而沿用历史分量时**必填**，与"非当期分量"标记成对出现 |
| `covered_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | **已被已批准计划引用的数量**（驱动状态流转） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `PENDING_HEDGE`（待套保）/ `PARTIALLY_COVERED`（部分覆盖）/ `COVERED`（已覆盖）/ `CLOSED`（已关闭） |
| `unmapped_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否属于"未映射"数据。**与 `product_id` 双向联动（`⇔`）**：`'Y'` ⇔ `product_id = 0`（未映射哨兵值；进"未映射清单"）；`'N'` ⇔ `product_id > 0`。由具名 `CHECK` `ck_exposure_product_unmapped` 保证（表达式见本表备注，`DC-041`） |
| `row_version` | `INT` | NOT NULL DEFAULT `0` | **乐观锁**：`covered_qty` 由计划绑定并发更新，须防丢失更新 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_exposure_key` | `sub_account_id, product_id, spot_category_code, direction, calc_mode, as_of_date` | 唯一 | **防重复计算产生重复敞口**。**两态语义不同**（`CH-008` / `FR-SPOT-14` 验收 1、2）：① **未映射行**（`product_id = 0`）：`spot_category_code` 为**真实品类** ⇒ **同一子账户下各未映射品类各占一行**（不得汇总为一行、不得以净额合并同品类买卖方向）；② **已映射行**（`product_id > 0`）：`spot_category_code` **恒为占位常量** ⇒ 唯一性**等价于「子账户 × 品种 × 方向 × 口径 × 基准日」**，**不因品类拆行**（同一品种下来自不同品类的现货数据须合并为一条）。**全列非空 → 唯一性不依赖 `NULL` 语义**（`DC-041`，与 `DC-020` / `G-5` 同向） |
| `idx_exposure_sub_status` | `sub_account_id, status` | 普通 | **敞口列表按子账户 + 状态筛选（`FR-SPOT-15`）；待套保敞口查询（计划编制入口）** |
| `idx_exposure_sub_product_dir` | `sub_account_id, product_id, direction` | 普通 | **按子账户 × 品种 × 方向取敞口（事前风控时点净持仓校验 `0x1009` 的口径来源）** |
| `idx_exposure_asof` | `as_of_date` | 普通 | 按基准日查敞口快照（审计复现） |
| `uk_exposure_snapshot` | `snapshot_id` | 唯一 | **快照标识唯一**（下发 C++ 与审计复现的引用键） |

**备注**：
- **本表是 `PLAN` 的上游且方向单向**（`R-PLAN-1`）：`t_hedge_plan_exposure.exposure_id` → 本表 `id`；
  **不存在**从 `t_trade_fill` 反向创建敞口的路径。
- **买卖不抵销**：`direction` 是主键组成部分 → 同一品种的买入与卖出敞口**各占一行**，
  **不得**用净额替代明细（`FR-SPOT-14` 验收 3）。净额仅用于时点净持仓校验（另一个计算，不落本表）。
- **`covered_qty` 的状态联动**：由 `R-SPOT-5` 定义——计划被驳回/撤销/终止时**立即回退**。
- **结构不变量（`CHECK`）—— 未映射 / 已映射双态自洽**（`DC-041`）：

  ```sql
  ALTER TABLE t_exposure ADD CONSTRAINT ck_exposure_product_unmapped CHECK (
       (unmapped_flag = 'Y' AND product_id = 0)
    OR (unmapped_flag = 'N' AND product_id > 0)
  );
  -- 品类列双态：已映射行恒取占位常量（不随来源品类变化）⇒ 不参与该行区分；
  --             未映射行恒取真实品类（不得占用占位常量）
  ALTER TABLE t_exposure ADD CONSTRAINT ck_exposure_spot_category_scope CHECK (
       (unmapped_flag = 'N' AND spot_category_code = '__MAPPED__')
    OR (unmapped_flag = 'Y' AND spot_category_code <> '__MAPPED__')
  );
  ```

  **与 `TB-TRADE-4` 的 `ck_trade_reason_related_or_missing` 属同一类**——均为**"标记列 ⇔ 取值列"的双向联动（`⇔`）**，
  但**承载例外态的取值方式不同**：
  ① **本表用未映射哨兵值 `0`**（`DC-041`）：**不以 `NULL` 承载例外态**，故 `uk_exposure_key` **全列非空**、
    其唯一性**不依赖 DM8 对 `NULL` 在唯一索引中的处理**（与 `DC-020` / `G-5` 同向）。该"未映射哨兵值"属**品种列**，
    与 §4.4 所称**子账户"作用域哨兵值"**（`sub_account_id = 0`）**不是同一概念、不共用取值域约束**；
  ② `TB-TRADE-4` 用 **`NULL` 条件非空**（`Q-13`，`DC-024`）：关联对象"未提供"即"无可取值"，故允许 `NULL`。
  二者**共同要求**：**不得出现"标记与取值不自洽"的行**（本表：`unmapped_flag` 与 `product_id`、及 `unmapped_flag` 与 `spot_category_code` 双态；该处：`related_object_missing_flag` 与 `related_*`）。
  因 `unmapped_flag` **非空**，本约束等价于 `(unmapped_flag = 'Y') ⇔ (product_id = 0)`，
  是**双向蕴含**：既强制两处允许组合，也**拒绝**两处被排除组合
  （`unmapped_flag='Y'` 而 `product_id > 0` / `unmapped_flag='N'` 而 `product_id = 0` 或 `< 0`）。
- **`ck_exposure_spot_category_scope` 的作用——让"品类只在未映射行区分"成为数据库级事实**：该约束强制
  **已映射行的 `spot_category_code` 恒等于占位常量 `'__MAPPED__'`** ⇒ 该列在 `uk_exposure_key` 中对已映射行是**常量**，
  唯一性**退化为「子账户 × 品种 × 方向 × 口径 × 基准日」**——`FR-SPOT-14` 验收 1 / `CH-008` 要求的
  "同一品种下来自不同现货品类的现货数据**须合并为一条、不得按品类拆行**"因此**由数据库保证**，而非仅靠服务层约定；
  未映射行则必须取**真实品类**（且不得占用占位常量）⇒ 各未映射品类**各占一行**（验收 2）。
- **占位常量为何取固定字面量、而不取"该品种的主品类"**：`TB-PRODUCT-4` 的 `uk_product_spot_primary`
  只保证"**同一品类**（+ 方向）的主映射唯一"，其备注明确**允许多个品类映射到同一品种**——
  故"该品种的主品类"**不是单值**；若已映射行按来源品类取"主品类"，同一品种的敞口仍会落到不同值上、**重新被拆行**。
  故取**与品种无关的固定常量 `'__MAPPED__'`**（同 `t_sub_account` 的 `__ALL_ACCOUNT__`、`t_product` 的 `__UNMAPPED__` 手法）。
- **DM8 可执行性（回答"是否需要表达式索引"）**：本方案**只用普通唯一索引 + 具名 `CHECK`**，
  **不需要**表达式索引 / 条件唯一索引（`CREATE UNIQUE INDEX ... WHERE`）；若后续确需条件唯一索引，DM8 亦支持
  （见 §6 `D6`，已登记；本库先例 `uk_product_spot_primary` / `uk_hedge_doc_current`）。
- **未映射清单的实际来源只有采购合同与销售合同**（`CH-008`）：`TB-SPOT-1` / `TB-SPOT-2` 的
  `spot_category_code` **`NOT NULL`** 即**为该路径而建**；`TB-SPOT-3` / `TB-SPOT-4`（生产计划）**以品种录入**、
  `product_id` 恒 `NOT NULL`，**不存在**"现货品类未映射"态 ⇒ **不需要**为其补品类列。
- **未映射行的语义边界**：`unmapped_flag='Y'` 的行**仅**承载"未映射清单"展示与映射补全入口
  （`FR-SPOT-14` 验收 2、`FR-PRODUCT-10` 验收 2），**不参与敞口计算、不参与计划绑定**（`R-PLAN-1`）；
  `product_id = 0` 指向 `t_product` **保留系统行 `id=0`**（该行**不是真实品种**，见 `TB-PRODUCT-1` 备注），
  故**外键恒有指向**——哨兵值**仍在引用完整性校验范围内**，**不适用** §0.4 的"可空外键"约定（`DC-023`）。
- **粒度口径已定（不再待确认）**：未映射行**按现货品类分别留行**、不得汇总为一行，也**不得**以净额合并同品类买卖方向
  （`direction` 恒为 `uk_exposure_key` 组成部分）；**品类维度仅在未映射行起区分作用**——依据 `CH-008`
  （`docs/REQUIREMENTS/SPOT.md`，未映射清单粒度澄清；其编号按全局序**推定**，待编排者登记确认）
  与 `FR-SPOT-14` 验收 1、2 / `FR-PRODUCT-10` 验收 2。

---

#### TB-SPOT-9 `t_exposure_detail` 敞口构成明细

**用途**：敞口**由哪些原始单据贡献、各贡献多少**（`FR-SPOT-15` 验收 1、`FR-SPOT-18` 验收 2）。
**这是"敞口可追溯到原始单据"的物理载体**，也是 `AUDIT` 追溯链起点的第一跳。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户归属（**冗余，支持不 join 直接按子账户聚合**） |
| `exposure_id` | `BIGINT` | NOT NULL, FK → `t_exposure(id)` | 所属敞口 |
| `component_type` | `VARCHAR(24)` | **NOT NULL**, CHECK | **该明细归属的敞口分量**（`Q-17`）：`CONTRACT_UNEXECUTED`（已签合同未执行量）/ `INVENTORY`（库存持有量）/ `PRODUCTION_PLAN`（生产计划需求量）；**`SUM(按分量分组) = t_exposure 对应分量列`**，这是"敞口构成可自证 + 分量与合计可互校"的结构保证** |
| `source_type` | `VARCHAR(24)` | NOT NULL, CHECK | `PURCHASE_CONTRACT` / `SALES_CONTRACT` / `PRODUCTION_PLAN` / `WIP_RECORD` / `INVENTORY_LEDGER`；与 `component_type` 的映射：`PURCHASE_CONTRACT`/`SALES_CONTRACT` → `CONTRACT_UNEXECUTED`；`INVENTORY_LEDGER` → `INVENTORY`；`PRODUCTION_PLAN`/`WIP_RECORD` → `PRODUCTION_PLAN` |
| `source_id` | `BIGINT` | NOT NULL | 来源单据 ID（多态外键，按 `source_type` 解释） |
| `source_no` | `VARCHAR(32)` | NULL | 来源单据编号（冗余，便于导出与追溯展示） |
| `contributed_qty` | `DECIMAL(20,4)` | NOT NULL | **该原始单据对敞口的贡献数量**；`SUM(contributed_qty) = t_exposure.exposure_qty` |
| `unit` | `VARCHAR(16)` | NOT NULL | 单位 |
| `direction` | `VARCHAR(16)` | NOT NULL, CHECK | 该贡献的方向（与敞口头一致） |
| `calc_as_of_date` | `DATE` | NOT NULL | 贡献计算所用的基准日 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_exposure_detail_exposure` | `exposure_id` | 普通 | **取敞口构成明细（`FR-SPOT-15` 验收 1、导出 `FR-SPOT-18`）** |
| `idx_exposure_detail_source` | `source_type, source_id` | 普通 | **反查"某单据贡献了哪些敞口"（`FR-SPOT-3` 作废前置校验、`FR-SPOT-16` 归属变更校验）** |
| `idx_exposure_detail_sub` | `sub_account_id, calc_as_of_date` | 普通 | 按子账户 × 基准日重算比对 |
| `idx_exposure_detail_component` | `exposure_id, component_type` | 普通 | **按分量聚合校验 `SUM(明细) = t_exposure 对应分量列`（`Q-17` 一致性核对与构成明细展示）** |

**备注**：本表**冗余 `sub_account_id` 与 `source_no`** 是刻意的：
`FR-SPOT-18` 的"含构成明细导出须同时导出原始单据标识以便追溯"要求明细**自身**可读，
而不必回查多张来源表。多态外键（`source_type` + `source_id`）**不用 DB 外键约束**
（一个字段无法指向多表）——由服务层在写入时校验（**这是本模型唯一的"软外键"处**，已在 §4.5 声明）。

---

#### TB-SPOT-10 `t_spot_change_log` 现货单据与归属变更留痕

**用途**：现货单据（合同/生产/库存/在制品）的变更、作废、**子账户归属变更**留痕（`FR-SPOT-2`、`FR-SPOT-16`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `source_type` | `VARCHAR(24)` | NOT NULL, CHECK | `PURCHASE_CONTRACT` / `SALES_CONTRACT` / `PRODUCTION_PLAN` / `WIP_RECORD` / `INVENTORY_LEDGER` |
| `source_id` | `BIGINT` | NOT NULL | 单据 ID |
| `change_type` | `VARCHAR(24)` | NOT NULL, CHECK | `CREATE` / `UPDATE` / `VOID` / `CLOSE` / `SUB_ACCOUNT_CHANGE` / `PRICE_OVERWRITE` / `COMPONENT_RECALC`（**分量重算留痕**：`#17` 展示侧对敞口三分量重算、告警但**不回写落库值**时写入；须与 §3.1 `COMPONENT_RECALC` 行、§4.4 敞口三分量行、`ARCHITECTURE.md` §6.10 / `CFG-17b` 一致；`DC-033`） |
| `sub_account_before` | `INT` | NULL | **变更前子账户**（归属变更时非空） |
| `sub_account_after` | `INT` | NULL | **变更后子账户** |
| `changed_field` | `VARCHAR(64)` | NULL | 变更字段名 |
| `before_value` | `VARCHAR(500)` | NULL | 变更前值 |
| `after_value` | `VARCHAR(500)` | NULL | 变更后值 |
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 当前子账户（便于按子账户检索；归属变更时取 `sub_account_after`）。**可空：品种档案本体变更时为 `NULL`**（`S-5` 补外键；可空外键在 `NULL` 时不参与引用完整性校验，见 §0.4） |
| `operator_id` | `BIGINT` | NOT NULL | 操作人 |
| `change_reason` | `VARCHAR(500)` | NULL | 变更/作废原因 |
| `change_time` | `TIMESTAMP` | NOT NULL | 变更时间 |
| `recalc_triggered` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否触发敞口重算（`FR-SPOT-16` 验收 1） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_spot_chg_source_time` | `source_type, source_id, change_time` | 普通 | 按单据查变更历史（追溯链"可识别变更时点"，`FR-SPOT-2` 验收 4） |
| `idx_spot_chg_sub_time` | `sub_account_id, change_time` | 普通 | 按子账户查变更（审计跨子账户查询） |
| `idx_spot_chg_type_time` | `change_type, change_time` | 普通 | 筛"归属变更"与"覆盖留痕" |

### 2.5 模块 `PLAN`（套保计划）—— 8 张

> ⚠️ **本模块是"交易正当性"的依据**：计划**必须**从实货敞口出发（`R-PLAN-1`），
> 且**计划、绑定敞口、交易计划、书面文件必须同属一个子账户**（`R-PLAN-7`）。
> 期权与期货**共用同一套保计划与同一套保额度**（`R-PLAN-11`）。

#### TB-PLAN-1 `t_hedge_plan` 套保计划

**用途**：由实货敞口出发编制、经审批的套期保值方案（`FR-PLAN-1`~`FR-PLAN-5`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **计划所属子账户（必填，不可更改）**，`R-PLAN-7` |
| `plan_no` | `VARCHAR(32)` | NOT NULL | **计划编号，全系统唯一，一经分配不得修改**（被交易指令绑定） |
| `plan_type` | `VARCHAR(16)` | NOT NULL, CHECK | `COMMODITY`（商品类套保 → 90% 红线）/ `TRADE`（贸易类套保 → 80% 红线） |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `hedge_direction` | `VARCHAR(16)` | NOT NULL, CHECK | `BUY_HEDGE`（买入套保，对冲卖出敞口）/ `SELL_HEDGE`（卖出套保，对冲买入敞口）；**必须与绑定敞口方向相反** |
| `plan_qty` | `DECIMAL(20,4)` | NOT NULL | 计划数量（与敞口同口径）；**必须 > 0 且 ≤ 绑定敞口的未覆盖数量**（应用层校验） |
| `unit` | `VARCHAR(16)` | NOT NULL | 单位 |
| `term_start_date` | `DATE` | NOT NULL | **计划期限起** |
| `term_end_date` | `DATE` | NOT NULL | **计划期限止**；`term_end_date ≤ min(term_start_date + CFG-03 的 12 个月, 实货合同到期日 + CFG-03 的 30 天宽限)`（**取较早者**；依据 `R-PLAN-3` ④ / `R-RISK-6`、`CFG-03`）。**勘误（`v1.3.4`）**：原表述为「`term_end_date ≤ term_start_date + 12 个月`（**或** ≤ 实货合同期限）」——`OR` 与 `R-PLAN-3` ④ 的「**min + 30 天宽限**」**松紧方向相反**（`OR` 会让"合同期长于 12 个月"的计划通过），已订正为 `min`；该列是**期限红线（`R-PLAN-3` ④）的取数依据**，依据错则服务端强制必错 |
| `hedge_ratio` | `DECIMAL(9,6)` | NOT NULL | 套保比率（`plan_qty / 敞口数量`）；**必须 ∈ (0, 1]**（`ck_hedge_plan_ratio`） |
| `ratio_method` | `VARCHAR(24)` | NOT NULL, CHECK | `SIMPLE`（简单比率法）/ `MIN_VARIANCE`（最小方差法）/ `CUSTOM_RULE`（自定义规则法） |
| `calc_base_date` | `DATE` | NOT NULL | 比率计算基准日 |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `DRAFT` / `PENDING_APPROVAL` / `APPROVED` / `FROZEN` / `COMPLETED` / `TERMINATED` |
| `executed_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 已成交数量（**由成交回报自动回写，不得手工填写**，`FR-PLAN-20`） |
| `ordered_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 已下达指令量（剩余可下单量 = `plan_qty − ordered_qty`） |
| `applicant_id` | `BIGINT` | NOT NULL | 发起人（**不得为审批人**，`R-PLAN-2`） |
| `current_doc_version` | `INT` | NOT NULL DEFAULT `0` | 当前书面文件版本号（指向 `TB-PLAN-4`） |
| `freeze_reason` | `VARCHAR(500)` | NULL | 冻结原因（冻结时必填） |
| `terminate_reason` | `VARCHAR(500)` | NULL | 终止原因（终止时必填） |
| `extend_basis` | `VARCHAR(500)` | NULL | 展期依据（实货合同期限依据；`R-PLAN-6`、`FR-PLAN-18`） |
| `quota_occupied` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否正在占用额度**（仅 `APPROVED`/`FROZEN` 为 `'Y'`；`R-PLAN-5`） |
| `row_version` | `INT` | NOT NULL DEFAULT `0` | 乐观锁（额度占用/释放、状态流转并发更新） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_hedge_plan_no` | `plan_no` | 唯一 | 计划编号全局唯一；交易指令按编号回溯 |
| `idx_hedge_plan_sub_status` | `sub_account_id, status` | 普通 | **计划列表筛选（`FR-PLAN-4`）；审批人"待我审批"集合** |
| `idx_hedge_plan_product_status` | `product_id, status` | 普通 | 品种停用前置校验（是否存在未结束计划）；限额使用统计 |
| `idx_hedge_plan_term` | `term_end_date, status` | 普通 | **到期提醒巡检（`CFG-11`）；期限红线复核** |
| `idx_hedge_plan_applicant` | `applicant_id, status` | 普通 | 审批岗过滤"待我审批"；发起人≠审批人核对 |
| `idx_hedge_plan_quota` | `quota_occupied, sub_account_id` | 普通 | **取占用额度的计划集合（额度占用/释放与下发）** |

**备注**：
- **`plan_no` 不可修改**：交易指令绑定它；改号等于断链。
- **状态与额度的关系**（`R-PLAN-5`）：`DRAFT`/`PENDING_APPROVAL` 不占额；`APPROVED`/`FROZEN` 占额；
  `TERMINATED`/`COMPLETED`/驳回至草稿释放未使用部分。`quota_occupied` 列使该规则**可被查询与校验**。
- **已批准计划不得直接修改要素**（`R-PLAN-6`）：状态机限制 + 服务层校验；任一要素变更须终止后新建。
- **`executed_qty > plan_qty` 为异常**：服务层须告警（`FR-PLAN-20` 验收 4）；数据库不阻止
  （因成交回报的写入优先于告警，强约束会导致回报落库失败）。

---

#### TB-PLAN-2 `t_hedge_plan_exposure` 计划-敞口绑定

**用途**：计划覆盖哪些实货敞口、各覆盖多少（`FR-PLAN-19`）。
**这是追溯链 `实货敞口 ↔ 套保计划` 的物理连接点**，也是 `AUDIT` 正反向追溯的关键一跳。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户（**必须与计划、与敞口三者一致**，`R-PLAN-7`） |
| `hedge_plan_id` | `BIGINT` | NOT NULL, FK → `t_hedge_plan(id)` | 计划 |
| `exposure_id` | `BIGINT` | NOT NULL, FK → `t_exposure(id)` | **被绑定的实货敞口（追溯链上游）** |
| `covered_qty` | `DECIMAL(20,4)` | NOT NULL | **本计划对应该敞口的覆盖数量**；`> 0` |
| `bind_time` | `TIMESTAMP` | NOT NULL | 绑定时间 |
| `unbind_time` | `TIMESTAMP` | NULL | 解绑时间（已批准计划解绑须走变更流程，`FR-PLAN-19` 验收 4） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `BOUND` / `UNBOUND`（解绑保留历史，不物理删除） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_plan_exposure` | `hedge_plan_id, exposure_id` | 唯一 | 同一计划对同一敞口只有一行（可累加覆盖量） |
| `idx_plan_exposure_exposure` | `exposure_id, status` | 普通 | **反查"该敞口被哪些计划覆盖"（`FR-SPOT-3` 作废前置校验、敞口状态流转、追溯链）** |
| `idx_plan_exposure_plan` | `hedge_plan_id, status` | 普通 | 取计划的绑定敞口清单（`FR-PLAN-5` 详情、`FR-AUDIT-1` 正向追溯） |
| `idx_plan_exposure_sub` | `sub_account_id, status` | 普通 | 跨子账户一致性核对（`R-PLAN-7` 校验与审计） |

**备注**：本表是 **N:M 连接表**，但**仍显式携带 `sub_account_id`** ——
因为 `INDEX.md` §2.1 明确禁止"用关联推导子账户"；且跨子账户一致性核对需要本列可查询。

---

#### TB-PLAN-3 `t_hedge_ratio_calc` 套保比率计算留痕

**用途**：三档方法的**计算过程留痕**（方法名、输入、输出、样本数、计算时点、操作人）。
**必须可被第三方复算**（`R-PLAN-4`、`FR-PLAN-7` 验收 3）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `hedge_plan_id` | `BIGINT` | NULL, FK → `t_hedge_plan(id)` | 关联计划（计算时计划可能尚未创建，故可空） |
| `exposure_id` | `BIGINT` | NULL, FK → `t_exposure(id)` | 关联敞口 |
| `ratio_method` | `VARCHAR(24)` | NOT NULL, CHECK | `SIMPLE` / `MIN_VARIANCE` / `CUSTOM_RULE` |
| `input_json` | `CLOB` | NULL | **输入参数快照**（拟套保数量、敞口数量、样本区间、自定义规则内容等） |
| `output_ratio` | `DECIMAL(9,6)` | NOT NULL | 输出比率 |
| `rho` | `DECIMAL(20,6)` | NULL | **相关系数 ρ**（最小方差法） |
| `sigma_spot` | `DECIMAL(20,6)` | NULL | **现货价标准差 σ_S** |
| `sigma_futures` | `DECIMAL(20,6)` | NULL | **期货价标准差 σ_F** |
| `sample_count` | `INT` | NULL | **样本数（交易日）**；最小方差法须 ≥ `HG.PLAN.MINVAR_MIN_SAMPLES`（默认 30） |
| `sample_start_date` / `sample_end_date` | `DATE` | NULL | 样本区间 |
| `custom_rule_id` | `BIGINT` | NULL, FK → `t_custom_hedge_rule(id)` | **命中的自定义规则**（`ratio_method='CUSTOM_RULE'` 时必填，指向 `TB-PLAN-8`；`Q-6`/`FR-PLAN-8`，v1.2 `DC-025`） |
| `custom_rule_desc` | `VARCHAR(500)` | NULL | 自定义规则内容与来源说明（`FR-PLAN-8` 验收 2）；**v1.2 起规则权威源为 `TB-PLAN-8`**，本列仅作计算留痕的自由说明 |
| `applied_ratio_cap` | `DECIMAL(9,6)` | NULL | **本次实际采用的比例上限**（`Q-6`；v1.2 `DC-025`）：`ratio_method='CUSTOM_RULE'` 时非空，**取自命中规则的 `ratio_cap`**，用于**复算**（"当时用的上限是多少"） |
| `applied_rule_id` | `BIGINT` | NULL, FK → `t_custom_hedge_rule(id)` | **多套规则叠加时最终采用的那一条规则**（v1.2 `DC-025`）：按 `TB-PLAN-8` 备注的**确定性取一算法**（**`ratio_cap` 升序（取最严者）→ `priority` 升序 → `id` 升序**）产生 |
| `hit_rule_count` | `INT` | NULL | **命中规则条数**（v1.2 `DC-025`）：`>1` 表示发生了多套规则叠加；与 `applied_rule_id` 共同构成**可复算、可审计**的留痕 |
| `result_code` | `VARCHAR(32)` | NOT NULL, CHECK | `SUCCESS` / `INSUFFICIENT_SAMPLE` / `SIGMA_FUTURES_ZERO` / `MISSING_PRICE` / `RATIO_OUT_OF_RANGE` / `RULE_MISSING` / `EXCEEDS_CUSTOM_CAP` |
| `fail_detail` | `VARCHAR(1000)` | NULL | 失败详情（**须列出缺失的品种与日期，不得静默跳过**，`FR-PLAN-7` 验收 4） |
| `operator_id` | `BIGINT` | NOT NULL | 操作人 |
| `calc_time` | `TIMESTAMP` | NOT NULL | 计算时点 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_ratio_calc_plan` | `hedge_plan_id, calc_time` | 普通 | **计划详情展示比率计算过程（`FR-PLAN-5` 验收 1，供审计复现）** |
| `idx_ratio_calc_exposure` | `exposure_id, calc_time` | 普通 | 按敞口查历史计算 |
| `idx_ratio_calc_method_time` | `ratio_method, calc_time` | 普通 | 按方法统计（合规分析：各方法使用比例） |
| `idx_ratio_calc_result` | `result_code, calc_time` | 普通 | 统计"样本不足"等失败原因分布（数据质量巡检） |

**备注**：`result_code` 与 `output_ratio NOT NULL` 的组合语义：
**只有 `SUCCESS` 才写入有效比率**；失败时 `output_ratio` 填 `0` 且 `result_code` 表明原因
——保证"**样本不足时不得返回任何比率结果**"（`FR-PLAN-7` 验收 2）在数据上不可伪造。

---

#### TB-PLAN-4 `t_hedge_doc` 套期关系书面文件（多版本）

**用途**：CAS 24 法定留痕文件，**三要素齐备方可生成；未生成的计划不得提交审批**（`R-PLAN-8`）。
**本表是本项目唯一承载该文件的地方**（`PLAN` 为所有者，`ACCT` 读取引用——避免两处各存一份）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `hedge_plan_id` | `BIGINT` | NOT NULL, FK → `t_hedge_plan(id)` | 关联计划 |
| `doc_no` | `VARCHAR(32)` | NOT NULL | 文件编号（全系统唯一） |
| `doc_version` | `INT` | NOT NULL | **版本号**（计划要素变更后重新生成并保留历史版本） |
| `is_current` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 是否当前版本（同一计划至多一个 `'Y'`，应用层 + 条件唯一索引保证） |
| `elem_formal_designation` | `CLOB` | **NOT NULL** | **要素①正式指定**（被套期项目 = 实货敞口标识；套期工具 = 期货/期权合约） |
| `elem_effectiveness_method` | `CLOB` | **NOT NULL** | **要素②有效性评估方法** |
| `elem_rebalance_mechanism` | `CLOB` | **NOT NULL** | **要素③再平衡机制** |
| `hedged_item_exposure_id` | `BIGINT` | NOT NULL, FK → `t_exposure(id)` | 被套期项目（实货敞口） |
| `hedging_instrument` | `VARCHAR(256)` | NOT NULL | 套期工具（合约 / 持仓标识） |
| `custom_rule_id` | `BIGINT` | NULL, FK → `t_custom_hedge_rule(id)` | **书面文件所依据的自定义规则**（CAS 24「书面文件载明有效性评估方法」；v1.2 `DC-025`）：`ratio_method='CUSTOM_RULE'` 时非空，使**书面文件可打印、可追溯到具体规则行**（规则编号 + 基数 + 比例上限 + 方向 + 生效期间 + 品种 + 子账户）；非自定义规则法为 `NULL` |
| `pending_external_review` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | **"待外部复核"标注（必须恒为 `'Y'`）**，`R-ACCT-10` |
| `generated_by` | `BIGINT` | NOT NULL | 生成人 |
| `generated_time` | `TIMESTAMP` | NOT NULL | 生成时间 |
| `invalidated_time` | `TIMESTAMP` | NULL | 失效时间（被新版本取代） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_hedge_doc_no` | `doc_no` | 唯一 | 文件编号唯一 |
| `uk_hedge_doc_current` | `hedge_plan_id, is_current`（条件唯一：仅 `is_current='Y'`） | 唯一 | **同一计划至多一个当前版本** |
| `idx_hedge_doc_plan_version` | `hedge_plan_id, doc_version` | 普通 | **取历史版本（`FR-PLAN-9` 验收 4、`FR-ACCT-1` 验收 4）** |
| `idx_hedge_doc_sub` | `sub_account_id, generated_time` | 普通 | 按子账户查文档（审计按子账户出证据） |

**备注**：
- **三要素列均为 `NOT NULL`** → "缺少再平衡机制时生成失败"（`FR-PLAN-9` 验收 2）在**结构上保证**：
  缺任一要素**写不进库**（而非依赖应用层判空）。
- `pending_external_review` 恒为 `'Y'` 的 `CHECK` 落地 `R-ACCT-10`
  "所有套期会计文档必须标注待外部复核，且标注不得被移除"。
- **所有者声明**：本表由 `PLAN` 模块写入（M1 交付）；`ACCT`（M4）**只读引用**并在
  `t_effectiveness_assess` / `t_rebalance_record` 中记录评估与再平衡（**不复制文档本体**）。

---

#### TB-PLAN-5 `t_plan_approval` 计划审批链

**用途**：审批节点记录（`FR-PLAN-10`、`FR-PLAN-11`、`FR-PLAN-12`）。**永久留痕、不可修改**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `hedge_plan_id` | `BIGINT` | NOT NULL, FK → `t_hedge_plan(id)` | 计划 |
| `node_seq` | `INT` | NOT NULL | 节点序号（支持多级审批） |
| `node_type` | `VARCHAR(24)` | NOT NULL, CHECK | `SUBMIT` / `APPROVE` / `REJECT` / `WITHDRAW` / `FREEZE` / `UNFREEZE` / `TERMINATE` / `EXTEND` |
| `applicant_id` | `BIGINT` | NOT NULL | 发起人 |
| `approver_id` | `BIGINT` | NULL | 审批人（**必须 ≠ `applicant_id`**，`R-PLAN-2`） |
| `approval_result` | `VARCHAR(16)` | NULL, CHECK | `APPROVED` / `REJECTED` / `WITHDRAWN` |
| `approval_comment` | `VARCHAR(1000)` | NULL | 审批意见 |
| `reject_reason` | `VARCHAR(500)` | NULL | **驳回原因（驳回时必填）**，`FR-PLAN-12` |
| `precheck_result` | `CLOB` | NULL | **提交前合规预校验结果**（逐项：规则编号 + 当前值 + 阈值 + 判定），`R-PLAN-3` |
| `action_time` | `TIMESTAMP` | NOT NULL | 动作时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_plan_approval_plan_seq` | `hedge_plan_id, node_seq` | 普通 | **按计划展示完整审批链（`FR-PLAN-5` 验收 3、追溯链"审批"段）** |
| `idx_plan_approval_approver` | `approver_id, action_time` | 普通 | 按审批人查历史；"待我审批"统计 |
| `idx_plan_approval_sub_time` | `sub_account_id, action_time` | 普通 | 按子账户查审批记录（审计） |

**备注**：`precheck_result` 用 `CLOB` 存**逐项校验结果**（结构化 JSON），
这是"阻断信息中包含具体规则编号、当前值与阈值"（`FR-PLAN-10` 验收 5）的**证据载体**。

---

#### TB-PLAN-6 `t_trade_plan` 交易计划

**用途**：套保计划分解出的可执行下单任务（`FR-PLAN-14`、`FR-PLAN-15`）。
**每笔交易指令必须绑定交易计划 ID**（`R-TRADE-1`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **子账户（必须与套保计划一致）**，`R-PLAN-7` |
| `trade_plan_no` | `VARCHAR(32)` | NOT NULL | 交易计划编号（全系统唯一） |
| `hedge_plan_id` | `BIGINT` | NOT NULL, FK → `t_hedge_plan(id)` | **所属套保计划（追溯链：交易 → 计划，必填）** |
| `exposure_id` | `BIGINT` | NOT NULL, FK → `t_exposure(id)` | **实货敞口 ID（随计划携带，供指令侧回溯）**；`FR-TRADE-1` |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `instrument_id` | `VARCHAR(32)` | NOT NULL | 合约代码（**必须属于该品种**） |
| `instrument_type` | `VARCHAR(16)` | NOT NULL, CHECK | `FUTURES` / `OPTION`（**期权与期货走同一机制**，`R-PLAN-11`） |
| `direction` | `VARCHAR(16)` | NOT NULL, CHECK | `BUY` / `SELL`（**必须与套保计划方向一致**） |
| `plan_qty` | `DECIMAL(20,4)` | NOT NULL | 该交易计划的数量；**所有交易计划合计 ≤ 套保计划数量**（`FR-PLAN-14` 验收 2） |
| `executed_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 已成交数量 |
| `ordered_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 已下达指令量 |
| `exec_deadline` | `DATE` | NOT NULL | 执行期限 |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `PENDING`（待执行）/ `EXECUTING`（执行中）/ `COMPLETED` / `CANCELLED` |
| `strategy_id` | `BIGINT` | NULL, FK → `t_option_strategy(id)` | 期权策略（多腿共用一个策略 ID） |
| `row_version` | `INT` | NOT NULL DEFAULT `0` | 乐观锁（并发下单更新 `ordered_qty`） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_trade_plan_no` | `trade_plan_no` | 唯一 | 编号唯一；指令按编号回溯 |
| `idx_trade_plan_sub_status` | `sub_account_id, status` | 普通 | **交易岗"待执行交易计划"列表（`FR-PLAN-15`，默认按执行期限升序）** |
| `idx_trade_plan_hedge_plan` | `hedge_plan_id` | 普通 | 按套保计划下钻交易计划（`FR-PLAN-5` 详情、`FR-AUDIT-1` 追溯） |
| `idx_trade_plan_exposure` | `exposure_id` | 普通 | **反向追溯：敞口 → 交易计划**（`FR-AUDIT-2`） |
| `idx_trade_plan_deadline` | `exec_deadline, status` | 普通 | 执行期限巡检与提醒 |
| `idx_trade_plan_strategy` | `strategy_id` | 普通 | 取期权策略下的各腿 |

**备注**：本表**同时携带 `hedge_plan_id` 与 `exposure_id`** 是**刻意的冗余**：
`FR-TRADE-1` 要求"指令须可回溯实货敞口"，若只经套保计划跳转，
则 `C++` 侧下发时的 `exposure_id` 字段需要额外查询——冗余后**交易路径无需 join**（虽在 Java 侧，但同样减少一次查询）。

---

#### TB-PLAN-7 `t_plan_state_log` 计划状态流转与额度占用留痕

**用途**：计划状态变更与**额度占用/释放**的记录（`R-PLAN-5`）。**只增不改不删**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `hedge_plan_id` | `BIGINT` | NOT NULL, FK → `t_hedge_plan(id)` | 计划 |
| `from_status` | `VARCHAR(24)` | NULL | 变更前状态 |
| `to_status` | `VARCHAR(24)` | NOT NULL | 变更后状态 |
| `quota_action` | `VARCHAR(16)` | NOT NULL, CHECK | `OCCUPY` / `RELEASE` / `NONE`（额度动作） |
| `quota_qty` | `DECIMAL(20,4)` | NULL | 占用/释放的额度数量 |
| `quota_product_id` | `BIGINT` | NULL, FK → `t_product(id)` | 涉及的品种 |
| `dispatch_status` | `VARCHAR(16)` | NOT NULL DEFAULT `'PENDING'`, CHECK | 额度变更下发 C++ 的结果（**失败则该变更不得视为生效**，`R-PRODUCT-9`） |
| `dispatch_version` | `BIGINT` | NULL | 下发所用 `param_version` |
| `operator_id` | `BIGINT` | NOT NULL | 操作人 |
| `reason` | `VARCHAR(500)` | NULL | 原因（冻结/终止/撤销/驳回必填） |
| `action_time` | `TIMESTAMP` | NOT NULL | 动作时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_plan_state_log_plan_time` | `hedge_plan_id, action_time` | 普通 | 按计划查状态流转历史（审批与额度审计） |
| `idx_plan_state_log_sub_time` | `sub_account_id, action_time` | 普通 | 按子账户查额度占用/释放明细（额度使用统计与审计） |
| `idx_plan_state_log_dispatch` | `dispatch_status` | 普通 | 巡检未下发成功的额度变更（防止内存与库不一致） |

**备注**：本表是 `R-PLAN-5`「额度占用与释放口径」的**唯一审计载体**：
额度不只是"数值"，而是**每一次 OCCUPY/RELEASE 都有记录**；下发失败可被巡检发现并重发。

---

#### TB-PLAN-8 `t_custom_hedge_rule` 自定义套保比例规则（**v1.2 新增，`PI-02`/`DC-025`**）

**用途**：承载 `Q-6` / `FR-PLAN-8` 的**自定义规则法**——"**固定比例上限 + 适用条件**"的**规则实体**
（**不做表达式引擎**）。计划编制与套保比率计算时，按本表命中规则并校验比例上限
（如"设定内控上限 80%"）。**支持多套规则叠加**（命中多条时的取一规则见备注）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `rule_code` | `VARCHAR(32)` | **NOT NULL, UNIQUE** | 规则编号（业务编号，应用层生成，`R-CONFIG-4` 口径；**书面文件引用用**） |
| `rule_name` | `VARCHAR(100)` | NOT NULL | 规则名称（如"贸易类内控上限 80%"） |
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户（规则按子账户配置） |
| `product_id` | `BIGINT` | NULL, FK → `t_product(id)` | 品种级；**`NULL` = 全品种适用**（"品种列表"由**多条规则行**表达：每个品种一行） |
| `direction` | `VARCHAR(8)` | NOT NULL, CHECK | **方向**：`BUY`（买入套保）/ `SELL`（卖出套保）/ `BOTH`（不限） |
| `basis` | `VARCHAR(24)` | NOT NULL, CHECK | **比例基数（与 `Q-17` 三分量口径对齐）**：`EXPOSURE_QTY`（实货敞口实物量）/ `CONTRACT_QTY`（合同量）/ `INVENTORY_QTY`（库存量） |
| `ratio_cap` | `DECIMAL(9,4)` | NOT NULL, CHECK | **比例上限**（小数存储，如 `0.8000` = 不超过 80%）；**必须 ∈ (0, 1]** |
| `priority` | `INT` | NOT NULL DEFAULT `100` | **优先级**（**数值小者优先**；并列时的第二次序） |
| `effective_from` | `DATE` | NOT NULL | **生效起始日** |
| `effective_to` | `DATE` | NULL | **生效截止日**（`NULL` = 长期有效）；`CHECK (effective_to IS NULL OR effective_to >= effective_from)` |
| `status` | `VARCHAR(16)` | NOT NULL, CHECK | `DRAFT`（草稿）/ `EFFECTIVE`（生效）/ `EXPIRED`（已过期） |
| `rule_desc` | `VARCHAR(500)` | NULL | 规则说明（**不承载规则本体**，本体已结构化；`FR-PLAN-8` 验收 2） |
| `approved_by` | `BIGINT` | NULL | **审批人**（内控红线类规则的放开须双人复核并留痕，`R-CONFIG-8` 口径） |
| `approved_time` | `TIMESTAMP` | NULL | 审批时间 |
| 通用审计字段 | — | — | 按 **§0.3**（`id` / `created_by` / `created_time` / `updated_by` / `updated_time` / `row_version`） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_custom_rule_code` | `rule_code` | 唯一 | 规则编号唯一（书面文件引用） |
| `idx_custom_rule_scope` | `sub_account_id, product_id, direction, status, effective_from` | 普通 | **取当前生效规则集（计划编制 / 比率计算热路径）** |
| `idx_custom_rule_product` | `product_id, status` | 普通 | **按品种查规则**（含 `product_id IS NULL` 的全品种规则） |

**备注**：
- **命中规则的选择算法（写死，保证确定性）**：候选 = `status='EFFECTIVE'` ∧ `sub_account_id` 匹配 ∧
  `direction` 匹配（`BOTH` 视为匹配）∧（`product_id IS NULL` 或 `product_id` = 被校验品种）
  ∧ 业务日期 ∈ `[effective_from, effective_to]`；
  命中多条时依次比较：**① `ratio_cap` 升序（取最严者 = 最小上限）→ ② `priority` 升序 → ③ `id` 升序取一**。
  **多套规则叠加语义 = 取最严（`min(ratio_cap)`），不得把多条上限相加或相乘。**
- **仅"固定比例上限 + 适用条件"，不做表达式引擎**（`Q-6` 已确认）。
- 本表**替代**原 `t_hedge_ratio_calc.custom_rule_desc`（单条文本，**无法承载结构化规则**）作为**规则权威源**；
  `custom_rule_desc` 保留为**计算留痕的自由说明**，其权威期规则经 `t_hedge_ratio_calc.custom_rule_id` 外键指向本表。
- **书面文件可打印**（CAS 24「书面文件载明有效性评估方法」）：`t_hedge_doc.custom_rule_id` 使书面文件
  可输出该规则的**人读文本**（规则编号 + 基数 + 比例上限 + 方向 + 生效期间 + 品种 + 子账户）并**追溯到具体规则行**。

---


### 2.6 模块 `TRADE`（交易执行）—— 9 张

> ⚠️ **本模块跨组件**：`C++` 负责下单、事前风控、指令流水与重放；
> `Java` 负责**事件流落库**（单一写库入口）。本模块的表**全部由 Java 写入**
> （数据来源为 C++ 的 `EVT_*` 事件流），`C++` **不写任何表**。
>
> **量级提示**：`t_trade_order` / `t_trade_order_report` / `t_trade_fill` / `t_trade_reason` /
> `t_command_flow` / `t_pre_trade_reject` 六张表在 5 年内可达**百万至千万级** → 索引必须齐备。

#### TB-TRADE-1 `t_trade_order` 交易指令

**用途**：交易岗提交的、拟发往 CTP 的下单请求（`FR-TRADE-1`、`FR-TRADE-2`）。
**必须绑定交易计划 ID 与子账户标识**（`R-TRADE-1`，协议级强制，见 `ARCHITECTURE.md` §4.2.4）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **子账户标识（必填；随指令显式携带，不得由会话/计划间接推导）**，`R-TRADE-1` |
| `client_order_ref` | `VARCHAR(32)` | **NOT NULL, UNIQUE** | Java 侧生成的**幂等键**（格式 `HG<yyyyMMdd><seq>`）；对应 `CMD_PLACE_ORDER.client_order_ref` |
| `order_id` | `BIGINT` | NULL, UNIQUE | **C++ 侧指令号**（`EVT_ORDER_ACCEPTED.order_id`）；未受理时为 `NULL` |
| `trade_plan_id` | `BIGINT` | **NOT NULL**, FK → `t_trade_plan(id)` | **交易计划 ID（必填；`0` 视为缺失 → 协议拒绝 `0x1020`）** |
| `hedge_plan_id` | `BIGINT` | NOT NULL, FK → `t_hedge_plan(id)` | 套保计划（冗余，便于追溯链快速跳转） |
| `exposure_id` | `BIGINT` | NOT NULL, FK → `t_exposure(id)` | **实货敞口 ID** |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `instrument_id` | `VARCHAR(32)` | NOT NULL | 合约代码 |
| `instrument_type` | `VARCHAR(16)` | NOT NULL, CHECK | `FUTURES` / `OPTION` |
| `direction` | `VARCHAR(16)` | NOT NULL, CHECK | `BUY` / `SELL` |
| `offset_flag` | `VARCHAR(16)` | NOT NULL, CHECK | `OPEN` / `CLOSE` / `CLOSE_TODAY` |
| `price_type` | `VARCHAR(16)` | NOT NULL, CHECK | `LIMIT` / `MARKET` |
| `limit_price` | `DECIMAL(20,6)` | NULL | 限价（`price_type='LIMIT'` 时必填） |
| `volume` | `INT` | NOT NULL | 手数；**必须 > 0** |
| `operator_id` | `BIGINT` | NOT NULL | 提交人（交易岗） |
| `strategy_id` | `BIGINT` | NULL, FK → `t_option_strategy(id)` | 期权策略 |
| `leg_no` | `INT` | NULL | 期权策略腿序号 |
| `trade_reason_id` | `BIGINT` | NULL, FK → `t_trade_reason(id)` | 交易理由（详见 `TB-TRADE-4`） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `PENDING_CONFIRM`（待确认）/ `REJECTED_PRECHECK`（事前风控拒绝）/ `SUBMITTED`（已提交）/ `SENT_CTP`（已发送）/ `PARTIAL_FILLED` / `ALL_FILLED` / `CANCELLED` / `REJECTED_CTP` / `EXPIRED` |
| `reject_rule_id` | `VARCHAR(32)` | NULL | 事前风控拒绝的**规则编号**（如 `R-RISK-4`） |
| `reject_code` | `VARCHAR(16)` | NULL | 拒绝错误码（`0x10xx`） |
| `reject_reason` | `VARCHAR(500)` | NULL | 拒绝原因 |
| `confirm_operator_id` | `BIGINT` | NULL | **人工确认人**（**必须与 `operator_id` 一致**，否则回 `0x0305`） |
| `confirm_time` | `TIMESTAMP` | NULL | 人工确认时间（留痕，`FR-TRADE-2` 验收 3） |
| `accept_time` | `TIMESTAMP` | NULL | C++ 受理时间（`EVT_ORDER_ACCEPTED.accepted_ts_ms`） |
| `submit_time` | `TIMESTAMP` | NOT NULL | 提交时间 |
| `client_ts_ms` | `BIGINT` | NOT NULL | 提交毫秒时间戳（端到端时延统计，`NFR-PERF-2`） |
| `expire_time` | `TIMESTAMP` | NULL | 待确认失效时间（`submit_time + HG.TRADE.CONFIRM_TIMEOUT_MS`） |
| `cancel_reason`（**v1.2 补列，`PI-12`/`DC-030`**） | `VARCHAR(500)` | NULL | **撤单原因**（`FR-TRADE-6` 的输入项；`status='CANCELLED'` 时必填，服务层校验）；**撤单须可自证**，与 `t_trade_reason` 同属"正当性证据" |
| `cancel_operator_id` / `cancel_time`（**v1.2 补列，`PI-12`/`DC-030`**） | `BIGINT` / `TIMESTAMP` | NULL | 撤单人与撤单时间（`status='CANCELLED'` 时非空） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_trade_order_client_ref` | `client_order_ref` | 唯一 | **幂等键唯一（重复指令不重复冻结额度，`0x2007`）** |
| `uk_trade_order_order_id` | `order_id` | 唯一 | C++ 指令号唯一；事件流按 `order_id` 关联 |
| `idx_trade_order_sub_status_time` | `sub_account_id, status, submit_time` | 普通 | **委托列表按子账户 + 状态 + 时间筛选（`FR-TRADE-7`）** |
| `idx_trade_order_plan` | `trade_plan_id` | 普通 | 按下单计划查指令（计划执行进度、可下单量核对） |
| `idx_trade_order_exposure` | `exposure_id` | 普通 | **反向追溯：敞口 → 指令**（`FR-AUDIT-2`） |
| `idx_trade_order_instrument_time` | `instrument_id, submit_time` | 普通 | 按合约查当日指令（异常下单行为分析） |
| `idx_trade_order_strategy` | `strategy_id, leg_no` | 普通 | 取期权策略各腿指令（`FR-TRADE-13`） |
| `idx_trade_order_pending` | `status, expire_time` | 普通 | **待确认超时巡检（失效处理）** |

**备注**：
- **本表在指令"已发出/已收回报"后才写入完整行**（`ARCHITECTURE.md` §4.2.6（4）第 ⑪ 步）。
  若 C++ 事前风控拒绝，则写入 `status='REJECTED_PRECHECK'` 的行（合规证据的一部分）；
  若人工确认超时，写入 `status='EXPIRED'`。
- **`order_id` 可空**（C++ 未受理时无号）+ `UNIQUE`：DM 的唯一约束**允许多个 `NULL`**
  （标准 SQL 语义），故不会冲突。

---

#### TB-TRADE-2 `t_trade_order_report` 委托（报单）回报

**用途**：与交易指令一一对应的柜台报单及其状态回报（`FR-TRADE-4`）。**幂等键 = `order_sys_id` + `status`**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `order_sys_id` | `VARCHAR(32)` | NOT NULL | **CTP 报单编号（幂等键）** |
| `order_id` | `BIGINT` | NOT NULL, FK → `t_trade_order(id)` | 关联指令 |
| `order_local_id` | `VARCHAR(32)` | NULL | CTP 本地报单编号 |
| `exchange_id` | `VARCHAR(16)` | NULL | 交易所 |
| `instrument_id` | `VARCHAR(32)` | NOT NULL | 合约 |
| `direction` | `VARCHAR(16)` | NOT NULL, CHECK | `BUY` / `SELL` |
| `offset_flag` | `VARCHAR(16)` | NOT NULL, CHECK | 开平标志 |
| `order_status` | `VARCHAR(32)` | NOT NULL | CTP 委托状态（映射到 `TB-TRADE-1.status` 的语义；**保留 CTP 原文语义**） |
| `limit_price` | `DECIMAL(20,6)` | NULL | 限价 |
| `volume_total` | `INT` | NOT NULL | 报单总量 |
| `volume_traded` | `INT` | NOT NULL DEFAULT `0` | 已成交量 |
| `volume_left` | `INT` | NOT NULL DEFAULT `0` | 未成交量 |
| `ctp_error_id` | `INT` | NULL | CTP 错误号（拒绝时） |
| `ctp_error_msg` | `VARCHAR(500)` | NULL | **CTP 错误信息原文**（`FR-TRADE-4` 验收 3） |
| `report_ts_ms` | `BIGINT` | NOT NULL | 回报毫秒时间戳 |
| `report_time` | `TIMESTAMP` | NOT NULL | 回报落库时间 |
| `event_seq` | `BIGINT` | NOT NULL | 事件流序号（缺口检测与补数比对） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_order_report_idem` | `order_sys_id, order_status, volume_traded` | 唯一 | **回报幂等（重复推送不重复计入，`FR-TRADE-4` 验收 2、`NFR-REL-5`）** |
| `idx_order_report_order` | `order_id, report_ts_ms` | 普通 | 取某指令的回报轨迹（状态追溯） |
| `idx_order_report_sub_time` | `sub_account_id, report_time` | 普通 | 委托列表按子账户 + 时间筛选（`FR-TRADE-7`） |
| `idx_order_report_instrument` | `instrument_id, report_time` | 普通 | 按合约查委托（异常行为分析、撤单定位） |

**备注**：`uk_order_report_idem` 用 `(order_sys_id, order_status, volume_traded)` 组合做幂等键，
而非仅 `order_sys_id` —— 因为**同一报单会多次回报**（不同状态、不同成交量），
但**同一状态 + 同一成交量**的重复推送才是真正的重复（这才是幂等的正确粒度）。

---

#### TB-TRADE-3 `t_trade_fill` 成交记录

**用途**：CTP 回报的实际成交明细（`FR-TRADE-5`）。**结算与审计的原始依据，不得修改、不得删除**。
**幂等键 = `trade_id`**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **子账户标识（由指令携带并随成交写入）**；**加总对账的关键维度** |
| `trade_id` | `VARCHAR(64)` | **NOT NULL, UNIQUE** | **CTP 成交编号（幂等键）** |
| `order_id` | `BIGINT` | NOT NULL, FK → `t_trade_order(id)` | 关联指令 |
| `order_sys_id` | `VARCHAR(32)` | NULL | CTP 报单编号 |
| `trade_plan_id` | `BIGINT` | NOT NULL, FK → `t_trade_plan(id)` | **交易计划（追溯链：成交 → 计划）** |
| `hedge_plan_id` | `BIGINT` | NOT NULL, FK → `t_hedge_plan(id)` | 套保计划（冗余） |
| `exposure_id` | `BIGINT` | NOT NULL, FK → `t_exposure(id)` | **实货敞口（追溯链终点/起点）** |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `instrument_id` | `VARCHAR(32)` | NOT NULL | 合约 |
| `instrument_type` | `VARCHAR(16)` | NOT NULL, CHECK | `FUTURES` / `OPTION` |
| `direction` | `VARCHAR(16)` | NOT NULL, CHECK | `BUY` / `SELL` |
| `offset_flag` | `VARCHAR(16)` | NOT NULL, CHECK | `OPEN` / `CLOSE` / `CLOSE_TODAY` |
| `fill_price` | `DECIMAL(20,6)` | NOT NULL | 成交价 |
| `fill_volume` | `INT` | NOT NULL | 成交手数；**必须 > 0** |
| `contract_multiplier` | `DECIMAL(20,6)` | NULL | 合约乘数（金额计算用；冗余避免 join） |
| `commission` | `DECIMAL(20,4)` | NULL | 手续费（CTP 回报或结算回填） |
| `trade_ts_ms` | `BIGINT` | NOT NULL | CTP 成交毫秒时间戳 |
| `trade_date` | `DATE` | NOT NULL | 交易日（结算与会计期间归属） |
| `fill_time` | `TIMESTAMP` | NOT NULL | 落库时间 |
| `event_seq` | `BIGINT` | NOT NULL | 事件流序号 |
| `settled_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否已纳入日终结算（防止漏结算/重复结算） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_trade_fill_id` | `trade_id` | 唯一 | **幂等（`FR-TRADE-5` 验收 4）**；外部对账的天然主键 |
| `idx_trade_fill_sub_date` | `sub_account_id, trade_date` | 普通 | **按子账户 × 交易日取成交（日终结算 ③④、加总对账、`FR-TRADE-18` 导出）** |
| `idx_trade_fill_plan` | `trade_plan_id, trade_date` | 普通 | **计划执行进度回写（`FR-TRADE-11`、`FR-PLAN-20`）** |
| `idx_trade_fill_order` | `order_id` | 普通 | 按指令取成交明细 |
| `idx_trade_fill_instrument_date` | `instrument_id, trade_date` | 普通 | 按合约 × 交易日（结算核对、异常行为分析） |
| `idx_trade_fill_settled` | `settled_flag, trade_date` | 普通 | **巡检未结算成交（防漏结算）** |
| `idx_trade_fill_exposure` | `exposure_id` | 普通 | 反向追溯：敞口 → 成交（`FR-AUDIT-2`） |

**备注**：本表**不提供修改/删除入口**（`R-TRADE-15`）；手续费与结算相关信息通过
`t_settle_pnl` 与 `t_settle_fund` 承载，**不回改成交记录本身**。

---

#### TB-TRADE-4 `t_trade_reason` 交易理由（正当性说明）

**用途**：说明本次交易的正当性（`FR-TRADE-10`）。**"每笔交易可自证正当性"的核心载体**。
`Q-13` 含**不可拆的两层**：① **提交时**关联对象必选（校验不通过即拒绝，`FR-TRADE-10` 验收 2，**服务层强制**）；
② **审计侧**「**理由缺失**」与「**关联缺失**」为**并列独立**的筛查维度（`DC-014`）。
故 `related_plan_id` / `related_exposure_id` **物理可空**，但受**条件非空 `CHECK`** 约束——
可空**仅**承载**补填态 / 异常态**，且**必带缺失标记**（见下表与备注；**v1.2 按产品经理 `CH-004` 裁定**）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `trade_order_id` | `BIGINT` | NOT NULL, FK → `t_trade_order(id)` | 关联指令 |
| `related_type` | `VARCHAR(32)` | **NOT NULL**, CHECK | **关联类型（结构化必填，`Q-13` 已确认）**：`NEW_EXPOSURE`（敞口新增）/ `EXPOSURE_DECREASE`（敞口减少）/ `PLAN_EXECUTION`（计划执行）/ `ROLLOVER`（展期置换）/ `RISK_DISPOSAL`（风险处置）/ `ERROR_CORRECTION`（错误更正）/ **`FORCE_CLOSE`（强制平仓，CTP 侧直接产生）/ `DELIVERY`（到期交割，CTP 侧直接产生）**（后两值于 v1.2 `DC-024` 增补，用于表达"**非本系统下单路径**"的成交场景，**不另设标记列**） |
| `related_plan_id` | `BIGINT` | **NULL**, FK → `t_hedge_plan(id)` | **关联对象①：计划 ID**；`Q-13`："**提交时关联对象必选**"（服务层强制）；**物理可空仅用于补填态/异常态**，且此时 `related_object_missing_flag='Y'`（条件 `CHECK` 保证） |
| `related_exposure_id` | `BIGINT` | **NULL**, FK → `t_exposure(id)` | **关联对象②：实货敞口 ID**；**必须与 `sub_account_id` 归属同一子账户**（`Q-13`，服务层校验，`0x1019`）；可空条件同① |
| `time_desc` | `VARCHAR(32)` | **NOT NULL**, CHECK | **时点说明（结构化必填，`Q-13`）**：`CONTRACT_SIGNED`（合同签约）/ `PRICE_TRIGGERED`（价格触发）/ `MONTHLY_REBALANCE`（月度调仓）/ `EXPIRY_ROLLOVER`（到期展期）/ `RISK_REQUIRED`（风控要求） |
| `reason_text` | `VARCHAR(1000)` | NULL | **可选补充说明**（`FR-TRADE-10`） |
| `forced_by_freq_alert` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否因**触发频繁开平仓（`Q-5`，≥6 次）而强制填写**（`Q-5` 与 `Q-13` 联动） |
| `related_object_missing_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否"关联对象缺失"**（**保留**，`DC-024` 撤销改名）：`'Y'` = 理由已填但**关联对象未提供**（补填态/异常态），此时两关联列**可为 `NULL`**；`'N'` = 关联对象齐备。**与 `reason_missing_flag` 并列独立**（`DC-014`），进 `AUDIT` 独立筛查维度 `RELATED_MISSING`（`FR-AUDIT-4`、`Q-13`） |
| `operator_id` | `BIGINT` | NOT NULL | 填写人 |
| `fill_time` | `TIMESTAMP` | NOT NULL | 填写时间 |
| `is_modified` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否被补填/修改过（**不得事后静默修改**，`R-TRADE-9`） |
| `before_code` / `before_text` | `VARCHAR(1000)` | NULL | 修改前值（保留前后值，`FR-TRADE-10` 验收 2） |
| `modified_by` / `modified_time` | — | — | 修改人与时间（可空） |
| `reason_missing_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否"理由缺失"**（进 `AUDIT` 违规筛查，`FR-TRADE-10` 验收 3、`FR-AUDIT-4`） |
| `missing_marked_time` | `TIMESTAMP` | NULL | 标记为"缺失"的时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_trade_reason_order` | `trade_order_id` | 唯一 | 一条指令一条理由 |
| `idx_trade_reason_sub_time` | `sub_account_id, fill_time` | 普通 | 按子账户查理由记录 |
| `idx_trade_reason_related_type` | `related_type` | 普通 | 按理由类型统计（合规分析：各理由分布） |
| `idx_trade_reason_missing` | `reason_missing_flag` | 普通 | **筛"理由缺失"交易（`FR-AUDIT-4` 独立筛查维度）** |
| `idx_trade_reason_related_missing` | `related_object_missing_flag` | 普通 | **筛"关联对象缺失"交易（`Q-13` 独立筛查维度）**；使 `RELATED_MISSING` 维度**可被索引直接命中** |
| `idx_trade_reason_related` | `related_plan_id, related_exposure_id` | 普通 | 从关联对象反查理由（审计自证：该计划/敞口的交易理由全集） |

**结构不变量（`CHECK`，v1.2 按产品经理 `CH-004` 裁定）**：
1. **`ck_trade_reason_related_or_missing`**：
   `((related_plan_id IS NOT NULL AND related_exposure_id IS NOT NULL) OR related_object_missing_flag = 'Y')`
   —— **关联对象齐备**，**或**显式标注**关联对象缺失**；二者必居其一，**不得出现"两列皆空且未标缺失"的行**。
2. `reason_missing_flag` 与 `related_object_missing_flag` **并列独立**（`DC-014`）：
   `REASON_MISSING` = 无理由记录／理由为空；`RELATED_MISSING` = 理由已填但**关联对象未提供**。
3. **「必选」强度由服务层保证，且校验时点为三层**（**不得只依赖 C++**，C++ 不访问 DB）：
   - ① **提交时**（权威）：`TradeService` 在**同一事务**内校验"两关联对象必填 + 与 `sub_account_id` 同子账户"；
     不通过 → **拒绝提交**（`FR-TRADE-10` 验收 2 原样保留；`Q-13` 校验不通过即拒绝）；
   - ② **下发前**：C++ 事前风控 `0x1019` 兜底复核（关联对象与指令子账户一致性）；
   - ③ **落库时**：`TradeService` 对理由记录**再复核**；异常/补填流程写库必须 `related_object_missing_flag='Y'`
     （由不变量 1 的 `CHECK` 强制），**并为该行生成 `RELATED_MISSING` 审计筛查命中项**
     （`t_violation_screen_item`，采用**筛查侧生成**方式，见 `TB-AUDIT-5` / `t_risk_violation`）。

**备注**：`reason_missing_flag` / `related_object_missing_flag` 使两个筛查维度
**均可被索引直接筛出**，不必全表判断——这是 `FR-AUDIT-4` 可执行性的结构前提，
也使 `Q-13` 的「关联缺失」**在结构上可达、可构造、可测**（v1.1 曾因 `related_*` 恒 `NOT NULL` 而**不可产生**）。
**C++ 侧直接产生的成交**（强平 / 交割 / 错单更正）如需区分，**通过 `related_type` 枚举扩展表达**
（新增枚举值须改 `CHECK` 并登记 `DC-`），**不另设标记列**（`DC-024`）。

---

#### TB-TRADE-5 `t_position` 持仓明细

**用途**：某子账户在某合约上的持仓明细（`FR-TRADE-9`）。
**期货公司侧持仓是合并的，拆分完全依赖本表按子账户记账**（`R-TRADE-8`）。

> 📌 **权威源声明**：本表是**落库投影**；交易路径的权威持仓在 **C++ 内存**（`ARCHITECTURE.md` §4.4.1）。
> 本表通过 `EVT_POSITION_CHANGE` 增量更新，用于展示、结算核对与加总对账。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **子账户（拆分维度，必填）** |
| `instrument_id` | `VARCHAR(32)` | NOT NULL | 合约 |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `direction` | `VARCHAR(16)` | NOT NULL, CHECK | `LONG`（多头）/ `SHORT`（空头） |
| `position_type` | `VARCHAR(16)` | NOT NULL, CHECK | `TODAY`（今仓）/ `YESTERDAY`（昨仓） |
| `position_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 持仓数量；**必须 ≥ 0** |
| `available_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 可用持仓（可平量）；**≤ `position_qty`** |
| `frozen_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 冻结（挂单占用）持仓 |
| `avg_open_price` | `DECIMAL(20,6)` | NULL | 持仓均价（浮动盈亏计算） |
| `last_settlement_price` | `DECIMAL(20,6)` | NULL | 最近结算价（结算与浮盈计算） |
| `hedge_plan_id` | `BIGINT` | NULL, FK → `t_hedge_plan(id)` | 持仓对应的**套保计划归属**（可能多计划，取主计划；细分见 `t_exposure_detail` 与成交链） |
| `as_of_time` | `TIMESTAMP` | NOT NULL | 该持仓数据的时点（来自 `EVT_POSITION_CHANGE.as_of_ts_ms`） |
| `event_seq` | `BIGINT` | NOT NULL | 事件流序号 |
| `row_version` | `INT` | NOT NULL DEFAULT `0` | 乐观锁（事件并发更新） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_position_key` | `sub_account_id, instrument_id, direction, position_type` | 唯一 | **每个子账户每合约每方向每仓型只有一行** |
| `idx_position_sub_instrument` | `sub_account_id, instrument_id` | 普通 | **按子账户查持仓（`FR-TRADE-9`、可用持仓校验、平仓量校验）** |
| `idx_position_instrument` | `instrument_id` | 普通 | **按合约汇总所有子账户（加总对账 `Σ 子账户 = 真实账户`，`FR-SETTLE-2`）** |
| `idx_position_plan` | `hedge_plan_id` | 普通 | 按计划查持仓归属（计划终止时的审计影响提示） |
| `idx_position_sub_product` | `sub_account_id, product_id` | 普通 | 时点净持仓与持仓上限校验的数据来源 |

**备注**：
- **汇总视图用 SQL 聚合或 `VIEW`，不落表**（`R-SETTLE-9` 明确"不得以汇总行 + 关联表替代子账户明细"）：
  ```sql
  CREATE VIEW v_position_summary_by_sub AS
  SELECT sub_account_id, instrument_id, direction,
         SUM(position_qty) AS position_qty, SUM(available_qty) AS available_qty
  FROM t_position GROUP BY sub_account_id, instrument_id, direction;
  ```
  全账户汇总则由 `GROUP BY instrument_id, direction` 聚合得到——`Σ 子账户明细 = 汇总`**天然成立**。
- `available_qty ≤ position_qty` 与 `frozen_qty ≤ position_qty` 由 `CHECK` 保证
  （`ck_position_qty_consistency`）。

---

#### TB-TRADE-6 `t_pre_trade_reject` 事前风控拒绝记录

**用途**：被事前风控拒绝的指令及其规则编号与原因（`FR-TRADE-17`、`FR-RISK-11`）。
**合规证据，必须经事件流上报 Java 落库，只增不改不删**（`R-TRADE-14`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `client_order_ref` | `VARCHAR(32)` | NOT NULL | 被拒指令引用（幂等键） |
| `order_digest` | `VARCHAR(500)` | NOT NULL | **指令要素摘要**（供审计在无完整指令行时还原场景） |
| `product_id` | `BIGINT` | NULL, FK → `t_product(id)` | 品种 |
| `instrument_id` | `VARCHAR(32)` | NULL | 合约 |
| `direction` | `VARCHAR(16)` | NULL, CHECK | 方向 |
| `volume` | `INT` | NULL | 数量 |
| `rule_id` | `VARCHAR(32)` | **NOT NULL** | **规则编号**（如 `R-RISK-4`）；**提示必须含规则编号**（`NFR-USE-3`） |
| `reject_code` | `VARCHAR(16)` | NOT NULL | 错误码（`0x10xx` 段） |
| `limit_layer` | `VARCHAR(24)` | **NULL**, CHECK | **命中的限额层级**（`Q-16`；**v1.2 补列，`PI-01`/`DC-027`**）：`SUB_PRODUCT`（层①）/ `SUB_INSTRUMENT`（层②）/ `ACCOUNT_PRODUCT`（层③）/ `AUTH_QUOTA`（授权额度）/ `NONE`（**非限额类拒绝**，如价格偏离、规模红线等）。**取值域与 `ARCHITECTURE.md` §4.2.4 `EVT_PRE_TRADE_REJECT.limit_layer` 完全同域（5 值）**；**非限额类拒绝一律填 `NONE`**；列可空，**`NULL` 仅表示该拒绝记录未命中任何规则层级**（历史 / 导入数据），**不再用于表达"非限额类拒绝"**；**命中多层时按 `R-RISK-2` 全序取最细者**（层②>层①>层③）。v1.1 仅登记了索引 `idx_reject_rule_layer` **而未登记本列** → 建表脚本会报错，本版补记 |
| `exposure_snapshot_id` | `BIGINT` | **NULL** | **判定所用实货敞口快照的 ID**（`Q-3`；**v1.2 补列，`PI-05`/`DC-028`**）：指向 `t_exposure.snapshot_id`；非敞口相关拒绝（如价格偏离）为 `NULL` |
| `exposure_snapshot_time` | `TIMESTAMP` | **NULL** | **判定所用实货敞口快照的时点**（`Q-3`；**v1.2 补列，`PI-05`/`DC-028`**）："校验记录须标注敞口快照时点"的落点，使事前校验所依据的敞口数据**可在审计中独立复现** |
| `current_value` | `VARCHAR(64)` | NOT NULL | **当前值**（如规模红线使用率 `"0.93"`、价格偏离幅度 `"0.0612"`）；无适用值时 `"-"` |
| `threshold_value` | `VARCHAR(64)` | NOT NULL | **阈值**（如 `"0.90"`；价格偏离告警线 `"0.02"` / 阻断线 `"0.05"`）；无适用值时 `"-"` |
| `base_price_used` | `DECIMAL(20,6)` | NULL | **判定所用的基准价快照**（`Q-15`：价格偏离的基准 = **CTP 昨结算价**，`risk.price.base=PREV_SETTLEMENT`；非价格偏离类拒绝为 `NULL`）——使偏离幅度可在审计中**独立复算** |
| `base_price_source` | `VARCHAR(24)` | NULL, CHECK | 基准价来源（`Q-15`）：`PREV_SETTLEMENT`（昨结算价）/ `NOT_APPLICABLE`（市价单不适用偏离校验，须显式标注） |
| `reject_reason` | `VARCHAR(500)` | NOT NULL | 拒绝原因（含维度区分：子账户 / 总账户） |
| `operator_id` | `BIGINT` | NOT NULL | 操作人 |
| `reject_ts_ms` | `BIGINT` | NOT NULL | 拒绝毫秒时间戳（C++ 侧） |
| `reject_time` | `TIMESTAMP` | NOT NULL | 落库时间（Java 侧） |
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `event_seq` | `BIGINT` | NOT NULL | 事件流序号 |
| `reported_retry_count` | `INT` | NOT NULL DEFAULT `0` | 上报重试次数（`FR-RISK-11` 验收 2：上报失败须重试并最终一致） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_pre_trade_reject_idem` | `client_order_ref, rule_id` | 唯一 | **幂等（同指令同规则只记一条）** |
| `idx_reject_sub_time` | `sub_account_id, reject_time` | 普通 | **按子账户 + 时间查拒绝记录（`FR-TRADE-17`）** |
| `idx_reject_rule_time` | `rule_id, reject_time` | 普通 | **按规则编号统计触发次数（`FR-TRADE-17` 验收 2、事后风控违规汇总）** |
| `idx_reject_date` | `trade_date` | 普通 | 按交易日归集（事后风控与风控日报） |
| `idx_reject_operator` | `operator_id, reject_time` | 普通 | 按操作人查（合规行为分析） |
| `idx_reject_rule_layer` | `rule_id, limit_layer` | 普通 | **按"规则 × 命中限额层级"统计（`Q-16`：核验三层限额各自被触发的频次）** |

**备注**：`current_value` / `threshold_value` 的 `NOT NULL` 使
"阻断提示必须含规则编号 + 当前值 + 阈值三项"（`NFR-USE-3`）在**数据层不可缺失**
（无适用值也必须写 `"-"`，而不是留空）。

---

#### TB-TRADE-7 `t_command_flow` 指令流水镜像

**用途**：指令从创建到终态的**完整生命周期轨迹**（`FR-TRADE-16`）。
> ⚠️ **权威源声明**：权威源是 **C++ 侧的 append-only 流水文件**（`ARCHITECTURE.md` §4.5.2）。
> 本表是 **Java 侧镜像**，由 `EVT_COMMAND_FLOW` 落库，用于支持"查询不改变状态"的只读检索。
> 若镜像与 C++ 流水不一致，**以 C++ 流水为准**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `order_id` | `BIGINT` | NULL | C++ 指令号 |
| `client_order_ref` | `VARCHAR(32)` | NOT NULL | 幂等键（未受理也能关联） |
| `flow_type` | `VARCHAR(24)` | NOT NULL, CHECK | `CREATED` / `REJECTED` / `ACCEPTED` / `CONFIRMED` / `SENT` / `REPORTED` / `PARTIAL_FILLED` / `FILLED` / `CANCELLED` / `EXPIRED` |
| `flow_detail` | `VARCHAR(1000)` | NULL | 该阶段详情（CTP 错误、状态原文等） |
| `flow_ts_ms` | `BIGINT` | NOT NULL | 事件毫秒时间戳 |
| `flow_time` | `TIMESTAMP` | NOT NULL | 落库时间 |
| `event_seq` | `BIGINT` | NOT NULL | 事件流序号 |
| `replay_marker` | `VARCHAR(16)` | NULL, CHECK | 是否来自重放：`LIVE` / `REPLAY`（崩溃恢复期间补推的事件） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_command_flow_idem` | `client_order_ref, flow_type, flow_ts_ms` | 唯一 | **幂等（重放可能重复投递）** |
| `idx_command_flow_order` | `order_id, flow_ts_ms` | 普通 | **按指令号取完整轨迹（`FR-TRADE-16` 验收 1）** |
| `idx_command_flow_ref` | `client_order_ref, flow_ts_ms` | 普通 | 未受理指令（无 `order_id`）的轨迹查询 |
| `idx_command_flow_sub_time` | `sub_account_id, flow_time` | 普通 | 按子账户 + 时间筛选流水 |
| `idx_command_flow_type_time` | `flow_type, flow_time` | 普通 | 按阶段类型统计（如"已发出但长时间无回报"稽核） |

---

#### TB-TRADE-8 `t_option_strategy` 期权策略

**用途**：期权多腿策略的策略头（`FR-TRADE-13`）。**各腿绑定同一套保计划 ID，额度占用合计校验**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `strategy_no` | `VARCHAR(32)` | NOT NULL | 策略编号（全系统唯一） |
| `strategy_type` | `VARCHAR(24)` | NOT NULL, CHECK | `COLLAR`（领口）/ `STRADDLE` / `STRANGLE` / `BUTTERFLY` / `CUSTOM` |
| `hedge_plan_id` | `BIGINT` | NOT NULL, FK → `t_hedge_plan(id)` | **同一套保计划（同链同额）**，`R-TRADE-12` |
| `trade_plan_id` | `BIGINT` | NOT NULL, FK → `t_trade_plan(id)` | 交易计划 |
| `leg_count` | `INT` | NOT NULL | 腿数 |
| `total_quota_occupied` | `DECIMAL(20,4)` | NULL | **各腿合计占用的套保额度**（`FR-TRADE-13` 验收 2） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `PENDING` / `PARTIAL`（部分腿成交）/ `COMPLETED` / `CANCELLED` |
| `operator_id` | `BIGINT` | NOT NULL | 提交人 |
| `submit_time` | `TIMESTAMP` | NOT NULL | 提交时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_option_strategy_no` | `strategy_no` | 唯一 | 编号唯一 |
| `idx_option_strategy_hedge_plan` | `hedge_plan_id` | 普通 | **核验"各腿绑定同一套保计划"（`FR-TRADE-13` 验收 1）** |
| `idx_option_strategy_sub_status` | `sub_account_id, status` | 普通 | 按子账户查策略（部分成交须明确展示） |

**备注**：各腿通过 `t_trade_order.strategy_id` + `leg_no` 关联（见 `TB-TRADE-1`）。

---

#### TB-TRADE-9 `t_replay_log` 重放记录

**用途**：崩溃恢复的启动重放过程与结果（`FR-TRADE-15`）。**重放条数与结果可审计**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `component_id` | `VARCHAR(64)` | NOT NULL | 组件标识（`hg-trading`） |
| `replay_seq` | `INT` | NOT NULL | 该组件第几次重放 |
| `trigger_source` | `VARCHAR(24)` | NOT NULL, CHECK | `STARTUP` / `MANUAL`（`CMD_TRIGGER_REPLAY`） |
| `state` | `VARCHAR(24)` | NOT NULL, CHECK | `NOT_READY` / `REPLAYING` / `READY` / `FAILED` |
| `replayed_records` | `BIGINT` | NOT NULL DEFAULT `0` | 已重放记录数 |
| `total_records` | `BIGINT` | NOT NULL DEFAULT `0` | 总记录数 |
| `in_flight_orders` | `INT` | NOT NULL DEFAULT `0` | **在途指令数**（已发 CTP 未收回报） |
| `ctp_matched_count` | `INT` | NOT NULL DEFAULT `0` | 与 CTP 侧对账匹配数 |
| `ctp_mismatch_count` | `INT` | NOT NULL DEFAULT `0` | 与 CTP 侧对账不一致数 |
| `mismatch_detail` | `CLOB` | NULL | 不一致明细（`0x0405` 的依据） |
| `started_ts_ms` | `BIGINT` | NOT NULL | 开始时间 |
| `finished_ts_ms` | `BIGINT` | NULL | 结束时间 |
| `duration_ms` | `BIGINT` | NULL | 耗时（**`NFR-REL-1`：≤ 60 s**） |
| `result` | `VARCHAR(16)` | NULL, CHECK | `OK` / `MISMATCH` / `ABORTED` |
| `operator_id` | `BIGINT` | NULL | 手工触发时的操作人 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_replay_log_seq` | `component_id, replay_seq` | 唯一 | 每次重放一条记录 |
| `idx_replay_log_time` | `started_ts_ms` | 普通 | 按时间查重放历史（`FR-TRADE-15` 验收 3） |
| `idx_replay_log_result` | `result, duration_ms` | 普通 | **统计重放耗时与失败率（验证 `NFR-REL-1` 的 60 s 指标）** |

### 2.7 模块 `SETTLE`（结算）—— 16 张

> ⚠️ **本模块是"子账户体系可自证正确"的唯一途径**：每日必做
> **`Σ 子账户数据 = 真实期货账户数据`** 加总对账；不一致须**告警并阻断**（`R-SETTLE-2`）。
> 与 `FUND` **共享资金台账**，但**职责不重叠**（`R-SETTLE-8`）。

#### TB-SETTLE-1 `t_ctp_settle_batch` CTP 日终结算数据批次

**用途**：接收 CTP 日终结算数据的记录（`FR-SETTLE-1`）。**幂等**；**数据未到达时不得用推算数据替代**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `source_identifier` | `VARCHAR(256)` | NOT NULL | **来源标识**（文件名 / 接口批次号） |
| `received_time` | `TIMESTAMP` | NOT NULL | **接收时间**（`FR-SETTLE-1` 验收 4） |
| `position_count` / `fund_count` / `trade_count` | `INT` | NOT NULL DEFAULT `0` | 三类明细的接收条数 |
| `parse_status` | `VARCHAR(24)` | NOT NULL, CHECK | `OK` / `PARTIAL` / `FAILED` / `NOT_ARRIVED`（**数据未到达**〔未发生解析〕，置待重试；`CH-021`） |
| `parse_error` | `VARCHAR(1000)` | NULL | 解析错误说明（未到达/不完整/格式异常） |
| `retry_count` | `INT` | NOT NULL DEFAULT `0` | 重试次数（**任务置待重试并记录未达原因**，`FR-SETTLE-1` 验收 2） |
| `last_retry_time` | `TIMESTAMP` | NULL | 最近重试时间 |
| `is_confirmed` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否已确认（**重复接收以最后一次确认为准并留痕**） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_ctp_settle_batch_date` | `trade_date, source_identifier` | 唯一 | **幂等（重复接收同一交易日数据不产生重复结算记录，`FR-SETTLE-1` 验收 3）** |
| `idx_ctp_settle_batch_status` | `parse_status, trade_date` | 普通 | 巡检"待重试"批次（结算任务的阻塞点） |

---

#### TB-SETTLE-2 `t_ctp_settle_position` CTP 持仓明细

**用途**：期货公司侧持仓明细（`FR-SETTLE-1`、`FR-SETTLE-6`）。**核对基准；只读，不得修改**（`R-SETTLE-3`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `settle_batch_id` | `BIGINT` | NOT NULL, FK → `t_ctp_settle_batch(id)` | 所属批次 |
| `instrument_id` | `VARCHAR(32)` | NOT NULL | 合约 |
| `direction` | `VARCHAR(16)` | NOT NULL, CHECK | `LONG` / `SHORT` |
| `position_type` | `VARCHAR(16)` | NOT NULL, CHECK | `TODAY` / `YESTERDAY` |
| `position_qty` | `DECIMAL(20,4)` | NOT NULL | 持仓量（**合并持仓，不含子账户维度**） |
| `avg_price` | `DECIMAL(20,6)` | NULL | 持仓均价 |
| `settlement_price` | `DECIMAL(20,6)` | NULL | 结算价 |
| `margin` | `DECIMAL(20,4)` | NULL | 占用保证金 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_ctp_settle_pos` | `trade_date, instrument_id, direction, position_type` | 唯一 | 幂等 + 定位 |
| `idx_ctp_settle_pos_date` | `trade_date` | 普通 | **按交易日取合并持仓（加总对账的右侧基准，`FR-SETTLE-8`）** |

**备注**：本表**故意不含 `sub_account_id`** —— 期货公司侧持仓是**合并的**，
子账户拆分正是 `t_settle_position`（系统内）要解决的问题（`FR-SETTLE-2`）。
这是"子账户贯穿"的**唯一合理例外**，且它不属于五类业务数据中的任何一类（是**外部基准数据**）。

---

#### TB-SETTLE-3 `t_ctp_settle_fund` CTP 资金明细

**用途**：期货公司侧资金明细（`FR-SETTLE-3`、`FR-SETTLE-7`）。**核对基准；只读**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `settle_batch_id` | `BIGINT` | NOT NULL, FK → `t_ctp_settle_batch(id)` | 所属批次 |
| `equity` | `DECIMAL(20,4)` | NOT NULL | 权益（客户权益） |
| `available_fund` | `DECIMAL(20,4)` | NOT NULL | 可用资金 |
| `margin_occupied` | `DECIMAL(20,4)` | NOT NULL | 占用保证金 |
| `frozen_margin` | `DECIMAL(20,4)` | NULL | 冻结保证金 |
| `commission_today` | `DECIMAL(20,4)` | NULL | 当日手续费 |
| `deposit_today` / `withdraw_today` | `DECIMAL(20,4)` | NULL | 当日入金 / 出金 |
| `close_pnl` / `float_pnl` | `DECIMAL(20,4)` | NULL | 平仓盈亏 / 持仓浮动盈亏 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_ctp_settle_fund` | `trade_date` | 唯一 | 幂等（每日一条账户级资金） |
| `idx_ctp_settle_fund_date` | `trade_date` | 普通 | **按交易日取账户资金（加总对账右侧基准，`FR-SETTLE-3` 验收 1）** |

---

#### TB-SETTLE-4 `t_ctp_settle_trade` CTP 成交明细

**用途**：期货公司侧成交明细（`FR-SETTLE-5`）。**成交记录核对的基准；只读**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `settle_batch_id` | `BIGINT` | NOT NULL, FK → `t_ctp_settle_batch(id)` | 所属批次 |
| `trade_id` | `VARCHAR(64)` | NOT NULL | **CTP 成交编号**（与 `t_trade_fill.trade_id` 比对） |
| `instrument_id` | `VARCHAR(32)` | NOT NULL | 合约 |
| `direction` | `VARCHAR(16)` | NOT NULL | 方向 |
| `offset_flag` | `VARCHAR(16)` | NOT NULL | 开平 |
| `fill_price` | `DECIMAL(20,6)` | NOT NULL | 成交价 |
| `fill_volume` | `INT` | NOT NULL | 成交量 |
| `trade_ts_ms` | `BIGINT` | NULL | 成交时间戳 |
| `commission` | `DECIMAL(20,4)` | NULL | 手续费 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_ctp_settle_trade` | `trade_date, trade_id` | 唯一 | 幂等 + 逐笔比对主键 |
| `idx_ctp_settle_trade_date` | `trade_date` | 普通 | **按交易日取全部成交（逐笔核对，`FR-SETTLE-5`）** |
| `idx_ctp_settle_trade_instrument` | `trade_date, instrument_id` | 普通 | 差异下钻定位（按合约缩小范围） |

---

#### TB-SETTLE-5 `t_settle_cycle` 结算单（子账户独立）

**用途**：某交易日 × 某子账户（或全账户汇总）的结算结果（`FR-SETTLE-4`）。
**每个子账户独立出结算结果，不得合并出账**（`R-SETTLE-1`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户（**独立结算的维度**） |
| `settle_no` | `VARCHAR(32)` | NOT NULL | 结算单号（全系统唯一） |
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `is_aggregate` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否为全账户汇总行**（`'Y'` 时 `sub_account_id` 取"汇总占位子账户"并由 `sub_account_code='__TOTAL__'` 标识；**汇总仅用于展示与对账结论，不得替代明细**，`R-SETTLE-9`） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `PENDING`（待结算）/ `GENERATED`（已生成待核对）/ `BLOCKED`（已阻断）/ `RECONCILED`（核对完成）/ `CONFIRMED`（已确认关账） |
| `close_pnl` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 平仓盈亏（已实现） |
| `float_pnl` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 浮动盈亏（未实现） |
| `commission` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 手续费 |
| `margin_occupied` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 保证金占用 |
| `available_fund` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 可用资金 |
| `equity` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 权益 |
| `risk_ratio` | `DECIMAL(9,6)` | NULL | 风险度（保证金占用 / 权益） |
| `negative_fund_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 子账户资金是否为负（**告警并进入差异处理**，`FR-SETTLE-4` 验收 3） |
| `confirmed_by` | `BIGINT` | NULL | 关账确认人 |
| `confirmed_time` | `TIMESTAMP` | NULL | 关账时间 |
| `voided_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否已作废（重算须作废原单并留痕，`R-SETTLE-5`） |
| `void_reason` | `VARCHAR(500)` | NULL | 作废原因 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_settle_cycle_no` | `settle_no` | 唯一 | 结算单号唯一 |
| `uk_settle_cycle_date_sub` | `trade_date, sub_account_id, is_aggregate` | 唯一 | **同一交易日同一子账户只有一份有效结算单**（重复结算须重算，`SETTLE.md` §2 数据字典） |
| `idx_settle_cycle_date_status` | `trade_date, status` | 普通 | **结算列表按交易日 + 状态筛选（`FR-SETTLE-15`）；关账门禁校验** |
| `idx_settle_cycle_sub_date` | `sub_account_id, trade_date` | 普通 | 按子账户查历史结算（`FR-SETTLE-15`） |
| `idx_settle_cycle_daily_settled` | `trade_date, status` | 普通 | **风控日报与每日结算报告生成时取当日全部结算单** |

**备注**：`is_aggregate='Y'` 的汇总行**不是**"汇总替代明细"（`R-SETTLE-9` 禁止后者）
——它是**对账结论的载体**（`FR-SETTLE-10` 报告须含全账户汇总）；明细完整保存在
`t_settle_position` / `t_settle_fund` / `t_settle_pnl` 中，且**这三张表不含汇总行**。

---

#### TB-SETTLE-6 `t_settle_position` 系统内结算持仓（按子账户）

**用途**：系统内各子账户的日终持仓（`FR-SETTLE-2`、`FR-SETTLE-6`）。
**`Σ 本表 = t_ctp_settle_position`**（加总对账的结构基础）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **子账户（拆分结果维度；必填，可直接求和）** |
| `settle_cycle_id` | `BIGINT` | NOT NULL, FK → `t_settle_cycle(id)` | 所属结算单 |
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `instrument_id` | `VARCHAR(32)` | NOT NULL | 合约 |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `instrument_type` | `VARCHAR(16)` | NOT NULL, CHECK | `FUTURES` / `OPTION` |
| `direction` | `VARCHAR(16)` | NOT NULL, CHECK | `LONG` / `SHORT` |
| `position_type` | `VARCHAR(16)` | NOT NULL, CHECK | `TODAY` / `YESTERDAY` |
| `position_qty` | `DECIMAL(20,4)` | NOT NULL | 持仓量 |
| `attribution_source` | `VARCHAR(24)` | NOT NULL, CHECK | **归属来源**：`TRADE_RECORD`（由系统内成交记录推导）/ `UNABLE_TO_ATTRIBUTE`（**拆不出来**） |
| `unattributed_qty` | `DECIMAL(20,4)` | NULL | **无法归属的数量（须告警并列出，不得默认归入任一子账户）**，`FR-SETTLE-2` 验收 2 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_settle_position_key` | `trade_date, sub_account_id, instrument_id, direction, position_type` | 唯一 | 幂等 + 定位 |
| `idx_settle_position_date_instrument` | `trade_date, instrument_id` | 普通 | **`Σ 子账户 = CTP 合并` 的加总聚合（`FR-SETTLE-6`、`FR-SETTLE-8`）** |
| `idx_settle_position_sub_date` | `sub_account_id, trade_date` | 普通 | 按子账户查结算持仓与持仓核对（`FR-SETTLE-6`） |
| `idx_settle_position_unattributed` | `attribution_source` | 普通 | **筛"无法归属"的记录（差异告警的来源）** |

---

#### TB-SETTLE-7 `t_settle_fund` 系统内结算资金（按子账户）

**用途**：系统内各子账户的日终资金（`FR-SETTLE-3`、`FR-SETTLE-7`）。
**`Σ 本表 = t_ctp_settle_fund`**；**口径与 `t_fund_ledger` 完全一致**（`R-FUND-8`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **子账户（必填，可直接求和）** |
| `settle_cycle_id` | `BIGINT` | NOT NULL, FK → `t_settle_cycle(id)` | 所属结算单 |
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `begin_balance` | `DECIMAL(20,4)` | NOT NULL | 期初余额（自上日期末结转） |
| `equity` | `DECIMAL(20,4)` | NOT NULL | 权益 |
| `available_fund` | `DECIMAL(20,4)` | NOT NULL | 可用资金 |
| `margin_occupied` | `DECIMAL(20,4)` | NOT NULL | 占用保证金 |
| `frozen_margin` | `DECIMAL(20,4)` | NULL | 冻结保证金 |
| `amount_in` / `amount_out` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 当日入金 / 出金 |
| `transfer_in` / `transfer_out` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | **当日子账户划拨入 / 划拨出**（划拨体现"两子账户一增一减、加总不变"） |
| `close_pnl` / `float_pnl` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 平仓 / 浮动盈亏 |
| `commission` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 手续费 |
| `margin_change` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 保证金占用变动 |
| `end_balance` | `DECIMAL(20,4)` | NOT NULL | **期末余额**；`begin_balance + 当日发生额合计 = end_balance`（`FR-FUND-2`） |
| `fund_ledger_id` | `BIGINT` | NULL, FK → `t_fund_ledger(id)` | 关联的资金台账行（**共享台账，单一口径**，`R-SETTLE-8`） |
| `attribution_source` | `VARCHAR(24)` | NOT NULL, CHECK | `LEDGER` / `UNABLE_TO_ATTRIBUTE` |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_settle_fund_date_sub` | `trade_date, sub_account_id` | 唯一 | 幂等 + 定位 |
| `idx_settle_fund_date` | `trade_date` | 普通 | **`Σ 子账户 = CTP 账户资金` 的加总聚合（`FR-SETTLE-3` 验收 1、`FR-SETTLE-8`）** |
| `idx_settle_fund_sub_date` | `sub_account_id, trade_date` | 普通 | 按子账户查资金结算与资金核对（`FR-SETTLE-7`） |
| `idx_settle_fund_ledger` | `fund_ledger_id` | 普通 | **与 `FUND` 台账勾稽（`FR-SETTLE-7` 验收 3、`R-FUND-8`）** |

---

#### TB-SETTLE-8 `t_settle_pnl` 结算盈亏

**用途**：当日平仓盈亏 + 持仓浮动盈亏 + 手续费 + 保证金（`FR-SETTLE-4`）。
**口径全系统唯一**，须区分已实现与未实现。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **子账户（必填，可直接求和）** |
| `settle_cycle_id` | `BIGINT` | NOT NULL, FK → `t_settle_cycle(id)` | 所属结算单 |
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `instrument_id` | `VARCHAR(32)` | NOT NULL | 合约 |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `hedge_plan_id` | `BIGINT` | NULL, FK → `t_hedge_plan(id)` | 套保计划归属（会计按计划归集盈亏） |
| `pnl_type` | `VARCHAR(24)` | NOT NULL, CHECK | `CLOSE_REALIZED`（平仓盈亏-已实现）/ `FLOAT_UNREALIZED`（浮动盈亏-未实现）/ `COMMISSION`（手续费）/ `MARGIN_CHANGE`（保证金占用变动） |
| `amount` | `DECIMAL(20,4)` | NOT NULL | 金额（可为负） |
| `qty` | `DECIMAL(20,4)` | NULL | 对应数量（平仓/持仓量） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_settle_pnl_key` | `trade_date, sub_account_id, instrument_id, pnl_type` | 唯一 | 幂等 + 定位 |
| `idx_settle_pnl_date_type` | `trade_date, pnl_type` | 普通 | **加总对账：`Σ 子账户盈亏 = 真实账户盈亏`（`FR-SETTLE-8`）** |
| `idx_settle_pnl_sub_date` | `sub_account_id, trade_date` | 普通 | 按子账户查盈亏（子账户独立结算展示） |
| `idx_settle_pnl_plan` | `hedge_plan_id, trade_date` | 普通 | **按计划归集盈亏（`ACCT` 的 `FR-ACCT-5` 衍生品盈亏来源）** |

---

#### TB-SETTLE-9 `t_reconcile_record` 核对记录（三类）

**用途**：三类核对的执行记录（成交 / 仓位 / 资金）（`FR-SETTLE-5`~`FR-SETTLE-7`、`R-SETTLE-4`）。
**核对记录本身即合规证据**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 涉及的子账户（**差异可定位到子账户时非空**；账户级核对为 `NULL`） |
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `reconcile_type` | `VARCHAR(24)` | NOT NULL, CHECK | `TRADE`（成交记录核对）/ `POSITION`（仓位核对）/ `FUND`（资金核对） |
| `compare_granularity` | `VARCHAR(16)` | NOT NULL, CHECK | `BY_RECORD`（逐笔）/ `BY_SUMMARY`（按日汇总 + **可下钻**） |
| `system_count` / `ctp_count` | `INT` | NOT NULL DEFAULT `0` | 两侧条数 |
| `result` | `VARCHAR(24)` | NOT NULL, CHECK | `CONSISTENT`（一致）/ `HAS_DIFFERENCE`（存在差异）/ `NOT_COMPLETED`（未完成） |
| `difference_count` | `INT` | NOT NULL DEFAULT `0` | 差异条数 |
| `operator_id` | `BIGINT` | NOT NULL | 核对人（结算岗） |
| `reconcile_time` | `TIMESTAMP` | NOT NULL | 核对时间 |
| `remark` | `VARCHAR(500)` | NULL | 备注 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_reconcile_rec_date_type` | `trade_date, reconcile_type` | 普通 | **取某日三类核对记录（关账门禁校验 `R-SETTLE-5`）** |
| `idx_reconcile_rec_result` | `result, trade_date` | 普通 | 筛"存在差异/未完成"（阻断与差异处理列表） |
| `idx_reconcile_rec_sub` | `sub_account_id, trade_date` | 普通 | 按子账户查核对记录 |

---

#### TB-SETTLE-10 `t_reconcile_difference` 核对差异

**用途**：系统内与期货公司数据不一致项（`FR-SETTLE-9`）。
**差异须逐条定位；未闭环的差异不得标记结算完成**（`R-SETTLE-4`、`R-SETTLE-5`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 差异涉及子账户（可定位时非空） |
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `reconcile_record_id` | `BIGINT` | NOT NULL, FK → `t_reconcile_record(id)` | 所属核对记录 |
| `difference_type` | `VARCHAR(16)` | NOT NULL, CHECK | `TRADE`（成交差异）/ `POSITION`（持仓差异）/ `FUND`（资金差异） |
| `difference_kind` | `VARCHAR(32)` | NOT NULL, CHECK | `SYS_ONLY`（系统有、期货公司无）/ `CTP_ONLY`（期货公司有、系统无）/ `VALUE_MISMATCH`（要素不一致） |
| `biz_key` | `VARCHAR(128)` | NULL | 定位键（成交编号 / 合约+方向+仓型 / 资金项目） |
| `field_name` | `VARCHAR(64)` | NULL | 不一致的字段名（`VALUE_MISMATCH` 时） |
| `system_value` | `VARCHAR(128)` | NULL | 系统侧取值 |
| `ctp_value` | `VARCHAR(128)` | NULL | 期货公司侧取值 |
| `diff_qty` / `diff_amount` | `DECIMAL(20,4)` | NULL | 数量差 / 金额差 |
| `handle_status` | `VARCHAR(16)` | NOT NULL, CHECK | `PENDING`（待处理）/ `HANDLING`（处理中）/ `CLOSED`（已闭环） |
| `handle_method` | `VARCHAR(16)` | NULL, CHECK | `SUPPLEMENT`（补录）/ `REVERSE`（冲正）/ `ADJUST`（调整） |
| `handle_desc` | `VARCHAR(1000)` | NULL | 处理说明 |
| `owner_id` | `BIGINT` | NULL | 责任人 |
| `handler_id` | `BIGINT` | NULL | 处理人 |
| `reviewer_id` | `BIGINT` | NULL | **复核人（调整类操作须双人复核，≠ `handler_id`）** |
| `found_time` | `TIMESTAMP` | NOT NULL | 发现时间 |
| `closed_time` | `TIMESTAMP` | NULL | 闭环时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_reconcile_diff_date_status` | `trade_date, handle_status` | 普通 | **关账门禁：某日是否还有未闭环差异（`R-SETTLE-5`）** |
| `idx_reconcile_diff_type_status` | `difference_type, handle_status` | 普通 | 按差异类型查看待处理项 |
| `idx_reconcile_diff_sub_date` | `sub_account_id, trade_date` | 普通 | 按子账户定位差异（加总不一致的阻断范围判定） |
| `idx_reconcile_diff_bizkey` | `biz_key` | 普通 | 按业务键反查差异（差异下钻与历史比对） |

---

#### TB-SETTLE-11 `t_aggregation_check` 加总对账记录

**用途**：`Σ 子账户数据 = 真实期货账户数据` 的校验结论（`FR-SETTLE-8`）。**每日必做红线**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `check_type` | `VARCHAR(24)` | NOT NULL, CHECK | `POSITION`（持仓）/ `FUND`（资金）/ `PNL`（盈亏）/ `MARGIN`（保证金） |
| `sum_sub_account` | `DECIMAL(20,4)` | NOT NULL | **Σ 子账户**（本模型可直接求和的结果） |
| `real_account_value` | `DECIMAL(20,4)` | NOT NULL | **真实期货账户对应值**（来自 `t_ctp_settle_*`） |
| `difference` | `DECIMAL(20,4)` | NOT NULL | 差额（`sum_sub_account − real_account_value`） |
| `result` | `VARCHAR(24)` | NOT NULL, CHECK | `CONSISTENT`（一致）/ `INCONSISTENT_BLOCKED`（不一致-已阻断） |
| `possible_sub_accounts` | `VARCHAR(500)` | NULL | **可能来源子账户清单（v1.2 起为"展示缓存"，**禁止作为判定取数来源**）**；机判来源见 `t_agg_diff_candidate`（`TB-SETTLE-16`，§4.4.1） |
| `block_scope` | `VARCHAR(32)` | NULL | **阻断范围**（按 `CFG-18`：`ALL_SUB` / `DIFF_SUB` / `DIFF_SUB_NON_CLOSE`） |
| `block_effective_time` | `TIMESTAMP` | NULL | **阻断生效时间**（`NFR-REL-6`：**阻断立即生效**，不等人确认） |
| `alert_time` | `TIMESTAMP` | NULL | 告警可见时间（**不一致到告警 ≤ 60 s**） |
| `check_time` | `TIMESTAMP` | NOT NULL | 校验执行时间 |
| `closed_time` | `TIMESTAMP` | NULL | 差异闭环、阻断解除时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_agg_check_date_type` | `trade_date, check_type` | 普通 | **取某日加总对账结论（`FR-SETTLE-10` 报告、`FR-SETTLE-15` 列表）** |
| `idx_agg_check_result` | `result, trade_date` | 普通 | **筛"不一致-已阻断"（阻断状态查询：新开仓/划拨/关账须读此结论）** |
| `idx_agg_check_block` | `block_effective_time, closed_time` | 普通 | 阻断时长统计（`NFR-REL-6` 指标验证） |

**备注**：**本表是"阻断"状态的唯一载体**。任何需要检查阻断的操作
（下单、划拨、关账）都先查"当日是否存在 `result='INCONSISTENT_BLOCKED'` 且 `closed_time IS NULL`"的行。
`block_scope` 决定阻断粒度（`Q-11` / `CFG-18`），
`block_effective_time` 使"阻断立即生效"（`NFR-REL-6`）可被度量。
**v1.2（`DC-021`）**：差异的**归属子账户**由 `t_agg_diff_candidate`（`TB-SETTLE-16`）**结构化承载**；
`possible_sub_accounts` **降级为展示缓存**，任何阻断/放行判定**不得**解析该文本（`G-6`）。

---

#### TB-SETTLE-16 `t_agg_diff_candidate` 加总差异候选归属（v1.2 新增）

**用途**：加总对账不一致时，**结构化承载"差异归属到哪些子账户"**（`Q-11`、`NFR-REL-6`、`FR-SETTLE-8`）。
**这是"仅差异所属子账户被阻断"这一判定唯一的机判取数来源**（§4.4.1、`G-6` 整改）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | **NOT NULL**, FK → `t_sub_account(id)` | **候选归属子账户**（机判主键之一；**恒 `> 0`，不得为哨兵值 `0`**） |
| `aggregation_check_id` | `BIGINT` | NOT NULL, FK → `t_aggregation_check(id)` | 所属加总对账记录（`TB-SETTLE-11`） |
| `candidate_rank` | `INT` | NOT NULL | **候选排序**（`1` = 最可能；用于界面提示与逐行断言） |
| `diff_amount` | `DECIMAL(20,4)` | NOT NULL | 该子账户**差额贡献量**（候选口径；`Σ diff_amount` 应与 `t_aggregation_check.difference` 一致或显式标注未分配部分） |
| `resolution_status` | `VARCHAR(16)` | NOT NULL DEFAULT `'PENDING'`, CHECK | `PENDING`（待定位）/ `CONFIRMED`（已确认归属，对应 `t_reconcile_difference.sub_account_id`）/ `EXCLUDED`（已排除）。**阻断生效集合 = `{PENDING, CONFIRMED}`** |
| `reconcile_difference_id` | `BIGINT` | NULL, FK → `t_reconcile_difference(id)` | 定位完成后关联的差异记录（闭环） |
| `evidence_desc` | `VARCHAR(500)` | NULL | 归属判据说明（如"该子账户当日平仓盈亏差额 = −x"），供审计复现 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_agg_diff_candidate` | `aggregation_check_id, sub_account_id` | 唯一 | 同一次对账对同一子账户只有一个候选结论 |
| `idx_agg_diff_candidate_check_rank` | `aggregation_check_id, candidate_rank` | 普通 | **按候选顺序取出阻断集合（阻断范围判定热路径）** |
| `idx_agg_diff_candidate_sub_status` | `sub_account_id, resolution_status` | 普通 | 按子账户查历史差异归属（审计与追责） |

**备注**：本表**只增不改**（`resolution_status` 与 `reconcile_difference_id` 为唯一可更新列，更新须留痕）；
**无删除入口**。写入来源：`SettleService` 加总对账（`FR-SETTLE-8`）。

---

#### TB-SETTLE-12 `t_settle_report` 结算报告 / 风控日报

**用途**：每日结算报告与风控日报（`FR-SETTLE-10`、`FR-SETTLE-12`）。
**加总对账不一致时报告须显式标注"不一致/已阻断"，不得输出正常结论**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 子账户（报告按子账户出时非空；全账户汇总为 `NULL`） |
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `report_type` | `VARCHAR(24)` | NOT NULL, CHECK | `DAILY_SETTLE`（每日结算报告）/ `DAILY_RISK`（风控日报） |
| `agg_result` | `VARCHAR(24)` | NOT NULL, CHECK | **本报告所依据的加总对账结论**：`CONSISTENT` / `INCONSISTENT_BLOCKED` / `NOT_APPLICABLE` |
| `content_json` | `CLOB` | NOT NULL | 报告内容（结构化） |
| `content_path` | `VARCHAR(256)` | NULL | 生成的文件路径（可导出） |
| `data_gap_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否存在数据缺口（存在须显式标注） |
| `gap_desc` | `VARCHAR(1000)` | NULL | 缺口说明 |
| `generated_by` | `BIGINT` | NOT NULL | 生成人 |
| `generated_time` | `TIMESTAMP` | NOT NULL | 生成时间 |
| `missing_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **该日报告是否缺失**（`R-SETTLE-6`：核对记录缺失须告警） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_settle_report_date_type_sub` | `trade_date, report_type, sub_account_id` | 唯一 | 同一日同类型同子账户一份报告 |
| `idx_settle_report_date_type` | `trade_date, report_type` | 普通 | **按日期 + 类型查报告（`FR-SETTLE-12` 验收 4、导出 `FR-SETTLE-16`）** |
| `idx_settle_report_agg` | `agg_result, trade_date` | 普通 | **筛"不一致/已阻断"的报告（确保异常不被输出为正常）** |
| `idx_settle_report_missing` | `missing_flag, trade_date` | 普通 | 缺失巡检（每日报告必生成，`R-SETTLE-6`） |

---

#### TB-SETTLE-13 `t_check_task` 核对任务（月度 / 季度 / 年度）

**用途**：按周期自动生成、需**指定角色在规定期内完成核对并电子签署**的任务单（`FR-SETTLE-17`、`R-SETTLE-11`、`Q-14` 已确认）。
**月度 = 风控岗 + 交易岗 + 财务岗三方各自签署；季度 = 风控编制 + 交易会签 → 管理层签收；年度 = 审计岗全覆盖。**

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `check_type` | `VARCHAR(16)` | NOT NULL, CHECK | `MONTHLY`（月度三方核对）/ `QUARTERLY`（季度报告）/ `ANNUAL`（年度审计覆盖） |
| `period_key` | `VARCHAR(16)` | NOT NULL | 期间键（`2026-09` / `2026-Q3` / `2026`） |
| `🔴子` `sub_account_id` | `INT` | **NOT NULL DEFAULT 0**, FK → `t_sub_account(id)` | 涉及子账户（**跨子账户核对取哨兵值 `0`**，不再使用 `NULL`；`DC-020`） |
| `required_sign_roles` | `VARCHAR(128)` | NOT NULL | **应签角色清单**（`Q-14`）：月度 `RISK,TRADE,FINANCE`；季度 `RISK,TRADE,MANAGE`；年度 `AUDITOR`（来自配置 `settle.sign.roles`） |
| `signed_roles` | `VARCHAR(128)` | NOT NULL DEFAULT `''` | **已签角色**（用于判定"待签署 / 部分签署 / 已完成"） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `PENDING_SIGN`（待签署）/ `PARTIAL_SIGNED`（部分签署）/ `COMPLETED`（已完成）/ `OVERDUE`（已逾期） |
| `snapshot_hash` | `VARCHAR(128)` | **NOT NULL** | **核对对象快照哈希**（签署对象的指纹；**签署后快照变更须重新签署**，`FR-SETTLE-17` 验收 4） |
| `snapshot_json` | `CLOB` | NULL | 快照内容（核对对象的关键数据，供审计复现） |
| `check_items` | `VARCHAR(500)` | NOT NULL | 核对项（持仓 / 资金 / 盈亏 / 额度） |
| `check_conclusion` | `VARCHAR(1000)` | NULL | 核对结论与说明（签署齐备后填写） |
| `content_json` | `CLOB` | NULL | 季度报告内容（保值规模 / 双侧盈亏 / 有效性 / 违规事项） |
| `submitted_to` | `VARCHAR(128)` | NULL | 提交 / 签收对象（管理层角色，`Q-14`） |
| `deadline_date` | `DATE` | NOT NULL | **签署截止日**（期间结束后 `settle.sign.deadline_days` 内，默认 30 天，`Q-14`） |
| `overdue_alerted` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **逾期是否已告警**（`R-SETTLE-11`：逾期未签须告警，**不得静默缺失**） |
| `generated_time` | `TIMESTAMP` | NOT NULL | 任务生成时间（调度触发） |
| `completed_time` | `TIMESTAMP` | NULL | 全部签署完成时间 |

**具名 `CHECK`（`G-11`，可执行 DDL 见 §4.3；三处须一致）**：

```sql
ALTER TABLE t_check_task ADD CONSTRAINT ck_check_task_scope_sentinel CHECK (
     sub_account_id >= 0
);
```
> **作用域列例外（`G-11`）**：本表**无第二判别列**（`check_type` 是**任务周期**、非作用域），`sub_account_id` 取值域 = **`0`（跨子账户核对）∪ `(0,∞)`（具体子账户）**，**不存在被排除组合** → 上述 `CHECK` 为**范围标注型**（登记作用域列身份 + 拒绝负值），**不得为迁就 CHECK 而新增判别列**。本表**不属五类 → 不计入「可加总 43」**。

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_check_task_period` | `check_type, period_key, sub_account_id` | 唯一 | 同一期间同一类型一份任务（跨子账户核对为哨兵值 `0`，**唯一性不依赖 `NULL` 语义**） |
| `idx_check_task_status_deadline` | `status, deadline_date` | 普通 | **筛"待签署 / 部分签署"与"逾期"（逾期巡检与告警）** |
| `idx_check_task_period_time` | `period_key, generated_time` | 普通 | 按期间查任务（`FR-AUDIT-13` 查询；`FR-AUDIT-6` 年度报告附清单） |
| `idx_check_task_snapshot_hash` | `snapshot_hash` | 普通 | **校验"签署后快照是否变更"（变更须重签）** |

**备注**：本表由 `SETTLE` 写入、`AUDIT` **只读**引用（`R-AUDIT-11`、`R-AUDIT-6`）。
原 `t_compliance_check`（v1.0 的"月度核对记录"）在本版**升级为"核对任务"**——
二者是同一实体的不同成熟度：从"记录一次核对"升级为"任务 + 签署流程"。

---

#### TB-SETTLE-15 `t_check_signature` 核对签署记录

**用途**：核对任务中**每个角色的电子签署留痕**（`FR-SETTLE-17`、`R-SETTLE-11`、`Q-14` 已确认）。
**含账号 / 角色 / 时间 / IP / 核对对象快照哈希；不可代签；逾期未签进审计报表。**

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | **NOT NULL DEFAULT 0**, FK → `t_sub_account(id)` | 涉及子账户（**随任务头**；跨子账户核对为哨兵值 `0`，`DC-020`） |
| `check_task_id` | `BIGINT` | NOT NULL, FK → `t_check_task(id)` | 所属核对任务 |
| `sign_role` | `VARCHAR(24)` | NOT NULL, CHECK | **签署角色**：`RISK`（风控岗）/ `TRADE`（交易岗）/ `FINANCE`（财务岗）/ `MANAGE`（管理层，**签收**）/ `AUDITOR`（审计岗） |
| `signer_id` | `BIGINT` | **NOT NULL**, FK → `t_user(id)` | **签署人账号**（**必须是应签角色账号本人**，`FR-SETTLE-17` 验收 3：非应签角色或代签被拒） |
| `signer_role_code` | `VARCHAR(32)` | NOT NULL | 签署人的实际角色编码（**由签署动作显式选择**，须与 `sign_role` 一致；解析与校验规则见 §4.4"不可代签"行） |
| `sign_time` | `TIMESTAMP` | NOT NULL | **签署时间** |
| `sign_ip` | `VARCHAR(64)` | NOT NULL | **签署 IP**（`Q-14` 明确要求留痕） |
| `snapshot_hash` | `VARCHAR(128)` | **NOT NULL** | **签署时的核对对象快照哈希**（与任务头比对；不一致 → 任务回到"待签署"并要求重签） |
| `sign_result` | `VARCHAR(16)` | NOT NULL, CHECK | `SIGNED`（已签署）/ `REJECTED`（签署时提出异议） |
| `sign_opinion` | `VARCHAR(1000)` | NULL | 签署意见 / 异议说明 |
| `proxy_rejected` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否因"代签"被系统拒绝**（越权尝试，须留痕并计 `FR-SETTLE-17` 验收 3） |
| `manage_role_trade_blocked` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **"管理层签署权与交易权互斥"的核验留痕（v1.2 补定义，`S-3`）**：<br>`'Y'` = 本次签署**触发**了 `R-AUTH-13` 互斥核验——即**签署人为管理层角色（`sign_role='MANAGE'`）**，签署时系统已核验其**账号交易权已被禁用**（管理层只签收、不得操盘）；<br>`'N'` = **未触发**（非管理层签署，不适用该核验）。<br>**由 `AuthService` 在签署动作中写入**，仅作留痕；**不表示"曾因管理层下单被拒"**（后者记于 `t_unauthorized_attempt` / `EVT_PRE_TRADE_REJECT`） |

**具名 `CHECK`（`G-11`，可执行 DDL 见 §4.3；三处须一致）**：

```sql
ALTER TABLE t_check_signature ADD CONSTRAINT ck_check_signature_scope_sentinel CHECK (
     sub_account_id >= 0
);
```
> **作用域列例外（`G-11`）**：`sub_account_id` **随任务头**（非独立判决维度），取值域 = **`0`（跨子账户任务）∪ `(0,∞)`（具体子账户任务）**，**无被排除组合** → 上述 `CHECK` 为**范围标注型**。本表**不属五类 → 不计入「可加总 43」**。

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_check_signature_task_role` | `check_task_id, sign_role` | 唯一 | 同一任务同一角色只签一次（**不可重复签 / 不可代签**） |
| `idx_check_signature_signer` | `signer_id, sign_time` | 普通 | **按签署人查签署史（`FR-AUDIT-13` 只读查询；年度审计全覆盖）** |
| `idx_check_signature_role_time` | `sign_role, sign_time` | 普通 | 按角色统计签署完成率；年度审计覆盖度 |
| `idx_check_signature_task` | `check_task_id, sign_time` | 普通 | 取某任务的全部签署（判定"部分签署 / 已完成"） |
| `idx_check_signature_proxy` | `proxy_rejected, sign_time` | 普通 | **筛"代签被拒"记录（审计重点，`R-AUTH-13`）** |

**备注**：本表在 **`AUDIT` 侧只读**（`R-AUDIT-11`、`R-AUDIT-6`）；**无删除 / 编辑入口**。
`uk_check_signature_task_role` 把"**不可代签 / 不可重复签**"下沉为**数据库级唯一约束**——
同一任务同一角色**结构上只能有一条**签署记录。

---

#### TB-SETTLE-14 `t_stress_test` 压力测试结果

**用途**：情景化保证金缺口估算（`FR-SETTLE-11`、`R-SETTLE-7`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 子账户（`NULL` = 全账户） |
| `test_date` | `DATE` | NOT NULL | 测试基准日 |
| `scenario_json` | `CLOB` | NOT NULL | **情景参数**（价格不利变动序列、保证金率变动；来自 `CFG-14b`） |
| `margin_gap` | `DECIMAL(20,4)` | NOT NULL | 估算的**保证金缺口** |
| `additional_requirement` | `DECIMAL(20,4)` | NULL | **追加需求**（追加保证金金额） |
| `equity_after` | `DECIMAL(20,4)` | NULL | 情景后的权益估算 |
| `risk_ratio_after` | `DECIMAL(9,6)` | NULL | 情景后的风险度 |
| `operator_id` | `BIGINT` | NULL | 执行人（手工触发；自动执行为 `NULL`） |
| `test_time` | `TIMESTAMP` | NOT NULL | 执行时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_stress_test_date_sub` | `test_date, sub_account_id` | 普通 | 按基准日 + 子账户查压力测试结果（`FR-SETTLE-11` 验收 3） |
| `idx_stress_test_time` | `test_time` | 普通 | 压力测试历史（趋势分析） |

---

### 2.8 模块 `RISK`（风险控制）—— 11 张

> ⚠️ **本模块跨组件**：**事前风控在 C++ 全内存执行**（不落本模块的表）；
> 本模块的表承载 **Java 侧的事中 / 事后风控**，以及**参数下发与镜像状态**（Java 侧记录）。
> **事前风控的判定数据不落库**（权威源为 C++ 内存），但**其参数来源与下发过程**落库。

#### TB-RISK-11 `t_annual_scale` 年度规模基数（品种 × 年度）

**用途**：**规模红线的分子与分母**（`Q-1` 已确认：基数 = **年度现货敞口累计量（实物量口径）**，
**不用营业收入**；分子 = 年度累计新开仓保值数量折算实物量；分母 = 该品种年度预计实物经营量）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | **NOT NULL DEFAULT 0**, FK → `t_sub_account(id)` | 子账户（`scope_type='SUB_ACCOUNT'` 时 `> 0`；`TOTAL_ACCOUNT` 时取**哨兵值 `0`（= 全账户）**，不再使用 `NULL`，`DC-020`） |
| `scope_type` | `VARCHAR(16)` | NOT NULL, CHECK | `SUB_ACCOUNT` / `TOTAL_ACCOUNT`（**`Q-1`：子账户与全账户双算、取严者**）；**与 `sub_account_id` 双向联动见 §4.3 具名 `CHECK` `ck_annual_scale_scope_sentinel`**（`TOTAL_ACCOUNT ⇔ 0`；`SUB_ACCOUNT ⇔ >0`） |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `scale_year` | `INT` | NOT NULL | 年度（如 `2026`） |
| `expected_spot_business_qty` | `DECIMAL(20,4)` | NOT NULL | **分母：该品种年度预计实物经营量（实物量口径）**；由用户在现货管理按**品种 + 年度**维护（`Q-1`） |
| `accumulated_hedge_qty` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | **分子：年度累计新开仓保值数量**（手数 × 合约乘数折算为实物量）；由成交回报增量维护（**权威源为 C++ 内存**，本列为投影） |
| `category` | `VARCHAR(16)` | NOT NULL, CHECK | 该品种生效分类（`COMMODITY` 90% / `TRADE` 80%；继承子账户分类或品种级覆盖，`Q-2`） |
| `scale_ratio_limit` | `DECIMAL(9,6)` | NOT NULL | 适用红线比例（`0.900000` / `0.800000`；比例本身可配置） |
| `usage_ratio` | `DECIMAL(9,6)` | NULL | 使用率（`accumulated_hedge_qty / expected_spot_business_qty`）；**双算取严者**后的值 |
| `dispatch_status` | `VARCHAR(16)` | NOT NULL DEFAULT `'PENDING'`, CHECK | 下发 C++ 状态 |
| `dispatch_version` | `BIGINT` | NULL | 下发 `param_version` |

**具名 `CHECK`（`G-11`，可执行 DDL 见 §4.3；三处须一致）**：

```sql
ALTER TABLE t_annual_scale ADD CONSTRAINT ck_annual_scale_scope_sentinel CHECK (
     (scope_type = 'TOTAL_ACCOUNT' AND sub_account_id = 0)
  OR (scope_type = 'SUB_ACCOUNT'   AND sub_account_id > 0)
);
```
> **作用域列例外（`G-11`）**：本表为**混合表**——子账户口径行（`SUB_ACCOUNT`）为 **A 类**（`sub_account_id > 0`），全账户口径行（`TOTAL_ACCOUNT`）为 **B 类**（哨兵 `0`）。故「五类业务数据行 `sub_account_id` 恒 `>0`」**不适用于其 B 类行**；B 类行由本 `CHECK` 按 `scope_type` **分派**。**仍计入五类①，计数口径不变**（见 §4.6.1(1b)）。

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_annual_scale` | `scope_type, sub_account_id, product_id, scale_year` | 唯一 | 同范围同品种同年度一条（全账户口径为哨兵值 `0`，**唯一性不依赖 `NULL` 语义**） |
| `idx_annual_scale_key` | `sub_account_id, product_id, scale_year` | 普通 | **取规模基数（事前风控 `0x1007`/`0x1008` 判定热路径，双算取严者）** |
| `idx_annual_scale_total` | `scope_type, product_id, scale_year` | 普通 | **取全账户口径基数（双算之一）** |
| `idx_annual_scale_dispatch` | `dispatch_status` | 普通 | 巡检未下发成功 |

**备注**：**分母缺失时**（某品种某年度未维护预计经营量）→ 规模红线**无法判定**，
服务层须**明确报错并阻断**（不得默认为 0 从而"永远不超限"）——这是"不静默兜底"原则的落地。

---

#### TB-RISK-1 `t_risk_threshold` 风控阈值

**用途**：集中配置的风控参数（`FR-CONFIG-5`、`R-RISK-14`）。**不得硬编码**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `threshold_key` | `VARCHAR(128)` | **NOT NULL, UNIQUE** | 阈值 Key（对应 `ARCHITECTURE.md` §11 的 `HG.RISK.*`） |
| `threshold_value` | `VARCHAR(128)` | NOT NULL | 阈值取值 |
| `value_type` | `VARCHAR(16)` | NOT NULL, CHECK | `DECIMAL` / `INT` / `BOOL` / `LIST` |
| `min_value` / `max_value` | `DECIMAL(20,6)` | NULL | 取值范围（`FR-CONFIG-5` 验收 3：输入越界被拒） |
| `default_value` | `VARCHAR(128)` | NOT NULL | **默认值（须与 `PROJECT.md` 的合规红线一致，不得更宽松）**，`R-CONFIG-8` |
| `is_compliance_redline` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否为合规红线**（`'Y'` 时放宽须双人复核并留痕） |
| `scope_type` | `VARCHAR(16)` | NOT NULL, CHECK | `GLOBAL`（全局）/ `SUB_ACCOUNT`（子账户级，**优先于全局**，`FR-CONFIG-8`）；**与 `sub_account_id` 双向联动见 §4.3 具名 `CHECK` `ck_risk_threshold_scope_sentinel`**（`GLOBAL ⇔ 0`；`SUB_ACCOUNT ⇔ >0`） |
| `🔴子` `sub_account_id` | `INT` | **NOT NULL DEFAULT 0**, FK → `t_sub_account(id)` | 子账户（`scope_type='SUB_ACCOUNT'` 时 `> 0`；`scope_type='GLOBAL'` 时取**哨兵值 `0`**，不再使用 `NULL`，`DC-020`） |
| `related_question_no` | `VARCHAR(16)` | NULL | 关联待澄清编号（`Q-1`~`Q-17`） |
| `dispatch_status` | `VARCHAR(16)` | NOT NULL DEFAULT `'PENDING'`, CHECK | 下发 C++ 状态 |
| `dispatch_version` | `BIGINT` | NULL | 下发 `param_version` |
| `description` | `VARCHAR(500)` | NULL | 说明（含影响提示） |

**具名 `CHECK`（`G-11`，可执行 DDL 见 §4.3；三处须一致）**：

```sql
ALTER TABLE t_risk_threshold ADD CONSTRAINT ck_risk_threshold_scope_sentinel CHECK (
     (scope_type = 'GLOBAL'      AND sub_account_id = 0)
  OR (scope_type = 'SUB_ACCOUNT' AND sub_account_id > 0)
);
```
> **作用域列例外（`G-11`）**：子账户级阈值行（`SUB_ACCOUNT`）为 **A 类**（`> 0`），全局阈值行（`GLOBAL`）为 **B 类**（哨兵 `0`）。本表**不属五类业务数据**（属非五类显式携带），该 `CHECK` 使其哨兵取值在**数据库级**确定。

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_risk_threshold_key_scope` | `threshold_key, scope_type, sub_account_id` | 唯一 | 同 Key 同范围唯一（全局/账户级为哨兵值 `0`，**唯一性不依赖 `NULL` 语义**） |
| `idx_risk_threshold_scope` | `scope_type, sub_account_id` | 普通 | **取生效阈值（子账户级优先于全局）** |
| `idx_risk_threshold_dispatch` | `dispatch_status` | 普通 | 巡检未下发成功 |

---

#### TB-RISK-2 `t_param_dispatch` 参数下发记录

**用途**：Java → C++ 的控制指令下发与回执（`FR-RISK-9`、`FR-RISK-10`、`FR-CONFIG-6`、`R-CONFIG-3`）。
**无回执或失败时该变更不得视为已生效**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `param_version` | `BIGINT` | NOT NULL | **单调递增的参数版本**（`R-CONFIG-3` 顺序保证） |
| `dispatch_type` | `VARCHAR(32)` | NOT NULL, CHECK | `LOAD_RISK_PARAMS` / `UPDATE_WHITELIST` / `UPDATE_QUOTA` / `UPDATE_POSITION_LIMIT` / `UPDATE_RISK_THRESHOLD` / `UPDATE_PERMISSION` / `UPDATE_EXPOSURE` / `SET_TRADING_SUSPEND` / `SET_READY_STATE` / `TRIGGER_REPLAY` / `RELOAD`（`CH-021` 扩域；对应线格式 `0x0119`/`0x011A`/`0x011B`） |
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 涉及子账户（若为子账户级变更） |
| `batch_index` / `batch_total` | `INT` | NULL | 分批分片（全量预加载） |
| `payload_digest` | `VARCHAR(128)` | NOT NULL | 下发内容摘要（SHA-256，用于核对两侧一致） |
| `record_count` | `INT` | NOT NULL DEFAULT `0` | 本次下发的记录条数（**加载条数可校验**，`FR-RISK-9` 验收 3） |
| `dispatch_status` | `VARCHAR(16)` | NOT NULL, CHECK | `PENDING` / `SUCCESS` / `FAIL` / `UNCONFIRMED`（超时） |
| `ack_time` | `TIMESTAMP` | NULL | 回执时间 |
| `ack_detail` | `VARCHAR(1000)` | NULL | 回执详情（或 `0x0403` 等错误） |
| `retry_count` | `INT` | NOT NULL DEFAULT `0` | 重试次数 |
| `operator_id` | `BIGINT` | NOT NULL | 操作人 |
| `dispatch_time` | `TIMESTAMP` | NOT NULL | 下发时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_param_dispatch_version` | `param_version, dispatch_type` | 普通 | **按版本核对下发完整性（全量预加载须收齐全部批次）** |
| `idx_param_dispatch_status` | `dispatch_status, dispatch_time` | 普通 | **巡检"失败/未确认"的下发（`R-CONFIG-3`）** |
| `idx_param_dispatch_sub_time` | `sub_account_id, dispatch_time` | 普通 | 按子账户查下发历史 |

**备注**：`payload_digest` 是排查"内存与库不一致"的关键工具——
两侧摘要一致 ⇒ 内存状态与库状态一致（可自动比对，替代人工翻日志）。

---

#### TB-RISK-3 `t_mirror_watermark` 镜像状态水位

**用途**：Java 侧镜像状态的**已消费事件序号**（`FR-RISK-12`）。
**镜像状态滞后超阈值须告警**（`R-RISK-10`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `component_id` | `VARCHAR(64)` | NOT NULL | 组件标识（`hg-trading`） |
| `stream_type` | `VARCHAR(24)` | NOT NULL, CHECK | `EVENT`（事件流）/ `QUOTE`（行情流） |
| `last_consumed_seq` | `BIGINT` | NOT NULL DEFAULT `0` | 已消费的最大 `SEQ` |
| `last_produced_seq` | `BIGINT` | NOT NULL DEFAULT `0` | 对端已发送的最大 `SEQ`（来自心跳载荷） |
| `lag` | `BIGINT` | NOT NULL DEFAULT `0` | **滞后量 = `last_produced_seq − last_consumed_seq`** |
| `lag_threshold` | `BIGINT` | NOT NULL DEFAULT `100` | 滞后告警阈值（配置项） |
| `lag_alarm` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 当前是否告警 |
| `last_update_time` | `TIMESTAMP` | NOT NULL | 最后更新时间 |
| `connection_state` | `VARCHAR(24)` | NOT NULL, CHECK | `CONNECTED` / `DISCONNECTED` / `RESUMING` |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_mirror_watermark` | `component_id, stream_type` | 唯一 | 每组件每流一条水位 |
| `idx_mirror_watermark_alarm` | `lag_alarm, last_update_time` | 普通 | **筛滞后告警（`FR-RISK-12` 验收 3、协议 T3/T4）** |

---

#### TB-RISK-4 `t_risk_alert` 风险告警

**用途**：事中指标超阈值生成的告警（`FR-RISK-12`~`FR-RISK-14`）。
**须留痕（指标值、阈值、时间、子账户）；不得静默丢弃**（`R-RISK-11`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `alert_type` | `VARCHAR(32)` | NOT NULL, CHECK | `POSITION`（实时持仓）/ `FLOAT_PNL`（浮动盈亏）/ `MARGIN_RATIO`（保证金占用率）/ `RISK_RATIO`（风险度）/ `AVAILABLE_FUND`（资金可用量）/ `ABNORMAL_ORDER`（异常下单行为）/ `MIRROR_LAG`（镜像滞后）/ `FUND_QUOTA`（资金额度超限） |
| `alert_level` | `VARCHAR(16)` | NOT NULL, CHECK | `INFO`（提示）/ `WARNING`（警告）/ `CRITICAL`（严重） |
| `metric_name` | `VARCHAR(64)` | NOT NULL | 指标名 |
| `metric_value` | `VARCHAR(64)` | NOT NULL | **指标值** |
| `threshold_value` | `VARCHAR(64)` | NOT NULL | **阈值**（如频繁开平仓 `Q-5` 的 `6` 告警 / `10` 限制；价格偏离 `Q-15` 的 `0.02` 告警 / `0.05` 阻断） |
| `instrument_id` | `VARCHAR(32)` | NULL | 涉及合约 |
| `alert_detail` | `VARCHAR(1000)` | NULL | 详情 |
| `alert_ts_ms` | `BIGINT` | NOT NULL | 告警毫秒时间戳 |
| `alert_time` | `TIMESTAMP` | NOT NULL | 落库时间 |
| `disposed` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否已处置**（未处置须显式标注，`FR-RISK-19` 验收 2） |
| `disposal_id` | `BIGINT` | NULL, FK → `t_risk_disposal(id)` | 关联的处置动作 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_risk_alert_sub_time` | `sub_account_id, alert_time` | 普通 | **按子账户 + 时间查告警（`FR-RISK-19`）** |
| `idx_risk_alert_type_level` | `alert_type, alert_level, alert_time` | 普通 | 按类型 + 级别筛选；级别统计 |
| `idx_risk_alert_disposed` | `disposed, alert_time` | 普通 | **筛"未处置"告警（`FR-RISK-19` 验收 2、重大事项触发条件④）** |
| `idx_risk_alert_date` | `alert_time` | 普通 | 按日归集（事后风控与风控日报） |

---

#### TB-RISK-5 `t_risk_disposal` 风险处置记录

**用途**：告警的处置动作（告警 / 限制新开仓 / 强制平仓提示 / 解除）（`FR-RISK-15`、`FR-RISK-16`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户（`0`/全账户限制时由应用层写"全账户"占位并置 `is_global='Y'`） |
| `is_global` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否全账户范围 |
| `disposal_type` | `VARCHAR(32)` | NOT NULL, CHECK | `ALERT_ONLY`（仅告警）/ `SUSPEND_OPEN`（限制新开仓）/ `FORCE_CLOSE_HINT`（强制平仓提示）/ `RELEASE_SUSPEND`（解除限制） |
| `scan_scope` | `VARCHAR(16)` | NOT NULL DEFAULT `'OPEN_ONLY'`, CHECK | **限制范围恒为 `OPEN_ONLY`**（**不阻断平仓**，`R-RISK-5`） |
| `reason` | `VARCHAR(500)` | NOT NULL | 处置理由 |
| `hint_instrument_id` | `VARCHAR(32)` | NULL | **强制平仓提示的建议合约**（`FR-RISK-16` 验收 2） |
| `hint_direction` | `VARCHAR(16)` | NULL | 建议方向 |
| `hint_volume` | `INT` | NULL | 建议数量 |
| `hint_accepted` | `CHAR(1)` | NULL, CHECK | 是否接受提示（`'N'` 时须填 `reject_reason`） |
| `reject_reason` | `VARCHAR(500)` | NULL | **不接受提示的理由（必填）**，`FR-RISK-16` 验收 4 |
| `operator_id` | `BIGINT` | NOT NULL | 处置人 |
| `disposal_time` | `TIMESTAMP` | NOT NULL | 处置时间 |
| `dispatch_status` | `VARCHAR(16)` | NULL, CHECK | 下发 C++ 结果（`SUSPEND_OPEN` / `RELEASE_SUSPEND` 须下发） |
| `dispatch_version` | `BIGINT` | NULL | 下发 `param_version` |
| `released_time` | `TIMESTAMP` | NULL | 解除时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_risk_disposal_sub_type` | `sub_account_id, disposal_type` | 普通 | **查该子账户当前是否处于限制状态（下单前提示）** |
| `idx_risk_disposal_active` | `disposal_type, released_time` | 普通 | **取"生效中"的限制（`released_time IS NULL`）** |
| `idx_risk_disposal_time` | `disposal_time` | 普通 | 按时间查处置历史；强制平仓提示未处置稽核（重大事项触发条件④） |
| `idx_risk_disposal_dispatch` | `dispatch_status` | 普通 | 巡检未下发成功的限制（防止"限制了但内存未生效"） |

**备注**：`scan_scope` 恒为 `'OPEN_ONLY'` 的 `CHECK` 落地 `R-RISK-5`
「**只限开仓、不限平仓**」——这是风控的基本安全原则（否则风险无法释放，会放大损失）。

---

#### TB-RISK-6 `t_abnormal_order_stat` 异常下单行为统计

**用途**：按时间窗统计开平仓频次与最短持仓周期（`FR-RISK-14`），用于识别**频繁开平仓**（交易所红线）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `instrument_id` | `VARCHAR(32)` | NULL | 合约（可选维度） |
| `stat_date` | `DATE` | NOT NULL | 统计日期 |
| `window_minutes` | `INT` | NOT NULL | 时间窗（`CFG-05`，默认 1440=1 日；**7 日滚动 = 10080**） |
| `open_close_count` | `INT` | NOT NULL DEFAULT `0` | 时间窗内开平仓次数 |
| `min_hold_minutes` | `INT` | NULL | 最短持仓周期（分钟） |
| `avg_hold_minutes`（**v1.2 补列，`PI-04`/`DC-029`**） | `DECIMAL(20,4)` | NULL | **平均持仓周期（分钟）**（`Q-5`/`FR-RISK-14` 验收 2："平均持仓 **< 3 交易日**"的**可判数据**；3 交易日按交易日历折算分钟，由 `AvgHoldPolicy` 配置） |
| `round_trip_count`（**v1.2 补列，`PI-04`/`DC-029`；v1.3 约束修正，`PI-20`**） | `INT` | **NOT NULL DEFAULT `0`** | **回合数**（一次开+平记 1 个回合；计数类字段**无"未知"语义**，无回合即为 `0`，避免判空歧义）；`Q-5`/`FR-RISK-14` 验收 2："**回合 ≥ 5**"的可判数据；`window_minutes` 取 7 日时即为"7 日回合 ≥5"判据 |
| `last_order_time` | `TIMESTAMP` | NULL | 最近下单时间 |
| `is_abnormal` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否判定为异常 |
| `action_taken` | `VARCHAR(32)` | NULL, CHECK | 触发的动作（`ALERT_ONLY` / `ALERT_AND_SUSPEND_OPEN`，按 `CFG-05`） |
| `order_ids` | `VARCHAR(1000)` | NULL | 涉嫌频繁交易的指令 ID 清单（**含交易理由字段供自证**，`FR-RISK-14` 验收 2） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_abnormal_order_stat` | `stat_date, sub_account_id, product_id, instrument_id, window_minutes` | 唯一 | 幂等 + 定位 |
| `idx_abnormal_stat_abnormal` | `is_abnormal, stat_date` | 普通 | **筛异常记录（`FR-RISK-14` 验收 1，6 类风险之④的证据）** |
| `idx_abnormal_stat_sub_date` | `sub_account_id, stat_date` | 普通 | 按子账户查异常行为史（审计） |

---

#### TB-RISK-7 `t_post_risk_report` 事后风险报表

**用途**：交易日结束后生成并**落库**的风险报表（`FR-RISK-17`）。含风险指标统计三部分内容。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 子账户（按子账户出报表；全账户汇总为 `NULL`） |
| `report_date` | `DATE` | NOT NULL | 报表日期（交易日） |
| `report_type` | `VARCHAR(32)` | NOT NULL, CHECK | `RISK_METRIC`（风险指标统计）/ `VIOLATION_SUMMARY`（违规事项汇总）/ `LIMIT_USAGE`（限额使用情况） |
| `content_json` | `CLOB` | NOT NULL | 报表内容（结构化） |
| `settle_reconcile_key` | `VARCHAR(64)` | NULL | **与结算数据勾稽的键**（`trade_date` + 结算单号；`FR-RISK-17` 验收 3） |
| `generated_by` | `BIGINT` | NOT NULL | 生成人（自动生成为系统账号） |
| `generated_time` | `TIMESTAMP` | NOT NULL | 生成时间 |
| `settle_cycle_id` | `BIGINT` | NULL, FK → `t_settle_cycle(id)` | 关联的结算单（勾稽） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_post_risk_report` | `report_date, report_type, sub_account_id` | 唯一 | 幂等 + 定位 |
| `idx_post_risk_report_date` | `report_date, report_type` | 普通 | **按交易日 + 类型查报表（`FR-RISK-17` 验收 1、`AUDIT` 导出）** |
| `idx_post_risk_report_settle` | `settle_cycle_id` | 普通 | **与结算数据勾稽校验（`FR-RISK-17` 验收 3）** |

---

#### TB-RISK-8 `t_risk_violation` 违规事项

**用途**：六类违规事项的汇总（`FR-RISK-18`、`FR-AUDIT-4`）。**筛查口径复用 `RISK` 规则编号**（`R-AUDIT-7`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `violation_date` | `DATE` | NOT NULL | 发生日期 |
| `violation_type` | `VARCHAR(32)` | NOT NULL, CHECK | `OVER_PRODUCT`（超品种）/ `OVER_SCALE`（超规模）/ `OVER_TERM`（超期限）/ `OVER_AUTH`（超授权）/ `FREQUENT_TRADE`（频繁开平仓）/ `DOC_MISSING`（文档缺失）/ **`REASON_MISSING`（交易理由缺失）与 `RELATED_MISSING`（关联对象缺失）为并列独立的审计筛查维度**（`DC-014`、`Q-13`） |
| `severity` | `VARCHAR(16)` | NOT NULL, CHECK | `LOW` / `MEDIUM` / `HIGH` |
| `rule_id` | `VARCHAR(32)` | NULL | **复用 `RISK` 的规则编号**（口径一致，避免两套标准） |
| `trade_order_id` | `BIGINT` | NULL, FK → `t_trade_order(id)` | 涉及交易指令 |
| `trade_fill_id` | `BIGINT` | NULL, FK → `t_trade_fill(id)` | 涉及成交 |
| `hedge_plan_id` | `BIGINT` | NULL, FK → `t_hedge_plan(id)` | 涉及计划（文档缺失类） |
| `violation_desc` | `VARCHAR(1000)` | NOT NULL | 违规描述（含当前值 / 阈值） |
| `trade_reason_text` | `VARCHAR(1000)` | NULL | **该笔交易的交易理由（供自证或整改）**，`FR-AUDIT-4` |
| `source` | `VARCHAR(24)` | NOT NULL, CHECK | `PRE_TRADE_REJECT` / `INTRADAY_ALERT` / `POST_AUDIT_SCREEN` / `MANUAL` |
| `occur_time` | `TIMESTAMP` | NOT NULL | 发生时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_risk_violation_sub_date` | `sub_account_id, violation_date` | 普通 | 按子账户 + 日期查违规（`FR-RISK-18`、`FR-AUDIT-4`） |
| `idx_risk_violation_type_date` | `violation_type, violation_date` | 普通 | **按类型汇总（`FR-RISK-18` 验收 1、事后风控报表）** |
| `idx_risk_violation_severity` | `severity, violation_date` | 普通 | 按严重级别筛选 |
| `idx_risk_violation_order` | `trade_order_id` | 普通 | 从交易反查违规（追溯链标注） |

---

#### TB-RISK-9 `t_limit_usage_stat` 限额使用统计

**用途**：按**子账户 × 品种**展示限额使用率并标注接近红线者（`FR-RISK-18`）。
使用率 ≥ 80% 提示、≥ 95% 警告（阈值可配置）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种 |
| `stat_date` | `DATE` | NOT NULL | 统计日期 |
| `limit_type` | `VARCHAR(24)` | NOT NULL, CHECK | `PRODUCT_QUOTA`（品种额度）/ `POSITION_LIMIT`（持仓上限）/ `SCALE_REDLINE`（规模红线）/ `FUND_QUOTA`（资金额度）/ `AUTH_QUOTA`（个人授权额度） |
| `limit_value` | `DECIMAL(20,4)` | NOT NULL | 限额值 |
| `used_value` | `DECIMAL(20,4)` | NOT NULL | 已使用值 |
| `usage_ratio` | `DECIMAL(9,6)` | NOT NULL | **使用率** |
| `hint_level` | `VARCHAR(16)` | NOT NULL, CHECK | `NORMAL` / `HINT`（≥80%）/ `WARNING`（≥95%）/ `EXCEEDED`（≥100%） |
| `hint_threshold_1` / `hint_threshold_2` | `DECIMAL(9,6)` | NULL | 提示线 / 警告线（快照，便于审计复现） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_limit_usage_stat` | `stat_date, sub_account_id, product_id, limit_type` | 唯一 | 幂等 + 定位 |
| `idx_limit_usage_sub_date` | `sub_account_id, stat_date` | 普通 | **按子账户查限额使用（`FR-RISK-18` 验收 2）** |
| `idx_limit_usage_hint` | `hint_level, stat_date` | 普通 | **筛接近/超红线项（`FR-RISK-18` 验收 3；`FR-SETTLE-12` 风控日报的规模红线使用率）** |
| `idx_limit_usage_product` | `product_id, stat_date` | 普通 | 按品种查（跨子账户对比） |

---

#### TB-RISK-10 `t_risk_metric_snapshot` 风险指标快照

**用途**：事中指标的时点值（`FR-RISK-12`、`FR-RISK-13`）。用于趋势分析与审计复现。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `snapshot_time` | `TIMESTAMP` | NOT NULL | 快照时间 |
| `snapshot_date` | `DATE` | NOT NULL | 快照日期 |
| `position_qty_net` | `DECIMAL(20,4)` | NULL | 净持仓量 |
| `float_pnl` | `DECIMAL(20,4)` | NULL | 浮动盈亏 |
| `margin_occupied` | `DECIMAL(20,4)` | NULL | 保证金占用 |
| `margin_ratio` | `DECIMAL(9,6)` | NULL | 保证金占用率 |
| `risk_ratio` | `DECIMAL(9,6)` | NULL | 风险度 |
| `available_fund` | `DECIMAL(20,4)` | NULL | 可用资金 |
| `equity` | `DECIMAL(20,4)` | NULL | 权益 |
| `mirror_seq` | `BIGINT` | NOT NULL | 该快照对应的镜像事件序号（可复现） |
| `snapshot_source` | `VARCHAR(16)` | NOT NULL, CHECK | `PERIODIC`（周期快照）/ `EVENT`（事件触发）/ `EOD`（日终） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_risk_metric_sub_time` | `sub_account_id, snapshot_time` | 普通 | **按子账户 + 时间查指标序列（`FR-RISK-19` 趋势分析）** |
| `idx_risk_metric_date_source` | `snapshot_date, snapshot_source` | 普通 | 按日归集；日终快照用于事后风控与风控日报 |
| `idx_risk_metric_mirror_seq` | `mirror_seq` | 普通 | 按事件序号复现指标（审计复现"当时看到的值"） |

**备注**：本表量级大（周期快照），归档策略：**日终快照永久保留，周期快照保留 90 天**
（配置项 `HG.RISK.METRIC_RETAIN_DAYS`）；归档**只移表不删除**。

---

### 2.9 模块 `AUDIT`（审计）—— 8 张

> ⚠️ **只读原则**：`AUDIT` 对其它模块数据**只读**，**不反向写入业务数据**（`R-AUDIT-6`）。
> 本模块的表只承载：**操作留痕、审计日志、筛查结论、报表与导出记录**。

#### TB-AUDIT-1 `t_operation_log` 操作留痕

**用途**：系统内关键操作的记录（`FR-AUDIT-11`、`R-AUDIT-3`）。
覆盖范围：计划提交/审批/终止、指令提交/人工确认、风控拒绝、资金转账与划拨、
权限与授权变更、系统配置变更、结算确认与差异处理、会计凭证审核。**只增不改不删**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | **子账户维度（追溯链须含子账户；账户级操作为 `NULL`）** |
| `operator_id` | `BIGINT` | NOT NULL | **操作人** |
| `operation_type` | `VARCHAR(48)` | NOT NULL | **操作类型**（如 `PLAN_SUBMIT` / `PLAN_APPROVE` / `ORDER_CONFIRM` / `PRE_TRADE_REJECT` / `FUND_TRANSFER_APPROVE` / `PERM_CHANGE` / `CONFIG_CHANGE` / `SETTLE_CONFIRM` / `VOUCHER_REVIEW`） |
| `object_type` | `VARCHAR(32)` | NOT NULL | **对象类型**（表名或实体名） |
| `object_id` | `BIGINT` | NULL | **对象标识** |
| `object_no` | `VARCHAR(64)` | NULL | 对象业务编号（可读，便于审计检索） |
| `action_result` | `VARCHAR(24)` | NOT NULL, CHECK | `SUCCESS` / `FAIL` / `REJECTED` |
| `result_detail` | `VARCHAR(1000)` | NULL | 结果详情（失败原因、拒绝规则编号等） |
| `operation_time` | `TIMESTAMP` | NOT NULL | 操作时间 |
| `client_ip` | `VARCHAR(64)` | NULL | 客户端 IP |
| `trace_id` | `VARCHAR(64)` | NULL | 请求追踪 ID（与日志关联） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_op_log_time_type` | `operation_time, operation_type` | 普通 | **按时间 + 操作类型查留痕（`FR-AUDIT-11`）** |
| `idx_op_log_operator_time` | `operator_id, operation_time` | 普通 | 按操作人查（"谁在什么时候做了什么"） |
| `idx_op_log_object` | `object_type, object_id, operation_time` | 普通 | **按对象查操作轨迹（追溯链"审批/交易"段的留痕）** |
| `idx_op_log_sub_time` | `sub_account_id, operation_time` | 普通 | **审计岗跨子账户查询（`FR-AUDIT-11` 验收 4）** |

**备注**：本表量级最大（千万级）→ 建议按 `operation_time` **按月分区**（`DM8 分区为可选手段`，见 §6）。

---

#### TB-AUDIT-2 `t_audit_log` 审计日志

**用途**：审计操作自身的记录（查询与导出）（`FR-AUDIT-9`、`R-AUDIT-4`）。
**不可篡改；写入失败须中断被记录的操作**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 涉及的子账户范围（跨子账户查询为 `NULL`） |
| `audit_user_id` | `BIGINT` | NOT NULL | **查询人** |
| `action_type` | `VARCHAR(24)` | NOT NULL, CHECK | `QUERY` / `EXPORT` / `VERIFY`（防篡改校验）/ `TRACE_FORWARD` / `TRACE_BACKWARD` / `SCREEN`（违规筛查） |
| `query_condition` | `VARCHAR(2000)` | NULL | **查询条件** |
| `result_count` | `INT` | NOT NULL DEFAULT `0` | **结果条数** |
| `export_file_id` | `VARCHAR(128)` | NULL | **导出文件标识**（`action_type='EXPORT'` 时非空） |
| `chain_integrity` | `VARCHAR(16)` | NULL, CHECK | **链条完整性标注**：`COMPLETE` / `HAS_BREAK` / `IN_FLIGHT` / `UNKNOWN` |
| `action_time` | `TIMESTAMP` | NOT NULL | 操作时间 |
| `prev_hash` | `VARCHAR(128)` | NOT NULL | **前一行的行哈希（链式校验）** |
| `row_hash` | `VARCHAR(128)` | NOT NULL | **本行哈希** = `SHA-256(prev_hash ∥ 关键字段)` |
| `seq_in_chain` | `BIGINT` | NOT NULL | 链内序号（单调递增） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_audit_log_chain_seq` | `seq_in_chain` | 唯一 | **链式校验的顺序基准（防篡改校验的关键）** |
| `idx_audit_log_user_time` | `audit_user_id, action_time` | 普通 | 按查询人查审计日志（`FR-AUDIT-9`） |
| `idx_audit_log_time_type` | `action_time, action_type` | 普通 | 按时间 + 类型查 |
| `idx_audit_log_file` | `export_file_id` | 普通 | 按导出文件标识反查 |

**备注**：
- `prev_hash` / `row_hash` / `seq_in_chain` 是**防篡改机制**（`FR-AUDIT-10`）：任何人直接修改某行，
  该行 `row_hash` 与下一行 `prev_hash` 将不匹配 → 校验失败 → **告警 + 阻断导出**。
- **写出链的并发控制**：`seq_in_chain` 由单线程（`ARCHITECTURE.md` §2.4.4 `J7`）分配，
  保证链不分支（这是唯一需要串行的写路径）。

---

#### TB-AUDIT-3 `t_audit_log_checksum` 审计日志校验值

**用途**：按批次的链头/链尾校验值（`FR-AUDIT-10`），支持增量校验（避免每次全校验全表）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `batch_no` | `BIGINT` | NOT NULL | 批次号（单调递增） |
| `batch_start_seq` / `batch_end_seq` | `BIGINT` | NOT NULL | 批次覆盖的链序号区间 |
| `batch_head_hash` | `VARCHAR(128)` | NOT NULL | 批次首行哈希 |
| `batch_tail_hash` | `VARCHAR(128)` | NOT NULL | 批次末行哈希 |
| `batch_checksum` | `VARCHAR(128)` | NOT NULL | 批次校验和 = `SHA-256(链式累加)` |
| `record_count` | `INT` | NOT NULL | 批次记录数 |
| `created_time` | `TIMESTAMP` | NOT NULL | 批次生成时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_audit_log_checksum_batch` | `batch_no` | 唯一 | 批次唯一 |
| `idx_audit_log_checksum_seq` | `batch_start_seq, batch_end_seq` | 普通 | **按时间范围做增量校验（`FR-AUDIT-10` 校验范围定位）** |

---

#### TB-AUDIT-4 `t_violation_screen_run` 违规筛查运行

**用途**：每次违规筛查的**条件、时间、执行人**（`FR-AUDIT-4`、`R-AUDIT-8`）。
**筛查结论须可复现**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `screen_no` | `VARCHAR(32)` | NOT NULL | 筛查批次号 |
| `date_from` / `date_to` | `DATE` | NOT NULL | 筛查时间范围 |
| `sub_account_scope` | `VARCHAR(500)` | NOT NULL | 子账户范围（可为"全部有权限"） |
| `screen_dimensions` | `VARCHAR(256)` | NOT NULL | **筛查维度**（超品种/超规模/超期限/超授权/频繁开平仓/交易理由缺失） |
| `rule_version` | `VARCHAR(64)` | NULL | **所用规则口径版本**（与 `RISK` 一致，`R-AUDIT-7`） |
| `hit_count` | `INT` | NOT NULL DEFAULT `0` | 命中条数 |
| `operator_id` | `BIGINT` | NOT NULL | **执行人** |
| `screen_time` | `TIMESTAMP` | NOT NULL | **筛查时间** |
| `conclusion` | `VARCHAR(500)` | NOT NULL | **结论**（无命中时须明确"未发现违规"，不得空白，`FR-AUDIT-4` 验收 4） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_violation_screen_no` | `screen_no` | 唯一 | 批次号唯一 |
| `idx_violation_screen_time` | `screen_time` | 普通 | 按时间查筛查历史（可复现性核对） |
| `idx_violation_screen_range` | `date_from, date_to` | 普通 | 按期间查（同一期间多次筛查比对） |

---

#### TB-AUDIT-5 `t_violation_screen_item` 筛查命中项

**用途**：筛查命中的具体记录（`FR-AUDIT-4`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `screen_run_id` | `BIGINT` | NOT NULL, FK → `t_violation_screen_run(id)` | 所属筛查批次 |
| `violation_type` | `VARCHAR(32)` | NOT NULL | 违规类型（与 `t_risk_violation.violation_type` 同域）；**`REASON_MISSING` 与 `RELATED_MISSING` 为 `Q-13` 下并列独立的筛查维度**（`DC-014`） |
| `severity` | `VARCHAR(16)` | NOT NULL, CHECK | 严重级别 |
| `rule_id` | `VARCHAR(32)` | NULL | 规则编号（复用 `RISK`） |
| `object_type` | `VARCHAR(32)` | NOT NULL | 对象类型（`TRADE_ORDER` / `TRADE_FILL` / `HEDGE_PLAN`） |
| `object_id` | `BIGINT` | NOT NULL | 对象 ID |
| `occur_time` | `TIMESTAMP` | NULL | 发生时间 |
| `violation_desc` | `VARCHAR(1000)` | NOT NULL | 描述（含当前值/阈值） |
| `trade_reason_id` | `BIGINT` | NULL, FK → `t_trade_reason(id)` | **关联交易理由（供自证或整改）** |
| `related_object_info` | `VARCHAR(256)` | NULL | **关联对象信息**（计划 ID / 实货敞口 ID；`violation_type='RELATED_MISSING'` 时记录**缺失的具体项**，`Q-13`） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_screen_item_run_type` | `screen_run_id, violation_type` | 普通 | 取某批次命中明细（`FR-AUDIT-4` 输出） |
| `idx_screen_item_sub_time` | `sub_account_id, occur_time` | 普通 | 按子账户 + 时间查命中（跨子账户视图） |
| `idx_screen_item_object` | `object_type, object_id` | 普通 | 从业务对象反查命中记录 |

---

#### TB-AUDIT-6 `t_audit_report` 审计报表

**用途**：季度报表 / 年度专项报告 / 重大事项专项报告（`FR-AUDIT-5`~`FR-AUDIT-7`、`FR-AUDIT-12`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 子账户（按子账户报表时非空；汇总为 `NULL`） |
| `report_no` | `VARCHAR(32)` | NOT NULL | 报表编号 |
| `report_type` | `VARCHAR(32)` | NOT NULL, CHECK | `QUARTERLY` / `ANNUAL_SPECIAL` / `MAJOR_EVENT` |
| `period_key` | `VARCHAR(16)` | NOT NULL | 期间键（`2026-Q3` / `2026` / 事项编号） |
| `content_json` | `CLOB` | NOT NULL | 报表内容（季度含品种/保值规模/盈亏/持仓；年度含五部分；重大事项含五节） |
| `dual_pnl_included` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | **是否含双侧盈亏**（须恒为 `'Y'`，`R-ACCT-4`） |
| `chain_integrity` | `VARCHAR(16)` | NOT NULL, CHECK | **链条完整性标注**：`COMPLETE` / `HAS_BREAK` / `IN_FLIGHT` / `UNKNOWN`（**有断点须显式标注**；**数据缺失致不可判定时标 `UNKNOWN`，不得误标为完整**，`R-AUDIT-2`、`#19`④；v1.3.1 与 `t_audit_log` 统一为 4 值，`DC-038`） |
| `break_list` | `CLOB` | NULL | **断点清单**（`chain_integrity≠'COMPLETE'` 时非空） |
| `data_gap_desc` | `VARCHAR(1000)` | NULL | 数据缺口说明 |
| `generated_by` | `BIGINT` | NOT NULL | 生成人 |
| `generated_time` | `TIMESTAMP` | NOT NULL | 生成时间 |
| `submitted` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否已提交（重大事项专项报告） |
| `submitted_to` | `VARCHAR(128)` | NULL | 提交对象 |
| `submitted_time` | `TIMESTAMP` | NULL | 提交时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_audit_report_no` | `report_no` | 唯一 | 报表编号唯一 |
| `idx_audit_report_type_period` | `report_type, period_key` | 普通 | **按类型 + 期间查报表（`FR-AUDIT-12` 导出）** |
| `idx_audit_report_integrity` | `chain_integrity, generated_time` | 普通 | **筛"链条不完整"的报表（`R-AUDIT-2`：不得粉饰）** |
| `idx_audit_report_sub_time` | `sub_account_id, generated_time` | 普通 | 按子账户查报表 |

---

#### TB-AUDIT-7 `t_evidence_export` 证据链导出记录

**用途**：证据链导出的记录（`FR-AUDIT-8`）。**导出动作进入审计日志**；**存在断点须显式标注**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `export_no` | `VARCHAR(32)` | NOT NULL | 导出批次号 |
| `start_point_type` | `VARCHAR(16)` | NOT NULL, CHECK | `EXPOSURE` / `TRADE` / `VOUCHER`（追溯起点类型） |
| `start_point_ids` | `VARCHAR(2000)` | NOT NULL | 追溯起点标识集合 |
| `date_from` / `date_to` | `DATE` | NULL | 时间范围 |
| `sub_account_scope` | `VARCHAR(500)` | NOT NULL | 子账户范围 |
| `include_source_detail` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否含原始单据明细 |
| `chain_integrity` | `VARCHAR(16)` | NOT NULL, CHECK | 链条完整性：`COMPLETE` / `HAS_BREAK` / `IN_FLIGHT` / `UNKNOWN`（与 `t_audit_log` / `t_audit_report` 同域；**数据缺失致不可判定时标 `UNKNOWN`**，v1.3.1 `DC-038`） |
| `break_list` | `CLOB` | NULL | 断点清单 |
| `dual_pnl_included` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | **是否含双侧盈亏（须恒为 `'Y'`）** |
| `reason_included` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | **是否含交易理由（须恒为 `'Y'`）** |
| `file_id` | `VARCHAR(128)` | NOT NULL | 导出文件标识 |
| `file_path` | `VARCHAR(256)` | NULL | 文件路径 |
| `exported_by` | `BIGINT` | NOT NULL | 导出人 |
| `exported_time` | `TIMESTAMP` | NOT NULL | 导出时间 |
| `audit_log_id` | `BIGINT` | NULL, FK → `t_audit_log(id)` | **关联的审计日志（先写日志后导出；写失败则中断导出）** |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_evidence_export_no` | `export_no` | 唯一 | 批次号唯一 |
| `idx_evidence_export_time` | `exported_time` | 普通 | 按时间查导出历史（`FR-AUDIT-8` 验收 3） |
| `idx_evidence_export_integrity` | `chain_integrity` | 普通 | 筛"链条不完整"的导出（合规自查） |
| `idx_evidence_export_audit_log` | `audit_log_id` | 普通 | **校验"导出必有审计日志"（`R-AUDIT-4`：不记录即不允许发生）** |

---

#### TB-AUDIT-8 `t_major_event` 重大事项

**用途**：重大事项的触发与专项报告关联（`FR-AUDIT-7`、`R-AUDIT-5`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 涉及子账户 |
| `event_no` | `VARCHAR(32)` | NOT NULL | 事项编号 |
| `event_type` | `VARCHAR(32)` | NOT NULL, CHECK | `AGG_INCONSISTENT_TIMEOUT`（加总对账不一致且超时未闭环）/ `SCALE_REDLINE_HIT`（触及规模红线）/ `EXCHANGE_INQUIRY`（被交易所问询或收到警示）/ `FORCE_CLOSE_HINT_UNDISPOSED`（强制平仓提示未处置）/ `LOSS_THRESHOLD`（单笔或当日亏损超阈值） |
| `trigger_source` | `VARCHAR(128)` | NOT NULL | 触发来源（关联的对象标识） |
| `event_desc` | `VARCHAR(2000)` | NOT NULL | 事项描述 |
| `impact_scope` | `VARCHAR(1000)` | NULL | 影响范围 |
| `handling_measures` | `VARCHAR(2000)` | NULL | 处置措施 |
| `rectification_plan` | `VARCHAR(2000)` | NULL | 整改计划 |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `TRIGGERED`（已触发待生成）/ `REPORT_GENERATED`（已生成）/ `SUBMITTED`（已提交） |
| `audit_report_id` | `BIGINT` | NULL, FK → `t_audit_report(id)` | 关联的专项报告 |
| `triggered_time` | `TIMESTAMP` | NOT NULL | 触发时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_major_event_no` | `event_no` | 唯一 | 事项编号唯一 |
| `idx_major_event_type_status` | `event_type, status` | 普通 | **筛"已触发待生成"（提醒生成专项报告，`FR-AUDIT-7` 验收 1）** |
| `idx_major_event_time` | `triggered_time` | 普通 | 按时间查重大事项（年度报告汇总） |

### 2.10 模块 `ACCT`（套保会计）—— 12 张

> ⚠️ **本模块按 CAS 24 / IFRS 9** 生成套期关系文档、有效性评估、再平衡与会计凭证；
> **实货盈亏与衍生品盈亏必须同时呈现、同时上报**（`R-ACCT-4`，17 号文红线）。
> **本期全部文档须标注"待外部复核"**（`R-ACCT-10`）。
> **凭证必须按子账户出账；子账户标识缺失时拒绝生成凭证**（`R-ACCT-5`）。

#### TB-ACCT-1 `t_hedge_relation` 套期关系

**用途**：被套期项目（实货敞口）与套期工具（期货/期权合约）之间的**正式指定**关系（`FR-ACCT-1`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `relation_no` | `VARCHAR(32)` | NOT NULL | 套期关系编号（全系统唯一） |
| `hedge_plan_id` | `BIGINT` | NOT NULL, FK → `t_hedge_plan(id)` | 套保计划 |
| `hedged_item_exposure_id` | `BIGINT` | NOT NULL, FK → `t_exposure(id)` | **被套期项目（已识别的实货敞口）** |
| `hedging_instrument_type` | `VARCHAR(16)` | NOT NULL, CHECK | `FUTURES` / `OPTION`（**期权与期货同等对待**） |
| `hedging_instrument_desc` | `VARCHAR(500)` | NOT NULL | 套期工具描述（合约与持仓标识） |
| `hedge_doc_id` | `BIGINT` | NOT NULL, FK → `t_hedge_doc(id)` | **关联的套期关系书面文件（三要素来源）** |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `DESIGNATED`（已指定）/ `EFFECTIVE`（有效）/ `NEED_REBALANCE`（需再平衡）/ `REDESIGNATED`（已重新指定）/ `TERMINATED`（已终止） |
| `designated_time` | `TIMESTAMP` | NOT NULL | 正式指定时间 |
| `terminated_time` | `TIMESTAMP` | NULL | 终止时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_hedge_relation_no` | `relation_no` | 唯一 | 编号唯一 |
| `idx_hedge_relation_plan` | `hedge_plan_id` | 普通 | **按计划取套期关系（`FR-ACCT-1`、有效性评估入口）** |
| `idx_hedge_relation_sub_status` | `sub_account_id, status` | 普通 | 按子账户查套期关系；筛"需再平衡" |
| `idx_hedge_relation_exposure` | `hedged_item_exposure_id` | 普通 | 从敞口反查套期关系（追溯链） |

**备注**：**套期关系一经指定不得静默变更**（`ACCT.md` §2 数据字典）→ 状态机限制 +
要素变更须终止后新建（或在 `t_rebalance_record` 中走"重新指定"并留痕）。

---

#### TB-ACCT-2 `t_effectiveness_assess` 有效性评估记录

**用途**：按会计期间对套期关系有效性的持续评估（`FR-ACCT-2`、`FR-ACCT-4`）。
**定性方法（关注经济关系）；每期至少一次**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `hedge_relation_id` | `BIGINT` | NOT NULL, FK → `t_hedge_relation(id)` | 套期关系 |
| `period_key` | `VARCHAR(16)` | NOT NULL | 会计期间（`2026-09`） |
| `assess_method` | `VARCHAR(32)` | NOT NULL, CHECK | `QUALITATIVE_ECONOMIC_RELATION`（**定性经济关系，本期唯一方法**） |
| `observer_linkage` | `CHAR(1)` | NOT NULL, CHECK | **观察点①品种相关性**（`Q-7`）：同品种为强；近相关须**书面说明经济关系**（`linkage_note`） |
| `linkage_note` | `VARCHAR(1000)` | NULL | 近相关时的**书面经济关系说明**（`Q-7` 要求） |
| `observer_direction` | `CHAR(1)` | NOT NULL, CHECK | **观察点②方向一致性**（`Q-7`）：买入套保 ↔ 采购敞口；卖出套保 ↔ 销售 / 库存敞口 |
| `observer_term` | `CHAR(1)` | NOT NULL, CHECK | **观察点③期限对应性**（`Q-7`）：敞口预计实现日 **±3 个月** |
| `exposure_expected_date` | `DATE` | NULL | 敞口预计实现日（期限对应性的判定基准） |
| `term_deviation_days` | `INT` | NULL | 期限偏离天数（**绝对值须 ≤ 90 天**，即 ±3 个月） |
| `monitor_deviation_ratio` | `DECIMAL(9,6)` | NULL | **量化监测指标**（`Q-7`）：累计衍生品盈亏 / 累计实货盈亏 **偏离 1** 的值；**超 ±50% 提示复核** |
| `monitor_review_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 监测值超阈值 → 提示复核（**仅监测与触发再平衡，不用于判定有效性**） |
| `observer_detail` | `CLOB` | NULL | 各观察点的**判定数据与过程**（**结论须可复现**，`FR-ACCT-4` 验收 3） |
| `conclusion` | `VARCHAR(24)` | NOT NULL, CHECK | `EFFECTIVE`（有效）/ `INEFFECTIVE`（无效）/ `NEED_REBALANCE`（需再平衡） |
| `need_action_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否需终止或再平衡**（结论为无效/需再平衡时为 `'Y'`，`FR-ACCT-2` 验收 2） |
| `action_note` | `VARCHAR(500)` | NULL | **明确标注"需终止或再平衡"**（**不得自动修改会计处理**） |
| `assessor_id` | `BIGINT` | NOT NULL | 评估人 |
| `assess_time` | `TIMESTAMP` | NOT NULL | 评估时间 |
| `corrected_from_id` | `BIGINT` | NULL, FK → `t_effectiveness_assess(id)` | **更正来源**（评估记录不得事后修改，更正须新增记录并引用原记录） |
| `is_corrected` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否已被更正 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_eff_assess_rel_period` | `hedge_relation_id, period_key, is_corrected` | 唯一 | **每期至少一次评估；更正记录不冲突** |
| `idx_eff_assess_sub_period` | `sub_account_id, period_key` | 普通 | **按子账户 + 期间查评估（`FR-ACCT-2`、关账门禁 `R-ACCT-8`）** |
| `idx_eff_assess_conclusion` | `conclusion, period_key` | 普通 | **筛无效/需再平衡（`FR-ACCT-2` 验收 2、再平衡触发检查）** |
| `idx_eff_assess_monitor` | `monitor_review_flag, period_key` | 普通 | **筛"量化监测超阈值需复核"的记录（`Q-7`；仅提示，不判定）** |

**备注**：`observer_*` 三列拆分存储（而非一个 JSON）使"三个观察点是否满足"**可被索引与统计**
——这是 `FR-ACCT-2` 验收 1"含结论与观察点明细"与 `FR-ACCT-14` 自检"逐条给出通过/不通过"的结构支撑。

---

#### TB-ACCT-3 `t_rebalance_record` 再平衡记录

**用途**：再平衡操作的全过程记录（`FR-ACCT-3`）。**须重新生成书面文件与正式指定，不得只改数字**（`R-ACCT-3`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `hedge_relation_id` | `BIGINT` | NOT NULL, FK → `t_hedge_relation(id)` | 套期关系 |
| `rebalance_no` | `VARCHAR(32)` | NOT NULL | 再平衡编号 |
| `trigger_type` | `VARCHAR(32)` | **NOT NULL**, CHECK | **触发类型（`Q-8` 已确认四类，满足任一即触发评估）**：`EXPOSURE_DELTA`（实货敞口变动 > 20%）/ `RATIO_DEVIATION`（套期比率偏离 > 20%）/ `PLAN_REVISED`（计划数量被修订 / 终止）/ `EXPOSURE_GONE`（敞口消失需终止关系） |
| `trigger_side` | `VARCHAR(16)` | **NOT NULL**, CHECK | **触发条件所在侧**（`Q-8`：**敞口侧与工具侧必须各设一项**）：`EXPOSURE_SIDE`（敞口侧）/ `INSTRUMENT_SIDE`（工具侧） |
| `trigger_reason` | `VARCHAR(1000)` | NOT NULL | **触发原因描述**（含量化判定值） |
| `trigger_exposure_delta` | `DECIMAL(9,6)` | NULL | 敞口变化幅度（触发判定值） |
| `trigger_ratio_deviation` | `DECIMAL(9,6)` | NULL | 比率偏离（触发判定值） |
| `trigger_threshold` | `DECIMAL(9,6)` | NOT NULL | 触发阈值（快照，来自 `CFG-16`） |
| `ratio_before` | `DECIMAL(9,6)` | NOT NULL | **调整前套保比率** |
| `ratio_after` | `DECIMAL(9,6)` | NOT NULL | **调整后套保比率** |
| `qty_before` | `DECIMAL(20,4)` | NOT NULL | 调整前数量 |
| `qty_after` | `DECIMAL(20,4)` | NOT NULL | 调整后数量 |
| `new_hedge_doc_id` | `BIGINT` | NOT NULL, FK → `t_hedge_doc(id)` | **重新生成的书面文件（不得只改数字）** |
| `new_hedge_relation_id` | `BIGINT` | NULL, FK → `t_hedge_relation(id)` | 重新指定后的套期关系 |
| `executor_id` | `BIGINT` | NOT NULL | 执行人 |
| `rebalance_time` | `TIMESTAMP` | NOT NULL | 执行时间 |
| `plan_feedback_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否已反馈至 `PLAN`（如需调整计划，`R-ACCT-3`） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_rebalance_no` | `rebalance_no` | 唯一 | 编号唯一 |
| `idx_rebalance_relation_time` | `hedge_relation_id, rebalance_time` | 普通 | **按套期关系查再平衡史（`FR-ACCT-3`、CAS24 自检④）** |
| `idx_rebalance_sub_time` | `sub_account_id, rebalance_time` | 普通 | 按子账户查再平衡（审计） |
| `idx_rebalance_trigger` | `trigger_type, trigger_side, rebalance_time` | 普通 | **按触发类型 + 所在侧统计（`Q-8`：核验"敞口侧与工具侧各设一项"是否落实）** |

---

#### TB-ACCT-4 `t_voucher` 会计凭证（头）

**用途**：会计处理的记账凭证（草稿与正式）（`FR-ACCT-6`、`FR-ACCT-7`、`FR-ACCT-13`）。
**必须按子账户出账；子账户标识缺失时拒绝生成凭证**（`R-ACCT-5`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | **NOT NULL**, FK → `t_sub_account(id)` | **子账户（凭证类贯穿字段；`NOT NULL` 使"缺标识拒绝生成凭证"在结构上成立）** |
| `voucher_no` | `VARCHAR(32)` | **NOT NULL, UNIQUE** | **凭证编号，全系统唯一，一经分配不得修改**（被审计追溯引用） |
| `period_key` | `VARCHAR(16)` | NOT NULL | 会计期间 |
| `voucher_date` | `DATE` | NOT NULL | 凭证日期 |
| `voucher_type` | `VARCHAR(32)` | NOT NULL, CHECK | `CLOSE_PNL`（平仓盈亏结转）/ `COMMISSION`（手续费）/ `MARGIN_CHANGE`（保证金占用变动）/ `FLOAT_PNL`（浮动盈亏）/ `PREMIUM`（期权权利金）/ **`EXERCISE`（期权行权，v1.2 补）/ `EXPIRED_WORTHLESS`（期权到期作废，v1.2 补）** / `HEDGE_EFFECT`（套保效果归集）/ `REVERSAL`（冲销凭证）/ `OTHER` —— 后两项 `EXERCISE`/`EXPIRED_WORTHLESS` 系 `FR-ACCT-11`（期权行权与到期作废）的凭证类型落点（`PI-13`/`DC-031`） |
| `hedge_plan_id` | `BIGINT` | NULL, FK → `t_hedge_plan(id)` | 关联套保计划 |
| `hedge_relation_id` | `BIGINT` | NULL, FK → `t_hedge_relation(id)` | 关联套期关系 |
| `settle_cycle_id` | `BIGINT` | NULL, FK → `t_settle_cycle(id)` | **关联结算单（追溯链：凭证 → 结算）** |
| `exposure_id` | `BIGINT` | NULL, FK → `t_exposure(id)` | **关联实货敞口（追溯链终点/起点）** |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `DRAFT` / `PENDING_REVIEW` / `POSTED`（已审核-正式）/ `REVERSED`（已冲销） |
| `total_amount` | `DECIMAL(20,4)` | NOT NULL | 借贷合计金额（借贷须平衡） |
| `generated_by` | `BIGINT` | NOT NULL | **凭证生成人**（**不得为审核人**，`R-ACCT-6`） |
| `generated_time` | `TIMESTAMP` | NOT NULL | 生成时间 |
| `reversal_of_id` | `BIGINT` | NULL, FK → `t_voucher(id)` | **原凭证（冲销凭证时非空）** |
| `reversed_by_id` | `BIGINT` | NULL, FK → `t_voucher(id)` | 被哪张凭证冲销 |
| `reversal_reason` | `VARCHAR(500)` | NULL | 冲销原因（**必填**，`FR-ACCT-13`） |
| `pending_external_review` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | **"待外部复核"标注（恒为 `'Y'`）**，`R-ACCT-10` |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_voucher_no` | `voucher_no` | 唯一 | **凭证编号唯一（`FR-ACCT-6`）；按编号查询（`FR-ACCT-8`）** |
| `idx_voucher_sub_period_status` | `sub_account_id, period_key, status` | 普通 | **凭证列表按子账户 + 期间 + 状态筛选（`FR-ACCT-8`）；关账门禁筛未审核凭证** |
| `idx_voucher_plan` | `hedge_plan_id` | 普通 | 按计划取凭证（追溯链、套保效果勾稽） |
| `idx_voucher_settle_cycle` | `settle_cycle_id` | 普通 | **从结算反查凭证（`FR-AUDIT-2` 反向追溯）** |
| `idx_voucher_exposure` | `exposure_id` | 普通 | **从敞口反查凭证（`FR-AUDIT-1` 正向追溯终点）** |
| `idx_voucher_reversal` | `reversal_of_id, reversed_by_id` | 普通 | 冲销关系查询（`FR-ACCT-13`） |

**备注**：
- `sub_account_id NOT NULL` 是无条件的 —— 这是"**子账户标识缺失时拒绝生成凭证**"
  （`FR-ACCT-6` 验收 2）的**数据库级**保证（缺标识的凭证**根本无法插入**）。
- `voucher_no` 不变 + `status` 流转 → "正式凭证不得修改，只能冲销"（`R-ACCT-5`）在结构上成立。

---

#### TB-ACCT-5 `t_voucher_line` 凭证分录行

**用途**：凭证的借贷分录（`FR-ACCT-6`）。科目来自 `CONFIG` 科目字典（**不得硬编码**，`R-ACCT-11`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户（**冗余自凭证头，便于直接按子账户聚合金额**） |
| `voucher_id` | `BIGINT` | NOT NULL, FK → `t_voucher(id)` | 所属凭证 |
| `line_no` | `INT` | NOT NULL | 分录序号 |
| `subject_code` | `VARCHAR(32)` | NOT NULL, FK → `t_account_subject(subject_code)` | **会计科目编码（来自字典）** |
| `subject_name` | `VARCHAR(128)` | NULL | 科目名称（冗余，便于导出） |
| `direction` | `VARCHAR(8)` | NOT NULL, CHECK | `DEBIT`（借）/ `CREDIT`（贷） |
| `amount` | `DECIMAL(20,4)` | NOT NULL | 金额；**必须 > 0** |
| `qty` | `DECIMAL(20,4)` | NULL | 数量（如成交手数） |
| `trade_fill_id` | `BIGINT` | NULL, FK → `t_trade_fill(id)` | **关联成交记录（追溯链：凭证 → 交易）** |
| `biz_desc` | `VARCHAR(500)` | NULL | 业务摘要（凭证摘要） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_voucher_line` | `voucher_id, line_no` | 唯一 | 分录序号唯一 |
| `idx_voucher_line_voucher` | `voucher_id` | 普通 | **取凭证分录（`FR-ACCT-8`、导出 `FR-ACCT-9`）** |
| `idx_voucher_line_subject` | `subject_code, voucher_id` | 普通 | 按科目汇总（科目余额、审计抽查） |
| `idx_voucher_line_fill` | `trade_fill_id` | 普通 | **从成交反查凭证（`FR-AUDIT-2` 反向追溯）** |
| `idx_voucher_line_sub` | `sub_account_id` | 普通 | 按子账户聚合金额（子账户出账核对） |

---

#### TB-ACCT-6 `t_accounting_period` 会计期间

**用途**：会计期间的开放 / 已关账（`FR-ACCT-12`）。**关账后不得在该期间产生新凭证**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `period_key` | `VARCHAR(16)` | **NOT NULL, UNIQUE** | 期间键（`2026-09`） |
| `period_type` | `VARCHAR(16)` | NOT NULL, CHECK | `MONTH` / `QUARTER` / `YEAR` |
| `period_start` / `period_end` | `DATE` | NOT NULL | 期间起止 |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `OPEN`（开放）/ `CLOSED`（已关账） |
| `closed_by` | `BIGINT` | NULL | 关账人 |
| `closed_time` | `TIMESTAMP` | NULL | 关账时间 |
| `close_reviewer_id` | `BIGINT` | NULL | 关账复核人（**≠ `closed_by`**，`R-ACCT-8`） |
| `reopened_by` / `reopened_time` | `BIGINT` / `TIMESTAMP` | NULL | **撤销关账**的操作人与时间（**须双人复核并留痕**，`FR-ACCT-12` 验收 4） |
| `reopen_reason` | `VARCHAR(500)` | NULL | 撤销关账原因 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_accounting_period_key` | `period_key` | 唯一 | 期间唯一 |
| `idx_accounting_period_status_range` | `status, period_start, period_end` | 普通 | **判断某日期是否落在已关账期间（凭证生成前置校验）** |

---

#### TB-ACCT-7 `t_period_close_log` 关账留痕

**用途**：关账与撤销关账的完整留痕（`FR-ACCT-12`）。**只增不改不删**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `period_key` | `VARCHAR(16)` | NOT NULL | 期间 |
| `action` | `VARCHAR(16)` | NOT NULL, CHECK | `CLOSE` / `REOPEN` |
| `precheck_result` | `CLOB` | NOT NULL | **关账前置校验结果**（① 全部凭证已审核/已冲销 ② 结算已完成 ③ 有效性评估已完成） |
| `operator_id` | `BIGINT` | NOT NULL | 操作人 |
| `reviewer_id` | `BIGINT` | NOT NULL | 复核人（**≠ `operator_id`**） |
| `reason` | `VARCHAR(500)` | NULL | 原因（撤销关账时必填） |
| `action_time` | `TIMESTAMP` | NOT NULL | 操作时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_period_close_log_key_time` | `period_key, action_time` | 普通 | 按期间查关账历史（`FR-ACCT-12` 审计） |
| `idx_period_close_log_operator` | `operator_id, action_time` | 普通 | 按操作人查（内部审计） |

---

#### TB-ACCT-8 `t_hedge_effect` 套保效果（双侧盈亏）

**用途**：**实货盈亏 + 衍生品盈亏 + 净效果 + 未对冲部分**（`FR-ACCT-5`、`FR-ACCT-11`）。
**两侧盈亏必须同时呈现**（`R-ACCT-4`，17 号文）——**本表结构强制两侧列同时存在**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `period_key` | `VARCHAR(16)` | NOT NULL | 会计期间 |
| `hedge_plan_id` | `BIGINT` | NULL, FK → `t_hedge_plan(id)` | 套保计划（按计划归集；子账户级汇总时可为 `NULL`） |
| `exposure_id` | `BIGINT` | NULL, FK → `t_exposure(id)` | 实货敞口 |
| `spot_pnl_realized` | `DECIMAL(20,4)` | NULL | **实货盈亏（已实现）** |
| `spot_pnl_unrealized` | `DECIMAL(20,4)` | NULL | **实货盈亏（未实现）** |
| `spot_pnl_total` | `DECIMAL(20,4)` | NULL | 实货盈亏合计 |
| `derivative_pnl_realized` | `DECIMAL(20,4)` | NULL | **衍生品盈亏（已实现-平仓）** |
| `derivative_pnl_unrealized` | `DECIMAL(20,4)` | NULL | **衍生品盈亏（未实现-浮动）** |
| `derivative_pnl_total` | `DECIMAL(20,4)` | NULL | 衍生品盈亏合计 |
| `net_effect` | `DECIMAL(20,4)` | NULL | **综合评判净效果**（两侧合计） |
| `unhedged_qty` | `DECIMAL(20,4)` | NULL | **未完全对冲的敞口数量（须明确展示）** |
| `unhedged_amount` | `DECIMAL(20,4)` | NULL | 未对冲敞口金额 |
| `hedged_ratio_actual` | `DECIMAL(9,6)` | NULL | 实际套保比率 |
| `spot_price_missing` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **实货盈亏因缺价无法计算**（`'Y'` 时实货盈亏列必须为 `NULL` 并填写 `missing_desc`，**不得省略栏位**，`R-ACCT-9`） |
| `missing_desc` | `VARCHAR(1000)` | NULL | **缺失的品种与日期清单**（`Q-12`：估值用途下**沿用最近一期但必须标注**） |
| `price_as_of` | `DATE` | NULL | **实货盈亏计算所用现货价的实际时点**（`Q-12` 已确认：沿用最近一期时**必带**此字段） |
| `non_current_price_flag` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否使用了"非当日价"**（`Q-12`：结果须标记"非当日价"，**不得无标注静默替代**） |
| `calc_time` | `TIMESTAMP` | NOT NULL | 计算时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_hedge_effect_key` | `period_key, sub_account_id, hedge_plan_id, exposure_id` | 唯一 | 幂等 + 定位 |
| `idx_hedge_effect_sub_period` | `sub_account_id, period_key` | 普通 | **按子账户 + 期间取套保效果（`FR-ACCT-5` 同屏展示、`NFR-USE-5`）** |
| `idx_hedge_effect_plan_period` | `hedge_plan_id, period_key` | 普通 | 按计划取（计划效果评估） |
| `idx_hedge_effect_missing` | `spot_price_missing, period_key` | 普通 | 筛"实货盈亏无法计算"的记录（`CFG-12b=REJECT` 时才出现） |
| `idx_hedge_effect_price_asof` | `non_current_price_flag, period_key` | 普通 | **筛"使用了非当日价"的记录（`Q-12` 审计可核验：哪些估值沿用了历史价）** |

**备注**：`spot_pnl_*` 与 `derivative_pnl_*` **成对存在于同一行** ——
这是 `R-ACCT-4`「**禁止只报一侧**」在**表结构层面**的实现：**没有"只有衍生品盈亏"的行**。
`spot_price_missing='Y'` 时实货列可为 `NULL`，但**列仍存在且必须给出缺失说明**（"保留栏位 + 标注原因"）。

---

#### TB-ACCT-9 `t_dual_pnl_report` 双侧盈亏上报

**用途**：实货盈亏与衍生品盈亏**同时上报**的记录（`FR-ACCT-10`）。
**禁止只报衍生品盈亏**；**实货盈亏无法计算时保留栏位并标注原因，不得省略**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 子账户（上报按子账户 + 汇总） |
| `report_no` | `VARCHAR(32)` | NOT NULL | 上报编号 |
| `report_period` | `VARCHAR(16)` | NOT NULL | 报告期间 |
| `report_type` | `VARCHAR(16)` | NOT NULL, CHECK | `MONTHLY` / `QUARTERLY` / `ANNUAL` |
| `spot_pnl_column_present` | `CHAR(1)` | NOT NULL, CHECK | **实货盈亏栏位是否存在（恒为 `'Y'`）**——**禁止以"缺数据"为由省略栏位** |
| `spot_pnl_value` | `DECIMAL(20,4)` | NULL | 实货盈亏值（无法计算时为 `NULL`） |
| `spot_pnl_missing_reason` | `VARCHAR(1000)` | NULL | **无法计算的原因 + 缺失品种与日期**（`spot_pnl_value IS NULL` 时必填） |
| `derivative_pnl_value` | `DECIMAL(20,4)` | NOT NULL | 衍生品盈亏值 |
| `derivative_pnl_realized` / `_unrealized` | `DECIMAL(20,4)` | NULL | 衍生品盈亏拆分 |
| `net_effect` | `DECIMAL(20,4)` | NULL | 净效果 |
| `data_completeness_note` | `VARCHAR(1000)` | NULL | 数据完整性说明 |
| `submitted_by` | `BIGINT` | NOT NULL | **上报人** |
| `submitted_time` | `TIMESTAMP` | NOT NULL | **上报时间** |
| `pending_external_review` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 待外部复核标注 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_dual_pnl_report_no` | `report_no` | 唯一 | 编号唯一 |
| `idx_dual_pnl_period_type` | `report_period, report_type` | 普通 | **按期间 + 类型查上报记录（`FR-ACCT-10` 验收 3）** |
| `idx_dual_pnl_sub_period` | `sub_account_id, report_period` | 普通 | 按子账户查上报（审计） |

**备注**：`spot_pnl_column_present` 恒为 `'Y'` 的 `CHECK` 是
`FR-ACCT-10` 验收 2「**不省略该栏**」的**结构保证**。

---

#### TB-ACCT-10 `t_voucher_review` 凭证审核记录

**用途**：凭证审核链（`FR-ACCT-7`）。**草稿生成人与审核人不得为同一人**（`R-ACCT-6`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `voucher_id` | `BIGINT` | NOT NULL, FK → `t_voucher(id)` | 凭证 |
| `node_seq` | `INT` | NOT NULL | 审核节点序号 |
| `action` | `VARCHAR(16)` | NOT NULL, CHECK | `SUBMIT` / `APPROVE` / `REJECT` / `WITHDRAW` |
| `generator_id` | `BIGINT` | NOT NULL | **凭证生成人**（冗余，供"生成人≠审核人"校验） |
| `reviewer_id` | `BIGINT` | NULL | **审核人（必须 ≠ `generator_id`）** |
| `co_reviewer_id`（**v1.2 补列，`PI-11`/`DC-032`**） | `BIGINT` | NULL | **第二复核人**（**冲销 / 调整类凭证的双人复核**，`FR-ACCT-13`、`R-ACCT-6`、`R-SETTLE-5` 口径）；**须 ≠ `reviewer_id` 且 ≠ `generator_id`**（服务层校验）；非冲销/调整类为 `NULL` |
| `review_result` | `VARCHAR(16)` | NULL, CHECK | `APPROVED` / `REJECTED` |
| `review_comment` | `VARCHAR(1000)` | NULL | 审核意见 |
| `reject_reason` | `VARCHAR(500)` | NULL | **驳回原因（驳回时必填）** |
| `action_time` | `TIMESTAMP` | NOT NULL | 动作时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_voucher_review_voucher_seq` | `voucher_id, node_seq` | 普通 | **取凭证审核链（`FR-ACCT-7` 验收 4）** |
| `idx_voucher_review_reviewer` | `reviewer_id, action_time` | 普通 | 按审核人查历史；"待我审核"集合 |

**备注**：**冲销 / 调整类凭证走同一审核链，且须"双人复核"**（`FR-ACCT-13`）：除"生成人 ≠ 审核人"外，
另须填写 `co_reviewer_id`（第二复核人，≠ 审核人、≠ 生成人）—— 这是 `PI-11`/`DC-032` 的落点，
使"冲销须双人复核"在**结构上可校验、可测**（用例：三人中有两人相同 → 拒绝）。

---

#### TB-ACCT-11 `t_cas24_selfcheck` CAS 24 条款自检（运行）

**用途**：CAS 24 条款自检清单的运行记录（`FR-ACCT-14`）。
**自检不替代外部复核，须显式说明；结论须导出为自检报告并标注"待外部复核"**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 子账户 |
| `selfcheck_no` | `VARCHAR(32)` | NOT NULL | 自检批次号 |
| `scope_type` | `VARCHAR(16)` | NOT NULL, CHECK | `HEDGE_RELATION`（按套期关系）/ `PERIOD`（按会计期间） |
| `scope_key` | `VARCHAR(64)` | NOT NULL | 范围键（关系编号 / 期间键） |
| `pass_count` / `fail_count` | `INT` | NOT NULL DEFAULT `0` | 通过 / 不通过条数 |
| `conclusion` | `VARCHAR(24)` | NOT NULL, CHECK | `ALL_PASS` / `HAS_FAIL` / `CANNOT_SELFCHECK`（关键数据缺失） |
| `cannot_selfcheck_reason` | `VARCHAR(1000)` | NULL | 无法自检的原因（`conclusion='CANNOT_SELFCHECK'` 时必填） |
| `report_file_id` | `VARCHAR(128)` | NULL | **自检报告导出文件标识** |
| `pending_external_review` | `CHAR(1)` | NOT NULL DEFAULT `'Y'`, CHECK | 待外部复核标注 |
| `not_substitute_note` | `VARCHAR(500)` | NOT NULL DEFAULT `'自检不替代外部复核'` | **须显式说明**（`R-ACCT-10`） |
| `executor_id` | `BIGINT` | NOT NULL | 执行人 |
| `selfcheck_time` | `TIMESTAMP` | NOT NULL | 自检时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_cas24_selfcheck_no` | `selfcheck_no` | 唯一 | 批次号唯一 |
| `idx_cas24_selfcheck_scope` | `scope_type, scope_key` | 普通 | **按套期关系/期间查自检结论（`FR-ACCT-14`）** |
| `idx_cas24_selfcheck_conclusion` | `conclusion, selfcheck_time` | 普通 | 筛"不通过/无法自检"（合规自查巡检） |

---

#### TB-ACCT-12 `t_cas24_selfcheck_item` CAS 24 自检条目

**用途**：逐条自检结论与**证据位置**（`FR-ACCT-14`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `selfcheck_id` | `BIGINT` | NOT NULL, FK → `t_cas24_selfcheck(id)` | 所属自检批次 |
| `item_seq` | `INT` | NOT NULL | 条目序号 |
| `item_code` | `VARCHAR(32)` | NOT NULL, CHECK | ①`FORMAL_DESIGNATION` ②`WRITTEN_DOC`（载明目标策略/被套期项目/套期工具/有效性评估方法/再平衡机制）③`EFFECTIVENESS_RECORDS` ④`REBALANCE_RECORDS` ⑤`DUAL_PNL_PRESENTED` |
| `item_name` | `VARCHAR(256)` | NOT NULL | 条款名称 |
| `result` | `VARCHAR(16)` | NOT NULL, CHECK | `PASS` / `FAIL` / `NOT_APPLICABLE` |
| `evidence_type` | `VARCHAR(32)` | NULL | **证据类型**（表名 / 文档编号） |
| `evidence_id` | `BIGINT` | NULL | **证据对象 ID** |
| `evidence_desc` | `VARCHAR(500)` | NULL | **证据位置说明**（`FR-ACCT-14` 验收 1"逐条给出通过与证据位置"） |
| `fail_reason` | `VARCHAR(500)` | NULL | 不通过原因 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_cas24_item` | `selfcheck_id, item_seq` | 唯一 | 条目序号唯一 |
| `idx_cas24_item_code_result` | `item_code, result` | 普通 | **按条款统计通过率；筛某条款不通过（如"缺少再平衡记录"，`FR-ACCT-14` 验收 2）** |

---

### 2.11 模块 `FUND`（资金管理）—— 9 张

> ⚠️ **本模块承载"完整的资金记账"**：期初 / 出入金 / 调拨 / 盈亏结转 / 手续费 / 保证金变动 / 期末，
> **每笔均有凭证编号与操作留痕**（`R-FUND-2`）。
> **与 `SETTLE` 共享同一台账、单一口径**（`R-FUND-8`）：**不得两处各维护一套余额**。
> **子账户间划拨为系统内记账调整，真实账户资金不变动，加总金额不变**（`R-FUND-4`）。

#### TB-FUND-1 `t_fund_ledger` 资金台账

**用途**：各子账户资金状况的台账（期初 / 发生额 / 期末）（`FR-FUND-1`、`FR-FUND-3`）。
**必须可直接求和以支持加总对账**（`R-FUND-1`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **子账户（独立台账；可加总的关键维度）** |
| `ledger_date` | `DATE` | NOT NULL | 记账日 |
| `begin_balance` | `DECIMAL(20,4)` | NOT NULL | **期初余额（自上日期末结转，不得手工改写）** |
| `amount_in` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 当日入金合计（`FUND.md` 流水类型汇总） |
| `amount_out` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 当日出金合计 |
| `transfer_in` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 当日**划拨入**合计 |
| `transfer_out` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 当日**划拨出**合计 |
| `pnl_carryover` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 当日**盈亏结转**合计 |
| `commission` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 当日手续费合计 |
| `margin_change` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 当日**保证金占用变动**合计 |
| `difference_adjust` | `DECIMAL(20,4)` | NOT NULL DEFAULT `0` | 当日**差异调整**合计（**须双人复核**） |
| `end_balance` | `DECIMAL(20,4)` | NOT NULL | **期末余额**；`begin_balance + 发生额合计 = end_balance`（`R-FUND-2`） |
| `available_fund` | `DECIMAL(20,4)` | NULL | 可用资金（新开仓可用） |
| `margin_occupied` | `DECIMAL(20,4)` | NULL | 占用保证金 |
| `equity` | `DECIMAL(20,4)` | NULL | 权益 |
| `risk_ratio` | `DECIMAL(9,6)` | NULL | 风险度 |
| `is_closed` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 当日台账是否已结账（结账后 `end_balance` 冻结） |
| `row_version` | `INT` | NOT NULL DEFAULT `0` | 乐观锁（流水写入更新台账） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_fund_ledger_date_sub` | `ledger_date, sub_account_id` | 唯一 | **每子账户每记账日一份台账** |
| `idx_fund_ledger_date` | `ledger_date` | 普通 | **`Σ 子账户期末余额 = 真实账户` 的加总聚合（`FR-FUND-17`、`FR-SETTLE-3`）** |
| `idx_fund_ledger_sub_date` | `sub_account_id, ledger_date` | 普通 | **按子账户查台账（`FR-FUND-3`、期初结转）** |

**备注**：
- **本表是 `FUND` 与 `SETTLE` 的共享数据源**（`R-SETTLE-8`）；
  `t_settle_fund.fund_ledger_id` 反向引用本表，保证"同一数据源、同一期末余额口径"。
- **期初不得手工改写**（`FUND.md` §2）：仅通过上日期末结转 + 差异调整（双人复核）实现。

---

#### TB-FUND-2 `t_fund_flow` 资金流水

**用途**：资金台账中的每一笔发生额（`FR-FUND-2`）。
**每笔均有凭证编号与操作留痕；只增不改不删**（`R-FUND-2`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **子账户（每条流水自带，可直接求和）** |
| `voucher_no` | `VARCHAR(32)` | **NOT NULL, UNIQUE** | **资金凭证编号，全系统唯一，一经分配不得修改**（被审计追溯引用） |
| `ledger_date` | `DATE` | NOT NULL | 记账日 |
| `flow_type` | `VARCHAR(32)` | NOT NULL, CHECK | `DEPOSIT`（入金）/ `WITHDRAW`（出金）/ `TRANSFER_OUT`（子账户划拨-出）/ `TRANSFER_IN`（子账户划拨-入）/ `PNL_CARRYOVER`（盈亏结转）/ `COMMISSION`（手续费）/ `MARGIN_CHANGE`（保证金占用变动）/ `DIFFERENCE_ADJUST`（差异调整） |
| `amount` | `DECIMAL(20,4)` | NOT NULL | 金额（正数；方向由 `flow_type` 决定） |
| `direction` | `VARCHAR(8)` | NOT NULL, CHECK | `IN` / `OUT`（该流水对子账户余额的影响方向） |
| `balance_after` | `DECIMAL(20,4)` | NOT NULL | **该笔之后的子账户余额**（支持"期初 + 发生额 = 期末"逐笔验证） |
| `transfer_apply_id` | `BIGINT` | NULL, FK → `t_transfer_apply(id)` | **子账户划拨单号（划拨的两条流水共用同一单号）** |
| `deposit_withdraw_id` | `BIGINT` | NULL, FK → `t_deposit_withdraw_apply(id)` | 关联的出入金申请 |
| `settle_cycle_id` | `BIGINT` | NULL, FK → `t_settle_cycle(id)` | **关联结算单（盈亏结转与手续费来源）** |
| `trade_fill_id` | `BIGINT` | NULL, FK → `t_trade_fill(id)` | 关联成交（手续费） |
| `counterpart_sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | **对方子账户（划拨流水）** |
| `operator_id` | `BIGINT` | NOT NULL | 操作人 |
| `flow_time` | `TIMESTAMP` | NOT NULL | 发生时间 |
| `remark` | `VARCHAR(500)` | NULL | 摘要 |
| `double_review_required` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否须双人复核（`DIFFERENCE_ADJUST` 恒为 `'Y'`） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_fund_flow_voucher_no` | `voucher_no` | 唯一 | **凭证编号唯一（`FR-FUND-2`、审计追溯主键）** |
| `idx_fund_flow_sub_date` | `sub_account_id, ledger_date` | 普通 | **按子账户 + 日期查流水（`FR-FUND-3`，对账须按期与编号升序）** |
| `idx_fund_flow_type_date` | `flow_type, ledger_date` | 普通 | **按类型汇总（台账各列的发生额合计、导出对账 `FR-FUND-4`）** |
| `idx_fund_flow_transfer` | `transfer_apply_id, flow_type` | 普通 | **按划拨单号取成对流水（一出、一入，金额相等，`FR-FUND-16` 验收 1）** |
| `idx_fund_flow_settle` | `settle_cycle_id` | 普通 | 从结算单查资金影响（追溯链） |
| `idx_fund_flow_date` | `ledger_date` | 普通 | **加总对账：按日求 Σ 各子账户发生额（`FR-FUND-17`）** |

**备注**：**划拨的两条流水通过 `transfer_apply_id` 关联**（同单号、金额相等、方向相反）
——这是 `FR-FUND-11` 验收 1「生成两条关联的资金流水」的结构载体。

---

#### TB-FUND-3 `t_deposit_withdraw_apply` 出入金申请

**用途**：向真实期货账户转入/转出资金的申请（`FR-FUND-5`、`FR-FUND-6`）。
**须双人复核；经 CTP 发起；以期货公司回执为准；不得假设同步返回**（`R-FUND-5`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **记账归属子账户（必填）** |
| `apply_no` | `VARCHAR(32)` | NOT NULL | 申请单号（全系统唯一） |
| `idempotency_key` | `VARCHAR(64)` | **NOT NULL, UNIQUE** | **幂等标识（同一标识重复提交不得产生第二笔划转）**，`FR-FUND-5` |
| `direction` | `VARCHAR(8)` | NOT NULL, CHECK | `IN`（入金）/ `OUT`（出金） |
| `amount` | `DECIMAL(20,4)` | NOT NULL | 金额；**必须 > 0** |
| `fund_source_desc` / `purpose_desc` | `VARCHAR(500)` | NULL | 资金来源说明（入金）/ 用途说明（出金） |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `PENDING_SUBMIT`（待提交）/ `PENDING_REVIEW`（待复核）/ `SUBMITTED`（已提交，等待回执）/ `SUCCESS`（已成功）/ `FAILED`（已失败）/ `TIMEOUT_UNKNOWN`（**超时待查**）/ `REJECTED`（已驳回） |
| `applicant_id` | `BIGINT` | NOT NULL | **发起人** |
| `reviewer_id` | `BIGINT` | NULL | **复核人（必须 ≠ `applicant_id`）**，`R-FUND-3` |
| `review_time` | `TIMESTAMP` | NULL | 复核时间 |
| `reject_reason` | `VARCHAR(500)` | NULL | 驳回原因（驳回时必填） |
| `submit_time` | `TIMESTAMP` | NULL | 经 CTP 提交时间 |
| `available_before` | `DECIMAL(20,4)` | NULL | 出金前的子账户可用资金（出金前置校验依据） |
| `retry_of_id` | `BIGINT` | NULL, FK → `t_deposit_withdraw_apply(id)` | **重试来源申请（不得因重试产生重复划转，`FR-FUND-9`）** |
| `original_confirmed` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **重试前是否已确认原请求真实结果**（**未确认不得重试**，`FR-FUND-9` 验收 2） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_dw_apply_no` | `apply_no` | 唯一 | 申请单号唯一 |
| `uk_dw_apply_idem` | `idempotency_key` | 唯一 | **幂等（重复提交不产生第二笔划转，`FR-FUND-5` 验收 3）** |
| `idx_dw_apply_sub_status` | `sub_account_id, status` | 普通 | **按子账户 + 状态查申请；筛"超时待查"（查询确认队列）** |
| `idx_dw_apply_applicant` | `applicant_id, apply_no` | 普通 | **停用用户前置校验"是否存在进行中的关键操作"（`FR-AUTH-3` 验收 2）** |
| `idx_dw_apply_time` | `submit_time` | 普通 | 按时间查（对账与审计） |

---

#### TB-FUND-4 `t_transfer_apply` 子账户间划拨申请

**用途**：子账户 A → B 的资金调整申请（`FR-FUND-10`、`FR-FUND-11`）。
**系统内记账调整，真实期货账户资金不变动；划拨后加总须不变**（`R-FUND-4`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **划出子账户（本表的子账户维度取"划出方"）** |
| `from_sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **划出子账户** |
| `to_sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | **划入子账户（必须 ≠ `from_sub_account_id`）** |
| `apply_no` | `VARCHAR(32)` | NOT NULL | 划拨单号（全系统唯一；两条流水共用） |
| `amount` | `DECIMAL(20,4)` | NOT NULL | 金额；**必须 > 0** |
| `reason` | `VARCHAR(500)` | **NOT NULL** | **划拨理由（必填）**，`FR-FUND-10` |
| `expected_effective_date` | `DATE` | NULL | 期望生效日 |
| `status` | `VARCHAR(24)` | NOT NULL, CHECK | `PENDING_SUBMIT`（待提交）/ `PENDING_APPROVAL`（待审批）/ `PENDING_POSTING`（待记账）/ `POSTED`（已记账）/ `ROLLED_BACK`（已回滚）/ `REJECTED`（已驳回） |
| `applicant_id` | `BIGINT` | NOT NULL | **发起人（资金岗）** |
| `approver_id` | `BIGINT` | NULL | **审批人（必须 ≠ `applicant_id`，且不得为交易岗）**，`R-FUND-3` |
| `approve_time` | `TIMESTAMP` | NULL | 审批时间 |
| `reject_reason` | `VARCHAR(500)` | NULL | 驳回原因（驳回时必填） |
| `agg_check_id` | `BIGINT` | NULL, FK → `t_aggregation_check(id)` | **加总校验记录（划拨后须校验加总不变）**，`FR-FUND-12` |
| `agg_check_passed` | `CHAR(1)` | NULL, CHECK | 加总校验是否通过（`'N'` → 状态 `ROLLED_BACK`） |
| `rollback_time` | `TIMESTAMP` | NULL | 回滚时间 |
| `posting_time` | `TIMESTAMP` | NULL | 记账时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_transfer_apply_no` | `apply_no` | 唯一 | 划拨单号唯一（两条流水共用此号，见 `t_fund_flow.transfer_apply_id`） |
| `idx_transfer_apply_status` | `status, apply_no` | 普通 | **按状态查申请；筛"待记账/已回滚"** |
| `idx_transfer_apply_from` | `from_sub_account_id, status` | 普通 | 划出子账户的划拨历史（加总核对） |
| `idx_transfer_apply_to` | `to_sub_account_id, status` | 普通 | 划入子账户的划拨历史 |
| `idx_transfer_apply_time` | `posting_time` | 普通 | 按记账时间查（资金对账与审计） |

**备注**：`ck_transfer_apply_diff_sub`（`from ≠ to`）保证
"划出与划入为同一子账户时被拒绝"（`FR-FUND-10` 验收 4）在**结构上成立**。

---

#### TB-FUND-5 `t_capital_receipt` 资金回执

**用途**：期货公司经 CTP 返回的资金划转结果（`FR-FUND-8`）。
**唯一到账依据；网络超时不代表未到账；不得以本地乐观状态替代回执**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 记账归属子账户 |
| `idempotency_key` | `VARCHAR(64)` | NOT NULL | **幂等标识（重复回执须幂等处理）** |
| `apply_id` | `BIGINT` | NOT NULL, FK → `t_deposit_withdraw_apply(id)` | 关联申请 |
| `result` | `VARCHAR(16)` | NOT NULL, CHECK | `SUCCESS` / `FAIL` / `PROCESSING` / `UNKNOWN`（超时待查） |
| `amount` | `DECIMAL(20,4)` | NOT NULL | 回执金额 |
| `ctp_error_id` | `INT` | NULL | CTP 错误号 |
| `ctp_error_msg` | `VARCHAR(500)` | NULL | **失败原因（须记录，且失败不得记账）** |
| `receipt_ts_ms` | `BIGINT` | NOT NULL | 回执毫秒时间戳（C++ 侧） |
| `receipt_time` | `TIMESTAMP` | NOT NULL | 落库时间 |
| `booked` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否已据此记账**（`result='SUCCESS'` 且 `booked='N'` → 待记账；`result='FAIL'` 时 `booked` 恒为 `'N'`） |
| `query_confirmed` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否为**主动查询确认**得到的结果（超时待查的最终定论） |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_capital_receipt_idem` | `idempotency_key, result, receipt_ts_ms` | 唯一 | **回执幂等（`FR-FUND-8` 验收 2：不产生重复流水）** |
| `idx_capital_receipt_apply` | `apply_id, receipt_time` | 普通 | **取某申请的全部回执（超时待查的查询与定论）** |
| `idx_capital_receipt_booked` | `booked, result` | 普通 | **巡检"回执成功但未记账"（防漏记账）** |
| `idx_capital_receipt_sub_time` | `sub_account_id, receipt_time` | 普通 | 按子账户查回执（对账） |

---

#### TB-FUND-6 `t_fund_review_record` 资金复核记录

**用途**：出入金与划拨的**双人复核**记录（`FR-FUND-7`、`FR-FUND-11`、`R-FUND-3`）。
**发起人与复核人不得为同一人（系统级强制，不得仅靠制度）**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NOT NULL, FK → `t_sub_account(id)` | 子账户 |
| `biz_type` | `VARCHAR(24)` | NOT NULL, CHECK | `DEPOSIT_WITHDRAW` / `TRANSFER` / `DIFFERENCE_ADJUST` / `LEDGER_INIT` |
| `biz_id` | `BIGINT` | NOT NULL | 业务对象 ID |
| `applicant_id` | `BIGINT` | NOT NULL | **发起人** |
| `reviewer_id` | `BIGINT` | NULL | **复核人（必须 ≠ `applicant_id`）** |
| `review_result` | `VARCHAR(16)` | NULL, CHECK | `APPROVED` / `REJECTED` |
| `review_comment` | `VARCHAR(1000)` | NULL | 复核意见 |
| `reject_reason` | `VARCHAR(500)` | NULL | 驳回原因（驳回时必填） |
| `self_review_rejected` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | **是否因"发起人=复核人"被系统拒绝**（越权尝试，须留痕，`FR-FUND-7` 验收 2） |
| `review_time` | `TIMESTAMP` | NOT NULL | 复核时间 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_fund_review_biz` | `biz_type, biz_id` | 普通 | **取某业务对象的复核记录（证明双人复核，`FR-FUND-16` 验收 2）** |
| `idx_fund_review_reviewer` | `reviewer_id, review_time` | 普通 | 按复核人查历史 |
| `idx_fund_review_self_rejected` | `self_review_rejected` | 普通 | **筛"发起人=复核人"的越权尝试（审计重点）** |

---

#### TB-FUND-7 `t_fund_quota` 资金额度

**用途**：按**子账户 / 账户**两级设定的资金上限（`FR-FUND-13`）。**不得跨子账户挪用**。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 子账户（`quota_scope='ACCOUNT'` 时为 `NULL`） |
| `quota_scope` | `VARCHAR(16)` | NOT NULL, CHECK | `SUB_ACCOUNT`（子账户级）/ `ACCOUNT`（账户级/全账户） |
| `quota_value` | `DECIMAL(20,4)` | NOT NULL | 额度值；**必须 ≥ 0** |
| `effective_from` | `DATE` | NOT NULL | 生效起始日 |
| `effective_to` | `DATE` | NULL | 生效截止日 |
| `operator_id` | `BIGINT` | NOT NULL | 设置人 |
| `change_reason` | `VARCHAR(500)` | NULL | 变更原因 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_fund_quota_scope` | `quota_scope, sub_account_id, effective_from` | 唯一 | 同范围同起点唯一 |
| `idx_fund_quota_sub` | `sub_account_id, effective_from` | 普通 | **取某子账户生效资金额度（额度告警判定）** |
| `idx_fund_quota_scope` | `quota_scope, effective_from` | 普通 | 取账户级额度（**账户级额度不得超过真实账户可用资金上限**，`FR-FUND-13` 验收 2） |

---

#### TB-FUND-8 `t_fund_quota_alert` 资金额度告警

**用途**：资金触及上限时的告警（`FR-FUND-14`）。**告警不阻断减仓与出金**（`R-FUND-7`）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 子账户（账户级告警为 `NULL`） |
| `quota_scope` | `VARCHAR(16)` | NOT NULL, CHECK | `SUB_ACCOUNT` / `ACCOUNT` |
| `quota_value` | `DECIMAL(20,4)` | NOT NULL | 额度上限 |
| `occupied_value` | `DECIMAL(20,4)` | NOT NULL | 当前占用 |
| `alert_level` | `VARCHAR(16)` | NOT NULL, CHECK | `INFO` / `WARNING` / `CRITICAL`（按 `CFG-12`） |
| `alert_time` | `TIMESTAMP` | NOT NULL | 告警时间 |
| `blocked_operation` | `VARCHAR(24)` | NOT NULL DEFAULT `'NONE'`, CHECK | **恒为 `'NONE'`**（**告警不阻断**，`FR-FUND-14` 验收 3） |
| `disposed` | `CHAR(1)` | NOT NULL DEFAULT `'N'`, CHECK | 是否已处置 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `idx_fund_quota_alert_sub_time` | `sub_account_id, alert_time` | 普通 | **按子账户查额度告警（`FR-FUND-14`）** |
| `idx_fund_quota_alert_level` | `alert_level, alert_time` | 普通 | 按级别筛选 |

**备注**：`blocked_operation` 恒为 `'NONE'` 的 `CHECK` 落地
`R-FUND-7`「**超限告警但不阻断减仓与出金**」——用结构禁止"把资金额度告警做成阻断"。

---

#### TB-FUND-9 `t_fund_reconcile` 资金对账

**用途**：每日资金对账（`FR-FUND-17`）。
**`期初 + 发生额 = 期末`** 与 **`Σ 子账户 = 真实账户`**（与 `FR-SETTLE-8` **同口径、同结果**）。

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `🔴子` `sub_account_id` | `INT` | NULL, FK → `t_sub_account(id)` | 子账户（子账户级对账非空；账户级为 `NULL`） |
| `trade_date` | `DATE` | NOT NULL | 交易日 |
| `reconcile_level` | `VARCHAR(16)` | NOT NULL, CHECK | `SUB_ACCOUNT` / `ACCOUNT` |
| `begin_balance` | `DECIMAL(20,4)` | NOT NULL | 期初余额 |
| `occurrence_total` | `DECIMAL(20,4)` | NOT NULL | 当日发生额合计 |
| `end_balance` | `DECIMAL(20,4)` | NOT NULL | 期末余额 |
| `balance_equation_ok` | `CHAR(1)` | NOT NULL, CHECK | **`期初 + 发生额 = 期末` 是否成立**（`FR-FUND-17` 验收 1） |
| `agg_compare_value` | `DECIMAL(20,4)` | NULL | 加总比对值（账户级对账时 = Σ 子账户；子账户级为 `NULL`） |
| `real_account_value` | `DECIMAL(20,4)` | NULL | 真实账户对应值（账户级对账时非空） |
| `agg_consistent` | `CHAR(1)` | NULL, CHECK | 加总是否一致 |
| `result` | `VARCHAR(24)` | NOT NULL, CHECK | `CONSISTENT` / `INCONSISTENT` / `HAS_OPEN_DIFFERENCE`（**存在未闭环差异时不得标记完成**） |
| `agg_check_id` | `BIGINT` | NULL, FK → `t_aggregation_check(id)` | **与 `SETTLE` 的加总对账记录关联（保证"两处结论不得不同"）**，`R-FUND-8` |
| `difference_list` | `CLOB` | NULL | 差异清单（不一致时非空） |
| `reconcile_time` | `TIMESTAMP` | NOT NULL | 对账时间 |
| `operator_id` | `BIGINT` | NOT NULL | 对账人 |

**索引**：

| 索引名 | 字段 | 类型 | 用途 |
|--------|------|------|------|
| `uk_fund_reconcile_key` | `trade_date, reconcile_level, sub_account_id` | 唯一 | 幂等 + 定位 |
| `idx_fund_reconcile_date` | `trade_date, reconcile_level` | 普通 | **按交易日查资金对账结论（`FR-FUND-17`）** |
| `idx_fund_reconcile_result` | `result, trade_date` | 普通 | 筛"不一致/存在未闭环差异"（阻断与差异处理） |
| `idx_fund_reconcile_agg_check` | `agg_check_id` | 普通 | **与 `SETTLE` 结论勾稽（`R-FUND-8`：不得两处结论不同）** |

---

## 3. 字段级说明

> 仅对**含义不直观**或**有业务规则**的字段做展开。命名自明的字段可略过。
> 本节按 ① 枚举字段 ② 金额与数量精度 ③ 状态字段 ④ 时间字段 ⑤ 编号生成规则
> 五类展开——这五类是本模型最易被实现者"各写各的"的地方。

### 3.1 枚举字段（**须使用本表取值，不得自行扩展**）

> 说明：以下为**全部枚举字段的取值域与业务含义**。数据库 `CHECK` 约束与本节**必须一致**。
> 新增枚举值须走变更流程（`DC-<序号>`）并同步本节与 `CHECK`。

| 字段（示例表） | 取值 | 业务含义 |
|---------------|------|---------|
| `t_product.category` | `COMMODITY` | **商品类**：年度保值规模 ≤ 实货经营规模 **90%** |
| | `TRADE` | **贸易类**：年度保值规模 ≤ 实货经营规模 **80%** |
| `t_product_contract.instrument_type` | `FUTURES` / `OPTION` | 期货 / 期权；**两者共用同一追溯链与同一套保额度** |
| `t_product_spot_category.mapping_direction` | `PURCHASE` / `SALES` / `BOTH` | 采购侧 / 销售侧 / 通用映射 |
| `t_exposure.direction` | `BUY` | **买入敞口**：未来需买入，担心价格上涨 |
| | `SELL` | **卖出敞口**：未来需卖出，担心价格下跌 |
| `t_exposure.calc_mode` | `CONTRACT_UNEXECUTED` | 已签合同未执行量 |
| | `INVENTORY_HELD` | 库存持有量 |
| | `PRODUCTION_PLAN` | 生产计划需求量 |
| | `COMBINED` | 组合口径（须在 `combined_components` 标注构成） |
| `t_exposure.status` | `PENDING_HEDGE` / `PARTIALLY_COVERED` / `COVERED` / `CLOSED` | 待套保 / 部分覆盖 / 已覆盖 / 已关闭 |
| `t_spot_price.source_type` | `MANUAL` / `BATCH_PASTE` / `CSV_IMPORT` | 手工录入 / 批量粘贴 / CSV 导入（**来源影响审计可信度评估**） |
| `t_hedge_plan.hedge_direction` | `BUY_HEDGE` | **买入套保**：对冲**卖出**敞口（担心原料涨价） |
| | `SELL_HEDGE` | **卖出套保**：对冲**买入**敞口（担心成品跌价） |
| `t_hedge_plan.ratio_method` | `SIMPLE` | 简单比率法（敞口明确、单一品种） |
| | `MIN_VARIANCE` | 最小方差法 `h = ρ × σ_S / σ_F`（**样本 ≥ 30 个交易日**） |
| | `CUSTOM_RULE` | 自定义规则法（企业内控比例） |
| `t_hedge_plan.status` | `DRAFT` / `PENDING_APPROVAL` / `APPROVED` / `FROZEN` / `COMPLETED` / `TERMINATED` | 草稿 / 待审批 / **已批准（生效，占额度）** / **已冻结（保留占额）** / 已完成 / 已终止（释放未用额度） |
| `t_trade_plan.status` | `PENDING` / `EXECUTING` / `COMPLETED` / `CANCELLED` | 待执行 / 执行中 / 已完成 / 已撤销 |
| `t_trade_order.status` | `PENDING_CONFIRM` | **待确认**（已过事前风控，等待人工确认） |
| | `REJECTED_PRECHECK` | **已拒绝（事前风控）**——**未发往 CTP** |
| | `SUBMITTED` | 已人工确认，正在发出 |
| | `SENT_CTP` | **已发送 CTP**，等待委托回报 |
| | `PARTIAL_FILLED` / `ALL_FILLED` | 部分成交 / 全部成交 |
| | `CANCELLED` | 未成交部分已撤销 |
| | `REJECTED_CTP` | 被 CTP 拒绝（保留柜台错误原文） |
| | `EXPIRED` | 超时未确认，**已失效** |
| `t_trade_order.offset_flag` | `OPEN` / `CLOSE` / `CLOSE_TODAY` | 开仓 / 平仓 / 平今（以 CTP 语义为准） |
| `t_trade_order.price_type` | `LIMIT` / `MARKET` | 限价 / 市价（**市价单标注"不适用偏离校验"**） |
| `t_trade_reason.related_type`（**关联类型，v1.1 结构化必填；v1.2 扩至 8 值**） | `NEW_EXPOSURE` | 敞口新增 |
| | `EXPOSURE_DECREASE` | 敞口减少 |
| | `PLAN_EXECUTION` | 计划执行 |
| | `ROLLOVER` | 展期置换 |
| | `RISK_DISPOSAL` | 风险处置 |
| | `ERROR_CORRECTION` | 错误更正 |
| | **`FORCE_CLOSE`** | **强制平仓**（CTP 侧直接产生，**非本系统下单路径**；v1.2 `DC-024` 增补） |
| | **`DELIVERY`** | **到期交割**（CTP 侧直接产生，**非本系统下单路径**；v1.2 `DC-024` 增补） |
| `t_trade_reason.time_desc`（**时点说明，v1.1 结构化必填**） | `CONTRACT_SIGNED` / `PRICE_TRIGGERED` / `MONTHLY_REBALANCE` / `EXPIRY_ROLLOVER` / `RISK_REQUIRED` | 合同签约 / 价格触发 / 月度调仓 / 到期展期 / 风控要求 |
| `t_product_quota.limit_layer`（**三层限额，v1.1**；**2 值域**） | `SUB_PRODUCT` | **层①：子账户 + 品种（主维度）** |
| | `ACCOUNT_PRODUCT` | **层③：账户 + 品种（总账户，防串用）** |
| `t_sub_instrument_quota.limit_layer`（**层②表，v1.1**；**单值域**） | `SUB_INSTRUMENT` | **层②：子账户 + 合约**（可选层；本表本身即层②载体，除该值外不接受其它值） |
| `t_pre_trade_reject.limit_layer`（**v1.2 补列，`PI-01`/`DC-027`；5 值域**） | `SUB_PRODUCT` / `SUB_INSTRUMENT` / `ACCOUNT_PRODUCT` | 拒绝命中的**限额层级**（层① / 层② / 层③）；**取值域与 `ARCHITECTURE.md` §4.2.4 `EVT_PRE_TRADE_REJECT.limit_layer` 完全同域** |
| | `AUTH_QUOTA` / `NONE` | **授权额度超限** / **非限额类拒绝**（价格偏离、规模红线等）。**非限额类拒绝一律填 `NONE`**（与 `ARCHITECTURE.md` §4.2.4 一致）；该列可空，**`NULL` 仅表示该拒绝记录未命中任何规则层级**（历史 / 导入数据），**不再用于表达"非限额类拒绝"**（`PI-19`③ 二义已消除） |
| `t_exposure.components_enabled`（**敞口分量，v1.1**） | `CONTRACT_UNEXECUTED` / `INVENTORY` / `PRODUCTION_PLAN` | 分量①已签合同未执行量 / 分量②库存持有量 / 分量③生产计划需求量（`exposure.components` 默认**全开**） |
| `t_check_task.check_type` / `.status`（**核对任务，v1.1**） | `MONTHLY` / `QUARTERLY` / `ANNUAL`；`PENDING_SIGN` / `PARTIAL_SIGNED` / `COMPLETED` / `OVERDUE` | 月度三方核对 / 季度报告 / 年度审计覆盖；待签署 / 部分签署 / 已完成 / 已逾期 |
| `t_check_signature.sign_role`（**签署角色，v1.1**） | `RISK` / `TRADE` / `FINANCE` / `MANAGE` / `AUDITOR` | 风控岗 / 交易岗 / 财务岗 / **管理层（签收）** / 审计岗 |
| `t_rebalance_record.trigger_type` / `.trigger_side`（**再平衡触发，v1.1**） | `EXPOSURE_DELTA` / `RATIO_DEVIATION` / `PLAN_REVISED` / `EXPOSURE_GONE`；`EXPOSURE_SIDE` / `INSTRUMENT_SIDE` | 敞口变动 >20% / 比率偏离 >20% / 计划数量修订终止 / 敞口消失；**敞口侧与工具侧各设一项** |
| `t_sub_account.category` / `t_sub_product_whitelist.category_override`（**分类，v1.1**） | `COMMODITY` / `TRADE` | 商品类（**90%**）/ 贸易类（**80%**）；默认挂子账户，允许品种级覆盖 |
| `t_position.direction` | `LONG` / `SHORT` | 多头 / 空头 |
| `t_position.position_type` | `TODAY` / `YESTERDAY` | 今仓 / 昨仓（以 CTP 语义为准） |
| `t_fund_flow.flow_type` | `DEPOSIT` / `WITHDRAW` | 入金 / 出金（经 CTP，由期货公司完成银期划转） |
| | `TRANSFER_OUT` / `TRANSFER_IN` | **子账户划拨（出/入）**——系统内记账调整，真实账户不变动 |
| | `PNL_CARRYOVER` / `COMMISSION` / `MARGIN_CHANGE` | 盈亏结转 / 手续费 / 保证金占用变动 |
| | `DIFFERENCE_ADJUST` | 差异调整（**须双人复核**） |
| `t_deposit_withdraw_apply.status` | `TIMEOUT_UNKNOWN` | **超时待查**：未收到回执；**不得假设失败或成功**，须主动查询确认 |
| `t_reconcile_difference.difference_kind` | `SYS_ONLY` / `CTP_ONLY` / `VALUE_MISMATCH` | 系统有而期货公司无 / 期货公司有而系统无 / 要素不一致 |
| `t_aggregation_check.result` | `CONSISTENT` / `INCONSISTENT_BLOCKED` | 一致 / **不一致-已阻断** |
| `t_risk_alert.alert_level` | `INFO` / `WARNING` / `CRITICAL` | 提示 / 警告 / 严重 |
| `t_risk_disposal.disposal_type` | `ALERT_ONLY` / `SUSPEND_OPEN` / `FORCE_CLOSE_HINT` / `RELEASE_SUSPEND` | 仅告警 / **限制新开仓** / **强制平仓提示（只提示不自动下单）** / 解除限制 |
| `t_risk_violation.violation_type` | `OVER_PRODUCT` / `OVER_SCALE` / `OVER_TERM` / `OVER_AUTH` / `FREQUENT_TRADE` / `DOC_MISSING` / `REASON_MISSING` / **`RELATED_MISSING`** | 超品种 / 超规模 / 超期限 / 超授权 / 频繁开平仓 / 文档缺失 / **交易理由缺失** / **关联对象缺失**（**后两者为并列独立的审计筛查维度**，`DC-014`、`Q-13`；`PI-08` 已补） |
| `t_voucher.status` | `DRAFT` / `PENDING_REVIEW` / `POSTED` / `REVERSED` | 草稿 / 待审核 / **已审核（正式，不可修改）** / 已冲销 |
| `t_voucher.voucher_type` | `CLOSE_PNL` / `COMMISSION` / `MARGIN_CHANGE` / `FLOAT_PNL` / `PREMIUM` / **`EXERCISE`** / **`EXPIRED_WORTHLESS`** / `HEDGE_EFFECT` / `REVERSAL` / `OTHER` | 平仓盈亏结转 / 手续费 / 保证金变动 / 浮动盈亏 / **期权权利金** / **期权行权（v1.2）** / **期权到期作废（v1.2）** / 套保效果归集 / 冲销 / 其它 |
| `t_effectiveness_assess.conclusion` | `EFFECTIVE` / `INEFFECTIVE` / `NEED_REBALANCE` | 有效 / 无效 / 需再平衡（**无效时须标注"需终止或再平衡"，不得自动改会计处理**） |
| `t_cas24_selfcheck_item.item_code` | 5 个枚举（见 `TB-ACCT-12`） | CAS 24 自检五项 |
| `t_audit_log.chain_integrity` | `COMPLETE` / `HAS_BREAK` / `IN_FLIGHT` / `UNKNOWN` | 链条完整 / **存在断点** / **在途（不计为断点）** / 无法判定（**在途判定条件与时窗见 §4.4**） |
| `t_audit_report.chain_integrity` / `t_evidence_export.chain_integrity` | `COMPLETE` / `HAS_BREAK` / `IN_FLIGHT` / `UNKNOWN` | 链条完整 / **存在断点** / **在途（不计为断点）** / **无法判定（数据缺失）**——**v1.3.1 与 `t_audit_log` 统一为 4 值**（`DC-038`）；使 `#19`④「数据缺失 → `UNKNOWN`」在**报表/导出侧同样可表达**，不得误标为完整（`R-AUDIT-2`） |
| **`dispatch_status`（统一定义，v1.2 补，`S-4`）** | `PENDING` / `SUCCESS` / `FAIL` / `UNCONFIRMED` / `NOT_REQUIRED` | **下发 C++ 状态**：待下发 / 下发成功 / 下发失败 / **已下发未收到回执（超时）** / 无需下发。**统一适用于**：`t_sub_product_whitelist`、`t_sub_instrument_quota`、`t_product_quota`、`t_annual_scale`、`t_risk_threshold`、`t_param_dispatch`、`t_authorization`、`t_risk_disposal`、`t_plan_state_log`。**巡检判据**：`dispatch_status ∈ {PENDING, FAIL, UNCONFIRMED}` → 视为**尚未生效**，须巡警告警（`R-CONFIG-3`）；`FAIL`/`UNCONFIRMED` 的变更**不得视为已生效** |
| `t_spot_change_log.change_type` 新增值（v1.2，`#17`） | `COMPONENT_RECALC` | **敞口分量与合计不一致时的"重算"留痕**（展示侧重算 + 告警；**改落库值须另生成新快照、旧快照保留**，见 §4.4 敞口三分量行） |

> ⚠️ **枚举存储纪律**：① **一律 `VARCHAR` + `CHECK`**，**不用整数掩码**（可读性 + 换库友好）；
> ② **取值域变更 = 表结构变更**（须改 `CHECK`）→ 故**极易变的选项（如交易理由的细分补充项）走 `t_dict_item`**，
> 表中存储**结构化枚举列**（如 `t_trade_reason.related_type`、`t_exposure.calc_mode`），字典仅提供细分项/显示名与启停。
> **（`t_trade_reason.reason_code` 已在 v1.1 `DC-008` 更名为 `related_type`；本表内不存在 `reason_code` 列，
> 引用该列的索引 `idx_trade_reason_code` 已在 v1.2 移除/改建，见 `DC-017`。）**
> ③ 枚举值**不得设为 `NULL`**（除显式标注可空者）。
> ④ **作用域哨兵值**（v1.2，`DC-020`）：账户级/全账户口径的**作用域列**（`t_product_quota.sub_account_id`、
> `t_annual_scale.sub_account_id`、`t_risk_threshold.sub_account_id`、`t_check_task.sub_account_id`、
> `t_check_signature.sub_account_id`）**不使用 `NULL`**，一律填**哨兵值 `0`（= 账户级/全账户）**，
> 由**非空的** `limit_layer` / `scope_type` 配合 `CHECK` 保证一致性；
> **唯一约束不再依赖 `NULL` 在唯一索引中的行为**（详见 §4.3、§6.1 `D16`）。

### 3.2 金额、价格、数量精度

| 类型 | 精度 | 适用 | 规则 |
|------|------|------|------|
| 金额 | `DECIMAL(20,4)` | 资金余额、盈亏、手续费、保证金、凭证金额、额度 | 单位 **人民币元**；**不含税**（本系统不处理税务）；**禁止 `FLOAT/DOUBLE`** |
| 价格 | `DECIMAL(20,6)` | 现货价、成交价、限价、结算价、持仓均价、合约乘数 | 单位随 `*_unit` 或合约定义；**标准化建议：以元/吨计**（由 `t_product_unit` 保证口径） |
| 数量（折算后） | `DECIMAL(20,4)` | 敞口数量、持仓量、额度、成交金额相关数量 | 单位见 `unit` 列；跨品种比较前须经 `t_product_unit.convert_factor` 换算 |
| 数量（手数） | `INT` | 报单手数、成交量（手） | 整数；**必须 > 0** |
| 比例 | `DECIMAL(9,6)` | 套保比率、使用率、风险度、变化幅度 | **以小数存储**（90% → `0.900000`）；**禁止存 `90`** |
| 换算系数 | `DECIMAL(20,6)` | `convert_factor` | **必须 > 0** |

**精度相关的强约束**（均已在各表 `CHECK` 中声明）：

| 约束 | 表 | 业务规则来源 |
|------|----|------------|
| `spot_price > 0` | `t_spot_price` | `FR-SPOT-10` 验收 3（价格为 0 或负数被拒） |
| `total_qty > 0` | `t_purchase_contract` / `t_sales_contract` | 总量必须为正 |
| `quota_limit >= 0` | `t_product_quota` | `FR-PRODUCT-11` 验收 4（负数额度被拒） |
| `quota_limit > 0` | `t_authorization`、`t_fund_quota.quota_value >= 0` | 授权额度必须为正；资金额度非负 |
| `convert_factor > 0` | `t_product_unit` | `FR-PRODUCT-13` 验收 2（系数 0 或负被拒） |
| `hedge_ratio > 0 AND hedge_ratio <= 1` | `t_hedge_plan` | 套保比率 ∈ (0, 100%] |
| `completion_ratio BETWEEN 0 AND 1` | `t_wip_record` | 完工比例 |
| `usage_ratio >= 0` | `t_limit_usage_stat` | 使用率 |
| `position_qty >= 0` | `t_position` / `t_settle_position` | 持仓量非负 |
| `available_qty <= position_qty` | `t_position` | 可用持仓不得超过总持仓 |
| `amount > 0` | `t_voucher_line`、`t_fund_flow` | 分录与流水金额为正（方向由 `direction` 表达） |
| `SUM(voucher_line.amount)` 借贷平衡 | `t_voucher_line` | **不由 DB 保证**（跨行聚合无法用 `CHECK` 表达）→ 由服务层校验 + `t_voucher.total_amount` 冗余 |

> ⚠️ **借贷平衡的实现说明**：`t_voucher.total_amount` 是`借方合计 = 贷方合计`的冗余记录；
> **数据库无法用 `CHECK` 表达跨行聚合约束**（除非用触发器——而**触发器被永久禁止**，`PROJECT.md` §6）。
> 故由**服务层在同一事务内**校验：写入分录行后聚合校验，不平衡则回滚。
> **这是本模型中"约束下沉到数据库"的唯一例外**，已在 §4.5 声明，并须由测试经理在 M1 会签时确认可测性。

### 3.3 状态字段（状态流转）

> **统一纪律**：① 状态**只增流转、不删除**；② 流转**必须留痕**（`t_plan_state_log` / `t_spot_change_log` /
> `t_permission_change_log` / `t_operation_log`）；③ **不得提供直接改状态的入口**（只能通过业务动作触发流转）。
>
> **集中说明四个跨模块的状态约定**：

| 约定 | 规则 |
|------|------|
| **终态** | `COMPLETED` / `TERMINATED`（计划）、`ALL_FILLED` / `CANCELLED` / `REJECTED_*` / `EXPIRED`（指令）、`CLOSED` / `VOID`（单据）、`REVERSED`（凭证）——终态**不可再流转**，只能新建 |
| **"反向操作"的位置** | `VOID`（作废）、`TERMINATED`（终止）、`CANCELLED`（撤销）、`REVERSED`（冲销）、`ROLLED_BACK`（回滚）、`REOPEN`（撤销关账）、`DISABLED`（停用）——**每个正向操作都有对应反向操作**（需求中"反向操作，不得遗漏"的要求） |
| **额度生命周期** | 计划 `/草稿` 与 `/待审批` 不占额 → `/已批准` 占额 → `/已冻结` 保留占额 → `/已终止`、`/驳回至草稿` 释放未用部分（`R-PLAN-5`） |
| **加总不一致的阻断** | `t_aggregation_check.result='INCONSISTENT_BLOCKED'` 且 `closed_time IS NULL` → **阻断差异所属子账户的新开仓、资金划拨、结算关账；不阻断平仓与风险处置**（`CFG-18`） |

### 3.4 时间字段

| 字段类别 | 类型 | 时区 | 语义 | 示例 |
|---------|------|------|------|------|
| 业务日期 | `DATE` | `Asia/Shanghai` | 交易日、记账日、会计期间归属日、基准日 | `trade_date`、`ledger_date`、`as_of_date` |
| 事件发生时间 | `TIMESTAMP` | `Asia/Shanghai` | 业务动作发生时间（**应用层写入，不使用 DB 默认值**） | `action_time`、`reject_time`、`reported_time` |
| 审计时间 | `TIMESTAMP` | `Asia/Shanghai` | 创建/更新（**DEFAULT `CURRENT_TIMESTAMP`**） | `created_time`、`updated_time` |
| 外部毫秒时间戳 | `BIGINT` | UTC epoch（毫秒） | CTP 回报时间、事件流时间（**与协议一致**） | `report_ts_ms`、`trade_ts_ms`、`flow_ts_ms` |
| 序号 | `BIGINT` | — | **事件流序号 / 链序号**（非时间，但用于顺序校验） | `event_seq`、`seq_in_chain` |

**时间纪律**：
- ① **业务日期与事件时间分离**：`trade_date`（DATE）用于结算与会计归集；`trade_ts_ms`（BIGINT）用于事件排序与延迟统计。**不得用 `TIMESTAMP` 替代业务日期**（时区与"交易日"概念不同）。
- ② **可注入时钟**：应用层时间**必须**经可注入的 `Clock` 获取（`NFR-TEST-4`），**禁止**在业务代码中直接 `System.currentTimeMillis()` / `time()`。数据库仅对 `created_time` / `updated_time` 使用 `CURRENT_TIMESTAMP`（审计字段，不参与业务判定）。
- ③ **接口时间格式**：对外（REST）为 ISO-8601 `yyyy-MM-dd'T'HH:mm:ss`；纯日期为 `yyyy-MM-dd`（见 `ARCHITECTURE.md` §4.1.1）。

### 3.5 编号生成规则（**由应用层生成，不依赖数据库序列**）

> **为什么不用数据库序列**：`PROJECT.md` §5.2 要求**数据库可更换**。
> 若业务编号依赖 DM 的 `SEQUENCE`，换库时全量编号逻辑受牵连。
> 故**业务编号一律由应用层生成**，规则如下（保证全系统唯一、不可修改、可读、可排序）：

| 编号 | 规则 | 示例 | 生成位置 |
|------|------|------|---------|
| 子账户编码 | 人工指定 | `SA-A`、`SA-B` | `CONFIG` |
| 采购/销售合同编号 | **人工录入**（同一子账户内唯一） | `CG2026001`（由业务人员按自身规则命名） | `SPOT` |
| 生产计划编号 | **人工录入** | `SC2026001` | `SPOT` |
| 实货敞口 | 系统生成：`EXP<基准日><子账户ID><品种ID><方向序号>` | `EXP20260920-01-0007-B-01` | `SPOT` |
| 套保计划编号 | 系统生成：`HP<yyyyMMdd><seq(4)>` | `HP202609200001` | `PLAN` |
| 套期关系文件编号 | 系统生成：`HD<计划编号>-V<版本>` | `HDHP202609200001-V2` | `PLAN` |
| 交易计划编号 | 系统生成：`TP<yyyyMMdd><seq(5)>` | `TP2026092000001` | `PLAN` |
| **交易指令引用（幂等键）** | 系统生成：`HG<yyyyMMdd><seq(6)>` | `HG20260920000042` | `TRADE`（**协议字段 `client_order_ref`**） |
| C++ 指令号 | C++ 侧生成（`uint64` 单调递增） | `100001` | C++ |
| 结算单号 | 系统生成：`ST<yyyyMMdd>-<子账户编码>` | `ST20260920-SA-A` | `SETTLE` |
| **资金凭证编号** | 系统生成：`FV<yyyyMMdd><seq(5)>` | `FV2026092000012` | `FUND` |
| **会计凭证编号** | 系统生成：`AV<期间><seq(5)>` | `AV20260900031` | `ACCT` |
| 划拨单号 | 系统生成：`TF<yyyyMMdd><seq(4)>` | `TF202609200003` | `FUND` |
| 筛查 / 报表 / 导出批次号 | 系统生成：`<前缀><yyyyMMdd><seq>` | `VS202609200001` | `AUDIT` |
| **敏感字段** | — | — | **无编号、无落盘**（`NFR-SEC-1`） |

**生成方式**：`<日期段 + 当日序号>` 由应用层在**同一事务内**用"当日最大序号 + 1"生成，
并依赖表上的 `UNIQUE` 约束兜底（并发冲突时重试）。**不使用** `SELECT MAX(...) FOR UPDATE`
（会在热点表上产生锁竞争）；采用 `UNIQUE` 冲突 + 最多 3 次重试的策略。

---

## 4. 索引与约束

> **合格标准**：说清「为了什么查询建的索引」——不写用途的索引是无效索引。
> §2 各表已给出**单表索引清单与用途**；本节给出**全局视角的汇总与跨表约束**。

### 4.1 索引总体统计

| 项 | 数量 | 说明 |
|----|------|------|
| 主键索引 | **111** | 每表一个（`id`；`t_sub_account` 为 `INT`） |
| **唯一索引**（不含主键） | **93**（具名去重） | 含业务编号唯一、幂等键唯一、组合唯一、条件唯一 |
| **普通索引** | **297**（具名去重） | 全部标注"服务什么查询" |
| **索引合计（不含主键）** | **390** | 平均每表 3.5 个（`390 / 111 ≈ 3.51`） |
| 视图 | **1（建议）** | `v_position_summary_by_sub`（持仓汇总，**不落表**，`R-SETTLE-9`） |

> **计数口径（`S-6` 整改，v1.3）**：本表**只统计 §2 各表"索引"清单中类型为『唯一』/『普通』的具名索引行**（按索引名去重）。
> **字段行内联 `UNIQUE`**（如 `t_sub_account.sub_account_code`、`t_config_item.config_key`、`t_trade_fill.trade_id`）
> **均已在本表索引清单中以具名 `uk_*` 索引一一登记**，**不重复计数**。
> ① 机器复核命令（**为避免自指，模式用反斜杠转义**）：`grep -cE "\| 唯一 \|"`（= **93**）、`grep -cE "\| 普通 \|"`（= **297**）；
> ② §4.2「高价值索引」表为**引用性列举**（非定义），其中 `uk_command_flow_idem` / `uk_audit_log_chain_seq` 与 §2 定义**同名重复出现不计入**本计数。

> **索引纪律**：① 每个索引**必须有明确的服务查询**（§2 已逐条标注）；
> ② **禁止**在低基数列（如 `is_deleted` 若存在、`status` 单独）上建单列索引，除非确实有该过滤需求；
> ③ 联合索引**遵循最左前缀**（如 `(sub_account_id, status, submit_time)` 服务"按子账户+状态筛选并按时间排序"）；
> ④ 高写入表（`t_trade_fill`、`t_command_flow`、`t_operation_log`）的索引数量**控制在 6 个以内**（写入放大）。

### 4.2 高价值索引（服务于框架级/高频查询）

| 索引 | 表 | 服务的查询 | 依据 |
|------|----|-----------|------|
| `idx_trade_order_sub_status_time` | `t_trade_order` | 委托列表（子账户 + 状态 + 时间倒序 + 分页） | `FR-TRADE-7`、`NFR-PERF-4` |
| `idx_trade_fill_sub_date` | `t_trade_fill` | 日终结算按子账户取当日成交；交易记录导出 | `FR-SETTLE-4`、`FR-TRADE-18`、`NFR-PERF-7` |
| `idx_settle_position_date_instrument` | `t_settle_position` | **`Σ 子账户 = CTP 合并` 的加总聚合** | `FR-SETTLE-8`、`NFR-PERF-7` |
| `idx_fund_flow_sub_date` | `t_fund_flow` | 资金台账与流水查询（子账户 + 日期升序对账） | `FR-FUND-3`、`NFR-PERF-3` |
| `idx_voucher_sub_period_status` | `t_voucher` | 凭证列表（子账户 + 期间 + 状态）；关账门禁 | `FR-ACCT-8`、`NFR-PERF-3` |
| `idx_spot_price_product_date` | `t_spot_price` | 现货价序列（最小方差法计算输入） | `FR-PLAN-7`、`FR-SPOT-13` |
| `idx_exposure_sub_status` | `t_exposure` | 待套保敞口查询（计划编制入口） | `FR-SPOT-15`、`FR-PLAN-1` |
| `idx_reject_rule_time` | `t_pre_trade_reject` | 拒绝记录按规则编号统计 | `FR-TRADE-17` |
| `uk_command_flow_idem` | `t_command_flow` | 事件流幂等（重放重复投递） | `NFR-REL-5`、`R-TRADE-4` |
| `uk_audit_log_chain_seq` | `t_audit_log` | **防篡改链式校验的顺序基准** | `FR-AUDIT-10`、`NFR-SEC-6` |

### 4.3 一致性约束（由数据库保证）

| 约束类型 | 说明 | 主要实例 |
|---------|------|---------|
| **唯一性** | 业务编号、幂等键、组合唯一、条件唯一 | 见 §4.1；**幂等键唯一是本系统可靠性的基石**（`NFR-REL-5`） |
| **外键** | **一律 `ON DELETE RESTRICT` / `ON UPDATE RESTRICT`**；**禁止级联删除** | `sub_account_id` → `t_sub_account`；追溯链外键（`t_trade_fill.trade_plan_id` → `t_trade_plan` 等） |
| **非空** | 关键维度与合规必需字段 | `sub_account_id`（五类数据表）、`plan_no`、`voucher_no`、三要素列 |
| **检查（取值域）** | 枚举取值、正负、区间 | 见 §3.2 / §3.1 的 `CHECK` 声明 |
| **检查（业务不变量）** | 跨列一致性 | `available_qty <= position_qty`；`quantity < 0 → negative_confirmed='Y'`；`is_sensitive='Y' → config_value IS NULL`；`is_mandatory='Y' → is_enabled='Y'`；`is_domestic_exchange='Y'`；`scan_scope='OPEN_ONLY'`；`blocked_operation='NONE'`；`pending_external_review='Y'`；`ck_trade_reason_related_or_missing`（关联对象齐备 **或** `related_object_missing_flag='Y'`）；`ck_exposure_product_unmapped`（`unmapped_flag='Y'` ⇔ `product_id=0`〔未映射哨兵值〕；`'N'` ⇔ `product_id>0`，`DC-041`）；`ck_exposure_spot_category_scope`（`'N'` ⇔ `spot_category_code='__MAPPED__'`〔占位常量，使品类不参与已映射行区分〕；`'Y'` ⇔ `<> '__MAPPED__'`〔真实品类〕，`DC-041`） |
| **检查（作用域哨兵值，v1.2 `DC-020`；v1.3 `G-11` **落为具名 `CHECK`**）** | **账户级/全账户口径的"作用域列"一律用哨兵值 `0`，不用 `NULL`**，由**具名 `CHECK`** 与**非空的**范围列**双向联动（`⇔`）**；哨兵值外键指向保留系统行 `t_sub_account(id=0)`。**目的：使唯一约束的确定性不依赖 DM8 对 `NULL` 在唯一索引中的处理** | 见下方**可执行 DDL（5 条具名 `CHECK`）**：`ck_product_quota_layer_sentinel`（`TB-PRODUCT-6`）/ `ck_annual_scale_scope_sentinel`（`TB-RISK-11`）/ `ck_risk_threshold_scope_sentinel`（`TB-RISK-1`）为**双向联动型**；`ck_check_task_scope_sentinel`（`TB-SETTLE-13`）/ `ck_check_signature_scope_sentinel`（`TB-SETTLE-15`）为**范围标注型**（该二表**无第二判别列**，取值域 = `{0} ∪ (0,∞)`，**不存在被排除组合**，`CHECK (sub_account_id >= 0)` 即完整表达，**不外加凭空判别列**） |

**作用域哨兵值具名 `CHECK`（v1.3 `G-11` 落为可执行 DDL，供建表脚本直接引用）**：

```sql
-- 双向联动型（⇔）：既强制「账户级行 = 0 / 行级行 > 0」，也拒绝被排除组合（如 (SUB_PRODUCT, 0)）
ALTER TABLE t_product_quota ADD CONSTRAINT ck_product_quota_layer_sentinel CHECK (
     (limit_layer = 'ACCOUNT_PRODUCT' AND sub_account_id = 0)
  OR (limit_layer = 'SUB_PRODUCT'     AND sub_account_id > 0)
);
ALTER TABLE t_annual_scale ADD CONSTRAINT ck_annual_scale_scope_sentinel CHECK (
     (scope_type = 'TOTAL_ACCOUNT' AND sub_account_id = 0)
  OR (scope_type = 'SUB_ACCOUNT'   AND sub_account_id > 0)
);
ALTER TABLE t_risk_threshold ADD CONSTRAINT ck_risk_threshold_scope_sentinel CHECK (
     (scope_type = 'GLOBAL'      AND sub_account_id = 0)
  OR (scope_type = 'SUB_ACCOUNT' AND sub_account_id > 0)
);
-- 范围标注型：本二表无第二判别列，取值域 = {0} ∪ (0,∞)，不存在"被排除组合"；
-- 该约束登记「作用域列」身份并拒绝负值（不得为迁就 CHECK 而凭空新增判别列）
ALTER TABLE t_check_task ADD CONSTRAINT ck_check_task_scope_sentinel CHECK (
     sub_account_id >= 0
);
ALTER TABLE t_check_signature ADD CONSTRAINT ck_check_signature_scope_sentinel CHECK (
     sub_account_id >= 0
);
```

> **5 条具名 `CHECK` 的落点（三处必须逐字一致，实现者任取其一即可建表）**：
> ① **本节（§4.3 约束目录，唯一权威 DDL）**；② **§2 各表定义**（`TB-PRODUCT-6` / `TB-RISK-11` / `TB-RISK-1` / `TB-SETTLE-13` / `TB-SETTLE-15` 的「具名 `CHECK`」行）；
> ③ **§6.1 `D16`**（换库清单引用同一批具名约束）。三处任一不一致即为缺陷。

> **等价的拒绝式（`G-11` 点③）**：上述 `CHECK` 为**双向蕴含**，故**同时拒绝**被排除组合——例如
> `(limit_layer='SUB_PRODUCT', sub_account_id=0)` 被 `ck_product_quota_layer_sentinel` 拒绝
> （等价于 `CHECK (limit_layer <> 'SUB_PRODUCT' OR sub_account_id > 0)`）。**测试用例「层①行以哨兵 `0` 写入 → 应被拒」由此具备数据库级判据**（原 `D16` 脚本前件亦随之成立）。

> ⚠️ **术语分离（v1.3 `G-11` 写死）—— 以下两个集合互不相同，禁止混用**：
> - **A. 行级归属类（五类业务数据行）**：**需逐行归属到具名真实子账户**的业务数据行 → `sub_account_id` **恒 `> 0`**（禁止以 `0` 冒充子账户，否则污染加总口径）。
> - **B. 作用域列（账户级/全账户口径）**：`t_product_quota`（层③）/ `t_annual_scale`（全账户口径）/ `t_risk_threshold`（`GLOBAL`）/ `t_check_task`·`t_check_signature`（跨子账户）的 `sub_account_id` → **允许且仅允许**哨兵 `0`。
>
> 故「**五类业务数据表的 `sub_account_id` 恒 `> 0`**」**仅指 A 类行**；`t_product_quota` / `t_annual_scale` 是**混合表**（同时含 A 行与 B 行），其 B 行由上述具名 `CHECK` 按 `limit_layer` / `scope_type` **分派**，**不违反 A**。与 §0.4「作用域列例外」表述一致。**规则不再只停在散文层**——`A`/`B` 的边界由具名 `CHECK` 在**数据库级**保证（`§4.3` 标题即"由数据库保证"）。
> **另一定义处（§4.6 侧）**：§4.6.2 顶部「术语」块已**同步写死**同一 A/B 分离，并在 §4.6.1(1b) / §4.6.2 / §4.6.4 **逐处标注「作用域列例外」**。两处定义必须同义，任一处被改写须同步另一处。

### 4.4 无法由数据库保证的业务规则（**服务层 + 测试**）

> 这些规则**必须**在服务层实现，且**必须**由测试经理在 M1 会签时确认其**可测性**。
> **不得**用触发器实现（`PROJECT.md` §6 永久禁止）。

| 规则 | 为何 DB 无法保证 | 服务层实现点 | 建议测试用例 |
|------|---------------|-------------|------------|
| **借贷平衡**（`SUM(借方) = SUM(贷方)`） | 跨行聚合 | `AcctService` 同事务内校验 | 构造不平衡分录 → 拒绝并回滚 |
| **计划数量 ≤ 敞口未覆盖数量** | 跨表聚合 | `PlanService` | 边界：等于、超出 1 单位 |
| **`SUM(交易计划数量) ≤ 套保计划数量`** | 跨行聚合 | `PlanService` | 边界：合计等于、超出 |
| **`Σ 子账户 = 真实账户`** | 跨表聚合 + 与外部数据比对 | `SettleService` / `FundService`；**差异归属的机判来源见下方「阻断范围的取数路径」** | 一致 → `CONSISTENT` / 人为构造不一致 → `INCONSISTENT_BLOCKED` 且 `block_effective_time` 非空 |
| **岗位分离互斥** | 跨行跨表语义（互斥矩阵） | `AuthService` + `C++ 0x100E` 兜底 | 两两互斥组合穷举 |
| **需求 `p1 ≠ p2 ≠ p3`** | 含 NULL 的三方比较 | `SettleService` | 同一账号充当两方 → 拒绝 |
| **"日期不得晚于当日"** | `CURRENT_DATE` 非确定性 | `SpotService` | 录入未来日期 → 拒绝（"当日"取自**业务日期**，非 DB `CURRENT_DATE`） |
| **额度不得跨品种/跨子账户串用** | 语义级（无"串用"字段） | `VarietyService` + `C++ 0x1004` | 用 A 品种额度给 B 品种下单 → 拒绝 |
| **敞口状态由引用关系自动计算** | 派生计算 | `SpotService` | 计划驳回后敞口状态回退 |
| **阻断状态的级联影响** | 需要跨模块读取对账结论 | `SettleService` 提供查询 + 各服务调用（**取数路径见下方注**） | 阻断期间：差异子账户**新开仓被拒**、**平仓放行**、**其他子账户正常**、划拨/关账被拒 |
| **审计日志"先写日志再导出"** | 时序约束 | `AuditService` | 模拟日志写失败 → 导出中断 |
| **规模红线分母缺失 → 阻断**（`Q-1`） | 跨表存在性判断 | `RiskService` + C++ 判定 | 未维护 `t_annual_scale` 分母 → 明确报错并阻断（**不得默认 0**） |
| **三层限额"单笔按最细命中者"**（`Q-16`，**v1.2 写死全序 + 并列取一**；**v1.3 补构造纪律 `S-9`**） | 三层优先级语义（**全序 + 确定性并列规则**） | C++ 事前风控 + `RiskService` | **全序（写死）：层②（子账户+合约 `SUB_INSTRUMENT`）> 层①（子账户+品种 `SUB_PRODUCT`）> 层③（账户+品种 `ACCOUNT_PRODUCT`）**；**层③为总账户兜底，不参与"最细命中"的选择**。<br>**并列取一（确定性）**：**同一层级内命中多条**（如多条层①额度）时，取 **占用率最高者**（`(used+frozen)/quota_limit` 最大）；**占用率再并列**时按 **`id` 升序**取一。**保证同一场景结果唯一、用例可复现**。<br>**单笔校验**：三层同时判定；命中多层时 `limit_layer` 取全序最细者；`EVT_PRE_TRADE_REJECT` 的 **`entries[]` 携带全部命中项**（不只最细者，便于审计复算）。<br>**累计校验**：三层**同时**执行，**任一超限即拒绝**。<br>用例：① 仅层① → `SUB_PRODUCT`；② 仅层② → `SUB_INSTRUMENT`；③ 仅层③ → `ACCOUNT_PRODUCT`；④ **三层同时超 → 必须回 `SUB_INSTRUMENT`**；⑤ 层①+层③同时超 → 回 `SUB_PRODUCT`；⑥ **同层两条额度同时命中 → 必回占用率高者**（占用率并列时回 `id` 小者）；⑦ 错误码与层级一致（`0x1004`/`0x1017`/`0x1005`）。<br>**⚠️ 构造纪律（`S-9`，防测试误判）**：用例⑥/⑦依赖的**占用率权威在 C++ 内存**——`t_product_quota.used_qty`/`frozen_qty` 等 DB 投影**仅供展示**，**不得据 DB 投影做判定**（见 `TB-PRODUCT-6` 备注「`used_qty` / `frozen_qty` 是内存权威值的库内投影」、`R-RISK-9`）。故⑥/⑦**必须经协议/内存构造**（`0x0113` `CMD_UPDATE_QUOTA` / `CMD_LOAD_RISK_PARAMS` 注入额度与占用后再下单），**不得用 DB 直插构造**，否则会产生与 C++ 实际判定不一致的**反例误判** |
| **"不可代签"且"签署人须为应签角色本人"**（`Q-14`，**v1.2 补判据**） | 跨表角色比对；**`uk_check_signature_task_role` 只保证 (task, role) 唯一，不防"错人签"** | `SettleService` | **判据（角色级，写死）**：签署时解析 `signer_role_code`，须**同时**满足 ① 该用户**确实持有** `signer_role_code`（查 `t_user_role`）；② `signer_role_code ∈ t_check_task.required_sign_roles`（集合成员）；③ 显式提交的 `sign_role` **等于** `signer_role_code`。任一不满足 → **拒绝签署**、置 `proxy_rejected='Y'`、记 `t_unauthorized_attempt`。<br>**多角色账号解析规则**：`signer_role_code` **由签署动作显式选择**（请求携带 `sign_role`）；**未显式选择、或所选角色不在 `required_sign_roles`、或用户不持有该角色 → 一律拒绝**（**不猜测、不默认取任一角色**）。<br>用例：① 非应签角色账号签署 → 拒绝 + `proxy_rejected='Y'`；② 用户持多角色但选择非应签角色 → 拒绝；③ 应签角色本人签署 → 通过；④ 同任务同角色二次签署 → 被 `uk_check_signature_task_role` 拒绝 |
| **"签署后快照变更须重签"**（`Q-14`） | 时序 + 哈希比对 | `SettleService` | 签署后改数据 → `snapshot_hash` 变更 → 任务回到 `PENDING_SIGN`，原签署失效 |
| **交易理由：关联对象必选（提交时拒绝）+「理由缺失 / 关联缺失」并列可筛查**（`Q-13`，**v1.2 按 `CH-004`**，`DC-024`） | 跨表归属比对 + **提交期/存续期分离** | `TradeService`（+ C++ 复核 `0x1019`） | ① **提交时（权威）**：`related_plan_id`/`related_exposure_id` **必填**，且须与 `sub_account_id` **同子账户**；不通过 → **拒绝提交**（`FR-TRADE-10` 验收 2；`0x1019` 为 C++ 兜底复核）。<br>② **存续期（补填/异常态）**：两列可空**仅**用于补填/异常，此时 `related_object_missing_flag='Y'`（`ck_trade_reason_related_or_missing` **结构强制**，不可能出现"两列皆空且未标缺失"的行）→ 为该校验/补填动作**生成 `RELATED_MISSING` 筛查命中项**（`t_violation_screen_item`，筛查侧生成）。<br>③ **两个筛查维度并列独立**：`REASON_MISSING`（无理由记录/理由为空，`reason_missing_flag`）与 **`RELATED_MISSING`**（理由已填但关联对象未提供，`related_object_missing_flag`）——**均可被索引直接筛出、均可构造用例**（`DC-014`）。<br>用例：① 缺关联提交 → 拒绝且不落库；② 关联对象跨子账户提交 → 拒绝（`0x1019`）；③ 补填态落库（`flag='Y'`）→ 命中 `RELATED_MISSING`；④ 无理由记录 → 命中 `REASON_MISSING`；⑤ 两列皆空且 `flag='N'` 的插入 → 被 `CHECK` 拒绝 |
| **CTP 侧直接产生成交的理由登记**（`Q-13` 扩展，v1.2 `DC-024`） | 枚举取值域 | `TradeService` | 强制平仓 / 到期交割 / 错单更正等**非本系统下单路径**的成交，其理由以 **`related_type ∈ {FORCE_CLOSE, DELIVERY, ERROR_CORRECTION}`** 表达；**不另设标记列**；关联对象与说明要求同上（关联对象仍必选）。用例：以 `related_type='FORCE_CLOSE'` 登记 → 落库成功且可按 `related_type` 聚合 |
| **敞口三分量缺失 / 合计不一致的处理**（`Q-17`，**v1.2 写死；合并版**） | 跨列算术恒等式 + 分量可用性 | `SpotService`（写入同事务）/ `PlanService`（比率）/ `AcctService`（估值）/ `RiskService`（再平衡） | ① **写入侧（默认拒绝）**：三分量与合计**同事务计算**，`Σ分量 ≠ exposure_qty` → **拒绝写入并报错**（**不得静默覆盖落库值**）。<br>② **展示侧**：以**分量重算** + 告警 + 留痕（`t_spot_change_log.change_type` 增 **`COMPONENT_RECALC`**）；**展示不得回写落库值**。<br>③ **确需改落库值**：须**重算生成新快照**（新 `snapshot_id`），**旧快照保留**（**禁止静默覆盖**，否则 `Q-8`「敞口变动 >20%」的判定依据失真）。<br>④ **沿用最近一期分量**（估值/展示场景）：须**显式接口调用 + 留痕**，并标注 **`price_as_of` / `snapshot_as_of`** 与"非当期"标记；**不得**无标注静默沿用。**套保比率计算用途下不得沿用，一律拒绝**。<br>⑤ **`Q-8`「敞口变动 >20%」判定**：分量不完整时 **暂缓判定并告警**，**不得用残缺分量得出触发结论**（其余三项触发不受影响）。<br>用例：① 改分量使 `Σ ≠ 合计` → 写入被拒；② 展示重算 → 有告警 + `COMPONENT_RECALC` 留痕且未回写；③ 改落库 → 生成新 `snapshot_id` 且旧快照仍可查；④ 缺分量做比率 → 报错；⑤ 缺分量 + 敞口变动 >20% → 无生效触发记录、仅告警 |
| **"管理层签署权与交易权互斥"**（`Q-14`） | 跨表权限语义 | `AuthService` + C++ `0x100F` 分支 | 管理层账号（`is_management_role='Y'`）下单 → 拒绝；核验留痕见 `t_check_signature.manage_role_trade_blocked` |
| **"在途不计为断点"**（**v1.2 写死判定条件与时窗**） | 语义判定 + 时窗（**当日结算时点，上限 1 个结算周期**） | `AuditService` | **在途定义**：**已发 CTP 且未收到回报**（`t_trade_order.status='SENT_CTP'`；属「**已成交未结算**」的子情形；为覆盖完整链，**「已结算未出凭证」**亦按本规则处理）。<br>**时窗（两档写死）**：① **当日结算时点前** → `chain_integrity='IN_FLIGHT'`，**不计为断点**；② **逾当日结算时点仍未收到回报 → 转 `UNKNOWN` 并告警**（**严禁**继续挂"在途"掩盖）；③ **再逾（上限 = 1 个结算周期，配置 `audit.chain.inflight_max_cycles`）→ `HAS_BREAK`** 并写入 `break_list`。<br>**无法判定（数据缺失）→ `UNKNOWN`**，**不得**记为 `IN_FLIGHT`。<br>用例：① 已发未回报、当日结算时点前 → `IN_FLIGHT` 不计断点；② 逾期未回报 → `UNKNOWN` + 告警（**不得记在途**）；③ 再逾 1 个结算周期 → `HAS_BREAK` 且 `break_list` 非空；④ 已结算未出凭证 → 按同规则处理 |
| **追溯链完整性** | 图遍历 | `AuditService` | 人为移除关联 → 输出断点（`break_list` 非空） |
| **子账户作用域哨兵值边界**（`G-5`，**v1.2**；**v1.3 `G-11` 术语分离**） | 语义级取值边界（`DB` 已保证**双向联动**，见 §4.3 具名 `CHECK`） | 各写入服务 + `ConfigService` | ① **哨兵值 `0` 仅允许出现在"作用域列"**（`t_product_quota` / `t_annual_scale` / `t_risk_threshold` / `t_check_task` / `t_check_signature`）；② **A 类"行级归属"行（五类业务数据行）的 `sub_account_id` 恒 `> 0`**；以 `0` 写入**混合表的 A 类行** → **由 §4.3 具名 `CHECK` 拒绝**并告警；**其余五类表**（如 `t_trade_order`）的 `sub_account_id=0` **在 DB 层并不被拒**（FK 目标保留系统行 `t_sub_account(id=0)` 存在），**由服务层保证**（见 §0.2「业务数据表禁用（由服务层保证）」、§4.4 本行；DB 层仅对上述 5 张作用域表保证，见 §0.4）。**注**：`t_product_quota` / `t_annual_scale` 为**混合表**（A/B 行并存），其 B 行 `=0` 合法（详见 §4.3 术语分离） |

#### 4.4.1 「阻断范围的取数路径」——差异归属的**机判来源**（`Q-11` / `NFR-REL-6`，`G-6` 整改）

> **问题（测试经理 `G-6`）**：`t_aggregation_check.possible_sub_accounts` 原为 `VARCHAR(500)` **自由文本**，
> 导致"**仅差异所属子账户被阻断**、其他子账户正常、平仓不被阻断"三条断言**无结构化取数来源**。
> **整改（v1.2）**：新增**结构化候选表** `t_agg_diff_candidate`（`TB-SETTLE-16`），
> `possible_sub_accounts` **降级为展示缓存**（不作判定）。

| 判定用途 | **机判取数路径（唯一事实源）** | 说明 |
|---------|--------------------------|------|
| **差异的候选归属子账户**（差异尚未定位到单一子账户时） | **`t_agg_diff_candidate`**：`aggregation_check_id` → `sub_account_id`（`NOT NULL`，**每个候选一行**）+ `candidate_rank` + `diff_amount` + `resolution_status` | 机判可枚举、可排序、可逐行断言 |
| **差异的**已定位**归属子账户**（定位完成后） | **`t_reconcile_difference.sub_account_id`**（`TB-SETTLE-10`，可定位时非空） | 与 `t_agg_diff_candidate.resolution_status='CONFIRMED'` 关联闭环 |
| **阻断生效的子账户集合** | **`t_agg_diff_candidate` 中 `resolution_status ∈ {PENDING, CONFIRMED}` 的 `sub_account_id` 全集**（`DISTINCT`） | 断言：该集合内子账户**新开仓被拒**；集合外子账户**正常**；**平仓恒放行**（`block_scope='DIFF_SUB_NON_CLOSE'`） |
| **展示用途（非判定）** | `t_aggregation_check.possible_sub_accounts`（自由文本，**仅界面提示**） | **禁止**作为阻断范围判定的取数来源 |

> **判定纪律**：任何以"差异归属"为前提的阻断/放行判定，**必须**取自 `t_agg_diff_candidate`（候选态）
> 或 `t_reconcile_difference.sub_account_id`（定位态），**不得**解析 `possible_sub_accounts` 文本。

### 4.5 与 `ARCHITECTURE.md` 的一致性声明

| 项 | 声明 |
|----|------|
| **软外键** | `t_exposure_detail.source_id` 是**多态外键**（按 `source_type` 指向 5 张表之一），**无 DB 外键约束**——本模型**唯一**的软外键处，由服务层写入时校验。已在 `TB-SPOT-9` 备注声明 |
| **权威源** | `t_position` / `t_product_quota.used_qty|frozen_qty` 是**落库投影**（权威源为 C++ 内存，见 `ARCHITECTURE.md` §4.4）；**不得**据此做判定 |
| **不落库的数据** | **行情**（`EVT_MARKET_QUOTE`）**不落库**（仅内存 + WS 推送，`FR-TRADE-12`）；**敏感字段**不落库（`CFG-19`） |
| **视图** | `v_position_summary_by_sub` 是**视图**而非表——满足"汇总不替代明细"（`R-SETTLE-9`）；视图**不是**存储过程/触发器/自定义函数，**符合** `PROJECT.md` §6 |
| **唯一例外** | 借贷平衡等跨行聚合约束（§4.4）**未**下沉到数据库（因触发器被禁止）；这是本项目**唯一**的"约束未下沉"处，须测试经理会签确认 |

### 4.6 ⭐ 子账户贯穿字段逐表核对（M1 门禁核心审查项）

> **依据**：`INDEX.md` §2.1「子账户贯穿字段规范」原文——
> "每张业务表凡涉及**资金、持仓、交易、敞口、凭证**者，**必须显式携带子账户标识**，
> **不得依赖'通过关联表间接推导'**"。
>
> **核对方法**：逐表检查 `sub_account_id`（或语义等价的显式子账户列）是否存在、
> 是否 `NOT NULL`、是否**行级携带**（而非靠 join）、是否**可直接求和**。
>
> **图例**：✅ 行级携带且 `NOT NULL` · ⚠️ 行级携带但可空（已在备注说明原因） · ➖ 不适用（见右下"例外说明"）。

#### 4.6.1 五类数据逐类核对

**（1）敞口类**（`INDEX.md` §2.1 第 1 行：采购合同/订单、销售合同/订单、生产计划/在制品、库存台账、实货敞口、现货价格记录）

| # | 表编号 | 表名 | `sub_account_id` | **可加总（口径 A）** | 核对结论 |
|---|--------|------|-----------------|--------|---------|
| 1 | `TB-SPOT-1` | `t_purchase_contract` | ✅ `NOT NULL` | ✅ **可加总** | ✅ |
| 2 | `TB-SPOT-2` | `t_sales_contract` | ✅ `NOT NULL` | ✅ **可加总** | ✅ |
| 3 | `TB-SPOT-3` | `t_production_plan` | ✅ `NOT NULL` | ✅ **可加总** | ✅ |
| 4 | `TB-SPOT-4` | `t_production_plan_line` | ✅ `NOT NULL`（**冗余自计划头**） | ✅ **可加总**（可直接按子账户聚合） | ✅ |
| 5 | `TB-SPOT-5` | `t_wip_record` | ✅ `NOT NULL` | ✅ **可加总** | ✅ |
| 6 | `TB-SPOT-6` | `t_inventory_ledger` | ✅ `NOT NULL` | ✅ **可加总**（库存量） | ✅ |
| 7 | `TB-SPOT-7` | `t_spot_price` | ✅ `NOT NULL`（**录入归属**；`GLOBAL` 模式下 `is_shared='Y'`） | ➖ **不计入 43**（**单价，不参与加总**） | ⚠️ 已说明（`RSK-05`/`CFG-18`/**`CH-003` 口径 A 已闭环**；**属①但单价不加总，故单列于 43 之外**） |
| 8 | `TB-SPOT-8` | `t_exposure` | ✅ `NOT NULL` | ✅ **可加总**（敞口数量） | ✅ |
| 9 | `TB-SPOT-9` | `t_exposure_detail` | ✅ `NOT NULL`（**冗余，不 join 即可聚合**） | ✅ **可加总**（贡献量） | ✅ |
| 10 | `TB-SPOT-10` | `t_spot_change_log` | ⚠️ 可空（**品种档案本体变更时为 `NULL`**） | ➖ **不计入 43**（可空**留痕表**，无加总度量） | ⚠️ 已说明 |

**（1b）敞口/额度类 v1.1 新增表**（源自 `CH-001` / `CH-002`）

| # | 表编号 | 表名 | `sub_account_id` | **可加总（口径 A）** | 核对结论 |
|---|--------|------|-----------------|--------|---------|
| 10a | `TB-PRODUCT-6` | `t_product_quota`（层①/层③） | ✅ `NOT NULL`（层①）/ **哨兵值 `0`**（层③为账户级，v1.2 `DC-020`） | ✅ **可加总**（额度与占用；**层③按哨兵值单列聚合**） | ✅（层③哨兵值已说明；**作用域例外**：层③ B 类行 `=0` 合法，见 §4.3 术语分离） |
| 10b | **`TB-PRODUCT-8`** | **`t_sub_instrument_quota`（层②）** | ✅ `NOT NULL` | ✅ **可加总** | ✅ |
| 10c | **`TB-RISK-11`** | **`t_annual_scale`（规模基数）** | ✅ `NOT NULL`（子账户口径）/ **哨兵值 `0`**（全账户口径，v1.2 `DC-020`） | ✅ **可加总**（分子分母；**全账户口径按哨兵值单列聚合**） | ✅（全账户口径哨兵值已说明；**作用域例外**：全账户 B 类行 `=0` 合法，见 §4.3 术语分离） |

> **作用域例外（`G-11`）**：本组 10a / 10c 为**混合表**——A 类行级归属行 `>0`、B 类账户级行 `=0`；**仍计入五类①（计数口径不变）**，但须按 §4.3 具名 `CHECK` 分派，故「五类业务数据行 `sub_account_id` 恒 `>0`」**不适用于其 B 类行**。

**敞口类小计**：**13 张表，12 张物理 `NOT NULL` 行级携带、1 张可空已说明、0 张遗漏**；
其中 **11 张计入「可加总 43」**（`t_spot_price` 属①但单价不参与加总，单列于 43 之外）。

**（2）计划类**（`INDEX.md` §2.1 第 2 行：套保计划、交易计划、套期关系书面文件）

| # | 表编号 | 表名 | `sub_account_id` | **可加总（口径 A）** | 核对结论 |
|---|--------|------|-----------------|--------|---------|
| 11 | `TB-PLAN-1` | `t_hedge_plan` | ✅ `NOT NULL` | ✅ **可加总**（额度占用） | ✅ |
| 12 | `TB-PLAN-2` | `t_hedge_plan_exposure` | ✅ `NOT NULL`（**连接表仍显式携带**） | ✅ **可加总**（覆盖量） | ✅ |
| 13 | `TB-PLAN-3` | `t_hedge_ratio_calc` | ✅ `NOT NULL` | ✅ **可加总**（属①；度量可 `GROUP BY` 求和） | ✅ |
| 14 | `TB-PLAN-4` | `t_hedge_doc` | ✅ `NOT NULL` | ✅ **可加总**（属①；度量可 `GROUP BY` 求和） | ✅ |
| 15 | `TB-PLAN-5` | `t_plan_approval` | ✅ `NOT NULL` | ✅ **可加总**（属①；度量可 `GROUP BY` 求和） | ✅ |
| 16 | `TB-PLAN-6` | `t_trade_plan` | ✅ `NOT NULL` | ✅ **可加总**（计划数量） | ✅ |
| 17 | `TB-PLAN-7` | `t_plan_state_log` | ✅ `NOT NULL` | ✅ **可加总**（额度动作量） | ✅ |

**计划类小计**：**7 张表，7 张行级携带（**均计入「可加总 43」**），0 张遗漏**。

**（3）交易类**（`INDEX.md` §2.1 第 3 行：交易指令、委托、成交记录、事前风控拒绝记录、交易理由记录）

| # | 表编号 | 表名 | `sub_account_id` | **可加总（口径 A）** | 核对结论 |
|---|--------|------|-----------------|--------|---------|
| 18 | `TB-TRADE-1` | `t_trade_order` | ✅ `NOT NULL`（**协议级强制携带**） | ✅ **可加总**（指令量） | ✅ |
| 19 | `TB-TRADE-2` | `t_trade_order_report` | ✅ `NOT NULL` | ✅ **可加总**（委托量） | ✅ |
| 20 | `TB-TRADE-3` | `t_trade_fill` | ✅ `NOT NULL`（**随指令携带并随成交写入**） | ✅ **可加总**（成交量） | ✅ |
| 21 | `TB-TRADE-4` | `t_trade_reason` | ✅ `NOT NULL` | ✅ **可加总**（属①；度量可 `GROUP BY` 求和） | ✅ |
| 22 | `TB-TRADE-6` | `t_pre_trade_reject` | ✅ `NOT NULL` | ✅ **可加总**（属①；度量可 `GROUP BY` 求和） | ✅ |
| 23 | `TB-TRADE-7` | `t_command_flow` | ✅ `NOT NULL` | ✅ **可加总**（属①；度量可 `GROUP BY` 求和） | ✅ |
| 24 | `TB-TRADE-8` | `t_option_strategy` | ✅ `NOT NULL` | ✅ **可加总**（额度占用） | ✅ |
| 25 | `TB-TRADE-9` | `t_replay_log` | ➖ | ➖ **不计入 43**（无该列） | ➖ 见例外说明 E3 |

**交易类小计**：**8 张表，7 张行级携带（**均计入「可加总 43」**），1 张不适用（组件级重放日志）**。

**（4）持仓类**（`INDEX.md` §2.1 第 4 行：持仓明细、持仓汇总、结算持仓、保证金占用）

| # | 表编号 | 表名 | `sub_account_id` | **可加总（口径 A）** | 核对结论 |
|---|--------|------|-----------------|--------|---------|
| 26 | `TB-TRADE-5` | `t_position` | ✅ `NOT NULL` | ✅ **可加总**（持仓量） | ✅ |
| 27 | `TB-SETTLE-5` | `t_settle_cycle` | ✅ `NOT NULL`（汇总行用占位子账户 + `is_aggregate`） | ✅ **可加总** | ✅ |
| 28 | `TB-SETTLE-6` | `t_settle_position` | ✅ `NOT NULL` | ✅ **可加总（加总对账的核心）** | ✅ |
| 29 | `TB-SETTLE-2` | `t_ctp_settle_position` | ➖（**外部基准数据**） | ➖ **不计入 43**（无该列） | ➖ 见例外说明 E1 |
| 30 | `TB-SETTLE-8` | `t_settle_pnl` | ✅ `NOT NULL` | ✅ **可加总**（盈亏） | ✅ |
| 31 | `TB-SETTLE-14` | `t_stress_test` | ⚠️ 可空（`NULL` = 全账户） | ➖ **不计入 43**（**可空**；全账户行按 `scope` 单列聚合） | ⚠️ 已说明（全账户场景） |

> **"持仓汇总"的处理**：`R-SETTLE-9` 要求"**不得以汇总行 + 关联表替代子账户明细**"。
> 本模型**不落"持仓汇总表"**——汇总由 `t_position` 的 SQL 聚合或视图 `v_position_summary_by_sub` 得到
> （`Σ 子账户明细 ≡ 汇总`**天然成立**）。这是**刻意不建表**的设计决定，**不是遗漏**。

**持仓/保证金类小计**：**6 张表，4 张行级携带（**均计入「可加总 43」**）、1 张不适用（外部基准）、1 张可空已说明**。
（保证金占用由 `t_settle_fund.margin_occupied` 与 `t_fund_ledger.margin_occupied` 承载，均已携带子账户标识。）

**（5）资金类**（`INDEX.md` §2.1 第 5 行：资金台账、资金流水、资金额度）

| # | 表编号 | 表名 | `sub_account_id` | **可加总（口径 A）** | 核对结论 |
|---|--------|------|-----------------|--------|---------|
| 32 | `TB-FUND-1` | `t_fund_ledger` | ✅ `NOT NULL` | ✅ **可加总（加总对账的核心）** | ✅ |
| 33 | `TB-FUND-2` | `t_fund_flow` | ✅ `NOT NULL` | ✅ **可加总**（发生额） | ✅ |
| 34 | `TB-FUND-7` | `t_fund_quota` | ⚠️ 可空（`quota_scope='ACCOUNT'` 时为 `NULL`） | ➖ **不计入 43**（**可空**；账户级额度按 `quota_scope` 单列聚合） | ⚠️ 已说明（账户级额度本身是全域的） |
| 35 | `TB-FUND-3` | `t_deposit_withdraw_apply` | ✅ `NOT NULL` | ✅ **可加总**（金额） | ✅ |
| 36 | `TB-FUND-4` | `t_transfer_apply` | ✅ `NOT NULL`（**划出方**；另显式存 `from` / `to`） | ✅ **可加总** | ✅ |
| 37 | `TB-FUND-5` | `t_capital_receipt` | ✅ `NOT NULL` | ✅ **可加总**（金额） | ✅ |
| 38 | `TB-FUND-6` | `t_fund_review_record` | ✅ `NOT NULL` | ✅ **可加总**（属①；度量可 `GROUP BY` 求和） | ✅ |
| 39 | `TB-FUND-8` | `t_fund_quota_alert` | ⚠️ 可空（账户级告警为 `NULL`） | ➖ **不计入 43**（**可空**，且无加总度量） | ⚠️ 已说明 |
| 40 | `TB-FUND-9` | `t_fund_reconcile` | ⚠️ 可空（账户级对账为 `NULL`） | ➖ **不计入 43**（**可空**；账户级对账按 `scope` 单列聚合） | ⚠️ 已说明 |
| 41 | `TB-SETTLE-3` | `t_ctp_settle_fund` | ➖（**外部基准数据**） | ➖ **不计入 43**（无该列） | ➖ 见例外说明 E1 |
| 42 | `TB-SETTLE-7` | `t_settle_fund` | ✅ `NOT NULL` | ✅ **可加总（加总对账的核心）** | ✅ |

**资金类小计**：**11 张表，7 张 `NOT NULL`（**均计入「可加总 43」**）、3 张可空已说明、1 张不适用（外部基准）**。

**（6）凭证类**（`INDEX.md` §2.1 第 6 行：会计凭证、有效性评估记录、再平衡记录）

| # | 表编号 | 表名 | `sub_account_id` | **可加总（口径 A）** | 核对结论 |
|---|--------|------|-----------------|--------|---------|
| 43 | `TB-ACCT-4` | `t_voucher` | ✅ **`NOT NULL`**（**缺标识则无法插入 → 拒绝生成凭证**） | ✅ **可加总**（金额） | ✅ |
| 44 | `TB-ACCT-5` | `t_voucher_line` | ✅ `NOT NULL`（冗余自凭证头） | ✅ **可加总**（分录金额） | ✅ |
| 45 | `TB-ACCT-2` | `t_effectiveness_assess` | ✅ `NOT NULL` | ✅ **可加总**（属①；度量可 `GROUP BY` 求和） | ✅ |
| 46 | `TB-ACCT-3` | `t_rebalance_record` | ✅ `NOT NULL` | ✅ **可加总**（属①；度量可 `GROUP BY` 求和） | ✅ |
| 47 | `TB-ACCT-1` | `t_hedge_relation` | ✅ `NOT NULL` | ✅ **可加总**（属①；度量可 `GROUP BY` 求和） | ✅ |
| 48 | `TB-ACCT-8` | `t_hedge_effect` | ✅ `NOT NULL` | ✅ **可加总**（双侧盈亏） | ✅ |
| 49 | `TB-ACCT-9` | `t_dual_pnl_report` | ⚠️ 可空（汇总上报为 `NULL`） | ➖ **不计入 43**（**可空**；汇总上报按 `scope` 单列聚合） | ⚠️ 已说明 |
| 50 | `TB-ACCT-10` | `t_voucher_review` | ✅ `NOT NULL` | ✅ **可加总**（属①；度量可 `GROUP BY` 求和） | ✅ |
| 51 | `TB-ACCT-11` | `t_cas24_selfcheck` | ⚠️ 可空（全账户自检为 `NULL`） | ➖ **不计入 43**（**可空**，且无加总度量） | ⚠️ 已说明 |
| 52 | `TB-ACCT-7` | `t_period_close_log` | ➖（期间级操作，无子账户维度） | ➖ **不计入 43**（无该列） | ➖ 见例外说明 E2 |
| 53 | `TB-ACCT-6` | `t_accounting_period` | ➖（期间定义，全局） | ➖ **不计入 43**（无该列） | ➖ 见例外说明 E2 |

**凭证类小计**：**11 张表，7 张 `NOT NULL`（**均计入「可加总 43」**）、2 张可空已说明、2 张不适用（期间级）**。

**（7）核对与流程类 v1.1 新增表**（源自 `CH-002` `Q-14`）

| # | 表编号 | 表名 | `sub_account_id` | 核对结论 |
|---|--------|------|-----------------|---------|
| 53a | `TB-SETTLE-13` | `t_check_task`（核对任务） | ✅ `NOT NULL`（**跨子账户核对取哨兵值 `0`**，v1.2 `DC-020`） | ⚠️ 已说明（核对任务可跨子账户；界 "哨兵值 `0`" 表达）。**不属五类 → 不计入 43** |
| 53b | **`TB-SETTLE-15`** | **`t_check_signature`（签署记录）** | ✅ `NOT NULL`（**随任务头，跨子账户为哨兵值 `0`**，v1.2 `DC-020`） | ⚠️ 已说明（签署是**流程维度**，不是五类业务数据；`sub_account_id` 为范围标注）。**不属五类 → 不计入 43** |

> **说明**：核对任务与签署记录属**流程/合规留痕维度**，`INDEX.md` §2.1 的**五类业务数据不包含**它们
> （与 `AUTH` 的用户-子账户授权关系同理）。但它们**仍显式携带 `sub_account_id`**（跨子账户核对时取**哨兵值 `0`**，v1.2 `DC-020`），
> 以便审计按子账户检索（`FR-AUDIT-13`）。**这是"超出五类红线"的主动设计，不是遗漏。**
> **⑦ 全部 4 张为"非五类但属①"的主动携带表，均不计入「可加总 43」（43 仅限五类）。**

#### 4.6.2 五类数据汇总（**M1 门禁核对结果**）

> **术语（v1.2 固化 + v1.3 `G-9` 拆名；编排者对 `W1`/`W2` 的裁决；本文档**禁用**"不携带子账户标识"这一含混表述）**
> ——全库每张表**必须且只能**归入以下三层之一，**三层互斥完备**：
> - **① 物理列 `NOT NULL` 行级携带**：表定义中**存在** `sub_account_id` 列**且声明 `NOT NULL`**。
>   **一律按物理列约束计**（`W2` 裁决）；账户级/全账户取值用**哨兵值 `0`**，**仍属本层**（`DC-020`）。
> - **② 可空但已说明**：存在该列但**可空**，且**已在 §4.6.1 或「例外说明」中给出理由**。
> - **③ 非五类数据表（不适用）**：表中**无** `sub_account_id` 列（含 5 张属五类但有书面理由的例外 E1/E2/E3）。
> **合计恒等式**：① + ② + ③ = 全库表数 = **111**。
>
> **⚠️ 两个口径必须分开引用（v1.3 `G-9` 拆名，消除"一名两义"）**：
> - **口径 A「可加总」= 43 张**：**仅**用于**五类数据中属①且度量可加总**的核心表（**§4.6.4**）。
>   它是①的**子集**：五类①共 **44** 张，其中 `t_spot_price` 虽物理 `NOT NULL`（属①），但**单价不参与加总**，故**不计入 43** → 可加总 = 44 − 1 = **43**。
> - **口径 B「物理列存在」= ① 59 张**（全库三层划分，**§4.6.3**）。二者**定义不同、非矛盾**：`43 ⊂ ①五类(44) ⊂ ①全库(59)`。
> - **§4.6.1 逐表「可加总（口径 A）」列**：标 ✅ 者**恰为 43 张**（五类①减去 `t_spot_price`）；标 ➖ 者逐张注明不计入理由。**除 §4.6.4 外，全文不得用"可加总"指代①。**

| 数据类别 | 涉及表数 | ① 物理 `NOT NULL` 行级携带 | 其中「**可加总**」（口径 A） | ② 可空但已说明 | ③ 不适用（有理由） | **遗漏** |
|---------|---------|-------------------|-------------|----------------|---------|---------|
| **① 敞口（含 v1.1 额度/规模基数 3 张）** | **13** | **12** | **11**（`t_spot_price` 单价不加总，单列） | 1（变更留痕 `t_spot_change_log`） | 0 | **0** ✅ |
| **② 计划** | 7 | 7 | 7 | 0 | 0 | **0** ✅ |
| **③ 交易** | 8 | 7 | 7 | 0 | 1（重放日志，E3） | **0** ✅ |
| **④ 持仓** | 6 | 4 | 4 | 1（`t_stress_test`） | 1（CTP 原始持仓，E1） | **0** ✅ |
| **⑤ 资金** | 11 | 7 | 7 | 3（`t_fund_quota`/`t_fund_quota_alert`/`t_fund_reconcile`） | 1（CTP 原始资金，E1） | **0** ✅ |
| **⑥ 凭证** | 11 | 7 | 7 | 2（`t_dual_pnl_report`/`t_cas24_selfcheck`） | 2（会计期间/关账，E2） | **0** ✅ |
| **（五类小计）** | **56** | **44** | **43** | **7** | **5** | **0** ✅ |
| **⑦ §4.6.1 核对范围内新增的非五类携带表**（核对/流程 + 差异归属 + 规则配置；**非穷举**，见下注） | **4** | **4** | 0（非五类，不计入 43） | 0 | 0 | **0** ✅ |
| **合计（§4.6.1 逐表核对范围）** | **60** | **48** | **43** | **7** | **5** | **0** ✅ |

> **恒等式核对**：五类 44 + 7 + 5 = **56** ✅；合计 48 + 7 + 5 = **60** ✅；可加总 **43 = 五类① 44 − `t_spot_price` 1** ✅（**逐表标注见 §4.6.1，机器计数恰为 43**）。
> **作用域例外（v1.3 `G-11`）**：敞口类 1b 的 `t_product_quota`（层③）/ `t_annual_scale`（全账户）含**账户级行取哨兵 `0`**，**仍计入五类①（计数不变）**；「行级归属行 `sub_account_id` 恒 `>0`」仅约束其 A 类行，B 类行 `=0` 由 §4.3 具名 `CHECK` 分派。
> ⑦ = `t_check_task`（`TB-SETTLE-13`）、`t_check_signature`（`TB-SETTLE-15`）、
> **`t_agg_diff_candidate`（`TB-SETTLE-16`，v1.2 新增）**、**`t_custom_hedge_rule`（`TB-PLAN-8`，v1.2 新增，
> 子账户级**规则配置**表，类同 `t_sub_product_whitelist`，**不计入五类"计划数据"**）**——四者均**超出五类**但**主动携带**子账户标识，
> **均不计入「可加总 43」（43 仅限五类）**。
>
> **⑦ 非穷举说明（v1.3.1 订正，`N-3`）**：本行 ⑦ **仅列 §4.6.1 逐表核对范围（60 张）内**的四张，**并非全库非五类① 的穷举**。
> **全库非五类① = 全库① 59 − 五类① 44 = 15 张**；未列入 ⑦ 的另 **11 张**（均在 §4.6.1 核对范围之外，已含于 §4.6.3 模块级 ① 59）为：
> `t_authorization`、`t_user_sub_account_perm`、`t_sub_product_whitelist`、`t_risk_threshold`、`t_risk_alert`、`t_risk_disposal`、
> `t_abnormal_order_stat`、`t_risk_violation`、`t_limit_usage_stat`、`t_risk_metric_snapshot`、`t_violation_screen_item`（**计数本身不变**）。

#### 4.6.3 三层划分的机器可核对清单与推导（**v1.2 重写，`G-2`/`W1` 整改**）

> **层级定义**（与 §4.6.2 顶部一致，**本文档禁用"不携带"**）：**① 物理列 `NOT NULL` 行级携带** /
> **② 可空但已说明** / **③ 非五类数据表（不适用，表中无 `sub_account_id` 列）**。
> **v1.0/v1.1 的 A/B/C/D 四类清单已废止**：其混淆了"已携带"与"无该列"（C/D 类含大量"已含 `NOT NULL`"的表），
> 且 D 类**重复列入** §4.6.1 已核对的表（`t_trade_plan`、`t_voucher_line`、`t_settle_5/6/7/8` 等），
> 导致归类无法从清单推导（测试经理 `G-2`）。**本节改为可机器复现的模块级汇总 + 显式推导。**

**（1）全库 111 张表 —— 按模块的机器可核对汇总（三层划分）**

| 模块 | 表数 | **① `NOT NULL` 行级携带** | **② 可空但已说明** | **③ 无该列（不适用）** |
|------|-----:|--------------------------:|------------------:|----------------------:|
| `CONFIG` | 7 | 0 | 0 | 7 |
| `AUTH` | 13 | 2 | 2 | 9 |
| `PRODUCT` | 8 | 3 | 1 | 4 |
| `SPOT` | 10 | 9 | 1 | 0 |
| `PLAN` | 8 | 8 | 0 | 0 |
| `TRADE` | 9 | 8 | 0 | 1 |
| `SETTLE` | 16 | 7 | 4 | 5 |
| `RISK` | 11 | 8 | 2 | 1 |
| `AUDIT` | 8 | 1 | 4 | 3 |
| `ACCT` | 12 | 7 | 2 | 3 |
| `FUND` | 9 | 6 | 3 | 0 |
| **合计** | **111** | **59** | **19** | **33** |

> **复核方法（可机器复现，口径 = 物理列约束）**：对 §2 每张表，检查其字段表是否存在 `sub_account_id` 列；
> 无该列 → **③**；有该列且声明 `NOT NULL` → **①**（账户级哨兵值 `0` 仍属①）；有该列但可空 → **②**。
> **不含任何主观判断。**

**（2）推导（合计恒等式）**

```
① 物理列 NOT NULL 行级携带 = 59
② 可空但已说明            = 19
③ 非五类数据表（无该列）   = 33
① + ② + ③ = 111 = 全库表数   ✅
```

**（3）属五类但归入③的「有理由例外」= 5 张**（E1/E2/E3；与 §4.6.2「不适用」列 5 张**逐表对应**）

| 表 | 例外 | 理由 |
|----|:----:|------|
| `t_ctp_settle_position`（`TB-SETTLE-2`） | E1 | 期货公司侧**合并**持仓，本就不含子账户维度 |
| `t_ctp_settle_fund`（`TB-SETTLE-3`） | E1 | 同上（合并资金） |
| `t_replay_log`（`TB-TRADE-9`） | E3 | 组件级重放日志（组件维度） |
| `t_accounting_period`（`TB-ACCT-6`） | E2 | 全局会计期间定义 |
| `t_period_close_log`（`TB-ACCT-7`） | E2 | 全局期间级关账动作 |

**（4）归入③且属非五类的 = 28 张**（= 33 − 5，逐模块可核对）

```
CONFIG 7 + AUTH 9 + PRODUCT 4 + SETTLE 3 + RISK 1 + AUDIT 3 + ACCT 1 = 28
（其中 SETTLE 3 = 5 − E1 的 2；ACCT 1 = 3 − E2 的 2；TRADE 0 = 1 − E3 的 1）
```

> **结论（可复现）**：全库 **111** 张 = ① **59** + ② **19** + ③ **33**（恒等成立）；
> 五类数据 **56 张** = ① **44**（其中「可加总」**43** 〔§4.6.4〕+ `t_spot_price` 1 张单价不加总）+ ② **7** + ③ **5**（44 + 7 + 5 = 56 ✅）。
> **口径区分**：① **44** 为五类物理 `NOT NULL` 计数；「**可加总 43**」= 44 − `t_spot_price`（**仅五类**，`43 ⊂ 44 ⊂ 全库① 59`）。
> v1.0 文档所写的"**53 张不携带**"**与任一正确口径均不符**（既非 5，也非 33），
> 系以陈旧的 106 表数循环推导所致，**予以更正**；本文档**不再使用"不携带"表述**。

**（5）归入③的 33 张表的逐表理由**（与上表逐模块**完全对应**，可逐行核对）

| 模块 | 表 | 理由 |
|------|----|------|
| `CONFIG` | `t_sub_account`（`TB-CONFIG-1`） | **它本身就是子账户维度**（不是"归属某子账户的数据"） |
| `CONFIG` | `t_config_item` / `t_config_change_log`（`TB-CONFIG-2/3`） | 全局配置与配置留痕（子账户级配置由 `scope_type` 表达，非行级归属） |
| `CONFIG` | `t_dict_item` / `t_account_subject` / `t_report_period_config` / `t_ctp_front_config`（`TB-CONFIG-4/5/6/7`） | 全局字典 / 会计科目 / 报告周期 / CTP 前置配置（全局主数据） |
| `AUTH` | `t_user` / `t_role` / `t_role_permission` / `t_user_role`（`TB-AUTH-1/2/3/4`） | 用户与权限主体（**权限维度，非业务数据**，`INDEX.md` §2.1 明确） |
| `AUTH` | `t_position_group` / `t_position_conflict`（`TB-AUTH-5/6`） | 岗位组与互斥矩阵（权限维度） |
| `AUTH` | `t_login_session` / `t_login_log` / `t_password_history`（`TB-AUTH-9/10/11`） | 会话 / 登录日志 / 口令历史（账号级，非子账户级） |
| `PRODUCT` | `t_product` / `t_product_contract` / `t_product_spot_category`（`TB-PRODUCT-1/3/4`） | **品种档案为全局共享**（`R-PRODUCT-7`「品种一份，白名单与额度多份」） |
| `PRODUCT` | `t_product_unit`（`TB-PRODUCT-2`） | 计量单位与换算关系（全局主数据） |
| `RISK` | `t_mirror_watermark`（`TB-RISK-3`） | **组件级镜像水位**（组件维度，非子账户维度） |
| `SETTLE` | `t_ctp_settle_batch`（`TB-SETTLE-1`） | CTP 日终**接收批次**（文件级，与子账户无关） |
| `SETTLE` | `t_ctp_settle_position` / `t_ctp_settle_fund` / `t_ctp_settle_trade`（`TB-SETTLE-2/3/4`） | **例外 E1**：期货公司侧**合并数据**，本就不含子账户维度（子账户拆分正是本系统职责） |
| `SETTLE` | `t_aggregation_check`（`TB-SETTLE-11`） | 加总对账是**账户级校验**（其值本身是"和"）；差异归属由 `TB-SETTLE-16` 结构化承载 |
| `AUDIT` | `t_audit_log_checksum`（`TB-AUDIT-3`） | 审计日志**批次校验值**（链本身跨子账户；明细行 `t_audit_log` 已携带） |
| `AUDIT` | `t_violation_screen_run`（`TB-AUDIT-4`） | **筛查运行级**（`sub_account_scope` 是范围声明；命中明细 `t_violation_screen_item` 已携带） |
| `AUDIT` | `t_evidence_export`（`TB-AUDIT-7`） | 导出**批次级**（`sub_account_scope` 是范围声明；导出内容含子账户列） |
| `ACCT` | `t_accounting_period` / `t_period_close_log`（`TB-ACCT-6/7`） | **例外 E2**：全局会计期间与关账（全局期间级操作） |
| `ACCT` | `t_cas24_selfcheck_item`（`TB-ACCT-12`） | 自检**条目**（随自检头确定子账户；条目本身为条款级） |
| `TRADE` | `t_replay_log`（`TB-TRADE-9`） | **例外 E3**：组件级重放日志（重放是整个组件的行为） |

> **核对**：上表 **7 + 9 + 4 + 1 + 5 + 3 + 3 + 1 = 33** 张，与（1）之 **③ 无该列**合计**一致**。

**例外说明汇总**：

| 例外 | 表 | 理由 | 是否可接受 |
|------|----|------|-----------|
| **E1** | `t_ctp_settle_position` / `t_ctp_settle_fund` / `t_ctp_settle_trade` | **期货公司侧数据是合并的，本就不含子账户维度**——这正是"系统须自行维护子账户拆分"（`PROJECT.md` §4.1.B 关键风险）的原因。它们**不是业务数据，而是外部基准数据** | ✅ **必要且正确**（若强行加子账户列，反而是把系统记账冒充成期货公司数据） |
| **E2** | `t_accounting_period` / `t_period_close_log` | 会计期间与关账是**全局期间级操作**（不按子账户分别关账） | ✅ 可接受（`FR-ACCT-12` 未要求按子账户关账） |
| **E3** | `t_replay_log` | 组件级重放行为（重放整个 C++ 组件的流水） | ✅ 可接受（重放是组件维度，与业务子账户无关） |
| **E4**（v1.1） | `t_check_task` / `t_check_signature` | 核对与签署属**流程/合规留痕维度**（非五类业务数据）；`sub_account_id` 为**范围标注**，**仍显式携带**以便审计检索（`FR-AUDIT-13`） | ✅ 可接受（主动超出五类红线）。**v1.2 起**跨子账户核对取**哨兵值 `0`**（不再用 `NULL`，`DC-020`） |
| **E5**（v1.1） | `t_product_quota` 层③ / `t_annual_scale` 全账户口径 | **层③（账户+品种）与全账户口径的规模基数本身是"全账户"的**，逻辑上不隶属单个子账户；以 `scope_type` / `limit_layer` 显式区分 | ✅ 可接受（层①/子账户口径仍为行级归属）。**v1.2 起**账户级取**哨兵值 `0`**（**不再为 `NULL`**，使唯一约束不依赖 `NULL` 语义，`DC-020`） |

#### 4.6.4 加总可求性的结构保证（`INDEX.md` §2.1 配套约束）

| 保证 | 本模型如何落实 |
|------|--------------|
| **五类数据须"可直接求和"** | **43 张**五类数据表（**口径 A：属五类①且度量可加总**；= 五类① **44** 张 − `t_spot_price`）的 `sub_account_id` **全部 `NOT NULL`**（`TB-ACCT-4` 的 `NOT NULL` 更是"缺标识无法生成凭证"的硬保证）→ `GROUP BY sub_account_id` 即可求和，**无需求 join 任何表**。**逐表标注见 §4.6.1（✅「可加总」恰 43 行）**。**注**：`t_spot_price` 属①但**单价不参与加总**，不在此 43 张内；**账户级哨兵值 `0` 的行须按 `limit_layer`/`scope_type` 单列聚合**（不得与真实子账户混算） |
| **不得用"汇总行 + 关联表"替代明细** | ① **不落"持仓汇总表"**（用视图/聚合，见 §4.6.1 持仓类备注）；② `t_settle_cycle.is_aggregate='Y'` 是**对账结论载体**，且明细表（`t_settle_position` / `t_settle_fund` / `t_settle_pnl`）**不含汇总行**；③ 五类数据表中**没有一张"汇总表"** |
| **不得用"用户→子账户"或"计划→子账户"关联推导替代行级标识** | ① `t_hedge_plan_exposure`、`t_production_plan_line`、`t_exposure_detail`、`t_voucher_line` 等**连接/明细表仍冗余携带**；② `t_trade_order` 的子账户**由协议字段显式携带**（`ARCHITECTURE.md` §4.2.4），**不由会话或计划推导** |
| **`AUTH` 的用户-子账户授权不属于五类** | 已在 §4.6.3（5）`AUTH` 行显式说明（`INDEX.md` §2.1 原文支持） |
| **子账户为逻辑虚拟账户，CTP 报文不含该字段** | 全模型**无一处**假设"从 CTP 回报中获取子账户"；子账户标识的来源只有两个：**业务侧录入时确定**（SPOT/PLAN）或**指令显式携带**（TRADE，经协议） |
| **口径可核对（`G-1`/`G-2`/`W1`/`W2`/`G-9` 整改）** | 本表"**43**"为**口径 A（五类中属①且度量可加总）**，**且** `43 = 五类① 44 − `t_spot_price``；**全库三层划分**的口径 B 见 §4.6.3（**111 = ① 59 + ② 19 + ③ 33**）。**二者定义不同、非矛盾**：`可加总 43 ⊂ 五类① 44 ⊂ 全库① 59` |
| **作用域哨兵值与"五类恒 >0"的边界（`G-11` 整改）** | **两个集合互不相同**：**A 行级归属类**（五类业务数据行）`sub_account_id` **恒 >0**；**B 作用域列**（账户级/全账户口径，见 §4.3）**允许且仅允许** `0`。`t_product_quota` / `t_annual_scale` 为**混合表**（A/B 并存，**仍计入五类①，计数不变**），其边界由 §4.3 **具名 `CHECK`**（`ck_product_quota_layer_sentinel` 等）在**数据库级**保证 |
| **加总闭环（`W5` 整改）** | 「可加总 = 可 `GROUP BY` 求和」只是**必要条件**；**收敛闭环**（`Σ子账户 ≡ 真实账户` 的比对、差异定位、告警与阻断）由 `ARCHITECTURE.md` §6.5 落实，其**差异归属的机判取数**为 `t_agg_diff_candidate`（`TB-SETTLE-16`）与 `t_reconcile_difference.sub_account_id`（见 §4.4.1）。**即"可求和"→"被系统比对并阻断"的链条在 M1 已有落点，不是仅证必要性** |

> **M1 门禁自评结论（技术负责人席，v1.3）**：五类数据 **56 张表逐表核对完成**（含额度/规模基数 3 张），
> **物理 `NOT NULL` 行级携带 44 张**，其中 **「可加总」43 张**（= 44 − `t_spot_price` 单价不加总）、可空但已说明 **7 张**、有理由不适用 **5 张**、遗漏 **0 张**；
> 另有 **4 张非五类但属①的主动携带表**（`t_check_task` / `t_check_signature` / `t_agg_diff_candidate` / `t_custom_hedge_rule`），**均不计入 43**。
> **全库 111 张表**按三层划分：**① 物理 `NOT NULL` 行级携带 59 张 / ② 可空但已说明 19 张 / ③ 非五类无该列 33 张**，
> **① + ② + ③ = 111**，**全部有明确归属或理由（机器可复核，见 §4.6.3）**。
> ⚠️ **本结论属产出方自评**，**不替代**产品经理（业务字段覆盖）与测试经理（可验证性）的独立审查（`PROJECT.md` §9 会签纪律）。

---

### 4.7 FR → 表 映射补全索引（M1 会签用：消除"未被表直接引用"的 FR）

> **说明**：§2 各表的「用途」列已标注主要需求来源。本节**补全剩余 FR 的库表承载**，
> 使 **184 条 FR 全部可追溯到至少一张表**（产品经理席按要求"每个字段都能回答它服务于什么需求"）。
> 此处只列§2 未逐条引用的 FR；已引用者见各表「用途」列。

| 需求编号 | 承载表 | 承载方式 |
|---------|--------|---------|
| `FR-PRODUCT-2` | `TB-PRODUCT-7` | 品种修改 → 变更历史留痕（含前后值） |
| `FR-PRODUCT-3` | `TB-PRODUCT-1`（`status`）+ `TB-PRODUCT-7` + `TB-PRODUCT-5`（`dispatch_status`） | 停用 → 状态置 `DISABLED` + 白名单移出 + 下发 C++ |
| `FR-PRODUCT-4` | `TB-PRODUCT-1`（`status`）+ `TB-PRODUCT-2`（换算关系齐备校验前置） + `TB-PRODUCT-7` | 启用 → 状态置 `ENABLED` + 加入白名单 |
| `FR-PRODUCT-6` | `TB-PRODUCT-1/2/3/4/5/6/8` | 品种详情聚合多表 |
| `FR-PRODUCT-7` | `TB-PRODUCT-1/2`（CSV 全量预校验后一次性插入） | 导入 |
| `FR-PRODUCT-8` | `TB-PRODUCT-1` 等（导出读） | 导出 |
| `FR-SPOT-11` | `TB-SPOT-7`（`source_type='BATCH_PASTE'`） | 批量粘贴录入 |
| `FR-SPOT-12` | `TB-SPOT-7`（`source_type='CSV_IMPORT'` + `import_file_name`） | CSV 导入 |
| `FR-PLAN-2` | `TB-PLAN-1`（状态门禁）+ `TB-PLAN-7`（变更留痕） | 修改草稿计划 |
| `FR-PLAN-3` | `TB-PLAN-1`（`DRAFT` 可物理删除）+ `TB-PLAN-7`（删除留痕） | 删除草稿计划 |
| `FR-PLAN-6` | `TB-PLAN-3`（`ratio_method='SIMPLE'`）+ `TB-PLAN-1`（`hedge_ratio`） | 简单比率法留痕 |
| `FR-PLAN-8` | **`TB-PLAN-8`（规则实体：`ratio_cap`/`direction`/`basis`/`product_id`（`NULL`=全品种）/生效期间/`priority`）** + `TB-PLAN-3`（`custom_rule_id` 引用命中规则）+ `TB-PLAN-1` | **自定义规则法（`Q-6`）**：设内控上限并按"规则命中算法"取一（v1.2 `DC-025`） |
| `FR-PLAN-13` | `TB-PLAN-1`（`status` 回草稿）+ `TB-PLAN-7`（撤销留痕）+ `TB-PLAN-6`（交易计划撤销） | 撤销计划 |
| `FR-PLAN-16` | `TB-PLAN-1`（`status='FROZEN'`/`freeze_reason`）+ `TB-PLAN-7` + `TB-PRODUCT-6/8`（额度保留） | 冻结/解冻 |
| `FR-PLAN-17` | `TB-PLAN-1`（`status='TERMINATED'`/`terminate_reason`）+ `TB-PLAN-7`（额度释放）+ `TB-PLAN-6`（交易计划撤销） | 终止计划 |
| `FR-TRADE-3` | `TB-TRADE-6`（拒绝记录）+ `TB-TRADE-1`（`reject_rule_id`/`reject_code`）+ `TB-PRODUCT-6/8` + `TB-RISK-11`（判定输入来源） | 事前风控校验（**判定在 C++ 内存，库内承载输入与结果**） |
| `FR-TRADE-6` | `TB-TRADE-1`（`CANCELLED`）+ `TB-TRADE-2`（撤单结果）+ `TB-TRADE-7`（`flow_type='CANCELLED'`）+ `TB-TRADE-5`（可下单量回退） | 撤单 |
| `FR-TRADE-8` | `TB-TRADE-3`（成交）+ `TB-TRADE-1/2`（关联回溯） | 成交查询（只读） |
| `FR-TRADE-14` | `TB-TRADE-2/3/5/6/7`（事件流落库目标表）+ `TB-RISK-3`（水位） | 事件流落库 |
| `FR-RISK-1` | `TB-PRODUCT-5`（白名单）+ `TB-PRODUCT-3`（合约归属）+ `TB-TRADE-6`（拒绝记录） | 白名单校验 |
| `FR-RISK-2` | `TB-PRODUCT-6`（层①③）+ **`TB-PRODUCT-8`（层②）** + `TB-TRADE-6`（含 `limit_layer`） | **三层限额校验** |
| `FR-RISK-3` | `TB-PRODUCT-3`（合约）+ `TB-TRADE-5`（持仓）+ `TB-TRADE-6` | 持仓上限校验 |
| `FR-RISK-4` | **`TB-RISK-11`（分子分母）** + `TB-SUB_ACCOUNT.category` / `TB-PRODUCT-5.category_override`（分类） + `TB-TRADE-6` | 规模红线校验 |
| `FR-RISK-5` | `TB-SPOT-8`（**三分量 + `snapshot_time`**） + `TB-TRADE-5`（净持仓） + `TB-TRADE-6` | 时点净持仓校验 |
| `FR-RISK-6` | `TB-PLAN-1`（`term_*`） + `TB-SPOT-1/2`（合同到期日） + `TB-TRADE-6` | 期限匹配校验 |
| `FR-RISK-7` | `TB-AUTH-7`（授权） + `TB-AUTH-1`（`is_enterprise_leader`） + `TB-TRADE-6` | 权限与授权校验 |
| `FR-RISK-8` | `TB-TRADE-6`（`current_value`/`threshold_value`） + `TB-RISK-1`（阈值） + `TB-RISK-4`（`0x1018` 告警） | 价格偏离校验（两档） |
| `FR-SETTLE-13` | **`TB-SETTLE-13`（核对任务）** + **`TB-SETTLE-15`（签署记录）** | 月度三方核对（v1.1 升级） |
| `FR-SETTLE-8` | **`TB-SETTLE-11`（合并数/差额）** + **`TB-SETTLE-16`（差异候选归属，v1.2）** + `TB-SETTLE-10`（定位态差异） | **加总对账 + 差异归属机判**（`Q-11` 阻断范围取数，`DC-021`） |
| `FR-SETTLE-14` | **`TB-SETTLE-13`**（季度 + `MANAGE` 签收） + **`TB-SETTLE-15`** | 季度报告 |
| `FR-SETTLE-17` | **`TB-SETTLE-13`** + **`TB-SETTLE-15`**（`uk_check_signature_task_role`） | 核对任务与电子签署 |
| `FR-AUDIT-3` | `TB-AUDIT-6`（`chain_integrity`/`break_list`） + `TB-AUDIT-7` | 链条完整性校验 |
| `FR-AUDIT-13` | **`TB-SETTLE-13`** + **`TB-SETTLE-15`**（`AUDIT` 侧**只读**，`R-AUDIT-11`） | 查询核对任务与签署记录 |
| `FR-AUTH-1` | `TB-AUTH-1`（`password_hash`/`must_change_password`/`is_enterprise_leader`） | 新增用户 |
| `FR-AUTH-2` | `TB-AUTH-1`（`natural_person_key` 唯一约束） + `TB-AUTH-12` | 修改用户 + 留痕 |
| `FR-AUTH-4` | `TB-AUTH-1`（`status`）+ `TB-AUTH-11`（强制重置口令） | 启用用户 |
| `FR-AUTH-5` | `TB-AUTH-1/2/4/7/8/10`（`last_login_time`） | 用户列表聚合多表 |
| `FR-AUTH-6` | `TB-AUTH-2`（`is_preset`）+ `TB-AUTH-3`（功能权限） | 角色定义 |
| `FR-AUTH-8` | `TB-AUTH-6`（互斥矩阵）+ `TB-AUTH-13`（越权尝试）+ `TB-AUTH-12` | 岗位分离校验 |
| `FR-AUTH-14` | `TB-AUTH-1`（`fail_count`/`locked_until`）+ `TB-AUTH-10`（登录日志，**无口令字段**） | 登录认证 |
| `FR-AUTH-15` | `TB-AUTH-9`（`idle_timeout_minutes`/`status='KICKED'`） | 会话管理 |
| `FR-AUTH-16` | `TB-AUTH-1`（`password_hash` 加盐哈希）+ `TB-AUTH-11`（历史） | 口令管理 |
| `FR-CONFIG-1` | `TB-CONFIG-7`（非敏感 CTP 参数）+ `TB-CONFIG-2`（`is_sensitive='Y'` 项**只存状态**） | CTP 连接配置（**敏感字段零落盘**） |
| `FR-CONFIG-2` | `TB-CONFIG-2`（`is_sensitive` / `sensitive_state`，**结构上无存取值能力**） | 敏感字段内存持有 |
| `FR-CONFIG-3` | `TB-CONFIG-2`（`persist_scope='LOCAL_ADMIN'`） | 本地 admin 持久化 |
| `FR-CONFIG-4` | `TB-CONFIG-2`（`persist_scope='API_DISPATCH'`，**不落库**） | 下发配置不落盘 |
| `FR-CONFIG-9` | `TB-CONFIG-2`（`hg.tcp.bind` 白名单仅 `127.0.0.1`） | 组件通信安全配置 |
| `FR-CONFIG-10` | `TB-CONFIG-7`（`priority` 主/备前置切换） | 行情/交易前置 |
| `FR-CONFIG-13` | `TB-CONFIG-2/4/5`（导入导出范围；**敏感字段系统级排除**） | 参数导入导出 |
| `FR-FUND-15` | `TB-FUND-1`（`margin_occupied`/`available_fund`/`risk_ratio`） | 保证金占用视图 |

> **自检结论**：**184 条 FR 均有库表承载**（§2 各表「用途」列 + 本节补全）；
> 其中**事前风控类 FR（`FR-RISK-1`~`8`、`FR-TRADE-3`）**的**判定执行在 C++ 内存**（无表），
> 但**其输入数据来源、结果与拒绝记录均有库表承载**——这是"全内存判定 + 单一写库入口"的必然结构，
> **不是遗漏**（`ARCHITECTURE.md` §4.4 已声明权威源划分）。

---

## 5. 数据流向

> **合格标准**：说清「数据从哪来、谁写、谁读、生命周期如何」。这一节是排查数据问题的依据。

### 5.1 逐表的数据流向（按模块分组）

| 表（代表） | 谁写入 | 谁读取 | 写入时机 | 生命周期 |
|-----------|--------|--------|---------|---------|
| `t_sub_account` | `CONFIG`（系统管理员，双人复核） | 全部模块（维度过滤） | 用户操作 | **永久**（不可删除，只停用） |
| `t_config_item` / `t_config_change_log` | `CONFIG` | 全部模块；审计 | 用户操作 / 下发回执 | 永久 |
| `t_dict_item` / `t_account_subject` | `CONFIG` | 各模块界面；`ACCT` | 用户操作 | 永久（被引用不可删） |
| `t_user` / `t_role` / `t_*perm*` | `AUTH` | 全部模块（鉴权） | 用户操作（双人复核） | 永久（不可删） |
| `t_login_session` | `AUTH` | HTTP 过滤器 | 登录 / 请求 | **短期**（可物理删除；空闲 30 min 过期） |
| `t_login_log` / `t_unauthorized_attempt` | `AUTH` | 审计 | 事件驱动 | 永久 |
| `t_product*` | `PRODUCT` | `SPOT`/`PLAN`/`RISK`/`TRADE`/`AUDIT` | 用户操作 | 永久 |
| `t_sub_instrument_quota`（层②，v1.1） | `PRODUCT` / `CONFIG` | **`RISK`（下发给 C++）** | 用户操作 → 触发下发 | 永久（变更留痕） |
| `t_annual_scale`（规模基数，v1.1） | `SPOT`（分母：品种+年度维护）/ `TRADE`（分子：成交增量） | **`RISK`（下发给 C++）** | 用户维护 + 成交回报增量 | 永久 |
| `t_check_task` / `t_check_signature`（v1.1） | `SETTLE`（调度生成 + 用户签署） | `AUDIT`（**只读**）、管理层 | **周期调度 + 用户签署** | 永久（**不可改删**，`R-SETTLE-11`） |
| `t_sub_product_whitelist` / `t_product_quota` | `PRODUCT` | **`RISK`（下发给 C++）** | 用户操作 → 触发下发 | 永久（变更留痕） |
| `t_purchase_contract` / `t_sales_contract` / `t_production_plan*` / `t_wip_record` / `t_inventory_ledger` | `SPOT` | `SPOT`（敞口计算）、`PLAN`、`AUDIT` | 用户操作 | 永久（作废保留） |
| `t_spot_price` | `SPOT`（外部录入：手工/粘贴/CSV） | `PLAN`（比率计算）、`ACCT`（实货盈亏）、`AUDIT` | **用户录入 / 文件导入**（**非系统抓取**） | 永久 |
| `t_exposure` / `t_exposure_detail` | `SPOT`（**由现货数据计算**） | `PLAN`（计划绑定）、`RISK`（下发给 C++）、`ACCT`、`AUDIT` | 用户触发计算 / 单据变更触发重算 | 永久 |
| `t_hedge_plan` / `t_hedge_plan_exposure` / `t_hedge_ratio_calc` / `t_hedge_doc` / `t_plan_approval` / `t_trade_plan` / `t_plan_state_log` / **`t_custom_hedge_rule`** | `PLAN` | `TRADE`、`RISK`（额度下发）、`ACCT`、`AUDIT` | 用户操作 / 审批 / 成交回写 | 永久 |
| `t_trade_order` / `t_trade_order_report` / `t_trade_fill` / `t_trade_reason` / `t_command_flow` / `t_pre_trade_reject` / `t_option_strategy` | **`TRADE`（由 C++ 事件流驱动）** | `SETTLE`、`RISK`、`ACCT`、`AUDIT` | **事件驱动**（C++ 产生 → 事件流 → Java 落库） | 永久（**不可改删**） |
| `t_position` | `TRADE`（`EVT_POSITION_CHANGE` 增量） | `TRADE`（可用持仓）、`SETTLE`（结算拆分）、`RISK`、`FUND` | **事件驱动** | 当前态（历史在 `t_settle_position`） |
| `t_replay_log` | `TRADE`（`EVT_REPLAY_PROGRESS`） | 运维、审计 | **组件启动 / 手工触发** | 永久 |
| `t_ctp_settle_*` | `SETTLE`（**CTP 日终文件/接口导入**） | `SETTLE`（核对基准） | **日终批量** | 永久（外部基准，只读） |
| `t_settle_cycle` / `t_settle_position` / `t_settle_fund` / `t_settle_pnl` | `SETTLE`（**日终批量计算**） | `FUND`、`ACCT`、`RISK`、`AUDIT` | **日终批量**（调度触发） | 永久（作废重算保留原单） |
| `t_reconcile_record` / `t_reconcile_difference` / `t_aggregation_check` / **`t_agg_diff_candidate`** | `SETTLE`（结算岗 + 系统） | `SETTLE`（阻断判定）、`FUND`、`AUDIT` | **日终批量 + 用户处理** | 永久 |
| `t_settle_report` / `t_check_task` / `t_stress_test` | `SETTLE`（调度 + 用户） | 管理层、`AUDIT` | **日/月/季调度** | 永久 |
| `t_risk_threshold` / `t_param_dispatch` | `RISK` / `CONFIG`（系统管理员） | **`RISK`（下发 C++）**、运维 | 用户操作 → 触发下发 | 永久（变更留痕） |
| `t_mirror_watermark` | `RISK`（镜像消费线程） | `RISK`（滞后告警）、运维 | **事件驱动** | 当前态（滚动） |
| `t_risk_alert` / `t_risk_disposal` / `t_abnormal_order_stat` / `t_risk_metric_snapshot` | `RISK`（事中风控） | `RISK`、`AUDIT`、管理层 | **事件驱动 / 周期快照** | 快照类保留 90 天（可配置），其余永久 |
| `t_post_risk_report` / `t_risk_violation` / `t_limit_usage_stat` | `RISK`（事后风控） | `SETTLE`（日报）、`AUDIT` | **日终批量** | 永久 |
| `t_operation_log` / `t_audit_log` / `t_audit_log_checksum` | 全部模块（统一留痕组件）+ `AUDIT` | `AUDIT`、内外部审计 | **每次关键操作 / 每次审计动作** | 永久（**不可改删**） |
| `t_violation_screen_run` / `t_violation_screen_item` / `t_audit_report` / `t_evidence_export` / `t_major_event` | `AUDIT` | 审计岗、管理层、监管 | **用户操作 / 自动触发** | 永久 |
| `t_hedge_relation` / `t_effectiveness_assess` / `t_rebalance_record` / `t_voucher` / `t_voucher_line` / `t_voucher_review` / `t_hedge_effect` / `t_dual_pnl_report` / `t_cas24_selfcheck*` | `ACCT` | `AUDIT`、`SETTLE`（有效性反馈）、管理层 | **期间调度 + 用户操作** | 永久（冲销保留原凭证） |
| `t_accounting_period` / `t_period_close_log` | `ACCT`（财务岗，双人复核） | `ACCT` | 用户操作 | 永久 |
| `t_fund_ledger` / `t_fund_flow` | `FUND`（**事件驱动 + 批量结转**） | `SETTLE`（**共享台账**）、`ACCT`、`AUDIT` | **事件驱动 / 日终结转** | 永久（**不可改删**） |
| `t_deposit_withdraw_apply` / `t_capital_receipt` | `FUND`（用户发起 + C++ 回执事件） | `FUND`、`SETTLE`、`AUDIT` | 用户操作 → CTP 异步回执 | 永久 |
| `t_transfer_apply` / `t_fund_review_record` | `FUND`（资金岗 + 审批人） | `FUND`、`SETTLE`、`AUDIT` | 用户操作 | 永久 |
| `t_fund_quota` / `t_fund_quota_alert` | `FUND` | `FUND`、`RISK` | 用户操作 / 事件驱动 | 永久 |
| `t_fund_reconcile` | `FUND`（日终） | `SETTLE`（勾稽）、`AUDIT` | **日终批量** | 永久 |

### 5.2 数据源说明

| 数据 | 来源 | 获取方式 |
|------|------|---------|
| **现货价** | **企业外部输入** | **手工录入 / 批量粘贴 / CSV 导入**；**系统不抓取、不订阅任何第三方资讯源**（`R-SPOT-6`、`NFR-COMPAT-7`） |
| **期货行情** | **CTP 柜台** | C++ 侧 `ctp_md_spi` 接收 → 通道 B（WebSocket）推 Java → 前端；**不落库** |
| **委托 / 成交 / 持仓** | **CTP 柜台** | C++ 侧 `ctp_trade_spi` 接收 → 事件流 → Java 落库（**单一写库入口**） |
| **CTP 日终结算数据** | **期货公司** | 日终文件 / 接口导入（`t_ctp_settle_batch` + 三类明细）；**权威基准，只读** |
| **资金划转回执** | **CTP / 期货公司** | `EVT_FUND_RECEIPT` → `t_capital_receipt`；**唯一到账依据** |
| **实货敞口** | **系统由现货数据计算** | `t_purchase_contract` / `t_sales_contract` / `t_production_plan*` / `t_wip_record` / `t_inventory_ledger` → 按口径计算 → `t_exposure` + `t_exposure_detail` |
| **事前风控判定结果** | **C++ 内存** | 拒绝记录经 `EVT_PRE_TRADE_REJECT` → `t_pre_trade_reject`；**归因于 C++，Java 只落库** |
| **会计科目** | **企业提供** | `CONFIG` 科目字典（`t_account_subject`）；**不得硬编码** |
| **口令 / AuthCode / 资金密码** | **用户启动手工录入** | **仅内存持有，零落盘**——**本模型不存在任何相关表或列**（`NFR-SEC-1`） |

### 5.3 数据生命周期与归档

| 类别 | 保留策略 | 归档方式 | 依据 |
|------|---------|---------|------|
| 交易 / 结算 / 凭证 / 审计类 | **永久保留**（不删除） | 无需归档；量大表可分区 | `R-TRADE-15`、`R-SETTLE-10`、`R-ACCT-5`、`R-AUDIT-3` |
| 资金流水 | **永久保留** | 同上 | `R-FUND-2` |
| 风险指标快照（`t_risk_metric_snapshot`） | 日终快照永久；周期快照 90 天（`HG.RISK.METRIC_RETAIN_DAYS`） | **移到历史表**（**不得删除**） | 内部策略 |
| 登录会话（`t_login_session`） | 过期即清理（**允许物理删除**） | 直接删除 | 会话非业务数据 |
| 指令流水（C++ 侧文件） | `HG.FLOW.RETAIN_DAYS`（默认 90 天） | 归档到冷目录 | `ARCHITECTURE.md` §4.5.2 |
| **加密/脱敏** | 敏感字段**从不落库**；口令**加盐哈希**；日志**强制脱敏** | — | `NFR-SEC-1`、`NFR-SEC-2` |

---

## 6. 数据库特性依赖

> ⚠️ **必填**：`PROJECT.md` §5.2 声明了「数据库可更换性」，本节逐条列出对当前数据库
> （**达梦 DM8**）特有能力的依赖，供换库时定位。

### 6.1 DM8 特有语法/类型清单

| # | 依赖项 | 用到的 DM8 特性 | 是否标准 SQL | 换库时的处理 | 出现位置 |
|---|--------|---------------|-------------|------------|---------|
| **D1** | **自增主键** | `BIGINT IDENTITY(1,1)` / `INT IDENTITY(1,1)` | ❌ 非标准 | 改 `AUTO_INCREMENT`（MySQL）/ `GENERATED ALWAYS AS IDENTITY`（PG/Oracle 12c+）/ `SERIAL`；**或统一改用序列 + 应用层取号** | **全部 111 张表的主键** |
| **D2** | **审计时间默认值** | `DEFAULT CURRENT_TIMESTAMP` | ⚠️ 半标准（SQL:2003 有 `CURRENT_TIMESTAMP`，但作为列默认值的写法各家不同） | PG/Oracle 可沿用；MySQL 需 `DEFAULT CURRENT_TIMESTAMP`（5.6+ 支持）；**建议统一改由应用层写入**以彻底解耦 | 全部表的 `created_time` |
| **D3** | **分页语法** | `SELECT ... LIMIT ? OFFSET ?`（DM 兼容 MySQL 风格） | ❌ **非标准**（SQL:2008 标准为 `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY`） | 统一改写为 `OFFSET ... FETCH NEXT`（PG/Oracle/SQL Server 支持）或 `ROWNUM`（Oracle 11g） | `dao/dialect/DmDialect.java`（**唯一集中点**） |
| **D4** | **日期函数** | `SYSDATE`、`TO_DATE`、`TRUNC`、`ADD_MONTHS`、`MONTHS_BETWEEN` | ❌ 非标准（Oracle 风格） | `SYSDATE`→`CURRENT_TIMESTAMP`/`NOW()`；`TO_DATE`→`TO_TIMESTAMP`/`STR_TO_DATE`；`ADD_MONTHS`→`INTERVAL` 运算；`TRUNC(date)`→`DATE_TRUNC` | `DmDialect.java`；SQL 注释 `// DM-SPECIFIC: 日期函数` |
| **D5** | **字符串拼接** | `||` | ✅ 标准（SQL:2008） | 无需改写（MySQL 需 `CONCAT`，可在方言层适配） | 各 DAO 的拼接查询 |
| **D6** | **条件唯一索引** | `CREATE UNIQUE INDEX ... WHERE <条件>`（部分索引 / 过滤索引） | ❌ 非标准 | PG 支持（`WHERE`）；MySQL **不支持** → 需改为"**可空列的复合唯一**"技巧（MySQL 唯一索忽略 `NULL`）或应用层保证；Oracle 需用函数索引 + 联合技巧 | `uk_product_spot_primary`（`is_primary='Y'`）、`uk_hedge_doc_current`（`is_current='Y'`） |
| **D7** | **CLOB 类型** | `CLOB` | ⚠️ 半标准（SQL 标准为 `CLOB`，但各家名称与行为有差异） | MySQL→`LONGTEXT`；PG→`TEXT`；Oracle→`CLOB`；**JDBC 读写需改用 `getCharacterStream` / `setCharacterStream`** | 12 张表含 `CLOB` 列（`t_hedge_doc`、`t_voucher.content_json` 等） |
| **D8** | **`DECIMAL(p,s)`** | `DECIMAL(20,4)` / `DECIMAL(20,6)` / `DECIMAL(9,6)` | ✅ 标准 | 无需改写（MySQL 注意上限 65 位精度，本模型最高 20 位，安全） | 金额/价格/数量/比例列 |
| **D9** | **`CHECK` 约束** | 支持 `CHECK`（含多列布尔表达式、`IN`、`IS NULL` 组合） | ✅ 标准（SQL-92） | 均支持；**MySQL 8.0.16 之前忽略 `CHECK`** → 需确认目标库版本，或改由应用层校验 | 全部枚举与不变量约束 |
| **D10** | **表/列注释** | `COMMENT ON TABLE / COLUMN` | ⚠️ 半标准 | MySQL 用 `COMMENT '...'` 内联；PG 用 `COMMENT ON`；**建议以建表脚本 + 本文件双轨维护** | 建表脚本 |
| **D11** | **标识符大小写** | DM 的 `CASE_SENSITIVE` 参数决定（默认区分时，小写标识符需加引号） | ❌ 非标准（各家行为不一） | **建库时须设置"标识符大小写不敏感"**（部署文档前置条件）；若目标库强制小写（PG），则全库一致即可 | 部署配置 |
| **D12** | **`FETCH FIRST n ROWS ONLY`** | 支持 | ✅ 标准 | 无需改写 | 备用分页写法 |
| **D13** | **分区表** | DM 支持范围分区（可选手段） | ❌ 非标准语法 | 各库分区语法差异大 → **本期不强制分区**；量级达标时按 `operation_time` / `trade_date` 分区（换库时须重写 DDL） | `t_operation_log`、`t_trade_fill`、`t_command_flow`、`t_risk_metric_snapshot` |
| **D14** | **视图** | `CREATE VIEW` | ✅ 标准 | 无需改写（`v_position_summary_by_sub`） |
| **D15** | **驱动类名 / URL** | `dm.jdbc.driver.DmDriver`；`jdbc:dm://host:port` | ❌ 非标准 | 换驱动类名与 URL 前缀；**须同步 `deploy/` 环境依赖清单**（`NFR-DEPLOY-5`） | `HG.DB.*` 配置项 |
| **D16** | **唯一索引中的 `NULL` 语义 + 作用域哨兵值 `CHECK`（v1.2 `G-5`；v1.3 `G-11` 补具名 `CHECK`）** | 原设计依赖"含 `NULL` 的唯一约束仍保证唯一"；**本版已弃用该依赖**，改为**作用域哨兵值 `0` + 非空范围列**（`DC-020`），唯一键**不含可空列**；**v1.3 起**由 §4.3 具名 `CHECK`（`ck_product_quota_layer_sentinel` / `ck_annual_scale_scope_sentinel` / `ck_risk_threshold_scope_sentinel`）在**数据库级**保证 `0 ⇔ 账户级` 双向联动 → **`D16` 实机脚本前件成立** | ⚠️ 各库对 `NULL` 在 `UNIQUE` 中的处理一致（标准为"互不相等"），但**本模型不再依赖该行为** | **无需改写**（哨兵值方案跨库确定；`CHECK` 为标准语法） | `uk_product_quota` / `uk_annual_scale` / `uk_risk_threshold_key_scope` / `uk_check_task_period` |

> ⚠️ **需实机验证点（M1 内闭环，`G-5`）**：在 DM8 实机执行以下 1 组用例，确认**哨兵值方案**行为符合预期
> （即"账户级行不可重复插入"）：
> ① 向 `t_product_quota` 插入两条 `(limit_layer='ACCOUNT_PRODUCT', sub_account_id=0, product_id=P, effective_from=D)`
> → **第二条必须被 `uk_product_quota` 拒绝**；
> ② 同法验证 `t_annual_scale`（`scope_type='TOTAL_ACCOUNT'`）、`t_risk_threshold`（`scope_type='GLOBAL'`）、
> `t_check_task`（`sub_account_id=0`）。
> **判据**：四条 `INSERT` 的第二条均返回唯一约束冲突 → **通过**；任一未拒绝 → **不通过**，须回到本表评估。
> （本版**已不依赖 `D9` 的 `NULL` 唯一语义**，故该验证项失败不会导致模型返工，仅需确认 `CHECK` 与唯一索引生效。）
> **前件（`G-11`，v1.3）**：本脚本的前提是"账户级行以哨兵 `0` 写入"合法 —— 该前提已由 §4.3 **具名 `CHECK`**（`ck_product_quota_layer_sentinel` 等）在数据库级保证，**前件成立**。
> **追加判据（`G-11`）**：向 `t_product_quota` 插入 `(limit_layer='SUB_PRODUCT', sub_account_id=0, ...)` → **必被 `ck_product_quota_layer_sentinel` 拒绝**（层①行不得用哨兵 `0`）；同法验证 `t_annual_scale`/`t_risk_threshold` 的"行级行 `=0`"被拒。

**汇总**：

| 分类 | 条数 | 说明 |
|------|------|------|
| **必须改写**（❌ 非标准） | **8 条**（D1、D3、D4、D6、D11、D13、D15 及 D2 的建议项） | 集中在 `DmDialect.java` + DDL 脚本 + 部署配置 |
| **半标准**（⚠️） | 4 条（D2、D7、D10、D5） | 需轻微适配 |
| **标准**（✅） | 3 条（D8、D9、D12、D14 中的标准项） | 无需改写 |

### 6.2 换库工作量评估

| 位置 | 工作量 | 说明 |
|------|--------|------|
| **DDL 脚本** | **中**（111 张表） | `D1`（自增）、`D6`（条件唯一索引，**MySQL 最麻烦**）、`D7`（CLOB 类型名）、`D10`（注释语法）、`D13`（分区）需逐表改；可用脚本生成 |
| **`DmDialect.java`** | **小**（1 个文件） | `D3`（分页）、`D4`（日期函数）、`D5`（拼接）集中在此；**这是分级设计回报最大的地方** |
| **DAO 层 SQL** | **小-中** | 因"SQL 只在 `dao`"（`E5` 校验）+ "DM 特性集中在 `DmDialect`"，改写量可控；`// DM-SPECIFIC:` 注释用于定位 |
| **JDBC 驱动** | **小** | 替换 jar + 驱动类名 + URL（`HG.DB.*`） |
| **LOB 读写** | **小-中** | `CLOB` 的 JDBC 读写若用了 DM 特有方法，需改为标准 `getCharacterStream`（**要求实现时即使用标准 API**） |
| **数据迁移** | **独立工作量** | 用 DM 导出工具 + 目标库导入工具；**因无存储过程/触发器，迁移无逻辑依赖** |
| **业务代码** | **零** | 业务逻辑全在 Java 层；无存储过程/触发器/自定义函数 → **业务代码一行不改** |

**评估结论**：本模型对 DM8 的依赖**全部集中在 DDL 与 `DmDialect` 两处**，
**业务逻辑与业务代码零依赖**。这是"DAO 是唯一接触 SQL 的地方"（`NFR-MAINT-3`）+
"DM 特性集中封装"（`NFR-COMPAT-5`）两条纪律的直接回报。

### 6.3 红线自检（`PROJECT.md` §5.2 / §6）

- [x] **未使用存储过程** —— 本模型全部 111 张表**仅用表、索引、约束、视图**；`DDL` 脚本中**无 `CREATE PROCEDURE`**
- [x] **未使用触发器** —— **无 `CREATE TRIGGER`**；所有"自动更新"均由**应用层**在同一事务内完成
- [x] **未使用自定义函数** —— **无 `CREATE FUNCTION`**；所有计算（敞口、比率、盈亏、加总）均由 **Java 服务层**完成
- [x] **业务逻辑均在应用层，未下沉到数据库** —— 比对：敞口计算、套保比率、红线校验、加总对账、盈亏与凭证生成、追溯链遍历、违规筛查**全部在 Java 层**（见 `ARCHITECTURE.md` §3.8）

> **唯一"未下沉"的例外**（诚实声明）：跨行聚合约束（**借贷平衡**，§4.4）本可用触发器保证，
> 但**触发器被永久禁止** → 改为**应用层同事务校验**。这是"数据库可更换性"这一红线**主动选择的代价**，
> 已在 §4.4 / §4.5 显式登记，并须由**测试经理在 M1 会签时确认其可测性**。

---

## 7. 变更记录

> 表结构与字段的变更须在此登记。**变更前须走变更流程（`CH-xxx`）**。
> 字段级变更编号规则：**`DC-<序号>`**（3 位，从 `001` 起，**一经分配不得复用**）。

### 7.1 版本变更记录

| 版本 | 日期 | 变更内容 | 变更编号 | 原因 | 影响面 |
|------|------|---------|---------|------|--------|
| v1 | 2026-09-20 | 初始模型：**106 张表**（11 模块）、**约 360 个具名索引**、**五类数据 53 张表子账户贯穿逐表核对**、DM8 特性依赖 15 条 | — | S2 架构阶段产出 | 全库（新建） |
| **v1.1** | 2026-09-20 | **传导 `CH-001` / `CH-002`**：**新增 3 张表**（`TB-PRODUCT-8` 限额层②、`TB-RISK-11` 规模基数、`TB-SETTLE-15` 核对签署记录）+ **修订 14 张表**（详见 §7.2 `DC-001`～`DC-014`）→ **表数 106 → 109**，具名索引 **约 360 → 385**；子账户贯穿核对由 53 张 → **56 张五类表 + 2 张流程表** | **源自 `CH-001` / `CH-002`（L2 需求级，用户已确认）** | `Q-1`～`Q-17` 全部确认口径落地为库表结构；**新增 2 条 FR（`FR-SETTLE-17`、`FR-AUDIT-13`）与 2 条规则** | **需数据迁移**（v1.1 是 M1 编码前的定稿修订，**尚无生产数据**，故不涉及历史数据迁移；建表脚本按 v1.1 一次性生成） |
| **v1.3** | 2026-09-20 | **M1 数据模型会签第二轮整改**（测试经理 `B-3`/`G-7`~`G-10`/`S-6`~`S-9`；产品经理 `PI-15`~`PI-20`）：<br>① **`B-3`/`PI-15` 阻塞项**：`t_spot_change_log.change_type` 列级 `CHECK` 补 **`COMPONENT_RECALC`**（`DC-033`）；<br>② §3.1 枚举总表同步（`related_type` 8 值、`limit_layer` 5 值、非限额类拒绝统一 `NONE`，`DC-034`）；<br>③ `round_trip_count` 改 `NOT NULL DEFAULT 0`（`DC-035`）；<br>④ **「可加总」口径拆名**（`t_spot_price` 归①但单列不加总 → 五类① **44**、可加总 **43**；§4.6.1 逐表✅恰 43 行）；<br>⑤ **取一算法四处统一**为 `ratio_cap` 升序→`priority` 升序→`id` 升序；<br>⑥ §0.6 删重复行、§5 旧表名 `t_compliance_check`→`t_check_task`、`TB-SPOT-7` 清除 `PER_SUB_ACCOUNT` 残留；<br>⑦ **唯一索引计数 94→93**（合计 391→390）并写死计数口径；`uk_spot_price`→`uk_spot_price_global`；`DC-021`/`DC-025` 笔误订正；<br>⑧ 补**快照字段集定义**（`C-3`/`S-8`，`ARCHITECTURE.md` §6.14① 同步）；`#13` 补占用率权威/协议构造纪律（`C-4`/`S-9`，`DC-036`）；<br>⑨ **作用域哨兵值 `CHECK` 落为具名 DDL（共 5 条：双向联动型 3 + 范围标注型 2）** + **A/B 术语分离**（`G-11`，`DC-037`） | **源自** `docs/gates/M1-data-model-signoff/test-manager.md`（R1～R8）与 `product-manager.md`（`PI-15`～`PI-20`）第二轮复核 + 编排者裁决 | **表数维持 111**；**无架构变更（仅 1 列可空性 + 1 列级 `CHECK` + 5 具名 `CHECK`〔双向联动型 3 + 范围标注型 2〕）**；**无生产数据，不涉及历史数据迁移**（按 v1.3 一次性生成建表脚本） |
| **v1.3.6** | 2026-10-06 | **`CH-021` 两处 CHECK 取值域扩充（冻结 CHECK 域承载补齐）**：<br>① `TB-SETTLE-1 t_ctp_settle_batch.parse_status` 由 3 值（`OK`/`PARTIAL`/`FAILED`）→ **4 值**，补 **`NOT_ARRIVED`**（**数据未到达**〔未发生解析〕，置待重试；原服务侧以 `PENDING_DATA` 表达该态被冻结 CHECK 拒，`TE-T058-001b`）；<br>② `TB-RISK-2 t_param_dispatch.dispatch_type` 由 8 值 → **11 值**，补 **`SET_READY_STATE`**/**`TRIGGER_REPLAY`**/**`RELOAD`**（对应线格式 `0x0119`/`0x011A`/`0x011B`，`ARCHITECTURE.md` §4.2.3；三类去 `CMD_` 前缀后仍越域，`TE-T058-002`） | **源自 `CH-021`（L3 冻结域变更；用户已批准 2026-10-06；part 1 新值定名 `NOT_ARRIVED`）；字段级登记 `DC-042`（part 1）/ `DC-043`（part 2）** | 冻结 CHECK 域对既有 FR 承载不足：`FR-SETTLE-1`（三态含「未到达」承载）/ `FR-RISK-10`（输入含重载/触发重放）；技术席裁定书 `docs/DECISIONS/T-182-frozen-check-domain-gaps.md` | **枚举取值域扩展 2 处（改列级 `CHECK`）**：**表数维持 111**；**表 / 列 / 索引 0 变更**；**无生产数据，不涉及历史数据迁移**；若已建库须由 DBA 按 `ALTER`（DROP/ADD CONSTRAINT）重建（回滚亦同） |
| **v1.3.5** | 2026-09-21 | **抬头「需求基线」`FR` 计数传导（`183 → 184`）**：`docs/REQUIREMENTS/SPOT.md` 经 `CH-011`（**L2**，用户已确认 2026-09-21）**新增 `FR-SPOT-19`（导出现货价格序列）** → 需求侧 `FR` 总数 `183 → 184`；本文件**抬头「需求基线」行 + §4.7 说明 + §4.7 自检结论**共 **3 处**计数同步为 **184** | **源自 `CH-011`（L2 需求级，用户已确认）** | `CH-011` 为已交付端点 `GET /spot/price/export` 补需求承载（消除 `TE-T109-007`「产出面 > 需求面」缺口）；`FR-SPOT-19` 的库表承载复用既有 `TB-SPOT-7`（与 `FR-SPOT-13` 同源），**模型侧无需新增表 / 列** | **非结构变更**：**表数维持 111**；**表 / 列 / 索引 / `CHECK` / 枚举 0 变更**；`FR` 计数属**需求基线传导值** |
| **v1.3.4** | 2026-09-21 | **`TB-PLAN-1 t_hedge_plan.term_end_date` 的期限口径勘误（纠正 `OR` → `min`）**：该列备注原写「`≤ term_start_date + 12 个月`（**或** ≤ 实货合同期限）」——与 `R-PLAN-3` ④ / `Q-4` 的「**取较早者 = `min`(建仓日 + 12 个月, 合同到期日 + 30 天宽限)**」**松紧方向相反**（`OR` 会让"实货合同期长于 12 个月"的计划通过，而需求要求按 12 个月卡）。已订正为 `min` 形式并保留原表述为**勘误留痕**。**动因**：该列是**期限红线（`R-PLAN-3` ④）的取数依据**，而 ④ 已列入 M1 服务端强制清单（`ARCHITECTURE.md` §6.4「过渡口径」块，`v1.5.7`）⇒ **依据错则服务端强制必错** | **—（无结构变更、不新增 `DC`；属**列语义口径订正**）** | `CR-002` 裁定的连带项：`FR-PLAN-10` / `R-PLAN-3` 的④项在 M1 由服务端强制，其取数依据须与需求一致；`S7` 首审未发现，由技术负责人在裁定中复核发现 | **表数维持 111**；**列结构 0 变更、索引 0 变更**（仅 `term_end_date` 的**备注口径**订正）；**无生产数据，不涉及历史数据迁移** |
| **v1.3.3** | 2026-09-20 | **`t_exposure` 未映射口径改为哨兵值（解除唯一键对 `NULL` 的依赖）**：<br>① `product_id` 由**条件非空（可空 `NULL`）**改为**恒 `NOT NULL`**，未映射行取**未映射哨兵值 `0`**（外键指向 `t_product` **保留系统行 `id=0`**，见 `TB-PRODUCT-1` 备注）；<br>② 具名 `CHECK` **`ck_exposure_product_unmapped`** 表达式由 `IS NULL`/`IS NOT NULL` 改为 **`product_id = 0` / `product_id > 0`**；<br>③ `t_exposure` 补列 **`spot_category_code`（`VARCHAR(32) NOT NULL`）**，**双态取值**（未映射行＝**真实品类**；已映射行＝**固定占位常量 `'__MAPPED__'`**，不随来源品类变化），并新增具名 `CHECK` **`ck_exposure_spot_category_scope`** 强制该双态 —— 使「**品类维度仅在未映射行起区分作用**」成为**数据库级事实**（`CH-008` / `FR-SPOT-14` 验收 1、2）；<br>④ `uk_exposure_key` 由 `sub_account_id, product_id, direction, calc_mode, as_of_date` 改为 **`sub_account_id, product_id, spot_category_code, direction, calc_mode, as_of_date`**（**全列非空 → 唯一性不依赖 DM8 对 `NULL` 的处理**；未映射行**按品类各占一行**、已映射行因占位恒定而**不因品类拆行**；与 `DC-020` / `G-5` 同向）；<br>⑤ §4.3「检查（业务不变量）」实例列补上述 **2 条**具名 `CHECK`；§6.1 自检清单第 16 项 `DC-` 区间同步为 `DC-001`～`DC-041` | **`DC-041`（同编号改写，不新增 `DC`）** | `DC-041` 原定"`product_id` 条件非空（可空 `NULL`）"；**同轮复核认定**：以 `NULL` 承载例外态会使 `uk_exposure_key` **含可空列**、其唯一性**依赖 DM8 对 `NULL` 在唯一索引中的处理**，与 `DC-020` / `G-5`（"唯一约束**不依赖** `NULL` 语义"）**方向相反** → 收敛为**未映射哨兵值 `0` + 恒非空列**；**未映射行粒度同轮由产品经理裁定**（`CH-008`：未映射清单**按品类分别留行**、且**品类维度仅在未映射行起区分作用**）——故已映射行的品类取值**必须恒定**，否则同一品种来自不同品类的现货数据会被拆行、与 `FR-SPOT-14` 验收 1 冲突；占位取**与品种无关的固定常量**（**不取**"该品种主品类"——`TB-PRODUCT-4` 的 `uk_product_spot_primary` 只保证"同一品类的主映射唯一"，**允许多品类映射同一品种**，故"该品种主品类"**非单值**） | `t_exposure`：**1 列改 `NOT NULL` + 新增 1 列 + 1 唯一索引改列 + 2 条具名 `CHECK`（1 条改写表达式 + 1 条新增）**；`t_product`：**新增 1 保留系统行（`id=0`）**；**索引计数不变（唯一 93 / 普通 297 / 合计 390）**、**表数维持 111**；**无生产数据，不涉及历史数据迁移** |
| **v1.3.2** | 2026-09-20 | **M1 门禁后「二次一致性订正」**（两席对 v1.3.1 的定点再验证留项 `O-1`/`PI-22`/`PI-23`，共 3 项**建议级**；编排者打包一次性收干净）：<br>① **`O-1`（= 产品席 `PI-24`，两席独立复现**同一处**）**：`DC-034` 原因列残留的**行号括注引用**改为**语义引用**（「见 §3.1 开篇『数据库 `CHECK` 约束与本节必须一致』说明」），并**全库复查**确保同类行号引用**归零**（宽匹配 `:[0-9]{3,4}[^0-9]`，排除 SQL 标准号「`SQL:2003`/`SQL:2008`」）；<br>② **`PI-22`**：§4.4 `N-2` 文本的引用指向订正——「由服务层保证」指向 **§0.2 / §4.4 本行**，「DB 层仅对 5 张作用域表保证」指向 **§0.4**（原误将前半句指向 §0.4）；<br>③ **`PI-23`**：§6.1 自检清单第 10 项口径**拆清**——`§4.4` 业务规则 **22 条** + `§4.4.1` 取数路径 **4 行**，**口径分列、不并计**（原「v1.2 共 26 条」为 22 + 4 的误并）；<br>④ 顺带同步 §6.1 第 16 项 `DC-` 区间 `DC-001`～`DC-037` → **`DC-001`～`DC-040`**（随 `DC-038`~`DC-040` 追加） | **源自** `docs/gates/M1-data-model-signoff/` 两席 v1.3.1 定点再验证（测试席 `O-1` + 产品席 `PI-22`/`PI-23`）+ 编排者裁决 | **表数维持 111**；**纯文档一致性订正（无结构变更、无迁移）** |
| **v1.3.1** | 2026-09-20 | **M1 数据模型门禁后「一致性订正」**（测试经理终局 `N-1`~`N-4` + 产品经理终局 `PI-21`，共 5 项**建议级**留项；编排者打包裁决）：<br>① **`N-1`**：v1.3 变更行「影响面」列「3 具名 `CHECK`」订正为 **5 具名 `CHECK`（双向联动型 3 + 范围标注型 2）**，与同处四处计数一致；<br>② **`N-2`**：§4.4 哨兵边界行 ② 的拒绝来源**收窄为「混合表的 A 类行」**，并补「其余五类表由服务层保证」（引用指向 §0.2 / §4.4 本行，DB 层保证指向 §0.4）；<br>③ **`N-3`**：§4.6.2 ⑦ 行标签改为「**§4.6.1 核对范围内新增的非五类携带表**」，并在注中给出「全库非五类① = 59 − 44 = 15」的推导与 11 张未列表清单；<br>④ **`N-4`**：`t_audit_report` / `t_evidence_export` 的 `chain_integrity` **补 `UNKNOWN`**，与 `t_audit_log` **统一为 4 值**，§3.1 同步（`DC-038`）；<br>⑤ **`PI-21`**：`DC-034` 的内部行号交叉引用改**语义引用**（改引 `TB-TRADE-4` 表定义 `related_type` 列 / `TB-TRADE-6` 表定义 `limit_layer` 列），并顺手订正 §4.4 对 `TB-PRODUCT-6` 备注的同类行号引用（`DC-039`） | **源自** `docs/gates/M1-data-model-signoff/test-manager.md`（`R10.7`）+ `product-manager.md`（第三轮 §七）+ 编排者 v1.3.1 打包裁决 | **表数维持 111**；**仅 1 处取值域扩展（`DC-038`：`chain_integrity` 补 `UNKNOWN`）+ 其余纯文档一致性订正**；**无生产数据，不涉及历史数据迁移** |
| **v1.2** | 2026-09-20 | **M1 数据模型会签整改（三方意见终版）**（测试经理 B-1/B-2 + G-1～G-6 + 判据 `#13/#19`；产品经理 `PI-01`～`PI-13`；编排者对 `W1`～`W5` 的裁决）：<br>① **删除失效索引** `idx_trade_reason_code` → 改建于 `related_type`（`DC-017`）；<br>② **B-2 终版（按产品经理 `CH-004`，用户批准）**：`related_plan_id`/`related_exposure_id` **改可空 + 条件非空 `CHECK`**（`ck_trade_reason_related_or_missing`），**保留 `related_object_missing_flag`**，「提交时关联对象必选」由**服务层三层校验**保证；`related_type` 增补 `FORCE_CLOSE`/`DELIVERY`；`RELATED_MISSING` 与 `REASON_MISSING` **并列保留**（`DC-024` 取代 `DC-018`/`DC-019`）；<br>③ **作用域列改哨兵值 `0`**（5 表，弃用 `NULL` 唯一语义，`DC-020`）；<br>④ **新增 2 张表**：`TB-SETTLE-16` `t_agg_diff_candidate`（差异归属机判，`DC-021`）、`TB-PLAN-8` `t_custom_hedge_rule`（自定义规则实体，`PI-02`，`DC-025`）；<br>⑤ `t_check_signature` 补"不可代签"角色级判据（`DC-022`）；`t_spot_change_log` 补外键（`DC-023`）；<br>⑥ **产品经理阻塞项**：`t_pre_trade_reject` 补 `limit_layer`（`PI-01`，`DC-027`）；`t_exposure` 补 `price_as_of`/`non_current_price_flag`/`snapshot_as_of`（`PI-03`，`DC-026`）；<br>⑦ **一般项**：`t_pre_trade_reject` 补敞口快照列（`PI-05`，`DC-028`）、`t_abnormal_order_stat` 补持仓/回合字段（`PI-04`，`DC-029`）、`t_trade_order` 补撤单原因（`PI-12`，`DC-030`）、`voucher_type` 补行权/作废（`PI-13`，`DC-031`）、`t_voucher_review` 补第二复核人（`PI-11`，`DC-032`）；<br>⑧ **`§4.6.3` 按三层重写**（① 物理列 `NOT NULL` 行级携带 / ② 可空但已说明 / ③ 非五类无该列），**禁用"不携带"含混表述**（`W1`/`W2`/`W3`/`W4`），§4.6.2/§4.6.4 统一为「可加总 43 张」；<br>⑨ **判据写死**：三层限额全序 + 并列取一（`#13`）、敞口三分量缺失处理（`#17`）、在途判定与时窗（`#19`）、不可代签（`#14`）；<br>⑩ `W5` 补**加总收敛闭环**说明；抬头 `131 R → 129 R`（`PI-10`） | **源自** `docs/gates/M1-data-model-signoff/`（`test-manager.md` + `product-manager.md`）与编排者裁决；`Q-13` 语义文案正式回写由产品经理走 `CH-004` | **表数 109 → 111**；**无生产数据，不涉及历史数据迁移**（按 v1.2 一次性生成建表脚本） |

### 7.2 字段级变更登记（`DC-<序号>`）

| 变更编号 | 日期 | 表 | 变更内容 | 原因（已确认口径） | 影响面 / 是否需迁移 |
|---------|------|----|---------|------------------|------------------|
| — | — | — | v1.0 为初始版本，无字段级变更 | — | — |
| `DC-001` | 2026-09-20 | `t_sub_account` | 新增 `category`（`COMMODITY`/`TRADE`，默认 `COMMODITY`）、`category_approved_by`；`disable_precheck_json`、`archive_export_id` | `Q-2` 分类**默认挂子账户** + **允许品种级覆盖**，变更须**风控发起 + 财务审批**；`Q-10` 停用条件 4 项 + 彻底清除只提供"导出归档" | 新增列 + `CHECK`；构建表脚本即可 |
| `DC-002` | 2026-09-20 | `t_spot_price`（备注） | 配置键由 `HG.SPOT.PRICE_SCOPE` 对齐为 **`spot.price.scope`**（架构 `CFG-18`） | 统一配置键命名，避免两套（`ARCHITECTURE.md` §11） | 仅文档；无结构变更 |
| `DC-003` | 2026-09-20 | `t_exposure` | 新增 `contract_unexecuted_qty` / `inventory_qty` / `production_plan_qty`（**三分量**）、`components_enabled`、`snapshot_id`、`snapshot_time`；新增唯一索引 `uk_exposure_snapshot` | `Q-17` **敞口快照必须存三类分量，不得只存合计**；`Q-3` 校验须标注**快照时点** | 新增 6 列 + 1 唯一索引 |
| `DC-004` | 2026-09-20 | `t_sub_product_whitelist` | 新增 `category_override`（`NULL` = 继承子账户分类） | `Q-2` **允许品种级分类覆盖** | 新增列 + `CHECK` |
| `DC-005` | 2026-09-20 | `t_product_quota` | `sub_account_id` 改 **NULL**；新增 `limit_layer ∈ {SUB_PRODUCT, ACCOUNT_PRODUCT}`；唯一索引改 `(limit_layer, sub_account_id, product_id, effective_from)`；新增 `idx_product_quota_account_product` | `Q-16` **三层限额全部保留**（本表承载层①与层③，层②在 `TB-PRODUCT-8`） | 结构变更（列可空 + 唯一键组成变更）+ 索引 |
| `DC-006` | 2026-09-20 | **新增 `t_sub_instrument_quota`（`TB-PRODUCT-8`）** | 层②（子账户 + 合约）额度表（11 列 + 4 索引） | `Q-16` **限额层②（子账户 + 合约）** | **新增表** |
| `DC-007` | 2026-09-20 | **新增 `t_annual_scale`（`TB-RISK-11`）** | 规模基数表（分子 `accumulated_hedge_qty` / 分母 `expected_spot_business_qty` / 分类与比例快照，11 列 + 4 索引） | `Q-1` 基数 = **年度现货敞口累计量（实物量）**；**子账户与全账户双算取严者**；`Q-2` 分类与比例 | **新增表** |
| `DC-008` | 2026-09-20 | `t_trade_reason` | 原 `reason_code` → **`related_type`（必填）**；新增 `related_plan_id`、`related_exposure_id`（**均必填**）、`time_desc`（必填）、`forced_by_freq_alert`、`related_object_missing_flag`；新增 2 索引 | `Q-13` 交易理由 = **结构化必填**（关联类型 + 关联对象〔须同子账户〕+ 时点说明）；`Q-5` 触发频繁开平仓时**强制填写** | 结构变更（3 列改必填 + 5 新增列）+ 索引 |
| `DC-009` | 2026-09-20 | `t_compliance_check` → **`t_check_task`（重定义）**；**新增 `t_check_signature`（`TB-SETTLE-15`）** | 核对任务：`required_sign_roles`/`signed_roles`/`status`(4 态)/`snapshot_hash`/`snapshot_json`/`deadline_date`/`overdue_alerted` 等；签署记录：`sign_role`/`signer_id`/`sign_ip`/`snapshot_hash`/`proxy_rejected`/`manage_role_trade_blocked` 等（12 列 + 5 索引，含 **`uk_check_signature_task_role`**） | `Q-14` 月度三方**电子签署**、季度管理层**签收**、年度审计全覆盖；**不可代签 / 不可重复签**（唯一约束下沉）；**管理层签署权与交易权互斥**；新增 `FR-SETTLE-17`、`FR-AUDIT-13` | 原表重定义 + **新增表** |
| `DC-010` | 2026-09-20 | `t_effectiveness_assess` | 观察点顺序与语义细化（`observer_linkage`/`observer_direction`/`observer_term`）；新增 `linkage_note`、`exposure_expected_date`、`term_deviation_days`（**≤ 90 天 = ±3 个月**）、`monitor_deviation_ratio`、`monitor_review_flag`；新增 1 索引 | `Q-7` 三项定性观察点（品种相关性 / 方向一致性 / 期限对应性 **±3 个月**）+ 量化监测指标（**仅监测不判定**） | 新增 6 列 + 1 索引 |
| `DC-011` | 2026-09-20 | `t_rebalance_record` | 新增 `trigger_type ∈ {EXPOSURE_DELTA, RATIO_DEVIATION, PLAN_REVISED, EXPOSURE_GONE}`、`trigger_side ∈ {EXPOSURE_SIDE, INSTRUMENT_SIDE}`（均 `NOT NULL`）；新增 1 索引 | `Q-8` **四类触发**，且**敞口侧与工具侧必须各设一项** | 新增 2 列（必填）+ 1 索引 |
| `DC-012` | 2026-09-20 | `t_hedge_effect` | 新增 `price_as_of`、`non_current_price_flag`；新增 1 索引 | `Q-12` 实货盈亏/估值用途下**沿用最近一期但必须标注价格时点**，结果标记"非当日价" | 新增 2 列 + 1 索引 |
| `DC-013` | 2026-09-20 | `t_account_subject` | 新增 `is_template_default`、`editable_by_enterprise`；新增 1 索引 | `Q-9` 科目表**纳入系统配置**，系统提供**套期会计常用科目模板**，**企业可增删改** | 新增 2 列 + 1 索引 |
| `DC-014` | 2026-09-20 | `t_violation_screen_item`、`t_risk_violation`、`t_risk_alert` | 违规类型枚举新增 **`RELATED_MISSING`（关联对象缺失）**；筛查命中项新增 `related_object_info`；`t_risk_alert.threshold_value` 说明补 `Q-5`/`Q-15` 阈值取值 | `Q-13` 违规筛查**新增"关联对象缺失"独立维度**（与"交易理由缺失"并列）；`R-AUDIT-7` | 枚举取值域扩展（改 `CHECK`）+ 1 新增列 |

| `DC-015` | 2026-09-20 | `t_exposure_detail` | 新增 `component_type`（`NOT NULL`，三分量归属）+ 索引 `idx_exposure_detail_component` | `Q-17` 强化：**敞口明细须可按分量分组求和，与 `t_exposure` 的三分量列互校**（"敞口构成可自证"） | 新增 1 列（必填）+ 1 索引 |
| `DC-016` | 2026-09-20 | `t_pre_trade_reject` | 新增 `base_price_used`、`base_price_source`；索引 `idx_reject_rule_layer` | `Q-15` 价格偏离基准 = **CTP 昨结算价**；记录基准价快照使偏离幅度**可在审计中独立复算**；`Q-16` 按"规则 × 限额层级"统计 | 新增 2 列 + 1 索引 |
| `DC-017` | 2026-09-20 | `t_trade_reason`（索引）+ 文档 §3.1 | **删除失效索引** `idx_trade_reason_code`（建在 `DC-008` 已删除的列 `reason_code` 上，建表脚本执行即报 `invalid column`）→ **改建** `idx_trade_reason_related_type`（`related_type`）；同步修正 §3.1 枚举存储纪律段的 `reason_code` 表述 | **`B-1` 阻塞项整改**（测试经理会签）：索引引用不存在列 | **索引层面**（一删一增，计数不变）；**无结构变更、无迁移** |
| `DC-018` | 2026-09-20 | `t_trade_reason` | **列改名** `related_object_missing_flag` → **`non_order_path_flag`**（"非下单路径成交"标记）；**新增** `path_type ∈ {NORMAL, FORCE_CLOSE, DELIVERY, ERROR_CORRECTION}`（`NOT NULL DEFAULT 'NORMAL'`）；`trade_order_id` 由 `NOT NULL` → **`NULL` + `CHECK`**（仅 `path_type='NORMAL'` 时非空）；`uk_trade_reason_order` 改**条件唯一**（`trade_order_id IS NOT NULL`）；索引 `idx_trade_reason_related_missing` → `idx_trade_reason_non_order_path`；新增 2 条 `CHECK` 不变量 | **`B-2` 阻塞项整改**（编排者裁决）：`related_plan_id`/`related_exposure_id` 保持 `NOT NULL`（与 `Q-13`「关联对象必选、校验不通过即拒绝」一致），故"**关联对象缺失**"在结构上**不可产生**；改将该标记**重定义为"非下单路径成交"**（CTP 侧直接产生的强制平仓/到期交割/错单更正），审计筛查维度改为「**非下单路径成交是否附有说明**」 | **结构变更**（1 列改名 + 1 新增列 + 1 列可空 + 1 唯一索引改条件 + 1 索引改名）；**语义文案的正式回写由产品经理走 `CH-003`**；**无生产数据、无迁移** |
| `DC-019` | 2026-09-20 | `t_risk_violation`、`t_violation_screen_item` + 文档 §3.1 | 违规枚举 **`RELATED_MISSING`（关联对象缺失）→ `NON_ORDER_PATH_UNEXPLAINED`（非下单路径成交未附说明）**；`t_violation_screen_item.related_object_info` → **`non_order_path_info`**（记录 `path_type` + 关联计划/敞口 ID + 成交标识）；§3.1 枚举总表补入新值 | **`B-2` 连带**：原 `RELATED_MISSING` 维度在 `related_*` 恒非空下**无数据可命中、不可测**；新维度**可构造**（登记一条无说明的非下单路径成交即命中），使 `Q-13` 审计维度可测性闭环 | **枚举取值域（改 `CHECK`）+ 1 列改名**；**无迁移** |
| `DC-020` | 2026-09-20 | `t_product_quota`、`t_annual_scale`、`t_risk_threshold`、`t_check_task`、`t_check_signature` | **作用域列 `sub_account_id` 由可空改 `NOT NULL DEFAULT 0`**：`0` = **账户级/全账户哨兵值**；新增 `CHECK` 与 `limit_layer`/`scope_type` 联动；**唯一约束不再含 `NULL`**（`uk_product_quota`/`uk_annual_scale`/`uk_risk_threshold_key_scope`/`uk_check_task_period` 改为哨兵值口径）；`t_sub_account` **增加保留系统行 `id=0`**（`__ALL_ACCOUNT__`，不参与业务与加总）作为哨兵值的外键目标 | **`G-5` 整改**：原唯一键含可空列，其唯一性**依赖 DM8 对 `NULL` 的处理**，若按标准"`NULL` 互不相等"则账户级行**可重复插入**（唯一性失效）；改为哨兵值后**确定且可测**。**实机验证点见 §6.1 `D16`** | **结构变更**（5 表列可空→非空 + 4 唯一索引 + 1 保留行 + `CHECK`）；**无迁移** |
| `DC-021` | 2026-09-20 | **新增 `t_agg_diff_candidate`（`TB-SETTLE-16`）**；`t_aggregation_check.possible_sub_accounts` 语义降级 | 加总差异**候选归属**结构化表（**7 个业务列** + §0.3 通用审计字段 + 3 索引：`sub_account_id`/`aggregation_check_id`/`candidate_rank`/`diff_amount`/`resolution_status`/`reconcile_difference_id`/`evidence_desc`）；`possible_sub_accounts` **降级为展示缓存**（禁止作判定取数来源）；§4.4 新增 §4.4.1「阻断范围的取数路径」 | **`G-6` 整改**：`possible_sub_accounts` 为自由文本 → **"仅差异所属子账户被阻断"三条断言无结构化取数来源**；新增结构化表后差异归属**可机判、可逐行断言**（`NFR-REL-6`、`Q-11`） | **新增表**（**表数 109 → 110**、SETTLE 模块 15 → 16）；**无迁移** |
| `DC-022` | 2026-09-20 | `t_check_signature`（列语义）+ 文档 §4.4 | 补 **"不可代签"的角色级判据**（`signer_role_code` 由签署动作显式选择；须满足"用户持有该角色 + 该角色 ∈ `required_sign_roles` + `sign_role = signer_role_code`"，任一不满足即拒绝并置 `proxy_rejected='Y'`）；补 **多角色账号解析规则**（**不猜测、不默认取任一角色**）；补 **`manage_role_trade_blocked` 语义定义**（`'Y'` = 管理层签署时已核验其交易权被禁用；`'N'` = 非管理层签署不适用） | **`G-4` + `S-3` 整改**：`uk_check_signature_task_role` 仅保证"(task, role) 唯一"，**只防重复签、不防错人签**；`manage_role_trade_blocked` 原语义未定义、不可测 | **列语义明确（无新增列）**；规则写入 §4.4；**无迁移** |
| `DC-023` | 2026-09-20 | `t_spot_change_log` + 文档 §0.4 | `sub_account_id` **补外键** `FK → t_sub_account(id)`（可空：品种档案本体变更时为 `NULL`）；§0.4 补一条约定"**可空外键在 `NULL` 时不参与引用完整性校验**" | **`S-5` 整改**：同章其他表的 `sub_account_id` 均标 FK，本表遗漏 | **补外键约束**；**无迁移** |
| `DC-024` | 2026-09-20 | `t_trade_reason`、`t_risk_violation`、`t_violation_screen_item` | **取代 `DC-018` / `DC-019`**（按产品经理 `CH-004` 裁定，用户批准）：`related_plan_id` / `related_exposure_id` **`NOT NULL → NULL`**；新增**条件非空 `CHECK`** `ck_trade_reason_related_or_missing`：`((两列皆非空) OR related_object_missing_flag='Y')`；**保留** `related_object_missing_flag`（**撤销 `DC-018` 的改名**）；**不新增 `path_type`**（撤销 `DC-018`）；`related_type` **增补枚举 `FORCE_CLOSE` / `DELIVERY`** 以表达"非本系统下单路径"成交；`RELATED_MISSING` 与 `REASON_MISSING` **两者并列保留**（撤销 `DC-019` 的枚举改名）；索引恢复为 `idx_trade_reason_related_missing` | **`B-2` 终版裁定**：`Q-13` 含**不可拆的两层**——①「关联对象必选、提交时校验不通过即拒绝」②「审计侧『理由缺失 / 关联缺失』并列筛查」（`DC-014`）；若 `related_*` 恒 `NOT NULL`，则②**永不可达 = 死条款**。故改**条件非空**，「必选」由**服务层三层校验**（Java 提交权威 → C++ `0x1019` 兜底 → 落库复核）保证，**可空仅承载补填态/异常态且必带缺失标记** | **结构变更**（2 列可空 + 1 `CHECK` + 1 枚举扩展）；**无迁移**。**`DC-018`/`DC-019` 记录保留作历史，其方案已被本条取代** |
| `DC-025` | 2026-09-20 | **新增 `t_custom_hedge_rule`（`TB-PLAN-8`）**；`t_hedge_ratio_calc` 增 `custom_rule_id` / `applied_ratio_cap` / `applied_rule_id` / `hit_rule_count`；`t_hedge_doc` 增 `custom_rule_id` | 自定义套保比例**规则实体**（按编排者规格：`rule_code` UNIQUE / `rule_name` / `sub_account_id` / `product_id`（`NULL`=全品种）/ `direction ∈ {BUY,SELL,BOTH}` / **`basis ∈ {EXPOSURE_QTY,CONTRACT_QTY,INVENTORY_QTY}` 对齐 `Q-17` 三分量** / `ratio_cap DECIMAL(9,4) ∈ (0,1]` / `priority` / `effective_from` / `effective_to` / `status ∈ {DRAFT,EFFECTIVE,EXPIRED}` / `rule_desc` / 审批留痕 + §0.3 审计字段；3 索引 `uk_custom_rule_code` / `idx_custom_rule_scope` / `idx_custom_rule_product`）；`t_hedge_ratio_calc` 关联命中规则并**留痕可复算**；`t_hedge_doc` 关联规则使**书面文件可打印、可追溯** | **`PI-02` 阻塞项整改**：`Q-6` / `FR-PLAN-8` 要求「比例值 + 方向 + 品种列表 + 生效期间 + 优先级」且**支持多套规则叠加**，原模型仅有 `custom_rule_desc` 单条文本 →"设定内控上限 80%"**无处落地**。**叠加语义写死：取最严（`min(ratio_cap)`）→ `priority` 升序 → `id` 升序，不得相加或相乘** | **新增表**（**表数 109 → 111**、PLAN 模块 7 → 8）+ 4 个新增列（2 表）；**无迁移** |
| `DC-026` | 2026-09-20 | `t_exposure` | 新增 `price_as_of`（`DATE`）、`non_current_price_flag`（`CHAR(1)`）、`snapshot_as_of`（`TIMESTAMP`） | **`PI-03` 阻塞项整改**：`Q-12` + `R-SPOT-4` + `FR-ACCT-5` 要求"**敞口估值**沿用最近一期价时**必带** `price_as_of` 并标非当日价"；原 `t_exposure` 仅有 `price_missing_flag`（只能表达"缺价"）→ `Q-12` 在 SPOT 侧**未闭环**。`snapshot_as_of` 同时承载 `Q-17`/`#17` 的"分量沿用须标时点" | **新增 3 列**；**无迁移** |
| `DC-027` | 2026-09-20 | `t_pre_trade_reject` | **补列 `limit_layer`**（`VARCHAR(24)`，取值 `SUB_PRODUCT`/`SUB_INSTRUMENT`/`ACCOUNT_PRODUCT`/`AUTH_QUOTA`/`NONE`，与 `ARCHITECTURE.md` §4.2.4 同域） | **`PI-01` 阻塞项整改**：该列被**索引 `idx_reject_rule_layer`、§3.1 枚举表、§4.7** 三处引用**却无列定义** → **DDL 无法生成**，且 `Q-16`「拒绝须区分命中限额层级」落空（`DC-016` 只登记了索引未登记列，本条补记） | **新增 1 列**；**无迁移** |
| `DC-028` | 2026-09-20 | `t_pre_trade_reject` | 新增 `exposure_snapshot_id`（`BIGINT`）、`exposure_snapshot_time`（`TIMESTAMP`） | **`PI-05` 整改**：`Q-3`「校验记录须标注敞口快照时点」原**无落点**；补列后事前校验所依据的敞口数据**可在审计中独立复现** | **新增 2 列**；**无迁移** |
| `DC-029` | 2026-09-20 | `t_abnormal_order_stat` | 新增 `avg_hold_minutes`（平均持仓周期/分钟）、`round_trip_count`（回合数）；`window_minutes` 说明补"7 日滚动 = 10080" | **`PI-04` 整改**：`Q-5` / `FR-RISK-14` 验收 2 要求"**平均持仓 < 3 交易日且回合 ≥ 5**"，原表仅有 `min_hold_minutes`（最短持仓）→ **判据无数据** | **新增 2 列**；**无迁移** |
| `DC-030` | 2026-09-20 | `t_trade_order` | 新增 `cancel_reason`（`VARCHAR(500)`）、`cancel_operator_id`、`cancel_time` | **`PI-12` 整改**：`FR-TRADE-6` 的输入含撤单原因，原模型**无字段承载**→撤单不可自证 | **新增 3 列**；**无迁移** |
| `DC-031` | 2026-09-20 | `t_voucher` + 文档 §3.1 | `voucher_type` 枚举新增 **`EXERCISE`**（期权行权）/ **`EXPIRED_WORTHLESS`**（到期作废） | **`PI-13` 整改**：`FR-ACCT-11` 要求期权行权/到期作废有对应凭证类型，原枚举缺失 | **枚举取值域扩展（改 `CHECK`）**；**无迁移** |
| `DC-032` | 2026-09-20 | `t_voucher_review` | 新增 `co_reviewer_id`（第二复核人，≠ `reviewer_id` 且 ≠ `generator_id`）；补 备注说明冲销/调整类凭证走同一审核链且须**双人复核** | **`PI-11` 整改**：`FR-ACCT-13` 要求凭证冲销**双人复核**，原表仅有"生成人 ≠ 审核人"→ 冲销的双人复核**无字段可校验** | **新增 1 列**；**无迁移** |
| `DC-033` | 2026-09-20 | `t_spot_change_log` + 文档 §3.1 | `change_type` 的**列级 `CHECK` 取值域追加 `COMPONENT_RECALC`**（原 6 值 → 7 值）；§3.1 已登记该值，本轮仅**补列级 `CHECK`** | **`A-1` / `PI-15` 阻塞项整改**（测试经理 + 产品经理**两席独立复现**）：`COMPONENT_RECALC` 被 §3.1、§4.4（`#17`）、`ARCHITECTURE.md` §6.10 与 `CFG-17b` **四处**要求，但**列级 `CHECK` 缺失** → `#17` 必测用例⑤（展示重算留痕）**按本模型必然落库失败**（与 B-1 同类的"表定义级遗漏"）。关联 `#17` / `DC-026` | **枚举取值域扩展（改列级 `CHECK`）**；**无结构变更、无迁移** |
| `DC-034` | 2026-09-20 | 文档 §3.1（枚举总表） | §3.1 枚举总表**同步三处**：① `t_trade_reason.related_type` 补 **`FORCE_CLOSE`/`DELIVERY`**（与 `TB-TRADE-4` 表定义 `related_type` 列的 8 值一致，`B-1`/`G-7`）；② `t_pre_trade_reject.limit_layer` **补齐 5 值域**（`SUB_PRODUCT`/`SUB_INSTRUMENT`/`ACCOUNT_PRODUCT`/`AUTH_QUOTA`/`NONE`，与 `TB-TRADE-6` 表定义 `limit_layer` 列、`DC-027`、`ARCHITECTURE.md` §4.2.4 同域，`B-2`/`G-8`）；③ 明确**非限额类拒绝一律填 `NONE`**，`NULL` 仅表示未命中任何规则层级（消除 `NONE`/`NULL` 二义，`B-9`/`PI-19`③） | **`B-1`/`B-2`/`B-9` 整改**：§3.1 自述「**数据库 `CHECK` 约束与本节必须一致**」（见 §3.1 开篇说明）→ 表定义已扩展而 §3.1 滞后，将使 `DC-024`/`DC-027` 用例按 §3.1 建表时**必失败** | **仅文档同步（无结构变更、无迁移）** |
| `DC-035` | 2026-09-20 | `t_abnormal_order_stat` | `round_trip_count` 由 **`INT NULL` 改为 `INT NOT NULL DEFAULT 0`** | **`B-8`/`PI-20` 整改**：回合数为**计数类字段**，无"未知"语义；"回合 ≥ 5"判据在无回合时应为 `0` 而非 `NULL`，避免判空歧义 | **列可空性变更（1 列）**；**无迁移** |
| `DC-036` | 2026-09-20 | 文档一致性整批（§4.6 / §0.6 / §5 / §4.1 / `TB-SPOT-7` / `TB-PLAN-8`） | ① **「可加总」口径拆名**（`B-3`/`G-9`）：`t_spot_price` 归**①**并单列"单价不加总"，五类① **44**、可加总 **43**（= 44 − 1）；§4.6.1 逐表标注✅恰 43 行；② 取一算法**统一为** `ratio_cap` 升序→`priority` 升序→`id` 升序（`B-4`/`G-10`，四处）；③ §0.6 删 `TB-PLAN-8` **重复行**（`B-5`/`PI-16`）；④ §5 生命周期表 `t_compliance_check` → `t_check_task`（`B-6`/`PI-17`）；⑤ `TB-SPOT-7` 前缀块清除 `PER_SUB_ACCOUNT` 模式段与过时 question 措辞（`B-7`/`PI-18`）；⑥ **唯一索引计数 94 → 93**、合计 391 → **390** 并写死计数口径（`C-1`/`S-6`）；⑦ `uk_spot_price` → `uk_spot_price_global`（`C-6`②）；⑧ `DC-021` "8 列" → "7 业务列 + §0.3 审计字段"（`C-5`）；⑨ `DC-025` "110→111" → "109→111"（`C-6`①）；⑩ 补**快照字段集定义**（`C-3`/`S-8`，同 `ARCHITECTURE.md` §6.14①）；⑪ `#13` 补**占用率权威在 C++ 内存、须经协议构造**的纪律（`C-4`/`S-9`） | **`B-3`~`B-7`、`C-1`~`C-6` 整改**：均属**文档内部一致性 / 计数自洽 / 口径统一**，不触及模型架构 | **仅文档（无结构变更、无迁移）** |

| `DC-037` | 2026-09-20 | `t_product_quota`、`t_annual_scale`、`t_risk_threshold`、`t_check_task`、`t_check_signature` + 文档 §4.3/§4.4/§4.6 | **作用域哨兵值 `CHECK` 落为具名、可执行 DDL（共 5 条）**：**双向联动型 3 条**——`ck_product_quota_layer_sentinel` / `ck_annual_scale_scope_sentinel` / `ck_risk_threshold_scope_sentinel`（`(范围列=账户级 AND sub_account_id=0) OR (范围列=行级 AND sub_account_id>0)`）；**范围标注型 2 条**——`ck_check_task_scope_sentinel` / `ck_check_signature_scope_sentinel`（该二表**无第二判别列**，`CHECK (sub_account_id >= 0)` 登记"作用域列"身份并拒负值）；**三处落点**（§4.3 权威 DDL / §2 各表定义 / §6.1 `D16`）逐字一致；**术语分离**：A「行级归属类（五类业务数据行）」恒 `>0` vs B「作用域列」允许 `0`，明确 `t_product_quota`/`t_annual_scale` 为**混合表**（B 类行 `=0` 合法，**仍计入五类①、计数不变**）；补被排除组合的拒绝判据 | **`G-11` 整改**：① 原 `§4.3` 自称"由数据库保证"却**无任何具名 `CHECK`**（枚举 `ck_*` 无该约束）→ 实现者拿不到表达式；② 原"五类业务数据表 `sub_account_id` 恒 `>0`"与把 `t_product_quota`/`t_annual_scale` 计入五类①**自我否定**；③ 用例「层①行以 `0` 写入 → 应拒」**无判据**、`D16` 脚本前件不成立 | **新增 5 条具名 `CHECK`**（DDL，§4.3）；**计数不变**；**无迁移** |

| `DC-038` | 2026-09-20 | `t_audit_report`、`t_evidence_export` + 文档 §3.1 | `chain_integrity` 的**列级 `CHECK` 取值域补 `UNKNOWN`**：`t_audit_report` 由 3 值（`COMPLETE`/`HAS_BREAK`/`IN_FLIGHT`）→ **4 值**；`t_evidence_export` 同域表述由「同上」改为**显式 4 值**；二者与 `t_audit_log` **统一**；§3.1 枚举总表补登该二表行 | **`N-4` 订正**（编排者裁决取「**方案①：补 `UNKNOWN`**」）：`#19`④ 要求「数据缺失 → `UNKNOWN`」，而报表/导出侧原仅 3 值 → 当链状态为 `UNKNOWN` 时**无非误标取值可选**，与 `R-AUDIT-2`「不得粉饰」存在张力。补值后「数据缺失一律 `UNKNOWN`、**不得误标为完整**」在**报表/导出侧同样可表达**。**不取**「拒绝生成」方案（会使审计报表在数据缺失时**不可产出**，反而削弱证据链） | **枚举取值域扩展（改列级 `CHECK`）**；**无结构变更、无迁移** |

| `DC-039` | 2026-09-20 | 文档一致性整批（§7.1 v1.3 行 / §4.4 / §4.6.2 / `DC-034`） | ① **`N-1`**：v1.3 变更行「影响面」列「3 具名 `CHECK`」→ **5 具名 `CHECK`（双向联动型 3 + 范围标注型 2）**，与同一行 ⑨、`DC-037`、§4.3、§6.1 `D16` 四处一致；② **`N-2`**：§4.4 哨兵边界行 ② 拒绝来源**收窄为「混合表的 A 类行」**，并补「其余五类表由服务层保证」（指向 §0.2 / §4.4 本行）；③ **`N-3`**：§4.6.2 ⑦ 行标签改「**§4.6.1 核对范围内新增的非五类携带表**」（非穷举），注中补「全库非五类① = 59 − 44 = **15**」推导与 11 张未列表清单；④ **`PI-21`**：`DC-034` 的内部行号交叉引用改**语义引用**（`TB-TRADE-4` / `TB-TRADE-6` 表定义），并订正 §4.4 对 `TB-PRODUCT-6` 备注的同类行号引用 | **`N-1`~`N-3` + `PI-21` 订正**：均为**纯文档一致性 / 计数自洽 / 引用稳定性**问题，**不触及任何表 / 列 / 索引 / `CHECK` 的实质内容**；行号引用统一改**语义引用**以**杜绝后续整改再次漂移**（`PI-21` 的建议） | **仅文档（无结构变更、无迁移）** |

| `DC-040` | 2026-09-20 | 文档一致性整批（§7.2 `DC-034` / §4.4 / §6.1） | ① **`O-1`（= 产品席 `PI-24`，两席独立复现**同一处**）**：`DC-034` 原因列的**行号括注引用**（原引 §3.1 的旧行号，实已漂移约 48 行）改为**语义引用**「见 §3.1 开篇『数据库 `CHECK` 约束与本节必须一致』说明」；并**全库复查**所有「`:` + 数字」形式的内部行号引用已**归零**（宽匹配 `:[0-9]{3,4}[^0-9]`，排除 SQL 标准号「`SQL:2003`/`SQL:2008`」；含 §7.2 各 `DC-` 行、§6.1 自检清单，以及 v1.3.1 行 / `DC-039` 内对"改前值"的描述性回引）；② **`PI-22`**：§4.4 `N-2` 文本「由服务层保证」的引用由 §0.4 改为 **§0.2 / §4.4 本行**，「DB 层仅对 5 张作用域表保证」保留指向 **§0.4**，使引用与内容对应；③ **`PI-23`**：§6.1 自检清单第 10 项由「v1.2 共 **26** 条」改为「**`§4.4` 业务规则 22 条**；另 `§4.4.1` 取数路径 4 行，**二者口径分列、不并计**」；④ §6.1 第 16 项 `DC-` 区间同步为 **`DC-001`～`DC-040`** | **`O-1`/`PI-22`/`PI-23` 订正**：均为**纯文档引用准确性 / 口径拆分**问题，**不触及任何表 / 列 / 索引 / `CHECK` / 枚举取值域的实质内容**；与 `N-1`（同一事项两个数并列而无口径说明）同类的 §4.4「22 vs 26」已消除 | **仅文档（无结构变更、无迁移）** |

| `DC-041` | 2026-09-20 | `t_exposure` + `t_product` | **未映射口径定为"哨兵值 `0` + 恒非空列"（同编号改写，不新增 `DC`）**：① `product_id` **恒 `NOT NULL`**，未映射行取**未映射哨兵值 `0`**（外键指向 `t_product` **保留系统行 `id=0`**）；② 具名 `CHECK` **`ck_exposure_product_unmapped`** 表达式为 `((unmapped_flag='Y' AND product_id = 0) OR (unmapped_flag='N' AND product_id > 0))`；③ `t_exposure` 补列 **`spot_category_code`（`VARCHAR(32) NOT NULL`）**，**双态取值**：未映射行＝**真实品类**（据此**按品类分别留行**，不得汇总为一行、不得以净额合并同品类买卖方向）、已映射行＝**固定占位常量 `'__MAPPED__'`**（不随来源品类变化 ⇒ 品类维度**不参与已映射行的区分**）；④ 新增具名 `CHECK` **`ck_exposure_spot_category_scope`** 强制该双态（使"品类只在未映射行区分"成为**数据库级事实**，`CH-008`）；⑤ `uk_exposure_key` 改为 **`sub_account_id, product_id, spot_category_code, direction, calc_mode, as_of_date`**（全列非空 → 唯一性不依赖 `NULL` 语义；未映射行按品类各占一行、已映射行**不因品类拆行**）；⑥ 文档同步：§4.3「检查（业务不变量）」补列 **2 条**具名 `CHECK`、§6.1 第 16 项 `DC-` 区间改 `DC-001`～`DC-041`、§7.1 落 v1.3.3 行 | **实现方 `#java2` 的 `question`**：本表 `product_id` **约束列写 `NOT NULL`**，而同表 `unmapped_flag` 说明写「`'Y'` 时 `product_id` 可为 `NULL`」——**同一表内两处规定互相否定**，"未映射清单"（`FR-SPOT-14` 验收 2、`FR-PRODUCT-10` 验收 2）**无任何行可承载**。**裁定：业务语义优先**（未映射行必须有行可承载）。**同轮复核进一步认定**：原"条件非空（可空 `NULL`）"方案使 `uk_exposure_key` **含可空列**、其唯一性**依赖 DM8 对 `NULL` 在唯一索引中的处理**，与 `DC-020` / `G-5`（"唯一约束**不依赖** `NULL` 语义"）**方向相反**；且缺 `spot_category_code` 会使未映射行**无法回答品类**。**故收敛为哨兵值 `0` + 恒非空列**。**粒度口径同轮由产品经理裁定**（`CH-008`）：未映射清单**按现货品类分别留行**、且**品类维度仅在未映射行起区分作用**（已映射行仍按「子账户 × 品种 × 方向 × 口径 × 基准日」归并，同一品种来自不同品类的现货数据**须合并为一条**，`FR-SPOT-14` 验收 1/2）——故已映射行的品类取值**必须恒定**；**不取**"该品种主品类"（`TB-PRODUCT-4` 的 `uk_product_spot_primary` 只保证"同一品类的主映射唯一"、**允许多品类映射同一品种** ⇒ "该品种主品类"**非单值**，取之会重新按品类拆行） | **`t_exposure`：1 列改 `NOT NULL` + 新增 1 列 + 1 唯一索引改列 + 2 条具名 `CHECK`（1 改写 + 1 新增）**；**`t_product`：新增 1 保留系统行（`id=0`）**；**索引计数不变（唯一 93 / 普通 297 / 合计 390）**；**无迁移**（尚无生产数据） |

| `DC-042` | 2026-10-06 | `t_ctp_settle_batch`（`TB-SETTLE-1`）+ 文档 §3.1 | `parse_status` 的**列级 `CHECK` 取值域补 `NOT_ARRIVED`**（由 3 值 → **4 值**）：`NOT_ARRIVED` = **数据未到达**（未发生解析），置待重试 | **`CH-021` part 1（L3，用户已批准 2026-10-06）**：`FR-SETTLE-1` 处理规则与验收 2/4 要求「数据未到达」态可落库、可查、可重试，`parse_error` 语义已明列「未到达」，而冻结域缺该取值 ⇒ 未到达分支无法留痕（`te-verify-t058` 独立复现，`T-178` 披露 001b 遗留）。技术席裁定**扩域**、否决以 `is_confirmed='N'` 承载（语义正交：`is_confirmed` 是幂等重收最终性、与未到达取值重合区不可反推、`NOT NULL` 域内仍须填值、`idx_ctp_settle_batch_status` 待重试巡检语义受损） | **枚举取值域扩展（改列级 `CHECK`）**；**无结构变更、无迁移**（`m2_settle.sql` 同步） |

| `DC-043` | 2026-10-06 | `t_param_dispatch`（`TB-RISK-2`）+ 文档 §3.1 | `dispatch_type` 的**列级 `CHECK` 取值域补 `SET_READY_STATE` / `TRIGGER_REPLAY` / `RELOAD`**（由 8 值 → **11 值**，与 `ParamChange.Type` 逐值对齐；对应线格式 `0x0119`/`0x011A`/`0x011B`） | **`CH-021` part 2（L3，用户已批准 2026-10-06）**：`FR-RISK-10` 输入与验收 4 要求「重载/触发重放」下发留痕含回执，`TB-RISK-2` 为唯一回执承载表；`ParamChange.Type` 11 值与冻结域 8 值不符——服务侧 `dispatchTypeOf` 去 `CMD_` 前缀后三类仍越域（`TE-T058-002`，生产未构造、测试假 DAO 掩盖）。**主 FR 依据订正为 `FR-RISK-10`**（`FR-CONFIG-6` 输入不含三类） | **枚举取值域扩展（改列级 `CHECK`）**；**无结构变更、无迁移**（`m3_risk.sql` 的 CHECK 与 COMMENT 同步） |

**v1.3.6 变更统计（`CH-021` 冻结 CHECK 域承载补齐）**：**新增表 0 张**（**表数维持 111**）；
**枚举取值域扩展 2 处（改列级 `CHECK`）**（`DC-042`：`t_ctp_settle_batch.parse_status` 由 3 值扩为 **4 值**〔补 `NOT_ARRIVED`〕；`DC-043`：`t_param_dispatch.dispatch_type` 由 8 值扩为 **11 值**〔补 `SET_READY_STATE`/`TRIGGER_REPLAY`/`RELOAD`〕）；
**表 / 列结构 / 索引均无变更**；**是否需要数据迁移**：**不需要**（尚无生产数据；若已建库由 DBA 按 `ALTER` 重建约束）。

**v1.3.5 变更统计（`CH-011` `FR` 计数传导）**：**新增表 0 张**（**表数维持 111**）；
**纯文档计数传导 1 批**（抬头「需求基线」+ §4.7 说明 + §4.7 自检结论，共 3 处 `FR` 计数 `183 → 184`，源自 `CH-011` 新增 `FR-SPOT-19`）；
**表 / 列结构 / 索引 / 枚举取值域 / `CHECK` 均无变更**。
**是否需要数据迁移**：**不需要**（尚无生产数据）。

**v1.3.3 变更统计（未映射口径定案：哨兵值 + 品类双态 · `DC-041` 同编号改写）**：**新增表 0 张**（**表数维持 111**）；
**列结构变更 2 处**（`t_exposure`：`product_id` 由条件非空改**恒 `NOT NULL`**；新增 `spot_category_code`（`VARCHAR(32) NOT NULL`，**双态取值**：未映射行＝真实品类 / 已映射行＝占位常量 `'__MAPPED__'`））；
**唯一索引改列 1 处**（`uk_exposure_key` 纳入 `spot_category_code`，**全列非空**、不再依赖 `NULL` 语义；**索引计数不变：唯一 93 / 普通 297 / 合计 390**）；
**具名 `CHECK` 2 条**（① 改写表达式 `ck_exposure_product_unmapped`：`IS NULL`/`IS NOT NULL` → `product_id = 0` / `product_id > 0`；② **新增** `ck_exposure_spot_category_scope`：已映射行品类恒取占位常量 ⇒ **品类维度仅在未映射行起区分作用**，`CH-008`）；
**保留系统行新增 1 行**（`t_product` `id=0`，未映射哨兵值载体）；
**口径定案 1 项**（未映射清单**按现货品类分别留行**、已映射行**不得按品类拆行**，`CH-008` / `FR-SPOT-14` 验收 1、2）；
**文档同步 3 处**（§4.3 业务不变量实例列〔补 2 条具名 `CHECK`〕/ §6.1 第 16 项 `DC-` 区间 / §7.1 版本行）。
**是否需要数据迁移**：**不需要**（尚无生产数据）。

**v1.3.2 变更统计（门禁后二次一致性订正）**：**新增表 0 张**（**表数维持 111**）；
**纯文档一致性订正 1 批**（`DC-040`：`O-1` 行号引用语义化并全库复查归零 / `PI-22` 引用指向订正 / `PI-23` §4.4 口径拆清 / §6.1 第 16 项 `DC-` 区间同步）；
**表 / 列结构 / 索引 / 枚举取值域均无变更**。
**是否需要数据迁移**：**不需要**（尚无生产数据）。

**v1.3.1 变更统计（门禁后一致性订正）**：**新增表 0 张**（**表数维持 111**）；
**枚举取值域扩展 1 处**（`DC-038`：`t_audit_report` / `t_evidence_export` 的 `chain_integrity` 由 3 值扩为 **4 值**，补 `UNKNOWN`，§3.1 同步）；
**纯文档一致性订正 1 批**（`DC-039`：`N-1` 计数订正 / `N-2` 拒绝来源收窄 / `N-3` 标签与非穷举说明 / `PI-21` 行号引用改语义引用 + §4.4 同类引用顺手订正）；
**表 / 列结构 / 索引均无变更**（`DC-038` 仅改列级 `CHECK` 取值域）。
**是否需要数据迁移**：**不需要**（尚无生产数据）。

**v1.3 变更统计（M1 会签第二轮整改 · 终版）**：**新增表 0 张**（**表数维持 111**）；**列结构变更 1 处**（`DC-035` `round_trip_count` 改 `NOT NULL DEFAULT 0`）；
**枚举列级 `CHECK` 扩展 1 处**（`DC-033` `change_type` 补 `COMPONENT_RECALC`）；**§3.1 枚举表同步 3 处**（`DC-034`）；
**新增具名 `CHECK` 5 条**（`DC-037` 作用域哨兵值：双向联动型 3 条 + 范围标注型 2 条）；
**索引计数修正**（唯一 **93**、普通 **297**、合计 **390**，`C-1`）；**口径/计数/表述一致性整改 1 批**（`DC-036`）。
**是否需要数据迁移**：**不需要**（尚无生产数据，建表脚本按 v1.3 一次性生成）。

**v1.2 变更统计（M1 会签整改 · 终版）**：**新增表 2 张**（`DC-021` `TB-SETTLE-16`、`DC-025` `TB-PLAN-8`）→ **表数 109 → 111**；
**列改名 0 处**（`DC-018`/`DC-019` 的改名方案已被 `DC-024` 撤销）；**新增列约 12 个**
（`DC-024` 关联类型枚举扩展、`DC-025` 的 `custom_rule_id`、`DC-026` 的 3 列、`DC-027` 的 1 列、`DC-028` 的 2 列、
`DC-029` 的 2 列、`DC-030` 的 3 列、`DC-032` 的 1 列）；**列可空性变更 7 处**（`DC-024` 的 2 个关联列 + `DC-020` 的 5 个作用域列）；
**索引变更 4 处**；**枚举取值域变更 3 处**（`DC-024` 的 `related_type`、`DC-031` 的 `voucher_type`、`S-4`/`#17` 的 `dispatch_status`/`change_type` 文档登记）；
**补外键 1 处**（`DC-023`）；**新增保留系统行 1 行**（`DC-020`）。
**是否需要数据迁移**：**不需要** —— 与 v1.1 同（**尚无生产数据**），建表脚本按 v1.2 一次性生成。

**v1.1 变更统计**：**新增表 3 张**（`DC-006` / `DC-007` / `DC-009` 之一）、**修订表 14 张**、
**新增列约 43 个**、**新增索引约 20 个**、**结构变更（列可空 / 必填 / 唯一键）4 处**（`DC-005`、`DC-008`、`DC-009`、`DC-010`）。
**是否需要数据迁移**：**不需要** —— v1.1 是 M1 编码前的定稿修订，**尚无生产数据**；
建表脚本按 v1.1 一次性生成（`DC-` 编号保留，用于追溯"为何是现在这个结构"）。

### 7.3 变更流程（供后续使用）

```
提出（change 工单 CH-xxx）
   ↓
技术负责人评估影响面（哪些表 / 是否需数据迁移 / 是否影响已上线数据）
   ↓
L3 级由技术负责人批准（涉及接口契约与模块划分）；L1/L2 级须用户拍板
   ↓
① 先改本文件（DATA_MODEL.md）→ 分配 DC-<序号>
   ↓
② 再改 DDL 脚本 → ③ 再改 DAO 与实体 → ④ 再改受影响的测试用例
   ↓
同步通知所有受影响角色（`tech-lead.md` §7：数据模型变更须同步通知）
```

> ⚠️ **纪律**：**表结构变更必须先改 `DATA_MODEL.md` 再改代码**，不得反向（`PROJECT.md` §5.2 / `tech-lead.md` §8.7）。
>
> **影响面须写清**：涉及哪些表、是否需要数据迁移、是否影响已上线数据。

---

## 附录 A：与其它制品的关系

```
docs/REQUIREMENTS/（需求：INDEX + 11 模块文件 + NONFUNCTIONAL + FLOWS）
      ↓ 需求驱动实体识别
ARCHITECTURE.md（架构：分层与接口契约；HGCP v1.0 FROZEN）
      ↓ 接口需要的数据结构
DATA_MODEL.md（本文件：库表落地方案）
      ↓ 数据边界
TASKS.md（任务分解按数据/模块边界切开）
      ↓
源码（严格按本文件的表结构实现）
```

**追溯要求**：本文件中的**每张表**均可追溯到 `docs/REQUIREMENTS/` 中对应模块文件的需求来源——
§2 每张表的**"用途"一句话**均已标注 `FR-xxx` 编号；每个字段均能回答"它服务于什么需求"
（见各表字段的"说明"列与 §3 的字段级说明）。

## 附录 B：表编号 → 需求 → 架构 交叉索引（抽样，完整追溯见各表"用途"）

| 表编号 | 主要需求来源 | 架构承载章节 | 子账户类别 |
|--------|------------|-------------|-----------|
| `TB-CONFIG-1` | `FR-CONFIG-7`、`FR-CONFIG-8` | `ARCHITECTURE.md` §3.8 | 维度本身 |
| `TB-CONFIG-2` | `FR-CONFIG-5`、`FR-CONFIG-11` | §11（配置项清单） | 不适用 |
| `TB-AUTH-7` | `FR-AUTH-10`、`R-AUTH-4` | §3.8、§4.2.3 `CMD_UPDATE_PERMISSION` | 权限维度 |
| `TB-AUTH-8` | `FR-AUTH-12`、`FR-AUTH-13`、`R-AUTH-5` | §8.2 `NFR-SEC-7` | 权限维度 |
| `TB-PRODUCT-1` | `FR-PRODUCT-1`~`14` | §3.8 | 全局主数据 |
| `TB-PRODUCT-6` | `FR-PRODUCT-11`、`R-PRODUCT-4` | §4.2.3 `CMD_UPDATE_QUOTA` | ✅ 敞口/额度 |
| `TB-SPOT-7` | `FR-SPOT-10`~`13`、`FR-SPOT-17` | §3.8、`RSK-05` | ⚠️ 敞口类（已说明） |
| `TB-SPOT-8` | `FR-SPOT-14`、`FR-SPOT-15`、`R-SPOT-4` | §2.5 数据流图、§5.2 | ✅ 敞口类（根实体） |
| `TB-PLAN-1` | `FR-PLAN-1`~`20` | §6.4 | ✅ 计划类 |
| `TB-PLAN-4` | `FR-PLAN-9`、`R-PLAN-8`、`R-ACCT-1` | §6.4 | ✅ 计划类 |
| `TB-TRADE-1` | `FR-TRADE-1`、`R-TRADE-1` | §4.2.4（协议字段） | ✅ 交易类 |
| `TB-TRADE-3` | `FR-TRADE-5`、`FR-SETTLE-1` | §4.2.3 `EVT_TRADE_FILL` | ✅ 交易类 |
| `TB-TRADE-5` | `FR-TRADE-9`、`R-TRADE-8` | §4.4.1（权威源声明） | ✅ 持仓类 |
| `TB-SETTLE-6` | `FR-SETTLE-2`、`FR-SETTLE-6`、`FR-SETTLE-8` | §6.5 | ✅ 持仓类（加总核心） |
| `TB-SETTLE-11` | `FR-SETTLE-8`、`R-SETTLE-2` | §6.5⑥、`CFG-18` | 账户级校验 |
| `TB-RISK-1` | `FR-CONFIG-5`、`R-RISK-14` | §11（`CFG-01`~`CFG-08`） | 含可空子账户 |
| `TB-AUDIT-2` | `FR-AUDIT-9`、`FR-AUDIT-10` | §5.3（链式哈希） | 含可空子账户 |
| `TB-ACCT-4` | `FR-ACCT-6`、`FR-ACCT-7`、`R-ACCT-5` | §6.10 | ✅ 凭证类（`NOT NULL` 硬保证） |
| `TB-ACCT-8` | `FR-ACCT-5`、`FR-ACCT-10`、`R-ACCT-4` | §6.10（双侧同屏） | ✅ 凭证类 |
| `TB-FUND-1` | `FR-FUND-1`、`FR-FUND-3`、`FR-FUND-17` | §6.6、`R-FUND-8` | ✅ 资金类（加总核心） |
| `TB-FUND-4` | `FR-FUND-10`~`12` | §6.6 | ✅ 资金类 |

## 附录 C：M1 门禁会签自检清单（供三方独立审查使用）

> ⚠️ **会签纪律**：三方须**各自独立出具意见**，不得由技术负责人代为汇总后请另两方"确认"。
> 技术负责人是**产出方**，其意见属**自评**。

| # | 审查项 | 审查视角（责任方） | 本文件对应章节 |
|---|--------|-----------------|--------------|
| 1 | **业务字段是否全覆盖**（数据字典项是否都能落到表/字段） | **产品经理** | §2（全表）、§3.1（枚举） |
| 2 | 每个业务实体是否有对应表；是否漏了需求中的数据项 | **产品经理** | §1.3 实体清单、附录 B |
| 3 | 枚举取值是否与需求文件的枚举业务含义一致 | **产品经理** | §3.1 |
| 4 | **表 / 字段 / 约束 / 索引是否齐备** | **技术负责人**（自评） | §2、§4 |
| 5 | **子账户贯穿字段是否逐表落地**（五类数据无一遗漏） | **技术负责人**（自评）+ 产品经理复核 | **§4.6** |
| 6 | 加总可求性是否为结构要求（明细自带标识，可直接求和） | 技术负责人（自评）+ 测试经理 | §4.6.4 |
| 7 | 追溯链的数据载体是否完整（敞口→计划→审批→交易→结算→凭证） | 技术负责人 + 产品经理 | §1.1、§1.2 |
| 8 | DM8 特性依赖是否显式列出；**是否无存储过程/触发器/自定义函数** | 技术负责人（自评） | §6 |
| 9 | **约束/索引是否可作为测试用例的设计输入**（唯一→重复插入、非空→空值、外键→孤儿数据） | **测试经理** | §4.1、§4.3、§4.4 |
| 10 | **无法由 DB 保证的约束是否已列出且可测**（借贷平衡等，**`§4.4` 业务规则 22 条**；另 **`§4.4.1` 取数路径 4 行**，二者口径分列、不并计） | **测试经理** | **§4.4**（含 §4.4.1 阻断取数路径） |
| 11 | 索引是否足以支撑性能 NFR（`NFR-PERF-3/4/7`） | 测试经理 + 技术负责人 | §4.2 |
| 12 | 幂等键唯一约束是否覆盖全部外部回报 | **测试经理** | §4.3（`NFR-REL-5` 相关 5 张表） |
| 13 | 表编号规范是否为 `TB-<模块>-<序号>` | 技术负责人（自评） | §0.6、§2 |
| 14 | 字段变更编号规范是否为 `DC-<序号>` | 技术负责人（自评） | §7 |
| 15 | 与 `PROJECT.md` §5 / §6 是否一致（无自行更换数据库、无强绑定对象） | 三方 | §0、§6.3 |
| 16 | **`INDEX.md` §9.1 的 17 条已确认口径是否逐条有库表承载**（`Q-1`～`Q-17`） | 技术负责人（自评）+ **测试经理复核可测性** | **§7.2 `DC-001`～`DC-043`**、§3.1、§4.4 |
| 17 | **三层限额（`Q-16`）是否三层齐备且校验口径明确**（层①主维度 + 层② 可选 + 层③ 防串用；**v1.2 写死"最细命中者"全序 层②>层①>层③**） | 技术负责人 + 测试经理 | `TB-PRODUCT-6`（层①③）、`TB-PRODUCT-8`（层②）、§4.4（三层限额行）、§4.6.1（1b） |
| 18 | **敞口快照是否存三类分量（`Q-17`）**，而非只存合计；**v1.2 写死分量缺失时按用途分别处理** | 产品经理 + 测试经理 | `TB-SPOT-8`（三分量列 + `snapshot_id/time`）、§4.4（三分量缺失行） |
| 19 | **交易理由是否结构化必填且关联对象校验同子账户（`Q-13`）**；**v1.2（`CH-004`）：`related_*` 条件非空 + 「理由缺失 `REASON_MISSING` / 关联缺失 `RELATED_MISSING`」两个筛查维度并列且均可构造** | 产品经理 + 测试经理 | `TB-TRADE-4`（`related_type`/`related_*`/`time_desc` + `related_object_missing_flag` + `ck_trade_reason_related_or_missing`） |
| 20 | **核对任务与签署记录（`Q-14`）的可验证性**：不可代签（**唯一约束 + v1.2 角色级判据**）、快照变更须重签、逾期告警、`manage_role_trade_blocked` 语义 | **测试经理** | `TB-SETTLE-13` / `TB-SETTLE-15`（`uk_check_signature_task_role`）、§4.4（不可代签行） |
| 21 | **规模基数（`Q-1`）的分子分母是否可独立验证** | 产品经理 + 测试经理 | `TB-RISK-11`（`expected_spot_business_qty` / `accumulated_hedge_qty`） |
| 22 | **v1.1 新增 3 张表 / v1.2 新增 2 张表是否影响五类数据子账户贯穿结论**（§4.6 已重跑：**111 张 = ① 59 + ② 19 + ③ 33**） | 技术负责人（自评）+ 产品经理复核 | §4.6.1（1b）（7）、§4.6.2、§4.6.3（E4/E5）、§4.6.4 |

