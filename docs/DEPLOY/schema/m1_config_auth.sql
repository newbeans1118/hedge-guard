-- =============================================================================
--  HedgeGuard 建表脚本 —— 模块 CONFIG（7 张）+ AUTH（13 张），共 20 张
--  制品编号：docs/DEPLOY/schema/m1_config_auth.sql
--  依据：docs/DATA_MODEL.md v1.3.2（md5 99b647ec3e7a29b95ee86b04a58c747c）
--        §0 全局建表约定 / §0.2 字段类型 / §0.3 通用审计字段 / §0.4 外键与级联
--        §0.5 DM8 建表脚本模板 / §2.1 CONFIG / §2.2 AUTH / §3.5 编号生成 / §4.3 约束目录
--  目标库：达梦 DM8（PROJECT.md §5.1）
--  执行者：DBA（应用账号仅 SELECT/INSERT/UPDATE，禁 DDL —— NFR-SEC-8）
--
--  ⚠️ 脚本纪律（DATA_MODEL.md §0.5）
--    ① 不含存储过程 / 触发器 / 自定义函数（PROJECT.md §6 永久禁止）；
--    ② 不含任何 DML —— 初始化数据见同目录 data-init.sql；
--    ③ 每条 DM 特性语法紧邻 `-- DM-SPECIFIC:` 注释（NFR-COMPAT-5）；
--    ④ 全库禁用级联删除，外键一律 ON DELETE RESTRICT / ON UPDATE RESTRICT（§0.4）。
--
--  ⚠️ 部署前置条件（须由 T-052 部署清单承接，NFR-DEPLOY-5）
--    ① 建库时须设置「标识符大小写不敏感」（DATA_MODEL.md §6.1 D11）；
--    ② 本脚本的 `fk_authorization_product` 指向 `t_product`（TB-PRODUCT-1，PRODUCT 模块），
--       该表不在本里程碑脚本内 —— 见文件末尾「跨模块外键」段的执行顺序说明；
--    ③ 保留系统行 t_sub_account(id = 0) 由 data-init.sql 插入。
--
--  ⚠️ 未实测声明：本环境无 DM8 实例，本脚本**未在 DM8 实机执行**（见 T-004 工单处理记录）。
--     脚本已通过静态校验（列名/类型/约束与 DATA_MODEL.md 逐表对照，证据见 java 侧自检用例）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 模块 CONFIG（系统配置）—— 7 张
-- -----------------------------------------------------------------------------

-- ===== TB-CONFIG-1 t_sub_account 子账户 =====
-- 全系统根维度；被 40+ 张业务表行级引用。不可物理删除，仅可停用。
-- 备注：保留系统行 id = 0（sub_account_code = '__ALL_ACCOUNT__'）作为作用域哨兵值载体（§2.1 备注 / DC-020），
--       该行由 data-init.sql 插入，不是业务子账户，不参与任何业务与加总。
CREATE TABLE t_sub_account (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                    INT          IDENTITY(1,1) NOT NULL,
    sub_account_code      VARCHAR(32)                NOT NULL,
    sub_account_name      VARCHAR(128)               NOT NULL,
    business_line_desc    VARCHAR(500)               NULL,
    status                VARCHAR(24)                NOT NULL,
    category              VARCHAR(16)  DEFAULT 'COMMODITY' NOT NULL,
    category_approved_by  BIGINT                     NULL,
    is_default            CHAR(1)      DEFAULT 'N'   NOT NULL,
    initial_fund          DECIMAL(20,4)              NULL,
    ledger_start_date     DATE                       NULL,
    remark                VARCHAR(500)               NULL,
    disabled_reason       VARCHAR(500)               NULL,
    disable_precheck_json CLOB                       NULL,
    archive_export_id     VARCHAR(128)               NULL,
    created_by            BIGINT                     NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                     NULL,
    updated_time          TIMESTAMP                  NULL,
    row_version           INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_sub_account        PRIMARY KEY (id),
    CONSTRAINT uk_sub_account_code     UNIQUE (sub_account_code),
    CONSTRAINT uk_sub_account_name     UNIQUE (sub_account_name),
    CONSTRAINT ck_sub_account_status   CHECK (status IN ('ENABLED','DISABLED')),
    CONSTRAINT ck_sub_account_category CHECK (category IN ('COMMODITY','TRADE')),
    CONSTRAINT ck_sub_account_is_default CHECK (is_default IN ('Y','N'))
);
COMMENT ON TABLE  t_sub_account                       IS '子账户（全系统根维度；不可物理删除，仅可停用）';
COMMENT ON COLUMN t_sub_account.sub_account_code      IS '子账户编码，全系统唯一且不可修改（R-CONFIG-4）';
COMMENT ON COLUMN t_sub_account.sub_account_name      IS '子账户名称；同一真实账户内不得重复';
COMMENT ON COLUMN t_sub_account.business_line_desc    IS '业务线说明';
COMMENT ON COLUMN t_sub_account.status                IS 'ENABLED / DISABLED（无 DELETED：不可物理删除）';
COMMENT ON COLUMN t_sub_account.category              IS '子账户分类（Q-2）：COMMODITY 商品类90% / TRADE 贸易类80%；变更须风控岗发起+财务负责人审批';
COMMENT ON COLUMN t_sub_account.category_approved_by  IS '分类变更的财务负责人审批人（Q-2）';
COMMENT ON COLUMN t_sub_account.is_default            IS '是否默认子账户（全库至多 1 个 Y，应用层保证）';
COMMENT ON COLUMN t_sub_account.initial_fund          IS '初始资金（台账建立依据，FR-FUND-1）';
COMMENT ON COLUMN t_sub_account.ledger_start_date     IS '资金记账起始日';
COMMENT ON COLUMN t_sub_account.remark                IS '备注';
COMMENT ON COLUMN t_sub_account.disabled_reason       IS '停用原因（停用时必填，应用层校验）';
COMMENT ON COLUMN t_sub_account.disable_precheck_json IS '停用条件校验快照（Q-10 四项：资金0/持仓0/无在途指令/当日已结算）';
COMMENT ON COLUMN t_sub_account.archive_export_id     IS '导出归档批次标识（Q-10：彻底清除只提供导出归档，不做物理删除）';
CREATE INDEX idx_sub_account_status ON t_sub_account (status);

-- ===== TB-CONFIG-2 t_config_item 配置项 =====
-- ARCHITECTURE.md §11 的 CFG-* 全部落在此表的行；敏感字段不入本表（结构上不具备存密码的能力）。
CREATE TABLE t_config_item (
    id                     BIGINT       IDENTITY(1,1) NOT NULL,
    config_key             VARCHAR(128)               NOT NULL,
    config_category        VARCHAR(24)                NOT NULL,
    config_value           VARCHAR(2000)              NULL,
    value_type             VARCHAR(16)                NOT NULL,
    enum_scope             VARCHAR(500)               NULL,
    min_value              DECIMAL(20,6)              NULL,
    max_value              DECIMAL(20,6)              NULL,
    default_value          VARCHAR(2000)              NULL,
    is_sensitive           CHAR(1)      DEFAULT 'N'   NOT NULL,
    sensitive_state        VARCHAR(16)                NULL,
    persist_scope          VARCHAR(16)                NOT NULL,
    dispatch_to_ctp        CHAR(1)      DEFAULT 'N'   NOT NULL,
    double_review_required CHAR(1)      DEFAULT 'N'   NOT NULL,
    related_question_no    VARCHAR(16)                NULL,
    effective_version      BIGINT       DEFAULT 0     NOT NULL,
    description            VARCHAR(500)               NULL,
    created_by             BIGINT                     NOT NULL,
    created_time           TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by             BIGINT                     NULL,
    updated_time           TIMESTAMP                  NULL,
    row_version            INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_config_item PRIMARY KEY (id),
    CONSTRAINT uk_config_item_key UNIQUE (config_key),
    CONSTRAINT ck_config_item_category CHECK (config_category IN ('CTP_CONN','RISK_PARAM','SUB_ACCOUNT','COMPONENT_COMM','REPORT_PERIOD','DICT_SUBJECT','SYSTEM')),
    CONSTRAINT ck_config_item_value_type CHECK (value_type IN ('STRING','INT','DECIMAL','BOOL','ENUM','LIST')),
    CONSTRAINT ck_config_item_sensitive_state CHECK (sensitive_state IS NULL OR sensitive_state IN ('UNSET','SET')),
    CONSTRAINT ck_config_item_persist_scope CHECK (persist_scope IN ('LOCAL_ADMIN','API_DISPATCH','NEVER')),
    CONSTRAINT ck_config_item_is_sensitive CHECK (is_sensitive IN ('Y','N')),
    CONSTRAINT ck_config_item_dispatch_to_ctp CHECK (dispatch_to_ctp IN ('Y','N')),
    CONSTRAINT ck_config_item_double_review CHECK (double_review_required IN ('Y','N')),
    -- 业务不变量（§4.3）：敏感项不得有取值 —— 敏感字段零落盘在数据结构层的落地
    CONSTRAINT ck_config_item_sensitive CHECK (is_sensitive = 'N' OR config_value IS NULL)
);
COMMENT ON TABLE  t_config_item                        IS '配置项（ARCHITECTURE.md §11 CFG-* 的载体；敏感字段不入本表）';
COMMENT ON COLUMN t_config_item.config_key             IS '配置项 Key（如 HG.RISK.SCALE_BASE_MODE）';
COMMENT ON COLUMN t_config_item.config_category        IS '配置分类：CTP_CONN/RISK_PARAM/SUB_ACCOUNT/COMPONENT_COMM/REPORT_PERIOD/DICT_SUBJECT/SYSTEM';
COMMENT ON COLUMN t_config_item.config_value           IS '配置值（统一字符串存储，按 value_type 解析）；敏感字段禁止入本列';
COMMENT ON COLUMN t_config_item.value_type             IS '值类型：STRING/INT/DECIMAL/BOOL/ENUM/LIST';
COMMENT ON COLUMN t_config_item.enum_scope             IS 'ENUM/LIST 时的取值域（逗号分隔）';
COMMENT ON COLUMN t_config_item.min_value              IS '数值下界（FR-CONFIG-11 取值校验）';
COMMENT ON COLUMN t_config_item.max_value              IS '数值上界（FR-CONFIG-11 取值校验）';
COMMENT ON COLUMN t_config_item.default_value          IS '默认值（须与需求文件明示口径一致，R-CONFIG-8）';
COMMENT ON COLUMN t_config_item.is_sensitive           IS '是否敏感字段；Y 时 config_value 必须为 NULL（ck_config_item_sensitive）';
COMMENT ON COLUMN t_config_item.sensitive_state        IS '敏感字段状态 UNSET/SET（只记状态不记取值，R-CONFIG-2）';
COMMENT ON COLUMN t_config_item.persist_scope          IS 'LOCAL_ADMIN 可持久化 / API_DISPATCH 不落盘 / NEVER 零落盘';
COMMENT ON COLUMN t_config_item.dispatch_to_ctp        IS '是否需下发 C++ 组件（R-CONFIG-3）';
COMMENT ON COLUMN t_config_item.double_review_required IS '变更是否须双人复核（放宽合规红线为 Y）';
COMMENT ON COLUMN t_config_item.related_question_no    IS '关联待澄清编号（Q-1~Q-17）；非待澄清衍生项为 NULL';
COMMENT ON COLUMN t_config_item.effective_version      IS '生效版本（下发用 param_version，单调递增）';
COMMENT ON COLUMN t_config_item.description            IS '配置项说明（含默认值与影响提示，NFR-DEPLOY-4）';
CREATE INDEX idx_config_item_category ON t_config_item (config_category);
CREATE INDEX idx_config_item_dispatch ON t_config_item (dispatch_to_ctp);

-- ===== TB-CONFIG-3 t_config_change_log 配置变更留痕 =====
-- 只增不改不删（§0.3）：故不含 updated_by / updated_time / row_version（本表备注显式声明）。
CREATE TABLE t_config_change_log (
    id                      BIGINT       IDENTITY(1,1) NOT NULL,
    config_key              VARCHAR(128)               NOT NULL,
    config_category         VARCHAR(24)                NOT NULL,
    before_value            VARCHAR(2000)              NULL,
    after_value             VARCHAR(2000)              NULL,
    is_sensitive_change     CHAR(1)      DEFAULT 'N'   NOT NULL,
    sensitive_state_before  VARCHAR(16)                NULL,
    sensitive_state_after   VARCHAR(16)                NULL,
    operator_id             BIGINT                     NOT NULL,
    reviewer_id             BIGINT                     NULL,
    change_reason           VARCHAR(500)               NULL,
    dispatch_result         VARCHAR(16)                NULL,
    dispatch_param_version  BIGINT                     NULL,
    change_time             TIMESTAMP                  NOT NULL,
    created_by              BIGINT                     NOT NULL,
    created_time            TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    CONSTRAINT pk_t_config_change_log PRIMARY KEY (id),
    CONSTRAINT ck_config_chg_is_sensitive CHECK (is_sensitive_change IN ('Y','N')),
    CONSTRAINT ck_config_chg_sensitive_state_before CHECK (sensitive_state_before IS NULL OR sensitive_state_before IN ('UNSET','SET')),
    CONSTRAINT ck_config_chg_sensitive_state_after CHECK (sensitive_state_after IS NULL OR sensitive_state_after IN ('UNSET','SET')),
    CONSTRAINT ck_config_chg_dispatch_result CHECK (dispatch_result IS NULL OR dispatch_result IN ('SUCCESS','FAIL','UNCONFIRMED','NOT_REQUIRED')),
    -- 业务不变量（§4.3）：敏感字段变更时前后值必须为 NULL
    CONSTRAINT ck_config_chg_sensitive_values CHECK (is_sensitive_change = 'N' OR (before_value IS NULL AND after_value IS NULL))
);
COMMENT ON TABLE  t_config_change_log                         IS '配置变更留痕（只增不改不删；敏感字段只记状态不记取值）';
COMMENT ON COLUMN t_config_change_log.config_key              IS '配置项 Key';
COMMENT ON COLUMN t_config_change_log.config_category         IS '冗余分类（便于按分类筛选，避免关联查询）';
COMMENT ON COLUMN t_config_change_log.before_value            IS '变更前值；敏感字段恒为 NULL';
COMMENT ON COLUMN t_config_change_log.after_value             IS '变更后值；敏感字段恒为 NULL';
COMMENT ON COLUMN t_config_change_log.is_sensitive_change     IS '是否敏感字段变更；Y 时前后值必须为 NULL';
COMMENT ON COLUMN t_config_change_log.sensitive_state_before  IS '敏感字段变更前状态 UNSET/SET';
COMMENT ON COLUMN t_config_change_log.sensitive_state_after   IS '敏感字段变更后状态 UNSET/SET';
COMMENT ON COLUMN t_config_change_log.operator_id             IS '操作人';
COMMENT ON COLUMN t_config_change_log.reviewer_id             IS '复核人（须双人复核项必填且 != operator_id）';
COMMENT ON COLUMN t_config_change_log.change_reason           IS '变更原因（放宽合规红线时必填，应用层校验）';
COMMENT ON COLUMN t_config_change_log.dispatch_result         IS '下发结果 SUCCESS/FAIL/UNCONFIRMED/NOT_REQUIRED';
COMMENT ON COLUMN t_config_change_log.dispatch_param_version  IS '下发所用 param_version';
COMMENT ON COLUMN t_config_change_log.change_time             IS '变更时间';
CREATE INDEX idx_config_chg_key_time        ON t_config_change_log (config_key, change_time);
CREATE INDEX idx_config_chg_category_time   ON t_config_change_log (config_category, change_time);
CREATE INDEX idx_config_chg_dispatch_fail   ON t_config_change_log (dispatch_result);

-- ===== TB-CONFIG-4 t_dict_item 枚举字典 =====
CREATE TABLE t_dict_item (
    id           BIGINT       IDENTITY(1,1) NOT NULL,
    dict_type    VARCHAR(64)                NOT NULL,
    item_code    VARCHAR(32)                NOT NULL,
    item_name    VARCHAR(128)               NOT NULL,
    item_desc    VARCHAR(500)               NULL,
    sort_no      INT          DEFAULT 0     NOT NULL,
    status       VARCHAR(24)                NOT NULL,
    is_system    CHAR(1)      DEFAULT 'N'   NOT NULL,
    created_by   BIGINT                     NOT NULL,
    created_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by   BIGINT                     NULL,
    updated_time TIMESTAMP                  NULL,
    row_version  INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_dict_item PRIMARY KEY (id),
    CONSTRAINT uk_dict_item_type_code UNIQUE (dict_type, item_code),
    CONSTRAINT ck_dict_item_status CHECK (status IN ('ENABLED','DISABLED')),
    CONSTRAINT ck_dict_item_is_system CHECK (is_system IN ('Y','N'))
);
COMMENT ON TABLE  t_dict_item            IS '枚举字典（被引用的项不得删除，只能停用）';
COMMENT ON COLUMN t_dict_item.dict_type  IS '字典类型（如 TRADE_REASON/UNIT_LABEL/EXPOSURE_DIRECTION）';
COMMENT ON COLUMN t_dict_item.item_code  IS '枚举编码';
COMMENT ON COLUMN t_dict_item.item_name  IS '枚举名称';
COMMENT ON COLUMN t_dict_item.item_desc  IS '业务含义说明（枚举业务含义必须写清，FR-CONFIG-14）';
COMMENT ON COLUMN t_dict_item.sort_no    IS '排序号';
COMMENT ON COLUMN t_dict_item.status     IS 'ENABLED / DISABLED（无删除；被引用只能停用）';
COMMENT ON COLUMN t_dict_item.is_system  IS '是否系统内置项（内置项不可停用）';
CREATE INDEX idx_dict_item_type_status ON t_dict_item (dict_type, status);

-- ===== TB-CONFIG-5 t_account_subject 会计科目字典 =====
CREATE TABLE t_account_subject (
    id                     BIGINT       IDENTITY(1,1) NOT NULL,
    subject_code           VARCHAR(32)                NOT NULL,
    subject_name           VARCHAR(128)               NOT NULL,
    parent_code            VARCHAR(32)                NULL,
    subject_level          INT          DEFAULT 1     NOT NULL,
    direction              VARCHAR(8)                 NOT NULL,
    biz_scene              VARCHAR(32)                NOT NULL,
    is_template_default    CHAR(1)      DEFAULT 'N'   NOT NULL,
    editable_by_enterprise CHAR(1)      DEFAULT 'Y'   NOT NULL,
    status                 VARCHAR(24)                NOT NULL,
    created_by             BIGINT                     NOT NULL,
    created_time           TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by             BIGINT                     NULL,
    updated_time           TIMESTAMP                  NULL,
    row_version            INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_account_subject PRIMARY KEY (id),
    CONSTRAINT uk_account_subject_code UNIQUE (subject_code),
    CONSTRAINT ck_account_subject_direction CHECK (direction IN ('DEBIT','CREDIT')),
    CONSTRAINT ck_account_subject_biz_scene CHECK (biz_scene IN ('OPEN_POSITION','CLOSE_PNL','COMMISSION','MARGIN_CHANGE','FLOAT_PNL','PREMIUM','HEDGE_EFFECT','OTHER')),
    CONSTRAINT ck_account_subject_is_template CHECK (is_template_default IN ('Y','N')),
    CONSTRAINT ck_account_subject_editable CHECK (editable_by_enterprise IN ('Y','N')),
    CONSTRAINT ck_account_subject_status CHECK (status IN ('ENABLED','DISABLED'))
);
COMMENT ON TABLE  t_account_subject                        IS '会计科目字典（不得在代码中硬编码科目，R-ACCT-11）';
COMMENT ON COLUMN t_account_subject.subject_code           IS '科目编码（如 3101 衍生工具）';
COMMENT ON COLUMN t_account_subject.subject_name           IS '科目名称';
COMMENT ON COLUMN t_account_subject.parent_code            IS '上级科目编码（NULL = 一级科目）';
COMMENT ON COLUMN t_account_subject.subject_level          IS '科目级次';
COMMENT ON COLUMN t_account_subject.direction              IS '余额方向：DEBIT / CREDIT';
COMMENT ON COLUMN t_account_subject.biz_scene              IS '适用业务场景（科目映射口径落点，Q-9）';
COMMENT ON COLUMN t_account_subject.is_template_default    IS '是否系统预置的套期会计常用科目模板（Q-9）';
COMMENT ON COLUMN t_account_subject.editable_by_enterprise IS '企业是否可修改（模板项亦为 Y，Q-9 明确企业可增删改）';
COMMENT ON COLUMN t_account_subject.status                 IS 'ENABLED / DISABLED';
CREATE INDEX idx_account_subject_template ON t_account_subject (is_template_default, status);
CREATE INDEX idx_account_subject_scene ON t_account_subject (biz_scene, status);

-- ===== TB-CONFIG-6 t_report_period_config 报告周期配置 =====
CREATE TABLE t_report_period_config (
    id                   BIGINT       IDENTITY(1,1) NOT NULL,
    report_type          VARCHAR(32)                NOT NULL,
    period_unit          VARCHAR(8)                 NOT NULL,
    period_value         INT          DEFAULT 1     NOT NULL,
    remind_advance_days  INT          DEFAULT 0     NOT NULL,
    remind_role_codes    VARCHAR(500)               NOT NULL,
    is_mandatory         CHAR(1)      DEFAULT 'Y'   NOT NULL,
    is_enabled           CHAR(1)      DEFAULT 'Y'   NOT NULL,
    created_by           BIGINT                     NOT NULL,
    created_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by           BIGINT                     NULL,
    updated_time         TIMESTAMP                  NULL,
    row_version          INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_report_period_config PRIMARY KEY (id),
    CONSTRAINT uk_report_period_type UNIQUE (report_type),
    CONSTRAINT ck_report_period_unit CHECK (period_unit IN ('DAY','MONTH','QUARTER','YEAR')),
    CONSTRAINT ck_report_period_mandatory CHECK (is_mandatory IN ('Y','N')),
    CONSTRAINT ck_report_period_enabled CHECK (is_enabled IN ('Y','N')),
    -- 业务不变量（§4.3）：合规必需报告不得被配置关闭（R-CONFIG-7 下沉到数据结构）
    CONSTRAINT ck_report_period_mandatory_enabled CHECK (is_mandatory = 'N' OR is_enabled = 'Y')
);
COMMENT ON TABLE  t_report_period_config                     IS '报告周期配置（FR-CONFIG-15；合规必需报告不得被关闭）';
COMMENT ON COLUMN t_report_period_config.report_type         IS 'DAILY_SETTLE/DAILY_RISK/MONTHLY_CHECK/QUARTERLY_REPORT/ANNUAL_REPORT/MAJOR_EVENT';
COMMENT ON COLUMN t_report_period_config.period_unit          IS 'DAY / MONTH / QUARTER / YEAR';
COMMENT ON COLUMN t_report_period_config.period_value         IS '周期数量';
COMMENT ON COLUMN t_report_period_config.remind_advance_days  IS '提前提醒天数（计划到期提醒另有 CFG-24）';
COMMENT ON COLUMN t_report_period_config.remind_role_codes    IS '提醒接收角色编码（逗号分隔，须为有效角色）';
COMMENT ON COLUMN t_report_period_config.is_mandatory         IS '是否合规必需（Y 时不得被配置为不生成，R-CONFIG-7）';
COMMENT ON COLUMN t_report_period_config.is_enabled           IS '是否启用；is_mandatory=Y 时必须为 Y';
CREATE INDEX idx_report_period_enabled ON t_report_period_config (is_enabled);

-- ===== TB-CONFIG-7 t_ctp_front_config CTP 前置配置 =====
-- 结构上就不具备存储敏感凭据的能力：无任何密码 / AuthCode 列（NFR-SEC-1）。
CREATE TABLE t_ctp_front_config (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    env_type              VARCHAR(16)                NOT NULL,
    front_type            VARCHAR(16)                NOT NULL,
    front_addr            VARCHAR(128)               NOT NULL,
    priority              INT          DEFAULT 0     NOT NULL,
    broker_id             VARCHAR(32)                NOT NULL,
    investor_id           VARCHAR(32)                NOT NULL,
    connect_timeout_ms    INT          DEFAULT 5000  NOT NULL,
    reconnect_interval_ms INT          DEFAULT 3000  NOT NULL,
    flow_path             VARCHAR(256)               NULL,
    is_enabled            CHAR(1)      DEFAULT 'Y'   NOT NULL,
    created_by            BIGINT                     NOT NULL,
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by            BIGINT                     NULL,
    updated_time          TIMESTAMP                  NULL,
    row_version           INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_ctp_front_config PRIMARY KEY (id),
    CONSTRAINT uk_ctp_front_env_type_addr UNIQUE (env_type, front_type, front_addr),
    CONSTRAINT ck_ctp_front_env_type CHECK (env_type IN ('SIMULATION','PRODUCTION')),
    CONSTRAINT ck_ctp_front_front_type CHECK (front_type IN ('TRADE','MD')),
    CONSTRAINT ck_ctp_front_is_enabled CHECK (is_enabled IN ('Y','N'))
);
COMMENT ON TABLE  t_ctp_front_config                       IS 'CTP 前置配置（非敏感项；密码/AuthCode 不在此表，零落盘）';
COMMENT ON COLUMN t_ctp_front_config.env_type              IS 'SIMULATION / PRODUCTION（显式区分，防误连实盘，R-CONFIG-9）';
COMMENT ON COLUMN t_ctp_front_config.front_type            IS 'TRADE / MD';
COMMENT ON COLUMN t_ctp_front_config.front_addr            IS '前置地址（tcp://ip:port）';
COMMENT ON COLUMN t_ctp_front_config.priority              IS '优先级（0 = 主，>0 = 备份）';
COMMENT ON COLUMN t_ctp_front_config.broker_id             IS '经纪商代码';
COMMENT ON COLUMN t_ctp_front_config.investor_id           IS '用户代码（资金账号）';
COMMENT ON COLUMN t_ctp_front_config.connect_timeout_ms    IS '连接超时（毫秒）';
COMMENT ON COLUMN t_ctp_front_config.reconnect_interval_ms IS '重连间隔（毫秒）';
COMMENT ON COLUMN t_ctp_front_config.flow_path             IS 'CTP 流文件目录（非敏感；不得存放明文凭证）';
COMMENT ON COLUMN t_ctp_front_config.is_enabled            IS '是否启用（PRODUCTION 启用须二次确认 + 双人复核）';
CREATE INDEX idx_ctp_front_env_prio ON t_ctp_front_config (env_type, front_type, priority);

-- -----------------------------------------------------------------------------
-- 模块 AUTH（用户与权限）—— 13 张
-- -----------------------------------------------------------------------------

-- ===== TB-AUTH-1 t_user 用户 =====
-- 不可物理删除，只能停用（R-AUTH-1）。口令字段的唯一形态是 password_hash —— 无明文列。
CREATE TABLE t_user (
    id                       BIGINT       IDENTITY(1,1) NOT NULL,
    username                 VARCHAR(64)                NOT NULL,
    real_name                VARCHAR(64)                NOT NULL,
    natural_person_key       VARCHAR(64)                NOT NULL,
    department               VARCHAR(128)               NULL,
    is_enterprise_leader     CHAR(1)      DEFAULT 'N'   NOT NULL,
    leader_flag_reviewer_id  BIGINT                     NULL,
    status                   VARCHAR(24)                NOT NULL,
    password_hash            VARCHAR(128)               NOT NULL,
    must_change_password     CHAR(1)      DEFAULT 'Y'   NOT NULL,
    password_updated_time    TIMESTAMP                  NULL,
    fail_count               INT          DEFAULT 0     NOT NULL,
    locked_until             TIMESTAMP                  NULL,
    last_login_time          TIMESTAMP                  NULL,
    disabled_reason          VARCHAR(500)               NULL,
    created_by               BIGINT                     NOT NULL,
    created_time             TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by               BIGINT                     NULL,
    updated_time             TIMESTAMP                  NULL,
    row_version              INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_user PRIMARY KEY (id),
    CONSTRAINT uk_user_username UNIQUE (username),
    CONSTRAINT uk_user_natural_person UNIQUE (natural_person_key),
    CONSTRAINT ck_user_status CHECK (status IN ('ENABLED','DISABLED')),
    CONSTRAINT ck_user_is_enterprise_leader CHECK (is_enterprise_leader IN ('Y','N')),
    CONSTRAINT ck_user_must_change_password CHECK (must_change_password IN ('Y','N'))
);
COMMENT ON TABLE  t_user                           IS '用户（不可物理删除，只能停用；口令仅以加盐哈希存储）';
COMMENT ON COLUMN t_user.username                  IS '登录名，不可修改';
COMMENT ON COLUMN t_user.real_name                 IS '姓名';
COMMENT ON COLUMN t_user.natural_person_key        IS '自然人唯一键（身份证号哈希或工号）——保证"一个自然人一个账号"（R-AUTH-2）';
COMMENT ON COLUMN t_user.department                IS '所属部门';
COMMENT ON COLUMN t_user.is_enterprise_leader      IS '企业负责人标记；Y 则禁授交易岗 + 禁下单（FR-AUTH-9 / 0x100F）';
COMMENT ON COLUMN t_user.leader_flag_reviewer_id   IS '负责人标记变更的复核人（须双人复核且 != 变更操作人）';
COMMENT ON COLUMN t_user.status                    IS 'ENABLED / DISABLED（无 DELETED）';
COMMENT ON COLUMN t_user.password_hash             IS '加盐哈希：PBKDF2-HMAC-SHA256，格式 pbkdf2$<iter>$<salt_b64>$<hash_b64>；永不明文（NFR-SEC-2）';
COMMENT ON COLUMN t_user.must_change_password      IS '首次登录 / 重置后强制改口令';
COMMENT ON COLUMN t_user.password_updated_time     IS '最近一次口令修改时间';
COMMENT ON COLUMN t_user.fail_count                IS '连续登录失败次数';
COMMENT ON COLUMN t_user.locked_until              IS '锁定至（NULL = 未锁定）；解锁后 fail_count 清零（FR-AUTH-17）';
COMMENT ON COLUMN t_user.last_login_time           IS '最后登录时间';
COMMENT ON COLUMN t_user.disabled_reason           IS '停用原因（停用时必填）';
CREATE INDEX idx_user_status ON t_user (status);
CREATE INDEX idx_user_leader ON t_user (is_enterprise_leader);
CREATE INDEX idx_user_locked ON t_user (locked_until);

-- ===== TB-AUTH-2 t_role 角色 =====
CREATE TABLE t_role (
    id                   BIGINT       IDENTITY(1,1) NOT NULL,
    role_code            VARCHAR(32)                NOT NULL,
    role_name            VARCHAR(128)               NOT NULL,
    position_group_id    INT                        NOT NULL,
    is_preset            CHAR(1)      DEFAULT 'N'   NOT NULL,
    is_business_role     CHAR(1)      DEFAULT 'Y'   NOT NULL,
    has_trading_operation CHAR(1)     DEFAULT 'N'   NOT NULL,
    status               VARCHAR(24)                NOT NULL,
    created_by           BIGINT                     NOT NULL,
    created_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by           BIGINT                     NULL,
    updated_time         TIMESTAMP                  NULL,
    row_version          INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_role PRIMARY KEY (id),
    CONSTRAINT uk_role_code UNIQUE (role_code),
    CONSTRAINT ck_role_is_preset CHECK (is_preset IN ('Y','N')),
    CONSTRAINT ck_role_is_business_role CHECK (is_business_role IN ('Y','N')),
    CONSTRAINT ck_role_has_trading_operation CHECK (has_trading_operation IN ('Y','N')),
    CONSTRAINT ck_role_status CHECK (status IN ('ENABLED','DISABLED')),
    -- 业务不变量（§4.3）：系统管理员（非业务角色）不得拥有交易下单能力
    CONSTRAINT ck_role_sysadmin_no_trading CHECK (is_business_role = 'Y' OR has_trading_operation = 'N'),
    CONSTRAINT fk_role_position_group FOREIGN KEY (position_group_id) REFERENCES t_position_group (id)
);
COMMENT ON TABLE  t_role                        IS '角色（六类业务角色 + 系统管理员；预置角色不可删除，R-AUTH-10）';
COMMENT ON COLUMN t_role.role_code              IS '角色编码：FIN_RISK/TRADER/SETTLER/CASHIER/APPROVER/AUDITOR/SYS_ADMIN';
COMMENT ON COLUMN t_role.role_name              IS '角色名称';
COMMENT ON COLUMN t_role.position_group_id      IS '所属岗位组（互斥判定的单位）';
COMMENT ON COLUMN t_role.is_preset              IS '是否预置角色（Y 则不可删除，只可调整权限）';
COMMENT ON COLUMN t_role.is_business_role       IS '是否业务角色；系统管理员为 N（不得拥有业务操作权限）';
COMMENT ON COLUMN t_role.has_trading_operation  IS '是否含交易下单能力（供互斥与负责人规则快速判定）';
COMMENT ON COLUMN t_role.status                 IS 'ENABLED / DISABLED';
CREATE INDEX idx_role_position_group ON t_role (position_group_id);
CREATE INDEX idx_role_trading ON t_role (has_trading_operation, status);

-- ===== TB-AUTH-3 t_role_permission 角色功能权限 =====
CREATE TABLE t_role_permission (
    id            BIGINT       IDENTITY(1,1) NOT NULL,
    role_id       BIGINT                     NOT NULL,
    perm_code     VARCHAR(128)               NOT NULL,
    perm_category VARCHAR(16)                NOT NULL,
    created_by    BIGINT                     NOT NULL,
    created_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by    BIGINT                     NULL,
    updated_time  TIMESTAMP                  NULL,
    row_version   INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_role_permission PRIMARY KEY (id),
    CONSTRAINT uk_role_perm UNIQUE (role_id, perm_code),
    CONSTRAINT ck_role_perm_category CHECK (perm_category IN ('FUNCTION','DATA','QUOTA')),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES t_role (id)
);
COMMENT ON TABLE  t_role_permission               IS '角色功能权限（角色 → 模块与操作）';
COMMENT ON COLUMN t_role_permission.role_id       IS '角色';
COMMENT ON COLUMN t_role_permission.perm_code     IS '权限编码（如 PRODUCT:EDIT / TRADE:PLACE_ORDER / FUND:TRANSFER）';
COMMENT ON COLUMN t_role_permission.perm_category IS 'FUNCTION / DATA / QUOTA（AUTH.md §2 的三类权限）';
CREATE INDEX idx_role_perm_code ON t_role_permission (perm_code);

-- ===== TB-AUTH-4 t_user_role 用户角色 =====
CREATE TABLE t_user_role (
    id           BIGINT       IDENTITY(1,1) NOT NULL,
    user_id      BIGINT                     NOT NULL,
    role_id      BIGINT                     NOT NULL,
    granted_by   BIGINT                     NOT NULL,
    granted_time TIMESTAMP                  NOT NULL,
    reviewer_id  BIGINT                     NULL,
    created_by   BIGINT                     NOT NULL,
    created_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by   BIGINT                     NULL,
    updated_time TIMESTAMP                  NULL,
    row_version  INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_user_role PRIMARY KEY (id),
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES t_user (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES t_role (id)
);
COMMENT ON TABLE  t_user_role              IS '用户角色（岗位互斥校验的落点，服务层在插入前校验）';
COMMENT ON COLUMN t_user_role.user_id      IS '用户';
COMMENT ON COLUMN t_user_role.role_id      IS '角色';
COMMENT ON COLUMN t_user_role.granted_by   IS '授予人';
COMMENT ON COLUMN t_user_role.granted_time IS '授予时间';
COMMENT ON COLUMN t_user_role.reviewer_id  IS '复核人（须双人复核的角色必填且 != granted_by）';
CREATE INDEX idx_user_role_role ON t_user_role (role_id);

-- ===== TB-AUTH-5 t_position_group 岗位组 =====
CREATE TABLE t_position_group (
    id                   BIGINT       IDENTITY(1,1) NOT NULL,
    group_code           VARCHAR(32)                NOT NULL,
    group_name           VARCHAR(128)               NOT NULL,
    front_mid_back       VARCHAR(16)                NOT NULL,
    is_conflict_enforced CHAR(1)      DEFAULT 'Y'   NOT NULL,
    status               VARCHAR(24)                NOT NULL,
    created_by           BIGINT                     NOT NULL,
    created_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by           BIGINT                     NULL,
    updated_time         TIMESTAMP                  NULL,
    row_version          INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_position_group PRIMARY KEY (id),
    CONSTRAINT uk_position_group_code UNIQUE (group_code),
    CONSTRAINT ck_position_group_fmb CHECK (front_mid_back IN ('FRONT','MID','BACK','MANAGE','SYSTEM')),
    CONSTRAINT ck_position_group_status CHECK (status IN ('ENABLED','DISABLED')),
    -- 不得提供"关闭互斥"的开关（FR-AUTH-7 验收 2）在数据结构层的落地
    CONSTRAINT ck_position_group_conflict_enforced CHECK (is_conflict_enforced = 'Y')
);
COMMENT ON TABLE  t_position_group                       IS '岗位组（角色按前中后台归并的类别，互斥判定的单位）';
COMMENT ON COLUMN t_position_group.group_code            IS '岗位组编码';
COMMENT ON COLUMN t_position_group.group_name            IS '岗位组名称';
COMMENT ON COLUMN t_position_group.front_mid_back        IS 'FRONT 交易 / MID 风控结算资金 / BACK 审计 / MANAGE 管理审批 / SYSTEM 系统';
COMMENT ON COLUMN t_position_group.is_conflict_enforced  IS '是否参与强制互斥；恒为 Y（不允许关闭互斥，R-AUTH-3）';
COMMENT ON COLUMN t_position_group.status                IS 'ENABLED / DISABLED';
CREATE INDEX idx_position_group_fmb ON t_position_group (front_mid_back);

-- ===== TB-AUTH-6 t_position_conflict 岗位互斥关系 =====
CREATE TABLE t_position_conflict (
    id              BIGINT       IDENTITY(1,1) NOT NULL,
    group_a_id      INT                        NOT NULL,
    group_b_id      INT                        NOT NULL,
    conflict_reason VARCHAR(500)               NOT NULL,
    is_mandatory    CHAR(1)      DEFAULT 'Y'   NOT NULL,
    reviewer_id     BIGINT                     NULL,
    created_by      BIGINT                     NOT NULL,
    created_time    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by      BIGINT                     NULL,
    updated_time    TIMESTAMP                  NULL,
    row_version     INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_position_conflict PRIMARY KEY (id),
    CONSTRAINT uk_position_conflict_pair UNIQUE (group_a_id, group_b_id),
    CONSTRAINT ck_position_conflict_is_mandatory CHECK (is_mandatory IN ('Y','N')),
    CONSTRAINT fk_position_conflict_group_a FOREIGN KEY (group_a_id) REFERENCES t_position_group (id),
    CONSTRAINT fk_position_conflict_group_b FOREIGN KEY (group_b_id) REFERENCES t_position_group (id)
);
COMMENT ON TABLE  t_position_conflict                 IS '岗位组两两互斥矩阵（唯一事实源；服务层授予前查矩阵，R-AUTH-3/R-AUTH-8）';
COMMENT ON COLUMN t_position_conflict.group_a_id      IS '岗位组 A（矩阵按单向存储，仅存 a < b 的组合，服务层双向匹配）';
COMMENT ON COLUMN t_position_conflict.group_b_id      IS '岗位组 B';
COMMENT ON COLUMN t_position_conflict.conflict_reason IS '互斥依据（如 8 号文 四(一) 前中后台分离）';
COMMENT ON COLUMN t_position_conflict.is_mandatory    IS '是否强制互斥；默认互斥集恒为 Y（不可转为可配置）';
COMMENT ON COLUMN t_position_conflict.reviewer_id     IS '变更复核人（互斥配置变更须双人复核）';
CREATE INDEX idx_position_conflict_a ON t_position_conflict (group_a_id);

-- ===== TB-AUTH-7 t_authorization 授权 =====
-- 人员名单 + 品种 + 额度 三者齐备（8 号文 四(二)）；事前风控权限校验的输入。
CREATE TABLE t_authorization (
    id                BIGINT       IDENTITY(1,1) NOT NULL,
    user_id           BIGINT                     NOT NULL,
    sub_account_id    INT                        NOT NULL,
    product_id        BIGINT                     NOT NULL,
    quota_limit       DECIMAL(20,4)              NOT NULL,
    effective_from    DATE                       NOT NULL,
    effective_to      DATE                       NULL,
    status            VARCHAR(24)                NOT NULL,
    applicant_id      BIGINT                     NOT NULL,
    reviewer_id       BIGINT                     NULL,
    dispatch_status   VARCHAR(16)  DEFAULT 'PENDING' NOT NULL,
    dispatch_version  BIGINT                     NULL,
    reject_reason     VARCHAR(500)               NULL,
    created_by        BIGINT                     NOT NULL,
    created_time      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by        BIGINT                     NULL,
    updated_time      TIMESTAMP                  NULL,
    row_version       INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_authorization PRIMARY KEY (id),
    CONSTRAINT uk_authorization_unique UNIQUE (user_id, sub_account_id, product_id, effective_from),
    CONSTRAINT ck_authorization_status CHECK (status IN ('DRAFT','PENDING_REVIEW','EFFECTIVE','EXPIRED','REJECTED')),
    CONSTRAINT ck_authorization_dispatch_status CHECK (dispatch_status IN ('PENDING','SUCCESS','FAIL','UNCONFIRMED')),
    -- 仅配置人员与品种、未配置额度时保存被拒绝（FR-AUTH-10 验收 2）下沉到数据结构
    CONSTRAINT ck_authorization_quota_positive CHECK (quota_limit > 0),
    CONSTRAINT fk_authorization_user FOREIGN KEY (user_id) REFERENCES t_user (id),
    CONSTRAINT fk_authorization_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
    -- fk_authorization_product 见文件末尾「跨模块外键」段（依赖 TB-PRODUCT-1 t_product）
);
COMMENT ON TABLE  t_authorization                  IS '授权（人员名单 + 品种 + 额度；8 号文 四(二)）';
COMMENT ON COLUMN t_authorization.user_id          IS '被授权人（人员名单）';
COMMENT ON COLUMN t_authorization.sub_account_id   IS '授权适用的子账户（行级归属列，恒 > 0）';
COMMENT ON COLUMN t_authorization.product_id       IS '授权品种（每品种一行，便于精确校验）';
COMMENT ON COLUMN t_authorization.quota_limit      IS '授权额度（该用户该子账户该品种的个人上限）；须 > 0';
COMMENT ON COLUMN t_authorization.effective_from   IS '生效起始日';
COMMENT ON COLUMN t_authorization.effective_to     IS '生效截止日（NULL = 长期）';
COMMENT ON COLUMN t_authorization.status           IS 'DRAFT / PENDING_REVIEW / EFFECTIVE / EXPIRED / REJECTED';
COMMENT ON COLUMN t_authorization.applicant_id     IS '申请人';
COMMENT ON COLUMN t_authorization.reviewer_id      IS '复核人（!= applicant_id，R-AUTH-4）';
COMMENT ON COLUMN t_authorization.dispatch_status  IS '下发 C++ 状态 PENDING/SUCCESS/FAIL/UNCONFIRMED';
COMMENT ON COLUMN t_authorization.dispatch_version IS '下发所用 param_version';
COMMENT ON COLUMN t_authorization.reject_reason    IS '驳回原因（驳回时必填）';
CREATE INDEX idx_authorization_user_eff ON t_authorization (user_id, status, effective_to);
CREATE INDEX idx_authorization_dispatch ON t_authorization (dispatch_status);
CREATE INDEX idx_authorization_sub ON t_authorization (sub_account_id);

-- ===== TB-AUTH-8 t_user_sub_account_perm 子账户数据权限 =====
-- 软隔离；安全默认 = 无记录 = 不可见任何子账户（NFR-SEC-7）。
CREATE TABLE t_user_sub_account_perm (
    id             BIGINT       IDENTITY(1,1) NOT NULL,
    user_id        BIGINT                     NOT NULL,
    sub_account_id INT                        NOT NULL,
    perm_type      VARCHAR(24)                NOT NULL,
    granted_by     BIGINT                     NOT NULL,
    reviewer_id    BIGINT                     NULL,
    effective_from DATE                       NOT NULL,
    effective_to   DATE                       NULL,
    created_by     BIGINT                     NOT NULL,
    created_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by     BIGINT                     NULL,
    updated_time   TIMESTAMP                  NULL,
    row_version    INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_user_sub_account_perm PRIMARY KEY (id),
    CONSTRAINT uk_user_sub_perm UNIQUE (user_id, sub_account_id),
    CONSTRAINT ck_user_sub_perm_type CHECK (perm_type IN ('LIMITED','CROSS_READONLY')),
    CONSTRAINT fk_user_sub_perm_user FOREIGN KEY (user_id) REFERENCES t_user (id),
    CONSTRAINT fk_user_sub_perm_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
);
COMMENT ON TABLE  t_user_sub_account_perm                  IS '子账户数据权限（软隔离；无记录 = 不可见任何子账户）';
COMMENT ON COLUMN t_user_sub_account_perm.user_id          IS '用户';
COMMENT ON COLUMN t_user_sub_account_perm.sub_account_id   IS '被授权的子账户（语义为"被授权的子账户"，非数据归属）';
COMMENT ON COLUMN t_user_sub_account_perm.perm_type        IS 'LIMITED 限定子账户可读写 / CROSS_READONLY 跨子账户只读';
COMMENT ON COLUMN t_user_sub_account_perm.granted_by       IS '授予人';
COMMENT ON COLUMN t_user_sub_account_perm.reviewer_id      IS '复核人（!= granted_by，须双人复核）';
COMMENT ON COLUMN t_user_sub_account_perm.effective_from   IS '生效起始日';
COMMENT ON COLUMN t_user_sub_account_perm.effective_to     IS '生效截止日';
CREATE INDEX idx_user_sub_perm_user ON t_user_sub_account_perm (user_id, perm_type);

-- ===== TB-AUTH-9 t_login_session 登录会话 =====
-- 允许物理 DELETE（清理历史会话）；状态流转留存于 t_login_log（只增不改）。
CREATE TABLE t_login_session (
    id                          BIGINT       IDENTITY(1,1) NOT NULL,
    session_id                  VARCHAR(64)                NOT NULL,
    user_id                     BIGINT                     NOT NULL,
    login_time                  TIMESTAMP                  NOT NULL,
    last_active_time            TIMESTAMP                  NOT NULL,
    idle_timeout_minutes        INT                        NOT NULL,
    expire_time                 TIMESTAMP                  NOT NULL,
    client_ip                   VARCHAR(64)                NULL,
    user_agent                  VARCHAR(500)               NULL,
    status                      VARCHAR(24)                NOT NULL,
    kicked_by                   BIGINT                     NULL,
    permission_version_snapshot BIGINT                     NOT NULL,
    created_by                  BIGINT                     NOT NULL,
    created_time                TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by                  BIGINT                     NULL,
    updated_time                TIMESTAMP                  NULL,
    row_version                 INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_login_session PRIMARY KEY (id),
    CONSTRAINT uk_login_session_id UNIQUE (session_id),
    CONSTRAINT ck_login_session_status CHECK (status IN ('ACTIVE','LOGGED_OUT','EXPIRED','KICKED')),
    CONSTRAINT fk_login_session_user FOREIGN KEY (user_id) REFERENCES t_user (id)
);
COMMENT ON TABLE  t_login_session                             IS '登录会话（空闲超时；允许物理删除，状态流转留存于 t_login_log）';
COMMENT ON COLUMN t_login_session.session_id                  IS '服务端生成的 opaque 会话 ID（Cookie HGSESSION）';
COMMENT ON COLUMN t_login_session.user_id                     IS '用户';
COMMENT ON COLUMN t_login_session.login_time                  IS '登录时间';
COMMENT ON COLUMN t_login_session.last_active_time            IS '最后活动时间（空闲超时判定）';
COMMENT ON COLUMN t_login_session.idle_timeout_minutes        IS '该会话的空闲超时（登录时快照，防运行期改配置影响既有会话）';
COMMENT ON COLUMN t_login_session.expire_time                 IS '绝对过期时间';
COMMENT ON COLUMN t_login_session.client_ip                   IS '客户端 IP（审计用）';
COMMENT ON COLUMN t_login_session.user_agent                  IS 'UA（审计用）';
COMMENT ON COLUMN t_login_session.status                      IS 'ACTIVE / LOGGED_OUT / EXPIRED / KICKED';
COMMENT ON COLUMN t_login_session.kicked_by                   IS '强制下线的管理员';
COMMENT ON COLUMN t_login_session.permission_version_snapshot IS '会话建立时的权限版本快照；权限变更后须使相关会话立即失效（R-AUTH-9）';
CREATE INDEX idx_login_session_user_status ON t_login_session (user_id, status);
CREATE INDEX idx_login_session_expire ON t_login_session (expire_time);

-- ===== TB-AUTH-10 t_login_log 登录日志 =====
-- 只增不改不删（§0.3）：updated_by / updated_time 恒为 NULL，row_version 不使用。
-- 本表无口令字段；username_input 原样存储仅用于内部定位，对外响应统一（R-AUTH-7）。
CREATE TABLE t_login_log (
    id                BIGINT       IDENTITY(1,1) NOT NULL,
    user_id           BIGINT                     NULL,
    username_input    VARCHAR(64)                NOT NULL,
    event_type        VARCHAR(24)                NOT NULL,
    fail_count_after  INT                        NULL,
    client_ip         VARCHAR(64)                NULL,
    user_agent        VARCHAR(500)               NULL,
    fail_reason_code  VARCHAR(32)                NULL,
    event_time        TIMESTAMP                  NOT NULL,
    created_by        BIGINT                     NOT NULL,
    created_time      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by        BIGINT                     NULL,
    updated_time      TIMESTAMP                  NULL,
    row_version       INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_login_log PRIMARY KEY (id),
    CONSTRAINT ck_login_log_event_type CHECK (event_type IN ('LOGIN_SUCCESS','LOGIN_FAIL','LOCKED','UNLOCKED','LOGOUT','KICKED')),
    CONSTRAINT fk_login_log_user FOREIGN KEY (user_id) REFERENCES t_user (id)
);
COMMENT ON TABLE  t_login_log                  IS '登录日志（登录成功/失败/锁定/解锁全部留痕，R-AUTH-7；只增不改不删）';
COMMENT ON COLUMN t_login_log.user_id          IS '用户（用户不存在时为 NULL，但该场景不向用户暴露，防枚举）';
COMMENT ON COLUMN t_login_log.username_input   IS '输入的用户名（不含口令）';
COMMENT ON COLUMN t_login_log.event_type       IS 'LOGIN_SUCCESS/LOGIN_FAIL/LOCKED/UNLOCKED/LOGOUT/KICKED';
COMMENT ON COLUMN t_login_log.fail_count_after IS '事件后的失败计数';
COMMENT ON COLUMN t_login_log.client_ip        IS '客户端 IP';
COMMENT ON COLUMN t_login_log.user_agent       IS 'UA';
COMMENT ON COLUMN t_login_log.fail_reason_code IS '失败原因内部码（运维分析用）；对外统一为同一提示（R-AUTH-7）';
COMMENT ON COLUMN t_login_log.event_time       IS '事件时间';
CREATE INDEX idx_login_log_user_time  ON t_login_log (user_id, event_time);
CREATE INDEX idx_login_log_time_type  ON t_login_log (event_time, event_type);
CREATE INDEX idx_login_log_ip         ON t_login_log (client_ip, event_time);

-- ===== TB-AUTH-11 t_password_history 口令历史 =====
CREATE TABLE t_password_history (
    id            BIGINT       IDENTITY(1,1) NOT NULL,
    user_id       BIGINT                     NOT NULL,
    password_hash VARCHAR(128)               NOT NULL,
    changed_time  TIMESTAMP                  NOT NULL,
    seq_no        INT                        NOT NULL,
    created_by    BIGINT                     NOT NULL,
    created_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by    BIGINT                     NULL,
    updated_time  TIMESTAMP                  NULL,
    row_version   INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_password_history PRIMARY KEY (id),
    CONSTRAINT uk_password_history UNIQUE (user_id, seq_no),
    CONSTRAINT fk_password_history_user FOREIGN KEY (user_id) REFERENCES t_user (id)
);
COMMENT ON TABLE  t_password_history               IS '口令历史（最近 N 次，不得与最近 5 次相同，NFR-SEC-2）';
COMMENT ON COLUMN t_password_history.user_id       IS '用户';
COMMENT ON COLUMN t_password_history.password_hash IS '历史口令的加盐哈希（PBKDF2-HMAC-SHA256）';
COMMENT ON COLUMN t_password_history.changed_time  IS '修改时间';
COMMENT ON COLUMN t_password_history.seq_no        IS '序号（1 = 最近）；保留 HG.AUTH.PASSWORD_HISTORY_SIZE 条（默认 5）';
CREATE INDEX idx_password_history_user_time ON t_password_history (user_id, changed_time);

-- ===== TB-AUTH-12 t_permission_change_log 权限变更留痕 =====
-- 只增不改不删（§0.3）：updated_by / updated_time 恒为 NULL，row_version 不使用。
CREATE TABLE t_permission_change_log (
    id              BIGINT       IDENTITY(1,1) NOT NULL,
    change_target   VARCHAR(24)                NOT NULL,
    target_id       BIGINT                     NULL,
    target_user_id  BIGINT                     NULL,
    sub_account_id  INT                        NULL,
    before_value    CLOB                       NULL,
    after_value     CLOB                       NULL,
    operator_id     BIGINT                     NOT NULL,
    reviewer_id     BIGINT                     NULL,
    change_reason   VARCHAR(500)               NULL,
    rejected        CHAR(1)      DEFAULT 'N'   NOT NULL,
    reject_reason   VARCHAR(500)               NULL,
    change_time     TIMESTAMP                  NOT NULL,
    dispatch_status VARCHAR(16)                NULL,
    created_by      BIGINT                     NOT NULL,
    created_time    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by      BIGINT                     NULL,
    updated_time    TIMESTAMP                  NULL,
    row_version     INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_permission_change_log PRIMARY KEY (id),
    CONSTRAINT ck_perm_chg_change_target CHECK (change_target IN ('USER_ROLE','AUTHORIZATION','SUB_ACCOUNT_PERM','USER_ATTR','ROLE_DEF','POSITION_CONFLICT')),
    CONSTRAINT ck_perm_chg_rejected CHECK (rejected IN ('Y','N')),
    CONSTRAINT ck_perm_chg_dispatch_status CHECK (dispatch_status IS NULL OR dispatch_status IN ('PENDING','SUCCESS','FAIL','UNCONFIRMED','NOT_REQUIRED')),
    CONSTRAINT fk_perm_chg_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
);
COMMENT ON TABLE  t_permission_change_log                  IS '权限变更留痕（含前后值与复核人；被拒绝的尝试也须留痕，R-AUTH-8；只增不改不删）';
COMMENT ON COLUMN t_permission_change_log.change_target    IS 'USER_ROLE/AUTHORIZATION/SUB_ACCOUNT_PERM/USER_ATTR/ROLE_DEF/POSITION_CONFLICT';
COMMENT ON COLUMN t_permission_change_log.target_id        IS '变更对象 ID（如 t_authorization.id）';
COMMENT ON COLUMN t_permission_change_log.target_user_id   IS '受影响的用户（便于按用户查历史）';
COMMENT ON COLUMN t_permission_change_log.sub_account_id   IS '涉及的子账户（数据权限变更时非空）';
COMMENT ON COLUMN t_permission_change_log.before_value     IS '变更前值（JSON 快照）';
COMMENT ON COLUMN t_permission_change_log.after_value      IS '变更后值（JSON 快照）';
COMMENT ON COLUMN t_permission_change_log.operator_id      IS '操作人';
COMMENT ON COLUMN t_permission_change_log.reviewer_id      IS '复核人（!= operator_id）';
COMMENT ON COLUMN t_permission_change_log.change_reason    IS '变更原因';
COMMENT ON COLUMN t_permission_change_log.rejected         IS '是否被拒绝（拒绝的尝试也须留痕，R-AUTH-8）';
COMMENT ON COLUMN t_permission_change_log.reject_reason    IS '拒绝原因（岗位冲突 / 负责人禁授交易岗 / 系统管理员不得持业务角色）';
COMMENT ON COLUMN t_permission_change_log.change_time      IS '变更时间';
COMMENT ON COLUMN t_permission_change_log.dispatch_status  IS '是否已下发 C++（PENDING/SUCCESS/FAIL/UNCONFIRMED/NOT_REQUIRED）';
CREATE INDEX idx_perm_chg_user_time     ON t_permission_change_log (target_user_id, change_time);
CREATE INDEX idx_perm_chg_target_time   ON t_permission_change_log (change_target, change_time);
CREATE INDEX idx_perm_chg_operator_time ON t_permission_change_log (operator_id, change_time);
CREATE INDEX idx_perm_chg_rejected      ON t_permission_change_log (rejected, change_time);

-- ===== TB-AUTH-13 t_unauthorized_attempt 越权尝试记录 =====
-- 只增不改不删（§0.3）：updated_by / updated_time 恒为 NULL，row_version 不使用。
CREATE TABLE t_unauthorized_attempt (
    id              BIGINT       IDENTITY(1,1) NOT NULL,
    operator_id     BIGINT                     NOT NULL,
    attempt_type    VARCHAR(32)                NOT NULL,
    attempt_desc    VARCHAR(500)               NOT NULL,
    target_resource VARCHAR(256)               NULL,
    sub_account_id  INT                        NULL,
    reject_reason   VARCHAR(500)               NOT NULL,
    reject_code     VARCHAR(32)                NULL,
    attempt_time    TIMESTAMP                  NOT NULL,
    client_ip       VARCHAR(64)                NULL,
    created_by      BIGINT                     NOT NULL,
    created_time    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL, -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值
    updated_by      BIGINT                     NULL,
    updated_time    TIMESTAMP                  NULL,
    row_version     INT          DEFAULT 0     NOT NULL,
    CONSTRAINT pk_t_unauthorized_attempt PRIMARY KEY (id),
    CONSTRAINT ck_unauth_attempt_type CHECK (attempt_type IN ('POSITION_CONFLICT','LEADER_TRADE','CROSS_SUB_WRITE','SYSADMIN_BIZ_OP','DUAL_REVIEW_SELF','PERMISSION_DENIED','SELF_APPROVE')),
    CONSTRAINT fk_unauth_attempt_user FOREIGN KEY (operator_id) REFERENCES t_user (id),
    CONSTRAINT fk_unauth_attempt_sub_account FOREIGN KEY (sub_account_id) REFERENCES t_sub_account (id)
);
COMMENT ON TABLE  t_unauthorized_attempt                 IS '越权尝试记录（与 t_permission_change_log 的 rejected=Y 行互补，FR-AUTH-18；只增不改不删）';
COMMENT ON COLUMN t_unauthorized_attempt.operator_id     IS '操作人';
COMMENT ON COLUMN t_unauthorized_attempt.attempt_type    IS 'POSITION_CONFLICT/LEADER_TRADE/CROSS_SUB_WRITE/SYSADMIN_BIZ_OP/DUAL_REVIEW_SELF/PERMISSION_DENIED/SELF_APPROVE';
COMMENT ON COLUMN t_unauthorized_attempt.attempt_desc    IS '尝试的操作描述';
COMMENT ON COLUMN t_unauthorized_attempt.target_resource IS '目标资源（API 路径 / 对象标识）';
COMMENT ON COLUMN t_unauthorized_attempt.sub_account_id  IS '涉及的子账户（跨子账户类越权必有值）';
COMMENT ON COLUMN t_unauthorized_attempt.reject_reason   IS '拒绝原因';
COMMENT ON COLUMN t_unauthorized_attempt.reject_code     IS '拒绝错误码（业务码 10005 / 组件码 0x100E 等）';
COMMENT ON COLUMN t_unauthorized_attempt.attempt_time    IS '尝试时间';
COMMENT ON COLUMN t_unauthorized_attempt.client_ip       IS '客户端 IP';
CREATE INDEX idx_unauth_attempt_user_time ON t_unauthorized_attempt (operator_id, attempt_time);
CREATE INDEX idx_unauth_attempt_type_time ON t_unauthorized_attempt (attempt_type, attempt_time);

-- =============================================================================
--  跨模块外键（依赖其他里程碑脚本）
--
--  ⚠️ 执行顺序：以下约束指向 PRODUCT 模块的表，**本脚本单独执行时须先建该表**，
--     否则本段会失败（不影响上面 20 张表的建表结果）。
--     ① fk_authorization_product → t_product(TB-PRODUCT-1)：须先执行 PRODUCT 模块建表脚本。
--     ② 若部署策略要求"先建 CONFIG+AUTH"，可暂缓执行本段，并在 PRODUCT 脚本就绪后补执行
--        （由 T-052 部署清单登记执行顺序，NFR-DEPLOY-2）。
--     级联规则一律 RESTRICT（§0.4：禁止级联删除）。
-- =============================================================================
ALTER TABLE t_authorization ADD CONSTRAINT fk_authorization_product
    FOREIGN KEY (product_id) REFERENCES t_product (id);

-- =============================================================================
--  本脚本明细自检（供 DBA 执行后核对）
--    表数：CONFIG 7（t_sub_account、t_config_item、t_config_change_log、t_dict_item、
--          t_account_subject、t_report_period_config、t_ctp_front_config）
--          AUTH  13（t_user、t_role、t_role_permission、t_user_role、t_position_group、
--          t_position_conflict、t_authorization、t_user_sub_account_perm、t_login_session、
--          t_login_log、t_password_history、t_permission_change_log、t_unauthorized_attempt）
--    合计：20 张 = DATA_MODEL.md §0.6 CONFIG(7) + AUTH(13) ✅
--    通用审计字段：除 t_config_change_log（本表备注显式排除 updated_by/updated_time/row_version）外，
--          其余 19 张含 id / created_by / created_time / updated_by / updated_time / row_version（§0.3）。
--    未在本脚本落地的 DB 级约束（诚实声明）：
--      · 「五类业务数据行 sub_account_id 恒 > 0」由**服务层**保证（§0.4 / §4.4），
--        本脚本不为 A 类行添加 CHECK —— 与 §4.3「作用域哨兵值具名 CHECK」清单一致
--        （该清单仅覆盖 TB-PRODUCT-6 / TB-RISK-11 / TB-RISK-1 / TB-SETTLE-13 / TB-SETTLE-15，均不在本脚本）。
--      · 岗位互斥（t_user_role）、双人复核（applicant != reviewer）等跨行规则为服务层职责（§4.4），
--        不得用触发器实现（PROJECT.md §6 永久禁止）。
-- =============================================================================
