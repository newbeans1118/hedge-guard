package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.ExposureDetailDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SpotChangeLogDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.ExposureDetail;
import com.hedgeguard.dao.entity.SpotChangeLog;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-114}：{@code POST /spot/exposure/recalc}（{@link SpotService#recalcForDisplay}）
 * 子账户可见性强制的验收。
 *
 * <p>锁定四类语义：
 * <ul>
 *   <li><b>越权拒绝</b>（{@code FR-AUTH-13} 验收 1 / {@code NFR-SEC-7}）：按 {@code exposureId}
 *       取到的敞口属<b>他人子账户</b> ⇒ {@code 10004}（HTTP 403），且<b>不返回</b>他人敞口分量；</li>
 *   <li><b>先校验、后留痕</b>（{@code T-114} 验收 3）：越权被拒时 {@code t_spot_change_log} 的
 *       {@code COMPONENT_RECALC} <b>零写入</b>（不得先写后拒）；用例故意构造「明细不一致」，
 *       使若遗漏校验则必然产生落库副作用（变异后必红）；</li>
 *   <li><b>合法放行</b>：对可见子账户重算成功，不一致时留痕一次且 {@code writtenBack=false}
 *       （{@code Q-17} ④ 绝不回写）；跨账户只读（哨兵 {@code 0}）在读面亦放行（{@code T-115}
 *       验收 4 防误拒——本端点不回写任何业务落库值）；</li>
 *   <li><b>fail-closed</b>：可见性入口缺位（{@code null}）/ 可见集为空 / 敞口行缺子账户标识
 *       ⇒ 一律 {@code 10004}，绝不退化为「不校验 = 放行」。</li>
 * </ul>
 *
 * <p>含一组<b>真实 {@code AuthService}</b> 端到端探针（非替身）：证明越权在真实权限判定 +
 * 越权留痕（{@code R-AUTH-8}）下被证伪，且业务留痕库零写入。完全离线（{@code NFR-TEST-2}）。
 */
class SpotT114RecalcVisibilityTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");

    /** 操作者只对 {@link #VISIBLE_SUB} 可见。 */
    private static final long OPERATOR = 999L;
    /** 可见子账户。 */
    private static final int VISIBLE_SUB = 7;
    /** 不可见子账户（越权目标）。 */
    private static final int OTHER_SUB = 8;

    private FakeExposureDao exposureDao;
    private FakeExposureDetailDao detailDao;
    private FakeSpotChangeLogDao changeLogDao;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        exposureDao = new FakeExposureDao();
        detailDao = new FakeExposureDetailDao();
        changeLogDao = new FakeSpotChangeLogDao();
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ============================================================ 一、越权拒绝 + 零副作用

    @Test
    @DisplayName("T-114 越权：recalc 他人子账户敞口 ⇒ 10004 / HTTP 403，且 COMPONENT_RECALC 零留痕")
    void recalcCrossSubAccountRejected() {
        Exposure foreign = exposure(OTHER_SUB);
        exposureDao.rows.add(foreign);
        // 故意构造「明细重算 ≠ 落库分量」：若遗漏可见性校验，重算将写入他人子账户留痕
        detailDao.rows.add(detail(foreign.id, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 21L,
                new BigDecimal("55")));

        BizException e = assertThrows(BizException.class,
                () -> service(uid -> Arrays.asList(VISIBLE_SUB)).recalcForDisplay(foreign.id, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode(), "越权须 10004");
        assertEquals(403, e.errorCode().httpStatus(), "对外表现 HTTP 403");
        assertTrue(changeLogDao.rows.isEmpty(),
                "先校验、后留痕：越权被拒时不得对他人子账户产生 COMPONENT_RECALC 落库副作用");
    }

    @Test
    @DisplayName("T-114 越权：可见性入口缺位（null）⇒ 10004 且零留痕（fail-closed）")
    void recalcFailsClosedWhenVisibilityMissing() {
        Exposure foreign = exposure(OTHER_SUB);
        exposureDao.rows.add(foreign);
        detailDao.rows.add(detail(foreign.id, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 21L,
                new BigDecimal("55")));

        BizException e = assertThrows(BizException.class,
                () -> service(null).recalcForDisplay(foreign.id, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode(),
                "入口缺位不得退化为放行");
        assertTrue(changeLogDao.rows.isEmpty(), "入口缺位时不得留痕");
    }

    @Test
    @DisplayName("T-114 fail-closed：可见集为空（无任何授权）⇒ 10004 且零留痕")
    void recalcFailsClosedWhenVisibleSetEmpty() {
        Exposure target = exposure(VISIBLE_SUB);
        exposureDao.rows.add(target);
        detailDao.rows.add(detail(target.id, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 21L,
                new BigDecimal("55")));

        BizException e = assertThrows(BizException.class,
                () -> service(uid -> new ArrayList<Integer>()).recalcForDisplay(target.id, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(changeLogDao.rows.isEmpty(), "空可见集不得退化为全可见");
    }

    @Test
    @DisplayName("T-114 fail-closed：敞口行无子账户标识 ⇒ 10004（不 NPE / 不放行）")
    void recalcNullSubAccountRejected() {
        Exposure orphan = exposure(OTHER_SUB);
        orphan.subAccountId = null;   // 数据异常：DB NOT NULL 约束被破坏
        exposureDao.rows.add(orphan);
        detailDao.rows.add(detail(orphan.id, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 21L,
                new BigDecimal("55")));

        BizException e = assertThrows(BizException.class, () -> service(
                uid -> Arrays.asList(VISIBLE_SUB)).recalcForDisplay(orphan.id, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(changeLogDao.rows.isEmpty(), "异常行不得被写入");
    }

    @Test
    @DisplayName("T-114 存在性校验在前：不存在的 exposureId ⇒ 10001（可见性不被触达）")
    void recalcNonexistentExposureIsParamInvalid() {
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> {
                    throw new AssertionError("可见性不应在敞口不存在时被触达");
                }).recalcForDisplay(404L, OPERATOR));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertTrue(changeLogDao.rows.isEmpty(), "不存在时不得留痕");
    }

    // ============================================================ 二、合法放行

    @Test
    @DisplayName("T-114 放行：可见子账户不一致 ⇒ 重算成功 + 留痕 COMPONENT_RECALC + writtenBack=false")
    void recalcVisibleMismatchSucceeds() {
        Exposure target = exposure(VISIBLE_SUB);
        exposureDao.rows.add(target);
        detailDao.rows.add(detail(target.id, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 21L,
                new BigDecimal("55")));

        Map<String, Object> out = service(uid -> Arrays.asList(VISIBLE_SUB))
                .recalcForDisplay(target.id, OPERATOR);

        assertEquals(Boolean.TRUE, out.get("mismatched"), "明细 55 ≠ 落库 60");
        assertEquals(Boolean.FALSE, out.get("writtenBack"), "展示侧绝不回写（Q-17 ④）");
        assertEquals(1, changeLogDao.rows.size(), "不一致须留痕一次");
        SpotChangeLog log = changeLogDao.rows.get(0);
        assertEquals("COMPONENT_RECALC", log.changeType);
        assertEquals(VISIBLE_SUB, log.subAccountId, "留痕归属须为目标敞口子账户");
        assertEquals(OPERATOR, log.operatorId);
        assertEquals("Y", log.recalcTriggered);
        assertEquals(new BigDecimal("60"), target.contractUnexecutedQty, "落库分量不得被改写");
    }

    @Test
    @DisplayName("T-114 放行：可见子账户且一致 ⇒ 无留痕（既有语义不变）")
    void recalcVisibleConsistentNoLog() {
        Exposure target = exposure(VISIBLE_SUB);
        exposureDao.rows.add(target);
        detailDao.rows.add(detail(target.id, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 21L,
                new BigDecimal("60")));

        Map<String, Object> out = service(uid -> Arrays.asList(VISIBLE_SUB))
                .recalcForDisplay(target.id, OPERATOR);

        assertEquals(Boolean.FALSE, out.get("mismatched"));
        assertEquals(Boolean.FALSE, out.get("writtenBack"));
        assertTrue(changeLogDao.rows.isEmpty(), "一致时不留痕");
    }

    @Test
    @DisplayName("T-114 读面口径：跨账户只读（哨兵 0）对可读敞口放行重算（只留痕、不回写）")
    void recalcCrossReadonlyAllowed() {
        Exposure target = exposure(OTHER_SUB);
        exposureDao.rows.add(target);
        detailDao.rows.add(detail(target.id, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 21L,
                new BigDecimal("55")));

        Map<String, Object> out = service(uid -> Collections.singletonList(
                SubAccountScope.ALL_ACCOUNT_SENTINEL)).recalcForDisplay(target.id, OPERATOR);

        assertEquals(Boolean.TRUE, out.get("mismatched"));
        assertEquals(Boolean.FALSE, out.get("writtenBack"),
                "跨账户只读不得触发任何业务落库写入（本端点只追加审计留痕）");
        assertEquals(1, changeLogDao.rows.size(), "跨账户只读可见该敞口 ⇒ 审计留痕照常");
    }

    // ============================================================ 三、真实 AuthService 端到端

    @Test
    @DisplayName("T-114 端到端（真实 AuthService）：越权 recalc 被拒 10004 + 越权留痕 + 业务留痕零写入")
    void realAuthServiceRejectsCrossSubAccountRecalc() {
        FakePermDao permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();
        AuthService auth = authService(permDao, attemptDao);
        permDao.rows.add(perm(OPERATOR, VISIBLE_SUB, AuthService.PERM_LIMITED));

        Exposure foreign = exposure(OTHER_SUB);
        exposureDao.rows.add(foreign);
        detailDao.rows.add(detail(foreign.id, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 21L,
                new BigDecimal("55")));

        SpotService svc = service(auth);
        BizException e = assertThrows(BizException.class,
                () -> svc.recalcForDisplay(foreign.id, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(changeLogDao.rows.isEmpty(), "越权被拒时业务留痕库零写入");
        assertFalse(attemptDao.rows.isEmpty(), "越权尝试须留痕（R-AUTH-8）");
        assertEquals(AuthService.ATTEMPT_SUB_ACCOUNT_DENIED, attemptDao.rows.get(0).attemptType);

        // 合法：可见子账户敞口 ⇒ 通过
        Exposure own = exposure(VISIBLE_SUB);
        exposureDao.rows.add(own);
        detailDao.rows.add(detail(own.id, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 21L,
                new BigDecimal("60")));
        Map<String, Object> out = svc.recalcForDisplay(own.id, OPERATOR);
        assertNotNull(out);
        assertEquals(Boolean.FALSE, out.get("mismatched"), "可见子账户重算应放行");
    }

    // ============================================================ 辅助

    /** 仅装配 {@code recalc} 所需的三个 DAO；其余依赖不被驱动 ⇒ 传 {@code null}。 */
    private SpotService service(SubAccountVisibility visibility) {
        return new SpotService(FakeTx.newTemplate(), null, null, null, null, null, null,
                exposureDao, detailDao, changeLogDao, null, null, null, null, visibility, null);
    }

    /** 真实 {@link AuthService}（仅装配可见性判定所需两个 DAO；其余不被驱动 ⇒ {@code null}）。 */
    private static AuthService authService(FakePermDao permDao, FakeAttemptDao attemptDao) {
        return new AuthService(FakeTx.newTemplate(), null, null, null, null, null, null, null,
                permDao, attemptDao, null, null, null, null, null);
    }

    private static UserSubAccountPerm perm(long userId, int subAccountId, String permType) {
        UserSubAccountPerm perm = new UserSubAccountPerm();
        perm.userId = userId;
        perm.subAccountId = subAccountId;
        perm.permType = permType;
        perm.grantedBy = 1L;
        perm.effectiveFrom = LocalDate.of(2026, 1, 1);
        return perm;
    }

    /** 敞口（落库分量 60 / 0 / 0；与明细 55 故意不一致）。 */
    private static Exposure exposure(int subAccountId) {
        Exposure e = new Exposure();
        e.id = 100L + subAccountId;
        e.snapshotId = "EXP-20260920-" + subAccountId;
        e.subAccountId = subAccountId;
        e.productId = 5L;
        e.spotCategoryCode = SpotService.MAPPED_CATEGORY_PLACEHOLDER;
        e.direction = "BUY";
        e.calcMode = "CONTRACT_UNEXECUTED";
        e.contractUnexecutedQty = new BigDecimal("60");
        e.inventoryQty = BigDecimal.ZERO;
        e.productionPlanQty = BigDecimal.ZERO;
        e.exposureQty = new BigDecimal("60");
        e.unit = "TON";
        e.asOfDate = LocalDate.of(2026, 9, 20);
        e.unmappedFlag = "N";
        return e;
    }

    private static ExposureDetail detail(long exposureId, String component, String sourceType,
                                         long sourceId, BigDecimal qty) {
        ExposureDetail d = new ExposureDetail();
        d.exposureId = exposureId;
        d.subAccountId = 7;
        d.componentType = component;
        d.sourceType = sourceType;
        d.sourceId = sourceId;
        d.contributedQty = qty;
        d.direction = "BUY";
        d.calcAsOfDate = LocalDate.of(2026, 9, 20);
        return d;
    }

    // ------------------------------------------------------------ 内存假 DAO

    private static final class FakeExposureDao extends UnsupportedBaseDao<Exposure, Long>
            implements ExposureDao {

        private final List<Exposure> rows = new ArrayList<>();

        @Override
        public Exposure findById(Long id) {
            for (Exposure row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }

        @Override
        public PageResult<Exposure> findPageScoped(PageQuery query, SubAccountScope scope,
                                                   String extraWhere, List<Object> extraArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findPageScoped");
        }

        @Override
        public Exposure findByIdScoped(long id, SubAccountScope scope) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByIdScoped");
        }
    }

    private static final class FakeExposureDetailDao extends UnsupportedBaseDao<ExposureDetail, Long>
            implements ExposureDetailDao {

        private final List<ExposureDetail> rows = new ArrayList<>();

        @Override
        public List<ExposureDetail> findByExposure(long exposureId) {
            List<ExposureDetail> out = new ArrayList<>();
            for (ExposureDetail row : rows) {
                if (row.exposureId != null && row.exposureId == exposureId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<ExposureDetail> findBySource(String sourceType, long sourceId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findBySource");
        }
    }

    private static final class FakeSpotChangeLogDao
            extends UnsupportedBaseDao<SpotChangeLog, Long> implements SpotChangeLogDao {

        private final List<SpotChangeLog> rows = new ArrayList<>();

        @Override
        public int insert(SpotChangeLog entity, long operatorId) {
            rows.add(entity);
            return 1;
        }
    }

    private static final class FakePermDao extends UnsupportedBaseDao<UserSubAccountPerm, Long>
            implements UserSubAccountPermDao {

        private final List<UserSubAccountPerm> rows = new ArrayList<>();

        @Override
        public List<UserSubAccountPerm> findByUser(long userId) {
            List<UserSubAccountPerm> out = new ArrayList<>();
            for (UserSubAccountPerm row : rows) {
                if (row.userId == userId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<Long> findUserIdsBySubAccount(int subAccountId) {
            List<Long> out = new ArrayList<>();
            for (UserSubAccountPerm row : rows) {
                if (row.subAccountId == subAccountId && !out.contains(row.userId)) {
                    out.add(row.userId);
                }
            }
            return out;
        }
    }

    private static final class FakeAttemptDao
            extends UnsupportedBaseDao<UnauthorizedAttempt, Long> implements UnauthorizedAttemptDao {

        private final List<UnauthorizedAttempt> rows = new ArrayList<>();

        @Override
        public int insert(UnauthorizedAttempt entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public long countByTypeBetween(String attemptType, java.time.LocalDateTime from,
                                       java.time.LocalDateTime to) {
            return 0;
        }

        @Override
        public List<UnauthorizedAttempt> findByOperatorSince(long operatorId,
                                                            java.time.LocalDateTime from) {
            return new ArrayList<>(rows);
        }
    }
}
