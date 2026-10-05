package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.RiskViolation;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code TB-RISK-8 t_risk_violation} 数据访问（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-18}）。
 *
 * <p>通用增删改查经 {@link BaseDao}；按发生日期归集供事后风控汇总。本接口为 {@code T-180} 纯增量，
 * 已登记于 {@code java/CONTRACTS.md} §2.2.1。
 */
public interface RiskViolationDao extends BaseDao<RiskViolation, Long> {

    /**
     * 按发生日期取当日的全部违规事项（{@code violation_date} 升序、{@code id} 升序收官）。
     *
     * @param violationDate 发生日期
     * @return 列表（无命中返回空列表）
     */
    List<RiskViolation> findByDate(LocalDate violationDate);

    /**
     * 按自然键计数（同子账户 + 同日期 + 同类型 + 同规则 + 同来源的已落库行数）。
     *
     * <p>供 {@code PostRiskReportServiceImpl#persistDerivedViolations} 作去重下界：
     * 同自然键已存在 {@code n} 行时补插至本次发生数（{@code t_risk_violation} 无计数列，
     * 以多重度承载次数；重跑时已存在行数 == 本次发生数 ⇒ 补插 0，不翻倍）。
     *
     * @param subAccountId  子账户
     * @param violationDate 发生日期
     * @param violationType 违规类型
     * @param ruleId        规则编号（可空）
     * @param source        来源
     * @return 同自然键的已存在行数（调用方以此为补插下界）
     */
    long countByNaturalKey(int subAccountId, LocalDate violationDate, String violationType,
                           String ruleId, String source);
}
