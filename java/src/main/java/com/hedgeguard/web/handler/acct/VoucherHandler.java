package com.hedgeguard.web.handler.acct;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.Voucher;
import com.hedgeguard.dao.entity.VoucherLine;
import com.hedgeguard.dao.entity.VoucherReview;
import com.hedgeguard.service.AcctService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会计凭证 {@code GET /api/v1/acct/voucher}（分页列表 / 详情，{@code FR-ACCT-8}）与
 * {@code POST /api/v1/acct/voucher}（生成草稿，{@code FR-ACCT-6}；模式 {@code DRAFT_ONLY}、
 * 不做总账过账，草稿标注「待企业财务复核入账」——CFG-09；{@code T-079}）。
 *
 * <p><b>GET</b>：无 {@code id} 参数 = 分页列表（默认凭证编号倒序 + 子账户可见集过滤）；
 * {@code id} 参数 = 详情（含分录行与审核链）。凭证记录只读（FR-ACCT-8 验收 3 无编辑/删除入口
 * ——删除走 POST 体 {@code action=DELETE_DRAFT} 的显式通道）。
 *
 * <p><b>POST 请求体</b>：草稿生成字段（{@code AcctService.VoucherDraftRequest} 同形）或
 * 含 {@code action=DELETE_DRAFT} + {@code voucherId} 的草稿删除（FR-ACCT-13 草稿可直接删除）。
 */
public final class VoucherHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("ACCT")}）。 */
    public static final String PATH = "/api/v1/acct/voucher";

    private final AcctService acctService;

    /**
     * 构造。
     *
     * @param acctService 套期会计服务
     */
    public VoucherHandler(AcctService acctService) {
        this.acctService = acctService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return get(ctx);
        }
        CreateRequest in = JsonCodec.fromJson(AcctParams.body(ctx), CreateRequest.class);
        if (in == null) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "请求体不得为空；复现=按 FR-ACCT-6 提交凭证草稿字段", ctx.traceId()));
        }
        if ("DELETE_DRAFT".equals(in.action)) {
            if (in.voucherId == null) {
                return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                        "voucherId 必填（草稿删除）；复现=补齐后重试", ctx.traceId()));
            }
            Voucher v = acctService.deleteDraftVoucher(in.voucherId, ctx.currentUserId());
            return HttpResult.json(200, ApiResponse.ok(view(v), ctx.traceId()));
        }
        if (in.subAccountId == null) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "subAccountId 必填（凭证必须按子账户出账，R-ACCT-5）；复现=补齐后重试",
                    ctx.traceId()));
        }
        Voucher v = acctService.createVoucherDraft(toDraftRequest(in), ctx.currentUserId());
        Map<String, Object> out = view(v);
        out.put("draftPendingEntryNote", AcctService.DRAFT_PENDING_ENTRY_NOTE);
        return HttpResult.json(200, ApiResponse.ok(out, ctx.traceId()));
    }

    private AcctService.VoucherDraftRequest toDraftRequest(CreateRequest in) {
        AcctService.VoucherDraftRequest req = new AcctService.VoucherDraftRequest();
        req.subAccountId = in.subAccountId;
        req.periodKey = in.periodKey;
        req.voucherType = in.voucherType;
        req.summary = in.summary;
        req.amount = in.amount;
        req.qty = in.qty;
        req.hedgePlanId = in.hedgePlanId;
        req.hedgeRelationId = in.hedgeRelationId;
        req.settleCycleId = in.settleCycleId;
        req.exposureId = in.exposureId;
        req.tradeFillId = in.tradeFillId;
        return req;
    }

    private HttpResult get(RequestContext ctx) {
        Long id = AcctParams.longOrNull(ctx.queryParam("id"), "id");
        if (id != null) {
            AcctService.VoucherDetail d = acctService.getVoucher(id, ctx.currentUserId());
            Map<String, Object> out = view(d.voucher);
            List<Map<String, Object>> lines = new ArrayList<>();
            for (VoucherLine l : d.lines) {
                lines.add(lineView(l));
            }
            List<Map<String, Object>> reviews = new ArrayList<>();
            for (VoucherReview r : d.reviews) {
                reviews.add(reviewView(r));
            }
            out.put("lines", lines);
            out.put("reviews", reviews);
            out.put("draftPendingEntryNote", AcctService.DRAFT_PENDING_ENTRY_NOTE);
            return HttpResult.json(200, ApiResponse.ok(out, ctx.traceId()));
        }
        PageQuery q = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "voucher_no", "desc");
        PageResult<Voucher> page = acctService.listVouchers(q,
                AcctParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                AcctParams.text(ctx.queryParam("periodKey")),
                AcctParams.text(ctx.queryParam("status")), ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Voucher v : page.items()) {
            items.add(view(v));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    /** 头视图（字段名 camelCase；「待企业财务复核入账」/「待外部复核」标注恒带）。 */
    static Map<String, Object> view(Voucher v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id);
        m.put("subAccountId", v.subAccountId);
        m.put("voucherNo", v.voucherNo);
        m.put("periodKey", v.periodKey);
        m.put("voucherDate", v.voucherDate == null ? null : v.voucherDate.toString());
        m.put("voucherType", v.voucherType);
        m.put("hedgePlanId", v.hedgePlanId);
        m.put("hedgeRelationId", v.hedgeRelationId);
        m.put("settleCycleId", v.settleCycleId);
        m.put("exposureId", v.exposureId);
        m.put("status", v.status);
        m.put("totalAmount", v.totalAmount == null ? null : v.totalAmount.toPlainString());
        m.put("generatedBy", v.generatedBy);
        m.put("generatedTime", v.generatedTime == null ? null : v.generatedTime.toString());
        m.put("reversalOfId", v.reversalOfId);
        m.put("reversedById", v.reversedById);
        m.put("reversalReason", v.reversalReason);
        m.put("pendingExternalReview", v.pendingExternalReview);
        return m;
    }

    /** 分录行视图。 */
    static Map<String, Object> lineView(VoucherLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id);
        m.put("subAccountId", l.subAccountId);
        m.put("voucherId", l.voucherId);
        m.put("lineNo", l.lineNo);
        m.put("subjectCode", l.subjectCode);
        m.put("subjectName", l.subjectName);
        m.put("direction", l.direction);
        m.put("amount", l.amount == null ? null : l.amount.toPlainString());
        m.put("qty", l.qty == null ? null : l.qty.toPlainString());
        m.put("tradeFillId", l.tradeFillId);
        m.put("bizDesc", l.bizDesc);
        return m;
    }

    /** 审核节点视图（含第二复核人 coReviewerId，DC-032）。 */
    static Map<String, Object> reviewView(VoucherReview r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("subAccountId", r.subAccountId);
        m.put("voucherId", r.voucherId);
        m.put("nodeSeq", r.nodeSeq);
        m.put("action", r.action);
        m.put("generatorId", r.generatorId);
        m.put("reviewerId", r.reviewerId);
        m.put("coReviewerId", r.coReviewerId);
        m.put("reviewResult", r.reviewResult);
        m.put("reviewComment", r.reviewComment);
        m.put("rejectReason", r.rejectReason);
        m.put("actionTime", r.actionTime == null ? null : r.actionTime.toString());
        return m;
    }

    /** POST 请求体（草稿生成或 {@code action=DELETE_DRAFT}）。 */
    public static final class CreateRequest {
        /** 动作（草稿删除专用；缺省 = 草稿生成）。 */
        public String action;
        /** 草稿删除目标凭证。 */
        public Long voucherId;
        /** 子账户（草稿生成必填）。 */
        public Integer subAccountId;
        /** 会计期间。 */
        public String periodKey;
        /** 凭证类型。 */
        public String voucherType;
        /** 业务摘要。 */
        public String summary;
        /** 金额。 */
        public java.math.BigDecimal amount;
        /** 数量（可空）。 */
        public java.math.BigDecimal qty;
        /** 关联套保计划（可空）。 */
        public Long hedgePlanId;
        /** 关联套期关系（可空）。 */
        public Long hedgeRelationId;
        /** 关联结算单（可空）。 */
        public Long settleCycleId;
        /** 关联实货敞口（可空）。 */
        public Long exposureId;
        /** 关联成交（可空）。 */
        public Long tradeFillId;
    }
}
