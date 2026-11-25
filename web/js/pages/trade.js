/*
 * 交易执行页面（TRADE）—— 管理界面（FR-TRADE-1~18；T-053）。
 *
 * ## 端点对接面（**不对称盘点**，以 java/src/main/java/com/hedgeguard/web 实测为准）
 *   ApiRoutes.java TRADE **路径表**登记 12 路径，但 handler/trade/TradeRoutes.java（T-046）
 *   仅注册 **3 对写入面** ⇒ **可接 3 端点**（本页全部真实对接）：
 *     POST /trade/order                  提交交易指令（不落库，经桥接下发 C++；FR-TRADE-1）
 *     POST /trade/order/{id}/confirm     人工确认后发出（FR-TRADE-2；confirmToken 线格式必填）
 *     POST /trade/order/{id}/cancel      撤单（FR-TRADE-6；仅未成交部分，reason 可选）
 *   ⇒ **未注册 9 路径**（登记于 routes.js NOT_REGISTERED，**调用即 404**——本页保留 Tab 框架、
 *     数据面接「未注册」诚实提示，不臆造调用；缺口只报不改，属后续立号承接）：
 *     /trade/order-report、/trade/fill、/trade/position、/trade/reason、/trade/reject-record、
 *     /trade/flow、/trade/quote/{instrument}、/trade/strategy、/trade/export
 *
 * ## 业务红线（写进页面逻辑）
 *   · R-TRADE-1 正当性红线：交易指令必须显式携带交易计划 ID 与子账户标识——提交表单两字段必填，
 *     缺失即前端预检拒绝（不发请求）；不得由会话间接推导（表单显式输入）。
 *   · R-TRADE-2 人工确认门禁：系统不提供自动下单——本页无任何自动下单入口；
 *     提交后指令为 PENDING_CONFIRM，须在「指令动作」Tab 人工确认（confirmToken 必填）后才发往 CTP。
 *   · R-TRADE-9 交易理由结构化必填（CFG-13/CH-002/CH-004）：①关联类型必选（8 值）②关联对象必选
 *     （关联计划 ID + 实货敞口 ID——**提交路径**缺任一即前端预检拒绝，同子账户校验在后端三层）③时点
 *     说明必选（5 值）；可选补充说明。
 *   · R-TRADE-15 只增不改不删：委托/成交/流水/拒绝记录页面均只读（未注册 Tab 亦无编辑入口）。
 *   · R-TRADE-11 行情零外部依赖且不驱动决策：行情只经 /ws/quote（CTP 源）展示；
 *     **本页无任何「按行情自动下单」能力**（行情面板与下单表单不联动自动提交）。
 *   · Q-15 价格偏离：基准 = 昨结算价，(2%,5%] 告警 + 二次确认（confirmToken）、> 5% 阻断——
 *     判定在 C++ 事前风控；前端仅提供 confirmToken 输入与阻断错误四项渲染（R-RISK-3 同型）。
 *
 * ## WS 接线（ARCHITECTURE.md §4.1.2 W-1 + CH-007）
 *   /ws/quote（允许丢帧，仅推送最新值）：订阅 channels=["quote"] + instruments 白名单（大写归一，ws.js）；
 *   补数批次行情帧由服务端消费侧丢弃（CH-007 接收侧不消费），前端无需区分来源、禁止把陈旧价计入新鲜度；
 *   渲染经 HG.ws.throttle 节流（前端纪律 §5.3，禁止高频全量重建 DOM）；行情面板标注数据时间。
 *   防僵尸连接：与 risk.js 同型——模块级存活引用，重建面板前 close 上一次连接。
 *
 * ## 幂等纪律
 *   写操作（下单 / 确认 / 撤单）一律携带 Idempotency-Key（auth.js / risk.js 先例）。
 *
 * ## 预填（NFR-USE-2 ≤5 步）
 *   从套保计划「下单」进入：planId / subAccountId / productId / direction / tradePlanId / instrumentId
 *   经 query 预填（plan.js goOrder 先例）；套保方向 BUY_HEDGE/SELL_HEDGE 推导下单方向 SELL/BUY。
 *
 * 纪律：不直连 DB / C++，只经 HG.api；失败统一经 HG.errmsg；数据界面必配 加载 / 空 / 错误 三态。
 */
import { HG } from '../core/ns.js';
import '../core/format.js';
import '../core/router.js';
import '../core/routes.js';
import '../core/ui.js';
import '../core/ws.js';

const ui = HG.ui;
const F = HG.format;
const RT = HG.routes.TRADE;
const NR = HG.routes.NOT_REGISTERED;

/* ------------------------------------------------------------------ *
 * 取值域（以 TradeService 实测为准：RELATED_TYPES 8 值 / TIME_DESCS 5 值 /
 * DIRECTIONS BUY|SELL / OFFSET_FLAGS OPEN|CLOSE|CLOSE_TODAY / PRICE_TYPES LIMIT|MARKET）
 * ------------------------------------------------------------------ */
/** 交易理由关联类型（ck_trade_reason_related_type，8 值；前 6 值为提交路径主用）。 */
const RELATED_TYPES = {
  NEW_EXPOSURE: '敞口新增', EXPOSURE_DECREASE: '敞口减少', PLAN_EXECUTION: '计划执行',
  ROLLOVER: '展期置换', RISK_DISPOSAL: '风险处置', ERROR_CORRECTION: '错误更正',
  FORCE_CLOSE: '强制平仓（CTP 直生）', DELIVERY: '交割（CTP 直生）'
};
/** 交易理由时点说明（ck_trade_reason_time_desc，5 值）。 */
const TIME_DESCS = {
  CONTRACT_SIGNED: '合同签约', PRICE_TRIGGERED: '价格触发', MONTHLY_REBALANCE: '月度调仓',
  EXPIRY_ROLLOVER: '到期展期', RISK_REQUIRED: '风控要求'
};
/** 开平标志。 */
const OFFSET_FLAGS = { OPEN: '开仓', CLOSE: '平仓', CLOSE_TODAY: '平今' };
/** 价格类型。 */
const PRICE_TYPES = { LIMIT: '限价', MARKET: '市价' };
/** 指令状态（TradeService.STATUS_* 实测子集；下单受理恒 PENDING_CONFIRM）。 */
const ORDER_STATUS = {
  PENDING_CONFIRM: '待确认', REJECTED_PRECHECK: '已拒绝（事前风控）',
  CANCELLED: '已撤销', EXPIRED: '已失效', SENT_CTP: '已发送 CTP'
};

/** 套保方向 → 下单方向推导（HEDGE_DIR_TO_TRADE 既有口径）。 */
const HEDGE_DIR_TO_TRADE = { BUY_HEDGE: 'SELL', SELL_HEDGE: 'BUY' };

/** 状态徽标。 */
function statusBadgeHTML(status) {
  const t = ORDER_STATUS[status] || status || '—';
  const cls = status === 'REJECTED_PRECHECK' ? 'danger' : (status === 'SENT_CTP' ? 'active' : 'pending');
  return '<span class="hg-badge hg-badge--' + cls + '" data-order-status="' + F.esc(status || '') + '">' + F.esc(t) + '</span>';
}

/** 下单流程说明（≤5 步，NFR-USE-2；Q-13/Q-15 口径文案）。 */
const FLOW_NOTE_HTML = '下单流程（≤ 5 步）：① 选计划（预填）→ ② 填数量/价格 → ③ 填交易理由'
  + '（结构化必填：关联类型 + 关联对象〔计划 ID + 实货敞口 ID，须同子账户——后端三层校验〕+ 时点说明）'
  + ' → ④ 提交（待确认，不自动发出）→ ⑤ 人工确认（confirmToken）后发往 CTP。'
  + '价格偏离（Q-15）：基准 = 昨结算价；(2%,5%] 告警 + 二次确认（confirmToken）；> 5% 阻断——判定在 C++ 事前风控。';

/** 只提示不自动下单横幅（R-TRADE-2 / PROJECT.md §4.2）。 */
const NO_AUTO_ORDER_BANNER_HTML = '<strong>系统不做自动交易（R-TRADE-2）</strong>：'
  + '每笔指令（含期权各腿）必须经交易岗显式人工确认后方可发往 CTP；提交成功仅产生「待确认」指令，'
  + '<strong>本页无任何自动下单入口</strong>。';

function numInput(id, props) {
  return ui.el('input', Object.assign({ id: id, type: 'number', inputmode: 'numeric' }, props || {}));
}
function textInput(id, props) {
  return ui.el('input', Object.assign({ id: id }, props || {}));
}
function enumSelect(id, map, opts) {
  const sel = ui.el('select', Object.assign({ id: id }, (opts && opts.attrs) || {}));
  if (!opts || !opts.noBlank) sel.appendChild(ui.el('option', { value: '', text: (opts && opts.blankText) || '请选择' }));
  Object.keys(map).forEach((k) => sel.appendChild(ui.el('option', { value: k, text: map[k] })));
  return sel;
}

/* ------------------------------------------------------------------ *
 * Tab 1：交易下单（POST /trade/order；FR-TRADE-1/10/12 预填与理由）
 * ------------------------------------------------------------------ */
function orderTab(container, ctx) {
  const q = (ctx && ctx.query) || {};
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '提交交易指令（POST /trade/order）' }));
  card.appendChild(ui.banner(
    '<strong>绑定红线（R-TRADE-1）</strong>：交易指令必须显式携带<strong>交易计划 ID</strong>与<strong>子账户标识</strong>——'
      + '缺任一项后端一律拒绝，本页预检亦拒绝（不得由会话间接推导）。<br>' + NO_AUTO_ORDER_BANNER_HTML
      + '<br>每次提交自动携带幂等标识（Idempotency-Key）。',
    'warn'
  ));
  card.appendChild(ui.el('p', { class: 'hg-muted', html: FLOW_NOTE_HTML }));

  // 预填区（NFR-USE-2：计划页「下单」跳入时展示；只读描述，不改写输入）
  const prefill = {
    planId: q.planId || '', tradePlanId: q.tradePlanId || '', subAccountId: q.subAccountId || '',
    productId: q.productId || '', direction: q.direction || '', instrumentId: q.instrumentId || ''
  };
  if (prefill.planId || prefill.tradePlanId) {
    const pf = ui.el('div', { class: 'hg-card' });
    pf.appendChild(ui.el('h4', { text: '下单预填参数（来自套保计划，NFR-USE-2）' }));
    const dl = ui.el('dl', { class: 'hg-desc' });
    [['套保计划 ID', prefill.planId || '（缺失）'],
     ['交易计划 ID', prefill.tradePlanId || '（未指定）'],
     ['子账户', prefill.subAccountId || '（缺失）'],
     ['品种', prefill.productId || '（缺失）'],
     ['套保方向', prefill.direction || '（缺失）'],
     ['推导下单方向', HEDGE_DIR_TO_TRADE[prefill.direction] || prefill.direction || '—'],
     ['合约', prefill.instrumentId || '（未指定，待选合约）']
    ].forEach((r) => { dl.appendChild(ui.el('dt', { text: r[0] })); dl.appendChild(ui.el('dd', { text: String(r[1]) })); });
    pf.appendChild(dl);
    container.appendChild(pf);
  }

  // —— 表单（字段名以 TradeService.SubmitRequest 实测 camelCase 为准）
  const fSub = numInput('tr-o-sub', { min: '1', placeholder: '子账户标识（显式携带，R-TRADE-1）' });
  const fPlan = numInput('tr-o-plan', { min: '1', placeholder: '交易计划 ID（tradePlanId，必填）' });
  const fExposure = numInput('tr-o-exp', { min: '1', placeholder: '实货敞口 ID（可空；权威校验以理由内为准）' });
  const fProduct = numInput('tr-o-prod', { min: '1', placeholder: '品种 ID（可空；非空时须与计划一致）' });
  const fInstr = textInput('tr-o-instr', { placeholder: '合约代码（instrumentId，如 cu2506；必填）' });
  const fDir = enumSelect('tr-o-dir', { BUY: '买入（BUY）', SELL: '卖出（SELL）' });
  const fOffset = enumSelect('tr-o-offset', OFFSET_FLAGS);
  const fPriceType = enumSelect('tr-o-ptype', PRICE_TYPES);
  const fPrice = textInput('tr-o-price', { placeholder: '委托价（限价单必填；字符串化十进制）' });
  const fVol = numInput('tr-o-vol', { min: '1', placeholder: '数量（手，必填且 >0；累计不得超计划剩余可下单量）' });
  const fToken = textInput('tr-o-token', { placeholder: '二次确认令牌（价格偏离告警区间时必填，0x1018）' });

  // 预填回填（输入可改；套保方向推导仅作展示提示，仍由用户显式选择）
  if (prefill.subAccountId) fSub.value = String(prefill.subAccountId);
  if (prefill.tradePlanId) fPlan.value = String(prefill.tradePlanId);
  if (prefill.productId) fProduct.value = String(prefill.productId);
  if (prefill.instrumentId) fInstr.value = String(prefill.instrumentId);

  const submitBtn = ui.el('button', { class: 'hg-btn hg-btn--danger', type: 'button', text: '提交交易指令' });

  // —— 交易理由（R-TRADE-9 结构化必填：relatedType / relatedPlanId / relatedExposureId / timeDesc）
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'tr-o-sub', label: '子账户标识（必填，显式携带）', required: true, control: fSub }),
    ui.field({ id: 'tr-o-plan', label: '交易计划 ID（必填）', required: true, control: fPlan }),
    ui.field({ id: 'tr-o-exp', label: '实货敞口 ID（可空）', control: fExposure }),
    ui.field({ id: 'tr-o-prod', label: '品种 ID（可空）', control: fProduct })
  ]));
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'tr-o-instr', label: '合约代码（必填）', required: true, control: fInstr }),
    ui.field({ id: 'tr-o-dir', label: '买卖方向（须与计划一致）', control: fDir }),
    ui.field({ id: 'tr-o-offset', label: '开平标志', control: fOffset }),
    ui.field({ id: 'tr-o-ptype', label: '价格类型', control: fPriceType }),
    ui.field({ id: 'tr-o-price', label: '委托价（限价必填）', control: fPrice }),
    ui.field({ id: 'tr-o-vol', label: '数量（手，必填）', required: true, control: fVol })
  ]));
  card.appendChild(ui.field({ id: 'tr-o-token', label: '二次确认令牌（价格偏离告警区间）', control: fToken }));

  const reasonCard = ui.el('section', { class: 'hg-card' });
  reasonCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '交易理由（结构化必填，R-TRADE-9 / CFG-13）' }));
  reasonCard.appendChild(ui.banner(
    '<strong>提交路径强制（CH-004）</strong>：① 关联类型必选 ② <strong>关联对象必选</strong>'
      + '（关联计划 ID + 实货敞口 ID——缺任一即拒绝提交；同子账户校验在后端三层：提交时 Java 权威 → '
      + '下发前 C++ 0x1019 兜底 → 落库复核）③ 时点说明必选。理由不得事后静默修改。',
    'warn'
  ));
  const rType = enumSelect('tr-r-type', RELATED_TYPES);
  const rPlan = numInput('tr-r-plan', { min: '1', placeholder: '关联计划 ID（套保计划，提交路径必填）' });
  const rExposure = numInput('tr-r-exp', { min: '1', placeholder: '关联实货敞口 ID（提交路径必填）' });
  const rTime = enumSelect('tr-r-time', TIME_DESCS);
  const rText = textInput('tr-r-text', { placeholder: '可选补充说明' });
  reasonCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'tr-r-type', label: '关联类型（必选）', required: true, control: rType }),
    ui.field({ id: 'tr-r-time', label: '时点说明（必选）', required: true, control: rTime })
  ]));
  reasonCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'tr-r-plan', label: '关联计划 ID（必填）', required: true, control: rPlan }),
    ui.field({ id: 'tr-r-exp', label: '关联实货敞口 ID（必填）', required: true, control: rExposure }),
    ui.field({ id: 'tr-r-text', label: '补充说明（可选）', control: rText })
  ]));
  container.appendChild(card);
  container.appendChild(reasonCard);

  const body = ui.el('div', { class: 'hg-card' });
  container.appendChild(body);
  container.appendChild(ui.el('div', { class: 'hg-form-actions' }, [submitBtn]));

  // 提交（危险操作：二次确认 + 幂等 + 前端预检）
  submitBtn.addEventListener('click', async () => {
    // 预检：R-TRADE-1 绑定红线 + 基础要素
    if (!fSub.value) { ui.toast('子账户标识必填（R-TRADE-1：不得由会话间接推导）', 'error'); return; }
    if (!fPlan.value) { ui.toast('交易计划 ID 必填（R-TRADE-1：无计划 ID 的指令一律拒绝）', 'error'); return; }
    if (!fInstr.value.trim()) { ui.toast('合约代码（instrumentId）必填', 'error'); return; }
    if (!fDir.value) { ui.toast('买卖方向必选（BUY/SELL）', 'error'); return; }
    if (!fVol.value || Number(fVol.value) <= 0) { ui.toast('数量必须为正（volume > 0）', 'error'); return; }
    if (fPriceType.value === 'LIMIT' && !fPrice.value.trim()) { ui.toast('限价单必须给出价格', 'error'); return; }
    // 预检：R-TRADE-9 提交路径三项（缺任一不发请求）
    if (!rType.value) { ui.toast('交易理由关联类型必选（R-TRADE-9）', 'error'); return; }
    if (!rTime.value) { ui.toast('交易理由时点说明必选（R-TRADE-9）', 'error'); return; }
    if (!rPlan.value || !rExposure.value) {
      ui.toast('提交路径必须提供关联对象（关联计划 ID + 实货敞口 ID）——缺任一即拒绝（CH-004）', 'error');
      return;
    }
    const dirNote = HEDGE_DIR_TO_TRADE[prefill.direction];
    const go = await ui.confirm(
      '确认提交交易指令？<br>子账户 ' + F.esc(fSub.value) + ' / 计划 ' + F.esc(fPlan.value)
        + ' / 合约 ' + F.esc(fInstr.value.trim()) + ' / ' + F.esc(fDir.value === 'BUY' ? '买入' : '卖出')
        + (dirNote && dirNote !== fDir.value ? '（套保方向 ' + F.esc(prefill.direction) + ' 推导方向为 ' + F.esc(dirNote) + '，请核对）' : '')
        + ' / ' + F.esc(fVol.value) + ' 手 / ' + F.esc(PRICE_TYPES[fPriceType.value] || fPriceType.value)
        + (fPriceType.value === 'LIMIT' ? ' @ ' + F.esc(fPrice.value.trim()) : '')
        + '<br>提交后指令为<strong>待确认</strong>，须人工确认后才发往 CTP（R-TRADE-2）。',
      { title: '提交交易指令', danger: true });
    if (!go) return;
    ui.clear(body); ui.renderLoading(body, '正在提交交易指令…');
    const payload = {
      subAccountId: Number(fSub.value),
      tradePlanId: Number(fPlan.value),
      exposureId: fExposure.value ? Number(fExposure.value) : null,
      productId: fProduct.value ? Number(fProduct.value) : null,
      instrumentId: fInstr.value.trim(),
      direction: fDir.value,
      offsetFlag: fOffset.value || null,
      priceType: fPriceType.value || 'LIMIT',
      price: fPriceType.value === 'LIMIT' ? fPrice.value.trim() : null,
      volume: Number(fVol.value),
      confirmToken: fToken.value.trim() || null,
      reason: {
        relatedType: rType.value,
        relatedPlanId: Number(rPlan.value),
        relatedExposureId: Number(rExposure.value),
        timeDesc: rTime.value,
        reasonText: rText.value.trim() || null
      }
    };
    try {
      const r = await HG.api.post(RT.ORDER, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(body);
      body.appendChild(ui.banner(
        '指令已受理（未发出）：状态 <strong>' + F.esc(ORDER_STATUS[r.status] || r.status || '—') + '</strong>'
          + '，C++ 指令号 <strong>' + F.esc(String(r.orderId ?? '—')) + '</strong>'
          + '，幂等引用 <code>' + F.esc(r.clientOrderRef || '—') + '</code>'
          + '，限额层 ' + F.esc(r.limitLayer || '—') + '。'
          + '请到「指令动作」Tab 人工确认（R-TRADE-2）或撤单。', 'ok'));
      body.appendChild(ui.el('div', { html: statusBadgeHTML(r.status) }));
      if (fToken.value.trim()) { /* 带确认令牌提交（0x1018 二次确认通过） */ }
    } catch (err) { ui.renderError(body, err, () => submitBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 2：指令动作（POST /trade/order/{id}/confirm | cancel；FR-TRADE-2/6）
 * ------------------------------------------------------------------ */
function actionTab(container) {
  // —— 人工确认（FR-TRADE-2；confirmToken 线格式必填 0x1018）
  const cCard = ui.el('section', { class: 'hg-card' });
  cCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '人工确认后发出（POST /trade/order/{id}/confirm）' }));
  cCard.appendChild(ui.banner(
    '<strong>人工确认门禁（R-TRADE-2）</strong>：未确认的指令不得发往 CTP；确认前 C++ 会再次执行'
      + '<strong>完整事前风控校验</strong>（防止确认期间状态变化）；确认超时（默认 60 秒）后指令自动失效。'
      + '确认令牌（confirmToken）线格式必填（0x1018）；确认动作由后端留痕（确认人、时间）。',
    'warn'
  ));
  const cId = numInput('tr-c-id', { min: '1', placeholder: 'C++ 指令号（下单受理返回的 orderId）' });
  const cToken = textInput('tr-c-token', { placeholder: '确认令牌（confirmToken，必填）' });
  const cBtn = ui.el('button', { class: 'hg-btn hg-btn--danger', type: 'button', text: '确认发出' });
  cCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'tr-c-id', label: '指令号（必填）', required: true, control: cId }),
    ui.field({ id: 'tr-c-token', label: '确认令牌（必填）', required: true, control: cToken }),
    cBtn
  ]));
  const cBody = ui.el('div');
  cCard.appendChild(cBody);
  container.appendChild(cCard);

  cBtn.addEventListener('click', async () => {
    if (!cId.value) { ui.toast('指令号（orderId）必填', 'error'); return; }
    if (!cToken.value.trim()) { ui.toast('确认令牌（confirmToken）必填（线格式 0x1018）', 'error'); return; }
    const go = await ui.confirm(
      '确认对指令 #' + F.esc(cId.value) + ' 执行人工确认并发出？'
        + '<br>确认后指令发往 CTP；确认前将再次执行完整事前风控校验。',
      { title: '人工确认发出', danger: true });
    if (!go) return;
    ui.clear(cBody); ui.renderLoading(cBody, '正在确认指令…');
    try {
      const r = await HG.api.post(RT.orderAction(cId.value, 'confirm'), { confirmToken: cToken.value.trim() },
        { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(cBody);
      cBody.appendChild(ui.banner(
        '确认回执：acknowledged=' + F.esc(String(r.acknowledged ?? '—'))
          + '，组件 ' + F.esc(r.componentCode || '—')
          + '，回执时间 ' + F.esc(F.dateTime(r.ackTime) || '—')
          + (r.message ? '，说明：' + F.esc(r.message) : '') + '。', 'ok'));
    } catch (err) { ui.renderError(cBody, err, () => cBtn.click()); }
  });

  // —— 撤单（FR-TRADE-6；仅未成交部分，R-TRADE-7）
  const xCard = ui.el('section', { class: 'hg-card' });
  xCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '撤销交易指令（POST /trade/order/{id}/cancel）' }));
  xCard.appendChild(ui.banner(
    '<strong>撤单口径（R-TRADE-7）</strong>：仅<strong>未成交部分</strong>可撤；撤单成功后释放对应额度占用'
      + '并回退交易计划可下单量；<strong>已成交部分不可撤销</strong>；撤单动作留痕（操作人、时间）。',
    'warn'
  ));
  const xId = numInput('tr-x-id', { min: '1', placeholder: 'C++ 指令号' });
  const xReason = textInput('tr-x-reason', { placeholder: '撤单原因（可选，留痕）' });
  const xBtn = ui.el('button', { class: 'hg-btn hg-btn--danger', type: 'button', text: '提交撤单' });
  xCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'tr-x-id', label: '指令号（必填）', required: true, control: xId }),
    ui.field({ id: 'tr-x-reason', label: '撤单原因（可选）', control: xReason }),
    xBtn
  ]));
  const xBody = ui.el('div');
  xCard.appendChild(xBody);
  container.appendChild(xCard);

  xBtn.addEventListener('click', async () => {
    if (!xId.value) { ui.toast('指令号（orderId）必填', 'error'); return; }
    const go = await ui.confirm(
      '确认对指令 #' + F.esc(xId.value) + ' 发起撤单？'
        + '<br>仅未成交部分可撤；已成交部分不可撤销（R-TRADE-7）。',
      { title: '撤销指令', danger: true });
    if (!go) return;
    ui.clear(xBody); ui.renderLoading(xBody, '正在提交撤单…');
    try {
      const r = await HG.api.post(RT.orderAction(xId.value, 'cancel'), { reason: xReason.value.trim() || null },
        { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(xBody);
      xBody.appendChild(ui.banner(
        '撤单回执：acknowledged=' + F.esc(String(r.acknowledged ?? '—'))
          + '，组件 ' + F.esc(r.componentCode || '—')
          + '，回执时间 ' + F.esc(F.dateTime(r.ackTime) || '—')
          + (r.message ? '，说明：' + F.esc(r.message) : '') + '。', 'ok'));
    } catch (err) { ui.renderError(xBody, err, () => xBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 3：实时行情（/ws/quote；FR-TRADE-12；R-TRADE-11 只展示不驱动决策）
 * ------------------------------------------------------------------ */
const QUOTE_STALE_MS = 15000; // 行情过期阈值（展示标识；FR-TRADE-12 验收 2「超过阈值未更新时明确标识」）

// 前端静态品种字典：仅用于展示名称，映射不到则回退显示合约代码。
// 不新增 wire 字段、不参与决策；CTP 原生代码小写，故匹配前统一大写。
const QUOTE_NAMES = {
  RB: '螺纹钢', HC: '热卷', CU: '沪铜', AL: '沪铝', ZN: '沪锌', NI: '沪镍', SN: '沪锡',
  AU: '沪金', AG: '沪银', RU: '天然橡胶', BU: '沥青', FU: '燃油', SP: '纸浆', SS: '不锈钢',
  I: '铁矿石', M: '豆粕', Y: '豆油', P: '棕榈油', C: '玉米', J: '焦炭', JM: '焦煤',
  A: '豆一', B: '豆二', EG: '乙二醇', L: '塑料', PP: '聚丙烯', V: 'PVC',
  SR: '白糖', TA: 'PTA', MA: '甲醇', FG: '玻璃', CF: '棉花', RM: '菜粕', OI: '菜油', SA: '纯碱',
  IF: '沪深300', IH: '上证50', IC: '中证500', IM: '中证1000', T: '十年国债', TF: '五年国债',
  SC: '原油', LU: '低硫燃油', NR: '20号胶', BC: '国际铜'
};
function quoteName(instr) {
  const m = /^([A-Za-z]+)/.exec(String(instr || ''));
  return m ? (QUOTE_NAMES[m[1].toUpperCase()] || '') : '';
}
function qNum(v) {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return isFinite(n) ? n : null;
}
function qSigned(d) {
  if (d === null || !isFinite(d)) return '—';
  return (d > 0 ? '+' : '') + d.toFixed(2);
}
function qStale(frame) {
  const ts = Date.parse(frame && frame.ts || '');
  return !isFinite(ts) || (Date.now() - ts) > QUOTE_STALE_MS;
}
// 保留带引号的 data-quote-stale 锚点（既有探针 / FR-TRADE-12 依赖），仅换深色样式类。
function staleHTML(frame) {
  const stale = qStale(frame);
  return '<span class="hg-q-stale" data-quote-stale="' + (stale ? 'stale' : 'fresh') + '">'
    + (stale ? '行情延迟/过期' : '实时') + '</span>';
}
// 会话内最近值走势（inline SVG，零依赖；坐标为归一化 0-100，不含原始价格字面）。
function qSpark(hist, rising) {
  const pts = (hist || []).filter((n) => n !== null);
  if (pts.length < 2) return '';
  const min = Math.min.apply(null, pts), max = Math.max.apply(null, pts);
  const span = (max - min) || 1;
  const coords = pts.map((v, i) => {
    const x = (i / (pts.length - 1)) * 100;
    const y = 60 - ((v - min) / span) * 56;
    return x.toFixed(1) + ',' + y.toFixed(1);
  });
  const line = coords.join(' ');
  const stroke = rising ? '#f04b4b' : '#23b26d';
  return '<svg class="hg-q-spark" viewBox="0 0 100 64" preserveAspectRatio="none" '
    + 'role="img" aria-label="会话内最近值走势">'
    + '<line class="hg-q-spark__grid" x1="0" y1="32" x2="100" y2="32" />'
    + '<polygon class="hg-q-spark__area" points="0,64 ' + line + ' 100,64" />'
    + '<polyline class="hg-q-spark__line" points="' + line + '" style="stroke:' + stroke + '" />'
    + '</svg>';
}

// ---- CH-027：前端静态合约目录（非权威全市场，随换月维护）。-------------------
// 按交易所分组，品种 → 合约代码按月份规则生成；「主力」= 该品种最近活跃月（近似，
// 非实时持仓量判定）。原生大小写：SHFE/DCE/INE 小写 4 位、CFFEX 大写 4 位、CZCE 大写 3 位。
const QUOTE_CATALOG_DEFS = [
  { ex: 'SHFE', label: '上期所', kind: 'lower4', products: [
    ['rb', '螺纹钢', [1, 5, 10]], ['hc', '热卷', [1, 5, 10]], ['cu', '沪铜', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]],
    ['al', '沪铝', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]], ['zn', '沪锌', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]],
    ['ni', '沪镍', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]], ['sn', '沪锡', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]],
    ['au', '沪金', [2, 4, 6, 8, 10, 12]], ['ag', '沪银', [2, 4, 6, 8, 10, 12]],
    ['ru', '天然橡胶', [1, 3, 5, 7, 9, 11]], ['bu', '沥青', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]],
    ['fu', '燃油', [1, 5, 9]], ['sp', '纸浆', [1, 5, 9]], ['ss', '不锈钢', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]]
  ] },
  { ex: 'DCE', label: '大商所', kind: 'lower4', products: [
    ['i', '铁矿石', [1, 5, 9]], ['m', '豆粕', [1, 3, 5, 7, 8, 9, 11]],
    ['y', '豆油', [1, 3, 5, 7, 8, 9, 11]], ['p', '棕榈油', [1, 3, 5, 7, 8, 9, 11]],
    ['c', '玉米', [1, 3, 5, 7, 9, 11]], ['j', '焦炭', [1, 5, 9]],
    ['jm', '焦煤', [1, 5, 9]], ['a', '豆一', [1, 3, 5, 7, 9, 11]],
    ['eg', '乙二醇', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]], ['l', '塑料', [1, 5, 9]], ['pp', '聚丙烯', [1, 5, 9]], ['v', 'PVC', [1, 5, 9]]
  ] },
  { ex: 'CZCE', label: '郑商所', kind: 'upper3', products: [
    ['SR', '白糖', [1, 3, 5, 7, 9, 11]], ['TA', 'PTA', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]],
    ['MA', '甲醇', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]], ['FG', '玻璃', [1, 5, 9]],
    ['CF', '棉花', [1, 3, 5, 7, 9, 11]], ['RM', '菜粕', [1, 3, 5, 7, 8, 9, 11]],
    ['OI', '菜油', [1, 3, 5, 7, 9, 11]], ['SA', '纯碱', [1, 5, 9]]
  ] },
  { ex: 'CFFEX', label: '中金所', kind: 'upper4', products: [
    ['IF', '沪深300', null], ['IH', '上证50', null], ['IC', '中证500', null], ['IM', '中证1000', null],
    ['T', '十年国债', [3, 6, 9, 12]], ['TF', '五年国债', [3, 6, 9, 12]]
  ] },
  { ex: 'INE', label: '能源中心', kind: 'lower4', products: [
    ['sc', '原油', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]],
    ['lu', '低硫燃油', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]], ['nr', '20号胶', [1, 3, 5, 7, 9, 11]], ['bc', '国际铜', [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]]
  ] }
];

function buildQuoteCatalog() {
  const now = new Date();
  const baseYear = now.getFullYear(), baseMonth = now.getMonth() + 1;
  return QUOTE_CATALOG_DEFS.map((d) => {
    const products = d.products.map((p) => {
      const code = p[0], name = p[1], mainMonths = p[2];
      const contracts = [];
      for (let k = 0; k < 12; k++) {
        let y = baseYear, m = baseMonth + k;
        while (m > 12) { m -= 12; y += 1; }
        const yy = String(y).slice(2), mm = String(m).length < 2 ? '0' + m : String(m);
        let id;
        if (d.kind === 'lower4') id = code.toLowerCase() + yy + mm;
        else if (d.kind === 'upper4') id = code.toUpperCase() + yy + mm;
        else id = code.toUpperCase() + String(y).slice(3) + mm;  // CZCE 3 位
        // CFFEX null = 当月为「主力」（月份规则近似）；其余品种取最近 mainMonths 命中月。
        const isMain = mainMonths === null ? (k === 0) : mainMonths.indexOf(m) !== -1 && contracts.every((c) => !c.main);
        contracts.push({ id: id, month: m, main: isMain });
      }
      return { code: code, name: name, contracts: contracts };
    });
    return { ex: d.ex, label: d.label, products: products };
  });
}
const QUOTE_CATALOG = buildQuoteCatalog();
function mainContracts(catalog) {
  const out = [];
  catalog.forEach(function (exg) {
    exg.products.forEach(function (p) {
      p.contracts.forEach(function (c) {
        if (c.main) out.push(c.id);
      });
    });
  });
  return out;
}

function quoteTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '实时行情（/ws/quote）' }));
  // 界面边界声明：文案逐字保留（探针 / FR-TRADE-12 / R-TRADE-11 / CH-007 依赖）。
  card.appendChild(ui.banner(
    '<strong>行情只来自 CTP</strong>（R-TRADE-11：系统不接任何第三方行情源），经 <code>/ws/quote</code> 推送'
      + '（<strong>允许丢帧</strong>，仅推送最新值）；行情面板标注<strong>数据时间</strong>，'
      + '超过阈值（' + Math.round(QUOTE_STALE_MS / 1000) + ' 秒）未更新显式标识「行情延迟/过期」。'
      + '<strong>行情不用于任何自动决策——本页不存在「按行情自动下单」能力</strong>（与下单表单不联动自动提交）。'
      + '补数批次行情帧由服务端消费侧丢弃（CH-007：重放陈旧价不触发 WebSocket 下发，亦不计入行情新鲜度）。',
    'warn'
  ));

  const panel = ui.el('div', { class: 'hg-q' });
  // ① 顶部状态条
  const top = ui.el('div', { class: 'hg-q-top' });
  const wsBadge = ui.el('span', { class: 'hg-q-top__badge', 'data-ws-state': 'init', text: '连接中…' });
  top.appendChild(wsBadge);
  const countVal = ui.el('span', { class: 'hg-q-top__val', text: '0' });
  const framesVal = ui.el('span', { class: 'hg-q-top__val', text: '0' });
  top.appendChild(ui.el('span', { class: 'hg-q-top__label', text: '合约数' }));
  top.appendChild(countVal);
  top.appendChild(ui.el('span', { class: 'hg-q-top__label', text: '累计帧' }));
  top.appendChild(framesVal);
  top.appendChild(ui.el('div', {
    class: 'hg-q-top__note',
    text: '口径：「会话变动」以本会话首帧价为基准，非昨结算价、非真实涨跌幅；红涨绿跌仅表示会话内方向。'
      + '走势为会话内最近值，非完整分时/K线。行情只展示、不驱动任何自动决策。'
  }));
  panel.appendChild(top);

  // ② 工具栏
  const bar = ui.el('div', { class: 'hg-q-bar' });
  const searchInput = textInput('tr-q-search', { placeholder: '搜索合约 / 名称' });
  searchInput.setAttribute('type', 'search');
  bar.appendChild(ui.field({ id: 'tr-q-search', label: '搜索', control: searchInput }));
  const favOnlyBtn = ui.el('button', { class: 'hg-q-btn', type: 'button', text: '仅自选' });
  bar.appendChild(favOnlyBtn);
  const denseBtn = ui.el('button', { class: 'hg-q-btn', type: 'button', text: '紧凑' });
  bar.appendChild(denseBtn);
  // §24：订阅入口合并为单一按钮。旧的「合约过滤」手输栏只改 WS 白名单、不下发 CTP 订阅集
  // （正是「订阅改变合约未成功」的历史根源），已移除；原「订阅」按钮复用为弹窗触发按钮。
  const subBtn = ui.el('button', {
    class: 'hg-q-btn hg-q-btn--primary', id: 'tr-q-open-sub', type: 'button', text: '行情订阅'
  });
  bar.appendChild(subBtn);
  panel.appendChild(bar);

  // ②b §24：合约筛选订阅收进弹出式对话框。DOM 常驻 .hg-q 面板内（不挂 document.body）：
  // ① --hg-q-* 变量定义在 .hg-q 局部作用域，挂在面板内才能继承深色配色；
  // ② 保持探针 collect(root,…) 可达；③ position:fixed 遮罩不受 .hg-main 的 overflow 影响。
  const dlg = ui.el('div', {
    class: 'hg-q-dlg', id: 'tr-q-dlg', role: 'dialog', 'aria-modal': 'true', 'aria-label': '行情订阅'
  });
  const dlgBox = ui.el('div', { class: 'hg-q-dlg__box' });
  const dlgHead = ui.el('div', { class: 'hg-q-dlg__head' });
  dlgHead.appendChild(ui.el('h3', {
    class: 'hg-q-dlg__title', text: '行情订阅 · 分交易所 / 分品种筛选'
  }));
  const dlgClose = ui.el('button', {
    class: 'hg-q-dlg__x', id: 'tr-q-dlg-x', type: 'button', 'aria-label': '关闭', text: '×'
  });
  dlgHead.appendChild(dlgClose);
  const dlgBody = ui.el('div', { class: 'hg-q-dlg__body' });
  const dlgFoot = ui.el('div', { class: 'hg-q-dlg__foot' });
  const dlgCancel = ui.el('button', {
    class: 'hg-q-btn', id: 'tr-q-dlg-cancel', type: 'button', text: '取消'
  });
  dlgFoot.appendChild(dlgCancel);

  // CH-027 合约选择面板（分交易所 / 分品种筛选 → 多选 → 动态订阅）：整块搬入对话框。
  const pick = ui.el('div', { class: 'hg-q-pick' });
  const pickBar = ui.el('div', { class: 'hg-q-pick__bar' });
  const exSel = ui.el('select', { id: 'tr-q-ex' });
  const allExOpt = ui.el('option', { value: '', text: '全部交易所' });
  exSel.appendChild(allExOpt);
  QUOTE_CATALOG.forEach(function (g) {
    exSel.appendChild(ui.el('option', { value: g.ex, text: g.label + '（' + g.ex + '）' }));
  });
  const prodSel = ui.el('select', { id: 'tr-q-prod' });
  pickBar.appendChild(ui.field({ id: 'tr-q-ex', label: '交易所', control: exSel }));
  pickBar.appendChild(ui.field({ id: 'tr-q-prod', label: '品种', control: prodSel }));
  const mainOnlyChk = ui.el('input', { type: 'checkbox', id: 'tr-q-mainonly' });
  mainOnlyChk.checked = true;
  const catSearchInput = textInput('tr-q-cat-search', { placeholder: '筛选合约代码/名称' });
  pickBar.appendChild(ui.field({ id: 'tr-q-mainonly', label: '仅主力', control: mainOnlyChk }));
  pickBar.appendChild(ui.field({ id: 'tr-q-cat-search', label: '合约搜索', control: catSearchInput }));
  const mainBtn = ui.el('button', { class: 'hg-q-btn', type: 'button', text: '主力一键选' });
  const clearBtn = ui.el('button', { class: 'hg-q-btn', type: 'button', text: '清空' });
  const applyBtn = ui.el('button', {
    class: 'hg-q-btn hg-q-btn--primary', type: 'button', text: '应用所选'
  });
  pickBar.appendChild(mainBtn);
  pickBar.appendChild(clearBtn);
  pick.appendChild(pickBar);
  const pickNote = ui.el('div', {
    class: 'hg-q-pick__note',
    text: '静态合约目录（非权威全市场、随换月维护）；「主力」按月份规则近似（每品种最近活跃月），非实时持仓量判定。'
      + '勾选后点「应用所选」动态改变 CTP 订阅集；「一键订阅主力」= 每品种主力合约一键全选并下发。'
      + '订阅成功后本对话框自动关闭；下发失败则保持打开以便改选或重试。'
  });
  pick.appendChild(pickNote);
  const pickList = ui.el('div', { class: 'hg-q-pick__list' });
  pick.appendChild(pickList);
  const pickStatus = ui.el('div', { class: 'hg-q-pick__status', text: '已选 0 个合约' });
  pick.appendChild(pickStatus);

  dlgBody.appendChild(pick);
  dlgFoot.appendChild(applyBtn);   // 主操作置于对话框底部，避免与筛选栏按钮重复
  dlgBox.appendChild(dlgHead);
  dlgBox.appendChild(dlgBody);
  dlgBox.appendChild(dlgFoot);
  dlg.appendChild(dlgBox);
  panel.appendChild(dlg);          // 常驻、默认隐藏（class 不含 is-open）

  // ③ 主区：左报价板 + 右选中详情
  const main = ui.el('div', { class: 'hg-q-main' });
  const board = ui.el('div', { class: 'hg-q-board' });
  const emptyNode = ui.el('p', {
    class: 'hg-q-empty',
    text: '暂无行情（连接建立并订阅后，最新价将在此展示；仅保留每合约最新一帧）。'
  });
  const wrap = ui.el('div', { class: 'hg-q-wrap' });
  const table = ui.el('table', { class: 'hg-q-table' });
  const thead = ui.el('thead');
  const headRow = ui.el('tr');
  const tbody = ui.el('tbody');
  table.appendChild(thead);
  table.appendChild(tbody);
  thead.appendChild(headRow);
  wrap.appendChild(table);
  board.appendChild(emptyNode);
  board.appendChild(wrap);

  const detail = ui.el('div', { class: 'hg-q-detail' });
  const detailBody = ui.el('div');
  detail.appendChild(detailBody);
  main.appendChild(board);
  main.appendChild(detail);
  panel.appendChild(main);

  // ④ 图例
  panel.appendChild(ui.el('div', { html:
    '<div class="hg-q-legend"><span><b class="hg-q-up">红</b> 涨 / <b class="hg-q-down">绿</b> 跌（会话内方向）</span>'
    + '<span>点击表头排序 · 点击行查看详情 · ★ 自选</span>'
    + '<span>仅现有 wire 字段：instrument / last / bid / ask / ts</span></div>'
  }));
  card.appendChild(panel);
  container.appendChild(card);

  // ---------------- 状态层 ----------------
  const FAV_KEY = 'hg.q.favs';
  function loadFav() {
    try {
      const raw = window.localStorage && window.localStorage.getItem(FAV_KEY);
      if (raw) {
        const arr = JSON.parse(raw);
        if (Array.isArray(arr)) return new Set(arr.filter((x) => typeof x === 'string'));
      }
    } catch (e) { /* 存储不可用 ⇒ 降级为空自选 */ }
    return new Set();
  }
  function saveFav() {
    try {
      if (window.localStorage) window.localStorage.setItem(FAV_KEY, JSON.stringify(Array.from(st.favorites)));
    } catch (e) { /* 忽略：存储不可用不影响展示 */ }
  }
  const st = {
    connected: false,
    quotes: {},            // instrument -> 最新帧（最近值语义，允许丢帧）
    sessionOpen: {},       // instrument -> 本会话首帧 last（变动基准）
    prevLast: {},          // instrument -> 上一帧 last（tick 方向）
    hist: {},              // instrument -> 最近 60 帧 last（sparkline）
    rows: {},              // instrument -> { tr, fav, cells }（差量渲染索引）
    favorites: loadFav(),
    showFavOnly: false,
    filter: '',
    sortKey: 'instrument',
    sortDir: 'asc',
    selected: null,
    dense: false,
    frames: 0,
    lastOrder: null
  };

  // ---------------- 表头（点击排序） ----------------
  const COLS = [
    { k: 'fav', t: '★', sortable: false, num: false },
    { k: 'instrument', t: '合约', sortable: true, num: false },
    { k: 'last', t: '最新', sortable: true, num: true },
    { k: 'chg', t: '会话变动', sortable: true, num: true },
    { k: 'bid', t: '买一', sortable: true, num: true },
    { k: 'ask', t: '卖一', sortable: true, num: true },
    { k: 'spread', t: '价差', sortable: true, num: true },
    { k: 'ts', t: '数据时间', sortable: true, num: false },
    { k: 'fresh', t: '新鲜度', sortable: false, num: false }
  ];
  const thEls = {};
  COLS.forEach((c) => {
    const th = ui.el('th', { class: c.num ? 'hg-q-num' : '' });
    th.innerHTML = F.esc(c.t) + (c.sortable ? '<span class="hg-q-sort"></span>' : '');
    if (c.sortable) {
      th.addEventListener('click', () => {
        if (st.sortKey === c.k) st.sortDir = st.sortDir === 'asc' ? 'desc' : 'asc';
        else { st.sortKey = c.k; st.sortDir = 'asc'; }
        renderHeadSort();
        renderFeed();
      });
    }
    headRow.appendChild(th);
    thEls[c.k] = th;
  });
  function renderHeadSort() {
    COLS.forEach((c) => {
      if (!c.sortable || !thEls[c.k]) return;
      const mark = st.sortKey === c.k ? (st.sortDir === 'asc' ? '▲' : '▼') : '';
      thEls[c.k].innerHTML = F.esc(c.t) + '<span class="hg-q-sort">' + mark + '</span>';
    });
  }
  renderHeadSort();

  // ---------------- 行构造与差量更新 ----------------
  function ensureRow(inst) {
    if (st.rows[inst]) return st.rows[inst];
    const tr = ui.el('tr', { 'data-instr': inst });
    const fav = ui.el('button', { class: 'hg-q-fav', type: 'button', text: '☆', title: '加入 / 移出自选' });
    fav.addEventListener('click', (e) => {
      // 阻止冒泡到 tr 的行选中（真实浏览器事件冒泡；探针 DOM 桩无 stopPropagation，故防御式调用）
      if (e && typeof e.stopPropagation === 'function') e.stopPropagation();
      if (st.favorites.has(inst)) st.favorites.delete(inst);
      else st.favorites.add(inst);
      saveFav();
      renderFeed();
    });
    const tdFav = ui.el('td', {}, [fav]);
    const name = quoteName(inst);
    const tdInstr = ui.el('td', {
      html: '<span class="hg-q-instr">' + F.esc(inst) + '</span>'
        + (name ? '<span class="hg-q-name">' + F.esc(name) + '</span>' : '')
    });
    const cells = {
      last: ui.el('td', { class: 'hg-q-num hg-q-last' }),
      chg: ui.el('td', { class: 'hg-q-num' }),
      bid: ui.el('td', { class: 'hg-q-num' }),
      ask: ui.el('td', { class: 'hg-q-num' }),
      spread: ui.el('td', { class: 'hg-q-num' }),
      ts: ui.el('td', {}),
      fresh: ui.el('td', {})
    };
    [tdFav, tdInstr, cells.last, cells.chg, cells.bid, cells.ask, cells.spread, cells.ts, cells.fresh]
      .forEach((td) => tr.appendChild(td));
    tr.addEventListener('click', () => { st.selected = inst; renderFeed(); });
    const row = { tr: tr, fav: fav, cells: cells };
    st.rows[inst] = row;
    return row;
  }
  function dirClass(delta) {
    return delta === null ? 'hg-q-flat' : (delta > 0 ? 'hg-q-up' : delta < 0 ? 'hg-q-down' : 'hg-q-flat');
  }
  function updateRow(inst) {
    const row = st.rows[inst];
    const f = st.quotes[inst];
    if (!row || !f) return;
    const c = row.cells;
    const base = qNum(st.sessionOpen[inst]);
    const cur = qNum(f.last);
    const delta = (base !== null && cur !== null) ? cur - base : null;
    const cls = dirClass(delta);
    c.last.textContent = String(f.last === undefined || f.last === null || f.last === '' ? '—' : f.last);
    c.last.className = 'hg-q-num hg-q-last ' + cls;
    c.chg.textContent = qSigned(delta);
    c.chg.className = 'hg-q-num ' + cls;
    c.bid.textContent = String(f.bid === undefined || f.bid === null || f.bid === '' ? '—' : f.bid);
    c.ask.textContent = String(f.ask === undefined || f.ask === null || f.ask === '' ? '—' : f.ask);
    const b = qNum(f.bid), a = qNum(f.ask);
    c.spread.textContent = (b !== null && a !== null) ? (a - b).toFixed(2) : '—';
    c.ts.textContent = F.dateTime(f.ts) || String(f.ts || '—');
    c.fresh.innerHTML = staleHTML(f);
    // tick 方向闪烁（仅本次更新，方向变化才换 class ⇒ 动画可重播）
    const prev = qNum(st.prevLast[inst]);
    const rising = cur !== null && prev !== null && cur > prev;
    const falling = cur !== null && prev !== null && cur < prev;
    const flash = rising ? ' hg-q-flash-up' : (falling ? ' hg-q-flash-down' : '');
    // 自选星标 / 选中态
    const on = st.favorites.has(inst);
    row.fav.textContent = on ? '★' : '☆';
    row.fav.className = 'hg-q-fav' + (on ? ' is-on' : '');
    row.tr.className = (st.selected === inst ? 'is-selected' : '') + flash;
  }
  function sortVal(inst, key) {
    const f = st.quotes[inst] || {};
    if (key === 'instrument') return inst;
    if (key === 'ts') return Date.parse(f.ts || '') || 0;
    if (key === 'chg') {
      const base = qNum(st.sessionOpen[inst]), cur = qNum(f.last);
      return (base !== null && cur !== null) ? cur - base : -Infinity;
    }
    if (key === 'spread') {
      const a = qNum(f.ask), b = qNum(f.bid);
      return (a !== null && b !== null) ? a - b : -Infinity;
    }
    return qNum(f[key]);
  }
  function visibleKeys() {
    let keys = Object.keys(st.quotes);
    const q = st.filter.trim().toUpperCase();
    if (q) {
      keys = keys.filter((k) => k.toUpperCase().indexOf(q) !== -1
        || quoteName(k).indexOf(st.filter.trim()) !== -1);
    }
    if (st.showFavOnly) keys = keys.filter((k) => st.favorites.has(k));
    const dir = st.sortDir === 'asc' ? 1 : -1;
    keys.sort((x, y) => {
      const vx = sortVal(x, st.sortKey), vy = sortVal(y, st.sortKey);
      let r = 0;
      if (typeof vx === 'string' || typeof vy === 'string') r = String(vx).localeCompare(String(vy));
      else r = vx === vy ? 0 : (vx < vy ? -1 : 1);
      return r * dir || x.localeCompare(y);
    });
    return keys;
  }
  function reorder(keys) {
    ui.clear(tbody);
    keys.forEach((k) => { if (st.rows[k]) tbody.appendChild(st.rows[k].tr); });
  }

  // ---------------- 详情（选中合约盘口 + sparkline） ----------------
  function updateDetail() {
    const inst = st.selected;
    const f = inst && st.quotes[inst];
    if (!f) {
      detailBody.innerHTML = '<p class="hg-q-detail__title">合约详情</p>'
        + '<p class="hg-q-detail__empty">点击左侧报价行查看买一 / 卖一盘口与会话内走势。</p>';
      return;
    }
    const name = quoteName(inst);
    const base = qNum(st.sessionOpen[inst]), cur = qNum(f.last);
    const delta = (base !== null && cur !== null) ? cur - base : null;
    const cls = dirClass(delta);
    const b = qNum(f.bid), a = qNum(f.ask);
    const sum = (b === null ? 0 : b) + (a === null ? 0 : a);
    const bidPct = sum > 0 && b !== null ? Math.max(0, Math.min(100, (b / sum) * 100)) : 50;
    const askPct = 100 - bidPct;
    const rising = delta !== null && delta > 0;
    detailBody.innerHTML = ''
      + '<p class="hg-q-detail__title">合约详情</p>'
      + '<div><span class="hg-q-detail__instr">' + F.esc(inst) + '</span>'
      + (name ? '<span class="hg-q-detail__name">' + F.esc(name) + '</span>' : '') + '</div>'
      + '<div class="hg-q-detail__last ' + cls + '">' + F.esc(String(f.last || '—')) + '</div>'
      + '<div class="hg-q-detail__chg ' + cls + '">会话变动 ' + F.esc(qSigned(delta)) + '</div>'
      + '<div class="hg-q-depth">'
      + '  <span class="hg-q-depth__tag">买一</span>'
      + '  <span class="hg-q-depth__track"><span class="hg-q-depth__fill hg-q-depth__fill--bid" style="width:' + bidPct.toFixed(1) + '%"></span></span>'
      + '  <span class="hg-q-depth__val hg-q-depth__val--bid">' + F.esc(String(f.bid || '—')) + '</span>'
      + '  <span class="hg-q-depth__tag">卖一</span>'
      + '  <span class="hg-q-depth__track"><span class="hg-q-depth__fill hg-q-depth__fill--ask" style="width:' + askPct.toFixed(1) + '%"></span></span>'
      + '  <span class="hg-q-depth__val hg-q-depth__val--ask">' + F.esc(String(f.ask || '—')) + '</span>'
      + '</div>'
      + (qSpark(st.hist[inst], rising) || '<p class="hg-q-detail__note">会话内数据点不足，走势待累积。</p>')
      + '<div class="hg-q-detail__note">走势为会话内最近值，非完整分时；盘口仅买一 / 卖一。'
      + '会话变动以本会话首帧价为基准，非真实涨跌幅。</div>';
  }

  // ---------------- 主渲染（差量：只更新单元格，顺序变化才重排） ----------------
  function renderFeed() {
    Object.keys(st.quotes).forEach(ensureRow);
    Object.keys(st.rows).forEach(updateRow);
    const keys = visibleKeys();
    const sig = keys.join('|');
    if (sig !== st.lastOrder) { reorder(keys); st.lastOrder = sig; }
    const total = Object.keys(st.quotes).length;
    emptyNode.textContent = total === 0
      ? '暂无行情（连接建立并订阅后，最新价将在此展示；仅保留每合约最新一帧）。'
      : '无匹配合约（调整搜索词或「仅自选」筛选）。';
    emptyNode.style.display = keys.length ? 'none' : '';
    wrap.style.display = keys.length ? '' : 'none';
    countVal.textContent = String(total);
    framesVal.textContent = String(st.frames);
    updateDetail();
  }
  // 高频推送节流渲染（前端纪律 §5.3；差量更新由「每合约最新一帧」+ throttle 实现）
  const renderFeedThrottled = HG.ws.throttle ? HG.ws.throttle(renderFeed, 300) : renderFeed;

  function renderWsBadge() {
    wsBadge.textContent = st.connected ? '已连接' : '已断线（自动重连中）';
    wsBadge.className = 'hg-q-top__badge';
    wsBadge.setAttribute('data-ws-state', st.connected ? 'connected' : 'disconnected');
  }

  // ---------------- 交互绑定 ----------------
  searchInput.addEventListener('input', () => { st.filter = searchInput.value; renderFeed(); });
  favOnlyBtn.addEventListener('click', () => {
    st.showFavOnly = !st.showFavOnly;
    favOnlyBtn.className = 'hg-q-btn' + (st.showFavOnly ? ' is-active' : '');
    renderFeed();
  });
  denseBtn.addEventListener('click', () => {
    st.dense = !st.dense;
    panel.className = 'hg-q' + (st.dense ? ' hg-q--compact' : '');
    denseBtn.className = 'hg-q-btn' + (st.dense ? ' is-active' : '');
    denseBtn.textContent = st.dense ? '标准' : '紧凑';
  });

  // 防僵尸连接：重建面板前关闭上一次构建遗留的连接（risk.js 同型）
  if (HG.pages._tradeQuoteConn && typeof HG.pages._tradeQuoteConn.close === 'function') {
    HG.pages._tradeQuoteConn.close();
  }
  const conn = HG.ws.connect('quote', {
    onOpen: () => { st.connected = true; renderWsBadge(); },
    onClose: () => { st.connected = false; renderWsBadge(); },
    onMessage: (msg) => {
      if (msg && msg.type === 'quote' && msg.instrument) {
        const inst = msg.instrument;
        if (st.sessionOpen[inst] === undefined) st.sessionOpen[inst] = msg.last;
        if (st.quotes[inst]) st.prevLast[inst] = st.quotes[inst].last;
        st.quotes[inst] = msg; // 最近值语义：仅保留每合约最新一帧
        if (!st.hist[inst]) st.hist[inst] = [];
        const n = qNum(msg.last);
        if (n !== null) { st.hist[inst].push(n); if (st.hist[inst].length > 60) st.hist[inst].shift(); }
        st.frames += 1;
        renderFeedThrottled();
      }
    }
  });
  HG.pages._tradeQuoteConn = conn;

  // ---------------- §24 订阅对话框显隐 ----------------
  // 订阅集规模只由 REST 成功回执更新；null = 尚未下发过，按钮不带数字（不臆造初值）。
  let subCount = null;
  function syncSubBtn() {
    subBtn.textContent = '行情订阅' + (subCount === null ? '' : ' · ' + subCount);
  }
  function openSubDlg() {
    dlg.classList.add('is-open');
    document.removeEventListener('keydown', onDlgKey);  // 防重复挂载
    document.addEventListener('keydown', onDlgKey);
    catSearchInput.focus();
  }
  function closeSubDlg() {
    dlg.classList.remove('is-open');
    document.removeEventListener('keydown', onDlgKey);
    subBtn.focus();
  }
  function onDlgKey(e) { if (e && e.key === 'Escape') closeSubDlg(); }
  subBtn.addEventListener('click', openSubDlg);
  dlgClose.addEventListener('click', closeSubDlg);
  dlgCancel.addEventListener('click', closeSubDlg);
  dlg.addEventListener('click', (e) => { if (e.target === dlg) closeSubDlg(); });
  syncSubBtn();

  // ---------------- CH-027 合约选择面板逻辑 ----------------
  st.pickSelected = new Set();   // 原生大小写合约代码集合
  function catalogProducts() {
    const ex = exSel.value || '';
    const groups = ex ? QUOTE_CATALOG.filter((g) => g.ex === ex) : QUOTE_CATALOG;
    const prod = prodSel.value || '';
    const out = [];
    groups.forEach((g) => g.products.forEach((p) => {
      if (!prod || prod === g.ex + ':' + p.code) {
        out.push({ ex: g.ex, label: g.label, code: p.code, name: p.name, contracts: p.contracts });
      }
    }));
    return out;
  }
  function renderProdOptions() {
    ui.clear(prodSel);
    prodSel.appendChild(ui.el('option', { value: '', text: '全部品种' }));
    const ex = exSel.value || '';
    const groups = ex ? QUOTE_CATALOG.filter((g) => g.ex === ex) : QUOTE_CATALOG;
    groups.forEach(function (g) {
      g.products.forEach(function (p) {
        prodSel.appendChild(ui.el('option', {
          value: g.ex + ':' + p.code, text: p.name + '（' + p.code + '·' + g.ex + '）'
        }));
      });
    });
  }
  function updatePickStatus() {
    const list = Array.from(st.pickSelected);
    const preview = list.slice(0, 12).join(', ') + (list.length > 12 ? ' …' : '');
    pickStatus.textContent = '已选 ' + list.length + ' 个合约' + (list.length ? '：' + preview : '');
  }
  function renderPickList() {
    ui.clear(pickList);
    const mainOnly = mainOnlyChk.checked;
    const q = (catSearchInput.value || '').trim().toUpperCase();
    const prods = catalogProducts();
    let shown = 0;
    prods.forEach((p) => {
      const row = ui.el('div', { class: 'hg-q-pick__grp' });
      row.appendChild(ui.el('span', {
        class: 'hg-q-pick__grpname',
        text: p.label + ' · ' + p.name + '（' + p.code + '）'
      }));
      p.contracts.forEach((c) => {
        if (mainOnly && !c.main) return;
        if (q && (c.id.toUpperCase().indexOf(q) === -1 && p.name.indexOf(q) === -1)) return;
        const lab = ui.el('label', { class: 'hg-q-pick__item' });
        const cb = ui.el('input', { type: 'checkbox' });
        cb.checked = st.pickSelected.has(c.id);
        cb.addEventListener('change', () => {
          if (cb.checked) st.pickSelected.add(c.id);
          else st.pickSelected.delete(c.id);
          updatePickStatus();
        });
        lab.appendChild(cb);
        lab.appendChild(ui.el('span', { text: c.id + (c.main ? ' ★' : '') }));
        row.appendChild(lab);
        shown += 1;
      });
      if (row.children.length > 1) pickList.appendChild(row);
    });
    if (!shown) pickList.appendChild(ui.el('p', { class: 'hg-q-empty', text: '无匹配合约（调整交易所/品种/仅主力/搜索条件）' }));
    updatePickStatus();
  }

  function applyContracts(list, label) {
    const instruments = Array.from(new Set(list));
    HG.api.post(RT.QUOTE_SUBSCRIPTION, { instruments: instruments })
      .then(() => {
        conn.subscribe({ channels: ['quote'], instruments: instruments.map((s) => s.toUpperCase()) });
        subCount = instruments.length;
        syncSubBtn();
        closeSubDlg();   // §24：仅 REST 成功后隐藏对话框
        ui.toast('已应用：' + label + '（' + instruments.length + ' 个合约）', 'ok');
      })
      .catch((err) => {
        // 失败保持对话框打开，供改选 / 重试（§24 裁定）
        ui.toast('订阅下发失败：' + ((err && err.message) || '组件不可达'), 'error');
      });
  }

  exSel.addEventListener('change', () => { prodSel.value = ''; renderProdOptions(); renderPickList(); });
  prodSel.addEventListener('change', renderPickList);
  mainOnlyChk.addEventListener('change', renderPickList);
  catSearchInput.addEventListener('input', renderPickList);
  mainBtn.addEventListener('click', () => {
    const mains = mainContracts(QUOTE_CATALOG);
    st.pickSelected = new Set(mains);
    renderPickList();
    applyContracts(mains, '一键订阅主力（每品种最近活跃月）');
  });
  applyBtn.addEventListener('click', () => {
    if (!st.pickSelected.size) { ui.toast('请先勾选合约', 'error'); return; }
    applyContracts(Array.from(st.pickSelected), '所选合约');
  });
  clearBtn.addEventListener('click', () => {
    st.pickSelected = new Set();
    renderPickList();
    applyContracts([], '清空订阅（退订全部）');
  });

  renderProdOptions();
  renderPickList();

  renderFeed(); // 初始空态 / 详情占位
}

/* ------------------------------------------------------------------ *
 * Tab 4~8：未注册端点（页面框架保留 + 「未注册」诚实提示，不臆造调用）
 *   /trade/order-report、/trade/fill、/trade/position、/trade/reason、/trade/reject-record、
 *   /trade/flow、/trade/quote/{instrument}（REST 读取面）、/trade/strategy、/trade/export
 *   —— ApiRoutes 路径表已登记但 handler/ 无对应 Handler ⇒ 调用即 404（cr-t025 R1-004：缺口
 *   系前端镜像随本单登记承接 + 后端读取面后续立号承接）。
 *   R-TRADE-15：以下各面（委托/成交/流水/拒绝记录）交付后亦只增不改不删、无编辑/删除入口。
 * ------------------------------------------------------------------ */
function unregisteredTab(title, pathConst, plannedDesc, frRef) {
  return function build(container) {
    const card = ui.el('section', { class: 'hg-card' });
    card.appendChild(ui.el('h3', { class: 'hg-card__title', text: title }));
    card.appendChild(ui.banner(
      '<strong>端点未注册（NOT_REGISTERED）</strong>：<code>' + F.esc(pathConst) + '</code> 已在 ApiRoutes 路径表登记，'
        + '但<strong>未注册任何 (方法, 路径) 对（handler/ 下无对应 Handler）——调用即 404</strong>。'
        + '本页保留框架、<strong>不发起调用</strong>（routes.js NOT_REGISTERED 语义：未注册路径禁止调用）；'
        + '后端缺口只报不改，属后续立号承接（cr-t025 R1-004 判例）。',
      'warn'
    ));
    card.appendChild(ui.el('p', { class: 'hg-page__desc', text: '规划功能：' + plannedDesc + '（' + frRef + '）。后端端点注册后本页将按同款三态形态接入；只读类面（委托/成交/流水/拒绝记录）一律无编辑或删除入口（R-TRADE-15）。' }));
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
  { key: 'order', label: '交易下单', build: (c, ctx) => orderTab(c, ctx) },
  { key: 'action', label: '指令动作', build: actionTab },
  { key: 'quote', label: '实时行情', build: quoteTab },
  { key: 'orderReport', label: '委托回报（未注册）', build: unregisteredTab('委托回报查询', NR.TRADE_ORDER_REPORT, '按条件查询 CTP 委托回报与状态跟踪（FR-TRADE-7）', 'FR-TRADE-4/7 规划') },
  { key: 'fill', label: '成交记录（未注册）', build: unregisteredTab('成交记录查询', NR.TRADE_FILL, '成交明细查询与追溯链正向入口（委托→指令→计划→敞口，FR-TRADE-8）', 'FR-TRADE-5/8 规划') },
  { key: 'position', label: '持仓（未注册）', build: unregisteredTab('持仓查询', NR.TRADE_POSITION, '按子账户持仓明细 + 汇总视图，区分可用与冻结持仓（FR-TRADE-9）', 'FR-TRADE-9 规划') },
  { key: 'reason', label: '交易理由（未注册）', build: unregisteredTab('交易理由查询', NR.TRADE_REASON, '交易理由查询与补填留痕（R-TRADE-9）', 'FR-TRADE-10 规划') },
  { key: 'rejectRecord', label: '拒绝记录（未注册）', build: unregisteredTab('事前风控拒绝记录', NR.TRADE_REJECT_RECORD, '被事前风控拒绝的指令及规则编号与原因（FR-TRADE-17）', 'FR-TRADE-17 规划') },
  { key: 'flow', label: '指令流水（未注册）', build: unregisteredTab('交易指令流水', NR.TRADE_FLOW, 'C++ 指令流水查询：完整生命周期轨迹（FR-TRADE-16）', 'FR-TRADE-16 规划') },
  { key: 'strategy', label: '期权策略（未注册）', build: unregisteredTab('期权策略下单（多腿）', NR.TRADE_STRATEGY, '期权多腿策略一次提交、同链同额（FR-TRADE-13）', 'FR-TRADE-13 规划') },
  { key: 'export', label: '导出（未注册）', build: unregisteredTab('交易记录导出', NR.TRADE_EXPORT, '交易记录 CSV 导出（含套保计划/交易计划/实货敞口编号与交易理由列，FR-TRADE-18）', 'FR-TRADE-18 规划') }
];

function renderTrade(root, ctx) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '交易执行（TRADE）' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '交易指令提交（绑定计划与子账户）/ 人工确认后发出 / 撤单 / 实时行情（/ws/quote）（FR-TRADE-1~18 可接入面）。' }));
  root.appendChild(ui.banner(
    '<strong>界面边界声明</strong>：本页仅提供界面，<strong>不做任何风控判定</strong>——事前风控在 C++ 全内存'
      + '（R-TRADE-3），额度权威源为 C++ 内存（R-TRADE-10），价格偏离判定在 C++（Q-15）。'
      + '当前可对接 REST 端点 <strong>3 个</strong>（下单 / 人工确认 / 撤单——T-046 注册写入面）；'
      + '另有 <strong>9 个路径未注册</strong>（order-report / fill / position / reason / reject-record / '
      + 'flow / quote 读取面 / strategy / export）——对应 Tab 保留框架并显式提示，不臆造调用。'
      + '<strong>系统不做自动交易（R-TRADE-2）：无任何自动下单入口。</strong>',
    'warn'
  ));

  const tabBar = ui.el('div', { class: 'hg-tabs', role: 'tablist' });
  const panel = ui.el('div');
  let active = (ctx && ctx.query && ctx.query.tab) || 'order';
  if (!TABS.some((t) => t.key === active)) active = 'order';

  function select(key) {
    Array.prototype.forEach.call(tabBar.children, (b) => {
      const on = b.dataset.key === key;
      b.classList.toggle('is-active', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    ui.clear(panel);
    TABS.filter((t) => t.key === key)[0].build(panel, ctx);
  }
  TABS.forEach((t) => {
    const b = ui.el('button', { type: 'button', role: 'tab', text: t.label, onclick: () => HG.router.navigate('/trade?tab=' + t.key) });
    b.dataset.key = t.key;
    tabBar.appendChild(b);
  });

  root.appendChild(tabBar);
  root.appendChild(panel);
  select(active);
}

HG.pages = HG.pages || {};
HG.pages.trade = { renderTrade };
HG.router.register('/trade', renderTrade);
export default HG.pages.trade;
