package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.AbnormalOrderStat;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code TB-RISK-6 t_abnormal_order_stat} 数据访问（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-14}）。
 *
 * <p>异常下单行为统计。本接口方法集与 {@code dao.impl.DmAbnormalOrderStatDao} 一一对应。
 */
public interface AbnormalOrderStatDao extends BaseDao<AbnormalOrderStat, Long> {

    /**
     * 按统计日期归集。
     *
     * @param statDate 统计日期
     * @return 列表（按主键升序）
     */
    List<AbnormalOrderStat> findByStatDate(LocalDate statDate);
}
