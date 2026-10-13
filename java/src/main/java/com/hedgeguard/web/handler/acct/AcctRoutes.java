package com.hedgeguard.web.handler.acct;

import com.hedgeguard.service.AcctService;
import com.hedgeguard.web.Router;

/**
 * {@code ACCT} 模块路由注册（{@code T-077} 建键 4 对；{@code T-078} 增再平衡 2 对；
 * 路径逐字取自 {@code ApiRoutes.byModule().get("ACCT")}，其余 8 条路径归 T-079/T-080 交付）。
 *
 * <pre>
 *   GET  /api/v1/acct/hedge-relation   套期关系分页查询（FR-ACCT-1）
 *   POST /api/v1/acct/hedge-relation   生成套期关系（正式指定；三要素门禁 R-ACCT-1）
 *   GET  /api/v1/acct/effectiveness    评估记录分页 / 监测复核清单 / 未评估提醒（FR-ACCT-2/4）
 *   POST /api/v1/acct/effectiveness    提交有效性评估（三项定性观察点，R-ACCT-2/Q-7）
 *   GET  /api/v1/acct/rebalance        再平衡史（按套期关系；idx_rebalance_relation_time，FR-ACCT-3）
 *   POST /api/v1/acct/rebalance        执行再平衡（四类触发 Q-8；重新指定 R-ACCT-3）
 * </pre>
 *
 * <p><b>注册序约束（{@code BUG-004}）</b>：本模块仅静态 2 段路由，无占位符路由，无遮蔽关系。
 */
public final class AcctRoutes {

    private AcctRoutes() {
    }

    /**
     * 注册 {@code ACCT} 模块路由（T-077 的 4 对 + T-078 再平衡 2 对）。
     *
     * @param router      路由
     * @param acctService 套期会计服务
     */
    public static void register(Router router, AcctService acctService) {
        router.register("GET", HedgeRelationHandler.PATH, new HedgeRelationHandler(acctService));
        router.register("POST", HedgeRelationHandler.PATH, new HedgeRelationHandler(acctService));
        router.register("GET", EffectivenessHandler.PATH, new EffectivenessHandler(acctService));
        router.register("POST", EffectivenessHandler.PATH, new EffectivenessHandler(acctService));
        router.register("GET", RebalanceHandler.PATH, new RebalanceHandler(acctService));
        router.register("POST", RebalanceHandler.PATH, new RebalanceHandler(acctService));
    }
}
