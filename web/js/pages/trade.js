/*
 * 交易执行页面（TRADE）—— 下单入口骨架。
 * 里程碑：M2（后端 T-046 起）。本页当前为**开工骨架**：
 *   - 承接来自套保计划列表/详情的「下单」按钮预填（NFR-USE-2：计划ID / 子账户 / 品种 / 方向）；
 *   - 展示 ≤5 步下单流程与交易理由结构化必填（Q-13）骨架；
 *   - 行情推送（/ws/quote）与提交指令的实际调用在 T-053 完成后接线。
 * ⚠️ 诚实登记：本页未完成——未调用 /trade/order（t_trade_order 字段级契约在 M2 冻结后接线）。
 * 路径来源：ApiRoutes.java（/trade/order、/trade/quote/{instrument}）。
 */
import { HG } from '../core/ns.js';

const ui = HG.ui;
const F = HG.format;

const HEDGE_DIR_TO_TRADE = { BUY_HEDGE: 'SELL', SELL_HEDGE: 'BUY' };

function renderOrder(root, ctx) {
  const q = ctx.query || {};
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '交易下单（预填）' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '从套保计划"下单"进入时，计划ID / 子账户 / 品种 / 方向已自动预填（NFR-USE-2：≤ 5 步提交指令）。' }));

  root.appendChild(ui.banner(
    '<strong>开工骨架</strong>：本页为 M2 交易执行的界面骨架。<code>/trade/**</code> 端点<strong>尚未注册</strong>' +
    '（见 Q-FE-001），故本页<strong>不发起任何下单请求</strong>；仅演示「从套保计划下单」的预填与 ≤5 步流程。',
    'warn'
  ));

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '下单预填参数' }));

  const tradeDir = HEDGE_DIR_TO_TRADE[q.direction] || q.direction || '—';
  const dl = ui.el('dl', { class: 'hg-desc' });
  [['套保计划 ID (hedge_plan_id)', q.planId || '（缺失）'],
   ['交易计划 ID (trade_plan_id)', q.tradePlanId || '（未指定）'],
   ['子账户 (sub_account_id)', q.subAccountId || '（缺失）'],
   ['品种 (product_id)', q.productId || '（缺失）'],
   ['套保方向', q.direction || '（缺失）'],
   ['推导下单方向', tradeDir],
   ['合约 (instrument_id)', q.instrumentId || '（未指定，待选合约）']
  ].forEach((r) => { dl.appendChild(ui.el('dt', { text: r[0] })); dl.appendChild(ui.el('dd', { text: String(r[1]) })); });
  card.appendChild(dl);
  root.appendChild(card);

  const flow = ui.el('div', { class: 'hg-card' });
  flow.appendChild(ui.el('h3', { class: 'hg-card__title', text: '下单流程（≤ 5 步）' }));
  const steps = ui.el('ol', { class: 'hg-steps' });
  ['选计划（预填）', '填数量/价格', '填交易理由（结构化必填）', '确认（价格偏离二次确认）', '提交'].forEach((t) => steps.appendChild(ui.el('li', { text: t })));
  flow.appendChild(steps);
  flow.appendChild(ui.el('p', { class: 'hg-muted', text: '交易理由结构化必填（Q-13）：关联类型 + 关联对象（计划 ID + 实货敞口 ID，须同子账户）+ 时点说明，可选自由文本；校验不通过即拒绝。' }));
  flow.appendChild(ui.el('p', { class: 'hg-muted', text: '价格偏离两档（Q-15）：基准 = 昨结算价；(2%,5%] 告警 + 二次确认；> 5% 阻断（后端返回 0x1010 / 0x1018，前端按阻断四项渲染）。' }));
  root.appendChild(flow);

  const back = ui.el('div', { class: 'hg-form-actions' }, [
    ui.el('a', { class: 'hg-btn hg-btn--ghost', href: '#/plan', text: '返回套保计划' })
  ]);
  root.appendChild(back);
}

HG.pages = HG.pages || {};
HG.pages.trade = { renderOrder };
HG.router.register('/trade', renderOrder);
export default HG.pages.trade;
