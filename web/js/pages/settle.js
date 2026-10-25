/*
 * 结算页面（SETTLE）—— 管理界面（FR-SETTLE-1~17；T-054）。
 *
 * ## 端点（12 路径 / 15 (方法,路径) 对，逐字对齐 ApiRoutes.byModule().get("SETTLE")；
 *          方法对取自 handler/settle/SettleRoutes.java + SettleCheckRoutes.java，实测全注册——无 NOT_REGISTERED 面）
 *   POST     /settle/ctp-data                      CTP 日终数据接收（FR-SETTLE-1）
 *   GET|POST /settle/cycle                         历史结算记录查询 / 执行子账户拆分与独立结算（FR-SETTLE-15 / 2/3/4）
 *   POST     /settle/cycle/{id}/reconcile          重跑三类核对（FR-SETTLE-5/6/7）
 *   POST     /settle/cycle/{id}/aggregate          加总对账（FR-SETTLE-8）
 *   POST     /settle/cycle/{id}/close              关账门禁（R-SETTLE-5）
 *   PUT      /settle/difference                    差异处理（FR-SETTLE-9）
 *   GET      /settle/report                        每日结算报告 / 风控日报（FR-SETTLE-10/12）
 *   GET      /settle/margin                        保证金与风险度视图（FR-SETTLE-11）
 *   POST     /settle/stress-test                   场景化压力测试（FR-SETTLE-11）
 *   GET|POST /settle/monthly-check                 月度核对任务查询 / 生成与签署（FR-SETTLE-13/17；FR-AUDIT-13 查询）
 *   GET|POST /settle/quarterly-report              季度报告任务查询 / 生成·会签·签收（FR-SETTLE-14/17）
 *   GET      /settle/export                        导出结算报表（FR-SETTLE-16）
 *
 * ## routes.js 键（T-054 只读核对结论）
 *   web/js/core/routes.js 现无 SETTLE 键。本单写域不含 routes.js（T-053 并行在飞），
 *   故本页以页面内路径常量承载（单页常量无跨页漂移面）；建议键清单已回报编排者裁决落法，
 *   落地后本页常量整体迁移至 HG.routes.SETTLE（逐字不变）。
 *
 * ## 业务红线（写进页面逻辑）
 *   · R-SETTLE-3：CTP 日终数据为**唯一权威基准**——接收回执 pendingRetry=true（数据未到达）时
 *     显式警示「待重试」，本页**不产出、不展示任何推算数据**（FR-SETTLE-1 验收 2）；
 *   · R-SETTLE-5 关账状态机：已确认（CONFIRMED）结算单的重算/核对/关账入口**禁用**
 *     （关账后如需重算须先作废原单并留痕；后端 PERIOD_CLOSED/STATE_NOT_ALLOWED 为最终裁决）；
 *     关账门禁三前置（①三类核对完成 ②加总对账一致 ③差异全部闭环）在服务端强制；
 *   · R-SETTLE-2 / Q-11（CH-001）：加总对账不一致 ⇒ 仅阻断**差异所属子账户**（新开仓/资金划拨/日终关账），
 *     **不波及其他子账户、不阻断平仓**——对账结果区固定渲染 blockedSubAccountIds 与阻断范围；
 *   · R-SETTLE-4 / FR-SETTLE-9：差异处理**调整类（ADJUST）须双人复核**（复核人≠处理人，后端强制，页面显式提示）；
 *     未闭环差异不得标记结算完成（后端关账门禁③强制）；
 *   · R-SETTLE-11 / Q-14（CH-002）：核对任务电子签署——签署角色**须显式选择**（不猜测不默认），
 *     **不可代签**（须为应签角色本人，后端校验并置 proxy_rejected）；**管理层只签收报告、不得操盘**
 *     （签署权与交易权互斥）；签署对象为核对时点快照哈希，签署后快照变更须重新签署；
 *   · R-SETTLE-10：结算单/核对记录/差异记录/报告**只增不改不删**——本页无任何编辑/删除入口，
 *     结算记录只读（FR-SETTLE-15 验收 3）；
 *   · 事件源接缝披露（SettleService javadoc 实态）：系统内按子账户明细（SettlementInput/
 *     SystemTradeRow）生产由事件流承接（T-172 域）——本页「执行结算」的明细粘贴区仅为
 *     服务方法接缝的演练入口，生产数据面以事件接线为准（诚实披露，不冒充已自动化）。
 *
 * ## 导出形态张力披露（工单要求 1③，TE-T069-003/T-195 先例）
 *   后端 GET /settle/export 现为 **JSON 信封**承载（ExportResult：exportTime/exportedBy/columns/rows），
 *   与 FR-SETTLE-16 输出「CSV/报表文件」的直返形态存在张力。本页在浏览器侧将 columns/rows
 *   转换为 CSV 触发下载（不改后端）；CSV **直返化**须后端变更承接（T-193/T-195 先例），已披露待裁。
 *
 * ## 幂等纪律
 *   写操作（ctp-data/执行结算/核对/对账/关账/差异处理/任务生成/签署）一律携带 Idempotency-Key
 *   （fund.js/auth.js 先例；FR-SETTLE-1「重复接收同一交易日数据不产生重复结算记录」）。
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

/* ------------------------------------------------------------------ *
 * 路径常量（页面内承载；逐字取自 ApiRoutes.byModule().get("SETTLE")。
 * routes.js SETTLE 键落地后整体迁移——迁移时逐字不变，见文件头「routes.js 键」节）
 * ------------------------------------------------------------------ */
// T-054 交付时页面内常量；第 255 轮补3 编排者裁决 routes.js SETTLE 键落地 ⇒ 整体迁移至
// HG.routes.SETTLE（逐字不变，见 routes.js T-054 补登记块）。S 保留为迁移别名（零调用面改动）。
const S = HG.routes.SETTLE;

/* ------------------------------------------------------------------ *
 * 取值域（以 SettleService 常量与 DATA_MODEL 冻结域实测为准）
 * ------------------------------------------------------------------ */
/** 结算单状态（SettleService.CYCLE_*；§2.1 状态机）。 */
const CYCLE_STATUS = {
  PENDING: '待结算', GENERATED: '已生成（待核对）', BLOCKED: '已阻断',
  RECONCILED: '核对完成', CONFIRMED: '已确认（关账）'
};
/** 结算单状态徽标（已确认=完成色、已阻断=警示色——均为显式业务态）。 */
function cycleStatusHTML(s) {
  const t = CYCLE_STATUS[s] || s || '—';
  const cls = s === 'CONFIRMED' ? 'done'
    : (s === 'BLOCKED' ? 'danger'
      : (s === 'RECONCILED' ? 'active'
        : (s === 'GENERATED' ? 'pending' : 'draft')));
  return '<span class="hg-badge hg-badge--' + cls + '" data-st-cycle-status="' + F.esc(s || '') + '">' + F.esc(t) + '</span>';
}
/** 核对结论（SettleService.RESULT_*）。 */
const RECON_RESULT = {
  CONSISTENT: '一致', HAS_DIFFERENCE: '存在差异', NOT_COMPLETED: '未完成（数据不足）'
};
/** 差异处理状态（SettleService.DIFF_*）。 */
const DIFF_STATUS = { PENDING: '待处理', HANDLING: '处理中', CLOSED: '已闭环' };
/** 差异处理方式（FR-SETTLE-9 输入：补录/冲正/调整）。 */
const DIFF_METHOD = { SUPPLEMENT: '补录', REVERSE: '冲正', ADJUST: '调整' };
/** 报告类型（t_settle_report.report_type 冻结域中本页端点承载的两类）。 */
const REPORT_TYPE = { DAILY_SETTLE: '每日结算报告', DAILY_RISK: '风控日报' };
/** 核对任务状态（t_check_task.status 冻结域）。 */
const TASK_STATUS = {
  PENDING_SIGN: '待签署', PARTIAL_SIGNED: '部分签署', COMPLETED: '已完成', OVERDUE: '已逾期'
};
/** 核对类型（t_check_task.check_type 冻结域）。 */
const CHECK_TYPE = { MONTHLY: '月度三方核对', QUARTERLY: '季度报告', ANNUAL: '年度审计覆盖' };
/** 签署角色（t_check_signature.sign_role 冻结域；管理层=签收）。 */
const SIGN_ROLE = { RISK: '风控岗', TRADE: '交易岗', FINANCE: '财务岗', MANAGE: '管理层（签收）', AUDITOR: '审计岗' };
/** 核对任务端点（月度核对 / 季度报告两条 GET|POST 端点）。 */
const CHECK_ENDPOINT = {
  MONTHLY_CHECK: { path: S.MONTHLY_CHECK, label: '月度核对（/settle/monthly-check）' },
  QUARTERLY_REPORT: { path: S.QUARTERLY_REPORT, label: '季度报告（/settle/quarterly-report）' }
};

/** 通用徽标（true=ok 徽标 / false=danger 徽标 / 空=—）。 */
function yesNoHTML(v, okText, badText) {
  if (v === true) return '<span class="hg-badge hg-badge--active">' + F.esc(okText || '是') + '</span>';
  if (v === false) return '<span class="hg-badge hg-badge--danger">' + F.esc(badText || '否') + '</span>';
  return '—';
}

/* ------------------------------------------------------------------ *
 * 通用控件（与 fund.js 同构）
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
function dateInput(id) { return ui.el('input', { id: id, type: 'date' }); }
/** JSON 文本区（明细接缝粘贴用）。 */
function jsonArea(id, placeholder, rows) {
  return ui.el('textarea', { id: id, rows: rows || 6, placeholder: placeholder || '' });
}
function selectOf(id, domain, emptyLabel) {
  const sel = ui.el('select', { id: id });
  if (emptyLabel) sel.appendChild(ui.el('option', { value: '', text: emptyLabel }));
  Object.keys(domain).forEach((k) => sel.appendChild(ui.el('option', { value: k, text: domain[k] })));
  return sel;
}

/* ------------------------------------------------------------------ *
 * Tab 1：CTP 日终数据接收（FR-SETTLE-1；R-SETTLE-3 权威基准红线）
 * ------------------------------------------------------------------ */
function ctpTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: 'CTP 日终结算数据接收（POST /settle/ctp-data）' }));
  card.appendChild(ui.banner(
    '<strong>CTP 日终数据为唯一权威基准（R-SETTLE-3）</strong>：数据未到达时系统置「待重试」（pendingRetry=true），'
      + '<strong>不得用推算数据替代</strong>（FR-SETTLE-1 验收 2）——本页不产出、不展示任何推算结算结果；'
      + '重复接收同一交易日数据由后端幂等处理，不产生重复结算记录（FR-SETTLE-1 验收 3）；'
      + '接收记录含接收时间与来源标识（验收 4）。',
    'warn'
  ));
  const tDate = dateInput('st-c-date');
  const tSource = ui.el('input', { id: 'st-c-source', placeholder: '文件名 / 批次号（来源标识，必填）' });
  const tDetail = jsonArea('st-c-detail',
    '明细 JSON（可选；形如 {"positions":[…],"funds":[…],"trades":[…]}——明细生产由事件流承接（T-172 域），此区为服务接缝演练入口，留空=空明细回执演练）', 8);
  const tBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '提交日终数据' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-c-date', label: '交易日（必填）', required: true, control: tDate }),
    ui.field({ id: 'st-c-source', label: '来源标识（必填）', required: true, control: tSource })
  ]));
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-c-detail', label: '持仓/资金/成交明细（JSON，可空）', control: tDetail }),
    tBtn
  ]));
  const tBody = ui.el('div');
  card.appendChild(tBody);
  container.appendChild(card);

  tBtn.addEventListener('click', async () => {
    if (!tDate.value) { ui.toast('交易日必填（yyyy-MM-dd）', 'error'); return; }
    if (!tSource.value.trim()) { ui.toast('来源标识必填（FR-SETTLE-1 验收 4）', 'error'); return; }
    let detail = {};
    if (tDetail.value.trim()) {
      try { detail = JSON.parse(tDetail.value); } catch (e) { ui.toast('明细 JSON 解析失败：' + e.message, 'error'); return; }
    }
    const go = await ui.confirm('确认提交 ' + F.esc(tDate.value) + ' 的 CTP 日终数据（来源：' + F.esc(tSource.value.trim()) + '）？'
      + '<br>重复接收同一交易日数据由后端幂等处理，不产生重复结算记录。', { title: '提交 CTP 日终数据' });
    if (!go) return;
    ui.clear(tBody); ui.renderLoading(tBody, '正在提交 CTP 日终数据…');
    try {
      const r = await HG.api.post(S.CTP_DATA, {
        tradeDate: tDate.value, sourceIdentifier: tSource.value.trim(),
        positions: detail.positions || null, funds: detail.funds || null, trades: detail.trades || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(tBody);
      if (r.pendingRetry === true) {
        tBody.appendChild(ui.banner(
          '<strong>数据未到达——已置待重试（pendingRetry）</strong>：系统不产生基于推算的结算结果（FR-SETTLE-1 验收 2）；'
            + '请确认 CTP 结算文件/查询接口后重新接收。', 'error'));
      } else {
        tBody.appendChild(ui.banner('接收成功：批次号 <code>' + F.esc(String(r.batchId)) + '</code>——持仓 '
          + F.esc(String(r.positionCount)) + ' 条 / 资金 ' + F.esc(String(r.fundCount)) + ' 条 / 成交 '
          + F.esc(String(r.tradeCount)) + ' 条，已进入按子账户拆分（FR-SETTLE-2/3）。', 'ok'));
      }
      if (r.positionPnlMissingCount > 0) {
        tBody.appendChild(ui.banner(
          '旧模板兼容披露（T-189）：' + F.esc(String(r.positionPnlMissingCount)) + ' 条持仓行未携带逐合约盈亏字段'
            + '（closeProfit/positionProfit），该部分不参与盈亏转录（FR-SETTLE-4 盈亏口径以携带行为准）。', 'warn'));
      }
      tBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { title: '项', render: (row) => F.esc(row[0]) },
          { title: '值', render: (row) => F.esc(row[1]) }
        ],
        [
          ['批次号（batchId）', String(r.batchId ?? '—')],
          ['待重试（pendingRetry）', r.pendingRetry === true ? '是（数据未到达）' : '否'],
          ['持仓条数', String(r.positionCount ?? '—')],
          ['资金条数', String(r.fundCount ?? '—')],
          ['成交条数', String(r.tradeCount ?? '—')],
          ['持仓盈亏字段携带数（T-189）', String(r.positionPnlPresentCount ?? '—')],
          ['持仓盈亏字段缺失数（T-189）', String(r.positionPnlMissingCount ?? '—')],
          ['回执说明', String(r.message || '—')]
        ]
      ) }));
    } catch (err) { ui.renderError(tBody, err, () => tBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 2：结算单（FR-SETTLE-15 列表 / FR-SETTLE-2/3/4 执行结算 / FR-SETTLE-5/6/7/8 + R-SETTLE-5 单据动作）
 * ------------------------------------------------------------------ */
function cyclesTab(container) {
  /* 列表状态缓存：id → status（动作卡片关账态感知的数据源；仅展示用，判定在服务端） */
  const statusById = {};

  // —— 列表（FR-SETTLE-15：默认按交易日倒序、只读；无任何编辑/删除入口，R-SETTLE-10）
  const listCard = ui.el('section', { class: 'hg-card' });
  listCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '历史结算记录查询（GET /settle/cycle，FR-SETTLE-15）' }));
  listCard.appendChild(ui.banner(
    '结算记录<strong>只读</strong>（R-SETTLE-10；FR-SETTLE-15 验收 3——无编辑/删除入口）；默认按交易日倒序；'
      + '仅返回有权限的子账户范围（服务端可见性强制）。状态列即 §2.1 关账状态机；'
      + '「已确认（关账）」单据的重算入口在下方动作区禁用（R-SETTLE-5）。',
    'warn'
  ));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE };
  const lStatus = selectOf('st-l-status', CYCLE_STATUS, '全部状态');
  const lFrom = dateInput('st-l-from');
  const lTo = dateInput('st-l-to');
  const lSub = numInput('st-l-sub', { min: '1', placeholder: '可空' });
  const lBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询结算单' });
  listCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-l-status', label: '状态', control: lStatus }),
    ui.field({ id: 'st-l-from', label: '交易日起', control: lFrom }),
    ui.field({ id: 'st-l-to', label: '交易日止', control: lTo }),
    ui.field({ id: 'st-l-sub', label: '子账户', control: lSub }),
    lBtn
  ]));
  const lBody = ui.el('div');
  const lPager = ui.el('div', { class: 'hg-pager' });
  listCard.appendChild(lBody);
  listCard.appendChild(lPager);
  container.appendChild(listCard);

  async function load(page) {
    state.page = page || 1;
    const q = { page: state.page, size: state.size, sort: 'trade_date,desc' };
    if (lStatus.value) q.status = lStatus.value;
    if (lFrom.value) q.from = lFrom.value;
    if (lTo.value) q.to = lTo.value;
    if (lSub.value) q.subAccountId = lSub.value;
    ui.clear(lBody); ui.renderLoading(lBody, '正在查询结算单…');
    try {
      const data = await HG.api.get(S.CYCLE, q);
      attachPager(lPager, { total: data.total, page: data.page, size: data.size }, load);
      ui.clear(lBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(lBody, '该筛选条件下无结算记录（FR-SETTLE-15：空结果返回空列表）。'); return; }
      data.items.forEach((c) => { if (c && c.id !== undefined && c.id !== null) statusById[c.id] = c.status; });
      lBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'id', title: 'ID', num: true },
          { key: 'settleNo', title: '结算单号' },
          { title: '交易日', render: (r) => F.esc(F.date(r.tradeDate)) },
          { key: 'subAccountId', title: '子账户', num: true },
          { title: '状态', render: (r) => cycleStatusHTML(r.status) },
          { title: '平仓盈亏', num: true, render: (r) => F.esc(F.money(r.closePnl)) },
          { title: '浮动盈亏', num: true, render: (r) => F.esc(F.money(r.floatPnl)) },
          { title: '手续费', num: true, render: (r) => F.esc(F.money(r.commission)) },
          { title: '保证金占用', num: true, render: (r) => F.esc(F.money(r.marginOccupied)) },
          { title: '可用资金', num: true, render: (r) => F.esc(F.money(r.availableFund)) },
          { title: '权益', num: true, render: (r) => F.esc(F.money(r.equity)) },
          { title: '风险度', num: true, render: (r) => F.esc(F.pct(r.riskRatio)) },
          { title: '负资金告警', render: (r) => (r.negativeFundFlag === 'Y' || r.negativeFundFlag === true
            ? '<span class="hg-badge hg-badge--danger">负资金（FR-SETTLE-4 验收 3）</span>'
            : (r.negativeFundFlag === 'N' || r.negativeFundFlag === false ? '—' : F.esc(String(r.negativeFundFlag ?? '—')))) }
        ],
        data.items
      ) }));
      refreshLock();
    } catch (err) { ui.renderError(lBody, err, () => load(state.page)); }
  }
  lBtn.addEventListener('click', () => load(1));

  // —— 执行结算（FR-SETTLE-2/3/4：POST /settle/cycle）
  const runCard = ui.el('section', { class: 'hg-card' });
  runCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '执行结算（POST /settle/cycle，FR-SETTLE-2/3/4）' }));
  runCard.appendChild(ui.banner(
    '按子账户拆分 + 独立结算 + 三类核对一次完成（每个子账户独立出结算结果，R-SETTLE-1）；'
      + 'CTP 明细缺失时后端以 DATA_INSUFFICIENT 拒绝（<strong>不产出推算结算结果</strong>，FR-SETTLE-1）；'
      + '系统内明细（持仓/资金/成交）生产由事件流承接（T-172 域）——下方明细区为服务方法接缝演练入口；'
      + '对「已确认（关账）」单据的同一交易日重复执行将被后端拒绝（重算须先作废原单并留痕，R-SETTLE-5）。',
    'warn'
  ));
  const rDate = dateInput('st-r-date');
  const rDetail = jsonArea('st-r-detail',
    '系统内明细 JSON（可选；{"positions":[…],"funds":[…],"trades":[…]}，行须显式携带 subAccountId——R-SETTLE-9）', 8);
  const rBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '执行结算' });
  runCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-r-date', label: '交易日（必填）', required: true, control: rDate })
  ]));
  runCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-r-detail', label: '系统内按子账户明细（JSON，可空）', control: rDetail }),
    rBtn
  ]));
  const rBody = ui.el('div');
  runCard.appendChild(rBody);
  container.appendChild(runCard);

  rBtn.addEventListener('click', async () => {
    if (!rDate.value) { ui.toast('交易日必填（yyyy-MM-dd）', 'error'); return; }
    let detail = {};
    if (rDetail.value.trim()) {
      try { detail = JSON.parse(rDetail.value); } catch (e) { ui.toast('明细 JSON 解析失败：' + e.message, 'error'); return; }
    }
    const go = await ui.confirm('确认执行 ' + F.esc(rDate.value) + ' 的子账户拆分与独立结算？'
      + '<br>已确认（关账）单据的同一交易日重算将被拒绝（R-SETTLE-5：须先作废原单并留痕）。', { title: '执行结算' });
    if (!go) return;
    ui.clear(rBody); ui.renderLoading(rBody, '正在执行结算…');
    try {
      const items = await HG.api.post(S.CYCLE, {
        tradeDate: rDate.value,
        positions: detail.positions || null, funds: detail.funds || null, trades: detail.trades || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(rBody);
      const list = Array.isArray(items) ? items : [];
      if (!list.length) { ui.renderEmpty(rBody, '本次执行未产出结算单（以服务端返回为准）。'); return; }
      list.forEach((c) => { if (c && c.id !== undefined && c.id !== null) statusById[c.id] = c.status; });
      rBody.appendChild(ui.banner('结算完成：本次产出 ' + F.esc(String(list.length)) + ' 份子账户结算单（R-SETTLE-1：子账户独立出账，不合并）。', 'ok'));
      rBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'id', title: 'ID', num: true },
          { key: 'settleNo', title: '结算单号' },
          { title: '交易日', render: (r) => F.esc(F.date(r.tradeDate)) },
          { key: 'subAccountId', title: '子账户', num: true },
          { title: '状态', render: (r) => cycleStatusHTML(r.status) },
          { title: '平仓盈亏', num: true, render: (r) => F.esc(F.money(r.closePnl)) },
          { title: '浮动盈亏', num: true, render: (r) => F.esc(F.money(r.floatPnl)) },
          { title: '手续费', num: true, render: (r) => F.esc(F.money(r.commission)) },
          { title: '风险度', num: true, render: (r) => F.esc(F.pct(r.riskRatio)) }
        ],
        list
      ) }));
      refreshLock();
    } catch (err) { ui.renderError(rBody, err, () => rBtn.click()); }
  });

  // —— 单据动作（重跑核对 / 加总对账 / 确认关账）——关账态感知禁用（R-SETTLE-5）
  const actCard = ui.el('section', { class: 'hg-card' });
  actCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '结算单动作（reconcile / aggregate / close）' }));
  actCard.appendChild(ui.banner(
    '<strong>关账门禁（R-SETTLE-5，后端强制）</strong>：确认关账前置 = ① 三类核对完成 ② 加总对账一致 ③ 全部差异已闭环。'
      + '「已确认（关账）」结算单：本区三动作<strong>禁用</strong>——关账后如需重算，须先作废原单并留痕；'
      + '加总对账不一致时<strong>仅阻断差异所属子账户</strong>（新开仓/资金划拨/日终关账），'
      + '<strong>不波及其他子账户、不阻断平仓</strong>（Q-11，CH-001）。',
    'warn'
  ));
  const aId = numInput('st-a-id', { min: '1', placeholder: '结算单 ID（见上方列表）' });
  actCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-a-id', label: '结算单 ID（必填）', required: true, control: aId })
  ]));
  const lockNote = ui.el('div');
  const recBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '重跑三类核对' });
  const aggBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '加总对账' });
  const closeBtn = ui.el('button', { class: 'hg-btn hg-btn--danger', type: 'button', text: '确认关账' });
  actCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [recBtn, aggBtn, closeBtn]));
  actCard.appendChild(lockNote);
  const aBody = ui.el('div');
  actCard.appendChild(aBody);
  container.appendChild(actCard);

  /** 关账态感知：CONFIRMED ⇒ 三动作禁用（R-SETTLE-5 前端形态；后端为最终裁决）。 */
  function refreshLock() {
    const raw = aId.value;
    const locked = raw !== '' && statusById[Number(raw)] === 'CONFIRMED';
    [recBtn, aggBtn, closeBtn].forEach((b) => {
      if (locked) b.setAttribute('disabled', ''); else b.removeAttribute('disabled');
    });
    ui.clear(lockNote);
    if (locked) {
      lockNote.appendChild(ui.banner(
        '结算单 #' + F.esc(raw) + ' 状态为<strong>已确认（关账）</strong>——重算/核对/关账入口已禁用'
          + '（R-SETTLE-5：关账后如需重算，须先作废原单并留痕）。', 'frozen'));
    } else if (raw && statusById[Number(raw)] === undefined) {
      lockNote.appendChild(ui.el('p', {
        class: 'hg-muted', text: '该 ID 不在当前已载入列表中——状态未知；关账门禁与重算守卫由服务端最终裁决（R-SETTLE-5）。'
      }));
    }
  }
  aId.addEventListener('input', refreshLock);

  recBtn.addEventListener('click', async () => {
    if (!aId.value) { ui.toast('结算单 ID 必填', 'error'); return; }
    if (aId.value && statusById[Number(aId.value)] === 'CONFIRMED') {
      ui.toast('该结算单已确认关账——重算入口已禁用（R-SETTLE-5：须先作废原单并留痕）', 'error'); return;
    }
    const go = await ui.confirm('确认对结算单 #' + F.esc(aId.value) + ' 重跑三类核对（成交/仓位/资金）？'
      + '<br>核对以 CTP 结算数据为基准（R-SETTLE-3）；三类核对完成是关账前置①。', { title: '重跑三类核对' });
    if (!go) return;
    ui.clear(aBody); ui.renderLoading(aBody, '正在重跑三类核对…');
    try {
      const r = await HG.api.post(S.cycleAction(aId.value, 'reconcile'), {}, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(aBody);
      const LINE_LABEL = { trade: '成交记录核对', position: '仓位核对', fund: '资金核对' };
      aBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { title: '核对类', render: (r2) => F.esc(LINE_LABEL[r2.type] || r2.type || '—') },
          { title: '结论', render: (r2) => (r2.result === 'CONSISTENT'
            ? '<span class="hg-badge hg-badge--active">一致</span>'
            : '<span class="hg-badge hg-badge--danger">' + F.esc(RECON_RESULT[r2.result] || r2.result || '—') + '</span>') },
          { title: '差异条数', num: true, render: (r2) => F.esc(String(r2.differenceCount ?? '—')) },
          { title: '核对记录号', num: true, render: (r2) => F.esc(String(r2.reconcileRecordId ?? '—')) }
        ],
        [r.trade, r.position, r.fund]
      ) }));
      aBody.appendChild(ui.el('p', { class: 'hg-muted', text: '三类核对必做且差异须逐条定位闭环（R-SETTLE-4）；核对记录可在审计中查询（FR-SETTLE-5/6/7 验收 4）。' }));
    } catch (err) { ui.renderError(aBody, err, () => recBtn.click()); }
  });

  aggBtn.addEventListener('click', async () => {
    if (!aId.value) { ui.toast('结算单 ID 必填', 'error'); return; }
    const go = await ui.confirm('确认对结算单 #' + F.esc(aId.value) + ' 所在交易日执行加总对账（Σ 子账户 = 真实账户）？'
      + '<br>不一致时全账户告警并生成差异待处理事项——仅阻断差异所属子账户，不阻断平仓。', { title: '加总对账' });
    if (!go) return;
    ui.clear(aBody); ui.renderLoading(aBody, '正在执行加总对账…');
    try {
      const r = await HG.api.post(S.cycleAction(aId.value, 'aggregate'), {}, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(aBody);
      aBody.appendChild(ui.banner(
        '加总对账结论（' + F.esc(F.date(r.tradeDate) || '—') + '）：<strong>' + (r.consistent === true ? '一致' : '不一致（已阻断）') + '</strong>'
          + (r.consistent === true ? '——允许关账（关账前置②达成）。'
            : '——全账户强制告警并生成「差异待处理」事项；仅阻断差异所属子账户（新开仓/资金划拨/日终关账），不波及其他子账户、不阻断平仓（Q-11）。'),
        r.consistent === true ? 'ok' : 'error'));
      const blocked = (r.blockedSubAccountIds || []);
      if (blocked.length) {
        aBody.appendChild(ui.banner(
          '被阻断子账户（机判来源 = t_agg_diff_candidate，仅差异所属）：' + F.esc(blocked.map(String).join('、'))
            + '——闭环前该子账户禁开新仓（FR-SETTLE-8）。', 'warn'));
      }
      const checks = (r.checks || []);
      if (checks.length) {
        aBody.appendChild(ui.el('div', { html: ui.tableHTML(
          [
            { key: 'id', title: '对账记录', num: true },
            { title: '对账类', render: (c) => F.esc(c.checkType || '—') },
            { title: 'Σ 子账户', num: true, render: (c) => F.esc(F.money(c.sumSubAccount)) },
            { title: '真实账户值', num: true, render: (c) => F.esc(F.money(c.realAccountValue)) },
            { title: '差额', num: true, render: (c) => F.esc(F.money(c.difference)) },
            { title: '结论', render: (c) => (c.result === 'CONSISTENT'
              ? '<span class="hg-badge hg-badge--active">一致</span>'
              : '<span class="hg-badge hg-badge--danger">' + F.esc(c.result || '—') + '</span>') },
            { title: '阻断范围', render: (c) => F.esc(c.blockScope || '—') },
            { title: '阻断生效时间', render: (c) => F.esc(F.dateTime(c.blockEffectiveTime) || '—') }
          ],
          checks
        ) }));
      }
    } catch (err) { ui.renderError(aBody, err, () => aggBtn.click()); }
  });

  closeBtn.addEventListener('click', async () => {
    if (!aId.value) { ui.toast('结算单 ID 必填', 'error'); return; }
    const go = await ui.confirm('确认关账结算单 #' + F.esc(aId.value) + '？'
      + '<br>关账门禁（R-SETTLE-5）三前置由服务端强制核验：① 三类核对完成 ② 加总对账一致 ③ 全部差异已闭环；'
      + '任一不满足将被拒绝并逐项说明。关账后进入会计处理（终态），重算须先作废原单并留痕。',
      { title: '确认关账', danger: true });
    if (!go) return;
    ui.clear(aBody); ui.renderLoading(aBody, '正在执行关账…');
    try {
      const r = await HG.api.post(S.cycleAction(aId.value, 'close'), {}, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(aBody);
      aBody.appendChild(ui.banner('关账成功：结算单 <code>' + F.esc(r.settleNo || '') + '</code>（ID #' + F.esc(String(r.cycleId ?? aId.value))
        + '）已确认——进入会计处理（ACCT 域）。', 'ok'));
      if (aId.value) statusById[Number(aId.value)] = 'CONFIRMED';
      refreshLock();
    } catch (err) { ui.renderError(aBody, err, () => closeBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 3：差异处理（FR-SETTLE-9；R-SETTLE-4 差异闭环红线 + 调整类双人复核）
 * ------------------------------------------------------------------ */
function differenceTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '差异标记与处理（PUT /settle/difference，FR-SETTLE-9）' }));
  card.appendChild(ui.banner(
    '<strong>未闭环的差异不得标记结算完成</strong>（R-SETTLE-4/5——后端关账门禁③强制）；'
      + '差异须逐条定位（不得只给汇总差额）；<strong>调整类（ADJUST）须双人复核</strong>：'
      + '复核人必填且不得为处理人本人（后端强制并留痕，与资金调整同口径）；'
      + '差异处理全过程留痕并进入审计（FR-SETTLE-9 验收 4）。',
    'warn'
  ));
  const dId = numInput('st-d-id', { min: '1', placeholder: '差异号（核对差异清单定位）' });
  const dStatus = selectOf('st-d-status', DIFF_STATUS);
  const dMethod = selectOf('st-d-method', DIFF_METHOD, '未选择');
  const dDesc = ui.el('input', { id: 'st-d-desc', placeholder: '处理说明（定位信息/处理结论）' });
  const dOwner = numInput('st-d-owner', { min: '1', placeholder: '责任人用户 ID' });
  const dReviewer = numInput('st-d-reviewer', { min: '1', placeholder: '复核人（调整类必填，≠处理人）' });
  const dBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '提交差异处理' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-d-id', label: '差异号（必填）', required: true, control: dId }),
    ui.field({ id: 'st-d-status', label: '处理状态', control: dStatus }),
    ui.field({ id: 'st-d-method', label: '处理方式', control: dMethod })
  ]));
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-d-desc', label: '处理说明', control: dDesc }),
    ui.field({ id: 'st-d-owner', label: '责任人', control: dOwner }),
    ui.field({ id: 'st-d-reviewer', label: '复核人（调整类必填）', control: dReviewer }),
    dBtn
  ]));
  const dBody = ui.el('div');
  card.appendChild(dBody);
  container.appendChild(card);

  dBtn.addEventListener('click', async () => {
    if (!dId.value) { ui.toast('差异号必填', 'error'); return; }
    if (dMethod.value === 'ADJUST' && !dReviewer.value) {
      ui.toast('调整类（ADJUST）须双人复核：复核人必填且不得为处理人本人（R-SETTLE-4）', 'error'); return;
    }
    const go = await ui.confirm('确认提交差异 #' + F.esc(dId.value) + ' 的处理（状态 '
      + F.esc(DIFF_STATUS[dStatus.value] || dStatus.value || '—') + '，方式 '
      + F.esc(DIFF_METHOD[dMethod.value] || '—') + '）？<br>差异闭环后方可进入关账（R-SETTLE-5 前置③）。',
      { title: '差异处理' });
    if (!go) return;
    ui.clear(dBody); ui.renderLoading(dBody, '正在提交差异处理…');
    try {
      const r = await HG.api.put(S.DIFFERENCE, {
        id: Number(dId.value), handleStatus: dStatus.value || null,
        handleMethod: dMethod.value || null, handleDesc: dDesc.value.trim() || null,
        ownerId: dOwner.value ? Number(dOwner.value) : null,
        reviewerId: dReviewer.value ? Number(dReviewer.value) : null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(dBody);
      const closed = r.closed === true || r.handleStatus === 'CLOSED';
      dBody.appendChild(ui.banner('差异 #' + F.esc(String(r.differenceId)) + ' 处理状态 → <strong>'
        + F.esc(DIFF_STATUS[r.handleStatus] || r.handleStatus || '—') + '</strong>'
        + (closed ? '——已闭环（关账前置③对该差异达成）。' : '——未闭环：该交易日结算单不得进入「已确认」（FR-SETTLE-9 验收 1）。'),
        closed ? 'ok' : 'warn'));
    } catch (err) { ui.renderError(dBody, err, () => dBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 4：报告与保证金（FR-SETTLE-10/12 报告 / FR-SETTLE-11 保证金视图 + 压力测试）
 * ------------------------------------------------------------------ */
function reportsTab(container) {
  // —— 每日报告（FR-SETTLE-10/12；GET /settle/report）
  const repCard = ui.el('section', { class: 'hg-card' });
  repCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '每日结算报告 / 风控日报（GET /settle/report）' }));
  repCard.appendChild(ui.banner(
    '报告含各子账户结算结果 + 全账户汇总 + 加总对账结论；<strong>加总对账不一致时报告显式标注「不一致/已阻断」，'
      + '不输出正常结论</strong>（FR-SETTLE-10 验收 2）；报告生成后留痕并进入审计；'
      + '风控日报每日必须生成（缺失告警，FR-SETTLE-12）。',
    'warn'
  ));
  const pDate = dateInput('st-p-date');
  const pType = selectOf('st-p-type', REPORT_TYPE);
  const pBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '生成/查询报告' });
  repCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-p-date', label: '交易日（必填）', required: true, control: pDate }),
    ui.field({ id: 'st-p-type', label: '报告类型', control: pType }),
    pBtn
  ]));
  const pBody = ui.el('div');
  repCard.appendChild(pBody);
  container.appendChild(repCard);

  pBtn.addEventListener('click', async () => {
    if (!pDate.value) { ui.toast('交易日必填（yyyy-MM-dd）', 'error'); return; }
    const q = { tradeDate: pDate.value };
    if (pType.value) q.reportType = pType.value;
    ui.clear(pBody); ui.renderLoading(pBody, '正在生成/查询报告…');
    try {
      const r = await HG.api.get(S.REPORT, q);
      ui.clear(pBody);
      const aggBad = r.aggResult && String(r.aggResult).indexOf('INCONSISTENT') !== -1;
      pBody.appendChild(ui.banner(
        '报告 <code>' + F.esc(String(r.id ?? '—')) + '</code>（' + F.esc(REPORT_TYPE[r.reportType] || r.reportType || '—')
          + '，交易日 ' + F.esc(F.date(r.tradeDate) || '—') + '）——加总对账结论：<strong>'
          + F.esc(r.aggResult || '—') + '</strong>'
          + (aggBad ? '——<strong>不一致/已阻断</strong>（FR-SETTLE-10 验收 2，不输出为正常结论）。' : '。'),
        aggBad ? 'error' : 'ok'));
      pBody.appendChild(ui.el('p', { class: 'hg-muted', text: '生成人 #' + String(r.generatedBy ?? '—') + '，生成时间 ' + (F.dateTime(r.generatedTime) || '—') + '（FR-SETTLE-10 验收 3）。' }));
      pBody.appendChild(ui.el('pre', { class: 'hg-pre', text: String(r.contentJson || '（无内容体）') }));
    } catch (err) { ui.renderError(pBody, err, () => pBtn.click()); }
  });

  // —— 保证金与风险度视图（FR-SETTLE-11；GET /settle/margin）
  const mCard = ui.el('section', { class: 'hg-card' });
  mCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '保证金与风险度视图（GET /settle/margin，FR-SETTLE-11）' }));
  mCard.appendChild(ui.banner(
    '子账户视图 + 汇总视图（汇总 = 明细之和，FR-SETTLE-11 验收 1）；风险度超阈值（阈值配置见 CONFIG FR-CONFIG-5）⇒ 告警；'
      + '数据未到达时本视图为空——<strong>不以推算数据替代</strong>（R-SETTLE-3）。',
    'warn'
  ));
  const mDate = dateInput('st-m-date');
  const mSub = numInput('st-m-sub', { min: '1', placeholder: '可空=全子账户' });
  const mTh = ui.el('input', { id: 'st-m-th', inputmode: 'decimal', placeholder: '风险度阈值（可空，缺省取配置）' });
  const mBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询保证金视图' });
  mCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-m-date', label: '交易日（必填）', required: true, control: mDate }),
    ui.field({ id: 'st-m-sub', label: '子账户', control: mSub }),
    ui.field({ id: 'st-m-th', label: '风险度阈值', control: mTh }),
    mBtn
  ]));
  const mBody = ui.el('div');
  mCard.appendChild(mBody);
  container.appendChild(mCard);

  mBtn.addEventListener('click', async () => {
    if (!mDate.value) { ui.toast('交易日必填（yyyy-MM-dd）', 'error'); return; }
    const q = { tradeDate: mDate.value };
    if (mSub.value) q.subAccountId = mSub.value;
    if (mTh.value.trim()) q.threshold = mTh.value.trim();
    ui.clear(mBody); ui.renderLoading(mBody, '正在查询保证金视图…');
    try {
      const r = await HG.api.get(S.MARGIN, q);
      ui.clear(mBody);
      const rows = (r && r.items) || [];
      if (!rows.length) {
        ui.renderEmpty(mBody, '该条件下无保证金视图数据（CTP 日终数据未到达时系统不产出推算数据——FR-SETTLE-1/R-SETTLE-3）。');
        return;
      }
      mBody.appendChild(ui.el('h4', { text: '汇总（Σ 各子账户）' }));
      mBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { title: '项', render: (row) => F.esc(row[0]) },
          { title: '值', num: true, render: (row) => F.esc(row[1]) }
        ],
        [
          ['权益合计', F.money(r.sumEquity)],
          ['保证金占用合计', F.money(r.sumMarginOccupied)],
          ['汇总风险度', F.pct(r.aggregateRiskRatio)],
          ['汇总告警', r.aggregateAlert === true ? '超阈值（告警）' : (r.aggregateAlert === false ? '正常' : '—')]
        ]
      ) }));
      if (r.aggregateAlert === true) {
        mBody.appendChild(ui.banner('汇总风险度超阈值——告警（阈值配置见 CONFIG FR-CONFIG-5；FR-SETTLE-11 验收 2）。', 'error'));
      }
      mBody.appendChild(ui.el('h4', { text: '子账户明细' }));
      mBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'subAccountId', title: '子账户', num: true },
          { title: '权益', num: true, render: (r2) => F.esc(F.money(r2.equity)) },
          { title: '保证金占用', num: true, render: (r2) => F.esc(F.money(r2.marginOccupied)) },
          { title: '风险度', num: true, render: (r2) => F.esc(F.pct(r2.riskRatio)) },
          { title: '告警', render: (r2) => yesNoHTML(r2.alert, '超阈值', '正常') }
        ],
        rows
      ) }));
    } catch (err) { ui.renderError(mBody, err, () => mBtn.click()); }
  });

  // —— 场景化压力测试（FR-SETTLE-11 / R-SETTLE-7；POST /settle/stress-test）
  const sCard = ui.el('section', { class: 'hg-card' });
  sCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '场景化压力测试（POST /settle/stress-test，FR-SETTLE-11）' }));
  sCard.appendChild(ui.banner(
    '按情景参数（价格不利变动幅度，R-SETTLE-7；情景参数可配置见 CONFIG）估算保证金缺口与追加需求；'
      + '结果落库并进入报告。子账户留空 = 全账户口径。',
    'warn'
  ));
  const sSub = numInput('st-s-sub', { min: '1', placeholder: '可空=全账户' });
  const sDate = dateInput('st-s-date');
  const sShock = ui.el('input', { id: 'st-s-shock', inputmode: 'decimal', placeholder: '价格不利变动幅度（小数，如 0.1 = 10%）' });
  const sBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '执行压力测试' });
  sCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-s-sub', label: '子账户', control: sSub }),
    ui.field({ id: 'st-s-date', label: '测试基准日（必填）', required: true, control: sDate }),
    ui.field({ id: 'st-s-shock', label: '不利变动幅度', control: sShock }),
    sBtn
  ]));
  const sBody = ui.el('div');
  sCard.appendChild(sBody);
  container.appendChild(sCard);

  sBtn.addEventListener('click', async () => {
    if (!sDate.value) { ui.toast('测试基准日必填（yyyy-MM-dd）', 'error'); return; }
    if (sShock.value.trim() && !(Number(sShock.value) >= 0 && Number(sShock.value) <= 1)) {
      ui.toast('不利变动幅度须为 0~1 的小数（如 0.1 = 10%）', 'error'); return;
    }
    const go = await ui.confirm('确认执行压力测试（基准日 ' + F.esc(sDate.value)
      + (sSub.value ? '，子账户 #' + F.esc(sSub.value) : '，全账户')
      + (sShock.value.trim() ? '，不利变动 ' + F.esc(sShock.value.trim()) : '') + '）？', { title: '压力测试' });
    if (!go) return;
    ui.clear(sBody); ui.renderLoading(sBody, '正在执行压力测试…');
    try {
      const r = await HG.api.post(S.STRESS_TEST, {
        subAccountId: sSub.value ? Number(sSub.value) : null,
        testDate: sDate.value, priceShockPct: sShock.value.trim() || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(sBody);
      sBody.appendChild(ui.banner('压力测试完成（记录 #' + F.esc(String(r.id ?? '—')) + '）——情景 ' + F.esc(F.date(r.testDate) || '—') + '。', 'ok'));
      sBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { title: '项', render: (row) => F.esc(row[0]) },
          { title: '值', num: true, render: (row) => F.esc(row[1]) }
        ],
        [
          ['保证金缺口（marginGap）', F.money(r.marginGap)],
          ['追加需求（additionalRequirement）', F.money(r.additionalRequirement)],
          ['情景后权益（equityAfter）', F.money(r.equityAfter)],
          ['情景后风险度（riskRatioAfter）', F.pct(r.riskRatioAfter)]
        ]
      ) }));
    } catch (err) { ui.renderError(sBody, err, () => sBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 5：核对任务与电子签署（FR-SETTLE-13/14/17；R-SETTLE-11 / Q-14）
 * ------------------------------------------------------------------ */
function checksTab(container) {
  // —— 核对任务查询（GET monthly-check / quarterly-report；FR-AUDIT-13 查询面）
  const qCard = ui.el('section', { class: 'hg-card' });
  qCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '核对任务查询（FR-SETTLE-17 / FR-AUDIT-13）' }));
  qCard.appendChild(ui.banner(
    '核对任务按周期生成并指派应签角色：月度 = 风控 + 交易 + 财务三方各自签署；季度 = 风控编制 + 交易会签 → 管理层签收；'
      + '年度 = 审计岗全覆盖。<strong>逾期未签告警并进审计报表</strong>（R-SETTLE-11，不得静默缺失）。',
    'warn'
  ));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE };
  const qEp = ui.el('select', { id: 'st-q-ep' });
  Object.keys(CHECK_ENDPOINT).forEach((k) => qEp.appendChild(ui.el('option', { value: k, text: CHECK_ENDPOINT[k].label })));
  const qType = selectOf('st-q-type', CHECK_TYPE, '全部类型');
  const qPeriod = ui.el('input', { id: 'st-q-period', placeholder: '期间键（如 2026-09 / 2026-Q3 / 2026）' });
  const qSub = numInput('st-q-sub', { min: '0', placeholder: '可空（0=跨子账户核对）' });
  const qBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询核对任务' });
  qCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-q-ep', label: '任务端点', control: qEp }),
    ui.field({ id: 'st-q-type', label: '核对类型', control: qType }),
    ui.field({ id: 'st-q-period', label: '期间键', control: qPeriod }),
    ui.field({ id: 'st-q-sub', label: '子账户', control: qSub }),
    qBtn
  ]));
  const qBody = ui.el('div');
  const qPager = ui.el('div', { class: 'hg-pager' });
  qCard.appendChild(qBody);
  qCard.appendChild(qPager);
  container.appendChild(qCard);

  async function load(page) {
    state.page = page || 1;
    const ep = CHECK_ENDPOINT[qEp.value].path;
    const q = { page: state.page, size: state.size, sort: 'generated_time,desc' };
    if (qType.value) q.checkType = qType.value;
    if (qPeriod.value.trim()) q.periodKey = qPeriod.value.trim();
    if (qSub.value !== '') q.subAccountId = qSub.value;
    ui.clear(qBody); ui.renderLoading(qBody, '正在查询核对任务…');
    try {
      const data = await HG.api.get(ep, q);
      attachPager(qPager, { total: data.total, page: data.page, size: data.size }, load);
      ui.clear(qBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(qBody, '该筛选条件下无核对任务（核对记录缺失须告警——R-SETTLE-6，不得静默缺失）。'); return; }
      qBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'id', title: '任务 ID', num: true },
          { title: '类型', render: (r) => F.esc(CHECK_TYPE[r.checkType] || r.checkType || '—') },
          { key: 'periodKey', title: '期间' },
          { title: '子账户', num: true, render: (r) => F.esc(r.subAccountId === 0 ? '跨子账户' : String(r.subAccountId ?? '—')) },
          { title: '应签角色', render: (r) => F.esc(String(r.requiredSignRoles || '—').split(',').map((c) => SIGN_ROLE[c.trim()] || c.trim()).join('、')) },
          { title: '已签角色', render: (r) => F.esc(String(r.signedRoles || '').split(',').filter(Boolean).map((c) => SIGN_ROLE[c.trim()] || c.trim()).join('、') || '—') },
          { title: '状态', render: (r) => (r.status === 'COMPLETED'
            ? '<span class="hg-badge hg-badge--active">已完成</span>'
            : (r.status === 'OVERDUE'
              ? '<span class="hg-badge hg-badge--danger">已逾期</span>'
              : '<span class="hg-badge hg-badge--pending">' + F.esc(TASK_STATUS[r.status] || r.status || '—') + '</span>')) },
          { title: '快照哈希', render: (r) => F.esc(r.snapshotHash ? String(r.snapshotHash).slice(0, 16) + '…' : '—') },
          { title: '签署截止', render: (r) => F.esc(F.date(r.deadlineDate) || '—') },
          { title: '逾期告警', render: (r) => yesNoHTML(r.overdueAlerted === 'Y' || r.overdueAlerted === true, '已告警', '未触发') },
          { title: '完成时间', render: (r) => F.esc(F.dateTime(r.completedTime) || '—') }
        ],
        data.items
      ) }));
      // 签署记录明细（账号/角色/时间/IP/快照哈希——FR-SETTLE-17 验收 2；不可代签留痕 proxyRejected）
      const sigRows = [];
      data.items.forEach((t) => (t.signatures || []).forEach((g) => sigRows.push(Object.assign({ taskId: t.id }, g))));
      if (sigRows.length) {
        qBody.appendChild(ui.el('h4', { text: '签署记录（账号 / 角色 / 时间 / IP / 快照哈希）' }));
        qBody.appendChild(ui.el('div', { html: ui.tableHTML(
          [
            { title: '任务', num: true, render: (g) => F.esc(String(g.taskId)) },
            { title: '签署角色', render: (g) => F.esc(SIGN_ROLE[g.signRole] || g.signRole || '—') },
            { title: '签署人', num: true, render: (g) => F.esc(String(g.signerId ?? '—')) },
            { title: '实际角色码', render: (g) => F.esc(g.signerRoleCode || '—') },
            { title: '签署时间', render: (g) => F.esc(F.dateTime(g.signTime) || '—') },
            { title: '签署 IP', render: (g) => F.esc(g.signIp || '—') },
            { title: '快照哈希', render: (g) => F.esc(g.snapshotHash ? String(g.snapshotHash).slice(0, 16) + '…' : '—') },
            { title: '结论', render: (g) => F.esc(g.signResult || '—') },
            { title: '代签拦截', render: (g) => (g.proxyRejected === 'Y' || g.proxyRejected === true
              ? '<span class="hg-badge hg-badge--danger">已拦截（不可代签）</span>'
              : (g.proxyRejected === 'N' || g.proxyRejected === false ? '—' : F.esc(String(g.proxyRejected ?? '—')))) },
            { title: '管理层互斥核验', render: (g) => (g.manageRoleTradeBlocked === 'Y'
              ? '<span class="hg-badge hg-badge--frozen">已核验（只签收不操盘）</span>'
              : (g.manageRoleTradeBlocked === 'N' ? '—' : F.esc(String(g.manageRoleTradeBlocked ?? '—')))) }
          ],
          sigRows
        ) }));
      }
    } catch (err) { ui.renderError(qBody, err, () => load(state.page)); }
  }
  qBtn.addEventListener('click', () => load(1));

  // —— 生成核对任务（POST action=GENERATE；FR-SETTLE-17 验收 1）
  const genCard = ui.el('section', { class: 'hg-card' });
  genCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '生成核对任务（POST action=GENERATE）' }));
  genCard.appendChild(ui.banner(
    '同一（核对类型，期间键，子账户）已存在时直接返回既有任务（后端幂等，FR-SETTLE-17）；'
      + '核对对象快照哈希为签署对象指纹——<strong>签署后快照变更须重新签署</strong>（FR-SETTLE-17 验收 4）。',
    'warn'
  ));
  const gEp = ui.el('select', { id: 'st-g-ep' });
  Object.keys(CHECK_ENDPOINT).forEach((k) => gEp.appendChild(ui.el('option', { value: k, text: CHECK_ENDPOINT[k].label })));
  const gType = selectOf('st-g-type', CHECK_TYPE);
  const gPeriod = ui.el('input', { id: 'st-g-period', placeholder: '期间键（必填，如 2026-09 / 2026-Q3）' });
  const gSub = numInput('st-g-sub', { min: '0', placeholder: '缺省 0=跨子账户核对' });
  const gHash = ui.el('input', { id: 'st-g-hash', placeholder: '核对对象快照哈希（未装配快照提供方时必填）' });
  const gBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '生成核对任务' });
  genCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-g-ep', label: '任务端点', control: gEp }),
    ui.field({ id: 'st-g-type', label: '核对类型', control: gType }),
    ui.field({ id: 'st-g-period', label: '期间键（必填）', required: true, control: gPeriod })
  ]));
  genCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-g-sub', label: '子账户', control: gSub }),
    ui.field({ id: 'st-g-hash', label: '快照哈希', control: gHash }),
    gBtn
  ]));
  const gBody = ui.el('div');
  genCard.appendChild(gBody);
  container.appendChild(genCard);

  gBtn.addEventListener('click', async () => {
    if (!gPeriod.value.trim()) { ui.toast('期间键必填（如 2026-09 / 2026-Q3 / 2026）', 'error'); return; }
    const go = await ui.confirm('确认生成核对任务（'
      + F.esc(CHECK_TYPE[gType.value] || gType.value) + '，期间 ' + F.esc(gPeriod.value.trim()) + '）？', { title: '生成核对任务' });
    if (!go) return;
    ui.clear(gBody); ui.renderLoading(gBody, '正在生成核对任务…');
    try {
      const r = await HG.api.post(CHECK_ENDPOINT[gEp.value].path, {
        action: 'GENERATE', checkType: gType.value || null, periodKey: gPeriod.value.trim(),
        subAccountId: gSub.value === '' ? null : Number(gSub.value),
        snapshotHash: gHash.value.trim() || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(gBody);
      gBody.appendChild(ui.banner('核对任务已生成：任务 #' + F.esc(String(r.id)) + '（'
        + F.esc(CHECK_TYPE[r.checkType] || r.checkType || '—') + '，期间 ' + F.esc(r.periodKey || '—')
        + '，状态 <strong>' + F.esc(TASK_STATUS[r.status] || r.status || '—') + '</strong>）。', 'ok'));
    } catch (err) { ui.renderError(gBody, err, () => gBtn.click()); }
  });

  // —— 电子签署（POST；R-SETTLE-11 红线）
  const signCard = ui.el('section', { class: 'hg-card' });
  signCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '电子签署（POST monthly-check / quarterly-report）' }));
  signCard.appendChild(ui.banner(
    '<strong>不可代签（R-SETTLE-11/Q-14）</strong>：签署角色须显式选择（不猜测不默认），'
      + '须为应签角色本人账号（后端校验用户实际角色并置 proxy_rejected、记录越权尝试）；'
      + '<strong>管理层只签收报告、不得操盘</strong>（签署权与交易权互斥，系统强制）；'
      + '签署对象为核对时点快照哈希——签署后快照变更须重新签署。同一任务同一角色只签一次。',
    'warn'
  ));
  const sEp = ui.el('select', { id: 'st-s2-ep' });
  Object.keys(CHECK_ENDPOINT).forEach((k) => sEp.appendChild(ui.el('option', { value: k, text: CHECK_ENDPOINT[k].label })));
  const sTask = numInput('st-s2-task', { min: '1', placeholder: '核对任务 ID' });
  const sRole = selectOf('st-s2-role', SIGN_ROLE);
  const sOpinion = ui.el('input', { id: 'st-s2-opinion', placeholder: '签署意见 / 异议说明（可空）' });
  const sHash = ui.el('input', { id: 'st-s2-hash', placeholder: '签署时当前快照哈希（快照变更检测用）' });
  const sBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '提交签署' });
  signCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-s2-ep', label: '任务端点', control: sEp }),
    ui.field({ id: 'st-s2-task', label: '任务 ID（必填）', required: true, control: sTask }),
    ui.field({ id: 'st-s2-role', label: '签署角色（必填，显式选择）', required: true, control: sRole })
  ]));
  signCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-s2-opinion', label: '签署意见', control: sOpinion }),
    ui.field({ id: 'st-s2-hash', label: '当前快照哈希', control: sHash }),
    sBtn
  ]));
  const sBody = ui.el('div');
  signCard.appendChild(sBody);
  container.appendChild(signCard);

  sBtn.addEventListener('click', async () => {
    if (!sTask.value) { ui.toast('任务 ID 必填', 'error'); return; }
    if (!sRole.value) { ui.toast('签署角色必填——须显式选择，不猜测不默认（R-SETTLE-11）', 'error'); return; }
    const go = await ui.confirm('确认以「' + F.esc(SIGN_ROLE[sRole.value]) + '」身份对任务 #' + F.esc(sTask.value) + ' 电子签署？'
      + '<br>不可代签：须为该角色本人账号，越权尝试将被记录；管理层仅签收（签署权与交易权互斥）。', { title: '电子签署' });
    if (!go) return;
    ui.clear(sBody); ui.renderLoading(sBody, '正在提交签署…');
    try {
      const r = await HG.api.post(CHECK_ENDPOINT[sEp.value].path, {
        taskId: Number(sTask.value), signRole: sRole.value,
        signOpinion: sOpinion.value.trim() || null, snapshotHash: sHash.value.trim() || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(sBody);
      const done = r.status === 'COMPLETED';
      sBody.appendChild(ui.banner('签署已受理：任务 #' + F.esc(String(r.taskId)) + '，本次角色「'
        + F.esc(SIGN_ROLE[r.signRole] || r.signRole || '—') + '」（签署人 #' + F.esc(String(r.signerId ?? '—')) + '）——任务状态 → <strong>'
        + F.esc(TASK_STATUS[r.status] || r.status || '—') + '</strong>'
        + (done ? '（应签角色签署齐备）。' : '（已签：' + (String(r.signedRoles || '').split(',').filter(Boolean).map((c) => SIGN_ROLE[c.trim()] || c.trim()).join('、') || '—') + '）。'),
        done ? 'ok' : 'warn'));
      if (done) load(1);
    } catch (err) { ui.renderError(sBody, err, () => sBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 6：导出（FR-SETTLE-16；GET /settle/export —— JSON 信封，浏览器侧转 CSV 下载，
 *        张力披露见文件头「导出形态张力披露」）
 * ------------------------------------------------------------------ */
/** CSV 单元格转义（含逗号/引号/换行则包裹双引号）。 */
function csvCell(v) {
  const s = v === null || v === undefined ? '' : String(v);
  return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}

function exportTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '导出结算报表（GET /settle/export，FR-SETTLE-16）' }));
  card.appendChild(ui.banner(
    '导出仅覆盖<strong>有权限的子账户范围</strong>（服务端可见性强制；FR-SETTLE-16 验收 4）；'
      + '导出内容含<strong>加总对账结论</strong>列与<strong>导出时间/导出人</strong>（验收 1/2）；'
      + '无数据时不生成文件并给出提示（验收 3）。'
      + '<strong>形态披露</strong>：后端当前为 JSON 信封承载，本页在浏览器侧转换为 CSV 下载；'
      + 'CSV 直返化须后端变更承接（T-193/T-195 先例），张力已披露待裁（工单要求 1③）。',
    'warn'
  ));
  const eFrom = dateInput('st-e-from');
  const eTo = dateInput('st-e-to');
  const eSub = numInput('st-e-sub', { min: '1', placeholder: '可空' });
  const eBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '导出结算报表' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'st-e-from', label: '交易日起', control: eFrom }),
    ui.field({ id: 'st-e-to', label: '交易日止', control: eTo }),
    ui.field({ id: 'st-e-sub', label: '子账户', control: eSub }),
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
    ui.clear(eBody); ui.renderLoading(eBody, '正在生成结算报表导出…');
    try {
      const r = await HG.api.get(S.EXPORT, q);
      ui.clear(eBody);
      const columns = (r && r.columns) || [];
      const rows = (r && r.rows) || [];
      if (!rows.length) {
        ui.renderEmpty(eBody, '无数据——不生成导出文件（FR-SETTLE-16 验收 3）。');
        return;
      }
      const csv = columns.map(csvCell).join(',') + '\r\n'
        + rows.map((row) => columns.map((c) => csvCell(row[c])).join(',')).join('\r\n');
      eBody.appendChild(ui.banner('导出就绪：数据 ' + F.esc(String(rows.length)) + ' 行 / ' + F.esc(String(columns.length))
        + ' 列（导出时间 ' + F.esc(F.dateTime(r.exportTime) || '—') + '，导出人 #' + F.esc(String(r.exportedBy ?? '—'))
        + '），正在触发 CSV 下载…', 'ok'));
      eBody.appendChild(ui.el('div', { html: ui.tableHTML(
        columns.map((c) => ({ key: c, title: c })),
        rows.slice(0, 50)
      ) }));
      if (rows.length > 50) eBody.appendChild(ui.el('p', { class: 'hg-muted', text: '（预览前 50 行，完整 ' + rows.length + ' 行见下载文件）' }));
      const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv; charset=UTF-8' });
      triggerCsvDownload(blob, 'settle-report.csv');
      ui.toast('结算报表已导出（CSV）', 'ok');
    } catch (err) { ui.renderError(eBody, err, () => eBtn.click()); }
  });
}

/** 触发浏览器下载（fund.js 同构；探针 DOM 桩下 appendChild 为 no-op 安全）。 */
function triggerCsvDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'settle-report.csv';
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
  { key: 'ctp', label: 'CTP 日终数据', build: ctpTab },
  { key: 'cycles', label: '结算单', build: cyclesTab },
  { key: 'difference', label: '差异处理', build: differenceTab },
  { key: 'reports', label: '报告与保证金', build: reportsTab },
  { key: 'checks', label: '核对任务与签署', build: checksTab },
  { key: 'export', label: '导出', build: exportTab }
];

function renderSettle(root, ctx) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '结算管理（SETTLE）' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: 'CTP 日终数据接收 / 子账户独立结算 / 三类核对 / 加总对账（Σ 子账户 = 真实账户）/ 关账 / 差异处理 / 报告与保证金 / 核对任务与电子签署 / 导出（FR-SETTLE-1~17）。' }));
  root.appendChild(ui.banner(
    '<strong>界面边界声明</strong>：本页仅承载界面，不做业务判定——关账门禁三前置（R-SETTLE-5）、'
      + '加总对账阻断范围（仅差异所属子账户、不阻断平仓，Q-11）、差异双人复核、签署不可代签与'
      + '管理层签署权/交易权互斥<strong>一律由后端强制</strong>。CTP 日终数据为唯一权威基准（R-SETTLE-3），'
      + '未到达时不得以推算数据替代；结算数据只增不改不删（R-SETTLE-10），本页无任何编辑/删除入口。',
    'warn'
  ));

  const tabBar = ui.el('div', { class: 'hg-tabs', role: 'tablist' });
  const panel = ui.el('div');
  let active = (ctx && ctx.query && ctx.query.tab) || 'ctp';
  if (!TABS.some((t) => t.key === active)) active = 'ctp';

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
    const b = ui.el('button', { type: 'button', role: 'tab', text: t.label, onclick: () => HG.router.navigate('/settle?tab=' + t.key) });
    b.dataset.key = t.key;
    tabBar.appendChild(b);
  });

  root.appendChild(tabBar);
  root.appendChild(panel);
  select(active);
}

HG.pages = HG.pages || {};
HG.pages.settle = { renderSettle };
HG.router.register('/settle', renderSettle);
export default HG.pages.settle;
