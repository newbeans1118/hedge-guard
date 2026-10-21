-- =============================================================================
--  HedgeGuard 建表脚本 —— 模块 FUND（资金管理），9 张
--  制品编号：docs/DEPLOY/schema/m2_fund.sql
--  依据：docs/DATA_MODEL.md §2.11 FUND（TB-FUND-1~9，9 张）+ §0.2 字段类型
--        + §0.3 通用审计字段 + §0.4 外键与级联 + §0.5 DM8 建表模板 + §4.3 约束目录
--        + §4.6.3 子账户贯穿
--  目标库：达梦 DM8（PROJECT.md §5.1）
--  执行者：DBA（应用账号仅 SELECT/INSERT/UPDATE，禁 DDL —— NFR-SEC-8）
--
--  ⚠️ 未实测声明：本环境无 DM8 实例，本脚本未在 DM8 实机执行。
--     等价证据见 java 侧 FundSchemaTest（文档 ↔ 脚本逐列/索引/外键/子账户贯穿）
--     与 FundDm8EquivalentIntegrationTest（喂入内存引擎建表 → 真实 DAO 读写 → 约束强制）。
--
--  ⚠️ 执行顺序（跨文件前置）：
--     t_fund_ledger / t_fund_flow / t_deposit_withdraw_apply / t_transfer_apply /
--       t_capital_receipt / t_fund_review_record / t_fund_quota /
--       t_fund_quota_alert / t_fund_reconcile 均引用 t_sub_account（CONFIG，m1_config_auth.sql）；
--     t_fund_flow 还引用 t_settle_cycle（SETTLE，m2_settle.sql）与 t_trade_fill（TRADE，m2_trade.sql）；
--     t_transfer_apply / t_fund_reconcile 还引用 t_aggregation_check（SETTLE，m2_settle.sql）。
--     ⇒ 推荐顺序：m1_config_auth.sql → m1_product.sql → m1_spot.sql → m1_plan.sql
--        → m2_trade.sql → m2_settle.sql → 本文件 m2_fund.sql。
--     同文件内：t_fund_flow 引用 t_transfer_apply（TB-FUND-4）与 t_deposit_withdraw_apply
--        （TB-FUND-3），二者定义于本文件靠后，按先例置于文件末尾 ALTER TABLE 段。
--
--  ⚠️ 脚本纪律（DATA_MODEL.md §0.5）：无存储过程/触发器/自定义函数；无 DML；
--     DM 特性带 -- DM-SPECIFIC: 注释；外键一律 ON DELETE/UPDATE RESTRICT（§0.4）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 模块 FUND —— 9 张（子账户贯穿，§4.6.3）：① NOT NULL = 6；② 可空 = 3；③ 无该列 = 0
--   （② 可空 = t_fund_quota / t_fund_quota_alert / t_fund_reconcile，
--     皆因账户级（quota_scope='ACCOUNT' / 账户级告警 / 账户级对账）时 sub_account_id 为 NULL）
-- -----------------------------------------------------------------------------

-- ===== TB-FUND-1 t_fund_ledger 资金台账（按子账户 × 记账日的期初/发生/期末；可加总） =====
CREATE TABLE t_fund_ledger (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                        NOT NULL,
    ledger_date           DATE                       NOT NULL,
    begin_balance         DECIMAL(20,4)              NOT NULL,
    amount_in             DECIMAL(20,4) DEFAULT 0    NOT NULL,
    amount_out            DECIMAL(20,4) DEFAULT 0    NOT NULL,
    transfer_in           DECIMAL(20,4) DEFAULT 0    NOT NULL,
    transfer_out          DECIMAL(20,4) DEFAULT 0    NOT NULL,
    pnl_carryover         DECIMAL(20,4) DEFAULT 0    NOT NULL,
    commission            DECIMAL(20,4) DEFAULT 0    NOT NULL,
    margin_change         DECIMAL(20,4) DEFAULT 0    NOT NULL,
    difference_adjust     DECIMAL(20,4) DEFAULT 0    NOT NULL,
    end_balance           DECIMAL(20,4)              NOT NULL,
    available_fund        DECIMAL(20,4)              NULL,
    margin_occupied       DECIMAL(20,4)              NULL,
    equity                DECIMAL(20,4)              NULL,
    risk_ratio            DECIMAL(9,6)               NULL,
    is_closed             CHAR(1)      DEFAULT 'N'   NOT NULL,
    created_by            BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                     NULL,
    updated_time          TIMESTAMP                  NULL,
    row_version           INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_fund_ledger PRIMARY KEY (id),
    CONSTRAINT uk_fund_ledger_date_sub UNIQUE (ledger_date, sub_account_id),
    CONSTRAINT ck_fund_ledger_is_closed CHECK (is_closed IN ('Y', 'N')),
    CONSTRAINT fk_fund_ledger_sub FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_fund_ledger               IS '资金台账（各子账户期初/发生额/期末；可直接求和以支持加总对账，R-FUND-1）';
COMMENT ON COLUMN t_fund_ledger.sub_account_id IS '子账户（独立台账；可加总的关键维度）';
COMMENT ON COLUMN t_fund_ledger.ledger_date   IS '记账日';
COMMENT ON COLUMN t_fund_ledger.begin_balance IS '期初余额（自上日期末结转，不得手工改写）';
COMMENT ON COLUMN t_fund_ledger.end_balance   IS '期末余额；begin_balance + 发生额合计 = end_balance（R-FUND-2）';
COMMENT ON COLUMN t_fund_ledger.is_closed     IS '当日台账是否已结账（结账后 end_balance 冻结）';
CREATE INDEX idx_fund_ledger_date ON t_fund_ledger (ledger_date);
CREATE INDEX idx_fund_ledger_sub_date ON t_fund_ledger (sub_account_id, ledger_date);

-- ===== TB-FUND-2 t_fund_flow 资金流水（每笔带凭证编号；只增不改不删，R-FUND-2） =====
CREATE TABLE t_fund_flow (
    id                        BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id            INT                        NOT NULL,
    voucher_no                VARCHAR(32)                NOT NULL,
    ledger_date               DATE                       NOT NULL,
    flow_type                 VARCHAR(32)                NOT NULL,
    amount                    DECIMAL(20,4)              NOT NULL,
    direction                 VARCHAR(8)                 NOT NULL,
    balance_after             DECIMAL(20,4)              NOT NULL,
    transfer_apply_id         BIGINT                     NULL,
    deposit_withdraw_id       BIGINT                     NULL,
    settle_cycle_id           BIGINT                     NULL,
    trade_fill_id             BIGINT                     NULL,
    counterpart_sub_account_id INT                       NULL,
    operator_id               BIGINT                     NOT NULL,
    flow_time                 TIMESTAMP                  NOT NULL,
    remark                    VARCHAR(500)               NULL,
    double_review_required    CHAR(1)      DEFAULT 'N'   NOT NULL,
    created_by                BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time              TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                BIGINT                     NULL,
    updated_time              TIMESTAMP                  NULL,
    row_version               INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_fund_flow PRIMARY KEY (id),
    CONSTRAINT uk_fund_flow_voucher_no UNIQUE (voucher_no),
    CONSTRAINT ck_fund_flow_type CHECK (flow_type IN
        ('DEPOSIT','WITHDRAW','TRANSFER_OUT','TRANSFER_IN','PNL_CARRYOVER',
         'COMMISSION','MARGIN_CHANGE','DIFFERENCE_ADJUST')),
    CONSTRAINT ck_fund_flow_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_fund_flow_double_review CHECK (double_review_required IN ('Y', 'N')),
    CONSTRAINT fk_fund_flow_sub FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_fund_flow_counterpart FOREIGN KEY (counterpart_sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_fund_flow_settle FOREIGN KEY (settle_cycle_id) REFERENCES t_settle_cycle (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_fund_flow_trade_fill FOREIGN KEY (trade_fill_id) REFERENCES t_trade_fill (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_fund_flow                    IS '资金流水（台账中每一笔发生额；只增不改不删，R-FUND-2）';
COMMENT ON COLUMN t_fund_flow.voucher_no         IS '资金凭证编号，全系统唯一，一经分配不得修改（审计追溯引用）';
COMMENT ON COLUMN t_fund_flow.flow_type          IS '流水类型（入金/出金/划拨出/划拨入/盈亏结转/手续费/保证金占用变动/差异调整）';
COMMENT ON COLUMN t_fund_flow.balance_after      IS '该笔之后的子账户余额（支持期初 + 发生额 = 期末逐笔验证）';
COMMENT ON COLUMN t_fund_flow.transfer_apply_id  IS '子账户划拨单号（划拨的两条流水共用同一单号）';
COMMENT ON COLUMN t_fund_flow.counterpart_sub_account_id IS '对方子账户（划拨流水）';
COMMENT ON COLUMN t_fund_flow.double_review_required IS '是否须双人复核（DIFFERENCE_ADJUST 恒为 Y）';
CREATE INDEX idx_fund_flow_sub_date ON t_fund_flow (sub_account_id, ledger_date);
CREATE INDEX idx_fund_flow_type_date ON t_fund_flow (flow_type, ledger_date);
CREATE INDEX idx_fund_flow_transfer ON t_fund_flow (transfer_apply_id, flow_type);
CREATE INDEX idx_fund_flow_settle ON t_fund_flow (settle_cycle_id);
CREATE INDEX idx_fund_flow_date ON t_fund_flow (ledger_date);

-- ===== TB-FUND-3 t_deposit_withdraw_apply 出入金申请（双人复核；经 CTP；以回执为准） =====
CREATE TABLE t_deposit_withdraw_apply (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                        NOT NULL,
    apply_no              VARCHAR(32)                NOT NULL,
    idempotency_key       VARCHAR(64)                NOT NULL,
    direction             VARCHAR(8)                 NOT NULL,
    amount                DECIMAL(20,4)              NOT NULL,
    fund_source_desc      VARCHAR(500)               NULL,
    purpose_desc          VARCHAR(500)               NULL,
    status                VARCHAR(24)                NOT NULL,
    applicant_id          BIGINT                     NOT NULL,
    reviewer_id           BIGINT                     NULL,
    review_time           TIMESTAMP                  NULL,
    reject_reason         VARCHAR(500)               NULL,
    submit_time           TIMESTAMP                  NULL,
    available_before      DECIMAL(20,4)              NULL,
    retry_of_id           BIGINT                     NULL,
    original_confirmed    CHAR(1)      DEFAULT 'N'   NOT NULL,
    created_by            BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                     NULL,
    updated_time          TIMESTAMP                  NULL,
    row_version           INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_deposit_withdraw_apply PRIMARY KEY (id),
    CONSTRAINT uk_dw_apply_no UNIQUE (apply_no),
    CONSTRAINT uk_dw_apply_idem UNIQUE (idempotency_key),
    CONSTRAINT ck_dw_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_dw_status CHECK (status IN
        ('PENDING_SUBMIT','PENDING_REVIEW','SUBMITTED','SUCCESS','FAILED',
         'TIMEOUT_UNKNOWN','REJECTED')),
    CONSTRAINT ck_dw_original_confirmed CHECK (original_confirmed IN ('Y', 'N')),
    CONSTRAINT fk_dw_apply_sub FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_dw_apply_retry FOREIGN KEY (retry_of_id) REFERENCES t_deposit_withdraw_apply (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_deposit_withdraw_apply                  IS '出入金申请（须双人复核；经 CTP 发起；以期货公司回执为准，R-FUND-5）';
COMMENT ON COLUMN t_deposit_withdraw_apply.idempotency_key  IS '幂等标识（同一标识重复提交不得产生第二笔划转，FR-FUND-5）';
COMMENT ON COLUMN t_deposit_withdraw_apply.direction        IS '方向：IN 入金 / OUT 出金';
COMMENT ON COLUMN t_deposit_withdraw_apply.reviewer_id      IS '复核人（必须 ≠ applicant_id，R-FUND-3）';
COMMENT ON COLUMN t_deposit_withdraw_apply.available_before IS '出金前的子账户可用资金（出金前置校验依据）';
COMMENT ON COLUMN t_deposit_withdraw_apply.retry_of_id      IS '重试来源申请（不得因重试产生重复划转，FR-FUND-9）';
COMMENT ON COLUMN t_deposit_withdraw_apply.original_confirmed IS '重试前是否已确认原请求真实结果（未确认不得重试，FR-FUND-9）';
CREATE INDEX idx_dw_apply_sub_status ON t_deposit_withdraw_apply (sub_account_id, status);
CREATE INDEX idx_dw_apply_applicant ON t_deposit_withdraw_apply (applicant_id, apply_no);
CREATE INDEX idx_dw_apply_time ON t_deposit_withdraw_apply (submit_time);

-- ===== TB-FUND-4 t_transfer_apply 子账户间划拨申请（系统内记账；划拨后加总不变，R-FUND-4） =====
CREATE TABLE t_transfer_apply (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                        NOT NULL,
    from_sub_account_id   INT                        NOT NULL,
    to_sub_account_id     INT                        NOT NULL,
    apply_no              VARCHAR(32)                NOT NULL,
    -- R-FUND-4/FR-FUND-10（v1.3.9/T-202）：划拨幂等标识（同 t_deposit_withdraw_apply.idempotency_key 先例形态）
    idempotency_key       VARCHAR(64)                NOT NULL,
    amount                DECIMAL(20,4)              NOT NULL,
    reason                VARCHAR(500)               NOT NULL,
    expected_effective_date DATE                     NULL,
    status                VARCHAR(24)                NOT NULL,
    applicant_id          BIGINT                     NOT NULL,
    approver_id           BIGINT                     NULL,
    approve_time          TIMESTAMP                  NULL,
    reject_reason         VARCHAR(500)               NULL,
    agg_check_id          BIGINT                     NULL,
    agg_check_passed      CHAR(1)                    NULL,
    rollback_time         TIMESTAMP                  NULL,
    posting_time          TIMESTAMP                  NULL,
    created_by            BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                     NULL,
    updated_time          TIMESTAMP                  NULL,
    row_version           INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_transfer_apply PRIMARY KEY (id),
    CONSTRAINT uk_transfer_apply_no UNIQUE (apply_no),
    CONSTRAINT uk_transfer_apply_idem UNIQUE (idempotency_key),
    CONSTRAINT ck_transfer_apply_status CHECK (status IN
        ('PENDING_SUBMIT','PENDING_APPROVAL','PENDING_POSTING','POSTED','ROLLED_BACK','REJECTED')),
    CONSTRAINT ck_transfer_apply_diff_sub CHECK (from_sub_account_id <> to_sub_account_id),
    CONSTRAINT ck_transfer_apply_agg_passed CHECK (agg_check_passed IN ('Y', 'N')),
    CONSTRAINT fk_transfer_apply_sub FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transfer_apply_from FOREIGN KEY (from_sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transfer_apply_to FOREIGN KEY (to_sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transfer_apply_agg FOREIGN KEY (agg_check_id) REFERENCES t_aggregation_check (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_transfer_apply                    IS '子账户间划拨申请（系统内记账调整；划拨后加总须不变，R-FUND-4）';
COMMENT ON COLUMN t_transfer_apply.sub_account_id     IS '划出子账户（本表的子账户维度取划出方）';
COMMENT ON COLUMN t_transfer_apply.from_sub_account_id IS '划出子账户';
COMMENT ON COLUMN t_transfer_apply.to_sub_account_id  IS '划入子账户（必须 ≠ from_sub_account_id）';
COMMENT ON COLUMN t_transfer_apply.reason             IS '划拨理由（必填，FR-FUND-10）';
COMMENT ON COLUMN t_transfer_apply.idempotency_key    IS '幂等标识（同一标识重复提交不得产生第二笔划转，FR-FUND-10）';
COMMENT ON COLUMN t_transfer_apply.agg_check_id       IS '加总校验记录（划拨后须校验加总不变，FR-FUND-12）';
CREATE INDEX idx_transfer_apply_status ON t_transfer_apply (status, apply_no);
CREATE INDEX idx_transfer_apply_from ON t_transfer_apply (from_sub_account_id, status);
CREATE INDEX idx_transfer_apply_to ON t_transfer_apply (to_sub_account_id, status);
CREATE INDEX idx_transfer_apply_time ON t_transfer_apply (posting_time);

-- ===== TB-FUND-5 t_capital_receipt 资金回执（唯一到账依据；幂等） =====
CREATE TABLE t_capital_receipt (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                        NOT NULL,
    idempotency_key       VARCHAR(64)                NOT NULL,
    apply_id              BIGINT                     NOT NULL,
    result                VARCHAR(16)                NOT NULL,
    amount                DECIMAL(20,4)              NOT NULL,
    ctp_error_id          INT                        NULL,
    ctp_error_msg         VARCHAR(500)               NULL,
    receipt_ts_ms         BIGINT                     NOT NULL,
    receipt_time          TIMESTAMP                  NOT NULL,
    booked                CHAR(1)      DEFAULT 'N'   NOT NULL,
    query_confirmed       CHAR(1)      DEFAULT 'N'   NOT NULL,
    created_by            BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                     NULL,
    updated_time          TIMESTAMP                  NULL,
    row_version           INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_capital_receipt PRIMARY KEY (id),
    CONSTRAINT uk_capital_receipt_idem UNIQUE (idempotency_key, result, receipt_ts_ms),
    CONSTRAINT ck_capital_receipt_result CHECK (result IN ('SUCCESS', 'FAIL', 'PROCESSING', 'UNKNOWN')),
    CONSTRAINT ck_capital_receipt_booked CHECK (booked IN ('Y', 'N')),
    CONSTRAINT ck_capital_receipt_query_confirmed CHECK (query_confirmed IN ('Y', 'N')),
    CONSTRAINT fk_capital_receipt_sub FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_capital_receipt_apply FOREIGN KEY (apply_id) REFERENCES t_deposit_withdraw_apply (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_capital_receipt                IS '资金回执（期货公司经 CTP 返回的资金划转结果；唯一到账依据）';
COMMENT ON COLUMN t_capital_receipt.idempotency_key IS '幂等标识（重复回执须幂等处理）';
COMMENT ON COLUMN t_capital_receipt.result         IS '回执结果：SUCCESS / FAIL / PROCESSING / UNKNOWN（超时待查）';
COMMENT ON COLUMN t_capital_receipt.booked         IS '是否已据此记账（SUCCESS 且 booked=N → 待记账；FAIL 时恒为 N）';
COMMENT ON COLUMN t_capital_receipt.query_confirmed IS '是否为主动查询确认得到的结果（超时待查的最终定论）';
CREATE INDEX idx_capital_receipt_apply ON t_capital_receipt (apply_id, receipt_time);
CREATE INDEX idx_capital_receipt_booked ON t_capital_receipt (booked, result);
CREATE INDEX idx_capital_receipt_sub_time ON t_capital_receipt (sub_account_id, receipt_time);

-- ===== TB-FUND-6 t_fund_review_record 资金复核记录（双人复核；发起≠复核，R-FUND-3） =====
CREATE TABLE t_fund_review_record (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                        NOT NULL,
    biz_type              VARCHAR(24)                NOT NULL,
    biz_id                BIGINT                     NOT NULL,
    applicant_id          BIGINT                     NOT NULL,
    reviewer_id           BIGINT                     NULL,
    review_result         VARCHAR(16)                NULL,
    review_comment        VARCHAR(1000)              NULL,
    reject_reason         VARCHAR(500)               NULL,
    self_review_rejected  CHAR(1)      DEFAULT 'N'   NOT NULL,
    review_time           TIMESTAMP                  NOT NULL,
    created_by            BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                     NULL,
    updated_time          TIMESTAMP                  NULL,
    row_version           INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_fund_review_record PRIMARY KEY (id),
    CONSTRAINT ck_fund_review_biz_type CHECK (biz_type IN
        ('DEPOSIT_WITHDRAW','TRANSFER','DIFFERENCE_ADJUST','LEDGER_INIT')),
    CONSTRAINT ck_fund_review_result CHECK (review_result IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_fund_review_self_rejected CHECK (self_review_rejected IN ('Y', 'N')),
    CONSTRAINT fk_fund_review_sub FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_fund_review_record                     IS '资金复核记录（出入金/划拨/差异调整的双人复核留痕，R-FUND-3）';
COMMENT ON COLUMN t_fund_review_record.biz_type            IS '业务类型：DEPOSIT_WITHDRAW / TRANSFER / DIFFERENCE_ADJUST / LEDGER_INIT';
COMMENT ON COLUMN t_fund_review_record.reviewer_id         IS '复核人（必须 ≠ applicant_id）';
COMMENT ON COLUMN t_fund_review_record.self_review_rejected IS '是否因发起人=复核人被系统拒绝（越权尝试留痕，FR-FUND-7）';
CREATE INDEX idx_fund_review_biz ON t_fund_review_record (biz_type, biz_id);
CREATE INDEX idx_fund_review_reviewer ON t_fund_review_record (reviewer_id, review_time);
CREATE INDEX idx_fund_review_self_rejected ON t_fund_review_record (self_review_rejected);

-- ===== TB-FUND-7 t_fund_quota 资金额度（子账户/账户两级上限；不得跨子账户挪用） =====
CREATE TABLE t_fund_quota (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                        NULL,
    quota_scope           VARCHAR(16)                NOT NULL,
    quota_value           DECIMAL(20,4)              NOT NULL,
    effective_from        DATE                       NOT NULL,
    effective_to          DATE                       NULL,
    operator_id           BIGINT                     NOT NULL,
    change_reason         VARCHAR(500)               NULL,
    created_by            BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                     NULL,
    updated_time          TIMESTAMP                  NULL,
    row_version           INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_fund_quota PRIMARY KEY (id),
    CONSTRAINT uk_fund_quota_scope UNIQUE (quota_scope, sub_account_id, effective_from),
    CONSTRAINT ck_fund_quota_scope CHECK (quota_scope IN ('SUB_ACCOUNT', 'ACCOUNT')),
    CONSTRAINT fk_fund_quota_sub FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_fund_quota               IS '资金额度（子账户/账户两级上限；不得跨子账户挪用，FR-FUND-13）';
COMMENT ON COLUMN t_fund_quota.sub_account_id IS '子账户（quota_scope=ACCOUNT 时为 NULL）';
COMMENT ON COLUMN t_fund_quota.quota_scope    IS '额度范围：SUB_ACCOUNT 子账户级 / ACCOUNT 账户级';
COMMENT ON COLUMN t_fund_quota.quota_value    IS '额度值；必须 >= 0';
CREATE INDEX idx_fund_quota_sub ON t_fund_quota (sub_account_id, effective_from);
CREATE INDEX idx_fund_quota_scope ON t_fund_quota (quota_scope, effective_from);

-- ===== TB-FUND-8 t_fund_quota_alert 资金额度告警（告警不阻断减仓与出金，R-FUND-7） =====
CREATE TABLE t_fund_quota_alert (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                        NULL,
    quota_scope           VARCHAR(16)                NOT NULL,
    quota_value           DECIMAL(20,4)              NOT NULL,
    occupied_value        DECIMAL(20,4)              NOT NULL,
    alert_level           VARCHAR(16)                NOT NULL,
    alert_time            TIMESTAMP                  NOT NULL,
    blocked_operation     VARCHAR(24) DEFAULT 'NONE' NOT NULL,
    disposed              CHAR(1)      DEFAULT 'N'   NOT NULL,
    created_by            BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                     NULL,
    updated_time          TIMESTAMP                  NULL,
    row_version           INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_fund_quota_alert PRIMARY KEY (id),
    CONSTRAINT ck_fund_quota_alert_scope CHECK (quota_scope IN ('SUB_ACCOUNT', 'ACCOUNT')),
    CONSTRAINT ck_fund_quota_alert_level CHECK (alert_level IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_fund_quota_alert_blocked CHECK (blocked_operation = 'NONE'),
    CONSTRAINT ck_fund_quota_alert_disposed CHECK (disposed IN ('Y', 'N')),
    CONSTRAINT fk_fund_quota_alert_sub FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_fund_quota_alert                    IS '资金额度告警（触及上限告警；不阻断减仓与出金，R-FUND-7）';
COMMENT ON COLUMN t_fund_quota_alert.sub_account_id     IS '子账户（账户级告警为 NULL）';
COMMENT ON COLUMN t_fund_quota_alert.blocked_operation  IS '恒为 NONE（告警不阻断，FR-FUND-14 验收 3）';
COMMENT ON COLUMN t_fund_quota_alert.disposed           IS '是否已处置';
CREATE INDEX idx_fund_quota_alert_sub_time ON t_fund_quota_alert (sub_account_id, alert_time);
CREATE INDEX idx_fund_quota_alert_level ON t_fund_quota_alert (alert_level, alert_time);

-- ===== TB-FUND-9 t_fund_reconcile 资金对账（期初+发生额=期末；与 SETTLE 同口径，R-FUND-8） =====
CREATE TABLE t_fund_reconcile (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                        NULL,
    trade_date            DATE                       NOT NULL,
    reconcile_level       VARCHAR(16)                NOT NULL,
    begin_balance         DECIMAL(20,4)              NOT NULL,
    occurrence_total      DECIMAL(20,4)              NOT NULL,
    end_balance           DECIMAL(20,4)              NOT NULL,
    balance_equation_ok   CHAR(1)                    NOT NULL,
    agg_compare_value     DECIMAL(20,4)              NULL,
    real_account_value    DECIMAL(20,4)              NULL,
    agg_consistent        CHAR(1)                    NULL,
    result                VARCHAR(24)                NOT NULL,
    agg_check_id          BIGINT                     NULL,
    difference_list       CLOB                       NULL,
    reconcile_time        TIMESTAMP                  NOT NULL,
    operator_id           BIGINT                     NOT NULL,
    created_by            BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                     NULL,
    updated_time          TIMESTAMP                  NULL,
    row_version           INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_fund_reconcile PRIMARY KEY (id),
    CONSTRAINT uk_fund_reconcile_key UNIQUE (trade_date, reconcile_level, sub_account_id),
    CONSTRAINT ck_fund_reconcile_level CHECK (reconcile_level IN ('SUB_ACCOUNT', 'ACCOUNT')),
    CONSTRAINT ck_fund_reconcile_equation CHECK (balance_equation_ok IN ('Y', 'N')),
    CONSTRAINT ck_fund_reconcile_agg_consistent CHECK (agg_consistent IN ('Y', 'N')),
    CONSTRAINT ck_fund_reconcile_result CHECK (result IN
        ('CONSISTENT','INCONSISTENT','HAS_OPEN_DIFFERENCE')),
    CONSTRAINT fk_fund_reconcile_sub FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_fund_reconcile_agg FOREIGN KEY (agg_check_id) REFERENCES t_aggregation_check (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_fund_reconcile                     IS '资金对账（期初+发生额=期末；与 SETTLE 同口径同结果，R-FUND-8）';
COMMENT ON COLUMN t_fund_reconcile.sub_account_id      IS '子账户（子账户级对账非空；账户级为 NULL）';
COMMENT ON COLUMN t_fund_reconcile.balance_equation_ok IS '期初 + 发生额 = 期末 是否成立（FR-FUND-17 验收 1）';
COMMENT ON COLUMN t_fund_reconcile.agg_compare_value   IS '加总比对值（账户级对账时 = Σ 子账户；子账户级为 NULL）';
COMMENT ON COLUMN t_fund_reconcile.result              IS '对账结论：CONSISTENT / INCONSISTENT / HAS_OPEN_DIFFERENCE';
COMMENT ON COLUMN t_fund_reconcile.agg_check_id        IS '与 SETTLE 加总对账记录关联（不得两处结论不同，R-FUND-8）';
COMMENT ON COLUMN t_fund_reconcile.difference_list     IS '差异清单（不一致时非空）';
CREATE INDEX idx_fund_reconcile_date ON t_fund_reconcile (trade_date, reconcile_level);
CREATE INDEX idx_fund_reconcile_result ON t_fund_reconcile (result, trade_date);
CREATE INDEX idx_fund_reconcile_agg_check ON t_fund_reconcile (agg_check_id);

-- -----------------------------------------------------------------------------
-- 模块内后置外键（目标表定义于本文件靠后，故以 ALTER TABLE 声明；见 §0.4 级联约定）
-- -----------------------------------------------------------------------------
ALTER TABLE t_fund_flow ADD CONSTRAINT fk_fund_flow_transfer
    FOREIGN KEY (transfer_apply_id) REFERENCES t_transfer_apply (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE t_fund_flow ADD CONSTRAINT fk_fund_flow_dw
    FOREIGN KEY (deposit_withdraw_id) REFERENCES t_deposit_withdraw_apply (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

-- =============================================================================
--  本脚本明细自检
--    表数：9 = DATA_MODEL.md §2.11 FUND（TB-FUND-1~9）
--    子账户贯穿（§4.6.3）：① NOT NULL = 6（TB-FUND-1/2/3/4/5/6）；
--        ② 可空 = 3（TB-FUND-7/8/9，账户级时 NULL）；③ 无该列 = 0
--    逐列核对：字段/类型（含精度）/非空 与 §2.11 各表字段表逐行一致（含 §0.3 通用审计字段）
--    唯一键（幂等）：uk_fund_ledger_date_sub / uk_fund_flow_voucher_no /
--        uk_dw_apply_no / uk_dw_apply_idem（验收 4 出入金幂等键）/
--        uk_transfer_apply_no / uk_capital_receipt_idem（回执幂等）/ uk_fund_quota_scope /
--        uk_fund_reconcile_key
--    具名业务不变量 CHECK：ck_transfer_apply_diff_sub（划出≠划入）/
--        ck_fund_quota_alert_blocked（告警不阻断：blocked_operation = 'NONE'）
--    凭证编号：t_fund_flow.voucher_no NOT NULL + UNIQUE（验收 3 每笔有凭证编号）
--    验收 2 落点：t_fund_ledger.sub_account_id / t_fund_flow.sub_account_id 均 NOT NULL，
--        ledger 各发生额列可加总；begin_balance + 发生额合计 = end_balance 由服务层校验
--    未落 DB 的规则（服务层/内存权威，§4.4）：发起人=复核人、驳因必填、金额>0、
--        额度值>=0、期初+发生额=期末、加总=真实账户、重试前须确认原请求 —— 不得用触发器实现。
-- =============================================================================
