package com.hedgeguard.dao.pool;

/**
 * 连接池参数（默认值取自 {@code ARCHITECTURE.md} §7.2.2 自实现连接池的约定）。
 *
 * @param initialSize             预建连接数
 * @param maxSize                 最大连接数（并发上限）
 * @param borrowTimeoutMillis     借出超时（超时抛 {@link PoolExhaustedException}，<b>不</b>无限等待）
 * @param validationTimeoutSeconds {@code Connection.isValid} 校验超时（秒）
 */
public final class PoolConfig {

    /** 默认预建连接数。 */
    public static final int DEFAULT_INITIAL_SIZE = 2;

    /** 默认最大连接数（内部岗位级并发，非高频交易路径）。 */
    public static final int DEFAULT_MAX_SIZE = 8;

    /** 默认借出超时（毫秒）。 */
    public static final int DEFAULT_BORROW_TIMEOUT_MILLIS = 3000;

    /** 默认连接校验超时（秒）。 */
    public static final int DEFAULT_VALIDATION_TIMEOUT_SECONDS = 2;

    private final int initialSize;
    private final int maxSize;
    private final int borrowTimeoutMillis;
    private final int validationTimeoutSeconds;

    /**
     * 构造。
     *
     * @param initialSize             预建连接数（&gt;= 0 且 &lt;= maxSize）
     * @param maxSize                 最大连接数（&gt;= 1）
     * @param borrowTimeoutMillis     借出超时（&gt;= 1）
     * @param validationTimeoutSeconds 校验超时（&gt;= 1）
     */
    public PoolConfig(int initialSize, int maxSize, int borrowTimeoutMillis,
                      int validationTimeoutSeconds) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("连接池 maxSize 须 >= 1：值=" + maxSize);
        }
        if (initialSize < 0 || initialSize > maxSize) {
            throw new IllegalArgumentException(
                    "连接池 initialSize 须在 [0, maxSize] 内：initial=" + initialSize + " max=" + maxSize);
        }
        if (borrowTimeoutMillis < 1) {
            throw new IllegalArgumentException("借出超时须 >= 1 ms：值=" + borrowTimeoutMillis);
        }
        if (validationTimeoutSeconds < 1) {
            throw new IllegalArgumentException("校验超时须 >= 1 s：值=" + validationTimeoutSeconds);
        }
        this.initialSize = initialSize;
        this.maxSize = maxSize;
        this.borrowTimeoutMillis = borrowTimeoutMillis;
        this.validationTimeoutSeconds = validationTimeoutSeconds;
    }

    /** 默认参数。 */
    public static PoolConfig defaults() {
        return new PoolConfig(DEFAULT_INITIAL_SIZE, DEFAULT_MAX_SIZE, DEFAULT_BORROW_TIMEOUT_MILLIS,
                DEFAULT_VALIDATION_TIMEOUT_SECONDS);
    }

    /** 预建连接数。 */
    public int initialSize() {
        return initialSize;
    }

    /** 最大连接数。 */
    public int maxSize() {
        return maxSize;
    }

    /** 借出超时（毫秒）。 */
    public int borrowTimeoutMillis() {
        return borrowTimeoutMillis;
    }

    /** 校验超时（秒）。 */
    public int validationTimeoutSeconds() {
        return validationTimeoutSeconds;
    }
}
