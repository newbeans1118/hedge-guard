package com.hedgeguard.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-235} 验收 1/2：<b>响应面日期 / 时间序列化口径</b>的可执行守卫。
 *
 * <p><b>依据</b>：{@code ARCHITECTURE.md} §4.1.1「时间 = ISO-8601 {@code yyyy-MM-dd'T'HH:mm:ss}
 * （本地时区 {@code Asia/Shanghai}）；纯日期用 {@code yyyy-MM-dd}」。
 *
 * <p><b>缺陷（{@code T-132} 复核 {@code O-1}，非 {@code T-132} 引入）</b>：{@link JsonCodec} 未注册
 * {@code java.time} 适配器时，Gson 走<b>反射</b>序列化 {@code java.time} 的<b>私有字段</b>：
 * <ul>
 *   <li>{@code LocalDate} ⇒ {@code {"year":2026,"month":1,"day":1}}（对象，非 {@code string}）；</li>
 *   <li>{@code LocalDateTime} ⇒ {@code {"date":{...},"time":{...}}}（对象，非 {@code string}）。</li>
 * </ul>
 * 前端 {@code format.date()} 拿到的是 {@code String({"year":...}) == "[object Object]"}，
 * 展示层直接失效；且「对象形态」不属 §4.1.1 冻结口径，跨端（含 C++ 侧读 Java 落库文本）不可判定。
 *
 * <p><b>为何在编解码层而非逐点改 handler</b>：响应唯一出口是 {@link HttpResult#json} →
 * {@link JsonCodec#toJson}（{@code web/ws} 消息帧同源），故单点注册即<b>全仓响应面收口</b>；
 * {@code dao/entity} 包实体面 {@code LocalDate} 67 字段 + {@code LocalDateTime} 103 字段（另加
 * {@code AuditedEntity} 继承的 {@code createdTime}/{@code updatedTime}），逐点改必漏且新端点会再犯。
 *
 * <p><b>本类与 {@code T235QuotaDateTimeWireTest} 的分工</b>：本类管「编解码器契约 + 实体包反射穷举」
 * （面）；后者管「真实 DAO + 真实分发链的一条 GET 实体直出 + 一条 POST 往返」（点，线上词法）。
 *
 * <p>归属：{@code T-235}（{@code #java2}）。
 */
class DateTimeWireContractTest {

    /** §4.1.1 纯日期形态。 */
    private static final Pattern DATE_WIRE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** §4.1.1 时间形态（ISO-8601，无时区；小数秒可选）。 */
    private static final Pattern DATE_TIME_WIRE =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$");

    /** 纯日期代表值。 */
    private static final LocalDate SAMPLE_DATE = LocalDate.of(2026, 11, 8);

    /** 时间代表值（微秒级小数秒，与 {@code TIMESTAMP(6)} 落库精度一致）。 */
    private static final LocalDateTime SAMPLE_DATE_TIME =
            LocalDateTime.of(2026, 11, 8, 4, 15, 25, 76_619_000);

    /** 实体包（日期 / 时间列的集中地）。 */
    private static final String ENTITY_PACKAGE = "com/hedgeguard/dao/entity";

    /** 扫描下限：防止「扫到 0 个字段也通过」（静默失效的护栏）。 */
    private static final int MIN_DATE_FIELDS = 60;

    /** 扫描下限（{@code LocalDateTime}；含 {@code AuditedEntity} 继承列）。 */
    private static final int MIN_DATE_TIME_FIELDS = 100;

    // ============================================================ 一、写出面：一律 JSON string

    @Test
    @DisplayName("T-235 验收 1：LocalDate 写出为纯日期字符串 yyyy-MM-dd（对象形态是缺陷）")
    void localDateWritesAsPlainDateString() {
        assertEquals("{\"v\":\"2026-11-08\"}", JsonCodec.toJson(new DateOnly(SAMPLE_DATE)),
                "LocalDate 必须以 JSON string yyyy-MM-dd 写出（§4.1.1「纯日期用 yyyy-MM-dd」）；"
                        + "若为 {\"year\":...} 对象形态即为 T-235 所述缺陷");
    }

    @Test
    @DisplayName("T-235 验收 1：LocalDateTime 写出为 ISO-8601 字符串（无时区、秒恒下发）")
    void localDateTimeWritesAsIsoString() {
        assertEquals("{\"v\":\"2026-11-08T04:15:25.076619\"}",
                JsonCodec.toJson(new DateTimeOnly(SAMPLE_DATE_TIME)),
                "LocalDateTime 必须以 JSON string ISO-8601 写出（§4.1.1），"
                        + "微秒级小数秒不得丢失，且不得为 {\"date\":...,\"time\":...} 对象形态");

        assertEquals("{\"v\":\"2026-11-08T04:15:00\"}",
                JsonCodec.toJson(new DateTimeOnly(LocalDateTime.of(2026, 11, 8, 4, 15))),
                "秒为 0 时仍须下发秒（yyyy-MM-ddTHH:mm:ss），否则形态不可判定"
                        + "（LocalDateTime.toString() 会省略秒，本适配器不得沿用该行为）");
    }

    @Test
    @DisplayName("T-235 验收 1：null 语义不变（null 字段仍整体省略，不变成字符串 \"null\"）")
    void nullSemanticsUnchanged() {
        assertEquals("{}", JsonCodec.toJson(new DateOnly(null)),
                "null LocalDate 字段须整体省略（serializeNulls 关闭，与 BigDecimal 适配器同语义）");
        assertEquals("{}", JsonCodec.toJson(new DateTimeOnly(null)),
                "null LocalDateTime 字段须整体省略");
    }

    @Test
    @DisplayName("T-235 验收 2：容器（Map／数组）装载的日期同样是字符串（类型驱动，非字段驱动）")
    void datesInContainersAreStrings() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tradeDate", SAMPLE_DATE);
        map.put("createdTime", SAMPLE_DATE_TIME);
        map.put("list", Collections.singletonList(SAMPLE_DATE));
        assertEquals("{\"tradeDate\":\"2026-11-08\",\"createdTime\":\"2026-11-08T04:15:25.076619\","
                        + "\"list\":[\"2026-11-08\"]}", JsonCodec.toJson(map),
                "Map/List 内的日期须同样字符串化（handler 以 Map<String,Object> 组装视图时受同一约束）");
    }

    @Test
    @DisplayName("T-235 验收 2：响应原文不得残留日期对象形态（词法层守卫）")
    void noLegacyObjectShapeInOutput() {
        String raw = JsonCodec.toJson(new Both(SAMPLE_DATE, SAMPLE_DATE_TIME));
        assertTrue(!raw.contains("\"year\":"), "响应不得含 {\"year\":...} 形态：" + raw);
        assertTrue(!raw.contains("\"month\":"), "响应不得含 \"month\" 字段（Gson 反射 java.time 私有字段的痕迹）：" + raw);
        assertTrue(!raw.contains("\"date\":{"), "响应不得含 {\"date\":{...},\"time\":{...}} 形态：" + raw);
        assertTrue(!raw.contains("\"nano\":"), "响应不得含 \"nano\" 字段：" + raw);
    }

    // ============================================================ 二、读入面：不收紧（string 为主，旧对象形态兼容）

    @Test
    @DisplayName("T-235 验收 1：读入兼容 string（请求面不得比改动前更严）")
    void readsFromString() {
        assertEquals(SAMPLE_DATE, JsonCodec.fromJson("{\"v\":\"2026-11-08\"}", DateOnly.class).v,
                "纯日期字符串须可反序列化（契约正解）");
        assertEquals(SAMPLE_DATE_TIME,
                JsonCodec.fromJson("{\"v\":\"2026-11-08T04:15:25.076619\"}", DateTimeOnly.class).v,
                "ISO-8601 字符串须可反序列化（小数秒保留）");
        assertEquals(LocalDateTime.of(2026, 11, 8, 4, 15, 25),
                JsonCodec.fromJson("{\"v\":\"2026-11-08T04:15:25\"}", DateTimeOnly.class).v,
                "无小数秒的 ISO-8601 亦须可解析");
        assertEquals(LocalDateTime.of(2026, 11, 8, 4, 15, 0),
                JsonCodec.fromJson("{\"v\":\"2026-11-08T04:15\"}", DateTimeOnly.class).v,
                "秒省略形态（<input type=\"datetime-local\"> 原生值）不得被拒，避免收紧既有请求面");
        assertEquals(LocalDateTime.of(2026, 11, 8, 4, 15, 0),
                JsonCodec.fromJson("{\"v\":\"2026-11-08 04:15:00\"}", DateTimeOnly.class).v,
                "空格分隔形态（前端展示串回填）亦须兼容");
        assertEquals(SAMPLE_DATE,
                JsonCodec.fromJson("{\"v\":\"2026-11-08T04:15:25\"}", DateOnly.class).v,
                "纯日期字段收到时间串时取日期部分（宽入；不收紧）");
    }

    @Test
    @DisplayName("T-235 验收 1：旧对象形态（Gson 反射产物）仍可读入（存量兼容，不因收口而拒收）")
    void readsLegacyObjectShape() {
        assertEquals(SAMPLE_DATE,
                JsonCodec.fromJson("{\"v\":{\"year\":2026,\"month\":11,\"day\":8}}", DateOnly.class).v,
                "改前 JSON 中的纯日期形态（{\"year\",\"month\",\"day\"}）须继续可读");
        assertEquals(SAMPLE_DATE_TIME,
                JsonCodec.fromJson("{\"v\":{\"date\":{\"year\":2026,\"month\":11,\"day\":8},"
                        + "\"time\":{\"hour\":4,\"minute\":15,\"second\":25,\"nano\":76619000}}}",
                        DateTimeOnly.class).v,
                "改前 JSON 中的时间形态（{\"date\":{...},\"time\":{...}}）须继续可读");
    }

    @Test
    @DisplayName("T-235 验收 1：null / 空串读入为 null（不抛）；非法值仍抛 10001")
    void readNullAndInvalid() {
        assertEquals(null, JsonCodec.fromJson("{\"v\":null}", DateOnly.class).v,
                "显式 JSON null 须读为 null");
        assertEquals(null, JsonCodec.fromJson("{\"v\":\"\"}", DateTimeOnly.class).v,
                "空串按缺省处理（前端未填的可选日期），不得抛错——宽入");
        BizException error = assertThrows(BizException.class,
                () -> JsonCodec.fromJson("{\"v\":\"2026/11/08\"}", DateOnly.class),
                "非法日期文本须以 10001 拒绝，不得静默吞掉");
        assertEquals(10001, error.errorCode().code(),
                "载荷非法统一 10001（§4.3.1）：" + error.getMessage());
        assertThrows(BizException.class,
                () -> JsonCodec.fromJson("{\"v\":true}", DateTimeOnly.class),
                "布尔 / 数字等非字符串非对象 token 须拒绝（改前同样拒绝，不得放宽到猜测语义）");
    }

    // ============================================================ 三、实体包反射穷举（响应面）

    @Test
    @DisplayName("T-235 验收 2：全仓实体日期 / 时间字段一律以 ISO 字符串输出（穷举，非抽样）")
    void everyEntityDateFieldIsSerializedAsIsoString() throws Exception {
        List<Class<?>> types = concreteEntityClasses();
        assertTrue(types.size() >= 50,
                "实体类扫描数量异常（应 ≥ 50，实际 " + types.size() + "）——扫描根失效会让本守卫静默通过");

        List<String> violations = new ArrayList<>();
        int dateFields = 0;
        int dateTimeFields = 0;
        for (Class<?> type : types) {
            Object instance = instantiate(type);
            List<Field> dateCols = fieldsOfType(type, LocalDate.class);
            List<Field> dateTimeCols = fieldsOfType(type, LocalDateTime.class);
            for (Field field : dateCols) {
                field.setAccessible(true);
                field.set(instance, SAMPLE_DATE);
            }
            for (Field field : dateTimeCols) {
                field.setAccessible(true);
                field.set(instance, SAMPLE_DATE_TIME);
            }
            if (dateCols.isEmpty() && dateTimeCols.isEmpty()) {
                continue;
            }
            JsonObject json = JsonParser.parseString(JsonCodec.toJson(instance)).getAsJsonObject();
            for (Field field : dateCols) {
                dateFields++;
                violations.addAll(check(json, type, field, DATE_WIRE, "2026-11-08"));
            }
            for (Field field : dateTimeCols) {
                dateTimeFields++;
                violations.addAll(check(json, type, field, DATE_TIME_WIRE, "2026-11-08T04:15:25.076619"));
            }
        }
        assertTrue(dateFields >= MIN_DATE_FIELDS,
                "未扫到足够的 LocalDate 字段（应 ≥ " + MIN_DATE_FIELDS + "，实际 " + dateFields
                        + "；扫描根或字段可见性失效）");
        assertTrue(dateTimeFields >= MIN_DATE_TIME_FIELDS,
                "未扫到足够的 LocalDateTime 字段（应 ≥ " + MIN_DATE_TIME_FIELDS + "，实际 " + dateTimeFields + "）");
        assertTrue(violations.isEmpty(),
                "以下响应点仍以对象形态输出日期 / 时间（违反 ARCHITECTURE §4.1.1「时间」约定）：\n  "
                        + String.join("\n  ", violations));
    }

    // ============================================================ 工具

    private static List<String> check(JsonObject json, Class<?> type, Field field, Pattern wire,
                                      String expected) {
        JsonElement element = json.get(field.getName());
        String where = type.getSimpleName() + "." + field.getName();
        List<String> out = new ArrayList<>();
        if (element == null) {
            out.add(where + " 未出现在序列化结果中");
            return out;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            out.add(where + " => " + element + "（期望 string \"" + expected + "\"）");
            return out;
        }
        String text = element.getAsString();
        if (!wire.matcher(text).matches()) {
            out.add(where + " => \"" + text + "\"（不符合 §4.1.1 形态）");
        } else if (!expected.equals(text)) {
            out.add(where + " => \"" + text + "\"（期望 \"" + expected + "\"）");
        }
        return out;
    }

    /** 反射取 {@code com.hedgeguard.dao.entity} 下的具体类（跳过抽象基类与内部类）。 */
    private static List<Class<?>> concreteEntityClasses() throws Exception {
        URL url = DateTimeWireContractTest.class.getClassLoader().getResource(ENTITY_PACKAGE);
        assertNotNull(url, "找不到实体包扫描根：" + ENTITY_PACKAGE + "（复现=在 java/ 下执行 mvn test）");
        assertEquals("file", url.getProtocol(), "实体包须为目录（非 jar）：" + url);
        List<Class<?>> out = new ArrayList<>();
        for (Object item : listPackageClasses(url)) {
            Class<?> type = (Class<?>) item;
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers()) || type.isEnum()) {
                continue;
            }
            out.add(type);
        }
        return out;
    }

    private static List<Object> listPackageClasses(URL url) throws Exception {
        java.nio.file.Path dir = java.nio.file.Paths.get(url.toURI());
        List<Object> out = new ArrayList<>();
        List<java.nio.file.Path> files;
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
            files = stream.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
        }
        java.util.Collections.sort(files);
        for (java.nio.file.Path file : files) {
            String name = file.getFileName().toString();
            name = name.substring(0, name.length() - ".class".length());
            if (name.indexOf('$') >= 0) {
                continue;
            }
            out.add(Class.forName(ENTITY_PACKAGE.replace('/', '.') + "." + name));
        }
        return out;
    }

    private static Object instantiate(Class<?> type) throws Exception {
        java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    /** 该类（含继承链）的 public 指定类型实例字段。 */
    private static List<Field> fieldsOfType(Class<?> type, Class<?> fieldType) {
        return Arrays.stream(type.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> f.getType() == fieldType)
                .collect(Collectors.toList());
    }

    /** 序列化载体（{@code public} 字段与实体同约定）。 */
    public static final class DateOnly {

        /** 待测值。 */
        public LocalDate v;

        DateOnly(LocalDate value) {
            this.v = value;
        }
    }

    /** 序列化载体（时间）。 */
    public static final class DateTimeOnly {

        /** 待测值。 */
        public LocalDateTime v;

        DateTimeOnly(LocalDateTime value) {
            this.v = value;
        }
    }

    /** 序列化载体（日期 + 时间同体）。 */
    public static final class Both {

        /** 纯日期。 */
        public LocalDate day;

        /** 时间。 */
        public LocalDateTime at;

        /** 金额（交叉校验：日期适配器不得干扰既有的 BigDecimal 口径）。 */
        public BigDecimal amount;

        Both(LocalDate day, LocalDateTime at) {
            this.day = day;
            this.at = at;
            this.amount = new BigDecimal("1234.5600");
        }
    }
}
