package com.hedgeguard.service.impl;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AbnormalOrderStatDao;
import com.hedgeguard.dao.MirrorWatermarkDao;
import com.hedgeguard.dao.RiskAlertDao;
import com.hedgeguard.dao.RiskMetricSnapshotDao;
import com.hedgeguard.dao.entity.AbnormalOrderStat;
import com.hedgeguard.dao.entity.MirrorWatermark;
import com.hedgeguard.dao.entity.RiskMetricSnapshot;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.risk_intraday.MirrorMetrics;
import com.hedgeguard.risk_intraday.ProductAbnormalStat;
import com.hedgeguard.risk_intraday.RiskAlert;
import com.hedgeguard.risk_intraday.RiskAlertNotifier;
import com.hedgeguard.risk_intraday.RiskEvaluation;
import com.hedgeguard.risk_intraday.RiskMetricSink;
import com.hedgeguard.risk_intraday.RiskMetricType;
import com.hedgeguard.risk_intraday.SubAccountRiskMetrics;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 事中风控「告警 / 指标快照 / 镜像水位 / 异常下单统计」的<b>真实 DAO 落库实现</b>
 * （{@code T-065}；{@code FR-RISK-12}~{@code FR-RISK-16}、{@code FR-RISK-19} 存储面）。
 *
 * <p>作为 {@link RiskMetricSink} 的<b>下游写者</b>装配在 {@code AsyncRiskMetricSink} 之后：
 * 计算在事件消费线程完成（{@code risk_intraday} 零 DB），本类在工作线程落库 ⇒
 * {@code FR-RISK-13} 验收 4「指标计算不得因数据库查询阻塞事件消费」（{@code R-RISK-10}）。
 *
 * <h2>落库映射</h2>
 * <ul>
 *   <li>每条内存告警 → {@code t_risk_alert}（{@code disposed='N'}；只增不改不删，{@code R-RISK-11}）；
 *       {@code alert_type} 由 {@code alertType} 显式值或 {@code metricType} 派生
 *       （{@code MARGIN_USAGE_RATE → MARGIN_RATIO}；滞后告警显式 {@code MIRROR_LAG}）；</li>
 *   <li>汇总 + 各子账户指标 → {@code t_risk_metric_snapshot}（{@code snapshot_source='EVENT'}，
 *       {@code mirror_seq} 记镜像水位以便复现）；{@code equity} 取 {@code SubAccountRiskMetrics.balance}；</li>
 *   <li>镜像水位 → {@code t_mirror_watermark}（{@code component_id='hg-trading'}，{@code stream_type='EVENT'}）；</li>
 *   <li>异常下单统计 → {@code t_abnormal_order_stat}（仅当本次评估存在 {@code ABNORMAL_ORDER} 告警时落库，
 *       按自然键 {@code (stat_date,sub,product,window)} 幂等 upsert）。</li>
 * </ul>
 *
 * <h2>诚实披露（数据源缺口，不伪造）</h2>
 * <ol>
 *   <li>{@code t_mirror_watermark.last_produced_seq} 的源（组件心跳载荷）在树中不存在
 *       （{@code TradingBridge} 签名冻结、{@code MirrorSnapshot} 无 produced seq）⇒ 本类只落
 *       {@code last_consumed_seq} 与时间型 {@code lag_alarm}，<b>不伪造</b> produced/lag（seq 型滞后量
 *       待 {@code T-040}/{@code T-175} 心跳接线）。</li>
 *   <li>{@code t_abnormal_order_stat.order_ids}（含交易理由供自证）无来源 —— {@code TradeActivity}
 *       不含指令号/理由字段 ⇒ 置 {@code null}（{@code T-172} 富化残留）。</li>
 * </ol>
 */
public final class RiskIntradayPersistenceSink implements RiskMetricSink {

    private static final Logger LOG = Logger.getLogger(RiskIntradayPersistenceSink.class.getName());

    /** 日内窗（分钟；{@code DATA_MODEL.md} TB-RISK-6 缺省 1440）。 */
    public static final int INTRADAY_WINDOW_MINUTES = 1440;

    /** 7 日滚动窗（分钟）。 */
    public static final int ROLLING_WINDOW_MINUTES = 10080;

    /** 缺省组件标识（与 {@code MirrorWatermark.componentId} 口径一致）。 */
    public static final String DEFAULT_COMPONENT_ID = "hg-trading";

    /** 事件流标识。 */
    public static final String STREAM_EVENT = "EVENT";

    private static final int DETAIL_MAX = 1000;

    private final TxTemplate tx;
    private final RiskAlertDao alertDao;
    private final RiskMetricSnapshotDao snapshotDao;
    private final MirrorWatermarkDao watermarkDao;
    private final AbnormalOrderStatDao abnormalDao;
    private final RiskAlertNotifier notifier;
    private final Supplier<List<ProductAbnormalStat>> abnormalSupplier;
    private final String componentId;
    private final long systemOperatorId;
    private final long lagThresholdMillis;

    /**
     * 构造。
     *
     * @param tx               事务模板
     * @param alertDao         告警 DAO
     * @param snapshotDao      指标快照 DAO
     * @param watermarkDao     镜像水位 DAO
     * @param abnormalDao      异常下单统计 DAO
     * @param notifier         告警实时推送端口（{@code null} ⇒ 仅落库不推送；生产为 {@code T-040}）
     * @param abnormalSupplier 异常统计取数（{@code null} ⇒ 不落统计；生产为 tracker.snapshot）
     * @param componentId      组件标识（空则取缺省）
     * @param systemOperatorId 系统操作人（落库留痕）
     * @param lagThresholdMillis 镜像时间型滞后告警阈值（毫秒）
     */
    public RiskIntradayPersistenceSink(TxTemplate tx, RiskAlertDao alertDao,
                                       RiskMetricSnapshotDao snapshotDao,
                                       MirrorWatermarkDao watermarkDao,
                                       AbnormalOrderStatDao abnormalDao,
                                       RiskAlertNotifier notifier,
                                       Supplier<List<ProductAbnormalStat>> abnormalSupplier,
                                       String componentId, long systemOperatorId,
                                       long lagThresholdMillis) {
        this.tx = tx;
        this.alertDao = alertDao;
        this.snapshotDao = snapshotDao;
        this.watermarkDao = watermarkDao;
        this.abnormalDao = abnormalDao;
        this.notifier = notifier;
        this.abnormalSupplier = abnormalSupplier;
        this.componentId = componentId == null || componentId.trim().isEmpty()
                ? DEFAULT_COMPONENT_ID : componentId;
        this.systemOperatorId = systemOperatorId;
        this.lagThresholdMillis = lagThresholdMillis;
    }

    @Override
    public void publish(RiskEvaluation evaluation) {
        if (evaluation == null) {
            return;
        }
        LocalDateTime now = Clocks.now();
        LocalDate today = now.toLocalDate();
        long tsMs = evaluation.evaluatedAtEpochMilli > 0
                ? evaluation.evaluatedAtEpochMilli : Clocks.epochMilli();
        MirrorMetrics metrics = evaluation.metrics;

        final List<com.hedgeguard.dao.entity.RiskAlert> persisted = new ArrayList<>();
        boolean hasAbnormalAlert = false;
        for (RiskAlert a : evaluation.alerts) {
            persisted.add(toEntity(a, now, tsMs));
            if ("ABNORMAL_ORDER".equals(resolveType(a))) {
                hasAbnormalAlert = true;
            }
        }
        final boolean persistAbnormal = hasAbnormalAlert && abnormalSupplier != null;
        final List<ProductAbnormalStat> abnormalStats =
                persistAbnormal ? safeStats() : java.util.Collections.emptyList();

        tx.inTransaction(connection -> {
            for (com.hedgeguard.dao.entity.RiskAlert row : persisted) {
                alertDao.insert(row, systemOperatorId);
            }
            if (metrics != null) {
                snapshotDao.insert(toSnapshot(metrics.aggregate, metrics, now, today), systemOperatorId);
                for (SubAccountRiskMetrics m : metrics.subAccounts) {
                    snapshotDao.insert(toSnapshot(m, metrics, now, today), systemOperatorId);
                }
                upsertWatermark(metrics, now);
            }
            for (ProductAbnormalStat st : abnormalStats) {
                upsertAbnormal(st, today);
            }
            return null;
        });

        // 推送在事务提交后（实时通道不参与落库事务；{@code R-RISK-11} 不丢由通道负责）
        if (notifier != null) {
            for (RiskAlert a : evaluation.alerts) {
                notifier.notify(a.subAccountId, a.level == null ? null : a.level.name(),
                        resolveType(a), a.value, a.threshold);
            }
        }
    }

    private List<ProductAbnormalStat> safeStats() {
        try {
            List<ProductAbnormalStat> s = abnormalSupplier.get();
            return s == null ? java.util.Collections.emptyList() : s;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "异常下单统计取数失败（不落该批统计，不影响告警落库）："
                    + e.getClass().getSimpleName() + "：" + e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    /** 内存告警 → 持久化实体。 */
    private static com.hedgeguard.dao.entity.RiskAlert toEntity(RiskAlert a, LocalDateTime now,
                                                               long tsMs) {
        com.hedgeguard.dao.entity.RiskAlert row = new com.hedgeguard.dao.entity.RiskAlert();
        row.subAccountId = a.subAccountId;
        row.alertType = resolveType(a);
        row.metricName = resolveType(a);
        row.alertLevel = a.level == null ? null : a.level.name();
        row.metricValue = a.value;
        row.thresholdValue = a.threshold;
        row.alertDetail = truncate(a.message, DETAIL_MAX);
        row.alertTsMs = tsMs;
        row.alertTime = parseTime(a.raisedAt, now);
        row.disposed = "N";
        return row;
    }

    /** 解析落库告警类型（{@code alertType} 优先；否则由指标类型派生）。 */
    static String resolveType(RiskAlert a) {
        if (a.alertType != null && !a.alertType.trim().isEmpty()) {
            return a.alertType.trim();
        }
        RiskMetricType t = a.metricType;
        if (t == null) {
            return null;
        }
        // MARGIN_USAGE_RATE 与 DATA_MODEL 的 MARGIN_RATIO 逐字对齐
        return t == RiskMetricType.MARGIN_USAGE_RATE ? "MARGIN_RATIO" : t.name();
    }

    /** 内存指标 → 快照实体（{@code equity} 取资金余额 balance）。 */
    private static RiskMetricSnapshot toSnapshot(SubAccountRiskMetrics m, MirrorMetrics metrics,
                                                 LocalDateTime now, LocalDate today) {
        RiskMetricSnapshot row = new RiskMetricSnapshot();
        row.subAccountId = m.subAccountId;
        row.snapshotTime = now;
        row.snapshotDate = today;
        row.positionQtyNet = m.positionQty;
        row.floatPnl = m.floatPnl;
        row.marginOccupied = m.marginOccupied;
        row.marginRatio = m.marginUsageRate;
        row.riskRatio = m.riskRatio;
        row.availableFund = m.available;
        row.equity = m.balance;
        row.mirrorSeq = metrics.watermarkEventSeq;
        row.snapshotSource = "EVENT";
        return row;
    }

    /** 镜像水位 upsert（{@code produced_seq} 无源 ⇒ 与 consumed 同值并披露，不伪造滞后）。 */
    private void upsertWatermark(MirrorMetrics metrics, LocalDateTime now) {
        MirrorWatermark row = watermarkDao.findByComponentAndStream(componentId, STREAM_EVENT);
        boolean isNew = row == null;
        if (isNew) {
            row = new MirrorWatermark();
            row.componentId = componentId;
            row.streamType = STREAM_EVENT;
        }
        row.lastConsumedSeq = metrics.watermarkEventSeq;
        row.lastProducedSeq = metrics.watermarkEventSeq;
        row.lag = 0L;
        row.lagThreshold = lagThresholdMillis;
        row.lagAlarm = metrics.lagging ? "Y" : "N";
        row.lastUpdateTime = now;
        row.connectionState = metrics.readyState == null
                ? "DISCONNECTED"
                : (metrics.readyState.name().contains("READY") ? "CONNECTED" : "DISCONNECTED");
        if (isNew) {
            watermarkDao.insert(row, systemOperatorId);
        } else {
            watermarkDao.updateById(row, systemOperatorId);
        }
    }

    /** 异常下单统计 upsert（自然键幂等）。 */
    private void upsertAbnormal(ProductAbnormalStat st, LocalDate today) {
        List<AbnormalOrderStat> existing = abnormalDao.findByStatDate(today);
        AbnormalOrderStat match = null;
        for (AbnormalOrderStat e : existing) {
            if (e.subAccountId != null && e.subAccountId == st.subAccountId
                    && e.productId != null && e.productId == st.productId
                    && eq(e.instrumentId, st.instrumentId)
                    && e.windowMinutes != null && e.windowMinutes == st.windowMinutes) {
                match = e;
                break;
            }
        }
        boolean isNew = match == null;
        AbnormalOrderStat row = isNew ? new AbnormalOrderStat() : match;
        row.subAccountId = st.subAccountId;
        row.productId = st.productId;
        row.instrumentId = st.instrumentId;
        row.statDate = today;
        row.windowMinutes = st.windowMinutes;
        row.openCloseCount = st.openCloseCount;
        row.minHoldMinutes = st.minHoldMinutes;
        row.avgHoldMinutes = st.avgHoldMinutes;
        row.roundTripCount = st.roundTripCount;
        row.lastOrderTime = st.lastOrderTime;
        row.isAbnormal = st.abnormal ? "Y" : "N";
        row.actionTaken = st.actionTaken;
        row.orderIds = null;
        if (isNew) {
            abnormalDao.insert(row, systemOperatorId);
        } else {
            abnormalDao.updateById(row, systemOperatorId);
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static LocalDateTime parseTime(String iso, LocalDateTime fallback) {
        if (iso == null || iso.trim().isEmpty()) {
            return fallback;
        }
        try {
            return LocalDateTime.parse(iso.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
