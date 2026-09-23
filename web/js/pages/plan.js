/*
 * 套保计划页面（PLAN）—— 列表 + 编制（含三档比率）+ 详情（书面文件 + 状态机操作）。
 *
 * **字段名以已注册 Handler 为准（camelCase）**（施工依据 = `java/src/main/java/com/hedgeguard/web/handler/plan/**`）：
 *   GET  /plan                    → **PageData 分页信封** {items:[PlanView], total, page, size}
 *                                    PlanView {id, planNo, subAccountId, planType, productId,
 *                                    hedgeDirection, planQty, unit, termStartDate, termEndDate, hedgeRatio,
 *                                    ratioMethod, status, quotaOccupied, currentDocVersion, executedQty,
 *                                    orderedQty, bindings:[{exposureId, coveredQty, status}], precheckResult}
 *                                    查询：page / size∈{10,20,50,100}（默认 20）/ sort=field,asc|desc
 *                                          （**默认 created_time,desc**）/ subAccountId / productId / status /
 *                                          planType / termStartFrom / termStartTo / createdFrom / createdTo / applicantId
 *   POST /plan                    → CreateRequest {subAccountId, planType, productId, hedgeDirection, planQty,
 *                                    unit, termStartDate, termEndDate, hedgeRatio, ratioMethod, calcBaseDate,
 *                                    binds:[{exposureId, coveredQty}]} → 新计划 id
 *   GET  /plan/{id}               → PlanView（详情；含 bindings 与最近一次 precheckResult 快照）
 *   POST /plan/{id}/doc           → {elemFormalDesignation, elemEffectivenessMethod, elemRebalanceMechanism,
 *                                    hedgingInstrument} → 新书面文件版本号（三要素门禁，缺一 → 10012）
 *   POST /plan/{id}/submit        → 提交审批 → {precheckResult, unverified:[...]}（unverified 含 "R-PLAN-3/③"）
 *   POST /plan/{id}/approve       → {reason?}    审批通过（**发起人 ≠ 审批人**；占额）
 *   POST /plan/{id}/reject        → {reason}     驳回（原因必填；回草稿）
 *   POST /plan/{id}/withdraw      → {reason}     撤回（待审批 → 草稿）
 *   POST /plan/{id}/freeze        → {reason}     冻结（额度继续占用）
 *   POST /plan/{id}/unfreeze      → {}           解冻
 *   POST /plan/{id}/terminate     → {reason}     终止（释放额度）
 *   POST /plan/{id}/extend        → {newEndDate, reason}  延期（须给实货合同期限依据）
 *   GET  /plan/{id}/trade-plans   → **PageData 分页信封** {items:[TradePlanHandler.View], total, page, size}
 *                                    View {id, tradePlanNo, hedgePlanId, exposureId, instrumentId,
 *                                    instrumentType, direction, planQty, executedQty, orderedQty,
 *                                    execDeadline, status, remainingQty?, limitReached?}
 *                                    查询：page / size∈{10,20,50,100}（默认 20）/ sort=exec_deadline,asc|desc
 *                                          （默认升序）/ subAccountId / instrumentId / status /
 *                                          execDeadlineFrom / execDeadlineTo
 *   POST /plan/{id}/trade-plans   → {plans:[{instrumentId, instrumentType, direction, planQty, execDeadline}]} → 条数
 *   POST /plan/ratio/{simple|minvar|custom} → RatioView {id, ratioMethod, resultCode, outputRatio, rho,
 *                                    sigmaSpot, sigmaFutures, sampleCount, appliedRatioCap, appliedRuleId,
 *                                    hitRuleCount, failDetail}
 *   POST /plan/{id}/bind-exposure → {binds:[{exposureId, coveredQty}]} → {bound}（仅草稿态可直接增删）
 *
 * ✅ **`GET /plan`（列表）已注册**（`java/src/main/java/com/hedgeguard/web/handler/plan/PlanRoutes.java:52`
 *    `router.register("GET", PlanHandler.PATH, …)`；`T-010` 补齐）。**列表 UI 已由本任务（`T-020`）铺开**。
 *
 * ⚠️ **诚实披露（未实现/受限项，集中在此，勿在正文另作承诺）**：
 *   1. **`FR-PLAN-15` 的 `remainingQty`（剩余可下单量）/ `limitReached`（已达上限标记）** 由服务端 `T-104`
 *      提供，而 `T-104` 实测为 `TODO` ⇒ **服务端尚未输出这两个字段**。本页**优先消费服务端字段**（存在即用），
 *      缺失时按 `planQty − orderedQty` **派生展示**并明确标注「派生值（待 `T-104` 服务端字段）」。
 *   2. **`GET /plan` 至多返回授权子账户范围**；「审批人视角默认展示待我审批」（`FR-PLAN-4` 验收 3）本页以
 *      **状态=待审批**快捷筛选近似；**「排除本人发起」需服务端支持**（无「非本人」筛选参数）⇒ 未实现。
 *   3. **详情区块缺口**：`PlanView` **不含**套保比率计算过程（`t_hedge_ratio_calc`）、审批链记录、书面文件**正文**，
 *      且**无书面文件读取端点** ⇒ 「书面文件预览」= **生成 + 回显本次提交要素 + 版本号**，**正文读取缺端点**。
 *      已如实登记，见详情页「待补区块」。
 *   4. **`FR-PLAN-2`（修改草稿）/ `FR-PLAN-3`（删除草稿）无 REST 端点**：`PlanRoutes` **未注册**
 *      `PUT|DELETE /plan/{id}`（见 `PlanRoutes.java:52-65` 全部 `router.register`）⇒ 前端**无法实现**修改 / 删除；
 *      本页**不虚构**这两个入口（状态机动作 `submit/approve/reject/withdraw/freeze/unfreeze/terminate/extend` 均已接线）。
 *
 * 易用性 NFR-USE-2：详情页提供「下单」按钮，预填 计划ID / 子账户 / 品种 / 方向。
 */
import { HG } from '../core/ns.js';
import '../core/format.js';
import '../core/router.js';
import '../core/ui.js';

const ui = HG.ui;
const F = HG.format;

/* ------------------------------------------------------------------ *
 * 品种 id → 展示名（拉取一次，仅展示）
 * ------------------------------------------------------------------ */

const productCache = {};
let productLoaded = false;
function loadProductsOnce() {
  if (productLoaded) return Promise.resolve();
  productLoaded = true;
  return HG.api.get(HG.routes.PRODUCT.LIST).then((data) => {
    const items = Array.isArray(data) ? data : (data && data.items) || [];
    items.forEach((p) => { productCache[String(p.id)] = (p.productCode ? p.productCode + ' ' : '') + (p.productName || ('#' + p.id)); });
  }).catch(() => { productLoaded = false; });
}
function productName(id) {
  if (id === null || id === undefined || Number(id) === 0) return '（未映射）';
  return productCache[String(id)] || ('#' + id);
}
function subAccountLabel(id) {
  if (id === null || id === undefined) return '—';
  const hit = HG.session.state.subAccounts.filter((s) => String(s.id) === String(id))[0];
  return hit ? ((hit.code ? hit.code + ' ' : '') + (hit.name || '')) : ('#' + id);
}

/* ------------------------------------------------------------------ *
 * 枚举 → 文案（仅展示）
 * ------------------------------------------------------------------ */

const PLAN_TYPE = { COMMODITY: '商品类套保', TRADE: '贸易类套保' };
const HEDGE_DIR = { BUY_HEDGE: '买入套保', SELL_HEDGE: '卖出套保' };
const PLAN_STATUS = { DRAFT: '草稿', PENDING_APPROVAL: '待审批', APPROVED: '已批准', FROZEN: '已冻结', COMPLETED: '已完成', TERMINATED: '已终止' };
const RATIO_METHOD = { SIMPLE: '简单比率法', MIN_VARIANCE: '最小方差法', CUSTOM_RULE: '自定义规则法' };
const TRADE_PLAN_STATUS = { PENDING: '待执行', EXECUTING: '执行中', COMPLETED: '已完成', CANCELLED: '已撤销' };
const INSTRUMENT_TYPE = { FUTURES: '期货', OPTION: '期权' };

/** 计划列表排序字段白名单（= PlanService.LIST_SORT_FIELDS，服务端拒绝白名单外取值）。 */
const LIST_SORT_FIELDS = ['created_time', 'id', 'plan_no', 'status', 'plan_type', 'term_start_date', 'term_end_date', 'plan_qty'];
/** 交易计划排序字段白名单（= PlanService.TRADE_PLAN_SORT_FIELDS）。 */
const TRADE_SORT_FIELDS = ['exec_deadline', 'id', 'trade_plan_no', 'instrument_id', 'status', 'plan_qty', 'created_time'];

/* ------------------------------------------------------------------ *
 * 通用下拉 / 校验 / 小工具
 * ------------------------------------------------------------------ */

function opt(value, text) { return ui.el('option', { value: value, text: text }); }

function subAccountSelect(id, required) {
  const sel = ui.el('select', { id: id, name: 'subAccountId' });
  sel.appendChild(opt('', required ? '请选择子账户' : '（全部授权子账户）'));
  HG.session.state.subAccounts.forEach((s) => {
    sel.appendChild(opt(s.id, (s.code ? s.code + ' ' : '') + (s.name || ('#' + s.id))));
  });
  const cur = HG.session.currentId();
  if (cur !== null && cur !== undefined) sel.value = String(cur);
  return sel;
}

function productSelect(id, required) {
  const sel = ui.el('select', { id: id, name: 'productId' });
  sel.appendChild(opt('', required ? '加载中…' : '（全部品种）'));
  loadProductsOnce().then(() => {
    sel.innerHTML = '';
    sel.appendChild(opt('', required ? '请选择品种' : '（全部品种）'));
    Object.keys(productCache).forEach((pid) => sel.appendChild(opt(pid, productCache[pid])));
    if (!Object.keys(productCache).length) sel.appendChild(opt('', '（无可选品种，请先在品种管理中维护）'));
  });
  return sel;
}

function enumSelect(id, map, firstLabel) {
  const sel = ui.el('select', { id: id });
  sel.appendChild(opt('', firstLabel));
  Object.keys(map).forEach((k) => sel.appendChild(opt(k, map[k])));
  return sel;
}

const req = (label) => (v) => (String(v === null || v === undefined ? '' : v).trim() ? true : '请填写' + label);
const dateReq = (label) => (v) => (v ? true : '请选择' + label);
const decimalPositive = (label) => (v) => {
  const s = String(v === null || v === undefined ? '' : v).trim();
  if (!s) return '请填写' + label;
  if (!/^\d+(\.\d+)?$/.test(s)) return label + '须为十进制数字（如 1000.0000）';
  if (Number(s) <= 0) return label + '必须大于 0';
  return true;
};

function today() {
  const d = new Date();
  const p = (n) => (n < 10 ? '0' + n : '' + n);
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

function planStatusBadge(s) { return ui.statusBadge(PLAN_STATUS[s] || s); }

/** 展示侧百分比（progress = numer/denom × 100）。**仅展示辅助，非业务判定**。 */
function ratioPct(numerStr, denomStr) {
  const n = Number(numerStr), d = Number(denomStr);
  if (!isFinite(n) || !isFinite(d) || d <= 0) return '';
  return (n / d * 100).toFixed(2) + '%';
}

/** 展示侧时间进度百分比（已过期限占比）。**仅展示辅助**。 */
function timeProgress(startDate, endDate) {
  if (!startDate || !endDate) return null;
  const s = Date.parse(startDate + 'T00:00:00'), e = Date.parse(endDate + 'T00:00:00');
  if (!isFinite(s) || !isFinite(e) || e <= s) return null;
  const now = Date.now();
  return Math.max(0, Math.min(100, (now - s) / (e - s) * 100));
}

function goOrder(prefill) {
  const q = 'planId=' + encodeURIComponent(prefill.hedgePlanId || '') +
    '&subAccountId=' + encodeURIComponent(prefill.subAccountId || '') +
    '&productId=' + encodeURIComponent(prefill.productId || '') +
    '&direction=' + encodeURIComponent(prefill.direction || '') +
    (prefill.tradePlanId ? '&tradePlanId=' + encodeURIComponent(prefill.tradePlanId) : '') +
    (prefill.instrumentId ? '&instrumentId=' + encodeURIComponent(prefill.instrumentId) : '');
  HG.router.navigate('/trade?' + q);
}

/* ------------------------------------------------------------------ *
 * 列表（FR-PLAN-4）
 * ------------------------------------------------------------------ */

function renderList(root, ctx) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '套保计划' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '计划由实货敞口出发编制、经审批后方可下单；每笔交易须绑定计划 ID。' }));

  root.appendChild(ui.banner(
    '<strong>列表已铺开</strong>：后端 <code>GET /plan</code>（列表，含筛选/分页/排序）<strong>已注册并可用</strong> ' +
    '（<code>PlanRoutes.java:52</code>；<code>T-010</code> 补齐）。默认按 <code>created_time</code> <strong>倒序</strong>' +
    '（<code>FR-PLAN-4</code>）；每行展示<strong>执行进度</strong>与<strong>占用额度</strong>（验收 2）。' +
    '结果<strong>仅含授权子账户范围</strong>（子账户隔离由服务端强制）。'
  ));

  const state = {
    page: 1, size: HG.api.DEFAULT_PAGE_SIZE,
    sortField: 'created_time', sortDir: 'desc'
  };

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '计划列表' }));

  const bar = ui.el('div', { class: 'hg-toolbar' });

  const fSub = subAccountSelect('pl-f-sub', false);
  const fProduct = productSelect('pl-f-product', false);
  const fStatus = enumSelect('pl-f-status', PLAN_STATUS, '全部状态');
  const fType = enumSelect('pl-f-type', PLAN_TYPE, '全部类型');
  const fTermFrom = ui.el('input', { id: 'pl-f-term-from', type: 'date' });
  const fTermTo = ui.el('input', { id: 'pl-f-term-to', type: 'date' });
  const fCreatedFrom = ui.el('input', { id: 'pl-f-created-from', type: 'date' });
  const fCreatedTo = ui.el('input', { id: 'pl-f-created-to', type: 'date' });
  const fApplicant = ui.el('input', { id: 'pl-f-applicant', type: 'text', inputmode: 'numeric' });
  const fSortField = ui.el('select', { id: 'pl-f-sortfield' });
  LIST_SORT_FIELDS.forEach((k) => fSortField.appendChild(opt(k, k)));
  const fSortDir = ui.el('select', { id: 'pl-f-sortdir' });
  fSortDir.appendChild(opt('desc', '降序'));
  fSortDir.appendChild(opt('asc', '升序'));
  const fSize = ui.el('select', { id: 'pl-f-size' });
  HG.api.PAGE_SIZES.forEach((s) => fSize.appendChild(opt(String(s), s + ' 条/页')));

  bar.appendChild(ui.field({ id: 'pl-f-sub', label: '子账户', control: fSub }));
  bar.appendChild(ui.field({ id: 'pl-f-product', label: '品种', control: fProduct }));
  bar.appendChild(ui.field({ id: 'pl-f-status', label: '状态', control: fStatus }));
  bar.appendChild(ui.field({ id: 'pl-f-type', label: '计划类型', control: fType }));
  bar.appendChild(ui.field({ id: 'pl-f-term-from', label: '期限起 ≥', control: fTermFrom }));
  bar.appendChild(ui.field({ id: 'pl-f-term-to', label: '期限起 ≤', control: fTermTo }));
  bar.appendChild(ui.field({ id: 'pl-f-created-from', label: '创建 ≥', control: fCreatedFrom }));
  bar.appendChild(ui.field({ id: 'pl-f-created-to', label: '创建 ≤', control: fCreatedTo }));
  bar.appendChild(ui.field({ id: 'pl-f-applicant', label: '发起人 ID', help: '按发起人筛选（服务端 applicantId）', control: fApplicant }));
  bar.appendChild(ui.field({ id: 'pl-f-sortfield', label: '排序字段', control: fSortField }));
  bar.appendChild(ui.field({ id: 'pl-f-sortdir', label: '方向', control: fSortDir }));
  bar.appendChild(ui.field({ id: 'pl-f-size', label: '每页条数', control: fSize }));

  fSortField.value = state.sortField;
  fSortDir.value = state.sortDir;
  fSize.value = String(state.size);

  const actions = ui.el('div', { class: 'hg-toolbar' });
  const pendingBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '仅看待审批' });
  const newBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '＋ 编制计划' });
  const refreshBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  actions.appendChild(pendingBtn);
  actions.appendChild(newBtn);
  actions.appendChild(refreshBtn);
  card.appendChild(bar);
  card.appendChild(actions);

  const body = ui.el('div');
  card.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '套保计划分页' });
  card.appendChild(pager);
  root.appendChild(card);

  function query(page) {
    const q = { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (fSub.value) q.subAccountId = fSub.value;
    if (fProduct.value) q.productId = fProduct.value;
    if (fStatus.value) q.status = fStatus.value;
    if (fType.value) q.planType = fType.value;
    if (fTermFrom.value) q.termStartFrom = fTermFrom.value;
    if (fTermTo.value) q.termStartTo = fTermTo.value;
    if (fCreatedFrom.value) q.createdFrom = fCreatedFrom.value;
    if (fCreatedTo.value) q.createdTo = fCreatedTo.value;
    if (fApplicant.value) q.applicantId = fApplicant.value;
    return q;
  }

  function syncState() {
    state.sortField = fSortField.value;
    state.sortDir = fSortDir.value;
    state.size = Number(fSize.value);
  }

  async function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载套保计划…');
    ui.clear(pager);
    try {
      const pg = normalizePage(await HG.api.get(HG.routes.PLAN.LIST, query(page)), state);
      state.page = pg.page;
      state.size = pg.size;
      renderRows(pg);
    } catch (err) {
      ui.renderError(body, err, () => load(state.page));
      ui.clear(pager);
    }
  }

  function renderRows(pg) {
    if (!pg.items.length) {
      const cond = [];
      if (fStatus.value) cond.push('状态=' + (PLAN_STATUS[fStatus.value] || fStatus.value));
      if (fType.value) cond.push('类型=' + (PLAN_TYPE[fType.value] || fType.value));
      if (fSub.value) cond.push('子账户=' + subAccountLabel(fSub.value));
      const extra = pg.total > 0
        ? '（共 ' + pg.total + ' 条，当前第 ' + pg.page + ' 页无可显示记录）'
        : (cond.length ? '（当前筛选：' + cond.join('、') + '）' : '');
      ui.renderEmpty(body, '当前视图下没有套保计划' + extra + '。可点击「＋ 编制计划」由实货敞口新建。');
      renderPager(pg);
      return;
    }
    const columns = [
      { title: '计划编号', render: (r) => `<a href="#/plan/${F.esc(r.id)}">${F.esc(r.planNo)}</a>` },
      { title: '状态', render: (r) => planStatusBadge(r.status) },
      { title: '计划类型', render: (r) => F.esc(PLAN_TYPE[r.planType] || r.planType) },
      { title: '品种', render: (r) => F.esc(productName(r.productId)) },
      { title: '套保方向', render: (r) => F.esc(HEDGE_DIR[r.hedgeDirection] || r.hedgeDirection) },
      { title: '计划数量', num: true, render: (r) => F.qty(r.planQty) },
      { title: '执行进度', num: true, render: (r) => execProgress(r) },
      { title: '占用额度', render: (r) => (r.quotaOccupied === 'Y' ? '占用中' : '未占用') },
      { title: '期限', render: (r) => F.date(r.termStartDate) + ' ~ ' + F.date(r.termEndDate) },
      { title: '文件版本', num: true, render: (r) => 'v' + F.esc(r.currentDocVersion) },
      { title: '操作', render: (r) => `<a class="hg-btn hg-btn--ghost hg-btn--sm" href="#/plan/${F.esc(r.id)}">详情</a>` }
    ];
    ui.clear(body);
    body.innerHTML = ui.tableHTML(columns, pg.items);
    renderPager(pg);
  }

  /** 执行进度：展示侧（已成交 / 计划 + 百分比）。**非业务判定**（FR-PLAN-4 验收 2）。 */
  function execProgress(r) {
    const pct = ratioPct(r.executedQty, r.planQty);
    return F.qty(r.executedQty) + ' / ' + F.qty(r.planQty) + (pct ? '（' + pct + '）' : '');
  }

  function renderPager(pg) {
    ui.clear(pager);
    const totalPages = Math.max(1, Math.ceil(pg.total / pg.size));
    pager.appendChild(ui.el('span', { class: 'hg-pager__info', text: '共 ' + pg.total + ' 条，第 ' + pg.page + ' / ' + totalPages + ' 页' }));
    const prev = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '上一页', disabled: pg.page <= 1 });
    prev.addEventListener('click', () => { if (pg.page > 1) load(pg.page - 1); });
    const next = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '下一页', disabled: pg.page >= totalPages });
    next.addEventListener('click', () => { if (pg.page < totalPages) load(pg.page + 1); });
    pager.appendChild(prev);
    pager.appendChild(next);
  }

  // 筛选/排序变更即重新取数（回到第 1 页）
  [fSub, fProduct, fStatus, fType, fTermFrom, fTermTo, fCreatedFrom, fCreatedTo, fApplicant].forEach((c) => {
    c.addEventListener('change', () => { syncState(); load(1); });
  });
  [fSortField, fSortDir, fSize].forEach((c) => {
    c.addEventListener('change', () => { syncState(); load(1); });
  });
  pendingBtn.addEventListener('click', () => { fStatus.value = 'PENDING_APPROVAL'; syncState(); load(1); });
  newBtn.addEventListener('click', () => HG.router.navigate('/plan/new'));
  refreshBtn.addEventListener('click', () => { syncState(); load(state.page); });

  load(1);
}

/** 归一化分页响应（PageData 信封；兼容旧 bare 数组，界面不崩）。 */
function normalizePage(data, fallback) {
  if (Array.isArray(data)) {
    return { items: data, total: data.length, page: 1, size: data.length || fallback.size };
  }
  const items = (data && Array.isArray(data.items)) ? data.items : [];
  return {
    items: items,
    total: (data && typeof data.total === 'number') ? data.total : items.length,
    page: (data && typeof data.page === 'number' && data.page > 0) ? data.page : fallback.page,
    size: (data && typeof data.size === 'number' && data.size > 0) ? data.size : fallback.size
  };
}

/* ------------------------------------------------------------------ *
 * 三档比率计算器（FR-PLAN-6 / 7 / 8）—— 可独立使用，也可回填编制表单
 * ------------------------------------------------------------------ */

function ratioCalculator(onApply) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '套保比率三档计算（FR-PLAN-6/7/8）' }));
  card.appendChild(ui.el('p', { class: 'hg-page__desc', text: '三档方法并列可选、不得静默降级：简单比率法 / 最小方差法（样本 ≥ 30）/ 自定义规则法（取最严）。每次计算均留痕，可复算（R-PLAN-4）。' }));

  const bar = ui.el('div', { class: 'hg-tabs', role: 'tablist' });
  const methods = [
    { key: 'simple', label: RATIO_METHOD.SIMPLE },
    { key: 'minvar', label: RATIO_METHOD.MIN_VARIANCE },
    { key: 'custom', label: RATIO_METHOD.CUSTOM_RULE }
  ];
  let active = 'simple';

  const panel = ui.el('div');
  const resultBox = ui.el('div');
  const alertBox = ui.el('div', { role: 'alert' });

  const mSub = subAccountSelect('rc-sub', true);
  const mExposure = ui.el('input', { id: 'rc-exposure', type: 'text', inputmode: 'numeric' });
  const mPlanQty = ui.el('input', { id: 'rc-planqty', type: 'text', inputmode: 'decimal' });
  const mSpotSeries = ui.el('textarea', { id: 'rc-spot', rows: '3', placeholder: '逗号或换行分隔，≥ 30 个交易日' });
  const mFutSeries = ui.el('textarea', { id: 'rc-futures', rows: '3', placeholder: '与现货等长、按日对齐' });
  const mProduct = productSelect('rc-product', true);
  const mDir = ui.el('select', { id: 'rc-dir' });
  mDir.appendChild(opt('BUY', '买入'));
  mDir.appendChild(opt('SELL', '卖出'));
  const mRatio = ui.el('input', { id: 'rc-ratio', type: 'text', inputmode: 'decimal', placeholder: '可选：拟用比率' });
  const mBizDate = ui.el('input', { id: 'rc-bizdate', type: 'date' });

  function buildSimple() {
    const grid = ui.el('div', { class: 'hg-grid-2' });
    grid.appendChild(ui.field({ id: 'rc-sub', label: '子账户', required: true, control: mSub }));
    grid.appendChild(ui.field({ id: 'rc-exposure', label: '实货敞口 ID', required: true, help: '比率 = 拟套保数量 ÷ 敞口总数量', control: mExposure }));
    grid.appendChild(ui.field({ id: 'rc-planqty', label: '拟套保数量', required: true, help: '字符串化十进制，> 0', control: mPlanQty }));
    return grid;
  }
  function buildMinvar() {
    const grid = ui.el('div', { class: 'hg-grid-2' });
    grid.appendChild(ui.field({ id: 'rc-sub', label: '子账户', required: true, control: mSub }));
    grid.appendChild(ui.field({ id: 'rc-exposure', label: '实货敞口 ID', required: true, control: mExposure }));
    grid.appendChild(ui.field({ id: 'rc-spot', label: '现货价序列 σ_S', required: true, help: 'h = ρ × σ_S / σ_F；样本 < 30 个交易日将明确报错（不静默降级）', control: mSpotSeries }));
    grid.appendChild(ui.field({ id: 'rc-futures', label: '期货价序列 σ_F', required: true, help: '来自 CTP 行情；与现货等长', control: mFutSeries }));
    return grid;
  }
  function buildCustom() {
    const grid = ui.el('div', { class: 'hg-grid-2' });
    grid.appendChild(ui.field({ id: 'rc-sub', label: '子账户', required: true, control: mSub }));
    grid.appendChild(ui.field({ id: 'rc-product', label: '品种', required: true, control: mProduct }));
    grid.appendChild(ui.field({ id: 'rc-dir', label: '方向', required: true, help: '固定比例上限 + 适用条件；多套规则叠加取最严（Q-6）', control: mDir }));
    grid.appendChild(ui.field({ id: 'rc-ratio', label: '拟用比率', required: false, help: '非空时校验是否超内控上限（超限拒绝）', control: mRatio }));
    grid.appendChild(ui.field({ id: 'rc-bizdate', label: '业务日期', required: true, control: mBizDate }));
    return grid;
  }
  function rebuild() {
    ui.clear(panel);
    panel.appendChild(active === 'simple' ? buildSimple() : (active === 'minvar' ? buildMinvar() : buildCustom()));
  }

  const run = ui.el('button', { class: 'hg-btn', type: 'button', text: '计算' });
  const actions = ui.el('div', { class: 'hg-form-actions' }, run);

  methods.forEach((m) => {
    const b = ui.el('button', { type: 'button', role: 'tab', text: m.label });
    b.dataset.key = m.key;
    b.addEventListener('click', () => {
      active = m.key;
      Array.prototype.forEach.call(bar.children, (x) => x.classList.toggle('is-active', x.dataset.key === active));
      ui.clear(resultBox);
      rebuild();
    });
    bar.appendChild(b);
  });

  card.appendChild(bar);
  card.appendChild(panel);
  card.appendChild(alertBox);
  card.appendChild(actions);
  card.appendChild(resultBox);

  async function doCompute() {
    ui.clear(alertBox);
    ui.clear(resultBox);
    let ok = true;
    if (!mSub.value) { ui.setFieldError(mSub, '请选择子账户'); ok = false; } else ui.setFieldError(mSub, '');
    if (active !== 'custom') {
      if (!mExposure.value) { ui.setFieldError(mExposure, '请填写实货敞口 ID'); ok = false; } else ui.setFieldError(mExposure, '');
    }
    if (active === 'simple') {
      const v = decimalPositive('拟套保数量')(mPlanQty.value); ui.setFieldError(mPlanQty, v === true ? '' : v); if (v !== true) ok = false;
    }
    if (active === 'minvar') {
      if (!mSpotSeries.value.trim()) { ui.setFieldError(mSpotSeries, '请填写现货价序列'); ok = false; } else ui.setFieldError(mSpotSeries, '');
      if (!mFutSeries.value.trim()) { ui.setFieldError(mFutSeries, '请填写期货价序列'); ok = false; } else ui.setFieldError(mFutSeries, '');
    }
    if (active === 'custom') {
      if (!mProduct.value) { ui.setFieldError(mProduct, '请选择品种'); ok = false; } else ui.setFieldError(mProduct, '');
      if (!mBizDate.value) { ui.setFieldError(mBizDate, '请选择业务日期'); ok = false; } else ui.setFieldError(mBizDate, '');
    }
    if (!ok) return;

    let payload;
    if (active === 'simple') {
      payload = { subAccountId: Number(mSub.value), exposureId: Number(mExposure.value), planQty: mPlanQty.value };
    } else if (active === 'minvar') {
      payload = { subAccountId: Number(mSub.value), exposureId: Number(mExposure.value),
        spotSeries: splitSeries(mSpotSeries.value), futuresSeries: splitSeries(mFutSeries.value) };
    } else {
      payload = { subAccountId: Number(mSub.value), productId: mProduct.value, direction: mDir.value,
        bizDate: mBizDate.value };
      if (mRatio.value.trim()) payload.requestedRatio = mRatio.value.trim();
    }

    run.disabled = true; const orig = run.textContent; run.textContent = '计算中…';
    try {
      const v = await HG.api.post(HG.routes.PLAN.ratio(active), payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      renderRatioResult(resultBox, v, onApply);
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      run.disabled = false; run.textContent = orig;
    }
  }
  run.addEventListener('click', doCompute);

  Array.prototype.forEach.call(bar.children, (x) => x.classList.toggle('is-active', x.dataset.key === active));
  rebuild();
  return card;
}

function splitSeries(text) {
  return String(text || '').split(/[\s,，]+/).map((s) => s.trim()).filter((s) => s !== '');
}

function renderRatioResult(box, v, onApply) {
  ui.clear(box);
  const ok = v && v.resultCode === 'SUCCESS';
  box.appendChild(ui.banner(
    '<strong>结果码：' + F.esc(v && v.resultCode) + '</strong>（' + F.esc(RATIO_METHOD[v && v.ratioMethod] || (v && v.ratioMethod)) + '）' +
    (ok ? '' : '——' + F.esc((v && v.failDetail) || '未返回比率（不得静默降级）')),
    ok ? undefined : 'warn'
  ));
  const dl = ui.el('dl', { class: 'hg-desc' });
  [['输出比率', F.pct(v && v.outputRatio)],
   ['相关系数 ρ', v && v.rho !== null && v.rho !== undefined ? F.esc(v.rho) : '—'],
   ['现货价标准差 σ_S', v && v.sigmaSpot !== null && v.sigmaSpot !== undefined ? F.esc(v.sigmaSpot) : '—'],
   ['期货价标准差 σ_F', v && v.sigmaFutures !== null && v.sigmaFutures !== undefined ? F.esc(v.sigmaFutures) : '—'],
   ['样本数', v && v.sampleCount !== null && v.sampleCount !== undefined ? F.esc(v.sampleCount) : '—'],
   ['采用上限', v && v.appliedRatioCap !== null && v.appliedRatioCap !== undefined ? F.pct(v.appliedRatioCap) : '—'],
   ['命中规则 ID', v && v.appliedRuleId !== null && v.appliedRuleId !== undefined ? F.esc(v.appliedRuleId) : '—'],
   ['命中规则条数', v && v.hitRuleCount !== null && v.hitRuleCount !== undefined ? F.esc(v.hitRuleCount) : '—'],
   ['留痕 ID', v ? F.esc(v.id) : '—']
  ].forEach((r) => { dl.appendChild(ui.el('dt', { text: r[0] })); dl.appendChild(ui.el('dd', { text: r[1] })); });
  box.appendChild(dl);
  if (ok && typeof onApply === 'function') {
    const use = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '填入编制表单' });
    use.addEventListener('click', () => { onApply(v); ui.toast('已填入套保比率与计算方法', 'ok'); });
    box.appendChild(ui.el('div', { class: 'hg-form-actions' }, [use]));
  }
}

/* ------------------------------------------------------------------ *
 * 编制计划（FR-PLAN-1）—— 分步表单 + 三档比率 + 绑定敞口
 * ------------------------------------------------------------------ */

function renderNew(root, ctx) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '编制套保计划' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '由实货敞口出发编制（R-PLAN-1：禁止无敞口依据的计划）；初始状态为「草稿」，不占用额度。' }));
  root.appendChild(ui.el('p', {}, [ui.el('a', { href: '#/plan', text: '← 返回计划列表' })]));

  const fSub = subAccountSelect('pn-sub', true);
  const fType = enumSelect('pn-type', PLAN_TYPE, '请选择计划类型');
  const fProduct = productSelect('pn-product', true);
  const fDir = enumSelect('pn-dir', HEDGE_DIR, '请选择套保方向');
  const fPlanQty = ui.el('input', { id: 'pn-qty', type: 'text', inputmode: 'decimal' });
  const fUnit = ui.el('input', { id: 'pn-unit', type: 'text', maxlength: 16, placeholder: '如 TON' });
  const fTermStart = ui.el('input', { id: 'pn-term-start', type: 'date' });
  const fTermEnd = ui.el('input', { id: 'pn-term-end', type: 'date' });
  const fRatio = ui.el('input', { id: 'pn-ratio', type: 'text', inputmode: 'decimal' });
  const fRatioMethod = enumSelect('pn-ratio-method', RATIO_METHOD, '请选择计算方法');
  const fCalcBase = ui.el('input', { id: 'pn-calcbase', type: 'date' });
  fCalcBase.value = today();

  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);

  // 比率计算器（可回填本表单）
  const calc = ratioCalculator((v) => {
    fRatio.value = v.outputRatio;
    fRatioMethod.value = v.ratioMethod;
    ui.setFieldError(fRatio, '');
  });
  form.appendChild(calc);

  const fieldCard = ui.el('div', { class: 'hg-card' });
  fieldCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '计划要素（FR-PLAN-1）' }));
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'pn-sub', label: '所属子账户', required: true, help: '计划、绑定敞口、交易计划须同属一个子账户（R-PLAN-7）', control: fSub }));
  grid.appendChild(ui.field({ id: 'pn-type', label: '计划类型', required: true, help: '商品类 90% 红线 / 贸易类 80% 红线（分类可品种级覆盖）', control: fType }));
  grid.appendChild(ui.field({ id: 'pn-product', label: '品种', required: true, control: fProduct }));
  grid.appendChild(ui.field({ id: 'pn-dir', label: '套保方向', required: true, help: '必须与所绑定敞口方向相反（对冲）', control: fDir }));
  grid.appendChild(ui.field({ id: 'pn-qty', label: '计划数量', required: true, help: '字符串化十进制；不得超过绑定敞口的未覆盖数量', control: fPlanQty }));
  grid.appendChild(ui.field({ id: 'pn-unit', label: '计量单位', required: true, help: '与敞口同口径', control: fUnit }));
  grid.appendChild(ui.field({ id: 'pn-term-start', label: '计划期限起（建仓日）', required: true, control: fTermStart }));
  grid.appendChild(ui.field({ id: 'pn-term-end', label: '计划期限止', required: true, help: '上限取「建仓日 + 12 个月」与「实货合同到期日 + 30 天」较早者', control: fTermEnd }));
  grid.appendChild(ui.field({ id: 'pn-ratio', label: '套保比率', required: true, help: '∈ (0, 100%]；可由上方比率计算器回填', control: fRatio }));
  grid.appendChild(ui.field({ id: 'pn-ratio-method', label: '比率计算方法', required: true, control: fRatioMethod }));
  grid.appendChild(ui.field({ id: 'pn-calcbase', label: '计算基准日', required: true, control: fCalcBase }));
  fieldCard.appendChild(grid);
  form.appendChild(fieldCard);

  // 绑定敞口（≥1 条）
  const bindCard = ui.el('div', { class: 'hg-card' });
  bindCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '绑定实货敞口（至少一条，R-PLAN-1 / FR-PLAN-19）' }));
  bindCard.appendChild(ui.el('p', { class: 'hg-muted', text: '敞口须与计划同子账户、同品种、方向相反；每条覆盖数量合计不得超过其未覆盖数量。' }));
  const bindRows = ui.el('div');
  bindCard.appendChild(bindRows);
  const addRow = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '＋ 添加绑定' });
  bindCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [addRow]));
  form.appendChild(bindCard);

  function addBindRow() {
    const row = ui.el('div', { class: 'hg-toolbar' });
    const ex = ui.el('input', { type: 'text', inputmode: 'numeric', placeholder: '敞口 ID' });
    const qty = ui.el('input', { type: 'text', inputmode: 'decimal', placeholder: '覆盖数量' });
    const del = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '移除' });
    del.addEventListener('click', () => { if (bindRows.children.length > 1) bindRows.removeChild(row); });
    row.appendChild(ui.field({ id: '', label: '敞口 ID', control: ex }));
    row.appendChild(ui.field({ id: '', label: '覆盖数量', control: qty }));
    row.appendChild(del);
    row._ex = ex; row._qty = qty;
    bindRows.appendChild(row);
  }
  addRow.addEventListener('click', addBindRow);
  addBindRow();

  const submit = ui.el('button', { class: 'hg-btn', type: 'button', text: '保存为草稿' });
  const cancel = ui.el('a', { class: 'hg-btn hg-btn--ghost', href: '#/plan', text: '取消' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, [submit, cancel]));
  root.appendChild(form);

  function collectBinds() {
    const out = [];
    Array.prototype.forEach.call(bindRows.children, (row) => {
      const ex = row._ex.value.trim(), qty = row._qty.value.trim();
      if (!ex && !qty) return;
      out.push({ exposureId: Number(ex), coveredQty: qty });
    });
    return out;
  }

  submit.addEventListener('click', async () => {
    ui.clear(alertBox);
    let ok = true;
    const check = (ctrl, res) => { ui.setFieldError(ctrl, res === true ? '' : res); if (res !== true) ok = false; };
    check(fSub, req('所属子账户')(fSub.value));
    check(fType, req('计划类型')(fType.value));
    check(fProduct, req('品种')(fProduct.value));
    check(fDir, req('套保方向')(fDir.value));
    check(fPlanQty, decimalPositive('计划数量')(fPlanQty.value));
    check(fUnit, req('计量单位')(fUnit.value));
    check(fTermStart, dateReq('计划期限起')(fTermStart.value));
    check(fTermEnd, dateReq('计划期限止')(fTermEnd.value));
    check(fRatio, decimalPositive('套保比率')(fRatio.value));
    check(fRatioMethod, req('比率计算方法')(fRatioMethod.value));
    check(fCalcBase, dateReq('计算基准日')(fCalcBase.value));
    if (fTermStart.value && fTermEnd.value && fTermEnd.value < fTermStart.value) { ui.setFieldError(fTermEnd, '期限止不得早于期限起'); ok = false; }
    const binds = collectBinds();
    if (!binds.length) { ui.clear(alertBox); alertBox.appendChild(ui.banner('<strong>计划必须绑定实货敞口</strong>（R-PLAN-1）：请至少填写一条敞口 ID 与覆盖数量。', 'warn')); ok = false; }
    binds.forEach((b) => { if (!b.exposureId || isNaN(b.exposureId)) ok = false; });
    if (!ok) return;

    submit.disabled = true; const orig = submit.textContent; submit.textContent = '保存中…';
    try {
      const id = await HG.api.post(HG.routes.PLAN.CREATE, {
        subAccountId: Number(fSub.value), planType: fType.value, productId: fProduct.value,
        hedgeDirection: fDir.value, planQty: fPlanQty.value, unit: fUnit.value,
        termStartDate: fTermStart.value, termEndDate: fTermEnd.value,
        hedgeRatio: fRatio.value, ratioMethod: fRatioMethod.value, calcBaseDate: fCalcBase.value,
        binds: binds
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('套保计划已保存为草稿（id=' + id + '）', 'ok');
      HG.router.navigate('/plan/' + id);
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
      window.scrollTo(0, 0);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });
}

/* ------------------------------------------------------------------ *
 * 计划动作（FR-PLAN-10~18）—— 统一入口，按状态启用
 * ------------------------------------------------------------------ */

const ACTION_LABEL = {
  submit: '提交审批', approve: '审批通过', reject: '驳回', withdraw: '撤回',
  freeze: '冻结', unfreeze: '解冻', terminate: '终止', extend: '延期'
};

/** 状态 → 可用动作（与 PlanService 状态机一致；服务端仍为权威）。 */
function actionsForStatus(status) {
  switch (status) {
    case 'DRAFT': return ['submit'];
    case 'PENDING_APPROVAL': return ['approve', 'reject', 'withdraw'];
    case 'APPROVED': return ['freeze', 'terminate', 'extend'];
    case 'FROZEN': return ['unfreeze', 'terminate', 'extend'];
    default: return [];
  }
}

/* ------------------------------------------------------------------ *
 * 详情（FR-PLAN-5 / 9~20）
 * ------------------------------------------------------------------ */

function renderDetail(root, ctx) {
  const id = ctx.params.id;
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '套保计划详情' }));
  root.appendChild(ui.el('p', {}, [ui.el('a', { href: '#/plan', text: '← 返回计划列表' })]));

  const body = ui.el('div');
  root.appendChild(body);

  loadProductsOnce();
  async function load() {
    ui.renderLoading(body, '正在加载计划详情…');
    try { renderPlan(body, await HG.api.get(HG.routes.PLAN.detail(id)), load); }
    catch (err) { ui.renderError(body, err, load); }
  }
  load();
}

function renderPlan(box, d, reload) {
  ui.clear(box);
  const canOrder = d.status === 'APPROVED';

  /* ---- 概览 ---- */
  const head = ui.el('div', { class: 'hg-card' });
  const headBar = ui.el('div', { class: 'hg-toolbar' });
  headBar.style.justifyContent = 'space-between';
  headBar.appendChild(ui.el('h3', { class: 'hg-card__title', text: '计划 ' + F.esc(d.planNo) + ' ' }));
  headBar.appendChild(ui.el('span', { html: planStatusBadge(d.status) }));
  const orderBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: canOrder ? '下单（预填计划要素）' : '下单（仅已批准计划可用）', disabled: !canOrder });
  orderBtn.addEventListener('click', () => goOrder({ hedgePlanId: d.id, subAccountId: d.subAccountId, productId: d.productId, direction: d.hedgeDirection }));
  headBar.appendChild(orderBtn);
  head.appendChild(headBar);

  head.appendChild(progressBlock(d));

  const dl = ui.el('dl', { class: 'hg-desc' });
  [['计划类型', PLAN_TYPE[d.planType] || d.planType],
   ['所属子账户', subAccountLabel(d.subAccountId)],
   ['品种', productName(d.productId)],
   ['套保方向', HEDGE_DIR[d.hedgeDirection] || d.hedgeDirection],
   ['计划数量', F.qty(d.planQty) + ' ' + F.esc(d.unit || '')],
   ['已下达指令量', F.qty(d.orderedQty)],
   ['已成交数量', F.qty(d.executedQty)],
   ['剩余可下单量', F.qty(F.decSub(d.planQty, d.orderedQty))],
   ['计划期限', F.date(d.termStartDate) + ' ~ ' + F.date(d.termEndDate)],
   ['套保比率', F.pct(d.hedgeRatio) + '（' + F.esc(RATIO_METHOD[d.ratioMethod] || d.ratioMethod) + '）'],
   ['占用额度', d.quotaOccupied === 'Y' ? '占用中' : '未占用'],
   ['当前书面文件版本', d.currentDocVersion > 0 ? ('v' + F.esc(d.currentDocVersion)) : '（未生成）']
  ].forEach((r) => { dl.appendChild(ui.el('dt', { text: r[0] })); dl.appendChild(ui.el('dd', { text: r[1] })); });
  head.appendChild(dl);
  box.appendChild(head);

  /* ---- 合规预校验快照（验收 5：审批页面须展示 unverified）---- */
  box.appendChild(renderPrecheck(d));

  /* ---- 计划操作 ---- */
  box.appendChild(renderActions(d, reload));

  /* ---- 书面文件（FR-PLAN-9）---- */
  box.appendChild(renderDocCard(d, reload));

  /* ---- 绑定敞口 ---- */
  box.appendChild(renderBindings(d, reload));

  /* ---- 交易计划（FR-PLAN-14/15）---- */
  renderTradePlans(box, d, reload);

  /* ---- 待补区块（诚实登记）---- */
  box.appendChild(ui.banner(
    '<strong>待补区块</strong>：当前 <code>PlanView</code> <strong>不含</strong> 套保比率计算过程（<code>t_hedge_ratio_calc</code>）、审批链记录、书面文件<strong>正文</strong>；' +
    '且<strong>无书面文件读取端点</strong>（仅 <code>POST /plan/{id}/doc</code> 生成）。这些属 <code>FR-PLAN-5</code> 的详情组成，' +
    '需后端在详情中返回或提供对应端点；本页不作虚假承诺。',
    'warn'
  ));
}

/** 执行进度块（FR-PLAN-20）。**时间进度/偏差为展示侧辅助提示，非权威判定（权威阈值 R-PLAN-10 以服务端为准）**。 */
function progressBlock(d) {
  const box = ui.el('div', { class: 'hg-components' });
  const metric = (label, value, isTotal) => ui.el('div', { class: 'hg-metric' + (isTotal ? ' hg-metric--total' : '') }, [
    ui.el('div', { class: 'hg-metric__label', text: label }),
    ui.el('div', { class: 'hg-metric__value', text: value })
  ]);
  const qtyPct = ratioPct(d.executedQty, d.planQty);
  const timePct = timeProgress(d.termStartDate, d.termEndDate);
  box.appendChild(metric('计划数量', F.qty(d.planQty)));
  box.appendChild(metric('已下达指令量', F.qty(d.orderedQty)));
  box.appendChild(metric('已成交数量', F.qty(d.executedQty)));
  box.appendChild(metric('剩余可执行量', F.qty(F.decSub(d.planQty, d.orderedQty))));
  box.appendChild(metric('数量进度', qtyPct || '—', true));
  box.appendChild(metric('时间进度', timePct === null ? '—' : timePct.toFixed(2) + '%'));

  const wrap = ui.el('div');
  wrap.appendChild(box);
  // 展示侧偏差提示（依 R-PLAN-10 阈值 30pp；明确标注非权威）
  if (qtyPct && timePct !== null) {
    const diff = timePct - Number(qtyPct.replace('%', ''));
    if (diff > 30) {
      wrap.appendChild(ui.banner('展示侧提示：时间进度领先数量进度 ' + diff.toFixed(2) + ' 个百分点（阈值 30pp，R-PLAN-10）——可能存在执行落后。权威判定以服务端为准。', 'warn'));
    }
  }
  // 数据异常告警（已成交 > 计划）
  const over = F.decSub(d.executedQty, d.planQty);
  if (over && over.charAt(0) === '-') {
    wrap.appendChild(ui.banner('数据异常告警：已成交数量 <strong>大于</strong> 计划数量（' + F.qty(d.executedQty) + ' > ' + F.qty(d.planQty) + '），请核查（R-PLAN-10）。', 'error'));
  }
  return wrap;
}

/** 合规预校验快照面板（FR-PLAN-10 验收 2 (c)：审批页面须展示 unverified）。 */
function renderPrecheck(d) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '合规预校验快照（R-PLAN-3）' }));
  const pr = d.precheckResult;
  if (!pr || typeof pr !== 'object') {
    card.appendChild(ui.el('p', { class: 'hg-muted', text: '尚无预校验快照（提交审批时由服务端生成）。' }));
    return card;
  }
  const unverified = Array.isArray(pr.unverified) ? pr.unverified : [];
  if (unverified.length) {
    card.appendChild(ui.banner(
      '<strong>未校验项（unverified）</strong>：<code>' + unverified.map((u) => F.esc(u)).join('</code>、<code>') + '</code>' +
      '。M1 期间规模红线（<code>R-PLAN-3/③</code>）归 M3 承接、分母表 <code>t_annual_scale</code> 不存在 ⇒ <strong>恒不可算、不因单项拒单</strong>，' +
      '但须显式标注（<code>FR-PLAN-10</code> 验收 2 (c)：审批接口响应<strong>与审批页面均须展示</strong>该标记）。',
      'warn'
    ));
  } else {
    card.appendChild(ui.el('p', { class: 'hg-muted', text: '本次快照未含未校验项。' }));
  }
  const items = Array.isArray(pr.items) ? pr.items : [];
  if (items.length) {
    card.appendChild(ui.el('div', { html: ui.tableHTML([
      { title: '规则编号', render: (r) => F.esc(r.rule) },
      { title: '当前值', render: (r) => F.esc(r.current) },
      { title: '阈值', render: (r) => F.esc(r.threshold) },
      { title: '判定', render: (r) => ui.statusBadge(r.verdict === 'PASS' ? '有效' : r.verdict) },
      { title: '来源', render: (r) => F.esc(r.source) }
    ], items) }));
  }
  if (pr.generated_at) {
    card.appendChild(ui.el('p', { class: 'hg-muted', text: '生成时间：' + F.esc(pr.generated_at) + '；生成人 ID：' + F.esc(pr.generated_by) }));
  }
  return card;
}

/** 计划状态机操作面板（FR-PLAN-10~18）。 */
function renderActions(d, reload) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '计划操作（状态机）' }));

  const list = actionsForStatus(d.status);
  if (!list.length) {
    card.appendChild(ui.el('p', { class: 'hg-muted', text: '当前状态（' + F.esc(PLAN_STATUS[d.status] || d.status) + '）无可用操作。' }));
    return card;
  }

  const reasonInput = ui.el('textarea', { id: 'pa-reason', rows: '2', placeholder: '原因 / 意见（驳回、撤回、冻结、终止、延期必填）' });
  const newEnd = ui.el('input', { id: 'pa-newend', type: 'date' });
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'pa-reason', label: '原因 / 审批意见', required: false, help: '驳回/撤回/冻结/终止/延期须填写原因（服务端校验）', control: reasonInput }));
  if (list.indexOf('extend') >= 0) {
    grid.appendChild(ui.field({ id: 'pa-newend', label: '新期限止（延期）', required: false, help: 'yyyy-MM-dd；展期不得自动延续，须重新审批', control: newEnd }));
  }
  card.appendChild(grid);

  const alertBox = ui.el('div', { role: 'alert' });
  card.appendChild(alertBox);

  // 提交前门禁提示：无当前版本书面文件（R-PLAN-8 / FR-PLAN-9 验收 3）
  if (list.indexOf('submit') >= 0 && !(d.currentDocVersion > 0)) {
    card.appendChild(ui.banner(
      '<strong>门禁提示</strong>：本计划<strong>尚无书面文件</strong>（当前版本 = 0）。依 <code>R-PLAN-8</code> / <code>FR-PLAN-9</code> 验收 3，' +
      '未生成书面文件的计划<strong>不得提交审批</strong>——提交将被服务端以 <code>10012</code> 拒绝并列出缺失项（<code>ruleId=R-PLAN-8</code>）。' +
      '请先在下方「套期关系书面文件」生成三要素齐备的文件。',
      'warn'
    ));
  }

  const bar = ui.el('div', { class: 'hg-form-actions' });
  list.forEach((action) => {
    const danger = (action === 'reject' || action === 'withdraw' || action === 'terminate' || action === 'freeze');
    const btn = ui.el('button', { class: 'hg-btn' + (danger ? ' hg-btn--danger' : ''), type: 'button', text: ACTION_LABEL[action] });
    btn.addEventListener('click', () => doAction(d, action, reasonInput, newEnd, alertBox, btn, reload));
    bar.appendChild(btn);
  });
  card.appendChild(bar);
  return card;
}

async function doAction(d, action, reasonInput, newEnd, alertBox, btn, reload) {
  ui.clear(alertBox);
  const reason = reasonInput.value.trim();

  const needReason = (action === 'reject' || action === 'withdraw' || action === 'freeze' || action === 'terminate' || action === 'extend');
  if (needReason && !reason) {
    ui.setFieldError(reasonInput, '该操作的原因必填（服务端亦强制）');
    return;
  }
  ui.setFieldError(reasonInput, '');

  const body = {};
  if (reason) body.reason = reason;
  if (action === 'extend') {
    if (!newEnd.value) { ui.setFieldError(newEnd, '延期须给出新期限止（newEndDate）'); return; }
    ui.setFieldError(newEnd, '');
    body.newEndDate = newEnd.value;
  }

  const danger = (action === 'reject' || action === 'withdraw' || action === 'terminate' || action === 'freeze');
  if (danger) {
    const ok = await ui.confirm(
      '确认对计划 <strong>' + F.esc(d.planNo) + '</strong> 执行「<strong>' + F.esc(ACTION_LABEL[action]) + '</strong>」？' +
      '<br><span class="hg-muted">' + actionEffect(action) + '</span>',
      { okText: ACTION_LABEL[action], danger: action === 'terminate' || action === 'reject' });
    if (!ok) return;
  }

  btn.disabled = true; const orig = btn.textContent; btn.textContent = '处理中…';
  try {
    const data = await HG.api.post(HG.routes.PLAN.action(d.id, action), body, { idempotencyKey: HG.api.newIdempotencyKey() });
    ui.toast('「' + ACTION_LABEL[action] + '」已提交', 'ok');
    // 提交审批：服务端回填的 unverified（三项可见性之 (a)）；页面侧持久展示由详情页「合规预校验快照」面板承载（(c)）
    if (action === 'submit' && data && Array.isArray(data.unverified) && data.unverified.length) {
      ui.toast('已提交；服务端预校验未校验项：' + data.unverified.join('、') + '（归 M3）', 'ok');
    }
    if (typeof reload === 'function') reload();
  } catch (err) {
    alertBox.innerHTML = ui.blockedHTML(err);
    window.scrollTo(0, 0);
  } finally {
    btn.disabled = false; btn.textContent = orig;
  }
}

function actionEffect(action) {
  switch (action) {
    case 'reject': return '驳回后计划回到「草稿」，驳回原因永久留痕（FR-PLAN-12）。';
    case 'withdraw': return '撤回后计划回到「草稿」（FR-PLAN-13）。';
    case 'freeze': return '冻结后保留已占用额度、不得新增下单（FR-PLAN-16）。';
    case 'terminate': return '终止后释放未使用额度、撤销未执行交易计划（FR-PLAN-17）。';
    default: return '';
  }
}

/** 绑定实货敞口面板（FR-PLAN-19）—— 仅草稿态可直接增删。 */
function renderBindings(d, reload) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '绑定的实货敞口（追溯链连接点）' }));
  const binds = d.bindings || [];
  if (!binds.length) {
    card.appendChild(ui.el('p', { class: 'hg-muted', text: '该计划未返回绑定敞口清单。' }));
  } else {
    card.appendChild(ui.el('div', { html: ui.tableHTML([
      // FR-PLAN-5 验收 2：须直达「该敞口详情」，故携带 id（由 spot.js renderExposure 读取 ctx.query.id）
      { title: '敞口 ID', render: (r) => `<a href="#/spot/exposure?id=${encodeURIComponent(r.exposureId)}">#${F.esc(r.exposureId)}</a>` },
      { title: '被覆盖数量', num: true, render: (r) => F.qty(r.coveredQty) },
      { title: '绑定状态', render: (r) => ui.statusBadge(r.status) }
    ], binds) }));
    card.appendChild(ui.el('p', { class: 'hg-muted', text: '注：当前 PlanView 的 BindingView 仅含 {exposureId, coveredQty, status}，无绑定时间；构成明细需敞口侧端点。' }));
  }

  if (d.status === 'DRAFT') {
    const ex = ui.el('input', { id: 'pb-exposure', type: 'text', inputmode: 'numeric' });
    const qty = ui.el('input', { id: 'pb-qty', type: 'text', inputmode: 'decimal' });
    const grid = ui.el('div', { class: 'hg-grid-2' });
    grid.appendChild(ui.field({ id: 'pb-exposure', label: '新增绑定：敞口 ID', required: false, help: '须与计划同子账户、同品种、方向相反', control: ex }));
    grid.appendChild(ui.field({ id: 'pb-qty', label: '覆盖数量', required: false, control: qty }));
    card.appendChild(grid);
    const alertBox = ui.el('div', { role: 'alert' });
    card.appendChild(alertBox);
    const bindBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '绑定追加' });
    bindBtn.addEventListener('click', async () => {
      ui.clear(alertBox);
      const v1 = req('敞口 ID')(ex.value), v2 = decimalPositive('覆盖数量')(qty.value);
      ui.setFieldError(ex, v1 === true ? '' : v1);
      ui.setFieldError(qty, v2 === true ? '' : v2);
      if (v1 !== true || v2 !== true) return;
      bindBtn.disabled = true; const orig = bindBtn.textContent; bindBtn.textContent = '绑定中…';
      try {
        const res = await HG.api.post(HG.routes.PLAN.bindExposure(d.id), {
          binds: [{ exposureId: Number(ex.value), coveredQty: qty.value }]
        }, { idempotencyKey: HG.api.newIdempotencyKey() });
        ui.toast('已追加绑定 ' + ((res && res.bound) || 0) + ' 条', 'ok');
        if (typeof reload === 'function') reload();
      } catch (err) {
        alertBox.innerHTML = ui.blockedHTML(err);
      } finally {
        bindBtn.disabled = false; bindBtn.textContent = orig;
      }
    });
    card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [bindBtn]));
  } else {
    card.appendChild(ui.el('p', { class: 'hg-muted', text: '已批准及之后状态不得直接增删绑定（须走变更流程，FR-PLAN-19 验收 4 / R-PLAN-6）。' }));
  }
  return card;
}

/* ------------------------------------------------------------------ *
 * 书面文件（FR-PLAN-9）—— 三要素门禁 + 生成 + 回显
 * ------------------------------------------------------------------ */

/** 最近一次生成回显（模块级；跨详情重渲染保留，避免生成后 reload 丢失回显）。 */
const docEcho = {};

function renderDocEcho(out, echo) {
  ui.clear(out);
  out.appendChild(ui.banner('已生成版本 <strong>v' + F.esc(echo.version) + '</strong>（pending_external_review = Y，R-ACCT-10）。以下为最近一次提交要素回显：', undefined));
  const dl = ui.el('dl', { class: 'hg-desc' });
  [['① 正式指定', echo.fd], ['② 有效性评估方法', echo.em], ['③ 再平衡机制', echo.rb], ['套期工具', echo.ins || '—']]
    .forEach((r) => { dl.appendChild(ui.el('dt', { text: r[0] })); dl.appendChild(ui.el('dd', { text: r[1] })); });
  out.appendChild(dl);
}

function renderDocCard(d, reload) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '套期关系书面文件（CAS 24 三要素门禁，FR-PLAN-9）' }));
  card.appendChild(ui.el('p', { class: 'hg-page__desc', text: '文件须含三要素：① 正式指定（被套期项目 + 套期工具）② 有效性评估方法 ③ 再平衡机制。三要素任一为空即拒绝（10012）。' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '当前版本：' + (d.currentDocVersion > 0 ? 'v' + d.currentDocVersion : '（未生成）') }));
  card.appendChild(ui.banner(
    '<strong>预览受限（诚实登记）</strong>：后端<strong>无书面文件读取端点</strong>（仅 <code>POST /plan/{id}/doc</code> 生成，返回版本号）⇒ ' +
    '本区只能<strong>生成文件并回显本次提交的三要素与版本号</strong>，无法读取已存正文。如需正文预览，须后端补读取端点。',
    'warn'
  ));

  const fd = ui.el('textarea', { id: 'pdoc-fd', rows: '2', placeholder: '① 正式指定：被套期项目 = 实货敞口标识；套期工具 = 期货/期权合约' });
  const em = ui.el('textarea', { id: 'pdoc-em', rows: '2', placeholder: '② 有效性评估方法' });
  const rb = ui.el('textarea', { id: 'pdoc-rb', rows: '2', placeholder: '③ 再平衡机制' });
  const ins = ui.el('input', { id: 'pdoc-ins', type: 'text', maxlength: 64, placeholder: '套期工具（合约 / 持仓标识）' });

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'pdoc-fd', label: '要素① 正式指定', required: true, control: fd }));
  grid.appendChild(ui.field({ id: 'pdoc-em', label: '要素② 有效性评估方法', required: true, control: em }));
  grid.appendChild(ui.field({ id: 'pdoc-rb', label: '要素③ 再平衡机制', required: true, control: rb }));
  grid.appendChild(ui.field({ id: 'pdoc-ins', label: '套期工具', required: false, help: '合约 / 持仓标识', control: ins }));
  card.appendChild(grid);

  const alertBox = ui.el('div', { role: 'alert' });
  card.appendChild(alertBox);
  const out = ui.el('div');
  card.appendChild(out);
  if (docEcho[d.id]) renderDocEcho(out, docEcho[d.id]);

  const gen = ui.el('button', { class: 'hg-btn', type: 'button', text: '生成书面文件' });
  gen.addEventListener('click', async () => {
    ui.clear(alertBox); ui.clear(out);
    let ok = true;
    const need = (ctrl, label) => { const r = req(label)(ctrl.value); ui.setFieldError(ctrl, r === true ? '' : r); if (r !== true) ok = false; };
    need(fd, '正式指定'); need(em, '有效性评估方法'); need(rb, '再平衡机制');
    if (!ok) {
      ui.clear(alertBox); alertBox.appendChild(ui.banner('三要素未齐：缺失项 <code>R-PLAN-8</code>（正式指定 / 有效性评估方法 / 再平衡机制）。三要素任一为空即拒绝生成。', 'warn'));
      return;
    }
    gen.disabled = true; const orig = gen.textContent; gen.textContent = '生成中…';
    try {
      const version = await HG.api.post(HG.routes.PLAN.doc(d.id), {
        elemFormalDesignation: fd.value, elemEffectivenessMethod: em.value,
        elemRebalanceMechanism: rb.value, hedgingInstrument: ins.value || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('书面文件已生成（v' + version + '）', 'ok');
      docEcho[d.id] = { version: version, fd: fd.value, em: em.value, rb: rb.value, ins: ins.value };
      renderDocEcho(out, docEcho[d.id]);
      if (typeof reload === 'function') reload();
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      gen.disabled = false; gen.textContent = orig;
    }
  });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [gen]));
  return card;
}

/* ------------------------------------------------------------------ *
 * 交易计划（FR-PLAN-14/15）—— 分页信封 + 排序 + 筛选 + 分解
 *
 *   响应 data = **PageData** {items, total, page, size}。
 *   size ∈ {10,20,50,100}（默认 20）；sort=exec_deadline,asc|desc（默认升序）。
 *   ⚠️ 服务端**默认已分页**：不传 size 只返回前 20 条，故本面板**始终显式传** page/size/sort。
 *   ⚠️ `remainingQty` / `limitReached` 由 `T-104` 提供（**实测 TODO**）⇒ 缺失时按派生值展示并标注。
 * ------------------------------------------------------------------ */

/** 剩余可下单量：优先取服务端 `remainingQty`；缺失时按 planQty − orderedQty 派生。 */
function tpRemaining(r) {
  if (r && r.remainingQty !== undefined && r.remainingQty !== null) return String(r.remainingQty);
  return F.decSub(r.planQty, r.orderedQty);
}
/** 已达上限：优先取服务端 `limitReached`；缺失时按剩余量 ≤ 0 派生。 */
function tpLimitReached(r) {
  if (r && typeof r.limitReached === 'boolean') return r.limitReached;
  const rem = tpRemaining(r);
  return rem !== '' && (rem === '0' || rem.charAt(0) === '-');
}

function renderTradePlans(box, plan, reloadPlan) {
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'exec_deadline', sortDir: 'asc', status: '' };

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '交易计划（可执行下单任务）' }));
  card.appendChild(ui.el('p', { class: 'hg-page__desc', text: '按 FR-PLAN-15：默认按执行期限升序；可切换排序方向与每页条数，并按状态筛选。' }));

  const bar = ui.el('div', { class: 'hg-toolbar' });
  const statusSel = ui.el('select', { id: 'tp-filter-status' });
  statusSel.appendChild(opt('', '全部状态'));
  Object.keys(TRADE_PLAN_STATUS).forEach((k) => statusSel.appendChild(opt(k, TRADE_PLAN_STATUS[k])));
  bar.appendChild(ui.field({ id: 'tp-filter-status', label: '状态', control: statusSel }));

  const dirSel = ui.el('select', { id: 'tp-sort-dir' });
  dirSel.appendChild(opt('asc', '执行期限：升序（先到期在前）'));
  dirSel.appendChild(opt('desc', '执行期限：降序（后到期在前）'));
  bar.appendChild(ui.field({ id: 'tp-sort-dir', label: '排序', control: dirSel }));

  const sizeSel = ui.el('select', { id: 'tp-page-size' });
  HG.api.PAGE_SIZES.forEach((s) => sizeSel.appendChild(opt(String(s), s + ' 条/页')));
  bar.appendChild(ui.field({ id: 'tp-page-size', label: '每页条数', control: sizeSel }));

  const reloadBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  bar.appendChild(reloadBtn);
  card.appendChild(bar);

  // 分解（POST /plan/{id}/trade-plans）—— 仅已批准状态（服务端校验）
  const decompose = renderDecomposeForm(plan, reloadPlan);
  if (plan.status === 'APPROVED') card.appendChild(decompose);

  const body = ui.el('div');
  card.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '交易计划分页' });
  card.appendChild(pager);
  box.appendChild(card);

  statusSel.value = state.status;
  dirSel.value = state.sortDir;
  sizeSel.value = String(state.size);

  statusSel.addEventListener('change', () => { state.status = statusSel.value; load(1); });
  dirSel.addEventListener('change', () => { state.sortDir = dirSel.value; load(1); });
  sizeSel.addEventListener('change', () => { state.size = Number(sizeSel.value); load(1); });
  reloadBtn.addEventListener('click', () => load(state.page));

  function query(page) {
    const q = { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (state.status) q.status = state.status;
    return q;
  }

  async function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载交易计划…');
    ui.clear(pager);
    try {
      const pg = normalizePage(await HG.api.get(HG.routes.PLAN.tradePlans(plan.id), query(page)), state);
      state.page = pg.page;
      state.size = pg.size;
      renderRows(pg);
    } catch (err) {
      ui.renderError(body, err, () => load(state.page));
      ui.clear(pager);
    }
  }

  function renderRows(pg) {
    if (!pg.items.length) {
      const extra = pg.total > 0
        ? '（共 ' + pg.total + ' 条，当前第 ' + pg.page + ' 页无可显示记录）'
        : (state.status ? '（当前筛选：' + (TRADE_PLAN_STATUS[state.status] || state.status) + '）' : '');
      ui.renderEmpty(body, '该计划尚未分解出交易计划' + extra + '。');
      renderPager(pg);
      return;
    }
    const serverFields = pg.items.some((r) => r.remainingQty !== undefined || typeof r.limitReached === 'boolean');
    const columns = [
      { title: '交易计划编号', render: (r) => F.esc(r.tradePlanNo) },
      { title: '合约', render: (r) => F.esc(r.instrumentId) + '（' + F.esc(INSTRUMENT_TYPE[r.instrumentType] || r.instrumentType) + '）' },
      { title: '方向', render: (r) => F.esc(r.direction === 'BUY' ? '买入' : '卖出') },
      { title: '数量', num: true, render: (r) => F.qty(r.planQty) },
      { title: '剩余可下单量', num: true, render: (r) => F.qty(tpRemaining(r)) + (serverFields ? '' : ' *') },
      { title: '执行期限', render: (r) => F.date(r.execDeadline) },
      { title: '状态', render: (r) => ui.statusBadge(TRADE_PLAN_STATUS[r.status] || r.status) },
      { title: '上限标记', render: (r) => (tpLimitReached(r) ? ui.statusBadge('已达上限') : '—') },
      { title: '操作', render: (r) => {
        const atCap = tpLimitReached(r);
        const disabled = atCap || r.status === 'COMPLETED' || r.status === 'CANCELLED';
        return `<button class="hg-btn hg-btn--sm" type="button" ${disabled ? 'disabled' : ''} data-tp='${F.esc(JSON.stringify({ hedgePlanId: plan.id, tradePlanId: r.id, subAccountId: plan.subAccountId, productId: plan.productId, direction: r.direction, instrumentId: r.instrumentId }))}'>${atCap ? '已达上限' : '下单'}</button>`;
      } }
    ];
    let html = ui.tableHTML(columns, pg.items);
    if (!serverFields) {
      html += '<p class="hg-muted">＊ 剩余可下单量按 <code>planQty − orderedQty</code> <strong>派生</strong>展示；' +
        '<code>remainingQty</code> / <code>limitReached</code> 由服务端 <strong><code>T-104</code></strong> 提供（实测 <code>TODO</code>、尚未交付）——本页消费优先取服务端字段，缺失时降级为派生值并标注。</p>';
    }
    ui.clear(body);
    body.innerHTML = html;
    Array.prototype.forEach.call(body.querySelectorAll('[data-tp]'), (b) => {
      b.addEventListener('click', () => goOrder(JSON.parse(b.getAttribute('data-tp'))));
    });
    renderPager(pg);
  }

  function renderPager(pg) {
    ui.clear(pager);
    const totalPages = Math.max(1, Math.ceil(pg.total / pg.size));
    pager.appendChild(ui.el('span', { class: 'hg-pager__info', text: '共 ' + pg.total + ' 条，第 ' + pg.page + ' / ' + totalPages + ' 页' }));
    const prev = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '上一页', disabled: pg.page <= 1 });
    prev.addEventListener('click', () => { if (pg.page > 1) load(pg.page - 1); });
    const next = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '下一页', disabled: pg.page >= totalPages });
    next.addEventListener('click', () => { if (pg.page < totalPages) load(pg.page + 1); });
    pager.appendChild(prev);
    pager.appendChild(next);
  }

  load(1);
}

/** 分解表单（POST /plan/{id}/trade-plans；FR-PLAN-14）。 */
function renderDecomposeForm(plan, reloadPlan) {
  const wrap = ui.el('div', { class: 'hg-card' });
  wrap.appendChild(ui.el('h4', { text: '分解交易计划（FR-PLAN-14）' }));
  wrap.appendChild(ui.el('p', { class: 'hg-muted', text: '拆分数量合计不得超过计划数量（' + F.qty(plan.planQty) + '）；方向须与计划一致；合约须属该品种；执行期限不得超计划期限。' }));

  const defaultDir = plan.hedgeDirection === 'BUY_HEDGE' ? 'BUY' : 'SELL';
  const rowsBox = ui.el('div');
  wrap.appendChild(rowsBox);
  function addRow() {
    const row = ui.el('div', { class: 'hg-toolbar' });
    const ins = ui.el('input', { type: 'text', placeholder: '合约代码', maxlength: 32 });
    const type = ui.el('select', {});
    type.appendChild(opt('FUTURES', '期货'));
    type.appendChild(opt('OPTION', '期权'));
    const dir = ui.el('select', {});
    dir.appendChild(opt('BUY', '买入'));
    dir.appendChild(opt('SELL', '卖出'));
    dir.value = defaultDir;
    const qty = ui.el('input', { type: 'text', inputmode: 'decimal', placeholder: '数量' });
    const deadline = ui.el('input', { type: 'date' });
    const del = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '移除' });
    del.addEventListener('click', () => { if (rowsBox.children.length > 1) rowsBox.removeChild(row); });
    row.appendChild(ui.field({ id: '', label: '合约', control: ins }));
    row.appendChild(ui.field({ id: '', label: '类型', control: type }));
    row.appendChild(ui.field({ id: '', label: '方向', control: dir }));
    row.appendChild(ui.field({ id: '', label: '数量', control: qty }));
    row.appendChild(ui.field({ id: '', label: '执行期限', control: deadline }));
    row.appendChild(del);
    row._ins = ins; row._type = type; row._dir = dir; row._qty = qty; row._deadline = deadline;
    rowsBox.appendChild(row);
  }
  const addBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '＋ 添加一条' });
  addBtn.addEventListener('click', addRow);
  addRow();
  wrap.appendChild(ui.el('div', { class: 'hg-form-actions' }, [addBtn]));

  const alertBox = ui.el('div', { role: 'alert' });
  wrap.appendChild(alertBox);
  const go = ui.el('button', { class: 'hg-btn', type: 'button', text: '提交分解' });
  go.addEventListener('click', async () => {
    ui.clear(alertBox);
    const plans = [];
    let ok = true;
    Array.prototype.forEach.call(rowsBox.children, (row) => {
      const ins = row._ins.value.trim(), qty = row._qty.value.trim(), deadline = row._deadline.value;
      if (!ins && !qty && !deadline) return;
      if (!ins) { ok = false; }
      if (!/^\d+(\.\d+)?$/.test(qty) || Number(qty) <= 0) { ok = false; }
      if (!deadline) { ok = false; }
      plans.push({ instrumentId: ins, instrumentType: row._type.value, direction: row._dir.value, planQty: qty, execDeadline: deadline });
    });
    if (!plans.length) { ui.clear(alertBox); alertBox.appendChild(ui.banner('请至少给出一个交易计划（合约/数量/执行期限齐备）。', 'warn')); return; }
    if (!ok) { ui.clear(alertBox); alertBox.appendChild(ui.banner('分解条目存在缺项或非法取值（合约、数量必填且数量 > 0，执行期限必填）。', 'warn')); return; }
    go.disabled = true; const orig = go.textContent; go.textContent = '提交中…';
    try {
      const inserted = await HG.api.post(HG.routes.PLAN.tradePlans(plan.id), { plans: plans }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('已分解 ' + (inserted || 0) + ' 条交易计划', 'ok');
      if (typeof reloadPlan === 'function') reloadPlan();
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      go.disabled = false; go.textContent = orig;
    }
  });
  wrap.appendChild(ui.el('div', { class: 'hg-form-actions' }, [go]));
  return wrap;
}

/* ------------------------------------------------------------------ *
 * 注册
 * ------------------------------------------------------------------ */

HG.pages = HG.pages || {};
HG.pages.plan = { renderList, renderNew, renderDetail };

// 顺序要紧：'/plan/new' 须先于 '/plan/:id' 注册（首个匹配生效）。
HG.router.register('/plan/new', renderNew);
HG.router.register('/plan', renderList);
HG.router.register('/plan/:id', renderDetail);

export default HG.pages.plan;
