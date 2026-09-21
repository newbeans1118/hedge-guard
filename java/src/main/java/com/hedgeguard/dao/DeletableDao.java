package com.hedgeguard.dao;

/**
 * 允许<b>物理删除</b>的表的 DAO 附加接口。
 *
 * <p>存在意义：{@code DATA_MODEL.md} §0.3 规定"本系统禁止物理删除业务记录"，
 * 仅对少数表开放物理删除例外（{@code t_login_session}、未被引用的 {@code t_dict_item} 项、
 * 草稿态单据等）。把删除能力<b>单独成接口</b>，使"误删业务数据"在类型层面不可表达
 * ——这是把数据模型纪律下沉到代码结构的手段。
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 */
public interface DeletableDao<T, ID> extends BaseDao<T, ID> {

    /**
     * 按主键物理删除。
     *
     * <p>仅限 {@code DATA_MODEL.md} 显式列出的例外表调用；其余表须以状态流转实现"删除"。
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteById(ID id);
}
