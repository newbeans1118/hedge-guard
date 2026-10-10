package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.MajorEventDao;
import com.hedgeguard.dao.SettlePnlDao;
import com.hedgeguard.dao.entity.MajorEvent;
import com.hedgeguard.dao.entity.SettlePnl;
import com.hedgeguard.risk_intraday.MirrorMetrics;
import com.hedgeguard.risk_intraday.RiskEvaluation;
import com.hedgeguard.risk_intraday.RiskMetricSink;
import com.hedgeguard.risk_intraday.SubAccountRiskMetrics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 重大事项「亏损阈值」自动触发器（{@code T-188}；{@code CFG-26} =
 * {@code audit.major_event.loss_threshold}；{@code FR-AUDIT-12}/{@code FR-AUDIT-13}；
 * {@code T-069} 验收 5）。
 *
 * <p><b>触发语义（用户 2026-10-10 裁定「双轨」，docs/TASKS.md T-188 裁定记）</b>：
 * 当日亏损按两路并行判定，任一路越限即触发（两路 OR），行形态统一为
 * {@code event_type=LOSS_THRESHOLD} + {@code status=TRIGGERED}（供
 * {@code AuditReportService.pendingMajorEvents} 经 {@code findByTypeAndStatus} 消费，
 * 既有 major-event 报告契约不改）：
 * <ol>
 *   <li><b>轨 1 · EOD 已实现（记账级，source=SETTLED）</b>：{@code t_settle_pnl} 按
 *       子账户当日 {@code amount} 求和，亏损绝对值<b>严格大于</b>阈值 ⇒ 触发。
 *       接线点 = {@code SettleService.runSettlement} 结算落库<b>事务提交后</b>（真实结算流程点；
 *       触发失败不阻断结算主流程）。⚠️ 诚实披露：当前生产<b>无 {@code t_settle_pnl} 写入方</b>
 *       （{@code SettleService} 注入 {@code pnlDao} 但从未 insert，既有缺口）⇒ 该轨在写入方
 *       落地前读空表、不触发；本触发器按裁定语义对真实表求值，写入方落地即通。</li>
 *   <li><b>轨 2 · 盘中镜像（提醒级，source=INTRADAY_MIRROR）</b>：
 *       {@code PositionView.floatPnl} 按子账户求和（{@code MirrorMetricsCalculator} 产物
 *       {@code SubAccountRiskMetrics.floatPnl}，即按子账户聚合的镜像浮动盈亏）越阈值 ⇒ 触发，
 *       {@code event_desc} 携带快照值。<b>铁律 4 边界（裁定）：镜像允许用于提醒触发、禁止用于记账。</b>
 *       接线点 = 事中风控既有异步先例（{@code RiskMetricSink} → {@code AsyncRiskMetricSink} →
 *       {@link com.hedgeguard.risk_intraday.CompositeRiskMetricSink} 并联下游），
 *       事件消费线程零 SQL（{@code R-RISK-10}）。</li>
 * </ol>
 *
 * <p><b>口径</b>：主体 = 子账户；「超阈值」= 亏损绝对值<b>严格大于</b>阈值（恰等不触发）；
 * 幂等 = 每子账户每源每自然日至多 1 行，由<b>确定性事项编号</b>
 * {@code LOSS-<yyyyMMdd>-<subAccountId>-<STLD|MIRR>} 承载（撞
 * {@code uk_major_event_no} 唯一约束的形态由「先查后插」前置换取，见 {@link #fire}），
 * 自然日翻转（编号内日期更替）即自然重置。
 *
 * <p><b>配置读取与处置</b>：惰性读取（先例 {@code CFG-17c} audit.chain.inflight_max_cycles，
 * 装配期不触库）。缺键/空值 ⇒ 安全回退默认 {@value #DEFAULT_THRESHOLD_TEXT}；
 * <b>非法（非数字）或 ≤0 ⇒ 显式拒</b>：抛 {@link IllegalStateException} 并由调用侧记
 * SEVERE 日志、<b>拒绝本次触发判定</b>（不静默回退默认值——以错误阈值判定比不判定更危险；
 * 不阻断主流程）。调用侧失败处置（LOG+吞，不重试）：
 * 轨 1 由 {@code SettleService} 捕获（旁路提醒能力不得阻断结算；幂等键按自然日，
 * 同日重跑结算天然重试）；轨 2 由 {@link com.hedgeguard.risk_intraday.CompositeRiskMetricSink}
 * 逐下游隔离（事件流持续到达，下一次评估即天然重试）。
 *
 * <p><b>载体披露</b>：裁定记称「content_json 标 source」——{@code t_major_event} 无该列
 * （DDL {@code docs/DEPLOY/schema/m3_audit.sql:287}）；source 落在语义即「触发来源」的
 * {@code trigger_source} 列（NOT NULL），快照值落 {@code event_desc}。语义不变、载体适配。
 */
public final class MajorEventLossThresholdTrigger implements RiskMetricSink {

    private static final Logger LOG = Logger.getLogger(MajorEventLossThresholdTrigger.class.getName());

    /** 配置键（{@code CFG-26}，TASKS:2841）。 */
    public static final String CONFIG_KEY = "audit.major_event.loss_threshold";

    /** 缺省阈值（元）：{@code data-init.sql} 播种值，缺键时安全回退。 */
    public static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("100000");

    /** 缺省阈值的字面形态（供日志/描述）。 */
    public static final String DEFAULT_THRESHOLD_TEXT = "100000";

    /** 事项类型（{@code ck_major_event_type} 冻结域）。 */
    public static final String EVENT_TYPE = "LOSS_THRESHOLD";

    /** 事项状态（待生成报告；供 {@code pendingMajorEvents} 消费）。 */
    public static final String STATUS_TRIGGERED = "TRIGGERED";

    /** 轨 1 触发来源：EOD 已实现（记账级）。 */
    public static final String SOURCE_SETTLED = "SETTLED";

    /** 轨 2 触发来源：盘中镜像（提醒级，禁止记账）。 */
    public static final String SOURCE_INTRADAY = "INTRADAY_MIRROR";

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MajorEventDao majorEventDao;
    private final SettlePnlDao settlePnlDao;
    /** 原始阈值取值（生产 = ConfigService 读取；{@code null} = 缺键 ⇒ 回退默认）。 */
    private final java.util.function.Supplier<String> thresholdRaw;
    private final long systemOperatorId;

    /**
     * 构造。
     *
     * @param majorEventDao    重大事项 DAO（真实）
     * @param settlePnlDao     结算盈亏 DAO（真实；轨 1 取数）
     * @param thresholdRaw     阈值原始取值供给（缺键返回 {@code null}）
     * @param systemOperatorId 系统操作人（落库留痕）
     */
    public MajorEventLossThresholdTrigger(MajorEventDao majorEventDao, SettlePnlDao settlePnlDao,
                                          java.util.function.Supplier<String> thresholdRaw,
                                          long systemOperatorId) {
        this.majorEventDao = majorEventDao;
        this.settlePnlDao = settlePnlDao;
        this.thresholdRaw = thresholdRaw;
        this.systemOperatorId = systemOperatorId;
    }

    /**
     * 轨 1（EOD 已实现，记账级）：对 {@code t_settle_pnl} 按子账户当日 {@code amount} 求和，
     * 亏损绝对值严格大于阈值的子账户各触发 1 行（{@code source=SETTLED}）。
     *
     * <p>由 {@code SettleService.runSettlement} 在结算落库事务提交后调用（真实结算流程点）；
     * 本方法抛出的任何 {@link RuntimeException} 由调用侧捕获并记日志，不阻断结算主流程。
     *
     * @param tradeDate 结算交易日（幂等键的自然日）
     * @return 本次新触发的行数（幂等去重后）
     */
    public int evaluateSettled(LocalDate tradeDate) {
        BigDecimal threshold = resolveThreshold();
        List<SettlePnl> rows = settlePnlDao.findByTradeDate(tradeDate);
        Map<Integer, BigDecimal> lossBySub = new LinkedHashMap<>();
        for (SettlePnl p : rows) {
            if (p.subAccountId == null || p.amount == null) {
                continue;
            }
            lossBySub.merge(p.subAccountId, p.amount, BigDecimal::add);
        }
        int fired = 0;
        for (Map.Entry<Integer, BigDecimal> e : lossBySub.entrySet()) {
            BigDecimal total = e.getValue();
            if (isLossBeyond(total, threshold)) {
                String desc = "当日已实现亏损超阈值（记账级）：t_settle_pnl 按子账户求和="
                        + total.toPlainString() + "，阈值=" + threshold.toPlainString()
                        + "，tradeDate=" + tradeDate;
                if (fire(e.getKey(), SOURCE_SETTLED, "STLD", tradeDate, desc)) {
                    fired++;
                }
            }
        }
        return fired;
    }

    /**
     * 轨 2（盘中镜像，提醒级）：对一次事中评估产物按子账户检查镜像浮动盈亏，
     * 亏损绝对值严格大于阈值的子账户各触发 1 行（{@code source=INTRADAY_MIRROR} +
     * {@code event_desc} 快照值）。
     *
     * <p><b>铁律 4（裁定）</b>：镜像允许用于提醒触发、禁止用于记账——本方法只落
     * {@code t_major_event} 触发行（提醒），<b>不做任何记账写操作</b>。
     *
     * <p>由 {@code AsyncRiskMetricSink} 工作线程调用（事件消费线程零 SQL，{@code R-RISK-10}）；
     * 抛出的异常由 {@code CompositeRiskMetricSink} 隔离。
     *
     * @param evaluation 事中评估产物（{@code null} 忽略）
     */
    @Override
    public void publish(RiskEvaluation evaluation) {
        if (evaluation == null || evaluation.metrics == null) {
            return;
        }
        MirrorMetrics metrics = evaluation.metrics;
        BigDecimal threshold = resolveThreshold();
        LocalDate today = Clocks.now().toLocalDate();
        for (SubAccountRiskMetrics m : metrics.subAccounts) {
            if (m == null || m.subAccountId <= 0) {
                continue; // 汇总行（哨兵 0）非触发主体：主体 = 子账户
            }
            if (isLossBeyond(m.floatPnl, threshold)) {
                String desc = "盘中浮动亏损超阈值（镜像快照，提醒级，禁止记账）："
                        + "PositionView.floatPnl 按子账户求和=" + m.floatPnl.toPlainString()
                        + "，阈值=" + threshold.toPlainString()
                        + "，快照水位 seq=" + metrics.watermarkEventSeq;
                fire(m.subAccountId, SOURCE_INTRADAY, "MIRR", today, desc);
            }
        }
    }

    /** 解析当前生效阈值（缺键回退默认；非法/≤0 显式拒）。 */
    public BigDecimal resolveThreshold() {
        String raw = thresholdRaw == null ? null : thresholdRaw.get();
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_THRESHOLD; // 缺键 ⇒ 安全回退默认（CFG-26 播种值）
        }
        BigDecimal v;
        try {
            v = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("CFG-26 " + CONFIG_KEY + " 取值非法（非数字）=\""
                    + raw + "\"：显式拒（拒绝本次触发判定，不回退默认值，不阻断主流程）", e);
        }
        if (v.signum() <= 0) {
            throw new IllegalStateException("CFG-26 " + CONFIG_KEY + " 取值须 >0，实际=\""
                    + raw + "\"：显式拒（拒绝本次触发判定，不回退默认值，不阻断主流程）");
        }
        return v;
    }

    /** 亏损判定：值为负且亏损绝对值<b>严格大于</b>阈值（恰等不触发）。 */
    private static boolean isLossBeyond(BigDecimal pnl, BigDecimal threshold) {
        return pnl != null && pnl.signum() < 0 && pnl.negate().compareTo(threshold) > 0;
    }

    /**
     * 落一行触发行（幂等：确定性编号先查后插，同号已存在即跳过）。
     *
     * @return 是否本次真实落行（{@code false} = 同日同源已触发过）
     */
    private boolean fire(int subAccountId, String source, String sourceCode,
                         LocalDate day, String desc) {
        String eventNo = "LOSS-" + day.format(DAY_FMT) + "-" + subAccountId + "-" + sourceCode;
        if (majorEventDao.findByEventNo(eventNo) != null) {
            return false; // 幂等：每子账户每源每自然日至多 1 行（uk_major_event_no 同键）
        }
        MajorEvent row = new MajorEvent();
        row.subAccountId = subAccountId;
        row.eventNo = eventNo;
        row.eventType = EVENT_TYPE;
        row.triggerSource = source;
        row.eventDesc = desc;
        row.impactScope = "子账户 " + subAccountId;
        row.status = STATUS_TRIGGERED;
        row.auditReportId = null;
        LocalDateTime now = Clocks.now();
        row.triggeredTime = now;
        majorEventDao.insert(row, systemOperatorId);
        LOG.info("重大事项自动触发（T-188/CFG-26）：eventNo=" + eventNo + " source=" + source
                + "；" + desc);
        return true;
    }
}
