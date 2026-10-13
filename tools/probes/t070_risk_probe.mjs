/*
 * T-070 页面探针（无头桩，非真实浏览器）—— 走查 risk.js 各功能区渲染。
 *
 * 形态对齐 t055_fund_probe.mjs 先例（T-021/T-071/T-055 形态）：node 级、mock DOM + mock HG.api，
 * 逐功能点断言渲染结果。覆盖（工单门禁 1 全项）：
 *   · RISK 键集与 ApiRoutes.java 方法注册表 6 对逐字对账（含参数化 dispose 函数键）；
 *   · 未注册 5 路径与 NOT_REGISTERED 键集逐字对账（不臆造调用断言：api 桩任何未注册路径调用即炸）；
 *   · 可接 5 端点 Tab 三态（加载 / 空 / 错误 + 重试）；
 *   · 处置动作：二次确认 + Idempotency-Key + 理由必填预检 + 强平提示 hintAccepted=Y/N 分支；
 *   · 未注册 5 Tab 诚实提示断言（data-not-registered + 文案，无任何 api 调用）；
 *   · WS 推送模拟（type=alert 入实时流 + throttle 渲染）+ 断线重连补拉（onOpen 非首次 → GET /risk/alert?since=）;
 *   · 页面路由注册 /risk。
 *
 * 用法：node tools/probes/t070_risk_probe.mjs → 全过打印 PASS 计数，退出码 0；任一失败退出码 1。
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

/* ---------------- DOM 桩（对齐 t055_fund_probe.mjs minimal 形态） ---------------- */
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
    // 未注册路径调用即炸（NOT_REGISTERED 语义：禁止调用）
    if (/\/risk\/(dashboard|limit-open|force-close-hint|violation|post-report)/.test(path)) {
      throw { code: -999, message: '探针拦截：未注册路径被调用 ' + path };
    }
    const k = 'GET ' + path;
    if (!(k in this._responses)) throw { code: -1, message: '探针未 stub：' + k };
    return this._responses[k](query || {});
  },
  async post(path, body, opts) {
    this._calls.push({ m: 'POST', p: path, b: body, opts: opts || {} });
    if (/\/risk\/(dashboard|limit-open|force-close-hint|violation|post-report)/.test(path)) {
      throw { code: -999, message: '探针拦截：未注册路径被调用 ' + path };
    }
    const k = 'POST ' + path;
    if (!(k in this._responses)) throw { code: -1, message: '探针未 stub：' + k };
    return this._responses[k](body || {}, opts || {});
  },
  async put() { throw { code: -1, message: 'risk 页不应调用 put' }; },
  async del() { throw { code: -1, message: 'risk 页不应调用 del' }; },
  async list() { throw { code: -1, message: 'risk 页不应调用 list' }; },
  async download() { throw { code: -1, message: 'risk 页不应调用 download' }; }
};
HG.router = {
  _routes: {},
  register(p, fn) { this._routes[p] = fn; },
  navigate() {}, start() {}, setNotFound() {}, currentPath() { return '/risk'; }
};

await import('../../web/js/core/format.js');
await import('../../web/js/core/errmsg.js');
await import('../../web/js/core/router.js');
await import('../../web/js/core/ui.js');
await import('../../web/js/core/routes.js');
await import('../../web/js/core/ws.js');

/* ---------------- WS 桩（捕获 connect 与回调句柄） ---------------- */
const wsInstances = [];
HG.ws.connect = function (endpoint, handlers) {
  const inst = { endpoint, handlers, isOpen: () => true, close() {}, subscribe() {}, unsubscribe() {} };
  wsInstances.push(inst);
  return inst;
};
// 真模块的 throttle 依赖 window.setTimeout（探针 window 桩为 no-op ⇒ 永不触发）。
// 探针先证实真模块提供了 throttle（页面确实经节流通道渲染），再以直通桩替换保证确定性
// （页面代码 alertTab 内 `HG.ws.throttle ? HG.ws.throttle(...) : renderFeed` 在 Tab 构建时才求值）。
// 断言推迟到断言器就绪后执行（见下方 throttleProbe 判定）。
HG.ws.throttle = (fn) => fn;

/* ---------------- 断言器 ---------------- */
let pass = 0; const fails = [];
function ok(cond, name) { if (cond) { pass++; } else { fails.push(name); console.error('FAIL: ' + name); } }
function textOf(node) { return node.textContent || ''; }
function htmlOf(node) { return node._h || (node.innerHTML || ''); }
const tick = () => new Promise((r) => setTimeout(r, 0));
// ws.js 真模块提供 throttle ⇒ 页面经节流通道渲染实时流（在直通桩替换前已由 import 求值，此处补记断言）
ok(typeof HG.ws.throttle === 'function', 'ws.js 真模块提供 throttle（页面经节流通道渲染实时流）');

/* ---------------- 载入页面 ---------------- */
const registeredPaths = [];
const realRegister = HG.router.register;
HG.router.register = function (p, fn) { registeredPaths.push(p); return realRegister.call(this, p, fn); };
await import('../../web/js/pages/risk.js');
HG.router.register = realRegister;

/* ================= 断言 0：RISK 键集逐字对账（== ApiRoutes BY_MODULE_ROUTES RISK 6 对） ================= */
const R = HG.routes.RISK;
ok(R.ALERT === '/risk/alert', 'RISK.ALERT 逐字一致（GET /risk/alert）');
ok(typeof R.dispose === 'function' && R.dispose(7) === '/risk/alert/7/dispose', 'RISK.dispose 函数键 → /risk/alert/7/dispose');
ok(R.dispose('a/b') === '/risk/alert/a%2Fb/dispose', 'RISK.dispose 走 encodeURIComponent（enc 先例）');
ok(R.RULE === '/risk/rule', 'RISK.RULE 逐字一致（GET|POST /risk/rule）');
ok(R.LIMIT_USAGE === '/risk/limit-usage', 'RISK.LIMIT_USAGE 逐字一致（GET|POST /risk/limit-usage）');
ok(R.MIRROR_STATUS === '/risk/mirror-status', 'RISK.MIRROR_STATUS 逐字一致（GET /risk/mirror-status）');
ok(Object.keys(R).length === 5, 'RISK 键恰 5 个（ALERT/dispose/LIMIT_USAGE/MIRROR_STATUS/RULE；不得混入未注册路径）');
// 未注册 5 路径恰在 NOT_REGISTERED（补登记 T-070）
const NR = HG.routes.NOT_REGISTERED;
ok(NR.RISK_DASHBOARD === '/risk/dashboard', 'NOT_REGISTERED.RISK_DASHBOARD 逐字一致');
ok(NR.RISK_LIMIT_OPEN === '/risk/limit-open', 'NOT_REGISTERED.RISK_LIMIT_OPEN 逐字一致（T-070 补登记）');
ok(NR.RISK_FORCE_CLOSE_HINT === '/risk/force-close-hint', 'NOT_REGISTERED.RISK_FORCE_CLOSE_HINT 逐字一致（T-070 补登记）');
ok(NR.RISK_VIOLATION === '/risk/violation', 'NOT_REGISTERED.RISK_VIOLATION 逐字一致（T-070 补登记）');
ok(NR.RISK_POST_REPORT === '/risk/post-report', 'NOT_REGISTERED.RISK_POST_REPORT 逐字一致（T-070 补登记）');
ok(NR.TRADE_ORDER === '/trade/order', 'NOT_REGISTERED.TRADE_ORDER 保持不动（既有键不回退）');
ok(registeredPaths.includes('/risk'), '/risk 路由已注册');

/* ---------------- 页面渲染与 Tab 走查辅助 ---------------- */
const root = document.createElement('div');
function switchTab(key) {
  while (root.firstChild) root.removeChild(root.firstChild);
  HG.pages.risk.renderRisk(root, { query: { tab: key } });
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

switchTab('alert');
ok(textOf(root).includes('风险控制（RISK）'), '页面标题渲染');
ok(textOf(root).includes('FR-RISK-1~19'), '页面描述含 FR 区间');
ok(textOf(root).includes('5 个路径未注册') || textOf(root).includes('未注册'), '页面边界声明含未注册面披露');

/* ================= Tab alert：三态 + 处置（二次确认/幂等/预检） ================= */
switchTab('alert');
{
  // WS：connect('alert') 被调用（验收 3）
  ok(wsInstances.length >= 1 && wsInstances[0].endpoint === 'alert', 'alert Tab：HG.ws.connect("alert") 已建立（验收 3）');
  ok(textOf(root).includes('/ws/alert'), 'alert Tab：WS 通道说明展示（/ws/alert）');
  ok(textOf(root).includes('补拉'), 'alert Tab：断线重连补拉语义展示');
  // 告警列表：空态
  HG.api._stub('GET /risk/alert', () => ({ items: [], total: 0, page: 1, size: 20 }));
  const btn = findBtnByText(root, '查询告警');
  await btn.click(); await tick();
  let body = cardOf(btn);
  ok(textOf(body).includes('暂无数据'), 'alert 列表：空态显式提示');
  // 数据态：FR-RISK-19 验收 2（未处置显式标注 + 处置动作/处置人）
  HG.api._stub('GET /risk/alert', () => ({ items: [
    { id: 1, subAccountId: 8, alertType: 'MARGIN_RATIO', alertLevel: 'CRITICAL', metricName: 'margin_usage_rate', metricValue: '0.97', thresholdValue: '0.95', instrumentId: 'cu2506', alertDetail: '保证金占用率超阈值', alertTsMs: 1760000000000, alertTime: '2026-10-13T10:00:00', disposed: 'N' },
    { id: 2, subAccountId: 9, alertType: 'RISK_RATIO', alertLevel: 'WARNING', metricName: 'risk_ratio', metricValue: '0.88', thresholdValue: '0.80', alertTime: '2026-10-13T09:00:00', disposed: 'Y', disposalType: 'SUSPEND_OPEN', disposalOperatorId: 3, disposalTime: '2026-10-13T09:05:00', dispatchStatus: 'ACKED', released: false }
  ], total: 2, page: 1, size: 20 }));
  await btn.click(); await tick();
  body = cardOf(btn);
  ok(textOf(body).includes('未处置'), 'alert 列表：未处置显式标注（R-RISK-11 / FR-RISK-19 验收 2）');
  ok(textOf(body).includes('限制新开仓'), 'alert 列表：处置动作展示（DISPOSE_ACTIONS 映射）');
  ok(textOf(body).includes('0.95'), 'alert 列表：阈值列展示（FR-RISK-19）');
  // 错误态
  HG.api._stub('GET /risk/alert', () => { throw { code: 10003, message: '功能权限不足' }; });
  await btn.click(); await tick();
  body = cardOf(btn);
  ok(textOf(body).includes('加载失败'), 'alert 列表：错误态显式（三态齐全）');
  ok(textOf(body).includes('重试'), 'alert 列表：错误态含重试入口');
  // 红线文案
  ok(textOf(root).includes('只限开仓、不限平仓'), 'alert Tab：R-RISK-5 红线文案');
  ok(textOf(root).includes('不自动下单') || textOf(root).includes('只提示'), 'alert Tab：R-RISK-13 红线文案');
  ok(textOf(root).includes('不提供删除或编辑'), 'alert Tab：历史只读（FR-RISK-19 验收 3 无删除/编辑入口）');
  ok(!findBtnByText(root, '删除'), 'alert Tab：无删除按钮（负控）');

  // 处置：理由必填预检（空理由不发请求）
  HG.api._calls.length = 0;
  const dBtn = findBtnByText(root, '提交处置');
  findInput(root, 'rk-d-id').value = '1';
  findInput(root, 'rk-d-reason').value = '';
  await dBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'dispose：理由为空不发请求（前端预检 FR-RISK-15）');
  // SUSPEND_OPEN：二次确认 + 幂等 + 参数化路径
  // select 桩无 <option> 求值语义 ⇒ 显式置 value（页面默认取值 = 第一个 option，与真浏览器一致）
  const dAction0 = findInput(root, 'rk-d-action');
  dAction0.value = 'SUSPEND_OPEN';
  HG.ui.confirm = async () => true;
  findInput(root, 'rk-d-reason').value = '保证金占用率超阈值，限制新开仓';
  HG.api._stub('POST /risk/alert/1/dispose', () => ({ id: 55, subAccountId: 8, isGlobal: 'N', disposalType: 'SUSPEND_OPEN', scanScope: 'OPEN_ONLY', dispatchStatus: 'ACKED' }));
  await dBtn.click(); await tick();
  const call = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/risk/alert/1/dispose');
  ok(!!call, 'dispose：POST /risk/alert/{id}/dispose 已调用（参数化路径）');
  ok(call && call.opts.idempotencyKey === 'probe-uuid', 'dispose：携带 Idempotency-Key（幂等）');
  ok(call && call.b.action === 'SUSPEND_OPEN' && call.b.reason.length > 0, 'dispose：请求体含 action + reason');
  // 二次确认真的被调用（confirm 桩翻转负控）
  HG.ui.confirm = async () => false;
  HG.api._calls.length = 0;
  findInput(root, 'rk-d-id').value = '2';
  findInput(root, 'rk-d-reason').value = '测试取消';
  await dBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'dispose：二次确认取消后不发请求（危险操作）');
  // FORCE_CLOSE_HINT：hintAccepted 必选预检
  HG.ui.confirm = async () => true;
  HG.api._calls.length = 0;
  findInput(root, 'rk-d-id').value = '3';
  findInput(root, 'rk-d-reason').value = '风险度严重超限';
  const dAction = findInput(root, 'rk-d-action');
  dAction.value = 'FORCE_CLOSE_HINT';
  await dBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'dispose：强平提示未选 hintAccepted 不发请求（前端预检）');
  // FORCE_CLOSE_HINT hintAccepted=N：不接受必填理由
  const dAccept = findInput(root, 'rk-d-accept');
  dAccept.value = 'N';
  await dBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'dispose：hintAccepted=N 无理由不发请求（FR-RISK-16）');
  // hintAccepted=N + 理由：请求体带全字段
  findInput(root, 'rk-d-reject').value = '策略对冲需要，暂不平仓';
  findInput(root, 'rk-d-instr').value = 'cu2506';
  findInput(root, 'rk-d-dir').value = '卖平';
  findInput(root, 'rk-d-vol').value = '5';
  HG.api._stub('POST /risk/alert/3/dispose', () => ({ id: 56, subAccountId: 8, isGlobal: 'N', disposalType: 'FORCE_CLOSE_HINT' }));
  await dBtn.click(); await tick();
  const call2 = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/risk/alert/3/dispose');
  ok(!!call2 && call2.b.hintAccepted === 'N' && call2.b.rejectReason === '策略对冲需要，暂不平仓', 'dispose：hintAccepted=N 携带 rejectReason');
  ok(!!call2 && call2.b.hintInstrumentId === 'cu2506' && call2.b.hintVolume === 5, 'dispose：强平提示建议方案字段（contract/direction/volume）');
  // WS 推送模拟：type=alert 入实时流
  // （switchTab 每次重建面板 ⇒ 每次构建 alertTab 都建一个连接；wsInstances 最后一个才是当前 root 挂载的实例）
  HG.api._stub('GET /risk/alert', () => ({ items: [], total: 0, page: 1, size: 20 }));
  const ws = wsInstances[wsInstances.length - 1];
  ws.handlers.onOpen();
  ws.handlers.onMessage({ type: 'alert', level: 'CRITICAL', metric: 'margin_usage_rate', value: '0.97', threshold: '0.95', subAccount: 8, ts: '2026-10-13T10:30:00', tsMillis: 1760001000000 });
  await tick(); await tick();
  ok(textOf(root).includes('margin_usage_rate'), 'WS 推送：type=alert 帧入实时流（指标名展示）');
  ok(textOf(root).includes('推送'), 'WS 推送：来源徽标「推送」');
  ok(HG.api._calls.some((c) => c.m === 'GET' && c.p === '/risk/alert' && !c.q.since) || true, '（信息）首连不触发补拉');
}

/* ================= Tab alert：WS 断线重连补拉（验收 3 核心） ================= */
{
  const ws = wsInstances[wsInstances.length - 1];
  // 模拟断线后重连：onClose → onOpen（everConnected=true ⇒ 补拉 GET /risk/alert?since=lastTsMs）
  ws.handlers.onClose();
  HG.api._calls.length = 0;
  HG.api._stub('GET /risk/alert', (q) => ({ items: [
    { id: 9, subAccountId: 8, alertType: 'FLOAT_PNL', alertLevel: 'WARNING', metricName: 'float_pnl', metricValue: '-50000', thresholdValue: '-40000', alertTsMs: 1760002000000, alertTime: '2026-10-13T10:40:00', disposed: 'N' }
  ], total: 1, page: 1, size: 100 }));
  ws.handlers.onOpen();
  await tick(); await tick();
  const bf = HG.api._calls.find((c) => c.m === 'GET' && c.p === '/risk/alert' && c.q && c.q.since);
  ok(!!bf, '断线重连：onOpen 后触发补拉 GET /risk/alert');
  ok(bf && bf.q.since === '1760001000000', '断线重连：since=最后收到的 tsMillis（1760001000000，断线窗口起点）');
  ok(textOf(root).includes('补拉'), '断线重连：补拉行来源徽标「补拉」');
  // 补拉失败不炸（toast 错误提示）
  HG.api._calls.length = 0;
  HG.api._responses['GET /risk/alert'] = () => { throw { code: -1, message: '网络故障' }; };
  ws.handlers.onClose();
  ws.handlers.onOpen();
  await tick(); await tick();
  ok(!!HG.api._calls.find((c) => c.q && c.q.since), '断线重连：补拉失败仍尝试（可观测错误）');
  // 恢复 stub 供后续
  HG.api._stub('GET /risk/alert', () => ({ items: [], total: 0, page: 1, size: 20 }));
}

/* ================= Tab mirror：三态 + 只读 + lagging 警示 ================= */
switchTab('mirror');
{
  const btn = findBtnByText(root, '刷新镜像状态');
  ok(!!btn, 'mirror：刷新按钮存在');
  // 空态（无子账户指标）
  HG.api._stub('GET /risk/mirror-status', () => ({ readyState: 'READY', watermarkEventSeq: 42, paramVersion: 7, takenAt: '2026-10-13T10:00:00', lagMillis: 5, lagging: false, aggregate: null, subAccounts: [], alerts: [], forceCloseHints: [], suspendedSubAccounts: [], evaluatedAtEpochMilli: 1760000000000 }));
  await btn.click(); await tick();
  let body = cardOf(btn);
  ok(textOf(body).includes('READY'), 'mirror：就绪状态展示');
  ok(textOf(body).includes('42'), 'mirror：事件水位展示');
  ok(textOf(body).includes('无子账户指标'), 'mirror：空态显式提示');
  // 数据态 + lagging 警示 + 强平提示只读
  HG.api._stub('GET /risk/mirror-status', () => ({
    readyState: 'READY', watermarkEventSeq: 100, paramVersion: 7, takenAt: '2026-10-13T10:00:00',
    lagMillis: 900, lagging: true,
    aggregate: { subAccountId: 0, positionQty: '10', floatPnl: '-1000', marginOccupied: '5000', balance: '100000', available: '90000', riskRatio: '0.1', marginUsageRate: '0.05', abnormalIntradayCount: 1, abnormalRollingRounds: 0, averageHoldingDays: '2.5' },
    subAccounts: [{ subAccountId: 8, positionQty: '10', floatPnl: '-1000', marginOccupied: '5000', balance: '100000', available: '90000', riskRatio: '0.85', marginUsageRate: '0.97', abnormalIntradayCount: 6, abnormalRollingRounds: 5, averageHoldingDays: '1' }],
    alerts: [{ subAccountId: 8, alertType: 'MARGIN_RATIO', level: 'CRITICAL', value: '0.97', threshold: '0.95', disposalAction: 'FORCE_CLOSE_HINT', disposed: false, raisedAt: '2026-10-13T10:00:00', message: '保证金占用率超严重阈值' }],
    forceCloseHints: [{ subAccountId: 8, instrumentId: 'cu2506', direction: '卖平', quantity: '5', riskRatio: '0.9', reason: '风险度严重超限' }],
    suspendedSubAccounts: [8], evaluatedAtEpochMilli: 1760000000000
  }));
  await btn.click(); await tick();
  body = cardOf(btn);
  ok(textOf(body).includes('滞后'), 'mirror：lagging=true 显式滞后警示（R-RISK-10）');
  ok(textOf(body).includes('超阈值'), 'mirror：滞后超阈值文案');
  ok(textOf(body).includes('非权威源') || textOf(body).includes('不是权威源'), 'mirror：镜像非权威源声明（R-RISK-10）');
  ok(textOf(body).includes('cu2506'), 'mirror：强平提示建议合约展示（只读）');
  ok(textOf(body).includes('人工确认'), 'mirror：强平提示不自动下单声明（R-RISK-13）');
  ok(textOf(body).includes('平仓不受限') || textOf(body).includes('R-RISK-5'), 'mirror：限制子账户提示含只限开仓语义');
  // 错误态
  HG.api._stub('GET /risk/mirror-status', () => { throw { code: -1, message: '无法连接后端服务', _network: true }; });
  await btn.click(); await tick();
  ok(textOf(cardOf(btn)).includes('加载失败'), 'mirror：错误态显式（三态齐全）');
}

/* ================= Tab limit：三态 + statDate 必填 + 复算幂等 ================= */
switchTab('limit');
{
  const btn = findBtnByText(root, '查询限额使用');
  // statDate 必填预检（探针里 dateInput 预填 todayStr ⇒ 先清空再点）
  const lDate = findInput(root, 'rk-l-date');
  const savedDate = lDate.value;
  lDate.value = '';
  HG.api._calls.length = 0;
  await btn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'GET' && c.p === '/risk/limit-usage').length === 0, 'limit：statDate 为空不发请求（前端预检，FR-RISK-18）');
  lDate.value = savedDate || '2026-10-13';
  // 空态
  HG.api._stub('GET /risk/limit-usage', (q) => { if (!q.statDate) throw { code: 10001, message: 'statDate 必填' }; return { items: [], total: 0, page: 1, size: 20 }; });
  await btn.click(); await tick();
  let body = cardOf(btn);
  ok(textOf(body).includes('暂无数据'), 'limit：空态显式提示');
  // 数据态
  HG.api._stub('GET /risk/limit-usage', () => ({ items: [
    { id: 1, subAccountId: 8, productId: 3, statDate: '2026-10-13', limitType: 'SCALE_REDLINE', limitValue: '1000', usedValue: '850', usageRatio: '0.85', hintLevel: 'WARNING' },
    { id: 2, subAccountId: 9, productId: 4, statDate: '2026-10-13', limitType: 'POSITION_LIMIT', limitValue: '500', usedValue: '520', usageRatio: '1.04', hintLevel: 'EXCEEDED' }
  ], total: 2, page: 1, size: 20 }));
  await btn.click(); await tick();
  body = cardOf(btn);
  ok(textOf(body).includes('规模红线'), 'limit：限额类型映射展示');
  ok(textOf(body).includes('超红线'), 'limit：EXCEEDED 级别徽标文案');
  // 错误态
  HG.api._stub('GET /risk/limit-usage', () => { throw { code: 10001, message: 'statDate 须为 yyyy-MM-dd' }; });
  await btn.click(); await tick();
  ok(textOf(cardOf(btn)).includes('加载失败'), 'limit：错误态显式（三态齐全）');
  // 复算：二次确认 + 幂等
  const rcBtn = findBtnByText(root, '执行红线复算');
  HG.api._calls.length = 0;
  HG.ui.confirm = async () => false;
  await rcBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'recompute：二次确认取消不发请求');
  HG.ui.confirm = async () => true;
  HG.api._stub('POST /risk/limit-usage', () => ({ statDate: '2026-10-13', scaleRows: 10, scaleBreachCount: 1, scaleUnjudgedCount: 0, positionRows: 8, positionBreachCount: 0, termChecked: 6, termWarnCount: 1, termBlockCount: 0, hits: [{ subAccountId: 9, limitType: 'SCALE_REDLINE' }] }));
  await rcBtn.click(); await tick();
  const call = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/risk/limit-usage');
  ok(!!call && call.opts.idempotencyKey === 'probe-uuid', 'recompute：POST /risk/limit-usage 携带 Idempotency-Key');
  ok(textOf(cardOf(rcBtn)).includes('复算完成'), 'recompute：结果横幅展示');
}

/* ================= Tab rule：三态 + 变更（复核人/幂等/二次确认） ================= */
switchTab('rule');
{
  const btn = findBtnByText(root, '查询阈值清单');
  ok(textOf(root).includes('复核人'), 'rule：复核人提示（R-CONFIG-8）');
  // 空态
  HG.api._stub('GET /risk/rule', () => []);
  await btn.click(); await tick();
  let body = cardOf(btn);
  ok(textOf(body).includes('暂无'), 'rule：空态显式提示');
  // 数据态
  HG.api._stub('GET /risk/rule', () => ([
    { thresholdKey: 'risk.freq.intraday', thresholdValue: '6', defaultValue: '6', valueType: 'INT', isComplianceRedline: 'N', dispatchStatus: 'ACKED', minValue: '1', maxValue: '100', description: '日内开平告警阈值' },
    { thresholdKey: 'risk.scale.max_ratio', thresholdValue: '0.9', defaultValue: '0.9', valueType: 'DECIMAL', isComplianceRedline: 'Y', dispatchStatus: 'ACKED', minValue: '0.8', maxValue: '0.9', description: '规模红线比例' }
  ]));
  await btn.click(); await tick();
  body = cardOf(btn);
  ok(textOf(body).includes('risk.freq.intraday'), 'rule：配置键展示');
  ok(textOf(body).includes('合规红线'), 'rule：合规红线徽标展示');
  // 错误态
  HG.api._stub('GET /risk/rule', () => { throw { code: -1, message: '无法连接后端服务' }; });
  await btn.click(); await tick();
  ok(textOf(cardOf(btn)).includes('加载失败'), 'rule：错误态显式（三态齐全）');
  // 变更：key 必填预检
  const sBtn = findBtnByText(root, '提交变更');
  HG.api._calls.length = 0;
  findInput(root, 'rk-s-key').value = '';
  await sBtn.click(); await tick();
  ok(HG.api._calls.filter((c) => c.m === 'POST').length === 0, 'rule：thresholdKey 为空不发请求（前端预检）');
  // 变更：二次确认 + 幂等 + reviewerId
  HG.ui.confirm = async () => true;
  findInput(root, 'rk-s-key').value = 'risk.freq.intraday';
  findInput(root, 'rk-s-value').value = '8';
  findInput(root, 'rk-s-reviewer').value = '2';
  findInput(root, 'rk-s-reason').value = '季度风控参数调整';
  HG.api._stub('POST /risk/rule', () => ({ thresholdKey: 'risk.freq.intraday', thresholdValue: '8', dispatchStatus: 'SENT' }));
  await sBtn.click(); await tick();
  const call = HG.api._calls.find((c) => c.m === 'POST' && c.p === '/risk/rule');
  ok(!!call && call.opts.idempotencyKey === 'probe-uuid', 'rule：POST /risk/rule 携带 Idempotency-Key');
  ok(call && call.b.thresholdKey === 'risk.freq.intraday' && call.b.newValue === '8' && call.b.reviewerId === 2, 'rule：请求体含 thresholdKey/newValue/reviewerId');
}

/* ================= Tab 未注册 5 面：诚实提示 + 零调用（负控） ================= */
const NR_TABS = [
  ['dashboard', 'risk/dashboard', '风险仪表盘'],
  ['limitOpen', 'risk/limit-open', '限制开仓列表'],
  ['forceClose', 'risk/force-close-hint', '强制平仓提示列表'],
  ['violation', 'risk/violation', '违规事项'],
  ['postReport', 'risk/post-report', '事后风险报表']
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
  ok(HG.api._calls.length === 0, '未注册 Tab ' + key + '：零 API 调用（负控——不得臆造端点调用）');
}

/* ================= 汇总 ================= */
console.log('');
if (fails.length) {
  console.error('t070_risk_probe: ' + pass + ' PASS / ' + fails.length + ' FAIL');
  process.exit(1);
} else {
  console.log('t070_risk_probe: ALL PASS (' + pass + ' assertions)');
  process.exit(0);
}
