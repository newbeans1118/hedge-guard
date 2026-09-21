package com.hedgeguard.dao.pool;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 物理连接工厂（把"如何建立一条 JDBC 连接"与"如何管理连接"解耦）。
 *
 * <p>解耦目的：连接池逻辑（借还/健康检查/超时）可在<b>无 DM8 环境</b>下用假工厂完整单测
 * （{@code NFR-TEST-4}、{@code NFR-TEST-2}），这与 {@code §7.2.2}「测试打桩自实现」一致。
 */
@FunctionalInterface
public interface ConnectionFactory {

    /**
     * 建立一条新连接。
     *
     * @return 连接（调用方负责归还或关闭）
     * @throws SQLException 建连失败
     */
    Connection create() throws SQLException;
}
