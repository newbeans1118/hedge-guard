/*
 * 现货管理页面（SPOT）—— 现货录入 + 实货敞口。
 *
 * **字段名以已注册 Handler 的请求/视图类为准（camelCase）**（T-ARCH-004 §5.2「契约逐字对齐」）：
 *   POST /spot/purchase  → SpotContractHandler.PurchaseRequest {action, contractId, subAccountId, contractNo,
 *                          spotCategoryCode, totalQty, contractStartDate, contractEndDate, supplierName, voidReason}
 *   POST /spot/sales     → SpotContractHandler.SalesRequest    {subAccountId, contractNo, spotCategoryCode, totalQty,
 *                          contractStartDate, contractEndDate, customerName, hasDualDirection, buyDirectionQty}
 *   POST /spot/price     → SpotPriceHandler.PriceRequest       {subAccountId, productId, priceDate, spotPrice,
 *                          sourceType, sourceDesc, importFileName, priceUnit, overwriteConfirmed}
 *   GET  /spot/exposure?subAccountId= → **bare 数组** of ExposureView（camelCase；**要求具体子账户**）
 *   GET  /spot/exposure/{id}          → ExposureView（**不含构成明细**）
 *   POST /spot/exposure/recalc        → {exposureId}（展示侧重算，只告警+留痕，不回写）
 *   GET  /product                     → **bare 数组** of ProductView {id, productCode, productName, category}
 *
 * ⚠️ **不实现**「前端计算敞口」：`POST /spot/exposure` 要求提交方给出三分量与合计（exposureQty），
 *    与「前端不得做业务判定」（T-ARCH-004 §硬约束）及 `FR-SPOT-14`「系统自动计算」冲突 → 已投 Q-FE-001 请示。
 */
import { HG } from '../core/ns.js';

const ui = HG.ui;
const F = HG.format;

/** 品种 id → 展示名缓存（仅展示）。 */
const productCache = {};

/* ------------------------------------------------------------------ *
 * 通用：子账户 / 品种 下拉
 * ------------------------------------------------------------------ */

function subAccountSelect(id, required) {
  const sel = ui.el('select', { id: id, name: 'subAccountId', required: required ? true : false });
  sel.appendChild(ui.el('option', { value: '', text: required ? '请选择子账户' : '（不指定）' }));
  HG.session.state.subAccounts.forEach((s) => {
    sel.appendChild(ui.el('option', { value: s.id, text: (s.code ? s.code + ' ' : '') + (s.name || ('#' + s.id)) }));
  });
  const cur = HG.session.currentId();
  if (cur !== null && cur !== undefined) sel.value = String(cur);
  return sel;
}

function productSelect(id, required) {
  const sel = ui.el('select', { id: id, name: 'productId', required: required ? true : false });
  sel.appendChild(ui.el('option', { value: '', text: required ? '加载中…' : '（未映射 / 留空）' }));
  HG.api.get(HG.routes.PRODUCT.LIST).then((data) => {
    const items = Array.isArray(data) ? data : (data && data.items) || [];
    sel.innerHTML = '';
    sel.appendChild(ui.el('option', { value: '', text: required ? '请选择品种' : '（未映射 / 留空）' }));
    items.forEach((p) => {
      const label = (p.productCode ? p.productCode + ' ' : '') + (p.productName || ('#' + p.id));
      productCache[String(p.id)] = label;
      sel.appendChild(ui.el('option', { value: p.id, text: label }));
    });
    if (!items.length) sel.appendChild(ui.el('option', { value: '', disabled: true, text: '（无可选品种，请先在品种管理中维护）' }));
  }).catch((err) => {
    sel.innerHTML = '';
    sel.appendChild(ui.el('option', { value: '', text: '（品种列表加载失败：' + HG.errmsg.summary(err) + '）' }));
  });
  return sel;
}

/* ------------------------------------------------------------------ *
 * 分步表单（NFR-USE-1）
 * ------------------------------------------------------------------ */

function stepForm(opts) {
  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);

  const indicator = ui.el('ol', { class: 'hg-steps' });
  opts.steps.forEach((s, i) => indicator.appendChild(ui.el('li', { text: (i + 1) + '. ' + s.title })));
  form.appendChild(indicator);

  const panels = [];
  opts.steps.forEach((s, i) => {
    const panel = ui.el('div', { class: 'hg-step-panel' + (i === 0 ? '' : ' hg-hidden') });
    s.fields.forEach((f) => {
      panel.appendChild(ui.field({ id: f.control.id, label: f.label, required: f.required, help: f.help, control: f.control }));
      f.control.addEventListener('blur', () => validateField(f));
      f.control.addEventListener('input', () => { if (f.control.classList.contains('is-invalid')) validateField(f); });
      f.control.addEventListener('change', () => validateField(f));
    });
    panels.push(panel);
    form.appendChild(panel);
  });

  const actions = ui.el('div', { class: 'hg-form-actions' });
  const prev = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '上一步' });
  const next = ui.el('button', { class: 'hg-btn', type: 'button', text: '下一步' });
  actions.appendChild(prev); actions.appendChild(next);
  form.appendChild(actions);

  let index = 0;
  let finalRendered = false;

  function refresh() {
    Array.prototype.forEach.call(indicator.children, (li, i) => {
      li.classList.toggle('is-current', i === index);
      li.classList.toggle('is-done', i < index);
    });
  }
  function show(i) {
    index = Math.max(0, Math.min(opts.steps.length - 1, i));
    panels.forEach((p, k) => p.classList.toggle('hg-hidden', k !== index));
    prev.disabled = index === 0;
    if (index === opts.steps.length - 1) {
      next.classList.add('hg-hidden');
      if (!finalRendered) { opts.finalActions(actions, collect, alertBox); finalRendered = true; }
    } else {
      next.classList.remove('hg-hidden');
    }
    refresh();
  }
  function validateField(f) {
    if (!f.validate) return true;
    const res = f.validate(f.control.value, f.control);
    ui.setFieldError(f.control, res === true ? '' : res);
    return res === true;
  }
  function validateAll() {
    let ok = true;
    opts.steps.forEach((s) => s.fields.forEach((f) => { if (!validateField(f)) ok = false; }));
    return ok;
  }
  function collect() {
    const out = {};
    opts.steps.forEach((s) => s.fields.forEach((f) => {
      out[f.key] = f.control.type === 'checkbox' ? f.control.checked : f.control.value;
    }));
    return out;
  }

  next.addEventListener('click', () => { let ok = true; opts.steps[index].fields.forEach((f) => { if (!validateField(f)) ok = false; }); if (ok) show(index + 1); });
  prev.addEventListener('click', () => show(index - 1));
  show(0);
  return { form, alertBox, collect, validateAll, isValid: () => opts.steps.every((s) => s.fields.every((f) => !f.validate || validateField(f))) };
}

const req = (label) => (v) => (String(v || '').trim() ? true : `请填写${label}`);
const decimalPositive = (label) => (v) => {
  const s = String(v || '').trim();
  if (!s) return `请填写${label}`;
  if (!/^\d+(\.\d+)?$/.test(s)) return `${label}须为十进制数字（如 1000.0000）`;
  if (Number(s) <= 0) return `${label}必须大于 0`;
  return true;
};

/* ------------------------------------------------------------------ *
 * 采购 / 销售合同录入
 * ------------------------------------------------------------------ */

function contractForm(kind) {
  const isPurchase = kind === 'purchase';
  const path = isPurchase ? HG.routes.SPOT.PURCHASE : HG.routes.SPOT.SALES;
  const title = isPurchase ? '采购合同录入（买入方向敞口）' : '销售合同录入（卖出方向敞口）';

  const sub = subAccountSelect('fa-sub', true);
  const contractNo = ui.el('input', { id: 'fa-cno', type: 'text', maxlength: 32 });
  const category = ui.el('input', { id: 'fa-cat', type: 'text', maxlength: 32 });
  const totalQty = ui.el('input', { id: 'fa-total', type: 'text', inputmode: 'decimal' });
  const startDate = ui.el('input', { id: 'fa-start', type: 'date' });
  const endDate = ui.el('input', { id: 'fa-end', type: 'date' });
  const party = ui.el('input', { id: 'fa-party', type: 'text', maxlength: 128 });

  const steps = [
    { title: '归属与编号', fields: [
      { key: 'subAccountId', label: '子账户', required: true, help: '单据必须归属一个子账户（贯穿性要求，创建后不可静默变更）', control: sub },
      { key: 'contractNo', label: '合同编号', required: true, help: '同一子账户内唯一', control: contractNo, validate: req('合同编号') }
    ] },
    { title: '品类与数量', fields: [
      { key: 'spotCategoryCode', label: '现货品类编码', required: true, help: '需经品种-现货品类映射后参与套保；无映射时草稿可存、提交有效会被拒（10012）', control: category, validate: req('现货品类') },
      { key: 'totalQty', label: '合同总量', required: true, help: '字符串化十进制，如 1000.0000', control: totalQty, validate: decimalPositive('合同总量') }
    ] },
    { title: '期限与相对方', fields: [
      { key: 'contractStartDate', label: '合同起始日期', required: true, control: startDate, validate: (v) => (v ? true : '请选择起始日期') },
      { key: 'contractEndDate', label: '合同到期日期', required: true, control: endDate, validate: (v) => {
        if (!v) return '请选择到期日期';
        if (startDate.value && v < startDate.value) return '到期日期不得早于起始日期';
        return true;
      } },
      { key: isPurchase ? 'supplierName' : 'customerName', label: isPurchase ? '供应商名称' : '客户名称', required: false, help: '仅文本说明，系统不做供应商/客户管理', control: party }
    ] }
  ];

  if (!isPurchase) {
    const dual = ui.el('input', { id: 'fa-dual', type: 'checkbox' });
    const dualQty = ui.el('input', { id: 'fa-dualqty', type: 'text', inputmode: 'decimal' });
    dualQty.disabled = true;
    dual.addEventListener('change', () => { dualQty.disabled = !dual.checked; if (!dual.checked) ui.setFieldError(dualQty, ''); });
    steps.push({ title: '双向敞口（可选）', fields: [
      { key: 'hasDualDirection', label: '存在双向敞口', required: false, help: '勾选后须填买入方向数量，系统将生成两条方向不同的敞口（FR-SPOT-5 验收 3）', control: dual },
      { key: 'buyDirectionQty', label: '买入方向数量', required: false, help: '仅"存在双向敞口"时填写', control: dualQty, validate: (v) => (dual.checked ? decimalPositive('买入方向数量')(v) : true) }
    ] });
  }

  const api = {};
  function buildPayload(v, action) {
    if (isPurchase) {
      const p = { action: action, subAccountId: Number(v.subAccountId), contractNo: v.contractNo,
        spotCategoryCode: v.spotCategoryCode, totalQty: v.totalQty,
        contractStartDate: v.contractStartDate, contractEndDate: v.contractEndDate };
      if (v.supplierName) p.supplierName = v.supplierName;
      return p;
    }
    const p = { subAccountId: Number(v.subAccountId), contractNo: v.contractNo,
      spotCategoryCode: v.spotCategoryCode, totalQty: v.totalQty,
      contractStartDate: v.contractStartDate, contractEndDate: v.contractEndDate,
      hasDualDirection: v.hasDualDirection ? 'Y' : 'N' };
    if (v.customerName) p.customerName = v.customerName;
    if (v.hasDualDirection) p.buyDirectionQty = v.buyDirectionQty;
    return p;
  }

  async function submitWith(action) {
    ui.clear(api.alertBox);
    const validAll = api.built.validateAll();
    if (!validAll) return;
    if (!api.built.collect().subAccountId) { ui.setFieldError(sub, '请选择子账户'); return; }
    const payload = buildPayload(api.built.collect(), action);
    api.submit.disabled = true; const orig = api.submit.textContent; api.submit.textContent = '提交中…';
    try {
      const id = await HG.api.post(path, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast(action === 'ACTIVATE' ? ('已提交为有效（id=' + id + '）') : ('已保存为草稿（id=' + id + '，不计入实货敞口）'), 'ok');
      HG.router.navigate(action === 'ACTIVATE' ? '/spot/exposure' : '/spot');
    } catch (err) {
      api.alertBox.innerHTML = ui.blockedHTML(err);
      window.scrollTo(0, 0);
    } finally {
      api.submit.disabled = false; api.submit.textContent = orig;
    }
  }

  const built = stepForm({
    steps,
    finalActions(actions, collect, alertBox) {
      api.alertBox = alertBox;
      if (isPurchase) {
        const draft = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '保存草稿（CREATE）' });
        const active = ui.el('button', { class: 'hg-btn', type: 'button', text: '提交为有效（ACTIVATE）' });
        draft.addEventListener('click', () => submitWith('CREATE'));
        active.addEventListener('click', () => submitWith('ACTIVATE'));
        api.submit = active;
        actions.appendChild(draft); actions.appendChild(active);
      } else {
        const create = ui.el('button', { class: 'hg-btn', type: 'button', text: '创建销售合同（CREATE）' });
        create.addEventListener('click', () => submitWith('CREATE'));
        api.submit = create;
        actions.appendChild(create);
      }
    }
  });
  api.built = built;

  built.form.appendChild(ui.el('p', { class: 'hg-muted', text: '提交前的映射校验、格式校验与合规校验一律以后端返回为准（10001 / 10012 等），阻断信息会完整展示规则编号与阈值。' }));

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: title }));
  card.appendChild(built.form);
  return card;
}

/* ------------------------------------------------------------------ *
 * 现货价录入（FR-SPOT-10）
 * ------------------------------------------------------------------ */

function priceForm() {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '手工录入现货价（FR-SPOT-10）' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '现货价只接受外部输入（手工 / 批量粘贴 / CSV），系统不抓取、不订阅任何第三方资讯源（R-SPOT-6）。' }));

  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);

  const sub = subAccountSelect('fp-sub', true);
  const product = productSelect('fp-product', true);
  const priceDate = ui.el('input', { id: 'fp-date', type: 'date' });
  const spotPrice = ui.el('input', { id: 'fp-price', type: 'text', inputmode: 'decimal' });
  const unit = ui.el('input', { id: 'fp-unit', type: 'text', value: 'CNY/TON', maxlength: 16 });
  const desc = ui.el('input', { id: 'fp-desc', type: 'text', maxlength: 500 });
  const overwrite = ui.el('input', { id: 'fp-over', type: 'checkbox' });

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'fp-sub', label: '录入归属子账户', required: true, help: '现货价为全账户共享市场数据；此处仅记录录入归属', control: sub }));
  grid.appendChild(ui.field({ id: 'fp-product', label: '品种', required: true, control: product }));
  grid.appendChild(ui.field({ id: 'fp-date', label: '价格日期', required: true, help: '不得晚于当日（后端校验）', control: priceDate }));
  grid.appendChild(ui.field({ id: 'fp-price', label: '现货价', required: true, help: '必须为正数', control: spotPrice }));
  grid.appendChild(ui.field({ id: 'fp-unit', label: '价格单位', required: true, control: unit }));
  grid.appendChild(ui.field({ id: 'fp-desc', label: '来源说明补充', required: false, control: desc }));
  grid.appendChild(ui.field({ id: 'fp-over', label: '确认覆盖同品种同日价格', required: false, help: '存在同口径价格时必须勾选，否则后端回 10009（R-SPOT-3：不得静默覆盖）', control: overwrite }));
  form.appendChild(grid);

  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '保存' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!sub.value) { ui.setFieldError(sub, '请选择子账户'); ok = false; }
    if (!product.value) { ui.setFieldError(product, '请选择品种'); ok = false; }
    if (!priceDate.value) { ui.setFieldError(priceDate, '请选择价格日期'); ok = false; }
    const pv = decimalPositive('现货价')(spotPrice.value); ui.setFieldError(spotPrice, pv === true ? '' : pv); if (pv !== true) ok = false;
    if (!unit.value) { ui.setFieldError(unit, '请填写价格单位'); ok = false; }
    if (!ok) return;

    submit.disabled = true; submit.textContent = '保存中…';
    try {
      const id = await HG.api.post(HG.routes.SPOT.PRICE, {
        subAccountId: Number(sub.value), productId: Number(product.value), priceDate: priceDate.value,
        spotPrice: spotPrice.value, sourceType: 'MANUAL', priceUnit: unit.value,
        sourceDesc: desc.value || null, overwriteConfirmed: overwrite.checked
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('现货价已保存（id=' + id + '）', 'ok');
      spotPrice.value = ''; desc.value = ''; overwrite.checked = false;
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = '保存';
    }
  });

  card.appendChild(form);
  return card;
}

/* ------------------------------------------------------------------ *
 * 录入页
 * ------------------------------------------------------------------ */

const TABS = [
  { key: 'purchase', label: '采购合同', build: () => contractForm('purchase') },
  { key: 'sales', label: '销售合同', build: () => contractForm('sales') },
  { key: 'price', label: '现货价', build: () => priceForm() }
];

function renderEntry(root, ctx) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '现货录入' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '分步录入现货数据；实货敞口由现货数据单向推导（R-SPOT-1：禁止由期货成交反向创建敞口）。' }));

  const tabBar = ui.el('div', { class: 'hg-tabs', role: 'tablist' });
  const panel = ui.el('div');
  let active = (ctx && ctx.query && ctx.query.tab) || 'purchase';
  if (!TABS.some((t) => t.key === active)) active = 'purchase';

  function select(key) {
    Array.prototype.forEach.call(tabBar.children, (b) => {
      b.classList.toggle('is-active', b.dataset.key === key);
      b.setAttribute('aria-selected', b.dataset.key === key ? 'true' : 'false');
    });
    ui.clear(panel);
    panel.appendChild(TABS.filter((t) => t.key === key)[0].build());
  }
  TABS.forEach((t) => {
    const b = ui.el('button', { type: 'button', role: 'tab', text: t.label, onclick: () => HG.router.navigate('/spot?tab=' + t.key) });
    b.dataset.key = t.key;
    tabBar.appendChild(b);
  });
  root.appendChild(tabBar);
  root.appendChild(panel);
  select(active);
}

/* ------------------------------------------------------------------ *
 * 实货敞口页（FR-SPOT-15）
 * ------------------------------------------------------------------ */

const DIRECTION_LABEL = { BUY: '买入', SELL: '卖出' };
const STATUS_LABEL = { PENDING_HEDGE: '待套保', PARTIALLY_COVERED: '部分覆盖', COVERED: '已覆盖', CLOSED: '已关闭' };
const CALC_MODE_LABEL = { COMBINED: '组合口径', CONTRACT_UNEXECUTED: '合同未执行量', INVENTORY_HELD: '库存持有量', PRODUCTION_PLAN: '生产计划需求量' };

function renderExposure(root) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '实货敞口' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '期现一体的起点：实货敞口由现货数据单向推导，是套保计划的唯一输入。' }));

  root.appendChild(ui.banner(
    '<strong>数据来源与口径</strong>：组合口径 = 已签合同未执行量 + 库存持有量 + 生产计划需求量。' +
    '三分量与合计同时展示；买卖方向不抵销。后端 `GET /spot/exposure` **要求具体子账户**且返回**未分页列表**；' +
    '「汇总」视图由前端按已授权子账户**逐账户拉取后拼接展示**（只拼接、不做任何计算）。'
  ));

  root.appendChild(ui.banner(
    '<strong>计算入口说明</strong>：`POST /spot/exposure` 要求提交方给出三分量与合计（exposureQty）。' +
    '若由前端计算即违反「前端不得做业务判定」；此冲突已投 <strong>Q-FE-001</strong> 请示技术负责人。' +
    '本页因此<strong>不提供"计算敞口"按钮</strong>，仅提供查询 / 明细 / 单条展示侧重算。',
    'warn'
  ));

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '敞口列表（子账户 × 品种 × 方向）' }));
  const bar = ui.el('div', { class: 'hg-toolbar' });
  const refresh = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  bar.appendChild(ui.el('span', { class: 'hg-muted', text: '当前视图：' }));
  const viewLabel = ui.el('strong');
  bar.appendChild(viewLabel);
  bar.appendChild(refresh);
  card.appendChild(bar);
  const body = ui.el('div');
  card.appendChild(body);
  root.appendChild(card);

  const detailCard = ui.el('div', { class: 'hg-card hg-hidden' });
  root.appendChild(detailCard);

  function updateViewLabel() {
    viewLabel.textContent = HG.session.isAggregate()
      ? '汇总（' + HG.session.state.subAccounts.length + ' 个已授权子账户，逐账户拼接）'
      : (subAccountLabel(HG.session.currentId()));
  }

  async function load() {
    updateViewLabel();
    ui.renderLoading(body, '正在加载实货敞口…');
    detailCard.classList.add('hg-hidden');
    try {
      const rows = await fetchRows();
      renderRows(rows);
    } catch (err) {
      ui.renderError(body, err, load);
    }
  }

  async function fetchRows() {
    const cur = HG.session.currentId();
    if (cur !== null) return normalizeRows(await HG.api.get(HG.routes.SPOT.EXPOSURE, { subAccountId: cur }));
    const subs = HG.session.state.subAccounts;
    if (!subs.length) return [];
    const out = [];
    for (let i = 0; i < subs.length; i++) {
      try {
        const part = normalizeRows(await HG.api.get(HG.routes.SPOT.EXPOSURE, { subAccountId: subs[i].id }));
        out.push.apply(out, part);
      } catch (e) {
        if (HG.errmsg.isUnauthenticated(e)) throw e;
        // 单账户失败不影响其余（如未授权 10004）：记录为占位行
        out.push({ _error: true, subAccountId: subs[i].id, _err: e });
      }
    }
    return out;
  }

  function normalizeRows(data) {
    const items = Array.isArray(data) ? data : (data && data.items) || [];
    return items;
  }

  function renderRows(rows) {
    if (!rows.length) { ui.renderEmpty(body, '当前视图下没有敞口快照。请由现货数据计算后查询（见上方说明）。'); return; }
    const mapped = rows.filter((r) => !r._error && Number(r.productId) > 0);
    const unmapped = rows.filter((r) => !r._error && Number(r.productId) === 0);
    const errored = rows.filter((r) => r._error);

    let html = '';
    errored.forEach((r) => {
      html += `<div class="hg-banner hg-banner--warn">子账户 ${F.esc(subAccountLabel(r.subAccountId))} 读取失败：${F.esc(HG.errmsg.summary(r._err))}</div>`;
    });
    const cols = [
      { title: '子账户', render: (r) => F.esc(subAccountLabel(r.subAccountId)) },
      { title: '品种', render: (r) => F.esc(productName(r.productId)) },
      { title: '现货品类', render: (r) => F.esc(r.spotCategoryCode) },
      { title: '方向', render: (r) => F.esc(DIRECTION_LABEL[r.direction] || r.direction) },
      { title: '口径', render: (r) => F.esc(CALC_MODE_LABEL[r.calcMode] || r.calcMode) },
      { title: '合同未执行量', num: true, render: (r) => F.qty(r.contractUnexecutedQty) },
      { title: '库存持有量', num: true, render: (r) => F.qty(r.inventoryQty) },
      { title: '生产计划需求量', num: true, render: (r) => F.qty(r.productionPlanQty) },
      { title: '合计', num: true, render: (r) => '<strong>' + F.qty(r.exposureQty) + '</strong>' },
      { title: '单位', render: (r) => F.esc(r.unit) },
      { title: '已覆盖', num: true, render: (r) => F.qty(r.coveredQty) },
      { title: '状态', render: (r) => ui.statusBadge(STATUS_LABEL[r.status] || r.status) },
      { title: '操作', render: (r) => `<button class="hg-btn hg-btn--ghost hg-btn--sm" type="button" data-detail="${F.esc(r.id)}">明细</button>` +
        `<button class="hg-btn hg-btn--ghost hg-btn--sm" type="button" data-recalc="${F.esc(r.id)}">重算</button>` }
    ];
    if (mapped.length) html += ui.tableHTML(cols, mapped);
    else if (!errored.length) html += '<p class="hg-muted">（本页无已映射敞口行）</p>';

    if (unmapped.length) {
      html += '<h4 style="margin:16px 0 6px">未映射清单（按现货品类分别留行，不参与敞口计算与计划绑定）</h4>';
      html += ui.tableHTML([
        { title: '子账户', render: (r) => F.esc(subAccountLabel(r.subAccountId)) },
        { title: '现货品类', render: (r) => F.esc(r.spotCategoryCode) },
        { title: '方向', render: (r) => F.esc(DIRECTION_LABEL[r.direction] || r.direction) },
        { title: '说明', render: () => '无品种映射（productId=0），请先在品种管理建立映射（FR-PRODUCT-10 验收 2）' }
      ], unmapped);
    }
    ui.clear(body);
    body.innerHTML = html;
    Array.prototype.forEach.call(body.querySelectorAll('[data-detail]'), (b) => b.addEventListener('click', () => loadDetail(b.getAttribute('data-detail'))));
    Array.prototype.forEach.call(body.querySelectorAll('[data-recalc]'), (b) => b.addEventListener('click', () => doRecalc(b.getAttribute('data-recalc'))));
  }

  async function doRecalc(id) {
    const ok = await ui.confirm('对敞口 #' + F.esc(id) + ' 执行展示侧重算？<br><span class="hg-muted">后端行为：只告警 + 留痕，<strong>不回写</strong>落库值。</span>', { okText: '重算' });
    if (!ok) return;
    try {
      const result = await HG.api.post(HG.routes.SPOT.EXPOSURE_RECALC, { exposureId: Number(id) }, { idempotencyKey: HG.api.newIdempotencyKey() });
      const keys = result && typeof result === 'object' ? Object.keys(result) : [];
      ui.toast('重算完成' + (keys.length ? '：' + keys.map((k) => k + '=' + result[k]).join(', ') : ''), 'ok');
      loadDetail(id);
    } catch (err) {
      detailCard.classList.remove('hg-hidden');
      detailCard.innerHTML = '';
      detailCard.appendChild(ui.el('div', { html: ui.blockedHTML(err) }));
    }
  }

  async function loadDetail(id) {
    detailCard.classList.remove('hg-hidden');
    detailCard.innerHTML = '';
    const box = ui.el('div');
    detailCard.appendChild(box);
    ui.renderLoading(box, '正在加载敞口详情…');
    try { renderDetail(box, await HG.api.get(HG.routes.SPOT.exposureDetail(id))); }
    catch (err) { ui.renderError(box, err, () => loadDetail(id)); }
  }

  function renderDetail(box, d) {
    ui.clear(box);
    box.appendChild(ui.el('h3', { class: 'hg-card__title', text: '敞口明细 #' + F.esc(d.id) + '（快照 ' + F.esc(d.snapshotId || '—') + '）' }));
    box.appendChild(ui.el('div', { class: 'hg-components' }, [
      metric('合同未执行量', F.qty(d.contractUnexecutedQty)),
      metric('库存持有量', F.qty(d.inventoryQty)),
      metric('生产计划需求量', F.qty(d.productionPlanQty)),
      metric('敞口合计', F.qty(d.exposureQty), true)
    ]));
    const dl = ui.el('dl', { class: 'hg-desc' });
    [['子账户', subAccountLabel(d.subAccountId)],
     ['品种', Number(d.productId) === 0 ? '（未映射：' + F.esc(d.spotCategoryCode) + '）' : productName(d.productId)],
     ['方向', DIRECTION_LABEL[d.direction] || d.direction],
     ['计算口径', CALC_MODE_LABEL[d.calcMode] || d.calcMode],
     ['单位', F.esc(d.unit)],
     ['敞口金额', d.priceMissingFlag === 'Y' ? '缺价，未计算' : F.money(d.exposureAmount)],
     ['价格时点(priceAsOf)', F.date(d.priceAsOf) || '—'],
     ['非当日价', d.nonCurrentPriceFlag === 'Y' ? '是（沿用最近一期，已标注）' : '否'],
     ['已覆盖数量', F.qty(d.coveredQty)],
     ['状态', STATUS_LABEL[d.status] || d.status]
    ].forEach((r) => { dl.appendChild(ui.el('dt', { text: r[0] })); dl.appendChild(ui.el('dd', { text: r[1] })); });
    box.appendChild(dl);
    box.appendChild(ui.el('p', { class: 'hg-muted', text: '注：当前 GET /spot/exposure/{id} 的视图不含「构成明细」（ExposureView 无明细字段），故此处不展示逐单据贡献；构成明细的查询端点见 Q-FE-001。' }));
    box.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
      ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '关闭', onclick: () => detailCard.classList.add('hg-hidden') })
    ]));
  }

  function metric(label, value, isTotal) {
    return ui.el('div', { class: 'hg-metric' + (isTotal ? ' hg-metric--total' : '') }, [
      ui.el('div', { class: 'hg-metric__label', text: label }),
      ui.el('div', { class: 'hg-metric__value', text: value })
    ]);
  }

  refresh.addEventListener('click', load);
  HG.session.onChange(load);
  load();
}

function subAccountLabel(id) {
  const hit = HG.session.state.subAccounts.filter((s) => String(s.id) === String(id))[0];
  return hit ? ((hit.code ? hit.code + ' ' : '') + (hit.name || '')) : ('#' + id);
}
function productName(id) {
  if (id === null || id === undefined || Number(id) === 0) return '（未映射）';
  return productCache[String(id)] || ('#' + id);
}

HG.pages = HG.pages || {};
HG.pages.spot = { renderEntry, renderExposure, contractForm, priceForm };

HG.router.register('/spot', renderEntry);
HG.router.register('/spot/exposure', renderExposure);

export default HG.pages.spot;
