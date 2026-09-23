package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.Exposure;

import java.util.List;

/**
 * {@code TB-SPOT-8 t_exposure} 数据访问（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 *
 * <p><b>子账户隔离载体（{@code T-107}）</b>：{@code t_exposure} 有 {@code sub_account_id} 列
 * （敞口类红线，{@code INDEX.md} §2.1）⇒ scope 的 {@code sub_account_id} 谓词<b>直接落在本表列上</b>
 * （{@code sub_account_id IN (...)}），与 {@code HedgePlanDao}/{@code TradePlanDao} 同构（{@code T-100}/{@code T-101}）。
 * 服务层经 {@code AuthService.visibleSubAccountIds} → {@link SubAccountScope#of} 注入，DAO 层统一拼装，
 * <b>不自拼 {@code IN (...)}</b>；空 scope ⇒ 恒假条件（返回空），绝不退化为不过滤。
 *
 * <p><b>读取面唯一入口</b>：列表（{@link #findPageScoped}）与详情（{@link #findByIdScoped}）均带 scope；
 * 旧 {@code findPendingHedge(int)}（调用方按 {@code subAccountId} 决定可见集）已由 {@code T-107} 删除——
 * 该形态允许任意调用者读取他人子账户敞口（{@code NFR-SEC-7} 越权面）。
 */
public interface ExposureDao extends BaseDao<Exposure, Long> {

    /**
     * 分页 + 子账户可见范围过滤（{@code FR-SPOT-15} 列表 / {@code NFR-SEC-7} 读取面强制点）。
     *
     * <p>scope 的 {@code sub_account_id} 谓词直接落在 {@code t_exposure.sub_account_id} 列上；
     * <b>空 scope ⇒ 恒假条件（返回空），绝不退化为不过滤</b>；计数（{@code total}）与取数（{@code items}）
     * 两条 SQL 均带该 scope（{@link com.hedgeguard.dao.impl.AbstractDmDao#findPage} 独立执行 {@code COUNT}）。
     *
     * <p><b>缺省排序方向</b>：本层不做方向缺省；调用方（handler）须显式声明方向（{@code exposure_qty,desc}），
     * 因 {@link PageQuery} 在 {@code sort} 缺省时方向恒为 {@code ASC}（{@code FR-SPOT-15}「默认按敞口金额或数量倒序」）。
     *
     * @param query      分页 / 排序
     * @param scope      子账户过滤（列名取本表 {@code sub_account_id}）
     * @param extraWhere 附加条件（可为 {@code null}；如 {@code status = ?}）
     * @param extraArgs  附加条件参数（可为 {@code null}）
     * @return 分页结果（{@code total} 为叠加 scope 与 {@code extraWhere} 后的总行数）
     */
    PageResult<Exposure> findPageScoped(PageQuery query, SubAccountScope scope,
                                        String extraWhere, List<Object> extraArgs);

    /**
     * 按主键取敞口 + 子账户可见范围过滤（{@code FR-SPOT-15} 详情 / {@code NFR-SEC-7}）。
     *
     * <p>条件形态 {@code WHERE id = ? AND <scope.sql()>}；scope 为空集时其 {@code sql()} 为恒假条件
     * （{@code sub_account_id IN (-1)}），与主键条件并列后必然无命中——即<b>不可见 ≡ 不存在</b>
     * （同一返回，不泄露存在性，同 {@code HedgePlanDao#findByIdScoped}）。
     *
     * @param id    主键
     * @param scope 子账户过滤（列名取本表 {@code sub_account_id}）
     * @return 命中的敞口；不可见或不存在均返回 {@code null}
     */
    Exposure findByIdScoped(long id, SubAccountScope scope);
}
