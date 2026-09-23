/*
 * 现货管理页面（SPOT）—— 现货录入 + 实货敞口。
 *
 * **字段名以已注册 Handler 的请求/视图类为准（camelCase）**（T-ARCH-004 §5.2「契约逐字对齐」）：
 *   POST /spot/purchase       → SpotContractHandler.PurchaseRequest {action, contractId, subAccountId, contractNo,
 *                               spotCategoryCode, totalQty, contractStartDate, contractEndDate, supplierName, voidReason}
 *                               action ∈ {CREATE（默认）/ ACTIVATE / VOID / DELETE_DRAFT}
 *   POST /spot/sales          → SpotContractHandler.SalesRequest {subAccountId, contractNo, spotCategoryCode,
 *                               totalQty, contractStartDate, contractEndDate, customerName, hasDualDirection, buyDirectionQty}
 *   POST /spot/production     → ProductionPlanHandler.Request {subAccountId, planNo, planPeriodStart, planPeriodEnd,
 *                               status?, lines:[{lineType, productId, quantity, unit}]}
 *                               lineType ∈ {FINISHED / RAW_MATERIAL}；结果 {id, overlapFlag, overlapHint}
 *   POST /spot/inventory      → SpotStockHandler.StockRequest（库存）{subAccountId, productId, inventoryType,
 *                               quantity, negativeConfirmed, unit, valuation, recordDate, exposureDirection, remark}
 *   POST /spot/wip            → SpotStockHandler.StockRequest（在制品）{subAccountId, productId, quantity,
 *                               completionRatio, productionPlanId, unit, exposureDate, exposureDirection}
 *   POST /spot/price          → SpotPriceHandler.PriceRequest {subAccountId, productId, priceDate, spotPrice,
 *                               sourceType, sourceDesc, importFileName, priceUnit, overwriteConfirmed}
 *   GET  /spot/price?productId=&date=&purpose= → SpotPriceHandler.PriceView（单点取价）
 *   POST /spot/price/import   → SpotPriceImportHandler.Request {sourceType, subAccountId, importFileName?,
 *                               overwriteConfirmed?, lines:[String]} → 汇总 {inserted, updated, total, sourceType}
 *   GET  /spot/price/export?productId=&from=&to= → text/csv（FR-SPOT-13 同源序列 / FR-SPOT-19 导出）
 *   GET  /spot/exposure?subAccountId= → **bare 数组** of ExposureView（camelCase；**要求具体子账户**）
 *   GET  /spot/exposure/{id}          → ExposureView（**不含构成明细**）
 *   POST /spot/exposure/recalc        → {exposureId}（展示侧重算，只告警+留痕，不回写）
 *   GET  /spot/exposure/export?subAccountId=&asOfDate= → text/csv（FR-SPOT-18）
 *   POST /spot/doc/sub-account        → SpotDocSubAccountHandler.Request {docType, docId, targetSubAccountId}（FR-SPOT-16）
 *   GET  /product?page=&size=         → **PageData 分页信封** of ProductView {id, productCode, productName, category}
 *
 * ⚠️ **不实现**「前端计算敞口」：`POST /spot/exposure` 要求提交方给出三分量与合计（exposureQty），
 *    与「前端不得做业务判定」（T-ARCH-004 §硬约束）及 `FR-SPOT-14`「系统自动计算」冲突 → 已投 Q-FE-001 请示。
 *
 * ⚠️ **未决（后端端点缺失，不自行改后端）**：
 *    - FR-SPOT-2 / FR-SPOT-6（修改采购 / 销售合同）：SPOT 模块无 UPDATE 端点；
 *    - FR-SPOT-4 / FR-SPOT-7（查询采购 / 销售合同列表）：SPOT 模块无 GET 列表端点；
 *    - FR-SPOT-15（敞口构成明细）：GET /spot/exposure/{id} 的 ExposureView 无明细字段；
 *    - FR-SPOT-17 验收 3（补充来源说明）：无更新 sourceDesc 的端点（来源类型/录入人/录入时间为系统自动记录，只读）。
 *    以上均以「未决清单」形式如实登记，不伪造入口。
 *
 * 纪律：零构建链；只经 HG.api；金额/数量一律字符串口径（HG.format，禁 Number 参与展示运算）；
 *       失败统一经 HG.errmsg（阻断四项）；任何取数界面必配 加载 / 空 / 错误 三态。
 */
import { HG } from '../core/ns.js';
import '../core/routes.js';

const ui = HG.ui;
const F = HG.format;
const R = HG.routes.SPOT;

/* ------------------------------------------------------------------ *
 * 取值域与展示标签（以 Handler / SpotService 实测为准）
 * ------------------------------------------------------------------ */
const DIRECTION_LABEL = { BUY: '买入', SELL: '卖出' };
const STATUS_LABEL = { PENDING_HEDGE: '待套保', PARTIALLY_COVERED: '部分覆盖', COVERED: '已覆盖', CLOSED: '已关闭' };
const CALC_MODE_LABEL = {
  COMBINED: '组合口径', CONTRACT_UNEXECUTED: '合同未执行量',
  INVENTORY_HELD: '库存持有量', PRODUCTION_PLAN: '生产计划需求量'
};
const INVENTORY_TYPE = { RAW_MATERIAL: '原材料', FINISHED: '产成品', WIP: '在制品' };
const PRODUCTION_LINE_TYPE = { FINISHED: '产成品', RAW_MATERIAL: '所需原料' };
const PRODUCTION_STATUS = { SCHEDULED: '已排产', ADJUSTING: '调整中', CLOSED: '已关闭' };
const PRICE_SOURCE_LABEL = { MANUAL: '手工录入', BATCH_PASTE: '批量粘贴', CSV_IMPORT: 'CSV 导入' };
const DOC_TYPE = { PURCHASE_CONTRACT: '采购合同', SALES_CONTRACT: '销售合同', PRODUCTION_PLAN: '生产计划' };
const SAMPLE_MIN = 30; // FR-SPOT-13 验收 3 / R-SPOT-8：最小方差法样本门槛（交易日）

/* 实货敞口页的「子账户切换 ⇒ 刷新」订阅：模块级持有退订句柄，
 * 保证任一时刻至多 1 个活跃订阅（见 renderExposure 尾部）。 */
let exposureSessionOff = null;

/* ------------------------------------------------------------------ *
 * 通用：品种加载（一次拉全量，多下拉共享）+ 子账户 / 品种 下拉
 * ------------------------------------------------------------------ */

/** 品种 id → 展示名缓存（仅展示）。 */
const productCache = {};
/** 共享的品种全量加载 Promise（避免每个下拉各发一次请求）。 */
let productsPromise = null;

/** 拉取全量品种（分页信封，逐页拼接；不参与业务判定，仅用于下拉展示）。 */
function loadProducts() {
  if (productsPromise) return productsPromise;
  productsPromise = (async () => {
    const size = 100;
    let page = 1;
    const out = [];
    for (let guard = 0; guard < 50; guard++) {
      const data = await HG.api.get(HG.routes.PRODUCT.LIST, { page: page, size: size });
      const items = Array.isArray(data) ? data : ((data && data.items) || []);
      items.forEach((p) => {
        const label = (p.productCode ? p.productCode + ' ' : '') + (p.productName || ('#' + p.id));
        productCache[String(p.id)] = label;
        out.push(p);
      });
      const total = data && typeof data.total === 'number' ? data.total : out.length;
      if (!items.length || out.length >= total || Array.isArray(data)) break;
      page += 1;
    }
    return out;
  })();
  productsPromise.catch(() => { productsPromise = null; }); // 失败允许重试
  return productsPromise;
}

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
  const placeholder = ui.el('option', { value: '', text: required ? '加载中…' : '（未映射 / 留空）' });
  sel.appendChild(placeholder);
  loadProducts().then((items) => {
    sel.innerHTML = '';
    sel.appendChild(ui.el('option', { value: '', text: required ? '请选择品种' : '（未映射 / 留空）' }));
    items.forEach((p) => {
      sel.appendChild(ui.el('option', { value: p.id, text: productCache[String(p.id)] }));
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
const reqSelect = (label) => (v) => (String(v || '').trim() ? true : `请选择${label}`);
const decimalPositive = (label) => (v) => {
  const s = String(v || '').trim();
  if (!s) return `请填写${label}`;
  if (!/^\d+(\.\d+)?$/.test(s)) return `${label}须为十进制数字（如 1000.0000）`;
  if (Number(s) <= 0) return `${label}必须大于 0`;
  return true;
};
/** 十进制数字（允许 0 与负号；不参与运算，仅形态校验）。 */
const decimalAny = (label) => (v) => {
  const s = String(v || '').trim();
  if (!s) return `请填写${label}`;
  if (!/^-?\d+(\.\d+)?$/.test(s)) return `${label}须为十进制数字（如 1000.0000）`;
  return true;
};
/** 完工比例 [0,1]（R-SPOT-4）。 */
const ratioField = (label) => (v) => {
  const s = String(v || '').trim();
  if (!s) return `请填写${label}`;
  if (!/^\d+(\.\d+)?$/.test(s)) return `${label}须为 0–1 之间的小数（如 0.5）`;
  if (Number(s) < 0 || Number(s) > 1) return `${label}必须在 [0,1] 内`;
  return true;
};

/* ------------------------------------------------------------------ *
 * 采购 / 销售合同录入（FR-SPOT-1 / FR-SPOT-5）
 * ------------------------------------------------------------------ */

function contractForm(kind) {
  const isPurchase = kind === 'purchase';
  const path = isPurchase ? R.PURCHASE : R.SALES;
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
 * 合同操作：作废有效合同 / 删除草稿（FR-SPOT-3；R-SPOT-9）
 * ------------------------------------------------------------------ */

function contractOpsPanel() {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '合同作废 / 草稿删除（FR-SPOT-3）' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '有效合同只能作废（保留追溯链），草稿合同可物理删除。作废前后端校验是否已被生效套保计划引用——被引用时拒绝并提示先终止相关计划（FR-PLAN-17）。' }));

  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);

  const action = ui.el('select', { id: 'co-action' });
  action.appendChild(ui.el('option', { value: 'VOID', text: '作废有效合同（VOID）' }));
  action.appendChild(ui.el('option', { value: 'DELETE_DRAFT', text: '删除草稿合同（DELETE_DRAFT）' }));
  const contractId = ui.el('input', { id: 'co-id', type: 'number', min: '1', inputmode: 'numeric' });
  const reason = ui.el('input', { id: 'co-reason', type: 'text', maxlength: 200 });

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'co-action', label: '操作', required: true, control: action }));
  grid.appendChild(ui.field({ id: 'co-id', label: '合同 ID', required: true, help: 'CREATE 返回的 id', control: contractId }));
  grid.appendChild(ui.field({ id: 'co-reason', label: '作废原因', required: false, help: '作废（VOID）时必填——缺失将被后端拒绝（FR-SPOT-3 验收 3）', control: reason }));
  form.appendChild(grid);

  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '执行' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  function syncReason() {
    const need = action.value === 'VOID';
    reason.disabled = !need;
    if (!need) ui.setFieldError(reason, '');
  }
  action.addEventListener('change', syncReason);
  syncReason();

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!contractId.value) { ui.setFieldError(contractId, '请填写合同 ID'); ok = false; }
    if (action.value === 'VOID' && !String(reason.value || '').trim()) { ui.setFieldError(reason, '作废须填写作废原因'); ok = false; }
    if (!ok) return;

    const isVoid = action.value === 'VOID';
    const confirmed = await ui.confirm(
      (isVoid ? '确认作废' : '确认删除草稿') + '合同 #' + F.esc(contractId.value) + '？' +
      '<br><span class="hg-muted">' + (isVoid
        ? '作废后敞口归零；合同仍可在列表中查询（不物理删除）。已被生效计划引用时会被后端拒绝。'
        : '草稿删除为物理删除；已产生过敞口或被引用过的单据不得删除（R-SPOT-9）。') + '</span>',
      { okText: isVoid ? '作废' : '删除', danger: true });
    if (!confirmed) return;

    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const payload = { action: action.value, contractId: Number(contractId.value) };
      if (isVoid) payload.voidReason = reason.value;
      const id = await HG.api.post(R.PURCHASE, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      alertBox.appendChild(ui.banner((isVoid ? '合同已作废（id=' + F.esc(id) + '），其贡献的敞口归零。' : '草稿已删除（id=' + F.esc(id) + '）。'), 'ok'));
      contractId.value = ''; reason.value = '';
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  card.appendChild(form);
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
  grid.appendChild(ui.field({ id: 'fp-sub', label: '录入归属子账户', required: true, help: '现货价为全账户共享市场数据；此处仅记录录入归属（CH-003，不参与加总）', control: sub }));
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
      const id = await HG.api.post(R.PRICE, {
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
 * 现货价序列（FR-SPOT-13 查询 / FR-SPOT-17 来源标注只读 / FR-SPOT-19 导出）
 *
 * 后端无独立 JSON 列表端点——序列查询与导出同源于 `GET /spot/price/export`（CSV，按
 * price_date 升序、全量不截断）；本页在前端解析 CSV 后**只作展示**（行数计数 / 升序显示 /
 * 来源标注只读），不含任何业务判定。CSV 解析按 RFC 4180 处理引号 / 逗号 / 换行（FR-SPOT-19 验收 6）。
 * ------------------------------------------------------------------ */

/** 最小 CSV 解析（RFC 4180：引号包裹、`""` 转义、字段内逗号/换行）。 */
function parseCsv(text) {
  const s = String(text).replace(/^\uFEFF/, '');
  const rows = [];
  let row = [];
  let field = '';
  let inQuotes = false;
  let i = 0;
  while (i < s.length) {
    const ch = s.charAt(i);
    if (inQuotes) {
      if (ch === '"') {
        if (s.charAt(i + 1) === '"') { field += '"'; i += 2; continue; }
        inQuotes = false; i++; continue;
      }
      field += ch; i++; continue;
    }
    if (ch === '"') { inQuotes = true; i++; continue; }
    if (ch === ',') { row.push(field); field = ''; i++; continue; }
    if (ch === '\r' || ch === '\n') {
      if (ch === '\r' && s.charAt(i + 1) === '\n') i++;
      row.push(field); rows.push(row); row = []; field = ''; i++; continue;
    }
    field += ch; i++;
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row); }
  // 丢弃末尾空行
  while (rows.length && rows[rows.length - 1].length === 1 && rows[rows.length - 1][0] === '') rows.pop();
  return rows;
}

function triggerBlobDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'spot-prices.csv';
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.setTimeout(() => { URL.revokeObjectURL(url); }, 1000);
}

function seriesPanel() {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '现货价序列（FR-SPOT-13 / FR-SPOT-17 / FR-SPOT-19）' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '按品种与日期区间查询；结果按日期升序，逐行展示来源标注（来源类型 / 录入人 / 录入时间，均为系统自动记录、界面只读）。导出为同源 CSV。' }));

  const alertBox = ui.el('div', { role: 'alert' });
  card.appendChild(alertBox);

  const product = productSelect('sp-product', false);
  const from = ui.el('input', { id: 'sp-from', type: 'date' });
  const to = ui.el('input', { id: 'sp-to', type: 'date' });

  const bar = ui.el('div', { class: 'hg-grid-2' });
  bar.appendChild(ui.field({ id: 'sp-product', label: '品种', required: false, help: '留空 = 全部品种', control: product }));
  bar.appendChild(ui.field({ id: 'sp-from', label: '起始日期', required: false, control: from }));
  bar.appendChild(ui.field({ id: 'sp-to', label: '截止日期', required: false, control: to }));
  card.appendChild(bar);

  const query = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询序列' });
  const exportBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '导出 CSV' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [query, exportBtn]));

  const body = ui.el('div');
  card.appendChild(body);

  function currentQuery() {
    return { productId: product.value || '', from: from.value || '', to: to.value || '' };
  }

  async function doQuery() {
    ui.clear(alertBox);
    ui.renderLoading(body, '正在查询现货价序列…');
    try {
      const res = await HG.api.download(R.PRICE_EXPORT, currentQuery());
      const rows = parseCsv(await res.blob.text());
      renderSeries(rows);
    } catch (err) {
      ui.renderError(body, err, doQuery);
    }
  }

  function renderSeries(rows) {
    // 每次重查都会重入本函数：先清掉上一次 doQuery 留下的 loading 态与旧结果，
    // 否则 renderLoading 写入的加载圈会与结果并存在 body 中（本函数此前全程只 appendChild）
    ui.clear(body);
    if (!rows.length) { ui.renderEmpty(body, '无可查询价格。'); return; }
    const header = rows[0];
    const idx = (name) => header.indexOf(name);
    const data = rows.slice(1);
    if (!data.length) { ui.renderEmpty(body, '无可查询价格（该区间内没有现货价记录）。'); return; }

    // 仅用于「样本不足」提示（FR-SPOT-13 验收 3 / R-SPOT-8）：按交易日（去重日期）计数
    const dates = {};
    data.forEach((r) => { const d = r[idx('price_date')]; if (d) dates[d] = true; });
    const sampleDays = Object.keys(dates).length;

    if (sampleDays < SAMPLE_MIN) {
      body.appendChild(ui.banner('样本不足（需 ≥' + SAMPLE_MIN + ' 个交易日）：当前 ' + sampleDays +
        ' 个交易日。不得静默改用简单比率法（R-SPOT-8）。', 'warn'));
    } else {
      body.appendChild(ui.banner('样本 ' + sampleDays + ' 个交易日（满足 ≥' + SAMPLE_MIN + ' 门槛）。', 'ok'));
    }

    const sorted = data.slice().sort((a, b) => {
      const da = a[idx('price_date')] || ''; const db = b[idx('price_date')] || '';
      return da < db ? -1 : (da > db ? 1 : 0); // 展示层升序（后端已按 price_date 升序）
    });

    const cols = [
      { title: '品种', render: (r) => F.esc(productName(r[idx('product_id')])) },
      { title: '日期', render: (r) => F.esc(r[idx('price_date')]) },
      { title: '现货价', num: true, render: (r) => F.decimal(r[idx('spot_price')]) },
      { title: '单位', render: (r) => F.esc(r[idx('price_unit')]) },
      { title: '来源类型', render: (r) => F.esc(PRICE_SOURCE_LABEL[r[idx('source_type')]] || r[idx('source_type')]) },
      { title: '来源说明', render: (r) => F.esc(r[idx('source_desc')]) },
      { title: '导入文件名', render: (r) => F.esc(r[idx('import_file_name')]) },
      { title: '录入人', render: (r) => F.esc(r[idx('operator_id')]) },
      { title: '录入时间', render: (r) => F.esc(F.dateTime(r[idx('entry_time')])) }
    ];
    // 表体插入时保留告警横幅：用临时容器拼装表格 HTML
    const tableHost = ui.el('div');
    tableHost.innerHTML = ui.tableHTML(cols, sorted);
    body.appendChild(tableHost);
    body.appendChild(ui.el('p', { class: 'hg-muted', text: '来源类型 / 录入人 / 录入时间由系统自动记录，界面上只读、不可修改（FR-SPOT-17 验收 1、2）。' }));
  }

  async function doExport() {
    ui.clear(alertBox);
    const orig = exportBtn.textContent; exportBtn.disabled = true; exportBtn.textContent = '导出中…';
    try {
      const res = await HG.api.download(R.PRICE_EXPORT, currentQuery());
      triggerBlobDownload(res.blob, res.filename || 'spot-prices.csv');
      ui.toast('现货价序列已导出', 'ok');
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      exportBtn.disabled = false; exportBtn.textContent = orig;
    }
  }

  query.addEventListener('click', doQuery);
  exportBtn.addEventListener('click', doExport);

  renderSeries([]); // 初始空态（提示先查询）
  return card;
}

/* ------------------------------------------------------------------ *
 * 批量粘贴 / CSV 导入（FR-SPOT-11 / FR-SPOT-12）
 * ------------------------------------------------------------------ */

/** 把后端 10019 逐行清单（并入 message，以 "\n- " 分隔）渲染为摘要 + 逐条列表。 */
function importProblemsHTML(err) {
  const msg = String((err && err.message) || '');
  const parts = msg.split(/\n\s*-\s*/);
  const summary = (parts.shift() || '').trim();
  const problems = parts.map((p) => p.trim()).filter((p) => p !== '');
  if (!problems.length) return ui.blockedHTML(err);
  const li = problems.map((p) => `<li>${F.esc(p)}</li>`).join('');
  return '<div class="hg-blocked">' +
    `<div class="hg-blocked__msg">${F.esc(summary || '导入文件校验失败')}</div>` +
    '<div class="hg-muted">逐行问题清单（行号从 1 起；已全部回滚、未产生部分写入）：</div>' +
    `<ul style="margin:6px 0 0 18px">${li}</ul>` +
    (err && err.traceId ? `<div class="hg-muted">traceId：<span class="hg-mono">${F.esc(err.traceId)}</span></div>` : '') +
    '</div>';
}

function importPanel() {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '批量导入现货价（FR-SPOT-11 / FR-SPOT-12）' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '逐行解析并全量预校验后一次性提交——任一行不合法则整体不导入，并定位到行号给出原因。批量粘贴无表头（品种编码、日期、现货价）；CSV 首行须为表头。' }));

  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);

  const sub = subAccountSelect('ip-sub', true);
  const sourceType = ui.el('select', { id: 'ip-source' });
  sourceType.appendChild(ui.el('option', { value: 'BATCH_PASTE', text: '批量粘贴（BATCH_PASTE）' }));
  sourceType.appendChild(ui.el('option', { value: 'CSV_IMPORT', text: 'CSV 导入（CSV_IMPORT）' }));
  const file = ui.el('input', { id: 'ip-file', type: 'file', accept: '.csv,text/csv' });
  const fileName = ui.el('input', { id: 'ip-name', type: 'text', maxlength: 128, placeholder: '如 prices.csv' });
  const area = ui.el('textarea', { id: 'ip-text', rows: '8', placeholder: '每行：品种编码,日期,现货价（如 HT001,2026-09-18,3790）' });
  const overwrite = ui.el('input', { id: 'ip-over', type: 'checkbox' });

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'ip-sub', label: '录入归属子账户', required: true, help: '现货价为全账户共享市场数据；仅记录录入归属（CH-003）', control: sub }));
  grid.appendChild(ui.field({ id: 'ip-source', label: '导入方式', required: true, control: sourceType }));
  grid.appendChild(ui.field({ id: 'ip-file', label: 'CSV 文件', required: false, help: 'CSV 导入方式下可直接选文件（自动读取文本）', control: file }));
  grid.appendChild(ui.field({ id: 'ip-name', label: '导入文件名', required: false, help: 'CSV 导入时必填（写入来源标注，FR-SPOT-12 验收 3）', control: fileName }));
  form.appendChild(grid);
  form.appendChild(ui.field({ id: 'ip-text', label: '或粘贴内容', required: false, help: 'CSV：首行为表头（品种编码,日期,现货价）；批量粘贴：无表头', control: area }));

  const owWrap = ui.field({ id: 'ip-over', label: '确认覆盖同品种同日价格', required: false, help: '存在同口径价格时须勾选，否则整体拒绝（R-SPOT-3）', control: overwrite });
  form.appendChild(owWrap);

  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '开始导入' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  function isCsv() { return sourceType.value === 'CSV_IMPORT'; }
  function syncSource() {
    fileName.disabled = !isCsv();
    if (!isCsv() && !fileName.value) fileName.value = '';
  }
  sourceType.addEventListener('change', syncSource);
  syncSource();

  file.addEventListener('change', () => {
    const f = file.files && file.files[0];
    if (!f) return;
    fileName.value = f.name;
    const reader = new FileReader();
    reader.onload = () => { area.value = String(reader.result || ''); };
    reader.onerror = () => { alertBox.innerHTML = ui.blockedHTML({ code: -1, message: '读取文件失败，请改用粘贴方式。' }); };
    reader.readAsText(f, 'utf-8');
  });

  /** 按行切分（仅去末尾换行产生的空行；保留中间空行由后端逐行定位）。 */
  function splitLines(text) {
    const s = String(text).replace(/^\uFEFF/, '');
    const lines = s.split(/\r\n|\n/);
    if (lines.length && lines[lines.length - 1] === '') lines.pop();
    return lines;
  }

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    if (!sub.value) { ui.setFieldError(sub, '请选择子账户'); return; }
    if (isCsv() && !String(fileName.value || '').trim()) { ui.setFieldError(fileName, 'CSV 导入须填写导入文件名'); return; }

    const lines = splitLines(area.value || '');
    const hasContent = lines.some((l) => String(l).trim() !== '');
    if (!hasContent) { ui.setFieldError(area, '请选择文件或粘贴内容（全为空行不会产生记录）'); return; }
    ui.setFieldError(area, '');

    const payload = {
      sourceType: sourceType.value,
      subAccountId: Number(sub.value),
      lines: lines,
      overwriteConfirmed: overwrite.checked
    };
    if (isCsv()) payload.importFileName = fileName.value;

    submit.disabled = true; const orig = submit.textContent; submit.textContent = '导入中…';
    try {
      const res = await HG.api.post(R.PRICE_IMPORT, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      const inserted = res && typeof res.inserted === 'number' ? res.inserted : 0;
      const updated = res && typeof res.updated === 'number' ? res.updated : 0;
      const total = res && typeof res.total === 'number' ? res.total : (inserted + updated);
      alertBox.appendChild(ui.banner('导入成功：新增 ' + inserted + ' 条、覆盖 ' + updated + ' 条（共 ' + total + ' 条）。来源标注=' +
        (PRICE_SOURCE_LABEL[payload.sourceType] || payload.sourceType) + '。', 'ok'));
      area.value = '';
    } catch (err) {
      alertBox.innerHTML = importProblemsHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  card.appendChild(form);
  return card;
}

/* ------------------------------------------------------------------ *
 * 生产计划录入（FR-SPOT-8）
 * ------------------------------------------------------------------ */

function productionPanel() {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '生产计划录入（FR-SPOT-8）' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '产成品行导出卖出敞口（SELL）、所需原料行导出买入敞口（BUY）。计划期与既有计划重叠时给出重复提示但不阻断。' }));

  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);

  const sub = subAccountSelect('pp-sub', true);
  const planNo = ui.el('input', { id: 'pp-no', type: 'text', maxlength: 32 });
  const start = ui.el('input', { id: 'pp-start', type: 'date' });
  const end = ui.el('input', { id: 'pp-end', type: 'date' });
  const status = ui.el('select', { id: 'pp-status' });
  Object.keys(PRODUCTION_STATUS).forEach((k) => status.appendChild(ui.el('option', { value: k, text: PRODUCTION_STATUS[k] })));

  const head = ui.el('div', { class: 'hg-grid-2' });
  head.appendChild(ui.field({ id: 'pp-sub', label: '子账户', required: true, control: sub }));
  head.appendChild(ui.field({ id: 'pp-no', label: '生产计划编号', required: true, help: '同一子账户内唯一', control: planNo }));
  head.appendChild(ui.field({ id: 'pp-start', label: '计划期起', required: true, control: start }));
  head.appendChild(ui.field({ id: 'pp-end', label: '计划期止', required: true, control: end }));
  head.appendChild(ui.field({ id: 'pp-status', label: '状态', required: false, help: '缺省"已排产"', control: status }));
  form.appendChild(head);

  form.appendChild(ui.el('h4', { text: '计划行（至少一行）' }));
  const linesHost = ui.el('div');
  form.appendChild(linesHost);
  const addLine = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '+ 添加计划行' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, addLine));

  let rowSeq = 0;
  function addRow() {
    const rid = 'pp-line-' + (++rowSeq);
    const lineType = ui.el('select', { id: rid + '-type' });
    Object.keys(PRODUCTION_LINE_TYPE).forEach((k) => lineType.appendChild(ui.el('option', { value: k, text: PRODUCTION_LINE_TYPE[k] })));
    const product = productSelect(rid + '-prod', true);
    const quantity = ui.el('input', { id: rid + '-qty', type: 'text', inputmode: 'decimal' });
    const unit = ui.el('input', { id: rid + '-unit', type: 'text', maxlength: 16, value: 'TON' });
    const row = ui.el('div', { class: 'hg-grid-2 hg-plan-line' });
    row.appendChild(ui.field({ id: rid + '-type', label: '行类型', required: true, control: lineType }));
    row.appendChild(ui.field({ id: rid + '-prod', label: '品种（须已映射）', required: true, control: product }));
    row.appendChild(ui.field({ id: rid + '-qty', label: '数量', required: true, help: '字符串化十进制，> 0', control: quantity }));
    row.appendChild(ui.field({ id: rid + '-unit', label: '单位', required: true, control: unit }));
    const remove = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '移除本行' });
    remove.addEventListener('click', () => { if (linesHost.children.length > 1) linesHost.removeChild(row); else ui.setFieldError(quantity, '至少保留一行'); });
    row.appendChild(ui.el('div', { class: 'hg-form-actions' }, remove));
    row._fields = { lineType, product, quantity, unit };
    linesHost.appendChild(row);
  }
  addLine.addEventListener('click', addRow);
  addRow();

  function collectLines() {
    const out = [];
    let ok = true;
    Array.prototype.forEach.call(linesHost.children, (row) => {
      const f = row._fields;
      const q = decimalPositive('数量')(f.quantity.value); ui.setFieldError(f.quantity, q === true ? '' : q); if (q !== true) ok = false;
      if (!f.product.value) { ui.setFieldError(f.product, '请选择品种'); ok = false; }
      if (!String(f.unit.value || '').trim()) { ui.setFieldError(f.unit, '请填写单位'); ok = false; }
      out.push({ lineType: f.lineType.value, productId: Number(f.product.value), quantity: f.quantity.value, unit: f.unit.value });
    });
    return ok ? out : null;
  }

  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '提交生产计划' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!sub.value) { ui.setFieldError(sub, '请选择子账户'); ok = false; }
    if (!String(planNo.value || '').trim()) { ui.setFieldError(planNo, '请填写生产计划编号'); ok = false; }
    if (!start.value) { ui.setFieldError(start, '请选择计划期起'); ok = false; }
    if (!end.value) { ui.setFieldError(end, '请选择计划期止'); ok = false; }
    if (start.value && end.value && end.value < start.value) { ui.setFieldError(end, '计划期止不得早于起始'); ok = false; }
    const lines = collectLines();
    if (lines === null) ok = false;
    if (!ok) return;

    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const res = await HG.api.post(R.PRODUCTION, {
        subAccountId: Number(sub.value), planNo: planNo.value,
        planPeriodStart: start.value, planPeriodEnd: end.value,
        status: status.value, lines: lines
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      const id = res && res.id !== undefined ? res.id : '';
      if (res && res.overlapFlag === 'Y') {
        alertBox.appendChild(ui.banner('生产计划已创建（id=' + F.esc(id) + '）。<strong>重复提示</strong>：' +
          F.esc(res.overlapHint || '计划期与既有计划重叠，请确认是否重复录入（FR-SPOT-8 验收 3）。'), 'warn'));
      } else {
        alertBox.appendChild(ui.banner('生产计划已创建（id=' + F.esc(id) + '）。', 'ok'));
      }
      planNo.value = '';
      linesHost.innerHTML = ''; addRow();
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  card.appendChild(form);
  return card;
}

/* ------------------------------------------------------------------ *
 * 在制品录入（FR-SPOT-8；R-SPOT-4 完工比例折算）
 * ------------------------------------------------------------------ */

function wipPanel() {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '在制品录入（FR-SPOT-8）' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '在制品按「数量 × 完工比例」折算风险敞口（R-SPOT-4）；完工比例须在 [0,1] 内。' }));

  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);

  const sub = subAccountSelect('wp-sub', true);
  const product = productSelect('wp-product', true);
  const qty = ui.el('input', { id: 'wp-qty', type: 'text', inputmode: 'decimal' });
  const ratio = ui.el('input', { id: 'wp-ratio', type: 'text', inputmode: 'decimal' });
  const unit = ui.el('input', { id: 'wp-unit', type: 'text', maxlength: 16, value: 'TON' });
  const dir = ui.el('select', { id: 'wp-dir' });
  Object.keys(DIRECTION_LABEL).forEach((k) => dir.appendChild(ui.el('option', { value: k, text: DIRECTION_LABEL[k] })));
  const recordDate = ui.el('input', { id: 'wp-date', type: 'date' });
  const planId = ui.el('input', { id: 'wp-plan', type: 'number', min: '1', inputmode: 'numeric' });

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'wp-sub', label: '子账户', required: true, control: sub }));
  grid.appendChild(ui.field({ id: 'wp-product', label: '品种', required: true, control: product }));
  grid.appendChild(ui.field({ id: 'wp-qty', label: '在制品数量', required: true, help: '字符串化十进制，≥ 0', control: qty }));
  grid.appendChild(ui.field({ id: 'wp-ratio', label: '完工比例', required: true, help: '0–1 的小数（如 0.5 表示 50%）', control: ratio }));
  grid.appendChild(ui.field({ id: 'wp-unit', label: '单位', required: true, control: unit }));
  grid.appendChild(ui.field({ id: 'wp-dir', label: '敞口方向', required: true, control: dir }));
  grid.appendChild(ui.field({ id: 'wp-date', label: '记录日期', required: true, control: recordDate }));
  grid.appendChild(ui.field({ id: 'wp-plan', label: '关联生产计划 ID', required: false, help: '可空', control: planId }));
  form.appendChild(grid);

  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '保存在制品' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!sub.value) { ui.setFieldError(sub, '请选择子账户'); ok = false; }
    if (!product.value) { ui.setFieldError(product, '请选择品种'); ok = false; }
    const qv = decimalAny('在制品数量')(qty.value); ui.setFieldError(qty, qv === true ? '' : qv); if (qv !== true) ok = false;
    if (qv === true && Number(qty.value) < 0) { ui.setFieldError(qty, '在制品数量须 ≥ 0'); ok = false; }
    const rv = ratioField('完工比例')(ratio.value); ui.setFieldError(ratio, rv === true ? '' : rv); if (rv !== true) ok = false;
    if (!String(unit.value || '').trim()) { ui.setFieldError(unit, '请填写单位'); ok = false; }
    if (!recordDate.value) { ui.setFieldError(recordDate, '请选择记录日期'); ok = false; }
    if (!ok) return;

    submit.disabled = true; const orig = submit.textContent; submit.textContent = '保存中…';
    try {
      const payload = { subAccountId: Number(sub.value), productId: Number(product.value),
        quantity: qty.value, completionRatio: ratio.value, unit: unit.value,
        exposureDirection: dir.value, recordDate: recordDate.value };
      if (planId.value) payload.productionPlanId = Number(planId.value);
      const id = await HG.api.post(R.WIP, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      alertBox.appendChild(ui.banner('在制品已保存（id=' + F.esc(id) + '）。', 'ok'));
      qty.value = ''; ratio.value = '';
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  card.appendChild(form);
  return card;
}

/* ------------------------------------------------------------------ *
 * 库存台账录入（FR-SPOT-9）
 * ------------------------------------------------------------------ */

function inventoryPanel() {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '库存台账录入（FR-SPOT-9）' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '库存数量不得为负；录入负值须二次确认（可能为录入错误或账实不符）。本页仅维护敞口相关字段，不做仓库出入库作业（R-SPOT-7）。' }));

  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);

  const sub = subAccountSelect('iv-sub', true);
  const product = productSelect('iv-product', true);
  const type = ui.el('select', { id: 'iv-type' });
  Object.keys(INVENTORY_TYPE).forEach((k) => type.appendChild(ui.el('option', { value: k, text: INVENTORY_TYPE[k] })));
  const qty = ui.el('input', { id: 'iv-qty', type: 'text', inputmode: 'decimal' });
  const unit = ui.el('input', { id: 'iv-unit', type: 'text', maxlength: 16, value: 'TON' });
  const valuation = ui.el('input', { id: 'iv-val', type: 'text', inputmode: 'decimal' });
  const dir = ui.el('select', { id: 'iv-dir' });
  Object.keys(DIRECTION_LABEL).forEach((k) => dir.appendChild(ui.el('option', { value: k, text: DIRECTION_LABEL[k] })));
  const recordDate = ui.el('input', { id: 'iv-date', type: 'date' });
  const remark = ui.el('input', { id: 'iv-remark', type: 'text', maxlength: 200 });
  const negConfirmed = ui.el('input', { id: 'iv-neg', type: 'checkbox' });
  negConfirmed.disabled = true;

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'iv-sub', label: '子账户', required: true, control: sub }));
  grid.appendChild(ui.field({ id: 'iv-product', label: '品种', required: true, control: product }));
  grid.appendChild(ui.field({ id: 'iv-type', label: '库存类型', required: true, control: type }));
  grid.appendChild(ui.field({ id: 'iv-qty', label: '数量', required: true, help: '字符串化十进制；负值须二次确认', control: qty }));
  grid.appendChild(ui.field({ id: 'iv-unit', label: '单位', required: true, control: unit }));
  grid.appendChild(ui.field({ id: 'iv-val', label: '计价单价', required: false, help: '可空', control: valuation }));
  grid.appendChild(ui.field({ id: 'iv-dir', label: '推导方向', required: true, help: '原材料通常为买入、产成品通常为卖出', control: dir }));
  grid.appendChild(ui.field({ id: 'iv-date', label: '记录日期', required: true, control: recordDate }));
  grid.appendChild(ui.field({ id: 'iv-remark', label: '备注', required: false, control: remark }));
  grid.appendChild(ui.field({ id: 'iv-neg', label: '负库存已二次确认', required: false, help: '由提交时的确认弹窗自动置位', control: negConfirmed }));
  form.appendChild(grid);

  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '保存库存' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  // 负值 → 取消勾选（每次修改数量后重置，须重新确认）
  qty.addEventListener('input', () => { if (Number(qty.value) >= 0) { negConfirmed.checked = false; ui.setFieldError(qty, ''); } });

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!sub.value) { ui.setFieldError(sub, '请选择子账户'); ok = false; }
    if (!product.value) { ui.setFieldError(product, '请选择品种'); ok = false; }
    const qv = decimalAny('数量')(qty.value); ui.setFieldError(qty, qv === true ? '' : qv); if (qv !== true) ok = false;
    if (!String(unit.value || '').trim()) { ui.setFieldError(unit, '请填写单位'); ok = false; }
    if (!recordDate.value) { ui.setFieldError(recordDate, '请选择记录日期'); ok = false; }
    if (valuation.value) { const vv = decimalAny('计价单价')(valuation.value); ui.setFieldError(valuation, vv === true ? '' : vv); if (vv !== true) ok = false; }
    if (!ok) return;

    const negative = Number(qty.value) < 0;
    if (negative) {
      const confirmed = await ui.confirm('库存数量为负值（' + F.esc(qty.value) + '）！<br><span class="hg-muted">负库存可能为录入错误或账实不符，系统将告警并标注异常。确认继续？（FR-SPOT-9 验收 2）</span>',
        { okText: '确认录入负库存', danger: true });
      if (!confirmed) return;
      negConfirmed.checked = true;
    } else {
      negConfirmed.checked = false;
    }

    submit.disabled = true; const orig = submit.textContent; submit.textContent = '保存中…';
    try {
      const payload = { subAccountId: Number(sub.value), productId: Number(product.value),
        inventoryType: type.value, quantity: qty.value, unit: unit.value,
        exposureDirection: dir.value, recordDate: recordDate.value,
        negativeConfirmed: negConfirmed.checked ? 'Y' : 'N' };
      if (valuation.value) payload.valuation = valuation.value;
      if (remark.value) payload.remark = remark.value;
      const id = await HG.api.post(R.INVENTORY, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      alertBox.appendChild(ui.banner('库存已保存（id=' + F.esc(id) + '）。' + (negative ? ' 已按负值告警并留痕。' : ''), 'ok'));
      qty.value = ''; valuation.value = ''; remark.value = ''; negConfirmed.checked = false;
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  card.appendChild(form);
  return card;
}

/* ------------------------------------------------------------------ *
 * 单据子账户归属变更（FR-SPOT-16）
 * ------------------------------------------------------------------ */

function docSubAccountPanel() {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '单据子账户归属变更（FR-SPOT-16）' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '子账户归属在单据创建时必填；变更仅允许在敞口未被任何生效计划引用时执行，变更后须重算敞口。现货价为全账户共享数据，其子账户仅为录入归属，不在本变更范围（CH-003）。' }));

  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);

  const docType = ui.el('select', { id: 'ds-type' });
  Object.keys(DOC_TYPE).forEach((k) => docType.appendChild(ui.el('option', { value: k, text: DOC_TYPE[k] })));
  const docId = ui.el('input', { id: 'ds-id', type: 'number', min: '1', inputmode: 'numeric' });
  const target = subAccountSelect('ds-target', true);

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'ds-type', label: '单据类型', required: true, control: docType }));
  grid.appendChild(ui.field({ id: 'ds-id', label: '单据 ID', required: true, control: docId }));
  grid.appendChild(ui.field({ id: 'ds-target', label: '目标子账户', required: true, help: '须存在且启用；调用者须对源、目标子账户均可见', control: target }));
  form.appendChild(grid);

  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '变更归属' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!docId.value) { ui.setFieldError(docId, '请填写单据 ID'); ok = false; }
    if (!target.value) { ui.setFieldError(target, '请选择目标子账户'); ok = false; }
    if (!ok) return;

    const confirmed = await ui.confirm('确认将' + F.esc(DOC_TYPE[docType.value] || docType.value) + ' #' + F.esc(docId.value) +
      ' 的子账户归属变更为「' + F.esc(target.options[target.selectedIndex].text) + '」？' +
      '<br><span class="hg-muted">变更后须重算敞口；已被生效计划引用时会被后端拒绝。</span>', { okText: '确认变更' });
    if (!confirmed) return;

    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const res = await HG.api.post(R.DOC_SUB_ACCOUNT, {
        docType: docType.value, docId: Number(docId.value), targetSubAccountId: Number(target.value)
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      const from = res && res.fromSubAccountId !== undefined ? res.fromSubAccountId : '—';
      const to = res && res.toSubAccountId !== undefined ? res.toSubAccountId : target.value;
      alertBox.appendChild(ui.banner('归属已变更：' + F.esc(subAccountLabel(from)) + ' → ' + F.esc(subAccountLabel(to)) +
        '。' + (res && res.recomputeRequired ? ' <strong>请重算敞口</strong>（R-SPOT-5：新快照由 /spot/exposure 生成）。' : ''), 'ok'));
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
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
  { key: 'price', label: '现货价录入', build: () => priceForm() },
  { key: 'series', label: '价格序列', build: () => seriesPanel() },
  { key: 'import', label: '批量导入', build: () => importPanel() },
  { key: 'production', label: '生产计划', build: () => productionPanel() },
  { key: 'wip', label: '在制品', build: () => wipPanel() },
  { key: 'inventory', label: '库存台账', build: () => inventoryPanel() },
  { key: 'contractOps', label: '合同作废', build: () => contractOpsPanel() },
  { key: 'docSub', label: '归属变更', build: () => docSubAccountPanel() }
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
 * 实货敞口页（FR-SPOT-15 查询 / FR-SPOT-18 导出）
 * ------------------------------------------------------------------ */

function renderExposure(root, ctx) {
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
  bar.appendChild(ui.el('a', { class: 'hg-btn hg-btn--ghost hg-btn--sm', href: '#/spot?tab=docSub', text: '归属变更（FR-SPOT-16）' }));
  card.appendChild(bar);

  // FR-SPOT-18 导出（仅按具体子账户；含可选基准日）
  const exportBar = ui.el('div', { class: 'hg-toolbar' });
  exportBar.appendChild(ui.el('span', { class: 'hg-muted', text: '导出（FR-SPOT-18）：' }));
  const asOf = ui.el('input', { id: 'ex-asof', type: 'date', 'aria-label': '计算基准日期（可空）' });
  exportBar.appendChild(asOf);
  const exportBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '导出敞口 CSV' });
  exportBar.appendChild(exportBtn);
  card.appendChild(exportBar);
  const exportAlert = ui.el('div', { role: 'alert' });
  card.appendChild(exportAlert);

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
    if (cur !== null) return normalizeRows(await HG.api.get(R.EXPOSURE, { subAccountId: cur }));
    const subs = HG.session.state.subAccounts;
    if (!subs.length) return [];
    const out = [];
    for (let i = 0; i < subs.length; i++) {
      try {
        const part = normalizeRows(await HG.api.get(R.EXPOSURE, { subAccountId: subs[i].id }));
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
      const result = await HG.api.post(R.EXPOSURE_RECALC, { exposureId: Number(id) }, { idempotencyKey: HG.api.newIdempotencyKey() });
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
    try { renderDetail(box, await HG.api.get(R.exposureDetail(id))); }
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

  async function doExport() {
    ui.clear(exportAlert);
    const cur = HG.session.currentId();
    if (cur === null) {
      exportAlert.appendChild(ui.banner('导出须指定具体子账户（后端仅按 subAccountId 导出并校可见性，FR-SPOT-18）；请在顶部将视图切换为某一子账户后重试。', 'warn'));
      return;
    }
    const orig = exportBtn.textContent; exportBtn.disabled = true; exportBtn.textContent = '导出中…';
    try {
      const q = { subAccountId: cur };
      if (asOf.value) q.asOfDate = asOf.value;
      const res = await HG.api.download(R.EXPOSURE_EXPORT, q);
      triggerBlobDownload(res.blob, res.filename || 'exposures.csv');
      ui.toast('敞口已导出', 'ok');
    } catch (err) {
      exportAlert.innerHTML = ui.blockedHTML(err);
    } finally {
      exportBtn.disabled = false; exportBtn.textContent = orig;
    }
  }

  exportBtn.addEventListener('click', doExport);
  refresh.addEventListener('click', load);
  // 子账户切换须刷新本页（功能需求，不可删）。但 registry 每次进入该路由都会重新执行本函数，
  // 故先退订上一次闭包的订阅，保证任一时刻至多 1 个活跃订阅（否则切换 1 次触发 N 次请求）。
  if (exposureSessionOff) { exposureSessionOff(); exposureSessionOff = null; }
  exposureSessionOff = HG.session.onChange(load);
  // FR-PLAN-5 验收 2：支持从计划详情直达某敞口详情（#/spot/exposure?id=…）。
  // 先让 load() 收敛（其内部会给 detailCard 加 hg-hidden），再打开指定详情，避免被 load 覆盖。
  const initialId = ctx && ctx.query && ctx.query.id;
  if (initialId) load().then(() => loadDetail(initialId));
  else load();
}

/* ------------------------------------------------------------------ *
 * 展示辅助
 * ------------------------------------------------------------------ */

function subAccountLabel(id) {
  const hit = HG.session.state.subAccounts.filter((s) => String(s.id) === String(id))[0];
  return hit ? ((hit.code ? hit.code + ' ' : '') + (hit.name || '')) : ('#' + id);
}
function productName(id) {
  if (id === null || id === undefined || Number(id) === 0) return '（未映射）';
  return productCache[String(id)] || ('#' + id);
}

HG.pages = HG.pages || {};
HG.pages.spot = {
  renderEntry, renderExposure, contractForm, contractOpsPanel, priceForm, seriesPanel,
  importPanel, productionPanel, wipPanel, inventoryPanel, docSubAccountPanel, parseCsv
};

HG.router.register('/spot', renderEntry);
HG.router.register('/spot/exposure', renderExposure);

export default HG.pages.spot;
