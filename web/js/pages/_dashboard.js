/*
 * 工作台（易用性入口 NFR-USE-1 / NFR-USE-2）。
 * - 提供「录入现货 → 实货敞口」与「计划 → 下单」的显著入口（减少步数）。
 * - 概览只调用**已注册**端点（GET /product、GET /config/sub-account），避免对未注册端点发无效请求。
 */
import { HG } from '../core/ns.js';
import '../core/routes.js';

const ui = HG.ui;

function quickCard(title, desc, href, cta) {
  return ui.el('div', { class: 'hg-card' }, [
    ui.el('h3', { class: 'hg-card__title', text: title }),
    ui.el('p', { class: 'hg-muted', text: desc }),
    ui.el('a', { class: 'hg-btn', href, text: cta })
  ]);
}

function render(root) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '工作台' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '期现一体：先有实货敞口，再有套保计划，后有交易。' }));

  const quick = ui.el('div', { class: 'hg-grid-2' });
  quick.appendChild(quickCard('录入现货', '分步表单录入采购/销售合同与现货价（新用户 30 分钟内可完成录入）。', '#/spot', '开始录入现货'));
  quick.appendChild(quickCard('实货敞口', '按子账户 × 品种 × 方向查看敞口；三分量与合计同时可见。', '#/spot/exposure', '查看敞口'));
  quick.appendChild(quickCard('套保计划', '按计划 ID 直达详情；列表查询接口未就绪（见页面说明）。', '#/plan', '查看套保计划'));
  root.appendChild(quick);

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '系统就绪概览' }));
  const body = ui.el('div');
  card.appendChild(body);
  root.appendChild(card);

  function load() {
    ui.renderLoading(body, '正在加载概览…');
    Promise.all([
      HG.api.get(HG.routes.PRODUCT.LIST).then((d) => ({ ok: true, n: (Array.isArray(d) ? d : (d && d.items) || []).length }))
        .catch((e) => ({ ok: false, e })),
      Promise.resolve({ ok: true, n: HG.session.state.subAccounts.length })
    ]).then((res) => {
      ui.clear(body);
      const comps = ui.el('div', { class: 'hg-components' });
      comps.appendChild(metric('已授权子账户', res[1].n, true));
      comps.appendChild(metric('品种数', res[0].ok ? res[0].n : '读取失败', false));
      body.appendChild(comps);
      if (!res[0].ok) body.appendChild(ui.el('div', { html: ui.blockedHTML(res[0].e) }));
      body.appendChild(ui.el('p', { class: 'hg-muted', text: '概览仅使用已注册端点（GET /product、GET /config/sub-account）。风险/交易/结算等模块接口未就绪，未在此调用。' }));
    });
  }
  function metric(label, value, isTotal) {
    return ui.el('div', { class: 'hg-metric' + (isTotal ? ' hg-metric--total' : '') }, [
      ui.el('div', { class: 'hg-metric__label', text: label }),
      ui.el('div', { class: 'hg-metric__value', text: String(value) })
    ]);
  }
  load();
}

HG.pages = HG.pages || {};
HG.pages.dashboard = { render };
export default HG.pages.dashboard;
