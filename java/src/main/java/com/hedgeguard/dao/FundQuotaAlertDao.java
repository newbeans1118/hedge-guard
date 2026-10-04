package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.FundQuotaAlert;
import java.util.List;

/**
 * {@code TB-FUND-8 t_fund_quota_alert} 资金额度告警数据访问（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface FundQuotaAlertDao extends BaseDao<FundQuotaAlert, Long> {
    /**
     * 按子账户查额度告警（{@code idx_fund_quota_alert_sub_time}）。
     *
     * @param subAccountId 子账户
     * @return 列表（{@code alert_time} 升序，{@code id} 升序收官）
     */
    List<FundQuotaAlert> findBySubAccount(int subAccountId);

    /**
     * 按级别筛选（{@code idx_fund_quota_alert_level}）。
     *
     * @param alertLevel 告警级别（INFO / WARNING / CRITICAL）
     * @return 列表（{@code alert_time} 升序，{@code id} 升序收官）
     */
    List<FundQuotaAlert> findByLevel(String alertLevel);
}
