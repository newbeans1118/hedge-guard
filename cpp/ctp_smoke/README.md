# `cpp/ctp_smoke` —— CTP 探路件（`RSK-02`）与 `CTP_FOUND` 一致性判据

> 承载：`docs/ARCHITECTURE.md` §7.2.1（依赖清单）、§9 `RSK-02`、§3.13（判据脚本产出清单）。
> 交付基线 = **官方 CTP v6.7.13（分离式）**：`ThostFtdcTraderApi.h` + `ThostFtdcMdApi.h`（`CH-015`）。
> 📌 本 README **只作操作说明**；判据定义与**不覆盖清单**以**脚本头注释**与 `ARCHITECTURE.md` 为准。
> 📌 本目录**不写死**仓库其他活跃制品的 `md5`/行号锚点——引用一律**以实时实测为准**。

---

## 1. 本目录是什么 / 两块职责

`cpp/ctp_smoke/` 内有**两块互相独立**的东西：

| # | 归属 | 作用 |
|---|------|------|
| A | **探路件**（`T-027`，**冻结产物**） | 在 `-std=c++11 -Wall -Wextra -Werror` 下 `#include` 真实 CTP 交易头、链接真实 SDK 动态库并读其自报版本 ⇒ 客观判定「C++11 边界 + 官方分离式头名」可用（`RSK-02`）。**改动须走对应任务重做重冻结**。 |
| B | **一致性判据**（`T-128`，`T-130` 接线） | 断言「**同一组 SDK 根 / 风格配置**下，主工程与探路件给出的 `CTP_FOUND` **必须一致**」——防「两侧各自成源 ⇒ 探路结论与产品不一致」的诊断误导。 |

> ⚠️ 探路工程是**冻结产物**：本文档描述其现状，**不代表**授权就地修改。

---

## 2. 文件清单

| 文件 | 归属 | 说明 |
|------|------|------|
| `CMakeLists.txt` | A/B | 探路工程构建：头名由 `HG_CTP_HEADER_STYLE` **唯一派生**（与主工程共用 `cpp/cmake/HgCtpStyle.cmake`），打印 `[HG-CTP-FOUND] role=smoke …` 判定行 |
| `src/ctp_include_probe.cpp` | A | 最小 `#include` + 版本打印探针 |
| `src/hgcp_ctp_headers.h.in` | A | 生成头（由 `HG_CTP_*` 派生值填充） |
| `ci/run_probe.sh` | A | **探路复现脚本**（`B1`~`B8` 断言） |
| `ci/check_ctp_found_parity.sh` | B | **一致性判据脚本**（本文档 §3/§4） |
| `build*/`、`build-logs/` | — | 构建产物与取证日志（`.gitignore` 排除） |

---

## 3. 触发（如何运行）

### 3.1 一致性判据（B）—— 已在 `ctest` 中**实扫**

判据脚本 **已接线**为 `ctest` 用例 **`ctp_found_parity`**（注册点 = `cpp/CMakeLists.txt` 末尾的
`add_test(NAME ctp_found_parity …)`）。故：

```bash
# 三轨（构建目录）任一，均会执行本判据：
ctest --test-dir cpp/build-ctp   -R ctp_found_parity --output-on-failure
ctest --test-dir cpp/build-stub  -R ctp_found_parity --output-on-failure
ctest --test-dir cpp/build-fetch -R ctp_found_parity --output-on-failure
```

- 接线口径（`cpp/CMakeLists.txt`）：以**该构建目录实际生效**的 `CTP_SDK_ROOT` / `HG_CTP_HEADER_STYLE`
  传参；有 SDK 根（配置期 `CTP_FOUND=ON`）时 `--expect on`（两端须**真的** ON，非空判），
  无 SDK 根时 `--expect auto`（两端须一致 `OFF`）。
- 临时目录落**该 CMake 构建树内**（`<build>/ctp_found_parity_build`）。

### 3.2 一致性判据 —— 手动调用

```bash
# 有 SDK 根（官方解包父目录）：
bash cpp/ctp_smoke/ci/check_ctp_found_parity.sh \
     --sdk-root <CTP_SDK_ROOT> --style CLASSIC --expect on \
     --build-dir /<大分区>/hg-ctp-parity
# 无 SDK 根（预期两端一致 OFF）：
bash cpp/ctp_smoke/ci/check_ctp_found_parity.sh --build-dir /<大分区>/hg-ctp-parity
```

> ⚠️ **本机 `/tmp` 是 ~800MB tmpfs 且常满** ⇒ 手动调用应显式 `TMPDIR=/ PARITY_TMPDIR=/`
> 并给 `--build-dir /<大分区>/…`；否则可能在**副本/临时目录**拿到与真实构建无关的假红。

### 3.3 探路复现（A）

```bash
CTP_SDK_ROOT=<官方解包父目录或老式单目录> bash cpp/ctp_smoke/ci/run_probe.sh
HG_SKIP_SDK=1 bash cpp/ctp_smoke/ci/run_probe.sh   # 仅做「SDK 不可得」取证
```

---

## 4. 退码语义

### 4.1 一致性判据 `check_ctp_found_parity.sh`

| 码 | 含义 |
|----|------|
| `0` | **一致（PASS）**：两侧均产出判定行，且 `found` 与 `style`/`trader_header`/`md_header` **全字段逐字相同** |
| `1` | **失败（FAIL）**：① 两侧判定**分歧**；**或** ② **仅一侧**产出判定行（另一侧在**披露判定前**即中止）⇒ parity 无法建立 |
| `2` | **用法错误**（未知参数） |
| `3` | **不可判定**（判据未能执行）：配置在**任何一侧披露判定行之前**即终止（如 CMake 环境/前置问题） |

> ⚠️ **`1`/`2`/`3` 均非 `0` ⇒ 调用方（`ctest` / CI）一律视为失败**；仅 `0` 为通过。
> 📌 `rc=1` 的 ② 分支是 **`T-130` 收口**：此前「一侧中止」被误判为 `3`（不可判定）——
> 因处置 FATAL **先于**对侧披露（`ctp_smoke` 先于主工程求值），**探路件侧分歧**只会看到一行，
> 于是「**该红而退 3**」。现**两侧任一方中止均判红**，主工程侧与探路件侧一视同仁。

### 4.2 探路复现 `run_probe.sh`

| 码 | 含义 |
|----|------|
| `0` | 全部 `B*` 断言通过（`RESULT: PASS`）；或 `HG_SKIP_SDK=1` 主动跳过 |
| `1` | 存在 `FAIL`（含 SDK 不可得） |

---

## 5. ⚠️ 两套机制**勿混谈**：防退化守卫 ≠ 一致性判据

本目录/`cpp/cmake` 里有**两套完全独立**的机制，**互不承重**——`T-130` 复核方关键纪律：

| 机制 | 落点 | 管什么 | 触发 |
|------|------|--------|------|
| **①「旧入口防退化守卫」** | `cpp/cmake/HgCtpStyle.cmake` 的 `hg_ctp_resolve_style()` 宏末（`if(DEFINED CACHE{HG_CTP_TRADER_HEADER}) …` / `…{HG_CTP_MD_HEADER}…` 两段） | **陈旧/冲突的旧缓存项被静默接受**（`T-128` 前 `HG_CTP_TRADER_HEADER`/`HG_CTP_MD_HEADER` 曾是独立入口） | 缓存里残留**与当前风格冲突**的旧头名时 `FATAL` |
| **②「一致性判据」** | `ci/check_ctp_found_parity.sh`（=`ctest ctp_found_parity`） | **主工程与探路件是否同源**（两侧 `CTP_FOUND` 判定须逐字一致） | 一次 top-level `cmake -S cpp` 抓两行判定并比较 |

> ⚠️ **删掉守卫①不会让判据②变红**；反之亦然。二者**正交**。给下游写期望时勿把
> 「①是否生效」当成「②是否通过」的替代，或反之。

---

## 6. 已知约束 / 隐患（登记）

- **硬约束（来源 = `T-129` 同类扫描）**：若将来把 `cpp/proto_smoke` 探路件的 **protobuf fallback
  版本抬到 ≥ 3.20**，须**同法把其头目录标 `SYSTEM`**。现用 **3.19.6**——其 `port.h` 仅 40 行、
  在普通 `-I` 下 `rc=0` ⇒ **当前不受影响**（`-Werror=unused-parameter` 缺陷只在 ≥ 3.20 出现；
  3.21.12 的 `port.h` = 80 行、含 `SizedDelete`）。**本件当前无需改代码**。
- **临时目录**：本机 `/tmp` 为 ~800MB tmpfs、常满 ⇒ 判据/构建的临时目录一律放**大分区**
  （`ctest` 接线已自动落构建树内）。
- **头名单一入口**：风格只能经 `-DHG_CTP_HEADER_STYLE=CLASSIC|MERGED` 指定；**主工程仅支持
  `CLASSIC`**，`MERGED` 仅供本探路件做历史对照（主工程消费点 `cpp/cmake/HgDependencies.cmake`
  对 `MERGED` 会**显式 `FATAL` 并点名风格**，`T-130`）。
