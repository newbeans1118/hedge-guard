package com.hedgeguard.job;

/**
 * 事后风险报表生成失败告警端口（{@code FR-RISK-17} 验收 4：生成失败须告警）。
 *
 * <p>实现须快速返回，不得抛出异常（告警本身失败不得掩盖原失败）。生产装配由运维告警
 * sink 承接；测试可用记录式实现断言「失败必告警」。
 */
@FunctionalInterface
public interface PostRiskReportAlarm {

    /** 空告警（不告警；仅用于无需告警的装配）。 */
    PostRiskReportAlarm NOOP = (message, cause) -> {
    };

    /**
     * 告警。
     *
     * @param message 告警文案
     * @param cause   触发告警的失败原因
     */
    void alert(String message, Throwable cause);
}
