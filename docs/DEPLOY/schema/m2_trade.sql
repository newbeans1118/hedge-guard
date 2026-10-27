-- =============================================================================
--  HedgeGuard 建表脚本 —— 模块 TRADE（交易执行），10 张
--  制品编号：docs/DEPLOY/schema/m2_trade.sql
--  依据：docs/DATA_MODEL.md §2.6 TRADE（TB-TRADE-1~10，10 张）+ §0.2 字段类型
--        + §0.3 通用审计字段 + §0.4 外键与级联 + §0.5 DM8 建表模板 + §3.1 枚举 + §4.3 / §4.6.3
--        （v1.3.10：T-206/cr-t088 R4-002 新增 TB-TRADE-10 t_trade_l3_reject）
--  目标库：达梦 DM8（PROJECT.md §5.1）
--  执行者：DBA（应用账号仅 SELECT/INSERT/UPDATE，禁 DDL —— NFR-SEC-8）
--
--  ⚠️ 未实测声明：本环境无 DM8 实例，本脚本未在 DM8 实机执行。
--     等价证据见 java 侧 TradeSchemaTest（文档 ↔ 脚本逐列/索引/外键/子账户贯穿）
--     与 TradeDm8EquivalentIntegrationTest（喂入内存引擎建表 → 真实 DAO 读写）。
--
--  ⚠️ 执行顺序（跨文件前置；两文件互为前置，须两阶段执行）：
--     t_trade_order / t_trade_fill / t_trade_reason / t_option_strategy 引用
--       t_trade_plan / t_hedge_plan（PLAN，m1_plan.sql）；t_trade_order 亦引用 t_exposure（SPOT）。
--     m1_plan.sql 末尾的 fk_trade_plan_strategy → t_option_strategy（本文件 TB-TRADE-8）
--       又要求本文件先执行（见 m1_plan.sql 文件头与末尾注释）。
--     ⇒ 推荐两阶段：
--        ① 执行 m1_config_auth.sql → m1_product.sql → m1_spot.sql；
--        ② 执行 m1_plan.sql，暂缓其末尾 fk_trade_plan_strategy 段；
--        ③ 执行本文件 m2_trade.sql（表级外键指向已在 ①② 建立的表）；
--        ④ 回补执行 m1_plan.sql 暂缓的跨模块外键段。
--     同文件内：t_trade_order.strategy_id → t_option_strategy、trade_reason_id → t_trade_reason
--       为模块内后置外键（目标表定义于本文件靠后），按先例置于文件末尾 ALTER TABLE 段。
--
--  ⚠️ 脚本纪律（DATA_MODEL.md §0.5）：无存储过程/触发器/自定义函数；无 DML；
--     DM 特性带 -- DM-SPECIFIC: 注释；外键一律 ON DELETE/UPDATE RESTRICT（§0.4）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 模块 TRADE —— 9 张（子账户贯穿，§4.6.3）：① NOT NULL = 8；② 可空 = 0；③ 无该列 = 1
--   （③ = t_replay_log，例外 E3：组件级重放日志，重放是整个组件的行为，非五类数据）
-- -----------------------------------------------------------------------------

-- ===== TB-TRADE-1 t_trade_order 交易指令 =====
-- 必须绑定交易计划 ID 与子账户标识（R-TRADE-1）；order_id 可空（C++ 未受理时无号）+ UNIQUE，
-- DM 唯一约束允许多个 NULL（标准 SQL 语义），故不冲突。
-- 本表在「已发出/已收回报」后写入完整行；事前风控拒绝写 REJECTED_PRECHECK、确认超时写 EXPIRED。
CREATE TABLE t_trade_order (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                        NOT NULL,
    client_order_ref      VARCHAR(32)                NOT NULL,
    order_id              BIGINT                     NULL,
    trade_plan_id         BIGINT                     NOT NULL,
    hedge_plan_id         BIGINT                     NOT NULL,
    exposure_id           BIGINT                     NOT NULL,
    product_id            BIGINT                     NOT NULL,
    instrument_id         VARCHAR(32)                NOT NULL,
    instrument_type       VARCHAR(16)                NOT NULL,
    direction             VARCHAR(16)                NOT NULL,
    offset_flag           VARCHAR(16)                NOT NULL,
    price_type            VARCHAR(16)                NOT NULL,
    limit_price           DECIMAL(20,6)              NULL,
    volume                INT                        NOT NULL,
    operator_id           BIGINT                     NOT NULL,
    strategy_id           BIGINT                     NULL,
    leg_no                INT                        NULL,
    trade_reason_id       BIGINT                     NULL,
    status                VARCHAR(24)                NOT NULL,
    reject_rule_id        VARCHAR(32)                NULL,
    reject_code           VARCHAR(16)                NULL,
    reject_reason         VARCHAR(500)               NULL,
    confirm_operator_id   BIGINT                     NULL,
    confirm_time          TIMESTAMP                  NULL,
    accept_time           TIMESTAMP                  NULL,
    submit_time           TIMESTAMP                  NOT NULL,
    client_ts_ms          BIGINT                     NOT NULL,
    expire_time           TIMESTAMP                  NULL,
    cancel_reason         VARCHAR(500)               NULL,
    cancel_operator_id    BIGINT                     NULL,
    cancel_time           TIMESTAMP                  NULL,
    row_version           INT          DEFAULT 0     NOT NULL,
    created_by            BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                     NULL,
    updated_time          TIMESTAMP                  NULL,
    CONSTRAINT pk_t_trade_order            PRIMARY KEY (id),
    CONSTRAINT uk_trade_order_client_ref   UNIQUE (client_order_ref),
    CONSTRAINT uk_trade_order_order_id     UNIQUE (order_id),
    CONSTRAINT ck_trade_order_instrument_type CHECK (instrument_type IN ('FUTURES','OPTION')),
    CONSTRAINT ck_trade_order_direction    CHECK (direction IN ('BUY','SELL')),
    CONSTRAINT ck_trade_order_offset       CHECK (offset_flag IN ('OPEN','CLOSE','CLOSE_TODAY')),
    CONSTRAINT ck_trade_order_price_type   CHECK (price_type IN ('LIMIT','MARKET')),
    CONSTRAINT ck_trade_order_status       CHECK (status IN
        ('PENDING_CONFIRM','REJECTED_PRECHECK','SUBMITTED','SENT_CTP',
         'PARTIAL_FILLED','ALL_FILLED','CANCELLED','REJECTED_CTP','EXPIRED')),
    CONSTRAINT ck_trade_order_volume_positive CHECK (volume > 0),
    CONSTRAINT fk_trade_order_sub          FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_order_plan         FOREIGN KEY (trade_plan_id) REFERENCES t_trade_plan (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_order_hedge_plan   FOREIGN KEY (hedge_plan_id) REFERENCES t_hedge_plan (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_order_exposure     FOREIGN KEY (exposure_id) REFERENCES t_exposure (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_order_product      FOREIGN KEY (product_id) REFERENCES t_product (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
    -- strategy_id / trade_reason_id 的后置外键见文件末尾「模块内后置外键」段
);
COMMENT ON TABLE  t_trade_order                  IS '交易指令（拟发往 CTP 的下单请求；必须绑定交易计划 ID 与子账户，R-TRADE-1）';
COMMENT ON COLUMN t_trade_order.sub_account_id   IS '子账户标识（必填；随指令显式携带，不得由会话/计划间接推导）';
COMMENT ON COLUMN t_trade_order.client_order_ref IS 'Java 侧幂等键（HG<yyyyMMdd><seq>）；对应 CMD_PLACE_ORDER.client_order_ref';
COMMENT ON COLUMN t_trade_order.order_id         IS 'C++ 侧指令号（未受理为 NULL）；DM UNIQUE 允许多 NULL';
COMMENT ON COLUMN t_trade_order.trade_plan_id    IS '交易计划 ID（必填；0 视为缺失 → 协议拒绝 0x1020）';
COMMENT ON COLUMN t_trade_order.hedge_plan_id    IS '套保计划（冗余，便于追溯链快速跳转）';
COMMENT ON COLUMN t_trade_order.exposure_id      IS '实货敞口 ID';
COMMENT ON COLUMN t_trade_order.status           IS 'PENDING_CONFIRM/REJECTED_PRECHECK/SUBMITTED/SENT_CTP/PARTIAL_FILLED/ALL_FILLED/CANCELLED/REJECTED_CTP/EXPIRED';
COMMENT ON COLUMN t_trade_order.confirm_operator_id IS '人工确认人（必须与 operator_id 一致，否则回 0x0305）';
COMMENT ON COLUMN t_trade_order.expire_time      IS '待确认失效时间（submit_time + HG.TRADE.CONFIRM_TIMEOUT_MS）';
COMMENT ON COLUMN t_trade_order.cancel_reason    IS '撤单原因（status=CANCELLED 时必填，服务层校验；撤单须可自证）';
CREATE INDEX idx_trade_order_sub_status_time ON t_trade_order (sub_account_id, status, submit_time);
CREATE INDEX idx_trade_order_plan            ON t_trade_order (trade_plan_id);
CREATE INDEX idx_trade_order_exposure        ON t_trade_order (exposure_id);
CREATE INDEX idx_trade_order_instrument_time ON t_trade_order (instrument_id, submit_time);
CREATE INDEX idx_trade_order_strategy        ON t_trade_order (strategy_id, leg_no);
CREATE INDEX idx_trade_order_pending         ON t_trade_order (status, expire_time);

-- ===== TB-TRADE-2 t_trade_order_report 委托（报单）回报 =====
-- 与交易指令一一对应；幂等键 = (order_sys_id, order_status, volume_traded)。
CREATE TABLE t_trade_order_report (
    id                 BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id     INT                        NOT NULL,
    order_sys_id       VARCHAR(32)                NOT NULL,
    order_id           BIGINT                     NOT NULL,
    order_local_id     VARCHAR(32)                NULL,
    exchange_id        VARCHAR(16)                NULL,
    instrument_id      VARCHAR(32)                NOT NULL,
    direction          VARCHAR(16)                NOT NULL,
    offset_flag        VARCHAR(16)                NOT NULL,
    order_status       VARCHAR(32)                NOT NULL,
    limit_price        DECIMAL(20,6)              NULL,
    volume_total       INT                        NOT NULL,
    volume_traded      INT          DEFAULT 0     NOT NULL,
    volume_left        INT          DEFAULT 0     NOT NULL,
    ctp_error_id       INT                        NULL,
    ctp_error_msg      VARCHAR(500)               NULL,
    report_ts_ms       BIGINT                     NOT NULL,
    report_time        TIMESTAMP                  NOT NULL,
    event_seq          BIGINT                     NOT NULL,
    created_by         BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by         BIGINT                     NULL,
    updated_time       TIMESTAMP                  NULL,
    row_version        INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_trade_order_report     PRIMARY KEY (id),
    CONSTRAINT uk_order_report_idem        UNIQUE (order_sys_id, order_status, volume_traded),
    CONSTRAINT ck_order_report_direction   CHECK (direction IN ('BUY','SELL')),
    CONSTRAINT ck_order_report_offset      CHECK (offset_flag IN ('OPEN','CLOSE','CLOSE_TODAY')),
    CONSTRAINT fk_trade_order_report_sub   FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_order_report_order FOREIGN KEY (order_id) REFERENCES t_trade_order (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_trade_order_report          IS '委托（报单）回报（与指令一一对应；幂等键 order_sys_id+order_status+volume_traded）';
COMMENT ON COLUMN t_trade_order_report.order_sys_id  IS 'CTP 报单编号（幂等键）';
COMMENT ON COLUMN t_trade_order_report.order_status  IS 'CTP 委托状态（保留 CTP 原文语义）';
COMMENT ON COLUMN t_trade_order_report.ctp_error_msg IS 'CTP 错误信息原文（FR-TRADE-4 验收 3）';
COMMENT ON COLUMN t_trade_order_report.event_seq     IS '事件流序号（缺口检测与补数比对）';
CREATE INDEX idx_order_report_order      ON t_trade_order_report (order_id, report_ts_ms);
CREATE INDEX idx_order_report_sub_time   ON t_trade_order_report (sub_account_id, report_time);
CREATE INDEX idx_order_report_instrument ON t_trade_order_report (instrument_id, report_time);

-- ===== TB-TRADE-3 t_trade_fill 成交记录 =====
-- 结算与审计的原始依据，不得修改、不得删除（R-TRADE-15）；幂等键 = trade_id。
CREATE TABLE t_trade_fill (
    id                   BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id       INT                        NOT NULL,
    trade_id             VARCHAR(64)                NOT NULL,
    order_id             BIGINT                     NOT NULL,
    order_sys_id         VARCHAR(32)                NULL,
    trade_plan_id        BIGINT                     NOT NULL,
    hedge_plan_id        BIGINT                     NOT NULL,
    exposure_id          BIGINT                     NOT NULL,
    product_id           BIGINT                     NOT NULL,
    instrument_id        VARCHAR(32)                NOT NULL,
    instrument_type      VARCHAR(16)                NOT NULL,
    direction            VARCHAR(16)                NOT NULL,
    offset_flag          VARCHAR(16)                NOT NULL,
    fill_price           DECIMAL(20,6)              NOT NULL,
    fill_volume          INT                        NOT NULL,
    contract_multiplier  DECIMAL(20,6)              NULL,
    commission           DECIMAL(20,4)              NULL,
    trade_ts_ms          BIGINT                     NOT NULL,
    trade_date           DATE                       NOT NULL,
    fill_time            TIMESTAMP                  NOT NULL,
    event_seq            BIGINT                     NOT NULL,
    settled_flag         CHAR(1)      DEFAULT 'N'   NOT NULL,
    created_by           BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by           BIGINT                     NULL,
    updated_time         TIMESTAMP                  NULL,
    row_version          INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_trade_fill               PRIMARY KEY (id),
    CONSTRAINT uk_trade_fill_id              UNIQUE (trade_id),
    CONSTRAINT ck_trade_fill_instrument_type CHECK (instrument_type IN ('FUTURES','OPTION')),
    CONSTRAINT ck_trade_fill_direction       CHECK (direction IN ('BUY','SELL')),
    CONSTRAINT ck_trade_fill_offset          CHECK (offset_flag IN ('OPEN','CLOSE','CLOSE_TODAY')),
    CONSTRAINT ck_trade_fill_volume_positive CHECK (fill_volume > 0),
    CONSTRAINT ck_trade_fill_settled_flag    CHECK (settled_flag IN ('Y','N')),
    CONSTRAINT fk_trade_fill_sub             FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_fill_order           FOREIGN KEY (order_id) REFERENCES t_trade_order (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_fill_plan            FOREIGN KEY (trade_plan_id) REFERENCES t_trade_plan (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_fill_hedge_plan      FOREIGN KEY (hedge_plan_id) REFERENCES t_hedge_plan (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_fill_exposure        FOREIGN KEY (exposure_id) REFERENCES t_exposure (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_fill_product         FOREIGN KEY (product_id) REFERENCES t_product (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_trade_fill                IS '成交记录（CTP 成交明细；结算与审计原始依据，不可改删；幂等键 trade_id）';
COMMENT ON COLUMN t_trade_fill.sub_account_id IS '子账户标识（由指令携带并随成交写入）；加总对账的关键维度';
COMMENT ON COLUMN t_trade_fill.trade_id       IS 'CTP 成交编号（幂等键，uk_trade_fill_id）';
COMMENT ON COLUMN t_trade_fill.trade_date     IS '交易日（结算与会计期间归属）';
COMMENT ON COLUMN t_trade_fill.settled_flag   IS '是否已纳入日终结算（防漏结算/重复结算）';
CREATE INDEX idx_trade_fill_sub_date        ON t_trade_fill (sub_account_id, trade_date);
CREATE INDEX idx_trade_fill_plan            ON t_trade_fill (trade_plan_id, trade_date);
CREATE INDEX idx_trade_fill_order           ON t_trade_fill (order_id);
CREATE INDEX idx_trade_fill_instrument_date ON t_trade_fill (instrument_id, trade_date);
CREATE INDEX idx_trade_fill_settled         ON t_trade_fill (settled_flag, trade_date);
CREATE INDEX idx_trade_fill_exposure        ON t_trade_fill (exposure_id);

-- ===== TB-TRADE-4 t_trade_reason 交易理由（正当性说明） =====
-- 「每笔交易可自证正当性」的核心载体；一条指令一条理由（uk_trade_reason_order）。
-- related_plan_id / related_exposure_id 物理可空仅承载补填态/异常态，受条件非空 CHECK 约束。
CREATE TABLE t_trade_reason (
    id                            BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id                INT                        NOT NULL,
    trade_order_id                BIGINT                     NOT NULL,
    related_type                  VARCHAR(32)                NOT NULL,
    related_plan_id               BIGINT                     NULL,
    related_exposure_id           BIGINT                     NULL,
    time_desc                     VARCHAR(32)                NOT NULL,
    reason_text                   VARCHAR(1000)              NULL,
    forced_by_freq_alert          CHAR(1)      DEFAULT 'N'   NOT NULL,
    related_object_missing_flag   CHAR(1)      DEFAULT 'N'   NOT NULL,
    operator_id                   BIGINT                     NOT NULL,
    fill_time                     TIMESTAMP                  NOT NULL,
    is_modified                   CHAR(1)      DEFAULT 'N'   NOT NULL,
    before_code                   VARCHAR(1000)              NULL,
    before_text                   VARCHAR(1000)              NULL,
    modified_by                   BIGINT                     NULL,
    modified_time                 TIMESTAMP                  NULL,
    reason_missing_flag           CHAR(1)      DEFAULT 'N'   NOT NULL,
    missing_marked_time           TIMESTAMP                  NULL,
    created_by                    BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time                  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                    BIGINT                     NULL,
    updated_time                  TIMESTAMP                  NULL,
    row_version                   INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_trade_reason              PRIMARY KEY (id),
    CONSTRAINT uk_trade_reason_order          UNIQUE (trade_order_id),
    CONSTRAINT ck_trade_reason_related_type   CHECK (related_type IN
        ('NEW_EXPOSURE','EXPOSURE_DECREASE','PLAN_EXECUTION','ROLLOVER',
         'RISK_DISPOSAL','ERROR_CORRECTION','FORCE_CLOSE','DELIVERY')),
    CONSTRAINT ck_trade_reason_time_desc      CHECK (time_desc IN
        ('CONTRACT_SIGNED','PRICE_TRIGGERED','MONTHLY_REBALANCE','EXPIRY_ROLLOVER','RISK_REQUIRED')),
    CONSTRAINT ck_trade_reason_freq_flag      CHECK (forced_by_freq_alert IN ('Y','N')),
    CONSTRAINT ck_trade_reason_related_missing_flag CHECK (related_object_missing_flag IN ('Y','N')),
    CONSTRAINT ck_trade_reason_is_modified    CHECK (is_modified IN ('Y','N')),
    CONSTRAINT ck_trade_reason_missing_flag   CHECK (reason_missing_flag IN ('Y','N')),
    -- 结构不变量（DATA_MODEL.md §2.6 TB-TRADE-4，v1.2 CH-004）：
    --   关联对象齐备 或 显式标注关联对象缺失；不得两列皆空且未标缺失。
    CONSTRAINT ck_trade_reason_related_or_missing CHECK (
        (related_plan_id IS NOT NULL AND related_exposure_id IS NOT NULL)
        OR related_object_missing_flag = 'Y'),
    CONSTRAINT fk_trade_reason_sub            FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_reason_order          FOREIGN KEY (trade_order_id) REFERENCES t_trade_order (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_reason_plan           FOREIGN KEY (related_plan_id) REFERENCES t_hedge_plan (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_trade_reason_exposure       FOREIGN KEY (related_exposure_id) REFERENCES t_exposure (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_trade_reason                         IS '交易理由（正当性说明；每笔交易可自证正当性，FR-TRADE-10）';
COMMENT ON COLUMN t_trade_reason.related_type            IS '关联类型（结构化必填，8 值：NEW_EXPOSURE/EXPOSURE_DECREASE/PLAN_EXECUTION/ROLLOVER/RISK_DISPOSAL/ERROR_CORRECTION/FORCE_CLOSE/DELIVERY）';
COMMENT ON COLUMN t_trade_reason.related_plan_id         IS '关联对象①计划 ID（物理可空仅用于补填/异常态；条件 CHECK 保证）';
COMMENT ON COLUMN t_trade_reason.related_exposure_id     IS '关联对象②实货敞口 ID（须与 sub_account_id 同子账户，服务层校验 0x1019）';
COMMENT ON COLUMN t_trade_reason.related_object_missing_flag IS '是否关联对象缺失：Y=理由已填但关联未提供（与 reason_missing_flag 并列独立）';
COMMENT ON COLUMN t_trade_reason.reason_missing_flag     IS '是否理由缺失（AUDIT 独立筛查维度 REASON_MISSING）';
CREATE INDEX idx_trade_reason_sub_time          ON t_trade_reason (sub_account_id, fill_time);
CREATE INDEX idx_trade_reason_related_type      ON t_trade_reason (related_type);
CREATE INDEX idx_trade_reason_missing           ON t_trade_reason (reason_missing_flag);
CREATE INDEX idx_trade_reason_related_missing   ON t_trade_reason (related_object_missing_flag);
CREATE INDEX idx_trade_reason_related           ON t_trade_reason (related_plan_id, related_exposure_id);

-- ===== TB-TRADE-5 t_position 持仓明细 =====
-- 落库投影；权威持仓在 C++ 内存（ARCH §4.4.1）；按 EVT_POSITION_CHANGE 增量更新。
-- 因子账户记账，拆分完全依赖本表（R-TRADE-8）。
CREATE TABLE t_position (
    id                     BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id         INT                        NOT NULL,
    instrument_id          VARCHAR(32)                NOT NULL,
    product_id             BIGINT                     NOT NULL,
    direction              VARCHAR(16)                NOT NULL,
    position_type          VARCHAR(16)                NOT NULL,
    position_qty           DECIMAL(20,4) DEFAULT 0    NOT NULL,
    available_qty          DECIMAL(20,4) DEFAULT 0    NOT NULL,
    frozen_qty             DECIMAL(20,4) DEFAULT 0    NOT NULL,
    avg_open_price         DECIMAL(20,6)              NULL,
    last_settlement_price  DECIMAL(20,6)              NULL,
    hedge_plan_id          BIGINT                     NULL,
    as_of_time             TIMESTAMP                  NOT NULL,
    event_seq              BIGINT                     NOT NULL,
    row_version            INT          DEFAULT 0     NOT NULL,
    created_by             BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time           TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by             BIGINT                     NULL,
    updated_time           TIMESTAMP                  NULL,
    CONSTRAINT pk_t_position            PRIMARY KEY (id),
    CONSTRAINT uk_position_key          UNIQUE (sub_account_id, instrument_id, direction, position_type),
    CONSTRAINT ck_position_direction    CHECK (direction IN ('LONG','SHORT')),
    CONSTRAINT ck_position_type         CHECK (position_type IN ('TODAY','YESTERDAY')),
    CONSTRAINT ck_position_qty_consistency CHECK (
        position_qty >= 0 AND available_qty <= position_qty AND frozen_qty <= position_qty),
    CONSTRAINT fk_position_sub          FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_position_product      FOREIGN KEY (product_id) REFERENCES t_product (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_position_hedge_plan   FOREIGN KEY (hedge_plan_id) REFERENCES t_hedge_plan (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_position               IS '持仓明细（子账户 × 合约 × 方向 × 今昨；期货公司侧持仓合并，拆分依赖本表，R-TRADE-8）';
COMMENT ON COLUMN t_position.direction     IS 'LONG（多头）/ SHORT（空头）';
COMMENT ON COLUMN t_position.position_type IS 'TODAY（今仓）/ YESTERDAY（昨仓）';
COMMENT ON COLUMN t_position.row_version   IS '乐观锁（事件并发更新）';
CREATE INDEX idx_position_sub_instrument ON t_position (sub_account_id, instrument_id);
CREATE INDEX idx_position_instrument     ON t_position (instrument_id);
CREATE INDEX idx_position_plan           ON t_position (hedge_plan_id);
CREATE INDEX idx_position_sub_product    ON t_position (sub_account_id, product_id);

-- ===== TB-TRADE-6 t_pre_trade_reject 事前风控拒绝记录 =====
-- 合规证据，只增不改不删（R-TRADE-14）；幂等键 = (client_order_ref, rule_id)。
CREATE TABLE t_pre_trade_reject (
    id                        BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id            INT                        NOT NULL,
    client_order_ref          VARCHAR(32)                NOT NULL,
    order_digest              VARCHAR(500)               NOT NULL,
    product_id                BIGINT                     NULL,
    instrument_id             VARCHAR(32)                NULL,
    direction                 VARCHAR(16)                NULL,
    volume                    INT                        NULL,
    rule_id                   VARCHAR(32)                NOT NULL,
    reject_code               VARCHAR(16)                NOT NULL,
    limit_layer               VARCHAR(24)                NULL,
    exposure_snapshot_id      BIGINT                     NULL,
    exposure_snapshot_time    TIMESTAMP                  NULL,
    current_value             VARCHAR(64)                NOT NULL,
    threshold_value           VARCHAR(64)                NOT NULL,
    base_price_used           DECIMAL(20,6)              NULL,
    base_price_source         VARCHAR(24)                NULL,
    reject_reason             VARCHAR(500)               NOT NULL,
    operator_id               BIGINT                     NOT NULL,
    reject_ts_ms              BIGINT                     NOT NULL,
    reject_time               TIMESTAMP                  NOT NULL,
    trade_date                DATE                       NOT NULL,
    event_seq                 BIGINT                     NOT NULL,
    reported_retry_count      INT          DEFAULT 0     NOT NULL,
    created_by                BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time              TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                BIGINT                     NULL,
    updated_time              TIMESTAMP                  NULL,
    row_version               INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_pre_trade_reject          PRIMARY KEY (id),
    CONSTRAINT uk_pre_trade_reject_idem       UNIQUE (client_order_ref, rule_id),
    CONSTRAINT ck_reject_direction            CHECK (direction IN ('BUY','SELL')),
    CONSTRAINT ck_reject_limit_layer          CHECK (limit_layer IN
        ('SUB_PRODUCT','SUB_INSTRUMENT','ACCOUNT_PRODUCT','AUTH_QUOTA','NONE')),
    CONSTRAINT ck_reject_base_price_source    CHECK (base_price_source IN ('PREV_SETTLEMENT','NOT_APPLICABLE')),
    CONSTRAINT fk_pre_trade_reject_sub        FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pre_trade_reject_product    FOREIGN KEY (product_id) REFERENCES t_product (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_pre_trade_reject                      IS '事前风控拒绝记录（合规证据，只增不改不删；幂等键 client_order_ref+rule_id）';
COMMENT ON COLUMN t_pre_trade_reject.limit_layer          IS '命中的限额层级（5 值：SUB_PRODUCT/SUB_INSTRUMENT/ACCOUNT_PRODUCT/AUTH_QUOTA/NONE；非限额类拒绝一律 NONE；NULL 仅表示历史/导入未命中层级）';
COMMENT ON COLUMN t_pre_trade_reject.exposure_snapshot_id IS '判定所用实货敞口快照 ID（指向 t_exposure.snapshot_id；非敞口相关拒绝为 NULL）';
COMMENT ON COLUMN t_pre_trade_reject.exposure_snapshot_time IS '判定所用实货敞口快照时点（使事前校验依据可在审计中独立复现）';
COMMENT ON COLUMN t_pre_trade_reject.base_price_used      IS '判定所用基准价快照（价格偏离基准 = CTP 昨结算价）';
COMMENT ON COLUMN t_pre_trade_reject.current_value        IS '当前值（无适用值填 "-"）';
COMMENT ON COLUMN t_pre_trade_reject.threshold_value      IS '阈值（无适用值填 "-"）';
CREATE INDEX idx_reject_sub_time     ON t_pre_trade_reject (sub_account_id, reject_time);
CREATE INDEX idx_reject_rule_time    ON t_pre_trade_reject (rule_id, reject_time);
CREATE INDEX idx_reject_date         ON t_pre_trade_reject (trade_date);
CREATE INDEX idx_reject_operator     ON t_pre_trade_reject (operator_id, reject_time);
CREATE INDEX idx_reject_rule_layer   ON t_pre_trade_reject (rule_id, limit_layer);

-- ===== TB-TRADE-7 t_command_flow 指令流水镜像 =====
-- 权威源是 C++ append-only 流水文件（ARCH §4.5.2）；本表为 Java 侧镜像。
-- 幂等键 = (client_order_ref, flow_type, flow_ts_ms)。
CREATE TABLE t_command_flow (
    id                 BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id     INT                        NOT NULL,
    order_id           BIGINT                     NULL,
    client_order_ref   VARCHAR(32)                NOT NULL,
    flow_type          VARCHAR(24)                NOT NULL,
    flow_detail        VARCHAR(1000)              NULL,
    flow_ts_ms         BIGINT                     NOT NULL,
    flow_time          TIMESTAMP                  NOT NULL,
    event_seq          BIGINT                     NOT NULL,
    replay_marker      VARCHAR(16)                NULL,
    created_by         BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by         BIGINT                     NULL,
    updated_time       TIMESTAMP                  NULL,
    row_version        INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_command_flow          PRIMARY KEY (id),
    CONSTRAINT uk_command_flow_idem       UNIQUE (client_order_ref, flow_type, flow_ts_ms),
    CONSTRAINT ck_command_flow_type       CHECK (flow_type IN
        ('CREATED','REJECTED','ACCEPTED','CONFIRMED','SENT',
         'REPORTED','PARTIAL_FILLED','FILLED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_command_flow_replay_marker CHECK (replay_marker IN ('LIVE','REPLAY')),
    CONSTRAINT fk_command_flow_sub        FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_command_flow                 IS '指令流水镜像（生命周期轨迹；权威源为 C++ append-only 流水文件，FR-TRADE-16）';
COMMENT ON COLUMN t_command_flow.flow_type       IS 'CREATED/REJECTED/ACCEPTED/CONFIRMED/SENT/REPORTED/PARTIAL_FILLED/FILLED/CANCELLED/EXPIRED';
COMMENT ON COLUMN t_command_flow.replay_marker   IS 'LIVE / REPLAY（崩溃恢复期间补推的事件）';
CREATE INDEX idx_command_flow_order     ON t_command_flow (order_id, flow_ts_ms);
CREATE INDEX idx_command_flow_ref       ON t_command_flow (client_order_ref, flow_ts_ms);
CREATE INDEX idx_command_flow_sub_time  ON t_command_flow (sub_account_id, flow_time);
CREATE INDEX idx_command_flow_type_time ON t_command_flow (flow_type, flow_time);

-- ===== TB-TRADE-8 t_option_strategy 期权策略 =====
-- 多腿策略头（同链同额）；各腿通过 t_trade_order.strategy_id + leg_no 关联。
CREATE TABLE t_option_strategy (
    id                     BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id         INT                        NOT NULL,
    strategy_no            VARCHAR(32)                NOT NULL,
    strategy_type          VARCHAR(24)                NOT NULL,
    hedge_plan_id          BIGINT                     NOT NULL,
    trade_plan_id          BIGINT                     NOT NULL,
    leg_count              INT                        NOT NULL,
    total_quota_occupied   DECIMAL(20,4)              NULL,
    status                 VARCHAR(24)                NOT NULL,
    operator_id            BIGINT                     NOT NULL,
    submit_time            TIMESTAMP                  NOT NULL,
    created_by             BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time           TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by             BIGINT                     NULL,
    updated_time           TIMESTAMP                  NULL,
    row_version            INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_option_strategy          PRIMARY KEY (id),
    CONSTRAINT uk_option_strategy_no         UNIQUE (strategy_no),
    CONSTRAINT ck_option_strategy_type       CHECK (strategy_type IN
        ('COLLAR','STRADDLE','STRANGLE','BUTTERFLY','CUSTOM')),
    CONSTRAINT ck_option_strategy_status     CHECK (status IN
        ('PENDING','PARTIAL','COMPLETED','CANCELLED')),
    CONSTRAINT fk_option_strategy_sub        FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_option_strategy_hedge_plan FOREIGN KEY (hedge_plan_id) REFERENCES t_hedge_plan (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_option_strategy_trade_plan FOREIGN KEY (trade_plan_id) REFERENCES t_trade_plan (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_option_strategy                       IS '期权策略（多腿策略头；各腿绑定同一套保计划，同链同额，FR-TRADE-13）';
COMMENT ON COLUMN t_option_strategy.strategy_no           IS '策略编号（全系统唯一）';
COMMENT ON COLUMN t_option_strategy.total_quota_occupied  IS '各腿合计占用的套保额度（FR-TRADE-13 验收 2）';
CREATE INDEX idx_option_strategy_hedge_plan ON t_option_strategy (hedge_plan_id);
CREATE INDEX idx_option_strategy_sub_status ON t_option_strategy (sub_account_id, status);

-- ===== TB-TRADE-9 t_replay_log 重放记录 =====
-- 崩溃恢复的启动重放过程与结果（FR-TRADE-15）；重放条数与结果可审计。
-- 例外 E3：组件级重放日志（重放是整个组件的行为），无 sub_account_id 列（§4.6.3 ③）。
CREATE TABLE t_replay_log (
    id                   BIGINT       IDENTITY(1,1) NOT NULL,
    component_id         VARCHAR(64)                NOT NULL,
    replay_seq           INT                        NOT NULL,
    trigger_source       VARCHAR(24)                NOT NULL,
    state                VARCHAR(24)                NOT NULL,
    replayed_records     BIGINT       DEFAULT 0     NOT NULL,
    total_records        BIGINT       DEFAULT 0     NOT NULL,
    in_flight_orders     INT          DEFAULT 0     NOT NULL,
    ctp_matched_count    INT          DEFAULT 0     NOT NULL,
    ctp_mismatch_count   INT          DEFAULT 0     NOT NULL,
    mismatch_detail      CLOB                       NULL,
    started_ts_ms        BIGINT                     NOT NULL,
    finished_ts_ms       BIGINT                     NULL,
    duration_ms          BIGINT                     NULL,
    result               VARCHAR(16)                NULL,
    operator_id          BIGINT                     NULL,
    created_by           BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by           BIGINT                     NULL,
    updated_time         TIMESTAMP                  NULL,
    row_version          INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_replay_log           PRIMARY KEY (id),
    CONSTRAINT uk_replay_log_seq         UNIQUE (component_id, replay_seq),
    CONSTRAINT ck_replay_trigger_source  CHECK (trigger_source IN ('STARTUP','MANUAL')),
    CONSTRAINT ck_replay_state           CHECK (state IN ('NOT_READY','REPLAYING','READY','FAILED')),
    CONSTRAINT ck_replay_result          CHECK (result IN ('OK','MISMATCH','ABORTED'))
);
COMMENT ON TABLE  t_replay_log                   IS '重放记录（崩溃恢复的启动重放过程与结果，FR-TRADE-15；例外 E3 无 sub_account_id）';
COMMENT ON COLUMN t_replay_log.component_id      IS '组件标识（hg-trading）';
COMMENT ON COLUMN t_replay_log.replay_seq        IS '该组件第几次重放';
COMMENT ON COLUMN t_replay_log.in_flight_orders  IS '在途指令数（已发 CTP 未收回报）';
COMMENT ON COLUMN t_replay_log.mismatch_detail   IS '不一致明细（0x0405 的依据）';
COMMENT ON COLUMN t_replay_log.duration_ms       IS '耗时（NFR-REL-1：≤ 60 s）';
CREATE INDEX idx_replay_log_time   ON t_replay_log (started_ts_ms);
CREATE INDEX idx_replay_log_result ON t_replay_log (result, duration_ms);

-- ===== TB-TRADE-10 t_trade_l3_reject L3 护栏/紧急停止拒绝事件（v1.3.10，DC-047） =====
-- cr-t088 R4-002 承接（T-206）：L3 实盘护栏（L3-FUND-CAP，G-6 资金上限）与紧急停止门禁
-- （L3-EMERGENCY-STOP，G-7）在 Java 提交路径（TradeService.submitOrder ②-b/②-c）拒绝下单时的
-- 持久化留痕（此前仅 LOG 结构化告警 + 响应载荷，失效场景见 cr-t088 R4-002：事后审计依赖日志留存策略）。
-- 合规证据，只增不改不删（R-TRADE-14 同族）；无幂等键——每次拒绝尝试均为独立事件，逐笔留行。
-- 语义边界：与 t_fund_quota_alert（R-FUND-7「告警不阻断」，blocked_operation CHECK 恒 'NONE' 冻结）
-- 相互独立——本表记录 L3 硬停拒绝（阻断类 10012，未发 CTP），R-FUND-7 告警面语义零改动。
CREATE TABLE t_trade_l3_reject (
    id                   BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id       INT                        NOT NULL,
    rule_id              VARCHAR(32)                NOT NULL,
    trigger_source       VARCHAR(32)                NOT NULL,
    quota_scope          VARCHAR(16)                NULL,
    product_id           BIGINT                     NULL,
    current_value        VARCHAR(64)                NOT NULL,
    threshold_value      VARCHAR(64)                NOT NULL,
    blocked_operation    VARCHAR(24)                NOT NULL,
    operator_id          BIGINT                     NOT NULL,
    event_time           TIMESTAMP                  NOT NULL,
    created_by           BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by           BIGINT                     NULL,
    updated_time         TIMESTAMP                  NULL,
    row_version          INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_trade_l3_reject        PRIMARY KEY (id),
    CONSTRAINT ck_l3_reject_rule_id        CHECK (rule_id IN ('L3-FUND-CAP','L3-EMERGENCY-STOP')),
    CONSTRAINT ck_l3_reject_trigger_source CHECK (trigger_source IN ('L3-FUND-CAP','L3-EMERGENCY-STOP')),
    CONSTRAINT ck_l3_reject_quota_scope    CHECK (quota_scope IS NULL
        OR quota_scope IN ('SUB_ACCOUNT','ACCOUNT')),
    CONSTRAINT ck_l3_reject_blocked        CHECK (blocked_operation IN ('PLACE_ORDER')),
    CONSTRAINT fk_l3_reject_sub            FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_l3_reject_product        FOREIGN KEY (product_id) REFERENCES t_product (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_trade_l3_reject                   IS 'L3 护栏/紧急停止拒绝事件（提交路径 Java 侧硬停留痕，cr-t088 R4-002/T-206；合规证据只增不改不删）';
COMMENT ON COLUMN t_trade_l3_reject.sub_account_id    IS '触发提交的指令子账户（账户级维度命中时为提交方子账户，占用/阈值为账户级汇总值）';
COMMENT ON COLUMN t_trade_l3_reject.rule_id           IS '护栏规则编号（L3-FUND-CAP 资金上限 / L3-EMERGENCY-STOP 紧急停止；与响应载荷 ruleId 逐字同源）';
COMMENT ON COLUMN t_trade_l3_reject.trigger_source    IS '事件触发来源标识（现值域同 rule_id；为「一规则多来源」预留分化位，工单 T-206 规定承载）';
COMMENT ON COLUMN t_trade_l3_reject.quota_scope       IS '资金上限命中的维度（SUB_ACCOUNT/ACCOUNT；紧急停止拒绝为 NULL）';
COMMENT ON COLUMN t_trade_l3_reject.product_id        IS '品种（提交请求携带时记录；拒绝点位于计划一致性校验之前且请求未携带时为 NULL）';
COMMENT ON COLUMN t_trade_l3_reject.current_value     IS '当前值（响应载荷 current：资金占用值 / emergency_stop=Y）';
COMMENT ON COLUMN t_trade_l3_reject.threshold_value   IS '阈值（响应载荷 threshold：资金上限值 / 新提交=全部拒绝）';
COMMENT ON COLUMN t_trade_l3_reject.blocked_operation IS '被阻断的操作（现唯一取值 PLACE_ORDER 下单提交）';
COMMENT ON COLUMN t_trade_l3_reject.operator_id       IS '操作人（提交人，留痕）';
COMMENT ON COLUMN t_trade_l3_reject.event_time        IS '事件时间（拒绝判定时点，业务时钟）';
CREATE INDEX idx_l3_reject_sub_time  ON t_trade_l3_reject (sub_account_id, event_time);
CREATE INDEX idx_l3_reject_rule_time ON t_trade_l3_reject (rule_id, event_time);

-- -----------------------------------------------------------------------------
-- 模块内后置外键（目标表定义于本文件靠后，故以 ALTER TABLE 声明；见 §0.4 级联约定）
-- -----------------------------------------------------------------------------
ALTER TABLE t_trade_order ADD CONSTRAINT fk_trade_order_strategy
    FOREIGN KEY (strategy_id) REFERENCES t_option_strategy (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE t_trade_order ADD CONSTRAINT fk_trade_order_reason
    FOREIGN KEY (trade_reason_id) REFERENCES t_trade_reason (id)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

-- =============================================================================
--  本脚本明细自检
--    表数：9 = DATA_MODEL.md §2.6 TRADE（TB-TRADE-1~9）
--    子账户贯穿（§4.6.3）：① NOT NULL = 8；② 可空 = 0；③ 无该列 = 1（t_replay_log，例外 E3）
--    逐列核对：字段/类型（含精度）/非空 与 §2.6 各表字段表逐行一致（含 §0.3 通用审计字段）
--    唯一键（幂等）：uk_trade_order_client_ref / uk_trade_order_order_id / uk_order_report_idem /
--        uk_trade_fill_id（幂等键，验收 2）/ uk_trade_reason_order / uk_position_key /
--        uk_pre_trade_reject_idem / uk_command_flow_idem / uk_option_strategy_no / uk_replay_log_seq
--    具名业务不变量 CHECK：ck_trade_reason_related_or_missing / ck_position_qty_consistency
--    验收 2 落点：t_trade_fill.trade_id UNIQUE（uk_trade_fill_id）；
--        t_pre_trade_reject 含 limit_layer（ck_reject_limit_layer）与敞口快照列
--        （exposure_snapshot_id / exposure_snapshot_time）
--    验收 4 落点：成交（uk_trade_fill_id）/ 委托（uk_order_report_idem）/ 流水（uk_command_flow_idem）
--        含事件幂等键所需唯一约束
--    未落 DB 的规则（服务层/内存权威，§4.4）：确认人 = 提交人、撤单原因必填、理由关联对象必选、
--        可用持仓 ≤ 持仓量之业务校验、三层限额判定 —— 不得用触发器实现。
-- =============================================================================
