package com.hedgeguard.service.impl;

import com.hedgeguard.service.ConfigService;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

/**
 * {@link ConfigService.SubAccountDisablePrecheck} 的 <b>M1 实现</b>（诚实披露，非静默）。
 *
 * <p><b>为何是"不可用"实现</b>：停用四条件（资金 0 / 持仓 0 / 无在途指令 / 当日已结算）的数据分属
 * {@code FUND} / {@code TRADE} / {@code SETTLE} 三个模块，而 <b>M1 不交付</b>这三模块的表
 * （{@code docs/DEPLOY/schema/} 仅 {@code m1_config_auth.sql} / {@code m1_product.sql} /
 * {@code m1_spot.sql} / {@code m1_plan.sql} / {@code m3_risk.sql}）⇒ 无源可查。
 *
 * <p>本项目既定的 M1 姿态是「<b>不伪报成功</b>」（先例 {@code CtpTestHandler}：M1 返回 {@code 10002}
 * 交易组件通信不可用）。故本实现<b>不</b>臆断条件成立/不成立，而是抛
 * {@link ErrorCode#COMPONENT_UNAVAILABLE}（{@code 10002}）并点名缺失的数据源，
 * 由 M2 以真实 {@code FUND}/{@code TRADE}/{@code SETTLE} DAO 替换本实现即可——
 * {@link ConfigService#disableSubAccount} 的判定与落库逻辑<b>不需要</b>改动
 * （{@code ARCHITECTURE.md} §3.8「核心逻辑不依赖具体 I/O」）。
 *
 * <p><b>安全性</b>：因从不返回 {@code true} 而只会抛异常，故在 M1 下停用一律被拒——
 * 与 {@code FR-CONFIG-7} 验收 3「停用条件不满足时停用被拒绝」的保守方向一致，<b>绝不</b>在
 * 无法核算时放行停用。
 */
public final class UnavailableSubAccountDisablePrecheck
        implements ConfigService.SubAccountDisablePrecheck {

    /** 缺失数据源的说明（进入错误信息，便于运维定位）。 */
    private final String missingSources;

    /**
     * 构造。
     *
     * @param missingSources 缺失数据源说明（如 {@code "FUND/TRADE/SETTLE（M1 未交付）"}）
     */
    public UnavailableSubAccountDisablePrecheck(String missingSources) {
        this.missingSources = missingSources;
    }

    @Override
    public boolean fundIsZero(int subAccountId) {
        throw unavailable("资金（FUND）", subAccountId);
    }

    @Override
    public boolean positionsAreZero(int subAccountId) {
        throw unavailable("持仓（TRADE）", subAccountId);
    }

    @Override
    public boolean hasNoInFlightOrders(int subAccountId) {
        throw unavailable("在途指令（TRADE）", subAccountId);
    }

    @Override
    public boolean settledToday(int subAccountId) {
        throw unavailable("当日结算（SETTLE）", subAccountId);
    }

    private BizException unavailable(String what, int subAccountId) {
        return BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                "子账户停用前置校验不可用：无法核算「" + what + "」——数据源 " + missingSources
                        + " 在本期未交付；subAccountId=" + subAccountId
                        + "；复现=在 M2（FUND/TRADE/SETTLE 就绪）环境重试");
    }
}
