package com.hedgeguard.service;

import com.hedgeguard.dao.BaseDao;
import com.hedgeguard.dao.CapitalReceiptDao;
import com.hedgeguard.dao.DepositWithdrawApplyDao;
import com.hedgeguard.dao.FundFlowDao;
import com.hedgeguard.dao.FundLedgerDao;
import com.hedgeguard.dao.FundQuotaAlertDao;
import com.hedgeguard.dao.FundQuotaDao;
import com.hedgeguard.dao.FundReconcileDao;
import com.hedgeguard.dao.FundReviewRecordDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TransferApplyDao;
import com.hedgeguard.dao.entity.AuditedEntity;
import com.hedgeguard.dao.entity.CapitalReceipt;
import com.hedgeguard.dao.entity.DepositWithdrawApply;
import com.hedgeguard.dao.entity.FundFlow;
import com.hedgeguard.dao.entity.FundLedger;
import com.hedgeguard.dao.entity.FundQuota;
import com.hedgeguard.dao.entity.FundQuotaAlert;
import com.hedgeguard.dao.entity.FundReconcile;
import com.hedgeguard.dao.entity.FundReviewRecord;
import com.hedgeguard.dao.entity.TransferApply;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code T-049} 服务层单测的**记录型内存假 DAO**（{@code NFR-TEST-2}：不引 Mockito）。
 *
 * <p>记录每次 {@code findPage} 的 {@code where} / 参数（供隔离与下推断言）；{@code count} 支持
 * {@code LIKE '<prefix>%'} 形态（供编号生成取「当日计数 + 1」）。未实现的方法经
 * {@code UnsupportedOperationException} 显式失败，避免桩静默返回默认值掩盖缺陷。
 */
final class FundFakes {

    private FundFakes() {
    }

    abstract static class Base<T extends AuditedEntity> implements BaseDao<T, Long> {
        final List<T> rows = new ArrayList<>();
        private long seq = 1;
        String lastWhere;
        List<Object> lastArgs = new ArrayList<>();

        abstract void setId(T e, long id);

        abstract long idOf(T e);

        abstract LocalDate dateOf(T e);

        Integer subOf(T e) {
            return null;
        }

        String noOf(T e) {
            return null;
        }

        @Override
        public int insert(T entity, long operatorId) {
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
            lastWhere = where;
            lastArgs = whereArgs == null ? new ArrayList<>() : new ArrayList<>(whereArgs);
            // 尊重 page/size 的 limit-offset（与真实 DAO 分页同形；供「导出全量分页迭代」用例
            // 复现「仅取第 1 页 ⇒ 截断」的失败模式）。
            int from = Math.min(query.offset(), rows.size());
            int to = Math.min(from + query.size(), rows.size());
            return new PageResult<>(new ArrayList<>(rows.subList(from, to)),
                    rows.size(), query.page(), query.size());
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            if (where != null && where.contains("LIKE") && whereArgs != null && !whereArgs.isEmpty()
                    && whereArgs.get(0) instanceof String) {
                String pat = (String) whereArgs.get(0);
                String prefix = pat.endsWith("%") ? pat.substring(0, pat.length() - 1) : pat;
                long c = 0;
                for (T e : rows) {
                    String no = noOf(e);
                    if (no != null && no.startsWith(prefix)) {
                        c++;
                    }
                }
                return c;
            }
            return rows.size();
        }
    }

    static final class Ledgers extends Base<FundLedger> implements FundLedgerDao {
        @Override void setId(FundLedger e, long id) { e.id = id; }
        @Override long idOf(FundLedger e) { return e.id; }
        @Override LocalDate dateOf(FundLedger e) { return e.ledgerDate; }
        @Override Integer subOf(FundLedger e) { return e.subAccountId; }

        @Override public FundLedger findBySubAccountAndDate(int subAccountId, LocalDate ledgerDate) {
            for (FundLedger l : rows) {
                if (l.subAccountId != null && l.subAccountId == subAccountId
                        && ledgerDate.equals(l.ledgerDate)) {
                    return l;
                }
            }
            return null;
        }

        @Override public List<FundLedger> findByDate(LocalDate ledgerDate) {
            List<FundLedger> out = new ArrayList<>();
            for (FundLedger l : rows) {
                if (ledgerDate.equals(l.ledgerDate)) {
                    out.add(l);
                }
            }
            return out;
        }
    }

    static final class Flows extends Base<FundFlow> implements FundFlowDao {
        @Override void setId(FundFlow e, long id) { e.id = id; }
        @Override long idOf(FundFlow e) { return e.id; }
        @Override LocalDate dateOf(FundFlow e) { return e.ledgerDate; }
        @Override Integer subOf(FundFlow e) { return e.subAccountId; }
        @Override String noOf(FundFlow e) { return e.voucherNo; }

        @Override public FundFlow findByVoucherNo(String voucherNo) {
            for (FundFlow f : rows) {
                if (voucherNo.equals(f.voucherNo)) {
                    return f;
                }
            }
            return null;
        }

        @Override public List<FundFlow> findBySubAccountAndDate(int subAccountId, LocalDate ledgerDate) {
            List<FundFlow> out = new ArrayList<>();
            for (FundFlow f : rows) {
                if (f.subAccountId != null && f.subAccountId == subAccountId
                        && ledgerDate.equals(f.ledgerDate)) {
                    out.add(f);
                }
            }
            return out;
        }

        @Override public List<FundFlow> findByTransferApplyId(long transferApplyId) {
            List<FundFlow> out = new ArrayList<>();
            for (FundFlow f : rows) {
                if (f.transferApplyId != null && f.transferApplyId == transferApplyId) {
                    out.add(f);
                }
            }
            return out;
        }

        @Override public List<FundFlow> findByDate(LocalDate ledgerDate) {
            List<FundFlow> out = new ArrayList<>();
            for (FundFlow f : rows) {
                if (ledgerDate.equals(f.ledgerDate)) {
                    out.add(f);
                }
            }
            return out;
        }
    }

    static final class DwApplies extends Base<DepositWithdrawApply> implements DepositWithdrawApplyDao {
        @Override void setId(DepositWithdrawApply e, long id) { e.id = id; }
        @Override long idOf(DepositWithdrawApply e) { return e.id; }
        @Override LocalDate dateOf(DepositWithdrawApply e) { return null; }
        @Override Integer subOf(DepositWithdrawApply e) { return e.subAccountId; }
        @Override String noOf(DepositWithdrawApply e) { return e.applyNo; }

        @Override public DepositWithdrawApply findByIdempotencyKey(String idempotencyKey) {
            for (DepositWithdrawApply a : rows) {
                if (idempotencyKey != null && idempotencyKey.equals(a.idempotencyKey)) {
                    return a;
                }
            }
            return null;
        }

        @Override public DepositWithdrawApply findByApplyNo(String applyNo) {
            for (DepositWithdrawApply a : rows) {
                if (applyNo != null && applyNo.equals(a.applyNo)) {
                    return a;
                }
            }
            return null;
        }

        @Override public List<DepositWithdrawApply> findBySubAccountAndStatus(int subAccountId, String status) {
            List<DepositWithdrawApply> out = new ArrayList<>();
            for (DepositWithdrawApply a : rows) {
                if (a.subAccountId != null && a.subAccountId == subAccountId
                        && status != null && status.equals(a.status)) {
                    out.add(a);
                }
            }
            return out;
        }

        @Override public String findMaxApplyNoByPrefix(String applyNoPrefix) {
            String max = null;
            for (DepositWithdrawApply a : rows) {
                if (a.applyNo != null && a.applyNo.startsWith(applyNoPrefix)
                        && (max == null || a.applyNo.compareTo(max) > 0)) {
                    max = a.applyNo;
                }
            }
            return max;
        }
    }

    static final class Transfers extends Base<TransferApply> implements TransferApplyDao {
        @Override void setId(TransferApply e, long id) { e.id = id; }
        @Override long idOf(TransferApply e) { return e.id; }
        @Override LocalDate dateOf(TransferApply e) { return null; }
        @Override Integer subOf(TransferApply e) { return e.subAccountId; }
        @Override String noOf(TransferApply e) { return e.applyNo; }

        @Override public TransferApply findByApplyNo(String applyNo) {
            for (TransferApply t : rows) {
                if (applyNo != null && applyNo.equals(t.applyNo)) {
                    return t;
                }
            }
            return null;
        }

        @Override public List<TransferApply> findByFromSubAccountAndStatus(int fromSubAccountId, String status) {
            List<TransferApply> out = new ArrayList<>();
            for (TransferApply t : rows) {
                if (t.fromSubAccountId != null && t.fromSubAccountId == fromSubAccountId
                        && status != null && status.equals(t.status)) {
                    out.add(t);
                }
            }
            return out;
        }

        @Override public List<TransferApply> findByToSubAccountAndStatus(int toSubAccountId, String status) {
            List<TransferApply> out = new ArrayList<>();
            for (TransferApply t : rows) {
                if (t.toSubAccountId != null && t.toSubAccountId == toSubAccountId
                        && status != null && status.equals(t.status)) {
                    out.add(t);
                }
            }
            return out;
        }

        @Override public String findMaxApplyNoByPrefix(String applyNoPrefix) {
            String max = null;
            for (TransferApply t : rows) {
                if (t.applyNo != null && t.applyNo.startsWith(applyNoPrefix)
                        && (max == null || t.applyNo.compareTo(max) > 0)) {
                    max = t.applyNo;
                }
            }
            return max;
        }
    }

    static final class Receipts extends Base<CapitalReceipt> implements CapitalReceiptDao {
        @Override void setId(CapitalReceipt e, long id) { e.id = id; }
        @Override long idOf(CapitalReceipt e) { return e.id; }
        @Override LocalDate dateOf(CapitalReceipt e) { return null; }
        @Override Integer subOf(CapitalReceipt e) { return e.subAccountId; }

        @Override public CapitalReceipt findByIdem(String idempotencyKey, String result, long receiptTsMs) {
            for (CapitalReceipt r : rows) {
                if (idempotencyKey != null && idempotencyKey.equals(r.idempotencyKey)
                        && result != null && result.equals(r.result)
                        && r.receiptTsMs != null && r.receiptTsMs == receiptTsMs) {
                    return r;
                }
            }
            return null;
        }

        @Override public List<CapitalReceipt> findByApplyId(long applyId) {
            List<CapitalReceipt> out = new ArrayList<>();
            for (CapitalReceipt r : rows) {
                if (r.applyId != null && r.applyId == applyId) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    static final class Reviews extends Base<FundReviewRecord> implements FundReviewRecordDao {
        @Override void setId(FundReviewRecord e, long id) { e.id = id; }
        @Override long idOf(FundReviewRecord e) { return e.id; }
        @Override LocalDate dateOf(FundReviewRecord e) { return null; }
        @Override Integer subOf(FundReviewRecord e) { return e.subAccountId; }

        @Override public List<FundReviewRecord> findByBiz(String bizType, long bizId) {
            List<FundReviewRecord> out = new ArrayList<>();
            for (FundReviewRecord r : rows) {
                if (bizType != null && bizType.equals(r.bizType) && r.bizId != null && r.bizId == bizId) {
                    out.add(r);
                }
            }
            return out;
        }

        @Override public List<FundReviewRecord> findBySelfReviewRejected(String selfReviewRejected) {
            List<FundReviewRecord> out = new ArrayList<>();
            for (FundReviewRecord r : rows) {
                if (selfReviewRejected != null && selfReviewRejected.equals(r.selfReviewRejected)) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    static final class Quotas extends Base<FundQuota> implements FundQuotaDao {
        @Override void setId(FundQuota e, long id) { e.id = id; }
        @Override long idOf(FundQuota e) { return e.id; }
        @Override LocalDate dateOf(FundQuota e) { return e.effectiveFrom; }
        @Override Integer subOf(FundQuota e) { return e.subAccountId; }

        @Override public List<FundQuota> findBySubAccount(int subAccountId) {
            List<FundQuota> out = new ArrayList<>();
            for (FundQuota q : rows) {
                if (q.subAccountId != null && q.subAccountId == subAccountId) {
                    out.add(q);
                }
            }
            return out;
        }

        @Override public List<FundQuota> findByScope(String quotaScope) {
            List<FundQuota> out = new ArrayList<>();
            for (FundQuota q : rows) {
                if (quotaScope != null && quotaScope.equals(q.quotaScope)) {
                    out.add(q);
                }
            }
            return out;
        }

        @Override public List<FundQuota> findScoped(String quotaScope, SubAccountScope scope) {
            List<FundQuota> out = new ArrayList<>();
            if (scope == null || scope.isEmpty()) {
                return out; // 恒假：空可见 ⇒ 空结果
            }
            List<Integer> allowed = scope.allowed();
            for (FundQuota q : rows) {
                if (quotaScope != null && quotaScope.equals(q.quotaScope)
                        && q.subAccountId != null && allowed.contains(q.subAccountId)) {
                    out.add(q);
                }
            }
            return out;
        }
    }

    static final class Alerts extends Base<FundQuotaAlert> implements FundQuotaAlertDao {
        @Override void setId(FundQuotaAlert e, long id) { e.id = id; }
        @Override long idOf(FundQuotaAlert e) { return e.id; }
        @Override LocalDate dateOf(FundQuotaAlert e) { return null; }
        @Override Integer subOf(FundQuotaAlert e) { return e.subAccountId; }

        @Override public List<FundQuotaAlert> findBySubAccount(int subAccountId) {
            List<FundQuotaAlert> out = new ArrayList<>();
            for (FundQuotaAlert a : rows) {
                if (a.subAccountId != null && a.subAccountId == subAccountId) {
                    out.add(a);
                }
            }
            return out;
        }

        @Override public List<FundQuotaAlert> findByLevel(String alertLevel) {
            List<FundQuotaAlert> out = new ArrayList<>();
            for (FundQuotaAlert a : rows) {
                if (alertLevel != null && alertLevel.equals(a.alertLevel)) {
                    out.add(a);
                }
            }
            return out;
        }
    }

    static final class Reconciles extends Base<FundReconcile> implements FundReconcileDao {
        @Override void setId(FundReconcile e, long id) { e.id = id; }
        @Override long idOf(FundReconcile e) { return e.id; }
        @Override LocalDate dateOf(FundReconcile e) { return e.tradeDate; }
        @Override Integer subOf(FundReconcile e) { return e.subAccountId; }

        @Override public FundReconcile findByKey(LocalDate tradeDate, String reconcileLevel, Integer subAccountId) {
            for (FundReconcile r : rows) {
                if (tradeDate.equals(r.tradeDate) && reconcileLevel != null
                        && reconcileLevel.equals(r.reconcileLevel)
                        && (r.subAccountId == null ? subAccountId == null
                        : r.subAccountId.equals(subAccountId))) {
                    return r;
                }
            }
            return null;
        }

        @Override public List<FundReconcile> findByDateAndLevel(LocalDate tradeDate, String reconcileLevel) {
            List<FundReconcile> out = new ArrayList<>();
            for (FundReconcile r : rows) {
                if (tradeDate.equals(r.tradeDate) && reconcileLevel != null
                        && reconcileLevel.equals(r.reconcileLevel)) {
                    out.add(r);
                }
            }
            return out;
        }
    }
}
