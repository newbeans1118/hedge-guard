package com.hedgeguard.web;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.bridge.HgcpClient;
import com.hedgeguard.bridge.HgcpEventListener;
import com.hedgeguard.bridge.TcpHgcpTransport;
import com.hedgeguard.bridge.TradingBridge;
import com.hedgeguard.common.SensitiveLog;
import com.hedgeguard.dao.AuthorizationDao;
import com.hedgeguard.dao.ParamDispatchDao;
import com.hedgeguard.dao.RoleDao;
import com.hedgeguard.dao.UserAccountDao;
import com.hedgeguard.dao.UserRoleDao;
import com.hedgeguard.dao.impl.DmAccountSubjectDao;
import com.hedgeguard.dao.impl.DmAuditLogChecksumDao;
import com.hedgeguard.dao.impl.DmAuditLogDao;
import com.hedgeguard.dao.impl.DmAuditReportDao;
import com.hedgeguard.dao.impl.DmConfigChangeLogDao;
import com.hedgeguard.dao.impl.DmAbnormalOrderStatDao;
import com.hedgeguard.dao.impl.DmMirrorWatermarkDao;
import com.hedgeguard.dao.impl.DmRiskAlertDao;
import com.hedgeguard.dao.impl.DmRiskDisposalDao;
import com.hedgeguard.dao.impl.DmRiskMetricSnapshotDao;
import com.hedgeguard.dao.impl.DmRiskThresholdDao;
import com.hedgeguard.dao.impl.DmLimitUsageStatDao;
import com.hedgeguard.dao.impl.DmAnnualScaleDao;
import com.hedgeguard.dao.impl.DmConfigItemDao;
import com.hedgeguard.dao.impl.DmCtpFrontConfigDao;
import com.hedgeguard.dao.impl.DmCapitalReceiptDao;
import com.hedgeguard.dao.impl.DmCustomHedgeRuleDao;
import com.hedgeguard.dao.impl.DmDepositWithdrawApplyDao;
import com.hedgeguard.dao.impl.DmDictItemDao;
import com.hedgeguard.dao.impl.DmEvidenceExportDao;
import com.hedgeguard.dao.impl.DmFundFlowDao;
import com.hedgeguard.dao.impl.DmFundLedgerDao;
import com.hedgeguard.dao.impl.DmFundQuotaAlertDao;
import com.hedgeguard.dao.impl.DmFundQuotaDao;
import com.hedgeguard.dao.impl.DmFundReconcileDao;
import com.hedgeguard.dao.impl.DmFundReviewRecordDao;
import com.hedgeguard.dao.impl.DmMajorEventDao;
import com.hedgeguard.dao.impl.DmPostRiskReportDao;
import com.hedgeguard.dao.impl.DmTransferApplyDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmAuthorizationDao;
import com.hedgeguard.dao.impl.DmExposureDetailDao;
import com.hedgeguard.dao.impl.DmHedgeDocDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmHedgeRelationDao;
import com.hedgeguard.dao.impl.DmEffectivenessAssessDao;
import com.hedgeguard.dao.impl.DmHedgePlanExposureDao;
import com.hedgeguard.dao.impl.DmHedgeRatioCalcDao;
import com.hedgeguard.dao.impl.DmInventoryLedgerDao;
import com.hedgeguard.dao.impl.DmLoginLogDao;
import com.hedgeguard.dao.impl.DmLoginSessionDao;
import com.hedgeguard.dao.impl.DmOperationLogDao;
import com.hedgeguard.dao.impl.DmParamDispatchDao;
import com.hedgeguard.dao.impl.DmPasswordHistoryDao;
import com.hedgeguard.dao.impl.DmPermissionChangeLogDao;
import com.hedgeguard.dao.impl.DmPlanApprovalDao;
import com.hedgeguard.dao.impl.DmPlanStateLogDao;
import com.hedgeguard.dao.impl.DmPositionConflictDao;
import com.hedgeguard.dao.impl.DmPositionGroupDao;
import com.hedgeguard.dao.impl.DmProductChangeLogDao;
import com.hedgeguard.dao.impl.DmProductContractDao;
import com.hedgeguard.dao.impl.DmProductDao;
import com.hedgeguard.dao.impl.DmProductQuotaDao;
import com.hedgeguard.dao.impl.DmProductSpotCategoryDao;
import com.hedgeguard.dao.impl.DmProductUnitDao;
import com.hedgeguard.dao.impl.DmProductionPlanDao;
import com.hedgeguard.dao.impl.DmProductionPlanLineDao;
import com.hedgeguard.dao.impl.DmPurchaseContractDao;
import com.hedgeguard.dao.impl.DmReportPeriodConfigDao;
import com.hedgeguard.dao.impl.DmRoleDao;
import com.hedgeguard.dao.impl.DmSalesContractDao;
import com.hedgeguard.dao.impl.DmSettleAggDiffCandidateDao;
import com.hedgeguard.dao.impl.DmSettleAggregationCheckDao;
import com.hedgeguard.dao.impl.DmSettleCtpBatchDao;
import com.hedgeguard.dao.impl.DmSettleCtpFundDao;
import com.hedgeguard.dao.impl.DmSettleCtpPositionDao;
import com.hedgeguard.dao.impl.DmSettleCtpTradeDao;
import com.hedgeguard.dao.impl.DmSettleCheckSignatureDao;
import com.hedgeguard.dao.impl.DmSettleCheckTaskDao;
import com.hedgeguard.dao.impl.DmSettleCycleDao;
import com.hedgeguard.dao.impl.DmSettleFundDao;
import com.hedgeguard.dao.impl.DmSettlePnlDao;
import com.hedgeguard.dao.impl.DmSettlePositionDao;
import com.hedgeguard.dao.impl.DmSettleReconcileDifferenceDao;
import com.hedgeguard.dao.impl.DmSettleReconcileRecordDao;
import com.hedgeguard.dao.impl.DmSettleReportDao;
import com.hedgeguard.dao.impl.DmSettleStressTestDao;
import com.hedgeguard.dao.impl.DmSpotChangeLogDao;
import com.hedgeguard.dao.impl.DmSpotPriceDao;
import com.hedgeguard.dao.impl.DmSubAccountDao;
import com.hedgeguard.dao.impl.DmSubInstrumentQuotaDao;
import com.hedgeguard.dao.impl.DmSubProductWhitelistDao;
import com.hedgeguard.dao.impl.DmTradeCommandFlowDao;
import com.hedgeguard.dao.impl.DmTradeFillDao;
import com.hedgeguard.dao.impl.DmTradeOrderDao;
import com.hedgeguard.dao.impl.DmTradeOrderReportDao;
import com.hedgeguard.dao.impl.DmTradePlanDao;
import com.hedgeguard.dao.impl.DmTradePositionDao;
import com.hedgeguard.dao.impl.DmTradePreTradeRejectDao;
import com.hedgeguard.dao.impl.DmTradeReasonDao;
import com.hedgeguard.dao.impl.DmUnauthorizedAttemptDao;
import com.hedgeguard.dao.impl.DmUserAccountDao;
import com.hedgeguard.dao.impl.DmUserRoleDao;
import com.hedgeguard.dao.impl.DmUserSubAccountPermDao;
import com.hedgeguard.dao.impl.DmViolationScreenItemDao;
import com.hedgeguard.dao.impl.DmViolationScreenRunDao;
import com.hedgeguard.dao.impl.DmWipRecordDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.JdbcConnectionFactory;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.risk_intraday.AbnormalOrderTracker;
import com.hedgeguard.risk_intraday.AsyncRiskMetricSink;
import com.hedgeguard.risk_intraday.CompositeRiskMetricSink;
import com.hedgeguard.risk_intraday.IntradayEventFeed;
import com.hedgeguard.risk_intraday.IntradayRiskMonitor;
import com.hedgeguard.risk_intraday.MirrorStatusHandler;
import com.hedgeguard.risk_intraday.RiskRoutes;
import com.hedgeguard.risk_intraday.RiskThresholds;
import com.hedgeguard.risk_intraday.TradingSuspendControl;
import com.hedgeguard.risk_intraday.TradingSuspender;
import com.hedgeguard.service.AuditLogService;
import com.hedgeguard.service.AuditReportService;
import com.hedgeguard.service.AuditService;
import com.hedgeguard.service.AuthService;
import com.hedgeguard.service.AcctService;
import com.hedgeguard.service.CheckTaskQueryService;
import com.hedgeguard.service.ConfigService;
import com.hedgeguard.service.EvidenceExportService;
import com.hedgeguard.service.FundService;
import com.hedgeguard.service.MajorEventLossThresholdTrigger;
import com.hedgeguard.service.ParamDispatchService;
import com.hedgeguard.service.PlanService;
import com.hedgeguard.service.LimitUsageThresholds;
import com.hedgeguard.service.RiskAlertService;
import com.hedgeguard.service.RiskRecomputeService;
import com.hedgeguard.service.RiskService;
import com.hedgeguard.service.SpotService;
import com.hedgeguard.service.SettleCheckService;
import com.hedgeguard.service.SettleService;
import com.hedgeguard.service.TradeService;
import com.hedgeguard.service.VarietyService;
import com.hedgeguard.service.ViolationScreenService;
import com.hedgeguard.service.impl.ParamDispatchServiceImpl;
import com.hedgeguard.service.impl.RiskIntradayPersistenceSink;
import com.hedgeguard.service.impl.RiskRecomputeServiceImpl;
import com.hedgeguard.web.handler.PingHandler;
import com.hedgeguard.web.handler.audit.AuditLogRoutes;
import com.hedgeguard.web.handler.audit.AuditReportRoutes;
import com.hedgeguard.web.handler.audit.AuditTraceRoutes;
import com.hedgeguard.web.handler.audit.CheckTaskQueryRoutes;
import com.hedgeguard.web.handler.audit.EvidenceExportRoutes;
import com.hedgeguard.web.handler.audit.ViolationScreenRoutes;
import com.hedgeguard.web.handler.auth.AuthRoutes;
import com.hedgeguard.web.handler.acct.AcctRoutes;
import com.hedgeguard.web.handler.config.ConfigRoutes;
import com.hedgeguard.web.handler.fund.FundRoutes;
import com.hedgeguard.web.handler.plan.PlanRoutes;
import com.hedgeguard.web.handler.product.ProductRoutes;
import com.hedgeguard.web.handler.risk.RiskAlertRoutes;
import com.hedgeguard.web.handler.risk.RiskLimitUsageRoutes;
import com.hedgeguard.web.handler.risk.RiskRuleRoutes;
import com.hedgeguard.web.handler.spot.SpotRoutes;
import com.hedgeguard.web.handler.settle.SettleCheckRoutes;
import com.hedgeguard.web.handler.settle.SettleRoutes;
import com.hedgeguard.web.handler.trade.TradeRoutes;

import java.io.IOException;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Java 业务组件（{@code hg-biz}）启动入口。
 *
 * <p>启动顺序（{@code ARCHITECTURE.md} §3.14）：{@code hg-trading}（C++）先启动，
 * {@code hg-biz} 后启动；本类只负责 L4 接口层的装配与启停，组件桥接（HGCP）在 {@code bridge} 层接入。
 *
 * <p><b>受配置保护装配</b>（{@code D-19}）：数据库相关层（DAO/连接池/服务/业务路由）以
 * {@link Config#databaseConfigured()}（即 {@code CFG-31 hg.db.url} 是否配置）为开关。
 * <ul>
 *   <li>开关为<b>假</b>：仅装配平台级能力（{@code /api/v1/ping} 与 {@code /web/**}），业务路由
 *       <b>不注册</b>——{@code /api/v1/ping} 与静态资源照常可用，<b>进程保持可用、绝不因缺配置而启动失败</b>；</li>
 *   <li>开关为<b>真</b>：装配连接池 → 事务模板 → 五个业务服务 → 五个模块路由注册器。</li>
 * </ul>
 *
 * <p>安全启动自检（启动期失败优于运行期事故）：
 * <ul>
 *   <li>绑定地址非回环 → 拒绝启动（{@code NFR-SEC-3}）；</li>
 *   <li>安装日志脱敏过滤器（{@code NFR-SEC-1}）；</li>
 *   <li>口令零落盘 / 最短生命周期：{@code HG_DB_PASSWORD} 取到 {@code char[]} 后立即交给工厂并清零（{@code NFR-SEC-1}）；</li>
 *   <li>提示运维使用 {@code -XX:-CreateCoredumpOnCrash -XX:ErrorFile=/dev/null} 关闭核心转储（{@code §8.2 ④}）。</li>
 * </ul>
 */
public final class Bootstrap {

    private static final Logger LOG = Logger.getLogger(Bootstrap.class.getName());

    /** 关闭核心转储的必需 JVM 参数（{@code ARCHITECTURE.md} §8.2 {@code NFR-SEC-1} ④）。 */
    public static final String REQUIRED_JVM_FLAGS = "-XX:-CreateCoredumpOnCrash -XX:ErrorFile=/dev/null";

    /**
     * 自动处置的系统操作人标识（{@code T-050} 事中风控自动限制开仓留痕用）。
     *
     * <p>{@code 0} = 系统（无自然人）。自动限制仅作<b>限制开仓</b>（{@code scan_scope=OPEN_ONLY}），
     * 绝不自动下单（{@code R-RISK-13}）；解除限制须风控岗人工处置（{@code T-065}）。
     */
    public static final long SYSTEM_OPERATOR_ID = 0L;

    private Bootstrap() {
    }

    /**
     * 装配（供单测与冒烟脚本复用；不启动 socket）。
     *
     * <p>仅返回分发器；连接池 / 口令等可释放资源由内部 {@link Assembly} 持有——
     * 需要显式释放时改用 {@link #assembleWithResources(Config)} 与 {@link #close(Assembly)}。
     *
     * @param config 配置
     * @return 已装配的分发器
     */
    public static RequestDispatcher assemble(Config config) {
        return assembleWithResources(config).dispatcher();
    }

    /**
     * 装配并返回<b>可释放资源</b>（包内可见：供 {@link #main} 与单测使用）。
     *
     * @param config 配置
     * @return 装配产物（含分发器与可关闭资源）
     */
    static Assembly assembleWithResources(Config config) {
        // ---- 无条件装配（与既有实现一致）：会话、静态托管、路由、ping ----
        SessionStore sessionStore = new SessionStore.InMemory();
        StaticHandler staticHandler = new StaticHandler(config.webRoot());

        Router router = new Router();
        router.register("GET", ApiRoutes.full(ApiRoutes.PING), new PingHandler());
        router.register("HEAD", ApiRoutes.full(ApiRoutes.PING), new PingHandler());

        DataSourcePool pool = null;
        JdbcConnectionFactory factory = null;
        HgcpClient hgcpClient = null;
        ParamDispatchService paramDispatchService = null;
        AuthFilter authFilter;

        // ---- 条件装配：仅当 hg.db.url 已配置时才接入数据库层 ----
        if (config.databaseConfigured()) {
            char[] password = config.databasePassword();
            try {
                factory = new JdbcConnectionFactory(config.databaseDriver(), config.databaseUrl(),
                        config.databaseUser(), password);
            } finally {
                // 口令最短生命周期：工厂已持自有副本，此处立即清零调用方持有的明文（NFR-SEC-1）
                if (password != null) {
                    Arrays.fill(password, '\0');
                }
            }
            pool = new DataSourcePool(factory, poolConfig(config));
            TxTemplate tx = new TxTemplate(pool);
            // HGCP 组件桥：与数据库层同姿态的「受配置保护装配」——凭据（HG_HGCP_AUTH_TOKEN）缺失即不装配。
            // 刻意先于业务服务构造：ParamDispatchService 需要一个可观测其就绪状态的桥实例。
            hgcpClient = assembleHgcpClient(config);
            // M2 参数下发服务（T-042 S3）：唯一生产装配点，接 ParamDispatchDao + TxTemplate + 组件桥。
            // 返回值**必须留存**（记入 Assembly.paramDispatch）：丢弃后「凭据缺失 ⇒ 未装配(null)」与
            // 「已装配(实例)」在生产运行时外观完全相同、只剩一行日志可区分，即门禁判据要求的
            // 「可观测性」。该字段亦供装配级用例断言，且为 M2 接线（T-046 等）的就绪接缝。
            // （参数下发当下无 HTTP 路由，故暂无业务消费者——这不影响本条的可观测性要求。）
            paramDispatchService = buildParamDispatchService(tx, new DmParamDispatchDao(tx), config,
                    hgcpClient, new DmUserAccountDao(tx), new DmRoleDao(tx), new DmUserRoleDao(tx),
                    new DmAuthorizationDao(tx));
            LOG.info(paramDispatchService == null
                    ? "参数下发服务未装配（组件桥不可用）"
                    : "参数下发服务已装配（回执超时取 CFG-28 " + ParamDispatchServiceImpl.KEY_CMD_TIMEOUT_MS
                            + "）");
            AuthService authService = registerBusinessRoutes(router, tx, sessionStore, config,
                    hgcpClient, paramDispatchService);
            // 认证时经 AuthService.touchSession 续期 ⇒ 会话为「空闲 30 min」滑动超时（FR-AUTH-15 验收 2）
            authFilter = new SessionAuthFilter(sessionStore, config.sessionCookieName(),
                    authService::touchSession);
            LOG.info("已装配数据库层：url=" + factory.url() + "（口令不落盘、不入日志）；"
                    + "业务服务 18 个已接线（含参数下发与 T-069 审计四服务），路由总数=" + router.routes().size());
        } else {
            // 未配库：无服务可续期，认证过滤器回退为「只查不续期」（进程保持可用，不因缺配置而启动失败）
            authFilter = new SessionAuthFilter(sessionStore, config.sessionCookieName());
            LOG.info("未装配数据库（" + Config.KEY_DB_URL + " 为空）：仅提供 "
                    + ApiRoutes.full(ApiRoutes.PING) + " 与 " + StaticHandler.URL_PREFIX + "/**；"
                    + "业务接口不可达（受配置保护装配，进程保持可用，不因缺配置而启动失败）");
        }

        RequestDispatcher dispatcher = new RequestDispatcher(router, staticHandler, authFilter);
        return new Assembly(dispatcher, sessionStore, pool, factory, hgcpClient,
                paramDispatchService, router.routes().size());
    }

    /**
     * 释放装配占用的资源（连接池关闭 + 口令清零）；幂等，可重复调用。
     *
     * <p>包内可见：供 {@link #main} 的停机钩子与单测复用。
     *
     * @param assembly 装配产物（{@code null} 忽略）
     */
    static void close(Assembly assembly) {
        if (assembly == null) {
            return;
        }
        if (assembly.pool() != null) {
            assembly.pool().close();
        }
        if (assembly.factory() != null) {
            assembly.factory().clearPassword();
        }
        if (assembly.hgcpClient() != null) {
            // HgcpClient 的全部线程均为守护线程（start()/restartReader() 显式 setDaemon），
            // 故此处 close 只负责置 running=false 并让在途请求以「客户端已关闭」收口，不阻塞停机。
            assembly.hgcpClient().close();
        }
    }



    /**
     * 构造参数下发服务（{@code T-042} S3；{@code ARCHITECTURE.md} §3.8）。
     *
     * <p>回执超时取自 {@code CFG-28 hg.cmd_timeout_ms}（经 {@link ParamDispatchServiceImpl} 的
     * {@code Config} 构造），<b>不硬编码</b>。
     *
     * @param tx     事务模板
     * @param dao    参数下发留痕 DAO（{@code t_param_dispatch}，单一写库入口）
     * @param config 配置
     * @param bridge 组件桥；{@code null}（凭据未配置）时返回 {@code null} ⇒ 参数下发保持不可用，
     *               而非以空桥在首次调用时 NPE
     * @return 参数下发服务；桥不可用时返回 {@code null}
     */
    static ParamDispatchService buildParamDispatchService(TxTemplate tx, ParamDispatchDao dao,
                                                          Config config, TradingBridge bridge) {
        return buildParamDispatchService(tx, dao, config, bridge, null, null, null, null);
    }

    /**
     * 全量装配形态（{@code T-168}）：权限快照派生（{@code dispatchPermissionSnapshot}）的
     * 读库依赖在此接线——四个 DM DAO 与 {@code registerBusinessRoutes} 同源同形态。
     * 单测沿用 4 参旧形态（权限 DAO 传 {@code null} ⇒ 快照下发显式拒绝）。
     */
    static ParamDispatchService buildParamDispatchService(TxTemplate tx, ParamDispatchDao dao,
                                                          Config config, TradingBridge bridge,
                                                          UserAccountDao userAccountDao,
                                                          RoleDao roleDao, UserRoleDao userRoleDao,
                                                          AuthorizationDao authorizationDao) {
        if (bridge == null) {
            LOG.info("未装配参数下发服务（" + Config.ENV_HGCP_AUTH_TOKEN
                    + " 未设置）：HGCP 组件桥不装配，参数下发不可用");
            return null;
        }
        return new ParamDispatchServiceImpl(tx, dao, bridge, config,
                userAccountDao, roleDao, userRoleDao, authorizationDao);
    }

    /**
     * 装配 HGCP 组件桥（{@code T-042} S3）：<b>受配置保护 + 惰性连接</b>。
     *
     * <p>三条处置，对应本类既有的「不因缺配置/外部依赖不可达而启动失败」纪律：
     * <ol>
     *   <li><b>凭据缺失 ⇒ 不装配</b>：{@link Config#ENV_HGCP_AUTH_TOKEN} 未设置时返回 {@code null}
     *       并记 {@code LOG.info} 说明原因。<b>不发明 token 来源、不硬编码、不落盘、不入日志</b>
     *       （{@code NFR-SEC-1} / {@code R-CONFIG-5}；§4.2.6「启动时由系统管理员手工录入，仅内存持有」）。</li>
     *   <li><b>组件不可达 ⇒ 装配成功、连接失败可诊断</b>：{@link HgcpClient#start()} 在
     *       {@code hg-trading} 未启动时必然抛 {@code IOException}。此处<b>捕获并只记一条 info 日志</b>，
     *       绝不外抛——否则本类在无 C++ 组件的环境（含整个单测套件）会装配失败。此后
     *       {@link HgcpClient#isReady()} 恒 {@code false}，参数下发按
     *       {@code ErrorCode.COMPONENT_UNAVAILABLE} <b>显式拒绝</b>（不静默丢弃）。</li>
     *   <li><b>凭据最短生命周期</b>：{@code char[]} 在构造完成后立即清零，与
     *       {@link Config#databasePassword()} 同姿态。{@code HgcpClient} 内部以 {@code String}
     *       持有令牌是既有形态（不可更动，§4.2.6 明确要求填入 {@code MsgHello.auth_token}）。</li>
     * </ol>
     *
     * <p><b>不在本方法内自动重连</b>：连接失败后不另外起线程轮询——{@code HgcpClient} 的重连循环
     * 只在其读循环/心跳线程运行期间生效（{@code onDisconnected()}）。首次即不可达 ⇒ 保持未连接，
     * 由运维重启进程恢复。这与「单例长时间运行」的完整语义差异已单列回报，超出 {@code T-042} 授权。
     *
     * @param config 配置
     * @return 已启动的客户端；凭据未配置时返回 {@code null}
     */
    static HgcpClient assembleHgcpClient(Config config) {
        char[] token = config.hgcpAuthToken();
        if (token == null || token.length == 0) {
            LOG.info("未装配 HGCP 组件桥（" + Config.ENV_HGCP_AUTH_TOKEN + " 未设置）："
                    + "参数下发不可用；凭据须由系统管理员手工录入环境变量，零落盘（NFR-SEC-1）");
            if (token != null) {
                Arrays.fill(token, '\0');
            }
            return null;
        }
        String bind = config.loopbackOnly(Config.KEY_TCP_BIND);
        int port = config.tcpPort();
        String tokenStr;
        try {
            tokenStr = new String(token);
        } finally {
            Arrays.fill(token, '\0');
        }
        HgcpClient client = new HgcpClient("hg-biz", tokenStr, clientBootId(),
                Config.HGCP_TOKEN_VERSION, () -> new TcpHgcpTransport(bind, port));
        try {
            client.start();
            LOG.info("已装配 HGCP 组件桥并建连成功：component_id=hg-biz，通道=" + bind + ":" + port
                    + "（令牌不落盘、不入日志）");
        } catch (IOException e) {
            // 组件不可达不是配置错误：装配照常完成，仅记录可诊断信息（不含任何凭据片段）
            LOG.info("HGCP 组件桥已装配但建连失败（组件未就绪，可诊断）：通道=" + bind + ":" + port
                    + "；原因=" + e.getClass().getSimpleName()
                    + "；影响=组件未就绪，参数下发将被显式拒绝（不静默丢弃）；"
                    + "处置=启动 hg-trading 后重启本进程");
        }
        return client;
    }

    /**
     * 客户端启动标识（{@code MsgHello.client_boot_id}；重连期间须保持稳定）。
     *
     * <p>取「进程标识 + 启动时刻」：同一进程内恒定，进程重启即变化——符合 §4.2.6 对
     * {@code client_boot_id}「重连时保持稳定」的口径。
     *
     * @return 启动标识
     */
    private static String clientBootId() {
        String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        return "hg-biz-" + pid + "-" + System.currentTimeMillis();
    }

    /** 由 {@code CFG-31 hg.db.pool.*} 构造池参数；缺省取 {@link PoolConfig} 默认值。 */
    static PoolConfig poolConfig(Config config) {
        return new PoolConfig(
                config.getInt(Config.KEY_DB_POOL_INITIAL, PoolConfig.DEFAULT_INITIAL_SIZE),
                config.getInt(Config.KEY_DB_POOL_MAX, PoolConfig.DEFAULT_MAX_SIZE),
                config.getInt(Config.KEY_DB_POOL_BORROW_TIMEOUT_MS,
                        PoolConfig.DEFAULT_BORROW_TIMEOUT_MILLIS),
                PoolConfig.DEFAULT_VALIDATION_TIMEOUT_SECONDS);
    }

    /**
     * 装配五个业务服务并注册其模块路由。
     *
     * <p>抽出的公共 DAO（{@code DmProductSpotCategoryDao} / {@code DmExposureDao} /
     * {@code DmProductContractDao}）在多个服务间<b>复用同一实例</b>（DAO 无状态，仅持 {@link TxTemplate}）。
     *
     * @return 已装配的 {@link AuthService}（供认证过滤器注入会话续期器）
     */
    private static AuthService registerBusinessRoutes(Router router, TxTemplate tx,
                                                      SessionStore sessionStore, Config config,
                                                      TradingBridge bridge,
                                                      ParamDispatchService paramDispatchService) {
        DmProductSpotCategoryDao productSpotCategoryDao = new DmProductSpotCategoryDao(tx);
        DmExposureDao exposureDao = new DmExposureDao(tx);
        DmProductContractDao productContractDao = new DmProductContractDao(tx);
        DmProductDao productDao = new DmProductDao(tx);
        DmHedgePlanExposureDao hedgePlanExposureDao = new DmHedgePlanExposureDao(tx);

        AuthService authService = new AuthService(tx,
                new DmUserAccountDao(tx), new DmRoleDao(tx), new DmUserRoleDao(tx),
                new DmPositionConflictDao(tx), new DmLoginSessionDao(tx), new DmLoginLogDao(tx),
                new DmPasswordHistoryDao(tx), new DmUserSubAccountPermDao(tx),
                new DmUnauthorizedAttemptDao(tx), sessionStore,
                new DmAuthorizationDao(tx), new DmPermissionChangeLogDao(tx),
                new DmPositionGroupDao(tx), config);

        ConfigService configService = new ConfigService(tx,
                new DmSubAccountDao(tx), new DmConfigItemDao(tx), new DmConfigChangeLogDao(tx),
                new DmDictItemDao(tx), new DmAccountSubjectDao(tx), new DmReportPeriodConfigDao(tx),
                new DmCtpFrontConfigDao(tx), new DmParamDispatchDao(tx));

        VarietyService varietyService = new VarietyService(tx,
                productDao, new DmProductUnitDao(tx), productContractDao,
                productSpotCategoryDao, new DmSubProductWhitelistDao(tx),
                new DmProductQuotaDao(tx), new DmSubInstrumentQuotaDao(tx),
                new DmProductChangeLogDao(tx), authService);

        SpotService spotService = new SpotService(tx,
                new DmPurchaseContractDao(tx), new DmSalesContractDao(tx), productSpotCategoryDao,
                new DmWipRecordDao(tx), new DmInventoryLedgerDao(tx), new DmSpotPriceDao(tx),
                exposureDao, new DmExposureDetailDao(tx), new DmSpotChangeLogDao(tx),
                new DmProductionPlanDao(tx), new DmProductionPlanLineDao(tx), new DmSubAccountDao(tx),
                productDao, authService, hedgePlanExposureDao);

        PlanService planService = new PlanService(tx,
                new DmHedgePlanDao(tx), new DmHedgePlanExposureDao(tx), new DmHedgeRatioCalcDao(tx),
                new DmCustomHedgeRuleDao(tx), new DmHedgeDocDao(tx), new DmPlanApprovalDao(tx),
                new DmPlanStateLogDao(tx), new DmTradePlanDao(tx), exposureDao, productContractDao,
                authService,
                new DmAuthorizationDao(tx), new DmSubProductWhitelistDao(tx), new DmExposureDetailDao(tx),
                new DmPurchaseContractDao(tx), new DmSalesContractDao(tx), authService::hasTradingRole);

        AuthRoutes.register(router, authService, sessionStore);
        // T-135：子账户停用端点的四条件判定端口。M1 未交付 FUND/TRADE/SETTLE 表 ⇒ 以
        // 「不可用」实现诚实失败（10002），M2 以真实 DAO 替换（CtpTestHandler 同姿态）。
        ConfigRoutes.register(router, configService,
                new com.hedgeguard.service.impl.UnavailableSubAccountDisablePrecheck(
                        "FUND/TRADE/SETTLE（M1 未交付）"));
        ProductRoutes.register(router, varietyService);
        SpotRoutes.register(router, spotService);
        PlanRoutes.register(router, planService);

        // M2 交易执行服务（T-046）：提交指令不落库（零写语句），事件流回传后由 Java 统一落库。
        // 复用共享 DAO 实例（DAO 无状态，仅持 TxTemplate）。
        TradeService tradeService = new TradeService(tx, bridge, authService,
                new DmTradePlanDao(tx), new DmHedgePlanDao(tx), exposureDao,
                new DmTradeOrderDao(tx), new DmTradeReasonDao(tx),
                new DmTradeOrderReportDao(tx), new DmTradeFillDao(tx),
                new DmTradePositionDao(tx), new DmTradePreTradeRejectDao(tx),
                new DmTradeCommandFlowDao(tx));
        TradeRoutes.register(router, tradeService);

        // T-188 重大事项亏损阈值自动触发器（CFG-26 = audit.major_event.loss_threshold，惰性读取）：
        // 轨 1（EOD 已实现/记账级）挂结算链（下方 SettleService），轨 2（盘中镜像/提醒级）
        // 经 CompositeRiskMetricSink 挂事中风控异步下游（R-RISK-10：事件消费线程零 SQL）。
        MajorEventLossThresholdTrigger lossTrigger = new MajorEventLossThresholdTrigger(
                new DmMajorEventDao(tx), new DmSettlePnlDao(tx),
                () -> {
                    com.hedgeguard.dao.entity.ConfigItem ci =
                            configService.getConfig(MajorEventLossThresholdTrigger.CONFIG_KEY);
                    return ci == null ? null : ci.configValue;
                },
                SYSTEM_OPERATOR_ID);

        // M2 结算服务（T-047）：CTP 日终入库 → 子账户拆分/独立结算 → 三类核对 → 加总对账 → 关账门禁。
        // 系统内按子账户数据以「服务方法 + 入参接缝」承接（生产事件接线归 T-172）。
        SettleService settleService = new SettleService(tx, authService,
                new DmSettleCtpBatchDao(tx), new DmSettleCtpPositionDao(tx),
                new DmSettleCtpTradeDao(tx), new DmSettleCtpFundDao(tx),
                new DmSettleCycleDao(tx), new DmSettlePositionDao(tx),
                new DmSettleFundDao(tx), new DmSettlePnlDao(tx),
                new DmSettleReconcileRecordDao(tx), new DmSettleReconcileDifferenceDao(tx),
                new DmSettleAggregationCheckDao(tx), new DmSettleAggDiffCandidateDao(tx),
                new DmSettleReportDao(tx), new DmSettleStressTestDao(tx));
        settleService.setLossThresholdTrigger(lossTrigger);
        SettleRoutes.register(router, settleService);

        // M2 核对任务与电子签署（T-048）：核对任务 + 三级签署 + 不可代签（DC-022）+ 逾期巡检 +
        // 合规证据导出 + 审计只读查询。快照接缝未装配（snapshotProvider=null）⇒ 快照哈希以请求携带值为准；
        // 六段快照的 SHA-256 生产计算待跨模块读面就绪（ARCHITECTURE.md §6.14，据实披露）。
        SettleCheckService settleCheckService = new SettleCheckService(tx, authService,
                authService::roleCodesOf, authService::hasTradingRole,
                key -> {
                    com.hedgeguard.dao.entity.ConfigItem ci = configService.getConfig(key);
                    return ci == null ? null : ci.configValue;
                },
                null, new DmSettleCheckTaskDao(tx), new DmSettleCheckSignatureDao(tx),
                new DmUnauthorizedAttemptDao(tx));
        SettleCheckRoutes.register(router, settleCheckService);

        // M2 资金管理服务（T-049）：台账/出入金/双人复核/划拨/额度/对账。
        // 回执处理与 CTP 下发以「服务方法 + 接缝」承接（bridge 未承载 CMD_FUND_TRANSFER(0x011D)/
        // CMD_QUERY_FUND_RESULT(0x011E) 与 EVT_FUND_RECEIPT(0x020B) 监听载荷，缺口归 T-172 扩载；
        // bridge/** 未改动）。CFG-25 额度告警阈值经 ConfigService 读取（缺省 0.80,0.95,1.00）。
        FundService fundService = new FundService(tx, authService, authService::hasTradingRole,
                key -> {
                    com.hedgeguard.dao.entity.ConfigItem ci = configService.getConfig(key);
                    return ci == null ? null : ci.configValue;
                },
                new DmFundLedgerDao(tx), new DmFundFlowDao(tx),
                new DmDepositWithdrawApplyDao(tx), new DmTransferApplyDao(tx),
                new DmCapitalReceiptDao(tx), new DmFundReviewRecordDao(tx),
                new DmFundQuotaDao(tx), new DmFundQuotaAlertDao(tx), new DmFundReconcileDao(tx));
        FundRoutes.register(router, fundService);

        // M4 套期会计服务（T-077，ACCT 首批）：套期关系生成（CAS 24 三要素门禁 R-ACCT-1）+
        // 有效性评估（三项定性观察点 R-ACCT-2/Q-7；量化监测 CFG-07b 仅提示不判定）。
        // CFG-07/07b 经 ConfigService 惰性读取（data-init.sql:318/:322 已播种；缺种子容错缺省）。
        AcctService acctService = new AcctService(tx, authService,
                key -> {
                    com.hedgeguard.dao.entity.ConfigItem ci = configService.getConfig(key);
                    return ci == null ? null : ci.configValue;
                },
                new DmHedgePlanDao(tx), new DmHedgeDocDao(tx),
                new DmHedgeRelationDao(tx), new DmEffectivenessAssessDao(tx));
        AcctRoutes.register(router, acctService);
        LOG.info("已装配套期会计首批面（T-077）：GET|POST /api/v1/acct/hedge-relation、"
                + "GET|POST /api/v1/acct/effectiveness；三要素门禁=R-ACCT-1；"
                + "有效性=三项定性观察点（CFG-07），量化监测（CFG-07b）仅提示不判定");

        // M3 风控阈值配置与下发闭环（T-063）：t_risk_threshold 配置 + 合规下限 + CMD_UPDATE_RISK_THRESHOLD 下发。
        // 复用既有 ParamDispatchService 下发通道（T-011）与 t_config_change_log 留痕（双人复核）。
        RiskService riskService = new RiskService(tx, new DmRiskThresholdDao(tx),
                paramDispatchService, new DmConfigChangeLogDao(tx));
        RiskRuleRoutes.register(router, riskService);
        LOG.info("已装配风控阈值配置面：GET|POST /api/v1/risk/rule；下发服务="
                + (paramDispatchService == null ? "未装配（写面拒绝）" : "已装配"));

        // M3 红线复算与限额使用统计（T-066）：Java 侧对 C++ 事前判定（R-RISK-4/5/6）的镜像复算 +
        // 统计落 t_limit_usage_stat（SCALE_REDLINE / POSITION_LIMIT）。判定权威仍在 C++（本服务不参与下单前阻断）。
        RiskRecomputeService riskRecomputeService = new RiskRecomputeServiceImpl(tx,
                new DmAnnualScaleDao(tx), exposureDao, new DmTradePositionDao(tx),
                productContractDao, new DmProductUnitDao(tx), new DmHedgePlanDao(tx),
                new DmPurchaseContractDao(tx), new DmSalesContractDao(tx),
                new DmLimitUsageStatDao(tx), new DmRiskThresholdDao(tx), authService,
                LimitUsageThresholds.from(config));
        RiskLimitUsageRoutes.register(router, riskRecomputeService);
        LOG.info("已装配红线复算与限额使用统计面：GET|POST /api/v1/risk/limit-usage；"
                + "复算口径=C++ risk_engine（R-RISK-4/5/6）；期限统计无承载表（经返回项展示）");

        IntradayRiskMonitor monitor = registerRiskIntradayRoute(router, config, bridge,
                paramDispatchService, authService, tx, lossTrigger);
        // M3 风控告警查询与处置面（T-065）：GET /risk/alert（FR-RISK-19）+ POST /risk/alert/{id}/dispose
        // （FR-RISK-15/16）。处置的「限制/解除新开仓」经既有 ParamDispatchServiceImpl 下发
        // CMD_SET_TRADING_SUSPEND(0x0118)（scan_scope=OPEN_ONLY，R-RISK-5）；桥不可用时端口为 null（拒绝下发）。
        TradingSuspendControl suspendControl = paramDispatchService instanceof ParamDispatchServiceImpl
                ? new ParamDispatchSuspendControl((ParamDispatchServiceImpl) paramDispatchService)
                : null;
        RiskAlertService riskAlertService = new RiskAlertService(tx,
                new DmRiskAlertDao(tx), new DmRiskDisposalDao(tx), authService, suspendControl);
        RiskAlertRoutes.register(router, riskAlertService);
        LOG.info("已装配风控告警查询与处置面：GET /api/v1/risk/alert、POST /api/v1/risk/alert/{id}/dispose；"
                + "限制/解除下发=" + (suspendControl == null ? "未装配（桥不可用）" : "已装配（0x0118 OPEN_ONLY）"));
        // T-172 事件面接线：生产装配（bridge = HgcpClient）时注册事件消费监听接缝 ——
        // EVT_TRADE_FILL → recordTrade（异常下单行为）+ 每事件 → evaluate()（评估泵，验收 2）。
        // 非 HgcpClient（测试桩）或缺省不装配时跳过；仅推进会水位的接线前行为不变（nullable seam）。
        if (bridge instanceof HgcpClient && monitor != null) {
            // 单槽接缝：组合「事中风控投喂（IntradayEventFeed）」与「交易执行落库（TradeService.HgcpEventSink）」，两者均被回调。
            // ProductIdResolver 接 Q-5 真值（order_id→product_id 经提交上下文），替代 IntradayEventFeed 恒 0 缺省。
            IntradayEventFeed feed = new IntradayEventFeed(monitor, tradeService::productIdForOrder);
            TradeService.HgcpEventSink sink = tradeService.new HgcpEventSink();
            ((HgcpClient) bridge).setEventListener(new CompositeHgcpEventListener(feed, sink));
            LOG.info("已接线 HGCP 事件消费（T-172）：EVT_TRADE_FILL→recordTrade + 每事件→evaluate()"
                    + " + 六事件富化落库（Q-5 品种级解析=提交上下文真值）");
        }

        // T-067 审计全链路追溯与操作留痕（AUDIT 模块首批接入）：正向/反向/完整性 + 在途时窗。
        // 会计凭证 t_voucher 属 ACCT（T-076~T-080，TODO）⇒ VoucherLookup 接缝暂不装配（null），
        // 该段判 UNKNOWN（诚实披露，R-AUDIT-2「数据缺失不得误标完整」）；T-079 落地后装入实现即闭合。
        // 在途时窗上限 audit.chain.inflight_max_cycles（CFG-17c，1–3，缺省 1）**惰性**读取：
        // 装配期不触库（生产装配在假驱动下亦须可完成），调用时经 ConfigService 读取并夹取。
        AuditService auditService = new AuditService(exposureDao, hedgePlanExposureDao,
                new DmHedgePlanDao(tx), new DmPlanApprovalDao(tx), new DmTradePlanDao(tx),
                new DmTradeOrderDao(tx), new DmTradeOrderReportDao(tx), new DmTradeFillDao(tx),
                new DmTradeReasonDao(tx), new DmSettleCycleDao(tx), new DmOperationLogDao(tx),
                authService, null,
                () -> {
                    com.hedgeguard.dao.entity.ConfigItem ci =
                            configService.getConfig("audit.chain.inflight_max_cycles");
                    if (ci == null || ci.configValue == null) {
                        return 1;
                    }
                    try {
                        return Integer.parseInt(ci.configValue.trim());
                    } catch (NumberFormatException e) {
                        LOG.warning("audit.chain.inflight_max_cycles 取值非法，回退缺省 1："
                                + ci.configValue);
                        return 1;
                    }
                },
                AuditService.endOfTradeDay());
        AuditTraceRoutes.register(router, auditService);
        LOG.info("已装配审计追溯面：GET|POST /api/v1/audit/trace/{forward,backward,integrity} 与 "
                + "/api/v1/audit/operation-log；会计凭证接缝=未装配（ACCT T-076~T-080 TODO）");

        // T-068 违规事项筛查（FR-AUDIT-4/Q-13）：规则四维复用 RISK 规则编号（事前拒绝记录
        // t_pre_trade_reject）、频繁开平仓复用 Q-5 阈值；理由/关联两维由 t_trade_reason 并列派生。
        // 落 t_violation_screen_run/item（T-062）。可见范围经 authService（软隔离，R-AUDIT-9）。
        ViolationScreenService violationScreenService = new ViolationScreenService(
                new DmTradePreTradeRejectDao(tx), new DmTradeOrderDao(tx), new DmTradeReasonDao(tx),
                new DmViolationScreenRunDao(tx), new DmViolationScreenItemDao(tx), authService, tx);
        ViolationScreenRoutes.register(router, violationScreenService);
        LOG.info("已装配违规事项筛查面：GET|POST /api/v1/audit/violation-screen");

        // T-069 审计日志/报表/证据链导出接线（TE-T069-001 修复）：三组 Routes 注册进生产装配，
        // 五个 service（含 DmAuditReportDataProvider）在此构造并注入（此前的「已声明未注册」缺口就此闭合）。
        // 防篡改告警端口 TamperAlarm 暂接 NOOP：运维告警 sink 未交付（与 T-065/T-177 告警 sink 同姿态），
        // 校验结论本身仍落库并可查（FR-AUDIT-10 验收 3），sink 交付后替换即通——据实披露。
        AuditLogService auditLogService = new AuditLogService(new DmAuditLogDao(tx),
                new DmAuditLogChecksumDao(tx), authService, null, tx);
        AuditLogRoutes.register(router, auditLogService);
        CheckTaskQueryService checkTaskQueryService = new CheckTaskQueryService(
                new DmSettleCheckTaskDao(tx), new DmSettleCheckSignatureDao(tx), authService);
        // T-194（te-verify-t071 O-1 承接）：核对任务与签署记录查询接线 —— 消费既有
        // CheckTaskQueryService.query/overdue（服务本体零改动），补齐 FR-AUDIT-13 验收 1/2/5 的
        // REST 端点缺口（此前 web/ 零 handler 调用）。只读（R-AUDIT-6），仅 GET。
        CheckTaskQueryRoutes.register(router, checkTaskQueryService);
        AuditReportService auditReportService = new AuditReportService(new DmAuditReportDao(tx),
                new DmMajorEventDao(tx), new DmOperationLogDao(tx), auditLogService,
                checkTaskQueryService, new DmPostRiskReportDao(tx),
                new com.hedgeguard.service.impl.DmAuditReportDataProvider(
                        new DmHedgePlanDao(tx), new DmSettlePositionDao(tx),
                        new DmSettlePnlDao(tx), productDao),
                authService, tx);
        AuditReportRoutes.register(router, auditReportService);
        EvidenceExportService evidenceExportService = new EvidenceExportService(auditService,
                auditLogService, new DmEvidenceExportDao(tx), new DmTradeReasonDao(tx),
                null, authService, tx);
        EvidenceExportRoutes.register(router, evidenceExportService);
        LOG.info("已装配审计日志/报表/证据链导出面：GET|POST /api/v1/audit/log、/api/v1/audit/log/verify、"
                + "/api/v1/audit/report/{quarterly,annual,major-event}、/api/v1/audit/evidence/export；"
                + "防篡改告警端口=NOOP（运维告警 sink 未交付，据实披露）；"
                + "导出列表子账户隔离=fail-closed（R-AUDIT-9/NFR-SEC-7）");
        LOG.info("已装配核对任务查询面（T-194）：GET /api/v1/audit/check-tasks、"
                + "GET /api/v1/audit/check-tasks/overdue（FR-AUDIT-13 验收 1/2/5；只读 R-AUDIT-6；"
                + "子账户可见性经 SubAccountVisibility 软隔离 R-AUDIT-9）");
        return authService;
    }

    /**
     * 装配事中风控模块（{@code T-050}，{@code ARCHITECTURE.md} §3.11 J5）并注册
     * {@code GET /api/v1/risk/mirror-status}。
     *
     * <p><b>最小接线</b>：本方法只构造 {@code risk_intraday} 引擎与读取服务、注册 1 条已声明路径；
     * 不改任何既有模块的注册序与路径。
     *
     * <p><b>限制开仓适配</b>：把既有 {@code ParamDispatchService} 的
     * {@code suspendOpenOnly}（{@code CMD_SET_TRADING_SUSPEND} + {@code scan_scope=OPEN_ONLY}，
     * {@code R-RISK-5}）适配为 {@code risk_intraday} 的端口，使该模块不直接依赖协议细节；
     * 未装配参数下发服务（桥不可用）时端口为 {@code null}，自动限制禁用（只产告警）。
     *
     * @param router                路由
     * @param config                配置（阈值经 {@code R-RISK-14} 统一读取，不硬编码）
     * @param bridge                组件桥（{@code null} 视为空快照）
     * @param paramDispatchService  参数下发服务（{@code null} 时不自动限制）
     * @param authService           认证服务（同时实现子账户可见性，供读取面隔离）
     */
    private static IntradayRiskMonitor registerRiskIntradayRoute(Router router, Config config,
                                                  TradingBridge bridge,
                                                  ParamDispatchService paramDispatchService,
                                                  AuthService authService, TxTemplate tx,
                                                  MajorEventLossThresholdTrigger lossTrigger) {
        TradingSuspender suspender = paramDispatchService instanceof ParamDispatchServiceImpl
                ? (subAccountId, reason, operatorId) ->
                        ((ParamDispatchServiceImpl) paramDispatchService)
                                .suspendOpenOnly(subAccountId, reason, operatorId)
                : null;
        RiskThresholds thresholds = RiskThresholds.from(config);
        AbnormalOrderTracker tracker = new AbnormalOrderTracker();
        // T-065：真实落库 sink（t_risk_alert / t_risk_metric_snapshot / t_mirror_watermark /
        // t_abnormal_order_stat），经 AsyncRiskMetricSink 异步下游落库 —— 事件消费线程零 DB（R-RISK-10）。
        // T-188：组合 sink 再并联重大事项亏损阈值触发器（盘中镜像轨，提醒级；单下游失败隔离）。
        // 实时推送端口 notifier = null：WsServer 生产装配属 T-040（BLOCKED），接入后即通（补拉侧 REST 本单已实现）。
        RiskIntradayPersistenceSink persistence = new RiskIntradayPersistenceSink(tx,
                new DmRiskAlertDao(tx), new DmRiskMetricSnapshotDao(tx), new DmMirrorWatermarkDao(tx),
                new DmAbnormalOrderStatDao(tx), null,
                () -> tracker.snapshot(thresholds, RiskIntradayPersistenceSink.INTRADAY_WINDOW_MINUTES,
                        RiskIntradayPersistenceSink.ROLLING_WINDOW_MINUTES),
                RiskIntradayPersistenceSink.DEFAULT_COMPONENT_ID, SYSTEM_OPERATOR_ID,
                thresholds.mirrorLagMillis);
        IntradayRiskMonitor monitor = new IntradayRiskMonitor(bridge, thresholds, tracker,
                new AsyncRiskMetricSink(new CompositeRiskMetricSink(persistence, lossTrigger)),
                suspender, SYSTEM_OPERATOR_ID);
        RiskRoutes.register(router, monitor, authService);
        LOG.info("已装配事中风控模块（risk_intraday）：GET " + MirrorStatusHandler.PATH
                + "；自动限制开仓=" + thresholds.autoSuspendEnabled
                + "；限制端口=" + (suspender == null ? "未装配（仅告警）" : "已装配（OPEN_ONLY）")
                + "；落库 sink=已装配（异步下游）；实时推送端口=未装配（T-040 承接）");
        return monitor;
    }

    /**
     * {@code T-172} 事件面装配：{@link HgcpClient} 的监听接缝为<b>单槽</b>（{@code setEventListener}
     * 覆盖式），故以本组合器把多个中立消费者串联：每帧回调<b>依次</b>分发给全部消费者；单个消费者抛
     * {@link RuntimeException} 只记日志、<b>不影响其余消费者</b>，也不中断事件消费（{@code NFR-REL-5}；
     * {@link HgcpClient} 另有一层兜底）。
     */
    static final class CompositeHgcpEventListener implements HgcpEventListener {

        private final java.util.List<HgcpEventListener> delegates;

        CompositeHgcpEventListener(HgcpEventListener... delegates) {
            this.delegates = Arrays.asList(delegates);
        }

        private void each(java.util.function.Consumer<HgcpEventListener> callback) {
            for (HgcpEventListener delegate : delegates) {
                try {
                    callback.accept(delegate);
                } catch (RuntimeException ex) {
                    LOG.log(Level.WARNING, "HGCP 组合监听器消费异常（已忽略，不中断其余消费者）："
                            + ex.getMessage(), ex);
                }
            }
        }

        @Override
        public void onEventConsumed(int eventType, long seq) {
            each(l -> l.onEventConsumed(eventType, seq));
        }

        @Override
        public void onOrderAccepted(HgcpEventListener.OrderAccepted e) {
            each(l -> l.onOrderAccepted(e));
        }

        @Override
        public void onOrderReport(HgcpEventListener.OrderReport e) {
            each(l -> l.onOrderReport(e));
        }

        @Override
        public void onTradeFill(HgcpEventListener.TradeFill e) {
            each(l -> l.onTradeFill(e));
        }

        @Override
        public void onPositionChange(HgcpEventListener.PositionChange e) {
            each(l -> l.onPositionChange(e));
        }

        @Override
        public void onPreTradeReject(HgcpEventListener.PreTradeReject e) {
            each(l -> l.onPreTradeReject(e));
        }

        @Override
        public void onCommandFlow(HgcpEventListener.CommandFlow e) {
            each(l -> l.onCommandFlow(e));
        }

        @Override
        public void onOrderCancelResult(HgcpEventListener.OrderCancelResult e) {
            each(l -> l.onOrderCancelResult(e));
        }

        @Override
        public void onOrderExpired(HgcpEventListener.OrderExpired e) {
            each(l -> l.onOrderExpired(e));
        }
    }

    /**
     * 主入口。
     *
     * @param args 命令行参数（当前不使用；配置一律经 {@code -D} 与环境变量注入）
     */
    public static void main(String[] args) {
        SensitiveLog.installRoot();
        if (!coredumpDisabled()) {
            LOG.warning("检测到未设置核心转储关闭参数，存在敏感信息落盘风险（NFR-SEC-1 ④）；"
                    + "请以 JVM 参数启动：" + REQUIRED_JVM_FLAGS);
        }
        Config config = Config.bootstrap();
        Assembly assembly = assembleWithResources(config);
        HttpServerLauncher launcher = new HttpServerLauncher(config, assembly.dispatcher());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("收到停机信号，正在停止 HTTP 服务");
            launcher.stop();
            close(assembly);
            LOG.info("已释放数据库资源（连接池已关闭、口令副本已清零）");
        }, "hg-shutdown"));
        launcher.start();
        LOG.info("hg-biz 就绪：" + Clocks.now());
    }

    /**
     * 核心转储是否已按 {@code NFR-SEC-1} ④ 关闭。
     *
     * <p>判据：启动脚本在确认已附加 {@link #REQUIRED_JVM_FLAGS} 后设置 {@code -Dhg.coredump.off=true}。
     * 由脚本而非代码设置，是因为 JVM 参数无法在进程内生效。
     *
     * @return 已关闭返回 {@code true}
     */
    private static boolean coredumpDisabled() {
        return Boolean.parseBoolean(System.getProperty("hg.coredump.off", "false"));
    }

    /**
     * 装配产物：分发器 + 会话存储 + 可释放的数据库资源。
     *
     * <p>包内可见，供 {@link #main} 与单测取证。数据库未装配时 {@link #pool()} / {@link #factory()}
     * 返回 {@code null}。
     */
    static final class Assembly {

        private final RequestDispatcher dispatcher;
        private final SessionStore sessionStore;
        private final DataSourcePool pool;
        private final JdbcConnectionFactory factory;
        private final HgcpClient hgcpClient;
        /** 参数下发服务（M2，{@code T-042} S3）；凭据缺失/桥未装配时为 {@code null}。 */
        private final ParamDispatchService paramDispatchService;
        private final int routeCount;

        Assembly(RequestDispatcher dispatcher, SessionStore sessionStore, DataSourcePool pool,
                 JdbcConnectionFactory factory, HgcpClient hgcpClient,
                 ParamDispatchService paramDispatchService, int routeCount) {
            this.dispatcher = dispatcher;
            this.sessionStore = sessionStore;
            this.pool = pool;
            this.factory = factory;
            this.hgcpClient = hgcpClient;
            this.paramDispatchService = paramDispatchService;
            this.routeCount = routeCount;
        }

        RequestDispatcher dispatcher() {
            return dispatcher;
        }

        SessionStore sessionStore() {
            return sessionStore;
        }

        DataSourcePool pool() {
            return pool;
        }

        JdbcConnectionFactory factory() {
            return factory;
        }

        /** HGCP 组件桥（未装配时为 {@code null}）；供 {@link #close(Assembly)} 释放。 */
        HgcpClient hgcpClient() {
            return hgcpClient;
        }

        /**
         * 参数下发服务：装配成功返回实例，凭据缺失（桥未装配）返回 {@code null}。
         *
         * <p>使「是否真的装上」在生产运行时**可观测**（不再仅靠日志区分），
         * 并为 M2 的下发接线（{@code T-046} 等）预置就绪接缝。
         *
         * @return 已装配实例；未装配时为 {@code null}
         */
        ParamDispatchService paramDispatchService() {
            return paramDispatchService;
        }

        /** 已注册路由条数（含平台级 ping 的 GET/HEAD）。 */
        int routeCount() {
            return routeCount;
        }

        /** 是否已装配数据库层。 */
        boolean databaseAssembled() {
            return pool != null;
        }
    }

    /**
     * {@code T-065} 处置下发适配：把既有 {@code ParamDispatchServiceImpl} 的
     * 挂起 / 解冻能力适配为 {@link TradingSuspendControl}，使 {@code service} 不直接依赖其实现类。
     */
    static final class ParamDispatchSuspendControl implements TradingSuspendControl {

        private final ParamDispatchServiceImpl delegate;

        ParamDispatchSuspendControl(ParamDispatchServiceImpl delegate) {
            this.delegate = delegate;
        }

        @Override
        public DispatchOutcome suspendOpen(int subAccountId, String reason, long operatorId) {
            return toOutcome(delegate.suspendOpenOnly(subAccountId, reason, operatorId));
        }

        @Override
        public DispatchOutcome releaseOpen(int subAccountId, String reason, long operatorId) {
            return toOutcome(delegate.releaseOpenOnly(subAccountId, reason, operatorId));
        }

        private static DispatchOutcome toOutcome(com.hedgeguard.bridge.AckResult ack) {
            if (ack == null) {
                return new DispatchOutcome(false, false, null, "FAIL", "空回执");
            }
            return new DispatchOutcome(true, ack.acknowledged, ack.paramVersion,
                    ack.acknowledged ? "SUCCESS" : "FAIL", ack.message);
        }
    }

    static {
        Logger.getLogger("").setLevel(Level.INFO);
    }
}
