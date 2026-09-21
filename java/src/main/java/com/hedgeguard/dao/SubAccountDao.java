package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.SubAccount;

import java.util.List;

/**
 * {@code TB-CONFIG-1 t_sub_account} 数据访问（{@code DATA_MODEL.md} §2.1）。
 *
 * <p>无 {@code sub_account_id} 列（本表<b>自身即</b>子账户维度），故不涉及子账户过滤注入。
 */
public interface SubAccountDao extends BaseDao<SubAccount, Integer> {

    /**
     * 按编码查子账户。
     *
     * @param code 子账户编码（全系统唯一且不可修改）
     * @return 子账户；不存在返回 {@code null}
     */
    SubAccount findByCode(String code);

    /**
     * 取全部<b>启用</b>子账户（加总对账取全集、下拉框）。
     *
     * <p>不返回保留系统行 {@code id = 0}（其 {@code status = 'DISABLED'}，见 {@code §2.1} 备注）。
     *
     * @return 启用子账户列表
     */
    List<SubAccount> findEnabled();
}
