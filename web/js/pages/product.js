/*
 * 品种管理页面（PRODUCT）—— FR-PRODUCT-1~14（T-018）。
 *
 * **字段名以已注册 Handler 的请求/视图类为准（camelCase）**（契约逐字对齐）：
 *   GET  /product                         → **PageData 分页信封** {items:[ProductView], total, page, size}
 *                                           查询：page / size∈{10,20,50,100}（默认 20）/ sort=field,asc|desc
 *                                           排序白名单（VarietyService.PRODUCT_SORT_FIELDS）：
 *                                             created_time(默认,desc) | product_code | product_name | status | category
 *   POST /product                          → CreateRequest {productCode, productName, category, remark}
 *   GET  /product/{id}                     → ProductView {id, productCode, productName, category,
 *                                            categoryManaged, status, isDomesticExchange, currency, remark,
 *                                            disabledReason}（⚠️ 无 createdTime / 最近变更时间）
 *   GET|POST /product/{id}/contracts       → ContractView[] / Request {instrumentId, instrumentType,
 *                                            exchangeCode, contractMultiplier, expiryDate,
 *                                            underlyingInstrumentId, strikePrice, optionType}
 *   GET|POST /product/mapping?productId=   → MappingView[] / Request {spotCategoryCode, spotCategoryName,
 *                                            productId, mappingDirection, isPrimary, changeDesc}
 *   POST /product/whitelist                → Request {action:ENABLE|DISABLE, subAccountId, productId,
 *                                            categoryOverride?, reason?}
 *   GET  /product/quota?subAccountId=&productId= → 三层全景 {layer1SubProduct, layer3AccountProduct,
 *                                            layer2SubInstrument, note}（**实体直出**：额度/数量为 JSON number）
 *   POST /product/quota                    → Request {limitLayer:SUB_PRODUCT|SUB_INSTRUMENT|ACCOUNT_PRODUCT,
 *                                            subAccountId, productId, instrumentId?, quotaLimit(字符串),
 *                                            quotaUnit:LOT|TON, effectiveFrom}
 *   GET  /product/change-log?productId=    → ChangeView[] {id, changeType, changedField, beforeValue,
 *                                            afterValue, subAccountId, operatorId, changeTime, changeReason}
 *   POST /product/import                   → Request {csvLines:[…]} → {inserted}；失败 10019 含逐行清单
 *   GET  /product/export                   → text/csv（Content-Disposition: attachment; filename="products.csv"）
 *
 * 关键界面约束（就绪后须落实，源自既有 stub 的登记）：
 *   · 品种-现货品类映射是实货敞口匹配衍生品的**唯一入口**（FR-PRODUCT-10）：无映射的现货品类
 *     不得参与套保计划编制——本页映射 Tab 明确提示。
 *   · 额度三层展示须**标明命中的层级**（Q-16）：层①/层②为子账户维度，层③为账户级（哨兵 0）。
 *   · 变更留痕：编码 / 分类 / 计量单位换算关系被下游引用时不得静默变更（R-PRODUCT-8）。
 *
 * 纪律：不直连 DB / C++，只经 HG.api；金额/数量禁止 Number 参与展示运算（走 HG.format 字符串口径）；
 *       失败统一经 HG.errmsg（阻断四项）；数据界面必配 加载/空/错误 三态。
 */
import { HG } from '../core/ns.js';
import '../core/format.js';
import '../core/router.js';
import '../core/routes.js';
import '../core/ui.js';

const ui = HG.ui;
const F = HG.format;
const R = HG.routes.PRODUCT;

/* ------------------------------------------------------------------ *
 * 标签与取值域（取值域以 Handler / VarietyService 实测为准）
 * ------------------------------------------------------------------ */
const CATEGORY = { COMMODITY: '商品类（90% 红线）', TRADE: '贸易类（80% 红线）' };
const STATUS = { ENABLED: '启用', DISABLED: '停用' };
const LIMIT_LAYER = { SUB_PRODUCT: '层① 子账户＋品种', SUB_INSTRUMENT: '层② 子账户＋合约', ACCOUNT_PRODUCT: '层③ 账户＋品种（哨兵 0）' };
const QUOTA_UNITS = ['LOT', 'TON'];
const INSTRUMENT_TYPE = { FUTURES: '期货', OPTION: '期权' };
const MAPPING_DIRECTION = { PURCHASE: '采购侧', SALES: '销售侧', BOTH: '通用' };
const CHANGE_TYPE = { CREATE: '新建', UPDATE: '修改', ENABLE: '启用', DISABLE: '停用' };
const PRODUCT_SORTS = [
  { v: 'created_time', t: '创建时间' },
  { v: 'product_code', t: '品种编码' },
  { v: 'product_name', t: '品种名称' },
  { v: 'status', t: '状态' },
  { v: 'category', t: '分类' }
];
const CSV_HEADER = ['product_code', 'product_name', 'category', 'currency', 'remark'];

/** 归一化分页响应（主路径 PageData；兼容旧 bare 数组，服务端若回退界面不崩）。 */
function normalizePage(data, fallback) {
  if (Array.isArray(data)) return { items: data, total: data.length, page: 1, size: data.length || fallback.size };
  const items = (data && Array.isArray(data.items)) ? data.items : [];
  return {
    items: items,
    total: (data && typeof data.total === 'number') ? data.total : items.length,
    page: (data && typeof data.page === 'number' && data.page > 0) ? data.page : fallback.page,
    size: (data && typeof data.size === 'number' && data.size > 0) ? data.size : fallback.size
  };
}

/* ------------------------------------------------------------------ *
 * 通用：子账户下拉（会话内的可见子账户；不可得时退化为文本输入）
 * ------------------------------------------------------------------ */
function subAccountSelect(id, required) {
  const list = (HG.session && HG.session.state && HG.session.state.subAccounts) || [];
  let control;
  if (!list.length) {
    // 会话未取到子账户（降级/未连接）：退化为数值输入，仍由后端做权限与存在性校验
    control = ui.el('input', { id: id, name: 'subAccountId', type: 'number', min: '1', inputmode: 'numeric' });
  } else {
    control = ui.el('select', { id: id, name: 'subAccountId' });
    control.appendChild(ui.el('option', { value: '', text: required ? '请选择子账户' : '（不指定）' }));
    list.forEach((s) => {
      control.appendChild(ui.el('option', { value: String(s.id), text: (s.code ? s.code + ' ' : '') + (s.name || ('#' + s.id)) }));
    });
    const cur = HG.session.currentId();
    if (cur !== null && cur !== undefined && list.some((s) => String(s.id) === String(cur))) control.value = String(cur);
  }
  return control;
}

/* ------------------------------------------------------------------ *
 * 导出（FR-PRODUCT-8 验收3：无数据时拦截并提示，不生成空文件）
 * ------------------------------------------------------------------ */
function triggerBlobDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'products.csv';
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.setTimeout(() => { URL.revokeObjectURL(url); }, 1000);
}

async function doExport(btn, alertBox) {
  ui.clear(alertBox);
  const orig = btn.textContent;
  btn.disabled = true; btn.textContent = '导出中…';
  try {
    const res = await HG.api.download(R.EXPORT);
    const text = await res.blob.text();
    // 行数统计：仅用于「无数据 ⇒ 不生成空文件」的界面裁决（非业务判定）
    const rows = text.replace(/^\uFEFF/, '').split(/\r\n|\n/).filter((l) => l !== '');
    if (rows.length <= 1) {
      alertBox.appendChild(ui.banner('无可导出数据：服务端仅返回表头，未生成文件（FR-PRODUCT-8 验收 3）。', 'warn'));
      return;
    }
    triggerBlobDownload(res.blob, res.filename || 'products.csv');
    ui.toast('已导出 ' + (rows.length - 1) + ' 条品种', 'ok');
  } catch (err) {
    alertBox.innerHTML = ui.blockedHTML(err);
  } finally {
    btn.disabled = false; btn.textContent = orig;
  }
}

/* ------------------------------------------------------------------ *
 * 导入（FR-PRODUCT-7）：CSV 文本 → csvLines；错误按行提示
 * ------------------------------------------------------------------ */
function splitCsvLines(text) {
  const s = String(text).replace(/^\uFEFF/, '');
  const lines = s.split(/\r\n|\n/);
  // 仅去掉末尾因换行产生的空行，保留中间空行（由后端逐行校验并定位行号）
  if (lines.length && lines[lines.length - 1] === '') lines.pop();
  return lines;
}

/** 把后端 10019 的逐行清单（并入 message）渲染为「摘要 + 逐条列表」。 */
function importProblemsHTML(err) {
  const msg = String((err && err.message) || '');
  const parts = msg.split(/\n\s*-\s*/);
  const summary = (parts.shift() || '').trim();
  const problems = parts.map((p) => p.trim()).filter((p) => p !== '');
  if (!problems.length) return ui.blockedHTML(err);   // 无逐行清单 ⇒ 交给统一渲染（含 traceId）
  const li = problems.map((p) => `<li>${F.esc(p)}</li>`).join('');
  return '<div class="hg-blocked">' +
    `<div class="hg-blocked__msg">${F.esc(summary || '导入文件校验失败')}</div>` +
    '<div class="hg-muted">逐行问题清单（行号从 1 起，表头为第 1 行；已全部回滚、未产生部分写入）：</div>' +
    `<ul style="margin:6px 0 0 18px">${li}</ul>` +
    (err && err.traceId ? `<div class="hg-muted">traceId：<span class="hg-mono">${F.esc(err.traceId)}</span></div>` : '') +
    '</div>';
}

function importPanel(onDone) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '批量导入（FR-PRODUCT-7）' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: 'CSV 首行须为表头：' + CSV_HEADER.join(',') + '。采用全量预校验后一次性提交——任一行不合法则整体不导入，并给出逐行错误清单。' }));

  const alertBox = ui.el('div', { role: 'alert' });
  const file = ui.el('input', { id: 'pd-import-file', type: 'file', accept: '.csv,text/csv' });
  const area = ui.el('textarea', { id: 'pd-import-text', rows: '6', placeholder: '也可直接粘贴 CSV 文本（含表头）' });
  const form = ui.el('form', { novalidate: 'novalidate' });
  form.appendChild(alertBox);
  form.appendChild(ui.field({ id: 'pd-import-file', label: 'CSV 文件', required: false, control: file }));
  form.appendChild(ui.field({ id: 'pd-import-text', label: '或粘贴内容', required: false, control: area }));
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '开始导入' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  file.addEventListener('change', () => {
    const f = file.files && file.files[0];
    if (!f) return;
    const reader = new FileReader();
    reader.onload = () => { area.value = String(reader.result || ''); };
    reader.onerror = () => { alertBox.innerHTML = ui.blockedHTML({ code: -1, message: '读取文件失败，请改用粘贴方式。' }); };
    reader.readAsText(f, 'utf-8');
  });

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    const lines = splitCsvLines(area.value || '');
    if (!lines.length || (lines.length === 1 && lines[0].trim() === '')) {
      ui.setFieldError(area, '请选择 CSV 文件或粘贴内容');
      return;
    }
    ui.setFieldError(area, '');
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '导入中…';
    try {
      const res = await HG.api.post(R.IMPORT, { csvLines: lines }, { idempotencyKey: HG.api.newIdempotencyKey() });
      const inserted = res && typeof res.inserted === 'number' ? res.inserted : 0;
      alertBox.appendChild(ui.banner('导入成功：新增 ' + inserted + ' 条品种。', 'ok'));
      area.value = '';
      if (typeof onDone === 'function') onDone();
    } catch (err) {
      alertBox.innerHTML = importProblemsHTML(err);
      window.scrollTo(0, 0);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  card.appendChild(form);
  return card;
}

/* ------------------------------------------------------------------ *
 * 新增品种（FR-PRODUCT-1）
 * ------------------------------------------------------------------ */
function createPanel(onDone) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '新增品种（FR-PRODUCT-1）' }));

  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);

  const code = ui.el('input', { id: 'pd-new-code', type: 'text', maxlength: '32' });
  const name = ui.el('input', { id: 'pd-new-name', type: 'text', maxlength: '64' });
  const cat = ui.el('select', { id: 'pd-new-cat' });
  cat.appendChild(ui.el('option', { value: '', text: '请选择分类' }));
  cat.appendChild(ui.el('option', { value: 'COMMODITY', text: CATEGORY.COMMODITY }));
  cat.appendChild(ui.el('option', { value: 'TRADE', text: CATEGORY.TRADE }));
  const remark = ui.el('input', { id: 'pd-new-remark', type: 'text', maxlength: '200' });

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'pd-new-code', label: '品种编码', required: true, help: '全系统唯一、一经使用不得修改；大写字母/数字/下划线/连字符，2–32 位', control: code }));
  grid.appendChild(ui.field({ id: 'pd-new-name', label: '品种名称', required: true, control: name }));
  grid.appendChild(ui.field({ id: 'pd-new-cat', label: '分类', required: true, help: '商品类沿用 90% 红线；贸易类 80% 红线', control: cat }));
  grid.appendChild(ui.field({ id: 'pd-new-remark', label: '备注', required: false, control: remark }));
  form.appendChild(grid);

  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '创建品种' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!/^[A-Z0-9][A-Z0-9_-]{1,31}$/.test(code.value.trim())) { ui.setFieldError(code, '编码须为大写字母/数字/下划线/连字符，2–32 位且以字母或数字开头'); ok = false; } else ui.setFieldError(code, '');
    if (!name.value.trim()) { ui.setFieldError(name, '请填写品种名称'); ok = false; } else ui.setFieldError(name, '');
    if (!cat.value) { ui.setFieldError(cat, '请选择分类（必填）'); ok = false; } else ui.setFieldError(cat, '');
    if (!ok) return;

    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const created = await HG.api.post(R.LIST, {
        productCode: code.value.trim(), productName: name.value.trim(), category: cat.value, remark: remark.value.trim() || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('品种已创建（id=' + (created && created.id) + '，状态=启用）', 'ok');
      form.reset();
      if (typeof onDone === 'function') onDone();
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  card.appendChild(form);
  return card;
}

/* ================================================================== *
 * 列表页（FR-PRODUCT-5 / FR-PRODUCT-7 / FR-PRODUCT-8）
 * ================================================================== */
function renderList(root) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '品种管理（PRODUCT）' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '品种档案为全局共享（编码全系统唯一），白名单与额度为子账户级配置（R-PRODUCT-7）。' }));

  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'created_time', sortDir: 'desc', kw: '', status: '', category: '', raw: [] };

  /* ---- 工具栏 ---- */
  const bar = ui.el('div', { class: 'hg-toolbar' });

  const kw = ui.el('input', { id: 'pd-f-kw', type: 'search', placeholder: '编码 / 名称关键字' });
  kw.addEventListener('input', () => { state.kw = kw.value.trim().toLowerCase(); renderRows(); });
  bar.appendChild(ui.field({ id: 'pd-f-kw', label: '关键字（本页内）', control: kw }));

  const statusSel = ui.el('select', { id: 'pd-f-status' });
  statusSel.appendChild(ui.el('option', { value: '', text: '全部状态' }));
  Object.keys(STATUS).forEach((k) => statusSel.appendChild(ui.el('option', { value: k, text: STATUS[k] })));
  statusSel.addEventListener('change', () => { state.status = statusSel.value; renderRows(); });
  bar.appendChild(ui.field({ id: 'pd-f-status', label: '状态（本页内）', control: statusSel }));

  const catSel = ui.el('select', { id: 'pd-f-cat' });
  catSel.appendChild(ui.el('option', { value: '', text: '全部分类' }));
  Object.keys(CATEGORY).forEach((k) => catSel.appendChild(ui.el('option', { value: k, text: CATEGORY[k] })));
  catSel.addEventListener('change', () => { state.category = catSel.value; renderRows(); });
  bar.appendChild(ui.field({ id: 'pd-f-cat', label: '分类（本页内）', control: catSel }));

  const sortSel = ui.el('select', { id: 'pd-f-sort' });
  PRODUCT_SORTS.forEach((s) => sortSel.appendChild(ui.el('option', { value: s.v, text: s.t })));
  sortSel.addEventListener('change', () => { state.sortField = sortSel.value; load(1); });
  bar.appendChild(ui.field({ id: 'pd-f-sort', label: '排序字段', control: sortSel }));

  const dirSel = ui.el('select', { id: 'pd-f-dir' });
  dirSel.appendChild(ui.el('option', { value: 'desc', text: '降序' }));
  dirSel.appendChild(ui.el('option', { value: 'asc', text: '升序' }));
  dirSel.addEventListener('change', () => { state.sortDir = dirSel.value; load(1); });
  bar.appendChild(ui.field({ id: 'pd-f-dir', label: '方向', control: dirSel }));

  const sizeSel = ui.el('select', { id: 'pd-f-size' });
  HG.api.PAGE_SIZES.forEach((s) => sizeSel.appendChild(ui.el('option', { value: String(s), text: s + ' 条/页' })));
  sizeSel.addEventListener('change', () => { state.size = Number(sizeSel.value); load(1); });
  bar.appendChild(ui.field({ id: 'pd-f-size', label: '每页条数', control: sizeSel }));

  const reloadBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  reloadBtn.addEventListener('click', () => load(state.page));
  bar.appendChild(reloadBtn);

  const exportBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '导出 CSV' });
  bar.appendChild(exportBtn);

  const importToggle = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '导入 CSV' });
  bar.appendChild(importToggle);

  const createToggle = ui.el('button', { class: 'hg-btn', type: 'button', text: '新增品种' });
  bar.appendChild(createToggle);

  root.appendChild(bar);
  root.appendChild(ui.el('p', { class: 'hg-muted', text: '注：服务端列表端点仅支持 分页 / 排序 / 子账户隔离，未提供 status、分类、关键字筛选参数；标「本页内」的筛选仅作用于当前页（FR-PRODUCT-5 的服务端筛选为待补项）。' }));

  const alertBox = ui.el('div', { role: 'alert' });
  root.appendChild(alertBox);

  const importCard = importPanel(() => load(1));
  importCard.classList.add('hg-hidden');
  root.appendChild(importCard);
  const createCard = createPanel(() => load(1));
  createCard.classList.add('hg-hidden');
  root.appendChild(createCard);
  createToggle.addEventListener('click', () => { createCard.classList.toggle('hg-hidden'); importCard.classList.add('hg-hidden'); });
  importToggle.addEventListener('click', () => { importCard.classList.toggle('hg-hidden'); createCard.classList.add('hg-hidden'); });
  exportBtn.addEventListener('click', () => doExport(exportBtn, alertBox));

  const body = ui.el('div');
  root.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '品种分页' });
  root.appendChild(pager);

  sortSel.value = state.sortField; dirSel.value = state.sortDir; sizeSel.value = String(state.size);

  function query(page) {
    return { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir };
  }

  async function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载品种列表…');
    ui.clear(pager);
    try {
      const data = await HG.api.get(R.LIST, query(page));
      const pg = normalizePage(data, state);
      state.page = pg.page; state.size = pg.size;
      state.raw = pg.items;
      renderRows(pg);
    } catch (err) {
      ui.renderError(body, err, () => load(state.page));
      ui.clear(pager);
    }
  }

  function applyFilters(items) {
    return items.filter((p) => {
      if (state.status && p.status !== state.status) return false;
      if (state.category && p.category !== state.category) return false;
      if (state.kw) {
        const hay = (String(p.productCode || '') + ' ' + String(p.productName || '')).toLowerCase();
        if (hay.indexOf(state.kw) === -1) return false;
      }
      return true;
    });
  }

  function renderRows(pg) {
    pg = pg || { items: state.raw, total: state.raw.length, page: state.page, size: state.size };
    const items = applyFilters(pg.items);
    if (!items.length) {
      const hint = state.raw.length
        ? '当前页 ' + state.raw.length + ' 条记录均被「本页内」筛选条件过滤（改用排序/翻页查看，或清空筛选）。'
        : '暂无品种。可点击「新增品种」创建，或「导入 CSV」批量建立品种档案。';
      ui.renderEmpty(body, hint);
      renderPager(pg);
      return;
    }
    const columns = [
      { title: '品种编码', render: (r) => `<a href="#/product/${encodeURIComponent(r.id)}">${F.esc(r.productCode)}</a>` },
      { title: '品种名称', render: (r) => F.esc(r.productName) },
      { title: '分类', render: (r) => F.esc(r.category || '—') },
      { title: '状态', render: (r) => ui.statusBadge(STATUS[r.status] || r.status) },
      { title: '分类管理', render: (r) => F.esc(r.categoryManaged === 'Y' ? '纳入' : '未纳入') },
      { title: '境内场内', render: (r) => F.esc(r.isDomesticExchange === 'Y' ? '是' : '否') },
      { title: '币种', render: (r) => F.esc(r.currency || '—') },
      { title: '备注', render: (r) => F.esc(r.remark || '—') },
      { title: '操作', render: (r) => `<a class="hg-btn hg-btn--ghost hg-btn--sm" href="#/product/${encodeURIComponent(r.id)}">详情</a>` }
    ];
    ui.clear(body);
    body.innerHTML = ui.tableHTML(columns, items);
    body.appendChild(ui.el('p', { class: 'hg-muted', text: '本页显示 ' + items.length + ' / ' + pg.items.length + ' 条（第 ' + pg.page + ' 页）。' }));
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

/* ================================================================== *
 * 详情页（FR-PRODUCT-6）：概览 / 合约关联 / 现货映射 / 白名单 / 额度 / 变更历史
 * ================================================================== */
function renderDetail(root, ctx) {
  const id = ctx.params.id;
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '品种详情' }));
  root.appendChild(ui.el('p', {}, [ui.el('a', { href: '#/product', text: '← 返回品种列表' })]));

  const head = ui.el('div');
  root.appendChild(head);

  const tabs = [
    { key: 'overview', label: '概览', build: (c) => buildOverview(c, id) },
    { key: 'contracts', label: '合约关联', build: (c) => buildContracts(c, id) },
    { key: 'mapping', label: '现货映射', build: (c) => buildMapping(c, id) },
    { key: 'whitelist', label: '白名单', build: (c) => buildWhitelist(c, id) },
    { key: 'quota', label: '额度（三层）', build: (c) => buildQuota(c, id) },
    { key: 'changelog', label: '变更历史', build: (c) => buildChangeLog(c, id) }
  ];

  const tabBar = ui.el('div', { class: 'hg-tabs', role: 'tablist' });
  const panel = ui.el('div');
  let active = (ctx.query && ctx.query.tab) || 'overview';
  if (!tabs.some((t) => t.key === active)) active = 'overview';

  function select(key) {
    Array.prototype.forEach.call(tabBar.children, (b) => {
      const on = b.dataset.key === key;
      b.classList.toggle('is-active', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    ui.clear(panel);
    tabs.filter((t) => t.key === key)[0].build(panel);
  }
  tabs.forEach((t) => {
    const b = ui.el('button', { type: 'button', role: 'tab', text: t.label, onclick: () => HG.router.navigate('/product/' + encodeURIComponent(id) + '?tab=' + t.key) });
    b.dataset.key = t.key;
    tabBar.appendChild(b);
  });

  let current = null;   // ProductView

  async function loadHead() {
    ui.renderLoading(head, '正在加载品种档案…');
    try {
      current = await HG.api.get(R.detail(id));
      ui.clear(head);
      head.appendChild(productHeadCard(current, id));
      root.appendChild(tabBar);
      root.appendChild(panel);
      select(active);
    } catch (err) {
      ui.renderError(head, err, loadHead);
    }
  }
  loadHead();
}

function productHeadCard(d, id) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: (d.productCode ? d.productCode + ' ' : '') + (d.productName || ('#' + id)) }));
  const dl = ui.el('dl', { class: 'hg-desc' });
  [['品种 ID', '#' + d.id],
   ['品种编码', F.esc(d.productCode) + '（一经使用不得修改，R-PRODUCT-3）'],
   ['品种名称', F.esc(d.productName)],
   ['分类', CATEGORY[d.category] || (d.category || '—')],
   ['状态', STATUS[d.status] || (d.status || '—')],
   ['分类管理', d.categoryManaged === 'Y' ? '纳入分类管理' : '未纳入'],
   ['境内场内', d.isDomesticExchange === 'Y' ? '是（境外/场外永久排除）' : '否'],
   ['计价币种', d.currency || '—'],
   ['备注', d.remark || '—'],
   ['停用原因', d.disabledReason || '—']
  ].forEach((r) => { dl.appendChild(ui.el('dt', { text: r[0] })); dl.appendChild(ui.el('dd', { text: r[1] })); });
  card.appendChild(dl);
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '注：ProductView 不含创建时间 / 最近变更时间；变更时间见「变更历史」Tab（FR-PRODUCT-5 输出的「最近变更时间」为待补项）。' }));
  return card;
}

/* ---- 概览 ---- */
function buildOverview(container) {
  container.appendChild(ui.el('p', { class: 'hg-muted', text: '基础属性见上方档案卡片。额度指标、合约关联、现货映射、白名单分别见对应 Tab（服务端将其拆为独立端点，未并入 ProductView）。' }));

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: 'FR 覆盖与接口缺口（诚实登记）' }));
  const ul = ui.el('ul');
  ul.appendChild(ui.el('li', { html: '<strong>已覆盖</strong>：' +
    '<code>FR-PRODUCT-1</code> 新增（<code>POST /product</code>）、' +
    '<code>FR-PRODUCT-5</code> 列表（分页 / 排序 / 子账户隔离）、' +
    '<code>FR-PRODUCT-6</code> 详情、<code>FR-PRODUCT-7</code> 导入、<code>FR-PRODUCT-8</code> 导出、' +
    '<code>FR-PRODUCT-9</code> 合约关联、<code>FR-PRODUCT-10</code> 现货映射、' +
    '<code>FR-PRODUCT-11</code> 额度（三层展示＋维护）、<code>FR-PRODUCT-12</code> 白名单写面＋留痕、' +
    '<code>FR-PRODUCT-14</code> 变更历史。' }));
  ul.appendChild(ui.el('li', { html: '<strong>接口缺口</strong>（服务端无端点 ⇒ 本页<strong>不提供</strong>假入口）：' +
    '<code>FR-PRODUCT-2</code> 修改（无 <code>PUT /product/{id}</code>）、' +
    '<code>FR-PRODUCT-3</code> 停用 / <code>FR-PRODUCT-4</code> 启用（无动作端点）、' +
    '<code>FR-PRODUCT-13</code> 计量单位与换算关系（无端点）、' +
    '<code>FR-PRODUCT-5</code> 的 status / 分类 / 关键字筛选（<code>GET /product</code> 仅支持 page / size / sort）、' +
    '<code>FR-PRODUCT-12</code> 「当前白名单」读取（仅注册 <code>POST /product/whitelist</code>）。' }));
  card.appendChild(ul);
  container.appendChild(card);
}

/* ---- 合约关联（FR-PRODUCT-9） ---- */
function buildContracts(container, id) {
  const body = ui.el('div');
  container.appendChild(body);
  const alertBox = ui.el('div', { role: 'alert' });
  container.appendChild(alertBox);

  async function load() {
    ui.renderLoading(body, '正在加载合约关联…');
    try {
      const list = await HG.api.get(R.CONTRACTS(id));
      const items = Array.isArray(list) ? list : [];
      if (!items.length) {
        ui.renderEmpty(body, '该品种尚未关联任何合约。同一品种下可关联多个合约（期货/期权分别标注类型）。');
      } else {
        ui.clear(body);
        body.innerHTML = ui.tableHTML([
          { title: '合约代码', render: (r) => F.esc(r.instrumentId) },
          { title: '类型', render: (r) => ui.statusBadge(INSTRUMENT_TYPE[r.instrumentType] || r.instrumentType) },
          { title: '交易所', render: (r) => F.esc(r.exchangeCode || '—') },
          { title: '合约乘数', num: true, render: (r) => F.decimal(r.contractMultiplier) },
          { title: '到期日', render: (r) => F.date(r.expiryDate) },
          { title: '期权类型', render: (r) => F.esc(r.optionType || '—') },
          { title: '行权价', num: true, render: (r) => F.decimal(r.strikePrice) },
          { title: '状态', render: (r) => ui.statusBadge(r.status) }
        ], items);
      }
    } catch (err) {
      ui.renderError(body, err, load);
    }
  }

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '新增合约关联（FR-PRODUCT-9）' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const insId = ui.el('input', { id: 'pd-c-ins', type: 'text', maxlength: '64' });
  const insType = ui.el('select', { id: 'pd-c-type' });
  insType.appendChild(ui.el('option', { value: '', text: '请选择类型' }));
  insType.appendChild(ui.el('option', { value: 'FUTURES', text: '期货' }));
  insType.appendChild(ui.el('option', { value: 'OPTION', text: '期权' }));
  const exch = ui.el('input', { id: 'pd-c-exch', type: 'text', maxlength: '32' });
  const mult = ui.el('input', { id: 'pd-c-mult', type: 'text', inputmode: 'decimal' });
  const expiry = ui.el('input', { id: 'pd-c-exp', type: 'date' });
  const under = ui.el('input', { id: 'pd-c-under', type: 'text', maxlength: '64' });
  const strike = ui.el('input', { id: 'pd-c-strike', type: 'text', inputmode: 'decimal' });
  const optType = ui.el('select', { id: 'pd-c-opttype' });
  optType.appendChild(ui.el('option', { value: '', text: '（仅期权）' }));
  optType.appendChild(ui.el('option', { value: 'CALL', text: '看涨 CALL' }));
  optType.appendChild(ui.el('option', { value: 'PUT', text: '看跌 PUT' }));

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'pd-c-ins', label: '合约代码', required: true, control: insId }));
  grid.appendChild(ui.field({ id: 'pd-c-type', label: '合约类型', required: true, help: '必须显式标注（期权与期货共用同一追溯链与额度）', control: insType }));
  grid.appendChild(ui.field({ id: 'pd-c-exch', label: '交易所', required: false, control: exch }));
  grid.appendChild(ui.field({ id: 'pd-c-mult', label: '合约乘数', required: false, help: '字符串化十进制', control: mult }));
  grid.appendChild(ui.field({ id: 'pd-c-exp', label: '到期日', required: false, control: expiry }));
  grid.appendChild(ui.field({ id: 'pd-c-under', label: '期权标的', required: false, help: '仅期权', control: under }));
  grid.appendChild(ui.field({ id: 'pd-c-strike', label: '行权价', required: false, help: '仅期权，字符串化十进制', control: strike }));
  grid.appendChild(ui.field({ id: 'pd-c-opttype', label: '期权类型', required: false, help: '仅期权', control: optType }));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '建立关联' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!insId.value.trim()) { ui.setFieldError(insId, '请填写合约代码'); ok = false; } else ui.setFieldError(insId, '');
    if (!insType.value) { ui.setFieldError(insType, '请选择合约类型'); ok = false; } else ui.setFieldError(insType, '');
    if (insType.value === 'OPTION' && !strike.value.trim()) { ui.setFieldError(strike, '期权须填写行权价'); ok = false; } else ui.setFieldError(strike, '');
    if (!ok) return;
    const payload = { instrumentId: insId.value.trim(), instrumentType: insType.value };
    if (exch.value.trim()) payload.exchangeCode = exch.value.trim();
    if (mult.value.trim()) payload.contractMultiplier = mult.value.trim();
    if (expiry.value) payload.expiryDate = expiry.value;
    if (under.value.trim()) payload.underlyingInstrumentId = under.value.trim();
    if (strike.value.trim()) payload.strikePrice = strike.value.trim();
    if (optType.value) payload.optionType = optType.value;
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const newId = await HG.api.post(R.CONTRACTS(id), payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('合约关联已建立（id=' + newId + '）', 'ok');
      form.reset();
      load();
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  card.appendChild(form);
  container.appendChild(card);
  load();
}

/* ---- 现货映射（FR-PRODUCT-10） ---- */
function buildMapping(container, id) {
  container.appendChild(ui.banner('<strong>映射是期现一体的唯一入口</strong>：无映射的现货品类不得参与套保计划编制；计划编制界面须提示先建立映射。', 'warn'));

  const body = ui.el('div');
  container.appendChild(body);
  const alertBox = ui.el('div', { role: 'alert' });
  container.appendChild(alertBox);

  async function load() {
    ui.renderLoading(body, '正在加载现货品类映射…');
    try {
      const list = await HG.api.get(R.MAPPING, { productId: id });
      const items = Array.isArray(list) ? list : [];
      if (!items.length) {
        ui.renderEmpty(body, '该品种尚未建立任何现货品类映射（实货敞口将无法匹配到本品种）。');
      } else {
        ui.clear(body);
        body.innerHTML = ui.tableHTML([
          { title: '现货品类编码', render: (r) => F.esc(r.spotCategoryCode) },
          { title: '现货品类名称', render: (r) => F.esc(r.spotCategoryName || '—') },
          { title: '映射方向', render: (r) => ui.statusBadge(MAPPING_DIRECTION[r.mappingDirection] || r.mappingDirection) },
          { title: '主映射', render: (r) => F.esc(r.isPrimary === 'Y' ? '是' : '否') },
          { title: '状态', render: (r) => ui.statusBadge(r.status) }
        ], items);
      }
    } catch (err) {
      ui.renderError(body, err, load);
    }
  }

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '新增 / 维护映射（FR-PRODUCT-10）' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const catCode = ui.el('input', { id: 'pd-m-code', type: 'text', maxlength: '32' });
  const catName = ui.el('input', { id: 'pd-m-name', type: 'text', maxlength: '64' });
  const dir = ui.el('select', { id: 'pd-m-dir' });
  dir.appendChild(ui.el('option', { value: '', text: '请选择映射方向' }));
  Object.keys(MAPPING_DIRECTION).forEach((k) => dir.appendChild(ui.el('option', { value: k, text: MAPPING_DIRECTION[k] })));
  const primary = ui.el('input', { id: 'pd-m-primary', type: 'checkbox' });
  const changeDesc = ui.el('input', { id: 'pd-m-desc', type: 'text', maxlength: '200' });

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'pd-m-code', label: '现货品类编码', required: true, control: catCode }));
  grid.appendChild(ui.field({ id: 'pd-m-name', label: '现货品类名称', required: false, control: catName }));
  grid.appendChild(ui.field({ id: 'pd-m-dir', label: '映射方向', required: true, help: '采购侧 / 销售侧 / 通用', control: dir }));
  grid.appendChild(ui.field({ id: 'pd-m-primary', label: '设为主映射', required: false, help: '同一品类+方向至多一个主映射，重复将回 10010', control: primary }));
  grid.appendChild(ui.field({ id: 'pd-m-desc', label: '变更说明', required: false, help: '变更不得影响已成立的套保计划', control: changeDesc }));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '提交映射' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!catCode.value.trim()) { ui.setFieldError(catCode, '请填写现货品类编码'); ok = false; } else ui.setFieldError(catCode, '');
    if (!dir.value) { ui.setFieldError(dir, '请选择映射方向'); ok = false; } else ui.setFieldError(dir, '');
    if (!ok) return;
    const payload = { spotCategoryCode: catCode.value.trim(), productId: Number(id), mappingDirection: dir.value, isPrimary: primary.checked ? 'Y' : 'N' };
    if (catName.value.trim()) payload.spotCategoryName = catName.value.trim();
    if (changeDesc.value.trim()) payload.changeDesc = changeDesc.value.trim();
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const newId = await HG.api.post(R.MAPPING, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('映射已提交（id=' + newId + '）', 'ok');
      form.reset();
      load();
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  card.appendChild(form);
  container.appendChild(card);
  load();
}

/* ---- 白名单（FR-PRODUCT-12） ---- */
function buildWhitelist(container, id) {
  container.appendChild(ui.banner(
    '<strong>接口现状（诚实登记）</strong>：白名单端点仅注册 <code>POST /product/whitelist</code>（action=ENABLE|DISABLE），' +
    '<strong>无读取端点</strong> ⇒ 本页可执行「加入 / 移出」并展示其变更留痕，但<strong>无法列出某子账户当前完整白名单</strong>。' +
    '当前白名单的读取属待补项（建议并入 <code>GET /product/{id}</code> 详情或新增查询端点）。',
    'warn'
  ));

  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);

  const sub = subAccountSelect('pd-w-sub', true);
  const action = ui.el('select', { id: 'pd-w-action' });
  action.appendChild(ui.el('option', { value: 'ENABLE', text: '加入白名单（ENABLE）' }));
  action.appendChild(ui.el('option', { value: 'DISABLE', text: '移出白名单（DISABLE，保留历史）' }));
  const override = ui.el('select', { id: 'pd-w-override' });
  override.appendChild(ui.el('option', { value: '', text: '（继承子账户分类）' }));
  override.appendChild(ui.el('option', { value: 'COMMODITY', text: CATEGORY.COMMODITY }));
  override.appendChild(ui.el('option', { value: 'TRADE', text: CATEGORY.TRADE }));
  const reason = ui.el('input', { id: 'pd-w-reason', type: 'text', maxlength: '200' });

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'pd-w-sub', label: '子账户', required: true, help: '白名单按子账户独立生效（R-PRODUCT-5）', control: sub }));
  grid.appendChild(ui.field({ id: 'pd-w-action', label: '动作', required: true, control: action }));
  grid.appendChild(ui.field({ id: 'pd-w-override', label: '品种级分类覆盖', required: false, help: '仅 ENABLE 有效；留空继承子账户分类（Q-2）', control: override }));
  grid.appendChild(ui.field({ id: 'pd-w-reason', label: '移出原因', required: false, help: 'DISABLE 时填写', control: reason }));
  form.appendChild(grid);

  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '提交' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  function syncHelp() {
    const isDisable = action.value === 'DISABLE';
    override.disabled = isDisable;
    reason.disabled = !isDisable;
  }
  action.addEventListener('change', syncHelp);
  syncHelp();

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    if (!sub.value) { ui.setFieldError(sub, '请选择子账户'); return; }
    ui.setFieldError(sub, '');
    const act = action.value;
    const productId = Number(id);
    const subId = Number(sub.value);
    if (act === 'DISABLE') {
      const yes = await ui.confirm(
        '确认将品种 <strong>#' + F.esc(id) + '</strong> 从子账户 <strong>#' + F.esc(sub.value) + '</strong> 的白名单<strong>移出</strong>？' +
        '（置 DISABLED 并保留历史；若该子账户存在该品种未平仓持仓，后端将拒绝）',
        { title: '移出白名单', okText: '确认移出', danger: true }
      );
      if (!yes) return;
    }
    const payload = { action: act, subAccountId: subId, productId: productId };
    if (act === 'ENABLE' && override.value) payload.categoryOverride = override.value;
    if (act === 'DISABLE' && reason.value.trim()) payload.reason = reason.value.trim();
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      await HG.api.post(R.WHITELIST, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast(act === 'ENABLE' ? '已加入白名单（变更待下发 C++）' : '已移出白名单（保留历史）', 'ok');
      form.reset(); syncHelp();
      if (typeof reloadLog === 'function') reloadLog();
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '白名单配置（FR-PRODUCT-12）' }));
  card.appendChild(form);
  container.appendChild(card);

  const logCard = ui.el('div', { class: 'hg-card' });
  logCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '白名单变更留痕（源自变更历史）' }));
  const logBody = ui.el('div');
  logCard.appendChild(logBody);
  container.appendChild(logCard);

  let reloadLog = null;
  async function loadLog() {
    ui.renderLoading(logBody, '正在加载白名单变更留痕…');
    try {
      const all = await HG.api.get(R.CHANGE_LOG, { productId: id });
      const items = (Array.isArray(all) ? all : []).filter((c) => c.changedField === 'whitelist');
      if (!items.length) {
        ui.renderEmpty(logBody, '暂无白名单变更留痕。');
      } else {
        ui.clear(logBody);
        logBody.innerHTML = ui.tableHTML([
          { title: '时间', render: (r) => F.dateTime(r.changeTime) },
          { title: '类型', render: (r) => ui.statusBadge(CHANGE_TYPE[r.changeType] || r.changeType) },
          { title: '子账户', render: (r) => (r.subAccountId === null || r.subAccountId === undefined ? '—' : '#' + F.esc(r.subAccountId)) },
          { title: '变更前', render: (r) => F.esc(r.beforeValue || '—') },
          { title: '变更后', render: (r) => F.esc(r.afterValue || '—') },
          { title: '原因', render: (r) => F.esc(r.changeReason || '—') },
          { title: '操作人', render: (r) => '#' + F.esc(r.operatorId) }
        ], items);
      }
    } catch (err) {
      ui.renderError(logBody, err, loadLog);
    }
  }
  reloadLog = loadLog;
  loadLog();
}

/* ---- 额度（三层限额，FR-PRODUCT-11 / Q-16） ---- */
function buildQuota(container, id) {
  const body = ui.el('div');
  container.appendChild(body);
  const alertBox = ui.el('div', { role: 'alert' });
  container.appendChild(alertBox);

  const qbar = ui.el('div', { class: 'hg-toolbar' });
  const sub = subAccountSelect('pd-q-sub', true);
  qbar.appendChild(ui.field({ id: 'pd-q-sub', label: '查询子账户', required: true, help: '三层全景按时点取数；层③为账户级（哨兵 0）', control: sub }));
  const qbtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '查询三层全景' });
  qbar.appendChild(qbtn);
  body.appendChild(qbar);
  const resultBox = ui.el('div');
  body.appendChild(resultBox);

  async function load() {
    if (!sub.value) { ui.setFieldError(sub, '请选择子账户'); return; }
    ui.setFieldError(sub, '');
    ui.renderLoading(resultBox, '正在加载三层限额全景…');
    try {
      const pano = await HG.api.get(R.QUOTA, { subAccountId: Number(sub.value), productId: Number(id) });
      renderPanorama(resultBox, pano, sub.value);
    } catch (err) {
      ui.renderError(resultBox, err, load);
    }
  }
  qbtn.addEventListener('click', load);

  /* 维护表单 */
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '维护额度（FR-PRODUCT-11）' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '层① 子账户＋品种 / 层② 子账户＋合约 / 层③ 账户＋品种（哨兵 0）。层③为账户级配置，其授权口径见 CH-012（本页仅提供界面，不做授权判定，越权由后端拒绝）。' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const layer = ui.el('select', { id: 'pd-q-layer' });
  Object.keys(LIMIT_LAYER).forEach((k) => layer.appendChild(ui.el('option', { value: k, text: LIMIT_LAYER[k] })));
  const ins = ui.el('input', { id: 'pd-q-ins', type: 'text', maxlength: '64' });
  const limit = ui.el('input', { id: 'pd-q-limit', type: 'text', inputmode: 'decimal' });
  const unit = ui.el('select', { id: 'pd-q-unit' });
  QUOTA_UNITS.forEach((u) => unit.appendChild(ui.el('option', { value: u, text: u })));
  const from = ui.el('input', { id: 'pd-q-from', type: 'date' });
  const sub2 = subAccountSelect('pd-q-sub2', false);

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'pd-q-layer', label: '限额层级', required: true, control: layer }));
  grid.appendChild(ui.field({ id: 'pd-q-sub2', label: '子账户', required: false, help: '层①/层② 必填；层③ 强制取哨兵 0（后端处理）', control: sub2 }));
  grid.appendChild(ui.field({ id: 'pd-q-ins', label: '合约代码', required: false, help: '仅层②必填', control: ins }));
  grid.appendChild(ui.field({ id: 'pd-q-limit', label: '额度上限', required: true, help: '字符串化十进制，必须 ≥ 0', control: limit }));
  grid.appendChild(ui.field({ id: 'pd-q-unit', label: '额度单位', required: true, control: unit }));
  grid.appendChild(ui.field({ id: 'pd-q-from', label: '生效起始日', required: true, help: 'yyyy-MM-dd', control: from }));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '提交额度' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

  function syncLayer() {
    const isTwo = layer.value === 'SUB_INSTRUMENT';
    const isThree = layer.value === 'ACCOUNT_PRODUCT';
    ins.disabled = !isTwo;
    sub2.disabled = isThree;
  }
  layer.addEventListener('change', syncLayer);
  syncLayer();

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!/^\d+(\.\d+)?$/.test(limit.value.trim())) { ui.setFieldError(limit, '额度须为十进制数字（≥ 0）'); ok = false; } else ui.setFieldError(limit, '');
    if (!from.value) { ui.setFieldError(from, '请选择生效起始日'); ok = false; } else ui.setFieldError(from, '');
    if (layer.value === 'SUB_INSTRUMENT' && !ins.value.trim()) { ui.setFieldError(ins, '层②须填写合约代码'); ok = false; } else ui.setFieldError(ins, '');
    if (layer.value !== 'ACCOUNT_PRODUCT' && !sub2.value) { ui.setFieldError(sub2, '该层级须选择子账户'); ok = false; } else ui.setFieldError(sub2, '');
    if (!ok) return;

    const payload = {
      limitLayer: layer.value,
      subAccountId: layer.value === 'ACCOUNT_PRODUCT' ? 0 : Number(sub2.value),
      productId: Number(id),
      quotaLimit: limit.value.trim(),
      quotaUnit: unit.value,
      effectiveFrom: from.value
    };
    if (layer.value === 'SUB_INSTRUMENT') payload.instrumentId = ins.value.trim();
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const newId = await HG.api.post(R.QUOTA, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('额度已维护（id=' + newId + '，待下发 C++）', 'ok');
      form.reset(); syncLayer();
      if (sub.value) load();
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  card.appendChild(form);
  container.appendChild(card);

  // 若会话有当前子账户，自动查询一次
  if (sub.value) load();
}

function renderPanorama(box, pano, subAccountId) {
  ui.clear(box);
  const layer1 = (pano && pano.layer1SubProduct) || [];
  const layer2 = (pano && pano.layer2SubInstrument) || [];
  const layer3 = (pano && pano.layer3AccountProduct) || [];

  if (!layer1.length && !layer2.length && !layer3.length) {
    ui.renderEmpty(box, '该子账户在本品种下暂无任何层级额度行。');
    return;
  }
  box.appendChild(quotaTable('层① 子账户＋品种（SUB_PRODUCT，主维度）', layer1, false));
  box.appendChild(quotaTable('层② 子账户＋合约（SUB_INSTRUMENT）', layer2, true));
  box.appendChild(quotaTable('层③ 账户＋品种（ACCOUNT_PRODUCT，哨兵 0，影响全体子账户加总口径）', layer3, false));
  if (pano && pano.note) box.appendChild(ui.el('p', { class: 'hg-muted', text: '说明：' + pano.note }));
  box.appendChild(ui.el('p', { class: 'hg-muted', text: '注：已占用 / 已冻结为库内投影，判定权威源为 C++ 内存（R-RISK-9）；三层同时校验、单笔按最细命中者。' }));
}

function quotaTable(title, rows, withInstrument) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: title }));
  if (!rows.length) {
    card.appendChild(ui.el('p', { class: 'hg-muted', text: '该层级暂无额度行。' }));
    return card;
  }
  const columns = [{ title: '子账户', render: (r) => (r.subAccountId === 0 ? '账户级（0）' : '#' + F.esc(r.subAccountId)) }];
  if (withInstrument) columns.push({ title: '合约', render: (r) => F.esc(r.instrumentId || '—') });
  columns.push(
    { title: '额度上限', num: true, render: (r) => F.decimal(r.quotaLimit) },
    { title: '单位', render: (r) => F.esc(r.quotaUnit || '—') },
    { title: '已占用', num: true, render: (r) => F.decimal(r.usedQty) },
    { title: '已冻结', num: true, render: (r) => F.decimal(r.frozenQty) },
    { title: '生效起始', render: (r) => F.date(r.effectiveFrom) },
    { title: '生效截止', render: (r) => F.date(r.effectiveTo) },
    { title: '下发状态', render: (r) => ui.statusBadge(r.dispatchStatus) }
  );
  card.appendChild(ui.el('div', { html: ui.tableHTML(columns, rows) }));
  return card;
}

/* ---- 变更历史（FR-PRODUCT-14） ---- */
function buildChangeLog(container, id) {
  container.appendChild(ui.el('p', { class: 'hg-muted', text: '变更历史只增不改不删（FR-PRODUCT-14）；按时间倒序，含变更类型、前后值、操作人与时间。' }));
  const body = ui.el('div');
  container.appendChild(body);

  async function load() {
    ui.renderLoading(body, '正在加载变更历史…');
    try {
      const list = await HG.api.get(R.CHANGE_LOG, { productId: id });
      const items = Array.isArray(list) ? list : [];
      if (!items.length) {
        ui.renderEmpty(body, '该品种暂无变更记录。');
        return;
      }
      ui.clear(body);
      body.innerHTML = ui.tableHTML([
        { title: '时间', render: (r) => F.dateTime(r.changeTime) },
        { title: '类型', render: (r) => ui.statusBadge(CHANGE_TYPE[r.changeType] || r.changeType) },
        { title: '变更字段', render: (r) => F.esc(r.changedField || '—') },
        { title: '变更前', render: (r) => F.esc(r.beforeValue || '—') },
        { title: '变更后', render: (r) => F.esc(r.afterValue || '—') },
        { title: '子账户', render: (r) => (r.subAccountId === null || r.subAccountId === undefined ? '—' : '#' + F.esc(r.subAccountId)) },
        { title: '操作人', render: (r) => '#' + F.esc(r.operatorId) },
        { title: '原因', render: (r) => F.esc(r.changeReason || '—') }
      ], items);
    } catch (err) {
      ui.renderError(body, err, load);
    }
  }
  load();
}

/* ------------------------------------------------------------------ *
 * 注册
 * ------------------------------------------------------------------ */
HG.pages = HG.pages || {};
HG.pages.product = { renderList, renderDetail };

HG.router.register('/product', renderList);
HG.router.register('/product/:id', renderDetail);

export default HG.pages.product;
