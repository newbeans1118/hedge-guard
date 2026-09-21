package com.hedgeguard.web.ws;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 客户端订阅（`CONTRACTS.md` §4.1.2 `W-1` 提案形态；`W-2` 已裁定为**安全必做**）。
 *
 * <p>客户端消息（收）：
 * <pre>
 * {"type":"subscribe","channels":["quote","alert"],"instruments":["ru2601"],"subAccounts":[1,2]}
 * {"type":"unsubscribe","channels":["alert"]}
 * </pre>
 * 服务端应答（发）：{@code {"type":"subscribed","channels":[...],"subAccounts":[...],"ts":"...","tsMillis":123}}
 *
 * <p><b>安全不变量（`W-2`）</b>：{@link #subAccounts()} 中的值**必须**已经过
 * {@link WsAuthorizer#authorize} 与 {@code t_user_sub_account_perm} 求交；
 * **空集表示"不得下发任何数据"**（安全默认），推送侧一律按**空集恒过滤**处理，不区分"未订阅"与"未授权"。
 */
public final class WsSubscription {

    /** 行情通道名（{@code /ws/quote}）。 */
    public static final String CHANNEL_QUOTE = "quote";

    /** 告警通道名（{@code /ws/alert}）。 */
    public static final String CHANNEL_ALERT = "alert";

    private final Set<String> channels;
    private final Set<String> instruments;
    private final Set<Integer> subAccounts;

    /**
     * 构造。
     *
     * @param channels    已启用的通道（{@code quote}/{@code alert}）
     * @param instruments 行情合约白名单（**空集 = 全部**）
     * @param subAccounts 已授权子账户集合（**空集 = 不下发任何数据**）
     */
    public WsSubscription(Set<String> channels, Set<String> instruments, Set<Integer> subAccounts) {
        this.channels = Collections.unmodifiableSet(new LinkedHashSet<>(channels));
        this.instruments = Collections.unmodifiableSet(new LinkedHashSet<>(instruments));
        this.subAccounts = Collections.unmodifiableSet(new LinkedHashSet<>(subAccounts));
    }

    /** 空订阅（连接建立时的初始态：不订阅任何通道、不含任何子账户）。 */
    public static WsSubscription empty() {
        return new WsSubscription(Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet());
    }

    /** 已启用通道（只读）。 */
    public Set<String> channels() {
        return channels;
    }

    /** 行情合约白名单（只读；空集 = 全部）。 */
    public Set<String> instruments() {
        return instruments;
    }

    /** 已授权子账户（只读；**空集 = 不下发任何数据**）。 */
    public Set<Integer> subAccounts() {
        return subAccounts;
    }

    /** 是否订阅行情通道。 */
    public boolean wantsQuote() {
        return channels.contains(CHANNEL_QUOTE);
    }

    /** 是否订阅告警通道。 */
    public boolean wantsAlert() {
        return channels.contains(CHANNEL_ALERT);
    }

    /**
     * 该合约的行情是否应下发（通道已订阅 **且** 合约在白名单内或白名单为空）。
     *
     * @param instrument 合约
     * @return 应下发返回 {@code true}
     */
    public boolean matchesQuote(String instrument) {
        if (!wantsQuote()) {
            return false;
        }
        return instruments.isEmpty()
                || (instrument != null && instruments.contains(instrument.toUpperCase(Locale.ROOT)));
    }

    /**
     * 该子账户的告警是否应下发。
     *
     * <p><b>安全默认</b>：{@link #subAccounts()} 为空 → **恒 {@code false}**（与 DAO 层
     * {@code SubAccountScope} 的"空集 → 恒假"同款纪律）。
     *
     * @param subAccountId 子账户
     * @return 应下发返回 {@code true}
     */
    public boolean matchesAlert(Integer subAccountId) {
        if (!wantsAlert() || subAccounts.isEmpty()) {
            return false;
        }
        // 账户级/全账户口径（哨兵 0）仅在其被显式授权时下发
        return subAccountId != null && subAccounts.contains(subAccountId);
    }

    /** 切换到指定通道集并保留既有合约/子账户（用于 {@code unsubscribe} 的减法语义）。 */
    public WsSubscription withChannels(Set<String> newChannels) {
        return new WsSubscription(newChannels, instruments, subAccounts);
    }

    @Override
    public String toString() {
        return "WsSubscription{channels=" + channels + ", instruments=" + instruments
                + ", subAccounts=" + subAccounts + '}';
    }
}
