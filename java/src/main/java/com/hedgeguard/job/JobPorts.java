package com.hedgeguard.job;

import java.time.LocalDate;

/**
 * 调度层 → L3 服务层的端口集合（{@code §3.12}「调度任务不得绕过 L3 直接调 DAO」）。
 *
 * <p><b>这一接口就是分层纪律的边界</b>：{@code job} 包内的 7 类任务只持有本接口，
 * 不持有任何 {@code com.hedgeguard.dao.*} 类型、不出现 SQL。生产实现由 {@code Bootstrap}
 * 用真实的 {@code SettleService / PostRiskReportService / ConfigService / PlanService /
 * CheckTaskQueryService / AuditReportService / TradingBridge} 方法引用装配；测试用内存记录桩。
 *
 * <p>每个端口对应 7 类任务的一次业务动作，返回结构化结果便于回报与断言；
 * 参数统一带 {@code operatorId}（系统账号），保证审计留痕归属明确。
 *
 * <p>端口实现须<b>幂等</b>（{@code NFR-REL-5}）：调度层的 {@link JobIdempotencyGuard}
 * 是第一道守卫，L3 服务自身的业务唯一键是第二道守卫。
 */
public interface JobPorts {

    // ── 1. 日终结算（§6.5）──────────────────────────────────────────────
    /** 日终结算：CTP 数据到达 → 入库 / 拆分 / 各子账户结算 / 加总对账 / 关账。 */
    JobOutcome.Summary runDailySettlement(LocalDate tradeDate, long operatorId);

    // ── 2. 事后风控（§6.9）──────────────────────────────────────────────
    /** 事后风控：结算完成后生成并落库当日风险报表三部分，与结算数据勾稽。 */
    JobOutcome.Summary runPostRiskReport(LocalDate tradeDate, long operatorId);

    // ── 3. 报表生成（§6.5 ⑨ / §6.11）────────────────────────────────────
    /** 报表生成：每日结算报告 + 风控日报。 */
    JobOutcome.Summary generateDailyReports(LocalDate tradeDate, long operatorId);

    // ── 4. 对账（§6.5 ⑥ / §6.14）───────────────────────────────────────
    /** 对账：加总对账 / 资金日终对账 / 核对任务逾期巡检。 */
    JobOutcome.Summary reconcile(LocalDate businessDate, long operatorId);

    // ── 5. 到期提醒（CFG-24）────────────────────────────────────────────
    /**
     * 到期提醒：扫描 {@code advanceDays} 天内到期的套保计划并提醒。
     *
     * @param asOf        基准日
     * @param advanceDays 提前天数（取自 {@code CFG-24 plan.expiry_remind_days}）
     * @param operatorId  系统操作人
     * @return 提醒结果摘要
     */
    JobOutcome.Summary remindPlanExpiry(LocalDate asOf, int advanceDays, long operatorId);

    // ── 6. 报告周期提醒（§11.3 / Q-14）──────────────────────────────────
    /** 报告周期提醒：按报告周期配置的提前天数提醒对应角色。 */
    JobOutcome.Summary remindReportPeriod(LocalDate asOf, long operatorId);

    // ── 7. 事件流水位巡检（§6.8 / R-RISK-10）───────────────────────────
    /** 事件流水位巡检：检查镜像事件序号是否滞后 / 回退，必要时告警。 */
    JobOutcome.Summary inspectEventFlowWatermark(long operatorId);

    /** 配置键：{@code CFG-24} 计划到期提前提醒期。 */
    String CFG_PLAN_EXPIRY_REMIND_DAYS = "plan.expiry_remind_days";

    /** {@code CFG-24} 缺省值（与 {@code data-init.sql} 种子一致）。 */
    int DEFAULT_PLAN_EXPIRY_REMIND_DAYS = 30;

    /** {@code CFG-24} 取值下界（§11.2 范围 1–180）。 */
    int MIN_PLAN_EXPIRY_REMIND_DAYS = 1;

    /** {@code CFG-24} 取值上界（§11.2 范围 1–180）。 */
    int MAX_PLAN_EXPIRY_REMIND_DAYS = 180;

    /**
     * 7 个端口的函数式定义，便于 {@code Bootstrap} 用 service 方法引用装配、
     * 单测用 lambda 构造记录桩，而<b>无需</b>在这里引入任何 service / dao 类型。
     */
    final class Actions {
        private Actions() {
        }

        /** 日终结算动作。 */
        @FunctionalInterface
        public interface DailySettlement {
            /** @return 摘要 */
            JobOutcome.Summary run(LocalDate tradeDate, long operatorId);
        }

        /** 事后风控动作。 */
        @FunctionalInterface
        public interface PostRiskReport {
            /** @return 摘要 */
            JobOutcome.Summary run(LocalDate tradeDate, long operatorId);
        }

        /** 报表生成动作。 */
        @FunctionalInterface
        public interface ReportGeneration {
            /** @return 摘要 */
            JobOutcome.Summary run(LocalDate tradeDate, long operatorId);
        }

        /** 对账动作。 */
        @FunctionalInterface
        public interface Reconciliation {
            /** @return 摘要 */
            JobOutcome.Summary run(LocalDate businessDate, long operatorId);
        }

        /** 到期提醒动作。 */
        @FunctionalInterface
        public interface PlanExpiryReminder {
            /** @return 摘要 */
            JobOutcome.Summary run(LocalDate asOf, int advanceDays, long operatorId);
        }

        /** 报告周期提醒动作。 */
        @FunctionalInterface
        public interface ReportPeriodReminder {
            /** @return 摘要 */
            JobOutcome.Summary run(LocalDate asOf, long operatorId);
        }

        /** 事件流水位巡检动作。 */
        @FunctionalInterface
        public interface EventFlowWatermark {
            /** @return 摘要 */
            JobOutcome.Summary run(long operatorId);
        }
    }

    /**
     * 用 7 个函数式动作装配一个 {@link JobPorts}（生产装配 / 测试桩共用）。
     *
     * @param settlement     日终结算
     * @param postRisk       事后风控
     * @param reports        报表生成
     * @param reconciliation 对账
     * @param expiryReminder 到期提醒
     * @param periodReminder 报告周期提醒
     * @param watermark      事件流水位巡检
     * @return 端口实例
     */
    static JobPorts of(Actions.DailySettlement settlement,
                       Actions.PostRiskReport postRisk,
                       Actions.ReportGeneration reports,
                       Actions.Reconciliation reconciliation,
                       Actions.PlanExpiryReminder expiryReminder,
                       Actions.ReportPeriodReminder periodReminder,
                       Actions.EventFlowWatermark watermark) {
        java.util.Objects.requireNonNull(settlement, "settlement");
        java.util.Objects.requireNonNull(postRisk, "postRisk");
        java.util.Objects.requireNonNull(reports, "reports");
        java.util.Objects.requireNonNull(reconciliation, "reconciliation");
        java.util.Objects.requireNonNull(expiryReminder, "expiryReminder");
        java.util.Objects.requireNonNull(periodReminder, "periodReminder");
        java.util.Objects.requireNonNull(watermark, "watermark");
        return new JobPorts() {
            @Override
            public JobOutcome.Summary runDailySettlement(LocalDate d, long op) {
                return settlement.run(d, op);
            }

            @Override
            public JobOutcome.Summary runPostRiskReport(LocalDate d, long op) {
                return postRisk.run(d, op);
            }

            @Override
            public JobOutcome.Summary generateDailyReports(LocalDate d, long op) {
                return reports.run(d, op);
            }

            @Override
            public JobOutcome.Summary reconcile(LocalDate d, long op) {
                return reconciliation.run(d, op);
            }

            @Override
            public JobOutcome.Summary remindPlanExpiry(LocalDate d, int days, long op) {
                return expiryReminder.run(d, days, op);
            }

            @Override
            public JobOutcome.Summary remindReportPeriod(LocalDate d, long op) {
                return periodReminder.run(d, op);
            }

            @Override
            public JobOutcome.Summary inspectEventFlowWatermark(long op) {
                return watermark.run(op);
            }
        };
    }

    /** 全空实现（7 个动作均返回空摘要），用于「无 DB 装配」与单测基线。 */
    JobPorts NOOP = of(
            (d, op) -> JobOutcome.Summary.empty(),
            (d, op) -> JobOutcome.Summary.empty(),
            (d, op) -> JobOutcome.Summary.empty(),
            (d, op) -> JobOutcome.Summary.empty(),
            (d, days, op) -> JobOutcome.Summary.empty(),
            (d, op) -> JobOutcome.Summary.empty(),
            op -> JobOutcome.Summary.empty());
}
