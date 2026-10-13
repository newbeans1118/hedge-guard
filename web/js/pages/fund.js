/*
 * 资金管理页面（FUND）—— 管理界面（FR-FUND-1~17；T-055）。
 *
 * ## 端点（12 路径，逐字对齐 ApiRoutes.byModule().get("FUND")；方法对取自 handler/fund/FundRoutes.java）
 *   GET|POST /fund/ledger                 台账分页查询 / 建立子账户资金台账（FR-FUND-1/3）
 *   GET      /fund/flow                   资金流水查询（FR-FUND-3/16）
 *   POST     /fund/deposit                发起入金（经 CTP；FR-FUND-5）
 *   POST     /fund/withdraw               发起出金（经 CTP；FR-FUND-6）
 *   POST     /fund/{id}/review            出入金双人复核（FR-FUND-7）
 *   GET|POST /fund/transfer               划拨记录查询 / 发起划拨（FR-FUND-10/16）
 *   POST     /fund/transfer/{id}/approve  划拨审批 + 记账 + 加总校验（FR-FUND-11/12）
 *   POST     /fund/receipt                处理 CTP 资金划转回执（FR-FUND-8）
 *   GET|POST /fund/quota                  额度查询 / 额度设定（FR-FUND-13）
 *   GET      /fund/margin                 保证金占用视图（只读；FR-FUND-15）
 *   POST     /fund/reconcile              每日资金对账（FR-FUND-17）
 *   GET      /fund/export                 资金对账导出（T-195 起 CSV 直返下载；FR-FUND-4）
 *   GET      /fund/apply-list             出入金申请列表分页（FR-FUND-8 验收3；T-195 新增）
 *
 * ## 业务红线（写进页面逻辑）
 *   · R-FUND-3 双人复核：出入金复核与划拨审批的发起人与复核/审批人不得为同一人——
 *     系统级强制在后端；本页在两处审批动作区显式提示双人复核（文案逐字取自 FUND.md FR-FUND-7 描述/
 *     处理规则原文：「发起人与复核人不得为同一人」），并要求驳回必填原因（前端预检 + 后端强制）；
 *   · R-FUND-4：划拨为系统内记账——划拨申请表单固定提示条逐字使用验收 3 原文
 *     「系统内记账，加总不变」；
 *   · R-FUND-5 CTP 异步模型：「超时待查（TIMEOUT_UNKNOWN）」= 网络超时**不代表未到账**（FUND.md §2
 *     回执「网络超时不代表未到账」）——出入金列表对 TIMEOUT_UNKNOWN 以独立警示徽标显式展示
 *     （**不渲染为失败色**，与 FAILED 徽标区分），行内提示「须主动查询确认，不得假设失败或成功」；
 *     本页不提供「重试即重发」入口（重试前置=查询确认，判定在服务端）；
 *   · R-FUND-9 零落盘：本页无任何资金密码字段（资金密码仅内存持有、由 CTP 侧启动录入，NFR-SEC-1）；
 *   · 流水/台账只读：流水「只增不改不删」（R-FUND-2）——本页无流水编辑/删除入口；
 *   · 保证金占用（margin）为只读视图；额度判定在服务端（页面只做展示与提交）。
 *
 * ## 幂等纪律
 *   写操作（deposit/withdraw/review/transfer/transfer approve/receipt/quota/ledger/reconcile）
 *   一律携带 Idempotency-Key（auth.js 先例；FR-FUND-5「同一幂等标识重复提交不得产生第二笔划转」）。
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
const R = HG.routes.FUND;

/* ------------------------------------------------------------------ *
 * 取值域（以 FundService 常量与 FUND.md 数据字典实测为准）
 * ------------------------------------------------------------------ */
/** 出入金状态（ck_dw_status）：超时待查**不得渲染为失败色**（红线）。 */
const DW_STATUS = {
  PENDING_REVIEW: '待复核', SUBMITTED: '已提交（等待 CTP 回执）', SUCCESS: '已成功',
  FAILED: '已失败', TIMEOUT_UNKNOWN: '超时待查', REJECTED: '已驳回'
};
/** 划拨状态（ck_transfer_apply_status）。 */
const TR_STATUS = {
  PENDING_APPROVAL: '待审批', PENDING_POSTING: '待记账', POSTED: '已记账',
  ROLLED_BACK: '已回滚', REJECTED: '已驳回'
};
/** 回执结果（ck_capital_receipt_result）。 */
const RECEIPT_RESULT = {
  SUCCESS: '成功', FAIL: '失败', PROCESSING: '处理中', UNKNOWN: '未知（超时待查）'
};
/** 流水类型（ck_fund_flow_type；后端 appendFlow 实际产生四类）。 */
const FLOW_TYPE = {
  DEPOSIT: '入金', WITHDRAW: '出金', TRANSFER_OUT: '子账户划拨（出）',
  TRANSFER_IN: '子账户划拨（入）', DIFFERENCE_ADJUST: '差异调整'
};
/** 方向。 */
const DIR = { IN: '入', OUT: '出' };
/** 额度维度。 */
const QUOTA_SCOPE = { SUB_ACCOUNT: '子账户', ACCOUNT: '账户（全账户）' };

/** 出入金状态徽标（TIMEOUT_UNKNOWN 独立警示形态——非失败色，红线）。 */
function dwStatusHTML(status) {
  const s = DW_STATUS[status] || status || '—';
  if (status === 'TIMEOUT_UNKNOWN') {
    return '<span class="hg-badge hg-badge--frozen" data-dw-status="TIMEOUT_UNKNOWN">超时待查</span>';
  }
  return ui.statusBadge(s);
}

/** 划拨状态徽标（已回滚显式警示；已记账=完成）。 */
function trStatusHTML(status) {
  const s = TR_STATUS[status] || status || '—';
  if (status === 'ROLLED_BACK') {
    return '<span class="hg-badge hg-badge--danger" data-tr-status="ROLLED_BACK">已回滚</span>';
  }
  return ui.statusBadge(s);
}

/** 双人复核红线提示（文案逐字取自 FUND.md：FR-FUND-7「发起人与复核人不得为同一人」）。 */
const DOUBLE_REVIEW_HTML = '<strong>双人复核（R-FUND-3）</strong>：发起人与复核人不得为同一人'
  + '（系统级强制，后端判定并记录越权尝试）；<strong>审批人不得为交易岗</strong>（岗位分离，FR-FUND-11）；'
  + '驳回须填写原因；复核记录永久留痕。';

/** 划拨红线固定提示条（验收 3 原文逐字：「系统内记账，加总不变」）。 */
const TRANSFER_BANNER_HTML = '<strong>系统内记账，加总不变</strong>——子账户间划拨为系统内记账调整，'
  + '真实期货账户资金不变动；划拨后各子账户资金加总须仍等于真实账户可用资金'
  + '（R-FUND-4；加总校验不通过将回滚并告警）。';

/** 「超时待查」行内警示文案（不得把超时渲染为失败；R-FUND-5）。 */
const TIMEOUT_NOTE_HTML = '<div class="hg-field__help">网络超时不代表未到账——须先主动查询确认'
  + '（POST /fund/receipt result=UNKNOWN + queryConfirmed），不得假设失败或成功；'
  + '确认未到账后方可按新申请重试（FR-FUND-9）。</div>';

const FLOW_SORTS = [
  { v: 'ledger_date', t: '记账日期' }, { v: 'id', t: '主键' }, { v: 'voucher_no', t: '凭证编号' }
];
const LEDGER_SORTS = [
  { v: 'ledger_date', t: '记账日期' }, { v: 'id', t: '主键' }, { v: 'sub_account_id', t: '子账户' }
];
const TRANSFER_SORTS = [{ v: 'id', t: '主键' }];

/* ------------------------------------------------------------------ *
 * 通用控件（与 audit.js 同构）
 * ------------------------------------------------------------------ */
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

function sortControls(bar, state, fields, load, idPrefix) {
  const sortSel = ui.el('select', { id: idPrefix + '-sort-' + fields[0].v, 'aria-label': '排序字段' });
  fields.forEach((s) => sortSel.appendChild(ui.el('option', { value: s.v, text: s.t })));
  sortSel.value = state.sortField;
  sortSel.addEventListener('change', () => { state.sortField = sortSel.value; load(1); });
  bar.appendChild(ui.field({ id: sortSel.id, label: '排序字段', control: sortSel }));

  const dirSel = ui.el('select', { id: idPrefix + '-dir', 'aria-label': '排序方向' });
  dirSel.appendChild(ui.el('option', { value: 'asc', text: '升序' }));
  dirSel.appendChild(ui.el('option', { value: 'desc', text: '降序' }));
  dirSel.value = state.sortDir;
  dirSel.addEventListener('change', () => { state.sortDir = dirSel.value; load(1); });
  bar.appendChild(ui.field({ id: dirSel.id, label: '方向', control: dirSel }));

  const sizeSel = ui.el('select', { id: idPrefix + '-size', 'aria-label': '每页条数' });
  HG.api.PAGE_SIZES.forEach((s) => sizeSel.appendChild(ui.el('option', { value: String(s), text: s + ' 条/页' })));
  sizeSel.value = String(state.size);
  sizeSel.addEventListener('change', () => { state.size = Number(sizeSel.value); load(1); });
  bar.appendChild(ui.field({ id: sizeSel.id, label: '每页条数', control: sizeSel }));
}

function numInput(id, props) {
  return ui.el('input', Object.assign({ id: id, type: 'number', inputmode: 'numeric' }, props || {}));
}

function dateInput(id) { return ui.el('input', { id: id, type: 'date' }); }

/** 金额输入（字符串化十进制透传，前端不做算术）。 */
function amtInput(id, props) {
  return ui.el('input', Object.assign({ id: id, type: 'text', inputmode: 'decimal', placeholder: '如 100000.00' }, props || {}));
}

/* ------------------------------------------------------------------ *
 * Tab 1：资金台账（FR-FUND-1/3；台账分页 + 建立台账）
 * ------------------------------------------------------------------ */
function ledgerTab(container) {
  const listCard = ui.el('section', { class: 'hg-card' });
  listCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '资金台账查询（FR-FUND-3）' }));
  listCard.appendChild(ui.banner(
    '台账按子账户独立（R-FUND-1）且可直接求和；展示口径与 SETTLE 共享同一数据源（R-FUND-8）。'
      + '期初余额不得手工改写——如与真实账户不一致须经差异调整并留痕。',
    'warn'
  ));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'ledger_date', sortDir: 'desc' };
  const lFrom = dateInput('fd-l-from');
  const lTo = dateInput('fd-l-to');
  const lSub = numInput('fd-l-sub', { min: '1', placeholder: '可空' });
  const lBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询台账' });
  listCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-l-from', label: '记账日期起', control: lFrom }),
    ui.field({ id: 'fd-l-to', label: '记账日期止', control: lTo }),
    ui.field({ id: 'fd-l-sub', label: '子账户', control: lSub }),
    lBtn
  ]));
  const lBody = ui.el('div');
  const lPager = ui.el('div', { class: 'hg-pager' });
  listCard.appendChild(lBody);
  listCard.appendChild(lPager);
  container.appendChild(listCard);

  const bar = ui.el('div', { class: 'hg-toolbar' });
  sortControls(bar, state, LEDGER_SORTS, load, 'fd-l');
  listCard.appendChild(bar);

  const initCard = ui.el('section', { class: 'hg-card' });
  initCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '建立子账户资金台账（FR-FUND-1，须经双人复核）' }));
  const iSub = numInput('fd-i-sub', { min: '1', placeholder: '子账户 ID' });
  const iDate = dateInput('fd-i-date');
  const iAmt = amtInput('fd-i-amt');
  const iReviewer = numInput('fd-i-reviewer', { min: '1', placeholder: '复核人用户 ID（不得为发起人本人）' });
  const iComment = ui.el('input', { id: 'fd-i-comment', placeholder: '复核意见（可空）' });
  const iBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '建立台账' });
  initCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-i-sub', label: '子账户（必填）', required: true, control: iSub }),
    ui.field({ id: 'fd-i-date', label: '记账起始日（必填）', required: true, control: iDate }),
    ui.field({ id: 'fd-i-amt', label: '初始资金（必填）', required: true, control: iAmt })
  ]));
  initCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-i-reviewer', label: '复核人（必填，双人复核）', required: true, control: iReviewer }),
    ui.field({ id: 'fd-i-comment', label: '复核意见', control: iComment }),
    iBtn
  ]));
  const iBody = ui.el('div');
  initCard.appendChild(iBody);
  container.appendChild(initCard);

  async function load(page) {
    state.page = page || 1;
    const q = { page: state.page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (lFrom.value) q.from = lFrom.value;
    if (lTo.value) q.to = lTo.value;
    if (lSub.value) q.subAccountId = lSub.value;
    ui.clear(lBody); ui.renderLoading(lBody, '正在查询资金台账…');
    try {
      const data = await HG.api.get(R.LEDGER, q);
      attachPager(lPager, { total: data.total, page: data.page, size: data.size }, load);
      ui.clear(lBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(lBody, '该筛选条件下无台账记录。'); return; }
      lBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'id', title: 'ID', num: true },
          { key: 'subAccountId', title: '子账户', num: true },
          { title: '记账日期', render: (r) => F.esc(F.date(r.ledgerDate)) },
          { title: '期初余额', num: true, render: (r) => F.esc(F.money(r.beginBalance)) },
          { title: '入金', num: true, render: (r) => F.esc(F.money(r.amountIn)) },
          { title: '出金', num: true, render: (r) => F.esc(F.money(r.amountOut)) },
          { title: '划拨入', num: true, render: (r) => F.esc(F.money(r.transferIn)) },
          { title: '划拨出', num: true, render: (r) => F.esc(F.money(r.transferOut)) },
          { title: '盈亏结转', num: true, render: (r) => F.esc(F.money(r.pnlCarryover)) },
          { title: '手续费', num: true, render: (r) => F.esc(F.money(r.commission)) },
          { title: '保证金变动', num: true, render: (r) => F.esc(F.money(r.marginChange)) },
          { title: '差异调整', num: true, render: (r) => F.esc(F.money(r.differenceAdjust)) },
          { title: '期末余额', num: true, render: (r) => F.esc(F.money(r.endBalance)) },
          { title: '可用资金', num: true, render: (r) => F.esc(F.money(r.availableFund)) },
          { title: '风险度', num: true, render: (r) => F.esc(F.pct(r.riskRatio)) }
        ],
        data.items
      ) }));
    } catch (err) { ui.renderError(lBody, err, () => load(state.page)); }
  }
  lBtn.addEventListener('click', () => load(1));

  iBtn.addEventListener('click', async () => {
    if (!iSub.value || !iDate.value || !iAmt.value.trim() || !iReviewer.value) {
      ui.toast('子账户、记账起始日、初始资金、复核人均必填（双人复核）', 'error'); return;
    }
    const go = await ui.confirm(
      '建立台账属双人复核动作：复核人 #' + F.esc(iReviewer.value) + ' 不得为发起人本人（R-FUND-3）。确认提交？',
      { title: '建立资金台账' });
    if (!go) return;
    ui.clear(iBody); ui.renderLoading(iBody, '正在建立台账…');
    try {
      const r = await HG.api.post(R.LEDGER, {
        subAccountId: Number(iSub.value), ledgerDate: iDate.value, beginBalance: iAmt.value.trim(),
        reviewerId: Number(iReviewer.value), reviewComment: iComment.value.trim() || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(iBody);
      iBody.appendChild(ui.banner('台账已建立（子账户 #' + F.esc(String(r.subAccountId)) + '，期初余额 ' + F.esc(F.money(r.beginBalance)) + '）。', 'ok'));
    } catch (err) { ui.renderError(iBody, err, () => iBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 2：出入金（FR-FUND-5/6/7/8；申请 + 复核 + 回执 + 流水查询）
 *        红线：双人复核提示、超时待查显式徽标（非失败色）
 * ------------------------------------------------------------------ */
function depositWithdrawTab(container) {
  // —— 申请区
  const applyCard = ui.el('section', { class: 'hg-card' });
  applyCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '发起出入金申请（经 CTP；FR-FUND-5/6）' }));
  applyCard.appendChild(ui.banner(
    '出入金按 CTP 异步回调模型处理（R-FUND-5）：<strong>不得假设同步返回</strong>，提交后状态为'
      + '「待复核」→ 复核通过后经 CTP 发出等待回执；<strong>以期货公司回执为唯一记账依据</strong>。'
      + '每次提交自动携带幂等标识（Idempotency-Key），同一标识重复提交不产生第二笔划转。'
      + '本页无资金密码字段（零落盘，NFR-SEC-1）。',
    'warn'
  ));
  const aSub = numInput('fd-dw-sub', { min: '1', placeholder: '子账户 ID' });
  const aAmt = amtInput('fd-dw-amt');
  const aDesc = ui.el('input', { id: 'fd-dw-desc', placeholder: '入金=资金来源说明 / 出金=用途说明' });
  const aInBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '发起入金' });
  const aOutBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '发起出金' });
  applyCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-dw-sub', label: '子账户（必填）', required: true, control: aSub }),
    ui.field({ id: 'fd-dw-amt', label: '金额（必填）', required: true, control: aAmt }),
    ui.field({ id: 'fd-dw-desc', label: '说明', control: aDesc })
  ]));
  applyCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [aInBtn, aOutBtn]));
  const aBody = ui.el('div');
  applyCard.appendChild(aBody);
  container.appendChild(applyCard);

  async function apply(direction) {
    if (!aSub.value || !aAmt.value.trim()) { ui.toast('子账户与金额必填', 'error'); return; }
    const label = direction === 'IN' ? '入金' : '出金';
    const go = await ui.confirm('确认发起' + label + '申请（子账户 #' + F.esc(aSub.value) + '，金额 ' + F.esc(aAmt.value.trim()) + '）？'
      + '提交后进入待复核，须经双人复核后方可经 CTP 发出。', { title: '发起' + label });
    if (!go) return;
    ui.clear(aBody); ui.renderLoading(aBody, '正在提交' + label + '申请…');
    const path = direction === 'IN' ? R.DEPOSIT : R.WITHDRAW;
    try {
      const r = await HG.api.post(path, {
        subAccountId: Number(aSub.value), amount: aAmt.value.trim(),
        fundSourceDesc: direction === 'IN' ? (aDesc.value.trim() || null) : null,
        purposeDesc: direction === 'OUT' ? (aDesc.value.trim() || null) : null,
        idempotencyKey: HG.api.newIdempotencyKey()
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(aBody);
      aBody.appendChild(ui.banner(
        label + '申请已创建：申请号 <code>' + F.esc(r.applyNo || '') + '</code>（ID #' + F.esc(String(r.id)) + '），'
          + '状态 <strong>待复核</strong>——请在下方列表执行复核（复核人不得为发起人）。', 'ok'));
    } catch (err) { ui.renderError(aBody, err, () => apply(direction)); }
  }
  aInBtn.addEventListener('click', () => apply('IN'));
  aOutBtn.addEventListener('click', () => apply('OUT'));

  // —— 申请列表（FR-FUND-8 验收3「查询接口确认」；T-195 新增 GET /fund/apply-list 只读端点。
  //     dwStatusHTML 就绪件在此激活——「超时待查」独立徽标形态首次有真实数据承载）。
  const listCard = ui.el('section', { class: 'hg-card' });
  listCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '出入金申请列表与复核（FR-FUND-7/8）' }));
  const dState = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE };
  const qStatus = ui.el('select', { id: 'fd-q-status' });
  qStatus.appendChild(ui.el('option', { value: '', text: '全部状态' }));
  Object.keys(DW_STATUS).forEach((k) => qStatus.appendChild(ui.el('option', { value: k, text: DW_STATUS[k] })));
  const qSub = numInput('fd-q-sub', { min: '1', placeholder: '可空' });
  const qFrom = dateInput('fd-q-from');
  const qTo = dateInput('fd-q-to');
  const qBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询申请' });
  listCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-q-status', label: '状态', control: qStatus }),
    ui.field({ id: 'fd-q-sub', label: '子账户', control: qSub }),
    ui.field({ id: 'fd-q-from', label: '创建日期起', control: qFrom }),
    ui.field({ id: 'fd-q-to', label: '创建日期止', control: qTo }),
    qBtn
  ]));
  const qBody = ui.el('div');
  const qPager = ui.el('div', { class: 'hg-pager' });
  listCard.appendChild(qBody);
  listCard.appendChild(qPager);
  container.appendChild(listCard);

  async function loadApplies(page) {
    dState.page = page || 1;
    const q = { page: dState.page, size: dState.size, sort: 'id,desc' };
    if (qStatus.value) q.status = qStatus.value;
    if (qSub.value) q.subAccountId = qSub.value;
    if (qFrom.value) q.from = qFrom.value;
    if (qTo.value) q.to = qTo.value;
    ui.clear(qBody); ui.renderLoading(qBody, '正在查询出入金申请…');
    try {
      const data = await HG.api.get(R.APPLY_LIST, q);
      attachPager(qPager, { total: data.total, page: data.page, size: data.size }, loadApplies);
      ui.clear(qBody);
      if (!data.items || !data.items.length) {
        ui.renderEmpty(qBody, '该筛选条件下无出入金申请。');
        return;
      }
      qBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'id', title: '申请 ID', num: true },
          { title: '申请号', render: (r) => F.esc(r.applyNo || '—') },
          { title: '子账户', num: true, render: (r) => F.esc(r.subAccountId === null || r.subAccountId === undefined ? '—' : String(r.subAccountId)) },
          { title: '方向', render: (r) => F.esc(r.direction === 'IN' ? '入金' : (r.direction === 'OUT' ? '出金' : (r.direction || '—'))) },
          { title: '金额', num: true, render: (r) => F.esc(F.money(r.amount)) },
          { title: '状态', render: (r) => dwStatusHTML(r.status) },
          { title: '申请人', num: true, render: (r) => F.esc(r.applicantId === null || r.applicantId === undefined ? '—' : String(r.applicantId)) },
          { title: '复核人', num: true, render: (r) => F.esc(r.reviewerId === null || r.reviewerId === undefined ? '—' : String(r.reviewerId)) },
          { title: '驳回原因', render: (r) => F.esc(r.rejectReason || '—') },
          { title: '创建时间', render: (r) => F.esc(F.dateTime(r.createdTime)) }
        ],
        data.items
      ) }));
      if (data.items.some((r) => r.status === 'TIMEOUT_UNKNOWN')) {
        qBody.appendChild(ui.el('div', { html: TIMEOUT_NOTE_HTML }));
      }
    } catch (err) { ui.renderError(qBody, err, () => loadApplies(dState.page)); }
  }
  qBtn.addEventListener('click', () => loadApplies(1));

  // —— 复核动作
  const reviewCard = ui.el('section', { class: 'hg-card' });
  reviewCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '出入金复核（POST /fund/{id}/review）' }));
  reviewCard.appendChild(ui.banner(DOUBLE_REVIEW_HTML, 'warn'));
  const rId = numInput('fd-r-id', { min: '1', placeholder: '申请 ID' });
  const rComment = ui.el('input', { id: 'fd-r-comment', placeholder: '复核意见（可空）' });
  const rReject = ui.el('input', { id: 'fd-r-reject', placeholder: '驳回原因（驳回时必填）' });
  const rPassBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '复核通过' });
  const rRejBtn = ui.el('button', { class: 'hg-btn hg-btn--danger', type: 'button', text: '驳回' });
  reviewCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-r-id', label: '申请 ID（必填）', required: true, control: rId }),
    ui.field({ id: 'fd-r-comment', label: '复核意见', control: rComment }),
    ui.field({ id: 'fd-r-reject', label: '驳回原因', control: rReject })
  ]));
  reviewCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [rPassBtn, rRejBtn]));
  const rBody = ui.el('div');
  reviewCard.appendChild(rBody);
  rBody.appendChild(ui.el('div', { html: TIMEOUT_NOTE_HTML }));
  container.appendChild(reviewCard);

  async function review(approved) {
    if (!rId.value) { ui.toast('申请 ID 必填', 'error'); return; }
    if (!approved && !rReject.value.trim()) { ui.toast('驳回须填写原因（FR-FUND-7 验收 3）', 'error'); return; }
    const go = await ui.confirm(
      '确认' + (approved ? '复核通过' : '驳回') + '出入金申请 #' + F.esc(rId.value) + '？'
        + '<br>双人复核：发起人与复核人不得为同一人（后端强制并记录越权尝试）。',
      { title: approved ? '复核通过' : '驳回申请', danger: !approved });
    if (!go) return;
    ui.clear(rBody); ui.renderLoading(rBody, '正在提交复核…');
    try {
      const r = await HG.api.post(R.review(rId.value), {
        approved: approved, comment: rComment.value.trim() || null,
        rejectReason: approved ? null : rReject.value.trim()
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(rBody);
      const st = DW_STATUS[r.status] || r.status || '—';
      rBody.appendChild(ui.banner('复核完成：申请 <code>' + F.esc(r.applyNo || '') + '</code> 状态 → <strong>' + F.esc(st) + '</strong>'
        + (r.status === 'SUBMITTED' ? '（已提交，等待 CTP 回执——不得假设同步返回）' : '') + '。', 'ok'));
    } catch (err) { ui.renderError(rBody, err, () => review(approved)); }
  }
  rPassBtn.addEventListener('click', () => review(true));
  rRejBtn.addEventListener('click', () => review(false));

  // —— CTP 回执处理（FR-FUND-8）
  const receiptCard = ui.el('section', { class: 'hg-card' });
  receiptCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: 'CTP 回执处理 / 主动查询确认（FR-FUND-8/9）' }));
  receiptCard.appendChild(ui.banner(
    '<strong>网络超时不代表未到账</strong>（R-FUND-5）：result=UNKNOWN 将置状态为「超时待查」，'
      + '须<b>主动查询确认</b>原请求真实结果后方可重试；重复回执由后端幂等处理（不重复记账）。',
    'warn'
  ));
  const recId = numInput('fd-rec-apply', { min: '1', placeholder: '关联申请 ID' });
  const recResult = ui.el('select', { id: 'fd-rec-result' });
  Object.keys(RECEIPT_RESULT).forEach((k) => recResult.appendChild(ui.el('option', { value: k, text: RECEIPT_RESULT[k] })));
  const recAmt = amtInput('fd-rec-amt');
  const recErr = ui.el('input', { id: 'fd-rec-err', placeholder: 'CTP 失败原因（失败回执时填写）' });
  const recConfirmed = ui.el('input', { id: 'fd-rec-confirmed', type: 'checkbox' });
  const recBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '提交回执' });
  receiptCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-rec-apply', label: '关联申请 ID（必填）', required: true, control: recId }),
    ui.field({ id: 'fd-rec-result', label: '回执结果', control: recResult }),
    ui.field({ id: 'fd-rec-amt', label: '回执金额', control: recAmt })
  ]));
  receiptCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-rec-err', label: '失败原因', control: recErr }),
    ui.field({ id: 'fd-rec-confirmed', label: '是否为主动查询确认所得（超时待查后确认时勾选）', control: recConfirmed }),
    recBtn
  ]));
  const recBody = ui.el('div');
  receiptCard.appendChild(recBody);
  container.appendChild(receiptCard);

  recBtn.addEventListener('click', async () => {
    if (!recId.value) { ui.toast('关联申请 ID 必填', 'error'); return; }
    const result = recResult.value;
    const go = await ui.confirm('确认提交回执（申请 #' + F.esc(recId.value) + '，结果 ' + F.esc(RECEIPT_RESULT[result]) + '）？'
      + (result === 'UNKNOWN' ? '<br>结果为未知：状态将置「超时待查」，不得假设失败或成功。' : ''), { title: '提交回执' });
    if (!go) return;
    ui.clear(recBody); ui.renderLoading(recBody, '正在处理回执…');
    try {
      const r = await HG.api.post(R.RECEIPT, {
        applyId: Number(recId.value), result: result,
        amount: recAmt.value.trim() || null, ctpErrorMsg: recErr.value.trim() || null,
        queryConfirmed: recConfirmed.checked, idempotencyKey: HG.api.newIdempotencyKey()
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(recBody);
      recBody.appendChild(ui.banner(
        '回执已处理（receiptId #' + F.esc(String(r.receiptId)) + '）——'
          + (r.processed ? '已按回执更新台账并生成资金流水。' : '幂等 no-op（同一回执重复提交，未重复记账）。'),
        'ok'));
      recBody.appendChild(ui.el('p', { class: 'hg-muted', text: String(r.detail || '') }));
    } catch (err) { ui.renderError(recBody, err, () => recBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 3：资金流水（FR-FUND-2/3/16；只读——只增不改不删）
 * ------------------------------------------------------------------ */
function flowTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '资金流水查询（FR-FUND-3/16）' }));
  card.appendChild(ui.banner(
    '流水<strong>只增不改不删</strong>（R-FUND-2）——本页无任何编辑 / 删除入口；每笔流水均含凭证编号与操作留痕；'
      + '划拨流水成对展示（一出一入、同一划拨单号、金额相等，FR-FUND-16 验收 1）。',
    'warn'
  ));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'ledger_date', sortDir: 'asc' };
  const fFrom = dateInput('fd-f-from');
  const fTo = dateInput('fd-f-to');
  const fSub = numInput('fd-f-sub', { min: '1', placeholder: '可空' });
  const fType = ui.el('select', { id: 'fd-f-type' });
  fType.appendChild(ui.el('option', { value: '', text: '全部类型' }));
  Object.keys(FLOW_TYPE).forEach((k) => fType.appendChild(ui.el('option', { value: k, text: FLOW_TYPE[k] })));
  const fTransfer = ui.el('input', { id: 'fd-f-transfer', placeholder: '按划拨单号查询（成对展示）' });
  const fBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询流水' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-f-from', label: '发生日期起', control: fFrom }),
    ui.field({ id: 'fd-f-to', label: '发生日期止', control: fTo }),
    ui.field({ id: 'fd-f-sub', label: '子账户', control: fSub }),
    ui.field({ id: 'fd-f-type', label: '流水类型', control: fType }),
    ui.field({ id: 'fd-f-transfer', label: '划拨单 ID', control: fTransfer }),
    fBtn
  ]));
  const fBody = ui.el('div');
  const fPager = ui.el('div', { class: 'hg-pager' });
  card.appendChild(fBody);
  card.appendChild(fPager);
  container.appendChild(card);

  const bar = ui.el('div', { class: 'hg-toolbar' });
  sortControls(bar, state, FLOW_SORTS, load, 'fd-f');
  card.appendChild(bar);

  async function load(page) {
    state.page = page || 1;
    // transferApplyId 面返回非分页数组（FundFlowHandler）；分页面返回 PageData
    if (fTransfer.value.trim()) {
      ui.clear(fBody); ui.renderLoading(fBody, '正在查询划拨流水（成对展示）…');
      try {
        const items = await HG.api.get(R.FLOW, { transferApplyId: fTransfer.value.trim() });
        ui.clear(fBody);
        const list = Array.isArray(items) ? items : ((items && items.items) || []);
        if (!list.length) { ui.renderEmpty(fBody, '该划拨单下无流水。'); return; }
        fBody.appendChild(flowTable(list));
      } catch (err) { ui.renderError(fBody, err, () => load(state.page)); }
      return;
    }
    const q = { page: state.page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (fFrom.value) q.from = fFrom.value;
    if (fTo.value) q.to = fTo.value;
    if (fSub.value) q.subAccountId = fSub.value;
    if (fType.value) q.flowType = fType.value;
    ui.clear(fBody); ui.renderLoading(fBody, '正在查询资金流水…');
    try {
      const data = await HG.api.get(R.FLOW, q);
      attachPager(fPager, { total: data.total, page: data.page, size: data.size }, load);
      ui.clear(fBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(fBody, '该筛选条件下无资金流水。'); return; }
      fBody.appendChild(flowTable(data.items));
    } catch (err) { ui.renderError(fBody, err, () => load(state.page)); }
  }
  fBtn.addEventListener('click', () => load(1));

  function flowTable(items) {
    const wrap = ui.el('div');
    wrap.appendChild(ui.el('div', { html: ui.tableHTML(
      [
        { key: 'id', title: 'ID', num: true },
        { key: 'subAccountId', title: '子账户', num: true },
        { title: '凭证编号', render: (r) => F.esc(r.voucherNo || '—') },
        { title: '记账日期', render: (r) => F.esc(F.date(r.ledgerDate)) },
        { title: '类型', render: (r) => F.esc(FLOW_TYPE[r.flowType] || r.flowType || '—') },
        { title: '方向', render: (r) => F.esc(DIR[r.direction] || r.direction || '—') },
        { title: '金额', num: true, render: (r) => F.esc(F.money(r.amount)) },
        { title: '余额（后）', num: true, render: (r) => F.esc(F.money(r.balanceAfter)) },
        { title: '对手子账户', num: true, render: (r) => F.esc(r.counterpartSubAccountId === undefined || r.counterpartSubAccountId === null ? '—' : String(r.counterpartSubAccountId)) },
        { title: '划拨单', num: true, render: (r) => F.esc(r.transferApplyId === undefined || r.transferApplyId === null ? '—' : String(r.transferApplyId)) },
        { title: '出入金申请', num: true, render: (r) => F.esc(r.depositWithdrawId === undefined || r.depositWithdrawId === null ? '—' : String(r.depositWithdrawId)) },
        { title: '操作人', num: true, render: (r) => F.esc(r.operatorId === undefined || r.operatorId === null ? '—' : String(r.operatorId)) },
        { title: '流水时间', render: (r) => F.esc(F.dateTime(r.flowTime)) },
        { title: '备注', render: (r) => F.esc(r.remark || '—') }
      ],
      items
    ) }));
    return wrap;
  }
}

/* ------------------------------------------------------------------ *
 * Tab 4：子账户划拨（FR-FUND-10/11/12/16）
 *        红线：「系统内记账，加总不变」固定提示条 + 双人复核 + 审批/加总校验
 * ------------------------------------------------------------------ */
function transferTab(container) {
  const applyCard = ui.el('section', { class: 'hg-card' });
  applyCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '发起划拨申请（FR-FUND-10）' }));
  // 验收 3 原文固定提示条（逐字「系统内记账，加总不变」）
  applyCard.appendChild(ui.banner(TRANSFER_BANNER_HTML, 'warn'));
  const tFrom = numInput('fd-t-from', { min: '1', placeholder: '划出子账户 ID' });
  const tTo = numInput('fd-t-to', { min: '1', placeholder: '划入子账户 ID' });
  const tAmt = amtInput('fd-t-amt');
  const tReason = ui.el('input', { id: 'fd-t-reason', placeholder: '划拨理由（必填）' });
  const tDate = dateInput('fd-t-date');
  const tBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '发起划拨' });
  applyCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-t-from', label: '划出子账户（必填）', required: true, control: tFrom }),
    ui.field({ id: 'fd-t-to', label: '划入子账户（必填）', required: true, control: tTo }),
    ui.field({ id: 'fd-t-amt', label: '金额（必填）', required: true, control: tAmt })
  ]));
  applyCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-t-reason', label: '划拨理由（必填，FR-FUND-10）', required: true, control: tReason }),
    ui.field({ id: 'fd-t-date', label: '期望生效日', control: tDate }),
    tBtn
  ]));
  const tBody = ui.el('div');
  applyCard.appendChild(tBody);
  container.appendChild(applyCard);

  tBtn.addEventListener('click', async () => {
    if (!tFrom.value || !tTo.value || !tAmt.value.trim() || !tReason.value.trim()) {
      ui.toast('划出/划入子账户、金额、划拨理由均必填（FR-FUND-10）', 'error'); return;
    }
    if (tFrom.value === tTo.value) { ui.toast('划出与划入不得为同一子账户（R-FUND-6）', 'error'); return; }
    const go = await ui.confirm(
      '确认发起划拨（#' + F.esc(tFrom.value) + ' → #' + F.esc(tTo.value) + '，金额 ' + F.esc(tAmt.value.trim()) + '）？'
        + '<br><strong>系统内记账，加总不变</strong>——真实期货账户资金不变动；须经审批后方可记账。',
      { title: '发起划拨' });
    if (!go) return;
    ui.clear(tBody); ui.renderLoading(tBody, '正在提交划拨申请…');
    try {
      const r = await HG.api.post(R.TRANSFER, {
        fromSubAccountId: Number(tFrom.value), toSubAccountId: Number(tTo.value),
        amount: tAmt.value.trim(), reason: tReason.value.trim(),
        expectedEffectiveDate: tDate.value || null,
        idempotencyKey: HG.api.newIdempotencyKey()
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(tBody);
      tBody.appendChild(ui.banner(
        '划拨申请已创建：申请号 <code>' + F.esc(r.applyNo || '') + '</code>（ID #' + F.esc(String(r.id)) + '），'
          + '状态 <strong>' + F.esc(TR_STATUS[r.status] || r.status || '—') + '</strong>——请在下方列表执行审批'
          + '（审批人不得为发起人且不得为交易岗）。', 'ok'));
    } catch (err) { ui.renderError(tBody, err, () => tBtn.click()); }
  });

  // —— 划拨列表（含超时/回滚形态说明与状态徽标）
  const listCard = ui.el('section', { class: 'hg-card' });
  listCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '划拨记录查询（FR-FUND-16）' }));
  const sStatus = ui.el('select', { id: 'fd-t-status' });
  sStatus.appendChild(ui.el('option', { value: '', text: '全部状态' }));
  Object.keys(TR_STATUS).forEach((k) => sStatus.appendChild(ui.el('option', { value: k, text: TR_STATUS[k] })));
  const sSub = numInput('fd-t-sub', { min: '1', placeholder: '可空' });
  const sBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询划拨' });
  listCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-t-status', label: '状态', control: sStatus }),
    ui.field({ id: 'fd-t-sub', label: '子账户', control: sSub }),
    sBtn
  ]));
  const sBody = ui.el('div');
  const sPager = ui.el('div', { class: 'hg-pager' });
  listCard.appendChild(sBody);
  listCard.appendChild(sPager);
  container.appendChild(listCard);

  const tState = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'id', sortDir: 'desc' };
  const bar = ui.el('div', { class: 'hg-toolbar' });
  sortControls(bar, tState, TRANSFER_SORTS, loadList, 'fd-tl');
  listCard.appendChild(bar);

  async function loadList(page) {
    tState.page = page || 1;
    const q = { page: tState.page, size: tState.size, sort: tState.sortField + ',' + tState.sortDir };
    if (sStatus.value) q.status = sStatus.value;
    if (sSub.value) q.subAccountId = sSub.value;
    ui.clear(sBody); ui.renderLoading(sBody, '正在查询划拨记录…');
    try {
      const data = await HG.api.get(R.TRANSFER, q);
      attachPager(sPager, { total: data.total, page: data.page, size: data.size }, loadList);
      ui.clear(sBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(sBody, '该筛选条件下无划拨记录。'); return; }
      sBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'id', title: 'ID', num: true },
          { key: 'applyNo', title: '申请号' },
          { title: '划出 → 划入', render: (r) => F.esc('#' + (r.fromSubAccountId ?? '—') + ' → #' + (r.toSubAccountId ?? '—')) },
          { title: '金额', num: true, render: (r) => F.esc(F.money(r.amount)) },
          { key: 'reason', title: '划拨理由' },
          { title: '状态', render: (r) => trStatusHTML(r.status) },
          { title: '申请人', num: true, render: (r) => F.esc(r.applicantId === undefined || r.applicantId === null ? '—' : String(r.applicantId)) },
          { title: '审批人', num: true, render: (r) => F.esc(r.approverId === undefined || r.approverId === null ? '—' : String(r.approverId)) },
          { title: '加总校验', render: (r) => (r.aggCheckPassed === undefined || r.aggCheckPassed === null ? '—' : (r.aggCheckPassed ? '<span class="hg-badge hg-badge--active">通过</span>' : '<span class="hg-badge hg-badge--danger">不通过（已回滚）</span>')) },
          { title: '审批时间', render: (r) => F.esc(F.dateTime(r.approveTime)) },
          { title: '记账时间', render: (r) => F.esc(F.dateTime(r.postingTime)) },
          { title: '回滚时间', render: (r) => F.esc(F.dateTime(r.rollbackTime)) }
        ],
        data.items
      ) }));
    } catch (err) { ui.renderError(sBody, err, () => loadList(tState.page)); }
  }
  sBtn.addEventListener('click', () => loadList(1));

  // —— 划拨审批（双人复核红线）
  const approveCard = ui.el('section', { class: 'hg-card' });
  approveCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '划拨审批（POST /fund/transfer/{id}/approve）' }));
  approveCard.appendChild(ui.banner(DOUBLE_REVIEW_HTML
    + '<br>划拨<strong>审批人不得为交易岗</strong>（岗位分离，FR-FUND-11）；审批通过后系统记账并做加总校验'
    + '（Σ 子账户 = 真实账户，校验不通过自动回滚并告警——FR-FUND-12）。', 'warn'));
  const apId = numInput('fd-ap-id', { min: '1', placeholder: '划拨申请 ID' });
  const apComment = ui.el('input', { id: 'fd-ap-comment', placeholder: '审批意见（可空）' });
  const apReject = ui.el('input', { id: 'fd-ap-reject', placeholder: '驳回原因（驳回时必填）' });
  const apReal = amtInput('fd-ap-real', { placeholder: '可空——提供则参与加总校验' });
  const apPassBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '审批通过' });
  const apRejBtn = ui.el('button', { class: 'hg-btn hg-btn--danger', type: 'button', text: '驳回' });
  approveCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-ap-id', label: '划拨申请 ID（必填）', required: true, control: apId }),
    ui.field({ id: 'fd-ap-comment', label: '审批意见', control: apComment }),
    ui.field({ id: 'fd-ap-reject', label: '驳回原因', control: apReject }),
    ui.field({ id: 'fd-ap-real', label: '真实账户可用资金（加总校验用）', control: apReal })
  ]));
  approveCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [apPassBtn, apRejBtn]));
  const apBody = ui.el('div');
  approveCard.appendChild(apBody);
  apBody.appendChild(ui.el('div', { html: TIMEOUT_NOTE_HTML }));
  container.appendChild(approveCard);

  async function approve(approved) {
    if (!apId.value) { ui.toast('划拨申请 ID 必填', 'error'); return; }
    if (!approved && !apReject.value.trim()) { ui.toast('驳回须填写原因（FR-FUND-11 验收 3）', 'error'); return; }
    const go = await ui.confirm(
      '确认' + (approved ? '审批通过' : '驳回') + '划拨申请 #' + F.esc(apId.value) + '？'
        + '<br>双人复核：发起人与审批人不得为同一人；审批通过后将记账并做加总校验（不通过自动回滚）。',
      { title: approved ? '审批通过' : '驳回划拨', danger: !approved });
    if (!go) return;
    ui.clear(apBody); ui.renderLoading(apBody, '正在提交审批…');
    try {
      const r = await HG.api.post(R.transferApprove(apId.value), {
        approved: approved, comment: apComment.value.trim() || null,
        rejectReason: approved ? null : apReject.value.trim(),
        realAccountAvailable: approved && apReal.value.trim() ? apReal.value.trim() : null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(apBody);
      const st = TR_STATUS[r.status] || r.status || '—';
      const agg = r.aggCheckPassed === true
        ? '加总校验<strong>通过</strong>（划拨前后加总不变）。'
        : (r.aggCheckPassed === false ? '加总校验<strong>不通过</strong>——已回滚本次记账并告警（FR-FUND-12）。' : '');
      apBody.appendChild(ui.banner('审批完成：划拨 <code>' + F.esc(r.applyNo || '') + '</code> 状态 → <strong>' + F.esc(st) + '</strong>。' + agg,
        r.aggCheckPassed === false ? 'error' : 'ok'));
    } catch (err) { ui.renderError(apBody, err, () => approve(approved)); }
  }
  apPassBtn.addEventListener('click', () => approve(true));
  apRejBtn.addEventListener('click', () => approve(false));
}

/* ------------------------------------------------------------------ *
 * Tab 5：资金额度（FR-FUND-13/14）——查询 + 设定（判定在服务端）
 * ------------------------------------------------------------------ */
function quotaTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '资金额度（FR-FUND-13/14）' }));
  card.appendChild(ui.banner(
    '额度按<strong>子账户 / 账户</strong>两级设定，不得跨子账户挪用（R-FUND-7）；'
      + '超限<strong>告警不阻断</strong>减仓与出金；额度设定的权限判定在服务端——本页只做展示与提交。',
    'warn'
  ));
  const qSub = numInput('fd-q-sub', { min: '1', placeholder: '可空' });
  const qScope = ui.el('select', { id: 'fd-q-scope' });
  qScope.appendChild(ui.el('option', { value: '', text: '全部维度' }));
  Object.keys(QUOTA_SCOPE).forEach((k) => qScope.appendChild(ui.el('option', { value: k, text: QUOTA_SCOPE[k] })));
  const qBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询额度' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-q-sub', label: '子账户', control: qSub }),
    ui.field({ id: 'fd-q-scope', label: '额度维度', control: qScope }),
    qBtn
  ]));
  const qBody = ui.el('div');
  card.appendChild(qBody);
  container.appendChild(card);

  async function load() {
    const q = {};
    if (qSub.value) q.subAccountId = qSub.value;
    if (qScope.value) q.quotaScope = qScope.value;
    ui.clear(qBody); ui.renderLoading(qBody, '正在查询资金额度…');
    try {
      const items = await HG.api.get(R.QUOTA, q);
      ui.clear(qBody);
      const list = Array.isArray(items) ? items : ((items && items.items) || []);
      if (!list.length) { ui.renderEmpty(qBody, '该筛选条件下无额度记录。'); return; }
      qBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'id', title: 'ID', num: true },
          { key: 'subAccountId', title: '子账户', num: true, render: (r) => F.esc(r.subAccountId === null ? '（账户级）' : String(r.subAccountId)) },
          { title: '维度', render: (r) => F.esc(QUOTA_SCOPE[r.quotaScope] || r.quotaScope || '—') },
          { title: '额度值', num: true, render: (r) => F.esc(F.money(r.quotaValue)) },
          { title: '生效起', render: (r) => F.esc(F.date(r.effectiveFrom) || '—') },
          { title: '生效止', render: (r) => F.esc(F.date(r.effectiveTo) || '—') },
          { title: '操作人', num: true, render: (r) => F.esc(r.operatorId === undefined || r.operatorId === null ? '—' : String(r.operatorId)) },
          { key: 'changeReason', title: '变更原因' }
        ],
        list
      ) }));
    } catch (err) { ui.renderError(qBody, err, () => load()); }
  }
  qBtn.addEventListener('click', () => load());

  const setCard = ui.el('section', { class: 'hg-card' });
  setCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '设定资金额度（POST /fund/quota）' }));
  const sScope = ui.el('select', { id: 'fd-q-set-scope' });
  Object.keys(QUOTA_SCOPE).forEach((k) => sScope.appendChild(ui.el('option', { value: k, text: QUOTA_SCOPE[k] })));
  sScope.value = 'SUB_ACCOUNT';
  const sSub = numInput('fd-q-set-sub', { min: '1', placeholder: '账户级留空' });
  const sValue = amtInput('fd-q-set-value');
  const sFrom = dateInput('fd-q-set-from');
  const sTo = dateInput('fd-q-set-to');
  const sReason = ui.el('input', { id: 'fd-q-set-reason', placeholder: '变更原因（留痕）' });
  const sReal = amtInput('fd-q-set-real', { placeholder: '账户级须提供，用于上限校验' });
  const sBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '设定额度' });
  setCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-q-set-scope', label: '额度维度（必填）', required: true, control: sScope }),
    ui.field({ id: 'fd-q-set-sub', label: '子账户', control: sSub }),
    ui.field({ id: 'fd-q-set-value', label: '额度值（必填）', required: true, control: sValue })
  ]));
  setCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-q-set-from', label: '生效起始日', control: sFrom }),
    ui.field({ id: 'fd-q-set-to', label: '生效截止日', control: sTo }),
    ui.field({ id: 'fd-q-set-reason', label: '变更原因', control: sReason }),
    ui.field({ id: 'fd-q-set-real', label: '真实账户可用资金', control: sReal }),
    sBtn
  ]));
  const sBody = ui.el('div');
  setCard.appendChild(sBody);
  container.appendChild(setCard);

  sBtn.addEventListener('click', async () => {
    const scope = sScope.value;
    if (!scope) { ui.toast('额度维度必填（SUB_ACCOUNT / ACCOUNT）', 'error'); return; }
    if (!sValue.value.trim()) { ui.toast('额度值必填', 'error'); return; }
    if (scope === 'SUB_ACCOUNT' && !sSub.value) { ui.toast('子账户维度须填写子账户 ID', 'error'); return; }
    const go = await ui.confirm(
      '确认设定' + F.esc(QUOTA_SCOPE[scope]) + '额度（'
        + (scope === 'SUB_ACCOUNT' ? '子账户 #' + F.esc(sSub.value) : '账户级') + '，'
        + '额度 ' + F.esc(sValue.value.trim()) + '）？额度变更将留痕。', { title: '设定资金额度' });
    if (!go) return;
    ui.clear(sBody); ui.renderLoading(sBody, '正在提交额度设定…');
    try {
      const r = await HG.api.post(R.QUOTA, {
        subAccountId: scope === 'SUB_ACCOUNT' ? Number(sSub.value) : null,
        quotaScope: scope, quotaValue: sValue.value.trim(),
        effectiveFrom: sFrom.value || null, effectiveTo: sTo.value || null,
        changeReason: sReason.value.trim() || null,
        realAccountAvailable: sReal.value.trim() || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(sBody);
      sBody.appendChild(ui.banner(
        '额度已生效：' + F.esc(QUOTA_SCOPE[r.quotaScope] || r.quotaScope || '') + '（'
          + F.esc(r.subAccountId === null || r.subAccountId === undefined ? '账户级' : '#' + String(r.subAccountId)) + '）'
          + ' = ' + F.esc(F.money(r.quotaValue)) + '。变更已留痕（操作人 #' + F.esc(String(r.operatorId ?? '—')) + '）。', 'ok'));
      load();
    } catch (err) { ui.renderError(sBody, err, () => sBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 6：保证金占用与对账（FR-FUND-15 只读视图 + FR-FUND-17 每日对账）
 * ------------------------------------------------------------------ */
function marginTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '保证金占用视图（FR-FUND-15，只读）' }));
  const mDate = dateInput('fd-m-date');
  const mSub = numInput('fd-m-sub', { min: '1', placeholder: '可空=全子账户' });
  const mBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询保证金' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-m-date', label: '交易日', control: mDate }),
    ui.field({ id: 'fd-m-sub', label: '子账户', control: mSub }),
    mBtn
  ]));
  const mBody = ui.el('div');
  card.appendChild(mBody);
  container.appendChild(card);

  mBtn.addEventListener('click', async () => {
    const q = {};
    if (mDate.value) q.tradeDate = mDate.value;
    if (mSub.value) q.subAccountId = mSub.value;
    ui.clear(mBody); ui.renderLoading(mBody, '正在查询保证金占用…');
    try {
      const r = await HG.api.get(R.MARGIN, q);
      ui.clear(mBody);
      const rows = (r && r.rows) || [];
      if (!rows.length) { ui.renderEmpty(mBody, '该条件下无保证金占用数据（数据未就绪时以后端返回为准）。'); return; }
      mBody.appendChild(ui.el('h4', { text: '汇总（Σ 各子账户，口径与 SETTLE 一致）' }));
      mBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { title: '项', render: (row) => F.esc(row[0]) },
          { title: '值', num: true, render: (row) => F.esc(row[1]) }
        ],
        [
          ['占用保证金合计', F.money(r.marginOccupiedTotal)],
          ['可用资金合计', F.money(r.availableFundTotal)],
          ['权益合计', F.money(r.equityTotal)]
        ]
      ) }));
      mBody.appendChild(ui.el('h4', { text: '子账户明细' }));
      mBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'subAccountId', title: '子账户', num: true },
          { title: '占用保证金', num: true, render: (r2) => F.esc(F.money(r2.marginOccupied)) },
          { title: '可用资金', num: true, render: (r2) => F.esc(F.money(r2.availableFund)) },
          { title: '权益', num: true, render: (r2) => F.esc(F.money(r2.equity)) },
          { title: '风险度', num: true, render: (r2) => F.esc(F.pct(r2.riskRatio)) }
        ],
        rows
      ) }));
    } catch (err) { ui.renderError(mBody, err, () => mBtn.click()); }
  });

  // —— 每日资金对账（FR-FUND-17）
  const rcCard = ui.el('section', { class: 'hg-card' });
  rcCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '每日资金对账（FR-FUND-17）' }));
  rcCard.appendChild(ui.banner(
    '对账校验「期初 + 发生额 = 期末」（按子账户）与「Σ 子账户 = 真实账户」——与 FR-SETTLE-8 <strong>同口径、同结果</strong>（R-FUND-8）；'
      + '存在未闭环差异时<strong>不得标记对账完成</strong>，差异清单逐项展示。',
    'warn'
  ));
  const rcDate = dateInput('fd-rc-date');
  const rcReal = amtInput('fd-rc-real', { placeholder: '真实账户可用资金（可空）' });
  const rcBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '执行对账' });
  rcCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-rc-date', label: '交易日', control: rcDate }),
    ui.field({ id: 'fd-rc-real', label: '真实账户可用资金', control: rcReal }),
    rcBtn
  ]));
  const rcBody = ui.el('div');
  rcCard.appendChild(rcBody);
  container.appendChild(rcCard);

  rcBtn.addEventListener('click', async () => {
    const go = await ui.confirm('确认执行每日资金对账' + (rcDate.value ? '（交易日 ' + F.esc(rcDate.value) + '）' : '') + '？', { title: '每日资金对账' });
    if (!go) return;
    ui.clear(rcBody); ui.renderLoading(rcBody, '正在执行每日资金对账…');
    try {
      const r = await HG.api.post(R.RECONCILE, {
        tradeDate: rcDate.value || null,
        realAccountAvailable: rcReal.value.trim() || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(rcBody);
      const okResult = String(r.result || '').indexOf('一致') !== -1 || String(r.result || '').toUpperCase() === 'CONSISTENT';
      rcBody.appendChild(ui.banner(
        '对账结论（交易日 ' + F.esc(F.date(r.tradeDate) || '—') + '）：<strong>' + F.esc(r.result || '—') + '</strong>'
          + (okResult ? '' : '——存在差异，<strong>不得标记对账完成</strong>。'),
        okResult ? 'ok' : 'error'));
      const subs = (r && r.subAccounts) || [];
      if (subs.length) {
        rcBody.appendChild(ui.el('h4', { text: '子账户对账明细' }));
        rcBody.appendChild(ui.el('div', { html: ui.tableHTML(
          [
            { key: 'subAccountId', title: '子账户', num: true },
            { title: '层级', render: (r2) => F.esc(r2.reconcileLevel || '—') },
            { title: '期初', num: true, render: (r2) => F.esc(F.money(r2.beginBalance)) },
            { title: '发生额合计', num: true, render: (r2) => F.esc(F.money(r2.occurrenceTotal)) },
            { title: '期末', num: true, render: (r2) => F.esc(F.money(r2.endBalance)) },
            { title: '期初+发生=期末', render: (r2) => (r2.balanceEquationOk ? '<span class="hg-badge hg-badge--active">成立</span>' : '<span class="hg-badge hg-badge--danger">不成立</span>') },
            { title: '加总比较值', num: true, render: (r2) => F.esc(F.money(r2.aggCompareValue)) },
            { title: '真实账户值', num: true, render: (r2) => F.esc(F.money(r2.realAccountValue)) },
            { title: '加总一致', render: (r2) => (r2.aggConsistent ? '<span class="hg-badge hg-badge--active">一致</span>' : '<span class="hg-badge hg-badge--danger">不一致</span>') }
          ],
          subs
        ) }));
      }
      const diffs = (r && r.differences) || [];
      if (diffs.length) {
        rcBody.appendChild(ui.banner('<strong>差异清单（' + diffs.length + ' 项）</strong>——未闭环差异不得标记对账完成（FR-FUND-17）。', 'error'));
        rcBody.appendChild(ui.el('pre', { class: 'hg-pre', text: JSON.stringify(diffs, null, 2) }));
      }
    } catch (err) { ui.renderError(rcBody, err, () => rcBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 7：资金对账导出（FR-FUND-4；T-195 起 GET /fund/export 返回 CSV 直返——
 *        Content-Type=text/csv，前端以 HG.api.download 下载；与 /product/export 同构）
 * ------------------------------------------------------------------ */
function exportTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '资金对账导出（FR-FUND-4）' }));
  card.appendChild(ui.banner(
    '导出仅覆盖<strong>有权限的子账户范围</strong>；导出物为 <strong>CSV 对账表</strong>，含期初余额、'
      + '各类型发生额小计、期末余额、凭证编号与<strong>子账户小计 + 全账户合计</strong>（便于加总对账）；'
      + '无数据时不生成文件并给出提示（FR-FUND-4 验收 4）。',
    'warn'
  ));
  const eFrom = dateInput('fd-e-from');
  const eTo = dateInput('fd-e-to');
  const eSub = numInput('fd-e-sub', { min: '1', placeholder: '可空' });
  const eBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '导出 CSV 对账表' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'fd-e-from', label: '日期起', control: eFrom }),
    ui.field({ id: 'fd-e-to', label: '日期止', control: eTo }),
    ui.field({ id: 'fd-e-sub', label: '子账户', control: eSub }),
    eBtn
  ]));
  const eBody = ui.el('div');
  card.appendChild(eBody);
  container.appendChild(card);

  eBtn.addEventListener('click', async () => {
    const q = {};
    if (eFrom.value) q.from = eFrom.value;
    if (eTo.value) q.to = eTo.value;
    if (eSub.value) q.subAccountId = eSub.value;
    ui.clear(eBody); ui.renderLoading(eBody, '正在生成资金对账导出…');
    try {
      const res = await HG.api.download(R.EXPORT, q);
      const text = await res.blob.text();
      // 行数统计：仅用于「无数据 ⇒ 不生成空文件」的界面裁决（FR-FUND-4 验收 4；/product/export 同构）
      const lines = text.replace(/^\uFEFF/, '').split(/\r\n|\n/).filter((l) => l !== '');
      if (lines.length <= 1) {
        ui.clear(eBody);
        ui.renderEmpty(eBody, '无数据——不生成导出文件（FR-FUND-4 验收 4）。');
        return;
      }
      eBody.appendChild(ui.el('p', { class: 'hg-muted', text: 'CSV 已生成：数据行 '
        + (lines.length - 1) + ' 行（含元信息/小计/合计行），正在触发下载…' }));
      triggerCsvDownload(res.blob, res.filename || 'fund-reconcile.csv');
      ui.toast('资金对账表已导出（CSV）', 'ok');
    } catch (err) { ui.renderError(eBody, err, () => eBtn.click()); }
  });
}

/** 触发浏览器下载（/product/pages 先例同构；探针 DOM 桩下 appendChild 为 no-op 安全）。 */
function triggerCsvDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'fund-reconcile.csv';
  a.style.display = 'none';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.setTimeout(() => { URL.revokeObjectURL(url); }, 1000);
}

/* ------------------------------------------------------------------ *
 * 页面装配
 * ------------------------------------------------------------------ */
const TABS = [
  { key: 'ledger', label: '资金台账', build: ledgerTab },
  { key: 'dw', label: '出入金与复核', build: depositWithdrawTab },
  { key: 'flow', label: '资金流水', build: flowTab },
  { key: 'transfer', label: '子账户划拨', build: transferTab },
  { key: 'quota', label: '资金额度', build: quotaTab },
  { key: 'margin', label: '保证金与对账', build: marginTab },
  { key: 'export', label: '对账导出', build: exportTab }
];

function renderFund(root, ctx) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '资金管理（FUND）' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '资金台账 / 出入金与双人复核 / 资金流水 / 子账户划拨（系统内记账）/ 资金额度 / 保证金占用 / 每日对账 / 对账导出（FR-FUND-1~17）。' }));
  root.appendChild(ui.banner(
    '<strong>界面边界声明</strong>：本页仅提供界面，不做业务判定——双人复核（发起人≠复核人/审批人）、'
      + '岗位分离、可用资金充足、加总对账差异认定、回执幂等<strong>一律由后端负责</strong>；'
      + '出入金按 CTP 异步模型处理（不得假设同步返回），「超时待查」= 网络超时不代表未到账，须查询确认。'
      + '本页无任何资金密码字段（零落盘，NFR-SEC-1），流水为只读（只增不改不删）。',
    'warn'
  ));

  const tabBar = ui.el('div', { class: 'hg-tabs', role: 'tablist' });
  const panel = ui.el('div');
  let active = (ctx && ctx.query && ctx.query.tab) || 'ledger';
  if (!TABS.some((t) => t.key === active)) active = 'ledger';

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
    const b = ui.el('button', { type: 'button', role: 'tab', text: t.label, onclick: () => HG.router.navigate('/fund?tab=' + t.key) });
    b.dataset.key = t.key;
    tabBar.appendChild(b);
  });

  root.appendChild(tabBar);
  root.appendChild(panel);
  select(active);
}

HG.pages = HG.pages || {};
HG.pages.fund = { renderFund };
HG.router.register('/fund', renderFund);
export default HG.pages.fund;
