package com.hedgeguard.service;

import com.hedgeguard.dao.PageQuery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Java 侧事前红线**复算与统计**（{@code T-066}；{@code FR-RISK-4}/{@code FR-RISK-5}/{@code FR-RISK-6}）。
 *
 * <h2>定位（判定与复算分离）</h2>
 * <p><b>判定权威在 C++</b>（{@code risk_engine}，{@code ARCHITECTURE.md} §3.3 {@code R-RISK-4/5/6}；
 * 业务码 {@code 0x1007}/{@code 0x1008}/{@code 0x1009}/{@code 0x100A}，见 §4.3.2）。本服务是
 * **Java 侧对同一口径的镜像复算**：防引擎单点漏判、并为事后统计/审计提供可查询留痕
 * （{@code FR-RISK-18} 限额使用统计）。本服务**不参与下单前阻断**（该路径在 C++ 全内存，零 DB）。
 *
 * <h2>口径一致性（对拍源）</h2>
 * <ul>
 *   <li><b>规模红线</b>：{@code risk_engine.cc evalScale}。使用率 {@code = accumulated_hedge_qty / expected_spot_business_qty}；
 *       子账户与全账户**双算取严**；分类（{@code COMMODITY} 90% / {@code TRADE} 80%，可品种级覆盖）；
 *       触及即判定（{@code value/base >= ratio}，{@code fixed_math.h ratioReaches}）。</li>
 *   <li><b>时点净持仓</b>：{@code evalNetPosition}。净持仓（多空相抵，折算实物量 {@code 手数 × 合约乘数 × 换算系数}）
 *       与对应方向实货敞口比较，**严格大于**即越限（{@code >}）；减仓方向（{@code -净持仓}）不受限。</li>
 *   <li><b>期限红线</b>：{@code evalTerm}。上限 = 「建仓日 + {@code max_months}」与「实货合同到期 + {@code grace_days}」**取较早者**；
 *       剩余 ≤ {@code grace_days} 告警；超限未审批阻断；不阻断平仓与展期置换。</li>
 * </ul>
 *
 * <h2>统计落库（{@code t_limit_usage_stat}）</h2>
 * <p>复算结果经 {@link #recomputeAndPersist(LocalDate, long)} 落 {@code t_limit_usage_stat}：
 * {@code limit_type='SCALE_REDLINE'}（规模红线，含全账户哨兵 0 行）与 {@code limit_type='POSITION_LIMIT'}
 * （时点净持仓 vs 实货敞口）。<b>期限红线的统计承载缺口</b>：该表 {@code limit_type} 冻结 CHECK 域
 * （{@code PRODUCT_QUOTA/POSITION_LIMIT/SCALE_REDLINE/FUND_QUOTA/AUTH_QUOTA}）<b>无期限项</b>，
 * 故期限复算结果**经本服务返回、不落独立统计表**（诚实披露，待裁定）。
 *
 * <h2>边界（不属本类）</h2>
 * 事前引擎（C++）、阈值配置下发（{@code RiskService}，{@code T-063}）、事中镜像（{@code risk_intraday}）、
 * 事后报表（{@code PostRiskReport*}，{@code T-180}）、告警处置（{@code RiskAlertService}，{@code T-065}）
 * 均不在此重复实现；本类只做**红线复算 + 统计留痕**。
 */
public interface RiskRecomputeService {

    /** 规模红线规则编号（空间乙，{@code ARCHITECTURE.md} §3.3）。 */
    String RULE_SCALE = "R-RISK-4";
    /** 时点净持仓规则编号。 */
    String RULE_NET_POSITION = "R-RISK-5";
    /** 期限红线规则编号。 */
    String RULE_TERM = "R-RISK-6";

    /** 规模红线-商品类业务码（{@code §4.3.2}）。 */
    String CODE_SCALE_COMMODITY = "0x1007";
    /** 规模红线-贸易类业务码。 */
    String CODE_SCALE_TRADE = "0x1008";
    /** 时点净持仓业务码。 */
    String CODE_NET_POSITION = "0x1009";
    /** 期限红线业务码。 */
    String CODE_TERM_REDLINE = "0x100A";

    /** 判定：通过。 */
    String PASS = "PASS";
    /** 判定：告警（不阻断）。 */
    String WARN = "WARN";
    /** 判定：拒绝（阻断新开仓；平仓不受限）。 */
    String REJECT = "REJECT";
    /** 判定：无法判定（分母/换算缺失；**不得**视为通过，{@code DATA_MODEL.md} §2.8 TB-RISK-11 备注）。 */
    String UNKNOWN = "UNKNOWN";

    /** 全账户哨兵子账户 ID（{@code DATA_MODEL.md} §0.2）。 */
    int ALL_ACCOUNT_SENTINEL = 0;

    /** 生效分类-商品类（90%）。 */
    String CATEGORY_COMMODITY = "COMMODITY";
    /** 生效分类-贸易类（80%）。 */
    String CATEGORY_TRADE = "TRADE";

    /** 敞口方向-买入。 */
    String DIRECTION_BUY = "BUY";
    /** 敞口方向-卖出。 */
    String DIRECTION_SELL = "SELL";

    /** 统计类型-规模红线（{@code t_limit_usage_stat.limit_type}）。 */
    String LIMIT_TYPE_SCALE_REDLINE = "SCALE_REDLINE";
    /** 统计类型-时点净持仓（以实货敞口为限额）。 */
    String LIMIT_TYPE_POSITION_LIMIT = "POSITION_LIMIT";

    /**
     * 复算规模红线（{@code FR-RISK-4}）：读 {@code t_annual_scale} 的子账户行与全账户行，双算取严。
     *
     * @param subAccountId 子账户（{@code > 0}）
     * @param productId    品种
     * @param scaleYear    年度
     * @return 复算结果（{@link ScaleResult}）
     */
    ScaleResult recomputeScale(int subAccountId, long productId, int scaleYear);

    /**
     * 复算时点净持仓（{@code FR-RISK-5}）：读 {@code t_position}（多空相抵、折算实物量）与
     * {@code t_exposure}（当日敞口快照），按方向比较。
     *
     * @param subAccountId 子账户（{@code > 0}）
     * @param productId    品种
     * @return 复算结果（{@link NetPositionResult}）
     */
    NetPositionResult recomputeNetPosition(int subAccountId, long productId);

    /**
     * 复算期限红线（{@code FR-RISK-6}）：读 {@code t_hedge_plan} 期限 + 实货合同到期，取较早者。
     *
     * @param planId 套保计划主键
     * @param today  业务日期（剩余天数基准；{@code null} 取 {@code Clocks.today()}）
     * @return 复算结果（{@link TermResult}）
     */
    TermResult recomputeTerm(long planId, LocalDate today);

    /**
     * 复算并落统计（{@code FR-RISK-4/5} 统计面）：对当日有数据的（子账户 × 品种）复算规模红线与
     * 时点净持仓，幂等落 {@code t_limit_usage_stat}；同时复算当日 APPROVED/FROZEN 计划的期限红线
     * （仅返回、不落库，见类注释）。
     *
     * @param statDate   统计日期（{@code null} 取 {@code Clocks.today()}）
     * @param operatorId 操作人（{@code created_by}）
     * @return 复算汇总（{@link RecomputeSummary}）
     */
    RecomputeSummary recomputeAndPersist(LocalDate statDate, long operatorId);

    /**
     * 查询限额使用统计（{@code FR-RISK-18} 验收 2/3；读取面按调用者可见子账户隔离）。
     *
     * @param query  过滤（日期必填）
     * @param page   分页
     * @param userId 调用者（可见性子账户过滤）
     * @return 分页统计视图
     */
    UsagePage queryUsage(UsageQuery query, PageQuery page, long userId);

    // ------------------------------------------------------------------ 结果类型

    /** 复算判定公共字段（{@code ruleId}/{@code code}/{@code verdict}/{@code current}/{@code threshold}）。 */
    class RedlineVerdict {
        /** 规则编号（空间乙 {@code R-RISK-n}）。 */
        public String ruleId;
        /** 组件业务码（越限时给出；通过为 {@code null}）。 */
        public String code;
        /** 判定：{@link RiskRecomputeService#PASS}/{@link RiskRecomputeService#WARN}/
         *  {@link RiskRecomputeService#REJECT}/{@link RiskRecomputeService#UNKNOWN}。 */
        public String verdict;
        /** 当前值（字符串化十进制，{@code NFR-USE-4}）。 */
        public String current;
        /** 阈值（字符串化十进制）。 */
        public String threshold;
        /** 说明（人可读，含口径与依据）。 */
        public String detail;
    }

    /** 规模红线复算结果（{@code FR-RISK-4}）。 */
    final class ScaleResult extends RedlineVerdict {
        /** 子账户。 */
        public int subAccountId;
        /** 品种。 */
        public long productId;
        /** 年度。 */
        public int scaleYear;
        /** 生效分类（{@code COMMODITY}/{@code TRADE}）。 */
        public String category;
        /** 适用比例（分类比例与行内 {@code scale_ratio_limit} 取更严）。 */
        public BigDecimal ratioLimit;
        /** 子账户口径使用率（行缺失为 {@code null}）。 */
        public BigDecimal subAccountUsageRatio;
        /** 全账户口径使用率（行缺失为 {@code null}）。 */
        public BigDecimal totalAccountUsageRatio;
        /** 双算取严后的使用率（两者较大者）。 */
        public BigDecimal effectiveUsageRatio;
        /** 分母缺失（{@code expected_spot_business_qty <= 0}）⇒ 无法判定。 */
        public boolean denominatorMissing;
    }

    /** 时点净持仓复算结果（{@code FR-RISK-5}）。 */
    final class NetPositionResult extends RedlineVerdict {
        /** 子账户。 */
        public int subAccountId;
        /** 品种。 */
        public long productId;
        /** 净持仓（多空相抵、折算实物量；有符号：正 = 净多）。 */
        public BigDecimal netPosition;
        /** 买入方向实货敞口（无行为 {@code null}）。 */
        public BigDecimal buyExposure;
        /** 卖出方向实货敞口（无行为 {@code null}）。 */
        public BigDecimal sellExposure;
        /** 越限方向（{@code BUY}/{@code SELL}；未越限为 {@code null}）。 */
        public String breachDirection;
        /** 所用敞口快照时点（{@code Q-3}：校验记录须标注快照时点；最晚者）。 */
        public LocalDateTime exposureSnapshotTime;
        /** 换算关系/合约乘数缺失 ⇒ 无法判定。 */
        public boolean conversionMissing;
    }

    /** 期限红线复算结果（{@code FR-RISK-6}）。 */
    final class TermResult extends RedlineVerdict {
        /** 计划主键。 */
        public long planId;
        /** 子账户。 */
        public int subAccountId;
        /** 品种。 */
        public long productId;
        /** 建仓日（起算点）。 */
        public LocalDate openDate;
        /** 计划期限止。 */
        public LocalDate termEndDate;
        /** 实货合同到期日（可空）。 */
        public LocalDate contractExpiry;
        /** 生效上限（较早者）。 */
        public LocalDate allowedEnd;
        /** 上限依据：{@code OPEN_PLUS_MONTHS} / {@code CONTRACT_PLUS_GRACE}。 */
        public String basis;
        /** 剩余天数（{@code today → allowedEnd}）。 */
        public long remainingDays;
        /** 展期是否已审批。 */
        public boolean extensionApproved;
    }

    /** 复算并落库汇总（{@link #recomputeAndPersist}）。 */
    final class RecomputeSummary {
        /** 统计日期。 */
        public LocalDate statDate;
        /** 落库的规模红线统计行数。 */
        public int scaleRows;
        /** 规模红线越限（或无法判定）组数。 */
        public int scaleBreachCount;
        /** 规模红线无法判定组数（分母缺失）。 */
        public int scaleUnjudgedCount;
        /** 落库的时点净持仓统计行数。 */
        public int positionRows;
        /** 时点净持仓越限组数。 */
        public int positionBreachCount;
        /** 复算的计划数（期限红线）。 */
        public int termChecked;
        /** 期限告警计划数。 */
        public int termWarnCount;
        /** 期限阻断计划数。 */
        public int termBlockCount;
        /** 命中明细（规模/净持仓/期限，含 ruleId/code/current/threshold）。 */
        public List<RedlineVerdict> hits;
    }

    /** 统计查询过滤。 */
    final class UsageQuery {
        /** 统计日期（必填）。 */
        public LocalDate statDate;
        /** 子账户（可空 = 全部可见）。 */
        public Integer subAccountId;
        /** 统计类型（可空 = 全部；{@code SCALE_REDLINE}/{@code POSITION_LIMIT}/…）。 */
        public String limitType;
        /** 提示级别（可空 = 全部）。 */
        public String hintLevel;
    }

    /** 统计行视图（{@code t_limit_usage_stat} 的 wire 视图）。 */
    final class UsageRow {
        /** 主键。 */
        public long id;
        /** 子账户（全账户哨兵 0）。 */
        public Integer subAccountId;
        /** 品种。 */
        public Long productId;
        /** 统计日期。 */
        public LocalDate statDate;
        /** 统计类型。 */
        public String limitType;
        /** 限额值。 */
        public String limitValue;
        /** 已使用值。 */
        public String usedValue;
        /** 使用率。 */
        public String usageRatio;
        /** 提示级别。 */
        public String hintLevel;
        /** 提示线快照。 */
        public String hintThreshold1;
        /** 警告线快照。 */
        public String hintThreshold2;
    }

    /** 统计分页结果。 */
    final class UsagePage {
        /** 当前页行。 */
        public List<UsageRow> items;
        /** 总行数。 */
        public long total;
        /** 页码。 */
        public int page;
        /** 每页条数。 */
        public int size;
    }
}
