/*
 * 套保计划页面（PLAN）—— 列表 + 详情。
 *
 * **字段名以已注册 Handler 为准（camelCase）**：
 *   GET  /plan/{id}              → PlanHandler.PlanView {id, planNo, subAccountId, planType, productId,
 *                                  hedgeDirection, planQty, unit, termStartDate, termEndDate, hedgeRatio,
 *                                  ratioMethod, status, quotaOccupied, currentDocVersion, executedQty,
 *                                  orderedQty, bindings:[{exposureId, coveredQty, status}]}
 *   GET  /plan/{id}/trade-plans  → **PageData 分页信封** {items:[TradePlanHandler.View {id, tradePlanNo,
 *                                  instrumentType, direction, planQty, executedQty, orderedQty,
 *                                  execDeadline, status, hedgePlanId, exposureId}], total, page, size}
 *                                  查询：page / size∈{10,20,50,100}（默认 20）/ sort=exec_deadline,asc|desc（默认升序）
 *                                  筛选：subAccountId / instrumentId / status / execDeadlineFrom / execDeadlineTo
 *
 * ⚠️ **`GET /plan`（列表）未注册**（PlanRoutes 仅注册 `POST /plan` 与 `GET /plan/{id}`）：
 *    `GET /plan` 命中 pathExists → **405**。`FR-PLAN-4` 的列表页因此**受阻**，已投 Q-FE-001 请示。
 *    本页对该阻塞**显式呈现**（并提供"探测真实响应"按钮留证），不伪装成已完成。
 *
 * 易用性 NFR-USE-2：详情页提供「下单」按钮，预填 计划ID / 子账户 / 品种 / 方向。
 */
import { HG } from '../core/ns.js';

const ui = HG.ui;
const F = HG.format;

/** 品种 id → 展示名（拉取一次，仅展示）。 */
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
function productName(id) { return productCache[String(id)] || ('#' + id); }

const PLAN_TYPE = { COMMODITY: '商品类套保', TRADE: '贸易类套保' };
const HEDGE_DIR = { BUY_HEDGE: '买入套保', SELL_HEDGE: '卖出套保' };
const PLAN_STATUS = { DRAFT: '草稿', PENDING_APPROVAL: '待审批', APPROVED: '已批准', FROZEN: '已冻结', COMPLETED: '已完成', TERMINATED: '已终止' };
const RATIO_METHOD = { SIMPLE: '简单比率法', MIN_VARIANCE: '最小方差法', CUSTOM_RULE: '自定义规则法' };
const TRADE_PLAN_STATUS = { PENDING: '待执行', EXECUTING: '执行中', COMPLETED: '已完成', CANCELLED: '已撤销' };

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
 * 列表（FR-PLAN-4）—— 后端未提供列表端点，显式登记阻塞
 * ------------------------------------------------------------------ */

function renderList(root) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '套保计划' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '计划由实货敞口出发编制、经审批后方可下单；每笔交易须绑定计划 ID。' }));

  root.appendChild(ui.banner(
    '<strong>接口未就绪（诚实登记）</strong>：后端 <code>PlanRoutes</code> 仅注册了 <code>POST /plan</code> 与 ' +
    '<code>GET /plan/{id}</code>，<strong>未注册 <code>GET /plan</code>（列表）</strong>；' +
    '按 <code>RequestDispatcher</code> 的分发规则，<code>GET /plan</code> 将命中 <code>pathExists</code> → <strong>405</strong>。' +
    '故 <code>FR-PLAN-4</code> 的列表页暂不可用；已投 <strong>Q-FE-001</strong> 请示补注册或改用其它口径。',
    'warn'
  ));

  // 依据实货敞口进入计划编制（POST /plan）；列表查询受阻，但详情可直达。
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '可用入口' }));
  const ul = ui.el('ul');
  ul.appendChild(ui.el('li', { html: '按计划 ID 直达详情：<code>#/plan/&lt;id&gt;</code>（<code>GET /plan/{id}</code> 已注册，可正常使用）。' }));
  ul.appendChild(ui.el('li', { html: '编制计划（<code>POST /plan</code>，须绑定至少一条实货敞口）——界面待计划列表/编制接口齐备后铺开。' }));
  ul.appendChild(ui.el('li', { html: '入口：<a href="#/spot/exposure">实货敞口</a>（计划编制的上游与唯一来源）。' }));
  card.appendChild(ul);
  root.appendChild(card);

  // 探测真实响应（留证）
  const probeCard = ui.el('div', { class: 'hg-card' });
  probeCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '探测真实响应（留证）' }));
  const probeBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: 'GET /plan 探测' });
  const probeOut = ui.el('div');
  probeBtn.addEventListener('click', async () => {
    ui.renderLoading(probeOut, '正在探测 GET /plan …');
    try {
      const data = await HG.api.get(HG.routes.NOT_REGISTERED.PLAN_LIST);
      ui.clear(probeOut);
      probeOut.appendChild(ui.el('div', { html: ui.banner('意外成功：后端已提供列表。请据此启用列表页。', 'warn') }));
      probeOut.appendChild(ui.el('pre', { text: JSON.stringify(data, null, 2).slice(0, 2000) }));
    } catch (err) {
      ui.clear(probeOut);
      probeOut.appendChild(ui.el('div', { html: ui.blockedHTML(err) }));
      probeOut.appendChild(ui.el('p', { class: 'hg-muted', text: '预期为 HTTP 405（方法不被允许）——即"路径存在但未注册 GET"的证据。' }));
    }
  });
  probeCard.appendChild(probeBtn);
  probeCard.appendChild(probeOut);
  root.appendChild(probeCard);
}

/* ------------------------------------------------------------------ *
 * 详情（FR-PLAN-5）
 * ------------------------------------------------------------------ */

function renderDetail(root, ctx) {
  const id = ctx.params.id;
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '套保计划详情' }));
  root.appendChild(ui.el('p', {}, [ui.el('a', { href: '#/plan', text: '← 返回计划列表（接口未就绪说明）' })]));

  const body = ui.el('div');
  root.appendChild(body);

  loadProductsOnce();
  async function load() {
    ui.renderLoading(body, '正在加载计划详情…');
    try { renderPlan(body, await HG.api.get(HG.routes.PLAN.detail(id))); }
    catch (err) { ui.renderError(body, err, load); }
  }
  load();
}

function renderPlan(box, d) {
  ui.clear(box);
  const canOrder = d.status === 'APPROVED';

  const head = ui.el('div', { class: 'hg-card' });
  const headBar = ui.el('div', { class: 'hg-toolbar' });
  headBar.style.justifyContent = 'space-between';
  headBar.appendChild(ui.el('h3', { class: 'hg-card__title', text: '计划 ' + F.esc(d.planNo) }));
  const orderBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: canOrder ? '下单（预填计划要素）' : '下单（仅已批准计划可用）', disabled: !canOrder });
  orderBtn.addEventListener('click', () => goOrder({ hedgePlanId: d.id, subAccountId: d.subAccountId, productId: d.productId, direction: d.hedgeDirection }));
  headBar.appendChild(orderBtn);
  head.appendChild(headBar);

  const dl = ui.el('dl', { class: 'hg-desc' });
  [['状态', PLAN_STATUS[d.status] || d.status],
   ['计划类型', PLAN_TYPE[d.planType] || d.planType],
   ['所属子账户', '#' + d.subAccountId],
   ['品种', productName(d.productId)],
   ['套保方向', HEDGE_DIR[d.hedgeDirection] || d.hedgeDirection],
   ['计划数量', F.qty(d.planQty) + ' ' + F.esc(d.unit || '')],
   ['已下达指令量', F.qty(d.orderedQty)],
   ['已成交数量', F.qty(d.executedQty)],
   ['剩余可下单量', F.qty(F.decSub(d.planQty, d.orderedQty))],
   ['计划期限', F.date(d.termStartDate) + ' ~ ' + F.date(d.termEndDate)],
   ['套保比率', F.pct(d.hedgeRatio) + '（' + F.esc(RATIO_METHOD[d.ratioMethod] || d.ratioMethod) + '）'],
   ['占用额度', d.quotaOccupied === 'Y' ? '占用中' : '未占用'],
   ['书面文件版本', 'v' + F.esc(d.currentDocVersion)]
  ].forEach((r) => { dl.appendChild(ui.el('dt', { text: r[0] })); dl.appendChild(ui.el('dd', { text: r[1] })); });
  head.appendChild(dl);
  box.appendChild(head);

  // 绑定敞口（PlanView.bindings）
  const bind = ui.el('div', { class: 'hg-card' });
  bind.appendChild(ui.el('h3', { class: 'hg-card__title', text: '绑定的实货敞口（追溯链连接点）' }));
  const binds = d.bindings || [];
  if (!binds.length) {
    bind.appendChild(ui.el('p', { class: 'hg-muted', text: '该计划未返回绑定敞口清单。' }));
  } else {
    bind.appendChild(ui.el('div', { html: ui.tableHTML([
      { title: '敞口 ID', render: (r) => `<a href="#/spot/exposure">#${F.esc(r.exposureId)}</a>` },
      { title: '被覆盖数量', num: true, render: (r) => F.qty(r.coveredQty) },
      { title: '绑定状态', render: (r) => ui.statusBadge(r.status) }
    ], binds) }));
    bind.appendChild(ui.el('p', { class: 'hg-muted', text: '注：当前 PlanView 的 BindingView 仅含 {exposureId, coveredQty, status}，无绑定时间；构成明细需敞口侧端点。' }));
  }
  box.appendChild(bind);

  // 交易计划（PageData 分页信封；FR-PLAN-15）
  renderTradePlans(box, d);

  // 未在 PlanView 中提供的区块，诚实登记
  box.appendChild(ui.el('div', { html: ui.banner(
    '<strong>待补区块</strong>：当前 <code>PlanView</code> <strong>不含</strong> 套保比率计算过程（<code>t_hedge_ratio_calc</code>）、审批链、书面文件正文。' +
    '这些属 <code>FR-PLAN-5</code> 的详情组成，需后端在详情中返回或提供对应端点；已投 <strong>Q-FE-001</strong> 登记。',
    'warn'
  ) }));
}

/* ------------------------------------------------------------------ *
 * 交易计划（FR-PLAN-15）—— 分页信封 + 排序 + 状态筛选
 *
 *   响应 data = **PageData** {items, total, page, size}（`T-103` 起，此前为 bare 数组）。
 *   size ∈ {10,20,50,100}（默认 20）；sort=exec_deadline,asc|desc（默认升序）。
 *   ⚠️ 服务端**默认已分页**：不传 size 只返回前 20 条，故本面板**始终显式传** page/size/sort。
 *   前端只做取数参数与展示，不做业务判定。
 * ------------------------------------------------------------------ */

/**
 * 归一化分页响应：**主路径走 PageData**；兼容旧「bare 数组」（服务端若回退，界面不崩）。
 * 返回值 {items,total,page,size}；缺位处按 fallback 兜底。
 */
function normalizeTradePlanPage(data, fallback) {
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

/** 交易计划分页面板：自带分页 / 排序 / 筛选状态，切换即重新取数。 */
function renderTradePlans(box, plan) {
  const state = {
    page: 1,
    size: HG.api.DEFAULT_PAGE_SIZE,
    sortField: 'exec_deadline',
    sortDir: 'asc',
    status: ''
  };

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '交易计划（可执行下单任务）' }));
  card.appendChild(ui.el('p', { class: 'hg-page__desc', text: '按 FR-PLAN-15：默认按执行期限升序；可切换排序方向与每页条数，并按状态筛选。' }));

  const bar = ui.el('div', { class: 'hg-toolbar' });

  const statusSel = ui.el('select', { id: 'tp-filter-status' });
  statusSel.appendChild(ui.el('option', { value: '', text: '全部状态' }));
  Object.keys(TRADE_PLAN_STATUS).forEach((k) => {
    statusSel.appendChild(ui.el('option', { value: k, text: TRADE_PLAN_STATUS[k] }));
  });
  bar.appendChild(ui.field({ id: 'tp-filter-status', label: '状态', control: statusSel }));

  const dirSel = ui.el('select', { id: 'tp-sort-dir' });
  dirSel.appendChild(ui.el('option', { value: 'asc', text: '执行期限：升序（先到期在前）' }));
  dirSel.appendChild(ui.el('option', { value: 'desc', text: '执行期限：降序（后到期在前）' }));
  bar.appendChild(ui.field({ id: 'tp-sort-dir', label: '排序', control: dirSel }));

  const sizeSel = ui.el('select', { id: 'tp-page-size' });
  HG.api.PAGE_SIZES.forEach((s) => sizeSel.appendChild(ui.el('option', { value: String(s), text: s + ' 条/页' })));
  bar.appendChild(ui.field({ id: 'tp-page-size', label: '每页条数', control: sizeSel }));

  const reloadBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  bar.appendChild(reloadBtn);
  card.appendChild(bar);

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

  /** 组装取数参数（page/size/sort 始终显式传，避免依赖默认 20 造成静默截断）。 */
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
      const data = await HG.api.get(HG.routes.PLAN.tradePlans(plan.id), query(page));
      const pg = normalizeTradePlanPage(data, state);
      state.page = pg.page;   // 以服务端返回为准
      state.size = pg.size;
      renderRows(pg);
    } catch (err) {
      // 错误态沿用既有渲染（阻断类含四项 ruleId/current/threshold/nextStep，经 errmsg）
      ui.renderError(body, err, () => load(state.page));
      ui.clear(pager);
    }
  }

  function renderRows(pg) {
    if (!pg.items.length) {
      // 空态：**不得**显示为"加载失败"
      const extra = pg.total > 0
        ? '（共 ' + pg.total + ' 条，当前第 ' + pg.page + ' 页无可显示记录）'
        : (state.status ? '（当前筛选：' + (TRADE_PLAN_STATUS[state.status] || state.status) + '）' : '');
      ui.renderEmpty(body, '该计划尚未分解出交易计划' + extra + '。');
      renderPager(pg);
      return;
    }

    const columns = [
      { title: '交易计划编号', render: (r) => F.esc(r.tradePlanNo) },
      { title: '合约', render: (r) => F.esc(r.instrumentId) + (r.instrumentType === 'OPTION' ? '（期权）' : '（期货）') },
      { title: '方向', render: (r) => F.esc(r.direction === 'BUY' ? '买入' : '卖出') },
      { title: '数量', num: true, render: (r) => F.qty(r.planQty) },
      { title: '剩余可下单量', num: true, render: (r) => F.qty(F.decSub(r.planQty, r.orderedQty)) },
      { title: '执行期限', render: (r) => F.date(r.execDeadline) },
      { title: '状态', render: (r) => ui.statusBadge(TRADE_PLAN_STATUS[r.status] || r.status) },
      { title: '操作', render: (r) => {
        const remaining = F.decSub(r.planQty, r.orderedQty);
        const atCap = remaining !== '' && (remaining.charAt(0) === '-' || remaining === '0');
        const disabled = atCap || r.status === 'COMPLETED' || r.status === 'CANCELLED';
        return `<button class="hg-btn hg-btn--sm" type="button" ${disabled ? 'disabled' : ''} data-tp='${F.esc(JSON.stringify({ hedgePlanId: plan.id, tradePlanId: r.id, subAccountId: plan.subAccountId, productId: plan.productId, direction: r.direction, instrumentId: r.instrumentId }))}'>${atCap ? '已达上限' : '下单'}</button>`;
      } }
    ];
    ui.clear(body);
    body.innerHTML = ui.tableHTML(columns, pg.items);   // 行数 = 本页 items 条数（末页不按 total%size 硬算）
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

HG.pages = HG.pages || {};
HG.pages.plan = { renderList, renderDetail };

HG.router.register('/plan', renderList);
HG.router.register('/plan/:id', renderDetail);

export default HG.pages.plan;
