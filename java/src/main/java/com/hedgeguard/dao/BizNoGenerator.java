package com.hedgeguard.dao;

import com.hedgeguard.common.Clocks;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 业务编号生成（{@code DATA_MODEL.md} §3.5「由应用层生成，不依赖数据库序列」）。
 *
 * <p>为何不用数据库序列：{@code PROJECT.md} §5.2 要求数据库可更换，编号若依赖 DM 的
 * {@code SEQUENCE}，换库时编号逻辑全量受牵连。故一律应用层生成，并由表上的 {@code UNIQUE}
 * 约束兜底 —— 并发冲突时<b>重试最多 3 次</b>（不使用 {@code SELECT MAX(...) FOR UPDATE}，
 * 避免热点表锁竞争，见 {@code §3.5}）。
 *
 * <p>本类只做<b>格式</b>生成（纯函数，便于单测）；序号如何取得（"当日最大序号 + 1"）
 * 由具体 DAO 在同一事务内完成，冲突重试入口见 {@link #retryOnUniqueConflict}。
 */
public final class BizNoGenerator {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

    private BizNoGenerator() {
    }

    /**
     * 套保计划编号：{@code HP<yyyyMMdd><seq(4)>}。
     *
     * @param bizDate 业务日期
     * @param seq     当日序号
     * @return 编号
     */
    public static String hedgePlan(LocalDate bizDate, long seq) {
        return "HP" + bizDate.format(YYYYMMDD) + pad(seq, 4, "套保计划编号");
    }

    /**
     * 套保计划编号的<b>日期段前缀</b>（{@code HP<yyyyMMdd>}）——「当日最大序号 + 1」的取数键
     * （{@code DATA_MODEL.md} §3.5）。
     *
     * <p>{@code t_hedge_plan} 无独立业务日列，业务日仅隐含于 {@code plan_no} 前缀；本方法提供
     * 唯一的前缀拼装入口，供 DAO 以前缀区间取当日最大编号（禁止调用方各自拼串）。
     *
     * @param bizDate 业务日期
     * @return 前缀（如 {@code HP20260920}）
     */
    public static String hedgePlanDayPrefix(LocalDate bizDate) {
        if (bizDate == null) {
            throw new IllegalArgumentException("业务日期不得为空；复现=检查取号调用点");
        }
        return "HP" + bizDate.format(YYYYMMDD);
    }

    /**
     * 套保计划「当日下一个序号」= <b>当日最大序号 + 1</b>（{@code DATA_MODEL.md} §3.5）。
     *
     * <p><b>按业务日重置</b>：当日无既有编号（{@code existingMaxPlanNo == null} 或前缀非当日）
     * ⇒ 返回 {@code 1}；否则取当日最大编号末段 + 1。序号零填充定长 ⇒ 字典序最大即序号最大。
     *
     * @param bizDate           业务日期
     * @param existingMaxPlanNo 当日字典序最大的既有编号（可为 {@code null}）
     * @return 当日下一个序号（从 1 起）
     */
    public static long hedgePlanNextSeq(LocalDate bizDate, String existingMaxPlanNo) {
        String prefix = hedgePlanDayPrefix(bizDate);
        if (existingMaxPlanNo == null || !existingMaxPlanNo.startsWith(prefix)) {
            return 1L;
        }
        String seqText = existingMaxPlanNo.substring(prefix.length());
        try {
            return Long.parseLong(seqText) + 1L;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("套保计划编号当日序号段非数字：" + existingMaxPlanNo
                    + "；复现=检查 plan_no 形态（HP<yyyyMMdd><seq(4)>，DATA_MODEL.md §3.5）", e);
        }
    }

    /**
     * 套期关系文件编号：{@code HD<计划编号>-V<版本>}。
     *
     * @param planNo  计划编号
     * @param version 版本号（从 1 起）
     * @return 编号
     */
    public static String hedgeDoc(String planNo, int version) {
        requireText(planNo, "计划编号");
        if (version < 1) {
            throw new IllegalArgumentException("套期关系文件版本须 >= 1：值=" + version
                    + "；复现=传入递增的版本号");
        }
        return "HD" + planNo + "-V" + version;
    }

    /**
     * 交易计划编号：{@code TP<yyyyMMdd><seq(5)>}。
     *
     * @param bizDate 业务日期
     * @param seq     当日序号
     * @return 编号
     */
    public static String tradePlan(LocalDate bizDate, long seq) {
        return "TP" + bizDate.format(YYYYMMDD) + pad(seq, 5, "交易计划编号");
    }

    /**
     * 交易指令引用（幂等键，协议字段 {@code client_order_ref}）：{@code HG<yyyyMMdd><seq(6)>}。
     *
     * @param bizDate 业务日期
     * @param seq     当日序号
     * @return 编号
     */
    public static String orderRef(LocalDate bizDate, long seq) {
        return "HG" + bizDate.format(YYYYMMDD) + pad(seq, 6, "交易指令引用");
    }

    /**
     * 结算单号：{@code ST<yyyyMMdd>-<子账户编码>}。
     *
     * @param bizDate        业务日期
     * @param subAccountCode 子账户编码
     * @return 编号
     */
    public static String settleCycle(LocalDate bizDate, String subAccountCode) {
        requireText(subAccountCode, "子账户编码");
        return "ST" + bizDate.format(YYYYMMDD) + "-" + subAccountCode;
    }

    /**
     * 资金凭证编号：{@code FV<yyyyMMdd><seq(5)>}。
     *
     * @param bizDate 业务日期
     * @param seq     当日序号
     * @return 编号
     */
    public static String fundVoucher(LocalDate bizDate, long seq) {
        return "FV" + bizDate.format(YYYYMMDD) + pad(seq, 5, "资金凭证编号");
    }

    /**
     * 会计凭证编号：{@code AV<期间(yyyyMM)><seq(5)>}。
     *
     * @param period 会计期间
     * @param seq    期间内序号
     * @return 编号
     */
    public static String acctVoucher(YearMonth period, long seq) {
        return "AV" + period.format(YYYYMM) + pad(seq, 5, "会计凭证编号");
    }

    /**
     * 划拨单号：{@code TF<yyyyMMdd><seq(4)>}。
     *
     * @param bizDate 业务日期
     * @param seq     当日序号
     * @return 编号
     */
    public static String transferApply(LocalDate bizDate, long seq) {
        return "TF" + bizDate.format(YYYYMMDD) + pad(seq, 4, "划拨单号");
    }

    /**
     * 实货敞口编号：{@code EXP<基准日><子账户ID(2)><品种ID(4)><方向序号>}。
     *
     * @param bizDate       基准日
     * @param subAccountId  子账户 ID
     * @param productId     品种 ID
     * @param direction     方向（如 {@code B} 买入 / {@code S} 卖出）
     * @param seq           当日序号（2 位）
     * @return 编号（如 {@code EXP20260920-01-0007-B-01}）
     */
    public static String exposure(LocalDate bizDate, int subAccountId, long productId, String direction,
                                  long seq) {
        requireText(direction, "敞口方向");
        if (subAccountId <= 0) {
            throw new IllegalArgumentException("敞口编号要求子账户 ID > 0（行级归属列不得用哨兵值）：值="
                    + subAccountId + "；复现=确认敞口归属子账户");
        }
        return "EXP" + bizDate.format(YYYYMMDD)
                + "-" + pad(subAccountId, 2, "子账户 ID")
                + "-" + pad(productId, 4, "品种 ID")
                + "-" + direction.toUpperCase(Locale.ROOT)
                + "-" + pad(seq, 2, "敞口序号");
    }

    /**
     * 筛查 / 报表 / 导出批次号：{@code <前缀><yyyyMMdd><seq(4)>}。
     *
     * <p>示例取自 {@code DATA_MODEL.md} §3.5：{@code VS202609200001}（4 位序号）。
     *
     * @param prefix  前缀（如 {@code VS}）
     * @param bizDate 业务日期
     * @param seq     当日序号
     * @return 编号
     */
    public static String batch(String prefix, LocalDate bizDate, long seq) {
        requireText(prefix, "批次前缀");
        return prefix + bizDate.format(YYYYMMDD) + pad(seq, 4, "批次序号");
    }

    /**
     * 唯一约束冲突重试（{@code §3.5}：最多 3 次）。
     *
     * <p>判据：{@code SQLException} 的 {@code SQLState} 以 {@code 23} 开头（约束违背）。
     * <b>不</b>重试其他异常 —— 避免把系统性故障重试成"偶发失败"。
     *
     * @param maxAttempts 最大尝试次数（须 &gt;= 1，建议 3）
     * @param attempt     单次尝试；返回 {@code true} 表示成功（无唯一冲突）
     * @return 是否在允许次数内成功
     */
    public static boolean retryOnUniqueConflict(int maxAttempts, Attempt attempt) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("重试次数须 >= 1：值=" + maxAttempts
                    + "；复现=按 §3.5 传入 3");
        }
        RuntimeException last = null;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                if (attempt.run()) {
                    return true;
                }
            } catch (SQLException e) {
                if (!isUniqueViolation(e)) {
                    throw new IllegalStateException(
                            "编号生成失败（非唯一冲突）：attempt=" + i + "/" + maxAttempts
                                    + "；SQLState=" + e.getSQLState() + "；复现=检查表结构与连接", e);
                }
                last = new IllegalStateException(
                        "编号唯一冲突，已重试 " + i + " 次：" + e.getMessage(), e);
            }
        }
        throw new IllegalStateException(
                "编号生成在 " + maxAttempts + " 次尝试内仍未成功（唯一约束冲突）；"
                        + "复现=确认当日序号取数与 UNIQUE 约束（DATA_MODEL.md §3.5）", last);
    }

    /**
     * 是否为唯一约束冲突。
     *
     * @param e 异常
     * @return 冲突返回 {@code true}
     */
    public static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        if (state == null) {
            return false;
        }
        // 标准 SQLState 23xxx 为完整性约束违背；DM 与主流库均遵循此前缀（DM-SPECIFIC: 若实机返回
        // 非 23 前缀的专有码，须在 dm 方言层补充判定并带 // DM-SPECIFIC: 注释）
        return state.startsWith("23");
    }

    private static String pad(long value, int width, String what) {
        String s = Long.toString(value);
        if (s.length() > width) {
            throw new IllegalArgumentException(what + "序号超出 " + width + " 位：值=" + value
                    + "；复现=检查当日序号是否溢出（DATA_MODEL.md §3.5 位数约定）");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(what + "不得为空；复现=检查调用点入参");
        }
    }

    /** 单次编号分配尝试。 */
    @FunctionalInterface
    public interface Attempt {

        /**
         * 执行一次尝试。
         *
         * @return 成功返回 {@code true}
         * @throws SQLException 数据库异常
         */
        boolean run() throws SQLException;
    }

    /**
     * 当前业务日期（取可注入时钟，保证测试可复现）。
     *
     * @return 业务日期
     */
    public static LocalDate today() {
        return Clocks.today();
    }
}
