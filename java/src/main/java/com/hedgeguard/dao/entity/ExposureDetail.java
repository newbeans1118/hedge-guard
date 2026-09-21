package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-SPOT-9 t_exposure_detail} 敞口构成明细（敞口可追溯到原始单据的物理载体）（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class ExposureDetail extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户归属（冗余，支持不 join 直接按子账户聚合）。 */
    public Integer subAccountId;

    /** 所属敞口。 */
    public Long exposureId;

    /** 该明细归属的敞口分量（Q-17）：CONTRACT_UNEXECUTED（已签合同未执行量）/ INVENTORY（库存持有量）/ PRODUCTION_PLAN（生产计划需求量）；SUM(按分量分组) = t_exposure 对应分量列，这是"敞口构成可自证 + 分量与合计可互校"的结构保证。 */
    public String componentType;

    /** PURCHASE_CONTRACT / SALES_CONTRACT / PRODUCTION_PLAN / WIP_RECORD / INVENTORY_LEDGER；与 component_type 的映射：PURCHASE_CONTRACT/SALES_CONTRACT → CONTRACT_UNEXECUTED；INVENTORY_LEDGER → INVENTORY；PRODUCTION_PLAN/WIP_RECORD → PRODUCTION_PLAN。 */
    public String sourceType;

    /** 来源单据 ID（多态外键，按 source_type 解释）。 */
    public Long sourceId;

    /** 来源单据编号（冗余，便于导出与追溯展示）。 */
    public String sourceNo;

    /** 该原始单据对敞口的贡献数量；SUM(contributed_qty) = t_exposure.exposure_qty。 */
    public BigDecimal contributedQty;

    /** 单位。 */
    public String unit;

    /** 该贡献的方向（与敞口头一致）。 */
    public String direction;

    /** 贡献计算所用的基准日。 */
    public LocalDate calcAsOfDate;
}
