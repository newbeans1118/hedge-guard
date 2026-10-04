package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.CapitalReceipt;
import java.util.List;

/**
 * {@code TB-FUND-5 t_capital_receipt} 资金回执数据访问（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface CapitalReceiptDao extends BaseDao<CapitalReceipt, Long> {
    /**
     * 按回执幂等键查回执（{@code uk_capital_receipt_idem}；重复回执须幂等处理，FR-FUND-8 验收 2）。
     *
     * @param idempotencyKey 幂等标识
     * @param result         回执结果
     * @param receiptTsMs    回执毫秒时间戳
     * @return 命中的回执；不存在返回 {@code null}
     */
    CapitalReceipt findByIdem(String idempotencyKey, String result, long receiptTsMs);

    /**
     * 取某申请的全部回执（{@code idx_capital_receipt_apply}；超时待查的查询与定论）。
     *
     * @param applyId 关联申请
     * @return 列表（{@code receipt_time} 升序，{@code id} 升序收官）
     */
    List<CapitalReceipt> findByApplyId(long applyId);
}
