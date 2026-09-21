package com.hedgeguard.testsupport;

/**
 * 测试桩的公共约定（{@code NFR-TEST-2}：测试打桩不引 Mockito）。
 *
 * <p>假 DAO 只实现被用例真正驱动的方法；其余方法一律经 {@link #unsupported} 显式失败，
 * 以避免"桩悄悄返回默认值"把缺陷掩盖成通过（例如分页逻辑被漏测却无感）。
 */
public final class FakeDao {

    private FakeDao() {
    }

    /**
     * 桩未实现的方法：显式失败而非静默返回默认值。
     *
     * @param method 方法名
     * @return 永不被返回的异常（供 {@code throw} 使用）
     */
    public static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(
                "测试桩未实现该方法：" + method
                        + "；复现=用例需要该方法时在对应 Fake*Dao 中补齐（不得改为返回默认值）");
    }
}
