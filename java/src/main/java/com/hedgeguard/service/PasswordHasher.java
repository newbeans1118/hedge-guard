package com.hedgeguard.service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * 口令加盐哈希（{@code NFR-SEC-2}；{@code ARCHITECTURE.md} §7.2.2「密码哈希：不引入 BCrypt，
 * 用 JDK 内置 {@code PBKDF2WithHmacSHA256}」）。
 *
 * <p>存储格式（本类自管盐与迭代参数，故须自带编码格式）：
 * <pre>
 *   pbkdf2-sha256$&lt;iterations&gt;$&lt;saltBase64&gt;$&lt;hashBase64&gt;
 * </pre>
 * 例：{@code pbkdf2-sha256$120000$Yk9m…==$3Qq1…=}
 *
 * <p>纪律：
 * <ul>
 *   <li>盐为 16 字节 {@link SecureRandom} 随机值（每次设置口令都重新生成，<b>不</b>复用）；</li>
 *   <li>迭代次数默认 {@value #DEFAULT_ITERATIONS}（{@code ≥ 120000}，{@code NFR-SEC-2} 下限），
 *       校验时以<b>编码内记录的</b>迭代次数为准，便于将来提升强度而不失效历史口令；</li>
 *   <li>比对用 {@link MessageDigest#isEqual}（避免逐字节短路比较的时序侧信道）；</li>
 *   <li><b>明文永不返回、永不入库、永不入日志</b>——本类无任何输出明文的路径。</li>
 * </ul>
 */
public final class PasswordHasher {

    /** JDK 内置算法名（{@code §7.2.2} 指定，勿改为 BCrypt/SCrypt）。 */
    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /** 编码前缀（用于识别格式版本）。 */
    public static final String PREFIX = "pbkdf2-sha256";

    /** 盐长度（字节）。 */
    public static final int SALT_BYTES = 16;

    /** 派生密钥长度（位）。 */
    public static final int KEY_BITS = 256;

    /** 默认迭代次数（{@code NFR-SEC-2} 下限 120000）。 */
    public static final int DEFAULT_ITERATIONS = 120_000;

    /** 编码字段分隔符。 */
    private static final char SEPARATOR = '$';

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /**
     * 生成口令哈希（默认迭代次数）。
     *
     * @param password 明文口令（仅内存持有）
     * @return 编码后的哈希串
     */
    public static String hash(String password) {
        return hash(password, DEFAULT_ITERATIONS);
    }

    /**
     * 生成口令哈希（指定迭代次数）。
     *
     * @param password   明文口令
     * @param iterations 迭代次数（须 ≥ 120000）
     * @return 编码后的哈希串
     */
    public static String hash(String password, int iterations) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException(
                    "口令不得为空；复现=核对调用点是否误传空串（空口令不允许设置）");
        }
        if (iterations < DEFAULT_ITERATIONS) {
            throw new IllegalArgumentException("迭代次数不得低于 " + DEFAULT_ITERATIONS
                    + "（NFR-SEC-2 下限）：实际=" + iterations + "；复现=按 CFG-23b 调整");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password.toCharArray(), salt, iterations, KEY_BITS);
        return PREFIX + SEPARATOR + iterations + SEPARATOR
                + Base64.getEncoder().encodeToString(salt) + SEPARATOR
                + Base64.getEncoder().encodeToString(derived);
    }

    /**
     * 校验口令（以编码内记录的迭代次数为准）。
     *
     * @param password 待校验明文口令
     * @param encoded  存储的编码哈希串
     * @return 匹配返回 {@code true}；参数非法或格式不符返回 {@code false}
     */
    public static boolean verify(String password, String encoded) {
        if (password == null || encoded == null || !isEncoded(encoded)) {
            return false;
        }
        String[] parts = encoded.split("\\" + SEPARATOR);
        if (parts.length != 4) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (iterations < 1 || salt.length == 0 || expected.length == 0) {
            return false;
        }
        byte[] actual = derive(password.toCharArray(), salt, iterations, expected.length * 8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 是否为合法编码串（用于拒绝把明文误当哈希存入）。
     *
     * @param value 待判定值
     * @return 是编码哈希返回 {@code true}
     */
    public static boolean isEncoded(String value) {
        return value != null && value.startsWith(PREFIX + SEPARATOR)
                && value.split("\\" + SEPARATOR).length == 4;
    }

    /**
     * 取编码内记录的迭代次数（用于运维核对强度是否达标）。
     *
     * @param encoded 编码哈希串
     * @return 迭代次数；格式不符返回 {@code -1}
     */
    public static int iterationsOf(String encoded) {
        if (!isEncoded(encoded)) {
            return -1;
        }
        try {
            return Integer.parseInt(encoded.split("\\" + SEPARATOR)[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 生成一次性随机口令（重置口令时使用；不落日志，仅返回一次）。
     *
     * @param length 长度（≥ 12）
     * @return 随机口令（含大小写、数字与符号各至少一个）
     */
    public static String randomPassword(int length) {
        if (length < 12) {
            throw new IllegalArgumentException(
                    "一次性口令长度不得低于 12；实际=" + length + "；复现=按 CFG-23b 下限生成");
        }
        final String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        final String lower = "abcdefghijkmnopqrstuvwxyz";
        final String digits = "23456789";
        final String symbols = "!@#$%^&*-_=+?";
        String all = upper + lower + digits + symbols;
        char[] out = new char[length];
        out[0] = upper.charAt(RANDOM.nextInt(upper.length()));
        out[1] = lower.charAt(RANDOM.nextInt(lower.length()));
        out[2] = digits.charAt(RANDOM.nextInt(digits.length()));
        out[3] = symbols.charAt(RANDOM.nextInt(symbols.length()));
        for (int i = 4; i < length; i++) {
            out[i] = all.charAt(RANDOM.nextInt(all.length()));
        }
        for (int i = length - 1; i > 3; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = out[i];
            out[i] = out[j];
            out[j] = tmp;
        }
        return new String(out);
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("口令派生失败：算法=" + ALGORITHM + " 迭代=" + iterations
                    + "；复现=核对 JDK 是否提供该算法（JDK 11 内置）", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 口令强度校验（{@code NFR-SEC-2}：长度 + 大小写 + 数字 + 符号）。
     *
     * @param password  明文口令
     * @param minLength 最小长度（{@code CFG-23b hg.auth.password_min_length}，默认 12）
     */
    public static void assertStrength(String password, int minLength) {
        StringBuilder missing = new StringBuilder();
        if (password == null || password.length() < minLength) {
            missing.append("长度≥").append(minLength).append(" ");
        }
        if (password == null || !password.matches(".*[A-Z].*")) {
            missing.append("大写字母 ");
        }
        if (password == null || !password.matches(".*[a-z].*")) {
            missing.append("小写字母 ");
        }
        if (password == null || !password.matches(".*[0-9].*")) {
            missing.append("数字 ");
        }
        if (password == null || !password.matches(".*[^A-Za-z0-9].*")) {
            missing.append("符号 ");
        }
        if (missing.length() > 0) {
            throw new IllegalArgumentException("口令强度不足，缺少：" + missing.toString().trim()
                    + "；复现=按 CFG-23b 强度策略设置（不满足则拒绝，不以明文回显口令）");
        }
    }
}
