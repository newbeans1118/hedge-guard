-- =============================================================================
--  HedgeGuard 建表脚本 —— 模块 RISK（`T-061`：`TB-RISK-1~11`，共 11 张）
--  制品编号：docs/DEPLOY/schema/m3_risk.sql
--  依据：docs/DATA_MODEL.md v1.3.2（md5 99b647ec3e7a29b95ee86b04a58c747c）§2.8 模块 RISK
--        §0.2 字段类型 / §0.3 通用审计字段 / §0.4 外键与级联 / §0.5 DM8 建表模板 / §4.3 约束目录
--  目标库：达梦 DM8；执行者：DBA（应用账号仅 SELECT/INSERT/UPDATE，NFR-SEC-8）
--
--  ⚠️ 增量交付声明（诚实标注，勿误读为"RISK 模块已完整交付"）
--     本文件当前**仅含 1 张表**：`TB-RISK-2 t_param_dispatch`。
--     原因：`#java2` 的 `T-011`（参数下发编排）验收 2 直接依赖 `ParamDispatchDao`，
--           编排者（2026-09-20）将 `T-061` 的**首个增量**收敛为 `t_param_dispatch` 以解阻该任务。
--     **待续 10 张**（`T-061` 剩余范围，同一文件续写）：
--       `TB-RISK-11 t_annual_scale`／`TB-RISK-1 t_risk_threshold`／`TB-RISK-3 t_mirror_watermark`
--       `TB-RISK-4 t_risk_alert`／`TB-RISK-5 t_risk_disposal`／`TB-RISK-6 t_abnormal_order_stat`
--       `TB-RISK-7 t_risk_metric_snapshot`／`TB-RISK-8 t_post_risk_report`／`TB-RISK-9 t_risk_violation`
--       `TB-RISK-10 t_limit_usage_stat`
--     ⚠️ 其中 `TB-RISK-11 t_annual_scale` / `TB-RISK-1 t_risk_threshold` 含**作用域哨兵值具名 CHECK**
--       （`ck_annual_scale_scope_sentinel` / `ck_risk_threshold_scope_sentinel`，`§4.3` 双向联动型），
--       续写时必须**逐字**采用 `§4.3` 的权威 DDL（三处必须一致）。
--     本文件**未**在 DM8 实机执行（环境无实例，见 `T-004`/`T-061` 工单处理记录）。
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

-- =============================================================================
--  本增量自检
--    表数：1（TB-RISK-2）／ 业务列 13 + 审计列 6 = 19 列
--    唯一约束 0（文档索引清单中该表无 唯一 项）／ 普通索引 3（逐条对应文档）／ 外键 1（→ t_sub_account）
--    具名 CHECK 3（类型 / 状态 / 分批成对）+ 文档 §4.3 未点名为该表规定业务不变量 CHECK
--    依赖：t_sub_account 须已建（见 m1_config_auth.sql）
-- =============================================================================
