package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.AccountingPeriod;

/**
 * {@code TB-ACCT-6 t_accounting_period} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>通用增删改查经 {@link BaseDao}（全局期间定义，无子账户维度，无 scope 注入面）。
 */
public interface AccountingPeriodDao extends BaseDao<AccountingPeriod, Long> {

    /**
     * 按期间键取会计期间（{@code uk_accounting_period_key}；FR-ACCT-12、凭证生成前置校验）。
     *
     * @param periodKey 期间键
     * @return 命中的期间；不存在返回 {@code null}
     */
    AccountingPeriod findByPeriodKey(String periodKey);
}
