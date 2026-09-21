package com.hedgeguard.bridge;

/**
 * 下单受理结果（{@code ARCHITECTURE.md} §4.2 事前风控拒绝语义 + §4.3.2 组件错误码）。
 *
 * <p>被拒绝时，{@code ruleId/current/threshold/nextStep} 由组件错误码映射而来，
 * 供 L4 直接组装为阻断类错误响应（{@code 10012}，{@code NFR-USE-3/4}）。
 */
public final class PlaceOrderResult {

    /** 是否被受理（事后风控与事前风控均通过）。 */
    public boolean accepted;

    /** C++ 指令号（{@code uint64} 单调递增；未受理时为 {@code 0}）。 */
    public long orderId;

    /** 幂等键（原样回显）。 */
    public String clientOrderRef;

    /** 组件错误码（{@code ARCHITECTURE.md} §4.3.2，如 {@code 0x1004}）；{@code 0} 表示成功。 */
    public int componentCode;

    /** 命中的额度层级（三层限额，{@code CFG-16}）：{@code SUB_PRODUCT} / {@code SUB_INSTRUMENT} / {@code ACCOUNT_PRODUCT}。 */
    public String limitLayer;

    /** 命中规则编号（如 {@code R-RISK-2}）。 */
    public String ruleId;

    /** 当前值（字符串化十进制）。 */
    public String current;

    /** 阈值（字符串化十进制）。 */
    public String threshold;

    /** 下一步建议。 */
    public String nextStep;

    /** 提示语（含 CTP 原文错误等可诊断信息）。 */
    public String message;

    /** 是否当前处于 C++ 未就绪（{@code 0x0401}）——此时应提示"系统未就绪"而非业务错误。 */
    public boolean systemNotReady;
}
