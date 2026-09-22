# `tools/arch-check` —— 架构合规检查

> 承载：`docs/ARCHITECTURE.md` **§3.0.2**（工程化手段 `E1`–`E6`）与 **§3.13**（支撑：架构合规检查）；
> `E7` 见下方 ⚠️ 说明。
> 目的：把「靠人审查」的边界纪律固化为**可执行、可 CI 化**的判据脚本。
>
> **`E1`–`E6` 六个判据脚本由 `T-090`~`T-093` 与既有任务交付，形态经三轮独立复核收口（`T-095`）**；
> 本 README 与聚合入口 `arch-check.sh` 由 `T-031` 交付。**判据逻辑不在本文件、也不在本 README 覆盖范围**——
> 各脚本的判据细节与**已知未覆盖项**以其**脚本头注释**与 `ARCHITECTURE.md §3.0.2` 为准。
>
> ⚠️ **`E7`（`scan_hgcp_schema.py`，`T-121` 交付）的架构承载**：`§3.0.2` **现载六大手段 `E1`–`E6`**，
> **`E7` 尚未登记**（登记动作不在 `T-121` 的写权范围 ⇒ 待技术席后续补入 `§3.0.2` / `§3.13`）。
> 本 README 只作**操作说明**，**不作口径权威**；`E7` 的判据定义与**不覆盖清单**以**脚本头注释**为准。

---

## 1. 文件清单与统一退出码语义

| 文件 | 手段 | 说明 |
|------|------|------|
| `check_no_db_links.sh` | `E1` | C++ 链接检查（不得链接 DB 客户端库） |
| `scan_cpp_sql.sh` | `E2` | C++ 侧 SQL / 驱动痕迹静态扫描 |
| `scan_cpp11.sh` | `E3` | C++11 合规扫描（编译参数 + 禁用特性） |
| `scan_lock_free.sh` | `E4` | 交易路径无锁 / 无同步 I/O 扫描 |
| `scan_java_sql.sh` | `E5` | Java「dao 之外不得出现 SQL」扫描 |
| `strip_java_comments.awk` | `E5` | Java 注释预处理器（`scan_java_sql.sh` 调用） |
| `check_frontend.sh` | `E6` | 前端零构建链 + 无直连扫描 |
| `scan_hgcp_schema.py` | `E7` | HGCP 字段级 schema：`ARCHITECTURE.md §4.2.8` ↔ `proto/hgcp.proto` 一致性（`T-121`；**单文件 Python3、无第三方依赖**） |
| `arch-check.sh` | **聚合入口** | 调用 E1–E7，任一非 0 ⇒ 整体非 0（本 README §2） |
| `README.md` | — | 本文件 |

**统一退出码语义（各判据脚本，`T-095` 收口）**：

| 码 | 含义 |
|----|------|
| `0` | 通过；**或**「**默认**路径」不存在（组件未铺开）→ 打印 `SKIP` |
| `1` | 命中（架构偏离） |
| `2` | 用法 / 前置错误 —— **显式传入**的路径不存在亦归 `2`（**不得**把「显式路径拼错」静默当 `SKIP`；靠「是否显式传参」区分，非靠「路径是否存在」） |

---

## 2. 聚合入口：`arch-check.sh`

**用法**：在仓库**任意位置**执行均可（脚本由自身位置推导仓库根 `../..`）。

```bash
bash tools/arch-check/arch-check.sh      # 或 ./tools/arch-check/arch-check.sh（已 chmod +x）
bash tools/arch-check/arch-check.sh -h    # 帮助
```

**行为口径**：

- 先 `cd` 到**仓库根**，再以**各脚本的默认扫描根**逐一调用（**不显式传 `--root`**）。
  理由：`--root` 的「显式不存在 ⇒ `2`」是防「路径拼错被静默当 SKIP」的护栏；
  对**合法缺位**（如 `web` 尚未铺开）应让脚本走其**设计好的默认 `SKIP` 分支**。
- ⚠️ **`E4` 的默认扫描根不再是 `cpp/src/trading`**（该目录**不存在**）：自 `T-120` 起为**三目录集合取并集**
  `cpp/src/{order_engine,risk_engine,risk_state}`（依据 `docs/ARCHITECTURE.md §3.0.2` 的 `E4` 脚注）；
  **三目录全部不存在**时才 `SKIP`。本入口**仍不传 `--root`**（调用口径不变）⇒ `E4` 现在**实际扫描**。
- **只做「调用 + 汇总退出码」**，不实现、不改写任何判据逻辑。
- **解释器按扩展名分派**（`T-121` 起）：`<脚本名>.py` ⇒ `python3`，其余 ⇒ `bash`。
  **`E1`–`E6` 全为 `*.sh` ⇒ 调用形态与参数逐字节不变**（无入参、默认根）；
  分派只决定「用哪个解释器」，不改任何判据的入参、默认根或退出码语义。

**聚合退出码（最高严重度优先，数值即严重度序）**：

| 码 | 条件 |
|----|------|
| `0` | 七个脚本全部返回 `0` |
| `1` | 无脚本返回 `2`，但至少一个返回 `1`（架构偏离） |
| `2` | 至少一个脚本返回 `2`（用法/前置错误 —— 判据未能真正执行，属更严重缺口） |

**接线**：

- **`ctest` 前置**：`cpp/CMakeLists.txt` 将 `arch-check.sh` 注册为 ctest **fixture SETUP**（`arch_check`），
  业务测试经 `FIXTURES_REQUIRED hg_arch_check` 依赖它 ⇒ `ctest` 时**先跑** `arch_check`。
- **`mvn verify` 前置**：`java/pom.xml` 以 `exec-maven-plugin` 的 `exec:exec` 绑定 `validate` 阶段调用本脚本
  ⇒ `mvn verify`（及任何生命周期）**先跑**架构合规门禁，非 0 即中断构建。

**行为证明（变异法；隔离副本 `/hgtmp/mut`，`2026-09-21` 实测）**：

| 场景 | 变异 | 实测整体退出码 |
|------|------|----------------|
| M0 基线 | 无 | **0**（`通过(0)=6 命中(1)=0 错误(2)=0`） |
| M1 注入必然违例 | `cpp/src/hg_mut_probe.cc` = `const char* hg_mut_probe = "SELECT id FROM t_user";` | **1**（`E2 失败：C++ 侧发现 1 处 SQL / DB 驱动痕迹`；汇总 `命中(1)=1`） |
| M2 判据脚本缺位 | 移除 `scan_java_sql.sh` | **2**（`错误：判据脚本不存在：…/scan_java_sql.sh`；汇总 `错误(2)=1`） |
| M3 复位复跑 | — | **0** |

> 结论：**任一脚本非 0 ⇒ 整体非 0**，且 `1`（偏离）与 `2`（工具/前置错误）可区分。

---

## 3. 各判据脚本：用法 / 退出码 / 自测三条（可复跑记录）

> 以下「自测三条」的命令与观察结果均为 **`2026-09-21` 本机实跑**（隔离夹具在 `/hgtmp/st/`，落在 `/` 下而非 `/tmp`）。
> 复跑方法：把命令逐条执行即可；`rc=` 为脚本退出码。
>
> 📌 **`E1`–`E6` 各表为交付时点（`T-090`~`T-095` / `T-031`）记录，本节未重跑**；`E7` 表为 **`2026-09-22` `T-121` 实测**（隔离夹具在 `/hgt121iso/`，同样落在 `/` 下）。

### E1 `check_no_db_links.sh`

- **用法**：`check_no_db_links.sh [--binary <path>] [--link-root <dir>]`
  - `--binary`：待 `ldd` 的产物（默认 `build/hg-trading`，并自动发现名为 `hg-trading` 的文件）
  - `--link-root`：检索 CMake `link.txt` 的根（默认 `.`）
- **判据**：`ldd(<binary>)` + `<link-root>/**/link.txt` 中命中 DB 客户端库名（`dmjdbc`/`dmdpi`/`mysql`/`sqlite`/`odbc`/`pq`/`oci`）⇒ 失败。
- **已知未覆盖**：静态库内**间接**引入的 DB 代码；非 ELF 产物（无法 `ldd`，跳过不报红）。详见脚本头。

| # | 命令 | 期望 | 实测 `rc` | 关键输出 |
|---|------|------|-----------|----------|
| 1 | `(cd /hgtmp/st/e1/skip && bash tools/arch-check/check_no_db_links.sh)` | `SKIP` | **0** | `SKIP: 未发现 C++ 交易组件产物（binary 与 link.txt 均不存在…）` |
| 2 | 夹具 `e1/hit/CMakeFiles/x.dir/link.txt` 含 `-lmysqlclient` → `(cd …/e1/hit && bash …/check_no_db_links.sh --link-root .)` | 命中 | **1** | `E1 失败：C++ 产物链接了 DB 客户端库，共 1 处（退出码 1）` |
| 3 | `bash …/check_no_db_links.sh --link-root /no/such/dir` | 用法错误 | **2** | `错误：--link-root 目录不存在（显式路径）：/no/such/dir` |

### E2 `scan_cpp_sql.sh`

- **用法**：`scan_cpp_sql.sh [--root <dir>]`（默认 `cpp`）
- **判据**：`<root>/**/*.{cpp,cxx,cc,c,hpp,h,hh}`（排除 `build*`/`_deps`/`third_party`）内出现 **SQL 语句形态**（`①`）或**驱动/连接串形态**（`②a`/`②b`/`②c`）⇒ 失败。
- **口径**：先剔 **C++ 注释**（**保留字符串字面量** —— 与 `E3`/`E4` 故意不对称）。

| # | 命令 | 期望 | 实测 `rc` | 关键输出 |
|---|------|------|-----------|----------|
| 1 | 夹具 `e2/hit/a.cpp` = `const char* s = "SELECT id FROM t_user";` → `scan_cpp_sql.sh --root …/e2/hit` | 命中 | **1** | `E2 失败：C++ 侧发现 1 处 SQL / DB 驱动痕迹（退出码 1）` |
| 2 | 夹具 `e2/ok/b.cpp` = 纯标识符 `insert`/`update`/`drop` → `--root …/e2/ok` | 不命中 | **0** | `E2 通过：C++ 侧未发现 SQL / DB 驱动痕迹（退出码 0）` |
| 3 | 夹具 `e2/bldroot/build/c.cpp` 含真 SQL → `--root …/e2/bldroot` | 不命中（构建目录已排除） | **0** | `E2 通过：…（退出码 0）` |

### E3 `scan_cpp11.sh`

- **用法**：`scan_cpp11.sh [--root <dir>]`（默认 `cpp`）
- **判据**：`①` 编译参数（`-std=c++11`/`CMAKE_CXX_STANDARD 11` + `-Wall -Wextra -Werror`，无冲突标准）；`②` 禁用特性（`make_unique`/`std::optional`/`string_view`/`if constexpr`/`std::variant`/结构化绑定）。先剔注释 + 字符串/字符字面量。
- **已知未覆盖/过报**：行尾 `\` 续行、多行/自定义定界符原始串（如实声明，**不作「零漏报」表述**）。

| # | 命令 | 期望 | 实测 `rc` | 关键输出 |
|---|------|------|-----------|----------|
| 1 | 夹具 `e3/hit/a.cpp` = `auto p = std::make_unique<int>(1);` → `scan_cpp11.sh --root …/e3/hit` | 命中 | **1** | `判据 ② 失败：发现 1 处 C++11 之外的禁用特性` → `E3 失败…（退出码 1）` |
| 2 | 夹具 `e3/ok/b.cpp` = 注释含 `禁用 std::make_unique` + 字符串 `"std::optional"` → `--root …/e3/ok` | 不命中 | **0** | `E3 通过：编译参数与源码均符合 C++11（退出码 0）` |
| 3 | `scan_cpp11.sh --root /no/such/dir` | 用法错误 | **2** | `错误：--root 目录不存在（显式路径）：/no/such/dir` |

### E4 `scan_lock_free.sh`

- **用法**：`scan_lock_free.sh [--root <dir>]`（默认 = **三目录集合取并集** `cpp/src/{order_engine,risk_engine,risk_state}`；`--root` 显式给出则为**单目录**语义）
- **判据**：`<root>` 内锁原语（`mutex`/`lock_guard`/`unique_lock`/`condition_variable`）或同步 I/O（按**调用形态**：`::send(` / 行首 `send(` / `.send(` / `->send(` 等）。先剔注释 + 字符串/字符字面量。
- **已知未覆盖/过报**：无动态分配、`compare_exchange` 循环、异常路径（仅 `WARN`）；通用名 `read`/`write` 的成员调用；跨行返回类型定义 ⇒ 误报；`expr * name(` ⇒ 漏判；限定名定义 ⇒ 误报。详见脚本头与 `§3.0.2`。
- **扫描根映射**：见本 README **§4**。

| # | 命令 | 期望 | 实测 `rc` | 关键输出 |
|---|------|------|-----------|----------|
| 1 | 夹具 `e4/hit/a.cpp` = `::send(fd, 0, 0, 0);` → `scan_lock_free.sh --root …/e4/hit` | 命中 | **1** | `E4 失败：交易路径发现 1 处锁 / 同步 I/O（退出码 1）` |
| 2 | 夹具 `e4/ok/b.cpp` = `struct S { int read(int); };` + `s.read(3);` → `--root …/e4/ok` | 不命中（定义 + 成员调用不判） | **0** | `E4 通过：交易路径未发现锁 / 同步 I/O…（退出码 0）` |
| 3 | `scan_lock_free.sh --root /no/such/dir` | 用法错误 | **2** | `错误：--root 目录不存在（显式路径）：/no/such/dir` |

### E5 `scan_java_sql.sh`

- **用法**：`scan_java_sql.sh [--root <dir>]`（默认 `java`）
- **判据**：`java/**/*.java`（排除 `**/dao/**`）内 `①` SQL 语句形态 / `②a` 连接创建调用 / `②a2` 驱动注册（仅生产）/ `②b` 内嵌连接串（仅生产）/ `②c` 关键字，且**非** `③` 豁免的 DAO 访问器调用 ⇒ 失败。
- **口径**：先剔 Java 注释（`strip_java_comments.awk`），**保留字符串字面量**（真外泄）。

| # | 命令 | 期望 | 实测 `rc` | 关键输出 |
|---|------|------|-----------|----------|
| 1 | 夹具 `e5/hit/A.java` 内嵌 `"SELECT id FROM t_user WHERE status = ?"` → `scan_java_sql.sh --root …/e5/hit` | 命中 | **1** | `E5 失败：dao 之外发现 1 处 SQL/驱动痕迹（退出码 1）` |
| 2 | 夹具 `e5/ok/B.java` = `// SELECT…`（注释）+ `configItemDao.insert(item, operatorId)` → `--root …/e5/ok` | 不命中 | **0** | `E5 通过：dao 之外未发现 SQL（退出码 0）` |
| 3 | `scan_java_sql.sh --root /no/such/dir` | 用法错误 | **2** | `错误：--root 目录不存在（显式路径）：/no/such/dir` |

### E6 `check_frontend.sh`

- **用法**：`check_frontend.sh [--root <dir>]`（默认 `web`）
- **判据**：`①` 存在 `package.json`/`node_modules`/`dist`/`*.bundle.js`/`*.min.js`；`②` 源码出现 `jdbc`/`127.0.0.1:<端口>`/`dsn` ⇒ 失败。
- **已知未覆盖**：`localhost:<端口>` 与 `ws://` 绝对地址；**间接**直连（常量拼接 IP）；判 `②` 按**原文**（未剔注释）。

| # | 命令 | 期望 | 实测 `rc` | 关键输出 |
|---|------|------|-----------|----------|
| 1 | 夹具 `e6/hit1/package.json` 存在 → `check_frontend.sh --root …/e6/hit1` | 命中 | **1** | `BUILD-CHAIN: …/e6/hit1/package.json` → `E6 失败…（退出码 1）` |
| 2 | 夹具 `e6/hit2/x.js` 含 `jdbc:dm://127.0.0.1:5236/HG` → `--root …/e6/hit2` | 命中 | **1** | `…/e6/hit2/x.js:1:` → `E6 失败…（退出码 1）` |
| 3 | `(cd /hgtmp/st/e6/empty && bash …/check_frontend.sh)`（默认路径 `web` 不存在） | `SKIP` | **0** | `SKIP: … web … 退出码 0` |

### E7 `scan_hgcp_schema.py`（`T-121`）

- **用法**：`scan_hgcp_schema.py [--arch <file>] [--proto <file>]`
  - `--arch`：架构文档（默认 `docs/ARCHITECTURE.md`）；`--proto`：线格式定义（默认 `proto/hgcp.proto`）
- **立项理由**：proto3 字段号**一经发布不可回收**（§4.2.8.1）；独立复核观察到把 `MsgError.rule_id`
  字段号 `4`→`7`（**合法但不符 §4.2.8**）后测试套件 **14 个用例全绿** ⇒ 现有测试**不锚定字段号**，
  既往唯一保证机制是**一次性人工机械化差集、非持续性**。本判据把该差集固化为**持续性门禁**。
- **判据（六类，全量机械比对、禁目测抽样）**：
  `①` 消息集（40 顶层 + 10 复合，**含声明顺序**）；`②` 字段三元组 **名 ↔ 号 ↔ 类型**（含 `repeated`，逐字段含序）；
  `③` 必填性（表体 `必`/`可`/`条件` ↔ proto 字段尾注释首 token）；`④` 枚举（集合 + **逐值** + **前缀剥离**；
  `MsgType` 的 40 个逐值**取自 §4.2.3**）；`⑤` 自洽计数（`214 = 38+77+99`、复合 `66`、字段号 `1..n` 连续、
  各节「小计」/标题条数 ↔ 实算）；`⑥` 命名派生（§4.2.3 冻结名 → TitleCase 加回 `Msg`/`Cmd`/`Evt`）。
- **锚定口径**：按 `^##### 4.2.8.x` **章节锚点求段**，**禁固定行号**（`ARCHITECTURE.md` 是**活跃制品**）。
- **⚠️ 已知不覆盖（**显式声明，不得含糊带过**）**：见脚本头 `N1`–`N8`。要点：
  §4.2.8.5 的**条件语义**（只判清单行存在且与 `条件` 字段集合一致）；**散文注记**（只锚表体）；
  **附录 C 留痕**（`C.3.1`–`C.3.5` 含与 §4.2.8 **近乎同名同形的表格副本**，但两侧**均明载「非权威」**
  ⇒ **只锚 `§4.2.8.x`、不扫附录 C**，故「§4.2.8 已改、附录 C 未同步」**不被拦下**）；
  **冻结-不变量**（消息集 `40` / 错误码 `62` / `VER 0x0100` / `FR 184`）**不在本条范围**；
  §4.2.4 字段级说明；`syntax`/`package`/`option` 等非字段内容；线格式编码。
- **取舍（显式披露）**：制品出现本判据**不认识的形态**（未解析表体行 / 未解析字段条目 / proto 新语法
  如 `optional`·`map<>`·`oneof`·`reserved`）⇒ **退 `2`**（当作前置错误、**不得静默跳过**）；
  反之 message/enum 体内的**纯注释行与空行允许**（计数并打印，不作静默吞并）。
- **`ComponentRole` 白名单**：§4.2.8.7 明载该枚举**保留** §4.2.3 冻结值名 `ROLE_JAVA` / `ROLE_CPP`、
  **不统一加枚举名前缀**（仅 `*_UNSET` → `COMPONENT_ROLE_UNSET`）⇒ 判据内置**唯一例外**，不扩散。

| # | 命令 | 期望 | 实测 `rc` | 关键输出 |
|---|------|------|-----------|----------|
| 1 | 现行制品（`python3 tools/arch-check/scan_hgcp_schema.py`） | 通过 | **0** | `E7 通过：§4.2.8 与 proto 逐条一致（消息 40+10、字段 214+66、枚举 28）` |
| 2 | 反例①：proto 侧 `MsgError.rule_id` `4`→`7`（**合法但不符 §4.2.8**） | 命中 | **1** | ``[②] `MsgError.rule_id` 字段号：ARCH 4 ≠ proto 7`` |
| 3 | 反例②：proto 体内注入 `optional string unknown_new_syntax = 99;` | 前置错误 | **2** | `proto 行 339（message MsgHeartbeat）：字段形态未解析` ⇒ `E7 用法/前置错误…（退出码 2）` |
| 4 | 反例③：`--proto /no/such/hgcp.proto` | 用法错误 | **2** | `错误：--proto 路径不存在（显式路径）：/no/such/hgcp.proto` |
| 5 | 反例④（同族，仅列示）：`docs/ARCHITECTURE.md` 表体行字段条目缺字段号 | 前置错误 | **2** | `4.2.8.2 行 …：字段条目形态未解析 ⇒ '`echo_ts_ms`(int64·必)'` |
| 6 | 反例⑤（同族）：§4.2.8 侧字段号 `4`→`7` | 命中 | **1** | ``[⑤] 顶层 MsgError：字段号非 `1..n` 连续`` + ``[②] …字段号：ARCH 7 ≠ proto 4`` |
| 7 | 反例⑥（同族）：proto 字段缺必填性尾注释 | 命中 | **1** | ``[③] `MsgHelloAck.param_version`：proto 字段缺必填性尾注释`` |
| 8 | 反例⑦（同族）：`ServerState.REPLAYING` 值 `2`→`9` | 命中 | **1** | ``[④] 枚举 `ServerState` **取值**不一致：ARCH [0,1,9,3,4] ≠ proto [0,1,2,3,4]`` |
| 9 | 反例⑧（同族）：`§4.2.8.2` 小计行 `38`→`39` | 命中 | **1** | `[⑤] 4.2.8.2：小计行 39 ≠ 实算 38` |
| 10 | 反例⑨（同族）：默认路径不存在（空目录内执行） | `SKIP` | **0** | `SKIP: 默认路径 docs/ARCHITECTURE.md 不存在（组件未铺开）` |

> **反例②为「未解析行必须 fail」的**关键取证**：退 `2`（**非静默通过**），
> 即新语法**不会**被静默漏判 —— 这正是本判据立项要消灭的失效模式（前序临时脚本的 `__UNPARSED__` 坑）。
> **聚合传播实测**（隔离副本）：注入反例① ⇒ 整体退 `1`（`命中(1)=1`、`E7 退出码 1`）；
> 注入反例② ⇒ 整体退 `2`（`用法/前置错误(2)=1`、`E7 退出码 2`）。

---

## 4. `E4` 扫描根与六模块目录的映射（**已固定**；权威口径见 `ARCHITECTURE.md §3.0.2` 的 `E4` 脚注）

- **权威定义在架构**：`§3.0.2` 的 `E4` 脚注（`v1.5.12` 裁定）定「交易路径」= **执行在 T4 交易线程上的代码**；
  `E4` 的**默认扫描根** = **三目录集合取并集** = `cpp/src/order_engine` + `cpp/src/risk_engine` + `cpp/src/risk_state`
  （`scan_lock_free.sh` 自 **`T-120`** 起默认根即此）。本 README 只作**操作说明**，不作口径权威。
- C++ 交易组件按 `§3.1–§3.6` 分为**六个模块目录** `cpp/src/{ctp_adapter,order_engine,risk_engine,risk_state,flow_store,hgcp}`；
  **不存在** `cpp/src/trading/`（旧路径**已废**）。
- **显式排除的另三个模块**（同步 I/O 由架构明确许可、非交易路径线程 ⇒ 纳入即**误报**）：
  - `flow_store`：`append + fsync` 由 **T7 `flow_persist`** 承担（§2.4.2 / §4.5.2）；
  - `hgcp`：socket `send`/`recv` 由 **T5/T6/T8** 承担（§2.4.2）；
  - `ctp_adapter`：SPI 回调属 **T2/T3**（§3.1）。
- **SKIP 条件**：**默认根三目录全部不存在**时打印 `SKIP` 并退出 `0`；**任一存在即实扫**（并集，不因另两个缺位而 `SKIP`）。
  **显式 `--root <dir>` 保持单目录语义**：以该目录为唯一根；**显式路径不存在 ⇒ 退出 `2`**（既有护栏不变）。
- **实测对照（证明排除非空转，`2026-09-21` 隔离探针）**：把 `::fsync(fd)` / `::send(fd,…)` / `::recv(fd,…)` 分别置于
  `flow_store` / `hgcp` / `ctp_adapter`，以**默认根**扫描 ⇒ 退出 `0`（探针**不进扫描面**，`待扫描文件: 6 个` 不变）；
  同一批探针以**父目录 `--root cpp/src`** 扫描 ⇒ 命中 **3** 处、退 `1`。⇒ 候选「父目录 `cpp/src`」**实测证伪、不予采纳**。

---

## 5. 仓库基线实跑

```bash
bash tools/arch-check/arch-check.sh
# 2026-09-22（T-121 接入 E7 后）实测：
# 汇总：通过(0)=7  命中(1)=0  用法/前置错误(2)=0  →  arch-check 通过（退出码 0）
# E1..E7 逐条退出码均 0；E4 = **实扫**（默认根三目录集合；`待扫描文件: 6 个`、0 命中）；
# E7 = **实扫**（`ARCH 顶层消息 40 条 / 复合 10 条 / 枚举 28 个；proto message 50 个 / enum 28 个`）。
# 2026-09-21（T-121 之前）为：通过(0)=6  命中(1)=0  用法/前置错误(2)=0。
```

**`E7` 失败注入下的聚合传播（隔离副本 `/hgt121iso`，`2026-09-22` 实测）**：

| 场景 | 注入 | 实测整体退出码 |
|------|------|----------------|
| M0 基线 | 无 | **0**（`通过(0)=7 命中(1)=0 错误(2)=0`） |
| M1 `E7` 内容漂移 | proto `MsgError.rule_id` `4`→`7` | **1**（`命中(1)=1`；`E7 退出码 1`） |
| M2 `E7` 形态未解析 | proto 体内 `map<string,int32> m = 9;` | **2**（`用法/前置错误(2)=1`；`E7 退出码 2`） |
| M3 复位复跑 | — | **0** |

> ⚠️ **接线口径**：`E7` 经**聚合入口**传播 ⇒ `cpp/CMakeLists.txt` 的 `arch_check` fixture 与
> `java/pom.xml` 的 `mvn validate` exec **两侧零改动**（二者只调用本入口、不枚举判据脚本）。

`ctest` 前置接线（`cpp/CMakeLists.txt`，`2026-09-21` 实测**三轨**）：

> 📌 **`rsk02` 的注册条件 —— 真实自变量 = `CTP_SDK_ROOT`（**不是** `FetchContent`）**：`cpp/ctp_smoke/CMakeLists.txt:87` 判
> `if(CTP_SDK_ROOT AND EXISTS "${CTP_SDK_ROOT}/${HGCP_CTP_TRADER_HEADER}")` ⇒ `CTP_FOUND=ON`；注册守卫在 `:128` `if(CTP_FOUND)`。
> ⇒ **三轨数字差异只由该构建目录的 `CTP_SDK_ROOT` 决定**。`CTP_USE_FETCH_DEPS` **只取 protobuf**（实测 `cpp/build-fetch/_deps/` 仅
> `protobuf-build` / `protobuf-src` / `protobuf-subbuild`，**无任何 CTP**）。各构建目录 `CMakeCache.txt` 实测：
> `build-ctp` `CTP_SDK_ROOT=/home/ctpmini/ctpmini_admin/ctp_sdk`；`build-stub` **空**；`build-fetch` **同上（非空）**。

| 轨（构建目录） | `CTP_SDK_ROOT` | 实测通过 | 说明 |
|----------------|----------------|----------|------|
| `cpp/build-ctp` | **非空** | **6/6** | 含 `rsk02.ctp_header_cxx11_compat`；`hgcp_ctp_probe` 的 `ldd` 链外部 `…/ctp_sdk/libthostuserapi.so` / `libthostmduserapi.so` |
| `cpp/build-fetch` | **非空** | **6/6** | 同左；**与 `FetchContent` 无关**（它只取 protobuf）⇒ 该 `6/6` 为**条件性结果** |
| `cpp/build-stub` | **空** | **5/5** | `CTP_FOUND=OFF` ⇒ `rsk02` **不注册** |

> ⚠️ **`6/6` 是条件性结果，不是恒真**：其**前置条件** = 该构建目录的 `CTP_SDK_ROOT` 指向含 `ThostFtdcUserApi.h` 的目录（`⇒ CTP_FOUND=ON`）。
> **无 CTP SDK 的环境下三轨一律退化为 `5/5`**（`rsk02` 不注册）。

```
# cpp/build-ctp 实测（节选）：
1/6 Test #1: arch_check ............... Passed 5.60 sec     ← fixture SETUP，先跑
6/6 Test #6: hg.skeleton_modules ...... Passed 0.01 sec     ← FIXTURES_REQUIRED hg_arch_check
100% tests passed, 0 tests failed out of 6
```

**链接证据（`E1` 相关，`cpp/build-ctp` 实测，`2026-09-21`）**：`ldd cpp/build-ctp/src/hg-trading` 输出 **3 行**（`linux-vdso.so.1` / `libc.so.6` / `ld-linux`）——
**无** `libthost*.so`、**无** `-lpthread`；`link.txt` 实际链接 = 六个模块静态库 + `/usr/lib/x86_64-linux-gnu/libprotobuf.so`（**仅 protobuf**）。

> 📌 **证据锚点口径**：本 README **不写死** md5 / 耗时类锚点（易随构建与并发写入失效）；
> 引用任何制品一律**以实时 `md5sum` 为准**。上文实测数字均为 **`2026-09-21` 本机实跑**所得，复跑命令已内联。
