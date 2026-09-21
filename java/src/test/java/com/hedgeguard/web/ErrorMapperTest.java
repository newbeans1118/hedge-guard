package com.hedgeguard.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ErrorMapper} 单元测试（{@code ARCHITECTURE.md} §4.1.1 / §4.3.1）。
 *
 * <p>重点覆盖验收项："统一错误映射器输出
 * {@code {code,message,ruleId,current,threshold,nextStep,traceId}}（阻断类必含后四字段）"。
 */
class ErrorMapperTest {

    @Test
    @DisplayName("阻断类（10012）必须携带 ruleId/current/threshold/nextStep 四项")
    void blockingCarriesFourFields() {
        BizException e = BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                "年度保值规模使用率超 90% 红线", "R-RISK-4", "0.930000", "0.900000",
                "请调低本次下单数量或申请额度调整");

        ErrorInfo info = ErrorMapper.map(e, "HGTEST001");

        assertEquals(10012, info.code);
        assertEquals("R-RISK-4", info.ruleId);
        assertEquals("0.930000", info.current);
        assertEquals("0.900000", info.threshold);
        assertEquals("请调低本次下单数量或申请额度调整", info.nextStep);
        assertEquals("HGTEST001", info.traceId);
        assertEquals(412, ErrorMapper.httpStatus(e));
    }

    @Test
    @DisplayName("构造阻断类错误时缺任一项即拒绝（源头保证，不靠运行期兜底）")
    void blockingFactoryRejectsMissingField() {
        assertThrows(IllegalArgumentException.class, () -> BizException.blocking(
                ErrorCode.COMPLIANCE_GATE_FAILED, "msg", "", "0.93", "0.90", "next"));
        assertThrows(IllegalArgumentException.class, () -> BizException.blocking(
                ErrorCode.COMPLIANCE_GATE_FAILED, "msg", "R-RISK-4", null, "0.90", "next"));
    }

    @Test
    @DisplayName("用普通工厂构造阈值型阻断码即拒绝（防绕过四项校验）")
    void plainFactoryRejectsThresholdBlockingCode() {
        assertThrows(IllegalArgumentException.class,
                () -> BizException.of(ErrorCode.AGGREGATION_MISMATCH, "Σ 子账户 ≠ 真实账户"));
        assertThrows(IllegalArgumentException.class,
                () -> BizException.of(ErrorCode.DISPATCH_NOT_EFFECTIVE, "无回执"));
    }

    @Test
    @DisplayName("非阻断业务错误不带四项（序列化时省略）")
    void nonBlockingHasNoFourFields() {
        ErrorInfo info = ErrorMapper.map(BizException.of(ErrorCode.STATE_NOT_ALLOWED, "状态不允许"),
                "HGTEST002");
        assertEquals(10009, info.code);
        assertNull(info.ruleId);
        assertNull(info.current);
        assertNull(info.threshold);
        assertNull(info.nextStep);
        assertEquals(409, ErrorMapper.httpStatus(BizException.of(ErrorCode.STATE_NOT_ALLOWED, "x")));
    }

    @Test
    @DisplayName("非法参数归入 10001；未分类异常归入 10022 且不回显堆栈细节")
    void mapsNonBusinessExceptions() {
        ErrorInfo param = ErrorMapper.map(new IllegalArgumentException("size 取值须为 [10, 20, 50, 100]"),
                "HGTEST003");
        assertEquals(10001, param.code);
        assertTrue(param.message.contains("size"));
        assertEquals(400, ErrorMapper.httpStatus(new IllegalArgumentException("x")));

        ErrorInfo internal = ErrorMapper.map(new IllegalStateException("连接池内部状态异常"), "HGTEST004");
        assertEquals(10022, internal.code);
        assertEquals(ErrorCode.INTERNAL_ERROR.defaultMessage(), internal.message);
        assertEquals(500, ErrorMapper.httpStatus(new IllegalStateException("x")));
    }

    @Test
    @DisplayName("错误码表与 ARCHITECTURE.md §4.3.1 的 HTTP 映射一致（抽样）")
    void httpStatusMappingMatchesSpec() {
        assertEquals(200, ErrorCode.OK.httpStatus());
        assertEquals(503, ErrorCode.COMPONENT_UNAVAILABLE.httpStatus());
        assertEquals(403, ErrorCode.FUNCTION_PERMISSION_DENIED.httpStatus());
        assertEquals(401, ErrorCode.NOT_AUTHENTICATED.httpStatus());
        assertEquals(423, ErrorCode.ACCOUNT_LOCKED.httpStatus());
        assertEquals(409, ErrorCode.UNIQUE_CONFLICT.httpStatus());
        assertEquals(422, ErrorCode.IMPORT_VALIDATION_FAILED.httpStatus());
        assertEquals(403, ErrorCode.AUDIT_LOG_WRITE_FAILED.httpStatus());
        assertEquals(10022, ErrorCode.of(10022).code());
        assertEquals(ErrorCode.INTERNAL_ERROR, ErrorCode.of(99999));
        assertNotNull(ErrorCode.of(0));
    }
}
