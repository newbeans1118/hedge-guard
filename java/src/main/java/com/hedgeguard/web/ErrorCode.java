package com.hedgeguard.web;

/**
 * Java 业务错误码（{@code ARCHITECTURE.md} §4.3.1）。
 *
 * <p><b>冻结口径</b>：本枚举是 §4.3.1 表的唯一代码载体，取值<b>不得</b>自行扩展；
 * 新增错误码须先改 {@code ARCHITECTURE.md} §4.3.1 再改此处（{@code §8.3「文档即事实」}）。
 *
 * <p>两套错误码<b>不得混用</b>：本枚举用于 Java → 前端（HTTP，{@code 1xxxx}）；
 * 组件错误码（{@code 0x10xx} 等）在 {@code proto/hgcp.proto} 中，由 {@code bridge} 层包装为本枚举后返回前端。
 */
public enum ErrorCode {

    /** 成功（HTTP 200）。 */
    OK(0, 200, false, "OK"),
    /** 参数校验失败。 */
    PARAM_INVALID(10001, 400, false, "参数校验失败"),
    /** 交易组件通信不可用。 */
    COMPONENT_UNAVAILABLE(10002, 503, false, "交易组件通信不可用"),
    /** 功能权限不足。 */
    FUNCTION_PERMISSION_DENIED(10003, 403, false, "功能权限不足"),
    /** 子账户数据权限不足。 */
    SUB_ACCOUNT_PERMISSION_DENIED(10004, 403, false, "子账户数据权限不足"),
    /** 岗位分离冲突。 */
    POSITION_SEPARATION_CONFLICT(10005, 403, false, "岗位分离冲突"),
    /** 未登录或会话超时。 */
    NOT_AUTHENTICATED(10006, 401, false, "未登录或会话超时"),
    /** 认证失败（与"用户不存在"同一响应，防账号枚举）。 */
    AUTH_FAILED(10007, 401, false, "认证失败"),
    /** 账号锁定。 */
    ACCOUNT_LOCKED(10008, 423, false, "账号锁定"),
    /** 状态不允许。 */
    STATE_NOT_ALLOWED(10009, 409, false, "状态不允许该操作"),
    /** 唯一约束冲突。 */
    UNIQUE_CONFLICT(10010, 409, false, "唯一约束冲突"),
    /** 关联引用冲突。 */
    REFERENCE_CONFLICT(10011, 409, false, "关联引用冲突"),
    /** 合规门禁未通过（阻断类，须携带 ruleId/current/threshold/nextStep）。 */
    COMPLIANCE_GATE_FAILED(10012, 412, true, "合规门禁未通过"),
    /** 加总对账不一致（阻断类，须携带 ruleId/current/threshold/nextStep）。 */
    AGGREGATION_MISMATCH(10013, 412, true, "加总对账不一致（已阻断）"),
    /** 参数下发未生效（阻断类，须携带 ruleId/current/threshold/nextStep）。 */
    DISPATCH_NOT_EFFECTIVE(10014, 412, true, "参数下发未生效"),
    /** 数据不足，拒绝计算。 */
    DATA_INSUFFICIENT(10015, 409, false, "数据不足，拒绝计算"),
    /** 期间已关账。 */
    PERIOD_CLOSED(10016, 409, false, "期间已关账"),
    /** 审计日志写入失败（须中断本次导出）。 */
    AUDIT_LOG_WRITE_FAILED(10017, 403, false, "审计日志写入失败"),
    /** 审计日志防篡改校验未通过（阻断导出）。 */
    AUDIT_CHAIN_TAMPERED(10018, 412, false, "审计日志防篡改校验未通过"),
    /** 导入文件校验失败。 */
    IMPORT_VALIDATION_FAILED(10019, 422, false, "导入文件校验失败"),
    /** 敏感字段操作被拒（零落盘红线）。 */
    SENSITIVE_FIELD_REJECTED(10020, 409, false, "敏感字段操作被拒"),
    /** 安全配置被拒。 */
    SECURITY_CONFIG_REJECTED(10021, 409, false, "安全配置被拒"),
    /** 内部错误。 */
    INTERNAL_ERROR(10022, 500, false, "内部错误");

    private final int code;
    private final int httpStatus;
    private final boolean thresholdBlocking;
    private final String defaultMessage;

    ErrorCode(int code, int httpStatus, boolean thresholdBlocking, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.thresholdBlocking = thresholdBlocking;
        this.defaultMessage = defaultMessage;
    }

    /** 业务错误码（写入响应信封 {@code code}）。 */
    public int code() {
        return code;
    }

    /** 对应 HTTP 状态码（{@code ARCHITECTURE.md} §4.3.1）。 */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 是否为「阈值型阻断」错误——此类错误<b>必须</b>携带
     * {@code ruleId} / {@code current} / {@code threshold} / {@code nextStep} 四项
     * （{@code NFR-USE-3}、{@code NFR-USE-4}、{@code ARCHITECTURE.md} §4.1.1）。
     */
    public boolean thresholdBlocking() {
        return thresholdBlocking;
    }

    /** 默认提示语。 */
    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * 按业务码反查枚举。
     *
     * @param code 业务码
     * @return 匹配的枚举；未知码返回 {@link #INTERNAL_ERROR}
     */
    public static ErrorCode of(int code) {
        for (ErrorCode e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return INTERNAL_ERROR;
    }
}
