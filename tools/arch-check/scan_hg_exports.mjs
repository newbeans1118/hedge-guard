#!/usr/bin/env node
/* =============================================================================
 * scan_hg_exports.mjs — E6 判据 ③「跨模块 API 引用 × 实际导出」对账（T-138）
 *
 * 承载：docs/ARCHITECTURE.md §3.0.2（E6 手段）、§3.15（`HG.<module>.*` 统一命名
 *       空间）、§7.1（前端零构建链）；任务单 T-138；失效来源 = T-134 验收 3
 *       （前端基座加载即崩：`HG.session.roleLabel` 从未导出却被调用）与
 *       T-017 验证盲区（「跨模块引用存在、目标未导出」时既有 E6 不拦截）。
 *
 * 用法：
 *   node tools/arch-check/scan_hg_exports.mjs [--root <dir>]
 *     --root <dir>   前端源码根目录（默认 web）
 *     -h, --help     显示本帮助
 *
 * 退出码（六脚本统一口径 `T-095`）：
 *   0 = 通过（或 `<root>` 内无 `*.js` ⇒ 组件未铺开，打印 SKIP）
 *   1 = 命中（存在引用了**目标模块未导出成员**的跨模块调用）
 *   2 = 用法 / 前置错误：`--root` 不存在或不是目录、参数错误、文件不可读
 *       ⚠️ **运行时缺失（node）不属本脚本职责** —— 由调用方 `check_frontend.sh`
 *       先判 `command -v node`，缺失即返 2（**不得静默 SKIP 返 0**）。
 *
 * 判据（一条；命中即失败）：
 *   ① 扫描 `<root>/**\/*.js`（排除 `**\/node_modules/**`、`**\/dist/**`；
 *      排除 `*.min.js` / `*.bundle.js` —— 其**存在本身**已由 E6 判据 ① 判红）
 *   ② **先剔注释**（`//`、`/* *\/`），并把**普通字符串与模板字面量的原文内容**
 *      置换为空格（**保留 `${…}` 内的代码**）—— 保证「只在注释里出现的引用」
 *      不算违规（否则 T-134 的**根因留痕注释**会被误判为违规）
 *   ③ 收集全局命名空间 `HG` 下**各模块的导出集合**：
 *        · `HG.<mod> = { a, b, c }` / `HG.<mod> = { a, b: x, 'k': y }`（可跨行）
 *        · `HG.<mod> = HG.<mod> || { … }`（幂等初始化）
 *        · `HG.<mod> = IDENT;` ⇒ 同文件回溯 `const|let|var IDENT = { … }`（如
 *          `HG.routes = ROUTES`，`ROUTES` 定义在 `core/routes.js`）
 *        · `HG.<mod>.<name> = …` ⇒ **动态挂载**的成员（如
 *          `HG.pages._riskAlertConn = conn;`）亦计入导出集合
 *   ④ 扫描**跨模块调用引用** `HG.<mod>.<member>(`（**只判前两段**，见「未覆盖」），
 *      与 ③ 的集合比对：模块未定义、或成员未导出 ⇒ 命中，打印 `文件:行` 与缺失符号
 *
 * ⚠️ 已知未覆盖 / 取舍（**不得沉默略过**）：
 *   · **只判调用形态** `HG.<mod>.<member>(`。纯属性读取（`HG.api.BASE`、
 *     `HG.pages._x && HG.pages._x.close`）**不判** —— 「读未定义属性」得 `undefined`
 *     （不抛错），T-134 的实际失效面是**调用**（`TypeError: … is not a function`）。
 *     读面误用由页面单测 / 代码审查兜底。
 *   · **只判前两段**：`HG.routes.PLAN.doc(` 只校验 `routes.PLAN` 已导出；
 *     `PLAN.doc` 等**第三段及更深**的成员不校验（需目标对象的**值级**求值，
 *     静态不可得 —— `ROUTES` 是常量表，可后续另立判据）。
 *   · 导出集合是**文本级**静态解析，非 AST：导出对象体内出现 **spread**
 *     （`...base`）或**计算属性名**（`[k]: v`）时该模块标 `partial` ——
 *     对其**漏判**（打印 `NOTE` 披露，不判红），因无法枚举键集合。
 *   · 正则字面量**未做专门处理**：若某正则体内含 `//` 或 `/*` 会被误认作注释
 *     （本仓库 `web/js` 现存两处正则均不含这两种序列）。
 *   · 不校验「引用的成员**确实是函数**」（只校验「名字在导出集合里」）。
 *
 * 自测（T-138 实测，见 tools/arch-check/README.md §3 E6 段）：
 *   正例  现行树                                        → 0
 *   反例  隔离副本注入 `HG.session.roleLabel()`          → 1（精确打印该 `文件:行`）
 *   反例  `node` 从 `PATH` 剥离（调用方 check_frontend.sh）→ 2
 * ========================================================================== */

import fs from 'node:fs';
import path from 'node:path';

const USAGE = `用法：scan_hg_exports.mjs [--root <dir>]
  --root <dir>   前端源码根目录，默认 web
  -h, --help     显示本帮助
退出码：0 通过 / 无 *.js（SKIP）；1 命中（引用未导出成员）；2 用法或前置错误
`;

/* ----------------------------- 参数解析 ---------------------------------- */

let ROOT = 'web';
{
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '-h' || a === '--help') { process.stdout.write(USAGE); process.exit(0); }
    else if (a === '--root') {
      if (i + 1 >= argv.length) { process.stderr.write('错误：--root 缺少参数\n'); process.exit(2); }
      ROOT = argv[i + 1]; i += 1;
    } else if (a.startsWith('--root=')) { ROOT = a.slice('--root='.length); }
    else { process.stderr.write(`错误：未知参数 ${a}\n${USAGE}`); process.exit(2); }
  }
}

let rootStat;
try { rootStat = fs.statSync(ROOT); } catch {
  process.stderr.write(`错误：--root 目录不存在（显式路径）：${ROOT}\n`);
  process.exit(2);
}
if (!rootStat.isDirectory()) {
  process.stderr.write(`错误：--root 不是目录：${ROOT}\n`);
  process.exit(2);
}

/* --------------------------- 文件遍历（有序） ----------------------------- */

const SKIP_DIRS = new Set(['node_modules', 'dist']);
const SKIP_FILE_RE = /\.(min|bundle)\.js$/;

function walk(dir, out) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) {
    process.stderr.write(`错误：目录不可读：${dir}（${e.message}）\n`);
    process.exit(2);
  }
  entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  for (const ent of entries) {
    const full = path.join(dir, ent.name);
    if (ent.isDirectory()) { if (!SKIP_DIRS.has(ent.name)) walk(full, out); continue; }
    if (!ent.isFile()) continue;
    if (!ent.name.endsWith('.js') || SKIP_FILE_RE.test(ent.name)) continue;
    out.push(full);
  }
  return out;
}

const files = walk(ROOT, []).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));

process.stdout.write('== E6-③ 跨模块 API 引用 × 实际导出（scan_hg_exports.mjs，T-138） ==\n');
process.stdout.write(`扫描根      : ${ROOT}\n`);
process.stdout.write(`排除项      : **/node_modules/**、**/dist/**、*.min.js、*.bundle.js\n`);
process.stdout.write(`判据        : HG.<mod>.<member>( 的 <mod>/<member> 须在 <mod> 的实际导出集合内\n`);
process.stdout.write('-------------------------------------------------------------------\n');

if (files.length === 0) {
  process.stdout.write(`SKIP: ${ROOT} 内无 *.js（组件未铺开）—— 退出码 0\n`);
  process.exit(0);
}

/* --------------------------- 注释/字符串置换 ------------------------------ */

/**
 * 返回两份**等长**文本（下标一一对应，行号可互推）：
 *   plain = 仅剔注释（字符串/模板**原文保留**，用于取引号键名）
 *   code  = 剔注释 + 把字符串与模板**原文内容**置换为空格（`${…}` 内代码保留）
 *           引号/反引号本体保留，便于识别字符串边界。
 */
function strip(src) {
  const n = src.length;
  const plain = src.split('');
  const code = src.split('');
  const blank = (arr, from, to) => {
    for (let k = Math.max(0, from); k < Math.min(n, to); k += 1) {
      if (arr[k] !== '\n' && arr[k] !== '\r') arr[k] = ' ';
    }
  };
  const frames = [];            // 'tpl' | { depth }
  let mode = 'code';            // 'code' | 'raw'
  let i = 0;
  while (i < n) {
    const c = src[i];
    if (mode === 'code') {
      if (c === '/' && src[i + 1] === '/') {
        let j = i; while (j < n && src[j] !== '\n') j += 1;
        blank(plain, i, j); blank(code, i, j); i = j; continue;
      }
      if (c === '/' && src[i + 1] === '*') {
        const k = src.indexOf('*/', i + 2);
        const j = k < 0 ? n : k + 2;
        blank(plain, i, j); blank(code, i, j); i = j; continue;
      }
      if (c === "'" || c === '"') {
        let j = i + 1; let closed = false;
        while (j < n) {
          const d = src[j];
          if (d === '\\') { j += 2; continue; }
          if (d === c) { closed = true; j += 1; break; }
          if (d === '\n') break;
          j += 1;
        }
        blank(code, i + 1, closed ? j - 1 : j);
        i = j; continue;
      }
      if (c === '`') { frames.push('tpl'); mode = 'raw'; i += 1; continue; }
      const top = frames.length ? frames[frames.length - 1] : null;
      if (top && typeof top === 'object') {
        if (c === '{') { top.depth += 1; i += 1; continue; }
        if (c === '}') {
          if (top.depth === 0) { frames.pop(); mode = 'raw'; i += 1; continue; }
          top.depth -= 1; i += 1; continue;
        }
      }
      i += 1; continue;
    }
    // raw：模板字面量原文
    if (c === '\\') { blank(code, i, i + 2); i += 2; continue; }
    if (c === '`') { frames.pop(); mode = 'code'; i += 1; continue; }
    if (c === '$' && src[i + 1] === '{') {
      blank(code, i, i + 2);
      frames.push({ depth: 0 });
      mode = 'code'; i += 2; continue;
    }
    blank(code, i, i + 1); i += 1;
  }
  return { plain: plain.join(''), code: code.join('') };
}

/* ------------------------------ 结构解析 --------------------------------- */

const IDENT_RE = /^[A-Za-z_$][A-Za-z0-9_$]*/;

function matchBracket(text, open) {
  const stack = [];
  for (let i = open; i < text.length; i += 1) {
    const c = text[i];
    if (c === '{' || c === '[' || c === '(') stack.push(c);
    else if (c === '}' || c === ']' || c === ')') { stack.pop(); if (stack.length === 0) return i; }
  }
  return -1;
}

function splitTopLevel(text, start, end) {
  const parts = [];
  const stack = [];
  let from = start;
  for (let i = start; i < end; i += 1) {
    const c = text[i];
    if (c === '{' || c === '[' || c === '(') stack.push(c);
    else if (c === '}' || c === ']' || c === ')') { if (stack.length) stack.pop(); }
    else if (c === ',' && stack.length === 0) { parts.push([from, i]); from = i + 1; }
  }
  parts.push([from, end]);
  return parts;
}

/** 取对象字面量 `{…}` 的顶层键集合。`open`/`close` 为 `code` 中的下标。 */
function keysOfLiteral(code, plain, open, close) {
  const keys = [];
  let partial = false;
  for (const [s, e] of splitTopLevel(code, open + 1, close)) {
    let i = s;
    while (i < e && /\s/.test(code[i])) i += 1;
    if (i >= e) continue;                                  // 尾逗号 / 空段
    if (code.startsWith('...', i)) { partial = true; continue; }   // spread 不可枚举
    const q = plain[i];
    if (q === "'" || q === '"') {
      let j = i + 1; let out = '';
      while (j < e && plain[j] !== q) {
        if (plain[j] === '\\') { out += plain.slice(j, j + 2); j += 2; continue; }
        out += plain[j]; j += 1;
      }
      keys.push(out); continue;
    }
    if (code[i] === '[') { partial = true; continue; }      // 计算属性名不可枚举
    const m = IDENT_RE.exec(code.slice(i, e));
    if (m) keys.push(m[0]); else partial = true;
  }
  return { keys, partial };
}

/* ------------------------- 定义收集（HG.<mod> = …） ------------------------ */

const mods = new Map();   // name -> { keys:Set<string>, partial:boolean, sources:Set<string> }

function modOf(name, file) {
  let m = mods.get(name);
  if (!m) { m = { keys: new Set(), partial: false, sources: new Set() }; mods.set(name, m); }
  if (file) m.sources.add(file);
  return m;
}

/**
 * 解析 `=` 右侧的导出来源；返回 { keys, partial } 或 null（不可静态确定）。
 * `i` = `code` 中右值起始下标（已跳过空白）。
 */
function resolveRhs(ctx, i, depth) {
  const { code, plain } = ctx;
  if (code[i] === '{') {
    const close = matchBracket(code, i);
    if (close < 0) return null;
    return keysOfLiteral(code, plain, i, close);
  }
  // HG.<mod> || { … }（幂等初始化）
  const alt = /^HG\s*\.\s*[A-Za-z_$][A-Za-z0-9_$]*\s*\|\|\s*\{/.exec(code.slice(i, i + 256));
  if (alt) {
    const open = i + alt[0].length - 1;
    const close = matchBracket(code, open);
    if (close < 0) return null;
    return keysOfLiteral(code, plain, open, close);
  }
  // 裸标识符 ⇒ 同文件回溯声明（如 HG.routes = ROUTES）
  const idm = IDENT_RE.exec(code.slice(i, i + 256));
  if (idm && depth < 3) {
    const name = idm[0];
    const declRe = new RegExp(`\\b(?:const|let|var)\\s+${name.replace(/\$/g, '\\$')}\\s*=\\s*`, 'g');
    const hit = declRe.exec(code);
    if (hit) return resolveRhs(ctx, hit.index + hit[0].length, depth + 1);
  }
  return null;
}

const DEF_RE = /\bHG\s*\.\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*(?:\.\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*)?=(?![=>])/g;

const sources = files.map((file) => {
  let raw;
  try { raw = fs.readFileSync(file, 'utf8'); } catch (e) {
    process.stderr.write(`错误：文件不可读：${file}（${e.message}）\n`);
    process.exit(2);
  }
  const { plain, code } = strip(raw);
  const lineStarts = [0];
  for (let i = 0; i < raw.length; i += 1) if (raw[i] === '\n') lineStarts.push(i + 1);
  const ctx = { file, raw, plain, code, lineStarts };
  collectDefs(ctx);
  return ctx;
});

function collectDefs(ctx) {
  const { code, file } = ctx;
  DEF_RE.lastIndex = 0;
  let m;
  while ((m = DEF_RE.exec(code)) !== null) {
    const modName = m[1];
    const member = m[2];
    const mod = modOf(modName, file);
    if (member) { mod.keys.add(member); continue; }   // HG.<mod>.<name> = … 动态挂载
    const rhsStart = m.index + m[0].length;
    let i = rhsStart;
    while (i < code.length && /\s/.test(code[i])) i += 1;
    const res = resolveRhs(ctx, i, 0);
    if (!res) { mod.partial = true; continue; }
    for (const k of res.keys) mod.keys.add(k);
    if (res.partial) mod.partial = true;
  }
}

/* ------------------------------ 引用对账 --------------------------------- */

const REF_RE = /\bHG\s*\.\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*\.\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*\(/g;

const hits = [];
const notes = [];
let refCount = 0;

for (const ctx of sources) {
  const { code, lineStarts } = ctx;
  const lineOf = (off) => {
    let lo = 0; let hi = lineStarts.length - 1;
    while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (lineStarts[mid] <= off) lo = mid; else hi = mid - 1; }
    return lo + 1;
  };
  REF_RE.lastIndex = 0;
  let m;
  while ((m = REF_RE.exec(code)) !== null) {
    refCount += 1;
    const modName = m[1];
    const member = m[2];
    const mod = mods.get(modName);
    const at = `${ctx.file}:${lineOf(m.index)}`;
    const sym = `HG.${modName}.${member}(`;
    if (!mod) {
      hits.push({ at, sym, reason: `命名空间 HG.${modName} 未被任何模块定义` });
      continue;
    }
    if (mod.keys.has(member)) continue;
    if (mod.partial) {
      notes.push({ at, sym, reason: `模块 ${modName} 的导出集合不可静态枚举（spread/计算属性名）⇒ 该引用不判` });
      continue;
    }
    const list = [...mod.keys].sort();
    const shown = list.slice(0, 12).join(', ') + (list.length > 12 ? `, …（共 ${list.length} 个）` : '');
    hits.push({ at, sym, reason: `模块 ${modName} 未导出成员 ${member}（实际导出：${shown}）` });
  }
}

/* -------------------------------- 输出 ---------------------------------- */

const inventory = [...mods.keys()].sort()
  .map((k) => `${k}(${mods.get(k).keys.size}${mods.get(k).partial ? '+' : ''})`)
  .join(' ');
process.stdout.write(`导出模块    : ${inventory}\n`);
process.stdout.write(`跨模块调用  : ${refCount} 处（判据范围 = HG.<mod>.<member>( 的前两段）\n`);

for (const n of notes) {
  process.stdout.write(`NOTE: ${n.at}: ${n.sym} —— ${n.reason}\n`);
}

if (hits.length === 0) {
  process.stdout.write('  （未发现引用未导出成员）\n');
  process.stdout.write('HIT-COUNT: 0\n');
  process.stdout.write('判据 ③ 通过：跨模块调用引用的模块与成员均在目标模块的实际导出集合内（退出码 0）\n');
  process.exit(0);
}

hits.forEach((h, idx) => {
  process.stdout.write(`${String(idx + 1).padStart(2, '0')}. UNEXPORTED: ${h.at}: ${h.sym} —— ${h.reason}\n`);
});
process.stdout.write(`HIT-COUNT: ${hits.length}\n`);
process.stderr.write(`判据 ③ 失败：${hits.length} 处跨模块调用引用了未导出成员 / 未定义命名空间（退出码 1）\n`);
process.exit(1);
