package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.MajorEventDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SettleAggregationCheckDao;
import com.hedgeguard.dao.entity.MajorEvent;
import com.hedgeguard.dao.entity.SettleAggregationCheck;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * 重大事项「加总对账不一致且超时未闭环」自动触发器（{@code T-081}；{@code R-AUDIT-5}
 * <b>条件①</b>；{@code FR-AUDIT-7} 验收 1）。
 *
 * <p><b>范围声明（工单硬约束）</b>：本类<b>仅承接 {@code R-AUDIT-5} 条件①</b>——
 * 「加总对账不一致且超时未闭环」命中即自动提醒生成专项报告。
 * 条件②（触及规模红线）/③（交易所问询或警示）/④（强制平仓提示未处置）<b>均在本类范围外</b>
 * （②③④无自动写入方系 INV-081082 盘点报告 §1.3 的<b>范围外观察</b>，本单不施工）；
 * 条件⑤（单笔/当日亏损超阈值）由 {@link MajorEventLossThresholdTrigger} 承载（{@code T-188}）。
 *
 * <p><b>触发语义</b>：{@code t_aggregation_check} 中存在
 * {@code result='INCONSISTENT_BLOCKED' AND closed_time IS NULL}（未闭环）
 * 且 {@code check_time} 早于「当前时间 − 超时阈值」（超时，{@code CFG} 键缺省
 * <b>24 小时</b>）的对账记录 ⇒ 逐条自动写 {@code t_major_event} 触发行
 * （{@code event_type=AGG_INCONSISTENT_TIMEOUT}（{@code ck_major_event_type} 冻结域）+
 * {@code status=TRIGGERED}，供 {@code AuditReportService.pendingMajorEvents} 经
 * {@code findByTypeAndStatus} 消费——既有 major-event 报告契约不改，与条件⑤同形态）。
 *
 * <p><b>接线点</b>：{@code SettleService.aggregate}（加总对账，{@code FR-SETTLE-8}）
 * 事务<b>提交后</b>调用（对齐 {@code T-188} 轨 1 接线先例形态）；旁路提醒能力：
 * 本类抛出的任何 {@link RuntimeException} 由调用侧捕获并记 SEVERE 日志、
 * <b>不阻断加总对账主流程</b>（关账门禁 {@code R-SETTLE-2} 阻断逻辑零改动）。
 * 每次加总对账重跑即天然重评一次（存在超时未闭环记录即补提醒）。
 *
 * <p><b>幂等（AGG 稳定键）</b>：{@code eventNo} =
 * {@code AGG-<对账记录 tradeDate yyyyMMdd>-<checkType>}——<b>锚业务日（对账记录的
 * {@code tradeDate}），不锚触发时点</b>（丢失线判例：取触发日会跨日重复落行——同一笔超时
 * 未闭环记录在 D+1、D+2 的重评中编号不变，不重复落行；同一交易日同一类型的加总不一致
 * 是同一个提醒事项）。{@code DmMajorEventDao} {@code uk_major_event_no}（{@code event_no}
 * 单列）唯一约束兜底：「先查后插」前置换取同号已存在即跳过（对齐条件⑤
 * {@link MajorEventLossThresholdTrigger} 先例）；同号并发撞 uk 的形态不静默——异常经
 * 调用侧记日志披露。
 *
 * <p><b>配置读取与处置</b>：惰性读取（先例 {@code CFG-26}，装配期不触库）。
 * 缺键/空值 ⇒ 安全回退默认 {@value #DEFAULT_TIMEOUT_HOURS} 小时；
 * <b>非法（非整数）或 ≤0 ⇒ 显式拒</b>：抛 {@link IllegalStateException}，由调用侧记
 * SEVERE 日志并拒绝本次触发判定（不静默回退默认值——以错误阈值判定比不判定更危险；
 * 不阻断主流程）。
 *
 * <p><b>取数面</b>：经 {@link SettleAggregationCheckDao#findPage}（{@code BaseDao} 冻结
 * 契约方法，{@code dao} 之外零 SQL 语句形态，{@code E5} 合规）；键集逐页循环
 * （终止 = 本页条数 &lt; 页大小，{@code T-190} 先例），单次评估耗尽全部超时记录。
 */
public final class AggregationInconsistencyTrigger {

    private static final Logger LOG = Logger.getLogger(AggregationInconsistencyTrigger.class.getName());

    /** 配置键（超时阈值，小时；{@code data-init.sql} §④-3 播种缺省 24）。 */
    public static final String CONFIG_KEY = "audit.major_event.agg_inconsistency_timeout_hours";

    /** 缺省超时阈值（小时）：{@code data-init.sql} 播种值，缺键时安全回退。 */
    public static final int DEFAULT_TIMEOUT_HOURS = 24;

    /** 事项类型（{@code ck_major_event_type} 冻结域字面量，条件①专用）。 */
    public static final String EVENT_TYPE = "AGG_INCONSISTENT_TIMEOUT";

    /** 事项状态（待生成报告；供 {@code AuditReportService.pendingMajorEvents} 消费）。 */
    public static final String STATUS_TRIGGERED = "TRIGGERED";

    /** 触发来源前缀（关联对象标识 = 加总对账记录主键）。 */
    public static final String SOURCE_PREFIX = "AGGREGATION_CHECK:";

    /** 分页大小（键集逐页循环页宽；{@code PageQuery} 允许档位）。 */
    private static final String PAGE_SIZE = "100";

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MajorEventDao majorEventDao;
    private final SettleAggregationCheckDao aggregationCheckDao;
    /** 超时阈值原始取值（生产 = ConfigService 惰性读取；{@code null} = 缺键 ⇒ 回退默认）。 */
    private final java.util.function.Supplier<String> timeoutRaw;
    private final long systemOperatorId;

    /**
     * 构造。
     *
     * @param majorEventDao      重大事项 DAO（真实）
     * @param aggregationCheckDao 加总对账记录 DAO（真实；取数面）
     * @param timeoutRaw         超时阈值原始取值供给（缺键返回 {@code null}）
     * @param systemOperatorId   系统操作人（落库留痕）
     */
    public AggregationInconsistencyTrigger(MajorEventDao majorEventDao,
                                           SettleAggregationCheckDao aggregationCheckDao,
                                           java.util.function.Supplier<String> timeoutRaw,
                                           long systemOperatorId) {
        this.majorEventDao = majorEventDao;
        this.aggregationCheckDao = aggregationCheckDao;
        this.timeoutRaw = timeoutRaw;
        this.systemOperatorId = systemOperatorId;
    }

    /**
     * 扫描「加总对账不一致且超时未闭环」的对账记录，逐条自动落 {@code t_major_event}
     * 触发行（幂等：AGG 稳定键先查后插）。
     *
     * <p>由 {@code SettleService.aggregate} 事务提交后调用；本方法抛出的任何
     * {@link RuntimeException} 由调用侧捕获并记日志，不阻断加总对账主流程。
     *
     * @return 本次真实落行的条数（幂等去重后）
     */
    public int evaluate() {
        LocalDateTime now = Clocks.now();
        LocalDateTime deadline = now.minusHours(resolveTimeoutHours());
        int fired = 0;
        int page = 1;
        while (true) {
            PageResult<SettleAggregationCheck> result = aggregationCheckDao.findPage(
                    PageQuery.of(String.valueOf(page), PAGE_SIZE, "id,asc", "id"),
                    "result = ? AND closed_time IS NULL AND check_time < ?",
                    Arrays.asList(SettleService.AGG_INCONSISTENT, deadline));
            for (SettleAggregationCheck check : result.items()) {
                if (fire(check)) {
                    fired++;
                }
            }
            if (result.items().size() < Integer.parseInt(PAGE_SIZE)) {
                break; // 键集逐页循环终止条件：本页不足页宽（T-190 先例）
            }
            page++;
        }
        return fired;
    }

    /**
     * 解析当前生效超时阈值（小时；缺键回退默认；非法/≤0 显式拒）。
     *
     * @return 超时阈值（小时）
     */
    public int resolveTimeoutHours() {
        String raw = timeoutRaw == null ? null : timeoutRaw.get();
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_TIMEOUT_HOURS; // 缺键 ⇒ 安全回退默认（播种值）
        }
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(CONFIG_KEY + " 取值非法（非整数）=\"" + raw
                    + "\"：显式拒（拒绝本次触发判定，不回退默认值，不阻断主流程）", e);
        }
        if (v <= 0) {
            throw new IllegalStateException(CONFIG_KEY + " 取值须 >0，实际=\"" + raw
                    + "\"：显式拒（拒绝本次触发判定，不回退默认值，不阻断主流程）");
        }
        return v;
    }

    /**
     * 落一行触发行（幂等：确定性编号先查后插，同号已存在即跳过）。
     *
     * @return 是否本次真实落行（{@code false} = 该提醒已存在）
     */
    private boolean fire(SettleAggregationCheck check) {
        // 幂等稳定键：锚对账记录业务日（tradeDate），不锚触发时点——跨日重评不重复落行
        String eventNo = "AGG-" + check.tradeDate.format(DAY_FMT) + "-" + check.checkType;
        if (majorEventDao.findByEventNo(eventNo) != null) {
            return false; // uk_major_event_no 同键：同一交易日同一类型至多 1 行提醒
        }
        MajorEvent row = new MajorEvent();
        row.subAccountId = null; // 加总对账为全账户级核对（σ 子账户 = 真实账户），主体非单一子账户
        row.eventNo = eventNo;
        row.eventType = EVENT_TYPE;
        row.triggerSource = SOURCE_PREFIX + check.id;
        row.eventDesc = "加总对账不一致且超时未闭环（" + SettleService.AGG_INCONSISTENT
                + "，超时阈值=" + resolveTimeoutHours() + "h，" + CONFIG_KEY + "）：tradeDate="
                + check.tradeDate + "，checkType=" + check.checkType
                + "，Σ子账户=" + check.sumSubAccount.toPlainString()
                + "，真实账户=" + check.realAccountValue.toPlainString()
                + "，差额=" + check.difference.toPlainString()
                + "，checkTime=" + check.checkTime + "；提醒生成重大事项专项报告（FR-AUDIT-7）";
        row.impactScope = "全账户（加总对账阻断 scope=" + check.blockScope + "）；tradeDate="
                + check.tradeDate + "；checkType=" + check.checkType;
        row.status = STATUS_TRIGGERED;
        row.auditReportId = null;
        row.triggeredTime = Clocks.now();
        majorEventDao.insert(row, systemOperatorId);
        LOG.info("重大事项自动触发（T-081/R-AUDIT-5 条件①）：eventNo=" + eventNo
                + " checkId=" + check.id + "；tradeDate=" + check.tradeDate
                + " checkType=" + check.checkType);
        return true;
    }
}
