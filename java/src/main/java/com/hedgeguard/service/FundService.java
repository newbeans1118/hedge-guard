package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.BizNoGenerator;
import com.hedgeguard.dao.CapitalReceiptDao;
import com.hedgeguard.dao.DepositWithdrawApplyDao;
import com.hedgeguard.dao.FundFlowDao;
import com.hedgeguard.dao.FundLedgerDao;
import com.hedgeguard.dao.FundQuotaAlertDao;
import com.hedgeguard.dao.FundQuotaDao;
import com.hedgeguard.dao.FundReconcileDao;
import com.hedgeguard.dao.FundReviewRecordDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TransferApplyDao;
import com.hedgeguard.dao.entity.CapitalReceipt;
import com.hedgeguard.dao.entity.DepositWithdrawApply;
import com.hedgeguard.dao.entity.FundFlow;
import com.hedgeguard.dao.entity.FundLedger;
import com.hedgeguard.dao.entity.FundQuota;
import com.hedgeguard.dao.entity.FundQuotaAlert;
import com.hedgeguard.dao.entity.FundReconcile;
import com.hedgeguard.dao.entity.FundReviewRecord;
import com.hedgeguard.dao.entity.TransferApply;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 资金管理服务（{@code ARCHITECTURE.md} §3.8 {@code FundService}；{@code T-049}；
 * {@code FR-FUND-1}~{@code 17}；{@code CFG-25}）。
 *
 * <p><b>五条结构性保证</b>：
 * <ol>
 *   <li><b>资金记账完整性</b>（{@code R-FUND-2}）：每笔流水带凭证编号（{@code FV<yyyyMMdd><seq(5)>}）、
 *       只增不改；台账恒满足 {@code 期初 + 发生额 = 期末}；期末由 {@link #appendFlow} 与发生额列同源维护。</li>
 *   <li><b>CTP 异步 + 幂等</b>（{@code R-FUND-5}）：出入金提交后不阻塞等待；回执到达才记账；
 *       网络超时置「超时待查」（{@code TIMEOUT_UNKNOWN}）<b>不得假设未到账</b>；同一幂等标识
 *       重复提交不产生第二笔；重复回执不重复记账（{@code uk_capital_receipt_idem}）。</li>
 *   <li><b>双人复核</b>（{@code R-FUND-3}）：出入金与划拨的发起人 ≠ 复核/审批人（系统级强制）；
 *       同账号复核被拒并写 {@code t_fund_review_record.self_review_rejected='Y'} 越权留痕；
 *       划拨审批人不得为交易岗。</li>
 *   <li><b>划拨为系统内记账</b>（{@code R-FUND-4}）：A 减 / B 增 / 加总不变；记账后校验
 *       {@code Σ 子账户可用 = 真实账户可用}，不通过即回滚（{@code FR-FUND-12}）；支持人工回滚。</li>
 *   <li><b>额度不挪用 + 告警不阻断</b>（{@code R-FUND-7}）：子账户 / 账户两级额度；账户级不得超过
 *       真实账户可用上限；超限写 {@code t_fund_quota_alert}（{@code blocked_operation='NONE'}，
 *       告警级别按 {@code CFG-25}），<b>不阻断</b>出金与减仓。</li>
 * </ol>
 *
 * <p><b>事件源实态（据实披露）</b>：{@code HgcpClient} 对 {@code EVT_FUND_RECEIPT}(0x020B)
 * 目前<b>只推进镜像水位、不回调监听器</b>（{@code HgcpEventListener} 无资金回执载荷；其 8 事件清单为
 * {@code 0x0201~0x0208}）。故回执处理在本类以 <b>「服务方法 + 接缝」</b>（{@link #onFundReceipt}）承接；
 * 生产事件接线缺口如实披露，归 {@code T-172} 扩载；{@code bridge/**} 未改动。
 * 同理 {@code TradingBridge} 未承载 {@code CMD_FUND_TRANSFER(0x011D)}/{@code CMD_QUERY_FUND_RESULT(0x011E)}
 * 下发方法，故提交 / 主动查询以服务方法 + 入参接缝承接。
 *
 * <p>分层纪律（{@code §3.8}）：不出现 SQL 语句；不依赖 HTTP 类型；只经 DAO 接口访问数据。
 */
public final class FundService {

    private static final Logger LOG = Logger.getLogger(FundService.class.getName());

    // ---------------------------------------------------------------- 流水类型（ck_fund_flow_type）
    /** 入金。 */
    public static final String FLOW_DEPOSIT = "DEPOSIT";
    /** 出金。 */
    public static final String FLOW_WITHDRAW = "WITHDRAW";
    /** 划拨出。 */
    public static final String FLOW_TRANSFER_OUT = "TRANSFER_OUT";
    /** 划拨入。 */
    public static final String FLOW_TRANSFER_IN = "TRANSFER_IN";
    /** 差异调整（恒须双人复核）。 */
    public static final String FLOW_DIFFERENCE_ADJUST = "DIFFERENCE_ADJUST";

    /** 方向：入。 */
    public static final String DIR_IN = "IN";
    /** 方向：出。 */
    public static final String DIR_OUT = "OUT";

    // ---------------------------------------------------------------- 出入金状态（ck_dw_status）
    /** 待复核。 */
    public static final String DW_PENDING_REVIEW = "PENDING_REVIEW";
    /** 已提交（等待 CTP 回执）。 */
    public static final String DW_SUBMITTED = "SUBMITTED";
    /** 已成功。 */
    public static final String DW_SUCCESS = "SUCCESS";
    /** 已失败。 */
    public static final String DW_FAILED = "FAILED";
    /** 超时待查（不得假设未到账）。 */
    public static final String DW_TIMEOUT_UNKNOWN = "TIMEOUT_UNKNOWN";
    /** 已驳回。 */
    public static final String DW_REJECTED = "REJECTED";

    // ---------------------------------------------------------------- 回执结果（ck_capital_receipt_result）
    /** 回执：成功。 */
    public static final String RECEIPT_SUCCESS = "SUCCESS";
    /** 回执：失败。 */
    public static final String RECEIPT_FAIL = "FAIL";
    /** 回执：处理中。 */
    public static final String RECEIPT_PROCESSING = "PROCESSING";
    /** 回执：未知（超时待查）。 */
    public static final String RECEIPT_UNKNOWN = "UNKNOWN";

    // ---------------------------------------------------------------- 划拨状态（ck_transfer_apply_status）
    /** 待审批。 */
    public static final String TR_PENDING_APPROVAL = "PENDING_APPROVAL";
    /** 待记账。 */
    public static final String TR_PENDING_POSTING = "PENDING_POSTING";
    /** 已记账。 */
    public static final String TR_POSTED = "POSTED";
    /** 已回滚。 */
    public static final String TR_ROLLED_BACK = "ROLLED_BACK";
    /** 已驳回。 */
    public static final String TR_REJECTED = "REJECTED";

    // ---------------------------------------------------------------- 复核记录
    /** 业务类型：出入金。 */
    public static final String BIZ_DEPOSIT_WITHDRAW = "DEPOSIT_WITHDRAW";
    /** 业务类型：划拨。 */
    public static final String BIZ_TRANSFER = "TRANSFER";
    /** 业务类型：台账建立。 */
    public static final String BIZ_LEDGER_INIT = "LEDGER_INIT";
    /** 复核：通过。 */
    public static final String REVIEW_APPROVED = "APPROVED";
    /** 复核：驳回。 */
    public static final String REVIEW_REJECTED = "REJECTED";

    // ---------------------------------------------------------------- 额度
    /** 额度范围：子账户。 */
    public static final String SCOPE_SUB = "SUB_ACCOUNT";
    /** 额度范围：账户。 */
    public static final String SCOPE_ACCOUNT = "ACCOUNT";
    /** 告警级别：提示。 */
    public static final String ALERT_INFO = "INFO";
    /** 告警级别：警告。 */
    public static final String ALERT_WARNING = "WARNING";
    /** 告警级别：严重。 */
    public static final String ALERT_CRITICAL = "CRITICAL";

    /** {@code CFG-25} 配置键。 */
    public static final String CFG_QUOTA_ALERT_LEVELS = "fund.quota.alert_levels";
    /** {@code CFG-25} 缺省阈值（递增序列：提示 / 警告 / 严重）。 */
    public static final String DEFAULT_QUOTA_ALERT_LEVELS = "0.80,0.95,1.00";

    // ---------------------------------------------------------------- 对账
    /** 对账层级：子账户。 */
    public static final String RECON_SUB = "SUB_ACCOUNT";
    /** 对账层级：账户。 */
    public static final String RECON_ACCOUNT = "ACCOUNT";
    /** 对账结论：一致。 */
    public static final String RECON_CONSISTENT = "CONSISTENT";
    /** 对账结论：不一致。 */
    public static final String RECON_INCONSISTENT = "INCONSISTENT";
    /** 对账结论：存在未闭环差异。 */
    public static final String RECON_OPEN_DIFF = "HAS_OPEN_DIFFERENCE";

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** 唯一约束冲突重试上限（{@code DATA_MODEL.md} §3.5「最多 3 次」，与 {@code PlanService} 同口径）。 */
    private static final int RETRY_MAX_ATTEMPTS = 3;

    private final TxTemplate tx;
    private final SubAccountVisibility visibility;
    private final TradingRoleChecker tradingRoleChecker;
    private final ConfigReader config;
    private final FundLedgerDao ledgerDao;
    private final FundFlowDao flowDao;
    private final DepositWithdrawApplyDao dwDao;
    private final TransferApplyDao transferDao;
    private final CapitalReceiptDao receiptDao;
    private final FundReviewRecordDao reviewDao;
    private final FundQuotaDao quotaDao;
    private final FundQuotaAlertDao alertDao;
    private final FundReconcileDao reconcileDao;

    /**
     * 构造。
     *
     * @param tx                 事务模板
     * @param visibility         子账户可见性（读 / 写断言）
     * @param tradingRoleChecker 交易岗判定（审批岗位分离）
     * @param config             {@code CFG-25} 阈值读取接缝；{@code null} 时取缺省
     *                           {@link #DEFAULT_QUOTA_ALERT_LEVELS}
     * @param ledgerDao          资金台账 DAO
     * @param flowDao            资金流水 DAO
     * @param dwDao              出入金申请 DAO
     * @param transferDao        划拨申请 DAO
     * @param receiptDao         资金回执 DAO
     * @param reviewDao          资金复核记录 DAO
     * @param quotaDao           资金额度 DAO
     * @param alertDao           资金额度告警 DAO
     * @param reconcileDao       资金对账 DAO
     */
    public FundService(TxTemplate tx, SubAccountVisibility visibility,
                       TradingRoleChecker tradingRoleChecker, ConfigReader config,
                       FundLedgerDao ledgerDao, FundFlowDao flowDao, DepositWithdrawApplyDao dwDao,
                       TransferApplyDao transferDao, CapitalReceiptDao receiptDao,
                       FundReviewRecordDao reviewDao, FundQuotaDao quotaDao,
                       FundQuotaAlertDao alertDao, FundReconcileDao reconcileDao) {
        this.tx = tx;
        this.visibility = visibility;
        this.tradingRoleChecker = tradingRoleChecker;
        this.config = config;
        this.ledgerDao = ledgerDao;
        this.flowDao = flowDao;
        this.dwDao = dwDao;
        this.transferDao = transferDao;
        this.receiptDao = receiptDao;
        this.reviewDao = reviewDao;
        this.quotaDao = quotaDao;
        this.alertDao = alertDao;
        this.reconcileDao = reconcileDao;
    }

    /** 配置读取接缝（{@code CFG-25}；避免 {@code FundService} 直接依赖 CONFIG 模块 DAO）。 */
    @FunctionalInterface
    public interface ConfigReader {
        /**
         * 读取配置值。
         *
         * @param key 配置键
         * @return 配置值；不存在返回 {@code null}
         */
        String value(String key);
    }

    // ================================================================ 一、台账建立与查询（FR-FUND-1/3）

    /**
     * 建立子账户资金台账（{@code FR-FUND-1}；{@code POST /fund/ledger}）。
     *
     * <p>初始资金建立**须双人复核**并留痕；同账号复核被系统拒绝并写越权留痕。
     *
     * @param req        建立请求
     * @param operatorId 操作人（发起人）
     * @return 已建立的台账
     */
    public FundLedger initLedger(LedgerInitRequest req, long operatorId) {
        if (req == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "台账建立请求不得为空（FR-FUND-1）");
        }
        int sub = requireSubAccount(req.subAccountId, "subAccountId");
        LocalDate date = parseDate(req.ledgerDate, "ledgerDate");
        BigDecimal begin = nz(dec(req.beginBalance));
        if (begin.signum() < 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "初始资金不得为负：值=" + begin.toPlainString());
        }
        long reviewer = req.reviewerId == null ? 0L : req.reviewerId;
        if (reviewer <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "台账建立须双人复核：复核人缺失（FR-FUND-1 验收4）");
        }
        visibility.assertSubAccountWritable(operatorId, sub);

        if (reviewer == operatorId) {
            // 越权尝试：留痕须提交，故在独立事务内落库后再拒绝（不随主事务回滚）
            tx.inTransaction(conn -> {
                insertReview(sub, BIZ_LEDGER_INIT, 0L, operatorId, reviewer, null, req.reviewComment,
                        "Y", "发起人=复核人，系统拒绝（FR-FUND-7）");
                return null;
            });
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "发起人与复核人不得为同一人（R-FUND-3）：userId=" + operatorId);
        }

        return tx.inTransaction(conn -> {
            FundLedger existing = ledgerDao.findBySubAccountAndDate(sub, date);
            if (existing != null) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "该子账户当日台账已存在：subAccountId=" + sub + " ledgerDate=" + date);
            }
            FundLedger l = new FundLedger();
            l.subAccountId = sub;
            l.ledgerDate = date;
            l.beginBalance = begin;
            l.amountIn = ZERO;
            l.amountOut = ZERO;
            l.transferIn = ZERO;
            l.transferOut = ZERO;
            l.pnlCarryover = ZERO;
            l.commission = ZERO;
            l.marginChange = ZERO;
            l.differenceAdjust = ZERO;
            l.endBalance = begin;
            l.availableFund = begin;
            l.marginOccupied = ZERO;
            l.equity = begin;
            l.riskRatio = null;
            l.isClosed = "N";
            ledgerDao.insert(l, operatorId);
            insertReview(sub, BIZ_LEDGER_INIT, l.id, operatorId, reviewer, REVIEW_APPROVED,
                    req.reviewComment, "N", null);
            return l;
        });
    }

    /**
     * 分页查询资金台账（{@code FR-FUND-3}；{@code GET /fund/ledger}）。
     *
     * @param query       分页
     * @param from        起始记账日（含）
     * @param to          截止记账日（含）
     * @param subAccountId 子账户（可空）
     * @param operatorId  调用者
     * @return 分页台账
     */
    public PageResult<FundLedger> listLedger(PageQuery query, LocalDate from, LocalDate to,
                                             Integer subAccountId, long operatorId) {
        StringBuilder where = new StringBuilder("1 = 1");
        List<Object> args = new ArrayList<>();
        if (from != null) {
            where.append(" AND ledger_date >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND ledger_date <= ?");
            args.add(to);
        }
        if (subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, subAccountId);
            where.append(" AND sub_account_id = ?");
            args.add(subAccountId);
        }
        applyScope(where, args, operatorId);
        return ledgerDao.findPage(query, where.toString(), args);
    }

    /**
     * 分页查询出入金申请（{@code FR-FUND-8} 验收 3「查询接口确认」；{@code GET /fund/apply-list}；
     * {@code T-195}）。
     *
     * <p><b>为何补此只读面</b>：「超时待查」（{@code TIMEOUT_UNKNOWN}）态资金岗须通过查询接口
     * 确认申请 ID 与最终结果——此前申请仅「写后经流水可见」（{@code te-verify-t055} 披露①），
     * 超时态无承载定位入口。本方法<b>只读</b>：复用既有 {@code dwDao.findPage}，不新增写路径、
     * 不改任何既有语义（{@code CheckTaskQueryService} 先例：服务本体最小增量）。
     *
     * <p><b>子账户可见性</b>（{@code NFR-SEC-7}）：显式指定 {@code subAccountId} 时先
     * {@link SubAccountVisibility#assertSubAccountVisible(long, int) assertSubAccountVisible}
     * （读面断言，越权明确 403）；再以 {@link #applyScope} 对整页结果做可见集过滤
     * （空可见集 ⇒ 恒假，fail-closed）。
     *
     * <p><b>WHERE 形态</b>：条件全部为占位符原子（{@code col >= ?} 形），按需以 {@code AND} 连接——
     * 与 {@code listFlow} 同语义；不写恒真字面量原子（{@code 1 = 1} 在内存等价引擎中不可求值）。
     *
     * @param query        分页
     * @param from         申请创建时间起（含；按 {@code created_time} 列）
     * @param to           申请创建时间止（含）
     * @param subAccountId 子账户（可空）
     * @param status       状态（可空；取值域 {@code ck_dw_status}，含 {@code TIMEOUT_UNKNOWN}）
     * @param operatorId   调用者
     * @return 分页申请（{@code id} 升序缺省，由 DAO 全序兜底）
     */
    public PageResult<DepositWithdrawApply> listDepositWithdrawApplies(
            PageQuery query, LocalDateTime from, LocalDateTime to, Integer subAccountId,
            String status, long operatorId) {
        if (status != null) {
            requireDwStatus(status);
        }
        List<String> conds = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (from != null) {
            conds.add("created_time >= ?");
            args.add(from);
        }
        if (to != null) {
            conds.add("created_time <= ?");
            args.add(to);
        }
        if (subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, subAccountId);
            conds.add("sub_account_id = ?");
            args.add(subAccountId);
        }
        if (status != null) {
            conds.add("status = ?");
            args.add(status);
        }
        applyScope(conds::add, args::add, operatorId);
        return dwDao.findPage(query,
                conds.isEmpty() ? null : String.join(" AND ", conds), args);
    }

    /**
     * 校验出入金状态取值域（{@code ck_dw_status} 7 值）；非法值明确报参数错误，不静默忽略
     * （「数据不足时明确报错，不静默降级」）。
     *
     * @param status 状态值
     */
    private void requireDwStatus(String status) {
        switch (status) {
            case DW_PENDING_REVIEW:
            case DW_SUBMITTED:
            case DW_SUCCESS:
            case DW_FAILED:
            case DW_TIMEOUT_UNKNOWN:
            case DW_REJECTED:
            case "PENDING_SUBMIT":
                return;
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "status 取值非法（ck_dw_status）：" + status
                                + "；复现=使用 PENDING_SUBMIT/PENDING_REVIEW/SUBMITTED/SUCCESS"
                                + "/FAILED/TIMEOUT_UNKNOWN/REJECTED 之一");
        }
    }

    /**
     * 分页查询资金流水（{@code FR-FUND-3} / {@code FR-FUND-16}；{@code GET /fund/flow}）。
     *
     * @param query       分页
     * @param from        起始记账日（含）
     * @param to          截止记账日（含）
     * @param subAccountId 子账户（可空）
     * @param flowType    流水类型（可空）
     * @param operatorId  调用者
     * @return 分页流水
     */
    public PageResult<FundFlow> listFlow(PageQuery query, LocalDate from, LocalDate to,
                                         Integer subAccountId, String flowType, long operatorId) {
        // T-195 载体等价改写（披露）：原「1 = 1」恒真字面量前缀 + AND 追加，改为条件收集 + join。
        // 最终 WHERE 语义逐字节等价（X ≡ 1 = 1 AND X）；动因 = 「1 = 1」是 InMemoryDmEngine
        // 不可求值的 WHERE 原子（导出面 exportReconcile → listFlow 在等价引擎测试中不可达，
        // te-verify-t055 O-3 复核在引擎轨上复现该端点时暴露），恒真前缀本身无谓词价值。
        // 判据面 FundServiceTest.scopeFailClosed 断言 lastWhere.contains("sub_account_id IN") 不受影响。
        List<String> conds = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (from != null) {
            conds.add("ledger_date >= ?");
            args.add(from);
        }
        if (to != null) {
            conds.add("ledger_date <= ?");
            args.add(to);
        }
        if (flowType != null && !flowType.isEmpty()) {
            conds.add("flow_type = ?");
            args.add(flowType);
        }
        if (subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, subAccountId);
            conds.add("sub_account_id = ?");
            args.add(subAccountId);
        }
        applyScope(conds::add, args::add, operatorId);
        return flowDao.findPage(query,
                conds.isEmpty() ? null : String.join(" AND ", conds), args);
    }

    /**
     * 按划拨单号取成对流水（{@code FR-FUND-16} 验收1）。
     *
     * @param transferApplyId 划拨单号
     * @param operatorId      调用者
     * @return 流水列表（一出、一入或含回滚流水）
     */
    public List<FundFlow> listTransferFlows(long transferApplyId, long operatorId) {
        List<FundFlow> flows = flowDao.findByTransferApplyId(transferApplyId);
        for (FundFlow f : flows) {
            if (f.subAccountId != null) {
                visibility.assertSubAccountVisible(operatorId, f.subAccountId);
            }
        }
        return flows;
    }

    // ================================================================ 二、出入金（FR-FUND-5/6/7/8/9）

    /**
     * 发起出入金申请（{@code FR-FUND-5}/{@code FR-FUND-6}）。
     *
     * <p>提交后状态为「待复核」，<b>不阻塞等待同步返回</b>。出金须校验来源子账户可用资金充足
     * 且不得使资金为负。同一幂等标识重复提交返回既有申请（不产生第二笔划转）。
     *
     * @param req        申请请求
     * @param operatorId 发起人
     * @return 申请（新建或幂等命中的既有申请）
     */
    public DepositWithdrawApply initiateDepositWithdraw(DepositWithdrawRequest req, long operatorId) {
        if (req == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "出入金申请不得为空");
        }
        int sub = requireSubAccount(req.subAccountId, "subAccountId");
        String direction = requireDirection(req.direction);
        BigDecimal amount = requirePositive(req.amount, "amount");
        String idem = requireText(req.idempotencyKey, "idempotencyKey（FR-FUND-5 验收3）");
        visibility.assertSubAccountWritable(operatorId, sub);

        String[] candidateNo = new String[1];
        return retryApplyNo(
                () -> tx.inTransaction(conn -> {
                    DepositWithdrawApply dup = dwDao.findByIdempotencyKey(idem);
                    if (dup != null) {
                        return dup; // 幂等 no-op：同一标识不产生第二笔划转
                    }
                    DepositWithdrawApply a = new DepositWithdrawApply();
                    a.subAccountId = sub;
                    a.direction = direction;
                    a.amount = amount;
                    a.idempotencyKey = idem;
                    a.fundSourceDesc = req.fundSourceDesc;
                    a.purposeDesc = req.purposeDesc;
                    a.applicantId = operatorId;
                    a.status = DW_PENDING_REVIEW;
                    a.originalConfirmed = "N";
                    a.applyNo = nextApplyNo("DW", Clocks.today(), 5, true);
                    candidateNo[0] = a.applyNo;
                    if (DIR_OUT.equals(direction)) {
                        FundLedger l = ledgerDao.findBySubAccountAndDate(sub, Clocks.today());
                        BigDecimal avail = l == null ? ZERO : nz(l.availableFund);
                        if (avail.compareTo(amount) < 0) {
                            throw BizException.of(ErrorCode.PARAM_INVALID,
                                    "出金金额超过来源子账户可用资金（FR-FUND-6 验收2）：可用="
                                            + avail.toPlainString() + " 申请=" + amount.toPlainString());
                        }
                        a.availableBefore = avail;
                    }
                    dwDao.insert(a, operatorId);
                    return a;
                }),
                () -> candidateNo[0],
                no -> dwDao.findByApplyNo(no) != null);
    }

    /**
     * 复核出入金申请（{@code FR-FUND-7}；{@code POST /fund/{id}/review}）。
     *
     * <p>发起人 = 复核人时拒绝并留痕；复核通过后状态转「已提交（等待回执）」，
     * <b>不阻塞等待</b>。驳回须填原因。
     *
     * @param applyId    申请 ID
     * @param req        复核请求
     * @param operatorId 复核人（当前登录用户）
     * @return 复核后的申请
     */
    public DepositWithdrawApply reviewDepositWithdraw(long applyId, ReviewRequest req, long operatorId) {
        DepositWithdrawApply probe = dwDao.findById(applyId);
        if (probe == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "出入金申请不存在：id=" + applyId);
        }
        if (!DW_PENDING_REVIEW.equals(probe.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅「待复核」可复核：当前状态=" + probe.status);
        }
        if (probe.applicantId == operatorId) {
            tx.inTransaction(conn -> {
                insertReview(probe.subAccountId, BIZ_DEPOSIT_WITHDRAW, applyId, operatorId, operatorId,
                        null, null, "Y", "发起人=复核人，系统拒绝（FR-FUND-7 验收2）");
                return null;
            });
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "发起人与复核人不得为同一人（FR-FUND-7 验收1）：userId=" + operatorId);
        }
        boolean approved = req == null || req.approved;
        String rejectReason = req == null ? null : req.rejectReason;
        String comment = req == null ? null : req.comment;
        if (!approved && (rejectReason == null || rejectReason.trim().isEmpty())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "驳回须填写原因（FR-FUND-7 验收3）");
        }
        return tx.inTransaction(conn -> {
            DepositWithdrawApply a = dwDao.findById(applyId);
            a.reviewerId = operatorId;
            a.reviewTime = Clocks.now();
            if (approved) {
                a.status = DW_SUBMITTED;
                a.submitTime = Clocks.now();
                insertReview(a.subAccountId, BIZ_DEPOSIT_WITHDRAW, applyId, a.applicantId, operatorId,
                        REVIEW_APPROVED, comment, "N", null);
            } else {
                a.status = DW_REJECTED;
                a.rejectReason = rejectReason;
                insertReview(a.subAccountId, BIZ_DEPOSIT_WITHDRAW, applyId, a.applicantId, operatorId,
                        REVIEW_REJECTED, comment, "N", rejectReason);
            }
            dwDao.updateById(a, operatorId);
            return a;
        });
    }

    /**
     * 处理 CTP 资金划转回执（{@code FR-FUND-8}；{@code POST /fund/receipt}）。
     *
     * <p>唯一记账依据；重复回执幂等（不重复记账）；失败回执不记账并记录原因；
     * 超时（{@code PROCESSING}/{@code UNKNOWN}）置「超时待查」，<b>不得假设未到账</b>。
     *
     * @param req        回执
     * @param operatorId 操作人
     * @return 处理结果
     */
    public ReceiptResult onFundReceipt(ReceiptRequest req, long operatorId) {
        if (req == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "回执不得为空（FR-FUND-8）");
        }
        String result = requireReceiptResult(req.result);
        String idem = requireText(req.idempotencyKey, "idempotencyKey");
        if (req.applyId == null || req.applyId <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "applyId 须为正整数：值=" + req.applyId);
        }
        long applyId = req.applyId;
        long tsMs = req.receiptTsMs == null ? 0L : req.receiptTsMs;
        BigDecimal amount = req.amount == null ? null : dec(req.amount);

        return tx.inTransaction(conn -> {
            CapitalReceipt dup = receiptDao.findByIdem(idem, result, tsMs);
            if (dup != null) {
                return new ReceiptResult(dup.id, false, "回执重复，幂等 no-op（FR-FUND-8 验收2）");
            }
            DepositWithdrawApply a = dwDao.findById(applyId);
            if (a == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "关联出入金申请不存在：id=" + applyId);
            }
            CapitalReceipt r = new CapitalReceipt();
            r.subAccountId = a.subAccountId;
            r.idempotencyKey = idem;
            r.applyId = applyId;
            r.result = result;
            r.amount = amount == null ? a.amount : amount;
            r.ctpErrorId = req.ctpErrorId;
            r.ctpErrorMsg = req.ctpErrorMsg;
            r.receiptTsMs = tsMs;
            r.receiptTime = Clocks.now();
            r.booked = "N";
            r.queryConfirmed = req.queryConfirmed ? "Y" : "N";
            receiptDao.insert(r, operatorId);

            if (RECEIPT_SUCCESS.equals(result)) {
                if (DW_SUCCESS.equals(a.status)) {
                    return new ReceiptResult(r.id, false,
                            "该申请已成功记账，回执幂等 no-op（不重复记账）");
                }
                FundLedger l = ledgerDao.findBySubAccountAndDate(a.subAccountId, Clocks.today());
                if (l == null) {
                    throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                            "当日台账未建立，无法记账：subAccountId=" + a.subAccountId);
                }
                String flowType = DIR_IN.equals(a.direction) ? FLOW_DEPOSIT : FLOW_WITHDRAW;
                appendFlow(l, flowType, a.amount, null, a.id, null, operatorId,
                        "CTP 回执记账（" + result + "）");
                r.booked = "Y";
                receiptDao.updateById(r, operatorId);
                a.status = DW_SUCCESS;
                dwDao.updateById(a, operatorId);
                return new ReceiptResult(r.id, true, "成功回执已记账并生成资金流水");
            }
            if (RECEIPT_FAIL.equals(result)) {
                a.status = DW_FAILED;
                dwDao.updateById(a, operatorId);
                return new ReceiptResult(r.id, true,
                        "失败回执：不记账，原因已记录（" + nullToEmpty(req.ctpErrorMsg) + "）");
            }
            // PROCESSING / UNKNOWN —— 超时待查，绝不假设未到账
            a.status = DW_TIMEOUT_UNKNOWN;
            dwDao.updateById(a, operatorId);
            return new ReceiptResult(r.id, true,
                    "未收到最终回执，置「超时待查」（不得假设未到账，须主动查询确认）");
        });
    }

    /**
     * 主动查询确认原请求结果（{@code FR-FUND-9}；{@code CMD_QUERY_FUND_RESULT} 接缝）。
     *
     * @param applyId    申请 ID
     * @param confirmed  查询得到的回执（{@code null} 时仅返回当前状态，不作结论）
     * @param operatorId 操作人
     * @return 申请当前状态
     */
    public DepositWithdrawApply confirmTimeoutResult(long applyId, ReceiptRequest confirmed, long operatorId) {
        DepositWithdrawApply a = dwDao.findById(applyId);
        if (a == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "出入金申请不存在：id=" + applyId);
        }
        if (confirmed == null) {
            return a;
        }
        confirmed.applyId = applyId;
        confirmed.queryConfirmed = true;
        onFundReceipt(confirmed, operatorId);
        return tx.inTransaction(conn -> {
            DepositWithdrawApply out = dwDao.findById(applyId);
            // O-1（T-049 退回修复）：originalConfirmed 仅在确认结果**实际收到**（终态 SUCCESS / FAILED）
            // 时置位；查询仍为 PROCESSING / UNKNOWN ⇒ 维持「超时待查」，不放行重试
            // （FR-FUND-9 验收1：确认未到账后方可重试；UNKNOWN ≠ 未到账）。
            if (DW_SUCCESS.equals(out.status) || DW_FAILED.equals(out.status)) {
                out.originalConfirmed = "Y";
                dwDao.updateById(out, operatorId);
            }
            return out;
        });
    }

    /**
     * 重试失败 / 超时的出入金申请（{@code FR-FUND-9}）。
     *
     * <p>超时待查**必须先查询确认原请求真实结果**后方可重试；否则拒绝。重试生成新申请
     * （{@code retry_of_id} 关联原申请），不产生重复划转。
     *
     * @param originalId 原申请 ID
     * @param newIdemKey 新幂等标识
     * @param operatorId 操作人
     * @return 新申请（待复核）
     */
    public DepositWithdrawApply retryDepositWithdraw(long originalId, String newIdemKey, long operatorId) {
        DepositWithdrawApply original = dwDao.findById(originalId);
        if (original == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "原申请不存在：id=" + originalId);
        }
        if (!DW_FAILED.equals(original.status) && !DW_TIMEOUT_UNKNOWN.equals(original.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅「已失败」或「超时待查」可重试：当前状态=" + original.status);
        }
        if (DW_TIMEOUT_UNKNOWN.equals(original.status) && !"Y".equals(original.originalConfirmed)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "未确认原请求真实结果，不得重试（FR-FUND-9 验收2）");
        }
        String idem = requireText(newIdemKey, "newIdempotencyKey");
        visibility.assertSubAccountWritable(operatorId, original.subAccountId);
        String[] candidateNo = new String[1];
        return retryApplyNo(
                () -> tx.inTransaction(conn -> {
                    DepositWithdrawApply dup = dwDao.findByIdempotencyKey(idem);
                    if (dup != null) {
                        return dup;
                    }
                    DepositWithdrawApply a = new DepositWithdrawApply();
                    a.subAccountId = original.subAccountId;
                    a.direction = original.direction;
                    a.amount = original.amount;
                    a.idempotencyKey = idem;
                    a.fundSourceDesc = original.fundSourceDesc;
                    a.purposeDesc = original.purposeDesc;
                    a.applicantId = operatorId;
                    a.status = DW_PENDING_REVIEW;
                    a.originalConfirmed = "N";
                    a.retryOfId = originalId;
                    a.applyNo = nextApplyNo("DW", Clocks.today(), 5, true);
                    candidateNo[0] = a.applyNo;
                    dwDao.insert(a, operatorId);
                    return a;
                }),
                () -> candidateNo[0],
                no -> dwDao.findByApplyNo(no) != null);
    }

    // ================================================================ 三、子账户划拨（FR-FUND-10/11/12/16）

    /**
     * 发起子账户间划拨（{@code FR-FUND-10}；{@code POST /fund/transfer}）。
     *
     * <p>系统内记账；划出/划入不得相同；划出资金须充足；划出/划入子账户当日加总对账
     * 存在未闭环差异时仅阻断差异所属子账户（{@code Q-11}）。
     *
     * @param req        划拨请求
     * @param operatorId 发起人
     * @return 划拨申请（待审批）
     */
    public TransferApply initiateTransfer(TransferRequest req, long operatorId) {
        if (req == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "划拨申请不得为空（FR-FUND-10）");
        }
        int from = requireSubAccount(req.fromSubAccountId, "fromSubAccountId");
        int to = requireSubAccount(req.toSubAccountId, "toSubAccountId");
        if (from == to) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "划出与划入不得为同一子账户（FR-FUND-10 验收4）");
        }
        BigDecimal amount = requirePositive(req.amount, "amount");
        String reason = requireText(req.reason, "reason（划拨理由必填，FR-FUND-10）");
        visibility.assertSubAccountWritable(operatorId, from);
        assertNoOpenDifference(from, "划出");

        String[] candidateNo = new String[1];
        return retryApplyNo(
                () -> tx.inTransaction(conn -> {
                    FundLedger l = ledgerDao.findBySubAccountAndDate(from, Clocks.today());
                    BigDecimal avail = l == null ? ZERO : nz(l.availableFund);
                    if (avail.compareTo(amount) < 0) {
                        throw BizException.of(ErrorCode.PARAM_INVALID,
                                "划出子账户可用资金不足（FR-FUND-10 验收3）：可用=" + avail.toPlainString()
                                        + " 申请=" + amount.toPlainString());
                    }
                    TransferApply t = new TransferApply();
                    t.subAccountId = from;
                    t.fromSubAccountId = from;
                    t.toSubAccountId = to;
                    t.amount = amount;
                    t.reason = reason;
                    t.expectedEffectiveDate = req.expectedEffectiveDate == null || req.expectedEffectiveDate.isEmpty()
                            ? null : parseDate(req.expectedEffectiveDate, "expectedEffectiveDate");
                    t.status = TR_PENDING_APPROVAL;
                    t.applicantId = operatorId;
                    t.applyNo = nextTransferApplyNo(Clocks.today());
                    candidateNo[0] = t.applyNo;
                    transferDao.insert(t, operatorId);
                    return t;
                }),
                () -> candidateNo[0],
                no -> transferDao.findByApplyNo(no) != null);
    }

    /**
     * 审批并记账划拨（{@code FR-FUND-11}/{@code FR-FUND-12}；{@code POST /fund/transfer/{id}/approve}）。
     *
     * <p>发起人 ≠ 审批人；审批人不得为交易岗；驳回须填原因。审批通过后生成两条成对流水
     * （一出、一入，金额相等、同一划拨单号），并校验加总不变；校验不通过即回滚。
     *
     * @param applyId             划拨申请 ID
     * @param req                 审批请求
     * @param operatorId          审批人
     * @param realAccountAvailable 真实账户可用资金（可空；非空时校验 Σ 子账户 = 真实账户）
     * @return 记账 / 回滚 / 驳回后的划拨申请
     */
    public TransferApply approveTransfer(long applyId, TransferApproveRequest req, long operatorId,
                                         BigDecimal realAccountAvailable) {
        TransferApply probe = transferDao.findById(applyId);
        if (probe == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "划拨申请不存在：id=" + applyId);
        }
        if (!TR_PENDING_APPROVAL.equals(probe.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅「待审批」可审批：当前状态=" + probe.status);
        }
        if (probe.applicantId == operatorId) {
            tx.inTransaction(conn -> {
                insertReview(probe.subAccountId, BIZ_TRANSFER, applyId, operatorId, operatorId,
                        null, null, "Y", "发起人=审批人，系统拒绝（FR-FUND-11 验收2）");
                return null;
            });
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "发起人与审批人不得为同一人（R-FUND-3）：userId=" + operatorId);
        }
        if (tradingRoleChecker != null && tradingRoleChecker.hasTradingRole(operatorId)) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "划拨审批人不得为交易岗（R-FUND-3 岗位分离）：userId=" + operatorId);
        }
        boolean approved = req == null || req.approved;
        String rejectReason = req == null ? null : req.rejectReason;
        String comment = req == null ? null : req.comment;
        if (!approved && (rejectReason == null || rejectReason.trim().isEmpty())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "驳回须填写原因（FR-FUND-11 验收3）");
        }
        if (!approved) {
            return tx.inTransaction(conn -> {
                TransferApply t = transferDao.findById(applyId);
                t.status = TR_REJECTED;
                t.approverId = operatorId;
                t.approveTime = Clocks.now();
                t.rejectReason = rejectReason;
                insertReview(t.subAccountId, BIZ_TRANSFER, applyId, t.applicantId, operatorId,
                        REVIEW_REJECTED, comment, "N", rejectReason);
                transferDao.updateById(t, operatorId);
                return t;
            });
        }
        // 审批通过 → 待记账（留痕）→ 记账
        tx.inTransaction(conn -> {
            TransferApply t = transferDao.findById(applyId);
            t.status = TR_PENDING_POSTING;
            t.approverId = operatorId;
            t.approveTime = Clocks.now();
            insertReview(t.subAccountId, BIZ_TRANSFER, applyId, t.applicantId, operatorId,
                    REVIEW_APPROVED, comment, "N", null);
            transferDao.updateById(t, operatorId);
            return null;
        });
        return postTransfer(applyId, realAccountAvailable, operatorId);
    }

    /**
     * 执行划拨记账 + 加总校验（{@code FR-FUND-12}）。
     *
     * @param applyId              划拨申请 ID
     * @param realAccountAvailable 真实账户可用资金（可空）
     * @param operatorId           操作人
     * @return 已记账（{@code POSTED}）或已回滚（{@code ROLLED_BACK}）的申请
     */
    public TransferApply postTransfer(long applyId, BigDecimal realAccountAvailable, long operatorId) {
        return tx.inTransaction(conn -> {
            TransferApply t = transferDao.findById(applyId);
            if (!TR_PENDING_POSTING.equals(t.status)) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "仅「待记账」可记账：当前状态=" + t.status);
            }
            visibility.assertSubAccountWritable(operatorId, t.fromSubAccountId);
            visibility.assertSubAccountWritable(operatorId, t.toSubAccountId);
            FundLedger fromL = ledgerDao.findBySubAccountAndDate(t.fromSubAccountId, Clocks.today());
            FundLedger toL = ledgerDao.findBySubAccountAndDate(t.toSubAccountId, Clocks.today());
            if (fromL == null || toL == null) {
                throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                        "划拨双方当日台账须均已建立：from=" + t.fromSubAccountId
                                + " to=" + t.toSubAccountId);
            }
            if (nz(fromL.availableFund).compareTo(t.amount) < 0) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "划出子账户可用资金不足，拒绝记账（FR-FUND-10 验收3）");
            }
            BigDecimal sumBefore = nz(fromL.availableFund).add(nz(toL.availableFund));
            appendFlow(fromL, FLOW_TRANSFER_OUT, t.amount, t.id, null, t.toSubAccountId, operatorId, t.reason);
            appendFlow(toL, FLOW_TRANSFER_IN, t.amount, t.id, null, t.fromSubAccountId, operatorId, t.reason);
            BigDecimal sumAfter = nz(fromL.availableFund).add(nz(toL.availableFund));
            boolean sumInvariant = sumBefore.compareTo(sumAfter) == 0;
            boolean realOk = realAccountAvailable == null
                    || sumAfter.compareTo(realAccountAvailable) == 0;
            t.postingTime = Clocks.now();
            if (!sumInvariant || !realOk) {
                // 加总校验不通过：回滚本次记账（不得留下半完成划拨），状态置已回滚
                appendFlow(fromL, FLOW_TRANSFER_IN, t.amount, t.id, null, t.toSubAccountId, operatorId,
                        "划拨加总校验不通过，回滚（FR-FUND-12）");
                appendFlow(toL, FLOW_TRANSFER_OUT, t.amount, t.id, null, t.fromSubAccountId, operatorId,
                        "划拨加总校验不通过，回滚（FR-FUND-12）");
                t.status = TR_ROLLED_BACK;
                t.aggCheckPassed = "N";
                t.rollbackTime = Clocks.now();
                transferDao.updateById(t, operatorId);
                LOG.warning("划拨加总校验不通过已回滚：applyNo=" + t.applyNo
                        + "；告警=须人工核查（FR-FUND-12 验收2）");
                return t;
            }
            t.status = TR_POSTED;
            t.aggCheckPassed = "Y";
            transferDao.updateById(t, operatorId);
            return t;
        });
    }

    /**
     * 人工回滚已记账的划拨（{@code FR-FUND-10} 验收「划拨后可回滚」）。
     *
     * @param applyId    划拨申请 ID
     * @param operatorId 操作人
     * @return 已回滚的申请
     */
    public TransferApply rollbackTransfer(long applyId, long operatorId) {
        return tx.inTransaction(conn -> {
            TransferApply t = transferDao.findById(applyId);
            if (t == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "划拨申请不存在：id=" + applyId);
            }
            if (!TR_POSTED.equals(t.status)) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "仅「已记账」可回滚：当前状态=" + t.status);
            }
            visibility.assertSubAccountWritable(operatorId, t.fromSubAccountId);
            visibility.assertSubAccountWritable(operatorId, t.toSubAccountId);
            FundLedger fromL = ledgerDao.findBySubAccountAndDate(t.fromSubAccountId, Clocks.today());
            FundLedger toL = ledgerDao.findBySubAccountAndDate(t.toSubAccountId, Clocks.today());
            if (fromL == null || toL == null) {
                throw BizException.of(ErrorCode.DATA_INSUFFICIENT, "划拨双方当日台账不存在");
            }
            appendFlow(fromL, FLOW_TRANSFER_IN, t.amount, t.id, null, t.toSubAccountId, operatorId, "人工回滚划拨");
            appendFlow(toL, FLOW_TRANSFER_OUT, t.amount, t.id, null, t.fromSubAccountId, operatorId, "人工回滚划拨");
            t.status = TR_ROLLED_BACK;
            t.rollbackTime = Clocks.now();
            transferDao.updateById(t, operatorId);
            return t;
        });
    }

    /**
     * 分页查询划拨记录（{@code FR-FUND-16}；{@code GET /fund/transfer}）。
     *
     * @param query      分页
     * @param status     状态（可空）
     * @param subAccountId 子账户（可空，匹配划出方）
     * @param operatorId 调用者
     * @return 分页划拨记录
     */
    public PageResult<TransferApply> listTransfers(PageQuery query, String status,
                                                   Integer subAccountId, long operatorId) {
        StringBuilder where = new StringBuilder("1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, subAccountId);
            where.append(" AND sub_account_id = ?");
            args.add(subAccountId);
        }
        applyScope(where, args, operatorId);
        return transferDao.findPage(query, where.toString(), args);
    }

    // ================================================================ 四、额度与告警（FR-FUND-13/14；CFG-25）

    /**
     * 设定资金额度（{@code FR-FUND-13}；{@code POST /fund/quota}）。
     *
     * <p>子账户 / 账户两级；账户级不得超过真实账户可用上限。
     *
     * @param req                 额度请求
     * @param realAccountAvailable 真实账户可用资金（账户级校验用；可空）
     * @param operatorId          操作人
     * @return 生效的额度
     */
    public FundQuota setQuota(QuotaRequest req, BigDecimal realAccountAvailable, long operatorId) {
        if (req == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "额度请求不得为空（FR-FUND-13）");
        }
        String scope = req.quotaScope == null ? "" : req.quotaScope.trim().toUpperCase();
        if (!SCOPE_SUB.equals(scope) && !SCOPE_ACCOUNT.equals(scope)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "额度范围须为 SUB_ACCOUNT / ACCOUNT：值=" + req.quotaScope);
        }
        BigDecimal value = dec(req.quotaValue);
        if (value == null || value.signum() < 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "额度值须 >= 0：值=" + req.quotaValue);
        }
        LocalDate from = parseDate(req.effectiveFrom, "effectiveFrom");
        LocalDate to = req.effectiveTo == null || req.effectiveTo.isEmpty()
                ? null : parseDate(req.effectiveTo, "effectiveTo");
        Integer sub = null;
        if (SCOPE_SUB.equals(scope)) {
            sub = requireSubAccount(req.subAccountId, "subAccountId");
            visibility.assertSubAccountWritable(operatorId, sub);
        } else if (realAccountAvailable != null && value.compareTo(realAccountAvailable) > 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "账户级额度不得超过真实账户可用资金上限（FR-FUND-13 验收2）：额度="
                            + value.toPlainString() + " 上限=" + realAccountAvailable.toPlainString());
        }
        final Integer subFinal = sub;
        return tx.inTransaction(conn -> {
            FundQuota existing = findQuota(scope, subFinal, from);
            FundQuota q = existing == null ? new FundQuota() : existing;
            q.subAccountId = subFinal;
            q.quotaScope = scope;
            q.quotaValue = value;
            q.effectiveFrom = from;
            q.effectiveTo = to;
            q.operatorId = operatorId;
            q.changeReason = req.changeReason;
            if (existing == null) {
                quotaDao.insert(q, operatorId);
            } else {
                quotaDao.updateById(q, operatorId);
            }
            return q;
        });
    }

    /**
     * 查询资金额度（{@code FR-FUND-13} 验收4；{@code GET /fund/quota}）。
     *
     * <p><b>读取面子账户隔离（{@code NFR-SEC-7}；{@code TE-T049-002} 退回修复）</b>：原实现在
     * {@code subAccountId == null} 时直接 {@code findByScope}，<b>不施加可见性过滤</b> ⇒ 可见集
     * {@code [1]} 时可读到子账户 2 的额度（跨子账户泄漏）。现按同族先例（{@code T-105}/{@code T-107}）
     * 取数入口接 {@link SubAccountVisibility#visibleSubAccountIds(long)}：非空且不含哨兵 ⇒ 构造
     * {@link SubAccountScope} 下推 DAO（{@code findScoped}）；空集 ⇒ 恒假（fail-closed）；含哨兵
     * {@code 0}（跨账户只读）⇒ 不加过滤。
     *
     * <p><b>账户级不施加子账户过滤</b>：{@code quota_scope = ACCOUNT} 行 {@code sub_account_id} 为
     * 哨兵 {@code NULL}，非子账户维度（同 {@code T-112} 层③口径），若按子账户可见性门禁会漏掉账户级额度。
     *
     * @param subAccountId 子账户（可空）
     * @param quotaScope   范围（可空，缺省 {@code SUB_ACCOUNT}）
     * @param operatorId   调用者
     * @return 可见范围内的额度列表
     */
    public List<FundQuota> listQuota(Integer subAccountId, String quotaScope, long operatorId) {
        if (subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, subAccountId);
            return quotaDao.findBySubAccount(subAccountId);
        }
        String scope = quotaScope == null || quotaScope.trim().isEmpty()
                ? SCOPE_SUB : quotaScope.trim().toUpperCase();
        if (SCOPE_ACCOUNT.equals(scope)) {
            // 账户级：非子账户维度（sub_account_id 哨兵 NULL），不施加子账户过滤（T-112 层③口径）
            return quotaDao.findByScope(scope);
        }
        List<Integer> visible = visibility.visibleSubAccountIds(operatorId);
        if (visible == null || visible.isEmpty()) {
            return Collections.emptyList(); // fail-closed：空可见 ⇒ 恒假，绝不退化为不过滤
        }
        if (visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            return quotaDao.findByScope(scope); // 跨账户只读（哨兵 0）⇒ 不加过滤
        }
        return quotaDao.findScoped(scope, SubAccountScope.of("sub_account_id", visible));
    }

    /**
     * 额度超限检查与告警（{@code FR-FUND-14}；{@code CFG-25}）。
     *
     * <p>资金占用触及或超过额度阈值时写告警；告警**不阻断**减仓与出金
     * （{@code blocked_operation='NONE'}）。
     *
     * @param subAccountId 子账户（账户级传 {@code null}）
     * @param scope        范围（{@code SUB_ACCOUNT} / {@code ACCOUNT}）
     * @param occupied     当前占用
     * @param operatorId   操作人
     * @return 触发的告警（未超限返回空列表）
     */
    public List<FundQuotaAlert> checkQuotaAlert(Integer subAccountId, String scope,
                                                BigDecimal occupied, long operatorId) {
        String scopeNorm = scope == null || scope.trim().isEmpty()
                ? (subAccountId == null ? SCOPE_ACCOUNT : SCOPE_SUB) : scope.trim().toUpperCase();
        BigDecimal occ = nz(occupied);
        FundQuota quota = latestQuota(scopeNorm, subAccountId);
        if (quota == null || quota.quotaValue == null || quota.quotaValue.signum() <= 0) {
            return Collections.emptyList();
        }
        BigDecimal ratio = occ.divide(quota.quotaValue, 6, RoundingMode.HALF_UP);
        String level = alertLevel(ratio);
        if (level == null) {
            return Collections.emptyList();
        }
        List<FundQuotaAlert> out = new ArrayList<>();
        tx.inTransaction(conn -> {
            FundQuotaAlert a = new FundQuotaAlert();
            a.subAccountId = subAccountId;
            a.quotaScope = scopeNorm;
            a.quotaValue = quota.quotaValue;
            a.occupiedValue = occ;
            a.alertLevel = level;
            a.alertTime = Clocks.now();
            a.blockedOperation = "NONE";
            a.disposed = "N";
            alertDao.insert(a, operatorId);
            out.add(a);
            return null;
        });
        return out;
    }

    /**
     * 保证金占用视图（{@code FR-FUND-15}；{@code GET /fund/margin}）。
     *
     * @param date        记账日
     * @param subAccountId 子账户（{@code null} = 汇总）
     * @param operatorId  调用者
     * @return 视图（子账户行 + 汇总）
     */
    public MarginSummary marginView(LocalDate date, Integer subAccountId, long operatorId) {
        List<FundLedger> ledgers = new ArrayList<>();
        if (subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, subAccountId);
            FundLedger l = ledgerDao.findBySubAccountAndDate(subAccountId, date);
            if (l != null) {
                ledgers.add(l);
            }
        } else {
            for (FundLedger l : ledgerDao.findByDate(date)) {
                if (isVisible(operatorId, l.subAccountId)) {
                    ledgers.add(l);
                }
            }
        }
        MarginSummary s = new MarginSummary();
        s.tradeDate = date;
        s.rows = new ArrayList<>();
        for (FundLedger l : ledgers) {
            MarginRow row = new MarginRow();
            row.subAccountId = l.subAccountId;
            row.marginOccupied = nz(l.marginOccupied).toPlainString();
            row.availableFund = nz(l.availableFund).toPlainString();
            row.equity = nz(l.equity).toPlainString();
            row.riskRatio = l.riskRatio == null ? null : l.riskRatio.toPlainString();
            s.rows.add(row);
            s.marginOccupiedTotal = s.marginOccupiedTotal.add(nz(l.marginOccupied));
            s.availableFundTotal = s.availableFundTotal.add(nz(l.availableFund));
            s.equityTotal = s.equityTotal.add(nz(l.equity));
        }
        return s;
    }

    // ================================================================ 五、每日资金对账（FR-FUND-17）

    /**
     * 每日资金对账（{@code FR-FUND-17}；{@code POST /fund/reconcile}）。
     *
     * <p>逐子账户校验 {@code 期初 + 发生额 = 期末}；账户级校验 {@code Σ 子账户 = 真实账户}。
     * 存在未闭环差异时结论不为「一致」。
     *
     * @param date                 交易日
     * @param realAccountAvailable 真实账户可用资金（可空）
     * @param operatorId           操作人
     * @return 对账结果
     */
    public ReconcileOutcome dailyReconcile(LocalDate date, BigDecimal realAccountAvailable, long operatorId) {
        List<FundLedger> ledgers = new ArrayList<>();
        for (FundLedger l : ledgerDao.findByDate(date)) {
            if (isVisible(operatorId, l.subAccountId)) {
                ledgers.add(l);
            }
        }
        ReconcileOutcome out = new ReconcileOutcome();
        out.tradeDate = date;
        out.subRows = new ArrayList<>();
        out.differences = new ArrayList<>();

        BigDecimal beginSum = ZERO;
        BigDecimal occSum = ZERO;
        BigDecimal endSum = ZERO;
        boolean allOk = true;
        for (FundLedger l : ledgers) {
            BigDecimal begin = nz(l.beginBalance);
            BigDecimal occ = occurrenceOf(l);
            BigDecimal end = nz(l.endBalance);
            boolean ok = begin.add(occ).compareTo(end) == 0;
            if (!ok) {
                allOk = false;
                out.differences.add("子账户 " + l.subAccountId + " 期初(" + begin.toPlainString()
                        + ") + 发生额(" + occ.toPlainString() + ") != 期末(" + end.toPlainString() + ")");
            }
            FundReconcile r = upsertReconcile(date, RECON_SUB, l.subAccountId, begin, occ, end,
                    ok ? "Y" : "N", null, null, null, ok ? RECON_CONSISTENT : RECON_INCONSISTENT,
                    operatorId);
            out.subRows.add(r);
            beginSum = beginSum.add(begin);
            occSum = occSum.add(occ);
            endSum = endSum.add(end);
        }
        boolean aggOk = realAccountAvailable == null || endSum.compareTo(realAccountAvailable) == 0;
        if (!aggOk) {
            out.differences.add("Σ 子账户期末(" + endSum.toPlainString() + ") != 真实账户("
                    + realAccountAvailable.toPlainString() + ")");
        }
        String accountResult;
        if (!allOk) {
            accountResult = RECON_OPEN_DIFF;
        } else if (!aggOk) {
            accountResult = RECON_INCONSISTENT;
        } else {
            accountResult = RECON_CONSISTENT;
        }
        FundReconcile acct = upsertReconcile(date, RECON_ACCOUNT, null, beginSum, occSum, endSum,
                allOk ? "Y" : "N", endSum, realAccountAvailable, aggOk ? "Y" : "N", accountResult, operatorId);
        out.accountRow = acct;
        out.result = accountResult;
        return out;
    }

    /**
     * 导出资金对账表（{@code FR-FUND-4}；{@code GET /fund/export}）。
     *
     * <p>含期初 / 各类型发生额小计 / 期末 / 凭证编号；含子账户小计与全账户合计；无数据返回空行。
     *
     * @param from        起始记账日（含）
     * @param to          截止记账日（含）
     * @param subAccountId 子账户（可空）
     * @param operatorId  导出人
     * @return 导出结果
     */
    public ExportResult exportReconcile(LocalDate from, LocalDate to, Integer subAccountId, long operatorId) {
        ExportResult out = new ExportResult();
        out.exportTime = Clocks.now();
        out.exportedBy = operatorId;
        // TE-T049-001 退回修复：导出列补「期初 / 各类型发生额小计 / 期末」（使验收 1
        // 「期初 + 发生额 = 期末」可在导出物上逐行核验）；各行取自所属台账，故同台账的多笔流水
        // 共享同一组期初 / 小计 / 期末（对账行语义）。
        out.columns = Arrays.asList("subAccountId", "ledgerDate", "voucherNo", "flowType",
                "direction", "amount", "balanceAfter",
                "beginBalance", "amountIn", "amountOut", "transferIn", "transferOut",
                "pnlCarryover", "commission", "marginChange", "differenceAdjust",
                "occurrenceTotal", "endBalance", "equation");
        out.rows = new ArrayList<>();

        // TE-T049-001 退回修复：全量分页迭代取尽（原实现硬编码 size=100 且仅第 1 页 ⇒ 150 条被静默截断为 100）。
        List<FundFlow> allFlows = new ArrayList<>();
        int pageNo = 1;
        int size = PageQuery.ALLOWED_SIZES.get(PageQuery.ALLOWED_SIZES.size() - 1); // 100（页大小上限）
        while (true) {
            PageQuery q = PageQuery.of(String.valueOf(pageNo), String.valueOf(size),
                    "ledger_date,asc,voucher_no,asc", "ledger_date", "asc");
            PageResult<FundFlow> page = listFlow(q, from, to, subAccountId, null, operatorId);
            allFlows.addAll(page.items());
            if (page.items().size() < size || allFlows.size() >= page.total()) {
                break;
            }
            pageNo++;
        }

        BigDecimal grandTotal = ZERO;
        Map<Integer, BigDecimal> subTotals = new LinkedHashMap<>();
        Map<String, FundLedger> ledgerCache = new LinkedHashMap<>();
        for (FundFlow f : allFlows) {
            FundLedger ledger = ledgerFor(ledgerCache, f.subAccountId, f.ledgerDate);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subAccountId", f.subAccountId);
            row.put("ledgerDate", f.ledgerDate == null ? null : f.ledgerDate.toString());
            row.put("voucherNo", f.voucherNo);
            row.put("flowType", f.flowType);
            row.put("direction", f.direction);
            row.put("amount", nz(f.amount).toPlainString());
            row.put("balanceAfter", nz(f.balanceAfter).toPlainString());
            row.put("beginBalance", ledger == null ? null : nz(ledger.beginBalance).toPlainString());
            row.put("amountIn", ledger == null ? null : nz(ledger.amountIn).toPlainString());
            row.put("amountOut", ledger == null ? null : nz(ledger.amountOut).toPlainString());
            row.put("transferIn", ledger == null ? null : nz(ledger.transferIn).toPlainString());
            row.put("transferOut", ledger == null ? null : nz(ledger.transferOut).toPlainString());
            row.put("pnlCarryover", ledger == null ? null : nz(ledger.pnlCarryover).toPlainString());
            row.put("commission", ledger == null ? null : nz(ledger.commission).toPlainString());
            row.put("marginChange", ledger == null ? null : nz(ledger.marginChange).toPlainString());
            row.put("differenceAdjust",
                    ledger == null ? null : nz(ledger.differenceAdjust).toPlainString());
            row.put("occurrenceTotal", ledger == null ? null : occurrenceOf(ledger).toPlainString());
            row.put("endBalance", ledger == null ? null : nz(ledger.endBalance).toPlainString());
            row.put("equation", ledger == null ? null
                    : (nz(ledger.beginBalance).add(occurrenceOf(ledger))
                    .compareTo(nz(ledger.endBalance)) == 0 ? "Y" : "N"));
            out.rows.add(row);
            BigDecimal signed = DIR_IN.equals(f.direction) ? nz(f.amount) : nz(f.amount).negate();
            subTotals.merge(f.subAccountId, signed, BigDecimal::add);
            grandTotal = grandTotal.add(signed);
        }
        out.subTotals = subTotals;
        out.grandTotal = grandTotal;
        return out;
    }

    /** 取（子账户, 记账日）所属台账；按 key 缓存，避免逐流水重复查询。 */
    private FundLedger ledgerFor(Map<String, FundLedger> cache, Integer sub, LocalDate date) {
        if (sub == null || date == null) {
            return null;
        }
        String key = sub + "@" + date;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        FundLedger ledger = ledgerDao.findBySubAccountAndDate(sub, date);
        cache.put(key, ledger);
        return ledger;
    }

    // ================================================================ 内部：流水 / 台账

    /**
     * 追加一条资金流水并同步更新台账（只增不改；凭证编号系统内唯一）。
     */
    private FundFlow appendFlow(FundLedger ledger, String flowType, BigDecimal amount,
                                Long transferApplyId, Long depositWithdrawId, Integer counterpart,
                                long operatorId, String remark) {
        String direction;
        switch (flowType) {
            case FLOW_DEPOSIT:
                direction = DIR_IN;
                ledger.amountIn = nz(ledger.amountIn).add(amount);
                break;
            case FLOW_TRANSFER_IN:
                direction = DIR_IN;
                ledger.transferIn = nz(ledger.transferIn).add(amount);
                break;
            case FLOW_WITHDRAW:
                direction = DIR_OUT;
                ledger.amountOut = nz(ledger.amountOut).add(amount);
                break;
            case FLOW_TRANSFER_OUT:
                direction = DIR_OUT;
                ledger.transferOut = nz(ledger.transferOut).add(amount);
                break;
            default:
                throw new IllegalArgumentException("不支持的流水类型：" + flowType
                        + "；复现=仅支持 DEPOSIT/WITHDRAW/TRANSFER_IN/TRANSFER_OUT");
        }
        BigDecimal delta = DIR_IN.equals(direction) ? amount : amount.negate();
        ledger.endBalance = nz(ledger.endBalance).add(delta);
        ledger.availableFund = nz(ledger.availableFund).add(delta);
        ledger.equity = nz(ledger.equity).add(delta);
        ledgerDao.updateById(ledger, operatorId);

        FundFlow f = new FundFlow();
        f.subAccountId = ledger.subAccountId;
        f.ledgerDate = ledger.ledgerDate;
        f.voucherNo = nextVoucher(ledger.ledgerDate);
        f.flowType = flowType;
        f.amount = amount;
        f.direction = direction;
        f.balanceAfter = ledger.endBalance;
        f.transferApplyId = transferApplyId;
        f.depositWithdrawId = depositWithdrawId;
        f.counterpartSubAccountId = counterpart;
        f.operatorId = operatorId;
        f.flowTime = Clocks.now();
        f.remark = remark;
        f.doubleReviewRequired = FLOW_DIFFERENCE_ADJUST.equals(flowType) ? "Y" : "N";
        flowDao.insert(f, operatorId);
        return f;
    }

    /** 当日下一资金凭证编号（「当日最大序号 + 1」，{@code DATA_MODEL.md} §3.5）。 */
    private String nextVoucher(LocalDate date) {
        String prefix = "FV" + date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        long max = 0L;
        for (FundFlow f : flowDao.findByDate(date)) {
            if (f.voucherNo != null && f.voucherNo.startsWith(prefix)) {
                max = Math.max(max, parseSeq(f.voucherNo.substring(prefix.length())));
            }
        }
        return BizNoGenerator.fundVoucher(date, max + 1);
    }

    /**
     * 当日下一申请单号（{@code <前缀><yyyyMMdd><seq(width)>}；{@code DATA_MODEL.md} §3.5
     * 「当日最大序号 + 1」，按业务日重置）。
     *
     * <p>{@code TE-T049-003} 退回修复：原实现取「当日计数 + 1」（前缀 {@code count}），对当日空档
     * 不鲁棒、且并发交错下据陈旧计数生成同一号，第二笔被 {@code uk_dw_apply_no} 唯一约束抛错而无兜底
     * （{@code BUG-003}/{@code T-141} 同族）。改为取当日前缀区间内字典序最大号 + 1（等价「最大序号 + 1」，
     * 定长零填充 ⇒ 字典序最大即序号最大）；{@code UNIQUE} 约束保留为并发兜底不动，
     * {@code R-FUND-5}「系统不自动重试」⇒ 修复落在号生成而非自动重试。
     *
     * @param prefixCode       前缀（{@code DW}）
     * @param date             业务日
     * @param width            序号定长
     * @param depositWithdraw  {@code true}=出入金申请（{@code dwDao}）；{@code false}=划拨（{@code transferDao}）
     * @return 新申请单号
     */
    private String nextApplyNo(String prefixCode, LocalDate date, int width, boolean depositWithdraw) {
        String prefix = prefixCode + date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String max = depositWithdraw
                ? dwDao.findMaxApplyNoByPrefix(prefix)
                : transferDao.findMaxApplyNoByPrefix(prefix);
        long next = 1L;
        if (max != null && max.startsWith(prefix) && max.length() > prefix.length()) {
            next = parseSeq(max.substring(prefix.length())) + 1L;
        }
        StringBuilder sb = new StringBuilder(prefix);
        String seq = Long.toString(next);
        for (int i = seq.length(); i < width; i++) {
            sb.append('0');
        }
        return sb.append(seq).toString();
    }

    /** 当日下一划拨单号（{@code TF<yyyyMMdd><seq(4)>}；「当日最大序号 + 1」）。 */
    private String nextTransferApplyNo(LocalDate date) {
        String prefix = "TF" + date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String max = transferDao.findMaxApplyNoByPrefix(prefix);
        long next = 1L;
        if (max != null && max.startsWith(prefix) && max.length() > prefix.length()) {
            next = parseSeq(max.substring(prefix.length())) + 1L;
        }
        return BizNoGenerator.transferApply(date, next);
    }

    /**
     * 以「唯一约束冲突 ⇒ 重新取号并重试整笔事务」执行一次编号分配
     * （{@code DATA_MODEL.md} §3.5：{@code UNIQUE} 冲突 + 最多 3 次重试）。
     *
     * <p>{@code tx.inTransaction} 内的 DAO 插入撞唯一约束时，{@code TxTemplate} 会把
     * {@link SQLException} 包装为 {@link IllegalStateException}；本方法沿因果链解出唯一冲突
     * （{@code SQLState 23xx}）后交由 {@link BizNoGenerator#retryOnUniqueConflict} 重试整笔事务
     * （回滚后重新取号，与 {@code PlanService} 同口径）。仅当候选编号确实已被占用
     * （= 编号冲突）时重试；其他唯一约束冲突原样抛出，不误报为「编号冲突」、
     * 不做无谓重试。
     *
     * @param attempt     单次尝试（内部须把本次候选编号写入 {@code candidateNo}）
     * @param candidateNo 本次尝试生成的候选编号（跨尝试可读；无编号冲突时可为 {@code null}）
     * @param occupied    候选编号是否已被占用（{@code true}=编号冲突，可重试；{@code false}=其他唯一冲突）
     * @param <T>         返回类型
     * @return 成功尝试的返回值
     */
    private <T> T retryApplyNo(Supplier<T> attempt, Supplier<String> candidateNo,
                               Predicate<String> occupied) {
        Object[] out = new Object[1];
        BizNoGenerator.retryOnUniqueConflict(RETRY_MAX_ATTEMPTS, () -> {
            try {
                out[0] = attempt.get();
                return true;
            } catch (IllegalStateException e) {
                SQLException unique = uniqueViolationCause(e);
                if (unique == null) {
                    throw e;
                }
                String no = candidateNo.get();
                if (no == null || !occupied.test(no)) {
                    throw new IllegalStateException(
                            "唯一约束冲突非申请单号（不重试）；复现=核对该事务其余唯一约束", e);
                }
                throw unique;
            }
        });
        @SuppressWarnings("unchecked")
        T result = (T) out[0];
        return result;
    }

    /**
     * 从异常链中解出「唯一约束冲突」的 {@link SQLException}（编号重试判定用）。
     *
     * <p>{@code TxTemplate} 会把 DAO 抛出的 {@link SQLException} 包装为 {@link IllegalStateException}
     * （{@code TxTemplate.java:147} 附近）；本方法沿因果链查找唯一冲突（{@code SQLState 23xx}）的原始
     * {@link SQLException}，供 {@link BizNoGenerator#retryOnUniqueConflict} 判定并重试；非唯一冲突返回
     * {@code null}（不误重试）。
     *
     * @param error 异常（通常为包装后的 {@link IllegalStateException}）
     * @return 唯一约束冲突的 SQL 异常；非唯一冲突（或不可判定）返回 {@code null}
     */
    static SQLException uniqueViolationCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException
                    && BizNoGenerator.isUniqueViolation((SQLException) cause)) {
                return (SQLException) cause;
            }
        }
        return null;
    }

    /** 落一条复核记录。 */
    private void insertReview(Integer sub, String bizType, long bizId, long applicantId, long reviewerId,
                              String result, String comment, String selfRejected, String rejectReason) {
        FundReviewRecord r = new FundReviewRecord();
        r.subAccountId = sub;
        r.bizType = bizType;
        r.bizId = bizId;
        r.applicantId = applicantId;
        r.reviewerId = reviewerId;
        r.reviewResult = result;
        r.reviewComment = comment;
        r.rejectReason = rejectReason;
        r.selfReviewRejected = selfRejected;
        r.reviewTime = Clocks.now();
        reviewDao.insert(r, reviewerId);
    }

    private FundReconcile upsertReconcile(LocalDate date, String level, Integer sub, BigDecimal begin,
                                          BigDecimal occ, BigDecimal end, String equationOk,
                                          BigDecimal aggCompare, BigDecimal realValue, String aggConsistent,
                                          String result, long operatorId) {
        FundReconcile existing = reconcileDao.findByKey(date, level, sub);
        FundReconcile r = existing == null ? new FundReconcile() : existing;
        r.subAccountId = sub;
        r.tradeDate = date;
        r.reconcileLevel = level;
        r.beginBalance = begin;
        r.occurrenceTotal = occ;
        r.endBalance = end;
        r.balanceEquationOk = equationOk;
        r.aggCompareValue = aggCompare;
        r.realAccountValue = realValue;
        r.aggConsistent = aggConsistent;
        r.result = result;
        r.differenceList = RECON_CONSISTENT.equals(result) ? null : "存在未闭环差异，不得标记对账完成";
        r.reconcileTime = Clocks.now();
        r.operatorId = operatorId;
        if (existing == null) {
            reconcileDao.insert(r, operatorId);
        } else {
            reconcileDao.updateById(r, operatorId);
        }
        return r;
    }

    /** 发生额 = 各发生额列带符号求和（与期末独立，使恒等式检查有牙）。 */
    private static BigDecimal occurrenceOf(FundLedger l) {
        return nz(l.amountIn).subtract(nz(l.amountOut))
                .add(nz(l.transferIn)).subtract(nz(l.transferOut))
                .add(nz(l.pnlCarryover)).subtract(nz(l.commission))
                .subtract(nz(l.marginChange)).add(nz(l.differenceAdjust));
    }

    private FundQuota findQuota(String scope, Integer sub, LocalDate from) {
        List<FundQuota> candidates = sub == null ? quotaDao.findByScope(scope) : quotaDao.findBySubAccount(sub);
        for (FundQuota q : candidates) {
            if (scope.equals(q.quotaScope) && eq(q.effectiveFrom, from)) {
                return q;
            }
        }
        return null;
    }

    private FundQuota latestQuota(String scope, Integer sub) {
        List<FundQuota> candidates = sub == null ? quotaDao.findByScope(scope) : quotaDao.findBySubAccount(sub);
        FundQuota best = null;
        LocalDate today = Clocks.today();
        for (FundQuota q : candidates) {
            if (!scope.equals(q.quotaScope)) {
                continue;
            }
            if (q.effectiveFrom != null && q.effectiveFrom.isAfter(today)) {
                continue;
            }
            if (q.effectiveTo != null && q.effectiveTo.isBefore(today)) {
                continue;
            }
            if (best == null || (q.effectiveFrom != null && best.effectiveFrom != null
                    && q.effectiveFrom.isAfter(best.effectiveFrom))) {
                best = q;
            }
        }
        return best;
    }

    /** 由 {@code CFG-25} 阈值判定告警级别。 */
    private String alertLevel(BigDecimal ratio) {
        double[] t = thresholds();
        if (ratio.compareTo(BigDecimal.valueOf(t[2])) >= 0) {
            return ALERT_CRITICAL;
        }
        if (ratio.compareTo(BigDecimal.valueOf(t[1])) >= 0) {
            return ALERT_WARNING;
        }
        if (ratio.compareTo(BigDecimal.valueOf(t[0])) >= 0) {
            return ALERT_INFO;
        }
        return null;
    }

    private double[] thresholds() {
        String raw = config == null ? null : config.value(CFG_QUOTA_ALERT_LEVELS);
        if (raw == null || raw.trim().isEmpty()) {
            raw = DEFAULT_QUOTA_ALERT_LEVELS;
        }
        String[] parts = raw.split(",");
        if (parts.length < 3) {
            parts = DEFAULT_QUOTA_ALERT_LEVELS.split(",");
        }
        try {
            return new double[]{Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim())};
        } catch (NumberFormatException e) {
            String[] d = DEFAULT_QUOTA_ALERT_LEVELS.split(",");
            return new double[]{Double.parseDouble(d[0]), Double.parseDouble(d[1]), Double.parseDouble(d[2])};
        }
    }

    /** 子账户当日加总对账存在未闭环差异时仅阻断该子账户（{@code Q-11}）。 */
    private void assertNoOpenDifference(int sub, String which) {
        FundReconcile r = reconcileDao.findByKey(Clocks.today(), RECON_SUB, sub);
        if (r != null && r.result != null && !RECON_CONSISTENT.equals(r.result)) {
            throw BizException.blocking(ErrorCode.AGGREGATION_MISMATCH,
                    which + "子账户当日加总对账存在未闭环差异，划拨被阻断（仅限该子账户，Q-11）："
                            + "subAccountId=" + sub + " 结论=" + r.result,
                    "R-FUND-6", String.valueOf(sub), RECON_CONSISTENT,
                    "先闭环该子账户差异后再发起划拨（不波及其他子账户）");
        }
    }

    // ---------------------------------------------------------------- 可见性

    private void applyScope(StringBuilder where, List<Object> args, long operatorId) {
        List<Integer> visible = visibility.visibleSubAccountIds(operatorId);
        if (visible == null || visible.isEmpty()) {
            where.append(" AND sub_account_id IN (-1)");
        } else if (!visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            SubAccountScope scope = SubAccountScope.of("sub_account_id", visible);
            where.append(" AND ").append(scope.sql());
            args.addAll(scope.params());
        }
    }

    /**
     * 子账户可见集过滤（谓词拼接形态；{@code T-195} 随 {@code listDepositWithdrawApplies} 增设）。
     *
     * <p>与 {@link #applyScope(StringBuilder, List, long)} 同一判据，仅承载形态不同：
     * 本重载向条件收集器追加<b>裸谓词</b>（不带 {@code AND} 前缀），供「逐条件拼接 + {@code AND} 连接」
     * 的调用方使用（空可见集 ⇒ 追加恒假条件 {@code sub_account_id IN (-1)}，fail-closed 不变；
     * 含哨兵 0 ⇒ 不追加，跨账户只读不过滤）。
     *
     * @param cond       条件收集器（追加裸谓词，如 {@code sub_account_id IN (?,?)}）
     * @param args       参数收集器（与追加顺序一致）
     * @param operatorId 调用者
     */
    private void applyScope(java.util.function.Consumer<String> cond,
                            java.util.function.Consumer<Object> args, long operatorId) {
        List<Integer> visible = visibility.visibleSubAccountIds(operatorId);
        if (visible == null || visible.isEmpty()) {
            cond.accept("sub_account_id IN (-1)");
        } else if (!visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            SubAccountScope scope = SubAccountScope.of("sub_account_id", visible);
            cond.accept(scope.sql());
            scope.params().forEach(args);
        }
    }

    private boolean isVisible(long operatorId, Integer sub) {
        if (sub == null) {
            return true;
        }
        List<Integer> visible = visibility.visibleSubAccountIds(operatorId);
        if (visible == null || visible.isEmpty()) {
            return false;
        }
        return visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL) || visible.contains(sub);
    }

    // ---------------------------------------------------------------- 参数工具

    private static int requireSubAccount(Integer v, String field) {
        if (v == null || v <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 须为正整数：值=" + v);
        }
        return v;
    }

    private static String requireDirection(String raw) {
        if (raw == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "direction 不得为空（IN/OUT）");
        }
        String d = raw.trim().toUpperCase();
        if (!DIR_IN.equals(d) && !DIR_OUT.equals(d)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "direction 须为 IN / OUT：值=" + raw);
        }
        return d;
    }

    private static String requireReceiptResult(String raw) {
        if (raw == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "回执 result 不得为空");
        }
        String r = raw.trim().toUpperCase();
        if (!RECEIPT_SUCCESS.equals(r) && !RECEIPT_FAIL.equals(r)
                && !RECEIPT_PROCESSING.equals(r) && !RECEIPT_UNKNOWN.equals(r)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "回执 result 须为 SUCCESS / FAIL / PROCESSING / UNKNOWN：值=" + raw);
        }
        return r;
    }

    private static BigDecimal requirePositive(String raw, String field) {
        BigDecimal v = dec(raw);
        if (v == null || v.signum() <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 须 > 0：值=" + raw);
        }
        return v;
    }

    private static BigDecimal dec(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "金额格式非法：" + raw);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private static String requireText(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 不得为空");
        }
        return raw.trim();
    }

    private static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 不得为空（yyyy-MM-dd）");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 格式非法（yyyy-MM-dd）：" + raw);
        }
    }

    private static long parseSeq(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ================================================================ 请求 / 结果 DTO

    /** 台账建立请求。 */
    public static final class LedgerInitRequest {
        /** 子账户。 */
        public Integer subAccountId;
        /** 记账起始日。 */
        public String ledgerDate;
        /** 初始资金。 */
        public String beginBalance;
        /** 复核人。 */
        public Long reviewerId;
        /** 复核意见。 */
        public String reviewComment;
    }

    /** 出入金申请请求。 */
    public static final class DepositWithdrawRequest {
        /** 子账户。 */
        public Integer subAccountId;
        /** IN / OUT。 */
        public String direction;
        /** 金额。 */
        public String amount;
        /** 资金来源说明（入金）。 */
        public String fundSourceDesc;
        /** 用途说明（出金）。 */
        public String purposeDesc;
        /** 幂等标识。 */
        public String idempotencyKey;
    }

    /** 复核请求。 */
    public static final class ReviewRequest {
        /** 是否通过。 */
        public boolean approved = true;
        /** 复核 / 审批意见。 */
        public String comment;
        /** 驳回原因。 */
        public String rejectReason;
    }

    /** 回执请求。 */
    public static final class ReceiptRequest {
        /** 幂等标识。 */
        public String idempotencyKey;
        /** 关联申请。 */
        public Long applyId;
        /** SUCCESS / FAIL / PROCESSING / UNKNOWN。 */
        public String result;
        /** 回执金额。 */
        public String amount;
        /** CTP 错误号。 */
        public Integer ctpErrorId;
        /** 失败原因。 */
        public String ctpErrorMsg;
        /** 回执毫秒时间戳。 */
        public Long receiptTsMs;
        /** 是否为主动查询确认所得。 */
        public boolean queryConfirmed;
    }

    /** 划拨请求。 */
    public static final class TransferRequest {
        /** 划出子账户。 */
        public Integer fromSubAccountId;
        /** 划入子账户。 */
        public Integer toSubAccountId;
        /** 金额。 */
        public String amount;
        /** 划拨理由。 */
        public String reason;
        /** 期望生效日。 */
        public String expectedEffectiveDate;
    }

    /** 划拨审批请求。 */
    public static final class TransferApproveRequest {
        /** 是否通过。 */
        public boolean approved = true;
        /** 审批意见。 */
        public String comment;
        /** 驳回原因。 */
        public String rejectReason;
        /** 真实账户可用资金（加总校验用；可空）。 */
        public String realAccountAvailable;
    }

    /** 额度请求。 */
    public static final class QuotaRequest {
        /** 子账户（账户级为 null）。 */
        public Integer subAccountId;
        /** SUB_ACCOUNT / ACCOUNT。 */
        public String quotaScope;
        /** 额度值。 */
        public String quotaValue;
        /** 生效起始日。 */
        public String effectiveFrom;
        /** 生效截止日。 */
        public String effectiveTo;
        /** 变更原因。 */
        public String changeReason;
        /** 真实账户可用资金（账户级上限校验用；可空）。 */
        public String realAccountAvailable;
    }

    /** 对账请求。 */
    public static final class ReconcileRequest {
        /** 交易日。 */
        public String tradeDate;
        /** 真实账户可用资金（可空）。 */
        public String realAccountAvailable;
    }

    /** 回执处理结果。 */
    public static final class ReceiptResult {
        /** 回执 ID。 */
        public final long receiptId;
        /** 是否实际处理（false = 幂等 no-op）。 */
        public final boolean processed;
        /** 说明。 */
        public final String detail;

        /**
         * 构造。
         *
         * @param receiptId 回执 ID
         * @param processed 是否实际处理
         * @param detail    说明
         */
        public ReceiptResult(long receiptId, boolean processed, String detail) {
            this.receiptId = receiptId;
            this.processed = processed;
            this.detail = detail;
        }
    }

    /** 保证金视图行。 */
    public static final class MarginRow {
        /** 子账户。 */
        public Integer subAccountId;
        /** 占用保证金。 */
        public String marginOccupied;
        /** 可用资金。 */
        public String availableFund;
        /** 权益。 */
        public String equity;
        /** 风险度。 */
        public String riskRatio;
    }

    /** 保证金汇总视图。 */
    public static final class MarginSummary {
        /** 记账日。 */
        public LocalDate tradeDate;
        /** 明细行。 */
        public List<MarginRow> rows = new ArrayList<>();
        /** 占用保证金合计。 */
        public BigDecimal marginOccupiedTotal = ZERO;
        /** 可用资金合计。 */
        public BigDecimal availableFundTotal = ZERO;
        /** 权益合计。 */
        public BigDecimal equityTotal = ZERO;
    }

    /** 对账结果。 */
    public static final class ReconcileOutcome {
        /** 交易日。 */
        public LocalDate tradeDate;
        /** 结论。 */
        public String result;
        /** 子账户级对账行。 */
        public List<FundReconcile> subRows = new ArrayList<>();
        /** 账户级对账行。 */
        public FundReconcile accountRow;
        /** 差异清单。 */
        public List<String> differences = new ArrayList<>();
    }

    /** 导出结果。 */
    public static final class ExportResult {
        /** 导出时间。 */
        public LocalDateTime exportTime;
        /** 导出人。 */
        public long exportedBy;
        /** 列名。 */
        public List<String> columns;
        /** 行数据。 */
        public List<Map<String, Object>> rows;
        /** 子账户小计（带符号）。 */
        public Map<Integer, BigDecimal> subTotals = new LinkedHashMap<>();
        /** 全账户合计（带符号）。 */
        public BigDecimal grandTotal = ZERO;
    }
}
