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
 *   PUT  /api/v1/plan/{id}                     修改草稿（FR-PLAN-2）
 *   GET  /api/v1/plan/{id}/doc                 读取当前书面文件正文（FR-PLAN-5）
 *   POST /api/v1/plan/{id}/bind-exposure       绑定实货敞口（FR-PLAN-19；仅草稿态可直接增删）
 * </pre>
 * ⇒ {@code PLAN} 模块的 {@code ApiRoutes} 清单**已全量注册**（{@code T-109} 补齐声明面）。
 *
 * <p><b>注册序约束（{@code BUG-004}）</b>：{@code Router.match} 按注册序首次命中；这里新增的
 * {@code PUT /plan/{id}}（4 段）与 {@code GET /plan/{id}/doc}（5 段）分别以方法与段数区分于
 * 既有 {@code GET /plan/{id}}、{@code POST /plan/{id}/doc}，故一并置于末尾「占位符段」区。
 * <b>删除草稿（{@code DELETE /plan/{id}}，FR-PLAN-3）本轮不注册</b>：草稿计划必然存在
 * {@code t_plan_state_log}（编制留痕）与 {@code t_hedge_plan_exposure}（绑定敞口）子行，二者 FK
 * 均为 {@code ON DELETE RESTRICT}，且状态留痕表「只增不改不删」、全库禁级联（{@code DATA_MODEL.md}
 * §0.3/§0.4）⇒ 物理删除在真 DM8 上必然被拒，而内存引擎不校验 RESTRICT 会造成假绿。
 * 该端点须先经 {@code CH/DC} 裁定删除留痕承载方案，登记待办、不臆造。
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
        // FR-PLAN-2：修改草稿（PUT /plan/{id}）；与 GET /plan/{id} 同路径不同方法。
        router.register("PUT", PlanDraftHandler.PATH, new PlanDraftHandler(planService));
        // FR-PLAN-5：读取当前书面文件正文（GET /plan/{id}/doc）；与 POST 生成同路径不同方法。
        router.register("GET", PlanDocReadHandler.PATH, new PlanDocReadHandler(planService));
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
