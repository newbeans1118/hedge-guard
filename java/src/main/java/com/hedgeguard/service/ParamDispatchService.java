package com.hedgeguard.service;

import com.hedgeguard.bridge.AckResult;
import com.hedgeguard.bridge.ParamChange;

import java.util.concurrent.CompletableFuture;

/**
 * 参数下发编排（{@code ARCHITECTURE.md} §3.8「跨模块共用能力 {@code ParamDispatchService}」）。
 *
 * <p>统一处理"改 Java 侧 → 下发 C++ → 等回执 → 留痕"，供
 * {@code Variety / Plan / Risk / Auth / Config} 复用（避免 5 处各写一套）。
 * 对应规则 {@code R-CONFIG-3}、{@code R-AUTH-9}、{@code R-PRODUCT-9}、{@code R-PLAN-5}、{@code R-RISK-9}。
 *
 * <p>关键约束：<b>无回执或回执失败不得视为已生效</b>（{@code 10014}，{@code §4.3.1}）。
 */
public interface ParamDispatchService {

    /**
     * 下发参数变更并等待回执。
     *
     * @param change 参数变更（须携带单调递增的 {@code paramVersion}）
     * @return 回执
     */
    CompletableFuture<AckResult> dispatch(ParamChange change);

    /**
     * 校验回执是否表示"已生效"；未生效时抛 {@code 10014}（阻断类，四项齐备）。
     *
     * @param change 下发的变更
     * @param ack    回执
     */
    void requireEffective(ParamChange change, AckResult ack);
}
