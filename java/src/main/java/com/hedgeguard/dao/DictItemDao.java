package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.DictItem;

import java.util.List;

/**
 * {@code TB-CONFIG-4 t_dict_item} 数据访问（{@code DATA_MODEL.md} §2.1）。
 *
 * <p>被引用的项不得删除，只能停用；删除能力仅对"未被引用"的项开放，故本接口
 * 继承 {@link DeletableDao} 但实现须在服务层校验引用后调用。
 */
public interface DictItemDao extends DeletableDao<DictItem, Long> {

    /**
     * 按类型 + 编码取字典项。
     *
     * @param dictType 字典类型
     * @param itemCode 枚举编码
     * @return 字典项；不存在返回 {@code null}
     */
    DictItem findByTypeAndCode(String dictType, String itemCode);

    /**
     * 取某类型下可用项（下拉框，按 {@code sort_no} 升序）。
     *
     * @param dictType 字典类型
     * @return 列表（{@code status = 'ENABLED'}）
     */
    List<DictItem> findEnabledByType(String dictType);
}
