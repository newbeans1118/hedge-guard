package com.hedgeguard.web.handler.config;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.AccountSubject;
import com.hedgeguard.service.ConfigService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 会计科目字典 {@code GET|POST /api/v1/config/subject}（{@code T-109}；{@code FR-CONFIG-14}）。
 *
 * <p>{@code GET ?bizScene=}：列出科目。{@code POST}：新增 / 停用（{@code id > 0} 更新）。
 * 科目由字典驱动，供 {@code ACCT} 生成凭证引用，<b>不得</b>在代码中硬编码。
 */
public final class AccountSubjectHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/config/subject");

    private final ConfigService configService;

    /**
     * 构造。
     *
     * @param configService 配置服务
     */
    public AccountSubjectHandler(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                    ctx.queryParam("sort"), "id");
            PageResult<AccountSubject> page = configService.listSubjects(ctx.queryParam("bizScene"), query);
            List<SubjectView> items = new ArrayList<>();
            for (AccountSubject subject : page.items()) {
                items.add(SubjectView.of(subject));
            }
            PageData<SubjectView> data = new PageData<>(items, page.total(), page.page(), page.size());
            return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
        }
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {subjectCode, subjectName, direction, bizScene, ...}：复现=按 FR-CONFIG-14 提交");
        }
        AccountSubject subject = new AccountSubject();
        subject.id = request.id;
        subject.subjectCode = request.subjectCode;
        subject.subjectName = request.subjectName;
        subject.parentCode = request.parentCode;
        subject.subjectLevel = request.subjectLevel;
        subject.direction = request.direction;
        subject.bizScene = request.bizScene;
        subject.isTemplateDefault = request.isTemplateDefault;
        subject.editableByEnterprise = request.editableByEnterprise;
        subject.status = request.status;
        long id = configService.saveSubject(subject, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    /** 写入请求体（{@code id > 0} 走更新）。 */
    public static final class Request {

        /** 主键（{@code > 0} 更新）。 */
        public long id;

        /** 科目编码（必填）。 */
        public String subjectCode;

        /** 科目名称（必填）。 */
        public String subjectName;

        /** 上级科目编码（{@code null} = 一级科目）。 */
        public String parentCode;

        /** 科目级次。 */
        public int subjectLevel;

        /** 余额方向（{@code DEBIT} / {@code CREDIT}）。 */
        public String direction;

        /** 适用业务场景。 */
        public String bizScene;

        /** 是否系统预置模板项。 */
        public String isTemplateDefault;

        /** 企业是否可修改。 */
        public String editableByEnterprise;

        /** 状态（{@code ENABLED} / {@code DISABLED}）。 */
        public String status;
    }

    /** 科目视图。 */
    public static final class SubjectView {

        /** 主键。 */
        public long id;

        /** 科目编码。 */
        public String subjectCode;

        /** 科目名称。 */
        public String subjectName;

        /** 上级科目编码。 */
        public String parentCode;

        /** 科目级次。 */
        public int subjectLevel;

        /** 余额方向。 */
        public String direction;

        /** 适用业务场景。 */
        public String bizScene;

        /** 是否系统预置模板项。 */
        public String isTemplateDefault;

        /** 企业是否可修改。 */
        public String editableByEnterprise;

        /** 状态。 */
        public String status;

        static SubjectView of(AccountSubject subject) {
            SubjectView view = new SubjectView();
            view.id = subject.id;
            view.subjectCode = subject.subjectCode;
            view.subjectName = subject.subjectName;
            view.parentCode = subject.parentCode;
            view.subjectLevel = subject.subjectLevel;
            view.direction = subject.direction;
            view.bizScene = subject.bizScene;
            view.isTemplateDefault = subject.isTemplateDefault;
            view.editableByEnterprise = subject.editableByEnterprise;
            view.status = subject.status;
            return view;
        }
    }
}
