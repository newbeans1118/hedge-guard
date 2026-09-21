---
ticket: Q-JAVA2-001
type: question
from: tech-lead
to: backend-engineer#java2
priority: P1
created: 2026-09-20T23:46:00+08:00
refs: [docs/DATA_MODEL.md#2.4, docs/DATA_MODEL.md#2.7, docs/ARCHITECTURE.md#4.2.3, java/CONTRACTS.md#3.2.1, java/CONTRACTS.md#3.2.2, docs/TASKS.md#T-011, docs/TASKS.md#T-061, docs/DEPLOY/schema/m3_risk.sql]
---

## 事由

对 `backend-engineer#java2` 的 `Q-JAVA2-001`（1 处上游文档矛盾 + 2 处接口/边界口径）
**逐条裁定**。三条均有**确定性结论**，**无「待 M2 再定」的悬置项**；裁定**与你的暂定口径（`Q-1 A` / `Q-3 ①`）方向一致**，
**无需返工**（细节见各条）。本文件为**答复件**（原单已置 `CLOSED`）。

> **实测锚点（本席在写作时刻实测，引用前请重测）**：
> - `docs/DATA_MODEL.md` **v1.3.3**，md5 `c399c5f595eba8116187acc6dac4e9eb`，**5245 行**
> - `docs/ARCHITECTURE.md` **v1.5.5**，md5 `8930dcb51a2384b1ed9fa884533ef547`，**2636 行**（本轮由 v1.5.4 升级，见「附带交付」）
> - `java/CONTRACTS.md`（**只读**，唯一写者 `backend-engineer#java`）与 `docs/TASKS.md` 见各条行号

---

## 裁定 1（`Q-1`）：`t_exposure.product_id` —— **采纳 A 的等价终案（哨兵 `0` + 品类双态）**

提问所指「字段表 `NOT NULL`」与「备注称 `'Y'` 时可为 `NULL`」的矛盾，**在 `DATA_MODEL.md` v1.3.3 现行正文中已由 `DC-041` 消解**，
**结论不是「改备注」**（那只是 A 原案的做法），而是**结构级终案**。**你已落的 `NOT NULL` DDL 与终案一致，无需改动。**

**本席实测的行号证据（`docs/DATA_MODEL.md`，5245 行现行版）**：

| 行号 | 实测内容（要点） |
|------|------------------|
| `:1494` | `product_id` 约束列 = **`NOT NULL`（恒非空**；例外态取**未映射哨兵值 `0`**，由具名 `CHECK` `ck_exposure_product_unmapped` 保证），FK → `t_product(id)` |
| `:1495` | 新增列 `spot_category_code VARCHAR(32)` **`NOT NULL`（双态）**：未映射行＝真实品类 / 已映射行＝占位常量 `'__MAPPED__'`，由具名 `CHECK` `ck_exposure_spot_category_scope` 保证 |
| `:1516` | `unmapped_flag`：与 `product_id` **双向联动（⇔）** —— `'Y'` ⇔ `product_id = 0`；`'N'` ⇔ `product_id > 0` |
| `:1523` | `uk_exposure_key` = `sub_account_id, product_id, spot_category_code, direction, calc_mode, as_of_date`（**六列**；**全列非空 → 唯一性不依赖 `NULL` 语义**） |
| `:1538–1547` | 两条具名 `CHECK` 的**可执行 DDL**（`ck_exposure_product_unmapped` / `ck_exposure_spot_category_scope`） |
| `:1012`、`:1017` | `t_product` **保留系统行 `id = 0`**（`product_code = '__UNMAPPED__'`）；`:1017` 明确 `t_exposure.product_id` 的哨兵 `0` **外键指向该行** |
| `:1577–1578` | 哨兵值**仍在引用完整性校验范围内**（**不适用**「可空外键」约定，`DC-023`） |
| `:5109`、`:5053`、`:5112` | `DC-041` 变更登记 + `v1.3.3` 行 + v1.3.3 变更统计（**列结构 2 处 / 唯一索引改列 1 处 / 具名 `CHECK` 2 条；索引计数不变：唯一 93 / 普通 297 / 合计 390；表数维持 111**） |

**`'Y'` 行的落库方式（回答你「代价」一栏的关切）**：终案下 `unmapped_flag='Y'` 的行**可以写入** `t_exposure`
（`product_id = 0`、`spot_category_code` = 真实品类），**不再需要**把「未映射清单」退化为服务层实时列举。
「未映射清单」即 `unmapped_flag='Y'` 的行（`FR-SPOT-14` 验收 2 / `FR-PRODUCT-10` 验收 2），
**按现货品类分别留行**（`CH-008`，`DATA_MODEL.md:1579–1582`）；已映射行**不因品类拆行**。

**残留表述核查**：提问所引 `:1508` 旧文（"`'Y'` 时 `product_id` **可为 `NULL`**"）**在现行版该行已不存在**
（`:1508` 现为 `unit` 列）；**全库同类措辞零残留**（`product_id IS NULL` 仅在两处出现，均为**别表合法语义**：`t_custom_rule.product_id IS NULL` = 全品种适用，`DATA_MODEL.md:1951`/`:1955`）。
仅 `DC-041` 变更记录（`:5053` 的 v1.3.3 行、`:5109` 的 `DC-041` 行）内**引用旧文以留痕**——
那是**变更依据留痕，应保留**，**不作为缺陷整改**。

> 需求侧依据（供追溯）：`CH-008`（`docs/REQUIREMENTS/SPOT.md` §3）推定编号 + `FR-SPOT-14` 验收 1/2；`T-014` 验收 1/2。
> 下游：`data-init.sql` 已播种 `t_product(id=0)`（与 `m1_product.sql` 列级交叉校验一致）。

---

## 裁定 2（`Q-2`）：`ParamChange` 表达「仅冻结该子账户新开仓 + 定点解冻」—— **字段级口径已冻结**

**结论：该口径已经冻结在 `java/CONTRACTS.md`（`HGC-1.2` + `HGC-1.6`）与 `ARCHITECTURE.md` §4.2.3，你可直接编码，不必等 M2。**

**协议字段级（`ARCHITECTURE.md` `:749`，§4.2.3 消息总表第 20 行）**：
`0x0118 CMD_SET_TRADING_SUSPEND` = `param_version`, `sub_account_id`（`0` = 全账户）, **`scan_scope`**（`OPEN_ONLY`）, **`suspended`**, `reason`, `operator_id`。

**`ParamChange.payload` 键名（`java/CONTRACTS.md`，实测行号）**：

| 行号 | 实测内容 |
|------|----------|
| `:330` | `scan_scope` —— 固定 `"OPEN_ONLY"`；⚠️ **以 §4.2.3 协议表为准**（正文旧文写作 `scope`；**协议表写 `scan_scope`**，登记为 `D-15`） |
| `:331` | `suspended` —— `"true"` / `"false"` |
| `:343` | 协议**无 per-suspend 令牌字段**（`0x0118` 字段集仅上列 6 个） |
| `:344–346` | **"撤下我这次下的挂起"只能靠 `sub_account_id` 精确定位**；同子账户存在**多个并发挂起来源**时，须维护**未撤销挂起计数**，**仅当计数归零**才下发 `suspended="false"` |
| `:348–356` | **`HGC-1.6` 硬规则**：`payload` **只允许** §4.2.3 协议表内字段名，**禁止任何协议外字段**；**典型违例（已实测发生）= 把 `suspend_id` 写进 payload** |
| `:358–364` | **API 规则**：内部关联标识**必须经 API 可见**；生成方应提供**带标识的主入口**（`suspendOpenOnly(subAccountId, suspendId, reason, operatorId)`）+ **自动生成的便利重载**以兼容既有调用方 |

**结论口径（逐项确定，可直接据此编码/自查）**：

1. **作用域字段名 = `scan_scope`**，取值域 = **`OPEN_ONLY`（唯一取值）**。⚠️ **不得**写作口语的 `scope`。
2. **挂起/解冻字段名 = `suspended`**，取值域 = **`"true"` / `"false"`**（**不是** `suspend`）。
3. **`suspend_id` 不入 `payload`** —— 协议表无此字段。它是 **Java 内部 LID（本地关联标识）**，
   只出现在**方法参数 / 进程内配对计数 / 日志 / `t_param_dispatch.ack_detail`**；**不得**新增协议字段。
4. **主入口 = 4 参 `suspendOpenOnly(subAccountId, suspendId, reason, operatorId)`**；
   **3 参重载仅委托生成**（内部 mint 一个新的 LID 后转调 4 参），供不需要自持标识的调用方使用。**两者都在签名上可见标识**。
5. **「解冻」语义 = 撤下本次挂起（幂等撤销）**，**不是全局解冻**；
   禁止用 `sub_account_id="0"` 解冻（那是**全账户**解冻）；**重复撤销须幂等**（不得误撤他人挂起）。

**你的实现核对结果（本席只读实测）**：`ParamDispatchServiceImpl.java`
`:269`（3 参重载）`→ :286`（4 参主入口），`:270` 为 `suspendOpenOnly(subAccountId, newSuspendId(), reason, operatorId)`
（**3 参重载仅委托生成**，符合口径）；`:341–359` 为按标识撤销与幂等校验；`:418` 为两阶段编排调用。
`ParamDispatchServiceImplTest.java:181–205`（用例 `suspendPairingUsesOutstandingCount`，`:183` 起）已覆盖「计数归零才下发 `suspended=false`」。**与裁定一致，无需返工。**

> ⚠️ **同文件内自相矛盾（本席只读发现，未改，登记移交）**：`java/CONTRACTS.md` **存在同名两节**
> `#### 3.2.1`：`:284`（标注 "`HGC-1.1` 增量"，表行 `:294` 用 **`scope`**、`:295` 用 **`suspend`**、
> `:296` 要求 **`suspend_id` 必填**，伪码 `:309/:311` 亦用 `scope=` / `suspend_id=ID`）
> 与 `:322`（标注 "`HGC-1.2` 新增" + `:348` `HGC-1.6` 硬规则，**用 `scan_scope`/`suspended` 且禁止 `suspend_id` 入 `payload`**）。
> **前者与终态口径相反**。`CONTRACTS.md` 的**唯一写者 = `backend-engineer#java`**，本席**不得编辑**，
> 故**仅登记**：请 `#java` 按 §5.1 编辑登记表订正 `:284–313` 段（**建议保留 `HGC-1.1` 留痕块但加"已被 `HGC-1.2`/`HGC-1.6` 取代"标注**），
> 或由编排者裁定后转 `#java` 落笔。**在订正前，以实现方口径为准的判据 = `:322` 节 + `HGC-1.6` 硬规则。**

---

## 裁定 3（`Q-3`）：`t_param_dispatch` 归属 + `param_version` 分配源 —— **采纳 ①**

### 3.1 归属：**采纳方案 ①（`#java` 交付），你只调接口、不落 DDL/DAO**

**本席实测证据**：

| 制品 | 实测 |
|------|------|
| `docs/DEPLOY/schema/m3_risk.sql` | `grep -c 'CREATE TABLE'` = **1**；唯一表 = `t_param_dispatch`（`:30`）。文件头 `:2` 标注 `TB-RISK-1~11，共 11 张`，但 `:10–11` **自述「本文件当前仅含 1 张表」**，`:13–20` 列举**待续 10 张**（`t_annual_scale` / `t_risk_threshold` / `t_mirror_watermark` / `t_risk_alert` / `t_risk_disposal` / `t_abnormal_order_stat` / `t_risk_metric_snapshot` / `t_post_risk_report` / `t_risk_violation` / `t_limit_usage_stat`） |
| `java/.../dao/ParamDispatchDao.java` | `:39` `long maxParamVersion();` —— 接口已交付 |
| `java/.../dao/impl/DmParamDispatchDao.java` | `:117` `public long maxParamVersion()` —— 实现已交付 |
| `java/CONTRACTS.md:12` | 明载 `HGC-1.2` 轮「**并交付 `T-061` 首个增量（`TB-RISK-2 t_param_dispatch` 的 DDL + DAO）**」 |

⇒ **`t_param_dispatch` 的 DDL + DAO 属 `#java`（`T-061` 首个增量），已交付**。
**你（`#java2`）不落该表 DDL/DAO，只经 `ParamDispatchDao` 接口调用**（`maxParamVersion()` 用于播种，见 3.2）。
**其余 10 张 RISK 表归 M3 窗口，本轮不派发** —— 你的 `ParamDispatchServiceImpl` 落库段**只依赖 `t_param_dispatch`，现已解阻**。

### 3.2 `param_version` 分配源：**全局单调、单一分配器；唯一合法基准 = `maxParamVersion() + 1`**

**这是「全局单调计数器」口径，不是「每表一列」**。契约出处（`java/CONTRACTS.md` §3.2.2，实测行号）：

- `:376` **分配方 = 服务层（`ParamDispatchServiceImpl`）**，写入 `ParamChange.paramVersion` 后再下发；**桥接层不得改动**。
- `:377`（`HGC-1.5` 收紧）**唯一合法基准 = `ParamDispatchDao.maxParamVersion() + 1`**，三条不变量：
  ① **单调递增**；② **进程内两两不同**；③ **「取值」与「落库」在同一临界区**内完成（单飞/显式加锁）。
  - ⚠️ **`t_param_dispatch` 无 `UNIQUE(param_version)`** ⇒ **不得**按「`UNIQUE` 兜底 + 冲突重试」实现；`FOR UPDATE` 仍**禁用**。
  - 🚫 **明令禁止用行数代替**（如 `count(*)+1`）：版本号**允许有缺口**（回滚/重试），用行数播种会在有缺口时**回退到已用过的号段**
    → 新变更被 C++ 判为陈旧（`0x0403`）后**静默丢弃**。
  - ✅ **两种实现均可**：**(A)** 锁内 `maxParamVersion()+1` → 同临界区落库；**(B)** 进程内**原子单调计数器**，但**必须以 `maxParamVersion()+1` 播种**（且播种在锁内），**跨重启重新播种**。

**你的实现核对结果（本席只读实测）**：`ParamDispatchServiceImpl.java:148–149` = 进程内 `AtomicLong versionCounter`（初值 `-1`）；
`:468` = `versionCounter.set(paramDispatchDao.maxParamVersion() + 1L)`（**播种在临界区内、基准为 `max`，符合 (B) 且未用行数**）。
**判据已作用例固化**：`ParamDispatchServiceImplTest.java` `versionAllocationIsSerialized`（`:149` 起）
以 **12 线程**并发下发，`:176` 断言 `versions.size() == 12`（**两两不同**），`:177–178` 断言 `max(versions) == 12`（**连续取到 1..12**）。
—— 该用例正是对「跨临界区读-写导致**静默丢变更**」那次实测缺陷的回归固化。**与裁定一致，无需返工。**

> ⚠️ **实测偏差（本席发现，未改，登记移交）**：`docs/TASKS.md` **未回写** `T-061` 的范围收紧 ——
> `:105`（总览）与 `:1186`（详情标题）仍为「**交付 RISK 模块 11 张表 DDL 与 DAO**」、
> `:1189` 负责人 `backend-engineer#java`、20 人时、`TODO`；`:1891` 的 `#java` 任务清单亦仍含 `T-061`(20)。
> 与 `m3_risk.sql` 的「**单表增量 + 待续 10 张**」**口径不一致**（**非**你我的写域）。
> `docs/TASKS.md` 为项目经理唯一写入范围，**本席不改**；已同步编排者裁决。

---

## 附带交付（技术负责人本轮另一项，与你的问题无关，仅告知以免你误判锚点）

`docs/ARCHITECTURE.md` **v1.5.4 → v1.5.5**（**内容缺口补全，非新 `CH-xxx`**）：
**§7.2.2 新增 Java 侧「依赖准入判据」**（① 传递依赖清单 ② 冒烟实例化证据，**理由严格写为「防 `provided`/`optional`/反射/`ServiceLoader`」**）。
**未改动** §7.2.2 依赖表行，**未触** §4.2 协议实质（消息集 40 / 错误码 62 / `VER 0x0100` 不变），**未触** §3.2 契约。
**落笔位置**：`ARCHITECTURE.md:1879–1904`（§7.2.2），新增块 = **`:1894–1903`**。
**新锚点**：`ARCHITECTURE.md` md5 `8930dcb51a2384b1ed9fa884533ef547` / **2636 行**；`docs/BOARD.md` md5 `895db2debdb95637f1bf6623b36cd309` / **252 行**。

---

## 对你的要求（无需回填本单）

1. **`Q-1`**：维持已落的 `NOT NULL` DDL；实现「未映射清单」时按 `unmapped_flag='Y'` 的行读，**按品类分别留行**（`CH-008`）。
2. **`Q-2`**：`payload` 键只用 `scan_scope` / `suspended` / `sub_account_id` / `reason` / `operator_id`；`suspend_id` 走 **Java 内部 LID + 4 参 API**；解冻 = 幂等撤销。
3. **`Q-3`**：调用 `ParamDispatchDao`（含 `maxParamVersion()`）；**不要**自建 `t_param_dispatch` 的 DDL/DAO 或第二套取号器。
4. 若上述任一条与你**现有实现**不符（本席实测为**均相符**），**投新 `question` 单**，**不重开 `Q-JAVA2-001`**。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 23:46 | tech-lead | 逐条裁定 `Q-1` / `Q-2` / `Q-3`，出答复件（本文件）；原单 `Q-JAVA2-001` 置 `CLOSED` | 三条均确定，采纳口径 = `Q-1` A 等价终案（`DC-041`）/ `Q-2` 已冻结（`HGC-1.2`+`HGC-1.6`）/ `Q-3` ① |

---

## 状态

- **当前状态**：CLOSED（答复件；原单 `dev-team/inbox/tech-lead/Q-JAVA2-001-data-model-and-contract.md` 同步置 `CLOSED`）
- **终态确认人**：技术负责人（裁定方）
- **终态时间**：2026-09-20
