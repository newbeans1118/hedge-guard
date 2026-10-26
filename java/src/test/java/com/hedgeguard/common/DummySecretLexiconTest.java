package com.hedgeguard.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-4/R-5 判据：哑口令词形清单（{@code T-082b} 重做承接，{@code NFR-SEC-1} 敏感字段零落盘红线）。
 *
 * <p><b>词形清单为冻结域字面量</b>（{@code T-082b} 工单：判据词须冻结域字面量），不得从配置或
 * 外部文件读取——判据本体只依赖自身常量，避免「判据与被判对象同源」的自证陷阱。
 *
 * <p><b>cpp 夹具显式豁免清单</b>（扫描面仅 {@code java/src}，本清单载明 cpp 侧既有命中，
 * 防未来 Java 侧误引为判据）：
 * <ul>
 *   <li>{@code cpp/src/ctp_adapter/tests/fake_gateway.h:141} —— {@code DUMMY_BANK_PASSWORD}，
 *       测试夹具哑值（引入提交 {@code 5e1bb87}，T-033 CTP 移植线，早于 T-082）；</li>
 *   <li>{@code cpp/src/ctp_adapter/tests/ct_mapping_test.cc:37,46} —— {@code DUMMY_PASSWORD}，
 *       同上夹具域（:46 为「映射须携带口令（内存，不落盘）」断言）；</li>
 *   <li>{@code cpp/src/ctp_adapter/tests/ct_adapter_test.cc:42,310} —— 同上夹具域
 *       （:310 为「口令不得出现在日志呈现」脱敏断言本体）。</li>
 * </ul>
 * 上述 5 处均为 C++ 测试夹具哑值/断言引用，非生产凭据、非 Java 判据清单成员。
 *
 * <p><b>自排除口径</b>：本测试自身源文件承载判据词字面量，扫描时须显式排除本文件
 * （制品内嵌自身词形的自排除，与落痕文档自排除同型纪律）。
 */
class DummySecretLexiconTest {

    /** 判据扫描面下限：java/src 当前实存 772 个文件；低于 700 视为扫描根解析失准（防空转假绿）。 */
    private static final int SCAN_FILE_COUNT_FLOOR = 700;

    /**
     * 哑口令词形清单（冻结域字面量，≥5 形）。语义：这些词形只允许出现在测试夹具哑值语境
     * （cpp 侧，见类注豁免清单），<b>不得出现在 java/src 任何文件</b>——一旦出现即视作
     * 「哑值渗入生产/判据面」的哨兵信号。
     */
    private static final String[] LEXICON = {
            "DUMMY_BANK_PASSWORD",
            "DUMMY_PASSWORD",
            "DUMMY_AUTH_CODE",
            "DUMMY_TOKEN",
            "DUMMY_SECRET",
            "dummy_secret",
    };

    /** 本测试自身源文件相对路径（自排除用）。 */
    private static final String SELF_RELATIVE_PATH =
            "src/test/java/com/hedgeguard/common/DummySecretLexiconTest.java";

    /**
     * R-4 ①：词形清单冻结且两两互不包含——防清单词形演进时静默互吞
     * （丢失线 te-verify-t082 观察项 O-1 判例的推广：{@code DUMMY_PASSWORD} 与
     * {@code DUMMY_BANK_PASSWORD} 若互为连续子串，逐词 grep 将互相污染命中统计）。
     */
    @Test
    @DisplayName("R-4①：词形清单冻结（6 形、非空、两两互不为连续子串）")
    void lexiconIsFrozenAndPairwiseNonOverlapping() {
        assertEquals(6, LEXICON.length, "词形清单为冻结集合，增删须同步 cpp 豁免清单与本注记");
        for (String form : LEXICON) {
            assertFalse(form == null || form.isEmpty(), "词形不得为空");
        }
        for (int i = 0; i < LEXICON.length; i++) {
            for (int j = 0; j < LEXICON.length; j++) {
                if (i == j) {
                    continue;
                }
                // R-5 推广：清单内任意两词形互不为连续子串（含自身大小写异形——contains 逐字符区分大小写）
                assertFalse(isContiguousSubstring(LEXICON[i], LEXICON[j]),
                        "词形 " + LEXICON[i] + " 不得为 " + LEXICON[j] + " 的连续子串（防互吞）");
            }
        }
    }

    /**
     * R-5：{@code DUMMY_PASSWORD} 与 {@code DUMMY_BANK_PASSWORD} 包含关系逐字符重测
     * （丢失线 te-verify-t082 观察项 O-1：非子串——以测试钉死该关系，防清单词形演进时静默互吞）。
     * 断言经显式逐字符对位（{@link #isContiguousSubstring}），不委托 {@code String.contains}。
     */
    @Test
    @DisplayName("R-5：DUMMY_PASSWORD 逐字符核验 NOT ⊆ DUMMY_BANK_PASSWORD（O-1 判例钉死）")
    void dummyPasswordIsNotSubstringOfDummyBankPassword() {
        String bank = "DUMMY_BANK_PASSWORD";
        String plain = "DUMMY_PASSWORD";
        // 显式逐字符对位：在每个可能起点逐位比较，不存在任何完全匹配的连续窗口
        assertFalse(isContiguousSubstring(plain, bank),
                plain + " 不得是 " + bank + " 的连续子串（丢失线 O-1 判例）");
        // 正向锚：共享前缀 DUMMY 与共享后缀 _PASSWORD 均成立，证两词形确实同族而非判据恒假
        assertTrue(isContiguousSubstring("DUMMY", bank), "共享前缀锚");
        assertTrue(isContiguousSubstring("_PASSWORD", bank), "共享后缀锚");
    }

    /**
     * 判据有牙性正控：逐字符探测器对已知包含对返回 true、对大小写异形返回 false——
     * 证探测器非恒真/恒假（判据非空转，见 {@code feedback} 判据设计纪律）。
     */
    @Test
    @DisplayName("R-4②：逐字符探测器正控（真包含⇒true；大小写异形⇒false）")
    void substringDetectorHasTeeth() {
        assertTrue(isContiguousSubstring("AA", "XXAAXX"), "真包含正控");
        assertTrue(isContiguousSubstring("DUMMY_PASSWORD", "x" + "DUMMY_PASSWORD" + "y"), "真包含正控（词形级）");
        assertFalse(isContiguousSubstring("dummy_password", "DUMMY_PASSWORD"), "大小写异形负控");
        assertFalse(isContiguousSubstring("DUMMY_PASSWORDZ", "DUMMY_PASSWORD"), "长度倒置负控");
    }

    /**
     * R-4③：词形清单在 java/src 全仓 0 命中（自排除本文件；扫描面下限防根解析失准空转）。
     * 负例（判据有牙）以源码变异独立执行：注入任一词形 → 本用例红 → 复位（T-082b 交付留痕）。
     */
    @Test
    @DisplayName("R-4③：哑口令词形清单 java/src 全仓 0 命中（cpp 夹具域豁免见类注）")
    void lexiconHasZeroHitsInJavaSrc() throws IOException {
        Path javaRoot = locateJavaRoot();
        Path srcRoot = javaRoot.resolve("src");
        Path self = javaRoot.resolve(SELF_RELATIVE_PATH).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(srcRoot), "扫描根必须实存：javaRoot=" + javaRoot);

        List<String> hits = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> paths = Files.walk(srcRoot)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                Path abs = p.toAbsolutePath().normalize();
                if (abs.equals(self)) {
                    continue; // 自排除：本文件承载判据词字面量
                }
                scanned++;
                String content = new String(Files.readAllBytes(abs), StandardCharsets.ISO_8859_1);
                for (String form : LEXICON) {
                    int idx = 0;
                    while ((idx = content.indexOf(form, idx)) >= 0) {
                        hits.add(javaRoot.relativize(abs).toString().replace('\\', '/')
                                + ": line " + (1 + lineOf(content, idx)) + " 命中词形 " + form);
                        idx += form.length();
                    }
                }
            }
        }
        assertTrue(scanned >= SCAN_FILE_COUNT_FLOOR,
                "扫描文件数 " + scanned + " 低于下限 " + SCAN_FILE_COUNT_FLOOR + "，扫描根解析失准（防空转）");
        assertTrue(hits.isEmpty(), "哑口令词形渗入 java/src（NFR-SEC-1 判据红）：" + String.join("; ", hits));
    }

    /** 逐字符连续子串判定（区分大小写；显式双循环，不委托 String.contains）。 */
    private static boolean isContiguousSubstring(String needle, String haystack) {
        char[] n = needle.toCharArray();
        char[] h = haystack.toCharArray();
        if (n.length > h.length) {
            return false;
        }
        outer:
        for (int start = 0; start + n.length <= h.length; start++) {
            for (int k = 0; k < n.length; k++) {
                if (h[start + k] != n[k]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int lineOf(String content, int index) {
        int line = 1;
        for (int i = 0; i < index && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 定位 {@code java/} 目录（surefire 工作目录通常即 basedir=java/；从当前目录向上最多 5 级兜底）。
     */
    private static Path locateJavaRoot() {
        Path dir = Paths.get("").toAbsolutePath().normalize();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(SELF_RELATIVE_PATH))
                    && Files.isDirectory(dir.resolve("src/main/java/com/hedgeguard"))) {
                return dir;
            }
        }
        throw new IllegalStateException("未能定位 java/ 扫描根（工作目录=" + Paths.get("").toAbsolutePath() + "）");
    }
}
