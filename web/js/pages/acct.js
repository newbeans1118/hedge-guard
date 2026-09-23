/*
 * 套保会计页面（ACCT）—— 开工骨架（M4，FR-ACCT-1~14）。
 * 关键约束：套保效果页两栏同屏（NFR-USE-5：CSS 并排，不分页不跳转）；双侧盈亏同时上报；凭证标注"待企业财务复核入账"。
 */
import { HG } from '../core/ns.js';
import '../core/router.js';
import './_stub.js';

HG.router.register('/acct', HG.pages.stub.renderStub({
  code: 'ACCT', name: '套保会计', fr: 'FR-ACCT-1~14', milestone: 'M4',
  apiPaths: ['/acct/hedge-relation', '/acct/effectiveness', '/acct/rebalance', '/acct/voucher', '/acct/voucher/{id}/review', '/acct/voucher/{id}/reverse', '/acct/period', '/acct/period/{id}/close', '/acct/effect', '/acct/report/dual-pnl', '/acct/selfcheck', '/acct/export'],
  notes: [
    '套保效果页<strong>两栏同屏</strong>（<code>NFR-USE-5</code>）：即期 / 远期（或敞口侧 / 工具侧）CSS 并排，不分页不跳转。',
    '凭证为草稿 + 科目建议，不做总账过账，标注"待企业财务复核入账"（<code>Q-9</code>）。',
    '期间已关账（<code>10016</code>）产生新凭证时须提示通过下期调整。'
  ]
}));

export default HG.pages.stub;
