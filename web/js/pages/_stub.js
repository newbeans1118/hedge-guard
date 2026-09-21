/*
 * 模块页「开工骨架」通用渲染（诚实登记未完成项）。
 * 用途：主干页面（现货 / 实货敞口 / 套保计划 / 登录 / 主框架）之外的模块，
 * 在各自 FR 的字段级契约就绪前先登记页面入口、需求范围与后端路径，避免"看不出进度差"。
 */
import { HG } from '../core/ns.js';

const ui = HG.ui;

/**
 * @param {object} spec { code, name, fr, milestone, apiPaths:[], notes:[] }
 */
function renderStub(spec) {
  return function render(root) {
    root.appendChild(ui.el('h2', { class: 'hg-page__title', text: spec.name + '（' + spec.code + '）' }));
    root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '该模块页面为开工骨架：已登记入口与契约边界，业务界面待对应后端接口就绪后铺开。' }));

    root.appendChild(ui.banner(
      '<strong>诚实登记</strong>：本页<strong>尚未完成</strong>业务界面与接口对接。' +
      '主干已交付：登录页 / 主框架（子账户·汇总切换）/ 现货录入与实货敞口 / 套保计划列表与详情。',
      'warn'
    ));

    const card = ui.el('div', { class: 'hg-card' });
    card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '范围与契约' }));
    const dl = ui.el('dl', { class: 'hg-desc' });
    [['模块代号', spec.code],
     ['里程碑', spec.milestone || '—'],
     ['需求范围', spec.fr || '—'],
     ['通信边界', '仅 /api/v1/** 与 /ws/**（不直连数据库、不直连 C++ 组件）'],
     ['后端路径（ApiRoutes.java）', (spec.apiPaths || []).join('、') || '—']
    ].forEach((r) => { dl.appendChild(ui.el('dt', { text: r[0] })); dl.appendChild(ui.el('dd', { text: r[1] })); });
    card.appendChild(dl);
    root.appendChild(card);

    if (spec.notes && spec.notes.length) {
      const nc = ui.el('div', { class: 'hg-card' });
      nc.appendChild(ui.el('h3', { class: 'hg-card__title', text: '关键界面约束（就绪后须落实）' }));
      const ul = ui.el('ul');
      spec.notes.forEach((n) => ul.appendChild(ui.el('li', { html: n })));
      nc.appendChild(ul);
      root.appendChild(nc);
    }
  };
}

HG.pages = HG.pages || {};
HG.pages.stub = { renderStub };
export default HG.pages.stub;
