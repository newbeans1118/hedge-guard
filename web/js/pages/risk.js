/*
 * 风险控制页面（RISK）—— 管理界面（FR-RISK-1~19；T-070）。
 *
 * ## 端点对接面（**不对称盘点**，以 java/src/main/java/com/hedgeguard/web 实测为准）
 *   ApiRoutes.java RISK **路径表**登记 10 路径，但**方法注册表**（BY_MODULE_ROUTES）仅 6 对、
 *   handler/ 下仅三组 Routes（Alert / LimitUsage / Rule）+ risk_intraday 事中面（MirrorStatus）。
 *   ⇒ **可接 5 端点**（本页全部真实对接）：
 *     GET  /risk/alert                 告警历史查询（FR-RISK-19；筛选/分页/默认倒序/可见性隔离；since 补拉）
 *     POST /risk/alert/{id}/dispose    告警处置（FR-RISK-15/16；ALERT_ONLY/SUSPEND_OPEN/FORCE_CLOSE_HINT/RELEASE_SUSPEND）
 *     GET|POST /risk/rule              风控阈值查看 / 变更下发（T-063；FR-CONFIG-5/6）
 *     GET|POST /risk/limit-usage       限额使用统计 / 红线复算落库（T-066；FR-RISK-4/5/6/18）
 *     GET  /risk/mirror-status         事中镜像状态（T-065；只读）
 *   ⇒ **未注册 5 路径**（登记于 routes.js NOT_REGISTERED，**调用即 404**——本页保留 Tab 框架、
 *     数据面接「未注册」诚实提示，不臆造调用；缺口只报不改，属 T-108/T-118/T-119 层③链承接）：
 *     /risk/dashboard、/risk/limit-open、/risk/force-close-hint、/risk/violation、/risk/post-report
 *
 * ## 验收 3：WS 实时推送 + 断线重连补拉
 *   /ws/alert（NEVER_DROP：服务端慢消费将关闭连接 1013，由前端重连并经 REST 补拉）。
 *   页面状态机：连接徽标（已连接 / 已断线·自动重连中）；onMessage(type=alert) 推送入实时流
 *   （经 HG.ws.throttle 节流渲染，前端纪律 §5.3）；**重连成功（onOpen 且非首次）后拉一次
 *   GET /risk/alert?since=<最后收到的 tsMillis>** 补齐断线窗口（数据行并入实时流，标「补拉」来源）。
 *
 * ## 业务红线（写进页面逻辑）
 *   · R-RISK-5「只限开仓、不限平仓」：限制类处置不得阻断平仓——处置区固定提示（文案取自 RISK.md）；
 *   · R-RISK-13：系统不自动平仓——强制平仓提示**只提示**，实际平仓须交易岗人工确认后发出；
 *     本页无任何「自动下单」入口；强平提示的「接受/不接受」均留痕（不接受必填理由）；
 *   · R-RISK-11：告警不可静默——未处置告警显式标注「未处置」；历史记录只读，
 *     **本页无删除 / 编辑告警入口**（FR-RISK-19 验收 2/3）；
 *   · R-RISK-3 / 前端纪律：事前风控在 C++ 全内存，**前端不做任何风控判定**（只展示与提交）；
 *   · R-RISK-10：镜像状态非权威源（权威源是 C++ 内存），滞后超阈值须告警（lagging 显式警示）。
 *
 * ## 幂等纪律
 *   写操作（dispose / rule 变更 / limit-usage 复算）一律携带 Idempotency-Key（auth.js 先例）。
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
const RR = HG.routes.RISK;
const NR = HG.routes.NOT_REGISTERED;

/* ------------------------------------------------------------------ *
 * 取值域（以 DATA_MODEL TB-RISK-4 / RiskRecomputeService / RiskAlertService 实测为准）
 * ------------------------------------------------------------------ */
/** 告警类型（t_risk_alert.alert_type CHECK）。 */
const ALERT_TYPES = {
  POSITION: '实时持仓', FLOAT_PNL: '浮动盈亏', MARGIN_RATIO: '保证金占用率', RISK_RATIO: '风险度',
  AVAILABLE_FUND: '资金可用量', ABNORMAL_ORDER: '异常下单行为', MIRROR_LAG: '镜像滞后', FUND_QUOTA: '资金额度超限'
};
/** 告警级别（t_risk_alert.alert_level CHECK）。 */
const ALERT_LEVELS = { INFO: '提示', WARNING: '警告', CRITICAL: '严重' };
/** 处置动作（RiskAlertService.ACTION_*）。 */
const DISPOSE_ACTIONS = {
  ALERT_ONLY: '仅告警', SUSPEND_OPEN: '限制新开仓',
  FORCE_CLOSE_HINT: '强制平仓提示', RELEASE_SUSPEND: '解除限制'
};
/** 限额类型（RiskRecomputeService.LIMIT_TYPE_*）。 */
const LIMIT_TYPES = { SCALE_REDLINE: '规模红线', POSITION_LIMIT: '期限持仓限额' };
/** 提示级别（t_risk_limit_usage.hint_level CHECK）。 */
const HINT_LEVELS = { NORMAL: '正常', HINT: '提示（≥80%）', WARNING: '警告（≥95%）', EXCEEDED: '超红线（≥100%）' };

/** 告警级别徽标：严重=警示色、警告=冻结色、提示=常态（仅展示）。 */
function alertLevelHTML(level) {
  const t = ALERT_LEVELS[level] || level || '—';
  const cls = level === 'CRITICAL' ? 'danger' : (level === 'WARNING' ? 'frozen' : 'draft');
  return '<span class="hg-badge hg-badge--' + cls + '" data-alert-level="' + F.esc(level || '') + '">' + F.esc(t) + '</span>';
}

/** 处置状态徽标：未处置显式标注（R-RISK-11 不得静默）。 */
function disposedHTML(row) {
  if (row.disposed === 'Y') {
    return '<span class="hg-badge hg-badge--active">已处置</span>';
  }
  return '<span class="hg-badge hg-badge--danger" data-disposed="N">未处置</span>';
}

/** 镜像滞后徽标（lagging=true 显式警示——非权威源，R-RISK-10）。 */
function lagBadgeHTML(view) {
  return view && view.lagging
    ? '<span class="hg-badge hg-badge--danger" data-lag="lagging">滞后 ' + F.esc(String(view.lagMillis)) + ' ms（超阈值）</span>'
    : '<span class="hg-badge hg-badge--active">正常（' + F.esc(String((view && view.lagMillis) || 0)) + ' ms）</span>';
}

/** 红线横幅：只限开仓、不限平仓（文案取自 RISK.md R-RISK-5 原文要点）。 */
const OPEN_ONLY_BANNER_HTML = '<strong>只限开仓、不限平仓（R-RISK-5）</strong>：任何限制类处置'
  + '（限制新开仓、额度阻断）一律<strong>不得阻断平仓与风险处置类指令</strong>——否则风险无法释放；'
  + '处置须留痕、可复核、可人工解除。';

/** 红线横幅：系统不自动平仓（RISK.md R-RISK-13 / FR-RISK-16 原文要点）。 */
const NO_AUTO_CLOSE_BANNER_HTML = '<strong>系统只提示，不自动下单（R-RISK-13）</strong>：'
  + '实际平仓须由交易岗人工确认后发出；本页无任何自动下单入口。';

/** WS 推送语义说明（NEVER_DROP + 补拉，ws.js / WsConnection 实测）。 */
const WS_NOTE_HTML = '告警经 <code>/ws/alert</code> 实时推送（<strong>不允许丢</strong>）；'
  + '服务端消费不过来时会<strong>关闭连接（1013）</strong>，由前端自动重连并经 REST '
  + '<code>/risk/alert?since=</code> <strong>补拉</strong>断线窗口告警。';

function numInput(id, props) {
  return ui.el('input', Object.assign({ id: id, type: 'number', inputmode: 'numeric' }, props || {}));
}
function dateInput(id) { return ui.el('input', { id: id, type: 'date' }); }
function dtInput(id) { return ui.el('input', { id: id, type: 'datetime-local', step: '1' }); }

/** 当日本地日期（YYYY-MM-DD；仅用于 statDate 预填，不做业务判定）。 */
function todayStr() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
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

/* ------------------------------------------------------------------ *
 * Tab 1：告警与处置（FR-RISK-19 查询 + FR-RISK-15/16 处置）+ WS 实时推送
 * ------------------------------------------------------------------ */
function alertTab(container) {
  // —— WS 实时推送面板（验收 3）
  const wsCard = ui.el('section', { class: 'hg-card' });
  wsCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '实时告警推送（/ws/alert）' }));
  wsCard.appendChild(ui.banner(WS_NOTE_HTML, 'warn'));
  const wsBar = ui.el('div', { class: 'hg-toolbar' });
  const wsBadge = ui.el('span', { class: 'hg-badge hg-badge--draft', 'data-ws-state': 'init', text: '连接中…' });
  wsBar.appendChild(ui.el('span', { class: 'hg-pager__info', text: '连接状态：' }));
  wsBar.appendChild(wsBadge);
  wsCard.appendChild(wsBar);
  const feedBody = ui.el('div');
  feedBody.appendChild(ui.el('p', { class: 'hg-muted', text: '暂无推送（连接建立后实时告警将在此展示；断线重连后补拉的告警标注「补拉」来源）。' }));
  wsCard.appendChild(feedBody);
  container.appendChild(wsCard);

  const st = {
    connected: false, everConnected: false, backfilling: false,
    feed: [], seen: {}, lastTsMs: 0, listLoaded: false,
    page: 1, size: HG.api.DEFAULT_PAGE_SIZE
  };

  function renderWsBadge() {
    wsBadge.textContent = st.connected ? '已连接' : '已断线（自动重连中）';
    wsBadge.className = 'hg-badge ' + (st.connected ? 'hg-badge--active' : 'hg-badge--danger');
    wsBadge.setAttribute('data-ws-state', st.connected ? 'connected' : 'disconnected');
  }

  function feedRowOf(m, source) {
    return {
      level: m.level, metric: m.metric, value: m.value, threshold: m.threshold,
      subAccount: m.subAccount, ts: m.ts || '', tsMillis: m.tsMillis || 0, source: source
    };
  }

  function renderFeed() {
    ui.clear(feedBody);
    if (!st.feed.length) {
      feedBody.appendChild(ui.el('p', { class: 'hg-muted', text: '暂无推送（连接建立后实时告警将在此展示；断线重连后补拉的告警标注「补拉」来源）。' }));
      return;
    }
    feedBody.appendChild(ui.el('div', { html: ui.tableHTML(
      [
        { title: '级别', render: (r) => alertLevelHTML(r.level) },
        { title: '指标', render: (r) => F.esc(r.metric || '—') },
        { title: '当前值', num: true, render: (r) => F.esc(r.value || '—') },
        { title: '阈值', num: true, render: (r) => F.esc(r.threshold || '—') },
        { title: '子账户', num: true, render: (r) => F.esc(r.subAccount === undefined || r.subAccount === null ? '—' : String(r.subAccount)) },
        { title: '时间', render: (r) => F.esc(F.dateTime(r.ts) || String(r.tsMillis || '—')) },
        { title: '来源', render: (r) => (r.source === 'backfill' ? '<span class="hg-badge hg-badge--frozen">补拉</span>' : '<span class="hg-badge hg-badge--draft">推送</span>') },
        { title: '处置', render: () => '<span class="hg-muted">请在下方告警列表执行（处置留痕）</span>' }
      ],
      st.feed.slice(0, 100)
    ) }));
  }

  const renderFeedThrottled = HG.ws.throttle ? HG.ws.throttle(renderFeed, 300) : renderFeed;

  function ingest(rows, source) {
    let added = 0;
    (rows || []).forEach((raw) => {
      const m = raw && raw.type === 'alert' ? raw : raw; // REST 行与 WS 帧同字段名（camelCase 对齐）
      const key = [m.tsMillis || 0, m.metric || m.metricName || '', m.value || m.metricValue || '', m.subAccount, m.level || m.alertLevel || ''].join('|');
      if (st.seen[key]) return;
      st.seen[key] = true;
      st.feed.unshift(feedRowOf(m, source));
      added += 1;
      const ts = Number(m.tsMillis || 0);
      if (ts > st.lastTsMs) st.lastTsMs = ts;
    });
    if (st.feed.length > 100) st.feed.length = 100;
    return added;
  }

  /** 断线重连补拉（验收 3）：since=最后收到的 tsMillis；REST 行并入实时流（标「补拉」）。 */
  async function backfillAfterReconnect() {
    if (st.backfilling || !st.lastTsMs) return;
    st.backfilling = true;
    try {
      const data = await HG.api.get(RR.ALERT, { since: String(st.lastTsMs), page: 1, size: 100 });
      const items = (data && data.items) || [];
      const added = ingest(items.map((r) => ({
        level: r.alertLevel, metric: r.metricName, value: r.metricValue, threshold: r.thresholdValue,
        subAccount: r.subAccountId, ts: r.alertTime, tsMillis: r.alertTsMs
      })), 'backfill');
      renderFeed();
      if (added > 0) {
        ui.toast('断线窗口告警已补拉 ' + added + ' 条（since=' + st.lastTsMs + '）', 'ok');
        if (st.listLoaded) loadAlerts(st.page); // 列表已加载过则同步刷新
      }
    } catch (err) {
      ui.toast('断线补拉失败：' + ((err && err.message) || '未知错误') + '（可在告警列表手动查询）', 'error');
    } finally {
      st.backfilling = false;
    }
  }

  // 防僵尸连接：router 每次 select 重建面板 ⇒ 每次 alertTab 构建都建新连接，
  // 须先关闭上一次构建遗留的连接（模块级存活引用；close 置 closedByUser 停止其自动重连）
  if (HG.pages._riskAlertConn && typeof HG.pages._riskAlertConn.close === 'function') {
    HG.pages._riskAlertConn.close();
  }
  const conn = HG.ws.connect('alert', {
    onOpen: () => {
      const wasEver = st.everConnected;
      st.connected = true;
      st.everConnected = true;
      renderWsBadge();
      if (wasEver) backfillAfterReconnect(); // 仅「重连」补拉；首连以列表加载为准
    },
    onClose: () => { st.connected = false; renderWsBadge(); },
    onMessage: (msg) => {
      if (msg && msg.type === 'alert') {
        ingest([msg], 'push');
        renderFeedThrottled();
      }
    }
  });
  HG.pages._riskAlertConn = conn;

  // —— 告警历史查询（FR-RISK-19：筛选 / 分页 / 默认倒序 / 只读）
  const listCard = ui.el('section', { class: 'hg-card' });
  listCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '告警历史查询（FR-RISK-19）' }));
  listCard.appendChild(ui.banner(
    OPEN_ONLY_BANNER_HTML + '<br>' + NO_AUTO_CLOSE_BANNER_HTML + '<br>'
      + '<strong>告警不可静默（R-RISK-11）</strong>：未处置告警显式标注；历史记录只读——'
      + '<strong>本页不提供删除或编辑告警的入口</strong>（FR-RISK-19 验收 2/3）。',
    'warn'
  ));
  const aSub = numInput('rk-a-sub', { min: '1', placeholder: '可空' });
  const aType = ui.el('select', { id: 'rk-a-type' });
  aType.appendChild(ui.el('option', { value: '', text: '全部类型' }));
  Object.keys(ALERT_TYPES).forEach((k) => aType.appendChild(ui.el('option', { value: k, text: ALERT_TYPES[k] })));
  const aLevel = ui.el('select', { id: 'rk-a-level' });
  aLevel.appendChild(ui.el('option', { value: '', text: '全部级别' }));
  Object.keys(ALERT_LEVELS).forEach((k) => aLevel.appendChild(ui.el('option', { value: k, text: ALERT_LEVELS[k] })));
  const aDisposed = ui.el('select', { id: 'rk-a-disposed' });
  aDisposed.appendChild(ui.el('option', { value: '', text: '全部处置状态' }));
  aDisposed.appendChild(ui.el('option', { value: 'N', text: '未处置' }));
  aDisposed.appendChild(ui.el('option', { value: 'Y', text: '已处置' }));
  const aFrom = dtInput('rk-a-from');
  const aTo = dtInput('rk-a-to');
  const aBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询告警' });
  listCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'rk-a-sub', label: '子账户', control: aSub }),
    ui.field({ id: 'rk-a-type', label: '告警类型', control: aType }),
    ui.field({ id: 'rk-a-level', label: '告警级别', control: aLevel }),
    ui.field({ id: 'rk-a-disposed', label: '处置状态', control: aDisposed })
  ]));
  listCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'rk-a-from', label: '告警时间起', control: aFrom }),
    ui.field({ id: 'rk-a-to', label: '告警时间止', control: aTo }),
    aBtn
  ]));
  const aBody = ui.el('div');
  const aPager = ui.el('div', { class: 'hg-pager' });
  listCard.appendChild(aBody);
  listCard.appendChild(aPager);
  container.appendChild(listCard);

  async function loadAlerts(page) {
    st.page = page || 1;
    st.listLoaded = true;
    const q = { page: st.page, size: st.size }; // 排序不传：服务端缺省 alert_time desc（FR-RISK-19 倒序）
    if (aSub.value) q.subAccountId = aSub.value;
    if (aType.value) q.alertType = aType.value;
    if (aLevel.value) q.alertLevel = aLevel.value;
    if (aDisposed.value) q.disposed = aDisposed.value;
    if (aFrom.value) q.fromTime = aFrom.value.replace('T', 'T');
    if (aTo.value) q.toTime = aTo.value;
    ui.clear(aBody); ui.renderLoading(aBody, '正在查询告警历史…');
    try {
      const data = await HG.api.get(RR.ALERT, q);
      attachPager(aPager, { total: data.total, page: data.page, size: data.size }, loadAlerts);
      ui.clear(aBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(aBody, '该筛选条件下无告警记录。'); return; }
      aBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'id', title: 'ID', num: true },
          { key: 'subAccountId', title: '子账户', num: true },
          { title: '类型', render: (r) => F.esc(ALERT_TYPES[r.alertType] || r.alertType || '—') },
          { title: '级别', render: (r) => alertLevelHTML(r.alertLevel) },
          { title: '指标名', render: (r) => F.esc(r.metricName || '—') },
          { title: '指标值', num: true, render: (r) => F.esc(r.metricValue || '—') },
          { title: '阈值', num: true, render: (r) => F.esc(r.thresholdValue || '—') },
          { title: '合约', render: (r) => F.esc(r.instrumentId || '—') },
          { title: '详情', render: (r) => F.esc(r.alertDetail || '—') },
          { title: '告警时间', render: (r) => F.esc(F.dateTime(r.alertTime)) },
          { title: '处置状态', render: (r) => disposedHTML(r) },
          { title: '处置动作', render: (r) => F.esc(DISPOSE_ACTIONS[r.disposalType] || r.disposalType || '—') },
          { title: '处置人', num: true, render: (r) => F.esc(r.disposalOperatorId === undefined || r.disposalOperatorId === null ? '—' : String(r.disposalOperatorId)) },
          { title: '处置时间', render: (r) => F.esc(F.dateTime(r.disposalTime)) },
          { title: '下发状态', render: (r) => F.esc(r.dispatchStatus || '—') },
          { title: '限制解除', render: (r) => (r.released ? '<span class="hg-badge hg-badge--active">已解除</span>' : '—') }
        ],
        data.items
      ) }));
    } catch (err) { ui.renderError(aBody, err, () => loadAlerts(st.page)); }
  }
  aBtn.addEventListener('click', () => loadAlerts(1));

  // —— 告警处置（FR-RISK-15/16；危险操作：二次确认 + 幂等）
  const disposeCard = ui.el('section', { class: 'hg-card' });
  disposeCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '告警处置（POST /risk/alert/{id}/dispose）' }));
  disposeCard.appendChild(ui.banner(
    '处置动作四类：仅告警 / 限制新开仓 / 强制平仓提示 / 解除限制。'
      + '<strong>限制新开仓与解除限制将生成控制指令下发 C++ 内存</strong>（事前风控拒绝新开仓；平仓不受限，R-RISK-5）；'
      + '处置理由必填（留痕，FR-RISK-15）；每次提交自动携带幂等标识（Idempotency-Key）。',
    'warn'
  ));
  const dId = numInput('rk-d-id', { min: '1', placeholder: '告警 ID（列表首列）' });
  const dAction = ui.el('select', { id: 'rk-d-action' });
  Object.keys(DISPOSE_ACTIONS).forEach((k) => dAction.appendChild(ui.el('option', { value: k, text: DISPOSE_ACTIONS[k] })));
  const dReason = ui.el('input', { id: 'rk-d-reason', placeholder: '处置理由（必填，留痕）' });
  const dInstr = ui.el('input', { id: 'rk-d-instr', placeholder: '建议平仓合约（如 cu2506；强平提示时填）' });
  const dDir = ui.el('input', { id: 'rk-d-dir', placeholder: '建议方向（如 卖平）' });
  const dVol = numInput('rk-d-vol', { min: '1', placeholder: '建议数量（手）' });
  const dAccept = ui.el('select', { id: 'rk-d-accept' });
  dAccept.appendChild(ui.el('option', { value: '', text: '请选择（强平提示必选）' }));
  dAccept.appendChild(ui.el('option', { value: 'Y', text: '接受提示' }));
  dAccept.appendChild(ui.el('option', { value: 'N', text: '不接受（须填理由）' }));
  const dReject = ui.el('input', { id: 'rk-d-reject', placeholder: '不接受理由（hintAccepted=N 时必填）' });
  const dBtn = ui.el('button', { class: 'hg-btn hg-btn--danger', type: 'button', text: '提交处置' });
  disposeCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'rk-d-id', label: '告警 ID（必填）', required: true, control: dId }),
    ui.field({ id: 'rk-d-action', label: '处置动作', control: dAction }),
    ui.field({ id: 'rk-d-reason', label: '处置理由（必填）', required: true, control: dReason })
  ]));
  disposeCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'rk-d-instr', label: '建议平仓合约（强平提示）', control: dInstr }),
    ui.field({ id: 'rk-d-dir', label: '建议方向（强平提示）', control: dDir }),
    ui.field({ id: 'rk-d-vol', label: '建议数量（强平提示）', control: dVol }),
    ui.field({ id: 'rk-d-accept', label: '是否接受提示（强平提示）', control: dAccept }),
    ui.field({ id: 'rk-d-reject', label: '不接受理由', control: dReject }),
    dBtn
  ]));
  const dBody = ui.el('div');
  disposeCard.appendChild(dBody);
  container.appendChild(disposeCard);

  dBtn.addEventListener('click', async () => {
    if (!dId.value) { ui.toast('告警 ID 必填', 'error'); return; }
    if (!dReason.value.trim()) { ui.toast('处置理由必填（留痕，FR-RISK-15）', 'error'); return; }
    const action = dAction.value;
    const body = { action: action, reason: dReason.value.trim() };
    let confirmMsg = '确认对告警 #' + F.esc(dId.value) + ' 执行处置「' + F.esc(DISPOSE_ACTIONS[action]) + '」？'
      + '<br>' + OPEN_ONLY_BANNER_HTML;
    if (action === 'FORCE_CLOSE_HINT') {
      if (!dAccept.value) { ui.toast('强制平仓提示须显式接受或拒绝（hintAccepted=Y/N）', 'error'); return; }
      if (dAccept.value === 'N' && !dReject.value.trim()) { ui.toast('不接受提示须填写理由并留痕（FR-RISK-16 验收）', 'error'); return; }
      body.hintInstrumentId = dInstr.value.trim() || null;
      body.hintDirection = dDir.value.trim() || null;
      body.hintVolume = dVol.value ? Number(dVol.value) : null;
      body.hintAccepted = dAccept.value;
      body.rejectReason = dAccept.value === 'N' ? dReject.value.trim() : null;
      confirmMsg += '<br>' + NO_AUTO_CLOSE_BANNER_HTML
        + '<br>建议方案：合约 ' + F.esc(body.hintInstrumentId || '—') + ' / 方向 ' + F.esc(body.hintDirection || '—')
        + ' / 数量 ' + F.esc(body.hintVolume === null ? '—' : String(body.hintVolume))
        + '；提示处置为 <strong>' + (dAccept.value === 'Y' ? '接受' : '不接受（' + F.esc(body.rejectReason) + '）') + '</strong>。';
    }
    const go = await ui.confirm(confirmMsg, { title: '告警处置', danger: true });
    if (!go) return;
    ui.clear(dBody); ui.renderLoading(dBody, '正在提交处置…');
    try {
      const r = await HG.api.post(RR.dispose(dId.value), body, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(dBody);
      dBody.appendChild(ui.banner(
        '处置已留痕：处置 ID #' + F.esc(String(r.id)) + '，类型 <strong>' + F.esc(DISPOSE_ACTIONS[r.disposalType] || r.disposalType || '—') + '</strong>，'
          + '范围 ' + F.esc(r.scanScope || '—') + '（' + F.esc(r.isGlobal === 'Y' ? '全账户' : '子账户 #' + F.esc(String(r.subAccountId ?? '—'))) + '），'
          + '下发状态 ' + F.esc(r.dispatchStatus || '无需下发 / —') + '。', 'ok'));
      loadAlerts(st.page);
    } catch (err) { ui.renderError(dBody, err, () => dBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 2：事中镜像状态（GET /risk/mirror-status；只读，T-065）
 * ------------------------------------------------------------------ */
function mirrorTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '事中镜像状态（GET /risk/mirror-status）' }));
  card.appendChild(ui.banner(
    '<strong>镜像状态不是权威源</strong>（权威源是 C++ 内存，R-RISK-10）：监控只读，不得据本页数据做业务判定；'
      + '镜像<strong>滞后超过阈值须告警</strong>——lagging=true 时下方显式警示。'
      + '强制平仓提示在此为<strong>只读展示</strong>：系统只提示、不自动下单（R-RISK-13）。',
    'warn'
  ));
  const mBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '刷新镜像状态' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [mBtn]));
  const mBody = ui.el('div');
  card.appendChild(mBody);
  container.appendChild(card);

  async function load() {
    ui.clear(mBody); ui.renderLoading(mBody, '正在查询镜像状态…');
    try {
      const v = await HG.api.get(RR.MIRROR_STATUS, {});
      ui.clear(mBody);
      mBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { title: '项', render: (row) => F.esc(row[0]) },
          { title: '值', render: (row) => F.esc(row[1]) }
        ],
        [
          ['就绪状态', v.readyState || '—'],
          ['事件水位（watermarkEventSeq）', String(v.watermarkEventSeq ?? '—')],
          ['参数版本（paramVersion）', String(v.paramVersion ?? '—')],
          ['快照时间（takenAt）', F.esc(F.dateTime(v.takenAt)) || '—'],
          ['镜像滞后', ''],
          ['评估时刻（epoch ms）', String(v.evaluatedAtEpochMilli ?? '—')]
        ]
      ) }));
      // 滞后徽标独立一行（lagging 显式警示，R-RISK-10）
      const lagRow = ui.el('div');
      lagRow.appendChild(ui.el('div', { html: lagBadgeHTML(v) }));
      mBody.appendChild(lagRow);
      if (v.lagging) {
        mBody.appendChild(ui.banner('镜像滞后超过阈值（' + F.esc(String(v.lagMillis)) + ' ms）——镜像数据可能陈旧，须告警并谨慎采信（R-RISK-10）。', 'error'));
      }
      const agg = v.aggregate;
      if (agg) {
        mBody.appendChild(ui.el('h4', { text: '全账户聚合' }));
        mBody.appendChild(metricsTable([agg]));
      }
      if (v.subAccounts && v.subAccounts.length) {
        mBody.appendChild(ui.el('h4', { text: '子账户指标' }));
        mBody.appendChild(metricsTable(v.subAccounts));
      } else {
        mBody.appendChild(ui.el('p', { class: 'hg-muted', text: '无子账户指标（或尚未产生事件）。' }));
      }
      const suspended = Array.from(v.suspendedSubAccounts || []);
      if (suspended.length) {
        mBody.appendChild(ui.banner('当前限制新开仓的子账户：#' + suspended.map((s) => F.esc(String(s))).join('、') + '（平仓不受限，R-RISK-5）。', 'warn'));
      }
      if (v.forceCloseHints && v.forceCloseHints.length) {
        mBody.appendChild(ui.el('h4', { text: '强制平仓提示（只读；实际平仓须交易岗人工确认——R-RISK-13）' }));
        mBody.appendChild(ui.el('div', { html: ui.tableHTML(
          [
            { key: 'subAccountId', title: '子账户', num: true },
            { title: '建议合约', render: (r) => F.esc(r.instrumentId || '—') },
            { title: '建议方向', render: (r) => F.esc(r.direction || '—') },
            { title: '建议数量', num: true, render: (r) => F.esc(r.quantity || '—') },
            { title: '风险度', num: true, render: (r) => F.esc(F.pct(r.riskRatio)) },
            { title: '原因', render: (r) => F.esc(r.reason || '—') }
          ],
          v.forceCloseHints
        ) }));
      }
      if (v.alerts && v.alerts.length) {
        mBody.appendChild(ui.el('h4', { text: '事中活动告警（镜像内存态）' }));
        mBody.appendChild(ui.el('div', { html: ui.tableHTML(
          [
            { key: 'subAccountId', title: '子账户', num: true },
            { title: '类型', render: (r) => F.esc(r.alertType || '—') },
            { title: '级别', render: (r) => alertLevelHTML(r.level) },
            { title: '指标值', num: true, render: (r) => F.esc(r.value || '—') },
            { title: '阈值', num: true, render: (r) => F.esc(r.threshold || '—') },
            { title: '处置动作', render: (r) => F.esc(DISPOSE_ACTIONS[r.disposalAction] || r.disposalAction || '—') },
            { title: '处置状态', render: (r) => (r.disposed ? '<span class="hg-badge hg-badge--active">已处置</span>' : '<span class="hg-badge hg-badge--danger" data-disposed="N">未处置</span>') },
            { title: '触发时间', render: (r) => F.esc(F.dateTime(r.raisedAt)) },
            { title: '说明', render: (r) => F.esc(r.message || '—') }
          ],
          v.alerts
        ) }));
      }
    } catch (err) { ui.renderError(mBody, err, () => load()); }
  }
  mBtn.addEventListener('click', () => load());

  function metricsTable(rows) {
    return ui.el('div', { html: ui.tableHTML(
      [
        { key: 'subAccountId', title: '子账户', num: true },
        { title: '持仓数量', num: true, render: (r) => F.esc(F.qty(r.positionQty)) },
        { title: '浮动盈亏', num: true, render: (r) => F.esc(F.money(r.floatPnl)) },
        { title: '占用保证金', num: true, render: (r) => F.esc(F.money(r.marginOccupied)) },
        { title: '权益（balance）', num: true, render: (r) => F.esc(F.money(r.balance)) },
        { title: '可用资金', num: true, render: (r) => F.esc(F.money(r.available)) },
        { title: '风险度', num: true, render: (r) => F.esc(F.pct(r.riskRatio)) },
        { title: '保证金占用率', num: true, render: (r) => F.esc(F.pct(r.marginUsageRate)) },
        { title: '日内异常次数', num: true, render: (r) => F.esc(String(r.abnormalIntradayCount ?? '—')) },
        { title: '7 日滚动回合', num: true, render: (r) => F.esc(String(r.abnormalRollingRounds ?? '—')) },
        { title: '平均持仓天数', num: true, render: (r) => F.esc(r.averageHoldingDays || '—') }
      ],
      rows
    ) });
  }
}

/* ------------------------------------------------------------------ *
 * Tab 3：限额使用与红线复算（GET|POST /risk/limit-usage；FR-RISK-18/4/5/6）
 * ------------------------------------------------------------------ */
function limitUsageTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '限额使用统计（GET /risk/limit-usage）' }));
  card.appendChild(ui.banner(
    '<strong>三层限额（Q-16 / R-RISK-2）</strong>：① 子账户+品种（主维度）② 子账户+合约（可选）③ 账户+品种（总账户）；'
      + '任一超限即拒绝、额度不得跨品种 / 跨子账户串用。提示级别：提示（≥80%）/ 警告（≥95%）/ 超红线（≥100%）。'
      + '统计日期（statDate）<strong>必填</strong>（FR-RISK-18 按统计日期查询）。',
    'warn'
  ));
  const lDate = dateInput('rk-l-date');
  lDate.value = todayStr();
  const lSub = numInput('rk-l-sub', { min: '1', placeholder: '可空' });
  const lType = ui.el('select', { id: 'rk-l-type' });
  lType.appendChild(ui.el('option', { value: '', text: '全部类型' }));
  Object.keys(LIMIT_TYPES).forEach((k) => lType.appendChild(ui.el('option', { value: k, text: LIMIT_TYPES[k] })));
  const lHint = ui.el('select', { id: 'rk-l-hint' });
  lHint.appendChild(ui.el('option', { value: '', text: '全部级别' }));
  Object.keys(HINT_LEVELS).forEach((k) => lHint.appendChild(ui.el('option', { value: k, text: HINT_LEVELS[k] })));
  const lBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询限额使用' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'rk-l-date', label: '统计日期（必填）', required: true, control: lDate }),
    ui.field({ id: 'rk-l-sub', label: '子账户', control: lSub }),
    ui.field({ id: 'rk-l-type', label: '限额类型', control: lType }),
    ui.field({ id: 'rk-l-hint', label: '提示级别', control: lHint }),
    lBtn
  ]));
  const lBody = ui.el('div');
  const lPager = ui.el('div', { class: 'hg-pager' });
  card.appendChild(lBody);
  card.appendChild(lPager);
  container.appendChild(card);

  const lState = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE };

  async function loadUsage(page) {
    if (!lDate.value) { ui.toast('统计日期（statDate）必填（FR-RISK-18）', 'error'); return; }
    lState.page = page || 1;
    const q = { statDate: lDate.value, page: lState.page, size: lState.size };
    if (lSub.value) q.subAccountId = lSub.value;
    if (lType.value) q.limitType = lType.value;
    if (lHint.value) q.hintLevel = lHint.value;
    ui.clear(lBody); ui.renderLoading(lBody, '正在查询限额使用…');
    try {
      const data = await HG.api.get(RR.LIMIT_USAGE, q);
      attachPager(lPager, { total: data.total, page: data.page, size: data.size }, loadUsage);
      ui.clear(lBody);
      if (!data.items || !data.items.length) { ui.renderEmpty(lBody, '该筛选条件下无限额使用记录。'); return; }
      lBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'id', title: 'ID', num: true },
          { key: 'subAccountId', title: '子账户', num: true },
          { title: '品种', num: true, render: (r) => F.esc(r.productId === undefined || r.productId === null ? '—' : String(r.productId)) },
          { title: '统计日期', render: (r) => F.esc(F.date(r.statDate)) },
          { title: '限额类型', render: (r) => F.esc(LIMIT_TYPES[r.limitType] || r.limitType || '—') },
          { title: '限额值', num: true, render: (r) => F.esc(r.limitValue || '—') },
          { title: '已用', num: true, render: (r) => F.esc(r.usedValue || '—') },
          { title: '使用率', num: true, render: (r) => F.esc(F.pct(r.usageRatio)) },
          { title: '提示级别', render: (r) => hintBadgeHTML(r.hintLevel) }
        ],
        data.items
      ) }));
    } catch (err) { ui.renderError(lBody, err, () => loadUsage(lState.page)); }
  }
  lBtn.addEventListener('click', () => loadUsage(1));

  function hintBadgeHTML(level) {
    const t = HINT_LEVELS[level] || level || '—';
    const cls = level === 'EXCEEDED' ? 'danger' : (level === 'WARNING' ? 'frozen' : (level === 'HINT' ? 'pending' : 'active'));
    return '<span class="hg-badge hg-badge--' + cls + '" data-hint-level="' + F.esc(level || '') + '">' + F.esc(t) + '</span>';
  }

  // —— 红线复算（POST /risk/limit-usage；写操作：二次确认 + 幂等）
  const rcCard = ui.el('section', { class: 'hg-card' });
  rcCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '红线复算（POST /risk/limit-usage）' }));
  rcCard.appendChild(ui.banner(
    '触发规模红线 / 期限持仓复算并落库统计（FR-RISK-18）；<strong>触及规模红线即阻断新开仓</strong>'
      + '（平仓与风险处置不受限，R-RISK-4/5）。statDate 可空 = 缺省当日（服务端口径）。'
      + '每次提交自动携带幂等标识。',
    'warn'
  ));
  const rcDate = dateInput('rk-rc-date');
  const rcBtn = ui.el('button', { class: 'hg-btn hg-btn--danger', type: 'button', text: '执行红线复算' });
  rcCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'rk-rc-date', label: '统计日期（可空=当日）', control: rcDate }),
    rcBtn
  ]));
  const rcBody = ui.el('div');
  rcCard.appendChild(rcBody);
  container.appendChild(rcCard);

  rcBtn.addEventListener('click', async () => {
    const go = await ui.confirm(
      '确认执行红线复算' + (rcDate.value ? '（统计日期 ' + F.esc(rcDate.value) + '）' : '（缺省当日）') + '？'
        + '<br>复算结果落库留痕；触及规模红线将阻断新开仓（平仓不受限）。',
      { title: '红线复算', danger: true });
    if (!go) return;
    ui.clear(rcBody); ui.renderLoading(rcBody, '正在执行红线复算…');
    try {
      const r = await HG.api.post(RR.LIMIT_USAGE, { statDate: rcDate.value || null }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(rcBody);
      const breach = (r.scaleBreachCount || 0) + (r.positionBreachCount || 0);
      rcBody.appendChild(ui.banner(
        '复算完成（统计日期 ' + F.esc(F.date(r.statDate) || '—') + '）：'
          + '规模红线 ' + F.esc(String(r.scaleRows ?? '—')) + ' 行 / 超限 <strong>' + F.esc(String(r.scaleBreachCount ?? 0)) + '</strong> / 未判定 ' + F.esc(String(r.scaleUnjudgedCount ?? 0))
          + '；期限持仓 ' + F.esc(String(r.positionRows ?? '—')) + ' 行 / 超限 <strong>' + F.esc(String(r.positionBreachCount ?? 0)) + '</strong>'
          + '；期限校验 ' + F.esc(String(r.termChecked ?? 0)) + ' 项（警告 ' + F.esc(String(r.termWarnCount ?? 0)) + ' / 阻断 ' + F.esc(String(r.termBlockCount ?? 0)) + '）。'
          + (breach > 0 ? '——<strong>存在超限，新开仓将被阻断（平仓不受限）</strong>。' : ''),
        breach > 0 ? 'error' : 'ok'
      ));
      if (r.hits && r.hits.length) {
        rcBody.appendChild(ui.el('h4', { text: '超限明细' }));
        rcBody.appendChild(ui.el('pre', { class: 'hg-pre', text: JSON.stringify(r.hits, null, 2) }));
      }
      loadUsage(1);
    } catch (err) { ui.renderError(rcBody, err, () => rcBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 4：风控规则 / 阈值（GET|POST /risk/rule；T-063；FR-CONFIG-5/6）
 * ------------------------------------------------------------------ */
function ruleTab(container) {
  const card = ui.el('section', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '风控阈值清单（GET /risk/rule）' }));
  card.appendChild(ui.banner(
    '事前风控阈值集中维护（FR-CONFIG-5）；阈值变更即下发（FR-CONFIG-6）。'
      + '<strong>放宽合规红线须带复核人（reviewerId）且不得等于操作人</strong>，否则后端拒绝（R-CONFIG-8，错误码 10012）。'
      + '本页只做展示与提交，阈值判定在后端 / C++。',
    'warn'
  ));
  const gBtn = ui.el('button', { class: 'hg-btn', type: 'button', text: '查询阈值清单' });
  card.appendChild(ui.el('div', { class: 'hg-form-actions' }, [gBtn]));
  const gBody = ui.el('div');
  card.appendChild(gBody);
  container.appendChild(card);

  async function loadRules() {
    ui.clear(gBody); ui.renderLoading(gBody, '正在查询风控阈值…');
    try {
      const items = await HG.api.get(RR.RULE, {});
      ui.clear(gBody);
      const list = Array.isArray(items) ? items : ((items && items.items) || []);
      if (!list.length) { ui.renderEmpty(gBody, '暂无阈值配置（后端返回空列表）。'); return; }
      gBody.appendChild(ui.el('div', { html: ui.tableHTML(
        [
          { key: 'thresholdKey', title: '配置键' },
          { title: '当前值', num: true, render: (r) => F.esc(r.thresholdValue || '—') },
          { title: '默认值', num: true, render: (r) => F.esc(r.defaultValue || '—') },
          { title: '取值类型', render: (r) => F.esc(r.valueType || '—') },
          { title: '合规红线', render: (r) => (r.isComplianceRedline === 'Y' ? '<span class="hg-badge hg-badge--danger">合规红线</span>' : '<span class="hg-badge hg-badge--draft">否</span>') },
          { title: '下发状态', render: (r) => F.esc(r.dispatchStatus || '—') },
          { title: '下限 / 上限', render: (r) => F.esc((r.minValue || '—') + ' ~ ' + (r.maxValue || '—')) },
          { title: '说明', render: (r) => F.esc(r.description || '—') }
        ],
        list
      ) }));
    } catch (err) { ui.renderError(gBody, err, () => loadRules()); }
  }
  gBtn.addEventListener('click', () => loadRules());

  const setCard = ui.el('section', { class: 'hg-card' });
  setCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '变更阈值并下发（POST /risk/rule）' }));
  const sKey = ui.el('input', { id: 'rk-s-key', placeholder: '配置键（thresholdKey，如 risk.freq.intraday）' });
  const sValue = ui.el('input', { id: 'rk-s-value', placeholder: '新取值（字符串化十进制 / 枚举值）' });
  const sReviewer = numInput('rk-s-reviewer', { min: '1', placeholder: '复核人用户 ID（放宽合规红线时必填）' });
  const sReason = ui.el('input', { id: 'rk-s-reason', placeholder: '变更原因（留痕）' });
  const sBtn = ui.el('button', { class: 'hg-btn hg-btn--danger', type: 'button', text: '提交变更' });
  setCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'rk-s-key', label: '配置键（必填）', required: true, control: sKey }),
    ui.field({ id: 'rk-s-value', label: '新取值（必填）', required: true, control: sValue })
  ]));
  setCard.appendChild(ui.el('div', { class: 'hg-form-actions' }, [
    ui.field({ id: 'rk-s-reviewer', label: '复核人（放宽合规红线必填）', control: sReviewer }),
    ui.field({ id: 'rk-s-reason', label: '变更原因', control: sReason }),
    sBtn
  ]));
  const sBody = ui.el('div');
  setCard.appendChild(sBody);
  container.appendChild(setCard);

  sBtn.addEventListener('click', async () => {
    if (!sKey.value.trim()) { ui.toast('配置键（thresholdKey）必填', 'error'); return; }
    if (!sValue.value.trim()) { ui.toast('新取值（newValue）必填', 'error'); return; }
    const go = await ui.confirm(
      '确认变更阈值 <code>' + F.esc(sKey.value.trim()) + '</code> → <strong>' + F.esc(sValue.value.trim()) + '</strong>？'
        + '<br>变更即下发（FR-CONFIG-6）；放宽合规红线须复核人且不得为操作人（R-CONFIG-8）。',
      { title: '阈值变更', danger: true });
    if (!go) return;
    ui.clear(sBody); ui.renderLoading(sBody, '正在提交阈值变更…');
    try {
      const r = await HG.api.post(RR.RULE, {
        thresholdKey: sKey.value.trim(), newValue: sValue.value.trim(),
        reviewerId: sReviewer.value ? Number(sReviewer.value) : null,
        reason: sReason.value.trim() || null
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(sBody);
      sBody.appendChild(ui.banner(
        '阈值已变更并下发：<code>' + F.esc(r.thresholdKey || sKey.value.trim()) + '</code> = <strong>'
          + F.esc(r.thresholdValue || sValue.value.trim()) + '</strong>（下发状态 ' + F.esc(r.dispatchStatus || '—') + '）。', 'ok'));
      loadRules();
    } catch (err) { ui.renderError(sBody, err, () => sBtn.click()); }
  });
}

/* ------------------------------------------------------------------ *
 * Tab 5~9：未注册端点（页面框架保留 + 「未注册」诚实提示，不臆造调用）
 *   /risk/dashboard、/risk/limit-open、/risk/force-close-hint、/risk/violation、/risk/post-report
 *   —— ApiRoutes 路径表已登记但方法注册表无对应 (方法, 路径) 对、handler/ 无 Handler ⇒ 调用即 404。
 *   处置对齐 routes.js NOT_REGISTERED 语义；缺口只报不改（T-108/T-118/T-119 层③链承接）。
 * ------------------------------------------------------------------ */
function unregisteredTab(title, pathConst, plannedDesc, frRef) {
  return function build(container) {
    const card = ui.el('section', { class: 'hg-card' });
    card.appendChild(ui.el('h3', { class: 'hg-card__title', text: title }));
    card.appendChild(ui.banner(
      '<strong>端点未注册（NOT_REGISTERED）</strong>：<code>' + F.esc(pathConst) + '</code> 已在 ApiRoutes 路径表登记，'
        + '但<strong>未注册任何 (方法, 路径) 对（handler/ 下无对应 Handler）——调用即 404</strong>。'
        + '本页保留框架、<strong>不发起调用</strong>（routes.js NOT_REGISTERED 语义：未注册路径禁止调用）；'
        + '后端缺口只报不改，属 T-108 / T-118 / T-119 层③链或后续立号承接。',
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
  { key: 'alert', label: '告警与处置', build: alertTab },
  { key: 'mirror', label: '镜像状态', build: mirrorTab },
  { key: 'limit', label: '限额使用', build: limitUsageTab },
  { key: 'rule', label: '风控规则', build: ruleTab },
  { key: 'dashboard', label: '风险仪表盘（未注册）', build: unregisteredTab('风险仪表盘', NR.RISK_DASHBOARD, '聚合展示事前 / 事中风险指标总览', 'FR-RISK-12/13/14 规划') },
  { key: 'limitOpen', label: '限制开仓（未注册）', build: unregisteredTab('限制开仓列表', NR.RISK_LIMIT_OPEN, '限制新开仓处置的生效视图（含解除入口规划）', 'FR-RISK-15 规划') },
  { key: 'forceClose', label: '强制平仓提示（未注册）', build: unregisteredTab('强制平仓提示列表', NR.RISK_FORCE_CLOSE_HINT, '强平提示历史与接受 / 不接受处置（镜像状态页已有内存态只读展示）', 'FR-RISK-16 规划') },
  { key: 'violation', label: '违规事项（未注册）', build: unregisteredTab('违规事项', NR.RISK_VIOLATION, '事前风控拒绝记录与异常交易清单', 'FR-RISK-11/14 规划') },
  { key: 'postReport', label: '事后报表（未注册）', build: unregisteredTab('事后风险报表', NR.RISK_POST_REPORT, '风险事后分析报表', 'FR-RISK-17 规划') }
];

function renderRisk(root, ctx) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '风险控制（RISK）' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '告警查询与处置 / 实时推送（/ws/alert）+ 断线补拉 / 事中镜像状态 / 限额使用与红线复算 / 风控阈值（FR-RISK-1~19）。' }));
  root.appendChild(ui.banner(
    '<strong>界面边界声明</strong>：本页仅提供界面，<strong>不做任何风控判定</strong>——事前风控在 C++ 全内存'
      + '（R-RISK-3），镜像状态非权威源（R-RISK-10），阈值 / 红线 / 处置生效判定一律由后端与 C++ 负责。'
      + '当前可对接 REST 端点 <strong>5 个</strong>（alert / dispose / rule / limit-usage / mirror-status）；'
      + '另有 <strong>5 个路径未注册</strong>（dashboard / limit-open / force-close-hint / violation / post-report）——'
      + '对应 Tab 保留框架并显式提示，不臆造调用。',
    'warn'
  ));

  const tabBar = ui.el('div', { class: 'hg-tabs', role: 'tablist' });
  const panel = ui.el('div');
  let active = (ctx && ctx.query && ctx.query.tab) || 'alert';
  if (!TABS.some((t) => t.key === active)) active = 'alert';

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
    const b = ui.el('button', { type: 'button', role: 'tab', text: t.label, onclick: () => HG.router.navigate('/risk?tab=' + t.key) });
    b.dataset.key = t.key;
    tabBar.appendChild(b);
  });

  root.appendChild(tabBar);
  root.appendChild(panel);
  select(active);
}

HG.pages = HG.pages || {};
HG.pages.risk = { renderRisk };
HG.router.register('/risk', renderRisk);
export default HG.pages.risk;
