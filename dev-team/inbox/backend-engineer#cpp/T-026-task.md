---
ticket: T-026
type: task
from: project-manager
to: backend-engineer#cpp
priority: P0
created: 2026-09-20T22:00:00+08:00
refs: [docs/ARCHITECTURE.md#9, docs/ARCHITECTURE.md#7.1.5, docs/REQUIREMENTS/NONFUNCTIONAL.md]
---

## 事由

**M2 首周探路任务，已提前投递**（`R-12` 裁决后 S4 开工，M2 探路不依赖 M1 交付，故提前启动以消除 M2 环境不确定性）。
`T-026` 验证 **protobuf 在 `-std=c++11 -Werror` 下能否构通**（`RSK-01`）——**未过不得铺开 M2 业务开发**（由 `T-030` 汇签并下达铺开许可）。

## 任务详情

| 项 | 内容 |
|----|------|
| 编号 | `T-026` |
| 标题 | `RSK-01` protobuf 在 C++11 下空壳构通验证 |
| 负责人 | `backend-engineer#cpp` |
| 优先级 | **P0** |
| 依赖 | **无**（M2 协议已 **FROZEN**，可直接开工） |
| 估时 | **8 人时** |
| 里程碑 | M2（**探路前置**，先于 `T-031` 起业务开发） |
| 需求追溯 | `RSK-01`、`ARCHITECTURE.md` §7.1.5、`NFR-COMPAT-1/2 @ REQUIREMENTS/NONFUNCTIONAL.md` |
| 上游产物（唯一事实源） | `docs/ARCHITECTURE.md` **v1.3 / md5 `641b481b…`**（§7.1.5 构建与依赖、§9 风险表 `RSK-01`、§4.2 HGCP v1.0 FROZEN）；`proto/hgcp.proto`（**唯一定义，40 消息 / 62 错误码**）；`PROJECT.md` §5.1 / §6（C++11 硬约束） |
| 产出 | `cpp/proto_smoke/`（空壳验证工程）+ 结论记录（**回写本工单「处理记录」**） |

## 要求

1. **任务目标**：在 `-std=c++11 -Wall -Wextra -Werror` 下，用 `proto/hgcp.proto` 仅编译一个 **HGCP 空消息**并完成收发，完成构建构通验证。
2. **验收标准（可客观判定，缺一不可）**：
   1. 在 `-std=c++11 -Wall -Wextra -Werror` 下，用 `proto/hgcp.proto` 仅编译一个 **HGCP 空消息**并完成收发，构建成功；
   2. 首选 protobuf `3.21.12`，失败回退 `3.19.6`，回退链路在 CI 中可复现；
   3. 记录实际可用版本与编译参数；两侧 protobuf 大版本一致（`3.x`，构建时校验）；
   4. 若两版本皆不可用 → **提 `CH-xxx`（L3）** 申请 `protobuf-lite` 或降级 `3.17.3`，**不得静默改技术栈**。
3. **必读上游章节**：`ARCHITECTURE.md` §7.1.5（构建链）、§4.2（HGCP v1.0，`FROZEN`）、§9 `RSK-01`；`PROJECT.md` §5.1（技术栈）/ §6（C++11 硬约束）。
4. **红线**：**禁止**单侧修改 `proto/hgcp.proto`（M2 协议已冻结，任何变更须走 `CH-xxx`(L3)，技术负责人批准并重跑 `T-057`）。
5. **第 7 项 · 待办工单（必办，不得静默跳过）**：你的 inbox 中已有未闭环工单 **`T-ARCH-002-architecture.md`**（`from: tech-lead`，`type: architecture`，P0，**当前状态 `OPEN`**）——C++ 交易组件架构交底，含必读章节、`Q-16`/`Q-15`/`Q-3`/`Q-4`/`Q-1`/`Q-13` 落库与判定口径、红线（`E1`/`E2`/`E4`）、协议专项测试 T1–T11、安全要求；其「回复要求」第 1 条明确要求**在 M2 D1 完成 `RSK-01`/`RSK-02` 探路并回写该工单「处理记录」**。
   - **本次请一并处理**：完成 `T-026`/`T-027` 后，把结论（成功 / 失败 / 回退版本）**同时回写 `T-ARCH-002` 工单的「处理记录」**；
   - 发现与文档不一致处 → 以 `question` 工单提出（**不得自行偏离**）；
   - 若本次确不能处理，**必须在本工单「处理记录」写明暂缓理由与预期处理时间**。
6. **纪律提醒**：`cpp/proto_smoke/**` 内你为唯一写者；**不得改** `proto/hgcp.proto`、`docs/gates/`、`docs/REQUIREMENTS/`、`docs/ARCHITECTURE.md`、`docs/DATA_MODEL.md`、`docs/TASKS.md`、`docs/BOARD.md`。

## 证据 / 复现

不适用（`type: task`）。结论记录中请附**构建命令、protobuf 版本、编译参数、CI 复现路径**。

## 变更评估

不适用（`type: task`）。若两版本 protobuf 皆不可用，**另提 `CH-xxx`（L3）**，本工单不承载变更评估。

## 回复要求

1. 完成后将**实际可用 protobuf 版本 + 编译参数 + 构建结果 + CI 复现路径**追加到本工单「处理记录」，并把「状态」置 `CLOSED`。
2. **同步**回写 `T-ARCH-002-architecture.md` 的「处理记录」（该工单明确要求 M2 D1 回写探路结论）。
3. 结论同时回报**项目经理**（`T-030` 汇签前需要 4 项探路结论齐备）。
4. 阻塞 1 小时内无法自行解除即上报。

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 22:00 | project-manager | 提前投递 M2 首周探路工单（`R-12` 裁决后） | 待 `backend-engineer#cpp` 开工 |
| 2026-09-20 19:30 | backend-engineer#cpp | 完成 `RSK-01` 探路：`-std=c++11 -Wall -Wextra -Werror` 下 HGCP 空消息编译 + 真机收发 | ✅ **通过** —— **可锁定 `protobuf 3.21.x` 作为长期版本线**，**无需** `CH-xxx`；附带 2 项须 `T-030` 汇签确认的发现（见下） |

### 探路结论（`RSK-01`）

> ⚠️ **基线复核（开工前实测 + 收尾复核）**：本工单「任务详情」引用 `ARCHITECTURE.md` **v1.3 / `641b481b…` / 2418 行**；
> **开工时实测现行基线为 v1.5 / `9feae927…` / 2516 行**（`CH-006` 承载层收敛为单一 HGCP 通道、C++ 移除 `websocketpp`/`Boost::system`；
> `CH-007` 协议标称 **v1.0.1**、**消息集 40 / 错误码 62 不变**、线格式 `VER` 仍 `0x0100`）。
> **收尾复核**：技术负责人已据本次探路把架构推进至 **v1.5.1 → v1.5.2**（`32babcacd0…` / 2546 行），
> 其中 **v1.5.1 已采纳本次 `RSK-01`/`RSK-02` 的全部结论**（见下「结论被采纳情况」）。
> **锚点以实时 `md5sum` 为准。**
> **本条探路结论不受影响**（`RSK-01` 只关乎 protobuf 生成代码能否在 C++11 下构通，与通道承载、依赖库集合无关）。
> `§7.1.5`（首选 `3.21.12` / 回退 `3.19.6`）与 `§7.2.1`（C++ 侧依赖 = CTP SDK + protobuf）在 v1.5 中**未变**。
> 教训：**派单文案中的架构锚点可能已陈旧，开工前必须实时 `md5sum` 复核**。

**结论被采纳情况（`ARCHITECTURE.md` v1.5.1，技术负责人已落文）**：
- ② `RSK-01`/`RSK-02` 状态更新为「**已通过（探路实证）**」，并写明 **protobuf `3.21.12` 构通成功 → 锁定 `3.21.x`**（回退 `3.19.6`）；
- ③ 登记两条施工口径：**CTP SDK include 以 SYSTEM 头引入**、**M2 业务代码一律 `ByteSizeLong()`**；
- ④ **登记时序缺口**：`proto/hgcp.proto` 仓库中尚不存在 → `RSK-01` **只能判「技术风险已排除」**，并写明「真实 schema 落地后须以 C++11 重跑 `cpp/proto_smoke` 并附证据」。
→ 即本工单「发现 ①」「发现 ②」与「回退链路陷阱」均已进入架构登记，**`RSK-01` 的闭环条件已被明确书面化**。

**结论一句话**：`protobuf 3.21.12` 在 `-std=c++11 -Wall -Wextra -Werror` 下**构通并完成真机收发**；`RSK-01` 所设想的「3.21.x 可能仍要求 C++14」**不成立**。`ARCHITECTURE.md` §7.1.5「选项 A：锁定 3.21.x」得到实测支持，**可锁定为长期版本线**。

**记录项（实际可用版本与编译参数）**：

| 项 | 实测值 |
|----|--------|
| protobuf 版本（构建期 / 头文件 / protoc） | **3.21.12**（`Protobuf_VERSION` / `GOOGLE_PROTOBUF_VERSION=3021012`→`3.21.12` / `libprotoc 3.21.12`） |
| 来源 | **系统包** `libprotobuf-dev 3.21.12-15ubuntu1`（provider = `system-package`） |
| 运行时变体 | `full`（`libprotobuf.so.32.0.12`）；另实测 `lite`（见 A6c） |
| protoc 路径 | `/usr/bin/protoc` |
| 编译参数 | **`-std=c++11 -Wall -Wextra -Werror`**（`CMAKE_CXX_STANDARD 11` + `CXX_STANDARD_REQUIRED ON` + `CXX_EXTENSIONS OFF`） |
| 编译期事实 | `__cplusplus == 201103`（源码内 `#if __cplusplus != 201103L` → `#error` 硬闸；运行时亦打印 `cxx.standard.is_cxx11 : YES`） |
| 编译器 | `g++ (Ubuntu 15.2.0-16ubuntu1) 15.2.0` |
| 构建类型 / 结果 | `RelWithDebInfo` / **成功**，`ctest` 3/3 通过 |
| 大版本一致性 | 构建期 `FATAL_ERROR` 校验 `Protobuf_VERSION` major == **3**（§4.2.5「C++ 与 Java 必须同一大版本」）→ 实测通过 |

**取证日志留存位置**（`cpp/.gitignore` 已排除构建产物与 `.log`，故这些日志**仅存于工作区、不入库**；如需长期归档请随 `T-030` 汇签材料一并固化）：
- 主路径：`cpp/proto_smoke/build.{configure,build,ctest}.log`
- 回退链路：`cpp/proto_smoke/build-fallback.{configure,build,self-test}.log`
- 降级链路：`cpp/proto_smoke/build-lite.{configure,build,ctest}.log`
- CTP 探路：`cpp/ctp_smoke/build-logs/{configure,build,ctest,configure-nonsystem,build-nonsystem}.log`

> 说明：`cpp/proto_smoke/build-fallback/`（693 MB，含 protobuf 3.19.6 源码与目标文件）**已清理**——它可由上面第 ①/③ 条命令**完整重建**，且其结论已固化在 `build-fallback.*.log` 与本工单中。回退链路的源码 tarball 若 `/tmp/pb396_full.tgz` 已失效，可用命令内注释中的 codeload URL 重新拉取并核对 `SHA256=9a301cf94a8ddcb380b901e7aac852780b826595075577bb967004050c835056`。

**核心取证命令与输出摘要（主路径）**：

```bash
# 1) 配置（构建期大版本校验）
cmake -S cpp/proto_smoke -B build -DCMAKE_BUILD_TYPE=RelWithDebInfo
# -- [RSK-01] 采用系统 protobuf 3.21.12（full 运行时）
# -- [RSK-01] 大版本校验通过：protobuf 3.21.12（major=3）provider=system-package

# 2) 生成 + 编译（零 warning/error）
cmake --build build -j"$(nproc)"
# [ 20%] [RSK-01] protoc --cpp_out 生成 hgcp_smoke.pb.{h,cc}
# [ 60%] Building CXX object .../generated/hgcp_smoke.pb.cc.o
# [100%] Built target hgcp_proto_smoke

# 3) 运行取证（空消息 + 加严消息，均经 127.0.0.1 真机 TCP 往返）
./build/hgcp_proto_smoke --self-test
# cxx.standard.requested      : c++11
# cxx.standard.__cplusplus    : 201103
# cxx.standard.is_cxx11       : YES
# protobuf.headers.version    : 3021012 -> 3.21.12
# protobuf.cmake.found        : 3.21.12
# protobuf.cmake.major.required: 3
# cxx.flags                   : -std=c++11 -Wall -Wextra -Werror
# [empty-roundtrip]  127.0.0.1:38283 空消息往返成功，双向 payload 均为 0 字节
# [fields-roundtrip] payload 46 字节，字段校验与逐字节回显一致
# RESULT: PASS

# 4) ctest
ctest --test-dir build --output-on-failure     # 3/3 Passed
```

**验收标准逐条对照**：

| 验收标准 | 判定 | 证据 |
|---------|------|------|
| ① `-std=c++11 -Wall -Wextra -Werror` 下编译 HGCP 空消息并完成收发 | ✅ **达成** | `--empty-roundtrip` 经 `127.0.0.1` 真机 TCP 往返，双向 payload 均为 **0 字节**；`--fields-roundtrip` 加严覆盖 §4.2.4 字段类型（string/enum/uint32/int32/int64/repeated bytes）逐字节回显一致 |
| ② 首选 `3.21.12`，失败回退 `3.19.6`，回退链路在 CI 中可复现 | ✅ **达成** | 首选 **实测通过**（系统包 3.21.12）；回退 **`3.19.6` 源码构建实测通过**（`FetchContent` + URL + 钉死 SHA256，HGCP 空消息收发成功、`ctest` 全绿）；降级分支 `protobuf-lite` **亦实测通过**（A6c） |
| ③ 记录实际可用版本与编译参数；两侧大版本一致（`3.x`）构建时校验 | ✅ **达成** | 见上表；`configure_file` 把 CMake 观测值固化进二进制，`--print-config` 同时打印"构建期观测"与"编译期事实"；大版本校验为 `FATAL_ERROR` |
| ④ 两版本皆不可用 → 提 `CH-xxx`（L3） | **不适用** | 首选版本可用，`RSK-01` 未触发；**无需** `CH-xxx` |

**CI 复现路径**（一条命令跑全流程；亦可分阶段）：

```bash
# ① 全流程：主路径 + 回退链路 + 降级链路（**一条命令，实测 PASS=11 FAIL=0 → RESULT: PASS**）
#    注：该合并跑在新增 A8/A9 之前完成（A8/A9 与 provider 无关，见 ② 的 PASS=10）。
HG_PROTOBUF_FETCH_VIA=URL HG_PROTOBUF_TARBALL=<protobuf-3.19.6-源码-tarball> \
  bash cpp/proto_smoke/ci/run_smoke.sh

# ② 主路径（离线可用，不拉外网）       实测：PASS=10 FAIL=0 → RESULT: PASS
HG_STAGE=system bash cpp/proto_smoke/ci/run_smoke.sh
# ③ 回退链路（protobuf 3.19.6 从源码构建）  实测：PASS=2  FAIL=0 → RESULT: PASS
HG_STAGE=fallback HG_PROTOBUF_FETCH_VIA=URL HG_PROTOBUF_TARBALL=<tarball> \
  bash cpp/proto_smoke/ci/run_smoke.sh
# ④ 降级链路（protobuf-lite）            实测：PASS=1  FAIL=0 → RESULT: PASS
HG_STAGE=lite bash cpp/proto_smoke/ci/run_smoke.sh
```

全流程实测汇总（`RESULT: PASS`，`PASS=11  FAIL=0`；A8/A9 为后续新增，已在主路径单独实测 `PASS=10`）：

```
[PASS] A2  主路径构建成功（-std=c++11 -Wall -Wextra -Werror）
[PASS] A1  compile_commands.json 中 -std=c++11 -Wall -Wextra -Werror 均实际生效
[PASS] A3  实际可用 protobuf = 3.21.12（首选版本，来自系统包）
[PASS] A4  构建期大版本=3 校验通过
[PASS] A1b __cplusplus == 201103（C++11 是编译期事实，而非仅构建请求）
[PASS] A2  ctest 3/3 通过（空消息收发 + 加严消息收发 + 编译标准事实）
[PASS] A5  -Werror 真实生效（反证命中 -Werror=deprecated-declarations）
[PASS] A7  ldd 无任何 DB 客户端库（附录 B 红线 1）
[PASS] A6  回退链路 v3.19.6 构通并收发成功
[PASS] A6b 回退链路 ctest 全绿
[PASS] A6c protobuf-lite 降级链路构通且 ctest 全绿（libprotobuf-lite.so.32）
```

新增（后于合并跑加入，已在主路径单独实测通过）：
```
[PASS] A8  cpp/proto_smoke 源码树无前端构建链产物（E6；范围与排除项已自证）
[PASS] A9  CMake 无 websocketpp / Boost（CH-006：C++ 侧仅 CTP SDK + protobuf）
```

### C++ 侧最终依赖清单（`CH-006` 后，实测口径）

| 项 | 内容 |
|----|------|
| **硬依赖（全部）** | **① CTP SDK**（ThostFtdc 合并版 UserApi，自报版本 **`V1.7.7`**）；**② protobuf**（首选 **`3.21.12`**，回退 `3.19.6`，降级 `protobuf-lite`） |
| 系统能力（非第三方库） | POSIX Threads / BSD sockets（`find_package(Threads)`） |
| 测试 | `ctest`（CMake ≥3.16 自带） |
| **已移除** | `websocketpp`、`Boost::system`（`CH-006`，用户裁决 HTTP/WebSocket 全部在 Java 端实现） |

**源码级自证**（`cpp/**`，排除 `build*/`）：
- `find_package(...)` 仅命中 `Protobuf` / `Threads`；链接库仅 `protobuf::libprotobuf(-lite)` / `Threads::Threads` / CTP SDK 动态库；
- 外部 `#include <>` 仅 `google/protobuf/stubs/common.h` + POSIX / 标准库头文件；
- 全库检索 `websocketpp` / `Boost::system` / `find_package(Boost` → **仅 2 处命中，且均在本 CI 脚本自身**（`ci/run_smoke.sh`、`ci/run_probe.sh`，是"断言其不存在"的守卫字符串），**无任何真实使用**；
- 该结论已固化为 CI 断言 **`A9`（主路径）** 与 **`B7c`（CTP 探路）**，两处均 `PASS`。

> **E6 扫描纪律（来自 `#java` 的实测提醒）**：CMake `FetchContent` 拉下的 protobuf 源码树自带 `js/package.json`（`_deps/protobuf-src/js/package.json`），**朴素的 E6 扫描会误报**"引入构建链"。故本工装的 E6 自检（`A8`/`B7b`）**显式声明扫描范围与排除项**（排除 `build*/`、`_deps/`、`third_party/`），符合本项目「0 命中类结论必须自证扫描范围与排除项」的纪律。`tools/arch-check/check_frontend.sh` 落地时请沿用该排除项。

断言全部落在**可客观判定的事实**上（不是"应该没问题"）：
`A1` `-std=c++11 -Wall -Wextra -Werror` 在 `compile_commands.json` 中**实际生效**（不是"请求了"）；
`A1b` `__cplusplus == 201103`；
`A2` 构建成功 + `ctest` 全绿（空消息 + 加严消息）；
`A3` 实际版本 = `3.21.12`；
`A4` 大版本 == `3`；
`A5` **`-Werror` 真实生效的反证**（故意使用 3.21 已弃用 API 必须编译失败）；
`A6` 回退 `3.19.6` 从源码构建 + 收发成功；`A6b` 回退链路 `ctest` 全绿；
`A6c` `protobuf-lite` 降级链路构通 + `ctest` 全绿；
`A7` `ldd` 无任何 DB 客户端库（附录 B 红线 1）。

### 发现（`T-030` 汇签需确认的 2 项）

**发现 ①（P1，影响 M2 全部业务代码）：`proto/hgcp.proto` 在仓库中不存在**

- 事实：`find . -name '*.proto'` 在仓库内**零命中**。`ARCHITECTURE.md` §4.2 把它定义为「**唯一定义**，两侧同源生成」的冻结物，`TASKS.md` 中它是 **`T-032` 的产出**（`T-032` 依赖 `T-031`，而 `T-031` 在 `T-030` 下达许可前 `BLOCKED`）。
- 因此：`T-026` 要求「用 `proto/hgcp.proto` 仅编译一个 HGCP 空消息」**在时序上不可满足** —— 冻结的是**协议语义**（§4.2 全节）与**「唯一定义」这条纪律**，而 schema 文件本身要等 `T-032` 才产出。
- **本项目处置**：在 `cpp/proto_smoke/proto/hgcp_smoke.proto` 放**等价替代物**（`syntax="proto3"` / `package hgcp.v1` / 空消息 `HgcpEmpty` + 加严用例 `HgcpSmokeFields`），并在文件头**显式声明**它是「等价替代物、不承载任何协议语义、不得被任何一侧当作协议定义引用」，同时把这一事实固化进二进制可打印字段（`schema.is.frozen.contract : NO (...)`）。
- **影响判定**：`RSK-01` 的技术风险是「protobuf **生成的 C++ 代码**能否在 C++11 下构通」——该风险由 `.proto` 的**语法特性**决定，与"具体有多少条消息、字段叫什么"无关。故本次结论对 `RSK-01` **有效**。
- **残留（须 `T-030` 处置）**：`RSK-01` 只能判为「**技术风险已排除**」，**不能**判为「冻结 schema 已验证」。建议在 `T-032` 的 DoD 中**显式增加一条**：「`proto/hgcp.proto` 落地后，以 `-std=c++11 -Wall -Wextra -Werror` **重跑** `cpp/proto_smoke`（把 `HGCP_SMOKE_SCHEMA_FILE` 指向真实 schema）并附证据」。
- 我**未**自行创建 `proto/hgcp.proto`：它不在我的写作边界内（`cpp/**` + 本人工单），且 §4.2 冻结规则与附录 B 红线 ③ 明令禁止单侧触碰协议产物 —— 若你认为应由本工单代产，请以 `CH-xxx`/`question` 明确授权，我再执行。

**发现 ②（P2，影响 M2 全部业务代码）：protobuf 3.21 的已弃用 API 会被 `-Werror` 直接拦下**

- 事实：`MessageLite::ByteSize()` 自 3.21 起标记 `deprecated`（`message_lite.h:381`）。在 `-Wall -Wextra -Werror` 下即 **编译失败**：`error: 'int google::protobuf::MessageLite::ByteSize() const' is deprecated [-Werror=deprecated-declarations]`。**这是本次探路真实撞到并修复的第一条**（首次构建失败即此因）。
- 处置：改用 `ByteSizeLong()`。该事实已写进 `hgcp_proto_smoke_main.cpp` 的代码注释，并把「故意用 `ByteSize()` 必须编译失败」固化 CI 反证 `A5`。
- **请 `T-030` 确认**：M2 业务代码**一律使用 `ByteSizeLong()`**；**不得**为了通过构建而整体加 `-Wno-deprecated-declarations`（那会同时屏蔽掉其他真问题）。若技术负责人另有口径，请明确指示。

### 未执行项（如实声明，不得据以判定通过）

| 项 | 状态 | 原因与证据 |
|----|------|-----------|
| **`protobuf 3.19.6` 源码拉取并构建**（回退链路 ②） | ✅ **已执行并通过** | `HG_STAGE=fallback HG_PROTOBUF_FETCH_VIA=URL HG_PROTOBUF_TARBALL=<源码 tarball>` → `FetchContent` populate（**钉死 SHA256 校验通过**）→ `SOURCE_SUBDIR cmake` 配置 → **从源码构建 `protobuf 3.19.6`** → HGCP 空消息**真机收发成功**、`ctest` 全绿（`RESULT: PASS`）。见下「回退链路实测记录」 |
| **`protobuf-lite` 降级链路**（`RSK-01` 应对③） | ✅ **已执行并通过** | `cmake -DHG_PROTOBUF_LITE=ON` → 构建成功、`ctest` 3/3 通过，`ldd` 显示 `libprotobuf-lite.so.32`；代码生成走 `protoc --cpp_out=lite:`（实测 protoc 3.21.12 支持该前缀） |
| **`3.17.3` 降级**（`RSK-01` 应对③ 备选） | ❌ 未执行 | 无必要：首选 `3.21.12` 与回退 `3.19.6` **均已实测通过**，`protobuf-lite` 降级亦通过，该备选不构成必要路径 |
| **`proto/hgcp.proto` 真实 schema 验证** | ❌ 未执行 | 该文件不存在（见发现 ①） |

### 回退链路实测记录（`protobuf 3.19.6`，从源码构建）

```bash
HG_STAGE=fallback HG_PROTOBUF_FETCH_VIA=URL \
HG_PROTOBUF_TARBALL=/tmp/pb396_full.tgz \
  bash cpp/proto_smoke/ci/run_smoke.sh
# [INFO] 回退链路使用本地缓存 tarball：/tmp/pb396_full.tgz（校验用 CMake 内置钉死 SHA256）
# protobuf.cmake.provider     : fetchcontent-3.19.6
# protobuf.cmake.found        : 3.19.6
# cxx.flags                   : -std=c++11 -Wall -Wextra -Werror
# [empty-roundtrip]  127.0.0.1:40567 空消息往返成功，双向 payload 均为 0 字节
# [fields-roundtrip] payload 51 字节，字段校验与逐字节回显一致
# RESULT: PASS
# [PASS] A6 回退链路 v3.19.6 构通并收发成功
# [PASS] A6b 回退链路 ctest 全绿
# PASS=2  FAIL=0   →  RESULT: PASS
```

**本次回退链路实测中撞到并修复的 3 个真实陷阱**（已全部写入 `cpp/proto_smoke/CMakeLists.txt` 的注释与守卫，`T-031` 若复用 `FetchContent` 请直接沿用）：

1. **`protobuf-cpp-<ver>.tar.gz` 不可用于 CMake `FetchContent`**：该 release 资产是 autotools 打包的 C++-only 发行包，**无可用 CMake 入口**。
2. **protobuf 的 CMake 入口位置随版本变化**：**v3.19.6 无顶层 `CMakeLists.txt`**（实测 HTTP 404；入口在 `cmake/CMakeLists.txt`）→ **必须** `SOURCE_SUBDIR cmake`；而 **v3.21.12 有顶层 `CMakeLists.txt`**（HTTP 200）→ 无需。漏配的后果是 `FetchContent_MakeAvailable` **只 populate 源码、不配置任何目标**，表现为"configure 静默成功、后续才炸"，极难定位。
3. **protobuf 3.19.6 的 `cmake_minimum_required(VERSION 3.1.3)` 被 CMake 4.x 拒绝**（< 3.5 兼容已移除）→ 必须显式 `CMAKE_POLICY_VERSION_MINIMUM=3.5`。

**网络受限的如实说明**：本机到 `github.com` 的带宽实测约 **15–20 KB/s**。首次尝试的 `GIT` 浅克隆（`--no-single-branch`，protobuf 仓库 ~100 MB 级）在 2 分 11 秒内仅取到 112 KB 即停滞；改走 release 资产 `protobuf-cpp-3.19.6.tar.gz`（4.8 MB）下载完成后，才发现陷阱 1/2。最终改用 **codeload 的 git-archive 源码 tarball**（5.3 MB，`https://codeload.github.com/protocolbuffers/protobuf/tar.gz/refs/tags/v3.19.6`，SHA256 `9a301cf9…`）成功。**CI 建议**：URL 方式配合 `HG_PROTOBUF_TARBALL` 走"离线缓存 + 钉死 SHA256"（缓存只省下载、不省校验），尽量避免在线浅克隆。

> ⚠️ **边界声明**：本次 `RSK-01` 的判定对象是**技术风险**（protobuf 生成代码在 C++11 下能否构通），**不是**对冻结 schema 的验证。
> 不得据此宣称「HGCP 协议已在 C++ 侧验证通过」——协议验证属 `T-032` / `T-057` 范围。

### 阻塞 / 剩余风险

| # | 内容 | 影响 | 建议 |
|---|------|------|------|
| R1 | `proto/hgcp.proto` 缺位（发现 ①） | `RSK-01` 只能判「技术风险已排除」；且 `T-031`/`T-032` 开工前两侧**无同源 schema** | `T-032` DoD 增加「真实 schema 重跑 `cpp/proto_smoke`」 |
| R2 | 已弃用 API 被 `-Werror` 拦下（发现 ②） | 若 M2 业务代码沿用旧写法，构建必失败 | 统一 `ByteSizeLong()`；禁止整体关 `deprecated` 告警 |
| R3 | 系统 protobuf 为 **Ubuntu 发行版包**，与 `ARCHITECTURE.md` §7.2.1 的「CMake `FetchContent`」获取方式**不同** | 长期版本线锁定需明确"靠系统包还是靠 FetchContent" | 建议：CI 与部署**优先系统包 3.21.12**（可复现、可审计），`FetchContent` 仅作离线/异常回退；具体口径请 `T-030` 裁决 |
| R4 | `libprotobuf` 运行库大版本 = **32**（`libprotobuf.so.32.0.12`），与头文件语义版本 `3.21.12` 的映射关系为 32 = 21+11 | 部署清单若按 `.so.32` 记录会与 `3.21.12` 混淆 | 部署文档统一记录 **`3.21.12`**（语义版本），`.so.32` 仅作运维排查线索 |

## 状态

- **当前状态**：**CLOSED**（`RSK-01` 探路**通过**，可锁定 `protobuf 3.21.x` 版本线；证据见上；`T-ARCH-002` 已同步回写）
- **终态确认人**：项目经理（`T-030` 汇签由 tech-lead 裁决）
- **终态时间**：2026-09-20（探路执行完成；终态签发待 `T-030`）

