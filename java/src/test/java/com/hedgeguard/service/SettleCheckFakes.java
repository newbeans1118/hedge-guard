package com.hedgeguard.service;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SettleCheckSignatureDao;
import com.hedgeguard.dao.SettleCheckTaskDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.entity.SettleCheckSignature;
import com.hedgeguard.dao.entity.SettleCheckTask;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code T-048} 核对/签署服务单测的记录型内存假 DAO（{@code NFR-TEST-2}：不引 Mockito）。
 *
 * <p>{@link CheckTasks#findPage} 按调用参数做<b>最小可判定过滤</b>（check_type / period_key /
 * sub_account_id），足以覆盖服务实际下推的 where 形态；未实现的方法经
 * {@link com.hedgeguard.testsupport.FakeDao#unsupported} 显式失败，避免桩静默返回默认值。
 */
public final class SettleCheckFakes {

    private SettleCheckFakes() {
    }

    /** 通用内存假 DAO。 */
    public abstract static class Base<T> {
        final List<T> rows = new ArrayList<>();
        private long seq = 1;

        abstract void setId(T e, long id);

        abstract long idOf(T e);

        int insertInternal(T e) {
            setId(e, seq++);
            rows.add(e);
            return 1;
        }

        int updateInternal(T e) {
            for (int i = 0; i < rows.size(); i++) {
                if (idOf(rows.get(i)) == idOf(e)) {
                    rows.set(i, e);
                    return 1;
                }
            }
            return 0;
        }
    }

    /** {@code t_check_task} 假 DAO。 */
    public static final class CheckTasks extends Base<SettleCheckTask> implements SettleCheckTaskDao {
        @Override void setId(SettleCheckTask e, long id) { e.id = id; }
        @Override long idOf(SettleCheckTask e) { return e.id; }

        @Override
        public int insert(SettleCheckTask entity, long operatorId) { return insertInternal(entity); }

        @Override
        public int updateById(SettleCheckTask entity, long operatorId) { return updateInternal(entity); }

        @Override
        public SettleCheckTask findById(Long id) {
            for (SettleCheckTask t : rows) {
                if (t.id == id) {
                    return t;
                }
            }
            return null;
        }

        @Override
        public SettleCheckTask findByPeriod(String checkType, String periodKey, int subAccountId) {
            for (SettleCheckTask t : rows) {
                if (checkType.equals(t.checkType) && periodKey.equals(t.periodKey)
                        && t.subAccountId != null && t.subAccountId == subAccountId) {
                    return t;
                }
            }
            return null;
        }

        @Override
        public List<SettleCheckTask> findOverdue(LocalDate asOf) {
            List<SettleCheckTask> out = new ArrayList<>();
            for (SettleCheckTask t : rows) {
                if (!"COMPLETED".equals(t.status) && t.deadlineDate != null
                        && t.deadlineDate.isBefore(asOf)) {
                    out.add(t);
                }
            }
            return out;
        }

        @Override
        public PageResult<SettleCheckTask> findPage(PageQuery query, String where, List<Object> whereArgs) {
            List<SettleCheckTask> out = new ArrayList<>();
            String checkType = null;
            String periodKey = null;
            List<Integer> subs = new ArrayList<>();
            if (whereArgs != null) {
                for (Object a : whereArgs) {
                    if (a instanceof Integer) {
                        subs.add((Integer) a);
                    } else if (a instanceof String) {
                        String s = (String) a;
                        if ("MONTHLY".equals(s) || "QUARTERLY".equals(s) || "ANNUAL".equals(s)) {
                            checkType = s;
                        } else {
                            periodKey = s;
                        }
                    }
                }
            }
            for (SettleCheckTask t : rows) {
                if (checkType != null && !checkType.equals(t.checkType)) {
                    continue;
                }
                if (periodKey != null && !periodKey.equals(t.periodKey)) {
                    continue;
                }
                if (!subs.isEmpty() && (t.subAccountId == null || !subs.contains(t.subAccountId))) {
                    continue;
                }
                out.add(t);
            }
            return new PageResult<>(out, out.size(), query.page(), query.size());
        }

        @Override
        public long count(String where, List<Object> whereArgs) { return rows.size(); }
    }

    /** {@code t_check_signature} 假 DAO。 */
    public static final class CheckSignatures extends Base<SettleCheckSignature>
            implements SettleCheckSignatureDao {
        @Override void setId(SettleCheckSignature e, long id) { e.id = id; }
        @Override long idOf(SettleCheckSignature e) { return e.id; }

        @Override
        public int insert(SettleCheckSignature entity, long operatorId) {
            // 模拟 uk_check_signature_task_role：同（task, role）唯一
            if (findByTaskAndRole(entity.checkTaskId, entity.signRole) != null) {
                throw new IllegalStateException(
                        "违反唯一约束 uk_check_signature_task_role（T-048 假 DAO）");
            }
            return insertInternal(entity);
        }

        @Override
        public int updateById(SettleCheckSignature entity, long operatorId) { return updateInternal(entity); }

        @Override
        public SettleCheckSignature findById(Long id) {
            for (SettleCheckSignature s : rows) {
                if (s.id == id) {
                    return s;
                }
            }
            return null;
        }

        @Override
        public SettleCheckSignature findByTaskAndRole(long checkTaskId, String signRole) {
            for (SettleCheckSignature s : rows) {
                if (s.checkTaskId != null && s.checkTaskId == checkTaskId
                        && signRole.equals(s.signRole)) {
                    return s;
                }
            }
            return null;
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
        public PageResult<SettleCheckSignature> findPage(PageQuery query, String where,
                                                         List<Object> whereArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("CheckSignatures.findPage");
        }

        @Override
        public long count(String where, List<Object> whereArgs) { return rows.size(); }
    }

    /** {@code t_unauthorized_attempt} 假 DAO（只验证写入）。 */
    public static final class Attempts extends Base<UnauthorizedAttempt> implements UnauthorizedAttemptDao {
        @Override void setId(UnauthorizedAttempt e, long id) { e.id = id; }
        @Override long idOf(UnauthorizedAttempt e) { return e.id; }

        @Override
        public int insert(UnauthorizedAttempt entity, long operatorId) { return insertInternal(entity); }

        @Override
        public int updateById(UnauthorizedAttempt entity, long operatorId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("Attempts.updateById");
        }

        @Override
        public UnauthorizedAttempt findById(Long id) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("Attempts.findById");
        }

        @Override
        public List<UnauthorizedAttempt> findByOperatorSince(long operatorId, LocalDateTime from) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("Attempts.findByOperatorSince");
        }

        @Override
        public long countByTypeBetween(String attemptType, LocalDateTime from, LocalDateTime to) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("Attempts.countByTypeBetween");
        }

        @Override
        public PageResult<UnauthorizedAttempt> findPage(PageQuery query, String where,
                                                        List<Object> whereArgs) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("Attempts.findPage");
        }

        @Override
        public long count(String where, List<Object> whereArgs) { return rows.size(); }
    }
}
