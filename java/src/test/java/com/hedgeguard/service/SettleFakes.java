package com.hedgeguard.service;

import com.hedgeguard.dao.BaseDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SettleAggDiffCandidateDao;
import com.hedgeguard.dao.SettleAggregationCheckDao;
import com.hedgeguard.dao.SettleCtpBatchDao;
import com.hedgeguard.dao.SettleCtpFundDao;
import com.hedgeguard.dao.SettleCtpPositionDao;
import com.hedgeguard.dao.SettleCtpTradeDao;
import com.hedgeguard.dao.SettleCycleDao;
import com.hedgeguard.dao.SettleFundDao;
import com.hedgeguard.dao.SettlePnlDao;
import com.hedgeguard.dao.SettlePositionDao;
import com.hedgeguard.dao.SettleReconcileDifferenceDao;
import com.hedgeguard.dao.SettleReconcileRecordDao;
import com.hedgeguard.dao.SettleReportDao;
import com.hedgeguard.dao.SettleStressTestDao;
import com.hedgeguard.dao.entity.AuditedEntity;
import com.hedgeguard.dao.entity.SettleAggDiffCandidate;
import com.hedgeguard.dao.entity.SettleAggregationCheck;
import com.hedgeguard.dao.entity.SettleCtpBatch;
import com.hedgeguard.dao.entity.SettleCtpFund;
import com.hedgeguard.dao.entity.SettleCtpPosition;
import com.hedgeguard.dao.entity.SettleCtpTrade;
import com.hedgeguard.dao.entity.SettleCycle;
import com.hedgeguard.dao.entity.SettleFund;
import com.hedgeguard.dao.entity.SettlePnl;
import com.hedgeguard.dao.entity.SettlePosition;
import com.hedgeguard.dao.entity.SettleReconcileDifference;
import com.hedgeguard.dao.entity.SettleReconcileRecord;
import com.hedgeguard.dao.entity.SettleReport;
import com.hedgeguard.dao.entity.SettleStressTest;
import com.hedgeguard.testsupport.FakeDao;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code T-047} 服务层单测的**记录型内存假 DAO**（{@code NFR-TEST-2}：不引 Mockito）。
 *
 * <p>{@link #findPage} 按调用参数做<b>最小可判定过滤</b>（本地日期 / 子账户 / is_aggregate），
 * 足以覆盖服务实际下推的 where 形态；未实现的方法经 {@link FakeDao#unsupported} 显式失败，
 * 避免"桩静默返回默认值"掩盖缺陷。子账户数据权限过滤（scope）不在假 DAO 中模拟——
 * 服务层通过 {@code AuthService} 断言，本文件只验证 SQL 条件被下推（int 参数）。
 */
final class SettleFakes {

    private SettleFakes() {
    }

    /** 通用内存假 DAO。 */
    abstract static class Base<T extends AuditedEntity> implements BaseDao<T, Long> {
        final List<T> rows = new ArrayList<>();
        private long seq = 1;

        abstract void setId(T e, long id);

        abstract long idOf(T e);

        abstract LocalDate dateOf(T e);

        Integer subOf(T e) {
            return null;
        }

        String aggOf(T e) {
            return null;
        }

        void validate(T e) {
        }

        @Override
        public int insert(T entity, long operatorId) {
            validate(entity);
            setId(entity, seq++);
            rows.add(entity);
            return 1;
        }

        @Override
        public int updateById(T entity, long operatorId) {
            for (int i = 0; i < rows.size(); i++) {
                if (idOf(rows.get(i)) == idOf(entity)) {
                    rows.set(i, entity);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public T findById(Long id) {
            for (T e : rows) {
                if (idOf(e) == id) {
                    return e;
                }
            }
            return null;
        }

        @Override
        public PageResult<T> findPage(PageQuery query, String where, List<Object> whereArgs) {
            List<LocalDate> dates = new ArrayList<>();
            List<Integer> subs = new ArrayList<>();
            String agg = null;
            boolean aggFilter = where != null && where.contains("is_aggregate");
            if (whereArgs != null) {
                for (Object a : whereArgs) {
                    if (a instanceof LocalDate) {
                        dates.add((LocalDate) a);
                    } else if (a instanceof Integer) {
                        subs.add((Integer) a);
                    } else if (aggFilter && a instanceof String) {
                        agg = (String) a;
                    }
                }
            }
            boolean range = where != null && where.contains(">=");
            List<T> out = new ArrayList<>();
            for (T e : rows) {
                LocalDate d = dateOf(e);
                if (!dates.isEmpty()) {
                    if (range && dates.size() >= 2) {
                        if (d == null || d.isBefore(dates.get(0)) || d.isAfter(dates.get(1))) {
                            continue;
                        }
                    } else if (d == null || !d.equals(dates.get(0))) {
                        continue;
                    }
                }
                if (!subs.isEmpty()) {
                    Integer s = subOf(e);
                    if (s == null || !subs.contains(s)) {
                        continue;
                    }
                }
                if (aggFilter && agg != null && !agg.equals(aggOf(e))) {
                    continue;
                }
                out.add(e);
            }
            return new PageResult<>(out, out.size(), query.page(), query.size());
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }
    }

    static final class Cycles extends Base<SettleCycle> implements SettleCycleDao {
        @Override void setId(SettleCycle e, long id) { e.id = id; }
        @Override long idOf(SettleCycle e) { return e.id; }
        @Override LocalDate dateOf(SettleCycle e) { return e.tradeDate; }
        @Override Integer subOf(SettleCycle e) { return e.subAccountId; }
        @Override String aggOf(SettleCycle e) { return e.isAggregate; }
    }

    static final class Positions extends Base<SettlePosition> implements SettlePositionDao {
        @Override void setId(SettlePosition e, long id) { e.id = id; }
        @Override long idOf(SettlePosition e) { return e.id; }
        @Override LocalDate dateOf(SettlePosition e) { return e.tradeDate; }
        @Override Integer subOf(SettlePosition e) { return e.subAccountId; }
    }

    static final class Funds extends Base<SettleFund> implements SettleFundDao {
        @Override void setId(SettleFund e, long id) { e.id = id; }
        @Override long idOf(SettleFund e) { return e.id; }
        @Override LocalDate dateOf(SettleFund e) { return e.tradeDate; }
        @Override Integer subOf(SettleFund e) { return e.subAccountId; }
    }

    static final class Pnls extends Base<SettlePnl> implements SettlePnlDao {
        @Override void setId(SettlePnl e, long id) { e.id = id; }
        @Override long idOf(SettlePnl e) { return e.id; }
        @Override LocalDate dateOf(SettlePnl e) { return e.tradeDate; }
        @Override Integer subOf(SettlePnl e) { return e.subAccountId; }
    }

    static final class CtpBatches extends Base<SettleCtpBatch> implements SettleCtpBatchDao {
        @Override void setId(SettleCtpBatch e, long id) { e.id = id; }
        @Override long idOf(SettleCtpBatch e) { return e.id; }
        @Override LocalDate dateOf(SettleCtpBatch e) { return e.tradeDate; }
        @Override public SettleCtpBatch findByTradeDateAndSource(LocalDate tradeDate, String sourceIdentifier) {
            for (SettleCtpBatch b : rows) {
                if (tradeDate.equals(b.tradeDate) && sourceIdentifier.equals(b.sourceIdentifier)) {
                    return b;
                }
            }
            return null;
        }
    }

    static final class CtpPositions extends Base<SettleCtpPosition> implements SettleCtpPositionDao {
        @Override void setId(SettleCtpPosition e, long id) { e.id = id; }
        @Override long idOf(SettleCtpPosition e) { return e.id; }
        @Override LocalDate dateOf(SettleCtpPosition e) { return e.tradeDate; }
    }

    static final class CtpTrades extends Base<SettleCtpTrade> implements SettleCtpTradeDao {
        @Override void setId(SettleCtpTrade e, long id) { e.id = id; }
        @Override long idOf(SettleCtpTrade e) { return e.id; }
        @Override LocalDate dateOf(SettleCtpTrade e) { return e.tradeDate; }
    }

    static final class CtpFunds extends Base<SettleCtpFund> implements SettleCtpFundDao {
        @Override void setId(SettleCtpFund e, long id) { e.id = id; }
        @Override long idOf(SettleCtpFund e) { return e.id; }
        @Override LocalDate dateOf(SettleCtpFund e) { return e.tradeDate; }
    }

    static final class ReconcileRecords extends Base<SettleReconcileRecord> implements SettleReconcileRecordDao {
        @Override void setId(SettleReconcileRecord e, long id) { e.id = id; }
        @Override long idOf(SettleReconcileRecord e) { return e.id; }
        @Override LocalDate dateOf(SettleReconcileRecord e) { return e.tradeDate; }
    }

    static final class Differences extends Base<SettleReconcileDifference> implements SettleReconcileDifferenceDao {
        @Override void setId(SettleReconcileDifference e, long id) { e.id = id; }
        @Override long idOf(SettleReconcileDifference e) { return e.id; }
        @Override LocalDate dateOf(SettleReconcileDifference e) { return e.tradeDate; }
        @Override Integer subOf(SettleReconcileDifference e) { return e.subAccountId; }
    }

    static final class AggChecks extends Base<SettleAggregationCheck> implements SettleAggregationCheckDao {
        @Override void setId(SettleAggregationCheck e, long id) { e.id = id; }
        @Override long idOf(SettleAggregationCheck e) { return e.id; }
        @Override LocalDate dateOf(SettleAggregationCheck e) { return e.tradeDate; }
    }

    static final class Candidates extends Base<SettleAggDiffCandidate> implements SettleAggDiffCandidateDao {
        @Override void setId(SettleAggDiffCandidate e, long id) { e.id = id; }
        @Override long idOf(SettleAggDiffCandidate e) { return e.id; }
        @Override LocalDate dateOf(SettleAggDiffCandidate e) { return null; }
        @Override public List<SettleAggDiffCandidate> findBlockingCandidates(long aggregationCheckId) {
            List<SettleAggDiffCandidate> out = new ArrayList<>();
            for (SettleAggDiffCandidate c : rows) {
                if (c.aggregationCheckId != null && c.aggregationCheckId == aggregationCheckId
                        && ("PENDING".equals(c.resolutionStatus) || "CONFIRMED".equals(c.resolutionStatus))) {
                    out.add(c);
                }
            }
            return out;
        }
    }

    static final class Reports extends Base<SettleReport> implements SettleReportDao {
        @Override void setId(SettleReport e, long id) { e.id = id; }
        @Override long idOf(SettleReport e) { return e.id; }
        @Override LocalDate dateOf(SettleReport e) { return e.tradeDate; }
        @Override Integer subOf(SettleReport e) { return e.subAccountId; }
    }

    static final class StressTests extends Base<SettleStressTest> implements SettleStressTestDao {
        @Override void setId(SettleStressTest e, long id) { e.id = id; }
        @Override long idOf(SettleStressTest e) { return e.id; }
        @Override LocalDate dateOf(SettleStressTest e) { return e.testDate; }
        @Override Integer subOf(SettleStressTest e) { return e.subAccountId; }
    }
}
