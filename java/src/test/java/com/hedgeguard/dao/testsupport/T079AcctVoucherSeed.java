package com.hedgeguard.dao.testsupport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code T-079} 凭证/期间服务单测的用例级播种件（{@code T077AcctSeed} 同法——SQL 集中于
 * {@code dao} 命名空间，E5 纪律；仅测试夹具，不改变任何 {@code src/main} 行为）。
 *
 * <p><b>范围</b>：{@code t_voucher}/{@code t_voucher_line}/{@code t_voucher_review}/
 * {@code t_accounting_period}/{@code t_period_close_log}/{@code t_settle_cycle}/
 * {@code t_account_subject} 的最小行集（父表由 {@code T077AcctSeed} 建）。
 * 日期/时间绑定对象形态遵从引擎陷阱（DATE=LocalDate、TIMESTAMP=LocalDateTime）。
 */
public final class T079AcctVoucherSeed {

    private T079AcctVoucherSeed() {
    }

    /** 科目字典行（按场景；{@code t_account_subject} 全列最小集）。 */
    public static void accountSubject(InMemoryDmEngine engine, long id, String code, String name,
                                      String direction, String bizScene, String template,
                                      String status) {
        T077AcctSeed.exec(engine,
                "INSERT INTO t_account_subject (id, subject_code, subject_name, parent_code,"
                        + " subject_level, direction, biz_scene, is_template_default,"
                        + " editable_by_enterprise, status, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Y', ?, ?)",
                id, code, name, null, 1, direction, bizScene, template, status, 0L);
    }

    /** 开放会计期间（全局；{@code t_accounting_period} 最小集；rv=1 生产 DAO 落库形态）。 */
    public static void openPeriod(InMemoryDmEngine engine, long id, String periodKey,
                                  String type, LocalDate start, LocalDate end) {
        T077AcctSeed.exec(engine,
                "INSERT INTO t_accounting_period (id, period_key, period_type, period_start,"
                        + " period_end, status, created_by, row_version)"
                        + " VALUES (?, ?, ?, ?, ?, 'OPEN', ?, 1)",
                id, periodKey, type, start, end, 0L);
    }

    /** 用例级小步变更：期间置为已关账（关账门禁负例构造；引擎 UPDATE 仅支持 ? 绑定右侧）。 */
    public static void closePeriod(InMemoryDmEngine engine, long id) {
        T077AcctSeed.exec(engine, "UPDATE t_accounting_period SET status = ? WHERE id = ?",
                "CLOSED", id);
    }

    /** 结算单（{@code t_settle_cycle} 最小集；CONFIRMED = 已完成）。 */
    public static void settleCycle(InMemoryDmEngine engine, long id, int subAccountId,
                                   String settleNo, LocalDate tradeDate, String status) {
        T077AcctSeed.exec(engine,
                "INSERT INTO t_settle_cycle (id, sub_account_id, settle_no, trade_date,"
                        + " is_aggregate, status, close_pnl, float_pnl, commission, margin_occupied,"
                        + " available_fund, equity, negative_fund_flag, confirmed_by, confirmed_time,"
                        + " voided_flag, created_by)"
                        + " VALUES (?, ?, ?, ?, 'N', ?, 0, 0, 0, 0, 0, 0, 'N', 200, ?, 'N', ?)",
                id, subAccountId, settleNo, tradeDate, status,
                LocalDateTime.of(2026, 10, 1, 18, 0), 100L);
    }

    /** 旧编号凭证（取号负例载体：跨业务日的旧编号，验证全系统前缀 max+1 非当日重置）。 */
    public static void legacyVoucher(InMemoryDmEngine engine, long id, int subAccountId,
                                     String voucherNo, String periodKey, String status,
                                     long generatedBy) {
        T077AcctSeed.exec(engine,
                "INSERT INTO t_voucher (id, sub_account_id, voucher_no, period_key, voucher_date,"
                        + " voucher_type, status, total_amount, generated_by, generated_time,"
                        + " pending_external_review, created_by, row_version)"
                        + " VALUES (?, ?, ?, ?, ?, 'CLOSE_PNL', ?, ?, ?, ?, 'Y', ?, 1)",
                id, subAccountId, voucherNo, periodKey, LocalDate.of(2026, 9, 1), status,
                new BigDecimal("100.0000"), generatedBy,
                LocalDateTime.of(2026, 9, 1, 9, 0), generatedBy);
    }

    /** 用例级小步变更：凭证状态改写（关账后不可修改等负例构造）。 */
    public static void setVoucherStatus(InMemoryDmEngine engine, long id, String status) {
        T077AcctSeed.exec(engine, "UPDATE t_voucher SET status = ? WHERE id = ?", status, id);
    }
}
