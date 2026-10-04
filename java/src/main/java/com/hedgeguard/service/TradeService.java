package com.hedgeguard.service;

import com.hedgeguard.bridge.AckResult;
import com.hedgeguard.bridge.HgcpConstants;
import com.hedgeguard.bridge.HgcpEventListener;
import com.hedgeguard.bridge.PlaceOrderCmd;
import com.hedgeguard.bridge.PlaceOrderResult;
import com.hedgeguard.bridge.TradingBridge;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.BizNoGenerator;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.TradeCommandFlowDao;
import com.hedgeguard.dao.TradeFillDao;
import com.hedgeguard.dao.TradeOrderDao;
import com.hedgeguard.dao.TradeOrderReportDao;
import com.hedgeguard.dao.TradePlanDao;
import com.hedgeguard.dao.TradePositionDao;
import com.hedgeguard.dao.TradePreTradeRejectDao;
import com.hedgeguard.dao.TradeReasonDao;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.TradeCommandFlow;
import com.hedgeguard.dao.entity.TradeFill;
import com.hedgeguard.dao.entity.TradeOrder;
import com.hedgeguard.dao.entity.TradeOrderReport;
import com.hedgeguard.dao.entity.TradePlan;
import com.hedgeguard.dao.entity.TradePosition;
import com.hedgeguard.dao.entity.TradePreTradeReject;
import com.hedgeguard.dao.entity.TradeReason;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 交易执行服务（{@code ARCHITECTURE.md} §3.8 {@code TradeService}；{@code FR-TRADE-1}~{@code 18}）。
 *
 * <p><b>三条结构性保证</b>：
 * <ol>
 *   <li><b>提交指令不落库</b>（{@code FR-TRADE-1} 验收 5 / {@code PROJECT.md} §10-23 / {@code ARCHITECTURE.md} §4.2.6(4) 第 ③ 步）：
 *       {@link #submitOrder} 只做鉴权、理由结构化权威校验与参数校验，随后经 {@link TradingBridge#placeOrder} 下发，
 *       <b>不产生任何 INSERT/UPDATE/DELETE</b>。写库只发生在事件流回传之后（{@link #onOrderAccepted} 等）。</li>
 *   <li><b>交易理由结构化必填</b>（{@code CFG-13} / {@code FR-TRADE-10}）：关联类型 + 关联对象（计划 ID + 敞口 ID，
 *       <b>提交时校验同子账户</b>）+ 时点说明，三项缺一即拒。三层时点中的<b>第①层（Java 提交时权威，DB 读事务内）</b>
 *       由 {@link #validateReasonStructured} 落实；第③层（落库复核）由 {@link #reviewAndPersistReason} 落实。
 *       第②层（C++ {@code 0x1019} 纯内存兜底）在 {@code cpp/**}，不在本类写域。</li>
 *   <li><b>{@code assertCanTrade} 的 M2 服务端强制点</b>（{@code T-102} 裁定；{@code FR-AUTH-9}/{@code R-AUTH-13}）：
 *       {@link #submitOrder}/{@link #confirmOrder}/{@link #cancelOrder} <b>前置</b>调用
 *       {@link AuthService#assertCanTrade}（负责人/管理层禁操盘 析取判据 + 交易岗能力），随后写面
 *       {@link AuthService#assertSubAccountWritable}（{@code T-115}）。</li>
 * </ol>
 *
 * <p><b>与 {@code Project.md} §10-23 的口径说明（据实披露）</b>：架构正文（§4.2.6(4) 第 ③ 步注记、§6.1④）
 * 把该保证表述为「Java <b>不预写库</b> / 不产生 SQL」，而 {@code FR-TRADE-10} 第①层又明确要求权威校验
 * 「Java，<b>DB 事务内</b>」，且 {@code assertCanTrade} 本身读库 ⇒ 提交路径必然产生 SELECT。
 * 本类据此把「零 DB」落实为<b>零写语句</b>（{@link #submitOrder} 不调用任何 DAO 写方法），
 * 并在自测中对 100 笔下单同时报告写语句数（=0）与读语句数。该 READ 口径若与本条验收的字面理解不同，
 * 属<b>待裁定项</b>，已在交付回执单列。
 *
 * <p>分层纪律（{@code §3.8}）：不出现 SQL；不依赖 HTTP 类型；只经 DAO 接口访问数据；
 * 只经 {@link TradingBridge} 与 C++ 交互。
 */
public final class TradeService {

    private static final Logger LOG = Logger.getLogger(TradeService.class.getName());

    /** 交易理由关联类型取值域（{@code ck_trade_reason_related_type}，8 值；提交路径前 6 值为主，后 2 值为 CTP 直生）。 */
    public static final Set<String> RELATED_TYPES = unmodifiable(
            "NEW_EXPOSURE", "EXPOSURE_DECREASE", "PLAN_EXECUTION", "ROLLOVER",
            "RISK_DISPOSAL", "ERROR_CORRECTION", "FORCE_CLOSE", "DELIVERY");

    /** 交易理由时点说明取值域（{@code ck_trade_reason_time_desc}，5 值）。 */
    public static final Set<String> TIME_DESCS = unmodifiable(
            "CONTRACT_SIGNED", "PRICE_TRIGGERED", "MONTHLY_REBALANCE", "EXPIRY_ROLLOVER", "RISK_REQUIRED");

    /** 买卖方向取值域（{@code ck_trade_order_direction}）。 */
    public static final Set<String> DIRECTIONS = unmodifiable("BUY", "SELL");

    /** 开平标志取值域（{@code ck_trade_order_offset}）。 */
    public static final Set<String> OFFSET_FLAGS = unmodifiable("OPEN", "CLOSE", "CLOSE_TODAY");

    /** 价格类型取值域（{@code ck_trade_order_price_type}）。 */
    public static final Set<String> PRICE_TYPES = unmodifiable("LIMIT", "MARKET");

    /** 指令状态：待确认（C++ 受理后）。 */
    public static final String STATUS_PENDING_CONFIRM = "PENDING_CONFIRM";

    /** 指令状态：事前风控拒绝。 */
    public static final String STATUS_REJECTED_PRECHECK = "REJECTED_PRECHECK";

    /** 指令状态：已撤单。 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 指令状态：已失效（确认超时）。 */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 指令状态：已发 CTP。 */
    public static final String STATUS_SENT_CTP = "SENT_CTP";

    /** 桥回执等待上限（毫秒）；超时按组件不可用拒绝（{@code COMPONENT_UNAVAILABLE}）。 */
    private static final long BRIDGE_TIMEOUT_MS = 10_000L;

    private final TxTemplate tx;
    private final TradingBridge bridge;
    private final AuthService authService;
    private final TradePlanDao tradePlanDao;
    private final ExposureDao exposureDao;
    private final TradeOrderDao tradeOrderDao;
    private final TradeReasonDao tradeReasonDao;
    private final TradeOrderReportDao tradeOrderReportDao;
    private final TradeFillDao tradeFillDao;
    private final TradePositionDao tradePositionDao;
    private final TradePreTradeRejectDao preTradeRejectDao;
    private final TradeCommandFlowDao commandFlowDao;

    // ==================== 提交上下文（T-172 硬边界①）====================

    /** 提交上下文有界地图容量上限（硬边界①：容量上限 + 淘汰策略）。 */
    public static final int SUBMIT_CONTEXT_CAPACITY = 4096;

    private final Object submitContextLock = new Object();

    /** 提交上下文主表：C++ 指令号为键；accessOrder=true ⇒ LRU，超容淘汰最久未访问者。 */
    private final LinkedHashMap<Long, SubmitContext> submitContexts =
            new LinkedHashMap<>(16, 0.75f, true);

    /** 次级索引：client_order_ref → order_id（与主表同步淘汰）。 */
    private final Map<String, Long> contextOrderIdsByRef = new HashMap<>();

    /** 品种解析次级索引：instrument_id → product_id（持仓事件无 order_id 时回填 product_id 用）。 */
    private final LinkedHashMap<String, Long> productIdByInstrument =
            new LinkedHashMap<>(16, 0.75f, true);

    /** 累计淘汰计数（判据/观测用）。 */
    private long submitContextEvictions;

    /** 不可溯源事件残留清单（硬边界②）：线格式与提交上下文均无源且字段 NOT NULL 时跳过落库。 */
    private final List<String> residualEvents = Collections.synchronizedList(new ArrayList<>());

    /** 残留清单上限（防不可溯源事件洪泛导致内存无界增长；超出仅记 LOG 不入清单）。 */
    private static final int RESIDUAL_LIST_CAP = 512;

    /** 幂等键当日序号（应用层内存分配，{@code HG<yyyyMMdd><seq(6)>}）；不读库以保交易路径零写。 */
    private final Object orderRefLock = new Object();
    private LocalDate orderRefDay;
    private long orderRefSeq;

    /**
     * 构造。
     *
     * @param tx                事务模板（仅事件流落库 / 理由落库用；提交路径不使用）
     * @param bridge            组件桥（{@code TradingBridge}）
     * @param authService       认证服务（{@code assertCanTrade} 强制点 + 子账户写面断言）
     * @param tradePlanDao      交易计划 DAO（进度 / 占用回写 + 理由同子账户权威校验）
     * @param exposureDao       实货敞口 DAO（理由同子账户权威校验）
     * @param tradeOrderDao     交易指令 DAO（事件流回传后落库）
     * @param tradeReasonDao    交易理由 DAO
     * @param tradeOrderReportDao 委托回报 DAO
     * @param tradeFillDao      成交 DAO
     * @param tradePositionDao  持仓 DAO
     * @param preTradeRejectDao 事前风控拒绝 DAO
     * @param commandFlowDao    指令流水镜像 DAO
     */
    public TradeService(TxTemplate tx, TradingBridge bridge, AuthService authService,
                        TradePlanDao tradePlanDao, ExposureDao exposureDao,
                        TradeOrderDao tradeOrderDao, TradeReasonDao tradeReasonDao,
                        TradeOrderReportDao tradeOrderReportDao, TradeFillDao tradeFillDao,
                        TradePositionDao tradePositionDao, TradePreTradeRejectDao preTradeRejectDao,
                        TradeCommandFlowDao commandFlowDao) {
        this.tx = tx;
        this.bridge = bridge;
        this.authService = authService;
        this.tradePlanDao = tradePlanDao;
        this.exposureDao = exposureDao;
        this.tradeOrderDao = tradeOrderDao;
        this.tradeReasonDao = tradeReasonDao;
        this.tradeOrderReportDao = tradeOrderReportDao;
        this.tradeFillDao = tradeFillDao;
        this.tradePositionDao = tradePositionDao;
        this.preTradeRejectDao = preTradeRejectDao;
        this.commandFlowDao = commandFlowDao;
    }

    // ================================================================== 一、提交指令（不落库）

    /**
     * 提交交易指令（{@code FR-TRADE-1}；{@code POST /api/v1/trade/order} 的服务端入口）。
     *
     * <p>顺序：① 参数与格式 → ② {@code assertCanTrade} + 写面可见性 → ③ 交易理由结构化权威校验（同子账户）
     * → ④ 计划一致性（品种 / 方向 / 剩余可下单量）→ ⑤ 组装 {@link PlaceOrderCmd} 并下发。
     * <b>全程不写库</b>。
     *
     * @param req        提交请求
     * @param operatorId 操作人（取自会话，{@code 0} 视为非法）
     * @return 下单受理结果（含 C++ 指令号与事前风控判定）
     * @throws BizException 鉴权失败 / 理由不合法 / 组件不可用 / 事前风控阻断
     */
    public PlaceOrderResult submitOrder(SubmitRequest req, long operatorId) {
        if (req == null) {
            throw invalid("请求体不得为空：复现=提供 subAccountId/tradePlanId/…（FR-TRADE-1）");
        }
        int subAccountId = requirePositive(req.subAccountId, "subAccountId（随指令显式携带，R-TRADE-1）");
        long tradePlanId = requirePositive(req.tradePlanId, "tradePlanId（交易指令必须绑定计划，FR-TRADE-1）");

        // ② 服务端权限强制点（T-102 裁定：M2 Java 下单写路径）
        authService.assertCanTrade(operatorId, subAccountId);
        authService.assertSubAccountWritable(operatorId, subAccountId);

        // ③ 交易理由结构化必填（CFG-13 / FR-TRADE-10 第①层：Java 提交时权威，同子账户）
        validateReasonStructured(req.reason, subAccountId, operatorId);

        // ④ 计划一致性（FR-TRADE-1 验收 4/5）
        TradePlan plan = tradePlanDao.findById(tradePlanId);
        if (plan == null || plan.subAccountId == null || plan.subAccountId.intValue() != subAccountId) {
            throw invalid("交易计划不存在或不属于该子账户：tradePlanId=" + tradePlanId
                    + " subAccountId=" + subAccountId + "；复现=核对 t_trade_plan 归属（FR-TRADE-1）");
        }
        if (req.productId != null && plan.productId != null && plan.productId.longValue() != req.productId) {
            throw invalid("合约品种与交易计划不一致：计划品种=" + plan.productId
                    + " 请求品种=" + req.productId + "；复现=按计划指定品种下单（FR-TRADE-1 验收4）");
        }
        if (req.direction == null || !DIRECTIONS.contains(req.direction)) {
            throw invalid("买卖方向非法：direction=" + req.direction + "（取值 BUY/SELL）");
        }
        if (plan.direction != null && !plan.direction.equals(req.direction)) {
            throw invalid("指令方向与交易计划方向相反：计划=" + plan.direction + " 指令=" + req.direction
                    + "；复现=按计划方向下单（FR-TRADE-1 验收5）");
        }
        requireText(req.instrumentId, "instrumentId");
        if (req.offsetFlag == null || !OFFSET_FLAGS.contains(req.offsetFlag)) {
            throw invalid("开平标志非法：offsetFlag=" + req.offsetFlag + "（OPEN/CLOSE/CLOSE_TODAY）");
        }
        String priceType = req.priceType == null ? "LIMIT" : req.priceType;
        if (!PRICE_TYPES.contains(priceType)) {
            throw invalid("价格类型非法：priceType=" + priceType + "（LIMIT/MARKET）");
        }
        if (req.volume == null || req.volume <= 0) {
            throw invalid("指令数量必须为正：volume=" + req.volume + "；复现=补齐 volume>0（FR-TRADE-1）");
        }
        if ("LIMIT".equals(priceType) && (req.price == null || req.price.trim().isEmpty())) {
            throw invalid("限价单必须给出价格：priceType=LIMIT；复现=补齐 price");
        }
        if (plan.planQty != null && plan.executedQty != null && plan.orderedQty != null) {
            BigDecimal remaining = plan.planQty.subtract(plan.executedQty).subtract(plan.orderedQty);
            if (BigDecimal.valueOf(req.volume).compareTo(remaining) > 0) {
                throw invalid("数量超过交易计划剩余可下单量：请求=" + req.volume + " 剩余=" + remaining
                        + "；复现=减小 volume（FR-TRADE-1 验收3）");
            }
        }

        // ⑤ 组装并下发（不落库）
        PlaceOrderCmd cmd = new PlaceOrderCmd();
        cmd.subAccountId = subAccountId;
        cmd.tradePlanId = tradePlanId;
        cmd.productId = req.productId == null ? (plan.productId == null ? 0L : plan.productId) : req.productId;
        cmd.instrumentId = req.instrumentId;
        cmd.side = req.direction;
        cmd.offsetFlag = req.offsetFlag;
        cmd.priceType = priceType;
        cmd.price = req.price;
        cmd.volume = String.valueOf(req.volume);
        cmd.tradeReasonId = 0L; // 理由尚未落库（提交路径不写库）；结构化内容随理由对象在事件回传后落库
        cmd.operatorId = operatorId;
        cmd.confirmToken = req.confirmToken;
        cmd.clientOrderRef = nextOrderRef();

        PlaceOrderResult result = await(bridge.placeOrder(cmd));
        // T-172：登记提交上下文（纯内存，非 DAO 写 ⇒ 不破坏验收 5「提交路径零写」）。
        registerSubmitContext(req, plan, cmd, result != null && result.orderId > 0 ? result.orderId : null,
                result == null ? null : result.clientOrderRef);
        if (result == null) {
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                    "交易组件未返回受理结果：复现=检查 hg-trading 是否就绪（FR-TRADE-2 验收4）");
        }
        if (result.systemNotReady) {
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                    "交易组件未就绪（0x0401）：" + result.message
                            + "；复现=等待组件重放完成后重试（FR-TRADE-15 验收2）");
        }
        if (!result.accepted) {
            // 阻断类（10012）四项齐备：由 C++ 事前风控判定结果逐字段承载（NFR-USE-3/4）
            throw blocked(nvl(result.ruleId, "R-RISK-2"), nvl(result.current, "-"),
                    nvl(result.threshold, "-"), nvl(result.nextStep, "请调整后重试"),
                    "事前风控阻断下单：code=0x" + Integer.toHexString(result.componentCode)
                            + " ruleId=" + result.ruleId + (result.message == null ? "" : " msg=" + result.message));
        }
        return result;
    }

    // ================================================================== 二、人工确认 / 撤单

    /**
     * 人工确认指令（{@code FR-TRADE-2}；强制环节，系统不做自动交易）。
     *
     * <p>{@code operatorId} 为线格式 {@code CmdConfirmOrder.operator_id} 的逐字段承载；
     * 与 {@code confirmToken} 分列（{@code CH-018}）。操作人须与提交人一致——由 C++ 复核（{@code 0x0305}）。
     *
     * @param orderId      C++ 指令号
     * @param operatorId   操作人
     * @param confirmToken 确认令牌
     * @return 回执
     */
    public AckResult confirmOrder(long orderId, long operatorId, String confirmToken) {
        if (orderId <= 0) {
            throw invalid("指令号必须为正：orderId=" + orderId);
        }
        authService.assertCanTrade(operatorId, null);
        requireText(confirmToken, "confirmToken（线格式必填）");
        AckResult ack = await(bridge.confirmOrder(orderId, operatorId, confirmToken));
        if (ack == null) {
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                    "交易组件未返回确认回执：orderId=" + orderId);
        }
        return ack;
    }

    /**
     * 撤单（{@code FR-TRADE-6}；仅未成交部分，{@code R-TRADE-7}）。
     *
     * @param orderId    C++ 指令号
     * @param reason     撤单原因（可空）
     * @param operatorId 操作人（{@code FR-TRADE-6} 验收 4：审计可查操作人）
     * @return 回执
     */
    public AckResult cancelOrder(long orderId, String reason, long operatorId) {
        if (orderId <= 0) {
            throw invalid("指令号必须为正：orderId=" + orderId);
        }
        authService.assertCanTrade(operatorId, null);
        AckResult ack = await(bridge.cancelOrder(orderId, reason, operatorId));
        if (ack == null) {
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                    "交易组件未返回撤单回执：orderId=" + orderId);
        }
        return ack;
    }

    // ================================================================== 三、交易理由（三层时点之第①层）

    /**
     * <b>交易理由结构化权威校验</b>（{@code CFG-13} / {@code FR-TRADE-10} 第①层，<b>提交时</b>，Java）。
     *
     * <p>三项必填：① 关联类型（8 值）；② 关联对象（计划 ID + 敞口 ID，<b>且须与指令同子账户</b>）；
     * ③ 时点说明（5 值）。提交路径<b>不得</b>缺任一关联对象（缺即拒——{@code CH-004} 明确「提交路径未提供任一
     * 关联对象即拒绝提交，强制性不变」；补填路径另走 {@link #reviewAndPersistReason}）。
     *
     * <p>「同子账户」为跨表归属比对（C++ 不访问 DB，无法判定），故<b>必须</b>在 Java 权威层读库校验
     * （{@code CH-004}「不得只依赖 C++」）。
     *
     * @param r            理由（可空 ⇒ 直接拒绝）
     * @param subAccountId 指令子账户
     * @param operatorId   操作人（留痕用）
     * @throws BizException 任一项不通过即抛（{@code 10012} 合规门禁）
     */
    public void validateReasonStructured(ReasonInput r, int subAccountId, long operatorId) {
        if (r == null) {
            throw invalid("交易理由结构化必填（CFG-13）：须给出 relatedType/relatedPlanId/relatedExposureId/timeDesc"
                    + "；复现=补齐交易理由三项");
        }
        if (r.relatedType == null || !RELATED_TYPES.contains(r.relatedType)) {
            throw invalid("交易理由关联类型必填且须为 8 值之一：relatedType=" + r.relatedType
                    + "（NEW_EXPOSURE/EXPOSURE_DECREASE/PLAN_EXECUTION/ROLLOVER/RISK_DISPOSAL/"
                    + "ERROR_CORRECTION/FORCE_CLOSE/DELIVERY）");
        }
        if (r.timeDesc == null || !TIME_DESCS.contains(r.timeDesc)) {
            throw invalid("交易理由时点说明必填且须为 5 值之一：timeDesc=" + r.timeDesc
                    + "（CONTRACT_SIGNED/PRICE_TRIGGERED/MONTHLY_REBALANCE/EXPIRY_ROLLOVER/RISK_REQUIRED）");
        }
        if (r.relatedPlanId == null || r.relatedExposureId == null) {
            throw invalid("提交路径必须提供关联对象（计划 ID + 实货敞口 ID）：relatedPlanId=" + r.relatedPlanId
                    + " relatedExposureId=" + r.relatedExposureId
                    + "；复现=补齐两项（FR-TRADE-10 验收2；CH-004 提交路径不得暂缺）");
        }
        TradePlan rp = tradePlanDao.findById(r.relatedPlanId);
        if (rp == null) {
            throw invalid("交易理由关联的计划不存在：relatedPlanId=" + r.relatedPlanId
                    + "；复现=核对 t_trade_plan.id");
        }
        Exposure re = exposureDao.findById(r.relatedExposureId);
        if (re == null) {
            throw invalid("交易理由关联的实货敞口不存在：relatedExposureId=" + r.relatedExposureId
                    + "；复现=核对 t_exposure.id");
        }
        if (rp.subAccountId == null || re.subAccountId == null
                || rp.subAccountId.intValue() != subAccountId
                || re.subAccountId.intValue() != subAccountId
                || rp.subAccountId.intValue() != re.subAccountId.intValue()) {
            // 0x1019 语义：阻断类（10012）四项齐备（CH-004 裁定该拒绝成立且不得只依赖 C++）
            throw blocked("R-TRADE-9", "subAccountId=" + subAccountId,
                    "计划/敞口同子账户", "改用本子账户的计划与敞口",
                    "交易理由关联的计划与实货敞口须与指令同子账户（0x1019）：subAccountId=" + subAccountId
                            + " 计划子账户=" + rp.subAccountId + " 敞口子账户=" + re.subAccountId
                            + "；复现=改用本子账户的计划与敞口（FR-TRADE-10 验收3）");
        }
    }

    /**
     * <b>交易理由落库复核并保存</b>（{@code FR-TRADE-10} 第③层，<b>落库复核</b>，Java）。
     *
     * <p>与第①层同源判据、再次比对——防止「提交后归属被并发变更」。本方法允许<b>补填态</b>暂缺关联对象
     * （{@code forSubmitPath=false}）：两列皆空时必须标 {@code related_object_missing_flag='Y'}
     * （{@code ck_trade_reason_related_or_missing} 结构强制）。
     *
     * @param orderId      已落库的交易指令主键
     * @param r            理由
     * @param operatorId   操作人
     * @param forSubmitPath {@code true}=提交路径（关联对象必须齐备）；{@code false}=补填路径（可缺失并标记）
     * @return 理由主键
     */
    public long reviewAndPersistReason(long orderId, ReasonInput r, long operatorId,
                                       boolean forSubmitPath) {
        TradeOrder order = tradeOrderDao.findById(orderId);
        if (order == null || order.subAccountId == null) {
            throw invalid("交易指令不存在或未携带子账户：orderId=" + orderId
                    + "；复现=先由事件流落库指令（FR-TRADE-10 第③层）");
        }
        int subAccountId = order.subAccountId;
        boolean missing = r == null || r.relatedPlanId == null || r.relatedExposureId == null;
        if (forSubmitPath) {
            validateReasonStructured(r, subAccountId, operatorId);
        } else if (r != null && !missing) {
            // 补填态给出完整关联对象时同样按权威同子账户校验（不得借补填路径绕开）
            validateReasonStructured(r, subAccountId, operatorId);
        }
        TradeReason entity = new TradeReason();
        entity.subAccountId = subAccountId;
        entity.tradeOrderId = orderId;
        entity.relatedType = r == null || r.relatedType == null ? "ERROR_CORRECTION" : r.relatedType;
        entity.relatedPlanId = r == null ? null : r.relatedPlanId;
        entity.relatedExposureId = r == null ? null : r.relatedExposureId;
        entity.timeDesc = r == null || r.timeDesc == null ? "RISK_REQUIRED" : r.timeDesc;
        entity.reasonText = r == null ? null : r.reasonText;
        entity.forcedByFreqAlert = r != null && "Y".equals(r.forcedByFreqAlert) ? "Y" : "N";
        boolean relatedMissing = missing;
        entity.relatedObjectMissingFlag = relatedMissing ? "Y" : "N";
        entity.operatorId = operatorId;
        entity.fillTime = Clocks.now();
        entity.isModified = "N";
        entity.reasonMissingFlag = "N";
        return tx.inTransaction(connection -> {
            if (tradeReasonDao.insert(entity, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "该指令已有交易理由（uk_trade_reason_order）：orderId=" + orderId);
            }
            return entity.id;
        });
    }

    // ================================================================== 四、事件流回传后落库（FR-TRADE-4/5/14）

    /**
     * <b>{@code EVT_ORDER_ACCEPTED}</b>：指令被 C++ 受理 ⇒ 落 {@code t_trade_order}（{@code PENDING_CONFIRM}）
     * 与交易理由（第③层落库复核），并<b>占用</b>交易计划量（{@code ordered_qty += volume}）。
     *
     * <p>⚠️ <b>事件源接线缺口（据实披露）</b>：{@code HgcpClient} 当前对 {@code EVT_*} 仅推进水位、不投递 L3，
     * 生产无订阅者（{@code T-050} 复核 {@code D-1}）。本方法为<b>服务侧接缝</b>，供事件面接线单（{@code T-172}）
     * 落地时调用；本任务不改 {@code bridge/**}。
     */
    public void onOrderAccepted(OrderAcceptedEvent e) {
        if (e == null) {
            throw invalid("受理事件为空");
        }
        // T-172：以 order_id / client_order_ref 关联提交上下文，回填线格式未回显的 NOT NULL 字段。
        SubmitContext c = lookupSubmitContext(e.orderId, e.clientOrderRef);
        Integer subAccountId = choose(e.subAccountId, c == null ? null : c.subAccountId);
        String clientOrderRef = choose(e.clientOrderRef, c == null ? null : c.clientOrderRef);
        Long tradePlanId = choose(e.tradePlanId, c == null ? null : c.tradePlanId);
        Long exposureId = choose(e.exposureId, c == null ? null : c.exposureId);
        Long productId = choose(e.productId, c == null ? null : c.productId);
        String instrumentId = choose(e.instrumentId, c == null ? null : c.instrumentId);
        String instrumentType = choose(e.instrumentType, c == null ? null : c.instrumentType);
        String direction = choose(e.direction, c == null ? null : c.direction);
        String offsetFlag = choose(e.offsetFlag, c == null ? null : c.offsetFlag);
        String priceType = choose(e.priceType, c == null ? null : c.priceType);
        String limitPrice = choose(e.limitPrice, c == null ? null : c.price);
        Integer volume = choose(e.volume, c == null ? null : c.volume);
        Long operatorId = choose(e.operatorId, c == null ? null : c.operatorId);
        Long hedgePlanId = c == null ? null : c.hedgePlanId;
        ReasonInput reason = e.reason != null ? e.reason : (c == null ? null : c.reason);
        if (hedgePlanId == null && tradePlanId != null) {
            // 计划为权威源：hedge_plan_id / 品种 / 敞口在事件回传时仍未回显，回退按计划解析
            TradePlan p = tradePlanDao.findById(tradePlanId);
            if (p != null) {
                hedgePlanId = p.hedgePlanId;
                productId = choose(productId, p.productId);
                exposureId = choose(exposureId, p.exposureId);
                instrumentId = choose(instrumentId, p.instrumentId);
                instrumentType = choose(instrumentType, p.instrumentType);
            }
        }
        Integer vol = volume;
        final Long fHedgePlanId = hedgePlanId;
        final Long fExposureId = exposureId;
        final Long fProductId = productId;
        final String fInstrumentId = instrumentId;
        final String fInstrumentType = instrumentType;
        if (skipIfUnsourced("EVT_ORDER_ACCEPTED", e.orderId, clientOrderRef,
                "subAccountId", subAccountId, "clientOrderRef", clientOrderRef,
                "tradePlanId", tradePlanId, "hedgePlanId", hedgePlanId,
                "exposureId", exposureId, "productId", productId,
                "instrumentId", instrumentId, "direction", direction,
                "offsetFlag", offsetFlag, "priceType", priceType,
                "volume", vol, "operatorId", operatorId)) {
            return;
        }
        final LocalDateTime submitTime = choose(e.submitTime, c == null ? null : c.submitTime) != null
                ? choose(e.submitTime, c == null ? null : c.submitTime) : Clocks.now();
        final Long clientTsMs = choose(e.clientTsMs, c == null ? null : c.clientTsMs) != null
                ? choose(e.clientTsMs, c == null ? null : c.clientTsMs) : Clocks.epochMilli();
        tx.inTransaction(connection -> {
            TradeOrder order = new TradeOrder();
            order.subAccountId = subAccountId;
            order.clientOrderRef = clientOrderRef;
            order.orderId = e.orderId;
            order.tradePlanId = tradePlanId;
            order.hedgePlanId = fHedgePlanId;
            order.exposureId = fExposureId;
            order.productId = fProductId;
            order.instrumentId = fInstrumentId;
            order.instrumentType = fInstrumentType == null ? "FUTURES" : fInstrumentType;
            order.direction = direction;
            order.offsetFlag = offsetFlag;
            order.priceType = priceType;
            order.limitPrice = dec(limitPrice);
            order.volume = vol;
            order.operatorId = operatorId;
            order.status = STATUS_PENDING_CONFIRM;
            order.acceptTime = Clocks.now();
            order.submitTime = submitTime;
            order.clientTsMs = clientTsMs;
            order.expireTime = e.expireTime;
            if (tradeOrderDao.insert(order, operatorId == null ? 0L : operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "指令重复受理（uk_trade_order_order_id/client_order_ref）：orderId=" + e.orderId
                                + " ref=" + clientOrderRef);
            }
            if (reason != null) {
                persistReasonInternal(order.id, subAccountId, reason, operatorId, true);
            }
            occupyPlan(tradePlanId, vol);
            return null;
        });
    }

    /**
     * <b>{@code EVT_ORDER_REPORT}</b>：委托回报 ⇒ 落 {@code t_trade_order_report}（幂等键 order_sys_id+status+volume_traded）
     * 并更新指令状态。
     */
    public void onOrderReport(OrderReportEvent e) {
        if (e == null || e.orderId == null) {
            throw invalid("委托回报事件缺少 orderId");
        }
        // T-172：回报线格式不含 sub_account_id / instrument_id / direction / offset_flag，按 order_id 富化。
        SubmitContext c = lookupSubmitContext(e.orderId, null);
        Integer subAccountId = choose(e.subAccountId, c == null ? null : c.subAccountId);
        String instrumentId = choose(e.instrumentId, c == null ? null : c.instrumentId);
        String direction = choose(e.direction, c == null ? null : c.direction);
        String offsetFlag = choose(e.offsetFlag, c == null ? null : c.offsetFlag);
        String limitPrice = choose(e.limitPrice, c == null ? null : c.price);
        if (skipIfUnsourced("EVT_ORDER_REPORT", e.orderId, null,
                "subAccountId", subAccountId, "orderSysId", e.orderSysId,
                "instrumentId", instrumentId, "direction", direction,
                "offsetFlag", offsetFlag, "orderStatus", e.orderStatus,
                "volumeTotal", e.volumeTotal, "reportTsMs", e.reportTsMs,
                "eventSeq", e.eventSeq)) {
            return;
        }
        tx.inTransaction(connection -> {
            TradeOrderReport existing = tradeOrderReportDao.findByIdem(
                    e.orderSysId, e.orderStatus, e.volumeTraded == null ? 0 : e.volumeTraded);
            if (existing != null) {
                return null; // 幂等：重复回报不重复落库（FR-TRADE-4 验收2）
            }
            TradeOrderReport row = new TradeOrderReport();
            row.subAccountId = subAccountId;
            row.orderSysId = e.orderSysId;
            row.orderId = e.orderId;
            row.orderLocalId = e.orderLocalId;
            row.exchangeId = e.exchangeId;
            row.instrumentId = instrumentId;
            row.direction = direction;
            row.offsetFlag = offsetFlag;
            row.orderStatus = e.orderStatus;
            row.limitPrice = dec(limitPrice);
            row.volumeTotal = e.volumeTotal;
            row.volumeTraded = e.volumeTraded;
            row.volumeLeft = e.volumeLeft;
            row.ctpErrorId = e.ctpErrorId;
            row.ctpErrorMsg = e.ctpErrorMsg;
            row.reportTsMs = e.reportTsMs;
            row.reportTime = Clocks.now();
            row.eventSeq = e.eventSeq;
            tradeOrderReportDao.insert(row, 0L);
            TradeOrder order = tradeOrderDao.findByOrderId(e.orderId);
            if (order != null && e.mappedOrderStatus != null) {
                order.status = e.mappedOrderStatus;
                tradeOrderDao.updateById(order, 0L);
            }
            return null;
        });
    }

    /**
     * <b>{@code EVT_TRADE_FILL}</b>：成交 ⇒ 落 {@code t_trade_fill}（幂等键 trade_id）、更新持仓、
     * 回写交易计划进度（{@code executed_qty += fillVolume}，并释放等量占用 {@code ordered_qty -= fillVolume}）。
     */
    public void onFill(FillEvent e) {
        if (e == null || e.tradeId == null || e.fillVolume == null || e.fillVolume <= 0) {
            throw invalid("成交事件缺少 tradeId 或 fillVolume<=0");
        }
        // T-172：成交线格式不含 product_id / instrument_type / hedge_plan_id / trade_plan_id，
        // 按 order_id 关联上下文；上下文缺失时回退按 trade_plan_id 的计划解析（计划为权威源）。
        SubmitContext c = lookupSubmitContext(e.orderId, null);
        Long tradePlanId = choose(e.tradePlanId, c == null ? null : c.tradePlanId);
        TradePlan plan = tradePlanId == null ? null : tradePlanDao.findById(tradePlanId);
        final Integer fSubAccountId = choose(e.subAccountId, c == null ? null : c.subAccountId);
        final Long fHedgePlanId = c != null && c.hedgePlanId != null ? c.hedgePlanId
                : (plan == null ? null : plan.hedgePlanId);
        final Long fExposureId = choose(choose(e.exposureId, c == null ? null : c.exposureId),
                plan == null ? null : plan.exposureId);
        final Long fProductId = choose(choose(e.productId, c == null ? null : c.productId),
                plan == null ? null : plan.productId);
        final String fInstrumentId = choose(choose(e.instrumentId, c == null ? null : e.instrumentId),
                plan == null ? null : plan.instrumentId);
        final String fInstrumentType = choose(choose(e.instrumentType, c == null ? null : e.instrumentType),
                plan == null ? null : plan.instrumentType);
        final String fDirection = choose(e.direction, c == null ? null : c.direction);
        final String fOffsetFlag = choose(e.offsetFlag, c == null ? null : c.offsetFlag);
        if (skipIfUnsourced("EVT_TRADE_FILL", e.orderId, null,
                "subAccountId", fSubAccountId, "tradeId", e.tradeId,
                "orderId", e.orderId, "tradePlanId", tradePlanId,
                "hedgePlanId", fHedgePlanId, "exposureId", fExposureId,
                "productId", fProductId, "instrumentId", fInstrumentId,
                "direction", fDirection, "offsetFlag", fOffsetFlag,
                "fillPrice", e.fillPrice, "fillVolume", e.fillVolume,
                "tradeTsMs", e.tradeTsMs, "eventSeq", e.eventSeq)) {
            return;
        }
        final LocalDate tradeDate = e.tradeDate != null ? e.tradeDate
                : (e.tradeTsMs == null ? Clocks.today()
                        : java.time.Instant.ofEpochMilli(e.tradeTsMs).atZone(Clocks.ZONE).toLocalDate());
        tx.inTransaction(connection -> {
            if (tradeFillDao.findByTradeId(e.tradeId) != null) {
                return null; // 幂等：同一成交回报重复推送不重复创建（FR-TRADE-5 验收4）
            }
            TradeFill fill = new TradeFill();
            fill.subAccountId = fSubAccountId;
            fill.tradeId = e.tradeId;
            fill.orderId = e.orderId;
            fill.orderSysId = e.orderSysId;
            fill.tradePlanId = tradePlanId;
            fill.hedgePlanId = fHedgePlanId;
            fill.exposureId = fExposureId;
            fill.productId = fProductId;
            fill.instrumentId = fInstrumentId;
            fill.instrumentType = fInstrumentType == null ? "FUTURES" : fInstrumentType;
            fill.direction = fDirection;
            fill.offsetFlag = fOffsetFlag;
            fill.fillPrice = dec(e.fillPrice);
            fill.fillVolume = e.fillVolume;
            fill.contractMultiplier = dec(e.contractMultiplier);
            fill.commission = dec(e.commission);
            fill.tradeTsMs = e.tradeTsMs;
            fill.tradeDate = tradeDate;
            fill.fillTime = Clocks.now();
            fill.eventSeq = e.eventSeq;
            fill.settledFlag = "N";
            tradeFillDao.insert(fill, 0L);
            if (tradePlanId != null) {
                TradePlan p = plan != null ? plan : tradePlanDao.findById(tradePlanId);
                if (p != null) {
                    p.executedQty = nz(p.executedQty).add(BigDecimal.valueOf(e.fillVolume));
                    BigDecimal orderedAfter = nz(p.orderedQty).subtract(BigDecimal.valueOf(e.fillVolume));
                    p.orderedQty = orderedAfter.signum() < 0 ? BigDecimal.ZERO : orderedAfter;
                    tradePlanDao.updateById(p, 0L);
                }
            }
            return null;
        });
    }

    /**
     * <b>{@code EVT_POSITION_CHANGE}</b>：持仓变化 ⇒ 按四元键 {@code upsert} {@code t_position}。
     */
    public void onPositionChange(PositionChangeEvent e) {
        if (e == null) {
            throw invalid("持仓变化事件为空");
        }
        // 持仓线格式无 order_id / client_order_ref，product_id 亦未回显；
        // 经提交时登记的 instrument_id → product_id 索引回填（硬边界②：仍无源则 skip+LOG）。
        Long productId = productIdForInstrument(e.instrumentId);
        if (skipIfUnsourced("EVT_POSITION_CHANGE", e.instrumentId, null,
                "subAccountId", e.subAccountId, "instrumentId", e.instrumentId,
                "productId", productId, "direction", e.direction,
                "positionType", e.positionType, "eventSeq", e.eventSeq)) {
            return;
        }
        final Long fProductId = productId;
        final LocalDateTime asOfTime = e.asOfTime == null ? Clocks.now() : e.asOfTime;
        tx.inTransaction(connection -> {
            TradePosition p = tradePositionDao.findByKey(e.subAccountId, e.instrumentId,
                    e.direction, e.positionType);
            if (p == null) {
                p = new TradePosition();
                p.subAccountId = e.subAccountId;
                p.instrumentId = e.instrumentId;
                p.productId = fProductId;
                p.direction = e.direction;
                p.positionType = e.positionType;
                p.positionQty = nz(e.positionQty);
                p.availableQty = nz(e.availableQty);
                p.frozenQty = nz(e.frozenQty);
                p.avgOpenPrice = dec(e.avgOpenPrice);
                p.lastSettlementPrice = dec(e.lastSettlementPrice);
                p.hedgePlanId = e.hedgePlanId;
                p.asOfTime = asOfTime;
                p.eventSeq = e.eventSeq;
                tradePositionDao.insert(p, 0L);
            } else {
                p.positionQty = nz(e.positionQty);
                p.availableQty = nz(e.availableQty);
                p.frozenQty = nz(e.frozenQty);
                p.avgOpenPrice = dec(e.avgOpenPrice);
                p.lastSettlementPrice = dec(e.lastSettlementPrice);
                p.asOfTime = asOfTime;
                p.eventSeq = e.eventSeq;
                tradePositionDao.updateById(p, 0L);
            }
            return null;
        });
    }

    /**
     * <b>{@code EVT_PRE_TRADE_REJECT}</b>：事前风控拒绝（合规证据，只增不改不删）⇒ 落 {@code t_pre_trade_reject}
     * （幂等键 client_order_ref+rule_id）。
     */
    public void onPreTradeReject(PreTradeRejectEvent e) {
        if (e == null) {
            throw invalid("事前风控拒绝事件为空");
        }
        // 事前拒绝线格式不含 direction / volume / reject_reason；前两者按 client_order_ref 富化，
        // reject_reason 线格式无源 ⇒ 由 rule_id/limit_layer/current/threshold 规范化派生
        // （DERIVED[...]，显式标注派生、缺项省略对应段，禁臆造自然语言；编排者裁定，不走 CH）。
        SubmitContext c = lookupSubmitContext(null, e.clientOrderRef);
        Integer subAccountId = choose(e.subAccountId, c == null ? null : c.subAccountId);
        String clientOrderRef = choose(e.clientOrderRef, c == null ? null : c.clientOrderRef);
        String direction = choose(e.direction, c == null ? null : c.direction);
        Integer volume = choose(e.volume, c == null ? null : c.volume);
        String rejectReason = e.rejectReason != null ? e.rejectReason
                : deriveRejectReason(e.ruleId, e.limitLayer, e.currentValue, e.thresholdValue);
        if (skipIfUnsourced("EVT_PRE_TRADE_REJECT", clientOrderRef, clientOrderRef,
                "subAccountId", subAccountId, "clientOrderRef", clientOrderRef,
                "orderDigest", e.orderDigest, "ruleId", e.ruleId,
                "rejectCode", e.rejectCode, "rejectReason", rejectReason,
                "operatorId", e.operatorId, "rejectTsMs", e.rejectTsMs,
                "eventSeq", e.eventSeq)) {
            return;
        }
        final LocalDate tradeDate = e.tradeDate != null ? e.tradeDate
                : (e.rejectTsMs == null ? Clocks.today()
                        : java.time.Instant.ofEpochMilli(e.rejectTsMs).atZone(Clocks.ZONE).toLocalDate());
        tx.inTransaction(connection -> {
            if (preTradeRejectDao.findByIdem(clientOrderRef, e.ruleId) != null) {
                return null;
            }
            TradePreTradeReject row = new TradePreTradeReject();
            row.subAccountId = subAccountId;
            row.clientOrderRef = clientOrderRef;
            row.orderDigest = e.orderDigest;
            row.productId = choose(e.productId, c == null ? null : c.productId);
            row.instrumentId = choose(e.instrumentId, c == null ? null : c.instrumentId);
            row.direction = direction;
            row.volume = volume;
            row.ruleId = e.ruleId;
            row.rejectCode = e.rejectCode;
            row.limitLayer = e.limitLayer;
            row.currentValue = e.currentValue == null ? "-" : e.currentValue;
            row.thresholdValue = e.thresholdValue == null ? "-" : e.thresholdValue;
            row.rejectReason = rejectReason;
            row.operatorId = e.operatorId;
            row.rejectTsMs = e.rejectTsMs;
            row.rejectTime = Clocks.now();
            row.tradeDate = tradeDate;
            row.eventSeq = e.eventSeq;
            row.reportedRetryCount = 0;
            preTradeRejectDao.insert(row, 0L);
            return null;
        });
    }

    /**
     * <b>{@code EVT_COMMAND_FLOW}</b>：指令流水镜像（只增不改）⇒ 落 {@code t_command_flow}
     * （幂等键 client_order_ref+flow_type+flow_ts_ms）。
     */
    public void onCommandFlow(CommandFlowEvent e) {
        if (e == null) {
            throw invalid("指令流水事件为空");
        }
        // 指令流水线格式「整条无 client_order_ref」；按 order_id 关联提交流水上下文回填。
        // 硬边界②：order_id 亦无法关联（未登记 / 已淘汰 / 重启前）⇒ skip+LOG，不硬造 NOT NULL。
        SubmitContext c = lookupSubmitContext(e.orderId, e.clientOrderRef);
        Integer subAccountId = choose(e.subAccountId, c == null ? null : c.subAccountId);
        String clientOrderRef = choose(e.clientOrderRef, c == null ? null : c.clientOrderRef);
        if (skipIfUnsourced("EVT_COMMAND_FLOW", e.orderId, clientOrderRef,
                "subAccountId", subAccountId, "clientOrderRef", clientOrderRef,
                "flowType", e.flowType, "flowTsMs", e.flowTsMs,
                "eventSeq", e.eventSeq)) {
            return;
        }
        tx.inTransaction(connection -> {
            // 幂等（uk_command_flow_idem = client_order_ref+flow_type+flow_ts_ms）：重复投递（断线重连 / 补数重放）
            // 不得重复落库，亦不得因唯一冲突中断事件消费（NFR-REL-5 / R-TRADE-4）。
            if (isDuplicateCommandFlow(clientOrderRef, e.flowType, e.flowTsMs)) {
                return null;
            }
            TradeCommandFlow row = new TradeCommandFlow();
            row.subAccountId = subAccountId;
            row.orderId = e.orderId;
            row.clientOrderRef = clientOrderRef;
            row.flowType = e.flowType;
            row.flowDetail = e.flowDetail;
            row.flowTsMs = e.flowTsMs;
            row.flowTime = Clocks.now();
            row.eventSeq = e.eventSeq;
            row.replayMarker = e.replayMarker == null ? "LIVE" : e.replayMarker;
            commandFlowDao.insert(row, 0L);
            return null;
        });
    }

    /**
     * 指令流水幂等判重（{@code uk_command_flow_idem} 三键合取：{@code client_order_ref+flow_type+flow_ts_ms}）。
     *
     * <p>经既有索引 {@code idx_command_flow_ref} 取同引用流水后按键值比对；{@code flow_ts_ms} 可空，按 null-safe 语义比较。
     * 仅当三键全等才视为重复，任一不同即新流水（防止过宽去重吞掉真实轨迹）。
     */
    private boolean isDuplicateCommandFlow(String clientOrderRef, String flowType, Long flowTsMs) {
        for (TradeCommandFlow existing : commandFlowDao.findByClientOrderRef(clientOrderRef)) {
            if (flowType.equals(existing.flowType)
                    && (flowTsMs == null ? existing.flowTsMs == null : flowTsMs.equals(existing.flowTsMs))) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>{@code EVT_ORDER_CANCEL_RESULT} / {@code EVT_ORDER_EXPIRED}</b>：终态 ⇒ 更新指令状态并
     * <b>释放</b>未成交的占用（{@code ordered_qty -= releaseVolume}）。
     */
    public void onOrderTerminal(OrderTerminalEvent e) {
        if (e == null || e.orderId == null || e.status == null) {
            throw invalid("指令终态事件缺少 orderId/status");
        }
        tx.inTransaction(connection -> {
            TradeOrder order = tradeOrderDao.findByOrderId(e.orderId);
            if (order == null) {
                return null;
            }
            order.status = e.status;
            if (STATUS_CANCELLED.equals(e.status)) {
                order.cancelReason = e.cancelReason;
                order.cancelOperatorId = e.operatorId;
                order.cancelTime = Clocks.now();
            }
            tradeOrderDao.updateById(order, e.operatorId == null ? 0L : e.operatorId);
            if (order.tradePlanId != null && e.releaseVolume != null && e.releaseVolume > 0) {
                TradePlan plan = tradePlanDao.findById(order.tradePlanId);
                if (plan != null) {
                    BigDecimal after = nz(plan.orderedQty).subtract(BigDecimal.valueOf(e.releaseVolume));
                    plan.orderedQty = after.signum() < 0 ? BigDecimal.ZERO : after;
                    tradePlanDao.updateById(plan, 0L);
                }
            }
            return null;
        });
    }

    // ================================================================== 内部工具

    // ---- T-172 提交上下文富化（硬边界①/②）----

    /** 登记提交上下文（submitOrder 内调用；纯内存、非 DAO 写，不破坏验收 5 零写）。 */
    private void registerSubmitContext(SubmitRequest req, TradePlan plan, PlaceOrderCmd cmd,
                                       Long orderId, String clientOrderRef) {
        String ref = clientOrderRef != null ? clientOrderRef : cmd.clientOrderRef;
        if (ref == null) {
            return;
        }
        SubmitContext c = new SubmitContext();
        c.clientOrderRef = ref;
        c.orderId = orderId;
        c.subAccountId = req.subAccountId;
        c.tradePlanId = req.tradePlanId;
        c.hedgePlanId = plan == null ? null : plan.hedgePlanId;
        c.exposureId = req.exposureId != null ? req.exposureId : (plan == null ? null : plan.exposureId);
        c.productId = req.productId != null ? req.productId : (plan == null ? null : plan.productId);
        c.instrumentId = req.instrumentId != null ? req.instrumentId : (plan == null ? null : plan.instrumentId);
        c.instrumentType = plan == null ? null : plan.instrumentType;
        c.direction = req.direction;
        c.offsetFlag = req.offsetFlag;
        c.priceType = req.priceType;
        c.price = req.price;
        c.volume = req.volume;
        c.operatorId = cmd.operatorId;
        c.reason = req.reason;
        c.submitTime = Clocks.now();
        c.clientTsMs = Clocks.epochMilli();
        synchronized (submitContextLock) {
            if (orderId != null && orderId > 0) {
                submitContexts.put(orderId, c);
            }
            contextOrderIdsByRef.put(ref, orderId);
            if (c.instrumentId != null && c.productId != null) {
                productIdByInstrument.put(c.instrumentId, c.productId);
            }
            evictSubmitContexts();
        }
    }

    /** LRU 淘汰：主表/品种索引超容即移除最久未访问者（硬边界①）。 */
    private void evictSubmitContexts() {
        while (submitContexts.size() > SUBMIT_CONTEXT_CAPACITY) {
            java.util.Iterator<Map.Entry<Long, SubmitContext>> it = submitContexts.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            Map.Entry<Long, SubmitContext> eldest = it.next();
            it.remove();
            contextOrderIdsByRef.remove(eldest.getValue().clientOrderRef);
            submitContextEvictions++;
        }
        while (contextOrderIdsByRef.size() > SUBMIT_CONTEXT_CAPACITY) {
            java.util.Iterator<Map.Entry<String, Long>> it = contextOrderIdsByRef.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
        while (productIdByInstrument.size() > SUBMIT_CONTEXT_CAPACITY) {
            java.util.Iterator<String> it = productIdByInstrument.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
    }

    /** 以 order_id / client_order_ref 关联提交上下文（次级索引回退）。 */
    private SubmitContext lookupSubmitContext(Long orderId, String clientOrderRef) {
        synchronized (submitContextLock) {
            SubmitContext c = orderId != null && orderId > 0 ? submitContexts.get(orderId) : null;
            if (c == null && clientOrderRef != null) {
                Long oid = contextOrderIdsByRef.get(clientOrderRef);
                if (oid != null) {
                    c = submitContexts.get(oid);
                }
            }
            return c;
        }
    }

    /** 品种解析：instrument_id 到 product_id；未知返回 null。 */
    private Long productIdForInstrument(String instrumentId) {
        if (instrumentId == null) {
            return null;
        }
        synchronized (submitContextLock) {
            return productIdByInstrument.get(instrumentId);
        }
    }

    /**
     * 品种级解析器（Q-5）：按 order_id 取提交上下文真值，供
     * risk_intraday.IntradayEventFeed.ProductIdResolver 装配（保持品种级分组）；未知返回 0。
     */
    public long productIdForOrder(long orderId) {
        SubmitContext c = lookupSubmitContext(orderId, null);
        return c == null || c.productId == null ? 0L : c.productId;
    }

    /** 提交上下文当前条目数（判据/观测用）。 */
    public int submitContextSize() {
        synchronized (submitContextLock) {
            return submitContexts.size();
        }
    }

    /** 累计淘汰条目数（硬边界① 判据）。 */
    public long submitContextEvictions() {
        synchronized (submitContextLock) {
            return submitContextEvictions;
        }
    }

    /** 不可溯源事件残留清单快照（硬边界②）。 */
    public List<String> residualEvents() {
        synchronized (residualEvents) {
            return new ArrayList<>(residualEvents);
        }
    }

    /**
     * 硬边界②：必要 NOT NULL 字段在「线格式 + 提交上下文」均无源时，跳过落库并登记残留清单。
     *
     * @param nameValuePairs 交替的 (字段名, 值)；值为 null 即视为缺源
     * @return true 表示已跳过（调用方应立即 return）
     */
    private boolean skipIfUnsourced(String event, Object key, String clientOrderRef,
                                    Object... nameValuePairs) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            if (nameValuePairs[i + 1] == null) {
                missing.add(String.valueOf(nameValuePairs[i]));
            }
        }
        if (missing.isEmpty()) {
            return false;
        }
        String msg = event + " 富化后仍缺 NOT NULL 字段 " + missing + "：key=" + key
                + " clientOrderRef=" + clientOrderRef + "（线格式与提交上下文均无源，已跳过落库）";
        LOG.warning(msg);
        synchronized (residualEvents) {
            if (residualEvents.size() < RESIDUAL_LIST_CAP) {
                residualEvents.add(msg);
            }
        }
        return true;
    }

    /** 取值：优先主值，空则回退（T-172 富化统一入口）。 */
    private static <T> T choose(T primary, T fallback) {
        return primary != null ? primary : fallback;
    }

    // ================================================================== T-172 事件接缝适配（供编排者装配）

    /**
     * HGCP 事件接缝适配器：把 bridge 中立载荷映射为六事件 DTO，交给本服务富化落库。
     *
     * <p><b>帧头 SEQ 关联</b>：中立载荷不含 event_seq，而落库 5 表 event_seq 为 NOT NULL。
     * HgcpClient 对同一帧先触发 typed 回调、后触发 onEventConsumed(type,seq)，故本类以
     * 「暂存-冲刷」把帧头 SEQ 补入：typed 回调暂存 (类型, 落库动作)，onEventConsumed 类型匹配
     * 时用 SEQ 冲刷；类型不匹配即丢弃（防异常帧残留误配，NFR-REL-5 不中断消费）。
     *
     * <p><b>装配归编排者</b>：本类不自注册；Bootstrap 生产装配时与 risk_intraday 监听器组合后
     * 经 HgcpClient.setEventListener 注册（本任务写域不含 Bootstrap）。
     */
    public final class HgcpEventSink implements HgcpEventListener {

        private int pendingType;
        private java.util.function.LongConsumer pending;

        private void stage(int type, java.util.function.LongConsumer action) {
            pendingType = type;
            pending = action;
        }

        @Override
        public void onOrderAccepted(OrderAccepted e) {
            stage(HgcpConstants.EVT_ORDER_ACCEPTED, seq -> {
                OrderAcceptedEvent d = new OrderAcceptedEvent();
                d.orderId = e.orderId;
                d.clientOrderRef = e.clientOrderRef;
                TradeService.this.onOrderAccepted(d);
            });
        }

        @Override
        public void onOrderReport(OrderReport e) {
            stage(HgcpConstants.EVT_ORDER_REPORT, seq -> {
                OrderReportEvent d = new OrderReportEvent();
                d.orderId = e.orderId;
                d.orderSysId = e.orderSysId;
                d.orderLocalId = e.orderLocalId;
                d.orderStatus = e.status;
                d.volumeTotal = e.volumeTotal;
                d.volumeTraded = e.volumeTraded;
                d.volumeLeft = e.volumeLeft;
                d.ctpErrorId = e.ctpErrorId;
                d.ctpErrorMsg = e.ctpErrorMsg;
                d.reportTsMs = e.reportTsMs;
                d.eventSeq = seq;
                d.mappedOrderStatus = mapOrderStatus(e.status);
                TradeService.this.onOrderReport(d);
            });
        }

        @Override
        public void onTradeFill(TradeFill e) {
            stage(HgcpConstants.EVT_TRADE_FILL, seq -> {
                FillEvent d = new FillEvent();
                d.tradeId = e.tradeId;
                d.orderId = e.orderId;
                d.orderSysId = e.orderSysId;
                d.instrumentId = e.instrumentId;
                d.direction = strip(e.direction, "DIRECTION_");
                d.offsetFlag = strip(e.offsetFlag, "OFFSET_FLAG_");
                d.fillPrice = e.price;
                d.fillVolume = e.volume;
                d.tradeTsMs = e.tradeTsMs;
                d.subAccountId = e.subAccountId;
                d.tradePlanId = e.tradePlanId;
                d.exposureId = e.exposureId;
                d.eventSeq = seq;
                TradeService.this.onFill(d);
            });
        }

        @Override
        public void onPositionChange(PositionChange e) {
            stage(HgcpConstants.EVT_POSITION_CHANGE, seq -> {
                PositionChangeEvent d = new PositionChangeEvent();
                d.subAccountId = e.subAccountId;
                d.instrumentId = e.instrumentId;
                d.direction = strip(e.direction, "DIRECTION_");
                d.positionType = strip(e.positionType, "POSITION_TYPE_");
                d.positionQty = BigDecimal.valueOf(e.positionQty);
                d.availableQty = BigDecimal.valueOf(e.availableQty);
                d.frozenQty = BigDecimal.valueOf(e.frozenQty);
                d.asOfTime = toDateTime(e.asOfTsMs);
                d.eventSeq = seq;
                TradeService.this.onPositionChange(d);
            });
        }

        @Override
        public void onPreTradeReject(PreTradeReject e) {
            stage(HgcpConstants.EVT_PRE_TRADE_REJECT, seq -> {
                PreTradeRejectEvent d = new PreTradeRejectEvent();
                d.clientOrderRef = e.clientOrderRef;
                d.subAccountId = e.subAccountId;
                d.productId = e.productId;
                d.instrumentId = e.instrumentId;
                d.ruleId = e.ruleId;
                d.rejectCode = "0x" + Integer.toHexString(e.reasonCode);
                d.limitLayer = mapLimitLayer(e.limitLayer);
                d.currentValue = e.currentValue;
                d.thresholdValue = e.thresholdValue;
                d.orderDigest = e.orderDigest;
                d.operatorId = e.operatorId;
                d.rejectTsMs = e.rejectTsMs;
                d.eventSeq = seq;
                TradeService.this.onPreTradeReject(d);
            });
        }

        @Override
        public void onCommandFlow(CommandFlow e) {
            stage(HgcpConstants.EVT_COMMAND_FLOW, seq -> {
                CommandFlowEvent d = new CommandFlowEvent();
                d.orderId = e.orderId;
                d.flowType = strip(e.flowType, "FLOW_TYPE_");
                d.flowDetail = e.detail;
                d.flowTsMs = e.flowTsMs;
                d.eventSeq = seq;
                TradeService.this.onCommandFlow(d);
            });
        }

        /** 每帧结尾由 HgcpClient 触发：类型匹配则用帧头 SEQ 冲刷暂存事件。 */
        @Override
        public void onEventConsumed(int eventType, long seq) {
            java.util.function.LongConsumer action = pending;
            boolean match = action != null && pendingType == eventType;
            pending = null;
            if (match) {
                action.accept(seq);
            }
        }
    }

    /** 去线格式枚举名前缀（如 ORDER_STATUS_ALL_FILLED → ALL_FILLED）。 */
    private static String strip(String value, String prefix) {
        return value == null || !value.startsWith(prefix) ? value : value.substring(prefix.length());
    }

    /** 线格式 OrderStatus 枚举名 → DDL t_trade_order.status 取值（去前缀；UNSET 不回写）。 */
    private static String mapOrderStatus(String wireStatus) {
        String s = strip(wireStatus, "ORDER_STATUS_");
        return s == null || "UNSET".equals(s) ? null : s;
    }

    /** 线格式 LimitLayer 枚举名 → DDL t_pre_trade_reject.limit_layer 取值（去前缀；UNSET 归 NONE）。 */
    private static String mapLimitLayer(String wireLayer) {
        String s = strip(wireLayer, "LIMIT_LAYER_");
        return s == null || "UNSET".equals(s) ? "NONE" : s;
    }

    /**
     * {@code T-172} {@code reject_reason} 规范化派生（{@code EVT_PRE_TRADE_REJECT} 线格式无源，编排者裁定）。
     *
     * <p>由 {@code rule_id} / {@code limit_layer} / {@code current} / {@code threshold} 四个既有线格式字段
     * 确定性派生，形如 {@code DERIVED[rule=<code>;layer=<layer>;cur=<n>;thr=<n>]}：显式 {@code DERIVED[...]}
     * 标注「本值为派生而非线格式原文」，<b>缺项省略对应段</b>（如仅 rule 可用时 {@code DERIVED[rule=<code>]}），
     * <b>禁臆造自然语言原因</b>。故该事件不再因 {@code reject_reason} 缺源而 skip，正常落库。
     */
    static String deriveRejectReason(String ruleId, String limitLayer, String currentValue, String thresholdValue) {
        StringBuilder sb = new StringBuilder("DERIVED[");
        boolean first = appendSegment(sb, true, "rule", ruleId);
        first = appendSegment(sb, first, "layer", limitLayer);
        first = appendSegment(sb, first, "cur", currentValue);
        appendSegment(sb, first, "thr", thresholdValue);
        return sb.append(']').toString();
    }

    /** 追加 {@code name=value} 段（{@code null}/空 值省略）；返回「追加后是否仍为首段」。 */
    private static boolean appendSegment(StringBuilder sb, boolean first, String name, String value) {
        if (value == null || value.isEmpty()) {
            return first;
        }
        if (!first) {
            sb.append(';');
        }
        sb.append(name).append('=').append(value);
        return false;
    }

    /** epoch 毫秒 → 本地日期时间（Asia/Shanghai）。 */
    private static LocalDateTime toDateTime(long epochMilli) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMilli), Clocks.ZONE);
    }

    /** 占用交易计划量（受理时 ordered_qty += volume）。 */
    private void occupyPlan(long tradePlanId, Integer volume) {
        if (volume == null || volume <= 0) {
            return;
        }
        TradePlan plan = tradePlanDao.findById(tradePlanId);
        if (plan == null) {
            return;
        }
        plan.orderedQty = nz(plan.orderedQty).add(BigDecimal.valueOf(volume));
        tradePlanDao.updateById(plan, 0L);
    }

    /** 理由落库（内部，不重复开事务——由调用方事务承载）。 */
    private void persistReasonInternal(long orderId, int subAccountId, ReasonInput r, Long operatorId,
                                       boolean forSubmitPath) {
        if (forSubmitPath) {
            validateReasonStructured(r, subAccountId, operatorId == null ? 0L : operatorId);
        }
        TradeReason entity = new TradeReason();
        entity.subAccountId = subAccountId;
        entity.tradeOrderId = orderId;
        entity.relatedType = r.relatedType;
        entity.relatedPlanId = r.relatedPlanId;
        entity.relatedExposureId = r.relatedExposureId;
        entity.timeDesc = r.timeDesc;
        entity.reasonText = r.reasonText;
        entity.forcedByFreqAlert = "Y".equals(r.forcedByFreqAlert) ? "Y" : "N";
        entity.relatedObjectMissingFlag = "N";
        entity.operatorId = operatorId;
        entity.fillTime = Clocks.now();
        entity.isModified = "N";
        entity.reasonMissingFlag = "N";
        tradeReasonDao.insert(entity, operatorId == null ? 0L : operatorId);
    }

    /** 生成当日幂等键（应用层内存分配，不读库）。 */
    private String nextOrderRef() {
        synchronized (orderRefLock) {
            LocalDate today = Clocks.today();
            if (!today.equals(orderRefDay)) {
                orderRefDay = today;
                orderRefSeq = 0L;
            }
            orderRefSeq++;
            return BizNoGenerator.orderRef(today, orderRefSeq);
        }
    }

    /** 等待桥异步结果（提交/确认/撤单同姿态）。 */
    private static <T> T await(CompletableFuture<T> future) {
        if (future == null) {
            return null;
        }
        try {
            return future.get(BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                    "交易组件回执超时（" + BRIDGE_TIMEOUT_MS + " ms）：复现=检查 hg-trading 连通性");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE, "等待交易组件回执被中断");
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "交易组件调用异常", e.getCause());
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                    "交易组件调用异常：" + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
        }
    }

    /** 参数 / 结构化校验失败（{@code 10001}，非阈值型）。 */
    private static BizException invalid(String message) {
        return BizException.of(ErrorCode.PARAM_INVALID, message);
    }

    /** 阈值型阻断（{@code 10012}，四项必填；{@code NFR-USE-3/4}）。 */
    private static BizException blocked(String ruleId, String current, String threshold,
                                        String nextStep, String message) {
        return BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED, message,
                ruleId, current, threshold, nextStep);
    }

    private static int requirePositive(Integer value, String what) {
        if (value == null || value <= 0) {
            throw invalid(what + " 必填且必须为正：值=" + value);
        }
        return value;
    }

    private static long requirePositive(Long value, String what) {
        if (value == null || value <= 0) {
            throw invalid(what + " 必填且必须为正：值=" + value);
        }
        return value;
    }

    private static void requireText(String value, String what) {
        if (value == null || value.trim().isEmpty()) {
            throw invalid(what + " 不得为空；复现=补齐后重试");
        }
    }

    private static BigDecimal dec(String value) {
        return value == null || value.trim().isEmpty() ? null : new BigDecimal(value);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static Set<String> unmodifiable(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    // ================================================================== DTO

    /**
     * 提交上下文（T-172）：submitOrder 时登记，供线格式事件富化回填 NOT NULL 字段。
     *
     * <p>纯内存、进程内、有界（LRU，容量 SUBMIT_CONTEXT_CAPACITY）——重启后丢失
     * （硬边界③，据实披露于交付回执）。不含口令/确认令牌等敏感字段。
     */
    public static final class SubmitContext {
        public String clientOrderRef;
        public Long orderId;
        public Integer subAccountId;
        public Long tradePlanId;
        public Long hedgePlanId;
        public Long exposureId;
        public Long productId;
        public String instrumentId;
        public String instrumentType;
        public String direction;
        public String offsetFlag;
        public String priceType;
        public String price;
        public Integer volume;
        public Long operatorId;
        public ReasonInput reason;
        public LocalDateTime submitTime;
        public Long clientTsMs;
    }

    /** 提交请求（POST /trade/order 请求体）。 */
    public static final class SubmitRequest {
        /** 子账户（显式携带，必填）。 */
        public Integer subAccountId;
        /** 交易计划 ID（必填）。 */
        public Long tradePlanId;
        /** 实货敞口 ID（订单侧，可空；权威校验以 reason 内为准）。 */
        public Long exposureId;
        /** 品种 ID（可空；非空时须与计划一致）。 */
        public Long productId;
        /** 合约代码（必填）。 */
        public String instrumentId;
        /** 买卖方向 BUY/SELL（必填，须与计划一致）。 */
        public String direction;
        /** 开平标志 OPEN/CLOSE/CLOSE_TODAY（必填）。 */
        public String offsetFlag;
        /** 价格类型 LIMIT/MARKET（缺省 LIMIT）。 */
        public String priceType;
        /** 委托价（LIMIT 必填）。 */
        public String price;
        /** 数量（手，必填且 >0）。 */
        public Integer volume;
        /** 二次确认令牌（价格偏离告警区间时必填，{@code 0x1018}）。 */
        public String confirmToken;
        /** 交易理由（结构化必填）。 */
        public ReasonInput reason;
    }

    /** 交易理由结构化输入（{@code CFG-13}）。 */
    public static final class ReasonInput {
        /** 关联类型（8 值，必填）。 */
        public String relatedType;
        /** 关联对象①计划 ID（提交路径必填）。 */
        public Long relatedPlanId;
        /** 关联对象②实货敞口 ID（提交路径必填）。 */
        public Long relatedExposureId;
        /** 时点说明（5 值，必填）。 */
        public String timeDesc;
        /** 可选补充说明。 */
        public String reasonText;
        /** 是否触发频繁开平仓强制填写（Y/N，可空）。 */
        public String forcedByFreqAlert;
    }

    /** {@code EVT_ORDER_ACCEPTED} 事件载荷。 */
    public static final class OrderAcceptedEvent {
        public Integer subAccountId;
        public Long orderId;
        public String clientOrderRef;
        public Long tradePlanId;
        public Long exposureId;
        public Long productId;
        public String instrumentId;
        public String instrumentType;
        public String direction;
        public String offsetFlag;
        public String priceType;
        public String limitPrice;
        public Integer volume;
        public Long operatorId;
        public Long clientTsMs;
        public LocalDateTime submitTime;
        public LocalDateTime expireTime;
        public ReasonInput reason;
    }

    /** {@code EVT_ORDER_REPORT} 事件载荷。 */
    public static final class OrderReportEvent {
        public Integer subAccountId;
        public Long orderId;
        public String orderSysId;
        public String orderLocalId;
        public String exchangeId;
        public String instrumentId;
        public String direction;
        public String offsetFlag;
        public String orderStatus;
        public String limitPrice;
        public Integer volumeTotal;
        public Integer volumeTraded;
        public Integer volumeLeft;
        public Integer ctpErrorId;
        public String ctpErrorMsg;
        public Long reportTsMs;
        public Long eventSeq;
        /** 映射后的指令状态（可空；空则不更新指令）。 */
        public String mappedOrderStatus;
    }

    /** {@code EVT_TRADE_FILL} 事件载荷。 */
    public static final class FillEvent {
        public Integer subAccountId;
        public String tradeId;
        public Long orderId;
        public String orderSysId;
        public Long tradePlanId;
        public Long exposureId;
        public Long productId;
        public String instrumentId;
        public String instrumentType;
        public String direction;
        public String offsetFlag;
        public String fillPrice;
        public Integer fillVolume;
        public String contractMultiplier;
        public String commission;
        public Long tradeTsMs;
        public LocalDate tradeDate;
        public Long eventSeq;
    }

    /** {@code EVT_POSITION_CHANGE} 事件载荷。 */
    public static final class PositionChangeEvent {
        public Integer subAccountId;
        public String instrumentId;
        public Long productId;
        public String direction;
        public String positionType;
        public BigDecimal positionQty;
        public BigDecimal availableQty;
        public BigDecimal frozenQty;
        public String avgOpenPrice;
        public String lastSettlementPrice;
        public Long hedgePlanId;
        public LocalDateTime asOfTime;
        public Long eventSeq;
    }

    /** {@code EVT_PRE_TRADE_REJECT} 事件载荷。 */
    public static final class PreTradeRejectEvent {
        public Integer subAccountId;
        public String clientOrderRef;
        public String orderDigest;
        public Long productId;
        public String instrumentId;
        public String direction;
        public Integer volume;
        public String ruleId;
        public String rejectCode;
        public String limitLayer;
        public String currentValue;
        public String thresholdValue;
        public String rejectReason;
        public Long operatorId;
        public Long rejectTsMs;
        public LocalDate tradeDate;
        public Long eventSeq;
    }

    /** {@code EVT_COMMAND_FLOW} 事件载荷。 */
    public static final class CommandFlowEvent {
        public Integer subAccountId;
        public Long orderId;
        public String clientOrderRef;
        public String flowType;
        public String flowDetail;
        public Long flowTsMs;
        public Long eventSeq;
        public String replayMarker;
    }

    /** 指令终态事件载荷（{@code EVT_ORDER_CANCEL_RESULT} / {@code EVT_ORDER_EXPIRED}）。 */
    public static final class OrderTerminalEvent {
        public Long orderId;
        public String status;
        public String cancelReason;
        public Long operatorId;
        public Integer releaseVolume;
    }
}
