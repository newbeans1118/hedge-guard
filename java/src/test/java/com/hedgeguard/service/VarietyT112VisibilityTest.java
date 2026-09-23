package com.hedgeguard.service;

import com.hedgeguard.dao.ProductChangeLogDao;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.ProductQuotaDao;
import com.hedgeguard.dao.SubInstrumentQuotaDao;
import com.hedgeguard.dao.SubProductWhitelistDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductChangeLog;
import com.hedgeguard.dao.entity.ProductQuota;
import com.hedgeguard.dao.entity.SubInstrumentQuota;
import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.handler.product.WhitelistHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-112}：PRODUCT 写面（白名单 / 三层额度）子账户可见性强制的验收（{@code ①②}）。
 *
 * <p>三类语义分开锁定（一刀切会引入新的误拒缺陷）：
 * <ul>
 *   <li><b>业务归属类</b>（{@code putWhitelist}/{@code removeWhitelist}、
 *       {@code putQuota}（层①）/ {@code putInstrumentQuota}（层②））⇒ <b>必须</b>强制调用者对目标
 *       子账户可见，越权 {@code 10004}（HTTP 403）且<b>不产生任何写入</b>；</li>
 *   <li><b>非子账户业务维度</b>（{@code putQuota} 层③ {@code ACCOUNT_PRODUCT}：有效目标为账户哨兵
 *       {@code 0}）⇒ <b>不得</b>按子账户可见性强制的语义误套（本用例锁定该判定，防后人"顺手"补一个
 *       对入参子账户的校验而误拒账户级配置）；</li>
 *   <li><b>fail-closed</b>（可见性入口缺位 / 可见集为空）⇒ 一律拒绝，<b>绝不</b>退化为"不校验 = 放行"。</li>
 * </ul>
 *
 * <p>含一组<b>真实 {@code AuthService}</b> 端到端探针（非替身）：越权在真实权限判定 + 留痕
 * （{@code R-AUTH-8}）下被证伪，且越权被拒时业务库零写入。
 *
 * <p>完全离线（{@code NFR-TEST-2}）：内存假 DAO 驱动，不含 SQL / 连接。
 */
class VarietyT112VisibilityTest {

    /** 操作者只对 {@link #VISIBLE_SUB} 可见。 */
    private static final long OPERATOR = 999L;
    /** 可见子账户。 */
    private static final int VISIBLE_SUB = 7;
    /** 不可见子账户（越权目标）。 */
    private static final int OTHER_SUB = 8;
    private static final long PRODUCT_ID = 5L;

    private FakeProductDao productDao;
    private FakeWhitelistDao whitelistDao;
    private FakeQuotaDao quotaDao;
    private FakeInstrumentQuotaDao instrumentQuotaDao;
    private FakeChangeLogDao changeLogDao;

    @BeforeEach
    void setUp() {
        productDao = new FakeProductDao();
        whitelistDao = new FakeWhitelistDao();
        quotaDao = new FakeQuotaDao();
        instrumentQuotaDao = new FakeInstrumentQuotaDao();
        changeLogDao = new FakeChangeLogDao();
        productDao.rows.add(product(PRODUCT_ID, "CU"));
    }

    // ============================================================ 一、业务归属类：越权拒绝 + 零写入

    @Test
    @DisplayName("T-112 越权：putWhitelist 写他人子账户 ⇒ 10004 / HTTP 403 且零写入")
    void putWhitelistCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> Arrays.asList(VISIBLE_SUB))
                        .putWhitelist(OTHER_SUB, PRODUCT_ID, null, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertEquals(403, e.errorCode().httpStatus(), "对外表现 HTTP 403");
        assertTrue(whitelistDao.rows.isEmpty(), "越权被拒时不得产生任何写入");
        assertTrue(changeLogDao.rows.isEmpty(), "越权被拒时不得留痕任何业务变更");
    }

    @Test
    @DisplayName("T-112 越权：removeWhitelist 写他人子账户 ⇒ 10004 且零写入")
    void removeWhitelistCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> Arrays.asList(VISIBLE_SUB))
                        .removeWhitelist(OTHER_SUB, PRODUCT_ID, "指标调整", OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(whitelistDao.rows.isEmpty(), "越权被拒时不得改动任何白名单行");
    }

    @Test
    @DisplayName("T-112 越权：putQuota（层① SUB_PRODUCT）写他人子账户 ⇒ 10004 且零写入")
    void putQuotaCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> Arrays.asList(VISIBLE_SUB)).putQuota(OTHER_SUB, "SUB_PRODUCT",
                        PRODUCT_ID, new BigDecimal("100"), "LOT", LocalDate.of(2026, 1, 1), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertEquals(403, e.errorCode().httpStatus());
        assertTrue(quotaDao.rows.isEmpty(), "越权被拒时不得产生额度行");
    }

    @Test
    @DisplayName("T-112 越权：putInstrumentQuota（层②）写他人子账户 ⇒ 10004 且零写入")
    void putInstrumentQuotaCrossSubAccountRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> Arrays.asList(VISIBLE_SUB)).putInstrumentQuota(OTHER_SUB, "cu2612",
                        PRODUCT_ID, new BigDecimal("20"), "LOT", LocalDate.of(2026, 1, 1), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(instrumentQuotaDao.rows.isEmpty(), "越权被拒时不得产生合约额度行");
    }

    // ============================================================ 二、业务归属类：合法放行

    @Test
    @DisplayName("T-112 放行：对可见子账户写白名单 / 层①额度 / 层②额度均成功")
    void visibleWritesSucceed() {
        VarietyService svc = service(uid -> Arrays.asList(VISIBLE_SUB));

        svc.putWhitelist(VISIBLE_SUB, PRODUCT_ID, "TRADE", OPERATOR);
        assertEquals(1, whitelistDao.rows.size());
        assertEquals(VISIBLE_SUB, whitelistDao.rows.get(0).subAccountId);

        long quotaId = svc.putQuota(VISIBLE_SUB, "SUB_PRODUCT", PRODUCT_ID, new BigDecimal("100"),
                "LOT", LocalDate.of(2026, 1, 1), OPERATOR);
        assertTrue(quotaId > 0, "可见子账户层①额度应落库成功");
        assertEquals(VISIBLE_SUB, quotaDao.rows.get(0).subAccountId);

        long instrId = svc.putInstrumentQuota(VISIBLE_SUB, "cu2612", PRODUCT_ID,
                new BigDecimal("20"), "LOT", LocalDate.of(2026, 1, 1), OPERATOR);
        assertTrue(instrId > 0, "可见子账户层②额度应落库成功");
        assertEquals(VISIBLE_SUB, instrumentQuotaDao.rows.get(0).subAccountId);
    }

    @Test
    @DisplayName("T-115 写面拒绝：跨账户只读授权（哨兵 0）对任意子账户 putWhitelist 拒 10004 且零写入")
    void crossReadonlySentinelRejected() {
        // T-115：跨子账户只读（哨兵 0）读面放行、写面必须拒绝（FR-AUTH-13 验收 3）。
        // 旧断言（放行、whitelistDao.rows.size()==1）编码的是错误口径，已订正为「拒 + 零写入」。
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> Collections.singletonList(0))
                        .putWhitelist(OTHER_SUB, PRODUCT_ID, null, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode(),
                "跨账户只读不得写（10004）");
        assertTrue(whitelistDao.rows.isEmpty(), "跨账户只读被拒时不得产生写入");
    }

    // ============================================================ 三、非子账户业务维度：不得误套

    @Test
    @DisplayName("T-112 层③锁定：putQuota(ACCOUNT_PRODUCT) 对不可见入参子账户仍成功（有效目标=账户哨兵 0）")
    void putQuotaAccountLevelNotSubAccountScoped() {
        // 入参 subAccountId=OTHER_SUB 被忽略、强制为哨兵 0；写的是账户级配置、非某子账户的数据 ⇒
        // 子账户可见性强制不适用（若误加对入参子账户的校验，本用例即红）。
        long id = service(uid -> Arrays.asList(VISIBLE_SUB)).putQuota(OTHER_SUB, "ACCOUNT_PRODUCT",
                PRODUCT_ID, new BigDecimal("500"), "LOT", LocalDate.of(2026, 1, 1), OPERATOR);
        assertTrue(id > 0, "层③为账户级配置（哨兵 0），不得按子账户归属强制");
        assertEquals(0, quotaDao.rows.get(0).subAccountId, "层③强制哨兵值 0（DC-020）");
    }

    // ============================================================ 四、fail-closed

    @Test
    @DisplayName("T-112 fail-closed：可见性入口缺位（null）⇒ 10004 且零写入（不得退化为放行）")
    void visibilityMissingFailsClosed() {
        VarietyService noVisibility = new VarietyService(FakeTx.newTemplate(), productDao, null, null,
                null, whitelistDao, quotaDao, instrumentQuotaDao, changeLogDao, null);
        BizException e = assertThrows(BizException.class,
                () -> noVisibility.putWhitelist(VISIBLE_SUB, PRODUCT_ID, null, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(whitelistDao.rows.isEmpty(), "入口缺位时不得退化为放行");
    }

    @Test
    @DisplayName("T-112 fail-closed：可见集为空（无任何授权）⇒ 10004")
    void emptyVisibleSetFailsClosed() {
        BizException e = assertThrows(BizException.class,
                () -> service(uid -> new ArrayList<Integer>())
                        .putQuota(VISIBLE_SUB, "SUB_PRODUCT", PRODUCT_ID, new BigDecimal("1"), "LOT",
                                LocalDate.of(2026, 1, 1), OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(quotaDao.rows.isEmpty(), "空可见集不得退化为全可见");
    }

    @Test
    @DisplayName("T-112 缺省 subAccountId（primitive int ⇒ 0）：给出可判定业务码，不得 NPE / 500")
    void missingSubAccountIdNotNpe() {
        VarietyService svc = service(uid -> Arrays.asList(VISIBLE_SUB));
        // 白名单 / 层① / 层② 入口：0 由 requireSubAccount 前置拦下 ⇒ PARAM_INVALID（10001），
        // 断言 BizException（若发生自动拆箱 NPE，本用例会以 Error 失败）。
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> svc.putWhitelist(0, PRODUCT_ID, null, OPERATOR)).errorCode());
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> svc.removeWhitelist(0, PRODUCT_ID, "调整", OPERATOR)).errorCode(),
                "removeWhitelist 与 putWhitelist 同口径：哨兵 0 前置拒（10001，非 NPE）");
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> svc.putQuota(0, "SUB_PRODUCT", PRODUCT_ID, new BigDecimal("1"), "LOT",
                        LocalDate.of(2026, 1, 1), OPERATOR)).errorCode());
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> svc.putInstrumentQuota(0, "cu2612", PRODUCT_ID, new BigDecimal("1"), "LOT",
                        LocalDate.of(2026, 1, 1), OPERATOR)).errorCode());
        assertTrue(whitelistDao.rows.isEmpty() && quotaDao.rows.isEmpty()
                && instrumentQuotaDao.rows.isEmpty(), "缺省 subAccountId 不得产生任何写入");
    }

    @Test
    @DisplayName("T-112 handler 级（真实 DTO 反序列化 + handler 链路）：缺省 / null subAccountId ⇒ 业务码，非 500")
    void handlerMissingSubAccountIdYieldsBusinessCode() {
        // (a) DTO 反序列化：字段为 primitive int，缺省与显式 null 均落为 0（Gson 对 primitive 跳过 null 赋值），
        //     既不抛异常、也不产生 NPE —— 这是「VarietyService 助手签名为 int」不构成 NPE 风险的前提证据。
        WhitelistHandler.Request omitted = JsonCodec.fromJson(
                "{\"action\":\"ENABLE\",\"productId\":7}", WhitelistHandler.Request.class);
        assertEquals(0, omitted.subAccountId, "缺省 ⇒ 0（primitive int，不 NPE）");
        WhitelistHandler.Request explicitNull = JsonCodec.fromJson(
                "{\"action\":\"ENABLE\",\"subAccountId\":null,\"productId\":7}",
                WhitelistHandler.Request.class);
        assertEquals(0, explicitNull.subAccountId, "显式 null ⇒ 0（primitive int，不 NPE）");

        // (b) 经真实 handler 走完整链路：subAccountId=0 ⇒ 可判定 10001（非 NPE/500）
        WhitelistHandler handler = new WhitelistHandler(service(uid -> Arrays.asList(VISIBLE_SUB)));
        BizException e = assertThrows(BizException.class,
                () -> handler.handle(ctx("{\"action\":\"ENABLE\",\"productId\":7}")));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertEquals(400, e.errorCode().httpStatus(), "缺参应为 4xx 业务码，非 5xx");
        assertTrue(whitelistDao.rows.isEmpty());
    }

    // ============================================================ 五、真实 AuthService 端到端探针

    @Test
    @DisplayName("T-112 端到端（真实 AuthService）：越权写白名单被拒 10004 + 留痕 + 业务库零写入")
    void realAuthServiceRejectsCrossSubAccountWrite() {
        FakePermDao permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();
        permDao.rows.add(perm(OPERATOR, VISIBLE_SUB, AuthService.PERM_LIMITED));
        AuthService auth = authService(permDao, attemptDao);

        VarietyService svc = new VarietyService(FakeTx.newTemplate(), productDao, null, null, null,
                whitelistDao, quotaDao, instrumentQuotaDao, changeLogDao, auth);

        // 越权：写他人子账户白名单 ⇒ 真实权限判定拒绝
        BizException e = assertThrows(BizException.class,
                () -> svc.putWhitelist(OTHER_SUB, PRODUCT_ID, null, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertTrue(whitelistDao.rows.isEmpty(), "越权被拒时业务库零写入");
        assertFalse(attemptDao.rows.isEmpty(), "越权尝试须留痕（R-AUTH-8）");
        assertEquals(AuthService.ATTEMPT_SUB_ACCOUNT_DENIED, attemptDao.rows.get(0).attemptType);

        // 合法：写自己可见子账户 ⇒ 通过
        svc.putWhitelist(VISIBLE_SUB, PRODUCT_ID, null, OPERATOR);
        assertEquals(1, whitelistDao.rows.size(), "真实 AuthService 下对可见子账户应放行");
    }

    @Test
    @DisplayName("T-112 端到端（真实 AuthService）：层③账户级额度不受子账户可见性阻碍")
    void realAuthServiceAllowsAccountLevelQuota() {
        FakePermDao permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();
        permDao.rows.add(perm(OPERATOR, VISIBLE_SUB, AuthService.PERM_LIMITED));

        VarietyService svc = new VarietyService(FakeTx.newTemplate(), productDao, null, null, null,
                whitelistDao, quotaDao, instrumentQuotaDao, changeLogDao, authService(permDao, attemptDao));

        long id = svc.putQuota(OTHER_SUB, "ACCOUNT_PRODUCT", PRODUCT_ID, new BigDecimal("500"), "LOT",
                LocalDate.of(2026, 1, 1), OPERATOR);
        assertTrue(id > 0, "层③账户级配置不在子账户可见性强制面内");
        assertTrue(attemptDao.rows.isEmpty(), "合法写入不得产生越权留痕");
    }

    // ============================================================ 辅助

    private VarietyService service(SubAccountVisibility visibility) {
        return new VarietyService(FakeTx.newTemplate(), productDao, null, null, null,
                whitelistDao, quotaDao, instrumentQuotaDao, changeLogDao, visibility);
    }

    /** 构造带会话的请求上下文（{@code currentUserId()=OPERATOR}），驱动真实 handler。 */
    private static RequestContext ctx(String body) {
        RequestContext ctx = new RequestContext("POST", WhitelistHandler.PATH, null,
                Collections.emptyMap(), body, "HGT112", "127.0.0.1");
        ctx.bindSession(new Session("S-112", OPERATOR, "u", 1L, null));
        return ctx;
    }

    /** 真实 {@link AuthService}（仅装配可见性判定所需的两个 DAO；其余入参不被驱动 → 传 {@code null}）。 */
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

    private static Product product(long id, String code) {
        Product p = new Product();
        p.id = id;
        p.productCode = code;
        return p;
    }

    // ------------------------------------------------------------ 内存假 DAO

    private static final class FakeProductDao extends UnsupportedBaseDao<Product, Long>
            implements ProductDao {

        private final List<Product> rows = new ArrayList<>();

        @Override
        public Product findById(Long id) {
            for (Product row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public Product findByCode(String productCode) {
            for (Product row : rows) {
                if (row.productCode.equals(productCode)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public List<Product> findAllOrderByCode() {
            return new ArrayList<>(rows);
        }

        // T-105：ProductDao 新增读取面方法（本用例不驱动列表/导出，显式失败）
        @Override
        public com.hedgeguard.dao.PageResult<Product> findPageScoped(com.hedgeguard.dao.PageQuery query,
                com.hedgeguard.dao.SubAccountScope scope, String extraWhere, List<Object> extraArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findPageScoped");
        }

        @Override
        public List<Product> findAllScoped(com.hedgeguard.dao.SubAccountScope scope) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findAllScoped");
        }

        @Override
        public List<Product> findAllOrderByCreatedDesc() {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findAllOrderByCreatedDesc");
        }
    }

    private static final class FakeWhitelistDao
            extends UnsupportedBaseDao<SubProductWhitelist, Long> implements SubProductWhitelistDao {

        private final List<SubProductWhitelist> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(SubProductWhitelist entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public int updateById(SubProductWhitelist entity, long operatorId) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id == entity.id) {
                    rows.set(i, entity);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public List<SubProductWhitelist> findBySubAccount(int subAccountId) {
            List<SubProductWhitelist> out = new ArrayList<>();
            for (SubProductWhitelist row : rows) {
                if (row.subAccountId == subAccountId) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public List<SubProductWhitelist> findByProduct(long productId) {
            return new ArrayList<>();
        }
    }

    private static final class FakeQuotaDao extends UnsupportedBaseDao<ProductQuota, Long>
            implements ProductQuotaDao {

        private final List<ProductQuota> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(ProductQuota entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public List<ProductQuota> findByLayerAndScope(String limitLayer, int subAccountId) {
            return new ArrayList<>();
        }
    }

    private static final class FakeInstrumentQuotaDao
            extends UnsupportedBaseDao<SubInstrumentQuota, Long> implements SubInstrumentQuotaDao {

        private final List<SubInstrumentQuota> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(SubInstrumentQuota entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public List<SubInstrumentQuota> findBySubAccount(int subAccountId) {
            return new ArrayList<>();
        }
    }

    private static final class FakeChangeLogDao extends UnsupportedBaseDao<ProductChangeLog, Long>
            implements ProductChangeLogDao {

        private final List<ProductChangeLog> rows = new ArrayList<>();

        @Override
        public int insert(ProductChangeLog entity, long operatorId) {
            rows.add(entity);
            return 1;
        }

        @Override
        public List<ProductChangeLog> findByProduct(long productId) {
            return new ArrayList<>();
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
