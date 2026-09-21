# 测试计划 · 非功能用例

> 负责人：测试经理 ｜ 阶段：S5 ｜ 版本：v0.1

- **项目**：企业期货套期保值管理系统（HedgeGuard）
- **编写人**：测试经理
- **编写时间**：2026-09-20

> 📁 非功能性需求**跨模块生效**，验证靠**测量**而非操作观察。
> 用例来源：`docs/REQUIREMENTS/NONFUNCTIONAL.md`（50 条 NFR）。
> 全局内容见 `TEST_PLAN/INDEX.md`。

---

## 1. 性能用例 `TC-PERF-*`

> **测量环境**：Ubuntu 22.04 LTS，双组件同机双进程，DM8；硬件配置见 `INDEX.md` §5。**"感觉快"不是结论，须给测量方法与阈值。**

### TC-PERF-1 事前风控单笔判定 P99 ≤ 5 ms 且判定路径零 DB I/O

- **对应需求**：`NFR-PERF-1 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **指标**：判定函数 P99 响应时间；判定路径 DB I/O 次数
- **目标值**：P99 ≤ 5 ms、上限 ≤ 10 ms；DB I/O = 0
- **测量方法**：C++ `bench_risk_check`（ctest 注册）≥ 10 万次调用统计 P99；DM8 全量语句审计跑 100 笔下单，核对交易路径 SQL 记录数 = 0
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 运行 `bench_risk_check` 采集 ≥10 万次 |
  | 2 | 开启 DM8 全量审计，跑 100 笔下单 |

- **判定**：P99 ≤ 5 ms 且交易路径 SQL 数 = 0 即通过（`PROJECT.md` 成功标准 23、24）
- **状态**：有效

### TC-PERF-2 人工确认到到达 CTP 的耗时 P95 ≤ 50 ms

- **对应需求**：`NFR-PERF-2 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **指标**：确认 → 到达 CTP 耗时
- **目标值**：P95 ≤ 50 ms
- **测量方法**：CTP 模拟环境压测 100 笔，统计确认到发往柜台时间差
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 压测 100 笔指令，记录时间差 |

- **判定**：P95 ≤ 50 ms 即通过
- **状态**：有效

### TC-PERF-3 资金台账与会计凭证查询 P95 < 2 s（1 万条）

- **对应需求**：`NFR-PERF-3 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **指标**：查询 P95
- **目标值**：< 2 s（1 万条流水 / 1 万条凭证）
- **测量方法**：造 1 万条流水与 1 万条凭证，执行典型查询统计 P95
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 造数并执行查询 |

- **判定**：P95 < 2 s 即通过
- **状态**：有效

### TC-PERF-4 业务列表查询 P95 < 2 s（1 万条）

- **对应需求**：`NFR-PERF-4 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **指标**：列表查询 P95
- **目标值**：< 2 s（1 万条，含筛选/排序/分页）
- **测量方法**：造 1 万条数据后按典型筛选执行，统计 P95；核对关键字段有索引
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 覆盖 `PRODUCT`/`SPOT`/`PLAN`/`TRADE`/`SETTLE`/`RISK`/`AUDIT` 列表查询 |

- **判定**：P95 < 2 s 即通过
- **状态**：有效

### TC-PERF-5 行情端到端推送延迟 P95 ≤ 200 ms（`RSK-06` 验证点）

- **对应需求**：`NFR-PERF-5 @ REQUIREMENTS/NONFUNCTIONAL.md`（`RSK-06`）
- **指标**：CTP 行情 → 前端展示端到端延迟
- **目标值**：P95 ≤ 200 ms
- **测量方法**：行情活跃时段对比柜台时间戳与前端渲染时间戳，采样 ≥1000 次统计 P95；WS 自实现 A′ 正确性由 §4.2.7/离线单测兜底
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 采样 ≥1000 次，统计端到端 P95 |

- **判定**：P95 ≤ 200 ms 即通过；不达标则触发 `RSK-06` 回退评估（降级声明 `NFR-PERF-5`）
- **状态**：有效
- **备注**：**`RSK-06` 主验证点**。

### TC-PERF-6 事件流吞吐 ≥1000 事件/秒且不丢不乱序

- **对应需求**：`NFR-PERF-6 @ REQUIREMENTS/NONFUNCTIONAL.md`（`T1`、`T2`）
- **指标**：事件吞吐；丢/乱序数
- **目标值**：≥1000 事件/秒；丢 = 0、乱序 = 0
- **测量方法**：连续产生 1000 笔事件，核对 Java 侧接收数量与顺序与 C++ 发出完全一致
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 产生 1000 事件，逐条比对 `SEQ` 与业务字段 |

- **判定**：数量 = 1000 且顺序完全一致即通过（`PROJECT.md` 成功标准 31）
- **状态**：有效

### TC-PERF-7 日终结算 ≤10 分钟（1 万成交、10 子账户）

- **对应需求**：`NFR-PERF-7 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **指标**：日终结算总耗时
- **目标值**：≤ 10 分钟
- **测量方法**：造 1 万笔成交与 10 个子账户，跑完整日终结算（含三类核对与加总对账）并计时
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 跑完整日终结算并计时 |

- **判定**：≤ 10 分钟即通过
- **状态**：有效

---

## 2. 可靠性用例 `TC-REL-*`

### TC-REL-1 C++ 崩溃恢复：重启 ≤60 s 重放，重放期拒绝新指令

- **对应需求**：`NFR-REL-1 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **故障场景**：已发 CTP 未收回报时强杀进程
- **预期恢复时间**：≤ 60 秒
- **数据一致性要求**：额度与持仓无重复计入、无遗漏
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 强杀进程 → 重启 → 计时并核对额度与持仓 |

- **判定**：恢复 ≤60 s 且重放期拒绝新指令、状态一致即通过（成功标准 27）
- **状态**：有效
- **备注**：关联 `TC-EXC-5`。

### TC-REL-2 组件通信断连检测 ≤5 s、恢复 ≤30 s、关键事件零丢失

- **对应需求**：`NFR-REL-2 @ REQUIREMENTS/NONFUNCTIONAL.md`（`T5`）
- **故障场景**：Socket 断开重连
- **预期恢复时间**：检出 ≤5 s、恢复 ≤30 s
- **数据一致性要求**：关键事件（成交/持仓/风控拒绝）零丢失
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 模拟断连，测量检测与恢复时间，比对两侧状态 |

- **判定**：达阈值且零丢失即通过（成功标准 32）
- **状态**：有效

### TC-REL-3 已确认业务数据持久化且事务性（无半条记录）

- **对应需求**：`NFR-REL-3 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **故障场景**：写入过程中异常中断
- **预期恢复时间**：重启后完整可查
- **数据一致性要求**：成交/资金流水/凭证/结算记录无半条、无丢失
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 写入中断 → 重启核对 |

- **判定**：无半条记录、无丢失即通过
- **状态**：有效

### TC-REL-4 交易日可用性 ≥99.5%

- **对应需求**：`NFR-REL-4 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **指标**：交易时段（09:00–15:30）可用率
- **目标值**：≥ 99.5%
- **测量方法**：连续运行记录 + 维护窗口登记；按"交易时段可用分钟数 / 总分钟数"计算
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 统计运行期可用率；核对维护窗口避开交易时段 |

- **判定**：≥ 99.5% 即通过
- **状态**：有效

### TC-REL-5 外部回报与指令幂等

- **对应需求**：`NFR-REL-5 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **故障场景**：外部回报重复推送
- **数据一致性要求**：不产生重复数据或重复记账
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 对同类回报重复推送 ≥3 次，核对条数与金额不变 |

- **判定**：条数与金额不变即通过
- **状态**：有效
- **备注**：关联 `TC-EXC-4`。

### TC-REL-6 加总不一致 ≤60 s 告警且阻断立即生效

- **对应需求**：`NFR-REL-6 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **故障场景**：人为构造加总不一致
- **预期恢复时间**：告警 ≤60 s；阻断不等待人工确认
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 构造不一致，计时告警可见时间，验证后续操作被阻断 |

- **判定**：告警 ≤60 s 且阻断立即生效即通过（成功标准 36）
- **状态**：有效
- **备注**：关联 `TC-SETTLE-9`。

---

## 3. 安全用例 `TC-SEC-*`

### TC-SEC-1 零落盘红线（密码/AuthCode/资金密码）

- **对应需求**：`NFR-SEC-1 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证点**：敏感字段是否落盘（配置文件/DB/日志/核心转储/临时目录）
- **验证方法**：全库 grep + 日志 grep + 配置文件审计 + **核心转储目录与临时目录**审计，四项零命中
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 手工录入敏感字段后全盘搜索明文 |
  | 2 | 重启后须重新输入 |

- **判定**：四项零命中且重启需重录即通过（成功标准 4）
- **状态**：有效

### TC-SEC-2 口令加盐哈希 + 明文不落盘 + 强度与历史

- **对应需求**：`NFR-SEC-2 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证点**：存储形式；强度策略；历史口令禁重用
- **验证方法**：检查为加盐哈希（PBKDF2-HMAC-SHA256，≥12 万次迭代）；弱口令/历史口令被拒；日志 grep 无明文
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 注册/改密边界；grep 日志 |

- **判定**：三项均满足即通过
- **状态**：有效

### TC-SEC-3 组件间 TCP 仅监听回环 + 强制鉴权

- **对应需求**：`NFR-SEC-3 @ REQUIREMENTS/NONFUNCTIONAL.md`（`T7`、`T8`）
- **验证点**：绑定地址；鉴权强制；鉴权凭据零落盘
- **验证方法**：`ss -lntp` 确认 `127.0.0.1`；尝试配置非回环被拒；关闭鉴权被拒；鉴权失败告警
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | `ss -lntp`；配 `0.0.0.0`；错误/缺失令牌/版本不符 |

- **判定**：仅回环且鉴权失败关闭连接 + 告警即通过
- **状态**：有效
- **备注**：`T7`/`T8`；关联 `TC-CONFIG-18`。

### TC-SEC-4 资金转账与划拨双人复核

- **对应需求**：`NFR-SEC-4 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证点**：发起人 ≠ 复核/审批人；审批人不得交易岗；越权留痕
- **验证方法**：同一账号发起并审批被拒；越权尝试在审计中可查
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 同账号审批；交易岗审批；核对审计 |

- **判定**：均被拒且留痕即通过（成功标准 22）
- **状态**：有效

### TC-SEC-5 前中后台岗位分离系统级强制

- **对应需求**：`NFR-SEC-5 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证点**：交易/风控/结算两两互斥；资金岗与交易岗互斥；负责人禁操盘；无关闭开关
- **验证方法**：配置越权角色组合被拒；负责人下单被拒（C++ `0x100F` 兜底）；授权变更入审计
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 穷举互斥组合 |

- **判定**：均被拒且无关闭开关即通过（成功标准 15）
- **状态**：有效

### TC-SEC-6 审计日志不可篡改

- **对应需求**：`NFR-SEC-6 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证点**：只增不改不删；防篡改校验；校验未通过阻断导出
- **验证方法**：人为修改底层日志后校验结论"未通过"且导出被阻断；界面无删除入口
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 篡改日志后校验并尝试导出 |

- **判定**：校验未通过且阻断导出即通过
- **状态**：有效

### TC-SEC-7 子账户数据软隔离（安全默认 = 空集）

- **对应需求**：`NFR-SEC-7 @ REQUIREMENTS/NONFUNCTIONAL.md`（`§4.1.2`）
- **验证点**：无授权默认看不到任何子账户数据；跨子账户仅管理层/审计岗可得且只读；数据权限与功能权限独立
- **验证方法**：以 `SUB-A` 用户登录验证看不到 B；审计岗可跨账户；交易岗申请跨账户被拒；`/ws/quote`、`/ws/alert` 推送前按 `t_user_sub_account_perm` 过滤（越权 `10004`）
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 多角色登录验证；WS 订阅越权验证 |

- **判定**：安全默认空集且越权被拒即通过（成功标准 34）
- **状态**：有效
- **备注**：JUnit `WsAuthorizerTest#noGrantMeansNothingDelivered` / `unauthorizedSubAccountRejectedAndRecorded`。

### TC-SEC-8 数据库访问账户最小权限

- **对应需求**：`NFR-SEC-8 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证点**：应用账户不得有建库/删库/授权权限；禁用超级账号
- **验证方法**：以应用账户尝试 DDL/授权应失败；部署文档记录账户权限清单
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 应用账户执行 DDL/GRANT |

- **判定**：DDL/授权失败即通过
- **状态**：有效

### TC-SEC-9 C++ 组件零 DB 访问

- **对应需求**：`NFR-SEC-9 @ REQUIREMENTS/NONFUNCTIONAL.md`（`T` 段，`E1`/`E2`）
- **验证点**：C++ 无 DB 访问代码、无 DB 客户端库链接、无 SQL 语句
- **验证方法**：代码审查 + 链接检查 + 静态扫描（`tools/arch-check/scan_cpp_sql.sh` 等）
- **步骤**：

  | 步 | 操作 |
  |----|------|
  | 1 | 运行 `E1`/`E2` 检查 |

- **判定**：三项零命中即通过（成功标准 29）
- **状态**：有效

---

## 4. 兼容性用例 `TC-COMPAT-*`

### TC-COMPAT-1 C++ 严格 C++11 且零警告零错误

- **对应需求**：`NFR-COMPAT-1 @ REQUIREMENTS/NONFUNCTIONAL.md`（`E3`）
- **兼容矩阵**：`-std=c++11 -Wall -Wextra -Werror`；禁 `make_unique`/`optional`/`string_view`/结构化绑定/`if constexpr`
- **步骤 / 判定**：以严格标准编译零警告零错误；`E3` 扫描无 C++14+ 特性（成功标准 6）
- **状态**：有效

### TC-COMPAT-2 JDK 11 编译目标且无 17+ 特性

- **对应需求**：`NFR-COMPAT-2 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **兼容矩阵**：`maven-compiler-plugin release=11`
- **步骤 / 判定**：JDK 11 编译通过；扫描无 `record`/`sealed`/模式匹配 `switch`/文本块
- **状态**：有效

### TC-COMPAT-3 Chromium 内核浏览器（Chrome/Edge 最近两版）

- **对应需求**：`NFR-COMPAT-3 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **兼容矩阵**：Chrome 最近两版 × Edge 最近两版
- **步骤 / 判定**：在 4 个组合上完成全流程冒烟；不支持移动端
- **状态**：有效

### TC-COMPAT-4 Ubuntu 22.04 同机双进程（非 JNI）

- **对应需求**：`NFR-COMPAT-4 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **兼容矩阵**：Ubuntu 22.04 LTS，双组件同机双进程
- **步骤 / 判定**：在目标环境完成部署与全流程验证；架构审查确认非 JNI
- **状态**：有效

### TC-COMPAT-5 达梦 DM8 + 标准 JDBC，无存储过程/触发器/自定义函数

- **对应需求**：`NFR-COMPAT-5 @ REQUIREMENTS/NONFUNCTIONAL.md`（`E5`）
- **兼容矩阵**：DM8；标准 JDBC；DAO 层唯一接触 SQL
- **步骤 / 判定**：在 DM8 完成建表/读写/查询；无 MySQL 方言残留；无存储过程/触发器/自定义函数依赖（成功标准 28、33）
- **状态**：有效

### TC-COMPAT-6 对接 CTP 合并版 UserApi（异步 SPI）

- **对应需求**：`NFR-COMPAT-6 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **兼容矩阵**：CTP 合并版（行情+交易+资金划转同一 SDK）
- **步骤 / 判定**：CTP 模拟环境完成期货开平与期权策略各一笔 + 一笔模拟出入金；按异步 SPI 回调设计（成功标准 10、21）
- **状态**：有效
- **备注**：`RSK-02` 已实证 CTP `V1.7.7` 兼容。

### TC-COMPAT-7 系统零外部数据依赖（断外网保 CTP）

- **对应需求**：`NFR-COMPAT-7 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **兼容矩阵**：封禁所有非 CTP 目标地址
- **步骤 / 判定**：跑通 M1–M4 全流程（成功标准 11）
- **状态**：有效

---

## 5. 可维护性用例 `TC-MAINT-*`

### TC-MAINT-1 前端零构建链

- **对应需求**：`NFR-MAINT-1 @ REQUIREMENTS/NONFUNCTIONAL.md`（`E6`）
- **验证方法**：全库搜索 `package.json` 与 `node_modules` 零命中；改动源文件刷新即生效
- **判定**：零命中且改动即生效即通过（成功标准 7）
- **状态**：有效

### TC-MAINT-2 最小依赖

- **对应需求**：`NFR-MAINT-2 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：审查构建文件依赖清单；新依赖有 `CH-xxx` 论证记录
- **判定**：无未论证新依赖即通过
- **状态**：有效

### TC-MAINT-3 数据访问分层（DAO 唯一 SQL 处）

- **对应需求**：`NFR-MAINT-3 @ REQUIREMENTS/NONFUNCTIONAL.md`（`E5`）
- **验证方法**：`tools/arch-check/scan_java_sql.sh` 检查 DAO 层之外无 SQL；抽查变更记录确认"文档先行"
- **判定**：DAO 外 0 SQL 命中即通过（成功标准 33）
- **状态**：有效

### TC-MAINT-4 组件边界不可越界

- **对应需求**：`NFR-MAINT-4 @ REQUIREMENTS/NONFUNCTIONAL.md`（`E1`/`E4`）
- **验证方法**：代码审查 + 链接检查；事前风控未搬到 Java
- **判定**：边界无越界即通过（成功标准 29）
- **状态**：有效

### TC-MAINT-5 前端与其它模块隔离

- **对应需求**：`NFR-MAINT-5 @ REQUIREMENTS/NONFUNCTIONAL.md`（`E6`）
- **验证方法**：架构审查 + 前端代码中无直连数据库/组件调用
- **判定**：仅经 Java `/api/v1` 与 `/ws` 通信即通过
- **状态**：有效

### TC-MAINT-6 单一写库入口

- **对应需求**：`NFR-MAINT-6 @ REQUIREMENTS/NONFUNCTIONAL.md`（`E1`/`E5`）
- **验证方法**：代码审查确认写库入口唯一；C++ 侧无 DB 客户端库链接
- **判定**：唯一写库入口即通过
- **状态**：有效

---

## 6. 可测试性用例 `TC-TEST-*`

### TC-TEST-1 核心模块单元测试覆盖率 ≥80%

- **对应需求**：`NFR-TEST-1 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：覆盖率工具统计 `PLAN`（比率三档）、`RISK`（额度与红线）、`ACCT`（凭证与文档）行覆盖率
- **判定**：三类均 ≥80% 即通过（成功标准 8）
- **状态**：有效

### TC-TEST-2 ctest 与 JUnit 全通过，且无 CTP 实盘可测

- **对应需求**：`NFR-TEST-2 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：本地/CI 执行 `ctest` 与 `mvn test`（`unit` 档无外部依赖）；`ICtGateway` 桩 + `InMemoryTradingBridge` 桩
- **判定**：全部用例通过即通过
- **状态**：有效
- **备注**：既有 Java JUnit 272 个（详见 `INDEX.md` §2.2）。

### TC-TEST-3 端到端冒烟可重复执行

- **对应需求**：`NFR-TEST-3 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：`tools/smoke/run.sh` 连跑 ≥3 次，每次结果一致且证据链完整
- **判定**：3 次结果一致即通过（成功标准 1）
- **状态**：有效

### TC-TEST-4 关键外部依赖可注入/可打桩

- **对应需求**：`NFR-TEST-4 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：审查时钟/CTP/DB/组件通信的注入方式；同一用例在固定时钟下结果稳定
- **判定**：无业务代码直接取 `System.currentTimeMillis()`/`time()`；固定时钟结果稳定即通过
- **状态**：有效

### TC-TEST-5 通信协议专项测试（`T1`–`T11`）

- **对应需求**：`NFR-TEST-5 @ REQUIREMENTS/NONFUNCTIONAL.md`（`ARCHITECTURE.md` §4.2.7）
- **验证方法**：`ctest --test-dir build -L protocol`（C++）+ Java 集成测试（两侧各自跑再互跑）
- **判定**：`T1`–`T11` 全通过即通过（成功标准 31、32）
- **状态**：有效
- **备注**：`T1`–`T11` 落点见 `INDEX.md` §4.2。

---

## 7. 易用性用例 `TC-USE-*`

### TC-USE-1 新用户 30 分钟内完成"录入现货 → 计算实货敞口"

- **对应需求**：`NFR-USE-1 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：≥3 名未受训业务人员实测
- **判定**：成功率 100% 且平均耗时 ≤30 分钟即通过
- **状态**：有效

### TC-USE-2 交易岗"查看交易计划 → 提交指令"操作步数 ≤5

- **对应需求**：`NFR-USE-2 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：界面路径清点（不含人工确认）
- **判定**：≤5 步即通过
- **状态**：有效

### TC-USE-3 风控阻断提示含"规则编号 + 当前值 + 阈值"

- **对应需求**：`NFR-USE-3 @ REQUIREMENTS/NONFUNCTIONAL.md`（成功标准 12、13、14）
- **验证方法**：逐条构造 ≥8 类阻断场景，检查三项齐备
- **判定**：三项齐备即通过
- **状态**：有效

### TC-USE-4 被阻断操作须给出可执行下一步提示

- **对应需求**：`NFR-USE-4 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：随机抽取 10 个阻断场景，检查是否有明确下一步提示
- **判定**：10 个均含下一步提示即通过
- **状态**：有效

### TC-USE-5 子账户/汇总切换 ≤2 次点击；套保效果页双侧同屏

- **对应需求**：`NFR-USE-5 @ REQUIREMENTS/NONFUNCTIONAL.md`（成功标准 18）
- **验证方法**：界面操作清点；检查套保效果页两栏同屏
- **判定**：≤2 次点击且双侧同屏即通过
- **状态**：有效

---

## 8. 可部署性用例 `TC-DEPLOY-*`

### TC-DEPLOY-1 同机双进程部署（非 JNI，无云服务）

- **对应需求**：`NFR-DEPLOY-1 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：部署拓扑审查 + 实际部署验证
- **判定**：同机双进程、非 JNI、无 K8s/外网中间件即通过
- **状态**：有效

### TC-DEPLOY-2 部署时长 ≤30 分钟且步骤清单无歧义

- **对应需求**：`NFR-DEPLOY-2 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：在干净 Ubuntu 22.04 上按清单部署并计时（不含 DM8 安装与 OS 准备）
- **判定**：≤30 分钟即通过
- **状态**：有效

### TC-DEPLOY-3 构建可复现（CMake FetchContent / Maven）

- **对应需求**：`NFR-DEPLOY-3 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：干净环境执行完整构建并计时；验证 `CTP_FOUND`/`CTP_USE_FETCH_DEPS` 双轨均可用
- **判定**：构建零警告零错误且双轨均可用即通过
- **状态**：有效
- **备注**：`RSK-01` 已实证 protobuf 3.21.12 / C++11 构通。

### TC-DEPLOY-4 配置管理分级（本地持久化 / API 不落盘 / 敏感零落盘）

- **对应需求**：`NFR-DEPLOY-4 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：检查部署文档含配置清单（默认值/取值范围/是否可持久化）；重启后非敏感保留、敏感须重录
- **判定**：分级正确即通过
- **状态**：有效

### TC-DEPLOY-5 环境依赖文档化（含实测版本）

- **对应需求**：`NFR-DEPLOY-5 @ REQUIREMENTS/NONFUNCTIONAL.md`
- **验证方法**：检查部署文档含 DM JDBC 驱动实测版本、CTP SDK 版本、JDK 11 版本、CMake/GCC 版本、浏览器要求；确认驱动在目标环境可用
- **判定**：清单含实测版本号即通过
- **状态**：有效

---

## 9. NFR 覆盖核对

| NFR 编号 | 类别 | 对应用例 | 覆盖状态 |
|---------|------|---------|---------|
| `NFR-PERF-1` | 性能 | `TC-PERF-1` | ✅ |
| `NFR-PERF-2` | 性能 | `TC-PERF-2` | ✅ |
| `NFR-PERF-3` | 性能 | `TC-PERF-3` | ✅ |
| `NFR-PERF-4` | 性能 | `TC-PERF-4` | ✅ |
| `NFR-PERF-5` | 性能 | `TC-PERF-5` | ✅（`RSK-06` 验证点） |
| `NFR-PERF-6` | 性能 | `TC-PERF-6` | ✅ |
| `NFR-PERF-7` | 性能 | `TC-PERF-7` | ✅ |
| `NFR-REL-1` | 可靠性 | `TC-REL-1` | ✅ |
| `NFR-REL-2` | 可靠性 | `TC-REL-2` | ✅ |
| `NFR-REL-3` | 可靠性 | `TC-REL-3` | ✅ |
| `NFR-REL-4` | 可靠性 | `TC-REL-4` | ✅ |
| `NFR-REL-5` | 可靠性 | `TC-REL-5` | ✅ |
| `NFR-REL-6` | 可靠性 | `TC-REL-6` | ✅ |
| `NFR-SEC-1` | 安全 | `TC-SEC-1` | ✅ |
| `NFR-SEC-2` | 安全 | `TC-SEC-2` | ✅ |
| `NFR-SEC-3` | 安全 | `TC-SEC-3` | ✅ |
| `NFR-SEC-4` | 安全 | `TC-SEC-4` | ✅ |
| `NFR-SEC-5` | 安全 | `TC-SEC-5` | ✅ |
| `NFR-SEC-6` | 安全 | `TC-SEC-6` | ✅ |
| `NFR-SEC-7` | 安全 | `TC-SEC-7` | ✅ |
| `NFR-SEC-8` | 安全 | `TC-SEC-8` | ✅ |
| `NFR-SEC-9` | 安全 | `TC-SEC-9` | ✅ |
| `NFR-COMPAT-1` | 兼容性 | `TC-COMPAT-1` | ✅ |
| `NFR-COMPAT-2` | 兼容性 | `TC-COMPAT-2` | ✅ |
| `NFR-COMPAT-3` | 兼容性 | `TC-COMPAT-3` | ✅ |
| `NFR-COMPAT-4` | 兼容性 | `TC-COMPAT-4` | ✅ |
| `NFR-COMPAT-5` | 兼容性 | `TC-COMPAT-5` | ✅ |
| `NFR-COMPAT-6` | 兼容性 | `TC-COMPAT-6` | ✅ |
| `NFR-COMPAT-7` | 兼容性 | `TC-COMPAT-7` | ✅ |
| `NFR-MAINT-1` | 可维护性 | `TC-MAINT-1` | ✅ |
| `NFR-MAINT-2` | 可维护性 | `TC-MAINT-2` | ✅ |
| `NFR-MAINT-3` | 可维护性 | `TC-MAINT-3` | ✅ |
| `NFR-MAINT-4` | 可维护性 | `TC-MAINT-4` | ✅ |
| `NFR-MAINT-5` | 可维护性 | `TC-MAINT-5` | ✅ |
| `NFR-MAINT-6` | 可维护性 | `TC-MAINT-6` | ✅ |
| `NFR-TEST-1` | 可测试性 | `TC-TEST-1` | ✅ |
| `NFR-TEST-2` | 可测试性 | `TC-TEST-2` | ✅ |
| `NFR-TEST-3` | 可测试性 | `TC-TEST-3` | ✅ |
| `NFR-TEST-4` | 可测试性 | `TC-TEST-4` | ✅ |
| `NFR-TEST-5` | 可测试性 | `TC-TEST-5` | ✅ |
| `NFR-USE-1` | 易用性 | `TC-USE-1` | ✅ |
| `NFR-USE-2` | 易用性 | `TC-USE-2` | ✅ |
| `NFR-USE-3` | 易用性 | `TC-USE-3` | ✅ |
| `NFR-USE-4` | 易用性 | `TC-USE-4` | ✅ |
| `NFR-USE-5` | 易用性 | `TC-USE-5` | ✅ |
| `NFR-DEPLOY-1` | 可部署性 | `TC-DEPLOY-1` | ✅ |
| `NFR-DEPLOY-2` | 可部署性 | `TC-DEPLOY-2` | ✅ |
| `NFR-DEPLOY-3` | 可部署性 | `TC-DEPLOY-3` | ✅ |
| `NFR-DEPLOY-4` | 可部署性 | `TC-DEPLOY-4` | ✅ |
| `NFR-DEPLOY-5` | 可部署性 | `TC-DEPLOY-5` | ✅ |

> **覆盖自检**：`NFR-PERF` 7 + `NFR-REL` 6 + `NFR-SEC` 9 + `NFR-COMPAT` 7 + `NFR-MAINT` 6 + `NFR-TEST` 5 + `NFR-USE` 5 + `NFR-DEPLOY` 5 = **50 ✅**
> 状态取值：✅ 已覆盖 / ❌ 未覆盖（须写理由）/ ⚪ 不适用（需求侧已豁免）。本文件**无 ❌、无 ⚪**。
