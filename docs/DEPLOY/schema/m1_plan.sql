-- =============================================================================
--  HedgeGuard 建表脚本 —— 模块 PLAN（套保计划），8 张
--  制品编号：docs/DEPLOY/schema/m1_plan.sql
--  依据：docs/DATA_MODEL.md v1.3.2（md5 99b647ec3e7a29b95ee86b04a58c747c）
--        §0.1~§0.5 全局约定 / §2.5 PLAN（TB-PLAN-1~8）/ §3.1 枚举 / §4.3 / §4.6.3
--  目标库：达梦 DM8（PROJECT.md §5.1）
--  执行者：DBA（应用账号仅 SELECT/INSERT/UPDATE，禁 DDL —— NFR-SEC-8）
--
--  ⚠️ 执行顺序：本模块全部 8 张表引用 `t_sub_account`（CONFIG）；6 张引用 `t_product`（PRODUCT）；
--     `t_hedge_plan_exposure` / `t_hedge_ratio_calc` / `t_hedge_doc` / `t_trade_plan` 引用
--     `t_exposure`（SPOT）。故须在 m1_config_auth.sql → m1_product.sql → m1_spot.sql **之后**执行。
--
--  ⚠️ 跨模块外键（须在执行前确认目标表已建）：
--     `fk_trade_plan_strategy` → `t_option_strategy`（TB-TRADE-8，**TRADE 模块，本里程碑不交付**，
--     由 `#java` 的 T-043 交付）。该表未建时本外键须**暂缓执行**（见文件末尾 ALTER TABLE 段）。
--
--  ⚠️ 脚本纪律（DATA_MODEL.md §0.5）：无存储过程/触发器/自定义函数；无 DML；
--     DM 特性带 `-- DM-SPECIFIC:` 注释；外键一律 ON DELETE/UPDATE RESTRICT（§0.4）。
--
--  ⚠️ 未实测声明：本环境无 DM8 实例，本脚本**未在 DM8 实机执行**。
--     静态校验证据见 java 侧 M1ProductSpotPlanSchemaTest。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 模块 PLAN —— 8 张（交易正当性依据；PLAN 模块 8/8 全部携带 NOT NULL 子账户标识）
-- 子账户贯穿（§4.6.3）：① NOT NULL = 8；② 可空 = 0；③ 无该列 = 0
-- -----------------------------------------------------------------------------

-- ===== TB-PLAN-1 t_hedge_plan 套保计划 =====
-- 计划必须从实货敞口出发（R-PLAN-1）；plan_no 一经分配不得修改。
CREATE TABLE t_hedge_plan (
    id                   BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id       INT                       NOT NULL,
    plan_no              VARCHAR(32)               NOT NULL,
    plan_type            VARCHAR(16)               NOT NULL,
    product_id           BIGINT                    NOT NULL,
    hedge_direction      VARCHAR(16)               NOT NULL,
    plan_qty             DECIMAL(20,4)             NOT NULL,
    unit                 VARCHAR(16)               NOT NULL,
    term_start_date      DATE                      NOT NULL,
    term_end_date        DATE                      NOT NULL,
    hedge_ratio          DECIMAL(9,6)              NOT NULL,
    ratio_method         VARCHAR(24)               NOT NULL,
    calc_base_date       DATE                      NOT NULL,
    status               VARCHAR(24)               NOT NULL,
    executed_qty         DECIMAL(20,4) DEFAULT 0   NOT NULL,
    ordered_qty          DECIMAL(20,4) DEFAULT 0   NOT NULL,
    applicant_id         BIGINT                    NOT NULL,
    current_doc_version  INT          DEFAULT 0    NOT NULL,
    freeze_reason        VARCHAR(500)              NULL,
    terminate_reason     VARCHAR(500)              NULL,
    extend_basis         VARCHAR(500)              NULL,
    quota_occupied       CHAR(1)      DEFAULT 'N'  NOT NULL,
    row_version          INT          DEFAULT 0    NOT NULL,
    created_by           BIGINT                    NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by           BIGINT                    NULL,
    updated_time         TIMESTAMP                 NULL,
    CONSTRAINT pk_t_hedge_plan            PRIMARY KEY (id),
    CONSTRAINT uk_hedge_plan_no           UNIQUE (plan_no),
    CONSTRAINT ck_hedge_plan_type         CHECK (plan_type IN ('COMMODITY','TRADE')),
    CONSTRAINT ck_hedge_plan_direction    CHECK (hedge_direction IN ('BUY_HEDGE','SELL_HEDGE')),
    CONSTRAINT ck_hedge_plan_ratio        CHECK (hedge_ratio > 0 AND hedge_ratio <= 1),
    CONSTRAINT ck_hedge_plan_method       CHECK (ratio_method IN ('SIMPLE','MIN_VARIANCE','CUSTOM_RULE')),
    CONSTRAINT ck_hedge_plan_qty_positive CHECK (plan_qty > 0),
    CONSTRAINT ck_hedge_plan_status       CHECK (status IN
        ('DRAFT','PENDING_APPROVAL','APPROVED','FROZEN','COMPLETED','TERMINATED')),
    CONSTRAINT ck_hedge_plan_period       CHECK (term_end_date >= term_start_date),
    CONSTRAINT ck_hedge_plan_quota_flag   CHECK (quota_occupied IN ('Y','N')),
    -- 额度占用与状态联动（R-PLAN-5）：仅 APPROVED / FROZEN 可占用额度
    CONSTRAINT ck_hedge_plan_quota_state  CHECK (quota_occupied <> 'Y'
        OR status IN ('APPROVED','FROZEN')),
    CONSTRAINT ck_hedge_plan_freeze       CHECK (status <> 'FROZEN' OR freeze_reason IS NOT NULL),
    CONSTRAINT ck_hedge_plan_terminate    CHECK (status <> 'TERMINATED' OR terminate_reason IS NOT NULL),
    CONSTRAINT fk_hedge_plan_sub          FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_hedge_plan_product      FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
-- DM-SPECIFIC: 表/列注释语法
COMMENT ON TABLE  t_hedge_plan                     IS '套保计划（经审批的套期保值方案；已批准计划不得直接修改要素，R-PLAN-6）';
COMMENT ON COLUMN t_hedge_plan.quota_occupied      IS '是否正在占用额度；仅 APPROVED/FROZEN 为 Y（R-PLAN-5）';
COMMENT ON COLUMN t_hedge_plan.executed_qty        IS '已成交数量（由成交回报自动回写，不得手工填写）';
CREATE INDEX idx_hedge_plan_sub_status     ON t_hedge_plan (sub_account_id, status);
CREATE INDEX idx_hedge_plan_product_status ON t_hedge_plan (product_id, status);
CREATE INDEX idx_hedge_plan_term           ON t_hedge_plan (term_end_date, status);
CREATE INDEX idx_hedge_plan_applicant      ON t_hedge_plan (applicant_id, status);
CREATE INDEX idx_hedge_plan_quota          ON t_hedge_plan (quota_occupied, sub_account_id);

-- ===== TB-PLAN-2 t_hedge_plan_exposure 计划-敞口绑定 =====
-- 追溯链「实货敞口 ↔ 套保计划」的物理连接点（FR-PLAN-19）。
CREATE TABLE t_hedge_plan_exposure (
    id                BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id    INT                       NOT NULL,
    hedge_plan_id     BIGINT                    NOT NULL,
    exposure_id       BIGINT                    NOT NULL,
    covered_qty       DECIMAL(20,4)             NOT NULL,
    bind_time         TIMESTAMP                 NOT NULL,
    unbind_time       TIMESTAMP                 NULL,
    status            VARCHAR(24)               NOT NULL,
    created_by        BIGINT                    NOT NULL,
    created_time      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by        BIGINT                    NULL,
    updated_time      TIMESTAMP                 NULL,
    row_version       INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_hedge_plan_exposure    PRIMARY KEY (id),
    CONSTRAINT uk_plan_exposure            UNIQUE (hedge_plan_id, exposure_id),
    CONSTRAINT ck_plan_exposure_qty        CHECK (covered_qty > 0),
    CONSTRAINT ck_plan_exposure_status     CHECK (status IN ('BOUND','UNBOUND')),
    CONSTRAINT ck_plan_exposure_unbind     CHECK (status <> 'UNBOUND' OR unbind_time IS NOT NULL),
    CONSTRAINT fk_plan_exposure_sub        FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_plan_exposure_plan       FOREIGN KEY (hedge_plan_id)
        REFERENCES t_hedge_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_plan_exposure_exposure   FOREIGN KEY (exposure_id)
        REFERENCES t_exposure (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_hedge_plan_exposure                 IS '计划-敞口绑定（N:M 连接表，仍显式携带 sub_account_id 以便跨子账户一致性核对）';
COMMENT ON COLUMN t_hedge_plan_exposure.sub_account_id  IS '必须与计划、与敞口三者一致（R-PLAN-7）';
CREATE INDEX idx_plan_exposure_exposure ON t_hedge_plan_exposure (exposure_id, status);
CREATE INDEX idx_plan_exposure_plan     ON t_hedge_plan_exposure (hedge_plan_id, status);
CREATE INDEX idx_plan_exposure_sub      ON t_hedge_plan_exposure (sub_account_id, status);

-- ===== TB-PLAN-3 t_hedge_ratio_calc 套保比率计算留痕 =====
-- 必须可被第三方复算（R-PLAN-4）；失败时 output_ratio 填 0 且 result_code 表明原因。
CREATE TABLE t_hedge_ratio_calc (
    id                  BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id      INT                       NOT NULL,
    hedge_plan_id       BIGINT                    NULL,
    exposure_id         BIGINT                    NULL,
    ratio_method        VARCHAR(24)               NOT NULL,
    input_json          CLOB                      NULL,
    output_ratio        DECIMAL(9,6)              NOT NULL,
    rho                 DECIMAL(20,6)             NULL,
    sigma_spot          DECIMAL(20,6)             NULL,
    sigma_futures       DECIMAL(20,6)             NULL,
    sample_count        INT                       NULL,
    sample_start_date   DATE                      NULL,
    sample_end_date     DATE                      NULL,
    custom_rule_id      BIGINT                    NULL,
    custom_rule_desc    VARCHAR(500)              NULL,
    applied_ratio_cap   DECIMAL(9,6)              NULL,
    applied_rule_id     BIGINT                    NULL,
    hit_rule_count      INT                       NULL,
    result_code         VARCHAR(32)               NOT NULL,
    fail_detail         VARCHAR(1000)             NULL,
    operator_id         BIGINT                    NOT NULL,
    calc_time           TIMESTAMP                 NOT NULL,
    created_by          BIGINT                    NOT NULL,
    created_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by          BIGINT                    NULL,
    updated_time        TIMESTAMP                 NULL,
    row_version         INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_hedge_ratio_calc      PRIMARY KEY (id),
    CONSTRAINT ck_ratio_calc_method       CHECK (ratio_method IN ('SIMPLE','MIN_VARIANCE','CUSTOM_RULE')),
    CONSTRAINT ck_ratio_calc_result       CHECK (result_code IN
        ('SUCCESS','INSUFFICIENT_SAMPLE','SIGMA_FUTURES_ZERO','MISSING_PRICE',
         'RATIO_OUT_OF_RANGE','RULE_MISSING','EXCEEDS_CUSTOM_CAP')),
    CONSTRAINT ck_ratio_calc_ratio_range  CHECK (output_ratio >= 0 AND output_ratio <= 1),
    CONSTRAINT ck_ratio_calc_fail_zero    CHECK (result_code = 'SUCCESS' OR output_ratio = 0),
    CONSTRAINT ck_ratio_calc_sample_range CHECK (sample_end_date IS NULL OR sample_start_date IS NULL
                                                 OR sample_end_date >= sample_start_date),
    CONSTRAINT fk_ratio_calc_sub          FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ratio_calc_plan         FOREIGN KEY (hedge_plan_id)
        REFERENCES t_hedge_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_ratio_calc_exposure     FOREIGN KEY (exposure_id)
        REFERENCES t_exposure (id) ON DELETE RESTRICT ON UPDATE RESTRICT
    -- custom_rule_id / applied_rule_id → t_custom_hedge_rule：目标表在本模块内后建，
    -- 故其外键在文件末尾以 ALTER TABLE 声明（见「模块内后置外键」段）
);
COMMENT ON TABLE  t_hedge_ratio_calc                    IS '套保比率计算留痕（方法/输入/输出/样本数/生效上限，可复算）';
COMMENT ON COLUMN t_hedge_ratio_calc.result_code        IS '仅 SUCCESS 写有效比率；其余失败原因须显式记录，不得静默降级';
COMMENT ON COLUMN t_hedge_ratio_calc.applied_rule_id    IS '多套规则叠加时最终采用的那一条（ratio_cap 升序 → priority 升序 → id 升序）';
CREATE INDEX idx_ratio_calc_plan        ON t_hedge_ratio_calc (hedge_plan_id, calc_time);
CREATE INDEX idx_ratio_calc_exposure    ON t_hedge_ratio_calc (exposure_id, calc_time);
CREATE INDEX idx_ratio_calc_method_time ON t_hedge_ratio_calc (ratio_method, calc_time);
CREATE INDEX idx_ratio_calc_result      ON t_hedge_ratio_calc (result_code, calc_time);

-- ===== TB-PLAN-4 t_hedge_doc 套期关系书面文件（多版本） =====
-- CAS 24 法定留痕：三要素齐备方可生成；未生成的计划不得提交审批（R-PLAN-8）。
-- 本表为 PLAN 所有者；ACCT（M4）只读引用，不复制文档本体。
CREATE TABLE t_hedge_doc (
    id                         BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id             INT                       NOT NULL,
    hedge_plan_id              BIGINT                    NOT NULL,
    doc_no                     VARCHAR(32)               NOT NULL,
    doc_version                INT                       NOT NULL,
    is_current                 CHAR(1)      DEFAULT 'Y'  NOT NULL,
    elem_formal_designation    CLOB                      NOT NULL,
    elem_effectiveness_method  CLOB                      NOT NULL,
    elem_rebalance_mechanism   CLOB                      NOT NULL,
    hedged_item_exposure_id    BIGINT                    NOT NULL,
    hedging_instrument         VARCHAR(256)              NOT NULL,
    custom_rule_id             BIGINT                    NULL,
    pending_external_review    CHAR(1)      DEFAULT 'Y'  NOT NULL,
    generated_by               BIGINT                    NOT NULL,
    generated_time             TIMESTAMP                 NOT NULL,
    invalidated_time           TIMESTAMP                 NULL,
    created_by                 BIGINT                    NOT NULL,
    created_time               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                    NULL,
    updated_time               TIMESTAMP                 NULL,
    row_version                INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_hedge_doc              PRIMARY KEY (id),
    CONSTRAINT uk_hedge_doc_no             UNIQUE (doc_no),
    CONSTRAINT ck_hedge_doc_current        CHECK (is_current IN ('Y','N')),
    -- R-ACCT-10：所有套期会计文档必须标注待外部复核，且标注不得被移除
    CONSTRAINT ck_hedge_doc_pending_review CHECK (pending_external_review = 'Y'),
    CONSTRAINT fk_hedge_doc_sub            FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_hedge_doc_plan           FOREIGN KEY (hedge_plan_id)
        REFERENCES t_hedge_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_hedge_doc_exposure       FOREIGN KEY (hedged_item_exposure_id)
        REFERENCES t_exposure (id) ON DELETE RESTRICT ON UPDATE RESTRICT
    -- custom_rule_id → t_custom_hedge_rule：后置 ALTER TABLE 声明
);
COMMENT ON TABLE  t_hedge_doc                          IS '套期关系书面文件（多版本；三要素列 NOT NULL → 缺要素写不进库）';
COMMENT ON COLUMN t_hedge_doc.pending_external_review  IS '恒为 Y（R-ACCT-10），数据库级保证标注不可被移除';
-- DM-SPECIFIC: 条件唯一（部分索引）语法；非标准，换库需按 §6.1 D6 改写
CREATE UNIQUE INDEX uk_hedge_doc_current ON t_hedge_doc (hedge_plan_id) WHERE is_current = 'Y';
CREATE INDEX idx_hedge_doc_plan_version ON t_hedge_doc (hedge_plan_id, doc_version);
CREATE INDEX idx_hedge_doc_sub          ON t_hedge_doc (sub_account_id, generated_time);

-- ===== TB-PLAN-5 t_plan_approval 计划审批链 =====
-- 永久留痕、不可修改；approver_id 必须 ≠ applicant_id（R-PLAN-2，服务层校验）。
CREATE TABLE t_plan_approval (
    id                 BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id     INT                       NOT NULL,
    hedge_plan_id      BIGINT                    NOT NULL,
    node_seq           INT                       NOT NULL,
    node_type          VARCHAR(24)               NOT NULL,
    applicant_id       BIGINT                    NOT NULL,
    approver_id        BIGINT                    NULL,
    approval_result    VARCHAR(16)               NULL,
    approval_comment   VARCHAR(1000)             NULL,
    reject_reason      VARCHAR(500)              NULL,
    precheck_result    CLOB                      NULL,
    action_time        TIMESTAMP                 NOT NULL,
    created_by         BIGINT                    NOT NULL,
    created_time       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by         BIGINT                    NULL,
    updated_time       TIMESTAMP                 NULL,
    row_version        INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_plan_approval         PRIMARY KEY (id),
    CONSTRAINT ck_plan_approval_node_type CHECK (node_type IN
        ('SUBMIT','APPROVE','REJECT','WITHDRAW','FREEZE','UNFREEZE','TERMINATE','EXTEND')),
    CONSTRAINT ck_plan_approval_result    CHECK (approval_result IS NULL
        OR approval_result IN ('APPROVED','REJECTED','WITHDRAWN')),
    CONSTRAINT ck_plan_approval_reject    CHECK (node_type <> 'REJECT' OR reject_reason IS NOT NULL),
    CONSTRAINT ck_plan_approval_no_self   CHECK (approver_id IS NULL OR approver_id <> applicant_id),
    CONSTRAINT fk_plan_approval_sub       FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_plan_approval_plan      FOREIGN KEY (hedge_plan_id)
        REFERENCES t_hedge_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_plan_approval                     IS '计划审批链（永久留痕不可修改；发起人 ≠ 审批人）';
COMMENT ON COLUMN t_plan_approval.precheck_result     IS '提交前合规预校验结果（规则编号+当前值+阈值+判定）证据载体';
CREATE INDEX idx_plan_approval_plan_seq  ON t_plan_approval (hedge_plan_id, node_seq);
CREATE INDEX idx_plan_approval_approver  ON t_plan_approval (approver_id, action_time);
CREATE INDEX idx_plan_approval_sub_time  ON t_plan_approval (sub_account_id, action_time);

-- ===== TB-PLAN-6 t_trade_plan 交易计划 =====
-- 每笔交易指令必须绑定交易计划 ID（R-TRADE-1）；期权与期货走同一机制（R-PLAN-11）。
CREATE TABLE t_trade_plan (
    id                 BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id     INT                       NOT NULL,
    trade_plan_no      VARCHAR(32)               NOT NULL,
    hedge_plan_id      BIGINT                    NOT NULL,
    exposure_id        BIGINT                    NOT NULL,
    product_id         BIGINT                    NOT NULL,
    instrument_id      VARCHAR(32)               NOT NULL,
    instrument_type    VARCHAR(16)               NOT NULL,
    direction          VARCHAR(16)               NOT NULL,
    plan_qty           DECIMAL(20,4)             NOT NULL,
    executed_qty       DECIMAL(20,4) DEFAULT 0   NOT NULL,
    ordered_qty        DECIMAL(20,4) DEFAULT 0   NOT NULL,
    exec_deadline      DATE                      NOT NULL,
    status             VARCHAR(24)               NOT NULL,
    strategy_id        BIGINT                    NULL,
    row_version        INT          DEFAULT 0    NOT NULL,
    created_by         BIGINT                    NOT NULL,
    created_time       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by         BIGINT                    NULL,
    updated_time       TIMESTAMP                 NULL,
    CONSTRAINT pk_t_trade_plan          PRIMARY KEY (id),
    CONSTRAINT uk_trade_plan_no         UNIQUE (trade_plan_no),
    CONSTRAINT ck_trade_plan_instr_type CHECK (instrument_type IN ('FUTURES','OPTION')),
    CONSTRAINT ck_trade_plan_direction  CHECK (direction IN ('BUY','SELL')),
    CONSTRAINT ck_trade_plan_qty        CHECK (plan_qty > 0),
    CONSTRAINT ck_trade_plan_status     CHECK (status IN ('PENDING','EXECUTING','COMPLETED','CANCELLED')),
    CONSTRAINT fk_trade_plan_sub        FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_plan_plan       FOREIGN KEY (hedge_plan_id)
        REFERENCES t_hedge_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_plan_exposure   FOREIGN KEY (exposure_id)
        REFERENCES t_exposure (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_plan_product    FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_trade_plan                     IS '交易计划（套保计划分解出的可执行下单任务；合计 ≤ 套保计划数量）';
COMMENT ON COLUMN t_trade_plan.exposure_id          IS '随计划携带的实货敞口 ID（FR-TRADE-1：指令须可回溯实货敞口，避免交易路径 join）';
CREATE INDEX idx_trade_plan_sub_status    ON t_trade_plan (sub_account_id, status);
CREATE INDEX idx_trade_plan_hedge_plan    ON t_trade_plan (hedge_plan_id);
CREATE INDEX idx_trade_plan_exposure      ON t_trade_plan (exposure_id);
CREATE INDEX idx_trade_plan_deadline      ON t_trade_plan (exec_deadline, status);
CREATE INDEX idx_trade_plan_strategy      ON t_trade_plan (strategy_id);

-- ===== TB-PLAN-7 t_plan_state_log 计划状态流转与额度占用留痕 =====
-- 只增不改不删；R-PLAN-5「额度占用与释放口径」的唯一审计载体。
CREATE TABLE t_plan_state_log (
    id                BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id    INT                       NOT NULL,
    hedge_plan_id     BIGINT                    NOT NULL,
    from_status       VARCHAR(24)               NULL,
    to_status         VARCHAR(24)               NOT NULL,
    quota_action      VARCHAR(16)               NOT NULL,
    quota_qty         DECIMAL(20,4)             NULL,
    quota_product_id  BIGINT                    NULL,
    dispatch_status   VARCHAR(16)  DEFAULT 'PENDING' NOT NULL,
    dispatch_version  BIGINT                    NULL,
    operator_id       BIGINT                    NOT NULL,
    reason            VARCHAR(500)              NULL,
    action_time       TIMESTAMP                 NOT NULL,
    created_by        BIGINT                    NOT NULL,
    created_time      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by        BIGINT                    NULL,
    updated_time      TIMESTAMP                 NULL,
    row_version       INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_plan_state_log      PRIMARY KEY (id),
    CONSTRAINT ck_plan_state_log_action CHECK (quota_action IN ('OCCUPY','RELEASE','NONE')),
    CONSTRAINT ck_plan_state_log_dispatch CHECK (dispatch_status IN
        ('PENDING','SUCCESS','FAIL','UNCONFIRMED','NOT_REQUIRED')),
    CONSTRAINT ck_plan_state_log_qty    CHECK (quota_action = 'NONE' OR quota_qty IS NOT NULL),
    CONSTRAINT fk_plan_state_log_sub    FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_plan_state_log_plan   FOREIGN KEY (hedge_plan_id)
        REFERENCES t_hedge_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_plan_state_log_product FOREIGN KEY (quota_product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_plan_state_log                  IS '计划状态流转与额度占用/释放留痕（每次 OCCUPY/RELEASE 都有记录）';
COMMENT ON COLUMN t_plan_state_log.dispatch_status  IS '失败则该额度变更不得视为生效（R-PRODUCT-9），由巡检重发';
CREATE INDEX idx_plan_state_log_plan_time ON t_plan_state_log (hedge_plan_id, action_time);
CREATE INDEX idx_plan_state_log_sub_time  ON t_plan_state_log (sub_account_id, action_time);
CREATE INDEX idx_plan_state_log_dispatch  ON t_plan_state_log (dispatch_status);

-- ===== TB-PLAN-8 t_custom_hedge_rule 自定义套保比例规则（v1.2 新增，DC-025） =====
-- 仅「固定比例上限 + 适用条件」，不做表达式引擎（Q-6）；多套规则叠加 = 取最严（min(ratio_cap)）。
CREATE TABLE t_custom_hedge_rule (
    id                BIGINT       IDENTITY(1,1) NOT NULL,
    rule_code         VARCHAR(32)               NOT NULL,
    rule_name         VARCHAR(100)              NOT NULL,
    sub_account_id    INT                       NOT NULL,
    product_id        BIGINT                    NULL,
    direction         VARCHAR(8)                NOT NULL,
    basis             VARCHAR(24)               NOT NULL,
    ratio_cap         DECIMAL(9,4)              NOT NULL,
    priority          INT          DEFAULT 100  NOT NULL,
    effective_from    DATE                      NOT NULL,
    effective_to      DATE                      NULL,
    status            VARCHAR(16)               NOT NULL,
    rule_desc         VARCHAR(500)              NULL,
    approved_by       BIGINT                    NULL,
    approved_time     TIMESTAMP                 NULL,
    created_by        BIGINT                    NOT NULL,
    created_time      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by        BIGINT                    NULL,
    updated_time      TIMESTAMP                 NULL,
    row_version       INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_custom_hedge_rule      PRIMARY KEY (id),
    CONSTRAINT uk_custom_rule_code         UNIQUE (rule_code),
    CONSTRAINT ck_custom_rule_direction    CHECK (direction IN ('BUY','SELL','BOTH')),
    CONSTRAINT ck_custom_rule_basis        CHECK (basis IN
        ('EXPOSURE_QTY','CONTRACT_QTY','INVENTORY_QTY')),
    CONSTRAINT ck_custom_rule_ratio_cap    CHECK (ratio_cap > 0 AND ratio_cap <= 1),
    CONSTRAINT ck_custom_rule_status       CHECK (status IN ('DRAFT','EFFECTIVE','EXPIRED')),
    CONSTRAINT ck_custom_rule_period       CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT fk_custom_rule_sub          FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_custom_rule_product      FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_custom_hedge_rule                   IS '自定义套保比例规则（规则权威源；书面文件与比率计算引用）';
COMMENT ON COLUMN t_custom_hedge_rule.ratio_cap         IS '比例上限（小数存储，如 0.8000 = 80%），必须 ∈ (0,1]';
COMMENT ON COLUMN t_custom_hedge_rule.product_id        IS 'NULL = 全品种适用；「品种列表」由多条规则行表达';
CREATE INDEX idx_custom_rule_scope   ON t_custom_hedge_rule
    (sub_account_id, product_id, direction, status, effective_from);
CREATE INDEX idx_custom_rule_product ON t_custom_hedge_rule (product_id, status);

-- -----------------------------------------------------------------------------
-- 模块内后置外键（目标表 `t_custom_hedge_rule` 在本脚本中后建，故此处追加）
-- -----------------------------------------------------------------------------
ALTER TABLE t_hedge_ratio_calc ADD CONSTRAINT fk_ratio_calc_custom_rule
    FOREIGN KEY (custom_rule_id) REFERENCES t_custom_hedge_rule (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE t_hedge_ratio_calc ADD CONSTRAINT fk_ratio_calc_applied_rule
    FOREIGN KEY (applied_rule_id) REFERENCES t_custom_hedge_rule (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE t_hedge_doc ADD CONSTRAINT fk_hedge_doc_custom_rule
    FOREIGN KEY (custom_rule_id) REFERENCES t_custom_hedge_rule (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

-- -----------------------------------------------------------------------------
-- 跨模块外键（TRADE 模块，本里程碑不交付；目标表 `t_option_strategy` 由 #java 的 T-043 交付）
-- ⚠️ 执行顺序前置：须先执行 T-043 的 `t_option_strategy` 建表；否则本段失败
--    （此时可将本段暂缓，待 T-043 后单独补执行 —— 该列可空，暂缺不影响 M1 其他功能）。
-- -----------------------------------------------------------------------------
ALTER TABLE t_trade_plan ADD CONSTRAINT fk_trade_plan_strategy
    FOREIGN KEY (strategy_id) REFERENCES t_option_strategy (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

-- =============================================================================
--  本脚本明细自检
--    表数：8 = DATA_MODEL.md §0.6 PLAN（TB-PLAN-1~8）✅（含 v1.2 新增 TB-PLAN-8）
--    逐列核对：字段/类型（含精度）/非空 与 §2.5 各表字段表逐行一致
--    索引：uk_hedge_plan_no / uk_plan_exposure / uk_hedge_doc_no /
--          uk_hedge_doc_current（条件唯一）/ uk_trade_plan_no / uk_custom_rule_code + 全部 idx_*
--    具名业务不变量 CHECK：ck_hedge_plan_ratio / ck_hedge_doc_pending_review
--    未落 DB 的规则（服务层，§4.4）：计划数量 ≤ 敞口未覆盖数量、Σ交易计划 ≤ 套保计划数量、
--          发起人 ≠ 审批人（服务层）、已批准计划要素不可直接修改、额度占用/释放 —— 不得用触发器实现。
--    待澄清登记（本模块）：TB-PLAN-3 `custom_rule_id` / `applied_rule_id` 同时指向
--          `t_custom_hedge_rule(id)` 而该表定义位于本模块**之后**（文件内顺序无关，建表按依赖执行）。
-- =============================================================================
