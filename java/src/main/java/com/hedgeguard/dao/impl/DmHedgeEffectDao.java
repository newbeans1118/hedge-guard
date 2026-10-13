package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.HedgeEffectDao;
import com.hedgeguard.dao.entity.HedgeEffect;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_hedge_effect} 的达梦实现（{@code DATA_MODEL.md} §2.10 TB-ACCT-8）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。幂等键 {@code uk_hedge_effect_key} 供重复生成时幂等或明确提示。
 */
public final class DmHedgeEffectDao extends AbstractDmDao<HedgeEffect, Long> implements HedgeEffectDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "period_key", "hedge_plan_id", "exposure_id",
            "spot_pnl_realized", "spot_pnl_unrealized", "spot_pnl_total",
            "derivative_pnl_realized", "derivative_pnl_unrealized", "derivative_pnl_total",
            "net_effect", "unhedged_qty", "unhedged_amount", "hedged_ratio_actual",
            "spot_price_missing", "missing_desc", "price_as_of", "non_current_price_flag",
            "calc_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmHedgeEffectDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_hedge_effect";
    }

    @Override
    protected String idColumn() {
        return "id";
    }

    @Override
    protected String[] businessColumns() {
        return COLUMNS.clone();
    }

    @Override
    protected void bindBusiness(PreparedStatement ps, HedgeEffect e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.periodKey);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.exposureId);
        bind(ps, i++, e.spotPnlRealized);
        bind(ps, i++, e.spotPnlUnrealized);
        bind(ps, i++, e.spotPnlTotal);
        bind(ps, i++, e.derivativePnlRealized);
        bind(ps, i++, e.derivativePnlUnrealized);
        bind(ps, i++, e.derivativePnlTotal);
        bind(ps, i++, e.netEffect);
        bind(ps, i++, e.unhedgedQty);
        bind(ps, i++, e.unhedgedAmount);
        bind(ps, i++, e.hedgedRatioActual);
        bind(ps, i++, e.spotPriceMissing);
        bind(ps, i++, e.missingDesc);
        bind(ps, i++, e.priceAsOf);
        bind(ps, i++, e.nonCurrentPriceFlag);
        bind(ps, i, e.calcTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, HedgeEffect e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.periodKey = string(rs, "period_key");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.exposureId = nullableLong(rs, "exposure_id");
        e.spotPnlRealized = decimal(rs, "spot_pnl_realized");
        e.spotPnlUnrealized = decimal(rs, "spot_pnl_unrealized");
        e.spotPnlTotal = decimal(rs, "spot_pnl_total");
        e.derivativePnlRealized = decimal(rs, "derivative_pnl_realized");
        e.derivativePnlUnrealized = decimal(rs, "derivative_pnl_unrealized");
        e.derivativePnlTotal = decimal(rs, "derivative_pnl_total");
        e.netEffect = decimal(rs, "net_effect");
        e.unhedgedQty = decimal(rs, "unhedged_qty");
        e.unhedgedAmount = decimal(rs, "unhedged_amount");
        e.hedgedRatioActual = decimal(rs, "hedged_ratio_actual");
        e.spotPriceMissing = string(rs, "spot_price_missing");
        e.missingDesc = string(rs, "missing_desc");
        e.priceAsOf = localDate(rs, "price_as_of");
        e.nonCurrentPriceFlag = string(rs, "non_current_price_flag");
        e.calcTime = localDateTime(rs, "calc_time");
    }

    @Override
    protected Long idOf(HedgeEffect entity) {
        return entity.id;
    }

    @Override
    protected HedgeEffect newEntity() {
        return new HedgeEffect();
    }

    @Override
    protected void applyGeneratedId(HedgeEffect entity, long generated) {
        entity.id = generated;
    }

    @Override
    public HedgeEffect findByKey(int subAccountId, String periodKey, Long hedgePlanId, Long exposureId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? AND period_key = ? AND hedge_plan_id = ?"
                + (exposureId == null ? " AND exposure_id IS NULL" : " AND exposure_id = ?");
        List<Object> args = new ArrayList<>(Arrays.asList(subAccountId, periodKey, hedgePlanId));
        if (exposureId != null) {
            args.add(exposureId);
        }
        return queryOne(sql, args, this::mapRow);
    }
}
