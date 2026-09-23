package com.hedgeguard.web;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.common.SensitiveLog;
import com.hedgeguard.dao.impl.DmAccountSubjectDao;
import com.hedgeguard.dao.impl.DmConfigChangeLogDao;
import com.hedgeguard.dao.impl.DmConfigItemDao;
import com.hedgeguard.dao.impl.DmCtpFrontConfigDao;
import com.hedgeguard.dao.impl.DmCustomHedgeRuleDao;
import com.hedgeguard.dao.impl.DmDictItemDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmAuthorizationDao;
import com.hedgeguard.dao.impl.DmExposureDetailDao;
import com.hedgeguard.dao.impl.DmHedgeDocDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmHedgePlanExposureDao;
import com.hedgeguard.dao.impl.DmHedgeRatioCalcDao;
import com.hedgeguard.dao.impl.DmInventoryLedgerDao;
import com.hedgeguard.dao.impl.DmLoginLogDao;
import com.hedgeguard.dao.impl.DmLoginSessionDao;
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
import com.hedgeguard.dao.impl.DmSpotChangeLogDao;
import com.hedgeguard.dao.impl.DmSpotPriceDao;
import com.hedgeguard.dao.impl.DmSubAccountDao;
import com.hedgeguard.dao.impl.DmSubInstrumentQuotaDao;
import com.hedgeguard.dao.impl.DmSubProductWhitelistDao;
import com.hedgeguard.dao.impl.DmTradePlanDao;
import com.hedgeguard.dao.impl.DmUnauthorizedAttemptDao;
import com.hedgeguard.dao.impl.DmUserAccountDao;
import com.hedgeguard.dao.impl.DmUserRoleDao;
import com.hedgeguard.dao.impl.DmUserSubAccountPermDao;
import com.hedgeguard.dao.impl.DmWipRecordDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.JdbcConnectionFactory;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.AuthService;
import com.hedgeguard.service.ConfigService;
import com.hedgeguard.service.PlanService;
import com.hedgeguard.service.SpotService;
import com.hedgeguard.service.VarietyService;
import com.hedgeguard.web.handler.PingHandler;
import com.hedgeguard.web.handler.auth.AuthRoutes;
import com.hedgeguard.web.handler.config.ConfigRoutes;
import com.hedgeguard.web.handler.plan.PlanRoutes;
import com.hedgeguard.web.handler.product.ProductRoutes;
import com.hedgeguard.web.handler.spot.SpotRoutes;

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
            AuthService authService = registerBusinessRoutes(router, tx, sessionStore, config);
            // 认证时经 AuthService.touchSession 续期 ⇒ 会话为「空闲 30 min」滑动超时（FR-AUTH-15 验收 2）
            authFilter = new SessionAuthFilter(sessionStore, config.sessionCookieName(),
                    authService::touchSession);
            LOG.info("已装配数据库层：url=" + factory.url() + "（口令不落盘、不入日志）；"
                    + "业务服务 5 个已接线，路由总数=" + router.routes().size());
        } else {
            // 未配库：无服务可续期，认证过滤器回退为「只查不续期」（进程保持可用，不因缺配置而启动失败）
            authFilter = new SessionAuthFilter(sessionStore, config.sessionCookieName());
            LOG.info("未装配数据库（" + Config.KEY_DB_URL + " 为空）：仅提供 "
                    + ApiRoutes.full(ApiRoutes.PING) + " 与 " + StaticHandler.URL_PREFIX + "/**；"
                    + "业务接口不可达（受配置保护装配，进程保持可用，不因缺配置而启动失败）");
        }

        RequestDispatcher dispatcher = new RequestDispatcher(router, staticHandler, authFilter);
        return new Assembly(dispatcher, sessionStore, pool, factory, router.routes().size());
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
                                                      SessionStore sessionStore, Config config) {
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
        return authService;
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
        private final int routeCount;

        Assembly(RequestDispatcher dispatcher, SessionStore sessionStore, DataSourcePool pool,
                 JdbcConnectionFactory factory, int routeCount) {
            this.dispatcher = dispatcher;
            this.sessionStore = sessionStore;
            this.pool = pool;
            this.factory = factory;
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

        /** 已注册路由条数（含平台级 ping 的 GET/HEAD）。 */
        int routeCount() {
            return routeCount;
        }

        /** 是否已装配数据库层。 */
        boolean databaseAssembled() {
            return pool != null;
        }
    }

    static {
        Logger.getLogger("").setLevel(Level.INFO);
    }
}
