package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.FundFlow;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code TB-FUND-2 t_fund_flow} 资金流水数据访问（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface FundFlowDao extends BaseDao<FundFlow, Long> {
    /**
     * 按凭证编号查流水（{@code uk_fund_flow_voucher_no}；审计追溯主键）。
     *
     * @param voucherNo 资金凭证编号
     * @return 命中的流水；不存在返回 {@code null}
     */
    FundFlow findByVoucherNo(String voucherNo);

    /**
     * 按子账户 + 记账日取流水（{@code idx_fund_flow_sub_date}；对账须按期与编号升序）。
     *
     * @param subAccountId 子账户
     * @param ledgerDate   记账日
     * @return 列表（{@code flow_time} 升序，{@code id} 升序收官）
     */
    List<FundFlow> findBySubAccountAndDate(int subAccountId, LocalDate ledgerDate);

    /**
     * 按划拨单号取成对流水（{@code idx_fund_flow_transfer}；一出、一入，金额相等，FR-FUND-16 验收 1）。
     *
     * @param transferApplyId 划拨单号
     * @return 列表（{@code flow_time} 升序，{@code id} 升序收官）
     */
    List<FundFlow> findByTransferApplyId(long transferApplyId);

    /**
     * 按记账日取全部流水（{@code idx_fund_flow_date}；按日求 Σ 各子账户发生额，FR-FUND-17）。
     *
     * @param ledgerDate 记账日
     * @return 列表（{@code id} 升序）
     */
    List<FundFlow> findByDate(LocalDate ledgerDate);
}
