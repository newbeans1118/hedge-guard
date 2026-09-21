package com.hedgeguard.dao.entity;

/**
 * {@code TB-CONFIG-7 t_ctp_front_config} CTP 前置配置（{@code DATA_MODEL.md} §2.1）。
 *
 * <p>结构上就不具备存储敏感凭据的能力：本类型<b>无任何密码 / AuthCode 字段</b>（{@code NFR-SEC-1}）。
 */
public final class CtpFrontConfig extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** {@code SIMULATION} / {@code PRODUCTION}（显式区分，防误连实盘）。 */
    public String envType;

    /** {@code TRADE} / {@code MD}。 */
    public String frontType;

    /** 前置地址（{@code tcp://ip:port}）。 */
    public String frontAddr;

    /** 优先级（0 = 主，&gt;0 = 备份）。 */
    public int priority;

    /** 经纪商代码。 */
    public String brokerId;

    /** 用户代码（资金账号）。 */
    public String investorId;

    /** 连接超时（毫秒）。 */
    public int connectTimeoutMs;

    /** 重连间隔（毫秒）。 */
    public int reconnectIntervalMs;

    /** CTP 流文件目录（非敏感）。 */
    public String flowPath;

    /** 是否启用。 */
    public String isEnabled;
}
