package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.FundReconcile;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code TB-FUND-9 t_fund_reconcile} 资金对账数据访问（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface FundReconcileDao extends BaseDao<FundReconcile, Long> {
    /**
     * 按对账键查对账（{@code uk_fund_reconcile_key}；{@code subAccountId} 为 {@code null} 时匹配账户级行）。
     *
     * @param tradeDate      交易日
     * @param reconcileLevel 对账层级（SUB_ACCOUNT / ACCOUNT）
     * @param subAccountId   子账户（账户级传 {@code null}）
     * @return 命中的对账；不存在返回 {@code null}
     */
    FundReconcile findByKey(LocalDate tradeDate, String reconcileLevel, Integer subAccountId);

    /**
     * 按交易日 + 层级查资金对账结论（{@code idx_fund_reconcile_date}，FR-FUND-17）。
     *
     * @param tradeDate      交易日
     * @param reconcileLevel 对账层级
     * @return 列表（{@code sub_account_id} 升序，{@code id} 升序收官）
     */
    List<FundReconcile> findByDateAndLevel(LocalDate tradeDate, String reconcileLevel);
}
