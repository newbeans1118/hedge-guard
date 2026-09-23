/*
 * 系统配置页面（CONFIG）—— 管理界面（FR-CONFIG-1~15；T-021）。
 *
 * ## 字段名以已注册 Handler 的请求 / 视图类为准（camelCase；契约逐字对齐）
 *   GET  /config/item?key=KEY | ?category=CAT   → ConfigItemView | ConfigItemView[]（**bare 数组，无分页**）
 *        ConfigItemView {configKey, configCategory, configValue, valueType, enumScope, isSensitive(Y/N),
 *                        sensitiveState(UNSET|SET), persistScope, dispatchToCtp, effectiveVersion, description}
 *        （**敏感项 configValue 恒为 null**，仅回 sensitiveState，NFR-SEC-1）
 *   PUT  /config/item {configKey, newValue, reviewerId?, reason} → ConfigItemView（敏感项 newValue 必须为 null）
 *   GET  /config/sub-account                    → SubAccountView[]（bare 数组；**无 DELETE**）
 *        SubAccountView {id, code, name, category, status, initialFund(字符串)}
 *   POST /config/sub-account {subAccountCode, subAccountName, category, businessLineDesc}
 *   GET  /config/ctp?envType&page&size&sort      → PageData<FrontView>
 *        FrontView {id, envType, frontType, frontAddr, priority, brokerId, investorId,
 *                   connectTimeoutMs, reconnectIntervalMs, flowPath, isEnabled}
 *   POST /config/ctp {id, envType, frontType, frontAddr, priority, brokerId, investorId,
 *                     connectTimeoutMs, reconnectIntervalMs, flowPath, isEnabled} → id
 *   POST /config/ctp/test {envType}              → Map（M1 无桥接：以 10002 明确失败并给 nextStep）
 *   GET  /config/dict?dictType&page&size&sort    → PageData<ItemView>
 *        ItemView {id, dictType, itemCode, itemName, itemDesc, sortNo, status, isSystem}
 *   POST /config/dict {id, dictType, itemCode, itemName, itemDesc, sortNo, status, isSystem}
 *   GET  /config/subject?bizScene&page&size&sort → PageData<SubjectView>
 *        SubjectView {id, subjectCode, subjectName, parentCode, subjectLevel, direction, bizScene,
 *                     isTemplateDefault, editableByEnterprise, status}
 *   POST /config/subject {id, subjectCode, subjectName, parentCode, subjectLevel, direction,
 *                         bizScene, isTemplateDefault, editableByEnterprise, status}
 *   GET  /config/report-period?page&size&sort    → PageData<PeriodView>
 *        PeriodView {id, reportType, periodUnit, remindAdvanceDays, remindRoleCodes, isMandatory, isEnabled}
 *   POST /config/report-period {id, reportType, periodUnit, periodValue, remindAdvanceDays,
 *                               remindRoleCodes, isMandatory, isEnabled}
 *   GET  /config/dispatch/{id}                   → DispatchView
 *        DispatchView {id, paramVersion, dispatchType, subAccountId, batchIndex, batchTotal,
 *                      payloadDigest, recordCount, dispatchStatus(PENDING|SUCCESS|FAIL|UNCONFIRMED),
 *                      ackTime, ackDetail, retryCount, operatorId, dispatchTime}
 *   GET  /config/change-log?category&from&to&page&size&sort → PageData<LogView>
 *   POST /config/import {mode:INSERT_ONLY|OVERWRITE, items:[Item]} → {written}
 *   GET  /config/export                          → text/csv（强制排除敏感字段取值）
 *
 * ## 端点的 (方法,路径) 对来源（`ROUTES` 对象只含路径不含方法，方法取自 Java `router.register`）
 *   ConfigRoutes.java:41/42 → GET|PUT /config/item；:43/44 → GET|POST /config/sub-account；
 *   :46/47 → GET|POST /config/ctp；:48 → POST /config/ctp/test；:49/50 → GET|POST /config/dict；
 *   :51/52 → GET|POST /config/subject；:53/54 → GET|POST /config/report-period；
 *   :55 → GET /config/dispatch/{id}；:56 → GET /config/change-log；
 *   :57 → POST /config/import；:58 → GET /config/export。
 *
 * ## 配置域纪律（T-021 特有）
 *   · **零落盘红线（NFR-SEC-1 / R-CONFIG-1）**：密码 / AuthCode / 资金密码仅内存持有、启动手工填写、
 *     重启后须重新输入。**敏感项界面只显示"已设置/未设置"状态、不可读取取值**（FR-CONFIG-11 验收 2）；
 *     界面**不提供**"显示明文 / 保存凭证"入口，**不得**把任何凭据写入 localStorage/sessionStorage/URL。
 *   · **持久化范围（R-CONFIG-2）**：本地 admin 配置可持久化；API 下发配置不落盘；敏感字段系统级排除。
 *   · **下发纪律（R-CONFIG-3）**：无回执 / 失败时该变更**不得视为已生效**，须告警并提供重试入口（10014）。
 *   · **安全配置（R-CONFIG-5）**：组件通信仅监听回环地址；**不得**提供关闭鉴权的配置项（10021）。
 *   · **合规报告不可关闭（R-CONFIG-7）**：合规必需报告不得被配置为"不生成"。
 *
 * 纪律：不直连 DB / C++，只经 HG.api；金额 / 数量按字符串展示；失败统一经 HG.errmsg（阻断四项）；
 *       数据界面必配 加载 / 空 / 错误 三态。
 */
import { HG } from '../core/ns.js';
import '../core/format.js';
import '../core/router.js';
import '../core/routes.js';
import '../core/ui.js';

const ui = HG.ui;
const F = HG.format;
const R = HG.routes.CONFIG;

/* ------------------------------------------------------------------ *
 * 取值域（以 ConfigService 实测为准）
 * ------------------------------------------------------------------ */
const SUB_CATEGORY = { COMMODITY: '商品类（90% 红线）', TRADE: '贸易类（80% 红线）' };
const SUB_STATUS = { ENABLED: '启用', DISABLED: '停用' };
const CTP_ENV = { SIMULATION: '模拟盘（SIMULATION）', PRODUCTION: '实盘（PRODUCTION）' };
const CTP_FRONT = { TRADE: '交易前置（TRADE）', MD: '行情前置（MD）' };
const DICT_STATUS = { ENABLED: '启用', DISABLED: '停用' };
const SUBJECT_DIRECTION = { DEBIT: '借方（DEBIT）', CREDIT: '贷方（CREDIT）' };
const BIZ_SCENES = ['OPEN_POSITION', 'CLOSE_PNL', 'COMMISSION', 'MARGIN_CHANGE', 'FLOAT_PNL', 'PREMIUM', 'HEDGE_EFFECT', 'OTHER'];
const PERIOD_UNITS = { DAY: '日（DAY）', MONTH: '月（MONTH）', QUARTER: '季（QUARTER）', YEAR: '年（YEAR）' };
const IMPORT_MODES = { INSERT_ONLY: '仅新增（INSERT_ONLY）', OVERWRITE: '覆盖（OVERWRITE）' };
const DISPATCH_STATUS = { PENDING: '待回执', SUCCESS: '成功', FAIL: '失败', UNCONFIRMED: '未确认（不得视为已生效）' };
const YESNO = { Y: '是', N: '否' };
const CTP_SORTS = [{ v: 'id', t: '主键' }, { v: 'priority', t: '优先级' }, { v: 'env_type', t: '环境类型' }, { v: 'front_type', t: '前置类型' }];
const DICT_SORTS = [{ v: 'id', t: '主键' }, { v: 'dict_type', t: '字典类型' }, { v: 'item_code', t: '枚举编码' }, { v: 'sort_no', t: '排序号' }];
const SUBJECT_SORTS = [{ v: 'id', t: '主键' }, { v: 'subject_code', t: '科目编码' }, { v: 'subject_level', t: '科目级次' }];
const PERIOD_SORTS = [{ v: 'id', t: '主键' }, { v: 'report_type', t: '报告类型' }, { v: 'period_unit', t: '周期单位' }];
const CLOG_SORTS = [{ v: 'change_time', t: '变更时间' }, { v: 'id', t: '主键' }, { v: 'config_key', t: '配置项' }];

/* ------------------------------------------------------------------ *
 * 通用小工具（与 auth.js 同形，避免依赖 core 改动）
 * ------------------------------------------------------------------ */
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

function attachPager(host, pg, go) {
  ui.clear(host);
  const totalPages = Math.max(1, Math.ceil(pg.total / pg.size));
  host.appendChild(ui.el('span', { class: 'hg-pager__info', text: '共 ' + pg.total + ' 条，第 ' + pg.page + ' / ' + totalPages + ' 页' }));
  const prev = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '上一页', disabled: pg.page <= 1 });
  prev.addEventListener('click', () => { if (pg.page > 1) go(pg.page - 1); });
  const next = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '下一页', disabled: pg.page >= totalPages });
  next.addEventListener('click', () => { if (pg.page < totalPages) go(pg.page + 1); });
  host.appendChild(prev);
  host.appendChild(next);
}

function sortControls(bar, state, fields, load, tag) {
  const sortSel = ui.el('select', { id: 'cf-sort-' + tag, 'aria-label': '排序字段' });
  fields.forEach((s) => sortSel.appendChild(ui.el('option', { value: s.v, text: s.t })));
  sortSel.value = state.sortField;
  sortSel.addEventListener('change', () => { state.sortField = sortSel.value; load(1); });
  bar.appendChild(ui.field({ id: sortSel.id, label: '排序字段', control: sortSel }));

  const dirSel = ui.el('select', { id: 'cf-dir-' + tag, 'aria-label': '排序方向' });
  dirSel.appendChild(ui.el('option', { value: 'desc', text: '降序' }));
  dirSel.appendChild(ui.el('option', { value: 'asc', text: '升序' }));
  dirSel.value = state.sortDir;
  dirSel.addEventListener('change', () => { state.sortDir = dirSel.value; load(1); });
  bar.appendChild(ui.field({ id: dirSel.id, label: '方向', control: dirSel }));

  const sizeSel = ui.el('select', { id: 'cf-size-' + tag, 'aria-label': '每页条数' });
  HG.api.PAGE_SIZES.forEach((s) => sizeSel.appendChild(ui.el('option', { value: String(s), text: s + ' 条/页' })));
  sizeSel.value = String(state.size);
  sizeSel.addEventListener('change', () => { state.size = Number(sizeSel.value); load(1); });
  bar.appendChild(ui.field({ id: sizeSel.id, label: '每页条数', control: sizeSel }));
}

function numInput(id, props) {
  return ui.el('input', Object.assign({ id: id, type: 'number', min: '0', inputmode: 'numeric' }, props || {}));
}

function triggerBlobDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'config.csv';
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.setTimeout(() => { URL.revokeObjectURL(url); }, 1000);
}

/* ------------------------------------------------------------------ *
 * Tab 1：配置项（FR-CONFIG-11 / 3 / 4）
 * ------------------------------------------------------------------ */
function itemsTab(container) {
  container.appendChild(ui.banner(
    '<strong>零落盘（NFR-SEC-1 / R-CONFIG-1）</strong>：敏感项（口令 / AuthCode / 资金密码）界面'
      + '<strong>只显示"已设置 / 未设置"状态、不可读取取值</strong>；本页<strong>不提供</strong>"显示明文 / 保存凭证"入口，'
      + '也不写入任何浏览器存储。持久化范围（R-CONFIG-2）：本地 admin 配置可持久化；API 下发配置不落盘；敏感字段系统级排除。',
    'warn'
  ));

  const queryCard = ui.el('div', { class: 'hg-card' });
  queryCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '查询配置项（GET /config/item）' }));
  const qForm = ui.el('form', { novalidate: 'novalidate' });
  const qAlert = ui.el('div', { role: 'alert' });
  qForm.appendChild(qAlert);
  const mode = ui.el('select', { id: 'cf-q-mode' });
  mode.appendChild(ui.el('option', { value: 'key', text: '按 Key 精确查询（?key=）' }));
  mode.appendChild(ui.el('option', { value: 'category', text: '按分类列出（?category=）' }));
  const qVal = ui.el('input', { id: 'cf-q-val', type: 'text', maxlength: '128' });
  const qGrid = ui.el('div', { class: 'hg-grid-2' });
  qGrid.appendChild(ui.field({ id: 'cf-q-mode', label: '查询方式', required: true, control: mode }));
  const qValField = ui.field({
    id: 'cf-q-val', label: 'Key 或 分类', required: true,
    help: '分类为库内取值（业务分类：CTP 连接类 / 风控参数类 / 子账户类 / 组件通信类 / 报告周期类 / 科目与字典类）',
    control: qVal
  });
  qGrid.appendChild(qValField);
  qForm.appendChild(qGrid);
  const qBtn = ui.el('button', { class: 'hg-btn', type: 'submit', text: '查询' });
  qForm.appendChild(ui.el('div', { class: 'hg-form-actions' }, qBtn));
  queryCard.appendChild(qForm);

  const resultHost = ui.el('div');
  queryCard.appendChild(resultHost);
  container.appendChild(queryCard);

  const changeCard = ui.el('div', { class: 'hg-card' });
  changeCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '变更取值（PUT /config/item）' }));
  const cForm = ui.el('form', { novalidate: 'novalidate' });
  const cAlert = ui.el('div', { role: 'alert' });
  cForm.appendChild(cAlert);
  const cKey = ui.el('input', { id: 'cf-c-key', type: 'text', maxlength: '128' });
  const cNew = ui.el('input', { id: 'cf-c-new', type: 'text', maxlength: '500' });
  const cReason = ui.el('input', { id: 'cf-c-reason', type: 'text', maxlength: '200' });
  const cRev = numInput('cf-c-reviewer', { min: '1' });
  const cGrid = ui.el('div', { class: 'hg-grid-2' });
  cGrid.appendChild(ui.field({ id: 'cf-c-key', label: '配置 Key', required: true, control: cKey }));
  cGrid.appendChild(ui.field({ id: 'cf-c-new', label: '新取值', required: false, help: '敏感项不可传值（须留空，仅记"已设置/已重置"状态）', control: cNew }));
  cGrid.appendChild(ui.field({ id: 'cf-c-reason', label: '变更原因', required: true, control: cReason }));
  cGrid.appendChild(ui.field({ id: 'cf-c-reviewer', label: '复核人 ID（须双人复核项必填）', required: false, help: '影响合规 / 需双人复核的配置项须填写且 ≠ 操作人', control: cRev }));
  cForm.appendChild(cGrid);
  const cBtn = ui.el('button', { class: 'hg-btn', type: 'submit', text: '提交变更' });
  cForm.appendChild(ui.el('div', { class: 'hg-form-actions' }, cBtn));
  changeCard.appendChild(cForm);
  container.appendChild(changeCard);

  function itemRow(r) {
    return [
      { title: '配置 Key', render: (x) => F.esc(x.configKey) },
      { title: '分类', render: (x) => F.esc(x.configCategory || '—') },
      {
        title: '取值', render: (x) => (x.isSensitive === 'Y'
          ? ui.statusBadge(x.sensitiveState === 'SET' ? '已设置（不可读取）' : '未设置（不可读取）')
          : F.esc(x.configValue == null ? '—' : x.configValue))
      },
      { title: '值类型', render: (x) => F.esc(x.valueType || '—') },
      { title: '取值域', render: (x) => F.esc(x.enumScope || '—') },
      { title: '敏感', render: (x) => ui.statusBadge(x.isSensitive === 'Y' ? '敏感（零落盘）' : '否') },
      { title: '持久化', render: (x) => F.esc(x.persistScope || '—') },
      { title: '需下发', render: (x) => ui.statusBadge(x.dispatchToCtp === 'Y' ? '是' : '否') },
      { title: '生效版本', render: (x) => F.esc(x.effectiveVersion) },
      { title: '说明', render: (x) => F.esc(x.description || '—') }
    ];
  }

  function renderItems(items) {
    ui.clear(resultHost);
    if (!items.length) { ui.renderEmpty(resultHost, '未找到匹配的配置项。'); return; }
    resultHost.innerHTML = ui.tableHTML(itemRow(), items);
    // 若只查得一个敏感项，自动填入变更表单并禁用取值
    if (items.length === 1) {
      cKey.value = items[0].configKey;
      const sen = items[0].isSensitive === 'Y';
      cNew.disabled = sen;
      cNew.value = '';
      ui.setFieldError(cNew, sen ? '敏感项不可传值（后端将拒绝非空取值）' : '');
    }
  }

  qForm.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(qAlert);
    if (!qVal.value.trim()) { ui.setFieldError(qVal, '请填写 Key 或 分类'); return; }
    ui.setFieldError(qVal, '');
    ui.renderLoading(resultHost, '正在查询配置项…');
    qBtn.disabled = true; const orig = qBtn.textContent; qBtn.textContent = '查询中…';
    try {
      const data = await HG.api.get(R.ITEM, mode.value === 'key' ? { key: qVal.value.trim() } : { category: qVal.value.trim() });
      renderItems(Array.isArray(data) ? data : (data ? [data] : []));
    } catch (err) {
      ui.renderError(resultHost, err, () => {});
      qAlert.innerHTML = ui.blockedHTML(err);
    } finally {
      qBtn.disabled = false; qBtn.textContent = orig;
    }
  });

  cForm.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(cAlert);
    if (!cKey.value.trim()) { ui.setFieldError(cKey, '请填写配置 Key'); return; }
    if (!cReason.value.trim()) { ui.setFieldError(cReason, '请填写变更原因（留痕要求）'); return; }
    const payload = { configKey: cKey.value.trim(), newValue: cNew.disabled || cNew.value === '' ? null : cNew.value, reason: cReason.value.trim() };
    if (cRev.value) payload.reviewerId = Number(cRev.value);
    cBtn.disabled = true; const orig = cBtn.textContent; cBtn.textContent = '提交中…';
    try {
      const view = await HG.api.put(R.ITEM, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('配置已变更' + (view && view.dispatchToCtp === 'Y' ? '（待下发 C++，见参数下发 Tab）' : ''), 'ok');
      if (view) renderItems([view]);
      cReason.value = '';
    } catch (err) {
      cAlert.innerHTML = ui.blockedHTML(err); // 10020 敏感项 / 10021 绑定键 在此渲染四项
      window.scrollTo(0, 0);
    } finally {
      cBtn.disabled = false; cBtn.textContent = orig;
    }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 2：子账户（FR-CONFIG-7 / 8）
 * ------------------------------------------------------------------ */
function subAccountTab(container) {
  container.appendChild(ui.banner(
    '<strong>子账户不可物理删除，仅可停用</strong>（R-CONFIG-4 / FR-CONFIG-7 验收 3）：本页<strong>不提供</strong>删除入口；'
      + '停用条件（资金 0 + 持仓 0 + 无在途指令 + 当日已结算）由后端校验；编码全系统唯一且不可修改（R-CONFIG-4）。',
    'warn'
  ));
  const body = ui.el('div');
  container.appendChild(body);

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '创建子账户（POST /config/sub-account）' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);
  const code = ui.el('input', { id: 'cf-sa-code', type: 'text', maxlength: '64' });
  const name = ui.el('input', { id: 'cf-sa-name', type: 'text', maxlength: '128' });
  const cat = ui.el('select', { id: 'cf-sa-cat' });
  cat.appendChild(ui.el('option', { value: '', text: '请选择类别' }));
  Object.keys(SUB_CATEGORY).forEach((k) => cat.appendChild(ui.el('option', { value: k, text: SUB_CATEGORY[k] })));
  const biz = ui.el('input', { id: 'cf-sa-biz', type: 'text', maxlength: '200' });
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'cf-sa-code', label: '子账户编码', required: true, help: '全系统唯一、不可修改', control: code }));
  grid.appendChild(ui.field({ id: 'cf-sa-name', label: '子账户名称', required: true, control: name }));
  grid.appendChild(ui.field({ id: 'cf-sa-cat', label: '子账户类别', required: true, help: '商品类 90% / 贸易类 80%；可按品种覆盖（Q-2）', control: cat }));
  grid.appendChild(ui.field({ id: 'cf-sa-biz', label: '业务线说明', required: false, control: biz }));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '创建子账户' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  card.appendChild(form);
  container.appendChild(card);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!code.value.trim()) { ui.setFieldError(code, '请填写子账户编码'); ok = false; } else ui.setFieldError(code, '');
    if (!name.value.trim()) { ui.setFieldError(name, '请填写子账户名称'); ok = false; } else ui.setFieldError(name, '');
    if (!cat.value) { ui.setFieldError(cat, '请选择子账户类别（CFG-02c）'); ok = false; } else ui.setFieldError(cat, '');
    if (!ok) return;
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const view = await HG.api.post(R.SUB_ACCOUNT, {
        subAccountCode: code.value.trim(), subAccountName: name.value.trim(),
        category: cat.value, businessLineDesc: biz.value.trim() || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('子账户已创建（' + (view && view.code ? view.code : '') + '），状态=启用', 'ok');
      form.reset();
      load();
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
      window.scrollTo(0, 0);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  function load() {
    ui.renderLoading(body, '正在加载子账户列表…');
    return HG.api.get(R.SUB_ACCOUNT).then((data) => {
      const items = Array.isArray(data) ? data : ((data && data.items) || []);
      if (!items.length) { ui.renderEmpty(body, '暂无子账户。可上方创建。'); return; }
      ui.clear(body);
      body.innerHTML = ui.tableHTML([
        { title: 'ID', render: (r) => '#' + F.esc(r.id) },
        { title: '编码', render: (r) => F.esc(r.code) },
        { title: '名称', render: (r) => F.esc(r.name) },
        { title: '类别', render: (r) => ui.statusBadge(SUB_CATEGORY[r.category] || r.category || '—') },
        { title: '状态', render: (r) => ui.statusBadge(SUB_STATUS[r.status] || r.status) },
        { title: '期初资金', num: true, render: (r) => F.money(r.initialFund) }
      ], items);
      body.appendChild(ui.el('p', { class: 'hg-muted', text: '注：本页只提供"创建"与"查看"（后端仅注册 GET|POST /config/sub-account，无停用动作端点）——' + '停用为待补端点；参数（品种白名单 / 额度 / 资金额度）在 PRODUCT / FUND 页维护。' }));
    }).catch((err) => ui.renderError(body, err, load));
  }
  load();
}

/* ------------------------------------------------------------------ *
 * Tab 3：CTP 前置（FR-CONFIG-1 / 10）
 * ------------------------------------------------------------------ */
function ctpTab(container) {
  container.appendChild(ui.banner(
    '<strong>零落盘（NFR-SEC-1 / R-CONFIG-1）</strong>：本资源（CTP 前置配置）结构上<strong>无凭据列</strong>——界面不提供'
      + '口令 / AuthCode / 资金密码的输入与回显，响应天然不含敏感字段。模拟盘与实盘<strong>显式分离</strong>（R-CONFIG-9），'
      + '防止误连实盘。连接测试在 M1 无 HGCP 桥接时以 <code>10002</code> 明确失败并给出下一步（不伪报成功）。',
    'warn'
  ));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'id', sortDir: 'asc', envType: '', raw: [] };
  const bar = ui.el('div', { class: 'hg-toolbar' });
  const envSel = ui.el('select', { id: 'cf-ctp-env' });
  envSel.appendChild(ui.el('option', { value: '', text: '全部环境' }));
  Object.keys(CTP_ENV).forEach((k) => envSel.appendChild(ui.el('option', { value: k, text: CTP_ENV[k] })));
  envSel.addEventListener('change', () => { state.envType = envSel.value; load(1); });
  bar.appendChild(ui.field({ id: 'cf-ctp-env', label: '环境筛选（服务端 envType）', control: envSel }));
  sortControls(bar, state, CTP_SORTS, load, 'ctp');
  const reload = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  reload.addEventListener('click', () => load(state.page));
  bar.appendChild(reload);
  container.appendChild(bar);

  const alertBox = ui.el('div', { role: 'alert' });
  container.appendChild(alertBox);
  const body = ui.el('div');
  container.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': 'CTP 前置分页' });
  container.appendChild(pager);

  const testCard = ui.el('div', { class: 'hg-card' });
  testCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '连接测试（POST /config/ctp/test）' }));
  const testForm = ui.el('form', { novalidate: 'novalidate' });
  const tAlert = ui.el('div', { role: 'alert' });
  testForm.appendChild(tAlert);
  const tEnv = ui.el('select', { id: 'cf-ctp-tenv' });
  Object.keys(CTP_ENV).forEach((k) => tEnv.appendChild(ui.el('option', { value: k, text: CTP_ENV[k] })));
  testForm.appendChild(ui.field({ id: 'cf-ctp-tenv', label: '测试环境', required: true, control: tEnv }));
  const tBtn = ui.el('button', { class: 'hg-btn', type: 'submit', text: '测试连接' });
  testForm.appendChild(ui.el('div', { class: 'hg-form-actions' }, tBtn));
  const tResult = ui.el('div');
  testForm.appendChild(tResult);
  testCard.appendChild(testForm);
  container.appendChild(testCard);

  const saveCard = ui.el('div', { class: 'hg-card' });
  saveCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '新增 / 更新前置（POST /config/ctp）' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const fAlert = ui.el('div', { role: 'alert' });
  form.appendChild(fAlert);
  const idIn = numInput('cf-ctp-id', { min: '0' });
  const env = ui.el('select', { id: 'cf-ctp-senv' });
  env.appendChild(ui.el('option', { value: '', text: '请选择环境' }));
  Object.keys(CTP_ENV).forEach((k) => env.appendChild(ui.el('option', { value: k, text: CTP_ENV[k] })));
  const frontType = ui.el('select', { id: 'cf-ctp-ftype' });
  frontType.appendChild(ui.el('option', { value: '', text: '请选择前置类型' }));
  Object.keys(CTP_FRONT).forEach((k) => frontType.appendChild(ui.el('option', { value: k, text: CTP_FRONT[k] })));
  const addr = ui.el('input', { id: 'cf-ctp-addr', type: 'text', maxlength: '128', placeholder: 'tcp://host:port' });
  const priority = numInput('cf-ctp-prio', { min: '0' });
  const broker = ui.el('input', { id: 'cf-ctp-broker', type: 'text', maxlength: '64' });
  const investor = ui.el('input', { id: 'cf-ctp-investor', type: 'text', maxlength: '64' });
  const timeout = numInput('cf-ctp-timeout', { min: '0' });
  const reconnect = numInput('cf-ctp-reconn', { min: '0' });
  const flow = ui.el('input', { id: 'cf-ctp-flow', type: 'text', maxlength: '256' });
  const enabled = ui.el('select', { id: 'cf-ctp-enabled' });
  enabled.appendChild(ui.el('option', { value: 'Y', text: '启用' }));
  enabled.appendChild(ui.el('option', { value: 'N', text: '停用' }));
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'cf-ctp-id', label: '主键（> 0 更新，0 = 新增）', required: false, control: idIn }));
  grid.appendChild(ui.field({ id: 'cf-ctp-senv', label: '环境类型', required: true, control: env }));
  grid.appendChild(ui.field({ id: 'cf-ctp-ftype', label: '前置类型', required: true, help: '行情（MD）与交易（TRADE）可分别配置', control: frontType }));
  grid.appendChild(ui.field({ id: 'cf-ctp-addr', label: '前置地址', required: true, help: 'tcp://host:port', control: addr }));
  grid.appendChild(ui.field({ id: 'cf-ctp-prio', label: '优先级', required: false, help: '0 = 主，>0 = 备份', control: priority }));
  grid.appendChild(ui.field({ id: 'cf-ctp-broker', label: '经纪商代码', required: true, control: broker }));
  grid.appendChild(ui.field({ id: 'cf-ctp-investor', label: '用户代码（资金账号）', required: true, help: '非口令；口令 / AuthCode 零落盘，界面不采集', control: investor }));
  grid.appendChild(ui.field({ id: 'cf-ctp-timeout', label: '连接超时（毫秒）', required: false, control: timeout }));
  grid.appendChild(ui.field({ id: 'cf-ctp-reconn', label: '重连间隔（毫秒）', required: false, control: reconnect }));
  grid.appendChild(ui.field({ id: 'cf-ctp-flow', label: '流文件目录', required: false, help: '非敏感；不得存放明文凭证', control: flow }));
  grid.appendChild(ui.field({ id: 'cf-ctp-enabled', label: '是否启用', required: true, control: enabled }));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '提交前置配置' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  saveCard.appendChild(form);
  container.appendChild(saveCard);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(fAlert);
    let ok = true;
    if (!env.value) { ui.setFieldError(env, '请选择环境类型'); ok = false; } else ui.setFieldError(env, '');
    if (!frontType.value) { ui.setFieldError(frontType, '请选择前置类型'); ok = false; } else ui.setFieldError(frontType, '');
    if (!/^tcp:\/\/.+:\d+$/.test(addr.value.trim())) { ui.setFieldError(addr, '地址须为 tcp://host:port 形式'); ok = false; } else ui.setFieldError(addr, '');
    if (!broker.value.trim()) { ui.setFieldError(broker, '请填写经纪商代码'); ok = false; } else ui.setFieldError(broker, '');
    if (!investor.value.trim()) { ui.setFieldError(investor, '请填写用户代码'); ok = false; } else ui.setFieldError(investor, '');
    if (!ok) return;
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const newId = await HG.api.post(R.CTP, {
        id: Number(idIn.value) || 0, envType: env.value, frontType: frontType.value, frontAddr: addr.value.trim(),
        priority: Number(priority.value) || 0, brokerId: broker.value.trim(), investorId: investor.value.trim(),
        connectTimeoutMs: Number(timeout.value) || 0, reconnectIntervalMs: Number(reconnect.value) || 0,
        flowPath: flow.value.trim() || null, isEnabled: enabled.value
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('前置配置已提交（id=' + newId + '）；地址变更须重新连接并验证', 'ok');
      form.reset();
      load(state.page);
    } catch (err) {
      fAlert.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  testForm.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(tAlert);
    ui.clear(tResult);
    tBtn.disabled = true; const orig = tBtn.textContent; tBtn.textContent = '测试中…';
    try {
      const res = await HG.api.post(R.CTP_TEST, { envType: tEnv.value }, { idempotencyKey: HG.api.newIdempotencyKey() });
      const ok = res && (res.connected === true || res.success === true);
      tResult.appendChild(ui.banner(ok ? '连接测试成功。' : '连接测试返回（详见响应）：' + F.esc(JSON.stringify(res)), ok ? 'ok' : 'warn'));
    } catch (err) {
      // M1：10002（交易组件通信不可用）——如实呈现，不伪报成功
      tResult.innerHTML = ui.blockedHTML(err);
    } finally {
      tBtn.disabled = false; tBtn.textContent = orig;
    }
  });

  function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载 CTP 前置配置…');
    ui.clear(pager);
    const query = { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (state.envType) query.envType = state.envType;
    return HG.api.get(R.CTP, query).then((data) => {
      const pg = normalizePage(data, state);
      state.page = pg.page; state.size = pg.size; state.raw = pg.items;
      if (!pg.items.length) { ui.renderEmpty(body, '暂无 CTP 前置配置。'); }
      else {
        ui.clear(body);
        body.innerHTML = ui.tableHTML([
          { title: 'ID', render: (r) => '#' + F.esc(r.id) },
          { title: '环境', render: (r) => ui.statusBadge(CTP_ENV[r.envType] || r.envType) },
          { title: '前置类型', render: (r) => ui.statusBadge(CTP_FRONT[r.frontType] || r.frontType) },
          { title: '地址', render: (r) => '<span class="hg-mono">' + F.esc(r.frontAddr) + '</span>' },
          { title: '优先级', render: (r) => F.esc(r.priority) },
          { title: '经纪商', render: (r) => F.esc(r.brokerId || '—') },
          { title: '用户代码', render: (r) => F.esc(r.investorId || '—') },
          { title: '超时(ms)', render: (r) => F.esc(r.connectTimeoutMs) },
          { title: '重连(ms)', render: (r) => F.esc(r.reconnectIntervalMs) },
          { title: '流文件目录', render: (r) => F.esc(r.flowPath || '—') },
          { title: '启用', render: (r) => ui.statusBadge(YESNO[r.isEnabled] || r.isEnabled) }
        ], pg.items);
      }
      attachPager(pager, pg, load);
    }).catch((err) => { ui.renderError(body, err, () => load(state.page)); ui.clear(pager); });
  }
  load(1);
}

/* ------------------------------------------------------------------ *
 * Tab 4：枚举字典（FR-CONFIG-14）
 * ------------------------------------------------------------------ */
function dictTab(container) {
  container.appendChild(ui.el('p', { class: 'hg-muted', text: '已被历史数据引用的枚举项不得删除，只能停用（R-CONFIG-6 / FR-CONFIG-14 验收 2）；本页不提供删除入口。' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'id', sortDir: 'asc', dictType: '', raw: [] };
  const bar = ui.el('div', { class: 'hg-toolbar' });
  const dt = ui.el('input', { id: 'cf-d-dt', type: 'text', maxlength: '64', placeholder: '字典类型（如 trade_reason）' });
  dt.addEventListener('change', () => { state.dictType = dt.value.trim(); load(1); });
  bar.appendChild(ui.field({ id: 'cf-d-dt', label: '字典类型筛选（服务端 dictType）', control: dt }));
  sortControls(bar, state, DICT_SORTS, load, 'dict');
  const reload = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  reload.addEventListener('click', () => load(state.page));
  bar.appendChild(reload);
  container.appendChild(bar);

  const alertBox = ui.el('div', { role: 'alert' });
  container.appendChild(alertBox);
  const body = ui.el('div');
  container.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '字典分页' });
  container.appendChild(pager);

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '新增 / 停用枚举项（POST /config/dict）' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const fAlert = ui.el('div', { role: 'alert' });
  form.appendChild(fAlert);
  const idIn = numInput('cf-d-id', { min: '0' });
  const dtype = ui.el('input', { id: 'cf-d-type', type: 'text', maxlength: '64' });
  const icode = ui.el('input', { id: 'cf-d-code', type: 'text', maxlength: '64' });
  const iname = ui.el('input', { id: 'cf-d-name', type: 'text', maxlength: '128' });
  const idesc = ui.el('input', { id: 'cf-d-desc', type: 'text', maxlength: '200' });
  const sortNo = numInput('cf-d-sort', { min: '0' });
  const st = ui.el('select', { id: 'cf-d-status' });
  Object.keys(DICT_STATUS).forEach((k) => st.appendChild(ui.el('option', { value: k, text: DICT_STATUS[k] })));
  const isSys = ui.el('select', { id: 'cf-d-sys' });
  isSys.appendChild(ui.el('option', { value: 'N', text: '非系统内置' }));
  isSys.appendChild(ui.el('option', { value: 'Y', text: '系统内置' }));
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'cf-d-id', label: '主键（> 0 更新/停用，0 = 新增）', required: false, control: idIn }));
  grid.appendChild(ui.field({ id: 'cf-d-type', label: '字典类型', required: true, control: dtype }));
  grid.appendChild(ui.field({ id: 'cf-d-code', label: '枚举编码', required: true, help: '与字典类型联合唯一', control: icode }));
  grid.appendChild(ui.field({ id: 'cf-d-name', label: '枚举名称', required: true, control: iname }));
  grid.appendChild(ui.field({ id: 'cf-d-desc', label: '业务含义说明', required: false, control: idesc }));
  grid.appendChild(ui.field({ id: 'cf-d-sort', label: '排序号', required: false, control: sortNo }));
  grid.appendChild(ui.field({ id: 'cf-d-status', label: '状态', required: true, help: '已引用项请用"停用"代替删除', control: st }));
  grid.appendChild(ui.field({ id: 'cf-d-sys', label: '系统内置', required: false, control: isSys }));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '保存枚举项' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  card.appendChild(form);
  container.appendChild(card);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(fAlert);
    let ok = true;
    if (!dtype.value.trim()) { ui.setFieldError(dtype, '请填写字典类型'); ok = false; } else ui.setFieldError(dtype, '');
    if (!icode.value.trim()) { ui.setFieldError(icode, '请填写枚举编码'); ok = false; } else ui.setFieldError(icode, '');
    if (!iname.value.trim()) { ui.setFieldError(iname, '请填写枚举名称'); ok = false; } else ui.setFieldError(iname, '');
    if (!ok) return;
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const newId = await HG.api.post(R.DICT, {
        id: Number(idIn.value) || 0, dictType: dtype.value.trim(), itemCode: icode.value.trim(),
        itemName: iname.value.trim(), itemDesc: idesc.value.trim() || null, sortNo: Number(sortNo.value) || 0,
        status: st.value, isSystem: isSys.value
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('枚举项已保存（id=' + newId + '）', 'ok');
      form.reset();
      load(state.page);
    } catch (err) {
      fAlert.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载枚举字典…');
    ui.clear(pager);
    const query = { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (state.dictType) query.dictType = state.dictType;
    return HG.api.get(R.DICT, query).then((data) => {
      const pg = normalizePage(data, state);
      state.page = pg.page; state.size = pg.size; state.raw = pg.items;
      if (!pg.items.length) { ui.renderEmpty(body, '暂无字典项。'); }
      else {
        ui.clear(body);
        body.innerHTML = ui.tableHTML([
          { title: 'ID', render: (r) => '#' + F.esc(r.id) },
          { title: '字典类型', render: (r) => F.esc(r.dictType) },
          { title: '枚举编码', render: (r) => F.esc(r.itemCode) },
          { title: '枚举名称', render: (r) => F.esc(r.itemName) },
          { title: '说明', render: (r) => F.esc(r.itemDesc || '—') },
          { title: '排序号', render: (r) => F.esc(r.sortNo) },
          { title: '状态', render: (r) => ui.statusBadge(DICT_STATUS[r.status] || r.status) },
          { title: '系统内置', render: (r) => ui.statusBadge(YESNO[r.isSystem] || r.isSystem || '—') }
        ], pg.items);
      }
      attachPager(pager, pg, load);
    }).catch((err) => { ui.renderError(body, err, () => load(state.page)); ui.clear(pager); });
  }
  load(1);
}

/* ------------------------------------------------------------------ *
 * Tab 5：会计科目（FR-CONFIG-14）
 * ------------------------------------------------------------------ */
function subjectTab(container) {
  container.appendChild(ui.el('p', { class: 'hg-muted', text: '会计科目由字典驱动、不得在代码中硬编码（R-CONFIG-6）；供 ACCT 生成凭证引用；系统提供常用科目模板，企业可增删改（Q-9）。' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'id', sortDir: 'asc', bizScene: '', raw: [] };
  const bar = ui.el('div', { class: 'hg-toolbar' });
  const bs = ui.el('select', { id: 'cf-su-bs' });
  bs.appendChild(ui.el('option', { value: '', text: '全部业务场景' }));
  BIZ_SCENES.forEach((s) => bs.appendChild(ui.el('option', { value: s, text: s })));
  bs.addEventListener('change', () => { state.bizScene = bs.value; load(1); });
  bar.appendChild(ui.field({ id: 'cf-su-bs', label: '业务场景筛选（服务端 bizScene）', control: bs }));
  sortControls(bar, state, SUBJECT_SORTS, load, 'subject');
  const reload = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  reload.addEventListener('click', () => load(state.page));
  bar.appendChild(reload);
  container.appendChild(bar);

  const alertBox = ui.el('div', { role: 'alert' });
  container.appendChild(alertBox);
  const body = ui.el('div');
  container.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '科目分页' });
  container.appendChild(pager);

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '新增 / 维护科目（POST /config/subject）' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const fAlert = ui.el('div', { role: 'alert' });
  form.appendChild(fAlert);
  const idIn = numInput('cf-su-id', { min: '0' });
  const scode = ui.el('input', { id: 'cf-su-code', type: 'text', maxlength: '32' });
  const sname = ui.el('input', { id: 'cf-su-name', type: 'text', maxlength: '128' });
  const parent = ui.el('input', { id: 'cf-su-parent', type: 'text', maxlength: '32' });
  const level = numInput('cf-su-level', { min: '1' });
  const dirSel = ui.el('select', { id: 'cf-su-dir' });
  Object.keys(SUBJECT_DIRECTION).forEach((k) => dirSel.appendChild(ui.el('option', { value: k, text: SUBJECT_DIRECTION[k] })));
  const bizSel = ui.el('select', { id: 'cf-su-biz' });
  BIZ_SCENES.forEach((s) => bizSel.appendChild(ui.el('option', { value: s, text: s })));
  const tpl = ui.el('select', { id: 'cf-su-tpl' });
  tpl.appendChild(ui.el('option', { value: 'N', text: '非预置模板' }));
  tpl.appendChild(ui.el('option', { value: 'Y', text: '系统预置模板项' }));
  const edit = ui.el('select', { id: 'cf-su-edit' });
  edit.appendChild(ui.el('option', { value: 'Y', text: '企业可修改' }));
  edit.appendChild(ui.el('option', { value: 'N', text: '企业不可修改' }));
  const st = ui.el('select', { id: 'cf-su-status' });
  Object.keys(DICT_STATUS).forEach((k) => st.appendChild(ui.el('option', { value: k, text: DICT_STATUS[k] })));
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'cf-su-id', label: '主键（> 0 更新，0 = 新增）', required: false, control: idIn }));
  grid.appendChild(ui.field({ id: 'cf-su-code', label: '科目编码', required: true, control: scode }));
  grid.appendChild(ui.field({ id: 'cf-su-name', label: '科目名称', required: true, control: sname }));
  grid.appendChild(ui.field({ id: 'cf-su-parent', label: '上级科目编码', required: false, help: '留空 = 一级科目', control: parent }));
  grid.appendChild(ui.field({ id: 'cf-su-level', label: '科目级次', required: false, control: level }));
  grid.appendChild(ui.field({ id: 'cf-su-dir', label: '余额方向', required: true, control: dirSel }));
  grid.appendChild(ui.field({ id: 'cf-su-biz', label: '适用业务场景', required: true, control: bizSel }));
  grid.appendChild(ui.field({ id: 'cf-su-tpl', label: '系统预置模板', required: false, control: tpl }));
  grid.appendChild(ui.field({ id: 'cf-su-edit', label: '企业可修改', required: false, control: edit }));
  grid.appendChild(ui.field({ id: 'cf-su-status', label: '状态', required: true, control: st }));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '保存科目' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  card.appendChild(form);
  container.appendChild(card);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(fAlert);
    let ok = true;
    if (!scode.value.trim()) { ui.setFieldError(scode, '请填写科目编码'); ok = false; } else ui.setFieldError(scode, '');
    if (!sname.value.trim()) { ui.setFieldError(sname, '请填写科目名称'); ok = false; } else ui.setFieldError(sname, '');
    if (!ok) return;
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const newId = await HG.api.post(R.SUBJECT, {
        id: Number(idIn.value) || 0, subjectCode: scode.value.trim(), subjectName: sname.value.trim(),
        parentCode: parent.value.trim() || null, subjectLevel: Number(level.value) || 1, direction: dirSel.value,
        bizScene: bizSel.value, isTemplateDefault: tpl.value, editableByEnterprise: edit.value, status: st.value
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('科目已保存（id=' + newId + '）', 'ok');
      form.reset();
      load(state.page);
    } catch (err) {
      fAlert.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载会计科目…');
    ui.clear(pager);
    const query = { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (state.bizScene) query.bizScene = state.bizScene;
    return HG.api.get(R.SUBJECT, query).then((data) => {
      const pg = normalizePage(data, state);
      state.page = pg.page; state.size = pg.size; state.raw = pg.items;
      if (!pg.items.length) { ui.renderEmpty(body, '暂无会计科目。'); }
      else {
        ui.clear(body);
        body.innerHTML = ui.tableHTML([
          { title: 'ID', render: (r) => '#' + F.esc(r.id) },
          { title: '科目编码', render: (r) => F.esc(r.subjectCode) },
          { title: '科目名称', render: (r) => F.esc(r.subjectName) },
          { title: '上级', render: (r) => F.esc(r.parentCode || '—') },
          { title: '级次', render: (r) => F.esc(r.subjectLevel) },
          { title: '方向', render: (r) => ui.statusBadge(SUBJECT_DIRECTION[r.direction] || r.direction || '—') },
          { title: '业务场景', render: (r) => F.esc(r.bizScene || '—') },
          { title: '模板', render: (r) => ui.statusBadge(YESNO[r.isTemplateDefault] || r.isTemplateDefault || '—') },
          { title: '企业可改', render: (r) => ui.statusBadge(YESNO[r.editableByEnterprise] || r.editableByEnterprise || '—') },
          { title: '状态', render: (r) => ui.statusBadge(DICT_STATUS[r.status] || r.status) }
        ], pg.items);
      }
      attachPager(pager, pg, load);
    }).catch((err) => { ui.renderError(body, err, () => load(state.page)); ui.clear(pager); });
  }
  load(1);
}

/* ------------------------------------------------------------------ *
 * Tab 6：报告周期（FR-CONFIG-15）
 * ------------------------------------------------------------------ */
function periodTab(container) {
  container.appendChild(ui.banner(
    '<strong>合规必需报告不得被配置关闭</strong>（R-CONFIG-7 / FR-CONFIG-15 验收 2）：每日 / 每月 / 每季度为 8 号文要求。'
      + '当"是否合规必需 = 是"时，"是否启用"强制为"是"（后端亦拒绝关闭，本页同步约束）。',
    'warn'
  ));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'id', sortDir: 'asc', raw: [] };
  const bar = ui.el('div', { class: 'hg-toolbar' });
  sortControls(bar, state, PERIOD_SORTS, load, 'period');
  const reload = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  reload.addEventListener('click', () => load(state.page));
  bar.appendChild(reload);
  container.appendChild(bar);

  const alertBox = ui.el('div', { role: 'alert' });
  container.appendChild(alertBox);
  const body = ui.el('div');
  container.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '报告周期分页' });
  container.appendChild(pager);

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '新增 / 维护报告周期（POST /config/report-period）' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const fAlert = ui.el('div', { role: 'alert' });
  form.appendChild(fAlert);
  const idIn = numInput('cf-rp-id', { min: '0' });
  const rtype = ui.el('input', { id: 'cf-rp-type', type: 'text', maxlength: '64', placeholder: '如 DAILY_SETTLE / RISK_DAILY / MONTHLY_RECON / QUARTERLY / ANNUAL' });
  const unit = ui.el('select', { id: 'cf-rp-unit' });
  Object.keys(PERIOD_UNITS).forEach((k) => unit.appendChild(ui.el('option', { value: k, text: PERIOD_UNITS[k] })));
  const pval = numInput('cf-rp-value', { min: '1' });
  const advance = numInput('cf-rp-advance', { min: '0' });
  const roles = ui.el('input', { id: 'cf-rp-roles', type: 'text', maxlength: '200', placeholder: '角色编码，逗号分隔' });
  const mandatory = ui.el('select', { id: 'cf-rp-mand' });
  mandatory.appendChild(ui.el('option', { value: 'N', text: '否' }));
  mandatory.appendChild(ui.el('option', { value: 'Y', text: '是（合规必需，不可关闭）' }));
  const enabled = ui.el('select', { id: 'cf-rp-en' });
  enabled.appendChild(ui.el('option', { value: 'Y', text: '启用' }));
  enabled.appendChild(ui.el('option', { value: 'N', text: '停用' }));
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'cf-rp-id', label: '主键（> 0 更新，0 = 新增）', required: false, control: idIn }));
  grid.appendChild(ui.field({ id: 'cf-rp-type', label: '报告类型', required: true, help: '唯一键 uk_report_period_type', control: rtype }));
  grid.appendChild(ui.field({ id: 'cf-rp-unit', label: '周期单位', required: true, control: unit }));
  grid.appendChild(ui.field({ id: 'cf-rp-value', label: '周期数量', required: true, control: pval }));
  grid.appendChild(ui.field({ id: 'cf-rp-advance', label: '提前提醒天数', required: false, control: advance }));
  grid.appendChild(ui.field({ id: 'cf-rp-roles', label: '提醒接收角色编码', required: false, help: '须为有效角色（管理层/审计岗等）', control: roles }));
  grid.appendChild(ui.field({ id: 'cf-rp-mand', label: '是否合规必需', required: true, control: mandatory }));
  grid.appendChild(ui.field({ id: 'cf-rp-en', label: '是否启用', required: true, help: '合规必需项强制启用', control: enabled }));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '保存报告周期' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  card.appendChild(form);
  container.appendChild(card);

  function syncMandatory() {
    if (mandatory.value === 'Y') { enabled.value = 'Y'; enabled.disabled = true; }
    else enabled.disabled = false;
  }
  mandatory.addEventListener('change', syncMandatory);
  syncMandatory();

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(fAlert);
    if (!rtype.value.trim()) { ui.setFieldError(rtype, '请填写报告类型'); return; }
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const newId = await HG.api.post(R.REPORT_PERIOD, {
        id: Number(idIn.value) || 0, reportType: rtype.value.trim(), periodUnit: unit.value,
        periodValue: Number(pval.value) || 1, remindAdvanceDays: Number(advance.value) || 0,
        remindRoleCodes: roles.value.trim() || null,
        isMandatory: mandatory.value, isEnabled: mandatory.value === 'Y' ? 'Y' : enabled.value
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('报告周期已保存（id=' + newId + '）', 'ok');
      form.reset(); syncMandatory();
      load(state.page);
    } catch (err) {
      fAlert.innerHTML = ui.blockedHTML(err); // 10021：尝试关闭合规必需报告
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载报告周期…');
    ui.clear(pager);
    return HG.api.get(R.REPORT_PERIOD, { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir })
      .then((data) => {
        const pg = normalizePage(data, state);
        state.page = pg.page; state.size = pg.size; state.raw = pg.items;
        if (!pg.items.length) { ui.renderEmpty(body, '暂无报告周期配置。'); }
        else {
          ui.clear(body);
          body.innerHTML = ui.tableHTML([
            { title: 'ID', render: (r) => '#' + F.esc(r.id) },
            { title: '报告类型', render: (r) => F.esc(r.reportType) },
            { title: '周期单位', render: (r) => F.esc(PERIOD_UNITS[r.periodUnit] || r.periodUnit) },
            { title: '周期数量', render: (r) => F.esc(r.periodValue) },
            { title: '提前提醒(天)', render: (r) => F.esc(r.remindAdvanceDays) },
            { title: '接收角色', render: (r) => F.esc(r.remindRoleCodes || '—') },
            { title: '合规必需', render: (r) => ui.statusBadge(r.isMandatory === 'Y' ? '合规必需（不可关闭）' : '否') },
            { title: '启用', render: (r) => ui.statusBadge(r.isEnabled === 'Y' ? '启用' : '停用') }
          ], pg.items);
        }
        attachPager(pager, pg, load);
      }).catch((err) => { ui.renderError(body, err, () => load(state.page)); ui.clear(pager); });
  }
  load(1);
}

/* ------------------------------------------------------------------ *
 * Tab 7：参数下发（FR-CONFIG-6 / R-CONFIG-3）
 * ------------------------------------------------------------------ */
function dispatchTab(container) {
  container.appendChild(ui.banner(
    '<strong>下发纪律（R-CONFIG-3）</strong>：凡影响事前风控的参数必须显式下发；<strong>无回执或失败时该变更'
      + '不得视为已生效</strong>并须告警。状态 <code>UNCONFIRMED</code> / <code>FAIL</code> 即"未生效"口径的载体。',
    'warn'
  ));
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '查询下发记录（GET /config/dispatch/{id}）' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);
  const idIn = numInput('cf-dp-id', { min: '1' });
  form.appendChild(ui.field({ id: 'cf-dp-id', label: '下发记录 ID', required: true, control: idIn }));
  const btn = ui.el('button', { class: 'hg-btn', type: 'submit', text: '查询' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, btn));
  card.appendChild(form);
  const body = ui.el('div');
  card.appendChild(body);
  container.appendChild(card);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    if (!idIn.value) { ui.setFieldError(idIn, '请填写下发记录 ID'); return; }
    ui.renderLoading(body, '正在查询下发记录…');
    btn.disabled = true; const orig = btn.textContent; btn.textContent = '查询中…';
    try {
      const d = await HG.api.get(R.dispatch(idIn.value));
      ui.clear(body);
      const notEffective = d.dispatchStatus === 'UNCONFIRMED' || d.dispatchStatus === 'FAIL' || d.dispatchStatus === 'PENDING';
      if (notEffective) body.appendChild(ui.banner('该下发状态为「' + F.esc(DISPATCH_STATUS[d.dispatchStatus] || d.dispatchStatus) + '」——按 R-CONFIG-3 不得视为已生效，请核查回执并按需重试。', 'warn'));
      const dl = ui.el('dl', { class: 'hg-desc' });
      [['下发 ID', '#' + d.id], ['参数版本', F.esc(d.paramVersion)], ['下发类型', F.esc(d.dispatchType || '—')],
        ['子账户', d.subAccountId == null ? '—' : '#' + F.esc(d.subAccountId)],
        ['分批', d.batchIndex == null ? '—' : F.esc(d.batchIndex) + ' / ' + F.esc(d.batchTotal)],
        ['内容摘要', F.esc(d.payloadDigest || '—')], ['记录条数', F.esc(d.recordCount)],
        ['下发状态', DISPATCH_STATUS[d.dispatchStatus] || d.dispatchStatus],
        ['下发时间', F.dateTime(d.dispatchTime) || '—'], ['回执时间', F.dateTime(d.ackTime) || '—'],
        ['回执详情', F.esc(d.ackDetail || '—')], ['重试次数', F.esc(d.retryCount)], ['操作人', '#' + F.esc(d.operatorId)]
      ].forEach((r) => { dl.appendChild(ui.el('dt', { text: r[0] })); dl.appendChild(ui.el('dd', { text: r[1] })); });
      body.appendChild(dl);
    } catch (err) {
      ui.renderError(body, err, () => {});
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      btn.disabled = false; btn.textContent = orig;
    }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 8：配置变更留痕（FR-CONFIG-12）
 * ------------------------------------------------------------------ */
function changeLogTab(container) {
  container.appendChild(ui.el('p', { class: 'hg-muted', text: '配置变更留痕只增不改不删，含配置项、前后值、操作人、复核人、时间、原因、下发结果（R-CONFIG-10 / FR-CONFIG-12）；敏感字段的值一律不记录（仅记"已设置/已重置"状态）；无删除入口。' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'change_time', sortDir: 'desc', category: '', from: '', to: '', raw: [] };
  const bar = ui.el('div', { class: 'hg-toolbar' });
  const cat = ui.el('input', { id: 'cf-cl-cat', type: 'text', maxlength: '64', placeholder: '分类（如 SUB_ACCOUNT）' });
  cat.addEventListener('change', () => { state.category = cat.value.trim(); load(1); });
  bar.appendChild(ui.field({ id: 'cf-cl-cat', label: '配置分类（服务端 category）', control: cat }));
  const from = ui.el('input', { id: 'cf-cl-from', type: 'date' });
  from.addEventListener('change', () => { state.from = from.value; load(1); });
  bar.appendChild(ui.field({ id: 'cf-cl-from', label: '起始日（闭区间）', control: from }));
  const to = ui.el('input', { id: 'cf-cl-to', type: 'date' });
  to.addEventListener('change', () => { state.to = to.value; load(1); });
  bar.appendChild(ui.field({ id: 'cf-cl-to', label: '截止日（闭区间）', control: to }));
  sortControls(bar, state, CLOG_SORTS, load, 'clog');
  const reload = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  reload.addEventListener('click', () => load(state.page));
  bar.appendChild(reload);
  container.appendChild(bar);

  const body = ui.el('div');
  container.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '配置变更留痕分页' });
  container.appendChild(pager);

  function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载配置变更留痕…');
    ui.clear(pager);
    const query = { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (state.category) query.category = state.category;
    if (state.from) query.from = state.from;
    if (state.to) query.to = state.to;
    return HG.api.get(R.CHANGE_LOG, query).then((data) => {
      const pg = normalizePage(data, state);
      state.page = pg.page; state.size = pg.size; state.raw = pg.items;
      if (!pg.items.length) { ui.renderEmpty(body, '暂无配置变更记录。'); }
      else {
        ui.clear(body);
        body.innerHTML = ui.tableHTML([
          { title: '时间', render: (r) => F.dateTime(r.changeTime) },
          { title: '配置 Key', render: (r) => F.esc(r.configKey || '—') },
          { title: '分类', render: (r) => F.esc(r.configCategory || '—') },
          { title: '变更前', render: (r) => F.esc(r.beforeValue || '—') },
          { title: '变更后', render: (r) => F.esc(r.afterValue || '—') },
          { title: '敏感', render: (r) => (r.isSensitiveChange === 'Y' ? ui.statusBadge('敏感（仅状态：' + F.esc((r.sensitiveStateBefore || '—') + '→' + (r.sensitiveStateAfter || '—')) + '）') : '—') },
          { title: '操作人', render: (r) => '#' + F.esc(r.operatorId) },
          { title: '复核人', render: (r) => (r.reviewerId == null ? '—' : '#' + F.esc(r.reviewerId)) },
          { title: '原因', render: (r) => F.esc(r.changeReason || '—') },
          { title: '下发结果', render: (r) => ui.statusBadge(r.dispatchResult || '—') }
        ], pg.items);
      }
      attachPager(pager, pg, load);
    }).catch((err) => { ui.renderError(body, err, () => load(state.page)); ui.clear(pager); });
  }
  load(1);
}

/* ------------------------------------------------------------------ *
 * Tab 9：系统参数导入导出（FR-CONFIG-13）
 * ------------------------------------------------------------------ */
function ioTab(container) {
  container.appendChild(ui.banner(
    '<strong>零落盘（NFR-SEC-1）</strong>：导出的参数文件中<strong>不含</strong>任何敏感字段取值（系统级强制，无可选项包含）；'
      + '导入须全量预校验，任一项不合法即整体不导入并给出逐项原因（<code>10019</code>）。',
    'warn'
  ));

  const exportCard = ui.el('div', { class: 'hg-card' });
  exportCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '导出系统参数（GET /config/export）' }));
  exportCard.appendChild(ui.el('p', { class: 'hg-muted', text: '导出为 CSV（text/csv）；敏感项取值列恒为空。' }));
  const alertBox = ui.el('div', { role: 'alert' });
  const exportBtn = ui.el('button', { id: 'cf-io-export', class: 'hg-btn', type: 'button', text: '导出 CSV' });
  exportCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, exportBtn));
  exportCard.appendChild(alertBox);
  container.appendChild(exportCard);

  exportBtn.addEventListener('click', async () => {
    ui.clear(alertBox);
    const orig = exportBtn.textContent;
    exportBtn.disabled = true; exportBtn.textContent = '导出中…';
    try {
      const res = await HG.api.download(R.EXPORT);
      const text = await res.blob.text();
      const rows = text.replace(/^\uFEFF/, '').split(/\r\n|\n/).filter((l) => l !== '');
      if (rows.length <= 1) {
        alertBox.appendChild(ui.banner('无可导出数据：服务端仅返回表头，未生成文件（FR-CONFIG-13 验收 1）。', 'warn'));
        return;
      }
      triggerBlobDownload(res.blob, res.filename || 'config.csv');
      ui.toast('已导出 ' + (rows.length - 1) + ' 条参数', 'ok');
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      exportBtn.disabled = false; exportBtn.textContent = orig;
    }
  });

  const importCard = ui.el('div', { class: 'hg-card' });
  importCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '导入系统参数（POST /config/import）' }));
  importCard.appendChild(ui.el('p', { class: 'hg-muted', text: '请求体：{mode, items:[{configKey, configCategory, valueType, ...}]}。导入须排除/拒绝敏感字段取值；任一项不合法即整体不导入。' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const iAlert = ui.el('div', { role: 'alert' });
  form.appendChild(iAlert);
  const modeSel = ui.el('select', { id: 'cf-io-mode' });
  Object.keys(IMPORT_MODES).forEach((k) => modeSel.appendChild(ui.el('option', { value: k, text: IMPORT_MODES[k] })));
  const area = ui.el('textarea', { id: 'cf-io-items', rows: '8', placeholder: '[{"configKey":"risk.term.max_months","configCategory":"RISK","valueType":"NUMBER","configValue":"12"}]' });
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'cf-io-mode', label: '导入模式', required: true, control: modeSel }));
  grid.appendChild(ui.field({ id: 'cf-io-items', label: 'items（JSON 数组）', required: true, help: '本地仅做 JSON 结构校验；业务校验由后端全量预校验', control: area }));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '开始导入' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  importCard.appendChild(form);
  container.appendChild(importCard);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(iAlert);
    let items;
    try {
      items = JSON.parse(area.value || 'null');
    } catch (e) {
      ui.setFieldError(area, 'JSON 解析失败：' + e.message);
      return;
    }
    if (!Array.isArray(items) || !items.length) { ui.setFieldError(area, '须为至少含一项的 JSON 数组'); return; }
    ui.setFieldError(area, '');
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '导入中…';
    try {
      const res = await HG.api.post(R.IMPORT, { mode: modeSel.value, items: items }, { idempotencyKey: HG.api.newIdempotencyKey() });
      const written = res && typeof res.written === 'number' ? res.written : 0;
      iAlert.appendChild(ui.banner('导入成功：写入 ' + written + ' 条；相关参数已触发 C++ 下发（见参数下发 Tab）。', 'ok'));
      ui.toast('导入成功（写入 ' + written + ' 条）', 'ok');
      area.value = '';
    } catch (err) {
      iAlert.innerHTML = importProblemsHTML(err);
      window.scrollTo(0, 0);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });
}

/** 把后端 10019 的逐项清单（并入 message）渲染为「摘要 + 逐条列表」。 */
function importProblemsHTML(err) {
  const msg = String((err && err.message) || '');
  const parts = msg.split(/\n\s*-\s*/);
  const summary = (parts.shift() || '').trim();
  const problems = parts.map((p) => p.trim()).filter((p) => p !== '');
  if (!problems.length) return ui.blockedHTML(err);
  const li = problems.map((p) => `<li>${F.esc(p)}</li>`).join('');
  return '<div class="hg-blocked">' +
    `<div class="hg-blocked__msg">${F.esc(summary || '导入文件校验失败')}</div>` +
    '<div class="hg-muted">逐项问题清单（已全量回滚、未产生部分写入）：</div>' +
    `<ul style="margin:6px 0 0 18px">${li}</ul>` +
    (err && err.traceId ? `<div class="hg-muted">traceId：<span class="hg-mono">${F.esc(err.traceId)}</span></div>` : '') +
    '</div>';
}

/* ------------------------------------------------------------------ *
 * 页面装配
 * ------------------------------------------------------------------ */
const TABS = [
  { key: 'items', label: '配置项', build: itemsTab },
  { key: 'subaccount', label: '子账户', build: subAccountTab },
  { key: 'ctp', label: 'CTP 前置', build: ctpTab },
  { key: 'dict', label: '枚举字典', build: dictTab },
  { key: 'subject', label: '会计科目', build: subjectTab },
  { key: 'period', label: '报告周期', build: periodTab },
  { key: 'dispatch', label: '参数下发', build: dispatchTab },
  { key: 'changelog', label: '配置变更留痕', build: changeLogTab },
  { key: 'io', label: '导入导出', build: ioTab }
];

function renderConfig(root, ctx) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '系统配置（CONFIG）' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '配置项 / 子账户 / CTP 前置 / 枚举字典 / 会计科目 / 报告周期 / 参数下发 / 变更留痕 / 导入导出（FR-CONFIG-1~15）。' }));
  root.appendChild(ui.banner(
    '<strong>零落盘红线（NFR-SEC-1 / R-CONFIG-1）</strong>：密码 / 期货认证码 AuthCode / 资金密码<strong>仅内存持有、'
      + '启动手工填写、重启后须重新输入</strong>；敏感项界面<strong>只显示"已设置/未设置"状态、不可读取取值</strong>，'
      + '本页<strong>不提供</strong>"显示明文 / 保存凭证"入口，也不把任何凭据写入 localStorage / sessionStorage / URL。'
      + '下发纪律（R-CONFIG-3）：无回执 / 失败的变更<strong>不得视为已生效</strong>。',
    'warn'
  ));

  const tabBar = ui.el('div', { class: 'hg-tabs', role: 'tablist' });
  const panel = ui.el('div');
  let active = (ctx && ctx.query && ctx.query.tab) || 'items';
  if (!TABS.some((t) => t.key === active)) active = 'items';

  function select(key) {
    Array.prototype.forEach.call(tabBar.children, (b) => {
      const on = b.dataset.key === key;
      b.classList.toggle('is-active', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    ui.clear(panel);
    TABS.filter((t) => t.key === key)[0].build(panel);
  }
  TABS.forEach((t) => {
    const b = ui.el('button', { type: 'button', role: 'tab', text: t.label, onclick: () => HG.router.navigate('/config?tab=' + t.key) });
    b.dataset.key = t.key;
    tabBar.appendChild(b);
  });

  root.appendChild(tabBar);
  root.appendChild(panel);
  select(active);
}

HG.pages = HG.pages || {};
HG.pages.config = { renderConfig };
HG.router.register('/config', renderConfig);
export default HG.pages.config;
