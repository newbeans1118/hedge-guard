-- =============================================================================
--  HedgeGuard 建表脚本 —— 模块 ACCT（套期会计）（TB-ACCT-1~12，共 12 张）
--  制品编号：docs/DEPLOY/schema/m4_acct.sql
--  依据：docs/DATA_MODEL.md v1.3.8（md5 以实时为准；v1.3.8 系 BUG-005/T-201 两表增列
--        pending_external_review——变更记录见 DATA_MODEL §7.1）
--        §2.10 模块 ACCT（TB-ACCT-1~12）/ §0.2 字段类型 / §0.3 通用审计字段
--        / §0.4 外键与级联 / §0.5 DM8 建表模板 / §3.1 枚举字段 / §4.3 约束目录
--  目标库：达梦 DM8；执行者：DBA（应用账号仅 SELECT/INSERT/UPDATE，禁 DDL — NFR-SEC-8）
--
--  执行顺序：本脚本建表段引用 t_sub_account（CONFIG/AUTH）、t_hedge_plan / t_hedge_doc
--    （PLAN）、t_exposure（SPOT）、t_trade_fill（TRADE）、t_settle_cycle（SETTLE）、
--    t_account_subject（CONFIG）——均按 docs/DEPLOY/README.md §7.1 顺序在本文件之前建表，
--    故本脚本可单遍执行（无跨模块循环外键；README §7.1 追加「14) m4_acct.sql」即成序）。
--
--  [未实测声明] 本环境无 DM8 实例（T-052 披露；CH-016：dev/test=SQLite），本脚本未在
--    DM8 实机执行。替代证据：M4AcctSchemaTest（与 DATA_MODEL §2.10 逐列一致）+
--    M4AcctDaoMappingAndWriteTest（InMemoryDmEngine 等价执行：建表→真实 DAO 读写→
--    NOT NULL / uk / ck 约束强制）。DM8 实机验证归生产就绪阶段（T-052 先例口径）。
--
--  只增不改分类（CONTRACTS.md §2.3「只增不改表覆写 updateById 显式抛异常」）：
--    - t_period_close_log 为「只增不改不删」留痕表（DATA_MODEL §2.10 TB-ACCT-7「只增不改不删」）
--      ⇒ 其 DAO 拒绝 updateById。
--    - t_effectiveness_assess / t_rebalance_record：需求明文「不得事后修改，更正须新增记录」
--      （TB-ACCT-2 备注 / ACCT.md R-ACCT-2、R-ACCT-3）⇒ 其 DAO 拒绝 updateById（防静默改写）。
--    - 其余九表为状态流转/幂等覆盖表（凭证状态机、套保效果幂等键、自检汇总等），
--      按可更新处理；状态流转须经业务动作（§3.3 统一纪律③：不提供直接改状态入口）。
--
--  会计科目（R-ACCT-11）：凭证分录科目编码引用 t_account_subject（CONFIG，m1_config_auth.sql），
--    科目不得硬编码；科目模板（is_template_default='Y'）由 CONFIG 承载（data-init.sql 播种清单
--    #4「承接方=ACCT 模块任务」——本脚本为纯 DDL，模板行播种随数据初始化单独交付）。
--
--  脚本纪律（DATA_MODEL.md §0.5）：无存储过程/触发器/自定义函数；无 DML；
--    DM 特性带 DM-SPECIFIC 注释；外键一律 ON DELETE/UPDATE RESTRICT（§0.4）。
-- =============================================================================

-- ===== TB-ACCT-1 t_hedge_relation 套期关系（正式指定；一经指定不得静默变更） =====
-- 用途：被套期项目（实货敞口）与套期工具（期货/期权合约）之间的正式指定关系（FR-ACCT-1）。
CREATE TABLE t_hedge_relation (
    id                         BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id             INT                                        NOT NULL,
    relation_no                VARCHAR(32)                                NOT NULL,
    hedge_plan_id              BIGINT                                     NOT NULL,
    hedged_item_exposure_id    BIGINT                                     NOT NULL,
    hedging_instrument_type    VARCHAR(16)                                NOT NULL,
    hedging_instrument_desc    VARCHAR(500)                               NOT NULL,
    hedge_doc_id               BIGINT                                     NOT NULL,
    status                     VARCHAR(24)                                NOT NULL,
    designated_time            TIMESTAMP                                  NOT NULL,
    terminated_time            TIMESTAMP                                  NULL,
    created_by                 BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time               TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                                     NULL,
    updated_time               TIMESTAMP                                  NULL,
    row_version                INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_hedge_relation PRIMARY KEY (id),
    CONSTRAINT uk_hedge_relation_no UNIQUE (relation_no),
    CONSTRAINT ck_t_hedge_relation_instrument_type CHECK (hedging_instrument_type IN ('FUTURES', 'OPTION')),
    CONSTRAINT ck_t_hedge_relation_status CHECK (status IN ('DESIGNATED', 'EFFECTIVE', 'NEED_REBALANCE', 'REDESIGNATED', 'TERMINATED')),
    CONSTRAINT fk_t_hedge_relation_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_hedge_relation_plan FOREIGN KEY (hedge_plan_id)
        REFERENCES t_hedge_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_hedge_relation_exposure FOREIGN KEY (hedged_item_exposure_id)
        REFERENCES t_exposure (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_hedge_relation_doc FOREIGN KEY (hedge_doc_id)
        REFERENCES t_hedge_doc (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_hedge_relation                IS '套期关系（正式指定；一经指定不得静默变更，ACCT.md §2 数据字典）';
COMMENT ON COLUMN t_hedge_relation.sub_account_id          IS '子账户';
COMMENT ON COLUMN t_hedge_relation.relation_no             IS '套期关系编号（全系统唯一）';
COMMENT ON COLUMN t_hedge_relation.hedge_plan_id           IS '套保计划';
COMMENT ON COLUMN t_hedge_relation.hedged_item_exposure_id IS '被套期项目（已识别的实货敞口）';
COMMENT ON COLUMN t_hedge_relation.hedging_instrument_type IS '套期工具类型（期权与期货同等对待，R-ACCT-7）';
COMMENT ON COLUMN t_hedge_relation.hedging_instrument_desc IS '套期工具描述（合约与持仓标识）';
COMMENT ON COLUMN t_hedge_relation.hedge_doc_id            IS '关联的套期关系书面文件（三要素来源）';
COMMENT ON COLUMN t_hedge_relation.status                  IS '状态（状态机见 ACCT.md §2.1）';
COMMENT ON COLUMN t_hedge_relation.designated_time         IS '正式指定时间';
COMMENT ON COLUMN t_hedge_relation.terminated_time         IS '终止时间';
CREATE INDEX idx_hedge_relation_plan ON t_hedge_relation (hedge_plan_id);
CREATE INDEX idx_hedge_relation_sub_status ON t_hedge_relation (sub_account_id, status);
CREATE INDEX idx_hedge_relation_exposure ON t_hedge_relation (hedged_item_exposure_id);

-- ===== TB-ACCT-2 t_effectiveness_assess 有效性评估记录（不得事后修改，更正须新增） =====
-- 用途：按会计期间对套期关系有效性的持续评估（FR-ACCT-2、FR-ACCT-4）。定性方法（关注经济关系）；
--   每期至少一次。observer_* 三列拆分存储使「三个观察点是否满足」可被索引与统计（TB-ACCT-2 备注）。
CREATE TABLE t_effectiveness_assess (
    id                         BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id             INT                                        NOT NULL,
    hedge_relation_id          BIGINT                                     NOT NULL,
    period_key                 VARCHAR(16)                                NOT NULL,
    assess_method              VARCHAR(32)                                NOT NULL,
    observer_linkage           CHAR(1)                                    NOT NULL,
    linkage_note               VARCHAR(1000)                              NULL,
    observer_direction         CHAR(1)                                    NOT NULL,
    observer_term              CHAR(1)                                    NOT NULL,
    exposure_expected_date     DATE                                       NULL,
    term_deviation_days        INT                                        NULL,
    monitor_deviation_ratio    DECIMAL(9,6)                               NULL,
    monitor_review_flag        CHAR(1)                   DEFAULT 'N'      NOT NULL,
    observer_detail            CLOB                                       NULL,
    conclusion                 VARCHAR(24)                                NOT NULL,
    need_action_flag           CHAR(1)                   DEFAULT 'N'      NOT NULL,
    action_note                VARCHAR(500)                               NULL,
    assessor_id                BIGINT                                     NOT NULL,
    assess_time                TIMESTAMP                                  NOT NULL,
    corrected_from_id          BIGINT                                     NULL,
    is_corrected               CHAR(1)                   DEFAULT 'N'      NOT NULL,
    -- R-ACCT-10（v1.3.8/BUG-005/T-201）：全部套期会计文档必须标注待外部复核，标注不得移除
    pending_external_review    CHAR(1)                   DEFAULT 'Y'      NOT NULL,
    created_by                 BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time               TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                                     NULL,
    updated_time               TIMESTAMP                                  NULL,
    row_version                INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_effectiveness_assess PRIMARY KEY (id),
    CONSTRAINT uk_eff_assess_rel_period UNIQUE (hedge_relation_id, period_key, is_corrected),
    CONSTRAINT ck_t_effectiveness_assess_method CHECK (assess_method IN ('QUALITATIVE_ECONOMIC_RELATION')),
    CONSTRAINT ck_t_effectiveness_assess_linkage CHECK (observer_linkage IN ('Y', 'N')),
    CONSTRAINT ck_t_effectiveness_assess_direction CHECK (observer_direction IN ('Y', 'N')),
    CONSTRAINT ck_t_effectiveness_assess_term CHECK (observer_term IN ('Y', 'N')),
    CONSTRAINT ck_t_effectiveness_assess_monitor_flag CHECK (monitor_review_flag IN ('Y', 'N')),
    CONSTRAINT ck_t_effectiveness_assess_conclusion CHECK (conclusion IN ('EFFECTIVE', 'INEFFECTIVE', 'NEED_REBALANCE')),
    CONSTRAINT ck_t_effectiveness_assess_need_action CHECK (need_action_flag IN ('Y', 'N')),
    CONSTRAINT ck_t_effectiveness_assess_is_corrected CHECK (is_corrected IN ('Y', 'N')),
    -- R-ACCT-10：全部套期会计文档必须标注待外部复核，标注不得被移除
    CONSTRAINT ck_t_effectiveness_assess_pending_review CHECK (pending_external_review = 'Y'),
    CONSTRAINT fk_t_effectiveness_assess_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_effectiveness_assess_relation FOREIGN KEY (hedge_relation_id)
        REFERENCES t_hedge_relation (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_effectiveness_assess_corrected_from FOREIGN KEY (corrected_from_id)
        REFERENCES t_effectiveness_assess (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_effectiveness_assess               IS '有效性评估记录（定性三项观察点 + 量化监测；不得事后修改，R-ACCT-2）';
COMMENT ON COLUMN t_effectiveness_assess.sub_account_id        IS '子账户';
COMMENT ON COLUMN t_effectiveness_assess.hedge_relation_id     IS '套期关系';
COMMENT ON COLUMN t_effectiveness_assess.period_key            IS '会计期间（如 2026-09）';
COMMENT ON COLUMN t_effectiveness_assess.assess_method         IS '评估方法（定性经济关系，本期唯一方法，R-ACCT-2）';
COMMENT ON COLUMN t_effectiveness_assess.observer_linkage      IS '观察点①品种相关性（Q-7：Y=同品种强/N=近相关，N 须填 linkage_note）';
COMMENT ON COLUMN t_effectiveness_assess.linkage_note          IS '近相关时的书面经济关系说明（Q-7 要求）';
COMMENT ON COLUMN t_effectiveness_assess.observer_direction    IS '观察点②方向一致性（Q-7：Y=满足/N=不满足）';
COMMENT ON COLUMN t_effectiveness_assess.observer_term         IS '观察点③期限对应性（Q-7：Y=满足/N=不满足；±3 个月）';
COMMENT ON COLUMN t_effectiveness_assess.exposure_expected_date IS '敞口预计实现日（期限对应性判定基准）';
COMMENT ON COLUMN t_effectiveness_assess.term_deviation_days   IS '期限偏离天数（绝对值须 ≤ 90 天 = ±3 个月）';
COMMENT ON COLUMN t_effectiveness_assess.monitor_deviation_ratio IS '量化监测指标（Q-7：偏离 1 超 ±50% 提示复核，仅监测不判定）';
COMMENT ON COLUMN t_effectiveness_assess.monitor_review_flag   IS '监测值超阈值提示复核（仅监测与触发再平衡，不用于判定有效性）';
COMMENT ON COLUMN t_effectiveness_assess.observer_detail       IS '各观察点判定数据与过程（结论须可复现，FR-ACCT-4 验收 3）';
COMMENT ON COLUMN t_effectiveness_assess.conclusion            IS '结论（无效时须标注需终止或再平衡，不得自动改会计处理）';
COMMENT ON COLUMN t_effectiveness_assess.need_action_flag      IS '是否需终止或再平衡（FR-ACCT-2 验收 2）';
COMMENT ON COLUMN t_effectiveness_assess.action_note           IS '需终止或再平衡的明确标注（不得自动修改会计处理）';
COMMENT ON COLUMN t_effectiveness_assess.assessor_id           IS '评估人';
COMMENT ON COLUMN t_effectiveness_assess.assess_time           IS '评估时间';
COMMENT ON COLUMN t_effectiveness_assess.corrected_from_id     IS '更正来源（更正须新增记录并引用原记录）';
COMMENT ON COLUMN t_effectiveness_assess.is_corrected          IS '是否已被更正';
COMMENT ON COLUMN t_effectiveness_assess.pending_external_review IS '待外部复核标注（恒为 Y，R-ACCT-10，数据库级保证不可移除）';
CREATE INDEX idx_eff_assess_sub_period ON t_effectiveness_assess (sub_account_id, period_key);
CREATE INDEX idx_eff_assess_conclusion ON t_effectiveness_assess (conclusion, period_key);
CREATE INDEX idx_eff_assess_monitor ON t_effectiveness_assess (monitor_review_flag, period_key);

-- ===== TB-ACCT-3 t_rebalance_record 再平衡记录（不得事后修改；须重新指定，不得只改数字） =====
-- 用途：再平衡操作的全过程记录（FR-ACCT-3）。须重新生成书面文件与正式指定（R-ACCT-3）。
CREATE TABLE t_rebalance_record (
    id                         BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id             INT                                        NOT NULL,
    hedge_relation_id          BIGINT                                     NOT NULL,
    rebalance_no               VARCHAR(32)                                NOT NULL,
    trigger_type               VARCHAR(32)                                NOT NULL,
    trigger_side               VARCHAR(16)                                NOT NULL,
    trigger_reason             VARCHAR(1000)                              NOT NULL,
    trigger_exposure_delta     DECIMAL(9,6)                               NULL,
    trigger_ratio_deviation    DECIMAL(9,6)                               NULL,
    trigger_threshold          DECIMAL(9,6)                               NOT NULL,
    ratio_before               DECIMAL(9,6)                               NOT NULL,
    ratio_after                DECIMAL(9,6)                               NOT NULL,
    qty_before                 DECIMAL(20,4)                              NOT NULL,
    qty_after                  DECIMAL(20,4)                              NOT NULL,
    new_hedge_doc_id           BIGINT                                     NOT NULL,
    new_hedge_relation_id      BIGINT                                     NULL,
    executor_id                BIGINT                                     NOT NULL,
    rebalance_time             TIMESTAMP                                  NOT NULL,
    plan_feedback_flag         CHAR(1)                   DEFAULT 'N'      NOT NULL,
    -- R-ACCT-10（v1.3.8/BUG-005/T-201）：全部套期会计文档必须标注待外部复核，标注不得移除
    pending_external_review    CHAR(1)                   DEFAULT 'Y'      NOT NULL,
    created_by                 BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time               TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                                     NULL,
    updated_time               TIMESTAMP                                  NULL,
    row_version                INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_rebalance_record PRIMARY KEY (id),
    CONSTRAINT uk_rebalance_no UNIQUE (rebalance_no),
    CONSTRAINT ck_t_rebalance_record_trigger_type CHECK (trigger_type IN ('EXPOSURE_DELTA', 'RATIO_DEVIATION', 'PLAN_REVISED', 'EXPOSURE_GONE')),
    CONSTRAINT ck_t_rebalance_record_trigger_side CHECK (trigger_side IN ('EXPOSURE_SIDE', 'INSTRUMENT_SIDE')),
    CONSTRAINT ck_t_rebalance_record_plan_feedback CHECK (plan_feedback_flag IN ('Y', 'N')),
    -- R-ACCT-10：全部套期会计文档必须标注待外部复核，标注不得被移除
    CONSTRAINT ck_t_rebalance_record_pending_review CHECK (pending_external_review = 'Y'),
    CONSTRAINT fk_t_rebalance_record_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_rebalance_record_relation FOREIGN KEY (hedge_relation_id)
        REFERENCES t_hedge_relation (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_rebalance_record_new_doc FOREIGN KEY (new_hedge_doc_id)
        REFERENCES t_hedge_doc (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_rebalance_record_new_relation FOREIGN KEY (new_hedge_relation_id)
        REFERENCES t_hedge_relation (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_rebalance_record                 IS '再平衡记录（四类触发 + 敞口/工具侧各设一项，Q-8；不得事后修改）';
COMMENT ON COLUMN t_rebalance_record.sub_account_id          IS '子账户';
COMMENT ON COLUMN t_rebalance_record.hedge_relation_id       IS '套期关系';
COMMENT ON COLUMN t_rebalance_record.rebalance_no            IS '再平衡编号';
COMMENT ON COLUMN t_rebalance_record.trigger_type            IS '触发类型（Q-8 四类，满足任一即触发评估）';
COMMENT ON COLUMN t_rebalance_record.trigger_side            IS '触发条件所在侧（Q-8：敞口侧与工具侧必须各设一项）';
COMMENT ON COLUMN t_rebalance_record.trigger_reason          IS '触发原因描述（含量化判定值）';
COMMENT ON COLUMN t_rebalance_record.trigger_exposure_delta  IS '敞口变化幅度（触发判定值）';
COMMENT ON COLUMN t_rebalance_record.trigger_ratio_deviation IS '比率偏离（触发判定值）';
COMMENT ON COLUMN t_rebalance_record.trigger_threshold       IS '触发阈值（快照，来自 CFG-16）';
COMMENT ON COLUMN t_rebalance_record.ratio_before            IS '调整前套保比率';
COMMENT ON COLUMN t_rebalance_record.ratio_after             IS '调整后套保比率';
COMMENT ON COLUMN t_rebalance_record.qty_before              IS '调整前数量';
COMMENT ON COLUMN t_rebalance_record.qty_after               IS '调整后数量';
COMMENT ON COLUMN t_rebalance_record.new_hedge_doc_id        IS '重新生成的书面文件（不得只改数字，R-ACCT-3）';
COMMENT ON COLUMN t_rebalance_record.new_hedge_relation_id   IS '重新指定后的套期关系';
COMMENT ON COLUMN t_rebalance_record.executor_id             IS '执行人';
COMMENT ON COLUMN t_rebalance_record.rebalance_time          IS '执行时间';
COMMENT ON COLUMN t_rebalance_record.plan_feedback_flag      IS '是否已反馈至 PLAN（如需调整计划，R-ACCT-3）';
COMMENT ON COLUMN t_rebalance_record.pending_external_review IS '待外部复核标注（恒为 Y，R-ACCT-10，数据库级保证不可移除）';
CREATE INDEX idx_rebalance_relation_time ON t_rebalance_record (hedge_relation_id, rebalance_time);
CREATE INDEX idx_rebalance_sub_time ON t_rebalance_record (sub_account_id, rebalance_time);
CREATE INDEX idx_rebalance_trigger ON t_rebalance_record (trigger_type, trigger_side, rebalance_time);

-- ===== TB-ACCT-4 t_voucher 会计凭证（头）（草稿与正式；缺子账户标识无法插入，R-ACCT-5） =====
-- 用途：会计处理的记账凭证（FR-ACCT-6/7/13）。sub_account_id NOT NULL 是「子账户标识缺失时
--   拒绝生成凭证」（FR-ACCT-6 验收 2）的数据库级保证；voucher_no 不变 + status 流转使
--   「正式凭证不得修改，只能冲销」（R-ACCT-5）在结构上成立。
CREATE TABLE t_voucher (
    id                         BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id             INT                                        NOT NULL,
    voucher_no                 VARCHAR(32)                                NOT NULL,
    period_key                 VARCHAR(16)                                NOT NULL,
    voucher_date               DATE                                       NOT NULL,
    voucher_type               VARCHAR(32)                                NOT NULL,
    hedge_plan_id              BIGINT                                     NULL,
    hedge_relation_id          BIGINT                                     NULL,
    settle_cycle_id            BIGINT                                     NULL,
    exposure_id                BIGINT                                     NULL,
    status                     VARCHAR(24)                                NOT NULL,
    total_amount               DECIMAL(20,4)                              NOT NULL,
    generated_by               BIGINT                                     NOT NULL,
    generated_time             TIMESTAMP                                  NOT NULL,
    reversal_of_id             BIGINT                                     NULL,
    reversed_by_id             BIGINT                                     NULL,
    reversal_reason            VARCHAR(500)                               NULL,
    pending_external_review    CHAR(1)                   DEFAULT 'Y'      NOT NULL,
    created_by                 BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time               TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                                     NULL,
    updated_time               TIMESTAMP                                  NULL,
    row_version                INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_voucher PRIMARY KEY (id),
    CONSTRAINT uk_voucher_no UNIQUE (voucher_no),
    -- voucher_type 含 EXERCISE / EXPIRED_WORTHLESS（v1.2 PI-13/DC-031：期权行权与到期作废，FR-ACCT-11 落点）
    CONSTRAINT ck_t_voucher_type CHECK (voucher_type IN (
        'CLOSE_PNL', 'COMMISSION', 'MARGIN_CHANGE', 'FLOAT_PNL', 'PREMIUM',
        'EXERCISE', 'EXPIRED_WORTHLESS', 'HEDGE_EFFECT', 'REVERSAL', 'OTHER')),
    CONSTRAINT ck_t_voucher_status CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'POSTED', 'REVERSED')),
    -- R-ACCT-10：全部套期会计文档必须标注待外部复核，标注不得被移除
    CONSTRAINT ck_t_voucher_pending_review CHECK (pending_external_review = 'Y'),
    CONSTRAINT fk_t_voucher_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_voucher_plan FOREIGN KEY (hedge_plan_id)
        REFERENCES t_hedge_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_voucher_relation FOREIGN KEY (hedge_relation_id)
        REFERENCES t_hedge_relation (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_voucher_settle_cycle FOREIGN KEY (settle_cycle_id)
        REFERENCES t_settle_cycle (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_voucher_exposure FOREIGN KEY (exposure_id)
        REFERENCES t_exposure (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_voucher_reversal_of FOREIGN KEY (reversal_of_id)
        REFERENCES t_voucher (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_voucher_reversed_by FOREIGN KEY (reversed_by_id)
        REFERENCES t_voucher (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_voucher                      IS '会计凭证（头；草稿与正式；缺子账户标识拒绝生成凭证，R-ACCT-5）';
COMMENT ON COLUMN t_voucher.sub_account_id          IS '子账户（凭证类贯穿字段；NOT NULL 使缺标识拒绝生成凭证在结构上成立）';
COMMENT ON COLUMN t_voucher.voucher_no              IS '凭证编号（全系统唯一，一经分配不得修改，被审计追溯引用）';
COMMENT ON COLUMN t_voucher.period_key              IS '会计期间';
COMMENT ON COLUMN t_voucher.voucher_date            IS '凭证日期';
COMMENT ON COLUMN t_voucher.voucher_type            IS '凭证类型（含 EXERCISE/EXPIRED_WORTHLESS，v1.2 PI-13/DC-031）';
COMMENT ON COLUMN t_voucher.hedge_plan_id           IS '关联套保计划';
COMMENT ON COLUMN t_voucher.hedge_relation_id       IS '关联套期关系';
COMMENT ON COLUMN t_voucher.settle_cycle_id         IS '关联结算单（追溯链：凭证 → 结算）';
COMMENT ON COLUMN t_voucher.exposure_id             IS '关联实货敞口（追溯链终点/起点）';
COMMENT ON COLUMN t_voucher.status                  IS '状态（草稿/待审核/已审核-正式/已冲销；正式不可修改只能冲销）';
COMMENT ON COLUMN t_voucher.total_amount            IS '借贷合计金额（借贷须平衡；跨行聚合由服务层校验，§4.4）';
COMMENT ON COLUMN t_voucher.generated_by            IS '凭证生成人（不得为审核人，R-ACCT-6）';
COMMENT ON COLUMN t_voucher.generated_time          IS '生成时间';
COMMENT ON COLUMN t_voucher.reversal_of_id          IS '原凭证（冲销凭证时非空）';
COMMENT ON COLUMN t_voucher.reversed_by_id          IS '被哪张凭证冲销';
COMMENT ON COLUMN t_voucher.reversal_reason         IS '冲销原因（必填，FR-ACCT-13）';
COMMENT ON COLUMN t_voucher.pending_external_review IS '待外部复核标注（恒为 Y，R-ACCT-10，数据库级保证不可移除）';
CREATE INDEX idx_voucher_sub_period_status ON t_voucher (sub_account_id, period_key, status);
CREATE INDEX idx_voucher_plan ON t_voucher (hedge_plan_id);
CREATE INDEX idx_voucher_settle_cycle ON t_voucher (settle_cycle_id);
CREATE INDEX idx_voucher_exposure ON t_voucher (exposure_id);
CREATE INDEX idx_voucher_reversal ON t_voucher (reversal_of_id, reversed_by_id);

-- ===== TB-ACCT-5 t_voucher_line 凭证分录行（科目来自 CONFIG 字典，不得硬编码，R-ACCT-11） =====
-- 用途：凭证的借贷分录（FR-ACCT-6）。借贷平衡为跨行聚合约束，不由 DB 保证（§4.4 唯一例外）
--   → 服务层同事务校验 + t_voucher.total_amount 冗余。
CREATE TABLE t_voucher_line (
    id                         BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id             INT                                        NOT NULL,
    voucher_id                 BIGINT                                     NOT NULL,
    line_no                    INT                                        NOT NULL,
    subject_code               VARCHAR(32)                                NOT NULL,
    subject_name               VARCHAR(128)                               NULL,
    direction                  VARCHAR(8)                                 NOT NULL,
    amount                     DECIMAL(20,4)                              NOT NULL,
    qty                        DECIMAL(20,4)                              NULL,
    trade_fill_id              BIGINT                                     NULL,
    biz_desc                   VARCHAR(500)                               NULL,
    created_by                 BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time               TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                                     NULL,
    updated_time               TIMESTAMP                                  NULL,
    row_version                INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_voucher_line PRIMARY KEY (id),
    CONSTRAINT uk_voucher_line UNIQUE (voucher_id, line_no),
    -- 金额必须 > 0（方向由 direction 表达，§4.3）
    CONSTRAINT ck_t_voucher_line_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_t_voucher_line_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT fk_t_voucher_line_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_voucher_line_voucher FOREIGN KEY (voucher_id)
        REFERENCES t_voucher (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_voucher_line_subject FOREIGN KEY (subject_code)
        REFERENCES t_account_subject (subject_code) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_voucher_line_fill FOREIGN KEY (trade_fill_id)
        REFERENCES t_trade_fill (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_voucher_line                 IS '凭证分录行（借贷分录；科目来自 CONFIG 科目字典，R-ACCT-11）';
COMMENT ON COLUMN t_voucher_line.sub_account_id     IS '子账户（冗余自凭证头，便于直接按子账户聚合金额）';
COMMENT ON COLUMN t_voucher_line.voucher_id         IS '所属凭证';
COMMENT ON COLUMN t_voucher_line.line_no            IS '分录序号';
COMMENT ON COLUMN t_voucher_line.subject_code       IS '会计科目编码（来自字典）';
COMMENT ON COLUMN t_voucher_line.subject_name       IS '科目名称（冗余，便于导出）';
COMMENT ON COLUMN t_voucher_line.direction          IS '记账方向：DEBIT（借）/ CREDIT（贷）';
COMMENT ON COLUMN t_voucher_line.amount             IS '金额；必须 > 0（方向由 direction 表达，§4.3）';
COMMENT ON COLUMN t_voucher_line.qty                IS '数量（如成交手数）';
COMMENT ON COLUMN t_voucher_line.trade_fill_id      IS '关联成交记录（追溯链：凭证 → 交易）';
COMMENT ON COLUMN t_voucher_line.biz_desc           IS '业务摘要（凭证摘要）';
CREATE INDEX idx_voucher_line_voucher ON t_voucher_line (voucher_id);
CREATE INDEX idx_voucher_line_subject ON t_voucher_line (subject_code, voucher_id);
CREATE INDEX idx_voucher_line_fill ON t_voucher_line (trade_fill_id);
CREATE INDEX idx_voucher_line_sub ON t_voucher_line (sub_account_id);

-- ===== TB-ACCT-6 t_accounting_period 会计期间（全局；例外 E2，无子账户维度） =====
-- 用途：会计期间的开放 / 已关账（FR-ACCT-12）。关账后不得在该期间产生新凭证（须下期调整）。
CREATE TABLE t_accounting_period (
    id                         BIGINT                                     IDENTITY(1,1) NOT NULL,
    period_key                 VARCHAR(16)                                NOT NULL,
    period_type                VARCHAR(16)                                NOT NULL,
    period_start               DATE                                       NOT NULL,
    period_end                 DATE                                       NOT NULL,
    status                     VARCHAR(24)                                NOT NULL,
    closed_by                  BIGINT                                     NULL,
    closed_time                TIMESTAMP                                  NULL,
    close_reviewer_id          BIGINT                                     NULL,
    reopened_by                BIGINT                                     NULL,
    reopened_time              TIMESTAMP                                  NULL,
    reopen_reason              VARCHAR(500)                               NULL,
    created_by                 BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time               TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                                     NULL,
    updated_time               TIMESTAMP                                  NULL,
    row_version                INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_accounting_period PRIMARY KEY (id),
    CONSTRAINT uk_accounting_period_key UNIQUE (period_key),
    CONSTRAINT ck_t_accounting_period_type CHECK (period_type IN ('MONTH', 'QUARTER', 'YEAR')),
    CONSTRAINT ck_t_accounting_period_status CHECK (status IN ('OPEN', 'CLOSED'))
);
COMMENT ON TABLE  t_accounting_period            IS '会计期间（全局期间定义，例外 E2；关账后不得在该期间产生新凭证）';
COMMENT ON COLUMN t_accounting_period.period_key        IS '期间键（如 2026-09）';
COMMENT ON COLUMN t_accounting_period.period_type       IS '期间类型';
COMMENT ON COLUMN t_accounting_period.period_start      IS '期间起';
COMMENT ON COLUMN t_accounting_period.period_end        IS '期间止';
COMMENT ON COLUMN t_accounting_period.status            IS '状态（开放/已关账）';
COMMENT ON COLUMN t_accounting_period.closed_by         IS '关账人';
COMMENT ON COLUMN t_accounting_period.closed_time       IS '关账时间';
COMMENT ON COLUMN t_accounting_period.close_reviewer_id IS '关账复核人（≠ closed_by，R-ACCT-8）';
COMMENT ON COLUMN t_accounting_period.reopened_by       IS '撤销关账操作人（须双人复核并留痕，FR-ACCT-12 验收 4）';
COMMENT ON COLUMN t_accounting_period.reopened_time     IS '撤销关账时间';
COMMENT ON COLUMN t_accounting_period.reopen_reason     IS '撤销关账原因';
CREATE INDEX idx_accounting_period_status_range ON t_accounting_period (status, period_start, period_end);

-- ===== TB-ACCT-7 t_period_close_log 关账留痕（只增不改不删；全局期间级，例外 E2） =====
-- 用途：关账与撤销关账的完整留痕（FR-ACCT-12）。
CREATE TABLE t_period_close_log (
    id                         BIGINT                                     IDENTITY(1,1) NOT NULL,
    period_key                 VARCHAR(16)                                NOT NULL,
    action                     VARCHAR(16)                                NOT NULL,
    precheck_result            CLOB                                       NOT NULL,
    operator_id                BIGINT                                     NOT NULL,
    reviewer_id                BIGINT                                     NOT NULL,
    reason                     VARCHAR(500)                               NULL,
    action_time                TIMESTAMP                                  NOT NULL,
    created_by                 BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time               TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                                     NULL,
    updated_time               TIMESTAMP                                  NULL,
    row_version                INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_period_close_log PRIMARY KEY (id),
    CONSTRAINT ck_t_period_close_log_action CHECK (action IN ('CLOSE', 'REOPEN'))
);
COMMENT ON TABLE  t_period_close_log             IS '关账留痕（只增不改不删；全局期间级操作，例外 E2）';
COMMENT ON COLUMN t_period_close_log.period_key      IS '期间';
COMMENT ON COLUMN t_period_close_log.action          IS '动作（关账/撤销关账）';
COMMENT ON COLUMN t_period_close_log.precheck_result IS '关账前置校验结果（① 全部凭证已审核/已冲销 ② 结算已完成 ③ 有效性评估已完成）';
COMMENT ON COLUMN t_period_close_log.operator_id     IS '操作人';
COMMENT ON COLUMN t_period_close_log.reviewer_id     IS '复核人（≠ operator_id，R-ACCT-8）';
COMMENT ON COLUMN t_period_close_log.reason          IS '原因（撤销关账时必填）';
COMMENT ON COLUMN t_period_close_log.action_time     IS '操作时间';
CREATE INDEX idx_period_close_log_key_time ON t_period_close_log (period_key, action_time);
CREATE INDEX idx_period_close_log_operator ON t_period_close_log (operator_id, action_time);

-- ===== TB-ACCT-8 t_hedge_effect 套保效果（双侧盈亏；两侧列同行共存，R-ACCT-4） =====
-- 用途：实货盈亏 + 衍生品盈亏 + 净效果 + 未对冲部分（FR-ACCT-5、FR-ACCT-11）。
--   spot_pnl_* 与 derivative_pnl_* 成对存在于同一行——「禁止只报一侧」的表结构层实现。
CREATE TABLE t_hedge_effect (
    id                         BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id             INT                                        NOT NULL,
    period_key                 VARCHAR(16)                                NOT NULL,
    hedge_plan_id              BIGINT                                     NULL,
    exposure_id                BIGINT                                     NULL,
    spot_pnl_realized          DECIMAL(20,4)                              NULL,
    spot_pnl_unrealized        DECIMAL(20,4)                              NULL,
    spot_pnl_total             DECIMAL(20,4)                              NULL,
    derivative_pnl_realized    DECIMAL(20,4)                              NULL,
    derivative_pnl_unrealized  DECIMAL(20,4)                              NULL,
    derivative_pnl_total       DECIMAL(20,4)                              NULL,
    net_effect                 DECIMAL(20,4)                              NULL,
    unhedged_qty               DECIMAL(20,4)                              NULL,
    unhedged_amount            DECIMAL(20,4)                              NULL,
    hedged_ratio_actual        DECIMAL(9,6)                               NULL,
    spot_price_missing         CHAR(1)                   DEFAULT 'N'      NOT NULL,
    missing_desc               VARCHAR(1000)                              NULL,
    price_as_of                DATE                                       NULL,
    non_current_price_flag     CHAR(1)                   DEFAULT 'N'      NOT NULL,
    calc_time                  TIMESTAMP                                  NOT NULL,
    created_by                 BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time               TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                                     NULL,
    updated_time               TIMESTAMP                                  NULL,
    row_version                INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_hedge_effect PRIMARY KEY (id),
    CONSTRAINT uk_hedge_effect_key UNIQUE (period_key, sub_account_id, hedge_plan_id, exposure_id),
    CONSTRAINT ck_t_hedge_effect_spot_missing CHECK (spot_price_missing IN ('Y', 'N')),
    CONSTRAINT ck_t_hedge_effect_non_current CHECK (non_current_price_flag IN ('Y', 'N')),
    CONSTRAINT fk_t_hedge_effect_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_hedge_effect_plan FOREIGN KEY (hedge_plan_id)
        REFERENCES t_hedge_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_hedge_effect_exposure FOREIGN KEY (exposure_id)
        REFERENCES t_exposure (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_hedge_effect                 IS '套保效果（双侧盈亏同 行；两侧盈亏必须同时呈现，R-ACCT-4 / 17 号文）';
COMMENT ON COLUMN t_hedge_effect.sub_account_id          IS '子账户';
COMMENT ON COLUMN t_hedge_effect.period_key              IS '会计期间';
COMMENT ON COLUMN t_hedge_effect.hedge_plan_id           IS '套保计划（按计划归集；子账户级汇总时可为 NULL）';
COMMENT ON COLUMN t_hedge_effect.exposure_id             IS '实货敞口';
COMMENT ON COLUMN t_hedge_effect.spot_pnl_realized       IS '实货盈亏（已实现）';
COMMENT ON COLUMN t_hedge_effect.spot_pnl_unrealized     IS '实货盈亏（未实现）';
COMMENT ON COLUMN t_hedge_effect.spot_pnl_total          IS '实货盈亏合计';
COMMENT ON COLUMN t_hedge_effect.derivative_pnl_realized IS '衍生品盈亏（已实现-平仓）';
COMMENT ON COLUMN t_hedge_effect.derivative_pnl_unrealized IS '衍生品盈亏（未实现-浮动）';
COMMENT ON COLUMN t_hedge_effect.derivative_pnl_total    IS '衍生品盈亏合计';
COMMENT ON COLUMN t_hedge_effect.net_effect              IS '综合评判净效果（两侧合计）';
COMMENT ON COLUMN t_hedge_effect.unhedged_qty            IS '未完全对冲的敞口数量（须明确展示）';
COMMENT ON COLUMN t_hedge_effect.unhedged_amount         IS '未对冲敞口金额';
COMMENT ON COLUMN t_hedge_effect.hedged_ratio_actual     IS '实际套保比率';
COMMENT ON COLUMN t_hedge_effect.spot_price_missing      IS '实货盈亏因缺价无法计算（Y 时实货列必须为 NULL 并填 missing_desc，不得省略栏位，R-ACCT-9）';
COMMENT ON COLUMN t_hedge_effect.missing_desc            IS '缺失的品种与日期清单（Q-12：估值用途下沿用最近一期但必须标注）';
COMMENT ON COLUMN t_hedge_effect.price_as_of             IS '实货盈亏计算所用现货价的实际时点（Q-12：沿用最近一期时必带）';
COMMENT ON COLUMN t_hedge_effect.non_current_price_flag  IS '是否使用了非当日价（Q-12：结果须标记，不得无标注静默替代）';
COMMENT ON COLUMN t_hedge_effect.calc_time               IS '计算时间';
CREATE INDEX idx_hedge_effect_sub_period ON t_hedge_effect (sub_account_id, period_key);
CREATE INDEX idx_hedge_effect_plan_period ON t_hedge_effect (hedge_plan_id, period_key);
CREATE INDEX idx_hedge_effect_missing ON t_hedge_effect (spot_price_missing, period_key);
CREATE INDEX idx_hedge_effect_price_asof ON t_hedge_effect (non_current_price_flag, period_key);

-- ===== TB-ACCT-9 t_dual_pnl_report 双侧盈亏上报（实货栏位恒在；sub_account_id 可空=汇总） =====
-- 用途：实货盈亏与衍生品盈亏同时上报的记录（FR-ACCT-10）。禁止只报衍生品盈亏；
--   实货盈亏无法计算时保留栏位并标注原因，不得省略。
CREATE TABLE t_dual_pnl_report (
    id                         BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id             INT                                        NULL,
    report_no                  VARCHAR(32)                                NOT NULL,
    report_period              VARCHAR(16)                                NOT NULL,
    report_type                VARCHAR(16)                                NOT NULL,
    spot_pnl_column_present    CHAR(1)                                    NOT NULL,
    spot_pnl_value             DECIMAL(20,4)                              NULL,
    spot_pnl_missing_reason    VARCHAR(1000)                              NULL,
    derivative_pnl_value       DECIMAL(20,4)                              NOT NULL,
    derivative_pnl_realized    DECIMAL(20,4)                              NULL,
    derivative_pnl_unrealized  DECIMAL(20,4)                              NULL,
    net_effect                 DECIMAL(20,4)                              NULL,
    data_completeness_note     VARCHAR(1000)                              NULL,
    submitted_by               BIGINT                                     NOT NULL,
    submitted_time             TIMESTAMP                                  NOT NULL,
    pending_external_review    CHAR(1)                   DEFAULT 'Y'      NOT NULL,
    created_by                 BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time               TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                                     NULL,
    updated_time               TIMESTAMP                                  NULL,
    row_version                INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_dual_pnl_report PRIMARY KEY (id),
    CONSTRAINT uk_dual_pnl_report_no UNIQUE (report_no),
    CONSTRAINT ck_t_dual_pnl_report_type CHECK (report_type IN ('MONTHLY', 'QUARTERLY', 'ANNUAL')),
    -- spot_pnl_column_present 恒为 Y：FR-ACCT-10 验收 2「不省略该栏」的结构保证（TB-ACCT-9 备注）
    CONSTRAINT ck_t_dual_pnl_report_spot_column CHECK (spot_pnl_column_present = 'Y'),
    CONSTRAINT ck_t_dual_pnl_report_pending_review CHECK (pending_external_review IN ('Y', 'N')),
    CONSTRAINT fk_t_dual_pnl_report_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_dual_pnl_report              IS '双侧盈亏上报（两侧必须同时上报，R-ACCT-4 / 17 号文；汇总上报 sub_account_id 为 NULL）';
COMMENT ON COLUMN t_dual_pnl_report.sub_account_id          IS '子账户（上报按子账户 + 汇总；汇总行为 NULL，§4.6.1 #49 口径 ②）';
COMMENT ON COLUMN t_dual_pnl_report.report_no               IS '上报编号';
COMMENT ON COLUMN t_dual_pnl_report.report_period           IS '报告期间';
COMMENT ON COLUMN t_dual_pnl_report.report_type             IS '报告类型';
COMMENT ON COLUMN t_dual_pnl_report.spot_pnl_column_present IS '实货盈亏栏位是否存在（恒为 Y——禁止以缺数据为由省略栏位）';
COMMENT ON COLUMN t_dual_pnl_report.spot_pnl_value          IS '实货盈亏值（无法计算时为 NULL）';
COMMENT ON COLUMN t_dual_pnl_report.spot_pnl_missing_reason IS '无法计算的原因 + 缺失品种与日期（spot_pnl_value IS NULL 时必填）';
COMMENT ON COLUMN t_dual_pnl_report.derivative_pnl_value    IS '衍生品盈亏值';
COMMENT ON COLUMN t_dual_pnl_report.derivative_pnl_realized IS '衍生品盈亏拆分（已实现）';
COMMENT ON COLUMN t_dual_pnl_report.derivative_pnl_unrealized IS '衍生品盈亏拆分（未实现）';
COMMENT ON COLUMN t_dual_pnl_report.net_effect              IS '净效果';
COMMENT ON COLUMN t_dual_pnl_report.data_completeness_note  IS '数据完整性说明';
COMMENT ON COLUMN t_dual_pnl_report.submitted_by            IS '上报人';
COMMENT ON COLUMN t_dual_pnl_report.submitted_time          IS '上报时间';
COMMENT ON COLUMN t_dual_pnl_report.pending_external_review IS '待外部复核标注';
CREATE INDEX idx_dual_pnl_period_type ON t_dual_pnl_report (report_period, report_type);
CREATE INDEX idx_dual_pnl_sub_period ON t_dual_pnl_report (sub_account_id, report_period);

-- ===== TB-ACCT-10 t_voucher_review 凭证审核记录（生成人≠审核人；冲销双人复核，DC-032） =====
-- 用途：凭证审核链（FR-ACCT-7）。草稿生成人与审核人不得为同一人（R-ACCT-6）。
--   co_reviewer_id 为 v1.2 补列（PI-11/DC-032）：冲销/调整类凭证的第二复核人，须 ≠ reviewer_id
--   且 ≠ generator_id（服务层校验）；非冲销/调整类为 NULL。
CREATE TABLE t_voucher_review (
    id                         BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id             INT                                        NOT NULL,
    voucher_id                 BIGINT                                     NOT NULL,
    node_seq                   INT                                        NOT NULL,
    action                     VARCHAR(16)                                NOT NULL,
    generator_id               BIGINT                                     NOT NULL,
    reviewer_id                BIGINT                                     NULL,
    co_reviewer_id             BIGINT                                     NULL,
    review_result              VARCHAR(16)                                NULL,
    review_comment             VARCHAR(1000)                              NULL,
    reject_reason              VARCHAR(500)                               NULL,
    action_time                TIMESTAMP                                  NOT NULL,
    created_by                 BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time               TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                                     NULL,
    updated_time               TIMESTAMP                                  NULL,
    row_version                INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_voucher_review PRIMARY KEY (id),
    CONSTRAINT ck_t_voucher_review_action CHECK (action IN ('SUBMIT', 'APPROVE', 'REJECT', 'WITHDRAW')),
    CONSTRAINT ck_t_voucher_review_result CHECK (review_result IN ('APPROVED', 'REJECTED')),
    CONSTRAINT fk_t_voucher_review_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_voucher_review_voucher FOREIGN KEY (voucher_id)
        REFERENCES t_voucher (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_voucher_review               IS '凭证审核记录（生成人≠审核人 R-ACCT-6；冲销双人复核 co_reviewer_id，PI-11/DC-032）';
COMMENT ON COLUMN t_voucher_review.sub_account_id    IS '子账户';
COMMENT ON COLUMN t_voucher_review.voucher_id        IS '凭证';
COMMENT ON COLUMN t_voucher_review.node_seq          IS '审核节点序号';
COMMENT ON COLUMN t_voucher_review.action            IS '动作';
COMMENT ON COLUMN t_voucher_review.generator_id      IS '凭证生成人（冗余，供生成人≠审核人校验）';
COMMENT ON COLUMN t_voucher_review.reviewer_id       IS '审核人（必须 ≠ generator_id，服务层校验）';
COMMENT ON COLUMN t_voucher_review.co_reviewer_id    IS '第二复核人（冲销/调整类双人复核，PI-11/DC-032；≠ reviewer_id 且 ≠ generator_id）';
COMMENT ON COLUMN t_voucher_review.review_result     IS '审核结论';
COMMENT ON COLUMN t_voucher_review.review_comment    IS '审核意见';
COMMENT ON COLUMN t_voucher_review.reject_reason     IS '驳回原因（驳回时必填，R-ACCT-6）';
COMMENT ON COLUMN t_voucher_review.action_time       IS '动作时间';
CREATE INDEX idx_voucher_review_voucher_seq ON t_voucher_review (voucher_id, node_seq);
CREATE INDEX idx_voucher_review_reviewer ON t_voucher_review (reviewer_id, action_time);

-- ===== TB-ACCT-11 t_cas24_selfcheck CAS 24 条款自检（运行；sub_account_id 可空=全账户） =====
-- 用途：CAS 24 条款自检清单的运行记录（FR-ACCT-14）。自检不替代外部复核，须显式说明；
--   结论须导出为自检报告并标注"待外部复核"。
CREATE TABLE t_cas24_selfcheck (
    id                         BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id             INT                                        NULL,
    selfcheck_no               VARCHAR(32)                                NOT NULL,
    scope_type                 VARCHAR(16)                                NOT NULL,
    scope_key                  VARCHAR(64)                                NOT NULL,
    pass_count                 INT                       DEFAULT 0        NOT NULL,
    fail_count                 INT                       DEFAULT 0        NOT NULL,
    conclusion                 VARCHAR(24)                                NOT NULL,
    cannot_selfcheck_reason    VARCHAR(1000)                              NULL,
    report_file_id             VARCHAR(128)                               NULL,
    pending_external_review    CHAR(1)                   DEFAULT 'Y'      NOT NULL,
    not_substitute_note        VARCHAR(500)              DEFAULT '自检不替代外部复核' NOT NULL,
    executor_id                BIGINT                                     NOT NULL,
    selfcheck_time             TIMESTAMP                                  NOT NULL,
    created_by                 BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time               TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                                     NULL,
    updated_time               TIMESTAMP                                  NULL,
    row_version                INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_cas24_selfcheck PRIMARY KEY (id),
    CONSTRAINT uk_cas24_selfcheck_no UNIQUE (selfcheck_no),
    CONSTRAINT ck_t_cas24_selfcheck_scope_type CHECK (scope_type IN ('HEDGE_RELATION', 'PERIOD')),
    CONSTRAINT ck_t_cas24_selfcheck_conclusion CHECK (conclusion IN ('ALL_PASS', 'HAS_FAIL', 'CANNOT_SELFCHECK')),
    -- R-ACCT-10：自检不替代外部复核，须显式说明；待外部复核标注不得移除
    CONSTRAINT ck_t_cas24_selfcheck_pending_review CHECK (pending_external_review = 'Y'),
    CONSTRAINT fk_t_cas24_selfcheck_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_cas24_selfcheck              IS 'CAS 24 条款自检运行（自检不替代外部复核 R-ACCT-10；全账户自检 sub_account_id 为 NULL）';
COMMENT ON COLUMN t_cas24_selfcheck.sub_account_id          IS '子账户（NULL = 全账户自检，§4.6.1 #51 口径 ②）';
COMMENT ON COLUMN t_cas24_selfcheck.selfcheck_no            IS '自检批次号';
COMMENT ON COLUMN t_cas24_selfcheck.scope_type              IS '自检范围类型（按套期关系/按会计期间）';
COMMENT ON COLUMN t_cas24_selfcheck.scope_key               IS '范围键（关系编号 / 期间键）';
COMMENT ON COLUMN t_cas24_selfcheck.pass_count              IS '通过条数';
COMMENT ON COLUMN t_cas24_selfcheck.fail_count              IS '不通过条数';
COMMENT ON COLUMN t_cas24_selfcheck.conclusion              IS '结论（CANNOT_SELFCHECK = 关键数据缺失）';
COMMENT ON COLUMN t_cas24_selfcheck.cannot_selfcheck_reason IS '无法自检的原因（conclusion=CANNOT_SELFCHECK 时必填）';
COMMENT ON COLUMN t_cas24_selfcheck.report_file_id          IS '自检报告导出文件标识';
COMMENT ON COLUMN t_cas24_selfcheck.pending_external_review IS '待外部复核标注（恒为 Y，R-ACCT-10）';
COMMENT ON COLUMN t_cas24_selfcheck.not_substitute_note     IS '须显式说明：自检不替代外部复核（R-ACCT-10）';
COMMENT ON COLUMN t_cas24_selfcheck.executor_id             IS '执行人';
COMMENT ON COLUMN t_cas24_selfcheck.selfcheck_time          IS '自检时间';
CREATE INDEX idx_cas24_selfcheck_scope ON t_cas24_selfcheck (scope_type, scope_key);
CREATE INDEX idx_cas24_selfcheck_conclusion ON t_cas24_selfcheck (conclusion, selfcheck_time);

-- ===== TB-ACCT-12 t_cas24_selfcheck_item CAS 24 自检条目（随自检头确定子账户，§4.6.3 ③） =====
-- 用途：逐条自检结论与证据位置（FR-ACCT-14）。
CREATE TABLE t_cas24_selfcheck_item (
    id                         BIGINT                                     IDENTITY(1,1) NOT NULL,
    selfcheck_id               BIGINT                                     NOT NULL,
    item_seq                   INT                                        NOT NULL,
    item_code                  VARCHAR(32)                                NOT NULL,
    item_name                  VARCHAR(256)                               NOT NULL,
    result                     VARCHAR(16)                                NOT NULL,
    evidence_type              VARCHAR(32)                                NULL,
    evidence_id                BIGINT                                     NULL,
    evidence_desc              VARCHAR(500)                               NULL,
    fail_reason                VARCHAR(500)                               NULL,
    created_by                 BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time               TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                                     NULL,
    updated_time               TIMESTAMP                                  NULL,
    row_version                INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_cas24_selfcheck_item PRIMARY KEY (id),
    CONSTRAINT uk_cas24_item UNIQUE (selfcheck_id, item_seq),
    -- CAS 24 自检五项（§3.1：t_cas24_selfcheck_item.item_code 5 个枚举）
    CONSTRAINT ck_t_cas24_item_code CHECK (item_code IN (
        'FORMAL_DESIGNATION', 'WRITTEN_DOC', 'EFFECTIVENESS_RECORDS', 'REBALANCE_RECORDS', 'DUAL_PNL_PRESENTED')),
    CONSTRAINT ck_t_cas24_item_result CHECK (result IN ('PASS', 'FAIL', 'NOT_APPLICABLE')),
    CONSTRAINT fk_t_cas24_item_selfcheck FOREIGN KEY (selfcheck_id)
        REFERENCES t_cas24_selfcheck (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_cas24_selfcheck_item         IS 'CAS 24 自检条目（逐条结论 + 证据位置，FR-ACCT-14；条目级随头确定子账户）';
COMMENT ON COLUMN t_cas24_selfcheck_item.selfcheck_id  IS '所属自检批次';
COMMENT ON COLUMN t_cas24_selfcheck_item.item_seq      IS '条目序号';
COMMENT ON COLUMN t_cas24_selfcheck_item.item_code     IS '条款代码（①正式指定 ②书面文件 ③有效性评估记录 ④再平衡记录 ⑤双侧盈亏同时呈现）';
COMMENT ON COLUMN t_cas24_selfcheck_item.item_name     IS '条款名称';
COMMENT ON COLUMN t_cas24_selfcheck_item.result        IS '条目结论';
COMMENT ON COLUMN t_cas24_selfcheck_item.evidence_type IS '证据类型（表名 / 文档编号）';
COMMENT ON COLUMN t_cas24_selfcheck_item.evidence_id   IS '证据对象 ID';
COMMENT ON COLUMN t_cas24_selfcheck_item.evidence_desc IS '证据位置说明（FR-ACCT-14 验收 1「逐条给出通过与证据位置」）';
COMMENT ON COLUMN t_cas24_selfcheck_item.fail_reason   IS '不通过原因';
CREATE INDEX idx_cas24_item_code_result ON t_cas24_selfcheck_item (item_code, result);

-- =============================================================================
--  本脚本自检
--    表数：12 = DATA_MODEL.md §2.10 ACCT（TB-ACCT-1~12）
--    三层划分（§4.6.3，ACCT 模块）：① NOT NULL 子账户标识 7 张（t_hedge_relation /
--      t_effectiveness_assess / t_rebalance_record / t_voucher / t_voucher_line /
--      t_voucher_review / t_hedge_effect）；② 可空已说明 2 张（t_dual_pnl_report /
--      t_cas24_selfcheck，§4.6.1 #49/#51）；③ 无该列 3 张（t_accounting_period /
--      t_period_close_log = 例外 E2；t_cas24_selfcheck_item 条目级随头）。
--    voucher_type 含 EXERCISE / EXPIRED_WORTHLESS（v1.2 DC-031，验收 3）。
--    R-ACCT-10 待外部复核标注（v1.3.8/BUG-005/T-201）：四类套期会计文档承载齐备——
--      t_hedge_doc（m1_plan.sql）/ t_effectiveness_assess / t_rebalance_record /
--      t_voucher / t_cas24_selfcheck 均 CHECK 恒 'Y'；t_dual_pnl_report IN('Y','N')。
--    凭证编号规则：AV<期间><seq(5)>（§3.5，应用层生成）。
--    借贷平衡：跨行聚合约束未下沉（§4.4 唯一例外）→ 服务层同事务校验 + total_amount 冗余。
--    执行顺序：本文件全部 FK 父表均在 README §7.1 顺序中先于本文件建表，单遍可执行
--      （建议登记为「14) m4_acct.sql」，随数据初始化之前）。
-- =============================================================================
