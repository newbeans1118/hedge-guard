package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.VoucherLine;

import java.util.List;

/**
 * {@code TB-ACCT-5 t_voucher_line} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface VoucherLineDao extends BaseDao<VoucherLine, Long> {

    /**
     * 取凭证分录（{@code idx_voucher_line_voucher}；FR-ACCT-8 展示、导出 FR-ACCT-9）。
     *
     * @param voucherId 凭证
     * @return 列表（{@code line_no} 升序，{@code id} 升序收官）
     */
    List<VoucherLine> findByVoucher(long voucherId);

    /**
     * 从成交反查凭证分录（{@code idx_voucher_line_fill}；FR-AUDIT-2 反向追溯）。
     *
     * @param tradeFillId 成交记录
     * @return 列表（{@code line_no} 升序，{@code id} 升序收官）
     */
    List<VoucherLine> findByTradeFill(long tradeFillId);
}
