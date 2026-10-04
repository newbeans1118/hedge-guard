package com.hedgeguard.risk_intraday;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-050} 验收 3：{@code risk_intraday} 主源<b>全过程无 DB 查询</b>。
 *
 * <p>机械判据（对照 {@code E5}「{@code dao} 之外不得出现 SQL」与包纪律「本包不得出现 SQL」）：
 * 主源不得 {@code import java.sql.*}，不得出现 SQL 语句形态。
 * 这使「指标在内存中计算、不因 DB 查询阻塞事件消费」具备静态可复核证据。
 */
class RiskIntradayNoSqlTest {

    private static final Pattern SQL_STATEMENT = Pattern.compile(
            "(?i)\\b(select\\s+[\\w*]|insert\\s+into|update\\s+\\w+\\s+set|delete\\s+from)\\b");

    @Test
    @DisplayName("主源零 SQL / 零 java.sql 依赖（内存计算，FR-RISK-13 验收 4）")
    void mainSourcesContainNoSql() throws IOException {
        Path root = Paths.get("src/main/java/com/hedgeguard/risk_intraday");
        assertTrue(Files.isDirectory(root), "缺少主源目录：" + root.toAbsolutePath());
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertFalse(files.isEmpty(), "未发现主源文件，判据空转");

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (text.contains("import java.sql.")) {
                violations.add(file.getFileName() + " 引入 java.sql");
            }
            if (SQL_STATEMENT.matcher(text).find()) {
                violations.add(file.getFileName() + " 含 SQL 语句形态");
            }
        }
        assertTrue(violations.isEmpty(), "risk_intraday 主源不得含 SQL / java.sql：\n"
                + String.join("\n", violations));
    }
}
