package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.EvidenceExportDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TradeReasonDao;
import com.hedgeguard.dao.entity.AuditLog;
import com.hedgeguard.dao.entity.EvidenceExport;
import com.hedgeguard.dao.entity.TradeReason;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 证据链导出服务（{@code T-069}；{@code FR-AUDIT-8}、{@code R-AUDIT-2}/{@code R-AUDIT-4}、{@code ARCHITECTURE.md} §6.11）。
 *
 * <p>导出流程（顺序不可颠倒，{@code ARCHITECTURE.md} §6.11）：
 * <ol>
 *   <li><b>防篡改校验</b>：{@link AuditLogService#assertChainIntact} 未通过 ⇒ {@code 10018} <b>阻断导出</b>（{@code FR-AUDIT-10} 验收 2）；</li>
 *   <li><b>先写审计日志</b>：写失败 ⇒ {@code 10017} <b>中断本次导出</b>（{@code R-AUDIT-4}「不记录即不允许发生」）；</li>
 *   <li><b>落导出记录</b>：{@code t_evidence_export}（{@code audit_log_id} 关联先写的日志）；</li>
 *   <li><b>组装文件</b>：六段全链 + 子账户维度 + 双侧盈亏 + 交易理由；断点<b>显式标注「链条不完整」及断点清单</b>（{@code R-AUDIT-2}）。</li>
 * </ol>
 *
 * <p><b>只读其它模块（{@code R-AUDIT-6}）</b>：遍历复用冻结的 {@link AuditService}（{@code T-067}），
 * 导出<b>不改变任何业务数据</b>（{@code FR-AUDIT-8} 验收 4）——仅新增审计日志与导出记录两张审计自有表。
 *
 * <p><b>双侧盈亏数据源诚实披露</b>：实货/衍生品双侧盈亏权威源在 {@code ACCT}（{@code T-076+}，未交付）
 * ⇒ 经 {@link DualPnlProvider} 接缝承接；未装配时该段标注<b>数据缺口</b>（{@code R-AUDIT-2}：
 * 数据缺失不得误标完整），<b>不伪造数值</b>。
 */
public final class EvidenceExportService {

    /** 起点类型：实货敞口。 */
    public static final String START_EXPOSURE = "EXPOSURE";
    /** 起点类型：交易。 */
    public static final String START_TRADE = "TRADE";
    /** 起点类型：会计凭证。 */
    public static final String START_VOUCHER = "VOUCHER";

    /** 链条不完整标注（{@code R-AUDIT-2} 红线：不得静默输出为完整）。 */
    public static final String MARK_INCOMPLETE = "链条不完整";
    /** 完整标注。 */
    public static final String MARK_COMPLETE = "链条完整";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final AuditService auditService;
    private final AuditLogService auditLogService;
    private final EvidenceExportDao exportDao;
    private final TradeReasonDao tradeReasonDao;
    private final DualPnlProvider dualPnlProvider;
    private final SubAccountVisibility authService;
    private final TxTemplate tx;

    /**
     * 构造。
     *
     * @param auditService    追溯服务（{@code T-067}，冻结面；只读调用）
     * @param auditLogService 审计日志服务（校验 + 写入）
     * @param exportDao       证据链导出 DAO
     * @param tradeReasonDao  交易理由 DAO（导出含交易理由，{@code FR-AUDIT-8} 验收 1）
     * @param dualPnlProvider 双侧盈亏接缝（{@code null} ⇒ 该段标注数据缺口）
     * @param authService     可见范围取数（{@code NFR-SEC-7}）
     * @param tx              事务模板
     */
    public EvidenceExportService(AuditService auditService, AuditLogService auditLogService,
                                 EvidenceExportDao exportDao, TradeReasonDao tradeReasonDao,
                                 DualPnlProvider dualPnlProvider, SubAccountVisibility authService,
                                 TxTemplate tx) {
        this.auditService = auditService;
        this.auditLogService = auditLogService;
        this.exportDao = exportDao;
        this.tradeReasonDao = tradeReasonDao;
        this.dualPnlProvider = dualPnlProvider;
        this.authService = authService;
        this.tx = tx;
    }

    /**
     * 导出证据链（{@code FR-AUDIT-8}）。
     *
     * @param req        导出请求（起点类型 / 起点标识集合 / 时间范围 / 子账户范围 / 是否含原始单据明细）
     * @param operatorId 导出人
     * @return 导出结果（含文件内容、完整性标注、断点清单、审计日志关联）
     */
    public EvidenceExportResult export(EvidenceExportRequest req, long operatorId) {
        if (req == null || req.startPointIds == null || req.startPointIds.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "追溯起点集合必填（FR-AUDIT-8 输入列）");
        }
        String type = req.startPointType == null ? "" : req.startPointType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!START_EXPOSURE.equals(type) && !START_TRADE.equals(type) && !START_VOUCHER.equals(type)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "追溯起点类型须为 EXPOSURE / TRADE / VOUCHER：值=" + req.startPointType);
        }

        // ① 防篡改校验未通过 ⇒ 阻断导出（FR-AUDIT-10 验收 2）。
        auditLogService.assertChainIntact(operatorId);

        // ② 遍历追溯链（只读；复用 T-067 冻结面）。
        List<AuditService.TraceResult> traces = new ArrayList<>();
        List<String> breaks = new ArrayList<>();
        Set<String> segSeen = new LinkedHashSet<>();
        int nodeCount = 0;
        for (String id : req.startPointIds) {
            AuditService.TraceResult tr = trace(type, id, operatorId);
            traces.add(tr);
            nodeCount += tr.nodes.size();
            for (AuditService.TraceNode n : tr.nodes) {
                segSeen.add(n.segment);
            }
            for (AuditService.BreakItem b : tr.breakList) {
                breaks.add("[" + b.kind + "] 段=" + b.position + " 对象=" + b.objectType
                        + "#" + b.objectId + " 原因=" + b.reason);
            }
        }
        String integrity = mergeIntegrity(traces);

        // ③ 先写审计日志（写失败中断导出，R-AUDIT-4）。
        String exportNo = uniqueExportNo();
        String fileId = "EXPORT-" + exportNo;
        AuditLog log = new AuditLog();
        log.subAccountId = req.subAccountIds == null || req.subAccountIds.size() != 1
                ? null : req.subAccountIds.get(0);
        log.auditUserId = operatorId;
        log.actionType = AuditLogService.ACTION_EXPORT;
        log.queryCondition = "evidence type=" + type + " ids=" + req.startPointIds
                + " from=" + req.dateFrom + " to=" + req.dateTo;
        log.resultCount = nodeCount;
        log.exportFileId = fileId;
        log.chainIntegrity = integrity;
        AuditLog written;
        try {
            written = auditLogService.append(log, operatorId);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.AUDIT_LOG_WRITE_FAILED,
                    "审计日志写入失败，已中断本次导出（R-AUDIT-4）：" + e.getMessage());
        }

        // ④ 组装导出文件（六段 + 子账户 + 双侧盈亏 + 交易理由 + 断点显式标注）。
        String content = render(type, req, traces, integrity, breaks, operatorId);

        // ⑤ 落导出记录。
        EvidenceExport ex = new EvidenceExport();
        ex.exportNo = exportNo;
        ex.startPointType = type;
        ex.startPointIds = String.join(",", req.startPointIds);
        ex.dateFrom = req.dateFrom;
        ex.dateTo = req.dateTo;
        ex.subAccountScope = req.subAccountIds == null ? "ALL_VISIBLE" : req.subAccountIds.toString();
        ex.includeSourceDetail = req.includeSourceDetail ? "Y" : "N";
        ex.chainIntegrity = integrity;
        ex.breakList = breaks.isEmpty() ? null : String.join("\n", breaks);
        ex.dualPnlIncluded = "Y";
        ex.reasonIncluded = "Y";
        ex.fileId = fileId;
        ex.filePath = null;
        ex.exportedBy = operatorId;
        ex.exportedTime = Clocks.now();
        ex.auditLogId = written.id;
        if (tx.inTransaction(connection -> exportDao.insert(ex, operatorId)) != 1) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "证据链导出记录落库失败（export_no 唯一约束）：exportNo=" + exportNo);
        }

        EvidenceExportResult out = new EvidenceExportResult();
        out.exportNo = exportNo;
        out.fileId = fileId;
        out.chainIntegrity = integrity;
        out.breakList = breaks;
        out.dualPnlIncluded = true;
        out.reasonIncluded = true;
        out.auditLogId = written.id;
        out.nodeCount = nodeCount;
        out.content = content;
        out.segments = new ArrayList<>(segSeen);
        return out;
    }

    /** 按起点类型分派追溯（{@code FR-AUDIT-1/2}）。 */
    private AuditService.TraceResult trace(String type, String id, long operatorId) {
        if (START_EXPOSURE.equals(type)) {
            Long exposureId = parseLong(id);
            if (exposureId == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "敞口标识须为主键整数：" + id);
            }
            return auditService.traceForward(exposureId, null, operatorId);
        }
        return auditService.traceBackward(type, id, null, operatorId);
    }

    /**
     * 合并多起点完整性（{@code R-AUDIT-2}：断点优先，绝不粉饰）：
     * 任一 {@code HAS_BREAK} ⇒ {@code HAS_BREAK}；否则任一 {@code UNKNOWN} ⇒ {@code UNKNOWN}；
     * 否则任一 {@code IN_FLIGHT} ⇒ {@code IN_FLIGHT}；否则 {@code COMPLETE}。
     */
    private static String mergeIntegrity(List<AuditService.TraceResult> traces) {
        String out = AuditService.COMPLETE;
        for (AuditService.TraceResult tr : traces) {
            String c = tr.chainIntegrity == null ? AuditService.UNKNOWN : tr.chainIntegrity;
            if (AuditService.HAS_BREAK.equals(c)) {
                return AuditService.HAS_BREAK;
            }
            if (AuditService.UNKNOWN.equals(c)) {
                out = AuditService.UNKNOWN;
            } else if (AuditService.IN_FLIGHT.equals(c) && !AuditService.UNKNOWN.equals(out)) {
                out = AuditService.IN_FLIGHT;
            }
        }
        return out;
    }

    /** 渲染导出文件（纯文本；断点显式标注）。 */
    private String render(String type, EvidenceExportRequest req,
                          List<AuditService.TraceResult> traces, String integrity,
                          List<String> breaks, long operatorId) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 审计证据链导出（FR-AUDIT-8）\n");
        sb.append("起点类型: ").append(type).append('\n');
        sb.append("起点标识: ").append(String.join(",", req.startPointIds)).append('\n');
        sb.append("时间范围: ").append(req.dateFrom).append(" ~ ").append(req.dateTo).append('\n');
        sb.append("子账户范围: ")
                .append(req.subAccountIds == null ? "全部可见" : req.subAccountIds).append('\n');
        sb.append("生成时间: ").append(Clocks.now()).append('\n');
        sb.append("生成人: ").append(operatorId).append('\n');
        sb.append("完整性: ").append(integrity).append('\n');
        if (!AuditService.COMPLETE.equals(integrity)) {
            sb.append(">>> ").append(MARK_INCOMPLETE).append("\n");
        } else {
            sb.append(">>> ").append(MARK_COMPLETE).append("\n");
        }
        if (!breaks.isEmpty()) {
            sb.append("断点清单:\n");
            for (String b : breaks) {
                sb.append("  - ").append(b).append('\n');
            }
        }
        List<Integer> visible = authService == null ? null : authService.visibleSubAccountIds(operatorId);
        boolean cross = visible != null && visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        Map<Integer, List<TradeReason>> reasonsBySub = new LinkedHashMap<>();
        for (AuditService.TraceResult tr : traces) {
            sb.append("\n== 追溯链: ").append(tr.startType).append('/').append(tr.startId)
                    .append(" 完整性=").append(tr.chainIntegrity).append(" ==\n");
            for (AuditService.TraceNode n : tr.nodes) {
                sb.append('[').append(n.segment).append("] ").append(n.objectType)
                        .append(" id=").append(n.objectId)
                        .append(" no=").append(n.objectNo)
                        .append(" 子账户=").append(n.subAccountId)
                        .append(" 状态=").append(n.status)
                        .append(" 明细=").append(n.detail).append('\n');
                if ("t_trade_order".equals(n.objectType)) {
                    Long orderId = parseLong(n.objectId);
                    TradeReason reason = orderId == null ? null : tradeReasonDao.findByTradeOrderId(orderId);
                    if (reason != null && visible(reason.subAccountId, visible, cross)) {
                        reasonsBySub.computeIfAbsent(reason.subAccountId, k -> new ArrayList<>()).add(reason);
                    }
                }
            }
        }
        // 双侧盈亏段（FR-AUDIT-8 验收 1）。
        sb.append("\n== 双侧盈亏 ==\n");
        Set<Integer> subs = new LinkedHashSet<>();
        for (AuditService.TraceResult tr : traces) {
            for (AuditService.TraceNode n : tr.nodes) {
                if (n.subAccountId != null) {
                    subs.add(n.subAccountId);
                }
            }
        }
        if (subs.isEmpty()) {
            sb.append("（无可归属子账户）\n");
        }
        for (Integer sub : subs) {
            if (dualPnlProvider == null) {
                sb.append("子账户=").append(sub)
                        .append(" 实货盈亏=数据缺口(ACCT T-076+未交付) 衍生品盈亏=数据缺口\n");
            } else {
                DualPnl dp = dualPnlProvider.forSubAccount(sub, req.dateFrom, req.dateTo);
                if (dp == null) {
                    sb.append("子账户=").append(sub).append(" 实货盈亏=数据缺口 衍生品盈亏=数据缺口\n");
                } else {
                    sb.append("子账户=").append(sub)
                            .append(" 实货盈亏=").append(dp.physicalPnl)
                            .append(" 衍生品盈亏=").append(dp.derivativePnl)
                            .append(dp.note == null ? "" : " 注=" + dp.note).append('\n');
                }
            }
        }
        // 交易理由段（FR-AUDIT-8 验收 1）。
        sb.append("\n== 交易理由 ==\n");
        if (reasonsBySub.isEmpty()) {
            sb.append("（无交易指令或无理由记录）\n");
        } else {
            for (Map.Entry<Integer, List<TradeReason>> e : reasonsBySub.entrySet()) {
                for (TradeReason r : e.getValue()) {
                    sb.append("子账户=").append(e.getKey())
                            .append(" 指令=").append(r.tradeOrderId)
                            .append(" 类型=").append(r.relatedType)
                            .append(" 计划=").append(r.relatedPlanId)
                            .append(" 敞口=").append(r.relatedExposureId)
                            .append(" 时点=").append(r.timeDesc)
                            .append(" 说明=").append(r.reasonText)
                            .append(" 理由缺失=").append(r.reasonMissingFlag)
                            .append(" 关联缺失=").append(r.relatedObjectMissingFlag)
                            .append('\n');
                }
            }
        }
        return sb.toString();
    }

    private boolean visible(Integer sub, List<Integer> visible, boolean cross) {
        return sub == null || cross || (visible != null && visible.contains(sub));
    }

    private String uniqueExportNo() {
        LocalDateTime now = Clocks.now();
        String base = "EV" + STAMP.format(now);
        if (exportDao.findByExportNo(base) == null) {
            return base;
        }
        for (int i = 1; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (exportDao.findByExportNo(candidate) == null) {
                return candidate;
            }
        }
        throw BizException.of(ErrorCode.UNIQUE_CONFLICT, "证据链导出批次号生成失败：" + base);
    }

    private static Long parseLong(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 接缝与载体

    /** 双侧盈亏数据源接缝（{@code ACCT} 未交付；未装配时标注数据缺口）。 */
    public interface DualPnlProvider {
        /**
         * 取某子账户在区间内的双侧盈亏。
         *
         * @param subAccountId 子账户
         * @param from         区间起（可空）
         * @param to           区间止（可空）
         * @return 双侧盈亏；无数据返回 {@code null}
         */
        DualPnl forSubAccount(int subAccountId, LocalDate from, LocalDate to);
    }

    /** 双侧盈亏（实货 + 衍生品）。 */
    public static final class DualPnl {
        /** 实货盈亏。 */
        public BigDecimal physicalPnl;
        /** 衍生品盈亏。 */
        public BigDecimal derivativePnl;
        /** 说明（可空）。 */
        public String note;
    }

    /** 证据链导出请求（{@code FR-AUDIT-8} 输入列）。 */
    public static final class EvidenceExportRequest {
        /** 追溯起点类型：{@code EXPOSURE}/{@code TRADE}/{@code VOUCHER}。 */
        public String startPointType;
        /** 追溯起点标识集合。 */
        public List<String> startPointIds = new ArrayList<>();
        /** 时间范围（起）。 */
        public LocalDate dateFrom;
        /** 时间范围（止）。 */
        public LocalDate dateTo;
        /** 子账户范围。 */
        public List<Integer> subAccountIds;
        /** 是否含原始单据明细。 */
        public boolean includeSourceDetail;
    }

    /** 证据链导出结果。 */
    public static final class EvidenceExportResult {
        /** 导出批次号。 */
        public String exportNo;
        /** 导出文件标识（写入审计日志的 {@code export_file_id}）。 */
        public String fileId;
        /** 完整性：{@code COMPLETE}/{@code HAS_BREAK}/{@code IN_FLIGHT}/{@code UNKNOWN}。 */
        public String chainIntegrity;
        /** 断点清单。 */
        public List<String> breakList = new ArrayList<>();
        /** 是否含双侧盈亏（恒 {@code true}）。 */
        public boolean dualPnlIncluded;
        /** 是否含交易理由（恒 {@code true}）。 */
        public boolean reasonIncluded;
        /** 关联审计日志主键。 */
        public long auditLogId;
        /** 链节点数。 */
        public int nodeCount;
        /** 出现过的段名（六段子集）。 */
        public List<String> segments = new ArrayList<>();
        /** 导出文件内容。 */
        public String content;
    }

    // ------------------------------------------------------------ 查询

    /**
     * 查证据链导出记录（{@code FR-AUDIT-8} 验收 3：按时间查导出历史）。
     *
     * @param query 分页/排序
     * @return 分页结果
     */
    public com.hedgeguard.dao.PageResult<EvidenceExport> queryExports(
            com.hedgeguard.dao.PageQuery query) {
        return exportDao.findPage(query, null, new ArrayList<>());
    }

    /**
     * 按批次号取导出记录。
     *
     * @param exportNo 导出批次号
     * @return 记录；不存在返回 {@code null}
     */
    public EvidenceExport findByExportNo(String exportNo) {
        return exportDao.findByExportNo(exportNo);
    }
}
