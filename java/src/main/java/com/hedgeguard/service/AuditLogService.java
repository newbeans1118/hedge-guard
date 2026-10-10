package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AuditLogChecksumDao;
import com.hedgeguard.dao.AuditLogDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.AuditLog;
import com.hedgeguard.dao.entity.AuditLogChecksum;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计日志服务（{@code T-069}；{@code FR-AUDIT-9}/{@code FR-AUDIT-10}；{@code R-AUDIT-4}）。
 *
 * <p>本服务是 {@code t_audit_log}（{@code TB-AUDIT-2}）与 {@code t_audit_log_checksum}（{@code TB-AUDIT-3}）
 * 的<b>唯一生产者与校验者</b>：
 * <ul>
 *   <li>{@link #append}：<b>只增不改</b>的链式写入 —— 分配 {@code seq_in_chain}（末行 + 1）、
 *       {@code prev_hash}（末行 {@code row_hash}；首行为 {@link #GENESIS_HASH}）、
 *       {@code row_hash = SHA-256(prev_hash ∥ 关键字段规范序)}；</li>
 *   <li>{@link #query}：审计日志查询（{@code FR-AUDIT-9}）；<b>查询本身也写入日志</b>（验收 2）；</li>
 *   <li>{@link #verify}：防篡改校验（{@code FR-AUDIT-10}）—— 逐行重算 {@code row_hash} + 逐链校验
 *       {@code prev_hash/row_hash} 衔接 + 批次校验和比对；结论含范围与时间并写入审计日志（验收 3）；</li>
 *   <li>{@link #assertChainIntact}：导出前的强制门禁（校验未通过 ⇒ {@code 10018} 阻断导出，验收 2）；</li>
 *   <li>{@link #generateChecksumBatch}：按批次落 {@code t_audit_log_checksum}（链头/链尾/批次校验和），
 *       支持增量校验（避免每次全校验全表）。</li>
 * </ul>
 *
 * <p><b>不可篡改（{@code R-AUDIT-4} / {@code FR-AUDIT-10} 验收 4）</b>：本服务<b>不提供</b>任何修改或删除
 * 历史日志的方法；底层 {@code DmAuditLogDao}/{@code DmAuditLogChecksumDao} 的 {@code updateById} 亦显式拒绝。
 *
 * <p><b>链式写入的串行性</b>：{@code seq_in_chain} 由单线程（{@code ARCHITECTURE.md} §2.4.4 {@code J7}）
 * 分配，保证链不分支（{@code DATA_MODEL.md} §2.9 TB-AUDIT-2 备注）；本服务在单个事务内完成
 * 「读末行 → 定 seq/prev_hash → 插入」，与 {@code J7} 单线程前提一致。
 *
 * <p><b>校验和口径（{@code batch_checksum = SHA-256(链式累加)}）</b>：批次首行哈希起算，
 * 逐行 {@code acc = SHA-256(acc ∥ row_hash)}，末值即为批次校验和；单行批次校验和 = 该行哈希。
 *
 * <p><b>告警端口</b>：校验未通过时经 {@link TamperAlarm} 告警（{@code FR-AUDIT-10}：「须告警」）。
 * 生产装配由运维告警 sink 承接；测试可注入记录式实现断言「未通过必告警」。
 */
public final class AuditLogService {

    // ------------------------------------------------------------ 动作类型（t_audit_log.action_type）
    /** 查询。 */
    public static final String ACTION_QUERY = "QUERY";
    /** 导出。 */
    public static final String ACTION_EXPORT = "EXPORT";
    /** 防篡改校验。 */
    public static final String ACTION_VERIFY = "VERIFY";

    /** 防篡改链的创世前驱哈希（首行 {@code prev_hash}；64 个 0）。 */
    public static final String GENESIS_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    /** 批次缺省大小（{@code ARCHITECTURE.md} §... 默认 1000 行）。 */
    public static final int DEFAULT_BATCH_SIZE = 1000;

    /** 单页读取大小（{@code PageQuery} 白名单最大值）。 */
    private static final int PAGE_SIZE = 100;

    /** 链内序号列名（排序/区间谓词）。 */
    private static final String COL_SEQ = "seq_in_chain";

    /** 规范序分隔符（\u001F，非打印）。 */
    private static final String US = "\u001F";

    private final AuditLogDao logDao;
    private final AuditLogChecksumDao checksumDao;
    private final SubAccountVisibility authService;
    private final TamperAlarm alarm;
    private final TxTemplate tx;

    /**
     * 构造。
     *
     * @param logDao      审计日志 DAO
     * @param checksumDao 审计日志校验值 DAO
     * @param authService 可见范围取数（{@code NFR-SEC-7}）
     * @param alarm       防篡改告警端口（{@code null} 视为 {@link TamperAlarm#NOOP}）
     * @param tx          事务模板
     */
    public AuditLogService(AuditLogDao logDao, AuditLogChecksumDao checksumDao,
                           SubAccountVisibility authService, TamperAlarm alarm, TxTemplate tx) {
        this.logDao = logDao;
        this.checksumDao = checksumDao;
        this.authService = authService;
        this.alarm = alarm == null ? TamperAlarm.NOOP : alarm;
        this.tx = tx;
    }

    // ------------------------------------------------------------ 链式写入

    /**
     * 追加一条审计日志（{@code FR-AUDIT-9}；只增不改）。
     *
     * <p>{@code seq_in_chain} / {@code prev_hash} / {@code row_hash} 由本方法分配与计算，调用方无需填写。
     *
     * @param entry      日志行（{@code actionType} 必填；{@code actionTime} 空则取 {@link Clocks#now()}）
     * @param operatorId 写入人（审计操作主体）
     * @return 落库后的日志行（含分配好的 seq/哈希）
     */
    public AuditLog append(AuditLog entry, long operatorId) {
        if (entry == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "审计日志行必填（FR-AUDIT-9）");
        }
        if (entry.actionType == null || entry.actionType.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "审计日志 actionType 必填（FR-AUDIT-9）");
        }
        if (entry.actionTime == null) {
            entry.actionTime = Clocks.now();
        }
        if (entry.auditUserId == 0L) {
            entry.auditUserId = operatorId;
        }
        return tx.inTransaction(connection -> {
            AuditLog tail = fetchTail();
            entry.seqInChain = tail == null ? 1L : tail.seqInChain + 1L;
            entry.prevHash = tail == null ? GENESIS_HASH : tail.rowHash;
            entry.rowHash = computeRowHash(entry);
            if (logDao.insert(entry, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "审计日志写入失败（seq_in_chain 唯一约束）：seq=" + entry.seqInChain);
            }
            return entry;
        });
    }

    // ------------------------------------------------------------ 查询

    /**
     * 查询审计日志（{@code FR-AUDIT-9}）。<b>该查询本身也写入一条 {@code QUERY} 日志</b>（验收 2）。
     *
     * @param q          筛选条件（可空 = 全部）
     * @param query      分页/排序（缺省方向由调用方显式声明）
     * @param operatorId 查询人
     * @return 分页结果
     */
    public PageResult<AuditLog> query(AuditLogQuery q, PageQuery query, long operatorId) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (q != null) {
            if (q.fromTime != null) {
                and(where).append("action_time >= ?");
                args.add(q.fromTime);
            }
            if (q.toTime != null) {
                and(where).append("action_time <= ?");
                args.add(q.toTime);
            }
            if (q.auditUserId != null) {
                and(where).append("audit_user_id = ?");
                args.add(q.auditUserId);
            }
            if (q.actionType != null && !q.actionType.trim().isEmpty()) {
                and(where).append("action_type = ?");
                args.add(q.actionType.trim());
            }
            if (q.subAccountId != null) {
                and(where).append("sub_account_id = ?");
                args.add(q.subAccountId);
            }
        }
        List<Integer> visible = authService == null ? null : authService.visibleSubAccountIds(operatorId);
        if (visible == null || !visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            SubAccountScope scope = SubAccountScope.of("sub_account_id", visible);
            and(where).append(scope.sql());
            args.addAll(scope.params());
        }
        String w = where.length() == 0 ? null : where.toString();
        PageResult<AuditLog> page = logDao.findPage(query, w, args);

        // FR-AUDIT-9 验收 2：查询审计日志的操作本身也写入日志。
        AuditLog self = new AuditLog();
        self.auditUserId = operatorId;
        self.actionType = ACTION_QUERY;
        self.queryCondition = q == null ? null : q.describe();
        self.resultCount = page.items().size();
        append(self, operatorId);
        return page;
    }

    // ------------------------------------------------------------ 防篡改校验

    /**
     * 防篡改校验（{@code FR-AUDIT-10}）：逐行重算 {@code row_hash} + 逐链校验衔接 + 批次校验和比对。
     *
     * <p>结论含校验范围、时间与执行人，并写入一条 {@code VERIFY} 审计日志（验收 3）；未通过时经
     * {@link TamperAlarm} 告警（验收 2）。本方法<b>不抛异常</b>（返回结论供调用方判定）；
     * 需要「阻断后续导出」的调用方应使用 {@link #assertChainIntact}。
     *
     * @param fromSeq    校验范围（起，含；{@code <= 0} 视为 1）
     * @param toSeq      校验范围（止，含；{@code < fromSeq} 视为全末）
     * @param operatorId 校验人
     * @return 校验结论
     */
    public VerifyResult verify(long fromSeq, long toSeq, long operatorId) {
        long from = Math.max(fromSeq, 1L);
        long max = maxSeq();
        long to = (toSeq <= 0L || toSeq > max) ? max : toSeq;
        VerifyResult r = new VerifyResult();
        r.fromSeq = from;
        r.toSeq = to;
        r.verifiedBy = operatorId;
        r.verifiedTime = Clocks.now();

        List<AuditLog> rows = fetchRange(from, to);
        r.checkedCount = rows.size();
        if (rows.isEmpty()) {
            r.passed = true;
            recordVerify(r, operatorId);
            return r;
        }

        // ① 逐行重算 row_hash + ② 链内衔接。
        for (int i = 0; i < rows.size(); i++) {
            AuditLog cur = rows.get(i);
            String expectHash = computeRowHash(cur);
            if (!expectHash.equals(cur.rowHash)) {
                r.mismatches.add("row_hash 不符：seq=" + cur.seqInChain
                        + " expected=" + expectHash + " actual=" + cur.rowHash);
            }
            if (i > 0) {
                AuditLog prev = rows.get(i - 1);
                if (!prev.rowHash.equals(cur.prevHash)) {
                    r.mismatches.add("链衔接断裂：seq=" + cur.seqInChain
                            + " prev_hash=" + cur.prevHash + " 上邻 row_hash=" + prev.rowHash);
                }
            }
        }
        // ③ 范围左边界衔接（首行 prev_hash 须等于前一行 row_hash，或创世哈希）。
        AuditLog first = rows.get(0);
        if (first.seqInChain <= 1L) {
            if (!GENESIS_HASH.equals(first.prevHash)) {
                r.mismatches.add("创世 prev_hash 不符：seq=" + first.seqInChain
                        + " prev_hash=" + first.prevHash);
            }
        } else {
            AuditLog before = logDao.findBySeqInChain(first.seqInChain - 1L);
            if (before == null || !before.rowHash.equals(first.prevHash)) {
                r.mismatches.add("左边界链衔接断裂：seq=" + first.seqInChain
                        + " prev_hash=" + first.prevHash
                        + " 上一行 row_hash=" + (before == null ? "无" : before.rowHash));
            }
        }
        // ④ 批次校验和比对（范围被完整覆盖的批次）。
        for (AuditLogChecksum batch : allBatches()) {
            if (batch.batchStartSeq >= from && batch.batchEndSeq <= to) {
                r.verifiedBatches++;
                List<AuditLog> batchRows = fetchRange(batch.batchStartSeq, batch.batchEndSeq);
                String expect = checksumOf(batchRows);
                if (!expect.equals(batch.batchChecksum)
                        || batch.recordCount != batchRows.size()) {
                    r.mismatches.add("批次校验和不符：batchNo=" + batch.batchNo
                            + " expected=" + expect + " actual=" + batch.batchChecksum);
                }
            }
        }

        r.passed = r.mismatches.isEmpty();
        if (!r.passed) {
            alarm.alert("审计日志防篡改校验未通过（FR-AUDIT-10 / R-AUDIT-4）：范围 ["
                    + r.fromSeq + "," + r.toSeq + "]，差异 " + r.mismatches.size() + " 项", r.mismatches);
        }
        recordVerify(r, operatorId);
        return r;
    }

    /**
     * 导出前强制门禁（{@code FR-AUDIT-10} 验收 2 / {@code ARCHITECTURE.md} §6.11）：校验未通过 ⇒
     * 抛 {@code 10018} 阻断导出。
     *
     * @param operatorId 操作人
     * @return 通过时的校验结论
     * @throws BizException 未通过时 {@code 10018}
     */
    public VerifyResult assertChainIntact(long operatorId) {
        VerifyResult r = verify(1L, 0L, operatorId);
        if (!r.passed) {
            throw BizException.of(ErrorCode.AUDIT_CHAIN_TAMPERED,
                    "审计日志防篡改校验未通过，已阻断导出（FR-AUDIT-10）：范围 ["
                            + r.fromSeq + "," + r.toSeq + "]，差异=" + r.mismatches);
        }
        return r;
    }

    // ------------------------------------------------------------ 批次校验值

    /**
     * 生成一个批次校验值（{@code TB-AUDIT-3}；{@code FR-AUDIT-10} 增量校验）。
     *
     * <p>批次区间 = 上一批次末端 + 1 起，取 {@code batchSize} 行（不足则取到末行）；无新增行时返回
     * {@code null}（<b>不落空批次</b>）。
     *
     * @param batchSize  批次行数（{@code <= 0} 取 {@link #DEFAULT_BATCH_SIZE}）
     * @param operatorId 操作人
     * @return 新批次；无新增行返回 {@code null}
     */
    public AuditLogChecksum generateChecksumBatch(long batchSize, long operatorId) {
        int size = batchSize <= 0L ? DEFAULT_BATCH_SIZE : (int) Math.min(batchSize, Integer.MAX_VALUE);
        return tx.inTransaction(connection -> {
            AuditLogChecksum last = lastBatch();
            long from = last == null ? 1L : last.batchEndSeq + 1L;
            long max = maxSeq();
            if (max < from) {
                return null;
            }
            long to = Math.min(max, from + size - 1L);
            List<AuditLog> rows = fetchRange(from, to);
            if (rows.isEmpty()) {
                return null;
            }
            AuditLogChecksum batch = new AuditLogChecksum();
            batch.batchNo = last == null ? 1L : last.batchNo + 1L;
            batch.batchStartSeq = from;
            batch.batchEndSeq = rows.get(rows.size() - 1).seqInChain;
            batch.batchHeadHash = rows.get(0).rowHash;
            batch.batchTailHash = rows.get(rows.size() - 1).rowHash;
            batch.batchChecksum = checksumOf(rows);
            batch.recordCount = rows.size();
            if (checksumDao.insert(batch, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "审计日志校验值落库失败（batch_no 唯一约束）：batchNo=" + batch.batchNo);
            }
            return batch;
        });
    }

    // ------------------------------------------------------------ 内部辅助

    private void recordVerify(VerifyResult r, long operatorId) {
        AuditLog log = new AuditLog();
        log.auditUserId = operatorId;
        log.actionType = ACTION_VERIFY;
        log.queryCondition = (r.passed ? "PASSED" : "FAILED")
                + " range=[" + r.fromSeq + "," + r.toSeq + "] checked=" + r.checkedCount
                + " batches=" + r.verifiedBatches
                + (r.mismatches.isEmpty() ? "" : " mismatches=" + r.mismatches);
        log.resultCount = r.checkedCount;
        append(log, operatorId);
    }

    private AuditLog fetchTail() {
        PageResult<AuditLog> page = logDao.findPage(
                PageQuery.of("1", String.valueOf(PAGE_SIZE), COL_SEQ + ",desc", COL_SEQ, "desc"),
                null, new ArrayList<>());
        List<AuditLog> items = page.items();
        return items.isEmpty() ? null : items.get(0);
    }

    private AuditLogChecksum lastBatch() {
        PageResult<AuditLogChecksum> page = checksumDao.findPage(
                PageQuery.of("1", String.valueOf(PAGE_SIZE), "batch_no,desc", "batch_no", "desc"),
                null, new ArrayList<>());
        List<AuditLogChecksum> items = page.items();
        return items.isEmpty() ? null : items.get(0);
    }

    private List<AuditLogChecksum> allBatches() {
        List<AuditLogChecksum> out = new ArrayList<>();
        int page = 1;
        while (true) {
            PageResult<AuditLogChecksum> pr = checksumDao.findPage(
                    PageQuery.of(String.valueOf(page), String.valueOf(PAGE_SIZE), "batch_no,asc",
                            "batch_no", "asc"),
                    null, new ArrayList<>());
            out.addAll(pr.items());
            if (out.size() >= pr.total() || pr.items().isEmpty()) {
                return out;
            }
            page++;
        }
    }

    private long maxSeq() {
        AuditLog tail = fetchTail();
        return tail == null ? 0L : tail.seqInChain;
    }

    /** 取 {@code [from,to]} 区间的日志行（按 seq 升序；自动翻页）。 */
    private List<AuditLog> fetchRange(long from, long to) {
        List<AuditLog> out = new ArrayList<>();
        if (to < from) {
            return out;
        }
        int page = 1;
        while (true) {
            PageResult<AuditLog> pr = logDao.findPage(
                    PageQuery.of(String.valueOf(page), String.valueOf(PAGE_SIZE), COL_SEQ + ",asc",
                            COL_SEQ, "asc"),
                    COL_SEQ + " >= ? AND " + COL_SEQ + " <= ?",
                    new ArrayList<Object>(java.util.Arrays.asList(from, to)));
            out.addAll(pr.items());
            if (pr.items().isEmpty() || out.size() >= pr.total()) {
                return out;
            }
            page++;
        }
    }

    private static StringBuilder and(StringBuilder sb) {
        if (sb.length() > 0) {
            sb.append(" AND ");
        }
        return sb;
    }

    /** 计算行哈希 = SHA-256(prev_hash ∥ 关键字段规范序)。 */
    static String computeRowHash(AuditLog e) {
        String canonical = nz(e.subAccountId) + US + e.auditUserId + US + nz(e.actionType)
                + US + nz(e.queryCondition) + US + e.resultCount + US + nz(e.exportFileId)
                + US + nz(e.chainIntegrity) + US + nz(e.actionTime) + US + e.seqInChain;
        return sha256Hex(nz(e.prevHash) + US + canonical);
    }

    /** 计算批次校验和 = SHA-256(链式累加)；空批次返回创世哈希。 */
    static String checksumOf(List<AuditLog> rows) {
        if (rows == null || rows.isEmpty()) {
            return GENESIS_HASH;
        }
        String acc = rows.get(0).rowHash;
        for (int i = 1; i < rows.size(); i++) {
            acc = sha256Hex(acc + US + rows.get(i).rowHash);
        }
        return acc;
    }

    private static String nz(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 十六进制小写 SHA-256。 */
    static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256（FR-AUDIT-10）", e);
        }
    }

    // ------------------------------------------------------------ 载体类型

    /** 防篡改告警端口（{@code FR-AUDIT-10}：「校验未通过须告警」）。 */
    @FunctionalInterface
    public interface TamperAlarm {

        /** 空告警（不告警）。 */
        TamperAlarm NOOP = (message, mismatches) -> {
        };

        /**
         * 告警。
         *
         * @param message    告警文案
         * @param mismatches 校验差异清单
         */
        void alert(String message, List<String> mismatches);
    }

    /** 审计日志查询筛选条件（{@code FR-AUDIT-9} 输入列）。 */
    public static final class AuditLogQuery {
        /** 起始时间。 */
        public LocalDateTime fromTime;
        /** 截止时间。 */
        public LocalDateTime toTime;
        /** 查询人（{@code audit_user_id}）。 */
        public Long auditUserId;
        /** 动作类型（{@code QUERY}/{@code EXPORT}/{@code VERIFY}）。 */
        public String actionType;
        /** 子账户。 */
        public Integer subAccountId;

        /** 查询条件描述（写入审计日志的 {@code query_condition}）。 */
        public String describe() {
            return "from=" + fromTime + " to=" + toTime + " user=" + auditUserId
                    + " action=" + actionType + " sub=" + subAccountId;
        }
    }

    /** 防篡改校验结论（{@code FR-AUDIT-10}）。 */
    public static final class VerifyResult {
        /** 是否通过。 */
        public boolean passed;
        /** 校验范围（起）。 */
        public long fromSeq;
        /** 校验范围（止）。 */
        public long toSeq;
        /** 校验行数。 */
        public int checkedCount;
        /** 校验批次数量。 */
        public int verifiedBatches;
        /** 差异清单。 */
        public List<String> mismatches = new ArrayList<>();
        /** 校验时间。 */
        public LocalDateTime verifiedTime;
        /** 校验人。 */
        public long verifiedBy;
    }
}
