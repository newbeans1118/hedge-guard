package com.hedgeguard.service.impl;

import com.hedgeguard.dao.BaseDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.SettlePnlDao;
import com.hedgeguard.dao.SettlePositionDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.SettlePnl;
import com.hedgeguard.dao.entity.SettlePosition;
import com.hedgeguard.service.AuditReportService.ReportDataProvider;
import com.hedgeguard.service.AuditReportService.ReportRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计报表聚合数据源（达梦实现；{@code T-069}；{@code FR-AUDIT-5/6}）。
 *
 * <p>以真实 {@code Dm*Dao} 读取已交付模块数据，按 <b>子账户 × 品种</b> 聚合：
 * <ul>
 *   <li><b>保值规模</b> = {@code t_hedge_plan.plan_qty} 之和（计划存续期与报表期间重叠者，按 {@code term_start_date/term_end_date}）；</li>
 *   <li><b>持仓</b> = {@code t_settle_position.position_qty}（取期间内<b>最近交易日</b>的存量）；</li>
 *   <li><b>衍生品盈亏</b> = {@code t_settle_pnl.amount} 之和（{@code CLOSE_REALIZED} + {@code FLOAT_UNREALIZED}；手续费/保证金占用不计入盈亏）；</li>
 *   <li><b>实货盈亏</b> = {@code null}（权威源在 {@code ACCT}，{@code T-076+} 未交付 ⇒ 记数据缺口，不伪造）；</li>
 *   <li><b>品种名称</b> = {@code t_product.product_name}。</li>
 * </ul>
 *
 * <p>子账户软隔离：非跨账户调用追加 {@code sub_account_id IN (...)}（{@code NFR-SEC-7}）。
 */
public final class DmAuditReportDataProvider implements ReportDataProvider {

    private static final int PAGE_SIZE = 100;

    private final HedgePlanDao hedgePlanDao;
    private final SettlePositionDao positionDao;
    private final SettlePnlDao pnlDao;
    private final ProductDao productDao;

    /**
     * 构造。
     *
     * @param hedgePlanDao 套保计划 DAO
     * @param positionDao  结算持仓 DAO
     * @param pnlDao       结算盈亏 DAO
     * @param productDao   品种 DAO
     */
    public DmAuditReportDataProvider(HedgePlanDao hedgePlanDao, SettlePositionDao positionDao,
                                     SettlePnlDao pnlDao, ProductDao productDao) {
        this.hedgePlanDao = hedgePlanDao;
        this.positionDao = positionDao;
        this.pnlDao = pnlDao;
        this.productDao = productDao;
    }

    @Override
    public List<ReportRow> rows(LocalDate from, LocalDate to, List<Integer> visibleSubAccounts,
                                boolean crossSubAccount) {
        Map<String, ReportRow> map = new LinkedHashMap<>();
        Map<String, LocalDate> positionAsOf = new LinkedHashMap<>();

        // ① 保值规模（计划存续期与期间重叠）。
        for (HedgePlan p : all(hedgePlanDao,
                withScope("term_start_date <= ? AND term_end_date >= ?", visibleSubAccounts, crossSubAccount,
                        new Object[]{to, from}))) {
            if (p.productId == null) {
                continue;
            }
            ReportRow r = row(map, p.subAccountId, p.productId);
            r.hedgeScale = nz(r.hedgeScale).add(nz(p.planQty));
        }

        // ② 持仓（期间内最近交易日存量）。
        for (SettlePosition p : all(positionDao,
                withScope("trade_date >= ? AND trade_date <= ?", visibleSubAccounts, crossSubAccount,
                        new Object[]{from, to}))) {
            if (p.productId == null) {
                continue;
            }
            ReportRow r = row(map, p.subAccountId, p.productId);
            String key = key(p.subAccountId, p.productId);
            LocalDate asOf = positionAsOf.get(key);
            if (asOf == null || (p.tradeDate != null && p.tradeDate.isAfter(asOf))) {
                positionAsOf.put(key, p.tradeDate);
                r.positionQty = nz(p.positionQty);
            } else if (p.tradeDate != null && p.tradeDate.equals(asOf)) {
                r.positionQty = nz(r.positionQty).add(nz(p.positionQty));
            }
        }

        // ③ 衍生品盈亏。
        for (SettlePnl p : all(pnlDao,
                withScope("trade_date >= ? AND trade_date <= ?", visibleSubAccounts, crossSubAccount,
                        new Object[]{from, to}))) {
            if (p.productId == null || !isRealizedPnl(p.pnlType)) {
                continue;
            }
            ReportRow r = row(map, p.subAccountId, p.productId);
            r.derivativePnl = nz(r.derivativePnl).add(nz(p.amount));
        }

        // 品种名称 + 实货盈亏数据缺口。
        for (ReportRow r : map.values()) {
            if (r.productId != null) {
                Product product = productDao.findById(r.productId);
                r.productName = product == null ? null : product.productName;
            }
            r.physicalPnl = null;
        }
        return new ArrayList<>(map.values());
    }

    private static boolean isRealizedPnl(String pnlType) {
        return "CLOSE_REALIZED".equals(pnlType) || "FLOAT_UNREALIZED".equals(pnlType);
    }

    private static String key(Integer sub, Long product) {
        return sub + "|" + product;
    }

    private static ReportRow row(Map<String, ReportRow> map, Integer sub, Long product) {
        String k = key(sub, product);
        ReportRow r = map.get(k);
        if (r == null) {
            r = new ReportRow();
            r.subAccountId = sub;
            r.productId = product;
            r.hedgeScale = BigDecimal.ZERO;
            r.derivativePnl = BigDecimal.ZERO;
            r.positionQty = BigDecimal.ZERO;
            map.put(k, r);
        }
        return r;
    }

    private Where withScope(String where, List<Integer> visible, boolean cross, Object[] args) {
        List<Object> out = new ArrayList<>(java.util.Arrays.asList(args));
        String w = where;
        if (!cross) {
            SubAccountScope scope = SubAccountScope.of("sub_account_id", visible);
            w = where + " AND " + scope.sql();
            out.addAll(scope.params());
        }
        return new Where(w, out);
    }

    private <T> List<T> all(BaseDao<T, Long> dao, Where where) {
        List<T> out = new ArrayList<>();
        int page = 1;
        while (true) {
            PageResult<T> pr = dao.findPage(
                    PageQuery.of(String.valueOf(page), String.valueOf(PAGE_SIZE), null, "id", "asc"),
                    where.sql, where.args);
            out.addAll(pr.items());
            if (pr.items().isEmpty() || out.size() >= pr.total()) {
                return out;
            }
            page++;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** WHERE 子句 + 参数。 */
    private static final class Where {
        final String sql;
        final List<Object> args;

        Where(String sql, List<Object> args) {
            this.sql = sql;
            this.args = args;
        }
    }
}
