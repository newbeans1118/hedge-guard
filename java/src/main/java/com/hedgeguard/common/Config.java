package com.hedgeguard.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 运行参数读取（{@code ARCHITECTURE.md} §11 配置项清单的落地载体）。
 *
 * <p>三条硬纪律：
 * <ol>
 *   <li><b>敏感字段零落盘</b>（{@code NFR-SEC-1} / 附录 B 红线 8）：本类<b>拒绝</b>从属性文件读入
 *       任何键名含 {@code password} / {@code authcode} / {@code auth_code} / {@code token} 的项——
 *       口令类只能经环境变量或进程内存注入（见 {@link #secret(String)}）。</li>
 *   <li><b>禁止硬编码</b>（{@code R-RISK-14}）：可调项一律经本类读取，不写死在代码里。</li>
 *   <li><b>默认值不得低于合规口径</b>（{@code R-CONFIG-8}）：本类只提供基础设施类默认值
 *       （绑定地址 / 端口 / 目录 / 池大小），业务口径默认值由 {@code CFG-*}/{@code TB-CONFIG-2} 承载。</li>
 * </ol>
 */
public final class Config {

    /**
     * HTTP 服务绑定地址（`CFG-33 hg.web.bind`；仅允许回环，见 {@link #loopbackOnly()}）。
     */
    public static final String KEY_HTTP_BIND = "hg.web.bind";
    /** HTTP 服务端口（`CFG-33 hg.web.port`，默认 27402；与 `hg.tcp.port` / `hg.web.ws.port` 分离）。 */
    public static final String KEY_HTTP_PORT = "hg.web.port";
    /** HTTP 服务工作线程数（`CFG-33 hg.web.threads`，默认 16）。 */
    public static final String KEY_HTTP_THREADS = "hg.web.threads";
    /** 前端静态资源目录（{@code CFG-30 hg.web.root}，默认 {@code ./web}）。 */
    public static final String KEY_WEB_ROOT = "hg.web.root";
    /** 会话 Cookie 名（{@code ARCHITECTURE.md} §4.1.1）。 */
    public static final String KEY_SESSION_COOKIE = "hg.auth.session_cookie";
    /** 会话空闲超时分钟数（{@code CFG-23b}，默认 30）。 */
    public static final String KEY_SESSION_IDLE_MINUTES = "hg.auth.session_idle_minutes";
    /** 登录失败锁定阈值（{@code CFG-23 hg.auth.login_fail_limit}，默认 5）。 */
    public static final String KEY_LOGIN_FAIL_LIMIT = "hg.auth.login_fail_limit";
    /** 登录锁定时长分钟（{@code CFG-23 hg.auth.login_lock_minutes}，默认 5）。 */
    public static final String KEY_LOGIN_LOCK_MINUTES = "hg.auth.login_lock_minutes";
    /** 口令最小长度（{@code CFG-23b hg.auth.password_min_length}，默认 12）。 */
    public static final String KEY_PASSWORD_MIN_LENGTH = "hg.auth.password_min_length";
    /** 近期口令历史条数（{@code CFG-23b hg.auth.password_history_size}，默认 5）。 */
    public static final String KEY_PASSWORD_HISTORY_SIZE = "hg.auth.password_history_size";
    /** 组件通信绑定地址（{@code CFG-20}）。 */
    public static final String KEY_TCP_BIND = "hg.tcp.bind";
    /** 组件通信端口（{@code CFG-20}）。 */
    public static final String KEY_TCP_PORT = "hg.tcp.port";
    /** 口令哈希迭代次数下限（{@code NFR-SEC-2}：≥ 120000）。 */
    public static final String KEY_PBKDF2_ITERATIONS = "hg.auth.pbkdf2_iterations";

    /**
     * JDBC URL（{@code CFG-31 hg.db.url}）。
     *
     * <p><b>装配开关</b>：该键为空即视为「未装配数据库」——进程仍须可用
     * （{@code /api/v1/ping} 与 {@code /web/**} 照常响应），见 {@link #databaseUrl()}。
     */
    public static final String KEY_DB_URL = "hg.db.url";
    /** 应用数据库账号（{@code CFG-31}；仅 SELECT/INSERT/UPDATE，{@code NFR-SEC-8}）。 */
    public static final String KEY_DB_USER = "hg.db.user";
    /** JDBC 驱动类名（{@code CFG-31}；DM8 为 {@code dm.jdbc.driver.DmDriver}）。 */
    public static final String KEY_DB_DRIVER = "hg.db.driver";
    /** 连接池初始连接数（{@code CFG-31}）。 */
    public static final String KEY_DB_POOL_INITIAL = "hg.db.pool.initial_size";
    /** 连接池最大连接数（{@code CFG-31}）。 */
    public static final String KEY_DB_POOL_MAX = "hg.db.pool.max_size";
    /** 连接池借出超时毫秒（{@code CFG-31}；超时<b>拒绝</b>而非阻塞）。 */
    public static final String KEY_DB_POOL_BORROW_TIMEOUT_MS = "hg.db.pool.borrow_timeout_ms";

    /**
     * 数据库口令的<b>唯一</b>来源环境变量名（{@code CFG-31}「密码不落盘」/ {@code NFR-SEC-1}）。
     *
     * <p>刻意<b>不</b>提供 {@code hg.db.password} 之类的属性键：{@code rejectSecrets} 会拒绝任何
     * 键名含 {@code password} 的属性文件项，口令只能经环境变量注入。
     */
    public static final String ENV_DB_PASSWORD = "HG_DB_PASSWORD";

    /**
     * HGCP 鉴权令牌的<b>唯一</b>来源环境变量名（{@code NFR-SEC-1} / {@code R-CONFIG-5}）。
     *
     * <p>与口令同姿态：{@code ARCHITECTURE.md} §4.2.6 明述「{@code auth_token} 零落盘——启动时由
     * 系统管理员手工录入，<b>仅内存持有</b>」，故<b>不</b>新增 {@code hg.*} 属性键承载它
     * （任何键名含 {@code token} 的属性文件项都会被 {@link #rejectSecrets} 拒绝）。
     *
     * <p>与 {@code CFG-20 hg.tcp.bind/port} 的既有先例一致：{@code hg.*} 只承载<b>非敏感</b>参数，
     * 凭据一律经环境变量注入（见 {@link #secret(String)}）。
     */
    public static final String ENV_HGCP_AUTH_TOKEN = "HG_HGCP_AUTH_TOKEN";

    /**
     * HGCP 组件接口默认端口（{@code CFG-20 hg.tcp.port} 缺省值；见 {@link #tcpPort()}）。
     */
    public static final int DEFAULT_HGCP_PORT = 27400;

    /**
     * HGCP 协议令牌版本（{@code MsgHello.token_version}，{@code CH-013} {@code I-1}）。
     *
     * <p>本期不支持在线轮换（§4.2.6：两侧不一致即拒绝并要求重启），故为常量
     * {@code 1}，<b>不</b>新增配置键。
     */
    public static final int HGCP_TOKEN_VERSION = 1;

    private final Properties properties;

    private Config(Properties properties) {
        this.properties = properties;
    }

    /**
     * 从属性文件装载（文件不存在则返回空配置，不报错——便于无配置直跑）。
     *
     * @param file 属性文件路径；{@code null} 表示不读文件
     * @return 配置实例
     */
    public static Config load(Path file) {
        Properties props = new Properties();
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file);
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "读取配置失败：文件=" + file.toAbsolutePath() + "；原因=" + e.getMessage()
                                + "；复现=检查文件权限与编码（须为 UTF-8 key=value）", e);
            }
            rejectSecrets(props, file);
        }
        return new Config(props);
    }

    /** 空配置（仅默认值）。 */
    public static Config empty() {
        return new Config(new Properties());
    }

    /** 默认装载：{@code -Dhg.config=<path>}，默认 {@code ./hg.properties}。 */
    public static Config bootstrap() {
        String custom = System.getProperty("hg.config");
        Path file = (custom == null || custom.isEmpty()) ? Paths.get("hg.properties") : Paths.get(custom);
        return load(file);
    }

    /**
     * 零落盘守卫：属性文件不得承载敏感值（{@code NFR-SEC-1}）。
     *
     * @param props 已加载属性
     * @param file  来源文件（用于报错定位）
     */
    private static void rejectSecrets(Properties props, Path file) {
        for (String name : props.stringPropertyNames()) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("password") || lower.contains("authcode") || lower.contains("auth_code")
                    || lower.contains("token") || lower.contains("secret")) {
                throw new IllegalStateException(
                        "拒绝装载敏感配置项：键=" + name + "；来源=" + file.toAbsolutePath()
                                + "；原因=敏感字段零落盘红线（NFR-SEC-1 / 附录 B 红线 8）"
                                + "；处置=改用环境变量或进程内存注入（Config.secret()）");
            }
        }
    }

    /**
     * 读字符串配置。
     *
     * @param key          配置键
     * @param defaultValue 缺省值
     * @return 系统属性 &gt; 属性文件 &gt; 默认值
     */
    public String get(String key, String defaultValue) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isEmpty()) {
            return sys;
        }
        String v = properties.getProperty(key);
        return (v == null || v.isEmpty()) ? defaultValue : v;
    }

    /**
     * 读整数配置。
     *
     * @param key          配置键
     * @param defaultValue 缺省值
     * @return 整数值
     */
    public int getInt(String key, int defaultValue) {
        String raw = get(key, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "配置项非整数：键=" + key + "；值=" + raw + "；复现=修正配置后重启（参数未应用）", e);
        }
    }

    /**
     * 读敏感配置：<b>只从进程环境读取</b>，绝不落文件、绝不进日志。
     *
     * @param envName 环境变量名（如 {@code HG_DB_PASSWORD}）
     * @return 字符数组；未设置时返回 {@code null}。调用方用完须 {@link java.util.Arrays#fill} 清零。
     */
    public char[] secret(String envName) {
        String v = System.getenv(envName);
        return (v == null || v.isEmpty()) ? null : v.toCharArray();
    }

    /**
     * 绑定地址守卫：只允许回环地址（{@code NFR-SEC-3} / 附录 B 红线 9）。
     *
     * @param key 绑定地址配置键
     * @return 校验通过的绑定地址
     */
    public String loopbackOnly(String key) {
        String bind = get(key, "127.0.0.1");
        if (!"127.0.0.1".equals(bind) && !"localhost".equals(bind) && !"::1".equals(bind)) {
            throw new IllegalStateException(
                    "拒绝启动：绑定地址=" + bind + "（键 " + key + "）；"
                            + "原因=只允许监听本地回环，禁止对外暴露（NFR-SEC-3 / 附录 B 红线 9）；"
                            + "处置=改回 127.0.0.1 后重启");
        }
        return bind;
    }

    /** 静态资源根目录。 */
    public Path webRoot() {
        return Paths.get(get(KEY_WEB_ROOT, "./web"));
    }

    /** 会话 Cookie 名。 */
    public String sessionCookieName() {
        return get(KEY_SESSION_COOKIE, "HGSESSION");
    }

    /**
     * JDBC URL（{@code CFG-31}）；<b>为空即表示未装配数据库</b>。
     *
     * <p>未配置属合法状态（便于无库直跑与前端联调）：调用方应据此跳过 DAO/服务装配，
     * 而不是启动失败。这是「受配置保护装配」的判据来源。
     *
     * @return URL；未配置返回 {@code null}
     */
    public String databaseUrl() {
        return get(KEY_DB_URL, null);
    }

    /** 应用数据库账号（{@code CFG-31}）。 */
    public String databaseUser() {
        return get(KEY_DB_USER, null);
    }

    /** JDBC 驱动类名（{@code CFG-31}；默认 DM8）。 */
    public String databaseDriver() {
        return get(KEY_DB_DRIVER, "dm.jdbc.driver.DmDriver");
    }

    /**
     * 数据库口令：<b>只从环境变量 {@link #ENV_DB_PASSWORD} 读取</b>（{@code CFG-31}「密码不落盘」）。
     *
     * <p>调用方持有返回数组的所有权，用毕须清零。
     *
     * @return 口令字符数组；未设置返回 {@code null}
     */
    public char[] databasePassword() {
        return secret(ENV_DB_PASSWORD);
    }

    /**
     * HGCP 鉴权令牌：<b>只从环境变量 {@link #ENV_HGCP_AUTH_TOKEN} 读取</b>，零落盘。
     *
     * <p><b>刻意返回 {@code char[]} 而非 {@code String}</b>：{@code HgcpClient} 内部以
     * {@code String} 持有令牌并填入 {@code MsgHello.auth_token}（{@code ARCHITECTURE.md} §4.2.6），
     * 该形态不可更动；故此处与 {@code JdbcConnectionFactory} 同姿态——把明文
     * <b>留在原生数组里</b>，由调用方在构造完成后立即 {@link java.util.Arrays#fill} 清零，
     * 避免调用方另持一份不受控的 {@code String} 副本（{@code NFR-SEC-1}）。
     *
     * <p>调用方持有返回数组的所有权，用毕须清零。
     *
     * @return 令牌字符数组；<b>未设置</b>返回 {@code null}（装配侧据此判「未配置 ⇒ 不装配 HGCP 桥」）
     */
    public char[] hgcpAuthToken() {
        return secret(ENV_HGCP_AUTH_TOKEN);
    }

    /**
     * HGCP 组件通道监听端口（{@code CFG-20 hg.tcp.port}，默认 {@value #DEFAULT_HGCP_PORT}）。
     *
     * <p>取值<b>恒为回环</b>：地址侧复用 {@code CFG-20 hg.tcp.bind} 的既有回环守卫
     * {@link #loopbackOnly(String)}，不再另设键。
     *
     * @return 端口
     */
    public int tcpPort() {
        return getInt(KEY_TCP_PORT, DEFAULT_HGCP_PORT);
    }

    /**
     * 数据库是否已配置（受配置保护装配的开关）。
     *
     * @return 已配置 {@code hg.db.url} 返回 {@code true}
     */
    public boolean databaseConfigured() {
        return databaseUrl() != null;
    }
}
