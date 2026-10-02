#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# =============================================================================
# scan_contracts_payload.py — E8 判据：java/CONTRACTS.md §3.2.1 payload 键 ↔ proto/hgcp.proto 字段名一致性
#
# 承载：docs/ARCHITECTURE.md §3.0.2 判据表 E8 行（T-170 立项）。
#
# 立项理由（缺口实证，2026-10-01 T-170）：
#   CH-019 变更横跨四制品（ARCHITECTURE.md / proto / Java 生成码 / CONTRACTS），
#   而 E7 只覆盖「ARCH §4.2.8 ↔ proto」一条链。「CONTRACTS §3.2.1 payload 键 ↔
#   proto 字段名」链零守卫：把 §3.2.1 表内 entries[n].has_trading_post 改为
#   合法命名风格的错名 entries[n].hasTradingPost ⇒ bash tools/arch-check/arch-check.sh
#   rc 0（未发现）。根因：全仓无任何测试或判据读取 CONTRACTS.md 的表体内容
#   （java/src/test 内 8 处引用全为 javadoc/@DisplayName 注释提及）。
#   CONTRACTS.md §3.2.1 明文冻结「键名一律直接采用 §4.2.3 冻结的协议字段名，
#   不做二次命名」⇒ 本判据把该冻结固化为持续性门禁。
#
# 用法：
#   tools/arch-check/scan_contracts_payload.py [--contracts <file>] [--proto <file>]
#     --contracts <file>  Java 契约文档（默认 java/CONTRACTS.md）
#     --proto     <file>  线格式定义（默认 proto/hgcp.proto）
#     -h, --help          显示本帮助
#
#   退出码语义（沿用 T-095 统一口径）：
#     0 = 通过；默认路径不存在（组件未铺开）⇒ 打印 SKIP 并退 0
#     1 = 命中（已解析但两侧不一致 ⇒ 架构偏离）
#     2 = 用法 / 前置错误 —— 显式传入的路径不存在，或制品出现本判据不认识的
#         形态（§3.2.1 章节缺失 / 表体行畸形 / proto 字段行未解析 ⇒ 判据对后续行
#         未能真正执行）。后者必须报错、不得静默跳过：静默吞掉未识别行会让
#         「新增指令行 / 新语法」被漏判，正是本判据立项要消灭的失效模式。
#
# 判据（双向差集；逐条机械比对，禁止目测抽样）：
#   按章节锚点 ^#### 3.2.1 定位 §3.2.1（CONTRACTS.md 为活跃制品，禁固定行号；
#   取该标题行至下一个 ^####/^###/^## 标题之间的表体行），解析表内三条指令的
#   payload 键集合，与 proto/hgcp.proto 对应 message 的字段名集合做双向差集：
#     ① CMD_SET_TRADING_SUSPEND 直键            ↔ message CmdSetTradingSuspend 字段名
#     ② CMD_UPDATE_QUOTA 的 entries[n].<字段>   ↔ message QuotaEntry 字段名
#       （首行说明列反引号内字段名清单 = QuotaEntry 键的 CONTRACTS 侧落点）
#     ③ CMD_UPDATE_PERMISSION 的 entries[n].X   ↔ message PermissionEntry 字段名
#   「proto 有而 CONTRACTS 无」与「CONTRACTS 有而 proto 无」皆须为空。
#   键名若二次命名（如 camelCase），落在「CONTRACTS 有而 proto 无」侧 ⇒ 退 1。
#
# ⚠️ 显式「不覆盖」（不得含糊带过；下列各项不由本判据保证）：
#   N1. 取值/必填性语义 —— 只判键名集合一致性，不判必填/选填、取值域、说明文字。
#   N2. §3.2 旧版表 —— §3.2 主体表含已废止的 suspend/scope/suspend_id 行
#       （§3.2.1 下方明载「现行以 §3.2.1 为准」）；本判据只锚 §3.2.1，
#       旧表内旧键不被拦下（属「已废止但保留原文」的体例）。
#   N3. 字段号/类型/必填性 —— 由 E7（ARCH §4.2.8 ↔ proto）守卫；本判据只对齐
#       CONTRACTS 键名 ↔ proto 字段名，不重复 E7 比对。
#   N4. 说明列散文 —— > 引用块不参与；唯指令行说明列反引号内字段名清单参与
#       （CMD_UPDATE_QUOTA 行，见判据 ②）。
#   N5. 表内新增指令 —— §3.2.1 表内出现未登记指令名 ⇒ 退 2（判据须随表扩展，
#       不得静默跳过）。
# =============================================================================

import os
import re
import sys

DEFAULT_CONTRACTS = "java/CONTRACTS.md"
DEFAULT_PROTO = "proto/hgcp.proto"

# §3.2.1 表内当前冻结的三条指令 ↔ proto message 名。
# 将来表内新增指令 ⇒ 在此登记扩展；未登记指令名 ⇒ ShapeError（退 2，N5）。
SPECS = {
    "CMD_SET_TRADING_SUSPEND": "CmdSetTradingSuspend",
    "CMD_UPDATE_QUOTA": "QuotaEntry",
    "CMD_UPDATE_PERMISSION": "PermissionEntry",
}

# §3.2.1 明载：通用 param_version 不放 payload（用 ParamChange.paramVersion 承载），
# 三个指令 message 的 param_version 是帧级字段、不经 payload 传输。
KEYS_EXCLUDED = {"param_version"}

USAGE = """用法：scan_contracts_payload.py [--contracts <file>] [--proto <file>]
  --contracts <file>  Java 契约文档，默认 java/CONTRACTS.md
  --proto     <file>  线格式定义，默认 proto/hgcp.proto
  -h, --help          显示本帮助
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
    """切分 markdown 表格行；不在反引号内的 | 才作分隔符（兼容单元格内转义 \\|）。"""
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    cells, cur, in_code = [], "", False
    i = 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s) and s[i + 1] == "|":
            cur += "|"
            i += 2
            continue
        if c == "`":
            in_code = not in_code
            cur += c
        elif c == "|" and not in_code:
            cells.append(cur.strip())
            cur = ""
        else:
            cur += c
        i += 1
    cells.append(cur.strip())
    return cells


def extract_backticks(text):
    """提取文本内所有反引号片段。"""
    return re.findall(r"\`([^\`]+)\`", text)


# =============================================================================
# CONTRACTS §3.2.1 侧解析
# =============================================================================

def extract_section_321(lines):
    """按章节锚点 ^#### 3.2.1 求段（禁固定行号），至下一个标题行。

    ⚠️ CONTRACTS.md 内 §3.2.1 标题存在两处（实测 2026-10-01）：
      前一处 = 「HGC-1.1 历史版，已被取代」（含已废止旧键 scope/suspend/suspend_id）；
      后一处 = 现行冻结版（HGC-1.2/HGC-1.6）。
    取最后一处为现行权威段（「时序取代」体例：live 陈述在后）；并以
    「段内须出现 CMD_UPDATE_PERMISSION」作形态守卫——若锚到历史版（其表内无
    CMD_UPDATE_PERMISSION），守卫触发 ShapeError（退 2），不会静默锚错段。
    """
    starts = [idx for idx, ln in enumerate(lines) if re.match(r"^#### 3\.2\.1 ", ln)]
    if not starts:
        raise ShapeError("CONTRACTS.md 未找到章节锚点「#### 3.2.1 」（§3.2.1 payload 键名约定）")
    start = starts[-1]
    end = len(lines)
    for idx in range(start + 1, len(lines)):
        if re.match(r"^#### ", lines[idx]) or re.match(r"^### ", lines[idx]) or re.match(r"^## ", lines[idx]):
            end = idx
            break
    section = lines[start:end]
    if not any("CMD_UPDATE_PERMISSION" in ln for ln in section):
        raise ShapeError(
            "§3.2.1 段（锚点行 %d 起）内未出现 CMD_UPDATE_PERMISSION —— " % (start + 1)
            + "可能锚到历史版段（CONTRACTS.md 存在多处同名 §3.2.1 标题，取最后一处；请人工核对章节结构）")
    return section


def is_placeholder(frag):
    """entries[n].<字段> 占位写法：不参与键集合。"""
    return bool(re.match(r"^entries\[n\]\.<", frag)) or frag == "entries[n].<字段>"


def parse_contracts_keys(section_lines):
    """解析 §3.2.1 表体 ⇒ {指令名: set(键名)}。

    表形态（实测 2026-10-01，T-170）：指令列非空 = 该指令首行；空 = 续键行。
    CMD_UPDATE_QUOTA 首行说明列反引号内的字段名清单亦参与集合（N4 例外）。
    """
    result = {}
    current = None
    header_seen = False
    for ln in section_lines:
        s = ln.strip()
        if not s.startswith("|"):
            continue
        cells = split_cells(s)
        if not header_seen:
            if len(cells) < 2 or "payload" not in cells[1]:
                raise ShapeError("§3.2.1 表头形态未解析：%r（期待「指令 | payload 键 | 说明」）" % s)
            header_seen = True
            continue
        if len(cells) >= 2 and set(cells[0]) <= set("-: ") and set(cells[1]) <= set("-: "):
            continue  # 分隔行 |---|---|---|
        inst_cell = cells[0].strip() if cells else ""
        key_cell = cells[1].strip() if len(cells) > 1 else ""
        note_cell = cells[2] if len(cells) > 2 else ""
        if inst_cell:
            if inst_cell == "通用":
                continue  # 通用行（param_version 不放 payload）——键列非指令键，跳过
            m = re.search(r"\`([A-Z][A-Z0-9_]+)\`", inst_cell)
            if not m:
                raise ShapeError("§3.2.1 指令行形态未解析：%r" % ln)
            name = m.group(1)
            if name not in SPECS:
                raise ShapeError("§3.2.1 出现本判据未登记的指令「%s」⇒ 判据须扩展（N5）；行：%r" % (name, ln))
            current = name
            result.setdefault(current, set())
            for frag in extract_backticks(key_cell):
                if is_placeholder(frag):
                    continue
                if re.match(r"^[a-z][a-z0-9_]*$", frag):
                    result[current].add(frag)
            if name == "CMD_UPDATE_QUOTA":
                for frag in extract_backticks(note_cell):
                    if re.match(r"^[a-z][a-z0-9_]*$", frag):
                        result[current].add(frag)
        else:
            if current is None:
                raise ShapeError("§3.2.1 续键行先于任何指令行：%r" % ln)
            for frag in extract_backticks(key_cell):
                m = re.match(r"^entries\[n\]\.([a-z][a-z0-9_]*)$", frag)
                if m:
                    result[current].add(m.group(1))
                elif re.match(r"^[a-z][a-z0-9_]*$", frag):
                    result[current].add(frag)  # 直键（如 CMD_SET_TRADING_SUSPEND 的 scan_scope）
    if not header_seen:
        raise ShapeError("§3.2.1 段内未找到表体（无表头行）")
    for name in SPECS:
        if name not in result:
            raise ShapeError("§3.2.1 表内未找到指令「%s」的行（判据无法执行，N5）" % name)
    return result


# =============================================================================
# proto 侧解析
# =============================================================================

def parse_proto_messages(lines):
    """解析 proto ⇒ {message 名: [字段名,...]}（保持声明序）。
    体内未识别形态 ⇒ ShapeError（不得静默跳过）；enum/顶层注释/空行跳过。"""
    messages = {}
    current = None
    for idx, ln in enumerate(lines, 1):
        s = ln.strip()
        if current is None:
            m = re.match(r"^message\s+([A-Za-z][A-Za-z0-9_]*)\s*\{", s)
            if m:
                current = m.group(1)
                messages[current] = []
                continue
            if s == "}" :  # enum/service 体内不解析，遇闭括号复位
                continue
            if (re.match(r"^(enum|service|message)\s+[A-Za-z_]", s)
                    or re.match(r"^[A-Z][A-Z0-9_]*\s*=\s*[-?0-9a-fA-FxX]+\s*;", s)
                    or s.startswith(("syntax", "package", "option", "import", "//"))
                    or s == ""):
                continue
            if s.startswith("/*") or s.startswith("*"):
                continue
            raise ShapeError("proto 行 %d：顶层形态未解析 ⇒ %r" % (idx, ln))
        if s == "}":
            current = None
            continue
        if not s or s.startswith("//") or s.startswith("/*") or s.startswith("*"):
            continue
        m = re.match(r"^(repeated\s+)?[A-Za-z_][A-Za-z0-9_.]*\s+([a-z][a-z0-9_]*)\s*=\s*\d+\s*;", s)
        if m:
            messages[current].append(m.group(2))
            continue
        raise ShapeError("proto 行 %d（message %s）：字段形态未解析 ⇒ %r" % (idx, current, ln))
    return messages


# =============================================================================
# 双向差集比对
# =============================================================================

def compare(contracts_keys, proto_msgs):
    drift = []
    for cmd, msg in sorted(SPECS.items()):
        if msg not in proto_msgs:
            drift.append("[%s] proto 内未找到 message %s（判据无法执行）" % (cmd, msg))
            continue
        want = set(proto_msgs[msg]) - KEYS_EXCLUDED
        have = contracts_keys.get(cmd, set()) - KEYS_EXCLUDED
        for k in sorted(want - have):
            drift.append("[%s] proto 字段 `%s`（%s）在 CONTRACTS §3.2.1 无对应 payload 键（proto 有而 CONTRACTS 无）"
                         % (cmd, k, msg))
        for k in sorted(have - want):
            drift.append("[%s] CONTRACTS §3.2.1 payload 键 `%s` 在 proto %s 无对应字段（CONTRACTS 有而 proto 无；"
                         "若系二次命名即为本判据要拦的缺陷）" % (cmd, k, msg))
    return drift


# =============================================================================
# 入口
# =============================================================================

def main(argv):
    contracts_path, proto_path = DEFAULT_CONTRACTS, DEFAULT_PROTO
    contracts_exp = proto_exp = False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("-h", "--help"):
            sys.stdout.write(USAGE)
            return 0
        if a in ("--contracts", "--proto"):
            if i + 1 >= len(argv):
                sys.stderr.write("错误：%s 缺少参数\n" % a)
                return 2
            if a == "--contracts":
                contracts_path, contracts_exp = argv[i + 1], True
            else:
                proto_path, proto_exp = argv[i + 1], True
            i += 2
            continue
        if a.startswith("--contracts="):
            contracts_path, contracts_exp, i = a[len("--contracts="):], True, i + 1
            continue
        if a.startswith("--proto="):
            proto_path, proto_exp, i = a[len("--proto="):], True, i + 1
            continue
        sys.stderr.write("错误：未知参数 %s\n" % a)
        sys.stderr.write(USAGE)
        return 2

    sys.stdout.write("== E8 java/CONTRACTS.md §3.2.1 payload 键 ↔ proto/hgcp.proto 字段名一致性扫描 ==\n")
    sys.stdout.write("契约文档  : %s\n" % contracts_path)
    sys.stdout.write("线格式    : %s\n" % proto_path)
    sys.stdout.write("判据      : 双向差集（直键 / entries[n].*）——"
                     "CMD_SET_TRADING_SUSPEND ↔ CmdSetTradingSuspend、"
                     "CMD_UPDATE_QUOTA ↔ QuotaEntry、CMD_UPDATE_PERMISSION ↔ PermissionEntry\n")
    sys.stdout.write("不覆盖    : 取值/必填性语义 / §3.2 旧版表(含已废止 suspend/scope/suspend_id) / "
                     "字段号·类型(E7) / 说明列散文 / 表内新增未登记指令(⇒2)\n")
    sys.stdout.write("-" * 67 + "\n")

    for path, exp, label in ((contracts_path, contracts_exp, "--contracts"), (proto_path, proto_exp, "--proto")):
        if not os.path.isfile(path):
            if exp:
                sys.stderr.write("错误：%s 路径不存在（显式路径）：%s\n" % (label, path))
                return 2
            sys.stdout.write("SKIP: 默认路径 %s 不存在（组件未铺开）—— 退出码 0\n" % path)
            return 0

    try:
        section = extract_section_321(read_lines(contracts_path))
        contracts_keys = parse_contracts_keys(section)
        proto_msgs = parse_proto_messages(read_lines(proto_path))
        drift = compare(contracts_keys, proto_msgs)
    except ShapeError as exc:
        sys.stdout.write("未解析行 / 制品形态：\n  %s\n" % exc)
        sys.stdout.write("-" * 67 + "\n")
        sys.stdout.write("E8 用法/前置错误：制品出现本判据不认识的形态 ⇒ 判据未能真正执行（退出码 2）\n")
        return 2

    sys.stdout.write("-" * 67 + "\n")
    if not drift:
        detail = "; ".join("%s=%d 键" % (cmd, len(ks - KEYS_EXCLUDED)) for cmd, ks in sorted(contracts_keys.items()))
        sys.stdout.write("E8 通过：§3.2.1 payload 键与 proto 字段名逐条一致（%s）（退出码 0）\n" % detail)
        return 0
    sys.stdout.write("E8 失败：发现 %d 处不一致（退出码 1）\n" % len(drift))
    for d in drift:
        sys.stdout.write("  %s\n" % d)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
