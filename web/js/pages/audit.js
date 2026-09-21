/*
 * 审计页面（AUDIT）—— 开工骨架（M3，FR-AUDIT-1~13）。
 * 关键约束：存在断点时不得输出"链条完整"结论（R-AUDIT-2）；审计导出失败/防篡改校验未过须阻断（10017/10018）。
 */
import { HG } from '../core/ns.js';

HG.router.register('/audit', HG.pages.stub.renderStub({
  code: 'AUDIT', name: '审计', fr: 'FR-AUDIT-1~13', milestone: 'M3',
  apiPaths: ['/audit/trace/forward', '/audit/trace/backward', '/audit/trace/integrity', '/audit/violation-screen', '/audit/report/quarterly', '/audit/report/annual', '/audit/report/major-event', '/audit/evidence/export', '/audit/log', '/audit/log/verify', '/audit/operation-log'],
  notes: [
    '正向 / 反向追溯断裂点须显式标记；断点存在时<strong>不得</strong>输出"链条完整"（<code>R-AUDIT-2</code>）。',
    '核对任务与签署记录查询（<code>FR-AUDIT-13</code> / <code>Q-14</code>）：展示签署 IP / 时间，明确拒绝非应签角色与代签。',
    '审计日志写入失败（<code>10017</code>）须<strong>中断本次导出</strong>并告警。'
  ]
}));

export default HG.pages.stub;
