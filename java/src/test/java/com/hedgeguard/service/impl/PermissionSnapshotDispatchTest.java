package com.hedgeguard.service.impl;

import com.hedgeguard.bridge.AckResult;
import com.hedgeguard.bridge.MirrorSnapshot;
import com.hedgeguard.bridge.ParamChange;
import com.hedgeguard.bridge.PlaceOrderCmd;
import com.hedgeguard.bridge.PlaceOrderResult;
import com.hedgeguard.bridge.ReplayProgress;
import com.hedgeguard.bridge.TradingBridge;
import com.hedgeguard.dao.AuthorizationDao;
import com.hedgeguard.dao.ParamDispatchDao;
import com.hedgeguard.dao.RoleDao;
import com.hedgeguard.dao.UserAccountDao;
import com.hedgeguard.dao.UserRoleDao;
import com.hedgeguard.dao.entity.Authorization;
import com.hedgeguard.dao.entity.ParamDispatch;
import com.hedgeguard.dao.entity.Role;
import com.hedgeguard.dao.entity.UserAccount;
import com.hedgeguard.dao.entity.UserRole;
import com.hedgeguard.service.FakeTx;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-168: dispatchPermissionSnapshot —— L3 侧 DB -> wire 派生用例。
 *
 * <p>覆盖点（派单判据逐条）：
 * <ol>
 *   <li><b>派生正确性</b>：payload 9 键逐键断言（5 布尔逐一；has_trading_post
 *       由 has_trading_operation 派生）；</li>
 *   <li><b>TE-CH019-001 承接</b>：entries[0].has_trading_post 存在且值正确
 *       （派生侧守卫——翻转派生必红）；</li>
 *   <li><b>条目粒度</b>：同一用户跨 2 子账户 => 恰 1 个 entry、不产生静默覆盖
 *       （CONTRACTS 3.2.1 注意块：引擎单键 map 后写覆盖）；</li>
 *   <li><b>留痕</b>：t_param_dispatch 有 PENDING->SUCCESS 行、dispatch_type
 *       为 CMD_UPDATE_PERMISSION；</li>
 *   <li><b>承重自证</b>：变异在隔离副本跑全量（见交付回报）；本类含 round-trip 对断言。</li>
 * </ol>
 *
 * <p>口径来源：ParamChangeEncoderPermissionRouteTest（T-167，编码侧）已证
 * 「payload 9 键 -> wire 字节」的忠实性；本类证「DB -> payload」段 + round-trip 全链。
 */
class PermissionSnapshotDispatchTest {

    private FakeUserAccountDao userAccountDao;
    private FakeRoleDao roleDao;
    private FakeUserRoleDao userRoleDao;
    private FakeAuthorizationDao authorizationDao;
    private RecordingParamDispatchDao dispatchDao;
    private StubBridge bridge;
    private ParamDispatchServiceImpl service;

    @BeforeEach
    void setUp() {
        userAccountDao = new FakeUserAccountDao();
        roleDao = new FakeRoleDao();
        userRoleDao = new FakeUserRoleDao();
        authorizationDao = new FakeAuthorizationDao();
        dispatchDao = new RecordingParamDispatchDao();
        bridge = new StubBridge();
        service = new ParamDispatchServiceImpl(FakeTx.newTemplate(), dispatchDao, bridge,
                Duration.ofMillis(80),
                userAccountDao, roleDao, userRoleDao, authorizationDao);
    }

    // ------------------------------------------------------------ 夹具

    private static UserAccount user(long id, String enterpriseLeader) {
        UserAccount u = new UserAccount();
        u.id = id;
        u.username = "u" + id;
        u.isEnterpriseLeader = enterpriseLeader;
        u.status = "ENABLED";
        return u;
    }

    private static Role role(long id, String code, String tradingOperation, String status) {
        Role r = new Role();
        r.id = id;
        r.roleCode = code;
        r.hasTradingOperation = tradingOperation;
        r.status = status;
        return r;
    }

    private static UserRole grant(long userId, long roleId) {
        UserRole ur = new UserRole();
        ur.id = userId * 100 + roleId;
        ur.userId = userId;
        ur.roleId = roleId;
        return ur;
    }

    private static Authorization auth(long userId, int subAccountId, long productId) {
        Authorization a = new Authorization();
        a.id = userId * 1000 + subAccountId * 10 + productId;
        a.userId = userId;
        a.subAccountId = subAccountId;
        a.productId = productId;
        a.status = "EFFECTIVE";
        a.effectiveFrom = LocalDate.of(2026, 1, 1);
        a.quotaLimit = new java.math.BigDecimal("100");
        return a;
    }

    private static String entryKey(int n, String field) {
        return "entries[" + n + "]." + field;
    }

    // ------------------------------------------------------------ 用例

    @Test
    @DisplayName("派生正确性：9 键逐键断言（5 布尔逐一 + product_ids 并集 + sub_account_id 最小值）")
    void derivesAllNineKeys() {
        userAccountDao.users.put(1001L, user(1001, "Y"));
        roleDao.roles.put(1L, role(1, "APPROVER", "N", "ENABLED"));
        roleDao.roles.put(2L, role(2, "FIN_RISK", "N", "ENABLED"));
        roleDao.roles.put(3L, role(3, "SETTLER", "N", "ENABLED"));
        roleDao.roles.put(4L, role(4, "TRADER", "Y", "ENABLED"));
        roleDao.roles.put(5L, role(5, "AUDITOR", "N", "ENABLED"));
        roleDao.roles.put(6L, role(6, "TRADER", "Y", "DISABLED"));
        userRoleDao.grants.add(grant(1001, 1));
        userRoleDao.grants.add(grant(1001, 2));
        userRoleDao.grants.add(grant(1001, 3));
        userRoleDao.grants.add(grant(1001, 4));
        userRoleDao.grants.add(grant(1001, 5));
        userRoleDao.grants.add(grant(1001, 6));
        authorizationDao.rows.add(auth(1001, 7, 11));
        authorizationDao.rows.add(auth(1001, 7, 22));
        authorizationDao.rows.add(auth(1001, 9, 11));

        service.dispatchPermissionSnapshot(1001, 9L).join();

        Map<String, String> payload = bridge.sent.get(0).payload;
        assertEquals("1001", payload.get(entryKey(0, "operator_id")));
        assertEquals(1, entryCount(payload), "同一 operator 须聚合为单 entry（CONTRACTS 3.2.1 注意块）");
        assertEquals("7", payload.get(entryKey(0, "sub_account_id")), "最小授权子账户 ID");
        assertEquals("11,22", payload.get(entryKey(0, "product_ids")), "跨子账户品种并集升序");
        assertEquals("true", payload.get(entryKey(0, "is_enterprise_leader")));
        assertEquals("true", payload.get(entryKey(0, "is_management_role")), "APPROVER => true");
        assertEquals("true", payload.get(entryKey(0, "has_risk_role")), "FIN_RISK => true");
        assertEquals("true", payload.get(entryKey(0, "has_settle_role")), "SETTLER => true");
        assertEquals("true", payload.get(entryKey(0, "has_trading_post")),
                "TRADER(has_trading_operation=Y, ENABLED) => true（TE-CH019-001 派生侧）");
        assertFalse(payload.containsKey(entryKey(0, "quota_limit")),
                "授权行额度无单值承载位：选填键不得发（发任一行值都会歪曲语义）");
    }

    @Test
    @DisplayName("负向派生：普通用户 5 布尔全 false（has_trading_post=false 仍须显式下发）")
    void derivesAllFalseForPlainUser() {
        userAccountDao.users.put(1002L, user(1002, "N"));
        roleDao.roles.put(5L, role(5, "AUDITOR", "N", "ENABLED"));
        userRoleDao.grants.add(grant(1002, 5));
        authorizationDao.rows.add(auth(1002, 7, 33));

        service.dispatchPermissionSnapshot(1002, 9L).join();

        Map<String, String> payload = bridge.sent.get(0).payload;
        assertEquals("false", payload.get(entryKey(0, "is_enterprise_leader")));
        assertEquals("false", payload.get(entryKey(0, "is_management_role")));
        assertEquals("false", payload.get(entryKey(0, "has_risk_role")));
        assertEquals("false", payload.get(entryKey(0, "has_settle_role")));
        assertEquals("false", payload.get(entryKey(0, "has_trading_post")),
                "准入类须显式 false（缺键会被编码器拒——8 必填键缺一即抛）");
    }

    @Test
    @DisplayName("TE-CH019-001 承接：has_trading_operation=1 亦派生 trading_post=true")
    void tradingPostFromHasTradingOperationOne() {
        userAccountDao.users.put(1003L, user(1003, "N"));
        roleDao.roles.put(7L, role(7, "TRADER", "1", "ENABLED"));
        userRoleDao.grants.add(grant(1003, 7));
        authorizationDao.rows.add(auth(1003, 7, 44));

        service.dispatchPermissionSnapshot(1003, 9L).join();

        assertEquals("true", bridge.sent.get(0).payload.get(entryKey(0, "has_trading_post")),
                "1 与 Y 同为真值口径（CH-019 派生规则）");
    }

    @Test
    @DisplayName("条目粒度：同一用户跨 2 子账户 => 单 entry 不静默覆盖（引擎单键 map 后写覆盖形态）")
    void singleEntryAcrossTwoSubAccounts() {
        userAccountDao.users.put(1004L, user(1004, "N"));
        authorizationDao.rows.add(auth(1004, 7, 11));
        authorizationDao.rows.add(auth(1004, 8, 22));

        service.dispatchPermissionSnapshot(1004, 9L).join();

        Map<String, String> payload = bridge.sent.get(0).payload;
        assertEquals(1, entryCount(payload),
                "两行授权若发两个 entry，引擎单键 map 后写覆盖 => 只剩最后一行（静默丢授权）");
        assertEquals("7", payload.get(entryKey(0, "sub_account_id")), "取最小子账户 ID");
        assertEquals("11,22", payload.get(entryKey(0, "product_ids")), "跨子账户品种并集");
    }

    @Test
    @DisplayName("留痕：先落库 PENDING -> 回执 SUCCESS；dispatch_type 与操作人正确")
    void persistsDispatchTrail() {
        userAccountDao.users.put(1005L, user(1005, "N"));
        authorizationDao.rows.add(auth(1005, 7, 11));

        service.dispatchPermissionSnapshot(1005, 42L).join();

        assertEquals(1, dispatchDao.rows.size());
        ParamDispatch row = dispatchDao.rows.get(0);
        assertEquals("CMD_UPDATE_PERMISSION", row.dispatchType);
        assertEquals("SUCCESS", row.dispatchStatus);
        assertEquals(42L, row.operatorId);
        assertTrue(row.paramVersion > 0, "paramVersion 由服务层分配（单调）");
        assertEquals(8, row.recordCount, "留痕记录数 = payload 键数（8 必填键）");
    }

    @Test
    @DisplayName("守卫：用户不存在 => 10001；无 EFFECTIVE 授权 => 10001（不发空快照）；权限 DAO 未装配 => 10002")
    void guardsReject() {
        BizException noUser = assertThrows(BizException.class,
                () -> service.dispatchPermissionSnapshot(9999, 9L));
        assertEquals(ErrorCode.PARAM_INVALID, noUser.errorCode());

        userAccountDao.users.put(1006L, user(1006, "N"));
        BizException noAuth = assertThrows(BizException.class,
                () -> service.dispatchPermissionSnapshot(1006, 9L));
        assertEquals(ErrorCode.PARAM_INVALID, noAuth.errorCode());

        ParamDispatchServiceImpl bare = new ParamDispatchServiceImpl(FakeTx.newTemplate(),
                dispatchDao, bridge, Duration.ofMillis(80));
        BizException noDaos = assertThrows(BizException.class,
                () -> bare.dispatchPermissionSnapshot(1006, 9L));
        assertEquals(ErrorCode.COMPONENT_UNAVAILABLE, noDaos.errorCode());
    }

    @Test
    @DisplayName("承重对：payload 经 encode() round-trip 重解出 9 字段全等（含 has_trading_post 全链）")
    void payloadSurvivesEncoderRoundTrip() {
        userAccountDao.users.put(1007L, user(1007, "Y"));
        roleDao.roles.put(4L, role(4, "TRADER", "Y", "ENABLED"));
        roleDao.roles.put(2L, role(2, "FIN_RISK", "N", "ENABLED"));
        userRoleDao.grants.add(grant(1007, 4));
        userRoleDao.grants.add(grant(1007, 2));
        authorizationDao.rows.add(auth(1007, 7, 11));
        authorizationDao.rows.add(auth(1007, 7, 22));

        service.dispatchPermissionSnapshot(1007, 9L).join();
        ParamChange change = bridge.sent.get(0);
        change.paramVersion = 7L;

        hgcp.v1.Hgcp.CmdUpdatePermission cmd;
        try {
            cmd = hgcp.v1.Hgcp.CmdUpdatePermission.parseFrom(
                    com.hedgeguard.bridge.ParamChangeEncoder.encode(change));
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new AssertionError("派生 payload 须能经编码器编出合法 wire 字节", e);
        }
        assertEquals(1, cmd.getEntriesCount(), "单 entry（条目粒度裁定）");
        hgcp.v1.Hgcp.PermissionEntry e = cmd.getEntries(0);
        assertEquals(1007L, e.getOperatorId());
        assertEquals(7, e.getSubAccountId());
        assertEquals(2, e.getProductIdsCount());
        assertEquals(11, e.getProductIds(0));
        assertEquals(22, e.getProductIds(1));
        assertTrue(e.getIsEnterpriseLeader());
        assertFalse(e.getIsManagementRole());
        assertTrue(e.getHasRiskRole());
        assertFalse(e.getHasSettleRole());
        assertTrue(e.getHasTradingPost(), "DB 派生值须在线格式上保真（TE-CH019-001 全链）");
    }

    private static int entryCount(Map<String, String> payload) {
        java.util.Set<Integer> indexes = new java.util.TreeSet<>();
        for (String k : payload.keySet()) {
            if (k.startsWith("entries[")) {
                indexes.add(Integer.parseInt(
                        k.substring("entries[".length(), k.indexOf(']'))));
            }
        }
        return indexes.size();
    }

    // ------------------------------------------------------------ 桩

    private static final class StubBridge implements TradingBridge {
        final List<ParamChange> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<AckResult> dispatch(ParamChange change) {
            sent.add(change);
            AckResult ack = new AckResult();
            ack.acknowledged = true;
            ack.message = "OK";
            ack.paramVersion = change.paramVersion;
            return CompletableFuture.completedFuture(ack);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public CompletableFuture<PlaceOrderResult> placeOrder(PlaceOrderCmd cmd) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("placeOrder");
        }

        @Override
        public CompletableFuture<AckResult> confirmOrder(long orderId, long operatorId,
                                                         String confirmToken) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("confirmOrder");
        }

        @Override
        public CompletableFuture<AckResult> cancelOrder(long orderId, String reason,
                                                        long operatorId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("cancelOrder");
        }

        @Override
        public ReplayProgress replayProgress() {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("replayProgress");
        }

        @Override
        public MirrorSnapshot mirror() {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("mirror");
        }
    }

    private static final class RecordingParamDispatchDao extends UnsupportedBaseDao<ParamDispatch, Long>
            implements ParamDispatchDao {
        final List<ParamDispatch> rows = Collections.synchronizedList(new ArrayList<>());
        private long nextId = 1;

        @Override
        public synchronized int insert(ParamDispatch entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public synchronized int updateAck(long id, String dispatchStatus,
                                          java.time.LocalDateTime ackTime, String ackDetail,
                                          int retryCount) {
            for (ParamDispatch row : rows) {
                if (row.id == id) {
                    row.dispatchStatus = dispatchStatus;
                    row.ackTime = ackTime;
                    row.ackDetail = ackDetail;
                    row.retryCount = retryCount;
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public synchronized List<ParamDispatch> findByVersion(long paramVersion, String dispatchType) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByVersion");
        }

        @Override
        public synchronized boolean isBatchComplete(long paramVersion, String dispatchType, int batchTotal) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("isBatchComplete");
        }

        @Override
        public synchronized List<ParamDispatch> findProblems() {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findProblems");
        }

        @Override
        public synchronized List<ParamDispatch> findBySubAccount(int subAccountId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findBySubAccount");
        }

        @Override
        public synchronized long maxParamVersion() {
            long max = 0;
            for (ParamDispatch row : rows) {
                max = Math.max(max, row.paramVersion);
            }
            return max;
        }
    }

    private static final class FakeUserAccountDao extends UnsupportedBaseDao<UserAccount, Long>
            implements UserAccountDao {
        final Map<Long, UserAccount> users = new HashMap<>();

        @Override
        public UserAccount findById(Long id) {
            return users.get(id);
        }

        @Override
        public UserAccount findByUsername(String username) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByUsername");
        }

        @Override
        public UserAccount findByNaturalPersonKey(String key) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByNaturalPersonKey");
        }

        @Override
        public int incrementFailCount(long userId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("incrementFailCount");
        }

        @Override
        public int lockUntil(long userId, java.time.LocalDateTime lockedUntil) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("lockUntil");
        }

        @Override
        public int unlock(long userId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("unlock");
        }

        @Override
        public int touchLastLogin(long userId, java.time.LocalDateTime lastLoginTime) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("touchLastLogin");
        }

        @Override
        public int updatePasswordHash(long userId, String passwordHash,
                                      java.time.LocalDateTime passwordUpdatedTime) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("updatePasswordHash");
        }
    }

    private static final class FakeRoleDao extends UnsupportedBaseDao<Role, Long>
            implements RoleDao {
        final Map<Long, Role> roles = new HashMap<>();

        @Override
        public Role findById(Long id) {
            return roles.get(id);
        }

        @Override
        public List<Role> findByPositionGroup(int positionGroupId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByPositionGroup");
        }

        @Override
        public Role findByCode(String roleCode) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByCode");
        }

        @Override
        public List<Role> findTradingRoles() {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findTradingRoles");
        }
    }

    private static final class FakeUserRoleDao extends UnsupportedBaseDao<UserRole, Long>
            implements UserRoleDao {
        final List<UserRole> grants = new ArrayList<>();

        @Override
        public List<UserRole> findByUser(long userId) {
            List<UserRole> out = new ArrayList<>();
            for (UserRole g : grants) {
                if (g.userId == userId) {
                    out.add(g);
                }
            }
            return out;
        }

        @Override
        public List<UserRole> findByRole(long roleId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByRole");
        }
    }

    private static final class FakeAuthorizationDao extends UnsupportedBaseDao<Authorization, Long>
            implements AuthorizationDao {
        final List<Authorization> rows = new ArrayList<>();

        @Override
        public List<Authorization> findEffective(long userId, LocalDate onDate) {
            List<Authorization> out = new ArrayList<>();
            for (Authorization a : rows) {
                if (a.userId == userId && "EFFECTIVE".equals(a.status)) {
                    out.add(a);
                }
            }
            out.sort(Comparator.comparingLong(a -> a.id));
            return out;
        }

        @Override
        public List<Authorization> findBySubAccount(int subAccountId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findBySubAccount");
        }

        @Override
        public int updateDispatchStatus(long id, String status, Long version) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("updateDispatchStatus");
        }
    }
}

