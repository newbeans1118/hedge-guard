package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.RiskAlert;

import java.util.List;

/**
 * {@code TB-RISK-4 t_risk_alert} 数据访问（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-12}~{@code FR-RISK-14}）。
 *
 * <p>事中风险告警落库。本接口方法集与 {@code dao.impl.DmRiskAlertDao} 一一对应。
 */
public interface RiskAlertDao extends BaseDao<RiskAlert, Long> {

    /**
     * 按子账户查告警（{@code FR-RISK-19}），按告警时间倒序。
     *
     * @param subAccountId 子账户
     * @return 列表
     */
    List<RiskAlert> findBySubAccount(int subAccountId);

    /**
     * 按子账户可见范围分页查询（{@code FR-RISK-19} 查询面隔离，{@code NFR-SEC-7}/{@code §8.2}）。
     *
     * <p>纯增量（{@code T-065}）；scope 谓词落在本表 {@code sub_account_id} 列。计数口径与
     * {@link BaseDao#findPage} 一致（叠加 scope 与 {@code extraWhere} 后统计）。
     *
     * @param query     分页/排序
     * @param scope     子账户过滤（空集 ⇒ 恒假，{@code AbstractDmDao.findPageScoped} 保证）
     * @param extraWhere 附加条件（可空）
     * @param extraArgs 附加条件参数
     * @return 分页结果
     */
    PageResult<RiskAlert> findPageScoped(PageQuery query, SubAccountScope scope,
                                         String extraWhere, List<Object> extraArgs);
}
