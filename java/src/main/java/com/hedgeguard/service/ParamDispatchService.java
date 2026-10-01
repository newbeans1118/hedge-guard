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

    /**
     * 下发指定用户的权限快照（{@code CMD_UPDATE_PERMISSION}，{@code T-168}）。
     *
     * <p><b>L3 侧 {@code DB → wire} 派生</b>（装配点唯一合法层：{@code bridge} 不读库，
     * {@code CONTRACTS §2.2}「{@code dao} 与 {@code bridge} 互不依赖」）：读库派生 5 布尔
     * + 按 {@code CONTRACTS §3.2.1} 冻结 9 键组装 {@code entries[n]}，经 {@link #dispatch}
     * 下发并留痕（{@code paramVersion} 单调、{@code t_param_dispatch} 先落 {@code PENDING}）。
     *
     * <p><b>条目粒度（{@code CONTRACTS §3.2.1} ⚠️ 块）</b>：按 {@code operator_id}
     * 聚合 —— 同一用户跨多子账户的授权行合并到<b>同一 entry</b>（引擎侧
     * {@code PermissionSnapshot} 以 {@code operator_id} 单键 map 后写覆盖，逐行发会静默丢授权）。
     *
     * @param userId     被授权用户（快照主体）
     * @param operatorId 操作人（留痕）
     * @return 回执
     */
    CompletableFuture<AckResult> dispatchPermissionSnapshot(long userId, long operatorId);
}
