package com.hedgeguard.job;

/**
 * 调度任务类型（{@code ARCHITECTURE.md} §3.12 J6 的 <b>7 类定时任务</b>）。
 *
 * <p>本枚举是「任务清单」的唯一权威来源；每个 {@link ScheduledJob} 通过 {@link #id()} 声明归属，
 * 幂等键 = {@code id + "#" + 时间片}，保证同一交易日同一任务最多执行一次
 * （{@code 验收标准 4「不产生重复结算」}）。
 */
public enum JobType {

    /** 日终结算：交易日收盘后，CTP 日终结算数据到达 → 入库 / 拆分 / 结算 / 关账（§6.5）。 */
    DAILY_SETTLEMENT("daily-settlement", "日终结算"),

    /** 事后风控：结算完成后，取当日全量生成并落库风险报表三部分（§6.9 / FR-RISK-17）。 */
    POST_RISK_REPORT("post-risk-report", "事后风控"),

    /** 报表生成：每日结算报告 + 风控日报（§6.5 ⑨）。 */
    REPORT_GENERATION("report-generation", "报表生成"),

    /** 对账：加总对账 / 资金日终对账 / 核对任务逾期巡检（§6.5 ⑥ / §6.14）。 */
    RECONCILIATION("reconciliation", "对账"),

    /** 到期提醒：套保计划到期前 N 天提醒，N 取 {@code CFG-24 plan.expiry_remind_days}（§11.2）。 */
    PLAN_EXPIRY_REMINDER("plan-expiry-reminder", "到期提醒"),

    /** 报告周期提醒：按 {@code t_report_period_config.remind_advance_days} 提醒（§11.3 / Q-14）。 */
    REPORT_PERIOD_REMINDER("report-period-reminder", "报告周期提醒"),

    /** 事件流水位巡检：镜像水位 / 事件序号滞后告警（§6.8 / R-RISK-10 / §4.5）。 */
    EVENT_FLOW_WATERMARK("event-flow-watermark", "事件流水位巡检");

    private final String id;
    private final String displayName;

    JobType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** 稳定任务标识（幂等键前缀，不随展示名变化）。 */
    public String id() {
        return id;
    }

    /** 中文展示名。 */
    public String displayName() {
        return displayName;
    }

    /** 任务种类数（固定 7 类，供装配自检与单测断言）。 */
    public static int count() {
        return values().length;
    }
}
