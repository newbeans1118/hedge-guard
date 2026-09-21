/*
 * 结算页面（SETTLE）—— 开工骨架（M2，FR-SETTLE-1~17）。
 * 关键约束：子账户独立结算 + 加总对账（Σ 子账户 = 真实账户）；加总不一致仅阻断差异所属子账户（Q-11），不阻断平仓。
 */
import { HG } from '../core/ns.js';

HG.router.register('/settle', HG.pages.stub.renderStub({
  code: 'SETTLE', name: '结算', fr: 'FR-SETTLE-1~17', milestone: 'M2',
  apiPaths: ['/settle/ctp-data', '/settle/cycle', '/settle/cycle/{id}/reconcile', '/settle/cycle/{id}/aggregate', '/settle/cycle/{id}/close', '/settle/difference', '/settle/report', '/settle/margin', '/settle/stress-test', '/settle/monthly-check', '/settle/quarterly-report', '/settle/export'],
  notes: [
    '加总对账不一致（<code>10013</code>）：须明示**阻断范围**（仅差异所属子账户，不含平仓）。',
    '核对任务与电子签署（<code>FR-SETTLE-17</code> / <code>Q-14</code>）：展示快照哈希、逾期未签、签署 IP / 时间；对非应签角色 / 代签给出明确拒绝。'
  ]
}));

export default HG.pages.stub;
