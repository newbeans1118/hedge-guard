package com.hedgeguard.web.handler.product;

import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductChangeLog;
import com.hedgeguard.service.VarietyService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 品种变更历史 {@code GET /api/v1/product/change-log?productId=}（{@code T-012}；{@code FR-PRODUCT-14}）。
 *
 * <p>只读：逐条返回变更类型、字段、前后值与操作人；留痕表**只增不改**（{@code §0.3}），
 * 故本端点**无**任何写方法。
 *
 * <p><b>{@code T-151} 读取面隔离</b>：按<b>调用者可见子账户范围</b>先过滤品种
 * （{@link ProductHandler#requireVisibleProduct}，{@code NFR-SEC-7} / {@code FR-PRODUCT-14} 处理规则
 * 「无权限时返回统一错误」）；<b>不可见 ≡ 不存在</b>（统一错误 {@code 10001}，不泄露存在性，不泄出变更明细）。
 * 载体判定：变更历史的可见性归属<b>品种</b>（输入为「品种标识」；{@code FR-PRODUCT-14} 验收 4
 * 「审计岗可跨子账户查看品种变更历史」），而 {@code t_product_change_log.sub_account_id} 语义为
 * 「涉及子账户」（品种档案本体变更为 {@code NULL}）——<b>非</b>行级归属，故<b>不</b>据该列过滤
 * （否则 {@code NULL} 行与跨子账户行会被误滤）。
 */
public final class ProductChangeLogHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/product/change-log");

    private final VarietyService varietyService;

    /**
     * 构造。
     *
     * @param varietyService 品种服务
     */
    public ProductChangeLogHandler(VarietyService varietyService) {
        this.varietyService = varietyService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        String raw = ctx.queryParam("productId");
        if (raw == null || raw.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "查询参数必填：productId；复现=GET /product/change-log?productId=…");
        }
        // T-151：读取面按调用者可见子账户范围过滤品种；不可见 ≡ 不存在（统一错误 10001，不泄露存在性）
        Product product = ProductHandler.requireVisibleProduct(varietyService, raw, ctx.currentUserId());
        List<ChangeView> views = new ArrayList<>();
        for (ProductChangeLog log : varietyService.listChangeLogs(product.id)) {
            views.add(ChangeView.of(log));
        }
        return HttpResult.json(200, ApiResponse.ok(views, ctx.traceId()));
    }

    /** 变更留痕视图（时间 ISO-8601；数量类字段以字符串承载）。 */
    public static final class ChangeView {

        /** 主键。 */
        public long id;

        /** 变更类型（{@code CREATE} / {@code UPDATE} / {@code ENABLE} / {@code DISABLE}）。 */
        public String changeType;

        /** 变更字段名。 */
        public String changedField;

        /** 变更前值。 */
        public String beforeValue;

        /** 变更后值。 */
        public String afterValue;

        /** 涉及子账户（白名单/额度变更时非空）。 */
        public Integer subAccountId;

        /** 操作人。 */
        public long operatorId;

        /** 变更时间（ISO-8601）。 */
        public String changeTime;

        /** 变更/停用原因。 */
        public String changeReason;

        static ChangeView of(ProductChangeLog log) {
            ChangeView view = new ChangeView();
            view.id = log.id;
            view.changeType = log.changeType;
            view.changedField = log.changedField;
            view.beforeValue = log.beforeValue;
            view.afterValue = log.afterValue;
            view.subAccountId = log.subAccountId;
            view.operatorId = log.operatorId;
            view.changeTime = log.changeTime == null ? null : log.changeTime.toString();
            view.changeReason = log.changeReason;
            return view;
        }
    }
}
