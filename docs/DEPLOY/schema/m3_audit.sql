-- =============================================================================
--  HedgeGuard 建表脚本 —— 模块 AUDIT（TB-AUDIT-1~8，共 8 张）
--  制品编号：docs/DEPLOY/schema/m3_audit.sql
--  依据：docs/DATA_MODEL.md v1.3.6（md5 201682e5aff5ee9f01fec9dd1728a4c5）§2.9 模块 AUDIT
--        §0.2 字段类型 / §0.3 通用审计字段 / §0.4 外键与级联 / §0.5 DM8 建表模板 / §4.3 约束目录
--  目标库：达梦 DM8；执行者：DBA（应用账号仅 SELECT/INSERT/UPDATE，NFR-SEC-8）
--
--  交付范围（终态）：TB-AUDIT-1~8 全部 8 张。
--  ⚠️ 只读原则（DATA_MODEL §2.9）：AUDIT 对其它模块数据只读，不反向写入业务数据（R-AUDIT-6）。
--  ⚠️ 通用审计字段（§0.3）每表都有，各表定义内联列出（id/created_by/created_time/updated_by/
--     updated_time/row_version）。审计时间默认值用 DM 语法 CURRENT_TIMESTAMP（§0.2 审计时间）。
--  ⚠️ 只增不改分类（CONTRACTS.md §2.3「只增不改表覆写 updateById 显式抛异常」）：
--     - t_operation_log / t_audit_log / t_audit_log_checksum 为「不可改删」留痕表
--       （DATA_MODEL §2.9 TB-AUDIT-1「只增不改不删」、TB-AUDIT-2「不可篡改」、§5.3 数据流表
--       「t_operation_log / t_audit_log / t_audit_log_checksum … 永久（不可改删）」）⇒ 其 DAO 拒绝 updateById。
--     - 其余五表（筛查运行 / 命中明细 / 报表 / 导出 / 重大事项）在 §2.9 未标注「不可改删」，
--       按可更新处理（报表提交、重大事项状态流转为业务必需）；不自行加强 FR 面。
--
--  建表顺序说明（表内依赖按 父→子 排列）：t_violation_screen_item → t_violation_screen_run；
--  t_evidence_export → t_audit_log；t_major_event → t_audit_report。跨模块父表 t_sub_account
--  （CONFIG/AUTH 文件）与 t_trade_reason（TRADE 文件）按 README §7.1 顺序须先于本文件执行。
--  本文件未在 DM8 实机执行（环境无实例，见 T-004/T-052）。
--
--  脚本纪律：无存储过程 / 触发器 / 自定义函数（PROJECT.md §6）；无 DML；DM 特性带 `-- DM-SPECIFIC:` 注释。
-- =============================================================================

-- ===== TB-AUDIT-1 t_operation_log 操作留痕 =====
-- 用途：系统内关键操作的记录（FR-AUDIT-11、R-AUDIT-3）。覆盖范围：计划提交/审批/终止、指令提交/
--   人工确认、风控拒绝、资金转账与划拨、权限与授权变更、系统配置变更、结算确认与差异处理、会计凭证审核。
-- 只增不改不删；量级最大（千万级）→ 建议按 operation_time 按月分区（DM8 分区为可选手段，§6）。
CREATE TABLE t_operation_log (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id              BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id  INT                         NULL,
    operator_id     BIGINT                      NOT NULL,
    operation_type  VARCHAR(48)                 NOT NULL,
    object_type     VARCHAR(32)                 NOT NULL,
    object_id       BIGINT                      NULL,
    object_no       VARCHAR(64)                 NULL,
    action_result   VARCHAR(24)                 NOT NULL,
    result_detail   VARCHAR(1000)               NULL,
    operation_time  TIMESTAMP                   NOT NULL,
    client_ip       VARCHAR(64)                 NULL,
    trace_id        VARCHAR(64)                 NULL,
    created_by      BIGINT                      NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by      BIGINT                      NULL,
    updated_time    TIMESTAMP                   NULL,
    row_version     INT           DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_operation_log PRIMARY KEY (id),
    CONSTRAINT ck_operation_log_action_result CHECK (action_result IN ('SUCCESS','FAIL','REJECTED')),
    CONSTRAINT fk_operation_log_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
);
COMMENT ON TABLE  t_operation_log IS '操作留痕（关键操作 who/when/对什么/做了什么/结果；只增不改不删，FR-AUDIT-11）';
COMMENT ON COLUMN t_operation_log.operation_type IS '操作类型，如 PLAN_SUBMIT/PLAN_APPROVE/ORDER_CONFIRM/PRE_TRADE_REJECT/FUND_TRANSFER_APPROVE/PERM_CHANGE/CONFIG_CHANGE/SETTLE_CONFIRM/VOUCHER_REVIEW（示例，非封闭枚举）';
COMMENT ON COLUMN t_operation_log.action_result IS 'SUCCESS / FAIL / REJECTED（ck_operation_log_action_result）';
CREATE INDEX idx_op_log_time_type     ON t_operation_log (operation_time, operation_type);
CREATE INDEX idx_op_log_operator_time ON t_operation_log (operator_id, operation_time);
CREATE INDEX idx_op_log_object        ON t_operation_log (object_type, object_id, operation_time);
CREATE INDEX idx_op_log_sub_time      ON t_operation_log (sub_account_id, operation_time);

-- ===== TB-AUDIT-2 t_audit_log 审计日志 =====
-- 用途：审计操作自身的记录（查询与导出）（FR-AUDIT-9、R-AUDIT-4）。不可篡改；写入失败须中断被记录的操作。
-- 防篡改机制（FR-AUDIT-10）：prev_hash / row_hash / seq_in_chain —— 改某行会使本行 row_hash 与下一行
--   prev_hash 不匹配 ⇒ 校验失败告警 + 阻断导出。seq_in_chain 由单线程（ARCHITECTURE §2.4.4 J7）分配，保证链不分支。
CREATE TABLE t_audit_log (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id              BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id  INT                         NULL,
    audit_user_id   BIGINT                      NOT NULL,
    action_type     VARCHAR(24)                 NOT NULL,
    query_condition VARCHAR(2000)               NULL,
    result_count    INT           DEFAULT 0     NOT NULL,
    export_file_id  VARCHAR(128)                NULL,
    chain_integrity VARCHAR(16)                 NULL,
    action_time     TIMESTAMP                   NOT NULL,
    prev_hash       VARCHAR(128)                NOT NULL,
    row_hash        VARCHAR(128)                NOT NULL,
    seq_in_chain    BIGINT                      NOT NULL,
    created_by      BIGINT                      NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by      BIGINT                      NULL,
    updated_time    TIMESTAMP                   NULL,
    row_version     INT           DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_audit_log PRIMARY KEY (id),
    CONSTRAINT uk_audit_log_chain_seq UNIQUE (seq_in_chain),
    CONSTRAINT ck_audit_log_action_type CHECK (action_type IN (
        'QUERY','EXPORT','VERIFY','TRACE_FORWARD','TRACE_BACKWARD','SCREEN')),
    CONSTRAINT ck_audit_log_chain_integrity CHECK (chain_integrity IN (
        'COMPLETE','HAS_BREAK','IN_FLIGHT','UNKNOWN')),
    CONSTRAINT fk_audit_log_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
);
COMMENT ON TABLE  t_audit_log IS '审计日志（审计查询/导出自身记录；不可篡改，FR-AUDIT-9/10；R-AUDIT-4）';
COMMENT ON COLUMN t_audit_log.chain_integrity IS 'COMPLETE / HAS_BREAK / IN_FLIGHT / UNKNOWN（DC-038）';
COMMENT ON COLUMN t_audit_log.prev_hash IS '前一行的行哈希（链式校验）';
COMMENT ON COLUMN t_audit_log.row_hash IS '本行哈希 = SHA-256(prev_hash ∥ 关键字段)';
COMMENT ON COLUMN t_audit_log.seq_in_chain IS '链内序号（单调递增；单线程分配，见 ARCHITECTURE §2.4.4 J7）';
CREATE INDEX idx_audit_log_user_time ON t_audit_log (audit_user_id, action_time);
CREATE INDEX idx_audit_log_time_type ON t_audit_log (action_time, action_type);
CREATE INDEX idx_audit_log_file      ON t_audit_log (export_file_id);

-- ===== TB-AUDIT-3 t_audit_log_checksum 审计日志校验值 =====
-- 用途：按批次的链头/链尾校验值（FR-AUDIT-10），支持增量校验（避免每次全校验全表）。
-- 不可改删（DATA_MODEL §5.3 数据流表）。created_time 即「批次生成时间」（§0.3 审计时间）。
CREATE TABLE t_audit_log_checksum (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                BIGINT        IDENTITY(1,1) NOT NULL,
    batch_no          BIGINT                      NOT NULL,
    batch_start_seq   BIGINT                      NOT NULL,
    batch_end_seq     BIGINT                      NOT NULL,
    batch_head_hash   VARCHAR(128)                NOT NULL,
    batch_tail_hash   VARCHAR(128)                NOT NULL,
    batch_checksum    VARCHAR(128)                NOT NULL,
    record_count      INT                         NOT NULL,
    created_by        BIGINT                      NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by        BIGINT                      NULL,
    updated_time      TIMESTAMP                   NULL,
    row_version       INT           DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_audit_log_checksum PRIMARY KEY (id),
    CONSTRAINT uk_audit_log_checksum_batch UNIQUE (batch_no)
);
COMMENT ON TABLE  t_audit_log_checksum IS '审计日志校验值（链头/链尾按批次；FR-AUDIT-10 增量校验）';
COMMENT ON COLUMN t_audit_log_checksum.batch_checksum IS '批次校验和 = SHA-256(链式累加)';
CREATE INDEX idx_audit_log_checksum_seq ON t_audit_log_checksum (batch_start_seq, batch_end_seq);

-- ===== TB-AUDIT-4 t_violation_screen_run 违规筛查运行 =====
-- 用途：每次违规筛查的条件、时间、执行人（FR-AUDIT-4、R-AUDIT-8）。筛查结论须可复现。
CREATE TABLE t_violation_screen_run (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                  BIGINT        IDENTITY(1,1) NOT NULL,
    screen_no           VARCHAR(32)                 NOT NULL,
    date_from           DATE                        NOT NULL,
    date_to             DATE                        NOT NULL,
    sub_account_scope   VARCHAR(500)                NOT NULL,
    screen_dimensions   VARCHAR(256)                NOT NULL,
    rule_version        VARCHAR(64)                 NULL,
    hit_count           INT           DEFAULT 0     NOT NULL,
    operator_id         BIGINT                      NOT NULL,
    screen_time         TIMESTAMP                   NOT NULL,
    conclusion          VARCHAR(500)                NOT NULL,
    created_by          BIGINT                      NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by          BIGINT                      NULL,
    updated_time        TIMESTAMP                   NULL,
    row_version         INT           DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_violation_screen_run PRIMARY KEY (id),
    CONSTRAINT uk_violation_screen_no UNIQUE (screen_no)
);
COMMENT ON TABLE  t_violation_screen_run IS '违规筛查运行（条件/时间/执行人；结论可复现，FR-AUDIT-4）';
COMMENT ON COLUMN t_violation_screen_run.screen_dimensions IS '筛查维度：超品种/超规模/超期限/超授权/频繁开平仓/交易理由缺失';
COMMENT ON COLUMN t_violation_screen_run.conclusion IS '结论；无命中时须明确「未发现违规」，不得空白（FR-AUDIT-4 验收 4）';
COMMENT ON COLUMN t_violation_screen_run.rule_version IS '所用规则口径版本（与 RISK 一致，R-AUDIT-7）';
CREATE INDEX idx_violation_screen_time  ON t_violation_screen_run (screen_time);
CREATE INDEX idx_violation_screen_range ON t_violation_screen_run (date_from, date_to);

-- ===== TB-AUDIT-5 t_violation_screen_item 筛查命中项 =====
-- 用途：筛查命中的具体记录（FR-AUDIT-4）。REASON_MISSING 与 RELATED_MISSING 为 Q-13 下并列独立的筛查维度（DC-014）。
CREATE TABLE t_violation_screen_item (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                  BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id      INT                         NOT NULL,
    screen_run_id       BIGINT                      NOT NULL,
    violation_type      VARCHAR(32)                 NOT NULL,
    severity            VARCHAR(16)                 NOT NULL,
    rule_id             VARCHAR(32)                 NULL,
    object_type         VARCHAR(32)                 NOT NULL,
    object_id           BIGINT                      NOT NULL,
    occur_time          TIMESTAMP                   NULL,
    violation_desc      VARCHAR(1000)               NOT NULL,
    trade_reason_id     BIGINT                      NULL,
    related_object_info VARCHAR(256)                NULL,
    created_by          BIGINT                      NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by          BIGINT                      NULL,
    updated_time        TIMESTAMP                   NULL,
    row_version         INT           DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_violation_screen_item PRIMARY KEY (id),
    CONSTRAINT ck_violation_screen_item_severity CHECK (severity IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT fk_violation_screen_item_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id),
    CONSTRAINT fk_violation_screen_item_run FOREIGN KEY (screen_run_id) REFERENCES t_violation_screen_run (id),
    CONSTRAINT fk_violation_screen_item_trade_reason FOREIGN KEY (trade_reason_id) REFERENCES t_trade_reason (id)
);
COMMENT ON TABLE  t_violation_screen_item IS '筛查命中项（FR-AUDIT-4；violation_type 与 t_risk_violation 同域）';
COMMENT ON COLUMN t_violation_screen_item.severity IS '严重级别 LOW / MEDIUM / HIGH（与 t_risk_violation.severity 同域）';
COMMENT ON COLUMN t_violation_screen_item.violation_desc IS '描述（含当前值/阈值）';
COMMENT ON COLUMN t_violation_screen_item.related_object_info IS '关联对象信息；violation_type=RELATED_MISSING 时记录缺失的具体项（Q-13）';
CREATE INDEX idx_screen_item_run_type ON t_violation_screen_item (screen_run_id, violation_type);
CREATE INDEX idx_screen_item_sub_time ON t_violation_screen_item (sub_account_id, occur_time);
CREATE INDEX idx_screen_item_object   ON t_violation_screen_item (object_type, object_id);

-- ===== TB-AUDIT-6 t_audit_report 审计报表 =====
-- 用途：季度报表 / 年度专项报告 / 重大事项专项报告（FR-AUDIT-5~7、FR-AUDIT-12）。
CREATE TABLE t_audit_report (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id    INT                         NULL,
    report_no         VARCHAR(32)                 NOT NULL,
    report_type       VARCHAR(32)                 NOT NULL,
    period_key        VARCHAR(16)                 NOT NULL,
    content_json      CLOB                        NOT NULL,
    dual_pnl_included CHAR(1)       DEFAULT 'Y'   NOT NULL,
    chain_integrity   VARCHAR(16)                 NOT NULL,
    break_list        CLOB                        NULL,
    data_gap_desc     VARCHAR(1000)               NULL,
    generated_by      BIGINT                      NOT NULL,
    generated_time    TIMESTAMP                   NOT NULL,
    submitted         CHAR(1)       DEFAULT 'N'   NOT NULL,
    submitted_to      VARCHAR(128)                NULL,
    submitted_time    TIMESTAMP                   NULL,
    created_by        BIGINT                      NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by        BIGINT                      NULL,
    updated_time      TIMESTAMP                   NULL,
    row_version       INT           DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_audit_report PRIMARY KEY (id),
    CONSTRAINT uk_audit_report_no UNIQUE (report_no),
    CONSTRAINT ck_audit_report_type CHECK (report_type IN ('QUARTERLY','ANNUAL_SPECIAL','MAJOR_EVENT')),
    CONSTRAINT ck_audit_report_dual_pnl CHECK (dual_pnl_included = 'Y'),
    CONSTRAINT ck_audit_report_integrity CHECK (chain_integrity IN (
        'COMPLETE','HAS_BREAK','IN_FLIGHT','UNKNOWN')),
    CONSTRAINT ck_audit_report_submitted CHECK (submitted IN ('Y','N')),
    CONSTRAINT fk_audit_report_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
);
COMMENT ON TABLE  t_audit_report IS '审计报表（季度/年度专项/重大事项；断点须显式标注，R-AUDIT-2）';
COMMENT ON COLUMN t_audit_report.dual_pnl_included IS '是否含双侧盈亏，须恒为 ''Y''（R-ACCT-4；ck_audit_report_dual_pnl）';
COMMENT ON COLUMN t_audit_report.chain_integrity IS 'COMPLETE / HAS_BREAK / IN_FLIGHT / UNKNOWN（数据缺失致不可判定时标 UNKNOWN，不得误标为完整）';
COMMENT ON COLUMN t_audit_report.break_list IS '断点清单（chain_integrity≠''COMPLETE'' 时非空）';
CREATE INDEX idx_audit_report_type_period ON t_audit_report (report_type, period_key);
CREATE INDEX idx_audit_report_integrity   ON t_audit_report (chain_integrity, generated_time);
CREATE INDEX idx_audit_report_sub_time    ON t_audit_report (sub_account_id, generated_time);

-- ===== TB-AUDIT-7 t_evidence_export 证据链导出记录 =====
-- 用途：证据链导出的记录（FR-AUDIT-8）。导出动作进入审计日志；存在断点须显式标注。
CREATE TABLE t_evidence_export (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                    BIGINT        IDENTITY(1,1) NOT NULL,
    export_no             VARCHAR(32)                 NOT NULL,
    start_point_type      VARCHAR(16)                 NOT NULL,
    start_point_ids       VARCHAR(2000)               NOT NULL,
    date_from             DATE                        NULL,
    date_to               DATE                        NULL,
    sub_account_scope     VARCHAR(500)                NOT NULL,
    include_source_detail CHAR(1)       DEFAULT 'N'   NOT NULL,
    chain_integrity       VARCHAR(16)                 NOT NULL,
    break_list            CLOB                        NULL,
    dual_pnl_included     CHAR(1)       DEFAULT 'Y'   NOT NULL,
    reason_included       CHAR(1)       DEFAULT 'Y'   NOT NULL,
    file_id               VARCHAR(128)                NOT NULL,
    file_path             VARCHAR(256)                NULL,
    exported_by           BIGINT                      NOT NULL,
    exported_time         TIMESTAMP                   NOT NULL,
    audit_log_id          BIGINT                      NULL,
    created_by            BIGINT                      NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                      NULL,
    updated_time          TIMESTAMP                   NULL,
    row_version           INT           DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_evidence_export PRIMARY KEY (id),
    CONSTRAINT uk_evidence_export_no UNIQUE (export_no),
    CONSTRAINT ck_evidence_export_start_point CHECK (start_point_type IN ('EXPOSURE','TRADE','VOUCHER')),
    CONSTRAINT ck_evidence_export_source_detail CHECK (include_source_detail IN ('Y','N')),
    CONSTRAINT ck_evidence_export_integrity CHECK (chain_integrity IN (
        'COMPLETE','HAS_BREAK','IN_FLIGHT','UNKNOWN')),
    CONSTRAINT ck_evidence_export_dual_pnl CHECK (dual_pnl_included = 'Y'),
    CONSTRAINT ck_evidence_export_reason CHECK (reason_included = 'Y'),
    CONSTRAINT fk_evidence_export_audit_log FOREIGN KEY (audit_log_id) REFERENCES t_audit_log (id)
);
COMMENT ON TABLE  t_evidence_export IS '证据链导出记录（FR-AUDIT-8；导出动作进入审计日志；断点须显式标注）';
COMMENT ON COLUMN t_evidence_export.start_point_type IS '追溯起点类型 EXPOSURE / TRADE / VOUCHER';
COMMENT ON COLUMN t_evidence_export.dual_pnl_included IS '是否含双侧盈亏，须恒为 ''Y''（ck_evidence_export_dual_pnl）';
COMMENT ON COLUMN t_evidence_export.reason_included IS '是否含交易理由，须恒为 ''Y''（ck_evidence_export_reason）';
COMMENT ON COLUMN t_evidence_export.audit_log_id IS '关联的审计日志（先写日志后导出；写失败则中断导出，R-AUDIT-4）';
CREATE INDEX idx_evidence_export_time      ON t_evidence_export (exported_time);
CREATE INDEX idx_evidence_export_integrity ON t_evidence_export (chain_integrity);
CREATE INDEX idx_evidence_export_audit_log ON t_evidence_export (audit_log_id);

-- ===== TB-AUDIT-8 t_major_event 重大事项 =====
-- 用途：重大事项的触发与专项报告关联（FR-AUDIT-7、R-AUDIT-5）。
CREATE TABLE t_major_event (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                    BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                         NULL,
    event_no              VARCHAR(32)                 NOT NULL,
    event_type            VARCHAR(32)                 NOT NULL,
    trigger_source        VARCHAR(128)                NOT NULL,
    event_desc            VARCHAR(2000)               NOT NULL,
    impact_scope          VARCHAR(1000)               NULL,
    handling_measures     VARCHAR(2000)               NULL,
    rectification_plan    VARCHAR(2000)               NULL,
    status                VARCHAR(24)                 NOT NULL,
    audit_report_id       BIGINT                      NULL,
    triggered_time        TIMESTAMP                   NOT NULL,
    created_by            BIGINT                      NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                      NULL,
    updated_time          TIMESTAMP                   NULL,
    row_version           INT           DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_major_event PRIMARY KEY (id),
    CONSTRAINT uk_major_event_no UNIQUE (event_no),
    CONSTRAINT ck_major_event_type CHECK (event_type IN (
        'AGG_INCONSISTENT_TIMEOUT','SCALE_REDLINE_HIT','EXCHANGE_INQUIRY',
        'FORCE_CLOSE_HINT_UNDISPOSED','LOSS_THRESHOLD')),
    CONSTRAINT ck_major_event_status CHECK (status IN ('TRIGGERED','REPORT_GENERATED','SUBMITTED')),
    CONSTRAINT fk_major_event_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id),
    CONSTRAINT fk_major_event_audit_report FOREIGN KEY (audit_report_id) REFERENCES t_audit_report (id)
);
COMMENT ON TABLE  t_major_event IS '重大事项（触发条件命中与专项报告关联；FR-AUDIT-7）';
COMMENT ON COLUMN t_major_event.event_type IS 'AGG_INCONSISTENT_TIMEOUT / SCALE_REDLINE_HIT / EXCHANGE_INQUIRY / FORCE_CLOSE_HINT_UNDISPOSED / LOSS_THRESHOLD';
COMMENT ON COLUMN t_major_event.status IS 'TRIGGERED（已触发待生成）/ REPORT_GENERATED（已生成）/ SUBMITTED（已提交）';
COMMENT ON COLUMN t_major_event.trigger_source IS '触发来源（关联的对象标识）';
CREATE INDEX idx_major_event_type_status ON t_major_event (event_type, status);
CREATE INDEX idx_major_event_time        ON t_major_event (triggered_time);
