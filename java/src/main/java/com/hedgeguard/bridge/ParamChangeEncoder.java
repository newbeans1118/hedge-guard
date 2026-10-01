package com.hedgeguard.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import hgcp.v1.Hgcp;

/**
 * {@link ParamChange} → HGCP 控制指令 payload 的编码器（{@code ARCHITECTURE.md} §3.8 / §4.2.3）。
 *
 * <p>{@code ParamChange.payload} 是 {@code Map<String,String>}
 * （{@code java/CONTRACTS.md} §3.2.1：<b>键名一律直接采用 §4.2.3 冻结的协议字段名</b>），
 * 本类负责把它映射为对应 {@code Cmd*} 消息。
 *
 * <p><b>硬规则（{@code HGC-1.6}）</b>：payload 只允许出现 §4.2.3 协议表内的字段名。
 * 本类对<b>未知键一律抛 {@link IllegalArgumentException}</b> —— 静默丢弃更危险
 * （调用方以为"带上了"，实际没上线路）。
 *
 * <p><b>{@code entries[n].} 串行化约定</b>（§3.2.1）：因 payload 为扁平字符串映射，
 * 重复条目用 {@code entries[0].}、{@code entries[1].}… 前缀索引。
 */
public final class ParamChangeEncoder {

    private ParamChangeEncoder() {
    }

    /**
     * 编码。
     *
     * @param change 参数变更
     * @return protobuf 序列化字节
     * @throws IllegalArgumentException payload 含协议外键名，或类型不支持
     */
    public static byte[] encode(ParamChange change) {
        switch (change.type) {
            case CMD_UPDATE_WHITELIST:
                return encodeWhitelist(change);
            case CMD_UPDATE_QUOTA:
                return encodeQuota(change);
            case CMD_UPDATE_POSITION_LIMIT:
                return encodePositionLimit(change);
            case CMD_SET_TRADING_SUSPEND:
                return encodeTradingSuspend(change);
            case CMD_SET_READY_STATE:
                return encodeReadyState(change);
            case CMD_RELOAD:
                return encodeOperatorReason(Hgcp.CmdReload.newBuilder(), change).build().toByteArray();
            case CMD_TRIGGER_REPLAY:
                return encodeOperatorReason(Hgcp.CmdTriggerReplay.newBuilder(), change)
                        .build().toByteArray();
            default:
                throw new IllegalArgumentException(
                        "本里程碑未实现的参数变更类型（payload 映射未定义）：" + change.type);
        }
    }

    // ==================== 各指令 ====================

    private static byte[] encodeWhitelist(ParamChange c) {
        Hgcp.CmdUpdateWhitelist.Builder b = Hgcp.CmdUpdateWhitelist.newBuilder()
                .setParamVersion(c.paramVersion)
                .setSubAccountId(uint(require(c, "sub_account_id")));
        for (String v : all(c, "add_product_ids")) {
            b.addAddProductIds(uint(v));
        }
        for (String v : all(c, "remove_product_ids")) {
            b.addRemoveProductIds(uint(v));
        }
        rejectUnknown(c, "sub_account_id", "add_product_ids", "remove_product_ids");
        return b.build().toByteArray();
    }

    private static byte[] encodeQuota(ParamChange c) {
        Hgcp.CmdUpdateQuota.Builder b = Hgcp.CmdUpdateQuota.newBuilder()
                .setParamVersion(c.paramVersion);
        for (Map<String, String> e : entries(c)) {
            Hgcp.QuotaEntry.Builder q = Hgcp.QuotaEntry.newBuilder()
                    .setLimitLayer(mapLimitLayer(e.get("limit_layer")))
                    .setSubAccountId(uint(e.getOrDefault("sub_account_id", "0")))
                    .setProductId(uint(e.getOrDefault("product_id", "0")))
                    .setQuotaLimit(e.getOrDefault("quota_limit", "0"))
                    .setUsed(e.getOrDefault("used", "0"))
                    .setFrozen(e.getOrDefault("frozen", "0"));
            String inst = e.get("instrument_id");
            if (inst != null && !inst.isEmpty()) {
                q.setInstrumentId(inst);
            }
            b.addEntries(q);
        }
        rejectUnknownEntryKeys(c, "limit_layer", "sub_account_id", "product_id",
                "instrument_id", "quota_limit", "used", "frozen");
        return b.build().toByteArray();
    }

    private static byte[] encodePositionLimit(ParamChange c) {
        Hgcp.CmdUpdatePositionLimit.Builder b = Hgcp.CmdUpdatePositionLimit.newBuilder()
                .setParamVersion(c.paramVersion);
        for (Map<String, String> e : entries(c)) {
            b.addEntries(Hgcp.PositionLimitEntry.newBuilder()
                    .setSubAccountId(uint(e.getOrDefault("sub_account_id", "0")))
                    .setProductId(uint(e.getOrDefault("product_id", "0")))
                    .setInstrumentId(e.getOrDefault("instrument_id", ""))
                    .setLimit(e.getOrDefault("limit", "0")));
        }
        rejectUnknownEntryKeys(c, "sub_account_id", "product_id", "instrument_id", "limit");
        return b.build().toByteArray();
    }

    private static byte[] encodeTradingSuspend(ParamChange c) {
        Hgcp.CmdSetTradingSuspend.Builder b = Hgcp.CmdSetTradingSuspend.newBuilder()
                .setParamVersion(c.paramVersion)
                .setSubAccountId(uint(require(c, "sub_account_id")))
                .setScanScope(mapScanScope(require(c, "scan_scope")))
                .setSuspended("true".equalsIgnoreCase(c.payload.getOrDefault("suspended", "false")))
                .setOperatorId(longOf(c.payload.getOrDefault("operator_id", "0")));
        String reason = c.payload.get("reason");
        if (reason != null) {
            b.setReason(reason);
        }
        rejectUnknown(c, "sub_account_id", "scan_scope", "suspended", "reason", "operator_id");
        return b.build().toByteArray();
    }

    private static byte[] encodeReadyState(ParamChange c) {
        String target = require(c, "target_state");
        Hgcp.CmdSetReadyState.Builder b = Hgcp.CmdSetReadyState.newBuilder()
                .setTargetState(mapTargetState(target));
        String reason = c.payload.get("reason");
        if (reason != null) {
            b.setReason(reason);
        }
        rejectUnknown(c, "target_state", "reason");
        return b.build().toByteArray();
    }

    private static <T extends com.google.protobuf.MessageLite.Builder> T encodeOperatorReason(
            T b, ParamChange c) {
        String reason = c.payload.get("reason");
        if (reason != null) {
            if (b instanceof Hgcp.CmdReload.Builder) {
                ((Hgcp.CmdReload.Builder) b).setReason(reason);
            } else if (b instanceof Hgcp.CmdTriggerReplay.Builder) {
                ((Hgcp.CmdTriggerReplay.Builder) b).setReason(reason);
            }
        }
        long op = longOf(c.payload.getOrDefault("operator_id", "0"));
        if (b instanceof Hgcp.CmdReload.Builder) {
            ((Hgcp.CmdReload.Builder) b).setOperatorId(op);
        } else if (b instanceof Hgcp.CmdTriggerReplay.Builder) {
            ((Hgcp.CmdTriggerReplay.Builder) b).setOperatorId(op);
        }
        rejectUnknown(c, "reason", "operator_id");
        return b;
    }

    // ==================== 验收 6：CMD_UPDATE_PERMISSION 的 DB→wire 组装 ====================

    /**
     * 权限条目输入（{@code T-042} 验收 6 的「派生」输入）。
     *
     * <p>⚠️ {@code DATA_MODEL.md} <b>无</b> {@code is_enterprise_leader} /
     * {@code is_management_role} / {@code has_risk_role} / {@code has_settle_role} 四列
     * （既不在 {@code t_user} 也不在 {@code t_role}）⇒ <b>必须派生、不得直读</b>。
     */
    public static final class PermissionInput {
        /** 操作人用户 ID。 */
        public long operatorId;
        /** 子账户 ID。 */
        public int subAccountId;
        /** 授权品种 ID 列表。 */
        public List<Integer> productIds = new ArrayList<>();
        /** 额度上限（字符串十进制；可空）。 */
        public String quotaLimit;
        /** {@code t_user.is_enterprise_leader}。 */
        public boolean enterpriseLeader;
        /** {@code t_user_role → t_role} 含 {@code APPROVER}。 */
        public boolean managementRole;
        /** {@code t_user_role → t_role} 含 {@code FIN_RISK}。 */
        public boolean riskRole;
        /** {@code t_user_role → t_role} 含 {@code SETTLER}。 */
        public boolean settleRole;
    }

    /**
     * 组装 {@code CMD_UPDATE_PERMISSION}（{@code T-042} 验收 6）。
     *
     * <p>派生口径（架构席 {@code tl-risk-ruling} A.4）：
     * <ul>
     *   <li>{@code SETTLER} → {@code has_settle_role}</li>
     *   <li>{@code FIN_RISK} → {@code has_risk_role}</li>
     *   <li>{@code APPROVER} → {@code is_management_role}</li>
     *   <li>{@code t_user.is_enterprise_leader} → {@code is_enterprise_leader}</li>
     * </ul>
     *
     * @param paramVersion 参数版本
     * @param inputs       条目输入（每条 = 一个「操作人 × 子账户」）
     * @return protobuf 序列化字节
     */
    public static byte[] encodePermission(long paramVersion, List<PermissionInput> inputs) {
        Hgcp.CmdUpdatePermission.Builder b = Hgcp.CmdUpdatePermission.newBuilder()
                .setParamVersion(paramVersion);
        for (PermissionInput in : inputs) {
            Hgcp.PermissionEntry.Builder e = Hgcp.PermissionEntry.newBuilder()
                    .setOperatorId(in.operatorId)
                    .setSubAccountId(in.subAccountId)
                    .setIsEnterpriseLeader(in.enterpriseLeader)
                    .setIsManagementRole(in.managementRole)
                    .setHasRiskRole(in.riskRole)
                    .setHasSettleRole(in.settleRole);
            for (Integer pid : in.productIds) {
                e.addProductIds(pid);
            }
            if (in.quotaLimit != null && !in.quotaLimit.isEmpty()) {
                e.setQuotaLimit(in.quotaLimit);
            }
            b.addEntries(e);
        }
        return b.build().toByteArray();
    }

    /**
     * 由角色代码集合派生三个布尔（验收 6 的派生核心；供 L3 调用后再传入
     * {@link #encodePermission}）。
     *
     * @param roleCodes 角色代码集合（如 {@code SETTLER}/{@code FIN_RISK}/{@code APPROVER}）
     * @return {@code [hasSettleRole, hasRiskRole, isManagementRole]}
     */
    public static boolean[] deriveRoles(java.util.Collection<String> roleCodes) {
        boolean settle = false;
        boolean risk = false;
        boolean mgmt = false;
        for (String r : roleCodes) {
            if (r == null) {
                continue;
            }
            switch (r.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "SETTLER":
                    settle = true;
                    break;
                case "FIN_RISK":
                    risk = true;
                    break;
                case "APPROVER":
                    mgmt = true;
                    break;
                default:
                    break;
            }
        }
        return new boolean[]{settle, risk, mgmt};
    }

    // ==================== entries[n]. 解析 ====================

    /**
     * 解析 {@code entries[n].<字段>} 形式的重复条目。
     *
     * @param c 参数变更
     * @return 按索引升序排列的条目映射列表
     */
    public static List<Map<String, String>> entries(ParamChange c) {
        Map<Integer, Map<String, String>> byIndex = new TreeMap<>();
        for (Map.Entry<String, String> en : c.payload.entrySet()) {
            String k = en.getKey();
            int dot = k.indexOf('.');
            if (dot < 0) {
                continue;
            }
            String prefix = k.substring(0, dot);
            if (!prefix.startsWith("entries[")) {
                continue;
            }
            int close = prefix.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("payload 键名不合法（缺 ]）：" + k);
            }
            int idx;
            try {
                idx = Integer.parseInt(prefix.substring("entries[".length(), close));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("payload 键名索引非数字：" + k);
            }
            byIndex.computeIfAbsent(idx, i -> new TreeMap<>())
                    .put(k.substring(dot + 1), en.getValue());
        }
        return new ArrayList<>(byIndex.values());
    }

    // ==================== 取值与校验 ====================

    private static String require(ParamChange c, String key) {
        String v = c.payload.get(key);
        if (v == null) {
            throw new IllegalArgumentException("payload 缺必填键：" + key);
        }
        return v;
    }

    private static List<String> all(ParamChange c, String key) {
        String v = c.payload.get(key);
        if (v == null || v.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static int uint(String s) {
        return (int) longOf(s);
    }

    private static long longOf(String s) {
        try {
            return Long.parseLong(String.valueOf(s).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("payload 数值字段非数字：" + s);
        }
    }

    /**
     * 拒绝协议外键名（{@code HGC-1.6} 硬规则：不得静默丢弃）。
     */
    private static void rejectUnknown(ParamChange c, String... allowed) {
        List<String> ok = java.util.Arrays.asList(allowed);
        for (String k : c.payload.keySet()) {
            if (!ok.contains(k)) {
                throw new IllegalArgumentException(
                        "payload 含 §4.2.3 协议外键名（HGC-1.6 禁止，不得静默丢弃）：" + k);
            }
        }
    }

    private static void rejectUnknownEntryKeys(ParamChange c, String... allowed) {
        List<String> ok = java.util.Arrays.asList(allowed);
        for (Map<String, String> e : entries(c)) {
            for (String k : e.keySet()) {
                if (!ok.contains(k)) {
                    throw new IllegalArgumentException(
                            "payload 条目含 §4.2.3 协议外键名：" + k);
                }
            }
        }
        // 非 entries[] 的顶层键（param_version 不放 payload）
        for (String k : c.payload.keySet()) {
            if (!k.startsWith("entries[")) {
                throw new IllegalArgumentException(
                        "payload 含非法顶层键（应为 entries[n].<字段>）：" + k);
            }
        }
    }

    /**
     * {@code limit_layer} 字面量 → 枚举。
     *
     * @param s 字面量
     * @return 枚举
     */
    public static Hgcp.LimitLayer mapLimitLayer(String s) {
        if (s == null) {
            return Hgcp.LimitLayer.LIMIT_LAYER_UNSET;
        }
        switch (s.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SUB_PRODUCT":
            case "LIMIT_LAYER_SUB_PRODUCT":
                return Hgcp.LimitLayer.LIMIT_LAYER_SUB_PRODUCT;
            case "SUB_INSTRUMENT":
            case "LIMIT_LAYER_SUB_INSTRUMENT":
                return Hgcp.LimitLayer.LIMIT_LAYER_SUB_INSTRUMENT;
            case "ACCOUNT_PRODUCT":
            case "LIMIT_LAYER_ACCOUNT_PRODUCT":
                return Hgcp.LimitLayer.LIMIT_LAYER_ACCOUNT_PRODUCT;
            case "AUTH_QUOTA":
            case "LIMIT_LAYER_AUTH_QUOTA":
                return Hgcp.LimitLayer.LIMIT_LAYER_AUTH_QUOTA;
            default:
                return Hgcp.LimitLayer.LIMIT_LAYER_UNSET;
        }
    }

    /**
     * {@code scan_scope} 字面量 → 枚举（§3.2.1：以协议表 {@code scan_scope} 为准）。
     *
     * @param s 字面量
     * @return 枚举
     */
    public static Hgcp.ScanScope mapScanScope(String s) {
        String v = String.valueOf(s).trim().toUpperCase(java.util.Locale.ROOT);
        if (v.endsWith("OPEN_ONLY")) {
            return Hgcp.ScanScope.SCAN_SCOPE_OPEN_ONLY;
        }
        return Hgcp.ScanScope.SCAN_SCOPE_UNSET;
    }

    /**
     * {@code target_state} 字面量 → 枚举。
     *
     * @param s 字面量
     * @return 枚举
     */
    public static Hgcp.TargetState mapTargetState(String s) {
        switch (String.valueOf(s).trim().toUpperCase(java.util.Locale.ROOT)) {
            case "READY":
            case "TARGET_STATE_READY":
                return Hgcp.TargetState.TARGET_STATE_READY;
            case "STOPPING":
            case "TARGET_STATE_STOPPING":
                return Hgcp.TargetState.TARGET_STATE_STOPPING;
            case "SUSPENDED":
            case "TARGET_STATE_SUSPENDED":
                return Hgcp.TargetState.TARGET_STATE_SUSPENDED;
            default:
                return Hgcp.TargetState.TARGET_STATE_UNSET;
        }
    }
}
