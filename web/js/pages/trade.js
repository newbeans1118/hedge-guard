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

function quoteTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '实时行情（/ws/quote）' }));
  card.appendChild(ui.banner(
    '<strong>行情只来自 CTP</strong>（R-TRADE-11：系统不接任何第三方行情源），经 <code>/ws/quote</code> 推送'
      + '（<strong>允许丢帧</strong>，仅推送最新值）；行情面板标注<strong>数据时间</strong>，'
      + '超过阈值（' + Math.round(QUOTE_STALE_MS / 1000) + ' 秒）未更新显式标识「行情延迟/过期」。'
      + '<strong>行情不用于任何自动决策——本页不存在「按行情自动下单」能力</strong>（与下单表单不联动自动提交）。'
      + '补数批次行情帧由服务端消费侧丢弃（CH-007：重放陈旧价不触发 WebSocket 下发，亦不计入行情新鲜度）。',
    'warn'
  ));
  const bar = ui.el('div', { class: 'hg-toolbar' });
  const wsBadge = ui.el('span', { class: 'hg-badge hg-badge--draft', 'data-ws-state': 'init', text: '连接中…' });
  bar.appendChild(ui.el('span', { class: 'hg-pager__info', text: '连接状态：' }));
  bar.appendChild(wsBadge);
  const instrInput = textInput('tr-q-instr', { placeholder: '订阅合约（可空=全部；多个用逗号分隔，大写归一）' });
  const subBtn = ui.el('button', { class: 'hg-btn hg-btn--sm', type: 'button', text: '订阅' });
  bar.appendChild(ui.field({ id: 'tr-q-instr', label: '合约过滤', control: instrInput }));
  bar.appendChild(subBtn);
  card.appendChild(bar);
  const feedBody = ui.el('div');
  feedBody.appendChild(ui.el('p', { class: 'hg-muted', text: '暂无行情（连接建立并订阅后，最新价将在此展示；仅保留每合约最新一帧）。' }));
  card.appendChild(feedBody);
  container.appendChild(card);

  const st = { connected: false, quotes: {} }; // quotes: instrument -> 最新帧（最近值语义，允许丢帧）

  function renderWsBadge() {
    wsBadge.textContent = st.connected ? '已连接' : '已断线（自动重连中）';
    wsBadge.className = 'hg-badge ' + (st.connected ? 'hg-badge--active' : 'hg-badge--danger');
    wsBadge.setAttribute('data-ws-state', st.connected ? 'connected' : 'disconnected');
  }

  function staleHTML(frame) {
    const ts = Date.parse(frame.ts || '');
    const stale = !isFinite(ts) || (Date.now() - ts) > QUOTE_STALE_MS;
    return stale
      ? '<span class="hg-badge hg-badge--danger" data-quote-stale="stale">行情延迟/过期</span>'
      : '<span class="hg-badge hg-badge--active" data-quote-stale="fresh">实时</span>';
  }

  function renderFeed() {
    ui.clear(feedBody);
    const keys = Object.keys(st.quotes);
    if (!keys.length) {
      feedBody.appendChild(ui.el('p', { class: 'hg-muted', text: '暂无行情（连接建立并订阅后，最新价将在此展示；仅保留每合约最新一帧）。' }));
      return;
    }
    feedBody.appendChild(ui.el('div', { html: ui.tableHTML(
      [
        { title: '合约', render: (r) => F.esc(r.instrument) },
        { title: '最新价', num: true, render: (r) => F.esc(r.last || '—') },
        { title: '买价', num: true, render: (r) => F.esc(r.bid || '—') },
        { title: '卖价', num: true, render: (r) => F.esc(r.ask || '—') },
        { title: '数据时间', render: (r) => F.esc(F.dateTime(r.ts) || String(r.ts || '—')) },
        { title: '新鲜度', render: (r) => staleHTML(r) }
      ],
      keys.map((k) => st.quotes[k])
    ) }));
  }
  // 高频推送节流渲染（前端纪律 §5.3；差量更新由「每合约最新一帧」最近值语义 + throttle 实现）
  const renderFeedThrottled = HG.ws.throttle ? HG.ws.throttle(renderFeed, 300) : renderFeed;

  // 防僵尸连接：重建面板前关闭上一次构建遗留的连接（risk.js 同型）
  if (HG.pages._tradeQuoteConn && typeof HG.pages._tradeQuoteConn.close === 'function') {
    HG.pages._tradeQuoteConn.close();
  }
  const conn = HG.ws.connect('quote', {
    onOpen: () => { st.connected = true; renderWsBadge(); },
    onClose: () => { st.connected = false; renderWsBadge(); },
    onMessage: (msg) => {
      if (msg && msg.type === 'quote' && msg.instrument) {
        st.quotes[msg.instrument] = msg; // 最近值语义：仅保留每合约最新一帧
        renderFeedThrottled();
      }
    }
  });
  HG.pages._tradeQuoteConn = conn;

  subBtn.addEventListener('click', () => {
    const list = instrInput.value.split(',').map((s) => s.trim()).filter(Boolean);
    conn.subscribe({ channels: ['quote'], instruments: list });
    ui.toast('已发送订阅（instruments=' + (list.length ? list.join('/').toUpperCase() : '全部') + '）', 'ok');
  });
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
