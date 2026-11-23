package com.hedgeguard.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code CMD_QUERY_STATUS} 回执 {@code MSG_ACK.detail} 的宽松解析器。
 *
 * <p><b>双形态</b>（阶段 2）：
 * <ul>
 *   <li><b>阶段 1 key=value</b>：{@code ready=true;param_version=7;load=1/1}
 *       —— C++ 未注入状态源时的降级路径、以及旧客户端，逐字节兼容；</li>
 *   <li><b>阶段 2 JSON</b>：C++ 侧按 {@code what} 位掩码组装的结构化状态
 *       （仍装进既有 {@code MsgAck.detail} string 字段，<b>不改 proto</b>）。</li>
 * </ul>
 *
 * <p><b>宽松语义</b>：未知键忽略、单字段类型错误只丢该字段、整体不抛异常。
 * 解析不出时置 {@link Parsed#unparsed} 并保留 {@link Parsed#raw}，
 * 由 {@code ComponentMonitor} 保留事件侧状态，不臆造、不猜值。
 *
 * <p>本类不做任何网络/DB 访问，纯字符串处理（gson 2.10.1 已在 compile 白名单）。
 */
public final class StatusDetailParser {

    private StatusDetailParser() {
    }

    /** 解析结果；未提供字段用 {@code null} 区分「缺失」与「0/false」。 */
    public static final class Parsed {
        /** 状态机细名（AppState，可区分 BLOCKED/FAILED）。 */
        public String state;
        /** 协议映射态（ServerState，BLOCKED/FAILED 归 NOT_READY）。 */
        public String serverState;
        /** 就绪门卫。 */
        public Boolean ready;
        /** 已生效参数版本。 */
        public Long paramVersion;
        /** 参数加载批次（received/total）。 */
        public Integer loadReceived;
        public Integer loadTotal;
        /** 重放：执行次数 / 会话起点 / 状态名。 */
        public Long replayRuns;
        public Long replayStartedMs;
        public String replayState;
        /** 队列水位。 */
        public Long eventQueueDepth;
        public Long eventQueueCapacity;
        public Long backpressure;
        /** 运行期计数（键 = C++ 侧 camelCase 计数器名）。 */
        public Map<String, Long> counters;
        /** CTP 前置/连接态。 */
        public Boolean ctpTradeConnected;
        public Boolean ctpMdConnected;
        public Boolean ctpLoggedIn;
        public Boolean ctpDegraded;
        public String ctpFront;
        /** 组件运行时长与 C++ 侧时间戳、what 回显。 */
        public Long uptimeMs;
        public Long tsMs;
        public Integer what;
        /** 原始 detail 串（诊断留痕）。 */
        public String raw;
        /** true = 无法提取字段（格式未知/JSON 解析失败），调用方须保留事件侧值。 */
        public boolean unparsed;
    }

    /**
     * 解析 detail。
     *
     * @param detail {@code MSG_ACK.detail}（可为 {@code null}）
     * @return 永不为 {@code null} 的解析结果
     */
    public static Parsed parse(String detail) {
        Parsed p = new Parsed();
        p.raw = detail == null ? "" : detail;
        if (detail == null || detail.isEmpty()) {
            return p;
        }
        if (detail.charAt(0) == '{') {
            parseJson(p, detail);
        } else {
            parseKeyValue(p, detail);
        }
        return p;
    }

    // ---- 阶段 1：key=value 降级摘要 ----------------------------------------

    private static void parseKeyValue(Parsed p, String detail) {
        for (String part : detail.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq == part.length() - 1) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            switch (key) {
                case "ready":
                    p.ready = Boolean.valueOf(value);
                    break;
                case "param_version":
                    p.paramVersion = parseLong(value);
                    break;
                case "load": {
                    int slash = value.indexOf('/');
                    if (slash > 0) {
                        p.loadReceived = parseInt(value.substring(0, slash));
                        p.loadTotal = parseInt(value.substring(slash + 1));
                    }
                    break;
                }
                default:
                    // 未知键：阶段 2 追加字段时保持向前兼容
                    break;
            }
        }
    }

    // ---- 阶段 2：结构化 JSON --------------------------------------------------

    private static void parseJson(Parsed p, String detail) {
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(detail);
            if (el == null || !el.isJsonObject()) {
                p.unparsed = true;
                return;
            }
            root = el.getAsJsonObject();
        } catch (RuntimeException e) {
            // 非预期 JSON / 版本演进中的新形态：如实标记未解析，不抛、不猜值
            p.unparsed = true;
            return;
        }
        p.state = str(root, "state");
        p.serverState = str(root, "serverState");
        p.ready = bool(root, "ready");
        p.paramVersion = lng(root, "paramVersion");
        p.uptimeMs = lng(root, "uptimeMs");
        p.tsMs = lng(root, "tsMs");
        p.what = integer(root, "what");

        JsonObject replay = obj(root, "replay");
        if (replay != null) {
            p.replayRuns = lng(replay, "runs");
            p.replayStartedMs = lng(replay, "startedMs");
            p.replayState = str(replay, "state");
        }
        JsonObject load = obj(root, "load");
        if (load != null) {
            p.loadReceived = integer(load, "received");
            p.loadTotal = integer(load, "total");
        }
        JsonObject queues = obj(root, "queues");
        if (queues != null) {
            p.eventQueueDepth = lng(queues, "event");
            p.eventQueueCapacity = lng(queues, "eventCapacity");
            p.backpressure = lng(queues, "backpressure");
        }
        JsonObject counters = obj(root, "counters");
        if (counters != null) {
            Map<String, Long> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : counters.entrySet()) {
                Long v = asLong(e.getValue());
                if (v != null) {
                    m.put(e.getKey(), v);
                }
            }
            p.counters = m;
        }
        JsonObject ctp = obj(root, "ctp");
        if (ctp != null) {
            p.ctpTradeConnected = bool(ctp, "tradeConnected");
            p.ctpMdConnected = bool(ctp, "mdConnected");
            p.ctpLoggedIn = bool(ctp, "loggedIn");
            p.ctpDegraded = bool(ctp, "degraded");
            p.ctpFront = str(ctp, "front");
        }
    }

    // ---- 宽容取值助手（缺键 / 类型错一律返回 null，不抛） ------------------------

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return e.getAsString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return Boolean.valueOf(e.getAsBoolean());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Long lng(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null ? null : asLong(e);
    }

    private static Integer integer(JsonObject o, String key) {
        Long v = lng(o, key);
        return v == null ? null : Integer.valueOf(v.intValue());
    }

    private static Long asLong(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return Long.valueOf(e.getAsLong());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Long parseLong(String v) {
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String v) {
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
