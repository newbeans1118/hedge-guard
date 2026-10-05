package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.SettleCheckSignature;

import java.util.List;

/**
 * {@code TB-SETTLE-15 t_check_signature} 数据访问（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link com.hedgeguard.dao.SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface SettleCheckSignatureDao extends BaseDao<SettleCheckSignature, Long> {

    SettleCheckSignature findByTaskAndRole(long checkTaskId, String signRole);

    /**
     * 取某任务的全部签署记录（{@code idx_check_signature_task}）。
     *
     * @param checkTaskId 任务主键
     * @return 签署记录（按 id 升序）
     */
    List<SettleCheckSignature> findByTask(long checkTaskId);
}
