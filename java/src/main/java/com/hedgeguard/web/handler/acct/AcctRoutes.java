package com.hedgeguard.web.handler.acct;

import com.hedgeguard.service.AcctService;
import com.hedgeguard.web.Router;

/**
 * {@code ACCT} 模块路由注册（{@code T-077}，ACCT 方法注册表从零建；路径逐字取自
 * {@code ApiRoutes.byModule().get("ACCT")}，本单注册其中 2 条路径的 4 对方法，
 * 其余 10 条路径归 T-078/T-079/T-080 交付）。
 *
 * <pre>
 *   GET  /api/v1/acct/hedge-relation   套期关系分页查询（FR-ACCT-1）
 *   POST /api/v1/acct/hedge-relation   生成套期关系（正式指定；三要素门禁 R-ACCT-1）
 *   GET  /api/v1/acct/effectiveness    评估记录分页 / 监测复核清单 / 未评估提醒（FR-ACCT-2/4）
 *   POST /api/v1/acct/effectiveness    提交有效性评估（三项定性观察点，R-ACCT-2/Q-7）
 * </pre>
 *
 * <p><b>注册序约束（{@code BUG-004}）</b>：本模块仅静态 2 段路由，无占位符路由，无遮蔽关系。
 */
public final class AcctRoutes {

    private AcctRoutes() {
    }

    /**
     * 注册 {@code ACCT} 模块路由（T-077 交付的 4 对）。
     *
     * @param router      路由
     * @param acctService 套期会计服务
     */
    public static void register(Router router, AcctService acctService) {
        router.register("GET", HedgeRelationHandler.PATH, new HedgeRelationHandler(acctService));
        router.register("POST", HedgeRelationHandler.PATH, new HedgeRelationHandler(acctService));
        router.register("GET", EffectivenessHandler.PATH, new EffectivenessHandler(acctService));
        router.register("POST", EffectivenessHandler.PATH, new EffectivenessHandler(acctService));
    }
}
