package com.hedgeguard.web;

import com.hedgeguard.common.Config;
import com.hedgeguard.common.SensitiveLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全红线单元测试：日志脱敏（{@code NFR-SEC-1} ③）与敏感配置零落盘（{@code NFR-SEC-1} ①②）。
 */
class SensitiveLoggingAndConfigTest {

    @Test
    @DisplayName("日志脱敏：CFG-19 四项敏感字段的值被替换为 ***（键名保留）")
    void masksSensitiveFieldValues() {
        String masked = SensitiveLog.MaskingFilter.mask(
                "login user=zhangsan password=Secret!234&auth_code=998877 fund_password=8888 auth_token=abcdef");
        assertFalse(masked.contains("Secret!234"), "口令明文不得出现在日志：" + masked);
        assertFalse(masked.contains("998877"), "AuthCode 不得出现在日志：" + masked);
        assertFalse(masked.contains("8888"), "资金密码不得出现在日志：" + masked);
        assertFalse(masked.contains("abcdef"), "auth_token 不得出现在日志：" + masked);
        assertTrue(masked.contains("password="));
        assertTrue(masked.contains("***"));
        assertTrue(masked.contains("user=zhangsan"), "非敏感字段须保留：" + masked);
    }

    @Test
    @DisplayName("日志脱敏覆盖 JSON 形态与键名大小写")
    void masksJsonFormAndCaseInsensitiveKeys() {
        String masked = SensitiveLog.MaskingFilter.mask("{\"Password\":\"P@ssw0rd\",\"user\":\"u1\"}");
        assertFalse(masked.contains("P@ssw0rd"));
        assertTrue(masked.contains("***"));
    }

    @Test
    @DisplayName("过滤器作用于 LogRecord 的消息与参数")
    void filterRewritesRecord() {
        SensitiveLog.MaskingFilter filter = new SensitiveLog.MaskingFilter();
        LogRecord record = new LogRecord(Level.INFO, "handshake auth_token=TOPSECRET");
        record.setParameters(new Object[]{"password=abc123"});
        assertTrue(filter.isLoggable(record));
        assertFalse(record.getMessage().contains("TOPSECRET"));
        assertFalse(((String) record.getParameters()[0]).contains("abc123"));
    }

    @Test
    @DisplayName("Kv 结构化日志在 toString 时脱敏")
    void kvMasksOnToString() {
        String line = new SensitiveLog.Kv()
                .put("traceId", "HGT1")
                .put("auth_token", "SHOULD-NOT-APPEAR")
                .put("status", 200)
                .toString();
        assertFalse(line.contains("SHOULD-NOT-APPEAR"));
        assertTrue(line.contains("traceId=HGT1"));
    }

    @Test
    @DisplayName("CFG-19 敏感字段清单不得为空白（固定集合，不得删减）")
    void sensitiveFieldListIsFixed() {
        assertEquals(4, SensitiveLog.SENSITIVE_FIELDS.size());
        assertTrue(SensitiveLog.SENSITIVE_FIELDS.contains("password"));
        assertTrue(SensitiveLog.SENSITIVE_FIELDS.contains("auth_code"));
        assertTrue(SensitiveLog.SENSITIVE_FIELDS.contains("fund_password"));
        assertTrue(SensitiveLog.SENSITIVE_FIELDS.contains("auth_token"));
    }

    @Test
    @DisplayName("属性文件中的敏感键被拒绝装载（零落盘红线）")
    void configRefusesSecretsInFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("hg.properties");
        Files.write(f, "hg.web.port=27402\nhg.db.password=plain-text\n".getBytes(StandardCharsets.UTF_8));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Config.load(f));
        assertTrue(e.getMessage().contains("敏感字段零落盘"));
        assertTrue(e.getMessage().contains("hg.db.password"));
    }

    @Test
    @DisplayName("非敏感配置可正常读取；系统属性优先于文件；默认值生效")
    void configReadsNonSensitiveValues(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("hg.properties");
        Files.write(f, "hg.web.port=28080\nhg.web.root=/srv/hg/web\n".getBytes(StandardCharsets.UTF_8));
        Config config = Config.load(f);
        assertEquals(28080, config.getInt(Config.KEY_HTTP_PORT, 27402));
        assertEquals("/srv/hg/web", config.get(Config.KEY_WEB_ROOT, "./web"));
        assertEquals("./web", Config.empty().get(Config.KEY_WEB_ROOT, "./web"));
        assertEquals(30, config.getInt(Config.KEY_SESSION_IDLE_MINUTES, 30));

        System.setProperty(Config.KEY_HTTP_PORT, "29090");
        try {
            assertEquals(29090, config.getInt(Config.KEY_HTTP_PORT, 27402));
            assertEquals("HGSESSION", config.sessionCookieName());
        } finally {
            System.clearProperty(Config.KEY_HTTP_PORT);
        }
    }

    @Test
    @DisplayName("配置项非整数时明确报错（含键、值、复现方式）")
    void configRejectsMalformedInteger(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("hg.properties");
        Files.write(f, "hg.web.port=abc\n".getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Config.load(f).getInt(Config.KEY_HTTP_PORT, 27402));
        assertTrue(e.getMessage().contains("hg.web.port"));
        assertTrue(e.getMessage().contains("abc"));
    }

    @Test
    @DisplayName("绑定地址守卫：仅回环放行，对外暴露地址被拒绝（附录 B 红线 9）")
    void loopbackGuardRejectsPublicBind() {
        System.setProperty(Config.KEY_HTTP_BIND, "0.0.0.0");
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> Config.empty().loopbackOnly(Config.KEY_HTTP_BIND));
            assertTrue(e.getMessage().contains("禁止对外暴露"));
        } finally {
            System.clearProperty(Config.KEY_HTTP_BIND);
        }
        System.setProperty(Config.KEY_HTTP_BIND, "127.0.0.1");
        try {
            assertEquals("127.0.0.1", Config.empty().loopbackOnly(Config.KEY_HTTP_BIND));
        } finally {
            System.clearProperty(Config.KEY_HTTP_BIND);
        }
    }

    @Test
    @DisplayName("敏感配置只从环境变量读取，未设置时返回 null（不得回退到属性文件）")
    void secretReadsFromEnvironmentOnly() {
        assertEquals(null, Config.empty().secret("HG_TEST_UNSET_VARIABLE_XYZ"));
    }

    @Test
    @DisplayName("D-19：HG_DB_PASSWORD 未设置时 databasePassword() 返回 null（口令只从环境变量来）")
    void databasePasswordIsNullWhenEnvUnset() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv(Config.ENV_DB_PASSWORD) == null,
                "本机已设置 " + Config.ENV_DB_PASSWORD + "，跳过'未设置即 null'断言");
        assertEquals(null, Config.empty().databasePassword());
    }

    @Test
    @DisplayName("D-19：CFG-31 非敏感键可正常装载；hg.db.password 仍被零落盘守卫拒绝")
    void dbKeysRespectZeroOnDiskGuard(@TempDir Path dir) throws IOException {
        Path ok = dir.resolve("ok.properties");
        Files.write(ok, ("hg.db.url=jdbc:dm://127.0.0.1:5236/HG\n"
                + "hg.db.user=hg_app\n"
                + "hg.db.driver=dm.jdbc.driver.DmDriver\n"
                + "hg.db.pool.initial_size=2\n"
                + "hg.db.pool.max_size=8\n"
                + "hg.db.pool.borrow_timeout_ms=3000\n").getBytes(StandardCharsets.UTF_8));
        Config config = Config.load(ok);
        assertEquals("jdbc:dm://127.0.0.1:5236/HG", config.databaseUrl());
        assertTrue(config.databaseConfigured(), "配置了 hg.db.url 即视为已装配数据库");
        assertEquals("hg_app", config.databaseUser());
        assertEquals("dm.jdbc.driver.DmDriver", config.databaseDriver());

        Path bad = dir.resolve("bad.properties");
        Files.write(bad,
                "hg.db.url=jdbc:dm://127.0.0.1:5236/HG\nhg.db.password=x\n"
                        .getBytes(StandardCharsets.UTF_8));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Config.load(bad));
        assertTrue(e.getMessage().contains("hg.db.password"), "口令键须被拒绝，实际=" + e.getMessage());
    }
}
