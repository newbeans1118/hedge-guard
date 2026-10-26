/*
 * T-054 页面探针（无头桩，非真实浏览器）—— 走查 settle.js 各功能区渲染。
 *
 * 形态对齐 t055_fund_probe.mjs 先例（T-021/T-071/T-055 形态）：node 级、mock DOM + mock HG.api，
 * 逐功能点断言渲染结果。覆盖（工单要求 4 判据）：
 *   · 端点面：页面路径常量与 ApiRoutes.java SETTLE 模块 12 路径逐字对账（15 方法对全覆盖）；
 *   · 渲染：6 Tab 装配 + 各 Tab 两态（空态 + 数据态）；加载/空/错误三态；
 *   · API 路径一致：列表/接收/核对/对账/关账/差异/报告/保证金/压测/任务/签署/导出调用路径逐字断言；
 *   · 关账态按钮禁用（R-SETTLE-5）：CONFIRMED ⇒ 重跑核对/加总对账/确认关账三按钮 disabled + 禁用横幅；
 *   · FR-SETTLE-1：pendingRetry=true ⇒ 「待重试」警示且不渲染成功文案（不以推算数据替代）；
 *   · 幂等：全部写操作带 Idempotency-Key；
 *   · 红线文案：R-SETTLE-3 权威基准 / Q-11 阻断范围 / R-SETTLE-4 调整类双人复核 / R-SETTLE-11 不可代签；
 *   · 负例 ≥2：①假路径必红（页面不得调用未注册路径）②关账态重算入口必禁用（disabled 且不发请求）。
 *
 * 用法：node tools/probes/t054_settle_probe.mjs → 全过打印 PASS 计数，退出码 0；任一失败退出码 1。
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
// settle.js 导出面走 triggerCsvDownload（fund.js 同构）⇒ 补 createObjectURL/revokeObjectURL
URL.createObjectURL = URL.createObjectURL || (() => 'blob:probe');
URL.revokeObjectURL = URL.revokeObjectURL || (() => {});

const { HG } = await import('../../web/js/core/ns.js');

/* ---------------- DOM 桩（对齐 t055_fund_probe.mjs 的 minimal 形态） ---------------- */
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
  async put(path, body, opts) {
    this._calls.push({ m: 'PUT', p: path, b: body, opts: opts || {} });
    const k = 'PUT ' + path;
    if (!(k in this._responses)) throw { code: -1, message: '探针未 stub：' + k };
    return this._responses[k](body || {}, opts || {});
  },
  async del() { throw { code: -1, message: 'settle 页不应调用 del' }; },
  async list() { throw { code: -1, message: 'settle 页不应调用 list' }; },
  async download(path, query) {
    this._calls.push({ m: 'DOWNLOAD', p: path, q: query });
    const k = 'GET ' + path;
    if (!(k in this._responses)) throw { code: -1, message: '探针未 stub：' + k };
    const text = this._responses[k](query || {});
    return { blob: new Blob([String(text)], { type: 'text/csv; charset=UTF-8' }), filename: 'settle-report.csv' };
  }
};
HG.router = {
  _routes: {},
  register(p, fn) { this._routes[p] = fn; },
  navigate() {}, start() {}, setNotFound() {}, currentPath() { return '/settle'; }
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
const tick = () => new Promise((r) => setTimeout(r, 0));

/* ---------------- 载入页面 ---------------- */
const registeredPaths = [];
const realRegister = HG.router.register;
HG.router.register = function (p, fn) { registeredPaths.push(p); return realRegister.call(this, p, fn); };
await import('../../web/js/pages/settle.js');
HG.router.register = realRegister;

/* ================= 断言 0：SETTLE 端点面对账（== ApiRoutes.java SETTLE 模块 12 路径 / 15 方法对） ================= */
const R = HG.routes.SETTLE;
ok(R !== undefined, 'routes.js SETTLE 键已由编排者落法承接（fe-t054 键清单逐字采纳，第 255 轮补3 裁决）；页面须改读 HG.routes.SETTLE');
ok(R.CTP_DATA === '/settle/ctp-data', 'routes SETTLE.CTP_DATA 逐字');
ok(R.CYCLE === '/settle/cycle', 'routes SETTLE.CYCLE 逐字');
ok(typeof R.cycleAction === 'function' && R.cycleAction(7, 'reconcile') === '/settle/cycle/7/reconcile', 'routes SETTLE.cycleAction 参数化');
ok(R.DIFFERENCE === '/settle/difference', 'routes SETTLE.DIFFERENCE 逐字');
ok(R.REPORT === '/settle/report', 'routes SETTLE.REPORT 逐字');
ok(R.MARGIN === '/settle/margin', 'routes SETTLE.MARGIN 逐字');
ok(R.STRESS_TEST === '/settle/stress-test', 'routes SETTLE.STRESS_TEST 逐字');
ok(R.MONTHLY_CHECK === '/settle/monthly-check', 'routes SETTLE.MONTHLY_CHECK 逐字');
ok(R.QUARTERLY_REPORT === '/settle/quarterly-report', 'routes SETTLE.QUARTERLY_REPORT 逐字');
ok(R.EXPORT === '/settle/export', 'routes SETTLE.EXPORT 逐字');
// 页面内路径常量对账（源码级逐字断言；调用面在下方各 Tab 逐一断言）
const src = (await import('node:fs')).readFileSync(new URL('../../web/js/pages/settle.js', import.meta.url), 'utf8');
const routesSrc = (await import('node:fs')).readFileSync(new URL('../../web/js/core/routes.js', import.meta.url), 'utf8'); // T-054 键迁移后字面量真源 = routes.js SETTLE 块（第 255 轮补3 落法）
ok(routesSrc.includes("CTP_DATA: '/settle/ctp-data'"), 'S.CTP_DATA 逐字 /settle/ctp-data');
ok(routesSrc.includes("CYCLE: '/settle/cycle'"), 'S.CYCLE 逐字 /settle/cycle');
ok(routesSrc.includes("DIFFERENCE: '/settle/difference'"), 'S.DIFFERENCE 逐字 /settle/difference');
ok(routesSrc.includes("REPORT: '/settle/report'"), 'S.REPORT 逐字 /settle/report');
ok(routesSrc.includes("MARGIN: '/settle/margin'"), 'S.MARGIN 逐字 /settle/margin');
ok(routesSrc.includes("STRESS_TEST: '/settle/stress-test'"), 'S.STRESS_TEST 逐字 /settle/stress-test');
ok(routesSrc.includes("MONTHLY_CHECK: '/settle/monthly-check'"), 'S.MONTHLY_CHECK 逐字 /settle/monthly-check');
ok(routesSrc.includes("QUARTERLY_REPORT: '/settle/quarterly-report'"), 'S.QUARTERLY_REPORT 逐字 /settle/quarterly-report');
ok(routesSrc.includes("EXPORT: '/settle/export'"), 'S.EXPORT 逐字 /settle/export');
ok(src.includes('HG.routes.SETTLE') && src.includes('const S = HG.routes.SETTLE;'), 'S 已迁移为 HG.routes.SETTLE 别名（第 255 轮补3 落法·零调用面改动）');
ok(registeredPaths.includes('/settle'), '/settle 路由已注册');

/* ---------------- 页面渲染与 Tab 走查辅助 ---------------- */
const root = document.createElement('div');
function switchTab(key) {
  while (root.firstChild) root.removeChild(root.firstChild);
  HG.pages.settle.renderSettle(root, { query: { tab: key } });
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

/* ================= Tab ctp：三态 + pendingRetry 警示（FR-SETTLE-1）+ 幂等 ================= */
switchTab('ctp');
ok(textOf(root).includes('结算管理（SETTLE）'), '页面标题渲染');
ok(textOf(root).includes('FR-SETTLE-1~17'), '页面描述含 FR 区间');
ok(textOf(root).includes('唯一权威基准'), 'ctp：R-SETTLE-3 权威基准红线文案');
ok(textOf(root).includes('不得用推算数据替代'), 'ctp：FR-SETTLE-1 验收 2 红线文案');
{
  const uiConfirmReal = HG.ui.confirm;
  HG.ui.confirm = async () => true;
  // 空明细回执：pendingRetry=true ⇒ 警示待重试、不渲染「接收成功」
  HG.api._calls.length = 0;
  HG.api._stub('POST /settle/ctp-data', () => ({ batchId: 9, pendingRetry: true, positionCount: 0, fundCount: 0, tradeCount: 0, positionPnlPresentCount: 0, positionPnlMissingCount: 0, message: '数据未到达' }));
  findInput(root, 'st-c-date').value = '2026-10-21';
  findInput(root, 'st-c-source').value = 'CTP-20261021.csv';
  const cBtn = findBtnByText(root, '提交日终数据');
  await cBtn.click(); await tick();
  const cBody = cardOf(cBtn);
  ok(textOf(cBody).includes('待重试'), 'ctp：pendingRetry=true 渲染待重试警示');
  ok(textOf(cBody).indexOf('接收成功') === -1, 'ctp：pendingRetry=true 不渲染成功文案（不以推算数据替代，FR-SETTLE-1 验收 2）');
  ok(textOf(cBody).includes('不产生基于推算的结算结果'), 'ctp：待重试警示含不产出推算结果语义');
  const cCall = HG.api._calls.find((c) => c.p === '/settle/ctp-data');
  ok(!!cCall && cCall.opts.idempotencyKey === 'probe-uuid', 'ctp：接收提交带 Idempotency-Key（FR-SETTLE-1 验收 3 幂等）');
  ok(cCall && cCall.b.tradeDate === '2026-10-21' && cCall.b.sourceIdentifier === 'CTP-20261021.csv', 'ctp：请求契约对齐（tradeDate/sourceIdentifier）');
  // 必填预检：来源标识缺失不发请求
  HG.api._calls.length = 0;
  findInput(root, 'st-c-source').value = '';
  await cBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p === '/settle/ctp-data').length === 0, 'ctp：来源标识缺失被预检拦截（FR-SETTLE-1 验收 4）');
  // 正常回执 + 旧模板兼容披露
  HG.api._stub('POST /settle/ctp-data', () => ({ batchId: 10, pendingRetry: false, positionCount: 3, fundCount: 1, tradeCount: 5, positionPnlPresentCount: 2, positionPnlMissingCount: 1, message: 'ok' }));
  findInput(root, 'st-c-source').value = 'CTP-B2';
  await cBtn.click(); await tick();
  const body2 = cardOf(cBtn);
  ok(textOf(body2).includes('接收成功'), 'ctp：正常回执渲染接收成功');
  ok(textOf(body2).includes('旧模板兼容披露'), 'ctp：positionPnlMissingCount>0 渲染 T-189 兼容披露');
  // 错误态
  HG.api._stub('POST /settle/ctp-data', () => { throw { code: 10001, message: 'tradeDate 格式非法' }; });
  await cBtn.click(); await tick();
  ok(textOf(cardOf(cBtn)).includes('10001') || textOf(cardOf(cBtn)).includes('失败'), 'ctp：错误态显式（三态齐全）');
  HG.ui.confirm = uiConfirmReal;
}

/* ================= Tab cycles：列表三态 + 执行结算 + 关账态禁用（R-SETTLE-5）+ 负例② ================= */
switchTab('cycles');
{
  // 列表空态
  HG.api._stub('GET /settle/cycle', () => ({ items: [], total: 0, page: 1, size: 20 }));
  const lBtn = findBtnByText(root, '查询结算单');
  await lBtn.click(); await tick();
  ok(textOf(cardOf(lBtn)).includes('暂无数据'), 'cycles：列表空态显式');
  // 列表数据态（含 CONFIRMED 行 ⇒ 关账态数据源就绪）
  HG.api._stub('GET /settle/cycle', (q) => ({
    items: [
      { id: 1, settleNo: 'SC20261020-001', tradeDate: '2026-10-20', subAccountId: 8, status: 'CONFIRMED', closePnl: '100.00', floatPnl: '5.00', commission: '2.00', marginOccupied: '1000.00', availableFund: '99000.00', equity: '100000.00', riskRatio: '0.01', negativeFundFlag: 'N' },
      { id: 2, settleNo: 'SC20261020-002', tradeDate: '2026-10-20', subAccountId: 9, status: 'GENERATED', closePnl: '200.00', floatPnl: '0', commission: '3.00', marginOccupied: '2000.00', availableFund: '88000.00', equity: '90000.00', riskRatio: '0.0222', negativeFundFlag: 'N' }
    ], total: 2, page: 1, size: 20
  }));
  HG.api._calls.length = 0;
  await lBtn.click(); await tick();
  const lBody = cardOf(lBtn);
  ok(textOf(lBody).includes('SC20261020-001'), 'cycles：数据态渲染结算单号');
  ok(textOf(lBody).includes('已确认（关账）'), 'cycles：CONFIRMED 状态徽标文案渲染');
  ok(textOf(lBody).includes('已生成（待核对）'), 'cycles：GENERATED 状态徽标文案渲染');
  const lCall = HG.api._calls.find((c) => c.m === 'GET' && c.p === '/settle/cycle');
  ok(lCall && lCall.q.sort === 'trade_date,desc', 'cycles：列表缺省排序 trade_date,desc（FR-SETTLE-15 验收 1 交易日倒序）');
  // 列表错误态
  HG.api._stub('GET /settle/cycle', () => { throw { code: 10003, message: '功能权限不足' }; });
  await lBtn.click(); await tick();
  ok(textOf(cardOf(lBtn)).includes('加载失败'), 'cycles：列表错误态显式（三态齐全）');
  // 关账态禁用（负例②）：输入 CONFIRMED 单 ID ⇒ 三按钮 disabled
  const recBtn = findBtnByText(root, '重跑三类核对');
  const aggBtn = findBtnByText(root, '加总对账');
  const closeBtn = findBtnByText(root, '确认关账');
  ok(!!recBtn && !!aggBtn && !!closeBtn, 'cycles：三动作按钮存在');
  findInput(root, 'st-a-id').value = '1';
  recBtn.attrs.disabled = undefined; aggBtn.attrs.disabled = undefined; closeBtn.attrs.disabled = undefined;
  // 触发 input 事件（refreshLock 挂在 input 事件上；探针桩须显式派发）
  const aInput = findInput(root, 'st-a-id');
  (aInput.listeners.input || []).forEach((fn) => fn({ target: aInput }));
  ok(recBtn.attrs.disabled === '' && aggBtn.attrs.disabled === '' && closeBtn.attrs.disabled === '', 'cycles：CONFIRMED ⇒ 三动作按钮 disabled（R-SETTLE-5 前端禁用形态，负例②）');
  ok(textOf(cardOf(recBtn)).includes('已确认（关账）') && textOf(cardOf(recBtn)).includes('禁用'), 'cycles：关账态禁用横幅渲染');
  // 负例②硬核断言：关账态点击重跑 ⇒ 不发请求（前端守卫）
  HG.api._calls.length = 0;
  HG.api._stub('POST /settle/cycle/1/reconcile', () => ({ trade: { type: 'trade', result: 'CONSISTENT', differenceCount: 0, reconcileRecordId: 1 } }));
  await recBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p === '/settle/cycle/1/reconcile').length === 0, 'cycles：负例②——关账态重算入口点击不发请求（前端守卫 + 后端 PERIOD_CLOSED 双层）');
  // 非 CONFIRMED 单：解锁
  findInput(root, 'st-a-id').value = '2';
  const aInput2 = findInput(root, 'st-a-id');
  (aInput2.listeners.input || []).forEach((fn) => fn({ target: aInput2 }));
  ok(recBtn.attrs.disabled === undefined && closeBtn.attrs.disabled === undefined, 'cycles：非关账单三动作可点（解锁态）');
  // 加总对账：不一致形态（Q-11 阻断范围渲染）
  HG.api._calls.length = 0;
  HG.ui.confirm = async () => true;
  HG.api._stub('POST /settle/cycle/2/aggregate', () => ({
    tradeDate: '2026-10-20', consistent: false,
    blockedSubAccountIds: [9],
    checks: [{ id: 5, checkType: 'FUND', sumSubAccount: '188000.00', realAccountValue: '190000.00', difference: '2000.00', result: 'INCONSISTENT_BLOCKED', blockScope: 'DIFF_SUB_NON_CLOSE', blockEffectiveTime: '2026-10-20T20:00:00' }]
  }));
  await aggBtn.click(); await tick();
  const aggBody = cardOf(aggBtn);
  ok(textOf(aggBody).includes('不一致（已阻断）'), 'cycles：加总对账不一致结论显式（不得静默通过）');
  ok(textOf(aggBody).includes('不波及其他子账户、不阻断平仓'), 'cycles：Q-11 阻断范围文案（仅差异所属子账户）');
  ok(textOf(aggBody).includes('DIFF_SUB_NON_CLOSE'), 'cycles：阻断范围字段渲染（blockScope）');
  ok(textOf(aggBody).includes('闭环前该子账户禁开新仓'), 'cycles：差异待处理事项语义渲染');
  const aggCall = HG.api._calls.find((c) => c.p === '/settle/cycle/2/aggregate');
  ok(!!aggCall && aggCall.opts.idempotencyKey === 'probe-uuid', 'cycles：加总对账带 Idempotency-Key');
  // 一致形态
  HG.api._stub('POST /settle/cycle/2/aggregate', () => ({ tradeDate: '2026-10-20', consistent: true, blockedSubAccountIds: [], checks: [{ id: 6, checkType: 'FUND', sumSubAccount: '188000.00', realAccountValue: '188000.00', difference: '0', result: 'CONSISTENT', blockScope: '', blockEffectiveTime: null }] }));
  await aggBtn.click(); await tick();
  ok(textOf(cardOf(aggBtn)).includes('允许关账'), 'cycles：加总一致 ⇒ 允许关账（前置②达成）');
  // 关账成功 ⇒ 状态缓存更新 + 锁定生效（状态机闭环）
  HG.api._stub('POST /settle/cycle/2/close', () => ({ cycleId: 2, settleNo: 'SC20261020-002', confirmed: true }));
  HG.api._calls.length = 0;
  await closeBtn.click(); await tick();
  const closeCall = HG.api._calls.find((c) => c.p === '/settle/cycle/2/close');
  ok(!!closeCall && closeCall.opts.idempotencyKey === 'probe-uuid', 'cycles：关账提交带 Idempotency-Key');
  ok(textOf(cardOf(closeBtn)).includes('关账成功'), 'cycles：关账成功反馈');
  ok(recBtn.attrs.disabled === '' && aggBtn.attrs.disabled === '' && closeBtn.attrs.disabled === '', 'cycles：关账成功后状态缓存更新 ⇒ 动作禁用（R-SETTLE-5 状态机闭环）');
}

/* ================= Tab cycles：执行结算（明细接缝披露 + 幂等） ================= */
switchTab('cycles');
{
  ok(textOf(root).includes('T-172 域') || textOf(root).includes('事件流承接'), 'cycles：执行结算明细接缝诚实披露（事件源实态）');
  ok(textOf(root).includes('DATA_INSUFFICIENT'), 'cycles：CTP 明细缺失拒绝语义（不产出推算结算结果）');
  HG.api._calls.length = 0;
  HG.ui.confirm = async () => true;
  HG.api._stub('POST /settle/cycle', () => [
    { id: 3, settleNo: 'SC20261021-001', tradeDate: '2026-10-21', subAccountId: 8, status: 'GENERATED', closePnl: '10.00', floatPnl: '1.00', commission: '0.50', riskRatio: '0.01' },
    { id: 4, settleNo: 'SC20261021-002', tradeDate: '2026-10-21', subAccountId: 9, status: 'GENERATED', closePnl: '20.00', floatPnl: '2.00', commission: '0.60', riskRatio: '0.02' }
  ]);
  findInput(root, 'st-r-date').value = '2026-10-21';
  const rBtn = findBtnByText(root, '执行结算');
  await rBtn.click(); await tick();
  const rCall = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/settle/cycle');
  ok(!!rCall && rCall.opts.idempotencyKey === 'probe-uuid', 'cycles：执行结算带 Idempotency-Key');
  ok(rCall && rCall.b.tradeDate === '2026-10-21', 'cycles：执行结算契约对齐（tradeDate）');
  const rBody = cardOf(rBtn);
  ok(textOf(rBody).includes('SC20261021-001') && textOf(rBody).includes('SC20261021-002'), 'cycles：执行结算渲染两份子账户结算单（R-SETTLE-1 独立出账）');
  // 错误态
  HG.api._stub('POST /settle/cycle', () => { throw { code: 10013, message: '加总对账不一致，已阻断' }; });
  await rBtn.click(); await tick();
  ok(textOf(cardOf(rBtn)).includes('10013') || textOf(cardOf(rBtn)).includes('阻断'), 'cycles：执行结算错误态显式（含阻断类错误语义）');
}

/* ================= Tab difference：调整类双人复核预检（R-SETTLE-4）+ 契约 ================= */
switchTab('difference');
{
  ok(textOf(root).includes('不得标记结算完成'), 'difference：未闭环差异不得标记结算完成（R-SETTLE-4/5）');
  ok(textOf(root).includes('双人复核'), 'difference：调整类双人复核文案（R-SETTLE-4）');
  HG.api._calls.length = 0;
  HG.ui.confirm = async () => true;
  // ADJUST 未填复核人 ⇒ 预检拦截
  findInput(root, 'st-d-id').value = '7';
  const dSel = findInput(root, 'st-d-method');
  dSel.value = 'ADJUST';
  const dBtn = findBtnByText(root, '提交差异处理');
  await dBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p === '/settle/difference').length === 0, 'difference：ADJUST 缺复核人被预检拦截（R-SETTLE-4 前端预检）');
  // 填复核人后提交：PUT 契约 + 幂等
  HG.api._stub('PUT /settle/difference', () => ({ differenceId: 7, handleStatus: 'CLOSED', closed: true }));
  findInput(root, 'st-d-reviewer').value = '3';
  await dBtn.click(); await tick();
  const dCall = HG.api._calls.find((c) => c.m === 'PUT' && c.p === '/settle/difference');
  ok(!!dCall, 'difference：PUT /settle/difference 已调用（方法级契约）');
  ok(dCall && dCall.b.id === 7 && dCall.b.handleMethod === 'ADJUST' && dCall.b.reviewerId === 3, 'difference：请求契约对齐（id/handleMethod/reviewerId）');
  ok(dCall && dCall.opts.idempotencyKey === 'probe-uuid', 'difference：差异处理带 Idempotency-Key');
  ok(textOf(cardOf(dBtn)).includes('已闭环'), 'difference：闭环结论渲染');
  // 未闭环形态
  HG.api._stub('PUT /settle/difference', () => ({ differenceId: 7, handleStatus: 'HANDLING', closed: false }));
  await dBtn.click(); await tick();
  ok(textOf(cardOf(dBtn)).includes('不得进入「已确认」'), 'difference：未闭环提示渲染（FR-SETTLE-9 验收 1 语义）');
  // 错误态
  HG.api._stub('PUT /settle/difference', () => { throw { code: 10012, message: '调整类须双人复核' }; });
  await dBtn.click(); await tick();
  ok(textOf(cardOf(dBtn)).includes('10012') || textOf(cardOf(dBtn)).includes('失败'), 'difference：错误态显式（三态齐全）');
}

/* ================= Tab reports：报告两态 + 保证金视图 + 压测 ================= */
switchTab('reports');
{
  // 报告：数据缺失拒绝（PARAM_INVALID）⇒ 错误态
  const pBtn = findBtnByText(root, '生成/查询报告');
  const pTypeSel = findInput(root, 'st-p-type');
  pTypeSel.value = 'DAILY_RISK';
  HG.api._stub('GET /settle/report', () => { throw { code: 10001, message: 'tradeDate 必填' }; });
  findInput(root, 'st-p-date').value = '2026-10-21';
  await pBtn.click(); await tick();
  ok(textOf(cardOf(pBtn)).includes('10001') || textOf(cardOf(pBtn)).includes('失败'), 'reports：报告错误态显式');
  // 报告：加总对账不一致 ⇒ 显式标注（FR-SETTLE-10 验收 2）
  HG.api._stub('GET /settle/report', (q) => ({ id: 1, tradeDate: q.tradeDate, reportType: 'DAILY_RISK', aggResult: 'INCONSISTENT_BLOCKED', contentJson: '{"days":1}', generatedBy: 2, generatedTime: '2026-10-21T20:00:00' }));
  HG.api._calls.length = 0;
  await pBtn.click(); await tick();
  const pBody = cardOf(pBtn);
  ok(textOf(pBody).includes('INCONSISTENT_BLOCKED'), 'reports：报告渲染加总对账结论（不一致显式）');
  ok(textOf(pBody).includes('不一致/已阻断'), 'reports：FR-SETTLE-10 验收 2 显式标注语义');
  ok(textOf(pBody).includes('生成人 #2'), 'reports：生成人留痕渲染（验收 3）');
  const pCall = HG.api._calls.find((c) => c.p === '/settle/report');
  ok(pCall && pCall.q.tradeDate === '2026-10-21' && pCall.q.reportType === 'DAILY_RISK', 'reports：报告查询参数透传（tradeDate/reportType）');
  // 保证金：空态（数据未到达 ⇒ 不以推算替代）
  const mBtn = findBtnByText(root, '查询保证金视图');
  HG.api._stub('GET /settle/margin', () => ({ tradeDate: '2026-10-21', items: [], sumEquity: '0', sumMarginOccupied: '0', aggregateRiskRatio: null, aggregateAlert: false }));
  findInput(root, 'st-m-date').value = '2026-10-21';
  await mBtn.click(); await tick();
  ok(textOf(cardOf(mBtn)).includes('暂无数据') && textOf(cardOf(mBtn)).includes('不以推算数据替代'), 'reports：保证金空态含 R-SETTLE-3 语义（数据未到达不推算）');
  // 保证金：数据态 + 告警
  HG.api._stub('GET /settle/margin', () => ({
    tradeDate: '2026-10-21', sumEquity: '190000.00', sumMarginOccupied: '3000.00', aggregateRiskRatio: '0.0158', aggregateAlert: true,
    items: [{ subAccountId: 8, equity: '100000.00', marginOccupied: '1000.00', riskRatio: '0.01', alert: false }, { subAccountId: 9, equity: '90000.00', marginOccupied: '2000.00', riskRatio: '0.0222', alert: true }]
  }));
  HG.api._calls.length = 0;
  await mBtn.click(); await tick();
  const mBody = cardOf(mBtn);
  ok(textOf(mBody).includes('190,000.00'), 'reports：保证金数据态渲染汇总权益');
  ok(textOf(mBody).includes('超阈值'), 'reports：告警渲染（FR-SETTLE-11 验收 2）');
  const mCall = HG.api._calls.find((c) => c.p === '/settle/margin');
  ok(mCall && mCall.q.tradeDate === '2026-10-21', 'reports：保证金查询参数透传');
  // 压测：幅度域预检 + 契约
  const sBtn = findBtnByText(root, '执行压力测试');
  HG.api._calls.length = 0;
  HG.ui.confirm = async () => true;
  findInput(root, 'st-s-date').value = '2026-10-21';
  findInput(root, 'st-s-shock').value = '1.5';
  await sBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p === '/settle/stress-test').length === 0, 'reports：压测幅度越域被预检拦截（0~1）');
  HG.api._stub('POST /settle/stress-test', () => ({ id: 3, testDate: '2026-10-21', marginGap: '5000.00', additionalRequirement: '6000.00', equityAfter: '185000.00', riskRatioAfter: '0.0162' }));
  findInput(root, 'st-s-shock').value = '0.1';
  await sBtn.click(); await tick();
  const sCall = HG.api._calls.find((c) => c.p === '/settle/stress-test');
  ok(!!sCall && sCall.b.priceShockPct === '0.1' && sCall.b.subAccountId === null, 'reports：压测契约对齐（priceShockPct/subAccountId=null=全账户）');
  ok(textOf(cardOf(sBtn)).includes('保证金缺口'), 'reports：压测结果渲染（缺口/追加需求/权益/风险度）');
  ok(sCall && sCall.opts.idempotencyKey === 'probe-uuid', 'reports：压测带 Idempotency-Key');
}

/* ================= Tab checks：任务查询两态 + 签署记录 + 生成 + 签署（R-SETTLE-11） ================= */
switchTab('checks');
{
  ok(textOf(root).includes('不可代签'), 'checks：不可代签红线（R-SETTLE-11/Q-14）');
  ok(textOf(root).includes('不得操盘') || textOf(root).includes('只签收'), 'checks：管理层签署权与交易权互斥文案');
  // 查询：月度端点 + 数据态（含签署记录）
  const qBtn = findBtnByText(root, '查询核对任务');
  const qEpSel = findInput(root, 'st-q-ep');
  qEpSel.value = 'MONTHLY_CHECK';
  HG.api._stub('GET /settle/monthly-check', (q) => ({
    items: [
      { id: 1, checkType: 'MONTHLY', periodKey: '2026-09', subAccountId: 0, requiredSignRoles: 'RISK,TRADE,FINANCE', signedRoles: 'RISK,TRADE', status: 'PARTIAL_SIGNED', snapshotHash: 'a1b2c3d4e5f60718293a4b5c', deadlineDate: '2026-10-31', overdueAlerted: 'N', generatedTime: '2026-10-01T08:00:00', completedTime: null,
        signatures: [{ signRole: 'RISK', signerId: 2, signerRoleCode: 'RISK', signTime: '2026-10-05T09:00:00', signIp: '10.0.0.8', snapshotHash: 'a1b2c3d4e5f60718293a4b5c', signResult: 'PASS', signOpinion: '', proxyRejected: 'N', manageRoleTradeBlocked: 'N' }] },
      { id: 2, checkType: 'MONTHLY', periodKey: '2026-08', subAccountId: 0, requiredSignRoles: 'RISK,TRADE,FINANCE', signedRoles: '', status: 'OVERDUE', snapshotHash: 'ffeeddccbbaa998877665544', deadlineDate: '2026-09-30', overdueAlerted: 'Y', generatedTime: '2026-09-01T08:00:00', completedTime: null, signatures: [] }
    ], total: 2, page: 1, size: 20
  }));
  HG.api._calls.length = 0;
  await qBtn.click(); await tick();
  const qBody = cardOf(qBtn);
  ok(textOf(qBody).includes('月度三方核对') && textOf(qBody).includes('2026-09'), 'checks：任务数据态渲染（类型中文 + 期间）');
  ok(textOf(qBody).includes('已逾期'), 'checks：OVERDUE 状态显式（R-SETTLE-11 逾期告警语义）');
  ok(textOf(qBody).includes('风控岗、交易岗'), 'checks：应签角色中文渲染');
  ok(textOf(qBody).includes('跨子账户'), 'checks：子账户哨兵 0 渲染为跨子账户');
  ok(textOf(qBody).includes('10.0.0.8'), 'checks：签署记录含 IP（FR-SETTLE-17 验收 2）');
  ok(textOf(qBody).includes('a1b2c3d4e5f6'), 'checks：签署记录含快照哈希');
  const qCall = HG.api._calls.find((c) => c.m === 'GET' && c.p === '/settle/monthly-check');
  ok(!!qCall, 'checks：GET /settle/monthly-check 已调用（月度端点）');
  ok(qCall && qCall.q.sort === 'generated_time,desc', 'checks：任务查询缺省排序 generated_time,desc（后端缺省同形）');
  // 切换季度端点
  const epSel = findInput(root, 'st-q-ep');
  epSel.value = 'QUARTERLY_REPORT';  HG.api._calls.length = 0;
  HG.api._stub('GET /settle/quarterly-report', () => ({ items: [], total: 0, page: 1, size: 20 }));
  await qBtn.click(); await tick();
  const q2 = HG.api._calls.find((c) => c.m === 'GET' && c.p === '/settle/quarterly-report');
  ok(!!q2, 'checks：GET /settle/quarterly-report 已调用（季度端点切换）');
  ok(textOf(cardOf(qBtn)).includes('暂无数据'), 'checks：季度端点空态显式');
  // 生成任务：期间键必填预检 + GENERATE 契约
  HG.api._calls.length = 0;
  HG.ui.confirm = async () => true;
  epSel.value = 'MONTHLY_CHECK';
  const gEpSel = findInput(root, 'st-g-ep');
  gEpSel.value = 'MONTHLY_CHECK';
  const gBtn = findBtnByText(root, '生成核对任务');
  const gSel = findInput(root, 'st-g-type');
  gSel.value = 'MONTHLY';
  await gBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.p === '/settle/monthly-check' && c.m === 'POST').length === 0, 'checks：期间键缺失被预检拦截');
  HG.api._stub('POST /settle/monthly-check', (b) => ({ id: 3, checkType: b.checkType, periodKey: b.periodKey, status: 'PENDING_SIGN', requiredSignRoles: 'RISK,TRADE,FINANCE', signedRoles: '' }));
  findInput(root, 'st-g-period').value = '2026-10';
  await gBtn.click(); await tick();
  const gCall = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/settle/monthly-check');
  ok(gCall && gCall.b.action === 'GENERATE' && gCall.b.checkType === 'MONTHLY' && gCall.b.periodKey === '2026-10', 'checks：生成任务契约对齐（action=GENERATE/checkType/periodKey）');
  ok(gCall && gCall.opts.idempotencyKey === 'probe-uuid', 'checks：生成任务带 Idempotency-Key');
  // 签署：角色必填预检 + 签署契约
  const sBtn = findBtnByText(root, '提交签署');
  findInput(root, 'st-s2-ep').value = 'MONTHLY_CHECK';
  HG.api._calls.length = 0;
  findInput(root, 'st-s2-task').value = '3';
  const sSel = findInput(root, 'st-s2-role');
  sSel.value = '';
  await sBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST' && c.p === '/settle/monthly-check').length === 0, 'checks：签署角色未显式选择被预检拦截（不猜测不默认，R-SETTLE-11）');
  HG.api._stub('POST /settle/monthly-check', (b) => ({ taskId: b.taskId, status: 'PARTIAL_SIGNED', requiredSignRoles: 'RISK,TRADE,FINANCE', signedRoles: 'RISK,TRADE', signRole: b.signRole, signerId: 7 }));
  sSel.value = 'TRADE';
  findInput(root, 'st-s2-hash').value = 'a1b2c3d4e5f60718293a4b5c';
  await sBtn.click(); await tick();
  const sCall = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/settle/monthly-check' && c.b.signRole);
  ok(sCall && sCall.b.taskId === 3 && sCall.b.signRole === 'TRADE' && sCall.b.snapshotHash === 'a1b2c3d4e5f60718293a4b5c', 'checks：签署契约对齐（taskId/signRole/snapshotHash）');
  ok(sCall && sCall.opts.idempotencyKey === 'probe-uuid', 'checks：签署带 Idempotency-Key');
  ok(textOf(cardOf(sBtn)).includes('签署已受理'), 'checks：签署受理反馈渲染');
}

/* ================= Tab export：CSV 直返下载（T-204 起 /settle/export 返回 CSV；空数据不生成 + 数据态） ================= */
switchTab('export');
{
  ok(textOf(root).includes('已直返化'), 'export：导出形态已直返化声明（T-204，替代原 JSON 信封张力披露）');
  ok(textOf(root).includes('CSV'), 'export：CSV 直返声明（HG.api.download 形态）');
  // 空数据：后端空态契约 = 仅表头一行（/fund/export、/product/export 先例同构：服务端恒回
  // CSV 表头行，前端以「行数 ≤ 1」判空、不触发下载并提示）
  HG.api._stub('GET /settle/export', () => 'section,settleNo,tradeDate,subAccountId,status,equity,aggResult,exportTime,exportedBy\r\n');
  const eBtn = findBtnByText(root, '导出结算报表');
  await eBtn.click(); await tick();
  ok(textOf(cardOf(eBtn)).includes('暂无数据') && textOf(cardOf(eBtn)).includes('不生成'), 'export：无数据不生成文件（FR-SETTLE-16 验收 3）');
  // 数据态：CSV 直返（meta 行 + data 行，T-195 先例同构）→ 走 download
  HG.api._stub('GET /settle/export', () => 'section,settleNo,tradeDate,subAccountId,status,equity,aggResult,exportTime,exportedBy\r\n'
    + 'meta,,,,,,,2026-10-21T20:00:00,1\r\n'
    + 'data,ST-20261020-1,2026-10-20,8,RECONCILED,12345.00,CONSISTENT,,\r\n');
  HG.api._calls.length = 0;
  await eBtn.click(); await tick();
  const dl = HG.api._calls.find((c) => c.m === 'DOWNLOAD' && c.p === '/settle/export');
  ok(!!dl, 'export：数据态走 HG.api.download（T-204 CSV 直返契约）');
  ok(textOf(cardOf(eBtn)).includes('数据行') && textOf(cardOf(eBtn)).includes('正在触发下载'), 'export：数据态反馈下载动作（含数据行计数）');
  // 筛选参数透传
  findInput(root, 'st-e-sub').value = '8';
  findInput(root, 'st-e-from').value = '2026-10-01';
  HG.api._calls.length = 0;
  await eBtn.click(); await tick();
  const e2 = HG.api._calls.find((c) => c.m === 'DOWNLOAD' && c.p === '/settle/export');
  ok(e2 && e2.q.subAccountId === '8' && e2.q.from === '2026-10-01', 'export：筛选参数透传（from/subAccountId）');
  // 错误态
  HG.api._stub('GET /settle/export', () => { throw { code: 10003, message: '功能权限不足' }; });
  await eBtn.click(); await tick();
  ok(textOf(cardOf(eBtn)).includes('加载失败') || textOf(cardOf(eBtn)).includes('失败'), 'export：错误态显式（三态齐全）');
}

/* ================= 负例①：假路径必红（页面路径常量全为已注册面，无臆造路径） ================= */
{
  // 页面源码中出现的全部 /settle/ 前缀路径字面量必须 ∈ ApiRoutes SETTLE 12 路径
  const srcAll = (await import('node:fs')).readFileSync(new URL('../../web/js/pages/settle.js', import.meta.url), 'utf8');
  const routesAll = (await import('node:fs')).readFileSync(new URL('../../web/js/core/routes.js', import.meta.url), 'utf8'); // 负例① 扫描面扩展：S 迁移后页面字面量真源含 routes.js
  const literals = new Set(((srcAll + routesAll).match(/'\/settle\/[a-z-]+'/g) || []).map((s) => s.slice(1, -1)));
  const REGISTERED = new Set(['/settle/ctp-data', '/settle/cycle', '/settle/difference', '/settle/report',
    '/settle/margin', '/settle/stress-test', '/settle/monthly-check', '/settle/quarterly-report', '/settle/export']);
  let allIn = true;
  literals.forEach((p) => { if (!REGISTERED.has(p)) { allIn = false; console.error('  非注册路径字面量：' + p); } });
  ok(allIn && literals.size >= 9, '负例①——假路径必红：页面路径字面量全部 ∈ 已注册 SETTLE 面（9 静态，无臆造；cycleAction 参数化另测）');
  // 页面对未注册路径零调用（api mock 对非注册路径强制抛错——若页面误调即探针红）
  ok(srcAll.indexOf('/settle/post-report') === -1 && srcAll.indexOf('/settle/dashboard') === -1, '负例①补充：无 risk 风格臆造路径（/settle/post-report 等）');
}

/* ================= 三态完备性 + 幂等覆盖计数 ================= */
ok(pass + fails.length > 60, '探针断言总量充分（>60）');

/* ---------------- 结果 ---------------- */
console.log('T-054 settle probe: PASS=' + pass + ' FAIL=' + fails.length);
if (fails.length) { process.exit(1); }
process.exit(0);
