package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.BizNoGenerator;
import com.hedgeguard.dao.EffectivenessAssessDao;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.HedgeDocDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.HedgeRelationDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.RebalanceRecordDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.EffectivenessAssess;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.HedgeRelation;
import com.hedgeguard.dao.entity.RebalanceRecord;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.JsonCodec;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 套期会计服务 —— 套期关系文档与有效性评估（{@code T-077}；{@code ARCHITECTURE.md} §6.10 首段）。
 *
 * <p><b>本单范围（T-077 四验收）</b>：① 生成套期关系（CAS 24 三要素，承载 = {@code t_hedge_doc}
 * 三要素列 + {@code t_hedge_relation} 正式指定行，R-ACCT-1）；② 三项定性观察点有效性判定
 * （品种相关性 / 方向一致性 / 期限对应性 ±3M，三项均满足即有效，CFG-07）；③ 量化监测指标
 * （CFG-07b 偏离 1 超 ±50% 提示复核）<b>仅监测、不判定有效性</b>（R-ACCT-2 / FR-ACCT-4 验收 2/5）；
 * ④ 评估记录落 {@code t_effectiveness_assess}（含观察点，FR-ACCT-2）。
 *
 * <p><b>边界声明（T-078+ 承接，本单不碰）</b>：FR-ACCT-3 再平衡记录（{@code t_rebalance_record}
 * 写入面）与四类触发判定、FR-ACCT-5 综合评估（{@code t_hedge_effect} 双侧盈亏）、
 * FR-ACCT-6~14 凭证/期间/自检 —— 均不在本单。
 *
 * <p><b>结构性保证</b>：
 * <ol>
 *   <li><b>三要素门禁</b>（R-ACCT-1，CAS 24 红线）：指定套期关系前须存在绑定计划当前版本的
 *       书面文件 {@code t_hedge_doc}，且三要素列全部非空——缺任一要素<b>拒绝生成并列出缺失项</b>
 *       （{@code 10012} 阻断，四项载荷）。文档由 {@code PLAN} 模块写入（{@code PlanService.generateHedgeDoc}
 *       已交付）；ACCT 只读引用（TB-PLAN-4 所有者声明），不复制本体。</li>
 *   <li><b>定性判定与量化监测分离</b>（R-ACCT-2 / Q-7）：结论 = 三项观察点的纯布尔合取；
 *       量化监测值（{@code monitor_deviation_ratio}）只落库并按阈值置 {@code monitor_review_flag}，
 *       <b>不参与结论计算</b>——本类不存在以 80%–125% 区间或任何量化区间为有效性判据的逻辑
 *       （FR-ACCT-4 验收 2 负向判据；全仓 grep 证据见交付回执）。</li>
 *   <li><b>评估记录不可改</b>（FR-ACCT-2 验收 3）：{@code DmEffectivenessAssessDao.updateById}
 *       显式拒绝（T-076 交付）；更正 = 新增记录 + {@code correctedFromId} 引用（{@link #correctAssessment}），
 *       原记录 {@code is_corrected} 置 'Y' 走 <b>DAO 专用标记方法</b>（见该方法披露）。</li>
 *   <li><b>观察点缺失拒绝</b>（FR-ACCT-4 验收 4）：观察点取值（'Y'/'N'）或判定基准缺失 ⇒
 *       评估被拒并<b>说明缺失项</b>；近相关（①='N'）未附书面经济关系说明亦列缺失项。</li>
 * </ol>
 *
 * <p><b>配置读取形态</b>（CFG-07 / CFG-07b）：经 {@link ConfigReader} 接缝惰性读取
 * （{@code FundService}/{@code SettleCheckService} 同族先例）；种子已由 {@code data-init.sql:318/:322}
 * 播种（T-077 实测），缺种子时<b>容错取缺省</b>并仅记录日志（T-188 CFG-26 先例：补播归播种微单）。
 *
 * <p><b>取号（O-4 教训落地）</b>：{@code relation_no} 全系统唯一（{@code uk_hedge_relation_no}），
 * {@code DATA_MODEL.md} §3.5 编号表<b>未规定</b>其形态、亦无业务日重置前提 ⇒ 取号 =
 * <b>全系统前缀 {@code HR} 内字典序最大 + 1</b>（{@link HedgeRelationDao#findMaxRelationNoByPrefix}），
 * 并发兜底 = 唯一约束 + 最多 3 次重试（{@code DATA_MODEL.md} §3.5；{@code FundService.retryApplyNo}
 * 同构形态）——<b>不得照搬「当日最大序号 + 1」</b>（T-137/T-141 判例的前提是编号按业务日重置，
 * 此处不成立，照搬会跨日撞号）。
 *
 * <p><b>子账户隔离</b>（NFR-SEC-7 / T-105/T-107 先例）：读取面按
 * {@link SubAccountVisibility#visibleSubAccountIds}（哨兵 0 = 跨账户只读不过滤；空集 = 恒假条件）；
 * 写面 {@link SubAccountVisibility#assertSubAccountWritable}（仅跨子账户只读者不得写）。
 *
 * <p>分层纪律（{@code §3.8}）：不出现 SQL 语句（E5）；不依赖 HTTP 类型；只经 DAO 接口访问数据。
 */
public final class AcctService {

    private static final Logger LOG = Logger.getLogger(AcctService.class.getName());

    // ---------------------------------------------------------------- 常量（枚举取值与 ck_* 同源）

    /** 套期关系编号前缀（全系统唯一；DATA_MODEL.md §3.5 未规定形态 ⇒ 本服务定义并在回执披露）。 */
    public static final String RELATION_NO_PREFIX = "HR";

    /** 套期关系编号序号定长（对齐 HP/TP 的 4 位序号形态；{@code VARCHAR(32)} 容量充足）。 */
    public static final int RELATION_NO_SEQ_WIDTH = 4;

    /** 再平衡编号前缀（{@code rebalance_no} 全系统唯一；§3.5 未列 ⇒ 本服务定义并在回执披露）。 */
    public static final String REBALANCE_NO_PREFIX = "RB";

    /** 再平衡编号序号定长（4 位；与 HR 同形态）。 */
    public static final int REBALANCE_NO_SEQ_WIDTH = 4;

    /** 状态：已指定。 */
    public static final String REL_DESIGNATED = "DESIGNATED";
    /** 状态：有效。 */
    public static final String REL_EFFECTIVE = "EFFECTIVE";
    /** 状态：需再平衡。 */
    public static final String REL_NEED_REBALANCE = "NEED_REBALANCE";
    /** 状态：已重新指定。 */
    public static final String REL_REDESIGNATED = "REDESIGNATED";
    /** 状态：已终止。 */
    public static final String REL_TERMINATED = "TERMINATED";

    /** 触发类型：敞口变动（敞口侧；CFG-08 exposure_delta_pct）。 */
    public static final String TRIGGER_EXPOSURE_DELTA = "EXPOSURE_DELTA";
    /** 触发类型：套期比率偏离（工具侧；CFG-08 ratio_deviation_pct）。 */
    public static final String TRIGGER_RATIO_DEVIATION = "RATIO_DEVIATION";
    /** 触发类型：计划数量被修订 / 终止（敞口侧）。 */
    public static final String TRIGGER_PLAN_REVISED = "PLAN_REVISED";
    /** 触发类型：敞口消失需终止关系（敞口侧）。 */
    public static final String TRIGGER_EXPOSURE_GONE = "EXPOSURE_GONE";

    /** 触发条件所在侧：敞口侧（Q-8：敞口侧与工具侧必须各设一项，缺口=总量判定的另一侧）。 */
    public static final String SIDE_EXPOSURE = "EXPOSURE_SIDE";
    /** 触发条件所在侧：工具侧。 */
    public static final String SIDE_INSTRUMENT = "INSTRUMENT_SIDE";

    /** 评估方法：定性经济关系（本期唯一方法，ck 约束）。 */
    public static final String METHOD_QUALITATIVE = "QUALITATIVE_ECONOMIC_RELATION";

    /** 结论：有效。 */
    public static final String CONCLUSION_EFFECTIVE = "EFFECTIVE";
    /** 结论：无效。 */
    public static final String CONCLUSION_INEFFECTIVE = "INEFFECTIVE";
    /** 结论：需再平衡。 */
    public static final String CONCLUSION_NEED_REBALANCE = "NEED_REBALANCE";

    /** CFG-07 配置键（有效性三项定性观察点）。 */
    public static final String CFG_OBSERVERS = "accounting.effectiveness.observers";
    /** CFG-07 缺省值（data-init.sql:318 同值）。 */
    public static final String CFG_OBSERVERS_DEFAULT = "PRODUCT_LINKAGE,DIRECTION,TERM±3M";
    /** CFG-07b 配置键（量化监测阈值，仅监测不判定）。 */
    public static final String CFG_MONITOR_THRESHOLD = "accounting.effectiveness.monitor_threshold";
    /** CFG-07b 缺省值（0.50 = 偏离 1 超 ±50%，data-init.sql:322 同值）。 */
    public static final String CFG_MONITOR_THRESHOLD_DEFAULT = "0.50";

    /** CFG-08 敞口侧触发阈值键（实货敞口变动 > 阈值即触发；data-init.sql:326 已播种 0.20）。 */
    public static final String CFG_EXPOSURE_DELTA = "accounting.rebalance.exposure_delta_pct";
    /** CFG-08 敞口侧缺省值（ARCHITECTURE §11.1：0.20，取值域 (0,1]）。 */
    public static final String CFG_EXPOSURE_DELTA_DEFAULT = "0.20";
    /** CFG-08 工具侧触发阈值键（套期比率偏离 > 阈值即触发；data-init.sql:330 已播种 0.20）。 */
    public static final String CFG_RATIO_DEVIATION = "accounting.rebalance.ratio_deviation_pct";
    /** CFG-08 工具侧缺省值（ARCHITECTURE §11.1：0.20，取值域 (0,1]）。 */
    public static final String CFG_RATIO_DEVIATION_DEFAULT = "0.20";

    /** 期限对应性容忍窗口（敞口预计实现日 ±3 个月 ⇒ 偏离绝对值 ≤ 90 天；TB-ACCT-2 备注）。 */
    public static final int TERM_TOLERANCE_DAYS = 90;

    /** 编号唯一冲突重试上限（DATA_MODEL.md §3.5；FundService 同值）。 */
    private static final int RETRY_MAX_ATTEMPTS = 3;

    private final TxTemplate tx;
    private final SubAccountVisibility visibility;
    private final ConfigReader config;
    private final HedgePlanDao hedgePlanDao;
    private final HedgeDocDao hedgeDocDao;
    private final HedgeRelationDao hedgeRelationDao;
    private final EffectivenessAssessDao assessDao;
    private final RebalanceRecordDao rebalanceDao;
    private final ExposureDao exposureDao;

    /**
     * 构造（T-077 七参形态保留；再平衡依赖经 {@link #withRebalanceSupport} 注入——
     * 既有调用点零改动，新写路径装配齐后才有再平衡能力）。
     *
     * @param tx               事务模板
     * @param visibility       子账户可见性（读 / 写断言）
     * @param config           配置读取接缝（CFG-07 / CFG-07b / CFG-08；缺种子容错缺省）
     * @param hedgePlanDao     套保计划 DAO（读取计划归属）
     * @param hedgeDocDao      书面文件 DAO（三要素门禁取数；PLAN 所有、ACCT 只读）
     * @param hedgeRelationDao 套期关系 DAO
     * @param assessDao        有效性评估 DAO
     */
    public AcctService(TxTemplate tx, SubAccountVisibility visibility, ConfigReader config,
                       HedgePlanDao hedgePlanDao, HedgeDocDao hedgeDocDao,
                       HedgeRelationDao hedgeRelationDao, EffectivenessAssessDao assessDao) {
        this(tx, visibility, config, hedgePlanDao, hedgeDocDao, hedgeRelationDao, assessDao,
                null, null);
    }

    /**
     * 全参构造（T-078 再平衡写路径；再平衡依赖齐备才启用 {@link #rebalance}）。
     *
     * @param rebalanceDao 再平衡记录 DAO（{@code t_rebalance_record} 写入；T-076 交付面承接）
     * @param exposureDao  敞口 DAO（触发判定取数：敞口现值 / 敞口消失）
     */
    public AcctService(TxTemplate tx, SubAccountVisibility visibility, ConfigReader config,
                       HedgePlanDao hedgePlanDao, HedgeDocDao hedgeDocDao,
                       HedgeRelationDao hedgeRelationDao, EffectivenessAssessDao assessDao,
                       RebalanceRecordDao rebalanceDao, ExposureDao exposureDao) {
        this.tx = tx;
        this.visibility = visibility;
        this.config = config;
        this.hedgePlanDao = hedgePlanDao;
        this.hedgeDocDao = hedgeDocDao;
        this.hedgeRelationDao = hedgeRelationDao;
        this.assessDao = assessDao;
        this.rebalanceDao = rebalanceDao;
        this.exposureDao = exposureDao;
    }

    /**
     * 再平衡依赖注解视图（回执披露用）：再平衡写路径是否已装配齐（九参构造）。
     *
     * @return {@code true} = 两 DAO 均已注入
     */
    public boolean rebalanceSupported() {
        return rebalanceDao != null && exposureDao != null;
    }

    /** 配置读取接缝（避免直接依赖 CONFIG 模块；FundService.ConfigReader 同形）。 */
    @FunctionalInterface
    public interface ConfigReader {
        /**
         * @param key 配置键
         * @return 配置值；不存在返回 {@code null}
         */
        String get(String key);
    }

    // ================================================================ 一、套期关系生成（FR-ACCT-1 / R-ACCT-1）

    /**
     * 生成套期关系（正式指定；{@code FR-ACCT-1}、{@code R-ACCT-1} 三要素门禁）。
     *
     * <p><b>三要素门禁</b>：绑定计划的<b>当前版本书面文件</b>（{@code t_hedge_doc.is_current='Y'}）
     * 须存在且三要素列（正式指定 / 有效性评估方法 / 再平衡机制）全部非空；缺任一要素 ⇒
     * {@code 10012} 阻断并列出缺失项（FR-ACCT-1 验收 3 同形态：缺再平衡机制配置时生成失败并列该项）。
     * 文档本体由 PLAN 模块生成，本方法<b>只读引用</b>（TB-PLAN-4 所有者声明）；
     * 生成动作 = 写 {@code t_hedge_relation} 正式指定行（{@code DESIGNATED}）。
     *
     * <p>文档 {@code pending_external_review='Y'} 恒成立（R-ACCT-10「待外部复核」标注，
     * 数据库 CHECK 承载不可移除）；本方法返回的套期关系经 {@code hedge_doc_id} 关联该标注，
     * 前端/导出侧据 {@code t_hedge_doc.pending_external_review} 呈现「待外部复核」。
     *
     * @param planId         套保计划（须存在）
     * @param exposureId     被套期项目（实货敞口标识）
     * @param instrumentType 套期工具类型（{@code FUTURES} / {@code OPTION}；期权与期货同等对待）
     * @param instrumentDesc 套期工具描述（合约与持仓标识）
     * @param operatorId     生成人
     * @return 生成的套期关系
     */
    public HedgeRelation generateHedgeRelation(long planId, long exposureId, String instrumentType,
                                               String instrumentDesc, long operatorId) {
        HedgePlan plan = hedgePlanDao.findById(planId);
        if (plan == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "套保计划不存在：id=" + planId);
        }
        visibility.assertSubAccountWritable(operatorId, requireSubOfPlan(plan));
        String type = requireEnum(instrumentType, "instrumentType", "FUTURES", "OPTION");
        if (isBlank(instrumentDesc)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期工具描述必填（合约与持仓标识）：planId=" + planId
                            + "；复现=补齐 hedging_instrument_desc");
        }

        // 三要素门禁（R-ACCT-1）：读计划当前版本书面文件，逐要素判空并列缺失项。
        HedgeDoc doc = hedgeDocDao.findCurrent(planId);
        List<String> missing = new ArrayList<>();
        if (doc == null) {
            missing.add("套期关系书面文件（计划未生成任何版本，先在 PLAN 模块生成）");
        } else {
            if (isBlank(doc.elemFormalDesignation)) {
                missing.add("要素①正式指定");
            }
            if (isBlank(doc.elemEffectivenessMethod)) {
                missing.add("要素②有效性评估方法");
            }
            if (isBlank(doc.elemRebalanceMechanism)) {
                missing.add("要素③再平衡机制");
            }
        }
        if (!missing.isEmpty()) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "三要素不齐备，不得生成套期关系（CAS 24 法定留痕，R-ACCT-1）：planId=" + planId,
                    "R-ACCT-1",
                    "缺失 " + missing.size() + " 项",
                    "三要素齐备（正式指定 + 有效性评估方法 + 再平衡机制）",
                    "缺失：" + String.join("、", missing)
                            + "；先在 PLAN 模块生成/补齐当前版本书面文件"
                            + "（FR-ACCT-1 验收 3：缺再平衡机制配置时生成失败并列该项）");
        }

        // 编号分配 + 并发兜底（DATA_MODEL.md §3.5）：UNIQUE 冲突 ⇒ 重新取号重试整笔（最多 3 次）。
        // 候选号在 attempt 内生成并写入 holder，冲突时供「占用判定」（仅编号冲突才重试，
        // 其他唯一约束冲突不误重试——FundService.retryApplyNo 同构）。
        String[] candidateNo = new String[1];
        return retryOnRelationNoConflict(() -> {
            HedgeRelation r = new HedgeRelation();
            r.subAccountId = plan.subAccountId;
            candidateNo[0] = nextRelationNo();
            r.relationNo = candidateNo[0];
            r.hedgePlanId = planId;
            r.hedgedItemExposureId = exposureId;
            r.hedgingInstrumentType = type;
            r.hedgingInstrumentDesc = instrumentDesc.trim();
            r.hedgeDocId = doc.id;
            r.status = REL_DESIGNATED;
            r.designatedTime = Clocks.now();
            if (hedgeRelationDao.insert(r, operatorId) != 1) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "套期关系写入失败：relationNo=" + r.relationNo);
            }
            return r;
        }, () -> candidateNo[0], no -> hedgeRelationDao.findByRelationNo(no) != null);
    }

    /**
     * 套期关系下一编号 = <b>全系统前缀 {@code HR} 内字典序最大 + 1</b>
     * （O-4 教训：{@code relation_no} 全系统唯一、无业务日重置前提，勿照搬「当日最大 +1」；
     * T-137/T-141 的形态是「当日最大 +1」且配合日期前缀天然隔离，此处前缀不含日期 ⇒ 取全系统 max）。
     *
     * <p>每次<b>重试</b>重新取号（放在 attempt 内而非闭包外缓存），使冲突后的下一轮
     * 取到并发方已提交的最大号。
     */
    private String nextRelationNo() {
        String max = hedgeRelationDao.findMaxRelationNoByPrefix(RELATION_NO_PREFIX);
        long next = 1L;
        if (max != null && max.startsWith(RELATION_NO_PREFIX)
                && max.length() > RELATION_NO_PREFIX.length()) {
            String seqText = max.substring(RELATION_NO_PREFIX.length());
            next = Long.parseLong(seqText) + 1L;
        }
        StringBuilder sb = new StringBuilder(RELATION_NO_PREFIX);
        String seq = Long.toString(next);
        for (int i = seq.length(); i < RELATION_NO_SEQ_WIDTH; i++) {
            sb.append('0');
        }
        return sb.append(seq).toString();
    }

    // ================================================================ 二、有效性评估（FR-ACCT-2 / FR-ACCT-4 / R-ACCT-2）

    /**
     * 提交有效性评估（{@code FR-ACCT-2}/{@code FR-ACCT-4}；定性三项观察点）。
     *
     * <p><b>判定规则（R-ACCT-2，Q-7）</b>：三项观察点全部 'Y' ⇒ {@code EFFECTIVE}；
     * 任一 'N' ⇒ {@code INEFFECTIVE}（并标注"需终止或再平衡"，不得自动修改会计处理）。
     * <b>量化监测不参与判定</b>：{@code monitorDeviationRatio} 仅落库，超阈值
     * （CFG-07b，缺省 0.50）置 {@code monitor_review_flag='Y'}（提示复核），结论不因此改变
     * （FR-ACCT-4 验收 5）。
     *
     * <p><b>缺失拒绝</b>（FR-ACCT-4 验收 4）：观察点①②取值缺失、观察点③判定基准
     * （敞口预计实现日）缺失、近相关（①='N'）未附书面经济关系说明 ⇒ 拒绝并列出缺失项。
     *
     * <p><b>幂等</b>：同套期关系同期间已有未更正评估 ⇒ 拒绝重复评估（10010；
     * {@code uk_eff_assess_rel_period} 数据库兜底）。评估结论落库后同步套期关系状态机
     * （有效 → {@code EFFECTIVE}；无效 → {@code NEED_REBALANCE}，再平衡执行归 T-078）。
     *
     * @param req        评估请求
     * @param operatorId 评估人
     * @return 落库的评估记录
     */
    public EffectivenessAssess assessEffectiveness(AssessRequest req, long operatorId) {
        // TE-T077-001：insert + 状态机 update 两段写整笔事务包裹（状态机失败须回滚插入，
        // 不得留失真留痕；FundService 同族形态）
        return tx.inTransaction(conn -> doAssess(req, operatorId, null));
    }

    /**
     * 更正评估记录（{@code FR-ACCT-2} 验收 3：不存在编辑入口，更正 = 新增记录并留痕）。
     *
     * <p>形态：以 {@code correctedFromId} 引用原记录<b>新增</b>一条（走 {@link #assessEffectiveness}
     * 同一判定管线），并把原记录 {@code is_corrected} 置 'Y'（使
     * {@code findActiveByRelationAndPeriod} 指向新记录）。
     *
     * <p><b>专用通道披露（诚实）</b>：置 {@code is_corrected='Y'} 须一次 UPDATE——
     * {@code DmEffectivenessAssessDao.updateById} 对<b>整行更新</b>显式拒绝（T-076 交付，
     * 防「借编辑入口静默改写判定结果」）；本方法以 DAO <b>专用标记方法</b>
     * {@link EffectivenessAssessDao#markCorrected(long, long)} 承载（仅 SET {@code is_corrected}，
     * 其余列不可触碰）——评估的业务字段仍不可改，「不可编辑」红线指<b>业务字段</b>而非行级
     * 标记位。该标记方法系本单新增（T-076 交付面之上的最小增量）。
     *
     * <p><b>时序</b>：先标记原记录、再插入新记录——{@code uk_eff_assess_rel_period} 的第三键为
     * {@code is_corrected}，不先释放唯一槽位新记录必撞约束（同期间同关系只允许一条未更正记录）。
     * 两段写（标记 + 新增）整笔 {@code tx.inTransaction} 包裹（TE-T077-001：校验拒绝须整笔回滚，
     * 不得留 {@code is_corrected='Y'} 的失真留痕；FundService 同族事务形态）。
     *
     * @param originalId 被更正的原记录
     * @param req        更正后的评估请求（重新走完整判定）
     * @param operatorId 更正人
     * @return 新评估记录（{@code correctedFromId} = 原记录）
     */
    public EffectivenessAssess correctAssessment(long originalId, AssessRequest req, long operatorId) {
        // TE-T077-001：markCorrected + doAssess 新记录 = 整笔事务（复核探针实证：更正请求
        // 校验拒绝后原记录 is_corrected 已='Y' 且无更正记录 ⇒ 留痕链失真）；拒绝路径
        // （记录不存在 / 已更正 / 可见性 / 新请求校验）在事务开启前后均整笔回滚。
        return tx.inTransaction(conn -> {
            EffectivenessAssess original = assessDao.findById(originalId);
            if (original == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "被更正的评估记录不存在：id=" + originalId);
            }
            if ("Y".equals(original.isCorrected)) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "该评估记录已被更正（不得二次更正同一记录）：id=" + originalId
                                + "；复现=以最新记录为更正来源");
            }
            visibility.assertSubAccountWritable(operatorId, requireSubOfAssess(original));
            if (assessDao.markCorrected(originalId, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "原记录标记已更正失败（并发冲突）：id=" + originalId + "；复现=重新读取后再更正");
            }
            return doAssess(req, operatorId, originalId);
        });
    }

    /** 评估落库主体（{@code correctedFromId} 非空 = 更正链新增）。 */
    private EffectivenessAssess doAssess(AssessRequest req, long operatorId, Long correctedFromId) {
        if (req == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "评估请求不得为空");
        }
        if (req.hedgeRelationId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "hedgeRelationId 必填");
        }
        if (isBlank(req.periodKey)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "periodKey 必填（会计期间，如 2026-09）");
        }
        HedgeRelation relation = hedgeRelationDao.findById(req.hedgeRelationId);
        if (relation == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期关系不存在：id=" + req.hedgeRelationId);
        }
        visibility.assertSubAccountWritable(operatorId, requireSubOfRelation(relation));
        if (REL_TERMINATED.equals(relation.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "套期关系已终止，不得再评估（不得再按套期会计处理）：relationNo="
                            + relation.relationNo);
        }
        if (correctedFromId == null
                && assessDao.findActiveByRelationAndPeriod(req.hedgeRelationId, req.periodKey.trim()) != null) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "该套期关系该期间已有未更正评估记录（uk_eff_assess_rel_period）：relationId="
                            + req.hedgeRelationId + " periodKey=" + req.periodKey.trim()
                            + "；复现=走更正（correctAssessment）而非重复评估");
        }

        // 观察点缺失拒绝（FR-ACCT-4 验收 4）：并列缺失项，不得静默按不满足处理。
        List<String> missing = new ArrayList<>();
        String linkage = requireFlag(req.observerLinkage, "observerLinkage（品种相关性）", missing);
        String direction = requireFlag(req.observerDirection, "observerDirection（方向一致性）", missing);
        String term = requireFlag(req.observerTerm, "observerTerm（期限对应性）", missing);
        if (req.exposureExpectedDate == null) {
            missing.add("exposureExpectedDate（敞口预计实现日：期限对应性判定基准）");
        }
        if (!missing.isEmpty()) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "观察点数据缺失，评估被拒（FR-ACCT-4 验收 4）：relationId=" + req.hedgeRelationId
                            + "；缺失 " + missing.size() + " 项：" + String.join("、", missing)
                            + "；复现=补齐观察点判定数据后重新提交");
        }
        if ("N".equals(linkage) && isBlank(req.linkageNote)) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "观察点①取 'N'（近相关）须附书面经济关系说明（Q-7）：relationId="
                            + req.hedgeRelationId + "；缺失：linkageNote"
                            + "；复现=补齐 linkageNote 后重新提交");
        }

        // 期限偏离天数留痕（结论可复现，FR-ACCT-4 验收 3）；观察点③='Y' 须与判定数据一致
        // （偏离 ≤ 90 天 = ±3 个月）；矛盾属数据错误而非判定失败，拒绝提交。
        int termDeviationDays = (int) Math.abs(ChronoUnit.DAYS.between(Clocks.today(),
                req.exposureExpectedDate));
        if ("Y".equals(term) && termDeviationDays > TERM_TOLERANCE_DAYS) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "观察点③取 'Y' 与判定数据矛盾：期限偏离 " + termDeviationDays + " 天 > 容忍 "
                            + TERM_TOLERANCE_DAYS + " 天（±3 个月）；relationId=" + req.hedgeRelationId
                            + "；复现=按实际偏离把观察点③改 'N'，或修正 exposureExpectedDate");
        }

        // 三项均满足即有效（R-ACCT-2）；量化监测仅落库提示、不参与本判定（Q-7 / FR-ACCT-4 验收 5）。
        boolean allSatisfied = "Y".equals(linkage) && "Y".equals(direction) && "Y".equals(term);
        String conclusion = allSatisfied ? CONCLUSION_EFFECTIVE : CONCLUSION_INEFFECTIVE;

        // 量化监测（CFG-07b）：偏离 1 的绝对值超阈值 ⇒ 提示复核（仅监测与再平衡触发）。
        BigDecimal threshold = monitorThreshold();
        BigDecimal deviation = req.monitorDeviationRatio;
        boolean flagged = deviation != null && deviation.abs().compareTo(threshold) > 0;

        EffectivenessAssess ea = new EffectivenessAssess();
        ea.subAccountId = relation.subAccountId;
        ea.hedgeRelationId = req.hedgeRelationId;
        ea.periodKey = req.periodKey.trim();
        ea.assessMethod = METHOD_QUALITATIVE;
        ea.observerLinkage = linkage;
        ea.linkageNote = "N".equals(linkage) ? req.linkageNote.trim() : null;
        ea.observerDirection = direction;
        ea.observerTerm = term;
        ea.exposureExpectedDate = req.exposureExpectedDate;
        ea.termDeviationDays = termDeviationDays;
        ea.monitorDeviationRatio = deviation;
        ea.monitorReviewFlag = flagged ? "Y" : "N";
        ea.observerDetail = buildObserverDetail(relation, linkage, direction, term,
                req.exposureExpectedDate, termDeviationDays, conclusion, deviation, threshold, flagged);
        ea.conclusion = conclusion;
        ea.needActionFlag = allSatisfied ? "N" : "Y";
        ea.actionNote = allSatisfied ? null
                : "需终止或再平衡（结论无效；不得自动修改会计处理，FR-ACCT-2 验收 2）";
        ea.assessorId = operatorId;
        ea.assessTime = Clocks.now();
        ea.correctedFromId = correctedFromId;
        ea.isCorrected = "N";
        if (assessDao.insert(ea, operatorId) != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "评估记录写入失败：relationId=" + req.hedgeRelationId);
        }

        // 套期关系状态机（ACCT.md §2.1）：有效 → EFFECTIVE；无效 → NEED_REBALANCE。
        String nextStatus = allSatisfied ? REL_EFFECTIVE : REL_NEED_REBALANCE;
        if (!nextStatus.equals(relation.status)) {
            relation.status = nextStatus;
            if (hedgeRelationDao.updateById(relation, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "套期关系并发冲突：relationNo=" + relation.relationNo
                                + "；复现=重新读取后再提交");
            }
        }
        if (flagged) {
            LOG.warning("有效性量化监测超阈值（仅提示复核，不影响结论，Q-7）：relationNo="
                    + relation.relationNo + " periodKey=" + ea.periodKey
                    + " 偏离=" + deviation.toPlainString() + " 阈值=" + threshold.toPlainString()
                    + "（CFG-07b）");
        }
        return ea;
    }

    // ================================================================ 四、读取面（列表 / 未评估提醒）

    /**
     * 分页查询套期关系（{@code GET /acct/hedge-relation}）。
     *
     * <p>子账户可见集过滤（NFR-SEC-7；{@code subAccountId} 指定时先做读面断言）。
     *
     * @param query        分页
     * @param subAccountId 子账户（可空）
     * @param status       状态筛选（可空；取值域 = 套期关系 5 态）
     * @param operatorId   调用者
     * @return 分页结果
     */
    public PageResult<HedgeRelation> listHedgeRelations(PageQuery query, Integer subAccountId,
                                                        String status, long operatorId) {
        if (status != null) {
            requireRelStatus(status);
        }
        List<String> conds = new ArrayList<>();
        List<Object> args = new ArrayList<>();
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
        return hedgeRelationDao.findPage(query,
                conds.isEmpty() ? null : String.join(" AND ", conds), args);
    }

    /**
     * 分页查询有效性评估记录（{@code GET /acct/effectiveness}）。
     *
     * @param query        分页
     * @param subAccountId 子账户（可空）
     * @param periodKey    会计期间（可空）
     * @param conclusion   结论筛选（可空；EFFECTIVE / INEFFECTIVE / NEED_REBALANCE）
     * @param monitorOnly  {@code true}=仅监测超阈值记录（提示复核清单）
     * @param operatorId   调用者
     * @return 分页结果
     */
    public PageResult<EffectivenessAssess> listAssessments(PageQuery query, Integer subAccountId,
                                                           String periodKey, String conclusion,
                                                           boolean monitorOnly, long operatorId) {
        if (conclusion != null) {
            requireConclusion(conclusion);
        }
        List<String> conds = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, subAccountId);
            conds.add("sub_account_id = ?");
            args.add(subAccountId);
        }
        if (periodKey != null) {
            conds.add("period_key = ?");
            args.add(periodKey);
        }
        if (conclusion != null) {
            conds.add("conclusion = ?");
            args.add(conclusion);
        }
        if (monitorOnly) {
            conds.add("monitor_review_flag = ?");
            args.add("Y");
        }
        applyScope(conds::add, args::add, operatorId);
        return assessDao.findPage(query,
                conds.isEmpty() ? null : String.join(" AND ", conds), args);
    }

    /**
     * 某期间未评估的套期关系清单（{@code FR-ACCT-2} 验收 4「系统提醒」的取数入口）。
     *
     * <p>口径：调用者可见范围内、状态非「已终止」、且在该期间<b>无未更正评估记录</b>的套期关系。
     * 提醒形态 = 本清单（handler 侧经信封返回，供页面呈现「本期未评估」提醒）。
     *
     * @param periodKey  会计期间（如 2026-09）
     * @param operatorId 调用者
     * @return 未评估的套期关系列表（{@code designated_time} 升序）
     */
    public List<HedgeRelation> unassessedRelations(String periodKey, long operatorId) {
        if (isBlank(periodKey)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "periodKey 必填（会计期间，如 2026-09）");
        }
        List<Integer> visible = visibility.visibleSubAccountIds(operatorId);
        // 哨兵语义与 applyScope 同源：null/空集按空集处理（fail-closed）；
        // 含哨兵 0 = 跨账户只读 ⇒ 查全部（传 null）。
        List<Integer> subs = (visible == null || visible.isEmpty())
                ? List.of(-1)
                : (visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL) ? null : visible);
        List<HedgeRelation> out = new ArrayList<>();
        for (HedgeRelation r : hedgeRelationDao.findBySubAccounts(subs)) {
            if (!REL_TERMINATED.equals(r.status)
                    && assessDao.findActiveByRelationAndPeriod(r.id, periodKey.trim()) == null) {
                out.add(r);
            }
        }
        return out;
    }

    // ================================================================ 三、再平衡（FR-ACCT-3 / R-ACCT-3 / Q-8 四类触发）

    /**
     * 执行再平衡（{@code FR-ACCT-3}；状态机「需再平衡 ──再平衡完成──→ 已重新指定」，
     * {@code ACCT.md} §2.1）。
     *
     * <p><b>形态判断（手动发起）</b>：FR-ACCT-3 输入列 =「套期关系标识、触发原因、
     * 调整前后套保比率与数量、<b>执行人</b>」——执行人语义只在手动发起形态存在（自动侦测无
     * 「执行人」输入位）；故四类触发场景 = <b>操作人选定场景提交，系统按数据面复核偏离值</b>：
     * ① 敞口变化与 ② 比率偏离由本方法按 {@code t_exposure} 现值 / 计划当前比率<b>实时计算</b>，
     * 不采信客户端声明的偏离值（防伪造触发）；③ 计划修订/终止与 ④ 敞口消失由数据面状态复核
     * （计划 {@code TERMINATED} / 敞口行消失），不采信客户端声明。任一场景复核通过即触发
     * （Q-8「满足任一即触发评估」）。<b>全部场景均不满足 ⇒ 拒绝生成并给出当前偏离值</b>
     * （FR-ACCT-3 验收 4；{@code 10012} 阻断四项载荷，{@code current}=当前偏离值、
     * {@code threshold}=CFG-08 阈值快照）。
     *
     * <p><b>触发条件两侧齐设（Q-8）</b>：敞口侧（CFG-08 exposure_delta_pct）与工具侧
     * （CFG-08 ratio_deviation_pct）两项条件在本方法<b>恒参与评估</b>（①② 场景须各自复核；
     * 两侧任一命中即触发），加上计划/敞口状态场景 ③④ —— 两侧各至少一项的配置约束由此落实，
     * 不依赖运行时配置增删。
     *
     * <p><b>再平衡须重新指定（R-ACCT-3，不得只改数字）</b>：成功路径 = 四段写——
     * ① 新版书面文件（复用 {@code generateHedgeRelation} 的取数形态读当前版本文档、
     * 以 v+1 版本再生成，三要素沿用当前版本文档内容并附加再平衡留痕，三要素门禁 R-ACCT-1
     * 在此同样成立：当前版本文档缺任一要素 ⇒ {@code 10012} 阻断并列缺失项，重新指定失败；
     * 同段内同步回写 {@code plan.current_doc_version}——TE-T078-001：该列与文档版本为
     * 同一事务面状态对，漏回写致 PLAN 侧再生成版本推导撞 {@code uk_hedge_doc_no}）；
     * ② 新套期关系行（{@code REDESIGNATED} 状态，关联新文档；编号全系统前缀 {@code HR}
     * max+1，O-4 教训）；③ 再平衡记录（四要素：触发类型/侧/原因/量化判定值 + 前后比率与
     * 数量 + 执行人与时间 + 新文档/新关系引用）。旧关系状态置 {@code REDESIGNATED}
     * （原行保留，历史可查——评估记录/凭证经 {@code hedge_relation_id} 追溯）。
     * <b>整笔 {@code tx.inTransaction} 包裹</b>（T-077 同族纪律：任一段失败整笔回滚，
     * 不得留半笔再平衡留痕）。
     *
     * <p><b>调整后取值</b>：{@code ratioAfter/qtyAfter} 为执行人申报的调整目标
     * （比率 ∈ (0,1]，数量 &gt; 0——ck_hedge_plan_ratio 同域）；敞口消失场景
     * （TRIGGER_EXPOSURE_GONE）语义为「终止重指定」，调整后数量 = 0（无敞口可套保）。
     *
     * <p><b>PLAN 反馈</b>：再平衡结论落 {@code plan_feedback_flag}（执行人声明是否需调整计划，
     * R-ACCT-3「结论须反馈至 PLAN」的最小承载；计划本体的修订走 PLAN 模块既有流程，
     * 不在本单写域）。
     *
     * @param req        再平衡请求（触发场景 + 调整前后值 + PLAN 反馈声明）
     * @param operatorId 执行人（会话身份，不接受请求体传入）
     * @return 落库的再平衡记录（含新文档 / 新关系引用）
     */
    public RebalanceRecord rebalance(RebalanceRequest req, long operatorId) {
        if (req == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "再平衡请求不得为空");
        }
        if (!rebalanceSupported()) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "再平衡依赖未装配（RebalanceRecordDao/ExposureDao 缺失）；"
                            + "复现=以全参构造装配 AcctService 后重试");
        }
        return tx.inTransaction(conn -> doRebalance(req, operatorId));
    }

    private RebalanceRecord doRebalance(RebalanceRequest req, long operatorId) {
        if (req.hedgeRelationId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "hedgeRelationId 必填");
        }
        HedgeRelation relation = hedgeRelationDao.findById(req.hedgeRelationId);
        if (relation == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期关系不存在：id=" + req.hedgeRelationId);
        }
        visibility.assertSubAccountWritable(operatorId, requireSubOfRelation(relation));
        if (REL_TERMINATED.equals(relation.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "套期关系已终止，不得再平衡（终态；ACCT.md §2.1 状态机）：relationNo="
                            + relation.relationNo);
        }
        if (REL_REDESIGNATED.equals(relation.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "该套期关系已完成再平衡（已重新指定），请对新关系发起：relationNo="
                            + relation.relationNo + "；复现=经 t_rebalance_record."
                            + "new_hedge_relation_id 定位新关系行");
        }
        requireRelStatus(relation.status);   // 复用 5 态取值域校验（防脏状态静默通过）
        if (isBlank(req.triggerReason)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "triggerReason 必填（触发原因描述）：relationNo=" + relation.relationNo);
        }
        String scenario = req.triggerScenario == null ? "" : req.triggerScenario.trim();
        if (!TRIGGER_EXPOSURE_DELTA.equals(scenario) && !TRIGGER_RATIO_DEVIATION.equals(scenario)
                && !TRIGGER_PLAN_REVISED.equals(scenario) && !TRIGGER_EXPOSURE_GONE.equals(scenario)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "triggerScenario 取值非法（四类触发，Q-8）：" + scenario
                            + "；复现=使用 EXPOSURE_DELTA/RATIO_DEVIATION/PLAN_REVISED"
                            + "/EXPOSURE_GONE 之一");
        }
        BigDecimal exposureThreshold = exposureDeltaThreshold();
        BigDecimal ratioThreshold = ratioDeviationThreshold();

        // 数据面复核（不采信客户端偏离值）：四类场景逐一求值，收集各自判定证据。
        Evaluation eval = evaluateTriggers(relation, scenario, exposureThreshold, ratioThreshold);

        // 触发条件不满足 ⇒ 拒绝生成并给出当前偏离值（FR-ACCT-3 验收 4；10012 四项载荷）
        if (!eval.triggered) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "再平衡触发条件不满足，拒绝生成记录（FR-ACCT-3 验收 4）：relationNo="
                            + relation.relationNo + " 场景=" + scenario,
                    "R-ACCT-3",
                    eval.currentValue,
                    eval.thresholdValue,
                    "当前偏离值未达阈值（敞口变化 " + eval.exposureDelta.toPlainString()
                            + " / 阈值 " + exposureThreshold.toPlainString()
                            + "；比率偏离 " + eval.ratioDeviation.toPlainString()
                            + " / 阈值 " + ratioThreshold.toPlainString()
                            + "；计划状态=" + eval.planStatus + "；敞口存在=" + eval.exposureExists
                            + "）；复现=确认偏离数据后重新发起，或走有效性评估（结论无效 ⇒ 需再平衡）");
        }

        // 调整后取值校验（申报目标；敞口消失=终止重指定，数量恒 0）
        BigDecimal qtyAfter;
        if (TRIGGER_EXPOSURE_GONE.equals(scenario)) {
            if (req.qtyAfter != null && req.qtyAfter.compareTo(BigDecimal.ZERO) != 0) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "敞口消失场景（终止重指定）调整后数量须为 0（无敞口可套保）：qtyAfter="
                                + req.qtyAfter.toPlainString());
            }
            qtyAfter = BigDecimal.ZERO;
        } else {
            if (req.qtyAfter == null || req.qtyAfter.compareTo(BigDecimal.ZERO) <= 0) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "qtyAfter 须 > 0（调整后数量；敞口消失场景除外）：relationNo="
                                + relation.relationNo);
            }
            qtyAfter = req.qtyAfter;
        }
        if (req.ratioAfter == null
                || req.ratioAfter.compareTo(BigDecimal.ZERO) <= 0
                || req.ratioAfter.compareTo(BigDecimal.ONE) > 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "ratioAfter 须 ∈ (0,1]（ck_hedge_plan_ratio 同域）：relationNo="
                            + relation.relationNo + " 值="
                            + (req.ratioAfter == null ? "null" : req.ratioAfter.toPlainString()));
        }
        BigDecimal ratioBefore = ratioOf(relation);

        // ① 新版书面文件（R-ACCT-3：不得只改数字）：v+1 版本再生成，三要素沿用当前版本
        // 并附加再平衡留痕；当前版本文档缺任一要素 ⇒ 10012 阻断（R-ACCT-1 门禁在重新指定同样成立）。
        HedgeDoc newDoc = regenerateHedgeDoc(relation, req, operatorId);

        // ② 新套期关系行（REDESIGNATED；编号 O-4 全系统前缀 max+1 + 唯一约束重试）
        HedgeRelation newRelation = insertRedesignatedRelation(relation, newDoc, req,
                ratioAfter(req), qtyAfter, operatorId);

        // ③ 再平衡记录（不得事后修改，R-ACCT-3：DAO updateById 显式拒绝）
        RebalanceRecord record = new RebalanceRecord();
        record.subAccountId = relation.subAccountId;
        record.hedgeRelationId = relation.id;
        record.rebalanceNo = nextRebalanceNo();
        record.triggerType = scenario;
        record.triggerSide = eval.triggerSide;
        record.triggerReason = req.triggerReason.trim();
        record.triggerExposureDelta = scale9(eval.exposureDelta);
        record.triggerRatioDeviation = scale9(eval.ratioDeviation);
        record.triggerThreshold = scale9(TRIGGER_EXPOSURE_DELTA.equals(scenario)
                || TRIGGER_PLAN_REVISED.equals(scenario) || TRIGGER_EXPOSURE_GONE.equals(scenario)
                ? exposureThreshold : ratioThreshold);
        record.ratioBefore = scale9(ratioBefore);
        record.ratioAfter = scale9(req.ratioAfter);
        record.qtyBefore = scale4(hedgeQtyOf(relation));
        record.qtyAfter = scale4(qtyAfter);
        record.newHedgeDocId = newDoc.id;
        record.newHedgeRelationId = newRelation.id;
        record.executorId = operatorId;
        record.rebalanceTime = Clocks.now();
        record.planFeedbackFlag = "Y".equalsIgnoreCase(req.planFeedbackRequired) ? "Y" : "N";
        if (rebalanceDao.insert(record, operatorId) != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "再平衡记录写入失败：relationNo=" + relation.relationNo);
        }

        // ④ 旧关系状态机：需再平衡 ──再平衡完成──→ 已重新指定（原行保留，历史可查）
        relation.status = REL_REDESIGNATED;
        if (hedgeRelationDao.updateById(relation, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "套期关系并发冲突：relationNo=" + relation.relationNo
                            + "；复现=重新读取后再提交");
        }
        LOG.warning("再平衡完成（R-ACCT-3 重新指定留痕）：rebalanceNo=" + record.rebalanceNo
                + " relationNo=" + relation.relationNo + " → " + newRelation.relationNo
                + " trigger=" + record.triggerType + "/" + record.triggerSide
                + " planFeedback=" + record.planFeedbackFlag);
        return record;
    }

    /** 四类触发场景的数据面复核（不采信客户端偏离值；证据落 eval）。 */
    private Evaluation evaluateTriggers(HedgeRelation relation, String scenario,
                                        BigDecimal exposureThreshold, BigDecimal ratioThreshold) {
        Evaluation eval = new Evaluation();
        HedgePlan plan = hedgePlanDao.findById(relation.hedgePlanId);
        eval.planStatus = plan == null ? "null" : String.valueOf(plan.status);
        Exposure exposure = exposureDao.findById(relation.hedgedItemExposureId);
        eval.exposureExists = exposure != null;

        // ① 敞口变化（敞口侧）：敞口现值 vs 计划立项时的套保基数——基数取计划数量
        // planQty（与敞口同口径，R-PLAN-3/⑤ 立项时按敞口未覆盖量校准；敞口快照历史量
        // 不落 t_hedge_relation，计划基数是关系行可稳定引用的最小可比基准——回执披露该口径）。
        // 变化幅度 = |现值 − 基数| / 基数（基数为 0 ⇒ 视为敞口消失场景 ④）。
        BigDecimal baseQty = plan == null ? BigDecimal.ZERO : nz(plan.planQty);
        BigDecimal exposureQty = exposure == null ? BigDecimal.ZERO : nz(exposure.exposureQty);
        if (baseQty.signum() > 0) {
            eval.exposureDelta = exposureQty.subtract(baseQty).abs()
                    .divide(baseQty, 6, java.math.RoundingMode.HALF_UP);
        } else {
            eval.exposureDelta = exposureQty.signum() == 0
                    ? BigDecimal.ZERO : BigDecimal.ONE;
        }

        // ② 套期比率偏离（工具侧）：实际覆盖比率 = 计划套保数量 / 敞口现值，对计划申报
        // 比率求偏离。敞口现值为 0 ⇒ 偏离取 1（完全偏离，实质即敞口消失）。
        BigDecimal hedgeQty = plan == null ? BigDecimal.ZERO : nz(plan.planQty);
        if (exposureQty.signum() > 0) {
            BigDecimal actualRatio = hedgeQty.divide(exposureQty, 6, java.math.RoundingMode.HALF_UP);
            BigDecimal declaredRatio = ratioOf(relation);
            eval.ratioDeviation = actualRatio.subtract(declaredRatio).abs()
                    .divide(declaredRatio.max(new BigDecimal("0.000001")), 6,
                            java.math.RoundingMode.HALF_UP);
        } else {
            eval.ratioDeviation = BigDecimal.ONE;
        }

        // ③ 计划数量被修订 / 终止（敞口侧）：数据面复核 = 计划状态 TERMINATED，或已修订
        // （t_plan_state_log 留痕由 PLAN 模块承载；本单以「敞口现值 ≠ 计划基数」作为修订
        // 的间接复核——敞口变化场景 ① 的判定值即其证据，两侧共用数据面计算，不另设真源）。
        boolean planRevised = plan != null && "TERMINATED".equals(plan.status);

        // ④ 敞口消失（敞口侧）：绑定敞口行不存在，或现值为 0 且状态非 CLOSED 的已清空形态。
        boolean exposureGone = !eval.exposureExists
                || exposure.exposureQty.signum() == 0;

        // 场景逐一判定（Q-8：满足任一即触发）；命中场景的侧别 = 记录 trigger_side 的来源。
        boolean hit = false;
        switch (scenario) {
            case TRIGGER_EXPOSURE_DELTA:
                hit = eval.exposureDelta.compareTo(exposureThreshold) > 0;
                eval.triggerSide = SIDE_EXPOSURE;
                eval.currentValue = "exposureDelta=" + eval.exposureDelta.toPlainString();
                eval.thresholdValue = exposureThreshold.toPlainString();
                break;
            case TRIGGER_RATIO_DEVIATION:
                hit = eval.ratioDeviation.compareTo(ratioThreshold) > 0;
                eval.triggerSide = SIDE_INSTRUMENT;
                eval.currentValue = "ratioDeviation=" + eval.ratioDeviation.toPlainString();
                eval.thresholdValue = ratioThreshold.toPlainString();
                break;
            case TRIGGER_PLAN_REVISED:
                hit = planRevised;
                eval.triggerSide = SIDE_EXPOSURE;
                eval.currentValue = "planStatus=" + eval.planStatus;
                eval.thresholdValue = "TERMINATED";
                break;
            case TRIGGER_EXPOSURE_GONE:
                hit = exposureGone;
                eval.triggerSide = SIDE_EXPOSURE;
                eval.currentValue = "exposureExists=" + eval.exposureExists
                        + " exposureQty=" + exposureQty.toPlainString();
                eval.thresholdValue = "0";
                break;
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "triggerScenario 取值非法：" + scenario);
        }
        eval.triggered = hit;
        return eval;
    }

    /**
     * 新版书面文件再生成（R-ACCT-3「重新生成书面文件，不得只改数字」；PLAN 所有、ACCT 只读
     * 引用的所有权边界不变——此处是<b>再平衡触发的重新生成</b>，写 {@code t_hedge_doc} 新版本行，
     * 与 {@code PlanService.generateHedgeDoc} 的 PLAN 主动再生成同表同形态）。
     *
     * <p>三要素门禁（R-ACCT-1）在重新指定同样成立：当前版本文档缺任一要素（不存在 / 空串）
     * ⇒ {@code 10012} 阻断并列缺失项（重新指定失败，整笔回滚）——要素变更致缺项时不静默降级。
     *
     * @return 新版本文档行（{@code is_current='Y'}；旧版本置 N 并留失效时间，历史可查）
     */
    private HedgeDoc regenerateHedgeDoc(HedgeRelation relation, RebalanceRequest req,
                                        long operatorId) {
        HedgeDoc current = hedgeDocDao.findCurrent(relation.hedgePlanId);
        List<String> missing = new ArrayList<>();
        if (current == null) {
            missing.add("套期关系书面文件（计划未生成任何版本）");
        } else {
            if (isBlank(current.elemFormalDesignation)) {
                missing.add("要素①正式指定");
            }
            if (isBlank(current.elemEffectivenessMethod)) {
                missing.add("要素②有效性评估方法");
            }
            if (isBlank(current.elemRebalanceMechanism)) {
                missing.add("要素③再平衡机制");
            }
        }
        if (!missing.isEmpty()) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "三要素不齐备，不得重新指定（R-ACCT-3 重新生成书面文件前提）：relationNo="
                            + relation.relationNo,
                    "R-ACCT-1",
                    "缺失 " + missing.size() + " 项",
                    "三要素齐备（正式指定 + 有效性评估方法 + 再平衡机制）",
                    "缺失：" + String.join("、", missing)
                            + "；先在 PLAN 模块补齐当前版本书面文件后再执行再平衡"
                            + "（计划要素变更致缺项 ⇒ 重新指定被拒，不静默降级）");
        }
        int version = (current.docVersion == null ? 1 : current.docVersion) + 1;
        HedgePlan plan = hedgePlanDao.findById(relation.hedgePlanId);
        if (plan == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "绑定计划不存在：id=" + relation.hedgePlanId);
        }
        // 旧版本置 N（同事务；PlanService.generateHedgeDoc 同形态）
        current.isCurrent = "N";
        current.invalidatedTime = Clocks.now();
        if (hedgeDocDao.updateById(current, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "书面文件并发冲突：docNo=" + current.docNo + "；复现=重新读取后再提交");
        }
        HedgeDoc doc = new HedgeDoc();
        doc.subAccountId = relation.subAccountId;
        doc.hedgePlanId = relation.hedgePlanId;
        doc.docNo = BizNoGenerator.hedgeDoc(plan.planNo, version);
        doc.docVersion = version;
        doc.isCurrent = "Y";
        // 三要素沿用当前版本 + 再平衡留痕（重新生成 ≠ 空白重写；评估方法/机制为 CAS 24 法定内容）
        doc.elemFormalDesignation = current.elemFormalDesignation
                + "【再平衡重新指定 " + Clocks.now() + "】";
        doc.elemEffectivenessMethod = current.elemEffectivenessMethod;
        doc.elemRebalanceMechanism = current.elemRebalanceMechanism;
        doc.hedgedItemExposureId = relation.hedgedItemExposureId;
        doc.hedgingInstrument = current.hedgingInstrument;
        doc.pendingExternalReview = "Y";   // R-ACCT-10：恒 'Y'（DB CHECK 承载）
        doc.generatedBy = operatorId;
        doc.generatedTime = Clocks.now();
        if (hedgeDocDao.insert(doc, operatorId) != 1) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "书面文件编号冲突（uk_hedge_doc_no）：docNo=" + doc.docNo + "；复现=重试");
        }
        // TE-T078-001：current_doc_version 与文档版本为同一事务面状态对——本事务内同步回写
        // （同笔提交同笔回滚），否则 PLAN 侧 generateHedgeDoc 以 plan.currentDocVersion + 1
        // 推导下一版本将命中已占用版本号 ⇒ uk_hedge_doc_no 冲突，PLAN 侧再生成永久失败。
        if (hedgePlanDao.updateCurrentDocVersion(relation.hedgePlanId, version, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "计划当前文档版本回写失败（并发删除？）：planId=" + relation.hedgePlanId
                            + " version=" + version);
        }
        return doc;
    }

    /** 新套期关系行（REDESIGNATED；编号同 O-4 全系统 max+1 + 唯一约束 3 次重试）。 */
    private HedgeRelation insertRedesignatedRelation(HedgeRelation relation, HedgeDoc newDoc,
                                                     RebalanceRequest req, BigDecimal ratioAfter,
                                                     BigDecimal qtyAfter, long operatorId) {
        String[] candidateNo = new String[1];
        return retryOnRelationNoConflict(() -> {
            HedgeRelation r = new HedgeRelation();
            r.subAccountId = relation.subAccountId;
            candidateNo[0] = nextRelationNo();
            r.relationNo = candidateNo[0];
            r.hedgePlanId = relation.hedgePlanId;
            r.hedgedItemExposureId = relation.hedgedItemExposureId;
            r.hedgingInstrumentType = relation.hedgingInstrumentType;
            r.hedgingInstrumentDesc = relation.hedgingInstrumentDesc;
            r.hedgeDocId = newDoc.id;
            r.status = REL_REDESIGNATED;
            r.designatedTime = Clocks.now();
            if (hedgeRelationDao.insert(r, operatorId) != 1) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "重新指定套期关系写入失败：relationNo=" + r.relationNo);
            }
            return r;
        }, () -> candidateNo[0], no -> hedgeRelationDao.findByRelationNo(no) != null);
    }

    /**
     * 再平衡编号 = <b>全系统前缀 {@code RB} 内字典序最大 + 1</b>（{@code rebalance_no} 全系统唯一
     * {@code uk_rebalance_no}；{@code DATA_MODEL.md} §3.5 编号表<b>未列</b>该编号 ⇒ 前缀形态
     * 本服务定义并在回执披露——与 {@code HR} 同法：无日期段、无业务日重置前提，勿照搬
     * 「当日最大 +1」；T-137/T-141 判例前提不成立）。序号 4 位零填充。
     */
    private String nextRebalanceNo() {
        String max = rebalanceDao.findMaxRebalanceNoByPrefix(REBALANCE_NO_PREFIX);
        long next = 1L;
        if (max != null && max.startsWith(REBALANCE_NO_PREFIX)
                && max.length() > REBALANCE_NO_PREFIX.length()) {
            next = Long.parseLong(max.substring(REBALANCE_NO_PREFIX.length())) + 1L;
        }
        StringBuilder sb = new StringBuilder(REBALANCE_NO_PREFIX);
        String seq = Long.toString(next);
        for (int i = seq.length(); i < REBALANCE_NO_SEQ_WIDTH; i++) {
            sb.append('0');
        }
        return sb.append(seq).toString();
    }

    /** 调整前比率 = 关联计划的申报比率（比率留痕基准；计划缺失 ⇒ 参数错误）。 */
    private BigDecimal ratioOf(HedgeRelation relation) {
        HedgePlan plan = hedgePlanDao.findById(relation.hedgePlanId);
        if (plan == null || plan.hedgeRatio == null) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "绑定计划缺失或比率缺失，无法确定调整前比率：relationNo=" + relation.relationNo
                            + "；复现=核对 t_hedge_plan.hedge_ratio");
        }
        return plan.hedgeRatio;
    }

    /** 调整前数量 = 计划申报的套保数量（再平衡前工具侧数量基准；计划缺失 ⇒ 数据不足拒绝）。 */
    private BigDecimal hedgeQtyOf(HedgeRelation relation) {
        HedgePlan plan = hedgePlanDao.findById(relation.hedgePlanId);
        if (plan == null || plan.planQty == null) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "绑定计划缺失或数量缺失，无法确定调整前数量：relationNo=" + relation.relationNo
                            + "；复现=核对 t_hedge_plan.plan_qty");
        }
        return plan.planQty;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale9(BigDecimal v) {
        return v.setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal scale4(BigDecimal v) {
        return v.setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** ratioAfter 别名（可读性；与记录 ratioAfter 同源）。 */
    private static BigDecimal ratioAfter(RebalanceRequest req) {
        return req.ratioAfter;
    }

    /** CFG-08 敞口侧阈值（容错缺省 0.20；data-init.sql:326 已播种）。 */
    private BigDecimal exposureDeltaThreshold() {
        return decimalConfig(CFG_EXPOSURE_DELTA, CFG_EXPOSURE_DELTA_DEFAULT);
    }

    /** CFG-08 工具侧阈值（容错缺省 0.20；data-init.sql:330 已播种）。 */
    private BigDecimal ratioDeviationThreshold() {
        return decimalConfig(CFG_RATIO_DEVIATION, CFG_RATIO_DEVIATION_DEFAULT);
    }

    private BigDecimal decimalConfig(String key, String defaultValue) {
        String raw = config.get(key);
        if (isBlank(raw)) {
            LOG.warning("配置 " + key + " 未播种（CFG-08），容错取缺省 " + defaultValue
                    + "（种子补播归播种微单，T-188 CFG-26 先例）");
            return new BigDecimal(defaultValue);
        }
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            if (v.signum() <= 0 || v.compareTo(BigDecimal.ONE) > 0) {
                LOG.warning("配置 " + key + " 取值超域 (0,1]（" + raw + "），容错取缺省 " + defaultValue);
                return new BigDecimal(defaultValue);
            }
            return v;
        } catch (NumberFormatException e) {
            LOG.warning("配置 " + key + " 取值非法（" + raw + "），容错取缺省 " + defaultValue);
            return new BigDecimal(defaultValue);
        }
    }

    /** 再平衡请求（四类触发场景 + 调整前后值；{@code T-078} 新增请求 DTO）。 */
    public static final class RebalanceRequest {
        /** 套期关系。 */
        public Long hedgeRelationId;
        /**
         * 触发场景（四类，Q-8）：{@code EXPOSURE_DELTA} / {@code RATIO_DEVIATION} /
         * {@code PLAN_REVISED} / {@code EXPOSURE_GONE}。偏离值由系统按数据面复核，
         * 不采信客户端声明（防伪造触发）。
         */
        public String triggerScenario;
        /** 触发原因描述（必填；落 trigger_reason）。 */
        public String triggerReason;
        /** 调整后套保比率（∈ (0,1]；敞口消失场景亦必填，记录申报目标）。 */
        public BigDecimal ratioAfter;
        /** 调整后数量（> 0；敞口消失场景须为 0）。 */
        public BigDecimal qtyAfter;
        /** 再平衡结论是否需反馈 PLAN 调整计划（Y/N；R-ACCT-3 最小承载）。 */
        public String planFeedbackRequired;
    }

    /** 四类触发的复核证据（拒绝时拼「当前偏离值」；内部载体）。 */
    private static final class Evaluation {
        boolean triggered;
        String triggerSide;
        BigDecimal exposureDelta = BigDecimal.ZERO;
        BigDecimal ratioDeviation = BigDecimal.ZERO;
        String planStatus = "null";
        boolean exposureExists;
        String currentValue;
        String thresholdValue;
    }

    // ================================================================ 请求 DTO

    /** 评估请求（观察点数据；{@code T-077} 新增请求 DTO）。 */
    public static final class AssessRequest {
        /** 套期关系。 */
        public Long hedgeRelationId;
        /** 会计期间（如 2026-09）。 */
        public String periodKey;
        /** 观察点①品种相关性（Y=同品种强 / N=近相关，N 须附 linkageNote）。 */
        public String observerLinkage;
        /** 近相关时的书面经济关系说明（Q-7）。 */
        public String linkageNote;
        /** 观察点②方向一致性（Y/N）。 */
        public String observerDirection;
        /** 观察点③期限对应性（Y/N；判定基准 = exposureExpectedDate）。 */
        public String observerTerm;
        /** 敞口预计实现日（期限对应性判定基准）。 */
        public LocalDate exposureExpectedDate;
        /** 量化监测指标（偏离 1 的值；可空；仅监测不判定）。 */
        public BigDecimal monitorDeviationRatio;
    }

    // ================================================================ 内部

    /**
     * 再平衡记录读取面：按套期关系取再平衡史（{@code idx_rebalance_relation_time}）。
     *
     * @param hedgeRelationId 套期关系
     * @param operatorId      调用者
     * @return 列表（{@code rebalance_time} 升序）
     */
    public List<RebalanceRecord> listRebalances(long hedgeRelationId, long operatorId) {
        if (!rebalanceSupported()) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "再平衡依赖未装配");
        }
        HedgeRelation relation = hedgeRelationDao.findById(hedgeRelationId);
        if (relation == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期关系不存在：id=" + hedgeRelationId);
        }
        visibility.assertSubAccountVisible(operatorId, requireSubOfRelation(relation));
        return rebalanceDao.findByHedgeRelation(hedgeRelationId);
    }

    /** 再平衡记录行视图字段透出（handler 复用；camelCase 契约形态）。 */
    public static Map<String, Object> rebalanceView(RebalanceRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("subAccountId", r.subAccountId);
        m.put("hedgeRelationId", r.hedgeRelationId);
        m.put("rebalanceNo", r.rebalanceNo);
        m.put("triggerType", r.triggerType);
        m.put("triggerSide", r.triggerSide);
        m.put("triggerReason", r.triggerReason);
        m.put("triggerExposureDelta", r.triggerExposureDelta == null ? null : r.triggerExposureDelta.toPlainString());
        m.put("triggerRatioDeviation", r.triggerRatioDeviation == null ? null : r.triggerRatioDeviation.toPlainString());
        m.put("triggerThreshold", r.triggerThreshold == null ? null : r.triggerThreshold.toPlainString());
        m.put("ratioBefore", r.ratioBefore == null ? null : r.ratioBefore.toPlainString());
        m.put("ratioAfter", r.ratioAfter == null ? null : r.ratioAfter.toPlainString());
        m.put("qtyBefore", r.qtyBefore == null ? null : r.qtyBefore.toPlainString());
        m.put("qtyAfter", r.qtyAfter == null ? null : r.qtyAfter.toPlainString());
        m.put("newHedgeDocId", r.newHedgeDocId);
        m.put("newHedgeRelationId", r.newHedgeRelationId);
        m.put("executorId", r.executorId);
        m.put("rebalanceTime", r.rebalanceTime == null ? null : r.rebalanceTime.toString());
        m.put("planFeedbackFlag", r.planFeedbackFlag);
        return m;
    }

    private String requireEnum(String value, String field, String... allowed) {
        if (value == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 必填");
        }
        for (String a : allowed) {
            if (a.equals(value)) {
                return value;
            }
        }
        throw BizException.of(ErrorCode.PARAM_INVALID,
                field + " 取值非法（合法：" + String.join("/", allowed) + "）：" + value);
    }

    /** 观察点取值解析：'Y'/'N' 缺失 ⇒ 记入缺失清单（由调用方拒绝）。 */
    private String requireFlag(String value, String field, List<String> missing) {
        if (isBlank(value)) {
            missing.add(field + "（取值 Y/N）");
            return null;
        }
        if (!"Y".equals(value.trim()) && !"N".equals(value.trim())) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    field + " 取值非法（Y/N）：" + value);
        }
        return value.trim();
    }

    /** CFG-07b 量化监测阈值（容错缺省；data-init.sql:322 已播种，缺种子时仅记录日志）。 */
    private BigDecimal monitorThreshold() {
        String raw = config.get(CFG_MONITOR_THRESHOLD);
        if (isBlank(raw)) {
            LOG.warning("配置 " + CFG_MONITOR_THRESHOLD + " 未播种（CFG-07b），容错取缺省 "
                    + CFG_MONITOR_THRESHOLD_DEFAULT + "（种子补播归播种微单，T-188 CFG-26 先例）");
            return new BigDecimal(CFG_MONITOR_THRESHOLD_DEFAULT);
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            LOG.warning("配置 " + CFG_MONITOR_THRESHOLD + " 取值非法（" + raw + "），容错取缺省 "
                    + CFG_MONITOR_THRESHOLD_DEFAULT);
            return new BigDecimal(CFG_MONITOR_THRESHOLD_DEFAULT);
        }
    }

    /** CFG-07 观察点配置（容错缺省；本单判定固定三项，配置用于呈现与后续子集扩展的读取位）。 */
    public String observersConfig() {
        String raw = config.get(CFG_OBSERVERS);
        if (isBlank(raw)) {
            LOG.warning("配置 " + CFG_OBSERVERS + " 未播种（CFG-07），容错取缺省 "
                    + CFG_OBSERVERS_DEFAULT + "（种子补播归播种微单）");
            return CFG_OBSERVERS_DEFAULT;
        }
        return raw.trim();
    }

    /** 判定过程留痕（FR-ACCT-4 验收 3：第三方可复现——记录所用数据与判定路径）。 */
    private String buildObserverDetail(HedgeRelation relation, String linkage, String direction,
                                       String term, LocalDate expectedDate, int termDeviationDays,
                                       String conclusion, BigDecimal deviation,
                                       BigDecimal threshold, boolean flagged) {
        Map<String, Object> observers = new LinkedHashMap<>();
        observers.put("linkage（品种相关性）", linkage);
        observers.put("direction（方向一致性）", direction);
        observers.put("term（期限对应性）", term);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", METHOD_QUALITATIVE);
        m.put("relationNo", relation.relationNo);
        m.put("rule", "R-ACCT-2（Q-7）：三项观察点均满足即有效；量化监测仅提示复核不判定");
        m.put("observers", observers);
        m.put("termExpectedDate", expectedDate == null ? null : expectedDate.toString());
        m.put("termDeviationDays", termDeviationDays);
        m.put("termToleranceDays", TERM_TOLERANCE_DAYS);
        m.put("monitorDeviation", deviation == null ? null : deviation.toPlainString());
        m.put("monitorThreshold", threshold.toPlainString());
        m.put("monitorFlagged", flagged);
        m.put("monitorAffectsConclusion", false);
        m.put("conclusion", conclusion);
        m.put("decisionPath", "EFFECTIVE ⇔ linkage=='Y' && direction=='Y' && term=='Y'");
        m.put("assessedAt", Clocks.now().toString());
        return JsonCodec.toJson(m);
    }

    private void applyScope(java.util.function.Consumer<String> cond,
                            java.util.function.Consumer<Object> args, long operatorId) {
        List<Integer> visible = visibility.visibleSubAccountIds(operatorId);
        if (visible == null || visible.isEmpty()) {
            cond.accept("sub_account_id IN (-1)");
        } else if (!visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            SubAccountScope scope = SubAccountScope.of("sub_account_id", visible);
            cond.accept(scope.sql());
            for (Object p : scope.params()) {
                args.accept(p);
            }
        }
    }

    private void requireRelStatus(String status) {
        switch (status) {
            case REL_DESIGNATED:
            case REL_EFFECTIVE:
            case REL_NEED_REBALANCE:
            case REL_REDESIGNATED:
            case REL_TERMINATED:
                return;
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "status 取值非法（套期关系 5 态）：" + status
                                + "；复现=使用 DESIGNATED/EFFECTIVE/NEED_REBALANCE/REDESIGNATED"
                                + "/TERMINATED 之一");
        }
    }

    private void requireConclusion(String conclusion) {
        switch (conclusion) {
            case CONCLUSION_EFFECTIVE:
            case CONCLUSION_INEFFECTIVE:
            case CONCLUSION_NEED_REBALANCE:
                return;
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "conclusion 取值非法：" + conclusion
                                + "；复现=使用 EFFECTIVE/INEFFECTIVE/NEED_REBALANCE 之一");
        }
    }

    private Integer requireSubOfPlan(HedgePlan plan) {
        if (plan.subAccountId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划子账户标识缺失（子账户贯穿字段规范，INDEX.md §2.1）；复现=核对计划归属列");
        }
        return plan.subAccountId;
    }

    private Integer requireSubOfRelation(HedgeRelation relation) {
        if (relation.subAccountId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期关系子账户标识缺失（子账户贯穿字段规范）；复现=核对归属列");
        }
        return relation.subAccountId;
    }

    private Integer requireSubOfAssess(EffectivenessAssess assess) {
        if (assess.subAccountId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "评估记录子账户标识缺失（子账户贯穿字段规范）；复现=核对归属列");
        }
        return assess.subAccountId;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 以「唯一约束冲突 ⇒ 重新取号并重试」执行套期关系编号分配
     * （{@code DATA_MODEL.md} §3.5；{@code FundService.retryApplyNo} 同构形态）。
     *
     * <p>仅当候选 {@code relation_no} 确实已被占用（= 编号冲突）时重试——本服务以
     * 「{@code findByRelationNo} 命中」判定占用；其他唯一约束冲突原样抛出，不误报为「编号冲突」。
     */
    private HedgeRelation retryOnRelationNoConflict(Supplier<HedgeRelation> attempt,
                                                    Supplier<String> candidateNo,
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
                            "唯一约束冲突非套期关系编号（不重试）；复现=核对该事务其余唯一约束", e);
                }
                throw unique;
            }
        });
        return (HedgeRelation) out[0];
    }

    /**
     * 从异常链中解出「唯一约束冲突」的 {@link SQLException}（{@code SQLState 23xx}）；
     * {@code FundService.uniqueViolationCause} 同构（TxTemplate 包装形态一致）。
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
}
