package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.CtpFrontConfig;

import java.util.List;

/** {@code TB-CONFIG-7 t_ctp_front_config} 数据访问（{@code DATA_MODEL.md} §2.1）。 */
public interface CtpFrontConfigDao extends BaseDao<CtpFrontConfig, Long> {

    /**
     * 按优先级取启用前置（故障切换用）。
     *
     * @param envType   环境（{@code SIMULATION} / {@code PRODUCTION}）
     * @param frontType 前置类型（{@code TRADE} / {@code MD}）
     * @return 列表（按 {@code priority} 升序，0 = 主）
     */
    List<CtpFrontConfig> findEnabledByPriority(String envType, String frontType);

    /**
     * 判断是否存在启用的实盘前置（启动自检：防误连实盘，{@code R-CONFIG-9}）。
     *
     * @return 存在返回 {@code true}
     */
    boolean existsEnabledProduction();
}
