package com.hedgeguard.web.handler.config;

import com.hedgeguard.service.ConfigService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.Router;

/**
 * {@code CONFIG} 模块路由注册（{@code #java2} 产出；路径逐字取自 {@code ApiRoutes.byModule().get("CONFIG")}）。
 *
 * <p>归属声明：本类与 {@code web/handler/config/**} 由 {@code backend-engineer#java2} 新增；
 * {@code web/} 的框架类保持 {@code #java} 写入域不变（本类只调用 {@code router.register}）。
 *
 * <p>当前已交付的路由（{@code T-008}/{@code T-009}/{@code T-109}）：
 * <pre>
 *   GET|PUT /api/v1/config/item           配置项查询与取值变更（敏感项 10020 / 绑定键 10021）
 *   GET|POST /api/v1/config/sub-account   子账户列表与创建（DISABLE_ONLY，无删除）
 *   GET|POST /api/v1/config/ctp           CTP 前置配置（FR-CONFIG-1/10）
 *   POST /api/v1/config/ctp/test          CTP 连接测试（M1 显式 10002，待 M2 组件桥接）
 *   GET|POST /api/v1/config/dict          枚举字典（FR-CONFIG-14；无删除，只停用）
 *   GET|POST /api/v1/config/subject       会计科目字典（FR-CONFIG-14）
 *   GET|POST /api/v1/config/report-period 报告周期配置（FR-CONFIG-15；合规必需不可关闭）
 *   GET /api/v1/config/dispatch/{id}      参数下发记录查询（FR-CONFIG-6/R-CONFIG-3）
 *   GET /api/v1/config/change-log         配置变更留痕查询（FR-CONFIG-12）
 *   POST /api/v1/config/import            系统参数导入（FR-CONFIG-13）
 *   GET /api/v1/config/export             系统参数导出（FR-CONFIG-13；强制排除敏感字段）
 * </pre>
 * ⇒ {@code CONFIG} 模块的 {@code ApiRoutes} 清单**已全量注册**（{@code T-109} 补齐声明面）。
 */
public final class ConfigRoutes {

    private ConfigRoutes() {
    }

    /**
     * 注册 {@code CONFIG} 模块路由。
     *
     * @param router        路由
     * @param configService 配置服务
     */
    public static void register(Router router, ConfigService configService) {
        router.register("GET", ConfigItemHandler.PATH, new ConfigItemHandler(configService));
        router.register("PUT", ConfigItemHandler.PATH, new ConfigItemHandler(configService));
        router.register("GET", SubAccountHandler.PATH, new SubAccountHandler(configService));
        router.register("POST", SubAccountHandler.PATH, new SubAccountHandler(configService));
        // T-109：补齐声明面（配置项之外的全部 CONFIG 路径）
        router.register("GET", CtpConfigHandler.PATH, new CtpConfigHandler(configService));
        router.register("POST", CtpConfigHandler.PATH, new CtpConfigHandler(configService));
        router.register("POST", CtpTestHandler.PATH, new CtpTestHandler(configService));
        router.register("GET", DictItemHandler.PATH, new DictItemHandler(configService));
        router.register("POST", DictItemHandler.PATH, new DictItemHandler(configService));
        router.register("GET", AccountSubjectHandler.PATH, new AccountSubjectHandler(configService));
        router.register("POST", AccountSubjectHandler.PATH, new AccountSubjectHandler(configService));
        router.register("GET", ReportPeriodHandler.PATH, new ReportPeriodHandler(configService));
        router.register("POST", ReportPeriodHandler.PATH, new ReportPeriodHandler(configService));
        router.register("GET", ParamDispatchHandler.PATH, new ParamDispatchHandler(configService));
        router.register("GET", ConfigChangeLogHandler.PATH, new ConfigChangeLogHandler(configService));
        router.register("POST", ConfigImportHandler.PATH, new ConfigImportHandler(configService));
        router.register("GET", ConfigExportHandler.PATH, new ConfigExportHandler(configService));
    }
}
