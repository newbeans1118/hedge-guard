package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.SettleCheckTask;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code TB-SETTLE-13 t_check_task} 数据访问（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link com.hedgeguard.dao.SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface SettleCheckTaskDao extends BaseDao<SettleCheckTask, Long> {

    /**
     * 按（类型, 期间, 子账户）取任务（{@code uk_check_task_period}；生成幂等）。
     *
     * @param checkType    核对类型
     * @param periodKey    期间键
     * @param subAccountId 子账户（跨账户哨兵 0）
     * @return 任务；不存在返回 {@code null}
     */
    SettleCheckTask findByPeriod(String checkType, String periodKey, int subAccountId);

    /**
     * 取已逾期未完成的任务（{@code status <> 'COMPLETED'} 且 {@code deadline_date < asOf}）。
     *
     * @param asOf 业务日
     * @return 任务列表（按 deadline_date, id 升序）
     */
    List<SettleCheckTask> findOverdue(LocalDate asOf);
}
