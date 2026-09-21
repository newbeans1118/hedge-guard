package com.hedgeguard.dao;

import java.util.List;

/**
 * DAO 基类接口（{@code ARCHITECTURE.md} §3.9；本接口自 T-004 起<b>冻结</b>）。
 *
 * <p>三条冻结约定：
 * <ol>
 *   <li><b>不出现 JDBC 类型</b>：方法签名不含 {@code Connection} / {@code ResultSet}，
 *       使 L3 只依赖本节接口而不依赖 JDBC（{@code §2.3}）；</li>
 *   <li><b>事务边界由 L3 经 {@code dao.tx.TxTemplate} 开启</b>；DAO 方法内部自动加入当前事务
 *       （线程内绑定），未开启事务时每条语句自成一个自动提交单元；</li>
 *   <li><b>乐观锁</b>：{@link #updateById} 依据 {@code row_version} 比对，
 *       影响行数为 {@code 0} 表示并发冲突（调用方据此抛 {@code 10009}/{@code 10010}，
 *       <b>不得</b>静默忽略）。</li>
 * </ol>
 *
 * @param <T>  实体类型（须继承 {@link com.hedgeguard.dao.entity.AuditedEntity}）
 * @param <ID> 主键类型
 */
public interface BaseDao<T, ID> {

    /**
     * 插入一行（自动填充通用审计字段，{@code §0.3}）。
     *
     * @param entity     实体
     * @param operatorId 操作人（{@code created_by}）
     * @return 影响行数（正常为 1）
     */
    int insert(T entity, long operatorId);

    /**
     * 按主键更新（乐观锁：{@code WHERE id = ? AND row_version = ?}）。
     *
     * @param entity     实体（{@code rowVersion} 须为读取到的版本）
     * @param operatorId 操作人（{@code updated_by}）
     * @return 影响行数；{@code 0} 表示版本冲突或行不存在
     */
    int updateById(T entity, long operatorId);

    /**
     * 按主键查询。
     *
     * @param id 主键
     * @return 实体；不存在返回 {@code null}
     */
    T findById(ID id);

    /**
     * 条件分页查询。
     *
     * @param query     分页参数
     * @param where     附加条件（{@code null} 表示无条件；调用方须以占位符传值，禁止拼接取值）
     * @param whereArgs 附加条件参数
     * @return 分页结果
     */
    PageResult<T> findPage(PageQuery query, String where, List<Object> whereArgs);

    /**
     * 统计满足条件的行数。
     *
     * @param where     条件（{@code null} 表示全部）
     * @param whereArgs 条件参数
     * @return 行数
     */
    long count(String where, List<Object> whereArgs);
}
