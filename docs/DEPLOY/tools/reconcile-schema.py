#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
reconcile-schema.py —— 建表脚本与 DATA_MODEL.md 的静态逐列一致性核对（T-052 验收 5 的可复跑脚本）。

背景
----
本环境无 DM8 实例（实测无 5236 监听 / 无 DmJdbcDriver*.jar / 无 dmserver 进程），
无法完成「DM8 实机建表跑通」。本脚本提供**文本级列清单比对**作为替代证据，
并按 CH-016 口径**明确标注「未验 DM8 实机」**——不得以本脚本冒充 DM8 实机验证。

它做什么
--------
1. 解析 docs/DEPLOY/schema/*.sql 的全部 CREATE TABLE 块，得到 {表名: {列名集合}}；
2. 解析 docs/DATA_MODEL.md 每个 '#### TB-XXX-n 表名' 小节的字段表，得到 {表名: {列名集合}}；
3. 对**已在脚本中建表**的表：
     · 表名必须在 DATA_MODEL 中存在；
     · 列集合必须逐列一致（缺列 / 多列均报错）；
4. 对 DATA_MODEL 中存在、但脚本尚未建表的表：列为「未建脚本（后续里程碑）」，
   仅信息性提示，不计失败（M3 的 RISK/AUDIT/ACCT 等模块尚未交付）。

退出码
------
0 = 已建表全部逐列一致（未建表的表另列，不影响退出码）
1 = 存在缺表 / 缺列 / 多列
2 = 输入文件缺失（环境前置缺失）

用法
----
python3 docs/DEPLOY/tools/reconcile-schema.py [--root <仓库根>] [--json]
"""
import argparse
import json
import os
import re
import sys

SCHEMA_REL_DIR = os.path.join("docs", "DEPLOY", "schema")
DATA_MODEL_REL = os.path.join("docs", "DATA_MODEL.md")

# SQL 中表级约束/其他非列定义行的首个关键字（跳过它们）
SQL_CONSTRAINT_KEYWORDS = {
    "CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "KEY", "INDEX",
    "REFERENCES", "ON", "OR", "AND", "NOT",
}

# 合法列类型（DATA_MODEL.md 第 0.2 节）。仅当「首 token=列名、次 token=类型」时才算列定义，
# 以排除外表键/索引约束的换行续行（如 ; ON DELETE RESTRICT ...）。
SQL_TYPES = {
    "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "VARCHAR", "CHAR",
    "DECIMAL", "NUMERIC", "DATE", "TIME", "TIMESTAMP", "DATETIME",
    "CLOB", "BLOB", "TEXT", "BOOLEAN", "BIT", "FLOAT", "DOUBLE", "REAL",
}

# 通用审计字段（DATA_MODEL.md 第 0.3 节）：文档不在每张表字段表内重复列出，
# 但建表脚本每张表都带 => 核对时作为「允许的额外列」。
AUDIT_COLUMNS = {"id", "created_by", "created_time", "updated_by", "updated_time", "row_version"}

BT = chr(96)  # 反引号：避免在生成该文件的编排模板里出现裸反引号

CREATE_RE = re.compile(r"^\s*CREATE\s+TABLE\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", re.IGNORECASE)
COLUMN_RE = re.compile(r"^\s*([a-z_][a-z0-9_]*)\s+([A-Za-z].*)", re.IGNORECASE)
DOC_SECTION_RE = re.compile(r"^####\s+(TB-[A-Z]+-\d+)\s+" + BT + r"([A-Za-z_][A-Za-z0-9_]*)" + BT)
BACKTICK_RE = re.compile(BT + r"([a-z_][a-z0-9_]*)" + BT)


def parse_sql(path):
    """解析单个 .sql -> {table: set(columns)}。"""
    tables = {}
    cur = None
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if cur is None:
                m = CREATE_RE.match(line)
                if m:
                    cur = m.group(1).lower()
                    tables[cur] = set()
                continue
            # 结束条件：遇到以 ); 收尾的顶层闭括号
            if re.match(r"^\s*\)\s*;", line):
                cur = None
                continue
            stripped = line.strip()
            if not stripped or stripped.startswith("--"):
                continue
            toks = stripped.split()
            if not toks:
                continue
            up = toks[0].upper()
            if up in SQL_CONSTRAINT_KEYWORDS:
                continue
            # 仅当「首 token=列名、次 token=合法类型」时才算列定义
            if len(toks) >= 2 and re.match(r"^[a-z_][a-z0-9_]*$", toks[0], re.IGNORECASE) \
                    and re.match(r"^[A-Za-z]+", toks[1]) \
                    and re.match(r"^[A-Za-z]+", toks[1]).group(0).upper() in SQL_TYPES:
                tables[cur].add(toks[0].lower())
    return tables


def parse_data_model(path):
    """解析 DATA_MODEL.md -> {table: set(columns)}（仅取每节的第一个字段表）。"""
    tables = {}
    cur = None
    in_table = False
    have_field_table = False
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            m = DOC_SECTION_RE.match(line)
            if m:
                cur = m.group(2).lower()
                tables.setdefault(cur, set())
                in_table = False
                have_field_table = False
                continue
            if cur is None:
                continue
            # 字段表表头（区别于其后的「索引」表：索引表无「约束」列）
            if (not have_field_table) and line.startswith("|") \
                    and "字段" in line and "类型" in line and "约束" in line:
                in_table = True
                have_field_table = True
                continue
            if in_table:
                if not line.startswith("|"):
                    in_table = False
                    continue
                if re.match(r"^\|[\s\-:|]+\|?\s*$", line):
                    continue  # 表头分隔行
                cells = [c.strip() for c in line.strip().strip("|").split("|")]
                if not cells:
                    continue
                # 一个单元格可并列多个列名（如 `min_value` / `max_value`）=> 全部计入
                for nm in BACKTICK_RE.findall(cells[0]):
                    tables[cur].add(nm.lower())
    return tables


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=None)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    root = args.root or os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    schema_dir = os.path.join(root, SCHEMA_REL_DIR)
    dm_path = os.path.join(root, DATA_MODEL_REL)

    if not os.path.isdir(schema_dir):
        print("FAIL: 建表脚本目录不存在：%s" % schema_dir)
        return 2
    if not os.path.isfile(dm_path):
        print("FAIL: DATA_MODEL.md 不存在：%s" % dm_path)
        return 2

    sql_tables = {}
    for name in sorted(os.listdir(schema_dir)):
        if name.endswith(".sql") and not name.startswith("data-init"):
            got = parse_sql(os.path.join(schema_dir, name))
            for t, cols in got.items():
                sql_tables[t] = cols

    doc_tables = parse_data_model(dm_path)

    problems = []
    checked = []
    for t in sorted(sql_tables):
        if t not in doc_tables:
            problems.append("缺文档：表 %s 在 DATA_MODEL.md 中无 TB-* 小节" % t)
            continue
        scols, dcols = sql_tables[t], doc_tables[t]
        if not dcols:
            problems.append("缺文档：表 %s 的 DATA_MODEL 小节未解析出任何列（疑缺字段表）" % t)
            continue
        missing = sorted(dcols - scols)               # 文档有、脚本无
        extra = sorted(scols - dcols - AUDIT_COLUMNS) # 脚本有、文档无（通用审计字段除外）
        ok = (not missing) and (not extra)
        checked.append({"table": t, "ok": ok, "columns": len(scols),
                        "missing": missing, "extra": extra})
        if missing:
            problems.append("缺列：%s 缺 %s" % (t, ",".join(missing)))
        if extra:
            problems.append("多列：%s 多 %s" % (t, ",".join(extra)))

    doc_only = sorted(set(doc_tables) - set(sql_tables))
    failed = len([c for c in checked if not c["ok"]])

    result = {
        "sql_tables_total": len(sql_tables),
        "doc_tables_total": len(doc_tables),
        "checked": len(checked),
        "failed": failed,
        "doc_only_tables": doc_only,
        "problems": problems,
        "dm8_real_build": "未执行（本环境无 DM8 实例；CH-016：不得以 SQLite 冒充 DM8）",
    }
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("== 建表脚本 <-> DATA_MODEL.md 静态逐列核对（T-052 验收 5 替代证据）==")
        print("建表脚本表数 = %d；DATA_MODEL.md 表数 = %d；已核对 = %d；失败 = %d"
              % (result["sql_tables_total"], result["doc_tables_total"],
                 result["checked"], result["failed"]))
        print("DM8 实机建表：%s" % result["dm8_real_build"])
        for c in checked:
            print("  [%s] %-32s (%d 列)" % ("OK" if c["ok"] else "FAIL", c["table"], c["columns"]))
        if doc_only:
            print("-- 已在 DATA_MODEL 但尚未建脚本（后续里程碑，非失败）：%d 张" % len(doc_only))
            print("   " + ", ".join(doc_only))
        if problems:
            print("-- 问题清单：")
            for p in problems:
                print("   " + p)
        print("RESULT: %s" % ("PASS" if not problems else "FAIL"))

    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
