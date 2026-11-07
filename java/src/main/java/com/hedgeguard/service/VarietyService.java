package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductChangeLogDao;
import com.hedgeguard.dao.ProductContractDao;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.ProductQuotaDao;
import com.hedgeguard.dao.ProductSpotCategoryDao;
import com.hedgeguard.dao.ProductUnitDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.SubInstrumentQuotaDao;
import com.hedgeguard.dao.SubProductWhitelistDao;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductChangeLog;
import com.hedgeguard.dao.entity.ProductContract;
import com.hedgeguard.dao.entity.ProductQuota;
import com.hedgeguard.dao.entity.ProductSpotCategory;
import com.hedgeguard.dao.entity.ProductUnit;
import com.hedgeguard.dao.entity.SubInstrumentQuota;
import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * 品种管理与白名单 / 三层额度服务（{@code T-012}）。
 *
 * <p>依据：{@code FR-PRODUCT-1~14}、{@code R-PRODUCT-3/4/5/6/7/9}、{@code CFG-16}、
 * {@code DATA_MODEL.md} §2.3（{@code TB-PRODUCT-1~8}）、{@code ARCHITECTURE.md} §4.6.3
 * （三层限额：层① 子账户+品种 / 层② 子账户+合约 / 层③ 账户+品种）。
 *
 * <p>四条结构纪律的落点：
 * <ol>
 *   <li><b>品种一份，白名单与额度多份</b>（{@code R-PRODUCT-7}）：品种档案为全局共享，不带子账户；
 *       白名单与三层额度**必须**携带子账户（层③取哨兵值 {@code 0}）；</li>
 *   <li><b>额度不可跨品种、跨子账户串用</b>（{@code R-PRODUCT-4}）：本类不提供任何"转移/借用"入口，
 *       且写入前按 {@code limit_layer} 校验哨兵值联动；</li>
 *   <li><b>额度是"防串用"的配置，不是判定权威源</b>（{@code R-RISK-9}）：{@code used_qty}/{@code frozen_qty}
 *       仅为库内投影，本类<b>不</b>据此做任何判定；</li>
 *   <li><b>下发状态显式建模</b>（{@code R-PRODUCT-9}）：白名单 / 额度的每次变更置
 *       {@code dispatch_status='PENDING'}，M1 只落库占位，M2 由 {@code ParamDispatchServiceImpl} 回填。</li>
 * </ol>
 *
 * <p><b>子账户写面强制</b>（{@code NFR-SEC-7} / {@code T-112}/{@code T-115}）：白名单（{@code FR-PRODUCT-12}）与
 * 层①/层②额度（{@code FR-PRODUCT-11}）是<b>子账户级配置</b>（{@code R-PRODUCT-7}），写面强制调用者
 * 持该子账户的<b>限定（{@code LIMITED}）写授权</b>（{@link #requireSubAccountWritable}，越权/跨子账户只读
 * {@code 10004}）。<b>层③</b>
 * （{@code ACCOUNT_PRODUCT}）的有效目标是账户哨兵 {@code 0}、<b>非子账户行级归属</b> ⇒ 不适用本强制
 * （否则会把账户级配置误拒，见 {@link #putQuota} 注释）。品种档案 / 换算关系 / 合约 / 现货映射等
 * <b>全局共享</b>对象（{@code R-PRODUCT-7}「品种一份」）不带子账户维度 ⇒ 同样不适用。
 *
 * <p><b>子账户读取面强制</b>（{@code NFR-SEC-7} / {@code T-105}/{@code T-106}）：列表 / 导出（{@code T-105}）
 * 与品种详情（{@code T-106}）经 {@code visibleSubAccountIds} → {@code SubAccountScope} 施加过滤
 * （含哨兵 {@code 0} ⇒ 不加过滤；空集 ⇒ 恒假）；三层额度全景则对<b>入参子账户</b>做读取面可见性断言
 * （{@link #requireSubAccountVisible}，越权 {@code 10004}）。
 *
 * <p><b>全局共享对象写面强制</b>（{@code FR-AUTH-13} 处理规则 / {@code NFR-SEC-7} / {@code R-AUTH-8}；
 * {@code T-118}）：品种档案（{@code createProduct}/{@code disableProduct}/{@code enableProduct}）、
 * 换算关系（{@code putUnit}）、合约关联（{@code putContract}）、现货映射（{@code putSpotMapping}）与
 * CSV 导入（{@code importProducts}）的目标均为<b>全局共享对象</b>（{@code R-PRODUCT-7}「品种一份」）、
 * <b>非子账户行级归属</b> ⇒ 其写面授权归<b>功能权限</b>维度（{@code FR-AUTH-12}「数据权限与功能权限独立」；
 * {@code CH-012}），<b>不得</b>套用子账户可见性（{@link #requireSubAccountWritable}）——
 * 后者对全局对象无子账户可锚，套用即误拒。判据入口复用 {@link AccountLevelConfigAuthorizer}
 * （主体 = 财务/风控岗 {@code FIN_RISK} 或系统管理员 {@code SYS_ADMIN}；失败码 {@code 10003} +
 * {@code R-AUTH-8} 留痕），见 {@link #requireGlobalObjectWriteAuthority}。
 *
 * <p><b>CSV 导入</b>（{@code T-012} 验收 3）：<b>全量预校验通过后一次性插入</b>；任一行不合格 →
 * 汇总**全部**错误后抛 {@code 10019}，且<b>不产生任何部分写入</b>（不在报错前逐行落库）。
 */
public final class VarietyService {

    /** 品种分类取值域（{@code CFG-02c}；90% / 80% 规模红线按此分派）。 */
    public static final Set<String> CATEGORIES =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("COMMODITY", "TRADE")));

    /** 限额层级取值域（{@code TB-PRODUCT-6 / TB-PRODUCT-8}）。 */
    public static final Set<String> LIMIT_LAYERS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("SUB_PRODUCT", "SUB_INSTRUMENT", "ACCOUNT_PRODUCT")));

    /** 额度单位取值域。 */
    public static final Set<String> QUOTA_UNITS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("LOT", "TON")));

    /** 合约类型取值域（期权与期货共用同一套保额度，{@code R-PLAN-11}）。 */
    public static final Set<String> INSTRUMENT_TYPES =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("FUTURES", "OPTION")));

    /** 现货映射方向取值域。 */
    public static final Set<String> MAPPING_DIRECTIONS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("PURCHASE", "SALES", "BOTH")));

    /** 品种 CSV 导入表头（逐列固定；缺列即 {@code 10019}）。 */
    public static final List<String> CSV_HEADER = Collections.unmodifiableList(
            Arrays.asList("product_code", "product_name", "category", "currency", "remark"));

    /** 品种列表缺省排序字段（{@code FR-PRODUCT-5} 处理规则「默认按创建时间倒序」）。 */
    public static final String DEFAULT_SORT_FIELD = "created_time";

    /**
     * 品种列表排序字段白名单（防无效列名 / 防注入；{@code T-105} 验收 2）。
     *
     * <p>缺省方向恒 {@code ASC}（{@link PageQuery}），故缺省排序须由 handler 以
     * {@code "created_time,desc"} 显式声明方向。
     */
    public static final Set<String> PRODUCT_SORT_FIELDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "created_time", "product_code", "product_name", "status", "category")));

    /** 品种状态取值域（{@code ck_product_status}；无 {@code DELETED}：品种不允许物理删除）。 */
    public static final Set<String> PRODUCT_STATUSES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("ENABLED", "DISABLED")));

    /** 变更类型取值域（{@code FR-PRODUCT-14}「新建/修改/启用/停用」；{@code ck_product_chg_type}）。 */
    public static final Set<String> CHANGE_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("CREATE", "UPDATE", "ENABLE", "DISABLE")));

    /** 白名单在册状态（{@code TB-PRODUCT-5}：移出白名单 = 置 {@code DISABLED}，保留历史）。 */
    private static final String WHITELIST_ENABLED = "ENABLED";

    /** 导出/遍历页长（{@link PageQuery#ALLOWED_SIZES} 白名单最大值：导出不受分页上限约束）。 */
    private static final int EXPORT_PAGE_SIZE = 100;

    /** 变更历史排序（{@code FR-PRODUCT-14} 输出列「按时间倒序」）。 */
    private static final String CHANGE_LOG_SORT = "change_time,desc";

    /** 品种编码格式（ASCII 大写字母/数字/连字符，{@code R-PRODUCT-3}）。 */
    private static final Pattern PRODUCT_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_\\-]{1,31}");

    /** 本项目唯一计价币种（{@code DATA_MODEL.md} §2.3 TB-PRODUCT-1）。 */
    private static final String CURRENCY_CNY = "CNY";

    private final TxTemplate tx;
    private final ProductDao productDao;
    private final ProductUnitDao productUnitDao;
    private final ProductContractDao productContractDao;
    private final ProductSpotCategoryDao productSpotCategoryDao;
    private final SubProductWhitelistDao subProductWhitelistDao;
    private final ProductQuotaDao productQuotaDao;
    private final SubInstrumentQuotaDao subInstrumentQuotaDao;
    private final ProductChangeLogDao productChangeLogDao;
    private final SubAccountVisibility subAccountVisibility;
    private final AccountLevelConfigAuthorizer accountLevelConfigAuthorizer;

    /**
     * 构造（不含账户级配置权入口；层③ 写面按 <b>fail-closed</b> 处理）。
     *
     * <p>既有 10 参构造<b>保留并委托</b> 11 参构造（{@code accountLevelConfigAuthorizer=null}），
     * 语义：<b>层③ 账户级额度写入一律拒绝</b>（{@code 10003}）——入口缺位即不校验决不允许退化为放行
     * （与 {@link SubAccountVisibility} 缺位时同姿态）。生产装配（{@code Bootstrap}）与端到端用例
     * 须走 11 参构造注入真实 {@link AuthService}。
     *
     * @param tx                     事务模板
     * @param productDao             品种 DAO
     * @param productUnitDao         计量单位 DAO
     * @param productContractDao     合约关联 DAO
     * @param productSpotCategoryDao 现货品类映射 DAO
     * @param subProductWhitelistDao 子账户白名单 DAO
     * @param productQuotaDao        层①/层③额度 DAO
     * @param subInstrumentQuotaDao  层②合约额度 DAO
     * @param productChangeLogDao    品种变更留痕 DAO
     * @param subAccountVisibility   子账户可见性取数 / 写面断言入口（{@code NFR-SEC-7}；{@code T-112}）
     */
    public VarietyService(TxTemplate tx, ProductDao productDao, ProductUnitDao productUnitDao,
                          ProductContractDao productContractDao,
                          ProductSpotCategoryDao productSpotCategoryDao,
                          SubProductWhitelistDao subProductWhitelistDao, ProductQuotaDao productQuotaDao,
                          SubInstrumentQuotaDao subInstrumentQuotaDao,
                          ProductChangeLogDao productChangeLogDao,
                          SubAccountVisibility subAccountVisibility) {
        this(tx, productDao, productUnitDao, productContractDao, productSpotCategoryDao,
                subProductWhitelistDao, productQuotaDao, subInstrumentQuotaDao, productChangeLogDao,
                subAccountVisibility, null);
    }

    /**
     * 构造（含账户级写面授权入口）。
     *
     * @param tx                          事务模板
     * @param productDao                  品种 DAO
     * @param productUnitDao              计量单位 DAO
     * @param productContractDao          合约关联 DAO
     * @param productSpotCategoryDao      现货品类映射 DAO
     * @param subProductWhitelistDao      子账户白名单 DAO
     * @param productQuotaDao             层①/层③额度 DAO
     * @param subInstrumentQuotaDao       层②合约额度 DAO
     * @param productChangeLogDao         品种变更留痕 DAO
     * @param subAccountVisibility        子账户可见性取数 / 写面断言入口（{@code NFR-SEC-7}；{@code T-112}）
     * @param accountLevelConfigAuthorizer 账户级（层③）配置权断言入口（{@code CH-012}；{@code T-119}）；
     *                                    {@code null} ⇒ 层③ 写入 fail-closed 拒绝
     */
    public VarietyService(TxTemplate tx, ProductDao productDao, ProductUnitDao productUnitDao,
                          ProductContractDao productContractDao,
                          ProductSpotCategoryDao productSpotCategoryDao,
                          SubProductWhitelistDao subProductWhitelistDao, ProductQuotaDao productQuotaDao,
                          SubInstrumentQuotaDao subInstrumentQuotaDao,
                          ProductChangeLogDao productChangeLogDao,
                          SubAccountVisibility subAccountVisibility,
                          AccountLevelConfigAuthorizer accountLevelConfigAuthorizer) {
        this.tx = tx;
        this.productDao = productDao;
        this.productUnitDao = productUnitDao;
        this.productContractDao = productContractDao;
        this.productSpotCategoryDao = productSpotCategoryDao;
        this.subProductWhitelistDao = subProductWhitelistDao;
        this.productQuotaDao = productQuotaDao;
        this.subInstrumentQuotaDao = subInstrumentQuotaDao;
        this.productChangeLogDao = productChangeLogDao;
        this.subAccountVisibility = subAccountVisibility;
        this.accountLevelConfigAuthorizer = accountLevelConfigAuthorizer;
    }

    // ------------------------------------------------------------ 品种档案

    /**
     * 按品种取合约关联（{@code FR-PRODUCT-9}）。
     *
     * @param productId 品种
     * @return 列表（无数据返回空列表）
     */
    public List<ProductContract> listContracts(long productId) {
        return productContractDao.findByProduct(productId);
    }

    /**
     * 按品种取现货品类映射（{@code FR-PRODUCT-10}）。
     *
     * @param productId 品种
     * @return 列表（无数据返回空列表）
     */
    public List<ProductSpotCategory> listSpotMappings(long productId) {
        return productSpotCategoryDao.findByProduct(productId);
    }

    /**
     * 按品种取变更历史（倒序；{@code FR-PRODUCT-14} 验收 1）。
     *
     * @param productId 品种
     * @return 列表（无数据返回空列表）
     */
    public List<ProductChangeLog> listChangeLogs(long productId) {
        return productChangeLogDao.findByProduct(productId);
    }

    /**
     * 按品种取变更历史 + <b>筛选</b>（{@code FR-PRODUCT-14} 输入列
     * {@code docs/REQUIREMENTS/PRODUCT.md:279}「品种标识、时间范围、变更类型筛选（新建/修改/启用/停用）」；
     * {@code T-116} 验收 7）。
     *
     * <p><b>下推</b>：{@code WHERE product_id = ? [AND change_time >= ?] [AND change_time <= ?]
     * [AND change_type IN (?,...)]}，经 {@link ProductChangeLogDao#findPage}（{@code BaseDao} 冻结面）
     * 执行——筛选在 SQL 层生效，不是取全量后内存过滤。时间边界均为<b>闭区间</b>
     * （{@code >=} / {@code <=}），与 {@code FR-PRODUCT-14}「时间范围」的审计口径一致。
     *
     * <p><b>排序</b>：{@code change_time DESC}（{@code FR-PRODUCT-14} 输出列「按时间倒序」），
     * 装配层另补主键 {@code id ASC} 收官构成全序（{@code T-096}）。
     *
     * <p><b>无筛选</b>（{@code null} 或空）⇒ 走原路径 {@link ProductChangeLogDao#findByProduct}
     * （{@code change_time DESC}，行为与 {@code T-012} 交付时一致，不改变既有语义）。
     *
     * @param productId 品种
     * @param filter    筛选（{@code null} ⇒ 不筛）
     * @return 变更历史（按时间倒序；无命中返回空列表）
     */
    public List<ProductChangeLog> listChangeLogs(long productId, ChangeLogFilter filter) {
        if (filter == null || filter.isEmpty()) {
            return productChangeLogDao.findByProduct(productId);
        }
        ProductWhere where = changeLogWhere(productId, filter);
        List<ProductChangeLog> out = new ArrayList<>();
        int page = 1;
        while (true) {
            PageResult<ProductChangeLog> pr =
                    productChangeLogDao.findPage(
                            pageQuery(page, CHANGE_LOG_SORT, "change_time"), where.where, where.args);
            out.addAll(pr.items());
            if (pr.items().isEmpty() || out.size() >= pr.total()) {
                return out;
            }
            page++;
        }
    }

    /**
     * 查询品种列表（筛选后分页 + 排序；{@code FR-PRODUCT-5} 验收 2/3）。
     *
     * <p><b>缺省排序</b>：{@code created_time} 倒序（{@code FR-PRODUCT-5} 处理规则）；排序字段须在
     * {@link #PRODUCT_SORT_FIELDS} 白名单内。方向缺省与 {@code PageQuery} 一致恒 {@code ASC}，故
     * handler 须以 {@code "created_time,desc"} 显式声明缺省方向（{@code BUG-001} 教训）。
     *
     * <p><b>子账户隔离（服务端强制点）</b>：由 {@code operatorId}（会话身份，不接受请求体传入）解析
     * 可见子账户集合后施加过滤（{@code ARCHITECTURE.md} §8.2 / {@code NFR-SEC-7}）：
     * <ul>
     *   <li>含哨兵 {@code 0}（跨账户只读）⇒ <b>不加过滤</b>（管理层 / 审计岗可跨子账户查看）；</li>
     *   <li>否则经 {@code t_sub_product_whitelist} 构造 {@code sub_account_id IN (...)} 过滤，且<b>仅取
     *       在册行</b>（{@code status = 'ENABLED'}；移出 = 置 {@code DISABLED} 保留历史 ⇒ 已移出品种不可见，
     *       {@code T-117}）；
     *       <b>空集 ⇒ 恒假条件（返回空），不得退化为全可见</b>；{@code null} 取数入口 ⇒ 按空集 fail-closed。</li>
     * </ul>
     * {@code total} 与 {@code items} 均按过滤后结果计（越权品种既不入 {@code items}，也不计入 {@code total}）。
     *
     * @param operatorId 调用者（用于解析可见子账户；由会话决定）
     * @param query      分页 / 排序
     * @return 分页结果（空结果 {@code items} 空、{@code total}=0）
     */
    public PageResult<Product> listProducts(long operatorId, PageQuery query) {
        return listProducts(operatorId, query, ProductFilter.none());
    }

    /**
     * 品种列表（分页 + 排序 + 子账户隔离 + <b>筛选</b>；{@code FR-PRODUCT-5} 输入列
     * {@code docs/REQUIREMENTS/PRODUCT.md:148}「筛选条件（品种编码/名称关键字、状态、分类、子账户）」；
     * {@code T-116} 验收 1）。
     *
     * <p><b>筛选下推（{@code T-116} 验收 1 的判据）</b>：本方法只调用 DAO 的<b>分页入口</b>
     * （{@code findPage} / {@code findPageScoped}），筛选条件以 {@code extraWhere + 占位符参数}
     * 形式交给 {@link ProductDao}；{@code AbstractDmDao#findPage} 内部<b>分别执行 COUNT 与 SELECT
     * 两条 SQL</b>且两条都用同一 {@code WHERE} ⇒ 筛选同时下推到<b>计数</b>与<b>取数</b>。
     * 若只筛 SELECT 不筛 COUNT，{@code total} 会大于 {@code items} 实际可翻页总数（分页错乱），
     * 故用例断言落在 {@code total} 上（{@code T-116} 验收 4 ①③）。
     *
     * <p><b>筛选项口径</b>（逐项下推，多个条件为 AND）：
     * <ul>
     *   <li>{@code productCode} / {@code productName}：关键字，下推为
     *       {@code product_code LIKE ?} / {@code product_name LIKE ?}（模式串 {@code %关键字%}，
     *       以<b>绑定参数</b>传入，不拼字符串 ⇒ 无注入面；{@code %}/{@code _} 仍具 LIKE 通配含义，
     *       与 SQL 语义一致）；</li>
     *   <li>{@code status}：{@code ENABLED} / {@code DISABLED}（越域 ⇒ {@code 10001}），
     *       下推为 {@code status = ?}；</li>
     *   <li>{@code category}：{@code COMMODITY} / {@code TRADE}（越域 ⇒ {@code 10001}），
     *       下推为 {@code category = ?}；</li>
     *   <li>{@code subAccountId}：<b>先经白名单取数</b>（{@link SubProductWhitelistDao#findBySubAccount}，
     *       仅 {@code status=ENABLED} 的在册行）解析为该子账户的在册品种 ID 集，再下推为
     *       {@code id IN (?,...)}；<b>空集 ⇒ 恒假条件 {@code id IN (-1)}（返回空），不得退化为不过滤</b>。
     *       <b>为何不用子查询</b>：{@code t_product} 无 {@code sub_account_id} 列，子查询形态
     *       {@code id IN (SELECT product_id ...)} 属 {@code T-152} 已记录的
     *       {@code InMemoryDmEngine} 静默错值形态（测试不可判）⇒ 改为「先解析再 IN 列表」，
     *       语义等价且可测。</li>
     * </ul>
     *
     * @param operatorId 调用者（用于解析可见子账户；由会话决定）
     * @param query      分页 / 排序
     * @param filter     筛选（{@code null} 或 {@link ProductFilter#none()} ⇒ 不筛）
     * @return 分页结果（{@code total} 为叠加子账户隔离与筛选后的总行数）
     */
    public PageResult<Product> listProducts(long operatorId, PageQuery query, ProductFilter filter) {
        requireSortFieldsAllowed(query, PRODUCT_SORT_FIELDS);
        ProductWhere where = productWhere(filter);
        List<Integer> visible = visibleSubAccountIds(operatorId);
        if (visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            // 跨账户只读（管理层 / 审计岗）：不构造 scope、不加过滤（筛选仍下推）
            return productDao.findPage(query, where.where, where.args);
        }
        return productDao.findPageScoped(query,
                SubAccountScope.of("sub_account_id", visible), where.where, where.args);
    }

    /**
     * 按主键取品种（<b>不</b>施加子账户隔离；仅供创建后回读等内部路径）。
     *
     * <p><b>读取面（HTTP 详情）须用</b> {@link #getProduct(long, long)}：本方法不过滤可见范围，
     * 直接暴露会构成按 id 越权读取（{@code NFR-SEC-7}）。
     *
     * @param productId 品种
     * @return 品种；不存在返回 {@code null}
     */
    public Product getProduct(long productId) {
        return productDao.findById(productId);
    }

    /**
     * 按主键取品种 + <b>调用者可见范围过滤</b>（{@code FR-PRODUCT-6} 详情 / {@code NFR-SEC-7} /
     * {@code T-106}）。
     *
     * <p>可见集来自<b>会话身份</b>（{@link #visibleSubAccountIds}），不由请求参数决定：
     * <ul>
     *   <li>含哨兵 {@link SubAccountScope#ALL_ACCOUNT_SENTINEL}（跨账户只读）⇒ <b>不加过滤</b>
     *       （管理层 / 审计岗可跨子账户查看）；</li>
     *   <li>否则经 {@link ProductDao#findByIdScoped} 注入白名单子查询
     *       （{@code id IN (SELECT product_id FROM t_sub_product_whitelist WHERE sub_account_id IN (...)
     *       AND status = 'ENABLED')}；仅<b>在册</b>行算可见，{@code T-117}）；
     *       空集 ⇒ 恒假（返回 {@code null}）。</li>
     * </ul>
     * <b>不可见 ≡ 不存在</b>（同一返回 {@code null}，不泄露存在性，{@code T-100}/{@code T-107} 口径）；
     * 由 handler 映射为<b>统一错误</b>（{@code FR-PRODUCT-6} 处理规则「无权限或不存在时返回统一错误」）。
     *
     * @param productId  品种
     * @param operatorId 调用者（用于解析可见子账户；由会话决定，不接受请求参数传入）
     * @return 品种；不存在或不可见均返回 {@code null}
     */
    public Product getProduct(long productId, long operatorId) {
        List<Integer> visible = visibleSubAccountIds(operatorId);
        if (visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            // 跨账户只读（管理层 / 审计岗）：不构造 scope、不加过滤
            return productDao.findById(productId);
        }
        return productDao.findByIdScoped(productId, SubAccountScope.of("sub_account_id", visible));
    }

    /**
     * 新增品种（{@code FR-PRODUCT-1}）。
     *
     * <p>强制项：{@code is_domestic_exchange='Y'}`（境外/场外永久排除，{@code R-TRADE-16}）、
     * {@code currency='CNY'}`、初始状态 {@code ENABLED}`。
     *
     * @param product    品种（{@code productCode}/{@code productName}/{@code category} 必填）
     * @param operatorId 操作人
     * @return 新行主键
     */
    public long createProduct(Product product, long operatorId) {
        requireGlobalObjectWriteAuthority(operatorId, "新增品种");
        String code = requireText(product.productCode, "product_code");
        if (!PRODUCT_CODE.matcher(code).matches()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种编码格式非法（允许大写字母/数字/下划线/连字符，2–32 位）：" + code
                            + "；复现=按 R-PRODUCT-3 编码规则提交");
        }
        requireText(product.productName, "product_name");
        requireCategory(product.category);
        product.categoryManaged = product.categoryManaged == null ? "Y" : product.categoryManaged;
        product.isDomesticExchange = "Y";
        product.currency = CURRENCY_CNY;
        product.status = "ENABLED";
        if (productDao.findByCode(code) != null) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "品种编码已存在（全系统唯一，不可修改）：" + code + "；复现=换编码或查询现有品种");
        }
        int affected = tx.inTransaction(connection -> {
            int n = productDao.insert(product, operatorId);
            writeLog(product.id, "CREATE", null, null, null, null, operatorId,
                    "新增品种：" + code);
            return n;
        });
        if (affected != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "品种新增影响行数异常：" + affected + "；复现=核对 t_product 约束（含 ck_product_domestic）");
        }
        return product.id;
    }

    /**
     * 停用品种（{@code FR-PRODUCT-3}：状态置 {@code DISABLED} + **白名单移出** + 待下发）。
     *
     * <p>品种**不允许物理删除**（{@code status} 无 {@code DELETED}）：停用后历史数据保留，
     * 且级联把该品种在各子账户白名单中的行一并置 {@code DISABLED}（保留历史、待下发 C++）。
     *
     * @param productId  品种
     * @param reason     停用原因（必填）
     * @param operatorId 操作人
     * @return 级联移出的白名单行数
     */
    public int disableProduct(long productId, String reason, long operatorId) {
        requireGlobalObjectWriteAuthority(operatorId, "停用品种");
        requireText(reason, "disabled_reason");
        Product product = productDao.findById(productId);
        if (product == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种不存在：id=" + productId + "；复现=按 product_code 查询确认 ID");
        }
        if ("DISABLED".equals(product.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "品种已停用，无需重复操作：id=" + productId + "；复现=查询 status 列");
        }
        List<SubProductWhitelist> whitelist = subProductWhitelistDao.findByProduct(productId);
        int removed = 0;
        for (SubProductWhitelist row : whitelist) {
            if ("ENABLED".equals(row.status)) {
                removed++;
            }
        }
        int removedFinal = removed;
        tx.inTransaction(connection -> {
            product.status = "DISABLED";
            product.disabledReason = reason;
            if (productDao.updateById(product, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "品种并发冲突（row_version 不匹配）：id=" + productId + "；复现=重新读取后再提交");
            }
            writeLog(productId, "DISABLE", "status", "ENABLED", "DISABLED", null, operatorId, reason);
            for (SubProductWhitelist row : whitelist) {
                if (!"ENABLED".equals(row.status)) {
                    continue;
                }
                row.status = "DISABLED";
                row.disabledReason = "品种停用级联移出：" + reason;
                row.dispatchStatus = "PENDING";
                if (subProductWhitelistDao.updateById(row, operatorId) != 1) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "白名单并发冲突：subAccountId=" + row.subAccountId + " productId=" + productId
                                    + "；复现=重新读取后再提交");
                }
                writeLog(productId, "DISABLE", "whitelist", "ENABLED", "DISABLED",
                        row.subAccountId, operatorId, "品种停用级联移出白名单");
            }
            return null;
        });
        return removedFinal;
    }

    /**
     * 启用品种（{@code FR-PRODUCT-5}）。
     *
     * <p>前置：必须有<b>至少一份生效换算关系</b>——换算关系缺失的品种不得启用（{@code R-PRODUCT-6}）。
     *
     * @param productId  品种
     * @param operatorId 操作人
     */
    public void enableProduct(long productId, long operatorId) {
        requireGlobalObjectWriteAuthority(operatorId, "启用品种");
        Product product = productDao.findById(productId);
        if (product == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种不存在：id=" + productId + "；复现=按 product_code 查询确认 ID");
        }
        ProductUnit unit = productUnitDao.findEffective(productId, Clocks.today());
        if (unit == null) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "品种缺生效的计量单位换算关系，不得启用（R-PRODUCT-6）：id=" + productId
                            + "；复现=先维护 t_product_unit 的当前生效换算关系");
        }
        tx.inTransaction(connection -> {
            product.status = "ENABLED";
            product.disabledReason = null;
            if (productDao.updateById(product, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "品种并发冲突：id=" + productId + "；复现=重新读取后再提交");
            }
            writeLog(productId, "ENABLE", "status", "DISABLED", "ENABLED", null, operatorId, "启用品种");
            return null;
        });
    }

    /**
     * 修改品种的**非编码**属性（{@code FR-PRODUCT-2}；{@code T-131}）。
     *
     * <p><b>可改</b>：{@code productName}（必填，置空即拒绝——{@code FR-PRODUCT-2} 验收 4）、
     * {@code categoryManaged}（{@code Y}/{@code N}）、{@code remark}。
     *
     * <p><b>不可改</b>：{@code productCode} —— 编码一经使用不得修改（{@code R-PRODUCT-3}；
     * {@code FR-PRODUCT-2} 验收 2「不提供该入口或拒绝修改」）。本方法<b>无编码入口</b>：入参不含
     * {@code productCode}，请求体（{@code UpdateRequest}）亦不含该字段 ⇒ 即便提交编码也<b>被忽略</b>
     * （不报错、按 200 返回，编码保持原值）；编码不可改由「无入口」保证，而非「给出即拒绝」。
     *
     * <p><b>分类（{@code category}）变更未承载（据实披露）</b>：{@code FR-PRODUCT-2} 处理规则要求
     * 分类变更「由风控岗发起、财务负责人审批并留痕，**且须校验该品种下是否存在未结束的套保计划或持仓**
     * ——存在则拒绝」。该审批流（{@code AUTH} 侧）与在途判定（{@code PLAN}/{@code TRADE} 侧 DAO）在本期
     * 均未建立，而分类直接决定规模红线比例（90% / 80%，{@code R-PRODUCT-4}）⇒ 按<b>安全默认拒绝</b>
     * （{@code 10012} 阻断类，四项 {@code ruleId}/{@code current}/{@code threshold}/{@code nextStep} 齐全），
     * **不得**静默放行造成红线口径中途漂移。登记见 {@code docs/TASKS.md} {@code T-131} 交付说明。
     *
     * <p><b>权限</b>：与 {@code createProduct}/{@code disableProduct}/{@code enableProduct}/{@code putUnit}
     * 同一强制点 —— {@link #requireGlobalObjectWriteAuthority}（{@code T-118}；{@code FR-AUTH-13} /
     * {@code CH-012} 口径：功能权限 {@code 10003}，入口缺位 fail-closed）。品种档案为<b>全局共享</b>对象
     * （{@code R-PRODUCT-7}）⇒ **不**套子账户写面断言（套用会误拒）。
     *
     * @param productId       品种
     * @param productName     新品种名称（必填）
     * @param categoryManaged 分类管理标记（{@code Y}/{@code N}；{@code null} = 不变）
     * @param remark          备注（{@code null} = 不变；空串 = 清空）
     * @param category        分类（仅用于**拒绝**判定：给出且与原值不同 ⇒ {@code 10012}）
     * @param operatorId      操作人
     * @return 修改后的品种（权威行）
     */
    public Product updateProduct(long productId, String productName, String categoryManaged,
                                 String remark, String category, long operatorId) {
        requireGlobalObjectWriteAuthority(operatorId, "修改品种");
        Product product = productDao.findById(productId);
        if (product == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种不存在：id=" + productId + "；复现=按 product_code 查询确认 ID");
        }
        String name = requireText(productName, "product_name");
        if (category != null && !category.trim().isEmpty()) {
            requireCategory(category.trim());
            if (!category.trim().equals(product.category)) {
                throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                        "品种分类变更未承载：分类变更须由风控岗发起、财务负责人审批并留痕，且须校验该品种下"
                                + "是否存在未结束的套保计划或持仓（FR-PRODUCT-2 / R-PRODUCT-4）：id=" + productId,
                        "R-PRODUCT-4", String.valueOf(product.category),
                        "分类变更 = 风控岗发起 + 财务负责人审批 + 在途计划/持仓校验",
                        "本期审批流与在途校验未建立 ⇒ 暂不提供分类变更入口；确需变更请由管理员经"
                                + "CONFIG 子账户分类口径统一处置并留痕");
            }
        }
        String candidateManaged = product.categoryManaged;
        if (categoryManaged != null && !categoryManaged.trim().isEmpty()) {
            candidateManaged = categoryManaged.trim();
            if (!"Y".equals(candidateManaged) && !"N".equals(candidateManaged)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "分类管理标记只允许 Y/N：实际=" + candidateManaged
                                + "；复现=按 §2 数据字典「品种分类管理标记」提交");
            }
        }
        final String newName = name;
        final String newManaged = candidateManaged;
        final String newRemark = remark;
        final boolean nameChanged = !newName.equals(product.productName);
        final boolean managedChanged = !newManaged.equals(product.categoryManaged);
        final boolean remarkChanged = newRemark != null && !newRemark.equals(product.remark);
        if (!nameChanged && !managedChanged && !remarkChanged) {
            return product;
        }
        tx.inTransaction(connection -> {
            if (nameChanged) {
                writeLog(productId, "UPDATE", "product_name", product.productName, newName, null,
                        operatorId, "修改品种名称");
                product.productName = newName;
            }
            if (managedChanged) {
                writeLog(productId, "UPDATE", "category_managed", product.categoryManaged, newManaged, null,
                        operatorId, "修改分类管理标记");
                product.categoryManaged = newManaged;
            }
            if (remarkChanged) {
                writeLog(productId, "UPDATE", "remark", product.remark, newRemark, null,
                        operatorId, "修改备注");
                product.remark = newRemark;
            }
            if (productDao.updateById(product, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "品种并发冲突：id=" + productId + "；复现=重新读取后再提交");
            }
            return null;
        });
        return productDao.findById(productId);
    }

    /**
     * 维护计量单位换算关系（{@code FR-PRODUCT-13}）。
     *
     * @param unit       换算关系（{@code convertFactor > 0}）
     * @param operatorId 操作人
     * @return 新行主键
     */
    public long putUnit(ProductUnit unit, long operatorId) {
        requireGlobalObjectWriteAuthority(operatorId, "维护计量单位换算关系");
        if (unit.productId == null || productDao.findById(unit.productId) == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种不存在：productId=" + unit.productId + "；复现=先创建品种");
        }
        if (unit.convertFactor == null || unit.convertFactor.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "换算系数必须 > 0（FR-PRODUCT-13 验收 2）：实际=" + unit.convertFactor
                            + "；复现=填写正数换算系数");
        }
        requireText(unit.spotUnit, "spot_unit");
        requireText(unit.futuresUnit, "futures_unit");
        if (unit.effectiveFrom == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "生效起始日必填（换算关系变更不追溯重算历史）：productId=" + unit.productId
                            + "；复现=补齐 effective_from");
        }
        unit.status = unit.status == null ? "ENABLED" : unit.status;
        return tx.inTransaction(connection -> {
            if (productUnitDao.insert(unit, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "同一品种同一生效日已有换算关系（uk_product_unit_eff）：productId="
                                + unit.productId + " effectiveFrom=" + unit.effectiveFrom
                                + "；复现=换生效日或改为变更既有行");
            }
            writeLog(unit.productId, "UPDATE", "product_unit", null,
                    unit.futuresUnit + "=" + unit.convertFactor + unit.spotUnit, null, operatorId,
                    "新增换算关系");
            return unit.id;
        });
    }

    /**
     * 现货数量折算为期货手数（{@code R-PRODUCT-6}：换算关系缺失一律拒绝，不降级为 1:1）。
     *
     * @param productId 品种
     * @param spotQty   现货数量
     * @param bizDate   业务日期（取该日生效的换算关系，不追溯重算历史）
     * @return 期货单位数量（向下取整到 6 位小数）
     */
    public BigDecimal convertToFuturesUnits(long productId, BigDecimal spotQty, LocalDate bizDate) {
        if (spotQty == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "待折算数量为空：productId=" + productId + "；复现=补齐数量");
        }
        ProductUnit unit = productUnitDao.findEffective(productId, bizDate);
        if (unit == null) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "品种在 " + bizDate + " 无生效换算关系，拒绝折算（R-PRODUCT-6）：productId=" + productId
                            + "；复现=先维护 t_product_unit");
        }
        return spotQty.divide(unit.convertFactor, 6, RoundingMode.HALF_UP);
    }

    /**
     * 维护品种-合约关联（{@code FR-PRODUCT-9}）。
     *
     * <p>期权必填项下沉校验：{@code instrumentType='OPTION'} 时 {@code underlying_instrument_id} /
     * {@code strike_price} / {@code option_type} 三者必填（否则行权价与标的无从判定）。
     *
     * @param contract   合约关联
     * @param operatorId 操作人
     * @return 新行主键
     */
    public long putContract(ProductContract contract, long operatorId) {
        requireGlobalObjectWriteAuthority(operatorId, "维护品种合约关联");
        requireText(contract.instrumentId, "instrument_id");
        if (contract.instrumentType == null
                || !INSTRUMENT_TYPES.contains(contract.instrumentType)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "合约类型必须显式标注 " + INSTRUMENT_TYPES + "：实际=" + contract.instrumentType
                            + "；复现=按 FR-PRODUCT-9 提交");
        }
        if (productDao.findById(contract.productId) == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种不存在：productId=" + contract.productId + "；复现=先创建品种");
        }
        if ("OPTION".equals(contract.instrumentType)
                && (contract.underlyingInstrumentId == null || contract.strikePrice == null
                        || contract.optionType == null)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "期权合约须同时提供标的合约、行权价与期权类型：instrumentId=" + contract.instrumentId
                            + "；复现=补齐 underlying_instrument_id / strike_price / option_type");
        }
        contract.isDomesticExchange = "Y";
        contract.status = contract.status == null ? "ENABLED" : contract.status;
        return tx.inTransaction(connection -> {
            if (productContractDao.insert(contract, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "合约代码已存在或该品种已关联此合约（uk_contract_instrument / uk_product_contract）："
                                + contract.instrumentId + "；复现=核对 t_product_contract 唯一键");
            }
            writeLog(contract.productId, "UPDATE", "product_contract", null, contract.instrumentId,
                    null, operatorId, "新增合约关联（" + contract.instrumentType + "）");
            return contract.id;
        });
    }

    /**
     * 维护品种-现货品类映射（{@code FR-PRODUCT-10}；期现一体的唯一入口）。
     *
     * <p>主映射唯一性：同一（品类 + 方向）**至多一个** {@code is_primary='Y'}
     * （{@code FR-PRODUCT-10} 验收 3）；违规返 {@code 10010}。
     *
     * @param mapping    映射
     * @param operatorId 操作人
     * @return 新行主键
     */
    public long putSpotMapping(ProductSpotCategory mapping, long operatorId) {
        requireGlobalObjectWriteAuthority(operatorId, "维护品种现货品类映射");
        requireText(mapping.spotCategoryCode, "spot_category_code");
        if (!MAPPING_DIRECTIONS.contains(mapping.mappingDirection)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "映射方向只允许 " + MAPPING_DIRECTIONS + "：实际=" + mapping.mappingDirection
                            + "；复现=按 TB-PRODUCT-4 取值域提交");
        }
        if (productDao.findById(mapping.productId) == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种不存在：productId=" + mapping.productId + "；复现=先创建品种");
        }
        mapping.isPrimary = mapping.isPrimary == null ? "N" : mapping.isPrimary;
        mapping.status = mapping.status == null ? "ENABLED" : mapping.status;
        if ("Y".equals(mapping.isPrimary)) {
            ProductSpotCategory existing =
                    productSpotCategoryDao.findPrimaryMapping(mapping.spotCategoryCode,
                            mapping.mappingDirection);
            if (existing != null && existing.productId != mapping.productId) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "该现货品类在此方向已有主映射（须先取消原主映射）：category="
                                + mapping.spotCategoryCode + " 原有 productId=" + existing.productId
                                + "；复现=先置原主映射 is_primary='N'（FR-PRODUCT-10 验收 3）");
            }
        }
        return tx.inTransaction(connection -> {
            if (productSpotCategoryDao.insert(mapping, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "映射已存在（uk_product_spot_cat）：category=" + mapping.spotCategoryCode
                                + " productId=" + mapping.productId + "；复现=核对映射唯一键");
            }
            writeLog(mapping.productId, "UPDATE", "product_spot_category",
                    existingPrimaryOf(mapping), mapping.spotCategoryCode, null, operatorId,
                    mapping.changeDesc);
            return mapping.id;
        });
    }

    // ------------------------------------------------------------ 白名单（子账户维度）

    /**
     * 维护子账户品种白名单（{@code FR-PRODUCT-12}；{@code R-PRODUCT-5}）。
     *
     * <p>已存在则"重新启用"（保留历史，不物理删除）。变更置
     * {@code dispatch_status='PENDING'}`待 M2 下发（{@code CMD_UPDATE_WHITELIST}）。
     *
     * <p><b>写面子账户可见性</b>（{@code NFR-SEC-7} / {@code T-112}）：白名单是<b>子账户级配置</b>
     * （{@code R-PRODUCT-7}），写面强制调用者对目标子账户可见，否则 {@code 10004}（fail-closed）。
     *
     * @param subAccountId     子账户（必须 &gt; 0）
     * @param productId        品种
     * @param categoryOverride 品种级分类覆盖（可空 = 继承子账户分类，{@code Q-2}）
     * @param operatorId       操作人
     */
    public void putWhitelist(int subAccountId, long productId, String categoryOverride,
                             long operatorId) {
        requireSubAccount(subAccountId);
        requireSubAccountWritable(operatorId, subAccountId);
        Product product = productDao.findById(productId);
        if (product == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种不存在：id=" + productId + "；复现=先创建品种");
        }
        if (categoryOverride != null) {
            requireCategory(categoryOverride);
        }
        SubProductWhitelist existing = findWhitelist(subAccountId, productId);
        tx.inTransaction(connection -> {
            if (existing == null) {
                SubProductWhitelist row = new SubProductWhitelist();
                row.subAccountId = subAccountId;
                row.productId = productId;
                row.categoryOverride = categoryOverride;
                row.status = "ENABLED";
                row.dispatchStatus = "PENDING";
                if (subProductWhitelistDao.insert(row, operatorId) != 1) {
                    throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                            "白名单已存在（uk_sub_product_wl）：subAccountId=" + subAccountId
                                    + " productId=" + productId + "；复现=改为变更既有行");
                }
                writeLog(productId, "UPDATE", "whitelist", "<<无>>", "ENABLED", subAccountId,
                        operatorId, "加入白名单");
            } else {
                if ("ENABLED".equals(existing.status)) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "该子账户已在白名单中：subAccountId=" + subAccountId + " productId="
                                    + productId + "；复现=无需重复加入");
                }
                String before = existing.status;
                existing.status = "ENABLED";
                existing.categoryOverride = categoryOverride;
                existing.disabledReason = null;
                existing.dispatchStatus = "PENDING";
                if (subProductWhitelistDao.updateById(existing, operatorId) != 1) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "白名单并发冲突：subAccountId=" + subAccountId + " productId=" + productId
                                    + "；复现=重新读取后再提交");
                }
                writeLog(productId, "ENABLE", "whitelist", before, "ENABLED", subAccountId,
                        operatorId, "重新加入白名单");
            }
            return null;
        });
    }

    /**
     * 移出白名单（置 {@code DISABLED} 并保留历史；{@code dispatch_status='PENDING'}`）。
     *
     * <p><b>写面子账户可见性</b>（{@code NFR-SEC-7} / {@code T-112}）：与 {@link #putWhitelist} 同口径，
     * 先断子账户合法性与可见性再落任何判定（哨兵 0/负数 {@code 10001}，越权 {@code 10004}，
     * 不回带目标白名单是否存在）。
     *
     * @param subAccountId 子账户
     * @param productId    品种
     * @param reason       移出原因（必填）
     * @param operatorId   操作人
     */
    public void removeWhitelist(int subAccountId, long productId, String reason, long operatorId) {
        requireText(reason, "disabled_reason");
        requireSubAccount(subAccountId);
        requireSubAccountWritable(operatorId, subAccountId);
        SubProductWhitelist existing = findWhitelist(subAccountId, productId);
        if (existing == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "该子账户未配置此品种白名单：subAccountId=" + subAccountId + " productId=" + productId
                            + "；复现=核对该子账户白名单");
        }
        if ("DISABLED".equals(existing.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "该白名单行已停用，无需重复操作：subAccountId=" + subAccountId + " productId="
                            + productId + "；复现=查询 status 列");
        }
        tx.inTransaction(connection -> {
            existing.status = "DISABLED";
            existing.disabledReason = reason;
            existing.dispatchStatus = "PENDING";
            if (subProductWhitelistDao.updateById(existing, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "白名单并发冲突：subAccountId=" + subAccountId + " productId=" + productId
                                + "；复现=重新读取后再提交");
            }
            writeLog(productId, "DISABLE", "whitelist", "ENABLED", "DISABLED", subAccountId,
                    operatorId, reason);
            return null;
        });
    }

    // ------------------------------------------------------------ 三层额度

    /**
     * 维护层①/层③额度（{@code FR-PRODUCT-11}；{@code Q-16} 三层限额）。
     *
     * <p>哨兵值联动（{@code DATA_MODEL.md} §4.3 {@code ck_product_quota_layer_sentinel}）：
     * {@code SUB_PRODUCT} ⇒ {@code subAccountId > 0}；{@code ACCOUNT_PRODUCT} ⇒ 强制取哨兵 {@code 0}。
     * 额度**不得为负**；**不得跨品种/跨子账户串用**（本类不提供转移入口，{@code R-PRODUCT-4}）。
     *
     * @param subAccountId 子账户（层③忽略入参、强制 0）
     * @param limitLayer   层级（{@code SUB_PRODUCT} / {@code ACCOUNT_PRODUCT}）
     * @param productId    品种
     * @param quotaLimit   额度上限（≥ 0）
     * @param quotaUnit    单位（{@code LOT} / {@code TON}）
     * @param effectiveFrom 生效起始日
     * @param operatorId   操作人
     * @return 新行主键
     */
    public long putQuota(int subAccountId, String limitLayer, long productId, BigDecimal quotaLimit,
                         String quotaUnit, LocalDate effectiveFrom, long operatorId) {
        if (!LIMIT_LAYERS.contains(limitLayer)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "限额层级只允许 " + LIMIT_LAYERS + "：实际=" + limitLayer + "；复现=按 Q-16 三层口径提交");
        }
        if ("SUB_INSTRUMENT".equals(limitLayer)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "层②（子账户+合约）须经合约额度接口维护：productId=" + productId
                            + "；复现=调用 putInstrumentQuota（TB-PRODUCT-8）");
        }
        if (quotaLimit == null || quotaLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "额度上限必须 ≥ 0（FR-PRODUCT-11 验收 4）：实际=" + quotaLimit + "；复现=填写非负额度");
        }
        if (!QUOTA_UNITS.contains(quotaUnit)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "额度单位只允许 " + QUOTA_UNITS + "：实际=" + quotaUnit + "；复现=按 TB-PRODUCT-6 取值域提交");
        }
        if (effectiveFrom == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "生效起始日必填：productId=" + productId + "；复现=补齐 effective_from");
        }
        int scopeSubAccount = "ACCOUNT_PRODUCT".equals(limitLayer)
                ? SubAccountScope.ALL_ACCOUNT_SENTINEL : subAccountId;
        if ("SUB_PRODUCT".equals(limitLayer) && scopeSubAccount <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "层①（子账户+品种）必须指定真实子账户（>0）：subAccountId=" + subAccountId
                            + "；复现=改用层③ ACCOUNT_PRODUCT 表达账户级额度");
        }
        // 写面子账户可见性（NFR-SEC-7 / T-112）：层①（SUB_PRODUCT）的行级归属为该子账户 ⇒ 强制；
        // 层③（ACCOUNT_PRODUCT）有效目标是账户哨兵 0、非子账户行级归属 ⇒ 本层不适用（见类 javadoc）。
        if (scopeSubAccount > 0) {
            requireSubAccountWritable(operatorId, scopeSubAccount);
        } else {
            // 层③（ACCOUNT_PRODUCT / 账户哨兵 0）的写面授权门禁（CH-012 / FR-PRODUCT-11 验收 5；T-119）。
            // ⚠️ 不得套用子账户可见性（assertSubAccountWritable 对哨兵 0 结构性互斥 ⇒ 误拒）；
            // 层③ 属「功能权限」维度 ⇒ 走账户级配置权断言（M1 角色近似：FIN_RISK / SYS_ADMIN）。
            requireCanConfigureAccountLevel(operatorId);
        }
        if (productDao.findById(productId) == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种不存在：id=" + productId + "；复现=先创建品种");
        }
        ProductQuota quota = new ProductQuota();
        quota.subAccountId = scopeSubAccount;
        quota.limitLayer = limitLayer;
        quota.productId = productId;
        quota.quotaLimit = quotaLimit;
        quota.quotaUnit = quotaUnit;
        quota.usedQty = BigDecimal.ZERO;
        quota.frozenQty = BigDecimal.ZERO;
        quota.effectiveFrom = effectiveFrom;
        quota.dispatchStatus = "PENDING";
        return tx.inTransaction(connection -> {
            if (productQuotaDao.insert(quota, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "同层同范围同起点已有额度（uk_product_quota）：layer=" + limitLayer + " sub="
                                + scopeSubAccount + " productId=" + productId + "；复现=改生效起点或变更既有行");
            }
            writeLog(productId, "UPDATE", "product_quota", null,
                    limitLayer + ":" + quotaLimit.toPlainString() + " " + quotaUnit,
                    scopeSubAccount == SubAccountScope.ALL_ACCOUNT_SENTINEL ? null : scopeSubAccount,
                    operatorId, "维护额度（" + limitLayer + "）");
            return quota.id;
        });
    }

    /**
     * 维护层②额度（子账户 + 合约，{@code TB-PRODUCT-8}）。
     *
     * @param subAccountId 子账户（&gt; 0）
     * @param instrumentId 合约
     * @param productId    品种（冗余维度，须与合约归属一致）
     * @param quotaLimit   额度上限（≥ 0）
     * @param quotaUnit    单位
     * @param effectiveFrom 生效起始日
     * @param operatorId   操作人
     * @return 新行主键
     */
    public long putInstrumentQuota(int subAccountId, String instrumentId, long productId,
                                   BigDecimal quotaLimit, String quotaUnit, LocalDate effectiveFrom,
                                   long operatorId) {
        requireSubAccount(subAccountId);
        requireSubAccountWritable(operatorId, subAccountId);
        requireText(instrumentId, "instrument_id");
        putQuotaCommon(productId, quotaLimit, quotaUnit, effectiveFrom);
        SubInstrumentQuota quota = new SubInstrumentQuota();
        quota.subAccountId = subAccountId;
        quota.instrumentId = instrumentId;
        quota.productId = productId;
        quota.quotaLimit = quotaLimit;
        quota.quotaUnit = quotaUnit;
        quota.usedQty = BigDecimal.ZERO;
        quota.frozenQty = BigDecimal.ZERO;
        quota.effectiveFrom = effectiveFrom;
        quota.dispatchStatus = "PENDING";
        return tx.inTransaction(connection -> {
            if (subInstrumentQuotaDao.insert(quota, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "同子账户同合约同起点已有额度（uk_sub_instrument_quota）：subAccountId="
                                + subAccountId + " instrumentId=" + instrumentId
                                + "；复现=改生效起点或变更既有行");
            }
            writeLog(productId, "UPDATE", "sub_instrument_quota", null,
                    instrumentId + ":" + quotaLimit.toPlainString() + " " + quotaUnit,
                    subAccountId, operatorId, "维护层②合约额度");
            return quota.id;
        });
    }

    /**
     * 三层限额全景（{@code Q-16}：三层**同时校验**、单笔按最细命中者，由 C++ 事前风控执行）。
     *
     * <p>本方法只做**展示与核对**取数；返回中的 {@code usedQty}/{@code frozenQty} 是库内投影，
     * <b>不得</b>作为判定依据（{@code R-RISK-9}）。
     *
     * <p><b>子账户读取面强制</b>（{@code NFR-SEC-7} / {@code T-106}）：全景按<b>入参子账户</b>取层①/层②
     * 额度行，故先强制调用者对该子账户<b>可见</b>（{@link #requireSubAccountVisible}，越权 {@code 10004}），
     * 否则任意调用者可凭 {@code subAccountId} 读取他人子账户额度配置（读取面越权）。
     * <b>层③</b>（{@code ACCOUNT_PRODUCT}）的有效目标是账户哨兵 {@code 0}、<b>非子账户行级归属</b>，
     * 故<b>不</b>按子账户可见性单独过滤（套用会制造误拒，同 {@code T-112}/{@code CH-012} 对层③的口径）；
     * 其可见性由「能否查看该子账户全景」这一入口强制承载。
     *
     * @param subAccountId 子账户（真实子账户，{@code > 0}）
     * @param productId    品种
     * @param operatorId   调用者（用于解析可见子账户；由会话决定，不接受请求参数传入）
     * @return 三层额度视图（层① / 层③ / 层②）
     */
    public Map<String, Object> quotaPanorama(int subAccountId, long productId, long operatorId) {
        requireSubAccount(subAccountId);
        requireSubAccountVisible(operatorId, subAccountId);
        List<ProductQuota> layer1 = productQuotaDao.findByLayerAndScope("SUB_PRODUCT", subAccountId);
        List<ProductQuota> layer3 = productQuotaDao.findByLayerAndScope("ACCOUNT_PRODUCT",
                SubAccountScope.ALL_ACCOUNT_SENTINEL);
        List<SubInstrumentQuota> layer2 = subInstrumentQuotaDao.findBySubAccount(subAccountId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("layer1SubProduct", filterByProduct(layer1, productId));
        out.put("layer3AccountProduct", filterByProduct(layer3, productId));
        List<SubInstrumentQuota> layer2OfProduct = new ArrayList<>();
        for (SubInstrumentQuota row : layer2) {
            if (row.productId != null && row.productId == productId) {
                layer2OfProduct.add(row);
            }
        }
        out.put("layer2SubInstrument", layer2OfProduct);
        out.put("note", "used_qty/frozen_qty 为库内投影，判定权威源为 C++ 内存（R-RISK-9）");
        return out;
    }

    // ------------------------------------------------------------ CSV 导入 / 导出

    /**
     * 品种 CSV 导入（{@code T-012} 验收 3）：**全量预校验 → 一次性插入**。
     *
     * <p>预校验逐行收集**全部**问题（缺列 / 列数不符 / 编码格式 / 分类越域 / 币种非 CNY /
     * 文件内重复 / 与库内重复），有一条不合格即抛 {@code 10019} 并**不产生任何写入**。
     *
     * @param csvLines   CSV 行（首行为表头）
     * @param operatorId 操作人
     * @return 成功导入行数
     */
    public int importProducts(List<String> csvLines, long operatorId) {
        requireGlobalObjectWriteAuthority(operatorId, "CSV 导入品种");
        if (csvLines == null || csvLines.isEmpty()) {
            throw BizException.of(ErrorCode.IMPORT_VALIDATION_FAILED,
                    "导入文件为空；复现=提供含表头与数据的 CSV（表头须为 " + CSV_HEADER + "）");
        }
        List<String> header = splitCsvLine(csvLines.get(0));
        List<String> problems = new ArrayList<>();
        for (String column : CSV_HEADER) {
            if (!header.contains(column)) {
                problems.add("缺少必需列：" + column);
            }
        }
        if (!problems.isEmpty()) {
            throw importFailed(problems, csvLines.size() - 1);
        }
        List<Product> parsed = new ArrayList<>();
        List<String> seenCodes = new ArrayList<>();
        for (int i = 1; i < csvLines.size(); i++) {
            String raw = csvLines.get(i);
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            List<String> cells = splitCsvLine(raw);
            if (cells.size() != header.size()) {
                problems.add("第 " + (i + 1) + " 行列数不符（期望 " + header.size() + "，实际 "
                        + cells.size() + "）");
                continue;
            }
            Product product = new Product();
            product.productCode = value(header, cells, "product_code");
            product.productName = value(header, cells, "product_name");
            product.category = value(header, cells, "category");
            product.currency = value(header, cells, "currency");
            product.remark = value(header, cells, "remark");
            if (!PRODUCT_CODE.matcher(String.valueOf(product.productCode)).matches()) {
                problems.add("第 " + (i + 1) + " 行品种编码格式非法：" + product.productCode
                        + "（允许大写字母/数字/下划线/连字符，2–32 位）");
            }
            if (!CATEGORIES.contains(product.category)) {
                problems.add("第 " + (i + 1) + " 行分类越域：" + product.category + "（只允许 " + CATEGORIES + "）");
            }
            if (product.currency != null && !product.currency.isEmpty()
                    && !CURRENCY_CNY.equals(product.currency)) {
                problems.add("第 " + (i + 1) + " 行币种非法：" + product.currency + "（本项目仅 " + CURRENCY_CNY + "）");
            }
            if (seenCodes.contains(product.productCode)) {
                problems.add("第 " + (i + 1) + " 行编码在文件内重复：" + product.productCode);
            }
            if (productDao.findByCode(product.productCode) != null) {
                problems.add("第 " + (i + 1) + " 行编码已存在于系统：" + product.productCode);
            }
            seenCodes.add(product.productCode);
            parsed.add(product);
        }
        if (parsed.isEmpty()) {
            problems.add("文件中无有效数据行");
        }
        if (!problems.isEmpty()) {
            throw importFailed(problems, csvLines.size() - 1);
        }
        return tx.inTransaction(connection -> {
            int inserted = 0;
            for (Product product : parsed) {
                product.categoryManaged = "Y";
                product.isDomesticExchange = "Y";
                product.currency = CURRENCY_CNY;
                product.status = "ENABLED";
                if (productDao.insert(product, operatorId) != 1) {
                    throw BizException.of(ErrorCode.IMPORT_VALIDATION_FAILED,
                            "插入失败（预校验通过但落库被拒，疑似并发写入同名编码）："
                                    + product.productCode + "；复现=重跑导入或核对 t_product 唯一键");
                }
                writeLog(product.id, "CREATE", null, null, null, null, operatorId, "CSV 导入新增品种");
                inserted++;
            }
            return inserted;
        });
    }

    /**
     * 品种导出（{@code FR-PRODUCT-8} 验收 / {@code T-105} 验收 4）：CSV 文本行（含表头）。
     *
     * <p><b>范围</b>：仅导出当前用户有权限的子账户范围（同一可见集过滤，与 {@code GET /product} 一致）。
     * <b>排序</b>：{@code created_time} 倒序，与 {@code GET /product} 的缺省排序对齐。
     *
     * @param operatorId 调用者（用于解析可见子账户；由会话决定）
     * @return CSV 行；无可见数据时仅返回表头（**HTTP 面**的「无可导出数据」语义见
     *         {@code ProductExportHandler}：{@code T-116} 验收 6 禁止返回「仅表头 CSV」）
     */
    public List<String> exportProducts(long operatorId) {
        return exportProducts(operatorId, ProductFilter.none());
    }

    /**
     * 品种导出（{@code FR-PRODUCT-8}；{@code T-105} 验收 4 / {@code T-116} 验收 2）：CSV 文本行（含表头）。
     *
     * <p><b>与列表同筛选口径（{@code T-116} 验收 2 的判据）</b>：本方法与
     * {@link #listProducts(long, PageQuery, ProductFilter)} <b>共用同一</b>
     * {@link #productWhere(ProductFilter)} 构造的 {@code WHERE} 与参数（同一筛选语义、同一可见集、
     * 同一排序 {@code created_time DESC}）⇒ 同一筛选下列表 {@code total} 与导出数据行数一致。
     * 实现上以 {@code PageQuery} 白名单最大页长（100）**分页遍历**取全量（导出不受分页上限约束），
     * 每条 SQL 都带同一筛选条件。
     *
     * @param operatorId 调用者（用于解析可见子账户；由会话决定）
     * @param filter     筛选（与列表同一口径；{@code null} ⇒ 不筛）
     * @return CSV 行（首行表头；无数据时**只**返回表头，由 handler 判定为「无可导出数据」）
     */
    public List<String> exportProducts(long operatorId, ProductFilter filter) {
        List<Product> products = allVisibleProducts(operatorId, productWhere(filter));
        List<String> out = new ArrayList<>();
        out.add(String.join(",", CSV_HEADER));
        for (Product product : products) {
            List<String> cells = new ArrayList<>();
            cells.add(nullToEmpty(product.productCode));
            cells.add(csvEscape(product.productName));
            cells.add(nullToEmpty(product.category));
            cells.add(nullToEmpty(product.currency));
            cells.add(csvEscape(product.remark));
            out.add(String.join(",", cells));
        }
        return out;
    }

    // ------------------------------------------------------------ 内部

    /**
     * 按筛选构造品种 {@code WHERE}（{@code T-116} 验收 1/2 的唯一口径来源）。
     *
     * <p>列表与导出<b>共用</b>本方法 ⇒ 「导出与列表同筛选口径」由代码结构保证，而非两处各自实现
     * （各写一遍正是{@code T-116} 立项时的缺陷形态）。
     *
     * @param filter 筛选（{@code null} ⇒ 无附加条件）
     * @return 条件 + 按占位符顺序排列的绑定参数（无条件时 {@code where=null}、{@code args} 空）
     */
    private ProductWhere productWhere(ProductFilter filter) {
        if (filter == null) {
            return ProductWhere.none();
        }
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (isNotBlank(filter.productCode)) {
            append(where, "product_code LIKE ?");
            args.add(likePattern(filter.productCode));
        }
        if (isNotBlank(filter.productName)) {
            append(where, "product_name LIKE ?");
            args.add(likePattern(filter.productName));
        }
        if (isNotBlank(filter.status)) {
            append(where, "status = ?");
            args.add(requireOneOf(filter.status, PRODUCT_STATUSES, "状态 status"));
        }
        if (isNotBlank(filter.category)) {
            append(where, "category = ?");
            args.add(requireOneOf(filter.category, CATEGORIES, "品种分类 category"));
        }
        if (filter.subAccountId != null) {
            List<Long> inBook = inBookProductIds(filter.subAccountId);
            if (inBook.isEmpty()) {
                // 空集 ⇒ 恒假（返回空），绝不退化为「不筛」= 全可见
                append(where, "id IN (-1)");
            } else {
                StringBuilder in = new StringBuilder("id IN (");
                for (int i = 0; i < inBook.size(); i++) {
                    in.append(i == 0 ? "?" : ",?");
                }
                append(where, in.append(')').toString());
                args.addAll(inBook);
            }
        }
        return new ProductWhere(where.length() == 0 ? null : where.toString(), args);
    }

    /**
     * 按筛选构造变更历史 {@code WHERE}（{@code T-116} 验收 7）。
     *
     * @param productId 品种
     * @param filter    筛选（非空）
     * @return 条件 + 绑定参数（{@code product_id = ?} 恒为首条件）
     */
    private ProductWhere changeLogWhere(long productId, ChangeLogFilter filter) {
        StringBuilder where = new StringBuilder("product_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(productId);
        if (filter.fromTime != null) {
            append(where, "change_time >= ?");
            args.add(filter.fromTime);
        }
        if (filter.toTime != null) {
            append(where, "change_time <= ?");
            args.add(filter.toTime);
        }
        if (filter.changeTypes != null && !filter.changeTypes.isEmpty()) {
            StringBuilder in = new StringBuilder("change_type IN (");
            for (int i = 0; i < filter.changeTypes.size(); i++) {
                in.append(i == 0 ? "?" : ",?");
            }
            append(where, in.append(')').toString());
            for (String type : filter.changeTypes) {
                args.add(requireOneOf(type, CHANGE_TYPES, "变更类型 changeType"));
            }
        }
        return new ProductWhere(where.toString(), args);
    }

    /**
     * 取可见范围内的全部品种（导出面；{@code created_time} 倒序，与列表缺省排序一致）。
     *
     * <p>以 {@code PageQuery} 白名单最大页长 {@value #EXPORT_PAGE_SIZE} 分页遍历：导出不受分页上限约束，
     * 但每条 SQL 都带同一 {@code WHERE}（筛选下推），且排序键含主键收官为全序 ⇒ 跨页不重不漏。
     *
     * @param operatorId 调用者
     * @param where      筛选条件
     * @return 可见且命中的全部品种（{@code created_time} 倒序）
     */
    private List<Product> allVisibleProducts(long operatorId, ProductWhere where) {
        List<Integer> visible = visibleSubAccountIds(operatorId);
        boolean cross = visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        SubAccountScope scope = cross ? null : SubAccountScope.of("sub_account_id", visible);
        List<Product> out = new ArrayList<>();
        int page = 1;
        while (true) {
            PageQuery query = pageQuery(page, DEFAULT_SORT_FIELD + ",desc", DEFAULT_SORT_FIELD);
            PageResult<Product> result = cross
                    ? productDao.findPage(query, where.where, where.args)
                    : productDao.findPageScoped(query, scope, where.where, where.args);
            out.addAll(result.items());
            if (result.items().isEmpty() || out.size() >= result.total()) {
                return out;
            }
            page++;
        }
    }

    /**
     * 取品种在册子账户（{@code FR-PRODUCT-5} 输出列「子账户」；{@code T-116} 验收 3）。
     *
     * <p><b>口径</b>：{@code t_product} 本身**无** {@code sub_account_id} 列（全局共享档案），
     * 「子账户」由 {@code TB-PRODUCT-5 t_sub_product_whitelist} 表达 ⇒ 本方法返回该品种白名单中
     * **{@code status = ENABLED}（在册；移出白名单 = 置 {@code DISABLED}）** 的
     * {@code sub_account_id} 去重升序列表（无在册行 ⇒ 空列表）。与
     * {@link #productWhere(ProductFilter)} 的 {@code subAccountId} 筛选口径**互为镜像**。
     *
     * <p><b>成本（诚实披露）</b>：{@code t_sub_product_whitelist} 现有索引为
     * {@code idx_sub_product_wl_sub(sub_account_id, status)} 与唯一键
     * {@code uk_sub_product_wl(sub_account_id, product_id)}——<b>前导列都是 {@code sub_account_id}</b>，
     * {@code WHERE product_id = ?} 无索引可用 ⇒ 每次为表扫描；列表页 {@code size ≤ 100} ⇒ 至多
     * {@value #EXPORT_PAGE_SIZE} 次扫描。<b>遗留建议</b>：补 {@code idx_sub_product_wl_product(product_id)}
     * （属 {@code docs/DEPLOY/schema} 与 {@code DATA_MODEL.md} 写者职责，{@code T-116} 写域不含）。
     *
     * @param productId 品种
     * @return 在册子账户 ID（升序；无则空列表）
     */
    public List<Integer> productSubAccountIds(long productId) {
        if (subProductWhitelistDao == null) {
            // 入口缺位 ⇒ fail-closed 返回空（不猜测、不放行为全量）
            return Collections.emptyList();
        }
        List<SubProductWhitelist> rows = subProductWhitelistDao.findByProduct(productId);
        Set<Integer> ids = new LinkedHashSet<>();
        if (rows != null) {
            for (SubProductWhitelist row : rows) {
                if (row != null && row.subAccountId != null && WHITELIST_ENABLED.equals(row.status)) {
                    ids.add(row.subAccountId);
                }
            }
        }
        List<Integer> out = new ArrayList<>(ids);
        Collections.sort(out);
        return out;
    }

    /**
     * 查询子账户品种白名单（{@code FR-PRODUCT-6} 处理规则「详情含…所属子账户白名单」；{@code T-131}）。
     *
     * <p><b>为何是 {@code FR-PRODUCT-6} 而非 {@code FR-PRODUCT-12}</b>：{@code FR-PRODUCT-12} 的输入列
     * 只有「子账户标识、品种集合（增/减）」，<b>不含读取</b>；读取义务来自 {@code FR-PRODUCT-6} 的
     * 「处理规则：详情含基础属性、额度指标、期货合约关联、现货品类映射、计量单位、<b>所属子账户白名单</b>、
     * 最近变更历史摘要」（{@code docs/REQUIREMENTS/PRODUCT.md}）。故本方法为**读取面**，不改变白名单。
     *
     * <p><b>{@code subAccountId} 取值域</b>：真实子账户（{@code > 0}，{@code DATA_MODEL.md} §0.2）；
     * 哨兵 {@code 0} 仅表达「账户级」作用域，<b>不是</b>白名单行 → 传 {@code 0} 一律 {@code 10001}。
     *
     * <p><b>权限口径（读面 / 写面分离，{@code NFR-SEC-7} / {@code FR-AUTH-13}）</b>：
     * <ul>
     *   <li>按 {@code productId} 查（本单主用法）：行级按<b>调用者可见子账户集合</b>过滤 ——
     *       含哨兵 {@code 0}（跨账户只读）⇒ 不过滤；空集 ⇒ <b>恒空</b>（fail-closed，严禁退化为全可见）；</li>
     *   <li>按 {@code subAccountId} 查（反向查「某子账户当前白名单」）：显式给定目标 ⇒ 走<b>读面</b>
     *       可见性断言（{@link #requireSubAccountVisible}，越权 {@code 10004}）——与 {@code GET /product/quota}
     *       的「显式目标 ⇒ 无权限提示」口径一致；</li>
     *   <li>写面（加/移出）仍归 {@code POST /product/whitelist}，其 {@code LIMITED} 写授权断言
     *       （{@link #requireSubAccountWritable}）<b>不变</b>；本方法<b>不</b>放宽写面。</li>
     * </ul>
     *
     * <p><b>「完整」的含义</b>：不做分页截断（白名单行数为子账户数量级），且<b>含已移出历史行</b>
     * （{@code status=DISABLED}，{@code R-PRODUCT-5}「移出 = 置 DISABLED 并保留历史」）——历史行是
     * {@code FR-PRODUCT-6}「所属子账户白名单」审计视图的一部分；只取在册行可由 {@code status} 参数收窄。
     *
     * @param productId    品种（{@code null} = 不限）
     * @param subAccountId 子账户（{@code null} = 不限；{@code <= 0} ⇒ {@code 10001}）
     * @param status       行状态筛选（{@code ENABLED}/{@code DISABLED}；空 = 全部）
     * @param operatorId   调用者（由会话决定，不接受请求参数传入）
     * @return 白名单行（无命中返回空列表，不报错）
     */
    public List<SubProductWhitelist> listWhitelist(Long productId, Integer subAccountId, String status,
                                                   long operatorId) {
        if (productId == null && subAccountId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须至少给出 productId 或 subAccountId 之一；复现=按品种查白名单用 productId，"
                            + "按子账户查用 subAccountId");
        }
        String wantedStatus = status == null || status.trim().isEmpty() ? null : status.trim();
        if (wantedStatus != null && !PRODUCT_STATUSES.contains(wantedStatus)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "白名单状态只允许 " + PRODUCT_STATUSES + "：实际=" + wantedStatus
                            + "；复现=按 TB-PRODUCT-5 status 取值域提交");
        }
        if (subAccountId != null) {
            requireSubAccount(subAccountId);
            // 显式给定子账户目标 ⇒ 读面可见性断言（T-106 双口径：显式目标给「无权限提示」而非统一错误）
            requireSubAccountVisible(operatorId, subAccountId);
        }
        List<Integer> visible = visibleSubAccountIds(operatorId);
        boolean allVisible = visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        List<SubProductWhitelist> rows = subAccountId != null
                ? subProductWhitelistDao.findBySubAccount(subAccountId)
                : subProductWhitelistDao.findByProduct(productId);
        List<SubProductWhitelist> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (SubProductWhitelist row : rows) {
            if (row == null || row.subAccountId == null) {
                continue;
            }
            if (productId != null && !productId.equals(row.productId)) {
                continue;
            }
            if (!allVisible && !visible.contains(row.subAccountId)) {
                continue;
            }
            if (wantedStatus != null && !wantedStatus.equals(row.status)) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    /** 子账户在册品种 ID（{@code status = ENABLED} 的白名单行；入口缺位 ⇒ 空集 fail-closed）。 */
    private List<Long> inBookProductIds(int subAccountId) {
        if (subProductWhitelistDao == null) {
            return Collections.emptyList();
        }
        List<SubProductWhitelist> rows = subProductWhitelistDao.findBySubAccount(subAccountId);
        Set<Long> ids = new LinkedHashSet<>();
        if (rows != null) {
            for (SubProductWhitelist row : rows) {
                if (row != null && row.productId != null && WHITELIST_ENABLED.equals(row.status)) {
                    ids.add(row.productId);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private static PageQuery pageQuery(int page, String sort, String defaultSortField) {
        return PageQuery.of(String.valueOf(page), String.valueOf(EXPORT_PAGE_SIZE), sort,
                defaultSortField);
    }

    private static void append(StringBuilder where, String clause) {
        if (where.length() > 0) {
            where.append(" AND ");
        }
        where.append(clause);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** {@code LIKE} 模式串（{@code %关键字%}）；以绑定参数传入，不拼 SQL。 */
    private static String likePattern(String keyword) {
        return "%" + keyword.trim() + "%";
    }

    private static String requireOneOf(String value, Set<String> allowedCandidates, String field) {
        String v = value.trim();
        if (!allowedCandidates.contains(v)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    field + " 取值越域：实际=" + v + "；可选=" + allowedCandidates
                            + "；复现=按 FR-PRODUCT-5 输入列的取值域提交");
        }
        return v;
    }

    /**
     * 取调用者可见子账户集合（{@code NFR-SEC-7} 取数入口；{@code null} ⇒ 空集 fail-closed）。
     *
     * @param operatorId 调用者
     * @return 可见子账户 ID 列表（含哨兵 0 表示跨账户只读）
     */
    private List<Integer> visibleSubAccountIds(long operatorId) {
        List<Integer> ids = subAccountVisibility == null
                ? null : subAccountVisibility.visibleSubAccountIds(operatorId);
        return ids == null ? Collections.emptyList() : ids;
    }

    private void putQuotaCommon(long productId, BigDecimal quotaLimit, String quotaUnit,
                                LocalDate effectiveFrom) {
        if (quotaLimit == null || quotaLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "额度上限必须 ≥ 0：实际=" + quotaLimit + "；复现=填写非负额度");
        }
        if (!QUOTA_UNITS.contains(quotaUnit)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "额度单位只允许 " + QUOTA_UNITS + "：实际=" + quotaUnit + "；复现=按取值域提交");
        }
        if (effectiveFrom == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "生效起始日必填：productId=" + productId + "；复现=补齐 effective_from");
        }
        if (productDao.findById(productId) == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种不存在：id=" + productId + "；复现=先创建品种");
        }
    }

    /**
     * 校验分页查询的<b>每一个</b>排序键均在白名单内（T-096：多键排序须逐键过白名单）。
     *
     * <p><b>为何逐键</b>：{@code PageQuery} 支持多键（{@code f1,dir1,f2,dir2}），这些键会直接进 DAO
     * 拼 {@code ORDER BY}；仅校验首键会让第 2+ 键绕开防注入校验。故对全部键逐一校验，任一越界即 {@code 10001}。
     *
     * @param query   分页查询
     * @param allowed 允许的排序字段（列名白名单）
     */
    private static void requireSortFieldsAllowed(PageQuery query, Set<String> allowed) {
        for (String field : query.sortFields()) {
            if (!allowed.contains(field)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "排序字段不在白名单：" + field + "；允许=" + allowed
                                + "；复现=改用允许的排序字段");
            }
        }
    }

    private static List<ProductQuota> filterByProduct(List<ProductQuota> rows, long productId) {
        List<ProductQuota> out = new ArrayList<>();
        for (ProductQuota row : rows) {
            if (row.productId != null && row.productId == productId) {
                out.add(row);
            }
        }
        return out;
    }

    private SubProductWhitelist findWhitelist(int subAccountId, long productId) {
        for (SubProductWhitelist row : subProductWhitelistDao.findBySubAccount(subAccountId)) {
            if (row.productId != null && row.productId == productId) {
                return row;
            }
        }
        return null;
    }

    private static String existingPrimaryOf(ProductSpotCategory mapping) {
        return mapping.mappingDirection + ":" + mapping.spotCategoryCode
                + (mapping.isPrimary == null ? "" : ("(" + mapping.isPrimary + ")"));
    }

    private void writeLog(Long productId, String changeType, String changedField, String before,
                          String after, Integer subAccountId, long operatorId, String reason) {
        ProductChangeLog log = new ProductChangeLog();
        log.productId = productId == null ? 0L : productId;
        log.changeType = changeType;
        log.changedField = changedField;
        log.beforeValue = before;
        log.afterValue = after;
        log.subAccountId = subAccountId;
        log.operatorId = operatorId;
        log.changeTime = Clocks.now();
        log.changeReason = reason;
        productChangeLogDao.insert(log, operatorId);
    }

    private static BizException importFailed(List<String> problems, int dataRows) {
        return BizException.of(ErrorCode.IMPORT_VALIDATION_FAILED,
                "导入文件校验失败（共 " + problems.size() + " 项问题，数据行 " + dataRows
                        + "；已全部回滚、未产生部分写入）：\n  - " + String.join("\n  - ", problems));
    }

    /** CSV 行拆分（支持双引号包裹与 {@code ""} 转义；不做静默降级）。 */
    private static List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                out.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        out.add(cell.toString().trim());
        return out;
    }

    private static String value(List<String> header, List<String> cells, String column) {
        int idx = header.indexOf(column);
        return idx < 0 || idx >= cells.size() ? null : cells.get(idx);
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("\"", "\"\"");
        return text.contains(",") || text.contains("\"") ? "\"" + text + "\"" : text;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void requireCategory(String category) {
        if (!CATEGORIES.contains(category)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "品种分类只允许 " + CATEGORIES + "：实际=" + category + "；复现=按 CFG-02c 取值域提交");
        }
    }

    private static void requireSubAccount(int subAccountId) {
        if (subAccountId <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "子账户必须为真实子账户（> 0；哨兵值 0 仅用于账户级作用域列）：实际=" + subAccountId
                            + "；复现=传入真实子账户 ID（DATA_MODEL.md §0.2）");
        }
    }

    /**
     * 子账户<b>读取面</b>可见性强制（{@code NFR-SEC-7}；{@code T-106}；{@code FR-PRODUCT-6} / {@code FR-PRODUCT-11}）。
     *
     * <p><b>仅用于只读面</b>（如三层额度全景 {@link #quotaPanorama}）：持有跨子账户只读
     * （{@code CROSS_READONLY}）者可查看全部子账户数据 ⇒ 放行。写动作一律走
     * {@link #requireSubAccountWritable}（{@code T-115}）。判据由
     * {@link SubAccountVisibility#assertSubAccountVisible} 给出（生产装配下即 {@link AuthService}
     * 的带留痕实现）。<b>fail-closed</b>：取数入口缺位（{@code null}）时一律拒绝（{@code 10004}），
     * 绝不退化为"不校验 = 放行"。入参 {@code subAccountId} 已由 {@link #requireSubAccount} 保证 {@code > 0}。
     *
     * @param operatorId   调用者
     * @param subAccountId 目标子账户（真实子账户，{@code > 0}）
     */
    private void requireSubAccountVisible(long operatorId, int subAccountId) {
        if (subAccountVisibility == null) {
            throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                    "子账户可见性取数入口缺位，按安全默认拒绝（NFR-SEC-7）：subAccountId=" + subAccountId
                            + "；复现=核对装配注入 SubAccountVisibility");
        }
        subAccountVisibility.assertSubAccountVisible(operatorId, subAccountId);
    }

    /**
     * 子账户<b>写入强制</b>（{@code NFR-SEC-7}；写路径统一入口；{@code T-112}/{@code T-115}）。
     *
     * <p><b>fail-closed</b>：可见性取数入口缺位（{@code null}）时一律拒绝（{@code 10004}），
     * 绝不退化为"不校验 = 放行"。判据由 {@link SubAccountVisibility#assertSubAccountWritable} 给出
     * （{@code T-115}）：<b>仅含哨兵（跨子账户只读 {@code CROSS_READONLY}）不得写</b>
     * （{@code FR-AUTH-13} 验收 3；{@code DATA_MODEL.md}：{@code CROSS_READONLY} 只读性由服务层保证）。
     * 入参 {@code subAccountId} 已由 {@link #requireSubAccount} 保证 {@code > 0}
     * （层③ 哨兵由调用点显式跳过）。
     *
     * @param operatorId   调用者
     * @param subAccountId 目标子账户（真实子账户，{@code > 0}）
     */
    private void requireSubAccountWritable(long operatorId, int subAccountId) {
        if (subAccountVisibility == null) {
            throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                    "子账户可见性取数入口缺位，按安全默认拒绝（NFR-SEC-7）：subAccountId=" + subAccountId
                            + "；复现=核对装配注入 SubAccountVisibility");
        }
        subAccountVisibility.assertSubAccountWritable(operatorId, subAccountId);
    }

    /**
     * 账户级（层③）配置权强制（{@code CH-012} / {@code FR-PRODUCT-11} 验收 5；{@code T-119}）。
     *
     * <p>层③（{@code ACCOUNT_PRODUCT}、行级落账户哨兵 {@code 0}）非子账户行级归属 ⇒ 写面授权按
     * <b>功能权限</b>维度判定（{@code FR-AUTH-12}「数据权限与功能权限独立」），
     * <b>不得</b>套用 {@link #requireSubAccountWritable}（对 {@code target=0} 结构性互斥 ⇒ 误拒，
     * 见 {@code ARCHITECTURE.md} §8.2③）。判据由
     * {@link AccountLevelConfigAuthorizer#assertCanConfigureAccountLevel} 给出（生产装配下即
     * {@link AuthService} 的带留痕实现；M1 角色近似：财务/风控岗 {@code FIN_RISK} 或系统管理员
     * {@code SYS_ADMIN}）。<b>fail-closed</b>：入口缺位（{@code null}）时一律拒绝（{@code 10003}），
     * 绝不退化为"不校验 = 放行"。
     *
     * @param operatorId 调用者
     */
    private void requireCanConfigureAccountLevel(long operatorId) {
        if (accountLevelConfigAuthorizer == null) {
            throw BizException.of(ErrorCode.FUNCTION_PERMISSION_DENIED,
                    "账户级（层③）配置权判定入口缺位，按安全默认拒绝（CH-012）：operatorId=" + operatorId
                            + "；复现=核对装配注入 AccountLevelConfigAuthorizer");
        }
        accountLevelConfigAuthorizer.assertCanConfigureAccountLevel(operatorId);
    }

    /**
     * <b>全局共享对象写面授权强制</b>（{@code FR-AUTH-13} 处理规则 / {@code NFR-SEC-7} /
     * {@code R-AUTH-8}；{@code T-118}）。
     *
     * <p><b>为何归「功能权限」维度、而非子账户可见性</b>：本类七个写方法
     * （{@link #createProduct} / {@link #disableProduct} / {@link #enableProduct} / {@link #putUnit} /
     * {@link #putContract} / {@link #putSpotMapping} / {@link #importProducts}）的目标均为
     * <b>全局共享对象</b>（{@code R-PRODUCT-7}「品种一份」：品种档案 / 换算关系 / 合约 / 现货映射不带
     * {@code sub_account_id} 维度）⇒ 其上<b>无子账户可锚</b>。{@code FR-AUTH-13} 处理规则
     * 「跨子账户下不得执行下单、审批、<b>改配置</b>等操作」为控制性动词，须在服务端<b>有</b>强制点；
     * 套用 {@link #requireSubAccountWritable} 会因「无目标子账户」而结构性误拒合法主体，
     * 故按 {@code FR-AUTH-12}「数据权限与功能权限独立」归<b>功能权限</b>维度（{@code CH-012} 口径）。
     *
     * <p><b>判据入口复用</b>：{@code CH-012} 已确认主体 = <b>财务/风控岗（{@code FIN_RISK}）或
     * 系统管理员（{@code SYS_ADMIN}）</b>，与 {@code T-119} 层③ 断言器
     * {@link AccountLevelConfigAuthorizer#assertCanConfigureAccountLevel} 的主体口径<b>一致</b>
     * ⇒ <b>复用既有一致判据、不新造断言器</b>（避免同族口径分叉）。生产装配下即
     * {@link AuthService} 的带留痕实现（失败码 {@code 10003}，落 {@code t_unauthorized_attempt}）。
     *
     * <p><b>fail-closed</b>：入口缺位（{@code null}）时一律拒绝（{@code 10003}），
     * 绝不退化为「不校验 = 放行」（与 {@link #requireSubAccountWritable} 缺位时同姿态）。
     *
     * @param operatorId 调用者
     * @param action     被拒动作名（仅用于可读拒绝信息，不改变错误码 / 留痕口径）
     */
    private void requireGlobalObjectWriteAuthority(long operatorId, String action) {
        if (accountLevelConfigAuthorizer == null) {
            throw BizException.of(ErrorCode.FUNCTION_PERMISSION_DENIED,
                    "全局共享对象写面授权判定入口缺位，按安全默认拒绝（FR-AUTH-13 / CH-012）：action="
                            + action + " operatorId=" + operatorId
                            + "；复现=核对装配注入 AccountLevelConfigAuthorizer");
        }
        accountLevelConfigAuthorizer.assertCanConfigureAccountLevel(operatorId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "必填字段为空：" + field + "；复现=补齐该字段后重新提交");
        }
        return value;
    }

    /**
     * 品种列表 / 导出的<b>同口径筛选</b>（{@code FR-PRODUCT-5} 输入列；{@code T-116} 验收 1/2）。
     *
     * <p>列表与导出共用本类实例 ⇒ 「导出与列表筛选一致」由类型系统保证。
     * 字段为 {@code null} / 空串表示不筛该维度；多个维度同时给出为 <b>AND</b>。
     */
    public static final class ProductFilter {

        /** 品种编码关键字（下推 {@code product_code LIKE ?}）。 */
        public String productCode;

        /** 品种名称关键字（下推 {@code product_name LIKE ?}）。 */
        public String productName;

        /** 状态：{@code ENABLED} / {@code DISABLED}（越域 ⇒ {@code 10001}）。 */
        public String status;

        /** 分类：{@code COMMODITY} / {@code TRADE}（越域 ⇒ {@code 10001}）。 */
        public String category;

        /** 子账户（白名单在册口径 ⇒ 下推 {@code id IN (?,...)}；无在册品种 ⇒ 恒假）。 */
        public Integer subAccountId;

        /**
         * 空筛选（不筛任何维度）。
         *
         * @return 空筛选实例
         */
        public static ProductFilter none() {
            return new ProductFilter();
        }
    }

    /**
     * 变更历史筛选（{@code FR-PRODUCT-14} 输入列；{@code T-116} 验收 7）。
     *
     * <p>时间边界均为<b>闭区间</b>；{@code null} 表示不设该边界。
     */
    public static final class ChangeLogFilter {

        /** 起始时间（含；下推 {@code change_time >= ?}）。 */
        public LocalDateTime fromTime;

        /** 截止时间（含；下推 {@code change_time <= ?}）。 */
        public LocalDateTime toTime;

        /** 变更类型（{@code CREATE}/{@code UPDATE}/{@code ENABLE}/{@code DISABLE}；空/null ⇒ 不筛）。 */
        public List<String> changeTypes;

        /**
         * 是否无任何筛选条件。
         *
         * @return {@code true} ⇒ 三个维度均未给出
         */
        public boolean isEmpty() {
            return fromTime == null && toTime == null
                    && (changeTypes == null || changeTypes.isEmpty());
        }
    }

    /** {@code WHERE} 片段 + 绑定参数（按占位符顺序；无条件时 {@code where=null}）。 */
    private static final class ProductWhere {

        private final String where;
        private final List<Object> args;

        private ProductWhere(String where, List<Object> args) {
            this.where = where;
            this.args = args == null ? Collections.emptyList() : args;
        }

        private static ProductWhere none() {
            return new ProductWhere(null, Collections.emptyList());
        }
    }
}
