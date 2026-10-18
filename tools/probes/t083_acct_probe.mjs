/*
 * T-083 页面探针（无头桩，非真实浏览器）—— 走查 acct.js 各功能区渲染。
 *
 * 形态对齐 t055_fund_probe.mjs 先例（T-021/T-071/T-055/T-070 形态）：node 级、mock DOM +
 * mock HG.api，逐功能点断言渲染结果。覆盖：
 *   · ACCT 键集与 ApiRoutes.java ACCT 模块 12 路径逐字对账（静态 9 + 参数化函数 3 = 20 方法对口径；T-197）；
 *   · 效果页同屏双栏（data-spot-column / data-derivative-column 成对恒在）+ price_as_of 三态
 *     （CURRENT/NON_CURRENT/MISSING）+ UNATTRIBUTED 残余行显式 + 汇总=Σ明细同屏 + 三态；
 *   · 上报页两栏恒在 + 缺价保留栏位 + 留痕三件（上报人/时间/期间）；
 *   · 三要素门禁 / 再平衡触发 / 关账三前置 10012 原文展示（data-gate-10012 锚标）；
 *   · 凭证 DRAFT_ONLY「不做总账过账」+「待企业财务复核入账」+ 岗位分离 + 冲销双人复核（DC-032）；
 *   · 凭证关联单据四键展示 + 套保计划深链（FR-ACCT-8 验收 2 / TE-T083-001 修复）：
 *     列表「关联单据」列 + 详情「关联单据」块（data-voucher-related 锚标）；
 *     hedgePlanId ⇒ navigate('/plan/{id}') 行为断言；无路由键（hedgeRelationId/settleCycleId/
 *     exposureId）展示为可选中文本；空值键不渲染、四键皆空显「—」；
 *   · 关账双人复核 + REOPEN 必填原因预检；
 *   · 评估近相关必填 linkageNote 预检 + 量化监测「不参与有效性判定」文案；
 *   · 写操作 idempotencyKey 全覆盖（10 类写操作）；
 *   · 负例有牙（变异子进程）：摘除双栏之一（data-spot-column）/ 摘除 UNATTRIBUTED 锚标 /
 *     摘除凭证关联单据四键展示（data-voucher-related）
 *     ⇒ 子进程同断言必红（退出码 7），父进程验证「有牙」。
 *
 * 用法：node tools/probes/t083_acct_probe.mjs → 全过打印 PASS 计数，退出码 0；任一失败退出码 1。
 *
 * ⚠️ ESM 静态 import 会先于任何语句求值（hoisting），故先 import 全局桩再动态 import 模块。
 */
/* ---------------- 全局桩先行（ns.js 顶层读取 window） ---------------- */
globalThis.window = {
  crypto: { randomUUID: () => 'probe-uuid' },
  setTimeout: () => 0,
  location: { host: 'probe.local', search: '' },
  addEventListener() {}
};

const { HG } = await import('../../web/js/core/ns.js');

/* ---------------- DOM 桩（对齐 t055/t071 minimal 形态） ---------------- */
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
HG.session = {
  state: { subAccounts: [
    { id: 8, code: 'SA8', name: '测试子账户A' },
    { id: 9, code: 'SA9', name: '测试子账户B' }
  ] },
  currentId: () => null
};
HG.api = {
  BASE: '/api/v1',
  DEFAULT_PAGE_SIZE: 20,
  PAGE_SIZES: [10, 20, 50, 100],
  newIdempotencyKey: () => 'probe-uuid',
  _responses: {},
  _calls: [],
  _stub(key, fn) { this._responses[key] = fn; },
  async get(path, query) {
    this._calls.push({ m: 'GET', p: path, q: query });
    const k = 'GET ' + path;
    if (!(k in this._responses)) throw { code: -1, message: '探针未 stub：' + k };
    return this._responses[k](query || {});
  },
  async post(path, body, opts) {
    this._calls.push({ m: 'POST', p: path, b: body, opts: opts || {} });
    this._allPosts = this._allPosts || [];
    this._allPosts.push({ p: path, b: body, opts: opts || {} });
    const k = 'POST ' + path;
    if (!(k in this._responses)) throw { code: -1, message: '探针未 stub：' + k };
    return this._responses[k](body || {}, opts || {});
  },
  async put() { throw { code: -1, message: 'acct 页不应调用 put' }; },
  async del() { throw { code: -1, message: 'acct 页不应调用 del' }; },
  async list() { throw { code: -1, message: 'acct 页不应调用 list' }; },
  async download() { throw { code: -1, message: 'acct 页不应调用 download（export 未注册）' }; }
};
HG.router = {
  _routes: {},
  register(p, fn) { this._routes[p] = fn; },
  navigate() {}, start() {}, setNotFound() {}, currentPath() { return '/acct'; }
};

await import('../../web/js/core/format.js');
await import('../../web/js/core/errmsg.js');
await import('../../web/js/core/router.js');
await import('../../web/js/core/ui.js');
await import('../../web/js/core/routes.js');

/* ---------------- 变异子进程模式（负例有牙） ----------------
 * T083_MUTATE=spot|unattr 时：载入 T083_COPY_DIR 下的**变异副本** acct.js，
 * 只跑对应锚标存在性断言；断言失败（变异被杀死）⇒ 打印 MUTATION-KILLED 退出码 7。
 * 父进程在全部断言后构造变异副本并 spawn 本文件验证「有牙」。
 */
const MUT = process.env.T083_MUTATE || '';

/* ---------------- 断言器 ---------------- */
let pass = 0; const fails = [];
function ok(cond, name) { if (cond) { pass++; } else { fails.push(name); console.error('FAIL: ' + name); } }
function textOf(node) { return node.textContent || ''; }
const tick = () => new Promise((r) => setTimeout(r, 0));

/* ---------------- 效果视图样例数据（父/子进程共用） ---------------- */
function makeEffectData() {
  return {
    subAccountId: 8, periodKey: '2026-09',
    summary: {
      id: 90, subAccountId: 8, periodKey: '2026-09', hedgePlanId: null, exposureId: null, unattributed: false,
      spotPnlRealized: '100.00', spotPnlUnrealized: '-30.50', spotPnlTotal: '69.50',
      spotPriceMissing: null, missingDesc: null, priceAsOf: null, nonCurrentPriceFlag: null,
      derivativePnlRealized: '-50.00', derivativePnlUnrealized: '-20.00', derivativePnlTotal: '-70.00',
      netEffect: '-0.50', unhedgedQty: '2.0000', unhedgedAmount: '1000.00', hedgedRatioActual: '0.85',
      calcTime: '2026-10-16T10:00:00'
    },
    details: [
      { id: 91, subAccountId: 8, periodKey: '2026-09', hedgePlanId: 12, exposureId: 34, unattributed: false,
        spotPnlRealized: '60.00', spotPnlUnrealized: '-20.00', spotPnlTotal: '40.00',
        spotPriceMissing: null, missingDesc: null, priceAsOf: '2026-10-10', nonCurrentPriceFlag: 'Y',
        derivativePnlRealized: '-30.00', derivativePnlUnrealized: '-12.00', derivativePnlTotal: '-42.00',
        netEffect: '-2.00', unhedgedQty: '1.0000', unhedgedAmount: '500.00', hedgedRatioActual: '0.90',
        calcTime: '2026-10-16T10:00:00' },
      { id: 92, subAccountId: 8, periodKey: '2026-09', hedgePlanId: null, exposureId: null, unattributed: true,
        spotPnlRealized: null, spotPnlUnrealized: null, spotPnlTotal: null,
        spotPriceMissing: 'Y', missingDesc: 'UNATTRIBUTED 品种级残余（无任何已录入现货价）', priceAsOf: null, nonCurrentPriceFlag: null,
        derivativePnlRealized: '-20.00', derivativePnlUnrealized: '-8.00', derivativePnlTotal: '-28.00',
        netEffect: '-28.00', unhedgedQty: null, unhedgedAmount: null, hedgedRatioActual: null,
        calcTime: '2026-10-16T10:00:00' }
    ],
    pendingExternalReview: 'Y'
  };
}

/* ---------------- 锚标查找辅助 ---------------- */
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
/** 聚合全部 innerHTML 片段（tableHTML 徽标为 HTML 字符串——stub 不解析为节点，须按串断言）。 */
function htmlAll(node) { return walkAll(node).map((n) => (n._h || '')).join(''); }
function findBtnByText(node, text) {
  let found = null;
  (function walk(n) {
    if (!n || !n.children || found) return;
    for (const c of n.children) {
      if ((c.attrs && c.attrs.type === 'button') && (c._t || c.textContent || '').indexOf(text) !== -1) { found = c; return; }
      walk(c);
    }
  })(node);
  return found;
}
function cardOf(btn) { let n = btn; while (n && String(n.className || '').indexOf('hg-card') === -1) { n = n.parentNode; } return n; }
function findInput(node, id) {
  let found = null;
  (function walk(n) {
    if (!n || !n.children || found) return;
    for (const c of n.children) {
      if (c.attrs && c.attrs.id === id) { found = c; return; }
      walk(c);
    }
  })(node);
  return found;
}

/* ================================================================== *
 * 子进程模式：只验证「摘除锚标 ⇒ 断言红」
 * ================================================================== */
if (MUT) {
  const acctPath = process.env.T083_COPY_DIR + '/web/js/pages/acct.js';
  await import(acctPath);
  const root = document.createElement('div');
  HG.pages.acct.renderAcct(root, { query: { tab: 'effect' } });
  // 播种数据态（与父进程同源构造；输入须显式赋值——桩 select 无默认选中语义）
  HG.api._stub('GET /acct/effect', () => makeEffectData());
  findInput(root, 'ac-e-sub').value = '8';
  findInput(root, 'ac-e-period').value = '2026-09';
  const btn = findBtnByText(root, '查询效果');
  await btn.click(); await tick();
  let killed = false; const names = [];
  if (MUT === 'reldocs') {
    // 摘除关联单据四键 ⇒ 列表字符串锚标与详情节点锚标双双缺失（TE-T083-001 靶向）
    while (root.firstChild) root.removeChild(root.firstChild);
    HG.pages.acct.renderAcct(root, { query: { tab: 'voucher' } });
    HG.api._stub('GET /acct/voucher', (q) => (q.id !== undefined ? {
      id: q.id, subAccountId: 8, voucherNo: 'AV202609-00001', periodKey: '2026-09', voucherType: 'CLOSE_PNL', status: 'POSTED',
      hedgePlanId: 12, hedgeRelationId: 15, settleCycleId: null, exposureId: 34, totalAmount: '5000.00',
      generatedBy: 1, generatedTime: '2026-10-16T13:00:00', pendingExternalReview: 'Y', draftPendingEntryNote: '待企业财务复核入账', lines: [], reviews: []
    } : { items: [{ id: 20, subAccountId: 8, voucherNo: 'AV202609-00001', periodKey: '2026-09', voucherType: 'CLOSE_PNL', status: 'POSTED', hedgePlanId: 12, hedgeRelationId: 15, settleCycleId: null, exposureId: 34, totalAmount: '5000.00', pendingExternalReview: 'Y' }], total: 1, page: 1, size: 20 }));
    const lvBtn = findBtnByText(root, '查询凭证');
    await lvBtn.click(); await tick();
    const dvBtn = findBtnByText(root, '查询详情');
    findInput(root, 'ac-vd-id').value = '20';
    await dvBtn.click(); await tick();
    const listOk = htmlAll(root).indexOf('data-voucher-related=') !== -1;
    const detOk = hasAttr(root, 'data-voucher-related');
    if (!listOk || !detOk) { killed = true; names.push('关联单据四键展示缺失（list=' + listOk + ' detail=' + detOk + '）'); }
  } else if (MUT === 'spot') {
    const spotGone = !findByAttr(root, 'data-spot-column', '1');
    const derivThere = findByAttr(root, 'data-derivative-column', '1') !== null;
    if (spotGone && derivThere) { killed = true; names.push('data-spot-column 缺失（衍生品栏仍在——击杀归因于 spot 变异本身）'); }
    else if (!spotGone) { /* 变异未生效——存活 */ }
    else { killed = true; names.push('双栏均缺失（探针环境异常，非靶向击杀）'); }
  } else if (MUT === 'unattr') {
    if (!hasAttr(root, 'data-unattributed')) { killed = true; names.push('data-unattributed 缺失'); }
  } else {
    console.error('未知变异：' + MUT);
    process.exit(2);
  }
  if (killed) { console.log('MUTATION-KILLED [' + MUT + '] ' + names.join('; ')); process.exit(7); }
  console.log('MUTATION-SURVIVED [' + MUT + ']（探针无牙——不合格）');
  process.exit(0);
}

/* ================================================================== *
 * 父进程模式：全量走查
 * ================================================================== */
const registeredPaths = [];
const realRegister = HG.router.register;
HG.router.register = function (p, fn) { registeredPaths.push(p); return realRegister.call(this, p, fn); };
await import('../../web/js/pages/acct.js');
HG.router.register = realRegister;

/* ================= 断言 0：ACCT 键集逐字对账（== ApiRoutes ACCT 12 路径 / 20 方法对；T-197 后口径） ================= */
const A = HG.routes.ACCT;
const NR = HG.routes.NOT_REGISTERED;
ok(A.HEDGE_RELATION === '/acct/hedge-relation', 'ACCT.HEDGE_RELATION 逐字一致');
ok(A.EFFECTIVENESS === '/acct/effectiveness', 'ACCT.EFFECTIVENESS 逐字一致');
ok(A.REBALANCE === '/acct/rebalance', 'ACCT.REBALANCE 逐字一致');
ok(A.VOUCHER === '/acct/voucher', 'ACCT.VOUCHER 逐字一致');
ok(A.PERIOD === '/acct/period', 'ACCT.PERIOD 逐字一致');
ok(A.EFFECT === '/acct/effect', 'ACCT.EFFECT 逐字一致');
ok(A.DUAL_PNL_REPORT === '/acct/report/dual-pnl', 'ACCT.DUAL_PNL_REPORT 逐字一致');
ok(typeof A.voucherReview === 'function' && A.voucherReview(5) === '/acct/voucher/5/review', 'ACCT.voucherReview 函数键 → /acct/voucher/{id}/review');
ok(typeof A.voucherReverse === 'function' && A.voucherReverse(6) === '/acct/voucher/6/reverse', 'ACCT.voucherReverse 函数键 → /acct/voucher/{id}/reverse');
ok(typeof A.periodClose === 'function' && A.periodClose(3) === '/acct/period/3/close', 'ACCT.periodClose 函数键 → /acct/period/{id}/close');
ok(A.voucherReview('a/b') === '/acct/voucher/a%2Fb/review', 'ACCT.voucherReview 走 encodeURIComponent（enc 先例）');
// T-197：自检面两键迁正键（原 NOT_REGISTERED.ACCT_SELFCHECK/ACCT_EXPORT 已删）
ok(A.SELFCHECK === '/acct/selfcheck', 'ACCT.SELFCHECK 逐字一致（T-197 迁正键）');
ok(A.EXPORT === '/acct/export', 'ACCT.EXPORT 逐字一致（T-197 迁正键）');
ok(NR.ACCT_SELFCHECK === undefined && NR.ACCT_EXPORT === undefined, 'NOT_REGISTERED 的 ACCT 两键已删除（T-197 迁出）');
// 覆盖：12 路径全接（静态 9 + 参数化 3）
const pathSet = new Set([A.HEDGE_RELATION, A.EFFECTIVENESS, A.REBALANCE, A.VOUCHER, A.PERIOD, A.EFFECT, A.DUAL_PNL_REPORT,
  A.SELFCHECK, A.EXPORT,
  A.voucherReview('X').replace('X', '{id}'), A.voucherReverse('X').replace('X', '{id}'), A.periodClose('X').replace('X', '{id}')]);
ok(pathSet.size === 12, 'ACCT 键集覆盖 12 路径（静态 9 + 参数化 3；T-197）');
// 20 方法对口径（= ApiRoutes BY_MODULE_ROUTES ACCT 全量；静态逐字镜像断言）
const TWENTY = ['GET /acct/hedge-relation', 'POST /acct/hedge-relation', 'GET /acct/effectiveness', 'POST /acct/effectiveness',
  'GET /acct/rebalance', 'POST /acct/rebalance', 'GET /acct/voucher', 'POST /acct/voucher',
  'POST /acct/voucher/{id}/review', 'POST /acct/voucher/{id}/reverse', 'GET /acct/period', 'POST /acct/period',
  'POST /acct/period/{id}/close', 'GET /acct/effect', 'POST /acct/effect', 'GET /acct/report/dual-pnl', 'POST /acct/report/dual-pnl',
  'GET /acct/selfcheck', 'POST /acct/selfcheck', 'GET /acct/export'];
ok(TWENTY.length === 20, 'ACCT 方法对口径 = 20（与 ApiRoutes BY_MODULE_ROUTES ACCT 一致；T-197）');
ok(registeredPaths.includes('/acct'), '/acct 路由已注册');
// T-079 O-2 闭环：陈旧注释订正（旧称「其余 8 条已声明路径未注册」已消亡）
const routesSrc = (await import('node:fs')).readFileSync(new URL('../../web/js/core/routes.js', import.meta.url), 'utf8');
ok(routesSrc.indexOf('voucher/period/effect/dual-pnl/selfcheck/export）未注册') === -1, 'routes.js 陈旧注释（8 条未注册旧口径原文）已消亡（T-079 O-2 闭环）');
ok(routesSrc.indexOf('实际未注册的仅 **2 条**') === -1, 'routes.js 陈旧注释（实际未注册仅 2 条）已消亡（T-197 迁正键）');
ok(routesSrc.indexOf('10 路径') === -1 || routesSrc.indexOf('12 路径') !== -1, 'routes.js 注释含现行 12 路径口径（T-197）');
ok(routesSrc.indexOf('17 (方法,路径) 对') === -1 && routesSrc.indexOf('20 (方法,路径) 对') !== -1, 'routes.js 注释含现行 20 对口径（T-197）');

/* ---------------- 页面渲染与 Tab 走查辅助 ---------------- */
const root = document.createElement('div');
function switchTab(key) {
  while (root.firstChild) root.removeChild(root.firstChild);
  HG.pages.acct.renderAcct(root, { query: { tab: key } });
}
function stubConfirm(capture) {
  HG.ui.confirm = capture ? async (msg) => { globalThis.__confirmMsg = msg; return true; } : async () => true;
}

switchTab('effect');
ok(textOf(root).includes('套保会计（ACCT）'), '页面标题渲染');
ok(textOf(root).includes('FR-ACCT-1~14'), '页面描述含 FR 区间');
ok(textOf(root).includes('待外部复核'), '页头横幅含 R-ACCT-10 待外部复核');
ok(textOf(root).includes('口径单一'), '页头横幅含 R-ACCT-12 口径单一');
ok(textOf(root).includes('期权与期货同等对待'), '页头横幅含 R-ACCT-7 期权同等对待');
ok(textOf(root).includes('/acct/selfcheck') && textOf(root).includes('/acct/export'), '页头横幅披露自检面端点（T-197 已接入）');

/* ================= Tab effect：同屏双栏 + 三态 + UNATTRIBUTED + 汇总勾稽 + 三态齐全 ================= */
switchTab('effect');
{
  const btn = findBtnByText(root, '查询效果');
  // 空态
  HG.api._stub('GET /acct/effect', () => ({ subAccountId: 8, periodKey: '2026-09', summary: null, details: [], pendingExternalReview: 'Y' }));
  findInput(root, 'ac-e-sub').value = '8';
  findInput(root, 'ac-e-period').value = '2026-09';
  await btn.click(); await tick();
  ok(textOf(cardOf(btn)).includes('暂无明细'), 'effect：空态显式（明细空=空态非错误）');
  // 数据态
  HG.api._stub('GET /acct/effect', () => makeEffectData());
  HG.api._calls.length = 0;
  await btn.click(); await tick();
  const body = cardOf(btn);
  ok(findByAttr(root, 'data-spot-column', '1') !== null, 'effect：实货栏锚标在（双栏之一）');
  ok(findByAttr(root, 'data-derivative-column', '1') !== null, 'effect：衍生品栏锚标在（双栏之二）');
  ok(textOf(body).includes('实货盈亏（被套期项目）') && textOf(body).includes('衍生品盈亏（套期工具'), 'effect：双栏栏名恒在（R-ACCT-4）');
  ok(textOf(body).includes('已实现（平仓）') && textOf(body).includes('未实现（浮动'), 'effect：衍生品已实现/未实现拆分（FR-ACCT-5 验收 2）');
  ok(textOf(body).includes('69.50') && textOf(body).includes('-70.00'), 'effect：汇总双侧值渲染');
  ok(textOf(body).includes('汇总 = Σ 明细') || textOf(body).includes('Σ 明细'), 'effect：汇总=明细之和同屏勾稽（FR-ACCT-5 验收 4）');
  // price_as_of 三态（summary 双栏=真实节点；明细表内=tableHTML 字符串，按 htmlAll 断言）
  ok(findByAttr(root, 'data-spot-price-state', 'CURRENT') !== null, 'effect：三态①当日价徽标（summary）');
  ok(htmlAll(root).indexOf('data-spot-price-state="NON_CURRENT"') !== -1, 'effect：三态②非当日价徽标');
  ok(textOf(body).includes('非当日价') && textOf(body).includes('2026-10-10'), 'effect：非当日价必带 price_as_of（Q-12/R-ACCT-9）');
  ok(htmlAll(root).indexOf('data-spot-price-state="MISSING"') !== -1, 'effect：三态③无法计算徽标');
  ok(textOf(body).includes('无法计算') && textOf(body).includes('UNATTRIBUTED 品种级残余'), 'effect：无法计算 + 缺失原因原文（栏位不省略）');
  // UNATTRIBUTED 行（残余横幅=真实节点锚标）
  ok(findByAttr(root, 'data-unattributed', '1') !== null, 'effect：UNATTRIBUTED 残余行锚标（显式呈现）');
  ok(textOf(body).includes('#12'), 'effect：明细行含计划归属（计划 #12）');
  ok(textOf(body).includes('待外部复核'), 'effect：待外部复核标注（R-ACCT-10）');
  // 查询参数透传
  const gCall = HG.api._calls.find((c) => c.m === 'GET' && c.p === '/acct/effect');
  ok(gCall && gCall.q.subAccountId === '8' && gCall.q.periodKey === '2026-09', 'effect：查询参数透传（subAccountId/periodKey）');
  // 期间形态预检
  HG.api._calls.length = 0;
  findInput(root, 'ac-e-period').value = '202609';
  await btn.click(); await tick();
  ok(HG.api._calls.length === 0, 'effect：期间形态非法被预检拦截（yyyy-MM）');
  findInput(root, 'ac-e-period').value = '2026-09';
  // 错误态
  HG.api._stub('GET /acct/effect', () => { throw { code: 10004, message: '子账户数据权限不足' }; });
  await btn.click(); await tick();
  ok(textOf(cardOf(btn)).includes('加载失败'), 'effect：错误态显式（三态齐全）');
  ok(textOf(cardOf(btn)).includes('重试'), 'effect：错误态含重试入口');
  // 计算写操作：幂等 + 请求体契约
  stubConfirm(true);
  HG.api._calls.length = 0;
  HG.api._stub('POST /acct/effect', () => makeEffectData());
  const cBtn = findBtnByText(root, '计算套保效果');
  await cBtn.click(); await tick();
  const cCall = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/effect');
  ok(!!cCall, 'effect：POST /acct/effect 已调用（计算）');
  ok(cCall && cCall.opts.idempotencyKey === 'probe-uuid', 'effect：计算带 Idempotency-Key（写操作幂等）');
  ok(cCall && cCall.b.subAccountId === 8 && cCall.b.periodKey === '2026-09', 'effect：计算请求体契约对齐（subAccountId/periodKey）');
  stubConfirm(false);
}

/* ================= Tab dualPnl：两栏恒在 + 缺价保留 + 留痕三件 + 幂等 ================= */
switchTab('dualPnl');
{
  ok(textOf(root).includes('两栏恒在'), 'dualPnl：R-ACCT-4 两栏恒在红线文案');
  ok(textOf(root).includes('禁止只报衍生品盈亏'), 'dualPnl：禁止只报衍生品盈亏（17 号文）');
  ok(textOf(root).includes('汇总上报'), 'dualPnl：汇总上报（子账户留空）声明');
  // 提交：期间预检
  stubConfirm(true);
  HG.api._calls.length = 0;
  findInput(root, 'ac-dp-period').value = '2026-9';
  const sBtn = findBtnByText(root, '提交上报');
  await sBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p === '/acct/report/dual-pnl').length === 0, 'dualPnl：期间形态非法被预检拦截');
  // 提交成功（缺价行：实货栏保留 + 缺失原因 + 留痕三件）——select 须显式赋值（桩 select 无默认选中语义）
  HG.api._stub('POST /acct/report/dual-pnl', () => ({
    id: 7, subAccountId: null, reportNo: 'DP-202609-0001', reportPeriod: '2026-09', reportType: 'MONTHLY',
    spotPnlColumnPresent: 'Y', spotPnlValue: null, spotPnlMissingReason: '【子账户 8】实货盈亏无法计算（无任何已录入现货价）',
    derivativePnlValue: '-70.00', derivativePnlRealized: '-50.00', derivativePnlUnrealized: '-20.00',
    netEffect: '-70.00', dataCompletenessNote: '汇总上报（sub_account_id=NULL）：覆盖 1 个已评估子账户；',
    submittedBy: 1, submittedTime: '2026-10-16T11:00:00', reportPeriodSubmitted: '2026-09', pendingExternalReview: 'Y'
  }));
  HG.api._calls.length = 0;
  findInput(root, 'ac-dp-period').value = '2026-09';
  findInput(root, 'ac-dp-sub').value = '';
  findInput(root, 'ac-dp-type').value = 'MONTHLY';
  await sBtn.click(); await tick();
  const dp = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/report/dual-pnl');
  ok(!!dp, 'dualPnl：POST 已调用');
  ok(dp && dp.opts.idempotencyKey === 'probe-uuid', 'dualPnl：提交带 Idempotency-Key');
  ok(dp && dp.b.subAccountId === null && dp.b.reportPeriod === '2026-09' && dp.b.reportType === 'MONTHLY', 'dualPnl：请求体契约对齐（subAccountId 可空=汇总/reportPeriod/reportType）');
  const sCard = cardOf(sBtn);
  ok(textOf(sCard).includes('DP-202609-0001'), 'dualPnl：上报编号渲染');
  ok(textOf(sCard).includes('实货栏保留'), 'dualPnl：缺价时实货栏保留显式（FR-ACCT-10 验收 2）');
  ok(textOf(sCard).includes('无任何已录入现货价'), 'dualPnl：缺失原因原文渲染');
  ok(textOf(sCard).includes('上报人') && textOf(sCard).includes('11:00:00') && textOf(sCard).includes('2026-09'), 'dualPnl：留痕三件（上报人/时间/期间）');
  // 查询：reportPeriod 必填透传 + 缺价行渲染 + data-spot-column 锚标
  HG.api._stub('GET /acct/report/dual-pnl', (q) => ([
    { id: 7, subAccountId: null, reportNo: 'DP-202609-0001', reportPeriod: '2026-09', reportType: 'MONTHLY',
      spotPnlColumnPresent: 'Y', spotPnlValue: null, spotPnlMissingReason: '实货盈亏无法计算（无任何已录入现货价）',
      derivativePnlValue: '-70.00', derivativePnlRealized: '-50.00', derivativePnlUnrealized: '-20.00',
      netEffect: '-70.00', dataCompletenessNote: '…', submittedBy: 1, submittedTime: '2026-10-16T11:00:00',
      reportPeriodSubmitted: '2026-09', pendingExternalReview: 'Y' },
    { id: 8, subAccountId: 8, reportNo: 'DP-202609-0002', reportPeriod: '2026-09', reportType: 'MONTHLY',
      spotPnlColumnPresent: 'Y', spotPnlValue: '69.50', spotPnlMissingReason: null,
      derivativePnlValue: '-70.00', derivativePnlRealized: '-50.00', derivativePnlUnrealized: '-20.00',
      netEffect: '-0.50', dataCompletenessNote: '实货盈亏使用当日价；', submittedBy: 1, submittedTime: '2026-10-16T11:05:00',
      reportPeriodSubmitted: '2026-09', pendingExternalReview: 'Y' }
  ]));
  const qBtn = findBtnByText(root, '查询上报记录');
  HG.api._calls.length = 0;
  findInput(root, 'ac-dq-period').value = '2026-09';
  await qBtn.click(); await tick();
  const dq = HG.api._calls.find((c) => c.m === 'GET' && c.p === '/acct/report/dual-pnl');
  ok(dq && dq.q.reportPeriod === '2026-09', 'dualPnl：查询 reportPeriod 透传');
  const qCard = cardOf(qBtn);
  ok(textOf(qCard).includes('DP-202609-0001') && textOf(qCard).includes('DP-202609-0002'), 'dualPnl：数据态渲染两条上报');
  ok(htmlAll(root).indexOf('data-spot-column="1"') !== -1, 'dualPnl：列表实货栏锚标（两栏恒在）');
  ok(htmlAll(root).indexOf('data-spot-price-state="MISSING"') !== -1, 'dualPnl：缺价行 MISSING 徽标（不省略）');
  ok(textOf(qCard).includes('69.50'), 'dualPnl：有价行实货值渲染');
  ok(textOf(qCard).includes('栏位保留未省略'), 'dualPnl：缺价警示条（不得只报衍生品侧）');
  // 错误态
  HG.api._stub('GET /acct/report/dual-pnl', () => { throw { code: 10001, message: 'reportPeriod 须为 yyyy-MM 形态' }; });
  await qBtn.click(); await tick();
  ok(textOf(cardOf(qBtn)).includes('加载失败') || textOf(cardOf(qBtn)).includes('10001'), 'dualPnl：错误态显式（三态齐全）');
  stubConfirm(false);
}

/* ================= Tab relation：三要素门禁 10012 原文 + 待外部复核 + 幂等 ================= */
switchTab('relation');
{
  ok(textOf(root).includes('三要素门禁'), 'relation：三要素门禁文案（R-ACCT-1）');
  ok(textOf(root).includes('并列列出缺失项'), 'relation：并列缺失项声明');
  stubConfirm(true);
  // 10012：并列缺失项原文展示（不得吞成通用错误）
  HG.api._stub('POST /acct/hedge-relation', () => { throw { code: 10012, message: '套期关系文档三要素缺失（并列）：①有效性评估方法；②再平衡机制；复现=补齐计划书面文件后重试' } });
  const gBtn = findBtnByText(root, '生成套期关系');
  findInput(root, 'ac-r-plan').value = '12';
  findInput(root, 'ac-r-exp').value = '34';
  HG.api._calls.length = 0;
  await gBtn.click(); await tick();
  const gCard = cardOf(gBtn);
  ok(textOf(gCard).includes('[10012]'), 'relation：10012 错误码原文（经 errmsg.render，非通用错误）');
  ok(textOf(gCard).includes('再平衡机制') && textOf(gCard).includes('有效性评估方法'), 'relation：并列缺失项原文展示（三要素逐项）');
  ok(findByAttr(root, 'data-gate-10012', '1') !== null, 'relation：10012 门禁锚标（data-gate-10012）');
  const g10012 = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/hedge-relation');
  ok(g10012 && g10012.opts.idempotencyKey === 'probe-uuid', 'relation：生成带 Idempotency-Key');
  // 成功态 + 请求体契约
  HG.api._stub('POST /acct/hedge-relation', (b) => ({
    id: 15, subAccountId: 8, relationNo: 'HR20261016-0001', hedgePlanId: b.planId, hedgedItemExposureId: b.exposureId,
    hedgingInstrumentType: b.instrumentType, hedgingInstrumentDesc: b.instrumentDesc, hedgeDocId: 77,
    pendingExternalReview: 'Y', status: 'DESIGNATED', designatedTime: '2026-10-16T12:00:00', terminatedTime: null
  }));
  findInput(root, 'ac-r-type').value = 'FUTURES';
  findInput(root, 'ac-r-desc').value = 'rb2605 多头 10 手';
  HG.api._calls.length = 0;
  await gBtn.click(); await tick();
  const gCall = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/hedge-relation');
  ok(gCall && gCall.b.planId === 12 && gCall.b.exposureId === 34 && gCall.b.instrumentType === 'FUTURES', 'relation：请求体契约对齐（planId/exposureId/instrumentType）');
  ok(textOf(cardOf(gBtn)).includes('HR20261016-0001') && textOf(cardOf(gBtn)).includes('待外部复核'), 'relation：成功态渲染关系编号 + 待外部复核');
  // 列表：状态徽标 + 空态 + 错误态
  HG.api._stub('GET /acct/hedge-relation', () => ({ items: [{ id: 15, subAccountId: 8, relationNo: 'HR20261016-0001', hedgePlanId: 12, hedgedItemExposureId: 34, hedgingInstrumentType: 'FUTURES', hedgingInstrumentDesc: 'rb2605', hedgeDocId: 77, pendingExternalReview: 'Y', status: 'EFFECTIVE', designatedTime: '2026-10-16T12:00:00' }], total: 1, page: 1, size: 20 }));
  const lBtn = findBtnByText(root, '查询套期关系');
  await lBtn.click(); await tick();
  const lCard = cardOf(lBtn);
  ok(textOf(lCard).includes('HR20261016-0001'), 'relation：列表数据态渲染');
  ok(htmlAll(lCard).indexOf('data-ac-relation-status="EFFECTIVE"') !== -1, 'relation：状态徽标锚标');
  ok(htmlAll(lCard).indexOf('data-external-review="Y"') !== -1, 'relation：列表行待外部复核徽标');
  HG.api._stub('GET /acct/hedge-relation', () => ({ items: [], total: 0, page: 1, size: 20 }));
  await lBtn.click(); await tick();
  ok(textOf(cardOf(lBtn)).includes('暂无数据'), 'relation：空态显式');
  HG.api._stub('GET /acct/hedge-relation', () => { throw { code: -1, message: '网络故障' }; });
  await lBtn.click(); await tick();
  ok(textOf(cardOf(lBtn)).includes('加载失败'), 'relation：错误态显式');
  stubConfirm(false);
}

/* ================= Tab effectiveness：三项观察点 + 监测不判定 + 近相关预检 + 幂等 ================= */
switchTab('effectiveness');
{
  ok(textOf(root).includes('不参与有效性判定'), 'effectiveness：量化监测不参与判定（Q-7）');
  ok(textOf(root).includes('80%–125%') === false || textOf(root).includes('无 80%–125% 判据'), 'effectiveness：无 80%–125% 判据入口声明');
  ok(textOf(root).includes('品种相关性') && textOf(root).includes('方向一致性') && textOf(root).includes('期限对应性'), 'effectiveness：三项观察点名齐全');
  stubConfirm(true);
  // 近相关必填 linkageNote 预检
  HG.api._calls.length = 0;
  findInput(root, 'ac-ev-rel').value = '15';
  findInput(root, 'ac-ev-period').value = '2026-09';
  findInput(root, 'ac-ev-link').value = 'N';
  findInput(root, 'ac-ev-note').value = '';
  const sBtn = findBtnByText(root, '提交评估');
  await sBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p === '/acct/effectiveness' && c.m === 'POST').length === 0, 'effectiveness：近相关未填说明被预检拦截（Q-7）');
  // 提交成功 + 契约
  HG.api._stub('POST /acct/effectiveness', (b) => ({
    id: 30, subAccountId: 8, hedgeRelationId: b.hedgeRelationId, periodKey: b.periodKey, assessMethod: 'QUALITATIVE_ECONOMIC_RELATION',
    observerLinkage: b.observerLinkage, linkageNote: b.linkageNote, observerDirection: b.observerDirection, observerTerm: b.observerTerm,
    exposureExpectedDate: b.exposureExpectedDate, monitorDeviationRatio: b.monitorDeviationRatio, monitorReviewFlag: 'Y',
    conclusion: 'EFFECTIVE', needActionFlag: 'N', assessorId: 1, assessTime: '2026-10-16T12:00:00', isCorrected: 'N', pendingExternalReview: 'Y'
  }));
  findInput(root, 'ac-ev-note').value = '近相关书面经济关系说明';
  findInput(root, 'ac-ev-dir').value = 'Y';
  findInput(root, 'ac-ev-term').value = 'Y';
  HG.api._calls.length = 0;
  await sBtn.click(); await tick();
  const ev = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/effectiveness');
  ok(!!ev, 'effectiveness：POST 已调用');
  ok(ev && ev.opts.idempotencyKey === 'probe-uuid', 'effectiveness：提交带 Idempotency-Key');
  ok(ev && ev.b.observerLinkage === 'N' && ev.b.linkageNote === '近相关书面经济关系说明' && ev.b.observerDirection === 'Y' && ev.b.observerTerm === 'Y', 'effectiveness：请求体契约对齐（观察点三值 + linkageNote）');
  ok(textOf(cardOf(sBtn)).includes('有效'), 'effectiveness：结论渲染');
  // monitorOnly 清单参数透传
  HG.api._stub('GET /acct/effectiveness', () => ({ items: [{ id: 30, subAccountId: 8, hedgeRelationId: 15, periodKey: '2026-09', observerLinkage: 'Y', observerDirection: 'Y', observerTerm: 'Y', monitorDeviationRatio: '0.62', monitorReviewFlag: 'Y', conclusion: 'EFFECTIVE', assessorId: 1, assessTime: '2026-10-16T12:00:00', pendingExternalReview: 'Y' }], total: 1, page: 1, size: 20 }));
  const qBtn = findBtnByText(root, '查询评估记录');
  HG.api._calls.length = 0;
  const monBox = findInput(root, 'ac-evq-monitor');
  monBox.checked = true;
  await qBtn.click(); await tick();
  const evq = HG.api._calls.find((c) => c.m === 'GET' && c.p === '/acct/effectiveness');
  ok(evq && evq.q.monitorOnly === 'true', 'effectiveness：monitorOnly 复核清单参数透传（CFG-07b）');
  ok(htmlAll(root).indexOf('data-ac-conclusion="EFFECTIVE"') !== -1, 'effectiveness：结论徽标锚标');
  ok(htmlAll(root).indexOf('data-yn="Y"') !== -1, 'effectiveness：观察点 Y/N 徽标（逐项可见，FR-ACCT-4 验收 1）');
  ok(textOf(cardOf(qBtn)).includes('不参与有效性判定'), 'effectiveness：列表监测不判定注脚');
  monBox.checked = false;
  // 空态
  HG.api._stub('GET /acct/effectiveness', () => ({ items: [], total: 0, page: 1, size: 20 }));
  await qBtn.click(); await tick();
  ok(textOf(cardOf(qBtn)).includes('暂无数据'), 'effectiveness：空态显式');
  // 未评估提醒
  HG.api._stub('GET /acct/effectiveness', (q) => (q.unassessedPeriod === '2026-09' ? [{ unassessedPeriod: '2026-09', relation: { hedgePlanId: 12, relationNo: 'HR20261016-0001', subAccountId: 8, status: 'DESIGNATED' } }] : []));
  const wBtn = findBtnByText(root, '查询未评估清单');
  findInput(root, 'ac-evw-period').value = '2026-09';
  await wBtn.click(); await tick();
  const wCard = cardOf(wBtn);
  ok(textOf(wCard).includes('未评估提醒') && textOf(wCard).includes('HR20261016-0001'), 'effectiveness：未评估提醒渲染（FR-ACCT-2 验收 4）');
  const wc = HG.api._calls.find((c) => c.m === 'GET' && c.q && c.q.unassessedPeriod === '2026-09');
  ok(!!wc, 'effectiveness：unassessedPeriod 参数透传');
  stubConfirm(false);
}

/* ================= Tab rebalance：四类触发 + 偏离值只读 + 10012 当前偏离值 + 幂等 ================= */
switchTab('rebalance');
{
  ok(textOf(root).includes('不采信客户端声明'), 'rebalance：偏离值服务端复核红线（R-ACCT-3）');
  ok(textOf(root).includes('敞口侧与工具侧必须各设一项'), 'rebalance：两侧各设一项触发条件（Q-8）');
  ok(textOf(root).includes('不得只改数字'), 'rebalance：重新指定不得只改数字');
  ok(findInput(root, 'ac-rb-scen') !== null, 'rebalance：触发场景为下拉（四类）');
  stubConfirm(true);
  // 10012：当前偏离值原文展示
  HG.api._stub('POST /acct/rebalance', () => { throw { code: 10012, message: '触发条件不满足：当前偏离值 敞口变动=12.50%（阈值 20%）；复现=核实数据面后重试' } });
  const gBtn = findBtnByText(root, '执行再平衡');
  findInput(root, 'ac-rb-rel').value = '15';
  findInput(root, 'ac-rb-scen').value = 'EXPOSURE_DELTA';
  findInput(root, 'ac-rb-reason').value = '敞口变动触发';
  findInput(root, 'ac-rb-ratio').value = '0.85';
  findInput(root, 'ac-rb-qty').value = '10';
  findInput(root, 'ac-rb-fb').value = 'N';
  HG.api._calls.length = 0;
  await gBtn.click(); await tick();
  const gCard = cardOf(gBtn);
  ok(textOf(gCard).includes('当前偏离值') && textOf(gCard).includes('12.50%'), 'rebalance：10012 当前偏离值原文展示（FR-ACCT-3 验收 4）');
  ok(findByAttr(root, 'data-gate-10012', '1') !== null, 'rebalance：10012 门禁锚标');
  const rb = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/rebalance');
  ok(rb && rb.opts.idempotencyKey === 'probe-uuid', 'rebalance：执行带 Idempotency-Key');
  // 成功态 + 契约（planFeedbackRequired 默认 N）
  HG.api._stub('POST /acct/rebalance', (b) => ({
    id: 4, subAccountId: 8, hedgeRelationId: b.hedgeRelationId, rebalanceNo: 'RB20261016-0001', triggerType: b.triggerScenario,
    triggerSide: 'EXPOSURE_SIDE', triggerReason: b.triggerReason, triggerExposureDelta: '0.2550', triggerRatioDeviation: null,
    triggerThreshold: '0.20', ratioBefore: '0.75', ratioAfter: b.ratioAfter, qtyBefore: '8', qtyAfter: b.qtyAfter,
    newHedgeDocId: 88, newHedgeRelationId: 16, executorId: 1, rebalanceTime: '2026-10-16T12:30:00', planFeedbackFlag: b.planFeedbackRequired
  }));
  HG.api._calls.length = 0;
  await gBtn.click(); await tick();
  const rb2 = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/rebalance');
  ok(rb2 && rb2.b.triggerScenario === 'EXPOSURE_DELTA' && rb2.b.ratioAfter === '0.85' && rb2.b.qtyAfter === '10' && rb2.b.planFeedbackRequired === 'N', 'rebalance：请求体契约对齐（triggerScenario/ratioAfter/qtyAfter/planFeedbackRequired）');
  ok(textOf(cardOf(gBtn)).includes('RB20261016-0001'), 'rebalance：成功态渲染记录编号');
  ok(textOf(cardOf(gBtn)).includes('不得只改数字') || textOf(cardOf(gBtn)).includes('重新生成'), 'rebalance：成功态声明书面文件重新生成');
  // 历史：hedgeRelationId 透传 + 偏离值只读展示
  HG.api._stub('GET /acct/rebalance', (q) => ([{ id: 4, subAccountId: 8, hedgeRelationId: Number(q.hedgeRelationId), rebalanceNo: 'RB20261016-0001', triggerType: 'EXPOSURE_DELTA', triggerSide: 'EXPOSURE_SIDE', triggerReason: '敞口变动触发', triggerExposureDelta: '0.2550', triggerRatioDeviation: null, triggerThreshold: '0.20', ratioBefore: '0.75', ratioAfter: '0.85', qtyBefore: '8', qtyAfter: '10', newHedgeDocId: 88, newHedgeRelationId: 16, executorId: 1, rebalanceTime: '2026-10-16T12:30:00', planFeedbackFlag: 'N' }]));
  const hBtn = findBtnByText(root, '查询再平衡史');
  findInput(root, 'ac-rbh-rel').value = '15';
  await hBtn.click(); await tick();
  const hc = HG.api._calls.find((c) => c.m === 'GET' && c.p === '/acct/rebalance');
  ok(hc && hc.q.hedgeRelationId === '15', 'rebalance：历史查询 hedgeRelationId 透传');
  ok(textOf(cardOf(hBtn)).includes('只读展示') || textOf(cardOf(hBtn)).includes('只读'), 'rebalance：偏离值只读展示声明（无输入项）');
  ok(textOf(cardOf(hBtn)).includes('0.2550') && textOf(cardOf(hBtn)).includes('0.20'), 'rebalance：历史行偏离值/阈值渲染');
  // 错误态
  HG.api._stub('GET /acct/rebalance', () => { throw { code: 10001, message: 'hedgeRelationId 必填' }; });
  await hBtn.click(); await tick();
  ok(textOf(cardOf(hBtn)).includes('加载失败') || textOf(cardOf(hBtn)).includes('10001'), 'rebalance：错误态显式');
  stubConfirm(false);
}

/* ================= Tab voucher：DRAFT_ONLY + 岗位分离 + 冲销双人复核 + 草稿删除 ================= */
switchTab('voucher');
{
  ok(textOf(root).includes('不做总账过账'), 'voucher：DRAFT_ONLY 不做总账过账（Q-9）');
  ok(textOf(root).includes('待企业财务复核入账'), 'voucher：草稿标注文案（Q-9/R-ACCT-11）');
  ok(textOf(root).includes('生成人与审核人不得为同一人'), 'voucher：岗位分离文案逐字（R-ACCT-6）');
  ok(textOf(root).includes('只能冲销') || textOf(root).includes('不得修改'), 'voucher：正式凭证不得修改只能冲销（R-ACCT-5）');
  ok(textOf(root).includes('无编辑 / 删除入口'), 'voucher：列表无编辑/删除入口声明（FR-ACCT-8 验收 3）');
  stubConfirm(true);
  // 草稿生成：子账户预检 + 幂等 + 契约
  const dBtn = findBtnByText(root, '生成草稿');
  findInput(root, 'ac-v-sub').value = '';
  HG.api._calls.length = 0;
  findInput(root, 'ac-v-period').value = '2026-09';
  findInput(root, 'ac-v-type').value = 'CLOSE_PNL';
  findInput(root, 'ac-v-summary').value = '平仓盈亏结转';
  findInput(root, 'ac-v-amt').value = '5000.00';
  await dBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p === '/acct/voucher' && c.m === 'POST').length === 0, 'voucher：缺子账户被预检拦截（按子账户出账，R-ACCT-5）');
  HG.api._stub('POST /acct/voucher', (b) => ({
    id: 20, subAccountId: b.subAccountId, voucherNo: 'AV202609-00001', periodKey: b.periodKey, voucherDate: '2026-09-30',
    voucherType: b.voucherType, hedgePlanId: b.hedgePlanId, hedgeRelationId: b.hedgeRelationId, settleCycleId: null,
    exposureId: null, status: 'DRAFT', totalAmount: b.amount, generatedBy: 1, generatedTime: '2026-10-16T13:00:00',
    reversalOfId: null, reversedById: null, reversalReason: null, pendingExternalReview: 'Y', draftPendingEntryNote: '待企业财务复核入账'
  }));
  findInput(root, 'ac-v-sub').value = '8';
  HG.api._calls.length = 0;
  await dBtn.click(); await tick();
  const dv = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/voucher');
  ok(!!dv, 'voucher：POST 草稿已调用');
  ok(dv && dv.opts.idempotencyKey === 'probe-uuid', 'voucher：草稿生成带 Idempotency-Key');
  ok(dv && dv.b.subAccountId === 8 && dv.b.voucherType === 'CLOSE_PNL' && dv.b.summary === '平仓盈亏结转' && dv.b.amount === '5000.00', 'voucher：请求体契约对齐（subAccountId/voucherType/summary/amount）');
  ok(textOf(cardOf(dBtn)).includes('AV202609-00001') && textOf(cardOf(dBtn)).includes('待企业财务复核入账'), 'voucher：成功态渲染凭证编号 + 复核入账标注');
  // 审核岗位分离
  stubConfirm(true);
  // 驳回未填原因预检
  findInput(root, 'ac-vr-id').value = '20';
  findInput(root, 'ac-vr-action').value = 'REJECT';
  findInput(root, 'ac-vr-reject').value = '';
  const rvBtn = findBtnByText(root, '提交审核动作');
  HG.api._calls.length = 0;
  await rvBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p.indexOf('/review') !== -1).length === 0, 'voucher：驳回未填原因被预检拦截（FR-ACCT-7 验收 3）');
  // 驳回 + 契约
  HG.api._stub('POST /acct/voucher/20/review', (b) => ({ id: 3, subAccountId: 8, voucherId: 20, nodeSeq: 2, action: b.action, generatorId: 1, reviewerId: 2, coReviewerId: null, reviewResult: 'REJECTED', reviewComment: null, rejectReason: b.rejectReason, actionTime: '2026-10-16T13:10:00' }));
  findInput(root, 'ac-vr-reject').value = '金额与结算单不符';
  HG.api._calls.length = 0;
  await rvBtn.click(); await tick();
  const rv = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/voucher/20/review');
  ok(!!rv, 'voucher：POST /acct/voucher/{id}/review 已调用（参数化路径）');
  ok(rv && rv.opts.idempotencyKey === 'probe-uuid', 'voucher：审核带 Idempotency-Key');
  ok(rv && rv.b.action === 'REJECT' && rv.b.rejectReason === '金额与结算单不符', 'voucher：审核请求体契约对齐（action/rejectReason）');
  ok(textOf(cardOf(rvBtn)).includes('审核人'), 'voucher：审核留痕渲染（人/时间）');
  // 冲销：缺第二复核人预检 + 双人复核契约
  findInput(root, 'ac-vrs-id').value = '20';
  findInput(root, 'ac-vrs-reason').value = '重复入账';
  findInput(root, 'ac-vrs-co').value = '';
  const rsBtn = findBtnByText(root, '执行冲销');
  HG.api._calls.length = 0;
  await rsBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p.indexOf('/reverse') !== -1).length === 0, 'voucher：冲销缺第二复核人被预检拦截（DC-032）');
  HG.api._stub('POST /acct/voucher/20/reverse', (b) => ({
    id: 21, subAccountId: 8, voucherNo: 'AV202609-00002', periodKey: '2026-09', voucherType: 'REVERSAL', status: 'DRAFT',
    totalAmount: '5000.00', reversalOfId: 20, reversedById: null, reversalReason: b.reason, pendingExternalReview: 'Y', draftPendingEntryNote: '待企业财务复核入账'
  }));
  findInput(root, 'ac-vrs-co').value = '2';
  HG.api._calls.length = 0;
  await rsBtn.click(); await tick();
  const rs = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/voucher/20/reverse');
  ok(!!rs, 'voucher：POST /acct/voucher/{id}/reverse 已调用');
  ok(rs && rs.opts.idempotencyKey === 'probe-uuid', 'voucher：冲销带 Idempotency-Key');
  ok(rs && rs.b.reason === '重复入账' && rs.b.coReviewerId === 2, 'voucher：冲销请求体契约对齐（reason/coReviewerId 双人复核）');
  ok(textOf(cardOf(rsBtn)).includes('AV202609-00002') && textOf(cardOf(rsBtn)).includes('冲销原凭证'), 'voucher：反向凭证渲染（关联原凭证）');
  // 草稿删除显式通道
  findInput(root, 'ac-vdel-id').value = '21';
  const dlBtn = findBtnByText(root, '删除草稿');
  HG.api._calls.length = 0;
  HG.api._stub('POST /acct/voucher', (b) => ({ id: b.voucherId, voucherNo: 'AV202609-00002', status: 'DRAFT' }));
  await dlBtn.click(); await tick();
  const dl = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/voucher');
  ok(dl && dl.b.action === 'DELETE_DRAFT' && dl.b.voucherId === 21, 'voucher：草稿删除走 DELETE_DRAFT 显式通道');
  ok(dl && dl.opts.idempotencyKey === 'probe-uuid', 'voucher：草稿删除带 Idempotency-Key');
  // 列表：状态徽标 + 详情
  HG.api._stub('GET /acct/voucher', (q) => (q.id !== undefined ? {
    id: q.id, subAccountId: 8, voucherNo: 'AV202609-00001', periodKey: '2026-09', voucherType: 'CLOSE_PNL', status: 'POSTED',
    hedgePlanId: 12, hedgeRelationId: 15, settleCycleId: null, exposureId: 34,
    totalAmount: '5000.00', generatedBy: 1, generatedTime: '2026-10-16T13:00:00', pendingExternalReview: 'Y', draftPendingEntryNote: '待企业财务复核入账',
    lines: [{ id: 1, lineNo: 1, subjectCode: '6001', subjectName: '主营业务收入', direction: 'DEBIT', amount: '5000.00', qty: null, tradeFillId: null, bizDesc: '平仓盈亏结转' },
      { id: 2, lineNo: 2, subjectCode: '6102', subjectName: '其他业务成本', direction: 'CREDIT', amount: '5000.00', qty: null, tradeFillId: null, bizDesc: '平仓盈亏结转' }],
    reviews: [{ id: 3, nodeSeq: 1, action: 'SUBMIT', generatorId: 1, reviewerId: null, coReviewerId: null, reviewResult: 'SUBMITTED', reviewComment: null, rejectReason: null, actionTime: '2026-10-16T13:05:00' }]
  } : { items: [{ id: 20, subAccountId: 8, voucherNo: 'AV202609-00001', periodKey: '2026-09', voucherType: 'CLOSE_PNL', status: 'POSTED', hedgePlanId: 12, hedgeRelationId: 15, settleCycleId: null, exposureId: 34, totalAmount: '5000.00', pendingExternalReview: 'Y' }], total: 1, page: 1, size: 20 }));
  const lBtn = findBtnByText(root, '查询凭证');
  HG.api._calls.length = 0;
  await lBtn.click(); await tick();
  ok(textOf(cardOf(lBtn)).includes('AV202609-00001'), 'voucher：列表数据态渲染');
  ok(htmlAll(root).indexOf('data-ac-voucher-status="POSTED"') !== -1, 'voucher：状态徽标锚标（已审核正式）');
  const detBtn = findBtnByText(root, '查询详情');
  findInput(root, 'ac-vd-id').value = '20';
  await detBtn.click(); await tick();
  const dCard = cardOf(detBtn);
  ok(textOf(dCard).includes('6001') && textOf(dCard).includes('借') && textOf(dCard).includes('贷'), 'voucher：详情分录行渲染（借贷成对）');
  ok(textOf(dCard).includes('审核链'), 'voucher：详情审核链渲染（留痕）');
  ok(textOf(dCard).includes('待企业财务复核入账'), 'voucher：详情复核标注透出');
  // ---- TE-T083-001：关联单据四键展示 + 套保计划深链（FR-ACCT-8 验收 2） ----
  ok(htmlAll(root).indexOf('data-voucher-related="hedgePlanId"') !== -1, 'voucher：列表「关联单据」列——套保计划键展示（TE-T083-001）');
  ok(htmlAll(root).indexOf('data-voucher-related="hedgeRelationId"') !== -1 && htmlAll(root).indexOf('data-voucher-related="exposureId"') !== -1, 'voucher：列表「关联单据」列——套期关系/实货敞口键展示');
  ok(htmlAll(root).indexOf('data-voucher-jump="/plan/12"') !== -1, 'voucher：列表套保计划锚标带深链目标 /plan/{id}');
  ok(htmlAll(root).indexOf('data-voucher-related="settleCycleId"') === -1, 'voucher：空值关联键不渲染（settleCycleId=null 不出空壳）');
  ok(findByAttr(root, 'data-voucher-related', 'hedgePlanId') !== null, 'voucher：详情「关联单据」块——套保计划键节点');
  ok(textOf(dCard).includes('套期关系 #15') && textOf(dCard).includes('实货敞口 #34'), 'voucher：详情关联单据——无路由键展示为可选中文本（勿臆造路由）');
  ok(textOf(dCard).indexOf('结算周期') === -1, 'voucher：详情空值键不渲染（结算周期）');
  {
    const navTo = [];
    const realNav = HG.router.navigate;
    HG.router.navigate = (to) => navTo.push(to);
    const planJump = findBtnByText(root, '套保计划 #12');
    ok(!!planJump, 'voucher：详情套保计划跳转入口在');
    planJump.click();
    HG.router.navigate = realNav;
    ok(navTo.indexOf('/plan/12') !== -1, 'voucher：点击套保计划 ⇒ navigate(/plan/12)（FR-ACCT-8 验收 2 行为断言）');
  }
  // 全空值负例：四键皆 null ⇒ 列表/详情显「—」且无跳转入口
  HG.api._stub('GET /acct/voucher', (q) => (q.id !== undefined
    ? { id: q.id, voucherNo: 'AV202609-00001', status: 'POSTED', lines: [], reviews: [] }
    : { items: [{ id: 20, voucherNo: 'AV202609-00001', status: 'POSTED' }], total: 1, page: 1, size: 20 }));
  await lBtn.click(); await tick();
  await detBtn.click(); await tick();
  ok(htmlAll(root).indexOf('data-voucher-related=') === -1, 'voucher：四键皆空——列表无关联单据锚标');
  ok(!hasAttr(root, 'data-voucher-related'), 'voucher：四键皆空——详情无关联键节点');
  ok(findBtnByText(root, '套保计划 #') === null && textOf(cardOf(detBtn)).includes('—'), 'voucher：四键皆空——详情无跳转入口、显「—」');
  // 空态 + 错误态
  HG.api._stub('GET /acct/voucher', (q) => { if (q.id === undefined) return { items: [], total: 0, page: 1, size: 20 }; throw { code: 10001, message: '凭证不存在' }; });
  await lBtn.click(); await tick();
  ok(textOf(cardOf(lBtn)).includes('暂无数据'), 'voucher：列表空态显式');
  await detBtn.click(); await tick();
  ok(textOf(cardOf(detBtn)).includes('加载失败') || textOf(cardOf(detBtn)).includes('10001'), 'voucher：详情错误态显式');
  stubConfirm(false);
}

/* ================= Tab period：三前置 + 双人复核 + REOPEN 预检 + 10012 原文 ================= */
switchTab('period');
{
  ok(textOf(root).includes('全部凭证已审核或已冲销') && textOf(root).includes('结算已完成') && textOf(root).includes('有效性评估已完成'), 'period：关账三前置文案逐项（R-ACCT-8）');
  ok(textOf(root).includes('下期调整'), 'period：关账后新凭证须下期调整（FR-ACCT-12 验收 3）');
  stubConfirm(true);
  // 列表
  HG.api._stub('GET /acct/period', (q) => (q.periodKey ? [
    { id: 9, periodKey: q.periodKey, action: 'CLOSE', precheckResult: '通过', operatorId: 1, reviewerId: 2, reason: null, actionTime: '2026-10-01T00:00:00' }
  ] : { items: [{ id: 3, periodKey: '2026-09', periodType: 'MONTH', periodStart: '2026-09-01', periodEnd: '2026-09-30', status: 'OPEN', closedBy: null, closedTime: null, closeReviewerId: null, reopenedBy: null, reopenedTime: null, reopenReason: null }], total: 1, page: 1, size: 20 }));
  const pBtn = findBtnByText(root, '查询期间');
  await pBtn.click(); await tick();
  ok(textOf(cardOf(pBtn)).includes('2026-09'), 'period：列表数据态渲染');
  ok(htmlAll(root).indexOf('data-ac-period-status="OPEN"') !== -1, 'period：状态徽标锚标（开放）');
  const lgBtn = findBtnByText(root, '查询关账历史');
  findInput(root, 'ac-p-log-period').value = '2026-09';
  HG.api._calls.length = 0;
  await lgBtn.click(); await tick();
  const lg = HG.api._calls.find((c) => c.m === 'GET' && c.p === '/acct/period');
  ok(lg && lg.q.periodKey === '2026-09', 'period：关账历史 periodKey 透传');
  ok(textOf(cardOf(lgBtn)).includes('通过') && textOf(cardOf(lgBtn)).includes('复核人'), 'period：关账留痕渲染（前置结果/操作人/复核人）');
  // 创建期间：预检 + 幂等
  HG.api._stub('POST /acct/period', (b) => ({ id: 4, periodKey: b.periodKey, periodType: b.periodType, periodStart: b.periodStart, periodEnd: b.periodEnd, status: 'OPEN' }));
  findInput(root, 'ac-p-new-period').value = '2026-10';
  findInput(root, 'ac-p-new-type').value = 'MONTH';
  findInput(root, 'ac-p-new-start').value = '2026-10-01';
  findInput(root, 'ac-p-new-end').value = '2026-10-31';
  const nBtn = findBtnByText(root, '创建期间');
  HG.api._calls.length = 0;
  await nBtn.click(); await tick();
  const np = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/period');
  ok(np && np.opts.idempotencyKey === 'probe-uuid', 'period：创建带 Idempotency-Key');
  ok(np && np.b.periodKey === '2026-10' && np.b.periodType === 'MONTH', 'period：创建请求体契约对齐');
  // 关账：REOPEN 未填原因预检 + 复核人必填
  findInput(root, 'ac-p-close-id').value = '3';
  findInput(root, 'ac-p-close-action').value = 'REOPEN';
  findInput(root, 'ac-p-close-reason').value = '';
  const cBtn = findBtnByText(root, '提交关账动作');
  HG.api._calls.length = 0;
  await cBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p.indexOf('/close') !== -1).length === 0, 'period：REOPEN 未填原因被预检拦截（FR-ACCT-12 验收 4）');
  findInput(root, 'ac-p-close-reviewer').value = '';
  findInput(root, 'ac-p-close-action').value = 'CLOSE';
  await cBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p.indexOf('/close') !== -1).length === 0, 'period：缺复核人被预检拦截（关账双人复核，R-ACCT-8）');
  // 10012：前置不满足原文展示
  HG.api._stub('POST /acct/period/3/close', () => { throw { code: 10012, message: '关账前置不满足：存在未审核凭证 AV202609-00001（并列）；复现=审核/冲销后重试' } });
  findInput(root, 'ac-p-close-reviewer').value = '2';
  HG.api._calls.length = 0;
  await cBtn.click(); await tick();
  const cCard = cardOf(cBtn);
  ok(textOf(cCard).includes('未审核凭证') && textOf(cCard).includes('AV202609-00001'), 'period：10012 原文展示（并列列出未审核凭证，FR-ACCT-12 验收 2）');
  ok(findByAttr(root, 'data-gate-10012', '1') !== null, 'period：10012 门禁锚标');
  const cp = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/period/3/close');
  ok(cp && cp.opts.idempotencyKey === 'probe-uuid', 'period：关账带 Idempotency-Key');
  // 成功态 + 契约
  HG.api._stub('POST /acct/period/3/close', (b) => ({ id: 9, periodKey: '2026-09', action: b.action, precheckResult: '通过', operatorId: 1, reviewerId: b.reviewerId, reason: b.reason, actionTime: '2026-10-16T14:00:00' }));
  HG.api._calls.length = 0;
  await cBtn.click(); await tick();
  const cp2 = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/acct/period/3/close');
  ok(cp2 && cp2.b.action === 'CLOSE' && cp2.b.reviewerId === 2 && cp2.b.reason === null, 'period：关账请求体契约对齐（action/reason/reviewerId）');
  ok(textOf(cardOf(cBtn)).includes('关账') && textOf(cardOf(cBtn)).includes('通过'), 'period：成功态渲染（动作 + 前置结果）');
  // 空态 + 错误态
  HG.api._stub('GET /acct/period', (q) => (q.periodKey ? [] : { items: [], total: 0, page: 1, size: 20 }));
  await pBtn.click(); await tick();
  ok(textOf(cardOf(pBtn)).includes('暂无'), 'period：列表空态显式');
  HG.api._stub('GET /acct/period', () => { throw { code: -1, message: '网络故障' }; });
  await lgBtn.click(); await tick();
  ok(textOf(cardOf(lgBtn)).includes('加载失败'), 'period：关账历史错误态显式');
  stubConfirm(false);
}

/* ================= Tab selfcheck/export：真端点接入（T-197）+ 载入面断言 ================= */
{
  switchTab('selfcheck');
  ok(findByAttr(root, 'data-not-registered', '/acct/selfcheck') === null, 'selfcheck：NOT_REGISTERED 锚标已消失（T-197 迁真端点）');
  ok(textOf(root).includes('自检批次清单'), 'selfcheck：批次清单卡片渲染（T-197）');
  ok(textOf(root).includes('发起自检'), 'selfcheck：发起自检卡片渲染');
  ok(textOf(root).includes('自检不替代外部复核'), 'selfcheck：R-ACCT-10 不替代说明披露');
  ok(textOf(root).includes('FR-ACCT-14'), 'selfcheck：FR 归属披露（FR-ACCT-14）');
  // 载入面：初始 loadList(1) 自动发起 GET /acct/selfcheck（PageData 信封）
  const scListCall = HG.api._calls.filter((c) => c.m === 'GET' && c.p === '/acct/selfcheck');
  ok(scListCall.length === 1, 'selfcheck：清单自动载入（GET /acct/selfcheck ×1）');
  // 批次详情：stub 后走查询 → 表格渲染 + 标注横幅
  HG.api._stub('GET /acct/selfcheck', (q) => {
    if (q.selfcheckNo) {
      return { id: 1, selfcheckNo: q.selfcheckNo, scopeType: 'HEDGE_RELATION', scopeKey: 'HR202609010001|2026-10',
        passCount: 4, failCount: 1, conclusion: 'HAS_FAIL', cannotSelfcheckReason: null,
        pendingExternalReview: 'Y', notSubstituteNote: '自检不替代外部复核（R-ACCT-10）',
        executorId: 100, selfcheckTime: '2026-10-13T09:00:00',
        items: [{ itemSeq: 1, itemCode: 'FORMAL_DESIGNATION', itemName: '①正式指定', result: 'PASS',
          evidenceType: 't_hedge_relation', evidenceId: 1, evidenceDesc: '指定留痕在案', failReason: null }] };
    }
    return { total: 1, page: 1, size: 20, items: [{ selfcheckNo: 'SC202610130001', scopeType: 'HEDGE_RELATION',
      scopeKey: 'HR202609010001|2026-10', subAccountId: 1, passCount: 4, failCount: 1, conclusion: 'HAS_FAIL',
      pendingExternalReview: 'Y', selfcheckTime: '2026-10-13T09:00:00', executorId: 100 }] };
  });
  findInput(root, 'ac-sc-no').value = 'SC202610130001';
  findBtnByText(root, '查询详情').click();
  await new Promise((r) => setTimeout(r, 0)); await new Promise((r) => setTimeout(r, 0));
  ok(textOf(root).includes('SC202610130001'), 'selfcheck：详情渲染批次号');
  ok(textOf(root).includes('FORMAL_DESIGNATION'), 'selfcheck：逐条条目渲染（itemCode）');
  ok(textOf(root).includes('t_hedge_relation'), 'selfcheck：证据位置渲染（evidenceType）');
  ok(textOf(root).includes('自检不替代外部复核'), 'selfcheck：详情横幅含不替代说明（验收 3）');
  switchTab('export');
  ok(findByAttr(root, 'data-not-registered', '/acct/export') === null, 'export：NOT_REGISTERED 锚标已消失（T-197 迁真端点）');
  ok(textOf(root).includes('自检报告导出'), 'export：导出卡片渲染（T-197 自检报告载点）');
  ok(textOf(root).includes('自检不替代外部复核') || textOf(root).includes('待外部复核'), 'export：R-ACCT-10 标注披露');
}

/* ================= 写操作幂等汇总负证（_allPosts：全程 POST 台账，不受分段清空影响） ================= */
{
  const writes = HG.api._allPosts || [];
  ok(writes.length >= 12, '写操作覆盖充分（全程 POST >=12 笔，实测 ' + writes.length + '）');
  ok(writes.every((c) => c.opts && c.opts.idempotencyKey === 'probe-uuid'), '负证——全部写操作携带 Idempotency-Key（无一遗漏）');
}

/* ================= 变异负例（负例有牙）：子进程重跑变异副本 ⇒ 必红 ================= */
{
  const fs = await import('node:fs');
  const path = await import('node:path');
  const cp = await import('node:child_process');
  const os = await import('node:os');
  const { fileURLToPath } = await import('node:url');
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
  const webJs = path.join(repoRoot, 'web', 'js');
  const srcAcct = fs.readFileSync(path.join(webJs, 'pages', 'acct.js'), 'utf8');
  const MUTATIONS = [
    { name: 'spot', find: "'data-spot-column'", repl: "'data-spot-column-x'", desc: '摘除实货栏锚标（双栏之一）' },
    { name: 'unattr', find: "'data-unattributed'", repl: "'data-unattributed-x'", desc: '摘除 UNATTRIBUTED 残余行锚标' },
    { name: 'reldocs', find: "'data-voucher-related'", repl: "'data-voucher-related-x'", desc: '摘除凭证关联单据四键展示（TE-T083-001）' }
  ];
  for (const m of MUTATIONS) {
    ok(srcAcct.indexOf(m.find) !== -1, '变异前置——源含锚标（' + m.name + '）');
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 't083-mut-'));
    fs.cpSync(webJs, path.join(dir, 'web', 'js'), { recursive: true });
    const copyPath = path.join(dir, 'web', 'js', 'pages', 'acct.js');
    const mutated = srcAcct.split(m.find).join(m.repl);
    ok(mutated !== srcAcct, '变异前置——副本确实被改写（' + m.name + '）');
    fs.writeFileSync(copyPath, mutated);
    const r = cp.spawnSync(process.execPath, [fileURLToPath(import.meta.url)], {
      env: Object.assign({}, process.env, { T083_MUTATE: m.name, T083_COPY_DIR: dir }),
      encoding: 'utf8'
    });
    ok(r.status === 7 && String(r.stdout).indexOf('MUTATION-KILLED') !== -1,
      '变异有牙——' + m.desc + ' ⇒ 子进程断言红（rc=' + r.status + '）');
    fs.rmSync(dir, { recursive: true, force: true });
  }
  // 存活对照（未变异副本 ⇒ 子进程断言绿）——证明子进程判据不是无条件红（假红护栏）
  {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 't083-pristine-'));
    fs.cpSync(webJs, path.join(dir, 'web', 'js'), { recursive: true });
    const r = cp.spawnSync(process.execPath, [fileURLToPath(import.meta.url)], {
      env: Object.assign({}, process.env, { T083_MUTATE: 'spot', T083_COPY_DIR: dir }),
      encoding: 'utf8'
    });
    ok(r.status === 0 && String(r.stdout).indexOf('MUTATION-SURVIVED') !== -1,
      '存活对照——未变异副本子进程绿（rc=' + r.status + '，判据非无条件红）');
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

/* ---------------- 结果 ---------------- */
ok(pass + fails.length > 80, '探针断言总量充分（>80）');
console.log('T-083 acct probe: PASS=' + pass + ' FAIL=' + fails.length);
if (fails.length) { process.exit(1); }
process.exit(0);
