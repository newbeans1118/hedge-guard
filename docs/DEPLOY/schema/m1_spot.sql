-- =============================================================================
--  HedgeGuard 建表脚本 —— 模块 SPOT（现货管理），10 张
--  制品编号：docs/DEPLOY/schema/m1_spot.sql
--  依据：docs/DATA_MODEL.md v1.3.2（md5 99b647ec3e7a29b95ee86b04a58c747c）
--        §0.1~§0.5 全局约定 / §2.4 SPOT（TB-SPOT-1~10）/ §3.1 枚举 / §4.3 / §4.6.3
--  目标库：达梦 DM8（PROJECT.md §5.1）
--  执行者：DBA（应用账号仅 SELECT/INSERT/UPDATE，禁 DDL —— NFR-SEC-8）
--
--  ⚠️ 数据前置（DC-041）：`t_product` 须先插入**保留系统行 `id = 0`**（未映射哨兵值载体，
--     与 `t_sub_account(id=0)` 同构；`unmapped_flag='Y'` 的行以 `product_id = 0` 指向它）。
--     该行由 `data-init.sql` 插入（**属 T-004 制品，非本任务写域，已在 T-006/T-014 工单登记**）。
--
--  ⚠️ 执行顺序：本脚本的 t_purchase_contract / t_sales_contract / t_production_plan /
--     t_production_plan_line / t_wip_record / t_inventory_ledger / t_spot_price /
--     t_exposure 均引用 `t_product`（PRODUCT 模块）与 `t_sub_account`（CONFIG 模块），
--     故须在 m1_config_auth.sql、m1_product.sql **之后**执行。
--
--  ⚠️ 脚本纪律（DATA_MODEL.md §0.5）：无存储过程/触发器/自定义函数；无 DML；
--     DM 特性带 `-- DM-SPECIFIC:` 注释；外键一律 ON DELETE/UPDATE RESTRICT（§0.4）。
--
--  ⚠️ 未实测声明：本环境无 DM8 实例，本脚本**未在 DM8 实机执行**。
--     静态校验证据见 java 侧 M1ProductSpotPlanSchemaTest。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 模块 SPOT —— 10 张（17 号文「期现一体」的起点：实货敞口为根，单向）
-- 子账户贯穿（§4.6.3）：① NOT NULL = 9；② 可空 = 1（t_spot_change_log）；③ = 0
-- -----------------------------------------------------------------------------

-- ===== TB-SPOT-1 t_purchase_contract 采购合同 =====
-- 买入方向实货敞口主要来源；草稿可物理删除，ACTIVE 及之后只能作废（R-SPOT-9）。
CREATE TABLE t_purchase_contract (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                       NOT NULL,
    contract_no           VARCHAR(32)               NOT NULL,
    spot_category_code    VARCHAR(32)               NOT NULL,
    product_id            BIGINT                    NULL,
    supplier_name         VARCHAR(128)              NULL,
    total_qty             DECIMAL(20,4)             NOT NULL,
    executed_qty          DECIMAL(20,4) DEFAULT 0   NOT NULL,
    pricing_mode          VARCHAR(32)               NULL,
    contract_start_date   DATE                      NOT NULL,
    contract_end_date     DATE                      NOT NULL,
    exposure_direction    VARCHAR(16)  DEFAULT 'BUY' NOT NULL,
    status                VARCHAR(24)               NOT NULL,
    void_reason           VARCHAR(500)              NULL,
    closed_time           TIMESTAMP                 NULL,
    created_by            BIGINT                    NOT NULL,
    -- DM-SPECIFIC: CURRENT_TIMESTAMP 默认值；DM 特有写法，换库需按 §6.1 D2 改写
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                    NULL,
    updated_time          TIMESTAMP                 NULL,
    row_version           INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_purchase_contract       PRIMARY KEY (id),
    CONSTRAINT uk_purchase_contract_no      UNIQUE (sub_account_id, contract_no),
    CONSTRAINT ck_purchase_contract_qty     CHECK (total_qty > 0),
    CONSTRAINT ck_purchase_contract_dir     CHECK (exposure_direction IN ('BUY','SELL')),
    CONSTRAINT ck_purchase_contract_status  CHECK (status IN ('DRAFT','ACTIVE','CLOSED','VOID')),
    CONSTRAINT ck_purchase_contract_period  CHECK (contract_end_date >= contract_start_date),
    CONSTRAINT ck_purchase_contract_void    CHECK (status <> 'VOID' OR void_reason IS NOT NULL),
    CONSTRAINT fk_purchase_contract_sub     FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_purchase_contract_product FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
-- DM-SPECIFIC: 表/列注释语法
COMMENT ON TABLE  t_purchase_contract                  IS '采购合同（买入方向敞口来源；草稿可物理删除，之后仅作废）';
COMMENT ON COLUMN t_purchase_contract.sub_account_id   IS '子账户归属（创建时必填，不可静默变更，R-SPOT-2）';
COMMENT ON COLUMN t_purchase_contract.product_id       IS '映射后的品种；无映射时为 NULL 并列入未映射清单';
CREATE INDEX idx_purchase_contract_sub_status ON t_purchase_contract (sub_account_id, status);
CREATE INDEX idx_purchase_contract_product    ON t_purchase_contract (product_id, status);
CREATE INDEX idx_purchase_contract_dates      ON t_purchase_contract (contract_start_date, contract_end_date);
CREATE INDEX idx_purchase_contract_cat        ON t_purchase_contract (spot_category_code);

-- ===== TB-SPOT-2 t_sales_contract 销售合同/订单 =====
CREATE TABLE t_sales_contract (
    id                    BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id        INT                       NOT NULL,
    contract_no           VARCHAR(32)               NOT NULL,
    spot_category_code    VARCHAR(32)               NOT NULL,
    product_id            BIGINT                    NULL,
    customer_name         VARCHAR(128)              NULL,
    total_qty             DECIMAL(20,4)             NOT NULL,
    delivered_qty         DECIMAL(20,4) DEFAULT 0   NOT NULL,
    pricing_mode          VARCHAR(32)               NULL,
    contract_start_date   DATE                      NOT NULL,
    contract_end_date     DATE                      NOT NULL,
    exposure_direction    VARCHAR(16)  DEFAULT 'SELL' NOT NULL,
    has_dual_direction    CHAR(1)      DEFAULT 'N'  NOT NULL,
    buy_direction_qty     DECIMAL(20,4)             NULL,
    status                VARCHAR(24)               NOT NULL,
    void_reason           VARCHAR(500)              NULL,
    closed_time           TIMESTAMP                 NULL,
    created_by            BIGINT                    NOT NULL,
    created_time          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by            BIGINT                    NULL,
    updated_time          TIMESTAMP                 NULL,
    row_version           INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_sales_contract          PRIMARY KEY (id),
    CONSTRAINT uk_sales_contract_no         UNIQUE (sub_account_id, contract_no),
    CONSTRAINT ck_sales_contract_qty        CHECK (total_qty > 0),
    CONSTRAINT ck_sales_contract_dir        CHECK (exposure_direction IN ('BUY','SELL')),
    CONSTRAINT ck_sales_contract_dual       CHECK (has_dual_direction <> 'Y' OR buy_direction_qty IS NOT NULL),
    CONSTRAINT ck_sales_contract_dual_flag  CHECK (has_dual_direction IN ('Y','N')),
    CONSTRAINT ck_sales_contract_status     CHECK (status IN ('DRAFT','ACTIVE','CLOSED','VOID')),
    CONSTRAINT ck_sales_contract_period     CHECK (contract_end_date >= contract_start_date),
    CONSTRAINT ck_sales_contract_void       CHECK (status <> 'VOID' OR void_reason IS NOT NULL),
    CONSTRAINT fk_sales_contract_sub        FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_sales_contract_product    FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_sales_contract                  IS '销售合同/订单（卖出方向敞口来源）';
COMMENT ON COLUMN t_sales_contract.has_dual_direction IS 'Y 时须在 t_exposure 生成两条方向不同的敞口（FR-SPOT-5 验收 3）';
CREATE INDEX idx_sales_contract_sub_status ON t_sales_contract (sub_account_id, status);
CREATE INDEX idx_sales_contract_product    ON t_sales_contract (product_id, status);
CREATE INDEX idx_sales_contract_dates      ON t_sales_contract (contract_start_date, contract_end_date);

-- ===== TB-SPOT-3 t_production_plan 生产计划 =====
CREATE TABLE t_production_plan (
    id                  BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id      INT                       NOT NULL,
    plan_no             VARCHAR(32)               NOT NULL,
    plan_period_start   DATE                      NOT NULL,
    plan_period_end     DATE                      NOT NULL,
    status              VARCHAR(24)               NOT NULL,
    overlap_flag        CHAR(1)      DEFAULT 'N'  NOT NULL,
    closed_time         TIMESTAMP                 NULL,
    created_by          BIGINT                    NOT NULL,
    created_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by          BIGINT                    NULL,
    updated_time        TIMESTAMP                 NULL,
    row_version         INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_production_plan       PRIMARY KEY (id),
    CONSTRAINT uk_prod_plan_no            UNIQUE (sub_account_id, plan_no),
    CONSTRAINT ck_prod_plan_period        CHECK (plan_period_end >= plan_period_start),
    CONSTRAINT ck_prod_plan_status        CHECK (status IN ('SCHEDULED','ADJUSTING','CLOSED')),
    CONSTRAINT ck_prod_plan_overlap_flag  CHECK (overlap_flag IN ('Y','N')),
    CONSTRAINT fk_prod_plan_sub           FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_production_plan              IS '生产计划（计划期不得为空；关闭后不再贡献新敞口）';
COMMENT ON COLUMN t_production_plan.overlap_flag IS '计划期与既有计划重叠提示（不阻断，FR-SPOT-8 验收 3）';
CREATE INDEX idx_prod_plan_sub_status ON t_production_plan (sub_account_id, status);
CREATE INDEX idx_prod_plan_period     ON t_production_plan (plan_period_start, plan_period_end);

-- ===== TB-SPOT-4 t_production_plan_line 生产计划行 =====
-- 冗余 sub_account_id：满足「不得靠关联表推导子账户」（INDEX.md §2.1）且可直接求和。
CREATE TABLE t_production_plan_line (
    id                   BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id       INT                       NOT NULL,
    production_plan_id   BIGINT                    NOT NULL,
    line_type            VARCHAR(16)               NOT NULL,
    product_id           BIGINT                    NOT NULL,
    quantity             DECIMAL(20,4)             NOT NULL,
    unit                 VARCHAR(16)               NOT NULL,
    exposure_direction   VARCHAR(16)               NOT NULL,
    created_by           BIGINT                    NOT NULL,
    created_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by           BIGINT                    NULL,
    updated_time         TIMESTAMP                 NULL,
    row_version          INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_production_plan_line    PRIMARY KEY (id),
    CONSTRAINT ck_prod_plan_line_type       CHECK (line_type IN ('FINISHED','RAW_MATERIAL')),
    CONSTRAINT ck_prod_plan_line_dir        CHECK (exposure_direction IN ('BUY','SELL')),
    CONSTRAINT ck_prod_plan_line_qty        CHECK (quantity > 0),
    CONSTRAINT fk_prod_plan_line_sub        FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_prod_plan_line_plan       FOREIGN KEY (production_plan_id)
        REFERENCES t_production_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_prod_plan_line_product    FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_production_plan_line                     IS '生产计划行（产成品 FINISHED → SELL；原料 RAW_MATERIAL → BUY）';
COMMENT ON COLUMN t_production_plan_line.sub_account_id       IS '冗余自计划头，支持不 join 直接按子账户聚合';
CREATE INDEX idx_prod_plan_line_plan        ON t_production_plan_line (production_plan_id, line_type);
CREATE INDEX idx_prod_plan_line_sub_product ON t_production_plan_line (sub_account_id, product_id);

-- ===== TB-SPOT-5 t_wip_record 在制品记录 =====
CREATE TABLE t_wip_record (
    id                   BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id       INT                       NOT NULL,
    product_id           BIGINT                    NOT NULL,
    production_plan_id   BIGINT                    NULL,
    wip_qty              DECIMAL(20,4)             NOT NULL,
    completion_ratio     DECIMAL(9,6)              NOT NULL,
    exposure_direction   VARCHAR(16)               NOT NULL,
    record_date          DATE                      NOT NULL,
    unit                 VARCHAR(16)               NOT NULL,
    created_by           BIGINT                    NOT NULL,
    created_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by           BIGINT                    NULL,
    updated_time         TIMESTAMP                 NULL,
    row_version          INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_wip_record           PRIMARY KEY (id),
    CONSTRAINT ck_wip_qty_nonneg         CHECK (wip_qty >= 0),
    CONSTRAINT ck_wip_completion_ratio   CHECK (completion_ratio >= 0 AND completion_ratio <= 1),
    CONSTRAINT ck_wip_direction          CHECK (exposure_direction IN ('BUY','SELL')),
    CONSTRAINT fk_wip_sub                FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_wip_product            FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_wip_plan               FOREIGN KEY (production_plan_id)
        REFERENCES t_production_plan (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_wip_record                    IS '在制品记录（敞口 = wip_qty × completion_ratio，R-SPOT-4）';
COMMENT ON COLUMN t_wip_record.completion_ratio   IS '完工比例（0–1），数据库级判据 R-SPOT-4';
CREATE INDEX idx_wip_sub_product_date ON t_wip_record (sub_account_id, product_id, record_date);
CREATE INDEX idx_wip_plan             ON t_wip_record (production_plan_id);

-- ===== TB-SPOT-6 t_inventory_ledger 库存台账 =====
-- 按记录日期形成序列，不覆盖历史；负值须二次确认（结构强制）。
CREATE TABLE t_inventory_ledger (
    id                   BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id       INT                       NOT NULL,
    product_id           BIGINT                    NOT NULL,
    inventory_type       VARCHAR(24)               NOT NULL,
    quantity             DECIMAL(20,4)             NOT NULL,
    negative_confirmed   CHAR(1)      DEFAULT 'N'  NOT NULL,
    unit                 VARCHAR(16)               NOT NULL,
    valuation            DECIMAL(20,6)             NULL,
    record_date          DATE                      NOT NULL,
    exposure_direction   VARCHAR(16)               NOT NULL,
    remark               VARCHAR(500)              NULL,
    created_by           BIGINT                    NOT NULL,
    created_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by           BIGINT                    NULL,
    updated_time         TIMESTAMP                 NULL,
    row_version          INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_inventory_ledger        PRIMARY KEY (id),
    CONSTRAINT uk_inventory_sub_product_type_date UNIQUE
        (sub_account_id, product_id, inventory_type, record_date),
    CONSTRAINT ck_inventory_type            CHECK (inventory_type IN ('RAW_MATERIAL','FINISHED','WIP')),
    CONSTRAINT ck_inventory_negative_confirmed CHECK (quantity >= 0 OR negative_confirmed = 'Y'),
    CONSTRAINT ck_inventory_negative_flag   CHECK (negative_confirmed IN ('Y','N')),
    CONSTRAINT ck_inventory_direction       CHECK (exposure_direction IN ('BUY','SELL')),
    CONSTRAINT fk_inventory_sub             FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_inventory_product         FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_inventory_ledger                       IS '库存台账（序列不覆盖历史；未确认的负库存写不进库）';
COMMENT ON COLUMN t_inventory_ledger.negative_confirmed    IS 'quantity < 0 时必须为 Y（ck_inventory_negative_confirmed）';
CREATE INDEX idx_inventory_sub_date     ON t_inventory_ledger (sub_account_id, record_date);
CREATE INDEX idx_inventory_product_date ON t_inventory_ledger (product_id, record_date);

-- ===== TB-SPOT-7 t_spot_price 现货价 =====
-- 口径 A（CH-003/RSK-05 已闭环）：全账户共享；唯一口径 = 品种 + 日期 + is_shared='Y'；
-- sub_account_id 仅记录「录入归属」，不是业务归属维度；单价、不参与加总。
CREATE TABLE t_spot_price (
    id                   BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id       INT                       NOT NULL,
    product_id           BIGINT                    NOT NULL,
    price_date           DATE                      NOT NULL,
    spot_price           DECIMAL(20,6)             NOT NULL,
    source_type          VARCHAR(24)               NOT NULL,
    source_desc          VARCHAR(500)              NULL,
    import_file_name     VARCHAR(256)              NULL,
    operator_id          BIGINT                    NOT NULL,
    entry_time           TIMESTAMP                 NOT NULL,
    is_shared            CHAR(1)      DEFAULT 'Y'  NOT NULL,
    overwritten_price    DECIMAL(20,6)             NULL,
    overwrite_confirmed  CHAR(1)      DEFAULT 'N'  NOT NULL,
    price_unit           VARCHAR(16)               NOT NULL,
    created_by           BIGINT                    NOT NULL,
    created_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by           BIGINT                    NULL,
    updated_time         TIMESTAMP                 NULL,
    row_version          INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_spot_price            PRIMARY KEY (id),
    CONSTRAINT uk_spot_price_global        UNIQUE (product_id, price_date, is_shared),
    CONSTRAINT ck_spot_price_positive      CHECK (spot_price > 0),
    CONSTRAINT ck_spot_price_source        CHECK (source_type IN ('MANUAL','BATCH_PASTE','CSV_IMPORT')),
    CONSTRAINT ck_spot_price_shared        CHECK (is_shared IN ('Y','N')),
    CONSTRAINT ck_spot_price_overwrite     CHECK (overwrite_confirmed IN ('Y','N')),
    CONSTRAINT fk_spot_price_sub           FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_spot_price_product       FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_spot_price                   IS '现货价（GLOBAL 口径全账户共享；唯一外部输入渠道，系统不抓取不订阅）';
COMMENT ON COLUMN t_spot_price.sub_account_id    IS '录入归属子账户（显式携带，非业务归属维度）';
COMMENT ON COLUMN t_spot_price.overwritten_price IS '被覆盖的原值（覆盖须留痕，不得静默覆盖，R-SPOT-3）';
CREATE INDEX idx_spot_price_sub_product_date ON t_spot_price (sub_account_id, product_id, price_date);
CREATE INDEX idx_spot_price_product_date     ON t_spot_price (product_id, price_date);
CREATE INDEX idx_spot_price_source           ON t_spot_price (source_type, entry_time);

-- ===== TB-SPOT-8 t_exposure 实货敞口（根实体） =====
-- PLAN 的唯一输入且方向单向；买卖不抵销（direction 为唯一键组成部分）。
CREATE TABLE t_exposure (
    id                       BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id           INT                       NOT NULL,
    -- DC-041：恒非空；未映射（unmapped_flag='Y'）时取**品种哨兵值 0**（指向 t_product 保留系统行 id=0），
    -- 故本列始终在引用完整性校验范围内，且 uk_exposure_key 全列非空（唯一性不依赖 NULL 语义）。
    product_id               BIGINT                    NOT NULL,
    -- DC-041 补列（**双态**，CH-008 裁定）：未映射行（unmapped_flag='Y', product_id=0）填**真实品类**
    -- （各品类各占一行）；已映射行（unmapped_flag='N', product_id>0）填**固定占位常量 '__MAPPED__'**
    -- ⇒ 同一品种来自不同品类的数据必须合并为一条（FR-SPOT-14 验收 1），唯一性等价于 5 列。
    spot_category_code       VARCHAR(32)               NOT NULL,
    direction                VARCHAR(16)               NOT NULL,
    calc_mode                VARCHAR(32)               NOT NULL,
    contract_unexecuted_qty  DECIMAL(20,4) DEFAULT 0   NOT NULL,
    inventory_qty            DECIMAL(20,4) DEFAULT 0   NOT NULL,
    production_plan_qty      DECIMAL(20,4) DEFAULT 0   NOT NULL,
    components_enabled       VARCHAR(128)              NOT NULL,
    snapshot_id              VARCHAR(64)               NOT NULL,
    snapshot_time            TIMESTAMP                 NOT NULL,
    combined_components      VARCHAR(128)              NULL,
    as_of_date               DATE                      NOT NULL,
    calc_time                TIMESTAMP                 NOT NULL,
    exposure_qty             DECIMAL(20,4)             NOT NULL,
    unit                     VARCHAR(16)               NOT NULL,
    exposure_amount          DECIMAL(20,4)             NULL,
    price_missing_flag       CHAR(1)      DEFAULT 'N'  NOT NULL,
    price_as_of              DATE                      NULL,
    non_current_price_flag   CHAR(1)      DEFAULT 'N'  NOT NULL,
    snapshot_as_of           TIMESTAMP                 NULL,
    covered_qty              DECIMAL(20,4) DEFAULT 0   NOT NULL,
    status                   VARCHAR(24)               NOT NULL,
    unmapped_flag            CHAR(1)      DEFAULT 'N'  NOT NULL,
    row_version              INT          DEFAULT 0    NOT NULL,
    created_by               BIGINT                    NOT NULL,
    created_time             TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by               BIGINT                    NULL,
    updated_time             TIMESTAMP                 NULL,
    CONSTRAINT pk_t_exposure                PRIMARY KEY (id),
    CONSTRAINT uk_exposure_key              UNIQUE
        (sub_account_id, product_id, spot_category_code, direction, calc_mode, as_of_date),
    CONSTRAINT uk_exposure_snapshot         UNIQUE (snapshot_id),
    CONSTRAINT ck_exposure_direction        CHECK (direction IN ('BUY','SELL')),
    CONSTRAINT ck_exposure_calc_mode        CHECK (calc_mode IN
        ('COMBINED','CONTRACT_UNEXECUTED','INVENTORY_HELD','PRODUCTION_PLAN')),
    CONSTRAINT ck_exposure_qty_nonneg       CHECK (exposure_qty >= 0),
    CONSTRAINT ck_exposure_status           CHECK (status IN
        ('PENDING_HEDGE','PARTIALLY_COVERED','COVERED','CLOSED')),
    CONSTRAINT ck_exposure_price_missing    CHECK (price_missing_flag IN ('Y','N')),
    CONSTRAINT ck_exposure_amount_missing   CHECK (price_missing_flag <> 'Y' OR exposure_amount IS NULL),
    CONSTRAINT ck_exposure_non_current      CHECK (non_current_price_flag IN ('Y','N')),
    CONSTRAINT ck_exposure_unmapped         CHECK (unmapped_flag IN ('Y','N')),
    -- 结构不变量①（DC-041）：与 TB-TRADE-4 ck_trade_reason_related_or_missing 同类——
    -- 「标记列 ⇔ 取值列」双向联动；本表用**未映射哨兵值 0** 承载例外态（不用 NULL，故唯一键全列非空）
    CONSTRAINT ck_exposure_product_unmapped CHECK (
         (unmapped_flag = 'Y' AND product_id = 0)
      OR (unmapped_flag = 'N' AND product_id > 0)
    ),
    -- 结构不变量②（CH-008 / DC-041 第 2 轮）：品类列双态——已映射行恒占位常量，未映射行恒真实品类
    -- （DATA_MODEL.md 分两条 ALTER TABLE，此处按本脚本 CONSTRAINT 列表风格内联，语义逐字一致）
    CONSTRAINT ck_exposure_spot_category_scope CHECK (
             (unmapped_flag = 'N' AND spot_category_code = '__MAPPED__')
          OR (unmapped_flag = 'Y' AND spot_category_code <> '__MAPPED__')
    ),
    CONSTRAINT fk_exposure_sub              FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_exposure_product          FOREIGN KEY (product_id)
        REFERENCES t_product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_exposure                          IS '实货敞口（根实体）；三分量与合计同事务计算，Σ分量 ≠ 合计时写入侧拒绝（Q-17）';
COMMENT ON COLUMN t_exposure.product_id                IS '品种；未映射时为哨兵值 0（指向 t_product 保留系统行 id=0，DC-041）';
COMMENT ON COLUMN t_exposure.spot_category_code        IS '现货品类编码（双态，CH-008）：未映射行=真实品类（各品类各占一行）；已映射行=占位常量 __MAPPED__';
COMMENT ON COLUMN t_exposure.components_enabled       IS '本快照启用的分量集合（被关闭的分量列恒为 0）';
COMMENT ON COLUMN t_exposure.snapshot_time            IS '快照时点（Q-3：校验记录须标注所用快照时点）';
COMMENT ON COLUMN t_exposure.price_as_of              IS '估值所用现货价实际时点（Q-12）；沿用最近一期价时必填';
COMMENT ON COLUMN t_exposure.snapshot_as_of           IS '三分量沿用最近一期的分量时点（Q-17）；与"非当期分量"标记成对出现';
CREATE INDEX idx_exposure_sub_status        ON t_exposure (sub_account_id, status);
CREATE INDEX idx_exposure_sub_product_dir   ON t_exposure (sub_account_id, product_id, direction);
CREATE INDEX idx_exposure_asof              ON t_exposure (as_of_date);

-- ===== TB-SPOT-9 t_exposure_detail 敞口构成明细 =====
-- 「敞口可追溯到原始单据」的物理载体；多态外键（source_type + source_id）不用 DB 外键（§4.5 唯一软外键处）。
CREATE TABLE t_exposure_detail (
    id                  BIGINT       IDENTITY(1,1) NOT NULL,
    sub_account_id      INT                       NOT NULL,
    exposure_id         BIGINT                    NOT NULL,
    component_type      VARCHAR(24)               NOT NULL,
    source_type         VARCHAR(24)               NOT NULL,
    source_id           BIGINT                    NOT NULL,
    source_no           VARCHAR(32)               NULL,
    contributed_qty     DECIMAL(20,4)             NOT NULL,
    unit                VARCHAR(16)               NOT NULL,
    direction           VARCHAR(16)               NOT NULL,
    calc_as_of_date     DATE                      NOT NULL,
    created_by          BIGINT                    NOT NULL,
    created_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by          BIGINT                    NULL,
    updated_time        TIMESTAMP                 NULL,
    row_version         INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_exposure_detail      PRIMARY KEY (id),
    CONSTRAINT ck_exposure_detail_component CHECK (component_type IN
        ('CONTRACT_UNEXECUTED','INVENTORY','PRODUCTION_PLAN')),
    CONSTRAINT ck_exposure_detail_source CHECK (source_type IN
        ('PURCHASE_CONTRACT','SALES_CONTRACT','PRODUCTION_PLAN','WIP_RECORD','INVENTORY_LEDGER')),
    CONSTRAINT ck_exposure_detail_dir     CHECK (direction IN ('BUY','SELL')),
    CONSTRAINT fk_exposure_detail_sub     FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_exposure_detail_exposure FOREIGN KEY (exposure_id)
        REFERENCES t_exposure (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_exposure_detail                     IS '敞口构成明细（SUM(contributed_qty) = t_exposure.exposure_qty；AUDIT 追溯链第一跳）';
COMMENT ON COLUMN t_exposure_detail.source_id           IS '来源单据 ID（多态外键，按 source_type 解释；由服务层校验）';
CREATE INDEX idx_exposure_detail_exposure  ON t_exposure_detail (exposure_id);
CREATE INDEX idx_exposure_detail_source    ON t_exposure_detail (source_type, source_id);
CREATE INDEX idx_exposure_detail_sub       ON t_exposure_detail (sub_account_id, calc_as_of_date);
CREATE INDEX idx_exposure_detail_component ON t_exposure_detail (exposure_id, component_type);

-- ===== TB-SPOT-10 t_spot_change_log 现货单据与归属变更留痕 =====
CREATE TABLE t_spot_change_log (
    id                   BIGINT       IDENTITY(1,1) NOT NULL,
    source_type          VARCHAR(24)               NOT NULL,
    source_id            BIGINT                    NOT NULL,
    change_type          VARCHAR(24)               NOT NULL,
    sub_account_before   INT                       NULL,
    sub_account_after    INT                       NULL,
    changed_field        VARCHAR(64)               NULL,
    before_value         VARCHAR(500)              NULL,
    after_value          VARCHAR(500)              NULL,
    sub_account_id       INT                       NULL,
    operator_id          BIGINT                    NOT NULL,
    change_reason        VARCHAR(500)              NULL,
    change_time          TIMESTAMP                 NOT NULL,
    recalc_triggered     CHAR(1)      DEFAULT 'N'  NOT NULL,
    created_by           BIGINT                    NOT NULL,
    created_time         TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by           BIGINT                    NULL,
    updated_time         TIMESTAMP                 NULL,
    row_version          INT          DEFAULT 0    NOT NULL,
    CONSTRAINT pk_t_spot_change_log    PRIMARY KEY (id),
    CONSTRAINT ck_spot_chg_source      CHECK (source_type IN
        ('PURCHASE_CONTRACT','SALES_CONTRACT','PRODUCTION_PLAN','WIP_RECORD','INVENTORY_LEDGER')),
    CONSTRAINT ck_spot_chg_type        CHECK (change_type IN
        ('CREATE','UPDATE','VOID','CLOSE','SUB_ACCOUNT_CHANGE','PRICE_OVERWRITE','COMPONENT_RECALC')),
    CONSTRAINT ck_spot_chg_recalc      CHECK (recalc_triggered IN ('Y','N')),
    CONSTRAINT ck_spot_chg_sub_change  CHECK (change_type <> 'SUB_ACCOUNT_CHANGE'
                                              OR (sub_account_before IS NOT NULL AND sub_account_after IS NOT NULL)),
    CONSTRAINT fk_spot_chg_sub         FOREIGN KEY (sub_account_id)
        REFERENCES t_sub_account (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE  t_spot_change_log                  IS '现货单据与归属变更留痕（COMPONENT_RECALC = 展示侧重算不回写，DC-033）';
COMMENT ON COLUMN t_spot_change_log.sub_account_id   IS '当前子账户；归属变更取 sub_account_after；品种档案本体变更为 NULL';
CREATE INDEX idx_spot_chg_source_time ON t_spot_change_log (source_type, source_id, change_time);
CREATE INDEX idx_spot_chg_sub_time    ON t_spot_change_log (sub_account_id, change_time);
CREATE INDEX idx_spot_chg_type_time   ON t_spot_change_log (change_type, change_time);

-- =============================================================================
--  本脚本明细自检
--    表数：10 = DATA_MODEL.md §0.6 SPOT（TB-SPOT-1~10）✅
--    逐列核对：字段/类型（含精度）/非空 与 §2.4 各表字段表逐行一致
--    索引：uk_purchase_contract_no / uk_sales_contract_no / uk_prod_plan_no /
--          uk_inventory_sub_product_type_date / uk_spot_price_global /
--          uk_exposure_key / uk_exposure_snapshot + 全部 idx_* 与文档一致
--    具名业务不变量 CHECK：ck_inventory_negative_confirmed / ck_sales_contract_dual /
--          ck_prod_plan_period / ck_wip_completion_ratio / ck_spot_price_positive
--    未落 DB 的规则（服务层，§4.4）：日期不得晚于当日、敞口状态由引用关系派生、
--          「未映射清单」、计划数量 ≤ 敞口未覆盖数量 —— 不得用触发器实现。
-- =============================================================================
