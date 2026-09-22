#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# =============================================================================
# scan_hgcp_schema.py — E7 判据：`ARCHITECTURE.md §4.2.8` ↔ `proto/hgcp.proto` 一致性
#
# 承载：docs/ARCHITECTURE.md **§4.2.8「字段级 schema（线格式表示的定义）」**
#       —— `CH-013` 定版（2026-09-22，用户批准）；本节明载
#       「**`proto/hgcp.proto` 必须由本节逐条转录产出**」。
#
# 为什么需要本判据（立项理由）：
#   proto3 字段号**一经发布不可回收**（§4.2.8.1；复用已删除号为**禁止**项）。
#   而独立复核观察到：把 `MsgError.rule_id` 的字段号 `4` 改成 `7`（**合法但不符
#   §4.2.8**）⇒ 测试套件 **14 个用例全绿** ⇒ 现有测试**完全不锚定字段号**。
#   即：既往唯一保证「字段号 ↔ §4.2.8」一致的机制是**人工机械化差集**（一次性），
#   **非持续性**。本判据把该差集固化为**可执行、可 CI 化**的门禁。
#
# 用法：
#   tools/arch-check/scan_hgcp_schema.py [--arch <file>] [--proto <file>]
#     --arch  <file>  架构文档（默认 docs/ARCHITECTURE.md）
#     --proto <file>  线格式定义（默认 proto/hgcp.proto）
#     -h, --help      显示本帮助
#
#   退出码语义（沿用六脚本统一口径，`T-095`）：
#     0 = 通过；**默认路径**不存在（组件未铺开）⇒ 打印 `SKIP` 并退 `0`
#     1 = 命中（**已解析但内容不符** ⇒ 架构偏离）
#     2 = 用法 / 前置错误 —— **显式传入**的路径不存在，或**制品出现本判据不认识的
#         形态**（未解析行 / 未解析表体行 ⇒ 判据对后续行**未能真正执行**）。
#         后者**必须报错、不得静默跳过**：静默吞掉未识别行会让「新语法」被漏判，
#         这恰是本判据立项要消灭的失效模式（前序临时脚本的 `__UNPARSED__` 即此坑）。
#
# 判据（六类；逐条机械比对，**禁止目测抽样**）：
#   ① 消息集    ：40 顶层（§4.2.8.2/3/4）+ 10 复合（§4.2.8.6），**含声明顺序**
#   ② 字段三元组：**名 ↔ 字段号 ↔ 类型**（含 `repeated`），**逐字段**、**含顺序**
#   ③ 必填性    ：§4.2.8 表体 `必`/`可`/`条件` ↔ proto 字段尾注释首 token
#   ④ 枚举      ：枚举集合 + **逐值**（名 ↔ 值），含**前缀剥离**；
#                 `MsgType` 的 40 个逐值**取自 §4.2.3**（§4.2.8.7 只给首尾）
#   ⑤ 自洽计数  ：`214 = 38 + 77 + 99`、复合 `66`、字段号 `1..n` **连续不跳号**、
#                 各节「小计」行 ↔ 实算、各节标题「N 条」↔ 实算
#   ⑥ 命名派生  ：§4.2.3 冻结名 → TitleCase 加回 `Msg`/`Cmd`/`Evt` ⇒ proto message 名
#
# ⚠️ **显式「不覆盖」（不得含糊带过；下列各项**不**由本判据保证）**：
#   N1. §4.2.8.5 `条件` 必填的**条件语义** —— 本判据只判「§4.2.8.5 清单行集合 ==
#       表体中 `条件` 字段集合」（行存在性 + 可对应），**不判条件表达式本身**是否
#       正确、是否可实现（属语义级，须代码审查 + 测试兜底）。
#   N2. **散文注记** —— 只锚**表体**（`|` 行）：`>` 引用块、正文段落、§4.2.8.8 裁定表、
#       §4.2.8.9 定稿表**均不参与比对**（唯二例外见 N3/N7）。
#       ⚠️ **勘误（2026-09-22，本席自查；原表述误将 §4.2.8.1 整体列入「不参与」）**：
#       **下列「非表体」内容其实参与比对，不得当作可自由编辑的注记**：
#         · **§4.2.8.1 规定表中的「顶层字段号总量」一行** —— `_parse_decl_row()` 从中取
#           `214` / `38`,`77`,`99` / `40 条消息` 三项**声明值**，与实算互校（判据 ⑤）。
#           实测：把该行的 `= 214` 改为 `= 215` ⇒ **退 `1`**（`[⑤] §4.2.8.1 声明顶层总量 215 ≠ 214`）。
#           **§4.2.8.1 的其余行**（编号空间 / 新增字段 / 类型映射 / 必填性语义 / 枚举 /
#           命名派生 / 与 §4.2.4 的分工）**不参与**。
#         · **§4.2.8.2 / .3 / .4 的章节标题**中的「`N` 条」（`（9 条，双向）` 等）——
#           用于判据 ⑤ 的「条数自洽」。
#       即：**参与面 = `§4.2.8.1`（仅该一行）+ `§4.2.8.2/.3/.4`（表体 + 标题 + 小计行）
#       + `§4.2.8.5`（表体两列）+ `§4.2.8.6`（表体）+ `§4.2.8.7`（表体）+ `§4.2.3`（表体）**。
#   N3. `MsgType` 的 40 个**逐值**取自 §4.2.3 —— 若 §4.2.3 行本身「名/码」写错，
#       本判据只会把它与 proto **一并**判为「不一致」，**不能**指出是哪一侧错。
#   N4. **冻结-不变量**（消息集 40 / 组件错误码 62 / `VER` = `0x0100` / `FR` 计数 184）
#       **不在本条判据范围**（避免与「§4.2.8 一致性」耦合）；建议**另立一条判据**。
#   N5. proto **顶层**只接受 `syntax = "…";` 与 `package <name>;` —— 二者**仅登记、不判**；
#       ⚠️ **`syntax` / `package` 之外的任何顶层结构**（含 `option` / `import` / 扩展声明等）
#       属「本判据**不认识的形态**」⇒ **退 `2`**（**不得**读作"不判"）—— 实测
#       `option java_package = "x";` 与 `import "other.proto";` **均退 `2`**，
#       与下方「已知取舍」条一致；
#       proto 字段注释中除**必填性首 token** 外的文字**不判**。
#   N6. **线格式编码**（protobuf wire type / 变长编码 / 帧布局）不判 —— 由类型映射
#       与 §4.2.2 帧格式分别承载。
#   N7. §4.2.4 的字段级说明（§4.2.8.1 规定「说明权威 = §4.2.4」）**不判**；
#       本判据只锚 §4.2.8 的表体。
#   N8. **附录 C（`C.3.1`~`C.3.5`）不判** —— 实测该附录含**与 §4.2.8 近乎同名同形的
#       表格副本**（如 `小计 38/77/99`、`ServerState` 枚举行均逐字重复），但
#       §4.2.8 正文与附录 C 标题**均明载「附录 C = `CH-013` 提案留痕，非权威」**
#       ⇒ 本判据**只锚 `^##### 4.2.8.x` 章节**，**不扫**附录 C。故「§4.2.8 已改、
#       附录 C 未同步」这类**留痕不一致不会被本判据拦下**（留痕件无门禁价值）。
#
# 已知取舍（**显式披露，非缺陷**）：
#   · 顶层出现本判据不认识的结构（如 `option java_package = ...;`）⇒ 退 `2`。
#     取舍理由：宁可**报错**也不**静默漏判**；新增顶层结构时须同步扩展本判据。
#   · message/enum 体内**纯注释行**与空行**允许**（不计为未解析，但**计数并打印**，
#     不作静默吞并）；体内其它任何不匹配字段/枚举成员形态的行 ⇒ 退 `2`。
#     ⇒ 危险的新语法（`optional`/`map<>`/`oneof`/`reserved`）**必被拦下**。
#   · `ComponentRole` 的**值名前缀例外**（§4.2.8.7 白名单）：§4.2.8.7 明载该枚举
#     保留 §4.2.3 冻结值名 `ROLE_JAVA` / `ROLE_CPP`、**不统一加枚举名前缀**
#     （仅 `*_UNSET` → `COMPONENT_ROLE_UNSET`）—— 系**唯一例外、不扩散**。
#
# 自测（三条；隔离夹具内做，原始输出见回执）：
#   正例 现行制品                          ⇒ 退 0
#   反例1 字段号漂移（proto 侧 `4`→`7`）    ⇒ 退 1
#   反例2 未解析行（proto/ARCH 注入新形态）  ⇒ 退 2（**fail，非静默通过**）
#   反例3 显式路径不存在                    ⇒ 退 2
# =============================================================================

import os
import re
import sys

# --- 统一预期计数（自洽断言用；与 §4.2.8.1 表体声明值互为校验） -----------------
TOP_TOTAL = 214
SEC_EXPECT = {"4.2.8.2": 38, "4.2.8.3": 77, "4.2.8.4": 99}
TOP_MSG_COUNT = 40
COMPOSITE_TOTAL = 66

# §4.2.8.7 值名前缀约定的**唯一白名单例外**（保住 §4.2.3 冻结值名）
ENUM_PREFIX_EXCEPTIONS = {"ComponentRole": ("ROLE_JAVA", "ROLE_CPP")}

USAGE = """用法：scan_hgcp_schema.py [--arch <file>] [--proto <file>]
  --arch  <file>  架构文档，默认 docs/ARCHITECTURE.md
  --proto <file>  线格式定义，默认 proto/hgcp.proto
  -h, --help      显示本帮助
退出码：0 通过 / 默认路径不存在（SKIP）；1 不一致（架构偏离）；
        2 用法或前置错误（含显式路径不存在、制品出现本判据不认识的形态）
"""


class ShapeError(Exception):
    """制品出现本判据不认识的形态 ⇒ 判据对后续行未能真正执行（退出码 2）。"""


# =============================================================================
# 通用工具
# =============================================================================

def read_lines(path):
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read().split("\n")


def split_cells(line):
    """切分 markdown 表格行；**不在 `〔…〕` 内、不在反引号内**的 `|` 才作分隔符。

    兼容单元格内的转义竖线 `\\|`（经 `&nbsp;` 之外的第二形态：`\\|` 原样保留）。
    """
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    cells, cur, depth, intick, i = [], "", 0, False, 0
    while i < len(s):
        ch = s[i]
        if ch == "\\" and i + 1 < len(s) and s[i + 1] == "|":
            cur += "|"
            i += 2
            continue
        if ch == "`":
            intick = not intick
        elif not intick:
            if ch == "〔":
                depth += 1
            elif ch == "〕":
                depth = max(0, depth - 1)
            elif ch == "|" and depth == 0:
                cells.append(cur.strip())
                cur = ""
                i += 1
                continue
        cur += ch
        i += 1
    cells.append(cur.strip())
    return cells


def is_separator_row(cells):
    return bool(cells) and all(re.fullmatch(r":?-{2,}:?", c or "") for c in cells)


def first_backticked(s):
    m = re.search(r"`([^`]+)`", s)
    return m.group(1) if m else None


def heading_lines(lines, prefix):
    """按**章节锚点**（`^#… <prefix>`）求区间，**禁用固定行号**（ARCHITECTURE.md 是活跃制品）。

    返回 (heading_text, [(lineno, line), ...])；章节不存在返回 (None, [])。
    """
    start = None
    for i, ln in enumerate(lines):
        if re.match(r"^#{2,6}\s+" + re.escape(prefix) + r"(\s|$)", ln):
            start = i
            break
    if start is None:
        return None, []
    end = len(lines)
    for j in range(start + 1, len(lines)):
        if re.match(r"^#{1,6}\s", lines[j]):
            end = j
            break
    return lines[start], [(k + 1, lines[k]) for k in range(start, end)]


# =============================================================================
# ARCHITECTURE.md 解析
# =============================================================================

FIELD_ENTRY_RE = re.compile(r"^`([A-Za-z_][A-Za-z0-9_]*)`\(\s*(\d+)\s*:\s*(.+?)\s*\)\s*(.*)$")
FIELD_INNER_RE = re.compile(
    r"^(?:(repeated)\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*"
    r"(?:·(必|可|条件))?\s*(〔[^〕]*〕)?$"
)
TRAILING_ANNO_RE = re.compile(r"^(?:〔[^〕]*〕\s*)*$")


def split_field_entries(cell):
    """按 `〔…〕` 深度 0 的 ASCII 逗号切分字段条目（注记内可能含全角逗号，不受影响）。"""
    out, cur, depth, intick, i = [], "", 0, False, 0
    while i < len(cell):
        ch = cell[i]
        if ch == "`":
            intick = not intick
        elif not intick:
            if ch == "〔":
                depth += 1
            elif ch == "〕":
                depth = max(0, depth - 1)
            elif ch == "," and depth == 0:
                out.append(cur)
                cur = ""
                i += 1
                continue
        cur += ch
        i += 1
    out.append(cur)
    return [c.strip() for c in out if c.strip()]


def parse_field_cell(cell, where):
    """解析「字段（号:类型·必填）」单元格 ⇒ [(name, num, type, repeated, req), ...]。"""
    fields = []
    for entry in split_field_entries(cell):
        m = FIELD_ENTRY_RE.match(entry)
        if not m:
            raise ShapeError("%s：字段条目形态未解析 ⇒ %r" % (where, entry))
        name, num, inner, trail = m.group(1), int(m.group(2)), m.group(3), m.group(4)
        mi = FIELD_INNER_RE.match(inner)
        if not mi:
            raise ShapeError("%s：字段 `%s` 的「类型·必填」形态未解析 ⇒ %r"
                             % (where, name, inner))
        repeated, ftype, req, anno = mi.group(1), mi.group(2), mi.group(3), mi.group(4)
        if not TRAILING_ANNO_RE.match(trail):
            raise ShapeError("%s：字段 `%s` 尾随注记形态未解析 ⇒ %r"
                             % (where, name, trail))
        if req is None:
            raise ShapeError("%s：字段 `%s` **缺必填性**（须为 `必`/`可`/`条件`）"
                             % (where, name))
        fields.append({
            "name": name, "num": num, "type": ftype,
            "repeated": bool(repeated), "req": req, "anno": anno or "",
        })
    return fields


class Arch(object):
    def __init__(self, lines):
        self.lines = lines
        self.top = []          # [(sec, code, msgname, fields, declared_cnt)]
        self.composite = []    # [(name, fields, declared_cnt)]
        self.enums = {}        # name -> [(valname, intval)]
        self.cond_rows = []    # [(msg, field)]
        self.m43 = []          # §4.2.3 [(frozen_name, int_code)]
        self.declared = {}     # 自洽声明值
        self._parse()

    # -- §4.2.8.2 / .3 / .4 ---------------------------------------------------
    def _parse_msg_section(self, sec, heading_line):
        rows = []
        subtotal = None
        hcnt = None
        m = re.search(r"（\s*(\d+)\s*条", heading_line)
        if m:
            hcnt = int(m.group(1))
        for no, ln in self.secs[sec]:
            if not ln.startswith("|"):
                continue
            cells = split_cells(ln)
            if is_separator_row(cells) or cells[0] == "#":
                continue
            if len(cells) != 5:
                raise ShapeError("%s 行 %d：表格列数 %d ≠ 5" % (sec, no, len(cells)))
            if cells[0] == "" and "小计" in ln:
                mm = re.search(r"\d+", cells[4])
                if not mm:
                    raise ShapeError("%s 行 %d：小计行数值未解析 ⇒ %r" % (sec, no, cells[4]))
                subtotal = int(mm.group(0))
                continue
            if not re.fullmatch(r"\d+", cells[0]):
                raise ShapeError("%s 行 %d：表体行形态未解析（首列非序号）⇒ %r"
                                 % (sec, no, cells[0]))
            mc = re.search(r"`?(0x[0-9A-Fa-f]{4})`?", cells[1])
            if not mc:
                raise ShapeError("%s 行 %d：类型码未解析 ⇒ %r" % (sec, no, cells[1]))
            name = first_backticked(cells[2]) or cells[2].strip()
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
                raise ShapeError("%s 行 %d：message 名未解析 ⇒ %r" % (sec, no, cells[2]))
            fields = parse_field_cell(cells[3], "%s 行 %d（%s）" % (sec, no, name))
            try:
                dcnt = int(cells[4])
            except ValueError:
                raise ShapeError("%s 行 %d：字段数列未解析 ⇒ %r" % (sec, no, cells[4]))
            rows.append((sec, int(mc.group(1), 16), name, fields, dcnt))
        return rows, subtotal, hcnt

    # -- §4.2.8.6 -------------------------------------------------------------
    def _parse_composite_section(self):
        rows = []
        for no, ln in self.secs["4.2.8.6"]:
            if not ln.startswith("|"):
                continue
            cells = split_cells(ln)
            if is_separator_row(cells) or cells[0] == "类型":
                continue
            if len(cells) != 4:
                raise ShapeError("4.2.8.6 行 %d：表格列数 %d ≠ 4" % (no, len(cells)))
            name = first_backticked(cells[0])
            if not name or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
                raise ShapeError("4.2.8.6 行 %d：复合类型名未解析 ⇒ %r" % (no, cells[0]))
            fields = parse_field_cell(cells[1], "4.2.8.6 行 %d（%s）" % (no, name))
            try:
                dcnt = int(re.sub(r"[^\d]", "", cells[2]) or "-1")
            except ValueError:
                raise ShapeError("4.2.8.6 行 %d：字段数列未解析 ⇒ %r" % (no, cells[2]))
            rows.append((name, fields, dcnt))
        return rows

    # -- §4.2.8.5 -------------------------------------------------------------
    def _parse_cond_section(self):
        rows = []
        for no, ln in self.secs["4.2.8.5"]:
            if not ln.startswith("|"):
                continue
            cells = split_cells(ln)
            if is_separator_row(cells) or cells[0] == "消息":
                continue
            if len(cells) != 3:
                raise ShapeError("4.2.8.5 行 %d：表格列数 %d ≠ 3" % (no, len(cells)))
            msg = first_backticked(cells[0])
            fld = first_backticked(cells[1])
            if not msg or not fld:
                raise ShapeError("4.2.8.5 行 %d：消息/字段名未解析 ⇒ %r" % (no, cells[:2]))
            rows.append((msg, fld))
        return rows

    # -- §4.2.8.7 -------------------------------------------------------------
    VAL_RE = re.compile(r"`([^`]+)`\s*=\s*(0x[0-9A-Fa-f]+|\d+)")

    def _parse_enum_section(self):
        enums = {}
        for no, ln in self.secs["4.2.8.7"]:
            if not ln.startswith("|"):
                continue
            cells = split_cells(ln)
            if is_separator_row(cells) or cells[0] == "枚举":
                continue
            if len(cells) != 3:
                raise ShapeError("4.2.8.7 行 %d：表格列数 %d ≠ 3" % (no, len(cells)))
            name = first_backticked(cells[0])
            if not name:
                raise ShapeError("4.2.8.7 行 %d：枚举名未解析 ⇒ %r" % (no, cells[0]))
            pairs = [(n, int(v, 0)) for n, v in self.VAL_RE.findall(cells[1])]
            if name == "MsgType":
                # 非标准形态：`MSG_TYPE_UNSET`=0 + N 个冻结名（首尾示例）⇒ 逐值取自 §4.2.3
                mcnt = re.search(r"\*\*(\d+)\s*个冻结名", cells[1])
                if not mcnt:
                    raise ShapeError("4.2.8.7 `MsgType` 行形态未解析（未见「N 个冻结名」）")
                self.declared["msgtype_count"] = int(mcnt.group(1))
                if not pairs or pairs[0] != ("MSG_TYPE_UNSET", 0):
                    raise ShapeError("4.2.8.7 `MsgType` 行：首值须为 `MSG_TYPE_UNSET`=0")
                self.declared["msgtype_ends"] = pairs[1:]
                enums[name] = [("MSG_TYPE_UNSET", 0)]
                continue
            if not pairs:
                raise ShapeError("4.2.8.7 行 %d（%s）：取值列未解析 ⇒ %r"
                                 % (no, name, cells[1]))
            enums[name] = pairs
        return enums

    # -- §4.2.8.1（自洽声明） --------------------------------------------------
    def _parse_decl_row(self):
        for no, ln in self.secs["4.2.8.1"]:
            if ln.startswith("|") and "顶层字段号总量" in ln:
                cells = split_cells(ln)
                m = re.search(r"=\s*\**(\d+)\**", cells[1])
                if not m:
                    raise ShapeError("4.2.8.1 行 %d：顶层字段号总量未解析" % no)
                self.declared["top_total"] = int(m.group(1))
                bolds = re.findall(r"\*\*(\d+)\*\*", cells[1])
                if len(bolds) < 3:
                    raise ShapeError("4.2.8.1 行 %d：分项（38/77/99）未解析" % no)
                self.declared["top_split"] = [int(b) for b in bolds[:3]]
                mt = re.search(r"\*\*(\d+)\s*条消息", cells[1])
                if mt:
                    self.declared["top_msg_count"] = int(mt.group(1))
                return
        raise ShapeError("4.2.8.1：未找到「顶层字段号总量」行（权威自洽口径缺失）")

    # -- §4.2.3 ---------------------------------------------------------------
    def _parse_43(self):
        head, body = heading_lines(self.lines, "4.2.3")
        if head is None:
            raise ShapeError("未找到 `#### 4.2.3` 章节（`MsgType` 逐值来源缺失）")
        rows = []
        for no, ln in body:
            if not ln.startswith("|"):
                continue
            cells = split_cells(ln)
            if is_separator_row(cells) or cells[0] == "#":
                continue
            if not re.fullmatch(r"\d+", cells[0]):
                raise ShapeError("4.2.3 行 %d：表体行形态未解析 ⇒ %r" % (no, cells[0]))
            mc = re.search(r"`?(0x[0-9A-Fa-f]{4})`?", cells[1])
            name = first_backticked(cells[2])
            if not mc or not name:
                raise ShapeError("4.2.3 行 %d：类型码/名称未解析 ⇒ %r" % (no, cells[1:3]))
            rows.append((name, int(mc.group(1), 16)))
        return rows

    def _parse(self):
        self.secs = {}
        for key in ("4.2.8.1", "4.2.8.2", "4.2.8.3", "4.2.8.4",
                    "4.2.8.5", "4.2.8.6", "4.2.8.7"):
            head, body = heading_lines(self.lines, key)
            if head is None:
                raise ShapeError("未找到 `##### %s` 章节（§4.2.8 结构缺失）" % key)
            self.secs[key] = body
            self.secs[key + "#head"] = head
        self.sec_subtotals = {}
        self.sec_hcounts = {}
        for sec in ("4.2.8.2", "4.2.8.3", "4.2.8.4"):
            rows, sub, hcnt = self._parse_msg_section(sec, self.secs[sec + "#head"])
            self.top.extend(rows)
            self.sec_subtotals[sec] = sub
            self.sec_hcounts[sec] = hcnt
        self.composite = self._parse_composite_section()
        self.cond_rows = self._parse_cond_section()
        self.enums = self._parse_enum_section()
        self._parse_decl_row()
        self.m43 = self._parse_43()


# =============================================================================
# proto/hgcp.proto 解析
# =============================================================================

MSG_OPEN_RE = re.compile(r"^(message|enum)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\{$")
FIELD_RE = re.compile(
    r"^(?:(repeated)\s+)?([A-Za-z_][A-Za-z0-9_.]*)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(\d+)\s*;$")
ENUMVAL_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(0x[0-9A-Fa-f]+|\d+)\s*;$")
TOP_RE = re.compile(r"^(syntax\s*=\s*\"[^\"]*\"|package\s+[A-Za-z_][A-Za-z0-9_.]*)\s*;$")
REQ_RE = re.compile(r"^(必|可|条件)")


class Proto(object):
    def __init__(self, lines):
        self.messages = []     # [{name, fields, line}]
        self.enums = []        # [{name, values:[(n,v,line)], line}]
        self.order = []        # [('message'|'enum', name)]
        self.top_level = []    # ['syntax …', 'package …']（登记，不静默）
        self.comment_only = 0
        self._parse(lines)

    def _parse(self, lines):
        blk = None
        for idx, raw in enumerate(lines, 1):
            code, sep, comment = raw.partition("//")
            c = code.strip()
            if c == "" and raw.strip() == "":
                continue
            if blk is None:
                m = MSG_OPEN_RE.match(c)
                if m:
                    blk = [m.group(1), m.group(2), idx,
                           {"name": m.group(2), "fields": [], "line": idx}
                           if m.group(1) == "message" else
                           {"name": m.group(2), "values": [], "line": idx}]
                    self.order.append((m.group(1), m.group(2)))
                    continue
                if c == "":
                    if comment.strip():
                        self.comment_only += 1
                    continue
                if TOP_RE.match(c):
                    self.top_level.append(c)
                    continue
                raise ShapeError("proto 行 %d：顶层结构未解析 ⇒ %r" % (idx, c))
            kind, name, _, obj = blk
            if c == "}":
                if kind == "message":
                    self.messages.append(obj)
                else:
                    self.enums.append(obj)
                blk = None
                continue
            if c == "":
                if comment.strip():
                    self.comment_only += 1
                continue
            if kind == "message":
                m = FIELD_RE.match(c)
                if not m:
                    raise ShapeError("proto 行 %d（message %s）：字段形态未解析 ⇒ %r"
                                     % (idx, name, c))
                req = REQ_RE.match(comment.strip()) if sep else None
                obj["fields"].append({
                    "name": m.group(3), "num": int(m.group(4)),
                    "type": m.group(2), "repeated": bool(m.group(1)),
                    "req": req.group(1) if req else None, "line": idx,
                })
            else:
                m = ENUMVAL_RE.match(c)
                if not m:
                    raise ShapeError("proto 行 %d（enum %s）：枚举成员形态未解析 ⇒ %r"
                                     % (idx, name, c))
                obj["values"].append((m.group(1), int(m.group(2), 0), idx))
        if blk is not None:
            raise ShapeError("proto 行 %d：块 `%s %s` 未闭合" % (blk[2], blk[0], blk[1]))


# =============================================================================
# 比对
# =============================================================================

def enum_prefix(enum_name):
    return re.sub(r"(?<!^)(?=[A-Z])", "_", enum_name).upper() + "_"


def expected_value_name(enum_name, arch_name):
    if arch_name == "*_UNSET":
        return enum_prefix(enum_name) + "UNSET"
    exc = ENUM_PREFIX_EXCEPTIONS.get(enum_name, ())
    if arch_name in exc:
        return arch_name
    return enum_prefix(enum_name) + arch_name


def derive_msg_name(frozen):
    for pre, out in (("MSG_", "Msg"), ("CMD_", "Cmd"), ("EVT_", "Evt")):
        if frozen.startswith(pre):
            rest = frozen[len(pre):]
            return out + "".join(t.capitalize() for t in rest.split("_"))
    return None


def compare(arch, proto):
    """返回 (drift: [str], notes: [str])；结构性失败由 ShapeError 冒泡。"""
    drift, notes = [], []

    def bad(msg):
        drift.append(msg)

    # ---- ⑤ 自洽计数：§4.2.8 内部 + 声明值 --------------------------------
    sec_sum = {}
    for sec in ("4.2.8.2", "4.2.8.3", "4.2.8.4"):
        rows = [r for r in arch.top if r[0] == sec]
        calc = sum(len(r[3]) for r in rows)
        dsum = sum(r[4] for r in rows)
        sec_sum[sec] = calc
        if calc != dsum:
            bad("[⑤] %s：字段数「列声明合计」%d ≠ 字段条目实算 %d" % (sec, dsum, calc))
        if arch.sec_subtotals[sec] != calc:
            bad("[⑤] %s：小计行 %s ≠ 实算 %d"
                % (sec, arch.sec_subtotals[sec], calc))
        if arch.sec_hcounts[sec] != len(rows):
            bad("[⑤] %s：标题声明 %s 条 ≠ 实际消息行 %d 条"
                % (sec, arch.sec_hcounts[sec], len(rows)))
        if calc != SEC_EXPECT[sec]:
            bad("[⑤] %s：字段合计 %d ≠ 预期 %d" % (sec, calc, SEC_EXPECT[sec]))
    if sum(sec_sum.values()) != TOP_TOTAL:
        bad("[⑤] 顶层字段合计 %d ≠ %d" % (sum(sec_sum.values()), TOP_TOTAL))
    if arch.declared.get("top_total") != TOP_TOTAL:
        bad("[⑤] §4.2.8.1 声明顶层总量 %s ≠ %d"
            % (arch.declared.get("top_total"), TOP_TOTAL))
    if arch.declared.get("top_split") != [SEC_EXPECT["4.2.8.2"], SEC_EXPECT["4.2.8.3"],
                                          SEC_EXPECT["4.2.8.4"]]:
        bad("[⑤] §4.2.8.1 分项 %s ≠ [38, 77, 99]" % (arch.declared.get("top_split"),))
    if arch.declared.get("top_msg_count") != TOP_MSG_COUNT:
        bad("[⑤] §4.2.8.1 声明消息数 %s ≠ %d"
            % (arch.declared.get("top_msg_count"), TOP_MSG_COUNT))
    if len(arch.top) != TOP_MSG_COUNT:
        bad("[⑤] §4.2.8 顶层消息行 %d 条 ≠ %d 条" % (len(arch.top), TOP_MSG_COUNT))
    comp_calc = sum(len(c[1]) for c in arch.composite)
    comp_decl = sum(c[2] for c in arch.composite)
    if comp_calc != comp_decl:
        bad("[⑤] §4.2.8.6：字段数列声明合计 %d ≠ 实算 %d" % (comp_decl, comp_calc))
    if comp_calc != COMPOSITE_TOTAL:
        bad("[⑤] §4.2.8.6 复合字段合计 %d ≠ %d" % (comp_calc, COMPOSITE_TOTAL))
    for sec, rows, label in (
            ("顶层", arch.top, "message"),
            ("复合", [(None, None, c[0], c[1], c[2]) for c in arch.composite], "message")):
        for r in rows:
            f = r[3]
            nums = sorted(x["num"] for x in f)
            if nums != list(range(1, len(f) + 1)):
                bad("[⑤] %s %s：字段号非 `1..n` 连续 ⇒ %s" % (sec, r[2], nums))

    # ---- ① 消息集 / 顺序 ---------------------------------------------------
    arch_order = [r[2] for r in arch.top] + [c[0] for c in arch.composite]
    proto_order = [n for k, n in proto.order if k == "message"]
    if arch_order != proto_order:
        only_arch = [n for n in arch_order if n not in proto_order]
        only_proto = [n for n in proto_order if n not in arch_order]
        if only_arch or only_proto:
            bad("[①] 消息集不一致：仅 ARCH 有 %s；仅 proto 有 %s" % (only_arch, only_proto))
        if set(arch_order) == set(proto_order):
            bad("[①] 消息**声明顺序**不一致：ARCH %s / proto %s"
                % (arch_order[:5], proto_order[:5]))

    # ---- ② ③ 字段三元组 / 必填性 -------------------------------------------
    arch_msgs = {}
    for sec, code, name, fields, dcnt in arch.top:
        arch_msgs[name] = fields
    for name, fields, dcnt in arch.composite:
        arch_msgs[name] = fields
    pmsgs = {m["name"]: m for m in proto.messages}
    for name, afields in arch_msgs.items():
        pm = pmsgs.get(name)
        if pm is None:
            bad("[①] proto 缺 message `%s`" % name)
            continue
        pf = pm["fields"]
        if len(afields) != len(pf):
            bad("[②] `%s` 字段数：ARCH %d ≠ proto %d"
                % (name, len(afields), len(pf)))
        for i in range(min(len(afields), len(pf))):
            a, p = afields[i], pf[i]
            at = ("repeated " if a["repeated"] else "") + a["type"]
            pt = ("repeated " if p["repeated"] else "") + p["type"]
            if a["name"] != p["name"]:
                bad("[②] `%s` 第 %d 字段名：ARCH `%s` ≠ proto `%s`"
                    % (name, i + 1, a["name"], p["name"]))
            if a["num"] != p["num"]:
                bad("[②] `%s.%s` 字段号：ARCH %d ≠ proto %d"
                    % (name, a["name"], a["num"], p["num"]))
            if at != pt:
                bad("[②] `%s.%s` 类型：ARCH `%s` ≠ proto `%s`"
                    % (name, a["name"], at, pt))
            if p["req"] is None:
                bad("[③] `%s.%s`：proto 字段缺必填性尾注释（无法与 §4.2.8 `%s` 比对）"
                    % (name, a["name"], a["req"]))
            elif a["req"] != p["req"]:
                bad("[③] `%s.%s` 必填性：ARCH `%s` ≠ proto `%s`"
                    % (name, a["name"], a["req"], p["req"]))
    for m in proto.messages:
        if m["name"] not in arch_msgs:
            bad("[①] proto 多出 message `%s`（§4.2.8 无对应）" % m["name"])

    # ---- ④ 枚举 -------------------------------------------------------------
    m43 = arch.m43
    if arch.declared.get("msgtype_count") != len(m43):
        bad("[④] §4.2.8.7 声明 `MsgType` 冻结名 %s 个 ≠ §4.2.3 实有 %d 条"
            % (arch.declared.get("msgtype_count"), len(m43)))
    ends = arch.declared.get("msgtype_ends") or []
    if m43 and ends != [m43[0], m43[-1]]:
        bad("[④] §4.2.8.7 `MsgType` 行首尾示例 %s ≠ §4.2.3 首尾 %s"
            % (ends, [m43[0], m43[-1]]))
    # §4.2.3 类型码 ↔ §4.2.8.2/.3/.4 类型码 列（两侧须一致）
    # §4.2.3 用**冻结名**（`MSG_HELLO`），§4.2.8 用 **message 名**（`MsgHello`）
    # ⇒ 经 ⑥ 命名派生对齐；派生不出者由 §4.2.3 侧单独报出（见下）。
    code43 = {}
    for frozen, code in m43:
        derived = derive_msg_name(frozen)
        if derived is None:
            bad("[⑥] §4.2.3 冻结名 `%s` 不符 [MSG_|CMD_|EVT_] 前缀" % frozen)
            continue
        code43[derived] = code
    for sec, code, name, fields, dcnt in arch.top:
        if code43.get(name) != code:
            bad("[④] `%s` 类型码：§4.2.3 %s ≠ §4.2.8 %s"
                % (name, code43.get(name), code))
    arch_enums = dict(arch.enums)
    arch_enums["MsgType"] = [("MSG_TYPE_UNSET", 0)] + m43
    penums = {e["name"]: e for e in proto.enums}
    if set(arch_enums) != set(penums):
        bad("[④] 枚举集合不一致：仅 ARCH 有 %s；仅 proto 有 %s"
            % (sorted(set(arch_enums) - set(penums)), sorted(set(penums) - set(arch_enums))))
    for en, avals in arch_enums.items():
        pe = penums.get(en)
        if pe is None:
            continue
        pvals = [(n, v) for n, v, _ in pe["values"]]
        if en == "MsgType":
            aexp = avals
        else:
            aexp = [(expected_value_name(en, n), v) for n, v in avals]
        if aexp != pvals:
            if [n for n, _ in aexp] == [n for n, _ in pvals]:
                bad("[④] 枚举 `%s` **取值**不一致：ARCH %s ≠ proto %s"
                    % (en, [v for _, v in aexp], [v for _, v in pvals]))
            else:
                bad("[④] 枚举 `%s` 逐值不一致：ARCH(展开前缀) %s ≠ proto %s"
                    % (en, aexp, pvals))

    # ---- ⑥ 命名派生 ---------------------------------------------------------
    for frozen, code in m43:
        derived = derive_msg_name(frozen)
        if derived is None:
            continue  # 已在 [④] 报出「不符前缀」
        if derived not in pmsgs:
            bad("[⑥] §4.2.3 `%s` 派生 `%s`，proto 无此 message" % (frozen, derived))

    # ---- §4.2.8.5 条件必填清单（只判「清单行存在 + 可对应」，不判条件语义） ----
    cond_fields = set()
    for sec, code, name, fields, dcnt in arch.top:
        for f in fields:
            if f["req"] == "条件":
                cond_fields.add((name, f["name"]))
    for name, fields, dcnt in arch.composite:
        for f in fields:
            if f["req"] == "条件":
                cond_fields.add((name, f["name"]))
    cond_rows = set(arch.cond_rows)
    if cond_fields != cond_rows:
        bad("[③] §4.2.8.5 清单 ≠ 表体 `条件` 字段集合："
            "缺清单行 %s；清单多出 %s"
            % (sorted(cond_fields - cond_rows), sorted(cond_rows - cond_fields)))

    # ---- 汇总注记 -----------------------------------------------------------
    notes.append("ARCH 顶层消息 %d 条 / 复合 %d 条 / 枚举 %d 个；proto message %d 个 / enum %d 个"
                 % (len(arch.top), len(arch.composite), len(arch_enums),
                    len(proto.messages), len(proto.enums)))
    notes.append("字段合计：顶层 %d + 复合 %d = %d"
                 % (sum(sec_sum.values()), comp_calc,
                    sum(sec_sum.values()) + comp_calc))
    notes.append("§4.2.8.5 `条件` 字段 %d 项（清单行 %d 行）"
                 % (len(cond_fields), len(cond_rows)))
    return drift, notes


# =============================================================================
# 入口
# =============================================================================

def main(argv):
    arch_path, proto_path = "docs/ARCHITECTURE.md", "proto/hgcp.proto"
    arch_exp = proto_exp = False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("-h", "--help"):
            sys.stdout.write(USAGE)
            return 0
        if a == "--arch" or a == "--proto":
            if i + 1 >= len(argv):
                sys.stderr.write("错误：%s 缺少参数\n" % a)
                return 2
            if a == "--arch":
                arch_path, arch_exp = argv[i + 1], True
            else:
                proto_path, proto_exp = argv[i + 1], True
            i += 2
            continue
        if a.startswith("--arch="):
            arch_path, arch_exp, i = a[len("--arch="):], True, i + 1
            continue
        if a.startswith("--proto="):
            proto_path, proto_exp, i = a[len("--proto="):], True, i + 1
            continue
        sys.stderr.write("错误：未知参数 %s\n" % a)
        sys.stderr.write(USAGE)
        return 2

    sys.stdout.write("== E7 `ARCHITECTURE.md §4.2.8` ↔ `proto/hgcp.proto` 一致性扫描 ==\n")
    sys.stdout.write("架构文档  : %s\n" % arch_path)
    sys.stdout.write("线格式    : %s\n" % proto_path)
    sys.stdout.write("判据      : ①消息集(含序) ②字段三元组 ③必填性 ④枚举(逐值) "
                     "⑤自洽计数 ⑥命名派生\n")
    sys.stdout.write("不覆盖    : §4.2.8.5 条件语义 / 散文注记 / 附录 C 留痕 / "
                     "不变量(40·62·VER·FR) / §4.2.4 说明 / 线格式编码\n")
    sys.stdout.write("-" * 67 + "\n")

    for path, exp, label in ((arch_path, arch_exp, "--arch"), (proto_path, proto_exp, "--proto")):
        if not os.path.isfile(path):
            if exp:
                sys.stderr.write("错误：%s 路径不存在（显式路径）：%s\n" % (label, path))
                return 2
            sys.stdout.write("SKIP: 默认路径 %s 不存在（组件未铺开）—— 退出码 0\n" % path)
            return 0

    try:
        arch = Arch(read_lines(arch_path))
        proto = Proto(read_lines(proto_path))
        drift, notes = compare(arch, proto)
    except ShapeError as exc:
        sys.stdout.write("未解析行 / 制品形态：\n  %s\n" % exc)
        sys.stdout.write("-" * 67 + "\n")
        sys.stdout.write("E7 用法/前置错误：制品出现本判据不认识的形态 ⇒ "
                         "判据未能真正执行（退出码 2）\n")
        return 2

    for n in notes:
        sys.stdout.write("  · %s\n" % n)
    sys.stdout.write("-" * 67 + "\n")
    if not drift:
        sys.stdout.write("E7 通过：§4.2.8 与 proto 逐条一致（消息 %d+%d、字段 %d+%d、枚举 %d）"
                         "（退出码 0）\n"
                         % (len(arch.top), len(arch.composite),
                            sum(SEC_EXPECT.values()), COMPOSITE_TOTAL, len(arch.enums)))
        return 0
    sys.stdout.write("E7 失败：发现 %d 处不一致（退出码 1）\n" % len(drift))
    for d in drift:
        sys.stdout.write("  %s\n" % d)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
