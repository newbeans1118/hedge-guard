package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.Authorization;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code TB-AUTH-7 t_authorization} 数据访问（{@code DATA_MODEL.md} §2.2）。
 *
 * <p>本表含行级归属列 {@code sub_account_id}（恒 {@code > 0}），列表类查询须经
 * {@link SubAccountScope} 注入数据权限过滤（{@code NFR-SEC-7}）。
 */
public interface AuthorizationDao extends BaseDao<Authorization, Long> {

    /**
     * 取某人在某日的有效授权（事前风控参数预加载、下单前权限展示）。
     *
     * @param userId 用户 ID
     * @param onDate 业务日期
     * @return 列表（{@code status = 'EFFECTIVE'} 且在有效期内）
     */
    List<Authorization> findEffective(long userId, LocalDate onDate);

    /**
     * 取某子账户下的授权（审计统计）。
     *
     * @param subAccountId 子账户
     * @return 列表
     */
    List<Authorization> findBySubAccount(int subAccountId);

    /**
     * 更新下发状态与版本（{@code R-CONFIG-3}：无回执不得视为已生效）。
     *
     * @param id      授权 ID
     * @param status  下发状态
     * @param version 下发所用 {@code param_version}（可为 {@code null}）
     * @return 影响行数
     */
    int updateDispatchStatus(long id, String status, Long version);
}
