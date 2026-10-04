package com.hedgeguard.web;

import com.hedgeguard.bridge.HgcpEventListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-172} 事件面装配：单槽接缝组合器 {@link Bootstrap.CompositeHgcpEventListener} 单测
 * （{@code NFR-TEST-2}：不引 Mockito，以记录型匿名实现替代）。
 *
 * <p><b>承载</b>：{@link com.hedgeguard.bridge.HgcpClient} 的 {@code setEventListener} 为单槽，
 * 生产装配把「事中风控投喂」与「交易执行落库」两消费者组合；本测试断言
 * <b>两消费者都要被回调</b>（依次分发）且<b>单个消费者抛异常不阻断其余消费者</b>（{@code NFR-REL-5}）。
 */
class CompositeHgcpEventListenerTest {

    /** 记录型消费者：把收到的回调名与关键参数写入 {@link #calls}。 */
    private static final class Recorder implements HgcpEventListener {
        final List<String> calls = new ArrayList<>();
        boolean throwOnTradeFill;

        @Override
        public void onEventConsumed(int eventType, long seq) {
            calls.add("onEventConsumed:" + eventType + ":" + seq);
        }

        @Override
        public void onTradeFill(HgcpEventListener.TradeFill e) {
            if (throwOnTradeFill) {
                throw new IllegalStateException("boom");
            }
            calls.add("onTradeFill:" + e.tradeId);
        }

        @Override
        public void onPreTradeReject(HgcpEventListener.PreTradeReject e) {
            calls.add("onPreTradeReject:" + e.ruleId);
        }
    }

    @Test
    @DisplayName("T-172 组合器：两消费者依次收到同一回调（单槽接缝多播）")
    void fansOutToAllDelegates() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        Bootstrap.CompositeHgcpEventListener composite =
                new Bootstrap.CompositeHgcpEventListener(a, b);

        HgcpEventListener.TradeFill fill = new HgcpEventListener.TradeFill();
        fill.tradeId = "T-1";
        composite.onTradeFill(fill);
        composite.onEventConsumed(0x0204, 7L);
        HgcpEventListener.PreTradeReject reject = new HgcpEventListener.PreTradeReject();
        reject.ruleId = "R-RISK-2";
        composite.onPreTradeReject(reject);

        List<String> expected = Arrays.asList(
                "onTradeFill:T-1", "onEventConsumed:516:7", "onPreTradeReject:R-RISK-2");
        assertEquals(expected, a.calls, "消费者 A 收到全部回调且顺序一致");
        assertEquals(expected, b.calls, "消费者 B 收到全部回调且顺序一致");
    }

    @Test
    @DisplayName("T-172 组合器：单个消费者抛异常不阻断其余消费者")
    void oneDelegateFailureDoesNotBlockOthers() {
        Recorder throwing = new Recorder();
        throwing.throwOnTradeFill = true;
        Recorder healthy = new Recorder();
        Bootstrap.CompositeHgcpEventListener composite =
                new Bootstrap.CompositeHgcpEventListener(throwing, healthy);

        HgcpEventListener.TradeFill fill = new HgcpEventListener.TradeFill();
        fill.tradeId = "T-2";
        composite.onTradeFill(fill);

        assertTrue(throwing.calls.isEmpty(), "抛异常的消费者未记录（异常已发生）");
        assertEquals(Arrays.asList("onTradeFill:T-2"), healthy.calls, "后序消费者仍收到回调");
    }
}
