package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.TradeOrder;
import java.util.List;

/**
 * {@code TB-TRADE-1 t_trade_order} 交易指令数据访问（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface TradeOrderDao extends BaseDao<TradeOrder, Long> {

    /**
     * 按 Java 侧幂等键查指令（{@code uk_trade_order_client_ref}；重复指令不重复冻结额度，{@code 0x2007}）。
     *
     * @param clientOrderRef 幂等键（HG&lt;yyyyMMdd&gt;&lt;seq&gt;）
     * @return 命中的实体；不存在返回 {@code null}
     */
    TradeOrder findByClientOrderRef(String clientOrderRef);

    /**
     * 按 C++ 指令号查指令（{@code uk_trade_order_order_id}；事件流按 {@code order_id} 关联）。
     *
     * @param orderId C++ 指令号
     * @return 命中的实体；不存在返回 {@code null}
     */
    TradeOrder findByOrderId(long orderId);

    /**
     * 按子账户 + 状态取指令（{@code idx_trade_order_sub_status_time} 前缀；列表/巡检用）。
     *
     * @param subAccountId 子账户
     * @param status       指令状态
     * @return 列表（{@code submit_time} 升序，{@code id} 升序收官）
     */
    List<TradeOrder> findBySubAccountAndStatus(int subAccountId, String status);

    /**
     * 按套保计划取指令（{@code FR-AUDIT-1} 正向追溯「交易（指令/委托/成交）」段）。
     *
     * <p>本方法为跨账户只读路径（{@code T-067}；子账户可见性由调用方 {@code AuditService}
     * 统一经 {@code AuthService.visibleSubAccountIds} 校验，DAO 不再叠加 scope）。
     *
     * @param hedgePlanId 套保计划
     * @return 指令列表（{@code id} 升序）；无则空列表
     */
    List<TradeOrder> findByHedgePlan(long hedgePlanId);
}
