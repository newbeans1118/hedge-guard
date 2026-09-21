package com.hedgeguard.service;

import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlanService#listPlans} 的筛选 / 排序白名单校验（{@code FR-PLAN-4}；完全离线）。
 *
 * <p>以"记录型假 DAO"捕获服务层生成的 {@code where} 与参数，机器校验：
 * ① 各筛选条件按固定顺序生成占位符（取值一律占位符，列名为代码内字面量）；
 * ② 创建时间按"日期闭区间 → [当日 00:00, 次日 00:00)"换算；
 * ③ 排序字段白名单、状态 / 计划类型取值域校验（非法即 {@code 10001}）。
 */
class PlanListQueryTest {

    private static PlanService service(HedgePlanDao dao) {
        // 仅 hedgePlanDao 被 listPlans 使用；其余依赖传 null（构造仅做赋值）。
        // 可见集返回哨兵 0（跨账户只读）⇒ 服务层走"不加过滤"路径，本用例专注筛选条件 SQL 本身。
        return new PlanService(FakeTx.newTemplate(), dao, null, null, null, null, null, null, null, null,
                null, userId -> Collections.singletonList(SubAccountScope.ALL_ACCOUNT_SENTINEL),
                null, null, null, null, null, null);
    }

    @Test
    @DisplayName("筛选条件：按固定顺序生成占位符与参数；创建时间为 [起, 止+1日) 半开区间")
    void buildsWhereAndArgsForAllFilters() {
        RecordingHedgePlanDao dao = new RecordingHedgePlanDao();
        PageQuery query = PageQuery.of("2", "50", "plan_no,desc", PlanService.DEFAULT_SORT_FIELD);
        PlanService.PlanFilter filter = new PlanService.PlanFilter();
        filter.subAccountId = 7;
        filter.productId = 100L;
        filter.status = "PENDING_APPROVAL";
        filter.planType = "COMMODITY";
        filter.applicantId = 9L;
        filter.termStartFrom = LocalDate.parse("2026-01-01");
        filter.termStartTo = LocalDate.parse("2026-12-31");
        filter.createdFrom = LocalDate.parse("2026-03-01");
        filter.createdTo = LocalDate.parse("2026-03-31");

        service(dao).listPlans(1L, query, filter);

        assertEquals("sub_account_id = ? AND product_id = ? AND status = ? AND plan_type = ?"
                        + " AND applicant_id = ? AND term_start_date >= ? AND term_start_date <= ?"
                        + " AND created_time >= ? AND created_time < ?",
                dao.where, "筛选条件 SQL 与顺序");
        List<Object> expected = new ArrayList<>();
        expected.add(7);
        expected.add(100L);
        expected.add("PENDING_APPROVAL");
        expected.add("COMMODITY");
        expected.add(9L);
        expected.add(LocalDate.parse("2026-01-01"));
        expected.add(LocalDate.parse("2026-12-31"));
        expected.add(LocalDateTime.parse("2026-03-01T00:00:00"));
        expected.add(LocalDateTime.parse("2026-04-01T00:00:00"));
        assertEquals(expected, dao.args, "绑定参数（末项 = 截止次日 00:00）");
    }

    @Test
    @DisplayName("无筛选条件：不生成 WHERE（where=null）、参数为空")
    void emptyFilterYieldsNoWhere() {
        RecordingHedgePlanDao dao = new RecordingHedgePlanDao();
        PageQuery query = PageQuery.of(null, null, null, PlanService.DEFAULT_SORT_FIELD);

        service(dao).listPlans(1L, query, null);

        assertNull(dao.where, "无筛选时 where 应为 null");
        assertTrue(dao.args.isEmpty(), "无筛选时参数应为空");
    }

    @Test
    @DisplayName("排序字段不在白名单 → 10001（防无效列名 / 防注入）")
    void rejectsSortFieldOutOfWhitelist() {
        RecordingHedgePlanDao dao = new RecordingHedgePlanDao();
        PageQuery query = PageQuery.of(null, null, "evil_column,asc", PlanService.DEFAULT_SORT_FIELD);

        BizException e = assertThrows(BizException.class, () -> service(dao).listPlans(1L, query, null));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
        assertEquals(0, dao.calls, "校验失败不得触碰 DAO");
    }

    @Test
    @DisplayName("状态 / 计划类型取值非法 → 10001（校验失败不触碰 DAO）")
    void rejectsInvalidStatusAndPlanType() {
        RecordingHedgePlanDao dao = new RecordingHedgePlanDao();
        PageQuery query = PageQuery.of(null, null, null, PlanService.DEFAULT_SORT_FIELD);

        PlanService.PlanFilter badStatus = new PlanService.PlanFilter();
        badStatus.status = "NOT_A_STATUS";
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service(dao).listPlans(1L, query, badStatus)).errorCode());

        PlanService.PlanFilter badType = new PlanService.PlanFilter();
        badType.planType = "NOT_A_TYPE";
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service(dao).listPlans(1L, query, badType)).errorCode());

        assertEquals(0, dao.calls, "校验失败不得触碰 DAO");
    }

    @Test
    @DisplayName("返回分页结果按 PageQuery 的 page/size 透传")
    void passesThroughPaging() {
        RecordingHedgePlanDao dao = new RecordingHedgePlanDao();
        PageQuery query = PageQuery.of("3", "100", null, PlanService.DEFAULT_SORT_FIELD);

        PageResult<HedgePlan> result = service(dao).listPlans(1L, query, null);

        assertEquals(3, result.page());
        assertEquals(100, result.size());
    }

    /** 记录型假 DAO：捕获 where/args，仅实现 findPage。 */
    private static final class RecordingHedgePlanDao extends UnsupportedBaseDao<HedgePlan, Long>
            implements HedgePlanDao {

        private String where;
        private List<Object> args = Collections.emptyList();
        private int calls;

        @Override
        public PageResult<HedgePlan> findPage(PageQuery query, String where, List<Object> whereArgs) {
            this.where = where;
            this.args = whereArgs == null ? Collections.emptyList() : new ArrayList<>(whereArgs);
            this.calls++;
            return new PageResult<>(Collections.emptyList(), 0L, query.page(), query.size());
        }

        @Override
        public PageResult<HedgePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                String extraWhere, List<Object> extraArgs) {
            throw FakeDao.unsupported("findPageScoped");
        }

        @Override
        public HedgePlan findByIdScoped(long id, SubAccountScope scope) {
            throw FakeDao.unsupported("findByIdScoped");
        }

        @Override
        public HedgePlan findByPlanNo(String planNo) {
            throw FakeDao.unsupported("findByPlanNo");
        }

        @Override
        public int deleteById(Long id) {
            throw FakeDao.unsupported("deleteById");
        }
    }
}
