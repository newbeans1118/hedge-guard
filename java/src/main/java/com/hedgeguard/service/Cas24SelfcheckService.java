package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.BizNoGenerator;
import com.hedgeguard.dao.Cas24SelfcheckDao;
import com.hedgeguard.dao.Cas24SelfcheckItemDao;
import com.hedgeguard.dao.EffectivenessAssessDao;
import com.hedgeguard.dao.HedgeDocDao;
import com.hedgeguard.dao.HedgeRelationDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.RebalanceRecordDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Cas24Selfcheck;
import com.hedgeguard.dao.entity.Cas24SelfcheckItem;
import com.hedgeguard.dao.entity.EffectivenessAssess;
import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.entity.HedgeRelation;
import com.hedgeguard.dao.entity.RebalanceRecord;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * CAS 24 条款自检（{@code FR-ACCT-14}；{@code T-197} 端点化）。
 *
 * <p><b>判定逻辑产品化</b>（判例「端点交付单须把判定逻辑产品化」）：T-085 的装配级基线
 * （{@code M4DocPackageCas24SelfcheckTest}，<b>验收对照物、本服务不照抄其断言</b>）把五条
 * 判定写在测试内；本服务按 {@code FR-ACCT-14} 处理规则（{@code R-ACCT-1}/{@code R-ACCT-10}）
 * 逐条实现为生产逻辑，经生产 {@link DmCas24SelfcheckDao}（T-076 交付面）落批次与条目：
 * <ol>
 *   <li>① {@code FORMAL_DESIGNATION} 套期关系正式指定——指定留痕在案
 *       （{@code designated_time} 非空 + 关联书面文件 {@code hedge_doc_id}&gt;0）；状态机后续
 *       流转（如 {@code NEED_REBALANCE}）不撤销「曾经正式指定」的事实；</li>
 *   <li>② {@code WRITTEN_DOC} 书面文件载明要素——当前版本（{@code is_current='Y'}）存在且
 *       三要素列（正式指定/有效性评估方法/再平衡机制）非空（{@code R-ACCT-1} 三要素门禁）；</li>
 *   <li>③ {@code EFFECTIVENESS_RECORDS} 有效性评估记录齐备——该关系在该期间存在<b>未更正</b>
 *       评估记录（{@code uk_eff_assess_rel_period} 承载；R-ACCT-2「每期至少一次」）；</li>
 *   <li>④ {@code REBALANCE_RECORDS} 再平衡记录齐备——<b>已触发（关系状态机
 *       {@code NEED_REBALANCE}）但无任何再平衡记录 ⇒ 该项「不通过」</b>（FR-ACCT-14 验收 2）；
 *       有记录 ⇒ 通过（证据=记录 + 新文档/新关系链接）；未触发 ⇒ {@code NOT_APPLICABLE}；</li>
 *   <li>⑤ {@code DUAL_PNL_PRESENTED} 双侧盈亏同时呈现——该子账户该期间效果<b>汇总行</b>存在且
 *       实货栏/衍生品栏<b>成对非空</b>（{@code R-ACCT-4} 同屏双栏；口径经 {@link AcctService#getEffect}
 *       同源取数，R-ACCT-12 口径单一）。</li>
 * </ol>
 *
 * <p><b>「待外部复核」标注</b>（{@code R-ACCT-10}）：批次 {@code pending_external_review} 恒
 * {@code 'Y'}（DB CHECK 结构保证，无移除入口）；「自检不替代外部复核」显式说明随
 * {@code not_substitute_note} 落库并进导出报告（FR-ACCT-14 验收 3）。
 *
 * <p><b>自检范围</b>（FR-ACCT-14 输入列「套期关系标识<b>或</b>会计期间、自检范围」）：
 * <ul>
 *   <li>{@code HEDGE_RELATION}：单关系五条逐条判定（{@code scope_key=关系编号}）；</li>
 *   <li>{@code PERIOD}：按期间聚合——对该期间存在未更正评估记录的全部<b>可见</b>关系逐条判定后
 *       聚合为一批次（{@code scope_key=期间键}，批次 {@code sub_account_id=NULL}=全账户自检，
 *       §4.6.1 #51 口径 ②）；期间内无可自检对象 ⇒ 批次结论 {@code CANNOT_SELFCHECK} +
 *       原因（FR-ACCT-14 输出列「关键数据缺失时标注无法自检及原因」）。</li>
 * </ul>
 *
 * <p><b>可见性</b>（NFR-SEC-7）：单关系自检按关系子账户做<b>读面</b>断言
 * （{@link SubAccountVisibility#assertSubAccountVisible}；FR-ACCT-14 使用者为「财务/风控岗
 * <b>或审计/合规岗</b>」——审计/合规岗常为跨账户只读授权，写面断言会 contrary 排除其法定使用人，
 * 故批次落库按读面放行、据实披露）；期间自检按可见集过滤参与关系（fail-closed：空集恒假）。
 *
 * <p><b>幂等</b>：同一 {@code (scope_type, scope_key)} 同一业务日重复发起 ⇒ 返回既有批次、
 * 不重复落批（FR-ACCT-14 验收重复执行不留重复账；跨业务日重跑 = 新批次，属合规复检留痕）。
 * {@code HEDGE_RELATION} 的 {@code scope_key} = {@code 关系编号|期间键}
 * （T-197 复核 O-2 收口：自检结论随期间评估记录变化，幂等键不含期间会同日跨期间错误命中；
 * {@code relation_no} 32 + 分隔符 1 + {@code yyyy-MM} 7 = 40 ≤ {@code scope_key} 64，无截断）。
 *
 * <p><b>取号</b>：{@code selfcheck_no} 全系统唯一（{@code uk_cas24_selfcheck_no}），
 * {@code DATA_MODEL.md} §3.5 未列形态 ⇒ 本服务定义 {@code SC<yyyyMMdd><seq(4)>}——编号含日期段、
 * 按业务日重置 ⇒ 取「当日前缀内字典序最大 + 1」（T-137/T-141「当日最大 +1」判例前提成立）
 * + 唯一约束冲突重试最多 3 次（{@code AcctService.retryOnRelationNoConflict} 同构）。
 */
public final class Cas24SelfcheckService {

    /** 自检范围：按套期关系。 */
    public static final String SCOPE_HEDGE_RELATION = "HEDGE_RELATION";
    /** 自检范围：按会计期间（聚合批次，全账户）。 */
    public static final String SCOPE_PERIOD = "PERIOD";

    /** 自检批次号前缀（{@code DATA_MODEL.md} §3.5 未列，本服务定义）。 */
    public static final String SELFCHECK_NO_PREFIX = "SC";
    /** 自检批次号序号位宽。 */
    public static final int SELFCHECK_NO_SEQ_WIDTH = 4;

    /** 批次结论：全部通过。 */
    public static final String CONCLUSION_ALL_PASS = "ALL_PASS";
    /** 批次结论：存在不通过条目。 */
    public static final String CONCLUSION_HAS_FAIL = "HAS_FAIL";
    /** 批次结论：关键数据缺失，无法自检（须给原因）。 */
    public static final String CONCLUSION_CANNOT_SELFCHECK = "CANNOT_SELFCHECK";

    /** 条款①：套期关系正式指定。 */
    public static final String ITEM_FORMAL_DESIGNATION = "FORMAL_DESIGNATION";
    /** 条款②：书面文件载明要素。 */
    public static final String ITEM_WRITTEN_DOC = "WRITTEN_DOC";
    /** 条款③：有效性评估记录齐备。 */
    public static final String ITEM_EFFECTIVENESS_RECORDS = "EFFECTIVENESS_RECORDS";
    /** 条款④：再平衡记录（如触发）齐备。 */
    public static final String ITEM_REBALANCE_RECORDS = "REBALANCE_RECORDS";
    /** 条款⑤：双侧盈亏同时呈现。 */
    public static final String ITEM_DUAL_PNL_PRESENTED = "DUAL_PNL_PRESENTED";

    /** 条目结论：通过。 */
    public static final String RESULT_PASS = "PASS";
    /** 条目结论：不通过。 */
    public static final String RESULT_FAIL = "FAIL";
    /** 条目结论：不适用（如未触发再平衡）。 */
    public static final String RESULT_NOT_APPLICABLE = "NOT_APPLICABLE";

    /** 「待外部复核」标注（恒 'Y'，R-ACCT-10；DB CHECK 结构保证）。 */
    public static final String PENDING_EXTERNAL_REVIEW = "Y";
    /** 「自检不替代外部复核」显式说明（R-ACCT-10）。 */
    public static final String NOT_SUBSTITUTE_NOTE = "自检不替代外部复核";

    /** 关系状态机：需再平衡（④「已触发」判定键；R-ACCT-2）。 */
    private static final String REL_NEED_REBALANCE = "NEED_REBALANCE";

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int RETRY_MAX_ATTEMPTS = 3;
    /** 证据说明列上限（{@code evidence_desc VARCHAR(500)}）；截断留 3 位省略号。 */
    private static final int EVIDENCE_DESC_MAX = 500;
    /** 聚合批次关系编号样例列出上限（防止 500 溢出）。 */
    private static final int AGGREGATE_SAMPLE = 5;

    private final TxTemplate tx;
    private final SubAccountVisibility visibility;
    private final AcctService acctService;
    private final HedgeRelationDao hedgeRelationDao;
    private final HedgeDocDao hedgeDocDao;
    private final EffectivenessAssessDao assessDao;
    private final RebalanceRecordDao rebalanceDao;
    private final Cas24SelfcheckDao selfcheckDao;
    private final Cas24SelfcheckItemDao itemDao;

    /**
     * 构造。
     *
     * @param tx               事务模板
     * @param visibility       子账户可见性（读面断言；生产装配为 AuthService）
     * @param acctService      套期会计服务（⑤经 {@link AcctService#getEffect} 同源取数，R-ACCT-12）
     * @param hedgeRelationDao 套期关系 DAO（①④取数）
     * @param hedgeDocDao      书面文件 DAO（②取数）
     * @param assessDao        有效性评估 DAO（③取数）
     * @param rebalanceDao     再平衡记录 DAO（④取数）
     * @param selfcheckDao     自检批次 DAO（{@code t_cas24_selfcheck} 写入；T-076 交付面承接）
     * @param itemDao          自检条目 DAO（{@code t_cas24_selfcheck_item} 写入）
     */
    public Cas24SelfcheckService(TxTemplate tx, SubAccountVisibility visibility,
                                 AcctService acctService,
                                 HedgeRelationDao hedgeRelationDao, HedgeDocDao hedgeDocDao,
                                 EffectivenessAssessDao assessDao, RebalanceRecordDao rebalanceDao,
                                 Cas24SelfcheckDao selfcheckDao, Cas24SelfcheckItemDao itemDao) {
        this.tx = tx;
        this.visibility = visibility;
        this.acctService = acctService;
        this.hedgeRelationDao = hedgeRelationDao;
        this.hedgeDocDao = hedgeDocDao;
        this.assessDao = assessDao;
        this.rebalanceDao = rebalanceDao;
        this.selfcheckDao = selfcheckDao;
        this.itemDao = itemDao;
    }

    // ================================================================ 发起自检（FR-ACCT-14 验收 1/2 + 无法自检）

    /**
     * 执行 CAS 24 自检并落批次（{@code POST /acct/selfcheck}）。
     *
     * @param req        自检请求（scopeType + hedgeRelationId/periodKey + periodKey 必填）
     * @param operatorId 执行人（会话身份；勿采客户端传入明文）
     * @return 落库（或幂等命中）的自检批次
     */
    public Cas24Selfcheck runSelfcheck(SelfcheckRequest req, long operatorId) {
        if (req == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "自检请求不得为空；复现=补齐请求体后重试");
        }
        String scopeType = req.scopeType == null ? null : req.scopeType.trim();
        if (!SCOPE_HEDGE_RELATION.equals(scopeType) && !SCOPE_PERIOD.equals(scopeType)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "scopeType 必填且须为 HEDGE_RELATION/PERIOD（FR-ACCT-14 自检范围）；实际="
                            + req.scopeType);
        }
        String period = requirePeriodKey(req.periodKey);
        if (SCOPE_HEDGE_RELATION.equals(scopeType)) {
            return runRelationScope(req, period, operatorId);
        }
        return runPeriodScope(period, operatorId);
    }

    /** 单关系自检（验收 1/2/4 主路径）。 */
    private Cas24Selfcheck runRelationScope(SelfcheckRequest req, String period, long operatorId) {
        if (req.hedgeRelationId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "scopeType=HEDGE_RELATION 时 hedgeRelationId 必填；复现=补齐请求体后重试");
        }
        HedgeRelation relation = hedgeRelationDao.findById(req.hedgeRelationId);
        if (relation == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期关系不存在：hedgeRelationId=" + req.hedgeRelationId
                            + "；复现=以 GET /acct/hedge-relation 核对后重试");
        }
        // 读面可见性（NFR-SEC-7；FR-ACCT-14 使用者含审计/合规岗——见类注披露）
        visibility.assertSubAccountVisible(operatorId, requireSubOf(relation));
        // 幂等：同 scope 同业务日重复发起 ⇒ 返回既有批次，不重复落批。
        // scope_key 含期间键（T-197 复核 O-2 收口：同日同关系换期间须各自成批——
        // 自检结论随期间评估记录变化，幂等键不含期间会错误返回他期间快照）。
        Cas24Selfcheck existing = findTodayBatch(SCOPE_HEDGE_RELATION,
                relationScopeKey(relation.relationNo, period));
        if (existing != null) {
            return existing;
        }
        EffectivenessAssess active = assessDao.findActiveByRelationAndPeriod(relation.id, period);
        List<Cas24SelfcheckItem> items =
                evaluateRelation(relation, active, period, operatorId, null);
        int pass = 0;
        int fail = 0;
        for (Cas24SelfcheckItem it : items) {
            if (RESULT_PASS.equals(it.result)) {
                pass++;
            } else if (RESULT_FAIL.equals(it.result)) {
                fail++;
            }
        }
        Cas24Selfcheck sc = newBatch(SCOPE_HEDGE_RELATION,
                relationScopeKey(relation.relationNo, period),
                relation.subAccountId, pass, fail, null);
        return insertBatch(sc, items, operatorId);
    }

    /** 期间聚合自检（FR-ACCT-14 输入「会计期间」路径；含「无法自检」承载）。 */
    private Cas24Selfcheck runPeriodScope(String period, long operatorId) {
        Cas24Selfcheck existing = findTodayBatch(SCOPE_PERIOD, period);
        if (existing != null) {
            return existing;
        }
        List<HedgeRelation> involved = involvedRelations(period, operatorId);
        if (involved.isEmpty()) {
            // 关键数据缺失 ⇒ 无法自检 + 原因（FR-ACCT-14 输出列）
            Cas24Selfcheck sc = newBatch(SCOPE_PERIOD, period, null, 0, 0,
                    "该期间可见范围内无任何有效性评估记录，无法圈定自检对象（关键数据缺失，"
                            + "FR-ACCT-14 输出列「无法自检」）；复现=先经 POST /acct/effectiveness"
                            + " 提交该期间评估后重试");
            return insertBatch(sc, new ArrayList<>(), operatorId);
        }
        // 逐关系判定后聚合为一批次（批次 sub_account_id=NULL=全账户自检）
        List<List<Cas24SelfcheckItem>> perRelation = new ArrayList<>();
        for (HedgeRelation r : involved) {
            EffectivenessAssess active = assessDao.findActiveByRelationAndPeriod(r.id, period);
            perRelation.add(evaluateRelation(r, active, period, operatorId, null));
        }
        List<Cas24SelfcheckItem> items = aggregateItems(period, involved, perRelation);
        int pass = 0;
        int fail = 0;
        for (Cas24SelfcheckItem it : items) {
            if (RESULT_PASS.equals(it.result)) {
                pass++;
            } else if (RESULT_FAIL.equals(it.result)) {
                fail++;
            }
        }
        Cas24Selfcheck sc = newBatch(SCOPE_PERIOD, period, null, pass, fail, null);
        return insertBatch(sc, items, operatorId);
    }

    // ================================================================ 查询面（FR-ACCT-14 清单/报告）

    /**
     * 按批次号取自检批次（含可见性断言）。
     *
     * @param selfcheckNo 自检批次号
     * @param operatorId  调用者
     * @return 自检批次
     */
    public Cas24Selfcheck findByNo(String selfcheckNo, long operatorId) {
        Cas24Selfcheck sc = selfcheckDao.findBySelfcheckNo(requireSelfcheckNo(selfcheckNo));
        if (sc == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "自检批次不存在：selfcheckNo=" + selfcheckNo
                            + "；复现=以 GET /acct/selfcheck 清单核对批次号后重试");
        }
        if (sc.subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, sc.subAccountId);
        }
        return sc;
    }

    /**
     * 分页查询自检批次（{@code GET /acct/selfcheck} 清单；PageData 分页信封由 handler 组装）。
     *
     * @param query        分页（缺省 selfcheck_time 倒序由 handler 给定）
     * @param scopeType    范围筛选（可空）
     * @param subAccountId 子账户筛选（可空；非空时做读面可见性断言）
     * @param conclusion   结论筛选（可空）
     * @param operatorId   调用者
     * @return 分页结果
     */
    public PageResult<Cas24Selfcheck> listBatches(PageQuery query, String scopeType,
                                                  Integer subAccountId, String conclusion,
                                                  long operatorId) {
        if (scopeType != null && !SCOPE_HEDGE_RELATION.equals(scopeType)
                && !SCOPE_PERIOD.equals(scopeType)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "scopeType 须为 HEDGE_RELATION/PERIOD：" + scopeType);
        }
        if (conclusion != null && !CONCLUSION_ALL_PASS.equals(conclusion)
                && !CONCLUSION_HAS_FAIL.equals(conclusion)
                && !CONCLUSION_CANNOT_SELFCHECK.equals(conclusion)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "conclusion 须为 ALL_PASS/HAS_FAIL/CANNOT_SELFCHECK：" + conclusion);
        }
        List<String> conds = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, subAccountId);
            conds.add("sub_account_id = ?");
            args.add(subAccountId);
        } else {
            // 无显式筛选 ⇒ 按调用者可见集过滤（fail-closed；哨兵 0 = 跨账户只读不过滤；
            // 注意：全账户自检批次（sub_account_id NULL）不受可见集过滤——聚合结论不含单账户明细）
            List<Integer> visible = visibility.visibleSubAccountIds(operatorId);
            if (visible == null || visible.isEmpty()) {
                conds.add("sub_account_id IS NOT NULL AND sub_account_id IN (-1)");
            } else if (!visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
                // 括号包裹 OR 子句——与 AND 连接时避免优先级漂移（A OR B AND C 语义陷阱）
                StringBuilder in = new StringBuilder(
                        "(sub_account_id IS NULL OR sub_account_id IN (");
                for (int i = 0; i < visible.size(); i++) {
                    in.append(i == 0 ? "?" : ", ?");
                }
                in.append("))");
                conds.add(in.toString());
                args.addAll(visible);
            }
        }
        if (scopeType != null) {
            conds.add("scope_type = ?");
            args.add(scopeType);
        }
        if (conclusion != null) {
            conds.add("conclusion = ?");
            args.add(conclusion);
        }
        return selfcheckDao.findPage(query,
                conds.isEmpty() ? null : String.join(" AND ", conds), args);
    }

    // ================================================================ 导出（FR-ACCT-14 验收 3/4）

    /**
     * 导出自检报告 CSV（{@code GET /acct/export}；FR-ACCT-14 验收 3/4）。
     *
     * <p>报告含：批次信息（结论/通过与不通过条数/「待外部复核」标注/「自检不替代外部复核」
     * 显式说明/<b>自检时间</b>/<b>执行人</b>——验收 3/4）+ 逐条目结论与证据位置（验收 1）。
     * 文件名 {@code cas24-selfcheck-<selfcheckNo>.csv}：{@code selfcheckNo} 先经字符白名单校验
     * 再进 {@code Content-Disposition}（响应头注入面，T-107 先例）。
     *
     * @param selfcheckNo 自检批次号
     * @param operatorId  调用者
     * @return 导出结果（文件名 + CSV 内容）
     */
    public SelfcheckExportResult exportReport(String selfcheckNo, long operatorId) {
        Cas24Selfcheck sc = findByNo(selfcheckNo, operatorId);
        List<Cas24SelfcheckItem> items = itemDao.findBySelfcheck(sc.id);
        StringBuilder sb = new StringBuilder();
        sb.append(joinCsv(CSV_COLUMNS)).append("\r\n");
        sb.append(csvRow(BATCH_SECTION, sc, null)).append("\r\n");
        for (Cas24SelfcheckItem it : items) {
            sb.append(csvRow(ITEM_SECTION, sc, it)).append("\r\n");
        }
        SelfcheckExportResult out = new SelfcheckExportResult();
        out.selfcheckNo = sc.selfcheckNo;
        out.fileName = "cas24-selfcheck-" + sc.selfcheckNo + ".csv";
        out.content = sb.toString();
        return out;
    }

    /** 导出结果（文件名已过白名单校验的批次号拼装；handler 直进 Content-Disposition）。 */
    public static final class SelfcheckExportResult {
        /** 自检批次号。 */
        public String selfcheckNo;
        /** 导出文件名（{@code cas24-selfcheck-<no>.csv}）。 */
        public String fileName;
        /** CSV 内容（与落库批次/条目同源）。 */
        public String content;
    }

    /** 自检请求体（{@code POST /acct/selfcheck}）。 */
    public static final class SelfcheckRequest {
        /** 自检范围：{@code HEDGE_RELATION} / {@code PERIOD}（必填）。 */
        public String scopeType;
        /** 套期关系（scopeType=HEDGE_RELATION 时必填）。 */
        public Long hedgeRelationId;
        /** 会计期间（yyyy-MM；必填——③④按期间、⑤双侧盈亏按期间呈现）。 */
        public String periodKey;
    }

    // ================================================================ 五条判定（FR-ACCT-14 处理规则）

    /**
     * 对单关系执行五条判定（periodKey 为评估/呈现期间）。
     *
     * @param relation       套期关系（DB 现读）
     * @param active         该关系该期间未更正评估记录（可空）
     * @param period         会计期间
     * @param operatorId     执行人（⑤取数透传）
     * @param relationPrefix 聚合批次证据说明前缀（单关系传 {@code null}）
     * @return 五条条目（itemSeq 1~5）
     */
    private List<Cas24SelfcheckItem> evaluateRelation(HedgeRelation relation,
                                                      EffectivenessAssess active, String period,
                                                      long operatorId, String relationPrefix) {
        List<Cas24SelfcheckItem> items = new ArrayList<>();
        items.add(checkFormalDesignation(relation, relationPrefix));
        items.add(checkWrittenDoc(relation, relationPrefix));
        items.add(checkEffectivenessRecords(relation, active, period, relationPrefix));
        items.add(checkRebalanceRecords(relation, relationPrefix));
        items.add(checkDualPnl(relation, period, operatorId, relationPrefix));
        return items;
    }

    /** ① 套期关系正式指定：指定留痕在案（designated_time + 关联书面文件）。 */
    private Cas24SelfcheckItem checkFormalDesignation(HedgeRelation relation, String prefix) {
        Cas24SelfcheckItem it = newItem(1, ITEM_FORMAL_DESIGNATION, "①套期关系正式指定");
        boolean ok = relation.designatedTime != null
                && relation.hedgeDocId != null && relation.hedgeDocId > 0;
        if (ok) {
            it.result = RESULT_PASS;
            it.evidenceType = "t_hedge_relation";
            it.evidenceId = relation.id;
            it.evidenceDesc = truncated(prefix + "关系 " + relation.relationNo
                    + " status=" + relation.status + "（designated_time=" + relation.designatedTime
                    + "，指定文档 id=" + relation.hedgeDocId + "）——正式指定留痕在案（R-ACCT-1）");
        } else {
            it.result = RESULT_FAIL;
            it.failReason = "指定留痕缺失（designated_time/hedge_doc_id）";
        }
        return it;
    }

    /** ② 书面文件载明要素：当前版本存在且三要素列非空（R-ACCT-1 三要素门禁）。 */
    private Cas24SelfcheckItem checkWrittenDoc(HedgeRelation relation, String prefix) {
        Cas24SelfcheckItem it = newItem(2, ITEM_WRITTEN_DOC,
                "②书面文件（目标策略/被套期项目/套期工具/评估方法/再平衡机制）");
        HedgeDoc cur = relation.hedgePlanId == null
                ? null : hedgeDocDao.findCurrent(relation.hedgePlanId);
        boolean ok = cur != null
                && notBlank(cur.elemFormalDesignation)
                && notBlank(cur.elemEffectivenessMethod)
                && notBlank(cur.elemRebalanceMechanism);
        if (ok) {
            it.result = RESULT_PASS;
            it.evidenceType = "t_hedge_doc";
            it.evidenceId = cur.id;
            it.evidenceDesc = truncated(prefix + "计划 " + relation.hedgePlanId + " 当前版本 v"
                    + cur.docVersion + "（id=" + cur.id + "）三要素列非空：正式指定/有效性评估方法"
                    + "（定性经济关系）/再平衡机制（R-ACCT-1）");
        } else {
            it.result = RESULT_FAIL;
            it.failReason = cur == null ? "无当前版本书面文件" : "三要素存在空缺";
        }
        return it;
    }

    /** ③ 有效性评估记录齐备：该关系该期间存在未更正评估记录（R-ACCT-2 每期至少一次）。 */
    private Cas24SelfcheckItem checkEffectivenessRecords(HedgeRelation relation,
                                                         EffectivenessAssess active,
                                                         String period, String prefix) {
        Cas24SelfcheckItem it = newItem(3, ITEM_EFFECTIVENESS_RECORDS, "③有效性评估记录齐备");
        if (active != null && active.id > 0) {
            it.result = RESULT_PASS;
            it.evidenceType = "t_effectiveness_assess";
            it.evidenceId = active.id;
            it.evidenceDesc = truncated(prefix + "关系 " + relation.relationNo + " 期间 " + period
                    + " 评估记录（id=" + active.id + "，结论=" + active.conclusion + "，三观察点="
                    + active.observerLinkage + "/" + active.observerDirection + "/"
                    + active.observerTerm + "）");
        } else {
            it.result = RESULT_FAIL;
            it.failReason = "期间 " + period + " 无未更正的有效性评估记录（R-ACCT-2 每期至少一次）";
        }
        return it;
    }

    /**
     * ④ 再平衡记录齐备（FR-ACCT-14 验收 2）：<b>已触发但未生成 ⇒ 不通过</b>
     * （触发键=关系状态机 NEED_REBALANCE，R-ACCT-2「结论无效须终止或再平衡」）。
     */
    private Cas24SelfcheckItem checkRebalanceRecords(HedgeRelation relation, String prefix) {
        Cas24SelfcheckItem it = newItem(4, ITEM_REBALANCE_RECORDS, "④再平衡记录（如触发）齐备");
        List<RebalanceRecord> rbs = rebalanceDao.findByHedgeRelation(relation.id);
        boolean triggered = REL_NEED_REBALANCE.equals(relation.status);
        if (triggered && rbs.isEmpty()) {
            // FR-ACCT-14 验收 2：缺少再平衡记录（已触发但未生成）⇒ 该项「不通过」
            it.result = RESULT_FAIL;
            it.failReason = "已触发再平衡（关系状态机 NEED_REBALANCE，R-ACCT-2）但 t_rebalance_record"
                    + " 无该关系任何记录（FR-ACCT-14 验收 2）";
        } else if (!rbs.isEmpty()) {
            it.result = RESULT_PASS;
            it.evidenceType = "t_rebalance_record";
            it.evidenceId = rbs.get(rbs.size() - 1).id;
            RebalanceRecord last = rbs.get(rbs.size() - 1);
            it.evidenceDesc = truncated(prefix + "关系 " + relation.relationNo + " 再平衡记录 "
                    + last.rebalanceNo + "（触发=" + last.triggerType + "，新文档 id="
                    + last.newHedgeDocId + "，新关系 id=" + last.newHedgeRelationId + "）");
        } else {
            it.result = RESULT_NOT_APPLICABLE;
            it.evidenceDesc = truncated(prefix + "关系 " + relation.relationNo
                    + " 未触发再平衡（关系状态=" + relation.status + "）");
        }
        return it;
    }

    /**
     * ⑤ 双侧盈亏同时呈现（R-ACCT-4）：该子账户该期间效果汇总行存在且实货栏/衍生品栏
     * <b>成对非空</b>（口径经 {@link AcctService#getEffect} 同源取数，R-ACCT-12 口径单一；
     * 未执行效果评估 ⇒ 汇总行缺失 ⇒ 「不通过」，提示先经 {@code POST /acct/effect} 计算）。
     */
    private Cas24SelfcheckItem checkDualPnl(HedgeRelation relation, String period,
                                            long operatorId, String prefix) {
        Cas24SelfcheckItem it = newItem(5, ITEM_DUAL_PNL_PRESENTED,
                "⑤双侧盈亏同时呈现（17 号文红线，R-ACCT-4）");
        Map<String, Object> view = acctService.getEffect(relation.subAccountId, period, operatorId);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) view.get("summary");
        boolean dualOk = summary != null
                && summary.containsKey("spotPnlTotal") && summary.get("spotPnlTotal") != null
                && summary.containsKey("derivativePnlTotal")
                && summary.get("derivativePnlTotal") != null;
        if (dualOk) {
            it.result = RESULT_PASS;
            it.evidenceType = "t_hedge_effect";
            it.evidenceId = ((Number) summary.get("id")).longValue();
            it.evidenceDesc = truncated(prefix + "子账户 " + relation.subAccountId + " / " + period
                    + " 效果汇总行：实货栏 spotPnlTotal=" + summary.get("spotPnlTotal")
                    + " 与衍生品栏 derivativePnlTotal=" + summary.get("derivativePnlTotal")
                    + " 成对呈现（R-ACCT-4 同屏双栏）");
        } else {
            it.result = RESULT_FAIL;
            it.failReason = "该期间效果评估未执行或汇总行缺失，双侧盈亏未呈现"
                    + "（须先经 POST /acct/effect 计算落库，R-ACCT-4 两栏不得省略）";
        }
        return it;
    }

    /** 聚合批次条目：逐关系结果聚合（任一不通过 ⇒ 聚合不通过；证据取首条命中 + 样例说明）。 */
    private List<Cas24SelfcheckItem> aggregateItems(String period, List<HedgeRelation> involved,
                                                    List<List<Cas24SelfcheckItem>> perRelation) {
        String prefix = "期间 " + period + " 聚合 " + involved.size() + " 条关系（"
                + sampleRelationNos(involved) + "）：";
        List<Cas24SelfcheckItem> out = new ArrayList<>();
        for (int seq = 1; seq <= 5; seq++) {
            Cas24SelfcheckItem proto = perRelation.get(0).get(seq - 1);
            Cas24SelfcheckItem agg = new Cas24SelfcheckItem();
            agg.itemSeq = seq;
            agg.itemCode = proto.itemCode;
            agg.itemName = proto.itemName;
            int fail = 0;
            int pass = 0;
            int na = 0;
            Long evidenceId = null;
            String evidenceType = null;
            String firstEvidenceDesc = null;
            List<String> failReasons = new ArrayList<>();
            for (List<Cas24SelfcheckItem> items : perRelation) {
                Cas24SelfcheckItem it = items.get(seq - 1);
                if (RESULT_FAIL.equals(it.result)) {
                    fail++;
                    if (it.failReason != null) {
                        failReasons.add(it.failReason);
                    }
                } else if (RESULT_PASS.equals(it.result)) {
                    pass++;
                    if (evidenceId == null) {
                        evidenceId = it.evidenceId;
                        evidenceType = it.evidenceType;
                        firstEvidenceDesc = it.evidenceDesc;
                    }
                } else {
                    na++;
                }
            }
            if (fail > 0) {
                agg.result = RESULT_FAIL;
                agg.failReason = truncated(fail + "/" + involved.size() + " 条关系不通过："
                        + String.join("；", failReasons));
            } else if (pass > 0) {
                agg.result = RESULT_PASS;
                agg.evidenceType = evidenceType;
                agg.evidenceId = evidenceId;
                agg.evidenceDesc = truncated(prefix + firstEvidenceDesc);
            } else {
                agg.result = RESULT_NOT_APPLICABLE;
                agg.evidenceDesc = truncated(prefix + "全部 " + na + " 条不适用");
            }
            out.add(agg);
        }
        return out;
    }

    // ================================================================ 落库 / 取号 / 幂等

    /** 批次骨架（标注与说明恒写入，R-ACCT-10；「待外部复核」不得被移除）。 */
    private Cas24Selfcheck newBatch(String scopeType, String scopeKey, Integer subAccountId,
                                    int pass, int fail, String cannotReason) {
        Cas24Selfcheck sc = new Cas24Selfcheck();
        sc.subAccountId = subAccountId;
        sc.scopeType = scopeType;
        sc.scopeKey = scopeKey;
        sc.passCount = pass;
        sc.failCount = fail;
        sc.conclusion = cannotReason != null
                ? CONCLUSION_CANNOT_SELFCHECK
                : (fail == 0 ? CONCLUSION_ALL_PASS : CONCLUSION_HAS_FAIL);
        sc.cannotSelfcheckReason = cannotReason;
        sc.pendingExternalReview = PENDING_EXTERNAL_REVIEW;
        sc.notSubstituteNote = NOT_SUBSTITUTE_NOTE;
        sc.executorId = null;
        sc.selfcheckTime = Clocks.now();
        return sc;
    }

    /** 批次 + 条目整笔事务落库（编号冲突 ⇒ 重新取号重试整笔，最多 3 次）。 */
    private Cas24Selfcheck insertBatch(Cas24Selfcheck sc, List<Cas24SelfcheckItem> items,
                                       long operatorId) {
        sc.executorId = operatorId;
        String[] candidateNo = new String[1];
        retryOnSelfcheckNoConflict(() -> {
            candidateNo[0] = nextSelfcheckNo();
            sc.selfcheckNo = candidateNo[0];
            sc.reportFileId = "cas24-selfcheck-" + candidateNo[0] + ".csv";
            return tx.inTransaction(connection -> {
                if (selfcheckDao.insert(sc, operatorId) != 1) {
                    throw BizException.of(ErrorCode.INTERNAL_ERROR,
                            "自检批次写入失败：selfcheckNo=" + sc.selfcheckNo);
                }
                for (Cas24SelfcheckItem it : items) {
                    it.selfcheckId = sc.id;
                    if (itemDao.insert(it, operatorId) != 1) {
                        throw BizException.of(ErrorCode.INTERNAL_ERROR,
                                "自检条目写入失败：selfcheckNo=" + sc.selfcheckNo
                                        + " itemCode=" + it.itemCode);
                    }
                }
                return sc;
            });
        }, () -> candidateNo[0], no -> selfcheckDao.findBySelfcheckNo(no) != null);
        return sc;
    }

    /**
     * 自检批次号下一编号 = <b>当日前缀 {@code SC<yyyyMMdd>} 内字典序最大 + 1</b>
     * （编号含日期段 ⇒ 按业务日重置前提成立，T-137/T-141 判例形态；每次重试重新取号）。
     */
    private String nextSelfcheckNo() {
        String dayPrefix = SELFCHECK_NO_PREFIX + Clocks.today().format(YYYYMMDD);
        String max = selfcheckDao.findMaxSelfcheckNoByPrefix(dayPrefix);
        long next = 1L;
        if (max != null && max.startsWith(dayPrefix) && max.length() > dayPrefix.length()) {
            next = Long.parseLong(max.substring(dayPrefix.length())) + 1L;
        }
        StringBuilder sb = new StringBuilder(dayPrefix);
        String seq = Long.toString(next);
        for (int i = seq.length(); i < SELFCHECK_NO_SEQ_WIDTH; i++) {
            sb.append('0');
        }
        return sb.append(seq).toString();
    }

    /**
     * HEDGE_RELATION 范围键 = {@code 关系编号|期间键}（T-197 复核 O-2 收口：
     * 幂等键含期间——同日同关系换期间须各自成批；长度 32+1+7=40 ≤ scope_key 64）。
     */
    private static String relationScopeKey(String relationNo, String period) {
        return relationNo + "|" + period;
    }

    /** 幂等查找：同 {@code (scope_type, scope_key)} 且自检时间为今日的既有批次。 */
    private Cas24Selfcheck findTodayBatch(String scopeType, String scopeKey) {
        java.time.LocalDate today = Clocks.today();
        for (Cas24Selfcheck sc : selfcheckDao.findByScope(scopeType, scopeKey)) {
            if (sc.selfcheckTime != null && today.equals(sc.selfcheckTime.toLocalDate())) {
                return sc;
            }
        }
        return null;
    }

    /** 期间参与关系：调用者可见范围内、该期间存在未更正评估记录的关系（fail-closed）。 */
    private List<HedgeRelation> involvedRelations(String period, long operatorId) {
        List<Integer> visible = visibility.visibleSubAccountIds(operatorId);
        // 哨兵语义与 AcctService.unassessedRelations 同源：null/空集按空集处理（fail-closed）；
        // 含哨兵 0 = 跨账户只读 ⇒ 查全部（传 null）。
        List<Integer> subs = (visible == null || visible.isEmpty())
                ? List.of(-1)
                : (visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL) ? null : visible);
        List<HedgeRelation> out = new ArrayList<>();
        for (HedgeRelation r : hedgeRelationDao.findBySubAccounts(subs)) {
            if (r.subAccountId != null
                    && assessDao.findActiveByRelationAndPeriod(r.id, period) != null) {
                out.add(r);
            }
        }
        return out;
    }

    // ================================================================ 视图（camelCase，以 handler 消费为准）

    /**
     * 批次视图（含条目；camelCase wire 契约）。
     *
     * @param sc    批次
     * @param items 条目（可空 ⇒ 按需查）
     * @return 视图
     */
    public Map<String, Object> selfcheckView(Cas24Selfcheck sc, List<Cas24SelfcheckItem> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sc.id);
        m.put("selfcheckNo", sc.selfcheckNo);
        m.put("subAccountId", sc.subAccountId);
        m.put("scopeType", sc.scopeType);
        m.put("scopeKey", sc.scopeKey);
        m.put("passCount", sc.passCount);
        m.put("failCount", sc.failCount);
        m.put("conclusion", sc.conclusion);
        m.put("cannotSelfcheckReason", sc.cannotSelfcheckReason);
        m.put("reportFileId", sc.reportFileId);
        // R-ACCT-10：标注与说明恒随行透出（验收 3）
        m.put("pendingExternalReview", sc.pendingExternalReview);
        m.put("notSubstituteNote", sc.notSubstituteNote);
        m.put("executorId", sc.executorId);
        m.put("selfcheckTime", sc.selfcheckTime == null ? null : sc.selfcheckTime.toString());
        List<Cas24SelfcheckItem> rows = items == null
                ? itemDao.findBySelfcheck(sc.id) : items;
        List<Map<String, Object>> itemViews = new ArrayList<>();
        for (Cas24SelfcheckItem it : rows) {
            itemViews.add(itemView(it));
        }
        m.put("items", itemViews);
        return m;
    }

    /** 条目视图（逐条结论 + 证据位置，验收 1）。 */
    public static Map<String, Object> itemView(Cas24SelfcheckItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemSeq", it.itemSeq);
        m.put("itemCode", it.itemCode);
        m.put("itemName", it.itemName);
        m.put("result", it.result);
        m.put("evidenceType", it.evidenceType);
        m.put("evidenceId", it.evidenceId);
        m.put("evidenceDesc", it.evidenceDesc);
        m.put("failReason", it.failReason);
        return m;
    }

    // ================================================================ 内部工具

    private static Cas24SelfcheckItem newItem(int seq, String code, String name) {
        Cas24SelfcheckItem it = new Cas24SelfcheckItem();
        it.itemSeq = seq;
        it.itemCode = code;
        it.itemName = name;
        return it;
    }

    private static Integer requireSubOf(HedgeRelation relation) {
        if (relation.subAccountId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期关系子账户标识缺失（子账户贯穿字段规范）；复现=核对归属列");
        }
        return relation.subAccountId;
    }

    /** 期间键校验（yyyy-MM；与 AcctService 口径一致）。 */
    private static String requirePeriodKey(String raw) {
        String p = raw == null ? null : raw.trim();
        if (p == null || !p.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "periodKey 必填且须为会计期间（yyyy-MM，如 2026-10）；实际=" + raw);
        }
        return p;
    }

    /**
     * 批次号白名单校验（{@code SC<yyyyMMdd><seq4>} 形态）——导出/详情入口共用：
     * 非法字符（含引号/换行/空格等响应头注入载体）一律拒绝。
     */
    private static String requireSelfcheckNo(String raw) {
        String no = raw == null ? null : raw.trim();
        if (no == null || !no.matches("SC\\d{12}")) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "selfcheckNo 须为合法批次号（SC<yyyyMMdd><seq4>，如 SC202610130001）；实际="
                            + raw);
        }
        return no;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 证据/原因列截断（{@code evidence_desc}/{@code fail_reason} 均 500 上限）。 */
    private static String truncated(String s) {
        if (s == null || s.length() <= EVIDENCE_DESC_MAX) {
            return s;
        }
        return s.substring(0, EVIDENCE_DESC_MAX - 3) + "...";
    }

    private static String sampleRelationNos(List<HedgeRelation> relations) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relations.size() && i < AGGREGATE_SAMPLE; i++) {
            if (i > 0) {
                sb.append("/");
            }
            sb.append(relations.get(i).relationNo);
        }
        if (relations.size() > AGGREGATE_SAMPLE) {
            sb.append("等").append(relations.size()).append("条");
        }
        return sb.toString();
    }

    // ================================================================ CSV（T-193 形态：section 分段 + 逐列转义）

    private static final String BATCH_SECTION = "BATCH_INFO";
    private static final String ITEM_SECTION = "SELFCHECK_ITEM";
    private static final String[] CSV_COLUMNS = {
            "section", "selfcheck_no", "scope_type", "scope_key", "conclusion", "pass_count",
            "fail_count", "cannot_selfcheck_reason", "pending_external_review",
            "not_substitute_note", "executor_id", "selfcheck_time", "report_file_id",
            "item_seq", "item_code", "item_name", "result", "evidence_type", "evidence_id",
            "evidence_desc", "fail_reason"};

    private static String joinCsv(String[] cols) {
        return String.join(",", cols);
    }

    /** 单行渲染（列序对齐 {@link #CSV_COLUMNS}；BATCH 行只填批次段，ITEM 行另填条目段）。 */
    private static String csvRow(String section, Cas24Selfcheck sc, Cas24SelfcheckItem item) {
        String[] cells = new String[CSV_COLUMNS.length];
        cells[0] = section;
        cells[1] = sc.selfcheckNo;
        if (BATCH_SECTION.equals(section)) {
            cells[2] = sc.scopeType;
            cells[3] = sc.scopeKey;
            cells[4] = sc.conclusion;
            cells[5] = sc.passCount == null ? "" : String.valueOf(sc.passCount);
            cells[6] = sc.failCount == null ? "" : String.valueOf(sc.failCount);
            cells[7] = sc.cannotSelfcheckReason;
            cells[8] = sc.pendingExternalReview;
            cells[9] = sc.notSubstituteNote;
            cells[10] = sc.executorId == null ? "" : String.valueOf(sc.executorId);
            cells[11] = sc.selfcheckTime == null ? "" : sc.selfcheckTime.toString();
            cells[12] = sc.reportFileId;
        } else {
            cells[13] = item.itemSeq == null ? "" : String.valueOf(item.itemSeq);
            cells[14] = item.itemCode;
            cells[15] = item.itemName;
            cells[16] = item.result;
            cells[17] = item.evidenceType;
            cells[18] = item.evidenceId == null ? "" : String.valueOf(item.evidenceId);
            cells[19] = item.evidenceDesc;
            cells[20] = item.failReason;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvEscape(cells[i]));
        }
        return sb.toString();
    }

    /** CSV 字段转义（对齐 EvidenceExportService 先例；覆盖逗号/引号/换行）。 */
    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("\"", "\"\"");
        boolean quoted = text.contains(",") || text.contains("\"")
                || text.contains("\n") || text.contains("\r");
        return quoted ? "\"" + text + "\"" : text;
    }

    // ================================================================ 编号冲突重试（AcctService 同构）

    /**
     * 以「唯一约束冲突 ⇒ 重新取号并重试」执行批次号分配；仅当候选号确实被占用时重试，
     * 其他唯一约束冲突原样抛出，不误报为「编号冲突」。
     */
    private Cas24Selfcheck retryOnSelfcheckNoConflict(Supplier<Cas24Selfcheck> attempt,
                                                      Supplier<String> candidateNo,
                                                      Predicate<String> occupied) {
        Object[] out = new Object[1];
        BizNoGenerator.retryOnUniqueConflict(RETRY_MAX_ATTEMPTS, () -> {
            try {
                out[0] = attempt.get();
                return true;
            } catch (IllegalStateException e) {
                java.sql.SQLException unique = AcctService.uniqueViolationCause(e);
                if (unique == null) {
                    throw e;
                }
                String no = candidateNo.get();
                if (no == null || !occupied.test(no)) {
                    throw new IllegalStateException(
                            "唯一约束冲突非自检批次号（不重试）；复现=核对该事务其余唯一约束", e);
                }
                throw unique;
            }
        });
        return (Cas24Selfcheck) out[0];
    }
}
