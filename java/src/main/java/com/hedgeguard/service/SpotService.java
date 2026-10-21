package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.BaseDao;
import com.hedgeguard.dao.BizNoGenerator;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.ExposureDetailDao;
import com.hedgeguard.dao.HedgePlanExposureDao;
import com.hedgeguard.dao.InventoryLedgerDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.ProductSpotCategoryDao;
import com.hedgeguard.dao.ProductionPlanDao;
import com.hedgeguard.dao.ProductionPlanLineDao;
import com.hedgeguard.dao.PurchaseContractDao;
import com.hedgeguard.dao.SalesContractDao;
import com.hedgeguard.dao.SpotChangeLogDao;
import com.hedgeguard.dao.SpotPriceDao;
import com.hedgeguard.dao.SubAccountDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.WipRecordDao;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.ExposureDetail;
import com.hedgeguard.dao.entity.HedgePlanExposure;
import com.hedgeguard.dao.entity.InventoryLedger;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductSpotCategory;
import com.hedgeguard.dao.entity.ProductionPlan;
import com.hedgeguard.dao.entity.ProductionPlanLine;
import com.hedgeguard.dao.entity.PurchaseContract;
import com.hedgeguard.dao.entity.SalesContract;
import com.hedgeguard.dao.entity.SpotChangeLog;
import com.hedgeguard.dao.entity.SpotPrice;
import com.hedgeguard.dao.entity.SubAccount;
import com.hedgeguard.dao.entity.WipRecord;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 现货管理 + 实货敞口服务（{@code T-013} 现货管理、{@code T-014} 敞口计算）。
 *
 * <p>依据：{@code FR-SPOT-1~18}、{@code R-SPOT-1/3/4/5/9}、{@code CFG-12/12b/18}、{@code Q-17}、
 * {@code DATA_MODEL.md} §2.4（{@code TB-SPOT-1~10}）、{@code ARCHITECTURE.md} §6.10。
 *
 * <p>四条不可绕过的纪律：
 * <ol>
 *   <li><b>敞口只能由现货数据推导，方向单向</b>（{@code R-SPOT-1}）：本服务<b>没有</b>任何
 *       "由成交反推敞口"的入口；{@code t_exposure_detail} 是"敞口 → 原始单据"的唯一物理链接；</li>
 *   <li><b>三分量与合计同事务计算，{@code Σ分量 ≠ 合计} 一律拒写</b>（{@code Q-17} 写入侧）；
 *       明细贡献量之和必须等于对应分量列 —— 这是"敞口构成可自证"的结构保证；</li>
 *   <li><b>展示侧不一致只重算 + 告警 + 留痕 {@code COMPONENT_RECALC}，绝不回写落库值</b>
 *       （{@code CFG-17b}）；确需改落库值须重算生成新快照（新 {@code snapshot_id}，旧快照保留）；</li>
 *   <li><b>缺价处置按用途分流</b>（{@code CFG-12/12b} + {@code R-SPOT-4}）：
 *       <b>比率计算用途</b>缺价<b>拒绝</b>（{@code 10015}）；<b>估值/展示用途</b>可沿用最近一期价，
 *       但<b>必须</b>标注 {@code price_as_of} 与"非当日价"。</li>
 * </ol>
 */
public final class SpotService {

    /** 敞口分量取值域（{@code Q-17}；{@code DATA_MODEL.md} §3.1）。 */
    public static final Set<String> COMPONENT_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("CONTRACT_UNEXECUTED", "INVENTORY", "PRODUCTION_PLAN")));

    /** 来源单据类型取值域（{@code TB-SPOT-9}）。 */
    public static final Set<String> SOURCE_TYPES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("PURCHASE_CONTRACT", "SALES_CONTRACT", "PRODUCTION_PLAN", "WIP_RECORD",
                    "INVENTORY_LEDGER")));

    /** 现货价来源类型取值域（{@code FR-SPOT-10}：唯一外部输入渠道）。 */
    public static final Set<String> PRICE_SOURCE_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("MANUAL", "BATCH_PASTE", "CSV_IMPORT")));

    /**
     * 现货价 CSV 表头必需列（{@code FR-SPOT-12}「列：品种编码、日期、现货价」）。
     *
     * <p>导入前须校验表头<b>列完整性</b>：缺任一列即整体拒绝并<b>提示缺失列名</b>
     * （{@code FR-SPOT-12} 验收 2）。批量粘贴（{@code FR-SPOT-11}）为无表头的<b>定位列</b>
     * （顺序恒为 品种编码 / 日期 / 现货价）。
     */
    public static final List<String> CSV_HEADER = Collections.unmodifiableList(
            Arrays.asList("品种编码", "日期", "现货价"));

    /** 未映射敞口的品种**哨兵值**（{@code DC-041}：指向 {@code t_product} 保留系统行 {@code id=0}）。 */
    public static final long UNMAPPED_PRODUCT_SENTINEL = 0L;

    /**
     * 已映射行的现货品类**占位常量**（{@code CH-008} / {@code DC-041} 第 2 轮；{@code REQUIREMENTS/SPOT.md:399}）。
     *
     * <p>双态：<b>未映射行</b>填**真实品类**（各品类各占一行）；<b>已映射行</b>恒填本常量
     * ⇒ {@code uk_exposure_key} 对该类行**等价于「子账户 × 品种 × 方向 × 口径 × 基准日」**，
     * 同一品种来自不同来源品类的数据**必须合并为一条**（{@code FR-SPOT-14} 验收 1）。
     * 数据库侧由具名 {@code CHECK ck_exposure_spot_category_scope} 双向兜底。
     */
    public static final String MAPPED_CATEGORY_PLACEHOLDER = "__MAPPED__";


    /** 现货价用途（决定缺价如何处置，{@code CFG-12/12b}）。 */
    public enum PricePurpose {
        /** 套保比率计算：缺价**拒绝**（不得静默降级）。 */
        RATIO,
        /** 估值 / 展示：可沿用最近一期价，但必须标注 {@code price_as_of} 与非当日价标记。 */
        VALUATION
    }

    private final TxTemplate tx;
    private final PurchaseContractDao purchaseContractDao;
    private final SalesContractDao salesContractDao;
    private final ProductSpotCategoryDao productSpotCategoryDao;
    private final WipRecordDao wipRecordDao;
    private final InventoryLedgerDao inventoryLedgerDao;
    private final SpotPriceDao spotPriceDao;
    private final ExposureDao exposureDao;
    private final ExposureDetailDao exposureDetailDao;
    private final SpotChangeLogDao spotChangeLogDao;
    private final ProductionPlanDao productionPlanDao;
    private final ProductionPlanLineDao productionPlanLineDao;
    private final SubAccountDao subAccountDao;
    private final ProductDao productDao;
    private final SubAccountVisibility subAccountVisibility;
    private final HedgePlanExposureDao hedgePlanExposureDao;

    /**
     * 构造（{@code T-013}/{@code T-014} 既有签名，向后兼容——新增依赖为 {@code null}）。
     *
     * @param tx                       事务模板
     * @param purchaseContractDao      采购合同 DAO
     * @param salesContractDao         销售合同 DAO
     * @param productSpotCategoryDao   现货品类映射 DAO
     * @param wipRecordDao             在制品 DAO
     * @param inventoryLedgerDao       库存台账 DAO
     * @param spotPriceDao             现货价 DAO
     * @param exposureDao              敞口 DAO
     * @param exposureDetailDao        敞口构成明细 DAO
     * @param spotChangeLogDao         现货变更留痕 DAO
     */
    public SpotService(TxTemplate tx, PurchaseContractDao purchaseContractDao,
                       SalesContractDao salesContractDao,
                       ProductSpotCategoryDao productSpotCategoryDao, WipRecordDao wipRecordDao,
                       InventoryLedgerDao inventoryLedgerDao, SpotPriceDao spotPriceDao,
                       ExposureDao exposureDao, ExposureDetailDao exposureDetailDao,
                       SpotChangeLogDao spotChangeLogDao) {
        this(tx, purchaseContractDao, salesContractDao, productSpotCategoryDao, wipRecordDao,
                inventoryLedgerDao, spotPriceDao, exposureDao, exposureDetailDao, spotChangeLogDao,
                null, null, null);
    }

    /**
     * 构造（{@code T-109} 声明面补齐所需依赖；{@code T-109-R} 新增的
     * {@link ProductDao} / {@link SubAccountVisibility} / {@link HedgePlanExposureDao} 置 {@code null}，
     * 供既有单测与不涉新能力的调用方沿用）。
     *
     * @param tx                       事务模板
     * @param purchaseContractDao      采购合同 DAO
     * @param salesContractDao         销售合同 DAO
     * @param productSpotCategoryDao   现货品类映射 DAO
     * @param wipRecordDao             在制品 DAO
     * @param inventoryLedgerDao       库存台账 DAO
     * @param spotPriceDao             现货价 DAO
     * @param exposureDao              敞口 DAO
     * @param exposureDetailDao        敞口构成明细 DAO
     * @param spotChangeLogDao         现货变更留痕 DAO
     * @param productionPlanDao        生产计划头 DAO
     * @param productionPlanLineDao    生产计划行 DAO
     * @param subAccountDao            子账户 DAO（归属变更目标校验用）
     */
    public SpotService(TxTemplate tx, PurchaseContractDao purchaseContractDao,
                       SalesContractDao salesContractDao,
                       ProductSpotCategoryDao productSpotCategoryDao, WipRecordDao wipRecordDao,
                       InventoryLedgerDao inventoryLedgerDao, SpotPriceDao spotPriceDao,
                       ExposureDao exposureDao, ExposureDetailDao exposureDetailDao,
                       SpotChangeLogDao spotChangeLogDao, ProductionPlanDao productionPlanDao,
                       ProductionPlanLineDao productionPlanLineDao, SubAccountDao subAccountDao) {
        this(tx, purchaseContractDao, salesContractDao, productSpotCategoryDao, wipRecordDao,
                inventoryLedgerDao, spotPriceDao, exposureDao, exposureDetailDao, spotChangeLogDao,
                productionPlanDao, productionPlanLineDao, subAccountDao, null, null, null);
    }

    /**
     * 构造（全量依赖，{@code T-109-R} 修复 {@code TE-T109-001}/{@code TE-T109-002} 所需）。
     *
     * <p>新增三项依赖的用途：
     * <ul>
     *   <li>{@link ProductDao}：{@code FR-SPOT-11/12} 导入按<b>品种编码</b>解析为 {@code productId}
     *       （{@link ProductDao#findByCode(String)}，{@code uk_product_code} 唯一）；</li>
     *   <li>{@link SubAccountVisibility}：子账户<b>可见性/写入强制面</b>（{@code NFR-SEC-7}）——
     *       导出（只读面）经 {@link #requireSubAccountVisible}，写面经 {@link #requireSubAccountWritable}，
     *       均按调用者身份断言；</li>
     *   <li>{@link HedgePlanExposureDao}：{@code FR-SPOT-16} 验收 2 拒因按<b>计划</b>给出
     *       （{@code t_hedge_plan_exposure} 反查 {@code hedge_plan_id}）。</li>
     * </ul>
     *
     * @param tx                       事务模板
     * @param purchaseContractDao      采购合同 DAO
     * @param salesContractDao         销售合同 DAO
     * @param productSpotCategoryDao   现货品类映射 DAO
     * @param wipRecordDao             在制品 DAO
     * @param inventoryLedgerDao       库存台账 DAO
     * @param spotPriceDao             现货价 DAO
     * @param exposureDao              敞口 DAO
     * @param exposureDetailDao        敞口构成明细 DAO
     * @param spotChangeLogDao         现货变更留痕 DAO
     * @param productionPlanDao        生产计划头 DAO
     * @param productionPlanLineDao    生产计划行 DAO
     * @param subAccountDao            子账户 DAO（归属变更目标校验用）
     * @param productDao               品种 DAO（导入按编码解析）
     * @param subAccountVisibility     子账户可见性取数 / 写面断言入口
     * @param hedgePlanExposureDao     计划—敞口绑定 DAO（拒因反查计划）
     */
    public SpotService(TxTemplate tx, PurchaseContractDao purchaseContractDao,
                       SalesContractDao salesContractDao,
                       ProductSpotCategoryDao productSpotCategoryDao, WipRecordDao wipRecordDao,
                       InventoryLedgerDao inventoryLedgerDao, SpotPriceDao spotPriceDao,
                       ExposureDao exposureDao, ExposureDetailDao exposureDetailDao,
                       SpotChangeLogDao spotChangeLogDao, ProductionPlanDao productionPlanDao,
                       ProductionPlanLineDao productionPlanLineDao, SubAccountDao subAccountDao,
                       ProductDao productDao, SubAccountVisibility subAccountVisibility,
                       HedgePlanExposureDao hedgePlanExposureDao) {
        this.tx = tx;
        this.purchaseContractDao = purchaseContractDao;
        this.salesContractDao = salesContractDao;
        this.productSpotCategoryDao = productSpotCategoryDao;
        this.wipRecordDao = wipRecordDao;
        this.inventoryLedgerDao = inventoryLedgerDao;
        this.spotPriceDao = spotPriceDao;
        this.exposureDao = exposureDao;
        this.exposureDetailDao = exposureDetailDao;
        this.spotChangeLogDao = spotChangeLogDao;
        this.productionPlanDao = productionPlanDao;
        this.productionPlanLineDao = productionPlanLineDao;
        this.subAccountDao = subAccountDao;
        this.productDao = productDao;
        this.subAccountVisibility = subAccountVisibility;
        this.hedgePlanExposureDao = hedgePlanExposureDao;
    }

    // ------------------------------------------------------------ T-013 现货单证

    /**
     * 新建采购合同（草稿）。
     *
     * <p>无映射的现货品类**允许存草稿但不得提交为有效**（{@code FR-SPOT-1}）：
     * 提交（{@link #activatePurchaseContract}）时才要求已映射。
     *
     * @param contract   合同
     * @param operatorId 操作人
     * @return 新行主键
     */
    public long createPurchaseContract(PurchaseContract contract, long operatorId) {
        requireSubAccount(contract.subAccountId);
        requireSubAccountWritable(operatorId, contract.subAccountId);
        requireText(contract.contractNo, "contract_no");
        requireText(contract.spotCategoryCode, "spot_category_code");
        requirePositive(contract.totalQty, "total_qty");
        requirePeriod(contract.contractStartDate, contract.contractEndDate);
        contract.status = "DRAFT";
        contract.exposureDirection = "BUY";
        contract.executedQty = contract.executedQty == null ? BigDecimal.ZERO : contract.executedQty;
        contract.productId = mapProduct(contract.spotCategoryCode, "PURCHASE");
        return tx.inTransaction(connection -> {
            if (purchaseContractDao.insert(contract, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "同一子账户内合同编号已存在（uk_purchase_contract_no）：subAccountId="
                                + contract.subAccountId + " contractNo=" + contract.contractNo
                                + "；复现=换编号（不同子账户可重号）");
            }
            writeLog("PURCHASE_CONTRACT", contract.id, "CREATE", null, "DRAFT",
                    contract.subAccountId, operatorId, "新建采购合同（草稿）");
            return contract.id;
        });
    }

    /**
     * 新建销售合同（草稿；卖出方向敞口来源，{@code FR-SPOT-5}）。
     *
     * <p>双向敞口：{@code hasDualDirection='Y'} 时 {@code buyDirectionQty} 必填
     * （{@code ck_sales_contract_dual} 的结构约束 + 本层显式提示）。
     *
     * @param contract   销售合同
     * @param operatorId 操作人
     * @return 新行主键
     */
    public long createSalesContract(SalesContract contract, long operatorId) {
        requireSubAccount(contract.subAccountId);
        requireSubAccountWritable(operatorId, contract.subAccountId);
        requireText(contract.contractNo, "contract_no");
        requireText(contract.spotCategoryCode, "spot_category_code");
        requirePositive(contract.totalQty, "total_qty");
        requirePeriod(contract.contractStartDate, contract.contractEndDate);
        contract.hasDualDirection = contract.hasDualDirection == null ? "N" : contract.hasDualDirection;
        if ("Y".equals(contract.hasDualDirection) && contract.buyDirectionQty == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "双向敞口须显式给出买入方向数量（FR-SPOT-5 验收 3）：contractNo="
                            + contract.contractNo + "；复现=补齐 buy_direction_qty");
        }
        contract.status = "DRAFT";
        contract.exposureDirection = "SELL";
        contract.deliveredQty = contract.deliveredQty == null ? BigDecimal.ZERO : contract.deliveredQty;
        contract.productId = mapProduct(contract.spotCategoryCode, "SALES");
        return tx.inTransaction(connection -> {
            if (salesContractDao.insert(contract, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "同一子账户内合同编号已存在（uk_sales_contract_no）：subAccountId="
                                + contract.subAccountId + " contractNo=" + contract.contractNo
                                + "；复现=换编号（不同子账户可重号）");
            }
            writeLog("SALES_CONTRACT", contract.id, "CREATE", null, "DRAFT",
                    contract.subAccountId, operatorId, "新建销售合同（草稿）");
            return contract.id;
        });
    }

    /**
     * 提交采购合同为有效（{@code ACTIVE}）。
     *
     * <p>前置：现货品类**必须已完成映射**（{@code product_id` 非空且非哨兵值），
     * 否则进"未映射清单"、**不得提交为有效**（{@code FR-SPOT-1} 备注）。
     *
     * @param contractId 合同
     * @param operatorId 操作人
     */
    public void activatePurchaseContract(long contractId, long operatorId) {
        PurchaseContract contract = purchaseContractDao.findById(contractId);
        if (contract == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "采购合同不存在：id=" + contractId + "；复现=按 contract_no 查询确认 ID");
        }
        requireSubAccountWritable(operatorId, contract.subAccountId);
        if (!"DRAFT".equals(contract.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅草稿态可提交为有效：id=" + contractId + " status=" + contract.status
                            + "；复现=核对合同状态");
        }
        if (contract.productId == null || contract.productId == UNMAPPED_PRODUCT_SENTINEL) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "现货品类未映射到品种，不得提交为有效（须先在 t_product_spot_category 建立映射）："
                            + "spotCategoryCode=" + contract.spotCategoryCode
                            + "；复现=维护品种-现货品类映射后重试（FR-PRODUCT-10）");
        }
        tx.inTransaction(connection -> {
            contract.status = "ACTIVE";
            if (purchaseContractDao.updateById(contract, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "合同并发冲突：id=" + contractId + "；复现=重新读取后再提交");
            }
            writeLog("PURCHASE_CONTRACT", contractId, "UPDATE", "DRAFT", "ACTIVE",
                    contract.subAccountId, operatorId, "提交为有效");
            return null;
        });
    }

    /**
     * 作废合同（{@code R-SPOT-9}：{@code ACTIVE} 及之后只能作废，不可物理删除）。
     *
     * <p>作废前置校验（{@code FR-SPOT-3}）：该合同**不得已被任何敞口明细引用**——
     * 引用存在时返 {@code 10011}（否则会静默破坏追溯链）。
     *
     * @param contractId 合同
     * @param reason     作废原因（必填）
     * @param operatorId 操作人
     */
    public void voidPurchaseContract(long contractId, String reason, long operatorId) {
        requireText(reason, "void_reason");
        PurchaseContract contract = purchaseContractDao.findById(contractId);
        if (contract == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "采购合同不存在：id=" + contractId + "；复现=按 contract_no 查询确认 ID");
        }
        requireSubAccountWritable(operatorId, contract.subAccountId);
        if ("DRAFT".equals(contract.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "草稿态合同请用删除接口（可物理删除）；ACTIVE 及之后才走作废：id=" + contractId
                            + "；复现=按 FR-SPOT-1 处置草稿");
        }
        if ("VOID".equals(contract.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "合同已作废，无需重复操作：id=" + contractId + "；复现=查询 status 列");
        }
        List<ExposureDetail> referenced =
                exposureDetailDao.findBySource("PURCHASE_CONTRACT", contractId);
        if (!referenced.isEmpty()) {
            throw BizException.of(ErrorCode.REFERENCE_CONFLICT,
                    "该合同已被 " + referenced.size() + " 条敞口明细引用，作废会破坏追溯链：id=" + contractId
                            + "；复现=先撤销/终止引用它的套保计划并重算敞口（FR-SPOT-3 / R-PLAN-1）");
        }
        tx.inTransaction(connection -> {
            String before = contract.status;
            contract.status = "VOID";
            contract.voidReason = reason;
            contract.closedTime = Clocks.now();
            if (purchaseContractDao.updateById(contract, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "合同并发冲突：id=" + contractId + "；复现=重新读取后再提交");
            }
            writeLog("PURCHASE_CONTRACT", contractId, "VOID", before, "VOID",
                    contract.subAccountId, operatorId, reason);
            return null;
        });
    }

    /**
     * 删除草稿合同（{@code DATA_MODEL.md} §0.3 允许物理删除的例外态）。
     *
     * @param contractId 合同
     * @param operatorId 操作人
     */
    public void deleteDraftPurchaseContract(long contractId, long operatorId) {
        PurchaseContract contract = purchaseContractDao.findById(contractId);
        if (contract == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "采购合同不存在：id=" + contractId + "；复现=按 contract_no 查询确认 ID");
        }
        requireSubAccountWritable(operatorId, contract.subAccountId);
        if (!"DRAFT".equals(contract.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅草稿态可物理删除（DATA_MODEL.md §0.3 例外）：id=" + contractId + " status="
                            + contract.status + "；复现=改用作废接口（R-SPOT-9）");
        }
        tx.inTransaction(connection -> {
            writeLog("PURCHASE_CONTRACT", contractId, "UPDATE", "DRAFT", "DELETED",
                    contract.subAccountId, operatorId, "删除草稿");
            purchaseContractDao.deleteById(contractId);
            return null;
        });
    }

    /**
     * 录入库存台账（{@code FR-SPOT-9}）：负库存须二次确认，否则拒绝。
     *
     * @param ledger     库存行
     * @param operatorId 操作人
     * @return 新行主键
     */
    public long createInventory(InventoryLedger ledger, long operatorId) {
        requireSubAccount(ledger.subAccountId);
        requireSubAccountWritable(operatorId, ledger.subAccountId);
        if (ledger.productId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "库存行必须指定品种：subAccountId=" + ledger.subAccountId
                            + "；复现=补 product_id（库存不参与期现映射）");
        }
        if (ledger.quantity == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "库存数量必填；复现=补齐 quantity");
        }
        if (ledger.quantity.compareTo(BigDecimal.ZERO) < 0
                && !"Y".equals(ledger.negativeConfirmed)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "库存为负值须二次确认（negative_confirmed='Y'）：quantity=" + ledger.quantity
                            + "；复现=确认账实不符后显式确认（FR-SPOT-9 验收 2）");
        }
        requireText(ledger.inventoryType, "inventory_type");
        requireText(ledger.exposureDirection, "exposure_direction");
        if (ledger.recordDate == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "记录日期必填；复现=补齐 record_date");
        }
        return tx.inTransaction(connection -> {
            if (inventoryLedgerDao.insert(ledger, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "同子账户同品种同类型同日已有记录（uk_inventory_sub_product_type_date）："
                                + "subAccountId=" + ledger.subAccountId + " productId=" + ledger.productId
                                + " recordDate=" + ledger.recordDate + "；复现=改为变更既有行");
            }
            writeLog("INVENTORY_LEDGER", ledger.id, "CREATE", null, ledger.quantity.toPlainString(),
                    ledger.subAccountId, operatorId, "录入库存");
            return ledger.id;
        });
    }

    /**
     * 录入在制品（{@code R-SPOT-4}：敞口按 {@code wip_qty × completion_ratio} 折算）。
     *
     * @param record     在制品行
     * @param operatorId 操作人
     * @return 新行主键
     */
    public long createWip(WipRecord record, long operatorId) {
        requireSubAccount(record.subAccountId);
        requireSubAccountWritable(operatorId, record.subAccountId);
        if (record.productId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "在制品必须指定品种；复现=补 product_id");
        }
        if (record.wipQty == null || record.wipQty.compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "在制品数量必须 ≥ 0：实际=" + record.wipQty + "；复现=填写非负数量");
        }
        if (record.completionRatio == null || record.completionRatio.compareTo(BigDecimal.ZERO) < 0
                || record.completionRatio.compareTo(BigDecimal.ONE) > 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "完工比例必须在 [0,1] 内（R-SPOT-4）：实际=" + record.completionRatio
                            + "；复现=按小数填写（如 0.5 表示 50%）");
        }
        if (record.recordDate == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "记录日期必填；复现=补齐 record_date");
        }
        return tx.inTransaction(connection -> {
            if (wipRecordDao.insert(record, operatorId) != 1) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "在制品新增影响行数异常；复现=核对 t_wip_record 约束（ck_wip_completion_ratio）");
            }
            writeLog("WIP_RECORD", record.id, "CREATE", null, record.wipQty.toPlainString(),
                    record.subAccountId, operatorId, "录入在制品");
            return record.id;
        });
    }

    // ------------------------------------------------------------ T-013 现货价

    /**
     * 录入现货价（{@code FR-SPOT-10/17}；唯一口径 = {@code 品种 + 日期 + 共享标记}）。
     *
     * <p>覆盖规则（{@code R-SPOT-3}）：已存在同口径价格时**必须**显式确认覆盖
     * （{@code overwriteConfirmed=true}），否则拒绝——**不得静默覆盖**；覆盖时把原值写入
     * {@code overwritten_price}，原始录入人/录入时间保留不动。
     *
     * <p><b>不加强子账户可见性强制（{@code CH-003} / {@code R-SPOT-2} 注；{@code T-110}）</b>：
     * 现货价是全账户共享的市场数据，其 {@code subAccountId} 仅为**录入归属**（记录由谁录入），
     * **不参与业务归属判定与加总**；若按业务归属强制，会把对共享价的正常录入**误拒**。
     * 此语义由 {@code T-110} 显式锁定（勿在后续改动中"顺手"补一次可见性校验）。
     *
     * @param price              现货价行（{@code sourceType} 决定渠道）
     * @param overwriteConfirmed 是否确认覆盖
     * @param operatorId         录入人
     * @return 新行主键；覆盖时返回既有行主键
     */
    public long putSpotPrice(SpotPrice price, boolean overwriteConfirmed, long operatorId) {
        requireSubAccount(price.subAccountId);
        if (price.productId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "现货价必须关联品种（未映射品类不得直接录价）：复现=先完成映射");
        }
        if (price.spotPrice == null || price.spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "现货价必须 > 0（FR-SPOT-10 验收 3）：实际=" + price.spotPrice + "；复现=填写正数价格");
        }
        if (price.priceDate == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "价格日期必填；复现=补齐 price_date");
        }
        if (price.priceDate.isAfter(Clocks.today())) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "价格日期不得晚于当日（FR-SPOT-10 验收 2）：priceDate=" + price.priceDate
                            + " 当日=" + Clocks.today() + "；复现=改为不晚于当日的日期"
                            + "（“当日”取业务日期，非 DB CURRENT_DATE）");
        }
        if (price.sourceType == null || !PRICE_SOURCE_TYPES.contains(price.sourceType)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "现货价来源必须显式标注 " + PRICE_SOURCE_TYPES + "：实际=" + price.sourceType
                            + "；复现=按来源渠道提交（MANUAL/BATCH_PASTE/CSV_IMPORT）");
        }
        requireText(price.priceUnit, "price_unit");
        if ("CSV_IMPORT".equals(price.sourceType)) {
            requireText(price.importFileName, "import_file_name");
        }
        price.isShared = "Y";   // GLOBAL 口径单值（CFG-18；PER_SUB_ACCOUNT 已废弃）
        SpotPrice existing = spotPriceDao.findGlobalPrice(price.productId, price.priceDate);
        if (existing != null && !overwriteConfirmed) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "该品种当日已有价格，覆盖须显式确认（不得静默覆盖，R-SPOT-3）：productId="
                            + price.productId + " priceDate=" + price.priceDate + " 原值="
                            + existing.spotPrice.toPlainString()
                            + "；复现=携带覆盖确认后重试（原值将记入 overwritten_price）");
        }
        return tx.inTransaction(connection -> {
            if (existing == null) {
                price.operatorId = operatorId;
                price.entryTime = Clocks.now();
                price.overwriteConfirmed = "N";
                if (spotPriceDao.insert(price, operatorId) != 1) {
                    throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                            "现货价唯一口径冲突（uk_spot_price_global）：productId=" + price.productId
                                    + " priceDate=" + price.priceDate + "；复现=核对唯一口径");
                }
                return price.id;
            }
            existing.overwrittenPrice = existing.spotPrice;
            existing.spotPrice = price.spotPrice;
            existing.overwriteConfirmed = "Y";
            existing.sourceType = price.sourceType;
            existing.sourceDesc = price.sourceDesc;
            existing.importFileName = price.importFileName;
            existing.priceUnit = price.priceUnit;
            if (spotPriceDao.updateById(existing, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "现货价并发冲突：productId=" + price.productId + " priceDate=" + price.priceDate
                                + "；复现=重新读取后再提交");
            }
            return existing.id;
        });
    }

    /**
     * 取价并按用途处置缺口（{@code CFG-12/12b} + {@code R-SPOT-4}）。
     *
     * @param productId 品种
     * @param bizDate   业务日期
     * @param purpose   {@link PricePurpose}
     * @return 价格行（{@code VALUATION} 用途可能沿用历史价，其 {@code priceDate} 即 {@code price_as_of}）
     */
    public SpotPrice resolvePrice(long productId, LocalDate bizDate, PricePurpose purpose) {
        SpotPrice exact = spotPriceDao.findGlobalPrice(productId, bizDate);
        if (exact != null) {
            return exact;
        }
        if (purpose == PricePurpose.RATIO) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "套保比率计算用途缺价一律拒绝（不得静默降级/沿用）：productId=" + productId
                            + " 业务日期=" + bizDate + "；复现=先录入该日现货价（R-SPOT-4 / CFG-12）");
        }
        SpotPrice carried = spotPriceDao.findLatestOnOrBefore(productId, bizDate);
        if (carried == null) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "该品种无任何可用历史价，沿用亦不可得：productId=" + productId + " 业务日期=" + bizDate
                            + "；复现=先录入至少一期现货价（CFG-12b）");
        }
        return carried;
    }

    // ------------------------------------------------------------ T-014 实货敞口

    /**
     * 按主键取敞口（详情/追溯下钻用），<b>并按调用者可见子账户范围过滤</b>
     * （{@code FR-SPOT-15} 处理规则「仅返回有权限的子账户范围」/ 验收 3 / {@code NFR-SEC-7}）。
     *
     * <p>可见集来自<b>会话身份</b>（{@link #visibleSubAccountIds}），不由请求参数决定：
     * 含哨兵 {@link SubAccountScope#ALL_ACCOUNT_SENTINEL}（跨账户只读）⇒ 不构造 scope（不加过滤，只读面放行）；
     * 否则经 {@link ExposureDao#findByIdScoped} 注入 {@code sub_account_id IN (...)}（空集 ⇒ 恒假）。
     * <b>不可见 ≡ 不存在</b>（同一返回 {@code null}，不泄露存在性，{@code T-100} 口径）。
     *
     * @param exposureId 敞口
     * @param operatorId 调用者（由会话决定，不接受请求参数传入）
     * @return 敞口；不存在或不可见均返回 {@code null}
     */
    public Exposure getExposure(long exposureId, long operatorId) {
        List<Integer> visible = visibleSubAccountIds(operatorId);
        if (visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            return exposureDao.findById(exposureId);
        }
        return exposureDao.findByIdScoped(exposureId, SubAccountScope.of("sub_account_id", visible));
    }

    /**
     * 取待套保敞口列表（计划编制入口，{@code FR-SPOT-15}），<b>分页 + 子账户隔离</b>。
     *
     * <p>可见集来自<b>会话身份</b>（{@link #visibleSubAccountIds}），<b>不由</b>调用方传入的
     * {@code subAccountId} 决定：含哨兵 {@link SubAccountScope#ALL_ACCOUNT_SENTINEL}（跨账户只读）
     * ⇒ 不构造 scope（读面放行）；否则经 {@link ExposureDao#findPageScoped} 注入
     * {@code sub_account_id IN (...)}（空集 ⇒ 恒假）。可选 {@code subAccountId} 仅作<b>收窄过滤</b>
     * （与 scope 取交集，越权取值自然落空），<b>不能</b>用它扩大可见集。
     *
     * <p>排序方向由调用方（handler）显式声明（缺省 {@code exposure_qty,desc}）；
     * 本层固定附加 {@code status = 'PENDING_HEDGE'} 谓词。
     *
     * @param operatorId    调用者（由会话决定，不接受请求参数传入）
     * @param query         分页 / 排序
     * @param subAccountId  可选收窄过滤（{@code null} = 不按子账户收窄）
     * @return 分页结果（{@code total} 为叠加可见集与过滤后的总行数）
     */
    public PageResult<Exposure> listPendingHedge(long operatorId, PageQuery query,
                                                 Integer subAccountId) {
        StringBuilder where = new StringBuilder("status = ?");
        List<Object> args = new ArrayList<>();
        args.add("PENDING_HEDGE");
        if (subAccountId != null) {
            where.append(" AND sub_account_id = ?");
            args.add(subAccountId);
        }
        List<Integer> visible = visibleSubAccountIds(operatorId);
        if (visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            return exposureDao.findPage(query, where.toString(), args);
        }
        return exposureDao.findPageScoped(query, SubAccountScope.of("sub_account_id", visible),
                where.toString(), args);
    }

    /**
     * 取调用者可见子账户集合（{@code NFR-SEC-7} 取数侧）。
     *
     * <p>入口缺位（{@code null}）或返回 {@code null} ⇒ 空集（fail-closed，谁都不见），
     * 绝不退化为"不过滤 = 全可见"（{@code SubAccountScope} 安全默认）。
     *
     * @param operatorId 调用者
     * @return 可见子账户集合（含哨兵 0 = 跨账户只读；可能为空）
     */
    private List<Integer> visibleSubAccountIds(long operatorId) {
        List<Integer> ids = subAccountVisibility == null
                ? null : subAccountVisibility.visibleSubAccountIds(operatorId);
        return ids == null ? Collections.emptyList() : ids;
    }

    /**
     * 计算并落库敞口快照（{@code Q-17} 写入侧；三分量与合计**同事务**校验）。
     *
     * <p>校验顺序（任一失败即整笔回滚）：
     * ① 子账户 / 品类 / 方向 / 口径 / 基准日 / 单位必填；
     * ② 未映射行必须 {@code productId = 0} 且 {@code unmappedFlag='Y'}`；已映射行必须 {@code productId > 0}`；
     * ③ {@code Σ三分量 = exposure_qty}`，否则**拒绝写入**（不得静默覆盖落库值）；
     * ④ {@code calcMode='COMBINED'}` 时组合构成必填；
     * ⑤ 明细（若提供）逐分量求和必须等于对应分量列，且方向一致；
     * ⑥ 估值缺价按 {@code VALUATION} 用途处置并标注。
     *
     * @param exposure   敞口（三分量与合计由调用方同事务算出）
     * @param details    构成明细（可为空列表，但为空时无法"自证构成"）
     * @param operatorId 操作人
     * @return 落库后的敞口（含 {@code snapshotId}）
     */
    public Exposure computeExposure(Exposure exposure, List<ExposureDetail> details, long operatorId) {
        requireSubAccount(exposure.subAccountId);
        requireSubAccountWritable(operatorId, exposure.subAccountId);
        requireText(exposure.spotCategoryCode, "spot_category_code");
        requireText(exposure.direction, "direction");
        requireText(exposure.calcMode, "calc_mode");
        requireText(exposure.unit, "unit");
        if (exposure.asOfDate == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计算基准日期必填（审计复现口径）：复现=补齐 as_of_date");
        }
        boolean unmapped = "Y".equals(exposure.unmappedFlag);
        if (unmapped && exposure.productId != UNMAPPED_PRODUCT_SENTINEL) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "未映射行必须取品种哨兵值 0（DC-041）：productId=" + exposure.productId
                            + "；复现=置 unmapped_flag='Y' 且 product_id=0");
        }
        if (!unmapped && (exposure.productId == null
                || exposure.productId == UNMAPPED_PRODUCT_SENTINEL)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "已映射行必须使用真实品种（productId > 0）：productId=" + exposure.productId
                            + "；复现=先完成品种映射（FR-SPOT-14）");
        }
        exposure.unmappedFlag = unmapped ? "Y" : "N";
        normalizeSpotCategory(exposure);
        if ("COMBINED".equals(exposure.calcMode) && isBlank(exposure.combinedComponents)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "组合口径必须显式声明构成分量（R-SPOT-4）：calcMode=" + exposure.calcMode
                            + "；复现=补齐 combined_components");
        }
        if (exposure.exposureQty == null || exposure.exposureQty.compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "敞口数量必须 ≥ 0：实际=" + exposure.exposureQty + "；复现=重算三分量");
        }
        BigDecimal componentSum = nz(exposure.contractUnexecutedQty)
                .add(nz(exposure.inventoryQty)).add(nz(exposure.productionPlanQty));
        if (componentSum.compareTo(exposure.exposureQty) != 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "Σ三分量 ≠ 敞口合计，拒绝写入（Q-17 写入侧，不得静默覆盖落库值）：分量合计="
                            + componentSum.toPlainString() + " 合计=" + exposure.exposureQty.toPlainString()
                            + "；复现=同事务重算三分量，或改落库值须生成新快照");
        }
        List<ExposureDetail> detailRows = details == null ? new ArrayList<>() : details;
        verifyDetails(exposure, detailRows);
        long seq = exposureDao.count("sub_account_id = ? AND as_of_date = ?",
                Arrays.asList(exposure.subAccountId, exposure.asOfDate)) + 1;
        exposure.snapshotId = BizNoGenerator.exposure(exposure.asOfDate, exposure.subAccountId,
                exposure.productId, exposure.direction, seq);
        exposure.snapshotTime = Clocks.now();
        exposure.calcTime = Clocks.now();
        exposure.coveredQty = nz(exposure.coveredQty);
        exposure.status = exposure.coveredQty.compareTo(BigDecimal.ZERO) == 0
                ? "PENDING_HEDGE"
                : (exposure.coveredQty.compareTo(exposure.exposureQty) >= 0
                        ? "COVERED" : "PARTIALLY_COVERED");
        applyValuation(exposure);
        return tx.inTransaction(connection -> {
            if (exposureDao.insert(exposure, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "同子账户×品种×品类×方向×口径×基准日已有敞口（uk_exposure_key）：snapshotId="
                                + exposure.snapshotId + "；复现=改为变更既有快照或调整基准日");
            }
            for (ExposureDetail detail : detailRows) {
                detail.exposureId = exposure.id;
                detail.subAccountId = exposure.subAccountId;
                detail.direction = exposure.direction;
                detail.calcAsOfDate = exposure.asOfDate;
                if (detail.unit == null) {
                    detail.unit = exposure.unit;
                }
                if (exposureDetailDao.insert(detail, operatorId) != 1) {
                    throw BizException.of(ErrorCode.INTERNAL_ERROR,
                            "敞口构成明细写入失败；复现=核对 t_exposure_detail 约束");
                }
            }
            return exposure;
        });
    }

    /**
     * 展示侧重算（{@code Q-17} ④ / {@code CFG-17b}）：只重算 + 告警 + 留痕，**绝不回写落库值**。
     *
     * @param exposureId 敞口
     * @param operatorId 触发人
     * @return 对比结果（含 {@code writtenBack=false} 的显式声明与逐项差异）
     */
    public Map<String, Object> recalcForDisplay(long exposureId, long operatorId) {
        Exposure exposure = exposureDao.findById(exposureId);
        if (exposure == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "敞口不存在：id=" + exposureId + "；复现=按 snapshot_id 查询确认 ID");
        }
        List<ExposureDetail> details = exposureDetailDao.findByExposure(exposureId);
        Map<String, BigDecimal> recomputed = sumByComponent(details);
        Map<String, BigDecimal> stored = new LinkedHashMap<>();
        stored.put("CONTRACT_UNEXECUTED", nz(exposure.contractUnexecutedQty));
        stored.put("INVENTORY", nz(exposure.inventoryQty));
        stored.put("PRODUCTION_PLAN", nz(exposure.productionPlanQty));

        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : stored.entrySet()) {
            BigDecimal actual = recomputed.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (actual.compareTo(entry.getValue()) != 0) {
                mismatches.add(entry.getKey() + "：落库=" + entry.getValue().toPlainString()
                        + " 明细重算=" + actual.toPlainString());
            }
        }
        if (!mismatches.isEmpty()) {
            Set<String> logged = new LinkedHashSet<>();
            tx.inTransaction(connection -> {
                for (ExposureDetail detail : details) {
                    String key = detail.sourceType + "#" + detail.sourceId;
                    if (!logged.add(key)) {
                        continue;
                    }
                    SpotChangeLog log = new SpotChangeLog();
                    log.sourceType = detail.sourceType;
                    log.sourceId = detail.sourceId;
                    log.changeType = "COMPONENT_RECALC";
                    log.subAccountId = exposure.subAccountId;
                    log.operatorId = operatorId;
                    log.changeTime = Clocks.now();
                    log.recalcTriggered = "Y";
                    log.changeReason = "展示侧重算不一致（未回写落库值，Q-17/CFG-17b）："
                            + String.join("；", mismatches);
                    spotChangeLogDao.insert(log, operatorId);
                }
                return null;
            });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", exposure.snapshotId);
        out.put("storedComponents", stored);
        out.put("recomputedComponents", recomputed);
        out.put("mismatched", !mismatches.isEmpty());
        out.put("mismatchDetail", mismatches);
        out.put("writtenBack", false);
        out.put("note", "展示侧不一致只重算 + 告警 + 留痕 COMPONENT_RECALC，不回写落库值；"
                + "确需改落库值须重算生成新快照并保留旧快照（Q-17 ③）");
        return out;
    }

    // ------------------------------------------------------------ T-109 SPOT 声明面补齐

    /** 生产计划行类型取值域（{@code FR-SPOT-8}）。 */
    public static final Set<String> PRODUCTION_LINE_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("FINISHED", "RAW_MATERIAL")));

    /** 子账户归属可变更的单据类型（{@code FR-SPOT-16}；现货价不属其中，{@code CH-003}）。 */
    public static final Set<String> DOC_SUB_ACCOUNT_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("PURCHASE_CONTRACT", "SALES_CONTRACT",
                    "PRODUCTION_PLAN")));

    /**
     * 录入生产计划（{@code FR-SPOT-8}）。
     *
     * <p>计划须归属子账户、映射到品种；计划期不得为空；在制品按完工比例折算（本端点只落"计划"，
     * 敞口由 {@code /spot/exposure} 计算）。计划期与既有计划重叠时置 {@code overlapFlag='Y'} 并
     * <b>回传重复提示</b>（<b>不阻断</b>，{@code FR-SPOT-8} 验收 3）。产成品行导出 {@code SELL}、
     * 原料行导出 {@code BUY}。写面强制调用者对目标子账户可见（{@code NFR-SEC-7}）。
     *
     * @param plan       计划头（{@code subAccountId}/{@code planNo}/计划期 必填）
     * @param lines      计划行（产成品 / 所需原料；至少一行）
     * @param operatorId 操作人
     * @return 结果（{@code id} / {@code overlapFlag} / {@code overlapHint}）
     */
    public Map<String, Object> createProductionPlan(ProductionPlan plan, List<ProductionPlanLine> lines,
                                                    long operatorId) {
        requireSubAccount(plan.subAccountId);
        requireSubAccountWritable(operatorId, plan.subAccountId);
        requireText(plan.planNo, "plan_no");
        if (plan.planPeriodStart == null || plan.planPeriodEnd == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "生产计划期不得为空（FR-SPOT-8）：复现=补齐 planPeriodStart / planPeriodEnd");
        }
        if (plan.planPeriodEnd.isBefore(plan.planPeriodStart)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划期止不得早于起始：start=" + plan.planPeriodStart + " end=" + plan.planPeriodEnd
                            + "；复现=调整计划期");
        }
        if (lines == null || lines.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "生产计划须至少一行（产成品或所需原料）：复现=补齐 lines");
        }
        for (ProductionPlanLine line : lines) {
            if (!PRODUCTION_LINE_TYPES.contains(line.lineType)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "计划行类型须 ∈ " + PRODUCTION_LINE_TYPES + "：实际=" + line.lineType
                                + "；复现=按 FR-SPOT-8 取值域提交");
            }
            if (line.productId == null || line.productId <= 0) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "计划行品种必须已建立映射（productId > 0）：lineType=" + line.lineType
                                + "；复现=先完成 FR-PRODUCT-10 映射");
            }
            requirePositive(line.quantity, "quantity");
            requireText(line.unit, "unit");
            line.exposureDirection = "FINISHED".equals(line.lineType) ? "SELL" : "BUY";
        }
        plan.status = isBlank(plan.status) ? "SCHEDULED" : plan.status;
        plan.overlapFlag = hasOverlappingPlan(plan) ? "Y" : "N";
        long id = tx.inTransaction(connection -> {
            if (productionPlanDao.insert(plan, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "生产计划编号冲突（同一子账户内唯一）：planNo=" + plan.planNo
                                + "；复现=更换编号或调整计划期");
            }
            for (ProductionPlanLine line : lines) {
                line.productionPlanId = plan.id;
                line.subAccountId = plan.subAccountId;
                if (productionPlanLineDao.insert(line, operatorId) != 1) {
                    throw BizException.of(ErrorCode.INTERNAL_ERROR,
                            "生产计划行写入失败；复现=核对 t_production_plan_line 约束");
                }
            }
            writeLog("PRODUCTION_PLAN", plan.id, "CREATE", null, plan.status, plan.subAccountId,
                    operatorId, "录入生产计划（FR-SPOT-8；重叠=" + plan.overlapFlag + "）");
            return plan.id;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("overlapFlag", plan.overlapFlag);
        out.put("overlapHint", "Y".equals(plan.overlapFlag)
                ? "计划期与既有计划重叠，请确认是否重复录入（FR-SPOT-8 验收 3）" : "");
        return out;
    }

    /**
     * 批量导入现货价（{@code FR-SPOT-11} 批量粘贴 / {@code FR-SPOT-12} CSV 导入）。
     *
     * <p><b>输入契约（{@code TE-T109-001} 修复）</b>：入参为<b>文本块</b>而非已解析的实体行——
     * 与 {@code FR-SPOT-11/12} 的「粘贴文本块 / CSV 文件」一致；列以<b>品种编码</b>为准
     * （{@link #CSV_HEADER}），经 {@link ProductDao#findByCode(String)} 解析为 {@code productId}。
     * <ul>
     *   <li>{@code BATCH_PASTE}（{@code FR-SPOT-11}）：每行 = 品种编码、日期、现货价
     *       （逗号或制表符分隔），<b>无表头</b>；忽略空行（含末尾空行）；</li>
     *   <li>{@code CSV_IMPORT}（{@code FR-SPOT-12}）：首行为表头，<b>校验列完整性</b>
     *       （缺列即整体拒绝并<b>提示缺失列名</b>）。</li>
     * </ul>
     *
     * <p><b>不加强子账户可见性强制（{@code CH-003} / {@code R-SPOT-2} 注；{@code T-110}）</b>：
     * 传入的 {@code subAccountId} 为现货价的**录入归属**（同 {@link #putSpotPrice}），非业务归属维度，
     * 不参与加总；不得按业务归属强制（否则会误拒共享价的正常导入）。
     *
     * <p><b>全量预校验 → 一次性提交</b>：逐行校验（品种编码存在、日期合法且不晚于当日、价格为正、
     * 批次内品种+日期不重复）；任一不合法行<b>定位到行号</b>、整体不导入。同品种同日已有价时，
     * 未确认覆盖则整体拒绝（{@code R-SPOT-3}，不得静默覆盖）。成功时来源标注含 {@code sourceType} /
     * 导入文件名 / 操作人（{@code FR-SPOT-12} 验收 3）。
     *
     * @param lines              文本行（{@code BATCH_PASTE} 全为数据行；{@code CSV_IMPORT} 首行为表头）
     * @param subAccountId       录入归属子账户（必填，&gt; 0）
     * @param sourceType         {@code BATCH_PASTE} / {@code CSV_IMPORT}（缺省 {@code BATCH_PASTE}）
     * @param importFileName     CSV 导入文件名（{@code sourceType=CSV_IMPORT} 时必填）
     * @param overwriteConfirmed 是否确认覆盖既有同日价
     * @param operatorId         操作人
     * @return 导入汇总（{@code inserted} / {@code updated} / {@code total} / {@code sourceType}）
     */
    public Map<String, Object> importSpotPrices(List<String> lines, Integer subAccountId,
                                                String sourceType, String importFileName,
                                                boolean overwriteConfirmed, long operatorId) {
        if (lines == null || lines.isEmpty()) {
            throw BizException.of(ErrorCode.IMPORT_VALIDATION_FAILED,
                    "导入内容为空：复现=提供至少一行（FR-SPOT-11/12）");
        }
        String source = isBlank(sourceType) ? "BATCH_PASTE" : sourceType;
        if (!"BATCH_PASTE".equals(source) && !"CSV_IMPORT".equals(source)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "来源类型须为 BATCH_PASTE / CSV_IMPORT：实际=" + source + "；复现=改正来源");
        }
        if (subAccountId == null || subAccountId <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "录入归属子账户必填（> 0）：实际=" + subAccountId + "；复现=补 subAccountId（R-SPOT-2）");
        }
        List<String> problems = new ArrayList<>();
        int dataStart = 0;
        List<String> header = null;
        if ("CSV_IMPORT".equals(source)) {
            requireText(importFileName, "importFileName");
            String headerLine = lines.get(0);
            if (headerLine == null || headerLine.trim().isEmpty()) {
                // 表头行缺失/空白：不得对 null 行做拆分（否则 NPE→500），
                // 按 10019 全量预校验体例给出可判定拒因（对齐全为空行的同一语义）。
                problems.add("CSV 表头行缺失或为空（第 1 行）：期望列 "
                        + String.join("/", CSV_HEADER));
            } else {
                header = splitCells(headerLine);
                for (String column : CSV_HEADER) {
                    if (!header.contains(column)) {
                        problems.add("CSV 表头缺少必需列：" + column);
                    }
                }
            }
            if (!problems.isEmpty()) {
                throw BizException.of(ErrorCode.IMPORT_VALIDATION_FAILED,
                        "CSV 表头列完整性校验未通过（整体不导入）：" + String.join("；", problems));
            }
            dataStart = 1;
        }
        List<SpotPrice> parsed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int validRows = 0;
        for (int i = dataStart; i < lines.size(); i++) {
            String raw = lines.get(i);
            int line = i + 1;
            if (raw == null || raw.trim().isEmpty()) {
                continue; // 忽略空行（FR-SPOT-11：忽略末尾空行）
            }
            validRows++;
            List<String> cells = splitCells(raw);
            String code;
            String dateText;
            String priceText;
            if (header == null) {
                if (cells.size() < 3) {
                    problems.add("第 " + line + " 行列数不足（期望 品种编码,日期,现货价）：实际=" + raw);
                    continue;
                }
                code = cells.get(0);
                dateText = cells.get(1);
                priceText = cells.get(2);
            } else {
                code = cellValue(header, cells, "品种编码");
                dateText = cellValue(header, cells, "日期");
                priceText = cellValue(header, cells, "现货价");
            }
            SpotPrice price = new SpotPrice();
            price.subAccountId = subAccountId;
            // 品种编码 → 品种 ID（uk_product_code 唯一；CSV 导入与下单前置校验共用入口）
            Product product = productDao == null || isBlank(code) ? null : productDao.findByCode(code);
            if (isBlank(code)) {
                problems.add("第 " + line + " 行品种编码必填");
            } else if (product == null) {
                problems.add("第 " + line + " 行品种编码不存在：" + code);
            } else {
                price.productId = product.id;
            }
            if (isBlank(dateText)) {
                problems.add("第 " + line + " 行日期必填");
            } else {
                try {
                    LocalDate date = LocalDate.parse(dateText);
                    price.priceDate = date;
                    if (date.isAfter(Clocks.today())) {
                        problems.add("第 " + line + " 行日期不得晚于当日：" + date);
                    }
                } catch (DateTimeParseException e) {
                    problems.add("第 " + line + " 行日期格式非法：" + dateText + "（须 yyyy-MM-dd）");
                }
            }
            if (isBlank(priceText)) {
                problems.add("第 " + line + " 行现货价必填");
            } else {
                try {
                    BigDecimal amount = new BigDecimal(priceText);
                    price.spotPrice = amount;
                    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                        problems.add("第 " + line + " 行现货价必须 > 0：" + priceText);
                    }
                } catch (NumberFormatException e) {
                    problems.add("第 " + line + " 行现货价非法：" + priceText);
                }
            }
            if (price.productId != null && price.priceDate != null
                    && !seen.add(price.productId + "#" + price.priceDate)) {
                problems.add("第 " + line + " 行与批次内其它行品种+日期重复");
            }
            parsed.add(price);
        }
        if (validRows == 0) {
            problems.add("无可导入数据行（内容全为空行）");
        }
        if (!problems.isEmpty()) {
            throw BizException.of(ErrorCode.IMPORT_VALIDATION_FAILED,
                    "导入全量预校验未通过（整体不导入，共 " + problems.size() + " 处）："
                            + String.join("；", problems));
        }
        if (!overwriteConfirmed) {
            List<String> conflicts = new ArrayList<>();
            for (SpotPrice row : parsed) {
                if (spotPriceDao.findGlobalPrice(row.productId, row.priceDate) != null) {
                    conflicts.add("品种=" + row.productId + " 日期=" + row.priceDate);
                }
            }
            if (!conflicts.isEmpty()) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "存在已录入的同期现货价，不得静默覆盖（R-SPOT-3）：" + String.join("；", conflicts)
                                + "；复现=携带覆盖确认后重试");
            }
        }
        return tx.inTransaction(connection -> {
            int inserted = 0;
            int updated = 0;
            for (SpotPrice row : parsed) {
                SpotPrice existing = spotPriceDao.findGlobalPrice(row.productId, row.priceDate);
                if (existing == null) {
                    row.sourceType = source;
                    row.importFileName = importFileName;
                    row.isShared = "Y";
                    row.operatorId = operatorId;
                    row.entryTime = Clocks.now();
                    row.overwriteConfirmed = "N";
                    if (spotPriceDao.insert(row, operatorId) != 1) {
                        throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                                "现货价唯一口径冲突（uk_spot_price_global）：productId=" + row.productId
                                        + " priceDate=" + row.priceDate);
                    }
                    inserted++;
                } else {
                    existing.overwrittenPrice = existing.spotPrice;
                    existing.spotPrice = row.spotPrice;
                    existing.sourceType = source;
                    existing.importFileName = importFileName;
                    existing.overwriteConfirmed = "Y";
                    existing.priceUnit = row.priceUnit == null ? existing.priceUnit : row.priceUnit;
                    if (spotPriceDao.updateById(existing, operatorId) != 1) {
                        throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                                "现货价并发冲突：productId=" + row.productId + " priceDate="
                                        + row.priceDate + "；复现=重新读取后再提交");
                    }
                    updated++;
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("inserted", inserted);
            summary.put("updated", updated);
            summary.put("total", parsed.size());
            summary.put("sourceType", source);
            return summary;
        });
    }

    /** 按分隔符拆列：含制表符按制表符，否则按逗号（{@code FR-SPOT-11} 制表符/逗号）。 */
    private static List<String> splitCells(String line) {
        String sep = line.indexOf('\t') >= 0 ? "\t" : ",";
        List<String> cells = new ArrayList<>();
        for (String part : line.split(Pattern.quote(sep), -1)) {
            cells.add(part.trim());
        }
        return cells;
    }

    /** 按表头列名取值（列名不存在或超界返回 {@code null}）。 */
    private static String cellValue(List<String> header, List<String> cells, String column) {
        int idx = header.indexOf(column);
        return idx < 0 || idx >= cells.size() ? null : cells.get(idx);
    }

    /**
     * 导出现货价序列为 CSV 行（{@code FR-SPOT-13} 的价格序列查询之导出形态）。
     *
     * @param productId 品种（可空）
     * @param from      起始日期（可空）
     * @param to        截止日期（可空）
     * @return CSV 行（首行表头）
     */
    public List<String> exportSpotPricesCsv(Long productId, LocalDate from, LocalDate to) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (productId != null) {
            where.append("product_id = ?");
            args.add(productId);
        }
        if (from != null) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append("price_date >= ?");
            args.add(from);
        }
        if (to != null) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append("price_date <= ?");
            args.add(to);
        }
        List<SpotPrice> all = collectAll(spotPriceDao, where.length() == 0 ? null : where.toString(),
                args, "price_date");
        List<String> lines = new ArrayList<>();
        lines.add("product_id,price_date,spot_price,source_type,source_desc,import_file_name,"
                + "price_unit,operator_id,entry_time");
        for (SpotPrice price : all) {
            lines.add(String.join(",", csv(text(price.productId)), csv(price.priceDate == null ? null
                            : price.priceDate.toString()), csv(text(price.spotPrice)),
                    csv(price.sourceType), csv(price.sourceDesc), csv(price.importFileName),
                    csv(price.priceUnit), csv(text(price.operatorId)), csv(price.entryTime == null
                            ? null : price.entryTime.toString())));
        }
        return lines;
    }

    /**
     * 导出实货敞口为 CSV 行（{@code FR-SPOT-18}）。
     *
     * <p>导出内容含子账户 / 品种 / 方向 / 数量 / 状态列，并携带导出时间、导出人与计算基准日期
     * （供审计复现口径）。<b>仅导出有权限的子账户范围</b>（{@code NFR-SEC-7}，{@code TE-T109-002}）：
     * 导出前强制调用者对该子账户的<b>可见性</b>（{@link #requireSubAccountVisible}），
     * 不可见即 {@code 10004} 拒绝，<b>不</b>泄露任意子账户敞口。<b>无数据时不生成空文件</b>。
     *
     * @param subAccountId 子账户（必填；仅导出该子账户范围）
     * @param asOfDate     计算基准日期（可空）
     * @param operatorId   导出人
     * @return CSV 行（首行为口径注释，次行表头）
     */
    public List<String> exportExposuresCsv(Integer subAccountId, LocalDate asOfDate, long operatorId) {
        if (subAccountId == null || subAccountId <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "导出须指定子账户（仅导出有权限的子账户范围，FR-SPOT-18）：复现=给出 subAccountId");
        }
        requireSubAccountVisible(operatorId, subAccountId);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("sub_account_id = ?");
        args.add(subAccountId);
        if (asOfDate != null) {
            where.append(" AND as_of_date = ?");
            args.add(asOfDate);
        }
        List<Exposure> all = collectAll(exposureDao, where.toString(), args, "id");
        if (all.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "无可导出的敞口数据，不生成空文件（FR-SPOT-18 验收 4）：subAccountId=" + subAccountId
                            + "；复现=先计算敞口或调整筛选条件");
        }
        List<String> lines = new ArrayList<>();
        lines.add("# exportTime=" + Clocks.now() + "; exportedBy=" + operatorId + "; asOfDate="
                + (asOfDate == null ? "" : asOfDate));
        lines.add("sub_account_id,product_id,spot_category_code,direction,exposure_qty,unit,status,"
                + "covered_qty");
        for (Exposure exposure : all) {
            lines.add(String.join(",", csv(text(exposure.subAccountId)),
                    csv(text(exposure.productId)), csv(exposure.spotCategoryCode),
                    csv(exposure.direction), csv(text(exposure.exposureQty)), csv(exposure.unit),
                    csv(exposure.status), csv(text(exposure.coveredQty))));
        }
        return lines;
    }

    /**
     * 变更现货单据的子账户归属（{@code FR-SPOT-16}；{@code T-111} 服务端敞口推导引擎接线）。
     *
     * <p>规则：归属变更<b>仅允许在敞口未被任何生效计划引用时</b>执行（该单据贡献的敞口
     * {@code coveredQty} 恒为 0）；目标子账户须存在且启用；<b>调用者须对源、目标子账户均可见</b>
     * （{@code NFR-SEC-7} 写面强制）。<b>归属变更 + 两侧敞口重算 + 留痕在同一事务内</b>
     * （{@code TxTemplate}；重算失败 ⇒ 整笔回滚，不留半笔写）：成功路径<b>同步触发</b>
     * {@link #recomputeDocExposures}——按构成明细推导（Σ明细同源构造三分量与合计，{@code T-080b}
     * 口径），旧侧敞口减量（明细清空 ⇒ 归零并按 {@code R-SPOT-5} 进入 {@code CLOSED} 终态）、
     * 目标子账户侧生成/更新等量敞口（沿既有 snapshot 世代形态刷新快照标识）。
     *
     * <p><b>{@code recomputeRequired} 语义（{@code T-111} 收紧）</b>：旧语义
     * 「{@code true} = 调用方经 {@code /spot/exposure} 负责重算」已废止；现值恒为
     * {@code true}，语义 = <b>服务端已在本事务内完成敞口重算</b>（旧快照身份留痕、
     * 新世代快照标识可审计复现）。响应字段形态保留（信封零破坏）。
     *
     * <p><b>拒因口径（{@code TE-T109-006}）</b>：单据被生效计划引用时，拒因按 {@code FR-SPOT-16}
     * 验收 2 列<b>计划</b>（经 {@code t_hedge_plan_exposure} 反查 {@code hedge_plan_id}），
     * 而非仅列敞口 ID。留痕含变更前后子账户与操作人（验收 3），并标注
     * {@code recalc_triggered='Y'}（{@code TB-SPOT-10}）。
     *
     * @param docType             单据类型（{@code PURCHASE_CONTRACT}/{@code SALES_CONTRACT}/{@code PRODUCTION_PLAN}）
     * @param docId               单据 ID
     * @param targetSubAccountId  目标子账户
     * @param operatorId          操作人
     * @return 变更结果（{@code fromSubAccountId}/{@code toSubAccountId}/{@code recomputeRequired}）
     */
    public Map<String, Object> changeDocSubAccount(String docType, long docId,
                                                   int targetSubAccountId, long operatorId) {
        requireText(docType, "docType");
        if (!DOC_SUB_ACCOUNT_TYPES.contains(docType)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "单据类型须 ∈ " + DOC_SUB_ACCOUNT_TYPES + "（现货价不属其中，CH-003）：实际="
                            + docType + "；复现=按 FR-SPOT-16 取值域提交");
        }
        if (subAccountDao == null) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "子账户 DAO 未装配；复现=检查 Bootstrap 装配");
        }
        SubAccount target = subAccountDao.findById(targetSubAccountId);
        if (target == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "目标子账户不存在：id=" + targetSubAccountId + "；复现=核对子账户编码");
        }
        if (!"ENABLED".equals(target.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "目标子账户已停用，不得作为归属：id=" + targetSubAccountId + "；复现=改用启用中的子账户");
        }
        Integer current = docSubAccountId(docType, docId);
        if (current == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "单据不存在或已删除：" + docType + " id=" + docId + "；复现=核对单据 ID");
        }
        requireSubAccountWritable(operatorId, current);
        requireSubAccountWritable(operatorId, targetSubAccountId);
        List<Long> referenced = referencedExposures(docType, docId);
        if (!referenced.isEmpty()) {
            List<Long> plans = referencingPlans(referenced);
            throw BizException.of(ErrorCode.REFERENCE_CONFLICT,
                    "该单据贡献的敞口已被生效计划引用，归属变更被拒绝（FR-SPOT-16 验收 2）：plans="
                            + plans + "（敞口=" + referenced + "）；复现=先终止相关计划再变更归属");
        }
        tx.inTransaction(connection -> {
            applyDocSubAccount(docType, docId, targetSubAccountId, operatorId);
            // T-111：归属变更与两侧敞口重算同一事务——重算抛出 ⇒ 整笔回滚（不留半笔写）。
            recomputeDocExposures(docType, docId, targetSubAccountId, operatorId);
            writeSubAccountChangeLog(docType, docId, current, targetSubAccountId, operatorId,
                    "变更单据子账户归属并同步重算敞口（FR-SPOT-16 验收 1/3；T-111）");
            return null;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docType", docType);
        out.put("docId", docId);
        out.put("fromSubAccountId", current);
        out.put("toSubAccountId", targetSubAccountId);
        // T-111 语义收紧：true = 服务端已在本事务内完成敞口重算（旧「调用方负责重算」语义废止）
        out.put("recomputeRequired", true);
        out.put("note", "归属已变更并留痕；敞口已由服务端按构成明细同步重算"
                + "（旧侧减量/清空归 CLOSED，目标侧生成或并入等量敞口并刷新快照世代；"
                + "R-SPOT-5：状态由引用关系计算，旧快照身份留痕）");
        return out;
    }

    // ------------------------------------------------------------ T-111 服务端敞口推导引擎

    /**
     * 按构成明细推导归属变更影响面并重算两侧敞口（{@code T-111}；{@code FR-SPOT-16} 验收 1）。
     *
     * <p><b>推导口径（Σ明细同源构造，{@code T-080b}）</b>：三分量与合计<b>不由调用方传值</b>，
     * 一律从 {@code t_exposure_detail} 按分量分组求和构造——与 {@code recalcForDisplay} 的
     * 重算口径同源，与 {@code verifyDetails} 的「SUM(明细)=分量列」结构保证互恰。
     *
     * <p><b>影响面</b>：单据贡献的全部构成明细（{@code findBySource}，同一单据可对多个基准日
     * 各贡献明细）按旧敞口分组，逐组 {@link #moveContribution}。单据尚未进入任何敞口构成
     * （明细为空）⇒ 无可重算（验收 1 不适用），直接返回。
     *
     * <p><b>哨兵与双态（{@code DC-041}/{@code CH-008}）</b>：新侧敞口身份列
     * （{@code product_id}/{@code spot_category_code}/{@code unmapped_flag}）自旧敞口**原样承袭**
     * ——未映射行携哨兵 {@code product_id=0} 与真实品类（各品类各占一行）、已映射行携
     * 占位常量 {@code __MAPPED__}；<b>不在本路径重做品种映射</b>（映射状态是敞口身份的组成部分，
     * 变更归属不改变映射事实）。
     *
     * <p><b>事务边界</b>：本方法<b>不</b>自开事务，必须在 {@code changeDocSubAccount} 的
     * {@code TxTemplate} 事务内调用（嵌套加入外层）；任一写入失败 ⇒ 整笔回滚。
     *
     * @param docType            单据类型
     * @param docId              单据 ID
     * @param targetSubAccountId 目标子账户
     * @param operatorId         操作人
     */
    private void recomputeDocExposures(String docType, long docId, int targetSubAccountId,
                                       long operatorId) {
        List<ExposureDetail> docDetails = exposureDetailDao.findBySource(docType, docId);
        if (docDetails.isEmpty()) {
            return; // 单据尚未进入任何敞口构成 ⇒ 验收 1 不适用（无可重算面）
        }
        Map<Long, List<ExposureDetail>> byOldExposure = new LinkedHashMap<>();
        for (ExposureDetail detail : docDetails) {
            if (detail.exposureId == null) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "构成明细缺敞口引用（exposure_id 为空），无法重算：detailId=" + detail.id
                                + "；复现=核对 t_exposure_detail.exposure_id");
            }
            byOldExposure.computeIfAbsent(detail.exposureId, k -> new ArrayList<>()).add(detail);
        }
        for (Map.Entry<Long, List<ExposureDetail>> entry : byOldExposure.entrySet()) {
            Exposure oldExposure = exposureDao.findById(entry.getKey());
            if (oldExposure == null) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "构成明细指向的敞口不存在，无法重算：exposureId=" + entry.getKey()
                                + "；复现=核对 t_exposure_detail 与 t_exposure 引用一致性");
            }
            moveContribution(oldExposure, entry.getValue(), targetSubAccountId, operatorId);
        }
    }

    /**
     * 将单据在旧敞口上的贡献迁移至目标子账户并重算两侧敞口（{@code T-111}）。
     *
     * <p>顺序（同一事务内）：① 目标侧敞口头查找或新建（同身份键：目标子账户 × 品种 × 品类
     * × 方向 × 口径 × 基准日，即 {@code uk_exposure_key} 六列）；② 构成明细改挂目标敞口
     * （{@code exposure_id}/{@code sub_account_id} 双列更新，明细行身份与留痕保留）；
     * ③ 目标侧重算 = Σ其全部明细（既有明细 + 迁入明细），刷新快照世代；
     * ④ 旧侧重算 = Σ剩余明细，清空 ⇒ 归零并 {@code CLOSED}（{@code R-SPOT-5} 终态）。
     *
     * @param oldExposure        旧敞口（迁移来源）
     * @param movedDetails       迁移的构成明细（须属旧敞口）
     * @param targetSubAccountId 目标子账户
     * @param operatorId         操作人
     */
    private void moveContribution(Exposure oldExposure, List<ExposureDetail> movedDetails,
                                  int targetSubAccountId, long operatorId) {
        // ① 目标侧敞口头：同身份键查找，缺则新建（数量暂零，③ 统一同源重算）
        Exposure targetExposure = findExposureByIdentity(targetSubAccountId, oldExposure);
        if (targetExposure == null) {
            targetExposure = newExposureHead(oldExposure, targetSubAccountId, operatorId);
        }
        // ② 明细改挂（保留明细行主键与来源标注，exposure_id / sub_account_id 双列随迁）
        for (ExposureDetail detail : movedDetails) {
            detail.exposureId = targetExposure.id;
            detail.subAccountId = targetSubAccountId;
            if (exposureDetailDao.updateById(detail, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "构成明细改挂失败（乐观锁/行缺失）：detailId=" + detail.id
                                + "；复现=重新读取后再提交");
            }
        }
        // ③ 目标侧重算：Σ 全部明细（既有 + 迁入）——三分量与合计同源构造
        refreshExposureFromDetails(targetExposure, operatorId);
        // ④ 旧侧重算：Σ 剩余明细；清空 ⇒ 归零 + CLOSED（R-SPOT-5 终态）
        refreshExposureFromDetails(oldExposure, operatorId);
    }

    /**
     * 按身份键（{@code uk_exposure_key} 六列）查找子账户下的既有敞口（{@code T-111}）。
     *
     * <p>谓词走 {@link BaseDao#findPage} 占位符参数（不拼取值）；命中多行（理论不可，
     * 唯一键保证）取首行。
     *
     * @param subAccountId 目标子账户
     * @param source       承袭身份列的源敞口（品种/品类/方向/口径/基准日）
     * @return 既有敞口；不存在返回 {@code null}
     */
    private Exposure findExposureByIdentity(int subAccountId, Exposure source) {
        List<Object> args = new ArrayList<>(Arrays.asList(
                subAccountId, source.productId, source.spotCategoryCode,
                source.direction, source.calcMode, source.asOfDate));
        List<Exposure> hits = collectAll(exposureDao,
                "sub_account_id = ? AND product_id = ? AND spot_category_code = ?"
                        + " AND direction = ? AND calc_mode = ? AND as_of_date = ?",
                args, "id");
        return hits.isEmpty() ? null : hits.get(0);
    }

    /**
     * 依旧敞口身份新建目标子账户敞口头（数量暂零；重算在明细改挂后统一进行）。
     *
     * <p>快照世代沿 {@link #computeExposure} 既有形态：{@code snapshot_id} =
     * {@link BizNoGenerator#exposure}（当日序号 = 同子账户同基准日行数 + 1）、
     * {@code snapshot_time}/{@code calc_time} = 当前时点；估值按 {@code VALUATION} 用途
     * 处置（缺价标注，不阻断——{@code CFG-12b}）；初始 {@code covered_qty=0}、
     * 状态 {@code PENDING_HEDGE}（覆盖量重算后按引用关系再派生）。
     *
     * @param source             源敞口（身份与口径配置承袭）
     * @param targetSubAccountId 目标子账户
     * @param operatorId         操作人
     * @return 已落库的新敞口（含 {@code id}）
     */
    private Exposure newExposureHead(Exposure source, int targetSubAccountId, long operatorId) {
        Exposure created = new Exposure();
        created.subAccountId = targetSubAccountId;
        created.productId = source.productId;
        created.spotCategoryCode = source.spotCategoryCode;
        created.direction = source.direction;
        created.calcMode = source.calcMode;
        created.asOfDate = source.asOfDate;
        created.componentsEnabled = source.componentsEnabled;
        created.combinedComponents = source.combinedComponents;
        created.unit = source.unit;
        created.unmappedFlag = source.unmappedFlag;
        created.contractUnexecutedQty = BigDecimal.ZERO;
        created.inventoryQty = BigDecimal.ZERO;
        created.productionPlanQty = BigDecimal.ZERO;
        created.exposureQty = BigDecimal.ZERO;
        created.coveredQty = BigDecimal.ZERO;
        created.status = "PENDING_HEDGE";
        long seq = exposureDao.count("sub_account_id = ? AND as_of_date = ?",
                Arrays.asList(targetSubAccountId, created.asOfDate)) + 1;
        created.snapshotId = BizNoGenerator.exposure(created.asOfDate, targetSubAccountId,
                created.productId, created.direction, seq);
        created.snapshotTime = Clocks.now();
        created.calcTime = Clocks.now();
        applyValuation(created);
        if (exposureDao.insert(created, operatorId) != 1) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "目标敞口写入失败（同子账户×品种×品类×方向×口径×基准日或快照标识冲突）：snapshotId="
                            + created.snapshotId + "；复现=核对 uk_exposure_key / uk_exposure_snapshot");
        }
        return created;
    }

    /**
     * 从构成明细同源重算敞口头（Σ明细 → 三分量 → 合计；{@code T-080b} 口径）并落库。
     *
     * <p>明细为空 ⇒ 三分量与合计归零；状态派生：合计为 0 ⇒ {@code CLOSED}（{@code R-SPOT-5}
     * 「敞口数量归零时进入已关闭（终态）」）；否则按覆盖量派生（同 {@code computeExposure}）。
     * 快照世代刷新：重算即新世代（新 {@code snapshot_id}/{@code snapshot_time}/{@code calc_time}），
     * 旧快照身份随留痕可追溯（Q-17 ③ 禁止静默覆盖——本路径以留痕 + 世代刷新承载）。
     *
     * @param exposure   敞口头（须已携 {@code id}）
     * @param operatorId 操作人
     */
    private void refreshExposureFromDetails(Exposure exposure, long operatorId) {
        List<ExposureDetail> details = exposureDetailDao.findByExposure(exposure.id);
        Map<String, BigDecimal> sums = sumByComponent(details);
        exposure.contractUnexecutedQty = sums.getOrDefault("CONTRACT_UNEXECUTED", BigDecimal.ZERO);
        exposure.inventoryQty = sums.getOrDefault("INVENTORY", BigDecimal.ZERO);
        exposure.productionPlanQty = sums.getOrDefault("PRODUCTION_PLAN", BigDecimal.ZERO);
        exposure.exposureQty = nz(exposure.contractUnexecutedQty)
                .add(nz(exposure.inventoryQty)).add(nz(exposure.productionPlanQty));
        long seq = exposureDao.count("sub_account_id = ? AND as_of_date = ?",
                Arrays.asList(exposure.subAccountId, exposure.asOfDate)) + 1;
        exposure.snapshotId = BizNoGenerator.exposure(exposure.asOfDate, exposure.subAccountId,
                exposure.productId, exposure.direction, seq);
        exposure.snapshotTime = Clocks.now();
        exposure.calcTime = Clocks.now();
        exposure.status = deriveStatus(exposure);
        applyValuation(exposure);
        if (exposureDao.updateById(exposure, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "敞口重算落库失败（乐观锁/行缺失）：exposureId=" + exposure.id
                            + "；复现=重新读取后再提交");
        }
    }

    /** 按覆盖量与合计派生敞口状态（合计归零 ⇒ CLOSED 终态；{@code R-SPOT-5}）。 */
    private static String deriveStatus(Exposure exposure) {
        BigDecimal qty = nz(exposure.exposureQty);
        BigDecimal covered = nz(exposure.coveredQty);
        if (qty.compareTo(BigDecimal.ZERO) == 0) {
            return "CLOSED";
        }
        if (covered.compareTo(qty) >= 0) {
            return "COVERED";
        }
        return covered.compareTo(BigDecimal.ZERO) > 0 ? "PARTIALLY_COVERED" : "PENDING_HEDGE";
    }

    /**
     * 归属变更留痕（{@code FR-SPOT-16} 验收 3；{@code TB-SPOT-10}）。
     *
     * <p>与 {@link #writeLog} 同形态，另三处为归属变更专属：
     * {@code sub_account_before}/{@code sub_account_after} 非空（{@code ck_spot_chg_sub_change}
     * 与验收 3「含前后子账户」）、{@code sub_account_id} 取变更后（按子账户检索定位）、
     * {@code recalc_triggered='Y'}（{@code TB-SPOT-10}：是否触发敞口重算）。
     */
    private void writeSubAccountChangeLog(String sourceType, long sourceId, int before,
                                          int after, long operatorId, String reason) {
        SpotChangeLog log = new SpotChangeLog();
        log.sourceType = sourceType;
        log.sourceId = sourceId;
        log.changeType = "SUB_ACCOUNT_CHANGE";
        log.subAccountBefore = before;
        log.subAccountAfter = after;
        log.beforeValue = String.valueOf(before);
        log.afterValue = String.valueOf(after);
        log.subAccountId = after;
        log.operatorId = operatorId;
        log.changeTime = Clocks.now();
        log.recalcTriggered = "Y";
        log.changeReason = reason;
        spotChangeLogDao.insert(log, operatorId);
    }

    // ------------------------------------------------------------ 内部

    /** 逐分量校验明细之和等于分量列，且方向与敞口头一致（{@code TB-SPOT-9} 结构保证）。 */
    private static void verifyDetails(Exposure exposure, List<ExposureDetail> details) {
        if (details.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> sums = sumByComponent(details);
        Map<String, BigDecimal> columns = new LinkedHashMap<>();
        columns.put("CONTRACT_UNEXECUTED", nz(exposure.contractUnexecutedQty));
        columns.put("INVENTORY", nz(exposure.inventoryQty));
        columns.put("PRODUCTION_PLAN", nz(exposure.productionPlanQty));
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : columns.entrySet()) {
            BigDecimal sum = sums.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (sum.compareTo(entry.getValue()) != 0) {
                problems.add(entry.getKey() + " 明细之和=" + sum.toPlainString()
                        + " ≠ 分量列=" + entry.getValue().toPlainString());
            }
        }
        for (ExposureDetail detail : details) {
            if (!COMPONENT_TYPES.contains(detail.componentType)) {
                problems.add("明细分量类型越域：" + detail.componentType);
            }
            if (!SOURCE_TYPES.contains(detail.sourceType)) {
                problems.add("明细来源类型越域：" + detail.sourceType);
            }
            if (!exposure.direction.equals(detail.direction)) {
                problems.add("明细方向与敞口头不一致：" + detail.direction + " ≠ " + exposure.direction);
            }
            if (detail.contributedQty == null) {
                problems.add("明细贡献量必填（sourceId=" + detail.sourceId + "）");
            }
        }
        if (!problems.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "敞口构成明细与分量列不一致，拒绝写入（SUM(明细)=分量列 为结构保证）：\n  - "
                            + String.join("\n  - ", problems));
        }
    }

    private static Map<String, BigDecimal> sumByComponent(List<ExposureDetail> details) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (ExposureDetail detail : details) {
            BigDecimal value = nz(detail.contributedQty);
            sums.merge(detail.componentType, value, BigDecimal::add);
        }
        return sums;
    }

    /**
     * 现货品类列**双态**归一（{@code CH-008}）。
     *
     * @param exposure 敞口（{@code unmappedFlag} 已确定）
     */
    private static void normalizeSpotCategory(Exposure exposure) {
        if ("Y".equals(exposure.unmappedFlag)) {
            if (MAPPED_CATEGORY_PLACEHOLDER.equals(exposure.spotCategoryCode)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "未映射行必须填**真实现货品类**，不得填占位常量 " + MAPPED_CATEGORY_PLACEHOLDER
                                + "（CH-008 双态）：subAccountId=" + exposure.subAccountId
                                + "；复现=填入来源单据的 spot_category_code");
            }
            return;
        }
        exposure.spotCategoryCode = MAPPED_CATEGORY_PLACEHOLDER;
    }

    /** 估值：缺价按用途处置并标注 {@code price_as_of} 与非当日价标记。 */
    private void applyValuation(Exposure exposure) {
        if ("Y".equals(exposure.unmappedFlag) || exposure.productId == UNMAPPED_PRODUCT_SENTINEL) {
            exposure.priceMissingFlag = "Y";
            exposure.exposureAmount = null;
            exposure.priceAsOf = null;
            exposure.nonCurrentPriceFlag = "N";
            return;
        }
        SpotPrice price;
        try {
            price = resolvePrice(exposure.productId, exposure.asOfDate, PricePurpose.VALUATION);
        } catch (BizException e) {
            exposure.priceMissingFlag = "Y";
            exposure.exposureAmount = null;
            exposure.priceAsOf = null;
            exposure.nonCurrentPriceFlag = "N";
            return;
        }
        exposure.priceMissingFlag = "N";
        exposure.exposureAmount = exposure.exposureQty.multiply(price.spotPrice);
        exposure.priceAsOf = price.priceDate;
        exposure.nonCurrentPriceFlag =
                price.priceDate.isBefore(exposure.asOfDate) ? "Y" : "N";
    }


    private Long mapProduct(String spotCategoryCode, String direction) {
        ProductSpotCategory mapping =
                productSpotCategoryDao.findPrimaryMapping(spotCategoryCode, direction);
        return mapping == null ? null : mapping.productId;
    }

    private void writeLog(String sourceType, long sourceId, String changeType, String before,
                          String after, int subAccountId, long operatorId, String reason) {
        SpotChangeLog log = new SpotChangeLog();
        log.sourceType = sourceType;
        log.sourceId = sourceId;
        log.changeType = changeType;
        log.beforeValue = before;
        log.afterValue = after;
        log.subAccountId = subAccountId;
        log.operatorId = operatorId;
        log.changeTime = Clocks.now();
        log.recalcTriggered = "N";
        log.changeReason = reason;
        spotChangeLogDao.insert(log, operatorId);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 计划期是否与既有非关闭计划重叠（{@code FR-SPOT-8} 验收 3：提示重复，不阻断）。 */
    private boolean hasOverlappingPlan(ProductionPlan plan) {
        if (productionPlanDao == null) {
            return false;
        }
        List<Object> args = new ArrayList<>(Arrays.asList(plan.subAccountId, "CLOSED",
                plan.planPeriodEnd, plan.planPeriodStart));
        return !productionPlanDao.findPage(PageQuery.of("1", "10", null, "id"),
                "sub_account_id = ? AND status <> ? AND plan_period_start <= ? AND plan_period_end >= ?",
                args).items().isEmpty();
    }

    /** 逐页收集全部行（导出用；避免单页 {@code size} 上限造成静默截断）。 */
    private static <T> List<T> collectAll(BaseDao<T, Long> dao, String where, List<Object> args,
                                          String sortField) {
        List<T> all = new ArrayList<>();
        int page = 1;
        while (true) {
            PageResult<T> result = dao.findPage(
                    PageQuery.of(String.valueOf(page), "100", null, sortField), where, args);
            all.addAll(result.items());
            if (result.items().isEmpty() || all.size() >= result.total()) {
                break;
            }
            page++;
        }
        return all;
    }

    /** 取单据当前归属子账户（不存在返回 {@code null}）。 */
    private Integer docSubAccountId(String docType, long docId) {
        switch (docType) {
            case "PURCHASE_CONTRACT": {
                PurchaseContract c = purchaseContractDao.findById(docId);
                return c == null ? null : c.subAccountId;
            }
            case "SALES_CONTRACT": {
                SalesContract c = salesContractDao.findById(docId);
                return c == null ? null : c.subAccountId;
            }
            case "PRODUCTION_PLAN": {
                ProductionPlan p = productionPlanDao.findById(docId);
                return p == null ? null : p.subAccountId;
            }
            default:
                return null;
        }
    }

    /** 落库单据的子账户归属（{@code FR-SPOT-16}）。 */
    private void applyDocSubAccount(String docType, long docId, int target, long operatorId) {
        switch (docType) {
            case "PURCHASE_CONTRACT": {
                PurchaseContract c = purchaseContractDao.findById(docId);
                c.subAccountId = target;
                if (purchaseContractDao.updateById(c, operatorId) != 1) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "采购合同并发冲突：id=" + docId + "；复现=重新读取后再提交");
                }
                break;
            }
            case "SALES_CONTRACT": {
                SalesContract c = salesContractDao.findById(docId);
                c.subAccountId = target;
                if (salesContractDao.updateById(c, operatorId) != 1) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "销售合同并发冲突：id=" + docId + "；复现=重新读取后再提交");
                }
                break;
            }
            case "PRODUCTION_PLAN": {
                ProductionPlan p = productionPlanDao.findById(docId);
                p.subAccountId = target;
                if (productionPlanDao.updateById(p, operatorId) != 1) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "生产计划并发冲突：id=" + docId + "；复现=重新读取后再提交");
                }
                break;
            }
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID, "未知单据类型：" + docType);
        }
    }

    /** 单据贡献的敞口中已被生效计划引用（{@code coveredQty > 0}）的敞口 ID 清单。 */
    private List<Long> referencedExposures(String docType, long docId) {
        List<Long> ids = new ArrayList<>();
        for (ExposureDetail detail : exposureDetailDao.findBySource(docType, docId)) {
            Exposure exposure = exposureDao.findById(detail.exposureId);
            if (exposure != null && nz(exposure.coveredQty).compareTo(BigDecimal.ZERO) > 0
                    && !ids.contains(exposure.id)) {
                ids.add(exposure.id);
            }
        }
        return ids;
    }

    /**
     * 反查引用这些敞口的<b>计划</b>（{@code FR-SPOT-16} 验收 2 拒因按计划给出）。
     *
     * <p>经 {@code t_hedge_plan_exposure}（{@code exposure_id} 反查 {@code hedge_plan_id}）；
     * {@link HedgePlanExposureDao} 未装配（既有单测）时返回空列表，调用方据此回退为仅列敞口。
     */
    private List<Long> referencingPlans(List<Long> exposureIds) {
        List<Long> plans = new ArrayList<>();
        if (hedgePlanExposureDao == null || exposureIds == null || exposureIds.isEmpty()) {
            return plans;
        }
        for (Long exposureId : exposureIds) {
            for (HedgePlanExposure row : collectAll(hedgePlanExposureDao, "exposure_id = ?",
                    Arrays.asList(exposureId), "id")) {
                if (row.hedgePlanId != null && !plans.contains(row.hedgePlanId)) {
                    plans.add(row.hedgePlanId);
                }
            }
        }
        return plans;
    }

    /**
     * 子账户<b>读面可见性强制</b>（{@code NFR-SEC-7}；{@code TE-T109-002}；{@code FR-AUTH-13} 验收 1）。
     *
     * <p><b>仅用于只读面</b>（如导出 {@link #exportExposuresCsv}）：持有跨子账户只读
     * （{@code CROSS_READONLY}）者可查看（含导出）全部子账户数据 ⇒ 放行。写动作一律走
     * {@link #requireSubAccountWritable}（{@code T-115}）。
     *
     * <p><b>fail-closed</b>：可见性取数入口缺位（{@code null}）时一律拒绝（{@code 10004}），
     * 绝不退化为"不校验 = 放行"。判据由 {@link SubAccountVisibility#assertSubAccountVisible} 给出
     * （生产装配下即 {@link AuthService} 的带留痕实现）。
     *
     * @param operatorId   调用者
     * @param subAccountId 目标子账户（{@code null} 视为不可见）
     */
    private void requireSubAccountVisible(long operatorId, Integer subAccountId) {
        if (subAccountVisibility == null) {
            throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                    "子账户可见性取数入口缺位，按安全默认拒绝（NFR-SEC-7）：subAccountId=" + subAccountId
                            + "；复现=核对装配注入 SubAccountVisibility");
        }
        if (subAccountId == null) {
            throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                    "目标数据未携带子账户标识，按安全默认拒绝（NFR-SEC-7）："
                            + "operatorId=" + operatorId + "；复现=核对目标行 sub_account_id 非空");
        }
        subAccountVisibility.assertSubAccountVisible(operatorId, subAccountId.intValue());
    }

    /**
     * 子账户<b>写面强制</b>（{@code NFR-SEC-7} 写路径强制点；{@code T-110}/{@code T-109}；
     * {@code T-115}）。
     *
     * <p><b>fail-closed</b>：可见性取数入口缺位（{@code null}）时一律拒绝（{@code 10004}），
     * 绝不退化为"不校验 = 放行"。判据由 {@link SubAccountVisibility#assertSubAccountWritable} 给出
     * （{@code T-115}）：<b>仅含哨兵（跨子账户只读 {@code CROSS_READONLY}）不得写</b>
     * （{@code FR-AUTH-13} 验收 3；{@code DATA_MODEL.md}：{@code CROSS_READONLY} 只读性由服务层保证）。
     *
     * <p><b>{@code null} 子账户一律拒绝</b>（同 {@code PlanService.requireWritablePlan} 口径，
     * {@code R-PLAN-7}）：按主键取回的单据若未携带子账户标识（DB 列 {@code NOT NULL} 约束被破坏 /
     * 数据异常），不得因无法判定归属而放行，须 {@code 10004} 明确拒绝。
     *
     * @param operatorId   调用者
     * @param subAccountId 目标子账户（{@code null} 视为不可写）
     */
    private void requireSubAccountWritable(long operatorId, Integer subAccountId) {
        if (subAccountVisibility == null) {
            throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                    "子账户可见性取数入口缺位，按安全默认拒绝写操作（NFR-SEC-7）：subAccountId=" + subAccountId
                            + "；复现=核对装配注入 SubAccountVisibility");
        }
        if (subAccountId == null) {
            throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                    "目标数据未携带子账户标识，按安全默认拒绝写操作（NFR-SEC-7 / R-PLAN-7）："
                            + "operatorId=" + operatorId + "；复现=核对目标行 sub_account_id 非空");
        }
        subAccountVisibility.assertSubAccountWritable(operatorId, subAccountId.intValue());
    }

    private static String text(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String text(Long value) {
        return value == null ? "" : value.toString();
    }

    private static String text(Integer value) {
        return value == null ? "" : value.toString();
    }

    /** CSV 单元格转义（含逗号 / 引号 / 换行时加引号）。 */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void requireSubAccount(int subAccountId) {
        if (subAccountId <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "子账户必须为真实子账户（> 0；哨兵值 0 仅用于账户级作用域列）：实际=" + subAccountId
                            + "；复现=传入真实子账户 ID（DATA_MODEL.md §0.2）");
        }
    }

    private static void requireText(String value, String field) {
        if (isBlank(value)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "必填字段为空：" + field + "；复现=补齐该字段后重新提交");
        }
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "字段必须 > 0：" + field + " 实际=" + value + "；复现=填写正数");
        }
    }

    private static void requirePeriod(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "合同起止日期必填；复现=补齐 contract_start_date / contract_end_date");
        }
        if (end.isBefore(start)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "合同截止日不得早于起始日：start=" + start + " end=" + end + "；复现=调整日期");
        }
    }
}
