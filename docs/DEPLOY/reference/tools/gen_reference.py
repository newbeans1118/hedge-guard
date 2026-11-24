#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HedgeGuard 参考数据校验器 / 装载脚本生成器（stdlib only，零新增依赖）。

制品：docs/DEPLOY/reference/tools/gen_reference.py
依据：docs/DEPLOY/reference/README.md / manifest.json（单一事实源）
      docs/DEPLOY/schema/*.sql（DDL：列 / 类型 / NOT NULL / CHECK / FK 的机读真值）
      docs/DEPLOY/schema/data-init.sql（③④ 重叠段只读一致性核对）

用法：
    python3 docs/DEPLOY/reference/tools/gen_reference.py --check [--groups master,demo]
    python3 docs/DEPLOY/reference/tools/gen_reference.py --target dm8|sqlite|java-fixture \
        [--groups master,demo] [--out PATH]

设计要点：
  * TSV 表头 = DDL 列名扣除 injected_columns 后的子集（相对顺序同 DDL）；
    空串 = NULL；id 留空 = 目标环境自增，显式 0 = 保留系统行。
  * FK 列为「父表自然键值」，SQL 目标用标量子查询在装载时解析为实际 id，
    Java 夹具目标用确定性分配的 id 直接解析 —— 两种目标都不依赖目标环境
    预先分配的 IDENTITY 值。
  * 幂等：每条 INSERT 带 WHERE NOT EXISTS（按 natural_key 判重）。
  * 零落盘：password_hash 不来自 TSV，由生成器注入非凭证明文标记。
  * 仅 INSERT，无存储过程 / 触发器 / 自定义函数（PROJECT.md §6）。
"""

import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REF = os.path.dirname(HERE)                      # docs/DEPLOY/reference
ROOT = os.path.abspath(os.path.join(REF, "..", "..", ".."))
SCHEMA_DIR = os.path.join(ROOT, "docs", "DEPLOY", "schema")
DATA_INIT = os.path.join(SCHEMA_DIR, "data-init.sql")
MANIFEST = os.path.join(REF, "manifest.json")
MASTER_DIR = os.path.join(REF, "master")
DEMO_DIR = os.path.join(REF, "demo")
DEFAULT_OUT = os.path.join(REF, "out")

PENDING_PASSWORD = "__PENDING_SETUP__"          # 非凭证明文标记（零落盘）
FIXED_TS = "2026-11-20 00:00:00"               # java-fixture 的固定审计时间戳
NUMERIC_TYPES = {"INT", "INTEGER", "BIGINT", "SMALLINT", "DECIMAL", "NUMERIC",
                 "FLOAT", "DOUBLE", "REAL"}
DATE_TYPES = {"DATE"}
TS_TYPES = {"TIMESTAMP", "DATETIME"}

# --check 中的额外组合式不变量（DDL CHECK 表达式无法用通用 IN/= 解析器覆盖的部分）
# 键为表名，值为 (说明, 判定函数)，判定函数接收该表的一行 dict，返回 None 或错误说明。
COMPOSITE_CHECKS = {}


def _cc(table, desc):
    def deco(fn):
        COMPOSITE_CHECKS[table] = (desc, fn)
        return fn
    return deco


@_cc("t_role", "SYS_ADMIN 不得有交易权")
def _(r):
    return None if r.get("is_business_role") == "Y" or r.get("has_trading_operation") == "N" \
        else "ck_role_sysadmin_no_trading 违例"


@_cc("t_product", "分类管理标记要求 category 非空")
def _(r):
    return None if r.get("category_managed") != "Y" or r.get("category") else \
        "ck_product_category_managed 违例"


@_cc("t_report_period_config", "强制报告不得关闭")
def _(r):
    return None if r.get("is_mandatory") == "N" or r.get("is_enabled") == "Y" else \
        "ck_report_period_mandatory_enabled 违例"


@_cc("t_config_item", "敏感项 config_value 必须为空")
def _(r):
    return None if r.get("is_sensitive") == "N" or not r.get("config_value") else \
        "ck_config_item_sensitive 违例"


@_cc("t_position_group", "is_conflict_enforced 恒 Y")
def _(r):
    return None if r.get("is_conflict_enforced") == "Y" else "conflict_enforced 违例"


@_cc("t_product_unit", "换算系数 > 0")
def _(r):
    return None if _num(r.get("convert_factor")) > 0 else "convert_factor 非正"


@_cc("t_product_quota", "层③必须取哨兵 0，层①必须取业务子账户")
def _(r):
    ok = (r.get("limit_layer") == "ACCOUNT_PRODUCT" and r.get("sub_account_id") == "__ALL_ACCOUNT__") \
        or (r.get("limit_layer") == "SUB_PRODUCT" and r.get("sub_account_id") != "__ALL_ACCOUNT__")
    return None if ok else "limit_layer / sub_account_id 哨兵不匹配"


@_cc("t_annual_scale", "全账户口径必须取哨兵 0")
def _(r):
    ok = (r.get("scope_type") == "TOTAL_ACCOUNT" and r.get("sub_account_id") == "__ALL_ACCOUNT__") \
        or (r.get("scope_type") == "SUB_ACCOUNT" and r.get("sub_account_id") != "__ALL_ACCOUNT__")
    return None if ok else "scope_type / sub_account_id 哨兵不匹配"


@_cc("t_exposure", "已映射行品类列恒 __MAPPED__ 且 product_id>0")
def _(r):
    if r.get("unmapped_flag") == "N" and (r.get("spot_category_code") != "__MAPPED__"
                                          or r.get("product_id") == "__UNMAPPED__"):
        return "ck_exposure_spot_category_scope / product_unmapped 违例"
    if _num(r.get("exposure_qty")) < 0:
        return "exposure_qty 为负"
    if r.get("price_missing_flag") == "Y" and r.get("exposure_amount"):
        return "price_missing_flag=Y 但 exposure_amount 非空"
    return None


@_cc("t_hedge_plan", "占用额度状态 / 比率 / 期限 / 数量")
def _(r):
    if r.get("quota_occupied") == "Y" and r.get("status") not in ("APPROVED", "FROZEN"):
        return "quota_occupied=Y 但状态不符"
    if not (0 < _num(r.get("hedge_ratio")) <= 1):
        return "hedge_ratio 越界"
    if _num(r.get("plan_qty")) <= 0:
        return "plan_qty<=0"
    if r.get("term_end_date") and r.get("term_start_date") and \
            r["term_end_date"] < r["term_start_date"]:
        return "期限倒挂"
    if r.get("status") == "FROZEN" and not r.get("freeze_reason"):
        return "FROZEN 缺 freeze_reason"
    if r.get("status") == "TERMINATED" and not r.get("terminate_reason"):
        return "TERMINATED 缺 terminate_reason"
    return None


@_cc("t_hedge_plan_exposure", "covered_qty>0 / UNBOUND 须有解绑时间")
def _(r):
    if _num(r.get("covered_qty")) <= 0:
        return "covered_qty<=0"
    if r.get("status") == "UNBOUND" and not r.get("unbind_time"):
        return "UNBOUND 缺 unbind_time"
    return None


@_cc("t_hedge_ratio_calc", "非 SUCCESS 时 output_ratio 必须为 0")
def _(r):
    if not (0 <= _num(r.get("output_ratio")) <= 1):
        return "output_ratio 越界"
    if r.get("result_code") != "SUCCESS" and _num(r.get("output_ratio")) != 0:
        return "非 SUCCESS 但 output_ratio!=0"
    if r.get("sample_start_date") and r.get("sample_end_date") and \
            r["sample_end_date"] < r["sample_start_date"]:
        return "样本期倒挂"
    return None


@_cc("t_hedge_doc", "套期文档必须标注待外部复核")
def _(r):
    return None if r.get("pending_external_review") == "Y" else "缺 pending_external_review=Y"


@_cc("t_plan_approval", "不得自审自批 / REJECT 须有理由")
def _(r):
    if r.get("approver_id") and r.get("approver_id") == r.get("applicant_id"):
        return "approver_id == applicant_id（自审自批）"
    if r.get("node_type") == "REJECT" and not r.get("reject_reason"):
        return "REJECT 缺 reject_reason"
    return None


@_cc("t_plan_state_log", "非 NONE 的额度动作须有 quota_qty")
def _(r):
    return None if r.get("quota_action") == "NONE" or r.get("quota_qty") else \
        "quota_action 非 NONE 但缺 quota_qty"


@_cc("t_trade_reason", "关联对象或缺失标记二选一")
def _(r):
    ok = (r.get("related_plan_id") and r.get("related_exposure_id")) \
        or r.get("related_object_missing_flag") == "Y"
    return None if ok else "关联对象缺失但标记 N"


@_cc("t_position", "可用/冻结不得超过持仓量")
def _(r):
    q = _num(r.get("position_qty"))
    if q < 0 or _num(r.get("available_qty")) > q or _num(r.get("frozen_qty")) > q:
        return "available_qty / frozen_qty > position_qty"
    return None


@_cc("t_voucher", "凭证必须标注待外部复核")
def _(r):
    return None if r.get("pending_external_review") == "Y" else "缺 pending_external_review=Y"


@_cc("t_voucher_line", "分录金额 > 0")
def _(r):
    return None if _num(r.get("amount")) > 0 else "amount<=0"


@_cc("t_authorization", "quota_limit > 0")
def _(r):
    return None if _num(r.get("quota_limit")) > 0 else "quota_limit<=0"


@_cc("t_spot_price", "现货价 > 0")
def _(r):
    return None if _num(r.get("spot_price")) > 0 else "spot_price<=0"


@_cc("t_trade_order", "volume > 0")
def _(r):
    return None if _num(r.get("volume")) > 0 else "volume<=0"


@_cc("t_trade_fill", "fill_volume > 0")
def _(r):
    return None if _num(r.get("fill_volume")) > 0 else "fill_volume<=0"


@_cc("t_wip_record", "在制品数量 / 完工比例")
def _(r):
    return None if _num(r.get("wip_qty")) >= 0 and 0 <= _num(r.get("completion_ratio")) <= 1 \
        else "wip_qty / completion_ratio 越界"


@_cc("t_inventory_ledger", "负库存须确认")
def _(r):
    return None if _num(r.get("quantity")) >= 0 or r.get("negative_confirmed") == "Y" \
        else "负库存未确认"


@_cc("t_purchase_contract", "合同数量 / 期限 / 作废原因")
def _(r):
    return _contract(r)


@_cc("t_sales_contract", "合同数量 / 期限 / 作废原因 / 双向数量")
def _(r):
    e = _contract(r)
    if e:
        return e
    if r.get("has_dual_direction") == "Y" and not r.get("buy_direction_qty"):
        return "双向合同缺 buy_direction_qty"
    return None


@_cc("t_production_plan", "计划期间不倒挂")
def _(r):
    return None if r.get("plan_period_end") >= r.get("plan_period_start") else "期间倒挂"


@_cc("t_production_plan_line", "quantity > 0")
def _(r):
    return None if _num(r.get("quantity")) > 0 else "quantity<=0"


def _contract(r):
    if _num(r.get("total_qty")) <= 0:
        return "total_qty<=0"
    if r.get("contract_end_date") < r.get("contract_start_date"):
        return "合同期倒挂"
    if r.get("status") == "VOID" and not r.get("void_reason"):
        return "VOID 缺 void_reason"
    return None


def _num(v):
    """把 TSV 值转为 float；空串视为 0，用于数值型组合不变量。"""
    if v is None or v == "":
        return 0.0
    try:
        return float(v)
    except ValueError:
        return 0.0


# ---------------------------------------------------------------------------
# DDL 解析
# ---------------------------------------------------------------------------

def parse_ddl():
    """返回 table -> {cols:[(name, base_type, full_type)], order, notnull, checks, fks, uniques}。"""
    out = {}
    for path in sorted(_glob_sql()):
        text = re.sub(r"--[^\n]*", "", _read(path))
        for m in re.finditer(r"CREATE\s+TABLE\s+(\w+)\s*\((.*?)\);", text, re.S | re.I):
            table, body = m.group(1), m.group(2)
            parts = _split_top(body)
            cols, notnull, checks, fks, uniques = [], {}, [], [], []
            for part in parts:
                s = part.strip()
                cm = re.match(r"[`\"\[]?(\w+)[`\"\]]?\s+(.*)", s, re.S)
                if cm and not re.match(r"(CONSTRAINT|PRIMARY|FOREIGN|UNIQUE|CHECK|KEY)\b", s, re.I):
                    name, rest = cm.group(1), cm.group(2)
                    tm = re.match(r"(\w+(?:\s*\([^)]*\))?)", rest)
                    full = tm.group(1).replace(" ", "") if tm else ""
                    base = re.match(r"(\w+)", full).group(1).upper() if full else ""
                    cols.append((name, base, full))
                    notnull[name] = bool(re.search(r"\bNOT\s+NULL\b", rest, re.I))
                    continue
                ck = re.match(r"(?:CONSTRAINT\s+\w+\s+)?CHECK\s*\((.*)\)", s, re.I | re.S)
                if ck:
                    checks.append(" ".join(ck.group(1).split()))
                    continue
                fk = re.search(r"FOREIGN\s+KEY\s*\(([^)]*)\)\s*REFERENCES\s+(\w+)\s*\(([^)]*)\)",
                               s, re.I | re.S)
                if fk:
                    fks.append((re.sub(r"\s", "", fk.group(1)), fk.group(2),
                                re.sub(r"\s", "", fk.group(3))))
                    continue
                uq = re.match(r"(?:CONSTRAINT\s+\w+\s+)?UNIQUE\s*\(([^)]*)\)", s, re.I | re.S)
                if uq:
                    uniques.append([c.strip() for c in uq.group(1).split(",")])
            out[table] = dict(cols=cols, order=[c[0] for c in cols], types={c[0]: c[1] for c in cols},
                              notnull=notnull, checks=checks, fks=fks, uniques=uniques)
    # 建表脚本外的 CREATE UNIQUE INDEX（条件唯一索引不在本工具判定范围，仅登记普通唯一）
    for path in sorted(_glob_sql()):
        text = re.sub(r"--[^\n]*", "", _read(path))
        for m in re.finditer(r"CREATE\s+UNIQUE\s+INDEX\s+(\w+)\s+ON\s+(\w+)\s*\(([^)]*)\)",
                             text, re.I | re.S):
            t = m.group(2)
            if t in out:
                cols = [c.strip() for c in re.sub(r"\s", "", m.group(3)).split(",")]
                out[t]["uniques"].append(cols)
    return out


def _split_top(body):
    depth, parts, cur = 0, [], ""
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur.strip())
    return parts


def _glob_sql():
    import glob
    return glob.glob(os.path.join(SCHEMA_DIR, "m*.sql"))


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


# ---------------------------------------------------------------------------
# TSV / manifest
# ---------------------------------------------------------------------------

def read_tsv(path):
    raw = _read(path)
    if raw.startswith("\ufeff"):
        return None, None, ["BOM"]
    lines = raw.split("\n")
    data = [l.split("\t") for l in lines if l and not l.startswith("#")]
    if not data:
        return None, None, ["空文件（无表头）"]
    header = data[0]
    rows, errs = [], []
    for i, cells in enumerate(data[1:], 1):
        if len(cells) != len(header):
            errs.append("第 %d 行字段数 %d != 表头 %d" % (i, len(cells), len(header)))
            continue
        rows.append(dict(zip(header, cells)))
    return header, rows, errs


def load_manifest():
    with open(MANIFEST, encoding="utf-8") as fh:
        return json.load(fh)


def selected_datasets(man, groups):
    ds = [d for d in man["datasets"] if d["group"] in groups]
    ds.sort(key=lambda d: (d["load_order"], d["file_order"], d["file"]))
    return ds


def group_rows(man, groups, ddl):
    """按表聚合行：table -> [(dataset, row)]，顺序即装载顺序。"""
    table_rows, errs = {}, []
    for d in selected_datasets(man, groups):
        path = os.path.join(REF, d["file"])
        if not os.path.exists(path):
            errs.append("缺文件 %s" % d["file"])
            continue
        header, rows, e = read_tsv(path)
        for x in e:
            errs.append("%s: %s" % (d["file"], x))
        if header is None:
            continue
        table = d["table"]
        if table not in ddl:
            errs.append("%s: DDL 中无表 %s" % (d["file"], table))
            continue
        exp = [c for c in ddl[table]["order"] if c not in man["injected_columns"]["columns"]]
        if header != exp:
            errs.append("%s: 表头与 DDL 子集不符\n    got=%s\n    exp=%s" % (d["file"], header, exp))
        table_rows.setdefault(table, []).extend((d, r) for r in rows)
    return table_rows, errs


# ---------------------------------------------------------------------------
# 解析规格
# ---------------------------------------------------------------------------

class Resolver:
    """决定某 (table, col) 是否需要把自然键值解析为父表 id。"""

    def __init__(self, man, ddl):
        self.fkr = man["fk_ref_columns"]
        self.passthrough = set(man["passthrough_columns"]["columns"])
        self.overrides = set(man["plain_value_overrides"])
        self.poly = man["polymorphic_ref_columns"]["source_id"]
        self.ddl = ddl
        self.edges = {}                      # table -> {col: (parent, parent_col)}
        for t, info in ddl.items():
            for col, par, pcol in info["fks"]:
                self.edges.setdefault(t, {})[col] = (par, pcol)
        # 父表 -> 自然键列（供 java-fixture 的确定性 id 映射）
        self.parent_key = {}
        for col, spec in self.fkr.items():
            pt, key = spec["table"], spec["key"]
            if pt in self.parent_key and self.parent_key[pt] != key:
                raise ValueError("父表 %s 出现冲突的自然键列：%s / %s" %
                                 (pt, self.parent_key[pt], key))
            self.parent_key[pt] = key

    def spec(self, table, col):
        """返回 ('fk', parent, keycol) / ('poly', info) / None。"""
        if "%s.%s" % (table, col) in self.overrides:
            return None
        if col in self.passthrough:
            return None
        edge = self.edges.get(table, {}).get(col)
        if edge:
            par, pcol = edge
            if pcol != "id":
                return None                 # 外键指向父表自然键列 → 原样透传
            info = self.fkr.get(col)
            if info is None:
                raise ValueError("FK %s.%s -> %s.id 但 fk_ref_columns 缺该列映射" % (table, col, par))
            return ("fk", info["table"], info["key"])
        if col == "source_id" and table == "t_exposure_detail":
            return ("poly", self.poly)
        info = self.fkr.get(col)             # 软引用（无 DB FK，但存用户等自然键）
        if info:
            return ("fk", info["table"], info["key"])
        return None


# ---------------------------------------------------------------------------
# SQL 目标
# ---------------------------------------------------------------------------

def sql_lit(value, base_type):
    if value in ("", "\\N"):
        return "NULL"
    if base_type in NUMERIC_TYPES and re.match(r"^-?\d+(\.\d+)?$", value):
        return value
    return "'" + value.replace("'", "''") + "'"


def sql_expr(table, col, value, res, ddl, from_row=None):
    """生成某列在 INSERT ... SELECT 列表中的 SQL 表达式。"""
    spec = res.spec(table, col)
    if spec and spec[0] == "fk":
        _, par, key = spec
        if value in ("", "\\N"):
            return "NULL"
        return "(SELECT id FROM %s WHERE %s = %s)" % (par, key, "'" + value.replace("'", "''") + "'")
    if spec and spec[0] == "poly":
        return poly_sql(value, from_row, spec[1], res, ddl)
    return sql_lit(value, ddl[table]["types"].get(col, ""))


def poly_sql(value, row, poly, res, ddl):
    """多态软外键：按 source_type 选父表，值以 | 拼接各 key 列。

    复合键中若某列本身是 FK（如 t_inventory_ledger.sub_account_id / product_id），
    父表存的是解析后的数字 id，故该列的比较条件也必须用同一标量子查询解析，
    不能直接拿符号值（如 'SA-COMMODITY'）去比 INTEGER 列。
    """
    if value in ("", "\\N") or row is None:
        return "NULL"
    st = row.get(poly["discriminator"], "")
    mapping = poly["mapping"].get(st)
    if mapping is None:
        raise ValueError("未知 source_type：%s" % st)
    vals = value.split(poly["join"])
    if len(vals) != len(mapping["key"]):
        raise ValueError("source_id 复合键列数不符：%s vs %s" % (vals, mapping["key"]))
    conds = []
    for k, v in zip(mapping["key"], vals):
        quoted = "'" + v.replace("'", "''") + "'"
        sub = res.spec(mapping["table"], k)
        if sub and sub[0] == "fk":
            rhs = "(SELECT id FROM %s WHERE %s = %s)" % (sub[1], sub[2], quoted)
        else:
            rhs = quoted
        conds.append("%s = %s" % (k, rhs))
    return "(SELECT id FROM %s WHERE %s)" % (mapping["table"], " AND ".join(conds))


def sql_injected(col, target):
    return {"created_by": "0", "created_time": "CURRENT_TIMESTAMP",
            "updated_by": "NULL", "updated_time": "NULL", "row_version": "0"}[col]


def emit_sql(target, groups, man, ddl, res, out_path):
    table_rows, errs = group_rows(man, groups, ddl)
    if errs:
        for e in errs:
            print("  ERROR " + e, file=sys.stderr)
        return 1
    lines = [
        "-- =============================================================================",
        "-- HedgeGuard 参考数据装载脚本（由 gen_reference.py 生成，勿手工编辑）",
        "-- 目标：%s   组：%s" % (target, ",".join(groups)),
        "-- 幂等：每条 INSERT 带 WHERE NOT EXISTS（按 manifest.json natural_key 判重）",
        "-- FK：父表自然键值以标量子查询在装载时解析为实际 id，不依赖预分配 IDENTITY",
        "-- 零落盘：password_hash 由生成器注入非凭证明文标记 %s" % PENDING_PASSWORD,
        "-- 仅 INSERT，无存储过程 / 触发器 / 自定义函数（PROJECT.md §6）",
        "-- 前置：按 docs/DEPLOY/README.md §7.1 顺序先执行 schema/*.sql 建表",
        "-- =============================================================================",
        "",
    ]
    for table, items in table_rows.items():
        info = ddl[table]
        # 先哨兵（id 显式 0），后普通行（id 省略）
        sentinel = [(d, r) for d, r in items if r.get("id") == "0"]
        normal = [(d, r) for d, r in items if r.get("id") != "0"]
        for kind, batch in (("sentinel", sentinel), ("normal", normal)):
            if not batch:
                continue
            lines.append("-- ---- %s [%s, %d 行] ----" % (table, kind, len(batch)))
            for d, r in batch:
                cols, exprs = [], []
                for col in info["order"]:
                    if col == "id" and kind == "normal":
                        continue
                    cols.append(col)
                    if col == "id":
                        exprs.append("0")
                    elif col == "password_hash":
                        exprs.append("'%s'" % PENDING_PASSWORD)
                    elif col in man["injected_columns"]["columns"]:
                        exprs.append(sql_injected(col, target))
                    else:
                        exprs.append(sql_expr(table, col, r.get(col, ""), res, ddl, r))
                # 判重谓词
                conds = []
                for k in d["natural_key"]:
                    e = sql_expr(table, k, r.get(k, ""), res, ddl, r)
                    conds.append("x.%s IS NULL" % k if e == "NULL" else "x.%s = %s" % (k, e))
                if kind == "sentinel" and target == "dm8":
                    lines.append("SET IDENTITY_INSERT %s ON;" % table)
                lines.append("INSERT INTO %s (%s)" % (table, ", ".join(cols)))
                lines.append("SELECT %s" % ", ".join(exprs))
                # 内联视图给出 FROM 子句（与 data-init.sql ③④ 的 INSERT...SELECT...FROM (...) src
                # 形态一致），避免依赖「SELECT 无 FROM」在目标方言中的可用性。
                lines.append("FROM (SELECT 1 AS _d) src")
                lines.append("WHERE NOT EXISTS (SELECT 1 FROM %s x WHERE %s);"
                             % (table, " AND ".join(conds) if conds else "1 = 0"))
                if kind == "sentinel" and target == "dm8":
                    lines.append("SET IDENTITY_INSERT %s OFF;" % table)
                lines.append("")
    text = "\n".join(lines).rstrip() + "\n"
    _write_out(out_path, text)
    print("已生成 %s（%d 行）" % (out_path, len(lines)))
    return 0


# ---------------------------------------------------------------------------
# Java 夹具目标
# ---------------------------------------------------------------------------

def assign_ids(table_rows, man, res):
    """确定性分配 id：显式 0 保留；其余按装载顺序从 1 递增。"""
    id_by_key, row_id, poly_id = {}, {}, {}
    for table, items in table_rows.items():
        keycol = res.parent_key.get(table)
        next_id = 1
        for d, r in items:
            rid = 0 if r.get("id") == "0" else next_id
            if r.get("id") != "0":
                next_id += 1
            row_id[id(r)] = rid
            if keycol and r.get(keycol):
                id_by_key[(table, r[keycol])] = rid
        # 多态父表复合键 -> id
        for st, spec in res.poly["mapping"].items():
            if spec["table"] == table:
                for d, r in items:
                    composite = res.poly["join"].join(r.get(k, "") for k in spec["key"])
                    poly_id[(st, composite)] = row_id[id(r)]
    return id_by_key, row_id, poly_id


def java_lit(value, base_type):
    if value in ("", "\\N"):
        return "null"
    if base_type in NUMERIC_TYPES:
        if base_type in ("DECIMAL", "NUMERIC"):
            return 'new BigDecimal("%s")' % value
        return value + "L"
    if base_type in DATE_TYPES:
        return 'LocalDate.parse("%s")' % value
    if base_type in TS_TYPES:
        return 'LocalDateTime.parse("%s")' % value.replace(" ", "T")
    return '"%s"' % _java_escape(value)


def _java_escape(s):
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\r", "\\r")


def java_expr(table, col, value, res, ddl, r, ids):
    spec = res.spec(table, col)
    if spec and spec[0] == "fk":
        if value in ("", "\\N"):
            return "null"
        return str(_lookup(ids[0], spec[1], value)) + "L"
    if spec and spec[0] == "poly":
        if value in ("", "\\N"):
            return "null"
        st = r.get(res.poly["discriminator"], "")
        return str(_lookup_poly(ids[2], st, value)) + "L"
    return java_lit(value, ddl[table]["types"].get(col, ""))


def _lookup(id_by_key, table, key):
    if (table, key) not in id_by_key:
        raise ValueError("无法解析 %s.%s = %r（父行未在本批数据中）" % (table, "natural_key", key))
    return id_by_key[(table, key)]


def _lookup_poly(poly_id, st, value):
    if (st, value) not in poly_id:
        raise ValueError("无法解析多态 source_id：%s / %r" % (st, value))
    return poly_id[(st, value)]


def emit_java(groups, man, ddl, res, out_path):
    table_rows, errs = group_rows(man, groups, ddl)
    if errs:
        for e in errs:
            print("  ERROR " + e, file=sys.stderr)
        return 1
    ids = assign_ids(table_rows, man, res)
    by_group = {}
    for table, items in table_rows.items():
        for d, r in items:
            by_group.setdefault(d["group"], {}).setdefault(table, []).append((d, r))
    master_tables = by_group.get("master", {})
    demo_tables = by_group.get("demo", {})
    total = sum(len(v) for v in table_rows.values())

    lines = [
        "package com.hedgeguard.dao.testsupport;",
        "",
        "import java.math.BigDecimal;",
        "import java.sql.Connection;",
        "import java.sql.Date;",
        "import java.sql.PreparedStatement;",
        "import java.sql.SQLException;",
        "import java.sql.Timestamp;",
        "import java.sql.Types;",
        "import java.time.LocalDate;",
        "import java.time.LocalDateTime;",
        "import java.util.Map;",
        "",
        "/**",
        " * HedgeGuard 参考数据播种件（由 gen_reference.py 生成，勿手工编辑）。",
        " *",
        " * <p>组：%s；共 %d 张表 / %d 行（master %d 行 / demo %d 行）。" %
        (",".join(groups), len(table_rows), total,
         sum(len(v) for v in master_tables.values()), sum(len(v) for v in demo_tables.values())),
        " * 落在 dao 命名空间内以满足 E5（dao 外无 SQL）。",
        " *",
        " * <p>InMemoryDmEngine 语义：显式给全列、id 确定性分配（哨兵 0）、",
        " * DATE 传 LocalDate、TIMESTAMP 传 LocalDateTime（Timestamp 会 ClassCastException）、",
        " * row_version=1、表名大小写敏感、表级 UNIQUE 已在生成前按 natural_key 去重、",
        " * rollback 为 no-op 故装载测试不依赖回滚语义。",
        " *",
        " * <p>幂等：exec 先按 (table, id) 查已装载行，命中即跳过；重复 load 不产生重复行、",
        " * 也不产生 UNIQUE 拒绝。零落盘：password_hash 由生成器注入非凭证明文标记 %s，" % PENDING_PASSWORD,
        " * 真实凭据经环境变量 + AuthService API 设置。前置：调用方须已用 DDL 建好表。",
        " */",
        "public final class ReferenceSeed {",
        "",
        "    private ReferenceSeed() {",
        "    }",
        "",
        "    /** 装载全部选中组（master + demo），等价于 --groups master,demo。 */",
        "    public static void load(InMemoryDmEngine engine) throws SQLException {",
        "        loadMaster(engine);",
        "        loadDemo(engine);",
        "    }",
        "",
        "    /** 只装 master（必需基准），等价于 --groups master。 */",
        "    public static void loadMaster(InMemoryDmEngine engine) throws SQLException {",
    ]

    def emit_inserts(tables):
        for table, items in tables.items():
            info = ddl[table]
            lines.append("        // ---- %s (%d 行) ----" % (table, len(items)))
            for d, r in items:
                cols, vals = [], []
                for col in info["order"]:
                    cols.append(col)
                    if col == "id":
                        vals.append(str(ids[1][id(r)]) + "L")
                    elif col == "password_hash":
                        vals.append('"%s"' % PENDING_PASSWORD)
                    elif col in man["injected_columns"]["columns"]:
                        vals.append({"created_by": "0L",
                                     "created_time": 'LocalDateTime.parse("%s")' % FIXED_TS.replace(" ", "T"),
                                     "updated_by": "null", "updated_time": "null",
                                     "row_version": "1"}[col])
                    else:
                        vals.append(java_expr(table, col, r.get(col, ""), res, ddl, r, ids))
                lines.append("        exec(engine, \"%s\", %dL," % (table, ids[1][id(r)]))
                lines.append('                "INSERT INTO %s (%s) VALUES (%s)",'
                             % (table, ", ".join(cols), ", ".join("?" for _ in cols)))
                lines.append("                %s);" % ", ".join(vals))

    emit_inserts(master_tables)
    lines += [
        "    }",
        "",
        "    /** 只装 demo（可叠加在 loadMaster 之后），不构成独立的合法初始化。 */",
        "    private static void loadDemo(InMemoryDmEngine engine) throws SQLException {",
    ]
    emit_inserts(demo_tables)
    lines += [
        "    }",
        "",
        "    /** 与 M1E2eSeed 同法的 JDBC 绑定助手；先按 (table, id) 判重实现幂等。 */",
        "    private static void exec(InMemoryDmEngine engine, String table, long rowId,",
        "            String sql, Object... args) throws SQLException {",
        "        InMemoryDmEngine.Table t = engine.tables().get(table);",
        "        if (t != null) {",
        "            for (Map<String, Object> existing : t.rows) {",
        "                Object v = existing.get(\"id\");",
        "                if (v instanceof Number && ((Number) v).longValue() == rowId) {",
        "                    return;",
        "                }",
        "            }",
        "        }",
        "        try (Connection conn = engine.create();",
        "             PreparedStatement ps = conn.prepareStatement(sql)) {",
        "            for (int i = 0; i < args.length; i++) {",
        "                Object a = args[i];",
        "                int idx = i + 1;",
        "                if (a instanceof Integer) {",
        "                    ps.setInt(idx, (Integer) a);",
        "                } else if (a instanceof Long) {",
        "                    ps.setLong(idx, (Long) a);",
        "                } else if (a instanceof LocalDate) {",
        "                    ps.setDate(idx, Date.valueOf((LocalDate) a));",
        "                } else if (a instanceof LocalDateTime) {",
        "                    ps.setTimestamp(idx, Timestamp.valueOf((LocalDateTime) a));",
        "                } else if (a instanceof BigDecimal) {",
        "                    ps.setBigDecimal(idx, (BigDecimal) a);",
        "                } else if (a == null) {",
        "                    ps.setNull(idx, Types.NULL);",
        "                } else {",
        "                    ps.setString(idx, String.valueOf(a));",
        "                }",
        "            }",
        "            ps.executeUpdate();",
        "        }",
        "    }",
        "}",
        "",
    ]
    _write_out(out_path, "\n".join(lines))
    print("已生成 %s（%d 行）" % (out_path, len(lines)))
    return 0


# ---------------------------------------------------------------------------
# --check
# ---------------------------------------------------------------------------

def _domain_error(checks, col, value):
    """通用 CHECK：col IN (...) / col IS NULL OR col IN (...) / col = 'x'。"""
    if not value:
        return None
    for ck in checks:
        m = re.match(r"^%s\s+IN\s*\((.*?)\)$" % re.escape(col), ck, re.I)
        if m:
            dom = set(re.findall(r"'([^']*)'", m.group(1)))
            return None if value in dom else "值 %r 不在域 %s" % (value, sorted(dom))
        m = re.match(r"^%s\s+IS\s+NULL\s+OR\s+%s\s+IN\s*\((.*?)\)$"
                     % (re.escape(col), re.escape(col)), ck, re.I)
        if m:
            dom = set(re.findall(r"'([^']*)'", m.group(1)))
            return None if value in dom else "值 %r 不在域(含 NULL) %s" % (value, sorted(dom))
        m = re.match(r"^%s\s*=\s*'([^']*)'$" % re.escape(col), ck, re.I)
        if m:
            return None if value == m.group(1) else "值 %r != %r" % (value, m.group(1))
    return None


def check(groups, man, ddl, res):
    errs, notes = [], []
    table_rows, e = group_rows(man, groups, ddl)
    errs.extend(e)
    injected = set(man["injected_columns"]["columns"])
    gen_resolved = set(man["conventions"]["generator_resolved_columns"])
    date_re = re.compile(r"^\d{4}-\d{2}-\d{2}$")
    ts_re = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")
    num_re = re.compile(r"^-?\d+(\.\d+)?$")

    # 1) 结构 / NOT NULL / CHECK 域 / 列类型格式 / 自然键 / id / password_hash / 组合不变量
    total = 0
    for table, items in table_rows.items():
        info = ddl[table]
        seen = {}
        for d, r in items:
            total += 1
            for col in info["order"]:
                if col in injected:
                    continue
                if col not in r:
                    errs.append("%s: 行缺列 %s" % (d["file"], col))
                    continue
                v = r[col]
                if col not in gen_resolved and info["notnull"].get(col) and v in ("", "\\N"):
                    errs.append("%s: NOT NULL 列 %s 为空" % (d["file"], col))
                de = _domain_error(info["checks"], col, v)
                if de:
                    errs.append("%s: %s" % (d["file"], de))
                # 列类型格式（DATE/TIMESTAMP 的 java-fixture 会按类型解析，格式错会运行期抛异常）
                if v not in ("", "\\N"):
                    bt = info["types"].get(col, "")
                    if bt == "DATE" and not date_re.match(v):
                        errs.append("%s: DATE 列 %s 值格式错误 %r" % (d["file"], col, v))
                    elif bt in ("TIMESTAMP", "DATETIME") and not ts_re.match(v):
                        errs.append("%s: TIMESTAMP 列 %s 值格式错误 %r" % (d["file"], col, v))
                    elif bt in NUMERIC_TYPES and res.spec(table, col) is None \
                            and not num_re.match(v):
                        errs.append("%s: 数值列 %s 含非数值符号值 %r（应为 FK 自然键却缺解析规格）"
                                    % (d["file"], col, v))
            if r.get("id", "") not in ("", "0"):
                errs.append("%s: id 非 0/空：%r" % (d["file"], r["id"]))
            if "password_hash" in r and r["password_hash"] not in ("", "\\N"):
                errs.append("%s: password_hash 非空（应为生成器解析列）" % d["file"])
            key = tuple(r.get(c, "") for c in d["natural_key"])
            if key in seen:
                errs.append("%s: 自然键重复 %s（%s）" % (d["file"], key, seen[key]))
            seen[key] = d["file"]
            cc = COMPOSITE_CHECKS.get(table)
            if cc:
                msg = cc[1](r)
                if msg:
                    errs.append("%s: %s" % (d["file"], msg))
    notes.append("结构/NOT NULL/CHECK/自然键/组合不变量：%d 行" % total)

    # 2) 解析规格：所有非空待解析值都能落到父行
    #    每个父表可能有多列可作为查找键（fk_ref_columns 目标 / 透传目标 / 单列自然键）
    keycols = {}
    for pt, k in res.parent_key.items():
        keycols.setdefault(pt, set()).add(k)
    for col, spec in man["passthrough_columns"]["targets"].items():
        keycols.setdefault(spec["table"], set()).add(spec["key"])
    for d in man["datasets"]:
        if len(d["natural_key"]) == 1:
            keycols.setdefault(d["table"], set()).add(d["natural_key"][0])
    all_keys, poly_sets = {}, {}
    for table, items in table_rows.items():
        for k in keycols.get(table, ()):
            all_keys.setdefault(table, set()).update(
                r.get(k, "") for _d, r in items if r.get(k))
        for st, spec in res.poly["mapping"].items():
            if spec["table"] == table:
                for d, r in items:
                    poly_sets.setdefault(st, set()).add(
                        res.poly["join"].join(r.get(k, "") for k in spec["key"]))
    unresolved = 0
    for table, items in table_rows.items():
        for d, r in items:
            for col in ddl[table]["order"]:
                if col in injected or col == "id":
                    continue
                v = r.get(col, "")
                if v in ("", "\\N"):
                    continue
                spec = res.spec(table, col)
                if spec and spec[0] == "fk":
                    if v not in all_keys.get(spec[1], set()):
                        errs.append("%s: %s=%r 无法解析到 %s.%s" %
                                    (d["file"], col, v, spec[1], spec[2]))
                        unresolved += 1
                elif spec and spec[0] == "poly":
                    st = r.get(res.poly["discriminator"], "")
                    if v not in poly_sets.get(st, set()):
                        errs.append("%s: source_id=%r 无法解析（%s）" % (d["file"], v, st))
                        unresolved += 1
    notes.append("FK / 多态软外键解析：未解析 %d 处" % unresolved)

    # 3) 透传类列的值域（自然键外键 / 普通代码）
    pass_checks = [("master/product_contract.tsv", "underlying_instrument_id", "t_product_contract"),
                   ("demo/sub_instrument_quota.tsv", "instrument_id", "t_product_contract"),
                   ("demo/voucher_line.tsv", "subject_code", "t_account_subject")]
    selected_files = {d["file"] for d in selected_datasets(man, groups)}
    for fn, col, pt in pass_checks:
        if fn not in selected_files:
            continue
        hdr, rows, _ = read_tsv(os.path.join(REF, fn))
        if rows:
            for r in rows:
                v = r.get(col, "")
                if v and v not in all_keys.get(pt, set()):
                    errs.append("%s: %s=%r 不在 %s 自然键集" % (fn, col, v, pt))
    notes.append("自然键外键透传列：3 组")

    # 4) load_order 拓扑一致（与 DDL FK 边对照）
    order = {}
    for d in man["datasets"]:
        order[d["table"]] = min(order.get(d["table"], d["load_order"]), d["load_order"])
    bad_edges = 0
    for d in man["datasets"]:
        t = d["table"]
        for col, par, pcol in ddl.get(t, {}).get("fks", []):
            if par in order and par != t and order[par] >= d["load_order"]:
                errs.append("load_order 违例：%s(%d) 依赖 %s(%d)" %
                            (t, d["load_order"], par, order[par]))
                bad_edges += 1
        for par in d["depends_on"]:
            if par in order and par != t and order[par] >= d["load_order"]:
                errs.append("depends_on/load_order 违例：%s(%d) 依赖 %s(%d)" %
                            (t, d["load_order"], par, order[par]))
                bad_edges += 1
    notes.append("FK 拓扑 / load_order：违例 %d 边" % bad_edges)

    # 5) 零敏感扫描（仅 TSV 数据行，跳过 # 注释与文档）
    bad = re.compile(r"(?i)(password\s*[:=]|passwd\s*[:=]|auth_?token\s*[:=]|"
                     r"secret\s*[:=]|BEGIN\s+(RSA\s+|EC\s+)?PRIVATE KEY|fund_password)")
    hits = 0
    for group_dir in (MASTER_DIR, DEMO_DIR):
        for fn in sorted(os.listdir(group_dir)):
            if not fn.endswith(".tsv"):
                continue
            for i, l in enumerate(_read(os.path.join(group_dir, fn)).splitlines(), 1):
                if l.startswith("#"):
                    continue
                if bad.search(l):
                    errs.append("%s/%s#%d: 疑似敏感字面值" % (os.path.basename(group_dir), fn, i))
                    hits += 1
    notes.append("零敏感扫描（仅 TSV 数据行）：命中 %d" % hits)

    # 6) 与 data-init.sql ③④ 重叠段只读一致性（按自然键集合比对）
    ov = _data_init_consistency(man)
    errs.extend(ov["errors"])
    notes.append("data-init.sql ③④ 重叠段一致性：%d 表 / %d 差异" %
                 (ov["tables"], len(ov["errors"])))

    return errs, notes


def _statement_end(text, start):
    """找语句结尾的分号；跳过单引号字符串内部（如 '-3%,-5%,-10%;+10%' 含 ASCII 分号）。"""
    in_quote = False
    i = start
    while i < len(text):
        c = text[i]
        if c == "'":
            if in_quote and i + 1 < len(text) and text[i + 1] == "'":
                i += 2
                continue
            in_quote = not in_quote
        elif c == ";" and not in_quote:
            return i
        i += 1
    return len(text) - 1


def _data_init_consistency(man):
    """抽取 data-init.sql 各 INSERT 语句源子查询的前 N 个字符串字面量，与 TSV 自然键集合比对。"""
    n_by_table = {"t_position_group": 1, "t_role": 1, "t_position_conflict": 2,
                  "t_role_permission": 2, "t_dict_item": 2,
                  "t_report_period_config": 1, "t_config_item": 1}
    errors, tables = [], 0
    text = _read(DATA_INIT)
    for table, n in n_by_table.items():
        keypos = text.find("INSERT INTO %s (" % table)
        if keypos < 0:
            errors.append("data-init.sql 中未找到 INSERT INTO %s" % table)
            continue
        end = _statement_end(text, keypos)
        block = text[keypos:end + 1]
        frm = block.find("FROM (")
        if frm < 0:
            errors.append("data-init.sql %s 语句缺 FROM ( 源子查询" % table)
            continue
        close = block.find("\n) src", frm)
        if close < 0:
            close = block.find(") src", frm)
        inner = re.sub(r"--[^\n]*", "", block[frm + len("FROM ("):close])
        parts = re.split(r"\bUNION\s+ALL\s+SELECT\b", inner, flags=re.I)
        parts[0] = re.sub(r"^\s*SELECT\b", "", parts[0], flags=re.I)
        keys = set()
        for p in parts:
            lits = re.findall(r"'((?:[^']|'')*)'", p)
            if len(lits) >= n:
                keys.add(tuple(x.replace("''", "'") for x in lits[:n]))
        # TSV 侧自然键（仅 master 组）
        tsv_keys = set()
        for d in man["datasets"]:
            if d["table"] == table and d["group"] == "master":
                hdr, rows, e = read_tsv(os.path.join(REF, d["file"]))
                if rows:
                    for r in rows:
                        tsv_keys.add(tuple(r.get(c, "") for c in d["natural_key"])[:n])
        tables += 1
        if keys != tsv_keys:
            errors.append("data-init.sql 与 master TSV 自然键集合不一致：%s "
                          "(仅 data-init=%s / 仅 TSV=%s)" %
                          (table, sorted(keys - tsv_keys), sorted(tsv_keys - keys)))
    return {"errors": errors, "tables": tables}


# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------

def _write_out(path, text):
    d = os.path.dirname(os.path.abspath(path))
    os.makedirs(d, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def parse_groups(raw):
    groups = [g.strip() for g in raw.split(",") if g.strip()]
    if not groups or any(g not in ("master", "demo") for g in groups):
        raise ValueError("--groups 只接受 master / demo / master,demo")
    if "demo" in groups and "master" not in groups:
        raise ValueError("demo 依赖 master（FK 指向 master 的保留系统行与品种行），不支持只装 demo")
    if "master" not in groups:
        groups.insert(0, "master")
    return ["master"] if groups == ["master"] else ["master", "demo"]


def main(argv=None):
    ap = argparse.ArgumentParser(description="HedgeGuard 参考数据校验器 / 装载脚本生成器")
    ap.add_argument("--check", action="store_true", help="静态校验（默认模式）")
    ap.add_argument("--target", choices=["dm8", "sqlite", "java-fixture"],
                    help="生成目标环境装载脚本")
    ap.add_argument("--groups", default="master", help="初始化组：master / master,demo（默认 master）")
    ap.add_argument("--out", help="输出路径（默认 docs/DEPLOY/reference/out/<target>.*）")
    args = ap.parse_args(argv)

    try:
        groups = parse_groups(args.groups)
    except ValueError as ex:
        print("参数错误：%s" % ex, file=sys.stderr)
        return 2
    man = load_manifest()
    ddl = parse_ddl()
    res = Resolver(man, ddl)

    if args.target and args.check:
        print("参数错误：--check 与 --target 互斥", file=sys.stderr)
        return 2

    if args.target:
        suffix = "ReferenceSeed.java" if args.target == "java-fixture" else \
                 "reference_%s.sql" % args.target
        out = args.out or os.path.join(DEFAULT_OUT, suffix)
        if args.target == "java-fixture":
            return emit_java(groups, man, ddl, res, out)
        return emit_sql(args.target, groups, man, ddl, res, out)

    errs, notes = check(groups, man, ddl, res)
    print("== HedgeGuard 参考数据 --check（组：%s）==" % ",".join(groups))
    for n in notes:
        print("  · " + n)
    if errs:
        print("结果：FAIL（%d 项）" % len(errs))
        for e in errs[:80]:
            print("  - " + e)
        if len(errs) > 80:
            print("  ... 另有 %d 项" % (len(errs) - 80))
        return 1
    print("结果：PASS（RC=0）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
