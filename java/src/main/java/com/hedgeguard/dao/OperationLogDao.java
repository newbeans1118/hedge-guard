package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.OperationLog;

import java.util.List;

/**
 * {@code TB-AUDIT-1 t_operation_log} 数据访问（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-11}/{@code R-AUDIT-3}）。
 *
 * <p><b>只增不改不删</b>：实现类覆写 {@code updateById} 显式拒绝（{@code CONTRACTS.md} §2.3）。
 */
public interface OperationLogDao extends BaseDao<OperationLog, Long> {

    /**
     * 按对象查操作轨迹（{@code idx_op_log_object}；追溯链「审批/交易」段的留痕）。
     *
     * @param objectType 对象类型（表名或实体名）
     * @param objectId   对象标识
     * @return 按操作时间升序的行
     */
    List<OperationLog> findByObject(String objectType, long objectId);
}
