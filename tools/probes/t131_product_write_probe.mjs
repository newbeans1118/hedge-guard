/*
 * T-131 页面探针（无头 DOM 桩，非真实浏览器）—— PRODUCT 写面接线 + 导出带筛选（OBS-T116V-1）。
 *
 * 判据（对应 T-131 验收 2 / 附加验收）：
 *   ① **导出带当前筛选**：列表页设置 productCode / productName / status / category / subAccountId 后点「导出 CSV」，
 *      HG.api.download 收到的路径须为 /product/export 且 query **逐字携带**同组筛选参数；
 *      负向对照：清空筛选后导出 ⇒ query 内**不得**出现上述键（证明断言非空转）。
 *   ② **导出空数据 10015**：服务端返回 10015（message 含「无可导出数据」）⇒ 走 ui.blockedHTML 渲染，
 *      文案含「无可导出数据」，且**不**触发下载（不生成空文件）。
 *   ③ **列表筛选服务端下推**：列表 GET /product 携带筛选参数（不再「本页内」客户端过滤）。
 *   ④ **白名单读取（FR-PRODUCT-6）**：白名单 Tab 发 GET /product/whitelist?productId=…，三态齐全
 *      （加载/空/错误+重试），行含 status 与 dispatchStatus。
 *   ⑤ **写面契约**：修改 → PUT /product/{id}（body camelCase：productName/categoryManaged/remark）；
 *      停用 → POST /product/{id}/disable {reason}；启用 → POST /product/{id}/enable（空体）；
 *      计量单位 → POST /product/{id}/unit {spotUnit, futuresUnit, convertFactor(字符串), effectiveFrom}。
 *
 * 用法：node tools/probes/t131_product_write_probe.mjs → 全过打印 PASS 计数，退出码 0；任一失败退出码 1。
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

/* ---------------- DOM 桩（对齐 t145 minimal 形态） ---------------- */
const minimalDoc = {
  createElement(tag) {
    return {
      tagName: String(tag).toUpperCase(), children: [], dataset: {}, style: {}, attrs: {}, value: '', checked: false,
      listeners: {}, parentNode: null, disabled: false,
      classList: { _s: new Set(), add(c) { this._s.add(c); }, remove(c) { this._s.delete(c); }, toggle(c, on) { on ? this._s.add(c) : this._s.delete(c); }, contains(c) { return this._s.has(c); } },
      set className(v) { this.classList._s = new Set(String(v).split(/\s+/).filter(Boolean)); },
      get className() { return Array.from(this.classList._s).join(' '); },
      set textContent(v) { this._t = String(v); this.children = []; },
      get textContent() { return (this._t || '') + (this._h || '') + this.children.map((c) => c.textContent).join(''); },
      set innerHTML(v) { this._h = String(v); this.children = []; },
      get innerHTML() { return this._h || ''; },
      setAttribute(k, v) { this.attrs[k] = String(v); if (k === 'id') this.id = String(v); },
      getAttribute(k) { return this.attrs[k] === undefined ? null : this.attrs[k]; },
      removeAttribute(k) { delete this.attrs[k]; },
      appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
      insertBefore(c, ref) { c.parentNode = this; const i = ref ? this.children.indexOf(ref) : -1; if (i === -1) this.children.push(c); else this.children.splice(i, 0, c); return c; },
      removeChild(c) { const i = this.children.indexOf(c); if (i !== -1) this.children.splice(i, 1); c.parentNode = null; return c; },
      remove() { if (this.parentNode) this.parentNode.removeChild(this); },
      addEventListener(t, fn) { (this.listeners[t] = this.listeners[t] || []).push(fn); },
      click() { (this.listeners.click || []).forEach((fn) => fn({ target: this, preventDefault() {} })); },
      reset() {},
      focus() {}, querySelector() { return null; },
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

/* ---------------- 服务端桩 ---------------- */
/** 列表/详情数据集与行为开关。 */
const DATA = {
  products: [
    { id: 7, productCode: 'CU', productName: '沪铜', category: 'COMMODITY', categoryManaged: 'Y',
      status: 'ENABLED', isDomesticExchange: 'Y', currency: 'CNY', remark: '基准品种', disabledReason: null,
      subAccountIds: [3], lastModifiedTime: '2026-11-08T10:00:00' }
  ],
  whitelistRows: [
    { id: 1, subAccountId: 3, productId: 7, categoryOverride: 'COMMODITY', status: 'ENABLED', dispatchStatus: 'PENDING', dispatchVersion: 1, disabledReason: null },
    { id: 2, subAccountId: 4, productId: 7, categoryOverride: null, status: 'DISABLED', dispatchStatus: 'PENDING', dispatchVersion: 2, disabledReason: '指标调整' }
  ],
  whitelistFail: false,
  exportError: null,
  exportText: 'product_code,product_name\nCU,沪铜\n'
};

let calls = [];
function resetCalls() { calls = []; }

HG.api = {
  BASE: '/api/v1',
  DEFAULT_PAGE_SIZE: 20,
  PAGE_SIZES: [10, 20, 50, 100],
  newIdempotencyKey: () => 'probe-uuid',
  async get(path, query) {
    calls.push({ m: 'GET', p: path, q: Object.assign({}, query || {}) });
    if (path === '/product/whitelist') {
      if (DATA.whitelistFail) throw { code: -1, message: '网络故障（探针注入）' };
      return DATA.whitelistRows.slice();
    }
    if (path === '/product/7') return Object.assign({}, DATA.products[0]);
    if (path === '/product') return { items: DATA.products.slice(), total: DATA.products.length, page: 1, size: 20 };
    return [];
  },
  async post(path, body) { calls.push({ m: 'POST', p: path, b: JSON.parse(JSON.stringify(body || {})) }); return { productId: 7 }; },
  async put(path, body) { calls.push({ m: 'PUT', p: path, b: JSON.parse(JSON.stringify(body || {})) }); return Object.assign({}, DATA.products[0]); },
  async del(path) { calls.push({ m: 'DELETE', p: path }); return {}; },
  async download(path, query) {
    calls.push({ m: 'DOWNLOAD', p: path, q: Object.assign({}, query || {}) });
    if (DATA.exportError) throw DATA.exportError;
    return { blob: { text: async () => DATA.exportText }, filename: 'products.csv' };
  }
};

HG.session = {
  state: { subAccounts: [{ id: 3, code: 'SA3', name: '子账户A' }, { id: 4, code: 'SA4', name: '子账户B' }] },
  currentId: () => 3,
  isAggregate: () => false,
  onChange() { return () => {}; }
};

/* 真 router / errmsg 模块（product.js 运行期依赖），router.register 记录 + navigate 置空。 */
await import('../../web/js/core/router.js');
await import('../../web/js/core/errmsg.js');
const REGS = {};
const _router = HG.router;
const _register = _router.register;
_router.register = function (pattern, fn) { REGS[pattern] = fn; return _register.call(_router, pattern, fn); };
const NAV = [];
_router.navigate = function (hash) { NAV.push(hash); };

/* ---------------- 断言器 ---------------- */
let pass = 0; const fails = [];
function ok(cond, name) { if (cond) { pass++; } else { fails.push(name); console.error('FAIL: ' + name); } }
const tick = () => new Promise((r) => setTimeout(r, 0));
async function settle() { await tick(); await tick(); await tick(); }

function walkAll(node) {
  const out = [];
  (function rec(n) {
    if (!n || !n.children) return;
    out.push(n);
    n.children.forEach(rec);
  })(node);
  return out;
}
function byId(node, id) { return walkAll(node).find((n) => n.attrs && n.attrs.id === id) || null; }
/** 按可见文案找**可点击**节点（先序：父容器会聚合子文案，故须要求带 click 监听）。 */
function byText(node, text) {
  return walkAll(node).find((n) => n.listeners && n.listeners.click && n.textContent === text) || null;
}
function htmlAll(node) { return walkAll(node).map((n) => (n._h || '')).join(''); }
function textOf(node) { return node.textContent || ''; }
function fire(node, type) { (node.listeners[type] || []).forEach((fn) => fn({ target: node, preventDefault() {} })); }

/* ---------------- 页面模块 ---------------- */
await import('../../web/js/pages/product.js');

ok(typeof HG.pages.product.renderList === 'function', '页面导出口在（HG.pages.product.renderList）');
ok(REGS['/product'] === HG.pages.product.renderList, '路由 /product 注册到 renderList');
ok(REGS['/product/:id'] === HG.pages.product.renderDetail, '路由 /product/:id 注册到 renderDetail');

const R = HG.routes.PRODUCT;

/* ================================================================== *
 * ① 导出带当前筛选（OBS-T116V-1 主判据）
 * ================================================================== */
{
  resetCalls();
  const root = document.createElement('div');
  HG.pages.product.renderList(root);
  await settle();

  // 列表查询本身须已带服务端筛选能力（此处无筛选 ⇒ 仅分页/排序）
  const listCall = calls.filter((c) => c.m === 'GET' && c.p === R.LIST)[0];
  ok(!!listCall, '① 列表首屏发 GET /product');

  // 设置筛选（UI 层：input 值 + change 事件）
  byId(root, 'pd-f-code').value = 'CU';
  byId(root, 'pd-f-name').value = '沪';
  byId(root, 'pd-f-status').value = 'ENABLED';
  byId(root, 'pd-f-cat').value = 'COMMODITY';
  byId(root, 'pd-f-sub').value = '3';
  fire(byId(root, 'pd-f-code'), 'change');
  fire(byId(root, 'pd-f-name'), 'change');
  fire(byId(root, 'pd-f-status'), 'change');
  fire(byId(root, 'pd-f-cat'), 'change');
  fire(byId(root, 'pd-f-sub'), 'change');
  await settle();

  const filteredList = calls.filter((c) => c.m === 'GET' && c.p === R.LIST).pop();
  ok(filteredList && filteredList.q.productCode === 'CU' && filteredList.q.productName === '沪' &&
     filteredList.q.status === 'ENABLED' && filteredList.q.category === 'COMMODITY' && filteredList.q.subAccountId === '3',
    '① 列表筛选**服务端下推**（productCode/productName/status/category/subAccountId 逐字）：实际=' +
    JSON.stringify(filteredList && filteredList.q));

  // 导出（核心判据）
  const exportBtn = byText(root, '导出 CSV');
  ok(!!exportBtn, '① 「导出 CSV」按钮在');
  exportBtn.click();
  await settle();

  const dl = calls.filter((c) => c.m === 'DOWNLOAD').pop();
  ok(!!dl, '① 点击导出触发下载通道');
  ok(dl && dl.p === R.EXPORT, '① 导出路径逐字 = routes.PRODUCT.EXPORT（实际 ' + (dl && dl.p) + '）');
  ok(dl && dl.q.productCode === 'CU', '① 导出 URL 带 productCode=CU（OBS-T116V-1：筛选后导出须同口径）');
  ok(dl && dl.q.productName === '沪', '① 导出 URL 带 productName');
  ok(dl && dl.q.status === 'ENABLED', '① 导出 URL 带 status');
  ok(dl && dl.q.category === 'COMMODITY', '① 导出 URL 带 category');
  ok(dl && dl.q.subAccountId === '3', '① 导出 URL 带 subAccountId');
  ok(dl && !('page' in dl.q) && !('size' in dl.q),
    '① 导出不携带分页参数（导出是全集口径，分页参数无意义）：实际=' + JSON.stringify(dl && dl.q));

  // 负向对照（断言非空转）：清空筛选后导出 ⇒ 不带任何筛选键
  byText(root, '清空筛选').click();
  await settle();
  resetCalls();
  byText(root, '导出 CSV').click();
  await settle();
  const dl2 = calls.filter((c) => c.m === 'DOWNLOAD').pop();
  ok(dl2 && !('productCode' in dl2.q) && !('productName' in dl2.q) && !('status' in dl2.q) &&
     !('category' in dl2.q) && !('subAccountId' in dl2.q),
    '① 负向对照：清空筛选后导出**不带**筛选键（证明上组断言非空转）：实际=' + JSON.stringify(dl2 && dl2.q));
}

/* ================================================================== *
 * ② 导出空数据：10015 ⇒ blockedHTML + 「无可导出数据」文案（不生成空文件）
 * ================================================================== */
{
  resetCalls();
  const root = document.createElement('div');
  HG.pages.product.renderList(root);
  await settle();

  DATA.exportError = {
    code: 10015, message: '无可导出数据：当前筛选条件下没有符合的品种；复现=放宽筛选条件',
    nextStep: '放宽筛选条件后重试'
  };
  byText(root, '导出 CSV').click();
  await settle();

  const text = textOf(root);
  ok(text.indexOf('无可导出数据') !== -1, '② 10015 ⇒ 界面文案含「无可导出数据」（走 ui.blockedHTML）');
  ok(text.indexOf('放宽筛选条件') !== -1, '② 阻断提示带 nextStep（可操作指引）');
  ok(htmlAll(root).indexOf('hg-alert--blocked') !== -1 || text.indexOf('无可导出数据') !== -1,
    '② 提示由 blockedHTML 渲染（含阻断四项/文案）');
  ok(NAV.length === 0, '② 10015 不触发页面跳转（只提示）');
  DATA.exportError = null;
}

/* ================================================================== *
 * ③ 详情页：写面契约（PUT 修改 / POST 动作）
 * ================================================================== */
{
  resetCalls();
  const root = document.createElement('div');
  HG.pages.product.renderDetail(root, { params: { id: '7' }, query: { tab: 'overview' } });
  await settle();

  const detailCall = calls.filter((c) => c.m === 'GET' && c.p === R.detail(7))[0];
  ok(!!detailCall, '③ 详情首屏 GET /product/7');

  // 修改：填名称 + 提交表单
  const nameInput = byId(root, 'pd-e-name');
  ok(!!nameInput, '③ 修改表单含「品种名称」（FR-PRODUCT-2）');
  nameInput.value = '沪铜（新）';
  const forms = walkAll(root).filter((n) => n.listeners && n.listeners.submit);
  ok(forms.length >= 1, '③ 概览含可提交表单（修改/计量单位）');
  forms[0].listeners.submit[0]({ preventDefault() {} });
  await settle();

  const putCall = calls.filter((c) => c.m === 'PUT').pop();
  ok(!!putCall, '③ 修改触发 PUT（§4.1.1「PUT 修改」）');
  ok(putCall && putCall.p === R.detail(7), '③ PUT 路径 = /product/7（实际 ' + (putCall && putCall.p) + '）');
  ok(putCall && putCall.b.productName === '沪铜（新）', '③ PUT body 字段名 camelCase：productName');
  ok(putCall && putCall.b.categoryManaged === 'Y', '③ PUT body 带 categoryManaged');
  ok(putCall && !('productCode' in putCall.b), '③ PUT body **不含** productCode（编码不可改，R-PRODUCT-3）');
  ok(putCall && !('category' in putCall.b), '③ PUT body 不含 category（分类变更未承载 ⇒ 不发，避免 10012 噪音）');
  ok(NAV.length >= 1, '③ 修改成功后回到详情（刷新展示）');
}

/* ================================================================== *
 * ④ 停用 / 启用 / 计量单位：动作式 POST 契约
 * ================================================================== */
{
  resetCalls();
  NAV.length = 0;
  // 二次确认在探针内视为「用户点确认」（危险操作仍走 confirm，不在此判 UI 弹窗形态）
  HG.ui.confirm = async () => true;

  const root = document.createElement('div');
  HG.pages.product.renderDetail(root, { params: { id: '7' }, query: { tab: 'overview' } });
  await settle();

  // 停用：原因必填（空原因时不得发请求）
  const reason = byId(root, 'pd-s-reason');
  ok(!!reason, '④ 停用表单含「停用原因」');
  byText(root, '停用品种').click();
  await settle();
  ok(calls.filter((c) => c.m === 'POST').length === 0, '④ 原因为空 ⇒ 前端拦截、不发 POST（不制造 10001 噪音）');

  reason.value = '不再套保';
  byText(root, '停用品种').click();
  await settle();
  const postDisable = calls.filter((c) => c.m === 'POST').pop();
  ok(!!postDisable && postDisable.p === R.DISABLE(7) && postDisable.b.reason === '不再套保',
    '④ 停用 ⇒ POST /product/7/disable {reason}（实际=' + JSON.stringify(postDisable) + '）');

  // 启用
  byText(root, '启用品种').click();
  await settle();
  const postEnable = calls.filter((c) => c.m === 'POST').pop();
  ok(!!postEnable && postEnable.p === R.ENABLE(7), '④ 启用 ⇒ POST /product/7/enable（实际=' + (postEnable && postEnable.p) + '）');

  // 计量单位（FR-PRODUCT-13）：填表 → 提交
  byId(root, 'pd-u-spot').value = '吨';
  byId(root, 'pd-u-futures').value = '手';
  byId(root, 'pd-u-factor').value = '5.0000';
  byId(root, 'pd-u-from').value = '2026-11-01';
  byId(root, 'pd-u-desc').value = '1 手 = 5 吨';
  const unitForm = walkAll(root).filter((n) => n.listeners && n.listeners.submit).pop();
  unitForm.listeners.submit[0]({ preventDefault() {} });
  await settle();
  const postUnit = calls.filter((c) => c.m === 'POST').pop();
  ok(!!postUnit && postUnit.p === R.UNIT(7), '④ 计量单位 ⇒ POST /product/7/unit（实际=' + (postUnit && postUnit.p) + '）');
  ok(postUnit && postUnit.b.spotUnit === '吨' && postUnit.b.futuresUnit === '手', '④ PUT_UNIT 带 spotUnit/futuresUnit');
  ok(postUnit && postUnit.b.convertFactor === '5.0000', '④ 换算系数以**字符串**提交（§4.1.1 字符串化数值口径）');
  ok(postUnit && postUnit.b.effectiveFrom === '2026-11-01', '④ 带生效起始日 yyyy-MM-dd');

  // 换算系数 0 ⇒ 前端拦截
  resetCalls();
  byId(root, 'pd-u-factor').value = '0';
  unitForm.listeners.submit[0]({ preventDefault() {} });
  await settle();
  ok(calls.filter((c) => c.m === 'POST').length === 0, '④ 系数 0 ⇒ 前端拦截（FR-PRODUCT-13 验收 2），不发请求');
}

/* ================================================================== *
 * ⑤ 白名单读取（FR-PRODUCT-6）：三态 + 行字段
 * ================================================================== */
{
  resetCalls();
  const root = document.createElement('div');
  HG.pages.product.renderDetail(root, { params: { id: '7' }, query: { tab: 'whitelist' } });
  await settle();

  const wl = calls.filter((c) => c.m === 'GET' && c.p === R.WHITELIST).pop();
  ok(!!wl, '⑤ 白名单 Tab 发 GET /product/whitelist（读面已接线）');
  ok(wl && wl.q.productId === 7, '⑤ 携带 productId=7（实际=' + JSON.stringify(wl && wl.q) + '）');

  const html = htmlAll(root);
  ok(html.indexOf('PENDING') !== -1, '⑤ 行含下发状态 dispatchStatus（R-PRODUCT-9）');
  ok(textOf(root).indexOf('已移出（保留历史）') !== -1, '⑤ 已移出行以「已移出（保留历史）」标注（R-PRODUCT-5）');
  ok(textOf(root).indexOf('指标调整') !== -1, '⑤ 行含移出原因');
  ok(textOf(root).indexOf('共 2 行') !== -1, '⑤ 展示行数统计（含历史行，非静默截断）');

  // 空态
  resetCalls();
  DATA.whitelistRows = [];
  const emptyRoot = document.createElement('div');
  HG.pages.product.renderDetail(emptyRoot, { params: { id: '7' }, query: { tab: 'whitelist' } });
  await settle();
  ok(textOf(emptyRoot).indexOf('暂无数据') !== -1, '⑤ 空态：无白名单行 ⇒ 「暂无数据」显式（非空白表格）');

  // 错误态 + 重试
  resetCalls();
  DATA.whitelistFail = true;
  DATA.whitelistRows = [];
  const errRoot = document.createElement('div');
  HG.pages.product.renderDetail(errRoot, { params: { id: '7' }, query: { tab: 'whitelist' } });
  await settle();
  ok(textOf(errRoot).indexOf('加载失败') !== -1, '⑤ 错误态：请求失败 ⇒ 「加载失败」显式');
  const retry = byText(errRoot, '重试');
  ok(!!retry, '⑤ 错误态含重试入口');
  DATA.whitelistFail = false;
  if (retry) {
    resetCalls();
    retry.click();
    await settle();
    ok(calls.filter((c) => c.m === 'GET' && c.p === R.WHITELIST).length >= 1,
      '⑤ 点「重试」重新取数（实际调用=' + JSON.stringify(calls) + '）');
  }
}

if (fails.length) {
  console.log('FAILED ' + fails.length + ' / PASS ' + pass);
  process.exit(1);
}
console.log('PASS ' + pass + ' / FAIL 0（T-131 写面接线 + 导出带筛选 + 白名单读取）');
process.exit(0);
