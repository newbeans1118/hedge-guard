package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_product} 的达梦实现（{@code DATA_MODEL.md} §2.3 TB-PRODUCT-1）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmProductDao extends AbstractDmDao<Product, Long> implements ProductDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "product_code", "product_name", "category", "category_managed", "status",
            "is_domestic_exchange", "currency", "remark", "disabled_reason"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmProductDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_product";
    }

    @Override
    protected String idColumn() {
        return "id";
    }

    @Override
    protected String[] businessColumns() {
        return COLUMNS.clone();
    }

    @Override
    protected void bindBusiness(PreparedStatement ps, Product e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.productCode);
        bind(ps, i++, e.productName);
        bind(ps, i++, e.category);
        bind(ps, i++, e.categoryManaged);
        bind(ps, i++, e.status);
        bind(ps, i++, e.isDomesticExchange);
        bind(ps, i++, e.currency);
        bind(ps, i++, e.remark);
        bind(ps, i, e.disabledReason);
    }

    @Override
    protected void readBusiness(ResultSet rs, Product e) throws SQLException {
        e.id = rs.getLong("id");
        e.productCode = string(rs, "product_code");
        e.productName = string(rs, "product_name");
        e.category = string(rs, "category");
        e.categoryManaged = string(rs, "category_managed");
        e.status = string(rs, "status");
        e.isDomesticExchange = string(rs, "is_domestic_exchange");
        e.currency = string(rs, "currency");
        e.remark = string(rs, "remark");
        e.disabledReason = string(rs, "disabled_reason");
    }

    @Override
    protected Long idOf(Product entity) {
        return entity.id;
    }

    @Override
    protected Product newEntity() {
        return new Product();
    }

    @Override
    protected void applyGeneratedId(Product entity, long generated) {
        entity.id = generated;
    }

    @Override
    public Product findByCode(String productCode) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE product_code = ?";
        return queryOne(sql, Arrays.asList(productCode), this::mapRow);
    }

    @Override
    public List<Product> findAllOrderByCode() {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " ORDER BY product_code ASC";
        return queryList(sql, java.util.Collections.emptyList(), this::mapRow);
    }

    // ------------------------------------------------------------ T-105：列表 / 导出读取面

    /**
     * {@inheritDoc}
     *
     * <p><b>scope 落点</b>：{@code t_product} 无 {@code sub_account_id} 列，故把 scope 的
     * {@code sub_account_id} 谓词落在 {@code t_sub_product_whitelist} 子查询上，主查询以
     * {@code id IN (...)} 取并集；{@code scope.sql()} 为空集时为 {@code sub_account_id IN (-1)} ⇒
     * 子查询无行 ⇒ 主查询恒假（返回空），<b>不</b>退化为不过滤。
     *
     * <p>计数与取数经 {@link AbstractDmDao#findPage} 各执行一条 SQL（{@code COUNT} + {@code SELECT}），
     * 两条<b>均</b>带该 scope 条件（{@code T-105} 验收 6）。
     *
     * <p><b>排序稳定性</b>：{@code T-096} 边界修订后，分页装配层（{@link AbstractDmDao#findPage}）在排序键
     * 不含唯一列时自动补本表主键 {@code id} 收官 ⇒ {@code ORDER BY created_time DESC, id ASC}，构成全序，
     * 同 {@code created_time} 的记录跨页不再重复 / 漏行（{@code FR-PLAN-15}「分页排序稳定性」）。
     */
    @Override
    public PageResult<Product> findPageScoped(PageQuery query, SubAccountScope scope,
                                              String extraWhere, List<Object> extraArgs) {
        List<Object> args = new ArrayList<>();
        String scoped = scopedProductIdsPredicate(scope);
        String where = scoped;
        if (extraWhere != null && !extraWhere.isEmpty()) {
            where = "(" + extraWhere + ") AND " + scoped;
            args.addAll(extraArgs == null ? Collections.emptyList() : extraArgs);
        }
        args.addAll(scope.params());
        return findPage(query, where, args);
    }

    /**
     * {@inheritDoc}
     *
     * <p>排序与 {@code GET /product} 缺省一致（{@code created_time} 倒序，{@code FR-PRODUCT-5}
     * 处理规则 / {@code T-105} 验收 4）；scope 落点同 {@link #findPageScoped}。
     */
    @Override
    public List<Product> findAllScoped(SubAccountScope scope) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE " + scopedProductIdsPredicate(scope)
                + " ORDER BY created_time DESC";
        return queryList(sql, new ArrayList<Object>(scope.params()), this::mapRow);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code created_time} 倒序：与 {@link #findAllScoped} 排序口径一致，供跨账户只读（哨兵 0）
     * 的无过滤导出路径使用。
     */
    @Override
    public List<Product> findAllOrderByCreatedDesc() {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " ORDER BY created_time DESC";
        return queryList(sql, Collections.emptyList(), this::mapRow);
    }

    /**
     * 可见品种主键谓词：{@code id IN (SELECT product_id FROM t_sub_product_whitelist WHERE <scope>)}。
     *
     * <p>把 {@link SubAccountScope} 的 {@code sub_account_id} 谓词放入白名单子查询（形态见接口注释）；
     * 空 scope ⇒ 子查询恒假 ⇒ 主查询无命中。
     *
     * @param scope 子账户过滤
     * @return 主查询 WHERE 片段（不含绑定值）
     */
    private static String scopedProductIdsPredicate(SubAccountScope scope) {
        return "id IN (SELECT product_id FROM t_sub_product_whitelist WHERE " + scope.sql() + ")";
    }
}
