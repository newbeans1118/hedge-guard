/*
 * T-071 页面探针（无头桩，非真实浏览器）—— 走查 audit.js 各功能区渲染。
 *
 * 形态对齐 T-021 先例（96/0）：node 级、mock DOM + mock HG.core + mock HG.api，
 * 逐功能点断言渲染结果（加载/空/错误三态、断点两态、CSV 成功/JSON 失败两态、
 * 11 路径对账、R-AUDIT-2 红线、10017/10018 阻断告警）。
 *
 * 用法：node tools/probes/t071_audit_probe.mjs   → 全过打印 PASS 计数，退出码 0；任一失败退出码 1。
 *
 * ⚠️ ESM 静态 import 会先于任何语句求值（hoisting），故先以 `--import` 预置 window/document
 * 全局桩（见 t071_probe_globals.mjs），再动态 import 全部模块。
 */
await import('./t071_probe_globals.mjs');
const { HG } = await import('../../web/js/core/ns.js');
/* ---------------- DOM 桩（复用 globals 模块的 document；登记表见页面装配段） ---------------- */
const documentStub = globalThis.document;
// 页面运行时经 document.getElementById 读回控件（如筛查维度 checkbox）：探针以 registry 兜底
const origGetById = documentStub.getElementById.bind(documentStub);
documentStub.getElementById = (id) => origGetById(id) || (refreshRegistry(), registry[id] || null);
// id 登记表：探针用（refreshRegistry 见页面装配段）
const registry = {};

/* ---------------- HG 核心桩（在 import 页面前就位） ---------------- */
HG.format = {
  decimal: (v) => (v === null || v === undefined || v === '' ? '' : String(v)),
  fixed: (v) => String(v ?? ''), money: (v) => String(v ?? ''), qty: (v) => String(v ?? ''),
  pct: (v) => String(v ?? ''),
  date: (v) => (v ? String(v).slice(0, 10) : ''),
  dateTime: (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : ''),
  esc: (v) => String(v ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'),
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
  newIdempotencyKey: () => 'test-key',
  _responses: {},
  _calls: [],
  _stub(key, fn) { this._responses[key] = fn; },
  _route(method, path) { return method + ' ' + path; },
  async get(path, query) {
    this._calls.push({ m: 'GET', p: path, q: query });
    const k = 'GET ' + path;
    if (!(k in this._responses)) throw { code: -1, message: '探针未 stub：' + k };
    return this._responses[k](query || {});
  },
  async post(path, body) {
    this._calls.push({ m: 'POST', p: path, b: body });
    const k = 'POST ' + path;
    if (!(k in this._responses)) throw { code: -1, message: '探针未 stub：' + k };
    return this._responses[k](body || {});
  },
  async put() { throw { code: -1, message: 'audit 页不应调用 put' }; },
  async del() { throw { code: -1, message: 'audit 页不应调用 del' }; },
  async list() { throw { code: -1, message: 'audit 页不应调用 list' }; },
  async download() { throw { code: -1, message: 'audit 页不应调用 download（evidence POST 走页面内 fetch 桩）' }; }
};
HG.router = {
  _routes: {},
  register(p, fn) { this._routes[p] = fn; },
  navigate() {},
  start() {}, setNotFound() {}, currentPath() { return '/audit'; }
};
HG.ui = null; // 由 ui.js 提供——但探针不引 DOM 依赖的真实 ui.js（其 import format.js 亦为 HG 挂载）。
// 实际做法：直接 import 真实 ui.js/format.js/errmsg.js？→ 真实 ui.js 无 DOM 顶层执行，仅函数定义 + HG 挂载，可在桩 document 下加载。
await import('../../web/js/core/format.js');
await import('../../web/js/core/errmsg.js');
await import('../../web/js/core/router.js'); // 先载真实 router（会整体替换 HG.router），随后包装 register
globalThis.URL = { createObjectURL: () => 'blob:probe', revokeObjectURL: () => {} };
globalThis.Blob = class { constructor(parts, opts) { this.parts = parts; this.type = (opts && opts.type) || ''; this._t = (parts || []).join(''); } async text() { return this._t; } };
globalThis.fetch = async () => { throw new Error('fetch 应由探针逐例覆盖'); };
await import('../../web/js/core/ui.js');
await import('../../web/js/core/routes.js');

/* ---------------- 断言器 ---------------- */
let pass = 0; const fails = [];
function ok(cond, name) { if (cond) { pass++; } else { fails.push(name); console.error('FAIL: ' + name); } }
function htmlOf(node) { return node._h || ''; }
function textOf(node) { return node.textContent || ''; }

/* ---------------- 载入页面 ---------------- */
// 真实 router.js 用模块内闭包数组注册（HG.router.register 可包装记录）。
const registeredPaths = [];
const realRegister = HG.router.register;
HG.router.register = function (p, fn) { registeredPaths.push(p); return realRegister.call(this, p, fn); };
await import('../../web/js/pages/audit.js');
HG.router.register = realRegister;
const routes = HG.routes.AUDIT;
const R = routes;

/* ================= 断言 0：AUDIT 键 13 路径对账（== ApiRoutes.java AUDIT 模块） ================= */
const EXPECT = {
  TRACE_FORWARD: '/audit/trace/forward', TRACE_BACKWARD: '/audit/trace/backward',
  TRACE_INTEGRITY: '/audit/trace/integrity', VIOLATION_SCREEN: '/audit/violation-screen',
  REPORT_QUARTERLY: '/audit/report/quarterly', REPORT_ANNUAL: '/audit/report/annual',
  REPORT_MAJOR_EVENT: '/audit/report/major-event', EVIDENCE_EXPORT: '/audit/evidence/export',
  LOG: '/audit/log', LOG_VERIFY: '/audit/log/verify', OPERATION_LOG: '/audit/operation-log',
  CHECK_TASKS: '/audit/check-tasks', CHECK_TASKS_OVERDUE: '/audit/check-tasks/overdue'
};
ok(JSON.stringify(routes) === JSON.stringify(EXPECT), 'AUDIT 键 13 路径逐字对齐 ApiRoutes（含 T-194 追加 2 键）');

/* ================= 页面渲染 + Tab 走查 ================= */
const root = document.createElement('div');
HG.pages.audit.renderAudit(root, { query: {} });
ok(registeredPaths.includes('/audit'), '/audit 路由已注册');
ok(textOf(root).includes('审计（AUDIT）'), '页面标题渲染');
ok(textOf(root).includes('R-AUDIT-2'), '页面级红线声明含 R-AUDIT-2');

// id 注册表：页面每次重建 Tab 面板后重建登记（探针自有 registry + 整树扫描）
function refreshRegistry() {
  Object.keys(registry).forEach((k) => delete registry[k]);
  (function walk(n) {
    if (!n || !n.children) return;
    if (n.attrs && n.attrs.id) registry[n.attrs.id] = n;
    n.children.forEach(walk);
  })(root);
}
function ctl(id) {
  refreshRegistry();
  const n = registry[id];
  if (!n) throw new Error('探针找不到控件 #' + id);
  return n;
}

// Tab 切换：页面经 HG.router.navigate 换 URL 后由 router 重渲染；探针无 router，直接以 tab 参数重渲染。
function switchTab(key) {
  while (root.firstChild) root.removeChild(root.firstChild);
  HG.pages.audit.renderAudit(root, { query: { tab: key } });
}

/* ================= Tab trace：断点两态（R-AUDIT-2 核心红线） ================= */
switchTab('trace');
{
  const calls0 = HG.api._calls.length;
  // —— 正向追溯：有断点
  HG.api._stub('GET ' + R.TRACE_FORWARD, () => ({
    startType: 'EXPOSURE', startId: '1001', chainIntegrity: 'HAS_BREAK',
    nodes: [{ segment: 'EXPOSURE', objectType: 'EXPOSURE', objectId: '1001', status: 'ACTIVE', subAccountId: 8, detail: {} }],
    breakList: [{ kind: 'MISSING', position: 'SETTLE', objectType: 'SETTLE_CYCLE', objectId: '77', objectNo: 'SC-77', reason: '结算环节缺失' }],
    alerts: ['链上存在断点，已告警'], visibleSubAccounts: [8], permissionLimited: true, note: '测试注记'
  }));
  ctl('ad-f-exposure').value = '1001';
  const fBtn = findBtnByText(root, '正向追溯'); 
const CB1 = () => cardOf(fBtn);

  await fBtn.click();
  await tick();
  const fBody = CB1();
  const html = textOf(fBody);
  if (process.env.DBG) console.log('[DBG] fBody kids=', fBody.children.length, 'textHead=', JSON.stringify(html.slice(0,300)));
  ok(html.includes('HAS_BREAK'), 'trace/forward：断点徽标含 HAS_BREAK');
  ok(html.includes('链条不完整'), 'trace/forward：断点存在时输出「链条不完整」（R-AUDIT-2）');
  ok(!html.includes('链条完整（COMPLETE）'), 'trace/forward：有断点时禁用「链条完整（COMPLETE）」字样');
  ok(html.includes('结算环节缺失'), 'trace/forward：断点原因显式列出');
  ok(html.includes('已告警'), 'trace/forward：alerts 显式告警横幅');
  ok(html.includes('权限受限'), 'trace/forward：permissionLimited 明示权限边界（TC-AUDIT-16）');
  ok(HG.api._calls.length === calls0 + 1 && HG.api._calls[calls0].p === '/audit/trace/forward', 'trace/forward 调 GET /audit/trace/forward');
}
{
  // —— 完整性：无断点（COMPLETE）
  HG.api._stub('GET ' + R.TRACE_INTEGRITY, () => ({
    startType: 'EXPOSURE', startId: '1002', chainIntegrity: 'COMPLETE',
    nodes: [], breakList: [], alerts: [], visibleSubAccounts: [8], permissionLimited: false
  }));
  ctl('ad-i-exposure').value = '1002';
  const iBtn = findBtnByText(root, '校验完整性'); 
const CB2 = () => cardOf(iBtn);

  await iBtn.click(); await tick();
  const html = textOf(CB2());
  ok(html.includes('链条完整（COMPLETE）'), 'trace/integrity：无断点时输出「链条完整（COMPLETE）」');
  ok(html.includes('无断点记录'), 'trace/integrity：无断点时断点清单空态');
}
{
  // —— 反向追溯：IN_FLIGHT
  HG.api._stub('GET ' + R.TRACE_BACKWARD, () => ({
    chainIntegrity: 'IN_FLIGHT', nodes: [], breakList: [], alerts: []
  }));
  ctl('ad-b-id').value = 'V-2026-001';
  const bBtn = findBtnByText(root, '反向追溯'); 
const CB3 = () => cardOf(bBtn);

  await bBtn.click(); await tick();
  const html = textOf(CB3());
  ok(html.includes('IN_FLIGHT'), 'trace/backward：IN_FLIGHT 不计断点且不称完整');
  ok(!html.includes('链条完整（COMPLETE）'), 'trace/backward：IN_FLIGHT 时禁「完整」表述');
}
{
  // —— 错误态
  HG.api._stub('GET ' + R.TRACE_FORWARD, () => { throw { code: 10003, message: '功能权限不足' }; });
  ctl('ad-f-exposure').value = '9999';
  const fBtn = findBtnByText(root, '正向追溯'); 
const CB4 = () => cardOf(fBtn);

  await fBtn.click(); await tick();
  const html = textOf(CB4()) + htmlOf(CB4());
  ok(html.includes('10003'), 'trace：错误态渲染错误码（10003）');
}

/* ================= Tab violation：筛查 + 命中清单 ================= */
switchTab('violation');
{
  ctl('ad-v-from').value = '2026-09-01';
  ctl('ad-v-to').value = '2026-09-30';
  ctl('ad-v-dim-REASON_MISSING').checked = true;
  HG.api._stub('POST ' + R.VIOLATION_SCREEN, (b) => {
    return {
      run: { id: 1, screenNo: 'VS-20260930-0001', dateFrom: b.dateFrom, dateTo: b.dateTo, ruleVersion: 'v1', hitCount: 1, conclusion: '发现违规' },
      items: [{ id: 5, subAccountId: 8, violationType: 'REASON_MISSING', severity: 'HIGH', ruleId: 'R1', objectType: 'TRADE_ORDER', objectId: '9', occurTime: '2026-09-30T10:00:00', violationDesc: '缺理由', tradeReasonId: null, relatedObjectInfo: null }],
      hitCount: 1, permissionLimited: false, note: '注记', visibleSubAccounts: [8]
    };
  });
  const runBtn = findBtnByText(root, '执行筛查'); 
const CB5 = () => cardOf(runBtn);

  await runBtn.click(); await tick();
  const body = CB5();
  const html = textOf(body) + htmlOf(body);
  ok(html.includes('VS-20260930-0001'), 'violation：批次号摘要渲染');
  ok(html.includes('交易理由缺失'), 'violation：维度中文名映射（REASON_MISSING）');
  ok(html.includes('缺理由'), 'violation：命中清单渲染');
  const call = HG.api._calls[HG.api._calls.length - 1];
  ok(call.p === '/audit/violation-screen' && call.m === 'POST' && call.b.dimensions && call.b.dimensions.length === 1, 'violation：POST 请求体含 dimensions');
}

/* ================= Tab report：三报表生成 + 断点报表 + 只读签署 ================= */
switchTab('report');
{
  HG.api._stub('POST ' + R.REPORT_QUARTERLY, (b) => {
    if (b.periodKey !== '2026-Q3') throw { code: 10001, message: 'periodKey 必填' };
    return { reportNo: 'AR-Q-2026-Q3', reportType: 'QUARTERLY', periodKey: '2026-Q3', chainIntegrity: 'HAS_BREAK', breakList: [{ kind: 'MISSING', position: 'SETTLE', reason: '缺口' }], dataGapDesc: '缺 9 月上旬数据', submitted: false, contentJson: '{"k":"v"}' };
  });
  ctl('ad-q-period').value = '2026-Q3';
  const genBtn = findBtnByText(root, '生成季度报表'); 
const CB6 = () => cardOf(genBtn);

  await genBtn.click(); await tick();
  const html = textOf(CB6()) + htmlOf(CB6());
  ok(html.includes('AR-Q-2026-Q3'), 'report：季度报表编号渲染');
  ok(html.includes('链条不完整'), 'report：断点存在时报表区输出「链条不完整」（R-AUDIT-2）');
  ok(html.includes('缺口'), 'report：数据缺口说明渲染');
  ctl('ad-q-no').value = 'AR-Q-2026-Q3';
}
{
  // 年度报告（FR-AUDIT-13 全覆盖摘要）
  HG.api._stub('POST ' + R.REPORT_ANNUAL, () => ({ reportNo: 'AR-A-2026', reportType: 'ANNUAL', periodKey: '2026', chainIntegrity: 'COMPLETE', breakList: [], contentJson: '{"checkTaskCoverage":{"byType":{"MONTHLY":{"total":12,"signed":12}}}}' }));
  ctl('ad-a-year').value = '2026';
  const aGenBtn = findBtnByText(root, '生成年度报告'); 
const CB7 = () => cardOf(aGenBtn);
 await aGenBtn.click(); await tick();
  const html = textOf(CB7()) + htmlOf(CB7());
  ok(html.includes('AR-A-2026'), 'report：年度报告渲染（checkTaskCoverage 承载于 contentJson）');
  ok(html.includes('checkTaskCoverage'), 'report：年度报告内容含 checkTaskCoverage（FR-AUDIT-13 承载）');
}
{
  // 重大事项
  HG.api._stub('POST ' + R.REPORT_MAJOR_EVENT, (b) => {
    if (!b.eventType) throw { code: 10001, message: '事项类型必填' };
    return { reportNo: 'AR-M-001', reportType: 'MAJOR_EVENT', chainIntegrity: 'UNKNOWN', breakList: [] };
  });
  ctl('ad-m-type').value = 'LOSS_THRESHOLD';
  const mGenBtn = findBtnByText(root, '生成重大事项报告'); 
const CB8 = () => cardOf(mGenBtn);
 await mGenBtn.click(); await tick();
  ok((textOf(CB8()) + htmlOf(CB8())).includes('AR-M-001'), 'report：重大事项报告渲染');
}
{
  // 导出 10017 阻断告警（FR-AUDIT-12）
  HG.api._stub('GET ' + R.REPORT_QUARTERLY, () => { throw { code: 10017, message: '审计日志写入失败' }; });
  // 页面 exportReport 走 HG.api.download（txt GET）——探针里 download 被禁止，改为验证错误路径由页面 exportReport 抛出。
  // 注意：真实实现 exportReport 用 HG.api.download；此处 stub download 允许专用例：
  HG.api.download = async (path, q) => {
    HG.api._calls.push({ m: 'GET-DL', p: path, q });
    throw { code: 10017, message: '审计日志写入失败' };
  };
  const qExpBtn = findBtnByText(root, '导出（txt）'); 
const CB9 = () => cardOf(qExpBtn);
 await qExpBtn.click(); await tick();
  const html = textOf(CB9()) + htmlOf(CB9());
  ok(html.includes('10017'), 'report：10017 阻断告警显式呈现');
  ok(html.includes('中断'), 'report：10017 文案含「中断」（审计日志写失败须中断导出）');
}

/* ================= Tab evidence：CSV 成功 / JSON 失败 两态 ================= */
switchTab('evidence');
{
  // —— 成功：text/csv 直返 + Content-Disposition
  const csvBody = 'section,export_no\nHEADER,EV-1\n';
  let captured = null;
  globalThis.fetch = async (url, opts) => {
    captured = { url, opts };
    return {
      status: 200,
      headers: {
        get: (h) => (String(h).toLowerCase() === 'content-type' ? 'text/csv; charset=UTF-8'
          : String(h).toLowerCase() === 'content-disposition' ? 'attachment; filename="evidence-EV-20260930-0001.csv"' : null)
      },
      blob: async () => new Blob([csvBody], { type: 'text/csv' }),
      text: async () => csvBody
    };
  };
  ctl('ad-e-ids').value = '1001, 1002';
  ctl('ad-e-detail').checked = true;
  const eBtn = findBtnByText(root, '导出证据链（CSV）'); 
const CB10 = () => cardOf(eBtn);
 await eBtn.click(); await tick();
  ok(captured && captured.opts.method === 'POST', 'evidence：POST /audit/evidence/export（非 GET location）');
  ok(captured.url.includes('/api/v1/audit/evidence/export'), 'evidence：URL 逐字对齐');
  ok(captured.opts.body.includes('includeSourceDetail') && captured.opts.body.includes('true'), 'evidence：请求体含 includeSourceDetail=true');
  ok((textOf(CB10()) + htmlOf(CB10())).includes('导出成功'), 'evidence：CSV 成功态横幅');
  ok((textOf(CB10()) + htmlOf(CB10())).includes('evidence-EV-20260930-0001.csv'), 'evidence：文件名取自 Content-Disposition');
}
{
  // —— 失败：JSON 信封（10018 阻断）
  globalThis.fetch = async () => ({
    status: 422,
    headers: { get: (h) => (String(h).toLowerCase() === 'content-type' ? 'application/json; charset=UTF-8' : null) },
    text: async () => JSON.stringify({ code: 10018, message: '防篡改校验未通过', traceId: 't1' })
  });
  const eBtn = findBtnByText(root, '导出证据链（CSV）'); 
const CB11 = () => cardOf(eBtn);
 await eBtn.click(); await tick();
  const html = textOf(CB11()) + htmlOf(CB11());
  ok(html.includes('10018'), 'evidence：JSON 失败态走 10018 阻断告警');
  ok(html.includes('阻断'), 'evidence：10018 文案含「阻断」');
  ok(!html.includes('导出成功'), 'evidence：失败态禁「导出成功」');
}
{
  // —— 失败：普通业务错误（10004）走 errmsg
  globalThis.fetch = async () => ({
    status: 422,
    headers: { get: (h) => (String(h).toLowerCase() === 'content-type' ? 'application/json; charset=UTF-8' : null) },
    text: async () => JSON.stringify({ code: 10004, message: '子账户数据权限不足', traceId: 't2' })
  });
  const eBtn = findBtnByText(root, '导出证据链（CSV）'); 
const CB12 = () => cardOf(eBtn);
 await eBtn.click(); await tick();
  ok((textOf(CB12()) + htmlOf(CB12())).includes('10004'), 'evidence：普通业务错误（10004）经 errmsg 渲染');
}
{
  // —— 导出记录 GET 分页
  HG.api._stub('GET ' + R.EVIDENCE_EXPORT, (q) => {
    if (q.exportNo) return { exportNo: q.exportNo, startPointType: 'EXPOSURE', chainIntegrity: 'HAS_BREAK', breakList: [{ kind: 'X', position: 'Y', reason: 'Z' }], exportedTime: '2026-09-30T12:00:00' };
    return { items: [{ exportNo: 'EV-1', startPointType: 'EXPOSURE', chainIntegrity: 'COMPLETE', breakList: [], exportedTime: '2026-09-30T11:00:00', exportedBy: 3 }], total: 1, page: 1, size: 20 };
  });
  ctl('ad-r-no').value = '';
  const xBtn1 = findBtnByText(root, '查询记录'); 
const CB13 = () => cardOf(xBtn1);
 await xBtn1.click(); await tick();
  const html = textOf(CB13()) + htmlOf(CB13());
  ok(html.includes('EV-1'), 'evidence：导出记录分页渲染');
  ok(html.includes('链条完整（COMPLETE）'), 'evidence：记录行完整性徽标（无断点=完整）');
  ctl('ad-r-no').value = 'EV-20260930-0001';
  const xBtn2 = findBtnByText(root, '查询记录'); 
const CB14 = () => cardOf(xBtn2);
 await xBtn2.click(); await tick();
  const html2 = textOf(CB14()) + htmlOf(CB14());
  ok(html2.includes('EV-20260930-0001'), 'evidence：按 exportNo 精确查渲染');
  ok(html2.includes('链条不完整'), 'evidence：记录详情断点显式（HAS_BREAK）');
  ok(!html2.includes('链条完整（COMPLETE）'), 'evidence：记录详情有断点时禁「完整」');
}

/* ================= Tab log：日志查询 + 防篡改校验 ================= */
switchTab('log');
{
  HG.api._stub('GET ' + R.LOG, () => ({ items: [{ id: 1, actionType: 'EXPORT', auditUserId: 3, subAccountId: 8, queryCondition: '{}', resultCount: 5, exportFileId: 'F1', chainIntegrity: 'COMPLETE', actionTime: '2026-09-30T10:00:00', seqInChain: 42 }], total: 1, page: 1, size: 20 }));
  const xBtn3 = findBtnByText(root, '查询日志'); 
const CB15 = () => cardOf(xBtn3);
 await xBtn3.click(); await tick();
  const html = textOf(CB15()) + htmlOf(CB15());
  ok(html.includes('导出'), 'log：审计日志列表渲染（动作中文名）');
  ok(html.includes('42'), 'log：链内序号渲染');
  const call = HG.api._calls[HG.api._calls.length - 1];
  ok(call.p === '/audit/log', 'log：调 GET /audit/log');
}
{
  // 校验通过
  HG.api._stub('POST ' + R.LOG_VERIFY, (b) => {
    if (b.generateBatch) return { passed: true, fromSeq: 1, toSeq: 50, checkedCount: 50, verifiedBatches: 1, mismatches: [], verifiedTime: '2026-09-30T10:00:00', verifiedBy: 3, note: '防篡改校验通过', checksumBatch: { batchNo: 'B1', batchStartSeq: 1, batchEndSeq: 50, batchHeadHash: 'h', batchTailHash: 't', batchChecksum: 'c', recordCount: 50 } };
    return { passed: true, fromSeq: 1, toSeq: 50, checkedCount: 50, verifiedBatches: 1, mismatches: [], verifiedTime: '2026-09-30T10:00:00', verifiedBy: 3, note: '防篡改校验通过' };
  });
  ctl('ad-v-seq-from').value = '1';
  ctl('ad-v-seq-to').value = '50';
  ctl('ad-v-batch').checked = true;
  const xBtn4 = findBtnByText(root, '执行校验'); 
const CB16 = () => cardOf(xBtn4);
 await xBtn4.click(); await tick();
  const html = textOf(CB16()) + htmlOf(CB16());
  ok(html.includes('防篡改校验通过'), 'log/verify：通过态渲染');
  ok(html.includes('B1'), 'log/verify：新校验批次渲染');
  const call = HG.api._calls[HG.api._calls.length - 1];
  ok(call.p === '/audit/log/verify' && call.m === 'POST' && call.b.generateBatch === true, 'log/verify：POST 请求体含 generateBatch');
}
{
  // 校验未通过
  HG.api._stub('POST ' + R.LOG_VERIFY, () => ({ passed: false, fromSeq: 1, toSeq: 50, checkedCount: 50, verifiedBatches: 1, mismatches: ['seq 23 哈希不一致'], verifiedTime: '2026-09-30T10:05:00', verifiedBy: 3, note: '防篡改校验未通过：已告警并阻断后续导出' }));
  const xBtn5 = findBtnByText(root, '执行校验'); 
const CB17 = () => cardOf(xBtn5);
 await xBtn5.click(); await tick();
  const html = textOf(CB17()) + htmlOf(CB17());
  ok(html.includes('防篡改校验未通过'), 'log/verify：未通过态显式告警');
  ok(html.includes('阻断'), 'log/verify：未通过文案含「阻断后续导出」');
  ok(html.includes('seq 23 哈希不一致'), 'log/verify：不一致明细列出');
}

/* ================= Tab oplog：操作留痕 ================= */
switchTab('oplog');
{
  HG.api._stub('GET ' + R.OPERATION_LOG, () => ({ items: [{ id: 9, operatorId: 5, operationType: 'APPROVE', objectType: 'HEDGE_PLAN', objectId: 12, objectNo: 'HP-12', subAccountId: 8, actionResult: '已批准', resultDetail: '', operationTime: '2026-09-30T09:00:00', clientIp: '10.0.0.5', traceId: 'tr-1' }], total: 1, page: 1, size: 20 }));
  ctl('ad-o-type').value = 'APPROVE';
  const xBtn6 = findBtnByText(root, '查询留痕'); 
const CB18 = () => cardOf(xBtn6);
 await xBtn6.click(); await tick();
  const html = textOf(CB18()) + htmlOf(CB18());
  ok(html.includes('APPROVE') && html.includes('HP-12'), 'oplog：列表渲染（操作类型 + 对象号）');
  ok(html.includes('10.0.0.5'), 'oplog：客户端 IP 渲染');
  const call = HG.api._calls[HG.api._calls.length - 1];
  ok(call.p === '/audit/operation-log' && call.q.operationType === 'APPROVE', 'oplog：查询参数 operationType 透传');
}
{
  // 空态
  HG.api._stub('GET ' + R.OPERATION_LOG, () => ({ items: [], total: 0, page: 1, size: 20 }));
  ctl('ad-o-type').value = '';
  const xBtn7 = findBtnByText(root, '查询留痕'); 
const CB19 = () => cardOf(xBtn7);
 await xBtn7.click(); await tick();
  ok(htmlOf(CB19()).includes('暂无数据') || (textOf(CB19()) + htmlOf(CB19())).includes('无操作留痕'), 'oplog：空态渲染');
}

/* ================= Tab signoff：只读 + 无写入口 ================= */
switchTab('signoff');
{
  ctl('ad-s-year').value = '2026';
  const xBtn8 = findBtnByText(root, '查询年度全覆盖'); 
const CB20 = () => cardOf(xBtn8);
 await xBtn8.click(); await tick();
  const html = textOf(CB20()) + htmlOf(CB20());
  ok(html.includes('只读'), 'signoff：只读纪律声明渲染');
  ok(html.includes('checkTaskCoverage') || html.includes('年度专项报告'), 'signoff：指向 FR-AUDIT-6 承载（年度报告全覆盖）');
}

/* ================= 全局纪律扫描 ================= */
{
  const fs = await import('node:fs');
  const path = await import('node:path');
  const srcPath = path.resolve(path.dirname(decodeURIComponent(new globalThis.__RealURL(import.meta.url).pathname)), '../../web/js/pages/audit.js');
  const src = await fs.promises.readFile(srcPath, 'utf8');
  ok(!/localStorage|sessionStorage/.test(src.replace(/\/\*[\s\S]*?\*\/|\/\/.*/g, '')), '纪律：代码区零 localStorage/sessionStorage（口令零落盘同款）');
  ok(!/document\.cookie/.test(src), '纪律：零 cookie 写');
  ok(src.includes('10017') && src.includes('10018'), '红线：10017/10018 显式处理');
  // 11 路径全部被页面使用
  ['TRACE_FORWARD', 'TRACE_BACKWARD', 'TRACE_INTEGRITY', 'VIOLATION_SCREEN', 'REPORT_QUARTERLY', 'REPORT_ANNUAL', 'REPORT_MAJOR_EVENT', 'EVIDENCE_EXPORT', 'LOG', 'LOG_VERIFY', 'OPERATION_LOG'].forEach((k) => {
    ok(new RegExp('R\\.' + k).test(src), '路径对账：页面使用 R.' + k);
  });
}

/* ---------------- 结果 ---------------- */
console.log('\n===== T-071 探针结果 =====');
console.log('PASS: ' + pass + ' / FAIL: ' + fails.length);
if (fails.length) { fails.forEach((f) => console.error('  FAIL: ' + f)); process.exit(1); }
process.exit(0);

/* ---------------- 辅助 ---------------- */
async function tick() { await new Promise((r) => setImmediate(r)); await new Promise((r) => setImmediate(r)); await new Promise((r) => setTimeout(r, 10)); }
function findBtnByText(scope, label) {
  const found = [];
  (function walk(n) { if (!n || !n.children) return; if (n.tagName === 'BUTTON' && (n._text === label || (n.children.length === 0 && n.textContent === label))) found.push(n); n.children.forEach(walk); })(scope);
  return found[found.length - 1];
}
/** 取按钮所在 hg-card（探针语义：该按钮操作的输出容器）。 */
function cardOf(btn) {
  let n = btn;
  while (n && !String(n.className || '').includes('hg-card')) n = n.parentNode;
  return n;
}
function lastSectionBody(scope) {
  // 返回最后一个 hg-card（保守兜底；优先用 cardOf(btn)）
  const cards = [];
  (function walk(n) { if (!n || !n.children) return; if (String(n.className).includes('hg-card')) cards.push(n); n.children.forEach(walk); })(scope);
  return cards[cards.length - 1];
}
