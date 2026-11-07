package com.hedgeguard.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-051} 验收标准 2「调度任务不得绕过 service 层直接调 DAO」的静态守卫。
 *
 * <p><b>守卫有牙</b>：直接扫描 {@code java/src/main/java/com/hedgeguard/job/**} 的生产源码，
 * 断言零 {@code com.hedgeguard.dao} 引用、零 SQL 语句形态、零墙钟直取；并断言 7 个任务类各自
 * 只经 {@link JobPorts} 完成业务动作。任何人日后往 {@code job} 包塞一个 DAO 调用即红。
 */
class JobLayeringTest {

    private static final Pattern SQL_STMT = Pattern.compile(
            "(?i)\\b(select\\s+[a-z_*\"'`]|insert\\s+into\\b|update\\s+[a-z_]\\w*\\s+set\\b"
                    + "|delete\\s+from\\b|create\\s+(table|index|view)\\b|drop\\s+table\\b)");

    private static final Pattern DAO_REF = Pattern.compile("com\\.hedgeguard\\.dao");

    private static final Pattern WALL_CLOCK = Pattern.compile(
            "System\\.currentTimeMillis\\(|LocalDate\\.now\\(\\)|LocalDateTime\\.now\\(\\)"
                    + "|Instant\\.now\\(\\)|new\\s+Date\\(\\)");

    private static final List<String> JOB_CLASSES = Arrays.asList(
            "DailySettlementJob.java", "PostRiskReportScheduledJob.java", "ReportGenerationJob.java",
            "ReconciliationJob.java", "PlanExpiryReminderJob.java", "ReportPeriodReminderJob.java",
            "EventFlowWatermarkJob.java");

    @Test
    @DisplayName("验收2：job 包生产源码零 com.hedgeguard.dao 引用、零 SQL、零墙钟直取")
    void jobPackageHasNoDaoSqlOrWallClock() throws IOException {
        Path dir = jobSourceDir();
        assertTrue(Files.isDirectory(dir), "找不到 job 包源码目录：" + dir);
        List<String> violations = new ArrayList<>();
        List<String> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toString()));
        }
        assertTrue(files.size() >= JOB_CLASSES.size() + 8,
                "job 包源文件数异常（预期框架 + 7 任务类）：" + files.size());

        for (String file : files) {
            String body = stripComments(new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8));
            if (DAO_REF.matcher(body).find()) {
                violations.add("DAO 引用: " + file);
            }
            if (SQL_STMT.matcher(body).find()) {
                violations.add("SQL 形态: " + file);
            }
            if (WALL_CLOCK.matcher(body).find()) {
                violations.add("墙钟直取: " + file);
            }
        }
        assertEquals(new ArrayList<String>(), violations,
                "调度层必须只经 JobPorts（L3 服务端口）完成业务动作，不得直连 DAO / SQL / 墙钟");
    }

    @Test
    @DisplayName("验收2：7 个任务类均存在、均为 ScheduledJob，且各自实现 execute(ctx, ports)")
    void sevenJobClassesImplementPortBasedExecute() throws IOException {
        Path dir = jobSourceDir();
        for (String name : JOB_CLASSES) {
            Path f = dir.resolve(name);
            assertTrue(Files.isRegularFile(f), "缺少任务类：" + name);
            String body = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            assertTrue(body.contains("extends AbstractScheduledJob") || body.contains("implements ScheduledJob"),
                    name + " 须实现 ScheduledJob");
            assertTrue(body.contains("JobOutcome.Summary execute(JobContext ctx, JobPorts ports)"),
                    name + " 须经 JobPorts 完成业务动作");
            assertFalse(body.contains("com.hedgeguard.dao"), name + " 不得引用 DAO");
        }
    }

    @Test
    @DisplayName("既有 PostRiskReportJob（T-180）保持只依赖 L3 PostRiskReportService，未被改动")
    void existingPostRiskJobUnchanged() throws IOException {
        Path f = jobSourceDir().resolve("PostRiskReportJob.java");
        assertTrue(Files.isRegularFile(f));
        String body = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        assertTrue(body.contains("PostRiskReportService"));
        assertFalse(body.contains("com.hedgeguard.dao"));
    }

    private static Path jobSourceDir() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("java/src/main/java/com/hedgeguard/job");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 java/src/main/java/com/hedgeguard/job");
    }

    /** 去除 // 与 slash-star 注释，避免 javadoc 里的说明文字误伤扫描（只判可执行代码）。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder();
        boolean inBlock = false;
        boolean inLine = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && n == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (c == '/' && n == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '/' && n == '*') {
                inBlock = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
