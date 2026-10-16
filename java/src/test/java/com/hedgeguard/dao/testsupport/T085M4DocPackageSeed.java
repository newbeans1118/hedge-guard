package com.hedgeguard.dao.testsupport;

import java.time.LocalDateTime;

/**
 * {@code T-085} M4 会计文档包 / CAS 24 自检执行用例的<b>用例级小步变更播种件</b>
 * （建在 {@code dao.testsupport} 命名空间内，E5 纪律：SQL 只允许出现在 {@code dao} 之下；
 * {@link T077AcctSeed} 同法。基础链路行复用 {@link T077AcctSeed}/{@link T080AcctEffectSeed}，
 * 本类只补两者未覆盖的最小增量）。
 *
 * <p><b>本类不属于产品代码，仅测试夹具；不改变任何 {@code src/main} 行为。</b>
 */
public final class T085M4DocPackageSeed {

    private T085M4DocPackageSeed() {
    }

    /**
     * 用例级小步变更：为计划 13（子账户 1，品种 2 沪铝，敞口 23）播种三要素齐备的当前版本书面文件
     * （id=31）。{@code T077AcctSeed.seedChains} 只播计划 11/12 的文档；计划 13（{@code
     * T080AcctEffectSeed} 引入的期权腿载体）无文档 ⇒ {@code generateHedgeRelation} 三要素门禁
     * （R-ACCT-1）必拒。本行使期权腿可走完整「关系生成 → 评估」链（FR-ACCT-11 期权同等对待）。
     *
     * <p>行形态与生产 DAO 落库同构：{@code pending_external_review='Y'}（R-ACCT-10）、
     * {@code row_version=1}（可被再平衡版本化更新）。
     */
    public static void seedPlan13Doc(InMemoryDmEngine engine) {
        T077AcctSeed.exec(engine,
                "INSERT INTO t_hedge_doc (id, sub_account_id, hedge_plan_id, doc_no, doc_version,"
                        + " is_current, elem_formal_designation, elem_effectiveness_method,"
                        + " elem_rebalance_mechanism, hedged_item_exposure_id, hedging_instrument,"
                        + " pending_external_review, generated_by, generated_time, created_by,"
                        + " row_version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                31L, 1, 13L, "HD202609010003", 1, "Y", "正式指定：敞口23×al2602",
                "定性经济关系法（期权：权利金非线性，按经济关系判定，R-ACCT-7）",
                "偏离>20%再平衡", 23L, "al2602P", "Y", 7L,
                LocalDateTime.of(2026, 9, 1, 9, 0), 7L, 1);
    }

    /**
     * 用例级负证探针：构造「标注被移除」的自检批次行（{@code pending_external_review='N'}）经
     * <b>INSERT</b> 路径写入。
     *
     * <p>预期被 {@code ck_t_cas24_selfcheck_pending_review CHECK (pending_external_review = 'Y')}
     * 拒绝——调用方以 {@code assertThrows} 断言（TC-M4-19「标注不可移除」DB 级负证）。
     * <b>口径披露</b>：等价引擎对 CHECK 的求值在 INSERT 路径（{@code M4AcctDaoMappingAndWriteTest}
     * 同口径）；UPDATE 路径 CHECK 不求值系夹具引擎局限，真实 DM8 两条路径均强制。
     */
    public static void insertSelfcheckWithMarkRemoved(InMemoryDmEngine engine) {
        T077AcctSeed.exec(engine,
                "INSERT INTO t_cas24_selfcheck (sub_account_id, selfcheck_no, scope_type,"
                        + " scope_key, pass_count, fail_count, conclusion, pending_external_review,"
                        + " not_substitute_note, executor_id, selfcheck_time, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1, "SC-T085-BADMARK", "HEDGE_RELATION", "HR-PROBE", 5, 0, "ALL_PASS",
                "N", "自检不替代外部复核", 7L,
                LocalDateTime.of(2026, 10, 13, 12, 0), 7L);
    }
}
