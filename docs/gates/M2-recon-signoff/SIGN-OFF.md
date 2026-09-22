# M2 首周探路门禁 · 汇签结论（SIGN-OFF）

> 依据 `docs/TASKS.md` §0「M2 首周探路门禁（技术负责人加设）」与 §2 `T-030`：
> 三项探路（`T-026` / `T-027` / `T-028`）**全部通过**后，由**技术负责人**出具书面许可，
> 解锁 `T-031` 及后续 M2 业务开发任务。
> 记录人：**技术负责人席（`tl-t030`）** ｜ 汇签日期：**2026-09-21**
> 本记录**唯一写域 = 本文件**。本席**未改动** `docs/TASKS.md` / `docs/BOARD.md`（项目经理 `pm-batch-28` 正在写）、
> `docs/ARCHITECTURE.md` / `docs/DECISIONS/websocket-selection.md` / `docs/DATA_MODEL.md`（技术席 `tl-e3-ch011` 正在写）、
> `java/**` / `cpp/**` / `tools/**` / `web/**` / `docs/REQUIREMENTS/**`（一律只读或他人在写）。

---

## 0. 独立性声明（区分「汇签」与「独立复核」）

- 技术负责人席为探路成果的**部分产出方**（`T-026` / `T-027` 由 `#cpp` 承建、架构侧由本席定调）。
- 故三项结论**一律取自两份独立复核报告**，本席**只作汇签，不替代复核**：

  | 独立性来源 | 覆盖项 | 报告出具者 |
  |-----------|-------|-----------|
  | 独立复核 | `T-026` / `T-027`（`RSK-01` / `RSK-02`） | `te-verify-cpp-probe` |
  | 独立复核 | `T-028`（`RSK-06`） | `te-verify-t028` |

- 本席对三项结论**自行抽验**（见 §5）；**若抽验与本表不符，则不下达许可**。本次抽验结果**全部吻合**，故据以下达。

---

## 1. 门禁结论（逐项）

| 项 | 风险 | 结论（**精确用词**） | 独立复核者 | 性质 |
|----|------|---------------------|-----------|------|
| **`T-027`** | `RSK-02` | **通过** | `te-verify-cpp-probe` | 无条件 |
| **`T-026`** | `RSK-01` | **通过（技术风险已排除）** | `te-verify-cpp-probe` | **有条件**（标的物非冻结点名，见 §3 条件 1） |
| **`T-028`** | `RSK-06` | **通过（`E9` 合法不可判定，落点归 `T-040`）** | `te-verify-t028` | 不阻断 |

### 1.1 逐项结论依据（复核实测摘要）

- **`T-027`（`RSK-02`，通过）**：真实 CTP SDK **`V1.7.7`**（合并版 `UserApi`，`/home/ctpmini/ctpmini_admin/ctp_sdk`）
  头文件在 **SYSTEM include** 下**编译 + 链接**通过；`run_probe.sh` **`PASS=11 FAIL=0 RESULT: PASS`** 退码 0；
  **签名级承重**：`override` SPI + 成员函数指针绑定 6 个 API + `static_assert(is_abstract / is_polymorphic)`；
  **反证**：`-DHG_CTP_INCLUDE_AS_SYSTEM=OFF` ⇒ `ThostFtdcUserApi.h:55:46 unused parameter 'nReason' [-Werror=unused-parameter]`
  （与 C++ 标准无关，属第三方头告警洁癖）；含 `ThostFtdcUserApiDataType.h`(6631 行) / `UserApiStruct.h`(9830 行) 两个大数据头并 `sizeof` 通过。
- **`T-026`（`RSK-01`，通过·技术风险已排除）**：**三条供给链路**均构通 + 真机 TCP 收发——
  系统 `3.21.12` / `FetchContent` 源码建 `3.19.6` / `protobuf-lite`；编译参数 `-std=c++11 -Wall -Wextra -Werror`；
  `run_smoke.sh` **`PASS=13`**（系统 10 / 回退 2 / 降级 1），三段 `RESULT: PASS` 退码 0；`ctest` 3/3；
  `ldd` 产物无 DB 库（`A7`）；CMake 无 `websocketpp` / `Boost`（`A9`，`CH-006`）。
- **`T-028`（`RSK-06`，通过·`E9` 不可判定）**：`E1`–`E13` 满足 **12** / `E9` **合法不可判定 1** / `E3` 措辞已订正消解；
  独立复现 `443/0/0/0` 两次；`src/main` **零改动经 git 证明**（本席于 §5 复验 `git diff --stat 59b6f70 -- java/src/main` = 空）。

### 1.2 `T-026` 措辞二分（**不得升级**）

- `T-026` 的结论**仅覆盖**「protobuf `3.21.12` 在 `-std=c++11 -Wall -Wextra -Werror` 下的**技术可行性**」（版本 / 编译标准边界成立）。
- 因 `proto/hgcp.proto` **实测不存在**（见 §3 条件 1），本项**不得**据此宣称「**HGCP 协议已在 C++ 侧验证通过**」——
  与 `ARCHITECTURE.md` §9「⚠️ 时序缺口登记（`proto/hgcp.proto` 缺位）」**硬一致**。
- `T-028` 的 `E9` 系**合法不可判定**（被测物——含行情帧的补数批次——在 Java 侧尚不存在，属「尚无标的物」，
  **非「有而不用」**），故**不判缺陷**；落点 = **`T-040`**（HGCP 行情消费层）。

---

## 2. 所用版本与编译参数（本席实测 / 复核取证）

| 项 | 版本 / 取值 | 证据来源（本席实测或复核实测） |
|----|------------|------------------------------|
| protobuf · 主路径 | **系统包 `3.21.12`**（`libprotobuf-dev 3.21.12-15ubuntu1`、`libprotoc 3.21.12`；`protoc.path = /usr/bin/protoc`） | `dpkg-query` / `protoc --version`（本席实测） |
| protobuf · 回退链路 | **`3.19.6`**，`FetchContent` 源码构建（默认 `GIT`；或预置 tarball 按**钉死 SHA256** 校验：`9a301cf94a8ddcb380b901e7aac852780b826595075577bb967004050c835056`，5,299,501 字节） | `cpp/proto_smoke/CMakeLists.txt`（SHA256 内置）+ `t026-fallback2.log` |
| protobuf · 降级链路 | **`protobuf-lite`** | `build-lite.ctest.log`（3/3 通过） |
| CTP SDK | **`V1.7.7`**（SDK 自报），**合并版** `UserApi`；根目录 `/home/ctpmini/ctpmini_admin/ctp_sdk` | `t027-probe.log`（`sdk.api.version.trader = V1.7.7`） |
| CTP 头文件 md5 | `ThostFtdcUserApi.h` = `76c9cc10d60601d55712c491db644f46`；`ThostFtdcUserApiDataType.h` = `f2265c7bc0840b6b862963d4231238ff`；`ThostFtdcUserApiStruct.h` = `0ef090a7361c87ce975d1a38c71544a5`；`ThostFtdcMdApi.h` = `ef0ca7af98648e397f6e47ca92429f5b` | `t027-probe.log` + 本席 `md5sum` 实测（`ThostFtdcUserApi.h` 一致） |
| 编译参数（硬边界） | **`-std=c++11 -Wall -Wextra -Werror`**（`cxx.flags`；`__cplusplus == 201103`） | `A1` / `A1b` / `B3` / `B8` 断言 |
| 工具链 | `g++ (Ubuntu 15.2.0-16ubuntu1) 15.2.0`；`cmake version 4.2.3` | 本席 `g++ --version` / `cmake --version` |

> **C++ 架构红线（复核已跑，本席于 §5 复验退出码）**：`E1` `check_no_db_links.sh` = 0；`E2` `scan_cpp_sql.sh --root cpp` = 0；
> `E3` `scan_cpp11.sh --root cpp` = 0；无 `websocketpp` / `Boost`。
> **反退化（复核自设 4 变异，全部真红）**：① `std::make_unique` 在 C++11 下编译失败；② `-std=c++14` 触发源码 `#error` 守卫；
> ③ `scan_cpp11.sh` 退 1；④ `scan_cpp_sql.sh` 退 1。

---

## 3. 两项条件的显式登记（**独立成节，不得埋入正文**）

### 条件 1（技术级 · 标的物缺口）—— 影响 `T-026` 的可闭环性

- **事实（本席实测）**：`proto/hgcp.proto` **不存在**——`proto/` 目录不存在；全仓 `find . -name 'hgcp*.proto'` **唯一命中**
  `cpp/proto_smoke/proto/hgcp_smoke.proto`（md5 `cd714d160c9fa532547eda75897fed31` / 52 行）。
- 该替代物文件头已**自声明**：「**不是** M2 冻结契约」「**不承载任何协议语义**」「**不得**被任何一侧当作协议定义引用」。
- **判定**：`T-026` 所验标的物 = 等价替代物 `hgcp_smoke.proto`，**非冻结契约点名的 `proto/hgcp.proto`** ⇒
  本项**只能判「技术风险已排除」**，**不能**判「协议已验证」。
- **补救（本席下达的书面义务要求）**：在 **`T-032` 的 DoD** 增一条——
  「**真实 schema `proto/hgcp.proto` 落地后，以 `-std=c++11 -Wall -Wextra -Werror` 重跑 `cpp/proto_smoke`（指向真实 schema）并附证据**，
  方可确认 `RSK-01` 闭环」——与 `ARCHITECTURE.md` §9「时序缺口登记」② **逐字一致**。
- **归属**：**本单只登记该义务要求**；`docs/TASKS.md` 的写入归**项目经理**（`pm-batch-28`）。

### 条件 2（文档级 · 计数陈旧）—— 影响 `TASKS.md` 可复核性，不影响结论实质

- **事实（本席抽验，见 §5）**：`docs/TASKS.md` 两处计数陈旧（因 `A8`/`A9` 与 `B7b`/`B7c` 断言**后加未回写**）：

  | 位置 | 现载 | **实测** | 差异原因 |
  |------|------|---------|---------|
  | `T-026` 验收标准 5 | `PASS=11 FAIL=0` | **`PASS=13`**（系统 10 + 回退 2 + 降级 1） | 新增 `A8` / `A9` 两条断言未回写 |
  | `T-027` 验收标准 4 | `PASS=9  FAIL=0` | **`PASS=11`** | 新增 `B7b` / `B7c` 两条断言未回写 |

- **判定**：两处均**不影响 `RESULT: PASS` 的实质**（脚本逻辑与退出码语义未变，`FAIL=0`，退码 0）⇒ **不阻断铺开**。
- **归属**：**登记为待项目经理订正**（本席不改 `docs/TASKS.md`）。

---

## 4. 铺开许可（**明文下达**）

**依据**：三项探路结论（本文 §1）＋ 本汇签记录（本文）＋ 本席抽验（本文 §5，全部吻合）。

> **许可令**：自本记录出具之时起，**`T-031` 起（依 `T-030` 备注原文 = `T-031`~`T-060`）解除阻塞、可开工**。
>
> - 解除对象：`docs/TASKS.md` §1 / `BOARD.md` §2 中受「M2 首周探路门禁」阻塞的 M2 业务开发任务（`T-031`~`T-060`）。
> - **解除人 = `T-030`**（技术负责人汇签）；依 `T-030` 验收标准原文：『**全部通过后以书面许可（本任务结论）解锁 `T-031` 及后续 M2 开发任务**』。
> - 本许可**不解除** `T-042` 等对 **`T-040` / 其他前置**的既有依赖——那是任务间的正常依赖，非本门禁阻塞。
> - 遗留义务（**不阻断铺开，但须履行**）：条件 1 的 `T-032` DoD 增项（§3 条件 1）；条件 2 的计数订正（§3 条件 2）。

---

## 5. 本席抽验（**命令 + 输出**，用于 §4 许可的自证）

> 抽验口径：**以本席实测为准**；若与转述不符则**不下达许可**。以下命令均于 `2026-09-21 16:32` 前后在 `/root/myspace/hedge-guard` 执行。

**抽验 1 — `T-026` PASS 计数事实（读脚本断言点 + 分段日志）**

```
$ grep -c '_ok ' cpp/proto_smoke/ci/run_smoke.sh
13
```
（`run_smoke.sh` 的 `_ok` **调用点 13 处**，且**全部会触发** —— 逐段：system 10 处 `A2`/`A1`/`A3`/`A4`/`A1b`/`A2`ctest/`A5`/`A7`/`A8`/`A9`
＋ fallback 2 处 `A6`/`A6b` ＋ lite 1 处 `A6c` ⇒ **`PASS=13`**。）
分段日志互证：
```
$ grep -E 'PASS=|RESULT:' /root/te-cpp-probe/t026-system-final.log
PASS=10  FAIL=0
RESULT: PASS
$ grep -E 'PASS=|RESULT:' /root/te-cpp-probe/t026-fallback2.log
PASS=2  FAIL=0
RESULT: PASS
$ grep -h 'tests passed' /root/te-cpp-probe/repo/cpp/proto_smoke/build-lite.ctest.log
100% tests passed, 0 tests failed out of 3
```
⇒ **系统 10 / 回退 2 / 降级 1 = 13**。与 `TASKS.md` `T-026` 验收 5 现载「`PASS=11`」**不符** ⇒ 记为 **条件 2**。

**抽验 2 — `T-027` PASS 计数事实**

```
$ grep -n '_ok ' cpp/ctp_smoke/ci/run_probe.sh | wc -l
13
```
（`_ok` **调用点 13 处**，但其中 **2 处互斥分支** 不会同时触发：`B1` 正常路径 vs `HG_SKIP_SDK` 跳过路径取一；`B6` 「可得」vs「不可得」取一。
本轮实测为非跳过、`B6` 走「不可得」分支 ⇒ 实触发 **11** 处：`B1`/`B2`/`B3`/`B4`/`B2b`/`B7`/`B7b`/`B7c`/`B8`/`B5`/`B6` ⇒ **`PASS=11`**。）
日志互证：
```
$ grep -E 'PASS=|RESULT:' /root/te-cpp-probe/t027-probe.log
PASS=11  FAIL=0
RESULT: PASS
```
⇒ **实际 `PASS=11`**。与 `TASKS.md` `T-027` 验收 4 现载「`PASS=9`」**不符** ⇒ 记为 **条件 2**。

**抽验 3 — C++ 红线 `E3`（C++11 合规扫描）**

```
$ bash tools/arch-check/scan_cpp11.sh --root cpp ; echo "EXIT=$?"
...
E3 通过：编译参数与源码均符合 C++11（退出码 0）
EXIT=0
```

**抽验 4 — C++ 红线 `E2`（C++ SQL 外泄扫描）**

```
$ bash tools/arch-check/scan_cpp_sql.sh --root cpp ; echo "EXIT=$?"
E2 通过：C++ 侧未发现 SQL / DB 驱动痕迹（退出码 0）
EXIT=0
```

**抽验 5 — C++ 红线 `E1`（不得链接 DB 客户端库）**

```
$ bash tools/arch-check/check_no_db_links.sh ; echo "EXIT=$?"
待检 binary: 0 个 ；待检 link : 6 个
E1 通过：C++ 产物未链接 DB 客户端库（退出码 0）
EXIT=0
```

**抽验 6 — 条件 1 的标的物缺口（`proto/hgcp.proto` 不存在）**

```
$ ls -la proto/ 2>/dev/null || echo "proto/ DIR NOT EXIST"
proto/ DIR NOT EXIST
$ find . -name 'hgcp*.proto' -not -path './.git/*'
./cpp/proto_smoke/proto/hgcp_smoke.proto
$ head -5 cpp/proto_smoke/proto/hgcp_smoke.proto
// HGCP smoke schema  ——  T-026 / RSK-01 探路专用，**不是** M2 冻结契约
```

**抽验 7 — `T-028` 的 `src/main` 零改动 + C++ 面未改动（git）**

```
$ git diff --stat 59b6f70 -- java/src/main
（空 —— 无差异）
$ git status --short
 M docs/ARCHITECTURE.md
 M docs/BOARD.md
 M docs/DECISIONS/websocket-selection.md
 M docs/REQUIREMENTS/AUTH.md
 M docs/REQUIREMENTS/INDEX.md
 M docs/REQUIREMENTS/PRODUCT.md
 M docs/REQUIREMENTS/SPOT.md
 M docs/TASKS.md
?? java/WS_T028_ACCEPTANCE.md
$ git log --oneline -3
59b6f70 (HEAD -> master) chore: 引入本地 git 与基线快照（首次提交）
```
⇒ **`cpp/**` 未出现在改动列表**（C++ 探路面在探路后未被改动）；改动全部为文档面（均为他人正在写）。

---

## 6. 锚点声明（**实时 md5 + 采样时刻；以实时 `md5sum` 为准**）

> 「java」口径 = `find java/src -type f | sort | xargs md5sum | md5sum`；「docs」口径 = 直接 `md5sum <path>`。
> **采样时刻：2026-09-21 16:32（+0800）**。⚠️ 下列文档制品**可能正被他人写入**（`TASKS.md` 由 `pm-batch-28`、`ARCHITECTURE.md` 由 `tl-e3-ch011`）
> ⇒ **一律以实时 `md5sum` 为准**，本表仅为「汇签当时」快照。

| 制品 | 实时 md5 | 行数 / 文件数 | 备注 |
|------|----------|--------------|------|
| `docs/TASKS.md` | `6b535bbdfc2522ec613711334af66c1a` | 2572 行 | ⚠️ `pm-batch-28` **正在写**，**以实时为准** |
| `docs/ARCHITECTURE.md` | `952bca1f6bbcb0e93abeaace0e6c48fc` | 2746 行（v1.5.11） | ⚠️ `tl-e3-ch011` 可能仍写，**以实时为准** |
| `java/src`（树级） | `6bfe9cc3227be5c6b8c1deb1763642ce` | 318 文件 | 口径见上 |
| `cpp/**`（去 `build*/`、`_deps/`） | `3b18e93162e001dfaf4a15c705f0d296` | — | 探路源码面 |
| `cpp/proto_smoke/proto/hgcp_smoke.proto` | `cd714d160c9fa532547eda75897fed31` | 52 行 | **非冻结契约**（替代物） |
| CTP `ThostFtdcUserApi.h` | `76c9cc10d60601d55712c491db644f46` | — | `/home/ctpmini/ctpmini_admin/ctp_sdk` |

**探路证据日志（复核实测，`/root/te-cpp-probe/`，本席抽验引用）**：
`t026-system-final.log`（`PASS=10`）、`t026-fallback2.log`（`PASS=2`）、`t027-probe.log`（`PASS=11`）、
`repo/cpp/proto_smoke/build-lite.ctest.log`（3/3）。

---

## 7. 不阻断的遗留项（如实登记，**不构成本次许可的条件**）

1. **`T-028` 两项非阻塞承接（均转 `T-040`）**：`(a)` `E6` 补「有界队列丢最旧时保留的是**最新帧**」断言
   （复核自设变异 `pollFirst→pollLast` **存活** ⇒ 实现正确但**测试未覆盖该属性**）；
   `(b)` `E2` **显式声明工况**（实测稀疏 max=193 ms、相位最坏 204 ms）。
2. **`RSK-01` 的协议级闭环** 待条件 1（`T-032` 真实 schema 落地后重跑 `cpp/proto_smoke`）。
3. **`T-027` 残余风险（部署复测）**：实盘柜台 SDK 版本可能与本次探路（`V1.7.7`）不同 ⇒ 部署前按 `NFR-DEPLOY-5`
   记录实测版本并重跑 `run_probe.sh`；承接归 `T-052` 部署清单（已登记于 `T-027` 验收 6）。

---

## 8. 结论一句话

**三项探路（`T-027` 通过 / `T-026` 通过·技术风险已排除 / `T-028` 通过·`E9` 合法不可判定）全部下达「通过」，
M2 首周探路门禁达成 —— `T-031` 起（`T-031`~`T-060`）解除阻塞、可开工**（条件见 §3，遗留项见 §7）。

---

## 9. `CH-013` 追认登记（**本文唯一追加节，2026-09-22；§0–§8 一字未改**）

> **性质**：本节为**事后追加的追认登记**。**不重开门禁、不改动 `T-030` 结论**（`T-026` / `T-027` / `T-028` 的结论与 §3 两项条件**均维持原样**）。写入者 = **技术负责人席（`tl-ch013`）**；写入范围 = **仅本文件**（`docs/ARCHITECTURE.md` 同时由本席按 `CH-013` 写入）。

### 9.1 事项

**`CH-013`（HGCP 字段级 schema 补全，L3 架构级）已获用户批准并已落地**：

| 项 | 内容 |
|----|------|
| **变更编号** | **`CH-013`**（L3 架构级；提出 = 技术负责人席 `tl-proto-gap`） |
| **用户裁决** | **批准，附条件**：须先并入 `G-1`（37 条消息字段必填性）与 `G-6`（4 个复合类型内部字段）后方视为定稿 |
| **条件满足** | ✅ 同日满足（`G-1`/`G-6`/`G-7` 补齐 + 新登记 `G-8` 7 个枚举值域一并补全） |
| **落地落点** | `docs/ARCHITECTURE.md` **§4.2.8「字段级 schema」**（该文件 **v1.5.15**，2026-09-22）—— 权威内容**唯一**在此；附录 C 由〔未生效〕转〔已生效 / 提案留痕〕 |
| **变更性质** | **对 §4.2 冻结对象「线格式表示」的补全（completeness）**，**非** semantic（不改语义） |
| **不变量** | **消息集 40 / 组件错误码 62 / 线格式 `VER` `0x0100` / `FR` 计数 184 —— 均不变**（落笔后同一时刻实测） |
| **不改动的面** | `cpp/**`、`java/**`、`tools/**`、`docs/DATA_MODEL.md`、`docs/TASKS.md`、`docs/BOARD.md` —— **均未改动** |

### 9.2 与三项探路结论 / 两项条件的关系（**逐条对齐 §3，不重开**）

- **§1 三项结论**：**不变**。`CH-013` 定义的是**线格式表示**（字段号 / 类型 / 必填性 / 枚举值），与 `T-026`（protobuf 技术可行性）/ `T-027`（CTP SDK 兼容）/ `T-028`（Java WS 选型 A′）的**标的物与判据无关**。
- **§3 条件 1（`T-026` 的标的物缺口）**：**义务不变、且前置已解除但义务未完成** —— 原义务 = 「真实 schema 落地后以 C++11 重跑 `cpp/proto_smoke`」。
  - `CH-013` 只使 **schema 的「内容」可产出**（原缺口 = §4.2 只冻结语义、未定义线格式 ⇒ `T-032` **无法凭转录产出**）；**`proto/hgcp.proto` 仍不存在**（`proto/` 目录仍不存在），**转录落盘归 `T-032`（`#cpp`）**。
  - ⇒ **`T-026` 仍只能判「技术风险已排除」**，`RSK-01` **仍待 `T-032` 依 §4.2.8 落地并重跑 `proto_smoke`** 方闭环。**本席未删除、未弱化第 1 条义务**（`ARCHITECTURE.md` §4.2 冻结登记块「Schema 文件」行与 §9「时序缺口登记」已同步为「内容已定版 / 待转录落盘」）。
- **§3 条件 2（`TASKS.md` 两处计数陈旧）**：**与本 CH 无关**，**维持待项目经理订正**（本席不改 `docs/TASKS.md`）。
- **§7 遗留项**：**不变**（`T-028` 两项承接归 `T-040`；`RSK-01` 协议级闭环见上；`T-027` 部署复测归 `T-052`）。
- **§4 铺开许可**：**不受影响**（`CH-013` 不改变任何任务的阻塞关系；`T-031`~`T-060` 许可维持）。

### 9.3 本节的锚点口径

> - 本节**不自嵌**本文件落笔后的 `md5sum`（落痕文档无法自洽）⇒ **一律以实时 `md5sum` 为准**。
> - 本节为**纯追加**：`§0`–`§8` 逐字节未改（追加前本文件 254 行 / `081f37a2d907f33e06a54d4c97a1335a`）。
> - `CH-013` 的**权威内容** = `docs/ARCHITECTURE.md` **§4.2.8**（`v1.5.15`）；本节的**唯一作用**是登记「门禁结论不重开 + `RSK-01` 闭环义务仍在」。
