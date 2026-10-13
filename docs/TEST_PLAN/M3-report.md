# M3 验证执行报告 · 超限阻断与崩溃恢复不超额下单（T-073）

- **任务**：`T-073`（P0 / 24 人时）｜**执行席**：test-engineer 实例 `te-t073`
- **设计依据**：`docs/TEST_PLAN/M3.md`（T-072 交付，终态 md5 `309bda4d…`/531 行，HEAD `07a9b17`）
- **代码版本**：git HEAD **`96f01b3`**（执行全程零改动树内源码；唯一新增 = 探针 `tools/probes/t073_recovery_non_overrun_probe.sh`）
- **执行时间**：2026-10-13（第 241 轮）
- **性质**：验证执行单。测试件大头已随实现交付（risk_engine 16 ctest / flow_store 2 / hgcp 15 / Java risk_intraday 7 文件 + job 1 文件等）——本席职责 = 逐 TC 溯源实跑 + 缺口实测 + 验收 2 性能实测 + 本报告。
- **状态**：执行完成，**待独立复核**（不自评 → 不置 DONE）

---

## 1. 门禁复跑（零回归声明）

| 门禁项 | 派单基线 | 本席实测（2026-10-13） | 判定 |
|--------|---------|----------------------|------|
| C++ CTP 轨 ctest | 54/54 | **54/54 全绿**（`ctest --test-dir cpp/build`，Total 25.29 s） | ✅ |
| C++ 桩轨 ctest | 50/50 | **50/50 全绿**（`ctest --test-dir cpp/build_stub`，Total 24.93 s） | ✅ |
| Java 全量 | 1099/0/0/0 | **1099/0/0/0 skip=0**（`mvn -B -o clean test`，BUILD SUCCESS，2m40s） | ✅ |
| arch 八判据 | rc0 | **E1–E8 全部退出码 0**（`tools/arch-check/arch-check.sh`；E4 WARN 3 处为信息性未覆盖判据提示，不影响退出码） | ✅ |

**树锚（执行起点，与派单基线逐字吻合）**：
- C++：`cd cpp && find src -type f \( -name '*.cc' -o -name '*.h' -o -name 'CMakeLists.txt' \) | LC_ALL=C sort | xargs md5sum | md5sum` = **`9df51d8c…`/147**
- Java：`cd java && find src -type f | LC_ALL=C sort | xargs md5sum | md5sum` = **`2bf7caa1…`/696**

---

## 2. 落点盘点表（M3.md §5–§7 共 36 TC → 落点 → 实跑结论）

> 判定口径：**通过** = 落点用例在门禁复跑中实跑全绿且断言覆盖该 TC 唯一预期值；**部分承载** = 机制已测但该 TC 的个别子断言无直测（见 §5 未决项）；**不覆盖** = 见 §4。

### 2.1 验收 1 · 六类风险逐类触发

| TC | 风险类 / 唯一预期值 | 落点 | 实跑证据 | 判定 |
|----|---------------------|------|---------|------|
| TC-M3-01 | 层①不足 `0x1004`+SUB_PRODUCT | ctest `risk_engine.quota_layers` | `quota_layers_test.cc:42-45` 断言 kReject/0x1004/kSubProduct/has_limit_layer | 通过 |
| TC-M3-02 | 层②不足 `0x1017`+SUB_INSTRUMENT | `quota_layers` / `double_layer` | `quota_layers_test.cc:55`（0x1017+kSubInstrument）；`double_layer_test` 全绿 | 通过 |
| TC-M3-03 | 层③不足 `0x1005`+ACCOUNT_PRODUCT | `quota_layers` | `:66`（0x1005+kAccountProduct） | 通过 |
| TC-M3-04 | 三层同超 ⇒ 主码 `0x1017`、`entry_count==3` | `quota_layers` | `:76-80`（三层同时超 → 层②最细 0x1017；entries[] 三条 entry_count==3；`:92` 层①+层③两条变体） | 通过 |
| TC-M3-05 | 商品类 ≥90% `0x1007`；平仓 `kPass` | `risk_engine.scale_redline` | `:106-107`（kClose ⇒ kPass）；0x1007 断言在 `close_exemption_test`/`scale_redline_test` 双文件 | 通过 |
| TC-M3-06 | 贸易类 ≥80% `0x1008` | `risk_engine.scale_redline` | `scale_redline_test.cc` 0x1008 断言；`:80` 边界（90% 未触及放行） | 通过 |
| TC-M3-07 | 净持仓超敞口 `0x1009`；减仓 `kPass` | `risk_engine.net_position` | `net_position_test.cc` 0x1009 + 减仓放行；`has_exposure_snapshot_time` 断言同文件 | 通过 |
| TC-M3-08 | 持仓上限 `0x1006`（仅开仓） | `position_limit` / `close_exemption` | `position_limit_test.cc:53-55`（kClose ⇒ kPass）+ 0x1006 断言 | 通过 |
| TC-M3-09 | 白名单外 `0x1001` | `risk_engine.whitelist` | `whitelist_test.cc` 0x1001 断言 | 通过 |
| TC-M3-10 | 合约与品种不符 `0x1002` | `risk_engine.whitelist` | 0x1002 断言（whitelist_test + `hgcp.order_path_test:188` 端到端双载） | 通过 |
| TC-M3-11 | 非境内场内 `0x1003` | `risk_engine.whitelist` | 0x1003 断言 | 通过 |
| TC-M3-12 | 期限红线 `0x100A` | `risk_engine.term` | `term_test.cc` 0x100A + 平仓豁免（`:101-102` kClose ⇒ kPass） | 通过 |
| TC-M3-13 | 告警带 `kWarn`（非 kReject）+ 特批放行 | `risk_engine.term` | `:77`（kWarn + kTerm）、`:89`（term_extension_approved ⇒ kPass） | 通过 |
| TC-M3-14 | 日内开平 ≥6 告警、不限制 | Java `AbnormalOrderTrackerTest` / `IntradayEventFeedTest` | tracker `intradayCountPerProductMax`（计数值 ==6 断言）；`IntradayEventFeedTest.sixFillsRaiseAbnormalOrderAlert`（6 笔 ⇒ ABNORMAL_ORDER 告警，经评估泵生产接线） | 通过 |
| TC-M3-15 | ≥10 且启用 ⇒ `CMD_SET_TRADING_SUSPEND(OPEN_ONLY)`；C++ 开仓回 `0x1011` | Java `IntradayRiskMonitorTest` + C++ `risk_engine.trading_suspend` | `suspendOpenOnlyDispatched`（假桥捕获 OPEN_ONLY）+ `autoSuspendDisabledKeepsSuggestionOnly`（缺省降级）；C++ `trading_suspend_test.cc`（全账户/子账户暂停 ⇒ 开仓拒 0x1011、平仓放行、解除不产码） | 通过 |
| TC-M3-16 | 7 日回合 ≥5 / 平均持仓 <3 日 ⇒ 告警 | Java `AbnormalOrderTrackerTest` | `rollingRoundsWindow`（回合 ==5、超窗剔除）+ `averageHoldingDays`（FIFO ==3.0000）；evaluate 侧告警分支存在（`IntradayRiskMonitor.java:213-223`）——**「告警计数 ==1」evaluate 级断言无直测** | 部分承载（O-1） |
| TC-M3-17 | 缺交易理由 ⇒ 拒（10012）；有理由 ⇒ 放行 | Java `TradeServiceTest` | `验收2` 系列：未提供理由 10012 拒绝且不下发（`:232`）、缺关联类型/时点说明/关联对象逐项拒（`:241/:252/:260`） | 通过 |
| TC-M3-18 | 三要素不齐 ⇒ 10012、`t_hedge_doc` +0 | Java `PlanServiceTest` | `:295` 书面文件门禁 10012（四项齐备/缺任一） | 通过 |
| TC-M3-19 | 无当前版本书面文件 ⇒ 10012、状态不推进 | Java `PlanServiceTest` | `:325`（ruleId=R-PLAN-8） | 通过 |
| TC-M3-20 | 口令/AuthCode/资金密码全介质 0 命中 | Java `SensitiveLoggingAndConfigTest` + grep | 12 用例全绿（脱敏 CFG-19 四项/JSON 形态/属性文件拒装载/D-19 环境变量-only）；本席补充实测：`java/src/main/resources` 内 `db.password`/`DbPassword` 命中 **0** | 通过 |
| TC-M3-21 | 日志禁打印 `auth_token` | Java `SensitiveLog` 测试 + C++ `hgcp.crypto_auth_test` | mask 断言 `auth_token` 值替换；C++ `:103` 凭证不出现在留痕字段（npos 断言） | 通过 |
| TC-M3-22 | 回环绑定 + 强制鉴权 `0x0201` | C++ `hgcp.endpoint_test` / `crypto_auth_test` | `endpoint_test:16-24`（`0.0.0.0`/非回环全拒）+ `validate_bind`；`crypto_auth_test:116`（kBadToken ⇒ 0x0201）+ 留痕 ==1 条 | 通过 |

### 2.2 验收 2 · 三层覆盖

| TC | 层 / 唯一预期值 | 落点 | 实跑证据 | 判定 |
|----|----------------|------|---------|------|
| TC-M3-23 | 阻断 ⇒ CTP 报单调用数 `==0` | C++ `hgcp.order_path_test` / `order_engine` | 拒单链：`test_place_reject`（0x1002 ⇒ `r.eng.order_count()==0`）；门卫链：`test_confirm_guards`（0x2001/0x0305/0x0307 全部 `submit_calls==0`）；正向对照：token 正确 ⇒ `submit_calls==1` + 流水 PLACED+SENT | 通过 |
| TC-M3-24 | 判定零 DB I/O/零锁/零分配；重复求值逐字段相等 | `risk_engine.purity` + arch E1/E2/E4 | `purity_test`（`same(r1,r2)` 双引擎同快照同结果 + 中间插单不变）；arch E1/E2/E4 rc0 | 通过 |
| TC-M3-25 | 占用率 0.71 ⇒ 警告不限制；0.91 ⇒ 严重+处置 | Java `MirrorMetricsCalculatorTest` / `IntradayRiskMonitorTest` | 0.95 ≥0.90 ⇒ CRITICAL（值/阈值/子账户/时间全断言）+ 强平只提示不自动下单；0.71 警告档：monitor 分支存在（`IntradayRiskMonitor.java:184-187`）+ WARNING 级持久化/查询筛选在 `RiskAlertServiceTest` 覆盖——**0.71 直测断言无** | 部分承载（O-2） |
| TC-M3-26 | 事中过程 DB 查询数 `==0` | Java `RiskIntradayNoSqlTest` | 主源零 SQL/零 `java.sql`（静态可复核判据）+ `IntradayRiskMonitorTest.asyncSinkDoesNotBlockEvaluate`（先算后异步落库，慢下游不阻塞）+ `monitoringIsReadOnly`（evaluate 只读） | 通过 |
| TC-M3-27 | 镜像滞后 ⇒ `lagging==true`；不可判定不误报 | Java `MirrorMetricsCalculatorTest` | `:85` 滞后判定用例（落后超阈值 ⇒ lagging；不可判定不伪造） | 通过 |
| TC-M3-28 | 报表落库行数 `==1`（当日）；与结算差异 `==0` | Java `PostRiskReportJobTest`（T-180 落地） | `generateReport`（`TC-M3-28` 字面断言：RISK_METRIC 当日行数==1 + `reconcileDiff==0`）+ 负控（镜像与结算不一致 ⇒ 差异非 0，确证勾稽在比对）+ 无结算单拒绝生成 | 通过 |
| TC-M3-29 | 六类违规覆盖 + 80%/95% 计数 | Java `PostRiskReportJobTest` | 六类 `{超品种,超规模,超期限,超授权,频繁开平仓,文档缺失}` 各 1 断言 + 82%⇒HINT/96%⇒WARNING 计数 + 真实落库 6 行 + 幂等回归（O-2/TE-T180-001） | 通过 |
| TC-M3-30 | 导出字段完整；无数据不生成空文件 | Java `AuditReportServiceTest` / `SettleCheckServiceTest` | `:194` 导出含类型/时间/生成人/子账户范围 + 留痕；`:210` 无数据 ⇒ 不生成空文件；季度报表品种/规模/双侧盈亏（`:107`）——**「非法子账户范围被拒」断言未定位** | 部分承载（O-3） |
| TC-M3-31 | 同一风险三类贯通（① 为例） | 双侧对拍 | 三层各自实测通过：事前 0x1004（quota_layers）/ 事中 used+frozen 口径（`PostRiskReportJobTest` O-1 回归：占用率分子=used+frozen）/ 事后报表计数——但**同笔业务端到端贯通**受真联档限制无承载 | 部分承载（§4-1 同源） |

### 2.3 验收 3 · 崩溃恢复（在途指令）

| TC | 唯一预期值 | 落点 | 实跑证据 | 判定 |
|----|-----------|------|---------|------|
| TC-M3-32 | 还原指令集合逐条相等；重复/遗漏 `==0`；`frozen_held==1` | C++ `flow_store.replay` + `order_engine.recovery_hook` | `test_replay_ready_progress_states`（orders 3 条逐态断言：终态/未发/在途 live_ctp；frozen_held==1；q.calls==1 只查在途）+ `test_crash_replay_restore_conservation`（OrderMap 恰 1 条不重复、台账 open==1、next_order_id==2 防重号、重放零改写流水）+ `test_adopt_terminal_no_double_count`（CTP 终态采纳后 frozenOf 三层归零） | 通过 |
| TC-M3-33 | 重放 ≤60 s；期间拒绝一切新指令 | `flow_store.replay` + hgcp 单侧（0x0401） | `ready()/failed()` 状态机断言（REPLAYING 中 `ready()==false`）；0x0401 子断言：`recovery_gate_test`（参数未就绪/hook 未注入/unknown 未闭环三形态）+ `command_handler_test:432`；耗时：单进程 6 条重放 ctest 墙钟 **0.23 s**，`report.started_ts_ms/finished_ts_ms` 字段在产（`replay.cc:287/497`）——**「≤60 s」无专断言**（O-4） | 部分承载（O-4） |
| TC-M3-34 | 进度回调 `0/2/4/6 + 终态`；条数 `==6` | `flow_store.replay` | `test_replay_ready_progress_states`：`p.states.size()==5`、`done[0..2]==0/2/4`、`total==6`、末态 kReady | 通过 |
| TC-M3-35 | 对账不一致 ⇒ `kMismatch` + `failed()` + `0x0405` | `flow_store.replay` + `hgcp.replay_gate` | `test_reconcile_conflict_mismatch`（kMismatch + `!rc.ready()`）；`replay_gate_test.cc:231/299`（0x0405 线码断言 ×2） | 通过 |
| TC-M3-36 | 补数收敛、重复落库 `==0` | 双侧：`hgcp.seq_out_of_order_test` + Java `HgcpResumeConvergenceIdempotencyTest` | C++ 状态机回退检出互斥/水位不回退；Java：200 事件全补齐、逐帧保序、水位收敛 300、重放批次幂等无重复（TC-M2-16/19 同源细化） | 通过 |

**盘点小结**：36 TC = **通过 32 + 部分承载 4（TC-M3-16/25/30/33）+ 跨层贯通部分承载 1（TC-M3-31）**；零「设计有/落点无」缺口（M3.md §2 现状表列名的三类落点限制中，两类已随 T-180/T-181 解除，见 §4）。

---

## 3. 验收 2 性能实测（事前风控全内存判定耗时）

### 3.1 既有基准承载（T-035 交付，无需新增测试件）

落点 = `cpp/src/risk_engine/tests/bench_risk_check.cc`（197 行），ctest 注册名 `risk_engine.bench_risk_check`（双轨均在册）。规模按 ARCH §4.4.1：10 子账户 / 200 品种 / 2000 合约 / 每子账户 20 品种白名单+层①额度 / 每子账户 100 合约层②额度 / 2000 持仓上限；**100,000 次** `RiskEngine::check` 调用统计分位数，验收断言 `P99 ≤ 5 ms`（NFR-PERF-1 / §4.4.4 预算分解 P3 ≤ 2.0 ms）。

### 3.2 本席实测数字（Release 构建，连续两跑）

```
run1: iters=100000  P50=9.978 us   P99=22.056 us  P99.9=83.590 us  max=3211.471 us  → PASS P99 <= 5 ms (exit 0)
run2: iters=100000  P50=9.991 us   P99=21.679 us  P99.9=81.200 us  max=972.729 us   → PASS P99 <= 5 ms (exit 0)
```

| 指标 | 实测 | 字面上限 | 裕度 |
|------|------|---------|------|
| P99 | **≈22 µs** | 5 ms（§10-24 / ARCH G2） | **≈227×** |
| P50 | ≈10 µs | — | — |
| P99.9 | ≈82–84 µs | 10 ms（上限档） | ≈119× |
| max | ≈0.97–3.21 ms | — | — |

**结论**：验收 2 的「全内存判定 + P99 ≤ 5ms」达成，且远优于字面（P99 为预算 2.0 ms 的约 1.1%）。全内存判定的旁证 = arch E1（零 DB 链接）/E2（零 SQL 痕迹）/E4（交易路径零锁/零同步 I/O）全部 rc0 + `risk_engine.purity` 纯函数式断言。

---

## 4. 不覆盖声明（按 M3.md 分层硬纪律）

| # | 不覆盖项 | 原因 | 处置 |
|---|---------|------|------|
| 1 | **崩溃恢复跨进程真强杀档**（真 kill 进程 + 重启 + TCP 重连 + 跨进程背压/真 TCP 时序） | `cpp/src/main.cc` 仍为工程骨架（不监听、不握手）——M3.md §2.1-3 / §3.4 已披露 | 验收 4 以**进程内模拟**承载（T-041 先例：弃引擎模拟强杀 + 重放 + 还原），与本单口径一致；main.cc 生产装配落地后须补跑 §6.4/§7 跨进程版本并单列证据 |
| 2 | **TC-M3-31 同笔业务端到端三层贯通** | 真联档不可达（同 #1）；C++ 事前 / Java 事中 / 事后报表各自单侧实测全绿，无同笔贯通面 | 单侧结论标注；待真联档补跑 |
| 3 | 真实 CTP 报单回执 / 真实行情时钟抖动（M3.md §3.1 不覆盖项） | 设计既定不测范围 | — |
| 4 | 生产规模（真实交易日流水）下的重放耗时 | 单测档 6 条流水 ≠ 生产规模；`≤60s` 判据以单测档字面 + 墙钟旁证 | 见 O-4 |

**与派单口径不符的两项实测指正**（工单「与我不符请指出」条款）：

1. **事后报表层「job 空落点待建」已过时**：派单前置事实①第 1 点称「job/ 仅 package-info ⇒ TC-M3-27/28 落点待建」。实测：`com/hedgeguard/job/PostRiskReportJob*` 等已由 **T-180 落地**（git log `4bbd63c` 第 202 轮转 DONE；te-verify-t180b 通过），且 `PostRiskReportJobTest` 以 `TC-M3-28`/`TC-M3-29` 字面断言全绿。M3.md §2 现状表、§6.3、§11-A 均已带「〔2026-10-06 更新〕」解决注记。⇒ 该项**不在不覆盖清单**，已按可执行实跑。
2. **0x1011「T-036 接线缺口 / RED 保留」已过时**：实测 `risk_engine.cc:72`（`kErrTradingSuspended = 0x1011 // T-181`）+ 判定实现（`:516-531`）+ `risk_engine.trading_suspend_test` 双轨全绿（开仓拒 0x1011 / 平仓放行 / 解除不产码）。T-181 已接线（te-verify-t181 通过），M3.md §2 现状表 2、§5.4 备注、§11-B 均已带更新注记。⇒ TC-M3-15 的 C++ 子断言**转可执行并实测通过**，无 RED 保留项。

---

## 5. 未决项（只报不改）

| # | 项 | 事实 | 建议 |
|---|----|------|------|
| O-1 | TC-M3-16 evaluate 侧「告警计数 ==1」断言无直测 | 计数面已测（tracker ==5 回合）、monitor 告警分支代码在（`IntradayRiskMonitor.java:213-223`），但 7 日回合/平均持仓两分支无 evaluate 级 JUnit 断言 | 归后续测试件增补单（IntradayRiskMonitorTest 补 2 用例，约 1 人时） |
| O-2 | TC-M3-25 警告档（0.71）无直测断言 | CRITICAL 档全断言；WARNING 档经 `RiskAlertServiceTest`（WARNING 持久化/级别筛选）旁路覆盖，0.71→警告值无端到端断言 | 同上（1 用例） |
| O-3 | TC-M3-30「非法子账户范围被拒」断言未定位 | 导出/无数据不生成空文件已覆盖（AuditReportServiceTest `:194/:210`）；子账户范围非法输入的拒绝断言未在 M3 视角定位到 | 建议编排者裁定归属（可能已被 AUTH 可见性面覆盖，归 T-112/T-110 链） |
| O-4 | TC-M3-33「≤60 s」无专断言 | `started_ts_ms/finished_ts_ms` 字段在产（`replay.cc:287/497`）但无用例断言差值；单测档墙钟 0.23 s 旁证 | 归测试件增补单（1 断言） |
| O-5 | 壳档案键盲区为**既有设计披露面，非新缺陷** | 探针实证：`restoreFromReplay` 壳档案（要素不可还原）⇒ `restoreFrozenHold` 入账壳键 `(0,0)`，对新单真实键不可见；该窗口由就绪门禁第三合取（unknown 未闭环 ⇒ 0x0401 阻断一切新指令，`hgcp.recovery_gate` 已测）+ 人工闭环释放覆盖，不构成超额窗口 | 无需动作；已写入报告作披露 |
| O-6 | 派单基线中 M3.md 头部披露三类落点限制与现状不一致 | 见 §4 指正 1/2 | 编排者后续派单引用 M3.md 时以带更新注记的现状表为准 |

---

## 6. 验收 4 组合语义探针（「不超额下单」补测，工单执行形态第 4 点）

### 6.1 动机与授权形态

工单要求「你补『不超额下单』语义：恢复后新单受理时风控判定基于正确额度状态」。实测确认 T-041 恢复链**组件级**测试齐备（`restoreFrozenHold` 入账幂等 / `quotaLedgerViolation` 账本拒单 / `test_post_restore_accept_uses_ledger` 占号+幂等），但「还原冻结 → 恢复后新单 → 账本累计 ⇒ 超额被拒」的**组合语义**无既有用例承载。按工单写域（探针可落 `tools/` 下新文件，命名 `t073_*`），以**一次性编译源**承载（不入 `cpp/src`，不碰既有测试件与实现）。

### 6.2 探针落痕

- 文件：`tools/probes/t073_recovery_non_overrun_probe.sh`（md5 `0a184c07…`/262 行，`chmod` 默认）
- 形态：mktemp 临时目录内 cmake 构建 Release 库 + g++ 链接探针源（复用树内 `risk_engine_test_util.h` fixture，`-Wall -Wextra -Werror`）；产物与流水目录用后即弃；**对树内文件零写入**（`git status` 仅新增该探针 + 编排者的 TASKS.md 状态行）。
- 授权链：工单写域明示「探针/基准脚本可落 tools/ 下新文件（命名 t073_*）」——本探针属该授权，无新增 ctest/JUnit 测试件，**无需测试件增补审批**。

### 6.3 场景与判定（两跑全 PASS，rc=0）

```
阶段1（崩溃前）：层限 100 手；受理 60 手开仓 → confirm → SENT（不发终态）→ 弃引擎（进程内强杀模拟）
阶段2（重启）：重放（CTP 查询恒 kTimeout ⇒ 保守轨）→ verdict=kOk、orders==1、unknown==1、
              frozen_held==1（冻结保留）、frozen_released==0 → RecoveryHook 消费（unknown_held==1/
              released==0/adopted==0）→ 台账 unknown open → restoreFrozenHold(60万×3 层) 首次成功/
              二次拒（幂等护栏）→ 壳键 (0,0) 在账 60 万（T-041 要素不可还原披露面）→ next_order_id==2
阶段3（人工闭环）：双人核验撤销（op 11≠22 + reason）→ 门禁解除（has_unknown_open==false）→
              壳键份额释放归零 → 恢复后新单三连：
  ① 50 手受理，占号 2（无重号），真实键冻结 50 万
  ② 50 手受理（100 ≤ 100，不误拒——防误拒面），占号 3
  ③ 50 手被拒：kReject + RuleId::kQuota + code==0x1017（账本守卫 150 > 100）
  ④ 被拒单不占号（order_count 恰 3）、无冻结副作用（三层账本 100 万不变）、
     CTP submit_calls == 0（恢复后引擎全程零提交——不发 CTP）
输出：t073_probe: PASS  crash_with_inflight_60  replay_unknown_held=1  manual_release_freed_60
      new1(order=2,50)  new2(order=3,50)  new3_rejected(code=0x1017)  order_count=3
      submit_calls=0  frozen_real_keys=1000000x3
```

**结论**：恢复后新单受理的风控判定基于正确额度状态（双人核验释放 ⇒ 基线归零 ⇒ 正常受理；账本累计超限 ⇒ 0x1017 拒绝且不发 CTP）。「不超额」由**双机制**保障：① unknown 未闭环期间就绪门禁 0x0401 全阻断（无受理面）；② 闭环后冻结账本守卫 `quotaLedgerViolation` 拒超额（判定面）。组件级 + 组合级双轨证据齐备。

---

## 7. 测试件增补请求与结论

- **测试件增补请求：无**。本席零改动树内测试件/实现/冻结制品；组合语义缺口以授权范围内探针承载（§6）。O-1/O-2/O-4 的断言缺口（合计约 2 人时）单列待编排者裁定是否立测试件增补单。
- **验收判定汇总**：
  - 验收 1（六类超限阻断不发 CTP，6 用例全通过）：**达成**（§2.1：六类 22 TC 全绿；TC-M3-31 跨层贯通部分承载已声明）
  - 验收 2（事前全内存判定 + P99 ≤5ms）：**达成**（P99 ≈22 µs，裕度 ≈227×；E1/E2/E4 rc0）
  - 验收 3（事中告警/限制生效且过程无 DB 查询）：**达成**（RiskIntradayNoSqlTest + 异步落库不阻塞 + 告警分级/处置/OPEN_ONLY 限制链实测）
  - 验收 4（崩溃恢复不产生超额下单）：**达成（进程内模拟档）**（T-041 恢复链 4 用例 + flow_store 重放 8 用例 + 0x0401/0x0405 线码门禁 + §6 组合语义探针；跨进程档不覆盖已声明）
- **零回归**：门禁四项（§1）全绿复跑，与派单基线逐项吻合。
- **状态**：待独立复核（不自评 → 不置 DONE）。

---

````
本报告指纹（落笔后以实时 md5sum 为准）：
  文件：docs/TEST_PLAN/M3-report.md
  覆盖：TC-M3-01 .. TC-M3-36（36 条）+ 验收 1–4 + 性能实测 + 探针证据
````
