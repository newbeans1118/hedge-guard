package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PlanApprovalDao;
import com.hedgeguard.dao.PlanStateLogDao;
import com.hedgeguard.dao.ProductContractDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TradePlanDao;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.PlanApproval;
import com.hedgeguard.dao.entity.PlanStateLog;
import com.hedgeguard.dao.entity.ProductContract;
import com.hedgeguard.dao.entity.TradePlan;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-102} 写面**子账户可见性**强制补齐的验收用例（{@code ①}/{@code ③}/{@code ④}）。
 *
 * <p>{@code ②}（{@code assertCanTrade} 判据的**析取**订正）的用例在 {@link AuthServiceTest}
 * （{@code ②a} / {@code ②b} 两条单条件）；其**服务端强制点为 M2 下单写路径**（{@code T-046}），
 * **不**落在本类覆盖的计划写路径上（{@code FR-PLAN-14} 明列分解主体含「财务/风控岗」）。
 *
 * <p>覆盖三类拒绝 / 一致性：
 * <ol>
 *   <li><b>① 越权写被拒</b>（{@code NFR-SEC-7} / {@code FR-AUTH-13}）：受限身份对**他人子账户**的计划
 *       执行写动作 ⇒ {@code 10004}；对照：对本子账户的计划同一动作放行；</li>
 *   <li><b>③ withdraw 口径一致</b>：非发起人可撤销（实现不比较 {@code applicantId}）+ 源码 javadoc
 *       不再承诺「须为发起人」；</li>
 *   <li><b>④ decompose 既有量按可见集合读取</b>：跨子账户既有交易计划**不计入** {@code used}
 *       （不过滤时本用例必红）。</li>
 *   <li><b>T-115 写面拒跨账户只读</b>：跨子账户只读（哨兵 0）身份对分解入口 ⇒ {@code 10004}
 *       （读面放行 ≠ 写面放行，{@code FR-AUTH-13} 验收 3）。</li>
 * </ol>
 *
 * <p>完全离线（{@code NFR-TEST-2}）：以内存假 DAO 驱动，不含任何 SQL / 连接。
 */
class PlanWritePathPermissionTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");

    private FakeHedgePlanDao planDao;
    private FakeTradePlanDao tradePlanDao;
    private FakePlanApprovalDao approvalDao;
    private FakePlanStateLogDao stateLogDao;
    private FakeProductContractDao contractDao;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        planDao = new FakeHedgePlanDao();
        tradePlanDao = new FakeTradePlanDao();
        approvalDao = new FakePlanApprovalDao();
        stateLogDao = new FakePlanStateLogDao();
        contractDao = new FakeProductContractDao();
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ------------------------------------------------------------ ① 写面可见性

    @Test
    @DisplayName("① 越权写被拒：受限身份对他人子账户的计划写动作 ⇒ 10004；同子账户放行（对照）")
    void crossSubAccountWriteRejected() {
        planDao.rows.add(plan(1L, 8, "PENDING_APPROVAL", 1L));

        PlanService outsider = service(uid -> Arrays.asList(7));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class,
                        () -> outsider.withdraw(1L, 999L, "越权撤销")).errorCode(),
                "受限身份（可见集 [7]）对属子账户 8 的计划写 ⇒ 拒");

        // 对照：同一动作在可见子账户下放行（证明拒绝来自可见性而非状态 / 参数）
        PlanService insider = service(uid -> Arrays.asList(8));
        insider.withdraw(1L, 999L, "本子账户撤销");
        assertEquals("DRAFT", planDao.rows.get(0).status, "可见身份下撤销成功并回草稿");
    }

    @Test
    @DisplayName("① 写面可见性覆盖全部计划写动作（以 submit/freeze 为例，均经统一入口）")
    void visibilityGateCoversPlanWriteActions() {
        planDao.rows.add(plan(2L, 8, "DRAFT", 1L));
        PlanService outsider = service(uid -> Arrays.asList(7));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class,
                        () -> outsider.submitForApproval(2L, "{\"items\":[]}", 999L)).errorCode());

        planDao.rows.get(0).status = "APPROVED";
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class,
                        () -> outsider.freeze(2L, 999L, "越权冻结")).errorCode());
    }

    // ------------------------------------------------------------ ④ 既有量按可见集合读取

    @Test
    @DisplayName("④ decompose「既有量」按可见集合读取：跨子账户既有交易计划不计入 used（不过滤时必红）")
    void decomposeExistingQuantityExcludesForeignSubAccount() {
        planDao.rows.add(bigPlan(4L, 7, "1000"));
        tradePlanDao.rows.add(tradePlan(4L, 7, "400"));     // 本子账户既有量
        tradePlanDao.rows.add(tradePlan(4L, 8, "100000"));  // 跨子账户（脏数据 / 越权行）
        PlanService svc = service(uid -> Arrays.asList(7));

        int n = svc.decomposeTradePlans(4L, decomposeInputOf("500"), 999L);

        assertEquals(1, n, "used=400（仅本子账户）+500 ≤ 1000 ⇒ 放行；"
                + "若「既有量」未按可见集合过滤则 used=100400 ⇒ 必被拒（本断言即反退化探针）");
        long foreignCounted = tradePlanDao.rows.stream()
                .filter(r -> r.subAccountId != null && r.subAccountId == 8)
                .count();
        assertEquals(1, foreignCounted, "跨子账户既有行未被改写（只读判定）");
    }

    @Test
    @DisplayName("T-115 写面拒绝：跨账户只读（含哨兵）对他人子账户计划分解 ⇒ 入口 10004 且零写入")
    void decomposeCrossReadonlyRejectedAtEntry() {
        // T-115：跨子账户只读（哨兵 0）读面放行、写面必须拒绝（FR-AUTH-13 验收 3 / R-AUTH-5）。
        // 旧断言（放行并走到「累计超量」PARAM_INVALID）编码的是错误口径，已订正为「入口拒 10004、零写入」。
        planDao.rows.add(bigPlan(5L, 7, "1000"));
        tradePlanDao.rows.add(tradePlan(5L, 7, "400"));
        tradePlanDao.rows.add(tradePlan(5L, 8, "100000"));
        PlanService svc = service(
                uid -> Collections.singletonList(SubAccountScope.ALL_ACCOUNT_SENTINEL));

        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class,
                        () -> svc.decomposeTradePlans(5L, decomposeInputOf("500"), 999L)).errorCode(),
                "跨账户只读不得写：分解入口须先拒（10004）");
        assertEquals(2, tradePlanDao.rows.size(), "越权被拒时不得落任何交易计划");
    }

    @Test
    @DisplayName("④ 跨子账户 decompose 被拒：受限身份对他人子账户计划分解 ⇒ 10004（入口可见性强制）")
    void crossSubAccountDecomposeRejected() {
        planDao.rows.add(bigPlan(6L, 8, "1000"));
        PlanService svc = service(uid -> Arrays.asList(7));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                assertThrows(BizException.class,
                        () -> svc.decomposeTradePlans(6L, decomposeInputOf("500"), 999L)).errorCode(),
                "受限身份（可见集 [7]）对属子账户 8 的计划分解 ⇒ 拒");
    }

    // ------------------------------------------------------------ ③ withdraw 口径一致

    @Test
    @DisplayName("③ withdraw 非发起人可撤销（实现不比较 applicantId，与订正后 javadoc 一致）")
    void withdrawDoesNotRequireApplicant() {
        planDao.rows.add(plan(7L, 7, "PENDING_APPROVAL", 1L));
        PlanService svc = service(uid -> Arrays.asList(7));

        svc.withdraw(7L, 999L, "非发起人撤回");

        assertEquals("DRAFT", planDao.rows.get(0).status, "非发起人撤销成功（实现不校验发起人）");
        assertEquals("WITHDRAW", approvalDao.rows.get(0).nodeType);
    }

    @Test
    @DisplayName("③ javadoc 与实现一致：withdraw 的 javadoc 不再承诺「须为发起人」（源码级断言）")
    void withdrawJavadocConsistentWithImplementation() throws IOException {
        Path file = repoRoot().resolve("java/src/main/java/com/hedgeguard/service/PlanService.java");
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        int sig = text.indexOf("public void withdraw(");
        assertTrue(sig > 0, "未找到 withdraw 方法签名：" + file);
        int docStart = text.lastIndexOf("/**", sig);
        assertTrue(docStart > 0, "withdraw 方法缺少 javadoc（该块即不一致点）");
        String doc = text.substring(docStart, sig);
        assertTrue(doc.contains("@param operatorId"), "javadoc 须保留 operatorId 说明");
        assertTrue(!doc.contains("须为发起人"),
                "javadoc 不得再承诺「须为发起人」——FR-PLAN-13 未要求该限制（PLAN.md:288-301）");
    }

    // ------------------------------------------------------------ 辅助

    private PlanService service(SubAccountVisibility visibility) {
        return new PlanService(FakeTx.newTemplate(), planDao, null, null, null, null,
                approvalDao, stateLogDao, tradePlanDao, null, contractDao, visibility,
                null, null, null, null, null, null);
    }

    private static HedgePlan plan(long id, int subAccountId, String status, long applicantId) {
        HedgePlan plan = new HedgePlan();
        plan.id = id;
        plan.subAccountId = subAccountId;
        plan.planNo = "HG-PLAN-" + id;
        plan.status = status;
        plan.applicantId = applicantId;
        plan.currentDocVersion = 0;
        return plan;
    }

    private static HedgePlan bigPlan(long id, int subAccountId, String planQty) {
        HedgePlan plan = plan(id, subAccountId, "APPROVED", 1L);
        plan.productId = 1L;
        plan.hedgeDirection = "BUY_HEDGE";
        plan.planQty = new BigDecimal(planQty);
        plan.termStartDate = LocalDate.of(2026, 9, 1);
        plan.termEndDate = LocalDate.of(2026, 12, 31);
        return plan;
    }

    private static TradePlan tradePlan(long hedgePlanId, int subAccountId, String qty) {
        TradePlan row = new TradePlan();
        row.hedgePlanId = hedgePlanId;
        row.subAccountId = subAccountId;
        row.planQty = new BigDecimal(qty);
        return row;
    }

    private List<TradePlan> decomposeInputOf(String qty) {
        contractDao.rows.add(contract("cu2612", 1L));
        TradePlan row = new TradePlan();
        row.instrumentId = "cu2612";
        row.instrumentType = "FUTURES";
        row.direction = "BUY";
        row.planQty = new BigDecimal(qty);
        row.execDeadline = LocalDate.of(2026, 12, 31);
        return Arrays.asList(row);
    }

    private static ProductContract contract(String instrumentId, long productId) {
        ProductContract c = new ProductContract();
        c.instrumentId = instrumentId;
        c.productId = productId;
        return c;
    }

    /** 从测试工作目录向上寻找仓库根（含 {@code docs/DATA_MODEL.md}）。 */
    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && path != null; i++) {
            if (Files.isRegularFile(path.resolve("docs/DATA_MODEL.md"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("未找到仓库根；当前=" + Paths.get("").toAbsolutePath());
    }

    // ------------------------------------------------------------ 内存假 DAO

    private static final class FakeHedgePlanDao extends UnsupportedBaseDao<HedgePlan, Long>
            implements HedgePlanDao {

        private final List<HedgePlan> rows = new ArrayList<>();

        @Override
        public String findMaxPlanNoByPrefix(String planNoPrefix) {
            String max = null;
            for (HedgePlan row : rows) {
                if (row.planNo != null && row.planNo.startsWith(planNoPrefix)
                        && (max == null || row.planNo.compareTo(max) > 0)) {
                    max = row.planNo;
                }
            }
            return max;
        }

        @Override
        public HedgePlan findById(Long id) {
            for (HedgePlan row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public int updateById(HedgePlan entity, long operatorId) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id == entity.id) {
                    rows.set(i, entity);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public PageResult<HedgePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                                    String extraWhere, List<Object> extraArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findPageScoped");
        }

        @Override
        public HedgePlan findByIdScoped(long id, SubAccountScope scope) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByIdScoped");
        }

        @Override
        public HedgePlan findByPlanNo(String planNo) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByPlanNo");
        }

        @Override
        public int deleteById(Long id) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("deleteById");
        }
    }

    private static final class FakeTradePlanDao extends UnsupportedBaseDao<TradePlan, Long>
            implements TradePlanDao {

        private final List<TradePlan> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(TradePlan entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }

        @Override
        public List<TradePlan> findByHedgePlan(long hedgePlanId) {
            List<TradePlan> out = new ArrayList<>();
            for (TradePlan row : rows) {
                if (row.hedgePlanId == hedgePlanId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<TradePlan> findByHedgePlanScoped(long hedgePlanId, SubAccountScope scope) {
            List<TradePlan> out = new ArrayList<>();
            if (scope.isEmpty()) {
                return out;
            }
            for (TradePlan row : rows) {
                if (row.hedgePlanId == hedgePlanId && row.subAccountId != null
                        && scope.allowed().contains(row.subAccountId)) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public PageResult<TradePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                                    String extraWhere, List<Object> extraArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findPageScoped");
        }

        @Override
        public TradePlan findByTradePlanNo(String tradePlanNo) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByTradePlanNo");
        }

        @Override
        public String findMaxTradePlanNoByPrefix(String tradePlanNoPrefix) {
            String max = null;
            for (TradePlan row : rows) {
                if (row.tradePlanNo != null && row.tradePlanNo.startsWith(tradePlanNoPrefix)
                        && (max == null || row.tradePlanNo.compareTo(max) > 0)) {
                    max = row.tradePlanNo;
                }
            }
            return max;
        }
    }

    private static final class FakePlanApprovalDao extends UnsupportedBaseDao<PlanApproval, Long>
            implements PlanApprovalDao {

        private final List<PlanApproval> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(PlanApproval entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }
    }

    private static final class FakePlanStateLogDao extends UnsupportedBaseDao<PlanStateLog, Long>
            implements PlanStateLogDao {

        @Override
        public int insert(PlanStateLog entity, long operatorId) {
            assertNotNull(entity, "状态留痕行不得为空");
            return 1;
        }
    }

    private static final class FakeProductContractDao
            extends UnsupportedBaseDao<ProductContract, Long> implements ProductContractDao {

        private final List<ProductContract> rows = new ArrayList<>();

        @Override
        public ProductContract findByInstrumentId(String instrumentId) {
            for (ProductContract row : rows) {
                if (row.instrumentId.equals(instrumentId)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public List<ProductContract> findByProduct(long productId) {
            List<ProductContract> out = new ArrayList<>();
            for (ProductContract row : rows) {
                if (row.productId != null && row.productId == productId) {
                    out.add(row);
                }
            }
            return out;
        }
    }
}
