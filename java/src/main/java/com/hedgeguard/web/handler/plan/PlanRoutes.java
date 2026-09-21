package com.hedgeguard.web.handler.plan;

import com.hedgeguard.service.PlanService;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.Router;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * {@code PLAN} 模块路由注册（{@code #java2} 产出；路径逐字取自 {@code ApiRoutes.byModule().get("PLAN")}）。
 *
 * <p>归属声明：本类与 {@code web/handler/plan/**} 由 {@code backend-engineer#java2} 新增；
 * {@code web/} 框架类保持 {@code #java} 写入域不变（本类只调用 {@code router.register}）。
 *
 * <pre>
 *   GET  /api/v1/plan                          计划列表（条件筛选 + 分页，FR-PLAN-4）
 *   POST /api/v1/plan                          编制计划（绑定敞口）
 *   GET  /api/v1/plan/{id}                     计划详情（含绑定敞口清单）
 *   POST /api/v1/plan/{id}/doc                 生成书面文件（三要素门禁）
 *   POST /api/v1/plan/{id}/submit              提交审批（须有当前版本书面文件）
 *   POST /api/v1/plan/{id}/approve             审批通过（发起人 ≠ 审批人；占额）
 *   POST /api/v1/plan/{id}/reject              驳回（原因必填；回草稿）
 *   POST /api/v1/plan/{id}/withdraw            撤销（待审批 → 草稿）
 *   POST /api/v1/plan/{id}/freeze              冻结（额度继续占用）
 *   POST /api/v1/plan/{id}/unfreeze            解冻
 *   POST /api/v1/plan/{id}/terminate           终止（释放额度）
 *   POST /api/v1/plan/{id}/extend              延期（须给实货合同期限依据）
 *   GET|POST /api/v1/plan/{id}/trade-plans     交易计划查询 / 分解
 *   POST /api/v1/plan/ratio/simple             简单比率法
 *   POST /api/v1/plan/ratio/minvar             最小方差法（样本 ≥ 30）
 *   POST /api/v1/plan/ratio/custom             自定义规则法（取最严）
 *   POST /api/v1/plan/{id}/bind-exposure       绑定实货敞口（FR-PLAN-19；仅草稿态可直接增删）
 * </pre>
 * ⇒ {@code PLAN} 模块的 {@code ApiRoutes} 清单**已全量注册**（{@code T-109} 补齐声明面）。
 */
public final class PlanRoutes {

    private PlanRoutes() {
    }

    /**
     * 注册 {@code PLAN} 模块路由。
     *
     * @param router      路由
     * @param planService 计划服务
     */
    public static void register(Router router, PlanService planService) {
        router.register("GET", PlanHandler.PATH, new PlanHandler(planService));
        router.register("POST", PlanHandler.PATH, new PlanHandler(planService));
        router.register("GET", PlanHandler.PATH_BY_ID, new PlanHandler(planService));
        router.register("POST", PlanDocHandler.PATH, new PlanDocHandler(planService));
        for (PlanActionHandler.Action action : PlanActionHandler.Action.values()) {
            router.register("POST", action.path(), new PlanActionHandler(planService, action));
        }
        router.register("GET", TradePlanHandler.PATH, new TradePlanHandler(planService));
        router.register("POST", TradePlanHandler.PATH, new TradePlanHandler(planService));
        for (PlanRatioHandler.Method method : PlanRatioHandler.Method.values()) {
            router.register("POST", method.path(), new PlanRatioHandler(planService, method));
        }
        // T-109：补齐声明面
        router.register("POST", BindExposureHandler.PATH, new BindExposureHandler(planService));
    }

    /** 解析十进制整数（路径参数 / 字符串化 ID）。 */
    static long parseId(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "参数必填：" + field + "；复现=补 " + field);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "参数须为十进制整数：" + field + "=" + raw + "；复现=改正取值", e);
        }
    }

    /** 解析字符串化十进制（禁止 JSON number，{@code §4.1.1}）。 */
    static BigDecimal decimal(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "字段必填：" + field + "；复现=以**字符串化十进制**提交（禁止 JSON number）");
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "字段须为字符串化十进制：" + field + "=" + raw + "；复现=改正取值", e);
        }
    }

    /** 解析日期（{@code yyyy-MM-dd}）。 */
    static LocalDate date(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "字段必填：" + field + "（yyyy-MM-dd）；复现=补齐日期");
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "字段须为 yyyy-MM-dd：" + field + "=" + raw + "；复现=改正日期格式", e);
        }
    }

    /** 路径常量（供注册器与测试引用）。 */
    public static final String RATIO_SIMPLE = ApiRoutes.full("/plan/ratio/simple");
}
