-- =============================================================================
--  HedgeGuard 建表脚本 —— 模块 PRODUCT（品种管理），8 张
--  制品编号：docs/DEPLOY/schema/m1_product.sql
--  依据：docs/DATA_MODEL.md v1.3.2（md5 99b647ec3e7a29b95ee86b04a58c747c）
--        §0.1 命名 / §0.2 类型 / §0.3 审计字段 / §0.4 外键与级联 / §0.5 脚本模板
--        §2.3 PRODUCT（TB-PRODUCT-1~8）/ §3.1 枚举取值域 / §4.3 具名 CHECK / §4.6.3 三层划分
--  目标库：达梦 DM8（PROJECT.md §5.1）
--  执行者：DBA（应用账号仅 SELECT/INSERT/UPDATE，禁 DDL —— NFR-SEC-8）
--
--  ⚠️ 脚本纪律（DATA_MODEL.md §0.5）
--    ① 不含存储过程 / 触发器 / 自定义函数（PROJECT.md §6 永久禁止）；
--    ② 不含任何 DML —— 初始化数据见同目录 data-init.sql；
--    ③ 每条 DM 特性语法紧邻 `-- DM-SPECIFIC:` 注释（NFR-COMPAT-5）；
--    ④ 全库禁用级联删除，外键一律 ON DELETE RESTRICT / ON UPDATE RESTRICT（§0.4）。
--
--  ⚠️ 未实测声明：本环境无 DM8 实例，本脚本**未在 DM8 实机执行**。
--     脚本已通过静态校验（列名/类型/非空/索引/外键与 DATA_MODEL.md 逐表对照，
--     证据见 java 侧 M1ProductSpotPlanSchemaTest）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 模块 PRODUCT（品种管理）—— 8 张
-- 子账户贯穿（§4.6.3）：① NOT NULL 行级携带 = 3（白名单 + 层①/③额度 + 层②额度）
--                        ② 可空但已说明 = 1（t_product_change_log）
--                        ③ 无该列（品种档案全局共享）= 4
-- -----------------------------------------------------------------------------

-- ===== TB-PRODUCT-1 t_product 品种 =====
-- 品种档案为全局共享（R-PRODUCT-7「品种一份，白名单与额度多份」）；编码全系统唯一且不可修改。
CREATE TABLE t_product (
    -- DM-SPECIFIC: IDENTITY 自增；DM 特有，换库需改为序列或 AUTO_INCREMENT
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    product_code          VARCHAR(32)               NOT NULL,
    product_name          VARCHAR(128)              NOT NULL,
    category              VARCHAR(16)               NOT NULL,
    category_managed      CHAR(1)      DEFAULT 'Y'  NOT NULL,
    status                VARCHAR(24)               NOT NULL,
    is_domestic_exchange  CHAR(1)      DEFAULT 'Y'  NOT NULL,
    currency              VARCHAR(8)   DEFAULT 'CNY' NOT NULL,
    remark                VARCHAR(500)              NULL,
    disabled_reason       VARCHAR(500)              NULL,
    created_by            BIGINT                    NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                    NULL,
    updated_time          TIMESTAMP                 NULL,
    row_version           INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_product             PRIMARY KEY (id),
    CONSTRAINT uk_product_code          UNIQUE (product_code),
    CONSTRAINT ck_product_category      CHECK (category IN ('COMMODITY','TRADE')),
    CONSTRAINT ck_product_category_managed CHECK (category_managed <> 'Y' OR category IS NOT NULL),
    CONSTRAINT ck_product_status        CHECK (status IN ('ENABLED','DISABLED')),
    CONSTRAINT ck_product_domestic      CHECK (is_domestic_exchange = 'Y'),
    CONSTRAINT ck_product_managed_flag  CHECK (category_managed IN ('Y','N')),
    CONSTRAINT ck_product_currency      CHECK (currency = 'CNY')
);
-- DM-SPECIFIC: 表/列注释语法
COMMENT ON TABLE  t_product                       IS '品种（全局共享档案；编码一经使用不得修改，无 DELETED 状态）';
COMMENT ON COLUMN t_product.product_code          IS '品种编码，全系统唯一';
COMMENT ON COLUMN t_product.category              IS '品种分类：COMMODITY（90% 红线）/ TRADE（80% 红线）';
COMMENT ON COLUMN t_product.is_domestic_exchange  IS '是否境内场内，恒为 Y（境外/场外永久排除）';
CREATE INDEX idx_product_status_category ON t_product (status, category);
CREATE INDEX idx_product_name            ON t_product (product_name);

-- ===== TB-PRODUCT-2 t_product_unit 计量单位与换算关系 =====
-- 换算关系缺失的品种不得启用（R-PRODUCT-6）；换算关系变更不追溯重算历史。
CREATE TABLE t_product_unit (
    id                BIGINT       IDENTITY(1,1) NOT NULL,
    product_id        BIGINT                    NOT NULL,
    spot_unit         VARCHAR(16)               NOT NULL,
    futures_unit      VARCHAR(16)               NOT NULL,
    convert_factor    DECIMAL(20,6)             NOT NULL,
    convert_desc      VARCHAR(500)              NULL,
    status            VARCHAR(24)               NOT NULL,
    effective_from    DATE                      NOT NULL,
    created_by        BIGINT                    NOT NULL,
    created_time      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by        BIGINT                    NULL,
    updated_time      TIMESTAMP                 NULL,
    row_version       INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_product_unit            PRIMARY KEY (id),
    CONSTRAINT uk_product_unit_eff          UNIQUE (product_id, effective_from),
    CONSTRAINT ck_product_unit_factor_positive CHECK (convert_factor > 0),
    CONSTRAINT ck_product_unit_status        CHECK (status IN ('ENABLED','DISABLED')),
    CONSTRAINT fk_product_unit_product      FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_product_unit                 IS '计量单位与换算关系（1 futures_unit = convert_factor spot_unit）';
COMMENT ON COLUMN t_product_unit.convert_factor  IS '换算系数，必须 > 0（数据库级保证）';
CREATE INDEX idx_product_unit_product_status ON t_product_unit (product_id, status);

-- ===== TB-PRODUCT-3 t_product_contract 品种-期货/期权合约关联 =====
-- 期权与期货共用同一追溯链与同一套保额度（R-PLAN-11）。
CREATE TABLE t_product_contract (
    id                        BIGINT       IDENTITY(1,1) NOT NULL,
    product_id                BIGINT                    NOT NULL,
    instrument_id             VARCHAR(32)               NOT NULL,
    instrument_type           VARCHAR(16)               NOT NULL,
    exchange_code             VARCHAR(16)               NOT NULL,
    contract_multiplier       DECIMAL(20,6)             NOT NULL,
    expiry_date               DATE                      NOT NULL,
    underlying_instrument_id  VARCHAR(32)               NULL,
    strike_price              DECIMAL(20,6)             NULL,
    option_type               VARCHAR(8)                NULL,
    status                    VARCHAR(24)               NOT NULL,
    is_domestic_exchange      CHAR(1)      DEFAULT 'Y'  NOT NULL,
    created_by                BIGINT                    NOT NULL,
    created_time              TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by                BIGINT                    NULL,
    updated_time              TIMESTAMP                 NULL,
    row_version               INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_product_contract        PRIMARY KEY (id),
    CONSTRAINT uk_product_contract          UNIQUE (product_id, instrument_id),
    CONSTRAINT uk_contract_instrument       UNIQUE (instrument_id),
    CONSTRAINT ck_contract_instrument_type  CHECK (instrument_type IN ('FUTURES','OPTION')),
    CONSTRAINT ck_contract_option_type      CHECK (option_type IS NULL OR option_type IN ('CALL','PUT')),
    CONSTRAINT ck_contract_status           CHECK (status IN ('ENABLED','DISABLED')),
    CONSTRAINT ck_contract_domestic         CHECK (is_domestic_exchange = 'Y'),
    CONSTRAINT fk_product_contract_product  FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_product_contract                   IS '品种-期货/期权合约关联（合约代码全局唯一，合约必属唯一品种）';
COMMENT ON COLUMN t_product_contract.exchange_code     IS 'SHFE / DCE / CZCE / CFFEX / INE / GFEX（仅境内场内）';
CREATE INDEX idx_contract_product_type_status ON t_product_contract (product_id, instrument_type, status);
CREATE INDEX idx_contract_expiry              ON t_product_contract (expiry_date);

-- ===== TB-PRODUCT-4 t_product_spot_category 品种-现货品类映射 =====
-- 17 号文「期现一体」的前置：实货敞口匹配到品种的唯一入口（FR-PRODUCT-10）。
CREATE TABLE t_product_spot_category (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    spot_category_code    VARCHAR(32)               NOT NULL,
    spot_category_name    VARCHAR(128)              NULL,
    product_id            BIGINT                    NOT NULL,
    mapping_direction     VARCHAR(16)               NOT NULL,
    is_primary            CHAR(1)      DEFAULT 'N'  NOT NULL,
    status                VARCHAR(24)               NOT NULL,
    change_desc           VARCHAR(500)              NULL,
    created_by            BIGINT                    NOT NULL,
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                    NULL,
    updated_time          TIMESTAMP                 NULL,
    row_version           INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_product_spot_category   PRIMARY KEY (id),
    CONSTRAINT uk_product_spot_cat          UNIQUE (spot_category_code, product_id, mapping_direction),
    CONSTRAINT ck_product_spot_direction    CHECK (mapping_direction IN ('PURCHASE','SALES','BOTH')),
    CONSTRAINT ck_product_spot_is_primary   CHECK (is_primary IN ('Y','N')),
    CONSTRAINT ck_product_spot_status       CHECK (status IN ('ENABLED','DISABLED')),
    CONSTRAINT fk_product_spot_product      FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_product_spot_category               IS '品种-现货品类映射（期现一体唯一入口；未映射场景不落表，进未映射清单）';
COMMENT ON COLUMN t_product_spot_category.is_primary    IS '是否主映射；同一品类+方向至多一个主映射（条件唯一索引）';
-- DM-SPECIFIC: 条件唯一（部分索引）语法；非标准，换库需按 §6.1 D6 改写（MySQL 需可空列复合唯一或应用层保证）
CREATE UNIQUE INDEX uk_product_spot_primary ON t_product_spot_category (spot_category_code, mapping_direction)
    WHERE is_primary = 'Y';
CREATE INDEX idx_product_spot_product ON t_product_spot_category (product_id, status);

-- ===== TB-PRODUCT-5 t_sub_product_whitelist 子账户品种白名单 =====
-- 移出白名单 = 置 DISABLED 并保留历史；移出前须校验无未平仓持仓（服务层，FR-PRODUCT-12）。
CREATE TABLE t_sub_product_whitelist (
    id                  BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id      INT                       NOT NULL,
    product_id          BIGINT                    NOT NULL,
    category_override   VARCHAR(16)               NULL,
    status              VARCHAR(24)               NOT NULL,
    dispatch_status     VARCHAR(16)  DEFAULT 'PENDING' NOT NULL,
    dispatch_version    BIGINT                    NULL,
    disabled_reason     VARCHAR(500)              NULL,
    created_by          BIGINT                    NOT NULL,
    created_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by          BIGINT                    NULL,
    updated_time        TIMESTAMP                 NULL,
    row_version         INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_sub_product_whitelist   PRIMARY KEY (id),
    CONSTRAINT uk_sub_product_wl            UNIQUE (sub_account_id, product_id),
    CONSTRAINT ck_sub_product_wl_override   CHECK (category_override IS NULL
                                                   OR category_override IN ('COMMODITY','TRADE')),
    CONSTRAINT ck_sub_product_wl_status     CHECK (status IN ('ENABLED','DISABLED')),
    CONSTRAINT ck_sub_product_wl_dispatch   CHECK (dispatch_status IN
                                                   ('PENDING','SUCCESS','FAIL','UNCONFIRMED','NOT_REQUIRED')),
    CONSTRAINT fk_sub_product_wl_sub        FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_sub_product_wl_product    FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_sub_product_whitelist                    IS '子账户品种白名单（事前风控参数预加载热路径）';
COMMENT ON COLUMN t_sub_product_whitelist.category_override  IS '品种级分类覆盖（Q-2）；NULL = 继承子账户分类';
CREATE INDEX idx_sub_product_wl_sub_status ON t_sub_product_whitelist (sub_account_id, status);
CREATE INDEX idx_sub_product_wl_dispatch   ON t_sub_product_whitelist (dispatch_status);

-- ===== TB-PRODUCT-8 t_sub_instrument_quota 限额层②（子账户 + 合约） =====
-- 三层限额的层②（Q-16 保留，可选层）；层①/层③见 t_product_quota。
CREATE TABLE t_sub_instrument_quota (
    id                BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id    INT                       NOT NULL,
    instrument_id     VARCHAR(32)               NOT NULL,
    product_id        BIGINT                    NOT NULL,
    quota_limit       DECIMAL(20,4)             NOT NULL,
    quota_unit        VARCHAR(16)               NOT NULL,
    used_qty          DECIMAL(20,4) DEFAULT 0   NOT NULL,
    frozen_qty        DECIMAL(20,4) DEFAULT 0   NOT NULL,
    effective_from    DATE                      NOT NULL,
    effective_to      DATE                      NULL,
    dispatch_status   VARCHAR(16)  DEFAULT 'PENDING' NOT NULL,
    dispatch_version  BIGINT                    NULL,
    created_by        BIGINT                    NOT NULL,
    created_time      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by        BIGINT                    NULL,
    updated_time      TIMESTAMP                 NULL,
    row_version       INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_sub_instrument_quota    PRIMARY KEY (id),
    CONSTRAINT uk_sub_instrument_quota      UNIQUE (sub_account_id, instrument_id, effective_from),
    CONSTRAINT ck_sub_instr_quota_nonneg    CHECK (quota_limit >= 0),
    CONSTRAINT ck_sub_instr_quota_unit      CHECK (quota_unit IN ('LOT','TON')),
    CONSTRAINT ck_sub_instr_quota_dispatch  CHECK (dispatch_status IN
                                                   ('PENDING','SUCCESS','FAIL','UNCONFIRMED','NOT_REQUIRED')),
    CONSTRAINT fk_sub_instr_quota_sub       FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_sub_instr_quota_instr     FOREIGN KEY (instrument_id)
        REFERENCES t_product_contract (instrument_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_sub_instr_quota_product   FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_sub_instrument_quota           IS '限额层②（子账户+合约）；used/frozen 为 C++ 内存权威值的库内投影，不作判定（R-RISK-9）';
COMMENT ON COLUMN t_sub_instrument_quota.used_qty  IS '已占用（非权威源）；权威源为 C++ 内存';
CREATE INDEX idx_sub_instrument_quota_key     ON t_sub_instrument_quota (sub_account_id, instrument_id);
CREATE INDEX idx_sub_instrument_quota_product ON t_sub_instrument_quota (product_id);
CREATE INDEX idx_sub_instrument_quota_dispatch ON t_sub_instrument_quota (dispatch_status);

-- ===== TB-PRODUCT-6 t_product_quota 品种额度指标（层① 子账户×品种 / 层③ 账户×品种） =====
-- 严禁跨品种、跨子账户借用或串用（8 号文红线，R-PRODUCT-4）。
CREATE TABLE t_product_quota (
    id                BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id    INT          DEFAULT 0    NOT NULL,
    limit_layer       VARCHAR(24)               NOT NULL,
    product_id        BIGINT                    NOT NULL,
    quota_limit       DECIMAL(20,4)             NOT NULL,
    quota_unit        VARCHAR(16)               NOT NULL,
    used_qty          DECIMAL(20,4) DEFAULT 0   NOT NULL,
    frozen_qty        DECIMAL(20,4) DEFAULT 0   NOT NULL,
    effective_from    DATE                      NOT NULL,
    effective_to      DATE                      NULL,
    dispatch_status   VARCHAR(16)  DEFAULT 'PENDING' NOT NULL,
    dispatch_version  BIGINT                    NULL,
    created_by        BIGINT                    NOT NULL,
    created_time      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by        BIGINT                    NULL,
    updated_time      TIMESTAMP                 NULL,
    row_version       INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_product_quota          PRIMARY KEY (id),
    CONSTRAINT uk_product_quota            UNIQUE (limit_layer, sub_account_id, product_id, effective_from),
    CONSTRAINT ck_product_quota_nonneg     CHECK (quota_limit >= 0),
    CONSTRAINT ck_product_quota_layer      CHECK (limit_layer IN ('SUB_PRODUCT','ACCOUNT_PRODUCT')),
    CONSTRAINT ck_product_quota_unit       CHECK (quota_unit IN ('LOT','TON')),
    CONSTRAINT ck_product_quota_dispatch   CHECK (dispatch_status IN
                                                  ('PENDING','SUCCESS','FAIL','UNCONFIRMED','NOT_REQUIRED')),
    -- 作用域哨兵值双向联动（§4.3 G-11 / DC-020）：A 类行（层①）> 0；B 类行（层③）恒 = 0
    CONSTRAINT ck_product_quota_layer_sentinel CHECK (
         (limit_layer = 'ACCOUNT_PRODUCT' AND sub_account_id = 0)
      OR (limit_layer = 'SUB_PRODUCT'     AND sub_account_id > 0)
    ),
    CONSTRAINT fk_product_quota_sub        FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_product_quota_product    FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_product_quota             IS '品种额度指标（层① 子账户×品种 主维度 / 层③ 账户×品种 兜底）；不可跨品种跨子账户串用';
COMMENT ON COLUMN t_product_quota.used_qty    IS '已占用（汇总口径，非权威源；权威源为 C++ 内存）';
COMMENT ON COLUMN t_product_quota.limit_layer IS '层① SUB_PRODUCT（sub_account_id > 0）/ 层③ ACCOUNT_PRODUCT（哨兵 0）';
CREATE INDEX idx_product_quota_sub_product     ON t_product_quota (sub_account_id, product_id);
CREATE INDEX idx_product_quota_account_product ON t_product_quota (limit_layer, product_id);
CREATE INDEX idx_product_quota_dispatch        ON t_product_quota (dispatch_status);

-- ===== TB-PRODUCT-7 t_product_change_log 品种变更历史 =====
-- 只增不改不删；不随品种删除而消失。品种档案本体变更时 sub_account_id 可为 NULL。
CREATE TABLE t_product_change_log (
    id                BIGINT       IDENTITY(1,1) NOT NULL,
    product_id        BIGINT                    NOT NULL,
    change_type       VARCHAR(24)               NOT NULL,
    changed_field     VARCHAR(64)               NULL,
    before_value      VARCHAR(500)              NULL,
    after_value       VARCHAR(500)              NULL,
    sub_account_id    INT                       NULL,
    operator_id       BIGINT                    NOT NULL,
    change_time       TIMESTAMP                 NOT NULL,
    change_reason     VARCHAR(500)              NULL,
    created_by        BIGINT                    NOT NULL,
    created_time      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by        BIGINT                    NULL,
    updated_time      TIMESTAMP                 NULL,
    row_version       INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_product_change_log      PRIMARY KEY (id),
    CONSTRAINT ck_product_chg_type          CHECK (change_type IN ('CREATE','UPDATE','ENABLE','DISABLE')),
    CONSTRAINT ck_product_chg_update_field  CHECK (change_type <> 'UPDATE' OR changed_field IS NOT NULL),
    CONSTRAINT fk_product_chg_product       FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_product_chg_sub           FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_product_change_log               IS '品种变更历史（只增不改不删，保留前后值）';
COMMENT ON COLUMN t_product_change_log.sub_account_id IS '涉及子账户（白名单/额度变更时非空；品种档案本体变更为 NULL）';
CREATE INDEX idx_product_chg_product_time ON t_product_change_log (product_id, change_time);
CREATE INDEX idx_product_chg_type_time    ON t_product_change_log (change_type, change_time);
CREATE INDEX idx_product_chg_sub_time     ON t_product_change_log (sub_account_id, change_time);

-- =============================================================================
--  本脚本明细自检（供 DBA 执行后核对）
--    表数：8 = DATA_MODEL.md §0.6 PRODUCT（TB-PRODUCT-1~8）✅
--    逐列核对：字段/类型（含精度）/非空 与 §2.3 各表字段表逐行一致
--    索引：uk_product_code / uk_product_unit_eff / uk_product_contract / uk_contract_instrument /
--          uk_product_spot_cat / uk_product_spot_primary（条件唯一）/ uk_sub_product_wl /
--          uk_sub_instrument_quota / uk_product_quota + 全部 idx_* 与文档"索引"表一致
--    具名业务不变量 CHECK：ck_product_category_managed / ck_product_unit_factor_positive /
--          ck_sub_instr_quota_nonneg / ck_product_quota_nonneg / ck_product_quota_layer_sentinel
--    未在本脚本落地的 DB 级约束（诚实声明）：额度不可跨品种/跨子账户串用、"品种停用前无未结束计划"
--          等跨表规则由服务层保证（§4.4），不得用触发器实现（PROJECT.md §6）。
-- =============================================================================
