#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# =============================================================================
# scan_frozen_invariants.py — E9 判据：HGCP 四项**冻结-不变量**跨制品一致性
#
# 承载：docs/ARCHITECTURE.md **§3.0.2 判据表 E9 行**（`T-122` 立项；
#       `te-verify-proto2` 建议 ⇒ `tl-t121` 以 `U-3` 上报 ⇒ 编排者 2026-09-22 受理）。
#
# 为什么需要本判据（立项理由）：
#   `E7`（`scan_hgcp_schema.py`）**显式不覆盖**「冻结-不变量」——其头注 N4 明载
#   「消息集 `40` / 错误码 `62` / `VER` / `FR` 计数 —— 另立任务（已立号 `T-122`）」。
#   若不变量无判据，则「改一条枚举值 / 删一行错误码 / 改 `VER` 字面量 / 增删一条 FR」
#   都只会让**声明值与实际值**悄悄背离，而既有测试与 E1–E8 全绿（E7 只判
#   §4.2.8 ↔ proto 的**字段级**一致性，两处同改即一致、不变量照样漂移）。
#   本判据把「四项冻结不变量」固化为**跨制品、可持续**的门禁。
#
# 四项不变量（`ARCHITECTURE.md §4.2.8` 声明；本节一律不改动）：
#   ① 消息集        = **40**（元协议 9 + 控制指令 17 + 事件流 14）
#   ② 组件错误码    = **62**
#   ③ 线格式 `VER`  = **`0x0100`**
#   ④ `FR` 计数     = **184**
#
# 用法：
#   tools/arch-check/scan_frozen_invariants.py [--arch <file>] [--proto <file>]
#                                              [--protocol-h <file>] [--java-consts <file>]
#                                              [--java-frame <file>] [--req-dir <dir>]
#     --arch        架构文档            （默认 docs/ARCHITECTURE.md）
#     --proto       线格式定义          （默认 proto/hgcp.proto）
#     --protocol-h  C++ 协议常量        （默认 cpp/src/hgcp/include/hg/hgcp/protocol.h）
#     --java-consts Java 类型码常量      （默认 java/src/main/java/com/hedgeguard/bridge/HgcpConstants.java）
#     --java-frame  Java 帧常量         （默认 java/src/main/java/com/hedgeguard/bridge/HgcpFrame.java）
#     --req-dir     需求文档目录        （默认 docs/REQUIREMENTS）
#     -h, --help    显示本帮助
#
#   退出码语义（沿用 `T-095` 统一口径；`E7`/`E8` 同）：
#     0 = 通过；**默认路径**不存在（组件未铺开）⇒ 打印 `SKIP` 并退 `0`
#     1 = 命中（**已解析但值不符** ⇒ 架构偏离 / 不变量漂移）
#     2 = 用法 / 前置错误 —— **显式传入**的路径不存在，或**制品出现本判据不认识的
#         形态**（锚点章节缺失 / 表体行畸形 / 声明行缺失 ⇒ 判据对后续行未能真正执行）。
#         后者必须报错、不得静默跳过：静默吞掉未识别形态会让新语法被漏判，
#         正是本判据立项要消灭的失效模式（与 E7/E8 同一纪律）。
#
# 判据（四项不变量 × 逐来源机械比对；**禁止目测抽样**）：
#   ① 消息集 40：
#       a. `proto/hgcp.proto` 的 `enum MsgType` 内 `= 0x....` 有效值数（不含 `*_UNSET`）
#       b. `ARCHITECTURE.md §4.2.8.2 + §4.2.8.3 + §4.2.8.4` 表体 message 行数之和
#       c. `ARCHITECTURE.md §4.2.3` 标题「共 40 种」
#       d. `ARCHITECTURE.md §4.2.8` 声明行「消息集 **40**」
#       e. `HgcpConstants.java` 的 `MESSAGE_TYPE_COUNT`
#   ② 错误码 62：
#       a. `ARCHITECTURE.md §4.3.2` 表体 `| `0x....` |` 行数
#       b. `protocol.h` 的 `enum class ErrorCode` 内 `= 0x....` 值数
#       c. `ARCHITECTURE.md §4.2.8` 声明行「组件错误码 **62**」
#   ③ 线格式 VER 0x0100：
#       a. `protocol.h` 的 `kWireVersion`
#       b. `HgcpFrame.java` 的 `WIRE_VERSION`
#       c. `ARCHITECTURE.md §4.2.2` 的 `VER` 行「本版 = **`0x0100`**」
#       d. `ARCHITECTURE.md §4.2.8` 声明行「线格式 `VER` = **`0x0100`**」
#   ④ FR 计数 184：
#       a. `docs/REQUIREMENTS/*.md` 的 FR 定义标题数之和（`^#{2,4} *FR-<大写>-<数字>`）
#       b. `ARCHITECTURE.md §1.1` 的「184 条 FR」
#       c. `ARCHITECTURE.md` 的「FR 覆盖统计：共 **184 条**」
#       d. `ARCHITECTURE.md §4.2.8` 声明行「`FR` 计数 **184**」
#   任一项**来源之间不一致**，或与冻结值不符 ⇒ 退 `1`（精确点名来源与实测值）。
#
# ⚠️ **显式「不覆盖」（不得含糊带过；下列各项不由本判据保证）**：
#   N1. **字段级 schema**（字段号 / 类型 / 必填性 / 枚举逐值）—— 归 `E7`；本判据只
#       比「**总量**」，不重复 E7 的逐字段比对。
#   N2. **错误码名称 / 含义 / 触发条件** —— 只比表体**行数与取值集合规模**，
#       不判某码的语义列是否被改写（属人工审查 + `E7` 之外的文档纪律）。
#   N3. **FR 的正文与验收条件** —— 只比**定义标题条数**，不判单条 FR 内容变更。
#   N4. **`§4.2.8` 之外的不变量复述**（`§13` 变更记录行、`§4.2` 冻结登记块、
#       附录 C 副本）—— 只锚 `§4.2.8` 的声明行与 §1.1 / §4.2.2 / §4.2.3 /
#       §4.3.2 / FR 覆盖统计 六处**现行权威面**；历史变更记录行内出现旧值**不判红**
#       （属「只加不改」留痕体例）。
#       ⚠️ **「FR 覆盖统计」行按全文取首个匹配**（该句在现行制品中全文唯一；
#       若未来前置出现同形文本，须改为章节锚定——已由复核席登记为观察 O-2/O-3）。
#   N5. **`proto` 的 `syntax` / `package` / `option`** 与 wire type —— 非本判据范围。
# =============================================================================

import glob
import os
import re
import sys

DEFAULT_ARCH = "docs/ARCHITECTURE.md"
DEFAULT_PROTO = "proto/hgcp.proto"
DEFAULT_PROTOCOL_H = "cpp/src/hgcp/include/hg/hgcp/protocol.h"
DEFAULT_JAVA_CONSTS = "java/src/main/java/com/hedgeguard/bridge/HgcpConstants.java"
DEFAULT_JAVA_FRAME = "java/src/main/java/com/hedgeguard/bridge/HgcpFrame.java"
DEFAULT_REQ_DIR = "docs/REQUIREMENTS"

# ---- 冻结值（`ARCHITECTURE.md §4.2.8`「本节一律不改动」；改此处须走 `CH-xxx`）----
MSG_TOTAL = 40
ERR_TOTAL = 62
WIRE_VER = 0x0100
FR_TOTAL = 184

USAGE = """用法：scan_frozen_invariants.py [--arch FILE] [--proto FILE] [--protocol-h FILE]
                              [--java-consts FILE] [--java-frame FILE] [--req-dir DIR]
  --arch        架构文档            （默认 docs/ARCHITECTURE.md）
  --proto       线格式定义          （默认 proto/hgcp.proto）
  --protocol-h  C++ 协议常量        （默认 cpp/src/hgcp/include/hg/hgcp/protocol.h）
  --java-consts Java 类型码常量      （默认 java/src/main/java/com/hedgeguard/bridge/HgcpConstants.java）
  --java-frame  Java 帧常量         （默认 java/src/main/java/com/hedgeguard/bridge/HgcpFrame.java）
  --req-dir     需求文档目录        （默认 docs/REQUIREMENTS）
  -h, --help    显示本帮助
判据：消息集 40 / 组件错误码 62 / 线格式 VER 0x0100 / FR 计数 184，跨制品逐来源互校。
退出码：0 通过或默认路径缺失(SKIP)；1 不变量漂移；2 用法/前置错误
"""


class ShapeError(Exception):
    """制品出现本判据不认识的形态 ⇒ 判据未能真正执行（退 2）。"""


def read_text(path):
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def section(lines, heading_re, label):
    """按章节锚点求段：取标题行至下一个同级或更高级标题之间（禁固定行号）。"""
    rx = re.compile(heading_re)
    start = None
    for i, ln in enumerate(lines):
        if rx.match(ln):
            start = i
            break
    if start is None:
        raise ShapeError("未找到章节锚点「%s」（%s）" % (label, heading_re))
    level = len(lines[start]) - len(lines[start].lstrip("#"))
    end = len(lines)
    for j in range(start + 1, len(lines)):
        m = re.match(r"^(#{1,6})\s", lines[j])
        if m and len(m.group(1)) <= level:
            end = j
            break
    return lines[start:end], start + 1


def parse_proto_msgs(text):
    """proto 内 enum MsgType 的有效值（排除 *_UNSET）。"""
    i = text.find("enum MsgType")
    if i < 0:
        raise ShapeError("proto 内未找到 `enum MsgType`")
    j = text.find("}", i)
    if j < 0:
        raise ShapeError("`enum MsgType` 块未闭合")
    block = text[i:j]
    rows = re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*=\s*(0x[0-9A-Fa-f]+|\d+)\s*;", block, re.M)
    if not rows:
        raise ShapeError("`enum MsgType` 内未解析到任何值")
    vals = [(n, int(v, 0)) for n, v in rows]
    for n, v in vals:
        if not n.endswith("UNSET") and v == 0:
            raise ShapeError("`enum MsgType` 内非 `*_UNSET` 的值 `%s` 为 0（违反 §4.2.8.7）" % n)
    return [(n, v) for n, v in vals if not n.endswith("UNSET")]


def parse_cpp_errorcodes(text):
    i = text.find("enum class ErrorCode")
    if i < 0:
        raise ShapeError("protocol.h 内未找到 `enum class ErrorCode`")
    j = text.find("};", i)
    if j < 0:
        raise ShapeError("`enum class ErrorCode` 块未闭合")
    block = text[i:j]
    vals = re.findall(r"^\s*[A-Za-z_][A-Za-z0-9_]*\s*=\s*(0x[0-9A-Fa-f]+|\d+)\s*,?\s*(?://.*)?$",
                      block, re.M)
    if not vals:
        raise ShapeError("`enum class ErrorCode` 内未解析到任何值")
    return sorted({int(v, 0) for v in vals})


def parse_cpp_wire_ver(text):
    m = re.search(r"kWireVersion\s*=\s*(0x[0-9A-Fa-f]+|\d+)\s*u?\s*;", text)
    if not m:
        raise ShapeError("protocol.h 内未找到 `kWireVersion` 常量")
    return int(m.group(1), 0)


def parse_java_int(text, name):
    m = re.search(r"\b%s\s*=\s*(0x[0-9A-Fa-f]+|\d+)\s*;" % re.escape(name), text)
    if not m:
        raise ShapeError("Java 常量内未找到 `%s`" % name)
    return int(m.group(1), 0)


def parse_section_rows(lines, heading_re, row_re, label, table_head_re, allow_re=None):
    """按章节锚点取段，并解析**首个表体块**（自表头起至首个空行止）。

    纪律（与 E7/E8 同形）：表体块内以 `|` 开头的行，若既不是表头、不是分隔行、
    不在显式允许清单（`allow_re`），又不匹配 `row_re` ⇒ 判为「表体行畸形」
    （`ShapeError` ⇒ 退 2）。不得静默丢弃——静默丢弃正是本判据立项要消灭的失效模式。
    """
    seg, _ = section(lines, heading_re, label)
    allow = [re.compile(r) for r in (allow_re or [])]
    head = re.compile(table_head_re)
    start_i = None
    for i, ln in enumerate(seg):
        if head.match(ln):
            start_i = i
            break
    if start_i is None:
        raise ShapeError("%s：未找到表头行（%s）" % (label, table_head_re))
    rows = []
    base = lines.index(seg[0]) + 1 if seg else 1   # 段内首行的全文行号
    for off, ln in enumerate(seg[start_i:]):
        if ln.strip() == "":
            break                    # 表体块结束
        if not ln.startswith("|"):
            break                    # 非表格行（如「小计」前的说明）⇒ 表体块结束
        if re.match(row_re, ln):
            rows.append(ln)
            continue
        if head.match(ln):
            continue                 # 表头
        if re.match(r"^\|[\s:|-]+\|\s*$", ln):
            continue                 # 分隔行
        if any(r.match(ln) for r in allow):
            continue                 # 显式允许（如「小计」行）
        raise ShapeError("%s：表体行不符合标准形态（行 ~%d）— %s"
                         % (label, base + start_i + off, ln.strip()[:160]))
    return rows, seg


def parse_declared(arch_lines):
    """§4.2.8 的冻结-不变量声明行（本节唯一权威复述）。"""
    seg, _ = section(arch_lines, r"^####\s+4\.2\.8\s", "§4.2.8")
    pat = re.compile(
        r"冻结-不变量.*?消息集\s*\*\*(\d+)\*\*.*?组件错误码\s*\*\*(\d+)\*\*"
        r".*?VER`\s*=\s*\*\*`(0x[0-9A-Fa-f]+)`\*\*.*?FR`?\s*计数\s*\*\*(\d+)\*\*"
    )
    for ln in seg:
        m = pat.search(ln)
        if m:
            return {
                "msg": int(m.group(1)),
                "err": int(m.group(2)),
                "ver": int(m.group(3), 0),
                "fr": int(m.group(4)),
                "line": ln.strip(),
            }
    raise ShapeError("§4.2.8 内未找到「冻结-不变量」声明行（消息集/组件错误码/VER/FR 计数）")


def parse_fr_titles(req_dir):
    files = sorted(glob.glob(os.path.join(req_dir, "*.md")))
    if not files:
        raise ShapeError("需求目录内无 `*.md`：%s" % req_dir)
    total = 0
    per = []
    for f in files:
        text = read_text(f)
        n = len(re.findall(r"^#{2,4} *FR-[A-Z]+-[0-9]+", text, re.M))
        if n:
            per.append((os.path.basename(f), n))
        total += n
    if total == 0:
        raise ShapeError("需求目录内未解析到任何 FR 定义标题：%s" % req_dir)
    return total, per


def main(argv):
    arch_path, proto_path = DEFAULT_ARCH, DEFAULT_PROTO
    protocol_h, java_consts, java_frame = DEFAULT_PROTOCOL_H, DEFAULT_JAVA_CONSTS, DEFAULT_JAVA_FRAME
    req_dir = DEFAULT_REQ_DIR
    explicit = set()

    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("-h", "--help"):
            sys.stdout.write(USAGE)
            return 0
        if a in ("--arch", "--proto", "--protocol-h", "--java-consts",
                 "--java-frame", "--req-dir"):
            if i + 1 >= len(argv):
                sys.stderr.write("错误：%s 缺少参数\n" % a)
                return 2
            v = argv[i + 1]
            explicit.add(a)
            if a == "--arch":
                arch_path = v
            elif a == "--proto":
                proto_path = v
            elif a == "--protocol-h":
                protocol_h = v
            elif a == "--java-consts":
                java_consts = v
            elif a == "--java-frame":
                java_frame = v
            else:
                req_dir = v
            i += 2
            continue
        sys.stderr.write("错误：未知参数 %s\n" % a)
        return 2

    checks = [
        ("--arch", arch_path, False),
        ("--proto", proto_path, False),
        ("--protocol-h", protocol_h, False),
        ("--java-consts", java_consts, False),
        ("--java-frame", java_frame, False),
        ("--req-dir", req_dir, False),
    ]

    sys.stdout.write("=" * 67 + "\n")
    sys.stdout.write("E9 冻结-不变量跨制品一致性（消息 40 / 错误码 62 / VER 0x0100 / FR 184）\n")
    sys.stdout.write("判据      : 四项不变量 × 逐来源机械比对；任一来源间不一致或与冻结值不符 ⇒ 退 1\n")
    sys.stdout.write("不覆盖    : 字段级 schema(归 E7) / 错误码语义列 / FR 正文 / §13 历史记录行 / proto 非字段内容\n")
    sys.stdout.write("-" * 67 + "\n")

    # 默认路径缺失 ⇒ SKIP（组件未铺开）；显式路径缺失 ⇒ 2
    for flag, path, _ in checks:
        exists = os.path.isdir(path) if flag == "--req-dir" else os.path.isfile(path)
        if not exists:
            if flag in explicit:
                sys.stderr.write("错误：%s 路径不存在（显式路径）：%s\n" % (flag, path))
                return 2
            sys.stdout.write("SKIP: 默认路径 %s 不存在（组件未铺开）—— 退出码 0\n" % path)
            return 0

    problems = []
    try:
        arch_lines = read_text(arch_path).split("\n")
        proto_text = read_text(proto_path)
        protocol_h_text = read_text(protocol_h)
        java_consts_text = read_text(java_consts)
        java_frame_text = read_text(java_frame)

        # ---- ① 消息集 ----
        proto_msgs = parse_proto_msgs(proto_text)
        n_proto = len(proto_msgs)

        msg_allow = [r"^\|\s*\|\s*\|\s*\|\s*\*\*小计\*\*"]
        seg2, _ = parse_section_rows(arch_lines, r"^#####\s+4\.2\.8\.2\s",
                                     r"^\|\s*\d+\s*\|\s*`0x[0-9A-Fa-f]+`\s*\|", "§4.2.8.2",
                                     r"^\|\s*#\s*\|\s*类型码\s*\|", msg_allow)
        seg3, _ = parse_section_rows(arch_lines, r"^#####\s+4\.2\.8\.3\s",
                                     r"^\|\s*\d+\s*\|\s*`0x[0-9A-Fa-f]+`\s*\|", "§4.2.8.3",
                                     r"^\|\s*#\s*\|\s*类型码\s*\|", msg_allow)
        seg4, _ = parse_section_rows(arch_lines, r"^#####\s+4\.2\.8\.4\s",
                                     r"^\|\s*\d+\s*\|\s*`0x[0-9A-Fa-f]+`\s*\|", "§4.2.8.4",
                                     r"^\|\s*#\s*\|\s*类型码\s*\|", msg_allow)
        if not (seg2 and seg3 and seg4):
            raise ShapeError("§4.2.8.2/§4.2.8.3/§4.2.8.4 至少一节未解析到 message 行")
        n_arch_msg = len(seg2) + len(seg3) + len(seg4)

        seg_423, _ = section(arch_lines, r"^####\s+4\.2\.3\s", "§4.2.3")
        m423 = None
        for ln in seg_423:
            m = re.match(r"^####\s+4\.2\.3\s+.*?（\*\*共\s*(\d+)\s*种\*\*）", ln)
            if m:
                m423 = int(m.group(1))
                break
        if m423 is None:
            raise ShapeError("§4.2.3 标题未匹配「共 N 种」形态")
        n_java_msg = parse_java_int(java_consts_text, "MESSAGE_TYPE_COUNT")

        # ---- ② 错误码 ----
        rows_432, _ = parse_section_rows(arch_lines, r"^####\s+4\.3\.2\s",
                                         r"^\|\s*`0x[0-9A-Fa-f]{4}`\s*\|", "§4.3.2",
                                         r"^\|\s*错误码\s*\|", None)
        if not rows_432:
            raise ShapeError("§4.3.2 未解析到错误码表体行")
        err_arch_codes = [re.match(r"^\|\s*`(0x[0-9A-Fa-f]{4})`", ln).group(1) for ln in rows_432]
        n_err_arch = len(err_arch_codes)
        cpp_err = parse_cpp_errorcodes(protocol_h_text)
        n_err_cpp = len(cpp_err)

        # ---- ③ VER ----
        cpp_ver = parse_cpp_wire_ver(protocol_h_text)
        java_ver = parse_java_int(java_frame_text, "WIRE_VERSION")
        seg_422, _ = section(arch_lines, r"^####\s+4\.2\.2\s", "§4.2.2")
        ver_row = None
        for ln in seg_422:
            if re.match(r"^\|\s*`VER`\s*\|", ln):
                m = re.search(r"本版\s*=\s*\*\*`(0x[0-9A-Fa-f]+)`\*\*", ln)
                if m:
                    ver_row = int(m.group(1), 0)
                    break
        if ver_row is None:
            raise ShapeError("§4.2.2 未找到 `VER` 行且含「本版 = **`0x....`**」")

        # ---- ④ FR ----
        n_fr_files, fr_per = parse_fr_titles(req_dir)
        seg_11, _ = section(arch_lines, r"^###\s+1\.1\s", "§1.1")
        fr_11 = None
        for ln in seg_11:
            m = re.search(r"\*\*(\d+)\s*FR\*\*", ln) or re.search(r"（(\d+)\s*条 FR）", ln)
            if m:
                fr_11 = int(m.group(1))
                break
        if fr_11 is None:
            raise ShapeError("§1.1 未找到「184 FR」/「184 条 FR」形态")
        m_cov = re.search(r"FR 覆盖统计\*\*：共\s*\*\*(\d+)\s*条\*\*", "\n".join(arch_lines))
        if not m_cov:
            raise ShapeError("未找到「FR 覆盖统计：共 **N 条**」行")
        fr_cov = int(m_cov.group(1))

        decl = parse_declared(arch_lines)
    except ShapeError as exc:
        sys.stdout.write("未解析行 / 制品形态：\n  %s\n" % exc)
        sys.stdout.write("-" * 67 + "\n")
        sys.stdout.write("E9 用法/前置错误：制品出现本判据不认识的形态 ⇒ 判据未能真正执行（退出码 2）\n")
        return 2

    # ① 消息集
    for name, val in (("proto enum MsgType 有效值数", n_proto),
                      ("ARCH §4.2.8.2+3+4 表体行数", n_arch_msg),
                      ("ARCH §4.2.3 标题「共 N 种」", m423),
                      ("ARCH §4.2.8 声明行", decl["msg"]),
                      ("HgcpConstants.MESSAGE_TYPE_COUNT", n_java_msg)):
        if val != MSG_TOTAL:
            problems.append("[① 消息集] %s = %d ≠ 冻结值 %d" % (name, val, MSG_TOTAL))

    # ② 错误码
    for name, val in (("ARCH §4.3.2 表体行数", n_err_arch),
                      ("protocol.h enum ErrorCode 值数", n_err_cpp),
                      ("ARCH §4.2.8 声明行", decl["err"])):
        if val != ERR_TOTAL:
            problems.append("[② 错误码] %s = %d ≠ 冻结值 %d" % (name, val, ERR_TOTAL))
    if n_err_arch == ERR_TOTAL and len(set(err_arch_codes)) != ERR_TOTAL:
        problems.append("[② 错误码] ARCH §4.3.2 表体存在重复码（%d 行 / %d 唯一）"
                        % (n_err_arch, len(set(err_arch_codes))))

    # ③ VER
    for name, val in (("protocol.h kWireVersion", cpp_ver),
                      ("HgcpFrame.WIRE_VERSION", java_ver),
                      ("ARCH §4.2.2 VER 行", ver_row),
                      ("ARCH §4.2.8 声明行", decl["ver"])):
        if val != WIRE_VER:
            problems.append("[③ 线格式 VER] %s = 0x%04X ≠ 冻结值 0x%04X" % (name, val, WIRE_VER))

    # ④ FR
    for name, val in (("docs/REQUIREMENTS 的 FR 定义标题数", n_fr_files),
                      ("ARCH §1.1「N 条 FR」", fr_11),
                      ("ARCH「FR 覆盖统计」", fr_cov),
                      ("ARCH §4.2.8 声明行", decl["fr"])):
        if val != FR_TOTAL:
            problems.append("[④ FR 计数] %s = %d ≠ 冻结值 %d" % (name, val, FR_TOTAL))

    sys.stdout.write("实测各来源：\n")
    sys.stdout.write("  ① 消息集 : proto=%d / ARCH §4.2.8.2+3+4=%d / §4.2.3 标题=%d / §4.2.8 声明=%d / Java 常量=%d\n"
                     % (n_proto, n_arch_msg, m423, decl["msg"], n_java_msg))
    sys.stdout.write("  ② 错误码 : ARCH §4.3.2=%d / protocol.h enum=%d / §4.2.8 声明=%d\n"
                     % (n_err_arch, n_err_cpp, decl["err"]))
    sys.stdout.write("  ③ VER    : protocol.h=0x%04X / HgcpFrame=0x%04X / §4.2.2=%s / §4.2.8 声明=0x%04X\n"
                     % (cpp_ver, java_ver, "0x%04X" % ver_row, decl["ver"]))
    sys.stdout.write("  ④ FR     : REQUIREMENTS=%d / §1.1=%d / FR 覆盖统计=%d / §4.2.8 声明=%d\n"
                     % (n_fr_files, fr_11, fr_cov, decl["fr"]))
    sys.stdout.write("-" * 67 + "\n")

    if not problems:
        sys.stdout.write("E9 通过：四项冻结-不变量全部一致"
                         "（消息 %d / 错误码 %d / VER 0x%04X / FR %d）（退出码 0）\n"
                         % (MSG_TOTAL, ERR_TOTAL, WIRE_VER, FR_TOTAL))
        return 0
    sys.stdout.write("E9 失败：发现 %d 处不一致（退出码 1）\n" % len(problems))
    for p in problems:
        sys.stdout.write("  %s\n" % p)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
