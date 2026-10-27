# HedgeGuard 部署与运维支撑（T-052）

> **制品**：本目录（`docs/DEPLOY/**`）+ `scripts/deploy/**`
> **依据**：`REQUIREMENTS/NONFUNCTIONAL.md` §8（`NFR-DEPLOY-1~5`）、
> `ARCHITECTURE.md` §2.4.1 / §3.14 / §3.15 / §7.1.9 / §11、
> `TASKS.md`「T-052」。
> **门禁**：`NFR-DEPLOY-2/3/4/5`、`CFG-01`~`CFG-33`、`RSK-03`。
> **范围**：部署清单、环境依赖清单、配置项清单、双组件启动顺序脚本、健康检查脚本。

> ⚠️ **版本锚点纪律**：本文引用的 `ARCHITECTURE.md` / `DATA_MODEL.md` 行号与 md5 **以实时为准**；
> 本文不内嵌自身 md5（自指不可自洽），产出物 md5 以交付回执/`git` 为准。

---

## 0. 验收对照与诚实声明

| 验收 | 要求 | 状态 | 说明 |
|------|------|------|------|
| 1 | 部署清单 / 环境依赖清单（含 DM JDBC 实测版本）/ 配置项清单齐备 | **达成** | §2 环境依赖、§3 配置项清单、§7 部署步骤、§9 脚本清单 |
| 2 | 双组件启动顺序脚本（hg-trading → hg-biz） | **部分** | 脚本已按 §2.4.1 顺序交付（§4、§9）；**hg-trading 为工程骨架，不监听/不握手/无就绪态** ⇒ 顺序脚本可运行但「C++ 已就绪」无法在骨架期成立，真联编排承接中 |
| 3 | 健康检查脚本可用；前后端可独立升级 | **达成** | §5 健康检查（本机实测：hg-biz 3/3 PASS）；§6 独立升级（零构建链） |
| 4 | 敏感字段零落盘在部署文档显式声明 | **达成** | §10（含装载守卫与口令策略项陷阱） |
| 5 | DM8 实机建表跑通 + 与 DATA_MODEL 逐列一致 | **未达（环境前置缺失）** | 本环境**无 DM8 实例/无 DM 驱动**（§8.1 实测）。已交付可复跑静态核对脚本 + 结果；**不得以 SQLite 冒充 DM8**（CH-016）。另发现一处**建表脚本跨模块循环外键**（§8.4），**已由 T-179 按两阶段后置消解**（DM8 实机验证仍待环境） |
| 6 | protobuf 供给策略落地 | **达成** | §2.3（系统包 3.21.12 实测在位 / FetchContent 离线回退） |
| 7 | CTP SDK 实盘版本复测 | **达成** | §2.4（`run_probe.sh` 复测 PASS=12/FAIL=0，SDK v6.7.13） |

**三条不变量（贯穿全文）**：
1. **敏感字段零落盘**（`NFR-SEC-1` / 附录 B 红线 8）——口令/令牌只经环境变量注入，属性文件装载守卫会拒绝含 `password/token/secret` 的键。
2. **不得以 SQLite 冒充 DM8**（`CH-016`）——SQLite 仅限 dev/test；生产部署口径恒为 DM8。
3. **就绪语义不得伪装**——`hg-trading` 骨架期不提供 READY；脚本一律显式告警，不谎称已联调。

---

## 1. 部署拓扑与硬约束

**同机双进程（`NFR-DEPLOY-1`，非 JNI）**，禁止部署到不同主机、禁止依赖云服务/K8s/外网中间件：

```text
       ┌──────────────────────── 同一台主机（建议 Ubuntu 22.04 LTS）────────────────────────┐
       │                                                                                  │
       │   hg-trading（C++11，先启动，长驻）                    hg-biz（Java 11，后启动，长驻）│
       │   · HGCP TCP 服务端  127.0.0.1:27400 (CFG-20)   ◀──TCP──▶  · HGCP TCP 客户端        │
       │   · 前端 HTTP/WS：不提供（CH-006）                          · 前端 HTTP 127.0.0.1:27402│
       │   · 指令流水 ./data/flow (CFG-29)                          · 前端 WS  127.0.0.1:27401│
       │   · 零 DB I/O（E1 红线）                                   · 唯一写库入口（DM8）    │
       │                                                             · /web/** 静态托管(零构建)│
       └──────────────────────────────────────────────────────────────────────────────────┘
                                            ▲
                               浏览器（仅本机/内网，访问 Java 的 HTTP/WS）
```

- **端口分离**（`ARCHITECTURE.md` §11.2）：`hg.tcp.port=27400`（组件通道）/ `hg.web.ws.port=27401`（前端 WS）/ `hg.web.port=27402`（前端 HTTP）；三者互不相同。
- **仅回环**：`hg.tcp.bind` / `hg.web.bind` 白名单仅 `127.0.0.1`；非回环配置**拒绝启动并告警**（`NFR-SEC-3` / `FR-CONFIG-9`）。
- **单一 HGCP 本地 TCP 回环通道**承载事件流与控制指令（行情经事件 `EVT_MARKET_QUOTE` 承载）；**C++ 侧不提供 HTTP/WebSocket**（`CH-006`）。

---

## 2. 环境依赖清单（`NFR-DEPLOY-5`）

### 2.1 实测版本（本机 2026-10-05，采集命令见 §9 `env-check.sh`）

| 依赖 | 要求 | 本机实测 | 备注 |
|------|------|----------|------|
| JDK | **11**（`PROJECT.md` §6） | `openjdk 11.0.32.1 2026-08-18` | 编译目标 11，不得用 JDK 17+ 特性 |
| Maven | 3.x（离线可构建） | `Apache Maven 3.9.12` | `mvn -B -o` |
| CMake | **≥ 3.16**（`NFR-DEPLOY-3`） | `cmake 4.2.3` | FetchContent 双轨 |
| C++ 编译器 | 支持 `-std=c++11 -Wall -Wextra -Werror` | `g++ 15.2.0` | C++11 严格边界（`E3`） |
| protobuf（C++） | 大版本 3，首选 **3.21.12** | `libprotoc 3.21.12`；`GOOGLE_PROTOBUF_VERSION=3021012` | 系统包轨实测在位；见 §2.3 |
| protobuf-java | **3.21.12**（与 C++ 同大版本 §4.2.5） | `protobuf-java-3.21.12.jar`（本地仓库） | `pom.xml` `<protobuf.version>3.21.12` |
| CTP SDK | 官方**分离式**（`ThostFtdcTraderApi.h`+`ThostFtdcMdApi.h`） | **v6.7.13_20260225**（§2.4） | 见 §2.4 |
| DM JDBC 驱动 | 与客户 DM8 实例版本匹配（`RSK-03`） | **未提供**（`java/lib/DmJdbcDriver18.jar` 缺位） | 部署时由客户提供并**实测记录版本/md5**（§2.2） |
| DM8 实例 | 生产数据库 | **不可达**（5236 无监听，§8.1） | 见 §8 |
| 浏览器 | 近两年 Chrome/Edge/Firefox | — | 原生 ES2015+ / RFC 6455；无构建链 |
| 操作系统 | Ubuntu 22.04 LTS（推荐） | 本机 Ubuntu | — |

### 2.2 DM JDBC 驱动（`RSK-03` / `NFR-DEPLOY-5`）

- **要求**：驱动类名 `dm.jdbc.driver.DmDriver`、URL 前缀 `jdbc:dm://host:port`（`DATA_MODEL.md` §6.1 `D15`）。
- **放置**：`java/lib/DmJdbcDriver18.jar`（`pom.xml` 的 `dm` profile 以 `systemPath` 引用）。
- **实测版本**：本环境**无驱动**（全盘无 `DmJdbcDriver*.jar`），故**无法给出实测版本**——部署时必须补测并回填下表：

  | 项 | 采集命令 | 部署时填写 |
  |----|----------|-----------|
  | 驱动文件 | `ls -l java/lib/DmJdbcDriver*.jar` | — |
  | md5 | `md5sum java/lib/DmJdbcDriver*.jar` | — |
  | 类名自检 | `unzip -l <jar> \| grep DmDriver` | — |
  | 与实例匹配 | `select * from v$version;`（DM8 侧） | — |

- **换库可更换性**：仅换驱动类名与 URL 前缀，**须同步本清单**（`NFR-COMPAT-5` / `D15`）；生产环境**不得**使用 SQLite/MySQL/PostgreSQL（`CH-016`）。

### 2.3 protobuf 供给策略（验收 6）

落点：`cpp/cmake/HgDependencies.cmake`（双轨，`T-031`/`T-129`/`T-130` 已交付），与 `cpp/proto_smoke` 同纪律。

| 轨 | 开关 | 供给 | 本机实测 | 备注 |
|----|------|------|----------|------|
| 系统包（首选） | `CTP_USE_FETCH_DEPS=OFF`（默认） | `find_package(Protobuf)` | `libprotoc 3.21.12` + `libprotobuf.so.32` 在位 | `NFR-DEPLOY-3` |
| FetchContent（离线回退） | `CTP_USE_FETCH_DEPS=ON` | 拉取 `protobuf v3.21.12` 源码构建；**强制 SHA256` | 可离线：`-DHG_PROTOBUF_TARBALL=<本地tarball>` | 内置 SHA256 `930c2c3b…ac3a53`；无 SHA256 即 FATAL（不得跳校验） |
| protobuf-lite 降级 | — | `libprotobuf-lite.so` 在位 | 历史降级链（`T-026` 登记） | 属探路结论，非本期默认 |

- **版本锁定**：`HG_PROTOBUF_MAJOR_REQUIRED=3`、`HG_PROTOBUF_PREFERRED_VERSION=3.21.12`；Java 侧 `protobuf-java 3.21.12`，两侧同大版本（`§4.2.5`）。
- **构建命令**：`cmake -S cpp -B cpp/build -DCTP_USE_FETCH_DEPS=OFF`（系统包）或 `=ON`（FetchContent）。

### 2.4 CTP SDK 实盘版本复测（验收 7，承接 `T-027`）

- **复测命令**：`CTP_SDK_ROOT=<官方解包父目录> bash cpp/ctp_smoke/ci/run_probe.sh`
- **本机复测结果（2026-10-05 实测）**：`PASS=12 FAIL=0 RESULT: PASS`（`cpp/ctp_smoke/ci/run_probe.sh`）
- **SDK 自报版本（链接真实动态库取得，非桩）**：`sdk.api.version.trader = v6.7.13_20260225 14:16:30.12079`（`md` 同版本）
- **SDK 根**：`sdk/v6.7.13_20260225_api`（官方分离式，两子目录 `*_traderapi_se_linux64` / `*_mduserapi_se_linux64`）
- **头文件校验和（递归）**：

  | 头文件 | md5 |
  |--------|-----|
  | `ThostFtdcTraderApi.h` | `75c53723db1944f1a3207df0a8444e27` |
  | `ThostFtdcMdApi.h` | `822b1305424d9ac6fe52f6ade768ff1c` |
  | `ThostFtdcUserApiDataType.h` | `87a1c784dd1a5b3d5e8710e513ce54c1` |
  | `ThostFtdcUserApiStruct.h` | `224697e18f6e1f6374ab91c9cf92aed4` |

- **断言摘要（B1~B8，全 PASS）**：B2 `-std=c++11 -Wall -Wextra -Werror` 下编译通过（CTP 头作 SYSTEM 引入）；B3 `__cplusplus==201103`；B4 链接真实 SDK 取到自报版本；B7 产物无 DB 客户端库（附录 B 红线 1）；B8 C++11 边界未放宽；B6/B6b 官方分离式头名成立、合并版不可得（`CH-015` 基线）。
- **残余风险**：实盘柜台 SDK 版本可能与本次不同 ⇒ **部署前按 `NFR-DEPLOY-5` 重新执行本复测并回填版本**。

---

## 3. 配置项清单（`CFG-01`~`CFG-33`，验收 1/4）

**权威来源**：`ARCHITECTURE.md` §11（§11.1 业务口径 / §11.2 基础设施 / §11.3 分类与持久化属性）。
**持久化载体**：`TB-CONFIG-2 t_config_item`（通用 `(key,value,scope,version)` 模型）；**初始化播种**见 `docs/DEPLOY/schema/data-init.sql` 第 ④ 段（**66 键**；含 `CFG-26b` 1 键〔`T-081b` 增补，`ARCH §11` 落登待编排者转技术负责人〕）。

> `data-init.sql` 播种 **`§11.1 全部 37 键 + §11.2 可持久化 28 键 + CFG-26b 1 键（T-081b 增补）= 66`**。以下三项按设计**不播种**：
> `CFG-19`（`hg.security.sensitive_fields`，零落盘仅内存）、`CFG-31`（`hg.db.*`，密码不落盘、部署时填写）、`CFG-32` 的 `hg.web.ws.port`（部署时填写）。
> `CFG-01`~`CFG-18` 及 `b` 后缀子项见 `ARCHITECTURE.md` §11.1 原表（默认值 = 已确认口径，`R-CONFIG-8` 不得静默放宽）。

### 3.1 基础设施与运行参数（`ARCHITECTURE.md` §11.2 摘录，默认值即落地值）

| 配置编号 | 配置键 | 用途 | 默认值 | 持久化属性 |
|---------|--------|------|--------|-----------|
| `CFG-19` | `hg.security.sensitive_fields` | 敏感字段清单（日志脱敏/配置过滤） | `password,auth_code,fund_password,auth_token` | **零落盘（仅内存）** |
| `CFG-20` | `hg.tcp.bind` / `hg.tcp.port` | HGCP 组件通道（本地 TCP 回环） | `127.0.0.1` / `27400` | 本地 admin 可持久化；API 下发不落盘 |
| `CFG-21` | `risk.margin_ratio_warn` / `_critical` | 风险度/保证金占用率告警阈值 | `0.70` / `0.90` | 可持久化 |
| `CFG-22` | `hg.ctp.env_type` | 模拟盘/实盘显式标识 | `SIMULATION` | 可持久化；切 `PRODUCTION` 须二次确认+双人复核 |
| `CFG-23` | `hg.auth.login_fail_limit` / `_lock_minutes` | 登录失败锁定阈值/时长 | `5` / `5` | 可持久化 |
| `CFG-23b` | `hg.auth.session_idle_minutes` / `password_min_length` / `_history_size` | 会话空闲/口令强度/历史重用 | `30` / `12` / `5` | 可持久化（**注意 §10 装载守卫**） |
| `CFG-28` | `hg.tcp.heartbeat_ms` / `hg.cmd_timeout_ms` / `hg.trade.confirm_timeout_ms` | 心跳/命令超时/人工确认超时 | `1000` / `3000` / `60000` | 可持久化 |
| `CFG-29` | `hg.flow.dir` / `hg.flow.fsync_mode` / `hg.flow.retain_days` | 指令流水目录/fsync 模式/保留天数 | `./data/flow` / `BATCH` / `90` | C++ 本地 admin 可持久化 |
| `CFG-30` | `hg.web.root` | 前端静态资源目录 | `./web` | 可持久化 |
| `CFG-31` | `hg.db.*` | JDBC URL / 用户名 / 连接池参数 | 部署时填写 | **密码不落盘**；其余可持久化 |
| `CFG-32` | `hg.web.ws.port` / `heartbeat_ms` / `max_connections` | 前端 WebSocket（`/ws/quote`、`/ws/alert`） | 部署时填写 / `30000` / `200` | 可持久化（本地 admin） |
| `CFG-33` | `hg.web.bind` / `hg.web.port` / `hg.web.threads` | 前端 HTTP（REST+静态） | `127.0.0.1` / `27402` / `16` | 可持久化（本地 admin） |

### 3.2 配置管理分级（`NFR-DEPLOY-4` / `ARCHITECTURE.md` §11.3）

| 分类 | 可持久化 | 下发 C++ | 双人复核 | 示例 |
|------|----------|----------|----------|------|
| CTP 连接类（非敏感） | ✅ 本地 admin | ✅ | 环境切换须复核 | `CFG-22`/`CFG-20` |
| CTP 连接类（敏感） | ❌ **零落盘** | ❌（仅内存） | — | `CFG-19` 所列字段 |
| 风控参数类 | ✅ | ✅ 必须 | 放宽合规红线须复核 | `CFG-01`~`08`/`15`/`16`/`21` |
| 子账户类 | ✅ | ✅ | 创建/变更/停用须复核 | `CFG-02c`/`CFG-10` |
| 组件通信类 | ✅（本地）/❌（API 下发） | — | 鉴权凭据零落盘 | `CFG-20`/`32`/`33` |
| 报告周期/签署类 | ✅ | ⬜ | ✅ | `CFG-14`/`26`/`27` |
| 科目与字典类 | ✅ | ⬜ | ✅ | `CFG-09`、枚举字典 |

### 3.3 配置模板与装载

- 模板：`docs/DEPLOY/hg.properties.template`（复制为 `./hg.properties` 或用 `-Dhg.config=<绝对路径>` 指定）。
- 读取优先级（`common/Config`）：`系统属性 -D` > `属性文件` > `内建默认值`。

---

## 4. 双组件启动顺序（验收 2）

**架构规定（`ARCHITECTURE.md` §2.4.1 / §3.14）**：`hg-trading`（TCP **服务端**，权威状态源）**先**启动 → 启动即 `NOT_READY` → 重放指令流水 → 等 Java 下发全量风控参数 → `READY`；`hg-biz`（TCP **客户端**）**后**启动，Java 建连时 C++ 应已在重放/就绪态。

```text
C++ 启动时序（§4.5.3）：
  ① 读配置(NOT_READY) → ② 打开流水目录/CRC 校验 → ③ 建 HGCP 监听(127.0.0.1:27400，暂不受业务指令)
  → ④ 等 Java 下发 CMD_LOAD_RISK_PARAMS(未收齐对下单回 0x0401) → ⑤ REPLAYING 重放流水(每 1000 条发 EVT_REPLAY_PROGRESS)
  → ⑥ 与 CTP 对账 → ⑦ READY 发 EVT_SYSTEM_STATE(READY) → ⑧ 接受业务指令
```

**脚本**：`scripts/deploy/start-all.sh`（内含 `start-hg-trading.sh` → 等待端口 → `start-hg-biz.sh`）。

> ⚠️ **诚实标注：hg-trading 当前为工程骨架**（`cpp/src/main.cc`：装配/打印六模块 + `--self-test`，**不加载配置、不重放、不监听、无握手/无 READY**）。
> 故 `start-all.sh` 的「等待就绪」在骨架期会超时并**显式告警**，缺省**不阻断** Java 启动（便于前端/后端独立联调），
> 真联环境应以 `HG_REQUIRE_TRADING_READY=1` 强制「未就绪不得启 Java」。**此路径不代表已完成双组件联调**；真联编排承接见 §11。

---

## 5. 健康检查（验收 3）

`scripts/deploy/health-check.sh [biz|trading|all]`（默认 `all`）：

| 组件 | 检查项 | 依据 |
|------|--------|------|
| hg-biz | 进程存活；`GET /api/v1/ping`（公开存活探针，不返回业务数据）；`GET /web/index.html`（静态托管） | `SessionAuthFilter` 公开路径 |
| hg-trading | 进程存活；HGCP 回环端口可连 `127.0.0.1:27400` | `CFG-20` |

- **退出码**：`0` 全通过；`1` 有 FAIL；`2` 前置缺失。
- **本机实测（2026-10-05）**：`hg-biz` 3/3 PASS（ping 返回 `{"code":0,...,"status":"UP"}`）；`hg-trading` 2/2 FAIL（骨架未实化，如实反映）。总 `PASS=3 FAIL=2`，退出码 1。

---

## 6. 前端与后端独立升级（验收 3，`ARCHITECTURE.md` §7.1.9）

- **形态**：Java 以**文件系统目录**静态托管前端（`CFG-30 hg.web.root`，默认 `./web`）；**零构建链**（`NFR-MAINT-1`）。
- **独立升级操作**：替换 ``{HG_WEB_ROOT}``` 目录内容 → 浏览器刷新即生效，**前端改动不需重新构建后端**；后端升级只需替换 `java/target/hg-biz.jar`（前端目录不变）。
- **安全**：静态托管做扩展名白名单 + 路径规范化 + 拒绝 `..`（`StaticHandler`）；前端只与 Java 的 `/api/v1/**` 与 `/ws/**` 通信（`NFR-MAINT-5`）。
- **约束**：`web/` 下**不得**出现 `package.json`/`node_modules`/`dist`/`*.bundle.js`/`*.min.js`（`E6` 判据）。

---

## 7. 部署步骤清单（`NFR-DEPLOY-2`，目标 ≤ 30 分钟）

> 不含 DM8 安装与操作系统准备（`NFR-DEPLOY-2` 口径）。命令以仓库根为工作目录。

| 步 | 操作 | 命令 | 预估 |
|----|------|------|------|
| 1 | OS/工具链就绪 | `bash scripts/deploy/env-check.sh`（应 RC=0） | 预置 |
| 2 | 放置 DM JDBC 驱动 | `cp DmJdbcDriver18.jar java/lib/` 并登记版本/md5（§2.2） | 1 min |
| 3 | 构建 hg-biz | `cd java && mvn -B -o -Punit -DskipTests package && mvn -B -o dependency:copy-dependencies -DoutputDirectory=target/dependency` | 5 min |
| 4 | 构建 hg-trading | `cmake -S cpp -B cpp/build -DCMAKE_BUILD_TYPE=Release -DCTP_USE_FETCH_DEPS=OFF && cmake --build cpp/build -j` | 5 min |
| 5 | 建库建表 | 按 §7.1 顺序在 DM8 执行 `docs/DEPLOY/schema/*.sql`（**先读 §8.4 循环外键说明**） | 5 min |
| 6 | 播种初始化数据 | 执行 `docs/DEPLOY/schema/data-init.sql` | 1 min |
| 7 | 写配置 | `cp docs/DEPLOY/hg.properties.template hg.properties` 并按 §3 改值（**不写口令**） | 2 min |
| 8 | 注入敏感凭据（仅内存） | `export HG_DB_PASSWORD=…; export HG_HGCP_AUTH_TOKEN=…`（**不写盘**） | 1 min |
| 9 | 启动 | `bash scripts/deploy/start-all.sh` | 1 min |
| 10 | 健康检查 | `bash scripts/deploy/health-check.sh` | 1 min |

### 7.1 建表脚本执行顺序

脚本头部声明的顺序 + **两阶段（后置外键）** 要求如下：

```text
阶段 A（基表 + 后置外键回补）：
  1) m1_config_auth.sql   —— 暂缓文件末尾「跨模块外键」段（fk_authorization_product → t_product）
  2) m1_product.sql
  3) 回补 1) 暂缓的跨模块外键段
  4) m1_spot.sql
  5) m1_plan.sql          —— 暂缓文件末尾 fk_trade_plan_strategy → t_option_strategy 段
  6) m2_trade.sql
  7) 回补 5) 暂缓的跨模块外键段
  8) m2_settle.sql        —— 仅执行**建表段**；暂缓文件末尾 fk_t_settle_fund_ledger → t_fund_ledger 段
  9) m2_fund.sql
 10) 回补 8) 暂缓的 fk_t_settle_fund_ledger 段
 11) m3_risk.sql
 12) m3_audit.sql
 13) data-init.sql
```

> `m1_config_auth.sql` / `m1_plan.sql` / `m2_settle.sql` 的跨模块外键段均已由脚本作者显式置于文件末尾并注明「暂缓」；三处两阶段后置**消解了 CONFIG↔PRODUCT、PLAN↔TRADE、SETTLE↔FUND 三个跨模块环**（§8.4）。
> 判据（可复跑）：`python3 docs/DEPLOY/tools/fk-order-check.py` —— 按本顺序静态断言各 FK 父表在建表/回补时已存在，退出码 0=PASS。

---

## 8. DM8 实机建表（验收 5）

### 8.1 环境可达性实测（2026-10-05）

| 探测 | 命令 | 结果 |
|------|------|------|
| 端口 | `ss -ltnp \| grep 5236` | 无监听 |
| 进程 | `ps -ef \| grep dmserver` | 无 |
| 驱动 | `find / -iname 'DmJdbcDriver*.jar'` | 无 |
| 驱动目录 | `ls java/lib` | 不存在 |
| 结论 | — | **DM8 不可达 ⇒ 验收 5 = 未达（环境前置缺失）** |

### 8.2 已交付替代证据（静态逐列核对，可复跑）

```bash
python3 docs/DEPLOY/tools/reconcile-schema.py            # 退出码 0=PASS
python3 docs/DEPLOY/tools/reconcile-schema.py --json     # 机读
```

**本机结果（2026-10-06，落 `docs/DEPLOY/schema-reconcile-result.txt`）**：

- 建表脚本表数 = **99**（CONFIG+AUTH 20 / PRODUCT 8 / SPOT 10 / PLAN 8 / TRADE 9 / FUND 9 / SETTLE 16 / RISK 11 / AUDIT 8）
- DATA_MODEL.md 表数 = 111；已核对 = 99；**失败 = 0**；结果 = **PASS**
- 尚未建脚本的 12 张（M3 的 ACCT 等，非失败）：`t_hedge_relation`、`t_effectiveness_assess`、`t_voucher`、`t_dual_pnl_report` …（全表见结果文件）
- **核对口径**：**列名集合级**（脚本 CREATE TABLE 的列 ↔ 文档 §2.x 字段表列；通用审计字段 `§0.3` 6 列计为允许的额外列）。
- **更深一层证据（类型/精度/非空/索引/外键级）**：既有 `*SchemaTest`（`ConfigAuthSchemaTest`/`M1ProductSpotPlanSchemaTest`/`M2SettleSchemaTest`/`TradeSchemaTest`/`FundSchemaTest`/`RiskParamDispatchSchemaTest`/`M3RiskSchemaTest`/`M3AuditSchemaTest`）。本席复跑：`mvn -B -o -Punit test -Dtest='*SchemaTest'` ⇒ **37/37 通过，0 失败**。
- **明确标注**：以上均为**制品内部一致性**，**不替代** DM8 实机建表验证（`CH-016`：不得以 SQLite 冒充 DM8）。

### 8.3 待补（DM8 环境具备后）

1. 在 DM8 实例按 §7.1 顺序执行全部 `schema/*.sql` + `data-init.sql`（含 §8.4 修订后）；
2. 执行 `reconcile-schema.py` 复核 99/99；
3. 记录 DM8 版本（`v$version`）与 DM JDBC 驱动版本/md5，回填 §2.1/§2.2；
4. 以真实 DAO 跑读写与约束强制（等价于既有 `*Dm8EquivalentIntegrationTest` 的实机版）。

### 8.4 建表脚本跨模块循环外键（DM8 单遍执行阻断）—— 已修订

静态核对**只比列名，不判外键可满足性**，故不会发现本项；T-052 经人工取证发现三处跨模块环，现均按「文件末尾后置 ALTER + 两阶段回补」口径处置：

- `CONFIG↔PRODUCT`：`m1_config_auth.sql` 已后置 `fk_authorization_product → t_product`（§7.1 步 1 / 回补步 3）
- `PLAN↔TRADE`：`m1_plan.sql` 已后置 `fk_trade_plan_strategy → t_option_strategy`（步 5 / 回补步 7）
- `SETTLE↔FUND`：**T-179 已修订**——原 `m2_settle.sql` 的 `t_settle_fund.fund_ledger_id → t_fund_ledger`（`FUND`，**原为内联**）已后置至该文件末尾 ALTER 段；FUND 侧仍保留内联（`t_fund_flow.settle_cycle_id → t_settle_cycle`、`t_transfer_apply.agg_check_id → t_aggregation_check`、`t_fund_reconcile.agg_check_id → t_aggregation_check`，均指向 SETTLE）
  ⇒ 两阶段执行：`m2_settle.sql` **建表段**先于 `m2_fund.sql`；后置的 `fk_t_settle_fund_ledger` 在 `m2_fund.sql` 之后回补（§7.1 步 8 / 回补步 10）。

**修订后执行顺序**：`… → m2_trade.sql → m2_settle.sql（建表段） → m2_fund.sql → 回补 settle 后置外键 → m3_risk.sql → m3_audit.sql → data-init.sql`。

**判据（可复跑）**：

```bash
python3 docs/DEPLOY/tools/fk-order-check.py        # 退出码 0=PASS
python3 docs/DEPLOY/tools/fk-order-check.py -v     # 逐条 FK 边明细
```

根据 §7.1 顺序静态断言：每条内联 FK 的父表在其子表建表时已存在；每个后置 ALTER 的父表须在该 ALTER **实际执行时点**已存在——**跨模块**后置 ALTER 在 §7.1 回补步校验（`m1_config_auth` 步 1/回补步 3、`m1_plan` 步 5/回补步 7、`m2_settle` 步 8/回补步 10），**模块内**后置 ALTER（父表定义于本文件靠后，如 `m2_trade.sql`/`m2_fund.sql` 文件末尾的 ALTER 段）在本文件建表段完成后随即校验；同文件内按依赖排序，跨文件按 §7.1 固定顺序。统计口径为**唯一 FK 边**（去重后的有向「子表→父表」，同一父表被多列引用只计一条）。**本机实测：99 张表、170 条唯一 FK 边（含模块内后置 ALTER）、0 违约、`RESULT: PASS`**（无 DM8，纯静态）。DM8 实机执行仍待环境（§8.1）。

另注：本判据为**静态拓扑**证据，**不替代** DM8 实机建表（`CH-016`）。

---

## 9. 脚本清单与退出码

| 脚本 | 作用 | 退出码 |
|------|------|--------|
| `scripts/deploy/common.sh` | 公共函数（配置读取/进程/端口/classpath） | — |
| `scripts/deploy/env-check.sh` | 环境依赖探测与实测版本（`NFR-DEPLOY-5`） | 0 齐备 / 1 缺构建必需项 |
| `scripts/deploy/start-hg-trading.sh` | 启动 C++ 组件（支持 `--self-test`） | 0 已启动/已运行 |
| `scripts/deploy/start-hg-biz.sh` | 启动 Java 组件（注入 JVM 安全参数，就绪探测 ping） | 0 就绪 / 1 失败 |
| `scripts/deploy/start-all.sh` | 按序启动双组件 + 等待 C++ 就绪 | 0 / 1（`HG_REQUIRE_TRADING_READY=1` 未就绪时） |
| `scripts/deploy/health-check.sh` | 双组件健康检查 | 0 全通过 / 1 有 FAIL / 2 前置缺失 |
| `scripts/deploy/stop-all.sh` | 停机（先 biz 后 trading；SIGTERM→SIGKILL） | 0 |

**hg-biz 启动安全参数（`NFR-SEC-1` ④）**：`-XX:-CreateCoredumpOnCrash -XX:ErrorFile=/dev/null -Dhg.coredump.off=true`（脚本已内置）。

---

## 10. 敏感字段零落盘声明（验收 4 / `NFR-SEC-1` / `NFR-DEPLOY-4`）

**本部署文档与全部脚本、模板显式声明并遵循以下红线**：

1. **口令、鉴权令牌、资金密码等敏感字段零落盘** —— 只允许经**进程环境变量**注入：数据库口令 `HG_DB_PASSWORD`、HGCP 鉴权令牌 `HG_HGCP_AUTH_TOKEN`；仅驻内存（`char[]`），用毕清零。
2. **属性文件装载守卫**（`Config.rejectSecrets`）：`hg.properties` 中键名含 `password`/`authcode`/`auth_code`/`token`/`secret` 的项会被**拒绝装载并导致启动失败**。
3. **⚠️ 口令策略项陷阱**：`hg.auth.password_min_length` / `hg.auth.password_history_size` **含 `password` 字样** ⇒ **不得写入 `hg.properties`**；如需覆盖，只能经 JVM 参数（如 `-Dhg.auth.password_min_length=12`）或使用内建默认值。
4. **审计留痕**：配置变更落 `TB-CONFIG-3`，**敏感字段只记状态、不记明文**（`ARCHITECTURE.md` §11.4）。
5. **自查**：`docs/DEPLOY/hg.properties.template` 不含任何口令/令牌；`scripts/deploy/**` 不对敏感值做回显（`start-hg-biz.sh` 只判「是否已注入」）。
6. **核心转储关闭**：以 `start-hg-biz.sh` 内置 JVM 参数启动，避免内存口令随 core dump 落盘；缺参数时进程会告警。

---

## 11. 未达项与承接登记

| # | 项 | 状态 | 承接 |
|---|----|------|------|
| U-1 | DM8 实机建表跑通（验收 5） | **未达-环境前置缺失** | 环境具备后按 §8.3 补；本任务不判 DONE |
| U-2 | DM JDBC 驱动实测版本（`RSK-03`） | **未达-驱动缺位** | 部署时提供并回填 §2.2 |
| U-3 | 双组件真联（C++ 就绪态 → Java 连接 → 补数/参数下发） | **未达-骨架未实化** | 真联编排任务（`main.cc` 实化）；本项目多单已登记同一承接点 |
| U-4 | SETTLE↔FUND 循环外键致 DM8 单遍建表失败（§8.4） | **已修订（T-179：后置 ALTER + 两阶段回补；待独立复核）** | 脚本侧已消解；DM8 实机验证并入 U-1 |
| U-5 | 前端浏览器真机验证 | 未做 | 属前端任务；本席仅脚本级（curl）验证 |

---

*本文件由 backend-engineer#java2 席位实例 be-t052 于 T-052 交付；行号/md5 引用以实时为准；DM8/CTP/protobuf 证据见 §2、§8。*

---

## 12. 数据库账户权限清单（`NFR-SEC-8` / `CH-025` / `T-199`）

> **依据**：`REQUIREMENTS/NONFUNCTIONAL.md:125-127`（`NFR-SEC-8` 原文与验收）、
> `TASKS.md:240`（`T-199` 立号行·丢失线第三例重做）、`BOARD.md:469`（`CH-025` 禁 DELETE 分域细化裁定落账·第 253 轮收官行）、
> `DATA_MODEL.md:124-128`（§0.3「例外（允许物理 DELETE）」注）、`schema/data-init.sql:9`、
> `java/src/main/java/com/hedgeguard/common/Config.java:63-72`、`java/.../web/Bootstrap.java:278-279`、
> `java/.../dao/impl/DmLoginSessionDao.java:21`、`DmPasswordHistoryDao.java:22`、`hg.properties.template:45-50`。
> 行号以本单提交时点（2026-10-27）实测为准，以实时为准。

### 12.0 五判据（先定义后施工）

本节系 `T-199` 重做单（`TE-T086-V-001`：原贴标 `82c91d5` 系丢失线、五判据细节不可考）。以下五判据由产出方依上列现行依据**合法重定义**，逐条显式列依据：

| # | 判据 | 内容 | 依据（行号级） |
|---|------|------|----------------|
| G1 | 账户清单完备 | 部署文档记录全部数据库访问账户（账户×连接面×来源），即 §12.1 | `NONFUNCTIONAL.md:127`（验收 2「部署文档记录账户权限清单」） |
| G2 | 应用账户高危权限禁绝 | 应用账户**不具**建库/删库/DDL/授权（GRANT/REVOKE）权限 | `NONFUNCTIONAL.md:125`（「应用账户不得具备建库、删库、授权等高危权限」）；`data-init.sql:9`（「应用账号禁 INSERT 之外的 DDL」）；`Config.java:63`（javadoc「仅 SELECT/INSERT/UPDATE，`NFR-SEC-8`」） |
| G3 | 禁超级账号连接 | 连接面**唯一**入口 = `hg.db.user` 专用应用账户；禁止以 SYSDBA 等数据库超级账号连接 | `NONFUNCTIONAL.md:125`（「禁止使用数据库超级账号连接」）；`Config.java:64`（`KEY_DB_USER` 为唯一账号键） |
| G4 | DELETE 分域（`CH-025`） | 应用账户 DELETE 仅覆盖豁免域 7 表 8 处（§12.3 逐处对账）；审计域与已生效业务台账 DELETE=无 | `BOARD.md:469`（CH-025「8 处逐处核实均豁免域」）；`DATA_MODEL.md:124-128`（§0.3 例外注）；`DmLoginSessionDao.java:21` / `DmPasswordHistoryDao.java:22`（契约要求 javadoc） |
| G5 | 判据可证伪 + 验证可达 | 矩阵/计数判据用非平凡形态（==111 表、==7 表、==8 处）并附改前负控；部署时按 §12.4 以应用账户尝试 DDL/授权**应失败** | `NONFUNCTIONAL.md:127`（验收 1）；T-203 判例（禁用改前即 0 命中的盲形态） |

**判据形态负控（改前实测 2026-10-27）**：`grep -c "数据库账户权限清单" docs/DEPLOY/README.md` = **0**（本节追加前）；追加后承载本清单。可证伪判据 = §12.2 矩阵域行数（==3）、豁免 DELETE 表数（==7）、DELETE 语句站点数（==8）、审计域 UPDATE/DELETE 语句数（==0，实测 `grep -c '"UPDATE'` / `'DELETE'` 于 `DmAuditLogDao`/`DmOperationLogDao`/`DmAuditLogChecksumDao`/`DmViolationScreenRunDao`/`DmViolationScreenItemDao`/`DmAuditReportDao`/`DmEvidenceExportDao`/`DmMajorEventDao` 八文件均 0）——非「0 命中」盲形态。

### 12.1 账户清单（G1）

| 账户 | 用途 | 连接面/来源（实测） | 最小权限定位 |
|------|------|---------------------|--------------|
| 应用账户（模板示例名 `HG_APP`） | hg-biz（Java）**唯一写库入口**（§1 拓扑） | `hg.db.user`（`hg.properties.template:46`；`Config.java:64`）+ 口令唯一来源环境变量 `HG_DB_PASSWORD`（`Config.java` `ENV_DB_PASSWORD`；**无** `hg.db.password` 属性键，`rejectSecrets` 拒绝任何含 `password` 键名） | §12.2 矩阵 |
| 部署账户（DBA） | 执行 `schema/*.sql` 建表/播种与 §12.4 授权；**仅部署期使用，运行期不连接** | `data-init.sql:9`（「执行者：DBA」）；§7 步 5-6 | DDL 全量（限建库窗口）；运行期应锁定 |
| 审计数据库账户 | **无独立账户（诚实披露）** | 审计域写入由应用账户单点承载（`AuditLogService` 单写入口；T-075 M3 合规审查判据「审计旁路=0」成立） | 不适用 |
| hg-trading（C++） | **无数据库账户** | E1 红线：C++ 零 DB I/O、零 SQL（`NFR-SEC-9`；`ARCHITECTURE.md` E1） | 不适用 |

连接面实测（`Config.java:57-72`）：DB 相关键仅 `hg.db.url` / `hg.db.user` / `hg.db.driver` / `hg.db.pool.*` + 环境变量 `HG_DB_PASSWORD`，**无第二账号键**。装配开关：`hg.db.url` 为空即不接库（`Bootstrap.java:278-279` 条件装配）。

### 12.2 权限矩阵（应用账户 × 对象域，G2/G3/G4 判定）

对象全集 = `docs/DEPLOY/schema/` 10 个建表脚本 **111 张表**（判据命令 `grep -h '^CREATE TABLE' m*.sql \| wc -l` = 111；分脚本：m1_config_auth 20 / m1_product 8 / m1_spot 10 / m1_plan 8 / m2_trade 9 / m2_settle 16 / m2_fund 9 / m3_risk 11 / m3_audit 8 / m4_acct 12）+ `data-init.sql`（种子，仅 INSERT）。

| 对象域 | SELECT | INSERT | UPDATE | DELETE | DDL/授权 | 判定 |
|--------|--------|--------|--------|--------|----------|------|
| 全部 111 表（通用 DML 面） | 授予 | 授予 | 授予 | **拒绝**（仅 §12.3 豁免 7 表例外） | **拒绝** | 应用为唯一写库入口（§1），DML 下限即业务所需；高危权限零授予（G2/G3）。逐表更细粒度收紧（如只读表仅 SELECT）为部署方可选项，不影响本清单合规性 |
| 豁免域 7 表（§12.3：t_dict_item / t_login_session / t_sales_contract / t_production_plan / t_password_history / t_purchase_contract / t_hedge_plan） | 授予 | 授予 | 授予 | **授予（仅此 7 表）** | 拒绝 | CH-025 豁免域；与 `DATA_MODEL.md` §0.3 例外注（:124-128）同源（G4） |
| 审计域 m3_audit 8 表（t_operation_log / t_audit_log / t_audit_log_checksum / t_violation_screen_run / t_violation_screen_item / t_audit_report / t_evidence_export / t_major_event） | 授予 | 授予 | **拒绝**（制品内 UPDATE=0，见 §12.0 负控） | **拒绝** | 拒绝 | CH-025：审计域禁 DELETE（G4）；只增不改（`DATA_MODEL.md` §0.3） |

可证伪计数：矩阵域行 == 3；豁免 DELETE 表 == 7；DELETE 语句站点 == 8（§12.3）。

### 12.3 DELETE 分域与 CH-025 八处对账（G4）

CH-025 裁定（`BOARD.md:469`）：禁 DELETE 限定为**审计域与已生效业务台账**；豁免 = `DATA_MODEL.md` §0.3 例外态单据（仅 DRAFT 可删）+ `t_dict_item` 未引用项 + 运维清理。`java/src/main` 全库实测 DELETE 语句恰 **8 处**（命令 `grep -rn "DELETE FROM" java/src/main --include=*.java`），逐处对账：

| # | DAO:行号 | 表 | 豁免域归属 | 依据 | 生产调用面（实测披露） |
|---|----------|----|-----------|------|------------------------|
| 1 | `DmDictItemDao.java:111` | t_dict_item | 字典未引用项删除 | §0.3 例外注（`DATA_MODEL.md:126`） | 现无生产调用点（DAO 契约能力） |
| 2 | `DmLoginSessionDao.java:139`（deleteExpired） | t_login_session | 会话过期运维清理 | §0.3（:128）+ `DATA_MODEL.md:285`（TB-AUTH-9「可物理删除」） | 现无生产调用点（`DmLoginSessionDao.java:21` 明示系契约要求） |
| 3 | `DmLoginSessionDao.java:145`（deleteById） | t_login_session | 会话登出清理 | 同上 | 同上 |
| 4 | `DmSalesContractDao.java:127` | t_sales_contract | 草稿态单据（仅 DRAFT） | §0.3（:127） | 现无生产调用点（同族 DRAFT 门禁先例见 #7） |
| 5 | `DmProductionPlanDao.java:99` | t_production_plan | 草稿态单据（仅 DRAFT） | §0.3（:127） | 现无生产调用点 |
| 6 | `DmPasswordHistoryDao.java:121`（deleteBeyond） | t_password_history | 口令历史滚动清理（运维） | TB-AUTH-11（`DATA_MODEL.md:903`）；`DmPasswordHistoryDao.java:22` javadoc 明示「非业务留痕」 | **已接线**：`AuthService.java:481`（口令变更滚动保留 N 条） |
| 7 | `DmPurchaseContractDao.java:122` | t_purchase_contract | 草稿态单据（仅 DRAFT） | §0.3（:127） | **已接线**：`SpotService.java:436`（`deleteDraftPurchaseContract`，:428-431 DRAFT 门禁，非 DRAFT 抛 STATE_NOT_ALLOWED） |
| 8 | `DmHedgePlanDao.java:215` | t_hedge_plan | 草稿态计划（仅 DRAFT） | §0.3（:127） | 现无生产调用点 |

- **审计域核对**：8 处中 **0 处**命中 m3_audit 8 表（`t_audit_log` 不在其中）——CH-025 禁域成立。
- **软删澄清**：`AcctService.deleteDraftVoucher`（:2288）凭证草稿「删除」系 **UPDATE 置 REVERSED 留痕**（非 DELETE SQL），不在 8 处内、不产生 DELETE 权限需求（FR-ACCT-13 留痕承载，`AcctService.java:2280` javadoc）。
- **调用面口径**：DELETE 权限授予按**部署制品内可执行能力**计（8 处语句均在制品中，账户缺 DELETE 将使契约行为运行期失败）；其中 2 处现有生产调用路径、6 处为 DAO 契约能力（现无调用点）。此为如实披露——豁免域逐处有 §0.3 / TB-AUTH-11 显式依据，不构成越权放开。

### 12.4 部署时授权与验证步骤（G5 / `NFR-SEC-8` 验收）

1. DBA 以部署账户按 §12.2 矩阵对应用账户执行授权（DM8 具体 GRANT 语法由 DBA 按 DM8 实测版本确定——本环境无 DM8 实例（§8.1），**未实机执行**，与 U-1/U-2 同口径承接）；
2. **负向验证（验收 1）**：以应用账户尝试 `CREATE TABLE` / `DROP` / `GRANT` 应失败；尝试对审计域 8 表 `DELETE`/`UPDATE` 应失败（权限层面拒绝）；
3. **正向验证**：应用账户完成 §7 步 6 播种核对、`/api/v1/ping`、登录与一笔业务写入链路；
4. 结果回填本节（部署时点 + 执行人），作为 `NFR-SEC-8` 验收 1/2 终验证据。

### 12.5 已知表述张力与披露

- `Config.java:63` javadoc 记「仅 SELECT/INSERT/UPDATE，`NFR-SEC-8`」——系 CH-025（第 253 轮用户裁定）**之前**的一般口径；CH-025 分域细化后豁免域 7 表需 DELETE。javadoc 修订属 Java 改动、不在本单边界（Java 零改动），以**本节为权威口径**，javadoc 对齐留待他单承接。
- 本环境无 DM8（§8.1）⇒ §12.4 授权与双向验证未实机执行，部署时回填（与 U-1/U-2 同口径）。
- 行号引用以本单提交时点为准（2026-10-27），以实时为准。
