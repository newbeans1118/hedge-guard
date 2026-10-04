package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.FundLedger;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code TB-FUND-1 t_fund_ledger} 资金台账数据访问（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface FundLedgerDao extends BaseDao<FundLedger, Long> {
    /**
     * 按子账户 + 记账日查台账（{@code uk_fund_ledger_date_sub}；期初结转唯一入口）。
     *
     * @param subAccountId 子账户
     * @param ledgerDate   记账日
     * @return 命中的台账；不存在返回 {@code null}
     */
    FundLedger findBySubAccountAndDate(int subAccountId, LocalDate ledgerDate);

    /**
     * 按记账日取全部子账户台账（{@code idx_fund_ledger_date}；Σ 子账户期末余额 = 真实账户的加总，FR-FUND-17）。
     *
     * @param ledgerDate 记账日
     * @return 列表（{@code sub_account_id} 升序，{@code id} 升序收官）
     */
    List<FundLedger> findByDate(LocalDate ledgerDate);
}
