package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.Product;
import java.util.List;

/**
 * {@code TB-PRODUCT-1 t_product} 数据访问（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 *
 * <p><b>子账户隔离载体（{@code T-105}）</b>：{@code t_product} 本身<b>无</b> {@code sub_account_id} 列
 * （{@code DATA_MODEL.md} §2.3 TB-PRODUCT-1）；可见范围由 {@code TB-PRODUCT-5
 * t_sub_product_whitelist(sub_account_id, product_id)} 表达。故本 DAO 的 scoped 方法把 scope 的
 * {@code sub_account_id} 谓词落在<b>白名单子查询</b>上：{@code t_product.id IN (SELECT product_id
 * FROM t_sub_product_whitelist WHERE <scope.sql()>)}（{@code FR-PRODUCT-5} 处理规则
 * 「仅返回当前用户有权限查看的子账户范围内的品种」）。
 */
public interface ProductDao extends BaseDao<Product, Long> {

    /**
     * 按编码升序取全部品种。
     *
     * @return 列表（{@code product_code} 升序）
     */
    List<Product> findAllOrderByCode();

    /**
     * 按品种编码查品种（uk_product_code 唯一；CSV 导入与下单前置校验用）。
     *
     * @param productCode 查询条件
     * @return 命中的实体；不存在返回 {@code null}
     */
    Product findByCode(String productCode);

    // ------------------------------------------------------------ T-105：列表 / 导出读取面

    /**
     * 分页 + 子账户可见范围过滤（{@code FR-PRODUCT-5} 分页 / {@code NFR-SEC-7} 读取面强制点）。
     *
     * <p>scope 的 {@code sub_account_id} 谓词落在 <b>{@code t_sub_product_whitelist} 子查询</b>上
     * （{@code t_product} 无 {@code sub_account_id} 列）：命中的品种为「可见子账户白名单所覆盖的品种」
     * 之并集。<b>空 scope ⇒ 恒假条件（返回空），绝不退化为不过滤</b>；计数（{@code total}）与
     * 取数（{@code items}）两条 SQL 均带该 scope（{@code AbstractDmDao#findPage} 独立执行 {@code COUNT}）。
     *
     * <p><b>缺省排序方向</b>：本层不做方向缺省；调用方（handler）须显式声明（{@code created_time,desc}），
     * 因 {@link PageQuery} 在 {@code sort} 缺省时方向恒为 {@code ASC}（{@code BUG-001}）。
     *
     * @param query      分页 / 排序
     * @param scope      子账户过滤（列名取白名单表的 {@code sub_account_id}）
     * @param extraWhere 附加条件（可为 {@code null}）
     * @param extraArgs  附加条件参数（可为 {@code null}）
     * @return 分页结果（{@code total} 为叠加 scope 与 {@code extraWhere} 后的总行数）
     */
    PageResult<Product> findPageScoped(PageQuery query, SubAccountScope scope,
                                       String extraWhere, List<Object> extraArgs);

    /**
     * 取可见范围内的全部品种（导出面，{@code FR-PRODUCT-8}）：按 {@code created_time} 倒序，
     * 与 {@code GET /product} 的缺省排序对齐（{@code T-105} 验收 4）。
     *
     * <p>过滤语义同 {@link #findPageScoped}：scope 落在 {@code t_sub_product_whitelist} 子查询上；
     * <b>空 scope ⇒ 恒假条件（返回空）</b>。
     *
     * @param scope 子账户过滤（列名取白名单表的 {@code sub_account_id}）
     * @return 白名单覆盖的品种（{@code created_time} 倒序；无命中返回空列表）
     */
    List<Product> findAllScoped(SubAccountScope scope);

    /**
     * 取全部品种（不分权限），按 {@code created_time} 倒序（导出面的<b>跨账户只读</b>路径用；
     * 哨兵 {@code 0} 时不加过滤）。
     *
     * @return 列表（{@code created_time} 倒序）
     */
    List<Product> findAllOrderByCreatedDesc();
}
