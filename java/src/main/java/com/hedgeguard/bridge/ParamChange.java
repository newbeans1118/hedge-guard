package com.hedgeguard.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 参数下发请求（{@code ARCHITECTURE.md} §4.2.3 控制指令族 / §3.8 {@code ParamDispatchService}）。
 *
 * <p>统一封装"改 Java 侧 → 下发 C++ → 等回执 → 留痕"，供
 * {@code Variety / Plan / Risk / Auth / Config} 五个模块复用（避免各处各写一套）。
 *
 * <p>{@code payload} 用字符串键值承载，<b>不</b>在此处固化协议字段名——
 * 字段级定义以 {@code §4.2}（M2 门禁冻结对象）为准；{@code paramVersion} 单调递增，
 * C++ 侧对陈旧版本回 {@code 0x0403}。
 */
public final class ParamChange {

    /** 指令类型（C++ 侧控制指令族）。 */
    public enum Type {

        /** 品种白名单变更（层①前置）。 */
        CMD_UPDATE_WHITELIST,

        /** 额度变更（<b>三层同时</b>，{@code CFG-16}）。 */
        CMD_UPDATE_QUOTA,

        /** 风控阈值变更。 */
        CMD_UPDATE_RISK_THRESHOLD,

        /** 敞口变更（<b>三分量</b>，{@code CFG-17}）。 */
        CMD_UPDATE_EXPOSURE,

        /** 持仓上限变更。 */
        CMD_UPDATE_POSITION_LIMIT,

        /** 权限快照变更（授权 / 岗位）。 */
        CMD_UPDATE_PERMISSION,

        /** 限制新开仓（{@code scope=OPEN_ONLY}，平仓放行，{@code R-RISK-5}）。 */
        CMD_SET_TRADING_SUSPEND,

        /** 就绪状态切换。 */
        CMD_SET_READY_STATE,

        /** 全量风控参数预加载（分批）。 */
        CMD_LOAD_RISK_PARAMS,

        /** 触发启动重放。 */
        CMD_TRIGGER_REPLAY,

        /** 重载配置。 */
        CMD_RELOAD
    }

    /** 指令类型。 */
    public Type type;

    /** 参数版本（单调递增，应用层生成）。 */
    public long paramVersion;

    /** 操作人用户 ID（留痕用）。 */
    public long operatorId;

    /** 载荷字段（字符串键值）。 */
    public Map<String, String> payload = new LinkedHashMap<>();

    /** 无参构造（供 Gson）。 */
    public ParamChange() {
    }

    /**
     * 构造。
     *
     * @param type        类型
     * @param paramVersion 版本
     * @param operatorId  操作人
     */
    public ParamChange(Type type, long paramVersion, long operatorId) {
        this.type = type;
        this.paramVersion = paramVersion;
        this.operatorId = operatorId;
    }

    /**
     * 追加载荷项。
     *
     * @param key   键
     * @param value 值
     * @return 自身
     */
    public ParamChange with(String key, String value) {
        payload.put(key, value);
        return this;
    }

    /** 载荷的只读视图。 */
    public Map<String, String> payloadView() {
        return Collections.unmodifiableMap(payload);
    }
}
