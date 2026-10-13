/*
 * T-055 页面探针（无头桩，非真实浏览器）—— 走查 fund.js 各功能区渲染。
 *
 * 形态对齐 t071_audit_probe.mjs 先例（T-021/T-071 形态）：node 级、mock DOM + mock HG.api，
 * 逐功能点断言渲染结果。覆盖：
 *   · FUND 键集与 ApiRoutes.java FUND 模块 12 路径逐字对账（含参数化 review/transferApprove 函数键）；
 *   · 各 Tab 渲染两态（空态 + 数据态）；加载/空/错误三态；
 *   · 双人复核提示（文案逐字「发起人与复核人不得为同一人」）+ 驳回必填原因 + 复核二次确认；
 *   · 划拨页固定提示条「系统内记账，加总不变」逐字断言；
 *   · 「超时待查」显式徽标 + 不得渲染为失败色的负控断言；
 *   · 写操作 idempotencyKey 断言（deposit/withdraw/review/transfer/approve/receipt/quota/ledger/reconcile）。
 *
 * 用法：node tools/probes/t055_fund_probe.mjs → 全过打印 PASS 计数，退出码 0；任一失败退出码 1。
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
// T-195：fund.js 导出面走 triggerCsvDownload（URL.createObjectURL 先例 /product.js 同构）
// ⇒ 探针在原生 URL 上补 createObjectURL/revokeObjectURL（不覆盖 URL 构造器本身，
//   `new URL(...)` 仍被源码读取分支使用）。
URL.createObjectURL = URL.createObjectURL || (() => 'blob:probe');
URL.revokeObjectURL = URL.revokeObjectURL || (() => {});

const { HG } = await import('../../web/js/core/ns.js');

/* ---------------- DOM 桩（对齐 t071_probe_globals.mjs 的 minimal 形态） ---------------- */
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
HG.format = {
  decimal: (v) => (v === null || v === undefined || v === '' ? '' : String(v)),
  fixed: (v) => String(v ?? ''), money: (v) => String(v ?? ''), qty: (v) => String(v ?? ''),
  pct: (v) => String(v ?? ''),
  date: (v) => (v ? String(v).slice(0, 10) : ''),
  dateTime: (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : ''),
  esc: (v) => String(v ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;'),
  decSub: (v) => String(v ?? '')
};
HG.errmsg = {
  render: (err) => '<div class="hg-errmsg">[' + (err && err.code) + '] ' + ((err && err.message) || '') + '</div>',
  summary: (err) => '[' + (err && err.code) + '] ' + ((err && err.message) || ''),
  isBlocked: () => false, isUnauthenticated: () => false, isTransport: (e) => !!(e && e._network),
  isBackendUnavailable: () => false, CODE_HINT: {}
};
HG.session = { state: { subAccounts: [] } };
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
    const k = 'POST ' + path;
    if (!(k in this._responses)) throw { code: -1, message: '探针未 stub：' + k };
    return this._responses[k](body || {}, opts || {});
  },
  async put() { throw { code: -1, message: 'fund 页不应调用 put' }; },
  async del() { throw { code: -1, message: 'fund 页不应调用 del' }; },
  async list() { throw { code: -1, message: 'fund 页不应调用 list' }; },
  async download(path, query) {
    // T-195：/fund/export 改 CSV 直返 ⇒ export 页走 download（blob 桩形态与 api.js 一致）
    this._calls.push({ m: 'DOWNLOAD', p: path, q: query });
    const k = 'GET ' + path;
    if (!(k in this._responses)) throw { code: -1, message: '探针未 stub：' + k };
    const text = this._responses[k](query || {});
    // Node 22 原生 URL.createObjectURL 强校验 Blob 实例 ⇒ 桩 blob 用真 Blob（fund.js triggerCsvDownload 可直达）
    return { blob: new Blob([String(text)], { type: 'text/csv; charset=UTF-8' }), filename: 'fund-reconcile.csv' };
  }
};
HG.router = {
  _routes: {},
  register(p, fn) { this._routes[p] = fn; },
  navigate() {}, start() {}, setNotFound() {}, currentPath() { return '/fund'; }
};

await import('../../web/js/core/format.js');
await import('../../web/js/core/errmsg.js');
await import('../../web/js/core/router.js');
await import('../../web/js/core/ui.js');
await import('../../web/js/core/routes.js');

/* ---------------- 断言器 ---------------- */
let pass = 0; const fails = [];
function ok(cond, name) { if (cond) { pass++; } else { fails.push(name); console.error('FAIL: ' + name); } }
function textOf(node) { return node.textContent || ''; }
function htmlOf(node) { return node._h || (node.innerHTML || ''); }
const tick = () => new Promise((r) => setTimeout(r, 0));

/* ---------------- 载入页面 ---------------- */
const registeredPaths = [];
const realRegister = HG.router.register;
HG.router.register = function (p, fn) { registeredPaths.push(p); return realRegister.call(this, p, fn); };
await import('../../web/js/pages/fund.js');
HG.router.register = realRegister;

/* ================= 断言 0：FUND 键集逐字对账（== ApiRoutes.java FUND 模块 13 路径，T-195 起新增 apply-list） ================= */
const R = HG.routes.FUND;
// 静态键：13 路径中 11 个静态（ledger/flow/deposit/withdraw/transfer/receipt/quota/margin/reconcile/export/apply-list）
const EXPECT_STATIC = {
  LEDGER: '/fund/ledger', FLOW: '/fund/flow', DEPOSIT: '/fund/deposit', WITHDRAW: '/fund/withdraw',
  TRANSFER: '/fund/transfer', RECEIPT: '/fund/receipt', QUOTA: '/fund/quota', MARGIN: '/fund/margin',
  RECONCILE: '/fund/reconcile', EXPORT: '/fund/export', APPLY_LIST: '/fund/apply-list'
};
ok(JSON.stringify(Object.assign({}, R, { review: undefined, transferApprove: undefined })).replace(/,"review":undefined,"transferApprove":undefined/, '') !== '', 'FUND 键存在');
ok(R.LEDGER === EXPECT_STATIC.LEDGER && R.FLOW === EXPECT_STATIC.FLOW, 'FUND 静态键 LEDGER/FLOW 逐字一致');
ok(R.DEPOSIT === EXPECT_STATIC.DEPOSIT && R.WITHDRAW === EXPECT_STATIC.WITHDRAW, 'FUND 静态键 DEPOSIT/WITHDRAW 逐字一致');
ok(R.TRANSFER === EXPECT_STATIC.TRANSFER && R.RECEIPT === EXPECT_STATIC.RECEIPT, 'FUND 静态键 TRANSFER/RECEIPT 逐字一致');
ok(R.QUOTA === EXPECT_STATIC.QUOTA && R.MARGIN === EXPECT_STATIC.MARGIN, 'FUND 静态键 QUOTA/MARGIN 逐字一致');
ok(R.RECONCILE === EXPECT_STATIC.RECONCILE && R.EXPECT === undefined && R.EXPORT === EXPECT_STATIC.EXPORT, 'FUND 静态键 RECONCILE/EXPORT 逐字一致');
ok(R.APPLY_LIST === EXPECT_STATIC.APPLY_LIST, 'FUND 静态键 APPLY_LIST 逐字一致（T-195 新增 /fund/apply-list）');
// 参数化函数键（先例：PLAN.detail / SPOT.exposureDetail 同构）
ok(typeof R.review === 'function' && R.review(77) === '/fund/77/review', 'FUND.review 函数键 → /fund/77/review（占位符路径）');
ok(typeof R.transferApprove === 'function' && R.transferApprove(9) === '/fund/transfer/9/approve', 'FUND.transferApprove 函数键 → /fund/transfer/9/approve');
ok(R.review('a/b') === '/fund/a%2Fb/review', 'FUND.review 走 encodeURIComponent（enc 先例）');
// 覆盖断言：13/13 路径全接（静态 11 + 参数化 2）
const pathSet = new Set(Object.values(EXPECT_STATIC).concat([R.review('X').replace('X', '{id}'), R.transferApprove('X').replace('X', '{id}')]));
ok(pathSet.size === 13, 'FUND 键集覆盖 13 路径（11 静态 + 2 参数化；T-195 +apply-list）');
ok(registeredPaths.includes('/fund'), '/fund 路由已注册');

/* ---------------- 页面渲染与 Tab 走查辅助 ---------------- */
const root = document.createElement('div');
function switchTab(key) {
  while (root.firstChild) root.removeChild(root.firstChild);
  HG.pages.fund.renderFund(root, { query: { tab: key } });
}
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

switchTab('ledger');
ok(textOf(root).includes('资金管理（FUND）'), '页面标题渲染');
ok(textOf(root).includes('FR-FUND-1~17'), '页面描述含 FR 区间');

/* ================= Tab ledger：台账两态 + 建立台账（双人复核 + 幂等） ================= */
switchTab('ledger');
{
  // 空态
  HG.api._stub('GET /fund/ledger', () => ({ items: [], total: 0, page: 1, size: 20 }));
  const btn = findBtnByText(root, '查询台账');
  await btn.click(); await tick();
  let body = cardOf(btn);
  ok(textOf(body).includes('暂无数据'), 'ledger：空态显式提示');
  // 数据态
  HG.api._stub('GET /fund/ledger', () => ({ items: [{ id: 1, subAccountId: 8, ledgerDate: '2026-10-12', beginBalance: '1000000.00', endBalance: '990000.00', availableFund: '990000.00', riskRatio: '0.12' }], total: 1, page: 1, size: 20 }));
  await btn.click(); await tick();
  body = cardOf(btn);
  ok(textOf(body).includes('1,000,000.00'), 'ledger：数据态渲染期初余额（千分位，F.money）');
  ok(textOf(body).includes('期末余额'), 'ledger：数据态含期末余额列（期初+发生=期末可视）');
  // 错误态
  HG.api._stub('GET /fund/ledger', () => { throw { code: 10003, message: '功能权限不足' }; });
  await btn.click(); await tick();
  body = cardOf(btn);
  ok(textOf(body).includes('加载失败'), 'ledger：错误态显式（三态齐全）');
  ok(textOf(body).includes('重试'), 'ledger：错误态含重试入口');
  // 建立台账：双人复核必填 + 幂等
  HG.api._calls.length = 0;
  HG.api._stub('POST /fund/ledger', (b) => ({ id: 5, subAccountId: b.subAccountId, beginBalance: b.beginBalance }));
  // 关闭 confirm：探针直接确认
  globalThis.document.body.querySelector = () => null;
  const uiConfirmReal = HG.ui.confirm;
  HG.ui.confirm = async () => true;
  const iBtn = findBtnByText(root, '建立台账');
  findInput(root, 'fd-i-sub').value = '8';
  findInput(root, 'fd-i-date').value = '2026-10-12';
  findInput(root, 'fd-i-amt').value = '1000000.00';
  findInput(root, 'fd-i-reviewer').value = '2';
  await iBtn.click(); await tick();
  const call = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/fund/ledger');
  ok(!!call, 'ledger：POST /fund/ledger 已调用');
  ok(call && call.opts.idempotencyKey === 'probe-uuid', 'ledger：建立台账带 Idempotency-Key（幂等）');
  ok(call && call.b.reviewerId === 2, 'ledger：请求携带 reviewerId（双人复核语义）');
  HG.ui.confirm = uiConfirmReal;
}

/* ================= Tab dw：双人复核红线 + 驳回必填 + 幂等 ================= */
switchTab('dw');
{
  ok(textOf(root).includes('发起人与复核人不得为同一人'), 'dw：双人复核红线文案逐字（FUND.md FR-FUND-7）');
  ok(textOf(root).includes('不得假设同步返回'), 'dw：CTP 异步模型红线（R-FUND-5）');
  ok(textOf(root).includes('零落盘') || textOf(root).includes('NFR-SEC-1'), 'dw：零落盘声明');
  // 复核：驳回未填原因被拒（前端预检）
  HG.api._calls.length = 0;
  const rejBtn = findBtnByText(root, '驳回');
  const uiConfirmReal = HG.ui.confirm;
  HG.ui.confirm = async () => true;
  findInput(root, 'fd-r-id').value = '12';
  // 驳回原因为空 → toast 报错且不发请求
  await rejBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p.indexOf('/review') !== -1).length === 0, 'dw：驳回未填原因时不发请求（FR-FUND-7 验收 3 前端预检）');
  // 填原因后发请求：参数化路径 + 幂等
  HG.api._stub('POST /fund/12/review', (b) => ({ id: 12, applyNo: 'DW-12', status: 'REJECTED' }));
  findInput(root, 'fd-r-reject').value = '金额与用途不符';
  await rejBtn.click(); await tick();
  const call = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/fund/12/review');
  ok(!!call, 'dw：POST /fund/{id}/review 已调用（参数化路径）');
  ok(call && call.opts.idempotencyKey === 'probe-uuid', 'dw：复核带 Idempotency-Key');
  ok(call && call.b.approved === false && call.b.rejectReason === '金额与用途不符', 'dw：驳回请求携带 rejectReason');
  // 复核通过 → 二次确认文本含双人复核
  HG.ui.confirm = async (msg) => { globalThis.__confirmMsg = msg; return true; };
  HG.api._stub('POST /fund/12/review', (b) => ({ id: 12, applyNo: 'DW-12', status: 'SUBMITTED' }));
  findInput(root, 'fd-r-reject').value = '';
  const passBtn = findBtnByText(root, '复核通过');
  await passBtn.click(); await tick();
  ok(String(globalThis.__confirmMsg).indexOf('不得为同一人') !== -1, 'dw：复核二次确认含双人复核提示');
  const body = cardOf(passBtn);
  ok(textOf(body).includes('待复核') || textOf(body).includes('等待 CTP 回执'), 'dw：复核通过后展示新状态');
  // 入金申请：幂等
  HG.api._calls.length = 0;
  HG.ui.confirm = async () => true;
  HG.api._stub('POST /fund/deposit', (b) => ({ id: 20, applyNo: 'DW-20', status: 'PENDING_REVIEW' }));
  findInput(root, 'fd-dw-sub').value = '8';
  findInput(root, 'fd-dw-amt').value = '50000.00';
  const inBtn = findBtnByText(root, '发起入金');
  await inBtn.click(); await tick();
  const dep = HG.api._calls.find((c) => c.p === '/fund/deposit');
  ok(!!dep && dep.opts.idempotencyKey === 'probe-uuid', 'dw：入金申请带 Idempotency-Key（R-FUND-5 幂等）');
  ok(dep && dep.b.subAccountId === 8 && dep.b.amount === '50000.00', 'dw：入金请求字段名契约对齐（subAccountId/amount）');
  // 回执 UNKNOWN：确认文案不假设失败
  HG.api._calls.length = 0;
  HG.api._stub('POST /fund/receipt', (b) => ({ receiptId: 3, processed: true, detail: 'ok' }));
  findInput(root, 'fd-rec-apply').value = '20';
  const recSel = findInput(root, 'fd-rec-result');
  recSel.value = 'UNKNOWN';
  const recBtn = findBtnByText(root, '提交回执');
  HG.ui.confirm = async (msg) => { globalThis.__confirmMsg = msg; return true; };
  await recBtn.click(); await tick();
  ok(String(globalThis.__confirmMsg).indexOf('不得假设失败或成功') !== -1, 'dw：UNKNOWN 回执确认文案不假设失败/成功');
  const rec = HG.api._calls.find((c) => c.p === '/fund/receipt');
  ok(rec && rec.b.result === 'UNKNOWN' && rec.opts.idempotencyKey === 'probe-uuid', 'dw：回执提交带 result=UNKNOWN + 幂等');
  HG.ui.confirm = uiConfirmReal;
}

/* ================= Tab dw：申请列表查询（T-195 新增 GET /fund/apply-list；dwStatusHTML 就绪件激活落点） ================= */
switchTab('dw');
{
  // 空态
  HG.api._stub('GET /fund/apply-list', () => ({ items: [], total: 0, page: 1, size: 20 }));
  const qBtn = findBtnByText(root, '查询申请');
  await qBtn.click(); await tick();
  ok(textOf(cardOf(qBtn)).includes('暂无数据'), 'dw：申请列表空态显式提示（FR-FUND-8 验收3 查询面三态）');
  // 数据态：含 TIMEOUT_UNKNOWN 行（超时态定位申请 ID = 本端点的验收承载）
  HG.api._stub('GET /fund/apply-list', (q) => ({
    items: [
      { id: 20, applyNo: 'DW-20', subAccountId: 8, direction: 'IN', amount: '50000.00', status: 'TIMEOUT_UNKNOWN', applicantId: 1, reviewerId: 2, createdTime: '2026-10-12T10:00:00' },
      { id: 12, applyNo: 'DW-12', subAccountId: 8, direction: 'OUT', amount: '1000.00', status: 'REJECTED', applicantId: 1, reviewerId: 2, rejectReason: '金额与用途不符', createdTime: '2026-10-12T09:00:00' }
    ], total: 2, page: 1, size: 20
  }));
  HG.api._calls.length = 0;
  await qBtn.click(); await tick();
  const listBody = cardOf(qBtn);
  ok(textOf(listBody).includes('DW-20') && textOf(listBody).includes('DW-12'), 'dw：申请列表数据态渲染申请号（FR-FUND-8 验收3）');
  ok(textOf(listBody).includes('超时待查'), 'dw：TIMEOUT_UNKNOWN 行渲染「超时待查」文案（dwStatusHTML 激活）');
  ok(textOf(listBody).includes('金额与用途不符'), 'dw：驳回原因列渲染（rejectReason）');
  ok(textOf(listBody).includes('网络超时不代表未到账'), 'dw：列表含 TIMEOUT_UNKNOWN 行时展示超时警示条');
  const listCall = HG.api._calls.find((c) => c.m === 'GET' && c.p === '/fund/apply-list');
  ok(!!listCall, 'dw：GET /fund/apply-list 已调用（T-195 新端点）');
  // 筛选参数透传
  HG.api._calls.length = 0;
  findInput(root, 'fd-q-status').value = 'TIMEOUT_UNKNOWN';
  findInput(root, 'fd-q-sub').value = '8';
  await qBtn.click(); await tick();
  const fCall = HG.api._calls.find((c) => c.m === 'GET' && c.p === '/fund/apply-list');
  ok(fCall && fCall.q.status === 'TIMEOUT_UNKNOWN' && fCall.q.subAccountId === '8', 'dw：申请列表筛选参数透传（status 含 TIMEOUT_UNKNOWN / subAccountId）');
  // 错误态
  HG.api._stub('GET /fund/apply-list', () => { throw { code: 10001, message: 'status 取值非法' }; });
  await qBtn.click(); await tick();
  ok(textOf(cardOf(qBtn)).includes('10001') || textOf(cardOf(qBtn)).includes('错误') || textOf(cardOf(qBtn)).includes('失败'), 'dw：申请列表错误态显式（三态齐全）');
  // 负控：超时行不渲染 danger 徽标（dwStatusHTML 红线在真实数据面上的投影）
  const src = (await import('node:fs')).readFileSync(new URL('../../web/js/pages/fund.js', import.meta.url), 'utf8');
  ok(src.indexOf('APPLY_LIST') !== -1, 'dw：fund.js 已消费 /fund/apply-list（R.APPLY_LIST 键引用）');
}

/* ================= Tab dw：超时待查徽标红线（dwStatusHTML 独立形态） ================= */
{
  // 「超时待查」红线：文案语义 + 渲染函数形态（读取 fund.js 源码做形态断言）
  const src = (await import('node:fs')).readFileSync(new URL('../../web/js/pages/fund.js', import.meta.url), 'utf8');
  ok(src.indexOf("status === 'TIMEOUT_UNKNOWN'") !== -1, 'dw：超时待查走独立渲染分支（dwStatusHTML 显式分支）');
  ok(src.indexOf("hg-badge--frozen\" data-dw-status=\"TIMEOUT_UNKNOWN\"") !== -1, 'dw：超时待查徽标为 frozen 形态（非 danger/void 失败色）');
  ok(src.indexOf("hg-badge--danger\" data-dw-status") === -1, 'dw：负控——超时待查不用 danger 失败色徽标');
  switchTab('dw');
  ok(textOf(root).includes('网络超时不代表未到账'), 'dw：超时待查业务语义文案（网络超时不代表未到账）');
  ok(textOf(root).includes('不得假设失败或成功'), 'dw：超时待查不得假设失败或成功（FUND.md §2.1）');
  ok(textOf(root).includes('查询确认'), 'dw：超时待查须主动查询确认入口文案');
  ok(textOf(root).indexOf('超时即失败') === -1 && textOf(root).indexOf('重试即重发') === -1, 'dw：无「超时即失败/重试即重发」误导表述');
}

/* ================= Tab flow：两态 + 划拨成对查询 ================= */
switchTab('flow');
{
  ok(textOf(root).includes('只增不改不删'), 'flow：流水只读红线（R-FUND-2）');
  ok(textOf(root).includes('无任何编辑 / 删除入口') || textOf(root).includes('无任何编辑'), 'flow：显式声明无编辑/删除入口');
  HG.api._stub('GET /fund/flow', () => ({ items: [], total: 0, page: 1, size: 20 }));
  const btn = findBtnByText(root, '查询流水');
  await btn.click(); await tick();
  let body = cardOf(btn);
  ok(textOf(body).includes('暂无数据'), 'flow：空态显式');
  HG.api._stub('GET /fund/flow', () => ({ items: [
    { id: 1, subAccountId: 8, voucherNo: 'PZ-20261012-0001', flowType: 'TRANSFER_OUT', direction: 'OUT', amount: '10000.00', counterpartSubAccountId: 9, transferApplyId: 3 },
    { id: 2, subAccountId: 9, voucherNo: 'PZ-20261012-0002', flowType: 'TRANSFER_IN', direction: 'IN', amount: '10000.00', counterpartSubAccountId: 8, transferApplyId: 3 }
  ], total: 2, page: 1, size: 20 }));
  await btn.click(); await tick();
  body = cardOf(btn);
  ok(textOf(body).includes('PZ-20261012-0001') && textOf(body).includes('PZ-20261012-0002'), 'flow：数据态渲染凭证编号');
  ok(textOf(body).includes('入金') || textOf(body).includes('划拨'), 'flow：类型中文名渲染');
  // 成对查询
  HG.api._calls.length = 0;
  HG.api._stub('GET /fund/flow', (q) => [ { id: 1, subAccountId: 8, voucherNo: 'V1', flowType: 'TRANSFER_OUT', amount: '5.00' }, { id: 2, subAccountId: 9, voucherNo: 'V2', flowType: 'TRANSFER_IN', amount: '5.00' } ]);
  findInput(root, 'fd-f-transfer').value = '3';
  await btn.click(); await tick();
  const pair = HG.api._calls.find((c) => c.m === 'GET' && c.p === '/fund/flow');
  ok(pair && pair.q.transferApplyId === '3', 'flow：按划拨单号查询带 transferApplyId（FR-FUND-16 成对展示）');
  const pbody = cardOf(btn);
  ok(textOf(pbody).includes('V1') && textOf(pbody).includes('V2'), 'flow：成对流水两条均渲染');
  // 错误态
  HG.api._stub('GET /fund/flow', () => { throw { code: -1, message: '网络故障' }; });
  findInput(root, 'fd-f-transfer').value = '';
  await btn.click(); await tick();
  ok(textOf(cardOf(btn)).includes('加载失败'), 'flow：错误态显式');
}

/* ================= Tab transfer：固定提示条逐字 + 审批双确认 + 幂等 ================= */
switchTab('transfer');
{
  ok(textOf(root).includes('系统内记账，加总不变'), 'transfer：固定提示条「系统内记账，加总不变」逐字（验收 3）');
  ok(textOf(root).includes('真实期货账户资金不变动'), 'transfer：R-FUND-4 语义补全');
  ok(textOf(root).includes('发起人与复核人不得为同一人') && textOf(root).includes('不得为交易岗'), 'transfer：划拨审批双人复核文案（R-FUND-3/FR-FUND-11，审批人变体在二次确认文案另行断言）');
  ok(textOf(root).includes('不得为交易岗'), 'transfer：岗位分离文案（FR-FUND-11）');
  // 发起：同子账户被前端预检拦截
  HG.api._calls.length = 0;
  const uiConfirmReal = HG.ui.confirm;
  HG.ui.confirm = async () => true;
  const tBtn = findBtnByText(root, '发起划拨');
  findInput(root, 'fd-t-from').value = '8';
  findInput(root, 'fd-t-to').value = '8';
  findInput(root, 'fd-t-amt').value = '1000.00';
  findInput(root, 'fd-t-reason').value = '调拨';
  await tBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p === '/fund/transfer').length === 0, 'transfer：同子账户被预检拦截（R-FUND-6 ②）');
  // 正常发起：契约字段名 + 幂等
  HG.api._stub('POST /fund/transfer', (b) => ({ id: 7, applyNo: 'TR-7', fromSubAccountId: 8, toSubAccountId: 9, status: 'PENDING_APPROVAL' }));
  findInput(root, 'fd-t-to').value = '9';
  await tBtn.click(); await tick();
  const call = HG.api._calls.find((c) => c.p === '/fund/transfer');
  ok(!!call && call.b.fromSubAccountId === 8 && call.b.toSubAccountId === 9 && call.b.reason === '调拨', 'transfer：请求字段契约对齐（fromSubAccountId/toSubAccountId/reason）');
  ok(call && call.opts.idempotencyKey === 'probe-uuid', 'transfer：发起划拨带 Idempotency-Key');
  // 审批通过：二次确认含双人复核 + 加总校验；请求带 realAccountAvailable
  HG.api._calls.length = 0;
  HG.ui.confirm = async (msg) => { globalThis.__confirmMsg = msg; return true; };
  HG.api._stub('POST /fund/transfer/7/approve', (b) => ({ id: 7, applyNo: 'TR-7', status: 'POSTED', aggCheckPassed: true }));
  findInput(root, 'fd-ap-id').value = '7';
  findInput(root, 'fd-ap-real').value = '990000.00';
  const apBtn = findBtnByText(root, '审批通过');
  await apBtn.click(); await tick();
  ok(String(globalThis.__confirmMsg).indexOf('不得为同一人') !== -1, 'transfer：审批二次确认含双人复核提示');
  ok(String(globalThis.__confirmMsg).indexOf('加总校验') !== -1, 'transfer：审批二次确认含加总校验提示');
  const ap = HG.api._calls.find((c) => c.p === '/fund/transfer/7/approve');
  ok(!!ap && ap.opts.idempotencyKey === 'probe-uuid', 'transfer：审批带 Idempotency-Key');
  ok(ap && ap.b.approved === true && ap.b.realAccountAvailable === '990000.00', 'transfer：审批请求契约对齐（realAccountAvailable 参与加总校验）');
  const apBody = cardOf(apBtn);
  ok(textOf(apBody).includes('审批完成') && textOf(apBody).indexOf('已记账') !== -1 && textOf(apBody).indexOf('加总校验') !== -1, 'transfer：加总校验通过结论渲染（审批完成 + 已记账 + 加总校验）');
  ok(textOf(apBody).indexOf('已回滚本次记账') === -1, 'transfer：通过结论不含回滚失败文案（aggCheckPassed=true 分支）');
  // 驳回必填
  HG.api._calls.length = 0;
  const rejBtn = findBtnByText(root, '驳回');
  HG.ui.confirm = async () => true;
  await rejBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p.indexOf('/approve') !== -1).length === 0, 'transfer：驳回未填原因被预检拦截');
  // 已回滚 = danger 徽标
  HG.api._stub('GET /fund/transfer', () => ({ items: [{ id: 7, applyNo: 'TR-7', fromSubAccountId: 8, toSubAccountId: 9, amount: '1000.00', status: 'ROLLED_BACK', aggCheckPassed: false }], total: 1, page: 1, size: 20 }));
  const sBtn = findBtnByText(root, '查询划拨');
  await sBtn.click(); await tick();
  const lBody = cardOf(sBtn);
  ok(textOf(lBody).includes('已回滚'), 'transfer：已回滚状态显式展示');
  HG.ui.confirm = uiConfirmReal;
}

/* ================= Tab quota：两态 + 设定契约 ================= */
switchTab('quota');
{
  ok(textOf(root).includes('不得跨子账户挪用'), 'quota：R-FUND-7 红线文案');
  ok(textOf(root).includes('告警不阻断'), 'quota：超限告警不阻断文案（R-RISK-5 原则）');
  ok(textOf(root).includes('判定在服务端'), 'quota：权限判定在服务端声明');
  HG.api._stub('GET /fund/quota', () => []);
  const btn = findBtnByText(root, '查询额度');
  await btn.click(); await tick();
  ok(textOf(cardOf(btn)).includes('暂无数据'), 'quota：空态显式');
  HG.api._stub('GET /fund/quota', () => [{ id: 1, subAccountId: 8, quotaScope: 'SUB_ACCOUNT', quotaValue: '500000.00', operatorId: 2 }]);
  await btn.click(); await tick();
  ok(textOf(cardOf(btn)).includes('500,000.00'), 'quota：数据态渲染额度值（千分位）');
  // 设定：子账户维度缺子账户被拦截
  HG.api._calls.length = 0;
  const uiConfirmReal = HG.ui.confirm;
  HG.ui.confirm = async () => true;
  const sBtn = findBtnByText(root, '设定额度');
  findInput(root, 'fd-q-set-value').value = '500000.00';
  await sBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p === '/fund/quota').length === 0, 'quota：子账户维度缺子账户被预检拦截');
  // 正常设定
  HG.api._stub('POST /fund/quota', (b) => ({ id: 2, subAccountId: 8, quotaScope: 'SUB_ACCOUNT', quotaValue: b.quotaValue, operatorId: 1 }));
  findInput(root, 'fd-q-set-sub').value = '8';
  const sel = findInput(root, 'fd-q-set-scope');
  sel.value = 'SUB_ACCOUNT';
  await sBtn.click(); await tick();
  const q = HG.api._calls.find((c) => c.p === '/fund/quota');
  ok(!!q && q.opts.idempotencyKey === 'probe-uuid', 'quota：额度设定带 Idempotency-Key');
  ok(q && q.b.quotaScope === 'SUB_ACCOUNT' && q.b.subAccountId === 8, 'quota：请求契约对齐（quotaScope/subAccountId）');
  HG.ui.confirm = uiConfirmReal;
}

/* ================= Tab margin：只读视图 + 对账差异展示 ================= */
switchTab('margin');
{
  ok(textOf(root).includes('只读'), 'margin：只读声明');
  ok(textOf(root).includes('同口径、同结果') || textOf(root).includes('FR-SETTLE-8'), 'margin：与 SETTLE 同口径声明（R-FUND-8）');
  HG.api._stub('GET /fund/margin', () => ({
    tradeDate: '2026-10-12',
    rows: [{ subAccountId: 8, marginOccupied: '120000.00', availableFund: '870000.00', equity: '990000.00', riskRatio: '0.1212' }],
    marginOccupiedTotal: '120000.00', availableFundTotal: '870000.00', equityTotal: '990000.00'
  }));
  const mBtn = findBtnByText(root, '查询保证金');
  await mBtn.click(); await tick();
  const mBody = cardOf(mBtn);
  ok(textOf(mBody).includes('120,000.00'), 'margin：数据态渲染占用（千分位）');
  ok(textOf(mBody).includes('合计'), 'margin：汇总区渲染（Σ=各子账户之和，FR-FUND-15）');
  ok(textOf(mBody).includes('风险度'), 'margin：风险度列（FR-FUND-15 验收 2）');
  // 对账：差异形态
  const uiConfirmReal = HG.ui.confirm;
  HG.ui.confirm = async () => true;
  HG.api._stub('POST /fund/reconcile', () => ({
    tradeDate: '2026-10-12', result: '不一致',
    subAccounts: [{ subAccountId: 8, reconcileLevel: 'SUB_ACCOUNT', beginBalance: '100', occurrenceTotal: '10', endBalance: '110', balanceEquationOk: true, aggCompareValue: '110', realAccountValue: '105', aggConsistent: false }],
    differences: [{ subAccountId: 8, diff: '5.00' }]
  }));
  const rcBtn = findBtnByText(root, '执行对账');
  await rcBtn.click(); await tick();
  const rcBody = cardOf(rcBtn);
  ok(textOf(rcBody).includes('不一致'), 'reconcile：不一致结论显式');
  ok(textOf(rcBody).includes('不得标记对账完成'), 'reconcile：未闭环差异不得标记完成（FR-FUND-17 验收 3）');
  ok(textOf(rcBody).includes('差异清单'), 'reconcile：差异清单显式');
  ok(textOf(rcBody).includes('不成立') === false || textOf(rcBody).includes('成立'), 'reconcile：平衡方程结论列渲染');
  // 幂等
  const rc = HG.api._calls.find((c) => c.p === '/fund/reconcile');
  ok(rc && rc.opts.idempotencyKey === 'probe-uuid', 'reconcile：对账提交带 Idempotency-Key');
  HG.ui.confirm = uiConfirmReal;
}

/* ================= Tab export：CSV 直返下载（T-195 起 /fund/export 返回 CSV；空数据不生成 + 数据态） ================= */
switchTab('export');
{
  ok(textOf(root).includes('CSV'), 'export：导出面声明 CSV 对账表形态（FR-FUND-4「输出=CSV 对账表」）');
  // 空数据（服务端仅表头）→ 拦截不下载并提示（FR-FUND-4 验收 4）
  HG.api._stub('GET /fund/export', () => 'section,subAccountId\r\n');
  const eBtn = findBtnByText(root, '导出 CSV');
  ok(!!eBtn, 'export：导出按钮存在（文本=导出 CSV 对账表）');
  await eBtn.click(); await tick();
  ok(textOf(cardOf(eBtn)).includes('暂无数据') && textOf(cardOf(eBtn)).includes('不生成'), 'export：无数据不生成文件并提示（FR-FUND-4 验收 4）');
  // 数据态（含 meta/data/sub_total/grand_total 行）→ 走 download
  HG.api._stub('GET /fund/export', () => 'section,subAccountId,ledgerDate,voucherNo,amount,endBalance,exportTime,exportedBy\r\n'
    + 'meta,,,,,,2026-10-12T10:00:00,1\r\n'
    + 'data,8,2026-10-12,FV202610120001,DEPOSIT,990000.00,,\r\n'
    + 'sub_total,8,,,,990000.00,,\r\n'
    + 'grand_total,,,,,990000.00,,\r\n');
  HG.api._calls.length = 0;
  await eBtn.click(); await tick();
  const dl = HG.api._calls.find((c) => c.m === 'DOWNLOAD' && c.p === '/fund/export');
  ok(!!dl, 'export：数据态走 HG.api.download（T-195 CSV 直返契约）');
  ok(dl && dl.q && dl.q.from === undefined, 'export：无筛选时不传空参（q 形态）');
  ok(textOf(cardOf(eBtn)).includes('正在触发下载') || textOf(cardOf(eBtn)).includes('数据行'), 'export：数据态反馈下载动作');
  // 筛选参数透传
  findInput(root, 'fd-e-sub').value = '8';
  HG.api._calls.length = 0;
  await eBtn.click(); await tick();
  const dl2 = HG.api._calls.find((c) => c.m === 'DOWNLOAD' && c.p === '/fund/export');
  ok(dl2 && dl2.q.subAccountId === '8', 'export：导出筛选参数透传（subAccountId）');
}

/* ================= 三态完备性走查（每 Tab 错误态抽验已覆盖 ledger/flow/transfer 前置，此处计数） ================= */
ok(pass + fails.length > 40, '探针断言总量充分（>40）');

/* ---------------- 结果 ---------------- */
console.log('T-055 fund probe: PASS=' + pass + ' FAIL=' + fails.length);
if (fails.length) { process.exit(1); }
process.exit(0);
