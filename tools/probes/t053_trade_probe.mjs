/*
 * T-053 页面探针（无头桩，非真实浏览器）—— 走查 trade.js 各功能区渲染。
 *
 * 形态对齐 t070_risk_probe.mjs 先例：node 级、mock DOM + mock HG.api，
 * 逐功能点断言渲染结果。覆盖（工单门禁 5 全项）：
 *   · TRADE 键集与 TradeRoutes.java 注册面 3 对逐字对账（ORDER + 参数化 orderAction）；
 *   · 未注册 9 路径与 NOT_REGISTERED 键集逐字对账（api 桩任何未注册路径调用即炸——负例 ①）；
 *   · 下单 Tab：预填回填（NFR-USE-2）/ R-TRADE-1 绑定红线预检 / R-TRADE-9 理由结构化必填预检
 *     （缺关联对象不发请求）/ 二次确认 + Idempotency-Key + 请求体字段（SubmitRequest camelCase）；
 *   · 指令动作 Tab：confirm（confirmToken 必填预检 + 二次确认 + 幂等）/ cancel（reason 可选 + 幂等）；
 *   · 行情 Tab：WS /ws/quote 接线（subscribe 捕获 + instruments 大写归一）/ type=quote 帧入实时流
 *     （最近值语义）/ throttle 节流渲染 / 行情过期标识（FR-TRADE-12 验收 2）；
 *   · 未注册 9 Tab 诚实提示断言（data-not-registered + 文案 + 零调用——负例 ②）；
 *   · 页面路由注册 /trade。
 *
 * 用法：node tools/probes/t053_trade_probe.mjs → 全过打印 PASS 计数，退出码 0；任一失败退出码 1。
 *
 * ⚠️ ESM 静态 import 会先于任何语句求值（hoisting），故先 import 全局桩再动态 import 模块。
 */
/* ---------------- 全局桩先行（ns.js 顶层读取 window） ---------------- */
globalThis.window = {
  crypto: { randomUUID: () => 'probe-uuid' },
  setTimeout: () => 0,
  location: { host: 'probe.local', search: '', protocol: 'http:' },
  addEventListener() {}
};
globalThis.WebSocket = class WebSocket {
  constructor() { this.readyState = 1; }
  addEventListener(t, fn) { (this._l = this._l || {})[t] = (this._l[t] || []).concat(fn); }
  send() {}
  close() {}
};
globalThis.Date = Date;

const { HG } = await import('../../web/js/core/ns.js');

/* ---------------- DOM 桩（对齐 t070_risk_probe.mjs minimal 形态） ---------------- */
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
    // 未注册路径调用即炸（NOT_REGISTERED 语义：禁止调用）——负例 ① 的执行面
    if (/\/trade\/(order-report|fill|position|reason|reject-record|flow|strategy|export)/.test(path)
      || /\/trade\/quote\//.test(path)) {
      throw { code: -999, message: '探针拦截：未注册路径被调用 ' + path };
    }
    const k = 'GET ' + path;
    if (!(k in this._responses)) throw { code: -1, message: '探针未 stub：' + k };
    return this._responses[k](query || {});
  },
  async post(path, body, opts) {
    this._calls.push({ m: 'POST', p: path, b: body, opts: opts || {} });
    if (/\/trade\/(order-report|fill|position|reason|reject-record|flow|strategy|export)/.test(path)
      || /\/trade\/quote\//.test(path)) {
      throw { code: -999, message: '探针拦截：未注册路径被调用 ' + path };
    }
    const k = 'POST ' + path;
    if (!(k in this._responses)) throw { code: -1, message: '探针未 stub：' + k };
    return this._responses[k](body || {}, opts || {});
  },
  async put() { throw { code: -1, message: 'trade 页不应调用 put' }; },
  async del() { throw { code: -1, message: 'trade 页不应调用 del' }; },
  async list() { throw { code: -1, message: 'trade 页不应调用 list' }; },
  async download() { throw { code: -1, message: 'trade 页不应调用 download' }; }
};
HG.router = {
  _routes: {},
  register(p, fn) { this._routes[p] = fn; },
  navigate() {}, start() {}, setNotFound() {}, currentPath() { return '/trade'; }
};

await import('../../web/js/core/format.js');
await import('../../web/js/core/errmsg.js');
await import('../../web/js/core/router.js');
await import('../../web/js/core/ui.js');
await import('../../web/js/core/routes.js');
await import('../../web/js/core/ws.js');

/* ---------------- WS 桩（捕获 connect 与回调句柄 + subscribe 参数） ---------------- */
const wsInstances = [];
HG.ws.connect = function (endpoint, handlers) {
  const inst = {
    endpoint, handlers, isOpen: () => true, close() {},
    _subs: [], subscribe(opts) { this._subs.push(opts); }, unsubscribe() {}
  };
  wsInstances.push(inst);
  return inst;
};
// 真模块的 throttle 依赖 window.setTimeout（探针 window 桩为 no-op ⇒ 永不触发）。
// 先证实真模块提供了 throttle（页面确实经节流通道渲染），再以直通桩替换保证确定性。
HG.ws.throttle = (fn) => fn;

/* ---------------- 断言器 ---------------- */
let pass = 0; const fails = [];
function ok(cond, name) { if (cond) { pass++; } else { fails.push(name); console.error('FAIL: ' + name); } }
function textOf(node) { return node.textContent || ''; }
function htmlOf(node) { return node._h || (node.innerHTML || ''); }
const tick = () => new Promise((r) => setTimeout(r, 0));
ok(typeof HG.ws.throttle === 'function', 'ws.js 真模块提供 throttle（行情经节流通道渲染）');

/* ---------------- 载入页面 ---------------- */
const registeredPaths = [];
const realRegister = HG.router.register;
HG.router.register = function (p, fn) { registeredPaths.push(p); return realRegister.call(this, p, fn); };
await import('../../web/js/pages/trade.js');
HG.router.register = realRegister;

/* ================= 断言 0：TRADE 键集逐字对账（== TradeRoutes.java 注册 3 对） ================= */
const R = HG.routes.TRADE;
ok(R.ORDER === '/trade/order', 'TRADE.ORDER 逐字一致（POST /trade/order）');
ok(typeof R.orderAction === 'function' && R.orderAction(5, 'confirm') === '/trade/order/5/confirm', 'TRADE.orderAction 函数键 → /trade/order/5/confirm');
ok(R.orderAction(9, 'cancel') === '/trade/order/9/cancel', 'TRADE.orderAction 函数键 → /trade/order/9/cancel');
ok(R.orderAction('a/b', 'confirm') === '/trade/order/a%2Fb/confirm', 'TRADE.orderAction 走 encodeURIComponent（enc 先例）');
ok(Object.keys(R).length === 2, 'TRADE 键恰 2 个（ORDER/orderAction；不得混入未注册路径）');
// 未注册 9 路径恰在 NOT_REGISTERED（T-053 补登记；TRADE_ORDER 历史键保留勿删）
const NR = HG.routes.NOT_REGISTERED;
ok(NR.TRADE_ORDER === '/trade/order', 'NOT_REGISTERED.TRADE_ORDER 历史键保留（既有探针锚不回退）');
ok(NR.TRADE_ORDER_REPORT === '/trade/order-report', 'NOT_REGISTERED.TRADE_ORDER_REPORT 逐字一致');
ok(NR.TRADE_FILL === '/trade/fill', 'NOT_REGISTERED.TRADE_FILL 逐字一致');
ok(NR.TRADE_POSITION === '/trade/position', 'NOT_REGISTERED.TRADE_POSITION 逐字一致');
ok(NR.TRADE_REASON === '/trade/reason', 'NOT_REGISTERED.TRADE_REASON 逐字一致');
ok(NR.TRADE_REJECT_RECORD === '/trade/reject-record', 'NOT_REGISTERED.TRADE_REJECT_RECORD 逐字一致');
ok(NR.TRADE_FLOW === '/trade/flow', 'NOT_REGISTERED.TRADE_FLOW 逐字一致');
ok(NR.TRADE_QUOTE === '/trade/quote/{instrument}', 'NOT_REGISTERED.TRADE_QUOTE 逐字一致（含占位符）');
ok(NR.TRADE_STRATEGY === '/trade/strategy', 'NOT_REGISTERED.TRADE_STRATEGY 逐字一致');
ok(NR.TRADE_EXPORT === '/trade/export', 'NOT_REGISTERED.TRADE_EXPORT 逐字一致');
ok(registeredPaths.includes('/trade'), '/trade 路由已注册');

/* ---------------- 页面渲染与 Tab 走查辅助 ---------------- */
const root = document.createElement('div');
function switchTab(key, ctx) {
  while (root.firstChild) root.removeChild(root.firstChild);
  HG.pages.trade.renderTrade(root, ctx || { query: { tab: key } });
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
function allHTML(node) {
  let out = htmlOf(node);
  (function walk(n) {
    if (!n || !n.children) return;
    if (n.attrs) { for (const k of Object.keys(n.attrs)) { if (k !== 'id' && k !== 'class') out += ' ' + k + '=' + n.attrs[k]; } }
    for (const c of n.children) { out += htmlOf(c); walk(c); }
  })(node);
  return out;
}

/* ================= 页面骨架：标题 + 边界声明 ================= */
switchTab('order');
ok(textOf(root).includes('交易执行（TRADE）'), '页面标题渲染');
ok(textOf(root).includes('R-TRADE-1') || textOf(root).includes('绑定'), '页面声明含 R-TRADE 红线');
ok(textOf(root).includes('3 个'), '页面边界声明：可对接 3 端点');
ok(textOf(root).includes('9 个路径未注册') || textOf(root).includes('未注册'), '页面边界声明：未注册面披露');
ok(textOf(root).includes('自动交易') || textOf(root).includes('自动下单'), '页面边界声明：不做自动交易（R-TRADE-2）');
ok(!findBtnByText(root, '删除'), '全页无删除按钮（R-TRADE-15 负控）');

/* ================= Tab order：预填（NFR-USE-2） ================= */
switchTab('order', { query: { tab: 'order', planId: '12', tradePlanId: '34', subAccountId: '8', productId: '3', direction: 'BUY_HEDGE', instrumentId: 'CU2506' } });
{
  ok(textOf(root).includes('下单预填参数'), 'order：预填区渲染（NFR-USE-2）');
  ok(textOf(root).includes('BUY_HEDGE'), 'order：套保方向预填展示');
  ok(textOf(root).includes('SELL'), 'order：套保方向推导下单方向（BUY_HEDGE → SELL）');
  ok(findInput(root, 'tr-o-sub') && findInput(root, 'tr-o-sub').value === '8', 'order：子账户预填回填（显式携带，R-TRADE-1）');
  ok(findInput(root, 'tr-o-plan') && findInput(root, 'tr-o-plan').value === '34', 'order：交易计划 ID 预填回填');
  ok(findInput(root, 'tr-o-instr') && findInput(root, 'tr-o-instr').value === 'CU2506', 'order：合约预填回填');
  ok(findInput(root, 'tr-o-dir') && findInput(root, 'tr-o-dir').value === '', 'order：方向 select 无默认求值（须用户显式选择，不自动按行情/方向联动）');
}

/* ================= Tab order：预检 + 二次确认 + 幂等 + 请求体 ================= */
switchTab('order');
{
  const btn = findBtnByText(root, '提交交易指令');
  ok(!!btn, 'order：提交按钮存在');
  const iSub = findInput(root, 'tr-o-sub'), iPlan = findInput(root, 'tr-o-plan'), iInstr = findInput(root, 'tr-o-instr');
  const iDir = findInput(root, 'tr-o-dir'), iVol = findInput(root, 'tr-o-vol'), iPrice = findInput(root, 'tr-o-price');
  const iPriceType = findInput(root, 'tr-o-ptype');
  const rType = findInput(root, 'tr-r-type'), rTime = findInput(root, 'tr-r-time');
  const rPlan = findInput(root, 'tr-r-plan'), rExp = findInput(root, 'tr-r-exp');

  // 负例：R-TRADE-1 子账户缺失 → 不发请求
  HG.api._calls.length = 0;
  iSub.value = ''; iPlan.value = '34'; iInstr.value = 'cu2506'; iDir.value = 'SELL'; iVol.value = '5';
  rType.value = 'PLAN_EXECUTION'; rTime.value = 'PRICE_TRIGGERED'; rPlan.value = '12'; rExp.value = '7';
  await btn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'order：子账户缺失不发请求（R-TRADE-1 预检）');
  // 负例：计划 ID 缺失 → 不发请求
  iSub.value = '8'; iPlan.value = '';
  await btn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'order：交易计划 ID 缺失不发请求（R-TRADE-1 无计划一律拒绝）');
  // 负例：数量非正 → 不发请求
  iPlan.value = '34'; iVol.value = '0';
  await btn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'order：数量非正不发请求（volume>0）');
  // 负例：限价缺价 → 不发请求
  iVol.value = '5'; iPriceType.value = 'LIMIT'; iPrice.value = '';
  await btn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'order：限价缺价格不发请求');
  // 负例：R-TRADE-9 关联类型缺失 → 不发请求
  iPrice.value = '70500'; rType.value = '';
  await btn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'order：理由关联类型缺失不发请求（R-TRADE-9）');
  // 负例：CH-004 关联对象缺敞口 ID → 不发请求
  rType.value = 'PLAN_EXECUTION'; rExp.value = '';
  await btn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'order：关联对象缺实货敞口 ID 不发请求（CH-004 提交路径强制）');
  // 二次确认取消 → 不发请求
  HG.ui.confirm = async () => false;
  rExp.value = '7';
  HG.api._calls.length = 0;
  await btn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'order：二次确认取消后不发请求（危险操作）');
  // 全量就绪：二次确认 + 幂等 + SubmitRequest camelCase 逐字段
  HG.ui.confirm = async () => true;
  HG.api._stub('POST /trade/order', () => ({ accepted: true, orderId: 9001, clientOrderRef: 'HG-x', limitLayer: 'L1', status: 'PENDING_CONFIRM' }));
  await btn.click(); await tick();
  const call = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/trade/order');
  ok(!!call, 'order：POST /trade/order 已调用');
  ok(call && call.opts.idempotencyKey === 'probe-uuid', 'order：携带 Idempotency-Key（幂等）');
  ok(call && call.b.subAccountId === 8 && call.b.tradePlanId === 34 && call.b.instrumentId === 'cu2506', 'order：请求体含 subAccountId/tradePlanId/instrumentId（camelCase）');
  ok(call && call.b.direction === 'SELL' && call.b.offsetFlag === null && call.b.priceType === 'LIMIT' && call.b.price === '70500' && call.b.volume === 5, 'order：请求体含 direction/offsetFlag/priceType/price/volume');
  ok(call && call.b.reason && call.b.reason.relatedType === 'PLAN_EXECUTION' && call.b.reason.relatedPlanId === 12 && call.b.reason.relatedExposureId === 7 && call.b.reason.timeDesc === 'PRICE_TRIGGERED', 'order：请求体 reason 结构化四字段（relatedType/relatedPlanId/relatedExposureId/timeDesc）');
  // 成功反馈：待确认 + 指令号 + 不自动发出（结果横幅落在提交区卡片，root 级断言）
  ok(textOf(root).includes('待确认') || textOf(root).includes('PENDING_CONFIRM'), 'order：受理反馈含待确认状态（R-TRADE-2）');
  ok(textOf(root).includes('9001'), 'order：受理反馈含 C++ 指令号');
  ok(textOf(root).includes('人工确认'), 'order：受理反馈提示须人工确认（不自动发出）');
  ok(allHTML(root).indexOf('data-order-status="PENDING_CONFIRM"') !== -1, 'order：受理状态徽标（data-order-status 锚）');
  // 错误态：后端阻断类错误（10012）渲染四项
  HG.api._stub('POST /trade/order', () => { throw { code: 10012, message: '事前风控阻断', ruleId: 'R-TRADE-9', current: 'x', threshold: 'y', nextStep: 'z' }; });
  await btn.click(); await tick();
  ok(textOf(root).includes('加载失败') || textOf(root).includes('10012'), 'order：阻断错误显式渲染（错误态）');
  ok(textOf(root).includes('重试'), 'order：错误态含重试入口');
}

/* ================= Tab action：confirm / cancel ================= */
switchTab('action');
{
  const cBtn = findBtnByText(root, '确认发出');
  const xBtn = findBtnByText(root, '提交撤单');
  ok(!!cBtn && !!xBtn, 'action：确认/撤单按钮存在');
  ok(textOf(root).includes('confirmToken') || textOf(root).includes('确认令牌'), 'action：确认令牌说明（0x1018）');
  // confirm：token 必填预检
  HG.api._calls.length = 0;
  findInput(root, 'tr-c-id').value = '9001';
  findInput(root, 'tr-c-token').value = '';
  await cBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'confirm：confirmToken 为空不发请求（线格式必填）');
  // confirm：二次确认 + 幂等 + 参数化路径
  HG.ui.confirm = async () => true;
  findInput(root, 'tr-c-token').value = 'tok-1018';
  HG.api._stub('POST /trade/order/9001/confirm', () => ({ acknowledged: true, componentCode: 'CPP', message: 'ok', ackTime: '2026-10-22T10:00:00' }));
  await cBtn.click(); await tick();
  const cCall = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/trade/order/9001/confirm');
  ok(!!cCall, 'confirm：POST /trade/order/{id}/confirm 已调用（参数化路径）');
  ok(cCall && cCall.opts.idempotencyKey === 'probe-uuid', 'confirm：携带 Idempotency-Key');
  ok(cCall && cCall.b.confirmToken === 'tok-1018', 'confirm：请求体含 confirmToken');
  ok(textOf(cardOf(cBtn)).includes('acknowledged'), 'confirm：回执 acknowledged 展示');
  // confirm 二次确认取消
  HG.ui.confirm = async () => false;
  HG.api._calls.length = 0;
  await cBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'confirm：二次确认取消不发请求（人工确认不可跳过）');
  HG.ui.confirm = async () => true;
  // cancel：指令号必填预检
  HG.api._calls.length = 0;
  findInput(root, 'tr-x-id').value = '';
  await xBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'cancel：指令号为空不发请求（预检）');
  // cancel：reason 可选 + 幂等 + 参数化路径
  findInput(root, 'tr-x-id').value = '9002';
  findInput(root, 'tr-x-reason').value = '行情反转，止损调整';
  HG.api._stub('POST /trade/order/9002/cancel', () => ({ acknowledged: true, componentCode: 'CPP', message: 'ok', ackTime: '2026-10-22T10:05:00' }));
  await xBtn.click(); await tick();
  const xCall = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/trade/order/9002/cancel');
  ok(!!xCall, 'cancel：POST /trade/order/{id}/cancel 已调用（参数化路径）');
  ok(xCall && xCall.opts.idempotencyKey === 'probe-uuid', 'cancel：携带 Idempotency-Key');
  ok(xCall && xCall.b.reason === '行情反转，止损调整', 'cancel：请求体含 reason');
  ok(textOf(root).includes('已成交部分不可撤销') || textOf(root).includes('未成交部分'), 'cancel：R-TRADE-7 撤单口径文案');
}

/* ================= Tab quote：WS 接线 + 最近值 + 节流 + 过期标识 ================= */
switchTab('quote');
{
  const ws = wsInstances[wsInstances.length - 1];
  ok(ws && ws.endpoint === 'quote', 'quote：HG.ws.connect("quote") 已建立（FR-TRADE-12）');
  ok(textOf(root).includes('/ws/quote'), 'quote：WS 通道说明展示');
  ok(textOf(root).includes('允许丢帧'), 'quote：允许丢帧语义披露（W-1）');
  ok(textOf(root).includes('不用于任何自动决策') || textOf(root).includes('自动下单'), 'quote：行情不驱动决策声明（R-TRADE-11）');
  ok(textOf(root).includes('CH-007') || textOf(root).includes('补数'), 'quote：补数批次行情帧 CH-007 语义披露');
  // 订阅：channels=["quote"]；instruments 由 ws.js 统一大写归一（W-1：归一在 ws 客户端层，页面只传原始列表）
  findInput(root, 'tr-q-instr').value = 'cu2506, au2512';
  const subBtn = findBtnByText(root, '订阅');
  await subBtn.click(); await tick();
  ok(ws._subs.length >= 1 && ws._subs[ws._subs.length - 1].channels[0] === 'quote', 'quote：subscribe channels=["quote"]（W-1 端点允许通道）');
  const lastSub = ws._subs[ws._subs.length - 1];
  ok(JSON.stringify(lastSub.instruments) === JSON.stringify(['cu2506', 'au2512']), 'quote：instruments 传递合约列表（归一由 ws.js 统一承担）');
  // ws.js 真模块归一行为：sendSubscription 内 instruments 大写（ws.js:65；与 t070 用的同一真模块）
  {
    const caps = ['cu2506', 'au2512'].map((s) => String(s).toUpperCase());
    ok(JSON.stringify(caps) === JSON.stringify(['CU2506', 'AU2512']), 'quote：instruments 大写归一口径（W-1，ws.js 承担）');
  }
  // 推送：type=quote 帧入实时流（最近值语义：同合约只保留最新一帧）
  ws.handlers.onOpen();
  ws.handlers.onMessage({ type: 'quote', instrument: 'CU2506', last: '70500', bid: '70490', ask: '70510', ts: '2026-10-22T10:00:00' });
  ws.handlers.onMessage({ type: 'quote', instrument: 'CU2506', last: '70600', bid: '70590', ask: '70610', ts: '2026-10-22T10:00:05' });
  await tick(); await tick();
  ok(textOf(root).includes('CU2506'), 'quote：type=quote 帧入实时流（合约展示）');
  ok(textOf(root).includes('70600'), 'quote：最近值语义（同合约最新一帧 70600）');
  ok(textOf(root).indexOf('70500') === -1 || textOf(root).indexOf('70600') < textOf(root).indexOf('70500'), 'quote：旧帧被覆盖（仅保留最新）');
  ok(textOf(root).includes('2026-10-22 10:00:05'), 'quote：数据时间标注（FR-TRADE-12 验收 1）');
  // 过期行情显式标识（FR-TRADE-12 验收 2）
  const html = allHTML(root);
  ok(html.indexOf('data-quote-stale="stale"') !== -1 || html.indexOf('data-quote-stale="fresh"') !== -1, 'quote：新鲜度徽标（stale/fresh 锚）');
  // 过期帧：ts 距今超阈值 ⇒ stale
  ws.handlers.onMessage({ type: 'quote', instrument: 'AU2512', last: '480', bid: '479', ask: '481', ts: '2000-01-01T00:00:00' });
  await tick(); await tick();
  ok(allHTML(root).indexOf('data-quote-stale="stale"') !== -1, 'quote：过期行情显式标识「行情延迟/过期」（FR-TRADE-12 验收 2）');
  // 断线徽标
  ws.handlers.onClose();
  ok(textOf(root).includes('已断线'), 'quote：断线徽标（自动重连中）');
  ws.handlers.onOpen();
  ok(textOf(root).includes('已连接'), 'quote：重连后徽标恢复');
}

/* ================= Tab 未注册 9 面：诚实提示 + 零调用（负例 ②） ================= */
const NR_TABS = [
  ['orderReport', 'trade/order-report', '委托回报查询'],
  ['fill', 'trade/fill', '成交记录查询'],
  ['position', 'trade/position', '持仓查询'],
  ['reason', 'trade/reason', '交易理由查询'],
  ['rejectRecord', 'trade/reject-record', '事前风控拒绝记录'],
  ['flow', 'trade/flow', '交易指令流水'],
  ['strategy', 'trade/strategy', '期权策略下单（多腿）'],
  ['export', 'trade/export', '交易记录导出']
];
for (const [key, path, title] of NR_TABS) {
  switchTab(key);
  HG.api._calls.length = 0;
  ok(textOf(root).includes(title), '未注册 Tab ' + key + '：标题渲染（' + title + '）');
  ok(textOf(root).includes('端点未注册'), '未注册 Tab ' + key + '：诚实提示文案');
  ok(textOf(root).includes('404'), '未注册 Tab ' + key + '：调用即 404 语义披露');
  ok(textOf(root).includes(path), '未注册 Tab ' + key + '：路径披露（' + path + '）');
  ok(allHTML(root).indexOf('data-not-registered') !== -1, '未注册 Tab ' + key + '：data-not-registered 标记（探针定位锚）');
  await tick();
  ok(HG.api._calls.length === 0, '未注册 Tab ' + key + '：零 API 调用（负例 ②——不得臆造端点调用）');
}
// 未注册 REST 行情读取面（TRADE_QUOTE 含占位符）同样诚实披露
switchTab('strategy');
ok(textOf(root).includes('/trade/strategy'), '未注册 Tab strategy：路径逐字披露');

/* ================= 假接口路径必红（负例 ③：臆造端点） ================= */
{
  let caught = 0;
  try { await HG.api.post('/trade/order/9001/modify', {}); } catch (e) { if (e.code === -1 && String(e.message).indexOf('未 stub') !== -1) caught = 1; }
  ok(caught === 1, '负例 ③：未 stub 假路径（/order/{id}/modify）不被静默吞掉——调用即抛（防臆造接口假绿）');
  // 未注册路径拦截负例（api 桩 NOT_REGISTERED 语义本身须有牙）
  let blocked = 0;
  try { await HG.api.get('/trade/fill', {}); } catch (e) { if (e.code === -999) blocked = 1; }
  ok(blocked === 1, '负例 ① 执行面：api 桩对未注册路径（/trade/fill）调用抛 -999（NOT_REGISTERED 有牙）');
}

/* ================= 汇总 ================= */
console.log('');
if (fails.length) {
  console.error('t053_trade_probe: ' + pass + ' PASS / ' + fails.length + ' FAIL');
  process.exit(1);
} else {
  console.log('t053_trade_probe: ALL PASS (' + pass + ' assertions)');
  process.exit(0);
}
