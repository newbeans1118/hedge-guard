-- =============================================================================
--  HedgeGuard 建表脚本 —— 模块 SETTLE（结算），16 张
--  制品编号：docs/DEPLOY/schema/m2_settle.sql
--  依据：docs/DATA_MODEL.md v1.3.5（md5 d17bce0c37e513a0a236304b9cb3f25d）
--        §2.7 SETTLE（TB-SETTLE-1~16）/ §0.3 通用审计字段 / §0.4 外键与级联
--        / §0.5 DM8 建表模板 / §4.3 约束目录 / §4.4.1 差异归属机判来源
--  目标库：达梦 DM8；执行者：DBA（应用账号仅 SELECT/INSERT/UPDATE，禁 DDL — NFR-SEC-8）
--
--  执行顺序：本脚本**建表段**引用 t_sub_account（CONFIG）、t_product（PRODUCT）、t_user（AUTH）、
--    t_hedge_plan（PLAN），故须在上述脚本之后执行；t_fund_ledger（FUND）仅由**文件末尾后置
--    外键段**引用（SETTLE↔FUND 跨模块环：FUND 侧内联指向 SETTLE，须先建 FUND 再回补本段），
--    故建表段可先于 m2_fund.sql 执行。两阶段执行顺序见 docs/DEPLOY/README.md §7.1 / §8.4。
--
--  [未实测声明] 本环境无 DM8 实例，本脚本未在 DM8 实机执行。
--    替代证据：M2SettleSchemaTest（逐列一致）+ DaoColumnAlignmentTest（DAO 与 DDL 列对齐）
--              + M2SettleDaoMappingAndWriteTest（InMemoryDmEngine 等价执行）。
--
--  脚本纪律（DATA_MODEL.md §0.5）：无存储过程/触发器/自定义函数；无 DML；
--    DM 特性带 DM-SPECIFIC 注释；外键一律 ON DELETE/UPDATE RESTRICT（§0.4）。
-- =============================================================================

-- ===== TB-SETTLE-1 t_ctp_settle_batch CTP 日终结算数据批次（幂等；数据未到达不得用推算替代） =====
CREATE TABLE t_ctp_settle_batch (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    trade_date            DATE                                       NOT NULL,
    source_identifier     VARCHAR(256)                               NOT NULL,
    received_time         TIMESTAMP                                  NOT NULL,
    position_count        INT                       DEFAULT 0        NOT NULL,
    fund_count            INT                       DEFAULT 0        NOT NULL,
    trade_count           INT                       DEFAULT 0        NOT NULL,
    parse_status          VARCHAR(24)                                NOT NULL,
    parse_error           VARCHAR(1000)                              NULL,
    retry_count           INT                       DEFAULT 0        NOT NULL,
    last_retry_time       TIMESTAMP                                  NULL,
    is_confirmed          CHAR(1)                   DEFAULT 'N'      NOT NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_ctp_settle_batch PRIMARY KEY (id),
    CONSTRAINT uk_ctp_settle_batch_date UNIQUE (trade_date, source_identifier),
    CONSTRAINT ck_t_ctp_settle_batch_parse_status CHECK (parse_status IN ('OK', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_t_ctp_settle_batch_is_confirmed CHECK (is_confirmed IN ('Y', 'N'))
);
COMMENT ON TABLE  t_ctp_settle_batch           IS 'CTP 日终结算数据批次（幂等；数据未到达不得用推算替代）';
COMMENT ON COLUMN t_ctp_settle_batch.trade_date  IS '交易日';
COMMENT ON COLUMN t_ctp_settle_batch.source_identifier IS '来源标识（文件名/接口批次号）';
COMMENT ON COLUMN t_ctp_settle_batch.received_time IS '接收时间';
COMMENT ON COLUMN t_ctp_settle_batch.position_count IS '持仓明细接收条数';
COMMENT ON COLUMN t_ctp_settle_batch.fund_count  IS '资金明细接收条数';
COMMENT ON COLUMN t_ctp_settle_batch.trade_count IS '成交明细接收条数';
COMMENT ON COLUMN t_ctp_settle_batch.parse_status IS '解析状态';
COMMENT ON COLUMN t_ctp_settle_batch.parse_error IS '解析错误说明';
COMMENT ON COLUMN t_ctp_settle_batch.retry_count IS '重试次数';
COMMENT ON COLUMN t_ctp_settle_batch.last_retry_time IS '最近重试时间';
COMMENT ON COLUMN t_ctp_settle_batch.is_confirmed IS '是否已确认';
CREATE INDEX idx_ctp_settle_batch_status ON t_ctp_settle_batch (parse_status, trade_date);

-- ===== TB-SETTLE-2 t_ctp_settle_position CTP 持仓明细（合并持仓，外部核对基准，只读） =====
CREATE TABLE t_ctp_settle_position (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    trade_date            DATE                                       NOT NULL,
    settle_batch_id       BIGINT                                     NOT NULL,
    instrument_id         VARCHAR(32)                                NOT NULL,
    direction             VARCHAR(16)                                NOT NULL,
    position_type         VARCHAR(16)                                NOT NULL,
    position_qty          DECIMAL(20,4)                              NOT NULL,
    avg_price             DECIMAL(20,6)                              NULL,
    settlement_price      DECIMAL(20,6)                              NULL,
    margin                DECIMAL(20,4)                              NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_ctp_settle_position PRIMARY KEY (id),
    CONSTRAINT uk_ctp_settle_pos UNIQUE (trade_date, instrument_id, direction, position_type),
    CONSTRAINT ck_t_ctp_settle_position_direction CHECK (direction IN ('LONG', 'SHORT')),
    CONSTRAINT ck_t_ctp_settle_position_position_type CHECK (position_type IN ('TODAY', 'YESTERDAY')),
    CONSTRAINT fk_t_ctp_settle_position_batch FOREIGN KEY (settle_batch_id)
        REFERENCES t_ctp_settle_batch (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_ctp_settle_position        IS 'CTP 持仓明细（合并持仓，外部核对基准，只读）';
COMMENT ON COLUMN t_ctp_settle_position.trade_date IS '交易日';
COMMENT ON COLUMN t_ctp_settle_position.settle_batch_id IS '所属批次';
COMMENT ON COLUMN t_ctp_settle_position.instrument_id IS '合约';
COMMENT ON COLUMN t_ctp_settle_position.direction IS '方向';
COMMENT ON COLUMN t_ctp_settle_position.position_type IS '仓型';
COMMENT ON COLUMN t_ctp_settle_position.position_qty IS '持仓量（合并，不含子账户维度）';
COMMENT ON COLUMN t_ctp_settle_position.avg_price IS '持仓均价';
COMMENT ON COLUMN t_ctp_settle_position.settlement_price IS '结算价';
COMMENT ON COLUMN t_ctp_settle_position.margin   IS '占用保证金';
CREATE INDEX idx_ctp_settle_pos_date ON t_ctp_settle_position (trade_date);

-- ===== TB-SETTLE-3 t_ctp_settle_fund CTP 资金明细（账户级合并资金，外部核对基准，只读） =====
CREATE TABLE t_ctp_settle_fund (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    trade_date            DATE                                       NOT NULL,
    settle_batch_id       BIGINT                                     NOT NULL,
    equity                DECIMAL(20,4)                              NOT NULL,
    available_fund        DECIMAL(20,4)                              NOT NULL,
    margin_occupied       DECIMAL(20,4)                              NOT NULL,
    frozen_margin         DECIMAL(20,4)                              NULL,
    commission_today      DECIMAL(20,4)                              NULL,
    deposit_today         DECIMAL(20,4)                              NULL,
    withdraw_today        DECIMAL(20,4)                              NULL,
    close_pnl             DECIMAL(20,4)                              NULL,
    float_pnl             DECIMAL(20,4)                              NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_ctp_settle_fund PRIMARY KEY (id),
    CONSTRAINT uk_ctp_settle_fund UNIQUE (trade_date),
    CONSTRAINT fk_t_ctp_settle_fund_batch FOREIGN KEY (settle_batch_id)
        REFERENCES t_ctp_settle_batch (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_ctp_settle_fund            IS 'CTP 资金明细（账户级合并资金，外部核对基准，只读）';
COMMENT ON COLUMN t_ctp_settle_fund.trade_date   IS '交易日';
COMMENT ON COLUMN t_ctp_settle_fund.settle_batch_id IS '所属批次';
COMMENT ON COLUMN t_ctp_settle_fund.equity       IS '客户权益';
COMMENT ON COLUMN t_ctp_settle_fund.available_fund IS '可用资金';
COMMENT ON COLUMN t_ctp_settle_fund.margin_occupied IS '占用保证金';
COMMENT ON COLUMN t_ctp_settle_fund.frozen_margin IS '冻结保证金';
COMMENT ON COLUMN t_ctp_settle_fund.commission_today IS '当日手续费';
COMMENT ON COLUMN t_ctp_settle_fund.deposit_today IS '当日入金';
COMMENT ON COLUMN t_ctp_settle_fund.withdraw_today IS '当日出金';
COMMENT ON COLUMN t_ctp_settle_fund.close_pnl    IS '平仓盈亏';
COMMENT ON COLUMN t_ctp_settle_fund.float_pnl    IS '持仓浮动盈亏';
CREATE INDEX idx_ctp_settle_fund_date ON t_ctp_settle_fund (trade_date);

-- ===== TB-SETTLE-4 t_ctp_settle_trade CTP 成交明细（成交核对基准，只读） =====
CREATE TABLE t_ctp_settle_trade (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    trade_date            DATE                                       NOT NULL,
    settle_batch_id       BIGINT                                     NOT NULL,
    trade_id              VARCHAR(64)                                NOT NULL,
    instrument_id         VARCHAR(32)                                NOT NULL,
    direction             VARCHAR(16)                                NOT NULL,
    offset_flag           VARCHAR(16)                                NOT NULL,
    fill_price            DECIMAL(20,6)                              NOT NULL,
    fill_volume           INT                                        NOT NULL,
    trade_ts_ms           BIGINT                                     NULL,
    commission            DECIMAL(20,4)                              NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_ctp_settle_trade PRIMARY KEY (id),
    CONSTRAINT uk_ctp_settle_trade UNIQUE (trade_date, trade_id),
    CONSTRAINT fk_t_ctp_settle_trade_batch FOREIGN KEY (settle_batch_id)
        REFERENCES t_ctp_settle_batch (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_ctp_settle_trade           IS 'CTP 成交明细（成交核对基准，只读）';
COMMENT ON COLUMN t_ctp_settle_trade.trade_date  IS '交易日';
COMMENT ON COLUMN t_ctp_settle_trade.settle_batch_id IS '所属批次';
COMMENT ON COLUMN t_ctp_settle_trade.trade_id    IS 'CTP 成交编号';
COMMENT ON COLUMN t_ctp_settle_trade.instrument_id IS '合约';
COMMENT ON COLUMN t_ctp_settle_trade.direction   IS '方向';
COMMENT ON COLUMN t_ctp_settle_trade.offset_flag IS '开平';
COMMENT ON COLUMN t_ctp_settle_trade.fill_price  IS '成交价';
COMMENT ON COLUMN t_ctp_settle_trade.fill_volume IS '成交量';
COMMENT ON COLUMN t_ctp_settle_trade.trade_ts_ms IS '成交时间戳';
COMMENT ON COLUMN t_ctp_settle_trade.commission  IS '手续费';
CREATE INDEX idx_ctp_settle_trade_date ON t_ctp_settle_trade (trade_date);
CREATE INDEX idx_ctp_settle_trade_instrument ON t_ctp_settle_trade (trade_date, instrument_id);

-- ===== TB-SETTLE-5 t_settle_cycle 结算单（交易日 × 子账户；每个子账户独立出结算结果） =====
CREATE TABLE t_settle_cycle (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                                        NOT NULL,
    settle_no             VARCHAR(32)                                NOT NULL,
    trade_date            DATE                                       NOT NULL,
    is_aggregate          CHAR(1)                   DEFAULT 'N'      NOT NULL,
    status                VARCHAR(24)                                NOT NULL,
    close_pnl             DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    float_pnl             DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    commission            DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    margin_occupied       DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    available_fund        DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    equity                DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    risk_ratio            DECIMAL(9,6)                               NULL,
    negative_fund_flag    CHAR(1)                   DEFAULT 'N'      NOT NULL,
    confirmed_by          BIGINT                                     NULL,
    confirmed_time        TIMESTAMP                                  NULL,
    voided_flag           CHAR(1)                   DEFAULT 'N'      NOT NULL,
    void_reason           VARCHAR(500)                               NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_settle_cycle PRIMARY KEY (id),
    CONSTRAINT uk_settle_cycle_no UNIQUE (settle_no),
    CONSTRAINT uk_settle_cycle_date_sub UNIQUE (trade_date, sub_account_id, is_aggregate),
    CONSTRAINT ck_t_settle_cycle_is_aggregate CHECK (is_aggregate IN ('Y', 'N')),
    CONSTRAINT ck_t_settle_cycle_status CHECK (status IN ('PENDING', 'GENERATED', 'BLOCKED', 'RECONCILED', 'CONFIRMED')),
    CONSTRAINT ck_t_settle_cycle_negative_fund_flag CHECK (negative_fund_flag IN ('Y', 'N')),
    CONSTRAINT ck_t_settle_cycle_voided_flag CHECK (voided_flag IN ('Y', 'N')),
    CONSTRAINT fk_t_settle_cycle_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_settle_cycle               IS '结算单（交易日 × 子账户；每个子账户独立出结算结果）';
COMMENT ON COLUMN t_settle_cycle.sub_account_id  IS '子账户（独立结算维度）';
COMMENT ON COLUMN t_settle_cycle.settle_no       IS '结算单号（全系统唯一）';
COMMENT ON COLUMN t_settle_cycle.trade_date      IS '交易日';
COMMENT ON COLUMN t_settle_cycle.is_aggregate    IS '是否全账户汇总行';
COMMENT ON COLUMN t_settle_cycle.status          IS '结算状态';
COMMENT ON COLUMN t_settle_cycle.close_pnl       IS '平仓盈亏';
COMMENT ON COLUMN t_settle_cycle.float_pnl       IS '浮动盈亏';
COMMENT ON COLUMN t_settle_cycle.commission      IS '手续费';
COMMENT ON COLUMN t_settle_cycle.margin_occupied IS '保证金占用';
COMMENT ON COLUMN t_settle_cycle.available_fund  IS '可用资金';
COMMENT ON COLUMN t_settle_cycle.equity          IS '权益';
COMMENT ON COLUMN t_settle_cycle.risk_ratio      IS '风险度';
COMMENT ON COLUMN t_settle_cycle.negative_fund_flag IS '子账户资金是否为负';
COMMENT ON COLUMN t_settle_cycle.confirmed_by    IS '关账确认人';
COMMENT ON COLUMN t_settle_cycle.confirmed_time  IS '关账时间';
COMMENT ON COLUMN t_settle_cycle.voided_flag     IS '是否已作废';
COMMENT ON COLUMN t_settle_cycle.void_reason     IS '作废原因';
CREATE INDEX idx_settle_cycle_date_status ON t_settle_cycle (trade_date, status);
CREATE INDEX idx_settle_cycle_sub_date ON t_settle_cycle (sub_account_id, trade_date);
CREATE INDEX idx_settle_cycle_daily_settled ON t_settle_cycle (trade_date, status);

-- ===== TB-SETTLE-6 t_settle_position 系统内结算持仓（按子账户；σ 本表 = CTP 合并持仓） =====
CREATE TABLE t_settle_position (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                                        NOT NULL,
    settle_cycle_id       BIGINT                                     NOT NULL,
    trade_date            DATE                                       NOT NULL,
    instrument_id         VARCHAR(32)                                NOT NULL,
    product_id            BIGINT                                     NOT NULL,
    instrument_type       VARCHAR(16)                                NOT NULL,
    direction             VARCHAR(16)                                NOT NULL,
    position_type         VARCHAR(16)                                NOT NULL,
    position_qty          DECIMAL(20,4)                              NOT NULL,
    attribution_source    VARCHAR(24)                                NOT NULL,
    unattributed_qty      DECIMAL(20,4)                              NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_settle_position PRIMARY KEY (id),
    CONSTRAINT uk_settle_position_key UNIQUE (trade_date, sub_account_id, instrument_id, direction, position_type),
    CONSTRAINT ck_t_settle_position_instrument_type CHECK (instrument_type IN ('FUTURES', 'OPTION')),
    CONSTRAINT ck_t_settle_position_direction CHECK (direction IN ('LONG', 'SHORT')),
    CONSTRAINT ck_t_settle_position_position_type CHECK (position_type IN ('TODAY', 'YESTERDAY')),
    CONSTRAINT ck_t_settle_position_attribution_source CHECK (attribution_source IN ('TRADE_RECORD', 'UNABLE_TO_ATTRIBUTE')),
    CONSTRAINT fk_t_settle_position_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_settle_position_cycle FOREIGN KEY (settle_cycle_id)
        REFERENCES t_settle_cycle (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_settle_position_product FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_settle_position            IS '系统内结算持仓（按子账户；σ 本表 = CTP 合并持仓）';
COMMENT ON COLUMN t_settle_position.sub_account_id IS '子账户（拆分结果维度；必填，可直接求和）';
COMMENT ON COLUMN t_settle_position.settle_cycle_id IS '所属结算单';
COMMENT ON COLUMN t_settle_position.trade_date   IS '交易日';
COMMENT ON COLUMN t_settle_position.instrument_id IS '合约';
COMMENT ON COLUMN t_settle_position.product_id   IS '品种';
COMMENT ON COLUMN t_settle_position.instrument_type IS '合约类型';
COMMENT ON COLUMN t_settle_position.direction    IS '方向';
COMMENT ON COLUMN t_settle_position.position_type IS '仓型';
COMMENT ON COLUMN t_settle_position.position_qty IS '持仓量';
COMMENT ON COLUMN t_settle_position.attribution_source IS '归属来源';
COMMENT ON COLUMN t_settle_position.unattributed_qty IS '无法归属的数量';
CREATE INDEX idx_settle_position_date_instrument ON t_settle_position (trade_date, instrument_id);
CREATE INDEX idx_settle_position_sub_date ON t_settle_position (sub_account_id, trade_date);
CREATE INDEX idx_settle_position_unattributed ON t_settle_position (attribution_source);

-- ===== TB-SETTLE-7 t_settle_fund 系统内结算资金（按子账户；σ 本表 = CTP 账户资金） =====
CREATE TABLE t_settle_fund (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                                        NOT NULL,
    settle_cycle_id       BIGINT                                     NOT NULL,
    trade_date            DATE                                       NOT NULL,
    begin_balance         DECIMAL(20,4)                              NOT NULL,
    equity                DECIMAL(20,4)                              NOT NULL,
    available_fund        DECIMAL(20,4)                              NOT NULL,
    margin_occupied       DECIMAL(20,4)                              NOT NULL,
    frozen_margin         DECIMAL(20,4)                              NULL,
    amount_in             DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    amount_out            DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    transfer_in           DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    transfer_out          DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    close_pnl             DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    float_pnl             DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    commission            DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    margin_change         DECIMAL(20,4)             DEFAULT 0        NOT NULL,
    end_balance           DECIMAL(20,4)                              NOT NULL,
    fund_ledger_id        BIGINT                                     NULL,
    attribution_source    VARCHAR(24)                                NOT NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_settle_fund PRIMARY KEY (id),
    CONSTRAINT uk_settle_fund_date_sub UNIQUE (trade_date, sub_account_id),
    CONSTRAINT ck_t_settle_fund_attribution_source CHECK (attribution_source IN ('LEDGER', 'UNABLE_TO_ATTRIBUTE')),
    CONSTRAINT fk_t_settle_fund_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_settle_fund_cycle FOREIGN KEY (settle_cycle_id)
        REFERENCES t_settle_cycle (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_settle_fund                IS '系统内结算资金（按子账户；σ 本表 = CTP 账户资金）';
COMMENT ON COLUMN t_settle_fund.sub_account_id   IS '子账户（必填，可直接求和）';
COMMENT ON COLUMN t_settle_fund.settle_cycle_id  IS '所属结算单';
COMMENT ON COLUMN t_settle_fund.trade_date       IS '交易日';
COMMENT ON COLUMN t_settle_fund.begin_balance    IS '期初余额';
COMMENT ON COLUMN t_settle_fund.equity           IS '权益';
COMMENT ON COLUMN t_settle_fund.available_fund   IS '可用资金';
COMMENT ON COLUMN t_settle_fund.margin_occupied  IS '占用保证金';
COMMENT ON COLUMN t_settle_fund.frozen_margin    IS '冻结保证金';
COMMENT ON COLUMN t_settle_fund.amount_in        IS '当日入金';
COMMENT ON COLUMN t_settle_fund.amount_out       IS '当日出金';
COMMENT ON COLUMN t_settle_fund.transfer_in      IS '当日子账户划拨入';
COMMENT ON COLUMN t_settle_fund.transfer_out     IS '当日子账户划拨出';
COMMENT ON COLUMN t_settle_fund.close_pnl        IS '平仓盈亏';
COMMENT ON COLUMN t_settle_fund.float_pnl        IS '浮动盈亏';
COMMENT ON COLUMN t_settle_fund.commission       IS '手续费';
COMMENT ON COLUMN t_settle_fund.margin_change    IS '保证金占用变动';
COMMENT ON COLUMN t_settle_fund.end_balance      IS '期末余额';
COMMENT ON COLUMN t_settle_fund.fund_ledger_id   IS '关联的资金台账行';
COMMENT ON COLUMN t_settle_fund.attribution_source IS '归属来源';
CREATE INDEX idx_settle_fund_date ON t_settle_fund (trade_date);
CREATE INDEX idx_settle_fund_sub_date ON t_settle_fund (sub_account_id, trade_date);
CREATE INDEX idx_settle_fund_ledger ON t_settle_fund (fund_ledger_id);

-- ===== TB-SETTLE-8 t_settle_pnl 结算盈亏（平仓/浮动/手续费/保证金，口径全系统唯一） =====
CREATE TABLE t_settle_pnl (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                                        NOT NULL,
    settle_cycle_id       BIGINT                                     NOT NULL,
    trade_date            DATE                                       NOT NULL,
    instrument_id         VARCHAR(32)                                NOT NULL,
    product_id            BIGINT                                     NOT NULL,
    hedge_plan_id         BIGINT                                     NULL,
    pnl_type              VARCHAR(24)                                NOT NULL,
    amount                DECIMAL(20,4)                              NOT NULL,
    qty                   DECIMAL(20,4)                              NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_settle_pnl PRIMARY KEY (id),
    CONSTRAINT uk_settle_pnl_key UNIQUE (trade_date, sub_account_id, instrument_id, pnl_type),
    CONSTRAINT ck_t_settle_pnl_pnl_type CHECK (pnl_type IN ('CLOSE_REALIZED', 'FLOAT_UNREALIZED', 'COMMISSION', 'MARGIN_CHANGE')),
    CONSTRAINT fk_t_settle_pnl_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_settle_pnl_cycle FOREIGN KEY (settle_cycle_id)
        REFERENCES t_settle_cycle (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_settle_pnl_product FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_settle_pnl_plan FOREIGN KEY (hedge_plan_id)
        REFERENCES t_hedge_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_settle_pnl                 IS '结算盈亏（平仓/浮动/手续费/保证金，口径全系统唯一）';
COMMENT ON COLUMN t_settle_pnl.sub_account_id    IS '子账户（必填，可直接求和）';
COMMENT ON COLUMN t_settle_pnl.settle_cycle_id   IS '所属结算单';
COMMENT ON COLUMN t_settle_pnl.trade_date        IS '交易日';
COMMENT ON COLUMN t_settle_pnl.instrument_id     IS '合约';
COMMENT ON COLUMN t_settle_pnl.product_id        IS '品种';
COMMENT ON COLUMN t_settle_pnl.hedge_plan_id     IS '套保计划归属';
COMMENT ON COLUMN t_settle_pnl.pnl_type          IS '盈亏类型';
COMMENT ON COLUMN t_settle_pnl.amount            IS '金额（可为负）';
COMMENT ON COLUMN t_settle_pnl.qty               IS '对应数量';
CREATE INDEX idx_settle_pnl_date_type ON t_settle_pnl (trade_date, pnl_type);
CREATE INDEX idx_settle_pnl_sub_date ON t_settle_pnl (sub_account_id, trade_date);
CREATE INDEX idx_settle_pnl_plan ON t_settle_pnl (hedge_plan_id, trade_date);

-- ===== TB-SETTLE-9 t_reconcile_record 核对记录（成交/仓位/资金三类；记录本身即合规证据） =====
CREATE TABLE t_reconcile_record (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                                        NULL,
    trade_date            DATE                                       NOT NULL,
    reconcile_type        VARCHAR(24)                                NOT NULL,
    compare_granularity   VARCHAR(16)                                NOT NULL,
    system_count          INT                       DEFAULT 0        NOT NULL,
    ctp_count             INT                       DEFAULT 0        NOT NULL,
    result                VARCHAR(24)                                NOT NULL,
    difference_count      INT                       DEFAULT 0        NOT NULL,
    operator_id           BIGINT                                     NOT NULL,
    reconcile_time        TIMESTAMP                                  NOT NULL,
    remark                VARCHAR(500)                               NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_reconcile_record PRIMARY KEY (id),
    CONSTRAINT ck_t_reconcile_record_reconcile_type CHECK (reconcile_type IN ('TRADE', 'POSITION', 'FUND')),
    CONSTRAINT ck_t_reconcile_record_compare_granularity CHECK (compare_granularity IN ('BY_RECORD', 'BY_SUMMARY')),
    CONSTRAINT ck_t_reconcile_record_result CHECK (result IN ('CONSISTENT', 'HAS_DIFFERENCE', 'NOT_COMPLETED')),
    CONSTRAINT fk_t_reconcile_record_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_reconcile_record           IS '核对记录（成交/仓位/资金三类；记录本身即合规证据）';
COMMENT ON COLUMN t_reconcile_record.sub_account_id IS '涉及子账户（差异可定位时非空）';
COMMENT ON COLUMN t_reconcile_record.trade_date  IS '交易日';
COMMENT ON COLUMN t_reconcile_record.reconcile_type IS '核对类型';
COMMENT ON COLUMN t_reconcile_record.compare_granularity IS '比对粒度';
COMMENT ON COLUMN t_reconcile_record.system_count IS '系统侧条数';
COMMENT ON COLUMN t_reconcile_record.ctp_count   IS '期货公司侧条数';
COMMENT ON COLUMN t_reconcile_record.result      IS '核对结论';
COMMENT ON COLUMN t_reconcile_record.difference_count IS '差异条数';
COMMENT ON COLUMN t_reconcile_record.operator_id IS '核对人';
COMMENT ON COLUMN t_reconcile_record.reconcile_time IS '核对时间';
COMMENT ON COLUMN t_reconcile_record.remark      IS '备注';
CREATE INDEX idx_reconcile_rec_date_type ON t_reconcile_record (trade_date, reconcile_type);
CREATE INDEX idx_reconcile_rec_result ON t_reconcile_record (result, trade_date);
CREATE INDEX idx_reconcile_rec_sub ON t_reconcile_record (sub_account_id, trade_date);

-- ===== TB-SETTLE-10 t_reconcile_difference 核对差异（逐条定位与闭环；未闭环不得标记结算完成） =====
CREATE TABLE t_reconcile_difference (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                                        NULL,
    trade_date            DATE                                       NOT NULL,
    reconcile_record_id   BIGINT                                     NOT NULL,
    difference_type       VARCHAR(16)                                NOT NULL,
    difference_kind       VARCHAR(32)                                NOT NULL,
    biz_key               VARCHAR(128)                               NULL,
    field_name            VARCHAR(64)                                NULL,
    system_value          VARCHAR(128)                               NULL,
    ctp_value             VARCHAR(128)                               NULL,
    diff_qty              DECIMAL(20,4)                              NULL,
    diff_amount           DECIMAL(20,4)                              NULL,
    handle_status         VARCHAR(16)                                NOT NULL,
    handle_method         VARCHAR(16)                                NULL,
    handle_desc           VARCHAR(1000)                              NULL,
    owner_id              BIGINT                                     NULL,
    handler_id            BIGINT                                     NULL,
    reviewer_id           BIGINT                                     NULL,
    found_time            TIMESTAMP                                  NOT NULL,
    closed_time           TIMESTAMP                                  NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_reconcile_difference PRIMARY KEY (id),
    CONSTRAINT ck_t_reconcile_difference_difference_type CHECK (difference_type IN ('TRADE', 'POSITION', 'FUND')),
    CONSTRAINT ck_t_reconcile_difference_difference_kind CHECK (difference_kind IN ('SYS_ONLY', 'CTP_ONLY', 'VALUE_MISMATCH')),
    CONSTRAINT ck_t_reconcile_difference_handle_status CHECK (handle_status IN ('PENDING', 'HANDLING', 'CLOSED')),
    CONSTRAINT ck_t_reconcile_difference_handle_method CHECK (handle_method IN ('SUPPLEMENT', 'REVERSE', 'ADJUST')),
    CONSTRAINT fk_t_reconcile_difference_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_reconcile_difference_record FOREIGN KEY (reconcile_record_id)
        REFERENCES t_reconcile_record (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_reconcile_difference       IS '核对差异（逐条定位与闭环；未闭环不得标记结算完成）';
COMMENT ON COLUMN t_reconcile_difference.sub_account_id IS '差异涉及子账户（可定位时非空）';
COMMENT ON COLUMN t_reconcile_difference.trade_date IS '交易日';
COMMENT ON COLUMN t_reconcile_difference.reconcile_record_id IS '所属核对记录';
COMMENT ON COLUMN t_reconcile_difference.difference_type IS '差异类型';
COMMENT ON COLUMN t_reconcile_difference.difference_kind IS '差异种类';
COMMENT ON COLUMN t_reconcile_difference.biz_key IS '定位键';
COMMENT ON COLUMN t_reconcile_difference.field_name IS '不一致字段名';
COMMENT ON COLUMN t_reconcile_difference.system_value IS '系统侧取值';
COMMENT ON COLUMN t_reconcile_difference.ctp_value IS '期货公司侧取值';
COMMENT ON COLUMN t_reconcile_difference.diff_qty IS '数量差';
COMMENT ON COLUMN t_reconcile_difference.diff_amount IS '金额差';
COMMENT ON COLUMN t_reconcile_difference.handle_status IS '处理状态';
COMMENT ON COLUMN t_reconcile_difference.handle_method IS '处理方式';
COMMENT ON COLUMN t_reconcile_difference.handle_desc IS '处理说明';
COMMENT ON COLUMN t_reconcile_difference.owner_id IS '责任人';
COMMENT ON COLUMN t_reconcile_difference.handler_id IS '处理人';
COMMENT ON COLUMN t_reconcile_difference.reviewer_id IS '复核人';
COMMENT ON COLUMN t_reconcile_difference.found_time IS '发现时间';
COMMENT ON COLUMN t_reconcile_difference.closed_time IS '闭环时间';
CREATE INDEX idx_reconcile_diff_date_status ON t_reconcile_difference (trade_date, handle_status);
CREATE INDEX idx_reconcile_diff_type_status ON t_reconcile_difference (difference_type, handle_status);
CREATE INDEX idx_reconcile_diff_sub_date ON t_reconcile_difference (sub_account_id, trade_date);
CREATE INDEX idx_reconcile_diff_bizkey ON t_reconcile_difference (biz_key);

-- ===== TB-SETTLE-11 t_aggregation_check 加总对账记录（σ 子账户 = 真实账户；阻断状态唯一载体） =====
CREATE TABLE t_aggregation_check (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    trade_date            DATE                                       NOT NULL,
    check_type            VARCHAR(24)                                NOT NULL,
    sum_sub_account       DECIMAL(20,4)                              NOT NULL,
    real_account_value    DECIMAL(20,4)                              NOT NULL,
    difference            DECIMAL(20,4)                              NOT NULL,
    result                VARCHAR(24)                                NOT NULL,
    possible_sub_accounts VARCHAR(500)                               NULL,
    block_scope           VARCHAR(32)                                NULL,
    block_effective_time  TIMESTAMP                                  NULL,
    alert_time            TIMESTAMP                                  NULL,
    check_time            TIMESTAMP                                  NOT NULL,
    closed_time           TIMESTAMP                                  NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_aggregation_check PRIMARY KEY (id),
    CONSTRAINT ck_t_aggregation_check_check_type CHECK (check_type IN ('POSITION', 'FUND', 'PNL', 'MARGIN')),
    CONSTRAINT ck_t_aggregation_check_result CHECK (result IN ('CONSISTENT', 'INCONSISTENT_BLOCKED'))
);
COMMENT ON TABLE  t_aggregation_check          IS '加总对账记录（σ 子账户 = 真实账户；阻断状态唯一载体）';
COMMENT ON COLUMN t_aggregation_check.trade_date IS '交易日';
COMMENT ON COLUMN t_aggregation_check.check_type IS '对账类型';
COMMENT ON COLUMN t_aggregation_check.sum_sub_account IS 'σ 子账户';
COMMENT ON COLUMN t_aggregation_check.real_account_value IS '真实期货账户对应值';
COMMENT ON COLUMN t_aggregation_check.difference IS '差额';
COMMENT ON COLUMN t_aggregation_check.result     IS '对账结论';
COMMENT ON COLUMN t_aggregation_check.possible_sub_accounts IS '可能来源子账户清单（展示缓存，禁作判定来源）';
COMMENT ON COLUMN t_aggregation_check.block_scope IS '阻断范围（CFG-11）';
COMMENT ON COLUMN t_aggregation_check.block_effective_time IS '阻断生效时间';
COMMENT ON COLUMN t_aggregation_check.alert_time IS '告警可见时间';
COMMENT ON COLUMN t_aggregation_check.check_time IS '校验执行时间';
COMMENT ON COLUMN t_aggregation_check.closed_time IS '阻断解除时间';
CREATE INDEX idx_agg_check_date_type ON t_aggregation_check (trade_date, check_type);
CREATE INDEX idx_agg_check_result ON t_aggregation_check (result, trade_date);
CREATE INDEX idx_agg_check_block ON t_aggregation_check (block_effective_time, closed_time);

-- ===== TB-SETTLE-16 t_agg_diff_candidate 加总差异候选归属（机判唯一取数来源，Q-11；只增不改） =====
CREATE TABLE t_agg_diff_candidate (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                                        NOT NULL,
    aggregation_check_id  BIGINT                                     NOT NULL,
    candidate_rank        INT                                        NOT NULL,
    diff_amount           DECIMAL(20,4)                              NOT NULL,
    resolution_status     VARCHAR(16)               DEFAULT 'PENDING' NOT NULL,
    reconcile_difference_id BIGINT                                     NULL,
    evidence_desc         VARCHAR(500)                               NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_agg_diff_candidate PRIMARY KEY (id),
    CONSTRAINT uk_agg_diff_candidate UNIQUE (aggregation_check_id, sub_account_id),
    CONSTRAINT ck_t_agg_diff_candidate_resolution_status CHECK (resolution_status IN ('PENDING', 'CONFIRMED', 'EXCLUDED')),
    CONSTRAINT fk_t_agg_diff_candidate_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_agg_diff_candidate_check FOREIGN KEY (aggregation_check_id)
        REFERENCES t_aggregation_check (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_agg_diff_candidate_diff FOREIGN KEY (reconcile_difference_id)
        REFERENCES t_reconcile_difference (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_agg_diff_candidate         IS '加总差异候选归属（机判唯一取数来源，Q-11；只增不改）';
COMMENT ON COLUMN t_agg_diff_candidate.sub_account_id IS '候选归属子账户（恒 > 0，不得为哨兵值 0）';
COMMENT ON COLUMN t_agg_diff_candidate.aggregation_check_id IS '所属加总对账记录';
COMMENT ON COLUMN t_agg_diff_candidate.candidate_rank IS '候选排序（1 = 最可能）';
COMMENT ON COLUMN t_agg_diff_candidate.diff_amount IS '该子账户差额贡献量';
COMMENT ON COLUMN t_agg_diff_candidate.resolution_status IS '定位状态';
COMMENT ON COLUMN t_agg_diff_candidate.reconcile_difference_id IS '关联的差异记录（闭环）';
COMMENT ON COLUMN t_agg_diff_candidate.evidence_desc IS '归属判据说明';
CREATE INDEX idx_agg_diff_candidate_check_rank ON t_agg_diff_candidate (aggregation_check_id, candidate_rank);
CREATE INDEX idx_agg_diff_candidate_sub_status ON t_agg_diff_candidate (sub_account_id, resolution_status);

-- ===== TB-SETTLE-12 t_settle_report 结算报告 / 风控日报（不一致须显式标注，不得输出正常结论） =====
CREATE TABLE t_settle_report (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                                        NULL,
    trade_date            DATE                                       NOT NULL,
    report_type           VARCHAR(24)                                NOT NULL,
    agg_result            VARCHAR(24)                                NOT NULL,
    content_json          CLOB                                       NOT NULL,
    content_path          VARCHAR(256)                               NULL,
    data_gap_flag         CHAR(1)                   DEFAULT 'N'      NOT NULL,
    gap_desc              VARCHAR(1000)                              NULL,
    generated_by          BIGINT                                     NOT NULL,
    generated_time        TIMESTAMP                                  NOT NULL,
    missing_flag          CHAR(1)                   DEFAULT 'N'      NOT NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_settle_report PRIMARY KEY (id),
    CONSTRAINT uk_settle_report_date_type_sub UNIQUE (trade_date, report_type, sub_account_id),
    CONSTRAINT ck_t_settle_report_report_type CHECK (report_type IN ('DAILY_SETTLE', 'DAILY_RISK')),
    CONSTRAINT ck_t_settle_report_agg_result CHECK (agg_result IN ('CONSISTENT', 'INCONSISTENT_BLOCKED', 'NOT_APPLICABLE')),
    CONSTRAINT ck_t_settle_report_data_gap_flag CHECK (data_gap_flag IN ('Y', 'N')),
    CONSTRAINT ck_t_settle_report_missing_flag CHECK (missing_flag IN ('Y', 'N')),
    CONSTRAINT fk_t_settle_report_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_settle_report              IS '结算报告 / 风控日报（不一致须显式标注，不得输出正常结论）';
COMMENT ON COLUMN t_settle_report.sub_account_id IS '子账户（全账户汇总为 NULL）';
COMMENT ON COLUMN t_settle_report.trade_date     IS '交易日';
COMMENT ON COLUMN t_settle_report.report_type    IS '报告类型';
COMMENT ON COLUMN t_settle_report.agg_result     IS '所依据的加总对账结论';
COMMENT ON COLUMN t_settle_report.content_json   IS '报告内容（结构化）';
COMMENT ON COLUMN t_settle_report.content_path   IS '生成的文件路径';
COMMENT ON COLUMN t_settle_report.data_gap_flag  IS '是否存在数据缺口';
COMMENT ON COLUMN t_settle_report.gap_desc       IS '缺口说明';
COMMENT ON COLUMN t_settle_report.generated_by   IS '生成人';
COMMENT ON COLUMN t_settle_report.generated_time IS '生成时间';
COMMENT ON COLUMN t_settle_report.missing_flag   IS '该日报告是否缺失';
CREATE INDEX idx_settle_report_date_type ON t_settle_report (trade_date, report_type);
CREATE INDEX idx_settle_report_agg ON t_settle_report (agg_result, trade_date);
CREATE INDEX idx_settle_report_missing ON t_settle_report (missing_flag, trade_date);

-- ===== TB-SETTLE-13 t_check_task 核对任务（月度/季度/年度；需指定角色签署） =====
CREATE TABLE t_check_task (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    check_type            VARCHAR(16)                                NOT NULL,
    period_key            VARCHAR(16)                                NOT NULL,
    sub_account_id        INT                       DEFAULT 0        NOT NULL,
    required_sign_roles   VARCHAR(128)                               NOT NULL,
    signed_roles          VARCHAR(128)              DEFAULT ''       NOT NULL,
    status                VARCHAR(24)                                NOT NULL,
    snapshot_hash         VARCHAR(128)                               NOT NULL,
    snapshot_json         CLOB                                       NULL,
    check_items           VARCHAR(500)                               NOT NULL,
    check_conclusion      VARCHAR(1000)                              NULL,
    content_json          CLOB                                       NULL,
    submitted_to          VARCHAR(128)                               NULL,
    deadline_date         DATE                                       NOT NULL,
    overdue_alerted       CHAR(1)                   DEFAULT 'N'      NOT NULL,
    generated_time        TIMESTAMP                                  NOT NULL,
    completed_time        TIMESTAMP                                  NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_check_task PRIMARY KEY (id),
    CONSTRAINT uk_check_task_period UNIQUE (check_type, period_key, sub_account_id),
    CONSTRAINT ck_check_task_scope_sentinel CHECK (sub_account_id >= 0),
    CONSTRAINT ck_t_check_task_check_type CHECK (check_type IN ('MONTHLY', 'QUARTERLY', 'ANNUAL')),
    CONSTRAINT ck_t_check_task_status CHECK (status IN ('PENDING_SIGN', 'PARTIAL_SIGNED', 'COMPLETED', 'OVERDUE')),
    CONSTRAINT ck_t_check_task_overdue_alerted CHECK (overdue_alerted IN ('Y', 'N')),
    CONSTRAINT fk_t_check_task_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_check_task                 IS '核对任务（月度/季度/年度；需指定角色签署）';
COMMENT ON COLUMN t_check_task.check_type        IS '核对类型';
COMMENT ON COLUMN t_check_task.period_key        IS '期间键';
COMMENT ON COLUMN t_check_task.sub_account_id    IS '涉及子账户（跨子账户取哨兵值 0）';
COMMENT ON COLUMN t_check_task.required_sign_roles IS '应签角色清单';
COMMENT ON COLUMN t_check_task.signed_roles      IS '已签角色';
COMMENT ON COLUMN t_check_task.status            IS '任务状态';
COMMENT ON COLUMN t_check_task.snapshot_hash     IS '核对对象快照哈希';
COMMENT ON COLUMN t_check_task.snapshot_json     IS '快照内容';
COMMENT ON COLUMN t_check_task.check_items       IS '核对项';
COMMENT ON COLUMN t_check_task.check_conclusion  IS '核对结论与说明';
COMMENT ON COLUMN t_check_task.content_json      IS '季度报告内容';
COMMENT ON COLUMN t_check_task.submitted_to      IS '提交/签收对象';
COMMENT ON COLUMN t_check_task.deadline_date     IS '签署截止日';
COMMENT ON COLUMN t_check_task.overdue_alerted   IS '逾期是否已告警';
COMMENT ON COLUMN t_check_task.generated_time    IS '任务生成时间';
COMMENT ON COLUMN t_check_task.completed_time    IS '全部签署完成时间';
CREATE INDEX idx_check_task_status_deadline ON t_check_task (status, deadline_date);
CREATE INDEX idx_check_task_period_time ON t_check_task (period_key, generated_time);
CREATE INDEX idx_check_task_snapshot_hash ON t_check_task (snapshot_hash);

-- ===== TB-SETTLE-15 t_check_signature 核对签署记录（不可代签；uk_check_signature_task_role 下沉唯一约束） =====
CREATE TABLE t_check_signature (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                       DEFAULT 0        NOT NULL,
    check_task_id         BIGINT                                     NOT NULL,
    sign_role             VARCHAR(24)                                NOT NULL,
    signer_id             BIGINT                                     NOT NULL,
    signer_role_code      VARCHAR(32)                                NOT NULL,
    sign_time             TIMESTAMP                                  NOT NULL,
    sign_ip               VARCHAR(64)                                NOT NULL,
    snapshot_hash         VARCHAR(128)                               NOT NULL,
    sign_result           VARCHAR(16)                                NOT NULL,
    sign_opinion          VARCHAR(1000)                              NULL,
    proxy_rejected        CHAR(1)                   DEFAULT 'N'      NOT NULL,
    manage_role_trade_blocked CHAR(1)                   DEFAULT 'N'      NOT NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_check_signature PRIMARY KEY (id),
    CONSTRAINT uk_check_signature_task_role UNIQUE (check_task_id, sign_role),
    CONSTRAINT ck_check_signature_scope_sentinel CHECK (sub_account_id >= 0),
    CONSTRAINT ck_t_check_signature_sign_role CHECK (sign_role IN ('RISK', 'TRADE', 'FINANCE', 'MANAGE', 'AUDITOR')),
    CONSTRAINT ck_t_check_signature_sign_result CHECK (sign_result IN ('SIGNED', 'REJECTED')),
    CONSTRAINT ck_t_check_signature_proxy_rejected CHECK (proxy_rejected IN ('Y', 'N')),
    CONSTRAINT ck_t_check_signature_manage_role_trade_blocked CHECK (manage_role_trade_blocked IN ('Y', 'N')),
    CONSTRAINT fk_t_check_signature_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_check_signature_task FOREIGN KEY (check_task_id)
        REFERENCES t_check_task (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_t_check_signature_signer FOREIGN KEY (signer_id)
        REFERENCES t_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_check_signature            IS '核对签署记录（不可代签；uk_check_signature_task_role 下沉唯一约束）';
COMMENT ON COLUMN t_check_signature.sub_account_id IS '涉及子账户（随任务头；跨子账户取哨兵值 0）';
COMMENT ON COLUMN t_check_signature.check_task_id IS '所属核对任务';
COMMENT ON COLUMN t_check_signature.sign_role    IS '签署角色';
COMMENT ON COLUMN t_check_signature.signer_id    IS '签署人账号';
COMMENT ON COLUMN t_check_signature.signer_role_code IS '签署人实际角色编码';
COMMENT ON COLUMN t_check_signature.sign_time    IS '签署时间';
COMMENT ON COLUMN t_check_signature.sign_ip      IS '签署 IP';
COMMENT ON COLUMN t_check_signature.snapshot_hash IS '签署时快照哈希';
COMMENT ON COLUMN t_check_signature.sign_result  IS '签署结果';
COMMENT ON COLUMN t_check_signature.sign_opinion IS '签署意见/异议说明';
COMMENT ON COLUMN t_check_signature.proxy_rejected IS '是否因代签被拒';
COMMENT ON COLUMN t_check_signature.manage_role_trade_blocked IS '管理层签署权与交易权互斥核验留痕';
CREATE INDEX idx_check_signature_signer ON t_check_signature (signer_id, sign_time);
CREATE INDEX idx_check_signature_role_time ON t_check_signature (sign_role, sign_time);
CREATE INDEX idx_check_signature_task ON t_check_signature (check_task_id, sign_time);
CREATE INDEX idx_check_signature_proxy ON t_check_signature (proxy_rejected, sign_time);

-- ===== TB-SETTLE-14 t_stress_test 压力测试结果（情景化保证金缺口估算） =====
CREATE TABLE t_stress_test (
    id                    BIGINT                                     IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                                        NULL,
    test_date             DATE                                       NOT NULL,
    scenario_json         CLOB                                       NOT NULL,
    margin_gap            DECIMAL(20,4)                              NOT NULL,
    additional_requirement DECIMAL(20,4)                              NULL,
    equity_after          DECIMAL(20,4)                              NULL,
    risk_ratio_after      DECIMAL(9,6)                               NULL,
    operator_id           BIGINT                                     NULL,
    test_time             TIMESTAMP                                  NOT NULL,
    created_by            BIGINT                                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法
    created_time          TIMESTAMP                 DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                                     NULL,
    updated_time          TIMESTAMP                                  NULL,
    row_version           INT                       DEFAULT 0        NOT NULL,
    CONSTRAINT pk_t_stress_test PRIMARY KEY (id),
    CONSTRAINT fk_t_stress_test_sub FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_stress_test                IS '压力测试结果（情景化保证金缺口估算）';
COMMENT ON COLUMN t_stress_test.sub_account_id   IS '子账户（NULL = 全账户）';
COMMENT ON COLUMN t_stress_test.test_date        IS '测试基准日';
COMMENT ON COLUMN t_stress_test.scenario_json    IS '情景参数';
COMMENT ON COLUMN t_stress_test.margin_gap       IS '估算的保证金缺口';
COMMENT ON COLUMN t_stress_test.additional_requirement IS '追加需求';
COMMENT ON COLUMN t_stress_test.equity_after     IS '权益估算';
COMMENT ON COLUMN t_stress_test.risk_ratio_after IS '情景后的风险度';
COMMENT ON COLUMN t_stress_test.operator_id      IS '执行人';
COMMENT ON COLUMN t_stress_test.test_time        IS '执行时间';
CREATE INDEX idx_stress_test_date_sub ON t_stress_test (test_date, sub_account_id);
CREATE INDEX idx_stress_test_time ON t_stress_test (test_time);

-- =============================================================================
--  跨模块外键（依赖其他里程碑脚本）
--
--  ⚠️ 执行顺序：以下约束指向 FUND 模块的 t_fund_ledger，**本脚本单独执行时须先建该表**，
--     否则本段会失败（不影响上面 16 张表的建表结果）。
--     ① fk_t_settle_fund_ledger → t_fund_ledger(TB-FUND-2)：由 FUND 模块建表脚本创建。
--     ② 本约束原为表内联，因 SETTLE↔FUND 互为前置（FUND 侧 t_fund_flow.settle_cycle_id /
--        t_transfer_apply.agg_check_id / t_fund_reconcile.agg_check_id 内联指向 SETTLE）而
--        后置，与 m1_plan.sql 末尾 fk_trade_plan_strategy 同口径；建表段可先于 m2_fund.sql
--        执行，本段在 m2_fund.sql 之后回补（T-052 部署清单登记：README §7.1 / §8.4，NFR-DEPLOY-2）。
--     级联规则一律 RESTRICT（§0.4：禁止级联删除）。
-- =============================================================================
ALTER TABLE t_settle_fund ADD CONSTRAINT fk_t_settle_fund_ledger
    FOREIGN KEY (fund_ledger_id) REFERENCES t_fund_ledger (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

-- =============================================================================
--  本脚本自检
--    表数：16 = DATA_MODEL.md §2.7 SETTLE（TB-SETTLE-1~16）
--    加总差异机判：t_agg_diff_candidate（候选态，TB-SETTLE-16）+
--      t_reconcile_difference.sub_account_id（定位态，TB-SETTLE-10）字段齐备
--    uk_check_signature_task_role = 不可代签/不可重复签的数据库级唯一约束
-- =============================================================================
