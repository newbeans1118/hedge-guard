package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SPOT-7 t_spot_price} 现货价（GLOBAL 口径全账户共享；唯一外部输入渠道）（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SpotPrice extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 录入归属子账户（显式携带，INDEX.md §2.1 敞口类）。 */
    public Integer subAccountId;

    /** 品种。 */
    public Long productId;

    /** 价格日期；不得晚于当日（FR-SPOT-10 验收 2，应用层校验）。 */
    public LocalDate priceDate;

    /** 现货价；必须 > 0（ck_spot_price_positive）。 */
    public BigDecimal spotPrice;

    /** MANUAL（手工录入）/ BATCH_PASTE（批量粘贴）/ CSV_IMPORT（CSV 导入）。 */
    public String sourceType;

    /** 业务说明补充（仅允许补充，不允许修改系统自动记录项，FR-SPOT-17）。 */
    public String sourceDesc;

    /** CSV 导入文件名（source_type='CSV_IMPORT' 时非空）。 */
    public String importFileName;

    /** 录入人（系统自动记录，不可篡改）。 */
    public Long operatorId;

    /** 录入时间（系统自动记录，不可篡改）。 */
    public LocalDateTime entryTime;

    /** 是否全账户共享（GLOBAL 模式下为 'Y'）。 */
    public String isShared;

    /** 被覆盖的原值（R-SPOT-3 要求覆盖须留痕）。 */
    public BigDecimal overwrittenPrice;

    /** 是否经用户确认覆盖（R-SPOT-3：不得静默覆盖）。 */
    public String overwriteConfirmed;

    /** 价格单位（CNY/TON 等）。 */
    public String priceUnit;
}
