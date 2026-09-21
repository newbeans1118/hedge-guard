package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-PRODUCT-3 t_product_contract} 品种-期货/期权合约关联（期权与期货共用同一套保额度）（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class ProductContract extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 品种。 */
    public Long productId;

    /** 合约代码（如 cu2612、cu2612C70000）。 */
    public String instrumentId;

    /** FUTURES / OPTION（必须显式标注）。 */
    public String instrumentType;

    /** 交易所（SHFE / DCE / CZCE / CFFEX / INE / GFEX）。 */
    public String exchangeCode;

    /** 合约乘数（每手对应的标的数量）。 */
    public BigDecimal contractMultiplier;

    /** 合约到期日（期限红线校验输入）。 */
    public LocalDate expiryDate;

    /** 期权标的期货合约（instrument_type='OPTION' 时必填）。 */
    public String underlyingInstrumentId;

    /** 行权价（期权时必填）。 */
    public BigDecimal strikePrice;

    /** CALL / PUT（期权时必填）。 */
    public String optionType;

    /** ENABLED / DISABLED。 */
    public String status;

    /** 必须恒为 'Y'。 */
    public String isDomesticExchange;
}
