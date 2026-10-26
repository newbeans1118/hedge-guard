package com.hedgeguard.dao;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hedgeguard.dao.testsupport.InMemoryDmEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-009} 播种条件闭合（复核缺陷 {@code TE-T009-001}）的验收证据
 * （验收 5：「按 data-init.sql §② 诚实披露表所列本模块承接项，完成播种（幂等），
 * 并在测试中断言播种后行数/关键字段」；{@code FR-CONFIG-14} / {@code FR-CONFIG-15} / {@code R-CONFIG-8}）。
 *
 * <p>本环境无 DM8 实例；与 {@link AuthSeedDataTest} 同一手法——先对
 * {@code docs/DEPLOY/schema/data-init.sql} 第 ④ 段做<b>结构断言</b>（行数 / 关键字段 / NOT EXISTS 幂等），
 * 再把第 ④ 段三段源行<b>逐行回放进 {@link InMemoryDmEngine}</b>（真实 DDL 语义：
 * CHECK / 唯一键 / NOT NULL 全部按 {@code m1_config_auth.sql} 强制），证明：
 * <ul>
 *   <li><b>播种行数</b>：{@code t_dict_item} = 2（仅代码实际消费域）/ {@code t_report_period_config} = 6 /
 *       {@code t_config_item} = 66（§11.1 全部 37 键 + §11.2 可持久化 28 键 + {@code CFG-26b}
 *       1 键〔{@code T-081b} 增补，{@code R-AUDIT-5} 条件①超时阈值〕）；</li>
 *   <li><b>关键字段</b>：R-CONFIG-8 下限值（0.90/0.80/12/30/6/10/5/3/0.02/0.05…）、
 *       敏感项零落盘（无 is_sensitive='Y' 行）、persist_scope / dispatch_to_ctp 按 §11.3；</li>
 *   <li><b>幂等</b>：三段 INSERT 均含 NOT EXISTS（自然键去重）＋引擎唯一约束对重复行必拒
 *       （uk_dict_item_type_code / uk_report_period_type / uk_config_item_key）。</li>
 * </ul>
 *
 * <p>「行数」口径：④ 段用 {@code INSERT ... SELECT ... FROM (SELECT ... UNION ALL ...)}，
 * 行数 = {@code UNION ALL} 计数 + 1（每行源行独占一行且列数一致）。
 */
class ConfigSeedDataTest {

    private static final String INIT_SQL_REL = "docs/DEPLOY/schema/data-init.sql";
    private static final String DDL_SQL_REL = "docs/DEPLOY/schema/m1_config_auth.sql";

    private String initSql;
    /** 第 ④ 段文本（从「④ CONFIG 模块任务播种」起，避免与 ①~③ 段互相污染）。 */
    private String section4;

    @BeforeEach
    void setUp() throws IOException {
        initSql = initSqlText();
        int at = initSql.indexOf("④ CONFIG 模块任务播种");
        assertTrue(at >= 0, "脚本缺第 ④ 段（T-009 承接段）");
        section4 = initSql.substring(at);
    }

    // ------------------------------------------------------------------ 结构断言

    @Test
    @DisplayName("播种行数：dict 2 / report 6 / config 66（T-009 验收 5 + T-081b 增补 1 键）")
    void seedsRowCounts() {
        assertEquals(2, rowCount(block(section4, "t_dict_item")), "t_dict_item 应为 2 行（仅代码消费域）");
        assertEquals(6, rowCount(block(section4, "t_report_period_config")), "t_report_period_config 应为 6 行");
        assertEquals(66, rowCount(block(section4, "t_config_item")),
                "t_config_item 应为 66 键（§11.1=37 + §11.2 可持久化=28 + CFG-26b T-081b 增补=1）");
    }

    @Test
    @DisplayName("幂等：④ 段三段 INSERT 均以 NOT EXISTS 按自然键去重")
    void seedsAreIdempotent() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("t_dict_item", "d.dict_type = src.dt AND d.item_code = src.code");
        keys.put("t_report_period_config", "r.report_type = src.rt");
        keys.put("t_config_item", "c.config_key = src.k");
        for (Map.Entry<String, String> e : keys.entrySet()) {
            String b = block(section4, e.getKey());
            assertTrue(b.contains("NOT EXISTS"), e.getKey() + " 播种须幂等（NOT EXISTS）");
            assertTrue(b.contains(e.getValue()),
                    e.getKey() + " 的去重自然键应为：" + e.getValue());
        }
    }

    @Test
    @DisplayName("§② 诚实披露表：三行状态已从「待 CONFIG」更新为「已交付：见第 ④ 段」")
    void section2StatusUpdated() {
        for (String table : Arrays.asList("t_report_period_config", "t_dict_item", "t_config_item")) {
            String line = lineContaining(initSql, "`" + table + "`");
            assertNotNull(line, "§② 表缺 " + table + " 行");
            assertTrue(line.contains("已交付：见第 ④ 段（T-009 承接）"),
                    table + " 的 §② 状态应已更新为已交付");
            assertFalse(line.contains("待 CONFIG"), table + " 不应残留「待 CONFIG」");
        }
        // 既有 AUTH 三行先例不受影响
        assertTrue(initSql.contains("已交付：见第 ③ 段（T-010）"), "AUTH 三行先例应保留");
        // 全文不应再有「待 CONFIG」残留（勘误零残留）
        assertFalse(initSql.contains("待 CONFIG"), "全文不应残留「待 CONFIG」");
    }

    @Test
    @DisplayName("R-CONFIG-8 下限：风控默认值不低于 INDEX.md §9.1 确认口径")
    void riskDefaultsMatchConfirmedBaseline() {
        String b = block(section4, "t_config_item");
        String[][] required = {
                {"'hedge.scale.base'", "'EXPOSURE_QTY'"},
                {"'hedge.scale.scope'", "'BOTH'"},
                {"'hedge.scale.ratio_commodity'", "'0.90'"},
                {"'hedge.scale.ratio_trade'", "'0.80'"},
                {"'risk.term.max_months'", "'12'"},
                {"'risk.term.grace_days'", "'30'"},
                {"'risk.term.action'", "'WARN_THEN_BLOCK'"},
                {"'risk.position.check_mode'", "'PRE_TRADE_AND_EOD'"},
                {"'risk.freq.intraday_warn'", "'6'"},
                {"'risk.freq.intraday_block'", "'10'"},
                {"'risk.freq.7d_rounds_warn'", "'5'"},
                {"'risk.freq.min_hold_days'", "'3'"},
                {"'risk.price.base'", "'PREV_SETTLEMENT'"},
                {"'risk.price.warn_pct'", "'0.02'"},
                {"'risk.price.block_pct'", "'0.05'"},
                {"'risk.limit.layers'", "'SUB_PRODUCT,SUB_INSTRUMENT,ACCOUNT_PRODUCT'"},
                {"'exposure.components'", "'CONTRACT_UNEXECUTED,INVENTORY,PRODUCTION_PLAN'"},
                {"'spot.price.gap_calc'", "'REJECT'"},
                {"'spot.price.gap_valuation'", "'CARRY_FORWARD_LABELED'"},
                {"'spot.price.scope'", "'GLOBAL'"},
                {"'settle.aggregation.block_scope'", "'DIFF_SUB_NON_CLOSE'"},
                {"'sub_account.lifecycle'", "'DISABLE_ONLY'"},
        };
        for (String[] kv : required) {
            assertTrue(rowLine(b, kv[0]).contains(kv[1]),
                    kv[0] + " 的默认值应 = " + kv[1] + "（INDEX.md §9.1 / R-CONFIG-8）");
        }
    }

    @Test
    @DisplayName("敏感项零落盘：④ 段不播种 is_sensitive='Y' 行；CFG-19/31/hg.web.ws.port 不在播种集")
    void noSensitiveRowsSeeded() {
        String b = block(section4, "t_config_item");
        // SELECT 骨架对全部行硬编码 is_sensitive='N'（sensitive_state=NULL）
        assertTrue(b.contains("src.val, 'N', NULL,"),
                "④-3 SELECT 骨架须对所有行硬编码 is_sensitive='N'");
        for (String forbidden : Arrays.asList("'hg.security.sensitive_fields'", "'hg.db.",
                "'hg.web.ws.port'")) {
            assertFalse(b.contains(forbidden),
                    forbidden + " 属零落盘/部署时填写项，不得播种（见 ④ 段头说明）");
        }
    }

    @Test
    @DisplayName("持久化属性按 §11.3：dispatch_to_ctp / persist_scope / 双人复核逐格核对（全 66 行）")
    void dispatchFlagsFollowSection113() {
        String b = block(section4, "t_config_item");
        String[][] rows = sourceRows(b);
        assertEquals(66, rows.length, "④-3 源行应为 66 行");
        // 下标：0=k 1=cat 2=val 3=vt 4=scope 5=lo 6=hi 7=ps 8=dsp 9=rev 10=qno
        for (String[] row : rows) {
                assertEquals(12, row.length, "源行应为 12 格（k..dscr）：" + row[0]);
        }
        // dispatch_to_ctp = Y 的代表行（§11 下发列 ✅）
        for (String key : Arrays.asList("'hedge.scale.base'", "'risk.limit.layers'",
                "'exposure.components'", "'risk.price.warn_pct'", "'hg.ctp.env_type'",
                "'hg.trade.confirm_timeout_ms'", "'hg.flow.dir'")) {
            assertEquals("Y", unquote(stripAlias(findRow(key, rows)[8])),
                    key + " 的 dispatch_to_ctp 应为 Y（§11 下发列 ✅）");
        }
        // dispatch_to_ctp = N 的代表行
        for (String key : Arrays.asList("'trade.reason.mode'", "'accounting.voucher.mode'",
                "'hg.web.root'", "'plan.expiry_remind_days'", "'risk.limit_usage_hint'",
                "'settle.stress.scenarios'")) {
            assertEquals("N", unquote(stripAlias(findRow(key, rows)[8])),
                    key + " 的 dispatch_to_ctp 应为 N（§11 下发列 ⬜）");
        }
        // persist_scope 恒 LOCAL_ADMIN（播种集内无 API_DISPATCH/NEVER 项）
        for (String[] row : rows) {
            assertEquals("LOCAL_ADMIN", unquote(stripAlias(row[7])),
                    row[0] + " 的 persist_scope 应为 LOCAL_ADMIN（§11.3）");
        }
        // 双人复核 = Y 的代表行（§11.1 复核列 ✅ / CFG-22 切实盘复核）
        for (String key : Arrays.asList("'hedge.scale.ratio_commodity'", "'risk.term.action'",
                "'settle.sign.roles'", "'hg.ctp.env_type'", "'accounting.subject.template'")) {
            assertEquals("Y", unquote(stripAlias(findRow(key, rows)[9])),
                    key + " 的 double_review_required 应为 Y");
        }
        // 双人复核 = N 的代表行（§11.1 复核列 ⬜）
        for (String key : Arrays.asList("'exposure.snapshot.mode'", "'plan.ratio.custom.mode'",
                "'spot.price.scope'", "'hg.auth.login_fail_limit'")) {
            assertEquals("N", unquote(stripAlias(findRow(key, rows)[9])),
                    key + " 的 double_review_required 应为 N");
        }
    }

    /** 在 66 行源行中取含指定 key 字面量的行。 */
    private static String[] findRow(String keyLiteral, String[][] rows) {
        for (String[] row : rows) {
            if (row.length > 0 && row[0].contains(keyLiteral)) {
                return row;
            }
        }
        throw new AssertionError("④-3 缺源行：" + keyLiteral);
    }

    @Test
    @DisplayName("CFG-26 点名：audit.major_event.loss_threshold 播种在案（默认 100000 / INT / 下限 1；T-188 读取源）")
    void majorEventLossThresholdSeeded() {
        String b = block(section4, "t_config_item");
        String[][] rows = sourceRows(b);
        String[] row = findRow("'audit.major_event.loss_threshold'", rows);
        // 下标：0=k 1=cat 2=val 3=vt 4=scope 5=lo 6=hi 7=ps 8=dsp 9=rev 10=qno（+11=dscr）
        assertEquals("100000", unquote(stripAlias(row[2])),
                "CFG-26 默认值应为 100000（T-188 缺键回退同值）");
        assertEquals("INT", unquote(stripAlias(row[3])), "CFG-26 取值类型应为 INT");
        assertEquals("1", unquote(stripAlias(row[5])), "CFG-26 下限应为 1（取值须 >0）");
        assertEquals("LOCAL_ADMIN", unquote(stripAlias(row[7])), "CFG-26 persist_scope 应为 LOCAL_ADMIN");
        assertEquals("N", unquote(stripAlias(row[8])), "CFG-26 dispatch_to_ctp 应为 N（Java 侧消费）");
    }

    @Test
    @DisplayName("CFG-26b 点名：audit.major_event.agg_inconsistency_timeout_hours 播种在案"
            + "（默认 24 / INT / 下限 1；T-081b 读取源，R-AUDIT-5 条件①超时阈值）")
    void aggInconsistencyTimeoutSeeded() {
        String b = block(section4, "t_config_item");
        String[][] rows = sourceRows(b);
        String[] row = findRow("'audit.major_event.agg_inconsistency_timeout_hours'", rows);
        // 下标：0=k 1=cat 2=val 3=vt 4=scope 5=lo 6=hi 7=ps 8=dsp 9=rev 10=qno（+11=dscr）
        assertEquals("24", unquote(stripAlias(row[2])),
                "CFG-26b 默认值应为 24（T-081b 缺键回退同值）");
        assertEquals("INT", unquote(stripAlias(row[3])), "CFG-26b 取值类型应为 INT");
        assertEquals("1", unquote(stripAlias(row[5])), "CFG-26b 下限应为 1（取值须 >0）");
        assertEquals("LOCAL_ADMIN", unquote(stripAlias(row[7])), "CFG-26b persist_scope 应为 LOCAL_ADMIN");
        assertEquals("N", unquote(stripAlias(row[8])), "CFG-26b dispatch_to_ctp 应为 N（Java 侧消费）");
    }

    @Test
    @DisplayName("报告周期：六类型齐全、is_mandatory 全 Y 且 is_enabled 全 Y（R-CONFIG-7）")
    void reportPeriodsCompleteAndMandatory() {
        String b = block(section4, "t_report_period_config");
        for (String rt : Arrays.asList("'DAILY_SETTLE'", "'DAILY_RISK'", "'MONTHLY_CHECK'",
                "'QUARTERLY_REPORT'", "'ANNUAL_REPORT'", "'MAJOR_EVENT'")) {
            assertTrue(b.contains(rt), "缺报告类型 " + rt);
        }
        // 每行源行形如 ... 'ROLE_A,ROLE_B' 后由 SELECT 骨架补 'Y','Y' —— 骨架须含 'Y', 'Y'
        assertTrue(b.contains("src.rt, src.pu, 1, 0, src.roles, 'Y', 'Y', 0"),
                "④-2 SELECT 骨架须对全部 6 行落 is_mandatory='Y' 且 is_enabled='Y'");
        // remind_role_codes 均为 ③ 段已播种角色
        for (String role : Arrays.asList("FIN_RISK", "TRADER", "SETTLER", "APPROVER", "AUDITOR")) {
            assertTrue(b.contains(role), "remind_role_codes 引用了未播种角色 " + role);
        }
        assertFalse(b.contains("MANAGE") || b.contains("'RISK'") || b.contains("FINANCE"),
                "remind_role_codes 须用 t_role 编码而非 settle.sign_role 词汇");
    }

    // ------------------------------------------------------------------ 引擎回放（真实 DDL 语义）

    private InMemoryDmEngine engine;

    @BeforeEach
    void loadEngine() throws Exception {
        engine = new InMemoryDmEngine();
        engine.executeScript(new String(Files.readAllBytes(repoRoot().resolve(DDL_SQL_REL)),
                StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("引擎回放：④ 段源行经真实 DDL（CHECK/唯一/NOT NULL）全部落库，行数与结构断言一致")
    void replaySeedsAgainstRealDdl() throws Exception {
        replayDict();
        replayReport();
        replayConfig();

        assertEquals(2, engine.table("t_dict_item").rows.size());
        assertEquals(6, engine.table("t_report_period_config").rows.size());
        assertEquals(66, engine.table("t_config_item").rows.size());

        // 关键行抽查（引擎内真实值）
        Map<String, Object> warn = findRow("t_config_item", "config_key", "risk.price.warn_pct");
        assertEquals("0.02", String.valueOf(warn.get("config_value")));
        assertEquals("0.02", String.valueOf(warn.get("default_value")));
        assertEquals("N", String.valueOf(warn.get("is_sensitive")));
        Map<String, Object> daily = findRow("t_report_period_config", "report_type", "DAILY_RISK");
        assertEquals("FIN_RISK", String.valueOf(daily.get("remind_role_codes")));
        assertEquals("Y", String.valueOf(daily.get("is_mandatory")));
        Map<String, Object> buy = findRow("t_dict_item", "item_code", "BUY");
        assertEquals("EXPOSURE_DIRECTION", String.valueOf(buy.get("dict_type")));
        assertEquals("ENABLED", String.valueOf(buy.get("status")));
        assertEquals("Y", String.valueOf(buy.get("is_system")));
    }

    @Test
    @DisplayName("幂等的数据库级兜底：重复行在引擎唯一约束下必拒（三表各拒一次）")
    void uniqueConstraintsRejectDuplicateSeedRows() throws Exception {
        replayDict();
        replayReport();
        replayConfig();

        SQLException dictEx = assertInsertFails(
                "INSERT INTO t_dict_item (dict_type, item_code, item_name, sort_no, status, is_system,"
                        + " created_by) VALUES ('EXPOSURE_DIRECTION', 'BUY', '买入', 1, 'ENABLED', 'Y', 0)");
        assertTrue(String.valueOf(dictEx.getMessage()).contains("uk_dict_item_type_code"),
                "重复 dict 行应撞 uk_dict_item_type_code，实际：" + dictEx.getMessage());

        SQLException reportEx = assertInsertFails(
                "INSERT INTO t_report_period_config (report_type, period_unit, period_value,"
                        + " remind_advance_days, remind_role_codes, is_mandatory, is_enabled, created_by)"
                        + " VALUES ('DAILY_RISK', 'DAY', 1, 0, 'FIN_RISK', 'Y', 'Y', 0)");
        assertTrue(String.valueOf(reportEx.getMessage()).contains("uk_report_period_type"),
                "重复报告行应撞 uk_report_period_type，实际：" + reportEx.getMessage());

        SQLException cfgEx = assertInsertFails(
                "INSERT INTO t_config_item (config_key, config_category, config_value, value_type,"
                        + " persist_scope, dispatch_to_ctp, created_by)"
                        + " VALUES ('risk.price.warn_pct', 'RISK_PARAM', '0.02', 'DECIMAL',"
                        + " 'LOCAL_ADMIN', 'Y', 0)");
        assertTrue(String.valueOf(cfgEx.getMessage()).contains("uk_config_item_key"),
                "重复配置行应撞 uk_config_item_key，实际：" + cfgEx.getMessage());
    }

    @Test
    @DisplayName("CHECK 域回放：枚举域/取值域与 DDL CHECK 一致（错值必拒的负控）")
    void checkDomainsRejectInvalidValues() {
        // 负控 1：config_category 出 CHECK 域外值
        SQLException catEx = assertInsertFails(
                "INSERT INTO t_config_item (config_key, config_category, config_value, value_type,"
                        + " persist_scope, dispatch_to_ctp, created_by)"
                        + " VALUES ('probe.neg.1', 'WRONG_CAT', 'x', 'STRING', 'LOCAL_ADMIN', 'N', 0)");
        assertTrue(String.valueOf(catEx.getMessage()).contains("ck_config_item_category"),
                "非法分类应撞 ck_config_item_category，实际：" + catEx.getMessage());
        // 负控 2：report period_unit 出 DAY/MONTH/QUARTER/YEAR 之外 → 撞 ck_report_period_unit
        //  （注：两列 OR 形的 ck_report_period_mandatory_enabled 超出引擎单列 CHECK 求值面——
        //   InMemoryDmEngine 已诚实披露该限制；该不变量由 ④-2 SELECT 骨架 'Y','Y' 硬编码保证）
        SQLException unitEx = assertInsertFails(
                "INSERT INTO t_report_period_config (report_type, period_unit, period_value,"
                        + " remind_advance_days, remind_role_codes, is_mandatory, is_enabled, created_by)"
                        + " VALUES ('PROBE_NEG', 'WEEK', 1, 0, 'FIN_RISK', 'Y', 'Y', 0)");
        assertTrue(String.valueOf(unitEx.getMessage()).contains("ck_report_period_unit"),
                "非法周期单位应撞 ck_report_period_unit，实际：" + unitEx.getMessage());
        // 负控 3：value_type 出域 → 撞 ck_config_item_value_type
        //  （注：两列 OR 形的 ck_config_item_sensitive 超出引擎单列 CHECK 求值面——
        //   InMemoryDmEngine 已诚实披露该限制；该不变量由 ④-3 SELECT 骨架硬编码 is_sensitive='N' 保证）
        SQLException senEx = assertInsertFails(
                "INSERT INTO t_config_item (config_key, config_category, config_value, value_type,"
                        + " is_sensitive, persist_scope, dispatch_to_ctp, created_by)"
                        + " VALUES ('probe.neg.2', 'SYSTEM', 'secret', 'BAD', 'N', 'LOCAL_ADMIN', 'N', 0)");
        assertTrue(String.valueOf(senEx.getMessage()).contains("ck_config_item_value_type"),
                "非法值类型应撞 ck_config_item_value_type，实际：" + senEx.getMessage());
    }

    // ------------------------------------------------------------------ 回放实现

    /** 把 ④-x 段的 SELECT 源行逐行解析为「列名 → 字面量」并按 VALUES 插入引擎（跳过 NULL 列）。 */
    private void replayDict() throws Exception {
        String[][] rows = sourceRows(block(section4, "t_dict_item"));
        String[] cols = {"dict_type", "item_code", "item_name", "item_desc", "sort_no"};
        String[] aliases = {"dt", "code", "name", "descr", "sort"};
        for (String[] row : rows) {
            insertRow("t_dict_item", cols, aliases, row, "status, is_system, created_by",
                    "'ENABLED', 'Y', 0");
        }
    }

    private void replayReport() throws Exception {
        String[][] rows = sourceRows(block(section4, "t_report_period_config"));
        String[] cols = {"report_type", "period_unit", "remind_role_codes"};
        String[] aliases = {"rt", "pu", "roles"};
        for (String[] row : rows) {
            insertRow("t_report_period_config", cols, aliases, row,
                    "period_value, remind_advance_days, is_mandatory, is_enabled, created_by",
                    "1, 0, 'Y', 'Y', 0");
        }
    }

    private void replayConfig() throws Exception {
        String[][] rows = sourceRows(block(section4, "t_config_item"));
        String[] cols = {"config_key", "config_category", "config_value", "value_type",
                "enum_scope", "min_value", "max_value", "persist_scope",
                "dispatch_to_ctp", "double_review_required", "related_question_no"};
        String[] aliases = {"k", "cat", "val", "vt", "scope", "lo", "hi", "ps", "dsp", "rev", "qno"};
        for (String[] row : rows) {
            insertRow("t_config_item", cols, aliases, row,
                    "default_value, is_sensitive, created_by",
                    "同 val, 'N', 0");
        }
    }

    /**
     * 从一段 INSERT..SELECT..UNION ALL 源行文本提取每条逻辑源行的字面量。
     * 口径：逻辑源行 = 以 {@code SELECT '} / {@code UNION ALL SELECT '} 开头，续行并入当前行，
     * 直至下一条源行开始；值 = 顶层逗号切分后的各段（去别名 {@code AS xxx}），
     * 保留引号原样（由 {@link #insertRow} 解析）。
     */
    private String[][] sourceRows(String insertBlock) {
        int end = insertBlock.lastIndexOf(");");
        if (end >= 0) {
            insertBlock = insertBlock.substring(0, end);
        }
        List<String> logical = new ArrayList<>();
        for (String rawLine : insertBlock.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("--")
                    || line.startsWith("INSERT ") || line.startsWith("SELECT src.")
                    || line.startsWith("SELECT 1 FROM")
                    || line.startsWith("FROM (") || line.startsWith(") src")
                    || line.startsWith("WHERE")) {
                continue;
            }
            if (line.startsWith("SELECT '") || line.startsWith("UNION ALL SELECT '")) {
                logical.add(line);
            } else if (!logical.isEmpty()) {
                logical.set(logical.size() - 1, logical.get(logical.size() - 1) + " " + line);
            }
        }
        List<String[]> rows = new ArrayList<>();
        for (String row : logical) {
            String body = row.startsWith("UNION ALL SELECT")
                    ? row.substring("UNION ALL SELECT".length()).trim()
                    : row.substring("SELECT".length()).trim();
            rows.add(splitTopLevel(body).toArray(new String[0]));
        }
        return rows.toArray(new String[0][]);
    }

    /** 深度 0 的逗号切分（尊重引号）。 */
    private static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quote = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                quote = !quote;
            } else if (!quote && ch == '(') {
                depth++;
            } else if (!quote && ch == ')') {
                depth--;
            }
            if (!quote && depth == 0 && ch == ',') {
                out.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        if (!cur.toString().trim().isEmpty()) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    /**
     * 按别名对应把源行值插入引擎：值列与别名列一一对应；{@code NULL} 字面量 → 省略该列（交由 DDL 默认）；
     * 尾部常量列（如 {@code status, is_system, created_by}）以字面量尾串插入。
     * {@code extraLiterals} 中占位词「同 val」表示复用本行 val 字面量（t_config_item.default_value = config_value）。
     */
    private void insertRow(String table, String[] cols, String[] aliases, String[] values,
                           String extraCols, String extraLiterals) throws Exception {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
        List<String> useCols = new ArrayList<>();
        List<String> useMarks = new ArrayList<>();
        String valLiteral = null;
        for (int i = 0; i < cols.length && i < values.length; i++) {
            String v = stripAlias(values[i]);
            if ("NULL".equalsIgnoreCase(v)) {
                continue;   // 引擎 VALUES 形态以省略列表达 NULL
            }
            if (aliases[i].equals("val")) {
                valLiteral = v;
            }
            useCols.add(cols[i]);
            useMarks.add(v);
        }
        for (String c : extraCols.split(",")) {
            useCols.add(c.trim());
        }
        for (String lit : extraLiterals.split(",")) {
            String l = lit.trim();
            if (l.equals("同 val")) {
                l = valLiteral;
            }
            useMarks.add(l);
        }
        sql.append(String.join(", ", useCols)).append(") VALUES (")
                .append(String.join(", ", useMarks)).append(")");
        try (Connection conn = engine.create();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.executeUpdate();
        }
    }

    /** 去 {@code AS alias} 尾注与首尾引号之外的空白，保留引号（引擎按字面量解析）。 */
    private static String stripAlias(String cell) {
        String v = cell.trim();
        int as = indexOfAsKeyword(v);
        if (as > 0) {
            v = v.substring(0, as).trim();
        }
        return v;
    }

    private static int indexOfAsKeyword(String v) {
        if (v.startsWith("'")) {
            int end = v.indexOf('\'', 1);
            if (end < 0) {
                return -1;
            }
            String tail = v.substring(end + 1).trim();
            return tail.startsWith("AS ") ? end + 1 : -1;
        }
        int as = v.toUpperCase(Locale.ROOT).lastIndexOf(" AS ");
        return as < 0 ? -1 : as;
    }

    private static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private SQLException assertInsertFails(String sql) {
        try (Connection conn = engine.create();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            return e;
        }
        throw new AssertionError("该 INSERT 应被 DDL 约束拒绝：" + sql);
    }

    private Map<String, Object> findRow(String table, String keyCol, String keyVal) {
        for (Map<String, Object> row : engine.table(table).rows) {
            if (keyVal.equals(String.valueOf(row.get(keyCol)))) {
                return row;
            }
        }
        throw new AssertionError(table + " 缺行 " + keyCol + "=" + keyVal);
    }

    // ------------------------------------------------------------------ 文本工具（与 AuthSeedDataTest 同族）

    private static String initSqlText() throws IOException {
        Path file = repoRoot().resolve(INIT_SQL_REL);
        assertTrue(Files.isRegularFile(file), "缺少初始化数据脚本：" + file);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String block(String sql, String table) {
        int at = sql.indexOf("INSERT INTO " + table);
        assertTrue(at >= 0, "脚本缺 " + table + " 的 INSERT（T-009 验收 5 未闭合）");
        int semi = sql.indexOf(");", at);
        return sql.substring(at, semi < 0 ? sql.length() : semi + 2);
    }

    private static int rowCount(String block) {
        return count(block, "UNION ALL") + 1;
    }

    private static int count(String text, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return n;
            }
            n++;
            from = idx + needle.length();
        }
    }

    private static String lineContaining(String text, String needle) {
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return null;
    }

    /** 取含指定 key 字面量的源行（④-3 每行一键）。 */
    private static String rowLine(String blockText, String keyLiteral) {
        for (String line : blockText.split("\n")) {
            String t = line.trim();
            if ((t.startsWith("SELECT '") || t.startsWith("UNION ALL SELECT '"))
                    && t.contains(keyLiteral)) {
                return t;
            }
        }
        throw new AssertionError("④ 段缺源行：" + keyLiteral);
    }

    /** 把 ④-3 源行切为 11 个别名格（k/cat/val/vt/scope/lo/hi/ps/dsp/rev/qno → 下标 0..10）。 */
    private String[] splitCells(String sourceLine) {
        String body = sourceLine.trim();
        if (body.startsWith("UNION ALL")) {
            body = body.substring("UNION ALL".length()).trim();
        }
        body = body.substring("SELECT".length()).trim();
        List<String> cells = splitTopLevel(body);
        assertEquals(11, cells.size(), "④-3 源行应为 11 格，实际行：" + sourceLine);
        String[] out = new String[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            out[i] = unquote(stripAlias(cells.get(i)));
        }
        return out;
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根（应含 docs/DATA_MODEL.md），当前目录="
                + Paths.get("").toAbsolutePath() + "；复现=在 java/ 目录下运行 mvn test");
    }
}
