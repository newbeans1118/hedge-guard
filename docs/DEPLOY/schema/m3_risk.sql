-- =============================================================================
--  HedgeGuard 建表脚本 —— 模块 RISK（TB-RISK-1~11，共 11 张；本文件交付全部 11 张）
--  制品编号：docs/DEPLOY/schema/m3_risk.sql
--  依据：docs/DATA_MODEL.md v1.3.5（md5 d17bce0c37e513a0a236304b9cb3f25d）§2.8 模块 RISK
--        §0.2 字段类型 / §0.3 通用审计字段 / §0.4 外键与级联 / §0.5 DM8 建表模板 / §4.3 约束目录
--        历史依据（保留历史注，只加不改）：本文件首个增量（TB-RISK-2 t_param_dispatch）起草于
--        DATA_MODEL v1.3.2（md5 99b647ec3e7a29b95ee86b04a58c747c）；余 10 表由 T-094 依现行 v1.3.5 续写。
--  目标库：达梦 DM8；执行者：DBA（应用账号仅 SELECT/INSERT/UPDATE，NFR-SEC-8）
--
--  交付范围（终态，非增量）：
--    - TB-RISK-2 t_param_dispatch：T-061 首个增量（本文件原有内容）。
--    - 其余 10 张（TB-RISK-11/1/3/4/5/6/7/8/9/10）：T-094 续写；编号以 DATA_MODEL §2.8 实际编号为准。
--      实际编号映射（订正旧头注释错位）：7=t_post_risk_report / 8=t_risk_violation /
--      9=t_limit_usage_stat / 10=t_risk_metric_snapshot。
--    - 其中 TB-RISK-7/8/9 的 entity + Dm*Dao 已由 T-180 交付（CONTRACTS §2.2.1 登记）；其建表 DDL 由本单收口，
--      列级须与 T-180 内联夹具 java/src/test/java/com/hedgeguard/dao/testsupport/M3RiskReportDdl.java 逐列一致。
--      （te-verify-t180 已对该夹具对 §2.8 逐列核验。）
--
--  ⚠️ 作用域哨兵值具名 CHECK（§4.3 双向联动型）——三处必须逐字一致：本文件头 / 表定义 / DATA_MODEL §4.3
--     （唯一权威 DDL 为 §4.3；下列即其逐字转录）
--       ALTER TABLE t_annual_scale ADD CONSTRAINT ck_annual_scale_scope_sentinel CHECK (
--            (scope_type = 'TOTAL_ACCOUNT' AND sub_account_id = 0)
--         OR (scope_type = 'SUB_ACCOUNT'   AND sub_account_id > 0)
--       );
--       ALTER TABLE t_risk_threshold ADD CONSTRAINT ck_risk_threshold_scope_sentinel CHECK (
--            (scope_type = 'GLOBAL'      AND sub_account_id = 0)
--         OR (scope_type = 'SUB_ACCOUNT' AND sub_account_id > 0)
--       );
--     表定义内联同一条件（见 t_annual_scale / t_risk_threshold 的 CONSTRAINT 行）。
--
--  建表顺序说明：t_risk_disposal 置于 t_risk_alert 之前（后者含 → t_risk_disposal(id) 外键，先建父表）。
--  本文件未在 DM8 实机执行（环境无实例，见 T-004/T-052）。
--
--  脚本纪律：无存储过程 / 触发器 / 自定义函数（PROJECT.md §6）；无 DML；DM 特性带 `-- DM-SPECIFIC:` 注释。
-- =============================================================================

-- ===== TB-RISK-2 t_param_dispatch 参数下发记录 =====
-- 用途：Java → C++ 的控制指令下发与回执（FR-RISK-9 / FR-RISK-10 / FR-CONFIG-6 / R-CONFIG-3）。
-- 契约：**无回执或失败时该变更不得视为已生效** —— dispatch_status ∈ {FAIL, UNCONFIRMED} 的行
--       不得被任何服务层逻辑当作"已生效"读取。
-- 可变表（回执到达时更新 dispatch_status / ack_*），故含完整 6 项通用审计字段（§0.3）。
CREATE TABLE t_param_dispatch (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id              BIGINT       IDENTITY(1,1) NOT NULL,
    param_version   BIGINT                     NOT NULL,
    dispatch_type   VARCHAR(32)                NOT NULL,
    sub_account_id  INT                        NULL,
    batch_index     INT                        NULL,
    batch_total     INT                        NULL,
    payload_digest  VARCHAR(128)               NOT NULL,
    record_count    INT          DEFAULT 0     NOT NULL,
    dispatch_status VARCHAR(16)                NOT NULL,
    ack_time        TIMESTAMP                  NULL,
    ack_detail      VARCHAR(1000)              NULL,
    retry_count     INT          DEFAULT 0     NOT NULL,
    operator_id     BIGINT                     NOT NULL,
    dispatch_time   TIMESTAMP                  NOT NULL,
    created_by      BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by      BIGINT                     NULL,
    updated_time    TIMESTAMP                  NULL,
    row_version     INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_param_dispatch PRIMARY KEY (id),
    CONSTRAINT ck_param_dispatch_type CHECK (dispatch_type IN (
        'LOAD_RISK_PARAMS','UPDATE_WHITELIST','UPDATE_QUOTA','UPDATE_POSITION_LIMIT',
        'UPDATE_RISK_THRESHOLD','UPDATE_PERMISSION','UPDATE_EXPOSURE','SET_TRADING_SUSPEND')),
    CONSTRAINT ck_param_dispatch_status CHECK (dispatch_status IN ('PENDING','SUCCESS','FAIL','UNCONFIRMED')),
    CONSTRAINT ck_param_dispatch_batch CHECK (
        (batch_index IS NULL AND batch_total IS NULL) OR (batch_index >= 1 AND batch_total >= 1)),
    CONSTRAINT fk_param_dispatch_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
);
COMMENT ON TABLE  t_param_dispatch                 IS '参数下发记录（Java→C++ 控制指令与回执；无回执不得视为已生效，R-CONFIG-3）';
COMMENT ON COLUMN t_param_dispatch.param_version   IS '单调递增的参数版本（R-CONFIG-3 顺序保证）';
COMMENT ON COLUMN t_param_dispatch.dispatch_type   IS 'LOAD_RISK_PARAMS/UPDATE_WHITELIST/UPDATE_QUOTA/UPDATE_POSITION_LIMIT/UPDATE_RISK_THRESHOLD/UPDATE_PERMISSION/UPDATE_EXPOSURE/SET_TRADING_SUSPEND';
COMMENT ON COLUMN t_param_dispatch.sub_account_id  IS '涉及子账户（子账户级变更时非空；账户级为 NULL）';
COMMENT ON COLUMN t_param_dispatch.batch_index     IS '分批下标（全量预加载分批；未分批为 NULL）';
COMMENT ON COLUMN t_param_dispatch.batch_total     IS '分批总数（全量预加载分批；未分批为 NULL）';
COMMENT ON COLUMN t_param_dispatch.payload_digest  IS '下发内容摘要（SHA-256）：两侧摘要一致 ⇒ 内存状态与库状态一致（替代人工翻日志）';
COMMENT ON COLUMN t_param_dispatch.record_count    IS '本次下发的记录条数（加载条数可校验，FR-RISK-9 验收 3）';
COMMENT ON COLUMN t_param_dispatch.dispatch_status IS 'PENDING / SUCCESS / FAIL / UNCONFIRMED（超时）';
COMMENT ON COLUMN t_param_dispatch.ack_time        IS '回执时间（未回执为 NULL）';
COMMENT ON COLUMN t_param_dispatch.ack_detail      IS '回执详情（或 0x0403 等组件错误码）';
COMMENT ON COLUMN t_param_dispatch.retry_count     IS '重试次数';
COMMENT ON COLUMN t_param_dispatch.operator_id     IS '操作人';
COMMENT ON COLUMN t_param_dispatch.dispatch_time   IS '下发时间';
CREATE INDEX idx_param_dispatch_version   ON t_param_dispatch (param_version, dispatch_type);
CREATE INDEX idx_param_dispatch_status    ON t_param_dispatch (dispatch_status, dispatch_time);
CREATE INDEX idx_param_dispatch_sub_time  ON t_param_dispatch (sub_account_id, dispatch_time);

-- ===== TB-RISK-11 t_annual_scale 年度规模基数（品种 × 年度）=====
-- 用途：规模红线的分子与分母（Q-1：基数 = 年度现货敞口累计量，实物量口径，不用营业收入）。
-- 混合表：SUB_ACCOUNT 行为 A 类（sub_account_id > 0），TOTAL_ACCOUNT 行为 B 类（哨兵 0）。
-- 备注：分母缺失时规模红线无法判定，服务层须明确报错并阻断（不得默认 0）。
CREATE TABLE t_annual_scale (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                         BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id             INT           DEFAULT 0     NOT NULL,
    scope_type                 VARCHAR(16)                 NOT NULL,
    product_id                 BIGINT                      NOT NULL,
    scale_year                 INT                         NOT NULL,
    expected_spot_business_qty DECIMAL(20,4)               NOT NULL,
    accumulated_hedge_qty      DECIMAL(20,4) DEFAULT 0     NOT NULL,
    category                   VARCHAR(16)                 NOT NULL,
    scale_ratio_limit          DECIMAL(9,6)                NOT NULL,
    usage_ratio                DECIMAL(9,6)                NULL,
    dispatch_status            VARCHAR(16)   DEFAULT 'PENDING' NOT NULL,
    dispatch_version           BIGINT                      NULL,
    created_by                 BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                     NULL,
    updated_time               TIMESTAMP                  NULL,
    row_version                INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_annual_scale PRIMARY KEY (id),
    -- 作用域哨兵值具名 CHECK（§4.3 双向联动型；逐字采用权威 DDL）
    CONSTRAINT ck_annual_scale_scope_sentinel CHECK (
         (scope_type = 'TOTAL_ACCOUNT' AND sub_account_id = 0)
      OR (scope_type = 'SUB_ACCOUNT'   AND sub_account_id > 0)),
    CONSTRAINT ck_annual_scale_scope_type CHECK (scope_type IN ('SUB_ACCOUNT','TOTAL_ACCOUNT')),
    CONSTRAINT ck_annual_scale_category CHECK (category IN ('COMMODITY','TRADE')),
    CONSTRAINT ck_annual_scale_dispatch_status CHECK (dispatch_status IN (
        'PENDING','SUCCESS','FAIL','UNCONFIRMED')),
    CONSTRAINT uk_annual_scale UNIQUE (scope_type, sub_account_id, product_id, scale_year),
    CONSTRAINT fk_annual_scale_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id),
    CONSTRAINT fk_annual_scale_product FOREIGN KEY (product_id) REFERENCES t_product (id)
);
COMMENT ON TABLE  t_annual_scale IS '年度规模基数（品种 × 年度；规模红线分子/分母，Q-1；混合表 A/B 行由哨兵 CHECK 分派）';
COMMENT ON COLUMN t_annual_scale.expected_spot_business_qty IS '分母：该品种年度预计实物经营量（缺失须阻断，不得默认 0）';
COMMENT ON COLUMN t_annual_scale.accumulated_hedge_qty      IS '分子：年度累计新开仓保值数量（权威源为 C++ 内存，本列为投影）';
COMMENT ON COLUMN t_annual_scale.category                   IS 'COMMODITY(90%)/TRADE(80%)，继承子账户分类或品种级覆盖（Q-2）';
COMMENT ON COLUMN t_annual_scale.scale_ratio_limit          IS '适用红线比例（0.900000 / 0.800000，比例本身可配置）';
CREATE INDEX idx_annual_scale_key      ON t_annual_scale (sub_account_id, product_id, scale_year);
CREATE INDEX idx_annual_scale_total    ON t_annual_scale (scope_type, product_id, scale_year);
CREATE INDEX idx_annual_scale_dispatch ON t_annual_scale (dispatch_status);

-- ===== TB-RISK-1 t_risk_threshold 风控阈值 =====
-- 用途：集中配置的风控参数（FR-CONFIG-5、R-RISK-14）。不得硬编码。
-- 合规下限存储表达（验收 4）：default_value NOT NULL + is_compliance_redline —— 「默认值不得低于
--   INDEX.md §9.1 口径」（Q-5 频繁开平仓 6/10/5/3；Q-15 价格偏离 2%/5%）由服务层据此列校验并留痕；
--   min_value/max_value 表达输入越界拒绝（FR-CONFIG-5 验收 3）。
-- 本表不属五类业务数据（非五类显式携带）；GLOBAL 行为 B 类（哨兵 0），由哨兵 CHECK 在 DB 级确定。
CREATE TABLE t_risk_threshold (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                    BIGINT        IDENTITY(1,1) NOT NULL,
    threshold_key         VARCHAR(128)                NOT NULL,
    threshold_value       VARCHAR(128)                NOT NULL,
    value_type            VARCHAR(16)                 NOT NULL,
    min_value             DECIMAL(20,6)               NULL,
    max_value             DECIMAL(20,6)               NULL,
    default_value         VARCHAR(128)                NOT NULL,
    is_compliance_redline CHAR(1)       DEFAULT 'N'   NOT NULL,
    scope_type            VARCHAR(16)                 NOT NULL,
    sub_account_id        INT           DEFAULT 0     NOT NULL,
    related_question_no   VARCHAR(16)                 NULL,
    dispatch_status       VARCHAR(16)   DEFAULT 'PENDING' NOT NULL,
    dispatch_version      BIGINT                      NULL,
    description           VARCHAR(500)                NULL,
    created_by                 BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                     NULL,
    updated_time               TIMESTAMP                  NULL,
    row_version                INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_risk_threshold PRIMARY KEY (id),
    -- 作用域哨兵值具名 CHECK（§4.3 双向联动型；逐字采用权威 DDL）
    CONSTRAINT ck_risk_threshold_scope_sentinel CHECK (
         (scope_type = 'GLOBAL'      AND sub_account_id = 0)
      OR (scope_type = 'SUB_ACCOUNT' AND sub_account_id > 0)),
    CONSTRAINT ck_risk_threshold_value_type CHECK (value_type IN (
        'DECIMAL','INT','BOOL','LIST')),
    CONSTRAINT ck_risk_threshold_redline CHECK (is_compliance_redline IN ('Y','N')),
    CONSTRAINT ck_risk_threshold_scope_type CHECK (scope_type IN ('GLOBAL','SUB_ACCOUNT')),
    CONSTRAINT ck_risk_threshold_dispatch_status CHECK (dispatch_status IN (
        'PENDING','SUCCESS','FAIL','UNCONFIRMED')),
    CONSTRAINT uk_risk_threshold_key_scope UNIQUE (threshold_key, scope_type, sub_account_id),
    CONSTRAINT fk_risk_threshold_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
);
COMMENT ON TABLE  t_risk_threshold IS '风控阈值（集中配置、不得硬编码；FR-CONFIG-5/R-RISK-14；GLOBAL 行为哨兵 0）';
COMMENT ON COLUMN t_risk_threshold.threshold_key          IS '阈值 Key（对应 ARCHITECTURE.md §11 的 HG.RISK.*）';
COMMENT ON COLUMN t_risk_threshold.default_value          IS '默认值：须与 PROJECT.md 合规红线一致，不得更宽松（R-CONFIG-8；不低于 INDEX.md §9.1 口径）';
COMMENT ON COLUMN t_risk_threshold.is_compliance_redline  IS '是否合规红线（Y 时放宽须双人复核并留痕）';
COMMENT ON COLUMN t_risk_threshold.scope_type             IS 'GLOBAL（全局）/ SUB_ACCOUNT（子账户级，优先于全局，FR-CONFIG-8）';
CREATE INDEX idx_risk_threshold_scope    ON t_risk_threshold (scope_type, sub_account_id);
CREATE INDEX idx_risk_threshold_dispatch ON t_risk_threshold (dispatch_status);

-- ===== TB-RISK-3 t_mirror_watermark 镜像状态水位 =====
-- 用途：Java 侧镜像状态的已消费事件序号（FR-RISK-12）；滞后超阈值须告警（R-RISK-10）。
CREATE TABLE t_mirror_watermark (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                BIGINT        IDENTITY(1,1) NOT NULL,
    component_id      VARCHAR(64)                 NOT NULL,
    stream_type       VARCHAR(24)                 NOT NULL,
    last_consumed_seq BIGINT        DEFAULT 0     NOT NULL,
    last_produced_seq BIGINT        DEFAULT 0     NOT NULL,
    lag               BIGINT        DEFAULT 0     NOT NULL,
    lag_threshold     BIGINT        DEFAULT 100   NOT NULL,
    lag_alarm         CHAR(1)       DEFAULT 'N'   NOT NULL,
    last_update_time  TIMESTAMP                   NOT NULL,
    connection_state  VARCHAR(24)                 NOT NULL,
    created_by                 BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                     NULL,
    updated_time               TIMESTAMP                  NULL,
    row_version                INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_mirror_watermark PRIMARY KEY (id),
    CONSTRAINT ck_mirror_watermark_stream_type CHECK (stream_type IN ('EVENT','QUOTE')),
    CONSTRAINT ck_mirror_watermark_lag_alarm CHECK (lag_alarm IN ('Y','N')),
    CONSTRAINT ck_mirror_watermark_conn_state CHECK (connection_state IN (
        'CONNECTED','DISCONNECTED','RESUMING')),
    CONSTRAINT uk_mirror_watermark UNIQUE (component_id, stream_type)
);
COMMENT ON TABLE  t_mirror_watermark IS '镜像状态水位（Java 侧已消费事件序号；滞后超阈值告警，FR-RISK-12/R-RISK-10）';
COMMENT ON COLUMN t_mirror_watermark.lag            IS '滞后量 = last_produced_seq − last_consumed_seq';
COMMENT ON COLUMN t_mirror_watermark.lag_threshold  IS '滞后告警阈值（配置项）';
COMMENT ON COLUMN t_mirror_watermark.connection_state IS 'CONNECTED / DISCONNECTED / RESUMING';
CREATE INDEX idx_mirror_watermark_alarm ON t_mirror_watermark (lag_alarm, last_update_time);

-- ===== TB-RISK-5 t_risk_disposal 风险处置记录 =====
-- 用途：告警的处置动作（告警 / 限制新开仓 / 强制平仓提示 / 解除）（FR-RISK-15、FR-RISK-16）。
-- 置于 t_risk_alert 之前：后者含 → t_risk_disposal(id) 外键，须先建本表。
-- scan_scope 恒为 'OPEN_ONLY' 的具名 CHECK 落地 R-RISK-5「只限开仓、不限平仓」。
CREATE TABLE t_risk_disposal (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                 BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id     INT                         NOT NULL,
    is_global          CHAR(1)       DEFAULT 'N'   NOT NULL,
    disposal_type      VARCHAR(32)                 NOT NULL,
    scan_scope         VARCHAR(16)   DEFAULT 'OPEN_ONLY' NOT NULL,
    reason             VARCHAR(500)                NOT NULL,
    hint_instrument_id VARCHAR(32)                 NULL,
    hint_direction     VARCHAR(16)                 NULL,
    hint_volume        INT                         NULL,
    hint_accepted      CHAR(1)                     NULL,
    reject_reason      VARCHAR(500)                NULL,
    operator_id        BIGINT                      NOT NULL,
    disposal_time      TIMESTAMP                   NOT NULL,
    dispatch_status    VARCHAR(16)                 NULL,
    dispatch_version   BIGINT                      NULL,
    released_time      TIMESTAMP                   NULL,
    created_by                 BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                     NULL,
    updated_time               TIMESTAMP                  NULL,
    row_version                INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_risk_disposal PRIMARY KEY (id),
    CONSTRAINT ck_risk_disposal_is_global CHECK (is_global IN ('Y','N')),
    CONSTRAINT ck_risk_disposal_type CHECK (disposal_type IN (
        'ALERT_ONLY','SUSPEND_OPEN','FORCE_CLOSE_HINT','RELEASE_SUSPEND')),
    CONSTRAINT ck_risk_disposal_scan_scope CHECK (scan_scope = 'OPEN_ONLY'),
    CONSTRAINT ck_risk_disposal_hint_accepted CHECK (hint_accepted IN ('Y','N')),
    CONSTRAINT ck_risk_disposal_dispatch_status CHECK (dispatch_status IN (
        'PENDING','SUCCESS','FAIL','UNCONFIRMED')),
    CONSTRAINT fk_risk_disposal_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
);
COMMENT ON TABLE  t_risk_disposal IS '风险处置记录（告警/限制新开仓/强制平仓提示/解除；FR-RISK-15/16）';
COMMENT ON COLUMN t_risk_disposal.scan_scope        IS '限制范围恒为 OPEN_ONLY（不阻断平仓，R-RISK-5）';
COMMENT ON COLUMN t_risk_disposal.hint_instrument_id IS '强制平仓提示的建议合约（FR-RISK-16 验收 2）';
COMMENT ON COLUMN t_risk_disposal.reject_reason     IS '不接受提示的理由（hint_accepted=N 时必填，FR-RISK-16 验收 4；服务层强制）';
CREATE INDEX idx_risk_disposal_sub_type ON t_risk_disposal (sub_account_id, disposal_type);
CREATE INDEX idx_risk_disposal_active   ON t_risk_disposal (disposal_type, released_time);
CREATE INDEX idx_risk_disposal_time     ON t_risk_disposal (disposal_time);
CREATE INDEX idx_risk_disposal_dispatch ON t_risk_disposal (dispatch_status);

-- ===== TB-RISK-4 t_risk_alert 风险告警 =====
-- 用途：事中指标超阈值生成的告警（FR-RISK-12~FR-RISK-14）；须留痕、不得静默丢弃（R-RISK-11）。
CREATE TABLE t_risk_alert (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id              BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id  INT                         NOT NULL,
    alert_type      VARCHAR(32)                 NOT NULL,
    alert_level     VARCHAR(16)                 NOT NULL,
    metric_name     VARCHAR(64)                 NOT NULL,
    metric_value    VARCHAR(64)                 NOT NULL,
    threshold_value VARCHAR(64)                 NOT NULL,
    instrument_id   VARCHAR(32)                 NULL,
    alert_detail    VARCHAR(1000)               NULL,
    alert_ts_ms     BIGINT                      NOT NULL,
    alert_time      TIMESTAMP                   NOT NULL,
    disposed        CHAR(1)       DEFAULT 'N'   NOT NULL,
    disposal_id     BIGINT                      NULL,
    created_by                 BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                     NULL,
    updated_time               TIMESTAMP                  NULL,
    row_version                INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_risk_alert PRIMARY KEY (id),
    CONSTRAINT ck_risk_alert_type CHECK (alert_type IN (
        'POSITION','FLOAT_PNL','MARGIN_RATIO','RISK_RATIO','AVAILABLE_FUND','ABNORMAL_ORDER','MIRROR_LAG','FUND_QUOTA')),
    CONSTRAINT ck_risk_alert_level CHECK (alert_level IN ('INFO','WARNING','CRITICAL')),
    CONSTRAINT ck_risk_alert_disposed CHECK (disposed IN ('Y','N')),
    CONSTRAINT fk_risk_alert_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id),
    CONSTRAINT fk_risk_alert_disposal FOREIGN KEY (disposal_id) REFERENCES t_risk_disposal (id)
);
COMMENT ON TABLE  t_risk_alert IS '风险告警（事中指标超阈值；须留痕不得静默丢弃，FR-RISK-12~14/R-RISK-11）';
COMMENT ON COLUMN t_risk_alert.disposed    IS '是否已处置（未处置须显式标注，FR-RISK-19 验收 2）';
COMMENT ON COLUMN t_risk_alert.disposal_id IS '关联的处置动作（→ t_risk_disposal）';
CREATE INDEX idx_risk_alert_sub_time     ON t_risk_alert (sub_account_id, alert_time);
CREATE INDEX idx_risk_alert_type_level   ON t_risk_alert (alert_type, alert_level, alert_time);
CREATE INDEX idx_risk_alert_disposed     ON t_risk_alert (disposed, alert_time);
CREATE INDEX idx_risk_alert_date         ON t_risk_alert (alert_time);

-- ===== TB-RISK-6 t_abnormal_order_stat 异常下单行为统计 =====
-- 用途：按时间窗统计开平仓频次与最短/平均持仓周期（FR-RISK-14），识别频繁开平仓（交易所红线）。
CREATE TABLE t_abnormal_order_stat (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id    INT                         NOT NULL,
    product_id        BIGINT                      NOT NULL,
    instrument_id     VARCHAR(32)                 NULL,
    stat_date         DATE                        NOT NULL,
    window_minutes    INT                         NOT NULL,
    open_close_count  INT           DEFAULT 0     NOT NULL,
    min_hold_minutes  INT                         NULL,
    avg_hold_minutes  DECIMAL(20,4)               NULL,
    round_trip_count  INT           DEFAULT 0     NOT NULL,
    last_order_time   TIMESTAMP                   NULL,
    is_abnormal       CHAR(1)       DEFAULT 'N'   NOT NULL,
    action_taken      VARCHAR(32)                 NULL,
    order_ids         VARCHAR(1000)               NULL,
    created_by                 BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                     NULL,
    updated_time               TIMESTAMP                  NULL,
    row_version                INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_abnormal_order_stat PRIMARY KEY (id),
    CONSTRAINT ck_abnormal_stat_is_abnormal CHECK (is_abnormal IN ('Y','N')),
    CONSTRAINT ck_abnormal_stat_action CHECK (action_taken IN (
        'ALERT_ONLY','ALERT_AND_SUSPEND_OPEN')),
    CONSTRAINT uk_abnormal_order_stat UNIQUE (
        stat_date, sub_account_id, product_id, instrument_id, window_minutes),
    CONSTRAINT fk_abnormal_stat_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id),
    CONSTRAINT fk_abnormal_stat_product FOREIGN KEY (product_id) REFERENCES t_product (id)
);
COMMENT ON TABLE  t_abnormal_order_stat IS '异常下单行为统计（按时间窗开平仓频次与持仓周期；FR-RISK-14）';
COMMENT ON COLUMN t_abnormal_order_stat.window_minutes   IS '时间窗（CFG-05，默认 1440=1 日；7 日滚动=10080）';
COMMENT ON COLUMN t_abnormal_order_stat.round_trip_count IS '回合数（一次开+平记 1；计数类无未知语义，无回合即为 0）';
COMMENT ON COLUMN t_abnormal_order_stat.avg_hold_minutes IS '平均持仓周期（分钟）；Q-5/FR-RISK-14 验收 2「平均持仓<3 交易日」的可判数据';
CREATE INDEX idx_abnormal_stat_abnormal ON t_abnormal_order_stat (is_abnormal, stat_date);
CREATE INDEX idx_abnormal_stat_sub_date ON t_abnormal_order_stat (sub_account_id, stat_date);

-- ===== TB-RISK-7 t_post_risk_report 事后风险报表 =====
-- 用途：交易日结束后生成并落库的风险报表（FR-RISK-17），含风险指标统计三部分。
-- ⚠️ 列级须与 T-180 内联夹具 M3RiskReportDdl.java 逐列一致（本表 DDL 由 T-094 收口）。
CREATE TABLE t_post_risk_report (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                   BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id       INT                         NULL,
    report_date          DATE                        NOT NULL,
    report_type          VARCHAR(32)                 NOT NULL,
    content_json         CLOB                        NOT NULL,
    settle_reconcile_key VARCHAR(64)                 NULL,
    generated_by         BIGINT                      NOT NULL,
    generated_time       TIMESTAMP                   NOT NULL,
    settle_cycle_id      BIGINT                      NULL,
    created_by                 BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                     NULL,
    updated_time               TIMESTAMP                  NULL,
    row_version                INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_post_risk_report PRIMARY KEY (id),
    CONSTRAINT ck_post_risk_report_type CHECK (report_type IN (
        'RISK_METRIC','VIOLATION_SUMMARY','LIMIT_USAGE')),
    CONSTRAINT uk_post_risk_report UNIQUE (report_date, report_type, sub_account_id),
    CONSTRAINT fk_post_risk_report_sub FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id),
    CONSTRAINT fk_post_risk_report_settle FOREIGN KEY (settle_cycle_id) REFERENCES t_settle_cycle (id)
);
COMMENT ON TABLE  t_post_risk_report IS '事后风险报表（日终生成并落库；FR-RISK-17）';
COMMENT ON COLUMN t_post_risk_report.sub_account_id       IS '子账户（全账户汇总为 NULL）';
COMMENT ON COLUMN t_post_risk_report.settle_reconcile_key IS '与结算数据勾稽的键（trade_date+结算单号；FR-RISK-17 验收 3）';
CREATE INDEX idx_post_risk_report_date   ON t_post_risk_report (report_date, report_type);
CREATE INDEX idx_post_risk_report_settle ON t_post_risk_report (settle_cycle_id);

-- ===== TB-RISK-8 t_risk_violation 违规事项 =====
-- 用途：六类违规事项的汇总（FR-RISK-18、FR-AUDIT-4）；筛查口径复用 RISK 规则编号（R-AUDIT-7）。
-- ⚠️ 列级须与 T-180 内联夹具 M3RiskReportDdl.java 逐列一致；内联夹具裁剪了三条可空外键，本表补齐。
CREATE TABLE t_risk_violation (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id    INT                         NOT NULL,
    violation_date    DATE                        NOT NULL,
    violation_type    VARCHAR(32)                 NOT NULL,
    severity          VARCHAR(16)                 NOT NULL,
    rule_id           VARCHAR(32)                 NULL,
    trade_order_id    BIGINT                      NULL,
    trade_fill_id     BIGINT                      NULL,
    hedge_plan_id     BIGINT                      NULL,
    violation_desc    VARCHAR(1000)               NOT NULL,
    trade_reason_text VARCHAR(1000)               NULL,
    source            VARCHAR(24)                 NOT NULL,
    occur_time        TIMESTAMP                   NOT NULL,
    created_by                 BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                     NULL,
    updated_time               TIMESTAMP                  NULL,
    row_version                INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_risk_violation PRIMARY KEY (id),
    CONSTRAINT ck_risk_violation_type CHECK (violation_type IN (
        'OVER_PRODUCT','OVER_SCALE','OVER_TERM','OVER_AUTH','FREQUENT_TRADE','DOC_MISSING','REASON_MISSING','RELATED_MISSING')),
    CONSTRAINT ck_risk_violation_severity CHECK (severity IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT ck_risk_violation_source CHECK (source IN (
        'PRE_TRADE_REJECT','INTRADAY_ALERT','POST_AUDIT_SCREEN','MANUAL')),
    CONSTRAINT fk_risk_violation_sub FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id),
    CONSTRAINT fk_risk_violation_order FOREIGN KEY (trade_order_id) REFERENCES t_trade_order (id),
    CONSTRAINT fk_risk_violation_fill FOREIGN KEY (trade_fill_id) REFERENCES t_trade_fill (id),
    CONSTRAINT fk_risk_violation_plan FOREIGN KEY (hedge_plan_id) REFERENCES t_hedge_plan (id)
);
COMMENT ON TABLE  t_risk_violation IS '违规事项（六类汇总；FR-RISK-18/FR-AUDIT-4；rule_id 复用 RISK 规则编号）';
COMMENT ON COLUMN t_risk_violation.trade_reason_text IS '该笔交易的交易理由（供自证或整改，FR-AUDIT-4）';
CREATE INDEX idx_risk_violation_sub_date  ON t_risk_violation (sub_account_id, violation_date);
CREATE INDEX idx_risk_violation_type_date ON t_risk_violation (violation_type, violation_date);
CREATE INDEX idx_risk_violation_severity  ON t_risk_violation (severity, violation_date);
CREATE INDEX idx_risk_violation_order     ON t_risk_violation (trade_order_id);

-- ===== TB-RISK-9 t_limit_usage_stat 限额使用统计 =====
-- 用途：按子账户 × 品种展示限额使用率并标注接近红线者（FR-RISK-18）；>=80% 提示、>=95% 警告。
-- ⚠️ 列级须与 T-180 内联夹具 M3RiskReportDdl.java 逐列一致。
CREATE TABLE t_limit_usage_stat (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id               BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id   INT                         NOT NULL,
    product_id       BIGINT                      NOT NULL,
    stat_date        DATE                        NOT NULL,
    limit_type       VARCHAR(24)                 NOT NULL,
    limit_value      DECIMAL(20,4)               NOT NULL,
    used_value       DECIMAL(20,4)               NOT NULL,
    usage_ratio      DECIMAL(9,6)                NOT NULL,
    hint_level       VARCHAR(16)                 NOT NULL,
    hint_threshold_1 DECIMAL(9,6)                NULL,
    hint_threshold_2 DECIMAL(9,6)                NULL,
    created_by                 BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                     NULL,
    updated_time               TIMESTAMP                  NULL,
    row_version                INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_limit_usage_stat PRIMARY KEY (id),
    CONSTRAINT ck_limit_usage_limit_type CHECK (limit_type IN (
        'PRODUCT_QUOTA','POSITION_LIMIT','SCALE_REDLINE','FUND_QUOTA','AUTH_QUOTA')),
    CONSTRAINT ck_limit_usage_hint_level CHECK (hint_level IN (
        'NORMAL','HINT','WARNING','EXCEEDED')),
    CONSTRAINT ck_limit_usage_ratio_nonneg CHECK (usage_ratio >= 0),
    CONSTRAINT uk_limit_usage_stat UNIQUE (stat_date, sub_account_id, product_id, limit_type),
    CONSTRAINT fk_limit_usage_sub FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id),
    CONSTRAINT fk_limit_usage_product FOREIGN KEY (product_id) REFERENCES t_product (id)
);
COMMENT ON TABLE  t_limit_usage_stat IS '限额使用统计（子账户 × 品种 使用率与接近红线标注；FR-RISK-18）';
COMMENT ON COLUMN t_limit_usage_stat.hint_level       IS 'NORMAL / HINT(>=80%) / WARNING(>=95%) / EXCEEDED(>=100%)';
COMMENT ON COLUMN t_limit_usage_stat.hint_threshold_1 IS '提示线快照（便于审计复现）';
COMMENT ON COLUMN t_limit_usage_stat.hint_threshold_2 IS '警告线快照（便于审计复现）';
CREATE INDEX idx_limit_usage_sub_date ON t_limit_usage_stat (sub_account_id, stat_date);
CREATE INDEX idx_limit_usage_hint     ON t_limit_usage_stat (hint_level, stat_date);
CREATE INDEX idx_limit_usage_product  ON t_limit_usage_stat (product_id, stat_date);

-- ===== TB-RISK-10 t_risk_metric_snapshot 风险指标快照 =====
-- 用途：事中指标的时点值（FR-RISK-12、FR-RISK-13）；用于趋势分析与审计复现。
CREATE TABLE t_risk_metric_snapshot (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id               BIGINT        IDENTITY(1,1) NOT NULL,
    sub_account_id   INT                         NOT NULL,
    snapshot_time    TIMESTAMP                   NOT NULL,
    snapshot_date    DATE                        NOT NULL,
    position_qty_net DECIMAL(20,4)               NULL,
    float_pnl        DECIMAL(20,4)               NULL,
    margin_occupied  DECIMAL(20,4)               NULL,
    margin_ratio     DECIMAL(9,6)                NULL,
    risk_ratio       DECIMAL(9,6)                NULL,
    available_fund   DECIMAL(20,4)               NULL,
    equity           DECIMAL(20,4)               NULL,
    mirror_seq       BIGINT                      NOT NULL,
    snapshot_source  VARCHAR(16)                 NOT NULL,
    created_by                 BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time               TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                 BIGINT                     NULL,
    updated_time               TIMESTAMP                  NULL,
    row_version                INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_risk_metric_snapshot PRIMARY KEY (id),
    CONSTRAINT ck_risk_metric_source CHECK (snapshot_source IN (
        'PERIODIC','EVENT','EOD')),
    CONSTRAINT fk_risk_metric_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
);
COMMENT ON TABLE  t_risk_metric_snapshot IS '风险指标快照（事中指标时点值；FR-RISK-12/13；日终永久保留，周期 90 天）';
COMMENT ON COLUMN t_risk_metric_snapshot.mirror_seq      IS '该快照对应的镜像事件序号（可复现）';
COMMENT ON COLUMN t_risk_metric_snapshot.snapshot_source IS 'PERIODIC（周期）/ EVENT（事件触发）/ EOD（日终）';
CREATE INDEX idx_risk_metric_sub_time      ON t_risk_metric_snapshot (sub_account_id, snapshot_time);
CREATE INDEX idx_risk_metric_date_source   ON t_risk_metric_snapshot (snapshot_date, snapshot_source);
CREATE INDEX idx_risk_metric_mirror_seq    ON t_risk_metric_snapshot (mirror_seq);

-- =============================================================================
--  全文件自检（终态：11 张）
--    表数：11（TB-RISK-1~11，CREATE TABLE 计数 = 11）
--    T-094 新增 10 张：t_annual_scale / t_risk_threshold / t_mirror_watermark / t_risk_alert /
--      t_risk_disposal / t_abnormal_order_stat / t_post_risk_report / t_risk_violation /
--      t_limit_usage_stat / t_risk_metric_snapshot。
--    作用域哨兵值具名 CHECK：2 条（ck_annual_scale_scope_sentinel / ck_risk_threshold_scope_sentinel），
--      逐字采用 DATA_MODEL §4.3 双向联动型 DDL（文件头 + 表定义 + §4.3 三处一致）。
--    TB-RISK-7/8/9 DDL 与 T-180 内联夹具 M3RiskReportDdl.java 列级一致。
--    依赖（须先建）：t_sub_account / t_product / t_hedge_plan / t_trade_order / t_trade_fill / t_settle_cycle。
--    DM8 实机建表未执行（环境无实例，归 T-052）。
-- =============================================================================
