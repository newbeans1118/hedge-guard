package com.hedgeguard.service.impl;

import com.hedgeguard.service.AuditLogService;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 防篡改告警端口 {@link AuditLogService.TamperAlarm} 的<b>生产实现</b>（{@code T-223}）。
 *
 * <p><b>事由</b>：{@code T-069} 终审条件 {@code C-1} —— {@code AuditLogService} 的生产装配此前传
 * {@code null}（即 {@link AuditLogService.TamperAlarm#NOOP}）⇒ {@code R-AUDIT-4} /
 * {@code FR-AUDIT-10}「校验未通过<b>须告警</b>」在运维面<b>未通电</b>：校验结论落库可查
 * （{@code FR-AUDIT-10} 验收 3 仍成立），但无人被通知。本类把该端口接到<b>日志级 sink</b>：
 * 每次告警记 <b>一条 {@link Level#SEVERE}</b> 记录，运维按固定前缀 {@value #PREFIX} 检索即可。
 *
 * <p><b>边界（据实披露）</b>：本实现只到「运维日志 sink」这一层 —— <b>不</b>发短信/邮件/工单，
 * <b>不</b>落库（不新增表、不改 schema/proto），<b>不</b>引入任何第三方依赖。真实告警通道
 * （与 {@code T-065}/{@code T-177} 同类 sink）交付后，再在此处或另立实现接管即可。
 *
 * <p><b>不干扰主流程</b>：{@link #alert} <b>不抛异常</b> —— 校验结论与 {@code 10018} 阻断语义由
 * {@link AuditLogService} 负责，告警失败（如 Handler 抛错）不得反向影响校验/阻断路径。
 */
public final class LoggingTamperAlarm implements AuditLogService.TamperAlarm {

    /** 告警文案固定前缀（运维检索锚点；不得改动其字面量）。 */
    public static final String PREFIX = "AUDIT_TAMPER";

    /** 单条日志内最多展开的差异项条数（防止大批次篡改把日志行撑爆）。 */
    private static final int MAX_DETAIL_ITEMS = 5;

    /** 生产缺省 logger（类名即 logger 名，运维可在 logging 配置中按名定向到告警通道）。 */
    private static final Logger DEFAULT_LOG = Logger.getLogger(LoggingTamperAlarm.class.getName());

    private final Logger log;

    /** 生产装配入口：使用本类名下 logger。 */
    public LoggingTamperAlarm() {
        this(DEFAULT_LOG);
    }

    /**
     * 指定 logger 的构造（供测试挂 {@link java.util.logging.Handler} 捕获记录）。
     *
     * @param log 目标 logger；{@code null} 视为 {@link #DEFAULT_LOG}
     */
    public LoggingTamperAlarm(Logger log) {
        this.log = log == null ? DEFAULT_LOG : log;
    }

    /**
     * 记录防篡改告警（{@code FR-AUDIT-10} / {@code R-AUDIT-4}）。
     *
     * <p>文案形如
     * {@code AUDIT_TAMPER 审计日志防篡改校验未通过（FR-AUDIT-10 / R-AUDIT-4）：&lt;原 message&gt;；差异项数=N；差异明细=[...]}。
     *
     * @param message    告警文案（原样带入，便于与 {@code AuditLogService} 结论对齐）
     * @param mismatches 校验差异清单（可为 {@code null}；仅在日志内展开前若干项）
     */
    @Override
    public void alert(String message, List<String> mismatches) {
        int count = mismatches == null ? 0 : mismatches.size();
        try {
            log.log(Level.SEVERE, PREFIX + " 审计日志防篡改校验未通过（FR-AUDIT-10 / R-AUDIT-4）："
                    + message + "；差异项数=" + count + describe(mismatches));
        } catch (RuntimeException e) {
            // 告警通道故障不得反向影响校验/阻断主流程（10018 语义归 AuditLogService）；
            // 此处不再复用同一 logger 记录，避免 Handler 再次抛错形成递归。
        }
    }

    /** 差异明细摘要：最多展开 {@value #MAX_DETAIL_ITEMS} 项，余者以「另 N 项」概括。 */
    private static String describe(List<String> mismatches) {
        if (mismatches == null || mismatches.isEmpty()) {
            return "；差异明细=[]";
        }
        StringBuilder sb = new StringBuilder("；差异明细=[");
        int shown = Math.min(mismatches.size(), MAX_DETAIL_ITEMS);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(mismatches.get(i));
        }
        if (mismatches.size() > shown) {
            sb.append(" | 另 ").append(mismatches.size() - shown).append(" 项");
        }
        return sb.append(']').toString();
    }
}
