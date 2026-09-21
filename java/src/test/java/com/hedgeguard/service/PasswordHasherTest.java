package com.hedgeguard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PasswordHasher} 单元测试（{@code NFR-SEC-2} / {@code ARCHITECTURE.md} §7.2.2）。
 *
 * <p>验证四条要件：①JDK 内置 {@code PBKDF2WithHmacSHA256}；②盐随机且每次不同；
 * ③迭代次数 ≥ 120000 并随编码记录（可升级强度而不失效历史口令）；
 * ④强度策略与一次性随机口令满足「长度 + 大小写 + 数字 + 符号」。
 */
class PasswordHasherTest {

    @Test
    @DisplayName("哈希与校验：正确口令通过、错误口令拒绝、编码自带算法前缀")
    void hashAndVerify() {
        String encoded = PasswordHasher.hash("Hg#2026Trading01");
        assertTrue(PasswordHasher.isEncoded(encoded), "应为合法编码串：" + encoded);
        assertTrue(encoded.startsWith("pbkdf2-sha256$"), "编码前缀应标识算法");
        assertTrue(PasswordHasher.verify("Hg#2026Trading01", encoded), "正确口令应通过");
        assertFalse(PasswordHasher.verify("Hg#2026Trading02", encoded), "错误口令应拒绝");
        assertFalse(PasswordHasher.verify("", encoded), "空口令应拒绝");
        assertFalse(PasswordHasher.verify("Hg#2026Trading01", "明文口令"), "非编码串应拒绝");
        assertFalse(PasswordHasher.verify("Hg#2026Trading01", null), "null 编码应拒绝");
    }

    @Test
    @DisplayName("盐随机：同一口令两次哈希不同，但都能校验通过")
    void saltIsRandomPerHash() {
        String first = PasswordHasher.hash("Hg#2026Trading01");
        String second = PasswordHasher.hash("Hg#2026Trading01");
        assertNotEquals(first, second, "每次哈希须用新盐");
        assertTrue(PasswordHasher.verify("Hg#2026Trading01", first));
        assertTrue(PasswordHasher.verify("Hg#2026Trading01", second));
    }

    @Test
    @DisplayName("迭代次数：默认 120000 且记录在编码内；低于下限直接拒绝")
    void iterationsAreRecordedAndBounded() {
        assertEquals("PBKDF2WithHmacSHA256", PasswordHasher.ALGORITHM, "须用 JDK 内置算法");
        assertEquals(16, PasswordHasher.SALT_BYTES, "盐 16 字节");
        assertEquals(120_000, PasswordHasher.DEFAULT_ITERATIONS, "NFR-SEC-2 下限");
        String encoded = PasswordHasher.hash("Hg#2026Trading01");
        assertEquals(120_000, PasswordHasher.iterationsOf(encoded), "迭代次数须随编码记录");
        assertThrows(IllegalArgumentException.class,
                () -> PasswordHasher.hash("Hg#2026Trading01", 1000),
                "低于下限的迭代次数须拒绝");
        assertEquals(-1, PasswordHasher.iterationsOf("明文"));
    }

    @Test
    @DisplayName("强度策略：长度 / 大写 / 小写 / 数字 / 符号 逐项判定")
    void strengthPolicy() {
        PasswordHasher.assertStrength("Hg#2026Trading01", 12);
        assertThrows(IllegalArgumentException.class,
                () -> PasswordHasher.assertStrength("Hg#2026", 12), "过短应拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> PasswordHasher.assertStrength("hg#2026trading01", 12), "缺大写应拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> PasswordHasher.assertStrength("HG#2026TRADING01", 12), "缺小写应拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> PasswordHasher.assertStrength("Hg#TradingPass", 12), "缺数字应拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> PasswordHasher.assertStrength("Hg2026Trading01", 12), "缺符号应拒绝");
        // 错误信息不得回显口令本身
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PasswordHasher.assertStrength("short", 12));
        assertFalse(e.getMessage().contains("short"), "异常不得回显明文口令：" + e.getMessage());
    }

    @Test
    @DisplayName("一次性随机口令：长度达标、四类字符齐备、多次生成不重复")
    void oneTimePasswordIsStrongAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String pwd = PasswordHasher.randomPassword(16);
            assertEquals(16, pwd.length());
            assertTrue(pwd.matches(".*[A-Z].*"), "含大写" + pwd);
            assertTrue(pwd.matches(".*[a-z].*"), "含小写" + pwd);
            assertTrue(pwd.matches(".*[0-9].*"), "含数字" + pwd);
            assertTrue(pwd.matches(".*[^A-Za-z0-9].*"), "含符号" + pwd);
            // 随机生成的弱口令不得可预测：至少存在差异（避免随机源被固定）
            assertFalse(seen.contains(pwd), "随机口令不得重复：" + pwd);
            seen.add(pwd);
        }
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.randomPassword(8),
                "长度不足须拒绝");
    }
}
