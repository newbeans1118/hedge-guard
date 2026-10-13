package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.Cas24SelfcheckItem;

import java.util.List;

/**
 * {@code TB-ACCT-12 t_cas24_selfcheck_item} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>通用增删改查经 {@link BaseDao}。条目级随自检头确定子账户（§4.6.3 ③，无该列）。
 */
public interface Cas24SelfcheckItemDao extends BaseDao<Cas24SelfcheckItem, Long> {

    /**
     * 取自检批次的全部条目（{@code uk_cas24_item} 首键；FR-ACCT-14 逐条展示）。
     *
     * @param selfcheckId 自检批次
     * @return 列表（{@code item_seq} 升序，{@code id} 升序收官）
     */
    List<Cas24SelfcheckItem> findBySelfcheck(long selfcheckId);

    /**
     * 按条款代码 + 结论取条目（{@code idx_cas24_item_code_result}；
     * FR-ACCT-14 验收 2：筛某条款不通过，如"缺少再平衡记录"）。
     *
     * @param itemCode 条款代码
     * @param result   条目结论（{@code PASS} / {@code FAIL} / {@code NOT_APPLICABLE}）
     * @return 列表（{@code selfcheck_id} 升序、{@code item_seq} 升序、{@code id} 升序收官）
     */
    List<Cas24SelfcheckItem> findByCodeAndResult(String itemCode, String result);
}
