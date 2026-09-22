# T-032 交付说明 · 缺口清单 · 建议 schema（供 `CH-xxx` 裁定）

> ## ⚠️ 权威性声明（务必先读）
> **本文（含 §3 的「建议形态」与 proto 草稿）是 `be-t032` 的**建议稿，**非权威定义**；
> 权威字段级形态待**架构席裁定**（编排者 2026-09-22 裁定：**(A) 不采、(B) 生效** ——
> 字段号属**线格式且不可回收**，分配字段号/补类型/定枚举名值系**协议设计决定**，
> 不得由实现方落笔）。**本文不在 `proto/` 下、不得被当作冻结契约引用**；
> `ARCHITECTURE.md §4.2`（HGCP, FROZEN）为唯一权威语义源。
>
> 归属：`backend-engineer#cpp`（T-032）｜产出路径：`cpp/src/hgcp/**`
> 上游权威：`docs/ARCHITECTURE.md` §4.2（HGCP v1.0.1, FROZEN）、§4.3.2
> 结论摘要：**帧层/鉴权/心跳/退避/补数/绑定 已落地并全绿**；
> **`proto/hgcp.proto` 与元协议 payload 编解码 因「字段级 schema 在架构中缺失」而阻塞**，
> 依纪律**不自行发明**，缺口与建议形态如下（行号以现行 `ARCHITECTURE.md` 计，引用前请重测 md5）。
>
> 变更（2026-09-22，`be-t032b`，承载层冲突处置）：保留 **`tcp` + `session`**（schema-free 承载层）、
> **新增 `server_loop`**（二者的**生产侧装配**）、**删除死代码** `server*` / `socket_util*`；
> `tests/transport_test.cc` 改为**真实回环 socket E2E** 并接线。详见 **§7**（含具名接缝评估：**不移植**及理由）。

---

## 1. `proto/hgcp.proto` ↔ `ARCHITECTURE.md §4.2` 逐项覆盖对照

| 维度 | 覆盖 | 架构出处 | 可落笔？ |
|------|------|---------|---------|
| 消息名 + 类型码（40 种） | ✅ 40/40 | §4.2.3 表 A/B/C，`:790`–`:843`；统计 `:845` | 可（已转录进 `protocol.h`） |
| 字段**名**（40 消息） | ✅ 40/40 | §4.2.3「关键字段」列 | 可（已转录） |
| 字段**类型 + 必填性** | ⚠️ **仅 3/40** | §4.2.4 `:852`–`:899`（`CMD_PLACE_ORDER`/`CMD_CONFIRM_ORDER`/`EVT_PRE_TRADE_REJECT` 全表；`EVT_TRADE_FILL` `:901` 仅一句幂等键说明） | **否**（其余 37 条只有名字） |
| **字段号（field number）** | ❌ **0/40** | §4.2 全篇无 `= N`；§4.2.5「兼容演进」`:915` 只述"新增字段分配新号" | **否**（proto3 必需） |
| 枚举**类型名 + 数值** | ❌ | §4.2.5 `:909` 仅规定"显式赋值、0 保留 `*_UNSET`"；取值**标签**散落于 §4.2.3 单元格内联 | **否** |
| FLAGS 位（bit0..bit4） | ✅ | §4.2.2 `:775` | 可（已转录为 `FlagBits`） |
| 帧格式字段/字节/CRC 覆盖域 | ✅ | §4.2.2 `:770`–`:781` | 可（已落地 `frame.h/.cc`） |
| 错误码（62） | ✅ | §4.3.2 `:1103`–`:1178` | 可（已转录为 `ErrorCode`） |
| 序列化规定（proto3/金额 string/时间 int64/分片） | ✅（规定在，非 schema） | §4.2.5 `:903`–`:917` | — |

**⇒ 结论**：`ARCHITECTURE.md` 只冻结了**语义**（消息集 / 字段名 / 取值域 / 序列化规定），
**未冻结字段级 schema（字段号 + 类型）**；而 §4.2.1 `:708` 又把「唯一定义」委派给
`proto/hgcp.proto`（该文件从未产出）。故落笔该文件属**协议级补全**，须裁定/`CH-xxx`。

## 2. 文档内部不一致（附带发现，供架构席一并裁定）

| # | 事项 | 出处 | 说明 |
|---|------|------|------|
| I-1 | `HELLO.token_version` **不在** §4.2.3 的 `MSG_HELLO` 关键字段列 | §4.2.6 `:948` vs §4.2.3 `:794` | §4.2.6 明述「`HELLO` 携带 `token_version`」，但 §4.2.3 的 HELLO 字段列**无**该字段；两者需一致 |
| I-2 | 帧超限错误码双记 | §4.2.2 `:779`（记 `0x0104`）vs §4.3.2 `:1107`/`:1122`（`0x0104` 含"帧超 1 MiB"、`0x0308`=`ERR_FRAME_TOO_LARGE`） | 本实现 `error_code_for(kFrameTooLarge)=0x0308`（取更具体者）；若不采纳请裁定 |
| I-3 | §4.3.2 标题「共 50 个」 | §4.3.2 `:1097` | 表体与统计（`:1178`）均为 **62**，`:1182` 已自注 50/59 为笔误；本实现取 **62** |
| I-4 | `component_state`（`MSG_HEARTBEAT`）取值域未给 | §4.2.3 `:797` | `server_state` 给了 4 值，`component_state` **0 值** |

## 3. 元协议 9 种消息 —— 缺口明细 + **建议形态（草稿，非权威）**

> ⚠️ 本节为**非权威建议稿**（详见文首声明）。
> ⚠️ 下表「建议形态」是**待架构席裁定的建议**，**不是**冻结契约；采纳须走 `CH-xxx` 并同步
> `ARCHITECTURE.md` 与 `proto/hgcp.proto`（§4.2 `:713`）。字段集严格照抄 §4.2.3，不新增/不删。

| 消息（类型码） | 架构给出 | **缺口** | 建议类型 | 建议号 |
|----------------|---------|---------|---------|-------|
| `MSG_HELLO` `0x0001`（`:794`） | 字段名 6 个 | 全部字段类型；`token_version` 归属（I-1） | `proto_version:uint32`, `component_id:string`, `component_role:enum{ROLE_JAVA,ROLE_CPP}`, `auth_token:string`, `client_boot_id:string`, `last_received_seq:uint64` | 1..6 |
| `MSG_HELLO_ACK` `0x0002`（`:795`） | 字段名 6 个 + `server_state` 4 值 | 各字段类型；`server_state` 数值 | `accepted:bool`, `server_proto_version:uint32`, `session_id:string`, `server_state:enum{UNSET,NOT_READY,REPLAYING,READY,STOPPING}`, `param_version:uint64`, `reason_code:uint32` | 1..6 |
| `MSG_AUTH_FAIL` `0x0003`（`:796`） | 字段名 2 个 | 类型 | `reason_code:uint32`, `detail:string` | 1,2 |
| `MSG_HEARTBEAT` `0x0004`（`:797`） | 字段名 3 个 | 类型；`component_state` 取值域（I-4） | `sent_ts_ms:int64`, `last_received_seq:uint64`, `component_state:enum{...}` | 1..3 |
| `MSG_HEARTBEAT_ACK` `0x0005`（`:798`） | 字段名 2 个 | 类型 | `echo_ts_ms:int64`, `server_ts_ms:int64` | 1,2 |
| `MSG_ACK` `0x0006`（`:799`） | 字段名 5 个（`result=0` 成功） | 类型 | `ack_msg_id:uint64`, `result:int32`, `reason_code:uint32`, `detail:string`, `applied_version:uint64` | 1..5 |
| `MSG_ERROR` `0x0007`（`:800`） | 字段名 6 个 | 类型 | `err_code:uint32`, `err_msg:string`, `related_msg_id:uint64`, `rule_id:string`, `current_value:string`, `threshold_value:string` | 1..6 |
| `MSG_RESUME_REQ` `0x0008`（`:801`） | 字段名 3 个 | 类型；`session_id` 类型（与 HELLO_ACK 须一致） | `last_received_seq:uint64`, `session_id:string`, `max_resume_count:int32` | 1..3 |
| `MSG_RESUME_DATA` `0x0009`（`:802`） | 字段名 4 个（**`frames`=repeated bytes** 明示） | 类型 | `from_seq:uint64`, `to_seq:uint64`, `is_last:bool`, `frames:repeated bytes` | 1..4 |

建议 `proto/hgcp.proto` 头部（草稿）：

```proto
// ⚠️ 建议稿 —— 待 CH-xxx 采纳后方可作为冻结契约置于 proto/hgcp.proto
syntax = "proto3";
package hgcp.v1;

// 消息类型（§4.2.3，40 种；此处仅示元协议 9 种）
enum MsgType {
  MSG_TYPE_UNSET = 0;               // §4.2.5：0 保留 *_UNSET
  MSG_HELLO        = 0x0001;
  MSG_HELLO_ACK    = 0x0002;
  MSG_AUTH_FAIL    = 0x0003;
  MSG_HEARTBEAT    = 0x0004;
  MSG_HEARTBEAT_ACK= 0x0005;
  MSG_ACK          = 0x0006;
  MSG_ERROR        = 0x0007;
  MSG_RESUME_REQ   = 0x0008;
  MSG_RESUME_DATA  = 0x0009;
  // ... 其余 31 种（CMD_*/EVT_*）照 §4.2.3 转录
}

enum ServerState { SERVER_STATE_UNSET=0; NOT_READY=1; REPLAYING=2; READY=3; STOPPING=4; }
enum ComponentRole { COMPONENT_ROLE_UNSET=0; ROLE_JAVA=1; ROLE_CPP=2; }

message MsgHello {
  uint32 proto_version = 1;
  string component_id = 2;
  ComponentRole component_role = 3;
  string auth_token = 4;
  string client_boot_id = 5;
  uint64 last_received_seq = 6;
}
// ... 其余 8 条元协议同表；CMD_* / EVT_* 字段级定义**须架构席补全后方可落笔**。
```

## 4. 本任务已落地（不依赖消息字段 schema 的部分）

| 能力 | 架构依据 | 文件 |
|------|---------|------|
| 帧头 32B / 大端 / CRC32(IEEE) / 1 MiB 上限 / 五类拒绝 | §4.2.2 | `include/hg/hgcp/frame.h`、`src/frame.cc` |
| 消息类型 40 + 错误码 62 + FLAGS 位 | §4.2.3 / §4.3.2 | `include/hg/hgcp/protocol.h`、`src/protocol.cc` |
| SHA-256（自实现）+ 常量时间比较 | §4.2.6 `:946`（依赖清单 §7.2.1 无密码库） | `include/hg/hgcp/crypto.h`、`src/crypto.cc` |
| 令牌摘要校验 + 失败审计留痕 + 零令牌日志 | §4.2.6 `:945`–`:949` | `include/hg/hgcp/auth.h`、`src/auth.cc` |
| 心跳 1000ms/3 次/≤3s、退避 500ms→8s/无限/≥10 告警 | §4.2.6 `:951`–`:966`、NFR-REL-2 | `include/hg/hgcp/timing.h`、`src/timing.cc` |
| 补数缓冲 65536 / 单批 ≤500 / 缺口 → `0x0306` | §4.2.6 `:969` | `include/hg/hgcp/resume_buffer.h`、`src/resume_buffer.cc` |
| 回环白名单 `127.0.0.1` / 拒绝启动 / 单连接拒 `0x0302` | §4.2.1 `:736`–`:740` | `include/hg/hgcp/endpoint.h`、`src/endpoint.cc` |
| **帧信封**（头 + 不透明 body）+ **流式切帧**（`FrameStreamReader`） | §4.2.2 | `include/hg/hgcp/frame.h`、`src/frame.cc` |
| **payload 编解码接缝**（`PayloadCodec` / `OpaquePayloadCodec`） | §4.2.5（序列化规定） | `include/hg/hgcp/payload_codec.h`、`src/payload_codec.cc` |
| **回环 TCP 承载**（监听/接受/收发/fd 原语；端口 0 = 内核分配） | §4.2.1 `:736`–`:740` | `include/hg/hgcp/tcp.h`、`src/tcp.cc` |
| **连接会话**（帧切分 / SEQ / 心跳 / 退避 / 补数 / 单连接；schema-free） | §4.2.6 / §4.2.1 | `include/hg/hgcp/session.h`、`src/session.cc` |
| **服务端承载循环**（accept/读/握手门禁/心跳/退避/补数/单连接 的**生产侧装配**） | §4.2.1 / §4.2.6 | `include/hg/hgcp/server_loop.h`、`src/server_loop.cc` |
| **真实回环 socket 端到端物证**（帧级握手 / 半包粘包 / 拒 `0x0302` / 心跳判死 / 退避 / 补数批） | §4.2 各节 | `tests/transport_test.cc`（**驱动器**） |

## 5. 因 schema 未定而**暂不可判定**（单列，勿并入"已完成"）

> 判定口径：这些项**需要消息字段级 schema**（字段名→类型→字段号→枚举值），
> 在架构席给出权威定义前**无法实现，也无法验收**。

| # | 项 | 卡点 |
|---|----|------|
| S-1 | `proto/hgcp.proto` 本体 | 字段类型 + 字段号（0/40）未定 |
| S-2 | 元协议 9 种消息的 **payload 编解码**（§4.2.3A） | 同上 |
| S-3 | TCP 服务端握手/心跳/补数的**带 payload 帧收发**（`MSG_HELLO`/`HELLO_ACK`/`AUTH_FAIL`/`ACK`/`ERROR`/`RESUME_REQ`/`RESUME_DATA`） | S-2；**其判定逻辑（鉴权/心跳/退避/补数/单连接）已就绪** |
| S-4 | 验收 6（`proto_smoke` 指向真实 schema 重跑） | S-1（条件"落地后"未满足） |
| S-5 | 控制指令 17 + 事件 14 的字段级定义 | §4.2.3 仅字段名；§4.2.4 仅覆盖 3 条 |

**注意**（本轮已可判定、**不属**上表）：**帧层承载**（含无 body 的 `HEARTBEAT`/`HEARTBEAT_ACK`/`AUTH_FAIL`
帧、不透明 body 透传、流式切帧、`LEN`/`MSG_ID`/`SEQ` 正确性）——见 `tests/envelope_test.cc`。

## 6. 平移接缝（schema 到位后如何最小改动）

```
帧层 frame.h/.cc     ── 只认 (TYPE, FLAGS, SEQ, MSG_ID, LEN, body::bytes)
                          ↑ 零改动
payload_codec.h/.cc  ── 唯一 body ↔ 消息对象 入口：现 OpaquePayloadCodec
                          ↑ 仅新增 PbPayloadCodec（基于 proto 生成代码）并替换装配
服务端 / 状态机       ── 用 FrameStreamReader 取帧 → 交 PayloadCodec → 判定/回帧
```
⇒ schema 落地后：**新增 1 个实现 + 装配处换 1 行**，帧层与协议常量表不动。

## 7. 承载层冲突处置与具名接缝评估（`be-t032b`，2026-09-22）

### 7.1 两套互斥承载层 → 保留 A、删除 B
- **保留 A**：`tcp.{h,cc}`（回环监听/fd 原语）+ `session.{h,cc}`（schema-free 连接状态机）。
  依据：已在 CMake 目标内，`session_test` 已绿（半包/粘包/SEQ 缺口/出站 SEQ+补数/心跳判死/单连接+退避）。
- **新增 `server_loop.{h,cc}`**：把二者的**生产侧装配**（accept/读循环/帧级握手门禁/心跳定时器/
  断线退避装配/补数装配/单连接治理）下沉为**唯一归属**的生产代码（此前仅在测试文件内）；
  `tests/transport_test.cc` 退化为**驱动器**（建连/注入时间/断言）。接缝仍是 `TransportPolicy`（单一符号）。
- **删除 B**：`server.{h,cc}`、`server_seams.{h,cc}`、`socket_util.{h,cc}`。
  依据：**全部未接线 = 死代码**（违反「无死代码」），且制造 ODR 隐患
  （`bool hg::hgcp::send_all(...)` 曾在 `tcp.cc` 与 `socket_util.cc` 各有一份定义）。
- **ODR 清零**：删除后 `send_all` 全仓**仅剩一份定义**（`src/tcp.cc:43`）+ 一份声明（`tcp.h:31`）。
- **CMake 源列表 ↔ 磁盘 `.cc` 一一对应**（12 = 12，无「盘上有但不入目标」的 `.cc`）。

### 7.2 `TcpListener` 端口 0（内核分配）契约修复
`tcp.h` 文档承诺「端口 0 ⇒ 内核分配（测试用）」，但 `start()` 委派 `validate_bind`（视 0 为非法）
⇒ **文档/实现矛盾**。修复：`start()` 显式放行端口 0，非 0 端口仍走配置级校验；
`validate_bind` 的配置级语义**不变**（`endpoint_test` 仍断言端口 0 非法）。

### 7.3 具名接缝（`ControlFrameEncoder` / `HelloVerifier` / `ResumeRequestParser`）—— **评估后不移植**
结论：**不移植**，保留 `session.h` 的 `TransportPolicy` 为唯一注入接缝。理由：
1. **不是超集**：`ControlFrameEncoder::encode(type, code, detail, body)` **不携带时间戳**，
   无法产出 `MSG_HEARTBEAT` 的 `sent_ts_ms`；而 `TransportPolicy::heartbeat_body(now_ms, last_received_seq)`
   **正是为此**。二者覆盖不同面，具名接缝在心跳面上**更弱**。
2. `HelloVerifier` / `ResumeRequestParser` 属**协议逻辑**接缝，schema-free 承载层**无消费者**
   ⇒ 移植即产生**未使用接口（死代码）**。
3. 纪律「不得留下两套重复符号 / 最小改动」⇒ 避免两套重叠接缝。
> 具名接缝的**概念**（握手鉴权 / 补数请求解析）在 schema 到位（`CH-013`）后，随 `PbPayloadCodec`
> 装配一并引入即为自然落点（§6）。

### 7.4 平移接缝（更新）
```
帧层 frame.h/.cc      ── 只认 (TYPE, FLAGS, SEQ, MSG_ID, LEN, body::bytes)              ↑ 零改动
payload_codec.h/.cc   ── body ↔ 消息对象（现 OpaquePayloadCodec → 将来 PbPayloadCodec）↑ 换装配
承载层 tcp.h/.cc      ── 回环监听 / fd 原语（与 schema 无关）                            ↑ 零改动
session.h/.cc         ── 帧切分 / SEQ / 心跳 / 退避 / 补数 / 单连接（schema-free）
server_loop.h/.cc     ── 上述原语+状态机的**生产侧装配**（accept/读/握手门禁/心跳/退避/补数）
TransportPolicy       ── 出站控制帧 body 注入点（现 EmptyTransportPolicy，将来实现真字段）
```
schema 落地后：**新增 1 个 payload 实现 + 1 个策略实现 + 装配处换行**；帧层 / 承载层 / 协议常量表不动。
（注：**补数批 `MSG_RESUME_DATA` 的 body 装配**在 `HgcpServerLoop::send_resume` 内完成，
`from_seq`/`to_seq`/`is_last` 属字段级 schema（待 `CH-013`）；到位后同样经接缝注入即零改装配。）

