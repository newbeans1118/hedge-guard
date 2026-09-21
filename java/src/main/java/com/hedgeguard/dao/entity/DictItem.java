package com.hedgeguard.dao.entity;

/** {@code TB-CONFIG-4 t_dict_item} 枚举字典（{@code DATA_MODEL.md} §2.1）。被引用的项不得删除，只能停用。 */
public final class DictItem extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 字典类型（如 {@code TRADE_REASON}）。 */
    public String dictType;

    /** 枚举编码。 */
    public String itemCode;

    /** 枚举名称。 */
    public String itemName;

    /** 业务含义说明（{@code FR-CONFIG-14} 要求写清）。 */
    public String itemDesc;

    /** 排序号。 */
    public int sortNo;

    /** {@code ENABLED} / {@code DISABLED}。 */
    public String status;

    /** 是否系统内置项（内置项不可停用）。 */
    public String isSystem;
}
