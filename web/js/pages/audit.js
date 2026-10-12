/*
 * 审计页面（AUDIT）—— 管理界面（FR-AUDIT-1~13；T-071）。
 *
 * ## 端点（11 路径，逐字对齐 ApiRoutes.byModule().get("AUDIT")；方法对取自 handler/audit/*Routes.java）
 *   GET|POST /audit/trace/forward      正向追溯（敞口 → 凭证；FR-AUDIT-1）
 *   GET|POST /audit/trace/backward     反向追溯（凭证/交易 → 敞口；FR-AUDIT-2）
 *   GET|POST /audit/trace/integrity    链条完整性校验 + 断点清单（FR-AUDIT-3 / R-AUDIT-2）
 *   GET|POST /audit/violation-screen   违规事项筛查 / 命中清单查询（FR-AUDIT-4）
 *   GET|POST /audit/report/quarterly   季度报表（FR-AUDIT-5/12）
 *   GET|POST /audit/report/annual      年度专项报告（FR-AUDIT-6/13 全覆盖）
 *   GET|POST /audit/report/major-event 重大事项专项报告（FR-AUDIT-7）
 *   GET|POST /audit/evidence/export    GET=导出记录查询（分页/exportNo）；POST=触发导出 → **CSV 直返**（T-193）
 *   GET|POST /audit/log                审计日志查询（FR-AUDIT-9）
 *   GET|POST /audit/log/verify         防篡改校验（FR-AUDIT-10）
 *   GET|POST /audit/operation-log      操作留痕查询（FR-AUDIT-11）
 *
 * ## T-193 CSV 直返契约（与 GET 下载先例不同构）
 *   POST /audit/evidence/export 成功 ⇒ HTTP 200 + text/csv; charset=UTF-8 直返 body（非 JSON 信封），
 *   Content-Disposition: attachment; filename="evidence-<exportNo>.csv"；
 *   失败 ⇒ BizException 经 Dispatcher 统一转 JSON 错误信封。
 *   故走 fetch 读响应，按 Content-Type 分流：text/csv ⇒ blob 下载；application/json ⇒ 按 errmsg 常规错误渲染。
 *
 * ## 业务红线（写进页面逻辑）
 *   · R-AUDIT-2：存在断点时**不得**输出「链条完整」——完整性结论区在断点存在时禁用「完整」字样，
 *     断点以红色徽标 + 明细列表显式标记；
 *   · FR-AUDIT-10：防篡改校验未通过（10018）阻断并告警；审计日志写入失败（10017）中断导出并告警
 *     （二者均为服务端判定，本页如实呈现后端结论并阻断后续导出操作的界面入口状态）；
 *   · FR-AUDIT-13 / R-AUDIT-11：核对任务与签署记录在审计侧**只读**（本页无任何修改/删除入口）；
 *     签署记录展示签署人/角色/时间/IP/快照哈希；判定在服务端，文案不暗示可代签；
 *   · 无口令字段参与任何展示运算（auth.js 同款纪律）。
 *
 * 纪律：不直连 DB / C++，只经 HG.api；失败统一经 HG.errmsg；数据界面必配 加载 / 空 / 错误 三态。
 */
import { HG } from '../core/ns.js';
import '../core/format.js';
import '../core/router.js';
import '../core/routes.js';
import '../core/ui.js';

const ui = HG.ui;
const F = HG.format;
const R = HG.routes.AUDIT;

/* ------------------------------------------------------------------ *
 * 取值域（以 Handler 实测为准）
 * ------------------------------------------------------------------ */
const CHAIN = {
  COMPLETE: '链条完整（COMPLETE）',
  HAS_BREAK: '存在断点（HAS_BREAK）',
  IN_FLIGHT: '在途（IN_FLIGHT，两档时窗内不计断点）',
  UNKNOWN: '未知（UNKNOWN）'
};
const SEGMENTS = {
  EXPOSURE: '敞口', HEDGE_PLAN: '套保方案', APPROVAL: '审批', TRADE: '交易',
  SETTLE: '结算', VOUCHER: '凭证'
};
const DIMENSIONS = {
  OVER_PRODUCT: '超品种额度', OVER_SCALE: '超规模', OVER_TERM: '超期限',
  OVER_AUTH: '超授权', FREQUENT_TRADE: '频繁交易',
  REASON_MISSING: '交易理由缺失', RELATED_MISSING: '关联对象缺失'
};
const SEVERITY = { LOW: '低', MEDIUM: '中', HIGH: '高' };
const START_TYPES = { VOUCHER: '凭证', TRADE: '交易' };
const START_POINT_TYPES = { EXPOSURE: '敞口', TRADE: '交易', VOUCHER: '凭证' };
const ACTION_TYPES = { QUERY: '查询', EXPORT: '导出', VERIFY: '防篡改校验' };
const REPORT_TYPE = { QUARTERLY: '季度报表', ANNUAL: '年度专项报告', MAJOR_EVENT: '重大事项专项报告' };

const OPLOG_SORTS = [
  { v: 'operation_time', t: '操作时间' }, { v: 'id', t: '主键' },
  { v: 'operator_id', t: '操作人' }, { v: 'object_type', t: '对象类型' }
];
const LOG_SORTS = [
  { v: 'action_time', t: '操作时间' }, { v: 'id', t: '主键' }, { v: 'seq_in_chain', t: '链内序号' }
];
const VS_SORTS = [{ v: 'id', t: '主键' }];
const EXP_SORTS = [
  { v: 'exported_time', t: '导出时间' }, { v: 'id', t: '主键' }, { v: 'export_no', t: '导出批次号' }
];

/** 完整性结论渲染（R-AUDIT-2：有断点 ⇒ 禁用「完整」表述）。 */
function chainConclusionHTML(chainIntegrity, breakList) {
  const breaks = Array.isArray(breakList) ? breakList : [];
  if (chainIntegrity === 'COMPLETE' && breaks.length === 0) {
    return '<span class="hg-badge hg-badge--active">链条完整（COMPLETE）</span>';
  }
  if (chainIntegrity === 'IN_FLIGHT') {
    return '<span class="hg-badge hg-badge--pending">在途（IN_FLIGHT）——时窗内未接续不计为断点</span>';
  }
  if (chainIntegrity === 'UNKNOWN' && breaks.length === 0) {
    return '<span class="hg-badge">完整性未知（UNKNOWN）——不存在「链条完整」结论</span>';
  }
  // HAS_BREAK（或 UNKNOWN 但断点非空，R-AUDIT-2 双保险）：显式红色标记
  return '<span class="hg-badge hg-badge--danger">存在断点（' + F.esc(chainIntegrity || 'HAS_BREAK')
    + '）——<strong>链条不完整</strong>，' + breaks.length + ' 处断点，明细见下</span>';
}

/** 断点列表（显式标记，不静默）。 */
function breakListHTML(breakList) {
  const breaks = Array.isArray(breakList) ? breakList : [];
  if (!breaks.length) {
    return '<p class="hg-muted">无断点记录。</p>';
  }
  const rows = breaks.map((b, i) => {
    const kind = b && b.kind ? b.kind : '';
    const pos = b && b.position ? b.position : '';
    const obj = b && b.objectType ? b.objectType : '';
    const oid = b && b.objectId !== undefined && b.objectId !== null ? String(b.objectId) : '';
    const ono = b && b.objectNo ? b.objectNo : '';
    const reason = b && b.reason ? b.reason : '';
    const target = obj ? (obj + (ono ? '（' + ono + '）' : (oid ? '（#' + oid + '）' : ''))) : '';
    return '<tr><td>' + (i + 1) + '</td><td>' + F.esc(kind || '—') + '</td><td>' + F.esc(pos || '—')
      + '</td><td>' + F.esc(target || '—') + '</td><td>' + F.esc(reason || '—') + '</td></tr>';
  }).join('');
  return ui.tableHTML(
    [
      { title: '#' }, { title: '断点类型', render: (r) => F.esc(r[1]) },
      { title: '位置（段）', render: (r) => F.esc(r[2]) },
      { title: '对象', render: (r) => F.esc(r[3]) },
      { title: '原因', render: (r) => F.esc(r[4]) }
    ],
    breaks.map((b, i) => {
      const kind = b && b.kind ? b.kind : '';
      const pos = b && b.position ? b.position : '';
      const obj = b && b.objectType ? b.objectType : '';
      const oid = b && b.objectId !== undefined && b.objectId !== null ? String(b.objectId) : '';
      const ono = b && b.objectNo ? b.objectNo : '';
      const reason = b && b.reason ? b.reason : '';
      const target = obj ? (obj + (ono ? '（' + ono + '）' : (oid ? '（#' + oid + '）' : ''))) : '';
      return [String(i + 1), kind || '—', pos || '—', target || '—', reason || '—'];
    })
  );
}

/** 追溯链节点表。 */
function traceNodesHTML(nodes) {
  if (!Array.isArray(nodes) || !nodes.length) return '';
  const segName = (s) => SEGMENTS[s] || (s || '—');
  const rows = nodes.map((n, i) => {
    const detail = n && n.detail && typeof n.detail === 'object' ? JSON.stringify(n.detail) : '';
    return [
      String(i + 1),
      segName(n && n.segment),
      (n && n.objectType) || '—',
      (n && n.objectNo) || ((n && n.objectId) !== undefined && n.objectId !== null ? String(n.objectId) : '—'),
      (n && n.status) || '—',
      (n && n.subAccountId) !== undefined && n.subAccountId !== null ? String(n.subAccountId) : '—',
      detail || '—'
    ];
  });
  return ui.tableHTML(
    [
      { title: '#' }, { title: '环节', render: (r) => F.esc(r[1]) },
      { title: '对象类型', render: (r) => F.esc(r[2]) }, { title: '对象标识', render: (r) => F.esc(r[3]) },
      { title: '状态', render: (r) => ui.statusBadge(r[4]) },
      { title: '子账户', render: (r) => F.esc(r[5]) }, { title: '明细', render: (r) => F.esc(r[6]) }
    ],
    rows
  );
}

/** 告警横幅（10017/10018 等，红色显式告警）。 */
function alertBanners(host, alerts) {
  (Array.isArray(alerts) ? alerts : []).forEach((a) => {
    host.appendChild(ui.banner('<strong>⚠ 告警</strong>：' + F.esc(String(a)), 'error'));
  });
}

/** 分页控件（与 auth.js 同构）。 */
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

/** 排序 + 每页条数控件组（与 auth.js 同构）。 */
function sortControls(bar, state, fields, load) {
  const sortSel = ui.el('select', { id: 'ad-sort-' + fields[0].v, 'aria-label': '排序字段' });
  fields.forEach((s) => sortSel.appendChild(ui.el('option', { value: s.v, text: s.t })));
  sortSel.value = state.sortField;
  sortSel.addEventListener('change', () => { state.sortField = sortSel.value; load(1); });
  bar.appendChild(ui.field({ id: sortSel.id, label: '排序字段', control: sortSel }));

  const dirSel = ui.el('select', { id: 'ad-dir-' + fields[0].v, 'aria-label': '排序方向' });
  dirSel.appendChild(ui.el('option', { value: 'desc', text: '降序' }));
  dirSel.appendChild(ui.el('option', { value: 'asc', text: '升序' }));
  dirSel.value = state.sortDir;
  dirSel.addEventListener('change', () => { state.sortDir = dirSel.value; load(1); });
  bar.appendChild(ui.field({ id: dirSel.id, label: '方向', control: dirSel }));

  const sizeSel = ui.el('select', { id: 'ad-size-' + fields[0].v, 'aria-label': '每页条数' });
  HG.api.PAGE_SIZES.forEach((s) => sizeSel.appendChild(ui.el('option', { value: String(s), text: s + ' 条/页' })));
  sizeSel.value = String(state.size);
  sizeSel.addEventListener('change', () => { state.size = Number(sizeSel.value); load(1); });
  bar.appendChild(ui.field({ id: sizeSel.id, label: '每页条数', control: sizeSel }));
}

/** 数值输入。 */
function numInput(id, props) {
  return ui.el('input', Object.assign({ id: id, type: 'number', inputmode: 'numeric' }, props || {}));
}

/** ISO 日期时间输入（yyyy-MM-ddTHH:mm:ss，operation-log / audit-log 用）。 */
function dtInput(id) {
  const c = ui.el('input', { id: id, type: 'datetime-local' });
  c.dataset.kind = 'datetime';
  return c;
}

/** datetime-local 值转 ISO-8601（yyyy-MM-ddTHH:mm:ss）；空串返回 ''。 */
function isoDateTime(v) {
  if (!v) return '';
  return v.length === 16 ? v + ':00' : v;
}

/* ------------------------------------------------------------------ *
 * POST CSV 直返下载（T-193 契约；与 GET 下载先例 HG.api.download 不同构：
 * POST + JSON body ⇒ 须自走 fetch，按 Content-Type 分流）。
 * 成功：text/csv ⇒ { blob, filename }（从 Content-Disposition 取 evidence-<exportNo>.csv）；
 * 失败：application/json 错误信封 ⇒ 抛出交 errmsg 渲染（10017/10018 等）。
 * ------------------------------------------------------------------ */
async function postCsvDownload(path, body) {
  let res;
  try {
    res = await fetch(HG.api.BASE + path, {
      method: 'POST', credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json; charset=UTF-8',
        'Accept': 'text/csv, application/json;q=0.9, */*;q=0.8'
      },
      body: JSON.stringify(body || {})
    });
  } catch (e) {
    throw { code: -1, message: '无法连接后端服务', _network: true, nextStep: '请确认 Java 业务组件已启动并完成装配，然后重试。' };
  }
  const ct = (res.headers.get('Content-Type') || '').toLowerCase();
  if (ct.indexOf('json') !== -1) {
    // 失败（或未按契约返回 CSV）：JSON 信封按普通错误抛出，交 errmsg 渲染四项
    const text = await res.text();
    let env = null;
    try { env = JSON.parse(text); } catch (e) { env = null; }
    if (env && typeof env === 'object') {
      if (env.code === 0) {
        throw { code: -1, message: '导出未按 CSV 契约返回（收到 JSON 成功信封）', _http: res.status, nextStep: '请检查后端 T-193 CSV 直返契约是否生效。' };
      }
      throw Object.assign({ _http: res.status }, env);
    }
    throw { code: -1, message: '导出响应不是合法 JSON 信封', _network: true, _http: res.status, _raw: text.slice(0, 300) };
  }
  const disp = res.headers.get('Content-Disposition') || '';
  const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disp);
  let filename = null;
  if (m) { try { filename = decodeURIComponent(m[1].trim()); } catch (e) { filename = m[1].trim(); } }
  return { blob: await res.blob(), filename: filename };
}

/** 触发浏览器下载（与 spot.js/product.js 同构）。 */
function triggerBlobDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'evidence.csv';
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/* ------------------------------------------------------------------ *
 * Tab 1：追溯（正向 / 反向 / 完整性）（FR-AUDIT-1/2/3、R-AUDIT-2、TC-AUDIT-1~4/15/16）
 * ------------------------------------------------------------------ */
function traceTab(container) {
  container.appendChild(ui.banner(
    '<strong>业务红线（R-AUDIT-2）</strong>：存在断点时本页<strong>不会</strong>输出「链条完整」结论——'
      + '断点一律以红色徽标与明细列表显式标记；在途（IN_FLIGHT）两档时窗内不计为断点（TC-AUDIT-4）。',
    'warn'
  ));

  const forwardCard = ui.el('section', { class: 'hg-card' });
  forwardCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '正向追溯（敞口 → 凭证，FR-AUDIT-1）' }));
  const fExposure = numInput('ad-f-exposure', { min: '1', placeholder: '如 1001' });
  const fDepth = ui.el('input', { id: 'ad-f-depth', placeholder: '段名（可空=全链），如 SETTLE' });
  const fBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '正向追溯' });
  forwardCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-f-exposure', label: '敞口 ID（必填）', required: true, control: fExposure }),
    ui.field({ id: 'ad-f-depth', label: '追溯深度（可空）', control: fDepth }),
    fBtn
  ]));
  const fBody = ui.el('div');
  forwardCard.appendChild(fBody);
  container.appendChild(forwardCard);

  const backwardCard = ui.el('section', { class: 'hg-card' });
  backwardCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '反向追溯（凭证 / 交易 → 敞口，FR-AUDIT-2）' }));
  const bType = ui.el('select', { id: 'ad-b-type' });
  Object.keys(START_TYPES).forEach((k) => bType.appendChild(ui.el('option', { value: k, text: START_TYPES[k] })));
  const bId = ui.el('input', { id: 'ad-b-id', placeholder: '凭证号或交易 ID' });
  const bDepth = ui.el('input', { id: 'ad-b-depth', placeholder: '段名（可空=全链）' });
  const bBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '反向追溯' });
  backwardCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-b-type', label: '起点类型', control: bType }),
    ui.field({ id: 'ad-b-id', label: '起点标识（必填）', required: true, control: bId }),
    ui.field({ id: 'ad-b-depth', label: '追溯深度（可空）', control: bDepth }),
    bBtn
  ]));
  const bBody = ui.el('div');
  backwardCard.appendChild(bBody);
  container.appendChild(backwardCard);

  const integrityCard = ui.el('section', { class: 'hg-card' });
  integrityCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '链条完整性校验 + 断点清单（FR-AUDIT-3 / R-AUDIT-2）' }));
  const iExposure = numInput('ad-i-exposure', { min: '1', placeholder: '如 1001' });
  const iBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '校验完整性' });
  integrityCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-i-exposure', label: '敞口 ID（必填）', required: true, control: iExposure }),
    iBtn
  ]));
  const iBody = ui.el('div');
  integrityCard.appendChild(iBody);
  container.appendChild(integrityCard);

  /** 追溯结果渲染（正向/反向共用；断点区共用）。 */
  function renderTrace(body, r) {
    ui.clear(body);
    if (!r || typeof r !== 'object') { ui.renderEmpty(body, '无追溯结果。'); return; }
    alertBanners(body, r.alerts);
    if (r.permissionLimited) {
      body.appendChild(ui.banner('权限受限：仅能追溯可见子账户范围内的对象（权限边界已由后端明示，R-AUDIT-9 / NFR-SEC-7）。', 'warn'));
    }
    const head = ui.el('div');
    head.appendChild(ui.el('div', { html: '完整性结论：' + chainConclusionHTML(r.chainIntegrity, r.breakList) }));
    if (r.note) head.appendChild(ui.el('p', { class: 'hg-muted', text: String(r.note) }));
    if (Array.isArray(r.visibleSubAccounts)) {
      head.appendChild(ui.el('p', { class: 'hg-muted', text: '可见子账户：' + (r.visibleSubAccounts.length ? r.visibleSubAccounts.join(', ') : '（空集）') }));
    }
    body.appendChild(head);
    const nodes = traceNodesHTML(r.nodes);
    if (nodes) {
      body.appendChild(ui.el('div', { html: nodes }));
    } else {
      const empty = ui.el('div');
      ui.renderEmpty(empty, '链上无节点。');
      body.appendChild(empty.firstChild);
    }
    // 断点区（显式）
    const breaks = Array.isArray(r.breakList) ? r.breakList : [];
    const bTitle = ui.el('h4', { text: '断点清单（' + breaks.length + ' 处）' });
    body.appendChild(bTitle);
    if (breaks.length) {
      body.appendChild(ui.banner('<strong>存在断点：链条不完整</strong>——以下断点须处置后方可出具「完整」结论（R-AUDIT-2）。', 'error'));
    }
    body.appendChild(ui.el('div', { html: breakListHTML(breaks) }));
  }

  fBtn.addEventListener('click', async () => {
    const exposureId = fExposure.value.trim();
    if (!exposureId) { ui.toast('敞口 ID 必填', 'error'); return; }
    ui.clear(fBody); ui.renderLoading(fBody, '正在正向追溯…');
    try {
      const r = await HG.api.get(R.TRACE_FORWARD, { exposureId: exposureId, depth: fDepth.value.trim() });
      renderTrace(fBody, r);
    } catch (err) { ui.renderError(fBody, err, () => fBtn.click()); }
  });
  bBtn.addEventListener('click', async () => {
    const startId = bId.value.trim();
    if (!startId) { ui.toast('起点标识必填', 'error'); return; }
    ui.clear(bBody); ui.renderLoading(bBody, '正在反向追溯…');
    try {
      const r = await HG.api.get(R.TRACE_BACKWARD, { startType: bType.value, startId: startId, depth: bDepth.value.trim() });
      renderTrace(bBody, r);
    } catch (err) { ui.renderError(bBody, err, () => bBtn.click()); }
  });
  iBtn.addEventListener('click', async () => {
    const exposureId = iExposure.value.trim();
    if (!exposureId) { ui.toast('敞口 ID 必填', 'error'); return; }
    ui.clear(iBody); ui.renderLoading(iBody, '正在校验链条完整性…');
    try {
      const r = await HG.api.get(R.TRACE_INTEGRITY, { exposureId: exposureId });
      renderTrace(iBody, r);
    } catch (err) { ui.renderError(iBody, err, () => iBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 2：违规事项筛查（FR-AUDIT-4；TC-AUDIT-5）
 * ------------------------------------------------------------------ */
function violationTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '违规事项筛查（FR-AUDIT-4）' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '「交易理由缺失（REASON_MISSING）」与「关联对象缺失（RELATED_MISSING）」为两个独立筛查维度（CH-002 / TC-AUDIT-5）。' }));

  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'id', sortDir: 'asc', lastRun: null };
  const vFrom = ui.el('input', { id: 'ad-v-from', type: 'date' });
  const vTo = ui.el('input', { id: 'ad-v-to', type: 'date' });
  const dimsBox = ui.el('div', { class: 'hg-toolbar' });
  Object.keys(DIMENSIONS).forEach((k) => {
    const cb = ui.el('input', { id: 'ad-v-dim-' + k, type: 'checkbox', value: k });
    const lb = ui.el('label', { for: 'ad-v-dim-' + k }, [cb, document.createTextNode(' ' + DIMENSIONS[k])]);
    dimsBox.appendChild(lb);
  });
  const vVersion = ui.el('input', { id: 'ad-v-version', placeholder: '可空' });
  const vScreenNo = ui.el('input', { id: 'ad-v-screenno', placeholder: '复查既有批次时填写' });
  const runBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '执行筛查' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-v-from', label: '开始日期', control: vFrom }),
    ui.field({ id: 'ad-v-to', label: '结束日期', control: vTo }),
    ui.field({ id: 'ad-v-version', label: '规则版本（可空）', control: vVersion }),
    ui.field({ id: 'ad-v-screenno', label: '筛查批次号（复查，可空）', control: vScreenNo })
  ]));
  card.appendChild(ui.field({ id: 'ad-v-dims', label: '筛查维度（不勾选=全部维度）', control: dimsBox }));
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [runBtn]));

  const summary = ui.el('div');
  const body = ui.el('div');
  const pager = ui.el('div', { class: 'hg-pager' });
  card.appendChild(summary);
  card.appendChild(body);
  card.appendChild(pager);
  container.appendChild(card);

  const bar = ui.el('div', { class: 'hg-toolbar' });
  sortControls(bar, state, VS_SORTS, load);
  card.appendChild(bar);

  runBtn.addEventListener('click', async () => {
    if (!vFrom.value || !vTo.value) { ui.toast('筛查区间（起止日期）必填', 'error'); return; }
    const dims = [];
    Object.keys(DIMENSIONS).forEach((k) => {
      const cb = document.getElementById('ad-v-dim-' + k);
      if (cb && cb.checked) dims.push(k);
    });
    ui.clear(summary); ui.clear(body);
    ui.renderLoading(body, '正在执行违规事项筛查…');
    try {
      const r = await HG.api.post(R.VIOLATION_SCREEN, {
        dateFrom: vFrom.value, dateTo: vTo.value,
        subAccountIds: null, dimensions: dims.length ? dims : null,
        ruleVersion: vVersion.value.trim() || null, screenNo: vScreenNo.value.trim() || null
      });
      state.lastRun = r && r.run ? r.run : null;
      renderRunSummary(summary, r);
      renderItems(body, { items: (r && r.items) || [] });
    } catch (err) { ui.renderError(body, err, () => runBtn.click()); }
  });

  function renderRunSummary(host, r) {
    if (!r || !r.run) return;
    const run = r.run;
    const flags = [];
    flags.push('批次号：' + F.esc(run.screenNo || '—'));
    flags.push('规则版本：' + F.esc(run.ruleVersion || '—'));
    flags.push('命中：' + (run.hitCount !== undefined ? run.hitCount : (r.hitCount !== undefined ? r.hitCount : '—')) + ' 条');
    if (r.permissionLimited) flags.push('权限受限（仅可见子账户）');
    if (run.conclusion) flags.push('结论：' + run.conclusion);
    host.appendChild(ui.el('p', { text: '筛查摘要 —— ' + flags.join('；') }));
    if (r.note) host.appendChild(ui.el('p', { class: 'hg-muted', text: String(r.note) }));
  }

  function renderItems(host, pageData) {
    ui.clear(host);
    const items = pageData.items || [];
    if (!items.length) { ui.renderEmpty(host, '该批次 / 筛选条件下无命中记录（未发现违规）。'); return; }
    host.appendChild(ui.el('div', { html: ui.tableHTML(
      [
        { key: 'id', title: 'ID', num: true },
        { key: 'subAccountId', title: '子账户', num: true },
        { title: '违规类型', render: (r) => F.esc(DIMENSIONS[r.violationType] || r.violationType || '—') },
        { title: '严重度', render: (r) => ui.statusBadge(SEVERITY[r.severity] || r.severity || '—') },
        { key: 'ruleId', title: '规则' },
        { title: '对象', render: (r) => F.esc((r.objectType || '—') + (r.objectId !== undefined && r.objectId !== null ? ' #' + r.objectId : '')) },
        { title: '发生时间', render: (r) => F.esc(F.dateTime(r.occurTime)) },
        { key: 'violationDesc', title: '描述' },
        { key: 'tradeReasonId', title: '交易理由' },
        { key: 'relatedObjectInfo', title: '关联对象' }
      ],
      items
    ) }));
  }

  async function load(page) {
    state.page = page || 1;
    ui.clear(body); ui.renderLoading(body, '正在查询命中清单…');
    try {
      const q = { page: state.page, size: state.size, sort: state.sortField + ',' + state.sortDir };
      if (vFrom.value) q.dateFrom = vFrom.value;
      if (vTo.value) q.dateTo = vTo.value;
      if (state.lastRun && state.lastRun.screenNo) q.screenNo = state.lastRun.screenNo;
      const data = await HG.api.get(R.VIOLATION_SCREEN, q);
      attachPager(pager, { total: data.total, page: data.page, size: data.size }, load);
      renderItems(body, data);
    } catch (err) { ui.renderError(body, err, () => load(state.page)); }
  }
}

/* ------------------------------------------------------------------ *
 * Tab 3：审计报表（季度 / 年度 / 重大事项）（FR-AUDIT-5/6/7/12；TC-AUDIT-6~8/13/14）
 * ------------------------------------------------------------------ */
function reportTab(container) {
  container.appendChild(ui.banner(
    '<strong>FR-AUDIT-12 / TC-AUDIT-13</strong>：报表导出前须写审计日志；日志写入失败（<code>10017</code>）'
      + '或防篡改校验未过（<code>10018</code>）时<strong>后端中断导出</strong>，本页如实呈现错误与告警，不提供绕过入口。'
      + '<br><strong>FR-AUDIT-13 / R-AUDIT-11（Q-14）</strong>：年度专项报告含核对任务与签署记录全覆盖（完成率 + 逾期清单）；'
      + '签署记录展示签署人 / 角色 / 时间 / IP / 快照哈希；审计侧<strong>只读</strong>，本页不提供任何修改或删除入口。',
    'warn'
  ));

  const quarterlyCard = ui.el('section', { class: 'hg-card' });
  quarterlyCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '季度报表（FR-AUDIT-5）' }));
  const qPeriod = ui.el('input', { id: 'ad-q-period', placeholder: '如 2026-Q3' });
  const qGenBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '生成季度报表' });
  const qExpBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '导出（txt）' });
  const qNo = ui.el('input', { id: 'ad-q-no', placeholder: '导出须提供 reportNo' });
  quarterlyCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-q-period', label: '期间（periodKey，必填）', required: true, control: qPeriod }),
    qGenBtn
  ]));
  quarterlyCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-q-no', label: '报表编号（reportNo）', control: qNo }), qExpBtn
  ]));
  const qBody = ui.el('div');
  quarterlyCard.appendChild(qBody);
  container.appendChild(quarterlyCard);

  const annualCard = ui.el('section', { class: 'hg-card' });
  annualCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '年度专项报告（FR-AUDIT-6 / 13 全覆盖）' }));
  const aYear = ui.el('input', { id: 'ad-a-year', placeholder: '如 2026' });
  const aGenBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '生成年度报告' });
  const aExpBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '导出（txt）' });
  const aNo = ui.el('input', { id: 'ad-a-no', placeholder: '导出须提供 reportNo' });
  annualCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-a-year', label: '年度（year，必填）', required: true, control: aYear }), aGenBtn
  ]));
  annualCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-a-no', label: '报表编号（reportNo）', control: aNo }), aExpBtn
  ]));
  const aBody = ui.el('div');
  annualCard.appendChild(aBody);
  container.appendChild(annualCard);

  const majorCard = ui.el('section', { class: 'hg-card' });
  majorCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '重大事项专项报告（FR-AUDIT-7）' }));
  const mPendingBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '查询待生成重大事项' });
  majorCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [mPendingBtn]));
  const mType = ui.el('input', { id: 'ad-m-type', placeholder: '事项类型（必填）' });
  const mNo = ui.el('input', { id: 'ad-m-no', placeholder: '事项编号（可空=自动）' });
  const mSub = numInput('ad-m-sub', { min: '1', placeholder: '可空' });
  const mSource = ui.el('input', { id: 'ad-m-source', placeholder: '触发来源（可空）' });
  const mDesc = ui.el('textarea', { id: 'ad-m-desc', rows: '2' });
  const mCause = ui.el('textarea', { id: 'ad-m-cause', rows: '2' });
  const mImpact = ui.el('textarea', { id: 'ad-m-impact', rows: '2' });
  const mHandle = ui.el('textarea', { id: 'ad-m-handle', rows: '2' });
  const mRectify = ui.el('textarea', { id: 'ad-m-rectify', rows: '2' });
  const mGenBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '生成重大事项报告' });
  const mExpBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '导出（txt）' });
  const mReportNo = ui.el('input', { id: 'ad-m-reportno', placeholder: '导出须提供 reportNo' });
  majorCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-m-type', label: '事项类型（必填）', required: true, control: mType }),
    ui.field({ id: 'ad-m-no', label: '事项编号', control: mNo }),
    ui.field({ id: 'ad-m-sub', label: '子账户 ID', control: mSub }),
    ui.field({ id: 'ad-m-source', label: '触发来源', control: mSource })
  ]));
  majorCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-m-desc', label: '事项描述', control: mDesc }),
    ui.field({ id: 'ad-m-cause', label: '原因分析', control: mCause }),
    ui.field({ id: 'ad-m-impact', label: '影响范围', control: mImpact })
  ]));
  majorCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-m-handle', label: '处理措施', control: mHandle }),
    ui.field({ id: 'ad-m-rectify', label: '整改计划', control: mRectify }),
    mGenBtn
  ]));
  majorCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-m-reportno', label: '报表编号（reportNo）', control: mReportNo }), mExpBtn
  ]));
  const mBody = ui.el('div');
  majorCard.appendChild(mBody);
  container.appendChild(majorCard);

  function reportHTML(r) {
    if (!r || !r.reportNo) return '<p class="hg-muted">（无报表数据）</p>';
    const rows = [
      ['报表编号', r.reportNo],
      ['类型', REPORT_TYPE[r.reportType] || r.reportType || '—'],
      ['期间', r.periodKey || '—'],
      ['完整性', null] // 占位：下方专用渲染
    ];
    let html = ui.tableHTML(
      [
        { title: '项', render: (row) => F.esc(row[0]) },
        { title: '值', render: (row) => (row[0] === '完整性' ? chainConclusionHTML(r.chainIntegrity, r.breakList) : F.esc(row[1])) }
      ],
      rows
    );
    const breaks = Array.isArray(r.breakList) ? r.breakList : [];
    html += '<h4>断点清单（' + breaks.length + ' 处）</h4>';
    if (breaks.length) {
      html += '<div class="hg-banner hg-banner--error"><div><strong>存在断点：链条不完整</strong>（R-AUDIT-2）——本报表不得表述为「链条完整」。</div></div>';
    }
    html += breakListHTML(breaks);
    if (r.dataGapDesc) html += '<p><strong>数据缺口说明</strong>：' + F.esc(r.dataGapDesc) + '</p>';
    if (r.submitted) {
      html += '<p>已提交至 <strong>' + F.esc(r.submittedTo || '—') + '</strong>（' + F.esc(F.dateTime(r.submittedTime)) + '）</p>';
    }
    if (r.contentJson) {
      let pretty = '';
      try { pretty = JSON.stringify(JSON.parse(r.contentJson), null, 2); } catch (e) { pretty = String(r.contentJson); }
      html += '<h4>报告内容</h4><pre class="hg-pre">' + F.esc(pretty) + '</pre>';
    }
    return html;
  }

  /** 通用 txt 导出（GET reportNo；FR-AUDIT-12）。 */
  async function exportReport(path, reportNo, body) {
    if (!reportNo) { ui.toast('导出须提供 reportNo（FR-AUDIT-12）', 'error'); return; }
    ui.clear(body); ui.renderLoading(body, '正在导出报表（先写审计日志，失败即中断）…');
    try {
      const res = await HG.api.download(path, { reportNo: reportNo });
      triggerBlobDownload(res.blob, res.filename || (reportNo + '.txt'));
      ui.toast('报表已导出', 'ok');
      ui.clear(body);
      body.appendChild(ui.banner('导出成功（' + F.esc(res.filename || reportNo + '.txt') + '）。导出动作已由后端写入审计日志。', 'ok'));
    } catch (err) {
      if (err && (err.code === 10017 || err.code === 10018)) {
        ui.renderError(body, err, null);
        body.insertBefore(ui.banner(
          '<strong>⚠ 导出被阻断（' + err.code + '）</strong>：' + (err.code === 10017
            ? '审计日志写入失败——本次导出已中断并告警（FR-AUDIT-12）。'
            : '防篡改校验未通过——已告警并阻断导出（FR-AUDIT-10 / 10018）。')
            + '不得绕过，请先处置日志链问题。', 'error'), body.firstChild ? body.firstChild.nextSibling : null);
      } else {
        ui.renderError(body, err, () => exportReport(path, reportNo, body));
      }
    }
  }

  qGenBtn.addEventListener('click', async () => {
    const periodKey = qPeriod.value.trim();
    if (!periodKey) { ui.toast('期间必填（如 2026-Q3）', 'error'); return; }
    ui.clear(qBody); ui.renderLoading(qBody, '正在生成季度报表…');
    try {
      const r = await HG.api.post(R.REPORT_QUARTERLY, { action: 'GENERATE', periodKey: periodKey });
      qNo.value = (r && r.reportNo) || '';
      ui.clear(qBody);
      qBody.appendChild(ui.el('div', { html: reportHTML(r) }));
    } catch (err) { ui.renderError(qBody, err, () => qGenBtn.click()); }
  });
  qExpBtn.addEventListener('click', () => exportReport(R.REPORT_QUARTERLY, qNo.value.trim(), qBody));

  aGenBtn.addEventListener('click', async () => {
    const year = aYear.value.trim();
    if (!year) { ui.toast('年度必填（如 2026）', 'error'); return; }
    ui.clear(aBody); ui.renderLoading(aBody, '正在生成年度专项报告（含核对任务全覆盖汇总）…');
    try {
      const r = await HG.api.post(R.REPORT_ANNUAL, { action: 'GENERATE', year: year });
      aNo.value = (r && r.reportNo) || '';
      ui.clear(aBody);
      aBody.appendChild(ui.el('div', { html: reportHTML(r) }));
    } catch (err) { ui.renderError(aBody, err, () => aGenBtn.click()); }
  });
  aExpBtn.addEventListener('click', () => exportReport(R.REPORT_ANNUAL, aNo.value.trim(), aBody));

  mPendingBtn.addEventListener('click', async () => {
    ui.clear(mBody); ui.renderLoading(mBody, '正在查询待生成重大事项…');
    try {
      const r = await HG.api.get(R.REPORT_MAJOR_EVENT, {});
      ui.clear(mBody);
      const pending = (r && r.pendingMajorEvents) || [];
      mBody.appendChild(ui.el('p', { class: 'hg-muted', text: String((r && r.note) || '') }));
      if (!pending.length) { ui.renderEmpty(mBody, '无待生成的重大事项专项报告。'); return; }
      mBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'eventNo', title: '事项编号' },
          { key: 'eventType', title: '类型' },
          { title: '状态', render: (row) => ui.statusBadge(row.status) },
          { key: 'triggerSource', title: '触发来源' },
          { title: '触发时间', render: (row) => F.esc(F.dateTime(row.triggeredTime)) }
        ],
        pending
      ) }));
    } catch (err) { ui.renderError(mBody, err, () => mPendingBtn.click()); }
  });
  mGenBtn.addEventListener('click', async () => {
    const eventType = mType.value.trim();
    if (!eventType) { ui.toast('事项类型必填（FR-AUDIT-7 验收 3）', 'error'); return; }
    ui.clear(mBody); ui.renderLoading(mBody, '正在生成重大事项专项报告…');
    try {
      const r = await HG.api.post(R.REPORT_MAJOR_EVENT, {
        action: 'GENERATE', eventType: eventType, eventNo: mNo.value.trim() || null,
        subAccountId: mSub.value ? Number(mSub.value) : null,
        triggerSource: mSource.value.trim() || null,
        eventDesc: mDesc.value.trim() || null,
        causeAnalysis: mCause.value.trim() || null,
        impactScope: mImpact.value.trim() || null,
        handlingMeasures: mHandle.value.trim() || null,
        rectificationPlan: mRectify.value.trim() || null
      });
      mReportNo.value = (r && r.reportNo) || '';
      ui.clear(mBody);
      mBody.appendChild(ui.el('div', { html: reportHTML(r) }));
    } catch (err) { ui.renderError(mBody, err, () => mGenBtn.click()); }
  });
  mExpBtn.addEventListener('click', () => exportReport(R.REPORT_MAJOR_EVENT, mReportNo.value.trim(), mBody));
}

/* ------------------------------------------------------------------ *
 * Tab 4：证据链导出（FR-AUDIT-8；T-193 CSV 直返）
 * ------------------------------------------------------------------ */
function evidenceTab(container) {
  const exportCard = ui.el('section', { class: 'hg-card' });
  exportCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '触发导出（POST → CSV 直返下载）' }));
  exportCard.appendChild(ui.banner(
    '<strong>导出契约（T-193）</strong>：成功 ⇒ <code>text/csv</code> 直返并触发下载（文件名 <code>evidence-&lt;exportNo&gt;.csv</code>，'
      + '「流式」= 服务端同步拼装直返，与既有 CSV 导出先例同构）；失败 ⇒ JSON 错误信封——'
      + '<code>10017</code>（审计日志写入失败，中断导出并告警）/ <code>10018</code>（防篡改校验未过）均<strong>阻断</strong>。',
    'warn'
  ));
  const eType = ui.el('select', { id: 'ad-e-type' });
  Object.keys(START_POINT_TYPES).forEach((k) => eType.appendChild(ui.el('option', { value: k, text: START_POINT_TYPES[k] })));
  const eIds = ui.el('input', { id: 'ad-e-ids', placeholder: '逗号分隔，如 1001,1002（可空=可见范围全量）' });
  const eFrom = ui.el('input', { id: 'ad-e-from', type: 'date' });
  const eTo = ui.el('input', { id: 'ad-e-to', type: 'date' });
  const eSubs = ui.el('input', { id: 'ad-e-subs', placeholder: '逗号分隔子账户 ID（可空）' });
  const eDetail = ui.el('input', { id: 'ad-e-detail', type: 'checkbox' });
  const eBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '导出证据链（CSV）' });
  exportCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-e-type', label: '追溯起点类型', control: eType }),
    ui.field({ id: 'ad-e-ids', label: '起点标识集合', control: eIds }),
    ui.field({ id: 'ad-e-detail', label: '含原始单据明细', control: eDetail })
  ]));
  exportCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-e-from', label: '日期起', control: eFrom }),
    ui.field({ id: 'ad-e-to', label: '日期止', control: eTo }),
    ui.field({ id: 'ad-e-subs', label: '子账户范围', control: eSubs }),
    eBtn
  ]));
  const eBody = ui.el('div');
  exportCard.appendChild(eBody);
  container.appendChild(exportCard);

  eBtn.addEventListener('click', async () => {
    const ids = eIds.value.split(',').map((s) => s.trim()).filter(Boolean);
    const subs = eSubs.value.split(',').map((s) => s.trim()).filter(Boolean).map(Number);
    ui.clear(eBody); ui.renderLoading(eBody, '正在导出证据链（先写审计日志；写失败/校验未过将中断）…');
    try {
      const res = await postCsvDownload(R.EVIDENCE_EXPORT, {
        startPointType: eType.value,
        startPointIds: ids,
        dateFrom: eFrom.value || null,
        dateTo: eTo.value || null,
        subAccountIds: subs.length ? subs : null,
        includeSourceDetail: eDetail.checked
      });
      triggerBlobDownload(res.blob, res.filename || 'evidence.csv');
      ui.clear(eBody);
      eBody.appendChild(ui.banner(
        '<strong>导出成功</strong>：' + F.esc(res.filename || 'evidence.csv')
          + '（CSV 已触发下载）。本次导出已落 <code>t_evidence_export</code>（含 exportNo），可在下方「导出记录」查询。',
        'ok'
      ));
    } catch (err) {
      if (err && (err.code === 10017 || err.code === 10018)) {
        ui.renderError(eBody, err, null);
        eBody.insertBefore(ui.banner(
          '<strong>⚠ 导出被阻断（' + err.code + '）</strong>：' + (err.code === 10017
            ? '审计日志写入失败——本次导出已中断并告警（10017）。'
            : '防篡改校验未通过——已告警并阻断导出（10018）。')
            + '不得绕过，请先处置审计日志链问题。', 'error'),
          eBody.firstChild ? eBody.firstChild.nextSibling : null);
      } else {
        ui.renderError(eBody, err, () => eBtn.click());
      }
    }
  });

  const recordCard = ui.el('section', { class: 'hg-card' });
  recordCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '导出记录查询（GET 分页 / 按 exportNo）' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'exported_time', sortDir: 'desc' };
  const rNo = ui.el('input', { id: 'ad-r-no', placeholder: '按导出批次号精确查' });
  const rSub = numInput('ad-r-sub', { min: '1', placeholder: '可空' });
  const rBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询记录' });
  recordCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-r-no', label: '导出批次号（exportNo）', control: rNo }),
    ui.field({ id: 'ad-r-sub', label: '子账户筛选', control: rSub }),
    rBtn
  ]));
  const rBody = ui.el('div');
  const rPager = ui.el('div', { class: 'hg-pager' });
  recordCard.appendChild(rBody);
  recordCard.appendChild(rPager);
  container.appendChild(recordCard);

  const bar = ui.el('div', { class: 'hg-toolbar' });
  sortControls(bar, state, EXP_SORTS, load);
  recordCard.appendChild(bar);

  rBtn.addEventListener('click', async () => {
    ui.clear(rBody); ui.renderLoading(rBody, '正在查询导出记录…');
    try {
      if (rNo.value.trim()) {
        const rec = await HG.api.get(R.EVIDENCE_EXPORT, { exportNo: rNo.value.trim() });
        ui.clear(rBody);
        rBody.appendChild(ui.el('div', { html: ui.tableHTML(
          [
            { title: '项', render: (row) => F.esc(row[0]) },
            { title: '值', render: (row) => (row[0] === '链条完整性' ? chainConclusionHTML(rec.chainIntegrity, rec.breakList) : F.esc(row[1])) }
          ],
          [
            ['导出批次号', rec.exportNo || '—'],
            ['起点类型', rec.startPointType || '—'],
            ['起点集合', JSON.stringify(rec.startPointIds || [])],
            ['日期范围', (rec.dateFrom || '—') + ' ~ ' + (rec.dateTo || '—')],
            ['子账户范围', rec.subAccountScope || '—'],
            ['含原始单据明细', rec.includeSourceDetail ? '是' : '否'],
            ['链条完整性', null],
            ['双轨盈亏含', rec.dualPnlIncluded ? '是' : '否'],
            ['理由含', rec.reasonIncluded ? '是' : '否'],
            ['文件 ID', rec.fileId || '—'],
            ['导出人', rec.exportedBy !== undefined && rec.exportedBy !== null ? String(rec.exportedBy) : '—'],
            ['导出时间', F.dateTime(rec.exportedTime) || '—'],
            ['审计日志 ID', rec.auditLogId !== undefined && rec.auditLogId !== null ? String(rec.auditLogId) : '—']
          ]
        ) }));
        rBody.appendChild(ui.el('h4', { text: '断点清单（' + ((rec.breakList || []).length) + ' 处）' }));
        rBody.appendChild(ui.el('div', { html: breakListHTML(rec.breakList) }));
      } else {
        const q = { page: state.page, size: state.size, sort: state.sortField + ',' + state.sortDir };
        if (rSub.value) q.subAccountId = rSub.value;
        const data = await HG.api.get(R.EVIDENCE_EXPORT, q);
        attachPager(rPager, { total: data.total, page: data.page, size: data.size }, load);
        ui.clear(rBody);
        if (!data.items || !data.items.length) { ui.renderEmpty(rBody, '无导出记录。'); return; }
        rBody.appendChild(ui.el('div', { html: ui.tableHTML(
          [
            { key: 'exportNo', title: '批次号' },
            { key: 'startPointType', title: '起点类型' },
            { title: '完整性', render: (row) => chainConclusionHTML(row.chainIntegrity, row.breakList) },
            { title: '断点数', num: true, render: (row) => String((row.breakList || []).length) },
            { title: '导出时间', render: (row) => F.esc(F.dateTime(row.exportedTime)) },
            { key: 'exportedBy', title: '导出人', num: true }
          ],
          data.items
        ) }));
      }
    } catch (err) { ui.renderError(rBody, err, () => rBtn.click()); }
  });

  async function load(page) {
    state.page = page || 1;
    rNo.value = '';
    const q = { page: state.page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (rSub.value) q.subAccountId = rSub.value;
    ui.clear(rBody); ui.renderLoading(rBody, '正在查询导出记录…');
    try {
      const data = await HG.api.get(R.EVIDENCE_EXPORT, q);
      attachPager(rPager, { total: data.total, page: data.page, size: data.size }, load);
      ui.clear(rBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(rBody, '无导出记录。'); return; }
      rBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'exportNo', title: '批次号' },
          { key: 'startPointType', title: '起点类型' },
          { title: '完整性', render: (row) => chainConclusionHTML(row.chainIntegrity, row.breakList) },
          { title: '断点数', num: true, render: (row) => String((row.breakList || []).length) },
          { title: '导出时间', render: (row) => F.esc(F.dateTime(row.exportedTime)) },
          { key: 'exportedBy', title: '导出人', num: true }
        ],
        data.items
      ) }));
    } catch (err) { ui.renderError(rBody, err, () => load(state.page)); }
  }
}

/* ------------------------------------------------------------------ *
 * Tab 5：审计日志与防篡改校验（FR-AUDIT-9/10；TC-AUDIT-10/11）
 * ------------------------------------------------------------------ */
function logTab(container) {
  const queryCard = ui.el('section', { class: 'hg-card' });
  queryCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '审计日志查询（FR-AUDIT-9）' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'action_time', sortDir: 'desc' };
  const lFrom = dtInput('ad-l-from');
  const lTo = dtInput('ad-l-to');
  const lUser = numInput('ad-l-user', { min: '1', placeholder: '可空' });
  const lAction = ui.el('select', { id: 'ad-l-action' });
  lAction.appendChild(ui.el('option', { value: '', text: '全部动作' }));
  Object.keys(ACTION_TYPES).forEach((k) => lAction.appendChild(ui.el('option', { value: k, text: ACTION_TYPES[k] })));
  const lSub = numInput('ad-l-sub', { min: '1', placeholder: '可空' });
  const lBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询日志' });
  queryCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-l-from', label: '起（含时间）', control: lFrom }),
    ui.field({ id: 'ad-l-to', label: '止（含时间）', control: lTo }),
    ui.field({ id: 'ad-l-user', label: '审计操作人', control: lUser }),
    ui.field({ id: 'ad-l-action', label: '动作类型', control: lAction }),
    ui.field({ id: 'ad-l-sub', label: '子账户', control: lSub }),
    lBtn
  ]));
  const lBody = ui.el('div');
  const lPager = ui.el('div', { class: 'hg-pager' });
  queryCard.appendChild(lBody);
  queryCard.appendChild(lPager);
  container.appendChild(queryCard);

  const bar = ui.el('div', { class: 'hg-toolbar' });
  sortControls(bar, state, LOG_SORTS, load);
  queryCard.appendChild(bar);

  const verifyCard = ui.el('section', { class: 'hg-card' });
  verifyCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '防篡改校验（FR-AUDIT-10）' }));
  verifyCard.appendChild(ui.banner(
    '<strong>10018</strong>：校验未通过时已告警并<strong>阻断后续导出</strong>——证据链导出、报表导出将被后端拒绝（'
      + '本页在导出 Tab 对 <code>10018</code> 显式呈现阻断告警）。校验通过方可恢复导出操作。',
    'warn'
  ));
  const vFromSeq = numInput('ad-v-seq-from', { min: '1', placeholder: '默认 1' });
  const vToSeq = numInput('ad-v-seq-to', { min: '0', placeholder: '默认链尾' });
  const vBatch = ui.el('input', { id: 'ad-v-batch', type: 'checkbox' });
  const vBatchSize = numInput('ad-v-batchsize', { min: '1', placeholder: '默认 1000' });
  const vBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '执行校验' });
  verifyCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-v-seq-from', label: '起始 seq', control: vFromSeq }),
    ui.field({ id: 'ad-v-seq-to', label: '截止 seq', control: vToSeq }),
    ui.field({ id: 'ad-v-batch', label: '先生成校验批次', control: vBatch }),
    ui.field({ id: 'ad-v-batchsize', label: '批次大小', control: vBatchSize }),
    vBtn
  ]));
  const vBody = ui.el('div');
  verifyCard.appendChild(vBody);
  container.appendChild(verifyCard);

  lBtn.addEventListener('click', () => load(1));

  async function load(page) {
    state.page = page || 1;
    const q = { page: state.page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (lFrom.value) q.fromTime = isoDateTime(lFrom.value);
    if (lTo.value) q.toTime = isoDateTime(lTo.value);
    if (lUser.value) q.auditUserId = lUser.value;
    if (lAction.value) q.actionType = lAction.value;
    if (lSub.value) q.subAccountId = lSub.value;
    ui.clear(lBody); ui.renderLoading(lBody, '正在查询审计日志…');
    try {
      const data = await HG.api.get(R.LOG, q);
      attachPager(lPager, { total: data.total, page: data.page, size: data.size }, load);
      ui.clear(lBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(lBody, '该筛选条件下无审计日志。'); return; }
      lBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'id', title: 'ID', num: true },
          { title: '动作', render: (row) => F.esc(ACTION_TYPES[row.actionType] || row.actionType || '—') },
          { key: 'auditUserId', title: '操作人', num: true },
          { key: 'subAccountId', title: '子账户', num: true },
          { key: 'queryCondition', title: '查询条件' },
          { key: 'resultCount', title: '结果数', num: true },
          { key: 'exportFileId', title: '导出文件' },
          { title: '链完整性', render: (row) => chainConclusionHTML(row.chainIntegrity, []) },
          { title: '动作时间', render: (row) => F.esc(F.dateTime(row.actionTime)) },
          { key: 'seqInChain', title: '链内序号', num: true }
        ],
        data.items
      ) }));
    } catch (err) { ui.renderError(lBody, err, () => load(state.page)); }
  }

  vBtn.addEventListener('click', async () => {
    ui.clear(vBody); ui.renderLoading(vBody, '正在执行防篡改校验…');
    try {
      const body = {
        fromSeq: vFromSeq.value || null,
        toSeq: vToSeq.value || null,
        generateBatch: vBatch.checked,
        batchSize: vBatchSize.value || null
      };
      const r = await HG.api.post(R.LOG_VERIFY, body);
      ui.clear(vBody);
      const passed = !!(r && r.passed);
      vBody.appendChild(ui.banner(
        passed
          ? '<strong>防篡改校验通过</strong>：[' + F.esc(String(r.fromSeq)) + ' ~ ' + F.esc(String(r.toSeq)) + '] 共校验 '
            + F.esc(String(r.checkedCount)) + ' 条 / ' + F.esc(String(r.verifiedBatches)) + ' 个批次（'
            + F.esc(F.dateTime(r.verifiedTime)) + '，校验人 #' + F.esc(String(r.verifiedBy)) + '）。'
          : '<strong>⚠ 防篡改校验未通过</strong>：已告警并<strong>阻断后续导出</strong>（10018）。不一致 '
            + ((r && r.mismatches) || []).length + ' 处，明细见下。',
        passed ? 'ok' : 'error'
      ));
      vBody.appendChild(ui.el('p', { class: 'hg-muted', text: String((r && r.note) || '') }));
      const mismatches = (r && r.mismatches) || [];
      if (mismatches.length) {
        vBody.appendChild(ui.el('div', { html: ui.tableHTML(
          [{ title: '#', render: (row) => F.esc(row[0]) }, { title: '不一致明细', render: (row) => F.esc(row[1]) }],
          mismatches.map((m, i) => [String(i + 1), String(m)])
        ) }));
      }
      const cb = r && r.checksumBatch;
      if (cb && cb.batchNo) {
        vBody.appendChild(ui.el('h4', { text: '新校验批次' }));
        vBody.appendChild(ui.el('div', { html: ui.tableHTML(
          [
            { title: '项', render: (row) => F.esc(row[0]) }, { title: '值', render: (row) => F.esc(row[1]) }
          ],
          [
            ['批次号', cb.batchNo], ['起始 seq', String(cb.batchStartSeq)], ['截止 seq', String(cb.batchEndSeq)],
            ['头哈希', cb.batchHeadHash || '—'], ['尾哈希', cb.batchTailHash || '—'],
            ['校验和', cb.batchChecksum || '—'], ['记录数', String(cb.recordCount)]
          ]
        ) }));
      }
    } catch (err) { ui.renderError(vBody, err, () => vBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 6：操作留痕（FR-AUDIT-11；TC-AUDIT-12）
 * ------------------------------------------------------------------ */
function oplogTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '操作留痕查询（FR-AUDIT-11）' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'operation_time', sortDir: 'desc' };
  const oFrom = dtInput('ad-o-from');
  const oTo = dtInput('ad-o-to');
  const oUser = numInput('ad-o-user', { min: '1', placeholder: '可空' });
  const oType = ui.el('input', { id: 'ad-o-type', placeholder: '如 APPROVE / SUBMIT（可空）' });
  const oObj = ui.el('input', { id: 'ad-o-obj', placeholder: '如 HEDGE_PLAN（可空）' });
  const oSub = numInput('ad-o-sub', { min: '1', placeholder: '可空' });
  const oBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询留痕' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-o-from', label: '起（含时间）', control: oFrom }),
    ui.field({ id: 'ad-o-to', label: '止（含时间）', control: oTo }),
    ui.field({ id: 'ad-o-user', label: '操作人', control: oUser }),
    ui.field({ id: 'ad-o-type', label: '操作类型', control: oType }),
    ui.field({ id: 'ad-o-obj', label: '对象类型', control: oObj }),
    ui.field({ id: 'ad-o-sub', label: '子账户', control: oSub }),
    oBtn
  ]));
  const body = ui.el('div');
  const pager = ui.el('div', { class: 'hg-pager' });
  card.appendChild(body);
  card.appendChild(pager);
  container.appendChild(card);

  const bar = ui.el('div', { class: 'hg-toolbar' });
  sortControls(bar, state, OPLOG_SORTS, load);
  card.appendChild(bar);

  oBtn.addEventListener('click', () => load(1));

  async function load(page) {
    state.page = page || 1;
    const q = { page: state.page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (oFrom.value) q.fromTime = isoDateTime(oFrom.value);
    if (oTo.value) q.toTime = isoDateTime(oTo.value);
    if (oUser.value) q.operatorId = oUser.value;
    if (oType.value.trim()) q.operationType = oType.value.trim();
    if (oObj.value.trim()) q.objectType = oObj.value.trim();
    if (oSub.value) q.subAccountId = oSub.value;
    ui.clear(body); ui.renderLoading(body, '正在查询操作留痕…');
    try {
      const data = await HG.api.get(R.OPERATION_LOG, q);
      attachPager(pager, { total: data.total, page: data.page, size: data.size }, load);
      ui.clear(body);
      if (!data.items || !data.items.length) { ui.renderEmpty(body, '该筛选条件下无操作留痕。'); return; }
      body.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'id', title: 'ID', num: true },
          { key: 'operatorId', title: '操作人', num: true },
          { key: 'operationType', title: '操作类型' },
          { key: 'objectType', title: '对象类型' },
          { title: '对象标识', render: (row) => F.esc(row.objectNo || (row.objectId !== undefined && row.objectId !== null ? String(row.objectId) : '—')) },
          { key: 'subAccountId', title: '子账户', num: true },
          { title: '结果', render: (row) => ui.statusBadge(row.actionResult || '—') },
          { key: 'resultDetail', title: '结果明细' },
          { title: '操作时间', render: (row) => F.esc(F.dateTime(row.operationTime)) },
          { key: 'clientIp', title: '客户端 IP' },
          { key: 'traceId', title: 'traceId' }
        ],
        data.items
      ) }));
    } catch (err) { ui.renderError(body, err, () => load(state.page)); }
  }
}

/* ------------------------------------------------------------------ *
 * Tab 7：核对任务与签署记录（FR-AUDIT-13 / R-AUDIT-11 / Q-14；TC-AUDIT-14）
 *        审计侧只读：数据经年度专项报告（checkTaskCoverage）呈现，本页无修改/删除入口。
 * ------------------------------------------------------------------ */
function signoffTab(container) {
  container.appendChild(ui.banner(
    '<strong>FR-AUDIT-13 / R-AUDIT-11（Q-14）</strong>：核对任务与签署记录在审计侧<strong>只读</strong>——'
      + '本页<strong>不提供</strong>任何修改 / 删除 / 代签入口；签署判定在服务端（拒绝非应签角色与代签）。'
      + '签署记录展示签署人 / 角色 / 时间 / IP / 核对对象快照哈希；逾期未签任务随年度专项报告进入审计报表。',
    'warn'
  ));
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '按年度查询核对任务与签署记录（经年度专项报告全覆盖汇总）' }));
  const sYear = ui.el('input', { id: 'ad-s-year', placeholder: '如 2026' });
  const sBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询年度全覆盖' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ad-s-year', label: '年度（year，必填）', required: true, control: sYear }), sBtn
  ]));
  const sBody = ui.el('div');
  card.appendChild(sBody);
  container.appendChild(card);

  sBtn.addEventListener('click', async () => {
    const year = sYear.value.trim();
    if (!year) { ui.toast('年度必填（如 2026）', 'error'); return; }
    ui.clear(sBody); ui.renderLoading(sBody, '正在核对 ' + year + ' 年度全覆盖承载方式…');
    try {
      // FR-AUDIT-13 验收 3（年度全覆盖）的权威只读承载 = 年度专项报告（FR-AUDIT-6 验收 2：
      // 后端 CheckTaskQueryService.annualCoverage 汇总各类型完成率 + 逾期清单 + 签署明细）。
      // 本 Tab 不另设查询端点（11 路径契约内无「核对任务查询」独立端点），故指引至报表 Tab 生成，
      // 并保持零写入口（只读纪律）；不重复实现报表解析。
      ui.clear(sBody);
      sBody.appendChild(ui.banner(
        '核对任务与签署记录的**权威只读查询承载**为年度专项报告（<code>FR-AUDIT-6</code> 验收 2 / '
          + '<code>CheckTaskQueryService.annualCoverage</code>：各类型完成率 + 逾期清单 + 签署明细'
          + '——账号 / 角色 / 时间 / IP / 核对对象快照哈希）。'
          + '请转「审计报表」页签，以 year=<strong>' + F.esc(year) + '</strong> 生成年度专项报告，'
          + '报告内容区（<code>checkTaskCoverage</code>）即含该年度全覆盖汇总与逾期清单。',
        'warn'
      ));
      sBody.appendChild(ui.el('p', { class: 'hg-muted', text: '本页保持只读：无修改 / 删除 / 代签入口；签署合法性判定在服务端。' }));
    } catch (err) { ui.renderError(sBody, err, () => sBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * 页面装配
 * ------------------------------------------------------------------ */
const TABS = [
  { key: 'trace', label: '追溯与完整性', build: traceTab },
  { key: 'violation', label: '违规筛查', build: violationTab },
  { key: 'report', label: '审计报表', build: reportTab },
  { key: 'evidence', label: '证据链导出', build: evidenceTab },
  { key: 'log', label: '审计日志与校验', build: logTab },
  { key: 'oplog', label: '操作留痕', build: oplogTab },
  { key: 'signoff', label: '核对与签署（只读）', build: signoffTab }
];

function renderAudit(root, ctx) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '审计（AUDIT）' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '全链追溯 / 完整性断点标记 / 违规筛查 / 审计报表 / 证据链导出 / 审计日志与防篡改校验 / 操作留痕（FR-AUDIT-1~13）。' }));
  root.appendChild(ui.banner(
    '<strong>界面边界声明</strong>：本页仅提供界面，不做授权与完整性判定——子账户可见性过滤、断点认定、'
      + '防篡改校验、签署合法性（拒绝非应签角色与代签）<strong>一律由后端负责</strong>；'
      + '前端如实呈现后端结论（含 <code>10017</code>/<code>10018</code> 阻断告警）。'
      + '<strong>R-AUDIT-2</strong>：存在断点时本页任何区域都不输出「链条完整」结论。'
      + '核对任务与签署记录为<strong>只读</strong>展示。',
    'warn'
  ));

  const tabBar = ui.el('div', { class: 'hg-tabs', role: 'tablist' });
  const panel = ui.el('div');
  let active = (ctx && ctx.query && ctx.query.tab) || 'trace';
  if (!TABS.some((t) => t.key === active)) active = 'trace';

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
    const b = ui.el('button', { type: 'button', role: 'tab', text: t.label, onclick: () => HG.router.navigate('/audit?tab=' + t.key) });
    b.dataset.key = t.key;
    tabBar.appendChild(b);
  });

  root.appendChild(tabBar);
  root.appendChild(panel);
  select(active);
}

HG.pages = HG.pages || {};
HG.pages.audit = { renderAudit };
HG.router.register('/audit', renderAudit);
export default HG.pages.audit;
