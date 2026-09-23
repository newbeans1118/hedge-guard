/*
 * 资金管理页面（FUND）—— 开工骨架（M2，FR-FUND-1~17）。
 * 关键约束：子账户间划拨须"记账 + 加总校验 + 回滚"；出入金经 CTP 异步回执 + 幂等 + 超时待查。
 */
import { HG } from '../core/ns.js';
import '../core/router.js';
import './_stub.js';

HG.router.register('/fund', HG.pages.stub.renderStub({
  code: 'FUND', name: '资金管理', fr: 'FR-FUND-1~17', milestone: 'M2',
  apiPaths: ['/fund/ledger', '/fund/flow', '/fund/deposit', '/fund/withdraw', '/fund/{id}/review', '/fund/transfer', '/fund/transfer/{id}/approve', '/fund/receipt', '/fund/quota', '/fund/margin', '/fund/reconcile', '/fund/export'],
  notes: [
    '出入金 / 划拨为写类接口，<strong>必须带 <code>Idempotency-Key</code></strong>（§4.1.1）。',
    '划拨后须校验"加总不变"（Σ 子账户 = 真实账户）；失败回滚并提示。',
    '敏感字段（如 CTP 口令）<strong>零落盘</strong>，界面不得回显（<code>NFR-SEC-1</code>）。'
  ]
}));

export default HG.pages.stub;
