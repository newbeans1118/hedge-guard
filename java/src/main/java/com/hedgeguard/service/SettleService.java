package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.BizNoGenerator;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SettleAggDiffCandidateDao;
import com.hedgeguard.dao.SettleAggregationCheckDao;
import com.hedgeguard.dao.SettleCtpBatchDao;
import com.hedgeguard.dao.SettleCtpFundDao;
import com.hedgeguard.dao.SettleCtpPositionDao;
import com.hedgeguard.dao.SettleCtpTradeDao;
import com.hedgeguard.dao.SettleCycleDao;
import com.hedgeguard.dao.SettleFundDao;
import com.hedgeguard.dao.SettlePnlDao;
import com.hedgeguard.dao.SettlePositionDao;
import com.hedgeguard.dao.SettleReconcileDifferenceDao;
import com.hedgeguard.dao.SettleReconcileRecordDao;
import com.hedgeguard.dao.SettleReportDao;
import com.hedgeguard.dao.SettleStressTestDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.SettleAggDiffCandidate;
import com.hedgeguard.dao.entity.SettleAggregationCheck;
import com.hedgeguard.dao.entity.SettleCtpBatch;
import com.hedgeguard.dao.entity.SettleCtpFund;
import com.hedgeguard.dao.entity.SettleCtpPosition;
import com.hedgeguard.dao.entity.SettleCtpTrade;
import com.hedgeguard.dao.entity.SettleCycle;
import com.hedgeguard.dao.entity.SettleFund;
import com.hedgeguard.dao.entity.SettlePnl;
import com.hedgeguard.dao.entity.SettlePosition;
import com.hedgeguard.dao.entity.SettleReconcileDifference;
import com.hedgeguard.dao.entity.SettleReconcileRecord;
import com.hedgeguard.dao.entity.SettleReport;
import com.hedgeguard.dao.entity.SettleStressTest;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.JsonCodec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * 结算服务（{@code ARCHITECTURE.md} §3.8 {@code SettleService}；{@code T-047}；
 * {@code FR-SETTLE-1}~{@code 12}/{@code 15}/{@code 16}）。
 *
 * <p><b>三条结构性保证</b>：
 * <ol>
 *   <li><b>CTP 数据为唯一权威基准</b>（{@code R-SETTLE-3}）：{@link #ingestCtpData} 只在数据到达时入库，
 *       数据未到达置 {@code NOT_ARRIVED} 待重试，<b>绝不</b>用推算数据替代；{@link #runSettlement}
 *       在 CTP 明细缺失时以 {@code DATA_INSUFFICIENT} 拒绝（不产出结算结果）。</li>
 *   <li><b>子账户独立结算</b>（{@code R-SETTLE-1}/{@code R-SETTLE-9}）：每个子账户各出一份
 *       {@code t_settle_cycle}，持仓/资金/盈亏明细行**逐行携带子账户且可直接求和**，
 *       拆分不出（{@code Σ 子账户 ≠ CTP 合并}）时**只告警并列差额、不默认归入任一子账户**。</li>
 *   <li><b>加总对账一致性与差异阻断</b>（{@code FR-SETTLE-8} / {@code R-SETTLE-2} / {@code Q-11}）：
 *       {@link #aggregate} 校验 {@code Σ 子账户 = 真实账户}；不一致 ⇒ 全账户告警 + 立即阻断
 *       （{@code block_effective_time} 非空），阻断范围<b>仅差异所属子账户</b>的新开仓/资金划拨/日终关账，
 *       <b>不波及其他子账户、不阻断平仓</b>（{@code block_scope='DIFF_SUB_NON_CLOSE'}）。
 *       差异归属的**机判取数来源唯一**为 {@code t_agg_diff_candidate}（{@code resolution_status ∈
 *       {PENDING, CONFIRMED}} 的 {@code sub_account_id} 全集）；{@code possible_sub_accounts} 仅作展示缓存，
 *       <b>解析该文本作判定即违规</b>（{@code DATA_MODEL.md} §4.4.1）。</li>
 * </ol>
 *
 * <p><b>关账门禁</b>（{@code R-SETTLE-5}）：{@link #closeCycle} 前置条件为 ① 三类核对均已完成
 * （{@code t_reconcile_record} 含 TRADE/POSITION/FUND 三类）② 加总对账无未闭环的不一致
 * ③ 该交易日全部差异已闭环（{@code t_reconcile_difference.handle_status='CLOSED'}）。任一不满足即拒。</p>
 *
 * <p><b>与 {@code FUND} 的边界</b>（{@code R-SETTLE-8}）：结算资金与资金台账**共享口径**——系统侧
 * 按子账户资金以入参 {@link SystemFundRow} 承接（含 {@code ledgerEquity} 台账口径），结算时逐子账户
 * 与台账勾稽（不等即记差异，成为加总差异的候选归属）；本类不另维护一套资金余额。</p>
 *
 * <p><b>事件源实态（据实披露）</b>：{@code HgcpClient} 对 {@code EVT_*} 仅推水位、无生产订阅
 * （{@code T-172} 事件面接线并行实施中）。故 CTP 日终数据与系统内按子账户成交/持仓数据在本类以
 * <b>「服务方法 + 入参接缝」</b>承接（{@link CtpDataRequest} / {@link SettlementInput} /
 * {@link SystemTradeRow}），生产事件接线缺口归 {@code T-172}，不在本任务写域（{@code bridge/**} 未改动）。</p>
 *
 * <p>分层纪律（{@code §3.8}）：不出现 SQL；不依赖 HTTP 类型；只经 DAO 接口访问数据。</p>
 */
public final class SettleService {

    private static final Logger LOG = Logger.getLogger(SettleService.class.getName());

    /** 结算单状态：待结算。 */
    public static final String CYCLE_PENDING = "PENDING";
    /** 结算单状态：已生成（待核对）。 */
    public static final String CYCLE_GENERATED = "GENERATED";
    /** 结算单状态：已阻断。 */
    public static final String CYCLE_BLOCKED = "BLOCKED";
    /** 结算单状态：核对完成。 */
    public static final String CYCLE_RECONCILED = "RECONCILED";
    /** 结算单状态：已确认（关账）。 */
    public static final String CYCLE_CONFIRMED = "CONFIRMED";

    /** 对账结论：一致。 */
    public static final String AGG_CONSISTENT = "CONSISTENT";
    /** 对账结论：不一致-已阻断。 */
    public static final String AGG_INCONSISTENT = "INCONSISTENT_BLOCKED";

    /** 阻断范围（{@code CFG-18}）：仅差异子账户、且不含平仓。 */
    public static final String BLOCK_SCOPE_DIFF_SUB_NON_CLOSE = "DIFF_SUB_NON_CLOSE";
    /** 阻断范围：仅差异子账户（含平仓）。 */
    public static final String BLOCK_SCOPE_DIFF_SUB = "DIFF_SUB";
    /** 阻断范围：全部子账户。 */
    public static final String BLOCK_SCOPE_ALL_SUB = "ALL_SUB";

    /** 核对结论：一致。 */
    public static final String RESULT_CONSISTENT = "CONSISTENT";
    /** 核对结论：存在差异。 */
    public static final String RESULT_HAS_DIFFERENCE = "HAS_DIFFERENCE";
    /** 核对结论：未完成（数据不足）。 */
    public static final String RESULT_NOT_COMPLETED = "NOT_COMPLETED";

    /** 差异处理状态：待处理。 */
    public static final String DIFF_PENDING = "PENDING";
    /** 差异处理状态：处理中。 */
    public static final String DIFF_HANDLING = "HANDLING";

    /** 盈亏类型：手续费（{@code ck_t_settle_pnl_pnl_type} 冻结域；{@code T-189} 转录行）。 */
    public static final String PNL_TYPE_COMMISSION = "COMMISSION";
    /** 差异处理状态：已闭环。 */
    public static final String DIFF_CLOSED = "CLOSED";

    /** 差异种类：系统有、期货公司无。 */
    public static final String KIND_SYS_ONLY = "SYS_ONLY";
    /** 差异种类：期货公司有、系统无。 */
    public static final String KIND_CTP_ONLY = "CTP_ONLY";
    /** 差异种类：要素不一致。 */
    public static final String KIND_VALUE_MISMATCH = "VALUE_MISMATCH";

    /** 差异类型：成交。 */
    public static final String DIFF_TYPE_TRADE = "TRADE";
    /** 差异类型：持仓。 */
    public static final String DIFF_TYPE_POSITION = "POSITION";
    /** 差异类型：资金。 */
    public static final String DIFF_TYPE_FUND = "FUND";

    /** 归因来源：由系统内成交记录推导。 */
    public static final String ATTR_TRADE_RECORD = "TRADE_RECORD";
    /** 归因来源：无法归属。 */
    public static final String ATTR_UNABLE = "UNABLE_TO_ATTRIBUTE";

    /** CTP 批次解析状态：已解析（完整）。冻结域 {@code {OK, PARTIAL, FAILED, NOT_ARRIVED}}（{@code m2_settle.sql:44}；{@code CH-021} 扩域）。 */
    public static final String PARSE_PARSED = "OK";
    /** CTP 批次解析状态：数据未到达（未发生解析），置待重试。 */
    public static final String PARSE_PENDING = "NOT_ARRIVED";
    /** CTP 批次解析状态：解析失败。 */
    public static final String PARSE_FAILED = "FAILED";

    /** 候选定位状态：待定位。 */
    public static final String CANDIDATE_PENDING = "PENDING";
    /** 候选定位状态：已确认归属。 */
    public static final String CANDIDATE_CONFIRMED = "CONFIRMED";

    /** 动作：新开仓。 */
    public static final String ACTION_OPEN = "OPEN";
    /** 动作：平仓（含风险处置）。 */
    public static final String ACTION_CLOSE = "CLOSE";
    /** 动作：资金划拨。 */
    public static final String ACTION_TRANSFER = "TRANSFER";
    /** 动作：日终关账。 */
    public static final String ACTION_DAY_CLOSE = "DAY_CLOSE";

    /** 加总对账类型：持仓。 */
    public static final String CHECK_POSITION = "POSITION";
    /** 加总对账类型：资金。 */
    public static final String CHECK_FUND = "FUND";
    /** 加总对账类型：盈亏。 */
    public static final String CHECK_PNL = "PNL";
    /** 加总对账类型：保证金。 */
    public static final String CHECK_MARGIN = "MARGIN";

    /** 阻断命中规则编号（{@code FR-SETTLE-8} / {@code R-SETTLE-2}）。 */
    public static final String RULE_AGG_BLOCK = "R-SETTLE-2";

    /** 报告类型：每日结算报告。 */
    public static final String REPORT_DAILY_SETTLE = "DAILY_SETTLE";
    /** 报告类型：风控日报。 */
    public static final String REPORT_DAILY_RISK = "DAILY_RISK";

    private final TxTemplate tx;
    private final SubAccountVisibility visibility;
    private final SettleCtpBatchDao batchDao;
    private final SettleCtpPositionDao ctpPositionDao;
    private final SettleCtpTradeDao ctpTradeDao;
    private final SettleCtpFundDao ctpFundDao;
    private final SettleCycleDao cycleDao;
    private final SettlePositionDao positionDao;
    private final SettleFundDao fundDao;
    private final SettlePnlDao pnlDao;
    private final SettleReconcileRecordDao reconcileRecordDao;
    private final SettleReconcileDifferenceDao differenceDao;
    private final SettleAggregationCheckDao aggregationCheckDao;
    private final SettleAggDiffCandidateDao candidateDao;
    private final SettleReportDao reportDao;
    private final SettleStressTestDao stressTestDao;

    /**
     * 重大事项亏损阈值触发器（{@code T-188}；{@code CFG-26}）；可空接缝（生产由
     * {@code Bootstrap} 装配）——{@code null} = 不启用自动触发，结算行为不变。
     */
    private volatile MajorEventLossThresholdTrigger lossThresholdTrigger;

    /**
     * 构造。
     *
     * @param tx                 事务模板
     * @param visibility         子账户可见性（可见集合取数 + 读/写面断言；生产为 {@code AuthService}）
     * @param batchDao           CTP 批次 DAO
     * @param ctpPositionDao     CTP 持仓 DAO
     * @param ctpTradeDao        CTP 成交 DAO
     * @param ctpFundDao         CTP 资金 DAO
     * @param cycleDao           结算单 DAO
     * @param positionDao        结算持仓 DAO
     * @param fundDao            结算资金 DAO
     * @param pnlDao             结算盈亏 DAO
     * @param reconcileRecordDao 核对记录 DAO
     * @param differenceDao      核对差异 DAO
     * @param aggregationCheckDao 加总对账 DAO
     * @param candidateDao       加总差异候选 DAO
     * @param reportDao          结算报告 DAO
     * @param stressTestDao      压力测试 DAO
     */
    public SettleService(TxTemplate tx, SubAccountVisibility visibility,
                         SettleCtpBatchDao batchDao, SettleCtpPositionDao ctpPositionDao,
                         SettleCtpTradeDao ctpTradeDao, SettleCtpFundDao ctpFundDao,
                         SettleCycleDao cycleDao, SettlePositionDao positionDao,
                         SettleFundDao fundDao, SettlePnlDao pnlDao,
                         SettleReconcileRecordDao reconcileRecordDao,
                         SettleReconcileDifferenceDao differenceDao,
                         SettleAggregationCheckDao aggregationCheckDao,
                         SettleAggDiffCandidateDao candidateDao,
                         SettleReportDao reportDao, SettleStressTestDao stressTestDao) {
        this.tx = tx;
        this.visibility = visibility;
        this.batchDao = batchDao;
        this.ctpPositionDao = ctpPositionDao;
        this.ctpTradeDao = ctpTradeDao;
        this.ctpFundDao = ctpFundDao;
        this.cycleDao = cycleDao;
        this.positionDao = positionDao;
        this.fundDao = fundDao;
        this.pnlDao = pnlDao;
        this.reconcileRecordDao = reconcileRecordDao;
        this.differenceDao = differenceDao;
        this.aggregationCheckDao = aggregationCheckDao;
        this.candidateDao = candidateDao;
        this.reportDao = reportDao;
        this.stressTestDao = stressTestDao;
    }

    // ================================================================ 一、CTP 日终入库（FR-SETTLE-1）

    /**
     * 接收 CTP 日终结算数据（{@code FR-SETTLE-1}；{@code POST /settle/ctp-data}）。
     *
     * <p>幂等：以 {@code (trade_date, source_identifier)} 唯一键判定；重复接收同一批次为**幂等 no-op**
     * （返回既有批次，不再插入明细）。数据未到达（持仓/资金/成交三者皆空）⇒ 批次置
     * {@code NOT_ARRIVED} + 记录未达原因并累加 {@code retry_count}，<b>不产生</b>任何基于推算的结算结果。
     *
     * @param req        接收请求
     * @param operatorId 操作人
     * @return 接收结果（批次号 / 是否待重试 / 三类明细条数）
     */
    public CtpIngestResult ingestCtpData(CtpDataRequest req, long operatorId) {
        if (req == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求体不得为空（FR-SETTLE-1）");
        }
        LocalDate tradeDate = parseDate(req.tradeDate, "tradeDate");
        String source = requireText(req.sourceIdentifier, "sourceIdentifier（来源标识，FR-SETTLE-1 验收4）");

        int posCount = req.positions == null ? 0 : req.positions.size();
        int fundCount = req.funds == null ? 0 : req.funds.size();
        int tradeCount = req.trades == null ? 0 : req.trades.size();
        boolean arrived = posCount > 0 || fundCount > 0 || tradeCount > 0;
        // T-189：逐合约盈亏字段覆盖面（旧模板兼容——缺列/空值不硬失败，回执披露）
        int[] pnlCoverage = positionPnlCoverage(req.positions);
        final int pnlPresent = pnlCoverage[0];
        final int pnlMissing = pnlCoverage[1];

        return tx.inTransaction(conn -> {
            SettleCtpBatch existing = batchDao.findByTradeDateAndSource(tradeDate, source);
            if (existing != null && "Y".equals(existing.isConfirmed)) {
                // 幂等：重复接收同一批次不再插入明细（以最后一次确认为准并留痕）
                return new CtpIngestResult(existing.id, false, existing.positionCount, existing.fundCount,
                        existing.tradeCount, pnlPresent, pnlMissing, "已接收（幂等 no-op）");
            }
            SettleCtpBatch batch = existing == null ? new SettleCtpBatch() : existing;
            batch.tradeDate = tradeDate;
            batch.sourceIdentifier = source;
            batch.receivedTime = Clocks.now();
            batch.positionCount = posCount;
            batch.fundCount = fundCount;
            batch.tradeCount = tradeCount;
            if (!arrived) {
                batch.parseStatus = PARSE_PENDING;
                batch.parseError = "CTP 日终数据未到达（不得用推算数据替代，R-SETTLE-3）";
                batch.retryCount = (batch.retryCount == null ? 0 : batch.retryCount) + 1;
                batch.lastRetryTime = Clocks.now();
                batch.isConfirmed = "N";
                if (existing == null) {
                    batchDao.insert(batch, operatorId);
                } else {
                    batchDao.updateById(batch, operatorId);
                }
                return new CtpIngestResult(batch.id, true, 0, 0, 0, 0, 0,
                        "数据未到达，已置待重试（NOT_ARRIVED）");
            }
            batch.parseStatus = PARSE_PARSED;
            batch.parseError = null;
            // 到达分支：清零重试计数（DDL DEFAULT 0 对显式 NULL 不生效，须显式置 0；TE-T058-005）。
            batch.retryCount = 0;
            batch.isConfirmed = "Y";
            if (existing == null) {
                batchDao.insert(batch, operatorId);
            } else {
                batchDao.updateById(batch, operatorId);
            }
            persistCtpDetails(batch, req, operatorId);
            String message = "接收成功";
            if (pnlMissing > 0) {
                // 旧模板兼容披露（T-189）：未携带逐合约盈亏的行，结算时不参与 CLOSE/FLOAT 转录（跳过，不硬失败）
                message = "接收成功；持仓盈亏字段缺失 " + pnlMissing + " 行（旧模板兼容：盈亏转录跳过该行）";
            }
            return new CtpIngestResult(batch.id, false, posCount, fundCount, tradeCount,
                    pnlPresent, pnlMissing, message);
        });
    }

    /**
     * 统计持仓行逐合约盈亏字段覆盖面（{@code T-189}）。
     *
     * @return {@code [携带行数, 未携带行数]}（缺列/空值均计为未携带，旧模板兼容）
     */
    private static int[] positionPnlCoverage(java.util.List<CtpPositionRow> positions) {
        int present = 0;
        int missing = 0;
        if (positions != null) {
            for (CtpPositionRow row : positions) {
                boolean carried = (row.closeProfit != null && !row.closeProfit.trim().isEmpty())
                        || (row.positionProfit != null && !row.positionProfit.trim().isEmpty());
                if (carried) {
                    present++;
                } else {
                    missing++;
                }
            }
        }
        return new int[] {present, missing};
    }

    private void persistCtpDetails(SettleCtpBatch batch, CtpDataRequest req, long operatorId) {
        if (req.positions != null) {
            for (CtpPositionRow row : req.positions) {
                SettleCtpPosition e = new SettleCtpPosition();
                e.tradeDate = batch.tradeDate;
                e.settleBatchId = batch.id;
                e.instrumentId = requireText(row.instrumentId, "positions[].instrumentId");
                e.direction = requireText(row.direction, "positions[].direction");
                e.positionType = requireText(row.positionType, "positions[].positionType");
                e.positionQty = nz(dec(row.positionQty));
                e.avgPrice = dec(row.avgPrice);
                e.settlementPrice = dec(row.settlementPrice);
                e.margin = dec(row.margin);
                ctpPositionDao.insert(e, operatorId);
            }
        }
        if (req.funds != null) {
            for (CtpFundRow row : req.funds) {
                SettleCtpFund e = new SettleCtpFund();
                e.tradeDate = batch.tradeDate;
                e.settleBatchId = batch.id;
                e.equity = nz(dec(row.equity));
                e.availableFund = nz(dec(row.availableFund));
                e.marginOccupied = nz(dec(row.marginOccupied));
                e.frozenMargin = dec(row.frozenMargin);
                e.commissionToday = nz(dec(row.commissionToday));
                e.depositToday = nz(dec(row.depositToday));
                e.withdrawToday = nz(dec(row.withdrawToday));
                e.closePnl = nz(dec(row.closePnl));
                e.floatPnl = nz(dec(row.floatPnl));
                ctpFundDao.insert(e, operatorId);
            }
        }
        if (req.trades != null) {
            for (CtpTradeRow row : req.trades) {
                SettleCtpTrade e = new SettleCtpTrade();
                e.tradeDate = batch.tradeDate;
                e.settleBatchId = batch.id;
                e.tradeId = requireText(row.tradeId, "trades[].tradeId");
                e.instrumentId = requireText(row.instrumentId, "trades[].instrumentId");
                e.direction = row.direction;
                e.offsetFlag = row.offsetFlag;
                e.fillPrice = dec(row.fillPrice);
                e.fillVolume = row.fillVolume;
                e.tradeTsMs = row.tradeTsMs;
                e.commission = dec(row.commission);
                ctpTradeDao.insert(e, operatorId);
            }
        }
    }

    // ================================================================ 二、拆分 + 独立结算 + 三类核对（FR-SETTLE-2/3/4/5/6/7）

    /**
     * 执行子账户拆分与独立日终结算，并随后完成三类核对（{@code FR-SETTLE-2/3/4} + {@code 5/6/7}）。
     *
     * <p>顺序：① 取 CTP 当日明细（缺失即 {@code DATA_INSUFFICIENT}，不推算）→
     * ② 按子账户聚合系统内持仓/资金，生成 {@code t_settle_cycle}（每子账户一份，含平仓/浮动盈亏、
     * 手续费、保证金占用、可用资金、权益、风险度六项）→ ③ 持久化 {@code t_settle_position}/
     * {@code t_settle_fund}/{@code t_settle_pnl}（逐行携带子账户，可直接求和；
     * {@code t_settle_pnl} 本期落 {@code COMMISSION} 转录行——{@code CLOSE_REALIZED}/
     * {@code FLOAT_UNREALIZED} 的数据源扩列裁定见 {@link #transcribeCommissionPnl}，
     * 冻结面升级中、批准前不落，勿误读为「无亏损」）→
     * ④ 与 CTP 台账逐子账户勾稽（不等即记资金差异）→ ⑤ 三类核对（成交/仓位/资金）。</p>
     *
     * <p>子账户资金为负时置 {@code negative_fund_flag='Y'} 并记差异（{@code FR-SETTLE-4} 验收3）。</p>
     *
     * @param input      系统内按子账户数据（接缝；生产由 T-172 事件接线供给）
     * @param operatorId 操作人
     * @return 本次生成的结算单（按子账户）
     */
    public List<SettleCycle> runSettlement(SettlementInput input, long operatorId) {
        if (input == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "结算入参不得为空（FR-SETTLE-2）");
        }
        LocalDate date = parseDate(input.tradeDate, "tradeDate");

        List<SettleCtpPosition> ctpPositions = queryCtpPositions(date);
        List<SettleCtpFund> ctpFunds = queryCtpFunds(date);
        List<SettleCtpTrade> ctpTrades = queryCtpTrades(date);
        if (ctpPositions.isEmpty() && ctpFunds.isEmpty()) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "CTP 日终数据未到达，拒绝结算（R-SETTLE-3 不得用推算数据替代）：tradeDate=" + date);
        }

        List<SettleCycle> cycles = tx.inTransaction(conn -> {
            Map<Integer, List<SystemPositionRow>> posBySub = groupPositions(input.positions, date);
            Map<Integer, SystemFundRow> fundBySub = indexFunds(input.funds, date);

            Set<Integer> subs = new LinkedHashSet<>();
            subs.addAll(posBySub.keySet());
            subs.addAll(fundBySub.keySet());

            // T-189：逐合约手续费聚合（Σ CtpTradeRow.commission，加总非推算，裁定授权）
            Map<String, BigDecimal> commissionByInstrument = new LinkedHashMap<>();
            for (SettleCtpTrade t : ctpTrades) {
                if (t.instrumentId != null) {
                    commissionByInstrument.merge(t.instrumentId, nz(t.commission), BigDecimal::add);
                }
            }
            // 归属辅助：合约 → 系统内持仓子账户去重数（>1 ⇒ 合并持仓拆分不出，不默认归入）
            Map<String, Integer> subsByInstrument = new LinkedHashMap<>();
            for (List<SystemPositionRow> rows : posBySub.values()) {
                for (SystemPositionRow r : rows) {
                    subsByInstrument.merge(r.instrumentId, 1, Integer::sum);
                }
            }
            // 幂等键集：当日已存在的 (date|sub|instrument|pnl_type)，重复结算跳过（不重插）
            Set<String> existingPnlKeys = new LinkedHashSet<>();
            for (SettlePnl p : pnlDao.findByTradeDate(date)) {
                existingPnlKeys.add(pnlKey(p.tradeDate, p.subAccountId, p.instrumentId, p.pnlType));
            }

            List<SettleCycle> out = new ArrayList<>();
            for (Integer subId : subs) {
                if (subId == null || subId <= 0) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "系统内数据的 subAccountId 必须为正（行级归属恒 > 0，DATA_MODEL.md §4.3）：值=" + subId);
                }
                SettleCycle cycle = upsertCycle(date, subId, operatorId);
                if (cycle == null) {
                    continue; // 已确认关账，拒绝重算（PERIOD_CLOSED 由 upsertCycle 抛）
                }
                applyPositionSplit(cycle, posBySub.getOrDefault(subId, Collections.emptyList()), operatorId);
                applyFund(cycle, fundBySub.get(subId), operatorId);
                // T-189：结算盈亏明细转录（同结算事务：结算成功 ⇔ 盈亏明细落库原子生效；
                // 轨 1 触发器在事务提交后读取，见 evaluateSettled 接线点）
                transcribeCommissionPnl(cycle, posBySub, commissionByInstrument, subsByInstrument,
                        existingPnlKeys, operatorId);
                cycle.status = CYCLE_GENERATED;
                cycleDao.updateById(cycle, operatorId);
                out.add(cycle);
            }
            return out;
        });

        // 三类核对（在结算落库后执行；差异逐条定位）
        for (SettleCycle cycle : cycles) {
            reconcileInternal(cycle, ctpPositions, ctpFunds, ctpTrades,
                    input.trades == null ? Collections.emptyList() : input.trades, operatorId);
        }

        // T-188 轨 1（EOD 已实现，记账级）：结算落库事务提交后，按 t_settle_pnl 子账户当日求和
        // 判 CFG-26 亏损阈值，越限落 t_major_event（LOSS_THRESHOLD/TRIGGERED）。旁路能力：
        // 任何失败只记日志、不阻断结算主流程（幂等键按自然日，同日重跑结算天然重试）。
        if (lossThresholdTrigger != null) {
            try {
                lossThresholdTrigger.evaluateSettled(date);
            } catch (RuntimeException e) {
                LOG.severe("重大事项亏损阈值触发（EOD 轨）失败，不阻断结算主流程（T-188）：tradeDate="
                        + date + "；原因=" + e.getClass().getSimpleName() + "：" + e.getMessage());
            }
        }
        return cycles;
    }

    /**
     * 装配重大事项亏损阈值触发器（{@code T-188}；可空接缝，生产由 {@code Bootstrap} 装配）。
     *
     * @param trigger 触发器；{@code null} = 不启用自动触发
     */
    public void setLossThresholdTrigger(MajorEventLossThresholdTrigger trigger) {
        this.lossThresholdTrigger = trigger;
    }

    private SettleCycle upsertCycle(LocalDate date, int subId, long operatorId) {
        SettleCycle existing = findCycle(date, subId);
        if (existing != null) {
            if (CYCLE_CONFIRMED.equals(existing.status)) {
                throw BizException.of(ErrorCode.PERIOD_CLOSED,
                        "该子账户当日结算单已确认关账，重算须先作废（R-SETTLE-5）：tradeDate=" + date
                                + " subAccountId=" + subId);
            }
            return existing; // 幂等：同一交易日同一子账户只出一份有效结算单
        }
        SettleCycle cycle = new SettleCycle();
        cycle.subAccountId = subId;
        cycle.tradeDate = date;
        cycle.isAggregate = "N";
        cycle.status = CYCLE_PENDING;
        cycle.settleNo = BizNoGenerator.settleCycle(date, String.valueOf(subId));
        cycle.closePnl = BigDecimal.ZERO;
        cycle.floatPnl = BigDecimal.ZERO;
        cycle.commission = BigDecimal.ZERO;
        cycle.marginOccupied = BigDecimal.ZERO;
        cycle.availableFund = BigDecimal.ZERO;
        cycle.equity = BigDecimal.ZERO;
        cycle.negativeFundFlag = "N";
        cycle.voidedFlag = "N";
        cycleDao.insert(cycle, operatorId);
        return cycle;
    }

    private void applyPositionSplit(SettleCycle cycle, List<SystemPositionRow> rows, long operatorId) {
        Map<String, BigDecimal> agg = new TreeMap<>();
        for (SystemPositionRow r : rows) {
            String key = r.instrumentId + "|" + r.direction + "|" + r.positionType;
            agg.merge(key, nz(dec(r.positionQty)), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : agg.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            SettlePosition p = new SettlePosition();
            p.subAccountId = cycle.subAccountId;
            p.settleCycleId = cycle.id;
            p.tradeDate = cycle.tradeDate;
            p.instrumentId = parts[0];
            p.productId = 0L;
            p.instrumentType = "FUTURES";
            p.direction = parts[1];
            p.positionType = parts[2];
            p.positionQty = e.getValue();
            p.attributionSource = ATTR_TRADE_RECORD;
            for (SystemPositionRow r : rows) {
                if (parts[0].equals(r.instrumentId) && parts[1].equals(r.direction)
                        && parts[2].equals(r.positionType)) {
                    if (r.productId != null) {
                        p.productId = r.productId;
                    }
                    if (r.instrumentType != null) {
                        p.instrumentType = r.instrumentType;
                    }
                    break;
                }
            }
            positionDao.insert(p, operatorId);
        }
    }

    private void applyFund(SettleCycle cycle, SystemFundRow row, long operatorId) {
        SettleFund f = new SettleFund();
        f.subAccountId = cycle.subAccountId;
        f.settleCycleId = cycle.id;
        f.tradeDate = cycle.tradeDate;
        if (row == null) {
            f.beginBalance = BigDecimal.ZERO;
            f.equity = BigDecimal.ZERO;
            f.availableFund = BigDecimal.ZERO;
            f.marginOccupied = BigDecimal.ZERO;
            f.frozenMargin = BigDecimal.ZERO;
            f.amountIn = BigDecimal.ZERO;
            f.amountOut = BigDecimal.ZERO;
            f.transferIn = BigDecimal.ZERO;
            f.transferOut = BigDecimal.ZERO;
            f.closePnl = BigDecimal.ZERO;
            f.floatPnl = BigDecimal.ZERO;
            f.commission = BigDecimal.ZERO;
            f.marginChange = BigDecimal.ZERO;
            f.endBalance = BigDecimal.ZERO;
            f.attributionSource = ATTR_TRADE_RECORD;
        } else {
            f.beginBalance = nz(dec(row.beginBalance));
            f.equity = nz(dec(row.equity));
            f.availableFund = nz(dec(row.availableFund));
            f.marginOccupied = nz(dec(row.marginOccupied));
            f.frozenMargin = nz(dec(row.frozenMargin));
            f.amountIn = nz(dec(row.amountIn));
            f.amountOut = nz(dec(row.amountOut));
            f.transferIn = nz(dec(row.transferIn));
            f.transferOut = nz(dec(row.transferOut));
            f.closePnl = nz(dec(row.closePnl));
            f.floatPnl = nz(dec(row.floatPnl));
            f.commission = nz(dec(row.commission));
            f.marginChange = nz(dec(row.marginChange));
            f.endBalance = dec(row.endBalance);
            if (f.endBalance == null) {
                f.endBalance = f.beginBalance.add(f.amountIn).subtract(f.amountOut)
                        .add(f.transferIn).subtract(f.transferOut)
                        .add(f.closePnl).add(f.floatPnl).subtract(f.commission).add(f.marginChange);
            }
            f.attributionSource = row.attributionSource == null ? "LEDGER" : row.attributionSource;
        }
        f.fundLedgerId = null;
        fundDao.insert(f, operatorId);

        // 结算资金与 FUND 台账勾稽（R-SETTLE-8）：不等即记差异（并成为加总差异的候选归属）
        if (row != null && row.ledgerEquity != null && dec(row.ledgerEquity) != null
                && f.equity.compareTo(dec(row.ledgerEquity)) != 0) {
            recordDifference(cycle, null, DIFF_TYPE_FUND, KIND_VALUE_MISMATCH,
                    "subAccountId=" + cycle.subAccountId, "equity",
                    f.equity.toPlainString(), dec(row.ledgerEquity).toPlainString(),
                    null, f.equity.subtract(dec(row.ledgerEquity)), operatorId);
        }

        // 结算单六项
        cycle.closePnl = f.closePnl;
        cycle.floatPnl = f.floatPnl;
        cycle.commission = f.commission;
        cycle.marginOccupied = f.marginOccupied;
        cycle.availableFund = f.availableFund;
        cycle.equity = f.equity;
        if (f.equity.signum() > 0) {
            cycle.riskRatio = f.marginOccupied.divide(f.equity, 6, RoundingMode.HALF_UP);
        } else {
            cycle.riskRatio = null;
        }
        if (f.equity.signum() < 0) {
            cycle.negativeFundFlag = "Y";
            LOG.warning("子账户资金为负，进入差异处理（FR-SETTLE-4 验收3）：subAccountId="
                    + cycle.subAccountId + " equity=" + f.equity);
        } else {
            cycle.negativeFundFlag = "N";
        }
    }

    /**
     * 结算盈亏明细转录（{@code T-189}；落 {@code t_settle_pnl}，R-SETTLE-9 子账户贯穿可加总）。
     *
     * <p><b>本期范围（裁定授权）</b>：{@code COMMISSION} 行 = 该合约当日 CTP 成交手续费聚合
     * （Σ {@code CtpTradeRow.commission}，<b>加总非推算</b>，符号语义照转录原值/DDL「金额（可为负）」）。
     * {@code CLOSE_REALIZED}/{@code FLOAT_UNREALIZED} 行的数据源（导入契约扩列）已裁方案 A，
     * 但其<b>中间持久化承载</b>须扩 {@code TB-SETTLE-2}（DATA_MODEL 冻结面，
     * {@code M2SettleSchemaTest} 以 DATA_MODEL §2.7 为期望逐列锁 DDL）⇒ 按派单停手条款升级 CH，
     * 批准前不落该两类行（诚实披露，勿误读为「无亏损」）。
     *
     * <p><b>归属规则</b>（无法避免的归属设计，采纳最保守形态）：CTP 明细为账户级合并数据
     * （DATA_MODEL 例外 E1，无子账户维度），归属借结算输入的系统内持仓（子账户维度由业务侧确定，
     * DATA_MODEL §4.6.3）：仅当合约当日在<b>恰好一个</b>结算子账户名下有持仓时转录给该子账户；
     * 多子账户共用合约时合并值拆分不出 ⇒ <b>跳过并告警</b>，不默认归入（先例
     * {@code reconcilePositions} 仓位差异「无法归属不默认归入」）。合约当日无 CTP 成交 ⇒ 无行。
     *
     * <p><b>幂等</b>：跳过式——同 uk 键 (trade_date, sub_account_id, instrument_id, pnl_type)
     * 已存在即跳过（不重插、不重算）。相对「先删后插」的取舍：先删后插需新增 DAO 删除方法并回写
     * {@code CONTRACTS §2.2.1}（本单冻结面），且重复接收同一批次本就幂等 no-op、明细不可变，
     * 跳过式已满足重跑安全；副作用 = 若换 source 重导且手续费变化，旧值保留（重跑姿态披露）。
     *
     * @param cycle                  当前结算单（子账户）
     * @param posBySub               系统内持仓（按子账户）
     * @param commissionByInstrument 合约 → 当日 CTP 成交手续费合计（仅含有成交的合约）
     * @param subsByInstrument       合约 → 持有该合约的系统内子账户数
     * @param existingPnlKeys        当日已存在的盈亏行幂等键集（本事务内累计）
     * @param operatorId             操作人
     */
    private void transcribeCommissionPnl(SettleCycle cycle, Map<Integer, List<SystemPositionRow>> posBySub,
                                         Map<String, BigDecimal> commissionByInstrument,
                                         Map<String, Integer> subsByInstrument, Set<String> existingPnlKeys,
                                         long operatorId) {
        List<SystemPositionRow> rows = posBySub.getOrDefault(cycle.subAccountId, Collections.emptyList());
        Set<String> instruments = new LinkedHashSet<>();
        Map<String, Long> productByInstrument = new LinkedHashMap<>();
        for (SystemPositionRow r : rows) {
            instruments.add(r.instrumentId);
            if (r.productId != null && !productByInstrument.containsKey(r.instrumentId)) {
                productByInstrument.put(r.instrumentId, r.productId);
            }
        }
        for (String instrument : instruments) {
            if (!commissionByInstrument.containsKey(instrument)) {
                continue; // 合约当日无 CTP 成交 ⇒ 无手续费可转录
            }
            Integer holders = subsByInstrument.getOrDefault(instrument, 0);
            if (holders != 1) {
                // 合并持仓拆分不出子账户：不默认归入（不得重复计入多个子账户破坏可加总性）
                LOG.warning("合约盈亏归属歧义（多子账户持有，跳过转录，T-189）：tradeDate=" + cycle.tradeDate
                        + " instrumentId=" + instrument + " 子账户数=" + holders);
                continue;
            }
            Long productId = productByInstrument.get(instrument);
            if (productId == null) {
                // t_settle_pnl.product_id NOT NULL + FK → t_product：无品种归属不得伪造哨兵（FK 硬约束）
                LOG.warning("合约缺品种归属，跳过手续费转录（T-189）：tradeDate=" + cycle.tradeDate
                        + " instrumentId=" + instrument);
                continue;
            }
            String key = pnlKey(cycle.tradeDate, cycle.subAccountId, instrument, PNL_TYPE_COMMISSION);
            if (existingPnlKeys.contains(key)) {
                continue; // 幂等：重复结算跳过（见方法 javadoc 取舍）
            }
            SettlePnl p = new SettlePnl();
            p.subAccountId = cycle.subAccountId;
            p.settleCycleId = cycle.id;
            p.tradeDate = cycle.tradeDate;
            p.instrumentId = instrument;
            p.productId = productId;
            p.hedgePlanId = null;
            p.pnlType = PNL_TYPE_COMMISSION;
            p.amount = commissionByInstrument.get(instrument);
            p.qty = null;
            pnlDao.insert(p, operatorId);
            existingPnlKeys.add(key);
        }
    }

    /** 盈亏行幂等键（对齐 uk_settle_pnl_key）。 */
    private static String pnlKey(LocalDate date, Integer subAccountId, String instrumentId, String pnlType) {
        return date + "|" + subAccountId + "|" + instrumentId + "|" + pnlType;
    }

    private void recordDifference(SettleCycle cycle, Long reconcileRecordId, String diffType, String kind,
                                  String bizKey, String field, String systemValue, String ctpValue,
                                  BigDecimal diffQty, BigDecimal diffAmount, long operatorId) {
        SettleReconcileDifference d = new SettleReconcileDifference();
        d.subAccountId = cycle == null ? null : cycle.subAccountId;
        d.tradeDate = cycle == null ? null : cycle.tradeDate;
        d.reconcileRecordId = reconcileRecordId;
        d.differenceType = diffType;
        d.differenceKind = kind;
        d.bizKey = bizKey;
        d.fieldName = field;
        d.systemValue = systemValue;
        d.ctpValue = ctpValue;
        d.diffQty = diffQty;
        d.diffAmount = diffAmount;
        d.handleStatus = DIFF_PENDING;
        d.foundTime = Clocks.now();
        differenceDao.insert(d, operatorId);
    }

    /** 记录无归属差异（{@code sub_account_id} 为 {@code null}；{@code FR-SETTLE-2} 验收2）。 */
    private void recordUnattributedDifference(LocalDate date, Long reconcileRecordId, String diffType, String kind,
                                              String bizKey, String field, String systemValue, String ctpValue,
                                              BigDecimal diffQty, BigDecimal diffAmount, long operatorId) {
        SettleReconcileDifference d = new SettleReconcileDifference();
        d.subAccountId = null;
        d.tradeDate = date;
        d.reconcileRecordId = reconcileRecordId;
        d.differenceType = diffType;
        d.differenceKind = kind;
        d.bizKey = bizKey;
        d.fieldName = field;
        d.systemValue = systemValue;
        d.ctpValue = ctpValue;
        d.diffQty = diffQty;
        d.diffAmount = diffAmount;
        d.handleStatus = DIFF_PENDING;
        d.foundTime = Clocks.now();
        differenceDao.insert(d, operatorId);
    }

    /**
     * 重跑某结算单的三类核对（{@code POST /settle/cycle/{id}/reconcile}）。
     *
     * @param cycleId     结算单号
     * @param sysTrades   系统内成交（接缝；供成交核对逐笔比对）
     * @param operatorId  操作人
     * @return 三类核对结论
     */
    public ReconcileResult reconcile(long cycleId, List<SystemTradeRow> sysTrades, long operatorId) {
        SettleCycle cycle = requireCycle(cycleId);
        visibility.assertSubAccountVisible(operatorId, cycle.subAccountId);
        List<SettleCtpPosition> ctpPositions = queryCtpPositions(cycle.tradeDate);
        List<SettleCtpFund> ctpFunds = queryCtpFunds(cycle.tradeDate);
        List<SettleCtpTrade> ctpTrades = queryCtpTrades(cycle.tradeDate);
        return tx.inTransaction(conn -> reconcileInternal(cycle, ctpPositions, ctpFunds, ctpTrades,
                sysTrades == null ? Collections.emptyList() : sysTrades, operatorId));
    }

    private ReconcileResult reconcileInternal(SettleCycle cycle, List<SettleCtpPosition> ctpPositions,
                                              List<SettleCtpFund> ctpFunds, List<SettleCtpTrade> ctpTrades,
                                              List<SystemTradeRow> sysTrades, long operatorId) {
        ReconcileResult result = new ReconcileResult();
        result.trade = reconcileTrades(cycle, ctpTrades, sysTrades, operatorId);
        result.position = reconcilePositions(cycle, ctpPositions, operatorId);
        result.fund = reconcileFund(cycle, ctpFunds, operatorId);
        boolean allOk = RESULT_CONSISTENT.equals(result.trade.result)
                && RESULT_CONSISTENT.equals(result.position.result)
                && RESULT_CONSISTENT.equals(result.fund.result);
        SettleCycle fresh = cycleDao.findById(cycle.id);
        if (fresh != null && CYCLE_GENERATED.equals(fresh.status) && allOk) {
            fresh.status = CYCLE_RECONCILED;
            cycleDao.updateById(fresh, operatorId);
        }
        return result;
    }

    private ReconcileLine reconcilePositions(SettleCycle cycle, List<SettleCtpPosition> ctpPositions, long operatorId) {
        Map<String, BigDecimal> sys = new TreeMap<>();
        PageResult<SettlePosition> page = positionDao.findPage(
                PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Collections.singletonList((Object) cycle.tradeDate));
        for (SettlePosition p : page.items()) {
            sys.merge(p.instrumentId + "|" + p.direction + "|" + p.positionType, nz(p.positionQty), BigDecimal::add);
        }
        Map<String, BigDecimal> ctp = new TreeMap<>();
        for (SettleCtpPosition p : ctpPositions) {
            ctp.merge(p.instrumentId + "|" + p.direction + "|" + p.positionType, nz(p.positionQty), BigDecimal::add);
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(sys.keySet());
        keys.addAll(ctp.keySet());
        int diffCount = 0;
        SettleReconcileRecord record = newRecord(cycle, DIFF_TYPE_POSITION, sys.size(), ctp.size(), operatorId);
        recordDaoInsertPlaceholder(record, operatorId);
        for (String key : keys) {
            BigDecimal s = nz(sys.get(key));
            BigDecimal c = nz(ctp.get(key));
            if (s.compareTo(c) != 0) {
                diffCount++;
                String kind = s.compareTo(c) < 0 ? KIND_CTP_ONLY : KIND_SYS_ONLY;
                // 归属：仓位差异可能无法定位到子账户（合并持仓拆分不出）⇒ sub_account_id 为空并告警
                recordUnattributedDifference(cycle.tradeDate, record.id, DIFF_TYPE_POSITION, kind, key,
                        "positionQty", s.toPlainString(), c.toPlainString(), c.subtract(s), null, operatorId);
                LOG.warning("仓位核对差异（无法归属子账户，不得默认归入）：bizKey=" + key
                        + " 系统=" + s + " CTP=" + c);
            }
        }
        record.result = diffCount == 0 ? RESULT_CONSISTENT : RESULT_HAS_DIFFERENCE;
        record.differenceCount = diffCount;
        recordDaoUpdate(record, operatorId);
        return new ReconcileLine(DIFF_TYPE_POSITION, record.result, diffCount, record.id);
    }

    /**
     * 资金核对（{@code FR-SETTLE-7}）：账户级「Σ 子账户 vs CTP 账户」，逐项比对
     * <b>权益、可用资金、占用保证金、当日出入金（入金/出金两向）、当日手续费</b>；
     * 任意一项不一致 ⇒ 产生可定位到该项的 {@code FUND} 差异（未闭环前阻断关账，不得只告警不阻断）。
     */
    private ReconcileLine reconcileFund(SettleCycle cycle, List<SettleCtpFund> ctpFunds, long operatorId) {
        // 系统侧：Σ 全部子账户（CTP 资金为账户级合并值，故不按单子账户比较）
        BigDecimal[] sys = sumFundColumns(cycle);
        BigDecimal ctpEquity = BigDecimal.ZERO;
        BigDecimal ctpAvailable = BigDecimal.ZERO;
        BigDecimal ctpMargin = BigDecimal.ZERO;
        BigDecimal ctpCommission = BigDecimal.ZERO;
        BigDecimal ctpDeposit = BigDecimal.ZERO;
        BigDecimal ctpWithdraw = BigDecimal.ZERO;
        for (SettleCtpFund f : ctpFunds) {
            ctpEquity = ctpEquity.add(nz(f.equity));
            ctpAvailable = ctpAvailable.add(nz(f.availableFund));
            ctpMargin = ctpMargin.add(nz(f.marginOccupied));
            ctpCommission = ctpCommission.add(nz(f.commissionToday));
            ctpDeposit = ctpDeposit.add(nz(f.depositToday));
            ctpWithdraw = ctpWithdraw.add(nz(f.withdrawToday));
        }
        int diffCount = 0;
        SettleReconcileRecord record = newRecord(cycle, DIFF_TYPE_FUND,
                countFund(cycle), ctpFunds.size(), operatorId);
        recordDaoInsertPlaceholder(record, operatorId);
        diffCount += compareFundItem(cycle, record, "equity", sys[0], ctpEquity, operatorId);
        diffCount += compareFundItem(cycle, record, "availableFund", sys[1], ctpAvailable, operatorId);
        diffCount += compareFundItem(cycle, record, "marginOccupied", sys[2], ctpMargin, operatorId);
        diffCount += compareFundItem(cycle, record, "amountIn", sys[3], ctpDeposit, operatorId);
        diffCount += compareFundItem(cycle, record, "amountOut", sys[4], ctpWithdraw, operatorId);
        diffCount += compareFundItem(cycle, record, "commission", sys[5], ctpCommission, operatorId);
        record.result = diffCount == 0 ? RESULT_CONSISTENT : RESULT_HAS_DIFFERENCE;
        record.differenceCount = diffCount;
        recordDaoUpdate(record, operatorId);
        return new ReconcileLine(DIFF_TYPE_FUND, record.result, diffCount, record.id);
    }

    /**
     * 逐项比对资金核对项；不一致 ⇒ 记录可定位到具体项的 {@code FUND} 差异（{@code FR-SETTLE-7} 验收2）。
     *
     * @return 不一致返回 1，否则 0（供汇总 {@code differenceCount}）
     */
    private int compareFundItem(SettleCycle cycle, SettleReconcileRecord record, String field,
                                BigDecimal sysValue, BigDecimal ctpValue, long operatorId) {
        BigDecimal s = nz(sysValue);
        BigDecimal c = nz(ctpValue);
        if (s.compareTo(c) == 0) {
            return 0;
        }
        recordUnattributedDifference(cycle.tradeDate, record.id, DIFF_TYPE_FUND,
                s.compareTo(c) < 0 ? KIND_CTP_ONLY : KIND_SYS_ONLY, field, field,
                s.toPlainString(), c.toPlainString(), null, s.subtract(c), operatorId);
        LOG.warning("资金核对差异（FR-SETTLE-7）：tradeDate=" + cycle.tradeDate + " 项=" + field
                + " 系统=" + s.toPlainString() + " CTP=" + c.toPlainString());
        return 1;
    }

    private ReconcileLine reconcileTrades(SettleCycle cycle, List<SettleCtpTrade> ctpTrades,
                                          List<SystemTradeRow> sysTrades, long operatorId) {
        Map<String, SystemTradeRow> sys = new LinkedHashMap<>();
        for (SystemTradeRow r : sysTrades) {
            if (r.tradeId != null) {
                sys.put(r.tradeId, r);
            }
        }
        Map<String, SettleCtpTrade> ctp = new LinkedHashMap<>();
        for (SettleCtpTrade t : ctpTrades) {
            ctp.put(t.tradeId, t);
        }
        int diffCount = 0;
        SettleReconcileRecord record = newRecord(cycle, DIFF_TYPE_TRADE, sys.size(), ctp.size(), operatorId);
        if (sysTrades.isEmpty() && !ctpTrades.isEmpty()) {
            // 系统内成交接缝未供给 ⇒ 未完成（不得静默判一致）
            record.result = RESULT_NOT_COMPLETED;
            record.remark = "系统内成交记录未供给（事件接线归 T-172），成交核对未完成";
            record.differenceCount = 0;
            recordDaoInsertPlaceholder(record, operatorId);
            recordDaoUpdate(record, operatorId);
            return new ReconcileLine(DIFF_TYPE_TRADE, RESULT_NOT_COMPLETED, 0, record.id);
        }
        recordDaoInsertPlaceholder(record, operatorId);
        for (String tradeId : ctp.keySet()) {
            if (!sys.containsKey(tradeId)) {
                diffCount++;
                recordUnattributedDifference(cycle.tradeDate, record.id, DIFF_TYPE_TRADE, KIND_CTP_ONLY,
                        tradeId, null, null, "CTP 成交=" + tradeId, null, null, operatorId);
            }
        }
        for (Map.Entry<String, SystemTradeRow> e : sys.entrySet()) {
            SettleCtpTrade t = ctp.get(e.getKey());
            if (t == null) {
                diffCount++;
                SettleReconcileDifference d = new SettleReconcileDifference();
                d.subAccountId = e.getValue().subAccountId;
                d.tradeDate = cycle.tradeDate;
                d.reconcileRecordId = record.id;
                d.differenceType = DIFF_TYPE_TRADE;
                d.differenceKind = KIND_SYS_ONLY;
                d.bizKey = e.getKey();
                d.handleStatus = DIFF_PENDING;
                d.foundTime = Clocks.now();
                differenceDao.insert(d, operatorId);
            } else if (e.getValue().fillPrice != null && t.fillPrice != null
                    && dec(e.getValue().fillPrice) != null
                    && dec(e.getValue().fillPrice).compareTo(t.fillPrice) != 0) {
                diffCount++;
                SettleReconcileDifference d = new SettleReconcileDifference();
                d.subAccountId = e.getValue().subAccountId;
                d.tradeDate = cycle.tradeDate;
                d.reconcileRecordId = record.id;
                d.differenceType = DIFF_TYPE_TRADE;
                d.differenceKind = KIND_VALUE_MISMATCH;
                d.bizKey = e.getKey();
                d.fieldName = "fillPrice";
                d.systemValue = dec(e.getValue().fillPrice).toPlainString();
                d.ctpValue = t.fillPrice.toPlainString();
                d.handleStatus = DIFF_PENDING;
                d.foundTime = Clocks.now();
                differenceDao.insert(d, operatorId);
            }
        }
        record.result = diffCount == 0 ? RESULT_CONSISTENT : RESULT_HAS_DIFFERENCE;
        record.differenceCount = diffCount;
        recordDaoUpdate(record, operatorId);
        return new ReconcileLine(DIFF_TYPE_TRADE, record.result, diffCount, record.id);
    }

    private SettleReconcileRecord newRecord(SettleCycle cycle, String type, int sysCount, int ctpCount,
                                            long operatorId) {
        SettleReconcileRecord r = new SettleReconcileRecord();
        r.subAccountId = cycle.subAccountId;
        r.tradeDate = cycle.tradeDate;
        r.reconcileType = type;
        r.compareGranularity = DIFF_TYPE_TRADE.equals(type) ? "BY_RECORD" : "BY_SUMMARY";
        r.systemCount = sysCount;
        r.ctpCount = ctpCount;
        r.result = RESULT_CONSISTENT;
        r.differenceCount = 0;
        r.operatorId = operatorId;
        r.reconcileTime = Clocks.now();
        return r;
    }

    /** 先插入核对头，取得主键后再回填差异（占位方法名保持可读）。 */
    private void recordDaoInsertPlaceholder(SettleReconcileRecord record, long operatorId) {
        reconcileRecordDao.insert(record, operatorId);
    }

    private void recordDaoUpdate(SettleReconcileRecord record, long operatorId) {
        reconcileRecordDao.updateById(record, operatorId);
    }

    /**
     * 账户级系统侧资金合计：Σ 全部子账户结算资金，按核对项返回
     * {@code [权益, 可用资金, 占用保证金, 当日入金, 当日出金, 当日手续费]}
     * （{@code FR-SETTLE-7} 资金核对 = Σ 子账户 vs CTP 账户；CTP 资金为账户级合并值
     * {@code t_ctp_settle_fund} 单行，故<b>不按单子账户</b>比较）。
     */
    private BigDecimal[] sumFundColumns(SettleCycle cycle) {
        PageResult<SettleFund> page = fundDao.findPage(
                PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Collections.singletonList((Object) cycle.tradeDate));
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        BigDecimal margin = BigDecimal.ZERO;
        BigDecimal amountIn = BigDecimal.ZERO;
        BigDecimal amountOut = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        for (SettleFund f : page.items()) {
            equity = equity.add(nz(f.equity));
            available = available.add(nz(f.availableFund));
            margin = margin.add(nz(f.marginOccupied));
            amountIn = amountIn.add(nz(f.amountIn));
            amountOut = amountOut.add(nz(f.amountOut));
            commission = commission.add(nz(f.commission));
        }
        return new BigDecimal[] {equity, available, margin, amountIn, amountOut, commission};
    }

    private int countFund(SettleCycle cycle) {
        PageResult<SettleFund> page = fundDao.findPage(
                PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Collections.singletonList((Object) cycle.tradeDate));
        return page.items().size();
    }

    // ================================================================ 三、加总对账（FR-SETTLE-8）

    /**
     * 执行加总对账（{@code POST /settle/cycle/{id}/aggregate}；{@code FR-SETTLE-8}）。
     *
     * <p>对 POSITION/FUND/PNL/MARGIN 四类逐一校验 {@code Σ 子账户 = 真实账户}；
     * 不一致 ⇒ 结论 {@code INCONSISTENT_BLOCKED}、{@code block_scope='DIFF_SUB_NON_CLOSE'}、
     * 阻断与告警时间非空，并生成结构化候选（{@code t_agg_diff_candidate}）与全账户告警。</p>
     *
     * @param cycleId    结算单号（取该单交易日为对账日）
     * @param operatorId 操作人
     * @return 对账结论（含阻断子账户集合）
     */
    public AggregateResult aggregate(long cycleId, long operatorId) {
        SettleCycle cycle = requireCycle(cycleId);
        visibility.assertSubAccountVisible(operatorId, cycle.subAccountId);
        LocalDate date = cycle.tradeDate;
        List<SettleCycle> subs = findSubCycles(date);
        List<SettleCtpPosition> ctpPositions = queryCtpPositions(date);
        List<SettleCtpFund> ctpFunds = queryCtpFunds(date);

        return tx.inTransaction(conn -> {
            AggregateResult res = new AggregateResult();
            res.tradeDate = date;
            res.blockedSubAccountIds = new LinkedHashSet<>();
            res.checks = new ArrayList<>();

            BigDecimal sysPos = sumPositionQty(date);
            BigDecimal ctpPos = BigDecimal.ZERO;
            for (SettleCtpPosition p : ctpPositions) {
                ctpPos = ctpPos.add(nz(p.positionQty));
            }
            BigDecimal sysEquity = sumCycleColumn(date, "equity");
            BigDecimal sysPnl = sumCycleColumn(date, "pnl");
            BigDecimal sysMargin = sumCycleColumn(date, "margin");
            BigDecimal ctpEquity = BigDecimal.ZERO;
            BigDecimal ctpPnl = BigDecimal.ZERO;
            BigDecimal ctpMargin = BigDecimal.ZERO;
            for (SettleCtpFund f : ctpFunds) {
                ctpEquity = ctpEquity.add(nz(f.equity));
                ctpPnl = ctpPnl.add(nz(f.closePnl)).add(nz(f.floatPnl));
                ctpMargin = ctpMargin.add(nz(f.marginOccupied));
            }

            List<SettleAggregationCheck> checks = new ArrayList<>();
            checks.add(buildCheck(date, CHECK_POSITION, sysPos, ctpPos, operatorId));
            checks.add(buildCheck(date, CHECK_FUND, sysEquity, ctpEquity, operatorId));
            checks.add(buildCheck(date, CHECK_PNL, sysPnl, ctpPnl, operatorId));
            checks.add(buildCheck(date, CHECK_MARGIN, sysMargin, ctpMargin, operatorId));

            Map<Long, List<SettleAggDiffCandidate>> candidatesByCheck = new LinkedHashMap<>();
            boolean anyInconsistent = false;
            for (SettleAggregationCheck check : checks) {
                if (AGG_INCONSISTENT.equals(check.result)) {
                    anyInconsistent = true;
                    List<SettleAggDiffCandidate> candidates = attributeCandidates(check, date, subs, operatorId);
                    candidatesByCheck.put(check.id, candidates);
                    for (SettleAggDiffCandidate c : candidates) {
                        res.blockedSubAccountIds.add(c.subAccountId);
                    }
                }
                res.checks.add(check);
            }
            res.consistent = !anyInconsistent;

            if (anyInconsistent) {
                // 全账户强制告警（风控 + 财务 + 审计可见）——以告警时间与结构化候选承载
                for (SettleAggregationCheck check : checks) {
                    if (AGG_INCONSISTENT.equals(check.result)) {
                        LOG.severe("加总对账不一致，全账户强制告警（FR-SETTLE-8 / CFG-11）：tradeDate=" + date
                                + " checkType=" + check.checkType + " Σ子=" + check.sumSubAccount
                                + " 真实=" + check.realAccountValue + " 差额=" + check.difference
                                + " 阻断范围=" + check.blockScope + " 阻断子账户=" + res.blockedSubAccountIds);
                    }
                }
                // 结算单状态转 BLOCKED（差异所属子账户）
                for (SettleCycle sub : subs) {
                    if (res.blockedSubAccountIds.contains(sub.subAccountId)
                            && !CYCLE_CONFIRMED.equals(sub.status)) {
                        sub.status = CYCLE_BLOCKED;
                        cycleDao.updateById(sub, operatorId);
                    }
                }
            }
            return res;
        });
    }

    private SettleAggregationCheck buildCheck(LocalDate date, String type, BigDecimal sys, BigDecimal real,
                                              long operatorId) {
        SettleAggregationCheck c = new SettleAggregationCheck();
        c.tradeDate = date;
        c.checkType = type;
        c.sumSubAccount = sys;
        c.realAccountValue = real;
        c.difference = sys.subtract(real);
        c.checkTime = Clocks.now();
        if (c.difference.signum() == 0) {
            c.result = AGG_CONSISTENT;
        } else {
            c.result = AGG_INCONSISTENT;
            c.blockScope = BLOCK_SCOPE_DIFF_SUB_NON_CLOSE;
            c.blockEffectiveTime = Clocks.now(); // NFR-REL-6：阻断立即生效
            c.alertTime = Clocks.now();
        }
        aggregationCheckDao.insert(c, operatorId);
        return c;
    }

    /**
     * 差异归属的候选生成（机判来源 {@code t_agg_diff_candidate}；{@code DATA_MODEL.md} §4.4.1）。
     *
     * <p>取数：① 该交易日已定位到子账户的未闭环差异（{@code t_reconcile_difference.sub_account_id}，
     * 由资金台账勾稽差产生）② 若①为空则 fail-closed 保守记全部子账户候选并显式披露。
     * {@code possible_sub_accounts} 仅写展示缓存，不参与判定。</p>
     */
    private List<SettleAggDiffCandidate> attributeCandidates(SettleAggregationCheck check, LocalDate date,
                                                             List<SettleCycle> subs, long operatorId) {
        List<SettleAggDiffCandidate> out = new ArrayList<>();
        Set<Integer> culprits = new LinkedHashSet<>();
        StringBuilder display = new StringBuilder();
        PageResult<SettleReconcileDifference> diffs = differenceDao.findPage(
                PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Collections.singletonList((Object) date));
        for (SettleReconcileDifference d : diffs.items()) {
            if (d.subAccountId != null && !DIFF_CLOSED.equals(d.handleStatus)) {
                culprits.add(d.subAccountId);
            }
        }
        if (culprits.isEmpty()) {
            for (SettleCycle s : subs) {
                culprits.add(s.subAccountId);
            }
        }
        int rank = 1;
        for (Integer subId : culprits) {
            SettleAggDiffCandidate c = new SettleAggDiffCandidate();
            c.subAccountId = subId;
            c.aggregationCheckId = check.id;
            c.candidateRank = rank++;
            c.diffAmount = check.difference;
            c.resolutionStatus = CANDIDATE_PENDING;
            c.evidenceDesc = culprits.size() == subs.size()
                    ? "未定位差异来源，保守纳入候选（checkType=" + check.checkType + "，差额=" + check.difference + "）"
                    : "该子账户结算值与资金台账勾稽不一致（checkType=" + check.checkType + "）";
            candidateDao.insert(c, operatorId);
            out.add(c);
            if (display.length() > 0) {
                display.append(',');
            }
            display.append(subId);
        }
        // 展示缓存（仅界面提示，禁止作判定）
        check.possibleSubAccounts = display.toString();
        aggregationCheckDao.updateById(check, operatorId);
        return out;
    }

    // ================================================================ 四、阻断范围判定（FR-SETTLE-8 / Q-11 / CFG-11）

    /**
     * 判定某子账户在某动作下是否被加总对账阻断（{@code R-SETTLE-2} / {@code Q-11}）。
     *
     * <p>取数：当日 {@code t_aggregation_check.result='INCONSISTENT_BLOCKED' AND closed_time IS NULL}；
     * 阻断集合 = 各对账记录下 {@code t_agg_diff_candidate.resolution_status ∈ {PENDING, CONFIRMED}} 的
     * {@code sub_account_id} 全集（机判唯一来源）。{@code DIFF_SUB_NON_CLOSE} 下平仓恒放行。</p>
     *
     * @param tradeDate    交易日
     * @param subAccountId 子账户
     * @param action       动作（OPEN / CLOSE / TRANSFER / DAY_CLOSE）
     * @return 判定（含四项阻断措辞）
     */
    public BlockDecision evaluateBlock(LocalDate tradeDate, int subAccountId, String action) {
        BlockDecision decision = new BlockDecision();
        decision.subAccountId = subAccountId;
        decision.action = action;
        boolean close = ACTION_CLOSE.equals(action);
        PageResult<SettleAggregationCheck> page = aggregationCheckDao.findPage(
                PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Collections.singletonList((Object) tradeDate));
        for (SettleAggregationCheck check : page.items()) {
            if (!AGG_INCONSISTENT.equals(check.result) || check.closedTime != null) {
                continue;
            }
            String scope = check.blockScope == null ? BLOCK_SCOPE_DIFF_SUB_NON_CLOSE : check.blockScope;
            if (BLOCK_SCOPE_ALL_SUB.equals(scope)) {
                if (!close) {
                    decision.blocked = true;
                }
            } else if (BLOCK_SCOPE_DIFF_SUB.equals(scope)) {
                if (candidateSubAccountIds(check.id).contains(subAccountId)) {
                    decision.blocked = true;
                }
            } else { // DIFF_SUB_NON_CLOSE
                if (!close && candidateSubAccountIds(check.id).contains(subAccountId)) {
                    decision.blocked = true;
                }
            }
            if (decision.blocked) {
                decision.ruleId = RULE_AGG_BLOCK;
                decision.current = "subAccountId=" + subAccountId + ",action=" + action;
                decision.threshold = "加总对账 INCONSISTENT_BLOCKED（未闭环）";
                decision.nextStep = "定位差异 → 人工调账（留痕）→ 重跑对账 → 闭环后解除阻断";
                decision.aggregationCheckId = check.id;
                return decision;
            }
        }
        return decision;
    }

    /**
     * 强制断言：被阻断时抛 {@code 10013}（阻断类四项齐备）。供交易/资金写路径前置调用。
     *
     * @param tradeDate    交易日
     * @param subAccountId 子账户
     * @param action       动作
     */
    public void assertActionAllowed(LocalDate tradeDate, int subAccountId, String action) {
        BlockDecision d = evaluateBlock(tradeDate, subAccountId, action);
        if (d.blocked) {
            throw BizException.blocking(ErrorCode.AGGREGATION_MISMATCH,
                    "加总对账不一致，该动作被阻断（CFG-11）：subAccountId=" + subAccountId + " action=" + action
                            + "；复现=定位差异并闭环后重试（Q-11）",
                    d.ruleId, d.current, d.threshold, d.nextStep);
        }
    }

    private Set<Integer> candidateSubAccountIds(long aggregationCheckId) {
        Set<Integer> out = new LinkedHashSet<>();
        for (SettleAggDiffCandidate c : candidateDao.findBlockingCandidates(aggregationCheckId)) {
            if (c.subAccountId != null && c.subAccountId > 0) {
                out.add(c.subAccountId);
            }
        }
        return out;
    }

    // ================================================================ 五、关账门禁（R-SETTLE-5）

    /**
     * 结算单关账（{@code POST /settle/cycle/{id}/close}；{@code R-SETTLE-5}）。
     *
     * <p>前置：① 三类核对均已完成（存在三条不同 {@code reconcile_type} 的核对记录）
     * ② 加总对账无未闭环不一致 ③ 该交易日全部差异已闭环。任一不满足 ⇒ 拒绝关账。</p>
     *
     * @param cycleId    结算单号
     * @param operatorId 操作人
     * @return 关账结果
     */
    public CloseResult closeCycle(long cycleId, long operatorId) {
        SettleCycle cycle = requireCycle(cycleId);
        visibility.assertSubAccountWritable(operatorId, cycle.subAccountId);
        return tx.inTransaction(conn -> {
            if (CYCLE_CONFIRMED.equals(cycle.status)) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED, "结算单已关账，不得重复关账：" + cycle.settleNo);
            }
            LocalDate date = cycle.tradeDate;
            // ① 三类核对完成
            Set<String> types = new LinkedHashSet<>();
            PageResult<SettleReconcileRecord> records = reconcileRecordDao.findPage(
                    PageQuery.of("1", "100", "id,asc", "id"),
                    "trade_date = ?", Collections.singletonList((Object) date));
            for (SettleReconcileRecord r : records.items()) {
                if (RESULT_NOT_COMPLETED.equals(r.result)) {
                    continue;
                }
                types.add(r.reconcileType);
            }
            for (String required : Arrays.asList(DIFF_TYPE_TRADE, DIFF_TYPE_POSITION, DIFF_TYPE_FUND)) {
                if (!types.contains(required)) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "关账门禁未过：三类核对未完成（缺 " + required + "）：" + cycle.settleNo);
                }
            }
            // ② 加总对账无未闭环不一致（阻断检查；DAY_CLOSE 动作）
            BlockDecision d = evaluateBlock(date, cycle.subAccountId, ACTION_DAY_CLOSE);
            if (d.blocked) {
                throw BizException.blocking(ErrorCode.AGGREGATION_MISMATCH,
                        "关账门禁未过：加总对账不一致未闭环（R-SETTLE-2）：" + cycle.settleNo,
                        d.ruleId, d.current, d.threshold, d.nextStep);
            }
            // ③ 全部差异已闭环
            PageResult<SettleReconcileDifference> diffs = differenceDao.findPage(
                    PageQuery.of("1", "100", "id,asc", "id"),
                    "trade_date = ?", Collections.singletonList((Object) date));
            for (SettleReconcileDifference diff : diffs.items()) {
                if (!DIFF_CLOSED.equals(diff.handleStatus)) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "关账门禁未过：存在未闭环差异（R-SETTLE-5）：" + cycle.settleNo
                                    + " differenceId=" + diff.id + " status=" + diff.handleStatus);
                }
            }
            cycle.status = CYCLE_CONFIRMED;
            cycle.confirmedBy = operatorId;
            cycle.confirmedTime = Clocks.now();
            cycleDao.updateById(cycle, operatorId);
            CloseResult r = new CloseResult();
            r.cycleId = cycle.id;
            r.settleNo = cycle.settleNo;
            r.confirmed = true;
            return r;
        });
    }

    // ================================================================ 六、差异处理（FR-SETTLE-9）

    /**
     * 处理核对差异（{@code PUT /settle/difference}；{@code FR-SETTLE-9}）。
     *
     * <p>调整类操作（{@code ADJUST}/{@code REVERSE}）须双人复核（{@code reviewerId} 非空且 ≠ 处理人）。
     * 闭环后若该交易日已无未闭环差异，则解除加总阻断（{@code closed_time} 回填）。</p>
     *
     * @param differenceId 差异号
     * @param in           处理入参
     * @param operatorId   操作人
     * @return 处理结果
     */
    public DifferenceResult handleDifference(long differenceId, DifferenceInput in, long operatorId) {
        if (in == null || in.handleStatus == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "handleStatus 必填（PENDING/HANDLING/CLOSED）");
        }
        return tx.inTransaction(conn -> {
            SettleReconcileDifference d = differenceDao.findById(differenceId);
            if (d == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "差异记录不存在：id=" + differenceId);
            }
            if (d.subAccountId != null) {
                visibility.assertSubAccountWritable(operatorId, d.subAccountId);
            }
            boolean adjust = "ADJUST".equals(in.handleMethod) || "REVERSE".equals(in.handleMethod);
            if (adjust && (in.reviewerId == null || in.reviewerId == operatorId)) {
                throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                        "调整/冲正类差异处理须双人复核，且复核人不得为处理人本人（FR-SETTLE-9）："
                                + "handler=" + operatorId + " reviewer=" + in.reviewerId);
            }
            d.handleStatus = in.handleStatus;
            d.handleMethod = in.handleMethod;
            d.handleDesc = in.handleDesc;
            d.handlerId = operatorId;
            d.reviewerId = in.reviewerId;
            if (in.ownerId != null) {
                d.ownerId = in.ownerId;
            }
            if (DIFF_CLOSED.equals(in.handleStatus)) {
                d.closedTime = Clocks.now();
            }
            differenceDao.updateById(d, operatorId);

            // 闭环后：该交易日无未闭环差异 ⇒ 解除加总阻断
            boolean anyOpen = false;
            PageResult<SettleReconcileDifference> all = differenceDao.findPage(
                    PageQuery.of("1", "100", "id,asc", "id"),
                    "trade_date = ?", Collections.singletonList((Object) d.tradeDate));
            for (SettleReconcileDifference x : all.items()) {
                if (!DIFF_CLOSED.equals(x.handleStatus)) {
                    anyOpen = true;
                    break;
                }
            }
            if (!anyOpen) {
                PageResult<SettleAggregationCheck> checks = aggregationCheckDao.findPage(
                        PageQuery.of("1", "100", "id,asc", "id"),
                        "trade_date = ?", Collections.singletonList((Object) d.tradeDate));
                for (SettleAggregationCheck c : checks.items()) {
                    if (AGG_INCONSISTENT.equals(c.result) && c.closedTime == null) {
                        c.closedTime = Clocks.now();
                        aggregationCheckDao.updateById(c, operatorId);
                    }
                }
            }
            DifferenceResult r = new DifferenceResult();
            r.differenceId = differenceId;
            r.handleStatus = d.handleStatus;
            r.closed = DIFF_CLOSED.equals(d.handleStatus);
            return r;
        });
    }

    // ================================================================ 七、每日结算报告 / 风控日报（FR-SETTLE-10/12）

    /**
     * 生成每日结算报告（{@code GET /settle/report}；{@code FR-SETTLE-10}）。
     *
     * <p>加总对账不一致时报告 {@code agg_result} 显式标注 {@code INCONSISTENT_BLOCKED}，
     * <b>不得</b>输出为正常结论；关键数据缺失时拒绝生成。</p>
     *
     * @param tradeDate  交易日
     * @param reportType 报告类型（{@code DAILY_SETTLE}/{@code DAILY_RISK}）
     * @param operatorId 生成人
     * @return 报告
     */
    public SettleReport generateReport(LocalDate tradeDate, String reportType, long operatorId) {
        if (tradeDate == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "tradeDate 必填（FR-SETTLE-10）");
        }
        String type = reportType == null ? REPORT_DAILY_SETTLE : reportType;
        List<SettleCycle> subs = findSubCycles(tradeDate);
        if (subs.isEmpty()) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "关键数据缺失：当日无结算单，拒绝生成报告（FR-SETTLE-10 验收4）：tradeDate=" + tradeDate);
        }
        String aggResult = latestAggResult(tradeDate);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("tradeDate", tradeDate.toString());
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalEquity = BigDecimal.ZERO;
        for (SettleCycle c : subs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subAccountId", c.subAccountId);
            row.put("settleNo", c.settleNo);
            row.put("equity", c.equity == null ? "0" : c.equity.toPlainString());
            row.put("closePnl", c.closePnl == null ? "0" : c.closePnl.toPlainString());
            row.put("floatPnl", c.floatPnl == null ? "0" : c.floatPnl.toPlainString());
            items.add(row);
            totalEquity = totalEquity.add(nz(c.equity));
        }
        content.put("subAccounts", items);
        content.put("totalEquity", totalEquity.toPlainString());
        content.put("aggResult", aggResult);

        return tx.inTransaction(conn -> {
            SettleReport report = new SettleReport();
            report.subAccountId = null;
            report.tradeDate = tradeDate;
            report.reportType = type;
            report.aggResult = aggResult;
            report.contentJson = JsonCodec.toJson(content);
            report.dataGapFlag = subs.isEmpty() ? "Y" : "N";
            report.generatedBy = operatorId;
            report.generatedTime = Clocks.now();
            report.missingFlag = "N";
            reportDao.insert(report, operatorId);
            return report;
        });
    }

    private String latestAggResult(LocalDate date) {
        PageResult<SettleAggregationCheck> page = aggregationCheckDao.findPage(
                PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Collections.singletonList((Object) date));
        String result = AGG_CONSISTENT;
        boolean any = false;
        for (SettleAggregationCheck c : page.items()) {
            any = true;
            if (AGG_INCONSISTENT.equals(c.result) && c.closedTime == null) {
                return AGG_INCONSISTENT; // 显式标注不一致，不得输出正常结论
            }
        }
        return any ? result : "NOT_APPLICABLE";
    }

    // ================================================================ 八、保证金监控 / 压力测试（FR-SETTLE-11）

    /**
     * 保证金与风险度视图（{@code GET /settle/margin}；{@code FR-SETTLE-11}）。
     *
     * @param tradeDate  交易日
     * @param subAccountId 子账户（{@code null} = 汇总视图）
     * @param threshold  风险度阈值（字符串小数；超阈值置告警）
     * @param operatorId 操作人
     * @return 视图
     */
    public MarginView marginView(LocalDate tradeDate, Integer subAccountId, String threshold, long operatorId) {
        if (tradeDate == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "tradeDate 必填（FR-SETTLE-11）");
        }
        BigDecimal thr = threshold == null ? null : dec(threshold);
        PageResult<SettleFund> page = fundDao.findPage(
                PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Collections.singletonList((Object) tradeDate));
        MarginView view = new MarginView();
        view.tradeDate = tradeDate;
        view.subAccountId = subAccountId;
        view.rows = new ArrayList<>();
        BigDecimal sumEquity = BigDecimal.ZERO;
        BigDecimal sumMargin = BigDecimal.ZERO;
        for (SettleFund f : page.items()) {
            if (subAccountId != null && !subAccountId.equals(f.subAccountId)) {
                continue;
            }
            MarginRow row = new MarginRow();
            row.subAccountId = f.subAccountId;
            row.equity = nz(f.equity);
            row.marginOccupied = nz(f.marginOccupied);
            row.availableFund = nz(f.availableFund);
            row.riskRatio = f.equity != null && f.equity.signum() > 0
                    ? nz(f.marginOccupied).divide(f.equity, 6, RoundingMode.HALF_UP) : null;
            row.alert = thr != null && row.riskRatio != null && row.riskRatio.compareTo(thr) > 0;
            view.rows.add(row);
            sumEquity = sumEquity.add(row.equity);
            sumMargin = sumMargin.add(row.marginOccupied);
        }
        view.sumEquity = sumEquity;
        view.sumMarginOccupied = sumMargin;
        view.aggregateRiskRatio = sumEquity.signum() > 0
                ? sumMargin.divide(sumEquity, 6, RoundingMode.HALF_UP) : null;
        view.aggregateAlert = thr != null && view.aggregateRiskRatio != null
                && view.aggregateRiskRatio.compareTo(thr) > 0;
        return view;
    }

    /**
     * 场景化压力测试（{@code POST /settle/stress-test}；{@code FR-SETTLE-11} / {@code R-SETTLE-7}）。
     *
     * <p>以价格不利变动幅度估算各子账户/全账户保证金缺口：{@code marginGap = 保证金占用 × 变动幅度}；
     * 权益估算 = 原权益 − 缺口；情景后风险度 = 新保证金占用 / 新权益。</p>
     *
     * @param in         情景参数
     * @param operatorId 执行人
     * @return 压力测试结果
     */
    public SettleStressTest runStressTest(StressInput in, long operatorId) {
        if (in == null || in.testDate == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "testDate 必填（FR-SETTLE-11 验收3）");
        }
        BigDecimal shock = dec(in.priceShockPct);
        if (shock == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "priceShockPct 必填（情景参数）");
        }
        LocalDate date = in.testDate;
        MarginView view = marginView(date, in.subAccountId, null, operatorId);
        BigDecimal margin = view.sumMarginOccupied;
        BigDecimal equity = view.sumEquity;
        BigDecimal gap = margin.multiply(shock.abs());
        SettleStressTest st = new SettleStressTest();
        st.subAccountId = in.subAccountId;
        st.testDate = date;
        st.scenarioJson = JsonCodec.toJson(in);
        st.marginGap = gap;
        st.additionalRequirement = gap.subtract(equity).max(BigDecimal.ZERO);
        st.equityAfter = equity.subtract(gap);
        st.riskRatioAfter = st.equityAfter.signum() > 0
                ? margin.divide(st.equityAfter, 6, RoundingMode.HALF_UP) : null;
        st.operatorId = operatorId;
        st.testTime = Clocks.now();
        return tx.inTransaction(conn -> {
            stressTestDao.insert(st, operatorId);
            return st;
        });
    }

    // ================================================================ 九、历史结算记录查询 / 导出（FR-SETTLE-15/16）

    /**
     * 查询历史结算记录（{@code GET /settle/cycle}；{@code FR-SETTLE-15}）。
     *
     * <p>默认按交易日倒序；仅返回有权限的子账户范围（审计岗可跨子账户）；只读。</p>
     *
     * @param pageQuery  分页
     * @param from       起始日（含，可空）
     * @param to         截止日（含，可空）
     * @param status     状态（可空）
     * @param subAccountId 子账户（可空）
     * @param operatorId 操作人
     * @return 分页结果
     */
    public PageResult<SettleCycle> listCycles(PageQuery pageQuery, LocalDate from, LocalDate to,
                                              String status, Integer subAccountId, long operatorId) {
        StringBuilder where = new StringBuilder("is_aggregate = ?");
        List<Object> args = new ArrayList<>();
        args.add("N");
        if (from != null) {
            where.append(" AND trade_date >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND trade_date <= ?");
            args.add(to);
        }
        if (status != null && !status.isEmpty()) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, subAccountId);
            where.append(" AND sub_account_id = ?");
            args.add(subAccountId);
        }
        List<Integer> visible = visibility.visibleSubAccountIds(operatorId);
        if (visible != null && !visible.isEmpty()
                && !visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            SubAccountScope scope = SubAccountScope.of("sub_account_id", visible);
            where.insert(0, "(").append(") AND ").append(scope.sql());
            args.addAll(scope.params());
        } else if (visible == null || visible.isEmpty()) {
            where.insert(0, "(").append(") AND sub_account_id IN (-1)");
        }
        return cycleDao.findPage(pageQuery, where.toString(), args);
    }

    /**
     * 导出结算报表行（{@code GET /settle/export}；{@code FR-SETTLE-16}）。
     *
     * <p>仅导出有权限子账户范围；含加总对账结论列；无数据返回空列表（不生成空文件由接口层处理）。</p>
     *
     * @param from       起始日（含）
     * @param to         截止日（含）
     * @param subAccountId 子账户（可空）
     * @param operatorId 导出人
     * @return 导出结果（列名 + 行 + 加总结论）
     */
    public ExportResult exportReport(LocalDate from, LocalDate to, Integer subAccountId, long operatorId) {
        PageQuery q = PageQuery.of("1", "100", "trade_date,desc", "trade_date", "desc");
        PageResult<SettleCycle> page = listCycles(q, from, to, null, subAccountId, operatorId);
        ExportResult out = new ExportResult();
        out.exportTime = Clocks.now();
        out.exportedBy = operatorId;
        out.columns = Arrays.asList("settleNo", "tradeDate", "subAccountId", "status", "equity", "aggResult");
        out.rows = new ArrayList<>();
        for (SettleCycle c : page.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("settleNo", c.settleNo);
            row.put("tradeDate", c.tradeDate == null ? null : c.tradeDate.toString());
            row.put("subAccountId", c.subAccountId);
            row.put("status", c.status);
            row.put("equity", c.equity == null ? "0" : c.equity.toPlainString());
            row.put("aggResult", latestAggResult(c.tradeDate));
            out.rows.add(row);
        }
        return out;
    }

    // ================================================================ 内部工具

    private SettleCycle findCycle(LocalDate date, int subId) {
        PageResult<SettleCycle> page = cycleDao.findPage(
                PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ? AND sub_account_id = ? AND is_aggregate = ?",
                Arrays.asList(date, subId, "N"));
        return page.items().isEmpty() ? null : page.items().get(0);
    }

    private List<SettleCycle> findSubCycles(LocalDate date) {
        PageResult<SettleCycle> page = cycleDao.findPage(
                PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ? AND is_aggregate = ?", Arrays.asList(date, "N"));
        List<SettleCycle> out = new ArrayList<>();
        for (SettleCycle c : page.items()) {
            out.add(c);
        }
        return out;
    }

    private SettleCycle requireCycle(long cycleId) {
        SettleCycle cycle = cycleDao.findById(cycleId);
        if (cycle == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "结算单不存在：id=" + cycleId);
        }
        return cycle;
    }

    private List<SettleCtpPosition> queryCtpPositions(LocalDate date) {
        return ctpPositionDao.findPage(PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Collections.singletonList((Object) date)).items();
    }

    private List<SettleCtpFund> queryCtpFunds(LocalDate date) {
        return ctpFundDao.findPage(PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Collections.singletonList((Object) date)).items();
    }

    private List<SettleCtpTrade> queryCtpTrades(LocalDate date) {
        return ctpTradeDao.findPage(PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Collections.singletonList((Object) date)).items();
    }

    private BigDecimal sumPositionQty(LocalDate date) {
        PageResult<SettlePosition> page = positionDao.findPage(
                PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Collections.singletonList((Object) date));
        BigDecimal sum = BigDecimal.ZERO;
        for (SettlePosition p : page.items()) {
            sum = sum.add(nz(p.positionQty));
        }
        return sum;
    }

    private BigDecimal sumCycleColumn(LocalDate date, String column) {
        BigDecimal sum = BigDecimal.ZERO;
        for (SettleCycle c : findSubCycles(date)) {
            if ("equity".equals(column)) {
                sum = sum.add(nz(c.equity));
            } else if ("pnl".equals(column)) {
                sum = sum.add(nz(c.closePnl)).add(nz(c.floatPnl));
            } else if ("margin".equals(column)) {
                sum = sum.add(nz(c.marginOccupied));
            }
        }
        return sum;
    }

    private Map<Integer, List<SystemPositionRow>> groupPositions(List<SystemPositionRow> rows, LocalDate date) {
        Map<Integer, List<SystemPositionRow>> out = new LinkedHashMap<>();
        if (rows == null) {
            return out;
        }
        for (SystemPositionRow r : rows) {
            if (r == null || r.subAccountId == null) {
                continue;
            }
            out.computeIfAbsent(r.subAccountId, k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    private Map<Integer, SystemFundRow> indexFunds(List<SystemFundRow> rows, LocalDate date) {
        Map<Integer, SystemFundRow> out = new LinkedHashMap<>();
        if (rows == null) {
            return out;
        }
        for (SystemFundRow r : rows) {
            if (r != null && r.subAccountId != null) {
                out.put(r.subAccountId, r);
            }
        }
        return out;
    }

    private static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 必填（yyyy-MM-dd）");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 格式非法（yyyy-MM-dd）：值=" + raw);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 不得为空");
        }
        return value.trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal dec(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "金额/数量格式非法：值=" + s);
        }
    }

    // ================================================================ DTO

    /** CTP 日终接收请求。 */
    public static final class CtpDataRequest {
        /** 交易日（yyyy-MM-dd）。 */
        public String tradeDate;
        /** 来源标识（文件名/批次号）。 */
        public String sourceIdentifier;
        /** 持仓明细。 */
        public List<CtpPositionRow> positions;
        /** 资金明细。 */
        public List<CtpFundRow> funds;
        /** 成交明细。 */
        public List<CtpTradeRow> trades;
    }

    /** CTP 持仓行。 */
    public static final class CtpPositionRow {
        /** 合约。 */
        public String instrumentId;
        /** 方向。 */
        public String direction;
        /** 仓型。 */
        public String positionType;
        /** 持仓量。 */
        public String positionQty;
        /** 持仓均价。 */
        public String avgPrice;
        /** 结算价。 */
        public String settlementPrice;
        /** 占用保证金。 */
        public String margin;
        /**
         * 平仓盈亏（{@code T-189} 方案 A 扩导入契约；转录自 CTP SDK
         * {@code ThostFtdcUserApiStruct.h} 持仓字段 {@code CloseProfit}，逐字转录不推算）。
         *
         * <p><b>可缺省（旧模板向后兼容）</b>：缺列/空值 ⇒ 该合约行不参与盈亏转录
         * （在接收回执 {@code positionPnlMissingCount} 披露），不硬失败。
         * ⚠️ 逐合约盈亏的<b>中间持久化承载</b>须扩 {@code TB-SETTLE-2}（DATA_MODEL 冻结面），
         * 裁定前本字段仅入库回执披露、不落 {@code t_ctp_settle_position}（T-189 升级报告中）。
         */
        public String closeProfit;
        /** 持仓盈亏（浮动，{@code T-189} 方案 A；转录自 CTP SDK {@code PositionProfit}；可缺省，同上）。 */
        public String positionProfit;
    }

    /** CTP 资金行。 */
    public static final class CtpFundRow {
        /** 客户权益。 */
        public String equity;
        /** 可用资金。 */
        public String availableFund;
        /** 占用保证金。 */
        public String marginOccupied;
        /** 冻结保证金。 */
        public String frozenMargin;
        /** 当日手续费。 */
        public String commissionToday;
        /** 当日入金。 */
        public String depositToday;
        /** 当日出金。 */
        public String withdrawToday;
        /** 平仓盈亏。 */
        public String closePnl;
        /** 持仓浮动盈亏。 */
        public String floatPnl;
    }

    /** CTP 成交行。 */
    public static final class CtpTradeRow {
        /** CTP 成交编号。 */
        public String tradeId;
        /** 合约。 */
        public String instrumentId;
        /** 方向。 */
        public String direction;
        /** 开平。 */
        public String offsetFlag;
        /** 成交价。 */
        public String fillPrice;
        /** 成交量。 */
        public Integer fillVolume;
        /** 成交时间戳。 */
        public Long tradeTsMs;
        /** 手续费。 */
        public String commission;
    }

    /** 接收结果。 */
    public static final class CtpIngestResult {
        /** 批次号。 */
        public long batchId;
        /** 是否待重试（数据未到达）。 */
        public boolean pendingRetry;
        /** 持仓条数。 */
        public int positionCount;
        /** 资金条数。 */
        public int fundCount;
        /** 成交条数。 */
        public int tradeCount;
        /** 持仓行中<b>携带</b>逐合约盈亏字段（closeProfit/positionProfit 至少其一非空）的条数（{@code T-189}）。 */
        public int positionPnlPresentCount;
        /** 持仓行中<b>未携带</b>逐合约盈亏字段（缺列/空值，旧模板）的条数；该部分行结算时不参与盈亏转录（{@code T-189}）。 */
        public int positionPnlMissingCount;
        /** 说明。 */
        public String message;

        CtpIngestResult(long batchId, boolean pendingRetry, int positionCount, int fundCount,
                        int tradeCount, int positionPnlPresentCount, int positionPnlMissingCount,
                        String message) {
            this.batchId = batchId;
            this.pendingRetry = pendingRetry;
            this.positionCount = positionCount;
            this.fundCount = fundCount;
            this.tradeCount = tradeCount;
            this.positionPnlPresentCount = positionPnlPresentCount;
            this.positionPnlMissingCount = positionPnlMissingCount;
            this.message = message;
        }
    }

    /** 系统内按子账户结算入参（接缝；生产由 T-172 事件接线供给）。 */
    public static final class SettlementInput {
        /** 交易日（yyyy-MM-dd）。 */
        public String tradeDate;
        /** 系统内按子账户持仓（由成交记录推导）。 */
        public List<SystemPositionRow> positions;
        /** 系统内按子账户资金（含 FUND 台账口径 ledgerEquity）。 */
        public List<SystemFundRow> funds;
        /** 系统内按子账户成交（供成交核对）。 */
        public List<SystemTradeRow> trades;
    }

    /** 系统内持仓行。 */
    public static final class SystemPositionRow {
        /** 子账户。 */
        public Integer subAccountId;
        /** 合约。 */
        public String instrumentId;
        /** 品种。 */
        public Long productId;
        /** 合约类型（FUTURES/OPTION）。 */
        public String instrumentType;
        /** 方向（LONG/SHORT）。 */
        public String direction;
        /** 仓型（TODAY/YESTERDAY）。 */
        public String positionType;
        /** 持仓量。 */
        public String positionQty;
    }

    /** 系统内资金行。 */
    public static final class SystemFundRow {
        /** 子账户。 */
        public Integer subAccountId;
        /** 期初余额。 */
        public String beginBalance;
        /** 权益。 */
        public String equity;
        /** 可用资金。 */
        public String availableFund;
        /** 占用保证金。 */
        public String marginOccupied;
        /** 冻结保证金。 */
        public String frozenMargin;
        /** 当日入金。 */
        public String amountIn;
        /** 当日出金。 */
        public String amountOut;
        /** 当日子账户划拨入。 */
        public String transferIn;
        /** 当日子账户划拨出。 */
        public String transferOut;
        /** 平仓盈亏。 */
        public String closePnl;
        /** 浮动盈亏。 */
        public String floatPnl;
        /** 手续费。 */
        public String commission;
        /** 保证金占用变动。 */
        public String marginChange;
        /** 期末余额（可空，空则按恒等式推导）。 */
        public String endBalance;
        /** FUND 台账权益口径（勾稽用；不等即记差异并成为加总候选）。 */
        public String ledgerEquity;
        /** 归属来源（LEDGER/UNABLE_TO_ATTRIBUTE）。 */
        public String attributionSource;
    }

    /** 重跑核对入参。 */
    public static final class ReconcileRequest {
        /** 系统内成交（接缝；供成交核对逐笔比对）。 */
        public List<SystemTradeRow> systemTrades;
    }

    /** 系统内成交行。 */
    public static final class SystemTradeRow {
        /** 子账户。 */
        public Integer subAccountId;
        /** CTP 成交编号。 */
        public String tradeId;
        /** 合约。 */
        public String instrumentId;
        /** 方向。 */
        public String direction;
        /** 开平。 */
        public String offsetFlag;
        /** 成交价。 */
        public String fillPrice;
        /** 成交量。 */
        public Integer fillVolume;
        /** 成交时间戳。 */
        public Long tradeTsMs;
        /** 手续费。 */
        public String commission;
    }

    /** 核对单类结论。 */
    public static final class ReconcileLine {
        /** 核对类型。 */
        public final String type;
        /** 结论。 */
        public final String result;
        /** 差异条数。 */
        public final int differenceCount;
        /** 核对记录号。 */
        public final long reconcileRecordId;

        ReconcileLine(String type, String result, int differenceCount, long reconcileRecordId) {
            this.type = type;
            this.result = result;
            this.differenceCount = differenceCount;
            this.reconcileRecordId = reconcileRecordId;
        }
    }

    /** 三类核对结论。 */
    public static final class ReconcileResult {
        /** 成交核对。 */
        public ReconcileLine trade;
        /** 仓位核对。 */
        public ReconcileLine position;
        /** 资金核对。 */
        public ReconcileLine fund;
    }

    /** 加总对账结论。 */
    public static final class AggregateResult {
        /** 交易日。 */
        public LocalDate tradeDate;
        /** 是否一致。 */
        public boolean consistent;
        /** 四类对账记录。 */
        public List<SettleAggregationCheck> checks;
        /** 被阻断子账户集合（机判来源候选）。 */
        public Set<Integer> blockedSubAccountIds;
    }

    /** 阻断判定。 */
    public static final class BlockDecision {
        /** 子账户。 */
        public int subAccountId;
        /** 动作。 */
        public String action;
        /** 是否阻断。 */
        public boolean blocked;
        /** 命中规则编号。 */
        public String ruleId;
        /** 当前值。 */
        public String current;
        /** 阈值/依据。 */
        public String threshold;
        /** 下一步。 */
        public String nextStep;
        /** 对账记录号。 */
        public long aggregationCheckId;
    }

    /** 关账结果。 */
    public static final class CloseResult {
        /** 结算单号。 */
        public long cycleId;
        /** 结算单编号。 */
        public String settleNo;
        /** 是否成功关账。 */
        public boolean confirmed;
    }

    /** 差异处理入参。 */
    public static final class DifferenceInput {
        /** 处理状态。 */
        public String handleStatus;
        /** 处理方式（SUPPLEMENT/REVERSE/ADJUST）。 */
        public String handleMethod;
        /** 处理说明。 */
        public String handleDesc;
        /** 责任人。 */
        public Long ownerId;
        /** 复核人（调整类必填且 ≠ 处理人）。 */
        public Long reviewerId;
    }

    /** 差异处理结果。 */
    public static final class DifferenceResult {
        /** 差异号。 */
        public long differenceId;
        /** 处理状态。 */
        public String handleStatus;
        /** 是否闭环。 */
        public boolean closed;
    }

    /** 保证金视图行。 */
    public static final class MarginRow {
        /** 子账户。 */
        public Integer subAccountId;
        /** 权益。 */
        public BigDecimal equity;
        /** 占用保证金。 */
        public BigDecimal marginOccupied;
        /** 可用资金。 */
        public BigDecimal availableFund;
        /** 风险度。 */
        public BigDecimal riskRatio;
        /** 是否超阈值告警。 */
        public boolean alert;
    }

    /** 保证金视图。 */
    public static final class MarginView {
        /** 交易日。 */
        public LocalDate tradeDate;
        /** 子账户（null=汇总）。 */
        public Integer subAccountId;
        /** 明细行。 */
        public List<MarginRow> rows;
        /** 汇总权益。 */
        public BigDecimal sumEquity;
        /** 汇总保证金占用。 */
        public BigDecimal sumMarginOccupied;
        /** 汇总风险度。 */
        public BigDecimal aggregateRiskRatio;
        /** 汇总是否告警。 */
        public boolean aggregateAlert;
    }

    /** 压力测试入参。 */
    public static final class StressInput {
        /** 子账户（null=全账户）。 */
        public Integer subAccountId;
        /** 测试基准日。 */
        public LocalDate testDate;
        /** 价格不利变动幅度（小数，如 0.1 = 10%）。 */
        public String priceShockPct;
    }

    /** 导出结果。 */
    public static final class ExportResult {
        /** 导出时间。 */
        public LocalDateTime exportTime;
        /** 导出人。 */
        public long exportedBy;
        /** 列名。 */
        public List<String> columns;
        /** 数据行。 */
        public List<Map<String, Object>> rows;
    }
}
