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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-132} 验收 2/3：<b>全仓</b>「{@code BigDecimal} 以 JSON {@code number} 输出」的<b>可执行穷举</b>。
 *
 * <p><b>依据</b>：{@code ARCHITECTURE.md} §4.1.1「金额 / 数量 = 字符串化十进制（避免 JS {@code Number}
 * 精度丢失），如 {@code "1234.5600"}」+ §4.2.8.9 {@code G-2}/{@code G-3}（额度与比率字段一律 {@code string}）。
 *
 * <p><b>为何用反射穷举而不是逐点手改</b>：响应点<b>不止</b> {@code /product/quota}——{@code dao/entity}
 * 包下 57 个实体带 {@code BigDecimal} 列，凡「实体直出」的 Handler 都有同一根因（Gson 默认
 * {@code BIG_DECIMAL} 适配器输出 JSON number）。逐点改会漏，且新端点会再犯。故本用例
 * <b>反射枚举 {@code com.hedgeguard.dao.entity} 包的每一个具体实体类</b>，对其<b>每一个</b>
 * {@code BigDecimal} 字段做「序列化后词法类型 == string」断言：任何实体新增金额/数量列若未被
 * {@link JsonCodec} 的适配器覆盖，本用例<b>自动变红</b>（无需人工维护字段清单）。
 *
 * <p><b>与静态清单的关系</b>：静态清单（{@code grep}，见 {@code T-132} 交付说明）负责「点」的枚举与
 * 「不适用项」的理由；本类负责「面」的自动守卫。两者互为交叉校验：清单若有遗漏，本类会红。
 */
class BigDecimalWireContractTest {

    /** 代表值：{@code DECIMAL(20,4)} 全 4 位小数（尾零必须保留）。 */
    private static final String DECIMAL_TEXT = "1234.5600";

    /** 实体包（{@code BigDecimal} 业务列的集中地）。 */
    private static final String ENTITY_PACKAGE = "com/hedgeguard/dao/entity";

    /** 扫描下限：防止「扫到 0 个类也通过」（静默失效的护栏）。 */
    private static final int MIN_ENTITY_CLASSES = 50;

    // ============================================================ 一、编解码器层

    @Test
    @DisplayName("T-132 验收 3：BigDecimal 序列化为字符串化十进制（尾零不丢、无科学计数法、无引号丢失）")
    void bigDecimalSerializesAsString() {
        assertEquals("{\"v\":\"" + DECIMAL_TEXT + "\"}", JsonCodec.toJson(new Holder(DECIMAL_TEXT)),
                "BigDecimal 必须序列化为 JSON string（§4.1.1 金额/数量）");

        Holder tiny = new Holder("0.00000001");
        assertTrue(JsonCodec.toJson(tiny).contains("\"0.00000001\""),
                "极小值不得退化为科学计数法（toPlainString 口径）：" + JsonCodec.toJson(tiny));

        Holder huge = new Holder("12345678901234567890.1234");
        assertTrue(JsonCodec.toJson(huge).contains("\"12345678901234567890.1234\""),
                "超过 2^53 的金额不得丢精度（这正是禁止 JSON number 的原因）：" + JsonCodec.toJson(huge));

        assertEquals("{}", JsonCodec.toJson(new Holder(null)),
                "null 语义不变（适配器不得把 null 变成字符串 \"null\"；null 字段仍整体省略）");
    }

    @Test
    @DisplayName("T-132 验收 2：容器（Map／数组）装载的 BigDecimal 同样以字符串输出（类型驱动，非字段驱动）")
    void bigDecimalInContainersSerializesAsString() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("amount", new BigDecimal(DECIMAL_TEXT));
        map.put("count", 3);
        map.put("nested", Collections.singletonList(new BigDecimal("0.0000")));
        assertEquals("{\"amount\":\"" + DECIMAL_TEXT + "\",\"count\":3,\"nested\":[\"0.0000\"]}",
                JsonCodec.toJson(map),
                "Map/List 内的 BigDecimal 亦须字符串化（handler 以 Map<String,Object> 组装视图时同样受约束）；"
                        + "计数类整数（count）仍为 number");
    }

    @Test
    @DisplayName("T-132 验收 3：请求侧兼容——字符串与 JSON number 两种形态均可反序列化（不收紧既有入参）")
    void bigDecimalDeserializesFromBothForms() {
        assertEquals(0, new BigDecimal(DECIMAL_TEXT)
                .compareTo(JsonCodec.fromJson("{\"v\":\"" + DECIMAL_TEXT + "\"}", Holder.class).v),
                "字符串形态（契约正解）须可解析");
        assertEquals(0, new BigDecimal(DECIMAL_TEXT)
                .compareTo(JsonCodec.fromJson("{\"v\":" + DECIMAL_TEXT + "}", Holder.class).v),
                "number 形态须继续兼容（避免本次改动收紧既有请求面）");
    }

    // ============================================================ 二、实体包穷举

    @Test
    @DisplayName("T-132 验收 2/3：全仓实体 BigDecimal 字段一律以 JSON string 输出（穷举，非抽样）")
    void everyEntityBigDecimalFieldIsSerializedAsString() throws Exception {
        List<Class<?>> types = concreteEntityClasses();
        assertTrue(types.size() >= MIN_ENTITY_CLASSES,
                "实体类扫描数量异常（应 ≥ " + MIN_ENTITY_CLASSES + "，实际 " + types.size()
                        + "）——扫描根失效会让本守卫静默通过");

        List<String> violations = new ArrayList<>();
        int decimalFields = 0;
        for (Class<?> type : types) {
            Object instance = instantiate(type);
            List<Field> fields = decimalFields(type);
            if (fields.isEmpty()) {
                continue;
            }
            for (Field field : fields) {
                field.setAccessible(true);
                field.set(instance, new BigDecimal(DECIMAL_TEXT));
            }
            JsonObject json = JsonParser.parseString(JsonCodec.toJson(instance)).getAsJsonObject();
            for (Field field : fields) {
                decimalFields++;
                JsonElement element = json.get(field.getName());
                if (element == null) {
                    violations.add(type.getSimpleName() + "." + field.getName() + " 未出现在序列化结果中");
                } else if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                        || !DECIMAL_TEXT.equals(element.getAsString())) {
                    violations.add(type.getSimpleName() + "." + field.getName() + " => " + element
                            + "（期望字符串化十进制 \"" + DECIMAL_TEXT + "\"）");
                }
            }
        }
        assertTrue(decimalFields > 0, "未扫到任何 BigDecimal 字段（扫描根失效）");
        assertTrue(violations.isEmpty(), "以下响应点仍以 JSON number 输出金额/数量（违反 §4.1.1 / G-2）：\n  "
                + String.join("\n  ", violations));
    }

    // ============================================================ 工具

    /** 反射取 {@code com.hedgeguard.dao.entity} 下的具体类（跳过抽象基类与内部类）。 */
    private static List<Class<?>> concreteEntityClasses() throws Exception {
        URL url = BigDecimalWireContractTest.class.getClassLoader().getResource(ENTITY_PACKAGE);
        assertNotNull(url, "找不到实体包扫描根：" + ENTITY_PACKAGE + "（复现=在 java/ 下执行 mvn test）");
        assertEquals("file", url.getProtocol(), "实体包须为目录（非 jar）：" + url);
        Path dir = Paths.get(url.toURI());
        List<Path> classFiles;
        try (Stream<Path> stream = Files.list(dir)) {
            classFiles = stream.filter(p -> p.getFileName().toString().endsWith(".class"))
                    .collect(Collectors.toList());
        }
        List<Class<?>> out = new ArrayList<>();
        for (Path p : classFiles) {
            String simple = p.getFileName().toString().replace(".class", "");
            if (simple.contains("$")) {
                continue;
            }
            Class<?> type = Class.forName(ENTITY_PACKAGE.replace('/', '.') + "." + simple);
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers()) || type.isEnum()) {
                continue;
            }
            out.add(type);
        }
        return out;
    }

    private static Object instantiate(Class<?> type) throws Exception {
        java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    /** 该类（含继承链）的 public {@code BigDecimal} 实例字段。 */
    private static List<Field> decimalFields(Class<?> type) {
        return Arrays.stream(type.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .filter(f -> f.getType() == BigDecimal.class)
                .collect(Collectors.toList());
    }

    /** 序列化载体（{@code public} 字段与实体同约定）。 */
    public static final class Holder {

        /** 待测值。 */
        public BigDecimal v;

        /**
         * 构造。
         *
         * @param text 十进制文本（{@code null} 表示空值）
         */
        Holder(String text) {
            this.v = text == null ? null : new BigDecimal(text);
        }
    }
}
