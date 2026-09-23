# 接口冻结清单（FREEZE）

> **冻结标识**：`HGC-1.6`（`HGC-1.5` → `HGC-1.6`：**`payload` 键名严格限定为协议表字段**（禁止协议外字段，含内部关联标识）+ `data-init.sql` 的 `t_product(id=0)` 保留行纳入机器校验；纯增量）
> **`HGC-1.5`（前序）**：**`param_version` 取号口径收紧**（唯一基准 = `maxParamVersion()+1`、同临界区、**禁用行数代替**）+ **超时分支字段值明确**（`ack_time = NULL`）+ 新增 `ParamDispatchDao.maxParamVersion()`；纯增量）
> **冻结时间**：2026-09-20（`HGC-1.0` = `T-003`/`T-004` 交付；`HGC-1.1` = 补 WebSocket 约定 + 选型论证；`HGC-1.2` = 裁定登记 + 编辑登记表 + `ParamChange.payload` 键名冻结；`HGC-1.3` = 键名对齐 + 锚点更新）
> **`HGC-1.0` → `HGC-1.1` → `HGC-1.2` 差异（均**增量**，`#java2` 已开工的产出**无需返工**）**：
> ①（1.1）新增 **§4.1 WebSocket 对外约定**（`CH-006` 要求）与 **§4.2 选型论证**；
> ②（1.2）§4.1.2 的 `W-2`~`W-6` 由"待批"改为**已裁定生效**（`W-1` 按提案实现并保留「待批」标注）；
> ③（1.2）§5 新增依赖准入判据 `W-9`；§5.1 新增**编辑登记表**；文件头新增**唯一写者声明**；
> ⑤（1.2）**新增 §3.2.1 `ParamChange.payload` 键名冻结（字段级）** —— 为解阻 `#java2` 的 `T-011`：
>   冻结 `CMD_SET_TRADING_SUSPEND` / `CMD_UPDATE_QUOTA` 的 payload 键名与语义、**限额下调两阶段的 `suspend_id` 配对撤销**、
>   以及**下发超时计时归属（L3 编排侧，非 `TradingBridge` 内）**；并交付 `T-061` 首个增量（`TB-RISK-2 t_param_dispatch` 的 DDL + DAO）；
> ④ **未改动**任何签名、DTO 字段、错误码、REST 路径。
> ⑥（1.3）**配置键名对齐 `CFG-33`**：实现内 `hg.http.bind` / `hg.http.port` / `hg.http.threads`
>   → **`hg.web.bind` / `hg.web.port` / `hg.web.threads`**（`threads` 默认 **16 → 32**）。`D-5`/`D-9` **闭环**。
>   ⚠️ **`#java2` 注意**：`hg.http.*` 与 `hg.ws.bind` **均已废弃**，不得在新代码中出现（见 §4 与 `#java` 的协同消息）。
> ⑦（1.3）**锚点更新至 `ARCHITECTURE.md` v1.5.3**（**当前实测** md5 `522b2ef4e44737c26ac56a4d32965688` / 2572 行 / 287253 B / mtime `20:52:22`；**连采 3 次跨 6 s 恒定** ⇒ 写入已停止）：
> ⚠️ **本锚点的一次中间态留痕**：同一席位先前记录的 v1.5.3 md5 为 `a35bb076c4633c4d6b84d3790a0db251`（**同为 2572 行**）——那是 `tech-lead-15` 在同一修订轮**尚未写完 ② 连锁订正**时的中间态。**教训（与 `SIGN-OFF.md` §8.8 第 11 条同源）**：**冻结指纹只在写者停止后才有效**；行数相同不代表内容未变，**判据只能是「多次采样 md5 恒定」**。
>   **`W-7` 已落地**（§7.1.11 选项 A 措辞 → **A′**，旧措辞残留 **0**）、**`RSK-06` 定调 A′**（§7.2.2 登记「HTTP/WebSocket 轻量库 ❌ 不引入」）、
>   **`CFG-32` 取值已填**（`hg.web.ws.port` 独立/仅回环、`heartbeat_ms` **30000**、`max_connections` **200**）、
>   **`CFG-33` 已登记**、**§4.1.2 已补 `W-2` 安全必选**（推送前按 `t_user_sub_account_perm` 过滤，安全默认空集，越权 `10004`）。
>   复核不变量：业务码仍 **22 行**、附录 B 仍 **10 条红线** —— **对本冻结无破坏**。
> **冻结人**：`backend-engineer#java`（Java 业务组件唯一写者）
> **冻结工单**：`T-003`（工程骨架 + HTTP 服务基座）、`T-004`（DAO 基座 + CONFIG/AUTH 20 张表 DDL）
> **上游锚点**：
> - `docs/DATA_MODEL.md` **v1.3.3**（md5 `c399c5f595eba8116187acc6dac4e9eb`，5245 行） —— ⚠️ **本会话内该文件多次演进**（v1.3.2 `99b647ec…`/5177 → `68a0a55d…`/5198 → `8034177c…`/5225 → **v1.3.3 `c399c5f5…`/5245**）；v1.3.3 = **`DC-041`（同编号改写）**：`t_exposure.product_id` 改**恒 `NOT NULL` + 未映射哨兵值 `0`**、新增列 `spot_category_code`、`uk_exposure_key` 扩为 6 列、新增 2 条具名 `CHECK`，并**要求 `t_product` 新增保留系统行 `id=0`**（见 §6 `D-18`，已落地）。**引用前必重测 md5**
> - `docs/ARCHITECTURE.md` **v1.5.3**（md5 `02e0316dd249f6f7282f37974737564c`，2586 行） —— ⚠️ **第 5 次实测漂移**（`522b2ef4…`/2572 → `11dae683…`/2578 → **`02e0316d…`/2586**，系 `tech-lead-16/19` 落 E5 判据与 v1.5.3 收口）；**引用前必重测 md5**
>   —— ⚠️ **锚点漂移已复核（本会话内第 3 次）**：施工开始 **v1.3**（`641b481b…`/2418）→ 收尾 **v1.5**（`9feae927…`/2516）
>   → 复核时 **v1.5.2**（`32babcac…`/2546，经 `v1.5.1`/`v1.5.2` 两轮**勘误**）。
>   **v1.5.1/v1.5.2 均自述"非 `CH-xxx`、不改变架构分层与接口契约"**（消息集 40 / 错误码 62 / `VER 0x0100` 不变）；
>   我已**重新实测**：§4.3.1 业务码仍 **22 行（10001~10022）**、附录 B 红线仍 **10 条**、静态托管白名单不变、
>   §7.2.2 仍 `HikariCP 不引入`、`CFG-32/33` 在位 → **对本冻结无影响**（见 §6 `D-7`）。
>   **引用规则**：一律以**实时 `md5sum`** 为准，勿沿用任何派发单/gate 文件声明的 md5。
> - `PROJECT.md` v1.2
> **依据纪律**：`ARCHITECTURE.md` §1.2「契约冻结纪律」（先冻结再并行）、编排规则 §6.3.4
> **解阻对象**：`backend-engineer#java2` 的全部 29 条任务
>
> 🔒 **本文件的唯一写者 = `backend-engineer#java`**（编排者 2026-09-20 裁定）。
> 其他席位**不得直接编辑本文件**；确需变更（新增方法 / 字段 / 路由 / 约定）→ **投 `question` 给 `backend-engineer#java` 落笔**，不得先改后报。
> 理由：本文件是 `#java2` 与前端**共同依据的契约冻结单一权威载体**，双写会导致各方依据的版本漂移（正是 §1.2 要防的事）。
> **署名要求**：任何对本文件的编辑须在 `## 5. 变更纪律` 末尾登记「时间 / 编辑人 / 内容摘要 / 版本是否升版」。

---

## 0. 冻结范围与效力

| # | 冻结对象 | 载体 | 变更方式 |
|---|---------|------|---------|
| ① | **REST 契约** | `com.hedgeguard.web` 的 `ApiRoutes` / `ApiResponse` / `ErrorInfo` / `ErrorCode` / `PageData` | 先改 `ARCHITECTURE.md` §4.1.1 → 再改代码；破坏性变更须 `CH-xxx` |
| ② | **包结构与公共层接口** | 6 个顶层包 + `bridge` / `dao` 的公共接口 | 见 §2；**签名逐字冻结** |
| ③ | **共享 DTO** | `com.hedgeguard.web` / `com.hedgeguard.bridge` / `com.hedgeguard.dao.entity` | 字段级新增可（向后兼容）；删除/改类型须 `CH-xxx` |

**效力说明**：`#java2` 可在**不修改**本文任何条目的前提下并行开工全部 29 条任务。
若某任务确需变更本文条目，**先投 `question`/`CH-xxx`**，不得先改后报（`backend-engineer.md` §8）。

---

## 1. REST 契约（冻结）

### 1.1 通用约定（源自 `ARCHITECTURE.md` §4.1.1）

| 约定项 | 定义 |
|--------|------|
| 基础路径 | `/api/v1/`；资源清单见 `ApiRoutes.byModule()`（11 模块，逐字取自 §4.1.1） |
| 方法语义 | 查询 / 新增或动作 / 修改 / 草稿类物理删除（详见 §4.1.1） |
| 请求体 | `application/json; charset=UTF-8`（导出除外） |
| 成功信封 | `{"code":0,"message":"OK","data":<T>,"traceId":"..."}` |
| 错误信封 | `{"code":<业务码>,"message":"...","ruleId":"...","current":"...","threshold":"...","nextStep":"...","traceId":"..."}` |
| 阻断类错误 | **必须**含后四项 → `ErrorCode.thresholdBlocking()`；构造入口仅 `BizException.blocking(...)` |
| 分页 | `?page=1&size=20&sort=field,asc`；`size ∈ {10,20,50,100}`，默认 20；响应 `data.items/total/page/size` |
| 幂等 | 写类接口要求头 `Idempotency-Key`（出入金 / 划拨 / 下单 / 参数下发） |
| 时间 | ISO-8601 `yyyy-MM-dd'T'HH:mm:ss`，时区 `Asia/Shanghai`；纯日期 `yyyy-MM-dd` |
| 金额/数量/价格 | **字符串化十进制**（`"1234.5600"`）；**禁止** JSON number（JS 精度） |
| 鉴权 | Cookie `HGSESSION`（HttpOnly）或头 `X-HG-Session`；追踪头 `X-HG-TraceId`（可入可出） |
| JSON 细节 | `null` 字段**不序列化**（故错误响应无 `data`、非阻断错误无四项）；禁止 HTML 转义 |

### 1.2 公开路径白名单（冻结，`SessionAuthFilter.PUBLIC_EXACT`）

| 路径 | 方法 | 说明 |
|------|------|------|
| `/web/**` | GET / HEAD | 前端静态资源（白名单扩展名） |
| `/api/v1/auth/login` | POST | 登录入口 |
| `/api/v1/ping` | GET / HEAD | 进程存活探针（**不含任何业务数据**） |

> **其余全部请求**（含所有业务查询）缺失/失效会话 → `10006`。
> 分发顺序为 **先认证再路由**：未认证者访问任何 `/api/v1/**` 一律 `10006`，
> **不区分路径是否存在**（避免端点枚举）；`404/405` 仅对已认证请求可见。

### 1.3 业务错误码（冻结，`ErrorCode`，共 23 项 + `0`）

| 码 | HTTP | 常量 | 阻断 |
|----|------|------|------|
| 0 | 200 | `OK` | — |
| 10001 | 400 | `PARAM_INVALID` | — |
| 10002 | 503 | `COMPONENT_UNAVAILABLE` | — |
| 10003 | 403 | `FUNCTION_PERMISSION_DENIED` | — |
| 10004 | 403 | `SUB_ACCOUNT_PERMISSION_DENIED` | — |
| 10005 | 403 | `POSITION_SEPARATION_CONFLICT` | — |
| 10006 | 401 | `NOT_AUTHENTICATED` | — |
| 10007 | 401 | `AUTH_FAILED` | — |
| 10008 | 423 | `ACCOUNT_LOCKED` | — |
| 10009 | 409 | `STATE_NOT_ALLOWED` | — |
| 10010 | 409 | `UNIQUE_CONFLICT` | — |
| 10011 | 409 | `REFERENCE_CONFLICT` | — |
| **10012** | 412 | `COMPLIANCE_GATE_FAILED` | ✅ 四项必填 |
| **10013** | 412 | `AGGREGATION_MISMATCH` | ✅ 四项必填 |
| **10014** | 412 | `DISPATCH_NOT_EFFECTIVE` | ✅ 四项必填 |
| 10015 | 409 | `DATA_INSUFFICIENT` | — |
| 10016 | 409 | `PERIOD_CLOSED` | — |
| 10017 | 403 | `AUDIT_LOG_WRITE_FAILED` | — |
| 10018 | 412 | `AUDIT_CHAIN_TAMPERED` | — |
| 10019 | 422 | `IMPORT_VALIDATION_FAILED` | — |
| 10020 | 409 | `SENSITIVE_FIELD_REJECTED` | — |
| 10021 | 409 | `SECURITY_CONFIG_REJECTED` | — |
| 10022 | 500 | `INTERNAL_ERROR` | — |

> **10019 待补**：`ARCHITECTURE.md` §4.3.2 的组件码在 Java 侧包装为业务码时，
> `0x1019`（关联对象子账户不一致）**尚无对应业务码**（§4.3.1 仅到 `10022`）。
> 已在 `T-004` 工单登记为待澄清 `O-3`，建议方案：复用 `10012`（合规门禁）或新增 `10023`。
> **`#java2` 在收到技术负责人结论前，请按 `10012` 处理并保留 `ruleId=0x1019` 语义。**

### 1.4 路由注册约定（冻结）

```java
router.register("GET", ApiRoutes.full(ApiRoutes.PING), new PingHandler());   // 平台级
router.register("GET", ApiRoutes.full("/product/{id}"), handler);            // 模块级
```

- 占位符 `{name}` **独占整段**；段数不符即不命中；路径尾斜杠与查询串自动归一化。
- 同一 `(方法, 路径)` **重复注册 = 启动期失败**（禁止静默覆盖）。
- 处理器签名：`HttpResult handle(RequestContext ctx)`，**不得**抛受检异常；
  业务失败改抛 `BizException`，由 `RequestDispatcher` 统一映射。

### 1.5 计划提交合规预校验字段 `precheckResult`（`FR-PLAN-10` / `R-PLAN-3`；`ARCHITECTURE.md` §6.4 字段级定义）

> **背景与裁定**：`ARCHITECTURE.md` §6.4（`v1.5.7`，`CR-002` 裁定）指出该字段「**此前未在 `java/CONTRACTS.md` 定义**」（`FR-PLAN-10` 三方不一致之根因），要求本冻结唯一写者**择一落笔**：**(i) 补定义为「服务端回填的校验快照」（只读、入参忽略）** 或 **(ii) 从请求体移除**。
> **本冻结取 (i)**：架构对同一字段**同时**要求「服务端**自行生成**校验结果并落 `t_plan_approval.precheck_result`」与三项可见性之 (a)「**提交响应体**含 `unverified`」⇒ 该字段在**响应侧必须存在**；取 (ii) 会丢掉「一个字段、单向定义」的清晰性。

| 项 | 约定 |
|----|------|
| 字段名 | `precheckResult` |
| 出现位置 | `POST /api/v1/plan/{id}/submit`（`FR-PLAN-10`） |
| **方向** | **响应（服务端生成，只读）／ 请求（服务端忽略）** |
| **请求侧** | 服务端**忽略**其内容（**不作为通过依据**）；**不因其缺失/为空而拒单**（否则客户端字串即门禁，可绕过） |
| **响应侧** | 由**服务端自行重算后回填**的校验快照（成功信封 `data.precheckResult`，`unverified` 亦随成功信封返回，供三项可见性之 (a) 判定） |
| **结构** | 见 `ARCHITECTURE.md` §6.4：`{"items":[{"rule":"R-PLAN-3/①"…"/⑦","current":…,"threshold":…,"verdict":"PASS\|FAIL","source":"<表/服务>"},…],"unverified":["R-PLAN-3/③"],"generated_at":…,"generated_by":…}` |
| **`unverified`（M1 过渡）** | **M1 期间恒含 `"R-PLAN-3/③"`**（`③ 规模红线` 归 M3：其分母 ∈ `t_annual_scale`（`TB-RISK-11`，M1 不存在）⇒ 客观不可算） |
| **M1 服务端强制项** | **①②④⑤⑥⑦ 六项**由 `PlanService.submitForApproval` **服务端自行重算并阻断**；任一不通过 ⇒ **阻断提交**，业务码 ∈ `10009`/`10012`/`10005`/`10004`/`10003`，输出**四项载荷** `ruleId`/`current`/`threshold`/`nextStep`（`ruleId` 取 `R-PLAN-3/①`…`/⑦`） |
| **判定顺序（短路式）** | `① status=DRAFT`（`10009`）→ `② 书面文件三要素`（`10012`）→ `③ 岗位分离`（`10005`）→ `④ 操作授权`（`10004`/`10003`）→ `⑤ 白名单`（`10003`）→ `⑥ 期限红线`（`10012`）→ `⑦ 数量 ≤ 未覆盖量`（`10012`）。**`③ 规模红线` 不在序内**。 **📌 位号澄清（编排者 2026-09-21 裁定）**：本节「判定顺序」中的位号为**执行次序位号**，与实际报出的 **`ruleId` 编号不是同一套**；**`ruleId` 的唯一语义基准 = `REQUIREMENTS/PLAN.md` 的校验项编号**（① 操作授权 / ② 白名单 / ③ **规模红线** / ④ 期限 / ⑤ 数量 / ⑥ 书面文件 / ⑦ 岗位分离）。**不得按执行位号取 `ruleId`**。（映射：执行第 2 步书面文件报 `R-PLAN-3/⑥`、第 3 步岗位分离报 `/⑦`、第 4 步授权报 `/①`、第 5 步白名单报 `/②`、第 6 步期限报 `/④`、第 7 步数量报 `/⑤`；`status=DRAFT` 为前置、不进编号。） |
| **③ 不拒单但须可见** | M1 **不得**因 ③ 单项拒单（否则 M1 无任何计划可提交）；但须满足**三项可见性**：(a) 提交响应含 `unverified`；(b) 落库 `t_plan_approval.precheck_result.unverified`；(c) 审批接口响应与审批页面展示该标记 —— **任一缺失即缺陷** |
| **③ 权威拦截** | 在**下单路径**（C++ 事前风控 `0x1007`/`0x1008`，`R-RISK-4`）；**不得**由本流程 REST 端点返回组件码（本流程只返业务码 `10003`/`10004`/`10005`/`10012`） |
| **落点** | `PlanService.submitForApproval`（**服务端自行重算**；**禁止**以客户端字串作为通过依据）。**实现侧归 `T-016`（`#java2`）；本节只补字段级定义** |

---

## 2. 包结构与公共层接口（冻结）

### 2.1 工程与包树

```
java/
  pom.xml                                  groupId=com.hedgeguard  artifactId=hg-biz  JDK 11
  src/main/java/com/hedgeguard/
    web/            J1 接口层     （HTTP / REST / 静态托管 / 认证过滤 / 错误映射）
      handler/                    各模块 Handler（每模块一个包）
    service/        J2 应用服务层 （11 模块；事务边界；不得出现 SQL）
    dao/            J3 数据访问层 （唯一 SQL 位置）
      impl/                      Dm<Entity>Dao 实现
      tx/                        TxTemplate / TxCallback
      pool/                      DataSourcePool / ConnectionFactory / JdbcConnectionFactory / PoolConfig
      dialect/                   Dialect / DmDialect（DM 特性唯一集中点）
      entity/                    共享表簇实体（20 张 CONFIG+AUTH）
    bridge/         J4 组件桥接层 （TradingBridge + HGCP DTO）
      stub/                      InMemoryTradingBridge（测试桩）
    risk_intraday/  J5 事中风控   （镜像状态指标；不得出现 SQL）
    job/            J6 调度       （不得绕过 L3 直调 DAO；时钟可注入）
    common/         公共工具      （Config / Clocks / SensitiveLog）
```

**依赖方向（不得反向）**：`web → service → dao`，`web → service → bridge`；`dao` 与 `bridge` **互不依赖**。

### 2.2 冻结的公共接口（签名）

```java
// —— J4 桥接层：只此七项，不得出现 preCheck（§3.0.3）——
public interface TradingBridge {
    CompletableFuture<PlaceOrderResult> placeOrder(PlaceOrderCmd cmd);
    CompletableFuture<AckResult>        confirmOrder(long orderId, String operator);
    CompletableFuture<AckResult>        cancelOrder(long orderId, String reason);
    CompletableFuture<AckResult>        dispatch(ParamChange change);
    boolean                             isReady();
    ReplayProgress                      replayProgress();
    MirrorSnapshot                      mirror();
}

// —— J3 DAO：L3 只见接口，不见 JDBC 类型 ——
public interface BaseDao<T extends AuditedEntity, ID> {
    int insert(T entity, long operatorId);
    int updateById(T entity, long operatorId);      // 乐观锁；返回 0 = 版本冲突
    T findById(ID id);
    PageResult<T> findPage(PageQuery query, String where, List<Object> whereArgs);
    long count(String where, List<Object> whereArgs);
}
public interface DeletableDao<T, ID> extends BaseDao<T, ID> {   // 仅 §0.3 例外表
    int deleteById(ID id);
}

// —— 事务边界（在 L3 开启；DAO 方法自动加入）——
public final class TxTemplate {
    public <T> T inTransaction(TxCallback<T> callback);   // 提交/回滚；嵌套则加入外层
    public <T> T execute(TxCallback<T> callback);         // 自动提交；事务中则加入
    public boolean inTransaction();
}
@FunctionalInterface public interface TxCallback<T> { T apply(Connection connection) throws SQLException; }

// —— 连接池（自实现，不引 HikariCP）——
public final class DataSourcePool implements AutoCloseable {
    public DataSourcePool(ConnectionFactory factory, PoolConfig config);
    public Connection borrow();                            // 超时抛 PoolExhaustedException
    public void release(Connection connection);            // DAO 不得调用 Connection.close()
    public PoolStats stats();
}

// —— 方言唯一集中点（DM 特性均带 // DM-SPECIFIC:）——
public interface Dialect {
    String paginate(String sql, PageQuery query);          // " ... LIMIT ? OFFSET ?"
    String countSql(String sql);
    String currentTimestamp(); String sysDate();
    String toDate(String placeholder, String pattern); String truncDay(String expr);
    String addMonths(String expr, int months); String monthsBetween(String from, String to);
    String concat(List<String> parts);
    String identityInsertOn(String table); String identityInsertOff(String table);
    String name();
}

// —— 通用审计字段与编号（§0.3 / §3.5）——
public final class AuditFields {
    public static final long SYSTEM_USER_ID = 0L;
    public static void applyInsert(AuditedEntity e, long operatorId);  // created_* / rowVersion=1
    public static void applyUpdate(AuditedEntity e, long operatorId);  // updated_*；需基准版本
    public static void advanceVersion(AuditedEntity e);
}
public final class BizNoGenerator {
    hedgePlan(LocalDate,long) tradePlan(LocalDate,long) orderRef(LocalDate,long)
    settleCycle(LocalDate,String) fundVoucher(LocalDate,long) acctVoucher(YearMonth,long)
    transferApply(LocalDate,long) hedgeDoc(String,int) exposure(LocalDate,int,long,String,long)
    batch(String,LocalDate,long)
    // 冲突重试（§3.5：最多 3 次，仅 23xxx 触发）
    public static boolean retryOnUniqueConflict(int maxAttempts, Attempt attempt);
}

// —— 子账户数据权限（NFR-SEC-7：DAO 层统一注入）——
public final class SubAccountScope {
    public static final int ALL_ACCOUNT_SENTINEL = 0;
    public static SubAccountScope of(String column, List<Integer> allowed);
    public String sql();                 // 空集合 → "col IN (-1)" 恒假（绝不退化为不过滤）
    public List<Integer> params();
    public String appendTo(String whereClause);
}
```

#### 2.2.1 模块 DAO 的「§8.2 子账户隔离」契约面（**增量登记**；`HGC-1.0` 起逐条追加，`#java` 唯一写者）

> **登记依据**：`ARCHITECTURE.md` §8.2「`NFR-SEC-7` 服务端强制点」② 的 DAO 消费侧——
> 「取数（服务层 `AuthService.visibleSubAccountIds`）→ `SubAccountScope.of` → **DAO 层统一注入**」。
> 各模块 DAO 的 scoped 方法属**向后兼容的新增**（`§5.3`：可直接追加），故逐条登记于本子节。
> 命名与实现约定同 `§2.3`（接口在 `com.hedgeguard.dao`，实现在 `dao.impl`，`extends AbstractDmDao`）。

```java
// —— TB-PLAN-1 t_hedge_plan（FR-PLAN-4 验收 4 列表 / FR-PLAN-5 详情 / NFR-SEC-7）——
public interface HedgePlanDao extends DeletableDao<HedgePlan, Long> {
    HedgePlan findByPlanNo(String planNo);
    // T-100 追加：列表分页 + scope（复用 AbstractDmDao.findPageScoped）
    PageResult<HedgePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                         String extraWhere, List<Object> extraArgs);
    // T-100 追加：详情按主键 + scope（不可见 ≡ 不存在，同一返回）
    HedgePlan findByIdScoped(long id, SubAccountScope scope);
    // T-137 追加：按编号前缀取当日字典序最大编号（§3.5「当日最大序号 + 1」取数；专用 SQL，走 uk_hedge_plan_no 前缀区间）
    String findMaxPlanNoByPrefix(String planNoPrefix);
}

// —— TB-PLAN-6 t_trade_plan（FR-PLAN-15 验收 4 / FR-PLAN-14 验收 4 / NFR-SEC-7 / R-PLAN-7）——
public interface TradePlanDao extends BaseDao<TradePlan, Long> {
    List<TradePlan> findByHedgePlan(long hedgePlanId);                       // 不加过滤（跨账户只读路径）
    List<TradePlan> findByHedgePlanScoped(long hedgePlanId, SubAccountScope scope);   // T-101 追加
    // T-103 追加：分页 + scope；extraWhere/extraArgs 由服务层以占位符拼装，DAO 只合并与计数
    PageResult<TradePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                         String extraWhere, List<Object> extraArgs);
    TradePlan findByTradePlanNo(String tradePlanNo);
    // T-141 追加：按编号前缀取当日字典序最大编号（§3.5「当日最大序号 + 1」取数；专用 SQL，走 uk_trade_plan_no 前缀区间）
    String findMaxTradePlanNoByPrefix(String tradePlanNoPrefix);
}

// —— TB-PRODUCT-1 t_product（FR-PRODUCT-5 列表 / FR-PRODUCT-8 导出 / NFR-SEC-7）——
// ⚠️ 载体差异：t_product 无 sub_account_id 列 ⇒ scope 落在 t_sub_product_whitelist 子查询上
//    （id IN (SELECT product_id FROM t_sub_product_whitelist WHERE <scope.sql()>)，见下方说明）
public interface ProductDao extends BaseDao<Product, Long> {
    List<Product> findAllOrderByCode();
    Product findByCode(String productCode);
    // T-105 追加：列表分页 + scope（COUNT 与 SELECT 两条 SQL 均带 scope）
    PageResult<Product> findPageScoped(PageQuery query, SubAccountScope scope,
                                       String extraWhere, List<Object> extraArgs);
    // T-105 追加：导出面（可见范围内全部，created_time 倒序，与 GET /product 缺省对齐）
    List<Product> findAllScoped(SubAccountScope scope);
    // T-105 追加：导出面跨账户只读路径（哨兵 0 ⇒ 不加过滤，created_time 倒序）
    List<Product> findAllOrderByCreatedDesc();
    Product findByIdScoped(long id, SubAccountScope scope);   // T-106 追加：详情按主键 + scope（不可见 ≡ 不存在，同一返回；载体同 findPageScoped 走白名单子查询）
}

// —— TB-SPOT-8 t_exposure（FR-SPOT-15 列表分页 / 详情 / NFR-SEC-7）——
// ⚠️ 载体：t_exposure 有 sub_account_id 列（敞口类红线，INDEX.md §2.1）⇒ scope 直接落在本表列
//    （sub_account_id IN (...)，同 HedgePlanDao / TradePlanDao，无需子查询）
public interface ExposureDao extends BaseDao<Exposure, Long> {
    // T-107 追加：列表分页 + scope（COUNT 与 SELECT 两条 SQL 均带 scope）
    PageResult<Exposure> findPageScoped(PageQuery query, SubAccountScope scope,
                                        String extraWhere, List<Object> extraArgs);
    // T-107 追加：详情按主键 + scope（不可见 ≡ 不存在，同一返回）
    Exposure findByIdScoped(long id, SubAccountScope scope);
    // T-107 删除：findPendingHedge(int)（调用方按 subAccountId 决定可见集 ⇒ 越权面；无其它调用方）
}
```

- **`HedgePlanDao`**：`findPageScoped` / `findByIdScoped`（**`T-100` 交付，纯增量**；实现 `dao/impl/DmHedgePlanDao`，复用 `AbstractDmDao.findPageScoped`）——列表与详情面同接同一过滤；`findByIdScoped` 语义：**不可见 ≡ 不存在**（同一返回，不泄露存在性）。另加 **`findMaxPlanNoByPrefix`（`T-137` 交付，纯增量）**：`DATA_MODEL.md` §3.5「当日最大序号 + 1」取数（`t_hedge_plan` 无业务日列，业务日隐含于 `plan_no` 前缀；通用 WHERE 原子表达不了前缀匹配 ⇒ 专用 SQL `plan_no LIKE ?`，走 `uk_hedge_plan_no` 唯一索引区间）；**不使用 `SELECT MAX(...) FOR UPDATE`**（§3.5 明令禁止）。
- **`TradePlanDao`**：`findByHedgePlanScoped`（**`T-101` 交付**）/ `findPageScoped`（**`T-103` 交付**）——**两处纯增量、非破坏性**，无需 `CH-xxx`。另加 **`findMaxTradePlanNoByPrefix`（`T-141` 交付，纯增量）**：`DATA_MODEL.md` §3.5「当日最大序号 + 1」取数（`t_trade_plan` 无业务日列，业务日隐含于 `trade_plan_no` 前缀；通用 WHERE 原子表达不了前缀匹配 ⇒ 专用 SQL `trade_plan_no LIKE ?`，走 `uk_trade_plan_no` 唯一索引区间）；**不使用 `SELECT MAX(...) FOR UPDATE`**（§3.5 明令禁止）。
- **`ProductDao`**：`findPageScoped` / `findAllScoped` / `findAllOrderByCreatedDesc`（**`T-105` 交付**）/ `findByIdScoped`（**`T-106` 追加**）—— **共四处纯增量**；实现 `dao/impl/DmProductDao`——**载体差异**：`t_product` 无 `sub_account_id` 列，故 scope 的 `sub_account_id` 谓词落在 **`t_sub_product_whitelist` 子查询**上（`id IN (SELECT product_id FROM t_sub_product_whitelist WHERE <scope.sql()>)`），即"可见品种 = 可见子账户白名单覆盖品种之并集"；`findAllOrderByCreatedDesc` 为**不加过滤**（跨账户只读 / 哨兵 0）路径，服务层据哨兵择一调用。
- **`ExposureDao`**：`findPageScoped` / `findByIdScoped`（**`T-107` 交付，两处纯增量**；实现 `dao/impl/DmExposureDao`，`findPageScoped` 复用 `AbstractDmDao.findPageScoped`）——`t_exposure` 有 `sub_account_id` 列 ⇒ scope 谓词**直接落在本表列**（`sub_account_id IN (...)`，同 `HedgePlanDao`/`TradePlanDao`）；详情面 `findByIdScoped` 语义：**不可见 ≡ 不存在**（`WHERE id = ? AND <scope.sql()>`，同一返回，不泄露存在性）。**同批删除**旧 `findPendingHedge(int)`（可见集由调用方 `subAccountId` 决定 ⇒ 越权面，`NFR-SEC-7`；唯一调用方 `SpotService` 同批改，无其它引用 ⇒ 非破坏性）。
- 语义（各 scoped 方法一致，冻结；`ProductDao` 的子查询形态为经登记的载体差异）：由服务层构造 `SubAccountScope` 后传入；本层**不解析权限语义**，只把
  `scope.appendTo(...)` 的条件与既有条件合并（`ProductDao` 为包一层白名单子查询）；**空集合 ⇒ 恒假条件**（返回空），**绝不**退化为不过滤；
  **计数口径**（`findPageScoped`）：`total` 为**叠加 scope 与 `extraWhere` 过滤后**的总行数（分页实现独立执行 `COUNT`），**不得**取全表行数。
- ⚠️ **不得**以 `DmUserSubAccountPermDao.scopeOf` 作为取数入口（其不读 `perm_type`；**已由 `T-099` 处置：`scopeOf` 已删除**；正确入口 = `AuthService.visibleSubAccountIds`，见 `ARCHITECTURE.md` §8.2②）。〔勘误动因：本行原指引与同文件 §7 检查清单相悖（失效入口继续冒充权威）；授权链 = 编排者 2026-09-21 授权 + `T-099` 处置〕

### 2.3 DAO 命名与实现约定（冻结）

| 项 | 约定 |
|----|------|
| 接口 | `com.hedgeguard.dao.<Entity>Dao`（`#java2` 各模块的同名 DAO 仍放本包） |
| 实现 | `com.hedgeguard.dao.impl.Dm<Entity>Dao extends AbstractDmDao<E, ID>` |
| 扩展点 | 覆写 `tableName()` / `idColumn()` / `businessColumns()` / `bindBusiness()` / `readBusiness()` / `newEntity()` / `idOf()` / `applyGeneratedId()` |
| `readBusiness` | **还须读取主键**并写入实体自身 `id` 字段（主键类型随表而异） |
| 实体约定 | `public` 字段、**不写 getter/setter**（本项目无反射式 ORM；111 张表的样板方法会掩盖业务约束）；审计字段经 `extends AuditedEntity` 复用 |
| 只增不改表 | 覆写 `updateById` **显式抛 `UnsupportedOperationException`**；不提供删除方法 |
| 禁止 | 调用 `Connection.close()`；拼接取值（一律占位符）；在 `dao` 之外写 SQL |

---

## 3. 共享 DTO 清单（冻结，`HGC-1.1`；自 `HGC-1.0` 起未变）

### 3.1 `com.hedgeguard.web`（接口层）

| 类型 | 用途 | 关键成员 |
|------|------|---------|
| `ApiResponse<T>` | 成功/失败信封 | `code` `message` `data` `traceId` |
| `ErrorInfo` | 错误响应体 | 上四项 + `ruleId` `current` `threshold` `nextStep` |
| `ErrorCode` | 业务错误码枚举 | 见 §1.3 |
| `BizException` | 业务异常 | `of(...)` / `blocking(...)`（阻断四项必填） |
| `PageData<T>` | 分页 data 体 | `items` `total` `page` `size` |
| `HttpResult` | 处理结果（内部） | `status` `contentType` `body` `headers` |
| `RequestContext` | 请求上下文（**不含 I/O 类型**） | `method/path/queryParam/header/pathParam/body/traceId/clientIp/session/idempotencyKey/currentUserId` |
| `RouteHandler` | 处理函数 | `HttpResult handle(RequestContext)` |
| `Router` / `RouteMatch` | 路由 | `register/match/pathExists/routes` |
| `Session` / `SessionStore` | 会话 | `sessionId/userId/username/permissionVersion/expireTime` |
| `AuthFilter` / `SessionAuthFilter` | 认证过滤 | `isPublic/authenticate` |
| `ApiRoutes` | **REST 资源清单（逐字取自 §4.1.1）** | `byModule()` / `full(path)` / `PING` |
| `StaticHandler` | 静态托管 | `ALLOWED_EXTENSIONS`（7 项）/ `URL_PREFIX=/web` |

### 3.2 `com.hedgeguard.bridge`（桥接层）

`PlaceOrderCmd`、`PlaceOrderResult`、`AckResult`、`ParamChange`（含 `Type` 枚举：`CMD_UPDATE_WHITELIST` / `CMD_UPDATE_QUOTA` / `CMD_UPDATE_RISK_THRESHOLD` / `CMD_UPDATE_EXPOSURE` / `CMD_UPDATE_POSITION_LIMIT` / `CMD_UPDATE_PERMISSION` / `CMD_SET_TRADING_SUSPEND` / `CMD_SET_READY_STATE` / `CMD_LOAD_RISK_PARAMS` / `CMD_TRIGGER_REPLAY` / `CMD_RELOAD`）、`ReplayProgress`（含 `State`）、`MirrorSnapshot`、`PositionView`、`FundView`、`stub.InMemoryTradingBridge`。

> **字段集说明**：HGCP **字段级**定义在 M2 门禁冻结（`ARCHITECTURE.md` §4.2）。
> 本清单冻结的是**类型名 + 方法签名 + 当前字段集**；M2 协议冻结时若需逐字段对齐，
> 属**向后兼容的字段增补**（不破坏 `#java2` 已实现代码）；**删除或改类型**须 `CH-xxx`。

#### 3.2.1 `ParamChange.payload` 键名冻结（字段级）—— `HGC-1.1` 增量（**HGC-1.1 历史版，已被取代**）

> 🔴 **本节已被下方 §3.2.1（`HGC-1.2` / `HGC-1.6`，自 `:322` 起）取代——字段名与配对语义以该节为准。**
> **历史留痕，勿按本节实现。** 差异：本节的 `scope` → 现行 **`scan_scope`**；本节的 `suspend` → 现行 **`suspended`**；
> 本节的 `suspend_id`（"必填配对撤销"）→ **现行 `HGC-1.6` 明令禁止入 payload**（`§4.2.3` 协议表无此字段）；
> 其"撤下本次挂起"的语义改由 `ParamDispatchServiceImpl` 的**未撤销挂起计数** + `sub_account_id` 精确定位承载。
> **仅** `sub_account_id` / `limit_layer` / `product_id` / `instrument_id` / `quota_limit` / `effective_from` / `entries` 等键名沿用。

`ParamChange` 以 `payload: Map<String,String>` 承载载荷（不硬编码协议字段名）。为使 `#java2` 与 `#java` 可**并行实现**，
现冻结下列两个指令的**键名与语义**（其余指令的键名随其模块任务按同一风格追加，追加属兼容变更）：

| 指令 `type` | `payload` 键 | 取值 | 必填 | 语义 |
|---|---|---|---|---|
| （**所有**指令） | `param_version` | 十进制字符串 | ✅ | 单调递增；C++ 对陈旧版本回 `0x0403` |
| （**所有**指令） | `operator_id` | 十进制字符串 | ✅ | 留痕（对应 `t_param_dispatch.operator_id`） |
| `CMD_SET_TRADING_SUSPEND` | `sub_account_id` | 十进制字符串；`>0` = 该子账户，`0` = 全账户（哨兵，`§4.3`） | ✅ | 作用域 |
| | `scope` | **`OPEN_ONLY`** | ✅ | **只限开仓；平仓放行**（`R-RISK-5`） |
| | `suspend` | `true` / `false` | ✅ | `true` = 挂起；`false` = **撤下** |
| | `suspend_id` | 应用层生成的唯一标识 | ✅ | **配对撤销**：`suspend=false` 时**必须回传同一 `suspend_id`**；**禁止**不携带 `suspend_id` 的"全局解冻"（否则会误放行他人挂起） —— **（已废止，见现行 §3.2.1 / `HGC-1.6`）** |
| | `reason` / `until` | 文本 / ISO-8601 | ✗ | 审计与到期 |
| `CMD_UPDATE_QUOTA` | `limit_layer` | `SUB_PRODUCT` / `SUB_INSTRUMENT` / `ACCOUNT_PRODUCT` | ✅ | 三层限额（`CFG-16`） |
| | `sub_account_id` | 十进制字符串 | ✅ | 层①/② `>0`；**层③ = `0`（哨兵值）** |
| | `product_id` | 十进制字符串 | ✅ | |
| | `instrument_id` | 合约代码 | 层②必填 | |
| | `quota_limit` | 字符串化十进制 | ✅ | |
| | `effective_from` | `yyyy-MM-dd` | ✅ | |
| | `entries` | JSON 数组 | ✗ | 三层同发时携带全部命中项（层内并列规则见 `CFG-16`） |

**限额下调两阶段（`ARCHITECTURE.md` §9 `RSK-04`）的配对语义（本节的旧版伪码已废止）**：

> ⚠️ **本节原伪码（`suspend=` / `scope=` / `suspend_id=ID`）已作废，切勿照抄**：字段名与配对载体均已变更——
> `scope` → `scan_scope`、`suspend` → `suspended`，且 `suspend_id` **禁止**入 payload。
> **现行伪码以本节下方 §3.2.1（`HGC-1.2` / `HGC-1.6`）的「两阶段限额下调」为准。**

**下发超时（`CFG-28 hg.cmd_timeout_ms` = 3000 ms）的计时归属（冻结）**：
**由 L3 编排侧计时**（`ParamDispatchServiceImpl` 在 `CompletableFuture.get(timeout)` 上承担），
**不放在 `TradingBridge` 内** —— 理由：`TradingBridge` 是**传输缝**（`§3.10` 不含业务规则），
而"未回执 ⇒ 不得视为已生效"是**业务判据**（`R-CONFIG-3`），其错误码 `10014` 亦属业务码。
超时后**必须**：① 将 `t_param_dispatch.dispatch_status` 置 `UNCONFIRMED` 并落 `retry_count`；
② 抛**阻断类** `BizException.blocking(10014, …, ruleId="R-CONFIG-3", current=实际(无回执), threshold=期望(ACK), nextStep=…)`。

#### 3.2.1 `ParamChange.payload` 键名约定（**冻结，`HGC-1.2` 新增；`#java2` 提问触发**）

`ParamChange.payload` 是 `Map<String,String>`（刻意不固化协议字段）。**键名一律直接采用 `ARCHITECTURE.md` §4.2.3 冻结的协议字段名**，
不做二次命名（映射到 protobuf 由 `bridge` 层完成）：

| 指令（`§4.2.3`） | payload 键 | 说明 |
|---|---|---|
| **`CMD_SET_TRADING_SUSPEND`**（`0x0118`） | `sub_account_id` | **`"0"` = 全账户**；其余为具体子账户。**必填** |
| | **`scan_scope`** | 固定 `"OPEN_ONLY"`。⚠️ **以 §4.2.3 协议表为准**（正文 §3.11 写作 `scope`，**协议表写 `scan_scope`**——两者不一致时**以协议表为准**，已登记为 `D-15` 提请技术负责人统一正文） |
| | `suspended` | `"true"` / `"false"` |
| | `reason` | 挂起/解冻原因（审计用） |
| | `operator_id` | 操作人 |
| **`CMD_UPDATE_QUOTA`**（`0x0113`） | `entries[n].<字段>` | `entries[]` 为**三层限额条目**，含 `limit_layer`（`SUB_PRODUCT`/`SUB_INSTRUMENT`/`ACCOUNT_PRODUCT`）、`sub_account_id`、`product_id`、`instrument_id`、`quota_limit`、`used`、`frozen`。**串行化约定：前缀索引 `entries[0].`、`entries[1].`…**（因 payload 为扁平字符串映射） |
| 通用 | `param_version` | **不放 payload** —— 用 `ParamChange.paramVersion` 专用字段承载 |

**两阶段限额下调（`§9 RSK-04` ③ 的落地口径）**：
1. 冻结 → `CMD_SET_TRADING_SUSPEND{sub_account_id=X, scan_scope=OPEN_ONLY, suspended="true"}`
2. 替换快照 → `CMD_UPDATE_QUOTA{entries[0].limit_layer=…, entries[0].sub_account_id=X, …}`
3. 解冻 → `CMD_SET_TRADING_SUSPEND{sub_account_id=X, suspended="false"}` —— **必须带同一 `sub_account_id`**；
   **禁止**用 `sub_account_id="0"` 解冻（那才是**全账户**解冻）。

> ⚠️ **协议无 per-suspend 令牌字段**（`0x0118` 只有 `param_version`/`sub_account_id`/`scan_scope`/`suspended`/`reason`/`operator_id`），
> 故**"撤下我这次下的挂起"只能靠 `sub_account_id` 精确定位**。**设计要求（冻结）**：
> 同一子账户若可存在**多个并发挂起来源**，`ParamDispatchServiceImpl` 必须维护**该子账户的未撤销挂起计数**，
> **仅当计数归零时**才下发 `suspended="false"` —— 否则 A 的挂起会被 B 的解冻误撤（`R-RISK-5` 的"只限开仓"语义会被破坏）。

> 🔴 **`HGC-1.6` 新增硬规则：`payload` 只允许出现 `§4.2.3` 协议表内的字段名，禁止任何协议外字段。**
> 依据：`payload` 会被 `bridge` 层**逐键映射到 HGCP 帧**（`§4.2.3` 冻结表）；协议表里没有的键，
> 映射时**要么报错、要么被静默丢弃**——两者都是缺陷（后者更危险：调用方以为"带上了关联信息"，实际没上线路）。
> **典型违例（已实测发生）**：把**内部关联标识**（如 `suspend_id`）写进 payload。该标识**协议无此字段**。
> **正确做法**：
> - **内部关联标识仍可保留**（方法参数、进程内配对栈/计数、日志）——它是**Java 侧配对与追溯**的手段，与协议无关；
> - 需要**持久化追溯**时，写入 `t_param_dispatch.ack_detail`（`VARCHAR(1000)` 自由文本，如 `"配对撤销 suspend_id=S-7-3"`）
>   或应用日志；**不得**为它新增协议字段，也**不得**改动 `ParamChange` 的字段集（`§3.2` 已冻结）；
> - 确需新增**协议字段** ⇒ 必须走 `CH-xxx` 改 `§4.2.3`（`T-028` 之后由技术负责人裁定），**不得**由实现方自行加键。

> 📌 **同轮补充的一条 API 设计规则**（源自 `#java2` 的实现缺陷，`HGC-1.6` 同轮，不另升版）：
> **内部关联标识必须经 API 可见**——要么作为**入参**（调用方自持），要么作为**返回值/出参**（由生成方交给调用方）。
> **禁止**让调用方"解析协议内自由文本"（如从 `reason` 里抠 `suspend_id=…`）来获得该标识：
> 那等于把**内部标识的获取渠道**寄托在**可自由改写的审计文本**上 —— 措辞一改即红，且**API 上实际不可达**。
> **正确分工（本契约口径）**：`reason` 可**附带**该标识文本（供审计/人工排查），但**不得**是唯一的获取途径；
> 生成方应提供**带标识的主入口**（如 `suspendOpenOnly(subAccountId, suspendId, reason, operatorId)`）+
> **自动生成的便利重载**以兼容既有调用方。

> 🔴 **推论（同轮实测得出）：内部关联标识必须经 Java API 暴露，不得让调用方"回读"获取。**
> 若实现只把标识塞进报文的自由文本字段（如 `reason`）而**不通过返回值/入参暴露**，调用方就**只能解析文本**才能拿到它 —— 那是脆弱的隐式契约，且让用例被迫"断言线格式"（本轮 `ParamDispatchServiceImplTest` 的最后一处失败即由此而来）。
> **要求**：① 关联标识**由调用方生成并传入**，或**由方法返回**（二者其一，必须在方法签名上可见）；
> ② 允许在**协议内的自由文本字段**（如 `reason` / `ack_detail`）中**附带**该标识以便运维追溯，但那是**便利**，**不是**获取渠道；
> ③ **禁止**要求调用方/前端**解析报文文本**来获得标识。

#### 3.2.2 参数版本（`param_version`）分配与超时（**冻结**）

| 项 | 约定 |
|---|---|
| **分配方** | **服务层**（`ParamDispatchServiceImpl`）分配**单调递增**版本，写入 `ParamChange.paramVersion` 后再下发；**桥接层不得改动** |
| **分配算法（`HGC-1.5` 收紧）** | **唯一合法基准 = `ParamDispatchDao.maxParamVersion() + 1`**，且必须满足三条不变量：<br>① **单调递增**；② **进程内两两不同**（不得重复）；③ **「取值」与「落库」在同一临界区**内完成（单飞/显式加锁）。<br>⚠️ **`t_param_dispatch` 无 `UNIQUE(param_version)`**（§6 `D-16`）⇒ §3.5 的"`UNIQUE` 兜底 + 冲突重试"**在本表不成立**，**不得**按该口径实现；`FOR UPDATE` 仍**禁用**。<br>🚫 **明令禁止用行数代替**（如 `count(*)+1`）：版本号**允许有缺口**（回滚/重试），用行数播种会在有缺口时**回退到已用过的号段** → 新变更被 C++ 判为陈旧（`0x0403`）后**静默丢弃**。<br>✅ **两种实现均可**：**(A) 锁内 `maxParamVersion()+1` → 同临界区落库**（默认）；**(B) 进程内原子单调计数器**，但**必须以 `maxParamVersion()+1` 播种**（且播种在锁内），跨重启重新播种 |
| **落库** | 每次下发写 `t_param_dispatch`（含 `param_version`、`CMD_*` 类型、`operator_id`、回执结果） |
| **超时计时方** | **服务层**（`ParamDispatchServiceImpl`）。依据：`CFG-28 hg.cmd_timeout_ms = 3000`（命令超时）。实现用 `CompletableFuture.get(timeout)`；**桥接层不得吞掉超时**（否则服务层无法区分"C++ 无回执"与"桥接故障"，`10014` 的 `current` 就失真） |
| **超时处置（`HGC-1.5` 明确字段值）** | 置 `dispatch_status="UNCONFIRMED"`、**`ack_time = NULL`**（超时**没有回执**；判定时刻由审计字段 `updated_time` 记录）、`ack_detail = "超时 <timeout>ms 未收到回执"`、`retry_count = 本次重试数`；并抛 `BizException.blocking(10014, ruleId="R-CONFIG-3", current="UNCONFIRMED", threshold=期望 param_version, nextStep=提示重试入口+告警)`；**不得**视为已生效（`R-CONFIG-3`）。<br>⚠️ **不得**把判定时刻写进 `ack_time`：该列语义是"**收到回执的时间**"，混用会让回执时延不可测；巡检凭 `dispatch_status='UNCONFIRMED'`（`findProblems()` 已覆盖） |
| **可测性（`NFR-TEST-4`）** | `CompletableFuture.get(timeout)` 用**单调时钟**、不可注入 → 测试以**可控延迟的桩 future + 小超时**覆盖超时分支（`InMemoryTradingBridge` 需支持"永不回执/延迟回执"模式）；**若**要求"超时时长本身可注入"，须改用 `ScheduledExecutorService` + `Clocks` 实现 deadline（`#java2` 可提 `question` 决定） |

### 3.3 `com.hedgeguard.dao.entity`（共享表簇实体，20 个）

`SubAccount` `ConfigItem` `ConfigChangeLog` `DictItem` `AccountSubject` `ReportPeriodConfig`
`CtpFrontConfig` `UserAccount` `Role` `RolePermission` `UserRole` `PositionGroup`
`PositionConflict` `Authorization` `UserSubAccountPerm` `LoginSession` `LoginLog`
`PasswordHistory` `PermissionChangeLog` `UnauthorizedAttempt`

- 除 `ConfigChangeLog`（只增不改，**不含** `updated_*`/`rowVersion`）外，均 `extends AuditedEntity`
  （`createdBy/createdTime/updatedBy/updatedTime/rowVersion`）。
- 类型映射：`DECIMAL → BigDecimal`、`DATE → LocalDate`、`TIMESTAMP → LocalDateTime`、
  `CLOB → String`（读写走标准 `getCharacterStream`/`setCharacterStream`）、`CHAR(1) → String`。

---

## 4. 配置键（冻结，`hg.*` 基础设施类）

| 键 | 默认值 | 说明 | 文档出处 |
|----|--------|------|---------|
| `hg.web.bind` | `127.0.0.1` | **仅回环**；非回环 → 启动期拒绝（红线 9） | **`CFG-33` ✅（v1.5.3 登记）** |
| `hg.web.port` | `27402` | HTTP 服务端口（REST + 静态托管；与 `hg.tcp.port` / `hg.web.ws.port` **分离**） | **`CFG-33` ✅** |
| `hg.web.threads` | **`16`** | HTTP 工作线程数（**取 `T-003` 实测实现值**；编排者不填数） | **`CFG-33` ✅** |
| `hg.web.root` | `./web` | 静态资源目录 | `CFG-30` ✅ |
| `hg.auth.session_cookie` | `HGSESSION` | 会话 Cookie 名 | §4.1.1 ✅ |
| `hg.auth.session_idle_minutes` | `30` | 会话空闲超时 | `CFG-23b` ✅ |
| `hg.cmd_timeout_ms` | `3000` | 下发回执超时（**L3 编排侧计时**，见 §3.2.1） | `CFG-28` ✅ |
| `hg.config` | `./hg.properties` | 配置文件路径（**不得含敏感键**，装载即拒） | 本组件自有（非业务配置项） |
| 敏感凭据 | — | **只从环境变量读**（如 `HG_DB_PASSWORD`），永不落盘、永不入日志 | `NFR-SEC-1` ✅ |

> ✅ **`D-5`/`D-9` 已闭环（`HGC-1.3`）**：`ARCHITECTURE.md` **v1.5.3** 已补登记 **`CFG-33`**
> （`hg.web.bind` / `hg.web.port` / `hg.web.threads` = `127.0.0.1` / `27402` / **`16`**〔取 `T-003` 实测实现值〕），
> 故本组件已**把实现内的键名由 `hg.http.*` 改为 `hg.web.bind|port|threads`**（**以文档为准并修实现**）。
> ⚠️ **一处取值订正（留痕）**：该键曾一度被写成 `32`（编排者自认「越界拟值，已撤回」），
> 经 `tech-lead-15` 于 v1.5.3 同一修订轮订正为 **`16`**；**本组件实现与文档现已一致（16）**。
> **规则（编排者立）**：**登记值取实测实现值，编排者不填数**。
> 旧键 `hg.http.*` **已废弃**，不得在新代码中出现。
> 另：`CFG-32`（WS）= `hg.web.ws.port`（独立端口、仅回环）/ `hg.web.ws.heartbeat_ms` = **30000** / `hg.web.ws.max_connections` = **200**（v1.5.3 已填值）。

### 4.1 WebSocket 对外约定（`CH-006`；`T-028` 选型 + `T-040` 生产级服务端均归属 `#java`）

> **本轮状态（2026-09-20）**：`T-003` 已交付 HTTP REST + 静态托管，**未实现 `/ws/**`**（不属 `T-003` 验收范围）；
> **未新增任何依赖**（`pom.xml` 仍为 2 个 compile 依赖）。本节冻结**协议约定**（实现方式无关的部分），
> 供前端与 `#java2` 先行对接；**实现/选型见 §4.2 论证**。

#### 4.1.1 已由文档冻结（`ARCHITECTURE.md` §4.1.2 / §6.13 / §2.4.4 J2 / §11.2 `CFG-32`）

| 项 | 约定 | 出处 |
|----|------|------|
| 端点 | `/ws/quote`（行情）、`/ws/alert`（告警）；**由 Java 组件提供**，C++ 不提供 HTTP/WebSocket | §4.1.2、`CH-006` |
| 绑定 | 仅 `127.0.0.1`（与 HTTP 同一红线，非回环启动期拒绝） | `NFR-SEC-3` |
| 鉴权 | **沿用 §4.1.1 的 `HGSESSION` Cookie（HttpOnly）**；不因 WS 而放宽 | §4.1.2 |
| `/ws/quote` 消息（**服务端→客户端**） | `{"type":"quote","instrument":"...","last":"...","bid":"...","ask":"...","ts":"..."}` | §4.1.2 |
| `/ws/quote` 频率/丢帧 | CTP 行情驱动，**节流 ≤ 5 条/秒/合约**；**允许丢帧**（仅推最新值）；Java 侧为**覆盖式推送** | §4.1.2、§6.13 |
| `/ws/quote` 时效标注 | 前端须标注**数据时间**；超阈值未更新 → 标识"行情延迟/过期"；行情**不落库、不驱动任何自动决策** | §6.13 |
| `/ws/alert` 消息（**服务端→客户端**） | `{"type":"alert","level":"提示\|警告\|严重","metric":"...","value":"...","threshold":"...","subAccount":"...","ts":"..."}` | §4.1.2 |
| `/ws/alert` 丢帧 | **不允许丢**；客户端断线重连后**补拉 REST `/risk/alert?since=`** | §4.1.2 |
| 配置项 | `CFG-32`：`hg.web.ws.port` / `hg.web.ws.heartbeat_ms` / `hg.web.ws.max_connections` —— **取值待定**（选型论证通过后填） | §11.2 |
| 线程模型 | `J2` WebSocket 推送线程池；行情从 `quoteRing` 取；告警从告警表 + 内存事件 | §2.4.4 |
| 数值格式 | 与 REST 一致：**金额/数量/价格字符串化十进制**（`"14500.000000"`） | §4.1.1 |

#### 4.1.2 客户端→服务端约定（**裁定结果，2026-09-20，`HGC-1.2` 起生效**）

> ✅ **`T-028` 已按本节实现**（实现细节与关闭码映射见 **§4.3**；`W-2` 为安全必做，已含越权留痕）。
>
> 下表**原为"提案待批"**；编排者已于 2026-09-20 裁定：**`W-2`~`W-6` 采纳生效**（`W-2` 为**安全必做**，不得留待后续）；
> **`W-1` 按提案值实现并保留「待批（W-1）」标注**（订阅消息格式仍待技术负责人确认后回写 `§4.1.2`）。

| # | 待定项 | 我的提案（待技术负责人批准） | 为何必须定 |
|---|--------|---------------------------|-----------|
| `W-1` | **客户端→服务端订阅消息格式**（§4.1.2 仅称"沿用既有口径"，实未定义） | `{"type":"subscribe","channels":["quote","alert"],"instruments":["ru2601",...],"subAccounts":[1,2]}`；`{"type":"unsubscribe",...}`；服务端回 `{"type":"subscribed","channels":[...],"ts":"..."}` | 前端无法对接；`#java2` 无法实现订阅管理（`T-040`） |
| `W-2` | **数据权限与推送范围**（**安全相关缺口**） | 推送前按 `t_user_sub_account_perm` 过滤：`CROSS_READONLY` 用户仅得只读告警；**无授权子账户的行情/告警一律不下发**（安全默认 = 空集，与 `NFR-SEC-7` 的 DAO 层口径一致） | `/ws/alert` 消息含 `subAccount` 字段 → 若不过滤即**越权泄露**（`NFR-SEC-7`、`10004`） |
| `W-3` | **心跳机制**（`CFG-32` 的 `heartbeat_ms` 待定） | 用 **RFC 6455 原生 `ping`/`pong` 控制帧**（非业务 JSON 心跳）；间隔默认 `30000 ms`；连续 3 次无 `pong` → 关闭连接 | 实现方式相关；避免与业务消息争用 |
| `W-4` | **握手拒绝与关闭码** | 未认证/会话失效 → 握手期 HTTP `401`（不升级）；升级后会话失效 → `close(1008, "session expired")`；服务端过载 → `close(1013)` | 前端需据此决定"跳登录"还是"重连" |
| `W-5` | **首帧语义** | 连接建立后**立即下发一次当前最新值**（行情快照 + 未处置告警），避免前端空白；无数据时下发 `{"type":"quote","stale":true,...}` | 前端渲染取决于此 |
| `W-6` | **`ts` 语义与格式** | ISO-8601 本地时区（`yyyy-MM-ddTHH:mm:ss`，`Asia/Shanghai`），与 REST 一致；另可附 `tsMillis`（BIGINT）供前端计算延迟 | 前端"行情延迟/过期"判定依赖它 |

### 4.2 HTTP / WebSocket 选型论证（`ARCHITECTURE.md` §7.1.11 四要素；**已于 v1.5.3 定调 A′；`T-028` 已按 A′ 交付，实现细节见 §4.3**）

> **纪律**：§7.1.11 明定「**决定：待 M2 首周完成 WebSocket 可行性验证（`RSK-06`）后确定**」，
> 故本节给出**推荐 + 证据**，**未**写入 `pom.xml`，**未**声称已定。请技术负责人 / 编排者裁决。

**候选依赖树实测（`mvn dependency:tree`，探针 POM 位于仓库外 `/tmp/wsprobe`）**

| 候选 | Java 11 可用 | compile 传递依赖（实测） | 与「不引 SLF4J」冲突 | 维护状态 | 结论 |
|------|:---:|---|---|:---:|---|
| **A. 自实现 RFC 6455**（**形态经 §4.2.1(1) 更正为 A'：独立 `ServerSocket` 监听 `hg.web.ws.port` + 手工握手**；JDK 内置 `HttpServer` **无**协议升级能力） | ✅ | **0** | ✅ 无 | 自担 | **推荐** |
| B1. `org.java-websocket:Java-WebSocket:1.5.7` | ✅（`release 7`） | `org.slf4j:slf4j-api:2.0.6`（compile） | ❌ **冲突** | 活跃（2024-12） | **不可用**（见下） |
| B1'. 同库 `1.6.0` | ✅ | `org.slf4j:slf4j-api:2.0.13`（compile） | ❌ **冲突** | 活跃 | **不可用** |
| B2. `org.nanohttpd:nanohttpd-websocket:2.3.1` | ✅（老，Java 6/7 时代） | `org.nanohttpd:nanohttpd:2.3.1`（compile）→ **共 2 jar / 73 KB** | ✅ 无（`NanoWSD` 用 `java.util.logging`） | ❌ **2016-08-12 后无更新** | 形式合规但实质不匹配（见下） |
| C1. `org.eclipse.jetty.websocket:websocket-server:9.4.58` | ✅（`source/target 8`） | **12 个传递**（websocket-api/common/client/servlet、jetty-util/io/http/client/servlet/security/util-ajax、`javax.servlet-api`） | ✅ 无 | 9.4 系 EOL | 否决：**13 jar**，为我们用不到的 servlet 栈 |
| C2. `io.javalin:javalin:7.2.3` | ❌ **需 Java 17**（底层 Jetty 12，`<compiler.release>17</compiler.release>`） | **29 个传递**（Jetty 12 + EE10 + CDI/enterprise/inject/interceptor/transaction + ASM + `kotlin-stdlib:2.2.20`） | ❌ 冲突 | 活跃 | **否决：JDK 11 硬门槛 + 30 jar** |

**B1 `Java-WebSocket` 的否决证据（字节码级，非推测）**：解出 jar 后 `grep -rl "org/slf4j"` → **8 个 class 命中**；
`javap -c WebSocketImpl` 显示字段 `private final org.slf4j.Logger log` 在构造器中经
`LoggerFactory.getLogger(Class)` 初始化；同时 `grep -rl "java/util/logging"` → **0 命中**（**无 JUL 回退**）。
⇒ **排除 `slf4j-api` 不可行**（服务器构造即 `NoClassDefFoundError: org/slf4j/LoggerFactory`）；
引入它则必须放宽 §7.2.2「日志框架 ❌ 不引入（不用 SLF4J/Logback）」→ **须走 `CH-xxx`**。

**B2 `nanohttpd-websocket` 的实质不匹配证据**：jar 内**仅一个类 `fi.iki.elonen.NanoWSD`**（21.9 KB，
`OpCode` 含 `Continuation/Text/Binary/Close/Ping/Pong`，帧类型齐备）；但
① 元数据 `lastUpdated=20160812`（**9 年无发布**）；② 阻塞流 + `NanoHTTPD` **每连接一线程**模型，
与 `T-040`「多客户端 / 订阅管理 / 背压 / 限流」要求相悖；③ 无 subprotocol/压缩协商。

**不引入的代价（选项 A 的代价）**：自实现需覆盖握手（`Sec-WebSocket-Key`/`Accept` SHA-1）、
帧解析（掩码、分片、控制帧）、`ping/pong`、关闭握手 ≈ **300 行**，正确性风险即 `RSK-06`；
**代价可控的支撑事实**：本系统 WS 需求面很窄 —— 仅两条**服务端单向**通道、
**无客户端业务消息**（`W-1` 仅订阅/心跳）、**不需要分片/`permessage-deflate`/subprotocol**、
`/ws/alert` 的补拉走已有 REST。且这些逻辑可**完全离线单测**（与现有 99 个用例同风格，`NFR-TEST-2`）。

**新增依赖清单（当前）**：**0 条** —— `pom.xml` 维持 `protobuf-java` + `gson`（compile）+ JUnit 5（test）。
**若批准选项 A**：仍为 0 条。**若批准 B1/B2/C**：须先补 `ARCHITECTURE.md` §7.2.2 依赖行 + §11.2 `CFG-32` 取值 + `CH-xxx`（B1 另需放宽 SLF4J）。

**我与派发建议的差异（须编排者/技术负责人裁量）**：你（team-lead）在派发中写「WebSocket **更推荐轻量库**」，
而 `ARCHITECTURE.md` §9 `RSK-06` 处置为「② **优先自实现（约 300 行）；若不达标，引入轻量 Java WebSocket 库**」，
且 §7.1.11 明定「**待 `RSK-06` 验证后确定**」。本论证按**架构文本的优先序**给出结论（推荐 A），
并附"库路线事实上已近乎关闭"的证据（B1 被 SLF4J 否决 / B2 实质不匹配 / C 体积与 Java 门槛否决）。
若仍倾向引库，**唯一合规路径 = 走 `CH-xxx` 放宽 SLF4J 后引 `Java-WebSocket`**（`slf4j-api` + JUL 绑定，共 2 jar）。

#### 4.2.1 补充与一处**技术更正**（`backend-engineer#java` 同一次派发的第二个实例所补，**不推翻上表结论**）

> ⚠️ **并发写入声明（诚实留档）**：本节 §4.1/§4.2 主体由**同名实例**在 `2026-09-20 20:01:12` 落笔（`java/CONTRACTS.md`
> 由 21 672 B → 30 556 B），**未事先获通知**；我（同一次派发的另一实例）在补写时**未删除、未改写**其任何内容，
> 仅**追加**本小节与 §6 的裁定登记，以保证"同一制品同时只有一个写者"这一前提不被进一步破坏。已上报编排者。

**(1) 技术更正 A → A'（影响 `T-028` 的实现形态，**必须**修正）**
上表选项 A 写作「JDK 内置 `HttpServer` **协议升级** + 帧编解码」。**该措辞不可实现**：

```text
$ javap com.sun.net.httpserver.HttpExchange     # 全部公开方法，无 upgrade / hijack / 裸套接字访问
  getRequestHeaders getResponseHeaders getRequestURI getRequestMethod getHttpContext close
  getRequestBody getResponseBody sendResponseHeaders(int,long) getRemoteAddress getResponseCode
  getLocalAddress getProtocol getAttribute setAttribute setStreams getPrincipal
$ javap com.sun.net.httpserver.HttpServer       # 同样无 upgrade / detach 类 API
  create create(InetSocketAddress,int) bind start setExecutor getExecutor stop createContext removeContext getAddress
```
`HttpExchange` 只能经 `sendResponseHeaders()` + `getResponseBody()` 走 **HTTP 响应体（含分块编码）**，
**无法把连接降级为裸字节流**交给 WebSocket 帧读写（`getProtocol()` 只是返回请求协议串）。

⇒ **可行形态 = A'**：以**独立 `ServerSocket` 监听 `CFG-32` 的 `hg.web.ws.port`**，在该端口上手工完成 HTTP Upgrade 握手 + RFC 6455 帧收发。
**这与 `CFG-32` 已预留独立 WS 端口天然吻合，且不需要改动任何架构分层/契约** —— 即把选项 A 的措辞由
「HttpServer 协议升级」修正为「**独立监听 + 手工握手**」。**`pom.xml` 与依赖清单不受影响（仍为 0 新增）**。

**(2) B1 否决的**运行期**复现（补充证据，非静态推断）**
上表 B1 的结论由字节码静态分析（`javap -c` + `grep`）得出；我另做了**运行期实测**，二者一致：
```text
$ java -cp .:Java-WebSocket-1.6.0.jar WsProbe2      # 子类化 WebSocketServer 并实例化
FAILED: java.lang.NoClassDefFoundError / org/slf4j/LoggerFactory
root cause: java.lang.ClassNotFoundException / org.slf4j.LoggerFactory
$ java -cp .:Java-WebSocket-1.6.0.jar:slf4j-api-1.7.36.jar WsProbe2
instantiated OK        # 同时打印 SLF4J: Defaulting to no-operation (NOP) logger implementation
```
> ❌ **该「陷阱结论」已撤回**（`backend-engineer#java` 独立复核，2026-09-20 20:1x）：原判定称
> 「`mvn dependency:tree` 报'0 传递依赖' ≠ 运行期可用（`Java-WebSocket` 即'依赖树干净、运行期立即 `NoClassDefFoundError`'）」。
> **实测不成立** —— 隔离探针（仅声明该库）连跑两个版本，**树里均如实报出 `slf4j-api`**：
> ```text
> Java-WebSocket:1.6.0 → \- org.slf4j:slf4j-api:jar:2.0.13:compile
> Java-WebSocket:1.5.7 → \- org.slf4j:slf4j-api:jar:2.0.6:compile
> pom 结构：slf4j-api 在 <dependencyManagement>（行~51）**与** <dependencies>（行~283/290）**两处都有**
> ```
> ⇒ `dependency:tree` 对 `Java-WebSocket` **是有效的**（它正是靠这条才被发现"需要 slf4j"），
> 「树干净但运行期炸」的**现象不存在**；上面 (2) 的运行期复现与树的结论**一致**（都要求 slf4j），
> 故它是"字节码要求的可执行复现"，**不是**"树与运行期背离"的证据。
> **"加一次冒烟实例化"仍建议采纳**，但理由改为：防 `provided`/`optional`/反射/`ServiceLoader`
> 这类**确实不出现在依赖树里**的缺失 —— 而**不是**本例。
> **本节修正归属**：§4.2.1 由同名第二实例追加；上述撤回与证据由 `backend-engineer#java`（本席）追加。

**(3) 补充候选 C：SSE（Server-Sent Events）—— 零依赖，**但须 `CH-xxx`**
`/ws/quote` 与 `/ws/alert` 在 `§4.1.2` 中**均为服务端→客户端单向**，而 SSE **正是纯 HTTP 的单向推送**，
可直接复用**已实现并已验证**的 `com.sun.net.httpserver`，且 SSE 原生带**断线重连 + `Last-Event-ID`**
（恰好对上 `/ws/alert`"断线后重连补拉"的要求），**开发量与时延风险均低于 A'**。
**代价**：须 `CH-xxx` 改 `§4.1.2`（端点/消息格式）、`§11.2 CFG-32` 键名（`hg.web.ws.*` → `hg.web.sse.*`）与前端契约。
→ **供技术负责人权衡**：若"零依赖 + 最低开发量"优先且允许改契约 → C 优于 A'；若必须保持 `§4.1.2` 字面契约 → 选 A'。

**(4) 完整复现步骤**：见 `java/SELECTION_HTTP_WS.md`（本节的证据附录；**结论与候选表以本节为准**）。

---

### 4.3 WebSocket **实现细节冻结**（`T-028` 交付，`HGC-1.4`）

> 本节把 `§4.1`/`§4.1.2` 的对外约定落到**可实现的形态**；`T-040`（生产级：多客户端/背压/限流）在此之上扩展，**不得改动本节已冻结项**。

**包结构**：`com.hedgeguard.web.ws`（**零新增依赖**；不含任何第三方 jar）

| 类 | 职责 |
|---|---|
| `WsServer` | 独立 `ServerSocket` 监听 `hg.web.ws.port`（**仅回环**）；接受循环、`max_connections` 准入、握手+鉴权、读循环、发布 API（`publishQuote`/`publishAlert`）、`Stats` |
| `WsHandshake` | 手工 Upgrade：请求解析（→ `RequestContext`，复用 `AuthFilter`）、`Sec-WebSocket-Accept`（SHA-1+Base64）、101/400/401/404 响应 |
| `WsFrameCodec` | RFC 6455 帧编解码（发送不掩码 / 接收强制掩码）、长度三档、控制帧规则、UTF-8 校验、载荷上限 1 MiB |
| `WsConnection` | 每连接写线程 + 心跳线程；**有界出站队列**与两通道丢帧策略；`close()` 幂等 |
| `WsSubscription` | 通道 / 合约白名单 / **已授权子账户集**（空集 ⇒ 恒不下发） |
| `WsAuthorizer` | **`W-2` 裁决**：申请集必须与 `t_user_sub_account_perm` 求交；越权 ⇒ `10004` + 留痕 + `close(1008)`。含 `PermDaoGrantReader`（按 `effective_from/to` 过滤） |
| `WsMessages` | **线格式唯一构造点**（quote / quoteStale / alert / subscribed / error），数值字符串化十进制 + `ts`/`tsMillis` |

**对外行为（冻结）**

| 项 | 约定 |
|---|---|
| 端点即通道 | `/ws/quote` 只能订阅 `quote`、`/ws/alert` 只能订阅 `alert`；跨端点请求通道 ⇒ 回 `10004` 错误帧（**不关闭**） |
| 首帧顺序 | 升级成功后依次下发：`subscribed`（含**已授权交集**）→ **首帧快照**（`W-5`，快照源未装配则跳过） |
| `W-1` 消息 | 收：`{"type":"subscribe","channels":[..],"instruments":[..],"subAccounts":[..]}`；`{"type":"unsubscribe","channels":[..]}`。发：`{"type":"subscribed","channels":[..],"subAccounts":[..],"ts","tsMillis"}`；错误：`{"type":"error","code":<业务码>,"message":...}` |
| `subAccounts` 省略 | 语义 = **该用户全部已授权子账户**（仍严格 ⊆ 授权集）；**空集**（无授权）⇒ 一条都不下发 |
| 行情节流 | `publishQuote` **覆盖式**（同合约只保最新值），由 200 ms 节拍统一 flush ⇒ **≤5 条/秒/合约**（§4.1.2） |
| 行情丢帧 | 出站队列满 ⇒ 丢**最旧**并计数（`Stats.droppedQuote`） |
| **告警不丢** | 出站队列满 ⇒ **不静默丢**，`close(1013)` 断开该连接，由前端重连后经 REST `/risk/alert?since=` **补拉**（§4.1.2「不允许丢」的可执行解释） |
| 连接数上限 | 达 `hg.web.ws.max_connections` ⇒ **握手前**回 `HTTP 503` + `Retry-After: 5`（不挤占既有连接） |
| 心跳 | RFC 6455 原生 `ping`，间隔 `hg.web.ws.heartbeat_ms`（30 s）；**连续 3 次无 `pong`** ⇒ `close(1001)` |
| 握手期失败 | 无/失效会话 ⇒ **HTTP 401**（`10006`，不升级）；非放行路径 ⇒ `404`；非法 Upgrade（缺头/版本≠13/请求扩展）⇒ `400` |

**关闭码映射（`W-4` 的细化和一处**需裁定**）**

| 场景 | 关闭码 |
|---|---|
| 帧级协议错误（未掩码 / RSV 置位 / 未知 opcode / 控制帧超长或分片 / 非终止数据帧） | `1002`（未知/分片数据帧用 `1003`） |
| 文本帧非法 UTF-8 | `1007` |
| 单帧超上限 | `1009` |
| **策略/鉴权违规**（`W-2` 越权、会话失效） | `1008` |
| 服务端过载（出站队列溢出） | `1013` |
| 心跳超时 / 服务端停机 | `1001` |
| 正常 | `1000` |

> ⚠️ **待编排者确认（`D-17`）**：派发措辞为「升级后**协议错误**用 `close(1008)`」。本实现按 RFC 6455 **细分**为
> 「帧级协议错误 = `1002`；策略/鉴权违规 = `1008`」——**语义更准且前端可据此区分「重连」与「跳登录」**。
> 若要求统一为 `1008`，属**一处常量改动**（`WsServer`/`WsFrameCodec`），请裁定。

**装配状态（2026-09-20 更新；原「装配前置（诚实声明）」已闭项）**

- ✅ **HTTP/REST + DB 装配已接线**（`D-19` 闭环）：`Bootstrap` 以 **`CFG-31 hg.db.url` 为唯一开关**
  （`Config.databaseConfigured()`）**受配置保护装配**——
  **为假**：只装配平台级能力（`SessionStore`/`SessionAuthFilter`/`StaticHandler`/`Router` +
  `GET|HEAD /api/v1/ping`），业务路由**不注册**、进程保持可用（**不因缺配置而启动失败**）；
  **为真**：装配 `JdbcConnectionFactory` → `DataSourcePool` → `TxTemplate` → 5 个 service（`Auth`/`Config`/
  `Variety`/`Spot`/`Plan`）→ `AuthRoutes`/`ConfigRoutes`/`ProductRoutes`/`SpotRoutes`/`PlanRoutes` 五个注册器。
  口令零落盘（`char[]` 交工厂后即清零）、`Bootstrap.close(Assembly)` 于停机钩子释放连接池与口令副本。
- ✅ `DmUserSubAccountPermDao` **已交付**（`AuthService` 构造所需的 9 个 DAO 现已齐备）；
  原句"`DmUserSubAccountPermDao` 尚未交付（见 `D-3`）"**已过期**。
- ⚠️ **`WsServer` 仍未接线**（本节其余部分仍有效）：直接原因是 **`CFG-32 hg.web.ws.port` 在本仓库无实现值** ——
  `ARCHITECTURE.md` §12 **v1.5.4** 已登记 **`A-2`（该键取值未落）**，由 **`T-040`** 承接
  （生产级 WS 服务端：多客户端 / 背压 / 限流）。**不得自行编造 WS 默认端口**；接线时以 `A-2` 落定值为准。
- `CFG-32` 三键的**键名已在本节冻结**：`hg.web.ws.port` / `hg.web.ws.heartbeat_ms` / `hg.web.ws.max_connections`
  （本组件不预置"未被读取的常量"，避免死代码）。

## 5. 变更纪律

1. **REST 路径**：先改 `ARCHITECTURE.md` §4.1.1 → 再改 `ApiRoutes`（`NFR-MAINT-5`）。
2. **表结构**：先改 `DATA_MODEL.md`（登记 `DC-xxx`）→ 再改 DDL → 再改实体/DAO。
3. **公共接口签名**：破坏性变更须 `CH-xxx`；新增方法属兼容变更，可直接追加并**回写本节**。
4. **新增第三方依赖**：先入 `ARCHITECTURE.md` §7.2 论证（当前 4 个 compile 依赖已冻结）；
   **准入判据（`W-9`，编排者 2026-09-20 采纳）**：① 传递依赖清单（`mvn dependency:tree`，**必要**判据）
   + ② 冒烟实例化证据（**补充**判据，防 `provided`/`optional`/反射/`ServiceLoader` 这类**不进依赖树**的缺失）。
   二者**不可互相替代**。
5. **本文件版本**：任何冻结条目变更须**升版**（`HGC-1.1`…）并说明差异。
6. **署名登记（唯一写者裁定后生效）**：本文件唯一写者 = `backend-engineer#java`；他人变更走 `question`。

### 5.1 编辑登记

| 时间 | 编辑人 | 内容摘要 | 版本 |
|------|--------|---------|------|
| 2026-09-20 19:44 | `backend-engineer#java` | 初版：REST 契约 / 包与接口签名 / 共享 DTO / 配置键 / 变更纪律 / 已知偏离 | `HGC-1.0` |
| 2026-09-20 19:50 | `backend-engineer#java` | 补 §4.1 WS 义务、§6 `D-7`~`D-9` | `HGC-1.0` |
| 2026-09-20 20:01 | `backend-engineer#java`（同名席位） | 补 §4.1 WS 对外约定（`W-1`~`W-6`）、§4.2 选型论证；冻结标识升 `HGC-1.1` | `HGC-1.1` |
| 2026-09-20 20:07 | `backend-engineer#java` | 追加 §4.2.1（A→A' 技术更正 + B1 证据）、§6 裁定登记 `D-4/5/6/13/14` | `HGC-1.1` |
| 2026-09-20 20:1x | `backend-engineer#java`（同名席位） | 撤回"`dependency:tree` 报 0 传递依赖"的错误判定（`§4.2.1(2)`） | `HGC-1.1` |
| 2026-09-20 20:29 | `backend-engineer#java` | 锚点更新至 `ARCHITECTURE.md` **v1.5.2**（`D-7` 记 3 次漂移） | `HGC-1.1` |
| 2026-09-20 20:35 | `backend-engineer#java` | 写入**唯一写者裁定**（编排者）+ `W-9` 准入判据 + 本登记表 | `HGC-1.2` |
| 2026-09-20 21:35 | `backend-engineer#java` | 锚点更新（`DATA_MODEL` → **v1.3.3** `c399c5f5…`/5245；`ARCHITECTURE` → `02e0316d…`/2586）+ 登记 **`D-18`**（`data-init.sql` 补 `t_product(id=0)` 保留行） | `HGC-1.4` |
| 2026-09-20 22:35 | `backend-engineer#java` |（`HGC-1.6` 同轮）新增 **§5.3 实现纪律**：状态变更须在全部校验通过之后（禁失败路径留副作用；两部实测同族缺陷已登记） | `HGC-1.6` |
| 2026-09-20 22:25 | `backend-engineer#java` |（`HGC-1.6` 同轮）§3.2.1 补 **API 可获得性规则**；新增 **§5.2 方法学脚注**（断言锚点与契约职责对齐） | `HGC-1.6` |
| 2026-09-20 21:58 | `backend-engineer#java` | §3.2.1 新增**硬规则**：`payload` 只允许协议表字段（禁止 `suspend_id` 等协议外键，内部关联走 `ack_detail`/日志）；`data-init.sql` 的 `t_product(id=0)` 保留行补机器校验（含"防漏列"） | `HGC-1.6` |
| 2026-09-20 21:45 | `backend-engineer#java` | 应 `#java2` 的 `question`：新增 `ParamDispatchDao.maxParamVersion()`（接口+实现成对）；§3.2.2 **取号口径收紧**（`maxParamVersion()+1`、同临界区、**禁用 `count+1`**）+ 超时 `ack_time = NULL` | `HGC-1.5` |
| 2026-09-20 21:20 | `backend-engineer#java` | `T-028` 交付：新增 **§4.3 WebSocket 实现细节冻结**（包结构 / 对外行为 / 关闭码映射 / 装配前置）；登记 `D-17`（关闭码细分待裁定） | `HGC-1.4` |
| 2026-09-20 20:40 | `backend-engineer#java` | 新增 **§3.2.1 `ParamChange.payload` 键名约定**（含 `scan_scope` 口径、`entries[n].` 串行化、两阶段挂起配对与**未撤销挂起计数**要求）与 **§3.2.2 `param_version` 分配/超时归属**（应 `#java2` 的 `question`） | `HGC-1.2` |
| 2026-09-20 23:51 | `backend-engineer#java` | **状态回填（非契约变更）**：§6 `D-19` 标记**闭环**；§4.3「装配前置（诚实声明）」改写为「**装配状态**」——HTTP/REST+DB 已接线、`DmUserSubAccountPermDao` 已交付（旧"未交付"过期）、`WsServer` 未接线原因归 **`A-2`/`T-040`**（`CFG-32 port` 无实现值）。**无签名/字段/错误码/路径变更** | **未升版**（`HGC-1.6` 不变） |
| 2026-09-20 23:52 | `backend-engineer#java` | **同名两节消歧（`HGC-1.1` 历史节加废止标注）**：旧 §3.2.1（`:284`）标题加「`HGC-1.1` 历史版，已被取代」+ 顶部红标指向 `:322` 节；其**危险伪码**（`scope=`/`suspend=`/`suspend_id=ID`）整段替换为**指向现行节的引用**并标明差异。**不删旧节（留痕）**；**无现行条目变更**（现行节 `:322` 段未动） | **未升版**（`HGC-1.6` 不变） |
| 2026-09-20 23:54 | `backend-engineer#java` | §3.2.1 **历史节**（`HGC-1.1`）表内 `suspend_id` 行**补行内废止标记**「（已废止，见现行 §3.2.1 / `HGC-1.6`）」：**行号 `:302`**，原有文字**一字未删**、**未用删除线**（纯文本可 grep）。补足"红标在 `:286–290`，而 `grep suspend_id` 者会跳过"的漏洞 | **未升版**（`HGC-1.6` 不变） |
| 2026-09-21 08:12 | `backend-engineer#java` | **新增 §2.2.1「模块 DAO 的 §8.2 子账户隔离契约面（增量登记）」**：登记该契约面共 **4 只纯增量 scoped 方法** —— `HedgePlanDao.findPageScoped` / `findByIdScoped`（**`T-100` 交付**）+ `TradePlanDao.findByHedgePlanScoped`（**`T-101`**）/ `findPageScoped`（**`T-103`**）（此前已交付但未登记入本冻结）。**依据**：`ARCHITECTURE.md` §8.2②（DAO 层统一注入）；`§5.3`「新增方法属兼容变更，可直接追加并回写本节」⇒ **未升版**（`HGC-1.6` 不变；未改动任何既有签名/字段/错误码/路径） | **未升版**（`HGC-1.6` 不变） |
| 2026-09-21 08:16 | `backend-engineer#java` | 落实 `ARCHITECTURE.md` §6.4（`v1.5.7` `CR-002`）「同步要求 ②」：**新增 §1.5「计划提交合规预校验字段 `precheckResult`（`FR-PLAN-10`/`R-PLAN-3`）」**——按架构两互斥选项取 **(i) 补定义为「服务端回填的校验快照」（只读、入参忽略）**（因架构同时要求响应体含 `unverified`，该字段响应侧必须存在）；含方向、结构（引 §6.4）、M1 六项服务端强制、短路判定顺序、③ 不拒单但三项可见性、落点。**只补一处此前缺位的字段定义，非契约变更增量 ⇒ 未升版**（`HGC-1.6` 不变；实现侧归 `T-016`，本席未动代码） | **未升版**（`HGC-1.6` 不变） |
| 2026-09-21 09:40 | `backend-engineer#java2` | 落实 `ARCHITECTURE.md` §6.4`:1472`「📌 位号澄清（编排者 2026-09-21 裁定）」：在 §1.5「判定顺序」行**追加**位号澄清 —— 该行位号 = **「执行顺序位号」**（非 `ruleId` 的「校验项编号」）；`ruleId` 的**唯一语义基准 = `REQUIREMENTS/PLAN.md` 的校验项编号**（① 操作授权 / ② 白名单 / ③ 规模红线 / ④ 期限 / ⑤ 数量 / ⑥ 书面文件 / ⑦ 岗位分离）；**不得按执行位号取 `ruleId`**。**只加不改、行内追加（行数 `+0`）**；无签名 / 字段 / 错误码 / 路径变更 ⇒ **未升版**（`HGC-1.6` 不变） | **未升版**（`HGC-1.6` 不变） |
| 2026-09-21 15:2x | `backend-engineer#java2` | **`T-105` 交付**：§2.2.1 **追加 `ProductDao` 三只纯增量 scoped 方法** —— `findPageScoped`（列表分页）/ `findAllScoped`（导出，`created_time` 倒序）/ `findAllOrderByCreatedDesc`（跨账户只读/哨兵 0 无过滤导出）。**载体差异已在 §2.2.1 显式登记**：`t_product` 无 `sub_account_id` 列 ⇒ scope 落在 `t_sub_product_whitelist` 子查询上。**依据**：`FR-PRODUCT-5`/`FR-PRODUCT-8`/`NFR-SEC-7`；`§5.3`「新增方法属兼容变更」⇒ **未升版**（`HGC-1.6` 不变；未改动任何既有签名/字段/错误码/路径） | **未升版**（`HGC-1.6` 不变） |
| 2026-09-21 19:39 | `backend-engineer#java`（实例 `be-t099b`） | **勘误：`scopeOf` 过时指引订正 2 处** —— `:312`（§2.2「不得以 `scopeOf` 取数」）改为**已完成态**（`T-099` 已删除 `scopeOf`；正确入口 = `AuthService.visibleSubAccountIds`）；`:798`（§7 `#java2` 检查清单；本行追加登记后该条现位于 `:799`）取数入口由 `userSubAccountPermDao.scopeOf(userId, "sub_account_id")` 改为 `AuthService.visibleSubAccountIds(userId)` → `SubAccountScope.of(...)`（保留"注入 `SubAccountScope`"原意）。**动因**：`:798` 与 `:312` 自相矛盾（失效入口继续冒充权威）；**授权链** = 编排者 2026-09-21 授权 + `T-099` 处置。**无签名 / 字段 / 错误码 / 路径变更** | **未升版**（`HGC-1.6` 不变） |
| 2026-09-23（`T-135` 交付） | `backend-engineer#java2`（实例 `be-t135`） | **兼容性增量登记（`T-135`；`FR-AUTH-1/5/11/15④`、`FR-CONFIG-7` 验收3）**：① **新增 DAO 方法**（`§5.3`「新增方法属兼容变更」）—— `UserSubAccountPermDao.findUserIdsBySubAccount(int)`（+ `DmUserSubAccountPermDao` 实现；反向索引，仅供用户列表筛选下推 `id IN (...)`，**非**取数入口）；② **既有已声明路径补注册方法**（**未新增路径**）—— `PUT /api/v1/config/sub-account`（路径与 CONFIG 方法集 `GET/POST/PUT` 均在 `§4.1.1` 内；`ApiRoutes` 路径清单**未变**）；③ **既有端点新增可选入参**（`§0③` 字段级兼容）—— `GET /auth/user` 增 `role`/`keyword`/`subAccount`；`GET /auth/change-log` 增 `from`/`to`/`changeTarget`/`operatorId`；`POST /auth/user` 请求体增可选 `roleCode`；`PUT /auth/user` 的 `action` 取值域增 `FORCE_LOGOUT`；④ **新增服务重载**（`AuthService.listUsers`/`listPermissionChangeLogs`/`createUser` 及 `ConfigService.findSubAccountById`），旧签名保留委托。**无路径新增 / 无字段删除改类型 / 无错误码增减** ⇒ **未升版**（`HGC-1.6` 不变）。⚠️ **该 `PUT` 使装配面路由计数 `82 → 83`** —— `BootstrapAssemblyTest:140` 断言已同步订正（+加注理由）；**`web/js/core/routes.js` 头注仍写 `82`/`CONFIG17`，须由可写 `web/**` 的实例同步**（本席写入域为 `java/**`，未改）。 | **未升版**（`HGC-1.6` 不变） |
| 2026-09-23（`T-137` 退回修复） | `backend-engineer#java2`（实例 `be-t137b`） | **兼容性增量登记（`T-137`；`§3.5` 取号口径修复 `BUG-003` 复核 `O-1`）**：§2.2.1 **追加 `HedgePlanDao.findMaxPlanNoByPrefix(String)` 一只纯增量方法**（+ `DmHedgePlanDao` 实现；专用 SQL `SELECT plan_no FROM t_hedge_plan WHERE plan_no LIKE ?`，先例 `T-105`/`T-109`/`T-110`）。取号由「全局累计计数」改为「**当日最大序号 + 1**（按业务日重置）」，取数与插入同事务，保留 §3.5「`UNIQUE` + 最多 3 次重试」（`retryOnUniqueConflict`）；**未新增路径 / 未改字段 / 未改错误码** ⇒ **未升版**（`HGC-1.6` 不变）。⚠️ 测试侧同步（非契约）：`InMemoryDmEngine` 增补 `LIKE ?` 前缀谓词求值（等价引擎能力扩展，既有 `=`/`IN`/`IS NULL` 路径不变）；9 个假 DAO 补 `findMaxPlanNoByPrefix`。 | **未升版**（`HGC-1.6` 不变） |
| 2026-09-23（`T-141` 交付） | `backend-engineer#java2`（实例 `be-t141`） | **兼容性增量登记（`T-141`；`§3.5` 取号口径修复，与 `BUG-003` 同构）**：§2.2.1 **追加 `TradePlanDao.findMaxTradePlanNoByPrefix(String)` 一只纯增量方法**（+ `DmTradePlanDao` 实现；专用 SQL `SELECT trade_plan_no FROM t_trade_plan WHERE trade_plan_no LIKE ?`，走 `uk_trade_plan_no` 前缀区间）。`decomposeTradePlans` 取号由「按 `hedge_plan_id` 计数 + 批内偏移」改为「**当日最大序号 + 1**（按业务日重置，批内逐行重查前缀 MAX）」，取数与插入同事务并接线 §3.5「`UNIQUE` 冲突 + 最多 3 次重试」（`retryOnUniqueConflict`，非 `trade_plan_no` 唯一冲突不重试）；**未新增路径 / 未改字段 / 未改错误码** ⇒ **未升版**（`HGC-1.6` 不变）。⚠️ 测试侧同步（非契约）：4 个假 DAO 补 `findMaxTradePlanNoByPrefix`。 | **未升版**（`HGC-1.6` 不变） |
| 2026-09-23（`T-136` 交付） | `backend-engineer#java2`（实例 `be-t136`） | **兼容性登记（`T-136`；`FR-AUTH-16` 处理规则 ＋ 验收 3 / `R-AUTH-6`）**：① **删除响应 DTO 字段（对消费方为破坏性变更）** —— `PUT /api/v1/auth/password` 与 `PUT /api/v1/auth/user`（`action=ENABLE`）的 `ResetView.oneTimePassword` **整类移除**，两终点成功响应由 `data:{oneTimePassword}` 改为 `data:null`（前端 `web/js/pages/auth.js` 同批原子耦合移除 `oneTimePasswordCard` 与两处喂入，用户 2026-09-23 裁定「严格按需求：后端不得回传」）。② **服务方法返回类型收紧（非冻结签名）** —— `AuthService.resetPassword(long,long)` / `enableUser(long,long,Long)`：`String` → `void`（随机明文生成后即加盐哈希落库并弃，不落盘 / 不入日志 / 不回传调用方）。**冻结面判定**：`AuthService` 与 `ResetView` **均不在 §2.2 冻结公共接口 / §3.1 共享 DTO 清单内**；`§1` REST 冻结面（信封 / 分页 / 幂等 / 路径 / 错误码）与 `payload` 键名（`§3.2.1`）**均未变** ⇒ **未升版**（`HGC-1.6` 不变），无需 `CH`（同 `T-135` 口径）。 | **未升版**（`HGC-1.6` 不变） |
| 2026-09-23（`T-136` 形态 B 退回补做） | `backend-engineer#java2`（实例 `be-t136b`） | **兼容性登记（`T-136` **形态 B**「管理员设定新口令（作为输入）」；`FR-AUTH-16` 输入列本就含「新口令」＋ `FR-AUTH-4`「启用（须重置口令）」；用户 2026-09-23 裁定采 (B)）**：① **既有端点新增请求体字段** —— `PUT /api/v1/auth/password` 请求体 `{userId}` ⇒ `{userId, newPassword}`（**`newPassword` 必填**）；`PUT /api/v1/auth/user`（`action=ENABLE`）请求体增 **`newPassword`（必填）**；两终点**成功响应仍为 `data:null`（无任何口令字段 —— 第一轮「不回传」判据保留）**。② **服务方法签名增参** —— `AuthService.resetPassword(long,long)` → `resetPassword(long,String,long)`、`enableUser(long,long,Long)` → `enableUser(long,String,long,Long)`（方法**不在 §2.2 冻结公共接口**）。③ 三条"设定口令"路径（`changePassword`/`resetPassword`/`enableUser`）**复用**同一强度策略（`PasswordHasher.assertStrength`）与"不得与最近 5 次历史口令相同"（`PasswordHistoryDao.findRecent`）。**冻结面判定（本席实测）**：`§0③` 共享 DTO 载体 = `com.hedgeguard.web` 的 **`§3.1` 明确列举类型**（`ApiResponse`/`ErrorInfo`/`ErrorCode`/`BizException`/`PageData`/`HttpResult`/`RequestContext`/`RouteHandler`/`Router`/`Session`/`AuthFilter`/`ApiRoutes`/`StaticHandler`），**不含** handler 请求 DTO（`PasswordHandler.ResetRequest`/`UserHandler.MutateRequest`）；`ARCHITECTURE.md §4.1.1` 冻结**信封 / 会话 / 分页 / 幂等 / 错误码** + 资源**路径 + 方法**（`:641` AUTH 行仅列路径，**字段级定义归 `DATA_MODEL.md`**，该表无字段级内容）；`§3.2.1` 的 `payload` 键名冻结**仅适用 WS `ParamChange`、不适用 REST 请求体** ⇒ **新字段不在本冻结面内**，且 `§0③` 载明「字段级新增可（向后兼容）」 ⇒ **未升版**（`HGC-1.6` 不变），**无需 `CH`**（与 `T-136` §2 备注 ② 预判一致）。 | **未升版**（`HGC-1.6` 不变） |
| 2026-09-23（`T-107` 交付） | `backend-engineer#java2`（实例 `be-t107`） | **兼容性增量登记（`T-107`；`FR-SPOT-15` 列表/详情读取面，`NFR-SEC-7` 越权面修复）**：① §2.2.1 **追加 `ExposureDao.findPageScoped` / `findByIdScoped` 两只纯增量 scoped 方法**（+ `DmExposureDao` 实现；`§5.3`「新增方法属兼容变更」）；载体 = `t_exposure` 有 `sub_account_id` 列（敞口类红线）⇒ scope 谓词**直接落本表列**。② **同批删除**旧 `findPendingHedge(int)`（可见集由调用方 `subAccountId` 决定 ⇒ 越权面；唯一调用方 `SpotService` 同批改，**全仓无其它引用** ⇒ 非破坏性）。③ **REST 面形态修正（非路径 / 字段 / 错误码变更）**：`GET /api/v1/spot/exposure` 由**裸数组**改为 **`PageData` 分页信封**（`§1.1` 已冻结「分页接口用 `PageData` 信封」⇒ 属**向既有冻结口径对齐**的违规修正，同 `T-103` 先例）；列表与 `GET /spot/exposure/{id}` **按会话身份强制子账户可见范围**（取数入口 `AuthService.visibleSubAccountIds` → `SubAccountScope.of` → scoped DAO），`subAccountId` 查询参数降为**收窄过滤**（不再决定可见集）；列表缺省排序**显式 `exposure_qty,desc`**（`FR-SPOT-15`「默认按敞口金额或数量倒序」）。**未新增/改路径、未改字段类型、未增减错误码** ⇒ **未升版**（`HGC-1.6` 不变），**无需 `CH`**（同 `T-103`/`T-105` 口径）。⚠️ 前端 `web/js/pages/spot.js` **同批原子耦合**（消费 `PageData`、按新语义用 `subAccountId`）。 | **未升版**（`HGC-1.6` 不变） |
| 2026-09-23（`T-146` 交付） | `backend-engineer#java2`（实例 `be-t146`） | **兼容性增量登记（`T-146`；`FR-AUTH-16` 处理规则「首次登录与重置后强制修改口令」，服务端强制点落地）**：① **§3.1 共享 DTO 字段级新增**（`§0③`「字段级新增可（向后兼容）」）—— `Session` 增 `boolean mustChangePassword`（新增 6 参构造 + 既有 5 参构造保留并委托；新增 `withMustChangePassword(boolean)` 复制方法）；`LoginHandler.LoginResponse` 增 `boolean mustChangePassword`（登录响应回传，供前端引导至改密界面；**不**承担门禁）。② **无新增 REST 路径 / 方法 / 错误码**：门禁复用既有业务码 **`10003`**（`FUNCTION_PERMISSION_DENIED`，403）——**未新增错误码**（`§1.3` 23 项不变）。③ **服务层新增方法**（`AuthService` 不在 `§2.2` 冻结接口）：`markPasswordChanged(String)`（改密成功后清除会话内标记）。④ **冻结面判定**：`§1.1` 信封 / 分页 / 幂等 / 鉴权、`§4.1.1` 路径与方法、`§1.3` 错误码集合 **均未变** ⇒ **未升版**（`HGC-1.6` 不变），**无需 `CH`**（同 `T-135`/`T-136` 口径）。⚠️ 前端同批（`web/js/**`）：`login.js`/`session.js`/`auth.js` 三处最小耦合（置位用户登录后引导至改密 tab）；`errmsg.js` 错误码映射**未改**（复用 `10003`）。 | **未升版**（`HGC-1.6` 不变） |
| 2026-09-24（`T-151` 交付） | `backend-engineer#java2`（实例 `be-t151`） | **兼容性增量登记（`T-151`；`FR-PRODUCT-6` 读取面同族残留三端点，`NFR-SEC-7`；`ARCHITECTURE.md` §8.2③「其余读取面同须接同一过滤」）**：① **无新增 DAO 方法 / 无改路径 / 无改字段 / 无改错误码** —— `GET /product/{id}/contracts`、`GET /product/change-log?productId=`、`GET /product/mapping?productId=` 三端点读取面补**子账户可见性强制**（复用 `T-106` 的 `ProductDao.findByIdScoped` 白名单子查询 + `VarietyService.getProduct(id, operatorId)`；**不可见 ≡ 不存在** ⇒ 统一错误 `10001`，不泄露存在性）。② **补登记 `T-106` 遗留**：`VarietyService.quotaPanorama(int, long)` → **`quotaPanorama(int, long, long)`**（增 `operatorId`，读取面可见性断言）—— `VarietyService` **不在** §2.2 冻结公共接口 ⇒ 属兼容变更（`§0③` / `§5.3`）。③ §2.2.1 描述性 bullet 同步为含**第 4 法**（`ProductDao.findByIdScoped`，`T-106`）。④ **★路由注册序修复（`ProductRoutes`）**：`GET /product/{id}` 由"首个"改为**最后**注册 —— `Router.match` 为**注册序首次命中**，原序使 `/product/quota`、`/product/mapping`、`/product/change-log`、`/product/export` 四条**同段数静态 GET** 被占位符抢占（生产装配下不可达）；**未改 `web/Router.java`**（框架类属 `#java` 域）。**冻结面判定**：§1 信封 / 分页 / 幂等 / 鉴权、§4.1.1 路径与方法、§1.3 错误码集合 **均未变** ⇒ **未升版**（`HGC-1.6` 不变），**无需 `CH`**（同 `T-135`/`T-136`/`T-146` 口径）。 | **未升版**（`HGC-1.6` 不变） |

---

### 5.2 方法学脚注：**断言锚点须与契约职责对齐**（`HGC-1.6` 同轮）

冻结契约不仅约束**实现**，也约束**验证**。写针对本契约的用例时：

| 断言对象 | 正确的断言方式 |
|---|---|
| **线格式**（`ParamChange.payload`、WS 帧、REST 信封） | **只**断言 `§4.2.3` 协议表 / `§4.1.1` 契约内的字段；**不得**把内部标识或实现细节写进线格式断言 |
| **内部标识 / 中间状态** | 用「**方法入参校验 + 行为断言**」覆盖：缺参 → 业务码；未归零 → **不下发**；归零 → 才下发 |
| **内部关联标识的获取** | **必须经 API 可见**（入参或返回值）；**禁止**以"解析自由文本"（`reason`/`ack_detail`）作为获取渠道（详见 §3.2.1） |
| **跨线程可见的量**（计数、队列长度、连接状态） | **有界轮询**后断言；**不得**"读完即判"（存在可见性窗口 ⇒ 偶发假红） |

> **反面成本**：违反任一条会产生"**实现正确、用例正确地失败**"的**假红** —— 排查成本却记在实现头上，
> 并可能诱导实现方为了过用例而**偏离契约**（本项目已实测两次：一次线格式锚点、一次跨线程时机）。

### 5.3 实现纪律：**状态变更必须发生在全部校验通过之后**（`HGC-1.6` 同轮）

凡"**计数/状态 + 校验**"成对出现的流程（本契约至少 3 处：`param_version` 未撤销挂起计数、WS 出站队列、
`Idempotency-Key` 幂等占位），**禁止在校验失败时留下任何副作用**：

| 反例（危险） | 正例 |
|---|---|
| 先 `decrement()` 再判断标识是否合法 ⇒ **误减计数** ⇒ 可能放开**他人**的挂起 | **先校验**（未知/重复/不属于本子账户 → `10009`）→ **校验通过才动计数** |
| 溢出/异常路径先置 `closed/已处理` 标志 ⇒ 后续正规路径**早退** ⇒ **关闭帧根本没发出** | 用**独立的幂等闸门**（如 `closedOnce`）承担"只执行一次"，与"停止收新"的状态位分离 |

> **本项目已实测两次同族缺陷**：① `ParamDispatchServiceImpl.popSuspendId` 原实现在"标识不在未撤销集合中"
> 时**只打日志就继续减计数**（后由实现方改为抛 `10009` 且计数不变）；② `WsConnection.close()` 原实现因
> 溢出路径先置 `open=false` 而**早退，关闭帧从未发出**（已修为独立的 `closedOnce` 闸门）。
> **共性**：把"**状态位**"当成"**已完成标志**"用 ⇒ 失败路径污染状态 ⇒ 静默放行/静默失败。
> ⇒ **判据**：任何"校验/异常"分支都必须**可证明无副作用**（计数不变、状态位不变、不发帧、不落库）。

## 6. 已知偏离 / 未决项（诚实登记）

| # | 项 | 状态 | 处置 |
|---|----|------|------|
| D-1 | DM8 实机建表**未执行**（环境无 DM 实例） | 环境前置 | 已声明；替代证据 = 逐列/类型/非空/索引/外键机器比对（`ConfigAuthSchemaTest`） |
| D-2 | DM JDBC 驱动 `DmJdbcDriver18.jar` 未随构建接入（未发布至 Maven Central） | 环境前置 `R-5` | 由 `-Pdm` profile + `java/lib/` 承接；`T-052` 部署清单登记实测版本 |
| D-3 | `dao/impl` 仅实现 3 张表（`t_sub_account` / `t_config_item` / `t_user`） | 范围 | 其余 17 张的**接口已冻结**，实现随各模块任务交付 |
| D-4 | `0x1019` 无对应业务码 | ✅ **已裁定（2026-09-20）**：**暂不新增**，维持错误码 **62** 不变（新增＝协议实质变更，须 `CH-xxx` + 重跑协议专项测试）；**挂账**为 M2 前（`T-030` 汇签前）由技术负责人裁定的开放项，**不阻塞 M1** | `#java2` 的 TRADE 映射**暂按 `10012` 处理并保留 `0x1019` 语义**；见 §1.3 |
| D-5 | HTTP 服务 bind/port/threads 未入 §11 配置清单 | ✅ **已闭环**（`HGC-1.3`） | `CFG-33` 已于 `ARCHITECTURE.md` **v1.5.3** 登记（`127.0.0.1`/`27402`/`32`）；本组件键名**已对齐**为 `hg.web.bind`/`hg.web.port`/`hg.web.threads`（旧 `hg.http.*` 废弃） |
| D-6 | 文档字段行未标注 `sub_account_id` 外键（`t_permission_change_log` / `t_unauthorized_attempt`） | ✅ **已裁定（2026-09-20）**：**以 `DATA_MODEL.md` §0.4 为准，保留外键**（可空列上的外键合法，NULL 视为"无引用"）；文档侧**补注**由技术负责人统一落（**不属我的写域**） | **DDL 不返工**（脚本 17 条外键维持）；见 `T-004` 工单 `O-1` |
| **D-13** | `Q-3`（分发顺序）文档未规定 | ✅ **已裁定（2026-09-20）**：**采纳"先认证再路由"**，写入 `§4.1.1`（由技术负责人落） | 本实现即为此语义（`RequestDispatcher` 先 `authenticate` 再 `route`）；**无代码改动** |
| **D-14** | `O-2`（只增不改表的 `updated_*`/`row_version`） | ✅ **已裁定（2026-09-20）**：**维持现状**（`t_config_change_log` 依表级备注排除三列；`t_auth-10/12/13` 依 §0.3 保留三列、**值恒 `NULL`/`0`**） | 统一口径：**"只增不改表的 `updated_by`/`updated_time`/`row_version` 恒为空属预期，不得判定为缺陷"**；DDL **不返工** |
| **D-15** | `CMD_SET_TRADING_SUSPEND` 的"只限开仓"限定字段：**正文 §3.11 写 `scope=OPEN_ONLY`，协议表 §4.2.3（`0x0118`）写 `scan_scope`** | ⚠️ 两处不一致（正文 vs 冻结协议表） | **本冻结取协议表 `scan_scope`**（M2 冻结对象优先）；已提请技术负责人统一正文措辞；`#java2` 按 `scan_scope` 实现 |
| **D-18** | **`t_product` 保留系统行 `id=0` 的初始化数据缺口**（`DC-041`/v1.3.3 新增要求）：`t_exposure.product_id` 恒 `NOT NULL` 且未映射行取哨兵 `0`，其外键指向 `t_product(id=0)` | ✅ **已修复（2026-09-20）** | 本席在 **`docs/DEPLOY/schema/data-init.sql`** 增第 ①-bis 段：`SET IDENTITY_INSERT t_product ON/OFF` + 保留行（`product_code='__UNMAPPED__'`、`product_name='未映射（系统保留）'`、`category='COMMODITY'`、`status='DISABLED'`、`disabled_reason='系统保留行：未映射实货敞口的品种哨兵值载体'`），**逐字对齐 `TB-PRODUCT-1` 备注**；抬头执行顺序更新为 `m1_config_auth.sql → m1_product.sql → data-init.sql`。<br>触发方 = `#java2` 的同步（其已按新口径改 `m1_spot.sql` 的 `ck_exposure_product_unmapped`）。**若缺此行，实机上"未映射敞口"写入必因外键失败**（`FR-SPOT-14` 验收 2 的"未映射清单"无行可落）|
| **D-16** | **`t_param_dispatch.param_version` 在 `DATA_MODEL.md` 中无 `UNIQUE` 约束**（该表 3 个索引全为普通索引），而 §3.5「应用层生成编号 + `UNIQUE` 兜底 + 冲突重试」的兜底前提**不存在** | ⚠️ **并发下发可能产生重复 `param_version`** → 会破坏 `R-CONFIG-3` 的顺序保证与 C++ 侧 `0x0403`（参数版本陈旧）判定 | **`HGC-1.2` 临时口径（已告知 `#java2`）**：`param_version` 的**分配必须由服务层串行化**（单飞 / 显式加锁），**不得**依赖"`UNIQUE` 冲突重试"（该兜底在本表不成立）。**并已提请技术负责人裁决**：是否给 `t_param_dispatch` 补 `UNIQUE(param_version)`（属表结构变更，须先改 `DATA_MODEL.md` + `DC-xxx`，我**不擅自改**） |
| **D-7** | **上游锚点漂移（本会话内 3 次）**：施工开始 `ARCHITECTURE.md` **v1.3**（`641b481b…`/2418 行）→ 收尾 **v1.5**（`9feae927…`/2516，经 `CH-006`/`CH-007`）→ 复核 **v1.5.2**（`32babcac…`/2546，经 `v1.5.1`/`v1.5.2` **勘误**） | ✅ 已逐条复核**不影响本冻结**（3 次累计） | 复核结论（v1.5.2 实测）：§4.1.1 信封/阻断四项/分页/幂等/鉴权、§4.1.2 端点、**§4.3.1 业务码仍 22 行（10001~10022）**、§3.7~§3.12 分层、§7.2.2 Java 依赖（`HikariCP 不引入`）、§11 `CFG-30/31/32/33`、**附录 B 仍 10 条红线** —— **均未变**。本冻结锚 **v1.5.2**。`DATA_MODEL.md` 全程 `99b647ec…`（5177 行）**未变**。<br>**纪律**：引用前**必重测 md5**（派发单/gate 声明均可能过期）；已就此向编排者提流程建议 |
| **D-8** | **WebSocket 义务**（`CH-006`）：`/ws/quote`、`/ws/alert` 由 Java 提供；`T-028`（选型+MVP）/ `T-040`（生产级服务端）**均归属 `#java`** | **未实现**（不属 `T-003` 验收范围）；**未新增依赖** | 见 §4.1（对外约定已冻结）+ §4.2（选型论证：推荐自实现；库路线证据见 §4.2）|
| **D-9** | `hg.http.bind/port/threads` 未登记于 §11.2 | ✅ **已闭环**（`HGC-1.3`） | 同 `D-5`：`CFG-33` 已登记、实现已改键名；**新代码不得再用 `hg.http.*`** |
| **D-10** | **WS 推送缺"子账户数据权限过滤"要求**：§4.1.2/§6.13 均未规定 `/ws/quote`、`/ws/alert` 须按 `t_user_sub_account_perm` 过滤，而 `/ws/alert` 消息**含 `subAccount` 字段** | **越权泄露风险**（`NFR-SEC-7`、`10004`） | 提为 `W-2` 待裁决；我按"安全默认 = 空集"实现（与 DAO 层同口径） |
| **D-11** | **客户端→服务端订阅消息格式未定义**（§4.1.2 仅称"沿用既有口径"） | 前端与 `T-040` 无法对接 | 提为 `W-1` 待裁决（附提案 JSON） |
| **D-12** | `CFG-32`（`hg.web.ws.port`/`_heartbeat_ms`/`_max_connections`）**取值待定**，依赖选型结论 | 部署配置无法填值 | 随 §4.2 定选后填；本组件未擅自取值 |

---

| **D-19** | **装配待办（HTTP/REST + DB 面）**：`#java2` 的 5 个 `*Routes.register(...)` 与 DAO/DB 层（连接池 / 事务模板 / 5 个 service）待 `Bootstrap` 调用 | ✅ **已闭环（2026-09-20 23:48）**：`Bootstrap.assemble` 已按 `Config.databaseConfigured()`（`CFG-31 hg.db.url`）**受配置保护装配**——为假时进程保持可用（业务路由不注册），为真时接线连接池 + 事务模板 + 5 service + 5 注册器；口令零落盘、`close` 于停机钩子释放；`DmUserRoleDao`/`DmPositionConflictDao` 两个缺失实现已补 | 实现与用例见 §4.3「装配状态」。⚠️ **`WsServer` 接线不属本条 DB 前置**，改由 `A-2`/`T-040` 承接（§4.3） |
## 7. `#java2` 开工检查清单

- [ ] `mvn -q compile` 通过（JDK 11，零警告）
- [ ] 新增 DAO：接口放 `com.hedgeguard.dao`，实现放 `dao.impl`，`extends AbstractDmDao<E, ID>`
- [ ] 事务：`txTemplate.inTransaction(() -> { ...; return null; })`；**不在 DAO 内 close 连接**
- [ ] 列表查询：注入 `SubAccountScope`（取数入口 = `AuthService.visibleSubAccountIds(userId)` → `SubAccountScope.of(...)`；**不得**用 `DmUserSubAccountPermDao.scopeOf` —— 已由 `T-099` 删除，见 `ARCHITECTURE.md` §8.2②）〔勘误动因：本行原指引 `userSubAccountPermDao.scopeOf(…)` 与 `:312` 相悖、且指向已失效入口；授权链 = 编排者 2026-09-21 授权 + `T-099` 处置〕
- [ ] 时间：一律经 `Clocks`（禁止 `LocalDateTime.now()`）
- [ ] 金额：`BigDecimal` 进出，HTTP 边界字符串化
- [ ] 敏感字段：不入日志（`SensitiveLog` 已全局安装）、不入配置、不入 DDL
- [ ] 自检：`mvn -q test`；`grep` E5 等价扫描（`dao` 之外不得出现 SQL 关键字）
- [ ] **WebSocket 相关任务（`T-028`/`T-040`）**：先读 §4.1（对外约定，已冻结）与 §4.2（选型论证）；
      **选型未经裁决前不得向 `pom.xml` 新增任何依赖**；`W-1`~`W-6` 未裁决前按"提案值"实现并标记 `// 待批（W-x）`
