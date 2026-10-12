package com.hedgeguard.web.handler.audit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SettleCheckSignatureDao;
import com.hedgeguard.dao.SettleCheckTaskDao;
import com.hedgeguard.dao.entity.SettleCheckSignature;
import com.hedgeguard.dao.entity.SettleCheckTask;
import com.hedgeguard.service.CheckTaskQueryService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RequestDispatcher;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionAuthFilter;
import com.hedgeguard.web.SessionStore;
import com.hedgeguard.web.StaticHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-194} 核对任务查询 REST 端点测试（{@code FR-AUDIT-13} 验收 1/2/5）。
 *
 * <p>与 {@code com.hedgeguard.service.CheckTaskQueryServiceTest}（服务层）互补：本类走
 * <b>真实 {@link RequestDispatcher} + {@link Router} + {@link CheckTaskQueryHandler}</b> +
 * 记录型假 DAO，验证
 * <ul>
 *   <li>2 条新增路径均已注册且由 {@link CheckTaskQueryHandler} 处理（仅 GET；POST ⇒ 405）；</li>
 *   <li>分页信封（{@code items}/{@code total}/{@code page}/{@code size}）与
 *       {@code FR-AUDIT-13} 输出列（逾期清单 + 完成率）齐备；</li>
 *   <li>签署明细含验收 1 五要素（账号 / 角色 / 时间 / IP / 快照哈希）；</li>
 *   <li>筛选下推（checkType / subAccountId / signRole / overdueOnly）与
 *       非法参数（400/10001）；</li>
 *   <li>子账户可见性经服务内 {@code SubAccountVisibility} 强制（非审计岗仅见授权子账户）。</li>
 * </ul>
 * 完全离线（{@code NFR-TEST-2}）。
 */
class CheckTaskQueryHandlerTest {

    private static final long OPERATOR_AUDIT = 42L;   // 审计岗（跨子账户）
    private static final long OPERATOR_LIMITED = 43L; // 非审计岗（仅子账户 1）

    // ------------------------------------------------------------ 路由装配

    @Test
    @DisplayName("端点层：/audit/check-tasks 与 /audit/check-tasks/overdue 均注册且由 CheckTaskQueryHandler 处理")
    void bothRoutesRegistered() {
        Router router = new Router();
        CheckTaskQueryRoutes.register(router, service(fixed()));
        for (String path : new String[]{CheckTaskQueryHandler.PATH, CheckTaskQueryHandler.PATH_OVERDUE}) {
            Router.RouteMatch m = router.match("GET", path);
            assertNotNull(m, "端点未注册：GET " + path);
            assertTrue(m.handler() instanceof CheckTaskQueryHandler,
                    "应由 CheckTaskQueryHandler 处理：" + path);
        }
        assertEquals(2, router.routes().size(), "本注册器恰注册 2 个 (方法,路径) 对");
    }

    @Test
    @DisplayName("端点层：POST /audit/check-tasks ⇒ 405（只读端点，R-AUDIT-6；不存在修改或删除入口，验收 4）")
    void postRejectedAsReadOnly(@TempDir Path webRoot) {
        HttpResult r = dispatch(webRoot, "POST", "/audit/check-tasks", "{}", null, OPERATOR_AUDIT);
        assertEquals(405, r.status, "只读端点 POST 应 405，实际=" + r.status);
    }

    // ------------------------------------------------------------ 分页信封 + FR-AUDIT-13 输出列

    @Test
    @DisplayName("端点层：GET /audit/check-tasks 返回分页信封 + 签署明细五要素（验收 1）+ 逾期清单 + 完成率")
    void queryReturnsEnvelopeAndSignatureFields(@TempDir Path webRoot) {
        FakeTasks tasks = fixed();
        HttpResult r = dispatch(webRoot, "GET", "/audit/check-tasks", null,
                "page=1&size=20&asOf=2026-10-01", OPERATOR_AUDIT, tasks, new FakeSignatures());
        assertEquals(200, r.status);
        JsonObject data = data(r);
        assertTrue(data.has("items") && data.has("total") && data.has("page") && data.has("size"),
                "分页四键齐备：" + data);
        assertEquals(2, data.get("total").getAsInt());
        assertEquals(1, data.get("page").getAsInt());
        assertEquals(20, data.get("size").getAsInt());

        JsonArray items = data.getAsJsonArray("items");
        assertEquals(2, items.size());
        JsonObject first = items.get(0).getAsJsonObject();
        assertTrue(first.has("checkType") && first.has("periodKey") && first.has("status"),
                "任务字段齐备");
        // 验收 1：签署明细含账号、角色、时间、IP 与核对对象快照哈希
        JsonArray sigs = first.getAsJsonArray("signatures");
        assertEquals(2, sigs.size(), "三方（此处夹具两方）签署明细在列");
        JsonObject sig = sigs.get(0).getAsJsonObject();
        for (String field : new String[]{"signerId", "signRole", "signTime", "signIp", "snapshotHash"}) {
            assertTrue(sig.has(field) && !sig.get(field).isJsonNull(),
                    "签署明细须含 " + field + "（FR-AUDIT-13 验收 1）");
        }

        // FR-AUDIT-13 输出列：逾期清单 + 完成率汇总 + 可见范围披露
        assertTrue(data.has("overdue") && data.has("coverage"),
                "输出列（逾期清单 + 完成率汇总）齐备");
        assertEquals(1, data.getAsJsonArray("overdue").size(), "OVERDUE 任务入逾期清单");
        assertTrue(data.getAsJsonObject("coverage").has("MONTHLY"), "完成率按核对类型归集");
        assertTrue(data.has("crossSubAccount") && data.has("visibleSubAccounts"),
                "可见范围披露齐备（R-AUDIT-9）");
        assertTrue(data.get("crossSubAccount").getAsBoolean(), "审计岗跨子账户（验收 5）");
    }

    @Test
    @DisplayName("端点层：GET /audit/check-tasks/overdue 返回逾期未签清单（验收 2；消费服务 overdue）")
    void overdueEndpointReturnsOverdueList(@TempDir Path webRoot) {
        HttpResult r = dispatch(webRoot, "GET", "/audit/check-tasks/overdue", null,
                "asOf=2026-10-01", OPERATOR_AUDIT);
        assertEquals(200, r.status);
        JsonObject data = data(r);
        assertEquals(1, data.get("total").getAsInt(), "仅 1 条逾期（deadline < asOf 且未完成）");
        JsonObject task = data.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("OVERDUE", task.get("status").getAsString());
        assertEquals("2026-09", task.get("periodKey").getAsString());
    }

    // ------------------------------------------------------------ 筛选下推

    @Test
    @DisplayName("端点层：checkType/subAccountId/status 筛选下推至服务（where 参数实测）+ signRole 筛签署明细")
    void filtersPushedDown(@TempDir Path webRoot) {
        RecordingTasks tasks = new RecordingTasks(fixed());
        dispatch(webRoot, "GET", "/audit/check-tasks", null,
                "checkType=MONTHLY&subAccountId=1&status=PENDING_SIGN&signRole=RISK",
                OPERATOR_AUDIT, tasks, new FakeSignatures());
        assertEquals("MONTHLY", tasks.lastCheckType, "checkType 筛选下推");
        assertEquals(Integer.valueOf(1), tasks.lastSubAccountId, "subAccountId 筛选下推");
        assertEquals("PENDING_SIGN", tasks.lastStatus, "status 筛选下推");
    }

    @Test
    @DisplayName("端点层：signRole=RISK ⇒ 签署明细仅 RISK 一方在列（服务层 view 过滤，验收 1 角色维度）")
    void signRoleFiltersSignatures(@TempDir Path webRoot) {
        HttpResult r = dispatch(webRoot, "GET", "/audit/check-tasks", null,
                "signRole=RISK", OPERATOR_AUDIT);
        assertEquals(200, r.status);
        JsonArray items = data(r).getAsJsonArray("items");
        assertEquals(2, items.size());
        for (int i = 0; i < items.size(); i++) {
            JsonArray sigs = items.get(i).getAsJsonObject().getAsJsonArray("signatures");
            for (int j = 0; j < sigs.size(); j++) {
                assertEquals("RISK", sigs.get(j).getAsJsonObject().get("signRole").getAsString(),
                        "signRole 筛选后仅 RISK 签署在列");
            }
        }
    }

    @Test
    @DisplayName("端点层：overdueOnly=true 下推为逾期筛选（验收 2「逾期未签可单独筛出」）")
    void overdueOnlyPushedDown(@TempDir Path webRoot) {
        RecordingTasks tasks = new RecordingTasks(fixed());
        dispatch(webRoot, "GET", "/audit/check-tasks", null, "overdueOnly=true&asOf=2026-10-01",
                OPERATOR_AUDIT, tasks, new FakeSignatures());
        assertTrue(tasks.lastOverdueOnly, "overdueOnly 下推");
        assertEquals(LocalDate.parse("2026-10-01"), tasks.lastAsOf, "asOf 下推");
    }

    @Test
    @DisplayName("端点层：fromPeriod/toPeriod 时间范围筛选下推（FR-AUDIT-13 输入列）")
    void periodRangePushedDown(@TempDir Path webRoot) {
        RecordingTasks tasks = new RecordingTasks(fixed());
        dispatch(webRoot, "GET", "/audit/check-tasks", null,
                "fromPeriod=2026-09&toPeriod=2026-10", OPERATOR_AUDIT, tasks, new FakeSignatures());
        assertEquals("2026-09", tasks.lastFromPeriod);
        assertEquals("2026-10", tasks.lastToPeriod);
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    @DisplayName("端点层：subAccountId 非整数 ⇒ 400/10001")
    void invalidSubAccountId(@TempDir Path webRoot) {
        HttpResult r = dispatch(webRoot, "GET", "/audit/check-tasks", null,
                "subAccountId=abc", OPERATOR_AUDIT);
        assertEquals(400, r.status);
        assertEquals(10001, code(r));
    }

    @Test
    @DisplayName("端点层：asOf 非 yyyy-MM-dd ⇒ 400/10001")
    void invalidAsOf(@TempDir Path webRoot) {
        HttpResult r = dispatch(webRoot, "GET", "/audit/check-tasks/overdue", null,
                "asOf=2026/10/01", OPERATOR_AUDIT);
        assertEquals(400, r.status);
        assertEquals(10001, code(r));
    }

    // ------------------------------------------------------------ 空态

    @Test
    @DisplayName("端点层：无数据 ⇒ 空列表非报错（FR-AUDIT-13 输出「无数据返回空列表」）")
    void emptyResult(@TempDir Path webRoot) {
        FakeTasks tasks = new FakeTasks(false);
        HttpResult r = dispatch(webRoot, "GET", "/audit/check-tasks", null, null, OPERATOR_AUDIT,
                tasks, new FakeSignatures());
        assertEquals(200, r.status);
        JsonObject data = data(r);
        assertEquals(0, data.get("total").getAsInt());
        assertEquals(0, data.getAsJsonArray("items").size());
        assertEquals(0, data.getAsJsonArray("overdue").size());
    }

    // ------------------------------------------------------------ 子账户可见性（软隔离 R-AUDIT-9 / NFR-SEC-7）

    @Test
    @DisplayName("端点层：非审计岗（仅子账户 1）⇒ 子账户 2 任务不可见（可见性由服务强制）")
    void subAccountIsolationEnforcedByService(@TempDir Path webRoot) {
        HttpResult r = dispatch(webRoot, "GET", "/audit/check-tasks", null, null, OPERATOR_LIMITED);
        assertEquals(200, r.status);
        JsonObject data = data(r);
        assertEquals(1, data.get("total").getAsInt(), "仅见授权子账户任务");
        assertFalse(data.get("crossSubAccount").getAsBoolean(), "非审计岗不跨子账户");
        JsonArray items = data.getAsJsonArray("items");
        assertEquals(1, items.size());
        assertEquals(1, items.get(0).getAsJsonObject().get("subAccountId").getAsInt());
    }

    // ------------------------------------------------------------ 夹具

    /** 记录型假任务 DAO（默认夹具 2 行；空态用例以 {@code clearRows} 清空）。 */
    static class FakeTasks implements SettleCheckTaskDao {
        final List<SettleCheckTask> rows = new ArrayList<>();
        long lastId = 0;

        FakeTasks() {
            this(true);
        }

        FakeTasks(boolean seeded) {
            if (!seeded) {
                return;
            }
            rows.add(task(1L, "MONTHLY", "2026-10", 1, "PENDING_SIGN",
                    LocalDate.parse("2026-10-31")));
            rows.add(task(2L, "MONTHLY", "2026-09", 2, "OVERDUE",
                    LocalDate.parse("2026-09-30")));
        }

        static SettleCheckTask task(long id, String type, String periodKey, int sub, String status,
                                    LocalDate deadline) {
            SettleCheckTask t = new SettleCheckTask();
            t.id = id;
            t.checkType = type;
            t.periodKey = periodKey;
            t.subAccountId = sub;
            t.status = status;
            t.requiredSignRoles = "RISK,TRADE,FINANCE";
            t.signedRoles = "";
            t.snapshotHash = "hash-" + id;
            t.deadlineDate = deadline;
            t.generatedTime = LocalDateTime.parse("2026-09-30T08:00:00");
            return t;
        }

        @Override
        public PageResult<SettleCheckTask> findPage(PageQuery query, String where,
                                                    List<Object> whereArgs) {
            // 按位置解析 where 子句（与 CheckTaskQueryService 拼装顺序对应）：
            // check_type = ? / status = ? / status <> ? / sub_account_id = ? /
            // period_key >= ? / period_key <= ? / deadline_date < ?
            String checkType = null;
            String status = null;
            String statusNe = null;
            Integer sub = null;
            LocalDate deadlineBefore = null;
            String periodFrom = null;
            String periodTo = null;
            if (where != null && whereArgs != null) {
                int argIdx = 0;
                for (String clause : where.split(" AND ")) {
                    String c = clause.trim();
                    Object arg = argIdx < whereArgs.size() ? whereArgs.get(argIdx) : null;
                    if (c.startsWith("check_type")) {
                        checkType = (String) arg;
                    } else if (c.startsWith("status <>")) {
                        statusNe = (String) arg;
                    } else if (c.startsWith("status")) {
                        status = (String) arg;
                    } else if (c.startsWith("sub_account_id")) {
                        sub = (Integer) arg;
                    } else if (c.startsWith("period_key >=")) {
                        periodFrom = (String) arg;
                    } else if (c.startsWith("period_key <=")) {
                        periodTo = (String) arg;
                    } else if (c.startsWith("deadline_date")) {
                        deadlineBefore = (LocalDate) arg;
                    }
                    if (c.contains("?")) {
                        argIdx++;
                    }
                }
            }
            List<SettleCheckTask> out = new ArrayList<>();
            for (SettleCheckTask t : rows) {
                if (checkType != null && !checkType.equals(t.checkType)) {
                    continue;
                }
                if (status != null && !status.equals(t.status)) {
                    continue;
                }
                if (statusNe != null && statusNe.equals(t.status)) {
                    continue;
                }
                if (sub != null && t.subAccountId != null && t.subAccountId != sub) {
                    continue;
                }
                if (periodFrom != null && (t.periodKey == null || t.periodKey.compareTo(periodFrom) < 0)) {
                    continue;
                }
                if (periodTo != null && (t.periodKey == null || t.periodKey.compareTo(periodTo) > 0)) {
                    continue;
                }
                if (deadlineBefore != null && (t.deadlineDate == null
                        || !t.deadlineDate.isBefore(deadlineBefore))) {
                    continue;
                }
                out.add(t);
            }
            return new PageResult<>(out, out.size(), query.page(), query.size());
        }

        @Override
        public int insert(SettleCheckTask entity, long operatorId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("FakeTasks.insert");
        }

        @Override
        public int updateById(SettleCheckTask entity, long operatorId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("FakeTasks.updateById");
        }

        @Override
        public SettleCheckTask findById(Long id) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("FakeTasks.findById");
        }

        @Override
        public SettleCheckTask findByPeriod(String checkType, String periodKey, int subAccountId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("FakeTasks.findByPeriod");
        }

        @Override
        public List<SettleCheckTask> findOverdue(LocalDate asOf) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("FakeTasks.findOverdue");
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("FakeTasks.count");
        }
    }

    /** 捕获服务下推筛选条件的记录型假任务 DAO（继承 {@link FakeTasks} 求值）。 */
    static final class RecordingTasks extends FakeTasks {
        String lastCheckType;
        String lastStatus;
        Integer lastSubAccountId;
        Boolean lastOverdueOnly;
        LocalDate lastAsOf;
        String lastFromPeriod;
        String lastToPeriod;

        RecordingTasks(FakeTasks delegate) {
            this.rows.addAll(delegate.rows);
        }

        @Override
        public PageResult<SettleCheckTask> findPage(PageQuery query, String where,
                                                    List<Object> whereArgs) {
            // 从 where 语句与参数中提取下推值（与 CheckTaskQueryService.query 的拼装顺序对应）
            if (where != null && whereArgs != null) {
                int argIdx = 0;
                for (String clause : where.split(" AND ")) {
                    String c = clause.trim();
                    Object arg = argIdx < whereArgs.size() ? whereArgs.get(argIdx) : null;
                    if (c.startsWith("check_type")) {
                        lastCheckType = (String) arg;
                    } else if (c.startsWith("status <>")) {
                        // status <> 'COMPLETED'（overdueOnly 下推形态；须先于 status 前缀匹配）
                        lastOverdueOnly = Boolean.TRUE;
                    } else if (c.startsWith("status")) {
                        lastStatus = (String) arg;
                    } else if (c.startsWith("sub_account_id")) {
                        lastSubAccountId = (Integer) arg;
                    } else if (c.startsWith("period_key >=")) {
                        lastFromPeriod = (String) arg;
                    } else if (c.startsWith("period_key <=")) {
                        lastToPeriod = (String) arg;
                    } else if (c.startsWith("deadline_date")) {
                        lastAsOf = (LocalDate) arg;
                    }
                    if (c.contains("?")) {
                        argIdx++;
                    }
                }
            }
            return super.findPage(query, where, whereArgs);
        }
    }

    /** 记录型假签署 DAO：任务 1 两方签署（RISK + FINANCE），五要素齐备。 */
    static final class FakeSignatures implements SettleCheckSignatureDao {
        private final List<SettleCheckSignature> rows = new ArrayList<>();

        FakeSignatures() {
            rows.add(sig(1L, "RISK", 11L, "10.0.0.1"));
            rows.add(sig(1L, "FINANCE", 12L, "10.0.0.2"));
        }

        private static SettleCheckSignature sig(long taskId, String role, long signer, String ip) {
            SettleCheckSignature s = new SettleCheckSignature();
            s.id = taskId * 10 + (role.equals("RISK") ? 1 : 2);
            s.checkTaskId = taskId;
            s.signRole = role;
            s.signerId = signer;
            s.signerRoleCode = role;
            s.signTime = LocalDateTime.parse("2026-10-01T09:30:00");
            s.signIp = ip;
            s.snapshotHash = "sig-hash-" + taskId + "-" + role;
            s.signResult = "SIGNED";
            return s;
        }

        @Override
        public List<SettleCheckSignature> findByTask(long checkTaskId) {
            List<SettleCheckSignature> out = new ArrayList<>();
            for (SettleCheckSignature s : rows) {
                if (s.checkTaskId != null && s.checkTaskId == checkTaskId) {
                    out.add(s);
                }
            }
            return out;
        }

        @Override
        public SettleCheckSignature findByTaskAndRole(long checkTaskId, String signRole) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("FakeSignatures.findByTaskAndRole");
        }

        @Override
        public int insert(SettleCheckSignature entity, long operatorId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("FakeSignatures.insert");
        }

        @Override
        public int updateById(SettleCheckSignature entity, long operatorId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("FakeSignatures.updateById");
        }

        @Override
        public SettleCheckSignature findById(Long id) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("FakeSignatures.findById");
        }

        @Override
        public PageResult<SettleCheckSignature> findPage(PageQuery query, String where,
                                                         List<Object> whereArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("FakeSignatures.findPage");
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("FakeSignatures.count");
        }
    }

    // ------------------------------------------------------------ 装配辅助

    private static FakeTasks fixed() {
        return new FakeTasks();
    }

    private static CheckTaskQueryService service(SettleCheckTaskDao tasks) {
        return new CheckTaskQueryService(tasks, new FakeSignatures(), visibility());
    }

    /** 可见性：OPERATOR_AUDIT 跨子账户（哨兵 0）；OPERATOR_LIMITED 仅子账户 1。 */
    private static com.hedgeguard.service.SubAccountVisibility visibility() {
        return userId -> userId == OPERATOR_AUDIT
                ? Collections.singletonList(0)
                : Collections.singletonList(1);
    }

    private static HttpResult dispatch(Path webRoot, String method, String modulePath,
                                       String payload, String query, long userId) {
        return dispatch(webRoot, method, modulePath, payload, query, userId,
                new FakeTasks(), new FakeSignatures());
    }

    private static HttpResult dispatch(Path webRoot, String method, String modulePath,
                                       String payload, String query, long userId,
                                       SettleCheckTaskDao tasks, FakeSignatures sigs) {
        Router router = new Router();
        CheckTaskQueryRoutes.register(router, service(tasks));
        SessionStore store = new SessionStore.InMemory();
        store.save(new Session("S-T194", OPERATOR_AUDIT, "auditor", 1L, Clocks.now().plusMinutes(30)));
        store.save(new Session("S-T194-L", OPERATOR_LIMITED, "ops", 1L, Clocks.now().plusMinutes(30)));
        RequestDispatcher dispatcher = new RequestDispatcher(router, new StaticHandler(webRoot),
                new SessionAuthFilter(store, "HGSESSION"));
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION="
                + (userId == OPERATOR_AUDIT ? "S-T194" : "S-T194-L"));
        if (payload != null) {
            headers.put("Content-Type", "application/json");
        }
        return dispatcher.dispatch(new RequestContext(method, ApiRoutes.full(modulePath), query,
                headers, payload, "HGT194", "127.0.0.1"));
    }

    private static String body(HttpResult result) {
        return new String(result.body, StandardCharsets.UTF_8);
    }

    private static int code(HttpResult result) {
        return JsonParser.parseString(body(result)).getAsJsonObject().get("code").getAsInt();
    }

    private static JsonObject data(HttpResult result) {
        return JsonParser.parseString(body(result)).getAsJsonObject().getAsJsonObject("data");
    }
}
