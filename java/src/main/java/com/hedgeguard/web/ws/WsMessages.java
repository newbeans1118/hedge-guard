package com.hedgeguard.web.ws;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.web.JsonCodec;

import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前端 WebSocket 消息的**线格式唯一构造点**（`ARCHITECTURE.md` §4.1.2）。
 *
 * <p>字段与语义逐条对齐 §4.1.2 与 `CONTRACTS.md` §4.1：
 * <ul>
 *   <li>{@code /ws/quote}：{@code {"type":"quote","instrument","last","bid","ask","ts"}}；
 *       **数值一律字符串化十进制**（与 REST 同口径，禁止 JSON number）</li>
 *   <li>{@code /ws/alert}：{@code {"type":"alert","level","metric","value","threshold","subAccount","ts"}}</li>
 *   <li>订阅应答：{@code {"type":"subscribed","channels":[...],"subAccounts":[...],"ts","tsMillis"}}</li>
 *   <li>{@code ts} = ISO-8601 本地时区（`Asia/Shanghai`）；另附 {@code tsMillis} 供前端算延迟（`W-6`）</li>
 * </ul>
 *
 * <p>集中构造的意义：**前端与后端只认一处格式**，避免各推送点各写一套导致契约漂移。
 */
public final class WsMessages {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private WsMessages() {
    }

    /**
     * 行情帧。
     *
     * @param instrument 合约
     * @param last       最新价（字符串化十进制）
     * @param bid        买一价
     * @param ask        卖一价
     * @return JSON
     */
    public static String quote(String instrument, String last, String bid, String ask) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "quote");
        m.put("instrument", instrument);
        m.put("last", last);
        m.put("bid", bid);
        m.put("ask", ask);
        addTime(m);
        return JsonCodec.toJson(m);
    }

    /**
     * 行情过期/延迟标注帧（`ARCHITECTURE.md` §6.13：前端须能识别"行情延迟/过期"）。
     *
     * @param instrument 合约
     * @param lastSeenAt 最近一次数据的 ISO-8601 时间（可能为 {@code null}）
     * @return JSON
     */
    public static String quoteStale(String instrument, String lastSeenAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "quote");
        m.put("instrument", instrument);
        m.put("stale", Boolean.TRUE);
        m.put("lastSeenAt", lastSeenAt);
        addTime(m);
        return JsonCodec.toJson(m);
    }

    /**
     * 告警帧。
     *
     * @param level        提示 / 警告 / 严重
     * @param metric       指标名
     * @param value        当前值（字符串化十进制）
     * @param threshold    阈值（字符串化十进制）
     * @param subAccountId 子账户（**已过 `W-2` 授权过滤**；账户级为 0）
     * @return JSON
     */
    public static String alert(String level, String metric, String value, String threshold,
                               int subAccountId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "alert");
        m.put("level", level);
        m.put("metric", metric);
        m.put("value", value);
        m.put("threshold", threshold);
        m.put("subAccount", subAccountId);
        addTime(m);
        return JsonCodec.toJson(m);
    }

    /**
     * 订阅应答（告知客户端**实际生效**的通道与子账户）。
     *
     * @param channels    生效通道
     * @param subAccounts 生效子账户（**已授权交集**）
     * @return JSON
     */
    public static String subscribed(Collection<String> channels, Collection<Integer> subAccounts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "subscribed");
        m.put("channels", new java.util.ArrayList<>(channels));
        m.put("subAccounts", new java.util.ArrayList<>(subAccounts));
        addTime(m);
        return JsonCodec.toJson(m);
    }

    /**
     * 错误帧（升级后）。
     *
     * @param code    业务码（如 10004）
     * @param message 提示
     * @return JSON
     */
    public static String error(int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "error");
        m.put("code", code);
        m.put("message", message);
        addTime(m);
        return JsonCodec.toJson(m);
    }

    private static void addTime(Map<String, Object> m) {
        java.time.LocalDateTime now = Clocks.now();
        m.put("ts", now.format(TS));
        m.put("tsMillis", java.sql.Timestamp.valueOf(now).getTime());
    }
}
