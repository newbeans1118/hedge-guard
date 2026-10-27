# DM8 环境就绪验收底稿（TC-M4-27 待环境项承接）

> **性质**：验收底稿（checklist），非执行记录。DM8 实机不可达期间（`CH-016`：SQLite 仅 dev/test、生产仍 DM8；2026-10-05 用户批示「目前不考虑 DM8 问题」），本底稿供 **DM8 环境就绪后的增量补验单直接使用**——照 §2 步骤执行、§3 清单逐项回填实测值即可，无需再行设计。
> **来源**：`T-087` / `TC-M4-27`（`docs/TEST_PLAN/M4.md` §9）；执行证据见 `docs/TEST_RESULT.md` §T087。
> **时点锚**：基线 commit `cf5d53d`（2026-10-27）；schema 计数于该时点实测，**引用前以实时为准**。
> **边界声明**：本文件为新增文档，**未改动** `docs/DEPLOY/` 任何既有文件。

---

## 1. 环境前置（全部满足方可开工）

| # | 前置 | 核对命令 | 通过判据 |
|---|------|---------|---------|
| P-1 | DM8 实例可达 | `ss -ltnp \| grep 5236` | 有监听（对照：2026-10-05 实测无监听，README §8.1） |
| P-2 | dmserver 进程 | `ps -ef \| grep dmserver` | 存在 |
| P-3 | DM JDBC 驱动 jar | `ls java/lib/DmJdbcDriver18.jar && md5sum java/lib/DmJdbcDriver18.jar` | 存在，md5 登记 README §2.2 |
| P-4 | 驱动版本 | 驱动 jar 内 `MANIFEST.MF` 或 DM8 `SELECT * FROM v$version` | 版本号记录在案 |
| P-5 | 构建链 | `cd java && mvn -B -o -Pdm compile` | BUILD SUCCESS（system scope 走 P-3 jar） |
| P-6 | 账号权限 | 以 DBA 角色执行建库段 | 建表空间/用户/授权成功 |

## 2. 建库步骤（照序执行）

> 顺序 = `DEPLOY/README.md` §7.1 两阶段口径 + **本底稿补位**：`m4_acct.sql`（T-076 后新增脚本）插入步 12.5（其 FK 父表 `t_account_subject`/`t_sub_account`(CONFIG/AUTH)、`t_hedge_plan`/`t_hedge_doc`(PLAN)、`t_trade_fill`(TRADE)、`t_settle_cycle`(SETTLE)、`t_exposure`(SPOT) 均在先序脚本建齐；本脚本内自洽环 `t_hedge_relation`↔`t_voucher`↔`t_cas`↔`t_effectiveness_assess` 按脚本内书写序即可，**无后置 ALTER**）。
> ⚠️ 观察登记（不阻断）：`README §7.1` 的 13 步清单**未含 `m4_acct.sql`**（写于 T-076 之前）；本底稿按实测 FK 依赖补位，README 正文回改归 DEPLOY 档维护方。

```text
阶段 0：建库参数
  0) 创建 DM8 实例；库级参数须设「标识符大小写不敏感」
     （m1_config_auth.sql 头注 ① / DATA_MODEL.md §6.1 D11——缺此项建表可过但标识符
      匹配行为与开发档漂移，属 P0 级偏差）

阶段 A（基表 + 后置外键回补）：
  1) m1_config_auth.sql   —— 暂缓文件末尾跨模块外键段（fk_authorization_product → t_product）
  2) m1_product.sql
  3) 回补 1) 暂缓的跨模块外键段
  4) m1_spot.sql
  5) m1_plan.sql          —— 暂缓文件内后置 ALTER 段（:395~:411，含 fk_trade_plan_strategy → t_option_strategy）
  6) m2_trade.sql         —— 文件内后置 ALTER（fk_trade_order_strategy/reason）随建表段尾执行
  7) 回补 5) 暂缓的跨模块外键段
  8) m2_settle.sql        —— 仅建表段；暂缓 fk_t_settle_fund_ledger → t_fund_ledger 段（:775）
  9) m2_fund.sql          —— 文件内后置 ALTER（fk_fund_flow_transfer/dw）随建表段尾执行
 10) 回补 8) 暂缓的 fk_t_settle_fund_ledger 段
 11) m3_risk.sql
 12) m3_audit.sql
 12.5) m4_acct.sql        —— 本底稿补位（T-076；无后置 ALTER）
 13) data-init.sql        —— 播种（含 8 对 SET IDENTITY_INSERT ON/OFF + 8 条 INSERT..SELECT..FROM(UNION ALL)，
                              均为 DM-SPECIFIC 已标注形态，DM8 原样执行）
```

## 3. 逐项核对清单（唯一预期值·实测列由执行单回填）

> 计数类预期值 = 2026-10-27 实测（基线 `cf5d53d`）；schema 脚本变更后须重测本表。

| # | 核对项 | 方法 | 唯一预期值 | 实测值 | 依据 |
|---|--------|------|-----------|--------|------|
| C-1 | 建表总数 | `SELECT COUNT(*) FROM user_tables;` | **== 111**（CONFIG+AUTH 20 / PRODUCT 8 / SPOT 10 / PLAN 8 / TRADE 9 / SETTLE 16 / FUND 9 / RISK 11 / AUDIT 8 / ACCT 12） | ____ | DATA_MODEL §0.6（111 张 v1.2）；与 InMemoryDmEngine `tableCount()==111` 三方一致（§T087） |
| C-2 | 逐列一致性 | `python3 docs/DEPLOY/tools/reconcile-schema.py` | 退出码 0（⚠️ 该工具现行覆盖 99 表、未含 m4_acct——执行单须以扩展口径补 12 表或按登记观察处置） | ____ | README §8.2 |
| C-3 | 外键顺序 | `python3 docs/DEPLOY/tools/fk-order-check.py` | `RESULT: PASS`（README 记载 170 边口径不含 m4_acct 新增边，以实时为准） | ____ | README §8.4 |
| C-4 | IDENTITY 列 | 脚本 grep `IDENTITY(1,1)` vs `user_tab_columns` | **== 111**（每表 id 一列） | ____ | §T087 实测 |
| C-5 | 大字段 CLOB 列 | 脚本 grep vs `user_tab_columns` | **== 20** | ____ | §T087 实测 |
| C-6 | 注释语句 | `user_comments` 计数 | **== 832** 条 COMMENT ON 全部执行成功 | ____ | §T087 实测 |
| C-7 | 后置 ALTER 回补 | 脚本 grep `^ALTER TABLE` | **== 10** 条全过（无 ORA/DM 报错） | ____ | §T087 实测 |
| C-8 | DM-SPECIFIC 语法 | 脚本 grep `DM-SPECIFIC` | **== 19** 处逐处验证 DM8 接受（含 `SET IDENTITY_INSERT` 8 对、`CURRENT_TIMESTAMP` 缺省等） | ____ | NFR-COMPAT-5「DM 特有语法须标注」 |
| C-9 | 播种 | data-init.sql 执行 | 8 条 INSERT..SELECT 全过；播种行数与脚本自检段一致（以脚本头部声明为准） | ____ | §T087（引擎侧不可执行=单侧档披露，DM8 侧为准执行档） |
| C-10 | 大字段读写探针 | 见 §3.1 SQL | roundtrip 逐字符相等、长度 == 100000 | ____ | §T087 引擎侧同构探针 2/2 绿 |
| C-11 | 方言残留扫描 | §3.2 词表 grep | **命中 == 0** | ____ | TC-M4-27；§T087 引擎侧实测 0 |
| C-12 | DDL 禁项 | grep `CREATE (PROCEDURE\|TRIGGER\|FUNCTION)` 于 schema/*.sql | **命中 == 0** | ____ | NFR-COMPAT-5；TC-M4-28 |
| C-13 | 单测回归 | `cd java && mvn -B -o -Pdm clean test`（DM 驱动在位） | 全绿（基线以执行时点为准，2026-10-27 为 1289） | ____ | 门禁三件套 |
| C-14 | 版本留痕 | `SELECT * FROM v$version` + 驱动 md5 | 回填 README §2.1/§2.2 | ____ | README §8.3-3 |

### 3.1 C-10 大字段读写探针（DM8 版）

```sql
-- 写入（100_000 字符确定性序列：第 i 字符 = CHR(65 + MOD(i,26))）
INSERT INTO t_permission_change_log
  (change_target, before_value, after_value, operator_id, change_time, created_by, created_time, row_version)
VALUES ('USER_ROLE', <100000 字符串>, <100005 字符串（尾部拼 '#TAIL'）>, 1,
        TIMESTAMP '2026-10-27 09:00:00', 1, TIMESTAMP '2026-10-27 09:00:00', 1);
-- 读回断言
SELECT LENGTH(before_value), LENGTH(after_value),
       before_value = <同串> 等价比较  -- DM8 侧以 DBMS_LOB.COMPARE 或应用层逐字符比对承载
FROM t_permission_change_log WHERE operator_id = 1;
-- 唯一预期值：before 长度 == 100000；after 长度 == 100005；逐字符比较差值 == 0
```

### 3.2 C-11 方言残留扫描词表（判据冻结）

对 `docs/DEPLOY/schema/*.sql` **非注释行** grep（大小写不敏感），命中须 `== 0`：

```text
AUTO_INCREMENT / InnoDB / ENGINE= / ON DUPLICATE KEY / REPLACE INTO / UNSIGNED /
utf8mb4 / LOCK TABLES / LOW_PRIORITY / SQL_CALC_FOUND_ROWS / 反引号(`) / LIMIT n,m（双参形态）
```

（2026-10-27 实测：12 项全 0。`data-init.sql:28` 的 `DM-SPECIFIC: SET IDENTITY_INSERT` 为**已标注 DM 特有语法**，NFR-COMPAT-5 允许，不计残留。）

## 4. 与 dev/test 档（CH-016）的分工声明

| 面 | dev/test 档（现行·已执行） | DM8 档（本底稿·待环境） |
|----|---------------------------|------------------------|
| 建表 | InMemoryDmEngine `executeScript` 全 10 模块脚本，`tableCount()==111` ✅ | §2 步骤 |
| 播种 | **data-init.sql 不可执行**（DM8 方言：`SET IDENTITY_INSERT` + `INSERT..SELECT..FROM(UNION ALL)` 引擎不解析）⇒ 播种由测试种子类（`M1E2eSeed`/`M2E2eSeed` 等）承载 | data-init 原样执行（C-9） |
| 大字段 | CLOB 100000 字符真实 DAO roundtrip ✅ | §3.1 |
| 约束语义 | 引擎按 DDL 语义求值（NOT NULL/UNIQUE/CHECK）；**DDL DEFAULT 不生效**、FK 父表 DDL 时点不校验——两处引擎↔DM8 语义差异（R-5 族），结论不得互代 | 真实 DM8 语义 |
| 结论效力 | **不得以 dev/test 结论替代 DM8 验收**（`CH-016` / M4.md R-1 明文） | TC-M4-27 终验效力所在 |
