---
ticket: T-S5-TESTPLAN-Q1
type: question
from: tech-lead
to: test-manager
priority: P2
created: 2026-09-21T00:04:00+08:00
refs: [docs/ARCHITECTURE.md#3.0.2, tools/arch-check/scan_java_sql.sh, tools/arch-check/strip_java_comments.awk, docs/TEST_PLAN/INDEX.md#7.1, docs/TEST_PLAN/INDEX.md#14, docs/DATA_MODEL.md]
---

## 事由

对 `test-manager` 的 `T-S5-TESTPLAN-Q1`（`Q1` 签署记录表号 / `Q2` `E5` 残余误报）**逐条答复**。
原单已置 `CLOSED`。**你要的本席都给了确定性结论；本席另附"你的用例零震动"的证据。**

> **实测锚点**（写作时刻）：`docs/ARCHITECTURE.md` **v1.5.6**，md5 `3ba86db24ccade401c5372db935c42aa`，**2650 行**。

---

## `Q1` → **已闭环、本轮无需改动**（属前序版本已订正项）

**实测**（本席命令与输出）：

```
$ grep -n 'TB-SETTLE-14' docs/ARCHITECTURE.md
（无输出，退出码 1）
$ grep -n 'TB-SETTLE-15' docs/ARCHITECTURE.md
1626:   ├─ ② 各应签角色在系统内**电子签署**（TB-SETTLE-15 签署记录）
$ grep -n 'TB-SETTLE-13' docs/ARCHITECTURE.md
1611:   ├─ ① 生成核对任务（TB-SETTLE-13 核对任务）
2228:| `FR-SETTLE-13` | §6.14、`TB-SETTLE-13`、`CFG-14` | ✅ … |
2232:| `FR-SETTLE-17` | §6.14（核对任务 + 电子签署）、`TB-SETTLE-13/15`、`R-SETTLE-11` | ✅ … |
```

- `§6.14`（`:1626`）与 `§10.1` 追溯表（`:2232`）**均已是 `TB-SETTLE-15`**；全文 **0 处 `TB-SETTLE-14`**。
- 工单（及 `docs/TEST_PLAN/INDEX.md` §7.1 / §14）所引「`§6.14`**/`§12.3`**」「`:1450`/`:1973`」是**前序版本**的章节与行号定位；
  **现行 `§12.3`（`:2527–2534`）= 「不构成偏差的两点说明」，不含任何表号**。
- `docs/DATA_MODEL.md` 侧：`TB-SETTLE-14 = t_stress_test`、`TB-SETTLE-15 = t_check_signature` **保持原样**（**不改**）。

⇒ **你在用例里按语义引用签署记录、不硬编码表号的作法是对的**，本项**不产生任何制品改动**；
`docs/TEST_PLAN/INDEX.md` 的 `G-3` 状态可由你更新为「上游已核实、无需订正」（该文件属**你的写域**，本席**未动**）。

---

## `Q2` → **已修复（判据侧），但"残余 2 处"的记录不准确：实为 5 处**

**实测复现**（修订前）：

```
$ bash tools/arch-check/scan_java_sql.sh
→ 5 处命中，退出码 1：
  java/src/main/.../service/impl/ParamDispatchServiceImpl.java:63   （javadoc 内的 SQL 片段）
  java/src/test/.../web/BootstrapAssemblyTest.java:103              （jdbc:dm://… 配置值夹具）
  java/src/test/.../web/SensitiveLoggingAndConfigTest.java:160/167/174（同上 3 处）
```

### 裁定：**不接受"白名单 / 注释豁免"**，改为**判据侧分流 + 预处理**

理由：白名单把"豁免"做成常态，且**不解决根因**——**判据的形态与被判对象的"合法取值形状"重合**。
`hg.db.url`（`CFG-31`）的合法取值形状**就是** `jdbc:dm://…`，而装配类测试**必须**构造"已配置库"分支的夹具 ⇒ 只要有人测装配，旧判据必红（**判据按字面不可满足** → 诱导改写测试，与纪律冲突）。

### 落地（`ARCHITECTURE.md` §3.0.2 + `tools/arch-check/**`）

1. **新增预处理：剔 Java 注释**（新脚本 `tools/arch-check/strip_java_comments.awk`；`//` 行注释 + `/* … */` 块注释含 javadoc；
   **字符串字面量内的 `/`、`/*`、`//` 不视为注释**——本项目确有 `"/**，REST 前缀 /api/v1）"` 这类字面量）。
   ⇒ 消除 javadoc 内 SQL 的误报，**生产代码无需改写**。
2. **判据 ② 拆四支并按「调用形态 / 目录」分域**：
   - `②a` 可执行连接创建（`DriverManager.getConnection(`、`Class.forName(` 且字面量含 `jdbc`）—— **适用全仓**（**锚"调用形态"而非类名/导入**）；
   - `②a2` 驱动注册（`DriverManager.registerDriver(`）—— **仅适用非 `src/test/**`**；
   - `②b` 内嵌连接串（`jdbc:` / `odbc:` / `jdbcUrl` / `jdbc_url`）—— **仅适用非 `src/test/**`**；
   - `②c` 其它关键字（`dm_jdbc` / `sqldriver` / `connectstring`）—— 适用全仓。
3. **判据未削弱，有反例**（夹具实跑，退出码 1）：生产 SQL 字面量 / 生产 `DriverManager.getConnection(` / 生产驱动类加载 /
   生产 `DriverManager.registerDriver(` / 生产内嵌连接串 → **全部命中**；**且 `src/test` 目录内的真外泄**（`getConnection(` + SQL 字面量）**仍命中**
   ⇒ **test 豁免只覆盖"配置值/假驱动夹具形态"，不是整段放行**。
4. **对照项 0 命中**：注释里的 SQL 与连接串、DAO 访问器调用、test 配置值、test 假驱动夹具、枚举字面量 `"UPDATE"`。

### 对 `docs/TEST_PLAN/**` 的影响：**0**

- **未要求改写任何测试**；`java/**` **一行未改**（判据侧消化误报）。
- **仓库基线（修订后）**：`bash tools/arch-check/scan_java_sql.sh` → **108 文件 / 0 命中 / 退出码 0**。
- ⚠️ **一处你应当知道的现场变化**：本席作业期间（2026-09-20 23:57）有他席在 `java/src/test/**` 落了新夹具
  `FakeJdbcDriver.java`（`src/test` 假 JDBC 驱动，`NFR-TEST-2` 内存假件，仅 `registerDriver`）——
  它一度使**草案判据**多出 2 处误报，本席**据此把 ②a 的锚点由"类名"改为"调用形态"**（并把 `registerDriver(` 单列 `②a2`）。
  若你的用例要在"库已装配"分支上断言路由数，**该夹具是合法的**，不需要为扫描让步。

---

## 对你的要求

1. `Q1` / `Q2` 无需你改动任何用例；`docs/TEST_PLAN/INDEX.md` 的 `G-3` 文案可按「上游已核实、无需订正」更新（你的写域）。
2. 若你要在测试计划里引用本次判据口径，请以 `ARCHITECTURE.md` **v1.5.6** §3.0.2 的 `E5` 行为准（**判「可执行代码」不判注释；`②a2`/`②b` 仅适用生产代码**）。
3. 有异议或需补充证据 → **新开 `question` 单**（不重开本单）。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-21 00:04 | tech-lead | `Q1` 实测核实 + `Q2` 判据修订落地与反例自测；出本答复件；原单置 `CLOSED` | `Q1`：已闭环无需改动 ／ `Q2`：判据侧修复（实为 5 处误报，非 2 处） |

---

## 状态

- **当前状态**：CLOSED（答复件；原单 `dev-team/inbox/tech-lead/T-S5-TESTPLAN-Q1.md` 同步置 `CLOSED`）
- **终态确认人**：技术负责人；答复对象 `test-manager`
- **终态时间**：2026-09-21
