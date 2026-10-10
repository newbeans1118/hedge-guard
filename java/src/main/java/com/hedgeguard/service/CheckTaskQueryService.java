package com.hedgeguard.service;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SettleCheckSignatureDao;
import com.hedgeguard.dao.SettleCheckTaskDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.SettleCheckSignature;
import com.hedgeguard.dao.entity.SettleCheckTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 核对任务与签署记录查询服务（{@code T-069}；{@code FR-AUDIT-13}、{@code R-AUDIT-11}、{@code CH-002}/{@code Q-14}）。
 *
 * <p>审计侧<b>只读</b>查询 {@code SETTLE} 的核对任务（{@code TB-SETTLE-13}）与电子签署记录
 * （{@code TB-SETTLE-15}）：返回任务 + 签署明细（<b>账号 / 角色 / 时间 / IP / 核对对象快照哈希</b>）、
 * <b>逾期未签清单</b>与<b>年度完成率汇总</b>。
 *
 * <p><b>只读（{@code R-AUDIT-6}）</b>：本服务只调用 {@code findPage}/{@code findByTask} 读方法，
 * 无任何写入；无修改或删除入口（验收 4）。
 *
 * <p><b>软隔离（{@code R-AUDIT-9}）</b>：可见范围经
 * {@link SubAccountVisibility#visibleSubAccountIds} 解析；含哨兵 {@code 0} 者跨子账户（审计岗，验收 5）；
 * 否则仅覆盖授权子账户。
 *
 * <p><b>年度全覆盖（{@code Q-14}）</b>：{@link #annualCoverage} 按 {@code period_key} 以年份前缀归集，
 * 汇总各核对类型（月/季/年）的完成率与逾期清单。
 */
public final class CheckTaskQueryService {

    /** 任务状态：已完成。 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 任务状态：逾期（{@code ck_t_check_task_status}）。 */
    public static final String STATUS_OVERDUE = "OVERDUE";

    /** 单页读取大小（{@code PageQuery} 白名单最大值）。 */
    private static final int PAGE_SIZE = 100;

    private final SettleCheckTaskDao taskDao;
    private final SettleCheckSignatureDao signatureDao;
    private final SubAccountVisibility authService;

    /**
     * 构造。
     *
     * @param taskDao      核对任务 DAO
     * @param signatureDao 签署记录 DAO
     * @param authService  可见范围取数（{@code NFR-SEC-7}）
     */
    public CheckTaskQueryService(SettleCheckTaskDao taskDao, SettleCheckSignatureDao signatureDao,
                                 SubAccountVisibility authService) {
        this.taskDao = taskDao;
        this.signatureDao = signatureDao;
        this.authService = authService;
    }

    /**
     * 查询核对任务与签署记录（{@code FR-AUDIT-13} 验收 1/2/5）。
     *
     * @param f          筛选条件（可空 = 全部可见）
     * @param page       页码（从 1 起）
     * @param size       每页条数（{@code PageQuery} 白名单）
     * @param operatorId 查询人
     * @return 任务列表 + 签署明细 + 逾期清单 + 完成率
     */
    public CheckTaskResult query(CheckTaskFilter f, String page, String size, long operatorId) {
        List<Integer> visible = visible(operatorId);
        boolean cross = visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        String extraWhere = null;
        List<Object> args = new ArrayList<>();
        if (f != null) {
            if (f.checkType != null && !f.checkType.trim().isEmpty()) {
                extraWhere = append(extraWhere, "check_type = ?");
                args.add(f.checkType.trim());
            }
            if (f.status != null && !f.status.trim().isEmpty()) {
                extraWhere = append(extraWhere, "status = ?");
                args.add(f.status.trim());
            }
            if (f.subAccountId != null) {
                extraWhere = append(extraWhere, "sub_account_id = ?");
                args.add(f.subAccountId);
            }
            if (f.fromPeriod != null && !f.fromPeriod.trim().isEmpty()) {
                extraWhere = append(extraWhere, "period_key >= ?");
                args.add(f.fromPeriod.trim());
            }
            if (f.toPeriod != null && !f.toPeriod.trim().isEmpty()) {
                extraWhere = append(extraWhere, "period_key <= ?");
                args.add(f.toPeriod.trim());
            }
            if (Boolean.TRUE.equals(f.overdueOnly)) {
                extraWhere = append(extraWhere, "status <> ?");
                args.add(STATUS_COMPLETED);
                extraWhere = append(extraWhere, "deadline_date < ?");
                args.add(f.asOf == null ? LocalDate.now(com.hedgeguard.common.Clocks.ZONE) : f.asOf);
            }
        }
        String where = extraWhere;
        List<Object> whereArgs = args;
        if (!cross) {
            SubAccountScope scope = SubAccountScope.of("sub_account_id", visible);
            where = (where == null || where.isEmpty()) ? scope.sql() : where + " AND " + scope.sql();
            whereArgs = new ArrayList<>(args);
            whereArgs.addAll(scope.params());
        }
        PageQuery q = PageQuery.of(page, size, null, "id", "asc");
        PageResult<SettleCheckTask> pr = taskDao.findPage(q, where, whereArgs);
        CheckTaskResult out = new CheckTaskResult();
        out.total = pr.total();
        out.page = pr.page();
        out.size = pr.size();
        out.crossSubAccount = cross;
        out.visibleSubAccounts = visible;
        for (SettleCheckTask task : pr.items()) {
            out.tasks.add(view(task, visible, f == null ? null : f.signRole));
        }
        out.overdue = overdueIn(pr.items(), f == null ? null : f.asOf);
        out.coverage = coverageOf(pr.items());
        return out;
    }

    /**
     * 逾期未签清单（{@code FR-AUDIT-13} 验收 2；{@code R-AUDIT-11}）。逾期口径 =
     * {@code status <> 'COMPLETED'} 且 {@code deadline_date < asOf}，与
     * {@code SettleCheckTaskDao.findOverdue} 一致，另按可见范围过滤。
     *
     * @param asOf       业务日
     * @param operatorId 查询人
     * @return 逾期任务（按截止日、主键升序）
     */
    public List<SettleCheckTask> overdue(LocalDate asOf, long operatorId) {
        List<Integer> visible = visible(operatorId);
        boolean cross = visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        String where = "status <> ? AND deadline_date < ?";
        List<Object> args = new ArrayList<>();
        args.add(STATUS_COMPLETED);
        args.add(asOf == null ? LocalDate.now(com.hedgeguard.common.Clocks.ZONE) : asOf);
        if (!cross) {
            SubAccountScope scope = SubAccountScope.of("sub_account_id", visible);
            where = where + " AND " + scope.sql();
            args.addAll(scope.params());
        }
        List<SettleCheckTask> out = new ArrayList<>();
        int page = 1;
        while (true) {
            PageResult<SettleCheckTask> pr = taskDao.findPage(
                    PageQuery.of(String.valueOf(page), String.valueOf(PAGE_SIZE),
                            "deadline_date,asc", "deadline_date", "asc"),
                    where, args);
            out.addAll(pr.items());
            if (pr.items().isEmpty() || out.size() >= pr.total()) {
                return out;
            }
            page++;
        }
    }

    /**
     * 年度全覆盖汇总（{@code FR-AUDIT-13} 验收 3 / {@code R-AUDIT-11}）：按年份前缀归集全部可见核对任务，
     * 汇总各核对类型（月度/季度/年度）的完成率与逾期清单。
     *
     * @param year       年份（如 {@code 2026}）
     * @param asOf       业务日（逾期判定基准）
     * @param operatorId 查询人
     * @return 年度覆盖汇总
     */
    public AnnualCoverage annualCoverage(String year, LocalDate asOf, long operatorId) {
        if (year == null || year.trim().isEmpty()) {
            throw com.hedgeguard.web.BizException.of(
                    com.hedgeguard.web.ErrorCode.PARAM_INVALID, "年度必填（FR-AUDIT-13 验收 3）");
        }
        String prefix = year.trim();
        List<Integer> visible = visible(operatorId);
        boolean cross = visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        AnnualCoverage out = new AnnualCoverage();
        out.year = prefix;
        out.crossSubAccount = cross;
        out.visibleSubAccounts = visible;
        for (SettleCheckTask t : allVisibleTasks(visible, cross)) {
            if (t.periodKey == null || !t.periodKey.startsWith(prefix)) {
                continue;
            }
            String type = t.checkType == null ? "UNKNOWN" : t.checkType;
            Coverage c = out.byType.computeIfAbsent(type, Coverage::new);
            c.total++;
            if (STATUS_COMPLETED.equals(t.status)) {
                c.completed++;
            } else if (out.isOverdue(t, asOf)) {
                c.overdue++;
                out.overdue.add(t);
            }
        }
        for (Coverage c : out.byType.values()) {
            c.rate = c.total == 0 ? null
                    : BigDecimal.valueOf(c.completed)
                            .divide(BigDecimal.valueOf(c.total), 4, RoundingMode.HALF_UP);
        }
        return out;
    }

    // ------------------------------------------------------------ 内部辅助

    private List<Integer> visible(long operatorId) {
        List<Integer> v = authService == null ? null : authService.visibleSubAccountIds(operatorId);
        return v == null ? new ArrayList<>() : new ArrayList<>(v);
    }

    private List<SettleCheckTask> allVisibleTasks(List<Integer> visible, boolean cross) {
        List<SettleCheckTask> out = new ArrayList<>();
        String where = null;
        List<Object> args = new ArrayList<>();
        if (!cross) {
            SubAccountScope scope = SubAccountScope.of("sub_account_id", visible);
            where = scope.sql();
            args.addAll(scope.params());
        }
        int page = 1;
        while (true) {
            PageResult<SettleCheckTask> pr = taskDao.findPage(
                    PageQuery.of(String.valueOf(page), String.valueOf(PAGE_SIZE), null, "id", "asc"),
                    where, args);
            out.addAll(pr.items());
            if (pr.items().isEmpty() || out.size() >= pr.total()) {
                return out;
            }
            page++;
        }
    }

    private CheckTaskView view(SettleCheckTask task, List<Integer> visible, String signRole) {
        CheckTaskView v = new CheckTaskView();
        v.task = task;
        for (SettleCheckSignature sig : signatureDao.findByTask(task.id)) {
            boolean inScope = sig.subAccountId == null || visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)
                    || visible.contains(sig.subAccountId);
            boolean roleMatch = signRole == null || signRole.trim().isEmpty()
                    || signRole.trim().equals(sig.signRole);
            if (inScope && roleMatch) {
                v.signatures.add(sig);
            }
        }
        return v;
    }

    private List<SettleCheckTask> overdueIn(List<SettleCheckTask> tasks, LocalDate asOf) {
        List<SettleCheckTask> out = new ArrayList<>();
        for (SettleCheckTask t : tasks) {
            if (!STATUS_COMPLETED.equals(t.status) && t.deadlineDate != null
                    && asOf != null && t.deadlineDate.isBefore(asOf)) {
                out.add(t);
            }
        }
        return out;
    }

    private Map<String, Coverage> coverageOf(List<SettleCheckTask> tasks) {
        Map<String, Coverage> out = new LinkedHashMap<>();
        for (SettleCheckTask t : tasks) {
            String type = t.checkType == null ? "UNKNOWN" : t.checkType;
            Coverage c = out.computeIfAbsent(type, Coverage::new);
            c.total++;
            if (STATUS_COMPLETED.equals(t.status)) {
                c.completed++;
            }
        }
        for (Coverage c : out.values()) {
            c.rate = c.total == 0 ? null
                    : BigDecimal.valueOf(c.completed)
                            .divide(BigDecimal.valueOf(c.total), 4, RoundingMode.HALF_UP);
        }
        return out;
    }

    private static String append(String where, String clause) {
        return (where == null || where.isEmpty()) ? clause : where + " AND " + clause;
    }

    // ------------------------------------------------------------ 载体类型

    /** 核对任务筛选条件（{@code FR-AUDIT-13} 输入列）。 */
    public static final class CheckTaskFilter {
        /** 核对任务类型（{@code MONTHLY}/{@code QUARTERLY}/{@code ANNUAL}）。 */
        public String checkType;
        /** 子账户。 */
        public Integer subAccountId;
        /** 任务状态。 */
        public String status;
        /** 签署角色（筛签署明细）。 */
        public String signRole;
        /** 仅逾期未签（{@code FR-AUDIT-13} 验收 2）。 */
        public Boolean overdueOnly;
        /** 逾期判定基准日。 */
        public LocalDate asOf;
        /** 期间键下界（含）。 */
        public String fromPeriod;
        /** 期间键上界（含）。 */
        public String toPeriod;
    }

    /** 核对任务视图（任务 + 签署明细）。 */
    public static final class CheckTaskView {
        /** 任务。 */
        public SettleCheckTask task;
        /** 签署记录（含账号 / 角色 / 时间 / IP / 快照哈希）。 */
        public List<SettleCheckSignature> signatures = new ArrayList<>();
    }

    /** 查询结果（任务列表 + 逾期清单 + 完成率）。 */
    public static final class CheckTaskResult {
        /** 当页任务视图。 */
        public List<CheckTaskView> tasks = new ArrayList<>();
        /** 满足条件的总任务数。 */
        public long total;
        /** 当前页。 */
        public int page;
        /** 每页条数。 */
        public int size;
        /** 逾期未签清单。 */
        public List<SettleCheckTask> overdue = new ArrayList<>();
        /** 各核对类型的完成率。 */
        public Map<String, Coverage> coverage = new LinkedHashMap<>();
        /** 是否跨子账户（审计岗）。 */
        public boolean crossSubAccount;
        /** 可见子账户集合。 */
        public List<Integer> visibleSubAccounts = new ArrayList<>();
    }

    /** 某核对类型的完成率。 */
    public static final class Coverage {
        /** 核对类型。 */
        public String checkType;
        /** 任务总数。 */
        public int total;
        /** 已完成数。 */
        public int completed;
        /** 逾期未签数。 */
        public int overdue;
        /** 完成率（{@code completed/total}；无任务为 {@code null}）。 */
        public BigDecimal rate;

        Coverage(String checkType) {
            this.checkType = checkType;
        }
    }

    /** 年度覆盖汇总。 */
    public static final class AnnualCoverage {
        /** 年度。 */
        public String year;
        /** 各核对类型完成率。 */
        public Map<String, Coverage> byType = new LinkedHashMap<>();
        /** 年度内逾期未签清单。 */
        public List<SettleCheckTask> overdue = new ArrayList<>();
        /** 是否跨子账户。 */
        public boolean crossSubAccount;
        /** 可见子账户集合。 */
        public List<Integer> visibleSubAccounts = new ArrayList<>();

        boolean isOverdue(SettleCheckTask t, LocalDate asOf) {
            return t.deadlineDate != null && asOf != null && t.deadlineDate.isBefore(asOf);
        }
    }
}
