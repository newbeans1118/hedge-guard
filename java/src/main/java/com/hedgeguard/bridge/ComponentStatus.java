package com.hedgeguard.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C++ 组件（{@code hg-trading}）运行状态快照（阶段 1 监控面）。
 *
 * <p><b>不可变语义</b>：{@link HgcpClient} 在同步块内持有一个可变实例，对外
 * {@link HgcpClient#componentStatus()} 一律返回 {@link #copy()} 副本——调用方拿到的
 * 是时点快照，改它不会影响内部状态。
 *
 * <p><b>与 MirrorState 物理隔离</b>：本快照只用于监控观测，<b>绝不回写镜像</b>、
 * 绝不作为记账依据（{@code ARCHITECTURE.md §2.5 铁律 4}）。镜像仍是事中风控的唯一
 * 事件驱动数据源，本快照不替代它。
 *
 * <p><b>数据来源标注</b>（字段注释）：
 * {@code [事件]} = {@code EVT_SYSTEM_STATE/CTP_STATE/TRADING_ALERT} 推送；
 * {@code [查询]} = {@code CMD_QUERY_STATUS} 主动核验；
 * {@code [链路]} = Java 侧 HGCP 连接自身观测。
 * 所有值均为「最近一次观测」，非实时真值；{@code takenAt} 标注快照构建时点。
 *
 * <p>阶段 1 只承载现有线格式已提供的字段，<b>不新增 proto 字段/消息</b>；
 * 阶段 2 若 {@code MSG_ACK.detail} 升级为 JSON，只扩本类字段与
 * {@link StatusDetailParser}，不动传输层。
 */
public final class ComponentStatus {

    /** [链路] Java 侧 HGCP 桥是否已装配。 */
    public boolean bridgeAssembled;

    /** [链路] 是否已通过 HELLO_ACK 鉴权。 */
    public boolean linkEstablished;

    /** [事件/查询] 状态机名：{@code NOT_READY/REPLAYING/READY/STOPPING/UNKNOWN}。 */
    public String state = "UNKNOWN";

    /** [查询] 就绪门卫（{@code CMD_QUERY_STATUS} 的 {@code ready=...}）。 */
    public boolean ready;

    /** [事件/查询] 已生效参数版本。 */
    public long paramVersion;

    /** [查询] 参数加载已完成批次数（{@code load=x/y} 的 x）。 */
    public int loadBatchesReceived = -1;

    /** [查询] 参数加载总批次数（{@code load=x/y} 的 y）。 */
    public int loadBatchesTotal = -1;

    /** [查询·阶段2] 状态机细名（AppState，可区分 BLOCKED/FAILED）。 */
    public String componentState;

    /** [查询·阶段2] 协议映射态（ServerState；BLOCKED/FAILED 归 NOT_READY）。 */
    public String serverState;

    /** [查询·阶段2] 重放：执行次数 / 会话起点 / 状态名。 */
    public long replayRuns = -1;
    public long replayStartedMs = -1;
    public String replayState;

    /** [查询·阶段2] 事件队列深度 / 容量 / 背压计数。 */
    public long eventQueueDepth = -1;
    public long eventQueueCapacity = -1;
    public long backpressureCount = -1;

    /** [查询·阶段2] C++ 侧运行期计数（键 = 计数器 camelCase 名）。 */
    public Map<String, Long> counters;

    /** [查询·阶段2] CTP 登录观察态 / 降级态（C++ 内存观测，非会话断言）。 */
    public Boolean ctpLoggedIn;
    public Boolean ctpDegraded;

    /** [查询·阶段2] 组件运行时长（ms）/ C++ 侧时点 / what 位掩码回显。 */
    public long uptimeMs = -1;
    public long componentTsMs = -1;
    public int queryWhat = -1;

    /** [事件] {@code EvtSystemState.loaded_counts} JSON 摘要（原样透传）。 */
    public String loadedCounts;

    /** [事件] 状态变更原因。 */
    public String stateReason;

    /** [事件] 状态变更时点（epoch ms，0 = 未提供）。 */
    public long stateTsMs;

    /** [事件] CTP 交易前置连接态。 */
    public boolean ctpTradeConnected;

    /** [事件] CTP 行情前置连接态。 */
    public boolean ctpMdConnected;

    /** [事件] 交易前置地址。 */
    public String ctpFront;

    /** [事件] CTP 状态变更时点（epoch ms）。 */
    public long ctpStateChangeTsMs;

    /** [事件] CTP 状态变更原因。 */
    public String ctpReason;

    /** [事件] 最近一条交易告警码（0 = 无）。 */
    public int lastAlertCode;

    /** [事件] 最近一条交易告警级别。 */
    public String lastAlertLevel;

    /** [事件] 最近一条交易告警详情。 */
    public String lastAlertDetail;

    /** [事件] 最近一条交易告警时点（epoch ms）。 */
    public long lastAlertTsMs;

    /** [链路] 最后收到任意 HGCP 帧的时点（epoch ms，0 = 从未收帧）。 */
    public long lastFrameAtMs;

    /** [链路] 当前连接代际（重连递增）。 */
    public long connectionEpoch;

    /** [链路] 累计重连成功次数。 */
    public long reconnectCount;

    /** [链路] 镜像事件流水位（补数判据，取 MirrorState 只读快照）。 */
    public long watermarkEventSeq;

    /** [查询] 最近一次主动核验时点（epoch ms，0 = 从未核验）。 */
    public long lastQueryTsMs;

    /** [查询] 最近一次主动核验是否收到成功回执。 */
    public boolean lastQueryOk;

    /** [查询] 最近一次核验的回执说明/错误。 */
    public String lastQueryMessage;

    /** [查询] 最近一次核验的原始 {@code MSG_ACK.detail}（诊断用）。 */
    public String queryDetail;

    /** 最近一次 {@code ?refresh=1} 是否在超时前未返回（此时返回的是缓存快照）。 */
    public boolean refreshPending;

    /** 快照构建时点（ISO-8601）。 */
    public String takenAt;

    /** [阶段3] 组件告警有界历史（最近 N 条；仅内存留痕，重启不保留）。 */
    public List<ComponentAlert> alerts;

    /** 空快照（组件桥未装配时用）。 */
    public static ComponentStatus empty() {
        ComponentStatus s = new ComponentStatus();
        s.bridgeAssembled = false;
        s.linkEstablished = false;
        s.state = "UNKNOWN";
        s.loadBatchesReceived = -1;
        s.loadBatchesTotal = -1;
        s.lastAlertLevel = null;
        s.alerts = new ArrayList<>();
        return s;
    }

    /** 逐字段复制（保证对外快照与内部可变实例隔离）。 */
    public ComponentStatus copy() {
        ComponentStatus o = new ComponentStatus();
        o.bridgeAssembled = bridgeAssembled;
        o.linkEstablished = linkEstablished;
        o.state = state;
        o.ready = ready;
        o.paramVersion = paramVersion;
        o.loadBatchesReceived = loadBatchesReceived;
        o.loadBatchesTotal = loadBatchesTotal;
        o.componentState = componentState;
        o.serverState = serverState;
        o.replayRuns = replayRuns;
        o.replayStartedMs = replayStartedMs;
        o.replayState = replayState;
        o.eventQueueDepth = eventQueueDepth;
        o.eventQueueCapacity = eventQueueCapacity;
        o.backpressureCount = backpressureCount;
        o.counters = counters == null ? null : new LinkedHashMap<>(counters);
        o.ctpLoggedIn = ctpLoggedIn;
        o.ctpDegraded = ctpDegraded;
        o.uptimeMs = uptimeMs;
        o.componentTsMs = componentTsMs;
        o.queryWhat = queryWhat;
        o.loadedCounts = loadedCounts;
        o.stateReason = stateReason;
        o.stateTsMs = stateTsMs;
        o.ctpTradeConnected = ctpTradeConnected;
        o.ctpMdConnected = ctpMdConnected;
        o.ctpFront = ctpFront;
        o.ctpStateChangeTsMs = ctpStateChangeTsMs;
        o.ctpReason = ctpReason;
        o.lastAlertCode = lastAlertCode;
        o.lastAlertLevel = lastAlertLevel;
        o.lastAlertDetail = lastAlertDetail;
        o.lastAlertTsMs = lastAlertTsMs;
        o.lastFrameAtMs = lastFrameAtMs;
        o.connectionEpoch = connectionEpoch;
        o.reconnectCount = reconnectCount;
        o.watermarkEventSeq = watermarkEventSeq;
        o.lastQueryTsMs = lastQueryTsMs;
        o.lastQueryOk = lastQueryOk;
        o.lastQueryMessage = lastQueryMessage;
        o.queryDetail = queryDetail;
        o.refreshPending = refreshPending;
        o.takenAt = takenAt;
        o.alerts = alerts == null ? null : new ArrayList<>(alerts);
        return o;
    }
}
