package com.hedgeguard.dao.pool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;

/**
 * JDBC 物理连接工厂（达梦 DM8，{@code ARCHITECTURE.md} §6.1 {@code D15}）。
 *
 * <p><b>敏感字段零落盘 / 零日志</b>（{@code NFR-SEC-1}）：口令以 {@code char[]} 持有，
 * 不经 {@code String}、不写日志、不写配置；{@link #clearPassword()} 用于进程退出前清零。
 *
 * <p>驱动类名与 URL 前缀是 DM 专有项（{@code DATA_MODEL.md} §6.1 {@code D15}），
 * 经配置注入而非硬编码 —— 换库只改配置。
 */
public final class JdbcConnectionFactory implements ConnectionFactory {

    /** DM JDBC 驱动类名（{@code DATA_MODEL.md} §6.1 D15）。 */
    public static final String DM_DRIVER_CLASS = "dm.jdbc.driver.DmDriver";

    /** DM JDBC URL 前缀（{@code DATA_MODEL.md} §6.1 D15）。 */
    public static final String DM_URL_PREFIX = "jdbc:dm://";

    private final String url;
    private final String user;
    private final char[] password;
    private final String driverClass;

    /**
     * 构造。
     *
     * @param driverClass 驱动类名（DM 为 {@link #DM_DRIVER_CLASS}）
     * @param url         JDBC URL
     * @param user        用户名（应用账号，仅 SELECT/INSERT/UPDATE，{@code NFR-SEC-8}）
     * @param password    口令（可为 {@code null}；调用方持有所有权，可随后清零）
     */
    public JdbcConnectionFactory(String driverClass, String url, String user, char[] password) {
        this.driverClass = driverClass;
        this.url = url;
        this.user = user;
        this.password = password == null ? null : Arrays.copyOf(password, password.length);
    }

    @Override
    public Connection create() throws SQLException {
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            throw new SQLException(
                    "DM JDBC 驱动不可用：驱动类=" + driverClass
                            + "；原因=jar 未在 classpath（须与客户 DM8 实例版本匹配，PROJECT.md §5.3 第 4 项）；"
                            + "复现=mvn -Pdm compile 并确认 java/lib/DmJdbcDriver18.jar 存在", e);
        }
        Properties props = new Properties();
        if (user != null) {
            props.setProperty("user", user);
        }
        if (password != null) {
            // 注：JDBC 驱动 API 只接受 String，此处不可避免产生一次短暂 String 拷贝；
            // 该拷贝不落盘、不入日志，且本方法返回后不再被引用（NFR-SEC-1 的可达最优）。
            props.setProperty("password", new String(password));
        }
        return DriverManager.getConnection(url, props);
    }

    /**
     * 清零持有的口令副本。
     *
     * <p>调用时机：进程退出钩子内（{@code NFR-SEC-1}：仅内存持有、用后清零）。
     */
    public void clearPassword() {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    /** URL（**不含**口令，可安全记录）。 */
    public String url() {
        return url;
    }
}
