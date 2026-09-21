package com.hedgeguard.dao;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 结果集行映射（DAO 内唯一允许接触 {@link ResultSet} 的位置）。
 *
 * <p>L3 不依赖 JDBC 类型（{@code ARCHITECTURE.md} §2.3），故 DAO 接口只返回实体，不返回结果集。
 *
 * @param <T> 实体类型
 */
@FunctionalInterface
public interface RowMapper<T> {

    /**
     * 映射一行。
     *
     * @param rs 结果集（游标已定位到当前行）
     * @return 实体
     * @throws SQLException 读取失败
     */
    T map(ResultSet rs) throws SQLException;
}
