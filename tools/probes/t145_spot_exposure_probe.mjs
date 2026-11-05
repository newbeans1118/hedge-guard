/*
 * T-145 页面探针（无头桩，非真实浏览器）—— 实货敞口分页硬上限「静默截断」闭环。
 *
 * 缺陷事实（修复前 `web/js/pages/spot.js` fetchRows）：`size=100` × `guard < 50` = 5000 行硬上限，
 * 超出时循环静默结束、无任何提示 ⇒ 用户看到不完整数据而不自知（「静默错值」族）。
 *
 * 修复后判据（NFR-USE-1）：
 *   · 达到上限（50 页 / 5000 行）且服务端 `total` 仍更大 ⇒ **显式提示**「数据量超上限，已截断至 5000 行…请缩小筛选范围」；
 *   · 未达上限但分页提前结束而 `total` 仍更大 ⇒ 显式提示「数据未取全」（同类静默短量一并封堵）；
 *   · 正常全量（total ≤ 5000）与「恰好 5000」边界 ⇒ **不得**出现任何提示（满页 ≠ 截断）。
 *
 * 覆盖：
 *   · 契约面：GET /spot/exposure 逐字、size=100、page 连续无重复、汇总不带 subAccountId / 单账户带收窄；
 *   · 边界四态：① total=250（无提示、行数 250、GET=3）② total=6000（有提示、行数=5000、GET=50）
 *     ③ total=5000（边界：无提示、行数=5000、GET=50）④ total=1200 但第 4 页起空页（partial 提示、行数 300）；
 *   · 提示可访问性：role=alert、锚标 data-exposure-incomplete / -total / -shown 实测值、位于表格之前；
 *   · 三态齐全：空态（total=0 ⇒ 暂无数据，无提示）/ 错误态（抛出 ⇒ 加载失败 + 重试入口）；
 *   · 负例有牙（变异子进程）：摘除超限提示 ⇒ 子进程断言必红（退出码 7）+ 未变异副本存活对照。
 *
 * 用法：node tools/probes/t145_spot_exposure_probe.mjs → 全过打印 PASS 计数，退出码 0；任一失败退出码 1。
 *
 * ⚠️ ESM 静态 import 先于语句求值（hoisting），故先装全局桩，再动态 import 页面模块。
 */
/* ---------------- 全局桩先行（ns.js 顶层读取 window） ---------------- */
globalThis.window = {
  crypto: { randomUUID: () => 'probe-uuid' },
  setTimeout: () => 0,
  location: { host: 'probe.local', search: '' },
  addEventListener() {}
};

const { HG } = await import('../../web/js/core/ns.js');

/* ---------------- DOM 桩（对齐 t083/t055 minimal 形态 + querySelectorAll） ---------------- */
const minimalDoc = {
  createElement(tag) {
    return {
      tagName: String(tag).toUpperCase(), children: [], dataset: {}, style: {}, attrs: {}, value: '',
      listeners: {}, parentNode: null,
      classList: { _s: new Set(), add(c) { this._s.add(c); }, remove(c) { this._s.delete(c); }, toggle(c, on) { on ? this._s.add(c) : this._s.delete(c); }, contains(c) { return this._s.has(c); } },
      set className(v) { this.classList._s = new Set(String(v).split(/\s+/).filter(Boolean)); },
      get className() { return Array.from(this.classList._s).join(' '); },
      set textContent(v) { this._t = String(v); this.children = []; },
      get textContent() { return (this._t || '') + (this._h || '') + this.children.map((c) => c.textContent).join(''); },
      set innerHTML(v) { this._h = String(v); this.children = []; },
      get innerHTML() { return this._h || ''; },
      setAttribute(k, v) { this.attrs[k] = String(v); }, getAttribute(k) { return this.attrs[k] ?? null; },
      removeAttribute(k) { delete this.attrs[k]; },
      appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
      insertBefore(c, ref) { c.parentNode = this; const i = ref ? this.children.indexOf(ref) : -1; if (i === -1) this.children.push(c); else this.children.splice(i, 0, c); return c; },
      removeChild(c) { const i = this.children.indexOf(c); if (i !== -1) this.children.splice(i, 1); c.parentNode = null; return c; },
      remove() { if (this.parentNode) this.parentNode.removeChild(this); },
      addEventListener(t, fn) { (this.listeners[t] = this.listeners[t] || []).push(fn); },
      click() { (this.listeners.click || []).forEach((fn) => fn({ target: this })); },
      focus() {}, querySelector() { return null; },
      // tableHTML 产出的是 HTML 字符串（桩不解析为节点）⇒ querySelectorAll 恒空，避免页面绑定数据行按钮时抛错
      querySelectorAll() { return []; },
      get firstChild() { return this.children[0] || null; }, get form() { return null; }
    };
  },
  createTextNode(t) { const n = minimalDoc.createElement('#text'); n._t = String(t); return n; },
  body: null, addEventListener() {}, removeEventListener() {},
  getElementById() { return null; }, querySelector() { return null; }
};
minimalDoc.body = minimalDoc.createElement('body');
globalThis.document = minimalDoc;

/* ---------------- HG 核心桩 ---------------- */
let CUR = null; // 当前视图子账户（null = 汇总）
HG.session = {
  state: { subAccounts: [{ id: 8, code: 'SA8', name: '测试子账户A' }, { id: 9, code: 'SA9', name: '测试子账户B' }] },
  currentId: () => CUR,
  isAggregate: () => CUR === null,
  onChange() { return () => {}; }
};

/** 服务端数据集（各用例按需改字段，模拟 PageData 信封）。 */
const DATA = { total: 0, emptyAfterPage: null, fail: false };
const PAGE_SIZE_EXPECT = 100;

function makeRow(i) {
  return {
    id: i + 1, subAccountId: 8, productId: 1, spotCategoryCode: 'CU', direction: 'BUY', calcMode: 'COMBINED',
    contractUnexecutedQty: '10.0000', inventoryQty: '2.0000', productionPlanQty: '3.0000',
    exposureQty: '15.0000', unit: '吨', coveredQty: '0.0000', status: 'PENDING_HEDGE'
  };
}

HG.api = {
  BASE: '/api/v1',
  _calls: [],
  newIdempotencyKey: () => 'probe-uuid',
  async get(path, query) {
    this._calls.push({ m: 'GET', p: path, q: Object.assign({}, query || {}) });
    if (DATA.fail) throw { code: -1, message: '网络故障（探针注入）' };
    const page = Number((query && query.page) || 1);
    const size = Number((query && query.size) || 20);
    let count = Math.max(0, Math.min(size, DATA.total - (page - 1) * size));
    if (DATA.emptyAfterPage !== null && page > DATA.emptyAfterPage) count = 0; // 模拟分页提前结束
    const items = [];
    for (let i = 0; i < count; i++) items.push(makeRow((page - 1) * size + i));
    return { items: items, total: DATA.total, page: page, size: size };
  },
  async post() { throw { code: -1, message: '敞口页查询不应调用 POST（探针）' }; },
  async put() { throw { code: -1, message: '敞口页不应调用 put' }; },
  async del() { throw { code: -1, message: '敞口页不应调用 del' }; },
  async download() { throw { code: -1, message: '敞口页查询不应调用 download' }; }
};

/* 先求值真 router / errmsg 模块（spot.js 运行期依赖 HG.router、HG.errmsg），
 * 再包一层 router.register 记录器——页面注册面可断言。 */
await import('../../web/js/core/router.js');
await import('../../web/js/core/errmsg.js');
const REGS = {};
const _router = HG.router;
const _register = _router.register;
_router.register = function (pattern, fn) { REGS[pattern] = fn; return _register.call(_router, pattern, fn); };
_router.navigate = function () {};

/* ---------------- 断言器 ---------------- */
let pass = 0; const fails = [];
function ok(cond, name) { if (cond) { pass++; } else { fails.push(name); console.error('FAIL: ' + name); } }
const tick = () => new Promise((r) => setTimeout(r, 0));
async function settle() { await tick(); await tick(); }

function walkAll(node) {
  const out = [];
  (function rec(n) {
    if (!n || !n.children) return;
    out.push(n);
    n.children.forEach(rec);
  })(node);
  return out;
}
function findByAttr(node, key, val) {
  return walkAll(node).find((n) => n.attrs && n.attrs[key] === val) || null;
}
function hasAttr(node, key) { return walkAll(node).some((n) => n.attrs && n.attrs[key] !== undefined); }
/** 聚合全部 innerHTML 片段（表格为 HTML 字符串——stub 不解析为节点，须按串断言）。 */
function htmlAll(node) { return walkAll(node).map((n) => (n._h || '')).join(''); }
function textOf(node) { return node.textContent || ''; }
/** 表格实际渲染行数：每行「明细」按钮带 data-detail（一行为一处）。 */
function countRows(node) { return (htmlAll(node).match(/data-detail="/g) || []).length; }

/* ================================================================== *
 * 子进程模式：只验证「摘除超限提示 ⇒ 断言必红」（负例有牙）
 * ================================================================== */
if (process.env.T145_MUTATE) {
  await import(process.env.T145_COPY_DIR + '/web/js/pages/spot.js');
  DATA.total = 18000; DATA.emptyAfterPage = null; DATA.fail = false;
  HG.api._calls.length = 0;
  const root = document.createElement('div');
  HG.pages.spot.renderExposure(root, { query: {} });
  await settle();
  const shown = countRows(root);
  const notice = findByAttr(root, 'data-exposure-incomplete', 'truncated');
  // 截断态判据：必须「已加载满 5000 行」且「显式提示在」；任一不满足 ⇒ 击杀
  if (!(shown === 5000 && notice)) {
    console.log('MUTATION-KILLED [notrunc] 截断态判据不成立（shown=' + shown + '，服务端 total=18000，提示=' +
      (notice ? '在' : '缺') + '）');
    process.exit(7);
  }
  console.log('MUTATION-SURVIVED [notrunc]（截断提示在，探针无牙——不合格）');
  process.exit(0);
}

/* ================================================================== *
 * 父进程模式：全量走查
 * ================================================================== */
await import('../../web/js/pages/spot.js');

ok(typeof HG.pages.spot.renderExposure === 'function', '页面导出口在（HG.pages.spot.renderExposure）');
ok(REGS['/spot/exposure'] === HG.pages.spot.renderExposure, '路由 /spot/exposure 注册到 renderExposure');

function resetApi(total, opts) {
  opts = opts || {};
  DATA.total = total;
  DATA.emptyAfterPage = opts.emptyAfterPage === undefined ? null : opts.emptyAfterPage;
  DATA.fail = !!opts.fail;
  HG.api._calls.length = 0;
}

async function scenario(total, opts) {
  opts = opts || {};
  resetApi(total, opts);
  const root = document.createElement('div');
  HG.pages.spot.renderExposure(root, { query: {} });
  await settle();
  // 实测摘要（每次取数的边界态一眼可读：行数 / GET 次数 / 提示种类）
  const kind = findByAttr(root, 'data-exposure-incomplete', 'truncated') ? 'truncated'
    : (findByAttr(root, 'data-exposure-incomplete', 'partial') ? 'partial' : 'none');
  console.log('[T-145] ' + (opts.tag || ('total=' + total)) +
    ' | 服务端 total=' + DATA.total +
    (DATA.emptyAfterPage !== null ? '（第 ' + (DATA.emptyAfterPage + 1) + ' 页起空页）' : '') +
    ' | 渲染行数=' + countRows(root) +
    ' | GET=' + HG.api._calls.filter((c) => c.m === 'GET').length +
    ' | 提示=' + kind);
  return root;
}

/* ================= 契约面：路径 / size / page 序列 / 子账户收窄 ================= */
{
  CUR = null;
  const root = await scenario(250, { tag: '契约·汇总视图' });
  const calls = HG.api._calls.filter((c) => c.m === 'GET');
  ok(calls.length === 3, '契约：total=250 ⇒ GET 次数=3（实测 ' + calls.length + '）');
  ok(calls.every((c) => c.p === '/spot/exposure'), '契约：请求路径逐字 /spot/exposure（routes.SPOT.EXPOSURE）');
  ok(calls.every((c) => c.q.size === PAGE_SIZE_EXPECT), '契约：逐页 size=100 保留（不改服务端参数语义）');
  ok(calls.map((c) => c.q.page).join(',') === '1,2,3', '契约：page 从 1 递增且无重复（实测 ' + calls.map((c) => c.q.page).join(',') + '）');
  ok(calls.every((c) => !('subAccountId' in c.q)), '契约：汇总视图不带 subAccountId（仅收窄，不扩大可见集 NFR-SEC-7）');

  CUR = 8;
  const root2 = await scenario(250, { tag: '契约·单子账户 SA8' });
  const calls2 = HG.api._calls.filter((c) => c.m === 'GET');
  ok(calls2.length === 3 && calls2.every((c) => c.q.subAccountId === 8), '契约：单子账户视图带 subAccountId=8 收窄');
  ok(countRows(root2) === 250, '契约：单子账户视图同样取尽 250 行');
  CUR = null;
}

/* ================= 边界①：total=250（≤ 上限）⇒ 全量、无任何提示 ================= */
{
  const root = await scenario(250, { tag: '① total=250 ≤ 上限' });
  ok(countRows(root) === 250, '① total=250：行数=250（实测 ' + countRows(root) + '）');
  ok(!hasAttr(root, 'data-exposure-incomplete'), '① total=250：无未取全/截断锚标（正常全量不出提示）');
  ok(textOf(root).indexOf('数据量超上限') === -1, '① total=250：文案不含「数据量超上限」');
  ok(textOf(root).indexOf('数据未取全') === -1, '① total=250：文案不含「数据未取全」');
}

/* ================= 边界③先跑（total 恰好 5000）：满页 ≠ 截断 ================= */
{
  const root = await scenario(5000, { tag: '③ total=5000（恰好边界）' });
  const calls = HG.api._calls.filter((c) => c.m === 'GET');
  ok(countRows(root) === 5000, '③ total=5000（边界）：行数=5000（实测 ' + countRows(root) + '）');
  ok(calls.length === 50, '③ total=5000（边界）：GET 次数=50（页上限，实测 ' + calls.length + '）');
  ok(!hasAttr(root, 'data-exposure-incomplete'), '③ total=5000（边界）：无截断提示（total 已取尽，不得误报）');
  ok(textOf(root).indexOf('数据量超上限') === -1, '③ total=5000（边界）：文案不含「数据量超上限」');
}

/* ================= 边界②：total=6000（> 上限）⇒ 显式提示 + 行数=5000 ================= */
{
  const root = await scenario(6000, { tag: '② total=6000 > 上限' });
  const calls = HG.api._calls.filter((c) => c.m === 'GET');
  const notice = findByAttr(root, 'data-exposure-incomplete', 'truncated');
  ok(countRows(root) === 5000, '② total=6000：行数=5000（= 上限，实测 ' + countRows(root) + '）');
  ok(calls.length === 50, '② total=6000：GET 次数=50（页上限，实测 ' + calls.length + '）');
  ok(notice !== null, '② total=6000：截断显式提示在（锚标 data-exposure-incomplete=truncated）');
  ok(textOf(root).indexOf('数据量超上限') !== -1, '② total=6000：提示含「数据量超上限」');
  ok(textOf(root).indexOf('已截断至 5000 行') !== -1, '② total=6000：提示含「已截断至 5000 行」');
  ok(textOf(root).indexOf('请缩小筛选范围') !== -1, '② total=6000：提示含可操作指引「请缩小筛选范围」');
  ok(textOf(root).indexOf('6000') !== -1, '② total=6000：提示披露服务端总行数 6000');
  ok(notice !== null && notice.attrs['data-exposure-total'] === '6000', '② total=6000：锚标带实测 total=6000');
  ok(notice !== null && notice.attrs['data-exposure-shown'] === '5000', '② total=6000：锚标带实测已加载 shown=5000');
  ok(notice !== null && notice.attrs.role === 'alert', '② total=6000：提示 role=alert（读屏可达）');
  ok(notice !== null && notice.parentNode && notice.parentNode.children[0] === notice,
    '② total=6000：提示位于表格之前（先告知后展示，非静默）');
  ok(notice !== null && notice.children.length > 0 && notice.className.indexOf('hg-banner--warn') !== -1,
    '② total=6000：提示为警示样式节点（hg-banner--warn）且含文案子节点');
}

/* ================= 边界④：分页提前结束（total 仍更大）⇒ 显式「未取全」 ================= */
{
  const root = await scenario(1200, { emptyAfterPage: 3, tag: '④ 分页提前结束' });
  const notice = findByAttr(root, 'data-exposure-incomplete', 'partial');
  ok(countRows(root) === 300, '④ 分页提前结束：已取 300 行仍渲染（实测 ' + countRows(root) + '）');
  ok(notice !== null, '④ 分页提前结束：显式提示锚标 partial（与截断区分）');
  ok(textOf(root).indexOf('数据未取全') !== -1 && textOf(root).indexOf('1200') !== -1,
    '④ 分页提前结束：提示披露「数据未取全」与服务端总数 1200（不静默短量）');
  ok(textOf(root).indexOf('请缩小筛选范围') !== -1 || textOf(root).indexOf('请重试') !== -1,
    '④ 分页提前结束：提示含可操作指引');
}

/* ================= 三态齐全：空态 / 加载态 / 错误态 ================= */
{
  const root = await scenario(0, { tag: '三态·空' });
  ok(textOf(root).indexOf('暂无数据') !== -1, '三态·空：total=0 ⇒ 「暂无数据」显式（非空白表格）');
  ok(!hasAttr(root, 'data-exposure-incomplete'), '三态·空：空态不出现截断提示');

  const errRoot = await scenario(0, { fail: true, tag: '三态·错误' });
  ok(textOf(errRoot).indexOf('加载失败') !== -1, '三态·错误：请求失败 ⇒ 「加载失败」显式');
  ok(textOf(errRoot).indexOf('重试') !== -1, '三态·错误：含重试入口');
  ok(!hasAttr(errRoot, 'data-exposure-incomplete'), '三态·错误：错误态不出现截断提示（不混淆两类状态）');
}

/* ================= 变异负例（负例有牙） ================= */
{
  const fs = await import('node:fs');
  const path = await import('node:path');
  const cpMod = await import('node:child_process');
  const os = await import('node:os');
  const { fileURLToPath } = await import('node:url');
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
  const webJs = path.join(repoRoot, 'web', 'js');
  const spotPath = path.join(webJs, 'pages', 'spot.js');
  const src = fs.readFileSync(spotPath, 'utf8');
  const FIND = 'if (meta && (meta.truncated || meta.partial)) body.appendChild(incompleteNotice(meta));';
  const REPL = '/* MUTANT: 摘除未取全提示（T-145 变异） */';
  ok(src.indexOf(FIND) !== -1, '变异前置——源码含超限提示调用点');
  const mutated = src.split(FIND).join(REPL);
  ok(mutated !== src, '变异前置——副本确实被改写（notrunc）');

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 't145-mut-'));
  fs.cpSync(webJs, path.join(dir, 'web', 'js'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'web', 'js', 'pages', 'spot.js'), mutated);
  const r = cpMod.spawnSync(process.execPath, [fileURLToPath(import.meta.url)], {
    env: Object.assign({}, process.env, { T145_MUTATE: '1', T145_COPY_DIR: dir }),
    encoding: 'utf8'
  });
  ok(r.status === 7 && String(r.stdout).indexOf('MUTATION-KILLED') !== -1,
    '变异有牙——摘除超限提示 ⇒ 子进程断言红（rc=' + r.status + '）' +
    (r.status !== 7 ? ' stderr=' + String(r.stderr).slice(0, 400) : ''));
  fs.rmSync(dir, { recursive: true, force: true });

  // 存活对照（未变异副本 ⇒ 子进程断言绿）——证明子进程判据不是无条件红（假红护栏）
  const dir2 = fs.mkdtempSync(path.join(os.tmpdir(), 't145-pristine-'));
  fs.cpSync(webJs, path.join(dir2, 'web', 'js'), { recursive: true });
  const r2 = cpMod.spawnSync(process.execPath, [fileURLToPath(import.meta.url)], {
    env: Object.assign({}, process.env, { T145_MUTATE: '1', T145_COPY_DIR: dir2 }),
    encoding: 'utf8'
  });
  ok(r2.status === 0 && String(r2.stdout).indexOf('MUTATION-SURVIVED') !== -1,
    '存活对照——未变异副本子进程绿（rc=' + r2.status + '，判据非无条件红）');
  fs.rmSync(dir2, { recursive: true, force: true });
}

/* ---------------- 结果 ---------------- */
ok(pass + fails.length > 20, '探针断言总量充分（>20）');
console.log('T-145 spot exposure probe: PASS=' + pass + ' FAIL=' + fails.length);
if (fails.length) { process.exit(1); }
process.exit(0);
