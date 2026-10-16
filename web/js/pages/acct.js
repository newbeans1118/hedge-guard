/*
 * 套保会计页面（ACCT）—— 管理界面（FR-ACCT-1~14；T-083）。
 *
 * ## 端点（10 路径 / 17 (方法,路径) 对，逐字对齐 ApiRoutes.byModule().get("ACCT")；
 *    方法对取自 handler/acct/AcctRoutes.java；wire 字段名以各 Handler 请求/视图类为准）
 *   GET|POST /acct/hedge-relation            套期关系分页 / 生成（三要素门禁 R-ACCT-1；FR-ACCT-1）
 *   GET|POST /acct/effectiveness             评估分页 / 监测复核清单 / 未评估提醒 / 提交评估（FR-ACCT-2/4）
 *   GET|POST /acct/rebalance                 再平衡史（?hedgeRelationId=）/ 执行再平衡（四类触发 Q-8；FR-ACCT-3）
 *   GET|POST /acct/voucher                   凭证分页与详情 / 草稿生成 + 草稿删除（FR-ACCT-6/8/13）
 *   POST     /acct/voucher/{id}/review       凭证审核 SUBMIT/APPROVE/REJECT/WITHDRAW（R-ACCT-6；FR-ACCT-7）
 *   POST     /acct/voucher/{id}/reverse      凭证冲销（反向凭证 + 双人复核 DC-032；FR-ACCT-13）
 *   GET|POST /acct/period                    期间分页与关账历史 / 创建开放期间（FR-ACCT-12）
 *   POST     /acct/period/{id}/close         关账/撤销关账（三前置 + 双人复核 R-ACCT-8；FR-ACCT-12）
 *   GET|POST /acct/effect                    套保效果视图（同屏双栏）/ 计算（FR-ACCT-5）
 *   GET|POST /acct/report/dual-pnl           上报记录查询 / 提交上报（两栏恒在 R-ACCT-4；FR-ACCT-10）
 *
 * ## 未注册 2 路径（ApiRoutes 路径表已登记、handler/ 无对应 Handler ⇒ 调用即 404）
 *   /acct/selfcheck（FR-ACCT-14 自检清单）、/acct/export（FR-ACCT-9 凭证导出）
 *   —— 登记于 routes.js NOT_REGISTERED；本页保留 Tab 框架 + 「未注册」诚实提示，
 *   **不发起调用**（risk.js T-070 同形态）；缺口只报不改。
 *
 * ## 业务红线（写进页面逻辑）
 *   · R-ACCT-4（17 号文）：实货盈亏与衍生品盈亏**同屏双栏、成对恒在**——实货侧缺价时
 *     值为 null + 标注（栏位**不省略**，禁止只报衍生品盈亏）；price_as_of 三态标注
 *     （当日价 / 非当日价旗标（必带 price_as_of）/ 无法计算 + 缺失原因）（Q-12/R-ACCT-9）；
 *   · R-ACCT-10：本期**所有**套期会计文档标注「待外部复核」——套期关系 / 评估记录 /
 *     效果数据 / 上报记录随行视图 pendingExternalReview 显式徽标；
 *   · R-ACCT-1：三要素门禁——POST /acct/hedge-relation 失败 10012 时**并列缺失项原文展示**
 *     （经 HG.errmsg.render 透出后端 message，不吞成通用错误）；
 *   · R-ACCT-2/FR-ACCT-4：定性有效性（三项观察点），量化监测**仅提示不判定**（不参与有效性判定，
 *     页面不提供以监测值改写结论的任何入口；无 80%–125% 判据入口）；
 *   · R-ACCT-3：再平衡四类触发；**偏离值由服务端按数据面复核，不采信客户端声明**——
 *     偏离值在页面仅只读展示（无输入项），触发条件不满足 10012 时展示服务端返回的当前偏离值；
 *   · R-ACCT-5：凭证不可修改只能冲销；凭证必须按子账户出账（子账户缺失前端预检拦截）；
 *     正式凭证无编辑/删除入口（删除仅草稿走 POST action=DELETE_DRAFT 显式通道）；
 *   · FR-ACCT-8 验收 2：凭证列表/详情展示关联单据四键（hedgePlanId/hedgeRelationId/
 *     settleCycleId/exposureId，VoucherHandler.view 同源）；套保计划深链 /plan/{id}
 *     （plan.js:1283 已注册路由）；实货敞口/套期关系/结算周期无详情路由 ⇒ 仅展示编号
 *     （TE-T083-001 修复，FR-AUDIT-2 反向追溯入口）；
 *   · R-ACCT-6：岗位分离——凭证**生成人与审核人不得为同一人**（后端强制，页面显式提示）；
 *     驳回原因必填（前端预检 + 后端强制）；
 *   · R-ACCT-8：关账三前置（①凭证全部已审核/已冲销 ②结算已完成 ③评估已完成）任一不满足
 *     不得关账（10012 并列展示未审核凭证）；关账与撤销关账均须双人复核（reviewerId 必填）；
 *   · R-ACCT-11（Q-9）：凭证为**草稿（DRAFT_ONLY）不做总账过账**，标注「待企业财务复核入账」
 *     （随响应 draftPendingEntryNote 透出）；科目来自 CONFIG 字典（页面不硬编码科目）；
 *   · R-ACCT-12：口径单一——效果页 / 上报 / 凭证同源（衍生品盈亏取效果汇总行快照），
 *     页面不做任何自行计算，仅展示服务端产出；
 *   · R-ACCT-7：期权与期货同等对待（浮动盈亏纳入效果评估、凭证类型含期权三值）。
 *
 * ## 幂等纪律
 *   写操作（hedge-relation 生成 / 评估提交 / 再平衡 / 凭证草稿与删除 / 审核 / 冲销 /
 *   期间创建与关账 / 效果计算 / 上报提交）一律携带 Idempotency-Key（fund.js/auth.js 先例）。
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
const A = HG.routes.ACCT;
const NR = HG.routes.NOT_REGISTERED;

/* ------------------------------------------------------------------ *
 * 取值域（以 AcctService 常量 / 实体 CHECK 约束实测为准）
 * ------------------------------------------------------------------ */
/** 套期关系状态（AcctService.REL_*）。 */
const REL_STATUS = {
  DESIGNATED: '已指定', EFFECTIVE: '有效', NEED_REBALANCE: '需再平衡',
  REDESIGNATED: '已重新指定', TERMINATED: '已终止'
};
/** 评估结论（AcctService.CONCLUSION_*）。 */
const CONCLUSIONS = {
  EFFECTIVE: '有效', INEFFECTIVE: '无效（需终止或再平衡）', NEED_REBALANCE: '需再平衡'
};
/** 评估结论徽标（无效=警示色；需再平衡=冻结色——均为显式业务态，不渲染为静默）。 */
function conclusionHTML(c) {
  const t = CONCLUSIONS[c] || c || '—';
  const cls = c === 'EFFECTIVE' ? 'active' : (c === 'INEFFECTIVE' ? 'danger' : 'frozen');
  return '<span class="hg-badge hg-badge--' + cls + '" data-ac-conclusion="' + F.esc(c || '') + '">' + F.esc(t) + '</span>';
}
/** 套期关系状态徽标（data-ac-relation-status 锚标）。 */
function relationStatusHTML(s) {
  const t = REL_STATUS[s] || s || '—';
  const cls = s === 'EFFECTIVE' || s === 'REDESIGNATED' ? 'active'
    : (s === 'NEED_REBALANCE' ? 'frozen' : (s === 'TERMINATED' ? 'done' : 'draft'));
  return '<span class="hg-badge hg-badge--' + cls + '" data-ac-relation-status="' + F.esc(s || '') + '">' + F.esc(t) + '</span>';
}
/** 凭证状态（Voucher.status：DRAFT/PENDING_REVIEW/POSTED/REVERSED）。 */
const VOUCHER_STATUS = {
  DRAFT: '草稿', PENDING_REVIEW: '待审核', POSTED: '已审核（正式）', REVERSED: '已冲销'
};
function voucherStatusHTML(s) {
  const t = VOUCHER_STATUS[s] || s || '—';
  const cls = s === 'POSTED' ? 'active' : (s === 'PENDING_REVIEW' ? 'pending'
    : (s === 'REVERSED' ? 'done' : 'draft'));
  return '<span class="hg-badge hg-badge--' + cls + '" data-ac-voucher-status="' + F.esc(s || '') + '">' + F.esc(t) + '</span>';
}
/** 期间状态（OPEN/CLOSED）。 */
function periodStatusHTML(s) {
  const t = s === 'OPEN' ? '开放' : (s === 'CLOSED' ? '已关账' : (s || '—'));
  const cls = s === 'OPEN' ? 'active' : (s === 'CLOSED' ? 'frozen' : 'draft');
  return '<span class="hg-badge hg-badge--' + cls + '" data-ac-period-status="' + F.esc(s || '') + '">' + F.esc(t) + '</span>';
}
/** 凭证类型（ck_t_voucher_type 十值，含 DC-031 期权行权/作废三值）。 */
const VOUCHER_TYPES = {
  CLOSE_PNL: '平仓盈亏', COMMISSION: '手续费', MARGIN_CHANGE: '保证金变动', FLOAT_PNL: '浮动盈亏',
  PREMIUM: '期权权利金', EXERCISE: '期权行权', EXPIRED_WORTHLESS: '期权到期作废',
  HEDGE_EFFECT: '套保效果结转', REVERSAL: '冲销（反向）', OTHER: '其他'
};
/** 凭证审核动作（AcctService.VoucherReviewRequest.action）。 */
const REVIEW_ACTIONS = { SUBMIT: '提交审核', APPROVE: '审核通过', REJECT: '驳回', WITHDRAW: '撤回' };
/** 凭证关联单据四键（VoucherHandler.view 恒带：hedgePlanId/hedgeRelationId/settleCycleId/exposureId；
 *  FR-ACCT-8 验收 2 追溯入口，TE-T083-001 修复）。
 *  路由承载实测（routes.js / 各页 HG.router.register 实况）：套保计划 /plan/:id 已注册（plan.js）
 *  ⇒ 深链跳转；实货敞口（spot.js 仅注册 /spot、/spot/exposure，无 /spot/exposure/{id} 详情路由）、
 *  套期关系、结算周期无详情路由 ⇒ 仅展示编号（可选中复制；套期关系可经「再平衡史」按编号查询）。 */
const RELATED_DOC_DEFS = [
  { key: 'hedgePlanId', label: '套保计划' },
  { key: 'hedgeRelationId', label: '套期关系' },
  { key: 'settleCycleId', label: '结算周期' },
  { key: 'exposureId', label: '实货敞口' }
];
/** 关联键跳转目标：仅存在已注册详情路由的键返回路径，其余 null（勿臆造路由）。 */
function relatedDocTarget(key, id) {
  return key === 'hedgePlanId' ? '/plan/' + encodeURIComponent(id) : null;
}
/** 关联单据单元格 HTML：有值键逐个展示（跳转键为锚标 data-voucher-jump），全空显「—」。 */
function relatedDocHTML(v) {
  const parts = RELATED_DOC_DEFS.map((d) => {
    const id = v[d.key];
    if (id == null) return '';
    const to = relatedDocTarget(d.key, id);
    return to
      ? '<a class="hg-doclink" data-voucher-related="' + d.key + '" data-voucher-jump="' + F.esc(to) + '">'
        + F.esc(d.label) + ' #' + F.esc(String(id)) + '</a>'
      : '<span data-voucher-related="' + d.key + '">' + F.esc(d.label) + ' #' + F.esc(String(id)) + '</span>';
  }).filter(Boolean).join(' ');
  return parts || '—';
}
/** 再平衡四类触发（Q-8；AcctService.TRIGGER_*）。 */
const TRIGGERS = {
  EXPOSURE_DELTA: '实货敞口变动 > 20%（敞口侧）',
  RATIO_DEVIATION: '套期比率偏离 > 20%（工具侧）',
  PLAN_REVISED: '计划数量被修订/终止（敞口侧）',
  EXPOSURE_GONE: '敞口消失需终止关系（敞口侧）'
};
/** 双侧盈亏上报类型（ck_t_dual_pnl_report_type 三值）。 */
const REPORT_TYPES = { MONTHLY: '月度', QUARTERLY: '季度', ANNUAL: '年度' };
/** 套期工具类型。 */
const INSTRUMENT_TYPES = { FUTURES: '期货', OPTION: '期权' };

/* ------------------------------------------------------------------ *
 * 通用控件（fund.js / spot.js 同构）
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

function numInput(id, props) {
  return ui.el('input', Object.assign({ id: id, type: 'number', inputmode: 'numeric' }, props || {}));
}
function amtInput(id, props) {
  return ui.el('input', Object.assign({ id: id, type: 'text', inputmode: 'decimal', placeholder: '如 100000.00' }, props || {}));
}
function dateInput(id) { return ui.el('input', { id: id, type: 'date' }); }
/** 期间键（yyyy-MM）。 */
function periodInput(id) {
  return ui.el('input', { id: id, type: 'text', placeholder: 'yyyy-MM（如 2026-09）', inputmode: 'numeric' });
}
/** 子账户下拉（会话可见集；spot.js 同构）。 */
function subAccountSelect(id, required) {
  const sel = ui.el('select', { id: id, name: 'subAccountId', required: required ? true : false });
  sel.appendChild(ui.el('option', { value: '', text: required ? '请选择子账户' : '（不指定）' }));
  (HG.session.state.subAccounts || []).forEach((s) => {
    sel.appendChild(ui.el('option', { value: s.id, text: (s.code ? s.code + ' ' : '') + (s.name || ('#' + s.id)) }));
  });
  const cur = HG.session.currentId();
  if (cur !== null && cur !== undefined) sel.value = String(cur);
  return sel;
}
/** 期间键形态预检（yyyy-MM；与服务端 requirePeriodKey 同形态）。 */
function validPeriodKey(v) { return /^\d{4}-\d{2}$/.test(String(v || '').trim()); }
/** 金额/数量只读展示（字符串化十进制，禁 Number 运算——非十进制串原样）。 */
function showAmt(v) { return F.esc(F.decimal(v) || '—'); }
/** 值或「—」。 */
function dash(v) { return F.esc(v === null || v === undefined || v === '' ? '—' : String(v)); }
/** Y/N 徽标。 */
function ynHTML(v) {
  if (v === 'Y' || v === true) return '<span class="hg-badge hg-badge--active" data-yn="Y">Y</span>';
  if (v === 'N' || v === false) return '<span class="hg-badge hg-badge--draft" data-yn="N">N</span>';
  return '—';
}
/** 「待外部复核」标注徽标（R-ACCT-10：不得被移除，恒显）。 */
function extReviewHTML(flag) {
  if (flag !== 'Y') return '';
  return '<span class="hg-badge hg-badge--frozen" data-external-review="Y">待外部复核</span>';
}

/* ------------------------------------------------------------------ *
 * Tab 1：套保效果（FR-ACCT-5/11；R-ACCT-4 同屏双栏 + price_as_of 三态 + UNATTRIBUTED）
 * ------------------------------------------------------------------ */
/** 价格态徽标节点（真实 DOM 节点——锚标可被 DOM 走查；表格内徽标走 tableHTML 字符串形态）。 */
function stateBadge(kind, cls, text) {
  return ui.el('span', { class: 'hg-badge hg-badge--' + cls, 'data-spot-price-state': kind, text: text });
}
/** 实货侧栏（R-ACCT-4：栏位恒在；缺价时值为 — + 标注，不省略）。 */
function spotColumnNode(row) {
  const card = ui.el('div', { class: 'hg-card', 'data-spot-column': '1' });
  card.appendChild(ui.el('h4', { text: '实货盈亏（被套期项目）' }));
  card.appendChild(ui.el('p', { html: '已实现：<strong class="hg-num">' + showAmt(row.spotPnlRealized) + '</strong>' }));
  card.appendChild(ui.el('p', { html: '未实现：<strong class="hg-num">' + showAmt(row.spotPnlUnrealized) + '</strong>' }));
  card.appendChild(ui.el('p', { html: '合计：<strong class="hg-num">' + showAmt(row.spotPnlTotal) + '</strong>' }));
  const p = ui.el('p');
  if (row.spotPriceMissing === 'Y') {
    p.appendChild(stateBadge('MISSING', 'frozen', '无法计算'));
    p.appendChild(ui.el('div', { class: 'hg-field__help',
      text: '缺失原因：' + (row.missingDesc || '实货盈亏无法计算（无任何已录入现货价，Q-12/R-ACCT-4）') }));
  } else if (row.nonCurrentPriceFlag === 'Y') {
    p.appendChild(stateBadge('NON_CURRENT', 'frozen', '非当日价'));
    p.appendChild(ui.el('div', { class: 'hg-field__help',
      text: '价格时点 price_as_of=' + (row.priceAsOf || '—') + '（沿用最近一期，Q-12/R-ACCT-9 标注）' }));
  } else {
    p.appendChild(stateBadge('CURRENT', 'active', '当日价'));
  }
  card.appendChild(p);
  return card;
}
/** 衍生品侧栏（已实现/未实现拆分；FR-ACCT-5 验收 2；R-ACCT-7 期权共用）。 */
function derivativeColumnNode(row) {
  const card = ui.el('div', { class: 'hg-card', 'data-derivative-column': '1' });
  card.appendChild(ui.el('h4', { text: '衍生品盈亏（套期工具，期货/期权）' }));
  card.appendChild(ui.el('p', { html: '已实现（平仓）：<strong class="hg-num">' + showAmt(row.derivativePnlRealized) + '</strong>' }));
  card.appendChild(ui.el('p', { html: '未实现（浮动，含期权）：<strong class="hg-num">' + showAmt(row.derivativePnlUnrealized) + '</strong>' }));
  card.appendChild(ui.el('p', { html: '合计：<strong class="hg-num">' + showAmt(row.derivativePnlTotal) + '</strong>' }));
  card.appendChild(ui.el('p', { html: '净效果（两侧合计）：<strong class="hg-num">' + showAmt(row.netEffect) + '</strong>' }));
  return card;
}
/** 单行双栏卡（汇总/明细共用；净效果 + 未对冲部分显式）。 */
function effectDuoNode(row, label) {
  const wrap = ui.el('div', { class: 'hg-effect-duo', style: 'display:flex;gap:16px;flex-wrap:wrap;align-items:stretch' });
  wrap.appendChild(ui.el('div', { style: 'flex:1 1 320px' }, [spotColumnNode(row)]));
  wrap.appendChild(ui.el('div', { style: 'flex:1 1 320px' }, [derivativeColumnNode(row)]));
  const box = ui.el('div', { class: 'hg-card' });
  box.appendChild(ui.el('h3', { class: 'hg-card__title', text: label }));
  if (row.unattributed) {
    const line = ui.el('div');
    line.appendChild(ui.el('span', { class: 'hg-badge hg-badge--frozen', 'data-unattributed': '1', text: '未关联计划（残余行 UNATTRIBUTED）' }));
    line.appendChild(ui.el('span', { class: 'hg-field__help', text: '未关联套保计划的品种级残余盈亏——显式呈现，参与 Σ明细 = 汇总勾稽（TE-T080-001）' }));
    box.appendChild(line);
  }
  box.appendChild(wrap);
  const extra = ui.el('p', { class: 'hg-muted' });
  extra.textContent = '未完全对冲部分：数量 ' + (row.unhedgedQty == null ? '—' : F.qty(row.unhedgedQty))
    + '；金额 ' + (row.unhedgedAmount == null ? '—' : F.money(row.unhedgedAmount))
    + '；实际套保比率 ' + (row.hedgedRatioActual == null ? '—' : row.hedgedRatioActual)
    + (row.calcTime ? '；计算时间 ' + F.dateTime(row.calcTime) : '');
  box.appendChild(extra);
  return box;
}

function effectTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '套保效果综合评估（FR-ACCT-5，同屏双栏）' }));
  card.appendChild(ui.banner(
    '<strong>两侧盈亏同时呈现（R-ACCT-4，17 号文）</strong>：实货盈亏与衍生品盈亏同屏双栏、成对恒在——'
      + '实货侧缺价时<strong>保留栏位</strong>并标注「无法计算 + 缺失原因」，不得省略栏位、不得只报衍生品盈亏。'
      + '价格三态（Q-12/R-ACCT-9）：当日价 / 非当日价（必带 price_as_of）/ 无法计算。'
      + '<strong>汇总 = Σ 明细</strong>（含 UNATTRIBUTED 残余行），同屏可勾稽（FR-ACCT-5 验收 4）。'
      + '衍生品盈亏按已实现（平仓）/未实现（浮动）拆分（期权同等纳入，R-ACCT-7）。'
      + '本页为服务端同源产出（R-ACCT-12 口径单一），前端不做任何计算。'
      + extReviewHTML('Y') + ' 本页数据属套期会计文档，一律「待外部复核」（R-ACCT-10）。',
    'warn'
  ));
  const sel = subAccountSelect('ac-e-sub', true);
  const pk = periodInput('ac-e-period');
  const qBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询效果' });
  const cBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '计算套保效果' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-e-sub', label: '子账户（必填）', required: true, control: sel }),
    ui.field({ id: 'ac-e-period', label: '会计期间（必填，yyyy-MM）', required: true, control: pk }),
    qBtn, cBtn
  ]));
  const body = ui.el('div');
  card.appendChild(body);
  container.appendChild(card);

  async function renderView(data) {
    ui.clear(body);
    const sumNode = effectDuoNode(data.summary || { spotPriceMissing: null, nonCurrentPriceFlag: null },
      '汇总（子账户级）——须等于 Σ 明细（含残余行），同屏勾稽（FR-ACCT-5 验收 4）');
    body.appendChild(sumNode);
    const details = data.details || [];
    if (!details.length) {
      body.appendChild(ui.el('div', { class: 'hg-state' }, [
        ui.el('div', { class: 'hg-state__title', text: '暂无明细' }),
        ui.el('div', { text: '该子账户该期间无逐敞口效果明细（未计算或无 BOUND 敞口）——明细为空属空态，非错误。' })
      ]));
      return;
    }
    body.appendChild(ui.el('h4', { text: '逐敞口明细（' + details.length + ' 行；UNATTRIBUTED 残余行显式呈现）' }));
    body.appendChild(ui.el('div', { html: ui.tableHTML(
      [
        { title: '行类型', render: (r) => (r.unattributed
          ? '<span class="hg-badge hg-badge--frozen" data-unattributed="1">UNATTRIBUTED 残余</span>'
          : F.esc(r.hedgePlanId == null ? '汇总' : '明细（计划 #' + r.hedgePlanId + '）')) },
        { title: '敞口', num: true, render: (r) => dash(r.exposureId) },
        { title: '实货·已实现', num: true, render: (r) => showAmt(r.spotPnlRealized) },
        { title: '实货·未实现', num: true, render: (r) => showAmt(r.spotPnlUnrealized) },
        { title: '实货·合计', num: true, render: (r) => showAmt(r.spotPnlTotal) },
        { title: '实货价格态', render: (r) => (r.spotPriceMissing === 'Y'
          ? '<span class="hg-badge hg-badge--frozen" data-spot-price-state="MISSING">无法计算</span> ' + F.esc(r.missingDesc || '')
          : (r.nonCurrentPriceFlag === 'Y'
            ? '<span class="hg-badge hg-badge--frozen" data-spot-price-state="NON_CURRENT">非当日价</span> price_as_of=' + F.esc(r.priceAsOf || '—')
            : '<span class="hg-badge hg-badge--active" data-spot-price-state="CURRENT">当日价</span>')) },
        { title: '衍生品·已实现', num: true, render: (r) => showAmt(r.derivativePnlRealized) },
        { title: '衍生品·未实现', num: true, render: (r) => showAmt(r.derivativePnlUnrealized) },
        { title: '衍生品·合计', num: true, render: (r) => showAmt(r.derivativePnlTotal) },
        { title: '净效果', num: true, render: (r) => showAmt(r.netEffect) },
        { title: '未对冲数量', num: true, render: (r) => showAmt(r.unhedgedQty) },
        { title: '未对冲金额', num: true, render: (r) => showAmt(r.unhedgedAmount) }
      ],
      details
    ) }));
    if (details.some((r) => r.unattributed)) {
      const ub = ui.el('div', { class: 'hg-banner hg-banner--warn' });
      ub.appendChild(ui.el('span', { class: 'hg-badge hg-badge--frozen', 'data-unattributed': '1',
        text: 'UNATTRIBUTED 残余行 ' + details.filter((r) => r.unattributed).length + ' 行——显式呈现，参与 Σ明细 = 汇总勾稽（TE-T080-001）' }));
      body.appendChild(ub);
    }
  }

  async function query() {
    if (!sel.value) { ui.toast('子账户必填（效果页按子账户取数）', 'error'); return; }
    if (!validPeriodKey(pk.value)) { ui.toast('会计期间须为 yyyy-MM 形态（如 2026-09）', 'error'); return; }
    ui.clear(body); ui.renderLoading(body, '正在查询套保效果…');
    try {
      const data = await HG.api.get(A.EFFECT, { subAccountId: sel.value, periodKey: pk.value.trim() });
      await renderView(data);
    } catch (err) { ui.renderError(body, err, () => query()); }
  }

  async function calc() {
    if (!sel.value) { ui.toast('子账户必填', 'error'); return; }
    if (!validPeriodKey(pk.value)) { ui.toast('会计期间须为 yyyy-MM 形态（如 2026-09）', 'error'); return; }
    const go = await ui.confirm('确认计算套保效果（子账户 #' + F.esc(sel.value) + '，期间 ' + F.esc(pk.value.trim()) + '）？'
      + '同一子账户与期间重复计算按 uk_hedge_effect_key 覆盖更新（R-ACCT-12 口径单一）。', { title: '计算套保效果' });
    if (!go) return;
    ui.clear(body); ui.renderLoading(body, '正在计算套保效果…');
    try {
      const data = await HG.api.post(A.EFFECT, {
        subAccountId: Number(sel.value), periodKey: pk.value.trim()
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      await renderView(data);
      ui.toast('套保效果已计算', 'ok');
    } catch (err) { ui.renderError(body, err, () => calc()); }
  }
  qBtn.addEventListener('click', () => query());
  cBtn.addEventListener('click', () => calc());
}

/* ------------------------------------------------------------------ *
 * Tab 2：双侧盈亏上报（FR-ACCT-10；R-ACCT-4 两栏恒在 + 留痕三件）
 * ------------------------------------------------------------------ */
function dualPnlTab(container) {
  const subCard = ui.el('section', { class: 'hg-card' });
  subCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '提交双侧盈亏上报（FR-ACCT-10）' }));
  subCard.appendChild(ui.banner(
    '<strong>两栏恒在（R-ACCT-4，17 号文）</strong>：实货盈亏与衍生品盈亏必须同时上报，禁止只报衍生品盈亏；'
      + '实货缺价时<strong>保留实货栏</strong>并标注缺失原因（不得以「缺数据」为由只报一侧）。'
      + '上报口径与套保效果页一致（R-ACCT-12；衍生品取效果汇总行快照，须先经「计算套保效果」）。'
      + '子账户留空 = <strong>汇总上报</strong>（覆盖全部限写子账户，缺价子账户逐个标注见数据完整性说明）。'
      + '上报动作（推送监管）不在系统边界。', 'warn'
  ));
  const sSub = subAccountSelect('ac-dp-sub', false);
  const sPeriod = periodInput('ac-dp-period');
  const sType = ui.el('select', { id: 'ac-dp-type' });
  Object.keys(REPORT_TYPES).forEach((k) => sType.appendChild(ui.el('option', { value: k, text: REPORT_TYPES[k] })));
  const sBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '提交上报' });
  subCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-dp-sub', label: '子账户（留空 = 汇总上报）', control: sSub }),
    ui.field({ id: 'ac-dp-period', label: '报告期间（必填，yyyy-MM）', required: true, control: sPeriod }),
    ui.field({ id: 'ac-dp-type', label: '报告类型（必填）', required: true, control: sType }),
    sBtn
  ]));
  const sBody = ui.el('div');
  subCard.appendChild(sBody);
  container.appendChild(subCard);

  sBtn.addEventListener('click', async () => {
    if (!validPeriodKey(sPeriod.value)) { ui.toast('报告期间须为 yyyy-MM 形态（如 2026-09）', 'error'); return; }
    if (!sType.value) { ui.toast('报告类型必填（MONTHLY/QUARTERLY/ANNUAL）', 'error'); return; }
    const go = await ui.confirm('确认提交' + F.esc(REPORT_TYPES[sType.value]) + '双侧盈亏上报（期间 ' + F.esc(sPeriod.value.trim())
      + (sSub.value ? '，子账户 #' + F.esc(sSub.value) : '，汇总上报') + '）？两栏恒在：实货缺失将以「无法计算 + 缺失原因」保留栏位。',
      { title: '提交双侧盈亏上报' });
    if (!go) return;
    ui.clear(sBody); ui.renderLoading(sBody, '正在提交双侧盈亏上报…');
    try {
      const r = await HG.api.post(A.DUAL_PNL_REPORT, {
        subAccountId: sSub.value ? Number(sSub.value) : null,
        reportPeriod: sPeriod.value.trim(), reportType: sType.value
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(sBody);
      sBody.appendChild(ui.banner('上报已提交：上报编号 <code>' + F.esc(r.reportNo || '') + '</code>'
        + '（留痕：上报人 #' + dash(r.submittedBy) + ' / 时间 ' + F.esc(F.dateTime(r.submittedTime)) + ' / 期间 '
        + F.esc(r.reportPeriod || '—') + '）。' + F.esc(r.dataCompletenessNote || ''), 'ok'));
      if (r.spotPnlValue == null) {
        sBody.appendChild(ui.banner('<strong>实货栏保留（值空）</strong>：' + F.esc(r.spotPnlMissingReason || '实货盈亏无法计算')
          + '——未省略栏位（FR-ACCT-10 验收 2）。', 'warn'));
      }
      sBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { title: '实货盈亏（恒在）', num: true, render: () => (r.spotPnlValue == null ? '—' : F.esc(F.money(r.spotPnlValue))) },
          { title: '实货缺失原因', render: () => F.esc(r.spotPnlMissingReason || '—') },
          { title: '衍生品·已实现', num: true, render: () => showAmt(r.derivativePnlRealized) },
          { title: '衍生品·未实现', num: true, render: () => showAmt(r.derivativePnlUnrealized) },
          { title: '衍生品合计', num: true, render: () => showAmt(r.derivativePnlValue) },
          { title: '净效果', num: true, render: () => showAmt(r.netEffect) }
        ],
        [{}]
      ) }));
    } catch (err) { ui.renderError(sBody, err, () => sBtn.click()); }
  });

  const listCard = ui.el('section', { class: 'hg-card' });
  listCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '上报记录查询（FR-ACCT-10 验收 1/4）' }));
  const qPeriod = periodInput('ac-dq-period');
  const qType = ui.el('select', { id: 'ac-dq-type' });
  qType.appendChild(ui.el('option', { value: '', text: '全部类型（三类型聚合）' }));
  Object.keys(REPORT_TYPES).forEach((k) => qType.appendChild(ui.el('option', { value: k, text: REPORT_TYPES[k] })));
  const qSub = numInput('ac-dq-sub', { min: '1', placeholder: '可空' });
  const qBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询上报记录' });
  listCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-dq-period', label: '报告期间（必填）', required: true, control: qPeriod }),
    ui.field({ id: 'ac-dq-type', label: '报告类型', control: qType }),
    ui.field({ id: 'ac-dq-sub', label: '子账户', control: qSub }),
    qBtn
  ]));
  const qBody = ui.el('div');
  listCard.appendChild(qBody);
  container.appendChild(listCard);

  qBtn.addEventListener('click', async () => {
    if (!validPeriodKey(qPeriod.value)) { ui.toast('报告期间须为 yyyy-MM 形态（如 2026-09）', 'error'); return; }
    const q = { reportPeriod: qPeriod.value.trim() };
    if (qType.value) q.reportType = qType.value;
    if (qSub.value) q.subAccountId = qSub.value;
    ui.clear(qBody); ui.renderLoading(qBody, '正在查询上报记录…');
    try {
      const items = await HG.api.get(A.DUAL_PNL_REPORT, q);
      ui.clear(qBody);
      if (!items || !items.length) { ui.renderEmpty(qBody, '该期间无上报记录。'); return; }
      qBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'reportNo', title: '上报编号' },
          { title: '子账户', num: true, render: (r) => dash(r.subAccountId) },
          { title: '类型', render: (r) => F.esc(REPORT_TYPES[r.reportType] || r.reportType || '—') },
          { title: '实货栏（恒在）', render: (r) => '<span data-spot-column="1">'
            + (r.spotPnlValue == null
              ? '<span class="hg-badge hg-badge--frozen" data-spot-price-state="MISSING">无法计算</span> ' + F.esc(r.spotPnlMissingReason || '')
              : F.esc(F.money(r.spotPnlValue)))
            + '</span>' },
          { title: '衍生品·已实现', num: true, render: (r) => showAmt(r.derivativePnlRealized) },
          { title: '衍生品·未实现', num: true, render: (r) => showAmt(r.derivativePnlUnrealized) },
          { title: '衍生品合计', num: true, render: (r) => showAmt(r.derivativePnlValue) },
          { title: '净效果', num: true, render: (r) => showAmt(r.netEffect) },
          { title: '数据完整性说明', render: (r) => F.esc(r.dataCompletenessNote || '—') },
          { title: '上报人', num: true, render: (r) => dash(r.submittedBy) },
          { title: '上报时间', render: (r) => F.esc(F.dateTime(r.submittedTime)) },
          { title: '期间', render: (r) => F.esc(r.reportPeriodSubmitted || r.reportPeriod || '—') },
          { title: '外部复核', render: (r) => extReviewHTML(r.pendingExternalReview) || '—' }
        ],
        items
      ) }));
      if (items.some((r) => r.spotPnlValue == null)) {
        qBody.appendChild(ui.banner('存在实货侧「无法计算」的上报记录——<strong>实货栏位保留未省略</strong>（缺失原因见行内），'
          + '不得以缺数据为由只报衍生品侧（R-ACCT-4）。', 'warn'));
      }
    } catch (err) { ui.renderError(qBody, err, () => qBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 3：套期关系（FR-ACCT-1；R-ACCT-1 三要素门禁 + R-ACCT-10 待外部复核）
 * ------------------------------------------------------------------ */
function relationTab(container) {
  const genCard = ui.el('section', { class: 'hg-card' });
  genCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '生成套期关系（FR-ACCT-1，正式指定）' }));
  genCard.appendChild(ui.banner(
    '<strong>三要素门禁（R-ACCT-1，CAS 24 红线）</strong>：套期关系文档必须含 ①正式指定 ②有效性评估方法 '
      + '③再平衡机制；三要素承载于计划的当前版本书面文件（PLAN 模块）——缺任一要素服务端以 '
      + '<code>10012</code> 阻断并<strong>并列列出缺失项</strong>（原文展示于结果区，不得吞成通用错误）。'
      + '文档须标注' + extReviewHTML('Y') + '（R-ACCT-10）；绑定计划版本，计划变更后重新生成并保留历史版本。',
    'warn'
  ));
  const gPlan = numInput('ac-r-plan', { min: '1', placeholder: '套保计划 ID（须已生成书面文件）' });
  const gExp = numInput('ac-r-exp', { min: '1', placeholder: '实货敞口 ID' });
  const gType = ui.el('select', { id: 'ac-r-type' });
  Object.keys(INSTRUMENT_TYPES).forEach((k) => gType.appendChild(ui.el('option', { value: k, text: INSTRUMENT_TYPES[k] })));
  const gDesc = ui.el('input', { id: 'ac-r-desc', placeholder: '套期工具描述（合约与持仓标识）' });
  const gBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '生成套期关系' });
  genCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-r-plan', label: '套保计划（必填）', required: true, control: gPlan }),
    ui.field({ id: 'ac-r-exp', label: '被套期项目敞口（必填）', required: true, control: gExp }),
    ui.field({ id: 'ac-r-type', label: '套期工具类型', control: gType }),
    ui.field({ id: 'ac-r-desc', label: '套期工具描述', control: gDesc }),
    gBtn
  ]));
  const gBody = ui.el('div');
  genCard.appendChild(gBody);
  container.appendChild(genCard);

  gBtn.addEventListener('click', async () => {
    if (!gPlan.value || !gExp.value) { ui.toast('planId / exposureId 必填', 'error'); return; }
    const go = await ui.confirm('确认生成套期关系（计划 #' + F.esc(gPlan.value) + '，敞口 #' + F.esc(gExp.value)
      + '，工具 ' + F.esc(INSTRUMENT_TYPES[gType.value] || gType.value) + '）？'
      + '三要素不齐备将被 10012 阻断并列出缺失项。', { title: '生成套期关系' });
    if (!go) return;
    ui.clear(gBody); ui.renderLoading(gBody, '正在生成套期关系…');
    try {
      const r = await HG.api.post(A.HEDGE_RELATION, {
        planId: Number(gPlan.value), exposureId: Number(gExp.value),
        instrumentType: gType.value, instrumentDesc: gDesc.value.trim() || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(gBody);
      gBody.appendChild(ui.banner('套期关系已生成：关系编号 <code>' + F.esc(r.relationNo || '') + '</code>'
        + '（ID #' + dash(r.id) + '，子账户 #' + dash(r.subAccountId) + '，状态 ' + F.esc(REL_STATUS[r.status] || r.status || '—') + '）。'
        + '文档' + extReviewHTML(r.pendingExternalReview) + '——经计划书面文件承载三要素（FR-ACCT-1）。', 'ok'));
      loadList(1);
    } catch (err) {
      // 三要素门禁 10012：并列缺失项**原文展示**（经 errmsg.render 透出后端 message，不吞成通用错误）
      ui.renderError(gBody, err, () => gBtn.click());
      if (err && err.code === 10012) {
        gBody.appendChild(ui.el('div', { class: 'hg-state', 'data-gate-10012': '1' }, [
          ui.el('div', { class: 'hg-state__title', text: '三要素门禁未通过（10012）——缺失项见上方原文' }),
          ui.el('div', { text: '须先在 PLAN 模块补齐计划书面文件（含有效性评估方法与再平衡机制）后重试；本页不做要素补齐臆造。' })
        ]));
      }
    }
  });

  const listCard = ui.el('section', { class: 'hg-card' });
  listCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '套期关系查询（FR-ACCT-1）' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE };
  const lSub = numInput('ac-rl-sub', { min: '1', placeholder: '可空' });
  const lStatus = ui.el('select', { id: 'ac-rl-status' });
  lStatus.appendChild(ui.el('option', { value: '', text: '全部状态' }));
  Object.keys(REL_STATUS).forEach((k) => lStatus.appendChild(ui.el('option', { value: k, text: REL_STATUS[k] })));
  const lBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询套期关系' });
  listCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-rl-sub', label: '子账户', control: lSub }),
    ui.field({ id: 'ac-rl-status', label: '状态', control: lStatus }),
    lBtn
  ]));
  const lBody = ui.el('div');
  const lPager = ui.el('div', { class: 'hg-pager' });
  listCard.appendChild(lBody);
  listCard.appendChild(lPager);
  container.appendChild(listCard);

  async function loadList(page) {
    state.page = page || 1;
    const q = { page: state.page, size: state.size };
    if (lSub.value) q.subAccountId = lSub.value;
    if (lStatus.value) q.status = lStatus.value;
    ui.clear(lBody); ui.renderLoading(lBody, '正在查询套期关系…');
    try {
      const data = await HG.api.get(A.HEDGE_RELATION, q);
      attachPager(lPager, { total: data.total, page: data.page, size: data.size }, loadList);
      ui.clear(lBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(lBody, '该筛选条件下无套期关系。'); return; }
      lBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'relationNo', title: '关系编号' },
          { key: 'subAccountId', title: '子账户', num: true },
          { title: '套保计划', num: true, render: (r) => dash(r.hedgePlanId) },
          { title: '被套期敞口', num: true, render: (r) => dash(r.hedgedItemExposureId) },
          { title: '套期工具', render: (r) => F.esc((INSTRUMENT_TYPES[r.hedgingInstrumentType] || r.hedgingInstrumentType || '—')
            + (r.hedgingInstrumentDesc ? '（' + r.hedgingInstrumentDesc + '）' : '')) },
          { title: '状态', render: (r) => relationStatusHTML(r.status) },
          { title: '书面文件', num: true, render: (r) => dash(r.hedgeDocId) },
          { title: '指定时间', render: (r) => F.esc(F.dateTime(r.designatedTime)) },
          { title: '外部复核', render: (r) => extReviewHTML(r.pendingExternalReview) || '—' }
        ],
        data.items
      ) }));
    } catch (err) { ui.renderError(lBody, err, () => loadList(state.page)); }
  }
  lBtn.addEventListener('click', () => loadList(1));
}

/* ------------------------------------------------------------------ *
 * Tab 4：有效性评估（FR-ACCT-2/4；三项定性观察点 + 量化监测仅提示）
 * ------------------------------------------------------------------ */
function effectivenessTab(container) {
  const subCard = ui.el('section', { class: 'hg-card' });
  subCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '提交有效性评估（FR-ACCT-2/4，三项定性观察点）' }));
  subCard.appendChild(ui.banner(
    '<strong>定性有效性（R-ACCT-2，CAS 24）</strong>：判定 = 三项观察点布尔合取——①品种相关性（同品种强 / 近相关须书面说明经济关系）'
      + '②方向一致性 ③期限对应性（敞口预计实现日 ±3 个月）。'
      + '<strong>量化监测仅提示复核，不参与有效性判定</strong>（FR-ACCT-4 验收 2/5；偏离 1 超 ±50% 复核提示）——'
      + '本页无以监测值改写结论的入口，亦无 80%–125% 判据。结论无效时标注需终止或再平衡，不自动修改会计处理。'
      + '评估记录不得事后修改（更正须新增记录并留痕）；每期至少评估一次。', 'warn'
  ));
  const sRel = numInput('ac-ev-rel', { min: '1', placeholder: '套期关系 ID' });
  const sPeriod = periodInput('ac-ev-period');
  const sLink = ui.el('select', { id: 'ac-ev-link' });
  sLink.appendChild(ui.el('option', { value: 'Y', text: 'Y（同品种，强相关）' }));
  sLink.appendChild(ui.el('option', { value: 'N', text: 'N（近相关，须书面说明）' }));
  const sNote = ui.el('input', { id: 'ac-ev-note', placeholder: '书面经济关系说明（近相关必填，Q-7）' });
  const sDir = ui.el('select', { id: 'ac-ev-dir' });
  sDir.appendChild(ui.el('option', { value: 'Y', text: 'Y（方向一致）' }));
  sDir.appendChild(ui.el('option', { value: 'N', text: 'N（方向不一致）' }));
  const sTerm = ui.el('select', { id: 'ac-ev-term' });
  sTerm.appendChild(ui.el('option', { value: 'Y', text: 'Y（期限对应，±3 个月内）' }));
  sTerm.appendChild(ui.el('option', { value: 'N', text: 'N（期限不对应）' }));
  const sExpDate = dateInput('ac-ev-expdate');
  const sMonitor = amtInput('ac-ev-monitor', { placeholder: '可空——仅监测不判定' });
  const sBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '提交评估' });
  subCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-ev-rel', label: '套期关系（必填）', required: true, control: sRel }),
    ui.field({ id: 'ac-ev-period', label: '会计期间（必填，yyyy-MM）', required: true, control: sPeriod }),
    ui.field({ id: 'ac-ev-link', label: '观察点①品种相关性', required: true, control: sLink }),
    ui.field({ id: 'ac-ev-note', label: '书面经济关系说明', control: sNote })
  ]));
  subCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-ev-dir', label: '观察点②方向一致性', required: true, control: sDir }),
    ui.field({ id: 'ac-ev-term', label: '观察点③期限对应性', required: true, control: sTerm }),
    ui.field({ id: 'ac-ev-expdate', label: '敞口预计实现日（期限判定基准）', control: sExpDate }),
    ui.field({ id: 'ac-ev-monitor', label: '量化监测偏离值（可空，仅监测）', control: sMonitor }),
    sBtn
  ]));
  const sBody = ui.el('div');
  subCard.appendChild(sBody);
  container.appendChild(subCard);

  sBtn.addEventListener('click', async () => {
    if (!sRel.value) { ui.toast('hedgeRelationId 必填', 'error'); return; }
    if (!validPeriodKey(sPeriod.value)) { ui.toast('会计期间须为 yyyy-MM 形态（如 2026-09）', 'error'); return; }
    if (sLink.value === 'N' && !sNote.value.trim()) {
      ui.toast('近相关（品种相关性=N）须填写书面经济关系说明（Q-7）', 'error'); return;
    }
    const go = await ui.confirm('确认提交评估（关系 #' + F.esc(sRel.value) + '，期间 ' + F.esc(sPeriod.value.trim()) + '）？'
      + '结论 = 三项观察点布尔合取；量化监测仅提示不判定。', { title: '提交有效性评估' });
    if (!go) return;
    ui.clear(sBody); ui.renderLoading(sBody, '正在提交评估…');
    try {
      const r = await HG.api.post(A.EFFECTIVENESS, {
        hedgeRelationId: Number(sRel.value), periodKey: sPeriod.value.trim(),
        observerLinkage: sLink.value, linkageNote: sLink.value === 'N' ? sNote.value.trim() : null,
        observerDirection: sDir.value, observerTerm: sTerm.value,
        exposureExpectedDate: sExpDate.value || null,
        monitorDeviationRatio: sMonitor.value.trim() || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(sBody);
      sBody.appendChild(ui.banner('评估已提交（ID #' + dash(r.id) + '，期间 ' + F.esc(r.periodKey || '—') + '）：结论 '
        + conclusionHTML(r.conclusion) + (r.conclusion !== 'EFFECTIVE' ? '——需终止或再平衡，不得自动修改会计处理。' : '。')
        + '监测复核' + ynHTML(r.monitorReviewFlag) + '（仅提示，不影响结论）。', r.conclusion === 'EFFECTIVE' ? 'ok' : 'warn'));
    } catch (err) { ui.renderError(sBody, err, () => sBtn.click()); }
  });

  const listCard = ui.el('section', { class: 'hg-card' });
  listCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '评估记录查询（FR-ACCT-2/4）' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE };
  const qSub = numInput('ac-evq-sub', { min: '1', placeholder: '可空' });
  const qPeriod = periodInput('ac-evq-period');
  const qConc = ui.el('select', { id: 'ac-evq-conc' });
  qConc.appendChild(ui.el('option', { value: '', text: '全部结论' }));
  Object.keys(CONCLUSIONS).forEach((k) => qConc.appendChild(ui.el('option', { value: k, text: CONCLUSIONS[k] })));
  const qMonitor = ui.el('input', { id: 'ac-evq-monitor', type: 'checkbox' });
  const qBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询评估记录' });
  listCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-evq-sub', label: '子账户', control: qSub }),
    ui.field({ id: 'ac-evq-period', label: '会计期间', control: qPeriod }),
    ui.field({ id: 'ac-evq-conc', label: '结论', control: qConc }),
    ui.field({ id: 'ac-evq-monitor', label: '仅监测超阈值（CFG-07b 复核提示清单）', control: qMonitor }),
    qBtn
  ]));
  const qBody = ui.el('div');
  const qPager = ui.el('div', { class: 'hg-pager' });
  listCard.appendChild(qBody);
  listCard.appendChild(qPager);
  container.appendChild(listCard);

  async function loadList(page) {
    state.page = page || 1;
    const q = { page: state.page, size: state.size };
    if (qSub.value) q.subAccountId = qSub.value;
    if (validPeriodKey(qPeriod.value)) q.periodKey = qPeriod.value.trim();
    if (qConc.value) q.conclusion = qConc.value;
    if (qMonitor.checked) q.monitorOnly = 'true';
    ui.clear(qBody); ui.renderLoading(qBody, '正在查询评估记录…');
    try {
      const data = await HG.api.get(A.EFFECTIVENESS, q);
      attachPager(qPager, { total: data.total, page: data.page, size: data.size }, loadList);
      ui.clear(qBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(qBody, '该筛选条件下无评估记录。'); return; }
      qBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'id', title: 'ID', num: true },
          { key: 'subAccountId', title: '子账户', num: true },
          { title: '套期关系', num: true, render: (r) => dash(r.hedgeRelationId) },
          { key: 'periodKey', title: '期间' },
          { title: '①品种相关性', render: (r) => ynHTML(r.observerLinkage) },
          { title: '②方向一致性', render: (r) => ynHTML(r.observerDirection) },
          { title: '③期限对应性', render: (r) => ynHTML(r.observerTerm) },
          { title: '经济关系说明', render: (r) => F.esc(r.linkageNote || '—') },
          { title: '监测偏离', num: true, render: (r) => showAmt(r.monitorDeviationRatio) },
          { title: '监测复核', render: (r) => ynHTML(r.monitorReviewFlag) },
          { title: '结论', render: (r) => conclusionHTML(r.conclusion) },
          { title: '待办', render: (r) => F.esc(r.actionNote || '—') },
          { title: '评估人', num: true, render: (r) => dash(r.assessorId) },
          { title: '评估时间', render: (r) => F.esc(F.dateTime(r.assessTime)) },
          { title: '外部复核', render: (r) => extReviewHTML(r.pendingExternalReview) || '—' }
        ],
        data.items
      ) }));
      qBody.appendChild(ui.el('p', { class: 'hg-muted', text: '监测偏离值仅用于提示复核，不参与有效性判定（Q-7 / FR-ACCT-4 验收 2）；无 80%–125% 判据。' }));
    } catch (err) { ui.renderError(qBody, err, () => loadList(state.page)); }
  }
  qBtn.addEventListener('click', () => loadList(1));

  const warnCard = ui.el('section', { class: 'hg-card' });
  warnCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '未评估提醒（FR-ACCT-2 验收 4）' }));
  const wPeriod = periodInput('ac-evw-period');
  const wBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '查询未评估清单' });
  warnCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-evw-period', label: '会计期间（必填，yyyy-MM）', required: true, control: wPeriod }),
    wBtn
  ]));
  const wBody = ui.el('div');
  warnCard.appendChild(wBody);
  container.appendChild(warnCard);

  wBtn.addEventListener('click', async () => {
    if (!validPeriodKey(wPeriod.value)) { ui.toast('会计期间须为 yyyy-MM 形态（如 2026-09）', 'error'); return; }
    ui.clear(wBody); ui.renderLoading(wBody, '正在查询未评估套期关系…');
    try {
      const items = await HG.api.get(A.EFFECTIVENESS, { unassessedPeriod: wPeriod.value.trim() });
      ui.clear(wBody);
      if (!items || !items.length) {
        wBody.appendChild(ui.banner('该期间无未评估的套期关系（每期至少评估一次已满足）。', 'ok'));
        return;
      }
      wBody.appendChild(ui.banner('<strong>未评估提醒</strong>：以下 ' + items.length + ' 条套期关系在期间 '
        + F.esc(wPeriod.value.trim()) + ' 尚未评估——每会计期间至少评估一次（FR-ACCT-2）。', 'warn'));
      wBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { title: '关系编号', render: (r) => F.esc((r.relation && r.relation.relationNo) || '—') },
          { title: '子账户', num: true, render: (r) => dash(r.relation && r.relation.subAccountId) },
          { title: '套保计划', num: true, render: (r) => dash(r.relation && r.relation.hedgePlanId) },
          { title: '状态', render: (r) => relationStatusHTML(r.relation && r.relation.status) }
        ],
        items
      ) }));
    } catch (err) { ui.renderError(wBody, err, () => wBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 5：再平衡（FR-ACCT-3；四类触发 Q-8；偏离值服务端复核不采信客户端）
 * ------------------------------------------------------------------ */
function rebalanceTab(container) {
  const goCard = ui.el('section', { class: 'hg-card' });
  goCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '执行再平衡（FR-ACCT-3，四类触发）' }));
  goCard.appendChild(ui.banner(
    '<strong>触发条件（Q-8，满足任一即触发评估）</strong>：①实货敞口变动 &gt; 20%（敞口侧）②套期比率偏离 &gt; 20%（工具侧）'
      + '③计划数量被修订/终止 ④敞口消失需终止关系；敞口侧与工具侧必须各设一项。'
      + '<strong>偏离值由服务端按数据面复核，不采信客户端声明</strong>——本页偏离值仅只读展示（无输入项）；'
      + '触发条件不满足时服务端以 10012 阻断并返回<strong>当前偏离值</strong>（原文展示于结果区）。'
      + '再平衡必须重新生成书面文件与正式指定（不得只改数字）；记录不得事后修改；结论反馈 PLAN。', 'warn'
  ));
  const gRel = numInput('ac-rb-rel', { min: '1', placeholder: '套期关系 ID' });
  const gScen = ui.el('select', { id: 'ac-rb-scen' });
  Object.keys(TRIGGERS).forEach((k) => gScen.appendChild(ui.el('option', { value: k, text: TRIGGERS[k] })));
  const gReason = ui.el('input', { id: 'ac-rb-reason', placeholder: '触发原因描述（必填）' });
  const gRatio = amtInput('ac-rb-ratio', { placeholder: '调整后套保比率 ∈ (0,1]，如 0.85' });
  const gQty = amtInput('ac-rb-qty', { placeholder: '调整后数量（>0；敞口消失须为 0）' });
  const gFb = ui.el('select', { id: 'ac-rb-fb' });
  gFb.appendChild(ui.el('option', { value: 'N', text: 'N（无需反馈 PLAN）' }));
  gFb.appendChild(ui.el('option', { value: 'Y', text: 'Y（结论需反馈 PLAN 调整计划）' }));
  const gBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '执行再平衡' });
  goCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-rb-rel', label: '套期关系（必填）', required: true, control: gRel }),
    ui.field({ id: 'ac-rb-scen', label: '触发场景（四类，Q-8）', required: true, control: gScen }),
    ui.field({ id: 'ac-rb-reason', label: '触发原因（必填）', required: true, control: gReason })
  ]));
  goCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-rb-ratio', label: '调整后套保比率（必填）', required: true, control: gRatio }),
    ui.field({ id: 'ac-rb-qty', label: '调整后数量（必填）', required: true, control: gQty }),
    ui.field({ id: 'ac-rb-fb', label: '是否反馈 PLAN', control: gFb }),
    gBtn
  ]));
  const gBody = ui.el('div');
  goCard.appendChild(gBody);
  container.appendChild(goCard);

  gBtn.addEventListener('click', async () => {
    if (!gRel.value) { ui.toast('hedgeRelationId 必填', 'error'); return; }
    if (!gScen.value) { ui.toast('触发场景必填（四类之一）', 'error'); return; }
    if (!gReason.value.trim()) { ui.toast('触发原因必填（落再平衡记录留痕）', 'error'); return; }
    if (!gRatio.value.trim() || !gQty.value.trim()) { ui.toast('调整后比率与数量必填（敞口消失场景数量为 0）', 'error'); return; }
    const go = await ui.confirm('确认执行再平衡（关系 #' + F.esc(gRel.value) + '，场景 ' + F.esc(TRIGGERS[gScen.value]) + '）？'
      + '偏离值由服务端复核；条件不满足将被 10012 阻断并给出当前偏离值。', { title: '执行再平衡' });
    if (!go) return;
    ui.clear(gBody); ui.renderLoading(gBody, '正在执行再平衡…');
    try {
      const r = await HG.api.post(A.REBALANCE, {
        hedgeRelationId: Number(gRel.value), triggerScenario: gScen.value,
        triggerReason: gReason.value.trim(), ratioAfter: gRatio.value.trim(), qtyAfter: gQty.value.trim(),
        planFeedbackRequired: gFb.value
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(gBody);
      gBody.appendChild(ui.banner('再平衡完成：记录编号 <code>' + F.esc(r.rebalanceNo || '') + '</code>'
        + '（比率 ' + F.esc(r.ratioBefore == null ? '—' : String(r.ratioBefore)) + ' → ' + F.esc(r.ratioAfter == null ? '—' : String(r.ratioAfter))
        + '；数量 ' + F.esc(r.qtyBefore == null ? '—' : String(r.qtyBefore)) + ' → ' + F.esc(r.qtyAfter == null ? '—' : String(r.qtyAfter)) + '）。'
        + '书面文件与正式指定已重新生成（新文档 #' + dash(r.newHedgeDocId) + '，新关系 #' + dash(r.newHedgeRelationId) + '）——不得只改数字（R-ACCT-3）。', 'ok'));
      loadHistory();
    } catch (err) {
      // 触发条件不满足：10012 原文展示（含服务端当前偏离值）
      ui.renderError(gBody, err, () => gBtn.click());
      if (err && err.code === 10012) {
        gBody.appendChild(ui.el('div', { class: 'hg-state', 'data-gate-10012': '1' }, [
          ui.el('div', { class: 'hg-state__title', text: '触发条件不满足（10012）——当前偏离值见上方原文' }),
          ui.el('div', { text: '当前偏离值由服务端按数据面复核产出（不采信客户端声明）；未达阈值不得强行再平衡。' })
        ]));
      }
    }
  });

  const hisCard = ui.el('section', { class: 'hg-card' });
  hisCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '再平衡史查询（FR-ACCT-3）' }));
  const hRel = numInput('ac-rbh-rel', { min: '1', placeholder: '套期关系 ID（必填）' });
  const hBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询再平衡史' });
  hisCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-rbh-rel', label: '套期关系（必填）', required: true, control: hRel }),
    hBtn
  ]));
  const hBody = ui.el('div');
  hisCard.appendChild(hBody);
  container.appendChild(hisCard);

  async function loadHistory() {
    if (!hRel.value) { ui.toast('hedgeRelationId 必填（按关系取再平衡史）', 'error'); return; }
    ui.clear(hBody); ui.renderLoading(hBody, '正在查询再平衡史…');
    try {
      const items = await HG.api.get(A.REBALANCE, { hedgeRelationId: hRel.value });
      ui.clear(hBody);
      if (!items || !items.length) { ui.renderEmpty(hBody, '该套期关系无再平衡记录。'); return; }
      hBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'rebalanceNo', title: '记录编号' },
          { title: '触发类型', render: (r) => F.esc(TRIGGERS[r.triggerType] || r.triggerType || '—') },
          { title: '触发侧', render: (r) => F.esc(r.triggerSide === 'EXPOSURE_SIDE' ? '敞口侧' : (r.triggerSide === 'INSTRUMENT_SIDE' ? '工具侧' : (r.triggerSide || '—'))) },
          { key: 'triggerReason', title: '触发原因' },
          { title: '敞口变动（只读）', num: true, render: (r) => showAmt(r.triggerExposureDelta) },
          { title: '比率偏离（只读）', num: true, render: (r) => showAmt(r.triggerRatioDeviation) },
          { title: '阈值（只读）', num: true, render: (r) => showAmt(r.triggerThreshold) },
          { title: '比率调整', render: (r) => F.esc((r.ratioBefore == null ? '—' : String(r.ratioBefore)) + ' → ' + (r.ratioAfter == null ? '—' : String(r.ratioAfter))) },
          { title: '数量调整', render: (r) => F.esc((r.qtyBefore == null ? '—' : String(r.qtyBefore)) + ' → ' + (r.qtyAfter == null ? '—' : String(r.qtyAfter))) },
          { title: '新书面文件', num: true, render: (r) => dash(r.newHedgeDocId) },
          { title: '执行人', num: true, render: (r) => dash(r.executorId) },
          { title: '执行时间', render: (r) => F.esc(F.dateTime(r.rebalanceTime)) },
          { title: '反馈 PLAN', render: (r) => ynHTML(r.planFeedbackFlag) }
        ],
        items
      ) }));
      hBody.appendChild(ui.el('p', { class: 'hg-muted', text: '偏离值（敞口变动/比率偏离/阈值）为服务端复核产出，此处只读展示——前端不输入、不计算偏离值（R-ACCT-3）。' }));
    } catch (err) { ui.renderError(hBody, err, () => loadHistory()); }
  }
  hBtn.addEventListener('click', () => loadHistory());
}

/* ------------------------------------------------------------------ *
 * Tab 6：会计凭证（FR-ACCT-6/7/8/13；DRAFT_ONLY + 岗位分离 + 冲销双人复核）
 * ------------------------------------------------------------------ */
function voucherTab(container) {
  const draftCard = ui.el('section', { class: 'hg-card' });
  draftCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '生成凭证草稿（FR-ACCT-6）' }));
  draftCard.appendChild(ui.banner(
    '<strong>草稿模式（DRAFT_ONLY）——不做总账过账</strong>，草稿标注「待企业财务复核入账」（Q-9 / R-ACCT-11）。'
      + '凭证<strong>必须按子账户出账</strong>（子账户缺失拒绝生成，R-ACCT-5）；科目建议来自 CONFIG 科目字典'
      + '（本页不硬编码科目）；期间已关账（10016）须通过<strong>下期调整</strong>。'
      + '同一期间重复生成不产生重复凭证（幂等或明确提示）。', 'warn'
  ));
  const dSub = subAccountSelect('ac-v-sub', true);
  const dPeriod = periodInput('ac-v-period');
  const dType = ui.el('select', { id: 'ac-v-type' });
  Object.keys(VOUCHER_TYPES).forEach((k) => dType.appendChild(ui.el('option', { value: k, text: VOUCHER_TYPES[k] })));
  const dSummary = ui.el('input', { id: 'ac-v-summary', placeholder: '业务摘要（必填，落分录）' });
  const dAmt = amtInput('ac-v-amt');
  const dQty = amtInput('ac-v-qty', { placeholder: '可空，如成交手数' });
  const dPlan = numInput('ac-v-plan', { min: '1', placeholder: '可空' });
  const dRel = numInput('ac-v-rel', { min: '1', placeholder: '可空' });
  const dCycle = numInput('ac-v-cycle', { min: '1', placeholder: '关联结算单，可空' });
  const dExp = numInput('ac-v-exp', { min: '1', placeholder: '关联实货敞口，可空' });
  const dFill = numInput('ac-v-fill', { min: '1', placeholder: '关联成交，可空' });
  const dBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '生成草稿' });
  draftCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-v-sub', label: '子账户（必填，按子账户出账）', required: true, control: dSub }),
    ui.field({ id: 'ac-v-period', label: '会计期间（必填，yyyy-MM）', required: true, control: dPeriod }),
    ui.field({ id: 'ac-v-type', label: '凭证类型（十值，含期权三值）', required: true, control: dType }),
    ui.field({ id: 'ac-v-summary', label: '业务摘要（必填）', required: true, control: dSummary })
  ]));
  draftCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-v-amt', label: '金额（必填，借贷成对同额）', required: true, control: dAmt }),
    ui.field({ id: 'ac-v-qty', label: '数量', control: dQty }),
    ui.field({ id: 'ac-v-plan', label: '关联套保计划', control: dPlan }),
    ui.field({ id: 'ac-v-rel', label: '关联套期关系', control: dRel })
  ]));
  draftCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-v-cycle', label: '关联结算单', control: dCycle }),
    ui.field({ id: 'ac-v-exp', label: '关联实货敞口', control: dExp }),
    ui.field({ id: 'ac-v-fill', label: '关联成交', control: dFill }),
    dBtn
  ]));
  const dBody = ui.el('div');
  draftCard.appendChild(dBody);
  container.appendChild(draftCard);

  dBtn.addEventListener('click', async () => {
    if (!dSub.value) { ui.toast('子账户必填（凭证必须按子账户出账，R-ACCT-5）', 'error'); return; }
    if (!validPeriodKey(dPeriod.value)) { ui.toast('会计期间须为 yyyy-MM 形态（如 2026-09）', 'error'); return; }
    if (!dType.value) { ui.toast('凭证类型必填（ck_t_voucher_type 十值）', 'error'); return; }
    if (!dSummary.value.trim()) { ui.toast('业务摘要必填（落分录 biz_desc）', 'error'); return; }
    if (!dAmt.value.trim()) { ui.toast('金额必填（借贷成对同额）', 'error'); return; }
    const go = await ui.confirm('确认生成凭证草稿（子账户 #' + F.esc(dSub.value) + '，期间 ' + F.esc(dPeriod.value.trim())
      + '，类型 ' + F.esc(VOUCHER_TYPES[dType.value]) + '，金额 ' + F.esc(dAmt.value.trim()) + '）？'
      + '草稿不做总账过账，须经审核后方为正式。', { title: '生成凭证草稿' });
    if (!go) return;
    ui.clear(dBody); ui.renderLoading(dBody, '正在生成凭证草稿…');
    try {
      const r = await HG.api.post(A.VOUCHER, {
        subAccountId: Number(dSub.value), periodKey: dPeriod.value.trim(), voucherType: dType.value,
        summary: dSummary.value.trim(), amount: dAmt.value.trim(), qty: dQty.value.trim() || null,
        hedgePlanId: dPlan.value ? Number(dPlan.value) : null,
        hedgeRelationId: dRel.value ? Number(dRel.value) : null,
        settleCycleId: dCycle.value ? Number(dCycle.value) : null,
        exposureId: dExp.value ? Number(dExp.value) : null,
        tradeFillId: dFill.value ? Number(dFill.value) : null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(dBody);
      dBody.appendChild(ui.banner('凭证草稿已生成：凭证编号 <code>' + F.esc(r.voucherNo || '') + '</code>'
        + '（ID #' + dash(r.id) + '，状态 ' + voucherStatusHTML(r.status) + '）。'
        + '<strong>' + F.esc(r.draftPendingEntryNote || '待企业财务复核入账') + '</strong>——不做总账过账（Q-9）。'
        + '请在下方执行提交审核（审核人不得为生成人）。', 'ok'));
      loadList(1);
    } catch (err) { ui.renderError(dBody, err, () => dBtn.click()); }
  });

  const listCard = ui.el('section', { class: 'hg-card' });
  listCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '凭证查询（FR-ACCT-8，只读）' }));
  listCard.appendChild(ui.banner(
    '凭证记录<strong>只读</strong>：本列表无编辑 / 删除入口（FR-ACCT-8 验收 3）；正式凭证不得修改、只能冲销（R-ACCT-5）'
      + '——草稿删除仅经显式通道（见下方「草稿删除」）；默认按凭证编号倒序，仅返回有权限的子账户范围。', 'warn'
  ));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE };
  const lSub = numInput('ac-vl-sub', { min: '1', placeholder: '可空' });
  const lPeriod = periodInput('ac-vl-period');
  const lStatus = ui.el('select', { id: 'ac-vl-status' });
  lStatus.appendChild(ui.el('option', { value: '', text: '全部状态' }));
  Object.keys(VOUCHER_STATUS).forEach((k) => lStatus.appendChild(ui.el('option', { value: k, text: VOUCHER_STATUS[k] })));
  const lBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询凭证' });
  listCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-vl-sub', label: '子账户', control: lSub }),
    ui.field({ id: 'ac-vl-period', label: '会计期间', control: lPeriod }),
    ui.field({ id: 'ac-vl-status', label: '凭证状态', control: lStatus }),
    lBtn
  ]));
  const lBody = ui.el('div');
  const lPager = ui.el('div', { class: 'hg-pager' });
  // 关联单据跳转（事件委托——tableHTML 为字符串形态；点击 data-voucher-jump 锚标即深链）。
  lBody.addEventListener('click', (ev) => {
    const t = ev && ev.target;
    const to = t && t.getAttribute ? t.getAttribute('data-voucher-jump') : null;
    if (to) HG.router.navigate(to);
  });
  listCard.appendChild(lBody);
  listCard.appendChild(lPager);
  container.appendChild(listCard);

  async function loadList(page) {
    state.page = page || 1;
    const q = { page: state.page, size: state.size };
    if (lSub.value) q.subAccountId = lSub.value;
    if (validPeriodKey(lPeriod.value)) q.periodKey = lPeriod.value.trim();
    if (lStatus.value) q.status = lStatus.value;
    ui.clear(lBody); ui.renderLoading(lBody, '正在查询凭证…');
    try {
      const data = await HG.api.get(A.VOUCHER, q);
      attachPager(lPager, { total: data.total, page: data.page, size: data.size }, loadList);
      ui.clear(lBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(lBody, '该筛选条件下无凭证。'); return; }
      lBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'voucherNo', title: '凭证编号' },
          { key: 'subAccountId', title: '子账户', num: true },
          { key: 'periodKey', title: '期间' },
          { title: '凭证日期', render: (r) => F.esc(F.date(r.voucherDate)) },
          { title: '类型', render: (r) => F.esc(VOUCHER_TYPES[r.voucherType] || r.voucherType || '—') },
          { title: '状态', render: (r) => voucherStatusHTML(r.status) },
          { title: '金额', num: true, render: (r) => showAmt(r.totalAmount) },
          { title: '冲销关联', num: true, render: (r) => F.esc((r.reversalOfId ? '冲销原凭证 #' + r.reversalOfId : '')
            + (r.reversedById ? '（被 #' + r.reversedById + ' 冲销）' : '') || '—') },
          { title: '关联单据', render: (r) => relatedDocHTML(r) },
          { title: '外部复核', render: (r) => extReviewHTML(r.pendingExternalReview) || '—' }
        ],
        data.items
      ) }));
    } catch (err) { ui.renderError(lBody, err, () => loadList(state.page)); }
  }
  lBtn.addEventListener('click', () => loadList(1));

  const detCard = ui.el('section', { class: 'hg-card' });
  detCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '凭证详情（头 + 分录 + 审核链）' }));
  const detId = numInput('ac-vd-id', { min: '1', placeholder: '凭证 ID' });
  const detBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '查询详情' });
  detCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-vd-id', label: '凭证 ID（必填）', required: true, control: detId }),
    detBtn
  ]));
  const detBody = ui.el('div');
  detCard.appendChild(detBody);
  container.appendChild(detCard);

  detBtn.addEventListener('click', async () => {
    if (!detId.value) { ui.toast('凭证 ID 必填', 'error'); return; }
    ui.clear(detBody); ui.renderLoading(detBody, '正在查询凭证详情…');
    try {
      const d = await HG.api.get(A.VOUCHER, { id: detId.value });
      ui.clear(detBody);
      detBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { title: '项', render: (p) => F.esc(p[0]) },
          { title: '值', render: (p) => F.esc(p[1]) }
        ],
        [
          ['凭证编号', d.voucherNo || '—'],
          ['子账户', d.subAccountId == null ? '—' : String(d.subAccountId)],
          ['期间', d.periodKey || '—'],
          ['类型', VOUCHER_TYPES[d.voucherType] || d.voucherType || '—'],
          ['状态', VOUCHER_STATUS[d.status] || d.status || '—'],
          ['金额', d.totalAmount == null ? '—' : F.money(d.totalAmount)],
          ['生成人', d.generatedBy == null ? '—' : String(d.generatedBy)],
          ['生成时间', d.generatedTime == null ? '—' : F.dateTime(d.generatedTime)],
          ['复核标注', d.draftPendingEntryNote || '待企业财务复核入账']
        ]
      ) }));
      // 关联单据（FR-ACCT-8 验收 2 追溯入口，TE-T083-001）：真实节点形态——跳转键为按钮，
      // 无路由键展示为可选中文本；空值键不渲染，四键皆空显「—」。
      detBody.appendChild(ui.el('h4', { text: '关联单据（套保计划 / 实货敞口追溯）' }));
      const relRow = ui.el('div');
      RELATED_DOC_DEFS.forEach((def) => {
        const relId = d[def.key];
        if (relId == null) return;
        const to = relatedDocTarget(def.key, relId);
        relRow.appendChild(to
          ? ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', 'data-voucher-related': def.key,
              text: def.label + ' #' + relId, onclick: () => HG.router.navigate(to) })
          : ui.el('span', { 'data-voucher-related': def.key, text: def.label + ' #' + relId }));
      });
      if (!relRow.children.length) relRow.appendChild(ui.el('span', { text: '—' }));
      detBody.appendChild(relRow);
      detBody.appendChild(ui.el('h4', { text: '分录行（借贷成对）' }));
      detBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { title: '行号', num: true, render: (l) => dash(l.lineNo) },
          { title: '科目编码', render: (l) => F.esc(l.subjectCode || '—') },
          { title: '科目名称', render: (l) => F.esc(l.subjectName || '—') },
          { title: '方向', render: (l) => F.esc(l.direction === 'DEBIT' ? '借' : (l.direction === 'CREDIT' ? '贷' : (l.direction || '—'))) },
          { title: '金额', num: true, render: (l) => showAmt(l.amount) },
          { title: '数量', num: true, render: (l) => showAmt(l.qty) },
          { title: '摘要', render: (l) => F.esc(l.bizDesc || '—') },
          { title: '关联成交', num: true, render: (l) => dash(l.tradeFillId) }
        ],
        d.lines || []
      ) }));
      detBody.appendChild(ui.el('h4', { text: '审核链（留痕：人 / 时间 / 结论 / 意见）' }));
      detBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { title: '节点', num: true, render: (r) => dash(r.nodeSeq) },
          { title: '动作', render: (r) => F.esc(REVIEW_ACTIONS[r.action] || r.action || '—') },
          { title: '生成人', num: true, render: (r) => dash(r.generatorId) },
          { title: '审核人', num: true, render: (r) => dash(r.reviewerId) },
          { title: '第二复核人', num: true, render: (r) => dash(r.coReviewerId) },
          { title: '结果', render: (r) => F.esc(r.reviewResult || '—') },
          { title: '意见', render: (r) => F.esc(r.reviewComment || '—') },
          { title: '驳回原因', render: (r) => F.esc(r.rejectReason || '—') },
          { title: '时间', render: (r) => F.esc(F.dateTime(r.actionTime)) }
        ],
        d.reviews || []
      ) }));
    } catch (err) { ui.renderError(detBody, err, () => detBtn.click()); }
  });

  const revCard = ui.el('section', { class: 'hg-card' });
  revCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '凭证审核（FR-ACCT-7，岗位分离）' }));
  revCard.appendChild(ui.banner(
    '<strong>岗位分离（R-ACCT-6）</strong>：凭证<strong>草稿的生成人与审核人不得为同一人</strong>'
      + '（系统级强制，后端拒绝并留痕）；<strong>不得绕过审核直接入账</strong>；'
      + '审核记录（人、时间、结论、意见）永久留痕；<strong>驳回原因必填</strong>（前端预检 + 后端强制）。'
      + '审核通过后凭证为「已审核（正式）」且不可修改。', 'warn'
  ));
  const rvId = numInput('ac-vr-id', { min: '1', placeholder: '凭证 ID' });
  const rvAction = ui.el('select', { id: 'ac-vr-action' });
  Object.keys(REVIEW_ACTIONS).forEach((k) => rvAction.appendChild(ui.el('option', { value: k, text: REVIEW_ACTIONS[k] })));
  const rvComment = ui.el('input', { id: 'ac-vr-comment', placeholder: '审核意见（可空）' });
  const rvReject = ui.el('input', { id: 'ac-vr-reject', placeholder: '驳回原因（驳回时必填）' });
  const rvBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '提交审核动作' });
  revCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-vr-id', label: '凭证 ID（必填）', required: true, control: rvId }),
    ui.field({ id: 'ac-vr-action', label: '动作（必填）', required: true, control: rvAction }),
    ui.field({ id: 'ac-vr-comment', label: '审核意见', control: rvComment }),
    ui.field({ id: 'ac-vr-reject', label: '驳回原因', control: rvReject }),
    rvBtn
  ]));
  const rvBody = ui.el('div');
  revCard.appendChild(rvBody);
  container.appendChild(revCard);

  rvBtn.addEventListener('click', async () => {
    if (!rvId.value) { ui.toast('凭证 ID 必填', 'error'); return; }
    if (!rvAction.value) { ui.toast('审核动作必填（SUBMIT/APPROVE/REJECT/WITHDRAW）', 'error'); return; }
    if (rvAction.value === 'REJECT' && !rvReject.value.trim()) {
      ui.toast('驳回须填写原因（FR-ACCT-7 验收 3）', 'error'); return;
    }
    const go = await ui.confirm('确认对凭证 #' + F.esc(rvId.value) + ' 执行「' + F.esc(REVIEW_ACTIONS[rvAction.value]) + '」？'
      + '<br>岗位分离：生成人与审核人不得为同一人（后端强制）。', { title: '凭证审核', danger: rvAction.value === 'REJECT' });
    if (!go) return;
    ui.clear(rvBody); ui.renderLoading(rvBody, '正在提交审核动作…');
    try {
      const r = await HG.api.post(A.voucherReview(rvId.value), {
        action: rvAction.value, reviewComment: rvComment.value.trim() || null,
        rejectReason: rvAction.value === 'REJECT' ? rvReject.value.trim() : null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(rvBody);
      rvBody.appendChild(ui.banner('审核动作完成（节点 #' + dash(r.nodeSeq) + '，结果 ' + F.esc(r.reviewResult || '—') + '）——'
        + '留痕：审核人 #' + dash(r.reviewerId) + '，时间 ' + F.esc(F.dateTime(r.actionTime)) + '。', 'ok'));
    } catch (err) { ui.renderError(rvBody, err, () => rvBtn.click()); }
  });

  const revsCard = ui.el('section', { class: 'hg-card' });
  revsCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '凭证冲销（FR-ACCT-13，双人复核 DC-032）' }));
  revsCard.appendChild(ui.banner(
    '正式凭证<strong>不得修改、不得删除</strong>，只能<strong>冲销</strong>（生成反向凭证并关联原凭证）；'
      + '冲销须<strong>双人复核</strong>：第二复核人 ≠ 发起人且 ≠ 生成人（DC-032）；'
      + '已关账期间的冲销在<strong>当期做调整</strong>（不回改已关账期间）；冲销全过程留痕并进入 AUDIT。', 'warn'
  ));
  const rsId = numInput('ac-vrs-id', { min: '1', placeholder: '原凭证 ID' });
  const rsReason = ui.el('input', { id: 'ac-vrs-reason', placeholder: '冲销原因（必填）' });
  const rsCo = numInput('ac-vrs-co', { min: '1', placeholder: '第二复核人用户 ID（≠ 发起人 ≠ 生成人）' });
  const rsBtn = ui.el('button', { class: 'hg-btn hg-btn--danger', type: 'button', text: '执行冲销' });
  revsCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-vrs-id', label: '原凭证 ID（必填）', required: true, control: rsId }),
    ui.field({ id: 'ac-vrs-reason', label: '冲销原因（必填）', required: true, control: rsReason }),
    ui.field({ id: 'ac-vrs-co', label: '第二复核人（必填，双人复核）', required: true, control: rsCo }),
    rsBtn
  ]));
  const rsBody = ui.el('div');
  revsCard.appendChild(rsBody);
  container.appendChild(revsCard);

  rsBtn.addEventListener('click', async () => {
    if (!rsId.value) { ui.toast('原凭证 ID 必填', 'error'); return; }
    if (!rsReason.value.trim()) { ui.toast('冲销原因必填（FR-ACCT-13）', 'error'); return; }
    if (!rsCo.value) { ui.toast('第二复核人必填（冲销双人复核，DC-032）', 'error'); return; }
    const go = await ui.confirm('确认冲销凭证 #' + F.esc(rsId.value) + '（原因：' + F.esc(rsReason.value.trim()) + '）？'
      + '<br>双人复核：第二复核人 #' + F.esc(rsCo.value) + ' ≠ 发起人且 ≠ 生成人；将生成反向凭证并关联原凭证。'
      + '已关账期间的冲销在当期做调整。', { title: '凭证冲销', danger: true });
    if (!go) return;
    ui.clear(rsBody); ui.renderLoading(rsBody, '正在执行冲销…');
    try {
      const r = await HG.api.post(A.voucherReverse(rsId.value), {
        reason: rsReason.value.trim(), coReviewerId: Number(rsCo.value)
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(rsBody);
      rsBody.appendChild(ui.banner('反向凭证已生成：<code>' + F.esc(r.voucherNo || '') + '</code>'
        + '（ID #' + dash(r.id) + '，状态 ' + voucherStatusHTML(r.status) + '，冲销原凭证 #' + dash(r.reversalOfId) + '）。'
        + F.esc(r.draftPendingEntryNote || ''), 'ok'));
    } catch (err) { ui.renderError(rsBody, err, () => rsBtn.click()); }
  });

  const delCard = ui.el('section', { class: 'hg-card' });
  delCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '草稿删除（FR-ACCT-13；仅草稿可删除，显式通道）' }));
  const dlId = numInput('ac-vdel-id', { min: '1', placeholder: '草稿凭证 ID' });
  const dlBtn = ui.el('button', { class: 'hg-btn hg-btn--danger', type: 'button', text: '删除草稿' });
  delCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-vdel-id', label: '草稿凭证 ID（必填）', required: true, control: dlId }),
    dlBtn
  ]));
  const dlBody = ui.el('div');
  delCard.appendChild(dlBody);
  container.appendChild(delCard);

  dlBtn.addEventListener('click', async () => {
    if (!dlId.value) { ui.toast('草稿凭证 ID 必填', 'error'); return; }
    const go = await ui.confirm('确认删除草稿凭证 #' + F.esc(dlId.value) + '？'
      + '仅草稿状态可删除（正式凭证只能冲销）；删除留痕。', { title: '删除草稿', danger: true });
    if (!go) return;
    ui.clear(dlBody); ui.renderLoading(dlBody, '正在删除草稿…');
    try {
      const r = await HG.api.post(A.VOUCHER, { action: 'DELETE_DRAFT', voucherId: Number(dlId.value) },
        { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(dlBody);
      dlBody.appendChild(ui.banner('草稿已删除（凭证编号 <code>' + F.esc(r.voucherNo || '') + '</code>，状态 '
        + voucherStatusHTML(r.status) + '）——删除已留痕。', 'ok'));
      loadList(1);
    } catch (err) { ui.renderError(dlBody, err, () => dlBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 7：会计期间（FR-ACCT-12；关账三前置 + 双人复核）
 * ------------------------------------------------------------------ */
function periodTab(container) {
  const listCard = ui.el('section', { class: 'hg-card' });
  listCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '会计期间查询（FR-ACCT-12）' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE };
  const pBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询期间' });
  listCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [pBtn]));
  const pBody = ui.el('div');
  const pPager = ui.el('div', { class: 'hg-pager' });
  listCard.appendChild(pBody);
  listCard.appendChild(pPager);
  container.appendChild(listCard);

  async function loadList(page) {
    state.page = page || 1;
    ui.clear(pBody); ui.renderLoading(pBody, '正在查询会计期间…');
    try {
      const data = await HG.api.get(A.PERIOD, { page: state.page, size: state.size });
      attachPager(pPager, { total: data.total, page: data.page, size: data.size }, loadList);
      ui.clear(pBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(pBody, '暂无会计期间（可先创建开放期间）。'); return; }
      pBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'periodKey', title: '期间' },
          { title: '类型', render: (r) => F.esc(r.periodType || '—') },
          { title: '起', render: (r) => F.esc(F.date(r.periodStart)) },
          { title: '止', render: (r) => F.esc(F.date(r.periodEnd)) },
          { title: '状态', render: (r) => periodStatusHTML(r.status) },
          { title: '关账人', num: true, render: (r) => dash(r.closedBy) },
          { title: '关账时间', render: (r) => F.esc(F.dateTime(r.closedTime)) },
          { title: '复核人', num: true, render: (r) => dash(r.closeReviewerId) },
          { title: '重开人', num: true, render: (r) => dash(r.reopenedBy) },
          { title: '重开原因', render: (r) => F.esc(r.reopenReason || '—') }
        ],
        data.items
      ) }));
    } catch (err) { ui.renderError(pBody, err, () => loadList(state.page)); }
  }
  pBtn.addEventListener('click', () => loadList(1));

  const logCard = ui.el('section', { class: 'hg-card' });
  logCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '关账历史（审计留痕）' }));
  const lgPeriod = periodInput('ac-p-log-period');
  const lgBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '查询关账历史' });
  logCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-p-log-period', label: '期间（必填，yyyy-MM）', required: true, control: lgPeriod }),
    lgBtn
  ]));
  const lgBody = ui.el('div');
  logCard.appendChild(lgBody);
  container.appendChild(logCard);

  lgBtn.addEventListener('click', async () => {
    if (!validPeriodKey(lgPeriod.value)) { ui.toast('期间须为 yyyy-MM 形态（如 2026-09）', 'error'); return; }
    ui.clear(lgBody); ui.renderLoading(lgBody, '正在查询关账历史…');
    try {
      const items = await HG.api.get(A.PERIOD, { periodKey: lgPeriod.value.trim() });
      ui.clear(lgBody);
      if (!items || !items.length) { ui.renderEmpty(lgBody, '该期间无关账留痕。'); return; }
      lgBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'periodKey', title: '期间' },
          { title: '动作', render: (r) => F.esc(r.action === 'CLOSE' ? '关账' : (r.action === 'REOPEN' ? '撤销关账' : (r.action || '—'))) },
          { title: '前置检查结果', render: (r) => F.esc(r.precheckResult || '—') },
          { title: '操作人', num: true, render: (r) => dash(r.operatorId) },
          { title: '复核人', num: true, render: (r) => dash(r.reviewerId) },
          { key: 'reason', title: '原因' },
          { title: '时间', render: (r) => F.esc(F.dateTime(r.actionTime)) }
        ],
        items
      ) }));
    } catch (err) { ui.renderError(lgBody, err, () => lgBtn.click()); }
  });

  const newCard = ui.el('section', { class: 'hg-card' });
  newCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '创建开放期间（关账动作载体）' }));
  const nPeriod = periodInput('ac-p-new-period');
  const nType = ui.el('select', { id: 'ac-p-new-type' });
  nType.appendChild(ui.el('option', { value: 'MONTH', text: 'MONTH（月）' }));
  nType.appendChild(ui.el('option', { value: 'QUARTER', text: 'QUARTER（季）' }));
  nType.appendChild(ui.el('option', { value: 'YEAR', text: 'YEAR（年）' }));
  const nStart = dateInput('ac-p-new-start');
  const nEnd = dateInput('ac-p-new-end');
  const nBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '创建期间' });
  newCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-p-new-period', label: '期间键（必填，yyyy-MM）', required: true, control: nPeriod }),
    ui.field({ id: 'ac-p-new-type', label: '期间类型（必填）', required: true, control: nType }),
    ui.field({ id: 'ac-p-new-start', label: '期间起（必填）', required: true, control: nStart }),
    ui.field({ id: 'ac-p-new-end', label: '期间止（必填）', required: true, control: nEnd }),
    nBtn
  ]));
  const nBody = ui.el('div');
  newCard.appendChild(nBody);
  container.appendChild(newCard);

  nBtn.addEventListener('click', async () => {
    if (!validPeriodKey(nPeriod.value)) { ui.toast('期间键须为 yyyy-MM 形态（如 2026-09）', 'error'); return; }
    if (!nType.value) { ui.toast('期间类型必填（MONTH/QUARTER/YEAR）', 'error'); return; }
    if (!nStart.value || !nEnd.value) { ui.toast('期间起止必填', 'error'); return; }
    const go = await ui.confirm('确认创建开放期间 ' + F.esc(nPeriod.value.trim()) + '（'
      + F.esc(nStart.value) + ' ~ ' + F.esc(nEnd.value) + '）？期间键已存在时将被 10010 拒绝。', { title: '创建开放期间' });
    if (!go) return;
    ui.clear(nBody); ui.renderLoading(nBody, '正在创建期间…');
    try {
      const r = await HG.api.post(A.PERIOD, {
        periodKey: nPeriod.value.trim(), periodType: nType.value,
        periodStart: nStart.value, periodEnd: nEnd.value
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(nBody);
      nBody.appendChild(ui.banner('期间已创建：' + F.esc(r.periodKey || '—') + '（状态 ' + periodStatusHTML(r.status) + '）。', 'ok'));
      loadList(1);
    } catch (err) { ui.renderError(nBody, err, () => nBtn.click()); }
  });

  const closeCard = ui.el('section', { class: 'hg-card' });
  closeCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '关账 / 撤销关账（FR-ACCT-12，双人复核）' }));
  closeCard.appendChild(ui.banner(
    '<strong>关账三前置（R-ACCT-8，任一不满足不得关账）</strong>：① 该期间全部凭证已审核或已冲销 '
      + '② 该期间结算已完成 ③ 该期间有效性评估已完成——不满足时服务端以 10012 阻断并<strong>并列列出未审核凭证</strong>（原文展示）。'
      + '关账与撤销关账均须<strong>双人复核</strong>（复核人 ≠ 操作人，必填）。'
      + '关账后<strong>不得</strong>在该期间产生新凭证（10016），须通过<strong>下期调整</strong>。', 'warn'
  ));
  const cId = numInput('ac-p-close-id', { min: '1', placeholder: '期间行 ID' });
  const cAction = ui.el('select', { id: 'ac-p-close-action' });
  cAction.appendChild(ui.el('option', { value: 'CLOSE', text: 'CLOSE（关账）' }));
  cAction.appendChild(ui.el('option', { value: 'REOPEN', text: 'REOPEN（撤销关账）' }));
  const cReason = ui.el('input', { id: 'ac-p-close-reason', placeholder: '撤销关账原因（REOPEN 必填）' });
  const cReviewer = numInput('ac-p-close-reviewer', { min: '1', placeholder: '复核人用户 ID（≠ 操作人）' });
  const cBtn = ui.el('button', { class: 'hg-btn hg-btn--danger', type: 'button', text: '提交关账动作' });
  closeCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'ac-p-close-id', label: '期间行 ID（必填）', required: true, control: cId }),
    ui.field({ id: 'ac-p-close-action', label: '动作（必填）', required: true, control: cAction }),
    ui.field({ id: 'ac-p-close-reason', label: '撤销原因', control: cReason }),
    ui.field({ id: 'ac-p-close-reviewer', label: '复核人（必填，双人复核）', required: true, control: cReviewer }),
    cBtn
  ]));
  const cBody = ui.el('div');
  closeCard.appendChild(cBody);
  container.appendChild(closeCard);

  cBtn.addEventListener('click', async () => {
    if (!cId.value) { ui.toast('期间行 ID 必填', 'error'); return; }
    if (!cAction.value) { ui.toast('动作必填（CLOSE/REOPEN）', 'error'); return; }
    if (cAction.value === 'REOPEN' && !cReason.value.trim()) {
      ui.toast('撤销关账须填写原因（FR-ACCT-12 验收 4）', 'error'); return;
    }
    if (!cReviewer.value) { ui.toast('复核人必填（关账双人复核，R-ACCT-8）', 'error'); return; }
    const go = await ui.confirm('确认对期间 #' + F.esc(cId.value) + ' 执行「'
      + (cAction.value === 'CLOSE' ? '关账' : '撤销关账') + '」？'
      + '<br>双人复核：复核人 #' + F.esc(cReviewer.value) + ' ≠ 操作人；关账三前置不满足将被 10012 阻断。'
      + '关账后该期间新凭证须走下期调整。', { title: '期间关账', danger: true });
    if (!go) return;
    ui.clear(cBody); ui.renderLoading(cBody, '正在提交关账动作…');
    try {
      const r = await HG.api.post(A.periodClose(cId.value), {
        action: cAction.value, reason: cAction.value === 'REOPEN' ? cReason.value.trim() : null,
        reviewerId: Number(cReviewer.value)
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(cBody);
      cBody.appendChild(ui.banner('关账动作完成：期间 ' + F.esc(r.periodKey || '—') + '，动作 '
        + F.esc(r.action === 'CLOSE' ? '关账' : '撤销关账') + '，前置检查 ' + F.esc(r.precheckResult || '—')
        + '（留痕：操作人 #' + dash(r.operatorId) + ' / 复核人 #' + dash(r.reviewerId) + ' / '
        + F.esc(F.dateTime(r.actionTime)) + '）。', 'ok'));
      loadList(1);
    } catch (err) {
      // 关账三前置不满足：10012 原文展示（含未审核凭证并列清单）
      ui.renderError(cBody, err, () => cBtn.click());
      if (err && err.code === 10012) {
        cBody.appendChild(ui.el('div', { class: 'hg-state', 'data-gate-10012': '1' }, [
          ui.el('div', { class: 'hg-state__title', text: '关账前置不满足（10012）——未满足项见上方原文' }),
          ui.el('div', { text: '三前置：①全部凭证已审核或已冲销 ②结算已完成 ③评估已完成；任一不满足不得关账（R-ACCT-8）。' })
        ]));
      }
    }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 8~9：未注册端点（selfcheck / export——页面框架保留 + 「未注册」诚实提示）
 *   /acct/selfcheck（FR-ACCT-14 自检清单）、/acct/export（FR-ACCT-9 凭证导出）
 *   —— ApiRoutes 路径表已登记但 handler/ 无对应 Handler ⇒ 调用即 404。
 *   处置对齐 routes.js NOT_REGISTERED 语义 + risk.js T-070 先例；缺口只报不改。
 * ------------------------------------------------------------------ */
function unregisteredTab(title, pathConst, plannedDesc, frRef) {
  return function build(container) {
    const card = ui.el('section', { class: 'hg-card' });
    card.appendChild(ui.el('h3', { class: 'hg-card__title', text: title }));
    card.appendChild(ui.banner(
      '<strong>端点未注册（NOT_REGISTERED）</strong>：<code>' + F.esc(pathConst) + '</code> 已在 ApiRoutes 路径表登记，'
        + '但<strong>未注册任何 (方法, 路径) 对（handler/ 下无对应 Handler）——调用即 404</strong>。'
        + '本页保留框架、<strong>不发起调用</strong>（routes.js NOT_REGISTERED 语义：未注册路径禁止调用）；'
        + '后端缺口只报不改。',
      'warn'
    ));
    card.appendChild(ui.el('p', { class: 'hg-page__desc', text: '规划功能：' + plannedDesc + '（' + frRef + '）。后端端点注册后本页将按同款三态形态接入。' }));
    const placeholder = ui.el('div');
    placeholder.appendChild(ui.el('div', { class: 'hg-state', 'data-not-registered': pathConst }, [
      ui.el('div', { class: 'hg-state__title', text: '端点未注册——暂无可展示数据' }),
      ui.el('div', { text: '该功能的后端 REST 端点尚未实现（无 Handler），前端不做臆造调用；请关注后端缺口清单。' })
    ]));
    card.appendChild(placeholder);
    container.appendChild(card);
  };
}

/* ------------------------------------------------------------------ *
 * 页面装配
 * ------------------------------------------------------------------ */
const TABS = [
  { key: 'effect', label: '套保效果', build: effectTab },
  { key: 'dualPnl', label: '双侧盈亏上报', build: dualPnlTab },
  { key: 'relation', label: '套期关系', build: relationTab },
  { key: 'effectiveness', label: '有效性评估', build: effectivenessTab },
  { key: 'rebalance', label: '再平衡', build: rebalanceTab },
  { key: 'voucher', label: '会计凭证', build: voucherTab },
  { key: 'period', label: '会计期间', build: periodTab },
  { key: 'selfcheck', label: 'CAS 24 自检（未注册）', build: unregisteredTab('CAS 24 条款自检清单', NR.ACCT_SELFCHECK, '逐条对照 CAS 24 条款自检（正式指定 / 书面文件三要素 / 评估记录 / 再平衡记录 / 双侧盈亏），含自检报告导出（标注待外部复核，自检不替代外部复核）', 'FR-ACCT-14 规划') },
  { key: 'export', label: '凭证导出（未注册）', build: unregisteredTab('会计凭证导出', NR.ACCT_EXPORT, '按期间 / 子账户 / 状态导出凭证（含凭证编号、分录行、套保计划编号与实货敞口编号，可追溯；无数据不生成空文件）', 'FR-ACCT-9 规划') }
];

function renderAcct(root, ctx) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '套保会计（ACCT）' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '套保效果（双侧盈亏同屏）/ 双侧盈亏上报 / 套期关系（三要素门禁）/ 有效性评估（三项定性观察点）/ 再平衡 / 会计凭证（草稿-审核-冲销）/ 会计期间关账（FR-ACCT-1~14）。' }));
  root.appendChild(ui.banner(
    '<strong>界面边界声明</strong>：本页仅提供界面，不做业务判定——三要素门禁、有效性结论、再平衡触发复核、'
      + '岗位分离、关账三前置、凭证编号唯一与幂等<strong>一律由后端负责</strong>。'
      + '<strong>口径单一（R-ACCT-12）</strong>：效果页 / 上报 / 凭证同源，前端不做任何自行计算。'
      + '<strong>期权与期货同等对待（R-ACCT-7）</strong>：期权浮动盈亏纳入效果评估，凭证类型含期权三值。'
      + extReviewHTML('Y') + ' 全部套期会计文档（关系文档 / 评估记录 / 再平衡记录 / 自检报告）一律标注'
      + '「待外部复核」，标注不得移除（R-ACCT-10）；自检不替代外部复核。'
      + '未注册端点（/acct/selfcheck、/acct/export）保留框架并诚实提示，不发起调用。',
    'warn'
  ));

  const tabBar = ui.el('div', { class: 'hg-tabs', role: 'tablist' });
  const panel = ui.el('div');
  let active = (ctx && ctx.query && ctx.query.tab) || 'effect';
  if (!TABS.some((t) => t.key === active)) active = 'effect';

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
    const b = ui.el('button', { type: 'button', role: 'tab', text: t.label, onclick: () => HG.router.navigate('/acct?tab=' + t.key) });
    b.dataset.key = t.key;
    tabBar.appendChild(b);
  });

  root.appendChild(tabBar);
  root.appendChild(panel);
  select(active);
}

HG.pages = HG.pages || {};
HG.pages.acct = { renderAcct };
HG.router.register('/acct', renderAcct);
export default HG.pages.acct;
